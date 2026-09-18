package com.wisesoft.wenqu.repositories;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.wenqu.models.TaskRecord;
import com.wisesoft.wenqu.repository.port.TaskRecordMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 持久任务仓储。
 *
 * <p>由参考实现的 repositories/task_repository.py 逐方法翻译：待发布 intent 的创建与去重、
 * worker 租约的取得/续租/检查/释放、owner 行锁内的领域 checkpoint 写、终态提交、失联收敛与清理。
 *
 * <p>必要替换（均已在类注释或方法注释标注）：
 * <ul>
 *   <li>{@code pg_advisory_xact_lock(hashtext('durable-task-capacity'))} → MySQL 命名锁
 *       {@code GET_LOCK/RELEASE_LOCK}。参考实现是事务级建议锁，MySQL 是会话级；两者都用于串行化
 *       并发上限判定，差别仅在锁的持有粒度。
 *   <li>{@code now()} 缺省取数据库时钟 {@code clock_timestamp()}（UTC）→ {@code SELECT UTC_TIMESTAMP()}。
 *   <li>JSON 路径过滤 {@code payload[key].as_string() == value} →
 *       {@code JSON_UNQUOTE(JSON_EXTRACT(payload, '?.key')) = ?}（路径参数化，不做字符串拼接）。
 *   <li>{@code begin_nested()}（保存点）→ 直接插入并捕获唯一键冲突。MySQL 不会因单条语句失败使整个
 *       事务进入不可用状态（PostgreSQL 会），故无需保存点即语义等价。
 *   <li>{@code setattr(record, key, value)} → 实体列的显式白名单；未知键直接报错而非静默忽略
 *       （Java 无鸭子类型，静默忽略会掩盖拼写错误；参考实现的所有调用点只传列名）。
 *   <li>「owner 在操作期间丢失则回滚」的两处（{@code run_owned_transaction} / {@code finish_owned}）
 *       用编程式事务实现显式回滚——注解式事务只能靠抛异常回滚，而参考实现是回滚后返回 False。
 *   <li>参考实现的 {@code *_in_session}（复用调用方会话）与自带会话两个入口，在本工程统一为
 *       事务传播 REQUIRED —— 调用方有事务即加入，语义一致。
 *   <li>{@code before_* / operation} 钩子（参考实现接收 session）在本工程不接收 session：
 *       钩子在当前事务内执行，可直接使用其他仓储。
 * </ul>
 */
@Repository
public class TaskRepository {

    /** 任务终态集合。 */
    public static final Set<String> TERMINAL_TASK_STATUSES = Set.of("success", "failed", "cancelled");

    /** 参考实现的 {@code _UNSET}：区分「未传 result」与「显式传 None」。 */
    private static final Object UNSET = new Object();

    private final TaskRecordMapper taskMapper;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    public TaskRepository(
            TaskRecordMapper taskMapper, JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.taskMapper = taskMapper;
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 提交终态前/取消前的领域钩子。 */
    @FunctionalInterface
    public interface BeforeTaskChange {
        void apply(TaskRecord record);
    }

    /** 收敛失败前的领域钩子（携带失败原因）。 */
    @FunctionalInterface
    public interface BeforeTaskFail {
        void apply(TaskRecord record, String error);
    }

    /** owner 行锁事务内的领域写操作。 */
    @FunctionalInterface
    public interface TaskOperation {
        void apply(TaskRecord record);
    }

    /** 取得执行权结果：任务与是否成功取得。 */
    public record ClaimResult(TaskRecord record, boolean claimed) {}

    /** 创建或去重命中结果：任务与是否新建。 */
    public record CreateResult(TaskRecord record, boolean created) {}

    /** 失联收敛结果：任务 id、目标状态、执行次数。 */
    public record ReconciledTask(String taskId, String status, int attemptCount) {}

    /** owner 检查结果：是否仍持有有效租约、是否已请求取消。 */
    public record ControlState(boolean ownsLease, boolean cancelRequested) {}

    public TaskRecord getById(String taskId) {
        return taskMapper.selectById(taskId);
    }

    public List<TaskRecord> list(String status, int limit) {
        LambdaQueryWrapper<TaskRecord> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isEmpty()) {
            wrapper.eq(TaskRecord::getStatus, status);
        }
        // 活跃任务（pending/running）优先，其余按创建时间倒序
        wrapper.last(
                "ORDER BY CASE WHEN status IN ('pending','running') THEN 0 ELSE 1 END ASC, created_at DESC LIMIT "
                        + Math.max(limit, 0));
        return taskMapper.selectList(wrapper);
    }

