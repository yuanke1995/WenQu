package com.wisesoft.ai.schedule;

import com.wisesoft.ai.service.ArtifactService;
import com.wisesoft.ai.service.ConfigService;
import com.wisesoft.ai.service.KeywordIndexService;
import com.wisesoft.ai.service.RetrievalEvaluationService;
import com.wisesoft.ai.service.SessionService;
import com.wisesoft.ai.service.UserImageService;
import com.wisesoft.ai.thread.ThreadPoolManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

/**
 * 定时任务调度中心（项目所有周期任务的统一注册与触发入口，新增定时任务在本类 start() 里加一行）。
 * <p>
 * - 调度：单 daemon 调度线程按固定节拍（10s）检查各任务"距上次运行是否已达间隔"——间隔每次实时读配置，
 *   改配置（含 ≤0 暂停）即时生效无需重启，精度为一个节拍；
 * - 执行：任务体统一提交 ThreadPoolManager 线程池，慢任务不占用调度线程；
 *   上一轮未结束则本轮跳过（防重叠），失败只告警下轮重试；
 * - 放在 ApplicationReadyEvent：晚于 SchemaMigrator/所有 @PostConstruct，配置与表结构就绪。
 * 多副本：各副本独立调度，任务体需自身幂等（现有任务均满足）。
 */
@Slf4j
@Component
public class ScheduleCenter {

    /** 调度节拍：触发精度上限（各任务间隔远大于此值，±10s 抖动可忽略） */
    private static final long TICK_MS = 10_000;
    /** 间隔下限：防误配打爆 */
    private static final long MIN_INTERVAL_MS = 60_000;

    private final List<PeriodicTask> tasks = new ArrayList<>();
    private final ConfigService configService;
    private final KeywordIndexService keywordIndexService;
    private final UserImageService userImageService;
    private final RetrievalEvaluationService evalService;
    private final SessionService sessionService;
    private final ArtifactService artifactService;

