package com.wisesoft.wenqu.models;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 不可变的模型信息，供运行时使用。
 *
 * <p>由参考实现 models/providers/cache.py 的 {@code ModelInfo}（frozen dataclass）逐字段翻译：
 * 字段名与顺序、{@code spec} 属性、{@code to_dict()} / {@code from_dict()} 的键集合保持一致。
 *
 * <p>模型 spec 格式：{@code provider_id:model_id}（冒号分隔），{@code model_id} 允许包含斜杠。
 *
 * <p>平台差异（必要替换）：Python dataclass 的"字段缺省值"只在**调用点省略该参数**时生效
 * （构造时显式传入 None 就会存 None）。Java record 的每个分量都必须显式传参，无法在构造器里
 * 区分"省略"与"显式 null"，因此不在本类内补默认值——默认值由各调用点按参考实现逐一补
 * （{@code headers}/{@code extra}/{@code request_body_overrides} 空表、{@code batch_size=40}），
 * 以保持"显式 null 就存 null"的原始语义。{@link #DEFAULT_BATCH_SIZE} 供调用点引用。
 */
public record ModelInfo(
        String providerId,
        String modelId,
        String modelType,  // chat / embedding / rerank
        String displayName,
        // 运行时配置
        String apiKey,
        String baseUrl,
        String providerType,  // openai / anthropic / gemini / openrouter
        // 可选配置
        Map<String, Object> headers,
        Map<String, Object> extra,  // provider 级 extra_json，不是 OpenAI chat 的 extra_body
        Map<String, Object> requestBodyOverrides,
        // Embedding 专属
        Integer dimension,
        Integer batchSize) {

    /** 参考实现 {@code batch_size: int = 40} 的默认值（调用点在"未提供"时显式传入）。 */
    public static final int DEFAULT_BATCH_SIZE = 40;

    /** 模型 spec：{@code provider_id:model_id}。 */
    public String spec() {
        return providerId + ":" + modelId;
    }

    /** 供 Redis 序列化的键集合与顺序照搬参考实现 {@code to_dict()}。 */
    public Map<String, Object> toDict() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("provider_id", providerId);
        data.put("model_id", modelId);
        data.put("model_type", modelType);
        data.put("display_name", displayName);
        data.put("api_key", apiKey);
        data.put("base_url", baseUrl);
        data.put("provider_type", providerType);
        data.put("headers", headers);
        data.put("extra", extra);
        data.put("request_body_overrides", requestBodyOverrides);
        data.put("dimension", dimension);
        data.put("batch_size", batchSize);
        return data;
    }

    /**
     * 从缓存快照还原。
     *
     * <p>与参考实现 {@code from_dict} 一致：前 7 个键为必填（缺失即由取值处报错），
     * 其余键缺失时取默认值（{@code batch_size} 取 40，其余空表 / null）；键存在但为 null 时原样存 null。
     */
    @SuppressWarnings("unchecked")
    public static ModelInfo fromDict(Map<String, ?> data) {
        return new ModelInfo(
                (String) data.get("provider_id"),
                (String) data.get("model_id"),
                (String) data.get("model_type"),
                (String) data.get("display_name"),
                (String) data.get("api_key"),
                (String) data.get("base_url"),
                (String) data.get("provider_type"),
                optionalMap(data, "headers"),
                optionalMap(data, "extra"),
                optionalMap(data, "request_body_overrides"),
                toInteger(data.get("dimension")),
                data.containsKey("batch_size")
                        ? toInteger(data.get("batch_size"))
                        : DEFAULT_BATCH_SIZE);
    }

    /** {@code data.get(key, {})} 的等价：键缺失取空表，键存在（含显式 null）则原样返回。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> optionalMap(Map<String, ?> data, String key) {
        return data.containsKey(key) ? (Map<String, Object>) data.get(key) : emptyMap();
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

    /** 空表的便捷构造（参考实现 {@code {} / []} 缺省值）。 */
    public static Map<String, Object> emptyMap() {
        return new LinkedHashMap<>();
    }
}