    /** 从完整 Task 表计算列表摘要，不受返回 limit 影响。 */
    public Map<String, Object> summarize(String status) {
        List<Map<String, Object>> statusRows =
                jdbc.queryForList("SELECT status AS value, COUNT(*) AS cnt FROM tasks GROUP BY status");
        List<Map<String, Object>> typeRows =
                jdbc.queryForList("SELECT type AS value, COUNT(*) AS cnt FROM tasks GROUP BY type");
        long total = 0;
        long filteredTotal = 0;
        Map<String, Object> statusCounts = new LinkedHashMap<>();
        for (Map<String, Object> row : statusRows) {
            long count = ((Number) row.get("cnt")).longValue();
            total += count;
            statusCounts.put(String.valueOf(row.get("value")), count);
            if (status != null && status.equals(String.valueOf(row.get("value")))) {
                filteredTotal = count;
            }
        }
        if (status == null) {
            filteredTotal = total;
        }
        Map<String, Object> typeCounts = new LinkedHashMap<>();
        for (Map<String, Object> row : typeRows) {
            typeCounts.put(String.valueOf(row.get("value")), ((Number) row.get("cnt")).longValue());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("filtered_total", filteredTotal);
        result.put("status_counts", statusCounts);
        result.put("type_counts", typeCounts);
        return result;
    }

    public List<TaskRecord> listAll() {
        return taskMapper.selectList(
                new LambdaQueryWrapper<TaskRecord>().orderByDesc(TaskRecord::getCreatedAt));
    }

    public TaskRecord findLatestByPayload(String taskType, Map<String, Object> payloadMatch, Set<String> statuses) {
        StringBuilder sql = new StringBuilder("SELECT * FROM tasks WHERE type = ?");
        List<Object> args = new ArrayList<>();
        args.add(taskType);
        if (statuses != null) {
            if (statuses.isEmpty()) {
                return null;
            }
            sql.append(" AND status IN (").append(placeholders(statuses.size())).append(")");
            args.addAll(statuses);
        }
        for (Map.Entry<String, Object> entry : payloadMatch.entrySet()) {
            sql.append(" AND JSON_UNQUOTE(JSON_EXTRACT(payload, ?)) = ?");
            args.add("$." + entry.getKey());
            args.add(String.valueOf(entry.getValue()));
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT 1");
        List<TaskRecord> rows = jdbc.query(sql.toString(), (rs, rowNum) -> mapTaskRow(rs), args.toArray());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<TaskRecord> listByPayloadValues(String taskType, String payloadKey, Set<String> payloadValues) {
        if (payloadValues == null || payloadValues.isEmpty()) {
            return new ArrayList<>();
        }
        StringBuilder sql =
                new StringBuilder("SELECT * FROM tasks WHERE type = ? AND JSON_UNQUOTE(JSON_EXTRACT(payload, ?)) IN (");
        List<Object> args = new ArrayList<>();
        args.add(taskType);
        args.add("$." + payloadKey);
        sql.append(placeholders(payloadValues.size())).append(")");
        args.addAll(payloadValues);
        sql.append(" ORDER BY created_at DESC, id DESC");
        return jdbc.query(sql.toString(), (rs, rowNum) -> mapTaskRow(rs), args.toArray());
    }

    /** 创建尚未发布的 Task intent（在调用方事务中执行；无事务时自行开启）。 */
    @Transactional
    public TaskRecord createInSession(String taskId, Map<String, Object> data) {
        TaskRecord record = new TaskRecord();
        applyCreateData(record, taskId, data);
        taskMapper.insert(record);
        return record;
    }

    /** 按 active dedupe 原子创建或返回现有 Task。 */
    @Transactional
    public CreateResult createOrGetInSession(String taskId, Map<String, Object> data) {
        try {
            TaskRecord record = new TaskRecord();
            applyCreateData(record, taskId, data);
            taskMapper.insert(record);
            return new CreateResult(record, true);
        } catch (DuplicateKeyException exception) {
            String dedupeKey = RepoValues.asString(data.get("dedupe_key"));
            String type = RepoValues.asString(data.get("type"));
            if (dedupeKey == null || type == null) {
                throw exception;
            }
            TaskRecord existing =
                    taskMapper.selectOne(
                            new LambdaQueryWrapper<TaskRecord>()
                                    .eq(TaskRecord::getType, type)
                                    .eq(TaskRecord::getDedupeKey, dedupeKey)
                                    .notIn(TaskRecord::getStatus, TERMINAL_TASK_STATUSES));
            if (existing == null) {
                throw exception;
            }
            return new CreateResult(existing, false);
        }
    }

    /** 创建持久任务；活跃 dedupe 冲突时返回现有任务。 */
    @Transactional
    public CreateResult create(String taskId, Map<String, Object> data) {
        return createOrGetInSession(taskId, data);
    }

    /** 把无法重建的 pending Task 明确收敛为失败。 */
    @Transactional
    public boolean failPending(String taskId, String error, BeforeTaskChange beforeFail, LocalDateTime now) {
        TaskRecord record = lockTask(taskId);
        LocalDateTime currentTime = currentTime(now);
        if (record == null || !"pending".equals(record.getStatus())) {
            return false;
        }
        if (beforeFail != null) {
            beforeFail.apply(record);
        }
        record.setStatus("failed");
        record.setProgress(100.0);
        record.setMessage("任务 Handler 无法重建");
        record.setError(error);
        record.setCompletedAt(currentTime);
        record.setUpdatedAt(currentTime);
        record.setDedupeKey(null);
        updateRecord(record);
        return true;
    }

    /** 持久化取消意图；未执行任务直接收敛为 cancelled。 */
    @Transactional
    public TaskRecord requestCancel(String taskId, BeforeTaskChange beforeCancel, LocalDateTime now) {
        TaskRecord record = lockTask(taskId);
        LocalDateTime currentTime = currentTime(now);
        if (record == null || TERMINAL_TASK_STATUSES.contains(record.getStatus())) {
            return null;
        }
        record.setCancelRequested(1);
        record.setUpdatedAt(currentTime);
        if ("pending".equals(record.getStatus())) {
            if (beforeCancel != null) {
                beforeCancel.apply(record);
            }
            record.setStatus("cancelled");
            record.setMessage("任务已取消");
            record.setCompletedAt(currentTime);
            record.setDedupeKey(null);
        }
        updateRecord(record);
        return record;
    }

    /** 由一个 attempt 原子取得 pending Task 的执行权。 */
    @Transactional
    public ClaimResult claim(
            String taskId, String workerId, double leaseSeconds, LocalDateTime now, Integer maxRunning) {
        if (workerId == null || workerId.trim().isEmpty()) {
            throw new IllegalArgumentException("worker_id 不能为空");
        }
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("lease_seconds 必须大于 0");
        }
        if (maxRunning == null) {
            return claimLocked(taskId, workerId, leaseSeconds, now);
        }
        if (maxRunning <= 0) {
            throw new IllegalArgumentException("max_running 必须大于 0");
        }
        Integer acquired = jdbc.queryForObject("SELECT GET_LOCK(?, 10)", Integer.class, "durable-task-capacity");
        if (acquired == null || acquired != 1) {
            throw new IllegalStateException("无法取得任务容量判定的命名锁: durable-task-capacity");
        }
        try {
            Long runningCount = jdbc.queryForObject("SELECT COUNT(id) FROM tasks WHERE status = 'running'", Long.class);
            if (runningCount != null && runningCount >= maxRunning) {
                return new ClaimResult(taskMapper.selectById(taskId), false);
            }
            return claimLocked(taskId, workerId, leaseSeconds, now);
        } finally {
            jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, "durable-task-capacity");
        }
    }

    /** 使用数据库时钟检查当前 owner 与取消意图，不延长 lease。 */
    public ControlState checkControl(String taskId, String workerId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT cancel_requested FROM tasks WHERE id = ? AND status = 'running'"
                                + " AND worker_id = ? AND lease_expires_at > UTC_TIMESTAMP()",
                        taskId,
                        workerId);
        if (rows.isEmpty()) {
            return new ControlState(false, false);
        }
        Object value = rows.get(0).get("cancel_requested");
        return new ControlState(true, value instanceof Number number && number.intValue() != 0);
    }

