package com.wisesoft.wenqu.repositories;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.AgentRun;
import com.wisesoft.wenqu.models.AgentRunAttempt;
import com.wisesoft.wenqu.models.Message;
import com.wisesoft.wenqu.models.ModelConstants;
import com.wisesoft.wenqu.models.SubagentThread;
import com.wisesoft.wenqu.models.ToolCall;
import com.wisesoft.wenqu.repository.port.AgentRunAttemptMapper;
import com.wisesoft.wenqu.repository.port.AgentRunMapper;
import com.wisesoft.wenqu.repository.port.MessageMapper;
import com.wisesoft.wenqu.repository.port.SubagentThreadMapper;
import com.wisesoft.wenqu.repository.port.ToolCallMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 智能体运行仓储。
 *
 * <p>由参考实现的 repositories/agent_run_repository.py 逐方法翻译：run 的登记与查询、
 * 租约的取得/续租/释放/过期收敛、attempt 的开放与终结、终态提交的前置校验、
 * 执行树的级联取消、审计行的收口、write-once 的运行清单与四个时间戳里程碑。
 *
 * <p>必要替换（逐条标注）：
 * <ul>
 *   <li>行锁：{@code with_for_update()} → {@code FOR UPDATE}；{@code skip_locked=True} →
 *       {@code FOR UPDATE SKIP LOCKED}（MySQL 8.0+）。**注意**：自动提交下 FOR UPDATE 会立即释放，
 *       故所有取锁方法都以 {@code @Transactional} 标注（等价于参考实现的"请求会话"边界）；
 *       被其他仓储调用时会加入调用方事务（REQUIRED）。
 *   <li>窗口函数查询（各线程最新顶层 run）用原生 SQL：MySQL 8.0+ 支持
 *       {@code ROW_NUMBER() OVER (...)}，与参考实现同构。
 *   <li>JSON 列写入：参考实现由 ORM 把 dict/str 序列化为 JSON；本层显式
 *       {@code JSON.toJSONString}（字符串会带引号，与 ORM 行为一致）。
 *   <li>置空字段：一律用显式 {@code set} 写 UPDATE（框架默认更新策略会跳过 null 列）。
 *   <li>{@code ValueError} 的映射：参数非法 → {@code IllegalArgumentException}；
 *       状态非法 → {@code IllegalStateException}。
 * </ul>
 */
@Repository
public class AgentRunRepository {

    /** 终态集合。 */
    public static final Set<String> TERMINAL_RUN_STATUSES =
            new LinkedHashSet<>(ModelConstants.AGENT_RUN_TERMINAL_STATUSES);

    /** 持有租约的状态集合。 */
    public static final Set<String> LEASED_RUN_STATUSES = Set.of("running", "cancel_requested");

    /** Run 状态到输入消息投递状态的投影。 */
    private static final Map<String, String> RUN_STATUS_TO_DELIVERY_STATUS =
            Map.of("completed", "complete", "failed", "failed", "cancelled", "cancelled");

    /** 顶层 run 类型。 */
    public static final List<String> TOP_LEVEL_RUN_TYPES = List.of("chat", "resume");

    /** 终态提交时的审计执行状态映射。 */
    private static final Map<String, String> TERMINAL_AUDIT_STATUS =
            Map.of("completed", "abandoned", "failed", "failed", "cancelled", "interrupted", "interrupted", "interrupted");

    private final AgentRunMapper runMapper;
    private final AgentRunAttemptMapper attemptMapper;
    private final MessageMapper messageMapper;
    private final ToolCallMapper toolCallMapper;
    private final SubagentThreadMapper subagentThreadMapper;
    private final JdbcTemplate jdbc;

    public AgentRunRepository(
            AgentRunMapper runMapper,
            AgentRunAttemptMapper attemptMapper,
            MessageMapper messageMapper,
            ToolCallMapper toolCallMapper,
            SubagentThreadMapper subagentThreadMapper,
            JdbcTemplate jdbc) {
        this.runMapper = runMapper;
        this.attemptMapper = attemptMapper;
        this.messageMapper = messageMapper;
        this.toolCallMapper = toolCallMapper;
        this.subagentThreadMapper = subagentThreadMapper;
        this.jdbc = jdbc;
    }

    /** 带是否新变更标记的返回值。 */
    public record RunResult(AgentRun run, boolean changed) {}

    /** 取消结果：被直接取消的 run 与级联取消的后代 id。 */
    public record CancelTreeResult(AgentRun run, List<String> cancelledIds) {}

    /** 过期租约收敛结果：被收敛的 run 与（id, threadId）形式的被取消后代。 */
    public record ReconcileResult(List<AgentRun> runs, List<String[]> cancelledDescendants) {}

    /** 父子 run 对。 */
    public record SubagentRunPair(AgentRun creatorRun, AgentRun run) {}

    // ==================== 查询 ====================

    public AgentRun getRun(String runId) {
        return runMapper.selectById(runId);
    }

    public AgentRun getRunByRequestId(String requestId) {
        return runMapper.selectOne(new LambdaQueryWrapper<AgentRun>().eq(AgentRun::getRequestId, requestId));
    }

    public AgentRun getRunForUser(String runId, String uid) {
        return runMapper.selectOne(
                new LambdaQueryWrapper<AgentRun>().eq(AgentRun::getId, runId).eq(AgentRun::getUid, String.valueOf(uid)));
    }

    /** 锁定用户 Run，串行化 execution tree 创建与父 Run 终态提交。 */
    @Transactional
    public AgentRun lockRunForUser(String runId, String uid) {
        return runMapper.selectOne(
                new LambdaQueryWrapper<AgentRun>()
                        .eq(AgentRun::getId, runId)
                        .eq(AgentRun::getUid, String.valueOf(uid))
                        .last("FOR UPDATE"));
    }

    /** 读取父子 Run，并校验当前执行树的线程关系一致性。 */
    public SubagentRunPair getSubagentRunWithCreator(String uid, String createdByRunId, String runId) {
        AgentRun creatorRun = getRunForUser(createdByRunId, uid);
        if (creatorRun == null) {
            return null;
        }
        AgentRun run = getRunForUser(runId, uid);
        if (run == null || !"subagent".equals(run.getRunType())) {
            return null;
        }
        if (!creatorRun.getId().equals(run.getCreatedByRunId())) {
            return null;
        }
        Integer relationId = run.getSubagentThreadRelationId();
        if (relationId == null) {
            return null;
        }
        SubagentThread relation =
                subagentThreadMapper.selectOne(
                        new LambdaQueryWrapper<SubagentThread>()
                                .eq(SubagentThread::getId, relationId)
                                .eq(SubagentThread::getUid, String.valueOf(uid)));
        if (relation == null || !java.util.Objects.equals(creatorRun.getConversationId(), relation.getParentConversationId())) {
            return null;
        }
        if (!java.util.Objects.equals(relation.getChildConversationId(), run.getConversationId())
                || !java.util.Objects.equals(relation.getChildThreadId(), run.getConversationThreadId())) {
            return null;
        }
        return new SubagentRunPair(creatorRun, run);
    }

