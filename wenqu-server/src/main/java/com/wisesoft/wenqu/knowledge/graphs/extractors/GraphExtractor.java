package com.wisesoft.wenqu.knowledge.graphs.extractors;

import com.wisesoft.wenqu.knowledge.graphs.GraphUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图谱抽取器基类与抽取结果标准化。
 *
 * <p>由参考实现的 knowledge/graphs/extractors/base.py 逐项翻译：
 * {@code GraphExtractor}（抽象抽取器）与 {@code normalize_extraction_result}（结果标准化）。
 */
public abstract class GraphExtractor {

    protected final Map<String, Object> options;

    protected GraphExtractor(Map<String, Object> options) {
        this.options = options == null ? new LinkedHashMap<>() : options;
    }

    /** 抽取器类型标识（注册表键）。 */
    public abstract String extractorType();

    /** 从文本中抽取实体与关系，返回原始结果对象。 */
    public abstract Map<String, Object> extract(String text, Map<String, Object> chunkMetadata);

    /** 选项校验，默认无约束。 */
    public void validateOptions() {
        // 默认无约束
    }

    /**
     * 标准化抽取结果：实体按「归一化名称 + label」去重合并、关系端点解析为实体引用，
     * 并为 metadata 补 {@code extractor_type} / {@code schema_version}。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalizeExtractionResult(
            Map<String, Object> result, String extractorType) {
        if (result == null) {
            throw new IllegalArgumentException("extraction_result 必须是对象");
        }

        Object entitiesRaw = result.get("entities");
        Object relationsRaw = result.get("relations");
        List<Object> entities = entitiesRaw == null ? List.of() : (List<Object>) entitiesRaw;
        List<Object> relations = relationsRaw == null ? List.of() : (List<Object>) relationsRaw;
        if (!(entitiesRaw == null || entitiesRaw instanceof List)
                || !(relationsRaw == null || relationsRaw instanceof List)) {
            throw new IllegalArgumentException("extraction_result.entities 和 relations 必须是数组");
        }

        Map<String, Map<String, Object>> normalizedEntitiesByKey = new LinkedHashMap<>();
        Map<String, Map<String, Object>> entityRefs = new LinkedHashMap<>();

        for (int index = 0; index < entities.size(); index++) {
            addEntity(entities.get(index), "entities[" + index + "]",
                    normalizedEntitiesByKey, entityRefs);
        }

        List<Map<String, Object>> normalizedRelations = new ArrayList<>();
        for (int index = 0; index < relations.size(); index++) {
            Object relationRaw = relations.get(index);
            if (!(relationRaw instanceof Map)) {
                throw new IllegalArgumentException("relations 元素必须是对象");
            }
            Map<String, Object> relation = (Map<String, Object>) relationRaw;
            Map<String, Object> source = normalizeRelationEndpoint(
                    relation.get("source"), entityRefs, normalizedEntitiesByKey,
                    result, "relations[" + index + "].source");
            Map<String, Object> target = normalizeRelationEndpoint(
                    relation.get("target"), entityRefs, normalizedEntitiesByKey,
                    result, "relations[" + index + "].target");
            String text = trimmed(relation.get("text"));
            if (text.isEmpty()) {
                throw new IllegalArgumentException("relations[].text 不能为空");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("source", source);
            item.put("target", target);
            item.put("text", text);
            String label = trimmed(relation.get("label"));
            item.put("label", label.isEmpty() ? "RELATED_TO" : label);
            normalizedRelations.add(item);
        }

        Object metadataRaw = result.get("metadata");
        Map<String, Object> metadata = metadataRaw instanceof Map
                ? new LinkedHashMap<>((Map<String, Object>) metadataRaw) : new LinkedHashMap<>();
        metadata.putIfAbsent("extractor_type", extractorType);
        metadata.putIfAbsent("schema_version", 1);

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("entities", new ArrayList<>(normalizedEntitiesByKey.values()));
        normalized.put("relations", normalizedRelations);
        normalized.put("metadata", metadata);
        return normalized;
    }

    // ==================== 内部 ====================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> addEntity(
            Object rawEntity,
            String path,
            Map<String, Map<String, Object>> normalizedEntitiesByKey,
            Map<String, Map<String, Object>> entityRefs) {
        Map<String, Object> normalizedEntity = normalizeEntity(rawEntity, path);
        String key = entityKey(normalizedEntity);
        Map<String, Object> existing = normalizedEntitiesByKey.get(key);
        if (existing == null) {
            normalizedEntitiesByKey.put(key, normalizedEntity);
            existing = normalizedEntity;
        } else {
            mergeAttributes(existing, normalizedEntity);
        }

        for (String ref : entityRefs(rawEntity, existing)) {
            entityRefs.put(ref, existing);
        }
        return existing;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeRelationEndpoint(
            Object endpoint,
            Map<String, Map<String, Object>> entityRefs,
            Map<String, Map<String, Object>> normalizedEntitiesByKey,
            Map<String, Object> result,
            String path) {
        if (endpoint instanceof Map) {
            return addEntity(endpoint, path, normalizedEntitiesByKey, entityRefs);
        }

        String endpointRef = endpoint == null ? "" : String.valueOf(endpoint).strip();
        Map<String, Object> entity = entityRefs.get(endpointRef);
        if (entity == null) {
            throw new IllegalArgumentException(
                    "relations[].source/target 必须是实体对象，或引用 entities[].text/id，"
                            + "未找到: " + path + "=" + endpointRef + ", Result: " + result);
        }
        return entity;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeEntity(Object entity, String path) {
        if (!(entity instanceof Map)) {
            throw new IllegalArgumentException(path + " 必须是对象");
        }
        Map<String, Object> source = (Map<String, Object>) entity;

        String text = trimmed(source.get("text"));
        if (text.isEmpty()) {
            throw new IllegalArgumentException(path + ".text 不能为空");
        }

        Object attributesRaw = source.get("attributes");
        List<Object> attributes = attributesRaw == null ? List.of() : (List<Object>) attributesRaw;
        if (!(attributesRaw == null || attributesRaw instanceof List)) {
            throw new IllegalArgumentException(path + ".attributes 必须是数组");
        }

        List<Map<String, Object>> normalizedAttributes = new ArrayList<>();
        for (Object attributeRaw : attributes) {
            if (!(attributeRaw instanceof Map)) {
                throw new IllegalArgumentException(path + ".attributes 元素必须是对象");
            }
            Map<String, Object> attribute = (Map<String, Object>) attributeRaw;
            String attributeText = trimmed(attribute.get("text"));
            if (attributeText.isEmpty()) {
                continue;
            }
            Map<String, Object> normalizedAttribute = new LinkedHashMap<>();
            normalizedAttribute.put("text", attributeText);
            String attributeLabel = trimmed(attribute.get("label"));
            normalizedAttribute.put("label", attributeLabel.isEmpty() ? "Attribute" : attributeLabel);
            normalizedAttributes.add(normalizedAttribute);
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("text", text);
        String label = trimmed(source.get("label"));
        normalized.put("label", label.isEmpty() ? "Entity" : label);
        normalized.put("attributes", normalizedAttributes);
        return normalized;
    }

    private static String entityKey(Map<String, Object> entity) {
        return GraphUtils.normalizeEntityName(String.valueOf(entity.get("text")))
                + "\u0000" + entity.get("label");
    }

    private static List<String> entityRefs(Object rawEntity, Map<String, Object> entity) {
        List<String> refs = new ArrayList<>();
        refs.add(String.valueOf(entity.get("text")));
        if (rawEntity instanceof Map) {
            String entityId = trimmed(((Map<?, ?>) rawEntity).get("id"));
            if (!entityId.isEmpty()) {
                refs.add(entityId);
            }
        }
        return refs;
    }

    @SuppressWarnings("unchecked")
    private static void mergeAttributes(Map<String, Object> target, Map<String, Object> source) {
        List<Map<String, Object>> targetAttributes =
                (List<Map<String, Object>>) target.computeIfAbsent("attributes", k -> new ArrayList<>());
        java.util.Set<String> knownAttributes = new java.util.HashSet<>();
        for (Map<String, Object> attribute : targetAttributes) {
            knownAttributes.add(attribute.get("text") + "\u0000" + attribute.get("label"));
        }
        Object sourceAttributes = source.get("attributes");
        if (!(sourceAttributes instanceof List)) {
            return;
        }
        for (Object attributeRaw : (List<Object>) sourceAttributes) {
            Map<String, Object> attribute = (Map<String, Object>) attributeRaw;
            String key = attribute.get("text") + "\u0000" + attribute.get("label");
            if (!knownAttributes.contains(key)) {
                targetAttributes.add(attribute);
                knownAttributes.add(key);
            }
        }
    }

    private static String trimmed(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }
}
