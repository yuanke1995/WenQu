package com.wisesoft.wenqu.repositories;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.KnowledgeChunk;
import com.wisesoft.wenqu.repository.port.KnowledgeChunkMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Repository;

/**
 * 知识块仓储。
 *
 * <p>由参考实现的 repositories/knowledge_chunk_repository.py 逐方法翻译：可写字段白名单、
 * 分批参数、排序规则、批量 upsert 的"有则更新无则插入"语义、图谱状态的写入与计数口径一致。
 *
 * <p>必要替换（MySQL 方言与框架差异）：
 * <ul>
 *   <li>JSON 列取路径：参考实现用 PostgreSQL 的 {@code details["status"].as_string()}；
 *       本工程用 {@code JSON_UNQUOTE(JSON_EXTRACT(col, '$.status'))}，配 {@code COALESCE} 兜底，
 *       与参考实现的 {@code func.coalesce(..., "pending")} 对齐。
 *   <li>置空字段：本工程用显式 {@code set} 写 UPDATE（框架默认更新策略会跳过 null 列，
 *       会导致"清空"静默失效）。
 * </ul>
 */
@Repository
public class KnowledgeChunkRepository {

    /** 分批大小：参考实现按单条 SQL 参数上限设定。 */
    public static final int SQL_IN_BATCH_SIZE = 10_000;

    /** 可写字段白名单。 */
    private static final Set<String> WRITABLE_FIELDS =
            Set.of(
                    "chunk_id",
                    "file_id",
                    "kb_id",
                    "chunk_index",
                    "content",
                    "start_char_pos",
                    "end_char_pos",
                    "start_token_pos",
                    "end_token_pos",
                    "graph_indexed",
                    "graph_extraction_details",
                    "ent_ids",
                    "tags",
                    "extraction_result");

    private static final String GRAPH_STATUS_EXPR =
            "COALESCE(JSON_UNQUOTE(JSON_EXTRACT(graph_extraction_details, '$.status')), 'pending')";

    private final KnowledgeChunkMapper chunkMapper;

    public KnowledgeChunkRepository(KnowledgeChunkMapper chunkMapper) {
        this.chunkMapper = chunkMapper;
    }

    static <T> List<List<T>> iterBatches(List<T> items) {
        return iterBatches(items, SQL_IN_BATCH_SIZE);
    }

    static <T> List<List<T>> iterBatches(List<T> items, int batchSize) {
        List<List<T>> batches = new ArrayList<>();
        for (int index = 0; index < items.size(); index += batchSize) {
            batches.add(items.subList(index, Math.min(items.size(), index + batchSize)));
        }
        return batches;
    }

    /** 按 chunk_id 取单个知识块。 */
    public KnowledgeChunk getByChunkId(String chunkId) {
        return chunkMapper.selectOne(new LambdaQueryWrapper<KnowledgeChunk>().eq(KnowledgeChunk::getChunkId, chunkId));
    }