    /** 仅允许当前且未过期的 owner 续租，并返回取消意图。 */
    @Transactional
    public ControlState renewLease(String taskId, String workerId, double leaseSeconds, LocalDateTime now) {
        TaskRecord record = lockTask(taskId);
        LocalDateTime currentTime = currentTime(now);
        if (!isLiveOwner(record, workerId, currentTime)) {
            return new ControlState(false, false);
        }
        record.setHeartbeatAt(currentTime);
        record.setLeaseExpiresAt(leaseExpiry(currentTime, leaseSeconds));
        record.setUpdatedAt(currentTime);
        updateRecord(record);
        boolean cancelRequested = record.getCancelRequested() != null && record.getCancelRequested() != 0;
        return new ControlState(true, cancelRequested);
    }

    /**
     * 在当前 owner 的行锁事务中执行领域写操作。
     *
     * <p>用编程式事务：参考实现在「操作后 owner 已丢失」时显式回滚并返回 False，
     * 注解式事务只能靠抛异常回滚。
     *
     * @return 是否由当前 owner 成功提交
     */
    public boolean runOwnedTransaction(String taskId, String workerId, TaskOperation operation) {
        Boolean result =
                transactionTemplate.execute(
                        status -> {
                            TaskRecord record = lockTask(taskId);
                            if (!isLiveOwner(record, workerId, currentTime(null))) {
                                return false;
                            }
                            operation.apply(record);
                            if (!isLiveOwner(record, workerId, currentTime(null))) {
                                status.setRollbackOnly();
                                return false;
                            }
                            return true;
                        });
        return Boolean.TRUE.equals(result);
    }

