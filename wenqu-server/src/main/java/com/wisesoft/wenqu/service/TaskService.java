package com.wisesoft.wenqu.service;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.TaskRecord;
import com.wisesoft.wenqu.repositories.TaskRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 持久 Task Service 门面；执行只发生在独立 worker。
 *
 * <p>由参考实现的 services/task_service.py 逐类逐方法翻译：Task DTO（键名照搬）、
 * TaskContext（租约保护的进度/取消边界）、Tasker（入队/去重/发布/查询/取消/删除）、
 * 以及 worker 侧的 {@link #processTask}（从 PG Task 意图重建并执行一个注册 Handler）。
 *
 * <p>必要替换：
 * <ul>
 *   <li>{@code asyncio.create_task} 执行 handler → 线程池提交；心跳协程 → 周期调度任务；
 *       {@code asyncio.wait(timeout=...)} → {@code Future.get(timeout)}；
 *       {@code asyncio.CancelledError} → {@link TaskContext.Cancelled}（携带取消原因，
 *       语义同为协作式取消：handler 经 {@code raise_if_cancelled} 感知）。
 *       参考实现"当前任务正在被取消时重新抛出"在 Java 无对应（线程取消不传播），
 *       省略；参考实现的"handler 内 finally 清理"语义不变。
 *   <li>ORM 的 {@code to_dict / to_summary_dict} → DTO 的 {@code fromRecord / toMap / toSummaryMap}
 *       （键名与时间格式照搬，{@code format_utc_datetime} → {@code formatUtcDatetime}）。
 *   <li>{@code ValueError} → {@code IllegalArgumentException}。
 * </ul>
 */
@Service
public class TaskService {

    public static final Set<String> TERMINAL_STATUSES = TaskRepository.TERMINAL_TASK_STATUSES;
    public static final double PROGRESS_PERSIST_DELTA = 2.0;
    public static final double TASKER_DEFAULT_TIMEOUT_SECONDS =
            Double.parseDouble(System.getenv().getOrDefault("TASKER_DEFAULT_TIMEOUT_SECONDS", String.valueOf(6 * 60 * 60)));
    public static final int DURABLE_TASK_MAX_RUNNING = 4;

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskRepository repo;
    private final TaskQueueService taskQueueService;
    private final ExecutorService handlerExecutor;
    private final ScheduledExecutorService heartbeatExecutor;
    private final double defaultTimeoutSeconds;

    public TaskService(TaskRepository repo, TaskQueueService taskQueueService) {
        this(repo, taskQueueService, TASKER_DEFAULT_TIMEOUT_SECONDS);
    }

    TaskService(TaskRepository repo, TaskQueueService taskQueueService, double defaultTimeoutSeconds) {
        this.repo = repo;
        this.taskQueueService = taskQueueService;
        this.defaultTimeoutSeconds = validateTimeoutSeconds(defaultTimeoutSeconds);
        this.handlerExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "durable-task-handler");
            thread.setDaemon(true);
            return thread;
        });
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "durable-task-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    public double defaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    // ==================== Task DTO ====================

    /** 任务视图（键名与参考实现 Task dataclass 一致，时间为 UTC ISO 字符串）。 */
    public static final class Task {
        public String id;
        public String name;
        public String type;
        public String status = "pending";
        public double progress = 0.0;
        public String message = "";
        public String createdAt = DateTimeUtils.utcIsoformat();
        public String updatedAt = DateTimeUtils.utcIsoformat();
        public String startedAt;
        public String completedAt;
        public Map<String, Object> payload = new LinkedHashMap<>();
        public Object result;
        public String error;
        public boolean cancelRequested = false;
        public int handlerVersion = 1;
        public String dedupeKey;
        public int attemptCount = 0;
        public String workerId;
        public String heartbeatAt;
        public String leaseExpiresAt;
        public double timeoutSeconds = TASKER_DEFAULT_TIMEOUT_SECONDS;

        static Task fromRecord(TaskRecord record) {
            Task task = new Task();
            task.id = record.getId();
            task.name = record.getName();
            task.type = record.getType();
            task.status = record.getStatus();
            task.progress = record.getProgress() == null ? 0.0 : record.getProgress();
            task.message = record.getMessage();
            task.createdAt = DateTimeUtils.formatUtcDatetime(record.getCreatedAt());
            task.updatedAt = DateTimeUtils.formatUtcDatetime(record.getUpdatedAt());
            task.startedAt = DateTimeUtils.formatUtcDatetime(record.getStartedAt());
            task.completedAt = DateTimeUtils.formatUtcDatetime(record.getCompletedAt());
            task.payload = RepoValuesJson.toMap(record.getPayload());
            task.result = record.getResult() == null ? null : JSON.parse(record.getResult());
            task.error = record.getError();
            task.cancelRequested = record.getCancelRequested() != null && record.getCancelRequested() != 0;
            task.handlerVersion = record.getHandlerVersion() == null ? 1 : record.getHandlerVersion();
            task.dedupeKey = record.getDedupeKey();
            task.attemptCount = record.getAttemptCount() == null ? 0 : record.getAttemptCount();
            task.workerId = record.getWorkerId();
            task.heartbeatAt = DateTimeUtils.formatUtcDatetime(record.getHeartbeatAt());
            task.leaseExpiresAt = DateTimeUtils.formatUtcDatetime(record.getLeaseExpiresAt());
            task.timeoutSeconds = record.getTimeoutSeconds() == null ? 0.0 : record.getTimeoutSeconds();
            return task;
        }

        /** to_summary_dict：去掉 payload 与 result。 */
        Map<String, Object> toSummaryMap() {
            Map<String, Object> data = toMap();
            data.remove("payload");
            data.remove("result");
            return data;
        }

        Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", id);
            data.put("name", name);
            data.put("type", type);
            data.put("status", status);
            data.put("progress", progress);
            data.put("message", message);
            data.put("created_at", createdAt);
            data.put("updated_at", updatedAt);
            data.put("started_at", startedAt);
            data.put("completed_at", completedAt);
            data.put("payload", payload);
            data.put("result", result);
            data.put("error", error);
            data.put("cancel_requested", cancelRequested);
            data.put("handler_version", handlerVersion);
            data.put("dedupe_key", dedupeKey);
            data.put("attempt_count", attemptCount);
            data.put("worker_id", workerId);
            data.put("heartbeat_at", heartbeatAt);
            data.put("lease_expires_at", leaseExpiresAt);
            data.put("timeout_seconds", timeoutSeconds);
            return data;
        }
    }

    /** JSON 列读取辅助（payload 列可能为空或非对象）。 */
    private static final class RepoValuesJson {
        static Map<String, Object> toMap(String json) {
            if (json == null || json.isEmpty()) {
                return new LinkedHashMap<>();
            }
            try {
                return JSON.parseObject(json);
            } catch (Exception exception) {
                return new LinkedHashMap<>();
            }
        }
    }

    // ==================== TaskContext ====================

    /** 向领域 Handler 提供受当前 attempt lease 保护的进度与取消边界。 */
    public static final class TaskContext {
        /** 协作式取消信号（对应参考实现的 asyncio.CancelledError）。 */
        public static final class Cancelled extends RuntimeException {
            public Cancelled(String message) {
                super(message);
            }
        }

        private final String taskId;
        private final String workerId;
        private final Map<String, Object> payload;
        private final TaskRepository repo;
        String cancellationReason;
        private boolean cancelRequested = false;
        private Double lastPersistedProgress;
        private String lastPersistedMessage;

        TaskContext(TaskRepository repo, String taskId, String workerId, Map<String, Object> payload) {
            this.repo = repo;
            this.taskId = taskId;
            this.workerId = workerId;
            this.payload = payload == null ? new LinkedHashMap<>() : payload;
        }

        public String taskId() {
            return taskId;
        }

        public String workerId() {
            return workerId;
        }

        public Map<String, Object> payload() {
            return payload;
        }

        public String cancellationReason() {
            return cancellationReason;
        }

        public void setProgress(double progress, String message) {
            double normalized = Math.max(0.0, Math.min(progress, 100.0));
            boolean progressIsThrottled =
                    lastPersistedProgress != null && Math.abs(normalized - lastPersistedProgress) < PROGRESS_PERSIST_DELTA;
            if (progressIsThrottled && (message == null || message.equals(lastPersistedMessage))) {
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("progress", normalized);
            if (message != null) {
                data.put("message", message);
            }
            update(data);
            lastPersistedProgress = normalized;
            if (message != null) {
                lastPersistedMessage = message;
            }
        }

        public void setMessage(String message) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("message", message);
            update(data);
            lastPersistedMessage = message;
        }

        public void setResult(Object result) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("result", result);
            update(data);
        }

        public boolean isCancelRequested() {
            return cancelRequested;
        }

        /** 在 attempt lease 行锁内提交领域 checkpoint。 */
        public void runOwnedTransaction(TaskRepository.TaskOperation operation) {
            if (!repo.runOwnedTransaction(taskId, workerId, operation)) {
                cancellationReason = "lease_lost";
                throw new Cancelled("Task lease was lost");
            }
        }

        public void raiseIfCancelled() {
            TaskRepository.ControlState state = repo.checkControl(taskId, workerId);
            if (!state.ownsLease()) {
                cancellationReason = "lease_lost";
                throw new Cancelled("Task lease was lost");
            }
            if (state.cancelRequested()) {
                requestCancel("cancelled");
                throw new Cancelled("Task was cancelled");
            }
        }

        void requestCancel(String reason) {
            cancelRequested = "cancelled".equals(reason);
            cancellationReason = reason;
        }

        private void update(Map<String, Object> data) {
            if (!repo.updateOwned(taskId, workerId, data, null)) {
                cancellationReason = "lease_lost";
                throw new Cancelled("Task lease was lost");
            }
        }
    }

    // ==================== Tasker ====================

    @Transactional
    public Task enqueue(String name, String taskType, Map<String, Object> payload, Double timeoutSeconds)
            throws Exception {
        EnqueueResult ignored =
                enqueueInternal(name, taskType, payload == null ? new LinkedHashMap<>() : payload, timeoutSeconds, null);
        return ignored.task();
    }

    /** 按 payload 指纹去重入队；返回任务与是否新建。 */
    @Transactional
    public EnqueueResult enqueueUniqueByPayload(
            String name,
            String taskType,
            Map<String, Object> payload,
            Map<String, Object> payloadMatch,
            Double timeoutSeconds)
            throws Exception {
        return enqueueInternal(
                name,
                taskType,
                payload == null ? new LinkedHashMap<>() : payload,
                timeoutSeconds,
                dedupeKey(taskType, payloadMatch));
    }

    public record EnqueueResult(Task task, boolean created) {}

    /** 在领域 service 事务中创建 Task；调用方提交后必须显式 publish。 */
    @Transactional
    public Task createInSession(
            String name,
            String taskType,
            Map<String, Object> payload,
            Map<String, Object> payloadMatch,
            Double timeoutSeconds)
            throws Exception {
        String taskId = UUID.randomUUID().toString().replace("-", "");
        TaskRecord record =
                repo.createInSession(
                        taskId,
                        buildTaskData(
                                name,
                                taskType,
                                payload,
                                timeoutSeconds,
                                payloadMatch == null ? null : dedupeKey(taskType, payloadMatch)));
        return Task.fromRecord(record);
    }

    /** 在领域 service 事务中按数据库 dedupe 创建 Task。 */
    @Transactional
    public EnqueueResult createUniqueInSession(
            String name,
            String taskType,
            Map<String, Object> payload,
            Map<String, Object> payloadMatch,
            Double timeoutSeconds)
            throws Exception {
        String taskId = UUID.randomUUID().toString().replace("-", "");
        TaskRepository.CreateResult result =
                repo.createOrGetInSession(taskId, buildTaskData(name, taskType, payload, timeoutSeconds, dedupeKey(taskType, payloadMatch)));
        return new EnqueueResult(Task.fromRecord(result.record()), result.created());
    }

    /** 发布已经由 owning transaction 提交的 Task。 */
    public void publish(Task task) {
        publishCreatedTask(task);
    }

    public Task findTaskByPayload(String taskType, Map<String, Object> payloadMatch, Set<String> statuses) {
        TaskRecord record = repo.findLatestByPayload(taskType, payloadMatch, statuses);
        return record == null ? null : Task.fromRecord(record);
    }

    public Map<String, Object> listTasks(String status, int limit) {
        List<TaskRecord> records = repo.list(status, limit);
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (TaskRecord record : records) {
            tasks.add(Task.fromRecord(record).toSummaryMap());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tasks", tasks);
        result.put("summary", repo.summarize(status));
        return result;
    }

    public Map<String, Object> getTask(String taskId) {
        TaskRecord record = repo.getById(taskId);
        return record == null ? null : Task.fromRecord(record).toMap();
    }

    @Transactional
    public Task cancelTask(String taskId) throws Exception {
        TaskRecord current = repo.getById(taskId);
        TaskRepository.BeforeTaskChange beforeCancel = null;
        if (current != null) {
            int handlerVersion = current.getHandlerVersion() == null ? 1 : current.getHandlerVersion();
            TaskRegistry.TaskDefinition definition;
            try {
                definition = TaskRegistry.getFailureTaskDefinition(current.getType(), handlerVersion);
            } catch (IllegalArgumentException exception) {
                return null;
            }
            TaskRegistry.TaskFailureHandler failureHandler = definition.loadFailureHandler();
            if (failureHandler != null) {
                beforeCancel = beforeFinishHook(failureHandler, "任务已取消");
            }
        }
        TaskRecord record = repo.requestCancel(taskId, beforeCancel, null);
        return record == null ? null : Task.fromRecord(record);
    }

    public boolean deleteTask(String taskId) {
        TaskRecord current = repo.getById(taskId);
        if (current == null) {
            return false;
        }
        return repo.deleteTerminal(taskId);
    }

    private void publishCreatedTask(Task task) {
        try {
            taskQueueService.publishTask(task.id);
        } catch (Exception exception) {
            log.error("Task publication failed; pending intent will be retried: task_id={}", task.id, exception);
        }
    }

    private EnqueueResult enqueueInternal(
            String name, String taskType, Map<String, Object> payload, Double timeoutSeconds, String dedupeKey)
            throws Exception {
        TaskRepository.CreateResult result =
                repo.create(UUID.randomUUID().toString().replace("-", ""), buildTaskData(name, taskType, payload, timeoutSeconds, dedupeKey));
        Task task = Task.fromRecord(result.record());
        if (result.created()) {
            publishCreatedTask(task);
        }
        return new EnqueueResult(task, result.created());
    }

    private Map<String, Object> buildTaskData(
            String name, String taskType, Map<String, Object> payload, Double timeoutSeconds, String dedupeKey) {
        TaskRegistry.TaskDefinition definition = TaskRegistry.getTaskDefinition(taskType);
        LocalDateTime now = DateTimeUtils.utcNowNaive();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("type", taskType);
        data.put("status", "pending");
        data.put("progress", 0.0);
        data.put("message", "任务等待 worker 执行");
        data.put("payload", payload);
        data.put("result", null);
        data.put("error", null);
        data.put("cancel_requested", 0);
        data.put("handler_version", definition.version());
        data.put("dedupe_key", dedupeKey);
        data.put("attempt_count", 0);
        data.put("timeout_seconds", resolveTimeoutSeconds(timeoutSeconds));
        data.put("created_at", now);
        data.put("updated_at", now);
        return data;
    }

    private double resolveTimeoutSeconds(Double timeoutSeconds) {
        if (timeoutSeconds == null) {
            return defaultTimeoutSeconds;
        }
        double resolved = validateTimeoutSeconds(timeoutSeconds);
        if (resolved > defaultTimeoutSeconds) {
            throw new IllegalArgumentException("Task timeout cannot exceed the worker default timeout");
        }
        return resolved;
    }

    private static double validateTimeoutSeconds(double timeoutSeconds) {
        if (!Double.isFinite(timeoutSeconds) || timeoutSeconds <= 0) {
            throw new IllegalArgumentException("Task timeout must be a positive finite number of seconds");
        }
        return timeoutSeconds;
    }

    private static String dedupeKey(String taskType, Map<String, Object> payloadMatch) {
        // 与参考实现一致：sort_keys + 紧凑分隔符的 JSON 指纹
        String serialized = JSON.toJSONString(sortMap(payloadMatch));
        return sha256Hex(taskType + ":" + serialized);
    }

    private static Map<String, Object> sortMap(Map<String, Object> source) {
        Map<String, Object> sorted = new java.util.TreeMap<>(source);
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> nested) {
                Map<String, Object> casted = new LinkedHashMap<>();
                nested.forEach((key, value) -> casted.put(String.valueOf(key), value));
                entry.setValue(sortMap(casted));
            }
        }
        return sorted;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    // ==================== worker 侧 ====================

    private void heartbeatTask(TaskContext context, Future<?> execution) {
        if (execution.isDone()) {
            return;
        }
        boolean renewed;
        boolean cancelRequested;
        try {
            TaskRepository.ControlState state =
                    repo.renewLease(context.taskId, context.workerId, TaskQueueService.TASK_LEASE_SECONDS, null);
            renewed = state.ownsLease();
            cancelRequested = state.cancelRequested();
        } catch (Exception exception) {
            log.error("Task heartbeat failed; cancelling owner: task_id={}", context.taskId, exception);
            context.requestCancel("lease_lost");
            execution.cancel(true);
            return;
        }
        if (!renewed) {
            context.requestCancel("lease_lost");
            execution.cancel(true);
            return;
        }
        if (cancelRequested) {
            context.requestCancel("cancelled");
            execution.cancel(true);
        }
    }

    private static TaskRepository.BeforeTaskChange beforeFinishHook(
            TaskRegistry.TaskFailureHandler failureHandler, String error) {
        return TaskRegistry.asBeforeChange(failureHandler, error);
    }

    private static TaskRepository.BeforeTaskChange beforeFinishHook(
            TaskRegistry.TaskSuccessHandler successHandler, Object result) {
        return TaskRegistry.asBeforeChange(successHandler, result);
    }

    private void finishTaskFailure(
            String taskId,
            String owner,
            String status,
            String message,
            String error,
            TaskRegistry.TaskFailureHandler failureHandler)
            throws Exception {
        repo.finishOwned(taskId, owner, status, message, error, beforeFinishHook(failureHandler, error), null, null);
    }

    private void publishPendingAfterSlotRelease() {
        try {
            taskQueueService.publishPendingTasks(DURABLE_TASK_MAX_RUNNING);
        } catch (Exception exception) {
            log.error("Failed to publish pending tasks after slot release", exception);
        }
    }

    /** 从 PG Task 意图重建并执行一个注册 Handler。 */
    public void processTask(Map<String, Object> ctx, String taskId) throws Exception {
        TaskRecord record = repo.getById(taskId);
        if (record == null || TERMINAL_STATUSES.contains(record.getStatus())) {
            return;
        }

        int handlerVersion = record.getHandlerVersion() == null ? 1 : record.getHandlerVersion();
        TaskRegistry.TaskDefinition definition;
        try {
            definition = TaskRegistry.getTaskDefinition(record.getType(), handlerVersion);
        } catch (IllegalArgumentException exception) {
            log.error("Durable Task has unknown Handler metadata: task_id={}, type={}", taskId, record.getType());
            return;
        }
        if (record.getCancelRequested() != null && record.getCancelRequested() != 0) {
            TaskRegistry.TaskFailureHandler failureHandler = definition.loadFailureHandler();
            repo.requestCancel(taskId, beforeFinishHook(failureHandler, "任务已取消"), null);
            return;
        }

        String processIdentity =
                ctx != null && ctx.get("worker_id") != null
                        ? String.valueOf(ctx.get("worker_id"))
                        : "task-worker";
        String owner = processIdentity + ":" + UUID.randomUUID().toString().replace("-", "");
        TaskRepository.ClaimResult claimResult =
                repo.claim(taskId, owner, TaskQueueService.TASK_LEASE_SECONDS, null, DURABLE_TASK_MAX_RUNNING);
        if (!claimResult.claimed() || claimResult.record() == null) {
            return;
        }
        record = claimResult.record();

        TaskRegistry.TaskFailureHandler failureHandler = null;
        TaskRegistry.TaskSuccessHandler successHandler = null;
        TaskRegistry.TaskHandler handler = null;
        try {
            failureHandler = definition.loadFailureHandler();
            successHandler = definition.loadSuccessHandler();
            handler = definition.loadHandler();
        } catch (Exception exception) {
            finishTaskFailure(taskId, owner, "failed", "任务 Handler 无法加载", String.valueOf(exception.getMessage()), failureHandler);
            publishPendingAfterSlotRelease();
            return;
        }

        TaskContext context = new TaskContext(repo, taskId, owner, RepoValuesJson.toMap(record.getPayload()));
        TaskRegistry.TaskHandler finalHandler = handler;
        Future<Object> execution = handlerExecutor.submit(() -> finalHandler.run(context));
        ScheduledFuture<?> heartbeat =
                heartbeatExecutor.scheduleWithFixedDelay(
                        () -> heartbeatTask(context, execution),
                        (long) TaskQueueService.TASK_HEARTBEAT_SECONDS,
                        (long) TaskQueueService.TASK_HEARTBEAT_SECONDS,
                        TimeUnit.SECONDS);
        try {
            double timeoutSeconds =
                    record.getTimeoutSeconds() == null ? TASKER_DEFAULT_TIMEOUT_SECONDS : record.getTimeoutSeconds();
            Object result;
            try {
                result = execution.get((long) (timeoutSeconds * 1000), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                context.requestCancel("timeout");
                execution.cancel(true);
                finishTaskFailure(
                        taskId,
                        owner,
                        "failed",
                        "任务执行超时",
                        "Task exceeded the " + trimNumber(timeoutSeconds) + "-second execution timeout",
                        failureHandler);
                return;
            } catch (java.util.concurrent.ExecutionException executionException) {
                // 参考实现 await execution 直接重抛 handler 异常，由 except Exception 收敛为 failed
                Throwable cause = executionException.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                throw new IllegalStateException(cause);
            }
            repo.finishOwned(
                    taskId, owner, "success", "任务已完成", result, null,
                    beforeFinishHook(successHandler, result), beforeFinishHook(failureHandler, "任务已取消"), null);
        } catch (TaskContext.Cancelled cancelled) {
            if (!execution.isDone()) {
                execution.cancel(true);
            }
            if ("cancelled".equals(context.cancellationReason)) {
                try {
                    finishTaskFailure(taskId, owner, "cancelled", "任务已取消", "任务已取消", failureHandler);
                } catch (Exception exception) {
                    log.error("Failed to finish cancelled task: task_id={}", taskId, exception);
                }
            } else if (!"lease_lost".equals(context.cancellationReason)) {
                String shutdownError = "worker_shutdown: worker 停止时任务中断";
                TaskRegistry.TaskFailureHandler finalFailureHandler = failureHandler;
                repo.releaseInterruptedOwner(
                        taskId, owner, shutdownError,
                        (changed, error) -> {
                            try {
                                if (finalFailureHandler != null) {
                                    finalFailureHandler.apply(changed, error);
                                }
                            } catch (Exception exception) {
                                throw new IllegalStateException("failure hook 执行失败", exception);
                            }
                        },
                        null);
            }
        } catch (InterruptedException interrupted) {
            // 参考实现 worker 停止时以 CancelledError 中断 wait，走 release_interrupted_owner
            Thread.currentThread().interrupt();
            String shutdownError = "worker_shutdown: worker 停止时任务中断";
            TaskRegistry.TaskFailureHandler finalFailureHandler = failureHandler;
            try {
                repo.releaseInterruptedOwner(
                        taskId, owner, shutdownError,
                        (changed, error) -> {
                            try {
                                if (finalFailureHandler != null) {
                                    finalFailureHandler.apply(changed, error);
                                }
                            } catch (Exception exception) {
                                throw new IllegalStateException("failure hook 执行失败", exception);
                            }
                        },
                        null);
            } catch (Exception exception) {
                log.error("Failed to release interrupted owner: task_id={}", taskId, exception);
            }
        } catch (Exception exception) {
            log.error("Durable task failed: task_id={}, type={}", taskId, record.getType(), exception);
            finishTaskFailure(taskId, owner, "failed", "任务执行失败", String.valueOf(exception.getMessage()), failureHandler);
        } finally {
            heartbeat.cancel(false);
            publishPendingAfterSlotRelease();
        }
    }

    /** 参考实现 f"{x:g}" 的最短数字形式。 */
    private static String trimNumber(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
