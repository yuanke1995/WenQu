package com.wenqu.ai.agent.goal;

import com.wenqu.ai.model.Goal;
import com.wenqu.ai.service.GoalService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 目标调度器：任务运行时的「心跳」。
 *
 * <p>它只做四件事，按固定节拍：
 * <ol>
 *   <li>续租自己持有的目标（进程活着 = 租约活着）；</li>
 *   <li>回收过期租约（崩溃/换副本后对账并恢复）；</li>
 *   <li>抢一批到点的目标（DB 条件 UPDATE，affected=1 才算抢到）；</li>
 *   <li>把抢到的目标交给 worker 线程起一轮，然后立刻返回，不阻塞节拍。</li>
 * </ol>
 *
 * <p>为什么不用 {@code @Scheduled}：本仓没有开 Spring 的调度器，而且目标调度需要独立的
 * daemon 线程与可配置节拍（设置页可暂停/调速），用一条自己的 ticker 与
 * {@code ScheduleCenter} 的既有形态一致，不额外引入全局调度配置。
 *
 * <p>为什么 worker 不跟到轮次结束：一轮可能跑几分钟，调度线程只负责「起跑」；跟随与收尾
 * 在引擎的跟随循环里，收尾写 {@code onRoundSettled}，再由下一跳调度接上。这样进程崩溃时
 * 租约过期即可被另一个实例接管，而不是把整条链挂在原线程上。
 *
 * @author yuanke
 */
@Component
public class GoalScheduler {

    private static final Logger log = LoggerFactory.getLogger(GoalScheduler.class);

    private final GoalService goals;
    private final GoalSettings settings;

    /** 实例标识：写进租约，恢复时据此判断「是不是自己」。 */
    private final String owner = buildOwner();

    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "goal-scheduler");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService workers;

    private final AtomicBoolean started = new AtomicBoolean(false);

    public GoalScheduler(GoalService goals, GoalSettings settings) {
        this.goals = goals;
        this.settings = settings;
        this.workers = Executors.newFixedThreadPool(settings.workers(), r -> {
            Thread t = new Thread(r, "goal-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!started.compareAndSet(false, true)) return;
        long tickMs = settings.tickMs();
        ticker.scheduleWithFixedDelay(this::tick, settings.initialTickDelayMs(), tickMs, TimeUnit.MILLISECONDS);
        log.info("[GOAL] 调度器已启动：owner={} tick={}ms workers={} claimBatch={}",
                owner, tickMs, settings.workers(), settings.claimBatch());
    }

    /** 一跳：续租 → 回收 → 抢单 → 交给 worker。整体 try-catch，异常不能取消后续节拍。 */
    void tick() {
        try {
            goals.renewLeases(owner);
            goals.recoverExpiredLeases(owner);
            List<Goal> claimed = goals.claimReady(owner, settings.claimBatch());
            for (Goal goal : claimed) {
                workers.execute(() -> {
                    try {
                        goals.runClaimed(goal, owner);
                    } catch (Exception e) {
                        log.warn("[GOAL] worker 起轮异常 goal={}: {}", goal.getId(), e.toString());
                    }
                });
            }
        } catch (Throwable e) {
            log.warn("[GOAL] 调度节拍异常（下一跳继续）: {}", e.toString());
        }
    }

    @PreDestroy
    public void stop() {
        started.set(false);
        ticker.shutdownNow();
        workers.shutdownNow();
    }

    private static String buildOwner() {
        String host = "unknown";
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            // 容器里拿不到 hostname 就退回 pid+随机串
        }
        String pid = ManagementFactory.getRuntimeMXBean().getName();
        String raw = host + "-" + pid + "-" + UUID.randomUUID().toString().substring(0, 8);
        return raw.length() <= 64 ? raw : raw.substring(0, 64);
    }
}
