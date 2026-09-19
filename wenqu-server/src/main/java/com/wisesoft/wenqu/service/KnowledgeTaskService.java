package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.knowledge.KbUtils;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseRuntime;
import com.wisesoft.wenqu.models.TaskRecord;
import com.wisesoft.wenqu.repositories.KnowledgeFileRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 知识库任务的领域 Handler：摄取（添加→解析→可选入库）、解析、入库、图谱、虚拟目录迁移。
 *
 * <p>由参考实现的 services/knowledge_task_service.py 逐函数翻译。逐函数对应关系：
 * <ul>
 *   <li>{@link #runKnowledgeIngest} ← run_knowledge_ingest</li>
 *   <li>{@link #runKnowledgeParse} ← run_knowledge_parse</li>
 *   <li>{@link #runKnowledgeIndex} ← run_knowledge_index</li>
 *   <li>{@link #runVirtualFolderMigration} ← run_virtual_folder_migration</li>
 *   <li>{@link #runKnowledgeGraph} ← run_knowledge_graph</li>
 *   <li>{@link #failKnowledgeFileTask} ← fail_knowledge_file_task</li>
 * </ul>
 *
 * <p>必要替换（逐条标注）：
 * <ul>
 *   <li>模块级单例 {@code knowledge_base}（knowledge/runtime.py 的懒初始化 manager）→
 *       容器 bean {@link KnowledgeBaseRuntime}（本工程把 manager 门面与 executor 收敛到同一类，
 *       见其类注释）；模块级 {@code knowledge_folder_service} → {@link KnowledgeFolderService}。
 *   <li>{@code await context.set_progress(...)} → {@code context.setProgress(...)}（同步阻塞，
 *       由任务线程承载）；{@code asyncio.CancelledError} → {@link TaskService.TaskContext.Cancelled}。
 *   <li>failure hook 去掉了 session 首参：参考实现为同事务写库显式传会话，本工程钩子在调用方
 *       事务内执行，仓储方法自带 {@code @Transactional}，语义一致。
 *   <li>{@code TimeoutError} → {@code java.util.concurrent.TimeoutException}。
 *   <li>参考实现的 {@code dict} 用 {@code Map<String, Object>} 表达；{@code item} 项为
 *       文件来源 URL 字符串（参考实现为 str），故签名取 {@code Object} 以便原样回填。
 * </ul>
 *
 * <p>能力差异（如实标注，不谎称已支持）：
 * {@link #runKnowledgeGraph} 依赖参考实现的 knowledge/graphs/milvus_graph_service（图谱构建 /
 * 向量索引修复），该模块尚未移植，故本方法显式抛出 {@link UnsupportedOperationException}，
 * 使任务以明确错误失败而非静默返回零值假装完成。
 */
@Service
public class KnowledgeTaskService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTaskService.class);

    /** 待处理扫描的批大小（对应 DOCUMENT_ACTION_BATCH_SIZE）。 */
    private static final int DOCUMENT_ACTION_BATCH_SIZE = 500;

    /** 结果样本条数上限（对应 DOCUMENT_ACTION_RESULT_ITEM_LIMIT）。 */
    private static final int DOCUMENT_ACTION_RESULT_ITEM_LIMIT = 200;

    /** 摄取索引阶段只透传的分块参数键（对应 indexing_params 的取值口径）。 */
    private static final List<String> INDEXING_PARAM_KEYS = List.of("chunk_preset_id", "chunk_parser_config");

    private final KnowledgeBaseRuntime knowledgeBase;
    private final KnowledgeFolderService knowledgeFolderService;
    private final KnowledgeFileRepository knowledgeFileRepository;

    public KnowledgeTaskService(
            KnowledgeBaseRuntime knowledgeBase,
            KnowledgeFolderService knowledgeFolderService,
            KnowledgeFileRepository knowledgeFileRepository) {
        this.knowledgeBase = knowledgeBase;
        this.knowledgeFolderService = knowledgeFolderService;
        this.knowledgeFileRepository = knowledgeFileRepository;
    }

    // ==================== 失败钩子 ====================

    /** 原子收敛仍由失败 Task attempt 拥有的知识文件中间态（对应 fail_knowledge_file_task）。 */
    public void failKnowledgeFileTask(TaskRecord taskRecord, String error) throws Exception {
        knowledgeFileRepository.failTaskProcessingInSession(taskRecord.getId(), error);
    }

    // ==================== 摄取 ====================

    /** 从持久 payload 重建文档添加、解析和可选索引流程（对应 run_knowledge_ingest）。 */
    public Object runKnowledgeIngest(TaskService.TaskContext context) throws Exception {
        Map<String, Object> payload = context.payload();
        String kbId = str(payload.get("kb_id"));
        List<Object> items = new ArrayList<>(asList(payload.get("items")));
        Map<String, Object> params = asMap(payload.get("params"));
        String operatorId = str(payload.get("operator_id"));
        boolean autoIndex = truthy(params.get("auto_index"));
        Map<String, Object> indexingParams = new LinkedHashMap<>();
        for (String key : INDEXING_PARAM_KEYS) {
            if (params.containsKey(key) && params.get(key) != null) {
                indexingParams.put(key, params.get(key));
            }
        }
        String processingTaskId = context.taskId();
        String processingOwner = context.workerId();

        context.setProgress(5.0, "准备处理文档");
        int total = items.size();
        List<Object> processedItems = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            processedItems.add(null);
        }
        List<Map<String, Object>> addedFiles = new ArrayList<>();

        try {
            for (int index = 1; index <= total; index++) {
                context.raiseIfCancelled();
                Object item = items.get(index - 1);
                context.setProgress(5.0 + ((double) index / total) * 25.0,
                        "[1/3] 添加记录 " + index + "/" + total);
                try {
                    Map<String, Object> fileMeta = knowledgeBase.addFileRecord(
                            kbId, str(item), KbUtils.paramsForUploadedDocument(str(item), params), operatorId);
                    Map<String, Object> record = new LinkedHashMap<>();
                    record.put("index", index - 1);
                    record.put("item", item);
                    record.put("file_id", fileMeta.get("file_id"));
                    record.put("file_meta", fileMeta);
                    addedFiles.add(record);
                } catch (Exception exception) {
                    log.error("添加文件记录失败 {}: {}", item, exception.getMessage());
                    boolean timeout = exception instanceof java.util.concurrent.TimeoutException;
                    String errorType = timeout ? "timeout" : "add_failed";
                    String errorMessage = timeout ? "添加超时" : "添加记录失败";
                    Map<String, Object> failed = new LinkedHashMap<>();
                    failed.put("item", item);
                    failed.put("status", "failed");
                    failed.put("error", errorMessage + ": " + exception.getMessage());
                    failed.put("error_type", errorType);
                    processedItems.set(index - 1, failed);
                }
            }

            double parseEnd = autoIndex ? 60.0 : 95.0;
            for (int index = 1; index <= addedFiles.size(); index++) {
                context.raiseIfCancelled();
                Map<String, Object> record = addedFiles.get(index - 1);
                context.setProgress(30.0 + ((double) index / addedFiles.size()) * (parseEnd - 30.0),
                        "[2/3] 解析文件 " + index + "/" + addedFiles.size());
                try {
                    Map<String, Object> fileMeta = knowledgeBase.parseFile(
                            kbId, str(record.get("file_id")), operatorId, processingTaskId, processingOwner);
                    record.put("file_meta", fileMeta);
                    if (!autoIndex || !"parsed".equals(str(fileMeta.get("status")))) {
                        processedItems.set((Integer) record.get("index"), fileMeta);
                    }
                } catch (Exception exception) {
                    log.error("解析文件失败 {} (file_id={}): {}",
                            record.get("item"), record.get("file_id"), exception.getMessage());
                    boolean timeout = exception instanceof java.util.concurrent.TimeoutException;
                    String errorType = timeout ? "timeout" : "parse_failed";
                    String errorMessage = timeout ? "解析超时" : "解析失败";
                    Map<String, Object> failed = new LinkedHashMap<>();
                    failed.put("item", record.get("item"));
                    failed.put("status", "failed");
                    failed.put("error", errorMessage + ": " + exception.getMessage());
                    failed.put("error_type", errorType);
                    processedItems.set((Integer) record.get("index"), failed);
                }
            }

            if (autoIndex) {
                List<Map<String, Object>> parsedFiles = new ArrayList<>();
                for (Map<String, Object> record : addedFiles) {
                    Map<String, Object> meta = asMap(record.get("file_meta"));
                    if ("parsed".equals(str(meta.get("status")))) {
                        parsedFiles.add(record);
                    }
                }
                for (int index = 1; index <= parsedFiles.size(); index++) {
                    context.raiseIfCancelled();
                    Map<String, Object> record = parsedFiles.get(index - 1);
                    context.setProgress(60.0 + ((double) index / parsedFiles.size()) * 35.0,
                            "[3/3] 入库文件 " + index + "/" + parsedFiles.size());
                    try {
                        knowledgeBase.updateFileParams(
                                kbId, str(record.get("file_id")), indexingParams, operatorId);
                        processedItems.set((Integer) record.get("index"), knowledgeBase.indexFile(
                                kbId, str(record.get("file_id")), operatorId, indexingParams,
                                processingTaskId, processingOwner));
                    } catch (Exception exception) {
                        log.error("自动入库失败 {} (file_id={}): {}",
                                record.get("item"), record.get("file_id"), exception.getMessage());
                        Map<String, Object> failed = new LinkedHashMap<>();
                        failed.put("item", record.get("item"));
                        failed.put("status", "failed");
                        failed.put("error", "入库失败: " + exception.getMessage());
                        failed.put("error_type", "index_failed");
                        processedItems.set((Integer) record.get("index"), failed);
                    }
                }
            }
        } catch (TaskService.TaskContext.Cancelled cancelled) {
            context.setProgress(100.0, "任务已取消");
            throw cancelled;
        }

        context.raiseIfCancelled();
        List<Object> finalItems = new ArrayList<>();
        for (int index = 0; index < processedItems.size(); index++) {
            Object item = processedItems.get(index);
            if (item != null) {
                finalItems.add(item);
            } else {
                Map<String, Object> notProcessed = new LinkedHashMap<>();
                notProcessed.put("item", items.get(index));
                notProcessed.put("status", "failed");
                notProcessed.put("error", "文件未处理");
                notProcessed.put("error_type", "not_processed");
                finalItems.add(notProcessed);
            }
        }
        long failedCount = 0L;
        for (Object item : finalItems) {
            if (isFailedItem(item)) {
                failedCount++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("kb_id", kbId);
        summary.put("item_type", "文件");
        summary.put("submitted", total);
        summary.put("failed", failedCount);
        summary.put("items", finalItems);
        context.setResult(summary);
        context.setProgress(100.0, failedCount > 0
                ? "文件处理完成，失败 " + failedCount + " 个" : "文件处理完成");
        if (failedCount > 0) {
            throw new IllegalStateException("文件处理完成，失败 " + failedCount + " 个");
        }
        return summary;
    }

    // ==================== 解析 / 入库 ====================

    /** 按指定文件或待处理状态执行可重建的解析任务（对应 run_knowledge_parse）。 */
    public Object runKnowledgeParse(TaskService.TaskContext context) throws Exception {
        if ("pending".equals(str(context.payload().get("scope")))) {
            return runPendingFiles(context, "parse");
        }
        return runFileIds(context, "parse");
    }

    /** 按指定文件或待处理状态执行可重建的索引任务（对应 run_knowledge_index）。 */
    public Object runKnowledgeIndex(TaskService.TaskContext context) throws Exception {
        if ("pending".equals(str(context.payload().get("scope")))) {
            return runPendingFiles(context, "index");
        }
        return runFileIds(context, "index");
    }

    /** 按显式 file_ids 执行（对应 _run_file_ids）。 */
    private Map<String, Object> runFileIds(TaskService.TaskContext context, String action) throws Exception {
        Map<String, Object> payload = context.payload();
        String kbId = str(payload.get("kb_id"));
        List<Object> fileIds = asList(payload.get("file_ids"));
        Map<String, Object> params = asMap(payload.get("params"));
        String operatorId = str(payload.get("operator_id"));
        String label = "parse".equals(action) ? "解析" : "入库";
        String processingTaskId = context.taskId();
        String processingOwner = context.workerId();
        context.setProgress(5.0, "准备" + label + "文档");

        List<Object> processedItems = new ArrayList<>();
        for (int index = 1; index <= fileIds.size(); index++) {
            context.raiseIfCancelled();
            String fileId = str(fileIds.get(index - 1));
            context.setProgress(5.0 + ((double) index / fileIds.size()) * 90.0,
                    "正在" + label + "第 " + index + "/" + fileIds.size() + " 个文档");
            try {
                if (!params.isEmpty()) {
                    knowledgeBase.updateFileParams(kbId, fileId, params, operatorId);
                }
                Object result;
                if ("parse".equals(action)) {
                    result = knowledgeBase.parseFile(
                            kbId, fileId, operatorId, processingTaskId, processingOwner);
                } else {
                    result = knowledgeBase.indexFile(
                            kbId, fileId, operatorId, params, processingTaskId, processingOwner);
                }
                processedItems.add(result);
            } catch (Exception exception) {
                log.error("{} failed for {}: {}", label, fileId, exception.getMessage());
                Map<String, Object> failed = new LinkedHashMap<>();
                failed.put("file_id", fileId);
                failed.put("status", "failed");
                failed.put("error", String.valueOf(exception.getMessage()));
                processedItems.add(failed);
            }
        }

        context.raiseIfCancelled();
        long failedCount = 0L;
        for (Object item : processedItems) {
            if (isFailedItem(item)) {
                failedCount++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", processedItems);
        result.put("processed", processedItems.size());
        result.put("failed", failedCount);
        context.setResult(result);
        context.setProgress(100.0, label + "完成，失败 " + failedCount + " 个");
        return result;
    }

    /** 按状态集合分页扫描待处理文件（对应 _run_pending_files）。 */
    private Map<String, Object> runPendingFiles(TaskService.TaskContext context, String action) throws Exception {
        Map<String, Object> payload = context.payload();
        String kbId = str(payload.get("kb_id"));
        List<Object> statuses = asList(payload.get("statuses"));
        int initialTotal = toInt(payload.get("count"));
        Map<String, Object> params = asMap(payload.get("params"));
        String operatorId = str(payload.get("operator_id"));
        String label = "parse".equals(action) ? "解析" : "入库";
        String processingTaskId = context.taskId();
        String processingOwner = context.workerId();
        context.setProgress(5.0, "准备" + label + "待处理文档");

        int processedCount = 0;
        int failedCount = 0;
        List<Object> resultItems = new ArrayList<>();
        String afterFileId = null;
        while (true) {
            List<String> fileIds = knowledgeBase.listDocumentFileIdsByStatuses(
                    kbId, toStringList(statuses), afterFileId, DOCUMENT_ACTION_BATCH_SIZE);
            if (fileIds.isEmpty()) {
                break;
            }
            for (String fileId : fileIds) {
                context.raiseIfCancelled();
                afterFileId = fileId;
                processedCount++;
                int progressTotal = Math.max(initialTotal, processedCount);
                context.setProgress(5.0 + ((double) processedCount / progressTotal) * 90.0,
                        "正在" + label + "第 " + processedCount + "/" + progressTotal + " 个文档");
                try {
                    if ("parse".equals(action)) {
                        if (!params.isEmpty()) {
                            try {
                                knowledgeBase.updateFileParams(kbId, fileId, params, operatorId);
                            } catch (Exception exception) {
                                log.error("Failed to update params for pending parse file {}: {}",
                                        fileId, exception.getMessage());
                            }
                        }
                        appendResultSample(resultItems, knowledgeBase.parseFile(
                                kbId, fileId, operatorId, processingTaskId, processingOwner));
                    } else {
                        if (!params.isEmpty()) {
                            knowledgeBase.updateFileParams(kbId, fileId, params, operatorId);
                        }
                        appendResultSample(resultItems, knowledgeBase.indexFile(
                                kbId, fileId, operatorId, params, processingTaskId, processingOwner));
                    }
                } catch (Exception exception) {
                    failedCount++;
                    log.error("Pending {} failed for {}: {}", action, fileId, exception.getMessage());
                    Map<String, Object> failed = new LinkedHashMap<>();
                    failed.put("file_id", fileId);
                    failed.put("status", "failed");
                    failed.put("error", String.valueOf(exception.getMessage()));
                    appendResultSample(resultItems, failed);
                }
            }
        }

        context.raiseIfCancelled();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", resultItems);
        result.put("processed", processedCount);
        result.put("failed", failedCount);
        result.put("result_truncated", processedCount > resultItems.size());
        context.setResult(result);
        context.setProgress(100.0, processedCount > 0
                ? label + "完成，失败 " + failedCount + " 个" : "没有待" + label + "文档");
        return result;
    }

    // ==================== 虚拟目录迁移 ====================

    /** 从持久 payload 重建历史虚拟目录迁移（对应 run_virtual_folder_migration）。 */
    public Object runVirtualFolderMigration(TaskService.TaskContext context) throws Exception {
        return knowledgeFolderService.migrateVirtualFolderData(
                context, str(context.payload().get("kb_id")), str(context.payload().get("operator_id")));
    }

    // ==================== 图谱 ====================

    /**
     * 图谱构建 / 向量索引修复（对应 run_knowledge_graph）。
     *
     * <p>能力差异：依赖 knowledge/graphs/milvus_graph_service（reconcile_vectors /
     * build_pending_chunks），该模块尚未移植。此处显式失败，使任务状态如实反映"未实现"，
     * 不返回零值假装完成。
     */
    public Object runKnowledgeGraph(TaskService.TaskContext context) {
        String kbId = str(context.payload().get("kb_id"));
        throw new UnsupportedOperationException(
                "图谱服务尚未移植（knowledge/graphs/milvus_graph_service），无法执行知识图谱任务: kb_id=" + kbId);
    }

    // ==================== 工具 ====================

    /** 失败项判定（对应 _is_failed_item）。 */
    private static boolean isFailedItem(Object item) {
        if (!(item instanceof Map<?, ?> map)) {
            return false;
        }
        return "failed".equals(str(map.get("status"))) || truthy(map.get("error"));
    }

    /** 结果样本追加（对应 _append_result_sample）。 */
    private static void appendResultSample(List<Object> items, Object item) {
        if (items.size() < DOCUMENT_ACTION_RESULT_ITEM_LIMIT) {
            items.add(item);
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0.0;
        }
        if (value instanceof String s) {
            return !s.isEmpty() && !"false".equalsIgnoreCase(s) && !"0".equals(s);
        }
        if (value instanceof java.util.Collection<?> c) {
            return !c.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    private static int toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return (int) Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }

    private static List<String> toStringList(List<Object> values) {
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            result.add(str(value));
        }
        return result;
    }
}
