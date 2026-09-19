package com.wisesoft.wenqu.knowledge.graphs.extractors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 图谱抽取器工厂（注册表 + 创建）。
 *
 * <p>由参考实现的 knowledge/graphs/extractors/factory.py 逐项翻译：
 * {@code _registry} 注册表、{@code create}（创建并校验选项）、{@code supported_types}。
 */
public final class GraphExtractorFactory {

    /** 类型 → 构造器。参考实现注册了 {@code llm}。 */
    private static final Map<String, Function<Map<String, Object>, GraphExtractor>> REGISTRY =
            new LinkedHashMap<>();

    static {
        REGISTRY.put("llm", LlmGraphExtractor::new);
    }

    private GraphExtractorFactory() {}

    /** 按类型创建抽取器并校验其选项。 */
    public static GraphExtractor create(String extractorType, Map<String, Object> options) {
        String normalizedType = extractorType == null ? "" : extractorType.toLowerCase();
        Function<Map<String, Object>, GraphExtractor> builder = REGISTRY.get(normalizedType);
        if (builder == null) {
            throw new IllegalArgumentException("不支持的图谱抽取器类型: " + extractorType);
        }
        GraphExtractor extractor = builder.apply(options == null ? new LinkedHashMap<>() : options);
        extractor.validateOptions();
        return extractor;
    }

    /** 已支持的类型清单。 */
    public static List<String> supportedTypes() {
        return List.copyOf(REGISTRY.keySet());
    }
}
