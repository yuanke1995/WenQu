package com.wisesoft.wenqu.repositories;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wisesoft.wenqu.common.StringUtils;
import com.wisesoft.wenqu.models.ModelInfo;
import com.wisesoft.wenqu.models.ModelProvider;
import com.wisesoft.wenqu.service.ModelProviderService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 模型缓存服务 —— 基于 Redis 的跨进程模型信息缓存。
 *
 * <p>由参考实现的 models/providers/cache.py 逐方法翻译：本地 memo 的 5 秒 TTL、
 * Redis 读写与失败降级、按 provider / 按模型类型 / 按 provider 分组的读取、
 * {@code rebuild} 的装配与覆盖顺序、{@code _get_base_url_for_type} 的取值优先级。
 *
 * <p>模型 spec 格式：{@code provider_id:model_id}（冒号分隔）。
 *
 * <p>必要替换（已标注）：
 * <ul>
 *   <li>缓存键前缀取本系统的缓存命名空间（参考实现使用其项目名作为前缀）。前缀标识的是
 *       本系统在共享 Redis 中的键空间，写成外部项目名会让两套系统的缓存互相误命中。
 *   <li>本地 memo 的时间源用 {@code System.nanoTime()}（单调时钟），对应 Python 的
 *       {@code time.monotonic()}——两者都刻意避开挂钟回拨。
 *   <li>参考实现在函数内延迟 import 服务层的 {@code resolve_api_key} 以规避循环依赖；
 *       本工程该方法为静态方法，直接调用即可，无循环依赖问题。
 * </ul>
 */
@Component
public class ModelProviderCache {

    private static final Logger log = LoggerFactory.getLogger(ModelProviderCache.class);

    /** 键前缀（必要替换：本系统命名空间）。 */
    public static final String REDIS_CACHE_KEY = "wenqu:model_cache";

    /** 本地 memo 有效期（秒）。 */
    private static final long CACHE_TTL_SECONDS = 5;

    private final StringRedisTemplate redis;

    /** 本地 memo（对应参考实现的 {@code _local_cache}）。 */
    private volatile Map<String, ModelInfo> localCache;

    /** 本地 memo 建立时刻（{@code System.nanoTime()}）。 */
    private volatile long localCacheAt;

    public ModelProviderCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 读取缓存；Redis 不可用或内容非法时返回空表（不写本地 memo，与参考实现一致）。 */
    private Map<String, ModelInfo> loadCache() {
        long now = System.nanoTime();
        Map<String, ModelInfo> snapshot = localCache;
        if (snapshot != null && (now - localCacheAt) < CACHE_TTL_SECONDS * 1_000_000_000L) {
            return snapshot;
        }

        Map<String, ModelInfo> cache;
        try {
            String raw = redis.opsForValue().get(REDIS_CACHE_KEY);
            if (raw == null || raw.isEmpty()) {
                localCache = new LinkedHashMap<>();
                localCacheAt = now;
                return new LinkedHashMap<>();
            }
            JSONObject items = JSON.parseObject(raw);
            cache = new LinkedHashMap<>();
            if (items != null) {
                for (Map.Entry<String, Object> entry : items.entrySet()) {
                    if (entry.getValue() instanceof Map<?, ?> data) {
                        cache.put(entry.getKey(), ModelInfo.fromDict(asStringKeyMap(data)));
                    }
                }
            }
        } catch (Exception exc) {
            log.warn("Failed to load model cache from Redis: {}", exc.getMessage());
            return new LinkedHashMap<>();
        }

        localCache = cache;
        localCacheAt = now;
        return cache;
    }

    private void invalidateLocal() {
        localCache = null;
        localCacheAt = 0L;
    }

    /** 按 spec 取模型信息。 */
    public ModelInfo getModelInfo(String spec) {
        return loadCache().get(spec);
    }

    /** 全部模型信息；给定类型时只返回该类型。 */
    public List<ModelInfo> getAllSpecs(String modelType) {
        Map<String, ModelInfo> cache = loadCache();
        if (modelType == null) {
            return new ArrayList<>(cache.values());
        }
        List<ModelInfo> result = new ArrayList<>();
        for (ModelInfo info : cache.values()) {
            if (modelType.equals(info.modelType())) {
                result.add(info);
            }
        }
        return result;
    }

    /** 全部模型信息（不限类型）。 */
    public List<ModelInfo> getAllSpecs() {
        return getAllSpecs(null);
    }

    /** 按 provider 分组返回某类型的模型（组内顺序 = 缓存写入顺序）。 */
    public Map<String, List<ModelInfo>> getSpecsGroupedByProvider(String modelType) {
        Map<String, List<ModelInfo>> grouped = new LinkedHashMap<>();
        for (ModelInfo info : loadCache().values()) {
            if (!modelType.equals(info.modelType())) {
                continue;
            }
            grouped.computeIfAbsent(info.providerId(), key -> new ArrayList<>()).add(info);
        }
        return grouped;
    }