    /** 只有持有有效 lease 的 owner 可以更新进度、结果和消息。 */
    @Transactional
    public boolean updateOwned(String taskId, String workerId, Map<String, Object> data, LocalDateTime now) {
        TaskRecord record = lockTask(taskId);
        LocalDateTime currentTime = currentTime(now);
        if (!isLiveOwner(record, workerId, currentTime)) {
            return false;
        }
        applyOwnedData(record, data);
        record.setUpdatedAt(currentTime);
        updateRecord(record);
        return true;
    }

    /** 由当前 owner 提交终态并释放执行权（不写 result）。 */
    public boolean finishOwned(
            String taskId,
            String workerId,
            String status,
            String message,
            String error,
            BeforeTaskChange beforeFinish,
            BeforeTaskChange beforeCancel,
            LocalDateTime now) {
        return finishOwnedInternal(
                taskId, workerId, status, message, UNSET, error, beforeFinish, beforeCancel, now);
    }

    /** 由当前 owner 提交终态并释放执行权（写入 result）。 */
    public boolean finishOwned(
            String taskId,
            String workerId,
            String status,
            String message,
            Object result,
            String error,
            BeforeTaskChange beforeFinish,
            BeforeTaskChange beforeCancel,
            LocalDateTime now) {
        return finishOwnedInternal(
                taskId, workerId, status, message, result, error, beforeFinish, beforeCancel, now);
    }

