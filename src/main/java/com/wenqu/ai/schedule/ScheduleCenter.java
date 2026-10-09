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
import java.util.Set;
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
 * - 可观测：每个任务带一句话说明与间隔配置键，运行统计（上次结果/耗时/下次预期）留在内存，并支持手动触发一次。
 *   执行日志（{@code c_ai_schedule_run}，设置页「定时任务」面板查看）只记有信息量的执行——失败 /
 *   手动触发 / 有实质产出；高频扫描任务空跑不落行（见 {@link #registerWork}），防心跳把日志淹掉；
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
    /**
     * 间隔下限：与调度节拍同值（{@link #TICK_MS}），即"一个节拍"。
     * <p>此前取 60s（意图是防误配打爆），但它把秒级扫描任务的配置静默抬到了一分钟：
     * {@code parse.queue.scanIntervalMs} 默认 5s、工作流/定时智能体扫描 30s，实际都按 60s 跑，
     * 而设置页「间隔」列照实显示配置值（5 秒）——界面与行为不符，且无人察觉。
     * <p>节拍本身已经是天然且足够的打爆保护：调度线程 10s 才醒一次，
     * 任何任务都不可能跑得比一个节拍更密，所以下限对齐节拍即可，不需要额外抬到分钟级。
     * 该常量同时用于 {@link #tick()} 的触发判定与 {@link #snapshot()} 的下次预计，
     * 两处共用同一个值 ⇒ 界面显示的间隔与实际触发周期永远一致。
     */
    private static final long MIN_INTERVAL_MS = TICK_MS;
    /** 执行日志 error_msg 截断长度（与 c_ai_schedule_run.error_msg 列宽一致） */
    private static final int ERROR_MAX_LEN = 1000;
    /**
     * 任务体返回值哨兵：&lt;0 表示无「处理量」语义，每轮完成恒落执行日志
     * （register 的 Runnable 任务体统一包装为该值；registerWork 返回负数同样按恒落处理，防误算漏记）。
     */
    private static final int ALWAYS_LOG = -1;

    private final List<PeriodicTask> tasks = new ArrayList<>();
    private final ConfigService configService;
    private final ConfigSchemaService configSchemaService;
    private final KeywordIndexService keywordIndexService;
    private final UserImageService userImageService;
    private final RetrievalEvaluationService evalService;
    private final SessionService sessionService;
    private final ArtifactService artifactService;
    /** 会话事件账本的超期清理（挂在「过期会话/消息清理」同一周期上） */
    private final com.wenqu.ai.service.SessionEventService sessionEventService;
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
                          com.wenqu.ai.service.SessionEventService sessionEventService,
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
        this.sessionEventService = sessionEventService;
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
        // register：任务体为 Runnable，每轮完成必落一行执行日志（低频任务——每次跑都有信息量）；
        // registerWork：任务体返回本轮处理量（int），成功且处理量=0 且非手动触发时不落行
        //   （高频扫描任务——空跑占其日志九成以上）；失败/手动触发/有产出照常落行。两者运行统计一致。
        // 其余参数：名称 / 一句话说明 / 间隔配置键（null=内置节拍；键必须是 config-schema.json
        // 里的 backendKey 才能在界面暂停）/ 间隔(ms)动态读取（≤0=暂停）/ 启动首轮。

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
        // （间隔 config.reloadIntervalMs，默认 5 分钟；≤0 = 暂停。暂停后 Redis 一旦断线，
        //  其他实例收不到广播，配置变更会静默保持旧值直到重启——这是它可暂停的唯一代价）
        register("配置缓存兜底刷新", "Redis 订阅断线期间错过的配置变更由周期全量重读补齐（暂停后断线期间的变更会丢失直到重启）",
                "config.reloadIntervalMs",
                () -> configService.getInt("config.reloadIntervalMs", 5 * 60 * 1000),
                () -> false,
                () -> configService.reload(),
                "暂停后若 Redis 订阅断线，其他实例收不到配置变更广播，期间改过的配置会静默保持旧值直到重启。正常情况下订阅即时生效、暂停无感知，仅在订阅异常时暴露。");
        // 过期数据清理：① 硬删已软删且超保留期的会话/消息/已停用分享（保留期即撤销窗口）；
        // ② 回收闲置超期的**访客**会话——分享页与 MCP 端点产生的会话没有主人会去删，不回收就只增不减；
        // ③ 超期的会话事件账本（一轮十几条，比会话表长得更快，同样只增不减）
        register("过期会话/消息清理", "硬删已软删且超保留期的会话/消息/已停用分享；回收闲置超期的访客会话；清理超期会话事件账本",
                "cleanup.sessionCleanupIntervalMs",
                () -> configService.getInt("cleanup.sessionCleanupIntervalMs", 86_400_000),
                () -> false,
                () -> {
                    sessionService.purgeExpired(configService.getInt("cleanup.sessionRetentionDays", 30));
                    sessionService.purgeStaleVisitorSessions(
                            configService.getInt("cleanup.visitorIdleDays", ConfigDefaults.VISITOR_SESSION_IDLE_DAYS));
                    sessionEventService.purgeExpired(configService.getInt("cleanup.sessionEventRetentionDays", 30));
                });
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
        // 返回本轮派发数——空跑（无到期任务/总开关关闭）不落执行日志。
        registerWork("定时智能体任务", "扫描到期的用户定时任务并派发执行（scheduled.enabled 总开关之下）",
                "scheduled.scanIntervalMs",
                () -> configService.getInt("scheduled.scanIntervalMs", 30_000),
                () -> false,
                () -> scheduledJobService.tick());

        // 文档解析队列扫描：把 c_ai_parse_task 里到期的任务抢占后投给 worker 池执行（间隔 parse.queue.scanIntervalMs，默认 5s；≤0 暂停）。
        // 上传/重解析只往这张表登记一行，解析全靠这里的扫描器推动——批量上传不会因为"队列内存溢出/满"丢任务。
        // 返回本轮抢占数：空跑（无到期任务）不落执行日志；扫描失败上抛落失败行（scan 内部不再吞异常）。
        registerWork("文档解析队列扫描", "把 c_ai_parse_task 里到期的解析任务抢占后投给 worker 池执行（上传/重解析全靠它推动）",
                "parse.queue.scanIntervalMs",
                () -> configService.getInt("parse.queue.scanIntervalMs", 5_000),
                () -> false,
                () -> parseQueueService.scan());

        // 网页源定时刷新：扫描到期且开启自动刷新的 url 文档，重新抓网+同名替换重建（间隔 web.refreshScanIntervalMs，默认 60s；≤0 暂停）。
        // 复用 importFromUrl 全套入库链路，next_refresh_at 推进保证单实例不重复触发；刷新失败 fail-loud 不中断其他文档。
        // 返回本轮处理数（含刷失败的文档，逐文档失败不使任务行变红）：空跑（无到期文档）不落执行日志。
        registerWork("网页源定时刷新", "扫描到期且开启自动刷新的 url 文档，重新抓网+同名替换重建（web.refreshEnabled 总开关之下）",
                "web.refreshScanIntervalMs",
                () -> configService.getInt("web.refreshScanIntervalMs", 60_000),
                () -> false,
                () -> documentService.refreshDueWebSources());

        // 沙盒空闲回收：本工程沙盒 scope 挂在会话上（长生命周期），没有"run 结束释放"的时机，
        // 只能按空闲时长回收，否则用过沙盒的会话会永久占着一个容器。
        // 阈值 sandbox.idleReleaseMinutes（0=不回收）、间隔 sandbox.cleanupIntervalMs（≤0=暂停）。
        // provider 不可达/未配置 token 时 releaseIdle 内部按失败计数并摘除缓存条目，不会拖垮节拍线程。
        // 返回实际回收数：无事可收不落执行日志。
        registerWork("沙盒空闲回收", "回收空闲超过 sandbox.idleReleaseMinutes 的会话沙盒容器（0=不回收）",
                "sandbox.cleanupIntervalMs",
                () -> configService.getInt("sandbox.cleanupIntervalMs", ConfigDefaults.SANDBOX_CLEANUP_INTERVAL_MS),
                () -> false,
                () -> sandboxService.releaseIdle());

        // 工作流人工审核超时回收：挂起超过节点 timeoutSeconds 的 run落 timeout 终态（审核是人在回路，无自动恢复语义）
        // 间隔 workflow.approvalReapIntervalMs，默认 60s；≤0 = 暂停。
        // ⚠ 暂停代价：挂起不批的 run 永远停在 waiting_approval——不占执行线程（挂起时线程已释放），
        //   但运行记录不落终态、审批卡片持续等待，且清理任务显式排除该状态（不会被保留期回收）
        // 返回回收数：无超时挂起不落执行日志。
        registerWork("工作流审批超时回收", "把挂起超过节点 timeoutSeconds 的人工审核 run 落 timeout 终态（⚠ 暂停后挂起的 run 将一直停在「待审核」不收口）",
                "workflow.approvalReapIntervalMs",
                () -> configService.getInt("workflow.approvalReapIntervalMs", 60_000),
                () -> false,
                () -> workflowService.reapApprovalTimeouts(),
                "挂起不审批的运行将永远停在「待审核」：不占用执行线程（挂起时线程已释放），但运行记录不落终态、审批卡片持续等待，且记录清理任务显式排除该状态（不会被保留期回收）。仅在确实需要超长人工审批窗口时才暂停。");

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
        // 返回本轮派发数：空跑（无到期工作流）不落执行日志。
        registerWork("工作流定时触发", "扫描到期的定时工作流并派发已发布版本运行（定时只跑已发布版本）",
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
        // 空跑不记已收敛高频任务的写入量，但失败与有效执行仍会长期累积，不清理会无限增长。
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
        verifyTaskParams();
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
        register(name, desc, configKey, intervalMs, runOnStartup, body, null);
    }

    /**
     * 注册「空跑不记」型周期任务（高频扫描类，如解析队列 10s 级扫描）：任务体返回本轮处理量，
     * 成功且处理量为 0 且非手动触发时不落执行日志——这类任务空跑占日志九成以上，会把失败与
     * 有效执行淹掉；失败、手动触发、有处理量照常落行。运行统计（内存）与 {@link #register} 一致。
     * <p>任务体返回 0 = 本轮空跑（免记）；返回 &gt;0 = 处理量；返回负数同样恒落行（见 {@link #ALWAYS_LOG}）。
     */
    private void registerWork(String name, String desc, String configKey,
                              IntSupplier intervalMs, BooleanSupplier runOnStartup, IntSupplier body) {
        registerWork(name, desc, configKey, intervalMs, runOnStartup, body, null);
    }

    /**
     * 注册周期任务，{@code pauseRisk} 为暂停该任务的代价说明（null=无特别代价）。
     * <p>暂停本身只是「间隔写 0」，没有技术阻力；但个别任务停掉后会留下不易察觉的副作用
     * （如挂起的工作流永不落终态、Redis 断线期间配置变更静默丢失）。这类任务的注册处显式给出
     * 一句话代价，经 {@link #snapshot()} 下发给设置页，在暂停确认弹窗里展示——避免管理员
     * 在不知情的情况下关掉自愈机制。
     * <p>该任务的专属可调参数不在这里传，而是按任务名从 {@link #TASK_PARAMS} 取——
     * 17 处注册点各写一遍参数清单，必然出现「改了 A 任务漏改 B 任务」的漂移；
     * 集中在一张表里，且 {@link #start()} 启动时校验表与注册任务名一一对应。
     */
    private void register(String name, String desc, String configKey,
                          IntSupplier intervalMs, BooleanSupplier runOnStartup, Runnable body,
                          String pauseRisk) {
        IntSupplier alwaysLog = () -> {
            body.run();
            return ALWAYS_LOG;
        };
        tasks.add(new PeriodicTask(name, desc, configKey, intervalMs, runOnStartup, alwaysLog, pauseRisk,
                TASK_PARAMS.getOrDefault(name, List.of())));
    }

    /** {@link #registerWork} 的带暂停代价版本（pauseRisk 语义见同名参数处） */
    private void registerWork(String name, String desc, String configKey,
                              IntSupplier intervalMs, BooleanSupplier runOnStartup, IntSupplier body,
                              String pauseRisk) {
        tasks.add(new PeriodicTask(name, desc, configKey, intervalMs, runOnStartup, body, pauseRisk,
                TASK_PARAMS.getOrDefault(name, List.of())));
    }

    /**
     * 每个周期任务的「专属可调参数」：只登记**该任务自身行为**的旋钮（保留期、并发、阈值、总量上限等），
     * 间隔键不在其中（单独成列）。设置页据此把参数按任务归组——原先所有参数平铺在一张长表单里，
     * 管理员无法判断某个旋钮该在哪调。
     * <p>登记纪律：
     * <ul>
     *   <li>只写**确实影响本任务**的键，且必须是 {@code config-schema.json} 里可编辑的键
     *       （{@link ConfigSchemaService#isEditable}，快照里会过滤掉不可编辑的）；
     *   <li>不写任务体顺带读到、但属于别的子系统通用配置的键（如关键词引擎连接、问答链路参数）——
     *       那些归它们自己的面板，写进来会让"这个旋钮管谁"变模糊；
     *   <li>一个键可被多个任务登记（如 notification.dedupWindow），前端按任务分组展示，不影响原表单。
     * </ul>
     * 键名写错不会有任何症状（快照里被 isEditable 滤掉＝界面少一项），故 {@link #start()} 启动时
     * 对照 schema 逐个校验并告警——避免"配了但界面不显示"这类静默失效。
     */
    private static final Map<String, List<String>> TASK_PARAMS = Map.ofEntries(
            Map.entry("关键词索引精确对账", List.of("keyword.reconcileOnStartup")),
            Map.entry("聊天图片目录清理", List.of("images.chatRetentionMillis")),
            Map.entry("聊天附件超期清理", List.of("chat.uploadRetentionHours")),
            Map.entry("检索评估自动体检", List.of("eval.judgeEnabled", "eval.judgeModel", "eval.autoThresholdPct")),
            Map.entry("过期会话/消息清理", List.of("cleanup.sessionRetentionDays", "cleanup.visitorIdleDays")),
            Map.entry("产物超期清理", List.of("artifact.retentionDays")),
            Map.entry("定时智能体任务", List.of("scheduled.enabled", "scheduled.maxPerUser", "scheduled.timeoutMs")),
            Map.entry("文档解析队列扫描", List.of("parse.queue.capacity", "parse.concurrency",
                    "parse.taskLeaseSeconds", "parse.taskTimeoutMs", "parse.embedConcurrency", "parse.ocrGateConcurrency")),
            Map.entry("网页源定时刷新", List.of("web.refreshEnabled")),
            Map.entry("沙盒空闲回收", List.of("sandbox.idleReleaseMinutes")),
            Map.entry("工作流运行记录清理", List.of("workflow.runLogRetentionDays")),
            Map.entry("工作流定时触发", List.of("workflow.maxSteps", "workflow.runTimeoutSeconds",
                    "workflow.subagentTimeoutMs", "workflow.maxConcurrentRuns", "workflow.runQueueCapacity")),
            Map.entry("Trace 线上采样", List.of("trace.sampleRandomDaily", "trace.sampleNoHitDaily")),
            Map.entry("任务执行日志清理", List.of("schedule.runLogRetentionDays")),
            Map.entry("站内通知清理", List.of("notification.retentionDays", "notification.dedupWindow"))
    );

    /** 停机：先停节拍调度（不再触发新任务），池内在跑任务由 ThreadPoolManager 优雅停机收尾 */
    @jakarta.annotation.PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * 启动自检：{@link #TASK_PARAMS} 的表名必须与实际注册的任务名一一对应，且其中每个键
     * 都必须在 config-schema.json 里可编辑。两类写错（任务改名 / 键名拼错）都不会抛异常，
     * 只表现为设置页少显示一个旋钮——属于最难自查的静默失效，故启动即告警。
     */
    private void verifyTaskParams() {
        Set<String> registered = tasks.stream().map(t -> t.name).collect(Collectors.toSet());
        for (String name : TASK_PARAMS.keySet()) {
            if (!registered.contains(name)) {
                log.warn("[Schedule] TASK_PARAMS 里的任务名「{}」没有对应的注册任务（任务可能已改名）", name);
            }
        }
        for (PeriodicTask t : tasks) {
            if (!TASK_PARAMS.containsKey(t.name) && t.configKey != null) {
                log.debug("[Schedule] 任务「{}」未登记专属参数（只有间隔可调）", t.name);
            }
        }
        for (Map.Entry<String, List<String>> e : TASK_PARAMS.entrySet()) {
            for (String key : e.getValue()) {
                if (!configSchemaService.isEditable(key)) {
                    log.warn("[Schedule] TASK_PARAMS 中「{}」的键 {} 在 config-schema.json 不可编辑，界面不会显示",
                            e.getKey(), key);
                }
            }
        }
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

    /** 任务体执行 + 运行统计 + 按策略落执行日志（空跑不记；调用方已持有 running=true） */
    private void runBody(PeriodicTask task) {
        long startMs = System.currentTimeMillis();
        long startNano = System.nanoTime();
        boolean success;
        String error = null;
        int processed = ALWAYS_LOG;   // 任务体未及返回（抛异常）按恒落处理，失败必可见
        try {
            log.debug("[Schedule] 定时任务触发: {}", task.name);
            processed = task.body.getAsInt();
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
        // 空跑不记：处理量=0 的成功自动轮（如解析队列扫描没抢到任务）不落行——10s 级任务空跑
        // 日积上万行会把失败/有效执行淹掉；手动触发必落（用户点了要看反馈），失败必落（error 上方已判）。
        boolean idleNoop = success && processed == 0 && !"manual".equals(task.lastTrigger);
        if (!idleNoop) {
            insertRunLog(task.name, task.lastTrigger, success, error, durationMs, startMs);
        }
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
            // 暂停代价说明（仅少数任务非空）；设置页暂停确认弹窗展示，避免误关自愈机制
            m.put("pauseRisk", t.pauseRisk);
            m.put("intervalMs", interval);
            // 该任务专属可调参数（仅保留 schema 里可编辑的键）：设置页按任务归组，避免管理员
            // 在一张平铺长表单里猜「这个旋钮管谁」。不可编辑的键在此滤掉，不外泄到界面。
            m.put("relatedKeys", t.relatedKeys.stream()
                    .filter(configSchemaService::isEditable).toList());
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
        /** 暂停代价说明（null=无特别代价）；设置页暂停确认时展示 */
        final String pauseRisk;
        /** 该任务专属可调参数（见 {@link #REGISTER_TASK_PARAMS}）；设置页按任务归组展示 */
        final List<String> relatedKeys;
        final IntSupplier intervalMs;
        final BooleanSupplier runOnStartup;
        /** 任务体：返回值=本轮处理量——0=空跑（成功且非手动的自动轮不落日志）；&lt;0=恒落（{@link #ALWAYS_LOG}），&gt;0=有产出 */
        final IntSupplier body;
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
                     IntSupplier intervalMs, BooleanSupplier runOnStartup, IntSupplier body,
                     String pauseRisk, List<String> relatedKeys) {
            this.name = name;
            this.desc = desc;
            this.configKey = configKey;
            this.intervalMs = intervalMs;
            this.runOnStartup = runOnStartup;
            this.body = body;
            this.pauseRisk = pauseRisk;
            this.relatedKeys = relatedKeys == null ? List.of() : List.copyOf(relatedKeys);
        }
    }
}
