package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.config.AppProperties;
import com.wisesoft.ai.mapper.ModelInfoMapper;
import com.wisesoft.ai.mapper.ProviderMapper;
import com.wisesoft.ai.model.KnowledgeBase;
import com.wisesoft.ai.model.ModelInfo;
import com.wisesoft.ai.model.Provider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 模型供应商注册中心：供应商（OpenAI 兼容网关档案）+ 模型库（按类型分类登记）的统一管理入口。
 * <p>
 * <h3>模型引用格式</h3>
 * 所有存模型的位置（chat.model / agent.model / 用户偏好 / 会话覆盖等）统一用 {@code {providerId}/{modelId}}
 * 引用格式；解析时按第一段 providerId 查本表得到网关（baseUrl/apiKey/路径），第二段起为原样模型名
 * （兼容 OpenRouter 等模型名自带斜杠的网关）。<b>兼容策略</b>：值不含可识别引用（providerId 查不到）
 * 时按「遗留纯模型名」处理，回落全局 chat.* 配置的网关——存量数据无需刷库即可继续工作。
 * <p>
 * <h3>路由与缓存</h3>
 * {@link #chatRoute} / {@link #embeddingRoute} / {@link #visionRoute} / {@link #rerankRoute} 返回
 * {@link ModelRoute}（含解密后的网关信息），供 DynamicOpenAiChatModel / DynamicEmbeddingModel /
 * VisionService / RerankService 构建客户端；内存缓存 + Redis 广播失效（多实例同步，模式同 ConfigService）。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ModelRegistryService {

    /** 供应商/模型变更广播 channel（多实例同步：任意实例变更 → 其他实例重载缓存） */
    public static final String PROVIDER_CHANNEL = "ai:provider:changed";

    public static final String TYPE_CHAT = "chat";
    public static final String TYPE_VISION = "vision";
    public static final String TYPE_EMBEDDING = "embedding";
    public static final String TYPE_RERANK = "rerank";
    public static final String TYPE_OTHER = "other";
    public static final List<String> TYPES = List.of(TYPE_CHAT, TYPE_VISION, TYPE_EMBEDDING, TYPE_RERANK, TYPE_OTHER);

    private final ProviderMapper providerMapper;
    private final ModelInfoMapper modelMapper;
    private final com.wisesoft.ai.mapper.AgentMapper agentMapper;
    private final com.wisesoft.ai.mapper.UserMapper userMapper;
    private final com.wisesoft.ai.mapper.ConfigMapper configMapper;
    private final com.wisesoft.ai.mapper.KnowledgeBaseMapper kbMapper;
    private final ConfigService configService;
    private final ConfigCryptoService crypto;
    private final StringRedisTemplate redisTemplate;
    private final RedisProperties redisProperties;
    private final Environment environment;

    private volatile List<Provider> providers = List.of();
    private volatile List<ModelInfo> models = List.of();

    public ModelRegistryService(ProviderMapper providerMapper, ModelInfoMapper modelMapper,
                                com.wisesoft.ai.mapper.AgentMapper agentMapper,
                                com.wisesoft.ai.mapper.UserMapper userMapper,
                                com.wisesoft.ai.mapper.ConfigMapper configMapper,
                                com.wisesoft.ai.mapper.KnowledgeBaseMapper kbMapper,
                                ConfigService configService, ConfigCryptoService crypto,
                                StringRedisTemplate redisTemplate, RedisProperties redisProperties,
                                Environment environment) {
        this.providerMapper = providerMapper;
        this.modelMapper = modelMapper;
        this.agentMapper = agentMapper;
        this.userMapper = userMapper;
        this.configMapper = configMapper;
        this.kbMapper = kbMapper;
        this.configService = configService;
        this.crypto = crypto;
        this.redisTemplate = redisTemplate;
        this.redisProperties = redisProperties;
        this.environment = environment;
    }

    @PostConstruct
    public void init() {
        reload();
        migrateLegacyConfigs();
        startRedisSync();
        log.info("[Provider] 供应商注册中心加载完成: {} 个供应商, {} 个模型", providers.size(), models.size());
    }

    /** 全量重读供应商与模型库（本地变更 / Redis 订阅通知时调用） */
    public void reload() {
        try {
            List<Provider> ps = providerMapper.selectList(new LambdaQueryWrapper<Provider>());
            List<ModelInfo> ms = modelMapper.selectList(new LambdaQueryWrapper<ModelInfo>());
            providers = ps;
            models = ms;
        } catch (Exception e) {
            log.warn("[Provider] 供应商缓存重载失败: {}", e.getMessage());
        }
    }

    /** 多实例同步：daemon 线程订阅变更广播（模式同 ConfigService），Redis 不可用仅告警 */
    private void startRedisSync() {
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
                        }
                    }, PROVIDER_CHANNEL);
                } catch (Exception e) {
                    log.warn("[Provider] Redis 同步订阅中断，5s 后重连: {}", e.getMessage());
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "provider-redis-sync");
        t.setDaemon(true);
        t.start();
    }

    /** 本地变更后：重载缓存 + 广播其他实例（Redis 异常不影响保存结果） */
    private void changed() {
        reload();
        try {
            redisTemplate.convertAndSend(PROVIDER_CHANNEL, "changed");
        } catch (Exception e) {
            log.debug("[Provider] 变更广播失败: {}", e.getMessage());
        }
    }

    // ==================== 引用格式与路由解析 ====================

    /**
     * 按引用格式解析网关路由：{@code {providerId}/{modelId}}（按第一个斜杠切分，模型名可自带斜杠）。
     * 非引用格式 / providerId 不存在 → null（调用方回落遗留逻辑）。
     */
    public ModelRoute resolveReference(String value) {
        if (value == null) return null;
        String v = value.trim();
        int i = v.indexOf('/');
        if (i <= 0 || i == v.length() - 1) return null;
        Provider p = providerById(v.substring(0, i));
        if (p == null) return null;
        String modelId = v.substring(i + 1);
        String display = displayNameOf(p, modelId);
        return new ModelRoute(p.getId(), p.getBaseUrl(), crypto.decrypt(p.getApiKey()),
                p.getCompletionsPath(), p.getEmbeddingsPath(), modelId, display);
    }

    /**
     * 聊天模型路由：引用 → 供应商网关；遗留纯模型名 → 全局 chat.* 配置的网关（env 兜底，与原
     * DynamicOpenAiChatModel.resolve 语义一致，供注册中心上线前配置的遗留智能体继续工作）。
     * 恒非 null；模型名为空串时由调用方兜底（问答入口已 fail-loud 引导配置，全局 chat.model 兜底已移除）。
     */
    public ModelRoute chatRoute(String modelValue) {
        ModelRoute r = resolveReference(modelValue);
        if (r != null) return r;
        String model = (modelValue == null || modelValue.isBlank())
                ? "" : modelValue.trim();
        return new ModelRoute(null,
                firstNonBlank(configService.get("chat.baseUrl"), environment.getProperty("spring.ai.openai.base-url", "")),
                firstNonBlank(configService.get("chat.apiKey"), environment.getProperty("spring.ai.openai.api-key", "")),
                configService.get("chat.completionsPath"), null, model, model);
    }

    /** 向量路由（遗留全局客户端用）：引用/全局键 → 供应商；遗留 → embedding.* 配置（env 兜底）。
     *  向量模型本体已归知识库 embedding_ref，本方法仅供全局 VectorStore bean（回滚缓冲）的
     *  {@link DynamicEmbeddingModel#current()} 路由，不再作为任何业务运行时默认。 */
    public ModelRoute embeddingRoute() {
        String model = firstNonBlank(configService.get("embedding.model"),
                environment.getProperty("spring.ai.openai.embedding.options.model", ""));
        return embeddingRoute(model);
    }

    /** 向量路由（按模型名）：引用 → 供应商网关；遗留纯模型名 → embedding.* 遗留网关 + 该模型名。
     *  供知识库遗留模型引用（启动迁移回填的历史全局值）与全局客户端路由共用。 */
    public ModelRoute embeddingRoute(String modelValue) {
        String model = nz(modelValue);
        ModelRoute r = resolveReference(model);
        if (r != null) return r;
        return new ModelRoute(null,
                firstNonBlank(configService.get("embedding.baseUrl"), environment.getProperty("spring.ai.openai.embedding.base-url", "")),
                firstNonBlank(configService.get("embedding.apiKey"), environment.getProperty("spring.ai.openai.embedding.api-key", "")),
                null, configService.get("embedding.embeddingsPath"), model, model);
    }

    /** 重排路由：rerank.model 引用 → 供应商；遗留 → rerank.* 配置（本地 reranker 服务）。
     *  模型值可被知识库/智能体的检索参数覆盖（线程局部 rerank.model 覆盖经 ConfigService.get 生效）。 */
    public ModelRoute rerankRoute() {
        String model = nz(configService.get("rerank.model"));
        ModelRoute r = resolveReference(model);
        if (r != null) return r;
        return new ModelRoute(null, nz(configService.get("rerank.baseUrl")), null, null, null, model, model);
    }

    /**
     * 模型路由（值对象）：providerId=null 表示遗留全局网关；apiKey 已解密（日志勿明文打印）。
     * 指纹方法用归一化后的网关信息（复用 DynamicOpenAiChatModel.normalize），等值即同一客户端。
     */
    public record ModelRoute(String providerId, String baseUrl, String apiKey,
                             String completionsPath, String embeddingsPath,
                             String modelId, String displayName) {

        /** 聊天客户端指纹：baseUrl|completionsPath|apiKey */
        public String chatFingerprint() {
            String[] np = DynamicOpenAiChatModel.normalize(baseUrl, completionsPath,
                    DynamicOpenAiChatModel.DEFAULT_COMPLETIONS_PATH, "/chat/completions");
            return np[0] + "|" + np[1] + "|" + s(apiKey);
        }

        /** 向量客户端指纹：baseUrl|embeddingsPath|model|apiKey */
        public String embeddingFingerprint() {
            String[] np = DynamicOpenAiChatModel.normalize(baseUrl, embeddingsPath,
                    DynamicEmbeddingModel.DEFAULT_EMBEDDINGS_PATH, "/embeddings");
            return np[0] + "|" + np[1] + "|" + s(modelId) + "|" + s(apiKey);
        }

        private static String s(String v) {
            return v == null ? "" : v;
        }
    }

    // ==================== 查询 ====================

    public Provider providerById(String id) {
        if (id == null || id.isBlank()) return null;
        for (Provider p : providers) {
            if (id.equals(p.getId())) return p;
        }
        return null;
    }

    /** 已存供应商的解密后 apiKey（供连通性测试等需要真实 Key 的场景；供应商不存在返回 null） */
    public String decryptedApiKey(String providerId) {
        Provider p = providerMapper.selectById(providerId);
        return p == null ? null : crypto.decrypt(p.getApiKey());
    }

    private String displayNameOf(Provider p, String modelId) {
        for (ModelInfo m : models) {
            if (p.getId().equals(m.getProviderId()) && modelId.equals(m.getModelId())
                    && m.getDisplayName() != null && !m.getDisplayName().isBlank()) {
                return m.getDisplayName();
            }
        }
        return modelId;
    }

    /** 引用对应模型的登记类型（chat/vision/embedding/rerank/other；非引用或未登记返回 null） */
    public String referenceType(String value) {
        ModelRoute r = resolveReference(value);
        if (r == null) return null;
        for (ModelInfo mi : models) {
            if (r.providerId().equals(mi.getProviderId()) && r.modelId().equals(mi.getModelId())) {
                return mi.getModelType();
            }
        }
        return null;
    }

    /** 供应商列表（管理界面；apiKey 脱敏为 ****后4位） */
    public List<Map<String, Object>> listProviders() {
        List<Provider> ps = new ArrayList<>(providers);
        ps.sort(Comparator.comparingInt((Provider p) -> p.getSortOrder() == null ? 0 : p.getSortOrder())
                .thenComparing(p -> nz(p.getName())));
        List<Map<String, Object>> result = new ArrayList<>(ps.size());
        for (Provider p : ps) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("icon", p.getIcon());
            m.put("baseUrl", p.getBaseUrl());
            m.put("apiKeyMasked", mask(p.getApiKey()));
            m.put("completionsPath", p.getCompletionsPath());
            m.put("embeddingsPath", p.getEmbeddingsPath());
            m.put("apiType", p.getApiType());
            m.put("enabled", !Integer.valueOf(0).equals(p.getEnabled()));
            m.put("remark", p.getRemark());
            m.put("sortOrder", p.getSortOrder());
            Map<String, Integer> typeCounts = new java.util.LinkedHashMap<>();
            for (String t : TYPES) typeCounts.put(t, 0);
            int total = 0;
            for (ModelInfo mi : models) {
                if (p.getId().equals(mi.getProviderId())) {
                    total++;
                    typeCounts.merge(mi.getModelType() == null ? TYPE_OTHER : mi.getModelType(), 1, Integer::sum);
                }
            }
            m.put("modelCount", total);
            m.put("typeCounts", typeCounts);
            result.add(m);
        }
        return result;
    }

    /** 某供应商的模型列表（管理界面） */
    public List<Map<String, Object>> listModels(String providerId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ModelInfo mi : models) {
            if (!providerId.equals(mi.getProviderId())) continue;
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", mi.getId());
            m.put("modelId", mi.getModelId());
            m.put("displayName", mi.getDisplayName());
            m.put("modelType", mi.getModelType());
            m.put("thinking", resolveThinking(mi));
            m.put("enabled", !Integer.valueOf(0).equals(mi.getEnabled()));
            m.put("remark", mi.getRemark());
            result.add(m);
        }
        result.sort(Comparator.comparing(m -> String.valueOf(m.get("modelId"))));
        return result;
    }

    /** 思考能力合法值：auto=按模型名判定（存储默认） none=不支持 switchable=可开关 always=恒思考 */
    public static final String THINK_AUTO = "auto";
    public static final String THINK_NONE = "none";
    public static final String THINK_SWITCHABLE = "switchable";
    public static final String THINK_ALWAYS = "always";
    public static final List<String> THINKING_LEVELS =
            List.of(THINK_AUTO, THINK_NONE, THINK_SWITCHABLE, THINK_ALWAYS);

    /** 恒思考模型的名称特征（DeepSeek-R1 / QwQ / OpenAI o 系 / 带 thinking 字样） */
    private static final List<String> ALWAYS_THINK_TOKENS = List.of("r1", "qwq", "thinking");
    private static final List<String> ALWAYS_THINK_PREFIXES = List.of("o1", "o3", "o4");
    /** 可开关思考模型的名称特征（qwen3 / glm-4+ / doubao-seed / deepseek-v3 / claude / gemini 等） */
    private static final List<String> SWITCHABLE_THINK_TOKENS =
            List.of("qwen3", "glm-4", "glm-5", "doubao-seed", "deepseek-v3", "claude", "gemini", "hybrid");

    /**
     * 按模型名启发式判定思考能力（仅 thinking=auto 档兜底；管理员在模型库可显式覆盖）：
     * 命中恒思考特征 → always；命中可开关特征 → switchable；其余 → none。
     */
    public static String guessThinking(String modelId) {
        String m = (modelId == null ? "" : modelId).toLowerCase();
        for (String t : ALWAYS_THINK_TOKENS) if (m.contains(t)) return THINK_ALWAYS;
        for (String p : ALWAYS_THINK_PREFIXES) if (m.startsWith(p)) return THINK_ALWAYS;
        for (String t : SWITCHABLE_THINK_TOKENS) if (m.contains(t)) return THINK_SWITCHABLE;
        return THINK_NONE;
    }

    /** 解析登记行的最终思考能力：auto → 按模型名启发式；空 → auto */
    public static String resolveThinking(ModelInfo mi) {
        String v = mi == null || mi.getThinking() == null || mi.getThinking().isBlank()
                ? THINK_AUTO : mi.getThinking();
        return THINK_AUTO.equals(v) ? guessThinking(mi.getModelId()) : v;
    }

    /**
     * 按模型引用解析思考能力（问答入口归一 deepThink 用）：
     * 引用 → 查模型库登记（auto/空 → 启发式）；引用无效或遗留裸名 → switchable（保持现状行为）。
     */
    public String referenceThinking(String modelValue) {
        ModelRegistryService.ModelRoute r = resolveReference(modelValue);
        if (r == null) return THINK_SWITCHABLE;
        for (ModelInfo mi : models) {
            if (r.providerId().equals(mi.getProviderId()) && r.modelId().equals(mi.getModelId())) {
                return resolveThinking(mi);
            }
        }
        return THINK_SWITCHABLE;
    }

    /**
     * 可用模型清单（选择器数据源，登录即可见）：enabled 供应商下 enabled 模型，按类型过滤，
     * 引用串预先拼好（ref = providerId/modelId）。不暴露 baseUrl/apiKey。
     */
    public List<Map<String, Object>> available(String type) {
        List<Provider> ps = new ArrayList<>(providers);
        ps.sort(Comparator.comparingInt((Provider p) -> p.getSortOrder() == null ? 0 : p.getSortOrder())
                .thenComparing(p -> nz(p.getName())));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Provider p : ps) {
            if (Integer.valueOf(0).equals(p.getEnabled())) continue;
            List<Map<String, Object>> ms = new ArrayList<>();
            for (ModelInfo mi : models) {
                if (!p.getId().equals(mi.getProviderId())) continue;
                if (Integer.valueOf(0).equals(mi.getEnabled())) continue;
                if (type != null && !type.isBlank() && !type.equals(mi.getModelType())) continue;
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("ref", p.getId() + "/" + mi.getModelId());
                m.put("modelId", mi.getModelId());
                m.put("displayName", mi.getDisplayName() == null || mi.getDisplayName().isBlank()
                        ? mi.getModelId() : mi.getDisplayName());
                m.put("type", mi.getModelType());
                m.put("thinking", resolveThinking(mi));
                ms.add(m);
            }
            if (ms.isEmpty()) continue;
            Map<String, Object> g = new java.util.LinkedHashMap<>();
            g.put("providerId", p.getId());
            g.put("name", p.getName());
            g.put("icon", p.getIcon());
            g.put("models", ms);
            result.add(g);
        }
        return result;
    }

    // ==================== 供应商 / 模型 CRUD ====================

    /**
     * 新建/更新供应商。rawApiKey 为空或 **** 掩码时保留库中已存密钥（编辑场景未重输 Key）。
     */
    public Provider saveProvider(String id, String name, String icon, String baseUrl, String rawApiKey,
                                 String completionsPath, String embeddingsPath, String apiType,
                                 Boolean enabled, String remark, Integer sortOrder, String operator) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("供应商名称不能为空");
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("网关地址需以 http:// 或 https:// 开头");
        }
        for (String p : new String[]{completionsPath, embeddingsPath}) {
            if (p != null && !p.isBlank() && !p.trim().startsWith("/")) {
                throw new IllegalArgumentException("路径需以 / 开头（留空用默认值）");
            }
        }
        Provider p = id == null || id.isBlank() ? null : providerMapper.selectById(id);
        boolean isNew = p == null;
        if (isNew) {
            p = new Provider();
            p.setId(UUID.randomUUID().toString());
            p.setCreatedBy(operator);
        }
        p.setName(name.trim());
        p.setIcon(icon == null ? "" : icon.trim());
        p.setBaseUrl(url);
        // 空值/掩码 → 保留已存密钥（isNew 时空值存空，Ollama 等无 Key 网关）
        if (rawApiKey != null && !rawApiKey.isBlank() && !rawApiKey.startsWith("****")) {
            p.setApiKey(crypto.encrypt(rawApiKey.trim()));
        } else if (p.getApiKey() == null) {
            p.setApiKey("");
        }
        p.setCompletionsPath(completionsPath == null ? "" : completionsPath.trim());
        p.setEmbeddingsPath(embeddingsPath == null ? "" : embeddingsPath.trim());
        p.setApiType(apiType == null || apiType.isBlank() ? "openai" : apiType.trim());
        p.setEnabled(enabled == null || enabled ? 1 : 0);
        p.setRemark(remark == null ? "" : remark.trim());
        p.setSortOrder(sortOrder == null ? 0 : sortOrder);
        if (isNew) {
            providerMapper.insert(p);
            log.info("[Provider] 供应商已创建: {}（{}）by {}", p.getName(), p.getBaseUrl(), operator);
        } else {
            providerMapper.updateById(p);
            log.info("[Provider] 供应商已更新: {}（{}）by {}", p.getName(), p.getBaseUrl(), operator);
        }
        changed();
        return p;
    }

    /**
     * 删除供应商（连同其模型登记）。被引用（智能体模型 / 用户默认模型 / 系统配置槽位）时拒绝。
     */
    public void deleteProvider(String id) {
        Provider p = providerMapper.selectById(id);
        if (p == null) throw new IllegalArgumentException("供应商不存在");
        String prefix = id + "/";
        List<String> refs = new ArrayList<>();
        Long userRefs = userMapper.selectCount(new LambdaQueryWrapper<com.wisesoft.ai.model.User>()
                .likeRight(com.wisesoft.ai.model.User::getDefaultModel, prefix)
                .or()
                .likeRight(com.wisesoft.ai.model.User::getDefaultVisionModel, prefix));
        if (userRefs != null && userRefs > 0) refs.add("个人默认模型 ×" + userRefs);
        Long kbRefs = kbMapper.selectCount(new LambdaQueryWrapper<KnowledgeBase>()
                .likeRight(KnowledgeBase::getEmbeddingRef, prefix));
        if (kbRefs != null && kbRefs > 0) refs.add("知识库绑定向量模型 ×" + kbRefs);
        Long configRefs = configMapper.selectCount(new LambdaQueryWrapper<com.wisesoft.ai.model.Config>()
                .likeRight(com.wisesoft.ai.model.Config::getConfigValue, prefix));
        if (configRefs != null && configRefs > 0) refs.add("系统配置槽位 ×" + configRefs);
        if (!refs.isEmpty()) {
            throw new IllegalArgumentException("供应商「" + p.getName() + "」仍被引用（" + String.join("、", refs)
                    + "），请先在个人设置/知识库改用其他模型");
        }
        modelMapper.delete(new LambdaQueryWrapper<ModelInfo>().eq(ModelInfo::getProviderId, id));
        providerMapper.deleteById(id);
        log.info("[Provider] 供应商已删除: {}（{}）", p.getName(), p.getBaseUrl());
        changed();
    }

    /** 更新供应商启停状态（列表开关） */
    public void setProviderEnabled(String id, boolean enabled) {
        Provider p = providerMapper.selectById(id);
        if (p == null) throw new IllegalArgumentException("供应商不存在");
        p.setEnabled(enabled ? 1 : 0);
        providerMapper.updateById(p);
        changed();
    }

    /**
     * 批量保存供应商模型（全量同步语义）：入库/更新 incoming，删除该供应商下不在 incoming 中的登记。
     * modelId 重复时保留首条。
     */
    public void saveModels(String providerId, List<Map<String, Object>> incoming) {
        Provider p = providerMapper.selectById(providerId);
        if (p == null) throw new IllegalArgumentException("供应商不存在");
        List<ModelInfo> desired = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> item : incoming == null ? List.<Map<String, Object>>of() : incoming) {
            String modelId = str(item.get("modelId"));
            if (modelId == null || modelId.isBlank() || !seen.add(modelId)) continue;
            String type = str(item.get("modelType"));
            if (!TYPES.contains(type)) type = TYPE_CHAT;
            ModelInfo mi = new ModelInfo();
            mi.setProviderId(providerId);
            mi.setModelId(modelId.trim());
            mi.setDisplayName(str(item.get("displayName")));
            mi.setModelType(type);
            String thinking = str(item.get("thinking"));
            mi.setThinking(THINKING_LEVELS.contains(thinking) ? thinking : "auto");
            Object en = item.get("enabled");
            mi.setEnabled(en == null || Boolean.parseBoolean(String.valueOf(en)) ? 1 : 0);
            mi.setRemark(str(item.get("remark")));
            desired.add(mi);
        }
        // 全量同步：模型登记是纯配置数据且量小，顺序写即可（中途失败重开弹窗重存即可恢复）
        for (ModelInfo mi : modelMapper.selectList(new LambdaQueryWrapper<ModelInfo>().eq(ModelInfo::getProviderId, providerId))) {
            boolean keep = desired.stream().anyMatch(d -> d.getModelId().equals(mi.getModelId()));
            if (!keep) modelMapper.deleteById(mi.getId());
        }
        for (ModelInfo d : desired) {
            ModelInfo exist = modelMapper.selectOne(new LambdaQueryWrapper<ModelInfo>()
                    .eq(ModelInfo::getProviderId, providerId).eq(ModelInfo::getModelId, d.getModelId()));
            if (exist != null) {
                d.setId(exist.getId());
                modelMapper.updateById(d);
            } else {
                d.setId(UUID.randomUUID().toString());
                modelMapper.insert(d);
            }
        }
        log.info("[Provider] 供应商 {} 模型库已保存: {} 个", p.getName(), desired.size());
        changed();
    }

    // ==================== 远程拉取模型列表 ====================

    /**
     * 远程拉取网关模型列表（GET {baseUrl}/v1/models，Bearer 鉴权），返回候选清单（不入库）：
     * {modelId, guessedType(名称启发式自动分类), exists(是否已登记)}。
     * 网关未实现 /v1/models 时抛出带原因的异常（前端提示改用手动添加）。
     */
    public List<Map<String, Object>> fetchRemoteModels(String providerId, String baseUrl, String rawApiKey) {
        // 编辑已存供应商且 Key 为掩码/空 → 用库中真实 Key
        String key = rawApiKey;
        if (providerId != null && !providerId.isBlank() && (key == null || key.isBlank() || key.startsWith("****"))) {
            Provider p = providerMapper.selectById(providerId);
            if (p != null) key = crypto.decrypt(p.getApiKey());
        }
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("网关地址需以 http:// 或 https:// 开头");
        }
        String[] np = DynamicOpenAiChatModel.normalize(url, "", "/v1/models", "/models");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        String body;
        try {
            body = RestClient.builder().requestFactory(factory).build().get()
                    .uri(np[0] + np[1])
                    .header("Authorization", "Bearer " + (key == null ? "" : key))
                    .retrieve().body(String.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw new IllegalArgumentException("网关返回 " + e.getStatusCode().value() + "："
                    + trim(e.getResponseBodyAsString()));
        } catch (Exception e) {
            throw new IllegalArgumentException("无法连接网关：" + trim(rootMessage(e)));
        }
        if (body == null || body.isBlank()) throw new IllegalArgumentException("网关返回为空");
        try {
            JSONObject root = JSON.parseObject(body);
            JSONArray data = root.getJSONArray("data");
            if (data == null || data.isEmpty()) throw new IllegalArgumentException("网关未返回模型列表（data 为空）");
            java.util.Set<String> registered = new java.util.HashSet<>();
            if (providerId != null && !providerId.isBlank()) {
                for (ModelInfo mi : models) {
                    if (providerId.equals(mi.getProviderId())) registered.add(mi.getModelId());
                }
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (int i = 0; i < data.size(); i++) {
                String modelId = data.getJSONObject(i).getString("id");
                if (modelId == null || modelId.isBlank()) continue;
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("modelId", modelId);
                m.put("guessedType", guessType(modelId));
                m.put("exists", registered.contains(modelId));
                result.add(m);
            }
            result.sort(Comparator.comparing(m -> String.valueOf(m.get("modelId"))));
            log.info("[Provider] 远程拉取模型列表成功: {} → {} 个模型", url, result.size());
            return result;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("网关响应不是有效的 OpenAI /v1/models 格式：" + trim(rootMessage(e)));
        }
    }

    /**
     * 模型类型名称启发式自动分类（拉取候选的默认值，用户在界面可改）：
     * rerank/ranker → 重排；embed/bge/gte → 向量；vision/vl/llava → 视觉；
     * dall/whisper/tts/moderation/davinci 等 → 其他；其余 → 聊天。
     */
    public static String guessType(String modelId) {
        String m = (modelId == null ? "" : modelId).toLowerCase();
        if (m.contains("rerank") || m.contains("ranker")) return TYPE_RERANK;
        if (m.contains("embed") || m.startsWith("bge-") || m.startsWith("gte-") || m.contains("/embedding")) return TYPE_EMBEDDING;
        if (m.contains("vision") || m.contains("llava") || m.contains("internvl") || m.contains("qvq")
                || VL_TOKEN.matcher(m).find()) return TYPE_VISION;
        if (m.contains("dall") || m.contains("whisper") || m.contains("tts") || m.contains("moderation")
                || m.contains("davinci") || m.contains("babbage") || m.contains("stable-diffusion")
                || m.contains("flux") || m.contains("sora") || m.contains("video")) return TYPE_OTHER;
        return TYPE_CHAT;
    }

    /** 视觉模型名里的 vl 词元（前后非小写字母界定，如 qwen2-vl-72b / 4vl? 避免误伤普通词） */
    private static final Pattern VL_TOKEN = Pattern.compile(".*(^|[^a-z])vl([^a-z]|$).*");

    /** 供应商名猜测时跳过的域名段（常见前缀 + 顶级域） */
    private static final List<String> SKIP_DOMAIN_PARTS =
            List.of("api", "open", "aip", "ark", "gateway", "chat", "console", "dashscope",
                    "com", "cn", "net", "org", "ai", "cloud", "co");

    // ==================== 存量配置迁移 ====================

    /**
     * 存量四组手填配置（chat/embedding/vision/rerank 的 baseUrl+apiKey+模型名）迁移为内置供应商 + 模型登记，
     * 配置值改写为引用。幂等：值已是引用或网关信息为空则跳过；同网关（归一化 baseUrl + Key 相同）复用同一供应商。
     * 直接落库 + putInternal 改写配置值，不走 update() 联动（绝不触发全量重嵌入）。
     */
    private void migrateLegacyConfigs() {
        // chat 组已不再迁移：chat.model 全局兜底退役后其迁移产物无消费方（模型解析链止于智能体/个人默认）
        Map<String, String[]> groups = Map.of(
                "embedding", new String[]{"embedding.baseUrl", "embedding.apiKey", "embedding.embeddingsPath"},
                "vision", new String[]{"vision.baseUrl", "vision.apiKey", null},
                "rerank", new String[]{"rerank.baseUrl", null, null});
        Map<String, String> typeByGroup = Map.of(
                "embedding", TYPE_EMBEDDING, "vision", TYPE_VISION, "rerank", TYPE_RERANK);
        // 未启用的功能不迁移（yml 兜底默认值也非空，避免为从未用过的本地 vision/rerank 建供应商）；
        // 之后启用时走遗留解析（vision.*/rerank.* 原值未动，行为不变）
        Map<String, Boolean> enabledByGroup = Map.of(
                "embedding", true,
                "vision", configService.getBoolean("vision.enabled"),
                "rerank", configService.getBoolean("rerank.enabled"));
        int migrated = 0;
        for (Map.Entry<String, String[]> g : groups.entrySet()) {
            try {
                String group = g.getKey();
                if (!Boolean.TRUE.equals(enabledByGroup.get(group))) continue;
                String model = configService.get(group + ".model");
                if (model == null || model.isBlank() || resolveReference(model) != null) continue;
                String baseUrl = nz(configService.get(g.getValue()[0]));
                if (baseUrl.isBlank()) continue;
                String apiKey = g.getValue()[1] == null ? "" : nz(configService.get(g.getValue()[1]));
                String path = g.getValue()[2] == null ? "" : nz(configService.get(g.getValue()[2]));
                Provider p = findOrMergeProvider(baseUrl, apiKey, path, group);
                upsertModel(p.getId(), model.trim(), typeByGroup.get(group));
                configService.putInternal(group + ".model", p.getId() + "/" + model.trim());
                reload();
                migrated++;
                log.info("[Provider] 存量 {} 模型已迁移: {} → {}/{}", group, model, p.getName(), model.trim());
            } catch (Exception e) {
                log.warn("[Provider] {} 组存量迁移失败（保持原配置，不影响启动）: {}", g.getKey(), e.getMessage());
            }
        }
        if (migrated > 0) {
            log.info("[Provider] 存量模型配置迁移完成: {} 组改写为供应商引用", migrated);
        }
    }

    /** 按归一化 baseUrl + Key（明文比对）找同网关供应商，无则创建（名称/图标按域名猜测）；
     *  合并已有供应商时补齐该组对应的路径（如同网关的 embedding 组带 embeddingsPath） */
    private Provider findOrMergeProvider(String baseUrl, String apiKey, String path, String group) {
        String url = baseUrl.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        boolean embeddingGroup = "embedding".equals(group);
        for (Provider p : providers) {
            String pu = p.getBaseUrl() == null ? "" : p.getBaseUrl().trim();
            while (pu.endsWith("/")) pu = pu.substring(0, pu.length() - 1);
            if (!pu.equalsIgnoreCase(url)) continue;
            String pk = crypto.decrypt(p.getApiKey());
            if ((apiKey == null || apiKey.isBlank()) ? (pk == null || pk.isBlank()) : apiKey.equals(pk)) {
                // 同网关合并：当前组路径非空且供应商上缺失时补齐（chat 先迁移时 embedding 的 /v4/embeddings 不丢）
                if (embeddingGroup && !path.isBlank() && (p.getEmbeddingsPath() == null || p.getEmbeddingsPath().isBlank())) {
                    p.setEmbeddingsPath(path);
                    providerMapper.updateById(p);
                    reload();
                } else if (!embeddingGroup && !path.isBlank()
                        && (p.getCompletionsPath() == null || p.getCompletionsPath().isBlank())) {
                    p.setCompletionsPath(path);
                    providerMapper.updateById(p);
                    reload();
                }
                return p;
            }
        }
        Provider p = new Provider();
        p.setId(UUID.randomUUID().toString());
        p.setName(guessProviderName(url));
        p.setIcon(guessProviderIcon(url));
        p.setBaseUrl(url);
        p.setApiKey(apiKey == null || apiKey.isBlank() ? "" : crypto.encrypt(apiKey));
        if (embeddingGroup) p.setEmbeddingsPath(path);
        else p.setCompletionsPath(path);
        p.setApiType("openai");
        p.setEnabled(1);
        p.setRemark("由系统设置自动迁移生成");
        p.setSortOrder(0);
        p.setCreatedBy("system");
        providerMapper.insert(p);
        reload();
        log.info("[Provider] 存量配置迁移创建供应商: {}（{}）", p.getName(), url);
        return p;
    }

    private void upsertModel(String providerId, String modelId, String type) {
        ModelInfo exist = modelMapper.selectOne(new LambdaQueryWrapper<ModelInfo>()
                .eq(ModelInfo::getProviderId, providerId).eq(ModelInfo::getModelId, modelId));
        if (exist != null) {
            exist.setModelType(type);
            exist.setEnabled(1);
            modelMapper.updateById(exist);
            return;
        }
        ModelInfo mi = new ModelInfo();
        mi.setId(UUID.randomUUID().toString());
        mi.setProviderId(providerId);
        mi.setModelId(modelId);
        mi.setModelType(type);
        mi.setEnabled(1);
        modelMapper.insert(mi);
    }

    /** 网关域名 → 供应商名（如 api.deepseek.com → DeepSeek；localhost → 本地网关） */
    static String guessProviderName(String baseUrl) {
        String host = hostOf(baseUrl);
        if (host == null) return "未知网关";
        if (host.equals("localhost") || host.equals("127.0.0.1") || host.startsWith("192.168.") || host.startsWith("10.")) {
            return "本地网关";
        }
        // 取主域段：跳过常见前缀与顶级域（api.deepseek.com → DeepSeek；open.bigmodel.cn → Bigmodel）
        for (String part : host.split("\\.")) {
            if (!SKIP_DOMAIN_PARTS.contains(part)) {
                return part.substring(0, 1).toUpperCase() + part.substring(1);
            }
        }
        return host;
    }

    /** 网关域名 → 内置图标 key（识别不出用 custom，前端字母头像兜底） */
    static String guessProviderIcon(String baseUrl) {
        String host = hostOf(baseUrl);
        if (host == null) return "custom";
        if (host.contains("deepseek")) return "deepseek";
        if (host.contains("bigmodel") || host.contains("zhipu")) return "zhipu";
        if (host.contains("dashscope") || host.contains("aliyun")) return "qwen";
        if (host.contains("moonshot")) return "moonshot";
        if (host.contains("volces") || host.contains("volcengine")) return "doubao";
        if (host.contains("hunyuan") || host.contains("tencent")) return "hunyuan";
        if (host.contains("baidubce") || host.contains("baidu")) return "qianfan";
        if (host.contains("minimax")) return "minimax";
        if (host.contains("siliconflow")) return "siliconflow";
        if (host.contains("openai")) return "openai";
        if (host.contains("anthropic")) return "anthropic";
        if (host.contains("googleapis") || host.contains("gemini")) return "gemini";
        if (host.contains("openrouter")) return "openrouter";
        if (host.equals("localhost") || host.equals("127.0.0.1") || host.startsWith("192.168.") || host.startsWith("10.")) {
            return "ollama";
        }
        return "custom";
    }

    private static String hostOf(String baseUrl) {
        if (baseUrl == null) return null;
        String u = baseUrl.trim();
        int s = u.indexOf("://");
        if (s >= 0) u = u.substring(s + 3);
        int slash = u.indexOf('/');
        if (slash >= 0) u = u.substring(0, slash);
        int colon = u.indexOf(':');
        if (colon >= 0) u = u.substring(0, colon);
        return u.isBlank() ? null : u.toLowerCase();
    }

    // ==================== 工具 ====================

    /** 供应商名称→图标 key 的合法值由前端约定；此处仅做字符串工具 */

    private static String nz(String v) {
        return v == null ? "" : v.trim();
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a.trim() : (b == null ? "" : b.trim());
    }

    /** apiKey 脱敏（快照/列表回显）：****后4位 */
    private static String mask(String stored) {
        String plain = stored == null ? "" : stored;
        if (plain.length() <= 4) return "****";
        return "****" + plain.substring(plain.length() - 4);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String trim(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }
}
