package com.wisesoft.wenqu.service;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.wenqu.agents.McpService;
import com.wisesoft.wenqu.agents.SkillService;
import com.wisesoft.wenqu.agents.backends.sandbox.ProvisionerSandboxProvider;
import com.wisesoft.wenqu.common.AuthUtils;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.common.JsonValues;
import com.wisesoft.wenqu.common.ThreadUtils;
import com.wisesoft.wenqu.config.OptionsService;
import com.wisesoft.wenqu.config.RuntimePaths;
import com.wisesoft.wenqu.models.AgentRun;
import com.wisesoft.wenqu.models.AgentRunAttempt;
import com.wisesoft.wenqu.models.Conversation;
import com.wisesoft.wenqu.models.Message;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.repository.port.MessageMapper;
import com.wisesoft.wenqu.repositories.AgentRunRepository;
import com.wisesoft.wenqu.repositories.ConversationRepository;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.service.AgentRunManifestService.PreparedRunExecution;
import com.wisesoft.wenqu.service.WorkdirService.AuthorizedWorkdir;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Agent Run 的 worker 运行时（对应参考实现 {@code services/run_worker.py}）。
 *
 * <p>本类是 Run 队列的「执行侧」全量搬运：把已持久化的 {@code AgentRun} 执行成事件流并收敛终态，
 * 同时承担租约续约、runtime cleanup 栅栏、过期租约收敛、通用 Task 收敛与 worker 健康续租。
 * 参考实现的 ARQ 队列在本工程由「进程内 job 池 + 唤醒端口」承担（见
 * {@link AgentRunService.ArqPort} / {@link TaskQueueService.ArqPort}，两者均由本类实现）。
 *
 * <h3>WorkerSettings 字段映射（参考实现 {@code class WorkerSettings}）</h3>
 * <ul>
 *   <li>{@code functions=[process_agent_run, func(process_task, timeout=…)]} →
 *       {@link #enqueueProcessAgentRun} / {@link #enqueueProcessTask} 两个端口方法。</li>
 *   <li>{@code max_jobs = worker_max_jobs()} → {@link #workerMaxJobs()}（读 {@code ARQ_MAX_JOBS}，
 *       默认 10），即 {@link #jobExecutor} 的并发上限。</li>
 *   <li>{@code poll_delay = 0.05} → 无对位：参考实现由 worker 轮询 Redis 取 job，本工程是
 *       「入队即提交」的推送式池，空闲时零轮询。</li>
 *   <li>{@code max_tries = 2} / {@code retry_jobs = True} → {@link #MAX_TRIES} +
 *       {@link #scheduleRetry}（可重试失败的第二次尝试）。</li>
 *   <li>{@code job_timeout = int(os.getenv("YUXI_JOB_TIMEOUT_SECONDS", "3600"))} →
 *       {@link #JOB_TIMEOUT_SECONDS}（环境变量按本工程既有口径更名为 {@code WENQU_JOB_TIMEOUT_SECONDS}，
 *       与 {@code WENQU_CODE_REVISION} 同例）；ARQ 的 job 中止由 {@link RunContext#start()} 里的
 *       超时看门狗承担（置取消信号 ≡ ARQ abort 抛 CancelledError）。</li>
 *   <li>{@code keep_result = 60} → 无对位：ARQ 的结果保留窗口，本工程的 Run 终态即 PostgreSQL 事实。</li>
 *   <li>{@code health_check_interval} / {@code health_check_key} → {@link #healthThread} 按
 *       {@link RunQueueService#WORKER_HEALTH_INTERVAL_SECONDS} 续租 {@link RunQueueService#WORKER_HEALTH_KEY}。</li>
 *   <li>{@code on_startup} / {@code on_shutdown} → {@link #workerStartup} / {@link #workerShutdown}
 *       （由 worker 入口调用，见 §五 worker_main）。</li>
 *   <li>{@code redis_settings = get_arq_redis_settings()} → 无对位：本工程统一走
 *       {@link StringRedisTemplate}（Run 事件流/取消信号/健康键）。</li>
 * </ul>
 *
 * <h3>平台差异（必要替换）</h3>
 * <ol>
 *   <li><b>ARQ job 队列 → 进程内 job 池</b>：参考实现把 Run 投到 Redis 队列由独立 worker 进程消费；
 *       本工程 {@link #enqueueProcessAgentRun} 按 {@code run:{run_id}} 去重（≡ ARQ
 *       {@code _job_id=f"run:{run_id}"}）后提交 {@link #jobExecutor}。PG 始终是权威事实，
 *       故进程内队列丢失可由 {@code recover_pending_dispatches} 收敛（与参考实现同口径）。</li>
 *   <li><b>{@code asyncio.CancelledError} → {@link RunCancellation}</b>：本工程没有 Java 对位异常，
 *       以该运行时异常承载「执行被中止」的同一控制流（取消分支、ARQ abort、租约丢失三处共用）。</li>
 *   <li><b>{@code ExceptionGroup} → {@link RunExecutionError}</b>：参考实现把「已成组的执行链失败」
 *       单列一支（不可重试）；本工程由 {@link StreamChannel} 把生产者线程的失败包成该类型，保住同一分支。</li>
 *   <li><b>async generator + {@code aclosing} → {@link StreamProducer} + {@link StreamChannel}</b>：
 *       {@code _consume_stream_with_cancel} 的「取消优先于 next()、退出前回收执行任务与生成器」
 *       由「生产线程 + 有界轮询 + 关闭时中断 join」等价实现；关闭期间被重复取消的语义亦保留。</li>
 *   <li><b>{@code async with pg_manager.get_async_session_context()} → {@link TransactionTemplate}</b>：
 *       {@code _release_runtime_if_idle} 的「加栅栏 → 校验 → 释放 → 落库」是同一提交边界，
 *       用 {@link #cleanupTransactionTemplate} 承载；其余位置沿用各仓储自带的独立事务
 *       （参考实现在这些位置同样自管会话）。{@code db.flush()} 无对位（本工程 Mapper 即调用即写）。</li>
 *   <li><b>{@code pg_advisory_xact_lock(hashtext(key))}（PostgreSQL 专用）→ Conversation 行锁</b>：
 *       MySQL 无 advisory lock。清理栅栏的作用是「同一 runtime scope 的清理串行化」；同一 scope 内
 *       所有非 subagent Run 的 {@code conversation_id} 恒为同一 Conversation（scope 即根线程），
 *       故以 {@link ConversationRepository#lockConversationByThreadId} 的 {@code FOR UPDATE} 行锁
 *       充当跨实例的同一栅栏。</li>
 *   <li><b>{@code asyncio.to_thread(get_sandbox_provider().release, …)} → 同步直调</b>：
 *       {@link ProvisionerSandboxProvider#release} 自身即阻塞实现。</li>
 *   <li><b>{@code asyncio.gather} / 后台 Task → 守护线程 + 中断 join</b>：
 *       {@link RunContext} 的三条守护线程（取消信号/持久取消/租约心跳）与收敛循环线程等价，
 *       关闭时逐个 {@code interrupt()} 后 join（≡ {@code gather(return_exceptions=True)}）。</li>
 *   <li><b>{@code model_request_recorder.persist(run_id, worker_id)} 无调用点</b>：
 *       捕获与持久化已合并在 {@code callbacks/ModelRequestTimingMiddleware} 的首次拦截中
 *       （与 {@link ChatService} 同口径，见该批次记录）。</li>
 *   <li><b>{@code OperationalError} → {@link DataAccessResourceFailureException} /
 *       {@link TransientDataAccessException}</b>（Spring 的「连接不可用/瞬时」两类）；
 *       {@code ConnectionError} → {@code java.io.UncheckedIOException}（{@link ChatService} 的
 *       「{@code UncheckedIOException} ≡ ConnectionError」同口径）。{@code TimeoutError} 在 Java
 *       侧是受检异常（{@code java.util.concurrent.TimeoutException}），调用层已将其包成
 *       {@link TransientDataAccessException} 家族，故这里只判后者。</li>
 *   <li><b>{@code pg_manager.initialize()/require_current_schema()/close()} 与
 *       {@code close_queue_clients()} 无对位</b>：连接与 schema 由 Spring/MyBatis 接管。</li>
 *   <li><b>品牌前缀必要替换</b>：{@code "yuxi.agent_state"} → {@link AgentRunService#AGENT_STATE_EVENT_NAME}
 *       （{@code "wenqu.agent_state"}）、{@code f"yuxi.{status}"} → {@code "wenqu." + status}、
 *       {@code "yuxi.warning"} → {@code "wenqu.warning"}。消费方 {@link AgentRunService} 同处后端，
 *       两侧常量已互相指向（前端零引用，与 {@code wenqu.context_compression} 同例）。</li>
 * </ol>
 *
 * <h3>能力差异（显式标注，非遗漏）</h3>
 * <ul>
 *   <li><b>worker 启动入口未接线</b>：{@link #workerStartup} / {@link #workerShutdown} 已就位，
 *       但由 §五 {@code worker_main} 承载调用（参考实现是独立进程入口 {@code worker_main.py}）。
 *       在此期间 Run 可正常执行，只是收敛循环与 worker 健康租约尚未续租
 *       （{@code /api/system/ready} 的 worker 检查因此仍为 error，与「无 worker 启动」一致）。</li>
 *   <li><b>job 超时看门狗自 {@code run_ctx.start()} 起计</b>：参考实现的 ARQ {@code job_timeout}
 *       覆盖整个 job（含前置校验），本工程的前置校验发生在此之前，故超时窗口不含校验阶段。</li>
 *   <li><b>取消瞬间已并发的生产失败</b>：参考实现关闭执行链时以 {@code aclose()} 的异常优先；
 *       本工程按下「主动中断运行中的执行流」判定并抛 {@link RunCancellation}，仅在执行流已先失败时
 *       才抛 {@link RunExecutionError}（同型竞态，落点一致）。</li>
 * </ul>
 */
@Service
public class RunWorker implements AgentRunService.ArqPort, TaskQueueService.ArqPort {

    private static final Logger log = LoggerFactory.getLogger(RunWorker.class);

    // =========================================================================
    // === 常量（参考实现模块级常量，逐字保留） ===
    // =========================================================================

    public static final int LOADING_FLUSH_INTERVAL_MS = 100;
    public static final int LOADING_FLUSH_MAX_CHARS = 512;
    public static final double RUN_CANCEL_POLL_SECONDS = 0.2;
    public static final double RUN_DURABLE_CANCEL_POLL_SECONDS = 1.0;
    public static final int RUN_LEASE_SECONDS = 120;
    public static final int RUN_HEARTBEAT_SECONDS = 30;
    public static final Set<String> SUPPORTED_RUN_TYPES = Set.of("chat", "resume", "subagent");
    public static final String WORKER_ID = "worker-" + UUID.randomUUID().toString().replace("-", "");
    public static final String RECONCILIATION_TASK_KEY = "agent_run_reconciliation_task";
    public static final String TASK_RECONCILIATION_TASK_KEY = "durable_task_reconciliation_task";

    /** WorkerSettings.max_tries。 */
    public static final int MAX_TRIES = 2;

    /** WorkerSettings.job_timeout（环境变量按本工程口径更名，见类注释）。 */
    public static final long JOB_TIMEOUT_SECONDS =
            Long.parseLong(System.getenv().getOrDefault("WENQU_JOB_TIMEOUT_SECONDS", "3600"));

    /** {@code list_pending_runtime_cleanups(limit=100)} 的默认批量。 */
    public static final int PENDING_RUNTIME_CLEANUP_LIMIT = 100;

    /** {@code recover_scheduled_dispatches(limit=100)} 的默认批量。 */
    public static final int SCHEDULED_RECOVERY_LIMIT = 100;

    /** {@code claim_and_dispatch_due_jobs(limit=20)} 的默认批量。 */
    public static final int SCHEDULED_CLAIM_LIMIT = 20;

    /** 取消响应的最长阻塞等待（≡ 消费循环对 cancel 的 200ms 轮询粒度）。 */
    private static final long CANCEL_POLL_MILLIS = (long) (RUN_CANCEL_POLL_SECONDS * 1000);

    private static final ThreadFactory WATCHER_FACTORY = daemonFactory("wenqu-run-watcher");
    private static final ThreadFactory RECONCILE_FACTORY = daemonFactory("wenqu-run-reconcile");

    // =========================================================================
    // === 异常与值对象 ===
    // =========================================================================

    /** 应触发重试的失败（对应参考实现从 {@code RetryJob} 派生的 {@code RetryableRunError}）。 */
    public static class RetryableRunError extends RuntimeException {
        public RetryableRunError(String message) {
            super(message);
        }

        public RetryableRunError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 根 Run 已终态、但 execution runtime 的持久清理尚未完成（对应 {@code RuntimeCleanupPendingError}）。 */
    public static class RuntimeCleanupPendingError extends RuntimeException {
        public RuntimeCleanupPendingError(String message) {
            super(message);
        }

        public RuntimeCleanupPendingError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 不应触发重试的失败（对应 {@code NonRetryableRunError}）。 */
    public static class NonRetryableRunError extends RuntimeException {
        public NonRetryableRunError(String message) {
            super(message);
        }
    }

    /** 执行被中止（对应 {@code asyncio.CancelledError}；本工程无对位异常，见类注释）。 */
    public static class RunCancellation extends RuntimeException {
        public RunCancellation(String message) {
            super(message);
        }

        public RunCancellation(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 已成组的执行链失败（对应 {@code ExceptionGroup}；一律按不可重试处理）。 */
    public static class RunExecutionError extends RuntimeException {
        public RunExecutionError(Throwable cause) {
            super(String.valueOf(cause), cause);
        }
    }

    /** 一次 Run 的终态转换结果。 */
    public record TerminalTransition(String status, boolean changed) {}

    /** 已映射的 Run 事件（对应 {@code _map_chunk_to_run_event} 的 {@code (event_type, payload)}）。 */
    public record RunEvent(String type, Map<String, Object> payload) {}

    /** 线程内缓冲分组（对应 {@code _ThreadBuffer}）。 */
    private static final class ThreadBuffer {
        List<Map<String, Object>> items = new ArrayList<>();
        int chars = 0;
        double lastFlush = monotonic();
    }

    /** 待发布的中断（对应 {@code pending_interrupt} 二元组）。 */
    private record PendingInterrupt(Map<String, Object> chunk, String threadId) {}

    /** 执行流的生产者：把 {@link ChatService} 的回调 sink 桥接为可取消消费的产出源（≡ async generator）。 */
    @FunctionalInterface
    public interface StreamProducer {
        void produce(Consumer<String> sink);
    }

    private static ThreadFactory daemonFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /** {@code time.monotonic()}：仅用于比较间隔，故取纳秒时钟的秒值。 */
    private static double monotonic() {
        return System.nanoTime() / 1_000_000_000.0;
    }

    // =========================================================================
    // === 依赖 ===
    // =========================================================================

    private final AgentRunRepository agentRunRepository;
    private final ConversationRepository conversationRepository;
    private final UserRepository userRepository;
    private final MessageMapper messageMapper;
    private final WorkdirService workdirService;
    private final ChatService chatService;
    private final AgentRunManifestService agentRunManifestService;
    private final AgentRequestQueueService agentRequestQueueService;
    private final ScheduledAgentService scheduledAgentService;
    private final TaskQueueService taskQueueService;
    private final TaskService taskService;
    private final RunQueueService runQueueService;
    private final OptionsService optionsService;
    private final McpService mcpService;
    private final SkillService skillService;
    private final StringRedisTemplate redis;
    private final TransactionTemplate cleanupTransactionTemplate;

    /** ARQ {@code max_jobs} 对应的 job 并发池。 */
    private final ExecutorService jobExecutor;

    /** job 超时看门狗。 */
    private final ScheduledExecutorService timeoutScheduler =
            Executors.newSingleThreadScheduledExecutor(daemonFactory("wenqu-run-timeout"));

    /** 收敛循环的宿主。 */
    private final ExecutorService reconcilePool = Executors.newCachedThreadPool(RECONCILE_FACTORY);

    /** worker 健康租约线程（WorkerSettings.health_check_* 的对位实现）。 */
    private volatile Thread healthThread;

    /** 已在队列或执行中的 Run（≡ ARQ {@code _job_id=f"run:{run_id}"} 的去重集）。 */
    private final Set<String> inflightRuns = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public RunWorker(
            AgentRunRepository agentRunRepository,
            ConversationRepository conversationRepository,
            UserRepository userRepository,
            MessageMapper messageMapper,
            WorkdirService workdirService,
            ChatService chatService,
            AgentRunManifestService agentRunManifestService,
            AgentRequestQueueService agentRequestQueueService,
            ScheduledAgentService scheduledAgentService,
            TaskQueueService taskQueueService,
            TaskService taskService,
            RunQueueService runQueueService,
            OptionsService optionsService,
            McpService mcpService,
            SkillService skillService,
            StringRedisTemplate redis,
            PlatformTransactionManager transactionManager) {
        this.agentRunRepository = agentRunRepository;
        this.conversationRepository = conversationRepository;
        this.userRepository = userRepository;
        this.messageMapper = messageMapper;
        this.workdirService = workdirService;
        this.chatService = chatService;
        this.agentRunManifestService = agentRunManifestService;
        this.agentRequestQueueService = agentRequestQueueService;
        this.scheduledAgentService = scheduledAgentService;
        this.taskQueueService = taskQueueService;
        this.taskService = taskService;
        this.runQueueService = runQueueService;
        this.optionsService = optionsService;
        this.mcpService = mcpService;
        this.skillService = skillService;
        this.redis = redis;
        this.cleanupTransactionTemplate = new TransactionTemplate(transactionManager);
        this.cleanupTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.jobExecutor = Executors.newFixedThreadPool(workerMaxJobs(), daemonFactory("wenqu-run-job"));
    }

    /** 读取单个 worker 的并发任务上限（对应 {@code worker_max_jobs}）。 */
    public static int workerMaxJobs() {
        return RuntimePaths.getIntEnv("ARQ_MAX_JOBS", 10, 1);
    }

    // =========================================================================
    // === 唤醒端口（WorkerSettings.functions 的对位） ===
    // =========================================================================

    /** 把已持久化的 run 投递到 job 池（对应 ARQ {@code enqueue_job("process_agent_run", run_id, _job_id=…)}）。 */
    @Override
    public void enqueueProcessAgentRun(String runId) {
        if (!inflightRuns.add(runId)) {
            log.debug("Run already queued or executing, skip enqueue: {}", runId);
            return;
        }
        jobExecutor.execute(() -> executeJob(runId, 1));
    }

    /** 把已持久化的通度 Task 投递到 job 池（对应 ARQ {@code enqueue_job("process_task", task_id)}，无 job_id）。 */
    @Override
    public void enqueueProcessTask(String taskId) {
        jobExecutor.execute(() -> {
            try {
                taskService.processTask(newWorkerContext(1), taskId);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                log.info("Task job aborted: {}", taskId);
            } catch (Exception error) {
                log.error("Task job failed: " + taskId, error);
            }
        });
    }

    private static Map<String, Object> newWorkerContext(int jobTry) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("worker_id", WORKER_ID);
        ctx.put("job_try", jobTry);
        return ctx;
    }

    /** 执行一次 Run job，并按参考实现的 ARQ 语义决定重试（{@code retry_jobs} + {@code max_tries}）。 */
    private void executeJob(String runId, int jobTry) {
        boolean retryScheduled = false;
        try {
            processAgentRun(newWorkerContext(jobTry), runId);
        } catch (RunCancellation cancellation) {
            // ≡ ARQ 中止该 job：不算失败，也不写终态（终态由 process_agent_run 内的取消分支负责）。
            log.info("Run job aborted: {}", runId);
        } catch (RuntimeCleanupPendingError pending) {
            retryScheduled = scheduleRetry(runId, jobTry, pending);
        } catch (RetryableRunError retryable) {
            retryScheduled = scheduleRetry(runId, jobTry, retryable);
        } catch (RuntimeException error) {
            log.error("Run job failed unexpectedly: " + runId, error);
        } finally {
            if (!retryScheduled) {
                inflightRuns.remove(runId);
            }
        }
    }

    /** 复用 ARQ 的重试语义：次数未耗尽则再跑一次 attempt，否则交由收敛循环兜底。 */
    private boolean scheduleRetry(String runId, int jobTry, RuntimeException error) {
        if (jobTry >= Math.max(1, MAX_TRIES)) {
            log.warn("Run retry exhausted: run={}, reason={}", runId, error.getMessage());
            return false;
        }
        log.warn("Retry Run job: run={}, next_try={}, reason={}", runId, jobTry + 1, error.getMessage());
        jobExecutor.execute(() -> executeJob(runId, jobTry + 1));
        return true;
    }

    @PreDestroy
    void shutdownPools() {
        // 平台差异：JVM 进程退出时回收线程池（参考实现是独立 worker 进程，进程退出即回收）。
        stopHealthLoop();
        jobExecutor.shutdownNow();
        timeoutScheduler.shutdownNow();
        reconcilePool.shutdownNow();
    }

    // =========================================================================
    // === 执行器边界的归属校验 ===
    // =========================================================================

    /** 在执行器边界验证持久 Run 的 Conversation、执行树与 Workdir 归属（对应 {@code _validate_run_workdir_binding}）。 */
    private AuthorizedWorkdir validateRunWorkdirBinding(AgentRun run) {
        AuthorizedWorkdir binding =
                workdirService.resolveAuthorizedWorkdir(String.valueOf(run.getConversationThreadId()), run.getUid());
        if (binding.conversationId() != (run.getConversationId() == null ? 0 : run.getConversationId())) {
            throw new NonRetryableRunError("AgentRun 的 Conversation 身份不一致");
        }

        String persistedScope =
                run.getRuntimeScopeId() == null ? "" : String.valueOf(run.getRuntimeScopeId()).strip();
        if (persistedScope.isEmpty()) {
            throw new NonRetryableRunError("AgentRun 缺少 runtime scope");
        }
        String runType = run.getRunType();
        if (("chat".equals(runType) || "resume".equals(runType))
                && !persistedScope.equals(String.valueOf(run.getConversationThreadId()))) {
            throw new NonRetryableRunError(
                    capitalize(String.valueOf(run.getRunType())) + " AgentRun 的 runtime scope 非法");
        }

        if ("subagent".equals(runType)) {
            String creatorId = run.getCreatedByRunId() == null ? "" : run.getCreatedByRunId().strip();
            if (creatorId.isEmpty()) {
                throw new NonRetryableRunError("SubAgent Run 缺少创建者");
            }
            AgentRunRepository.SubagentRunPair executionPair = agentRunRepository.getSubagentRunWithCreator(
                    run.getUid(), creatorId, run.getId());
            if (executionPair == null) {
                throw new NonRetryableRunError("SubAgent Run 的线程关系非法");
            }
            AgentRun creatorRun = executionPair.creatorRun();
            if (!"chat".equals(creatorRun.getRunType()) && !"resume".equals(creatorRun.getRunType())) {
                throw new NonRetryableRunError("SubAgent Run 的创建者非法");
            }
            AuthorizedWorkdir creatorBinding = workdirService.resolveAuthorizedWorkdir(
                    String.valueOf(creatorRun.getConversationThreadId()), run.getUid());
            if (!persistedScope.equals(String.valueOf(creatorRun.getRuntimeScopeId()))
                    || creatorBinding.conversationId()
                            != (creatorRun.getConversationId() == null ? 0 : creatorRun.getConversationId())
                    || !Objects.equals(creatorBinding.projectId(), binding.projectId())) {
                throw new NonRetryableRunError("SubAgent Run 的 runtime scope 不属于创建者执行树");
            }
        }
        return binding;
    }

    /** Python {@code str.capitalize()}（首字符大写、其余小写）。 */
    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1).toLowerCase();
    }

    // =========================================================================
    // === 单 Run 执行上下文与租约守护 ===
    // =========================================================================

    /** 一次 Run 的取消/租约守护（对应 {@code RunContext}）。 */
    private final class RunContext {
        private final String runId;
        private final String workerId;
        private final CountDownLatch cancelEvent = new CountDownLatch(1);
        private Thread watchThread;
        private Thread durableCancelThread;
        private Thread heartbeatThread;
        private ScheduledFuture<?> timeoutFuture;
        private volatile boolean leaseLost;

        RunContext(String runId, String workerId) {
            this.runId = runId;
            this.workerId = workerId;
        }

        void start() {
            if (watchThread == null) {
                watchThread = startWatcher("watch-cancel-signal", this::watchCancelSignal);
            }
            if (durableCancelThread == null) {
                durableCancelThread = startWatcher("watch-durable-cancel", this::watchDurableCancel);
            }
            if (heartbeatThread == null) {
                heartbeatThread = startWatcher("heartbeat-lease", this::heartbeatLease);
            }
            if (timeoutFuture == null) {
                timeoutFuture = timeoutScheduler.schedule(this::abortOnJobTimeout, JOB_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        }

        private Thread startWatcher(String name, Runnable body) {
            Thread thread = WATCHER_FACTORY.newThread(body);
            thread.setName("wenqu-run-" + name);
            thread.start();
            return thread;
        }

        void close() {
            List<Thread> threads = new ArrayList<>();
            for (Thread thread : List.of(watchThread, durableCancelThread, heartbeatThread)) {
                if (thread != null) {
                    threads.add(thread);
                }
            }
            for (Thread thread : threads) {
                thread.interrupt();
            }
            joinQuietly(threads);
            watchThread = null;
            durableCancelThread = null;
            heartbeatThread = null;
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
                timeoutFuture = null;
            }
        }

        void cancel() {
            cancelEvent.countDown();
        }

        boolean isCancelled() {
            return cancelEvent.getCount() == 0;
        }

        /** ≡ asyncio.CancelledError 的控制流载体：等待被取消。 */
        void waitCancelled() throws InterruptedException {
            cancelEvent.await();
        }

        /** 共享同一 {@code cancel_event} 的区块（≡ {@code await run_ctx.is_cancelled()}）。 */
        boolean awaitCancelOrTimeout() throws InterruptedException {
            return cancelEvent.await(
                    (long) (RUN_DURABLE_CANCEL_POLL_SECONDS * 1000), TimeUnit.MILLISECONDS);
        }

        /** ARQ job_timeout 中止：置取消信号，让执行链按取消分支收尾（见类注释能力差异 2）。 */
        private void abortOnJobTimeout() {
            if (cancelEvent.getCount() == 0) {
                return;
            }
            log.warn("Run job exceeded {}s, aborting: run={}", JOB_TIMEOUT_SECONDS, runId);
            cancel();
        }

        /** 监听 Redis 取消信号。 */
        private void watchCancelSignal() {
            try {
                runQueueService.waitForCancelSignal(runId, RUN_CANCEL_POLL_SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            cancel();
        }

        /** 低频轮询 PostgreSQL，确保 Redis 丢信号时取消仍然 fail-closed。 */
        private void watchDurableCancel() {
            while (!isCancelled()) {
                boolean cancelled;
                try {
                    cancelled = isCancelRequested(runId);
                } catch (RuntimeException error) {
                    log.error("Failed to read durable AgentRun cancellation: run=" + runId, error);
                    cancel();
                    return;
                }
                if (cancelled) {
                    cancel();
                    return;
                }
                try {
                    if (awaitCancelOrTimeout()) {
                        return;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        /** 周期性续租；owner 或 lease 失效即停止执行。 */
        private void heartbeatLease() {
            while (!isCancelled()) {
                try {
                    Thread.sleep(RUN_HEARTBEAT_SECONDS * 1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (isCancelled()) {
                    return;
                }
                boolean renewed;
                try {
                    renewed = renewRunLease(runId, workerId);
                    if (!renewed && runAttemptFinished(runId, workerId)) {
                        // 终态事务已清除 lease；本 attempt 仍需完成流收尾、清理和事件发布。
                        return;
                    }
                } catch (RuntimeException error) {
                    log.error("Failed to renew AgentRun lease: run=" + runId, error);
                    renewed = false;
                }
                if (!renewed) {
                    leaseLost = true;
                    cancel();
                    return;
                }
            }
        }
    }

    private static void joinQuietly(List<Thread> threads) {
        for (Thread thread : threads) {
            boolean interrupted = false;
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException interruptedException) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // =========================================================================
    // === 分片事件写入器 ===
    // =========================================================================

    /** 按线程分组、按间隔/字符数分片发布 loading 事件（对应 {@code ChunkedEventWriter}）。 */
    private final class ChunkedEventWriter {
        private final String runId;
        private final String defaultThreadId;
        private final double intervalSeconds;
        private final int maxChars;
        private final Map<String, ThreadBuffer> threadBuffers = new LinkedHashMap<>();

        ChunkedEventWriter(String runId, String threadId, int intervalMs, int maxChars) {
            this.runId = runId;
            this.defaultThreadId = threadId;
            this.intervalSeconds = intervalMs / 1000.0;
            this.maxChars = maxChars;
        }

        private String targetThreadId(String threadId) {
            return JsonValues.truthy(threadId) ? threadId : defaultThreadId;
        }

        void append(Map<String, Object> chunk, String threadId) {
            String explicit = JsonValues.truthy(threadId) ? threadId : ThreadUtils.extractThreadId(chunk, null);
            String target = targetThreadId(explicit);
            ThreadBuffer buffer = threadBuffers.computeIfAbsent(target, key -> new ThreadBuffer());
            buffer.items.add(chunk);
            buffer.chars += loadingChunkSize(chunk);

            if (flushLoadingChunkImmediately(chunk)) {
                flush(target);
                return;
            }
            if ((monotonic() - buffer.lastFlush) >= intervalSeconds || buffer.chars >= maxChars) {
                flush(target);
            }
        }

        void flush(String threadId) {
            ThreadBuffer buffer = threadBuffers.get(threadId);
            if (buffer == null || buffer.items.isEmpty()) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("items", buffer.items);
            appendRunEventBestEffort(runId, "messages", payload, threadId);
            buffer.items = new ArrayList<>();
            buffer.chars = 0;
            buffer.lastFlush = monotonic();
        }

        /** ≡ {@code writer.flush()}（无参时刷全部线程）。 */
        void flushAll() {
            for (String target : new ArrayList<>(threadBuffers.keySet())) {
                flush(target);
            }
        }
    }

    // =========================================================================
    // === runtime 生命周期收敛 ===
    // =========================================================================

    /** 在 cleanup fence 内串行销毁根 execution runtime（对应 {@code _release_runtime_if_idle}）。 */
    private boolean releaseRuntimeIfIdle(AgentRun run) {
        if ("subagent".equals(run.getRunType())) {
            return false;
        }
        String runtimeScopeId = JsonValues.truthy(run.getRuntimeScopeId())
                ? String.valueOf(run.getRuntimeScopeId())
                : String.valueOf(run.getConversationThreadId());
        Boolean cleaned = cleanupTransactionTemplate.execute(status -> {
            // PostgreSQL pg_advisory_xact_lock 无 MySQL 对位 → 以 runtime scope 所属 Conversation 行锁
            // 充当「同一 runtime scope 清理串行化」的跨实例栅栏（见类注释平台差异 6）。
            conversationRepository.lockConversationByThreadId(runtimeScopeId);

            AgentRun current = agentRunRepository.lockRunForRuntimeCleanup(run.getId());
            if (current == null) {
                throw new IllegalStateException("Run " + run.getId() + " 不存在，不能确认 runtime cleanup Owner");
            }
            if (!Boolean.TRUE.equals(current.getRuntimeCleanupPending())) {
                return true;
            }
            if (agentRunRepository.hasNonTerminalRunInRuntimeScope(runtimeScopeId, current.getId())) {
                return false;
            }
            Conversation conversation = conversationRepository.getConversationById(current.getConversationId());
            if (conversation == null || !Objects.equals(conversation.getUid(), String.valueOf(current.getUid()))) {
                throw new IllegalStateException("Run " + run.getId() + " 的 Conversation 身份不一致");
            }
            String workdirPath =
                    workdirService.resolveConversationWorkdirPath(conversation, String.valueOf(current.getUid()));
            ProvisionerSandboxProvider.getSandboxProvider()
                    .release(runtimeScopeId, String.valueOf(current.getUid()), true, workdirPath);
            agentRunRepository.clearRuntimeCleanupPending(current.getId(), null);
            return true;
        });
        return Boolean.TRUE.equals(cleaned);
    }

    /** 在终态事件可见前收敛 runtime，避免客户端撞上随后发生的删除（对应 {@code _release_runtime_before_terminal_event}）。 */
    private void releaseRuntimeBeforeTerminalEvent(AgentRun run) {
        if (run == null || "subagent".equals(run.getRunType())) {
            return;
        }
        requireRuntimeCleanup(run, "Run " + run.getId() + " 的 execution tree 尚未完成 runtime cleanup");
    }

    /** 把 provisioner/并发清理失败统一转成可重试的 durable cleanup（对应 {@code _require_runtime_cleanup}）。 */
    private void requireRuntimeCleanup(AgentRun run, String message) {
        boolean cleaned;
        try {
            cleaned = releaseRuntimeIfIdle(run);
        } catch (RuntimeException error) {
            throw new RuntimeCleanupPendingError(message, error);
        }
        if (!cleaned) {
            throw new RuntimeCleanupPendingError(message);
        }
    }

    /** 收敛 execution tree 后代的数据库终态并通知其停止执行（对应 {@code _finish_execution_tree_children}）。 */
    private void finishExecutionTreeChildren(AgentRun run) {
        List<String[]> descendants = agentRunRepository.cancelActiveExecutionTreeDescendants(run);
        runQueueService.publishCancelSignals(descendantIds(descendants));
    }

    private static List<String> descendantIds(List<String[]> descendants) {
        List<String> ids = new ArrayList<>();
        for (String[] descendant : descendants == null ? List.<String[]>of() : descendants) {
            ids.add(descendant[0]);
        }
        return ids;
    }

    private AgentRun getRun(String runId) {
        return agentRunRepository.getRun(runId);
    }

    private void appendRunEvent(String runId, String eventType, Map<String, Object> payload, String threadId) {
        runQueueService.appendRunStreamEvent(runId, eventType, payload, threadId);
    }

    /** 发布短期事件；失败只记录，不能阻断持久状态收敛（对应 {@code _append_run_event_best_effort}）。 */
    private boolean appendRunEventBestEffort(
            String runId, String eventType, Map<String, Object> payload, String threadId) {
        try {
            appendRunEvent(runId, eventType, payload, threadId);
        } catch (RuntimeException error) {
            log.warn(
                    "Failed to publish non-authoritative AgentRun event: run={}, event={}",
                    runId,
                    eventType,
                    error);
            return false;
        }
        return true;
    }

    /** 尽力发布缓冲事件，不让 Redis 可用性决定 durable transition（对应 {@code _flush_writer_best_effort}）。 */
    private void flushWriterBestEffort(ChunkedEventWriter writer) {
        try {
            writer.flushAll();
        } catch (RuntimeException error) {
            log.warn("Failed to flush non-authoritative AgentRun events: run={}", writer.runId, error);
        }
    }

    private boolean markRunRunning(String runId, String workerId) {
        AgentRunRepository.RunResult result =
                agentRunRepository.markRunning(runId, workerId, RUN_LEASE_SECONDS, null);
        return result.changed();
    }

    /** 在独立事务中续租；owner 或 lease 已失效时返回 {@code false}。 */
    private boolean renewRunLease(String runId, String workerId) {
        return agentRunRepository.renewLease(runId, workerId, RUN_LEASE_SECONDS, null);
    }

    /** 确认终态由当前最后一次 attempt 提交（对应 {@code _run_attempt_finished}）。 */
    private boolean runAttemptFinished(String runId, String workerId) {
        AgentRun run = agentRunRepository.getRun(runId);
        if (run == null || !AgentRunRepository.TERMINAL_RUN_STATUSES.contains(run.getStatus())) {
            return false;
        }
        List<AgentRunAttempt> attempts = agentRunRepository.listRunAttempts(runId);
        if (attempts.isEmpty()) {
            return false;
        }
        AgentRunAttempt attempt = attempts.get(attempts.size() - 1);
        return workerId.equals(attempt.getWorkerId())
                && Objects.equals(attempt.getOutcome(), run.getStatus())
                && attempt.getFinishedAt() != null
                && attempt.getFinishedAt().equals(run.getFinishedAt());
    }

    /** 释放当前 attempt 的 lease，允许下一次 attempt 使用新 token（对应 {@code release_run_lease_for_retry}）。 */
    private boolean releaseRunLeaseForRetry(String runId, String workerId) {
        List<String> cancelledDescendantIds = new ArrayList<>();
        boolean released = agentRunRepository.releaseLeaseForRetry(runId, workerId, null);
        if (released) {
            AgentRun run = agentRunRepository.getRun(runId);
            if (run != null) {
                cancelledDescendantIds = descendantIds(agentRunRepository.cancelActiveExecutionTreeDescendants(run));
            }
        }
        runQueueService.publishCancelSignals(cancelledDescendantIds);
        return released;
    }

    private TerminalTransition markRunTerminal(
            String runId,
            String status,
            String errorType,
            String errorMessage,
            Map<String, Object> tokenUsage,
            String workerId) {
        List<String> cancelledDescendantIds = new ArrayList<>();
        String persistedStatus;
        boolean changed;
        AgentRunRepository.RunResult result =
                agentRunRepository.setTerminalStatus(runId, status, errorType, errorMessage, tokenUsage, workerId, null);
        changed = result.changed();
        if (changed && result.run() != null) {
            cancelledDescendantIds = descendantIds(agentRunRepository.cancelActiveExecutionTreeDescendants(result.run()));
        }
        persistedStatus = result.run() == null ? null : result.run().getStatus();
        runQueueService.publishCancelSignals(cancelledDescendantIds);
        return new TerminalTransition(persistedStatus, changed);
    }

    /** 收敛过期 Run ownership；重复或并发执行只返回本次实际转换的 Run（对应 {@code reconcile_expired_run_leases}）。 */
    public List<String> reconcileExpiredRunLeases(java.time.LocalDateTime now) {
        AgentRunRepository.ReconcileResult result = agentRunRepository.reconcileExpiredLeases(now);
        runQueueService.publishCancelSignals(descendantIds(result.cancelledDescendants()));
        reconcilePendingRuntimeCleanups();
        List<String> ids = new ArrayList<>();
        for (AgentRun run : result.runs() == null ? List.<AgentRun>of() : result.runs()) {
            ids.add(run.getId());
        }
        return ids;
    }

    /** 重试持久拥有的 runtime cleanup，并在成功后发布终态（对应 {@code reconcile_pending_runtime_cleanups}）。 */
    public List<String> reconcilePendingRuntimeCleanups() {
        List<AgentRun> pendingRuns = agentRunRepository.listPendingRuntimeCleanups(PENDING_RUNTIME_CLEANUP_LIMIT);
        List<String> cleaned = new ArrayList<>();
        for (AgentRun run : pendingRuns == null ? List.<AgentRun>of() : pendingRuns) {
            try {
                if (!releaseRuntimeIfIdle(run)) {
                    continue;
                }
            } catch (RuntimeException error) {
                log.error("Failed to reconcile execution-tree runtime cleanup: run={}", run.getId(), error);
                continue;
            }
            if (AgentRunRepository.TERMINAL_RUN_STATUSES.contains(run.getStatus())) {
                appendEndEvent(run.getId(), run.getStatus(), run.getConversationThreadId(), null);
            }
            if ("pending".equals(run.getStatus()) || "completed".equals(run.getStatus())) {
                agentRequestQueueService.dispatchNextRequest(
                        run.getUid(), run.getAgentSlug(), run.getConversationThreadId());
            }
            cleaned.add(run.getId());
        }
        return cleaned;
    }

    /** 重试只能复用与 write-once manifest 完全一致的运行资产（对应 {@code _require_persisted_manifest_match}）。 */
    private void requirePersistedManifestMatch(AgentRun persistedRun, boolean recorded, String fingerprint) {
        if (recorded) {
            return;
        }
        if (persistedRun == null || !Objects.equals(persistedRun.getManifestFingerprint(), fingerprint)) {
            throw new IllegalStateException("运行资产已在重试前变化，与已固化 manifest 不一致");
        }
    }

    /** 在构图执行前固化运行清单与指纹（对应 {@code prepare_and_record_run_execution}）。 */
    private PreparedRunExecution prepareAndRecordRunExecution(
            AgentRun run, User user, String workerId, AuthorizedWorkdir workdirBinding) {
        PreparedRunExecution result =
                agentRunManifestService.prepareRunExecution(run, user, workdirBinding, workerId);
        String fingerprint = AgentRunManifestService.computeManifestFingerprint(result.manifest());
        AgentRunRepository.RunResult recorded =
                agentRunRepository.recordRunManifest(run.getId(), result.manifest(), fingerprint, workerId, null);
        requirePersistedManifestMatch(recorded.run(), recorded.changed(), fingerprint);
        return result;
    }

    /** 记录单次 Run 阶段时间；观测失败不覆盖业务执行结果（对应 {@code _record_run_timing_best_effort}）。 */
    private void recordRunTimingBestEffort(
            String runId, String workerId, String phase, java.time.LocalDateTime observedAt) {
        try {
            if ("prepared".equals(phase)) {
                agentRunRepository.recordPrepared(runId, workerId, observedAt, null);
            } else if ("first_output".equals(phase)) {
                agentRunRepository.recordFirstOutput(runId, workerId, observedAt, null);
            } else {
                throw new IllegalArgumentException("不支持的 AgentRun timing phase: " + phase);
            }
        } catch (RuntimeException error) {
            log.warn("Failed to persist AgentRun timing: run={}, phase={}", runId, phase, error);
        }
    }

    private User loadUser(String uid) {
        return userRepository.getActiveByUid(uid);
    }

    private boolean isCancelRequested(String runId) {
        AgentRun run = getRun(runId);
        return run != null && "cancel_requested".equals(run.getStatus());
    }

    /** 只把持久层的 cancel_requested 视为用户取消事实（对应 {@code _confirmed_user_cancel}）。 */
    private boolean confirmedUserCancel(String runId) {
        try {
            return isCancelRequested(runId);
        } catch (RuntimeException error) {
            log.error("Failed to confirm durable AgentRun cancellation: run=" + runId, error);
            return false;
        }
    }

    /** 从当前线程 state 读取属于指定 Run 的用量快照（对应 {@code _read_run_token_usage_from_state}）。 */
    private Map<String, Object> readRunTokenUsageFromState(String runId, String threadId, User currentUser) {
        Map<String, Object> view;
        try {
            view = chatService.getAgentStateView(threadId, currentUser, false, false);
        } catch (RuntimeException error) {
            log.warn("Failed to read token usage from state for run " + runId, error);
            return null;
        }
        Object agentState = view == null ? null : view.get("agent_state");
        Object tokenUsage = agentState instanceof Map<?, ?> map ? map.get("token_usage") : null;
        if (!(tokenUsage instanceof Map<?, ?> usageMap) || !Objects.equals(usageMap.get("current_run_id"), runId)) {
            return null;
        }
        Object runUsage = usageMap.get("run");
        return runUsage instanceof Map<?, ?> runMap ? JsonValues.asMap(runMap) : null;
    }

    // =========================================================================
    // === job context 与事件翻译 ===
    // =========================================================================

    private static int jobTry(Map<String, Object> ctx) {
        if (ctx == null) {
            return 1;
        }
        try {
            Object value = ctx.get("job_try");
            return value == null ? 1 : Integer.parseInt(String.valueOf(value).strip());
        } catch (RuntimeException error) {
            return 1;
        }
    }

    private static boolean isLastTry(Map<String, Object> ctx) {
        return jobTry(ctx) >= Math.max(1, MAX_TRIES);
    }

    private static boolean isRetryableException(RuntimeException error) {
        if (error instanceof NonRetryableRunError) {
            return false;
        }
        return error instanceof RetryableRunError
                || error instanceof DataAccessResourceFailureException
                || error instanceof TransientDataAccessException
                || error instanceof java.io.UncheckedIOException;
    }

    /** 返回当前 worker 进程在所有 job 中复用的 identity（对应 {@code _worker_identity}）。 */
    private static String workerIdentity(Map<String, Object> ctx) {
        if (ctx != null) {
            Object value = ctx.get("worker_id");
            if (value instanceof String text && !text.isEmpty()) {
                return text;
            }
        }
        return WORKER_ID;
    }

    /** 为一次 job attempt 生成带稳定 worker identity 的唯一 owner token（对应 {@code _run_owner_token}）。 */
    private static String runOwnerToken(Map<String, Object> ctx) {
        return workerIdentity(ctx) + ":" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 拆分执行流分片为事件列表（对应 {@code _iter_json_chunks}）。 */
    private static List<Map<String, Object>> iterJsonChunks(String chunkText) {
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (String raw : chunkText.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            try {
                Object parsed = JSON.parse(line);
                if (parsed instanceof Map<?, ?> map) {
                    chunks.add(JsonValues.asMap(map));
                }
            } catch (RuntimeException error) {
                log.warn("Failed to parse run stream chunk: {}", line.substring(0, Math.min(200, line.length())));
            }
        }
        return chunks;
    }

    /** loading 分片的载荷体量估算（对应 {@code _loading_chunk_size}）。 */
    private static int loadingChunkSize(Map<String, Object> chunk) {
        Object response = chunk.get("response");
        int total = response instanceof String text ? text.length() : 0;
        if (!(chunk.get("stream_event") instanceof Map<?, ?> streamEvent)) {
            return total;
        }
        for (String key : List.of("content", "reasoning_content", "additional_reasoning_content", "args_delta")) {
            Object value = streamEvent.get(key);
            if (value instanceof String text) {
                total += text.length();
            }
        }
        return total;
    }

    /** 识别非空模型文本、推理文本或工具调用数据（对应 {@code _contains_model_output}）。 */
    private static boolean containsModelOutput(Map<String, Object> chunk) {
        if (!(chunk.get("stream_event") instanceof Map<?, ?> streamEvent)) {
            return false;
        }
        Object eventType = streamEvent.get("type");
        if ("message_delta".equals(eventType)) {
            for (String key : List.of("content", "reasoning_content", "additional_reasoning_content")) {
                Object value = streamEvent.get(key);
                if (value instanceof String text && !text.isEmpty()) {
                    return true;
                }
            }
            return false;
        }
        if ("tool_call".equals(eventType) || "tool_call_delta".equals(eventType)) {
            for (String key : List.of("name", "args", "args_delta")) {
                Object value = streamEvent.get(key);
                if (value != null && !"".equals(value) && !(value instanceof Map<?, ?> map && map.isEmpty())) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private static boolean flushLoadingChunkImmediately(Map<String, Object> chunk) {
        return chunk.get("stream_event") instanceof Map<?, ?> streamEvent
                && "tool_call".equals(streamEvent.get("type"));
    }

    private static String chunkThreadId(Map<String, Object> chunk, String fallback) {
        return ThreadUtils.extractThreadId(chunk, fallback);
    }

    /** {@code chunk.get("status") or "event"}。 */
    private static String chunkStatus(Map<String, Object> chunk) {
        Object status = chunk.get("status");
        return JsonValues.truthy(status) ? String.valueOf(status) : "event";
    }

    /** 把执行流分片映射为 Run 事件（对应 {@code _map_chunk_to_run_event}；品牌前缀见类注释必要替换）。 */
    private static RunEvent mapChunkToRunEvent(Map<String, Object> chunk) {
        String status = chunkStatus(chunk);
        Map<String, Object> payload = new LinkedHashMap<>();
        switch (status) {
            case "loading":
                payload.put("chunk", chunk);
                return new RunEvent("messages", payload);
            case "agent_state":
                payload.put("name", AgentRunService.AGENT_STATE_EVENT_NAME);
                payload.put("chunk", chunk);
                payload.put("agent_state", JsonValues.asMap(chunk.get("agent_state")));
                return new RunEvent("custom", payload);
            case "ask_user_question_required":
            case "human_approval_required":
            case "interrupted":
                payload.put("reason", "human_approval_required".equals(status) ? "human_approval" : status);
                payload.put("chunk", chunk);
                return new RunEvent("interrupt", payload);
            case "warning":
                payload.put("name", "wenqu.warning");
                payload.put("chunk", chunk);
                return new RunEvent("custom", payload);
            case "error":
                payload.put("chunk", chunk);
                payload.put("retryable", Boolean.TRUE.equals(chunk.get("retryable")));
                return new RunEvent("error", payload);
            case "finished":
                payload.put("status", "completed");
                payload.put("chunk", chunk);
                return new RunEvent("end", payload);
            default:
                payload.put("name", "wenqu." + status);
                payload.put("chunk", chunk);
                return new RunEvent("custom", payload);
        }
    }

    private void appendEndEvent(String runId, String status, String threadId, Map<String, Object> payload) {
        Map<String, Object> endPayload = new LinkedHashMap<>();
        endPayload.put("status", status);
        if (payload != null) {
            endPayload.putAll(payload);
        }
        appendRunEventBestEffort(runId, "end", endPayload, threadId);
    }

    // =========================================================================
    // === 终态收敛 ===
    // =========================================================================

    /** 收敛 Run 终态并在可见前清理 runtime（对应 {@code _finish_run}）。 */
    private TerminalTransition finishRun(
            String runId,
            String status,
            String threadId,
            Map<String, Object> chunk,
            User currentUser,
            String workerId,
            String errorType,
            String errorMessage,
            boolean publishEnd) {
        AgentRun run = getRun(runId);
        Map<String, Object> tokenUsage = new LinkedHashMap<>();
        tokenUsage.put("available", false);
        if (JsonValues.truthy(threadId)) {
            Map<String, Object> stateTokenUsage = readRunTokenUsageFromState(runId, threadId, currentUser);
            if (stateTokenUsage != null) {
                tokenUsage = stateTokenUsage;
            }
        }
        TerminalTransition transition =
                markRunTerminal(runId, status, errorType, errorMessage, tokenUsage, workerId);
        if (AgentRunRepository.TERMINAL_RUN_STATUSES.contains(transition.status())) {
            AgentRun committedRun = getRun(runId);
            releaseRuntimeBeforeTerminalEvent(committedRun == null ? run : committedRun);
        }
        if (publishEnd && transition.changed() && JsonValues.truthy(transition.status())) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("chunk", chunk);
            appendEndEvent(runId, transition.status(), threadId, payload);
        }
        return transition;
    }

    /** 在持久层已确认取消后，由当前 owner 写入 cancelled（对应 {@code _finish_user_cancel}）。 */
    private TerminalTransition finishUserCancel(
            String runId,
            String requestId,
            String threadId,
            User currentUser,
            String workerId,
            ChunkedEventWriter writer,
            AgentRun run) {
        flushWriterBestEffort(writer);
        Map<String, Object> cancelChunk = new LinkedHashMap<>();
        cancelChunk.put("status", "interrupted");
        cancelChunk.put("message", "对话已取消");
        cancelChunk.put("request_id", requestId);
        Map<String, Object> stateTokenUsage = null;
        if (currentUser != null) {
            stateTokenUsage = readRunTokenUsageFromState(runId, threadId, currentUser);
        }
        Map<String, Object> tokenUsage = new LinkedHashMap<>();
        if (stateTokenUsage != null) {
            tokenUsage = stateTokenUsage;
        } else {
            tokenUsage.put("available", false);
        }
        TerminalTransition transition =
                markRunTerminal(runId, "cancelled", "cancelled", "对话已取消", tokenUsage, workerId);
        if (!"subagent".equals(run.getRunType())) {
            releaseRuntimeBeforeTerminalEvent(run);
        }
        if (transition.changed()) {
            Map<String, Object> interruptPayload = new LinkedHashMap<>();
            interruptPayload.put("reason", "cancelled");
            interruptPayload.put("chunk", cancelChunk);
            appendRunEventBestEffort(runId, "interrupt", interruptPayload, threadId);
            Map<String, Object> endPayload = new LinkedHashMap<>();
            endPayload.put("chunk", cancelChunk);
            appendEndEvent(runId, "cancelled", threadId, endPayload);
        }
        return transition;
    }

    // =========================================================================
    // === 可取消的执行流消费 ===
    // =========================================================================

    /**
     * 消费执行流，取消优先于下一分片（对应 {@code _consume_stream_with_cancel}）。
     *
     * <p>参考实现每 Run 只建一个取消等待器，并在退出前回收执行任务与生成器；本工程由
     * {@link StreamChannel}（生产线程 + 有界轮询 + 关闭时中断 join）等价承载。
     */
    private void consumeStreamWithCancel(StreamProducer producer, RunContext runCtx, Consumer<String> onChunk) {
        StreamChannel channel = new StreamChannel(runCtx.runId);
        channel.start(producer);
        RunCancellation cancellation = null;
        RuntimeException failure = null;
        try {
            while (true) {
                Object item = channel.poll();
                if (runCtx.isCancelled()) {
                    cancellation = new RunCancellation("run " + runCtx.runId + " cancelled");
                    break;
                }
                if (item == StreamChannel.END) {
                    break;
                }
                if (item == null) {
                    continue;
                }
                onChunk.accept((String) item);
            }
        } catch (RunCancellation error) {
            cancellation = error;
        } catch (RuntimeException | Error error) {
            failure = error instanceof RuntimeException runtime ? runtime : new RunExecutionError(error);
        }
        channel.close(cancellation != null);
        if (failure != null) {
            throw failure;
        }
        if (cancellation != null) {
            throw cancellation;
        }
    }

    /** 执行流的生产线程与分片队列（≡ async generator + {@code aclosing}）。 */
    private static final class StreamChannel {

        private static final Object END = new Object();

        private final String runId;
        private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private Thread thread;

        StreamChannel(String runId) {
            this.runId = runId;
        }

        void start(StreamProducer producer) {
            Thread worker = new Thread(
                    () -> {
                        try {
                            producer.produce(chunk -> {
                                try {
                                    queue.put(chunk);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            });
                        } catch (Throwable error) {
                            failure.set(error);
                        } finally {
                            // 先置 finished 再放结束标记：消费方看到标记即保证 finished 为真（见 close）。
                            finished.set(true);
                            queue.add(END);
                        }
                    },
                    "wenqu-run-stream");
            worker.setDaemon(true);
            this.thread = worker;
            worker.start();
        }

        /** 取下一分片；返回 {@link #END} 表示流结束，{@code null} 表示本次等待超时。 */
        Object poll() {
            try {
                return queue.poll(CANCEL_POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RunCancellation("run " + runId + " cancelled while waiting for stream chunk");
            }
        }

        /**
         * 关闭整条执行链后外层才能释放 lease 或重试（对应 {@code close_execution}）。
         *
         * <p>顺序与参考实现一致：先回收执行任务与生成器（{@code cleanup.result()}），
         * 再上报「关闭期间被重复取消」。{@code cancelling} 表示本次关闭是取消所致
         * （此时中断运行中的执行流即取消事实）；否则中断只用于回收，原异常继续上抛。
         */
        void close(boolean cancelling) {
            boolean interruptedRunningStream = !finished.get();
            thread.interrupt();
            boolean cancelledDuringCleanup = false;
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException interrupted) {
                    // ≡ 参考实现「ARQ abort 和进程退出可以重复取消」：执行未关闭就不能交出 owner。
                    cancelledDuringCleanup = true;
                }
            }
            Throwable error = failure.get();
            if (interruptedRunningStream) {
                if (cancelling || cancelledDuringCleanup) {
                    RunCancellation cancellation = new RunCancellation("run " + runId + " cancelled");
                    if (error != null) {
                        cancellation.addSuppressed(error);
                    }
                    throw cancellation;
                }
                return;
            }
            if (error != null) {
                throw new RunExecutionError(error);
            }
            if (cancelledDuringCleanup) {
                throw new RunCancellation("run " + runId + " cancelled during cleanup");
            }
        }
    }

    // =========================================================================
    // === Run 执行 ===
    // =========================================================================

    /** 流式消费的可变游标（参考实现里是 {@code process_agent_run} 的局部变量）。 */
    private static final class StreamCursor {
        private final String runId;
        private final String threadId;
        private final String requestId;
        private final String workerId;
        private final User user;
        private final AgentRun run;
        private final ChunkedEventWriter writer;
        private boolean terminalSet = false;
        private boolean firstOutputObserved = false;
        private PendingInterrupt pendingInterrupt = null;

        StreamCursor(
                String runId,
                String threadId,
                String requestId,
                String workerId,
                User user,
                AgentRun run,
                ChunkedEventWriter writer) {
            this.runId = runId;
            this.threadId = threadId;
            this.requestId = requestId;
            this.workerId = workerId;
            this.user = user;
            this.run = run;
            this.writer = writer;
        }
    }

    /** 选择执行流入口（对应 {@code process_agent_run} 里的 resume / chat+subagent 分支）。 */
    private StreamProducer buildStreamProducer(
            String runType,
            String agentSlug,
            String threadId,
            Map<String, Object> meta,
            Object resumeInput,
            InputMessageService.AgentRunInputMessage normalizedInputMessage,
            User user,
            PreparedRunExecution preparedExecution,
            Runnable onPrepared) {
        if ("resume".equals(runType)) {
            return sink -> chatService.streamAgentResume(
                    threadId, resumeInput, meta, user, preparedExecution, onPrepared, sink);
        }
        if ("chat".equals(runType) || "subagent".equals(runType)) {
            return sink -> chatService.streamAgentChat(
                    agentSlug, threadId, meta, normalizedInputMessage, user, preparedExecution, onPrepared, sink);
        }
        throw new IllegalStateException("unsupported run_type after validation: " + runType);
    }

    /**
     * 执行已固化的 Run：选流 → 消费 → 收尾
     * （对应 {@code process_agent_run} 的 {@code async with … / aclosing(…) as chunks} 区块）。
     */
    private void streamAndSettle(
            String runId,
            String runType,
            String agentSlug,
            String threadId,
            String requestId,
            String workerId,
            User user,
            AgentRun run,
            Map<String, Object> meta,
            Object resumeInput,
            InputMessageService.AgentRunInputMessage normalizedInputMessage,
            PreparedRunExecution preparedExecution,
            Runnable onPrepared,
            RunContext runCtx,
            ChunkedEventWriter writer,
            boolean alreadyFirstOutput) {
        StreamCursor cursor = new StreamCursor(runId, threadId, requestId, workerId, user, run, writer);
        cursor.firstOutputObserved = alreadyFirstOutput;
        StreamProducer producer = buildStreamProducer(
                runType, agentSlug, threadId, meta, resumeInput, normalizedInputMessage, user, preparedExecution, onPrepared);
        consumeStreamWithCancel(producer, runCtx, chunkText -> consumeChunk(chunkText, cursor, runCtx));

        writer.flushAll();
        if (cursor.pendingInterrupt != null && !cursor.terminalSet) {
            Map<String, Object> interruptChunk = cursor.pendingInterrupt.chunk();
            String interruptThreadId = cursor.pendingInterrupt.threadId();
            RunEvent mapped = mapChunkToRunEvent(interruptChunk);

            String firstQuestion = "";
            Object questions = interruptChunk.get("questions");
            if (questions instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
                Object question = first.get("question");
                firstQuestion = question == null ? "" : String.valueOf(question).strip();
            }

            String interruptStatus = chunkStatus(interruptChunk);
            String interruptErrorMessage = "human_approval_required".equals(interruptStatus)
                    ? "需要用户审批工具操作"
                    : (firstQuestion.isEmpty() ? "需要用户回答问题" : firstQuestion);
            TerminalTransition transition = finishRun(
                    runId,
                    "interrupted",
                    threadId,
                    interruptChunk,
                    user,
                    workerId,
                    interruptChunk.get("status") == null ? null : JsonValues.text(interruptChunk.get("status")),
                    interruptErrorMessage,
                    false);
            if (transition.changed() || "interrupted".equals(transition.status())) {
                appendRunEventBestEffort(runId, mapped.type(), mapped.payload(), interruptThreadId);
                Map<String, Object> endPayload = new LinkedHashMap<>();
                endPayload.put("chunk", interruptChunk);
                appendEndEvent(runId, transition.status() == null ? "interrupted" : transition.status(), threadId, endPayload);
            }
            cursor.terminalSet = AgentRunRepository.TERMINAL_RUN_STATUSES.contains(transition.status());
        }

        if (!cursor.terminalSet) {
            if (runCtx.isCancelled()) {
                throw new RunCancellation("run " + runId + " cancelled");
            }
            Map<String, Object> finishedChunk = new LinkedHashMap<>();
            finishedChunk.put("status", "finished");
            finishedChunk.put("request_id", requestId);
            finishRun(runId, "completed", threadId, finishedChunk, user, workerId, null, null, true);
        }
    }

    /** 处理一个执行流分片（对应 {@code process_agent_run} 的内层循环体）。 */
    private void consumeChunk(String chunkText, StreamCursor cursor, RunContext runCtx) {
        for (Map<String, Object> chunk : iterJsonChunks(chunkText)) {
            String targetThreadId = chunkThreadId(chunk, cursor.threadId);
            if ("loading".equals(chunk.get("status"))) {
                if (!cursor.firstOutputObserved
                        && Objects.equals(targetThreadId, cursor.threadId)
                        && containsModelOutput(chunk)) {
                    cursor.firstOutputObserved = true;
                    java.time.LocalDateTime firstOutputAt = DateTimeUtils.utcNowNaive();
                    cursor.writer.append(chunk, targetThreadId);
                    cursor.writer.flush(targetThreadId);
                    recordRunTimingBestEffort(cursor.runId, cursor.workerId, "first_output", firstOutputAt);
                    continue;
                }
                cursor.writer.append(chunk, targetThreadId);
                continue;
            }

            cursor.writer.flush(targetThreadId);
            String status = chunkStatus(chunk);
            RunEvent mapped = mapChunkToRunEvent(chunk);
            boolean isParentApproval = Objects.equals(targetThreadId, cursor.threadId)
                    && ("ask_user_question_required".equals(status) || "human_approval_required".equals(status));
            if (isParentApproval) {
                cursor.pendingInterrupt = new PendingInterrupt(chunk, targetThreadId);
            } else if (!"end".equals(mapped.type())
                    && !(Objects.equals(targetThreadId, cursor.threadId)
                            && ("error".equals(status) || "interrupted".equals(status)))) {
                appendRunEventBestEffort(cursor.runId, mapped.type(), mapped.payload(), targetThreadId);
            }

            if (runCtx.isCancelled()) {
                throw new RunCancellation("run " + cursor.runId + " cancelled");
            }
            if (!Objects.equals(targetThreadId, cursor.threadId)) {
                continue;
            }

            if ("finished".equals(status)) {
                if (Boolean.TRUE.equals(chunk.get("terminal_committed"))) {
                    AgentRun committedRun = getRun(cursor.runId);
                    if (committedRun != null) {
                        finishExecutionTreeChildren(committedRun);
                    }
                    releaseRuntimeBeforeTerminalEvent(committedRun);
                    Map<String, Object> endPayload = new LinkedHashMap<>();
                    endPayload.put("chunk", chunk);
                    appendEndEvent(cursor.runId, "completed", cursor.threadId, endPayload);
                    cursor.terminalSet = true;
                } else {
                    TerminalTransition transition = finishRun(
                            cursor.runId, "completed", cursor.threadId, chunk, cursor.user, cursor.workerId,
                            null, null, true);
                    cursor.terminalSet = AgentRunRepository.TERMINAL_RUN_STATUSES.contains(transition.status());
                }
            } else if ("error".equals(status)) {
                Object errorType = JsonValues.or(chunk.get("error_type"), "stream_error");
                Object errorMessage = JsonValues.or(chunk.get("error_message"), chunk.get("message"));
                TerminalTransition transition = finishRun(
                        cursor.runId,
                        "failed",
                        cursor.threadId,
                        chunk,
                        cursor.user,
                        cursor.workerId,
                        errorType == null ? null : String.valueOf(errorType),
                        errorMessage == null ? null : String.valueOf(errorMessage),
                        false);
                if (transition.changed()) {
                    appendRunEventBestEffort(cursor.runId, mapped.type(), mapped.payload(), targetThreadId);
                    Map<String, Object> endPayload = new LinkedHashMap<>();
                    endPayload.put("chunk", chunk);
                    appendEndEvent(
                            cursor.runId,
                            transition.status() == null ? "failed" : transition.status(),
                            cursor.threadId,
                            endPayload);
                }
                cursor.terminalSet = AgentRunRepository.TERMINAL_RUN_STATUSES.contains(transition.status());
            } else if ("interrupted".equals(status)) {
                String statusValue = isCancelRequested(cursor.runId) ? "cancelled" : "interrupted";
                Object message = chunk.get("message");
                TerminalTransition transition = finishRun(
                        cursor.runId,
                        statusValue,
                        cursor.threadId,
                        chunk,
                        cursor.user,
                        cursor.workerId,
                        statusValue,
                        message == null ? null : String.valueOf(message),
                        false);
                if (transition.changed() || "interrupted".equals(transition.status())) {
                    appendRunEventBestEffort(cursor.runId, mapped.type(), mapped.payload(), targetThreadId);
                    Map<String, Object> endPayload = new LinkedHashMap<>();
                    endPayload.put("chunk", chunk);
                    appendEndEvent(
                            cursor.runId,
                            transition.status() == null ? statusValue : transition.status(),
                            cursor.threadId,
                            endPayload);
                }
                cursor.terminalSet = AgentRunRepository.TERMINAL_RUN_STATUSES.contains(transition.status());
            }
        }
    }

    /**
     * 执行队列中的 AgentRun，并只从 run 列和输入消息恢复运行参数（对应 {@code process_agent_run}）。
     *
     * <p>{@code ctx} 与 {@link TaskService#processTask} 同形：至少含 {@code worker_id} 与 {@code job_try}。
     */
    public void processAgentRun(Map<String, Object> ctx, String runId) {
        AgentRun run = getRun(runId);
        if (run == null) {
            log.warn("Run not found: {}", runId);
            return;
        }

        if (AgentRunRepository.TERMINAL_RUN_STATUSES.contains(run.getStatus())) {
            finishExecutionTreeChildren(run);
            boolean cleanupWasPending = Boolean.TRUE.equals(run.getRuntimeCleanupPending());
            if (cleanupWasPending) {
                requireRuntimeCleanup(run, "Run " + runId + " 的 execution tree 尚未完成 runtime cleanup");
                appendEndEvent(runId, run.getStatus(), run.getConversationThreadId(), null);
            }
            if ("completed".equals(run.getStatus())) {
                agentRequestQueueService.dispatchNextRequest(
                        run.getUid(), run.getAgentSlug(), run.getConversationThreadId());
            }
            log.info("Run already terminal, skip: {}, status={}", runId, run.getStatus());
            return;
        }

        if (Boolean.TRUE.equals(run.getRuntimeCleanupPending())) {
            requireRuntimeCleanup(run, "Run " + runId + " 尚未完成 retry runtime cleanup");
            run = getRun(runId);
            if (run == null) {
                throw new NonRetryableRunError("Run " + runId + " 在 runtime cleanup 后不存在");
            }
        }

        String workerId = runOwnerToken(ctx);
        if (!markRunRunning(runId, workerId)) {
            log.info("Run lease is owned elsewhere or expired, skip: {}", runId);
            return;
        }

        String runType = run.getRunType();
        String agentSlug = run.getAgentSlug();
        String uid = run.getUid();
        String requestId = run.getRequestId();
        String threadId = run.getConversationThreadId();
        User user = null;
        RunContext runCtx = new RunContext(runId, workerId);
        ChunkedEventWriter writer =
                new ChunkedEventWriter(runId, threadId, LOADING_FLUSH_INTERVAL_MS, LOADING_FLUSH_MAX_CHARS);
        try {
            if (isCancelRequested(runId)) {
                runCtx.cancel();
                throw new RunCancellation("run " + runId + " cancelled before execution");
            }

            Map<String, Object> inputPayload = AgentRunService.parseJsonObject(run.getInputPayload());
            if (inputPayload == null) {
                markRunTerminal(
                        runId, "failed", "invalid_input_payload", "run input_payload 必须是对象", null, workerId);
                return;
            }
            Object runtimeRaw = JsonValues.or(inputPayload.get("runtime"), new LinkedHashMap<>());
            if (!(runtimeRaw instanceof Map)) {
                markRunTerminal(
                        runId, "failed", "invalid_runtime_payload", "run input_payload.runtime 必须是对象", null, workerId);
                return;
            }

            Message inputMessage = loadInputMessage(run.getInputMessageId());
            if (inputMessage == null) {
                markRunTerminal(runId, "failed", "input_message_not_found", "运行任务缺少输入消息", null, workerId);
                return;
            }
            Map<String, Object> inputMetadata = AgentRunService.parseJsonObject(inputMessage.getExtraMetadata());
            if (inputMetadata == null) {
                markRunTerminal(runId, "failed", "invalid_input_metadata", "输入消息 metadata 必须是对象", null, workerId);
                return;
            }
            String imageContent = inputMessage.getImageContent();

            if (!SUPPORTED_RUN_TYPES.contains(runType)) {
                markRunTerminal(
                        runId, "failed", "invalid_run_type", "不支持的 run_type: " + runType, null, workerId);
                return;
            }

            user = loadUser(uid);
            if (user == null) {
                markRunTerminal(runId, "failed", "user_not_found", "user " + uid + " not found", null, workerId);
                return;
            }

            AuthorizedWorkdir workdirBinding;
            try {
                workdirBinding = validateRunWorkdirBinding(run);
            } catch (RuntimeException error) {
                markRunTerminal(runId, "failed", "invalid_runtime_scope", error.getMessage(), null, workerId);
                return;
            }

            Object[] resumeInputHolder = new Object[1];
            InputMessageService.AgentRunInputMessage[] normalizedInputHolder =
                    new InputMessageService.AgentRunInputMessage[1];
            if ("resume".equals(runType)) {
                resumeInputHolder[0] = inputMetadata.get("resume");
                if (resumeInputHolder[0] == null) {
                    markRunTerminal(runId, "failed", "resume_input_not_found", "resume run 缺少 resume 输入", null, workerId);
                    return;
                }
            } else {
                try {
                    normalizedInputHolder[0] = InputMessageService.restoreChatInputMessage(
                            inputMessage.getContent(), imageContent, inputMetadata);
                } catch (IllegalArgumentException error) {
                    markRunTerminal(runId, "failed", "invalid_input_message", error.getMessage(), null, workerId);
                    return;
                }
            }

            runCtx.start();
            // 准备配置期间也续租；manifest 提交成功前不得开始构图执行。
            PreparedRunExecution preparedExecution;
            try {
                preparedExecution = prepareAndRecordRunExecution(run, user, workerId, workdirBinding);
            } catch (RuntimeException manifestError) {
                if (isCancelRequested(runId)) {
                    throw new RunCancellation("run " + runId + " cancelled during preparation");
                }
                log.error("Failed to persist AgentRun manifest: run=" + runId, manifestError);
                markRunTerminal(
                        runId,
                        "failed",
                        "manifest_persist_failed",
                        "运行清单固化失败，执行未开始：" + manifestError.getMessage(),
                        null,
                        workerId);
                return;
            }

            // 固化期间用户可能已取消；复查一次，把取消竞态窗口恢复到执行开始前的水平。
            if (isCancelRequested(runId)) {
                throw new RunCancellation("run " + runId + " cancelled after manifest recorded");
            }

            Map<String, Object> meta = buildRunMeta(
                    runId, requestId, agentSlug, threadId, user, imageContent, inputMetadata, runType, run, workerId,
                    preparedExecution);

            Map<String, Object> metadataEvent = new LinkedHashMap<>();
            metadataEvent.put("request_id", requestId);
            metadataEvent.put("agent_slug", agentSlug);
            metadataEvent.put("uid", uid);
            metadataEvent.put("source", inputMetadata.get("source"));
            metadataEvent.put("run_type", runType);
            metadataEvent.put("created_by_run_id", run.getCreatedByRunId());
            metadataEvent.put("subagent_slug", "subagent".equals(runType) ? agentSlug : null);
            if (inputMetadata.get("agent_invocation_meta") instanceof Map) {
                metadataEvent.put("agent_invocation_meta", JsonValues.asMap(inputMetadata.get("agent_invocation_meta")));
            }
            appendRunEventBestEffort(runId, "metadata", metadataEvent, threadId);

            Runnable recordPrepared = () -> recordRunTimingBestEffort(
                    runId, workerId, "prepared", DateTimeUtils.utcNowNaive());

            streamAndSettle(
                    runId,
                    runType,
                    agentSlug,
                    threadId,
                    requestId,
                    workerId,
                    user,
                    run,
                    meta,
                    resumeInputHolder[0],
                    normalizedInputHolder[0],
                    preparedExecution,
                    recordPrepared,
                    runCtx,
                    writer,
                    run.getFirstOutputAt() != null);
        } catch (RunCancellation cancellation) {
            // 参考实现在此处调用 model_request_recorder.persist；本工程已并入
            // ModelRequestTimingMiddleware 的首次拦截（见类注释平台差异 9）。
            flushWriterBestEffort(writer);
            if (runCtx.leaseLost) {
                log.warn("Run stopped after losing its lease: {}", runId);
                return;
            }
            if (confirmedUserCancel(runId)) {
                TerminalTransition transition = finishUserCancel(
                        runId, requestId, threadId, user, workerId, writer, run);
                log.info("Run user cancellation settled: run={}, changed={}", runId, transition.changed());
                return;
            }

            boolean released;
            try {
                released = releaseRunLeaseForRetry(runId, workerId);
            } catch (RuntimeException error) {
                log.error("Infrastructure cancellation could not release AgentRun lease: run=" + runId, error);
                throw cancellation;
            }
            if (!released && confirmedUserCancel(runId)) {
                TerminalTransition transition = finishUserCancel(
                        runId, requestId, threadId, user, workerId, writer, run);
                log.info("Run concurrent user cancellation settled: run={}, changed={}", runId, transition.changed());
                return;
            }
            if (!released) {
                log.warn("Infrastructure cancellation could not release AgentRun lease: run={}", runId);
            }
            throw cancellation;
        } catch (RuntimeCleanupPendingError pending) {
            throw pending;
        } catch (RunExecutionError error) {
            flushWriterBestEffort(writer);
            String message = error.getMessage();
            log.error("Run failed {}: {}", runId, message);
            Map<String, Object> errorChunk = errorChunk(requestId, "worker_error", message, false);
            errorChunk.put("retryable", false);
            TerminalTransition transition = finishRun(
                    runId, "failed", threadId, errorChunk, user, workerId, "worker_error", message, false);
            if (transition.changed()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("chunk", errorChunk);
                payload.put("retryable", false);
                appendRunEventBestEffort(runId, "error", payload, threadId);
                Map<String, Object> endPayload = new LinkedHashMap<>();
                endPayload.put("chunk", errorChunk);
                appendEndEvent(runId, "failed", threadId, endPayload);
            }
        } catch (RuntimeException error) {
            flushWriterBestEffort(writer);
            if (isRetryableException(error)) {
                handleRetryableFailure(ctx, runId, requestId, threadId, user, workerId, writer, run, error);
                return;
            }
            log.error("Run failed {}: {}", runId, error.getMessage());
            Map<String, Object> errorChunk = errorChunk(requestId, "worker_error", error.getMessage(), false);
            TerminalTransition transition = finishRun(
                    runId, "failed", threadId, errorChunk, user, workerId, "worker_error", error.getMessage(), false);
            if (transition.changed()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("chunk", errorChunk);
                payload.put("retryable", false);
                appendRunEventBestEffort(runId, "error", payload, threadId);
                Map<String, Object> endPayload = new LinkedHashMap<>();
                endPayload.put("chunk", errorChunk);
                appendEndEvent(runId, "failed", threadId, endPayload);
            }
        } finally {
            runCtx.close();
            AgentRun finalRun;
            try {
                finalRun = getRun(runId);
            } catch (RuntimeException error) {
                log.error("Failed to load AgentRun during lifecycle cleanup: run=" + runId, error);
                finalRun = null;
            }
            if (finalRun != null && AgentRunRepository.TERMINAL_RUN_STATUSES.contains(finalRun.getStatus())) {
                finishExecutionTreeChildren(finalRun);
            }
            if (finalRun != null && "cancelled".equals(finalRun.getStatus())) {
                runQueueService.clearCancelSignal(runId);
            }
            // completed 后尝试派发线程的下一个排队请求
            if (finalRun != null
                    && "completed".equals(finalRun.getStatus())
                    && !Boolean.TRUE.equals(finalRun.getRuntimeCleanupPending())) {
                agentRequestQueueService.dispatchNextRequest(uid, agentSlug, threadId);
            }
        }
    }

    /** 可重试失败的收尾（对应 {@code except Exception} 里的 retryable 分支）。 */
    private void handleRetryableFailure(
            Map<String, Object> ctx,
            String runId,
            String requestId,
            String threadId,
            User user,
            String workerId,
            ChunkedEventWriter writer,
            AgentRun run,
            RuntimeException error) {
        int jobTry = jobTry(ctx);
        log.warn("Run retryable failure {} (try={}): {}", runId, jobTry, error.getMessage());
        Map<String, Object> retryableErrorChunk = errorChunk(requestId, "retryable_worker_error", error.getMessage(), true);
        retryableErrorChunk.put("job_try", jobTry);
        if (confirmedUserCancel(runId)) {
            finishUserCancel(runId, requestId, threadId, user, workerId, writer, run);
            return;
        }
        if (isLastTry(ctx)) {
            TerminalTransition transition = finishRun(
                    runId,
                    "failed",
                    threadId,
                    retryableErrorChunk,
                    user,
                    workerId,
                    "retryable_worker_error",
                    error.getMessage(),
                    false);
            if (transition.changed()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("chunk", retryableErrorChunk);
                payload.put("retryable", true);
                appendRunEventBestEffort(runId, "error", payload, threadId);
                Map<String, Object> endPayload = new LinkedHashMap<>();
                endPayload.put("chunk", retryableErrorChunk);
                appendEndEvent(
                        runId,
                        transition.status() == null ? "failed" : transition.status(),
                        threadId,
                        endPayload);
            }
            log.error("Run failed after retries exhausted {}: {}", runId, error.getMessage());
            return;
        }

        if (!releaseRunLeaseForRetry(runId, workerId)) {
            if (confirmedUserCancel(runId)) {
                finishUserCancel(runId, requestId, threadId, user, workerId, writer, run);
                return;
            }
            log.warn("Run retry skipped after ownership changed: {}", runId);
            return;
        }
        AgentRun retryRun = getRun(runId);
        if (retryRun != null && !"subagent".equals(retryRun.getRunType())) {
            requireRuntimeCleanup(retryRun, "Run " + runId + " 尚未完成 retry runtime cleanup");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chunk", retryableErrorChunk);
        payload.put("retryable", true);
        appendRunEventBestEffort(runId, "error", payload, threadId);
        if (error instanceof RetryableRunError) {
            throw error;
        }
        throw new RetryableRunError(String.valueOf(error.getMessage()), error);
    }

    private static Map<String, Object> errorChunk(
            String requestId, String errorType, String errorMessage, boolean retryable) {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("status", "error");
        chunk.put("error_type", errorType);
        chunk.put("error_message", errorMessage);
        chunk.put("request_id", requestId);
        chunk.put("retryable", retryable);
        return chunk;
    }

    /** 组装执行流 meta（对应 {@code process_agent_run} 的 meta 键序）。 */
    private static Map<String, Object> buildRunMeta(
            String runId,
            String requestId,
            String agentSlug,
            String threadId,
            User user,
            Object imageContent,
            Map<String, Object> inputMetadata,
            String runType,
            AgentRun run,
            String workerId,
            PreparedRunExecution preparedExecution) {
        com.wisesoft.wenqu.agents.BaseContext context = preparedExecution.context();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("run_id", runId);
        meta.put("request_id", requestId);
        meta.put("agent_slug", agentSlug);
        meta.put("thread_id", threadId);
        meta.put("uid", user.getUid());
        meta.put("has_image", JsonValues.truthy(imageContent));
        meta.put(
                "attachment_file_ids",
                JsonValues.or(inputMetadata.get("attachment_file_ids"), new ArrayList<>()));
        meta.put("model_spec", context.getString("model"));
        meta.put("tool_approval_mode", context.getString("tool_approval_mode"));
        meta.put("run_type", runType);
        meta.put("created_by_run_id", run.getCreatedByRunId());
        meta.put("worker_id", workerId);
        meta.put("runtime_scope_id", context.getString("runtime_scope_id"));
        meta.put("workdir_relative_path", context.getString("workdir_relative_path"));
        meta.put("workdir_path", context.getString("workdir_path"));
        if ("subagent".equals(runType)) {
            meta.put("parent_thread_id", context.getString("parent_thread_id"));
        }
        if (JsonValues.truthy(inputMetadata.get("source"))) {
            meta.put("source", inputMetadata.get("source"));
        }
        if (inputMetadata.get("agent_invocation_meta") instanceof Map) {
            meta.put("agent_invocation_meta", JsonValues.asMap(inputMetadata.get("agent_invocation_meta")));
        }
        return meta;
    }

    /** 加载 run 绑定的输入消息；worker 从这里恢复 query、resume、图片和请求元数据（对应 {@code _load_input_message}）。 */
    private Message loadInputMessage(Integer messageId) {
        if (messageId == null || messageId == 0) {
            return null;
        }
        return messageMapper.selectById(messageId);
    }

    // =========================================================================
    // === 收敛循环 ===
    // =========================================================================

    /** 周期收敛失去 heartbeat 的 Run（对应 {@code _reconcile_agent_run_leases_forever}）。 */
    private void reconcileAgentRunLeasesForever() {
        while (true) {
            try {
                Thread.sleep((long) (RunQueueService.RUN_RECONCILIATION_SECONDS * 1000L));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                List<String> reconciledIds = reconcileExpiredRunLeases(null);
                if (!reconciledIds.isEmpty()) {
                    log.warn("Reconciled expired AgentRun leases: count={}", reconciledIds.size());
                }
                List<String> cleanedIds = reconcilePendingRuntimeCleanups();
                if (!cleanedIds.isEmpty()) {
                    log.warn("Reconciled pending runtime cleanups: count={}", cleanedIds.size());
                }
                agentRequestQueueService.recoverPendingDispatches();
                scheduledAgentService.recoverScheduledDispatches(SCHEDULED_RECOVERY_LIMIT);
                scheduledAgentService.claimAndDispatchDueJobs(SCHEDULED_CLAIM_LIMIT);
                publishReconciliationHealth();
            } catch (RuntimeException error) {
                log.error("Failed to reconcile expired AgentRun leases", error);
            }
        }
    }

    /** 周期收敛失联通用 Task，并补发持久 pending 意图（对应 {@code _reconcile_durable_tasks_forever}）。 */
    private void reconcileDurableTasksForever() {
        while (true) {
            try {
                Thread.sleep((long) (TaskQueueService.TASK_RECONCILIATION_SECONDS * 1000L));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                Object reconciled = taskQueueService.reconcileAndPublishTasks();
                int count = reconciled instanceof List<?> list ? list.size() : 0;
                if (count > 0) {
                    log.warn("Reconciled expired durable tasks: count={}", count);
                }
                publishTaskReconciliationHealth();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception error) {
                log.error("Failed to reconcile durable tasks", error);
            }
        }
    }

    /** 续租 worker 的 Durable Task 收敛与 pending 补发能力（对应 {@code _publish_task_reconciliation_health}）。 */
    public void publishTaskReconciliationHealth() {
        redis.opsForValue()
                .set(
                        TaskQueueService.TASK_RECONCILIATION_HEALTH_KEY,
                        WORKER_ID,
                        java.time.Duration.ofSeconds(TaskQueueService.TASK_RECONCILIATION_HEALTH_TTL_SECONDS));
    }

    /** 续租 worker 的 AgentRun lease 收敛能力（对应 {@code _publish_reconciliation_health}）。 */
    public void publishReconciliationHealth() {
        redis.opsForValue()
                .set(
                        RunQueueService.WORKER_RECONCILIATION_HEALTH_KEY,
                        WORKER_ID,
                        java.time.Duration.ofSeconds(RunQueueService.WORKER_RECONCILIATION_HEALTH_TTL_SECONDS));
    }

    /** WorkerSettings.health_check_key 的续租（ARQ 自身行为，见类注释字段映射）。 */
    public void publishWorkerHealth() {
        redis.opsForValue()
                .set(
                        RunQueueService.WORKER_HEALTH_KEY,
                        WORKER_ID,
                        java.time.Duration.ofSeconds((long) RunQueueService.WORKER_HEALTH_INTERVAL_SECONDS));
    }

    // =========================================================================
    // === worker 启停（WorkerSettings.on_startup / on_shutdown） ===
    // =========================================================================

    /** 初始化 worker 依赖（对应 {@code _worker_startup}）。 */
    public void workerStartup(Map<String, Object> ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("worker context 必须是字典");
        }
        AuthUtils.requireSecuritySecrets();
        ctx.put("worker_id", WORKER_ID);
        optionsService.ensureOptionsInDb();
        optionsService.invalidateOptionCache(OptionsService.SYSTEM_OPTIONS.getKey());
        try {
            mcpService.ensureBuiltinMcpServersInDb();
        } catch (RuntimeException error) {
            log.error(
                    "Optional worker component failed: component=builtin_mcp_servers, type={}",
                    error.getClass().getSimpleName());
        }
        try {
            skillService.initBuiltinSkills(null);
        } catch (Exception error) {
            throw new IllegalStateException("builtin skills 初始化失败", error);
        }
        List<String> reconciledIds = reconcileExpiredRunLeases(null);
        if (!reconciledIds.isEmpty()) {
            log.warn("Reconciled expired AgentRun leases at startup: count={}", reconciledIds.size());
        }
        reconcilePendingRuntimeCleanups();
        agentRequestQueueService.recoverPendingDispatches();
        try {
            taskQueueService.reconcileAndPublishTasks();
        } catch (Exception error) {
            throw new IllegalStateException("durable task 收敛失败", error);
        }
        publishTaskReconciliationHealth();
        scheduledAgentService.recoverScheduledDispatches(SCHEDULED_RECOVERY_LIMIT);
        scheduledAgentService.claimAndDispatchDueJobs(SCHEDULED_CLAIM_LIMIT);
        publishReconciliationHealth();

        ctx.put(RECONCILIATION_TASK_KEY, startReconciliationLoop("reconcile-agent-run-leases", this::reconcileAgentRunLeasesForever));
        ctx.put(TASK_RECONCILIATION_TASK_KEY, startReconciliationLoop("reconcile-durable-tasks", this::reconcileDurableTasksForever));
        startHealthLoop();
    }

    /** 关闭 worker 共享连接（对应 {@code _worker_shutdown}）。 */
    public void workerShutdown(Map<String, Object> ctx) {
        if (ctx != null) {
            List<Thread> reconciliationThreads = new ArrayList<>();
            for (String key : List.of(RECONCILIATION_TASK_KEY, TASK_RECONCILIATION_TASK_KEY)) {
                Object task = ctx.remove(key);
                if (task instanceof Thread thread) {
                    reconciliationThreads.add(thread);
                }
            }
            for (Thread thread : reconciliationThreads) {
                thread.interrupt();
            }
            joinQuietly(reconciliationThreads);
        }
        if (healthThread != null) {
            healthThread.interrupt();
            joinQuietly(List.of(healthThread));
            healthThread = null;
        }
        // 平台差异：close_queue_clients() / pg_manager.close() 无对位（Redis 与连接池由 Spring 接管）。
    }

    private Thread startReconciliationLoop(String name, Runnable body) {
        Thread thread = RECONCILE_FACTORY.newThread(body);
        thread.setName("wenqu-run-" + name);
        thread.start();
        return thread;
    }

    /** ARQ {@code health_check_interval} 的对位：周期性续租 worker 健康键。 */
    private void startHealthLoop() {
        if (healthThread != null) {
            return;
        }
        Thread thread = WATCHER_FACTORY.newThread(() -> {
            while (true) {
                try {
                    Thread.sleep((long) (RunQueueService.WORKER_HEALTH_INTERVAL_SECONDS * 1000));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    publishWorkerHealth();
                } catch (RuntimeException error) {
                    log.warn("Failed to publish worker health: {}", error.getMessage());
                }
            }
        });
        thread.setName("wenqu-run-health");
        healthThread = thread;
        thread.start();
    }

    private void stopHealthLoop() {
        if (healthThread != null) {
            healthThread.interrupt();
            healthThread = null;
        }
    }

    // =========================================================================
    // === 状态常量透出（供就绪探针与收敛循环共用） ===
    // =========================================================================

    /** 供 {@link ReadinessService} 之外的调试入口读取当前 worker identity。 */
    public static String workerId() {
        return WORKER_ID;
    }

    /** 参考实现里「未终态」集合的对外只读出口（本类多处判据使用）。 */
    public static Set<String> terminalRunStatuses() {
        return new LinkedHashSet<>(AgentRunRepository.TERMINAL_RUN_STATUSES);
    }
}