    /**
     * 由当前 owner 提交终态并释放执行权。
     *
     * <p>用编程式事务：参考实现在「钩子执行后 owner 已丢失」时显式回滚并返回 False。
     */
    private boolean finishOwnedInternal(
            String taskId,
            String workerId,
            String status,
            String message,
            Object result,
            String error,
            BeforeTaskChange beforeFinish,
            BeforeTaskChange beforeCancel,
            LocalDateTime now) {
        if (!TERMINAL_TASK_STATUSES.contains(status)) {
            throw new IllegalArgumentException("非法 Task 终态: " + status);
        }
        Boolean committed =
                transactionTemplate.execute(
                        txStatus -> {
                            TaskRecord record = lockTask(taskId);
                            LocalDateTime currentTime = currentTime(now);
                            if (!isLiveOwner(record, workerId, currentTime)) {
                                return false;
                            }
                            String effectiveStatus = status;
                            String effectiveMessage = message;
                            Object effectiveResult = result;
                            boolean cancelRequested =
                                    record.getCancelRequested() != null && record.getCancelRequested() != 0;
                            if (cancelRequested && !"cancelled".equals(effectiveStatus)) {
                                effectiveStatus = "cancelled";
                                effectiveMessage = "任务已取消";
                                effectiveResult = UNSET;
                                BeforeTaskChange cancelHook = beforeCancel != null ? beforeCancel : beforeFinish;
                                if (cancelHook != null) {
                                    cancelHook.apply(record);
                                }
                            } else if (beforeFinish != null) {
                                beforeFinish.apply(record);
                            }
                            currentTime = currentTime(now);
                            if (!isLiveOwner(record, workerId, currentTime)) {
                                txStatus.setRollbackOnly();
                                return false;
                            }
                            record.setStatus(effectiveStatus);
                            record.setProgress(100.0);
                            record.setMessage(effectiveMessage);
                            if (effectiveResult != UNSET) {
                                record.setResult(RepoValues.toJsonText(effectiveResult));
                            }
                            record.setError(error);
                            record.setCompletedAt(currentTime);
                            record.setUpdatedAt(currentTime);
                            clearExecution(record, true);
                            updateRecord(record);
                            return true;
                        });
        return Boolean.TRUE.equals(committed);
    }

    /**
     * 优雅中断时明确失败并释放执行权。
     *
     * <p>用编程式事务：参考实现在「钩子执行后 owner 已丢失」时显式回滚并返回 None。
     *
     * @return 收敛后的状态；未持有有效租约或钩子后失去租约时为 null
     */
    public String releaseInterruptedOwner(
            String taskId, String workerId, String error, BeforeTaskFail beforeFail, LocalDateTime now) {
        return transactionTemplate.execute(
                status -> {
                    TaskRecord record = lockTask(taskId);
                    LocalDateTime currentTime = currentTime(now);
                    if (!isLiveOwner(record, workerId, currentTime)) {
                        return null;
                    }
                    if (beforeFail != null) {
                        beforeFail.apply(record, error);
                        currentTime = currentTime(now);
                        if (!isLiveOwner(record, workerId, currentTime)) {
                            status.setRollbackOnly();
                            return null;
                        }
                    }
                    String nextStatus = failInterruptedTask(record, error, currentTime);
                    updateRecord(record);
                    return nextStatus;
                });
    }