    /** 单文件的知识块，按块序号升序。 */
    public List<KnowledgeChunk> listByFileId(String fileId) {
        return chunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getFileId, fileId)
                        .orderByAsc(KnowledgeChunk::getChunkIndex));
    }

    /** 多文件的知识块，按文件与块序号升序（分批查询后统一排序）。 */
    public List<KnowledgeChunk> listByFileIds(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (List<String> batch : iterBatches(fileIds)) {
            chunks.addAll(
                    chunkMapper.selectList(
                            new LambdaQueryWrapper<KnowledgeChunk>()
                                    .in(KnowledgeChunk::getFileId, batch)
                                    .orderByAsc(KnowledgeChunk::getFileId)
                                    .orderByAsc(KnowledgeChunk::getChunkIndex)));
        }
        chunks.sort(
                java.util.Comparator.comparing(KnowledgeChunk::getFileId)
                        .thenComparing(KnowledgeChunk::getChunkIndex));
        return chunks;
    }

    /** 按文件聚合知识块数量。 */
    public Map<String, Integer> countByFileIds(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (List<String> batch : iterBatches(fileIds)) {
            QueryWrapper<KnowledgeChunk> wrapper = new QueryWrapper<>();
            wrapper.select("file_id", "COUNT(*) AS cnt")
                    .in("file_id", batch)
                    .groupBy("file_id");
            for (Map<String, Object> row : chunkMapper.selectMaps(wrapper)) {
                Long count = RepoValues.toLong(row.get("cnt"));
                counts.put(String.valueOf(row.get("file_id")), count == null ? 0 : count.intValue());
            }
        }
        return counts;
    }

    /** 单知识库的全部知识块，按主键升序。 */
    public List<KnowledgeChunk> listByKbId(String kbId) {
        return chunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getKbId, kbId)
                        .orderByAsc(KnowledgeChunk::getId));
    }

    /**
     * kb 作用域的关键词召回（为检索面 aquery 的 keyword / hybrid 分支提供召回）。
     *
     * <p><b>必要替换（引擎差异，显式标注）</b>：参考实现的关键词召回走 Milvus 稀疏向量 BM25
     * （{@code anns_field=CONTENT_SPARSE_FIELD} + {@code drop_ratio_search}），本工程未引入稀疏向量索引，
     * 改在 knowledge_chunks 表内做 kb 作用域的多词包含匹配，命中打分由调用方统一计算。
     * 返回顺序按主键升序（与 BM25 的相关度排序不同），排序同样由调用方统一重排。
     *
     * @param kbId    知识库 ID
     * @param terms   查询词（已抽取，非空）
     * @param fileIds 文件范围；{@code null} 表示不限，空集合表示必然无命中
     * @param limit   召回上限
     */
    public List<KnowledgeChunk> searchByKeywords(
            String kbId, List<String> terms, Collection<String> fileIds, int limit) {
        if (kbId == null || terms == null || terms.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }
        if (fileIds != null && fileIds.isEmpty()) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<KnowledgeChunk> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeChunk::getKbId, kbId);
        if (fileIds != null) {
            wrapper.in(KnowledgeChunk::getFileId, fileIds);
        }
        wrapper.and(nested -> {
            boolean first = true;
            for (String term : terms) {
                if (first) {
                    nested.like(KnowledgeChunk::getContent, term);
                    first = false;
                } else {
                    nested.or().like(KnowledgeChunk::getContent, term);
                }
            }
        });
        wrapper.orderByAsc(KnowledgeChunk::getId);
        wrapper.last("LIMIT " + limit);
        return chunkMapper.selectList(wrapper);
    }

    /** 按 chunk_id 列表取知识块（保持入参顺序，缺失的跳过）。 */
    public List<KnowledgeChunk> listByChunkIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, KnowledgeChunk> byId = new LinkedHashMap<>();
        for (List<String> batch : iterBatches(chunkIds)) {
            for (KnowledgeChunk chunk :
                    chunkMapper.selectList(
                            new LambdaQueryWrapper<KnowledgeChunk>().in(KnowledgeChunk::getChunkId, batch))) {
                byId.put(chunk.getChunkId(), chunk);
            }
        }
        List<KnowledgeChunk> result = new ArrayList<>();
        for (String chunkId : chunkIds) {
            KnowledgeChunk chunk = byId.get(chunkId);
            if (chunk != null) {
                result.add(chunk);
            }
        }
        return result;
    }

    /** 批量 upsert：按 chunk_id 有则更新（仅白名单字段）无则插入。 */
    public List<KnowledgeChunk> batchUpsert(List<Map<String, Object>> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> sanitized = new ArrayList<>();
        for (Map<String, Object> chunk : chunks) {
            Map<String, Object> kept = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : chunk.entrySet()) {
                if (WRITABLE_FIELDS.contains(entry.getKey())) {
                    kept.put(entry.getKey(), entry.getValue());
                }
            }
            sanitized.add(kept);
        }
        List<String> chunkIds = new ArrayList<>();
        for (Map<String, Object> chunk : sanitized) {
            chunkIds.add(String.valueOf(chunk.get("chunk_id")));
        }

        Map<String, KnowledgeChunk> existingByChunkId = new LinkedHashMap<>();
        for (List<String> batch : iterBatches(chunkIds)) {
            for (KnowledgeChunk chunk :
                    chunkMapper.selectList(
                            new LambdaQueryWrapper<KnowledgeChunk>().in(KnowledgeChunk::getChunkId, batch))) {
                existingByChunkId.put(chunk.getChunkId(), chunk);
            }
        }

        List<KnowledgeChunk> records = new ArrayList<>();
        for (Map<String, Object> chunkData : sanitized) {
            String chunkId = String.valueOf(chunkData.get("chunk_id"));
            KnowledgeChunk record = existingByChunkId.get(chunkId);
            if (record == null) {
                record = new KnowledgeChunk();
                applyChunkFields(record, chunkData);
                // 时间列默认值在参考实现由 ORM 填充，本层不经 ORM，需显式补上
                record.setCreatedAt(record.getCreatedAt() == null ? DateTimeUtils.utcNowNaive() : record.getCreatedAt());
                record.setUpdatedAt(record.getUpdatedAt() == null ? DateTimeUtils.utcNowNaive() : record.getUpdatedAt());
                chunkMapper.insert(record);
            } else {
                applyChunkFields(record, chunkData);
                chunkMapper.updateById(record);
            }
            records.add(record);
        }
        return records;
    }

    /** 删除单文件的知识块，返回删除行数。 */
    public int deleteByFileId(String fileId) {
        return chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunk>().eq(KnowledgeChunk::getFileId, fileId));
    }

    /** 删除单知识库的知识块，返回删除行数。 */
    public int deleteByKbId(String kbId) {
        return chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunk>().eq(KnowledgeChunk::getKbId, kbId));
    }

    /** 单知识库的知识块总数。 */
    public int countByKbId(String kbId) {
        Long count =
                chunkMapper.selectCount(new LambdaQueryWrapper<KnowledgeChunk>().eq(KnowledgeChunk::getKbId, kbId));
        return count == null ? 0 : count.intValue();
    }

    /** 单知识库中已完成图谱索引的知识块数。 */
    public int countGraphIndexedByKbId(String kbId) {
        LambdaQueryWrapper<KnowledgeChunk> wrapper =
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getKbId, kbId)
                        .eq(KnowledgeChunk::getGraphIndexed, true);
        Long count = chunkMapper.selectCount(wrapper);
        return count == null ? 0 : count.intValue();
    }

    /** 单知识库中已完成图谱结构索引的知识块数。 */
    public int countGraphStructureIndexedByKbId(String kbId) {
        Long count =
                chunkMapper.selectCount(
                        new LambdaQueryWrapper<KnowledgeChunk>()
                                .eq(KnowledgeChunk::getKbId, kbId)
                                .eq(KnowledgeChunk::getGraphStructureIndexed, true));
        return count == null ? 0 : count.intValue();
    }

    /** 单知识库的图谱抽取状态分布（缺省视为 pending）。 */
    public Map<String, Integer> countGraphExtractionStatusesByKbId(String kbId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("pending", 0);
        counts.put("succeeded", 0);
        counts.put("failed", 0);
        QueryWrapper<KnowledgeChunk> wrapper = new QueryWrapper<>();
        wrapper.select(GRAPH_STATUS_EXPR + " AS status", "COUNT(*) AS cnt")
                .eq("kb_id", kbId)
                .groupBy(GRAPH_STATUS_EXPR);
        for (Map<String, Object> row : chunkMapper.selectMaps(wrapper)) {
            Long count = RepoValues.toLong(row.get("cnt"));
            counts.put(String.valueOf(row.get("status")), count == null ? 0 : count.intValue());
        }
        return counts;
    }

    /** 图谱抽取失败的样本（最多 10 条，按主键倒序）。 */
    public List<Map<String, Object>> listGraphExtractionFailedSamples(String kbId, int limit) {
        int bounded = Math.max(1, Math.min(limit, 10));
        QueryWrapper<KnowledgeChunk> wrapper = new QueryWrapper<>();
        wrapper.eq("kb_id", kbId)
                .apply(GRAPH_STATUS_EXPR + " = {0}", "failed")
                .orderByDesc("id")
                .last("LIMIT " + bounded);
        List<Map<String, Object>> result = new ArrayList<>();
        for (KnowledgeChunk chunk : chunkMapper.selectList(wrapper)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunk_id", chunk.getChunkId());
            item.put("file_id", chunk.getFileId());
            item.put("chunk_index", chunk.getChunkIndex());
            item.put("content", chunk.getContent());
            item.put("details", RepoValues.parseObject(chunk.getGraphExtractionDetails()));
            result.add(item);
        }
        return result;
    }

    /** 单文件中已完成图谱索引的知识块数。 */
    public int countGraphIndexedByFileId(String fileId) {
        Long count =
                chunkMapper.selectCount(
                        new LambdaQueryWrapper<KnowledgeChunk>()
                                .eq(KnowledgeChunk::getFileId, fileId)
                                .eq(KnowledgeChunk::getGraphIndexed, true));
        return count == null ? 0 : count.intValue();
    }

    /** 单知识库中待图谱索引（未标记 graph_indexed）的知识块数。 */
    public int countGraphPendingByKbId(String kbId) {
        Long count =
                chunkMapper.selectCount(
                        new LambdaQueryWrapper<KnowledgeChunk>()
                                .eq(KnowledgeChunk::getKbId, kbId)
                                .ne(KnowledgeChunk::getGraphIndexed, true));
        return count == null ? 0 : count.intValue();
    }

    /** 单知识库中待图谱索引的知识块，按主键升序（支持游标）。 */
    public List<KnowledgeChunk> listGraphPendingByKbId(String kbId, int limit, int afterId) {
        return chunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getKbId, kbId)
                        .ne(KnowledgeChunk::getGraphIndexed, true)
                        .gt(KnowledgeChunk::getId, afterId)
                        .orderByAsc(KnowledgeChunk::getId)
                        .last("LIMIT " + Math.max(limit, 1)));
    }

    /** 写入抽取结果并标记状态为成功。 */
    public void updateExtractionResult(String chunkId, Map<String, Object> extractionResult, int attemptCount) {
        JSONObject details = new JSONObject();
        details.put("status", "succeeded");
        details.put("attempt_count", attemptCount);
        chunkMapper.update(
                null,
                new LambdaUpdateWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getChunkId, chunkId)
                        .set(KnowledgeChunk::getExtractionResult, JSON.toJSONString(extractionResult))
                        .set(KnowledgeChunk::getGraphExtractionDetails, JSON.toJSONString(details)));
    }

    /** 标记图谱抽取为待处理。 */
    public void markGraphExtractionPending(String chunkId) {
        JSONObject details = new JSONObject();
        details.put("status", "pending");
        details.put("attempt_count", 0);
        chunkMapper.update(
                null,
                new LambdaUpdateWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getChunkId, chunkId)
                        .set(KnowledgeChunk::getGraphExtractionDetails, JSON.toJSONString(details)));
    }

    /** 标记图谱抽取失败（错误信息截断到 4000 字符）。 */
    public void markGraphExtractionFailed(String chunkId, int attemptCount, String error) {
        JSONObject details = new JSONObject();
        details.put("status", "failed");
        details.put("attempt_count", attemptCount);
        details.put("last_error", error == null ? null : error.substring(0, Math.min(error.length(), 4000)));
        details.put("last_attempt_at", DateTimeUtils.utcIsoformat());
        chunkMapper.update(
                null,
                new LambdaUpdateWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getChunkId, chunkId)
                        .set(KnowledgeChunk::getGraphExtractionDetails, JSON.toJSONString(details)));
    }

    /** 标记图谱已索引（可选写入实体 id 与标签）。 */
    public void markGraphIndexed(String chunkId, List<String> entIds, List<String> tags) {
        LambdaUpdateWrapper<KnowledgeChunk> wrapper =
                new LambdaUpdateWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getChunkId, chunkId)
                        .set(KnowledgeChunk::getGraphIndexed, true);
        if (entIds != null) {
            wrapper.set(KnowledgeChunk::getEntIds, JSON.toJSONString(entIds));
        }
        if (tags != null) {
            wrapper.set(KnowledgeChunk::getTags, JSON.toJSONString(tags));
        }
        chunkMapper.update(null, wrapper);
    }

    /** 标记图谱结构已索引。 */
    public void markGraphStructureIndexed(String chunkId, List<String> entIds) {
        chunkMapper.update(
                null,
                new LambdaUpdateWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getChunkId, chunkId)
                        .set(KnowledgeChunk::getGraphStructureIndexed, true)
                        .set(KnowledgeChunk::getEntIds, JSON.toJSONString(entIds)));
    }

    /** 重置单知识库的图谱状态，返回受影响行数。 */
    public int resetGraphStateByKbId(String kbId, boolean clearExtractionResult) {
        LambdaUpdateWrapper<KnowledgeChunk> wrapper =
                new LambdaUpdateWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getKbId, kbId)
                        .set(KnowledgeChunk::getGraphStructureIndexed, false)
                        .set(KnowledgeChunk::getGraphIndexed, false);
        if (clearExtractionResult) {
            JSONObject details = new JSONObject();
            details.put("status", "pending");
            details.put("attempt_count", 0);
            wrapper.set(KnowledgeChunk::getExtractionResult, null)
                    .set(KnowledgeChunk::getGraphExtractionDetails, JSON.toJSONString(details))
                    .set(KnowledgeChunk::getEntIds, null)
                    .set(KnowledgeChunk::getTags, null);
        }
        return chunkMapper.update(null, wrapper);
    }

    /** 按白名单把 Map 写入知识块实体。 */
    private static void applyChunkFields(KnowledgeChunk chunk, Map<String, Object> data) {
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object value = entry.getValue();
            switch (entry.getKey()) {
                case "chunk_id" -> chunk.setChunkId(RepoValues.asString(value));
                case "file_id" -> chunk.setFileId(RepoValues.asString(value));
                case "kb_id" -> chunk.setKbId(RepoValues.asString(value));
                case "chunk_index" -> chunk.setChunkIndex(RepoValues.toInt(value));
                case "content" -> chunk.setContent(RepoValues.asString(value));
                case "start_char_pos" -> chunk.setStartCharPos(RepoValues.toInt(value));
                case "end_char_pos" -> chunk.setEndCharPos(RepoValues.toInt(value));
                case "start_token_pos" -> chunk.setStartTokenPos(RepoValues.toInt(value));
                case "end_token_pos" -> chunk.setEndTokenPos(RepoValues.toInt(value));
                case "graph_indexed" -> chunk.setGraphIndexed(RepoValues.toBoolean(value));
                case "graph_extraction_details" ->
                        chunk.setGraphExtractionDetails(RepoValues.toJsonText(value));
                case "ent_ids" -> chunk.setEntIds(RepoValues.toJsonText(value));
                case "tags" -> chunk.setTags(RepoValues.toJsonText(value));
                case "extraction_result" -> chunk.setExtractionResult(RepoValues.toJsonText(value));
                default -> {
                    // 白名单外：已在上游过滤，不会到达
                }
            }
        }
    }

    /** 便于测试与调用方复用：参考实现的可写字段集合。 */
    public static Set<String> writableFields() {
        return Collections.unmodifiableSet(WRITABLE_FIELDS);
    }

    /** 便于调用方按参考实现的分批规则切分 id 列表。 */
    public static List<List<String>> batchIds(String... ids) {
        return iterBatches(Arrays.asList(ids));
    }
}
