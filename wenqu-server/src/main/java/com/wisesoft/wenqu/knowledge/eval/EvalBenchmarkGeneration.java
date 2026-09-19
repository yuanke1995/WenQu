package com.wisesoft.wenqu.knowledge.eval;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.wenqu.common.LooseJson;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseManager;
import com.wisesoft.wenqu.knowledge.graphs.MilvusGraphService;
import com.wisesoft.wenqu.models.KnowledgeChunk;
import com.wisesoft.wenqu.repositories.KnowledgeChunkRepository;
import com.wisesoft.wenqu.service.ModelSelectors;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 评估基准数据集生成，对齐参考实现 {@code knowledge/eval/benchmark_generation.py}。
 *
 * <p>职责：从知识库 chunk 中抽取锚点片段，按「向量近邻」或「图谱增强」两种模式凑出上下文，
 * 再让 LLM 依据上下文产出 {@code {query, gold_chunk_ids, gold_answer}}，并按原始尝试序号保序产出。
 *
 * <p><b>必要替换（并发模型差异，显式标注）</b>：
 * <ul>
 *   <li>参考实现用 asyncio 任务 + Queue + {@code _WORKER_DONE} 哨兵实现"并发生成、保序产出"；
 *       本工程改用固定线程池 + 按尝试序号顺序 {@code Future.get()}，同样并发执行、按序号保序产出，
 *       且早停语义一致（生成满 count 即停止消费，剩余任务被 shutdownNow 中断）。</li>
 *   <li>进度回调在主消费线程中触发（参考实现在 worker 协程内 await）：回调会写库，
 *       收敛到单线程可避免并发写；「每生成一条报一次进度」的语义不变。</li>
 *   <li>{@code json_repair.loads} → {@link LooseJson#parseObject}（能力差异见 LooseJson 类注释）。</li>
 * </ul>
 */
@Component
public class EvalBenchmarkGeneration {

    private static final Logger log = LoggerFactory.getLogger(EvalBenchmarkGeneration.class);

    /** 默认并发数。 */
    public static final int DEFAULT_BENCHMARK_GENERATION_CONCURRENCY = 10;

    /** 并发数上限。 */
    public static final int MAX_BENCHMARK_GENERATION_CONCURRENCY = 20;

    /** 图谱扩展默认 top_k。 */
    public static final int DEFAULT_GRAPH_EXPAND_TOP_K = 1;

    /** 图谱扩展 top_k 上限。 */
    public static final int MAX_GRAPH_EXPAND_TOP_K = 3;

    /** 图谱种子衰减系数。 */
    private static final double GRAPH_SEED_DECAY = 0.9;

    /** PPR 阻尼系数。 */
    private static final double GRAPH_PPR_DAMPING = 0.85;

    /** PPR 最大节点数。 */
    private static final int GRAPH_PPR_MAX_NODES = 10000;

    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeBaseManager kbManager;
    private final MilvusGraphService graphService;
    private final ModelSelectors modelSelectors;

    public EvalBenchmarkGeneration(
            KnowledgeChunkRepository chunkRepository,
            KnowledgeBaseManager kbManager,
            MilvusGraphService graphService,
            ModelSelectors modelSelectors) {
        this.chunkRepository = chunkRepository;
        this.kbManager = kbManager;
        this.graphService = graphService;
        this.modelSelectors = modelSelectors;
    }

    /** 逐条产出回调（对应参考实现的 {@code yield}）。 */
    @FunctionalInterface
    public interface ItemConsumer {
        void accept(Map<String, Object> item);
    }

    /** 进度回调（对应参考实现的 {@code progress_cb}）。 */
    @FunctionalInterface
    public interface ProgressCallback {
        void accept(int progress, String message);
    }

    /** 取消检查回调（对应参考实现的 {@code cancel_cb}，取消时抛异常中断）。 */
    @FunctionalInterface
    public interface CancelCallback {
        void check();
    }

    /** 收集知识库全部 chunk（对应 {@code collect_kb_chunks}）。 */
    public List<Map<String, Object>> collectKbChunks(String kbId) {
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (KnowledgeChunk chunk : chunkRepository.listByKbId(kbId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", chunk.getChunkId());
            item.put("content", chunk.getContent() == null ? "" : chunk.getContent());
            item.put("file_id", chunk.getFileId());
            item.put("chunk_index", chunk.getChunkIndex());
            item.put("graph_indexed", Boolean.TRUE.equals(chunk.getGraphIndexed()));
            item.put("ent_ids", parseStringList(chunk.getEntIds()));
            item.put("tags", parseStringList(chunk.getTags()));
            item.put("extraction_result", chunk.getExtractionResult());
            chunks.add(item);
        }
        return chunks;
    }

    /** 邻居数量夹取（对应 {@code clamp_neighbors_count}）。 */
    public static int clampNeighborsCount(int neighborsCount) {
        return Math.min(Math.max(neighborsCount, 0), 10);
    }

    /** 并发数归一化（对应 {@code normalize_generation_concurrency_count}）。 */
    public static int normalizeGenerationConcurrencyCount(Object value) {
        if (value == null || "".equals(value)) {
            return DEFAULT_BENCHMARK_GENERATION_CONCURRENCY;
        }
        return Math.min(
                Math.max(1, toInt(value, DEFAULT_BENCHMARK_GENERATION_CONCURRENCY)),
                MAX_BENCHMARK_GENERATION_CONCURRENCY);
    }

    /** 图扩展 top_k 归一化（对应 {@code normalize_graph_expand_top_k}）。 */
    public static int normalizeGraphExpandTopK(Object value) {
        if (value == null || "".equals(value)) {
            return DEFAULT_GRAPH_EXPAND_TOP_K;
        }
        return Math.min(Math.max(1, toInt(value, DEFAULT_GRAPH_EXPAND_TOP_K)), MAX_GRAPH_EXPAND_TOP_K);
    }

    /** 取片段实体 ID（对应 {@code _chunk_entity_ids}）。 */
    private static List<String> chunkEntityIds(Map<String, Object> chunk) {
        List<String> ids = new ArrayList<>();
        Object raw = chunk.get("ent_ids");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isEmpty()) {
                    ids.add(String.valueOf(item));
                }
            }
        }
        return ids;
    }

    /** 判断候选片段是否为锚点自身（对应 {@code _is_anchor_chunk}）。 */
    @SuppressWarnings("unchecked")
    private static boolean isAnchorChunk(Map<String, Object> candidate, Map<String, Object> anchorChunk) {
        Map<String, Object> metadata =
                candidate.get("metadata") instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
        Object candidateId = metadata.get("chunk_id");
        if (candidateId != null && String.valueOf(candidateId).equals(String.valueOf(anchorChunk.get("id")))) {
            return true;
        }
        Object candidateFileId = metadata.get("file_id");
        Object candidateChunkIndex = metadata.get("chunk_index");
        return equalsNullable(candidateFileId, anchorChunk.get("file_id"))
                && equalsNullable(candidateChunkIndex, anchorChunk.get("chunk_index"));
    }

    /** 按向量近邻选取上下文片段（对应 {@code select_neighbor_chunks_by_kb_query}）。 */
    public List<Map<String, Object>> selectNeighborChunksByKbQuery(
            String kbId, Map<String, Object> anchorChunk, int neighborsCount) {
        if (neighborsCount <= 0) {
            return List.of();
        }
        Object anchorContentValue = anchorChunk.get("content");
        String anchorContent = anchorContentValue == null ? "" : String.valueOf(anchorContentValue);
        if (anchorContent.isEmpty()) {
            return List.of();
        }

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("search_mode", "vector");
        options.put("final_top_k", neighborsCount + 3);
        options.put("use_reranker", false);
        options.put("similarity_threshold", 0.0);
        List<Map<String, Object>> candidates = kbManager.aquery(anchorContent, kbId, options);

        List<Map<String, Object>> chunks = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            if (isAnchorChunk(candidate, anchorChunk)) {
                continue;
            }
            Map<String, Object> metadata =
                    candidate.get("metadata") instanceof Map<?, ?> raw ? castMap(raw) : Map.of();
            Object chunkId = metadata.get("chunk_id");
            Object contentValue = candidate.get("content");
            String content = contentValue == null ? "" : String.valueOf(contentValue);
            if (chunkId == null || content.isEmpty()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", String.valueOf(chunkId));
            item.put("content", content);
            item.put("file_id", metadata.get("file_id"));
            item.put("chunk_index", metadata.get("chunk_index"));
            chunks.add(item);
            if (chunks.size() >= neighborsCount) {
                break;
            }
        }
        return chunks;
    }

    /**
     * 按图谱扩展选取上下文片段（对应 {@code select_graph_enhanced_chunks}）。
     *
     * <p>返回 {@code null} 表示图谱路径不可用（无实体 / PPR 无结果 / 无新片段），
     * 由调用方按参考实现直接丢弃该次尝试。
     */
    public List<Map<String, Object>> selectGraphEnhancedChunks(
            String kbId,
            Map<String, Object> anchorChunk,
            Map<String, Map<String, Object>> chunksById,
            int contextCount,
            int graphExpandTopK) {
        if (contextCount <= 1) {
            List<Map<String, Object>> single = new ArrayList<>();
            single.add(anchorChunk);
            return single;
        }

        List<String> anchorEntityIds = chunkEntityIds(anchorChunk);
        if (anchorEntityIds.isEmpty()) {
            return null;
        }

        List<Map<String, Object>> selected = new ArrayList<>();
        selected.add(anchorChunk);
        Set<String> selectedIds = new LinkedHashSet<>();
        selectedIds.add(String.valueOf(anchorChunk.get("id")));

        Map<String, Double> seedWeights = new LinkedHashMap<>();
        for (String entityId : anchorEntityIds) {
            seedWeights.put(entityId, 1.0);
        }
        int roundIndex = 1;

        while (selected.size() < contextCount) {
            for (String entityId : anchorEntityIds) {
                seedWeights.put(entityId, 1.0);
            }

            List<MilvusGraphService.RankedChunk> rankedChunks = graphService.queryAndRankChunksByPpr(
                    kbId,
                    seedWeights,
                    GRAPH_PPR_MAX_NODES,
                    Math.max(contextCount * 5, 20),
                    GRAPH_PPR_DAMPING);
            if (rankedChunks == null || rankedChunks.isEmpty()) {
                return null;
            }

            List<Map<String, Object>> newChunks = new ArrayList<>();
            int takeLimit = Math.min(graphExpandTopK, contextCount - selected.size());
            for (MilvusGraphService.RankedChunk ranked : rankedChunks) {
                String chunkId = String.valueOf(ranked.chunkId());
                if (selectedIds.contains(chunkId)) {
                    continue;
                }
                Map<String, Object> chunk = chunksById.get(chunkId);
                if (chunk == null) {
                    continue;
                }
                newChunks.add(chunk);
                if (newChunks.size() >= takeLimit) {
                    break;
                }
            }

            if (newChunks.isEmpty()) {
                return null;
            }

            double newWeight = Math.pow(GRAPH_SEED_DECAY, roundIndex);
            for (Map<String, Object> chunk : newChunks) {
                selected.add(chunk);
                selectedIds.add(String.valueOf(chunk.get("id")));
                for (String entityId : chunkEntityIds(chunk)) {
                    seedWeights.merge(entityId, newWeight, Math::max);
                }
            }
            roundIndex += 1;
        }

        return selected;
    }

    /** 构造生成提示词（对应 {@code build_benchmark_generation_prompt}）。 */
    public static String buildBenchmarkGenerationPrompt(List<Map.Entry<String, String>> ctxItems) {
        StringBuilder contextText = new StringBuilder();
        for (int index = 0; index < ctxItems.size(); index++) {
            if (index > 0) {
                contextText.append("\n\n");
            }
            contextText.append("片段ID=").append(ctxItems.get(index).getKey())
                    .append("\n").append(ctxItems.get(index).getValue());
        }
        return "你将基于以下上下文生成一个可由上下文准确回答的问题与标准答案。"
                + "仅返回一个JSON对象，不要包含其他文字。"
                + "键为 query、gold_answer、gold_chunk_ids。gold_chunk_ids 必须是上述上下文片段的ID子集。\n\n"
                + "上下文：\n" + contextText + "\n";
    }

    /**
     * 生成单条基准样本（对应 {@code _generate_benchmark_item_once}）。
     *
     * <p>返回 {@code null} 表示本次尝试失败（LLM 输出不合规 / 调用异常），由调用方继续下一次尝试。
     */
    public Map<String, Object> generateBenchmarkItemOnce(
            String kbId,
            List<Map<String, Object>> allChunks,
            ModelSelectors.ChatAdapter llm,
            int contextCount,
            String generationMode,
            int graphExpandTopK,
            Map<String, Map<String, Object>> chunksById) {
        List<Map<String, Object>> ctxChunks;
        if ("graph_enhanced".equals(generationMode)) {
            List<Map<String, Object>> graphAnchorChunks = new ArrayList<>();
            for (Map<String, Object> chunk : allChunks) {
                if (Boolean.TRUE.equals(chunk.get("graph_indexed")) && !chunkEntityIds(chunk).isEmpty()) {
                    graphAnchorChunks.add(chunk);
                }
            }
            if (graphAnchorChunks.isEmpty()) {
                throw new IllegalArgumentException(
                        "No graph indexed chunks with entities found in knowledge base");
            }
            Map<String, Object> anchorChunk =
                    graphAnchorChunks.get(ThreadLocalRandom.current().nextInt(graphAnchorChunks.size()));
            ctxChunks = selectGraphEnhancedChunks(
                    kbId, anchorChunk, chunksById, contextCount, graphExpandTopK);
            if (ctxChunks == null) {
                return null;
            }
        } else {
            Map<String, Object> anchorChunk =
                    allChunks.get(ThreadLocalRandom.current().nextInt(allChunks.size()));
            List<Map<String, Object>> neighborChunks = selectNeighborChunksByKbQuery(
                    kbId, anchorChunk, contextCount - 1);
            ctxChunks = new ArrayList<>();
            ctxChunks.add(anchorChunk);
            ctxChunks.addAll(neighborChunks);
        }

        List<Map.Entry<String, String>> ctxItems = new ArrayList<>();
        Set<String> allowedIds = new LinkedHashSet<>();
        for (Map<String, Object> chunk : ctxChunks) {
            String chunkId = String.valueOf(chunk.get("id"));
            Object contentValue = chunk.get("content");
            ctxItems.add(Map.entry(chunkId, contentValue == null ? "" : String.valueOf(contentValue)));
            allowedIds.add(chunkId);
        }

        try {
            ModelSelectors.GeneralResponse response = llm.call(buildBenchmarkGenerationPrompt(ctxItems), false);
            String content = response == null || response.content == null ? "" : response.content;
            Map<String, Object> obj = LooseJson.parseObject(content);
            Object query = obj.get("query");
            Object answer = obj.get("gold_answer");
            Object goldIds = obj.get("gold_chunk_ids");
            if (isBlank(query) || isBlank(answer) || !(goldIds instanceof List<?>)) {
                log.warn("Generated JSON missing fields or invalid format: {}", obj);
                return null;
            }

            List<String> filtered = new ArrayList<>();
            for (Object raw : (List<?>) goldIds) {
                String text = String.valueOf(raw);
                if (allowedIds.contains(text)) {
                    filtered.add(text);
                }
            }
            if (filtered.isEmpty()) {
                log.warn("Generated gold_chunk_ids not found in allowed context");
                return null;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("query", String.valueOf(query));
            item.put("gold_chunk_ids", filtered);
            item.put("gold_answer", String.valueOf(answer));
            return item;
        } catch (Exception exc) {
            log.warn("Benchmark generation failed for one item: {}", exc.getMessage());
            return null;
        }
    }

    /**
     * 迭代生成基准样本（对应 {@code iter_generated_benchmark_items}）。
     *
     * <p>保序：按尝试序号升序回调解出的样本（与参考实现的 reorder 缓冲一致）。
     */
    public void iterGeneratedBenchmarkItems(
            String kbId,
            int count,
            int neighborsCount,
            String llmModelSpec,
            int concurrencyCount,
            String generationMode,
            int graphExpandTopK,
            int progressBase,
            Integer totalProgress,
            ProgressCallback progressCb,
            CancelCallback cancelCb,
            ItemConsumer onItem) {
        if (progressCb != null) {
            progressCb.accept(5, "加载chunks");
        }

        List<Map<String, Object>> allChunks = collectKbChunks(kbId);
        if (allChunks.isEmpty()) {
            throw new IllegalArgumentException("No chunks found in knowledge base");
        }
        Map<String, Map<String, Object>> chunksById = new LinkedHashMap<>();
        for (Map<String, Object> chunk : allChunks) {
            if (chunk.get("id") != null) {
                chunksById.put(String.valueOf(chunk.get("id")), chunk);
            }
        }

        if (!"vector".equals(generationMode) && !"graph_enhanced".equals(generationMode)) {
            throw new IllegalArgumentException("Unsupported benchmark generation mode");
        }
        int expandTopK = normalizeGraphExpandTopK(graphExpandTopK);

        if (progressCb != null) {
            progressCb.accept(15, "准备生成样本");
        }

        if (llmModelSpec == null || llmModelSpec.isEmpty()) {
            throw new IllegalArgumentException("llm_model_spec 不能为空");
        }

        ModelSelectors.ChatAdapter llm = modelSelectors.selectModel(llmModelSpec);
        int contextCount = Math.max(clampNeighborsCount(neighborsCount), 1);
        int maxAttempts = Math.max(count * 5, 50);
        int workerCount = normalizeGenerationConcurrencyCount(concurrencyCount);
        int actualWorkerCount = Math.min(Math.min(workerCount, Math.max(count, 1)), maxAttempts);
        int totalValue = totalProgress == null ? (progressBase + count) : totalProgress;

        // 待消费的尝试：按序号保序，window 上限控制"提前提交量"，早停时不浪费过多算力
        int windowSize = Math.max(actualWorkerCount * 2, 1);
        Map<Integer, Future<Map<String, Object>>> pending = new LinkedHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(actualWorkerCount, 1), runnable -> {
            Thread thread = new Thread(runnable, "eval-benchmark-gen");
            thread.setDaemon(true);
            return thread;
        });

        int nextSubmit = 0;
        int nextConsume = 0;
        int generated = 0;
        try {
            while (nextSubmit < maxAttempts && pending.size() < windowSize) {
                submitAttempt(pool, pending, nextSubmit, kbId, allChunks, llm,
                        contextCount, generationMode, expandTopK, chunksById);
                nextSubmit += 1;
            }

            while (!pending.isEmpty() && generated < count) {
                if (cancelCb != null) {
                    cancelCb.check();
                }
                int key = nextConsume;
                Future<Map<String, Object>> future = pending.remove(key);
                nextConsume += 1;

                Map<String, Object> item;
                try {
                    item = future.get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Benchmark generation was interrupted", interrupted);
                } catch (java.util.concurrent.ExecutionException executionException) {
                    Throwable cause = executionException.getCause();
                    if (cause instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new IllegalStateException(cause);
                }

                if (nextSubmit < maxAttempts && generated < count) {
                    submitAttempt(pool, pending, nextSubmit, kbId, allChunks, llm,
                            contextCount, generationMode, expandTopK, chunksById);
                    nextSubmit += 1;
                }

                if (item == null) {
                    continue;
                }
                generated += 1;
                onItem.accept(item);
                if (progressCb != null) {
                    int progress = (int) (99.0 * (progressBase + generated) / Math.max(totalValue, 1));
                    progressCb.accept(progress, "已生成 " + (progressBase + generated) + "/" + totalValue);
                }
            }
        } finally {
            pool.shutdownNow();
            try {
                pool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 序列化单条样本（对应 {@code dump_benchmark_item}：JSON + 换行，键间无空格）。 */
    public static String dumpBenchmarkItem(Map<String, Object> item) {
        return JSON.toJSONString(item) + "\n";
    }

    private void submitAttempt(
            ExecutorService pool,
            Map<Integer, Future<Map<String, Object>>> pending,
            int attemptNo,
            String kbId,
            List<Map<String, Object>> allChunks,
            ModelSelectors.ChatAdapter llm,
            int contextCount,
            String generationMode,
            int graphExpandTopK,
            Map<String, Map<String, Object>> chunksById) {
        pending.put(
                attemptNo,
                pool.submit(() -> generateBenchmarkItemOnce(
                        kbId, allChunks, llm, contextCount, generationMode, graphExpandTopK, chunksById)));
    }

    // ==================== 工具 ====================

    private static boolean isBlank(Object value) {
        return value == null || String.valueOf(value).isEmpty();
    }

    private static boolean equalsNullable(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    /** JSON 数组列 → 字符串列表（列值非法时返回空列表，与参考实现的 {@code or []} 一致）。 */
    private static List<String> parseStringList(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<String> values = JSON.parseArray(json, String.class);
            return values == null ? new ArrayList<>() : values;
        } catch (Exception exception) {
            return new ArrayList<>();
        }
    }

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return (int) number.doubleValue();
        }
        try {
            return (int) Double.parseDouble(String.valueOf(value).strip());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
