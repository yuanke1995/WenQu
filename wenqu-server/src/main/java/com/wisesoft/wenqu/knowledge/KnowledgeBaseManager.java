package com.wisesoft.wenqu.knowledge;

import com.alibaba.fastjson2.JSONObject;
import com.wisesoft.wenqu.models.KnowledgeBase;
import com.wisesoft.wenqu.models.KnowledgeFile;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.permissions.PermissionSubject;
import com.wisesoft.wenqu.permissions.ResourcePermission;
import com.wisesoft.wenqu.permissions.ResourcePermissions;
import com.wisesoft.wenqu.permissions.ShareableResource;
import com.wisesoft.wenqu.repositories.KnowledgeBaseCache;
import com.wisesoft.wenqu.repositories.KnowledgeBaseRepository;
import com.wisesoft.wenqu.repositories.KnowledgeFileRepository;
import com.wisesoft.wenqu.repositories.ModelProviderCache;
import com.wisesoft.wenqu.repositories.RepoValues;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.service.KnowledgeFolderService;
import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 知识库管理器：统一对外门面。
 *
 * <p>由参考实现的 knowledge/manager.py 中 {@code KnowledgeBaseManager} 逐方法翻译：知识库与文件的
 * 读写、权限过滤、分页列表、检索入口与一致性检测都从这里进出。
 *
 * <p>分层对应关系（参考实现 → 本工程）：
 * <ul>
 *   <li>{@code knowledge/manager.py} → 本类</li>
 *   <li>{@code knowledge/base.py} + {@code implementations/milvus.py} 的通用执行器 → {@link KnowledgeBaseRuntime}</li>
 *   <li>{@code knowledge/runtime.py} 的注册副作用 → {@link KnowledgeBaseRegistrar}</li>
 *   <li>{@code knowledge/factory.py} → {@link KnowledgeBaseFactory}</li>
 *   <li>{@code knowledge/cache.py} → {@link KnowledgeBaseCache}</li>
 *   <li>{@code knowledge/utils/security.py} → {@link KnowledgeSecurity}</li>
 * </ul>
 *
 * <p>必要替换（逐条标注）：
 * <ul>
 *   <li>异步：参考实现全为 async/await；本工程为同步阻塞调用，由任务线程与 Web 请求线程承载。
 *   <li>检索入口 {@link #aquery}/{@link #retrieve}：参考实现走 executor.aquery（向量 + 关键词 +
 *       图谱增强 + 重排，参数化检索）；本工程由 {@link KnowledgeBaseRuntime#aquery} 承载同一参数面，
 *       底层以本工程的 VectorStore 与 knowledge_chunks 召回（引擎差异在 Runtime 内逐条标注）。
 *   <li>并发：参考实现用 asyncio.gather 并行列表与统计；本工程顺序执行，语义一致。
 * </ul>
 *
 * <p>能力差异（如实标注，不谎称已支持）：
 * <ul>
 *   <li>{@code getKbQueryParamsConfig}：参考实现由各知识库类型的 {@code get_query_params_config}
 *       提供检索参数表（MilvusRetrievalConfig 字段元数据）；本工程该参数表由设置页配置承载，
 *       故返回结构（type + options）但 options 为空，不伪造参数清单。
 *   <li>{@code exportData}：参考实现由各类型实现导出器，本工程尚未提供 → 抛
 *       UnsupportedOperationException。
 *   <li>{@code detectDataInconsistencies}：向量库由 Milvus 换为 Redis VectorStore，无 collection
 *       概念，检测口径改为「有 chunk 记录但无文件记录」，返回结构保持一致。
 * </ul>
 */
@Service
public class KnowledgeBaseManager {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseManager.class);

    /** 搜索文件名时最多扫描的候选文件数（对应参考实现 KB_FILE_SEARCH_SCAN_LIMIT）。 */
    private static final int KB_FILE_SEARCH_SCAN_LIMIT = 3000;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final KnowledgeBaseRepository kbRepository;
    private final KnowledgeFileRepository fileRepository;
    private final UserRepository userRepository;
    private final KnowledgeBaseRuntime runtime;
    private final KnowledgeBaseCache kbCache;
    private final KnowledgeFolderService folderService;
    /** 模型缓存：检索参数里的 {@code reranker_model} 选项由当前重排模型清单动态供给。 */
    private final ModelProviderCache modelProviderCache;

    public KnowledgeBaseManager(
            KnowledgeBaseRepository kbRepository,
            KnowledgeFileRepository fileRepository,
            UserRepository userRepository,
            KnowledgeBaseRuntime runtime,
            KnowledgeBaseCache kbCache,
            KnowledgeFolderService folderService,
            ModelProviderCache modelProviderCache) {
        this.kbRepository = kbRepository;
        this.fileRepository = fileRepository;
        this.userRepository = userRepository;
        this.runtime = runtime;
        this.kbCache = kbCache;
        this.folderService = folderService;
        this.modelProviderCache = modelProviderCache;
    }

    /** {@code get_database_document_support} 的返回值（Java 无元组，改用 record 承载）。 */
    public record DatabaseDocumentSupport(KnowledgeBaseDetail database, boolean supportsDocuments) {}

    /** {@code search_document_files} 内部单库搜索结果。 */
    private record KbSearchResult(Map<String, Object> kb, List<KnowledgeFile> files, int total) {}

    // =============================================================================
    // 配置与执行器
    // =============================================================================

    /** 取知识库运行配置（对应 get_kb_config）。 */
    public KnowledgeBaseConfig getKbConfig(String kbId) {
        return runtime.getKbConfig(kbId);
    }

    /** 取知识库类型执行器（对应 get_kb_executor）。本工程只有一种执行器实现。 */
    public KnowledgeBaseRuntime getKbExecutor(String kbId) {
        getKbConfig(kbId);
        return runtime;
    }

    /** 移动文件或文件夹（对应 move_file）。 */
    public Map<String, Object> moveFile(String kbId, String fileId, String newParentId) {
        return runtime.moveFile(kbId, fileId, newParentId);
    }

    /** 重命名真实文件夹（对应 rename_folder）。 */
    public Map<String, Object> renameFolder(String kbId, String folderId, String folderName) {
        return runtime.renameFolder(kbId, folderId, folderName);
    }

    // =============================================================================
    // 共享配置与统计
    // =============================================================================

    /** 规范化共享配置（对应 _normalize_share_config）。 */
    public JSONObject normalizeShareConfig(JSONObject shareConfig, String userUid, Integer departmentId) {
        if (shareConfig == null) {
            JSONObject readScope = new JSONObject();
            readScope.put("access_level", "global");
            readScope.put("department_ids", new ArrayList<>());
            readScope.put("user_uids", new ArrayList<>());
            JSONObject normalized = new JSONObject();
            normalized.put("version", 2);
            normalized.put("read_scope", readScope);
            normalized.put("manage_scope", null);
            return normalized;
        }

        Object version = shareConfig.get("version");
        if (version instanceof Number number && number.intValue() == 2) {
            boolean strict = userUid != null || departmentId != null;
            JSONObject normalized = ResourcePermissions.normalizePermissionConfig(shareConfig, null, null, strict);
            JSONObject readScope = normalized.getJSONObject("read_scope");
            if (readScope == null && strict) {
                throw new IllegalArgumentException("知识库必须设置读取范围");
            }
            if (readScope != null) {
                String accessLevel = readScope.getString("access_level");
                if ("department".equals(accessLevel) && departmentId != null) {
                    Set<Integer> ids = new java.util.TreeSet<>();
                    for (Object value : readScope.getJSONArray("department_ids") == null
                            ? List.of() : readScope.getJSONArray("department_ids")) {
                        Integer id = RepoValues.toInt(value);
                        if (id != null) {
                            ids.add(id);
                        }
                    }
                    ids.add(departmentId);
                    readScope.put("department_ids", new ArrayList<>(ids));
                } else if ("user".equals(accessLevel) && userUid != null && !userUid.isEmpty()) {
                    Set<String> uids = new java.util.TreeSet<>();
                    for (Object value : readScope.getJSONArray("user_uids") == null
                            ? List.of() : readScope.getJSONArray("user_uids")) {
                        if (value != null) {
                            uids.add(String.valueOf(value));
                        }
                    }
                    uids.add(userUid);
                    readScope.put("user_uids", new ArrayList<>(uids));
                }
            }
            return normalized;
        }

        throw new IllegalArgumentException("知识库共享配置必须使用 version 2");
    }

    /** 规范化知识库聚合统计字段（对应 _normalize_database_stats）。 */
    public static Map<String, Object> normalizeDatabaseStats(Map<String, Object> stats) {
        return KnowledgeFileViews.normalizeDatabaseStats(stats);
    }

    /** 刷新并持久化知识库聚合统计（对应 _refresh_database_stats）。 */
    public Map<String, Object> refreshDatabaseStats(String kbId) {
        KnowledgeBase kb = kbRepository.refreshStats(kbId);
        if (kb == null) {
            throw new KnowledgeBaseException.KBNotFoundError("Database " + kbId + " not found");
        }
        Map<String, Object> additionalParams = RepoValues.parseObject(kb.getAdditionalParams());
        return normalizeDatabaseStats(asMap(additionalParams.get("stats")));
    }

    /** 执行文件操作并刷新统计，同时保留原始操作异常（对应 _run_with_stats_refresh）。 */
    private <T> T runWithStatsRefresh(String kbId, Callable<T> operation) throws Exception {
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
            log.error("Refresh database stats after failed operation: kb_id={}: {}", kbId, exception.getMessage());
        }
    }

    /** 将知识库记录转换为 Summary 与 Detail 共用的规范字段（对应 _database_read_fields）。 */
    private Map<String, Object> databaseReadFields(KnowledgeBase row, Map<String, Object> stats) {
        String kbType = row.getKbType() == null || row.getKbType().isEmpty() ? "milvus" : row.getKbType();
        Map<String, Object> additionalParams;
        if (KnowledgeBaseFactory.isTypeSupported(kbType)) {
            additionalParams = new LinkedHashMap<>(RepoValues.parseObject(row.getAdditionalParams()));
        } else {
            additionalParams = new LinkedHashMap<>(RepoValues.parseObject(row.getAdditionalParams()));
        }
        Object persistedStats = additionalParams.remove("stats");
        Map<String, Object> normalizedStats =
                normalizeDatabaseStats(stats != null ? stats : asMap(persistedStats));

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("kb_id", row.getKbId());
        fields.put("name", row.getName());
        fields.put("description", row.getDescription());
        fields.put("kb_type", kbType);
        fields.put("embedding_model_spec", row.getEmbeddingModelSpec());
        fields.put("llm_model_spec", row.getLlmModelSpec());
        fields.put("query_params", new LinkedHashMap<>(RepoValues.parseObject(row.getQueryParams())));
        fields.put("additional_params", additionalParams);
        fields.put("share_config", normalizeShareConfig(RepoValues.parseObject(row.getShareConfig()), null, null));
        fields.put("created_by", row.getCreatedBy());
        fields.put("created_at", row.getCreatedAt() == null ? null : row.getCreatedAt().atOffset(ZoneOffset.UTC));
        fields.putAll(normalizedStats);
        return fields;
    }

    /** 由规范字段构造摘要（对应 KnowledgeBaseSummary 的构造）。 */
    private KnowledgeBaseSummary toSummary(Map<String, Object> fields) {
        return new KnowledgeBaseSummary(
                (String) fields.get("kb_id"),
                (String) fields.get("name"),
                (String) fields.get("description"),
                (String) fields.get("kb_type"),
                (String) fields.get("embedding_model_spec"),
                (String) fields.get("llm_model_spec"),
                asMap(fields.get("query_params")),
                asMap(fields.get("additional_params")),
                asMap(fields.get("share_config")),
                (String) fields.get("created_by"),
                (java.time.OffsetDateTime) fields.get("created_at"),
                intOf(fields.get("file_count")),
                intOf(fields.get("folder_count")),
                intOf(fields.get("row_count")),
                longOf(fields.get("total_size")),
                longOf(fields.get("chunk_count")),
                longOf(fields.get("token_count")),
                longOf(fields.get("pending_parse_count")),
                longOf(fields.get("pending_index_count")),
                longOf(fields.get("processing_count")),
                fields.get("effective_permission") instanceof ResourcePermission permission ? permission : null);
    }

    // =============================================================================
    // 知识库列表与权限
    // =============================================================================

    /** 获取所有知识库摘要（对应 get_databases）。 */
    public List<KnowledgeBaseSummary> getDatabases() {
        List<KnowledgeBaseSummary> all = new ArrayList<>();
        for (KnowledgeBase row : kbRepository.getAll()) {
            String kbType = row.getKbType() == null || row.getKbType().isEmpty() ? "milvus" : row.getKbType();
            if (!KnowledgeBaseFactory.isTypeSupported(kbType)) {
                log.warn("Skip unsupported database: kb_id={}, kb_type={}", row.getKbId(), kbType);
                continue;
            }
            // 单条记录元数据不合法时只跳过该条，避免一条坏记录隐藏整个列表。
            try {
                all.add(toSummary(databaseReadFields(row, null)));
            } catch (Exception exception) {
                log.warn("Skip database with invalid metadata: kb_id={}, kb_type={}: {}",
                        row.getKbId(), kbType, exception.getMessage());
            }
        }
        return all;
    }

    /** 检查用户是否有权限访问知识库（对应 check_accessible）。 */
    public boolean checkAccessible(Map<String, Object> user, String kbId) {
        if ("superadmin".equals(user == null ? null : user.get("role"))) {
            return true;
        }
        KnowledgeBase kb = kbRepository.getByKbId(kbId);
        if (kb == null) {
            return false;
        }
        return databaseInfoAccessible(user, kb);
    }

    private static boolean databaseInfoAccessible(Map<String, Object> user, KnowledgeBase kb) {
        return ResourcePermissions.resolveKnowledgeBasePermission(
                PermissionSubject.ofMap(user), ShareableResource.of(kb)) != ResourcePermission.NONE;
    }

    /** 按 uid 获取一个可访问知识库的信息（对应 get_accessible_database_info_by_uid）。 */
    public KnowledgeBaseSummary getAccessibleDatabaseInfoByUid(String uid, String kbId) {
        String normalizedKbId = kbId == null ? "" : kbId.strip();
        if (normalizedKbId.isEmpty()) {
            return null;
        }
        for (KnowledgeBaseSummary database : getDatabasesByUid(uid)) {
            if (normalizedKbId.equals(database.kbId())) {
                return database;
            }
        }
        return null;
    }

    /** 判断知识库类型是否支持文档全文操作（对应 database_type_supports_documents）。 */
    public boolean databaseTypeSupportsDocuments(String kbType) {
        String normalizedType = kbType == null || kbType.isEmpty() ? "milvus" : kbType.toLowerCase();
        if (!KnowledgeBaseFactory.isTypeSupported(normalizedType)) {
            return false;
        }
        KnowledgeBaseFactory.KbTypeInfo info = KnowledgeBaseFactory.getKbClass(normalizedType);
        return info != null && info.supportsDocuments();
    }

    /** 返回知识库信息及其是否支持文档全文操作（对应 get_database_document_support）。 */
    public DatabaseDocumentSupport getDatabaseDocumentSupport(String kbId) {
        KnowledgeBaseDetail dbInfo = getDatabaseInfo(kbId, false);
        if (dbInfo == null) {
            return new DatabaseDocumentSupport(null, false);
        }
        return new DatabaseDocumentSupport(dbInfo, databaseTypeSupportsDocuments(dbInfo.kbType()));
    }

    /** 根据 uid 获取知识库列表（对应 get_databases_by_uid）。 */
    public List<KnowledgeBaseSummary> getDatabasesByUid(String uid) {
        User user = userRepository.getByUid(uid);
        if (user == null) {
            log.warn("User not found: {}", uid);
            return List.of();
        }
        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("uid", user.getUid());
        userInfo.put("role", user.getRole());
        userInfo.put("department_id", user.getDepartmentId());
        return getDatabasesByUser(userInfo);
    }

    /** 根据用户权限获取知识库列表（对应 get_databases_by_user）。 */
    public List<KnowledgeBaseSummary> getDatabasesByUser(Map<String, Object> userInfo) {
        List<KnowledgeBaseSummary> all = getDatabases();
        List<KnowledgeBaseSummary> filtered = new ArrayList<>();
        for (KnowledgeBaseSummary database : all) {
            ResourcePermission permission = resolveSummaryPermission(userInfo, database);
            if (permission == ResourcePermission.NONE) {
                continue;
            }
            Map<String, Object> additionalParams = database.additionalParams();
            if (permission == ResourcePermission.READ) {
                additionalParams = KnowledgeSecurity.redactSensitiveParams(additionalParams);
            }
            filtered.add(new KnowledgeBaseSummary(
                    database.kbId(), database.name(), database.description(), database.kbType(),
                    database.embeddingModelSpec(), database.llmModelSpec(), database.queryParams(),
                    additionalParams, database.shareConfig(), database.createdBy(), database.createdAt(),
                    database.fileCount(), database.folderCount(), database.rowCount(), database.totalSize(),
                    database.chunkCount(), database.tokenCount(), database.pendingParseCount(),
                    database.pendingIndexCount(), database.processingCount(), permission));
        }
        return filtered;
    }

    private static ResourcePermission resolveSummaryPermission(
            Map<String, Object> userInfo, KnowledgeBaseSummary database) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("created_by", database.createdBy());
        source.put("share_config", new JSONObject(database.shareConfig()));
        return ResourcePermissions.resolveKnowledgeBasePermission(
                PermissionSubject.ofMap(userInfo), ShareableResource.ofMap(source));
    }

    /** 检查知识库名称是否已存在（对应 database_name_exists）。 */
    public boolean databaseNameExists(String databaseName) {
        for (KnowledgeBase row : kbRepository.getAll()) {
            String name = row.getName() == null ? "" : row.getName();
            if (name.equalsIgnoreCase(databaseName == null ? "" : databaseName)) {
                return true;
            }
        }
        return false;
    }

    // =============================================================================
    // 知识库写入
    // =============================================================================

    /** 创建文件夹并刷新统计（对应 create_folder）。 */
    public Map<String, Object> createFolder(String kbId, String folderName, String parentId,
                                            String operatorId) throws Exception {
        return runWithStatsRefresh(kbId, () -> runtime.createFolder(kbId, folderName, parentId, operatorId));
    }

    /** 创建知识库（对应 create_database）。 */
    public KnowledgeBaseDetail createDatabase(String databaseName, String description, String kbType,
                                              String embeddingModelSpec, String llmModelSpec,
                                              Object shareConfig, String createdBy,
                                              Integer createdByDepartmentId,
                                              Map<String, Object> extraParams) {
        String normalizedType = kbType == null || kbType.isEmpty() ? "milvus" : kbType;
        if (!KnowledgeBaseFactory.isTypeSupported(normalizedType)) {
            throw new IllegalArgumentException("Unsupported knowledge base type: " + normalizedType
                    + ". Available types: " + KnowledgeBaseFactory.getAvailableTypes().keySet());
        }
        if (databaseNameExists(databaseName)) {
            throw new KnowledgeBaseException.KBNameConflictError("知识库名称 '" + databaseName + "' 已存在，请使用其他名称");
        }

        JSONObject normalizedShare = normalizeShareConfig(
                shareConfig instanceof JSONObject json ? json
                        : shareConfig instanceof Map<?, ?> map ? RepoValues.toJsonObject(map) : null,
                createdBy, createdByDepartmentId);

        Map<String, Object> additionalParams = extraParams == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(extraParams);
        additionalParams.putIfAbsent("auto_generate_questions", Boolean.FALSE);
        if (additionalParams.containsKey("reranker_config")) {
            throw new IllegalArgumentException("reranker_config 已移除，请在查询参数中使用 reranker_model spec");
        }
        additionalParams = KnowledgeBaseTypeParams.normalizeAdditionalParams(normalizedType, additionalParams);

        KnowledgeBaseFactory.KbTypeInfo typeInfo = KnowledgeBaseFactory.getKbClass(normalizedType);
        boolean requiresEmbedding = typeInfo != null && typeInfo.requiresEmbeddingModel();
        if (requiresEmbedding && (embeddingModelSpec == null || embeddingModelSpec.isEmpty())) {
            throw new IllegalArgumentException("embedding_model_spec 不能为空");
        }
        String effectiveEmbeddingSpec = requiresEmbedding ? embeddingModelSpec : null;

        String kbId;
        do {
            StringBuilder suffix = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                suffix.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
            }
            kbId = "kb_" + suffix;
        } while (kbRepository.getByKbId(kbId) != null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kb_id", kbId);
        data.put("name", databaseName);
        data.put("description", description);
        data.put("kb_type", normalizedType);
        data.put("embedding_model_spec", effectiveEmbeddingSpec);
        data.put("llm_model_spec", llmModelSpec);
        data.put("query_params", KnowledgeBaseTypeParams.defaultQueryParams(normalizedType));
        Map<String, Object> persistedParams = new LinkedHashMap<>(additionalParams);
        persistedParams.put("stats", normalizeDatabaseStats(null));
        data.put("additional_params", persistedParams);
        data.put("share_config", normalizedShare);
        data.put("created_by", createdBy);
        kbRepository.create(data);

        KnowledgeBaseDetail detail = getDatabaseInfo(kbId, false);
        if (detail == null) {
            throw new KnowledgeBaseException.KBNotFoundError("Database " + kbId + " not found after creation");
        }
        return detail;
    }

    /** 删除知识库（对应 delete_database）。 */
    public Map<String, Object> deleteDatabase(String kbId) {
        try {
            getKbExecutor(kbId);
            Map<String, Object> result = runtime.cleanupDatabaseResources(kbId);
            kbRepository.delete(kbId);
            kbCache.deleteCachedKbConfig(kbId);
            return result;
        } catch (KnowledgeBaseException.KBNotFoundError exception) {
            log.warn("Database {} not found during deletion: {}", kbId, exception.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message", "删除成功");
            return result;
        }
    }

    // =============================================================================
    // 摄取编排（上传 → 解析 → 入库 → 改参数）
    // =============================================================================

    /** 新增文件记录（对应 add_file_record）。 */
    public Map<String, Object> addFileRecord(String kbId, String item, Map<String, Object> params,
                                             String operatorId) throws Exception {
        return runtime.addFileRecord(kbId, item, params, operatorId);
    }

    /** 解析文件（对应 parse_file）。 */
    public Map<String, Object> parseFile(String kbId, String fileId, String operatorId,
                                         String processingTaskId, String processingOwner) throws Exception {
        return runtime.parseFile(kbId, fileId, operatorId, processingTaskId, processingOwner);
    }

    /** 入库文件（对应 index_file）。 */
    public Map<String, Object> indexFile(String kbId, String fileId, String operatorId,
                                         Map<String, Object> params,
                                         String processingTaskId, String processingOwner) throws Exception {
        return runtime.indexFile(kbId, fileId, operatorId, params, processingTaskId, processingOwner);
    }

    /** 更新文件处理参数（对应 update_file_params）。 */
    public void updateFileParams(String kbId, String fileId, Map<String, Object> params,
                                 String operatorId) throws Exception {
        runtime.updateFileParams(kbId, fileId, params, operatorId);
    }

    /**
     * 查询知识库并返回原始 chunk 列表（对应 manager.aquery：取运行配置后转交执行器）。
     *
     * <p>与 {@link #retrieve} 的分工同参考实现：本方法不套 {@code build_search_output} 投影，
     * 评测、外部检索等需要原始字段（score / distance / 各召回路径分数）的调用方走这里。
     */
    public List<Map<String, Object>> aquery(String queryText, String kbId, Map<String, Object> kwargs) {
        KnowledgeBaseConfig config = getKbConfig(kbId);
        return runtime.aquery(queryText, kbId, false, config, kwargs);
    }

    /**
     * 检索（对应 retrieve：按 kb 加载运行配置后执行检索并构造输出）。
     *
     * <p>参考实现固定传 {@code agent_call=True}（执行器保留该形参但实现体未使用）。
     */
    public Map<String, Object> retrieve(String kbId, String query, Map<String, Object> options) {
        List<Map<String, Object>> results = runtime.aquery(query, kbId, true, getKbConfig(kbId), options);
        // buildSearchOutput 在入参非列表时原样返回（对齐参考实现的 | Any），此处窄化为 Map 输出
        return asMap(KnowledgeFileViews.buildSearchOutput(kbId, results));
    }

    /**
     * 知识库检索参数定义，并合并当前保存值（对应 get_kb_query_params_config）。
     *
     * <p>{@code reranker_model} 的选项由当前重排模型清单动态供给（对应参考实现的
     * {@code options_provider="rerank_models"}）。
     */
    public Map<String, Object> getKbQueryParamsConfig(String kbId) {
        KnowledgeBaseConfig config = getKbConfig(kbId);
        Map<String, Object> result =
                KnowledgeBaseTypeParams.queryParamsConfig(
                        config.kbType(), modelProviderCache.getAllSpecs("rerank"));
        Map<String, Object> saved = config.queryOptions();
        if (result.get("options") instanceof List<?> rawOptions) {
            for (Object raw : rawOptions) {
                if (raw instanceof Map<?, ?> option) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mutable = (Map<String, Object>) option;
                    String key = String.valueOf(mutable.get("key"));
                    if (saved.containsKey(key)) {
                        mutable.put("default", saved.get(key));
                    }
                }
            }
        }
        return result;
    }

    /** 更新知识库检索参数（对应 update_kb_query_params：合并语义）。 */
    public void updateKbQueryParams(String kbId, Map<String, Object> params) {
        if (params == null) {
            return;
        }
        // 参考实现：KnowledgeBaseRepository().merge_query_params_options(kb_id, params)
        // —— 在行锁内先失效缓存，再把入参合并进 query_params.options 子对象（而非顶层），
        // 且不重算整份参数；取不到记录时抛 KBNotFoundError。
        KnowledgeBase updated = kbRepository.mergeQueryParamsOptions(kbId, params);
        if (updated == null) {
            throw new KnowledgeBaseException.KBNotFoundError("Database " + kbId + " not found");
        }
    }

    /** 导出知识库数据（对应 export_data；本工程暂未提供导出器）。 */
    public String exportData(String kbId, String format) {
        getKbExecutor(kbId);
        return runtime.exportData(kbId, format);
    }

    /** 文件记录 → 列表项（对应 _file_record_list_item）。 */
    public Map<String, Object> fileRecordListItem(KnowledgeFile record, Map<String, Integer> childCounts,
                                                  User creator) {
        return fileRecordListItem(record, childCounts, creator, null);
    }

    /**
     * 文件记录 → 列表项（对应 {@code _file_record_list_item}）。
     *
     * <p>必要替换：参考实现的该函数对 {@code is_virtual_folder}/{@code path_prefix}/
     * {@code virtual_children_count} 一律 {@code getattr(record, ..., 默认值)} 动态取值 ——
     * 这三个字段只有目录视图（无状态筛选的默认视图）返回的行才带。Java 侧未把它们建模进实体，
     * 故由 {@code directoryRow} 显式透传原始行；实体分支传 {@code null}，退化为参考实现的默认值。
     */
    public Map<String, Object> fileRecordListItem(KnowledgeFile record, Map<String, Integer> childCounts,
                                                  User creator, Map<String, Object> directoryRow) {
        Map<String, Integer> counts = childCounts == null ? Map.of() : childCounts;
        String fileId = record.getFileId();
        // 参考实现：int(getattr(record, "virtual_children_count", 0) or child_counts.get(file_id, 0))
        Long virtualChildrenCount =
                directoryRow == null ? null : RepoValues.toLong(directoryRow.get("virtual_children_count"));
        int childCount = virtualChildrenCount != null && virtualChildrenCount > 0
                ? virtualChildrenCount.intValue()
                : counts.getOrDefault(fileId, 0);
        Integer createdBy = null;
        String createdAt = "";
        String updatedAt = "";
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("file_id", fileId);
        item.put("filename", record.getFilename());
        item.put("file_type", record.getFileType());
        item.put("status", record.getStatus() == null || record.getStatus().isEmpty()
                ? "uploaded" : record.getStatus());
        item.put("created_at", record.getCreatedAt() == null ? null : formatUtc(record.getCreatedAt()));
        item.put("updated_at", record.getUpdatedAt() == null ? null : formatUtc(record.getUpdatedAt()));
        item.put("file_size", record.getFileSize() == null ? 0L : record.getFileSize());
        item.put("chunk_count", record.getChunkCount() == null ? 0 : record.getChunkCount());
        item.put("token_count", record.getTokenCount() == null ? 0L : record.getTokenCount());
        item.put("created_by", record.getCreatedBy());
        item.put("created_by_name", creator == null ? record.getCreatedBy() : creator.getUsername());
        item.put("created_by_avatar", creator == null ? null : creator.getAvatar());
        item.put("is_folder", Boolean.TRUE.equals(record.getIsFolder()));
        item.put("parent_id", record.getParentId());
        item.put("has_children", childCount > 0);
        item.put("children_count", childCount);
        item.put("has_original_file",
                (record.getMinioUrl() != null && !record.getMinioUrl().isEmpty())
                        || (record.getPath() != null && !record.getPath().isEmpty()));
        item.put("has_parsed_markdown", record.getMarkdownFile() != null && !record.getMarkdownFile().isEmpty());
        item.put("is_virtual_folder",
                directoryRow != null && Boolean.TRUE.equals(RepoValues.toBoolean(directoryRow.get("is_virtual_folder"))));
        item.put("path_prefix", directoryRow == null ? null : directoryRow.get("path_prefix"));
        return item;
    }

    /** 知识库文件统计（对应 _get_database_file_stats）。 */
    public Map<String, Object> getDatabaseFileStats(String kbId) {
        return fileRepository.getKbFileStats(kbId);
    }

    /** 获取知识库详情（对应 get_database_info）。 */
    public KnowledgeBaseDetail getDatabaseInfo(String kbId, boolean includeFiles) {
        KnowledgeBase kb = kbRepository.getByKbId(kbId);
        if (kb == null) {
            return null;
        }

        Map<String, Map<String, Object>> files = null;
        boolean filesTruncated = false;
        Integer filesPageSize = null;
        if (includeFiles) {
            Map<String, Object> page = fileRepository.listDocuments(kbId, null, null, null, 1, 500, false, false);
            List<KnowledgeFile> records = documentsOf(page);
            files = new LinkedHashMap<>();
            for (KnowledgeFile record : records) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("file_id", record.getFileId());
                item.put("filename", record.getFilename());
                item.put("path", record.getPath() == null ? "" : record.getPath());
                item.put("markdown_file", record.getMarkdownFile() == null ? "" : record.getMarkdownFile());
                item.put("type", record.getFileType() == null ? "" : record.getFileType());
                item.put("status", record.getStatus() == null || record.getStatus().isEmpty()
                        ? "uploaded" : record.getStatus());
                item.put("created_at", record.getCreatedAt() == null ? null : formatUtc(record.getCreatedAt()));
                item.put("is_folder", Boolean.TRUE.equals(record.getIsFolder()));
                item.put("parent_id", record.getParentId());
                item.put("chunk_count", record.getChunkCount() == null ? 0 : record.getChunkCount());
                item.put("token_count", record.getTokenCount() == null ? 0L : record.getTokenCount());
                files.put(record.getFileId(), item);
            }
            filesTruncated = intOf(page.get("total")) > records.size();
            filesPageSize = 500;
        }

        Map<String, Object> fileStats = getDatabaseFileStats(kbId);
        KnowledgeBaseSummary summary = toSummary(databaseReadFields(kb, fileStats));
        return KnowledgeBaseDetail.of(
                summary,
                RepoValues.parseObject(kb.getMindmap()),
                parseStringList(kb.getSampleQuestions()),
                files,
                filesTruncated,
                filesPageSize);
    }

    /** 按目录与筛选条件分页获取轻量文件列表（对应 list_document_files）。 */
    public Map<String, Object> listDocumentFiles(String kbId, String parentId, String pathPrefix,
                                                 String status, int page, int pageSize,
                                                 boolean recursive, boolean filesOnly,
                                                 boolean includeStats) {
        KnowledgeBase kb = kbRepository.getByKbId(kbId);
        if (kb == null) {
            throw new KnowledgeBaseException.KBNotFoundError("Database " + kbId + " not found");
        }
        if (parentId != null && !parentId.isEmpty()) {
            KnowledgeFile parent = fileRepository.getByFileId(parentId);
            if (parent == null || !kbId.equals(parent.getKbId())) {
                throw new IllegalArgumentException("Parent folder not found");
            }
            if (!Boolean.TRUE.equals(parent.getIsFolder())) {
                throw new IllegalArgumentException("Parent is not a folder");
            }
        }

        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = Math.min(Math.max(pageSize, 1), 500);
        boolean effectiveRecursive = recursive && status != null && !status.isEmpty() && !"all".equals(status);

        Map<String, Object> pageResult = fileRepository.listDocuments(
                kbId, parentId, pathPrefix, status, normalizedPage, normalizedPageSize,
                effectiveRecursive, filesOnly);
        List<KnowledgeFile> records = documentsOf(pageResult);
        Map<String, Map<String, Object>> directoryRows = directoryRowExtras(pageResult);
        int total = intOf(pageResult.get("total"));
        Map<String, Object> stats = includeStats ? fileRepository.getKbFileStats(kbId) : null;

        List<String> folderIds = new ArrayList<>();
        List<String> creatorUids = new ArrayList<>();
        for (KnowledgeFile record : records) {
            if (Boolean.TRUE.equals(record.getIsFolder())) {
                folderIds.add(record.getFileId());
            }
            if (record.getCreatedBy() != null && !record.getCreatedBy().isEmpty()) {
                creatorUids.add(record.getCreatedBy());
            }
        }
        Map<String, Integer> childCounts = fileRepository.countChildrenByParentIds(kbId, folderIds);
        if (childCounts == null) {
            childCounts = Map.of();
        }
        Map<String, User> creators = new LinkedHashMap<>();
        if (!creatorUids.isEmpty()) {
            for (User user : userRepository.listByUids(creatorUids)) {
                creators.put(user.getUid(), user);
            }
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (KnowledgeFile record : records) {
            items.add(fileRecordListItem(record, childCounts, creators.get(record.getCreatedBy()),
                    directoryRows.get(record.getFileId())));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total);
        result.put("page", normalizedPage);
        result.put("page_size", normalizedPageSize);
        result.put("has_more", (long) normalizedPage * normalizedPageSize < total);
        result.put("parent_id", parentId);
        result.put("path_prefix", pathPrefix == null ? "" : pathPrefix);
        result.put("recursive", effectiveRecursive);
        if (stats != null) {
            result.put("stats", stats);
        }
        return result;
    }

    /** 按文件名在一组知识库中搜索文件（对应 search_document_files）。 */
    public Map<String, Object> searchDocumentFiles(List<Map<String, Object>> knowledgeBases, String query,
                                                   int offset, int limit, String status,
                                                   boolean includeIsFolder, boolean includeParentId) {
        int normalizedOffset = Math.max(offset, 0);
        int normalizedLimit = Math.min(Math.max(limit, 1), KB_FILE_SEARCH_SCAN_LIMIT);
        String normalizedQuery = query == null ? "" : query.strip().toLowerCase();
        Set<String> acceptedStatuses = normalizeFileStatusFilter(status);

        boolean useSqlPagination = knowledgeBases != null && knowledgeBases.size() == 1;
        int candidateLimit = useSqlPagination ? normalizedLimit : normalizedOffset + normalizedLimit;
        int queryOffset = useSqlPagination ? normalizedOffset : 0;

        List<Map<String, Object>> allFiles = new ArrayList<>();
        int total = 0;
        if (knowledgeBases != null) {
            for (Map<String, Object> kb : knowledgeBases) {
                KbSearchResult searchResult =
                        searchKbFiles(kb, normalizedQuery, acceptedStatuses, queryOffset, candidateLimit);
                total += searchResult.total();
                for (KnowledgeFile file : searchResult.files()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("kb_id", kb.get("kb_id"));
                    item.put("kb_name", kb.get("name"));
                    item.put("file_id", file.getFileId());
                    item.put("filename", file.getFilename());
                    item.put("file_type", file.getFileType());
                    item.put("status", file.getStatus());
                    item.put("created_at", formatUtc(file.getCreatedAt()));
                    item.put("updated_at", formatUtc(file.getUpdatedAt()));
                    item.put("file_size", file.getFileSize());
                    if (includeIsFolder) {
                        item.put("is_folder", Boolean.TRUE.equals(file.getIsFolder()));
                    }
                    if (includeParentId) {
                        item.put("parent_id", file.getParentId());
                    }
                    allFiles.add(item);
                }
            }
        }

        if (!useSqlPagination) {
            // 多库才需要跨库按更新时间归并；单库结果已由 DB 按 updated_at desc, file_id asc 排好序。
            allFiles.sort((left, right) -> String.valueOf(right.get("updated_at"))
                    .compareTo(String.valueOf(left.get("updated_at"))));
        }
        List<Map<String, Object>> paginated = useSqlPagination
                ? allFiles
                : allFiles.subList(Math.min(normalizedOffset, allFiles.size()),
                        Math.min(normalizedOffset + normalizedLimit, allFiles.size()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", paginated);
        result.put("total", total);
        result.put("offset", normalizedOffset);
        result.put("limit", normalizedLimit);
        result.put("has_more", (long) normalizedOffset + normalizedLimit < total);
        return result;
    }

    /** 状态筛选归一（对应 _normalize_file_status_filter）。 */
    public static Set<String> normalizeFileStatusFilter(String status) {
        if (status == null || status.isEmpty() || "all".equals(status)) {
            return null;
        }
        if ("indexed".equals(status)) {
            return Set.of("indexed", "done");
        }
        if ("error_indexing".equals(status)) {
            return Set.of("error_indexing", "failed");
        }
        return Set.of(status);
    }

    /** 搜索单个知识库文件（对应 _search_kb_files）。 */
    private KbSearchResult searchKbFiles(Map<String, Object> kb, String query, Set<String> statuses,
                                         int offset, int limit) {
        Object kbId = kb.get("kb_id");
        if (kbId == null || String.valueOf(kbId).isEmpty()) {
            return new KbSearchResult(kb, List.of(), 0);
        }
        Map<String, Object> page = fileRepository.searchFiles(
                String.valueOf(kbId), query, statuses, offset, limit, true);
        return new KbSearchResult(kb, documentsOf(page), intOf(page.get("total")));
    }

    /** 检查知识库中是否存在给定文件名（对应 document_file_exists）。 */
    public boolean documentFileExists(String kbId, String filename) {
        String normalizedFilename = filename == null ? "" : filename.strip();
        if (normalizedFilename.isEmpty()) {
            throw new IllegalArgumentException("filename is required");
        }
        return fileRepository.existsByFilename(kbId, normalizedFilename);
    }

    /** 按状态游标分页获取文件 ID（对应 list_document_file_ids_by_statuses）。 */
    public List<String> listDocumentFileIdsByStatuses(String kbId, List<String> statuses,
                                                      String afterFileId, int limit) {
        return fileRepository.listFileIdsByExactStatuses(kbId, statuses, afterFileId, limit);
    }

    /** 递归删除文件夹（对应 delete_folder）。 */
    public void deleteFolder(String kbId, String folderId) throws Exception {
        runWithStatsRefresh(kbId, () -> {
            runtime.deleteFolder(kbId, folderId);
            return null;
        });
    }

    /** 删除文件（对应 delete_file）。 */
    public void deleteFile(String kbId, String fileId) throws Exception {
        runWithStatsRefresh(kbId, () -> {
            runtime.deleteFile(kbId, fileId);
            return null;
        });
    }

    /** 修复历史文件缺失统计并刷新聚合统计（对应 repair_missing_file_stats）。 */
    public Map<String, Object> repairMissingFileStats(String kbId) {
        Map<String, Object> result = runtime.repairMissingFileStats(kbId);
        result.put("stats", refreshDatabaseStats(kbId));
        return result;
    }

    public Map<String, Object> getFileBasicInfo(String kbId, String fileId) {
        return runtime.getFileBasicInfo(kbId, fileId);
    }

    public Map<String, Object> getFileContent(String kbId, String fileId) {
        return runtime.getFileContent(kbId, fileId);
    }

    public Map<String, Object> openFileContent(String kbId, String fileId, int offset, int limit) {
        return runtime.openFileContent(kbId, fileId, offset, limit);
    }

    public Map<String, Object> findFileContent(String kbId, String fileId, List<String> patterns,
                                               boolean useRegex, boolean caseSensitive,
                                               int maxWindows, int windowSize) {
        return runtime.findFileContent(kbId, fileId, patterns, useRegex, caseSensitive,
                maxWindows, windowSize);
    }

    public Map<String, Object> getFileInfo(String kbId, String fileId) {
        return runtime.getFileInfo(kbId, fileId);
    }

    public Map<String, Object> listFileTree(String kbId, String parentId, boolean recursive,
                                            boolean filesOnly) {
        return runtime.listFileTree(kbId, parentId, recursive, filesOnly);
    }

    public Map<String, Object> getFileDownload(String kbId, String fileId, String variant) {
        requireKbSupportsDocuments(kbId, "download");
        return runtime.getFileDownload(kbId, fileId, variant);
    }

    /** 获取同一知识库中同名文件列表（对应 get_same_name_files）。 */
    public List<Map<String, Object>> getSameNameFiles(String kbId, String filename) {
        if (kbId == null || kbId.isEmpty() || filename == null || filename.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (KnowledgeFile record : fileRepository.listSameNameFiles(kbId, filename)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("file_id", record.getFileId());
            item.put("filename", record.getFilename());
            item.put("size", record.getFileSize() == null ? 0L : record.getFileSize());
            item.put("created_at", formatUtc(record.getCreatedAt()));
            item.put("content_hash", record.getContentHash() == null ? "" : record.getContentHash());
            result.add(item);
        }
        return result;
    }

    /** 检查指定知识库中是否存在相同内容哈希的文件（对应 file_existed_in_db）。 */
    public boolean fileExistedInDb(String kbId, String contentHash) {
        if (kbId == null || kbId.isEmpty() || contentHash == null || contentHash.isEmpty()) {
            return false;
        }
        return fileRepository.existsByContentHash(kbId, contentHash);
    }

    /** 更新知识库（对应 update_database）。 */
    public KnowledgeBaseDetail updateDatabase(String kbId, String name, String description,
                                              String llmModelSpec, boolean updateLlmModelSpec,
                                              Map<String, Object> additionalParams,
                                              Object shareConfig, String operatorUid,
                                              Integer operatorDepartmentId) {
        KnowledgeBase kb = kbRepository.getByKbId(kbId);
        if (kb == null) {
            throw new IllegalArgumentException("数据库 " + kbId + " 不存在");
        }
        String kbType = kb.getKbType() == null || kb.getKbType().isEmpty() ? "milvus" : kb.getKbType();
        if (!KnowledgeBaseFactory.isTypeSupported(kbType)) {
            throw new IllegalArgumentException("不支持的知识库类型: " + kbType);
        }
        KnowledgeBaseFactory.KbTypeInfo typeInfo = KnowledgeBaseFactory.getKbClass(kbType);

        Map<String, Object> updateData = new LinkedHashMap<>();
        updateData.put("name", name);
        updateData.put("description", description);
        if (updateLlmModelSpec) {
            updateData.put("llm_model_spec", llmModelSpec);
        }
        if (additionalParams != null) {
            Map<String, Object> current = RepoValues.parseObject(kb.getAdditionalParams());
            Map<String, Object> currentGraphConfig = asMap(current.get("graph_build_config"));
            if (Boolean.TRUE.equals(currentGraphConfig.get("locked"))
                    && additionalParams.containsKey("graph_build_config")) {
                throw new IllegalArgumentException("图谱抽取配置已锁定，请使用图谱重置接口重新配置");
            }
            Map<String, Object> merged = deepMerge(current, additionalParams);
            updateData.put("additional_params", merged);
        }
        if (shareConfig != null) {
            JSONObject normalized = normalizeShareConfig(
                    shareConfig instanceof JSONObject json ? json
                            : shareConfig instanceof Map<?, ?> map ? RepoValues.toJsonObject(map) : null,
                    operatorUid, operatorDepartmentId);
            updateData.put("share_config", normalized);
        }
        kbRepository.update(kbId, updateData);
        kbCache.deleteCachedKbConfig(kbId);

        KnowledgeBaseDetail detail = getDatabaseInfo(kbId, false);
        if (detail == null) {
            throw new KnowledgeBaseException.KBNotFoundError("Database " + kbId + " not found after update");
        }
        return detail;
    }

    /** 按行窗口打开文件解析结果并构造 OpenOutput（对应 open_document）。 */
    public Map<String, Object> openDocument(String kbId, String fileId, int offset, int limit) {
        requireKbSupportsDocuments(kbId, "open");
        Map<String, Object> window = openFileContent(kbId, fileId, offset, limit);
        Object nextOffset = window.get("next_offset");
        return new KbToolSchemas.OpenOutput(
                kbId,
                fileId,
                intOf(window.get("start_line")),
                intOf(window.get("end_line")),
                intOf(window.get("total_lines")),
                intOf(window.get("offset")),
                intOf(window.get("window_size")),
                Boolean.TRUE.equals(window.get("has_more_before")),
                Boolean.TRUE.equals(window.get("has_more_after")),
                nextOffset instanceof Number number ? number.intValue() : null,
                window.get("content") == null ? "" : String.valueOf(window.get("content")))
                .toMap();
    }

    /** 在文件内定位并构造 FindOutput（对应 find_in_document）。 */
    public Map<String, Object> findInDocument(String kbId, String fileId, List<String> patterns,
                                              boolean useRegex, boolean caseSensitive,
                                              int maxWindows, int windowSize) {
        requireKbSupportsDocuments(kbId, "find");
        Map<String, Object> result = findFileContent(kbId, fileId, patterns, useRegex, caseSensitive,
                maxWindows, windowSize);
        List<KbToolSchemas.FindWindow> windows = new ArrayList<>();
        if (result.get("windows") instanceof List<?> rawWindows) {
            for (Object raw : rawWindows) {
                Map<String, Object> item = asMap(raw);
                List<Integer> matchedLines = new ArrayList<>();
                if (item.get("matched_lines") instanceof List<?> rawLines) {
                    for (Object line : rawLines) {
                        if (line instanceof Number number) {
                            matchedLines.add(number.intValue());
                        }
                    }
                }
                windows.add(new KbToolSchemas.FindWindow(
                        intOf(item.get("start_line")),
                        intOf(item.get("end_line")),
                        matchedLines,
                        item.get("content") == null ? "" : String.valueOf(item.get("content"))));
            }
        }
        return new KbToolSchemas.FindOutput(
                kbId,
                fileId,
                Boolean.TRUE.equals(result.get("semantic")),
                result.get("match_mode") == null ? "" : String.valueOf(result.get("match_mode")),
                intOf(result.get("total_matches")),
                windows)
                .toMap();
    }

    /** 按元数据判断是否支持文档全文操作；不支持抛异常（对应 _require_kb_supports_documents）。 */
    public void requireKbSupportsDocuments(String kbId, String operation) {
        DatabaseDocumentSupport support = getDatabaseDocumentSupport(kbId);
        if (support.database() == null) {
            throw new KnowledgeBaseException.KBNotFoundError("知识库资源 '" + kbId + "' 不存在");
        }
        if (!support.supportsDocuments()) {
            String label = switch (operation) {
                case "open" -> "文档查看";
                case "find" -> "文档查找";
                case "download" -> "文件下载";
                default -> operation;
            };
            String name = support.database().name() == null || support.database().name().isEmpty()
                    ? support.database().kbType() : support.database().name();
            throw new IllegalArgumentException(name + " 只支持检索，不支持" + label);
        }
    }

    // =============================================================================
    // 管理器特有方法
    // =============================================================================

    /** 获取支持的知识库类型（对应 get_supported_kb_types）。 */
    public Map<String, Map<String, Object>> getSupportedKbTypes() {
        return KnowledgeBaseFactory.getAvailableTypes();
    }

    /** 获取统计信息（对应 get_statistics）。 */
    public Map<String, Object> getStatistics() {
        List<KnowledgeBase> rows = kbRepository.getAll();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total_databases", rows.size());
        Map<String, Integer> kbTypes = new LinkedHashMap<>();
        for (KnowledgeBase row : rows) {
            String kbType = row.getKbType() == null || row.getKbType().isEmpty() ? "milvus" : row.getKbType();
            kbTypes.merge(kbType, 1, Integer::sum);
        }
        stats.put("kb_types", kbTypes);
        stats.put("total_files", fileRepository.countAll());
        return stats;
    }

    // =============================================================================
    // 数据一致性检测
    // =============================================================================

    /** 检测外部资源与主数据的不一致（对应 detect_data_inconsistencies）。 */
    public Map<String, Object> detectDataInconsistencies() {
        List<KnowledgeBase> rows = kbRepository.getAll();
        Set<String> knownKbIds = new HashSet<>();
        Set<String> managedKbIds = new HashSet<>();
        for (KnowledgeBase row : rows) {
            knownKbIds.add(row.getKbId());
            String kbType = row.getKbType() == null || row.getKbType().isEmpty() ? "milvus" : row.getKbType();
            if ("milvus".equals(kbType)) {
                managedKbIds.add(row.getKbId());
            }
        }

        log.info("开始检测向量数据库与元数据的一致性...");
        Map<String, Object> milvusInconsistencies = runtime.detectDataInconsistencies(knownKbIds, managedKbIds);
        List<Map<String, Object>> missingCollections =
                listOfMaps(milvusInconsistencies.get("missing_collections"));
        List<Map<String, Object>> missingFiles = listOfMaps(milvusInconsistencies.get("missing_files"));

        Map<String, Object> inconsistencies = new LinkedHashMap<>();
        inconsistencies.put("milvus", milvusInconsistencies);
        inconsistencies.put("total_missing_collections", missingCollections.size());
        inconsistencies.put("total_missing_files", missingFiles.size());
        logInconsistencies(inconsistencies, missingCollections, missingFiles);
        return inconsistencies;
    }

    private void logInconsistencies(Map<String, Object> inconsistencies,
                                    List<Map<String, Object>> missingCollections,
                                    List<Map<String, Object>> missingFiles) {
        if (missingCollections.isEmpty() && missingFiles.isEmpty()) {
            log.info("数据一致性检测完成，未发现不一致情况");
            return;
        }
        log.warn("数据一致性检测完成，发现以下不一致情况：");
        log.warn("  缺失集合数量: {}", missingCollections.size());
        for (Map<String, Object> info : missingCollections) {
            log.warn("    - 集合: {}, 实体数: {}", info.get("collection_name"), info.get("count"));
        }
        log.warn("  缺失文件记录数量: {}", missingFiles.size());
        for (Map<String, Object> info : missingFiles) {
            log.warn("    - 数据库: {}, 向量数: {}, 元数据文件数: {}",
                    info.get("kb_id"), info.get("vector_count"), info.get("metadata_files_count"));
        }
        log.warn("总计：缺失集合 {} 个，缺失文件记录 {} 个",
                missingCollections.size(), missingFiles.size());
    }

    // =============================================================================
    // 工具
    // =============================================================================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map) {
                    result.add((Map<String, Object>) item);
                }
            }
            return result;
        }
        return List.of();
    }

    /** 从仓储分页结果中取出记录列表（键名 items/list 兼容）。 */
    @SuppressWarnings("unchecked")
    /**
     * 列表记录归一：实体分支（MyBatis 查询）直接透传，目录视图分支（{@code jdbc.queryForList} 行）
     * 转为等价的轻量记录。
     *
     * <p>必要替换：参考实现的两个分支都返回"行对象"（ORM 实体与 SQLAlchemy Row 同形，均以属性访问），
     * {@code _file_record_list_item} 用 {@code getattr} 取值；Java 侧目录视图只能拿到 {@code Map}。
     * 此处原先只接受 {@code KnowledgeFile}，会把整页目录视图记录**静默丢弃**（表现为
     * {@code total=1} 但 {@code items=[]}，默认视图恒空），故显式转换；仅目录视图才有的三个字段
     * 由 {@link #directoryRowExtras(Map)} 另外透传给列表项构造。
     */
    private static List<KnowledgeFile> documentsOf(Map<String, Object> page) {
        Object records = page.get("items");
        if (records == null) {
            records = page.get("records");
        }
        if (records instanceof List<?> list) {
            List<KnowledgeFile> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof KnowledgeFile file) {
                    result.add(file);
                } else if (item instanceof Map<?, ?> row) {
                    result.add(fileRecordOf(row));
                }
            }
            return result;
        }
        return List.of();
    }

    /** 目录视图行 → 轻量文件记录（只取与实体同名的列；虚拟字段走 {@link #directoryRowExtras(Map)}）。 */
    private static KnowledgeFile fileRecordOf(Map<?, ?> row) {
        KnowledgeFile file = new KnowledgeFile();
        file.setFileId(textOf(row.get("file_id")));
        file.setFilename(textOf(row.get("filename")));
        file.setFileType(textOf(row.get("file_type")));
        file.setStatus(textOf(row.get("status")));
        file.setCreatedAt(utcOf(row.get("created_at")));
        file.setUpdatedAt(utcOf(row.get("updated_at")));
        file.setFileSize(RepoValues.toLong(row.get("file_size")));
        file.setChunkCount(RepoValues.toInt(row.get("chunk_count")));
        file.setTokenCount(RepoValues.toLong(row.get("token_count")));
        file.setCreatedBy(textOf(row.get("created_by")));
        file.setIsFolder(RepoValues.toBoolean(row.get("is_folder")));
        file.setParentId(textOf(row.get("parent_id")));
        file.setPath(textOf(row.get("path")));
        file.setMinioUrl(textOf(row.get("minio_url")));
        file.setMarkdownFile(textOf(row.get("markdown_file")));
        return file;
    }

    /** 目录视图原始行（按 file_id 索引）：只有这类行带虚拟文件夹三字段。非目录视图返回空表。 */
    private static Map<String, Map<String, Object>> directoryRowExtras(Map<String, Object> page) {
        Object records = page.get("items");
        if (records == null) {
            records = page.get("records");
        }
        Map<String, Map<String, Object>> extras = new LinkedHashMap<>();
        if (records instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> raw && raw.get("file_id") != null) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : raw.entrySet()) {
                        if (entry.getKey() != null) {
                            row.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                    }
                    extras.put(String.valueOf(raw.get("file_id")), row);
                }
            }
        }
        return extras;
    }

    private static String textOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** JDBC 行时间列 → LocalDateTime（MySQL 驱动通常已给 LocalDateTime，Timestamp 兜底）。 */
    private static java.time.LocalDateTime utcOf(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return RepoValues.toLocalDateTime(value);
    }

    private static int intOf(Object value) {
        Long parsed = RepoValues.toLong(value);
        return parsed == null ? 0 : parsed.intValue();
    }

    private static long longOf(Object value) {
        Long parsed = RepoValues.toLong(value);
        return parsed == null ? 0L : parsed;
    }

    private static String formatUtc(java.time.LocalDateTime value) {
        return value == null ? "" : com.wisesoft.wenqu.common.DateTimeUtils.formatUtcDatetime(value);
    }

    private static List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = com.alibaba.fastjson2.JSON.parseArray(json, String.class);
            return parsed == null ? List.of() : parsed;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /** 深度合并：请求侧键覆盖元数据侧（对应参考实现 deep_merge）。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> merged = new LinkedHashMap<>(base == null ? Map.of() : base);
        if (override == null) {
            return merged;
        }
        for (Map.Entry<String, Object> entry : override.entrySet()) {
            Object existing = merged.get(entry.getKey());
            Object incoming = entry.getValue();
            if (existing instanceof Map && incoming instanceof Map) {
                merged.put(entry.getKey(), deepMerge((Map<String, Object>) existing, (Map<String, Object>) incoming));
            } else {
                merged.put(entry.getKey(), incoming);
            }
        }
        return merged;
    }
}
