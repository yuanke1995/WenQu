package com.wenqu.ai.schedule;

import com.wenqu.ai.config.ConfigDefaults;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.config.ConfigSchemaService;
import com.wenqu.ai.model.ScheduleRunLog;
import com.wenqu.ai.mapper.ScheduleRunLogMapper;
import com.wenqu.ai.service.ArtifactService;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.KeywordIndexService;
import com.wenqu.ai.service.RetrievalEvaluationService;
import com.wenqu.ai.service.SandboxService;
import com.wenqu.ai.service.WorkflowService;
import com.wenqu.ai.service.ScheduledJobService;
import com.wenqu.ai.service.DocumentService;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.service.UserImageService;
import com.wenqu.ai.thread.ThreadPoolManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
 * - 可观测：每个任务带一句话说明与间隔配置键，运行统计（上次结果/耗时/下次预期）留在内存，
 *   每次执行完成后落一行 {@code c_ai_schedule_run}（设置页「定时任务」面板查看），并支持手动触发一次；
 * - 放在 ApplicationReadyEvent：晚于 SchemaMigrator/所有 @PostConstruct，配置与表结构就绪。
 * 多副本：各副本独立调度，任务体需自身幂等（现有任务均满足）。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class ScheduleCenter {

    /** 调度节拍：触发精度上限（各任务间隔远大于此值，±10s 抖动可忽略） */
    private static final long TICK_MS = 10_000;
    /** 间隔下限：防误配打爆 */
    private static final long MIN_INTERVAL_MS = 60_000;
    /** 执行日志 error_msg 截断长度（与 c_ai_schedule_run.error_msg 列宽一致） */
    private static final int ERROR_MAX_LEN = 1000;

    private final List<PeriodicTask> tasks = new ArrayList<>();
    private final ConfigService configService;
    private final ConfigSchemaService configSchemaService;
    private final KeywordIndexService keywordIndexService;
    private final UserImageService userImageService;
    private final RetrievalEvaluationService evalService;
    private final SessionService sessionService;
    private final ArtifactService artifactService;
    private final ScheduledJobService scheduledJobService;
    private final SandboxService sandboxService;
    private final DocumentService documentService;
    private final WorkflowService workflowService;
    private final com.wenqu.ai.service.TraceService traceService;
    private final com.wenqu.ai.service.ParseQueueService parseQueueService;
    private final com.wenqu.ai.service.ChatUploadService chatUploadService;
    private final com.wenqu.ai.service.NotificationService notificationService;
    private final ScheduleRunLogMapper scheduleRunLogMapper;

    /** 仅负责计时（daemon，随 JVM 退出），任务体都在 ThreadPoolManager 里跑 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ai-schedule");
        t.setDaemon(true);
        return t;
    });

    public ScheduleCenter(ConfigService configService, ConfigSchemaService configSchemaService,
                          KeywordIndexService keywordIndexService,
                          UserImageService userImageService, RetrievalEvaluationService evalService,
                          SessionService sessionService, ArtifactService artifactService,
                          ScheduledJobService scheduledJobService,
                          SandboxService sandboxService,
                          DocumentService documentService,
                          WorkflowService workflowService,
                          com.wenqu.ai.service.TraceService traceService,
                          com.wenqu.ai.service.ParseQueueService parseQueueService,
                          com.wenqu.ai.service.ChatUploadService chatUploadService,
                          com.wenqu.ai.service.NotificationService notificationService,
                          ScheduleRunLogMapper scheduleRunLogMapper) {
        this.configService = configService;
        this.configSchemaService = configSchemaService;
        this.keywordIndexService = keywordIndexService;
        this.userImageService = userImageService;
        this.evalService = evalService;
        this.sessionService = sessionService;
        this.artifactService = artifactService;
        this.scheduledJobService = scheduledJobService;
        this.sandboxService = sandboxService;
        this.documentService = documentService;
        this.workflowService = workflowService;
        this.traceService = traceService;
        this.parseQueueService = parseQueueService;
        this.chatUploadService = chatUploadService;
        this.notificationService = notificationService;
        this.scheduleRunLogMapper = scheduleRunLogMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        // ==================== 周期任务注册处 ====================
        // register 参数：名称 / 一句话说明 / 间隔配置键（null=内置节拍；键必须是 config-schema.json
        // 里的 backendKey 才能在界面暂停）/ 间隔(ms)动态读取（≤0=暂停）/ 启动首轮 / 任务体

        // 关键词索引精确对账：按 (id, contentHash) 双向比对 MySQL 有效块与 Meilisearch 文档，定向修复漂移
        register("关键词索引精确对账", "按 (id, contentHash) 双向比对 MySQL 有效块与 Meilisearch 文档，定向修复索引漂移",
                "keyword.reconcileIntervalMs",
                () -> configService.getInt("keyword.reconcileIntervalMs", 3_600_000),
                () -> configService.getBoolean("keyword.reconcileOnStartup"),
                () -> keywordIndexService.reconcile());
        // 聊天图片目录清理：防止 data/images/chat 磁盘缓慢泄漏（保留期 images.chatRetentionMillis，默认 7 天）
        register("聊天图片目录清理", "删除超过保留期的聊天图片文件，防止磁盘缓慢泄漏（保留期 images.chatRetentionMillis）",
                "images.chatCleanupIntervalMs",
                () -> configService.getInt("images.chatCleanupIntervalMs", 86_400_000),
                () -> false,
                () -> userImageService.cleanupChatImages(configService.getLong("images.chatRetentionMillis", 7L * 24 * 3600 * 1000)));
        // 聊天附件超期清理：上传的附件换 fileId 落盘供问答读取，保留期过后删除
        //（间隔 chat.uploadCleanupIntervalMs，保留期 chat.uploadRetentionHours，≤0 = 不清理）
        register("聊天附件超期清理", "删除超过保留期的聊天附件文件（保留期 chat.uploadRetentionHours，≤0 = 不清理）",
                "chat.uploadCleanupIntervalMs",
                () -> configService.getInt("chat.uploadCleanupIntervalMs", 3_600_000),
                () -> false,
                () -> chatUploadService.cleanupExpired());

        // 检索质量自动体检：按线上参数跑评估集并与上期对比（间隔 eval.autoIntervalMs，默认每日；≤0 停用）。
        // 评估集为空自动跳过并记录（生成评估集后无需重启即生效）；下滑结论落在 eval.lastReport 供看板红绿灯
        register("检索评估自动体检", "按线上参数跑检索评估集并与上期对比，结论落 eval.lastReport 供看板红绿灯（评估集为空自动跳过）",
                "eval.autoIntervalMs",
                () -> configService.getInt("eval.autoIntervalMs", 86_400_000),
                () -> false,
                () -> evalService.runAutoCheck());
        // 配置缓存兜底刷新：Redis 订阅断线期间错过的变更由周期全量重读补齐
        register("配置缓存兜底刷新", "Redis 订阅断线期间错过的配置变更由周期全量重读补齐（内置 5 分钟节拍，不可暂停）",
                null,
                () -> 5 * 60 * 1000,
                () -> false,
                () -> configService.reload());
        // 过期会话/消息物理清理：硬删逻辑删除标记超保留期的会话与其消息（保留期即撤销窗口；间隔/保留期配置化）
        register("过期会话/消息清理", "硬删逻辑删除标记超保留期的会话与其消息（保留期 cleanup.sessionRetentionDays 即撤销窗口）",
                "cleanup.sessionCleanupIntervalMs",
                () -> configService.getInt("cleanup.sessionCleanupIntervalMs", 86_400_000),
                () -> false,
                () -> sessionService.purgeExpired(configService.getInt("cleanup.sessionRetentionDays", 30)));
        // 产物超期清理：删超期产物的文件与记录（保留期/间隔配置化；保留期 ≤0 = 不清理）。
        // 产物按用户归属持久化，不清理就会随使用无限增长——与聊天图片清理同一口径。
        register("产物超期清理", "删除超过保留期的产物文件与记录（保留期 artifact.retentionDays，≤0 = 不清理）",
                "artifact.cleanupIntervalMs",
                () -> configService.getInt("artifact.cleanupIntervalMs", 86_400_000),
                () -> false,
                () -> {
                    int days = configService.getInt("artifact.retentionDays", ConfigDefaults.ARTIFACT_RETENTION_DAYS);
                    int cleaned = artifactService.cleanupExpired(days);
                    if (cleaned > 0) {
                        log.info("[ARTIFACT] 超期产物已清理 {} 件（保留 {} 天）", cleaned, days);
                    }
                });

        // 定时执行智能体：扫描到期的用户任务并派发（间隔 scheduled.scanIntervalMs，默认 30s；≤0 暂停）。
        // 任务体只做"扫描 + 投递到线程池"，实际执行在别的池线程里跑，所以不会被几十秒的长任务拖住。
        register("定时智能体任务", "扫描到期的用户定时任务并派发执行（scheduled.enabled 总开关之下）",
                "scheduled.scanIntervalMs",
                () -> configService.getInt("scheduled.scanIntervalMs", 30_000),
                () -> false,
                () -> scheduledJobService.tick());

        // 文档解析队列扫描：把 c_ai_parse_task 里到期的任务抢占后投给 worker 池执行（间隔 parse.queue.scanIntervalMs，默认 5s；≤0 暂停）。
        // 上传/重解析只往这张表登记一行，解析全靠这里的扫描器推动——批量上传不会因为"队列内存溢出/满"丢任务。
        register("文档解析队列扫描", "把 c_ai_parse_task 里到期的解析任务抢占后投给 worker 池执行（上传/重解析全靠它推动）",
                "parse.queue.scanIntervalMs",
                () -> configService.getInt("parse.queue.scanIntervalMs", 5_000),
                () -> false,
                () -> parseQueueService.scan());

        // 网页源定时刷新：扫描到期且开启自动刷新的 url 文档，重新抓网+同名替换重建（间隔 web.refreshScanIntervalMs，默认 60s；≤0 暂停）。
        // 复用 importFromUrl 全套入库链路，next_refresh_at 推进保证单实例不重复触发；刷新失败 fail-loud 不中断其他文档。
        register("网页源定时刷新", "扫描到期且开启自动刷新的 url 文档，重新抓网+同名替换重建（web.refreshEnabled 总开关之下）",
                "web.refreshScanIntervalMs",
                () -> configService.getInt("web.refreshScanIntervalMs", 60_000),
                () -> false,
                () -> documentService.refreshDueWebSources());

        // 沙盒空闲回收：本工程沙盒 scope 挂在会话上（长生命周期），没有"run 结束释放"的时机，
        // 只能按空闲时长回收，否则用过沙盒的会话会永久占着一个容器。
        // 阈值 sandbox.idleReleaseMinutes（0=不回收）、间隔 sandbox.cleanupIntervalMs（≤0=暂停）。
        // provider 不可达/未配置 token 时 releaseIdle 内部按失败计数并摘除缓存条目，不会拖垮节拍线程。
        register("沙盒空闲回收", "回收空闲超过 sandbox.idleReleaseMinutes 的会话沙盒容器（0=不回收）",
                "sandbox.cleanupIntervalMs",
                () -> configService.getInt("sandbox.cleanupIntervalMs", ConfigDefaults.SANDBOX_CLEANUP_INTERVAL_MS),
                () -> false,
                () -> sandboxService.releaseIdle());

        // 工作流人工审核超时回收：挂起超过节点 timeoutSeconds 的 run 落 timeout 终态（审核是人在回路，无自动恢复语义）
        register("工作流审批超时回收", "把挂起超过节点 timeoutSeconds 的人工审核 run 落 timeout 终态（内置 60s 节拍，不可暂停）",
                null,
                () -> 60_000,
                () -> false,
                () -> workflowService.reapApprovalTimeouts());

        // 工作流运行记录清理：c_ai_workflow_run 按保留期物理删除（保留期 workflow.runLogRetentionDays，默认 30 天）。
        // dsl_snapshot + node_traces 是大字段，随运行次数无限膨胀——与任务执行日志清理同一口径；
        // 挂起审批的 run 由清理方法内部排除，不丢人工裁决现场。
        register("工作流运行记录清理", "物理删除超过保留期（workflow.runLogRetentionDays）的工作流运行记录",
                "workflow.runCleanupIntervalMs",
                () -> configService.getInt("workflow.runCleanupIntervalMs", 86_400_000),
                () -> false,
                () -> {
                    int days = Math.max(1, configService.getInt("workflow.runLogRetentionDays", 30));
                    int purged = workflowService.cleanupExpiredRuns(days);
                    if (purged > 0) {
                        log.info("[Schedule] 工作流运行记录已清理 {} 条（保留 {} 天）", purged, days);
                    }
                });

        // 工作流定时触发：扫描到期的启用定时工作流，派发已发布版本运行（间隔 workflow.scheduleScanIntervalMs，默认 30s；≤0 暂停）。
        // 与定时智能体任务同一范式：先推进 next_run_at 再异步派发，防同轮重复触发；只跑已发布版本，失败自动重试 1 次。
        register("工作流定时触发", "扫描到期的定时工作流并派发已发布版本运行（定时只跑已发布版本）",
                "workflow.scheduleScanIntervalMs",
                () -> configService.getInt("workflow.scheduleScanIntervalMs", 30_000),
                () -> false,
                () -> workflowService.tickSchedules());

        // P1 Trace 线上采样：差评必采 + 无引用/随机按配置数量入池（间隔 trace.samplingIntervalMs，默认每日；≤0 暂停）。
        // uk_qalog 唯一键兜底幂等，重复触发不产生重复样本
        register("Trace 线上采样", "线上对话按规则入采样池（差评必采 + 无引用/随机），供标注回流评测集",
                "trace.samplingIntervalMs",
                () -> configService.getInt("trace.samplingIntervalMs", 86_400_000),
                () -> false,
                () -> traceService.sampleDaily());

        // 任务执行日志清理：c_ai_schedule_run 按保留期物理删除（保留期 schedule.runLogRetentionDays，默认 7 天）。
        // 高频扫描任务（解析队列 5s 一轮）每天可产生上万行日志，不清理会无限累积。
        register("任务执行日志清理", "物理删除超过保留期（schedule.runLogRetentionDays）的定时任务执行日志",
                "schedule.runLogCleanupIntervalMs",
                () -> configService.getInt("schedule.runLogCleanupIntervalMs", 86_400_000),
                () -> false,
                () -> {
                    int days = Math.max(1, configService.getInt("schedule.runLogRetentionDays", 7));
                    int purged = purgeRunLogs(days);
                    if (purged > 0) {
                        log.info("[Schedule] 执行日志已清理 {} 行（保留 {} 天）", purged, days);
                    }
                });

        // 站内通知清理：c_ai_notification 按保留期物理删除（保留期 notification.retentionDays，默认 30 天）。
        // 通知指向的解析任务/运行记录本身也有保留期，通知活得比它们久没有回溯价值；不区分已读未读。
        register("站内通知清理", "物理删除超过保留期（notification.retentionDays）的站内通知",
                "notification.cleanupIntervalMs",
                () -> configService.getInt("notification.cleanupIntervalMs", 86_400_000),
                () -> false,
                () -> {
                    int days = Math.max(1, configService.getInt("notification.retentionDays", 30));
                    int purged = notificationService.cleanupExpired(days);
                    if (purged > 0) {
                        log.info("[Schedule] 站内通知已清理 {} 条（保留 {} 天）", purged, days);
                    }
                });

        long now = System.currentTimeMillis();
        for (PeriodicTask task : tasks) {
            if (task.runOnStartup.getAsBoolean()) {
                fire(task, "startup");
            } else {
                task.lastRunAt = now; // 未跑首轮：以启动时刻为锚点，等满一个间隔再触发
            }
        }
        // 节拍循环必须整体 try-catch：ScheduledExecutorService 的任务抛异常会静默取消后续调度
        scheduler.scheduleWithFixedDelay(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        log.info("[Schedule] 定时任务调度已启动（节拍 {}s）：{}", TICK_MS / 1000,
                tasks.stream().map(t -> t.name).collect(Collectors.joining("、")));
    }

    private void register(String name, String desc, String configKey,
                          IntSupplier intervalMs, BooleanSupplier runOnStartup, Runnable body) {
        tasks.add(new PeriodicTask(name, desc, configKey, intervalMs, runOnStartup, body));
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
                if (now - task.lastRunAt >= Math.max(interval, MIN_INTERVAL_MS)) fire(task, "auto");
            }
        } catch (Exception e) {
            log.warn("[Schedule] 调度节拍异常（忽略继续）: {}", e.getMessage());
        }
    }

    /** 周期/启动首轮触发：只登记锚点与触发方式，任务体投线程池（防重叠 CAS 在任务体内做） */
    private void fire(PeriodicTask task, String trigger) {
        task.lastRunAt = System.currentTimeMillis();
        task.lastTrigger = trigger;
        ThreadPoolManager.execute(() -> {
            // 防重叠 CAS 放在任务体内：提交被拒绝丢弃（队列满）不影响下轮重试
            if (!task.running.compareAndSet(false, true)) return;
            runBody(task);
        });
    }

    /**
     * 手动触发一次：复用同一防重叠 CAS 与执行管线（trigger=manual 落日志）。
     * 暂停中（间隔 ≤0）也允许手动触发——手动触发的意义就是不等到点。
     *
     * @return accepted=false 表示上一轮还在跑，本轮拒绝（不排队）
     */
    public Map<String, Object> triggerNow(String name) {
        PeriodicTask task = tasks.stream().filter(t -> t.name.equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知定时任务: " + name));
        if (!task.running.compareAndSet(false, true)) {
            return Map.of("accepted", false, "reason", "该任务上一轮还在执行中，已拒绝重复触发");
        }
        task.lastRunAt = System.currentTimeMillis();
        task.lastTrigger = "manual";
        ThreadPoolManager.execute(() -> runBody(task));
        return Map.of("accepted", true);
    }

    /** 任务体执行 + 运行统计 + 执行日志落库（调用方已持有 running=true） */
    private void runBody(PeriodicTask task) {
        long startMs = System.currentTimeMillis();
        long startNano = System.nanoTime();
        boolean success;
        String error = null;
        try {
            log.debug("[Schedule] 定时任务触发: {}", task.name);
            task.body.run();
            success = true;
        } catch (Exception e) {
            success = false;
            error = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("[Schedule] 定时任务「{}」执行失败（下轮重试）: {}", task.name, error);
        } finally {
            task.running.set(false);
        }
        long durationMs = (System.nanoTime() - startNano) / 1_000_000;
        // ---- 内存统计（同任务防重叠，单写者；计数器用 AtomicLong 防跨界竞争）----
        task.lastFinishedAt = System.currentTimeMillis();
        task.lastSuccess = success;
        task.lastDurationMs = durationMs;
        task.lastError = error;
        (success ? task.successCount : task.failCount).incrementAndGet();
        // ---- 执行日志落库（只落完成态，一次 insert；失败不影响任务本身，仅告警）----
        insertRunLog(task.name, task.lastTrigger, success, error, durationMs, startMs);
    }

    private void insertRunLog(String taskName, String trigger, boolean success, String error, long durationMs, long startMs) {
        try {
            ScheduleRunLog row = new ScheduleRunLog();
            row.setId(UUID.randomUUID().toString());
            row.setTaskName(taskName);
            row.setTriggerType(trigger);
            row.setSuccess(success ? 1 : 0);
            row.setErrorMsg(error == null ? null
                    : (error.length() <= ERROR_MAX_LEN ? error : error.substring(0, ERROR_MAX_LEN)));
            row.setDurationMs(durationMs);
            row.setStartedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(startMs), ZoneId.systemDefault()));
            row.setFinishedAt(LocalDateTime.now());
            scheduleRunLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("[Schedule] 执行日志落库失败（不影响任务）: task={} err={}", taskName, e.getMessage());
        }
    }

    /** 执行日志超期物理清理（由「任务执行日志清理」任务周期调用；独立方法便于单测） */
    int purgeRunLogs(int retentionDays) {
        LocalDateTime before = LocalDateTime.now().minusDays(retentionDays);
        return scheduleRunLogMapper.delete(new LambdaQueryWrapper<ScheduleRunLog>()
                .lt(ScheduleRunLog::getStartedAt, before));
    }

    /**
     * 全量任务快照（设置页「定时任务」面板数据源）：注册元数据 + 实时间隔 + 内存运行统计。
     * 时间均为 epoch 毫秒（前端格式化）；从未跑完的任务 lastFinishedAt=null。
     */
    public List<Map<String, Object>> snapshot() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> out = new ArrayList<>(tasks.size());
        for (PeriodicTask t : tasks) {
            long interval = t.intervalMs.getAsInt();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name);
            m.put("desc", t.desc);
            m.put("configKey", t.configKey);
            // 键必须在 config-schema.json 才可经设置页写值（暂停/恢复=改间隔配置，非编辑键只展示）
            m.put("editable", t.configKey != null && configSchemaService.isEditable(t.configKey));
            m.put("intervalMs", interval);
            m.put("paused", interval <= 0);
            m.put("running", t.running.get());
            m.put("lastTrigger", t.lastTrigger);
            m.put("lastRunAt", t.lastRunAt);
            m.put("lastFinishedAt", t.lastFinishedAt);
            m.put("lastSuccess", t.lastSuccess);
            m.put("lastDurationMs", t.lastDurationMs);
            m.put("lastError", t.lastError);
            m.put("successCount", t.successCount.get());
            m.put("failCount", t.failCount.get());
            m.put("nextDueAt", interval <= 0 ? null : t.lastRunAt + Math.max(interval, MIN_INTERVAL_MS));
            m.put("now", now);
            out.add(m);
        }
        return out;
    }

    /** 一个周期任务：间隔/启动首轮均为动态读取（每次触发前取值） */
    private static final class PeriodicTask {
        final String name;
        final String desc;
        /** 间隔配置键（null=内置节拍）；暂停/恢复=改这个键的值（0=暂停），键须在 config-schema.json */
        final String configKey;
        final IntSupplier intervalMs;
        final BooleanSupplier runOnStartup;
        final Runnable body;
        final AtomicBoolean running = new AtomicBoolean(false);
        final AtomicLong successCount = new AtomicLong();
        final AtomicLong failCount = new AtomicLong();
        volatile long lastRunAt;
        volatile long lastFinishedAt;
        volatile String lastTrigger = "";
        volatile Boolean lastSuccess;
        volatile long lastDurationMs;
        volatile String lastError;

        PeriodicTask(String name, String desc, String configKey,
                     IntSupplier intervalMs, BooleanSupplier runOnStartup, Runnable body) {
            this.name = name;
            this.desc = desc;
            this.configKey = configKey;
            this.intervalMs = intervalMs;
            this.runOnStartup = runOnStartup;
            this.body = body;
        }
    }
}