    /** 读取某个子线程最近一次子智能体 run，用于状态页和继续线程校验。 */
    public AgentRun getLatestSubagentRunByThreadForUser(String conversationThreadId, String uid) {
        return runMapper.selectOne(
                new LambdaQueryWrapper<AgentRun>()
                        .eq(AgentRun::getConversationThreadId, conversationThreadId)
                        .eq(AgentRun::getUid, String.valueOf(uid))
                        .eq(AgentRun::getRunType, "subagent")
                        .orderByDesc(AgentRun::getCreatedAt)
                        .last("LIMIT 1"));
    }

    /** 读取线程最近一次 run，用于恢复查询 checkpoint 时的运行时模型。 */
    public AgentRun getLatestRunByThreadForUser(String conversationThreadId, String uid) {
        return runMapper.selectOne(
                new LambdaQueryWrapper<AgentRun>()
                        .eq(AgentRun::getConversationThreadId, conversationThreadId)
                        .eq(AgentRun::getUid, String.valueOf(uid))
                        .in(AgentRun::getRunType, List.of("chat", "resume", "subagent"))
                        .orderByDesc(AgentRun::getCreatedAt)
                        .last("LIMIT 1"));
    }

    /** 读取队列作用域内最新的顶层 chat/resume run。 */
    public AgentRun getLatestChatOrResumeRun(String uid, String agentSlug, String conversationThreadId) {
        return runMapper.selectOne(
                new LambdaQueryWrapper<AgentRun>()
                        .eq(AgentRun::getUid, String.valueOf(uid))
                        .eq(AgentRun::getAgentSlug, agentSlug)
                        .eq(AgentRun::getConversationThreadId, conversationThreadId)
                        .in(AgentRun::getRunType, TOP_LEVEL_RUN_TYPES)
                        .orderByDesc(AgentRun::getCreatedAt)
                        .orderByDesc(AgentRun::getId)
                        .last("LIMIT 1"));
    }

    /**
     * 批量读取各线程最新顶层 chat/resume run，返回 threadId → [runId, status]。
     *
     * <p>用窗口函数一次查询完成，避免对每个线程执行 N+1 查询。
     */
    public Map<String, String[]> getLatestTopLevelRunsForThreads(String uid, List<String> conversationThreadIds) {
        Map<String, String[]> result = new LinkedHashMap<>();
        if (conversationThreadIds == null || conversationThreadIds.isEmpty()) {
            return result;
        }
        List<Object> args = new ArrayList<>();
        args.add(String.valueOf(uid));
        args.addAll(conversationThreadIds);
        args.addAll(TOP_LEVEL_RUN_TYPES);
        String sql =
                "SELECT id, status, conversation_thread_id FROM ("
                        + "SELECT id, status, conversation_thread_id, ROW_NUMBER() OVER ("
                        + "PARTITION BY conversation_thread_id ORDER BY created_at DESC, id DESC) AS rn "
                        + "FROM agent_runs WHERE uid = ? AND conversation_thread_id IN ("
                        + placeholders(conversationThreadIds.size())
                        + ") AND run_type IN (?, ?)"
                        + ") ranked WHERE rn = 1";
        for (Map<String, Object> row : jdbc.queryForList(sql, args.toArray())) {
            result.put(
                    String.valueOf(row.get("conversation_thread_id")),
                    new String[] {String.valueOf(row.get("id")), String.valueOf(row.get("status"))});
        }
        return result;
    }

    /** 列出由指定 run 创建的所有子 run。 */
    public List<AgentRun> listChildRunsForUser(String createdByRunId, String uid) {
        return runMapper.selectList(
                new LambdaQueryWrapper<AgentRun>()
                        .eq(AgentRun::getCreatedByRunId, createdByRunId)
                        .eq(AgentRun::getUid, String.valueOf(uid))
                        .orderByAsc(AgentRun::getCreatedAt)
                        .orderByAsc(AgentRun::getId));
    }

    /** 检查同一用户、智能体、线程上是否已有未结束 run，避免并发写同一线程。 */
    public AgentRun getActiveRunByThreadForUser(String agentSlug, String conversationThreadId, String uid) {
        return runMapper.selectOne(
                new LambdaQueryWrapper<AgentRun>()
                        .eq(AgentRun::getAgentSlug, agentSlug)
                        .eq(AgentRun::getUid, String.valueOf(uid))
                        .eq(AgentRun::getConversationThreadId, conversationThreadId)
                        .and(w -> w.notIn(AgentRun::getStatus, TERMINAL_RUN_STATUSES)
                                .or()
                                .eq(AgentRun::getRuntimeCleanupPending, true))
                        .orderByDesc(AgentRun::getCreatedAt)
                        .last("LIMIT 1"));
    }

    /** 读取共享同一 runtime 的任意未终态 Run。 */
    public AgentRun getActiveRunByRuntimeScopeForUser(String runtimeScopeId, String uid) {
        return runMapper.selectOne(
                new LambdaQueryWrapper<AgentRun>()
                        .eq(AgentRun::getRuntimeScopeId, String.valueOf(runtimeScopeId))
                        .eq(AgentRun::getUid, String.valueOf(uid))
                        .and(w -> w.notIn(AgentRun::getStatus, TERMINAL_RUN_STATUSES)
                                .or()
                                .eq(AgentRun::getRuntimeCleanupPending, true))
                        .orderByDesc(AgentRun::getCreatedAt)
                        .orderByDesc(AgentRun::getId)
                        .last("LIMIT 1"));
    }

