package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.knowledge.KbUtils;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseDetail;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseException;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseManager;
import com.wisesoft.wenqu.knowledge.graphs.MilvusGraphService;
import com.wisesoft.wenqu.permissions.KnowledgePermissions;
import com.wisesoft.wenqu.permissions.ResourcePermission;
import com.wisesoft.wenqu.storage.MinioStorageClient;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@code knowledge_router} 各分组控制器的共享支撑层。
 *
 * <p>参考实现的 {@code server/routers/knowledge_router.py} 是**单个文件**，其中 56 个端点
 * 共享 9 个模块级函数与 5 个模块级常量。本工程把 56 个端点整体落在单个
 * {@code KnowledgeBaseController}（与参考文件 1:1）；同前缀的 {@code ExternalKbController}
 * 来自**另一个**参考文件 {@code external_kb_router.py}，二者共用 {@code /api/knowledge}
 * 前缀（Spring 允许多个类声明同一类级前缀，只要完整路径不冲突）——
 * 这是**物理拆分**，对外路径集合与参考实现逐一对应，不新增/不删除任何端点。
 *
 * <p>两个控制器共享同一批模块级函数/常量：若各写一份，错误码与文案必然分叉。
 * 故把参考实现的模块级函数与常量集中到此，逐函数对齐：
 * <ul>
 *   <li>{@link #ensureDatabaseSupportsDocuments} ← {@code _ensure_database_supports_documents}</li>
 *   <li>{@link #requireManagePermissionIfKbId} ← {@code _require_manage_permission_if_kb_id}</li>
 *   <li>{@link #ensureDocumentParams} ← {@code _ensure_document_params}</li>
 *   <li>{@link #validateUploadedDocumentItems} ← {@code _validate_uploaded_document_items}</li>
 *   <li>{@link #validateDirectDocumentActionFileIds} ← {@code _validate_direct_document_action_file_ids}</li>
 *   <li>{@link #enqueueDocumentActionTask} ← {@code _enqueue_document_action_task}</li>
 *   <li>{@link #enqueuePendingDocumentActionTask} ← {@code _enqueue_pending_document_action_task}</li>
 *   <li>{@link #deleteDocumentStorageObjects} ← {@code _delete_document_storage_objects}</li>
 *   <li>{@link #hasRunningGraphBuildTask} ← {@code _has_running_graph_build_task}</li>
 * </ul>
 *
 * <h3>平台差异（必要替换，均不影响状态码与文案）</h3>
 * <ul>
 *   <li>{@code Depends(get_admin_user)} → {@code AuthGuards.requireAdmin()}；
 *       {@code Depends(require_knowledge_base_read|manage)} →
 *       {@link KnowledgePermissions#requireKnowledgeBaseRead}/{@link KnowledgePermissions#requireKnowledgeBaseManage}
 *       （两者均已内含 admin 校验）。</li>
 *   <li>{@code HTTPException(status_code, detail)} → {@link ApiHttpException}（响应体 {@code {"detail": ...}}）。</li>
 *   <li>{@code KBNotFoundError} → {@link KnowledgeBaseException.KBNotFoundError}；
 *       {@code ValueError} → {@code IllegalArgumentException}（调用方转 400）。</li>
 *   <li>{@code asyncio.to_thread(minio.ensure_bucket_exists)} → 直接同步调用
 *       {@link MinioStorageClient#ensureBucketExists(String)}（本工程 MinIO 客户端本身即阻塞式）。</li>
 *   <li>{@code tasker.enqueue(...)} 的 Python 默认超时 → Java 传 {@code null}
 *       （{@link TaskService#resolveTimeoutSeconds} 会落回同一默认值）。</li>
 * </ul>
 *
 * @author yuanke
 */
@Service
public class KnowledgeRouteSupport {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRouteSupport.class);

    // ==================== 常量（逐条对齐参考实现模块级常量） ====================

    /** {@code ACTIVE_GRAPH_BUILD_STATUSES}。 */
    public static final Set<String> ACTIVE_GRAPH_BUILD_STATUSES = Set.of("pending", "running");

    /** {@code MAX_DIRECT_DOCUMENT_ACTION_FILE_IDS}。 */
    public static final int MAX_DIRECT_DOCUMENT_ACTION_FILE_IDS = 1000;

    /** {@code PENDING_PARSE_STATUSES}。 */
    public static final List<String> PENDING_PARSE_STATUSES = List.of("uploaded");

    /** {@code PENDING_INDEX_STATUSES}。 */
    public static final List<String> PENDING_INDEX_STATUSES = List.of("parsed", "error_indexing");

    /** {@code VIRTUAL_FOLDER_MIGRATION_TASK_TYPE}。 */
    public static final String VIRTUAL_FOLDER_MIGRATION_TASK_TYPE = "knowledge_virtual_folder_migration";

    /** 参考实现 {@code media_types} 字典，键序一致。 */
    public static final Map<String, String> MEDIA_TYPES;

    static {
        Map<String, String> types = new LinkedHashMap<>();
        types.put(".pdf", "application/pdf");
        types.put(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        types.put(".txt", "text/plain");
        types.put(".md", "text/markdown");
        types.put(".json", "application/json");
        types.put(".csv", "text/csv");
        types.put(".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        types.put(".xls", "application/vnd.ms-excel");
        types.put(".pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        types.put(".ppt", "application/vnd.ms-powerpoint");
        types.put(".jpg", "image/jpeg");
        types.put(".jpeg", "image/jpeg");
        types.put(".png", "image/png");
        types.put(".gif", "image/gif");
        types.put(".bmp", "image/bmp");
        types.put(".svg", "image/svg+xml");
        types.put(".zip", "application/zip");
        types.put(".rar", "application/x-rar-compressed");
        types.put(".7z", "application/x-7z-compressed");
        types.put(".tar", "application/x-tar");
        types.put(".gz", "application/gzip");
        types.put(".html", "text/html");
        types.put(".htm", "text/html");
        types.put(".xml", "text/xml");
        types.put(".css", "text/css");
        types.put(".js", "application/javascript");
        types.put(".py", "text/x-python");
        types.put(".java", "text/x-java-source");
        types.put(".cpp", "text/x-c++src");
        types.put(".c", "text/x-csrc");
        types.put(".h", "text/x-chdr");
        types.put(".hpp", "text/x-c++hdr");
        MEDIA_TYPES = java.util.Collections.unmodifiableMap(types);
    }

    // ==================== 依赖 ====================

    private final KnowledgeBaseManager knowledgeBase;
    private final TaskService tasker;
    private final MilvusGraphService milvusGraphService;

    public KnowledgeRouteSupport(
            KnowledgeBaseManager knowledgeBase,
            TaskService tasker,
            MilvusGraphService milvusGraphService) {
        this.knowledgeBase = knowledgeBase;
        this.tasker = tasker;
        this.milvusGraphService = milvusGraphService;
    }

    /** 知识库运行时（对应参考实现模块级单例 {@code knowledge_base}）。 */
    public KnowledgeBaseManager knowledgeBase() {
        return knowledgeBase;
    }

    /** 任务队列（对应参考实现模块级单例 {@code tasker}）。 */
    public TaskService tasker() {
        return tasker;
    }

    /** 图谱服务（对应参考实现按需构造的 {@code MilvusGraphService()}）。 */
    public MilvusGraphService graphService() {
        return milvusGraphService;
    }

    // ==================== 模块级函数 ====================

    /**
     * 校验知识库存在且支持文档全文操作（对应 {@code _ensure_database_supports_documents}）。
     *
     * @return 知识库详情（调用方会读 {@code pendingParseCount / pendingIndexCount}）
     */
    public KnowledgeBaseDetail ensureDatabaseSupportsDocuments(String kbId, String operation) {
        KnowledgeBaseManager.DatabaseDocumentSupport support = knowledgeBase.getDatabaseDocumentSupport(kbId);
        if (support.database() == null) {
            throw new ApiHttpException(404, "知识库 " + kbId + " 不存在");
        }
        if (!support.supportsDocuments()) {
            KnowledgeBaseDetail dbInfo = support.database();
            String kbType = dbInfo.kbType() == null ? "" : dbInfo.kbType().toLowerCase();
            String name = dbInfo.name() == null || dbInfo.name().isEmpty() ? kbType : dbInfo.name();
            throw new ApiHttpException(400, name + " 只支持检索，不支持" + operation);
        }
        return support.database();
    }

    /** 当请求携带 kb_id 时校验当前用户的管理权限（对应 {@code _require_manage_permission_if_kb_id}）。 */
    public void requireManagePermissionIfKbId(String kbId) {
        if (kbId != null && !kbId.isEmpty()) {
            KnowledgePermissions.ensureKnowledgeBasePermission(kbId, ResourcePermission.MANAGE);
        }
    }

    /**
     * 归一化文件处理参数（对应 {@code _ensure_document_params}）。
     *
     * <p>参考实现签名是 {@code params: dict | None}，非 dict 时显式 400，故入参放宽为 {@link Object}。
     */
    public Map<String, Object> ensureDocumentParams(Object params) {
        if (params == null) {
            return new LinkedHashMap<>();
        }
        if (!(params instanceof Map<?, ?> raw)) {
            throw new ApiHttpException(400, "params must be an object");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            normalized.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return normalized;
    }

    /** 校验已上传文档条目（对应 {@code _validate_uploaded_document_items}）。 */
    public void validateUploadedDocumentItems(List<String> items, Map<String, Object> params) {
        if (items == null || items.isEmpty()) {
            throw new ApiHttpException(400, "items must not be empty");
        }
        Map<?, ?> paramsMap = params == null ? Map.of() : params;

        Object contentHashes = paramsMap.get("content_hashes");
        if (contentHashes != null && !(contentHashes instanceof Map)) {
            throw new ApiHttpException(400, "params.content_hashes must be an object");
        }
        Object fileSizes = paramsMap.get("file_sizes");
        if (fileSizes != null && !(fileSizes instanceof Map)) {
            throw new ApiHttpException(400, "params.file_sizes must be an object");
        }
        Object preprocessedMap = paramsMap.get("_preprocessed_map");
        if (preprocessedMap != null && !(preprocessedMap instanceof Map)) {
            throw new ApiHttpException(400, "params._preprocessed_map must be an object");
        }
        Map<?, ?> contentHashesMap = contentHashes instanceof Map ? (Map<?, ?>) contentHashes : null;
        Map<?, ?> preprocessed = preprocessedMap instanceof Map ? (Map<?, ?>) preprocessedMap : null;

        for (String item : items) {
            if (item == null || item.strip().isEmpty()) {
                throw new ApiHttpException(400, "items must only contain non-empty strings");
            }
            if (!KbUtils.isMinioUrl(item)) {
                throw new ApiHttpException(400, "File source must be a MinIO URL");
            }
            boolean hasContentHash = contentHashesMap != null && truthy(contentHashesMap.get(item));
            Object preprocessedItem = preprocessed == null ? null : preprocessed.get(item);
            boolean hasPreprocessedHash =
                    preprocessedItem instanceof Map<?, ?> itemMap && truthy(itemMap.get("content_hash"));
            if (!hasContentHash && !hasPreprocessedHash) {
                throw new ApiHttpException(400, "Missing content_hash for file: " + item);
            }
        }
    }

    /** 校验直接操作的文件 ID 列表（对应 {@code _validate_direct_document_action_file_ids}）。 */
    public List<String> validateDirectDocumentActionFileIds(List<String> fileIds) {
        List<String> normalized = new java.util.ArrayList<>();
        if (fileIds != null) {
            for (String fileId : fileIds) {
                if (fileId != null && !fileId.isEmpty()) {
                    normalized.add(fileId);
                }
            }
        }
        if (normalized.isEmpty()) {
            throw new ApiHttpException(400, "请选择至少一个文件");
        }
        if (normalized.size() > MAX_DIRECT_DOCUMENT_ACTION_FILE_IDS) {
            throw new ApiHttpException(
                    400,
                    "单次最多支持 " + MAX_DIRECT_DOCUMENT_ACTION_FILE_IDS
                            + " 个文件，请使用待处理状态入口提交全量后台任务");
        }
        return normalized;
    }

    /** 提交管理端指定文件的解析或入库任务（对应 {@code _enqueue_document_action_task}）。 */
    public Map<String, Object> enqueueDocumentActionTask(
            String kbId, List<String> fileIds, Map<String, Object> params,
            String operatorId, KnowledgeBaseDetail dbInfo, String action) {
        String label = "parse".equals(action) ? "解析" : "入库";
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kb_id", kbId);
            payload.put("file_ids", fileIds);
            payload.put("params", params);
            payload.put("operator_id", operatorId);
            TaskService.Task task = tasker.enqueue(
                    "文档" + label + " (" + dbInfo.name() + ")",
                    "knowledge_" + action,
                    payload,
                    null);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message", label + "任务已提交");
            result.put("status", "queued");
            result.put("task_id", task.id);
            return result;
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message", "提交失败: " + exception.getMessage());
            result.put("status", "failed");
            return result;
        }
    }

    /** 提交管理端按状态全量解析或入库任务（对应 {@code _enqueue_pending_document_action_task}）。 */
    public Map<String, Object> enqueuePendingDocumentActionTask(
            String kbId, Map<String, Object> params, String operatorId,
            KnowledgeBaseDetail dbInfo, String action) {
        String label;
        long pendingCount;
        List<String> statuses;
        if ("parse".equals(action)) {
            label = "解析";
            pendingCount = dbInfo.pendingParseCount();
            statuses = PENDING_PARSE_STATUSES;
        } else {
            label = "入库";
            pendingCount = dbInfo.pendingIndexCount();
            statuses = PENDING_INDEX_STATUSES;
        }

        if (pendingCount <= 0) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message", "没有待" + label + "文档");
            result.put("status", "success");
            result.put("queued_count", 0);
            return result;
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kb_id", kbId);
            payload.put("scope", "pending");
            payload.put("action", action);
            payload.put("statuses", statuses);
            payload.put("count", pendingCount);
            payload.put("params", params);
            payload.put("operator_id", operatorId);
            Map<String, Object> payloadMatch = new LinkedHashMap<>();
            payloadMatch.put("kb_id", kbId);
            payloadMatch.put("scope", "pending");
            payloadMatch.put("action", action);
            TaskService.EnqueueResult enqueued = tasker.enqueueUniqueByPayload(
                    "待" + label + "文档" + label + " (" + dbInfo.name() + ")",
                    "knowledge_" + action,
                    payload,
                    payloadMatch,
                    null);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message", enqueued.created()
                    ? label + "任务已提交" : "已有待" + label + "任务正在执行");
            result.put("status", "queued");
            result.put("task_id", enqueued.task().id);
            result.put("queued_count", pendingCount);
            return result;
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message", "提交失败: " + exception.getMessage());
            result.put("status", "failed");
            return result;
        }
    }

    /** 删除文档在对象存储中的原始文件、解析结果与预览 PDF（对应 {@code _delete_document_storage_objects}）。 */
    public void deleteDocumentStorageObjects(String kbId, String docId, String filePath) {
        MinioStorageClient minioClient = MinioStorageClient.getMinioClient();

        if (KbUtils.isMinioUrl(filePath)) {
            try {
                String[] parsed = KbUtils.parseMinioUrl(filePath);
                minioClient.deleteFile(parsed[0], parsed[1]);
            } catch (Exception minioError) {
                log.warn("从MinIO删除原始文件失败: {}", minioError.getMessage());
            }
        }

        try {
            minioClient.deleteFile(
                    MinioStorageClient.KB_BUCKETS.get("parsed"), kbId + "/parsed/" + docId + ".md");
        } catch (Exception minioError) {
            log.warn("从MinIO删除解析结果失败: {}", minioError.getMessage());
        }

        try {
            minioClient.deleteFile(
                    MinioStorageClient.KB_BUCKETS.get("parsed"), kbId + "/preview/" + docId + ".pdf");
        } catch (Exception minioError) {
            log.warn("从MinIO删除预览 PDF 失败: {}", minioError.getMessage());
        }
    }

    /** 该知识库是否有正在运行的图谱构建任务（对应 {@code _has_running_graph_build_task}）。 */
    public boolean hasRunningGraphBuildTask(String kbId) {
        return tasker.findTaskByPayload(
                MilvusGraphService.GRAPH_TASK_TYPE,
                Map.of("kb_id", kbId),
                ACTIVE_GRAPH_BUILD_STATUSES) != null;
    }

    // ==================== 纯工具（供各控制器直接调用） ====================

    /**
     * {@code os.path.splitext} 的等价实现（含"前导点不算扩展名分隔"的语义）。
     *
     * <p>{@code ".bashrc" → (".bashrc", "")}、{@code "dir/.hidden.txt" → ("dir/.hidden", ".txt")}、
     * {@code "..." → ("...", "")}。
     */
    public static String[] splitExt(String path) {
        String value = path == null ? "" : path;
        int sepIndex = value.lastIndexOf('/');
        int dotIndex = value.lastIndexOf('.');
        if (dotIndex > sepIndex) {
            int filenameIndex = sepIndex + 1;
            while (filenameIndex < dotIndex) {
                if (value.charAt(filenameIndex) != '.') {
                    return new String[] {value.substring(0, dotIndex), value.substring(dotIndex)};
                }
                filenameIndex++;
            }
        }
        return new String[] {value, ""};
    }

    /** 扩展名 → media type（对应参考实现 {@code media_types.get(ext.lower(), "application/octet-stream")}）。 */
    public static String mediaTypeFor(String extension) {
        if (extension == null || extension.isEmpty()) {
            return "application/octet-stream";
        }
        return MEDIA_TYPES.getOrDefault(extension.toLowerCase(), "application/octet-stream");
    }

    /**
     * {@code urllib.parse.unquote(value, encoding="utf-8")} 的等价实现。
     *
     * <p>不能用 {@link java.net.URLDecoder#decode}——那个按 {@code application/x-www-form-urlencoded}
     * 语义把 {@code +} 解成空格，而 {@code unquote} 不会。非法转义序列按 {@code unquote} 的
     * {@code errors='replace'} 语义原样保留。
     */
    public static String unquote(String value) {
        if (value == null || value.indexOf('%') < 0) {
            return value;
        }
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '%' && index + 2 < value.length()) {
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high >= 0 && low >= 0) {
                    buffer.write((high << 4) + low);
                    index += 3;
                    continue;
                }
            }
            byte[] encoded = String.valueOf(current).getBytes(StandardCharsets.UTF_8);
            buffer.write(encoded, 0, encoded.length);
            index++;
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 子集式 Map 取值转换（{@code Map<?,?>} → {@code Map<String,Object>}，键序保持）。 */
    public static Map<String, Object> asStringKeyedMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> raw) {
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    /** {@code list[str] | None} → 去空后的 {@link List}（元素非字符串时按 {@code str()} 归一）。 */
    public static List<String> asStringList(Object value) {
        List<String> result = new java.util.ArrayList<>();
        if (value instanceof List<?> raw) {
            for (Object item : raw) {
                result.add(item == null ? "" : String.valueOf(item));
            }
        }
        return result;
    }

    /** 去重且保序的集合构造（供 SSE 终态判定等场景复用）。 */
    public static Set<String> orderedSet(String... values) {
        return new LinkedHashSet<>(List.of(values));
    }

    /** Python 真值语义（非 null / 非 false / 非空串 / 非空集合 / 非 0 数字）。 */
    public static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return !text.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (value instanceof Iterable<?> iterable) {
            return iterable.iterator().hasNext();
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0;
        }
        return true;
    }

    /** 文本归一（null → 空串，便于与参考实现的 f-string 拼接一致）。 */
    public static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