    /** 收敛失联 Task；返回 task_id、目标状态和 attempt。 */
    @Transactional
    public List<ReconciledTask> reconcileExpiredLeases(BeforeTaskFail beforeFail, LocalDateTime now) {
        LocalDateTime currentTime = currentTime(now);
        List<TaskRecord> records =
                jdbc.query(
                        "SELECT * FROM tasks WHERE status = 'running'"
                                + " AND (lease_expires_at IS NULL OR lease_expires_at <= ?)"
                                + " FOR UPDATE SKIP LOCKED",
                        (rs, rowNum) -> mapTaskRow(rs),
                        currentTime);
        List<ReconciledTask> reconciled = new ArrayList<>();
        for (TaskRecord record : records) {
            String error = "worker_lease_expired: 执行 worker 的 lease 已过期，任务副作用结果未知";
            if (beforeFail != null) {
                beforeFail.apply(record, error);
            }
            String nextStatus = failInterruptedTask(record, error, currentTime);
            updateRecord(record);
            reconciled.add(
                    new ReconciledTask(
                            record.getId(),
                            nextStatus,
                            record.getAttemptCount() == null ? 0 : record.getAttemptCount()));
        }
        return reconciled;
    }

    public List<TaskRecord> listPending(int limit) {
        return taskMapper.selectList(
                new LambdaQueryWrapper<TaskRecord>()
                        .eq(TaskRecord::getStatus, "pending")
                        .orderByAsc(TaskRecord::getCreatedAt)
                        .last("LIMIT " + Math.max(limit, 1)));
    }

    /** 保留最近终态任务，并删除更旧摘要。 */
    @Transactional
    public List<String> pruneTerminal(int keep) {
        // MySQL 的 OFFSET 必须伴随 LIMIT：用无符号大整数上限表达「不设上限 + 偏移」
        List<TaskRecord> stale =
                taskMapper.selectList(
                        new LambdaQueryWrapper<TaskRecord>()
                                .select(TaskRecord::getId)
                                .in(TaskRecord::getStatus, TERMINAL_TASK_STATUSES)
                                .orderByDesc(TaskRecord::getCreatedAt)
                                .orderByDesc(TaskRecord::getId)
                                .last("LIMIT 18446744073709551615 OFFSET " + Math.max(keep, 0)));
        List<String> staleIds = new ArrayList<>();
        for (TaskRecord record : stale) {
            staleIds.add(record.getId());
        }
        if (!staleIds.isEmpty()) {
            taskMapper.delete(new LambdaQueryWrapper<TaskRecord>().in(TaskRecord::getId, staleIds));
        }
        return staleIds;
    }

    public boolean deleteTerminal(String taskId) {
        int rows =
                taskMapper.delete(
                        new LambdaQueryWrapper<TaskRecord>()
                                .eq(TaskRecord::getId, taskId)
                                .in(TaskRecord::getStatus, TERMINAL_TASK_STATUSES));
        return rows > 0;
    }

    public void deleteAll() {
        jdbc.update("DELETE FROM tasks");
    }

    private ClaimResult claimLocked(String taskId, String workerId, double leaseSeconds, LocalDateTime now) {
        TaskRecord record = lockTask(taskId);
        LocalDateTime currentTime = currentTime(now);
        if (record == null || !"pending".equals(record.getStatus())) {
            return new ClaimResult(record, false);
        }
        if (record.getCancelRequested() != null && record.getCancelRequested() != 0) {
            record.setStatus("cancelled");
            record.setMessage("任务在执行前已取消");
            record.setCompletedAt(currentTime);
            record.setUpdatedAt(currentTime);
            record.setDedupeKey(null);
            updateRecord(record);
            return new ClaimResult(record, false);
        }
        record.setStatus("running");
        record.setWorkerId(workerId);
        record.setHeartbeatAt(currentTime);
        record.setLeaseExpiresAt(leaseExpiry(currentTime, leaseSeconds));
        record.setAttemptCount((record.getAttemptCount() == null ? 0 : record.getAttemptCount()) + 1);
        record.setStartedAt(record.getStartedAt() != null ? record.getStartedAt() : currentTime);
        record.setUpdatedAt(currentTime);
        record.setMessage("任务开始执行");
        updateRecord(record);
        return new ClaimResult(record, true);
    }