    /**
     * 用数据库中的供应商配置重建缓存并写入 Redis。
     *
     * <p>与参考实现一致：只收录已启用的供应商；模型基址优先取模型自身的
     * {@code base_url_override}，否则按模型类型回落到 provider 的 embedding / rerank / 通用基址。
     */
    public void rebuild(List<ModelProvider> providers) {
        Map<String, ModelInfo> newCache = new LinkedHashMap<>();

        for (ModelProvider provider : providers) {
            if (!Boolean.TRUE.equals(provider.getIsEnabled())) {
                continue;
            }

            String apiKey = ModelProviderService.resolveApiKey(provider);
            if (apiKey == null) {
                apiKey = "";
            }

            for (Map<String, Object> model : parseEnabledModels(provider.getEnabledModels())) {
                Object rawId = model.get("id");
                if (rawId == null) {
                    continue;
                }
                String modelId = String.valueOf(rawId);
                String modelType = model.get("type") instanceof String type ? type : "chat";
                Object override = model.get("base_url_override");
                String baseUrl =
                        override instanceof String text && !text.isEmpty()
                                ? text
                                : baseUrlForType(provider, modelType);

                ModelInfo info =
                        new ModelInfo(
                                provider.getProviderId(),
                                modelId,
                                modelType,
                                model.get("display_name") instanceof String displayName
                                        ? displayName
                                        : modelId,
                                apiKey,
                                baseUrl,
                                provider.getProviderType(),
                                RepoValues.toJsonObject(provider.getHeadersJson()),
                                RepoValues.toJsonObject(provider.getExtraJson()),
                                RepoValues.toJsonObject(model.get("request_body_overrides")),
                                toInteger(model.get("dimension")),
                                model.containsKey("batch_size")
                                        ? toInteger(model.get("batch_size"))
                                        : ModelInfo.DEFAULT_BATCH_SIZE);
                newCache.put(info.spec(), info);
            }
        }

        saveCache(newCache);
        invalidateLocal();
        log.info("Model cache rebuilt: {} models → Redis", newCache.size());
    }

    private void saveCache(Map<String, ModelInfo> cache) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            for (Map.Entry<String, ModelInfo> entry : cache.entrySet()) {
                data.put(entry.getKey(), entry.getValue().toDict());
            }
            redis.opsForValue().set(REDIS_CACHE_KEY, JSON.toJSONString(data));
        } catch (Exception exc) {
            log.error("Failed to save model cache to Redis: {}", exc.getMessage());
        }
    }

    /** 按模型类型取基址：embedding/rerank 有专属基址时优先，否则回落 provider 的通用基址。 */
    private static String baseUrlForType(ModelProvider provider, String modelType) {
        if ("embedding".equals(modelType) && notBlank(provider.getEmbeddingBaseUrl())) {
            return provider.getEmbeddingBaseUrl();
        }
        if ("rerank".equals(modelType) && notBlank(provider.getRerankBaseUrl())) {
            return provider.getRerankBaseUrl();
        }
        return provider.getBaseUrl();
    }

    /**
     * 根据 spec 返回 {@link ModelInfo}（参考实现 {@code resolve_model_spec}）。
     *
     * <p>未命中时抛出 {@link IllegalArgumentException}，文案与参考实现逐字一致
     * （含可用模型列表，列表按 Python {@code repr} 渲染）。
     */
    public ModelInfo resolveModelSpec(String spec) {
        if (spec == null || spec.isEmpty()) {
            throw new IllegalArgumentException("model spec 不能为空");
        }

        ModelInfo info = getModelInfo(spec);
        if (info != null) {
            return info;
        }

        List<ModelInfo> allSpecs = getAllSpecs();
        List<String> available = new ArrayList<>();
        for (ModelInfo item : allSpecs.subList(0, Math.min(10, allSpecs.size()))) {
            available.add(item.spec());
        }
        throw new IllegalArgumentException(
                "未找到模型: '" + spec + "'。可用模型 (" + allSpecs.size() + "): "
                        + StringUtils.pythonListRepr(available));
    }

    private static Map<String, Object> asStringKeyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isEmpty();
    }

    private static Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : Integer.valueOf(text);
    }

    /**
     * 解析 enabled_models 文本列。
     *
     * <p>参考实现该列是 JSON 数组（ORM 直接给 list）；本工程经 MySQL 文本列落库，
     * 故按 JSON 数组解析——空值 / 非法值均视为空表（与 {@code provider.enabled_models or []} 等价）。
     */
    public static List<Map<String, Object>> parseEnabledModels(String json) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            List<Object> parsed = JSON.parseArray(json, Object.class);
            if (parsed == null) {
                return result;
            }
            for (Object item : parsed) {
                if (item instanceof Map<?, ?> map) {
                    result.add(asStringKeyMap(map));
                }
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return result;
    }
}
