package com.wisesoft.wenqu.thread;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 共享后台线程池：承接"无返回值、可丢弃、短小"的 fire-and-forget 任务
 * （问答日志落库、会话补写、索引对账/重建、定时任务任务体等）。
 * <p>
 * 不适用的场景请自建专用池（舱壁隔离，避免互相拖累）：
 * - 带延迟预算的同步等待（如检索超时兜底）——共享池排队会吃掉预算
 * - 需要按业务限并发/自定义拒绝语义（如文档解析队列满要提示"队列繁忙"、视觉识别按配置限流）
 * <p>
 * 停机由 ThreadPoolLifecycle 的 @PreDestroy 触发 shutdown()（等待在跑任务收尾，5s 后强制中断）。
 */
@Slf4j
public class ThreadPoolManager {
    private static final ThreadPoolExecutor pool;
    /**
     * 固定并发度：ThreadPoolExecutor 只在队列 offer 失败（队列满）时才扩容到 maxPoolSize，
     * 配合 1024 容量的缓冲队列时 max>core 实际永不生效。故 core=max 明示真实并发度，
     * 避免"以为能扩容到 2×CPU"的错觉。
     */
    private static final int POOL_SIZE = Runtime.getRuntime().availableProcessors() + 1;
    /** 空闲线程回收时间（allowCoreThreadTimeOut=true：本池长期空闲时不常驻线程） */
    private static final long KEEP_ALIVE_TIME = 60;
    private static final TimeUnit TIME_UNIT = TimeUnit.SECONDS;
    // 使用有界队列，避免OOM
    private static final BlockingQueue<Runnable> WORK_QUEUE = new LinkedBlockingQueue<>(1024);
    /**
     * 拒绝策略：丢弃并告警。本池只承接可丢弃的后台任务，绝不阻塞调用方——
     * 阻塞式重试会把队列积压反弹成调用方（可能是 Tomcat 请求线程）的延迟。
     * 日志打任务类名（lambda 类名含所在类，可定位提交方）而非 toString。
     */
    private static final RejectedExecutionHandler REJECT_HANDLER = (r, executor) ->
            log.error("[ThreadPool] 队列已满，任务被丢弃: {}，{}", r.getClass().getName(), getPoolStatus());
    private static final AtomicBoolean isShutdown = new AtomicBoolean(false);

    static {
        pool = new ThreadPoolExecutor(
                POOL_SIZE,
                POOL_SIZE,
                KEEP_ALIVE_TIME,
                TIME_UNIT,
                WORK_QUEUE,
                // 建议指定线程工厂，方便排查线程归属
                new ThreadFactory() {
                    private final AtomicInteger count = new AtomicInteger();

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r, "ai-pool-" + count.getAndIncrement());
                        thread.setDaemon(false); // 非守护线程，配合 @PreDestroy 优雅停机（避免任务被强杀）
                        return thread;
                    }
                },
                REJECT_HANDLER
        );
        // 长期空闲时释放线程（本池多数时间空闲，不必常驻 POOL_SIZE 个线程）
        pool.allowCoreThreadTimeOut(true);
    }

    /**
     * 提交 fire-and-forget 任务。
     * L10 fail-loud：返回 boolean——队列满/已关闭返回 false，调用方（如补描述）可感知并落状态，不再无感知丢弃。
     * 说明：返回 true 仅表示"本次提交成功"；极端并发下仍可能被 REJECT_HANDLER 兜底拒绝（有 error 日志）。
     */
    public static boolean execute(Runnable runnable) {
        if (isShutdown.get()) {
            log.error("线程池已关闭，无法提交任务");
            return false;
        }
        if (pool.getQueue().remainingCapacity() <= 0) {
            log.error("[ThreadPool] 队列已满，任务被丢弃: {}，{}", runnable.getClass().getName(), getPoolStatus());
            return false;
        }
        pool.execute(runnable);
        return true;
    }

    // 提交Callable获取结果（更合理的返回值设计）
    public static <T> Future<T> submit(Callable<T> callable) {
        if (isShutdown.get()) {
            log.error("线程池已关闭，无法提交任务");
            return null;
        }
        return pool.submit(callable);
    }

    // 提交Runnable（如需返回值可传入result）
    public static <T> Future<T> submit(Runnable runnable, T result) {
        if (isShutdown.get()) {
            log.error("线程池已关闭，无法提交任务");
            return null;
        }
        return pool.submit(runnable, result);
    }

    // 优雅关闭线程池
    public static void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            pool.shutdown();
            try {
                // 等待现有任务完成，超时后强制关闭
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
            }
            log.info("线程池已关闭");
        }
    }

    // 监控线程池状态
    public static String getPoolStatus() {
        return String.format(
                "池大小：%d，活跃线程数：%d，队列任务数：%d，已完成任务数：%d",
                pool.getPoolSize(),
                pool.getActiveCount(),
                pool.getQueue().size(),
                pool.getCompletedTaskCount()
        );
    }
}