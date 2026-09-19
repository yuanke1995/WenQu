package com.wisesoft.wenqu.knowledge.graphs;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图谱向量库（实体 / 三元组向量化与检索）。
 *
 * <p>由参考实现的 knowledge/graphs/milvus_graph_vector_store.py 逐项翻译：
 * {@code upsert_graph_records} / {@code delete_graph_records} / {@code search_entities} /
 * {@code search_triples} / {@code drop_graph_collections}。
 *
 * <p><b>能力差异（如实标注）</b>：参考实现为图谱单独建 Milvus 集合
 * （{@code {kb_id}_entity} / {@code {kb_id}_triple}，各带稠密向量 + BM25 稀疏向量 + 双索引）。
 * 本工程的向量后端是 Spring AI {@link VectorStore}（Redis 向量库，与文档块同一后端），
 * 无独立集合概念，故以元数据字段 {@code record_type}（entity/triple）与 {@code kb_id}
 * 做命名空间隔离：
 * <ul>
 *   <li>写入：{@code id}=实体/三元组 id，{@code content}=归一化名/三元组文本，元数据带 kb_id 与 record_type。</li>
 *   <li>检索：按 {@code kb_id} + {@code record_type} 过滤表达式（等价于参考的"按集合检索"）后取 topK。</li>
 *   <li>删除：按 id 删除；整库清理走 {@code kb_id} 过滤表达式（等价于参考的 drop_collection）。</li>
 * </ul>
 * 参考中实体集合额外输出 {@code source_id}/{@code target_id}，本实现等价保留在三元组元数据中。
 *
 * <p>向量的生成由 {@link VectorStore} 内部绑定的 embedding 模型完成，因此
 * {@code embeddingModelSpec} 仅作记录、不切换模型（与 {@code KnowledgeBaseRuntime} 同口径）。
 */
@Component
public class GraphVectorStore {

    /** 元数据：记录类型（entity / triple）。 */
    public static final String META_RECORD_TYPE = "record_type";
    /** 元数据：知识库 id。 */
    public static final String META_KB_ID = "kb_id";

    /** 参考实现的 GRAPH_VECTOR_BATCH_SIZE。 */
    public static final int GRAPH_VECTOR_BATCH_SIZE = 100;

    private final VectorStore vectorStore;

    public GraphVectorStore(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 写入（upsert）图谱向量记录。
     *
     * <p>参考实现先按 {@code record_type} 取集合、批量编码，再 upsert；
     * 本实现按 id 覆盖写（Spring AI VectorStore 的 add 即按 id upsert 语义），
     * 不支持的 record_type 直接报错（与参考的 ValueError 一致）。
     */
    public void upsertGraphRecords(
            String kbId,
            String embeddingModelSpec,
            String recordType,
            List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        if (!"entity".equals(recordType) && !"triple".equals(recordType)) {
            throw new IllegalArgumentException("不支持的图谱向量记录类型: " + recordType);
        }

        List<Document> documents = new ArrayList<>(records.size());
        for (Map<String, Object> record : records) {
            String id = stringOf(record.get("id"));
            String content = stringOf(record.get("content"));
            if (id.isEmpty()) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put(META_KB_ID, kbId);
            metadata.put(META_RECORD_TYPE, recordType);
            // 与参考实现对齐：实体集合输出 id/content；三元组集合额外输出 source_id/target_id
            if ("triple".equals(recordType)) {
                metadata.put("source_id", stringOf(record.get("source_id")));
                metadata.put("target_id", stringOf(record.get("target_id")));
            }
            documents.add(new Document(id, content, metadata));
        }
        if (documents.isEmpty()) {
            return;
        }
        vectorStore.add(documents);
    }

    /** 按 id 删除实体 / 三元组向量记录（对应 delete_graph_records）。 */
    public void deleteGraphRecords(String kbId, List<String> entityIds, List<String> tripleIds) {
        List<String> ids = new ArrayList<>();
        if (entityIds != null) {
            ids.addAll(entityIds);
        }
        if (tripleIds != null) {
            ids.addAll(tripleIds);
        }
        if (ids.isEmpty()) {
            return;
        }
        vectorStore.delete(ids);
    }

    /** 实体向量检索（对应 search_entities）。 */
    public List<Map<String, Object>> searchEntities(
            String kbId, String queryText, String embeddingModelSpec, int topK) {
        return searchGraphCollection(kbId, "entity", queryText, topK,
                List.of("id", "content"));
    }

    /** 三元组向量检索（对应 search_triples）。 */
    public List<Map<String, Object>> searchTriples(
            String kbId, String queryText, String embeddingModelSpec, int topK) {
        return searchGraphCollection(kbId, "triple", queryText, topK,
                List.of("id", "content", "source_id", "target_id"));
    }

    /**
     * 清空该知识库的全部图谱向量（对应 drop_graph_collections：实体集合 + 三元组集合一并丢弃）。
     *
     * <p>参考实现按集合名 drop；本实现以 {@code kb_id} 过滤表达式删除同后端内的全部记录。
     */
    public void dropGraphCollections(String kbId) {
        if (kbId == null || kbId.isBlank()) {
            return;
        }
        Filter.Expression expression = new FilterExpressionBuilder()
                .eq(META_KB_ID, kbId)
                .build();
        vectorStore.delete(expression);
    }

    // ==================== 内部 ====================

    /**
     * 图谱向量检索的共用实现：按 kb_id + record_type 过滤后取 topK。
     *
     * <p>参考实现在 {@code top_k <= 0} 时直接返回空（本实现同样短路），
     * 集合不存在时返回空列表（本实现对应"过滤后无命中"）。
     */
    private List<Map<String, Object>> searchGraphCollection(
            String kbId, String recordType, String queryText, int topK, List<String> outputFields) {
        if (topK <= 0) {
            return List.of();
        }
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        Filter.Expression expression = builder
                .and(builder.eq(META_KB_ID, kbId), builder.eq(META_RECORD_TYPE, recordType))
                .build();
        SearchRequest request = SearchRequest.builder()
                .query(queryText == null ? "" : queryText)
                .topK(Math.max(topK, 1))
                .filterExpression(expression)
                .build();

        List<Document> documents = vectorStore.similaritySearch(request);
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> records = new ArrayList<>(documents.size());
        for (Document document : documents) {
            Map<String, Object> metadata = document.getMetadata();
            Map<String, Object> record = new LinkedHashMap<>();
            for (String field : outputFields) {
                if ("content".equals(field)) {
                    record.put(field, document.getText());
                } else if ("id".equals(field)) {
                    record.put(field, document.getId());
                } else {
                    record.put(field, metadata.get(field));
                }
            }
            record.put("score", document.getScore() == null ? 0.0d : document.getScore());
            records.add(record);
        }
        return records;
    }

    private static String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
