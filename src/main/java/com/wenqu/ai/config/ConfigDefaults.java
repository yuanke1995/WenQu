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
    /** mcp.server.timeoutMs：MCP Server 单次工具调用等待超时 */
    public static final int MCP_SERVER_TIMEOUT_MS = 180_000;
    /** artifact.retentionDays：产物保留天数（0=不清理） */
    public static final int ARTIFACT_RETENTION_DAYS = 90;
    /** cleanup.visitorIdleDays：访客会话闲置多少天后回收（分享页与 MCP 端点产生，没有主人会去删） */
    public static final int VISITOR_SESSION_IDLE_DAYS = 30;
    /** agent.consultMaxPerTurn：一轮最多把几问交给别的助手去查（P5 咨询工具；超出如实回给模型，不静默少问） */
    public static final int AGENT_CONSULT_MAX_PER_TURN = 3;
    /** agent.consultTimeoutMs：单次咨询要等那条子轮跑完的上限（超时按失败回报，不拖垮父轮） */
    public static final int AGENT_CONSULT_TIMEOUT_MS = 120_000;

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

    private ConfigDefaults() {
    }
}
