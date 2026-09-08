package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.config.AiAppProperties;
import com.wisesoft.ai.mapper.AiConfigMapper;
import com.wisesoft.ai.model.AiConfig;
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
 * - 可编辑白名单：chat.model / chat.baseUrl / chat.apiKey / chat.completionsPath / chat.temperature /
 *   vision.model / vision.prompt 等（保存即生效）
 * - chat.baseUrl / chat.apiKey / chat.completionsPath 支持跨厂商热切换（DynamicOpenAiChatModel
 *   每次请求校验配置指纹、变化即重建，配合 Redis 广播多实例同步生效）
 * - vision.baseUrl / vision.apiKey 可编辑（VisionService 每次调用动态读取，保存即生效）
 * - embedding.* 可编辑（DynamicEmbeddingModel 热切换）但向量无法跨模型迁移：保存检测到变化时
 *   先探测新配置可达性，通过后自动触发全量重嵌入（DocumentService.reembedAll：DROP 向量索引 →
 *   重建 schema → 全量重算 → 清空语义缓存）
 * - 敏感项（*.apiKey）RSA 加密入库（ConfigCryptoService）：启动自动迁移存量明文，读取透明解密
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
            Map.entry("parse.embedRetryCount", "向量化批次失败自动重试次数(0=不重试)"),
            Map.entry("parse.concurrency", "文档解析并发数(保存即生效)"),
            Map.entry("parse.ocrMinText", "PDF 扫描件判定阈值(页文本少于该长度触发 OCR)"),
            Map.entry("upload.maxFileSize", "文档上传大小上限(字节,保存即生效)"),
            Map.entry("retrieval.vectorWeight", "混合检索：向量权重(0~1)"),
            Map.entry("retrieval.keywordWeight", "混合检索：关键词权重(0~1)"),
            Map.entry("retrieval.titleBonus", "混合检索：标题命中奖励(0~1)"),
            Map.entry("retrieval.rewriteTimeoutMs", "查询改写超时(毫秒,默认5000；本地模型慢可调大)"),
            Map.entry("retrieval.rewriteFallbackMinHits", "改写回退：改写后检索命中块数低于此值→回退用原问题重检(0=关闭回退)"),
            Map.entry("retrieval.rewriteFallbackWeakScore", "改写回退：改写后最高命中融合分低于此值→回退用原问题重检(0=关闭该判据)"),
            Map.entry("retrieval.refDetectEnabled", "解析时识别知识块交叉引用(详见/参见X节,改后需重解析)"),
            Map.entry("retrieval.refDetectMention", "识别无动词提及(如 4.1.2 所述/《数据字典》/XX章节,仅精确匹配)"),
            Map.entry("retrieval.refExpandEnabled", "检索时关联块扩散+父章节带出总开关(保存即生效)"),
            Map.entry("retrieval.refExpandMaxHits", "关联扩散块数量上限(默认3)"),
            Map.entry("retrieval.refExpandMaxTokens", "关联扩散块token汇总上限(默认800)"),
            Map.entry("retrieval.refExpandIncludeIncoming", "是否扩散入边(引用本块的块,默认关)"),
            Map.entry("retrieval.refExpandParentEnabled", "命中子章节时带出父章节上下文(默认开)"),
            Map.entry("retrieval.refExpandParentMode", "父章节内容模式: title_only/summary/full"),
            Map.entry("retrieval.refExpandParentMaxLevels", "父章节向上带出级数(默认2)"),
            Map.entry("retrieval.refExpandParentSummaryChars", "父章节summary模式截取字符数(默认200)"),
            Map.entry("retrieval.refExpandFuzzyName", "章节名弱匹配(contains)开关(默认开)"),
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
            Map.entry("deepReasoning.thinkingMode", "深度思考：思考模式(model/prompt)"),
            Map.entry("deepReasoning.enableThinking", "深度思考：透传 enable_thinking"),
            Map.entry("deepReasoning.prompt", "深度思考：思考引导提示词"),
            Map.entry("deepReasoning.searchTag", "深度思考：检索计划标签名"),
            Map.entry("deepReasoning.maxSubQueries", "深度思考：最大子问题数"),
            Map.entry("deepReasoning.multiRetrieval", "深度思考：多路并行检索开关"),
            Map.entry("deepReasoning.timeoutMillis", "深度思考：思考阶段超时(ms)"),
            Map.entry("deepReasoning.maxThinkingTokens", "深度思考：思考输出上限(token,0=不设)"),
            Map.entry("rerank.enabled", "重排：是否启用（需先启动本地 reranker 服务）"),
            Map.entry("rerank.baseUrl", "重排：服务地址"),
            Map.entry("rerank.model", "重排：模型名"),
            Map.entry("rerank.timeoutMillis", "重排：单次超时(ms)"),
            Map.entry("retrieval.vecThreshold", "检索：向量相似度下限(0~1，评估对比后可应用)"),
            Map.entry("retrieval.keywordLimit", "检索：关键词召回词数上限"),
            Map.entry("retrieval.vectorTopK", "检索：向量召回 topK（评估对比后可应用）"),
            Map.entry("retrieval.fusionMode", "检索：双路融合方式 sum=加权和(默认,含标题/位置奖励) / rrf=倒数排名融合(实验,按名次,可用评估页对比)"),
            Map.entry("retrieval.searchTimeoutMs", "检索：混合检索总超时(ms,含关键词并行)"),
            Map.entry("retrieval.positionBonus", "检索：文档首块位置奖励(0~1)"),
            Map.entry("rerank.minHits", "重排：触发候选数下限（评估对比后可应用）"),
            Map.entry("rerank.maxHits", "重排：触发候选数上限（评估对比后可应用）"),
            Map.entry("rerank.failCooldownMs", "重排：服务失败后冷却时间(ms)，冷却期内不再探测/调用"),
            Map.entry("keyword.engine", "关键词引擎：mysql / meilisearch（切换前先探测并重建索引）"),
            Map.entry("keyword.baseUrl", "关键词引擎：Meilisearch 服务地址"),
            Map.entry("keyword.apiKey", "关键词引擎：Meilisearch master key（RSA 加密入库,留空回退环境变量 AI_MEILI_KEY）"),
            Map.entry("keyword.timeoutMillis", "关键词引擎：单次超时(ms)"),
            Map.entry("ratelimit.enabled", "接口限流总开关（Redis 固定窗口，按用户/IP）"),
            Map.entry("ratelimit.chatPerMinute", "问答限频：次/分钟/用户(0=不限)"),
            Map.entry("ratelimit.uploadPerMinute", "上传限频：次/分钟/用户(0=不限)"),
            // 向量模型热切换（保存即生效 + 自动触发全量重嵌入，见 update）
            Map.entry("embedding.model", "向量模型名(保存后自动全量重嵌入,期间降级关键词检索)"),
            Map.entry("embedding.baseUrl", "向量模型网关地址(OpenAI 兼容)"),
            Map.entry("embedding.apiKey", "向量模型 API Key(RSA 加密入库)"),
            Map.entry("embedding.embeddingsPath", "向量化路径(默认 /v1/embeddings;智谱 /v4、千帆 /v2)"),
            Map.entry("semanticCache.enabled", "语义缓存总开关（相似问题直接复用历史回答）"),
            Map.entry("semanticCache.threshold", "语义缓存相似度阈值(0.8~1，默认0.96)"),
            Map.entry("semanticCache.maxEntries", "语义缓存最大条数(超出淘汰最早，默认500)"),
            Map.entry("eval.judgeEnabled", "自动体检：LLM 评判检索充分性（对每个 case 判 top 命中是否足以回答；增加体检耗时与一次调用/case）"));

    private final AiConfigMapper configMapper;
    private final AiAppProperties properties;
    private final Environment environment;
    private final StringRedisTemplate redisTemplate;
    private final RedisProperties redisProperties;
    /** @Lazy 打破循环依赖：KeywordIndexService 构造依赖本类，仅引擎切换校验/重建时使用 */
    private final KeywordIndexService keywordIndexService;
    /** @Lazy 打破循环依赖：DocumentService 构造依赖本类，仅向量模型切换触发全量重嵌入时使用 */
    private final DocumentService documentService;
    /** 敏感项（*.apiKey）RSA 加解密 */
    private final ConfigCryptoService crypto;

    /** 配置变更广播 channel（多实例同步：任意实例保存配置 → 其他实例订阅后重载缓存） */
    public static final String CONFIG_CHANNEL = "ai:config:changed";

    private volatile Map<String, String> cache = new HashMap<>();

    public ConfigService(AiConfigMapper configMapper, AiAppProperties properties, Environment environment,
                         StringRedisTemplate redisTemplate, RedisProperties redisProperties,
                         @org.springframework.context.annotation.Lazy KeywordIndexService keywordIndexService,
                         @org.springframework.context.annotation.Lazy DocumentService documentService,
                         ConfigCryptoService crypto) {
        this.configMapper = configMapper;
        this.properties = properties;
        this.environment = environment;
        this.redisTemplate = redisTemplate;
        this.redisProperties = redisProperties;
        this.keywordIndexService = keywordIndexService;
        this.documentService = documentService;
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
                AiConfig c = configMapper.selectById(e.getKey());
                if (c != null) {
                    c.setConfigValue(e.getValue());
                    configMapper.updateById(c);
                }
            }
            Map<String, String> newCache = new HashMap<>(cache);
            newCache.putAll(encrypted);
            cache = newCache;
            log.info("[Config] 存量明文密钥已迁移为 RSA 加密存储: {}", encrypted.keySet());
        } catch (Exception e) {
            log.warn("[Config] 明文密钥加密迁移失败（不影响启动，读取兼容明文）: {}", e.getMessage());
        }
    }

    /** 全量重读 c_ai_config 进缓存（本地更新 / Redis 订阅通知 / schedule 包周期兜底均调用） */
    public void reload() {
        try {
            List<AiConfig> all = configMapper.selectList(new LambdaQueryWrapper<AiConfig>());
            Map<String, String> map = new HashMap<>();
            for (AiConfig c : all) {
                map.put(c.getConfigKey(), c.getConfigValue());
            }
            cache = map;
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
                Long cnt = configMapper.selectCount(new LambdaQueryWrapper<AiConfig>()
                        .eq(AiConfig::getConfigKey, e.getKey()));
                if (cnt == null || cnt == 0) {
                    AiConfig c = new AiConfig();
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
        d.put("retrieval.titleBonus", String.valueOf(properties.getRetrieval().getTitleBonus()));
        d.put("retrieval.rewriteTimeoutMs", String.valueOf(properties.getQueryRewrite().getTimeoutMillis())); // 查询改写超时(ms)
        d.put("retrieval.rewriteFallbackMinHits", "2");    // 改写回退：命中块数下限（0=关闭回退）
        d.put("retrieval.rewriteFallbackWeakScore", "0.2"); // 改写回退：最高融合分下限（0=关闭该判据）
        d.put("retrieval.refDetectEnabled", "true");      // 解析时引用识别
        d.put("retrieval.refDetectMention", "true");      // 无动词提及识别（如 4.1.2 所述/《数据字典》）
        d.put("retrieval.refExpandEnabled", "true");      // 检索时关联扩散+父章节带出
        d.put("retrieval.refExpandMaxHits", "3");         // 扩散块数量上限
        d.put("retrieval.refExpandMaxTokens", "800");     // 扩散块 token 上限
        d.put("retrieval.refExpandIncludeIncoming", "false"); // 入边扩散（默认关）
        d.put("retrieval.refExpandParentEnabled", "true");   // 父章节带出
        d.put("retrieval.refExpandParentMode", "summary");   // summary/title_only/full
        d.put("retrieval.refExpandParentMaxLevels", "2");    // 父块级数
        d.put("retrieval.refExpandParentSummaryChars", "200"); // summary 截取字符
        d.put("retrieval.refExpandFuzzyName", "true");       // 章节名弱匹配
        d.put("rerank.enabled", String.valueOf(properties.getRetrieval().getRerank().isEnabled()));
        d.put("rerank.baseUrl", properties.getRetrieval().getRerank().getBaseUrl());
        d.put("rerank.model", properties.getRetrieval().getRerank().getModel());
        d.put("rerank.timeoutMillis", String.valueOf(properties.getRetrieval().getRerank().getTimeoutMillis()));
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
        // 检索行为参数（原硬编码收口，设置页可调、保存即生效）
        d.put("retrieval.vecThreshold", "0.3");            // 向量相似度归一化基准/下限
        d.put("retrieval.vectorTopK", "15");               // 向量召回 topK（调优/评估扫参用，下限 1）
        d.put("retrieval.fusionMode", "sum");              // 双路融合：sum=加权和 / rrf=倒数排名（实验）
        d.put("retrieval.keywordLimit", "20");             // 关键词召回上限
        d.put("retrieval.keywordTimeoutMs", "800");        // 关键词检索超时
        d.put("retrieval.searchTimeoutMs", "8000");        // 混合检索总超时
        d.put("retrieval.positionBonus", "0.03");          // 首块位置奖励
        d.put("retrieval.sectionBonus", "0.01");           // 前段位置奖励
        d.put("retrieval.keywordMaxTerms", "6");           // 关键词提取主词元上限
        d.put("retrieval.keywordMaxTotal", "12");          // 关键词提取总词元上限
        d.put("retrieval.vectorTopK", "15");               // 向量检索召回上限（评估批量对比可覆盖）
        // 重排行为参数
        d.put("rerank.minHits", "6");                      // 触发重排的候选下限
        d.put("rerank.maxHits", "15");                     // 触发重排的候选上限
        d.put("rerank.failCooldownMs", "60000");           // 重排失败后冷却再探测
        // 关键词召回引擎（mysql=LIKE；meilisearch=外部索引，中文分词+相关度；index 只走 yml 不入库）
        d.put("keyword.engine", properties.getKeyword().getEngine());
        d.put("keyword.baseUrl", properties.getKeyword().getBaseUrl());
        d.put("keyword.apiKey", properties.getKeyword().getApiKey());   // master key RSA 加密入库（设置页可改，改后客户端自动重建）；未配置时回退 env AI_MEILI_KEY
        d.put("keyword.timeoutMillis", String.valueOf(properties.getKeyword().getTimeoutMillis()));
        d.put("keyword.failCooldownMs", "60000");          // Meilisearch 失败后冷却再探测
        d.put("keyword.reconcileOnStartup", "true");       // 启动索引对账：漂移自动重建（多副本部署可置 false 由运维单点执行）
        d.put("keyword.reconcileIntervalMs", "3600000");   // 周期索引对账间隔(ms,≤0=暂停)：按(id,hash)精确比对并定向修复漂移（schedule 包调度）
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
        // 语义缓存（相似问题直出历史答案；知识库变更时整体失效）
        d.put("semanticCache.enabled", "true");
        d.put("semanticCache.threshold", "0.96");
        d.put("semanticCache.maxEntries", "500");
        d.put("eval.judgeEnabled", "false");   // 自动体检 LLM 评判（默认关，评估集大时耗时/成本明显）
        return d;
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
        // 校验：仅当本次提交包含 chat.model 时才要求非空（避免只想改检索权重等其他项时被阻塞）
        String model = updates.get("chat.model");
        if (updates.containsKey("chat.model") && (model == null || model.isBlank())) {
            throw new IllegalArgumentException("chat.model 不能为空");
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
        for (String wKey : new String[]{"retrieval.vectorWeight", "retrieval.keywordWeight", "retrieval.titleBonus", "retrieval.vecThreshold", "retrieval.positionBonus", "context.safetyFactor", "chunk.structuralRatio", "retrieval.rewriteFallbackWeakScore"}) {
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
        for (String iKey : new String[]{"rerank.minHits", "retrieval.rewriteFallbackMinHits", "rerank.failCooldownMs"}) {
            String v = updates.get(iKey);
            if (v != null && !v.isBlank()) {
                try {
                    if (Integer.parseInt(v.trim()) < 0) throw new IllegalArgumentException(iKey + " 不能为负数");
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
                "retrieval.refExpandMaxHits", "retrieval.refExpandMaxTokens", "retrieval.refExpandParentMaxLevels", "retrieval.refExpandParentSummaryChars"}) {
            String v = updates.get(iKey);
            if (v != null && !v.isBlank()) {
                try {
                    if (Integer.parseInt(v.trim()) < 0) throw new IllegalArgumentException(iKey + " 不能为负数");
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(iKey + " 必须是整数");
                }
            }
        }
        for (String bKey : new String[]{"deepReasoning.enabled", "deepReasoning.enableThinking", "deepReasoning.multiRetrieval", "chunk.structural",
                "retrieval.refDetectEnabled", "retrieval.refDetectMention", "retrieval.refExpandEnabled", "retrieval.refExpandIncludeIncoming",
                "retrieval.refExpandParentEnabled", "retrieval.refExpandFuzzyName"}) {
            String v = updates.get(bKey);
            if (v != null && !v.isBlank() && !"true".equalsIgnoreCase(v) && !"false".equalsIgnoreCase(v)) {
                throw new IllegalArgumentException(bKey + " 仅允许 true / false");
            }
        }
        // 引用扩散父块模式校验
        String rpm = updates.get("retrieval.refExpandParentMode");
        if (rpm != null && !rpm.isBlank()
                && !"title_only".equals(rpm) && !"summary".equals(rpm) && !"full".equals(rpm)) {
            throw new IllegalArgumentException("retrieval.refExpandParentMode 仅允许 title_only / summary / full");
        }
        // 重排参数校验
        String rb = updates.get("rerank.enabled");
        if (rb != null && !rb.isBlank() && !"true".equalsIgnoreCase(rb) && !"false".equalsIgnoreCase(rb)) {
            throw new IllegalArgumentException("rerank.enabled 仅允许 true / false");
        }
        String rt = updates.get("rerank.timeoutMillis");
        if (rt != null && !rt.isBlank()) {
            try {
                if (Integer.parseInt(rt.trim()) < 1000) throw new IllegalArgumentException("rerank.timeoutMillis 不能小于 1000");
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("rerank.timeoutMillis 必须是整数");
            }
        }
        // 关键词引擎校验
        String ke = updates.get("keyword.engine");
        if (ke != null && !ke.isBlank() && !"mysql".equalsIgnoreCase(ke) && !"meilisearch".equalsIgnoreCase(ke)) {
            throw new IllegalArgumentException("keyword.engine 仅允许 mysql / meilisearch");
        }
        // 检索融合方式校验（rrf 为实验模式：保存即生效，用评估页对比验证后再留用）
        String fm = updates.get("retrieval.fusionMode");
        if (fm != null && !fm.isBlank() && !"sum".equalsIgnoreCase(fm) && !"rrf".equalsIgnoreCase(fm)) {
            throw new IllegalArgumentException("retrieval.fusionMode 仅允许 sum / rrf");
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
        String scThr = updates.get("semanticCache.threshold");
        if (scThr != null && !scThr.isBlank()) {
            try {
                double t = Double.parseDouble(scThr);
                if (t < 0.8 || t > 1.0) throw new IllegalArgumentException("semanticCache.threshold 需在 0.8~1 之间");
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("semanticCache.threshold 必须是数字");
            }
        }
        // 解析参数校验：非负整数（0 表示不限制）
        for (String iKey : new String[]{"chunk.maxChunks", "chunk.maxImages", "vision.concurrency",
                "ratelimit.chatPerMinute", "ratelimit.uploadPerMinute", "semanticCache.maxEntries",
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

        // 向量模型热切换：任一 embedding.* 提交时，用「新配置」真实探测一次 embedding
        // （校验地址/Key/模型名可达；失败拒绝保存——避免配错后自动触发的全量重嵌任务必然失败）。
        // 向量无法跨模型迁移（向量空间不兼容），真正切换后自动触发全量重嵌入。
        boolean embeddingChanged = false;
        if (updates.keySet().stream().anyMatch(k -> k.startsWith("embedding."))) {
            String newModel = updates.getOrDefault("embedding.model", get("embedding.model")).trim();
            String newBase = updates.getOrDefault("embedding.baseUrl", get("embedding.baseUrl")).trim();
            // 未提交新 key（掩码已过滤）时回退当前值（get 透明解密为明文）
            String newKey = updates.getOrDefault("embedding.apiKey", get("embedding.apiKey"));
            String newPath = updates.getOrDefault("embedding.embeddingsPath", "").trim();
            embeddingChanged = !newModel.equals(get("embedding.model").trim())
                    || !newBase.equals(get("embedding.baseUrl").trim())
                    || updates.containsKey("embedding.apiKey")
                    || !newPath.equals(get("embedding.embeddingsPath").trim());
            if (embeddingChanged) {
                int probeDim;
                try {
                    probeDim = DynamicEmbeddingModel.probe(newBase, newKey, newModel, newPath);
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
            AiConfig c = configMapper.selectById(kv.getKey());
            if (c == null) {
                c = new AiConfig();
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
     * @param groups 待恢复分组（chat/vision/chunk/parse/upload/retrieval/rerank/keyword/context/deepReasoning/ratelimit/semanticCache）
     * @return 实际恢复的键值
     */
    public Map<String, String> resetDefaults(Collection<String> groups) {
        Set<String> allowed = new HashSet<>(List.of(
                "chat", "vision", "chunk", "parse", "upload", "retrieval", "rerank",
                "keyword", "context", "deepReasoning", "ratelimit", "semanticCache"));
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
                AiConfig c = configMapper.selectById(k);
                if (c == null) {
                    c = new AiConfig();
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
            AiConfig c = configMapper.selectById(key);
            if (c == null) {
                c = new AiConfig();
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
                "keyword", "context", "deepReasoning", "ratelimit", "semanticCache"};
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
                        "editable", EDITABLE.containsKey(d.getKey())));
            }
            result.put(g, items);
        }
        return result;
    }
}