    private LocalDateTime currentTime(LocalDateTime explicit) {
        if (explicit != null) {
            return explicit;
        }
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP()", LocalDateTime.class);
    }

    private TaskRecord lockTask(String taskId) {
        List<TaskRecord> rows =
                jdbc.query("SELECT * FROM tasks WHERE id = ? FOR UPDATE", (rs, rowNum) -> mapTaskRow(rs), taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static LocalDateTime leaseExpiry(LocalDateTime from, double leaseSeconds) {
        return from.plusNanos(Math.round(leaseSeconds * 1_000_000_000L));
    }

    private static boolean isLiveOwner(TaskRecord record, String workerId, LocalDateTime now) {
        return record != null
                && "running".equals(record.getStatus())
                && Objects.equals(record.getWorkerId(), workerId)
                && record.getLeaseExpiresAt() != null
                && record.getLeaseExpiresAt().isAfter(now);
    }

    private static void clearExecution(TaskRecord record, boolean clearDedupe) {
        record.setWorkerId(null);
        record.setHeartbeatAt(null);
        record.setLeaseExpiresAt(null);
        if (clearDedupe) {
            record.setDedupeKey(null);
        }
    }

    private static String failInterruptedTask(TaskRecord record, String error, LocalDateTime now) {
        record.setStatus("failed");
        record.setProgress(100.0);
        record.setMessage("执行中断，无法安全自动恢复");
        record.setError(error);
        record.setCompletedAt(now);
        clearExecution(record, true);
        record.setUpdatedAt(now);
        return record.getStatus();
    }

    /**
     * 更新时显式 set 全部列（框架默认更新策略会跳过 null 列，会让 dedupe_key/worker_id
     * 等清空动作静默失效）。
     */
    private void updateRecord(TaskRecord record) {
        LambdaUpdateWrapper<TaskRecord> update =
                new LambdaUpdateWrapper<TaskRecord>().eq(TaskRecord::getId, record.getId());
        update.set(TaskRecord::getName, record.getName());
        update.set(TaskRecord::getType, record.getType());
        update.set(TaskRecord::getStatus, record.getStatus());
        update.set(TaskRecord::getProgress, record.getProgress());
        update.set(TaskRecord::getMessage, record.getMessage());
        update.set(TaskRecord::getPayload, record.getPayload());
        update.set(TaskRecord::getResult, record.getResult());
        update.set(TaskRecord::getError, record.getError());
        update.set(TaskRecord::getCancelRequested, record.getCancelRequested());
        update.set(TaskRecord::getHandlerVersion, record.getHandlerVersion());
        update.set(TaskRecord::getDedupeKey, record.getDedupeKey());
        update.set(TaskRecord::getAttemptCount, record.getAttemptCount());
        update.set(TaskRecord::getWorkerId, record.getWorkerId());
        update.set(TaskRecord::getHeartbeatAt, record.getHeartbeatAt());
        update.set(TaskRecord::getLeaseExpiresAt, record.getLeaseExpiresAt());
        update.set(TaskRecord::getTimeoutSeconds, record.getTimeoutSeconds());
        update.set(TaskRecord::getCreatedAt, record.getCreatedAt());
        update.set(TaskRecord::getUpdatedAt, record.getUpdatedAt());
        update.set(TaskRecord::getStartedAt, record.getStartedAt());
        update.set(TaskRecord::getCompletedAt, record.getCompletedAt());
        taskMapper.update(null, update);
    }

    private static void applyCreateData(TaskRecord record, String taskId, Map<String, Object> data) {
        record.setId(taskId);
        applyOwnedData(record, data);
    }

    /** 可写列白名单；未知键直接报错（见类注释的必要替换说明）。 */
    private static void applyOwnedData(TaskRecord record, Map<String, Object> data) {
        if (data == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            switch (key) {
                case "id" -> record.setId(RepoValues.asString(value));
                case "name" -> record.setName(RepoValues.asString(value));
                case "type" -> record.setType(RepoValues.asString(value));
                case "status" -> record.setStatus(RepoValues.asString(value));
                case "progress" -> record.setProgress(value == null ? null : ((Number) value).doubleValue());
                case "message" -> record.setMessage(RepoValues.asString(value));
                case "payload" -> record.setPayload(RepoValues.toJsonText(value));
                case "result" -> record.setResult(RepoValues.toJsonText(value));
                case "error" -> record.setError(RepoValues.asString(value));
                case "cancel_requested" -> record.setCancelRequested(RepoValues.toInt(value));
                case "handler_version" -> record.setHandlerVersion(RepoValues.toInt(value));
                case "dedupe_key" -> record.setDedupeKey(RepoValues.asString(value));
                case "attempt_count" -> record.setAttemptCount(RepoValues.toInt(value));
                case "worker_id" -> record.setWorkerId(RepoValues.asString(value));
                case "heartbeat_at" -> record.setHeartbeatAt(RepoValues.toLocalDateTime(value));
                case "lease_expires_at" -> record.setLeaseExpiresAt(RepoValues.toLocalDateTime(value));
                case "timeout_seconds" ->
                        record.setTimeoutSeconds(value == null ? null : ((Number) value).doubleValue());
                case "created_at" -> record.setCreatedAt(RepoValues.toLocalDateTime(value));
                case "updated_at" -> record.setUpdatedAt(RepoValues.toLocalDateTime(value));
                case "started_at" -> record.setStartedAt(RepoValues.toLocalDateTime(value));
                case "completed_at" -> record.setCompletedAt(RepoValues.toLocalDateTime(value));
                default -> throw new IllegalArgumentException("不支持写入的 Task 列: " + key);
            }
        }
    }

    private static String placeholders(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(i == 0 ? "?" : ", ?");
        }
        return builder.toString();
    }

    /** 原生 SQL 结果映射为实体（行锁与 JSON 路径查询走原生 SQL）。 */
    private static TaskRecord mapTaskRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        TaskRecord record = new TaskRecord();
        record.setId(rs.getString("id"));
        record.setName(rs.getString("name"));
        record.setType(rs.getString("type"));
        record.setStatus(rs.getString("status"));
        record.setProgress(rs.getObject("progress") == null ? null : rs.getDouble("progress"));
        record.setMessage(rs.getString("message"));
        record.setPayload(rs.getString("payload"));
        record.setResult(rs.getString("result"));
        record.setError(rs.getString("error"));
        record.setCancelRequested(rs.getObject("cancel_requested") == null ? null : rs.getInt("cancel_requested"));
        record.setHandlerVersion(rs.getObject("handler_version") == null ? null : rs.getInt("handler_version"));
        record.setDedupeKey(rs.getString("dedupe_key"));
        record.setAttemptCount(rs.getObject("attempt_count") == null ? null : rs.getInt("attempt_count"));
        record.setWorkerId(rs.getString("worker_id"));
        record.setHeartbeatAt(toLocalDateTime(rs.getTimestamp("heartbeat_at")));
        record.setLeaseExpiresAt(toLocalDateTime(rs.getTimestamp("lease_expires_at")));
        record.setTimeoutSeconds(rs.getObject("timeout_seconds") == null ? null : rs.getDouble("timeout_seconds"));
        record.setCreatedAt(toLocalDateTime(rs.getTimestamp("created_at")));
        record.setUpdatedAt(toLocalDateTime(rs.getTimestamp("updated_at")));
        record.setStartedAt(toLocalDateTime(rs.getTimestamp("started_at")));
        record.setCompletedAt(toLocalDateTime(rs.getTimestamp("completed_at")));
        return record;
    }

    private static LocalDateTime toLocalDateTime(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
