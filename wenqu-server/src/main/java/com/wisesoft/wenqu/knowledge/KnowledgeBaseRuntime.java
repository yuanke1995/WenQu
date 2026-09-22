package com.wisesoft.wenqu.knowledge;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.common.HashUtils;
import com.wisesoft.wenqu.config.OptionsService;
import com.wisesoft.wenqu.knowledge.chunking.ragflow.RagflowChunkDispatcher;
import com.wisesoft.wenqu.knowledge.chunking.ragflow.RagflowNlp;
import com.wisesoft.wenqu.models.KnowledgeBase;
import com.wisesoft.wenqu.models.KnowledgeChunk;
import com.wisesoft.wenqu.models.KnowledgeFile;
import com.wisesoft.wenqu.knowledge.graphs.KnowledgeGraphRetrieval;
import com.wisesoft.wenqu.knowledge.graphs.MilvusGraphService;
import com.wisesoft.wenqu.repositories.KnowledgeBaseCache;
import com.wisesoft.wenqu.repositories.KnowledgeBaseRepository;
import com.wisesoft.wenqu.repositories.KnowledgeChunkRepository;
import com.wisesoft.wenqu.repositories.KnowledgeFileRepository;
import com.wisesoft.wenqu.repositories.ModelProviderCache;
import com.wisesoft.wenqu.repositories.RepoValues;
import com.wisesoft.wenqu.service.DynamicEmbeddingModel;
import com.wisesoft.wenqu.service.FileStatus;
import com.wisesoft.wenqu.service.KeywordExtractor;
import com.wisesoft.wenqu.service.ModelSelectors;
import com.wisesoft.wenqu.service.OcrService;
import com.wisesoft.wenqu.storage.MinioStorageClient;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 知识库运行时执行器：文件记录的增改、解析、入库与状态查询。
 *
 * <p>由参考实现的 knowledge/base.py 中 {@code KnowledgeBase} 的通用（非抽象）实现逐方法翻译，
 * 并由 knowledge/manager.py 的 {@code KnowledgeBaseManager} 门面方法（add_file_record /
 * parse_file / index_file / update_file_params）合并为同一处对外入口——参考实现把「配置解析 +
 * 统计刷新」放在 manager、把「文件操作」放在 executor，本工程将两者收敛到本类，
 * 因为它们共享同一套仓储依赖，拆开只会产生一层无状态转发。
 *
 * <p>逐方法对应关系：
 * <ul>
 *   <li>{@link #addFileRecord} ← base.add_file_record（+ manager 的 stats 刷新包装）</li>
 *   <li>{@link #parseFile} ← base.parse_file</li>
 *   <li>{@link #updateFileParams} ← base.update_file_params</li>
 *   <li>{@link #indexFile} ← implementations/milvus.py::index_file</li>
 *   <li>{@link #listDocumentFileIdsByStatuses} ← manager.list_document_file_ids_by_statuses</li>
 *   <li>{@link #getKbConfig} ← manager.get_kb_config</li>
 *   <li>{@link #runWithStatsRefresh} ← manager._run_with_stats_refresh</li>
 * </ul>
 *
 * <p>必要替换（逐条标注）：
 * <ul>
 *   <li>向量库：参考实现为 Milvus collection（按 embedding 维度建集合、双写 PostgreSQL+Milvus）；
 *       本工程使用 Spring AI 的 {@link VectorStore}（Redis 向量库）单写，chunk 元数据仍落
 *       knowledge_chunks 表，与参考实现「关系库为准 + 向量库为索引」的双写语义等价。
 *   <li>embedding 函数：参考实现按 {@code embedding_model_spec} 动态选模型；本工程的向量库是全局单例
 *       （{@code RedisVectorStore} 绑定一个 {@code @Primary} 的 {@link DynamicEmbeddingModel}），
 *       故改为在<b>检索/入库的调用点</b>用 {@link DynamicEmbeddingModel#withSpec} 把客户端切到该知识库的 spec
 *       （spec → 供应商行的 base_url/api_key/model），语义与「按 KB 建实例」等价。
 *       2026-09-22 接线前 spec 只落库不生效，向量化一律走全局 {@code embedding.*} 配置；
 *       而新栈里那组配置没有任何来源（{@code c_ai_config} 已不在业务库、yml/env 未配），表现为向量化 401。
 *   <li>异步：参考实现全异步（asyncio）；本工程为同步阻塞调用，由任务线程承载。
 *   <li>并发锁：参考实现用 asyncio.gather 双写；本工程顺序写（失败即回滚已写 chunk）。
 * </ul>
 */
@Service
public class KnowledgeBaseRuntime {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseRuntime.class);

    /** 入库分批大小（对应参考实现 MILVUS_CHUNK_EMBED_BATCH_SIZE）。 */
    private static final int CHUNK_EMBED_BATCH_SIZE = 32;

    /** 向量召回过采样倍数（见 {@link #vectorRecall} 的必要替换说明）。 */
    private static final int VECTOR_RECALL_OVERSAMPLE = 8;

    /** 向量召回扫描下限（见 {@link #vectorRecall} 的必要替换说明）。 */
    private static final int VECTOR_RECALL_MIN_SCAN = 200;

    /** 文件名过滤扫描上限（与 KnowledgeBaseManager 的同名口径一致）。 */
    private static final int KB_FILE_SEARCH_SCAN_LIMIT = 3000;

    /** 重排分批大小（参考实现 models/rerank.py 的 {@code acompute_score} 默认值）。 */
    private static final int RERANK_BATCH_SIZE = 32;

    /** 重排单条截断长度（参考实现 models/rerank.py 的 {@code acompute_score} 默认值）。 */
    private static final int RERANK_MAX_LENGTH = 512;

    private final KnowledgeFileRepository fileRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final KnowledgeBaseCache kbCache;
    private final OcrService ocrService;
    private final OptionsService optionsService;
    private final VectorStore vectorStore;
    /**
     * 向量模型（全局单例，{@code @Primary}）。检索/入库前用
     * {@link DynamicEmbeddingModel#withSpec} 把它切到当前知识库的 {@code embedding_model_spec}
     * —— 对应参考实现「按 spec 建 KB 实例、每个 KB 一套 embedding 函数」。
     */
    private final DynamicEmbeddingModel embeddingModel;
    private final MinioStorageClient minioStorageClient;
    /**
     * 图谱服务。用 {@link ObjectProvider} 取用而非直接注入字段：
     * 图谱能力依赖 Neo4j（未部署时连接懒建即失败），此引用在删除/重建文件时才真正用到，
     * 延迟获取可保证未部署图谱时其余知识库功能不受影响。
     */
    private final ObjectProvider<MilvusGraphService> graphServiceProvider;

    /**
     * 图谱召回入口。同样用 {@link ObjectProvider} 延迟获取：图谱检索只在
     * {@code use_graph_retrieval=true} 时参与，未部署 Neo4j 不应影响普通检索。
     */
    private final ObjectProvider<KnowledgeGraphRetrieval> graphRetrievalProvider;

    /** 查询词抽取（关键词/混合检索分支使用）。 */
    private final KeywordExtractor keywordExtractor;

    /** 重排模型选择器（对应参考实现 models/rerank.py 的 {@code get_reranker}）。 */
    private final ModelSelectors modelSelectors;

    /** 模型缓存：检索参数里的 {@code reranker_model} 选项由当前重排模型清单动态供给。 */
    private final ModelProviderCache modelProviderCache;

    public KnowledgeBaseRuntime(
            KnowledgeFileRepository fileRepository,
            KnowledgeChunkRepository chunkRepository,
            KnowledgeBaseRepository kbRepository,
            KnowledgeBaseCache kbCache,
            OcrService ocrService,
            OptionsService optionsService,
            VectorStore vectorStore,
            DynamicEmbeddingModel embeddingModel,
            MinioStorageClient minioStorageClient,
            ObjectProvider<MilvusGraphService> graphServiceProvider,
            ObjectProvider<KnowledgeGraphRetrieval> graphRetrievalProvider,
            KeywordExtractor keywordExtractor,
            ModelSelectors modelSelectors,
            ModelProviderCache modelProviderCache) {
        this.fileRepository = fileRepository;
        this.chunkRepository = chunkRepository;
        this.kbRepository = kbRepository;
        this.kbCache = kbCache;
        this.ocrService = ocrService;
        this.optionsService = optionsService;
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.minioStorageClient = minioStorageClient;
        this.graphServiceProvider = graphServiceProvider;
        this.graphRetrievalProvider = graphRetrievalProvider;
        this.keywordExtractor = keywordExtractor;
        this.modelSelectors = modelSelectors;
        this.modelProviderCache = modelProviderCache;
    }

    // ==================== 配置 ====================

    /**
     * 取知识库运行配置（对应 manager.get_kb_config）。
     *
     * <p>先读 Redis 缓存，未命中或非法时回源数据库并回填；kb_type 未注册时抛 KBNotFoundError。
     */
    public KnowledgeBaseConfig getKbConfig(String kbId) {
        JSONObject snapshot = kbCache.getCachedKbConfig(kbId);
        if (snapshot == null) {
            KnowledgeBase kb = kbRepository.getByKbId(kbId);
            if (kb == null) {
                throw new KnowledgeBaseException.KBNotFoundError("Database " + kbId + " not found");
            }
            kbCache.cacheKbConfig(kb);
            snapshot = KnowledgeBaseCache.serializeKbConfig(kb);
        }

        String kbType = snapshot.getString("kb_type");
        if (kbType == null || kbType.isEmpty()) {
            kbType = "milvus";
        }
        if (!KnowledgeBaseFactory.isTypeSupported(kbType)) {
            throw new KnowledgeBaseException.KBNotFoundError("Unsupported knowledge base type: " + kbType);
        }

        Map<String, Object> additionalParams =
                KnowledgeBaseTypeParams.normalizeAdditionalParams(kbType, toMap(snapshot.get("additional_params")));
        additionalParams.remove("stats");
        // 快照中的 query_params 应为对象（见 KnowledgeBaseCache#serializeKbConfig）；
        // 兼容 TTL 内可能残留的旧快照（JSON 文本形态）再做一次解析，解析失败回落默认值。
        Object rawQueryParams = snapshot.get("query_params");
        Map<String, Object> queryParams =
                rawQueryParams instanceof String text ? parseJsonMap(text) : toMap(rawQueryParams);
        if (queryParams.isEmpty()) {
            queryParams = new LinkedHashMap<>(KnowledgeBaseTypeParams.defaultQueryParams(kbType));
        }
        return new KnowledgeBaseConfig(
                kbId, kbType, snapshot.getString("embedding_model_spec"), queryParams, additionalParams);
    }

    /** 知识库类型的创建参数配置（对应 get_create_params_config）。 */
    public Map<String, Object> getCreateParamsConfig(String kbId) {
        return KnowledgeBaseTypeParams.createParamsConfig(getKbConfig(kbId).kbType());
    }

    /**
     * 知识库类型的查询参数配置（对应 get_query_params_config）。
     *
     * <p>{@code reranker_model} 的选项由当前重排模型清单动态供给。
     */
    public Map<String, Object> getQueryParamsConfig(String kbId) {
        return KnowledgeBaseTypeParams.queryParamsConfig(
                getKbConfig(kbId).kbType(), modelProviderCache.getAllSpecs("rerank"));
    }

    /** 知识库类型的查询参数默认值（对应 get_default_query_params）。 */
    public Map<String, Object> getDefaultQueryParams(String kbId) {
        return KnowledgeBaseTypeParams.defaultQueryParams(getKbConfig(kbId).kbType());
    }

    /** 附加参数规范化（对应 normalize_additional_params）。 */
    public Map<String, Object> normalizeAdditionalParams(String kbId, Map<String, Object> additionalParams) {
        return KnowledgeBaseTypeParams.normalizeAdditionalParams(getKbConfig(kbId).kbType(), additionalParams);
    }

    /** 该类型是否要求 embedding 模型（对应 requires_embedding_model 类属性）。 */
    public boolean requiresEmbeddingModel(String kbId) {
        KnowledgeBaseFactory.KbTypeInfo info = KnowledgeBaseFactory.getKbClass(getKbConfig(kbId).kbType());
        return info != null && info.requiresEmbeddingModel();
    }

    /** 该类型是否支持文档全文操作（对应 supports_documents 类属性）。 */
    public boolean typeSupportsDocuments(String kbId) {
        KnowledgeBaseFactory.KbTypeInfo info = KnowledgeBaseFactory.getKbClass(getKbConfig(kbId).kbType());
        return info != null && info.supportsDocuments();
    }

    /** 知识库类型的附加参数（供处理参数三层合并使用）。 */
    public Map<String, Object> additionalParamsOf(String kbId) {
        return getKbConfig(kbId).additionalParams();
    }

    /**
     * 执行文件操作并刷新知识库统计，同时保留原始操作异常（对应 manager._run_with_stats_refresh）。
     */
    private <T> T runWithStatsRefresh(String kbId, java.util.concurrent.Callable<T> operation) throws Exception {
        try {
            T result = operation.call();
            refreshStatsQuietly(kbId);
            return result;
        } catch (Exception | Error failure) {
            refreshStatsQuietly(kbId);
            throw failure;
        }
    }

    private void refreshStatsQuietly(String kbId) {
        try {
            kbRepository.refreshStats(kbId);
        } catch (Exception exception) {
            log.error("Refresh database stats after operation: kb_id={}: {}", kbId, exception.getMessage());
        }
    }

    // ==================== 文件记录 ====================

    /**
     * 新增文件记录（对应 base.add_file_record + manager.add_file_record）。
     *
     * <p>元数据由 {@link KbUtils#prepareItemMetadata} 生成；文件大小缺失且来源为 MinIO 时补查；
     * 初始状态 uploaded，随后持久化。
     */
    public Map<String, Object> addFileRecord(String kbId, String item, Map<String, Object> params,
                                             String operatorId) throws Exception {
        return runWithStatsRefresh(kbId, () -> {
            Map<String, Object> additionalParams = additionalParamsOf(kbId);
            Map<String, Object> safeParams = params == null ? new LinkedHashMap<>() : params;
            String contentType = safeParams.get("content_type") == null
                    ? "file" : String.valueOf(safeParams.get("content_type"));

            Map<String, Object> metadata = KbUtils.prepareItemMetadata(item, contentType, kbId, safeParams);
            String fileId = String.valueOf(metadata.get("file_id"));
            metadata.put(
                    "processing_params",
                    KbUtils.resolveProcessingParams(
                            additionalParams, toMap(metadata.get("processing_params")), null));

            // 文件大小缺失时从 MinIO 补查
            if (metadata.get("size") == null && "file".equals(contentType)) {
                try {
                    String filePath = metadata.get("path") == null
                            ? item : String.valueOf(metadata.get("path"));
                    if (KbUtils.isMinioUrl(filePath)) {
                        String[] parsed = KbUtils.parseMinioUrl(filePath);
                        Long fileSize = minioStorageClient.statFile(parsed[0], parsed[1]);
                        if (fileSize != null) {
                            metadata.put("size", fileSize);
                        }
                    }
                } catch (Exception exception) {
                    log.warn("Failed to stat file size from MinIO for {}: {}", item, exception.getMessage());
                }
            }

            metadata.put("status", FileStatus.UPLOADED);
            metadata.put("created_at", DateTimeUtils.utcIsoformat());
            if (operatorId != null && !operatorId.isEmpty()) {
                metadata.put("created_by", operatorId);
            }

            persistFileMeta(fileId, metadata);
            return metadata;
        });
    }

    /**
     * 解析文件为 Markdown 并保存到对象存储（对应 base.parse_file）。
     *
     * <p>状态机：uploaded/error_parsing/failed → parsing → parsed / error_parsing。
     * 认领（claim）通过 {@code updateFieldsIfStatus} 完成，携带处理任务与持有者以支持并发收敛。
     */
    public Map<String, Object> parseFile(String kbId, String fileId, String operatorId,
                                         String processingTaskId, String processingOwner) throws Exception {
        Set<String> allowedStatuses = Set.of(FileStatus.UPLOADED, FileStatus.ERROR_PARSING, "failed");
        boolean hasOwner = processingTaskId != null && processingOwner != null;

        Map<String, Object> claimData = new LinkedHashMap<>();
        claimData.put("status", FileStatus.PARSING);
        claimData.put("error_message", null);
        claimData.put("processing_task_id", processingTaskId);
        claimData.put("processing_owner", processingOwner);
        if (operatorId != null && !operatorId.isEmpty()) {
            claimData.put("updated_by", operatorId);
        }
        KnowledgeFile claimed = fileRepository.updateFieldsIfStatus(
                kbId, fileId, allowedStatuses, claimData, null, null);
        if (claimed == null) {
            Map<String, Object> currentMeta = loadFileMeta(kbId, fileId);
            String currentStatus = currentMeta.get("status") == null ? null : String.valueOf(currentMeta.get("status"));
            throw new IllegalArgumentException(
                    "Cannot parse file with status '" + currentStatus + "'. "
                            + "File must be in one of these states: " + allowedStatuses);
        }

        Map<String, Object> fileMeta = fileRecordToMeta(claimed);
        String filePath = fileMeta.get("path") == null ? null : String.valueOf(fileMeta.get("path"));
        if (filePath == null || filePath.isEmpty()) {
            String message = "File " + fileId + " has no valid path in metadata";
            markParseFailure(kbId, fileId, operatorId, message, processingTaskId, processingOwner, hasOwner);
            throw new IllegalArgumentException(message);
        }

        try {
            Map<String, Object> params = KbUtils.resolveProcessingParams(
                    additionalParamsOf(kbId), toMap(fileMeta.get("processing_params")), null);
            params.put("image_bucket", MinioStorageClient.KB_BUCKETS.get("images"));
            params.put("image_prefix", kbId + "/kb-images");

            String markdownContent = ocrService.parseDocument(filePath, params);
            String markdownFilePath = saveMarkdownToMinio(kbId, fileId, markdownContent);

            Map<String, Object> updateData = new LinkedHashMap<>();
            updateData.put("status", FileStatus.PARSED);
            updateData.put("markdown_file", markdownFilePath);
            updateData.put("error_message", null);
            updateData.put("processing_task_id", null);
            updateData.put("processing_owner", null);
            if (operatorId != null && !operatorId.isEmpty()) {
                updateData.put("updated_by", operatorId);
            }
            KnowledgeFile updated = fileRepository.updateFieldsIfStatus(
                    kbId, fileId, Set.of(FileStatus.PARSING), updateData, processingTaskId, processingOwner);
            if (updated == null) {
                throw new IllegalStateException("File processing owner was lost");
            }

            fileMeta.put("status", FileStatus.PARSED);
            fileMeta.put("markdown_file", markdownFilePath);
            fileMeta.put("error", null);
            fileMeta.put("updated_at", DateTimeUtils.utcIsoformat());
            if (operatorId != null && !operatorId.isEmpty()) {
                fileMeta.put("updated_by", operatorId);
            }
            return fileMeta;
        } catch (Exception failure) {
            String errorMessage = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            log.error("Failed to parse file {}: {}", fileId, errorMessage);
            markParseFailure(kbId, fileId, operatorId, errorMessage, processingTaskId, processingOwner, hasOwner);
            throw failure;
        }
    }

    private void markParseFailure(String kbId, String fileId, String operatorId, String errorMessage,
                                  String processingTaskId, String processingOwner, boolean hasOwner) {
        Map<String, Object> updateData = new LinkedHashMap<>();
        updateData.put("status", FileStatus.ERROR_PARSING);
        updateData.put("error_message", errorMessage);
        updateData.put("processing_task_id", null);
        updateData.put("processing_owner", null);
        if (operatorId != null && !operatorId.isEmpty()) {
            updateData.put("updated_by", operatorId);
        }
        KnowledgeFile updated = fileRepository.updateFieldsIfStatus(
                kbId, fileId, Set.of(FileStatus.PARSING), updateData, processingTaskId, processingOwner);
        if (updated == null && hasOwner) {
            throw new IllegalStateException("File processing owner was lost");
        }
    }

    /** 更新文件处理参数（对应 base.update_file_params）。 */
    public void updateFileParams(String kbId, String fileId, Map<String, Object> params,
                                 String operatorId) throws Exception {
        if (params == null || params.isEmpty()) {
            return;
        }
        Map<String, Object> fileMeta = loadFileMeta(kbId, fileId);
        Map<String, Object> currentParams = toMap(fileMeta.get("processing_params"));
        Map<String, Object> resolved = KbUtils.resolveProcessingParams(
                additionalParamsOf(kbId), currentParams, params);

        Map<String, Object> updateData = new LinkedHashMap<>();
        updateData.put("processing_params", KbUtils.sanitizeProcessingParams(resolved));
        if (operatorId != null && !operatorId.isEmpty()) {
            updateData.put("updated_by", operatorId);
        }
        KnowledgeFile record = fileRepository.updateFields(fileId, updateData, kbId);
        if (record == null) {
            throw new IllegalArgumentException("File " + fileId + " not found");
        }
    }

    /**
     * 入库已解析文件（对应 implementations/milvus.py::index_file）。
     *
     * <p>状态机：parsed/error_indexing/indexed/done → indexing → indexed / error_indexing。
     * 无 markdown_file 时回落 uploaded 并报错。
     */
    public Map<String, Object> indexFile(String kbId, String fileId, String operatorId,
                                         Map<String, Object> params,
                                         String processingTaskId, String processingOwner) throws Exception {
        return runWithStatsRefresh(kbId, () -> {
            Map<String, Object> additionalParams = additionalParamsOf(kbId);
            Map<String, Object> fileMetaBefore = loadFileMeta(kbId, fileId);
            Set<String> allowedStatuses =
                    Set.of(FileStatus.PARSED, FileStatus.ERROR_INDEXING, FileStatus.INDEXED, "done");

            Map<String, Object> resolvedParams = KbUtils.resolveProcessingParams(
                    additionalParams, toMap(fileMetaBefore.get("processing_params")), params);

            Map<String, Object> claimData = new LinkedHashMap<>();
            claimData.put("status", FileStatus.INDEXING);
            claimData.put("processing_params", resolvedParams);
            claimData.put("error_message", null);
            claimData.put("processing_task_id", processingTaskId);
            claimData.put("processing_owner", processingOwner);
            if (operatorId != null && !operatorId.isEmpty()) {
                claimData.put("updated_by", operatorId);
            }

            KnowledgeFile claimed = fileRepository.updateFieldsIfStatus(
                    kbId, fileId, allowedStatuses, claimData, null, null);
            if (claimed == null) {
                Map<String, Object> currentMeta = loadFileMeta(kbId, fileId);
                String currentStatus = currentMeta.get("status") == null
                        ? null : String.valueOf(currentMeta.get("status"));
                throw new IllegalArgumentException(
                        "Cannot index file with status '" + currentStatus + "'. "
                                + "File must be parsed first (status should be one of: " + allowedStatuses + ")");
            }

            Map<String, Object> fileMeta = fileRecordToMeta(claimed);
            String markdownFile = fileMeta.get("markdown_file") == null
                    ? null : String.valueOf(fileMeta.get("markdown_file"));
            if (markdownFile == null || markdownFile.isEmpty()) {
                Map<String, Object> resetData = new LinkedHashMap<>();
                resetData.put("status", FileStatus.UPLOADED);
                resetData.put("error_message", null);
                resetData.put("processing_task_id", null);
                resetData.put("processing_owner", null);
                if (operatorId != null && !operatorId.isEmpty()) {
                    resetData.put("updated_by", operatorId);
                }
                fileRepository.updateFieldsIfStatus(
                        kbId, fileId, Set.of(FileStatus.INDEXING), resetData, processingTaskId, processingOwner);
                throw new IllegalArgumentException("File has not been parsed yet (no markdown_file)");
            }

            try {
                Map<String, Object> parserConfig = toMap(resolvedParams.get("chunk_parser_config"));
                if (parserConfig.get("embed_model_id") == null) {
                    parserConfig.put("embed_model_id", defaultEmbedModel());
                }
                resolvedParams.put("chunk_parser_config", parserConfig);

                String markdownContent = readMarkdownFromMinio(markdownFile);
                String filename = fileMeta.get("filename") == null ? null : String.valueOf(fileMeta.get("filename"));

                List<Map<String, Object>> chunks =
                        RagflowChunkDispatcher.chunkMarkdown(markdownContent, fileId, filename, resolvedParams);
                int chunkCount = chunks.size();
                long tokenCount = 0L;
                for (Map<String, Object> chunk : chunks) {
                    Object content = chunk.get("content");
                    tokenCount += RagflowNlp.countTokens(content == null ? "" : String.valueOf(content));
                }

                // 重建语义：先清空该文件既有 chunk（含向量），再写新 chunk
                deleteFileChunksOnly(kbId, fileId);

                if (!chunks.isEmpty()) {
                    embedAndStoreChunks(kbId, fileId, chunks);
                }

                Map<String, Object> updateData = new LinkedHashMap<>();
                updateData.put("status", FileStatus.INDEXED);
                updateData.put("chunk_count", chunkCount);
                updateData.put("token_count", tokenCount);
                updateData.put("error_message", null);
                updateData.put("processing_task_id", null);
                updateData.put("processing_owner", null);
                if (operatorId != null && !operatorId.isEmpty()) {
                    updateData.put("updated_by", operatorId);
                }
                KnowledgeFile updated = fileRepository.updateFieldsIfStatus(
                        kbId, fileId, Set.of(FileStatus.INDEXING), updateData, processingTaskId, processingOwner);
                if (updated == null) {
                    throw new IllegalStateException("File processing owner was lost");
                }

                fileMeta.put("status", FileStatus.INDEXED);
                fileMeta.put("chunk_count", chunkCount);
                fileMeta.put("token_count", tokenCount);
                fileMeta.put("error", null);
                fileMeta.put("updated_at", DateTimeUtils.utcIsoformat());
                if (operatorId != null && !operatorId.isEmpty()) {
                    fileMeta.put("updated_by", operatorId);
                }
                return fileMeta;
            } catch (Exception failure) {
                String errorMessage = failure.getMessage() == null ? failure.toString() : failure.getMessage();
                log.error("Failed to index file {}: {}", fileId, errorMessage);
                Map<String, Object> updateData = new LinkedHashMap<>();
                updateData.put("status", FileStatus.ERROR_INDEXING);
                updateData.put("error_message", errorMessage);
                updateData.put("processing_task_id", null);
                updateData.put("processing_owner", null);
                if (operatorId != null && !operatorId.isEmpty()) {
                    updateData.put("updated_by", operatorId);
                }
                KnowledgeFile updated = fileRepository.updateFieldsIfStatus(
                        kbId, fileId, Set.of(FileStatus.INDEXING), updateData, processingTaskId, processingOwner);
                if (updated == null && processingOwner != null) {
                    throw new IllegalStateException("File processing owner was lost");
                }
                throw failure;
            }
        });
    }

    /** 按状态集合列出文件 id（对应 manager.list_document_file_ids_by_statuses）。 */
    public List<String> listDocumentFileIdsByStatuses(String kbId, List<String> statuses,
                                                      String afterFileId, int limit) {
        return fileRepository.listFileIdsByExactStatuses(kbId, statuses, afterFileId, limit);
    }

    // ==================== 检索（aquery） ====================

    /**
     * 查询知识库，返回**原始** chunk 字典列表（对应 implementations/milvus.py 的 {@code aquery}）。
     *
     * <p>与 {@code KnowledgeBaseManager#retrieve} 的区别：本方法不做输出投影，返回 chunk 原始结构
     * （{@code content / metadata / score} 及 {@code distance}、各召回路径分数），供评测、
     * 外部知识库检索等需要原始字段的调用方使用；{@code retrieve} 在其之上套一层
     * {@code build_search_output}。参考实现的 manager 正是这样分工（retrieve = aquery + 投影）。
     *
     * <p><b>必要替换（引擎差异，逐条标注）</b>：
     * <ul>
     *   <li>向量召回：参考实现在 Milvus 集合内按 kb_id 隔离，可用 expr 过滤 file_id；本工程向量库是
     *       全局单索引（metadata 未声明索引字段，检索侧无法过滤），故过采样后内存过滤
     *       （见 {@link #vectorRecall}）。</li>
     *   <li>关键词召回：参考实现走 Milvus 稀疏向量 BM25；本工程改在 knowledge_chunks 表做
     *       kb 作用域包含匹配（见 {@link #keywordRecall}）。</li>
     *   <li>混合召回：参考实现用 Milvus {@code WeightedRanker(vector_weight, bm25_weight)}；
     *       本工程按「归一化分数加权和」融合，权重默认值（0.7 / 0.3）与参考实现一致。</li>
     *   <li>查询参数取值：{@code bool(...)} 的真值语义（{@code "false"} 字符串为真、空串为假）
     *       由 {@link #flagOf} 复现，不做字符串解析。</li>
     * </ul>
     *
     * @param queryText 查询文本
     * @param kbId      知识库 ID
     * @param agentCall 是否由智能体调用（参考实现保留该形参但实现体未使用，此处同样忽略）
     * @param config    知识库运行配置；为空时按 kbId 回源
     * @param kwargs    单次查询的临时参数（优先级高于持久化 query_options）
     */
    public List<Map<String, Object>> aquery(
            String queryText,
            String kbId,
            boolean agentCall,
            KnowledgeBaseConfig config,
            Map<String, Object> kwargs) {
        KnowledgeBaseConfig effectiveConfig = config == null ? getKbConfig(kbId) : config;

        // 合并查询参数：kwargs（临时参数）优先级高于 query_params（持久化参数）
        Map<String, Object> merged = new LinkedHashMap<>(effectiveConfig.queryOptions());
        if (kwargs != null) {
            merged.putAll(kwargs);
        }

        int finalTopK = Math.max(intOf(merged, "final_top_k", 10), 1);
        double similarityThreshold = doubleOf(merged, "similarity_threshold", 0.2);
        boolean includeDistances = flagOf(merged, "include_distances", true);
        String searchMode = strOf(merged, "search_mode", "vector").toLowerCase();
        if (!"vector".equals(searchMode) && !"keyword".equals(searchMode) && !"hybrid".equals(searchMode)) {
            searchMode = "vector";
        }
        boolean useReranker = flagOf(merged, "use_reranker", false);
        boolean useGraphRetrieval = flagOf(merged, "use_graph_retrieval", false);
        int recallTopK;
        if (useReranker || useGraphRetrieval) {
            recallTopK = Math.max(intOf(merged, "recall_top_k", 50), finalTopK);
        } else {
            recallTopK = finalTopK;
        }

        // 文件名过滤（对应 _build_file_name_expr）：null=不限；空列表=指定了但无匹配 → 必然为空
        List<String> fileIds = matchedFileIds(kbId, merged.get("file_name"));
        if (fileIds != null && fileIds.isEmpty()) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> retrievedChunks = new ArrayList<>();
        if ("vector".equals(searchMode)) {
            for (Document document : vectorRecall(queryText, kbId, fileIds, recallTopK)) {
                double similarity = document.getScore() == null ? 0.0 : document.getScore();
                if (similarity < similarityThreshold) {
                    continue;
                }
                retrievedChunks.add(fromDocument(document, similarity, null, includeDistances));
            }
            log.debug("Vector query response: {} chunks found (after similarity filtering)",
                    retrievedChunks.size());
        } else if ("keyword".equals(searchMode)) {
            int bm25TopK = Math.max(intOf(merged, "bm25_top_k", recallTopK), 1);
            for (ScoredChunk scored : keywordRecall(kbId, queryText, fileIds, bm25TopK)) {
                retrievedChunks.add(fromChunk(scored.chunk(), scored.score(), "bm25_score", includeDistances));
            }
            log.debug("Keyword query response: {} chunks found", retrievedChunks.size());
        } else {
            int bm25TopK = Math.max(intOf(merged, "bm25_top_k", recallTopK), 1);
            double vectorWeight = doubleOf(merged, "vector_weight", 0.7);
            double bm25Weight = doubleOf(merged, "bm25_weight", 0.3);

            Map<String, Map<String, Object>> fused = new LinkedHashMap<>();
            Map<String, Double> fusedScores = new LinkedHashMap<>();
            for (Document document : vectorRecall(queryText, kbId, fileIds, recallTopK)) {
                double similarity = document.getScore() == null ? 0.0 : document.getScore();
                Map<String, Object> chunk = fromDocument(document, similarity, null, includeDistances);
                String key = chunkKey(chunk);
                if (key == null) {
                    continue;
                }
                fused.put(key, chunk);
                fusedScores.put(key, vectorWeight * similarity);
            }
            for (ScoredChunk scored : keywordRecall(kbId, queryText, fileIds, bm25TopK)) {
                Map<String, Object> chunk = fromChunk(scored.chunk(), scored.score(), null, includeDistances);
                String key = chunkKey(chunk);
                if (key == null) {
                    continue;
                }
                if (fused.containsKey(key)) {
                    fusedScores.put(key, fusedScores.get(key) + bm25Weight * scored.score());
                } else {
                    fused.put(key, chunk);
                    fusedScores.put(key, bm25Weight * scored.score());
                }
            }
            List<Map.Entry<String, Double>> ranked = new ArrayList<>(fusedScores.entrySet());
            ranked.sort((left, right) -> Double.compare(right.getValue(), left.getValue()));
            for (Map.Entry<String, Double> entry : ranked) {
                double score = entry.getValue();
                if (score < similarityThreshold) {
                    continue;
                }
                Map<String, Object> chunk = fused.get(entry.getKey());
                chunk.put("score", score);
                chunk.put("hybrid_score", score);
                retrievedChunks.add(chunk);
            }
            log.debug("Hybrid query response: {} chunks found", retrievedChunks.size());
        }

        if (useGraphRetrieval) {
            KnowledgeGraphRetrieval graphRetrieval = graphRetrievalProvider.getIfAvailable();
            if (graphRetrieval != null) {
                List<Map<String, Object>> graphChunks = graphRetrieval.retrieveGraphChunks(
                        queryText, kbId, retrievedChunks, merged, effectiveConfig.embeddingModelSpec());
                if (!graphChunks.isEmpty()) {
                    double graphWeight = doubleOf(merged, "graph_weight", 1.0);
                    retrievedChunks = KnowledgeGraphRetrieval.fuseChunkRankings(
                            retrievedChunks, graphChunks, graphWeight);
                }
            }
        }

        if (retrievedChunks.isEmpty()) {
            return new ArrayList<>();
        }

        hydrateChunkSources(kbId, retrievedChunks);

        if (!useReranker) {
            return truncate(retrievedChunks, finalTopK);
        }

        // 使用重排序模型
        String rerankerModel = strOf(merged, "reranker_model", "");
        if (rerankerModel.isEmpty()) {
            throw new IllegalArgumentException(
                    "Reranker model must be specified when use_reranker=True. "
                            + "Please provide reranker_model in query parameters.");
        }

        try {
            ModelSelectors.BaseReranker reranker = modelSelectors.getReranker(rerankerModel);
            long rerankStart = System.currentTimeMillis();
            List<String> payload = new ArrayList<>();
            payload.add(queryText);
            for (Map<String, Object> chunk : retrievedChunks) {
                payload.add(chunk.get("content") == null ? "" : String.valueOf(chunk.get("content")));
            }
            List<Double> rerankScores =
                    reranker.acomputeScore(payload, RERANK_BATCH_SIZE, RERANK_MAX_LENGTH, true);
            for (int index = 0; index < retrievedChunks.size() && index < rerankScores.size(); index++) {
                retrievedChunks.get(index).put("rerank_score", rerankScores.get(index));
            }
            // 排序键：优先 rerank_score，缺失时回落 score（对应 Python 的 sort key）
            Comparator<Map<String, Object>> order =
                    (left, right) -> Double.compare(orderScore(right), orderScore(left));
            retrievedChunks.sort(order);
            log.info("Reranking completed for {} in {}s with model {}",
                    kbId, (System.currentTimeMillis() - rerankStart) / 1000.0, rerankerModel);
        } catch (Exception exc) {
            log.error("Reranking failed: {}, falling back to vector scores", exc.getMessage());
        }

        // 统一返回结果
        return truncate(retrievedChunks, finalTopK);
    }

    /** 检索召回命中（chunk 记录 + 召回分数）。 */
    private record ScoredChunk(KnowledgeChunk chunk, double score) {}

    /**
     * 向量召回（对应 Milvus {@code collection.search} 的 vector 分支）。
     *
     * <p><b>必要替换（引擎差异，显式标注）</b>：参考实现在 Milvus 集合内按 kb_id 隔离、可用
     * expr 过滤 file_id；本工程向量库是全局单索引（{@code RedisVectorStore} 未声明 metadata
     * 索引字段，检索侧无法过滤），因此改为**过采样后按 metadata 过滤**：先取
     * {@code max(topK × VECTOR_RECALL_OVERSAMPLE, VECTOR_RECALL_MIN_SCAN)} 条，再按
     * {@code kb_id} 与 file_id 范围筛出前 topK 条。该替换会损失一部分召回率，但保证结果集
     * 与参考实现同口径（同一知识库、同一文件范围）。
     */
    private List<Document> vectorRecall(String queryText, String kbId, List<String> fileIds, int topK) {
        int scan = Math.max(topK * VECTOR_RECALL_OVERSAMPLE, VECTOR_RECALL_MIN_SCAN);
        SearchRequest request = SearchRequest.builder()
                .query(queryText)
                .topK(scan)
                .similarityThresholdAll()
                .build();
        List<Document> documents = embeddingModel.withSpec(
                getKbConfig(kbId).embeddingModelSpec(), () -> vectorStore.similaritySearch(request));
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<Document> matched = new ArrayList<>();
        for (Document document : documents) {
            Map<String, Object> metadata = document.getMetadata();
            if (metadata == null) {
                continue;
            }
            Object kbIdValue = metadata.get("kb_id");
            if (kbIdValue == null || !kbId.equals(String.valueOf(kbIdValue))) {
                continue;
            }
            if (fileIds != null) {
                Object fileIdValue = metadata.get("file_id");
                if (fileIdValue == null || !fileIds.contains(String.valueOf(fileIdValue))) {
                    continue;
                }
            }
            matched.add(document);
            if (matched.size() >= topK) {
                break;
            }
        }
        return matched;
    }

    /**
     * 关键词召回（对应 Milvus {@code collection.search} 的 BM25 分支）。
     *
     * <p><b>必要替换（引擎差异，显式标注）</b>：参考实现用稀疏向量 BM25 打分并支持
     * {@code drop_ratio_search}；本工程在 knowledge_chunks 表做 kb 作用域包含匹配，
     * 分数按「命中词数 / 查询词数」归一化（0~1，与 BM25 分同量纲用于加权融合）。
     */
    private List<ScoredChunk> keywordRecall(
            String kbId, String queryText, List<String> fileIds, int topK) {
        List<String> terms = keywordExtractor.extract(queryText);
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        List<KnowledgeChunk> chunks = chunkRepository.searchByKeywords(kbId, terms, fileIds, topK);
        List<ScoredChunk> scored = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            String content = chunk.getContent() == null ? "" : chunk.getContent();
            int hitCount = 0;
            for (String term : terms) {
                if (term != null && !term.isEmpty() && content.contains(term)) {
                    hitCount += 1;
                }
            }
            scored.add(new ScoredChunk(chunk, hitCount / (double) terms.size()));
        }
        return scored;
    }

    /** 从向量命中构造 chunk 结构（对应 {@code _build_chunk_from_hit}）。 */
    private static Map<String, Object> fromDocument(
            Document document, double score, String scoreField, boolean includeDistances) {
        Map<String, Object> metadata =
                document.getMetadata() == null ? Map.of() : document.getMetadata();
        return buildChunk(
                document.getText(),
                metadata.get("chunk_id"),
                metadata.get("file_id"),
                metadata.get("chunk_index"),
                score,
                scoreField,
                includeDistances,
                metadata.get("distance"));
    }

    /** 从 chunk 记录构造 chunk 结构（对应 {@code _build_chunk_from_record}）。 */
    private static Map<String, Object> fromChunk(
            KnowledgeChunk chunk, double score, String scoreField, boolean includeDistances) {
        return buildChunk(
                chunk.getContent(),
                chunk.getChunkId(),
                chunk.getFileId(),
                chunk.getChunkIndex(),
                score,
                scoreField,
                includeDistances,
                null);
    }

    /**
     * 知识库统一返回的 chunk 结构。
     *
     * <p>键与顺序对齐 {@code _build_chunk_from_hit}：metadata 为
     * {@code source / chunk_id / file_id / chunk_index}，可选 {@code score_field}（bm25_score /
     * hybrid_score / graph_score）与 {@code distance}（受 {@code include_distances} 控制）。
     */
    private static Map<String, Object> buildChunk(
            String content,
            Object chunkId,
            Object fileId,
            Object chunkIndex,
            double score,
            String scoreField,
            boolean includeDistances,
            Object distance) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "未知来源");
        metadata.put("chunk_id", chunkId);
        metadata.put("file_id", fileId);
        metadata.put("chunk_index", chunkIndex);

        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("content", content == null ? "" : content);
        chunk.put("metadata", metadata);
        chunk.put("score", score);
        if (scoreField != null && !scoreField.isBlank()) {
            chunk.put(scoreField, score);
        }
        if (includeDistances) {
            chunk.put("distance", distance);
        }
        return chunk;
    }

    /** 融合去重键：取 {@code metadata.chunk_id}，缺失时回落 {@code file_id:chunk_index}。 */
    @SuppressWarnings("unchecked")
    private static String chunkKey(Map<String, Object> chunk) {
        Object rawMetadata = chunk.get("metadata");
        if (!(rawMetadata instanceof Map<?, ?>)) {
            return null;
        }
        Map<String, Object> metadata = (Map<String, Object>) rawMetadata;
        Object chunkId = metadata.get("chunk_id");
        if (chunkId != null) {
            return String.valueOf(chunkId);
        }
        Object fileId = metadata.get("file_id");
        Object chunkIndex = metadata.get("chunk_index");
        if (fileId == null && chunkIndex == null) {
            return null;
        }
        return fileId + ":" + chunkIndex;
    }

    /**
     * 文件名过滤命中集合（对应 {@code _build_file_name_expr}）。
     *
     * <p>必要替换（引擎差异）：参考实现返回 Milvus 表达式字符串（{@code file_id == "x"} /
     * {@code file_id in [...]} / 无匹配时的 {@code __no_matching_file__} 哨兵），本工程检索侧
     * 无法下推表达式，故改返回命中的 file_id 列表：{@code null} 表示未指定 file_name（不过滤），
     * 空列表表示指定了但无匹配（调用方据此直接返回空结果，与哨兵表达式等价）。
     */
    private List<String> matchedFileIds(String kbId, Object fileName) {
        if (fileName == null || String.valueOf(fileName).isEmpty()) {
            return null;
        }
        return fileRepository.listFileIdsByFilenameContains(
                kbId, String.valueOf(fileName), KB_FILE_SEARCH_SCAN_LIMIT);
    }

    /**
     * 回填 chunk 来源文件名（对应 {@code _hydrate_chunk_sources}）。
     *
     * <p>优先委托图谱检索组件内的同一实现；该组件不可用时（例如图谱相关 bean 因依赖缺失未装配）
     * 退回本地等价逻辑，保证检索来源不因图谱未部署而缺失。
     */
    private void hydrateChunkSources(String kbId, List<Map<String, Object>> chunks) {
        KnowledgeGraphRetrieval graphRetrieval = graphRetrievalProvider.getIfAvailable();
        if (graphRetrieval != null) {
            graphRetrieval.hydrateChunkSources(kbId, chunks);
            return;
        }
        List<String> fileIds = new ArrayList<>();
        for (Map<String, Object> chunk : chunks) {
            Object rawMetadata = chunk.get("metadata");
            if (!(rawMetadata instanceof Map<?, ?> metadata)) {
                continue;
            }
            Object fileId = metadata.get("file_id");
            if (fileId == null || String.valueOf(fileId).isEmpty()) {
                continue;
            }
            String text = String.valueOf(fileId);
            if (!fileIds.contains(text)) {
                fileIds.add(text);
            }
        }
        if (fileIds.isEmpty()) {
            return;
        }
        fileIds.sort(String::compareTo);

        Map<String, String> filenames = fileRepository.getFilenamesByFileIds(kbId, fileIds);
        for (Map<String, Object> chunk : chunks) {
            Object rawMetadata = chunk.get("metadata");
            if (!(rawMetadata instanceof Map<?, ?>)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) rawMetadata;
            Object fileId = metadata.get("file_id");
            String name = fileId == null ? null : filenames.get(String.valueOf(fileId));
            metadata.put("source", (name == null || name.isEmpty()) ? "未知来源" : name);
        }
    }

    /** 取前 limit 条（对应 Python 的 {@code chunks[:limit]}，返回新列表）。 */
    private static List<Map<String, Object>> truncate(List<Map<String, Object>> chunks, int limit) {
        if (chunks.size() <= limit) {
            return chunks;
        }
        return new ArrayList<>(chunks.subList(0, limit));
    }

    /** 重排后的排序键：优先 rerank_score，缺失时回落 score。 */
    private static double orderScore(Map<String, Object> chunk) {
        Object rerankScore = chunk.get("rerank_score");
        if (rerankScore instanceof Number number) {
            return number.doubleValue();
        }
        Object score = chunk.get("score");
        return score instanceof Number number ? number.doubleValue() : 0.0;
    }

    /**
     * 复现 Python {@code bool(params.get(key, default))} 的取值语义。
     *
     * <p>注意：Python 里非空字符串一律为真，故 {@code "false"} 也是真——不做字符串解析，
     * 避免出现与参考实现相反的行为。
     */
    private static boolean flagOf(Map<String, Object> params, String key, boolean defaultValue) {
        if (!params.containsKey(key)) {
            return defaultValue;
        }
        Object value = params.get(key);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value instanceof CharSequence text) {
            return text.length() > 0;
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    /** 整数取值（对应 Python {@code int(params.get(key, default))}，非法值回落默认值）。 */
    private static int intOf(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return (int) number.doubleValue();
        }
        try {
            return (int) Double.parseDouble(String.valueOf(value).strip());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /** 浮点取值（对应 Python {@code float(params.get(key, default))}，非法值回落默认值）。 */
    private static double doubleOf(Map<String, Object> params, String key, double defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).strip());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /** 字符串取值（键缺失或空串时回落默认值，与 Python {@code params.get(key, default)} 等价）。 */
    private static String strOf(Map<String, Object> params, String key, String defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? defaultValue : text;
    }

    // ==================== 向量入库 ====================

    /** 分批嵌入并写入向量库 + chunk 表（对应 _embed_and_store_chunks + _insert_chunks_to_stores）。 */
    private void embedAndStoreChunks(String kbId, String fileId, List<Map<String, Object>> chunks) {
        int batchSize = Math.max(CHUNK_EMBED_BATCH_SIZE, 1);
        for (int start = 0; start < chunks.size(); start += batchSize) {
            List<Map<String, Object>> batch = chunks.subList(start, Math.min(start + batchSize, chunks.size()));
            List<Document> documents = new ArrayList<>();
            for (Map<String, Object> chunk : batch) {
                String content = chunk.get("content") == null ? "" : String.valueOf(chunk.get("content"));
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("file_id", fileId);
                metadata.put("kb_id", kbId);
                metadata.put("chunk_id", chunk.get("chunk_id"));
                metadata.put("chunk_index", chunk.get("chunk_index"));
                // 向量键必须用 chunk_id（而不是让框架生成 UUID）：RedisVectorStore 的 key 是
                // prefix + document.getId()，而删除侧（deleteFileChunksOnly）按 chunk_id 删
                // —— 用 UUID 会导致「删不掉 + 重入库累积孤儿向量」（2026-09-22 实测：同一文件
                // 先后 32/74 块两次入库，Redis 里留下 106 条）。
                documents.add(new Document(
                        String.valueOf(chunk.get("chunk_id")), content, metadata));
            }
            try {
                embeddingModel.withSpec(getKbConfig(kbId).embeddingModelSpec(), () -> {
                    vectorStore.add(documents);
                    return null;
                });
            } catch (Exception exception) {
                // 双写失败即回滚已写 chunk（对应参考实现的双写回滚语义）
                log.error("Chunk vector write failed for file {}, rolling back chunks: {}",
                        fileId, exception.getMessage());
                chunkRepository.deleteByFileId(fileId);
                throw exception;
            }
            chunkRepository.batchUpsert(buildChunkRecords(kbId, fileId, batch));
        }
    }

    /** chunk 记录投影（对应 _build_chunk_pg_records）。 */
    private List<Map<String, Object>> buildChunkRecords(String kbId, String fileId,
                                                        List<Map<String, Object>> chunks) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> chunk : chunks) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("chunk_id", chunk.get("chunk_id"));
            record.put("file_id", fileId);
            record.put("kb_id", kbId);
            record.put("chunk_index", chunk.get("chunk_index"));
            record.put("content", chunk.get("content"));
            record.put("start_char_pos", chunk.get("start_char_pos"));
            record.put("end_char_pos", chunk.get("end_char_pos"));
            record.put("start_token_pos", chunk.get("start_token_pos"));
            record.put("end_token_pos", chunk.get("end_token_pos"));
            record.put("graph_indexed", Boolean.TRUE.equals(chunk.get("graph_indexed")));
            record.put("ent_ids", chunk.get("ent_ids"));
            record.put("tags", chunk.get("tags"));
            record.put("extraction_result", chunk.get("extraction_result"));
            records.add(record);
        }
        return records;
    }

    /** 删除单个文件的全部 chunk 与向量（对应 delete_file_chunks_only）。 */
    public void deleteFileChunksOnly(String kbId, String fileId) {
        // 已建图谱的文件先清图谱痕迹（Neo4j 边/孤儿实体 + 图谱向量），失败即抛出以便任务重试
        if (chunkRepository.countGraphIndexedByFileId(fileId) > 0) {
            MilvusGraphService graphService = graphServiceProvider.getIfAvailable();
            if (graphService == null) {
                throw new IllegalStateException(
                        "文件已构建图谱但图谱服务不可用，无法删除图谱数据: file_id=" + fileId);
            }
            graphService.deleteFileGraph(kbId, fileId);
        }
        List<KnowledgeChunk> existing = chunkRepository.listByFileId(fileId);
        if (!existing.isEmpty()) {
            List<String> vectorIds = new ArrayList<>();
            for (KnowledgeChunk chunk : existing) {
                vectorIds.add(chunk.getChunkId());
            }
            try {
                vectorStore.delete(vectorIds);
            } catch (Exception exception) {
                log.warn("Failed to delete vectors for file {}: {}", fileId, exception.getMessage());
            }
        }
        chunkRepository.deleteByFileId(fileId);
    }

    // ==================== 元数据转换 ====================

    /** 文件记录 → 元数据（对应 base._file_record_to_meta）。 */
    public Map<String, Object> fileRecordToMeta(KnowledgeFile record) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("file_id", record.getFileId());
        meta.put("kb_id", record.getKbId());
        meta.put("parent_id", record.getParentId());
        meta.put("filename", record.getFilename());
        meta.put("file_type", record.getFileType());
        meta.put("path", record.getPath());
        meta.put("markdown_file", record.getMarkdownFile());
        meta.put("status", record.getStatus());
        meta.put("content_hash", record.getContentHash());
        meta.put("size", record.getFileSize());
        meta.put("chunk_count", record.getChunkCount() == null ? 0 : record.getChunkCount());
        meta.put("token_count", record.getTokenCount() == null ? 0L : record.getTokenCount());
        meta.put("content_type", record.getContentType());
        meta.put("processing_params", KbUtils.sanitizeProcessingParams(parseJsonMap(record.getProcessingParams())));
        meta.put("is_folder", record.getIsFolder());
        meta.put("error", record.getErrorMessage());
        meta.put("created_by", record.getCreatedBy());
        meta.put("updated_by", record.getUpdatedBy());
        meta.put("created_at", record.getCreatedAt() == null ? null : DateTimeUtils.formatUtcDatetime(record.getCreatedAt()));
        meta.put("updated_at", record.getUpdatedAt() == null ? null : DateTimeUtils.formatUtcDatetime(record.getUpdatedAt()));
        meta.put("original_filename", record.getOriginalFilename());
        meta.put("minio_url", record.getMinioUrl());
        return meta;
    }

    /** 元数据 → 记录可写字段（对应 base._file_meta_to_record_data）。 */
    public static Map<String, Object> fileMetaToRecordData(Map<String, Object> meta) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kb_id", meta.get("kb_id"));
        data.put("parent_id", meta.get("parent_id"));
        data.put("filename", meta.get("filename") == null ? "" : meta.get("filename"));
        data.put("original_filename", meta.get("original_filename"));
        data.put("file_type", meta.get("file_type"));
        data.put("path", meta.get("path"));
        data.put("minio_url", meta.get("minio_url"));
        data.put("markdown_file", meta.get("markdown_file"));
        data.put("status", meta.get("status"));
        data.put("content_hash", meta.get("content_hash"));
        data.put("file_size", meta.get("size"));
        data.put("chunk_count", RepoValues.toInt(meta.get("chunk_count")) == null ? 0 : meta.get("chunk_count"));
        data.put("token_count", RepoValues.toLong(meta.get("token_count")) == null ? 0L : meta.get("token_count"));
        data.put("content_type", meta.get("content_type"));
        data.put("processing_params", KbUtils.sanitizeProcessingParams(toMap(meta.get("processing_params"))));
        data.put("is_folder", meta.get("is_folder") == null ? Boolean.FALSE : meta.get("is_folder"));
        data.put("error_message", meta.get("error"));
        data.put("created_by", meta.get("created_by") == null ? null : String.valueOf(meta.get("created_by")));
        data.put("updated_by", meta.get("updated_by") == null ? null : String.valueOf(meta.get("updated_by")));
        return data;
    }

    /** 读取文件元数据（对应 base._load_file_meta）。 */
    public Map<String, Object> loadFileMeta(String kbId, String fileId) {
        KnowledgeFile record = fileRepository.getByFileId(fileId);
        if (record == null || !kbId.equals(record.getKbId())) {
            throw new IllegalArgumentException("File " + fileId + " not found");
        }
        return fileRecordToMeta(record);
    }

    /** 持久化文件元数据（对应 base._persist_file_meta）。 */
    private void persistFileMeta(String fileId, Map<String, Object> meta) {
        fileRepository.upsert(fileId, fileMetaToRecordData(meta));
    }

    // ==================== 对象存储 ====================

    /** 保存 markdown 到对象存储并返回 URL（对应 base._save_markdown_to_minio）。 */
    private String saveMarkdownToMinio(String kbId, String fileId, String content) {
        String bucketName = MinioStorageClient.KB_BUCKETS.get("parsed");
        String objectName = kbId + "/parsed/" + fileId + ".md";
        return minioStorageClient
                .uploadFile(bucketName, objectName, content.getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/markdown")
                .url();
    }

    /** 读取对象存储中的 markdown（对应 base._read_markdown_from_minio）。 */
    private String readMarkdownFromMinio(String filePath) {
        if (filePath == null || !KbUtils.isMinioUrl(filePath)) {
            throw new IllegalArgumentException("Invalid MinIO path format: " + filePath);
        }
        String[] parsed = KbUtils.parseMinioUrl(filePath);
        byte[] bytes = minioStorageClient.downloadFile(parsed[0], parsed[1]);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 全局默认 embedding 模型（对应 system_options 的 embed_model）。 */
    private String defaultEmbedModel() {
        Object value = optionsService.get(OptionsService.SYSTEM_OPTIONS).get("embed_model");
        return value == null ? null : String.valueOf(value);
    }

    // ==================== 文件树 / 下载 ====================

    /** 按父目录递归列出文件条目（对应 base._list_knowledge_children）。 */
    public List<Map<String, Object>> listKnowledgeChildren(
            String kbId, String parentId, boolean recursive, boolean filesOnly) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (KnowledgeFile record : fileRepository.listChildren(kbId, parentId)) {
            Map<String, Object> meta = fileRecordToMeta(record);
            if (!filesOnly || !Boolean.TRUE.equals(meta.get("is_folder"))) {
                entries.add(KnowledgeFileViews.knowledgeFileEntry(kbId, record.getFileId(), meta));
            }
            if (recursive && Boolean.TRUE.equals(meta.get("is_folder"))) {
                entries.addAll(listKnowledgeChildren(kbId, record.getFileId(), true, filesOnly));
            }
        }
        return KnowledgeFileViews.sortFileEntries(entries);
    }

    /** 文件树（对应 base.list_file_tree）。 */
    public Map<String, Object> listFileTree(String kbId, String parentId, boolean recursive, boolean filesOnly) {
        if (parentId != null && !parentId.isEmpty()) {
            Map<String, Object> parentMeta = loadFileMeta(kbId, parentId);
            if (!Boolean.TRUE.equals(parentMeta.get("is_folder"))) {
                throw new IllegalArgumentException("Parent is not a folder");
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entries", listKnowledgeChildren(kbId, parentId, recursive, filesOnly));
        result.put("readonly", true);
        return result;
    }

    /** 文件下载（对应 base.get_file_download）。 */
    public Map<String, Object> getFileDownload(String kbId, String fileId, String variant) {
        Map<String, Object> fileMeta = loadFileMeta(kbId, fileId);
        if (Boolean.TRUE.equals(fileMeta.get("is_folder"))) {
            throw new IllegalArgumentException("Cannot download a folder");
        }
        String normalizedVariant = variant == null || variant.isEmpty() ? "original" : variant;
        if (!Set.of("original", "parsed").contains(normalizedVariant)) {
            throw new IllegalArgumentException("Unsupported download variant");
        }
        String filename = firstText(fileMeta.get("filename"), fileMeta.get("original_filename"), fileId);
        if ("parsed".equals(normalizedVariant)) {
            Object markdownFile = fileMeta.get("markdown_file");
            if (markdownFile == null || String.valueOf(markdownFile).isEmpty()) {
                throw new IllegalArgumentException("文件尚未生成解析结果");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("filename", filename + ".parsed.md");
            result.put("content", readMinioBytes(String.valueOf(markdownFile)));
            result.put("media_type", "text/markdown; charset=utf-8");
            return result;
        }
        String originalPath = KnowledgeFileViews.originalFilePath(fileMeta);
        if (originalPath == null || originalPath.isEmpty()) {
            throw new IllegalArgumentException("文件没有可下载的原始内容");
        }
        String mediaType = fileMeta.get("content_type") == null
                ? "application/octet-stream" : String.valueOf(fileMeta.get("content_type"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filename", filename);
        result.put("content", readMinioBytes(originalPath));
        result.put("media_type", mediaType);
        return result;
    }

    /** 读取对象存储字节（对应 base._read_minio_bytes）。 */
    public byte[] readMinioBytes(String filePath) {
        if (filePath == null || !KbUtils.isMinioUrl(filePath)) {
            throw new IllegalArgumentException("Invalid MinIO path format: " + filePath);
        }
        String[] parsed = KbUtils.parseMinioUrl(filePath);
        return minioStorageClient.downloadFile(parsed[0], parsed[1]);
    }

    // ==================== 文件夹 / 移动 / 删除 ====================

    /** 创建文件夹（对应 base.create_folder）。 */
    public Map<String, Object> createFolder(String kbId, String folderName, String parentId, String operatorId) {
        if (parentId != null && !parentId.isEmpty()) {
            Map<String, Object> parentMeta = loadFileMeta(kbId, parentId);
            if (!Boolean.TRUE.equals(parentMeta.get("is_folder"))) {
                throw new IllegalArgumentException("Parent is not a folder");
            }
        }
        String folderId = "folder-" + java.util.UUID.randomUUID();
        Map<String, Object> folderMeta = new LinkedHashMap<>();
        folderMeta.put("file_id", folderId);
        folderMeta.put("filename", folderName);
        folderMeta.put("is_folder", true);
        folderMeta.put("parent_id", parentId);
        folderMeta.put("kb_id", kbId);
        folderMeta.put("created_at", DateTimeUtils.utcIsoformat());
        folderMeta.put("status", "done");
        folderMeta.put("path", folderName);
        folderMeta.put("file_type", "folder");
        folderMeta.put("created_by", operatorId);
        fileRepository.upsert(folderId, fileMetaToRecordData(folderMeta));
        return folderMeta;
    }

    /** 重命名文件夹（对应 base.rename_folder）。 */
    public Map<String, Object> renameFolder(String kbId, String folderId, String folderName) {
        String normalizedName = folderName == null ? "" : folderName.strip();
        if (normalizedName.isEmpty()) {
            throw new IllegalArgumentException("Folder name cannot be empty");
        }
        if (normalizedName.contains("/") || normalizedName.contains("\\")) {
            throw new IllegalArgumentException("Folder name cannot contain path separators");
        }
        Map<String, Object> meta = loadFileMeta(kbId, folderId);
        if (!Boolean.TRUE.equals(meta.get("is_folder"))) {
            throw new IllegalArgumentException("Document is not a folder");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("filename", normalizedName);
        data.put("path", normalizedName);
        KnowledgeFile record = fileRepository.updateFields(folderId, data, kbId);
        if (record == null) {
            throw new IllegalArgumentException("File " + folderId + " not found");
        }
        return fileRecordToMeta(record);
    }

    /** 移动文件/文件夹（对应 base.move_file）。 */
    public Map<String, Object> moveFile(String kbId, String fileId, String newParentId) {
        try (AutoCloseable ignored = fileRepository.lockFileTree(kbId)) {
            Map<String, Object> meta = loadFileMeta(kbId, fileId);
            boolean isFolder = Boolean.TRUE.equals(meta.get("is_folder"));
            if (isFolder && newParentId != null && !newParentId.isEmpty()) {
                if (newParentId.equals(fileId)) {
                    throw new IllegalArgumentException("Cannot move a folder into itself");
                }
                String current = newParentId;
                while (current != null && !current.isEmpty()) {
                    Map<String, Object> parentMeta = loadFileMeta(kbId, current);
                    if (current.equals(newParentId) && !Boolean.TRUE.equals(parentMeta.get("is_folder"))) {
                        throw new IllegalArgumentException("Parent is not a folder");
                    }
                    if (current.equals(fileId)) {
                        throw new IllegalArgumentException("Cannot move a folder into its own subfolder");
                    }
                    Object next = parentMeta.get("parent_id");
                    current = next == null ? null : String.valueOf(next);
                }
            } else if (newParentId != null && !newParentId.isEmpty()) {
                Map<String, Object> parentMeta = loadFileMeta(kbId, newParentId);
                if (!Boolean.TRUE.equals(parentMeta.get("is_folder"))) {
                    throw new IllegalArgumentException("Parent is not a folder");
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("parent_id", newParentId);
            KnowledgeFile record = fileRepository.updateFields(fileId, data, kbId);
            if (record == null) {
                throw new IllegalArgumentException("File " + fileId + " not found");
            }
            return fileRecordToMeta(record);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure.getMessage(), failure);
        }
    }

    /** 删除文件（对应 milvus.delete_file）。 */
    public void deleteFile(String kbId, String fileId) {
        deleteFileChunksOnly(kbId, fileId);
        Map<String, Object> reset = new LinkedHashMap<>();
        reset.put("chunk_count", 0);
        reset.put("token_count", 0L);
        fileRepository.updateFields(fileId, reset, kbId);
        fileRepository.delete(fileId);
    }

    /** 递归删除文件夹（对应 base.delete_folder）。 */
    public void deleteFolder(String kbId, String folderId) {
        for (KnowledgeFile child : fileRepository.listChildren(kbId, folderId)) {
            if (Boolean.TRUE.equals(child.getIsFolder())) {
                deleteFolder(kbId, child.getFileId());
            } else {
                deleteFile(kbId, child.getFileId());
            }
        }
        deleteFile(kbId, folderId);
    }

    // ==================== 文件信息 ====================

    /** 文件基本信息（对应 milvus.get_file_basic_info）。 */
    public Map<String, Object> getFileBasicInfo(String kbId, String fileId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("meta", loadFileMeta(kbId, fileId));
        return result;
    }

    /** 文件内容（对应 milvus._get_file_content_from_meta）。 */
    public Map<String, Object> getFileContent(String kbId, String fileId) {
        Map<String, Object> fileMeta = loadFileMeta(kbId, fileId);
        return getFileContentFromMeta(fileId, fileMeta);
    }

    private Map<String, Object> getFileContentFromMeta(String fileId, Map<String, Object> fileMeta) {
        Map<String, Object> contentInfo = new LinkedHashMap<>();
        List<Map<String, Object>> lines = new ArrayList<>();
        try {
            for (KnowledgeChunk chunk : chunkRepository.listByFileId(fileId)) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("id", chunk.getChunkId());
                line.put("content", chunk.getContent());
                line.put("chunk_order_index", chunk.getChunkIndex());
                line.put("start_char_pos", chunk.getStartCharPos());
                line.put("end_char_pos", chunk.getEndCharPos());
                line.put("start_token_pos", chunk.getStartTokenPos());
                line.put("end_token_pos", chunk.getEndTokenPos());
                line.put("graph_indexed", chunk.getGraphIndexed());
                line.put("ent_ids", chunk.getEntIds());
                line.put("tags", chunk.getTags());
                line.put("extraction_result", chunk.getExtractionResult());
                lines.add(line);
            }
        } catch (Exception exception) {
            log.error("Failed to get file content from database: {}", exception.getMessage());
        }
        if (lines.isEmpty()) {
            log.warn("No chunks found in database for file {}, file may not have been indexed", fileId);
        }
        contentInfo.put("lines", lines);
        Object markdownFile = fileMeta.get("markdown_file");
        if (markdownFile != null && !String.valueOf(markdownFile).isEmpty()) {
            try {
                contentInfo.put("content", readMarkdownFromMinio(String.valueOf(markdownFile)));
            } catch (Exception exception) {
                log.error("Failed to read markdown file for {}: {}", fileId, exception.getMessage());
            }
        }
        return contentInfo;
    }

    /** 文件完整信息（对应 milvus.get_file_info）。 */
    public Map<String, Object> getFileInfo(String kbId, String fileId) {
        Map<String, Object> fileMeta = loadFileMeta(kbId, fileId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("meta", fileMeta);
        result.putAll(getFileContentFromMeta(fileId, fileMeta));
        return result;
    }

    /** 按行窗口打开解析后的 Markdown（对应 base.open_file_content）。 */
    public Map<String, Object> openFileContent(String kbId, String fileId, int offset, int limit) {
        Map<String, Object> fileMeta = loadFileMeta(kbId, fileId);
        Object markdownFile = fileMeta.get("markdown_file");
        if (markdownFile == null || String.valueOf(markdownFile).isEmpty()) {
            throw new IllegalArgumentException("文件尚未生成解析结果");
        }
        String content = readMarkdownFromMinio(String.valueOf(markdownFile));
        return KnowledgeFileViews.buildOpenFileWindow(content, offset, limit);
    }

    /** 在文件内按关键词/正则定位（对应 base.find_file_content）。 */
    public Map<String, Object> findFileContent(String kbId, String fileId, List<String> patterns,
                                               boolean useRegex, boolean caseSensitive,
                                               int maxWindows, int windowSize) {
        Map<String, Object> fileMeta = loadFileMeta(kbId, fileId);
        Object markdownFile = fileMeta.get("markdown_file");
        if (markdownFile == null || String.valueOf(markdownFile).isEmpty()) {
            throw new IllegalArgumentException("文件尚未生成解析结果");
        }
        String content = readMarkdownFromMinio(String.valueOf(markdownFile));
        return KnowledgeFileViews.buildFindFileWindows(
                content, patterns, useRegex, caseSensitive, maxWindows, windowSize);
    }

    // ==================== 统计修复 / 资源清理 ====================

    /** 修复缺失的 chunk/token/size 统计（对应 base.repair_missing_file_stats）。 */
    public Map<String, Object> repairMissingFileStats(String kbId) {
        long scannedFiles = 0;
        long scannedIndexedFiles = 0;
        long skippedFileCount = 0;
        long scannedTokenFiles = 0;
        long updatedFiles = 0;
        long updatedChunkFiles = 0;
        long updatedTokenFiles = 0;
        long updatedSizeFiles = 0;

        String afterFileId = null;
        while (true) {
            List<KnowledgeFile> records = fileRepository.listByKbIdAfter(kbId, afterFileId, 500, true);
            if (records.isEmpty()) {
                break;
            }
            afterFileId = records.get(records.size() - 1).getFileId();

            List<KnowledgeFile> indexedRecords = new ArrayList<>();
            for (KnowledgeFile record : records) {
                if (shouldRepairFileStats(record.getStatus())) {
                    indexedRecords.add(record);
                }
            }
            List<String> indexedFileIds = new ArrayList<>();
            for (KnowledgeFile record : indexedRecords) {
                indexedFileIds.add(record.getFileId());
            }
            Set<String> indexedFileIdSet = new java.util.HashSet<>(indexedFileIds);
            Map<String, Integer> chunkCounts = chunkRepository.countByFileIds(indexedFileIds);

            List<String> tokenFileIds = new ArrayList<>();
            for (KnowledgeFile record : indexedRecords) {
                long tokenCount = record.getTokenCount() == null ? 0L : record.getTokenCount();
                if (tokenCount <= 0) {
                    tokenFileIds.add(record.getFileId());
                }
            }
            Map<String, Long> tokenCounts = new LinkedHashMap<>();
            for (String fileId : tokenFileIds) {
                tokenCounts.put(fileId, 0L);
            }
            for (KnowledgeChunk chunk : chunkRepository.listByFileIds(tokenFileIds)) {
                String content = chunk.getContent() == null ? "" : chunk.getContent();
                tokenCounts.merge(chunk.getFileId(), (long) RagflowNlp.countTokens(content), Long::sum);
            }
            Map<String, Long> sizeUpdates = fillMissingFileSizesForRecords(records);

            scannedFiles += records.size();
            scannedIndexedFiles += indexedFileIds.size();
            skippedFileCount += records.size() - indexedFileIds.size();
            scannedTokenFiles += tokenFileIds.size();

            for (KnowledgeFile record : records) {
                String fileId = record.getFileId();
                Map<String, Object> updateData = new LinkedHashMap<>();
                if (!indexedFileIdSet.contains(fileId)) {
                    if (record.getChunkCount() != null && record.getChunkCount() != 0) {
                        updateData.put("chunk_count", 0);
                        updatedChunkFiles++;
                    }
                    if (record.getTokenCount() != null && record.getTokenCount() != 0L) {
                        updateData.put("token_count", 0L);
                        updatedTokenFiles++;
                    }
                } else {
                    int nextChunkCount = chunkCounts.getOrDefault(fileId, 0);
                    if (record.getChunkCount() == null || record.getChunkCount() != nextChunkCount) {
                        updateData.put("chunk_count", nextChunkCount);
                        updatedChunkFiles++;
                    }
                    if (tokenCounts.containsKey(fileId)) {
                        long nextTokenCount = tokenCounts.get(fileId);
                        if (record.getTokenCount() == null || record.getTokenCount() != nextTokenCount) {
                            updateData.put("token_count", nextTokenCount);
                            updatedTokenFiles++;
                        }
                    }
                }
                if (sizeUpdates.containsKey(fileId)) {
                    updateData.put("file_size", sizeUpdates.get(fileId));
                    updatedSizeFiles++;
                }
                if (!updateData.isEmpty()) {
                    updatedFiles++;
                    fileRepository.updateFields(fileId, updateData, kbId);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("scanned_files", scannedFiles);
        result.put("scanned_indexed_files", scannedIndexedFiles);
        result.put("skipped_unindexed_files", skippedFileCount);
        result.put("scanned_token_files", scannedTokenFiles);
        result.put("updated_files", updatedFiles);
        result.put("updated_chunk_files", updatedChunkFiles);
        result.put("updated_token_files", updatedTokenFiles);
        result.put("updated_size_files", updatedSizeFiles);
        return result;
    }

    /** 是否需要修复统计（对应 base._should_repair_file_stats）。 */
    private static boolean shouldRepairFileStats(String status) {
        return status != null && Set.of(FileStatus.INDEXED, "done").contains(status);
    }

    /** 为缺失 size 的记录从 MinIO 补全大小（对应 base._fill_missing_file_sizes_for_records）。 */
    private Map<String, Long> fillMissingFileSizesForRecords(List<KnowledgeFile> records) {
        Map<String, Long> updates = new LinkedHashMap<>();
        for (KnowledgeFile record : records) {
            Long size = record.getFileSize();
            if (size != null && size > 0) {
                continue;
            }
            String filePath = record.getMinioUrl() != null ? record.getMinioUrl() : record.getPath();
            if (filePath == null || !KbUtils.isMinioUrl(filePath)) {
                continue;
            }
            try {
                String[] parsed = KbUtils.parseMinioUrl(filePath);
                Long statSize = minioStorageClient.statFile(parsed[0], parsed[1]);
                if (statSize != null) {
                    updates.put(record.getFileId(), statSize);
                }
            } catch (Exception exception) {
                log.warn("Failed to fill missing size for {}: {}", record.getFileId(), exception.getMessage());
            }
        }
        return updates;
    }

    /** 清理知识库关联的文件与存储资源（对应 milvus.cleanup_database_resources）。 */
    public Map<String, Object> cleanupDatabaseResources(String kbId) {
        // 1) 删除文件元数据中记录的 MinIO 文件 + 对应解析产物
        String afterFileId = null;
        while (true) {
            List<KnowledgeFile> records = fileRepository.listByKbIdAfter(kbId, afterFileId, 500, false);
            if (records.isEmpty()) {
                break;
            }
            afterFileId = records.get(records.size() - 1).getFileId();
            for (KnowledgeFile record : records) {
                String filePath = record.getMinioUrl() != null ? record.getMinioUrl() : record.getPath();
                if (filePath != null && KbUtils.isMinioUrl(filePath)) {
                    try {
                        String[] parsed = KbUtils.parseMinioUrl(filePath);
                        minioStorageClient.deleteFile(parsed[0], parsed[1]);
                    } catch (Exception exception) {
                        log.warn("Failed to delete MinIO file {}: {}", filePath, exception.getMessage());
                    }
                }
                String parsedObject = kbId + "/parsed/" + record.getFileId() + ".md";
                minioStorageClient.deleteFile(MinioStorageClient.KB_BUCKETS.get("parsed"), parsedObject);
            }
        }

        // 2) 按前缀清理各凭证桶中该知识库的残留对象
        String prefix = kbId + "/";
        for (String bucketKey : List.of("parsed", "documents", "images")) {
            try {
                minioStorageClient.deleteObjectsByPrefix(MinioStorageClient.KB_BUCKETS.get(bucketKey), prefix);
            } catch (Exception exception) {
                log.warn("Failed to cleanup bucket {} prefix {}: {}", bucketKey, prefix, exception.getMessage());
            }
        }

        // 3) 删除 chunk 与文件记录（知识库主记录由 Manager 统一删除）
        chunkRepository.deleteByKbId(kbId);
        fileRepository.deleteByKbId(kbId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "删除成功");
        return result;
    }

    /**
     * 检测外部资源与主数据的不一致（对应 base.detect_data_inconsistencies 的基类语义）。
     *
     * <p>能力差异（如实标注）：参考实现基于 Milvus collection 清单比对；本工程向量库为
     * Redis VectorStore，无 collection 概念，故按「有向量/chunk 但无文件记录」的口径返回，
     * 结构保持 missing_collections/missing_files 与参考实现一致。
     */
    public Map<String, Object> detectDataInconsistencies(Set<String> knownKbIds, Set<String> managedKbIds) {
        List<Map<String, Object>> missingCollections = new ArrayList<>();
        List<Map<String, Object>> missingFiles = new ArrayList<>();
        for (String kbId : managedKbIds) {
            try {
                int chunkCount = chunkRepository.countByKbId(kbId);
                long fileCount = 0L;
                Map<String, Object> stats = fileRepository.getKbFileStats(kbId);
                if (stats.get("file_count") != null) {
                    fileCount = RepoValues.toLong(stats.get("file_count")) == null
                            ? 0L : RepoValues.toLong(stats.get("file_count"));
                }
                if (chunkCount > 0 && fileCount == 0) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("kb_id", kbId);
                    info.put("vector_count", chunkCount);
                    info.put("metadata_files_count", fileCount);
                    info.put("detected_at", DateTimeUtils.utcIsoformat());
                    missingFiles.add(info);
                }
            } catch (Exception exception) {
                log.debug("Failed to check consistency for kb {}: {}", kbId, exception.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("missing_collections", missingCollections);
        result.put("missing_files", missingFiles);
        return result;
    }

    /** 导出知识库数据（对应 base.export_data；参考实现由各类型实现，本工程暂未提供导出器）。 */
    public String exportData(String kbId, String format) {
        throw new UnsupportedOperationException("知识库数据导出尚未提供实现（export_data）");
    }

    // ==================== 工具 ====================

    private static String firstText(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isEmpty()) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    /** JSON 文本 → Map（解析失败返回空 Map）。 */
    public static Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            JSONObject parsed = JSON.parseObject(json);
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    /** 生成文件 id（对应 prepare_item_metadata 的 file_id 派生口径）。 */
    public static String newFileId(String seed) {
        return "file_" + HashUtils.hashstr(seed + (System.currentTimeMillis() / 1000.0), 6, false, null);
    }
}
