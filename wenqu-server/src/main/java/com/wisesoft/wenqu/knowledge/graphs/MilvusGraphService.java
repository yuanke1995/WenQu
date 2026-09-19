package com.wisesoft.wenqu.knowledge.graphs;

import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.knowledge.graphs.extractors.GraphExtractor;
import com.wisesoft.wenqu.knowledge.graphs.extractors.GraphExtractorFactory;
import com.wisesoft.wenqu.models.KnowledgeBase;
import com.wisesoft.wenqu.models.KnowledgeChunk;
import com.wisesoft.wenqu.repositories.KnowledgeBaseRepository;
import com.wisesoft.wenqu.repositories.KnowledgeChunkRepository;
import com.wisesoft.wenqu.repositories.KnowledgeGraphRepository;
import com.wisesoft.wenqu.repositories.RepoValues;
import com.wisesoft.wenqu.service.TaskService;
import com.wisesoft.wenqu.storage.neo4j.Neo4jConnectionManager;

import com.alibaba.fastjson2.JSON;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Milvus 知识库的独立图谱构建服务。
 *
 * <p>由参考实现的 {@code knowledge/graphs/milvus_graph_service.py} 逐项翻译：
 * 状态查询、配置锁定、失败样本、待处理 chunk 的并行抽取 / 串行写入 / 向量化、
 * 重置与对账、Neo4j 子图查询、PPR 排序、标签与统计。
 *
 * <h3>能力替换（如实标注，非逻辑改动）</h3>
 * <ul>
 *   <li>并发模型：参考用 asyncio（抽取 worker 池 + 写入队列 + 向量 worker + 进度上报协程），
 *       本工程任务处理器运行在阻塞线程上，改用线程池 + 阻塞队列，<b>并发度、队列容量、
 *       分批大小、重试次数与退避时长保持与参考一致</b>。</li>
 *   <li>Neo4j 查询卸载：参考在事件循环上做 {@code asyncio.to_thread} + 信号量限流
 *       （{@code NEO4J_QUERY_OFFLOAD_LIMIT=8}）；Java 侧本就在独立线程中执行阻塞 IO，
 *       无需卸载与限流，故省略该机制（语义等价）。</li>
 *   <li>PageRank：参考用 {@code networkx.pagerank}（无向图、dangling 用 personalization 分发、
 *       max_iter=100、tol=1e-6）；Java 无该依赖，改为等价的幂迭代实现，参数与收敛判据一致。</li>
 *   <li>向量后端：参考是 Milvus 集合，本工程由 {@link GraphVectorStore}（Spring AI VectorStore）
 *       承载。</li>
 * </ul>
 */
@Service
public class MilvusGraphService {

    private static final Logger log = LoggerFactory.getLogger(MilvusGraphService.class);

    public static final String GRAPH_CONFIG_KEY = "graph_build_config";
    public static final String GRAPH_TASK_TYPE = "knowledge_graph_index";
    /** 数据库游标每次预取的 chunk 数量边界，只控制查询频率和单页内存，不限制抽取并发。 */
    public static final int GRAPH_BUILD_FETCH_MIN_SIZE = 100;
    public static final int GRAPH_BUILD_FETCH_MAX_SIZE = 1000;
    /** 构建任务 INFO 汇总日志与前端进度更新间隔。 */
    public static final double GRAPH_BUILD_LOG_INTERVAL_SECONDS = 5.0;
    public static final int GRAPH_VECTOR_BATCH_SIZE = 100;
    public static final int GRAPH_VECTOR_LEASE_SECONDS = 300;
    public static final double GRAPH_VECTOR_FLUSH_INTERVAL_SECONDS = 0.2;
    public static final int GRAPH_EXTRACTION_MAX_ATTEMPTS = 3;
    public static final double[] GRAPH_EXTRACTION_RETRY_DELAYS_SECONDS = {2.0, 10.0};
    /** 队列哨兵：Java 的阻塞队列不接受 null，故用显式哨兵表达"结束"。 */
    private static final KnowledgeChunk POISON_CHUNK = new KnowledgeChunk();
    private static final String POISON_CHUNK_ID = "\u0000__graph_poison__";

    private final KnowledgeBaseRepository kbRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeGraphRepository graphRepository;
    private final GraphVectorStore graphVectorStore;
    private final String boundKbId;

    private volatile Neo4jConnectionManager connection;

    /**
     * 容器装配用构造器（四参）。
     *
     * <p>参考实现是单个构造器带默认值（{@code kb_id=None}）；Java 无默认参数，故拆成四参 / 五参
     * 两个重载。重载并存会让 Spring 无法自行判断注入哪一个（表现为启动期
     * {@code No default constructor found}），因此这里显式标注 {@link Autowired} 指定容器用四参版本，
     * 五参版本仅供需要绑定 kb_id 的调用方显式 new 使用。
     */
    @Autowired
    public MilvusGraphService(
            KnowledgeBaseRepository kbRepository,
            KnowledgeChunkRepository chunkRepository,
            KnowledgeGraphRepository graphRepository,
            GraphVectorStore graphVectorStore) {
        this(kbRepository, chunkRepository, graphRepository, graphVectorStore, null);
    }

    /**
     * 允许绑定 kb_id 的构造形式（对应参考实现的 {@code kb_id=...} 构造参数），
     * 供需要免传 kb 的调用方（如 {@code query_nodes}/{@code get_labels}）使用。
     */
    public MilvusGraphService(
            KnowledgeBaseRepository kbRepository,
            KnowledgeChunkRepository chunkRepository,
            KnowledgeGraphRepository graphRepository,
            GraphVectorStore graphVectorStore,
            String boundKbId) {
        this.kbRepository = kbRepository;
        this.chunkRepository = chunkRepository;
        this.graphRepository = graphRepository;
        this.graphVectorStore = graphVectorStore;
        this.boundKbId = boundKbId;
    }

    // ==================== 连接 ====================

    /** 共享 Neo4j 连接（懒建，与参考实现的 property 懒加载一致）。 */
    public Neo4jConnectionManager connection() {
        Neo4jConnectionManager current = this.connection;
        if (current == null) {
            synchronized (this) {
                if (this.connection == null) {
                    this.connection = Neo4jConnectionManager.getSharedNeo4jConnection();
                }
                current = this.connection;
            }
        }
        return current;
    }

    public Driver driver() {
        return connection().getDriver();
    }

    // ==================== 状态 ====================

