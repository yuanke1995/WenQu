package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置读取服务：**只读默认值 + 内存缓存**，不再落库。
 * <p>
 * 2026-09-22 修订：原实现的存储载体是 {@code c_ai_config}（老产品配置表，RSA 加密入库 + Redis 广播
 * 多实例同步）。业务库迁到 wenqu 之后该表不再存在，且本服务已没有配置写入入口，因此把持久化那一面
 * （{@code ConfigMapper} / {@code model.Config} / {@code ConfigCryptoService} / Redis 广播）整体移除：
 * <ul>
 *   <li>启动即把 {@link #defaults()} 灌进内存缓存，随后同步一次 {@code AppProperties}；</li>
 *   <li>对外读取接口（{@code get} / {@code getInt} / {@code getDouble} / {@code getLong} /
 *       {@code getBoolean} / {@code snapshot}）签名与语义不变，消费方零改动；</li>
 *   <li>运行时行为与迁库后完全一致 —— 表不存在时本来就读空回落默认值。</li>
 * </ul>
 * 需要真正可编辑的运行时配置，走新栈的 {@code config_options} 表（{@code OptionsService}）。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ConfigService {

    /** 可编辑白名单 */
    private static final Map<String, String> EDITABLE = Map.ofEntries(
            Map.entry("chat.model", "智能问答模型名"),
            Map.entry("chat.baseUrl", "LLM 网关地址(OpenAI 兼容,不含 /v1;跨厂商热切换,保存即生效)"),
            Map.entry("chat.apiKey", "LLM API Key(RSA 加密入库;保存即生效)"),
            Map.entry("chat.completionsPath", "对话补全路径(默认 /v1/chat/completions;GLM 等非 /v1 网关需改)"),
            Map.entry("chat.temperature", "回答温度(0~2)"),
            Map.entry("chat.systemPrompt", "AI助手系统提示词（角色与回答风格）"),
            Map.entry("chat.pipelineThreads", "问答流水线线程数(保存即生效)"),
            Map.entry("chat.streamRetryCount", "主 LLM 流式中断自动重试次数(未输出token时,0=关闭)"),
            Map.entry("chat.sseTimeoutMs", "问答 SSE 超时(毫秒,默认300000)"),
            Map.entry("chat.showDebugDegradations", "回答提示显示调试级降级信息（默认关：只显示用户级）"),
            Map.entry("chat.suggestedQuestions", "推荐问题池（每行一个，欢迎页展示，最多8条；看板热门问题可一键加入）"),
            Map.entry("chat.retrievalDebugEnabled", "检索调试入口（内部排障用，默认隐藏；开启后回答操作菜单显示「检索调试」）"),
            Map.entry("chat.historyRounds", "多轮记忆注入轮数（问答时注入最近几轮对话作为上下文）"),
            Map.entry("chat.remainTokenFloor", "上下文填充保留下限(token)：预算扣掉固定部分后至少保留该值给知识块"),
            Map.entry("chat.truncateFallbackChars", "知识块超预算截断兜底字符数(块级截断每块仍保留的最小片段)"),
            Map.entry("vision.enabled", "视觉模型总开关（false 时图片不生成描述）"),
            Map.entry("vision.model", "视觉识别模型名"),
            Map.entry("vision.baseUrl", "视觉模型网关地址(OpenAI 兼容,保存即生效)"),
            Map.entry("vision.apiKey", "视觉模型 API Key(RSA 加密入库,保存即生效)"),
            Map.entry("vision.prompt", "视觉识别提示词"),
            Map.entry("vision.concurrency", "图片描述并发数（保存即生效）"),
            Map.entry("vision.userImageConcurrency", "用户上传图片识别并发数（保存即生效）"),
            Map.entry("vision.descCacheVersion", "图片描述缓存版本(改动后重解析全量重新描述)"),
            Map.entry("vision.descCacheTtlDays", "图片描述缓存有效期(天,0=不过期)"),
            Map.entry("chunk.maxChunks", "单文档最大知识块数(0=不限制)"),
            Map.entry("chunk.maxImages", "单文档最多提取图片数(0=不限制)"),
            Map.entry("chunk.overlap", "分块重叠字符数(0=关闭)"),
            Map.entry("chunk.structural", "结构感知切分(标题/段落边界+章节路径注入,需重解析)"),
            Map.entry("chunk.structuralRatio", "结构切分边界阈值比例(0~1,达到maxSize×比例优先段落断块)"),
            Map.entry("upload.maxFileSize", "文档上传大小上限(字节,保存即生效)"),
            Map.entry("retrieval.vectorWeight", "混合检索：向量权重(0~1)"),
            Map.entry("retrieval.keywordWeight", "混合检索：关键词权重(0~1)"),
            Map.entry("context.modelWindows", "上下文：模型窗口映射（模型名=token,逗号分隔）"),
            Map.entry("context.defaultWindowTokens", "上下文：模型默认窗口(token)"),
            Map.entry("context.safetyFactor", "上下文：窗口安全系数(0~1)"),
            Map.entry("context.costCapTokens", "上下文：成本软上限(token,0=不限制)"),
            Map.entry("context.maxOutputTokens", "上下文：输出限制(token)"),
            Map.entry("context.historyMaxTokens", "上下文：对话历史注入上限(token)"),
            Map.entry("context.historyPerMsgChars", "上下文：单条历史截断(字符)"),
            Map.entry("context.snippetWindowChars", "上下文：知识块命中片段窗口(字符,0=整块)"),
            Map.entry("context.maxContextHits", "上下文：知识块填充上限(块)"),
            Map.entry("context.dedupEnabled", "上下文：信息增益去冗余（跳过与已选块语义重复的候选，防同一操作多块重复进上下文）"),
            Map.entry("context.dedupThreshold", "上下文：去冗余词元重叠阈值(0~1，默认0.45，越高越宽松)"),
            Map.entry("context.dedupPathThreshold", "上下文：同章节路径下去冗余阈值(0~1，默认0.28，同章节切片重叠更易剪)"),
            Map.entry("chat.citationCheckEnabled", "回答引用语义一致性自检（生成后校验[N]对应句子是否被引用内容支撑，不支撑剔除；增加一次校验调用延迟）"),
            Map.entry("deepReasoning.enabled", "深度思考：总开关"),
            Map.entry("deepReasoning.autoRoute", "深度思考：自动路由（未手动开启时按问题长度/多条件/对比自动判断）"),
            Map.entry("rerank.enabled", "重排：是否启用（需先启动本地 reranker 服务）"),
            Map.entry("rerank.baseUrl", "重排：服务地址"),
            Map.entry("rerank.model", "重排：模型名"),
            Map.entry("retrieval.vecThreshold", "检索：向量相似度下限(0~1，评估对比后可应用)"),
            Map.entry("retrieval.keywordLimit", "检索：关键词召回词数上限"),
            Map.entry("retrieval.vectorTopK", "检索：向量召回 topK（评估对比后可应用）"),
            Map.entry("retrieval.searchTimeoutMs", "检索：混合检索总超时(ms,含关键词并行)"),
            Map.entry("keyword.engine", "关键词引擎：mysql / meilisearch（切换前先探测并重建索引）"),
            Map.entry("keyword.baseUrl", "关键词引擎：Meilisearch 服务地址"),
            Map.entry("keyword.apiKey", "关键词引擎：Meilisearch master key（RSA 加密入库,留空回退环境变量 AI_MEILI_KEY）"),
            Map.entry("ratelimit.enabled", "接口限流总开关（Redis 固定窗口，按用户/IP）"),
            // 向量模型热切换（保存即生效 + 自动触发全量重嵌入，见 update）
            Map.entry("embedding.model", "向量模型名(保存后自动全量重嵌入,期间降级关键词检索)"),
            Map.entry("embedding.baseUrl", "向量模型网关地址(OpenAI 兼容)"),
            Map.entry("embedding.apiKey", "向量模型 API Key(RSA 加密入库)"),
            Map.entry("embedding.embeddingsPath", "向量化路径(默认 /v1/embeddings;智谱 /v4、千帆 /v2)"),
            // ===== 以下为「代码早已读取、此前未开放到设置页」的参数（补白名单，无需改读取点）=====
            Map.entry("images.chatCleanupIntervalMs", "聊天图片：清理任务执行间隔(ms,默认86400000=每天)"),
            Map.entry("images.chatRetentionMillis", "聊天图片：保留时长(ms,默认604800000=7天；超期清理)"),
            // ===== 以下为「原由 ai-app.* yml 读取、设置页不可改」的参数：开放后由 syncProperties 回写到 AppProperties =====
            Map.entry("chunk.maxSize", "文档解析：单块最大字符数(分块粒度，影响检索精度与 embedding 成本；改后需重解析生效)"),
            Map.entry("chunk.headingDepth", "文档解析：章节标题识别上限层级(1~6；调大后更深层的小节/条目标题独立成块并进章节路径，改后需重解析生效)"),
            Map.entry("images.maxWidth", "图片：压缩后最长边像素(0=不压缩；影响视觉识别清晰度与成本)"),
            Map.entry("images.quality", "图片：JPEG 压缩质量(0~1)"),
            Map.entry("images.authEnabled", "图片访问鉴权：HMAC 签名 URL 开关(生产建议开，关闭则图片 URL 可直接访问)"),
            Map.entry("images.authExpireSeconds", "图片访问鉴权：签名 URL 有效期(秒)"),
            Map.entry("vision.timeoutMillis", "视觉模型：单张图片描述读取超时(ms；客户端在启动时构建，改动需重启生效)"),
            Map.entry("vision.retryCount", "视觉模型：单图描述失败重试次数(降低降级率)"),
            Map.entry("vision.think", "视觉模型：是否开启思考模式(qwen3 系默认思考；关闭可提速且输出更稳定)"),
            Map.entry("vision.keepAliveMinutes", "视觉模型：Ollama 模型常驻时长(分钟，0=不发送；云端服务需设 0)"),
            Map.entry("vision.numCtx", "视觉模型：Ollama 上下文窗口 num_ctx(0=不设置；默认 4096 会截断大图)"),
            // ===== 查询改写（QueryRewrite）与图片相关性校验（ImageFilter）：同样由 syncProperties 回写 =====
            // ===== 意图分类（Intent）：闲聊/知识库无关消息跳过检索直接对话，由 syncProperties 回写 =====
            // ===== 消费方直读 configService 的行为参数（原先写死在代码里）=====
            Map.entry("chat.maxImagesPerMessage", "对话：单条消息最多图片张数"),
            Map.entry("chat.maxImageMb", "对话：单张图片体积上限(MB)"),
            Map.entry("retrieval.relatedCount", "回答：末尾 <related> 相关追问的推荐条数"),
            // ===== 工具调用（Function Calling）：@Tool 工具开关，均需 tool.enabled 总开关开启才生效 =====
            Map.entry("tool.enabled", "工具调用：总开关（开启后模型可调用 @Tool 工具，如知识库精确检索、产物交付）"),
            Map.entry("tool.knowledgeRetrieval.enabled", "工具调用：知识库精确检索工具开关（模型可主动补充检索，需总开关开启）"),
            Map.entry("tool.knowledgeRetrieval.maxHits", "工具调用：精确检索工具单次返回命中块上限(1~5)"),
            Map.entry("tool.artifact.enabled", "工具调用：产物交付工具开关（模型可生成 Markdown/CSV/JSON/HTML 文件并推送给用户，需总开关开启；默认关）"),
            Map.entry("tool.builtin.enabled", "工具调用：内置高频工具开关（算术计算/当前时间/日期差，需总开关开启；默认关）"),
            Map.entry("skill.enabled", "技能（Skills）：总开关。技能=目录+SKILL.md 的纯文本能力包，开启后按需注入/读取"),
            Map.entry("skill.dir", "技能（Skills）：用户技能目录（放 {技能名}/SKILL.md 即多一个技能；同名覆盖内置）"),
            Map.entry("skill.injectEnabled", "技能（Skills）：把「技能名+描述」清单注入系统提示（渐进披露，正文由模型按需 readSkill 取）"),
            Map.entry("skill.toolEnabled", "技能（Skills）：readSkill 工具开关（模型主动取技能全文，需工具总开关）"),
            Map.entry("skill.injectMaxChars", "技能（Skills）：清单注入字符上限（防技能过多挤占上下文）"),
            Map.entry("skill.maxFileChars", "技能（Skills）：单个技能全文读取上限（字符，超出截断）"),
            Map.entry("skill.disabledNames", "技能（Skills）：已停用技能目录名列表（JSON 数组，系统写入）"),
            Map.entry("agent.enabled", "SubAgent 并行编排：总开关（多视角并行检索 + 要点提炼；默认关，开启后每轮多 2~4 次提炼调用）"),
            Map.entry("agent.subAgents", "SubAgent 并行编排：子代理数量（2~4，默认 2）"),
            Map.entry("agent.topKPerAgent", "SubAgent 并行编排：每个子代理取回命中块数（默认 3）"),
            Map.entry("agent.digestEnabled", "SubAgent 并行编排：是否用模型把命中提炼成要点（关=只并行检索不调模型）"),
            Map.entry("agent.autoRoute", "SubAgent 并行编排：按需委派（主模型先从候选子智能体里挑选相关的，只咨询选中的；关=每轮全部并行）"),
            Map.entry("agent.routeTimeoutMs", "SubAgent 并行编排：按需委派的路由判定超时毫秒（超时回退为全部候选，默认 5000）"),
            // ===== MCP 外部工具（Model Context Protocol）：接入用户自配的 MCP Server，工具自动注册进 Function Calling =====
            Map.entry("mcp.enabled", "MCP 外部工具：总开关（开启后尝试连接下方 MCP Server 并把其工具暴露给模型；连接失败自动跳过不影响问答）"),
            Map.entry("mcp.servers", "MCP 外部工具：Server 列表 JSON（[{\"name\":\"名称\",\"url\":\"http://host:port/mcp\",\"type\":\"streamable\"}]，type 可选 streamable/sse；保存后下一轮问答生效）"));

    /**
     * 参数分层（仅影响设置页可见性，不影响任何读取链路）：
     * 1 = 必需，不配就不能跑（新手模式可见）
     * 2 = 调优，换语料/换场景才动（专家模式可见）
     * 3 = 工程排障，超时/重试/并发/TTL 之类（专家模式可见，默认折叠）
     * 未列出的 key 一律按 2 处理。
     */
    private static final Map<String, Integer> TIER = Map.ofEntries(
            // ===== L1 必需（15 项）=====
            Map.entry("chat.model", 1),
            Map.entry("chat.baseUrl", 1),
            Map.entry("chat.apiKey", 1),
            Map.entry("chat.temperature", 1),
            Map.entry("chat.systemPrompt", 1),
            Map.entry("chat.suggestedQuestions", 1),
            Map.entry("chat.historyRounds", 1),
            Map.entry("embedding.model", 1),
            Map.entry("embedding.baseUrl", 1),
            Map.entry("embedding.apiKey", 1),
            Map.entry("chunk.maxSize", 1),
            Map.entry("chunk.overlap", 1),
            Map.entry("retrieval.vectorTopK", 1),
            Map.entry("retrieval.vecThreshold", 1),
            Map.entry("vision.enabled", 1),
            // ===== L3 工程排障：chat =====
            Map.entry("chat.pipelineThreads", 3),
            Map.entry("chat.streamRetryCount", 3),
            Map.entry("chat.sseTimeoutMs", 3),
            Map.entry("chat.showDebugDegradations", 3),
            Map.entry("chat.retrievalDebugEnabled", 3),
            Map.entry("chat.truncateFallbackChars", 3),
            Map.entry("chat.maxImagesPerMessage", 3),
            Map.entry("chat.maxImageMb", 3),
            // ===== L3：vision =====
            Map.entry("vision.concurrency", 3),
            Map.entry("vision.userImageConcurrency", 3),
            Map.entry("vision.timeoutMillis", 3),
            Map.entry("vision.retryCount", 3),
            Map.entry("vision.think", 3),
            Map.entry("vision.keepAliveMinutes", 3),
            Map.entry("vision.numCtx", 3),
            Map.entry("vision.descCacheVersion", 3),
            Map.entry("vision.descCacheTtlDays", 3),
            // ===== L3：解析 =====
            Map.entry("parse.concurrency", 3),
            Map.entry("parse.embedRetryCount", 3),
            Map.entry("parse.embedBatchSize", 3),
            Map.entry("parse.ocrMinText", 3),
            Map.entry("parse.ocrDpi", 3),
            Map.entry("parse.recoverStuckOnStartup", 3),
            // ===== L3：关键词 / 重排 冷却与对账 =====
            // ===== L3：上下文预算（maxContextHits / costCapTokens / dedupEnabled 留 L2）=====
            Map.entry("context.modelWindows", 3),
            Map.entry("context.defaultWindowTokens", 3),
            Map.entry("context.safetyFactor", 3),
            Map.entry("context.maxOutputTokens", 3),
            Map.entry("context.historyMaxTokens", 3),
            Map.entry("context.historyPerMsgChars", 3),
            Map.entry("context.snippetWindowChars", 3),
            Map.entry("context.dedupThreshold", 3),
            Map.entry("context.dedupPathThreshold", 3),
            // ===== L3：深度思考细节（总开关/模式/自动路由留 L2）=====
            Map.entry("deepReasoning.timeoutMillis", 3),
            Map.entry("deepReasoning.maxThinkingTokens", 3),
            Map.entry("deepReasoning.maxThinkingChars", 3),
            Map.entry("deepReasoning.searchTag", 3),
            Map.entry("deepReasoning.prompt", 3),
            Map.entry("deepReasoning.multiRetrieval", 3),
            Map.entry("deepReasoning.injectThinkingMaxChars", 3),
            Map.entry("deepReasoning.injectKeywords", 3),
            Map.entry("deepReasoning.injectKeywordsMax", 3),
            Map.entry("deepReasoning.autoRouteMinChars", 3),
            Map.entry("deepReasoning.autoRouteLongChars", 3),
            Map.entry("deepReasoning.autoRouteKeywords", 3),
            // ===== L3：检索细节（权重/bonus/keywordLimit 等留 L2）=====
            // ===== L3：查询改写 / 意图 / 图片过滤 细节（总开关留 L2）=====
            Map.entry("imageFilter.minHits", 3),
            Map.entry("imageFilter.preContextChars", 3),
            // ===== L3：运维（限流 / 图片 / 会话 / 体检 / 清理 / 缓存）=====
            Map.entry("ratelimit.windowSeconds", 3),
            Map.entry("ratelimit.chatPerMinute", 3),
            Map.entry("ratelimit.uploadPerMinute", 3),
            Map.entry("images.maxWidth", 3),
            Map.entry("images.quality", 3),
            Map.entry("images.authEnabled", 3),
            Map.entry("images.authExpireSeconds", 3),
            Map.entry("images.chatCleanupIntervalMs", 3),
            Map.entry("images.chatRetentionMillis", 3),
            Map.entry("session.maxHistory", 3),
            Map.entry("session.expireMinutes", 3),
            Map.entry("eval.judgeEnabled", 3),
            Map.entry("eval.autoIntervalMs", 3),
            Map.entry("eval.autoThresholdPct", 3),
            Map.entry("eval.judgeModel", 3),
            Map.entry("cleanup.sessionCleanupIntervalMs", 3),
            Map.entry("cleanup.sessionRetentionDays", 3),
            Map.entry("cache.docMetaTtlSeconds", 3),
            // ===== 工具调用 / MCP（管理调优项）=====
            Map.entry("tool.enabled", 2),
            Map.entry("tool.knowledgeRetrieval.enabled", 2),
            Map.entry("tool.knowledgeRetrieval.maxHits", 3),
            Map.entry("tool.artifact.enabled", 2),
            Map.entry("tool.builtin.enabled", 2),
            Map.entry("skill.enabled", 2),
            Map.entry("skill.dir", 2),
            Map.entry("skill.injectEnabled", 2),
            Map.entry("skill.toolEnabled", 2),
            Map.entry("skill.injectMaxChars", 3),
            Map.entry("skill.maxFileChars", 3),
            Map.entry("agent.enabled", 2),
            Map.entry("agent.subAgents", 2),
            Map.entry("agent.topKPerAgent", 3),
            Map.entry("agent.digestEnabled", 2),
            Map.entry("agent.autoRoute", 2),
            Map.entry("agent.routeTimeoutMs", 3),
            Map.entry("mcp.enabled", 2),
            Map.entry("mcp.servers", 2));

    private final AppProperties properties;
    private final Environment environment;
    /** 从 yml/env 读取默认值（见类注释：已不再落库） */
    private volatile Map<String, String> cache = new HashMap<>();

    public ConfigService(AppProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        cache = defaults();
        syncProperties();
        log.info("模型配置加载完成，共 {} 项", cache.size());
    }

    /** 从 yml/env 读取默认值 */
    private Map<String, String> defaults() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("chat.model", env("spring.ai.openai.chat.options.model", "qwen3.8-27b"));
        d.put("chat.temperature", env("spring.ai.openai.chat.options.temperature", "0.3"));
        d.put("chat.systemPrompt", properties.getSystemPrompt());
        d.put("chat.baseUrl", env("spring.ai.openai.base-url", ""));
        d.put("chat.apiKey", env("spring.ai.openai.api-key", ""));
        // 对话补全路径（GLM 等非 /v1 网关需改，如 /api/paas/v4/chat/completions；默认与 Spring AI 一致）
        d.put("chat.completionsPath", "/v1/chat/completions");
        d.put("chat.citationCheckEnabled", "true");        // 引用语义一致性自检（生成后校验，默认开）
        d.put("vision.model", properties.getVision().getModel());
        d.put("vision.prompt", properties.getVision().getPrompt());
        d.put("vision.baseUrl", properties.getVision().getBaseUrl());
        d.put("vision.apiKey", properties.getVision().getApiKey());
        d.put("vision.enabled", String.valueOf(properties.getVision().isEnabled())); // L12：总开关（设置页可改）
        d.put("vision.concurrency", String.valueOf(properties.getVision().getConcurrency()));
        d.put("vision.descCacheVersion", "1");                 // 图片描述缓存版本（bump 后全量重新描述）
        d.put("vision.descCacheTtlDays", "180");               // 图片描述缓存有效期(天，0=不过期)
        d.put("embedding.model", env("spring.ai.openai.embedding.options.model", ""));
        // 向量模型（OpenAI 兼容）：DB 未配置时回退 yml/env 的 spring.ai.openai.embedding.*
        d.put("embedding.baseUrl", env("spring.ai.openai.embedding.base-url",
                env("spring.ai.openai.base-url", "")));
        d.put("embedding.apiKey", env("spring.ai.openai.embedding.api-key",
                env("spring.ai.openai.api-key", "")));
        d.put("embedding.embeddingsPath", "/v1/embeddings");
        // 当前向量索引维度（系统记录，非用户可编辑）：全量重嵌入成功后由 putInternal 回写，
        // 作为下次切换的"旧维度"基线并供设置页展示。空/0 = 尚未记录（首次部署或未切换过）
        d.put("embedding.dimensions", "");
        d.put("chunk.maxChunks", String.valueOf(properties.getChunk().getMaxChunks()));
        d.put("chunk.maxImages", String.valueOf(properties.getChunk().getMaxImages()));
        d.put("chunk.overlap", String.valueOf(properties.getChunk().getOverlap()));
        d.put("chunk.structural", String.valueOf(properties.getChunk().isStructural()));
        d.put("chunk.structuralRatio", String.valueOf(properties.getChunk().getStructuralRatio()));
        d.put("parse.embedRetryCount", "1");                 // M10：向量化批次失败自动重试次数
        d.put("upload.maxFileSize", String.valueOf(200L * 1024 * 1024));  // 业务上传上限（字节），默认 200MB
        d.put("retrieval.vectorWeight", String.valueOf(properties.getRetrieval().getVectorWeight()));
        d.put("retrieval.keywordWeight", String.valueOf(properties.getRetrieval().getKeywordWeight()));
        d.put("rerank.enabled", String.valueOf(properties.getRetrieval().getRerank().isEnabled()));
        d.put("rerank.baseUrl", properties.getRetrieval().getRerank().getBaseUrl());
        d.put("rerank.model", properties.getRetrieval().getRerank().getModel());
        d.put("context.modelWindows", properties.getContext().getModelWindows());
        d.put("context.defaultWindowTokens", String.valueOf(properties.getContext().getDefaultWindowTokens()));
        d.put("context.safetyFactor", String.valueOf(properties.getContext().getSafetyFactor()));
        d.put("context.costCapTokens", String.valueOf(properties.getContext().getCostCapTokens()));
        d.put("context.maxOutputTokens", String.valueOf(properties.getContext().getMaxOutputTokens()));
        d.put("context.historyMaxTokens", String.valueOf(properties.getContext().getHistoryMaxTokens()));
        d.put("context.historyPerMsgChars", String.valueOf(properties.getContext().getHistoryPerMsgChars()));
        d.put("context.snippetWindowChars", String.valueOf(properties.getContext().getSnippetWindowChars()));
        d.put("context.maxContextHits", String.valueOf(properties.getContext().getMaxContextHits()));
        d.put("context.dedupEnabled", "true");             // 信息增益去冗余（默认开）
        d.put("context.dedupThreshold", "0.45");           // 词元重叠阈值（越高越宽松）
        d.put("context.dedupPathThreshold", "0.28");       // 同章节路径下重叠阈值
        d.put("deepReasoning.enabled", String.valueOf(properties.getDeepReasoning().isEnabled()));
        d.put("deepReasoning.thinkingMode", properties.getDeepReasoning().getThinkingMode());
        d.put("deepReasoning.enableThinking", String.valueOf(properties.getDeepReasoning().isEnableThinking()));
        d.put("deepReasoning.prompt", properties.getDeepReasoning().getPrompt());
        d.put("deepReasoning.searchTag", properties.getDeepReasoning().getSearchTag());
        d.put("deepReasoning.maxSubQueries", String.valueOf(properties.getDeepReasoning().getMaxSubQueries()));
        d.put("deepReasoning.multiRetrieval", String.valueOf(properties.getDeepReasoning().isMultiRetrieval()));
        d.put("deepReasoning.timeoutMillis", String.valueOf(properties.getDeepReasoning().getTimeoutMillis()));
        d.put("deepReasoning.maxThinkingTokens", String.valueOf(properties.getDeepReasoning().getMaxThinkingTokens()));
        d.put("deepReasoning.maxThinkingChars", String.valueOf(properties.getDeepReasoning().getMaxThinkingChars()));
        d.put("deepReasoning.injectThinking", String.valueOf(properties.getDeepReasoning().isInjectThinking()));
        d.put("deepReasoning.injectThinkingMaxChars", String.valueOf(properties.getDeepReasoning().getInjectThinkingMaxChars()));
        d.put("deepReasoning.injectKeywords", String.valueOf(properties.getDeepReasoning().isInjectKeywords()));
        d.put("deepReasoning.injectKeywordsMax", String.valueOf(properties.getDeepReasoning().getInjectKeywordsMax()));
        d.put("deepReasoning.autoRoute", String.valueOf(properties.getDeepReasoning().isAutoRoute()));
        // 检索行为参数（原硬编码收口，设置页可调、保存即生效）
        d.put("retrieval.vecThreshold", "0.3");            // 向量相似度归一化基准/下限
        d.put("retrieval.vectorTopK", "15");               // 向量召回 topK（调优/评估扫参用，下限 1）
        d.put("retrieval.keywordLimit", "20");             // 关键词召回上限
        d.put("retrieval.searchTimeoutMs", "8000");        // 混合检索总超时
        d.put("retrieval.vectorTopK", "15");               // 向量检索召回上限（评估批量对比可覆盖）
        // 重排行为参数
        // 关键词召回引擎（mysql=LIKE；meilisearch=外部索引，中文分词+相关度；index 只走 yml 不入库）
        d.put("keyword.engine", properties.getKeyword().getEngine());
        d.put("keyword.baseUrl", properties.getKeyword().getBaseUrl());
        d.put("keyword.apiKey", properties.getKeyword().getApiKey());   // master key RSA 加密入库（设置页可改，改后客户端自动重建）；未配置时回退 env AI_MEILI_KEY
        // 解析行为参数
        d.put("parse.concurrency", "2");                   // 文档解析并发数
        d.put("parse.ocrMinText", "20");                   // PDF 文本少于该长度判定扫描件触发 OCR
        d.put("parse.recoverStuckOnStartup", "true");      // 启动对账：复位崩溃残留的"解析中"文档（多副本部署应置 false）
        d.put("vision.userImageConcurrency", "2");         // 用户上传图片识别并发
        // 问答行为参数
        d.put("chat.remainTokenFloor", "800");             // 上下文填充保留下限
        d.put("chat.truncateFallbackChars", "200");        // 超预算截断兜底字符数
        d.put("chat.historyRounds", "5");                  // 多轮记忆注入轮数
        d.put("chat.pipelineThreads", "8");                // 问答流水线线程数（重活不占 Tomcat 请求线程）
        d.put("chat.streamRetryCount", "1");               // H2：主 LLM 流式中断（未输出token）自动重试次数
        d.put("chat.sseTimeoutMs", "300000");              // H4：问答 SSE 超时(ms)
        d.put("chat.showDebugDegradations", "false");      // 回答提示：调试级降级信息开关（默认只显示用户级）
        d.put("chat.suggestedQuestions", "系统有哪些功能？\n如何创建一个新表单？\n字段验证怎么设置？\n什么是填报周期？");  // 欢迎页推荐问题（每行一个）
        d.put("chat.retrievalDebugEnabled", "false");      // 检索调试入口（内部排障，默认关）
        // 接口限流（按用户/IP 固定窗口）
        d.put("ratelimit.enabled", String.valueOf(properties.getRatelimit().isEnabled()));
        d.put("ratelimit.chatPerMinute", String.valueOf(properties.getRatelimit().getChatPerMinute()));
        d.put("ratelimit.uploadPerMinute", String.valueOf(properties.getRatelimit().getUploadPerMinute()));
        d.put("eval.judgeEnabled", "false");   // 自动体检 LLM 评判（默认关，评估集大时耗时/成本明显）
        d.put("eval.judgeModel", "");              // 评判用独立模型（留空回落 chat.model）
        d.put("eval.autoIntervalMs", "86400000");  // 自动体检周期(ms，≤0=暂停)
        d.put("eval.autoThresholdPct", "10");      // 退化判定：指标相对跌幅百分比阈值
        // 定时维护（schedule 包读取；≤0=暂停对应任务）
        d.put("images.chatCleanupIntervalMs", "86400000");   // 聊天图片清理间隔(ms)
        d.put("images.chatRetentionMillis", "604800000");    // 聊天图片保留时长(ms，7天)
        d.put("cleanup.sessionCleanupIntervalMs", "86400000"); // 会话清理间隔(ms)
        d.put("cleanup.sessionRetentionDays", "30");           // 会话保留天数
        // 原 yml 参数开放为可配置（值由 syncProperties 回写到 AppProperties，读取点无需改动）
        d.put("chunk.maxSize", "800");                 // 单块最大字符数
        d.put("chunk.headingDepth", "4");              // 章节标题识别上限层级(1~6)
        d.put("images.maxWidth", "1280");              // 图片压缩最长边(px,0=不压缩)
        d.put("images.quality", "0.9");                // JPEG 压缩质量
        d.put("images.authEnabled", "false");          // 图片签名鉴权开关
        d.put("images.authExpireSeconds", "3600");     // 签名 URL 有效期(秒)
        d.put("vision.timeoutMillis", "30000");        // 视觉模型读取超时(ms)
        d.put("vision.retryCount", "1");               // 单图失败重试次数
        d.put("vision.think", "false");                // 视觉模型思考模式开关
        d.put("vision.keepAliveMinutes", "30");        // Ollama 常驻时长(分钟)
        d.put("vision.numCtx", "16384");               // Ollama num_ctx
        d.put("session.maxHistory", "10");             // 会话保留轮数
        d.put("session.expireMinutes", "30");          // 会话过期(分钟)
        // 查询改写（默认值取 bean，单一来源；消费方 RagService 经 syncProperties 回写后热生效）
        // 图片相关性校验（读取点 RagService；defaults 取 bean）
        d.put("imageFilter.enabled", String.valueOf(properties.getImages().getImageFilter().isEnabled()));
        d.put("imageFilter.minHits", String.valueOf(properties.getImages().getImageFilter().getMinHits()));
        d.put("imageFilter.preContextChars", String.valueOf(properties.getImages().getImageFilter().getPreContextChars()));
        // 原先写死在消费方代码里的行为参数（直读 configService，保存即生效）
        d.put("deepReasoning.autoRouteMinChars", "8");
        d.put("deepReasoning.autoRouteLongChars", "25");
        d.put("deepReasoning.autoRouteKeywords", "如果,当,对比,区别,以及,同时,多个,分别,为什么");
        d.put("chat.maxImagesPerMessage", "9");
        d.put("chat.maxImageMb", "10");
        d.put("retrieval.relatedCount", "3");
        d.put("parse.embedBatchSize", "10");
        d.put("parse.ocrDpi", "200");
        d.put("ratelimit.windowSeconds", "60");
        d.put("cache.docMetaTtlSeconds", "600");
        // 工具调用（Function Calling）总开关与知识库精确检索工具
        d.put("tool.enabled", "false");                    // 工具调用总开关（默认关，开启后模型可调用工具）
        d.put("tool.knowledgeRetrieval.enabled", "false"); // 知识库精确检索工具开关（需总开关开启）
        d.put("tool.knowledgeRetrieval.maxHits", "5");     // 精确检索工具单次返回命中块上限(1~5)
        d.put("tool.artifact.enabled", "false");           // 产物交付工具开关（需总开关开启；生成 Markdown/CSV/JSON/HTML 文件并推送）
        d.put("tool.builtin.enabled", "false");            // 内置高频工具开关（需总开关开启；计算/当前时间/日期差）
        d.put("skill.enabled", "false");                   // 技能总开关（默认关；开启后按需注入清单 + readSkill 工具）
        d.put("skill.dir", "./data/skills");               // 用户技能目录（{技能名}/SKILL.md）
        d.put("skill.injectEnabled", "true");              // 清单注入系统提示（渐进披露）
        d.put("skill.toolEnabled", "true");                // readSkill 工具（需工具总开关）
        d.put("skill.injectMaxChars", "1200");             // 清单注入字符上限
        d.put("skill.maxFileChars", "20000");              // 单技能全文读取上限
        d.put("skill.disabledNames", "[]");                // 已停用技能（系统写入）
        d.put("agent.enabled", "false");                   // SubAgent 并行编排总开关（默认关）
        d.put("agent.subAgents", "2");                     // 子代理数量（2~4）
        d.put("agent.topKPerAgent", "3");                  // 每个子代理取回命中块数
        d.put("agent.digestEnabled", "true");              // 是否用模型提炼要点
        d.put("agent.autoRoute", "true");                  // 按需委派：主模型先挑相关的子智能体再咨询
        d.put("agent.routeTimeoutMs", "8000");             // 路由判定超时（超时回退全部候选）

        d.put("mcp.enabled", "false");                     // MCP 外部工具总开关（默认关；连接外部 MCP Server 并暴露其工具）
        d.put("mcp.servers", "[]");                        // MCP Server 列表 JSON（[{name,url,type}]，type=streamable|sse）
        return d;
    }

    /**
     * 把「原由 ai-app.*（yml/env）在启动时绑定」的配置项，从缓存回写到 AppProperties。
     * <p>目的：这些项（chunk.maxSize、images.*、vision.*、session.*）的消费方直接调用
     * properties.getXxx()，若只加白名单不回写，设置页保存了也不会生效。
     * 在每次缓存（重）载入后调用一次，即可让消费方无需改动而支持 DB/设置页热生效。
     * <p>bean 是缓存派生视图（非第二份真源）：值非法/缺失时保留 bean 当前值（安全回退）。
     * 注：个别在构造期一次性读取的值（如 VisionService 的 RestClient 读超时）需重启才生效。
     */
    private void syncProperties() {
        try {
            AppProperties.Chunk chunk = properties.getChunk();
            chunk.setMaxSize(pInt("chunk.maxSize", chunk.getMaxSize()));
            chunk.setHeadingDepth(pInt("chunk.headingDepth", chunk.getHeadingDepth()));
            AppProperties.Images images = properties.getImages();
            images.setMaxWidth(pInt("images.maxWidth", images.getMaxWidth()));
            images.setQuality((float) pDouble("images.quality", images.getQuality()));
            images.setAuthEnabled(pBool("images.authEnabled", images.isAuthEnabled()));
            images.setAuthExpireSeconds(pLong("images.authExpireSeconds", images.getAuthExpireSeconds()));
            AppProperties.Vision vision = properties.getVision();
            vision.setTimeoutMillis(pInt("vision.timeoutMillis", vision.getTimeoutMillis()));
            vision.setRetryCount(pInt("vision.retryCount", vision.getRetryCount()));
            vision.setThink(pBool("vision.think", vision.isThink()));
            vision.setKeepAliveMinutes(pInt("vision.keepAliveMinutes", vision.getKeepAliveMinutes()));
            vision.setNumCtx(pInt("vision.numCtx", vision.getNumCtx()));
            AppProperties.Session session = properties.getSession();
            session.setMaxHistory(pInt("session.maxHistory", session.getMaxHistory()));
            session.setExpireMinutes(pInt("session.expireMinutes", session.getExpireMinutes()));
            AppProperties.ImageFilter imgFilter = properties.getImages().getImageFilter();
            imgFilter.setEnabled(pBool("imageFilter.enabled", imgFilter.isEnabled()));
            imgFilter.setMinHits(pInt("imageFilter.minHits", imgFilter.getMinHits()));
            imgFilter.setPreContextChars(pInt("imageFilter.preContextChars", imgFilter.getPreContextChars()));
        } catch (Exception e) {
            log.warn("[Config] 回写 AppProperties 失败（沿用当前值）: {}", e.getMessage());
        }
    }

    private int pInt(String k, int def) {
        try { String v = get(k); return v == null || v.isBlank() ? def : Integer.parseInt(v.trim()); }
        catch (Exception e) { return def; }
    }

    private long pLong(String k, long def) {
        try { String v = get(k); return v == null || v.isBlank() ? def : Long.parseLong(v.trim()); }
        catch (Exception e) { return def; }
    }

    private double pDouble(String k, double def) {
        try { String v = get(k); return v == null || v.isBlank() ? def : Double.parseDouble(v.trim()); }
        catch (Exception e) { return def; }
    }

    private boolean pBool(String k, boolean def) {
        try { String v = get(k); return v == null || v.isBlank() ? def : Boolean.parseBoolean(v.trim()); }
        catch (Exception e) { return def; }
    }

    private String env(String key, String def) {
        String v = environment.getProperty(key);
        return v == null || v.isBlank() ? def : v;
    }

    /** 评估批量对比用的线程局部参数覆盖（仅当前线程生效，finally 必须 clear；不写 DB 不污染配置） */
    private static final ThreadLocal<Map<String, String>> OVERRIDE = new ThreadLocal<>();

    /** 设置线程局部参数覆盖（评估用），返回 this 便于 finally 中 clearOverride */
    public void putOverrides(Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) return;
        Map<String, String> cur = OVERRIDE.get();
        if (cur == null) {
            OVERRIDE.set(new HashMap<>(overrides));
        } else {
            cur.putAll(overrides);
        }
    }

    /** 清除线程局部参数覆盖（评估结束后必须调用） */
    public void clearOverride() {
        OVERRIDE.remove();
    }

    /**
     * 读取配置（线程局部覆盖 → 缓存 → 默认值）。
     *
     * <p>注：旧实现在此对 {@code *.apiKey} 做 RSA 透明解密（库里存密文、消费方拿明文）。落库那一面
     * 移除后，取值就是 yml/env 的原样值，不再存在密文形态。
     */
    public String get(String key) {
        Map<String, String> ov = OVERRIDE.get();
        if (ov != null && ov.containsKey(key)) return ov.get(key);
        String v = cache.get(key);
        return v == null ? defaults().getOrDefault(key, "") : v;
    }

    public double getDouble(String key) {
        try {
            return Double.parseDouble(get(key));
        } catch (Exception e) {
            return 0.3;
        }
    }

    public double getDouble(String key, double def) {
        String v = get(key);
        if (v == null || v.isBlank()) return def;
        try {
            return Double.parseDouble(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    public int getInt(String key) {
        try {
            return Integer.parseInt(get(key).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public int getInt(String key, int def) {
        String v = get(key);
        if (v == null || v.isBlank()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    public long getLong(String key) {
        try {
            return Long.parseLong(get(key).trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    public long getLong(String key, long def) {
        String v = get(key);
        if (v == null || v.isBlank()) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    public boolean getBoolean(String key) {
        try {
            return Boolean.parseBoolean(get(key).trim());
        } catch (Exception e) {
            return false;
        }
    }

    /** 全量配置（供配置界面展示；apiKey 脱敏） */
    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        // 分组需覆盖 defaults() 里所有前缀，否则该组配置永远回显不出来（前端只能退回硬编码默认值）
        String[] groups = {"chat", "vision", "embedding", "chunk", "parse", "upload", "retrieval", "rerank",
                "keyword", "context", "deepReasoning", "ratelimit",
                // 补漏：defaults() 中已有这些前缀，但此前未列入本数组，导致设置页永远只能回显前端硬编码默认值
                "images", "session", "cleanup", "eval", "imageFilter", "cache",
                // 工具调用（Function Calling）分组：tool.enabled / tool.knowledgeRetrieval.* / tool.artifact.enabled
                "tool",
                // MCP 外部工具分组：mcp.enabled / mcp.servers
                "mcp",
                // 技能（Skills）分组：skill.enabled / skill.dir / skill.inject* / skill.maxFileChars
                "skill",
                // 并行编排分组：agent.enabled / agent.subAgents / agent.topKPerAgent /
                // agent.digestEnabled / agent.autoRoute / agent.routeTimeoutMs
                // （按 "agent." 前缀自动收集，新增键无需改本数组，但必须在 defaults() 里有条目）
                "agent"};
        for (String g : groups) {
            Map<String, Object> items = new LinkedHashMap<>();
            for (Map.Entry<String, String> d : defaults().entrySet()) {
                if (!d.getKey().startsWith(g + ".")) continue;
                String shortKey = d.getKey().substring(g.length() + 1);
                String value = get(d.getKey());
                if (shortKey.contains("apiKey") && value.length() > 4) {
                    value = "****" + value.substring(value.length() - 4);
                }
                items.put(shortKey, Map.of(
                        "value", value,
                        "editable", EDITABLE.containsKey(d.getKey()),
                        "tier", TIER.getOrDefault(d.getKey(), 2)));
            }
            result.put(g, items);
        }
        return result;
    }
}
