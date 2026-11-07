package com.wenqu.ai.config;

/**
 * 配置默认值常量：**多个读点共用同一默认值**的 config key 收敛于此。
 * <p>
 * 背景三层分工（为什么 schema 的 def 不是运行时来源）：
 * <ol>
 *   <li>config-schema.json 的 {@code def} —— 仅下发给前端渲染表单/「恢复默认」，运行时不读；</li>
 *   <li>{@code ConfigService.defaults()} —— 运行时权威默认（启动灌库 + 缓存 miss 兜底）；</li>
 *   <li>读点 {@code get*(key, fallback)} 的 fallback —— 防御层（DB 值缺失/手改坏时的最后兜底）。</li>
 * </ol>
 * 同一默认值此前在三层各写一遍字面量，改一次默认要全仓库跟着改（0.25→0.5 重标定即漂移实证）。
 * 收敛约定：凡 fallback 散布 ≥2 个读点的 key，默认值只在本类定义一处——
 * {@code defaults()} 与各读点全部引用常量，改默认值只改这里（schema def 记得手动同步，仅影响前端展示）。
 * 单读点 key 的 fallback 仍留在读点原地（不值得为它建常量）。
 *
 * @author yuanke
 */
public final class ConfigDefaults {

    // ---- 检索 / 重排（调优重灾区，重标定只改这里） ----

    /** retrieval.minContextScore：重排分门（仅对重排分 0~1 分域生效） */
    public static final double RETRIEVAL_MIN_CONTEXT_SCORE = 0.6;
    /** retrieval.minFusionScore：融合分门（未启用重排时生效；2026-10-04 评测基线标定） */
    public static final double RETRIEVAL_MIN_FUSION_SCORE = 0.5;
    /** 多路检索共识加分（仅评测的多路近似使用；原 retrieval.multiConsensusBonus 已随深度思考退役） */
    public static final double RETRIEVAL_MULTI_CONSENSUS_BONUS = 0.03;
    /** rerank.minHits：候选少于该数不触发 cross-encoder（设置页「重排触发下限」可调，此为默认值） */
    public static final int RERANK_MIN_HITS = 6;
    /** rerank.maxHits：只重排融合分最高的前 N 块（设置页「重排窗口」可调，此为默认值） */
    public static final int RERANK_MAX_HITS = 15;

    // ---- 解析 / 向量化 ----

    /** parse.ocrTimeoutMs：版面引擎（PP-StructureV3 / MinerU）单次调用超时 */
    public static final int PARSE_OCR_TIMEOUT_MS = 600_000;
    /** parse.embedBatchSize：向量化分批大小（每批嵌入块数） */
    public static final int PARSE_EMBED_BATCH_SIZE = 10;
    /** parse.embedRetryCount：向量化批次失败自动重试次数 */
    public static final int PARSE_EMBED_RETRY_COUNT = 1;
    /** parse.embedConcurrency：向量化并发闸 */
    public static final int PARSE_EMBED_CONCURRENCY = 2;

    // ---- 工作流 ----

    /** workflow.maxSteps：单次运行最大图步数（StateGraph recursionLimit） */
    public static final int WORKFLOW_MAX_STEPS = 50;
    /** workflow.runQueueCapacity：异步运行排队容量（队列满即拒绝） */
    public static final int WORKFLOW_RUN_QUEUE_CAPACITY = 50;

    // ---- 其它多读点项 ----

