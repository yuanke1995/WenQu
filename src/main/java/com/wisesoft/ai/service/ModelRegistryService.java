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
 * 所有存模型的位置（agent.model / 用户偏好 / 会话覆盖 / KB 绑定等）统一用 {@code {providerId}/{modelId}}
 * 引用格式；解析时按第一段 providerId 查本表得到网关（baseUrl/apiKey/路径），第二段起为原样模型名
 * （兼容 OpenRouter 等模型名自带斜杠的网关）。<b>无兜底</b>：引用解析不出（providerId 不存在或非引用
 * 格式）一律返回 null，由调用方 fail-loud 引导配置——全局 chat.* 与 embedding.* 网关兜底已移除；存量
 * 知识库的遗留裸模型名由 {@link #repairKbEmbeddingRefs()} 启动迁移一次性改写为供应商引用。
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
    /** OCR 专用（文档解析/扫描件逐页识别，如 PaddleOCR-VL、DeepSeek-OCR）：只产出带版面标记的解析文本，
     *  不能用于聊天图片理解（输出 LOC 坐标标记+版面文本，注入对话上下文会产生乱码回答） */
    public static final String TYPE_OCR = "ocr";
    public static final String TYPE_EMBEDDING = "embedding";
    public static final String TYPE_RERANK = "rerank";
    public static final String TYPE_AUDIO = "audio";
    public static final String TYPE_OMNI = "omni";
    public static final String TYPE_OTHER = "other";
    public static final List<String> TYPES = List.of(TYPE_CHAT, TYPE_VISION, TYPE_OCR, TYPE_EMBEDDING, TYPE_RERANK,
            TYPE_AUDIO, TYPE_OMNI, TYPE_OTHER);

    private final ProviderMapper providerMapper;
    private final ModelInfoMapper modelMapper;
    private final com.wisesoft.ai.mapper.AgentMapper agentMapper;
    private final com.wisesoft.ai.mapper.UserMapper userMapper;
    private final com.wisesoft.ai.mapper.ConfigMapper configMapper;
    private final com.wisesoft.ai.mapper.KnowledgeBaseMapper kbMapper;
    /** 个人设置模型引用（c_ai_user_config）——删除供应商守门用 */
    private final com.wisesoft.ai.mapper.UserConfigMapper userConfigMapper;
    private final ConfigService configService;
    private final ConfigCryptoService crypto;
    private final StringRedisTemplate redisTemplate;
    private final RedisProperties redisProperties;
    /** 角色判定（RBAC）：管理员级可管理全部供应商；仅依赖 Mapper，无循环依赖 */
    private final com.wisesoft.ai.service.RoleService roleService;

    private volatile List<Provider> providers = List.of();
    private volatile List<ModelInfo> models = List.of();

    public ModelRegistryService(ProviderMapper providerMapper, ModelInfoMapper modelMapper,
                                com.wisesoft.ai.mapper.AgentMapper agentMapper,
                                com.wisesoft.ai.mapper.UserMapper userMapper,
                                com.wisesoft.ai.mapper.ConfigMapper configMapper,
                                com.wisesoft.ai.mapper.KnowledgeBaseMapper kbMapper,
                                com.wisesoft.ai.mapper.UserConfigMapper userConfigMapper,
                                ConfigService configService, ConfigCryptoService crypto,
                                StringRedisTemplate redisTemplate, RedisProperties redisProperties,
                                com.wisesoft.ai.service.RoleService roleService) {
        this.providerMapper = providerMapper;
        this.modelMapper = modelMapper;
        this.agentMapper = agentMapper;
        this.userMapper = userMapper;
        this.configMapper = configMapper;
        this.kbMapper = kbMapper;
        this.userConfigMapper = userConfigMapper;
        this.configService = configService;
        this.crypto = crypto;
        this.redisTemplate = redisTemplate;
        this.redisProperties = redisProperties;
        this.roleService = roleService;
    }

    @PostConstruct
    public void init() {
        reload();
        migrateLegacyConfigs();
        repairKbEmbeddingRefs();
        backfillOwnerUid();
        startRedisSync();
        log.info("[Provider] 供应商注册中心加载完成: {} 个供应商, {} 个模型", providers.size(), models.size());
    }

    /**
     * 存量无主供应商归属回填（启动迁移，幂等）：归属已改为「谁建归谁」，不再有 owner_uid 为空的
     * 平台共享供应商。历史平台级行去向：created_by 是真实用户 → 归创建人；系统迁移生成的
     * （created_by=system/空）→ 归第一个管理员；查不到管理员则保持无主（仅管理员级可见可用，下次启动重试）。
     */
    private void backfillOwnerUid() {
        try {
            List<Provider> orphans = new ArrayList<>();
            for (Provider p : providers) {
                if (p.getOwnerUid() == null || p.getOwnerUid().isBlank()) orphans.add(p);
            }
            if (orphans.isEmpty()) return;
            String fallbackAdmin = firstAdminUid();
            int backfilled = 0;
            for (Provider p : orphans) {
                String creator = p.getCreatedBy();
                if (creator != null && !creator.isBlank() && !"system".equals(creator)) {
                    p.setOwnerUid(creator);
                } else if (fallbackAdmin != null) {
                    p.setOwnerUid(fallbackAdmin);
                } else {
                    log.warn("[Provider] 供应商 {}（{}）无归属且找不到管理员可认领，保持无主（仅管理员级可见）",
                            p.getName(), p.getId());
                    continue;
                }
                providerMapper.updateById(p);
                backfilled++;
                log.info("[Provider] 存量无主供应商已回填归属: {} → {}", p.getName(), p.getOwnerUid());
            }
            if (backfilled > 0) {
                reload();
                log.info("[Provider] 供应商归属回填完成: {} 个（原平台共享，现谁建归谁）", backfilled);
            }
        } catch (Exception e) {
            log.warn("[Provider] 供应商归属回填失败（不影响启动，下次启动重试）: {}", e.getMessage());
        }
    }

    /** 第一个管理员级用户 uid（按创建时间最早；供无主存量行认领） */
    private String firstAdminUid() {
        List<String> codes = roleService.adminCodes();
        if (codes.isEmpty()) return null;
        List<com.wisesoft.ai.model.User> admins = userMapper.selectList(
                new LambdaQueryWrapper<com.wisesoft.ai.model.User>()
                        .in(com.wisesoft.ai.model.User::getRole, codes)
                        .orderByAsc(com.wisesoft.ai.model.User::getCreateTime));
        for (com.wisesoft.ai.model.User u : admins) {
            if (u.getUid() != null && !u.getUid().isBlank()) return u.getUid();
        }
        return null;
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
     * 非引用格式 / providerId 不存在 → null（调用方 fail-loud，不回落全局网关）。
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
     * 聊天模型路由：仅按引用解析（会话覆盖 / 个人默认均为 {@code {providerId}/{modelId}} 格式）。
     * 值为空 / 非引用 / 供应商不存在 → null，由调用方 fail-loud 引导配置
     * （全局 chat.* 网关兜底已移除——遗留裸模型名不再有可用网关）。
     */
    public ModelRoute chatRoute(String modelValue) {
        return resolveReference(modelValue);
    }

    /**
     * 按引用 {@code {providerId}/{modelId}} 取模型登记行（含窗口/最大输出声明）。
     * 非引用格式 / 未登记 → null（调用方回落全局配置）。
     */
    public ModelInfo modelInfoOf(String reference) {
        if (reference == null) return null;
        String v = reference.trim();
        int i = v.indexOf('/');
        if (i <= 0 || i == v.length() - 1) return null;
        String pid = v.substring(0, i);
        String mid = v.substring(i + 1);
        for (ModelInfo m : models) {
            if (pid.equals(m.getProviderId()) && mid.equals(m.getModelId())) return m;
        }
        return null;
    }

    /** 重排路由：rerank.model 引用 → 供应商；遗留 → rerank.* 配置（本地 reranker 服务）。
     *  值链：知识库/智能体检索参数（线程局部覆盖）＞ 个人设置默认（personalOnly，问答线程装载个人值）
     *  ＞ 空（回落本地 rerank.baseUrl 服务）——全局层已退役，模型只认归属人。 */
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

    // ==================== 归属（谁建归谁）与可用性 ====================

    /**
     * 该供应商是否对请求者可见可用：仅归属人本人（数据按 userId 隔离，任何角色不再有全量运维视角；
     * 2026-10-02 起管理员级也不例外——管理员建的供应商归管理员本人）。
     */
    public boolean canUse(Provider p, String uid, String role) {
        if (p == null) return false;
        return uid != null && !uid.isBlank() && uid.equals(p.getOwnerUid());
    }

    /** 该供应商是否对请求者可管理（改 / 删 / 启停 / 登记模型）：仅归属人本人 */
    public boolean canManage(Provider p, String uid, String role) {
        if (p == null) return false;
        return uid != null && !uid.isBlank() && uid.equals(p.getOwnerUid());
    }

    /**
     * 校验模型引用对请求者可用。**引用进入系统的三个入口统一走这里**：聊天请求 model、
     * 个人默认模型、知识库向量模型。
     * <p>
     * 只判定「引用命中他人登记的个人级供应商」这一种情形：非引用格式（遗留裸模型名）与
     * 不存在的引用不在此处理——由调用方原有的存在性 / 类型校验负责，免得改变既有报错口径。
     * 命中时 fail-loud：不静默回落全局网关，否则会拿平台 Key 去跑别人的模型名。
     *
     * @param uid  请求者 uid（可为空=未知身份，此时个人级一律不可用）
     * @param role 请求者角色编码
     */
    public void assertUsable(String value, String uid, String role) {
        ModelRoute r = resolveReference(value);
        if (r == null) return;
        Provider p = providerById(r.providerId());
        if (p != null && !canUse(p, uid, role)) {
            throw new com.wisesoft.ai.common.BizException("模型「" + r.displayName()
                    + "」属于他人登记的个人供应商，你无法使用；请在模型选择器里选自己可用的模型");
        }
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

    /**
     * 模型图片理解能力（三态判定）：显式登记（vision_capable=1/0）优先；
     * 未登记按类型（vision/omni 恒支持）与模型名启发式（{@link #guessVisionCapable}）——
     * 2024 后「聊天+视觉」一体是主流登记形态，单值 modelType 表达不了，靠能力位补齐。
     * 仅对话类类型（chat/vision/omni）可具备该能力：向量/重排/OCR 等即使误标也不放行，
     * 防止误标模型混进视觉下拉。
     */
    public boolean visionCapable(ModelInfo mi) {
        String t = mi.getModelType();
        if (!TYPE_CHAT.equals(t) && !TYPE_VISION.equals(t) && !TYPE_OMNI.equals(t)) return false;
        if (mi.getVisionCapable() != null) return mi.getVisionCapable() == 1;
        return TYPE_VISION.equals(t) || TYPE_OMNI.equals(t) || guessVisionCapable(mi.getModelId());
    }

    /**
     * 工具调用（Function Calling）能力判定（三态）：显式登记优先；未登记按类型
     * （chat/omni 主流模型普遍支持 → 默认可用，向量/重排/视觉/OCR 无工具语义 → 不放行）。
     * <p>不支持和未知走同一条保守路径：本轮不下发 tools（部分网关收到 tools 会直接 400），
     * 并向用户登记可见提示，不静默降级。
     */
    public boolean toolCapable(ModelInfo mi) {
        String t = mi.getModelType();
        if (!TYPE_CHAT.equals(t) && !TYPE_OMNI.equals(t)) return false;
        if (mi.getToolCapable() != null) return mi.getToolCapable() == 1;
        return true;
    }

    /** 按模型引用判定工具调用能力（引用无法解析→false：宁可不下发 tools，也不让网关 400） */
    public boolean toolCapableOf(String ref) {
        ModelInfo mi = modelInfoOf(ref);
        return mi != null && toolCapable(mi);
    }

    /**
     * 思考强度档位集合（该模型登记支持的强度，逗号分隔解析）。
     * 合法值 {@link #REASONING_LEVEL_LIST}；未登记/非法值一律视为空=只支持思考开关。
     */
    public List<String> reasoningLevelsOf(ModelInfo mi) {
        if (mi == null || mi.getReasoningLevels() == null || mi.getReasoningLevels().isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(4);
        for (String s : mi.getReasoningLevels().split(",")) {
            String v = s.trim().toLowerCase();
            if (REASONING_LEVEL_LIST.contains(v) && !out.contains(v)) out.add(v);
        }
        return out;
    }

    /** 按模型引用取支持档位（引用无法解析→空） */
    public List<String> reasoningLevelsOf(String ref) {
        return reasoningLevelsOf(modelInfoOf(ref));
    }

    /**
     * 思考强度默认值：模型登记的默认档位（须在其支持档位内，否则忽略——防止手改库配出自相矛盾的档位）；
     * 未登记 → 请求层不强指定，由网关默认决定（返回 null）。
     */
    public String defaultReasoningLevelOf(String ref) {
        ModelInfo mi = modelInfoOf(ref);
        if (mi == null) return null;
        String v = mi.getDefaultReasoningLevel();
        if (v == null || v.isBlank()) return null;
        v = v.trim().toLowerCase();
        return reasoningLevelsOf(mi).contains(v) ? v : null;
    }

    /**
     * 引用类型匹配（口径放宽）：期望 vision 时，vision/omni 类型或具备图片理解能力
     * （visionCapable，含自动判定）的模型均通过；其余期望类型维持严格相等——
     * 向量/重排/OCR 是能力性类型，混用会在运行时静默失效，不能放宽。
     * 类型未登记（actual=null）照旧放行（遗留手填名兼容，与原口径一致）。
     */
    public boolean referenceMatchesType(String value, String expected) {
        String actual = referenceType(value);
        if (actual == null || expected == null || expected.equals(actual)) return true;
        if (!TYPE_VISION.equals(expected)) return false;
        if (TYPE_OMNI.equals(actual)) return true;
        ModelRoute r = resolveReference(value);
        if (r == null) return false;
        for (ModelInfo mi : models) {
            if (r.providerId().equals(mi.getProviderId()) && r.modelId().equals(mi.getModelId())) {
                return visionCapable(mi);
            }
        }
        return false;
    }

    /**
     * 按模型引用判定图片理解能力（聊天直读图片链路的开关依据）：
     * 引用无法解析（遗留手填名）→ false，维持独立视觉模型描述链路，不赌。
     */
    public boolean visionCapableOf(String ref) {
        ModelRoute r = resolveReference(ref);
        if (r == null) return false;
        for (ModelInfo mi : models) {
            if (r.providerId().equals(mi.getProviderId()) && r.modelId().equals(mi.getModelId())) {
                return visionCapable(mi);
            }
        }
        return false;
    }

    /**
     * 供应商列表（管理界面；apiKey 脱敏为 ****后4位）。
     * 按归属过滤：仅见自己登记的（数据按 userId 隔离）。每行附 manageable，
     * 供前端决定是否给出编辑、删除、启停、模型登记入口。
     */
    public List<Map<String, Object>> listProviders(String uid, String role) {
        List<Provider> ps = new ArrayList<>();
        for (Provider p : providers) {
            if (canUse(p, uid, role)) ps.add(p);
        }
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
            m.put("ownerUid", p.getOwnerUid());
            m.put("manageable", canManage(p, uid, role));
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
            // 图片理解能力三态原值（null=自动判定）：编辑弹窗需要显式值回显，否则全量同步保存会把 1/0 冲成 NULL
            m.put("visionCapable", mi.getVisionCapable());
            // 工具调用能力三态原值（同上，编辑弹窗回显用）
            m.put("toolCapable", mi.getToolCapable());
            m.put("thinking", resolveThinking(mi));
            // 思考强度：支持档位数组 + 默认档位（编辑弹窗回显；无档位=只支持思考开关）
            m.put("reasoningLevels", reasoningLevelsOf(mi));
            m.put("defaultReasoningLevel", mi.getDefaultReasoningLevel());
            m.put("contextWindow", mi.getContextWindow());
            m.put("maxOutput", mi.getMaxOutput());
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

    // ==================== 思考强度档位（厂商方言映射） ====================
    // 各网关表达强度的方式完全不同，没有一个字段能通用，故按厂商方言映射，
    // 不做「透传一个 reasoning_effort 就完事」——那样只对 OpenAI 系生效，其他家静默忽略。

    /** 思考强度档位（按推理预算由弱到强，与主流网关的 low/medium/high/xhigh 对齐，max 为各家上限档） */
    public static final List<String> REASONING_LEVEL_LIST = List.of("low", "medium", "high", "xhigh", "max");
    public static final List<String> REASONING_LEVELS = REASONING_LEVEL_LIST;

    /** 档位 → 思考预算 token（Qwen/GLM/豆包等「预算型」网关）：强度靠 token 预算表达，max 取两档中的较大值 */
    private static final Map<String, Integer> LEVEL_TOKEN_BUDGET = Map.of(
            "low", 1024, "medium", 4096, "high", 16384, "xhigh", 32768, "max", 65536);

    /** 档位 → OpenAI reasoning_effort 取值（xhigh/max 官方无对应，收敛到 high 不静默丢档） */
    private static final Map<String, String> LEVEL_OPENAI_EFFORT =
            Map.of("low", "low", "medium", "medium", "high", "high", "xhigh", "high", "max", "high");

    /** 档位 → Anthropic thinking.budget_tokens（Claude 预算型，量级比 Qwen 系大） */
    private static final Map<String, Integer> LEVEL_CLAUDE_BUDGET = Map.of(
            "low", 2048, "medium", 8192, "high", 24576, "xhigh", 49152, "max", 98304);

    /**
     * 按模型族判定思考强度的方言（决定 extraBody 走哪种字段）：
     * OPENAI=reasoning_effort（o 系 / gpt-5）｜ANTHROPIC=thinking.budget_tokens（claude）
     * ｜QWEN=enable_thinking+thinking_budget｜NONE=无强度语义（只透传思考开关）。
     */
    public enum ReasoningDialect { OPENAI, ANTHROPIC, QWEN, NONE }

    /** 按模型名判定思考强度方言（显式登记档位的前提是网关真能表达强度，NONE 时不静默丢弃用户登记） */
    public static ReasoningDialect reasoningDialectOf(String modelId) {
        String m = modelId == null ? "" : modelId.toLowerCase();
        if (m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4") || m.contains("gpt-5")) {
            return ReasoningDialect.OPENAI;
        }
        if (m.contains("claude")) return ReasoningDialect.ANTHROPIC;
        if (m.contains("qwen") || m.contains("qwq")) return ReasoningDialect.QWEN;
        return ReasoningDialect.NONE;
    }

    /**
     * 思考强度的请求体增量（方言映射结果）：调用方合入 extraBody。
     * <ul>
     *   <li>level 空 / 引用无效 / 模型无档位登记 → 空 Map（不强指定，由网关默认决定）</li>
     *   <li>方言 NONE（该网关无强度语义）→ 空 Map，且调用方应登记 fail-loud 提示，
     *       因为用户明确登记了档位却无处下发，属配置与网关不匹配，不能装作生效</li>
     *   <li>其余 → 该方言的强度字段；同时保留 enable_thinking/thinking 开关字段语义
     *       （OpenAI 靠字段本身开启、Qwen 系需 enable_thinking=true）</li>
     * </ul>
     */
    public Map<String, Object> reasoningExtraBody(String ref, String level, boolean thinkingOn) {
        if (level == null || level.isBlank()) return Map.of();
        ModelInfo mi = modelInfoOf(ref);
        if (mi == null) return Map.of();
        String lv = level.trim().toLowerCase();
        if (!REASONING_LEVEL_LIST.contains(lv)) return Map.of();
        if (!reasoningLevelsOf(mi).contains(lv)) return Map.of();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        switch (reasoningDialectOf(mi.getModelId())) {
            case OPENAI -> {
                if (thinkingOn) body.put("reasoning_effort", LEVEL_OPENAI_EFFORT.getOrDefault(lv, "high"));
            }
            case ANTHROPIC -> {
                // Claude 思考必须显式开并给预算；预算不可为 0，否则网关按「不思考」处理
                body.put("thinking", Map.of("type", "enabled",
                        "budget_tokens", LEVEL_CLAUDE_BUDGET.getOrDefault(lv, 24576)));
            }
            case QWEN -> {
                if (thinkingOn) {
                    body.put("enable_thinking", true);
                    body.put("thinking_budget", LEVEL_TOKEN_BUDGET.getOrDefault(lv, 16384));
                }
            }
            case NONE -> {
                return Map.of();
            }
        }
        return body;
    }

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
     * 按请求者归属过滤——个人级供应商的模型只出现在归属人自己的选择器里。
     */
    public List<Map<String, Object>> available(String type, String uid, String role) {
        List<Provider> ps = new ArrayList<>(providers);
        ps.sort(Comparator.comparingInt((Provider p) -> p.getSortOrder() == null ? 0 : p.getSortOrder())
                .thenComparing(p -> nz(p.getName())));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Provider p : ps) {
            if (Integer.valueOf(0).equals(p.getEnabled())) continue;
            if (!canUse(p, uid, role)) continue;
            List<Map<String, Object>> ms = new ArrayList<>();
            for (ModelInfo mi : models) {
                if (!p.getId().equals(mi.getProviderId())) continue;
                if (Integer.valueOf(0).equals(mi.getEnabled())) continue;
                // type 支持逗号分隔多类型（如 "vision,ocr"，与前端 ModelSelect 的 type 契约一致）；
                // 期望 vision 时放宽口径：具备图片理解能力的模型（visionCapable，可与聊天并存）同样入选
                if (type != null && !type.isBlank()) {
                    java.util.Set<String> wanted = new java.util.HashSet<>();
                    for (String t : type.split(",")) {
                        String s = t.trim();
                        if (!s.isEmpty()) wanted.add(s);
                    }
                    if (!wanted.isEmpty() && !wanted.contains(mi.getModelType())
                            && !(wanted.contains(TYPE_VISION) && visionCapable(mi))) continue;
                }
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("ref", p.getId() + "/" + mi.getModelId());
                m.put("modelId", mi.getModelId());
                m.put("displayName", mi.getDisplayName() == null || mi.getDisplayName().isBlank()
                        ? mi.getModelId() : mi.getDisplayName());
                m.put("type", mi.getModelType());
                m.put("visionCapable", visionCapable(mi));
                m.put("toolCapable", toolCapable(mi));
                m.put("reasoningLevels", reasoningLevelsOf(mi));
                m.put("defaultReasoningLevel", mi.getDefaultReasoningLevel());
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

    /**
     * 第一个可用的向量模型引用（系统身份用，如官方内置库种子同步）：绕过「个人供应商归属」判权，
     * 按 sortOrder 取第一个 enabled 供应商下第一个 enabled 的 embedding 模型。
     * 系统级资源（官方库）挂平台已登记的向量模型与 {@code DynamicEmbeddingModel.forRef} 的系统级解析同口径；
     * 无任何可用向量模型时返回 null（调用方显式告警，不静默兜底）。
     */
    public String firstAvailableEmbeddingRef() {
        List<Provider> ps = new ArrayList<>(providers);
        ps.sort(Comparator.comparingInt((Provider p) -> p.getSortOrder() == null ? 0 : p.getSortOrder())
                .thenComparing(p -> nz(p.getName())));
        for (Provider p : ps) {
            if (Integer.valueOf(0).equals(p.getEnabled())) continue;
            for (ModelInfo mi : models) {
                if (!p.getId().equals(mi.getProviderId())) continue;
                if (Integer.valueOf(0).equals(mi.getEnabled())) continue;
                if (!TYPE_EMBEDDING.equals(mi.getModelType())) continue;
                return p.getId() + "/" + mi.getModelId();
            }
        }
        return null;
    }

    // ==================== 供应商 / 模型 CRUD ====================

    /**
     * 新建/更新供应商。rawApiKey 为空或 **** 掩码时保留库中已存密钥（编辑场景未重输 Key）。
     * <p>
     * 归属：谁建归谁——新建一律 ownerUid = 创建人（管理员建的也归管理员本人，不共享给他人）。
     * 编辑不改归属（谁登记的永远属于谁）。
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
            // 谁建归谁（管理员建的也归本人，不再有平台共享）
            p.setOwnerUid(operator);
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
            log.info("[Provider] 供应商已创建: {}（{}）by {}，归属={}", p.getName(), p.getBaseUrl(), operator, p.getOwnerUid());
        } else {
            providerMapper.updateById(p);
            log.info("[Provider] 供应商已更新: {}（{}）by {}", p.getName(), p.getBaseUrl(), operator);
        }
        changed();
        return p;
    }

    /**
     * 删除供应商（连同其模型登记）。被引用（智能体模型 / 用户默认模型 / 知识库向量 / 系统配置活跃槽位 /
     * 个人设置模型引用）时拒绝；退役与个人专属（personalOnly）配置键的遗留行只告警不阻挡——
     * 前者界面无处可改，后者的引用归各用户个人设置管理，挡在这里只会形成删不掉又说不清的僵局。
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
        // 个人设置（c_ai_user_config）里的模型引用：重排/记忆向量/图谱兜底/问答对生成等 personalOnly 键，
        // 以及个人覆盖的模型类字段——归属人自己在个人设置里即可改掉，属"可处理"引用，必须挡
        Long personalRefs = userConfigMapper.selectCount(
                new LambdaQueryWrapper<com.wisesoft.ai.model.UserConfig>()
                        .likeRight(com.wisesoft.ai.model.UserConfig::getConfigValue, prefix));
        if (personalRefs != null && personalRefs > 0) refs.add("个人设置模型引用 ×" + personalRefs);
        Long kbRefs = kbMapper.selectCount(new LambdaQueryWrapper<KnowledgeBase>()
                .likeRight(KnowledgeBase::getEmbeddingRef, prefix));
        if (kbRefs != null && kbRefs > 0) refs.add("知识库绑定向量模型 ×" + kbRefs);
        List<com.wisesoft.ai.model.Config> cfgRefs = configMapper.selectList(
                new LambdaQueryWrapper<com.wisesoft.ai.model.Config>()
                        .likeRight(com.wisesoft.ai.model.Config::getConfigValue, prefix));
        List<String> slotKeys = new ArrayList<>();
        for (com.wisesoft.ai.model.Config c : cfgRefs) {
            // 只有活跃键（defaults() 定义、设置页可见可改）且非个人专属键才阻挡删除；退役键/个人专属键
            // 的遗留行对用户不可见也不可改（personalOnly 已由个人层接管），挡删除是死路——只告警
            if (configService.isLiveKey(c.getConfigKey()) && !configService.isPersonalOnly(c.getConfigKey())) {
                slotKeys.add(c.getConfigKey());
            } else {
                log.warn("[Provider] 遗留/个人专属配置键 {} 引用了供应商 {}，删除后该引用随之失效",
                        c.getConfigKey(), p.getName());
            }
        }
        if (!slotKeys.isEmpty()) {
            refs.add("系统配置槽位（" + String.join("、", slotKeys) + "）×" + slotKeys.size());
        }
        if (!refs.isEmpty()) {
            throw new IllegalArgumentException("供应商「" + p.getName() + "」仍被引用（" + String.join("、", refs)
                    + "），请先在个人设置/知识库/系统设置改用其他模型");
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
            // 图片理解能力三态：显式 1/0 落库；"auto"/未传 → NULL=按类型与模型名自动判定（读时 visionCapable 解析）
            mi.setVisionCapable(visionTriState(item.get("visionCapable")));
            // 工具调用能力三态：同上（读时 toolCapable 解析）
            mi.setToolCapable(visionTriState(item.get("toolCapable")));
            String thinking = str(item.get("thinking"));
            mi.setThinking(THINKING_LEVELS.contains(thinking) ? thinking : "auto");
            // 思考强度档位：支持档位（逗号分隔，非法值过滤）+ 默认档位
            mi.setReasoningLevels(normalizeReasoningLevels(item.get("reasoningLevels")));
            String defLevel = str(item.get("defaultReasoningLevel"));
            defLevel = defLevel == null ? null : defLevel.trim().toLowerCase();
            // 默认档位必须在支持档位内，否则显式拒绝（存出自相矛盾的配置，运行时无法判断该听谁的）
            if (defLevel != null && !defLevel.isBlank() && !reasoningLevelsOf(mi).contains(defLevel)) {
                throw new IllegalArgumentException("模型 " + modelId.trim() + " 的默认思考强度（" + defLevel
                        + "）不在其支持档位内（" + (mi.getReasoningLevels() == null ? "未登记任何档位" : mi.getReasoningLevels())
                        + "），请先勾选该档位或留空由网关默认决定");
            }
            mi.setDefaultReasoningLevel(defLevel == null || defLevel.isBlank() ? null : defLevel);
            mi.setContextWindow(intOrNull(item.get("contextWindow")));
            mi.setMaxOutput(intOrNull(item.get("maxOutput")));
            // 跨字段一致性（预算 = 窗口×安全系数−输出限制）：两个都声明时输出不能吃光预算——
            // 否则该模型下上下文预算被运行时托底成 1000，检索资料塞不进。安全系数沿用全局配置。
            if (mi.getContextWindow() != null && mi.getMaxOutput() != null) {
                double safety = Math.max(0.1, Math.min(1, configService.getDouble("context.safetyFactor")));
                long windowBudget = (long) (mi.getContextWindow() * safety);
                if (mi.getMaxOutput() >= windowBudget) {
                    throw new IllegalArgumentException("模型 " + modelId.trim() + " 的最大输出（" + mi.getMaxOutput()
                            + "）必须小于其上下文窗口预算（" + mi.getContextWindow() + " × 安全系数 " + safety
                            + " = " + windowBudget + "），否则该模型下检索资料将无法填入上下文");
                }
            }
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
                String guessedType = guessType(modelId);
                m.put("modelId", modelId);
                m.put("guessedType", guessedType);
                m.put("guessVisionCapable", TYPE_VISION.equals(guessedType) || TYPE_OMNI.equals(guessedType)
                        || guessVisionCapable(modelId));
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
     * rerank/ranker → 重排；embed/bge/gte → 向量；ocr → OCR 专用（先于视觉判定——OCR 模型名里常带 -vl，
     * 如 PaddleOCR-VL）；vision/vl/llava → 视觉；omni → 全模态；asr/tts/whisper 等 → 语音；
     * dall/moderation/davinci 等 → 其他；其余 → 聊天。
     */
    public static String guessType(String modelId) {
        String m = (modelId == null ? "" : modelId).toLowerCase();
        if (m.contains("rerank") || m.contains("ranker")) return TYPE_RERANK;
        if (m.contains("embed") || m.startsWith("bge-") || m.startsWith("gte-") || m.contains("/embedding")) return TYPE_EMBEDDING;
        if (m.contains("ocr")) return TYPE_OCR;
        if (m.contains("vision") || m.contains("llava") || m.contains("internvl") || m.contains("qvq")
                || VL_TOKEN.matcher(m).find()) return TYPE_VISION;
        if (m.contains("omni")) return TYPE_OMNI;
        if (m.contains("asr") || m.contains("tts") || m.contains("audio") || m.contains("speech")
                || m.contains("whisper") || m.contains("paraformer") || m.contains("cosyvoice")
                || m.contains("sensevoice") || m.contains("sambert")) return TYPE_AUDIO;
        if (m.contains("dall") || m.contains("moderation")
                || m.contains("davinci") || m.contains("babbage") || m.contains("stable-diffusion")
                || m.contains("flux") || m.contains("sora") || m.contains("video")) return TYPE_OTHER;
        return TYPE_CHAT;
    }

    /** 视觉模型名里的 vl 词元（前后非小写字母界定，如 qwen2-vl-72b / 4vl? 避免误伤普通词） */
    private static final Pattern VL_TOKEN = Pattern.compile(".*(^|[^a-z])vl([^a-z]|$).*");

    /**
     * 「聊天+图片理解」一体模型家族词元（登记/拉取时预填「图片理解=支持」，界面可改）：
     * GPT-4o/GPT-4.1/GPT-5/o 系、Gemini、Claude 3+、Grok-4、Qwen-VL/QvQ、GLM-4V、
     * 豆包 vision、DeepSeek-VL、Step、Yi-Vision、InternVL、LLaVA 等。
     * 仅作预填启发：判定不了的（如网关自定义别名）由用户手动标记。
     */
    private static final String[] VISION_CHAT_FAMILIES = {
            "gpt-4o", "gpt-4.1", "chatgpt-4o", "gpt-4-turbo", "gpt-5", "o1", "o3", "o4",
            "gemini", "claude-3", "claude-4", "claude-sonnet", "claude-opus", "claude-haiku",
            "grok-4", "grok-2-vision",
            "qwen-vl", "qwen2-vl", "qwen2.5-vl", "qwen3-vl", "qvq",
            "glm-4v", "glm-4.5v", "glm-4.6v",
            "doubao-vision", "doubao-1.5-vision", "doubao-1.6-vision",
            "deepseek-vl", "step-1v", "step-1o", "step-3", "yi-vision", "internvl", "llava"
    };

    /** 按模型名启发式判定「聊天+图片理解」一体模型（配合 guessType 的类型口径，供能力位自动预填） */
    public static boolean guessVisionCapable(String modelId) {
        String m = (modelId == null ? "" : modelId).toLowerCase();
        for (String f : VISION_CHAT_FAMILIES) {
            if (m.contains(f)) return true;
        }
        return false;
    }

    /** 图片理解能力入参三态归一：1/true→1，0/false→0；null/"auto"/""→null（按类型与模型名自动判定） */
    private static Integer visionTriState(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty() || "auto".equals(s) || "null".equals(s)) return null;
        return ("1".equals(s) || "true".equals(s)) ? 1 : 0;
    }

    /**
     * 支持档位入参归一：接受逗号分隔字符串或数组，按合法档位去重并<b>按强度由弱到强排序</b>后回写规范串；
     * 非法档位静默丢弃（前端多选已限定合法值，这里防手改请求/脏数据），全空 → null（=只支持思考开关）。
     */
    private static String normalizeReasoningLevels(Object v) {
        List<String> raw = new ArrayList<>();
        if (v instanceof java.util.Collection<?> c) {
            for (Object o : c) if (o != null) raw.add(String.valueOf(o));
        } else if (v != null) {
            for (String s : String.valueOf(v).split(",")) if (!s.isBlank()) raw.add(s);
        }
        List<String> out = new ArrayList<>(5);
        for (String lv : REASONING_LEVEL_LIST) { // 遍历合法档位天然有序
            for (String s : raw) {
                if (lv.equals(s.trim().toLowerCase()) && !out.contains(lv)) out.add(lv);
            }
        }
        return out.isEmpty() ? null : String.join(",", out);
    }

    /** 供应商名猜测时跳过的域名段（常见前缀 + 顶级域） */
    private static final List<String> SKIP_DOMAIN_PARTS =
            List.of("api", "open", "aip", "ark", "gateway", "chat", "console", "dashscope",
                    "com", "cn", "net", "org", "ai", "cloud", "co");

    // ==================== 存量配置迁移 ====================

    /**
     * 存量手填网关配置（embedding / rerank 的 baseUrl+apiKey+模型名）迁移为内置供应商 + 模型登记。
     * 幂等：值已是引用或网关信息为空则跳过；同网关（归一化 baseUrl + Key 相同）复用同一供应商。
     * 直接落库，不走 update() 联动（绝不触发全量重嵌入）。
     * <p>迁移产物去向：rerank.model 已改为个人专属键（personalOnly，个人设置按归属人解析）——
     * 全局遗留值不再有消费方，不迁移回写；embedding.model 已退役（向量模型归知识库 embedding_ref），
     * 不迁移——两者只登记供应商档案与模型，供知识库绑定与 {@link #repairKbEmbeddingRefs()} 改写裸名引用。
     * chat / vision 两组无遗留消费方，不迁移。
     */
    private void migrateLegacyConfigs() {
        Map<String, String[]> groups = Map.of(
                "embedding", new String[]{"embedding.baseUrl", "embedding.apiKey", "embedding.embeddingsPath"},
                "rerank", new String[]{"rerank.baseUrl", null, null});
        Map<String, String> typeByGroup = Map.of(
                "embedding", TYPE_EMBEDDING, "rerank", TYPE_RERANK);
        // 未启用的功能不迁移（yml 兜底默认值也非空，避免为从未用过的本地 rerank 建供应商）；
        // 之后启用时走遗留解析（rerank.* 原值未动，行为不变）
        Map<String, Boolean> enabledByGroup = Map.of(
                "embedding", true,
                "rerank", configService.getBoolean("rerank.enabled"));
        int migrated = 0;
        for (Map.Entry<String, String[]> g : groups.entrySet()) {
            try {
                String group = g.getKey();
                if (!Boolean.TRUE.equals(enabledByGroup.get(group))) continue;
                // 个人专属键（rerank.model）不再迁移：全局层不参与读取，回写只会制造
                // "界面无处可见"的孤儿行（删供应商时还会被守门日志念到）
                if (configService.isPersonalOnly(group + ".model")) continue;
                String model = configService.get(group + ".model");
                if (model == null || model.isBlank() || resolveReference(model) != null) continue;
                String baseUrl = nz(configService.get(g.getValue()[0]));
                if (baseUrl.isBlank()) continue;
                String apiKey = g.getValue()[1] == null ? "" : nz(configService.get(g.getValue()[1]));
                String path = g.getValue()[2] == null ? "" : nz(configService.get(g.getValue()[2]));
                Provider p = findOrMergeProvider(baseUrl, apiKey, path, group);
                upsertModel(p.getId(), model.trim(), typeByGroup.get(group));
                // 仅活跃键回写引用（rerank.model）；embedding.model 等退役键不再制造孤儿行
                if (configService.isLiveKey(group + ".model")) {
                    configService.putInternal(group + ".model", p.getId() + "/" + model.trim());
                }
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

    /**
     * 知识库遗留裸模型名引用修复（启动迁移）：向量模型兜底已移除，{@code kb.embedding_ref} 必须是
     * {@code {providerId}/{modelId}} 引用。历史值两类：
     * <ul>
     *   <li>裸模型名（无斜杠）→ 按遗留 embedding.* 网关（c_ai_config 存量行）findOrMergeProvider
     *       建档并改写为引用——与 migrateLegacyConfigs 的同网关合并逻辑幂等复用；</li>     *   <li>引用但供应商已删 → 无法自动修复，log.error 暴露（使用时 fail-loud 引导重绑）。</li>
     * </ul>
     * 空绑定不自动选型，log.warn 提示手动绑定（storeForKb 对空绑定本就 fail-loud）。
     */
    private void repairKbEmbeddingRefs() {
        try {
            List<com.wisesoft.ai.model.KnowledgeBase> kbs = kbMapper.selectList(
                    new LambdaQueryWrapper<>());
            int repaired = 0;
            for (com.wisesoft.ai.model.KnowledgeBase kb : kbs) {
                String ref = nz(kb.getEmbeddingRef());
                if (ref.isEmpty()) {
                    log.warn("[Provider] 知识库 {}（{}）未绑定向量模型，检索/解析将不可用——请在知识库管理中绑定", kb.getId(), kb.getName());
                    continue;
                }
                if (resolveReference(ref) != null) continue;
                if (ref.contains("/")) {
                    log.error("[Provider] 知识库 {}（{}）绑定的向量模型引用 {} 供应商已不存在，请重新绑定", kb.getId(), kb.getName(), ref);
                    continue;
                }
                String baseUrl = nz(configService.get("embedding.baseUrl"));
                if (baseUrl.isEmpty()) {
                    log.error("[Provider] 知识库 {}（{}）遗留裸模型名 {} 无法修复（无遗留网关信息），请重新绑定向量模型",
                            kb.getId(), kb.getName(), ref);
                    continue;
                }
                String apiKey = nz(configService.get("embedding.apiKey"));
                String path = nz(configService.get("embedding.embeddingsPath"));
                Provider p = findOrMergeProvider(baseUrl, apiKey, path, "embedding");
                upsertModel(p.getId(), ref, TYPE_EMBEDDING);
                kb.setEmbeddingRef(p.getId() + "/" + ref);
                kbMapper.updateById(kb);
                reload();
                repaired++;
                log.info("[Provider] 知识库 {} 遗留向量模型已改写为引用: {} → {}/{}", kb.getId(), ref, p.getId(), ref);
            }
            if (repaired > 0) {
                log.info("[Provider] 知识库遗留向量模型引用修复完成: {} 个库", repaired);
            }
        } catch (Exception e) {
            log.warn("[Provider] 知识库向量引用修复失败（不影响启动，使用时报错可人工重绑）: {}", e.getMessage());
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

    /** apiKey 脱敏（快照/列表回显）：****后4位 */
    private static String mask(String stored) {
        String plain = stored == null ? "" : stored;
        if (plain.length() <= 4) return "****";
        return "****" + plain.substring(plain.length() - 4);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 前端表单值 → Integer（空串/null/非数字 → null=未声明；负数视为未声明） */
    private static Integer intOrNull(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return null;
        try {
            int v = Integer.parseInt(s);
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
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
