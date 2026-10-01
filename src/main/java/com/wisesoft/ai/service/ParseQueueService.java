package com.wisesoft.ai.service;

import com.wisesoft.ai.common.ParseFatalException;
import com.wisesoft.ai.mapper.ParseTaskMapper;
import com.wisesoft.ai.model.ParseTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 文档解析持久化任务队列。
 * <p>
 * **为什么必须有这张表**：旧实现把解析任务丢进 `fixed(2) + 有界队列(50) + 默认 AbortPolicy` 的线程池，
 * 队列一满就 Rejected → 代码**删掉已经 INSERT 的文档记录**并告诉用户"解析队列繁忙，请稍后再试"。
 * 批量上传时第 51 个及之后的文件就这样被静默丢弃（用户看到的"多个解析失败"多半是它）。
 * 任务不落盘，就只能在拥塞时丢任务；任务落盘，队列可以随便排、重启也不丢、失败还能退避重试。
 * <p>
 * 链路（与 Sidekiq / Celery / Hangfire 同构，Dify / Coze / FastGPT 的索引队列也是这个形态）：
 * <pre>
 * 上传登记（落盘+建文档+插任务行，立即 202）
 *   → 扫描器定时抢占（单行 UPDATE 原子 CAS，天然支持多实例，不需要 Redis 锁）
 *   → 投给固定并发的 worker 池（parse.concurrency）
 *   → 执行：解析 → 向量化 → 写回文档状态
 *   → 成功 / 可重试（指数退避回队）/ 终态失败（dead）
 *   → 租约(lease)过期或看门狗超时 → 自动回收回 queued（覆盖崩溃、卡死）
 * </pre>
 * 多线必须配合下游闸门（OCR 版面引擎、图片视觉、embedding），否则并发只是把任务堆在闸门门口排队：
 * OcrEngineGate 按引擎限并发、vision.concurrency 管图片描述、本类的 embedGate 管向量化。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class ParseQueueService {

    /** 本实例的任务执行者标识：任务只由抢占它的实例执行（worker 列据此过滤） */
    private static final String WORKER_ID = workerId();

    /** 看门狗巡检间隔 */
    private static final long WATCHDOG_INTERVAL_MS = 5_000;
    /** 退避上限（秒）：指数退避封顶，避免长尾任务霸占队列 */
    private static final int MAX_BACKOFF_SEC = 300;
    /** 每轮多捞的任务数（抢不到就自然留给下一轮，减少空转 UPDATE） */
    private static final int SCAN_SLACK = 8;

    private final ParseTaskMapper parseTaskMapper;
    private final ConfigService configService;
    /** 解析执行体：DocumentService。构造注入必须 @Lazy——DocumentService 本身又注入本类，否则构造环 */
    private final DocumentService documentService;
    /** 配置变更时（parse.embedConcurrency）按需增减许可 */
    private final Gate embedGate;

    /** worker 池：并发即 parse.concurrency，队列只做提交缓冲（队列满走 CallerRuns，绝不丢任务） */
    private volatile ThreadPoolExecutor parseExecutor;
    /** 任务运行句柄：taskId → 线程/起点，看门狗据此判定超时并中断 */
    private final Map<String, TaskHandle> handles = new ConcurrentHashMap<>();
    private final AtomicBoolean scanning = new AtomicBoolean(false);

    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "doc-parse-watchdog");
        t.setDaemon(true);
        return t;
    });

    public ParseQueueService(ParseTaskMapper parseTaskMapper, ConfigService configService,
                             @org.springframework.context.annotation.Lazy DocumentService documentService) {
        this.parseTaskMapper = parseTaskMapper;
        this.configService = configService;
        this.documentService = documentService;
        this.embedGate = new Gate(Math.max(1, configService.getInt("parse.embedConcurrency", 2)));
    }

    // ==================== 入队 ====================

    /**
     * 登记一个解析任务（上传/重解析/网页导入统一入口）。
     * 只写库，不碰线程池、不解析，调用方在请求线程里调也毫无压力。
     *
     * @param priority 优先级（大者优先；手动重解析可临时提权插队）
     */
    public String enqueue(String docId, String kbId, int priority) {
        ParseTask task = new ParseTask();
        task.setDocId(docId);
        task.setKbId(kbId);
        task.setStatus(ParseTask.STATUS_QUEUED);
        task.setAttempt(0);
        task.setMaxAttempt(Math.max(1, configService.getInt("parse.retryMaxAttempts", 3)));
        task.setNextRunAt(LocalDateTime.now());
        task.setPriority(priority);
        task.setCreateTime(LocalDateTime.now());
        parseTaskMapper.insert(task);
        log.info("[PARSE-QUEUE] 入队 task={} doc={} 当前排队 {}", task.getId(), docId, queuedCount());
        return task.getId();
    }

    /** 作废同一文档上未结束的旧任务（重解析时避免同一文档两个任务抢跑） */
    public void killActiveByDoc(String docId) {
        try {
            int n = parseTaskMapper.killActiveByDoc(docId);
            if (n > 0) log.info("[PARSE-QUEUE] 已作废 doc={} 的 {} 个未结束任务", docId, n);
        } catch (Exception e) {
            log.warn("[PARSE-QUEUE] 作废旧任务失败 doc={}: {}", docId, e.getMessage());
        }
    }

    /** 该文档是否还有未结束的解析任务（启动对账靠它区分"崩溃前已入队"与"任务行丢失"） */
    public boolean hasActiveTask(String docId) {
        try {
            return parseTaskMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ParseTask>()
                    .eq(ParseTask::getDocId, docId)
                    .in(ParseTask::getStatus, ParseTask.STATUS_QUEUED, ParseTask.STATUS_RUNNING,
                            ParseTask.STATUS_RETRYABLE)) > 0;
        } catch (Exception e) {
            log.warn("[PARSE-QUEUE] 查活跃任务失败 doc={}: {}", docId, e.getMessage());
            return false;
        }
    }

    // ==================== 扫描 ====================

    /** 扫描器主体（由 ScheduleCenter 定时调用）：回收租约 → 抢占 → 投池 */
    public void scan() {
        if (!scanning.compareAndSet(false, true)) return;   // 上一轮未结束跳过（防重叠）
        syncConcurrency();                                   // 并发可热改（保持既有"保存即生效"）
        try {
            reapTimeouts();
            int limit = Math.max(1, concurrency()) * 4 + SCAN_SLACK;
            int claimed = parseTaskMapper.claimDueTasks(limit, leaseSeconds(), WORKER_ID);
            if (claimed > 0) {
                List<ParseTask> mine = parseTaskMapper.selectMine(WORKER_ID);
                log.info("[PARSE-QUEUE] 本轮抢占 {} 个任务（在跑 {}）", claimed, mine.size());
                for (ParseTask task : mine) {
                    submit(task);
                }
            }
        } catch (Exception e) {
            log.warn("[PARSE-QUEUE] 扫描失败（下轮重试）: {}", e.getMessage());
        } finally {
            scanning.set(false);
        }
    }

    private void submit(ParseTask task) {
        try {
            parseExecutor.submit(() -> runTask(task.getId(), task.getDocId()));
        } catch (RejectedExecutionException e) {
            // CallerRunsPolicy 基本不会抛（除线程池已 shutdown）；真抛了说明任务手上是有的，
            // 原样退回可重试（退避 5s）等下一轮——落了库的任务绝不丢弃
            log.warn("[PARSE-QUEUE] 线程池拒绝任务 task={} doc={}，退回队列下轮再试", task.getId(), task.getDocId());
            parseTaskMapper.markRetryable(task.getId(), "解析线程池瞬时不可用，已退回队列", 5);
        }
    }

    /** 租约回收：把跑飞的任务（进程崩溃/线程卡死）收回待执行队列 */
    private void reapTimeouts() {
        try {
            int revoked = parseTaskMapper.revokeExpiredLeases();
            if (revoked > 0) {
                log.warn("[PARSE-QUEUE] 租约过期回收 {} 个任务（崩溃/卡死），退回队列等待重跑", revoked);
            }
        } catch (Exception e) {
            log.warn("[PARSE-QUEUE] 租约回收失败（下轮重试）: {}", e.getMessage());
        }
    }

    // ==================== 执行 ====================

    /** 任务执行壳：单一职责是「怎么对待失败」，解析本体在 DocumentService */
    private void runTask(String taskId, String docId) {
        long start = System.currentTimeMillis();
        handles.put(taskId, new TaskHandle(Thread.currentThread(), start));
        try {
            documentService.runParseTask(taskId, docId);
            parseTaskMapper.markSucceeded(taskId);
            log.info("[PARSE-QUEUE] 任务成功 task={} doc={} 耗时 {}s", taskId, docId, (System.currentTimeMillis() - start) / 1000);
        } catch (Throwable e) {
            handleFailure(taskId, docId, e);
        } finally {
            handles.remove(taskId);
        }
    }

    private void handleFailure(String taskId, String docId, Throwable e) {
        String msg = safeMessage(e);
        boolean fatal = isFatal(e);
        if (fatal) {
            parseTaskMapper.markDead(taskId, msg);
            log.warn("[PARSE-QUEUE] 任务终态失败（不重试） task={} doc={}: {}", taskId, docId, msg);
        } else {
            try {
                int backoff = parseTaskMapper.markRetryable(taskId, msg, backoffSeconds(taskId));
                log.warn("[PARSE-QUEUE] 任务可重试 task={} doc={}: {}（下次退避 {}s）", taskId, docId, msg, backoff);
            } catch (Exception inner) {
                // markRetryable 本身失败（例如 attempt 达上限转 dead 的分支写错）→ 直接记终态，不让任务悬空
                log.error("[PARSE-QUEUE] 任务失败且无法记录退避 task={} doc={}: {}", taskId, docId, inner.getMessage());
                try {
                    parseTaskMapper.markDead(taskId, msg);
                } catch (Exception dead2) {
                    log.error("[PARSE-QUEUE] 任务失败记录落库失败 task={} doc={}: {}", taskId, docId, dead2.getMessage());
                }
            }
        }
    }

    /** 指数退避：base(默认5s) × 2^(attempt-1)，封顶 5 分钟 */
    private int backoffSeconds(String taskId) {
        Integer attempt = null;
        ParseTask t = parseTaskMapper.selectById(taskId);
        if (t != null) attempt = t.getAttempt();
        int base = Math.max(1, configService.getInt("parse.retryBackoffSeconds", 5));
        int n = (attempt == null || attempt < 1 ? 1 : attempt);
        long backoff = (long) base * (1L << Math.min(n - 1, 6));
        return (int) Math.min(MAX_BACKOFF_SEC, backoff);
    }

    /** 终态判定：明确的格式/内容错误不重试；其余（超时/IO/OOM/上游 5xx）一律走退避重试 */
    private static boolean isFatal(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof ParseFatalException) return true;
        }
        return false;
    }

    private static String safeMessage(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) m = e.getClass().getSimpleName();
        return m.length() > 480 ? m.substring(0, 480) : m;
    }

    // ==================== 看门狗 ====================

    /** 定时巡检：超过 parse.taskTimeoutMs 仍在跑的任务，中断线程（租约超时后会由 reapTimeouts 兜底回收） */
    @EventListener(ApplicationReadyEvent.class)
    public void startWatchdog() {
        watchdog.scheduleWithFixedDelay(() -> {
            try {
                long limit = taskTimeoutMillis();
                for (Map.Entry<String, TaskHandle> en : handles.entrySet()) {
                    TaskHandle h = en.getValue();
                    if (System.currentTimeMillis() - h.startedAt > limit) {
                        log.warn("[PARSE-QUEUE] 任务超时（>{}ms）中断: task={} doc={}", limit, en.getKey(), h.thread.getName());
                        h.thread.interrupt();
                    }
                }
            } catch (Exception e) {
                log.warn("[PARSE-QUEUE] 看门狗巡检异常: {}", e.getMessage());
            }
        }, WATCHDOG_INTERVAL_MS, WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    // ==================== 下游闸门 ====================

    /** 向量化并发闸（parse.embedConcurrency）：多文档并行解析时别把 embedding 服务打爆 */
    public void acquireEmbedPermission() {
        syncEmbedGate();
        try {
            embedGate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ParseFatalException("向量化等待并发名额时被中断");
        }
    }

    public void releaseEmbedPermission() {
        embedGate.release();
    }

    private void syncEmbedGate() {
        int want = Math.max(1, configService.getInt("parse.embedConcurrency", 2));
        int have = embedGate.availablePermits();
        if (want > have) embedGate.release(want - have);
        else if (want < have) embedGate.reduce(want - have);
    }

    // ==================== 配置与统计 ====================

    public int concurrency() {
        return Math.max(1, configService.getInt("parse.concurrency", 3));
    }

    /** 任务租约时长：应大于任务超时（看门狗先中断，租约只做崩溃兜底） */
    public int leaseSeconds() {
        int explicit = configService.getInt("parse.taskLeaseSeconds", 0);
        if (explicit > 0) return explicit;
        return (int) (taskTimeoutMillis() / 1000) + 300;
    }

    public long taskTimeoutMillis() {
        return Math.max(60_000, configService.getInt("parse.taskTimeoutMs", 20 * 60_000));
    }

    /** 队列统计（前端展示「排队 N / 执行 M」） */
    public Map<String, Object> queueStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        try {
            Map<String, Object> row = parseTaskMapper.queueStats();
            long queued = num(row.get("queued"));
            long running = num(row.get("running"));
            stats.put("queued", queued);
            stats.put("running", running);
            stats.put("dead", num(row.get("dead")));
            stats.put("capacity", capacity());
            stats.put("executorActive", parseExecutor.getActiveCount());
            stats.put("executorQueue", parseExecutor.getQueue().size());
        } catch (Exception e) {
            log.warn("[PARSE-QUEUE] 队列统计失败: {}", e.getMessage());
            stats.put("queued", 0L);
            stats.put("running", 0L);
            stats.put("dead", 0L);
            stats.put("capacity", capacity());
        }
        return stats;
    }

    /** 当前排队数（入队日志与背压判断用） */
    public long queuedCount() {
        try {
            return num(parseTaskMapper.queueStats().get("queued"));
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 队列容量（parse.queue.capacity）：超过则上传直接拒绝并让前端等待，而不是先收下再悄悄丢解析任务。
     * 旧实现是「先收下、队列满、删记录、报繁忙」——丢数据；现在是「早拒绝、不丢数据」。
     */
    public int capacity() {
        return Math.max(50, configService.getInt("parse.queue.capacity", 500));
    }

    /** 是否因队列过满而应拒绝新上传 */
    public boolean isBackpressured() {
        return queuedCount() >= capacity();
    }

    private static long num(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    // ==================== 生命周期 ====================

    @PostConstruct
    void init() {
        parseExecutor = newParseExecutor(concurrency());
        log.info("[PARSE-QUEUE] 解析队列已启动 worker={} 并发={} 租约={}s 队列容量={}",
                WORKER_ID, concurrency(), leaseSeconds(), capacity());
    }

    /** 停机：停止接新任务，等 worker 池里在跑的解析自然收尾（旧实现只 shutdown 不 await，靠"重启一律判失败"擦屁股） */
    @PreDestroy
    void shutdown() {
        watchdog.shutdownNow();
        ThreadPoolExecutor ex = parseExecutor;
        if (ex != null) {
            ex.shutdown();
            try {
                if (!ex.awaitTermination(60, TimeUnit.SECONDS)) {
                    log.warn("[PARSE-QUEUE] 解析线程未在 60s 内收尾，强制中断");
                    ex.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ex.shutdownNow();
            }
        }
    }

    private ThreadPoolExecutor newParseExecutor(int c) {
        return new ThreadPoolExecutor(c, c, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(1024),
                r -> {
                    Thread t = new Thread(r, "doc-parse-" + WORKER_ID);
                    t.setDaemon(false);
                    return t;
                },
                // 兜底绝不丢任务：队列满时让提交方（扫描器）自己慢下来，下一轮再捞
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** 配置热更新：改 parse.concurrency 即时生效（保持既有"保存即生效"习惯） */
    public void syncConcurrency() {
        int c = concurrency();
        ThreadPoolExecutor ex = parseExecutor;
        if (ex != null && (ex.getCorePoolSize() != c || ex.getMaximumPoolSize() != c)) {
            ex.setCorePoolSize(c);
            ex.setMaximumPoolSize(c);
            log.info("[PARSE-QUEUE] 解析并发调整为 {}", c);
        }
    }

    private static final class TaskHandle {
        final Thread thread;
        final long startedAt;

        TaskHandle(Thread thread, long startedAt) {
            this.thread = thread;
            this.startedAt = startedAt;
        }
    }

    private static String workerId() {
        try {
            String host = java.net.InetAddress.getLocalHost().getHostName();
            return host + ":" + ProcessHandle.current().pid() + ":" + Long.toHexString(System.nanoTime());
        } catch (Exception e) {
            return "p" + Long.toHexString(System.nanoTime());
        }
    }

    /** 并发闸：把 Semaphore 的 protected reducePermits 暴露出来（配置下调并发时需要真的减少许可，不能只增不减） */
    private static final class Gate extends Semaphore {
        Gate(int permits) {
            super(permits);
        }

        void reduce(int reductions) {
            reducePermits(reductions);
        }
    }
}
