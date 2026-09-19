package com.wisesoft.wenqu.knowledge.graphs;

import com.wisesoft.wenqu.common.HashUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图谱构建相关的纯函数工具集。
 *
 * <p>由参考实现的 knowledge/graphs/graph_utils.py 逐函数翻译：实体/三元组 ID 派生、
 * 集合命名、抽取结果 → 图结构转换（实体按「归一化名称 + label」去重、属性取并集），
 * 以及写入 Neo4j 的 Cypher 模板。
 */
public final class GraphUtils {

    private GraphUtils() {}

    /** 统一实体名称：去首尾空白、小写化、压缩内部连续空白。 */
    public static String normalizeEntityName(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) {
                pendingSpace = sb.length() > 0;
                continue;
            }
            if (pendingSpace) {
                sb.append(' ');
                pendingSpace = false;
            }
            sb.append(Character.toLowerCase(ch));
        }
        return sb.toString();
    }

    /** 实体 ID：kb + 归一化名称 + label 的定长摘要。 */
    public static String computeEntityId(String kbId, String normalizedName, String label) {
        return HashUtils.hashstr(kbId + ":" + normalizedName + ":" + label, 32, false, null);
    }

    /** 三元组 ID：kb + 源实体（名/类型）+ 关系类型 + 目标实体（名/类型）的定长摘要。 */
    public static String computeTripleId(
            String kbId,
            String sourceNormalizedName,
            String sourceLabel,
            String relationType,
            String targetNormalizedName,
            String targetLabel) {
        return HashUtils.hashstr(
                kbId + ":" + sourceNormalizedName + ":" + sourceLabel + ":" + relationType
                        + ":" + targetNormalizedName + ":" + targetLabel,
                32, false, null);
    }

    /** 实体向量集合名。 */
    public static String graphEntityCollectionName(String kbId) {
        return kbId + "_entity";
    }

    /** 三元组向量集合名。 */
    public static String graphTripleCollectionName(String kbId) {
        return kbId + "_triple";
    }

    /**
     * 将抽取器产出的标准化结果转换为 Neo4j 写入所需的图结构。
     *
     * <p>返回的 entities 已完成去重合并：同名同 label 的实体只保留一份，
     * 属性（attributes）取并集。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> buildGraphPayload(Map<String, Object> normalizedResult) {
        List<Map<String, Object>> entities = new ArrayList<>();
        Map<String, Map<String, Object>> entityByKey = new LinkedHashMap<>();

        List<Map<String, Object>> rawEntities = (List<Map<String, Object>>) normalizedResult.get("entities");
        List<Map<String, Object>> rawRelations = (List<Map<String, Object>>) normalizedResult.get("relations");

        for (Map<String, Object> entity : orEmpty(rawEntities)) {
            addEntity(entity, entities, entityByKey);
        }

        List<Map<String, Object>> relations = new ArrayList<>();
        for (Map<String, Object> relation : orEmpty(rawRelations)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("source", addEntity(
                    (Map<String, Object>) relation.get("source"), entities, entityByKey));
            item.put("target", addEntity(
                    (Map<String, Object>) relation.get("target"), entities, entityByKey));
            item.put("text", relation.get("text"));
            Object label = relation.get("label");
            item.put("label", label == null || String.valueOf(label).isEmpty()
                    ? "RELATED_TO" : String.valueOf(label));
            relations.add(item);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entities", entities);
        payload.put("relations", relations);
        payload.put("metadata", normalizedResult.get("metadata"));
        return payload;
    }

    /** 把实体并入结果集（去重 + 属性并集），返回其在图中的 id。 */
    @SuppressWarnings("unchecked")
    private static String addEntity(
            Map<String, Object> entity,
            List<Map<String, Object>> entities,
            Map<String, Map<String, Object>> entityByKey) {
        Object labelValue = entity.get("label");
        String label = labelValue == null || String.valueOf(labelValue).isEmpty()
                ? "Entity" : String.valueOf(labelValue);
        String key = normalizeEntityName(String.valueOf(entity.get("text"))) + "\u0000" + label;

        Map<String, Object> existing = entityByKey.get(key);
        if (existing != null) {
            java.util.Set<String> knownAttributes = new java.util.HashSet<>();
            for (Map<String, Object> attr : orEmpty(
                    (List<Map<String, Object>>) existing.get("attributes"))) {
                knownAttributes.add(attr.get("text") + "\u0000" + attr.get("label"));
            }
            List<Map<String, Object>> existingAttributes =
                    (List<Map<String, Object>>) existing.get("attributes");
            for (Map<String, Object> attribute : orEmpty(
                    (List<Map<String, Object>>) entity.get("attributes"))) {
                String attributeKey = attribute.get("text") + "\u0000" + attribute.get("label");
                if (!knownAttributes.contains(attributeKey)) {
                    existingAttributes.add(attribute);
                    knownAttributes.add(attributeKey);
                }
            }
            return String.valueOf(existing.get("id"));
        }

        Map<String, Object> graphEntity = new LinkedHashMap<>();
        graphEntity.put("id", "e" + (entities.size() + 1));
        graphEntity.put("text", entity.get("text"));
        graphEntity.put("label", label);
        List<Map<String, Object>> attributes =
                (List<Map<String, Object>>) entity.get("attributes");
        graphEntity.put("attributes", attributes == null ? new ArrayList<>() : new ArrayList<>(attributes));
        entities.add(graphEntity);
        entityByKey.put(key, graphEntity);
        return String.valueOf(graphEntity.get("id"));
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    // ─── Cypher 模板 ────────────────────────────────────────────────
    // 将大段 Cypher 字符串集中管理，提升 write_chunk_graph 的可读性。

    /** MERGE Chunk 节点并写入元数据。 */
    public static String cypherMergeChunk(String dbLabel) {
        return """
                MERGE (c:Chunk:MilvusKB:`%s` {chunk_id: $chunk_id})
                SET c.file_id = $file_id,
                    c.kb_id = $kb_id,
                    c.chunk_index = $chunk_index,
                    c.content_preview = $content_preview,
                    c.start_char_pos = $start_char_pos,
                    c.end_char_pos = $end_char_pos
                """.formatted(dbLabel);
    }

    /** MERGE Entity 节点并创建 Chunk → Entity 的 MENTIONS 关系。 */
    public static String cypherMergeEntityMention(String dbLabel) {
        return """
                MATCH (c:Chunk:MilvusKB:`%s` {chunk_id: $chunk_id})
                MERGE (e:Entity:MilvusKB:`%s` {
                    kb_id: $kb_id,
                    normalized_name: $normalized_name,
                    label: $entity_label
                })
                SET e.entity_id = $entity_id,
                    e.name = $name,
                    e.attributes = $attributes
                MERGE (c)-[m:MENTIONS {chunk_id: $chunk_id, file_id: $file_id, kb_id: $kb_id}]->(e)
                """.formatted(dbLabel, dbLabel);
    }

    /** MERGE 两个 Entity 之间的 RELATION 边。 */
    public static String cypherMergeRelation(String dbLabel) {
        return """
                MATCH (source:Entity:MilvusKB:`%s` {
                    kb_id: $kb_id,
                    normalized_name: $source_name,
                    label: $source_label
                })
                MATCH (target:Entity:MilvusKB:`%s` {
                    kb_id: $kb_id,
                    normalized_name: $target_name,
                    label: $target_label
                })
                MERGE (source)-[r:RELATION {
                    kb_id: $kb_id,
                    chunk_id: $chunk_id,
                    source_name: $source_name,
                    target_name: $target_name,
                    type: $relation_type
                }]->(target)
                SET r.triple_id = $triple_id,
                    r.text = $text,
                    r.file_id = $file_id,
                    r.extractor_type = $extractor_type
                """.formatted(dbLabel, dbLabel);
    }
}
