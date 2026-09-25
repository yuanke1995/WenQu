package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.config.AppProperties;
import com.wisesoft.ai.mapper.ConfigMapper;
import com.wisesoft.ai.model.Config;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型配置服务：DB（c_ai_config）存储 + 内存缓存
 * <p>
 * - 启动时表空则从 yml/env 默认值灌入
 * - 可编辑白名单：chat.baseUrl / chat.apiKey / chat.completionsPath / chat.temperature /
 *   vision.model / vision.prompt 等（保存即生效；chat.model 已退役，全局兜底移除）
 * - chat.baseUrl / chat.apiKey / chat.completionsPath 支持跨厂商热切换（DynamicOpenAiChatModel
 *   每次请求校验配置指纹、变化即重建，配合 Redis 广播多实例同步生效）
 * - vision.baseUrl / vision.apiKey 可编辑（VisionService 每次调用动态读取，保存即生效）
 * - embedding.* 可编辑（DynamicEmbeddingModel 热切换）但向量无法跨模型迁移：保存检测到变化时
 *   先探测新配置可达性，通过后自动触发全量重嵌入（DocumentService.reembedAll：DROP 向量索引 →
 *   重建 schema → 全量重算）
 * - 敏感项（*.apiKey）RSA 加密入库（ConfigCryptoService）：启动自动迁移存量明文，读取透明解密
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ConfigService {

    /** 可编辑白名单（模型网关三要素已迁至「模型供应商」页：chat/vision/embedding/rerank 的
     *  baseUrl/apiKey/路径不再可编辑，模型键存引用 {providerId}/{modelId}，遗留纯模型名兼容；
     *  chat.model 已退役——全局兜底移除，模型解析链止于「会话覆盖 > 智能体 > 个人默认」） */
    private static final Map<String, String> EDITABLE = Map.ofEntries(
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
            Map.entry("vision.model", "视觉模型（引用 providerId/modelId，模型供应商页选择）"),
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
            Map.entry("rerank.model", "重排模型（引用 providerId/modelId，模型供应商页选择；遗留值走 rerank.baseUrl 本地服务）"),
            Map.entry("retrieval.vecThreshold", "检索：向量相似度下限(0~1，评估对比后可应用)"),
            Map.entry("retrieval.keywordLimit", "检索：关键词召回词数上限"),
            Map.entry("retrieval.vectorTopK", "检索：向量召回 topK（评估对比后可应用）"),
            Map.entry("retrieval.searchTimeoutMs", "检索：混合检索总超时(ms,含关键词并行)"),
            Map.entry("keyword.engine", "关键词引擎：mysql / meilisearch（切换前先探测并重建索引）"),
            Map.entry("keyword.baseUrl", "关键词引擎：Meilisearch 服务地址"),
            Map.entry("keyword.apiKey", "关键词引擎：Meilisearch master key（RSA 加密入库,留空回退环境变量 AI_MEILI_KEY）"),
            Map.entry("ratelimit.enabled", "接口限流总开关（Redis 固定窗口，按用户/IP）"),
            // 向量模型热切换（保存即生效 + 自动触发全量重嵌入，见 update）
            Map.entry("embedding.model", "向量模型（引用 providerId/modelId，供应商页选择；切换自动全量重嵌入）"),
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

    private final ConfigMapper configMapper;
    private final AppProperties properties;
    private final Environment environment;
    private final StringRedisTemplate redisTemplate;
    private final RedisProperties redisProperties;
    /** @Lazy 打破循环依赖：KeywordIndexService 构造依赖本类，仅引擎切换校验/重建时使用 */
    private final KeywordIndexService keywordIndexService;
    /** @Lazy 打破循环依赖：DocumentService 构造依赖本类，仅向量模型切换触发全量重嵌入时使用 */
    private final DocumentService documentService;
    /** 敏感项（*.apiKey）RSA 加解密 */
    private final ConfigCryptoService crypto;

    /** 供应商注册中心（向量路由解析；@Lazy 破循环：注册中心构造依赖本类） */
    private final ModelRegistryService modelRegistryService;

    /** 配置变更广播 channel（多实例同步：任意实例保存配置 → 其他实例订阅后重载缓存） */
    public static final String CONFIG_CHANNEL = "ai:config:changed";

    private volatile Map<String, String> cache = new HashMap<>();

    public ConfigService(ConfigMapper configMapper, AppProperties properties, Environment environment,
                         StringRedisTemplate redisTemplate, RedisProperties redisProperties,
                         @org.springframework.context.annotation.Lazy KeywordIndexService keywordIndexService,
                         @org.springframework.context.annotation.Lazy DocumentService documentService,
                         @org.springframework.context.annotation.Lazy ModelRegistryService modelRegistryService,
                         ConfigCryptoService crypto) {
        this.configMapper = configMapper;
        this.properties = properties;
        this.environment = environment;
        this.redisTemplate = redisTemplate;
        this.redisProperties = redisProperties;
        this.keywordIndexService = keywordIndexService;
        this.documentService = documentService;
        this.modelRegistryService = modelRegistryService;
        this.crypto = crypto;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        // 缺失的默认项自动补入（存量升级场景：新增 key 自动注入，不覆盖已有配置）
        ensureDefaults();
        reload();
        // 存量明文密钥（历史版本明文入库的 *.apiKey）自动迁移为 RSA 密文
        migratePlainSecrets();
        startRedisConfigSync();
        log.info("模型配置加载完成，共 {} 项", cache.size());
    }

    /**
     * 存量明文密钥迁移：*.apiKey 非 RSA: 前缀的值加密回写 DB 与缓存。
     * 读取兼容明文（get 透明解密对无前缀值原样返回），迁移只为尽快消除库中明文；
     * 多实例部署由 Redis 广播 reload 触发各自迁移，幂等。
     */
    private void migratePlainSecrets() {
        try {
            Map<String, String> encrypted = new HashMap<>();
            for (Map.Entry<String, String> e : cache.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (k.endsWith(".apiKey") && v != null && !v.isBlank() && !crypto.isEncrypted(v)) {
                    encrypted.put(k, crypto.encrypt(v));
                }
            }
            if (encrypted.isEmpty()) {
                return;
            }
            for (Map.Entry<String, String> e : encrypted.entrySet()) {
                Config c = configMapper.selectById(e.getKey());
                if (c != null) {
                    c.setConfigValue(e.getValue());
                    configMapper.updateById(c);
                }
            }
            Map<String, String> newCache = new HashMap<>(cache);
            newCache.putAll(encrypted);
            cache = newCache;
            syncProperties();
            log.info("[Config] 存量明文密钥已迁移为 RSA 加密存储: {}", encrypted.keySet());
        } catch (Exception e) {
            log.warn("[Config] 明文密钥加密迁移失败（不影响启动，读取兼容明文）: {}", e.getMessage());
        }
    }

    /** 全量重读 c_ai_config 进缓存（本地更新 / Redis 订阅通知 / schedule 包周期兜底均调用） */
    public void reload() {
        try {
            List<Config> all = configMapper.selectList(new LambdaQueryWrapper<Config>());
            Map<String, String> map = new HashMap<>();
            for (Config c : all) {
                map.put(c.getConfigKey(), c.getConfigValue());
            }
            cache = map;
            syncProperties();
        } catch (Exception e) {
            log.warn("[Config] 配置重载失败: {}", e.getMessage());
        }
    }

    /**
     * 多实例配置同步：daemon 线程订阅 Redis channel，任意实例保存配置后广播，
     * 本实例收到即全量重载缓存（保存即生效跨实例成立）。Redis 不可用时仅告警不影响启动。
     * 订阅线程是永久阻塞的事件监听（不适合进线程池）；周期兜底 reload
     * （订阅断线期间错过的变更由轮询补齐，每 5 分钟）已移至 schedule 包 ScheduleCenter。
     */
    private void startRedisConfigSync() {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = new Jedis(redisProperties.getHost(), redisProperties.getPort(), 5000)) {
                    if (redisProperties.getPassword() != null && !redisProperties.getPassword().isBlank()) {
                        jedis.auth(redisProperties.getPassword());
                    }
                    jedis.subscribe(new JedisPubSub() {
                        @Override
                        public void onMessage(String channel, String message) {
                            reload();
                            log.info("[Config] 收到配置变更广播，已刷新缓存");
                        }
                    }, CONFIG_CHANNEL);
                } catch (Exception e) {
                    log.warn("[Config] Redis 配置同步订阅中断，5s 后重连: {}", e.getMessage());
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "config-redis-sync");
        t.setDaemon(true);
        t.start();
    }

    /** 本地保存后广播（其他实例订阅刷新；Redis 异常不影响保存结果） */
    private void publishConfigChanged() {
        try {
            redisTemplate.convertAndSend(CONFIG_CHANNEL, "changed");
        } catch (Exception e) {
            log.debug("[Config] 配置变更广播失败: {}", e.getMessage());
        }
    }

    /** 遍历 defaults()，DB 中缺失的 key 自动灌入默认值（单条失败不影响其余） */
    private void ensureDefaults() {
        for (Map.Entry<String, String> e : defaults().entrySet()) {
            try {
                Long cnt = configMapper.selectCount(new LambdaQueryWrapper<Config>()
                        .eq(Config::getConfigKey, e.getKey()));
                if (cnt == null || cnt == 0) {
                    Config c = new Config();
                    c.setConfigKey(e.getKey());
                    // 敏感项默认值灌入即加密（RSA: 前缀密文）
                    c.setConfigValue(e.getKey().endsWith(".apiKey") ? crypto.encrypt(e.getValue()) : e.getValue());
                    c.setRemark(EDITABLE.getOrDefault(e.getKey(), "只读配置"));
                    configMapper.insert(c);
                }
            } catch (Exception ex) {
                log.warn("配置默认值灌入失败: {} error={}", e.getKey(), ex.getMessage());
            }
        }
    }

    /** 从 yml/env 读取默认值 */
    private Map<String, String> defaults() {
        Map<String, String> d = new LinkedHashMap<>();
        // chat.model 已退役（全局兜底移除）：不再注入默认值，存量库中的旧行成为孤儿数据（无读取方）
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
        d.put("eval.judgeModel", "");              // 评判用独立模型（留空=跳过 LLM 自动评判）
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
     * 敏感项（*.apiKey）RSA 密文在此透明解密：缓存/DB 存密文，消费方拿明文（无前缀的历史明文原样返回，兼容存量）。
     */
    public String get(String key) {
        Map<String, String> ov = OVERRIDE.get();
        if (ov != null && ov.containsKey(key)) return ov.get(key);
        String v = cache.get(key);
        if (v == null) v = defaults().getOrDefault(key, "");
        return key.endsWith(".apiKey") ? crypto.decrypt(v) : v;
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

    /** 保存可编辑项（白名单校验）→ 写 DB + 刷新缓存 */
    public Map<String, String> update(Map<String, Map<String, String>> groups) {
        Map<String, String> updates = new HashMap<>();
        if (groups != null) {
            for (Map.Entry<String, Map<String, String>> g : groups.entrySet()) {
                String prefix = g.getKey() + ".";
                for (Map.Entry<String, String> kv : g.getValue().entrySet()) {
                    String fullKey = prefix + kv.getKey();
                    if (EDITABLE.containsKey(fullKey)) {
                        updates.put(fullKey, kv.getValue() == null ? "" : kv.getValue().trim());
                    }
                }
            }
        }
        String temp = updates.get("chat.temperature");
        if (temp != null && !temp.isBlank()) {
            double t = Double.parseDouble(temp);
            if (t < 0 || t > 2) throw new IllegalArgumentException("temperature 需在 0~2 之间");
        }
        // LLM 网关地址校验：http(s) 开头、去尾部斜杠。路径拼接容错（…/v1、…/v4 等版本段、
        // 完整端点粘贴）统一由 DynamicOpenAiChatModel.normalize 处理，此处不做改写，避免双处逻辑漂移
        String cb = updates.get("chat.baseUrl");
        if (cb != null && !cb.isBlank()) {
            String url = cb.trim();
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new IllegalArgumentException("chat.baseUrl 需以 http:// 或 https:// 开头");
            }
            updates.put("chat.baseUrl", url);
        }
        // 补全路径校验：留空（用默认 /v1/chat/completions）或以 / 开头
        String cp = updates.get("chat.completionsPath");
        if (cp != null && !cp.isBlank() && !cp.trim().startsWith("/")) {
            throw new IllegalArgumentException("chat.completionsPath 需以 / 开头（如 /v1/chat/completions）");
        }
        // 检索权重校验：必须是 0~1 的数字（防非法值导致检索排序异常）
        for (String wKey : new String[]{"retrieval.vectorWeight", "retrieval.keywordWeight", "retrieval.vecThreshold", "context.safetyFactor", "chunk.structuralRatio"}) {
            String w = updates.get(wKey);
            if (w != null && !w.isBlank()) {
                try {
                    double v = Double.parseDouble(w);
                    if (v < 0 || v > 1) throw new IllegalArgumentException(wKey + " 需在 0~1 之间");
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(wKey + " 必须是数字");
                }
            }
        }
        // 上下文长度参数校验：必须是非负整数
        for (String iKey : new String[]{"context.defaultWindowTokens", "context.costCapTokens", "context.maxOutputTokens",
                "context.historyMaxTokens", "context.historyPerMsgChars", "context.snippetWindowChars", "context.maxContextHits",
                "chat.historyRounds", "chat.remainTokenFloor", "chat.truncateFallbackChars"}) {
            String v = updates.get(iKey);
            if (v != null && !v.isBlank()) {
                try {
                    if (Integer.parseInt(v.trim()) < 0) throw new IllegalArgumentException(iKey + " 不能为负数");
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(iKey + " 必须是整数");
                }
            }
        }
        // 检索/重排数值参数校验：正整数（minHits 允许 0=从不触发）
        for (String iKey : new String[]{"retrieval.keywordLimit", "retrieval.vectorTopK", "rerank.maxHits",
                "retrieval.searchTimeoutMs"}) {
            String v = updates.get(iKey);
            if (v != null && !v.isBlank()) {
                try {
                    if (Integer.parseInt(v.trim()) < 1) throw new IllegalArgumentException(iKey + " 需 ≥1");
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(iKey + " 必须是整数");
                }
            }
        }
        // 深度思考参数校验
        String mode = updates.get("deepReasoning.thinkingMode");
        if (mode != null && !mode.isBlank() && !"model".equals(mode) && !"prompt".equals(mode)) {
            throw new IllegalArgumentException("deepReasoning.thinkingMode 仅允许 model / prompt");
        }
        for (String iKey : new String[]{"deepReasoning.maxSubQueries", "deepReasoning.timeoutMillis", "deepReasoning.maxThinkingTokens",
                "deepReasoning.maxThinkingChars", "deepReasoning.injectThinkingMaxChars", "deepReasoning.injectKeywordsMax"}) {
            String v = updates.get(iKey);
            if (v != null && !v.isBlank()) {
                try {
                    if (Integer.parseInt(v.trim()) < 0) throw new IllegalArgumentException(iKey + " 不能为负数");
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(iKey + " 必须是整数");
                }
            }
        }
        for (String bKey : new String[]{"deepReasoning.enabled", "deepReasoning.enableThinking", "deepReasoning.multiRetrieval",
                "deepReasoning.injectThinking", "deepReasoning.injectKeywords", "deepReasoning.autoRoute",
                "chunk.structural"}) {
            String v = updates.get(bKey);
            if (v != null && !v.isBlank() && !"true".equalsIgnoreCase(v) && !"false".equalsIgnoreCase(v)) {
                throw new IllegalArgumentException(bKey + " 仅允许 true / false");
            }
        }
        // 重排参数校验
        String rb = updates.get("rerank.enabled");
        if (rb != null && !rb.isBlank() && !"true".equalsIgnoreCase(rb) && !"false".equalsIgnoreCase(rb)) {
            throw new IllegalArgumentException("rerank.enabled 仅允许 true / false");
        }
        // 关键词引擎校验
        String ke = updates.get("keyword.engine");
        if (ke != null && !ke.isBlank() && !"mysql".equalsIgnoreCase(ke) && !"meilisearch".equalsIgnoreCase(ke)) {
            throw new IllegalArgumentException("keyword.engine 仅允许 mysql / meilisearch");
        }
        // 切换到 meilisearch：保存前强制探测服务可用性，不可用则阻止保存（避免切到不可用的空索引）
        if (ke != null && "meilisearch".equalsIgnoreCase(ke)) {
            if (!keywordIndexService.checkAvailable()) {
                String reason = keywordIndexService.debugUnavailableReason();
                throw new IllegalArgumentException("Meilisearch 服务不可用（" + (reason == null ? "探测失败" : reason)
                        + "），请先启动 Meilisearch（docker compose 或本地）再切换");
            }
        }
        String kt = updates.get("keyword.timeoutMillis");
        if (kt != null && !kt.isBlank()) {
            try {
                if (Integer.parseInt(kt.trim()) < 200) throw new IllegalArgumentException("keyword.timeoutMillis 不能小于 200");
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("keyword.timeoutMillis 必须是整数");
            }
        }
        // 解析参数校验：非负整数（0 表示不限制）
        for (String iKey : new String[]{"chunk.maxChunks", "chunk.maxImages", "vision.concurrency",
                "ratelimit.chatPerMinute", "ratelimit.uploadPerMinute",
                "parse.ocrMinText", "parse.embedRetryCount"}) {
            String v = updates.get(iKey);
            if (v != null && !v.isBlank()) {
                try {
                    if (Integer.parseInt(v.trim()) < 0) throw new IllegalArgumentException(iKey + " 不能为负数");
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(iKey + " 必须是整数");
                }
            }
        }
        // 并发数校验：必须 ≥1（0 会让解析/图片识别线程池无工作线程，任务永久排队）
        for (String iKey : new String[]{"parse.concurrency", "vision.userImageConcurrency"}) {
            String v = updates.get(iKey);
            if (v != null && !v.isBlank()) {
                try {
                    if (Integer.parseInt(v.trim()) < 1) throw new IllegalArgumentException(iKey + " 需 ≥1");
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(iKey + " 必须是整数");
                }
            }
        }
        // 上传上限校验：必须 ≥1MB 且 ≤1GB（物理上限由 multipart 兜底）
        String uf = updates.get("upload.maxFileSize");
        if (uf != null && !uf.isBlank()) {
            try {
                long v = Long.parseLong(uf.trim());
                if (v < 1024 * 1024 || v > 1024L * 1024 * 1024) {
                    throw new IllegalArgumentException("upload.maxFileSize 需在 1MB ~ 1GB 之间");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("upload.maxFileSize 必须是整数(字节)");
            }
        }

        // 掩码回写保护：snapshot 对 *.apiKey 脱敏为 "****后4位"，前端未修改 key 时会把掩码原样提交；
        // 掩码值（**** 开头）一律跳过更新，避免覆盖库中真实 key（真实 master key 不可能以 **** 开头）
        updates.entrySet().removeIf(kv ->
                kv.getKey().endsWith(".apiKey") && kv.getValue() != null && kv.getValue().startsWith("****"));

        // 视觉模型网关地址校验：http(s) 开头、去尾部斜杠（路径容错由 VisionService 拼接处理）
        String vb = updates.get("vision.baseUrl");
        if (vb != null && !vb.isBlank()) {
            String url = vb.trim();
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new IllegalArgumentException("vision.baseUrl 需以 http:// 或 https:// 开头");
            }
            updates.put("vision.baseUrl", url);
        }

        // 向量模型热切换：任一 embedding.* 提交时，比较新旧「向量路由指纹」（引用解析到供应商网关后的
        // baseUrl|path|model|key）——迁移把配置值改写为引用时解析结果不变、不会误触发全量重嵌入，
        // 模型/网关真变时才联动。用「新路由」真实探测一次 embedding（校验地址/Key/模型名可达；
        // 失败拒绝保存——避免配错后自动触发的全量重嵌任务必然失败）。
        // 向量无法跨模型迁移（向量空间不兼容），真正切换后自动触发全量重嵌入。
        boolean embeddingChanged = false;
        if (updates.keySet().stream().anyMatch(k -> k.startsWith("embedding."))) {
            String newModel = updates.getOrDefault("embedding.model", get("embedding.model")).trim();
            String newBase = updates.getOrDefault("embedding.baseUrl", get("embedding.baseUrl")).trim();
            // 未提交新 key（掩码已过滤）时回退当前值（get 透明解密为明文）
            String newKey = updates.getOrDefault("embedding.apiKey", get("embedding.apiKey"));
            String newPath = updates.getOrDefault("embedding.embeddingsPath", "").trim();
            ModelRegistryService.ModelRoute oldRoute = modelRegistryService.embeddingRoute(
                    get("embedding.model"), get("embedding.baseUrl"), get("embedding.apiKey"), get("embedding.embeddingsPath"));
            ModelRegistryService.ModelRoute newRoute = modelRegistryService.embeddingRoute(newModel, newBase, newKey, newPath);
            embeddingChanged = !oldRoute.embeddingFingerprint().equals(newRoute.embeddingFingerprint());
            if (embeddingChanged) {
                int probeDim;
                try {
                    probeDim = DynamicEmbeddingModel.probe(newRoute.baseUrl(), newRoute.apiKey(),
                            newRoute.modelId(), newRoute.embeddingsPath());
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    throw new IllegalArgumentException("新向量模型探测失败（" + msg
                            + "），请检查网关地址/API Key/模型名；向量模型保存即触发全量重嵌入，配置错误将被拒绝");
                }
                // 维度护栏：探测维度非法直接拒绝（否则重建索引时 schema 维度非法，向量路整体不可用）
                if (probeDim <= 0) {
                    throw new IllegalArgumentException("新向量模型返回维度非法(" + probeDim
                            + ")，疑似网关返回格式不兼容 OpenAI embeddings，已拒绝保存");
                }
                int recordedDim = getInt("embedding.dimensions", 0);
                log.info("[Config] 新向量模型探测通过，维度 {}（当前索引记录维度 {}）：{}", probeDim, recordedDim,
                        recordedDim > 0 && recordedDim != probeDim
                                ? "维度变化，索引 schema 必须重建" : "维度未变，但跨模型向量空间不兼容，仍需全量重嵌入");
            }
        }

        // 敏感 key RSA 加密入库：明文→密文（已加密值原样保留；空值不加密直接存空）
        for (Map.Entry<String, String> kv : updates.entrySet()) {
            String v = kv.getValue();
            if (kv.getKey().endsWith(".apiKey") && v != null && !v.isBlank() && !crypto.isEncrypted(v)) {
                kv.setValue(crypto.encrypt(v));
            }
        }

        for (Map.Entry<String, String> kv : updates.entrySet()) {
            Config c = configMapper.selectById(kv.getKey());
            if (c == null) {
                c = new Config();
                c.setConfigKey(kv.getKey());
                c.setConfigValue(kv.getValue());
                c.setRemark(EDITABLE.get(kv.getKey()));
                configMapper.insert(c);
            } else {
                c.setConfigValue(kv.getValue());
                configMapper.updateById(c);
            }
        }
        // 引擎切换检测：仅当 keyword.engine 值真正变化（如 mysql→meilisearch）才全量重建。
        // 必须在刷新缓存前取旧值——前端保存总是提交当前 engine 值，若无条件重建，
        // 每次"改任意配置保存"都会误触发全量灌库（资源浪费 + 日志误导）
        boolean engineSwitchedToMeili = false;
        if (updates.containsKey("keyword.engine") && "meilisearch".equalsIgnoreCase(updates.get("keyword.engine"))) {
            String oldEngine = get("keyword.engine"); // 刷新前 cache 仍是旧值
            engineSwitchedToMeili = oldEngine == null || !"meilisearch".equalsIgnoreCase(oldEngine);
        }
        // 刷新缓存
        Map<String, String> newCache = new HashMap<>(cache);
        newCache.putAll(updates);
        cache = newCache;
        syncProperties();
        log.info("模型配置已更新: {}", updates.keySet());
        // 广播其他实例刷新（多副本部署配置同步）
        publishConfigChanged();
        // 向量模型切换：自动触发全量重嵌入（异步后台；期间向量检索降级关键词路，不影响服务可用）。
        // 多副本部署：Redis 索引共享，由保存配置的实例单点执行即可，其余实例 DynamicEmbeddingModel
        // 自行重建客户端后与新索引自然对齐
        if (embeddingChanged) {
            documentService.reembedAllAsync();
            log.info("[Config] 向量模型已切换，自动触发全量重嵌入");
        }
        // 自动全量重建（仅真实切换 mysql→meilisearch 时；reindexAll 内部有防重入与可用性检查）
        if (engineSwitchedToMeili) {
            try {
                keywordIndexService.reindexAll();
                log.info("[Config] 关键词引擎已切换至 Meilisearch，自动触发索引全量重建");
            } catch (Exception e) {
                log.warn("[Config] 自动触发索引重建失败（可稍后手动调 /api/ai/search-index/reindex）: {}", e.getMessage());
            }
        }
        return updates;
    }

    /**
     * 将指定分组恢复为出厂默认值（defaults() 值写库 + 刷新缓存 + Redis 广播）。
     * <ul>
     *   <li>白名单分组与 snapshot 对齐，但<b>排除 embedding</b>：向量模型恢复会触发全量重嵌入，
     *       必须走设置页正常流程（探测→确认）；</li>
     *   <li><b>跳过 *.apiKey</b>：密钥以 RSA 加密存于 DB，恢复默认不得清空用户已配置的模型密钥
     *       （env 回退值可能为空导致模型不可用）；</li>
     *   <li>keyword.engine 不触发索引联动（恢复 mysql 后关键词走 MySQL LIKE，Meili 索引可留待后续重建）。</li>
     * </ul>
     *
     * @param groups 待恢复分组（chat/vision/chunk/parse/upload/retrieval/rerank/keyword/context/deepReasoning/ratelimit）
     * @return 实际恢复的键值
     */
    public Map<String, String> resetDefaults(Collection<String> groups) {
        Set<String> allowed = new HashSet<>(List.of(
                "chat", "vision", "chunk", "parse", "upload", "retrieval", "rerank",
                "keyword", "context", "deepReasoning", "ratelimit"));
        Set<String> targets = new HashSet<>();
        for (String g : groups) {
            if (g == null || g.isBlank() || !allowed.contains(g)) {
                throw new IllegalArgumentException("不支持恢复默认的分组: " + g);
            }
            targets.add(g);
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("请至少指定一个分组");
        }
        Map<String, String> defs = defaults();
        Map<String, String> next = new HashMap<>(cache);
        Map<String, String> reset = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : defs.entrySet()) {
            String k = e.getKey();
            int dot = k.indexOf('.');
            if (dot <= 0 || !targets.contains(k.substring(0, dot))) continue;
            if (k.endsWith(".apiKey")) continue; // 密钥不随"恢复默认"清空
            String v = e.getValue();
            try {
                Config c = configMapper.selectById(k);
                if (c == null) {
                    c = new Config();
                    c.setConfigKey(k);
                    c.setConfigValue(v);
                    c.setRemark(EDITABLE.getOrDefault(k, "只读配置"));
                    configMapper.insert(c);
                } else {
                    c.setConfigValue(v);
                    configMapper.updateById(c);
                }
            } catch (Exception ex) {
                log.warn("[Config] 恢复默认写库失败 {}: {}", k, ex.getMessage());
                continue;
            }
            next.put(k, v);
            reset.put(k, v);
        }
        cache = next;
        syncProperties();
        publishConfigChanged();
        log.info("[Config] 分组恢复默认完成: {}（{} 项）", targets, reset.size());
        return reset;
    }

    /**
     * 系统内部回写（不经 EDITABLE 白名单）：供运行流程记录"既成事实"型配置，
     * 当前唯一用途是全量重嵌入成功后回写 embedding.dimensions（当前索引维度）。
     * 与 update() 的区别：不做业务校验、不加密、不触发重嵌入/重建索引等联动，
     * 只落库 + 刷新本地缓存 + 广播其他副本。失败仅告警（记录性数据，不阻断主流程）。
     */
    public void putInternal(String key, String value) {
        try {
            Config c = configMapper.selectById(key);
            if (c == null) {
                c = new Config();
                c.setConfigKey(key);
                c.setConfigValue(value);
                c.setRemark("只读配置");
                configMapper.insert(c);
            } else {
                c.setConfigValue(value);
                configMapper.updateById(c);
            }
            Map<String, String> newCache = new HashMap<>(cache);
            newCache.put(key, value);
            cache = newCache;
            syncProperties();
            publishConfigChanged();
            log.info("[Config] 系统内部记录已更新: {}={}", key, value);
        } catch (Exception e) {
            log.warn("[Config] 系统内部记录写入失败: {}={} error={}", key, value, e.getMessage());
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
