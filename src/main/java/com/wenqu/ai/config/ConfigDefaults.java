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
 * 同一默认值若分散在三层各写一遍字面量，改一次默认要全仓库跟着改。
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
    /** retrieval.minFusionScore：融合分门（未启用重排时生效） */
    public static final double RETRIEVAL_MIN_FUSION_SCORE = 0.5;
    /** retrieval.lowConfidencePolicy：低分全灭时的策略（top1=只保底最相关的 1 条；off=不保底） */
    public static final String RETRIEVAL_LOW_CONFIDENCE_POLICY = "top1";
    /** retrieval.top1Floor：保底条目的最低分（低于此分视为没有相关内容，不强行塞给模型） */
    public static final double RETRIEVAL_TOP1_FLOOR = 0.05;
    /** 多路检索共识加分（仅评测的多路近似使用） */
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

    /** workflow.maxSteps：单次运行最大图步数（StateGraph recursionLimit；默认取 schema 上限，实际不拦截） */
    public static final int WORKFLOW_MAX_STEPS = 1000;
    /** workflow.runQueueCapacity：异步运行排队容量（队列满即拒绝；默认放宽到 schema 上限） */
    public static final int WORKFLOW_RUN_QUEUE_CAPACITY = 1000;
    /** workflow.traceInputMaxChars：节点 trace 输入摘要截断长度 */
    public static final int WORKFLOW_TRACE_INPUT_MAX = 400;
    /** workflow.traceOutputMaxChars：节点 trace 输出摘要截断长度 */
    public static final int WORKFLOW_TRACE_OUTPUT_MAX = 800;
    /** workflow.traceErrorMaxChars：节点 trace 错误信息截断长度 */
    public static final int WORKFLOW_TRACE_ERROR_MAX = 500;

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
    /** agent.consultMaxPerTurn：一轮最多把几问交给别的助手去查（超出如实回给模型，不静默少问） */
    public static final int AGENT_CONSULT_MAX_PER_TURN = 3;
    /** agent.consultTimeoutMs：单次咨询要等那条子轮跑完的上限（超时按失败回报，不拖垮父轮） */
    public static final int AGENT_CONSULT_TIMEOUT_MS = 300_000;
    /**
     * agent.spawnForceSync：派遣子助手时父轮是否<b>同步</b>等它跑完（含人批卡的那段）。
     * <p>关掉的后果不是快，而是那张要人批的卡没人接：框架几十秒就把这一支转成异步，
     * 父轮继续往下走、异步回报又没人取，用户会长时间停在「正在溯源」。
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
     * cleanup.intervalMs：清理/回收类任务的共用节拍。
     * <p>取族内**最小**的旧默认（1 小时）而不是最大：清理间隔决定的是「过期数据什么时候被扫到」，
     * 保留期本身没变，扫得勤只是让已过期的东西早一点删，代价是多几轮空扫；反过来把一个原本
     * 每小时的临时目录/外溢原文清理改成每天一轮，等于让磁盘上最脏的两类文件多堆 23 小时。
     */
    public static final int CLEANUP_INTERVAL_MS = 3_600_000;

    /**
     * cleanup.tempFileRetentionDays：临时件保留天数（附件 / 聊天档临时目录 / 工具外溢原文 /
     * 任务执行日志 / 聊天图片）。
     * <p>这一族折算后按**最长**的 7 天走：
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

    // ---- 数据看板（阈值告警 / 周报） ----

    /** dashboard.alertIntervalMs：看板阈值告警检查间隔（默认 6 小时；≤0 = 暂停） */
    public static final int DASHBOARD_ALERT_INTERVAL_MS = 21_600_000;
    /** dashboard.noHitAlertRate：近 7 天无命中率告警阈值（%） */
    public static final int DASHBOARD_NO_HIT_ALERT_RATE = 15;
    /** dashboard.likeAlertRate：近 7 天反馈满意率告警阈值（%） */
    public static final int DASHBOARD_LIKE_ALERT_RATE = 80;
    /** dashboard.weeklyReportIntervalMs：周报检查间隔（默认每天检查；实际只在周一发送；≤0 = 暂停） */
    public static final int DASHBOARD_WEEKLY_INTERVAL_MS = 86_400_000;
    /** ops.checkupAlertEnabled：巡检出现 fail 时是否给管理员发站内通知 */
    public static final boolean OPS_CHECKUP_ALERT_ENABLED = true;

    // ---- 以下参数为平台常量（非设置项） ----

    // ---- 聊天：消息限制与内部超时 ----
    public static final int CHAT_MAX_IMAGES_PER_MESSAGE = 20;
    public static final int CHAT_MAX_IMAGE_MB = 50;
    public static final int CHAT_MAX_QUESTION_CHARS = 200_000;
    public static final int CHAT_ASYNC_WORKSPACE_MAX = 10;
    public static final int CHAT_AGENTSCOPE_MAX_ITERS = 0;
    public static final int CHAT_AGENTSCOPE_JOURNAL_ROTATE_EVENTS = 12_000;
    public static final boolean CHAT_SESSION_LOG_ARCHIVE_ENABLED = true;

    // ---- 视觉：并发与缓存 ----
    public static final int VISION_CONCURRENCY = 2;
    public static final int VISION_USER_IMAGE_CONCURRENCY = 2;
    public static final int VISION_TIMEOUT_MILLIS = 30_000;
    public static final int VISION_RETRY_COUNT = 1;
    public static final String VISION_DESC_CACHE_VERSION = "1";
    public static final int VISION_DESC_CACHE_TTL_DAYS = 180;

    // ---- 上传与分块 ----
    public static final int UPLOAD_MAX_FILE_SIZE_MB = 256;
    /** upload.maxFileSize 的存储口径（字节） */
    public static final long UPLOAD_MAX_FILE_SIZE_BYTES = 256L * 1048576;
    public static final int CHUNK_MAX_CHUNKS = 3000;
    public static final int CHUNK_MAX_IMAGES = 100;

    // ---- 图片处理 ----
    public static final int IMAGES_MAX_WIDTH = 1280;
    public static final float IMAGES_QUALITY = 0.9f;
    public static final int IMAGES_AUTH_EXPIRE_SECONDS = 3600;

    // ---- 解析内部参数 ----
    public static final int PARSE_OCR_MIN_TEXT = 20;
    public static final int PARSE_OCR_DPI = 200;
    public static final int PARSE_CHILD_SIZE = 400;
    public static final int PARSE_RETRY_MAX_ATTEMPTS = 10;
    public static final int PARSE_RETRY_BACKOFF_SECONDS = 5;

    // ---- 检索与上下文 ----
    public static final boolean RETRIEVAL_ACL_TOPK_SUPPLANT = true;
    public static final int CONTEXT_MAX_BLOCKS_PER_DOC = 0;
    public static final int CONTEXT_MAX_BLOCKS_PER_KB = 0;

    // ---- 工具内部参数 ----
    public static final int TOOL_KNOWLEDGE_RETRIEVAL_MAX_HITS = 5;
    public static final int TOOL_SPILL_MAX_MB_PER_USER = 0;
    public static final int TOOL_RUNCODE_MAX_PARALLEL = 8;
    public static final int TOOL_RUNCODE_TIMEOUT_SECONDS = 120;
    public static final int TOOL_RUNCODE_SUB_CALL_TIMEOUT_MS = 60_000;

    // ---- 智能体内部参数 ----
    public static final int AGENT_MAX_TOOL_STEPS = 0;
    public static final int AGENT_VERSION_KEEP = 20;

    // ---- 评估与评测 ----
    public static final String AGENT_EVAL_GATE_MODE = "hint";
    public static final int AGENT_EVAL_THRESHOLD_RATE = 0;
    public static final int AGENT_EVAL_MAX_CASES = 200;
    public static final int AGENT_EVAL_CONCURRENCY = 2;
    public static final int AGENT_EVAL_PER_CASE_TIMEOUT_MS = 180_000;
    public static final int AGENT_EVAL_RUN_TIMEOUT_MS = 900_000;

    // ---- 清理 ----
    public static final int CLEANUP_AUDIT_RETENTION_DAYS = 365;

    // ---- 联网搜索 ----
    public static final int WEB_SEARCH_MAX_RESULTS = 10;
    public static final int WEB_SEARCH_TIMEOUT_MS = 30_000;
    public static final double WEB_SEARCH_FALLBACK_MIN_OVERLAP = 0.2;
    public static final int WEB_SEARCH_SNIPPET_CHARS = 600;

    // ---- HTTP 工具 ----
    public static final int HTTP_TOOL_TIMEOUT_MS = 60_000;

    // ---- 沙盒 ----
    public static final int SANDBOX_PROJECT_UPLOAD_MAX_MB = 256;

    // ---- 通知 ----
    public static final int NOTIFICATION_TURN_MIN_SECONDS = 60;

    // ---- Web Push ----
    public static final int WEBPUSH_TTL_SECONDS = 86_400;

    // ---- 邮件 ----
    public static final int NOTIFY_MAIL_MIN_INTERVAL_MS = 300_000;
    public static final String NOTIFY_MAIL_RETRY_DELAYS_SECONDS = "60,300,900";

    // ---- MCP ----
    public static final int MCP_PATROL_INTERVAL_MS = 300_000;

    // ---- GraphRAG ----
    public static final int GRAPHRAG_BATCH_CHUNKS = 3;
    public static final int GRAPHRAG_MAX_TRIPLES_PER_CHUNK = 30;

    // ---- 目标模式（GoalSettings 读取） ----
    public static final int GOAL_NEXT_ROUND_DELAY_MS = 2000;
    public static final int GOAL_MAX_COMPLETION_CHECKS = 0;
    public static final int GOAL_MAX_VERIFY_FAILURES = 0;
    public static final int GOAL_MAX_RETRIES = 2;
    public static final int GOAL_MAX_RECOVERIES = 3;
    public static final int GOAL_RETRY_BASE_SECONDS = 15;
    public static final int GOAL_RETRY_MAX_SECONDS = 120;
    public static final double GOAL_SOFT_BUDGET_RATIO = 0.8;
    public static final int GOAL_CLAIM_BATCH = 5;
    public static final int GOAL_WORKERS = 4;
    public static final int GOAL_INITIAL_TICK_DELAY_MS = 1000;
    public static final int GOAL_TICK_MS = 2000;
    public static final int GOAL_LEASE_SECONDS = 300;
    public static final int GOAL_HEARTBEAT_STALE_SECONDS = 90;
    public static final int GOAL_STALE_GRACE_SECONDS = 90;
    public static final boolean GOAL_VERIFY_LOCAL_FILES = false;

    // ---- 内容安全 ----
    public static final int CONTENT_SAFETY_TIMEOUT_MS = 3000;
    public static final int CONTENT_SAFETY_REMOTE_CHECK_CHARS = 400;
    public static final int CONTENT_SAFETY_OUTPUT_HOLD_CHARS = 24;
    public static final int CONTENT_SAFETY_MAX_SCAN_CHARS = 20_000;

    private ConfigDefaults() {
    }
}
