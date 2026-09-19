package com.wisesoft.wenqu.service;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.common.StringUtils;
import com.wisesoft.wenqu.models.BuiltinProviders;
import com.wisesoft.wenqu.models.ModelInfo;
import com.wisesoft.wenqu.models.ModelProvider;
import com.wisesoft.wenqu.repositories.ModelProviderCache;
import com.wisesoft.wenqu.repositories.ModelProviderRepository;
import com.wisesoft.wenqu.repositories.RepoValues;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 模型供应商服务（models/providers/service.py 逐函数翻译）。
 *
 * <p>已移植：常量集合、payload 归一化与校验（provider_id / display_name / base_url / 端点 /
 * provider_type / capabilities 与 enabled_models 一致性 / request_body_overrides 作用域）、
 * 凭证状态判定、远端模型拉取与规范化、内置模板补种、供应商 CRUD 用例、
 * {@code test_model_status_by_spec} 的分支与返回契约。
 *
 * <p>平台差异（必要替换，已标注）：
 * <ul>
 *   <li>HTTP 客户端：{@code httpx.AsyncClient} → JDK {@link HttpClient}；参考实现用
 *       {@code asyncio.gather} 并发拉取多个端点，本实现顺序拉取——端点调用顺序、结果顺序
 *       与按 {@code (id, type)} 去重的语义完全一致，仅耗时不同。
 *   <li>远端 HTTP 错误：{@code httpx.HTTPStatusError} → {@link HttpStatusFailure}
 *       （携带状态码与响应体），供路由层按同一规则映射。
 *   <li>JSON 可序列化校验：{@code json.dumps(..., allow_nan=False)} → 显式检查 NaN / Infinity
 *       （其余值来自 JSON 解析，天然可序列化）。
 *   <li>{@code int(...)} 与列表插值的失败/提示文案按 Python 原样渲染
 *       （{@link StringUtils#pythonListRepr}），保证用户可见文案一致。
 *   <li>{@code db.flush() + db.refresh()} 的可见性语义 → 本工程每次 Mapper 调用即提交，
 *       写后回读即等价。
 * </ul>
 */
@Service
public class ModelProviderService {

    private static final Logger log = LoggerFactory.getLogger(ModelProviderService.class);

    /** 合法模型类型（参考实现 VALID_MODEL_TYPES）。 */
    public static final Set<String> VALID_MODEL_TYPES = Set.of("chat", "embedding", "rerank");

    /** 合法模型来源（参考实现 VALID_MODEL_SOURCES）。 */
    public static final Set<String> VALID_MODEL_SOURCES = Set.of("manual", "remote");

    /** 合法供应商适配类型（参考实现 VALID_PROVIDER_TYPES）。 */
    public static final Set<String> VALID_PROVIDER_TYPES =
            Set.of("openai", "anthropic", "gemini", "openrouter");

    /** 支持请求体覆盖的供应商适配类型。 */
    public static final Set<String> OPENAI_COMPATIBLE_REQUEST_BODY_PROVIDER_TYPES =
            Set.of("openai", "openrouter");

    /** 允许写进 extra_body 的请求体覆盖字段。 */
    public static final Set<String> ALLOWED_EXTRA_BODY_FIELDS =
            Set.of(
                    "enable_thinking",
                    "reasoning",
                    "reasoning_effort",
                    "thinking",
                    "thinking_budget");

    /** provider_id 命名约束（参考实现 _PROVIDER_ID_RE）。 */
    private static final Pattern PROVIDER_ID_RE =
            Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_-]{1,99}$");

    /** 远端模型列表请求超时（参考实现 {@code httpx.AsyncClient(timeout=40.0)}）。 */
    private static final Duration REMOTE_REQUEST_TIMEOUT = Duration.ofSeconds(40);

    private final ModelProviderRepository modelProviderRepository;
    private final ModelProviderCache modelProviderCache;
    private final ModelSelectors modelSelectors;

    public ModelProviderService(
            ModelProviderRepository modelProviderRepository,
            ModelProviderCache modelProviderCache,
            ModelSelectors modelSelectors) {
        this.modelProviderRepository = modelProviderRepository;
        this.modelProviderCache = modelProviderCache;
        this.modelSelectors = modelSelectors;
    }

    // ==================== 归一化与校验 ====================

    /** {@code value if isinstance(value, list) else []}。 */
    @SuppressWarnings("unchecked")
    static List<Object> normalizeList(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }

    /** {@code value if isinstance(value, dict) else {}}。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> normalizeDict(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    private static void validateProviderId(String providerId) {
        if (!PROVIDER_ID_RE.matcher(providerId).matches()) {
            throw new IllegalArgumentException("provider_id 只能包含字母、数字、下划线和中划线，长度 2-100");
        }
    }

    /** 规范化模型配置对象，校验运行所需字段。 */
    static Map<String, Object> normalizeModelItem(Map<String, Object> model) {
        String modelId = pythonStrip(asString(model.get("id")));
        if (modelId.isEmpty()) {
            throw new IllegalArgumentException("模型 id 不能为空");
        }

        String modelType = pythonStrip(asStringOr(model.get("type"), "unknown"));
        if (!VALID_MODEL_TYPES.contains(modelType)) {
            throw new IllegalArgumentException(
                    "启用模型 " + modelId + " 的 type 必须是 chat、embedding 或 rerank");
        }

        // source 区分手动添加 vs 远端拉取，用于跳过远端清单存在性的视觉警告。
        String source = pythonStrip(asStringOr(model.get("source"), "remote"));
        if (!VALID_MODEL_SOURCES.contains(source)) {
            throw new IllegalArgumentException("模型 " + modelId + " 的 source 必须是 manual 或 remote");
        }

        Map<String, Object> normalized = new LinkedHashMap<>(model);
        normalized.put("id", modelId);
        normalized.put("type", modelType);
        normalized.put("source", source);
        normalized.put(
                "display_name",
                asStringOr(firstNonEmpty(model.get("display_name"), model.get("name")), modelId));
        normalized.put("extra", normalizeDict(model.get("extra")));
        if (model.containsKey("request_body_overrides")) {
            Object overrides = model.get("request_body_overrides");
            if (!(overrides instanceof Map)) {
                throw new IllegalArgumentException(
                        "模型 " + modelId + " 的 request_body_overrides 必须是 JSON 对象");
            }
            Map<String, Object> overrideMap = normalizeDict(overrides);

            for (Object key : overrideMap.keySet()) {
                if (!(key instanceof String text) || pythonStrip(text).isEmpty()) {
                    throw new IllegalArgumentException(
                            "模型 " + modelId + " 的 request_body_overrides 字段名必须是非空字符串");
                }
            }

            List<String> unsupported = new ArrayList<>();
            for (String key : overrideMap.keySet()) {
                if (!ALLOWED_EXTRA_BODY_FIELDS.contains(key)) {
                    unsupported.add(key);
                }
            }
            if (!unsupported.isEmpty()) {
                java.util.Collections.sort(unsupported);
                List<String> allowed = new ArrayList<>(ALLOWED_EXTRA_BODY_FIELDS);
                java.util.Collections.sort(allowed);
                throw new IllegalArgumentException(
                        "模型 "
                                + modelId
                                + " 的 request_body_overrides 包含不支持的 extra_body 字段: "
                                + String.join(", ", unsupported)
                                + "；允许字段: "
                                + String.join(", ", allowed));
            }

            assertJsonSerializable(overrideMap, modelId);

            normalized.put("request_body_overrides", new LinkedHashMap<>(overrideMap));
        }

        if ("embedding".equals(modelType)) {
            Object dimension = model.get("dimension");
            if (dimension != null && !"".equals(dimension)) {
                normalized.put("dimension", pythonInt(dimension));
            }
            Object batchSize = model.get("batch_size");
            if (batchSize != null && !"".equals(batchSize)) {
                normalized.put("batch_size", pythonInt(batchSize));
            }
        }

        return normalized;
    }

    /** 规范化模型列表：逐项校验类型与 id 唯一性。 */
    static List<Map<String, Object>> normalizeModelList(Object models) {
        List<Map<String, Object>> normalizedModels = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (Object item : normalizeList(models)) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("模型配置必须是对象列表");
            }
            Map<String, Object> normalized = normalizeModelItem(asStringKeyMap(raw));
            String id = (String) normalized.get("id");
            if (!seenIds.add(id)) {
                throw new IllegalArgumentException("模型 id 重复: " + id);
            }
            normalizedModels.add(normalized);
        }
        return normalizedModels;
    }

    /** 校验 enabled_models 中所有模型的 type 都在 provider capabilities 范围内。 */
    static void validateModelsCapabilities(
            List<Map<String, Object>> enabledModels, Collection<String> capabilities) {
        for (Map<String, Object> model : nullSafeModels(enabledModels)) {
            Object type = model.get("type");
            if (!capabilities.contains(type)) {
                List<String> sorted = new ArrayList<>(capabilities);
                java.util.Collections.sort(sorted);
                throw new IllegalArgumentException(
                        "模型 "
                                + model.get("id")
                                + " 的 type="
                                + type
                                + " 不在 provider 能力 "
                                + StringUtils.pythonListRepr(sorted)
                                + " 内");
            }
        }
    }

    /** 校验请求体覆盖仅用于 OpenAI 兼容供应商的 chat 模型。 */
    static void validateRequestBodyOverridesScope(
            List<Map<String, Object>> enabledModels, String providerType) {
        for (Map<String, Object> model : nullSafeModels(enabledModels)) {
            Map<String, Object> overrides = normalizeDict(model.get("request_body_overrides"));
            if (overrides.isEmpty()) {
                continue;
            }
            String modelId = asStringOr(model.get("id"), "");
            if (!OPENAI_COMPATIBLE_REQUEST_BODY_PROVIDER_TYPES.contains(providerType)) {
                throw new IllegalArgumentException(
                        "模型 " + modelId + " 的 request_body_overrides 仅支持 OpenAI 兼容供应商");
            }
            if (!"chat".equals(model.get("type"))) {
                throw new IllegalArgumentException(
                        "模型 " + modelId + " 的 request_body_overrides 仅支持 chat 模型");
            }
        }
    }

    /** 字段缺省值（参考实现 {@code _FIELD_DEFAULTS}）。 */
    private static Map<String, Object> fieldDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("capabilities", new ArrayList<>());
        defaults.put("enabled_models", new ArrayList<>());
        defaults.put("headers_json", new LinkedHashMap<>());
        defaults.put("extra_json", new LinkedHashMap<>());
        defaults.put("is_enabled", Boolean.TRUE);
        defaults.put("is_builtin", Boolean.FALSE);
        return defaults;
    }

    /** 字段归一化器（参考实现 {@code _FIELD_NORMALIZERS}）。 */
    private static Object normalizeField(String field, Object value) {
        return switch (field) {
            case "capabilities" -> normalizeList(value);
            case "enabled_models" -> normalizeModelList(value);
            case "headers_json", "extra_json" -> normalizeDict(value);
            // 参考实现用 Python 内置 bool 归一化（真值语义），不做字符串解析
            case "is_enabled", "is_builtin" -> pyBool(value);
            default -> value;
        };
    }

    /**
     * 归一并校验供应商 payload。
     *
     * <p>{@code partial=true} 时只规范化传入的字段、不补默认值（更新场景下 DB 已有值不可见，
     * 补缺省会误清空）。
     */
    static Map<String, Object> normalizePayload(Map<String, Object> data, boolean partial) {
        Map<String, Object> payload = new LinkedHashMap<>(data);
        if (!partial || payload.containsKey("provider_id")) {
            String providerId = pythonStrip(asString(payload.get("provider_id")));
            validateProviderId(providerId);
            payload.put("provider_id", providerId);
        }

        if (!partial || payload.containsKey("display_name")) {
            String displayName = pythonStrip(asString(payload.get("display_name")));
            if (displayName.isEmpty()) {
                throw new IllegalArgumentException("display_name 不能为空");
            }
            payload.put("display_name", displayName);
        }

        if (!partial || payload.containsKey("base_url")) {
            String baseUrl = pythonStrip(asString(payload.get("base_url")));
            if (baseUrl.isEmpty()) {
                throw new IllegalArgumentException("base_url 不能为空");
            }
            payload.put("base_url", baseUrl);
        }

        for (String endpointField :
                List.of("models_endpoint", "embedding_models_endpoint", "rerank_models_endpoint")) {
            if (payload.containsKey(endpointField)) {
                payload.put(endpointField, pythonStrip(asString(payload.get(endpointField))));
            }
        }

        Object providerType = payload.get("provider_type");
        if (providerType == null && !partial) {
            payload.put("provider_type", "openai");
        } else if (providerType != null) {
            List<String> sortedTypes = new ArrayList<>(VALID_PROVIDER_TYPES);
            java.util.Collections.sort(sortedTypes);
            if (!VALID_PROVIDER_TYPES.contains(providerType)) {
                throw new IllegalArgumentException(
                        "provider_type 必须是 " + String.join(", ", sortedTypes) + " 之一");
            }
        }

        // partial 模式下仅规范化传入值，非 partial 补全默认值
        for (Map.Entry<String, Object> entry : fieldDefaults().entrySet()) {
            String field = entry.getKey();
            if (payload.containsKey(field)) {
                payload.put(field, normalizeField(field, payload.get(field)));
            } else if (!partial) {
                payload.put(field, entry.getValue());
            }
        }

        // 仅当本次 payload 同时携带 capabilities 与 enabled_models 时做一致性校验，
        // 防止前端把超出 provider.capabilities 的模型 type 写入。
        // partial 模式下若只更新其中一项，跳过校验避免误判（DB 已有值不可见）。
        if (payload.containsKey("capabilities") && payload.containsKey("enabled_models")) {
            List<String> capabilities = asStringList(payload.get("capabilities"));
            if (!capabilities.isEmpty()) {
                validateModelsCapabilities(asModelList(payload.get("enabled_models")), capabilities);
            }
        }

        if (!partial) {
            validateRequestBodyOverridesScope(
                    asModelList(payload.get("enabled_models")), asString(payload.get("provider_type")));
        }

        return payload;
    }

    // ==================== 凭证与查询 ====================

    /** 解析 provider 的 API Key，优先直接配置，其次从环境变量读取。 */
    public static String resolveApiKey(ModelProvider provider) {
        if (provider.getApiKey() != null && !provider.getApiKey().isEmpty()) {
            return provider.getApiKey();
        }
        if (provider.getApiKeyEnv() != null && !provider.getApiKeyEnv().isEmpty()) {
            return System.getenv(provider.getApiKeyEnv());
        }
        return null;
    }

    /** 检查 provider 的凭证配置状态。仅对启用的 provider 做校验。 */
    public static String checkCredentialStatus(ModelProvider provider) {
        if (!Boolean.TRUE.equals(provider.getIsEnabled())) {
            return "ok";
        }
        if (provider.getApiKey() != null && !provider.getApiKey().isEmpty()) {
            return "ok";
        }
        if (provider.getApiKeyEnv() != null && !provider.getApiKeyEnv().isEmpty()) {
            String value = System.getenv(provider.getApiKeyEnv());
            return value != null && !value.isEmpty() ? "ok" : "warning";
        }
        return "warning";
    }

    /**
     * provider 的字典投影（参考实现 {@code ModelProvider.to_dict()}）。
     *
     * <p>JSON 文本列在此处还原为对象 / 列表，时间列按 UTC ISO 输出；
     * 键集合与顺序、空值兜底（{@code or []} / {@code or {}} / {@code bool(...)}）逐项对齐。
     */
    public static Map<String, Object> toDict(ModelProvider provider) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", provider.getId());
        data.put("provider_id", provider.getProviderId());
        data.put("display_name", provider.getDisplayName());
        data.put("provider_type", provider.getProviderType());
        data.put("default_protocol", provider.getDefaultProtocol());
        data.put("base_url", provider.getBaseUrl());
        data.put("embedding_base_url", provider.getEmbeddingBaseUrl());
        data.put("rerank_base_url", provider.getRerankBaseUrl());
        data.put("models_endpoint", provider.getModelsEndpoint());
        data.put("embedding_models_endpoint", provider.getEmbeddingModelsEndpoint());
        data.put("rerank_models_endpoint", provider.getRerankModelsEndpoint());
        data.put("api_key_env", provider.getApiKeyEnv());
        data.put("api_key", provider.getApiKey());
        data.put("capabilities", jsonOrDefault(provider.getCapabilities(), new ArrayList<>()));
        data.put("enabled_models", jsonOrDefault(provider.getEnabledModels(), new ArrayList<>()));
        data.put("headers_json", jsonOrDefault(provider.getHeadersJson(), new LinkedHashMap<>()));
        data.put("extra_json", jsonOrDefault(provider.getExtraJson(), new LinkedHashMap<>()));
        data.put("is_enabled", Boolean.TRUE.equals(provider.getIsEnabled()));
        data.put("is_builtin", Boolean.TRUE.equals(provider.getIsBuiltin()));
        data.put("created_by", provider.getCreatedBy());
        data.put("updated_by", provider.getUpdatedBy());
        data.put("created_at", DateTimeUtils.formatUtcDatetime(provider.getCreatedAt()));
        data.put("updated_at", DateTimeUtils.formatUtcDatetime(provider.getUpdatedAt()));
        return data;
    }

    /** 获取全部独立模型供应商配置。 */
    public List<ModelProvider> getAllModelProviders() {
        return modelProviderRepository.listModelProviders();
    }

    /** 按 provider_id 获取独立模型供应商配置。 */
    public ModelProvider getModelProviderById(String providerId) {
        return modelProviderRepository.getModelProvider(providerId);
    }

    /**
     * 确保独立模型配置模块的内置 provider 模板存在。
     *
     * <p>这里只补不存在的内置 provider，不覆盖管理员已编辑的配置；
     * 已存在但 enabled_models 为空时补上模板里的模型清单与能力。
     */
    public void ensureBuiltinModelProvidersInDb() {
        List<ModelProvider> existing = modelProviderRepository.listModelProviders();
        Map<String, ModelProvider> existingById = new LinkedHashMap<>();
        for (ModelProvider provider : existing) {
            existingById.put(provider.getProviderId(), provider);
        }

        for (Map<String, Object> providerDef : BuiltinProviders.BUILTIN_PROVIDERS) {
            String providerId = (String) providerDef.get("provider_id");
            ModelProvider existingProvider = existingById.get(providerId);
            if (existingProvider != null) {
                boolean hasModels =
                        !ModelProviderCache.parseEnabledModels(existingProvider.getEnabledModels())
                                .isEmpty();
                if (!hasModels && providerDef.get("enabled_models") != null) {
                    Map<String, Object> patch = new LinkedHashMap<>();
                    patch.put("enabled_models", normalizeModelList(providerDef.get("enabled_models")));
                    Object capabilities = providerDef.get("capabilities");
                    patch.put(
                            "capabilities",
                            capabilities != null
                                    ? capabilities
                                    : existingProvider.getCapabilities());
                    patch.put("updated_by", "system");
                    modelProviderRepository.updateModelProvider(existingProvider, patch);
                }
                continue;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : providerDef.entrySet()) {
                if (entry.getValue() != null) {
                    payload.put(entry.getKey(), entry.getValue());
                }
            }
            payload.put(
                    "enabled_models", payload.getOrDefault("enabled_models", new ArrayList<>()));
            payload.put("headers_json", payload.getOrDefault("headers_json", new LinkedHashMap<>()));
            payload.put("extra_json", payload.getOrDefault("extra_json", new LinkedHashMap<>()));
            payload.put("is_enabled", "siliconflow-cn".equals(providerId));
            payload.put("is_builtin", Boolean.TRUE);
            payload.put("created_by", "system");
            payload.put("updated_by", "system");
            modelProviderRepository.createModelProvider(normalizePayload(payload, false));
        }
    }

    // ==================== CRUD 用例 ====================

    /** 创建独立模型供应商配置。 */
    public ModelProvider createProviderConfig(Map<String, Object> data, String username) {
        Map<String, Object> payload = normalizePayload(data, false);
        if (modelProviderRepository.getModelProvider(asString(payload.get("provider_id"))) != null) {
            throw new IllegalArgumentException("供应商 " + payload.get("provider_id") + " 已存在");
        }
        payload.put("created_by", username);
        payload.put("updated_by", username);
        return modelProviderRepository.createModelProvider(payload);
    }

    /** 更新独立模型供应商配置；供应商不存在时返回 null。 */
    public ModelProvider updateProviderConfig(
            String providerId, Map<String, Object> data, String username) {
        ModelProvider provider = modelProviderRepository.getModelProvider(providerId);
        if (provider == null) {
            return null;
        }
        Map<String, Object> payload = normalizePayload(data, true);
        // partial 更新时仅传 enabled_models，结合 DB 中现有 capabilities 校验
        if (payload.containsKey("enabled_models") && !payload.containsKey("capabilities")) {
            List<String> existingCaps =
                    asStringList(jsonOrDefault(provider.getCapabilities(), new ArrayList<>()));
            if (!existingCaps.isEmpty()) {
                validateModelsCapabilities(asModelList(payload.get("enabled_models")), existingCaps);
            }
        }
        if (payload.containsKey("enabled_models") || payload.containsKey("provider_type")) {
            Object enabledModels =
                    payload.containsKey("enabled_models")
                            ? payload.get("enabled_models")
                            : ModelProviderCache.parseEnabledModels(provider.getEnabledModels());
            Object providerType =
                    payload.containsKey("provider_type")
                            ? payload.get("provider_type")
                            : provider.getProviderType();
            validateRequestBodyOverridesScope(asModelList(enabledModels), asString(providerType));
        }
        payload.put("updated_by", username);
        return modelProviderRepository.updateModelProvider(provider, payload);
    }

    /** 删除独立模型供应商配置；供应商不存在时返回 false。 */
    public boolean deleteProviderConfig(String providerId) {
        ModelProvider provider = modelProviderRepository.getModelProvider(providerId);
        if (provider == null) {
            return false;
        }
        modelProviderRepository.deleteModelProvider(provider);
        return true;
    }

    // ==================== 远端模型 ====================

    /** 拼接模型列表 URL：端点为空取基址；端点已是绝对地址则直接使用。 */
    static String modelsUrl(String baseUrl, String endpoint) {
        String base = baseUrl == null ? "" : baseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (endpoint == null || endpoint.isEmpty()) {
            return base;
        }
        String trimmed = pythonStrip(endpoint);
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        String path = trimmed;
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return base + "/" + path;
    }

    /** 规范化远端模型条目；无 id 时返回空表（调用方据此跳过）。 */
    static Map<String, Object> normalizeRemoteModel(Map<String, Object> rawModel, String modelType) {
        String modelId = pythonStrip(asString(rawModel.get("id")));
        if (modelId.isEmpty()) {
            return new LinkedHashMap<>();
        }

        Map<String, Object> architecture = normalizeDict(rawModel.get("architecture"));
        Map<String, Object> topProvider = normalizeDict(rawModel.get("top_provider"));
        Object rawType = rawModel.get("type");
        String normalizedType =
                rawType instanceof String text && VALID_MODEL_TYPES.contains(text) ? text : modelType;

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("id", modelId);
        normalized.put("object", rawModel.get("object"));
        normalized.put("created", rawModel.get("created"));
        normalized.put("owned_by", rawModel.get("owned_by"));
        normalized.put("type", normalizedType);
        normalized.put("display_name", asStringOr(rawModel.get("name"), modelId));
        normalized.put("description", rawModel.get("description"));
        normalized.put(
                "context_length",
                firstNonEmpty(rawModel.get("context_length"), topProvider.get("context_length")));
        normalized.put("max_completion_tokens", topProvider.get("max_completion_tokens"));
        normalized.put("input_modalities", orEmptyList(architecture.get("input_modalities")));
        normalized.put("output_modalities", orEmptyList(architecture.get("output_modalities")));
        normalized.put("supported_parameters", orEmptyList(rawModel.get("supported_parameters")));
        normalized.put("pricing", orEmptyMap(rawModel.get("pricing")));
        normalized.put("default_parameters", orEmptyMap(rawModel.get("default_parameters")));
        normalized.put("raw_metadata", rawModel);
        normalized.put("extra", new LinkedHashMap<>());

        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : normalized.entrySet()) {
            if (entry.getValue() != null) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /** 按单个模型类型端点拉取并规范化远端模型列表。 */
    private List<Map<String, Object>> fetchModelsFromEndpoint(
            HttpClient client,
            ModelProvider provider,
            Map<String, String> headers,
            String endpoint,
            String modelType) {
        if (endpoint == null || endpoint.isEmpty()) {
            return new ArrayList<>();
        }

        String url = modelsUrl(provider.getBaseUrl(), endpoint);
        HttpResponse<String> response = sendGet(client, url, headers);
        if (response.statusCode() >= 400) {
            throw new HttpStatusFailure(response.statusCode(), response.body());
        }

        Object payload = JSON.parse(response.body());
        Object rawModels = payload instanceof Map<?, ?> map ? map.get("data") : payload;
        if (!(rawModels instanceof List<?> items)) {
            throw new IllegalArgumentException(endpoint + " 响应必须是列表或包含 data 列表");
        }

        List<Map<String, Object>> models = new ArrayList<>();
        for (Object rawModel : items) {
            if (rawModel instanceof Map<?, ?> map) {
                Map<String, Object> normalized = normalizeRemoteModel(asStringKeyMap(map), modelType);
                if (!normalized.isEmpty()) {
                    models.add(normalized);
                }
            }
        }
        return models;
    }

    /**
     * 按 provider 配置实时拉取远端模型列表，不落库。
     *
     * <p>Chat 模型默认走 {@code /models}；embedding 只有 provider 声明能力时才走
     * {@code /embeddings/models}；rerank 供应商没有稳定通用端点，配置了 endpoint 才拉取。
     */
    public List<Map<String, Object>> fetchRemoteModels(ModelProvider provider) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry :
                RepoValues.toJsonObject(provider.getHeadersJson()).entrySet()) {
            headers.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        String apiKey = resolveApiKey(provider);
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.putIfAbsent("Authorization", "Bearer " + apiKey);
        }

        List<String> capabilities =
                asStringList(jsonOrDefault(provider.getCapabilities(), new ArrayList<>()));
        List<String[]> endpointSpecs = new ArrayList<>();
        endpointSpecs.add(new String[] {provider.getModelsEndpoint(), "chat"});
        if (capabilities.contains("embedding")) {
            endpointSpecs.add(new String[] {provider.getEmbeddingModelsEndpoint(), "embedding"});
        }
        if (capabilities.contains("rerank")
                && provider.getRerankModelsEndpoint() != null
                && !provider.getRerankModelsEndpoint().isEmpty()) {
            endpointSpecs.add(new String[] {provider.getRerankModelsEndpoint(), "rerank"});
        }

        Set<String> seenIds = new LinkedHashSet<>();
        List<Map<String, Object>> models = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder().connectTimeout(REMOTE_REQUEST_TIMEOUT).build();
        for (String[] spec : endpointSpecs) {
            for (Map<String, Object> model :
                    fetchModelsFromEndpoint(client, provider, headers, spec[0], spec[1])) {
                String modelKey = model.get("id") + "\u0000" + model.get("type");
                if (!seenIds.add(modelKey)) {
                    continue;
                }
                models.add(model);
            }
        }
        return models;
    }

    // ==================== 模型状态测试 ====================

    /**
     * 根据 spec 测试模型连接状态。
     *
     * <p>分支与返回字段、文案逐项对齐参考实现；具体模型选择与调用交由
     * {@link ModelSelectors}（对应参考实现的 {@code models/chat.py}、{@code models/embed.py}、
     * {@code models/rerank.py}）。
     */
    public Map<String, Object> testModelStatusBySpec(String spec) {
        ModelInfo info = modelProviderCache.getModelInfo(spec);
        if (info == null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("spec", spec);
            result.put("status", "error");
            result.put("message", "未找到模型: " + spec);
            return result;
        }

        try {
            if ("embedding".equals(info.modelType())) {
                ModelSelectors.TestResult test =
                        modelSelectors.selectEmbeddingModel(spec).testConnection();
                return statusResult(spec, test, "embedding");
            }
            if ("rerank".equals(info.modelType())) {
                ModelSelectors.TestResult test =
                        modelSelectors.getReranker(spec).testConnection();
                return statusResult(spec, test, "rerank");
            }

            ModelSelectors.ChatAdapter model = modelSelectors.selectModel(spec);
            List<Map<String, Object>> testMessages =
                    List.of(Map.<String, Object>of("role", "user", "content", "Say 1"));
            String content = model.call(testMessages);
            if (content != null && !content.isEmpty()) {
                Map<String, Object> available = new LinkedHashMap<>();
                available.put("spec", spec);
                available.put("status", "available");
                available.put("message", "连接正常");
                available.put("model_type", "chat");
                return available;
            }
            Map<String, Object> unavailable = new LinkedHashMap<>();
            unavailable.put("spec", spec);
            unavailable.put("status", "unavailable");
            unavailable.put("message", "响应无效");
            unavailable.put("model_type", "chat");
            return unavailable;
        } catch (Exception exc) {
            log.error("测试模型状态失败 {}: {}", spec, exc.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("spec", spec);
            result.put("status", "error");
            result.put("message", exc.getMessage() == null ? String.valueOf(exc) : exc.getMessage());
            result.put("model_type", info.modelType());
            return result;
        }
    }

    /** 把连接测试结果投影为状态响应：成功一律「连接正常」，失败返回模型给出的具体原因。 */
    private static Map<String, Object> statusResult(
            String spec, ModelSelectors.TestResult test, String modelType) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("spec", spec);
        result.put("status", test.success() ? "available" : "unavailable");
        result.put("message", test.success() ? "连接正常" : test.message());
        result.put("model_type", modelType);
        return result;
    }

    // ==================== 工具 ====================

    private static HttpResponse<String> sendGet(
            HttpClient client, String url, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            try {
                builder.header(header.getKey(), header.getValue());
            } catch (IllegalArgumentException ignored) {
                // 非法头名（如含下划线）跳过，与 requests / httpx 的宽松处理一致
            }
        }
        try {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exc.getMessage(), exc);
        } catch (Exception exc) {
            throw new IllegalStateException(
                    exc.getMessage() == null ? exc.toString() : exc.getMessage(), exc);
        }
    }

    /** 远端 API 返回非 2xx 时的错误（对应 {@code httpx.HTTPStatusError}）。 */
    public static class HttpStatusFailure extends RuntimeException {
        private final int statusCode;
        private final String responseText;

        public HttpStatusFailure(int statusCode, String responseText) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
            this.responseText = responseText == null ? "" : responseText;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getResponseText() {
            return responseText;
        }
    }

    // ==================== Python 语义小工具 ====================

    static String pythonStrip(String value) {
        return value == null ? "" : value.strip();
    }

    /** {@code str(x or "")}：null → 空串；其余按字符串化。 */
    static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** {@code str(x or fallback)}。 */
    static String asStringOr(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof String text) {
            return text.isEmpty() ? fallback : text;
        }
        return String.valueOf(value);
    }

    /** Python {@code or}：左值为假（null/空串/空集合/0）时取右值。 */
    static Object firstNonEmpty(Object left, Object right) {
        return isFalsy(left) ? right : left;
    }

    static boolean isFalsy(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.isEmpty();
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        if (value instanceof Boolean bool) {
            return !bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() == 0.0;
        }
        return false;
    }

    /** Python 内置 {@code bool} 的真值语义（注意与"解析 'false' 字符串"不同）。 */
    static Boolean pyBool(Object value) {
        return !isFalsy(value);
    }

    /** Python {@code int(...)}：数值取整、字符串按十进制解析，失败时原样渲染其报错文案。 */
    static Integer pythonInt(Object value) {
        if (value instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        if (value instanceof Number number) {
            if (number instanceof Double || number instanceof Float || number instanceof BigDecimal) {
                double asDouble = number.doubleValue();
                if (asDouble != Math.floor(asDouble) || Double.isInfinite(asDouble)) {
                    throw new IllegalArgumentException(
                            "invalid literal for int() with base 10: '" + value + "'");
                }
                return (int) asDouble;
            }
            return number.intValue();
        }
        String text = String.valueOf(value).strip();
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException(
                    "invalid literal for int() with base 10: '" + value + "'");
        }
    }

    /** {@code json.dumps(..., allow_nan=False)} 的可序列化性检查。 */
    private static void assertJsonSerializable(Map<String, Object> overrides, String modelId) {
        for (Object value : overrides.values()) {
            if (value instanceof Double asDouble
                    && (Double.isNaN(asDouble) || Double.isInfinite(asDouble))) {
                throw new IllegalArgumentException(
                        "模型 " + modelId + " 的 request_body_overrides 只能包含合法 JSON 值");
            }
            if (value instanceof Float asFloat
                    && (Float.isNaN(asFloat) || Float.isInfinite(asFloat))) {
                throw new IllegalArgumentException(
                        "模型 " + modelId + " 的 request_body_overrides 只能包含合法 JSON 值");
            }
        }
    }

    private static List<String> asStringList(Object value) {
        List<String> result = new ArrayList<>();
        for (Object item : normalizeList(value)) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private static List<Map<String, Object>> asModelList(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : normalizeList(value)) {
            if (item instanceof Map<?, ?> map) {
                result.add(asStringKeyMap(map));
            }
        }
        return result;
    }

    private static List<Map<String, Object>> nullSafeModels(List<Map<String, Object>> models) {
        return models == null ? List.of() : models;
    }

    static Map<String, Object> asStringKeyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static Object orEmptyList(Object value) {
        return value instanceof List ? value : new ArrayList<>();
    }

    private static Object orEmptyMap(Object value) {
        return value instanceof Map ? value : new LinkedHashMap<>();
    }

    /** JSON 文本列的读取：空值或解析失败均取兜底值（对应 {@code x or []} / {@code x or {}}）。 */
    static Object jsonOrDefault(String json, Object fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            Object parsed = JSON.parse(json);
            return parsed == null ? fallback : parsed;
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
