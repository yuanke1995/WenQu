package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.mapper.ConfigMapper;
import com.wenqu.ai.model.Config;
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
 * - 可编辑白名单：vision.prompt / 检索与解析参数等（保存即生效；
 *   chat.model / embedding.model / vision.model 已退役——业务模型归属到使用者：
 *   知识库绑定向量/解析视觉/检索重排，聊天走会话覆盖>个人默认；问答对生成（parse.qaModel
 *   已退役）与 GraphRAG 抽取（graphrag.modelRef 已退役）均回落库主个人默认聊天模型；
 *   仅 eval.judgeModel 等
 *   管理员自用工具保留全局）
 * - chat.baseUrl / chat.apiKey / chat.completionsPath 支持跨厂商热切换（DynamicOpenAiChatModel
 *   每次请求校验配置指纹、变化即重建，配合 Redis 广播多实例同步生效）；embedding / vision / rerank
 *   各组的网关三要素保留为「遗留纯模型名」的回落网关，不再作为运行时默认
 * - 敏感项（*.apiKey / *.clientSecret）RSA 加密入库（ConfigCryptoService）：启动自动迁移存量明文，读取透明解密
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
    /** 敏感项（见 {@link #isSensitiveKey}）RSA 加解密 */
    private final ConfigCryptoService crypto;
    /** 配置字段定义（可编辑白名单 / 分层 / 说明 / 校验规则的唯一来源） */
    private final com.wenqu.ai.config.ConfigSchemaService schema;

    /** 配置变更广播 channel（多实例同步：任意实例保存配置 → 其他实例订阅后重载缓存） */
    public static final String CONFIG_CHANNEL = "ai:config:changed";

    private volatile Map<String, String> cache = new HashMap<>();

    public ConfigService(ConfigMapper configMapper, AppProperties properties, Environment environment,
                         StringRedisTemplate redisTemplate, RedisProperties redisProperties,
                         @org.springframework.context.annotation.Lazy KeywordIndexService keywordIndexService,
                         ConfigCryptoService crypto,
                         com.wenqu.ai.config.ConfigSchemaService schema) {
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
     * 存量明文密钥迁移：敏感项（*.apiKey / *.clientSecret）非 RSA: 前缀的值加密回写 DB 与缓存。
     * 读取兼容明文（get 透明解密对无前缀值原样返回），迁移只为尽快消除库中明文；
     * 多实例部署由 Redis 广播 reload 触发各自迁移，幂等。
     */
    private void migratePlainSecrets() {
        try {
            Map<String, String> encrypted = new HashMap<>();
            for (Map.Entry<String, String> e : cache.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (isSensitiveKey(k) && v != null && !v.isBlank() && !crypto.isEncrypted(v)) {
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
                    c.setConfigValue(isSensitiveKey(e.getKey()) ? crypto.encrypt(e.getValue()) : e.getValue());
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
        // chat.model 已退役（全局兜底移除）：不再注入默认值，存量库中的旧行成为孤儿数据（无读取方）。
        // chat.baseUrl / chat.apiKey / chat.completionsPath 同步退役：聊天网关统一来自「模型供应商」表
        // （模型引用 → 供应商网关），DynamicOpenAiChatModel 对解析不出的引用 fail-loud，无全局兜底
        // 回答温度已下线设置页/个人偏好（知识库问答固定低温度更稳，暴露给最终用户徒增困惑）：
        // 降级为隐藏参数——仅 DB 可调（与 tool.mcpCiteMaxRefs 同一口径），无设置页字段、无个人层
        d.put("chat.temperature", env("spring.ai.openai.chat.options.temperature", "0.3"));
        // chat.systemPrompt / chat.userSystemPrompt 已退役（2026-10）：角色提示词归智能体管
        // （c_ai_agent.system_prompt，问渠内置智能体已自带），未绑定智能体的会话回落内置默认
        // （AppProperties.systemPrompt，yml/env 可覆盖）；附加指令不再提供平台/个人层，存量库中的
        // 旧行成为孤儿数据（无读取方）
        d.put("chat.citationCheckEnabled", "true");         // 引用语义一致性自检（生成后校验：编造/张冠李戴的引用是 RAG 信任根基，默认开；每轮多一次模型调用，超时/失败自动跳过不阻塞）
        d.put("chat.planAutoIntent", "true");               // 按消息意图自动开计划模式（用户没开开关时，判断本轮是否适合先出执行计划；与检索并行的短判定，超时/失败按不开）
        // 断线重连接流（P0）：默认开。必须在这里登记而不是只靠 getBoolean(key, true) 的形参——
        // 键既无 DB 行又无 defaults 时 get() 返回空串，而 Boolean.parseBoolean("") 是 **false 且不抛异常**，
        // 兜底链走不到第二级，"默认开的开关"会静默变成关（trace.sessionEventEnabled 同款坑）。
        d.put("chat.resumeEnabled", "true");                // 刷新/换设备后接回正在跑的那一轮
        d.put("chat.detachGraceMs", "180000");              // 没人观看多久后按中断收束本轮；-1=一律跑完，0=关掉就停
        d.put("vision.prompt", properties.getVision().getPrompt());
        // vision.baseUrl / vision.apiKey 不注默认值：视觉网关统一来自「模型供应商」表（知识库
        // parse_params.visionRef 引用 → 供应商网关）。这两键既不在可编辑白名单、也没有运行时读取点，
        // 灌进库里只会让"设置值看起来生效"（VisionService.routeFor 对无引用直接跳过，不回落 legacy）
        d.put("vision.enabled", String.valueOf(properties.getVision().isEnabled())); // L12：总开关（设置页可改）
        d.put("vision.concurrency", String.valueOf(properties.getVision().getConcurrency()));
        d.put("vision.descCacheVersion", "1");                 // 图片描述缓存版本（bump 后全量重新描述）
        d.put("vision.descCacheTtlDays", "180");               // 图片描述缓存有效期(天，0=不过期)
        // embedding.baseUrl/apiKey/embeddingsPath 退役：向量网关统一来自「模型供应商」表（kb.embedding_ref
        // 引用 → 供应商网关），遗留网关信息已无任何读取方
        // 当前向量索引维度（系统记录，非用户可编辑）：按库重嵌入成功后由 putInternal 回写，
        // 供设置页展示。空/0 = 尚未记录（首次部署或未切换过）
        d.put("embedding.dimensions", "");
        // 平台内置向量化模型（全局、管理员可编辑、非 personalOnly）：运营方登记一个 embedding 模型引用，
        // 作为全体用户记忆语义能力的统一来源——用户零配置即可获得语义记忆。留空=不提供向量化
        // （记忆去重降级为精确匹配、语义注入关闭）。记忆向量化已不再暴露为个人设置项
        d.put("memory.platformEmbeddingRef", "");
        // 分块粒度与标题层级：解析器统一经 configService 读取（d 里必须给出种子，否则丢失 yml 默认），
        // 知识库 parse_params 的库级覆盖才可能生效（覆盖走线程局部，读 AppProperties 的旁路读不到）
        d.put("chunk.maxSize", String.valueOf(properties.getChunk().getMaxSize()));
        d.put("chunk.headingDepth", String.valueOf(properties.getChunk().getHeadingDepth()));
        d.put("chunk.maxChunks", String.valueOf(properties.getChunk().getMaxChunks()));
        d.put("chunk.maxImages", String.valueOf(properties.getChunk().getMaxImages()));
        d.put("chunk.overlap", String.valueOf(properties.getChunk().getOverlap()));
        d.put("chunk.structural", String.valueOf(properties.getChunk().isStructural()));
        d.put("chunk.structuralRatio", String.valueOf(properties.getChunk().getStructuralRatio()));
        d.put("parse.embedRetryCount", String.valueOf(ConfigDefaults.PARSE_EMBED_RETRY_COUNT));                 // M10：向量化批次失败自动重试次数
        d.put("upload.maxFileSize", String.valueOf(200L * 1024 * 1024));  // 业务上传上限（字节），默认 200MB
        d.put("retrieval.vectorWeight", String.valueOf(properties.getRetrieval().getVectorWeight()));
        d.put("retrieval.keywordWeight", String.valueOf(properties.getRetrieval().getKeywordWeight()));
        d.put("rerank.enabled", String.valueOf(properties.getRetrieval().getRerank().isEnabled()));
        d.put("rerank.model", "");                         // 重排模型引用槽位（personalOnly，无全局层）：归知识库/智能体检索设置绑定；空=回落 rerank.baseUrl 本地服务
        // 平台默认重排模型（管理员登记，全平台兜底）：库/智能体覆盖与个人设置都为空时生效，
        // 仍为空才回落 rerank.baseUrl。与 rerank.model 分开成键，是为了不动 personalOnly 的归属语义
        // （模型引用只认归属人），又让"一个库都没绑 = 全平台没重排"这个默认态有解。
        d.put("rerank.platformRef", "");
        d.put("rerank.baseUrl", properties.getRetrieval().getRerank().getBaseUrl());
        // context.safetyFactor / context.costCapTokens / context.maxOutputTokens 已退役：
        // 窗口与最大输出按模型在「模型管理」声明（对话类窗口必填），安全系数固定为
        // ModelRegistryService.CONTEXT_SAFETY_FACTOR，成本软上限移除。存量库中的旧行成为孤儿数据（无读取方）。
        d.put("context.historyCompress", String.valueOf(properties.getContext().isHistoryCompress()));
        d.put("context.compressRatio", String.valueOf(properties.getContext().getCompressRatio()));
        d.put("context.snippetWindowChars", String.valueOf(properties.getContext().getSnippetWindowChars()));
        d.put("context.maxContextHits", String.valueOf(properties.getContext().getMaxContextHits()));
        d.put("context.maxBlocksPerDoc", "3");             // 单文档块数配额（0=不限制；@ 引用块豁免）
        d.put("context.dedupEnabled", "true");             // 信息增益去冗余（默认开）
        d.put("context.dedupThreshold", "0.45");           // 词元重叠阈值（越高越宽松）
        d.put("context.dedupPathThreshold", "0.28");       // 同章节路径下重叠阈值
        d.put("context.adjacentMergeEnabled", "true");     // 相邻块合并（同文档 chunk 连续命中拼接进上下文）
        d.put("context.adjacentMergeMaxChunks", "3");      // 单次相邻合并的块数上限
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
        // 读点共用默认值的 key：defaults() 与读点 fallback 共用 ConfigDefaults 常量（改默认值只改常量一处）

        // 检索行为参数（原硬编码收口，设置页可调、保存即生效）
        d.put("retrieval.vecThreshold", "0.3");            // 向量相似度归一化基准/下限
        d.put("retrieval.minContextScore", String.valueOf(ConfigDefaults.RETRIEVAL_MIN_CONTEXT_SCORE));  // 最低相关分门槛（仅对重排分 0~1 分域生效，对齐 Dify/Coze Score 阈值；0=关）
        d.put("retrieval.minFusionScore", String.valueOf(ConfigDefaults.RETRIEVAL_MIN_FUSION_SCORE));    // 融合分门槛（未启用重排时生效；2026-10-04 评测基线标定：期望块 95.7%≥0.55，无关块 70%<0.5、通过率 30%，旧 0.25 时通过率 82.7% 拦不住词面重叠噪声；调检索权重后需重标定；0=关）
        d.put("retrieval.queryRewriteEnabled", "true");    // 多轮查询改写：检索前用对话模型做指代消解（仅多轮触发，失败按原句检索）
        d.put("retrieval.aclTopKSupplant", "true");        // 权限补采是否顶掉原 topK 尾部（2026-10-09 补：schema 早已声明，defaults 漏登记 → 设置页改了回显不出）
        d.put("retrieval.vectorTopK", "15");               // 向量召回 topK（调优/评估扫参用，下限 1）
        d.put("retrieval.keywordLimit", "20");             // 关键词召回上限
        d.put("retrieval.searchTimeoutMs", "8000");        // 混合检索总超时
        d.put("retrieval.multiConsensusBonus", "0.03");    // 深度思考多路检索的共识加分（被≥2个子查询命中的块；0=关；评测实测 0.03 最优）
        // 检索-反思循环（Agentic RAG · 自主多轮检索）：off/high/always。默认 off 非默认全量——
        // 循环轮次多出工具往返与自评开销，由显式开启者承担；high=深度思考高档及以上增强，always=独立开关。
        // 键取 retrieval 前缀（与 multiConsensusBonus 同属"检索策略"旋钮）：设置页挂在检索面板，
        // 「恢复本组默认」按前缀重置，前缀与面板一致才能被一起重置
        d.put("retrieval.reflectiveRetrieval", "off");
        // 重排行为参数
        // 关键词召回引擎（mysql=LIKE；meilisearch=外部索引，中文分词+相关度；index 只走 yml 不入库）
        d.put("keyword.engine", properties.getKeyword().getEngine());
        d.put("keyword.baseUrl", properties.getKeyword().getBaseUrl());
        d.put("keyword.apiKey", properties.getKeyword().getApiKey());   // master key RSA 加密入库（设置页可改，改后客户端自动重建）；未配置时回退 env AI_MEILI_KEY
        d.put("keyword.reconcileIntervalMs", "3600000");     // 关键词索引精确对账周期（ms，默认 1 小时；≤0 暂停）。只比 Meilisearch 与 MySQL，不调任何模型
        // 启动首轮对账：2026-10-06 默认关闭。此前库里存的是 true，导致每次重启都跑一遍全量双向比对——
        // 虽然不烧额度，但多副本各跑一遍纯属无谓扫描（对账已由上面的周期任务覆盖，启动首轮没有不可替代的价值）。
        // 注意：此前本键既没登记进 defaults() 也不在 config-schema.json，导致设置页看不到、改不了，
        // 值只能手工改库——「隐藏开关」本身就是隐患，故在此正式登记。
        d.put("keyword.reconcileOnStartup", "false");
        // 解析行为参数
        d.put("parse.concurrency", "3");                   // 文档解析 worker 并发数（队列worker 数；下游 OCR/视觉/embedding 另有闸门限流）
        d.put("parse.queue.capacity", "500");              // 解析队列容量：排队满则上传直接拒（不再是"收下再丢解析任务"）
        d.put("parse.queue.scanIntervalMs", "5000");       // 解析队列扫描间隔（≤0 暂停：解析不再自动执行）
        d.put("parse.taskTimeoutMs", "1200000");           // 单个解析任务总超时（超时中断线程；租约随后回收，崩溃也能自愈）
        d.put("parse.taskLeaseSeconds", "0");              // 任务租约时长(秒)；0=按 taskTimeoutMs+300 自动算
        d.put("parse.retryMaxAttempts", "3");              // 解析失败最大尝试次数（超过转终态 dead，不再退避）
        d.put("parse.retryBackoffSeconds", "5");           // 解析失败退避基数(秒)：第 n 次等 base×2^(n-1)（封顶 300s）
        d.put("parse.embedConcurrency", String.valueOf(ConfigDefaults.PARSE_EMBED_CONCURRENCY));              // 向量化并发闸（多文档并行解析时防打爆 embedding 服务）
        d.put("parse.ocrGateConcurrency", "1");            // 版面引擎并发闸：自托管单实例服务一次只吃一份，默认守住 1
        d.put("parse.ocrMinText", "20");                   // PDF 文本少于该长度判定扫描件触发 OCR
        d.put("parse.recoverStuckOnStartup", "true");      // 启动对账：复位崩溃残留的"解析中"文档（多副本部署应置 false）
        d.put("parse.qaEnabled", "true");                  // QA 增强：解析时按块生成问答对并按问法向量化（消耗对话模型 token；需重解析生效）
        d.put("parse.qaPerChunk", "2");                    // QA 增强：每块生成问法条数（1~5）
        // parse.qaModel 已退役：问答对生成回落库主个人默认聊天模型（User.defaultModel，QaIndexService 显式查询）
        d.put("parse.childEnabled", "true");               // 父子分块：超长块切子块向量化，命中后返回父块正文（确定性切分，无 LLM；需重解析生效）
        d.put("parse.childSize", "400");                   // 父子分块：子块尺寸（字符，超过该长度的块才切子块）
        d.put("vision.userImageConcurrency", "2");         // 用户上传图片识别并发
        // 问答行为参数
        // chat.remainTokenFloor / chat.truncateFallbackChars 已退役（2026-10）：上下文装配的工程细节
        // 不再暴露给设置页（用户容易误解为压缩参数，误调会在预算耗尽后强塞知识块）——
        // 固定为 RagService 常量 REMAIN_TOKEN_FLOOR=800 / TRUNCATE_FALLBACK_CHARS=200。存量库旧行成孤儿数据
        // chat.historyRounds 已退役：历史不再按轮数截断，改为预算驱动全量带入 + 滚动压缩
        // （context.historyCompress / context.compressRatio 控制；辅助调用内部仍用固定 2 轮短历史）
        // chat.userSystemPrompt（附加指令）已退役：见上方 chat.systemPrompt 注释
        // 深度思考默认偏好（personal；个人设置可改，前端据此决定新模型默认开/关，按模型的手动记忆仍存浏览器）
        d.put("chat.pipelineThreads", "8");                // 问答流水线线程数（重活不占 Tomcat 请求线程）
        d.put("chat.approvalTimeoutMs", "120000");         // 工具执行审批等待上限(ms)：超时按拒绝处理（阻塞工具线程，必须有界）
        d.put("chat.planGenTimeoutMs", "120000");          // 计划模式：计划生成流式超时(ms)，失败/为空降级为普通回答
        d.put("chat.planTimeoutMs", "600000");             // 计划模式：计划批准等待上限(ms)（人工等待不计入整轮预算）；超时按未批准终止本轮
        d.put("chat.streamRetryCount", "1");               // H2：主 LLM 流式中断（未输出token）自动重试次数
        d.put("chat.sseTimeoutMs", "300000");              // H4：问答 SSE 超时(ms)
        d.put("chat.askTimeoutMs", "600000");              // 人在回路提问卡等待上限(ms)（2026-10-09 补：schema 早已声明，defaults 漏登记 → 设置页改了回显不出）
        d.put("chat.retrievalDebugEnabled", "false");      // 检索调试入口（内部排障，默认关；统管调试显示含降级提示）
        // 空态示例问题（对话页新会话空态的引导卡，点一下即按这条提问）：体验项 → schema 标 personal，
        // 个人设置可覆盖成自己的或关掉；生效值 = 个人值 > 系统全局，经 /config/public 下发给两套对话页。
        // 内容一行一条，可写「标签｜问题」（schema def 与本串同值：def 管设置页展示，这里管运行时种子）
        d.put("chat.sampleQuestionsEnabled", "true");
        d.put("chat.sampleQuestions",
                "🔍 知识检索｜帮我查一下问渠怎么上传文档\n"
                        + "📝 总结提炼｜帮我总结一份文档的核心要点\n"
                        + "✍️ 辅助写作｜帮我起草一份项目周报的框架\n"
                        + "📊 对比分析｜帮我对比一下两个方案的优缺点");
        // 接口限流（按用户/IP 固定窗口）
        d.put("ratelimit.enabled", String.valueOf(properties.getRatelimit().isEnabled()));
        d.put("ratelimit.chatPerMinute", String.valueOf(properties.getRatelimit().getChatPerMinute()));
        d.put("ratelimit.uploadPerMinute", String.valueOf(properties.getRatelimit().getUploadPerMinute()));
        d.put("eval.judgeEnabled", "false");   // 自动体检 LLM 评判（默认关，评估集大时耗时/成本明显）
        d.put("eval.judgeModel", "");              // 评判用独立模型（留空=跳过 LLM 自动评判）
        d.put("eval.autoIntervalMs", "86400000");  // 自动体检周期(ms，≤0=暂停)
        d.put("eval.autoThresholdPct", "10");      // 退化判定：指标相对跌幅百分比阈值
        // 定时任务（schedule 包读取；≤0=暂停对应任务）
        d.put("images.chatCleanupIntervalMs", "86400000");   // 聊天图片清理间隔(ms)
        d.put("images.chatRetentionMillis", "604800000");    // 聊天图片保留时长(ms，7天)
        d.put("cleanup.sessionCleanupIntervalMs", "86400000"); // 会话清理间隔(ms)
        d.put("cleanup.sessionRetentionDays", "30");           // 会话保留天数
        d.put("cleanup.sessionEventRetentionDays", "30");      // 会话事件账本保留天数（≤0=不清理；与上一条同一清理周期）
        d.put("cleanup.visitorIdleDays", String.valueOf(ConfigDefaults.VISITOR_SESSION_IDLE_DAYS)); // 访客会话闲置回收天数
        d.put("artifact.retentionDays", String.valueOf(ConfigDefaults.ARTIFACT_RETENTION_DAYS));                 // 产物保留天数（0=不清理）
        d.put("artifact.cleanupIntervalMs", "86400000");       // 产物超期清理间隔(ms，≤0=暂停)
        d.put("schedule.runLogRetentionDays", "7");            // 定时任务执行日志保留天数（超期物理删除）
        d.put("schedule.runLogCleanupIntervalMs", "86400000"); // 任务执行日志清理间隔(ms，≤0=暂停)
        d.put("scheduled.enabled", "true");                    // 定时执行智能体总开关
        d.put("scheduled.maxPerUser", "20");                   // 每人定时任务数上限（0=不限）
        d.put("scheduled.timeoutMs", "300000");                // 单次执行超时(ms)
        d.put("scheduled.scanIntervalMs", "30000");            // 到期扫描间隔(ms，≤0=暂停扫描)
        d.put("web.refreshEnabled", "true");                   // 网页源定时刷新总开关（仅 file_type=url 且文档开启 autoRefresh 才刷新）
        d.put("web.refreshScanIntervalMs", "60000");           // 网页源到期扫描间隔(ms，≤0=暂停扫描)
        d.put("memory.dedupThreshold", "0.90");               // 自动提取/手动添加记忆的语义去重余弦阈值（≥此值视为重复，跳过）
        d.put("memory.useSemanticInject", "true");           // 注入是否按当前对话语义检索 Top-K（关闭则回退按更新时间倒序）
        // 原 yml 参数开放为可配置（值由 syncProperties 回写到 AppProperties，读取点无需改动）
        // 注：chunk.maxSize / chunk.headingDepth 已在上方按 properties 播种（保证 yml/env 覆盖生效），此处不得再写死覆盖
        d.put("images.maxWidth", "1280");              // 图片压缩最长边(px,0=不压缩)
        d.put("images.quality", "0.9");                // JPEG 压缩质量
        d.put("images.authEnabled", "true");               // 图片签名鉴权开关（默认开：签名 URL 防盗链，关掉等于图片裸奔）
        d.put("images.authExpireSeconds", "3600");     // 签名 URL 有效期(秒)
        d.put("vision.timeoutMillis", "30000");        // 视觉模型读取超时(ms，RestClient 构建期读取，需重启生效)
        d.put("vision.retryCount", "1");               // 单图失败重试次数
        d.put("session.maxHistory", "10");             // 会话保留轮数
        d.put("session.expireMinutes", "30");          // 会话过期(分钟)
        // 查询改写（默认值取 bean，单一来源；消费方 RagService 经 syncProperties 回写后热生效）
        // 图片相关性校验（读取点 RagService；defaults 取 bean）
        d.put("imageFilter.enabled", String.valueOf(properties.getImages().getImageFilter().isEnabled()));
        d.put("imageFilter.minHits", String.valueOf(properties.getImages().getImageFilter().getMinHits()));
        d.put("imageFilter.preContextChars", String.valueOf(properties.getImages().getImageFilter().getPreContextChars()));
        // 原先写死在消费方代码里的行为参数（直读 configService，保存即生效）
        d.put("chat.maxImagesPerMessage", "9");
        d.put("chat.maxImageMb", "10");
        // 聊天附件：上传换 fileId 落盘，超保留期由 ScheduleCenter 清理（0=不清理）
        d.put("chat.uploadRetentionHours", "24");
        d.put("chat.uploadCleanupIntervalMs", "3600000");
        d.put("retrieval.relatedCount", "3");
        d.put("parse.embedBatchSize", String.valueOf(ConfigDefaults.PARSE_EMBED_BATCH_SIZE));
        d.put("parse.ocrDpi", "200");
        d.put("parse.ocrEngine", "none");      // PDF 深度解析引擎：none=文本抽取+扫描件视觉兜底（默认）/ vision=整份逐页视觉OCR / pp_structure_v3 / mineru=外部版面解析服务（失败 fail-loud 不回落）
        d.put("parse.ocrPpUri", "http://localhost:8080");       // PP-StructureV3 服务地址（健康检查 GET {uri}/health）
        d.put("parse.ocrMineruUri", "http://localhost:30011");  // MinerU 服务地址（宿主端口 30011：30001 与 IDEA 内置服务冲突；容器内仍是 30001）
        d.put("parse.ocrMineruBackend", "pipeline"); // MinerU 解析后端：pipeline=CPU 稳（默认）/ hybrid-auto-engine=本地 VLM 高精度（需算力）
        d.put("parse.ocrTimeoutMs", String.valueOf(ConfigDefaults.PARSE_OCR_TIMEOUT_MS)); // 版面引擎单次调用超时(ms)：大 PDF 版面解析慢，默认 10 分钟
        d.put("ratelimit.windowSeconds", "60");
        d.put("cache.docMetaTtlSeconds", "600");
        // 工具调用（Function Calling）总开关与知识库精确检索工具
        d.put("tool.enabled", "true");                     // 工具调用总开关（默认开：算术/时间等无副作用能力不该要用户先开箱）
        d.put("tool.knowledgeRetrieval.enabled", "true");  // 知识库精确检索工具开关（需总开关开启）
        d.put("tool.knowledgeRetrieval.maxHits", "5");     // 精确检索工具单次返回命中块上限(1~5)
        d.put("tool.artifact.enabled", "true");            // 产物交付工具开关（需总开关开启；生成 Markdown/CSV/JSON/HTML 文件并推送）
        d.put("tool.todo.enabled", "false");               // 任务清单工具开关（默认关：多步任务才用得上，开着会给每轮多一个工具）
        d.put("tool.builtin.enabled", "true");             // 内置高频工具开关（需总开关开启；计算/当前时间/日期差）
        d.put("tool.mcpCiteEnabled", "true");              // MCP 工具结果注册引用来源（结果文本带 http(s) URL 才注册；默认开）
        d.put("tool.mcpCiteMaxRefs", "10");                // MCP 引用注册单轮上限（隐藏参数，DB 可调；无设置页字段）
        // ---------- 联网搜索（工具 webSearch；结果注册进引用体系，与知识库来源同 [N] 编号）----------
        // 注意：webSearch.apiKey 以 .apiKey 结尾 → 走敏感项 RSA 密文入库（isSensitiveKey），改不得命名
        d.put("webSearch.enabled", "false");               // 联网搜索总开关（需 tool.enabled 总闸开启）
        d.put("webSearch.provider", "tavily");            // 服务商：tavily / bocha / generic（自建 SearXNG）
        d.put("webSearch.baseUrl", "");                   // 服务地址（留空用服务商默认；generic 必填，如 http://127.0.0.1:8888）
        d.put("webSearch.apiKey", "");                    // 服务 Key（RSA 密文入库；generic 自建可留空）
        d.put("webSearch.maxResults", "5");               // 单次搜索返回条数上限(1~10)
        d.put("webSearch.maxCallsPerTurn", "2");          // 单轮搜索次数上限（防模型反复搜烧配额；0=不限制）
        d.put("webSearch.timeoutMs", "8000");             // 单次请求超时(ms)
        d.put("webSearch.snippetChars", "600");           // 单条摘要截断字符数（控制工具结果 token 量）
        d.put("webSearch.requireApproval", "false");      // 是否纳入"有副作用工具"：true=受智能体 toolApprovalMode=ask 管辖；false=自动执行
        // 技能的内容与启停已在个人表 c_ai_user_skill / c_ai_skill_disabled（谁装谁管），此处只剩两项预算参数
        d.put("skill.injectMaxChars", "1200");             // 清单注入字符上限
        d.put("skill.maxFileChars", String.valueOf(ConfigDefaults.SKILL_MAX_FILE_CHARS));              // 单技能全文读取上限
        // 远程安装来源白名单（逗号分隔的精确 host，子域要单列；留空=关闭远程安装）：技能正文入库不执行，
        // 但"允许从哪儿拉"必须是平台可控的边界（GitHub 走 raw 链接，故含 raw.githubusercontent.com）
        d.put("skill.remoteAllowedHosts", "github.com,raw.githubusercontent.com,modelscope.cn,www.modelscope.cn");

        // ---------- 单点登录（OIDC）----------
        // 语义与平台版 config/options.py 的 OIDCConfig 一致（字段名改成 camelCase 落 c_ai_config）：
        // client_id + (issuerUrl 或 authorizationEndpoint) 即可生成登录链接；回调换 token 还需 clientSecret。
        // clientSecret 走敏感项（RSA 密文入库 + 快照掩码），与 *.apiKey 同一机制。
        d.put("oidc.enabled", "false");                                 // 总开关（关闭时登录页不显示按钮）
        d.put("oidc.providerName", "OIDC登录");                          // 登录按钮上的认证源名称
        d.put("oidc.issuerUrl", "");                                    // 走 discovery（/.well-known/openid-configuration）
        d.put("oidc.clientId", "");
        d.put("oidc.clientSecret", "");
        d.put("oidc.redirectUri", "");                                  // 空=按当前站点推导 /api/ai/auth/oidc/callback
        d.put("oidc.frontendBaseUrl", "");                              // 空=相对路径跳转（同源部署）；前后端分离时填前端地址
        d.put("oidc.authorizationEndpoint", "");                        // 手填端点优先于 discovery（IdP 无 discovery 时用）
        d.put("oidc.tokenEndpoint", "");
        d.put("oidc.userinfoEndpoint", "");
        d.put("oidc.scopes", "openid profile email");
        d.put("oidc.autoCreateUser", "true");                           // 首次登录自动建号（关闭则须管理员预先建好并绑定）
        d.put("oidc.defaultRole", "user");
        d.put("oidc.defaultDepartment", "OIDC用户");
        d.put("oidc.usernameClaim", "preferred_username");
        d.put("oidc.nameClaim", "name");
        d.put("oidc.useRawUsername", "false");                          // 用 IdP 的用户名当 uid（便于与本地账号对齐）
        d.put("oidc.fetchDepartmentInfo", "false");                     // 从 userinfo 取部门名并自动建部门
        d.put("oidc.departmentClaim", "department");
        d.put("oidc.forcePromptLogin", "false");                        // 授权请求带 prompt=login（强制重新认证）
        // ---------- 沙盒（隔离执行环境）----------
        // 语义与问渠新栈的环境变量一一对应（SANDBOX_PROVISIONER_URL / TOKEN / DELETE_TIMEOUT_SECONDS /
        // KEEPALIVE_INTERVAL_SECONDS / VIRTUAL_PATH_PREFIX / EXEC_TIMEOUT_SECONDS / MAX_OUTPUT_BYTES），
        // 但改走 config-schema：本工程配置唯一来源是 c_ai_config，设置页可改、保存即生效（client 懒构建）。
        // token 走敏感项（.token 后缀 ⇒ RSA 密文入库 + 快照只回显后 4 位）。
        d.put("sandbox.provisionerUrl", "http://127.0.0.1:8002"); // provisioner 服务地址
        d.put("sandbox.token", "");                               // 访问令牌（≥32 字符，不足拒绝启用）
        d.put("sandbox.virtualPathPrefix", "/home/gem/user-data");// 沙盒内可读写根
        d.put("sandbox.commandTimeoutSeconds", "180");            // 单条命令超时（秒）
        d.put("sandbox.maxOutputBytes", "262144");                // 单条命令输出上限（字节）
        d.put("sandbox.keepaliveIntervalSeconds", "30");          // keepalive 间隔（秒，≤0 关闭）
        d.put("sandbox.deleteTimeoutSeconds", "120");             // 删除沙盒的超时（秒）
        d.put("sandbox.idleReleaseMinutes", "60");                // 空闲多久回收沙盒（分钟，0=不回收）
        d.put("sandbox.cleanupIntervalMs", String.valueOf(ConfigDefaults.SANDBOX_CLEANUP_INTERVAL_MS));             // 空闲回收扫描间隔（ms，≤0=暂停）
        d.put("tool.sandbox.enabled", "false");                   // 沙盒工具总开关（跟随 tool.enabled）
        d.put("agent.enabled", "false");                   // SubAgent 并行编排总开关（默认关）
        d.put("agent.subAgents", "2");                     // 子代理数量（2~4）
        d.put("agent.topKPerAgent", "3");                  // 每个子代理取回命中块数
        d.put("agent.digestEnabled", "true");              // 是否用模型提炼要点
        d.put("agent.aggregateMode", "concat");            // 结果聚合模式：concat=按分支顺序直拼 / rerank=按重排分排序 / supervisor=LLM 二次聚合
        d.put("agent.digestMaxChars", "1500");             // 要点段总字符预算（超限截断；supervisor 模式作为压缩目标）
        d.put("agent.aggregateMarkFailed", "true");        // 失败分支在要点段显式占位（让主模型知道该视角无资料）
        d.put("agent.maxToolSteps", "15");                 // 单轮工具调用步数上限（智能体可覆盖；0=不限制）
        d.put("workflow.maxSteps", String.valueOf(ConfigDefaults.WORKFLOW_MAX_STEPS));                  // 工作流单次运行的最大图步数（StateGraph recursionLimit；M3 loop 回跳也受此闸）
        d.put("workflow.subagentTimeoutMs", "180000");     // 工作流子智能体节点等待回答的超时（ms，下限 30s）
        d.put("workflow.runTimeoutSeconds", "600");        // M5：工作流单次运行硬超时（秒，0=不限制；超时按 timeout 终态收口，防长管线占死执行线程）
        d.put("workflow.runLogRetentionDays", "30");       // 工作流运行记录保留天数（含 DSL 快照与节点 trace 大字段；超期由「工作流运行记录清理」任务物理删除）
        d.put("workflow.runCleanupIntervalMs", "86400000"); // 工作流运行记录清理间隔（ms，默认每日；≤0 暂停）
        d.put("workflow.scheduleScanIntervalMs", "30000");  // 工作流定时触发扫描间隔（ms，默认 30s；≤0 暂停「工作流定时触发」任务）
        d.put("workflow.approvalReapIntervalMs", "60000"); // 审批超时回收间隔（ms，默认 60s；≤0 暂停，暂停后挂起 run 不落终态）
        d.put("config.reloadIntervalMs", "300000");        // 多副本配置同步的周期兜底刷新间隔（ms，默认 5min；≤0 暂停）
        d.put("notification.retentionDays", "30");          // 站内通知保留天数（超期由「站内通知清理」任务物理删除，不区分已读未读）
        d.put("notification.cleanupIntervalMs", "86400000"); // 站内通知清理间隔（ms，默认每日；≤0 暂停）
        d.put("notification.dedupWindow", "1440");          // 同类通知合并窗口(分钟)：同目标重复发生的同类通知合并为一条，0=关闭合并
        d.put("workflow.maxConcurrentRuns", "4");          // 第 2 期：异步运行并发上限（派发池 core=max；改后需重启生效）
        d.put("workflow.runQueueCapacity", String.valueOf(ConfigDefaults.WORKFLOW_RUN_QUEUE_CAPACITY));          // 第 2 期：异步运行排队容量（队列满即拒绝，fail-loud 不无限堆积；改后需重启生效）
        d.put("trace.samplingIntervalMs", "86400000");     // P1：Trace 线上采样间隔（ms，默认每日；≤0 暂停）
        d.put("trace.sessionEventEnabled", "true");         // 会话事件账本：每轮结构过程按写入顺序留痕（默认开；关掉后一行不写，问答行为不变）
        d.put("trace.sampleRandomDaily", "20");            // P1：每日随机采样条数（温和策略；0=不采）
        d.put("trace.sampleNoHitDaily", "10");             // P1：每日无引用采样条数（0=不采）；差评恒为必采
        // graphrag.modelRef 已退役（2026-10）：GraphRAG 抽取回落库主个人默认聊天模型（User.defaultModel），
        // 不再单独配置——存量个人键行成为孤儿数据（无读取方）。
        d.put("graphrag.maxTriplesPerChunk", "10");        // P1：单个知识块抽取三元组上限（成本闸）
        d.put("graphrag.batchChunks", "3");                // P1：合并批抽取的块数（3~5 平衡 token 与归属粒度）
        d.put("graphrag.expandTopK", "5");                 // P1：检索时图扩展并入的块数上限（0=不扩展）
        // 用户长期记忆（跨会话个性化）
        d.put("memory.enabled", "true");                   // 总开关：问答后自动提取 + 注入本人后续问答
        d.put("memory.maxPerUser", "50");                  // 每人记忆条数上限（满后自动提取跳过，个人设置可清理）
        d.put("memory.maxInjectCount", "30");              // 每轮最多注入条数（按更新时间取最近）
        d.put("memory.injectBudgetChars", "1500");         // 注入字符预算（防挤占知识上下文）
        d.put("agent.autoRoute", "true");                  // 按需委派：主模型先挑相关的子智能体再咨询
        d.put("agent.dispatchNarrowScope", "false");       // 委派收窄检索范围：挑出子集后主检索收窄到主智能体库∪选中助手库（默认关——路由判错会漏召回）
        d.put("agent.routeTimeoutMs", String.valueOf(ConfigDefaults.AGENT_ROUTE_TIMEOUT_MS));             // 路由判定超时（超时回退全部候选）
        d.put("agent.autoDispatch", "true");               // 自动派遣：对话页选「自动派遣」时按名称+描述路由（关=回落默认智能体）
        // 智能体配置版本快照保留份数（超出按版本号最小先删）；设置页挂在「定时任务 → 智能体配置版本」
        d.put("agent.versionKeep", "20");
        // mcp.enabled / mcp.servers 已移除：MCP **客户端**改为每人自己的 c_ai_user_mcp（见 McpClientService）
        // MCP **服务端**（对外提供端点）：把已发布的智能体暴露给 Claude/Cursor 等外部客户端
        d.put("mcp.server.enabled", "false");      // MCP 端点总开关（默认关：不主动对外暴露能力）
        d.put("mcp.server.timeoutMs", String.valueOf(ConfigDefaults.MCP_SERVER_TIMEOUT_MS));   // 单次工具调用等待问答完成的超时（ms）
        d.put("mcp.server.allowedOrigins", "");    // 允许的 Origin 列表（逗号分隔；空=仅允许回环地址）
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

    /**
     * 个人配置覆盖（个人设置 → 对话偏好）的线程局部快照：由问答流水线在装载身份后一次性载入
     * （键仅限 schema 标记 personal 的字段），只作用于本线程——管理端读取（Tomcat 线程）与
     * 定时任务/评估等无用户上下文的线程读到的仍是全局值。
     * <p>
     * 优先级：显式覆盖（{@link #putOverrides}，评估用）&gt; 个人覆盖 &gt; 全局缓存/默认值。
     * 池化线程复用，finally 必须 clear（见 {@link #clearUserOverrides()}）。
     */
    private static final ThreadLocal<Map<String, String>> USER_OVERRIDE = new ThreadLocal<>();

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

    /**
     * 敏感配置项：值以 RSA 密文入库、快照脱敏为 {@code ****后4位}、恢复默认时跳过。
     * <p>按后缀判定而不是枚举键名——否则每加一个含密钥的配置项（如 OIDC 的 clientSecret）
     * 都要回来补一处判断，漏掉就会明文落库且界面回显完整密钥。</p>
     */
    private static boolean isSensitiveKey(String key) {
        return key != null && (key.endsWith(".apiKey") || key.endsWith(".clientSecret") || key.endsWith(".token"));
    }

    /**
     * 键是否为「活跃槽位」：只有 {@link #defaults()} 定义的键才会被设置页渲染、保存校验、默认值灌入。
     * <p>退役键（chat.model / vision.model / embedding.model）不在其中——存量行对用户不可见也不可改，
     * 引用校验若仍认它们，会形成界面无处解除的死锁（删供应商被看不见的槽位挡住）。</p>
     */
    public boolean isLiveKey(String key) {
        return key != null && defaults().containsKey(key);
    }

    /**
     * 个人专属键（schema personalOnly：模型引用类，无全局槽位语义）。
     * 供删除供应商守门等场景区分「可见可改的活跃槽位」与「个人层键」——后者的引用去留
     * 由各归属用户自己管理，不该以"系统配置槽位"的名义挡住供应商删除（也挡不明白）。
     */
    public boolean isPersonalOnly(String key) {
        return schema.isPersonalOnly(key);
    }

    /** 清除线程局部参数覆盖（评估结束后必须调用） */
    public void clearOverride() {
        OVERRIDE.remove();
    }

    /**
     * 装载本轮用户的个人配置覆盖（个人设置 → 对话偏好；键仅限 schema 标记 personal 的字段）。
     * 调用方（问答流水线线程）负责 finally 里 {@link #clearUserOverrides()}；空/null 视为清除。
     */
    public void putUserOverrides(Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            USER_OVERRIDE.remove();
        } else {
            USER_OVERRIDE.set(new HashMap<>(overrides));
        }
    }

    /** 清除个人配置覆盖（池化线程复用，本轮问答结束必须调用） */
    public void clearUserOverrides() {
        USER_OVERRIDE.remove();
    }

    /** 取当前线程个人配置覆盖的快照（副本，可能为空；供并行子线程重放，见 SubAgentOrchestrator） */
    public Map<String, String> currentUserOverrides() {
        Map<String, String> cur = USER_OVERRIDE.get();
        return cur == null || cur.isEmpty() ? Map.of() : new HashMap<>(cur);
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
     * 读取配置（显式覆盖 → 个人覆盖 → 缓存 → 默认值）。
     * 敏感项 RSA 密文在此透明解密（见 {@link #isSensitiveKey}）：缓存/DB 存密文，消费方拿明文（无前缀的历史明文原样返回，兼容存量）。
     * <p>
     * <b>个人专属键（schema personalOnly）没有全局层</b>：值引用的是某个用户登记的私有资产
     * （模型），残留的全局行一旦被无个人覆盖的线程读到，就是"管理员的模型被全平台使用"——
     * 这里是隔离的最后一道闸：未装载个人覆盖（或未设置）时一律返回空，各消费方按自身口径降级/回落。
     */
    public String get(String key) {
        Map<String, String> ov = OVERRIDE.get();
        if (ov != null && ov.containsKey(key)) return ov.get(key);
        Map<String, String> pv = USER_OVERRIDE.get();
        if (pv != null) {
            String u = pv.get(key);
            if (u != null && !u.isBlank()) return u;
        }
        if (schema.isPersonalOnly(key)) return "";
        String v = cache.get(key);
        if (v == null) v = defaults().getOrDefault(key, "");
        return isSensitiveKey(key) ? crypto.decrypt(v) : v;
    }

    /**
     * 无参 typed getter 的兜底链：缓存/DB 值缺失或解析失败 → 回退 {@code defaults()} 的权威默认值
     * → 仍失败返回中性值。此前各方法散落着魔法兜底（getDouble 兜 0.3 恰是 temperature 的默认、
     * getInt 兜 0 会把 chunk.maxImages 的解析失败变成"不限制"），统一改为跟随声明默认，
     * 仅 defaults() 也未注册该 key 时落到中性值。
     */
    private <T> T getOrDefaultParsed(String key, java.util.function.Function<String, T> parser, T neutral) {
        try {
            return parser.apply(get(key).trim());
        } catch (Exception ignored) {
            // 值缺失/非数字 → 走下方权威默认
        }
        String seed = defaults().get(key);
        try {
            return seed == null ? neutral : parser.apply(seed.trim());
        } catch (Exception e) {
            return neutral;
        }
    }

    public double getDouble(String key) {
        return getOrDefaultParsed(key, Double::parseDouble, 0.0);
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
        return getOrDefaultParsed(key, Integer::parseInt, 0);
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
        return getOrDefaultParsed(key, Long::parseLong, 0L);
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
        return getOrDefaultParsed(key, Boolean::parseBoolean, false);
    }

    /**
     * 带默认值的布尔读取（与 {@link #getInt(String, int)} 同模式）。
     * <p>为什么需要：{@link #getBoolean(String)} 在键未配置时返回 false，对「默认应开启」的新增开关
     * 会静默变成关闭（该键尚未入库时永远读不到）。故新增开关一律用本方法显式给出默认值。
     */
    public boolean getBoolean(String key, boolean def) {
        return getOrDefaultParsed(key, Boolean::parseBoolean, def);
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
        // 掩码回写保护：snapshot 对敏感项脱敏为 "****后4位"，前端未修改时会把掩码原样提交；
        // 掩码值（**** 开头）一律跳过更新，避免覆盖库中真实 key（真实 master key 不可能以 **** 开头）
        updates.entrySet().removeIf(kv ->
                isSensitiveKey(kv.getKey()) && kv.getValue() != null && kv.getValue().startsWith("****"));

        // 个人专属键（模型引用，schema personalOnly）拒绝管理端写入：这类值的归属人是单个用户，
        // 放进全局槽位就是把"某人的模型"用成全平台默认（隔离原则）。设置页已不下发这些字段，
        // 这里拦住构造请求；请到「个人设置」配置（模型引用只允许归属于操作者本人）
        for (String k : updates.keySet()) {
            if (schema.isPersonalOnly(k)) {
                throw new IllegalArgumentException("「" + schema.helpOrDefault(k, k)
                        + "」已调整为个人设置项（模型归属登记人本人），请在「个人设置」中配置");
            }
        }

        // ---------- schema 驱动校验（类型 / 范围 / 枚举 / 布尔）----------
        // 规则全部来自 classpath:config-schema.json（与下发前端渲染的是同一份定义）。此前按前缀分组
        // 手写的校验清单已删除：其中 20 条对应的键早已退出可编辑白名单（死校验），其余 43 条的约束
        // 均已被 schema 覆盖且不更松。窗口与输出的跨字段一致性归模型管理登记时校验（窗口已无全局兜底）。
        for (Map.Entry<String, String> kv : updates.entrySet()) {
            String err = schema.validate(kv.getKey(), kv.getValue());
            if (err != null) throw new IllegalArgumentException(err);
        }
        // 切换到 meilisearch：保存前强制探测服务可用性，不可用则阻止保存（避免切到不可用的空索引）。
        // 这是"服务可达性"而不是"取值合法性"，故留在代码里而不进 schema。
        // 探测必须用**本次提交的值**（updates 里的新地址/新 Key），不能用 checkAvailable() 读已保存的旧值：
        // 「改地址 + 切引擎」常在同一次保存里完成（地址与 Key 只有选中 meilisearch 后才可见，用户是
        // 填好地址后一起保存的），拿旧值探测等于用上一次的服务地址去验这一次的新地址——首次配置
        // 必然被误判为不可用而拒绝保存，用户陷入死锁。updates 未带的键才回落已保存配置。
        String engine = updates.get("keyword.engine");
        if (engine != null && "meilisearch".equalsIgnoreCase(engine.trim())) {
            boolean ok = keywordIndexService.checkAvailable(updates.get("keyword.baseUrl"), updates.get("keyword.apiKey"));
            if (!ok) {
                String url = updates.getOrDefault("keyword.baseUrl", keywordIndexService.debugUnavailableReason());
                throw new IllegalArgumentException("Meilisearch 服务不可用（"
                        + (url == null || url.isBlank() ? "探测失败" : "地址 " + url + " 探测失败")
                        + "），请先启动 Meilisearch（docker compose 或本地）再切换");
            }
        }

        // 视觉网关地址校验已移除：vision.baseUrl 不在可编辑白名单、也无运行时读取点
        // （视觉网关来自供应商表），保留这段只会校验一个永远不会被消费的键

        // 敏感 key RSA 加密入库：明文→密文（已加密值原样保留；空值不加密直接存空）
        for (Map.Entry<String, String> kv : updates.entrySet()) {
            String v = kv.getValue();
            if (isSensitiveKey(kv.getKey()) && v != null && !v.isBlank() && !crypto.isEncrypted(v)) {
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
     *   <li><b>跳过敏感项</b>（*.apiKey / *.clientSecret）：密钥以 RSA 加密存于 DB，恢复默认不得清空用户已配置的密钥
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
            if (isSensitiveKey(k)) continue; // 密钥不随"恢复默认"清空
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

    /** 全量配置（供配置界面展示；敏感项脱敏） */
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
                if (isSensitiveKey(d.getKey()) && value.length() > 4) {
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