    /** 单个技能全文读取字符上限（平台常量，不再作为设置项） */
    public static final int SKILL_MAX_FILE_CHARS = 20_000;
    /** @ 文档提及正文注入的单份字符上限（粘贴型内容不放大，与技能正文同量级） */
    public static final int MENTION_DOC_MAX_CHARS = 20_000;
    /** @ 文档提及正文注入的总字符预算（一次 @ 十份文档不设总闸会把本轮输入顶爆） */
    public static final int MENTION_DOC_TOTAL_MAX_CHARS = 30_000;
    /**
     * mcp.server.timeoutMs：MCP Server 单次工具调用等待超时。
     * <p>定在 50s 而不是更宽：MCP 客户端侧的请求超时是 60s（TypeScript SDK 默认值，且进度通知不延长它），
     * 比它宽的等待只会让客户端先判死、服务端那一轮再白跑完。
     */
    public static final int MCP_SERVER_TIMEOUT_MS = 50_000;
    /** artifact.retentionDays：产物保留天数（0=不清理） */
    public static final int ARTIFACT_RETENTION_DAYS = 90;
    /** cleanup.visitorIdleDays：访客会话闲置多少天后回收（分享页与 MCP 端点产生，没有主人会去删） */
    public static final int VISITOR_SESSION_IDLE_DAYS = 30;
    /** agent.consultMaxPerTurn：一轮最多把几问交给别的助手去查（P5 咨询工具；超出如实回给模型，不静默少问） */
    public static final int AGENT_CONSULT_MAX_PER_TURN = 3;
    /** agent.consultTimeoutMs：单次咨询要等那条子轮跑完的上限（超时按失败回报，不拖垮父轮） */
    public static final int AGENT_CONSULT_TIMEOUT_MS = 300_000;
    /**
     * agent.spawnForceSync：派遣子助手时父轮是否<b>同步</b>等它跑完（含人批卡的那段）。
     * <p>关掉的后果不是快，而是那张要人批的卡没人接：框架几十秒就把这一支转成异步，
     * 父轮继续往下走、异步回报又没人取，2026-11-06 线上那 5 分钟「正在溯源」就是这个形状。
     */
    public static final boolean AGENT_SPAWN_FORCE_SYNC = true;
    /**
     * agent.spawnSyncTimeoutSeconds：强制同步等待的秒数；0=自动，取
     * {@code max(agent.consultTimeoutMs, chat.approvalTimeoutMs)/1000 + 60}，夹在 [60, 600]（框架上限）。
     */
    public static final int AGENT_SPAWN_SYNC_TIMEOUT_SECONDS = 0;

    // ---- 长期记忆 ----

    /** memory.flushThrottleMinutes：每轮收尾的自动提炼最短间隔（分钟；0=每轮都提炼） */
    public static final int MEMORY_FLUSH_THROTTLE_MINUTES = 10;
    /**
     * memory.maxMdTokens：MEMORY.md 的硬上限（写入落盘时按 4 字符/token 裁剪）。
     * consolidation prompt 的目标值由它按 2/3 推导（默认 2000），不再单列配置项。
     */
    public static final int MEMORY_MAX_MD_TOKENS = 3_000;

    /** sandbox.cleanupIntervalMs：沙盒空闲回收扫描间隔 */
    public static final int SANDBOX_CLEANUP_INTERVAL_MS = 600_000;

    /**
     * cleanup.intervalMs：清理/回收类任务的共用节拍（原 11 个「每日/每小时清理间隔」键收成一个）。
     * <p>取族内**最小**的旧默认（1 小时）而不是最大：清理间隔决定的是「过期数据什么时候被扫到」，
     * 保留期本身没变，扫得勤只是让已过期的东西早一点删，代价是多几轮空扫；反过来把一个原本
     * 每小时的临时目录/外溢原文清理改成每天一轮，等于让磁盘上最脏的两类文件多堆 23 小时。
     */
    public static final int CLEANUP_INTERVAL_MS = 3_600_000;

    /**
     * cleanup.tempFileRetentionDays：临时件保留天数（附件 / 聊天档临时目录 / 工具外溢原文 /
     * 任务执行日志 / 聊天图片）。
     * <p>这一族原先 5 个键各说各的（24 小时、72 小时、7 天、7 天、7 天），折算后按**最长**的 7 天走：
     * 保留期取短了会真删掉本来该留的东西，取长了只多占几天磁盘。
     */
    public static final int CLEANUP_TEMP_FILE_RETENTION_DAYS = 7;
    /** cleanup.recordRetentionDays：过程记录保留天数（会话 / 会话事件账本 / 访客会话 / 站内通知 / 工作流运行记录 / 换代旧日志） */
    public static final int CLEANUP_RECORD_RETENTION_DAYS = 30;
    /** cleanup.assetRetentionDays：用户资产保留天数（产物 / 会话日志归档）——删了就找不回来的那一类 */
    public static final int CLEANUP_ASSET_RETENTION_DAYS = 90;

    // ---- 安全与运维（系统巡检） ----

    /** ops.checkupIntervalMs：自动巡检间隔（默认 1 小时；≤0 = 暂停自动巡检） */
    public static final int OPS_CHECKUP_INTERVAL_MS = 3_600_000;
    /** ops.checkupRetentionDays：巡检报告保留天数（0 = 永久保留） */
    public static final int OPS_CHECKUP_RETENTION_DAYS = 90;
    /** ops.checkupAlertEnabled：巡检出现 fail 时是否给管理员发站内通知 */
    public static final boolean OPS_CHECKUP_ALERT_ENABLED = true;

    private ConfigDefaults() {
    }
}