    /** 图谱构建状态（对应 {@code get_status}）。{@code tasker} 可为 null。 */
    public Map<String, Object> getStatus(String kbId, TaskService tasker) {
        KnowledgeBase kb = getMilvusKb(kbId);
        Map<String, Object> params = parseObject(kb.getAdditionalParams());
        Map<String, Object> config = asMap(params.get(GRAPH_CONFIG_KEY));

        int totalChunks = chunkRepository.countByKbId(kbId);
        int pendingChunks = chunkRepository.countGraphPendingByKbId(kbId);
        int indexedChunks = chunkRepository.countGraphIndexedByKbId(kbId);
        int structuredChunks = chunkRepository.countGraphStructureIndexedByKbId(kbId);
        Map<String, Integer> extractionCounts = chunkRepository.countGraphExtractionStatusesByKbId(kbId);
        long[] graphCounts = graphRepository.countByKbId(kbId);
        Map<String, Integer> vectorCounts = graphRepository.countVectorStatusesByKbId(kbId);

        String buildTaskStatus = null;
        int buildTaskProgress = 0;
        if (tasker != null) {
            TaskService.Task latestTask =
                    tasker.findTaskByPayload(GRAPH_TASK_TYPE, Map.of("kb_id", kbId), null);
            if (latestTask != null && Set.of("pending", "running").contains(latestTask.status)) {
                buildTaskStatus = latestTask.status;
                buildTaskProgress = (int) Math.round(latestTask.progress);
            } else if (latestTask != null && "success".equals(latestTask.status)) {
                buildTaskStatus = "completed";
                buildTaskProgress = 100;
            } else if (latestTask != null && Set.of("failed", "cancelled").contains(latestTask.status)) {
                buildTaskStatus = "failed";
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kb_id", kbId);
        result.put("kb_type", kb.getKbType());
        result.put("configured", !config.isEmpty());
        result.put("locked", Boolean.TRUE.equals(config.get("locked")));
        result.put("config", publicConfig(config));
        result.put("total_chunks", totalChunks);
        result.put("pending_chunks", pendingChunks);
        result.put("indexed_chunks", indexedChunks);
        result.put("structured_chunks", structuredChunks);
        result.put("extraction_counts", extractionCounts);
        result.put("vector_counts", vectorCounts);
        result.put("entity_count", graphCounts[0]);
        result.put("relationship_count", graphCounts[1]);
        result.put("build_task_status", buildTaskStatus);
        result.put("build_task_progress", buildTaskProgress);
        return result;
    }

    /** 配置（锁定）图谱抽取器（对应 {@code configure}）。 */
    public Map<String, Object> configure(
            String kbId, String extractorType, Map<String, Object> extractorOptions, String createdBy) {
        KnowledgeBase kb = getMilvusKb(kbId);
        Map<String, Object> additionalParams = parseObject(kb.getAdditionalParams());
        Map<String, Object> existingConfig = asMap(additionalParams.get(GRAPH_CONFIG_KEY));
        String normalizedExtractorType = extractorType == null ? "" : extractorType.toLowerCase();

        if (Boolean.TRUE.equals(existingConfig.get("locked"))) {
            String existingExtractorType =
                    String.valueOf(existingConfig.getOrDefault("extractor_type", "")).toLowerCase();
            if (!normalizedExtractorType.equals(existingExtractorType)) {
                throw new IllegalArgumentException("图谱抽取器类型已锁定，只能修改模型、Schema 等抽取参数");
            }
        }

        Map<String, Object> options = extractorOptions == null ? new LinkedHashMap<>() : extractorOptions;
        if ("llm".equals(normalizedExtractorType) && options.get("prompt") != null) {
            throw new IllegalArgumentException("LLM 图谱抽取器不支持自定义完整 Prompt，请使用 schema 配置抽取约束");
        }
        GraphExtractorFactory.create(normalizedExtractorType, options);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("locked", true);
        config.put("extractor_type", normalizedExtractorType);
        config.put("extractor_options", options);
        config.put("created_at", existingConfig.get("created_at") == null
                ? DateTimeUtils.utcIsoformat() : existingConfig.get("created_at"));
        config.put("created_by", existingConfig.get("created_by") == null
                ? createdBy : existingConfig.get("created_by"));
        if (Boolean.TRUE.equals(existingConfig.get("locked"))) {
            config.put("updated_at", DateTimeUtils.utcIsoformat());
            config.put("updated_by", createdBy);
        }
        additionalParams.put(GRAPH_CONFIG_KEY, config);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("additional_params", additionalParams);
        kbRepository.update(kbId, data);
        return config;
    }

    /** 抽取失败的 chunk 样本（对应 {@code get_failed_chunk_samples}）。 */
    public Map<String, Object> getFailedChunkSamples(String kbId, int limit) {
        getMilvusKb(kbId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kb_id", kbId);
        result.put("samples", chunkRepository.listGraphExtractionFailedSamples(kbId, limit));
        return result;
    }

    // ==================== 构建 ====================

    /**
     * 构建所有待处理 chunk 的图谱（对应 {@code build_pending_chunks}）。
     *
     * <p>抽取并发由 {@code extractor_options.concurrency_count} 决定（仅 llm 抽取器生效），
     * 预取量取其两倍并落在 [100, 1000] 区间。
     */
    public Map<String, Object> buildPendingChunks(String kbId, TaskService.TaskContext context) {
        KnowledgeBase kb = getMilvusKb(kbId);
        Map<String, Object> config = getLockedConfig(parseObject(kb.getAdditionalParams()));
        Map<String, Object> extractorOptions = runtimeExtractorOptions(config);
        String extractorType = String.valueOf(config.get("extractor_type"));
        GraphExtractor extractor = GraphExtractorFactory.create(extractorType, extractorOptions);
        int workerCount = getWorkerCount(config);

        int totalPending = chunkRepository.countGraphPendingByKbId(kbId);
        int initiallyIndexed = chunkRepository.countGraphIndexedByKbId(kbId);
        int fetchSize = Math.max(GRAPH_BUILD_FETCH_MIN_SIZE,
                Math.min(workerCount * 2, GRAPH_BUILD_FETCH_MAX_SIZE));

        AtomicInteger processed = new AtomicInteger();
        AtomicInteger extractionFailed = new AtomicInteger();
        AtomicInteger writeFailed = new AtomicInteger();
        AtomicInteger extractionCompleted = new AtomicInteger();
        AtomicInteger writeCompleted = new AtomicInteger();
        AtomicInteger activeExtractions = new AtomicInteger();

        BlockingQueue<KnowledgeChunk> extractionQueue =
                new LinkedBlockingQueue<>(Math.max(workerCount * 2, 1));
        BlockingQueue<String> writeQueue = new LinkedBlockingQueue<>(Math.max(workerCount * 2, 1));
        AtomicBoolean structureDone = new AtomicBoolean(false);
        AsyncEvent vectorWakeup = new AsyncEvent();
        AsyncEvent reporterStop = new AsyncEvent();

        long startedAtNanos = System.nanoTime();
        log.info("图谱构建开始 kb_id={} pending={} extraction_concurrency={} fetch_size={}",
                kbId, totalPending, workerCount, fetchSize);

        ExecutorService extractionPool = Executors.newFixedThreadPool(Math.max(workerCount, 1), r -> {
            Thread t = new Thread(r, "graph-extractor");
            t.setDaemon(true);
            return t;
        });
        ExecutorService writerPool = daemonPool("graph-writer");
        ExecutorService vectorPool = daemonPool("graph-vector-indexer");

        // 进度上报：每 GRAPH_BUILD_LOG_INTERVAL_SECONDS 输出一次汇总并回填进度
        Thread reporter = new Thread(
                () -> reportProgressLoop(kbId, context, reporterStop, startedAtNanos,
                        totalPending, workerCount, initiallyIndexed,
                        extractionCompleted, writeCompleted, activeExtractions,
                        extractionFailed, writeFailed),
                "graph-progress-reporter");
        reporter.setDaemon(true);
        reporter.start();

        boolean cancelled = false;
        RuntimeException failure = null;
        try {
            // ---- 主流程：分页取待处理 chunk，按状态分流 ----
            int afterId = 0;
            while (true) {
                requireNotCancelled(context);
                List<KnowledgeChunk> chunks =
                        chunkRepository.listGraphPendingByKbId(kbId, fetchSize, afterId);
                if (chunks == null || chunks.isEmpty()) {
                    break;
                }
                for (KnowledgeChunk chunk : chunks) {
                    if (chunk.getId() != null) {
                        afterId = chunk.getId();
                    }
                    if (Boolean.TRUE.equals(chunk.getGraphStructureIndexed())) {
                        extractionCompleted.incrementAndGet();
                        writeCompleted.incrementAndGet();
                        vectorWakeup.set();
                    } else if (isPresent(chunk.getExtractionResult())) {
                        extractionCompleted.incrementAndGet();
                        putQueueItem(writeQueue, chunk.getChunkId(), context);
                    } else {
                        putQueueItem(extractionQueue, chunk, context);
                    }
                }
            }

            // ---- 收尾：抽取池 → 写入 → 向量 ----
            for (int i = 0; i < Math.max(workerCount, 1); i++) {
                putQueueItem(extractionQueue, POISON_CHUNK, context);
            }
            awaitPool(extractionPool, context);

            putQueueItem(writeQueue, POISON_CHUNK_ID, context);
            awaitPool(writerPool, context);

            structureDone.set(true);
            vectorWakeup.set();
            awaitPool(vectorPool, context);
        } catch (TaskService.TaskContext.Cancelled exc) {
            cancelled = true;
            throw exc;
        } catch (RuntimeException exc) {
            failure = exc;
            throw exc;
        } finally {
            if (cancelled || failure != null) {
                extractionPool.shutdownNow();
                writerPool.shutdownNow();
                vectorPool.shutdownNow();
            } else {
                shutdownQuietly(extractionPool);
                shutdownQuietly(writerPool);
                shutdownQuietly(vectorPool);
            }
            reporterStop.set();
            try {
                reporter.join(GRAPH_BUILD_LOG_INTERVAL_SECONDS > 0
                        ? (long) (GRAPH_BUILD_LOG_INTERVAL_SECONDS * 1000) + 500 : 500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        int remaining = chunkRepository.countGraphPendingByKbId(kbId);
        Map<String, Integer> extractionCounts = chunkRepository.countGraphExtractionStatusesByKbId(kbId);
        Map<String, Integer> vectorCounts = graphRepository.countVectorStatusesByKbId(kbId);
        double durationSeconds = (System.nanoTime() - startedAtNanos) / 1_000_000_000.0;
        log.info("图谱构建结束 kb_id={} success={} extraction_failed={} write_failed={} remaining={} duration={}s",
                kbId, processed.get(), extractionFailed.get(), writeFailed.get(), remaining,
                String.format("%.2f", durationSeconds));

        int incomplete = Math.max(remaining - extractionCounts.getOrDefault("failed", 0), 0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kb_id", kbId);
        result.put("success", Math.max(totalPending - remaining, 0));
        result.put("failed", extractionFailed.get() + writeFailed.get());
        result.put("extraction_failed", extractionFailed.get());
        result.put("write_failed", writeFailed.get());
        result.put("remaining", remaining);
        result.put("vector_failed", vectorCounts.getOrDefault("failed", 0));
        if (incomplete > 0 || writeFailed.get() > 0 || vectorCounts.getOrDefault("failed", 0) > 0) {
            throw new IllegalStateException(String.format(
                    "图谱构建执行异常：chunk_incomplete=%d, write_failed=%d, vector_failed=%d",
                    incomplete, writeFailed.get(), vectorCounts.getOrDefault("failed", 0)));
        }
        return result;
    }

    /** 抽取 worker 主体。 */
    private void extractionWorkerLoop(
            String kbId,
            TaskService.TaskContext context,
            GraphExtractor extractor,
            BlockingQueue<KnowledgeChunk> extractionQueue,
            BlockingQueue<String> writeQueue,
            AtomicInteger activeExtractions,
            AtomicInteger extractionCompleted,
            AtomicInteger extractionFailed) {
        while (true) {
            KnowledgeChunk chunk;
            try {
                chunk = extractionQueue.take();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                if (chunk == POISON_CHUNK) {
                    return;
                }
                requireNotCancelled(context);
                activeExtractions.incrementAndGet();
                long extractionStartedAt = System.nanoTime();
                try {
                    getChunkExtractionResult(kbId, chunk, extractor);
                    putQueueItem(writeQueue, chunk.getChunkId(), context);
                } catch (TaskService.TaskContext.Cancelled cancelled) {
                    throw cancelled;
                } catch (RuntimeException exc) {
                    extractionFailed.incrementAndGet();
                    log.error("Chunk 图谱抽取失败 kb_id={} chunk_id={}: {}",
                            kbId, chunk.getChunkId(), String.valueOf(exc.getMessage()));
                } finally {
                    activeExtractions.decrementAndGet();
                    extractionCompleted.incrementAndGet();
                    log.debug("Chunk 图谱抽取结束 kb_id={} chunk_id={} duration={}s",
                            kbId, chunk.getChunkId(),
                            String.format("%.2f", (System.nanoTime() - extractionStartedAt) / 1_000_000_000.0));
                }
            } finally {
                // 阻塞队列无 task_done 语义，计数的唯一职责由调用方完成，此处无需处理
            }
        }
    }

    /** 写入 worker 主体（单线程串行写 Neo4j，与参考一致）。 */
    private void writeWorkerLoop(
            String kbId,
            TaskService.TaskContext context,
            GraphExtractor extractor,
            BlockingQueue<String> writeQueue,
            AtomicInteger processed,
            AtomicInteger writeFailed,
            AtomicInteger writeCompleted,
            AsyncEvent vectorWakeup) {
        while (true) {
            String chunkId;
            try {
                chunkId = writeQueue.take();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                if (POISON_CHUNK_ID.equals(chunkId)) {
                    return;
                }
                requireNotCancelled(context);
                KnowledgeChunk chunk = chunkRepository.getByChunkId(chunkId);
                if (chunk == null) {
                    throw new IllegalArgumentException("图谱写入找不到 chunk: " + chunkId);
                }
                Map<String, Object> extractionResult = getChunkExtractionResult(kbId, chunk, extractor);
                long writeStartedAt = System.nanoTime();
                Object[] written = writeChunkGraph(kbId, chunk, extractionResult);
                List<Map<String, Object>> entities = castList(written[0]);
                List<Map<String, Object>> triples = castList(written[1]);
                graphRepository.upsertChunkGraph(kbId, chunk.getFileId(), chunk.getChunkId(),
                        entities, triples);
                List<String> entityIds = new ArrayList<>();
                for (Map<String, Object> entity : entities) {
                    entityIds.add(String.valueOf(entity.get("entity_id")));
                }
                chunkRepository.markGraphStructureIndexed(chunk.getChunkId(), entityIds);
                vectorWakeup.set();
                processed.incrementAndGet();
                log.debug("Chunk 图谱写入结束 kb_id={} chunk_id={} entities={} triples={} duration={}s",
                        kbId, chunk.getChunkId(), entities.size(), triples.size(),
                        String.format("%.2f", (System.nanoTime() - writeStartedAt) / 1_000_000_000.0));
            } catch (TaskService.TaskContext.Cancelled cancelled) {
                throw cancelled;
            } catch (RuntimeException exc) {
                writeFailed.incrementAndGet();
                log.error("Chunk 图谱写入失败 kb_id={} chunk_id={}: {}",
                        kbId, chunkId, String.valueOf(exc.getMessage()));
            } finally {
                if (chunkId != null) {
                    writeCompleted.incrementAndGet();
                }
            }
        }
    }

    /** 向量索引 worker 主体。 */
    private void vectorWorkerLoop(
            String kbId,
            TaskService.TaskContext context,
            String embeddingModelSpec,
            AtomicBoolean structureDone,
            AsyncEvent vectorWakeup) {
        while (true) {
            requireNotCancelled(context);
            int entityCount = indexVectorBatch(kbId, embeddingModelSpec, "entity");
            int tripleCount = indexVectorBatch(kbId, embeddingModelSpec, "triple");
            if (entityCount > 0 || tripleCount > 0) {
                graphRepository.finalizeGraphIndexedChunks(kbId);
                continue;
            }

            Map<String, Integer> vectorCounts = graphRepository.countVectorStatusesByKbId(kbId);
            if (structureDone.get()
                    && vectorCounts.getOrDefault("pending", 0) == 0
                    && vectorCounts.getOrDefault("processing", 0) == 0) {
                graphRepository.finalizeGraphIndexedChunks(kbId);
                return;
            }
            vectorWakeup.clear();
            vectorWakeup.await((long) (GRAPH_VECTOR_FLUSH_INTERVAL_SECONDS * 1000) * 5);
            if (vectorWakeup.isSet()) {
                sleepQuietly((long) (GRAPH_VECTOR_FLUSH_INTERVAL_SECONDS * 1000));
            }
        }
    }

    /** 认领并向量化一批记录，返回处理条数（对应 {@code index_vector_batch}）。 */
    private int indexVectorBatch(String kbId, String embeddingModelSpec, String recordType) {
        KnowledgeGraphRepository.ClaimResult claimed = graphRepository.claimVectorRecords(
                kbId, recordType, GRAPH_VECTOR_BATCH_SIZE, GRAPH_VECTOR_LEASE_SECONDS);
        List<Map<String, Object>> records = claimed.payloads();
        if (records == null || records.isEmpty()) {
            return 0;
        }
        List<String> recordIds = new ArrayList<>(records.size());
        for (Map<String, Object> record : records) {
            recordIds.add(String.valueOf(record.get("id")));
        }
        try {
            graphVectorStore.upsertGraphRecords(kbId, embeddingModelSpec, recordType, records);
            graphRepository.markVectorRecordsIndexed(recordType, recordIds, claimed.token());
        } catch (RuntimeException exc) {
            graphRepository.markVectorRecordsFailed(
                    recordType, recordIds, claimed.token(), String.valueOf(exc.getMessage()));
            log.error("图谱向量索引失败 kb_id={} type={} count={}: {}",
                    kbId, recordType, records.size(), String.valueOf(exc.getMessage()));
        }
        return records.size();
    }

    /** 进度上报循环（对应 {@code report_progress}）。 */
    private void reportProgressLoop(
            String kbId,
            TaskService.TaskContext context,
            AsyncEvent reporterStop,
            long startedAtNanos,
            int totalPending,
            int workerCount,
            int initiallyIndexed,
            AtomicInteger extractionCompleted,
            AtomicInteger writeCompleted,
            AtomicInteger activeExtractions,
            AtomicInteger extractionFailed,
            AtomicInteger writeFailed) {
        while (true) {
            reporterStop.await((long) (GRAPH_BUILD_LOG_INTERVAL_SECONDS * 1000));
            if (reporterStop.isSet()) {
                return;
            }
            double elapsed = Math.max((System.nanoTime() - startedAtNanos) / 1_000_000_000.0, 0.001);
            double extractionRate = extractionCompleted.get() / elapsed;
            Map<String, Integer> vectorCounts = graphRepository.countVectorStatusesByKbId(kbId);
            String message = String.format(
                    "图谱构建：抽取 %d/%d (活跃 %d/%d)，写入 %d/%d，向量待处理 %d，向量失败 %d，"
                            + "抽取失败 %d，写入失败 %d，抽取吞吐 %.2f chunk/s",
                    extractionCompleted.get(), totalPending, activeExtractions.get(), workerCount,
                    writeCompleted.get(), totalPending,
                    vectorCounts.getOrDefault("pending", 0) + vectorCounts.getOrDefault("processing", 0),
                    vectorCounts.getOrDefault("failed", 0),
                    extractionFailed.get(), writeFailed.get(), extractionRate);
            log.info("kb_id={} {}", kbId, message);
            if (context != null) {
                double extractionProgress = extractionCompleted.get() / (double) Math.max(totalPending, 1) * 40.0;
                double structureProgress = writeCompleted.get() / (double) Math.max(totalPending, 1) * 20.0;
                int indexedChunks = chunkRepository.countGraphIndexedByKbId(kbId);
                int newlyIndexed = Math.max(indexedChunks - initiallyIndexed, 0);
                double vectorProgress = newlyIndexed / (double) Math.max(totalPending, 1) * 35.0;
                context.setProgress(5.0 + Math.min(extractionProgress + structureProgress + vectorProgress, 95.0),
                        message);
            }
        }
    }

    // ==================== 抽取 ====================

    /** 取单个 chunk 的抽取结果（命中缓存则归一化复用，否则带重试调用抽取器）。 */
    private Map<String, Object> getChunkExtractionResult(
            String kbId, KnowledgeChunk chunk, GraphExtractor extractor) {
        String extractorType = extractor.extractorType();
        if (isPresent(chunk.getExtractionResult())) {
            return GraphExtractor.normalizeExtractionResult(
                    parseObject(chunk.getExtractionResult()), extractorType);
        }

        Map<String, Object> details = parseObject(chunk.getGraphExtractionDetails());
        if ("failed".equals(details.get("status"))) {
            chunkRepository.markGraphExtractionPending(chunk.getChunkId());
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kb_id", kbId);
        metadata.put("chunk_id", chunk.getChunkId());
        metadata.put("file_id", chunk.getFileId());
        metadata.put("chunk_index", chunk.getChunkIndex());

        for (int attempt = 1; attempt <= GRAPH_EXTRACTION_MAX_ATTEMPTS; attempt++) {
            try {
                Map<String, Object> extractionResult = extractor.extract(chunk.getContent(), metadata);
                Map<String, Object> normalized = GraphExtractor.normalizeExtractionResult(
                        extractionResult, extractorType);
                chunkRepository.updateExtractionResult(chunk.getChunkId(), normalized, attempt);
                return normalized;
            } catch (RuntimeException exc) {
                if (attempt >= GRAPH_EXTRACTION_MAX_ATTEMPTS) {
                    chunkRepository.markGraphExtractionFailed(
                            chunk.getChunkId(), attempt, String.valueOf(exc.getMessage()));
                    throw exc;
                }
                double delay = GRAPH_EXTRACTION_RETRY_DELAYS_SECONDS[attempt - 1];
                log.warn("Chunk 图谱抽取重试 kb_id={} chunk_id={} attempt={}/{} delay={}s: {}",
                        kbId, chunk.getChunkId(), attempt, GRAPH_EXTRACTION_MAX_ATTEMPTS,
                        String.format("%.1f", delay), String.valueOf(exc.getMessage()));
                sleepQuietly((long) (delay * 1000));
            }
        }
        throw new IllegalStateException("Chunk 图谱抽取未返回结果: " + chunk.getChunkId());
    }

    // ==================== 写入 ====================

    /** 将单个 chunk 的抽取结果写入 Neo4j，返回 {@code [entityRecords, tripleRecords]}。 */
    public Object[] writeChunkGraph(
            String kbId, KnowledgeChunk chunk, Map<String, Object> normalizedResult) {
        String label = Neo4jConnectionManager.safeNeo4jLabel(kbId);
        Map<String, Object> graphPayload = GraphUtils.buildGraphPayload(normalizedResult);
        Map<String, Object> payloadMetadata = asMap(graphPayload.get("metadata"));
        String relationExtractorType = String.valueOf(
                payloadMetadata.getOrDefault("extractor_type", "unknown"));
        List<Map<String, Object>> entities = castList(graphPayload.get("entities"));
        List<Map<String, Object>> relations = castList(graphPayload.get("relations"));
        Map<String, Map<String, Object>> entityById = new LinkedHashMap<>();
        for (Map<String, Object> entity : entities) {
            entityById.put(String.valueOf(entity.get("id")), entity);
        }
        List<Map<String, Object>> entityRecords = buildEntityRecords(kbId, entities);
        Map<String, Map<String, Object>> entityRecordByLocalId = new LinkedHashMap<>();
        for (int i = 0; i < entities.size(); i++) {
            entityRecordByLocalId.put(String.valueOf(entities.get(i).get("id")), entityRecords.get(i));
        }
        List<Map<String, Object>> tripleRecords =
                buildTripleRecords(kbId, relations, entityRecordByLocalId, graphPayload);
        String content = chunk.getContent() == null ? "" : chunk.getContent();
        String contentPreview = content.length() > 300 ? content.substring(0, 300) : content;

        // 预构建 Cypher 模板（同一 chunk 内复用）
        String mergeChunkCypher = GraphUtils.cypherMergeChunk(label);
        String mergeEntityCypher = GraphUtils.cypherMergeEntityMention(label);
        String mergeRelationCypher = GraphUtils.cypherMergeRelation(label);

        Neo4jConnectionManager.neo4jWrite(driver(), tx -> {
            // 1. MERGE Chunk 节点
            Map<String, Object> chunkParams = new LinkedHashMap<>();
            chunkParams.put("chunk_id", chunk.getChunkId());
            chunkParams.put("file_id", chunk.getFileId());
            chunkParams.put("kb_id", kbId);
            chunkParams.put("chunk_index", chunk.getChunkIndex());
            chunkParams.put("content_preview", contentPreview);
            chunkParams.put("start_char_pos", chunk.getStartCharPos());
            chunkParams.put("end_char_pos", chunk.getEndCharPos());
            tx.run(mergeChunkCypher, chunkParams);

            // 2. MERGE Entity 节点 + Chunk→Entity (MENTIONS)
            for (Map<String, Object> entity : entities) {
                Map<String, Object> entityRecord =
                        entityRecordByLocalId.get(String.valueOf(entity.get("id")));
                Map<String, Object> entityParams = new LinkedHashMap<>();
                entityParams.put("chunk_id", chunk.getChunkId());
                entityParams.put("file_id", chunk.getFileId());
                entityParams.put("kb_id", kbId);
                entityParams.put("entity_id", entityRecord.get("entity_id"));
                entityParams.put("normalized_name", GraphUtils.normalizeEntityName(
                        String.valueOf(entity.get("text"))));
                entityParams.put("entity_label", labelOf(entity, "Entity"));
                entityParams.put("name", entity.get("text"));
                entityParams.put("attributes", JSON.toJSONString(
                        entity.get("attributes") == null ? new ArrayList<>() : entity.get("attributes")));
                tx.run(mergeEntityCypher, entityParams);
            }

            // 3. MERGE Entity→Entity (RELATION) 边
            for (Map<String, Object> relation : relations) {
                String sourceLocalId = String.valueOf(relation.get("source"));
                String targetLocalId = String.valueOf(relation.get("target"));
                Map<String, Object> source = entityById.get(sourceLocalId);
                Map<String, Object> target = entityById.get(targetLocalId);
                Map<String, Object> sourceRecord = entityRecordByLocalId.get(sourceLocalId);
                Map<String, Object> targetRecord = entityRecordByLocalId.get(targetLocalId);
                String relationType = String.valueOf(relation.getOrDefault("label", "RELATED_TO"));
                String tripleId = GraphUtils.computeTripleId(
                        kbId,
                        String.valueOf(sourceRecord.get("normalized_name")),
                        String.valueOf(sourceRecord.get("label")),
                        relationType,
                        String.valueOf(targetRecord.get("normalized_name")),
                        String.valueOf(targetRecord.get("label")));
                Map<String, Object> relationParams = new LinkedHashMap<>();
                relationParams.put("kb_id", kbId);
                relationParams.put("chunk_id", chunk.getChunkId());
                relationParams.put("file_id", chunk.getFileId());
                relationParams.put("source_name", GraphUtils.normalizeEntityName(
                        String.valueOf(source.get("text"))));
                relationParams.put("source_label", labelOf(source, "Entity"));
                relationParams.put("target_name", GraphUtils.normalizeEntityName(
                        String.valueOf(target.get("text"))));
                relationParams.put("target_label", labelOf(target, "Entity"));
                relationParams.put("relation_type", relationType);
                relationParams.put("triple_id", tripleId);
                relationParams.put("text", relation.get("text"));
                relationParams.put("extractor_type", relationExtractorType);
                tx.run(mergeRelationCypher, relationParams);
            }
            return null;
        });
        return new Object[] {entityRecords, tripleRecords};
    }

    /** 构建实体记录（对应 {@code _build_entity_records}）。 */
    private List<Map<String, Object>> buildEntityRecords(
            String kbId, List<Map<String, Object>> entities) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> entity : entities) {
            String label = labelOf(entity, "Entity");
            String normalizedName = GraphUtils.normalizeEntityName(String.valueOf(entity.get("text")));
            String entityId = GraphUtils.computeEntityId(kbId, normalizedName, label);
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("entity_id", entityId);
            record.put("kb_id", kbId);
            record.put("normalized_name", normalizedName);
            record.put("label", label);
            record.put("name", entity.get("text"));
            record.put("attributes", entity.get("attributes") == null
                    ? new ArrayList<>() : entity.get("attributes"));
            record.put("content", normalizedName);
            records.add(record);
        }
        return records;
    }

    /** 构建三元组记录（对应 {@code _build_triple_records}，按 triple_id 去重）。 */
    private List<Map<String, Object>> buildTripleRecords(
            String kbId,
            List<Map<String, Object>> relations,
            Map<String, Map<String, Object>> entityRecordByLocalId,
            Map<String, Object> graphPayload) {
        List<Map<String, Object>> records = new ArrayList<>();
        Set<String> seenTripleIds = new LinkedHashSet<>();
        Map<String, Object> metadata = asMap(graphPayload.get("metadata"));
        String extractorType = String.valueOf(metadata.getOrDefault("extractor_type", "unknown"));
        for (Map<String, Object> relation : relations) {
            Map<String, Object> sourceRecord =
                    entityRecordByLocalId.get(String.valueOf(relation.get("source")));
            Map<String, Object> targetRecord =
                    entityRecordByLocalId.get(String.valueOf(relation.get("target")));
            String relationType = String.valueOf(relation.getOrDefault("label", "RELATED_TO"));
            String tripleId = GraphUtils.computeTripleId(
                    kbId,
                    String.valueOf(sourceRecord.get("normalized_name")),
                    String.valueOf(sourceRecord.get("label")),
                    relationType,
                    String.valueOf(targetRecord.get("normalized_name")),
                    String.valueOf(targetRecord.get("label")));
            if (!seenTripleIds.add(tripleId)) {
                continue;
            }
            String content = sourceRecord.get("normalized_name") + " → " + relationType
                    + " → " + targetRecord.get("normalized_name");
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("triple_id", tripleId);
            record.put("kb_id", kbId);
            record.put("source_entity_id", sourceRecord.get("entity_id"));
            record.put("target_entity_id", targetRecord.get("entity_id"));
            record.put("relation_type", relationType);
            record.put("content", content);
            record.put("text", relation.get("text"));
            record.put("extractor_type", extractorType);
            records.add(record);
        }
        return records;
    }

    // ==================== 重置与对账 ====================

    /** 重置图谱构建状态（对应 {@code reset}）。 */
    public Map<String, Object> reset(
            String kbId, boolean clearExtractionResult, boolean clearConfig) {
        KnowledgeBase kb = getMilvusKb(kbId);
        deleteGraph(kbId);
        graphRepository.deleteByKbId(kbId);
        int resetChunks = chunkRepository.resetGraphStateByKbId(kbId, clearExtractionResult);
        if (clearConfig) {
            Map<String, Object> additionalParams = parseObject(kb.getAdditionalParams());
            additionalParams.remove(GRAPH_CONFIG_KEY);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("additional_params", additionalParams);
            kbRepository.update(kbId, data);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "图谱构建状态已重置");
        result.put("status", "success");
        result.put("reset_chunks", resetChunks);
        result.put("clear_extraction_result", clearExtractionResult);
        result.put("clear_config", clearConfig);
        return result;
    }

    /** 向量记录对账（对应 {@code reconcile_vectors}）。 */
    public Map<String, Object> reconcileVectors(String kbId, boolean allVectors) {
        getMilvusKb(kbId);
        int resetRecords = graphRepository.reconcileVectorRecords(kbId, allVectors);
        if (allVectors) {
            graphVectorStore.dropGraphCollections(kbId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kb_id", kbId);
        result.put("mode", allVectors ? "all_vectors" : "failed");
        result.put("reset_records", resetRecords);
        return result;
    }

    /** 删除整个知识库的图谱（Neo4j + 图谱向量集合）。 */
    public void deleteGraph(String kbId) {
        String label = Neo4jConnectionManager.safeNeo4jLabel(kbId);
        Neo4jConnectionManager.neo4jWrite(driver(), tx -> {
            tx.run("MATCH (n:MilvusKB:`" + label + "`) DETACH DELETE n");
            return null;
        });
        graphVectorStore.dropGraphCollections(kbId);
    }

    /** 删除单文件在图谱中的痕迹（引用表 + 向量 + Neo4j）。 */
    public void deleteFileGraph(String kbId, String fileId) {
        KnowledgeGraphRepository.DeleteReferencesResult refs =
                graphRepository.deleteFileReferences(fileId);
        graphVectorStore.deleteGraphRecords(kbId, refs.orphanEntityIds(), refs.orphanTripleIds());
        deleteFileGraphFromNeo4j(kbId, fileId);
    }

    /** 从 Neo4j 删除单文件的关系边、孤儿实体与 Chunk 节点。 */
    private void deleteFileGraphFromNeo4j(String kbId, String fileId) {
        String label = Neo4jConnectionManager.safeNeo4jLabel(kbId);
        Neo4jConnectionManager.neo4jWrite(driver(), tx -> {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("kb_id", kbId);
            params.put("file_id", fileId);
            tx.run("""
                    MATCH (:Entity:MilvusKB:`%s`)-[r:RELATION {kb_id: $kb_id, file_id: $file_id}]->
                        (:Entity:MilvusKB:`%s`)
                    DELETE r
                    """.formatted(label, label), params);
            tx.run("""
                    MATCH (:Chunk:MilvusKB:`%s` {kb_id: $kb_id, file_id: $file_id})-[m:MENTIONS]->
                        (e:Entity:MilvusKB:`%s`)
                    DELETE m
                    WITH DISTINCT e
                    WHERE NOT ()-[:MENTIONS]->(e)
                    DETACH DELETE e
                    """.formatted(label, label), params);
            tx.run("""
                    MATCH (c:Chunk:MilvusKB:`%s` {kb_id: $kb_id, file_id: $file_id})
                    DETACH DELETE c
                    """.formatted(label), params);
            return null;
        });
    }

    // ==================== 查询 ====================

    /** 按关键词/深度查询子图（对应 {@code query_nodes}）。失败时返回空图。 */
    public Map<String, Object> queryNodes(
            String kbId, String keyword, int maxDepth, int maxNodes, boolean excludeChunk) {
        String effectiveKbId = (kbId == null || kbId.isBlank()) ? boundKbId : kbId;
        if (effectiveKbId == null || effectiveKbId.isBlank()) {
            return emptySubgraph();
        }
        String label = Neo4jConnectionManager.safeNeo4jLabel(effectiveKbId);
        try {
            return queryNodesSync(effectiveKbId, label, keyword == null ? "" : keyword,
                    maxNodes, maxDepth, excludeChunk);
        } catch (RuntimeException exc) {
            log.error("Milvus graph query failed: {}", String.valueOf(exc.getMessage()));
            return emptySubgraph();
        }
    }

    private Map<String, Object> queryNodesSync(
            String kbId, String label, String keyword, int limit, int maxDepth, boolean excludeChunk) {
        int effectiveDepth = maxDepth;
        try (Session session = driver().session()) {
            Map<String, Object> queryParams = new LinkedHashMap<>();
            queryParams.put("keyword", keyword);
            queryParams.put("limit", limit);
            if (effectiveDepth > 0) {
                effectiveDepth = Math.min(effectiveDepth, 3);
                queryParams.put("path_limit", Math.max(limit, 1) * 10);
            }
            Result result = session.run(
                    buildQuery(label, keyword, limit, effectiveDepth, excludeChunk), queryParams);
            if (effectiveDepth <= 0) {
                return processQueryResult(result, limit, kbId, excludeChunk);
            }
            List<Record> records = result.list();
            if (records.isEmpty()) {
                return emptySubgraph();
            }
            return processSubgraphRecord(records.get(0), limit, kbId);
        }
    }

    /** 按种子实体查询子图（对应 {@code query_seed_subgraph}）。 */
    public Map<String, Object> querySeedSubgraph(String kbId, List<String> entityIds, int maxNodes) {
        if (entityIds == null || entityIds.isEmpty()) {
            return emptySubgraph();
        }
        List<String> seedEntityIds = new ArrayList<>(new LinkedHashSet<>(entityIds));
        String label = Neo4jConnectionManager.safeNeo4jLabel(kbId);
        String cypher = """
                MATCH (seed:Entity:MilvusKB:`%s`)
                WHERE seed.entity_id IN $entity_ids
                MATCH p = (seed)-[*1..2]-(n:MilvusKB:`%s`)
                WITH p LIMIT $path_limit
                WITH collect(p) AS paths
                UNWIND paths AS node_path
                UNWIND nodes(node_path) AS node
                WITH paths, collect(DISTINCT node) AS graph_nodes
                UNWIND paths AS rel_path
                UNWIND relationships(rel_path) AS rel
                RETURN graph_nodes AS nodes, collect(DISTINCT rel) AS edges
                """.formatted(label, label);
        try {
            try (Session session = driver().session()) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("entity_ids", seedEntityIds);
                params.put("path_limit", Math.max(maxNodes, 1) * 4);
                List<Record> records = session.run(cypher, params).list();
                if (records.isEmpty()) {
                    return emptySubgraph();
                }
                return processSubgraphRecord(records.get(0), maxNodes, kbId);
            }
        } catch (RuntimeException exc) {
            log.error("Milvus seed subgraph query failed: {}", String.valueOf(exc.getMessage()));
            return emptySubgraph();
        }
    }

    /** 单个排名结果。 */
    public record RankedChunk(String chunkId, double score) {}

    /** 以种子实体权重做 PPR，返回按分数倒序的 chunk（对应 {@code query_and_rank_chunks_by_ppr}）。 */
    public List<RankedChunk> queryAndRankChunksByPpr(
            String kbId, Map<String, Double> seedWeights, int maxNodes, int topK, double damping) {
        if (seedWeights == null || seedWeights.isEmpty()) {
            return List.of();
        }
        Map<String, Object> subgraph = querySeedSubgraph(
                kbId, new ArrayList<>(seedWeights.keySet()), maxNodes);
        return rankChunksByPpr(subgraph, seedWeights, topK, damping);
    }

    /**
     * 对子图做 personalization PageRank 并按 chunk 得分排序（对应 {@code rank_chunks_by_ppr}）。
     *
     * <p>参考实现用 {@code networkx.pagerank}：无向图、{@code person��lization} 覆盖全部节点、
     * {@code dangling=None} 时跳转分布用 personalization、{@code alpha=min(max(damping,0.1),0.99)}、
     * max_iter=100、tol=1e-6。此处以等价的幂迭代实现，收敛判据与参数一致。
     */
    public static List<RankedChunk> rankChunksByPpr(
            Map<String, Object> subgraph, Map<String, Double> seedWeights, int topK, double damping) {
        List<Map<String, Object>> nodes = castList(subgraph.get("nodes"));
        List<Map<String, Object>> edges = castList(subgraph.get("edges"));
        if (nodes.isEmpty()) {
            return List.of();
        }

        int nodeCount = nodes.size();
        Map<String, Integer> indexById = new LinkedHashMap<>();
        for (int i = 0; i < nodeCount; i++) {
            indexById.put(String.valueOf(nodes.get(i).get("id")), i);
        }
        List<int[]> edgeIndices = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            Integer source = indexById.get(String.valueOf(edge.get("source_id")));
            Integer target = indexById.get(String.valueOf(edge.get("target_id")));
            if (source != null && target != null) {
                edgeIndices.add(new int[] {source, target});
            }
        }
        if (edgeIndices.isEmpty()) {
            return List.of();
        }

        // 无向简单图：邻接表去重（对应 networkx.Graph 的 add_edges_from 语义）
        List<Set<Integer>> adjacency = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            adjacency.add(new LinkedHashSet<>());
        }
        for (int[] edge : edgeIndices) {
            adjacency.get(edge[0]).add(edge[1]);
            adjacency.get(edge[1]).add(edge[0]);
        }

        double[] reset = new double[nodeCount];
        List<int[]> chunkNodeIndexes = new ArrayList<>(); // [index, chunk_id]
        List<String> chunkNodeIds = new ArrayList<>();
        for (int index = 0; index < nodeCount; index++) {
            Map<String, Object> node = nodes.get(index);
            Map<String, Object> properties = asMap(node.get("properties"));
            if ("Chunk".equals(node.get("type")) && isPresent(asString(properties.get("chunk_id")))) {
                chunkNodeIndexes.add(new int[] {index});
                chunkNodeIds.add(asString(properties.get("chunk_id")));
                continue;
            }
            String entityId = asString(properties.get("entity_id"));
            if (entityId != null && seedWeights.containsKey(entityId)) {
                reset[index] = seedWeights.get(entityId);
            }
        }

        double resetTotal = 0.0;
        for (double value : reset) {
            resetTotal += value;
        }
        if (resetTotal <= 0 || chunkNodeIndexes.isEmpty()) {
            return List.of();
        }
        for (int i = 0; i < nodeCount; i++) {
            reset[i] = reset[i] / resetTotal;
        }

        double[] scores = pageRank(adjacency, reset, Math.min(Math.max(damping, 0.1), 0.99));
        List<RankedChunk> ranked = new ArrayList<>(chunkNodeIds.size());
        for (int i = 0; i < chunkNodeIds.size(); i++) {
            ranked.add(new RankedChunk(chunkNodeIds.get(i), scores[chunkNodeIndexes.get(i)[0]]));
        }
        ranked.sort((a, b) -> Double.compare(b.score(), a.score()));
        return ranked.size() > topK ? new ArrayList<>(ranked.subList(0, Math.max(topK, 0))) : ranked;
    }

    /**
     * PageRank 幂迭代（等价实现）。
     *
     * <p>与 {@code networkx.pagerank(dangling=None)} 一致：dangling 节点的分数按 personalization
     * 分布重投；personalization 必须覆盖全部节点（未覆盖者权重为 0、贡献为 0）。
     */
    private static double[] pageRank(List<Set<Integer>> adjacency, double[] personalization, double alpha) {
        int n = adjacency.size();
        double[] scores = personalization.clone();
        double[] next = new double[n];
        int danglingCount = 0;
        for (Set<Integer> neighbors : adjacency) {
            if (neighbors.isEmpty()) {
                danglingCount++;
            }
        }
        double tolerance = 1e-6;
        int maxIter = 100;
        for (int iteration = 0; iteration < maxIter; iteration++) {
            double danglingSum = 0.0;
            if (danglingCount > 0) {
                for (int i = 0; i < n; i++) {
                    if (adjacency.get(i).isEmpty()) {
                        danglingSum += scores[i];
                    }
                }
            }
            java.util.Arrays.fill(next, 0.0);
            for (int i = 0; i < n; i++) {
                Set<Integer> neighbors = adjacency.get(i);
                if (neighbors.isEmpty()) {
                    continue;
                }
                double share = scores[i] / neighbors.size();
                for (int neighbor : neighbors) {
                    next[neighbor] += share;
                }
            }
            double error = 0.0;
            for (int i = 0; i < n; i++) {
                double updated = alpha * next[i] + alpha * danglingSum * personalization[i]
                        + (1.0 - alpha) * personalization[i];
                error += Math.abs(updated - scores[i]);
                next[i] = updated;
            }
            scores = next.clone();
            if (error < n * tolerance) {
                break;
            }
        }
        return scores;
    }

    /** 图谱节点标签清单（对应 {@code get_labels}）。 */
    public List<String> getLabels(String kbId) {
        String effectiveKbId = (kbId == null || kbId.isBlank()) ? boundKbId : kbId;
        if (effectiveKbId == null || effectiveKbId.isBlank()) {
            return List.of();
        }
        String label = Neo4jConnectionManager.safeNeo4jLabel(effectiveKbId);
        String cypher = """
                MATCH (n:MilvusKB:`%s`)
                UNWIND labels(n) AS node_label
                WITH DISTINCT node_label
                WHERE node_label <> 'MilvusKB' AND node_label <> $kb_id
                RETURN node_label
                ORDER BY node_label
                """.formatted(label);
        try {
            List<Map<String, Object>> records = Neo4jConnectionManager.neo4jRead(
                    driver(), cypher, Map.of("kb_id", effectiveKbId));
            List<String> labels = new ArrayList<>();
            for (Map<String, Object> record : records) {
                labels.add(asString(record.get("node_label")));
            }
            return labels;
        } catch (RuntimeException exc) {
            log.error("Failed to get Milvus graph labels: {}", String.valueOf(exc.getMessage()));
            return List.of();
        }
    }

    /** 图谱统计（对应 {@code get_stats}）。 */
    public Map<String, Object> getStats(String kbId) {
        String effectiveKbId = (kbId == null || kbId.isBlank()) ? boundKbId : kbId;
        if (effectiveKbId == null || effectiveKbId.isBlank()) {
            return emptyStats();
        }
        String label = Neo4jConnectionManager.safeNeo4jLabel(effectiveKbId);
        String statsCypher = """
                MATCH (n:MilvusKB:`%s`)
                WITH count(n) AS node_count
                OPTIONAL MATCH (:MilvusKB:`%s`)-[r]->(:MilvusKB:`%s`)
                RETURN node_count, count(r) AS edge_count
                """.formatted(label, label, label);
        String labelCypher = """
                MATCH (n:Entity:MilvusKB:`%s`)
                WITH n.label AS entity_label, count(*) AS count
                RETURN entity_label, count
                ORDER BY count DESC
                """.formatted(label);
        try {
            return getStatsSync(statsCypher, labelCypher);
        } catch (RuntimeException exc) {
            log.error("Failed to get Milvus graph stats: {}", String.valueOf(exc.getMessage()));
            return emptyStats();
        }
    }

    private Map<String, Object> getStatsSync(String statsCypher, String labelCypher) {
        try (Session session = driver().session()) {
            List<Record> statsRecords = session.run(statsCypher).list();
            Record stats = statsRecords.isEmpty() ? null : statsRecords.get(0);
            List<Map<String, Object>> entityTypes = new ArrayList<>();
            for (Record row : session.run(labelCypher).list()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", asString(row.get("entity_label")));
                item.put("count", row.get("count").asLong());
                entityTypes.add(item);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total_nodes", stats == null ? 0L : stats.get("node_count").asLong());
            result.put("total_edges", stats == null ? 0L : stats.get("edge_count").asLong());
            result.put("entity_types", entityTypes);
            return result;
        }
    }

    // ==================== 校验与内部工具 ====================

    /** 取 milvus 类型知识库，类型不符直接报错（对应 {@code _get_milvus_kb}）。 */
    private KnowledgeBase getMilvusKb(String kbId) {
        KnowledgeBase kb = kbRepository.getByKbId(kbId);
        if (kb == null) {
            throw new IllegalArgumentException("知识库 " + kbId + " 不存在");
        }
        if (!"milvus".equalsIgnoreCase(kb.getKbType())) {
            throw new IllegalArgumentException("仅 Milvus 知识库支持独立图谱构建");
        }
        return kb;
    }

    /** 取已锁定的图谱配置（对应 {@code _get_locked_config}）。 */
    private Map<String, Object> getLockedConfig(Map<String, Object> additionalParams) {
        Map<String, Object> config = asMap(additionalParams.get(GRAPH_CONFIG_KEY));
        if (!Boolean.TRUE.equals(config.get("locked"))) {
            throw new IllegalArgumentException("请先确认并锁定图谱抽取配置");
        }
        if (!isPresent(asString(config.get("extractor_type")))) {
            throw new IllegalArgumentException("图谱抽取配置缺少 extractor_type");
        }
        return config;
    }

    /** 对外可见的配置（剥离 prompt，对应 {@code _public_config}）。 */
    private Map<String, Object> publicConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("locked", Boolean.TRUE.equals(config.get("locked")));
        result.put("extractor_type", config.get("extractor_type"));
        result.put("extractor_options", runtimeExtractorOptions(config));
        result.put("created_at", config.get("created_at"));
        result.put("created_by", config.get("created_by"));
        result.put("updated_at", config.get("updated_at"));
        result.put("updated_by", config.get("updated_by"));
        return result;
    }

    /** 运行期抽取选项（去掉 prompt，对应 {@code _runtime_extractor_options}）。 */
    private static Map<String, Object> runtimeExtractorOptions(Map<String, Object> config) {
        Map<String, Object> options = new LinkedHashMap<>(asMap(config.get("extractor_options")));
        options.remove("prompt");
        return options;
    }

    /** 抽取并发数（仅 llm 抽取器生效，上限 1000，对应 {@code _get_worker_count}）。 */
    private static int getWorkerCount(Map<String, Object> config) {
        if (!"llm".equalsIgnoreCase(asString(config.get("extractor_type")))) {
            return 1;
        }
        Object raw = asMap(config.get("extractor_options")).get("concurrency_count");
        if (raw == null) {
            return 1;
        }
        int workerCount;
        try {
            workerCount = raw instanceof Number number
                    ? number.intValue() : Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException exc) {
            return 1;
        }
        return Math.max(1, Math.min(workerCount, 1000));
    }

    /** 构造节点筛选条件（对应 {@code _build_where}）。 */
    private static String buildWhere(boolean excludeChunk, String keyword) {
        List<String> clauses = new ArrayList<>();
        if (excludeChunk) {
            clauses.add("NOT n:Chunk");
        }
        if (keyword != null && !keyword.isEmpty() && !"*".equals(keyword)) {
            clauses.add("(toLower(coalesce(n.name, '')) CONTAINS toLower($keyword)"
                    + " OR toLower(coalesce(n.content_preview, '')) CONTAINS toLower($keyword)"
                    + " OR toLower(coalesce(n.chunk_id, '')) CONTAINS toLower($keyword))");
        }
        return clauses.isEmpty() ? "" : "WHERE " + String.join(" AND ", clauses);
    }

    /** 构造子图查询 Cypher（对应 {@code _build_query}）。 */
    private static String buildQuery(String label, String keyword, int limit, int maxDepth, boolean excludeChunk) {
        String where = buildWhere(excludeChunk, keyword);
        if (maxDepth <= 0) {
            return """
                    MATCH (n:MilvusKB:`%s`)
                    %s
                    RETURN n AS h, null AS r, null AS t
                    LIMIT $limit
                    """.formatted(label, where);
        }
        String pathNodeFilter = "path_node:MilvusKB AND path_node:`" + label + "`";
        if (excludeChunk) {
            pathNodeFilter += " AND NOT path_node:Chunk";
        }
        return """
                MATCH (n:MilvusKB:`%s`)
                %s
                WITH n LIMIT $limit
                WITH collect(n) AS seeds
                UNWIND seeds AS seed
                OPTIONAL MATCH p = (seed)-[*1..%d]-(m:MilvusKB:`%s`)
                WHERE all(path_node IN nodes(p) WHERE %s)
                WITH seeds, p
                LIMIT $path_limit
                WITH seeds, collect(p) AS paths
                RETURN reduce(path_nodes = [], path IN paths | path_nodes + nodes(path)) + seeds AS nodes,
                       reduce(path_edges = [], path IN paths | path_edges + relationships(path)) AS edges
                """.formatted(label, where, maxDepth, label, pathNodeFilter);
    }

    /** 处理逐条记录结果（对应 {@code _process_query_result}）。 */
    private Map<String, Object> processQueryResult(
            Result result, int limit, String kbId, boolean excludeChunk) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> nodeIds = new LinkedHashSet<>();
        Set<String> edgeIds = new LinkedHashSet<>();

        while (result.hasNext()) {
            Record record = result.next();
            for (String key : List.of("h", "t")) {
                if (!record.containsKey(key)) {
                    continue;
                }
                Value raw = record.get(key);
                if (raw == null || raw.isNull()) {
                    continue;
                }
                Map<String, Object> node = normalizeNode(raw.asNode(), kbId);
                if (node.isEmpty() || !nodeIds.add(String.valueOf(node.get("id")))) {
                    continue;
                }
                if (excludeChunk && "Chunk".equals(node.get("type"))) {
                    continue;
                }
                nodes.add(node);
            }
            if (record.containsKey("r")) {
                Value rawEdge = record.get("r");
                if (rawEdge != null && !rawEdge.isNull()) {
                    Map<String, Object> edge = normalizeEdge(rawEdge.asRelationship());
                    if (!edge.isEmpty() && edgeIds.add(String.valueOf(edge.get("id")))) {
                        edges.add(edge);
                    }
                }
            }
            if (nodes.size() >= limit) {
                break;
            }
        }
        return finalizeSubgraphResult(nodes, edges, limit);
    }

    /** 处理聚合子图记录（对应 {@code _process_subgraph_record}）。 */
    private Map<String, Object> processSubgraphRecord(Record record, int limit, String kbId) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> nodeIds = new LinkedHashSet<>();
        Set<String> edgeIds = new LinkedHashSet<>();

        if (record.containsKey("nodes")) {
            for (Node rawNode : record.get("nodes").asList(Value::asNode)) {
                Map<String, Object> node = normalizeNode(rawNode, kbId);
                if (node.isEmpty() || !nodeIds.add(String.valueOf(node.get("id")))) {
                    continue;
                }
                nodes.add(node);
                if (nodes.size() >= limit) {
                    break;
                }
            }
        }
        if (record.containsKey("edges")) {
            for (Relationship rawEdge : record.get("edges").asList(Value::asRelationship)) {
                Map<String, Object> edge = normalizeEdge(rawEdge);
                if (edge.isEmpty() || !edgeIds.add(String.valueOf(edge.get("id")))) {
                    continue;
                }
                if (!nodeIds.contains(String.valueOf(edge.get("source_id")))
                        || !nodeIds.contains(String.valueOf(edge.get("target_id")))) {
                    continue;
                }
                edges.add(edge);
            }
        }
        return finalizeSubgraphResult(nodes, edges, limit);
    }

    /** 截断并按节点集合过滤边（对应 {@code _finalize_subgraph_result}）。 */
    private static Map<String, Object> finalizeSubgraphResult(
            List<Map<String, Object>> nodes, List<Map<String, Object>> edges, int limit) {
        int bounded = Math.max(0, limit);
        List<Map<String, Object>> finalNodes =
                nodes.size() > bounded ? new ArrayList<>(nodes.subList(0, bounded)) : nodes;
        Set<String> nodeIds = new LinkedHashSet<>();
        for (Map<String, Object> node : finalNodes) {
            nodeIds.add(String.valueOf(node.get("id")));
        }
        List<Map<String, Object>> finalEdges = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            if (nodeIds.contains(String.valueOf(edge.get("source_id")))
                    && nodeIds.contains(String.valueOf(edge.get("target_id")))) {
                finalEdges.add(edge);
            }
        }
        int maxEdges = bounded * 2;
        if (finalEdges.size() > maxEdges) {
            finalEdges = new ArrayList<>(finalEdges.subList(0, maxEdges));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", finalNodes);
        result.put("edges", finalEdges);
        return result;
    }

    /** 归一化 Neo4j 节点（对应 {@code _normalize_node}）。 */
    private Map<String, Object> normalizeNode(Node rawNode, String kbId) {
        if (rawNode == null) {
            return Map.of();
        }
        String nodeId = rawNode.elementId();
        List<String> labels = new ArrayList<>();
        rawNode.labels().forEach(labels::add);
        Map<String, Object> properties = new LinkedHashMap<>(rawNode.asMap());

        String effectiveKbId = (kbId == null || kbId.isBlank()) ? boundKbId : kbId;
        String dbLabel = properties.get("kb_id") == null
                ? effectiveKbId : String.valueOf(properties.get("kb_id"));
        List<String> filteredLabels = new ArrayList<>();
        for (String label : labels) {
            if (!"MilvusKB".equals(label) && !label.equals(dbLabel)) {
                filteredLabels.add(label);
            }
        }
        String entityType = labels.contains("Chunk")
                ? "Chunk" : String.valueOf(properties.getOrDefault("label", "Entity"));
        Object nameValue = properties.get("name");
        if (nameValue == null) {
            nameValue = properties.get("content_preview");
        }
        if (nameValue == null) {
            nameValue = properties.get("chunk_id");
        }
        String name = nameValue == null ? "Unknown" : String.valueOf(nameValue);

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("name", name);
        normalized.put("type", entityType);
        normalized.put("source", "milvus");

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("name", name);
        node.put("original_id", nodeId);
        node.put("type", entityType);
        node.put("labels", filteredLabels);
        node.put("properties", properties);
        node.put("normalized", normalized);
        node.put("graph_type", "milvus");
        return node;
    }

    /** 归一化 Neo4j 关系（对应 {@code _normalize_edge}）。 */
    private static Map<String, Object> normalizeEdge(Relationship rawEdge) {
        if (rawEdge == null) {
            return Map.of();
        }
        String edgeId = rawEdge.elementId();
        String edgeType = rawEdge.type();
        String sourceId = rawEdge.startNodeElementId();
        String targetId = rawEdge.endNodeElementId();
        Map<String, Object> properties = new LinkedHashMap<>(rawEdge.asMap());
        if (properties.get("type") != null) {
            edgeType = String.valueOf(properties.get("type"));
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("type", edgeType);
        normalized.put("direction", "directed");

        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", edgeId);
        edge.put("source_id", sourceId);
        edge.put("target_id", targetId);
        edge.put("type", edgeType);
        edge.put("properties", properties);
        edge.put("normalized", normalized);
        return edge;
    }

    private static Map<String, Object> emptySubgraph() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", new ArrayList<>());
        result.put("edges", new ArrayList<>());
        return result;
    }

    private static Map<String, Object> emptyStats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_nodes", 0L);
        result.put("total_edges", 0L);
        result.put("entity_types", new ArrayList<>());
        return result;
    }

    // ==================== 并发与队列工具 ====================

    /** 队列投递：阻塞至成功，期间周期性检查取消（对应 {@code put_queue_item}）。 */
    private static <T> void putQueueItem(
            BlockingQueue<T> queue, T item, TaskService.TaskContext context) {
        while (true) {
            requireNotCancelled(context);
            try {
                if (queue.offer(item, 500, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("图谱构建被中断", ie);
            }
        }
    }

    private static void requireNotCancelled(TaskService.TaskContext context) {
        if (context != null) {
            context.raiseIfCancelled();
        }
    }

    /** 等待线程池自然结束（worker 由哨兵退出）。 */
    private static void awaitPool(ExecutorService pool, TaskService.TaskContext context) {
        while (true) {
            requireNotCancelled(context);
            try {
                if (pool.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("图谱构建被中断", ie);
            }
        }
    }

    private static void shutdownQuietly(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    private static ExecutorService daemonPool(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("图谱构建被中断", ie);
        }
    }

    /**
     * 可重置的等待/唤醒信号量（对应 asyncio.Event 的 set/clear/is_set/wait）。
     *
     * <p>wait 返回时要么已置位、要么超时；调用方按 isSet 判断，与参考的
     * {@code wait_for(...)} + {@code TimeoutError} 分支等价。
     */
    static final class AsyncEvent {
        private final Object monitor = new Object();
        private volatile boolean set;

        void set() {
            synchronized (monitor) {
                set = true;
                monitor.notifyAll();
            }
        }

        void clear() {
            synchronized (monitor) {
                set = false;
            }
        }

        boolean isSet() {
            return set;
        }

        void await(long timeoutMillis) {
            synchronized (monitor) {
                if (set) {
                    return;
                }
                long deadline = System.currentTimeMillis() + Math.max(timeoutMillis, 0);
                while (!set) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        return;
                    }
                    try {
                        monitor.wait(remaining);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    /** worker 启动/收尾的集中入口，保证退出顺序与参考的 create_task + gather 一致。 */
    void startWorkers(
            String kbId,
            TaskService.TaskContext context,
            GraphExtractor extractor,
            String embeddingModelSpec,
            int workerCount,
            BlockingQueue<KnowledgeChunk> extractionQueue,
            BlockingQueue<String> writeQueue,
            AtomicInteger processed,
            AtomicInteger extractionFailed,
            AtomicInteger writeFailed,
            AtomicInteger extractionCompleted,
            AtomicInteger writeCompleted,
            AtomicInteger activeExtractions,
            AtomicBoolean structureDone,
            AsyncEvent vectorWakeup,
            ExecutorService extractionPool,
            ExecutorService writerPool,
            ExecutorService vectorPool) {
        for (int i = 0; i < Math.max(workerCount, 1); i++) {
            extractionPool.execute(() -> extractionWorkerLoop(kbId, context, extractor,
                    extractionQueue, writeQueue, activeExtractions,
                    extractionCompleted, extractionFailed));
        }
        writerPool.execute(() -> writeWorkerLoop(kbId, context, extractor, writeQueue,
                processed, writeFailed, writeCompleted, vectorWakeup));
        vectorPool.execute(() -> vectorWorkerLoop(kbId, context, embeddingModelSpec,
                structureDone, vectorWakeup));
    }

    // ==================== 取值工具 ====================

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> castList(Object value) {
        return value instanceof List ? (List<Map<String, Object>>) value : new ArrayList<>();
    }

    static Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> parsed = RepoValues.parseObject(json);
        return parsed == null ? new LinkedHashMap<>() : parsed;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static String labelOf(Map<String, Object> item, String fallback) {
        Object label = item.get("label");
        return label == null || String.valueOf(label).isEmpty() ? fallback : String.valueOf(label);
    }
}