    /**
     * 全表范围内 pending run 的作用域投影（对应参考实现
     * {@code select(AgentRun.uid, AgentRun.agent_slug, AgentRun.conversation_thread_id).where(AgentRun.status == "pending")}）。
     *
     * <p>供恢复扫描枚举"已落库但未投递"的线程；参考实现未加 distinct，
     * 去重由调用方在同一集合内完成（保持同一语义）。
     */
    public List<String[]> listPendingDispatchScopes() {
        List<Map<String, Object>> rows = runMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AgentRun>()
                        .select("uid", "agent_slug", "conversation_thread_id")
                        .eq("status", "pending"));
        List<String[]> scopes = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            scopes.add(new String[] {
                row.get("uid") == null ? "" : String.valueOf(row.get("uid")),
                row.get("agent_slug") == null ? "" : String.valueOf(row.get("agent_slug")),
                row.get("conversation_thread_id") == null ? "" : String.valueOf(row.get("conversation_thread_id"))
            });
        }
        return scopes;
    }

    /** 登记一条 run 记录；输入正文和图片应通过 inputMessageId 指向 Message。 */
    @Transactional
    public AgentRun createRun(
            String runId,
            String conversationThreadId,
            String runtimeScopeId,
            String agentSlug,
            String uid,
            String requestId,
            Map<String, Object> inputPayload,
            String source,
            String channel,
            String externalId,
            Map<String, Object> originMetadata,
            Integer conversationId,
            String createdByRunId,
            Integer subagentThreadRelationId,
            String runType,
            Integer inputMessageId) {
        String runtimeScope =
                runtimeScopeId == null ? String.valueOf(conversationThreadId) : String.valueOf(runtimeScopeId).trim();
        LocalDateTime now = DateTimeUtils.utcNowNaive();
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setConversationThreadId(conversationThreadId);
        run.setRuntimeScopeId(runtimeScope);
        run.setAgentSlug(agentSlug);
        run.setUid(String.valueOf(uid));
        run.setRequestId(requestId);
        run.setSource(source);
        run.setChannel(channel);
        run.setExternalId(externalId);
        run.setOriginMetadata(JSON.toJSONString(originMetadata == null ? new LinkedHashMap<>() : originMetadata));
        run.setConversationId(conversationId);
        run.setCreatedByRunId(createdByRunId);
        run.setSubagentThreadRelationId(subagentThreadRelationId);
        run.setRunType(runType);
        run.setInputMessageId(inputMessageId);
        run.setInputPayload(JSON.toJSONString(inputPayload == null ? new LinkedHashMap<>() : inputPayload));
        run.setTokenUsage("{}");
        run.setStatus("pending");
        run.setRuntimeCleanupPending(false);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        runMapper.insert(run);
        return run;
    }

    /** 由当前 attempt 在执行前幂等固化 Run 的 Langfuse trace。 */
    @Transactional
    public AgentRun setLangfuseTraceId(String runId, String traceId, String workerId, LocalDateTime now) {
        String normalizedTraceId = traceId == null ? "" : traceId.trim();
        if (normalizedTraceId.isEmpty()) {
            throw new IllegalArgumentException("trace_id 不能为空");
        }
        if (workerId == null || workerId.trim().isEmpty()) {
            throw new IllegalArgumentException("worker_id 不能为空");
        }
        AgentRun run = lockRun(runId);
        if (run == null) {
            return null;
        }
        LocalDateTime currentTime = now == null ? DateTimeUtils.utcNowNaive() : now;
        requireLeaseOwner(run, workerId, currentTime, "固化 Langfuse trace");
        if (run.getLangfuseTraceId() != null && !run.getLangfuseTraceId().equals(normalizedTraceId)) {
            throw new IllegalArgumentException("AgentRun 已绑定不同的 Langfuse trace");
        }
        run.setLangfuseTraceId(normalizedTraceId);
        run.setUpdatedAt(currentTime);
        runMapper.updateById(run);
        return run;
    }

    /** 仅允许当前 attempt 绑定属于本 Run 的 assistant 输出。 */
    @Transactional
    public AgentRun setOutputMessage(String runId, Integer messageId, String workerId, LocalDateTime now) {
        if (workerId == null || workerId.trim().isEmpty()) {
            throw new IllegalArgumentException("worker_id 不能为空");
        }
        AgentRun run = lockRun(runId);
        if (run == null) {
            return null;
        }
        LocalDateTime currentTime = now == null ? DateTimeUtils.utcNowNaive() : now;
        requireLeaseOwner(run, workerId, currentTime, "持久化输出消息");
        Message message = getMatchingOutputMessage(run, messageId);
        if (message == null) {
            throw new IllegalArgumentException("输出消息必须属于同一 conversation、Run 和 request，且角色为 assistant");
        }
        run.setOutputMessageId(messageId);
        run.setUpdatedAt(currentTime);
        runMapper.updateById(run);
        return run;
    }

    /** 在任何输出写入前锁定并验证当前 attempt 的完整因果边界。 */
    @Transactional
    public AgentRun lockOutputPersistence(
            String runId, String workerId, String conversationThreadId, String requestId, LocalDateTime now) {
        if (workerId == null || workerId.trim().isEmpty()) {
            throw new IllegalArgumentException("worker_id 不能为空");
        }
        AgentRun run = lockRun(runId);
        if (run == null) {
            return null;
        }
        requireLeaseOwner(
                run, workerId, now == null ? DateTimeUtils.utcNowNaive() : now, "持久化输出消息");
        if (!run.getConversationThreadId().equals(conversationThreadId) || !run.getRequestId().equals(requestId)) {
            throw new IllegalArgumentException("AgentRun 输出必须属于同一 thread 和 request");
        }
        if (run.getConversationId() == null) {
            throw new IllegalArgumentException("AgentRun 输出缺少 conversation 归属");
        }
        return run;
    }

    /** 锁定并验证允许写入用户 Memory 的当前顶层 attempt。 */
    @Transactional
    public AgentRun lockMemoryWrite(
            String runId,
            String uid,
            String workerId,
            String conversationThreadId,
            String requestId,
            LocalDateTime now) {
        if (workerId == null || workerId.trim().isEmpty()) {
            throw new IllegalArgumentException("worker_id 不能为空");
        }
        AgentRun run = lockRun(runId);
        if (run == null) {
            return null;
        }
        requireLeaseOwner(run, workerId, now == null ? DateTimeUtils.utcNowNaive() : now, "写入 Memory");
        if (!run.getUid().equals(String.valueOf(uid))
                || !run.getConversationThreadId().equals(conversationThreadId)
                || !run.getRequestId().equals(requestId)
                || !TOP_LEVEL_RUN_TYPES.contains(run.getRunType())) {
            throw new IllegalArgumentException("Memory 写入必须属于当前用户的同一顶层 Run、thread 和 request");
        }
        return run;
    }

    /** 由一个 worker 原子取得或续接尚未过期的 Run ownership。 */
    @Transactional
    public RunResult markRunning(String runId, String workerId, double leaseSeconds, LocalDateTime now) {
        if (workerId == null || workerId.trim().isEmpty()) {
            throw new IllegalArgumentException("worker_id 不能为空");
        }
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("lease_seconds 必须大于 0");
        }
        AgentRun run = lockRun(runId);
        if (run == null) {
            return new RunResult(null, false);
        }
        if (TERMINAL_RUN_STATUSES.contains(run.getStatus())) {
            return new RunResult(run, false);
        }
        if (Boolean.TRUE.equals(run.getRuntimeCleanupPending())) {
            return new RunResult(run, false);
        }

        LocalDateTime currentTime = now == null ? DateTimeUtils.utcNowNaive() : now;
        boolean initialClaim =
                "pending".equals(run.getStatus())
                        || ("cancel_requested".equals(run.getStatus()) && run.getWorkerId() == null);
        boolean sameLiveOwner =
                LEASED_RUN_STATUSES.contains(run.getStatus())
                        && workerId.equals(run.getWorkerId())
                        && run.getLeaseExpiresAt() != null
                        && run.getLeaseExpiresAt().isAfter(currentTime);
        if (!initialClaim && !sameLiveOwner) {
            return new RunResult(run, false);
        }

        String newStatus = "pending".equals(run.getStatus()) ? "running" : run.getStatus();
        LocalDateTime leaseExpiresAt = currentTime.plusNanos((long) (leaseSeconds * 1_000_000_000L));
        LocalDateTime startedAt = run.getStartedAt() == null ? currentTime : run.getStartedAt();

        if (initialClaim) {
            closeOpenAttempts(
                    run.getId(), "lease_expired", "worker_lease_expired", "执行占有人在取得新所有权前已失联。", currentTime);
            Long maxAttemptNo =
                    attemptMapper.selectObjs(
                                    new QueryWrapper<AgentRunAttempt>()
                                            .select("COALESCE(MAX(attempt_no), 0)")
                                            .eq("run_id", run.getId()))
                            .stream()
                            .findFirst()
                            .map(value -> value == null ? 0L : ((Number) value).longValue())
                            .orElse(0L);
            AgentRunAttempt attempt = new AgentRunAttempt();
            attempt.setRunId(run.getId());
            int nextAttemptNo = maxAttemptNo.intValue() + 1;
            attempt.setAttemptNo(nextAttemptNo);
            attempt.setWorkerId(workerId);
            attempt.setStartedAt(currentTime);
            attempt.setHeartbeatAt(currentTime);
            attempt.setLeaseExpiresAt(leaseExpiresAt);
            attempt.setCreatedAt(currentTime);
            attempt.setUpdatedAt(currentTime);
            attemptMapper.insert(attempt);
        } else {
            AgentRunAttempt attempt = getOpenAttempt(runId, workerId);
            if (attempt != null) {
                updateAttemptLease(attempt.getId(), currentTime, leaseExpiresAt, currentTime);
            }
        }

        runMapper.update(
                null,
                new LambdaUpdateWrapper<AgentRun>()
                        .eq(AgentRun::getId, run.getId())
                        .set(AgentRun::getStatus, newStatus)
                        .set(AgentRun::getWorkerId, workerId)
                        .set(AgentRun::getHeartbeatAt, currentTime)
                        .set(AgentRun::getLeaseExpiresAt, leaseExpiresAt)
                        .set(AgentRun::getStartedAt, startedAt)
                        .set(AgentRun::getUpdatedAt, currentTime));
        run.setStatus(newStatus);
        run.setWorkerId(workerId);
        run.setHeartbeatAt(currentTime);
        run.setLeaseExpiresAt(leaseExpiresAt);
        run.setStartedAt(startedAt);
        run.setUpdatedAt(currentTime);
        return new RunResult(run, true);
    }

    /** 仅允许当前且尚未过期的 owner 续租。 */
    @Transactional
    public boolean renewLease(String runId, String workerId, double leaseSeconds, LocalDateTime now) {
        if (workerId == null || workerId.trim().isEmpty()) {
            throw new IllegalArgumentException("worker_id 不能为空");
        }
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("lease_seconds 必须大于 0");
        }
        AgentRun run = lockRun(runId);
        LocalDateTime currentTime = now == null ? DateTimeUtils.utcNowNaive() : now;
        if (run == null
                || !LEASED_RUN_STATUSES.contains(run.getStatus())
                || !workerId.equals(run.getWorkerId())
                || run.getLeaseExpiresAt() == null
                || !run.getLeaseExpiresAt().isAfter(currentTime)) {
            return false;
        }
        LocalDateTime leaseExpiresAt = currentTime.plusNanos((long) (leaseSeconds * 1_000_000_000L));
        AgentRunAttempt attempt = getOpenAttempt(runId, workerId);
        if (attempt != null) {
            updateAttemptLease(attempt.getId(), currentTime, leaseExpiresAt, currentTime);
        }
        runMapper.update(
                null,
                new LambdaUpdateWrapper<AgentRun>()
                        .eq(AgentRun::getId, runId)
                        .set(AgentRun::getHeartbeatAt, currentTime)
                        .set(AgentRun::getLeaseExpiresAt, leaseExpiresAt)
                        .set(AgentRun::getUpdatedAt, currentTime));
        return true;
    }

    /** 仅由 lease 尚有效的当前 attempt 释放 retry ownership。 */
    @Transactional
    public boolean releaseLeaseForRetry(String runId, String workerId, LocalDateTime now) {
        AgentRun run = lockRun(runId);
        LocalDateTime currentTime = now == null ? DateTimeUtils.utcNowNaive() : now;
        if (run == null
                || !"running".equals(run.getStatus())
                || !workerId.equals(run.getWorkerId())
                || run.getLeaseExpiresAt() == null
                || !run.getLeaseExpiresAt().isAfter(currentTime)) {
            return false;
        }
        runMapper.update(
                null,
                new LambdaUpdateWrapper<AgentRun>()
                        .eq(AgentRun::getId, runId)
                        .set(AgentRun::getStatus, "pending")
                        .set(AgentRun::getWorkerId, null)
                        .set(AgentRun::getHeartbeatAt, null)
                        .set(AgentRun::getLeaseExpiresAt, null)
                        .set(AgentRun::getRuntimeCleanupPending, !"subagent".equals(run.getRunType()))
                        .set(AgentRun::getUpdatedAt, currentTime));
        finishOpenAttempt(runId, workerId, "retry_released", null, null, currentTime);
        return true;
    }

    /** 把失去 owner 的活跃 Run 原子收敛为失败事实。 */
    @Transactional
    public ReconcileResult reconcileExpiredLeases(LocalDateTime now) {
        LocalDateTime currentTime = now == null ? DateTimeUtils.utcNowNaive() : now;
        QueryWrapper<AgentRun> wrapper = new QueryWrapper<>();
        wrapper.apply(
                        "(status = {0} AND (lease_expires_at IS NULL OR lease_expires_at <= {1})) "
                                + "OR (status = {2} AND worker_id IS NOT NULL AND (lease_expires_at IS NULL OR lease_expires_at <= {1})) "
                                + "OR (status = {2} AND worker_id IS NULL AND started_at IS NOT NULL)",
                        "running",
                        currentTime,
                        "cancel_requested")
                .last("FOR UPDATE SKIP LOCKED");
        List<AgentRun> runs = new ArrayList<>(runMapper.selectList(wrapper));
        runs.sort(Comparator.comparing(run -> run.getCreatedByRunId() != null));

        List<AgentRun> reconciledRuns = new ArrayList<>();
        List<String[]> cancelledDescendants = new ArrayList<>();
        for (AgentRun run : runs) {
            if (TERMINAL_RUN_STATUSES.contains(run.getStatus())) {
                continue;
            }
            run.setStatus("failed");
            run.setErrorType("worker_lease_expired");
            run.setErrorMessage(
                    "执行 worker 的 lease 已过期；本次运行结果未知，需按 at-least-once 语义检查副作用。");
            run.setFinishedAt(currentTime);
            run.setUpdatedAt(currentTime);
            runMapper.update(
                    null,
                    new LambdaUpdateWrapper<AgentRun>()
                            .eq(AgentRun::getId, run.getId())
                            .set(AgentRun::getStatus, run.getStatus())
                            .set(AgentRun::getErrorType, run.getErrorType())
                            .set(AgentRun::getErrorMessage, run.getErrorMessage())
                            .set(AgentRun::getFinishedAt, currentTime)
                            .set(AgentRun::getWorkerId, null)
                            .set(AgentRun::getHeartbeatAt, null)
                            .set(AgentRun::getLeaseExpiresAt, null)
                            .set(AgentRun::getRuntimeCleanupPending, !"subagent".equals(run.getRunType()))
                            .set(AgentRun::getUpdatedAt, currentTime));
            projectInputDeliveryStatus(run);
            closeRunningAudits(run.getId(), "abandoned", currentTime, false);
            closeOpenAttempts(
                    run.getId(),
                    "lease_expired",
                    "worker_lease_expired",
                    "执行 worker 的 lease 已过期；本次运行结果未知。",
                    currentTime);
            reconciledRuns.add(run);
            cancelledDescendants.addAll(cancelActiveExecutionTreeDescendants(run));
        }
        return new ReconcileResult(reconciledRuns, cancelledDescendants);
    }

    /** 停机迁移时把已失去运行环境的 Run 收敛为可观察失败事实。 */
    @Transactional
    public List<String> failNonterminalForStorageMigration() {
        LocalDateTime currentTime = DateTimeUtils.utcNowNaive();
        List<AgentRun> runs =
                runMapper.selectList(
                        new LambdaQueryWrapper<AgentRun>()
                                .notIn(AgentRun::getStatus, TERMINAL_RUN_STATUSES)
                                .orderByAsc(AgentRun::getCreatedAt)
                                .orderByAsc(AgentRun::getId)
                                .last("FOR UPDATE"));
        List<String> runIds = new ArrayList<>();
        for (AgentRun run : runs) {
            run.setStatus("failed");
            run.setErrorType("storage_migration");
            run.setErrorMessage("存储升级已停止旧运行环境；本次运行未完成");
            run.setFinishedAt(currentTime);
            run.setUpdatedAt(currentTime);
            runMapper.update(
                    null,
                    new LambdaUpdateWrapper<AgentRun>()
                            .eq(AgentRun::getId, run.getId())
                            .set(AgentRun::getStatus, run.getStatus())
                            .set(AgentRun::getErrorType, run.getErrorType())
                            .set(AgentRun::getErrorMessage, run.getErrorMessage())
                            .set(AgentRun::getFinishedAt, currentTime)
                            .set(AgentRun::getWorkerId, null)
                            .set(AgentRun::getHeartbeatAt, null)
                            .set(AgentRun::getLeaseExpiresAt, null)
                            // quiescence proof 已证明旧 runtime 不存在，无需再创建异步清理任务。
                            .set(AgentRun::getRuntimeCleanupPending, false)
                            .set(AgentRun::getUpdatedAt, currentTime));
            projectInputDeliveryStatus(run);
            closeRunningAudits(run.getId(), "abandoned", currentTime, false);
            closeOpenAttempts(run.getId(), "failed", run.getErrorType(), run.getErrorMessage(), currentTime);
            runIds.add(run.getId());
        }
        return runIds;
    }

    /** 按 root 到 descendants 的固定锁顺序取消一棵执行树。 */
    @Transactional
    public CancelTreeResult requestCancelExecutionTree(String runId, String uid, boolean cascadeDescendants) {
        AgentRun run = lockRunForUser(runId, String.valueOf(uid));
        if (run == null) {
            return new CancelTreeResult(null, new ArrayList<>());
        }
        requestCancelLocked(run);
        List<String> cancelledIds = new ArrayList<>();
        cancelledIds.add(run.getId());
        if (cascadeDescendants) {
            for (String[] entry : cancelActiveExecutionTreeDescendants(run)) {
                cancelledIds.add(entry[0]);
            }
        }
        return new CancelTreeResult(run, cancelledIds);
    }

    /** 转换一条已由当前事务锁定的 Run。 */
    @Transactional
    public void requestCancelLocked(AgentRun run) {
        if (TERMINAL_RUN_STATUSES.contains(run.getStatus())) {
            return;
        }
        LocalDateTime currentTime = DateTimeUtils.utcNowNaive();
        if ("pending".equals(run.getStatus()) && run.getWorkerId() == null && run.getStartedAt() == null) {
            run.setStatus("cancelled");
            run.setErrorType("cancelled");
            run.setErrorMessage("对话已在执行前取消");
            run.setFinishedAt(currentTime);
            run.setUpdatedAt(currentTime);
            runMapper.update(
                    null,
                    new LambdaUpdateWrapper<AgentRun>()
                            .eq(AgentRun::getId, run.getId())
                            .set(AgentRun::getStatus, "cancelled")
                            .set(AgentRun::getErrorType, "cancelled")
                            .set(AgentRun::getErrorMessage, "对话已在执行前取消")
                            .set(AgentRun::getFinishedAt, currentTime)
                            .set(AgentRun::getRuntimeCleanupPending, false)
                            .set(AgentRun::getUpdatedAt, currentTime));
            projectInputDeliveryStatus(run);
            return;
        }
        run.setStatus("cancel_requested");
        run.setUpdatedAt(currentTime);
        runMapper.update(
                null,
                new LambdaUpdateWrapper<AgentRun>()
                        .eq(AgentRun::getId, run.getId())
                        .set(AgentRun::getStatus, "cancel_requested")
                        .set(AgentRun::getUpdatedAt, currentTime));
    }

    /** 在父 Run 状态事务内请求仍活跃的 execution tree 后代停止。 */
    @Transactional
    public List<String[]> cancelActiveExecutionTreeDescendants(AgentRun rootRun) {
        if ("subagent".equals(rootRun.getRunType())) {
            return new ArrayList<>();
        }
        LocalDateTime currentTime = DateTimeUtils.utcNowNaive();
        List<String[]> cancelled = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        List<String> pendingParentIds = new ArrayList<>();
        pendingParentIds.add(rootRun.getId());
        while (!pendingParentIds.isEmpty()) {
            List<String> parentIds = pendingParentIds;
            pendingParentIds = new ArrayList<>();
            List<AgentRun> children =
                    runMapper.selectList(
                            new LambdaQueryWrapper<AgentRun>()
                                    .in(AgentRun::getCreatedByRunId, parentIds)
                                    .eq(AgentRun::getUid, String.valueOf(rootRun.getUid()))
                                    .eq(AgentRun::getRuntimeScopeId, String.valueOf(rootRun.getRuntimeScopeId()))
                                    .notIn(AgentRun::getStatus, TERMINAL_RUN_STATUSES)
                                    .orderByAsc(AgentRun::getCreatedAt)
                                    .orderByAsc(AgentRun::getId)
                                    .last("FOR UPDATE"));
            for (AgentRun child : children) {
                if (!seenIds.add(child.getId())) {
                    continue;
                }
                pendingParentIds.add(child.getId());
                child.setErrorType("execution_tree_closed");
                child.setErrorMessage("父运行已结束，请停止共享执行树");
                boolean resolvable =
                        "pending".equals(child.getStatus())
                                && child.getWorkerId() == null
                                && child.getStartedAt() == null;
                LambdaUpdateWrapper<AgentRun> update =
                        new LambdaUpdateWrapper<AgentRun>()
                                .eq(AgentRun::getId, child.getId())
                                .set(AgentRun::getErrorType, child.getErrorType())
                                .set(AgentRun::getErrorMessage, child.getErrorMessage())
                                .set(AgentRun::getUpdatedAt, currentTime);
                if (resolvable) {
                    update.set(AgentRun::getStatus, "cancelled")
                            .set(AgentRun::getFinishedAt, currentTime)
                            .set(AgentRun::getWorkerId, null)
                            .set(AgentRun::getHeartbeatAt, null)
                            .set(AgentRun::getLeaseExpiresAt, null);
                    runMapper.update(null, update);
                    projectInputDeliveryStatus(child);
                    closeOpenAttempts(
                            child.getId(),
                            "cancelled",
                            child.getErrorType(),
                            child.getErrorMessage(),
                            currentTime);
                } else {
                    update.set(AgentRun::getStatus, "cancel_requested");
                    runMapper.update(null, update);
                }
                cancelled.add(new String[] {child.getId(), child.getConversationThreadId()});
            }
        }
        return cancelled;
    }

    /** 提交 Run 终态。 */
    @Transactional
    public RunResult setTerminalStatus(
            String runId,
            String status,
            String errorType,
            String errorMessage,
            Map<String, Object> tokenUsage,
            String workerId,
            LocalDateTime now) {
        if (!TERMINAL_RUN_STATUSES.contains(status)) {
            throw new IllegalArgumentException("不支持的 AgentRun 终态：" + status);
        }
        AgentRun run = lockRun(runId);
        if (run == null) {
            return new RunResult(null, false);
        }
        if (TERMINAL_RUN_STATUSES.contains(run.getStatus())) {
            if (run.getWorkerId() != null || run.getHeartbeatAt() != null || run.getLeaseExpiresAt() != null) {
                runMapper.update(
                        null,
                        new LambdaUpdateWrapper<AgentRun>()
                                .eq(AgentRun::getId, runId)
                                .set(AgentRun::getWorkerId, null)
                                .set(AgentRun::getHeartbeatAt, null)
                                .set(AgentRun::getLeaseExpiresAt, null));
            }
            return new RunResult(run, false);
        }

        LocalDateTime currentTime = now == null ? DateTimeUtils.utcNowNaive() : now;
        if ("pending".equals(run.getStatus())) {
            if (workerId != null || !Set.of("failed", "cancelled").contains(status)) {
                return new RunResult(run, false);
            }
        } else if (LEASED_RUN_STATUSES.contains(run.getStatus())) {
            if (!String.valueOf(workerId).equals(run.getWorkerId())
                    || run.getLeaseExpiresAt() == null
                    || !run.getLeaseExpiresAt().isAfter(currentTime)) {
                return new RunResult(run, false);
            }
            if ("cancel_requested".equals(run.getStatus()) && !"cancelled".equals(status)) {
                return new RunResult(run, false);
            }
            if ("running".equals(run.getStatus()) && "cancelled".equals(status)) {
                return new RunResult(run, false);
            }
        } else {
            return new RunResult(run, false);
        }

        if ("completed".equals(status)) {
            if (run.getOutputMessageId() == null || getMatchingOutputMessage(run, run.getOutputMessageId()) == null) {
                throw new IllegalArgumentException("AgentRun 完成前必须绑定同一 Run 的有效 assistant 输出消息");
            }
        }

        run.setStatus(status);
        run.setErrorType(errorType);
        run.setErrorMessage(errorMessage);
        run.setTokenUsage(JSON.toJSONString(tokenUsage == null ? new LinkedHashMap<>() : tokenUsage));
        run.setFinishedAt(currentTime);
        run.setUpdatedAt(currentTime);
        run.setRuntimeCleanupPending(!"subagent".equals(run.getRunType()));
        runMapper.update(
                null,
                new LambdaUpdateWrapper<AgentRun>()
                        .eq(AgentRun::getId, runId)
                        .set(AgentRun::getStatus, status)
                        .set(AgentRun::getErrorType, errorType)
                        .set(AgentRun::getErrorMessage, errorMessage)
                        .set(AgentRun::getTokenUsage, run.getTokenUsage())
                        .set(AgentRun::getFinishedAt, currentTime)
                        .set(AgentRun::getWorkerId, null)
                        .set(AgentRun::getHeartbeatAt, null)
                        .set(AgentRun::getLeaseExpiresAt, null)
                        .set(AgentRun::getRuntimeCleanupPending, run.getRuntimeCleanupPending())
                        .set(AgentRun::getUpdatedAt, currentTime));
        projectInputDeliveryStatus(run);
        closeRunningAudits(run.getId(), TERMINAL_AUDIT_STATUS.get(status), currentTime, "interrupted".equals(status));
        finishOpenAttempt(runId, workerId, status, errorType, errorMessage, currentTime);
        return new RunResult(run, true);
    }

    /** 列出仍待清理根 runtime 的 run。 */
    public List<AgentRun> listPendingRuntimeCleanups(int limit) {
        return runMapper.selectList(
                new LambdaQueryWrapper<AgentRun>()
                        .eq(AgentRun::getRuntimeCleanupPending, true)
                        .ne(AgentRun::getRunType, "subagent")
                        .orderByAsc(AgentRun::getFinishedAt)
                        .orderByAsc(AgentRun::getId)
                        .last("LIMIT " + limit));
    }

    /** 由当前 lease owner 在首次执行前 write-once 固化运行清单。 */
    @Transactional
    public RunResult recordRunManifest(
            String runId, Map<String, Object> manifest, String fingerprint, String workerId, LocalDateTime now) {
        if (workerId == null || workerId.trim().isEmpty()) {
            throw new IllegalArgumentException("worker_id 不能为空");
        }
        if (fingerprint == null || fingerprint.trim().isEmpty()) {
            throw new IllegalArgumentException("fingerprint 不能为空");
        }
        AgentRun run = lockRun(runId);
        if (run == null) {
            return new RunResult(null, false);
        }
        LocalDateTime currentTime = now == null ? DateTimeUtils.utcNowNaive() : now;
        requireLeaseOwner(run, workerId, currentTime, "固化运行清单");
        if (run.getManifestFingerprint() != null) {
            return new RunResult(run, false);
        }
        run.setManifest(JSON.toJSONString(manifest));
        run.setManifestFingerprint(JSON.toJSONString(fingerprint));
        run.setManifestRecordedAt(currentTime);
        run.setUpdatedAt(currentTime);
        runMapper.update(
                null,
                new LambdaUpdateWrapper<AgentRun>()
                        .eq(AgentRun::getId, runId)
                        .set(AgentRun::getManifest, run.getManifest())
                        .set(AgentRun::getManifestFingerprint, run.getManifestFingerprint())
                        .set(AgentRun::getManifestRecordedAt, currentTime)
                        .set(AgentRun::getUpdatedAt, currentTime));
        return new RunResult(run, true);
    }

    /** 由当前 lease owner write-once 记录运行准备完成时间。 */
    @Transactional
    public RunResult recordPrepared(String runId, String workerId, LocalDateTime observedAt, LocalDateTime checkedAt) {
        AgentRun run = lockRun(runId);
        if (run == null) {
            return new RunResult(null, false);
        }
        if (run.getPreparedAt() != null) {
            return new RunResult(run, false);
        }
        LocalDateTime leaseCheckTime = checkedAt == null ? DateTimeUtils.utcNowNaive() : checkedAt;
        requireLeaseOwner(run, workerId, leaseCheckTime, "记录运行准备时间");
        LocalDateTime eventTime = observedAt == null ? leaseCheckTime : observedAt;
        if (run.getStartedAt() == null || eventTime.isBefore(run.getStartedAt())) {
            throw new IllegalArgumentException("AgentRun 准备时间不能早于开始时间");
        }
        run.setPreparedAt(eventTime);
        run.setUpdatedAt(leaseCheckTime);
        runMapper.update(
                null,
                new LambdaUpdateWrapper<AgentRun>()
                        .eq(AgentRun::getId, runId)
                        .set(AgentRun::getPreparedAt, eventTime)
                        .set(AgentRun::getUpdatedAt, leaseCheckTime));
        return new RunResult(run, true);
    }

    /** 由当前 lease owner write-once 记录首次进入模型请求边界的时间。 */
    @Transactional
    public RunResult recordFirstModelRequest(
            String runId, String workerId, LocalDateTime observedAt, LocalDateTime checkedAt) {
        AgentRun run = lockRun(runId);
        if (run == null) {
            return new RunResult(null, false);
        }
        if (run.getFirstModelRequestAt() != null) {
            return new RunResult(run, false);
        }
        LocalDateTime leaseCheckTime = checkedAt == null ? DateTimeUtils.utcNowNaive() : checkedAt;
        // 取消请求不抹去已经发生的调用；仅此观测允许仍持有效 lease 的取消中 Run 补写。
        if (!LEASED_RUN_STATUSES.contains(run.getStatus())
                || !String.valueOf(workerId).equals(run.getWorkerId())
                || run.getLeaseExpiresAt() == null
                || !run.getLeaseExpiresAt().isAfter(leaseCheckTime)) {
            throw new IllegalStateException("只有当前有效 AgentRun lease owner 可以记录首次模型请求时间");
        }
        LocalDateTime eventTime = observedAt == null ? leaseCheckTime : observedAt;
        if (run.getCreatedAt() != null && eventTime.isBefore(run.getCreatedAt())) {
            throw new IllegalArgumentException("AgentRun 首次模型请求时间不能早于创建时间");
        }
        run.setFirstModelRequestAt(eventTime);
        run.setUpdatedAt(leaseCheckTime);
        runMapper.update(
                null,
                new LambdaUpdateWrapper<AgentRun>()
                        .eq(AgentRun::getId, runId)
                        .set(AgentRun::getFirstModelRequestAt, eventTime)
                        .set(AgentRun::getUpdatedAt, leaseCheckTime));
        return new RunResult(run, true);
    }

    /** 由当前 lease owner write-once 记录首个模型语义输出时间。 */
    @Transactional
    public RunResult recordFirstOutput(String runId, String workerId, LocalDateTime observedAt, LocalDateTime checkedAt) {
        AgentRun run = lockRun(runId);
        if (run == null) {
            return new RunResult(null, false);
        }
        if (run.getFirstOutputAt() != null) {
            return new RunResult(run, false);
        }
        LocalDateTime leaseCheckTime = checkedAt == null ? DateTimeUtils.utcNowNaive() : checkedAt;
        requireLeaseOwner(run, workerId, leaseCheckTime, "记录首次模型输出时间");
        LocalDateTime eventTime = observedAt == null ? leaseCheckTime : observedAt;
        if (run.getPreparedAt() == null || eventTime.isBefore(run.getPreparedAt())) {
            throw new IllegalArgumentException("AgentRun 首次输出时间不能早于准备完成时间");
        }
        run.setFirstOutputAt(eventTime);
        run.setUpdatedAt(leaseCheckTime);
        runMapper.update(
                null,
                new LambdaUpdateWrapper<AgentRun>()
                        .eq(AgentRun::getId, runId)
                        .set(AgentRun::getFirstOutputAt, eventTime)
                        .set(AgentRun::getUpdatedAt, leaseCheckTime));
        return new RunResult(run, true);
    }

    /** 按执行序号读取一个 Run 的完整 attempt 历史。 */
    public List<AgentRunAttempt> listRunAttempts(String runId) {
        return attemptMapper.selectList(
                new LambdaQueryWrapper<AgentRunAttempt>()
                        .eq(AgentRunAttempt::getRunId, runId)
                        .orderByAsc(AgentRunAttempt::getAttemptNo)
                        .orderByAsc(AgentRunAttempt::getId));
    }

    // ==================== 内部工具 ====================

    /** 在 Run owning transaction 内关闭尚无 terminal 事实的 Model/Tool 审计行。 */
    private void closeRunningAudits(
            String runId, String executionStatus, LocalDateTime now, boolean preservePendingToolCalls) {
        List<Message> runningTools =
                messageMapper.selectList(
                        new LambdaQueryWrapper<Message>()
                                .select(Message::getId, Message::getExtraMetadata)
                                .eq(Message::getRunId, runId)
                                .eq(Message::getMessageType, ModelConstants.TOOL_AUDIT_MESSAGE_TYPE)
                                .eq(Message::getExecutionStatus, "running"));
        Map<Integer, JSONObject> toolMetadata = new LinkedHashMap<>();
        for (Message message : runningTools) {
            JSONObject metadata = RepoValues.parseObject(message.getExtraMetadata());
            Object toolCallId = metadata.get("compatibility_tool_call_id");
            if (toolCallId instanceof Number number) {
                toolMetadata.put(number.intValue(), metadata);
            }
        }
        if (!toolMetadata.isEmpty() && !preservePendingToolCalls) {
            List<ToolCall> toolCalls =
                    toolCallMapper.selectList(
                            new LambdaQueryWrapper<ToolCall>().in(ToolCall::getId, toolMetadata.keySet()));
            for (ToolCall toolCall : toolCalls) {
                JSONObject metadata = toolMetadata.get(toolCall.getId());
                String message = metadata.getString("error_message");
                toolCallMapper.update(
                        null,
                        new LambdaUpdateWrapper<ToolCall>()
                                .eq(ToolCall::getId, toolCall.getId())
                                .set(ToolCall::getStatus, "error")
                                .set(
                                        ToolCall::getErrorMessage,
                                        message == null || message.isEmpty()
                                                ? ("Tool 审计由 Run 终态收敛为 " + executionStatus)
                                                : message));
            }
        }
        LambdaUpdateWrapper<Message> wrapper =
                new LambdaUpdateWrapper<Message>()
                        .eq(Message::getRunId, runId)
                        .in(Message::getMessageType, ModelConstants.AUDIT_MESSAGE_TYPES)
                        .eq(Message::getExecutionStatus, "running")
                        .set(Message::getExecutionStatus, executionStatus);
        wrapper.setSql("finished_at = COALESCE(finished_at, '" + sqlTimestamp(now) + "')");
        messageMapper.update(null, wrapper);
    }

    /** 拼进 SQL 的时间字面量：固定 yyyy-MM-dd HH:mm:ss，不依赖 MySQL 对 ISO 分隔符的宽松解析。 */
    private static String sqlTimestamp(LocalDateTime value) {
        return value.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /** 在 owning transaction 内同步输入消息的终态投影。 */
    private void projectInputDeliveryStatus(AgentRun run) {
        String deliveryStatus = RUN_STATUS_TO_DELIVERY_STATUS.get(run.getStatus());
        if (run.getInputMessageId() == null || deliveryStatus == null) {
            return;
        }
        messageMapper.update(
                null,
                new LambdaUpdateWrapper<Message>()
                        .eq(Message::getId, run.getInputMessageId())
                        .set(Message::getDeliveryStatus, deliveryStatus));
    }

    private AgentRunAttempt getOpenAttempt(String runId, String workerId) {
        LambdaQueryWrapper<AgentRunAttempt> wrapper =
                new LambdaQueryWrapper<AgentRunAttempt>()
                        .eq(AgentRunAttempt::getRunId, runId)
                        .isNull(AgentRunAttempt::getFinishedAt);
        if (workerId != null) {
            wrapper.eq(AgentRunAttempt::getWorkerId, workerId);
        }
        wrapper.orderByDesc(AgentRunAttempt::getAttemptNo).last("LIMIT 1");
        return attemptMapper.selectOne(wrapper);
    }

    private void updateAttemptLease(Integer attemptId, LocalDateTime heartbeatAt, LocalDateTime leaseExpiresAt, LocalDateTime updatedAt) {
        attemptMapper.update(
                null,
                new LambdaUpdateWrapper<AgentRunAttempt>()
                        .eq(AgentRunAttempt::getId, attemptId)
                        .set(AgentRunAttempt::getHeartbeatAt, heartbeatAt)
                        .set(AgentRunAttempt::getLeaseExpiresAt, leaseExpiresAt)
                        .set(AgentRunAttempt::getUpdatedAt, updatedAt));
    }

    /** 终结当前开放 attempt；已终结的 attempt 事实不会被改写。 */
    private void finishOpenAttempt(
            String runId, String workerId, String outcome, String errorType, String errorMessage, LocalDateTime now) {
        AgentRunAttempt attempt = getOpenAttempt(runId, workerId);
        if (attempt == null) {
            return;
        }
        attemptMapper.update(
                null,
                new LambdaUpdateWrapper<AgentRunAttempt>()
                        .eq(AgentRunAttempt::getId, attempt.getId())
                        .set(AgentRunAttempt::getOutcome, outcome)
                        .set(AgentRunAttempt::getErrorType, errorType)
                        .set(AgentRunAttempt::getErrorMessage, errorMessage)
                        .set(AgentRunAttempt::getFinishedAt, now)
                        .set(AgentRunAttempt::getUpdatedAt, now));
    }

    /** 收敛该 Run 全部仍开放的 attempt；用于失联 Run 被接管或收敛时。 */
    private void closeOpenAttempts(
            String runId, String outcome, String errorType, String errorMessage, LocalDateTime now) {
        attemptMapper.update(
                null,
                new LambdaUpdateWrapper<AgentRunAttempt>()
                        .eq(AgentRunAttempt::getRunId, runId)
                        .isNull(AgentRunAttempt::getFinishedAt)
                        .set(AgentRunAttempt::getOutcome, outcome)
                        .set(AgentRunAttempt::getErrorType, errorType)
                        .set(AgentRunAttempt::getErrorMessage, errorMessage)
                        .set(AgentRunAttempt::getFinishedAt, now)
                        .set(AgentRunAttempt::getUpdatedAt, now));
    }

    /** 读取满足 AgentRun 因果归属后置条件的输出消息。 */
    private Message getMatchingOutputMessage(AgentRun run, Integer messageId) {
        return messageMapper.selectOne(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getId, messageId)
                        .eq(Message::getConversationId, run.getConversationId())
                        .eq(Message::getRunId, run.getId())
                        .eq(Message::getRequestId, run.getRequestId())
                        .eq(Message::getRole, "assistant"));
    }

    /** 只有当前有效 lease owner 才能执行的动作前置校验。 */
    static void requireLeaseOwner(AgentRun run, String workerId, LocalDateTime now, String action) {
        if (!"running".equals(run.getStatus())
                || !String.valueOf(workerId).equals(run.getWorkerId())
                || run.getLeaseExpiresAt() == null
                || !run.getLeaseExpiresAt().isAfter(now)) {
            throw new IllegalStateException("只有当前有效 AgentRun lease owner 可以" + action);
        }
    }

    private AgentRun lockRun(String runId) {
        return runMapper.selectOne(new LambdaQueryWrapper<AgentRun>().eq(AgentRun::getId, runId).last("FOR UPDATE"));
    }

    private static String placeholders(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(i == 0 ? "?" : ", ?");
        }
        return builder.toString();
    }

    /** 便于其他仓储复用：终态集合（不可变）。 */
    public static Set<String> terminalRunStatuses() {
        return Collections.unmodifiableSet(TERMINAL_RUN_STATUSES);
    }
}