    /** 仅负责计时（daemon，随 JVM 退出），任务体都在 ThreadPoolManager 里跑 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ai-schedule");
        t.setDaemon(true);
        return t;
    });

    public ScheduleCenter(ConfigService configService, KeywordIndexService keywordIndexService,
                          UserImageService userImageService, RetrievalEvaluationService evalService,
                          SessionService sessionService, ArtifactService artifactService) {
        this.configService = configService;
        this.keywordIndexService = keywordIndexService;
        this.userImageService = userImageService;
        this.evalService = evalService;
        this.sessionService = sessionService;
        this.artifactService = artifactService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        // ==================== 周期任务注册处 ====================
        // 关键词索引精确对账：按 (id, contentHash) 双向比对 MySQL 有效块与 Meilisearch 文档，定向修复漂移
        register("关键词索引精确对账",
                () -> configService.getInt("keyword.reconcileIntervalMs", 3_600_000),
                () -> configService.getBoolean("keyword.reconcileOnStartup"),
                () -> keywordIndexService.reconcile());
        // 聊天图片目录清理：防止 data/images/chat 磁盘缓慢泄漏（保留期 images.chatRetentionMillis，默认 7 天）
        register("聊天图片目录清理",
                () -> configService.getInt("images.chatCleanupIntervalMs", 86_400_000),
                () -> false,
                () -> userImageService.cleanupChatImages(configService.getLong("images.chatRetentionMillis", 7L * 24 * 3600 * 1000)));
        // 检索质量自动体检：按线上参数跑评估集并与上期对比（间隔 eval.autoIntervalMs，默认每日；≤0 停用）。
        // 评估集为空自动跳过并记录（生成评估集后无需重启即生效）；下滑结论落在 eval.lastReport 供看板红绿灯
        register("检索评估自动体检",
                () -> configService.getInt("eval.autoIntervalMs", 86_400_000),
                () -> false,
                () -> evalService.runAutoCheck());
        // 配置缓存兜底刷新：Redis 订阅断线期间错过的变更由周期全量重读补齐
        register("配置缓存兜底刷新",
                () -> 5 * 60 * 1000,
                () -> false,
                () -> configService.reload());
        // 过期会话/消息物理清理：硬删逻辑删除标记超保留期的会话与其消息（保留期即撤销窗口；间隔/保留期配置化）
        register("过期会话/消息清理",
                () -> configService.getInt("cleanup.sessionCleanupIntervalMs", 86_400_000),
                () -> false,
                () -> sessionService.purgeExpired(configService.getInt("cleanup.sessionRetentionDays", 30)));
        // 产物超期清理：删超期产物的文件与记录（保留期/间隔配置化；保留期 ≤0 = 不清理）。
        // 产物按用户归属持久化，不清理就会随使用无限增长——与聊天图片清理同一口径。
        register("产物超期清理",
                () -> configService.getInt("artifact.cleanupIntervalMs", 86_400_000),
                () -> false,
                () -> {
                    int days = configService.getInt("artifact.retentionDays", 90);
                    int cleaned = artifactService.cleanupExpired(days);
                    if (cleaned > 0) {
                        log.info("[ARTIFACT] 超期产物已清理 {} 件（保留 {} 天）", cleaned, days);
                    }
                });

        long now = System.currentTimeMillis();
        for (PeriodicTask task : tasks) {
            if (task.runOnStartup.getAsBoolean()) {
                fire(task);
            } else {
                task.lastRunAt = now; // 未跑首轮：以启动时刻为锚点，等满一个间隔再触发
            }
        }
        // 节拍循环必须整体 try-catch：ScheduledExecutorService 的任务抛异常会静默取消后续调度
        scheduler.scheduleWithFixedDelay(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        log.info("[Schedule] 定时任务调度已启动（节拍 {}s）：{}", TICK_MS / 1000,
                tasks.stream().map(t -> t.name).collect(Collectors.joining("、")));
    }

    private void register(String name, IntSupplier intervalMs, BooleanSupplier runOnStartup, Runnable body) {
        tasks.add(new PeriodicTask(name, intervalMs, runOnStartup, body));
    }

    /** 停机：先停节拍调度（不再触发新任务），池内在跑任务由 ThreadPoolManager 优雅停机收尾 */
    @jakarta.annotation.PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private void tick() {
        try {
            long now = System.currentTimeMillis();
            for (PeriodicTask task : tasks) {
                long interval = task.intervalMs.getAsInt();
                if (interval <= 0) continue; // ≤0=暂停（改回正值下个节拍自动恢复）
                if (now - task.lastRunAt >= Math.max(interval, MIN_INTERVAL_MS)) fire(task);
            }
        } catch (Exception e) {
            log.warn("[Schedule] 调度节拍异常（忽略继续）: {}", e.getMessage());
        }
    }

    private void fire(PeriodicTask task) {
        task.lastRunAt = System.currentTimeMillis();
        ThreadPoolManager.execute(() -> {
            // 防重叠 CAS 放在任务体内：提交被拒绝丢弃（队列满）不影响下轮重试
            if (!task.running.compareAndSet(false, true)) return;
            try {
                log.debug("[Schedule] 定时任务触发: {}", task.name);
                task.body.run();
            } catch (Exception e) {
                log.warn("[Schedule] 定时任务「{}」执行失败（下轮重试）: {}", task.name, e.getMessage());
            } finally {
                task.running.set(false);
            }
        });
    }

    /** 一个周期任务：间隔/启动首轮均为动态读取（每次触发前取值） */
    private static final class PeriodicTask {
        final String name;
        final IntSupplier intervalMs;
        final BooleanSupplier runOnStartup;
        final Runnable body;
        final AtomicBoolean running = new AtomicBoolean(false);
        volatile long lastRunAt;

        PeriodicTask(String name, IntSupplier intervalMs, BooleanSupplier runOnStartup, Runnable body) {
            this.name = name;
            this.intervalMs = intervalMs;
            this.runOnStartup = runOnStartup;
            this.body = body;
        }
    }
}
