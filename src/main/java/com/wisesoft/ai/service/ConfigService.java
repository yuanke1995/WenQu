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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型配置服务：DB（c_ai_config）存储 + 内存缓存
 * <p>
 * - 启动时表空则从 yml/env 默认值灌入
 * - 可编辑白名单：chat.temperature / vision.prompt / 检索与解析参数等（保存即生效；
 *   chat.model / embedding.model / vision.model / rerank.model 均已退役——业务模型归属到使用者：
 *   知识库绑定向量/解析视觉/检索重排，聊天走会话覆盖>个人默认，仅 eval.judgeModel 等系统工具保留全局）
 * - chat.baseUrl / chat.apiKey / chat.completionsPath 支持跨厂商热切换（DynamicOpenAiChatModel
 *   每次请求校验配置指纹、变化即重建，配合 Redis 广播多实例同步生效）；embedding / vision / rerank
 *   各组的网关三要素保留为「遗留纯模型名」的回落网关，不再作为运行时默认
 * - 敏感项（*.apiKey）RSA 加密入库（ConfigCryptoService）：启动自动迁移存量明文，读取透明解密
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ConfigService {

    // 可编辑白名单与参数分层（原 EDITABLE / TIER 两张 Map）已收敛到 classpath:config-schema.json，
    // 由 ConfigSchemaService 提供 isEditable() / tier() / help() / validate()（字段定义单一来源）。
    // MCP Server 已下沉为个人资产（c_ai_user_mcp），平台层面只剩 tool.enabled 总开关。

    private final ConfigMapper configMapper;
    private final AppProperties properties;
    private final Environment environment;
    private final StringRedisTemplate redisTemplate;
    private final RedisProperties redisProperties;
    /** @Lazy 打破循环依赖：KeywordIndexService 构造依赖本类，仅引擎切换校验/重建时使用 */
    private final KeywordIndexService keywordIndexService;
    /** 敏感项（*.apiKey）RSA 加解密 */
    private final ConfigCryptoService crypto;
    /** 配置字段定义（可编辑白名单 / 分层 / 说明 / 校验规则的唯一来源） */
    private final com.wisesoft.ai.config.ConfigSchemaService schema;

    /** 配置变更广播 channel（多实例同步：任意实例保存配置 → 其他实例订阅后重载缓存） */
    public static final String CONFIG_CHANNEL = "ai:config:changed";

    private volatile Map<String, String> cache = new HashMap<>();

    public ConfigService(ConfigMapper configMapper, AppProperties properties, Environment environment,
                         StringRedisTemplate redisTemplate, RedisProperties redisProperties,
                         @org.springframework.context.annotation.Lazy KeywordIndexService keywordIndexService,
                         ConfigCryptoService crypto,
                         com.wisesoft.ai.config.ConfigSchemaService schema) {
        this.configMapper = configMapper;
        this.properties = properties;
        this.environment = environment;
        this.redisTemplate = redisTemplate;
        this.redisProperties = redisProperties;
        this.keywordIndexService = keywordIndexService;
        this.crypto = crypto;
        this.schema = schema;
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
                    c.setRemark(schema.helpOrDefault(e.getKey(), "只读配置"));
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
        d.put("vision.prompt", properties.getVision().getPrompt());
        // vision.baseUrl / vision.apiKey 不注默认值：视觉网关统一来自「模型供应商」表（知识库
        // parse_params.visionRef 引用 → 供应商网关）。这两键既不在可编辑白名单、也没有运行时读取点，
        // 灌进库里只会让"设置值看起来生效"（VisionService.routeFor 对无引用直接跳过，不回落 legacy）
        d.put("vision.enabled", String.valueOf(properties.getVision().isEnabled())); // L12：总开关（设置页可改）
        d.put("vision.concurrency", String.valueOf(properties.getVision().getConcurrency()));
        d.put("vision.descCacheVersion", "1");                 // 图片描述缓存版本（bump 后全量重新描述）
        d.put("vision.descCacheTtlDays", "180");               // 图片描述缓存有效期(天，0=不过期)
        // 向量网关三要素（遗留纯模型名回落用；向量模型本体已归知识库 embedding_ref，全局键退役）
        d.put("embedding.baseUrl", env("spring.ai.openai.embedding.base-url",
                env("spring.ai.openai.base-url", "")));
        d.put("embedding.apiKey", env("spring.ai.openai.embedding.api-key",
                env("spring.ai.openai.api-key", "")));
        d.put("embedding.embeddingsPath", "/v1/embeddings");
        // 当前向量索引维度（系统记录，非用户可编辑）：全量重嵌入成功后由 putInternal 回写，
        // 作为下次切换的"旧维度"基线并供设置页展示。空/0 = 尚未记录（首次部署或未切换过）
        d.put("embedding.dimensions", "");
        // 分块粒度与标题层级：解析器统一经 configService 读取（d 里必须给出种子，否则丢失 yml 默认），
        // 知识库 parse_params 的库级覆盖才可能生效（覆盖走线程局部，读 AppProperties 的旁路读不到）
        d.put("chunk.maxSize", String.valueOf(properties.getChunk().getMaxSize()));
        d.put("chunk.headingDepth", String.valueOf(properties.getChunk().getHeadingDepth()));
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
        // 技能的内容与启停已在个人表 c_ai_user_skill / c_ai_skill_disabled（谁装谁管），此处只剩两项预算参数
        d.put("skill.injectMaxChars", "1200");             // 清单注入字符上限
        d.put("skill.maxFileChars", "20000");              // 单技能全文读取上限
        // 远程安装来源白名单（逗号分隔的精确 host，子域要单列；留空=关闭远程安装）：技能正文入库不执行，
        // 但"允许从哪儿拉"必须是平台可控的边界（GitHub 走 raw 链接，故含 raw.githubusercontent.com）
        d.put("skill.remoteAllowedHosts", "github.com,raw.githubusercontent.com,modelscope.cn,www.modelscope.cn");
        d.put("agent.enabled", "false");                   // SubAgent 并行编排总开关（默认关）
        d.put("agent.subAgents", "2");                     // 子代理数量（2~4）
        d.put("agent.topKPerAgent", "3");                  // 每个子代理取回命中块数
        d.put("agent.digestEnabled", "true");              // 是否用模型提炼要点
        d.put("agent.autoRoute", "true");                  // 按需委派：主模型先挑相关的子智能体再咨询
        d.put("agent.routeTimeoutMs", "8000");             // 路由判定超时（超时回退全部候选）
        d.put("agent.autoDispatch", "true");               // 自动派遣：对话页选「自动派遣」时按名称+描述路由（关=回落默认智能体）
        // mcp.enabled / mcp.servers 已移除：MCP Server 改为每人自己的 c_ai_user_mcp（见 McpClientService）
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
            // chunk.maxSize / chunk.headingDepth 不再回写 AppProperties：解析器已统一从 configService 读取，
            // 再回写一份进 bean 就形成双读取源——库级覆盖只写线程局部，读 bean 的旁路读不到（正是断链根因）
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
     * 取当前线程参数覆盖的快照（副本，可能为空）。
     * <p>用途：把本轮覆盖**显式**交给并行子线程——ThreadLocal 不随任务提交跨线程继承，
     * 池化线程里读到的永远是空覆盖（静默退化为全局配置）。调用方在子线程内 putOverrides(snapshot)
     * 并在 finally 里 clearOverride()（线程复用，必须清）。
     */
    public Map<String, String> currentOverrides() {
        Map<String, String> cur = OVERRIDE.get();
        return cur == null || cur.isEmpty() ? Map.of() : new HashMap<>(cur);
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
                    if (schema.isEditable(fullKey)) {
                        updates.put(fullKey, kv.getValue() == null ? "" : kv.getValue().trim());
                    }
                }
            }
        }
        // 掩码回写保护：snapshot 对 *.apiKey 脱敏为 "****后4位"，前端未修改 key 时会把掩码原样提交；
        // 掩码值（**** 开头）一律跳过更新，避免覆盖库中真实 key（真实 master key 不可能以 **** 开头）
        updates.entrySet().removeIf(kv ->
                kv.getKey().endsWith(".apiKey") && kv.getValue() != null && kv.getValue().startsWith("****"));

        // ---------- schema 驱动校验（类型 / 范围 / 枚举 / 布尔）----------
        // 规则全部来自 classpath:config-schema.json（与下发前端渲染的是同一份定义）。此前按前缀分组
        // 手写的校验清单已删除：其中 20 条对应的键早已退出可编辑白名单（死校验），其余 43 条的约束
        // 均已被 schema 覆盖且不更松（如 context.defaultWindowTokens 由"≥0"收紧为"≥1000"）。
        for (Map.Entry<String, String> kv : updates.entrySet()) {
            String err = schema.validate(kv.getKey(), kv.getValue());
            if (err != null) throw new IllegalArgumentException(err);
        }
        // 切换到 meilisearch：保存前强制探测服务可用性，不可用则阻止保存（避免切到不可用的空索引）。
        // 这是"服务可达性"而不是"取值合法性"，故留在代码里而不进 schema。
        String engine = updates.get("keyword.engine");
        if (engine != null && "meilisearch".equalsIgnoreCase(engine.trim()) && !keywordIndexService.checkAvailable()) {
            String reason = keywordIndexService.debugUnavailableReason();
            throw new IllegalArgumentException("Meilisearch 服务不可用（" + (reason == null ? "探测失败" : reason)
                    + "），请先启动 Meilisearch（docker compose 或本地）再切换");
        }

        // 视觉网关地址校验已移除：vision.baseUrl 不在可编辑白名单、也无运行时读取点
        // （视觉网关来自供应商表），保留这段只会校验一个永远不会被消费的键

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
                c.setRemark(schema.help(kv.getKey()));
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
                    c.setRemark(schema.helpOrDefault(k, "只读配置"));
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
     * 系统内部回写（不经字段定义白名单）：供运行流程记录"既成事实"型配置，
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
        // 分组由 defaults() 的键前缀自动派生：不再手写组名数组——漏一个前缀该组就永远回显不出来
        // （历史上 images / session / cleanup / eval / cache 就是这么漏掉的）。defaults() 只求值一次。
        Map<String, String> defs = defaults();
        Set<String> groups = new LinkedHashSet<>();
        for (String k : defs.keySet()) {
            int dot = k.indexOf('.');
            if (dot > 0) groups.add(k.substring(0, dot));
        }
        for (String g : groups) {
            Map<String, Object> items = new LinkedHashMap<>();
            for (Map.Entry<String, String> d : defs.entrySet()) {
                if (!d.getKey().startsWith(g + ".")) continue;
                String shortKey = d.getKey().substring(g.length() + 1);
                String value = get(d.getKey());
                if (shortKey.contains("apiKey") && value.length() > 4) {
                    value = "****" + value.substring(value.length() - 4);
                }
                items.put(shortKey, Map.of(
                        "value", value,
                        "editable", schema.isEditable(d.getKey()),
                        "tier", schema.tier(d.getKey())));
            }
            result.put(g, items);
        }
        return result;
    }
}
