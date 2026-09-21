package com.wisesoft.wenqu.knowledge.graphs;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.wenqu.models.KnowledgeChunk;
import com.wisesoft.wenqu.repositories.KnowledgeChunkRepository;
import com.wisesoft.wenqu.repositories.KnowledgeFileRepository;
import com.wisesoft.wenqu.models.RetrievalHit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图谱检索与排名融合（对应参考实现 {@code implementations/milvus.py::_retrieve_graph_chunks /
 * _build_graph_seed_weights / _fuse_chunk_rankings / _build_chunk_from_record /
 * _build_chunk_from_hit / _hydrate_chunk_sources}）。
 *
 * <p>返回与参考实现完全一致的 chunk 字典形状：
 * <pre>
 * {
 *   "content": str,
 *   "metadata": {"source": str, "chunk_id": str, "file_id": str, "chunk_index": int},
 *   "score": float,
 *   "graph_score": float      // 仅图谱来源携带（_build_chunk_from_record 的 score_field）
 * }
 * </pre>
 * 融合后追加 {@code fusion_score} / {@code fusion_sources}，并把 {@code score} 覆盖为
 * {@code fusion_score}（与 {@code _fuse_chunk_rankings} 一致）。
 *
 * <p>必要替换（仅语言/后端差异，逻辑不变）：
 * <ul>
 *   <li>参考实现的实体/三元组向量检索走 pymilvus 独立集合 {@code MilvusGraphVectorStore}，
 *       本工程后端为 Spring AI {@code VectorStore}，由 {@link GraphVectorStore} 以 kb_id
 *       过滤承载；入参/返回字段/排序语义保持一致。</li>
 *   <li>参考实现的 base 召回来自 Milvus Hit（{@code _build_chunk_from_hit}），本工程 base 召回
 *       来自基础召回的 {@link RetrievalHit}，故
 *       {@link #buildChunkFromHit} 的入参类型随之替换为该 record，字段映射逐项对齐。</li>
 *   <li>图谱检索整体 fail-soft：任一步异常都返回空列表并记日志，不打断主检索
 *       （与参考实现 {@code except Exception: return []} 一致）。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@Service
public class KnowledgeGraphRetrieval {

    /** 来源缺省文案（与参考实现一致）。 */
    private static final String UNKNOWN_SOURCE = "未知来源";

    private final GraphVectorStore graphVectorStore;
    private final MilvusGraphService graphService;
    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeFileRepository fileRepository;

    public KnowledgeGraphRetrieval(
            GraphVectorStore graphVectorStore,
            MilvusGraphService graphService,
            KnowledgeChunkRepository chunkRepository,
            KnowledgeFileRepository fileRepository) {
        this.graphVectorStore = graphVectorStore;
        this.graphService = graphService;
        this.chunkRepository = chunkRepository;
        this.fileRepository = fileRepository;
    }

    /**
     * 按图谱召回 chunk（对应 {@code _retrieve_graph_chunks}）。
     *
     * @param queryText          原始查询
     * @param kbId               知识库
     * @param baseChunks         基础召回结果（用于补 ent_ids 种子）
     * @param queryParams        检索参数（graph_entity_top_k / graph_triple_top_k / graph_top_k /
     *                           graph_max_nodes / ppr_damping）
     * @param embeddingModelSpec 嵌入模型标识；为空时直接返回空（与参考实现一致）
     * @return 图谱召回的 chunk 字典列表；任何异常都返回空列表
     */
    public List<Map<String, Object>> retrieveGraphChunks(
            String queryText,
            String kbId,
            List<Map<String, Object>> baseChunks,
            Map<String, Object> queryParams,
            String embeddingModelSpec) {
        try {
            if (embeddingModelSpec == null || embeddingModelSpec.isBlank()) {
                return List.of();
            }
            Map<String, Object> params = queryParams == null ? Map.of() : queryParams;

            int entityTopK = Math.max(intOf(params.get("graph_entity_top_k"), 10), 1);
            int tripleTopK = Math.max(intOf(params.get("graph_triple_top_k"), 10), 1);
            int graphTopK = Math.max(intOf(params.get("graph_top_k"), 20), 1);
            int graphMaxNodes = Math.max(intOf(params.get("graph_max_nodes"), 10000), 1);

            List<Map<String, Object>> entityHits = graphVectorStore.searchEntities(
                    kbId, queryText, embeddingModelSpec, entityTopK);
            List<Map<String, Object>> tripleHits = graphVectorStore.searchTriples(
                    kbId, queryText, embeddingModelSpec, tripleTopK);

            Map<String, Double> seedWeights = buildGraphSeedWeights(
                    kbId, baseChunks, entityHits, tripleHits);
            if (seedWeights.isEmpty()) {
                return List.of();
            }

            List<MilvusGraphService.RankedChunk> graphScores = graphService.queryAndRankChunksByPpr(
                    kbId, seedWeights, graphMaxNodes, graphTopK,
                    doubleOf(params.get("ppr_damping"), 0.85));
            if (graphScores.isEmpty()) {
                return List.of();
            }

            List<String> chunkIds = new ArrayList<>();
            Map<String, Double> scoreByChunkId = new LinkedHashMap<>();
            for (MilvusGraphService.RankedChunk ranked : graphScores) {
                chunkIds.add(ranked.chunkId());
                scoreByChunkId.put(ranked.chunkId(), ranked.score());
            }

            List<Map<String, Object>> out = new ArrayList<>();
            for (KnowledgeChunk chunk : chunkRepository.listByChunkIds(chunkIds)) {
                Double score = scoreByChunkId.get(chunk.getChunkId());
                if (score == null) {
                    continue;
                }
                out.add(buildChunkFromRecord(chunk, score, "graph_score"));
            }
            return out;
        } catch (Exception exc) {
            log.error("Graph retrieval failed for {}: {}", kbId, exc.getMessage());
            return List.of();
        }
    }

    /**
     * 构造 PPR 种子权重（对应 {@code _build_graph_seed_weights}）。
     *
     * <p>权重：实体命中 1.0；三元组 source/target 各 0.8（按分数累加）；命中 chunk 的 ent_ids 0.3
     * （权重为该 chunk 的召回分）；最后按总和归一化（总和 &le; 0 时返回空）。
     */
    public Map<String, Double> buildGraphSeedWeights(
            String kbId,
            List<Map<String, Object>> baseChunks,
            List<Map<String, Object>> entityHits,
            List<Map<String, Object>> tripleHits) {
        Map<String, Double> seedWeights = new LinkedHashMap<>();

        for (Map<String, Object> hit : safe(entityHits)) {
            addSeed(seedWeights, str(hit.get("id")), doubleOf(hit.get("score"), 0.0), 1.0);
        }
        for (Map<String, Object> hit : safe(tripleHits)) {
            double score = doubleOf(hit.get("score"), 0.0);
            addSeed(seedWeights, str(hit.get("source_id")), score, 0.8);
            addSeed(seedWeights, str(hit.get("target_id")), score, 0.8);
        }

        Map<String, Double> chunkScores = new LinkedHashMap<>();
        for (Map<String, Object> chunk : safe(baseChunks)) {
            String chunkId = metadataChunkId(chunk);
            if (chunkId != null && !chunkId.isBlank()) {
                chunkScores.put(chunkId, doubleOf(chunk.get("score"), 0.0));
            }
        }
        if (!chunkScores.isEmpty()) {
            for (KnowledgeChunk chunk : chunkRepository.listByChunkIds(new ArrayList<>(chunkScores.keySet()))) {
                double chunkScore = chunkScores.getOrDefault(chunk.getChunkId(), 0.0);
                for (String entityId : parseEntIds(chunk.getEntIds())) {
                    addSeed(seedWeights, entityId, chunkScore, 0.3);
                }
            }
        }

        double total = 0.0;
        for (double weight : seedWeights.values()) {
            total += weight;
        }
        if (total <= 0) {
            return Map.of();
        }
        Map<String, Double> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : seedWeights.entrySet()) {
            normalized.put(entry.getKey(), entry.getValue() / total);
        }
        return normalized;
    }

    /**
     * 把 chunk 记录转成知识库统一返回结构（对应 {@code _build_chunk_from_record}）。
     *
     * @param chunk      chunk 记录
     * @param score      分数
     * @param scoreField 额外写回的分数键（如 {@code graph_score}），可为空
     */
    public static Map<String, Object> buildChunkFromRecord(KnowledgeChunk chunk, double score, String scoreField) {
        Map<String, Object> metadata = baseMetadata(chunk.getChunkId(), chunk.getFileId(), chunk.getChunkIndex());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", chunk.getContent());
        result.put("metadata", metadata);
        result.put("score", score);
        if (scoreField != null && !scoreField.isBlank()) {
            result.put(scoreField, score);
        }
        return result;
    }

    /**
     * 把基础召回结果转成知识库统一返回结构（对应 {@code _build_chunk_from_hit}）。
     *
     * <p>必要替换：参考实现入参是 Milvus {@code hit}（取 {@code hit.entity.file_id /
     * chunk_id / chunk_index / content}），本工程基础召回是 {@link RetrievalHit}，
     * 字段逐一对应。
     *
     * @param hit       基础召回命中
     * @param score     分数
     * @param scoreField 额外写回的分数键（如 {@code bm25_score} / {@code hybrid_score}），可为空
     */
    public static Map<String, Object> buildChunkFromHit(
            RetrievalHit hit, double score, String scoreField) {
        Map<String, Object> metadata = baseMetadata(hit.knowledgeId(), hit.docId(), hit.chunkIndex());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", hit.content() == null ? "" : hit.content());
        result.put("metadata", metadata);
        result.put("score", score);
        if (scoreField != null && !scoreField.isBlank()) {
            result.put(scoreField, score);
        }
        return result;
    }

    /**
     * 回填 chunk 来源文件名（对应 {@code _hydrate_chunk_sources}）。
     *
     * <p>按 chunk 的 file_id 批量取文件名写入 {@code metadata.source}；取不到时保持
     * {@code 未知来源}（与参考实现一致：{@code or "未知来源"}）。
     */
    public void hydrateChunkSources(String kbId, List<Map<String, Object>> chunks) {
        List<String> fileIds = new ArrayList<>();
        for (Map<String, Object> chunk : safe(chunks)) {
            String fileId = metadataFileId(chunk);
            if (fileId != null && !fileId.isEmpty() && !fileIds.contains(fileId)) {
                fileIds.add(fileId);
            }
        }
        if (fileIds.isEmpty()) {
            return;
        }
        fileIds.sort(String::compareTo);

        Map<String, String> filenames = fileRepository.getFilenamesByFileIds(kbId, fileIds);
        for (Map<String, Object> chunk : safe(chunks)) {
            Object rawMetadata = chunk.get("metadata");
            if (!(rawMetadata instanceof Map<?, ?>)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) rawMetadata;
            String fileId = str(metadata.get("file_id"));
            String name = fileId == null ? null : filenames.get(fileId);
            metadata.put("source", (name == null || name.isEmpty()) ? UNKNOWN_SOURCE : name);
        }
    }

    /**
     * RRF 排名融合（对应 {@code _fuse_chunk_rankings}，k=60）。
     *
     * <p>基础召回权重恒为 1.0，图谱召回权重取 {@code max(graph_weight, 0.0)}；
     * 同一 chunk 双命中则分量叠加，{@code score} 覆盖为 {@code fusion_score}，
     * {@code fusion_sources} 追加来源（不去重，与参考实现一致）。
     */
    public static List<Map<String, Object>> fuseChunkRankings(
            List<Map<String, Object>> baseChunks,
            List<Map<String, Object>> graphChunks,
            double graphWeight) {
        final double rrfK = 60.0;
        Map<String, Map<String, Object>> fused = new LinkedHashMap<>();

        int rank = 0;
        for (Map<String, Object> chunk : safe(baseChunks)) {
            rank += 1;
            mergeChunk(fused, chunk, rank, 1.0, "chunk", rrfK);
        }
        rank = 0;
        for (Map<String, Object> chunk : safe(graphChunks)) {
            rank += 1;
            mergeChunk(fused, chunk, rank, Math.max(graphWeight, 0.0), "graph", rrfK);
        }

        List<Map<String, Object>> result = new ArrayList<>(fused.values());
        result.sort((left, right) -> Double.compare(
                doubleOf(right.get("fusion_score"), 0.0), doubleOf(left.get("fusion_score"), 0.0)));
        return result;
    }

    /** 单条融合（对应 {@code merge_chunk}）。 */
    private static void mergeChunk(
            Map<String, Map<String, Object>> fused,
            Map<String, Object> chunk,
            int rank,
            double weight,
            String source,
            double rrfK) {
        String chunkId = metadataChunkId(chunk);
        if (chunkId == null || chunkId.isBlank()) {
            return;
        }
        double score = weight / (rrfK + rank);
        Map<String, Object> existing = fused.get(chunkId);
        if (existing == null) {
            existing = new LinkedHashMap<>(chunk);
            existing.put("fusion_score", 0.0);
            existing.put("fusion_sources", new ArrayList<String>());
            fused.put(chunkId, existing);
        }
        existing.put("fusion_score", doubleOf(existing.get("fusion_score"), 0.0) + score);
        existing.put("score", existing.get("fusion_score"));
        Object sources = existing.get("fusion_sources");
        if (sources instanceof List<?> rawSources) {
            @SuppressWarnings("unchecked")
            List<String> mutable = (List<String>) rawSources;
            mutable.add(source);
        }
        if ("graph".equals(source) && chunk.containsKey("graph_score")) {
            existing.put("graph_score", chunk.get("graph_score"));
        }
    }

    /** chunk 的 base metadata（与参考实现键序一致）。 */
    private static Map<String, Object> baseMetadata(String chunkId, String fileId, Integer chunkIndex) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", UNKNOWN_SOURCE);
        metadata.put("chunk_id", chunkId);
        metadata.put("file_id", fileId);
        metadata.put("chunk_index", chunkIndex);
        return metadata;
    }

    /** 取 chunk 字典的 metadata.chunk_id。 */
    @SuppressWarnings("unchecked")
    private static String metadataChunkId(Map<String, Object> chunk) {
        if (chunk == null || !(chunk.get("metadata") instanceof Map<?, ?> raw)) {
            return null;
        }
        return str(((Map<String, Object>) raw).get("chunk_id"));
    }

    /** 取 chunk 字典的 metadata.file_id。 */
    @SuppressWarnings("unchecked")
    private static String metadataFileId(Map<String, Object> chunk) {
        if (chunk == null || !(chunk.get("metadata") instanceof Map<?, ?> raw)) {
            return null;
        }
        return str(((Map<String, Object>) raw).get("file_id"));
    }

    /** 解析 ent_ids 列（JSON 字符串数组）。 */
    static List<String> parseEntIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = JSON.parseArray(raw, String.class);
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static void addSeed(Map<String, Double> seeds, String entityId, double score, double weight) {
        if (entityId == null || entityId.isBlank()) {
            return;
        }
        seeds.merge(entityId, Math.max(score, 0.0) * weight, Double::sum);
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double doubleOf(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
