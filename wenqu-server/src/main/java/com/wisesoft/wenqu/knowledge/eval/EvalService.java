package com.wisesoft.wenqu.knowledge.eval;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseConfig;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseManager;
import com.wisesoft.wenqu.models.EvaluationDataset;
import com.wisesoft.wenqu.models.EvaluationDatasetItem;
import com.wisesoft.wenqu.models.EvaluationRun;
import com.wisesoft.wenqu.models.EvaluationRunItem;
import com.wisesoft.wenqu.models.KnowledgeBase;
import com.wisesoft.wenqu.models.TaskRecord;
import com.wisesoft.wenqu.repositories.EvaluationRepository;
import com.wisesoft.wenqu.repositories.KnowledgeBaseRepository;
import com.wisesoft.wenqu.repositories.KnowledgeChunkRepository;
import com.wisesoft.wenqu.repositories.TaskRepository;
import com.wisesoft.wenqu.service.ModelSelectors;
import com.wisesoft.wenqu.service.TaskService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RAG 评估服务，对齐参考实现 {@code knowledge/eval/service.py} 的 {@code EvaluationService}。
 *
 * <p>覆盖数据集（上传/生成/导出/删除/恢复）与评估运行（启动/列表/结果/删除）两条线，
 * 并把「生成数据集」「执行评估」两个长任务交给 Durable Task 承载（Handler 见 {@link EvalTaskService}）。
 *
 * <p><b>必要替换（平台差异，逐条标注）</b>：
 * <ul>
 *   <li>事务边界：参考实现用 {@code async with pg_manager.get_async_session_context()} 显式包裹
 *       「创建 Task + 创建业务记录」，提交后才 publish；本工程用 {@link TransactionTemplate} 复刻同一
 *       边界（Service 内自调用 {@code @Transactional} 不会开启新事务，显式模板更可控）。</li>
 *   <li>批次落库大小：参考实现读以自身前缀命名的 {@code *_DATASET_PERSIST_BATCH_SIZE} 环境变量；
 *       本工程改读 {@code WENQU_DATASET_PERSIST_BATCH_SIZE}（本系统标识），语义与默认值（1）一致。</li>
 *   <li>取消异常：参考实现里 {@code asyncio.CancelledError} 不属于 {@code Exception}，因此
 *       「保存残余题目」的 best-effort 捕获不会吞掉取消；本工程的
 *       {@link TaskService.TaskContext.Cancelled} 是 {@code RuntimeException}，故在 best-effort
 *       捕获处显式重抛，保持"取消不被吞"的原语义。</li>
 * </ul>
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);

    /** 数据集批量落库大小（对应参考实现的 DATASET_PERSIST_BATCH_SIZE）。 */
    public static final int DATASET_PERSIST_BATCH_SIZE =
            Math.max(1, envInt("WENQU_DATASET_PERSIST_BATCH_SIZE", 1));

    /** run_id 格式校验（与参考实现的正则逐字一致）。 */
    private static final Pattern RUN_ID_PATTERN = Pattern.compile("^run_[a-f0-9]{8}$");

    private static final DateTimeFormatter DATE_PART_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 与参考实现一致的非法 run_id 文案。 */
    private static final String INVALID_RUN_ID = "Invalid run_id format";

    private final EvaluationRepository evalRepo;
    private final KnowledgeBaseRepository kbRepo;
    private final KnowledgeChunkRepository chunkRepo;
    private final TaskRepository taskRepo;
    private final TaskService taskService;
    private final KnowledgeBaseManager kbManager;
    private final EvalBenchmarkGeneration benchmarkGeneration;
    private final ModelSelectors modelSelectors;
    private final TransactionTemplate transactionTemplate;

    public EvalService(
            EvaluationRepository evalRepo,
            KnowledgeBaseRepository kbRepo,
            KnowledgeChunkRepository chunkRepo,
            TaskRepository taskRepo,
            TaskService taskService,
            KnowledgeBaseManager kbManager,
            EvalBenchmarkGeneration benchmarkGeneration,
            ModelSelectors modelSelectors,
            PlatformTransactionManager transactionManager) {
        this.evalRepo = evalRepo;
        this.kbRepo = kbRepo;
        this.chunkRepo = chunkRepo;
        this.taskRepo = taskRepo;
        this.taskService = taskService;
        this.kbManager = kbManager;
        this.benchmarkGeneration = benchmarkGeneration;
        this.modelSelectors = modelSelectors;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ==================== 记录投影 ====================

    /** 数据集投影（对应 {@code _dataset_to_dict}）。 */
    public Map<String, Object> datasetToDict(EvaluationDataset row) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", row.getDatasetId());
        data.put("dataset_id", row.getDatasetId());
        data.put("name", row.getName());
        data.put("description", row.getDescription());
        data.put("kb_id", row.getKbId());
        data.put("item_count", row.getItemCount());
        data.put("has_gold_chunks", row.getHasGoldChunks());
        data.put("has_gold_answers", row.getHasGoldAnswers());
        data.put("build_metadata", parseObjectOrEmpty(row.getBuildMetadata()));
        data.put("created_by", row.getCreatedBy());
        data.put("created_at", DateTimeUtils.formatUtcDatetime(row.getCreatedAt()));
        data.put("updated_at", DateTimeUtils.formatUtcDatetime(row.getUpdatedAt()));
        return data;
    }

    /** 数据集题目投影（对应 {@code _dataset_item_to_dict}）。 */
    public Map<String, Object> datasetItemToDict(EvaluationDatasetItem item) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("item_id", item.getItemId());
        data.put("item_index", item.getItemIndex());
        data.put("query", item.getQueryText());
        data.put("gold_chunk_ids", parseArrayOrEmpty(item.getGoldChunkIds()));
        data.put("gold_answer", item.getGoldAnswer());
        return data;
    }

    /** 运行明细投影（对应 {@code _run_item_to_dict}）。 */
    public Map<String, Object> runItemToDict(EvaluationRunItem item) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("item_index", item.getItemIndex());
        data.put("query", item.getQueryText());
        data.put("gold_chunk_ids", parseArrayOrEmpty(item.getGoldChunkIds()));
        data.put("gold_answer", item.getGoldAnswer());
        data.put("generated_answer", item.getGeneratedAnswer());
        data.put("retrieved_chunks", parseArrayOrEmpty(item.getRetrievedChunks()));
        data.put("metrics", parseObjectOrEmpty(item.getMetrics()));
        return data;
    }

    /** 判断评估结果是否符合指定筛选条件（对应 {@code _matches_result_filter}）。 */
    public boolean matchesResultFilter(EvaluationRunItem item, String resultFilter) {
        if ("all".equals(resultFilter)) {
            return true;
        }

        Map<String, Object> metrics = parseObjectOrEmpty(item.getMetrics());
        double score = metrics.containsKey("score") ? EvalMetrics.toDouble(metrics.get("score")) : 1.0;
        boolean answerError = score <= 0.5;
        if ("answer_errors".equals(resultFilter)) {
            return answerError;
        }
        if ("legacy_errors".equals(resultFilter)) {
            if (answerError) {
                return true;
            }
            for (Map.Entry<String, Object> entry : metrics.entrySet()) {
                if (entry.getKey().startsWith("recall@")
                        && EvalMetrics.toDouble(entry.getValue()) < 0.3) {
                    return true;
                }
            }
            return false;
        }

        double recallAt10 = metrics.containsKey("recall@10") ? EvalMetrics.toDouble(metrics.get("recall@10")) : 1.0;
        return answerError || recallAt10 < 1;
    }

    /** 运行名归一化（对应 {@code _normalize_run_name}）。 */
    public String normalizeRunName(String name, String runId) {
        String runName = name == null ? "" : name.strip();
        if (!runName.isEmpty()) {
            return runName;
        }
        return buildEvaluationRunName(null, stripRunPrefix(runId));
    }

    /** 从记录推导运行名（对应 {@code _run_name_from_row}）。 */
    public String runNameFromRow(EvaluationRun row) {
        String name = row.getName() == null ? "" : row.getName().strip();
        if (!name.isEmpty()) {
            return name;
        }
        return buildEvaluationRunName(row.getStartedAt(), stripRunPrefix(row.getRunId()));
    }

    // ==================== 数据集 ====================

    /** 上传 JSONL 数据集（对应 {@code upload_dataset}）。 */
    public Map<String, Object> uploadDataset(
            String kbId, byte[] fileContent, String filename, String name, String description, String createdBy) {
        try {
            ParsedQuestions parsed = parseJsonlQuestions(fileContent);
            String datasetId = "dataset_" + randomHex(8);
            String datasetName = name == null ? "" : name.strip();
            if (datasetName.isEmpty()) {
                datasetName = (filename == null || filename.isEmpty()) ? datasetId : filename;
            }

            Map<String, Object> buildMetadata = new LinkedHashMap<>();
            buildMetadata.put("source", "upload");
            buildMetadata.put("status", "completed");
            buildMetadata.put("progress", 100);
            buildMetadata.put("filename", filename);

            Map<String, Object> datasetData = new LinkedHashMap<>();
            datasetData.put("dataset_id", datasetId);
            datasetData.put("kb_id", kbId);
            datasetData.put("name", datasetName);
            datasetData.put("description", description);
            datasetData.put("item_count", parsed.questions().size());
            datasetData.put("has_gold_chunks", parsed.hasGoldChunks());
            datasetData.put("has_gold_answers", parsed.hasGoldAnswers());
            datasetData.put("build_metadata", buildMetadata);
            datasetData.put("created_by", createdBy);

            EvaluationDataset row = evalRepo.createDatasetWithItems(
                    datasetData, buildDatasetItems(datasetId, kbId, parsed.questions(), 0));
            return datasetToDict(row);
        } catch (Exception exc) {
            log.error("上传评估数据集失败: {}", exc.getMessage(), exc);
            throw exc;
        }
    }

    /** 数据集列表（对应 {@code list_datasets}）。 */
    public List<Map<String, Object>> listDatasets(String kbId) {
        try {
            List<EvaluationDataset> rows = evalRepo.listDatasets(kbId);
            List<Map<String, Object>> result = new ArrayList<>();
            for (EvaluationDataset row : rows) {
                syncDatasetBuildMetadata(row);
                result.add(datasetToDict(row));
            }
            return result;
        } catch (Exception exc) {
            log.error("获取评估数据集列表失败: {}", exc.getMessage(), exc);
            throw exc;
        }
    }

    /** 数据集详情（对应 {@code get_dataset_detail}）。 */
    public Map<String, Object> getDatasetDetail(String kbId, String datasetId, int page, int pageSize) {
        try {
            EvaluationDataset row = evalRepo.getDataset(datasetId);
            if (row == null || !equalsText(row.getKbId(), kbId)) {
                throw new IllegalArgumentException("Dataset not found");
            }
            syncDatasetBuildMetadata(row);
            Map<String, Object> metadata = parseObjectOrEmpty(row.getBuildMetadata());
            String status = String.valueOf(metadata.getOrDefault("status", "completed"));
            if (!"completed".equals(status) && !"failed".equals(status)) {
                throw new IllegalArgumentException("Dataset is not ready");
            }

            int totalItems = evalRepo.countDatasetItems(datasetId);
            List<EvaluationDatasetItem> items =
                    evalRepo.listDatasetItems(datasetId, (page - 1) * pageSize, pageSize);
            int totalPages = (totalItems + pageSize - 1) / pageSize;

            Map<String, Object> data = datasetToDict(row);
            List<Map<String, Object>> itemViews = new ArrayList<>();
            for (EvaluationDatasetItem item : items) {
                itemViews.add(datasetItemToDict(item));
            }
            Map<String, Object> pagination = new LinkedHashMap<>();
            pagination.put("current_page", page);
            pagination.put("page_size", pageSize);
            pagination.put("total_items", totalItems);
            pagination.put("total_pages", totalPages);
            pagination.put("has_next", page < totalPages);
            pagination.put("has_prev", page > 1);
            data.put("items", itemViews);
            data.put("pagination", pagination);
            return data;
        } catch (Exception exc) {
            log.error("获取评估数据集详情失败: {}", exc.getMessage(), exc);
            throw exc;
        }
    }

    /** 导出数据集 JSONL（对应 {@code export_dataset_jsonl}）。 */
    public Map<String, String> exportDatasetJsonl(String datasetId) {
        EvaluationDataset row = evalRepo.getDataset(datasetId);
        if (row == null) {
            throw new IllegalArgumentException("Dataset not found");
        }
        syncDatasetBuildMetadata(row);
        Map<String, Object> metadata = parseObjectOrEmpty(row.getBuildMetadata());
        if (!"completed".equals(String.valueOf(metadata.getOrDefault("status", "completed")))) {
            throw new IllegalArgumentException("Dataset is not ready");
        }
        List<EvaluationDatasetItem> items = evalRepo.listAllDatasetItems(datasetId);

        Map<String, String> result = new LinkedHashMap<>();
        result.put("filename", safeJsonlFilename(row.getName(), row.getDatasetId()));
        result.put("content", buildJsonlContent(items));
        return result;
    }

    /** 删除数据集（对应 {@code delete_dataset}）。 */
    public void deleteDataset(String datasetId) {
        try {
            EvaluationDataset row = evalRepo.getDataset(datasetId);
            if (row == null) {
                throw new IllegalArgumentException("Dataset not found");
            }
            evalRepo.deleteDataset(datasetId);
            log.info("成功删除评估数据集: {}", datasetId);
        } catch (Exception exc) {
            log.error("删除评估数据集失败: {}", exc.getMessage(), exc);
            throw exc;
        }
    }

    /** 恢复数据集生成（对应 {@code resume_dataset_generation}）。 */
    public Map<String, Object> resumeDatasetGeneration(String kbId, String datasetId, String createdBy) {
        EvaluationDataset row = evalRepo.getDataset(datasetId);
        if (row == null || !equalsText(row.getKbId(), kbId)) {
            throw new IllegalArgumentException("Dataset not found");
        }
        Map<String, Object> metadata = parseObjectOrEmpty(row.getBuildMetadata());
        if (!"generated".equals(String.valueOf(metadata.get("source")))) {
            throw new IllegalArgumentException("只能恢复自动生成的数据集");
        }
        Map<String, Object> params = asMap(metadata.get("params"));
        if (params.isEmpty()) {
            throw new IllegalArgumentException("数据集缺少生成参数");
        }

        int existingCount = evalRepo.countDatasetItems(datasetId);
        int totalCount = toInt(params.get("count"), 0);
        if (existingCount >= totalCount) {
            metadata.put("status", "completed");
            metadata.put("progress", 100);
            metadata.put("message", "完成");
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put("item_count", existingCount);
            updates.put("build_metadata", metadata);
            evalRepo.updateDataset(datasetId, updates);
            return messageResult(datasetId, "数据集已完成生成");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dataset_id", datasetId);
        payload.put("kb_id", kbId);
        payload.put("created_by", createdBy);
        payload.put("name", row.getName());
        payload.put("description", row.getDescription());
        payload.put("count", totalCount);
        payload.put("neighbors_count", toInt(params.get("neighbors_count"), 1));
        payload.put("concurrency_count", toInt(params.get("concurrency_count"), 10));
        payload.put("llm_model_spec", params.get("llm_model_spec"));
        payload.put("generation_mode", params.getOrDefault("generation_mode", "vector"));
        payload.put("graph_expand_top_k", toInt(params.get("graph_expand_top_k"), 1));

        TaskService.EnqueueResult enqueueResult;
        try {
            // 事务边界：任务去重与数据集关联必须同批提交（对齐参考实现的单 session 语义）
            enqueueResult = transactionTemplate.execute(status -> {
                try {
                    TaskService.EnqueueResult created = taskService.createUniqueInSession(
                            "继续生成评估数据集",
                            "dataset_generation",
                            payload,
                            Map.of("dataset_id", datasetId),
                            null);
                    EvaluationDataset attached = evalRepo.attachDatasetGenerationTaskInSession(
                            datasetId, created.task().id);
                    if (attached == null) {
                        throw new IllegalArgumentException("Dataset not found");
                    }
                    Map<String, Object> attachedMetadata = parseObjectOrEmpty(attached.getBuildMetadata());
                    if ("completed".equals(String.valueOf(attachedMetadata.get("status")))
                            && !equalsText(attachedMetadata.get("task_id"), created.task().id)) {
                        throw new DatasetAlreadyCompleted();
                    }
                    return created;
                } catch (RuntimeException exc) {
                    // 领域异常（含 DatasetAlreadyCompleted）直接冒泡，保留 404/400 语义
                    throw exc;
                } catch (Exception exc) {
                    throw new IllegalStateException(exc);
                }
            });
        } catch (DatasetAlreadyCompleted exc) {
            return messageResult(datasetId, "数据集已完成生成");
        } catch (Exception exc) {
            log.error("恢复评估数据集生成失败: {}", exc.getMessage(), exc);
            throw exc;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataset_id", datasetId);
        result.put("task_id", enqueueResult.task().id);
        if (enqueueResult.created()) {
            taskService.publish(enqueueResult.task());
            result.put("message", "评估数据集生成任务已恢复");
        } else {
            result.put("message", "已有进行中的生成任务");
        }
        return result;
    }

    /** 提交数据集生成任务（对应 {@code generate_dataset}）。 */
    public Map<String, Object> generateDataset(
            String kbId,
            String name,
            String description,
            int count,
            int neighborsCount,
            int concurrencyCount,
            String llmModelSpec,
            String generationMode,
            int graphExpandTopK,
            String createdBy) {
        String datasetId = "dataset_" + randomHex(8);
        int normalizedCount = count;
        int normalizedNeighbors = neighborsCount;
        int normalizedConcurrency = EvalBenchmarkGeneration.normalizeGenerationConcurrencyCount(concurrencyCount);
        int normalizedExpandTopK = Math.min(Math.max(1, graphExpandTopK), 3);
        if (!"vector".equals(generationMode) && !"graph_enhanced".equals(generationMode)) {
            throw new IllegalArgumentException("不支持的评估基准生成方式");
        }
        if ("graph_enhanced".equals(generationMode) && chunkRepo.countGraphIndexedByKbId(kbId) <= 0) {
            throw new IllegalArgumentException("当前知识库尚未完成图索引，无法使用图增强构建");
        }

        Map<String, Object> generationParams = new LinkedHashMap<>();
        generationParams.put("count", normalizedCount);
        generationParams.put("neighbors_count", normalizedNeighbors);
        generationParams.put("concurrency_count", normalizedConcurrency);
        generationParams.put("llm_model_spec", llmModelSpec);
        generationParams.put("generation_mode", generationMode);
        generationParams.put("graph_expand_top_k", normalizedExpandTopK);

        Map<String, Object> buildMetadata = new LinkedHashMap<>();
        buildMetadata.put("source", "generated");
        buildMetadata.put("status", "pending");
        buildMetadata.put("progress", 0);
        buildMetadata.put("params", generationParams);

        Map<String, Object> taskPayload = new LinkedHashMap<>();
        taskPayload.put("dataset_id", datasetId);
        taskPayload.put("kb_id", kbId);
        taskPayload.put("created_by", createdBy);
        taskPayload.put("name", name);
        taskPayload.put("description", description);
        taskPayload.putAll(generationParams);

        Map<String, Object> datasetData = new LinkedHashMap<>();
        datasetData.put("dataset_id", datasetId);
        datasetData.put("kb_id", kbId);
        datasetData.put("name", name);
        datasetData.put("description", description);
        datasetData.put("item_count", 0);
        datasetData.put("has_gold_chunks", true);
        datasetData.put("has_gold_answers", true);
        datasetData.put("created_by", createdBy);

        try {
            // 事务边界：Task 与 Dataset 必须同批提交，提交后才 publish（对齐参考实现）
            TaskService.Task task = transactionTemplate.execute(status -> {
                try {
                    TaskService.Task created = taskService.createInSession(
                            "生成评估数据集",
                            "dataset_generation",
                            taskPayload,
                            Map.of("dataset_id", datasetId),
                            null);
                    buildMetadata.put("task_id", created.id);
                    datasetData.put("build_metadata", buildMetadata);
                    evalRepo.createDatasetInSession(datasetData);
                    return created;
                } catch (Exception exc) {
                    throw new IllegalStateException(exc);
                }
            });
            taskService.publish(task);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dataset_id", datasetId);
            result.put("task_id", task.id);
            result.put("message", "评估数据集生成任务已提交");
            return result;
        } catch (Exception exc) {
            log.error("生成评估数据集失败: {}", exc.getMessage(), exc);
            throw exc;
        }
    }

    // ==================== 评估运行 ====================

    /** 提交评估运行（对应 {@code run_evaluation}）。 */
    public String runEvaluation(
            String kbId, String datasetId, String name, Map<String, Object> modelConfig, String createdBy) {
        try {
            String runId = "run_" + randomHex(8);
            String runName = normalizeRunName(name, runId);
            EvaluationDataset datasetRow = evalRepo.getDataset(datasetId);
            if (datasetRow == null || !equalsText(datasetRow.getKbId(), kbId)) {
                throw new IllegalArgumentException("Dataset not found");
            }
            Map<String, Object> datasetMetadata = parseObjectOrEmpty(datasetRow.getBuildMetadata());
            if (!"completed".equals(String.valueOf(datasetMetadata.getOrDefault("status", "completed")))) {
                throw new IllegalArgumentException("Dataset is not ready");
            }

            Map<String, Object> retrievalConfig = new LinkedHashMap<>();
            try {
                KnowledgeBase kbRow = kbRepo.getByKbId(kbId);
                Map<String, Object> queryParams = kbRow == null ? null : parseObjectOrEmpty(kbRow.getQueryParams());
                Object options = queryParams == null ? null : queryParams.get("options");
                if (options instanceof Map<?, ?>) {
                    retrievalConfig.putAll(asMap(options));
                }
                if (retrievalConfig.isEmpty()) {
                    KnowledgeBaseConfig config = kbManager.getKbConfig(kbId);
                    retrievalConfig.putAll(config.queryOptions());
                }
                log.info("从知识库 {} 加载检索配置: {}", kbId, retrievalConfig.keySet());
            } catch (Exception exc) {
                log.error("获取知识库检索配置失败: {}", exc.getMessage());
            }

            if (modelConfig != null) {
                retrievalConfig.putAll(modelConfig);
            }

            Map<String, Object> taskPayload = new LinkedHashMap<>();
            taskPayload.put("run_id", runId);
            taskPayload.put("name", runName);
            taskPayload.put("kb_id", kbId);
            taskPayload.put("dataset_id", datasetId);
            taskPayload.put("retrieval_config", retrievalConfig);
            taskPayload.put("created_by", createdBy);

            Map<String, Object> runData = new LinkedHashMap<>();
            runData.put("run_id", runId);
            runData.put("name", runName);
            runData.put("kb_id", kbId);
            runData.put("dataset_id", datasetId);
            runData.put("status", "running");
            runData.put("retrieval_config", retrievalConfig);
            runData.put("metrics", new LinkedHashMap<String, Object>());
            runData.put("overall_score", null);
            runData.put("total_items", datasetRow.getItemCount() == null ? 0 : datasetRow.getItemCount());
            runData.put("completed_items", 0);
            runData.put("started_at", DateTimeUtils.utcNowNaive());
            runData.put("completed_at", null);
            runData.put("created_by", createdBy);

            TaskService.Task task = transactionTemplate.execute(status -> {
                try {
                    TaskService.Task created = taskService.createInSession(
                            "RAG评估(" + runName + ")",
                            "rag_evaluation",
                            taskPayload,
                            Map.of("run_id", runId),
                            null);
                    evalRepo.createRunInSession(runData);
                    return created;
                } catch (Exception exc) {
                    throw new IllegalStateException(exc);
                }
            });
            taskService.publish(task);
            return runId;
        } catch (Exception exc) {
            log.error("启动评估失败: {}", exc.getMessage(), exc);
            throw exc;
        }
    }

    /** 运行历史（对应 {@code list_runs}）。 */
    public List<Map<String, Object>> listRuns(String kbId) {
        try {
            List<EvaluationRun> rows = evalRepo.listRuns(kbId);
            Set<String> runningRunIds = new LinkedHashSet<>();
            for (EvaluationRun row : rows) {
                if ("running".equals(row.getStatus())) {
                    runningRunIds.add(row.getRunId());
                }
            }

            Map<String, TaskRecord> taskByRunId = new LinkedHashMap<>();
            if (!runningRunIds.isEmpty()) {
                for (TaskRecord task : taskRepo.listByPayloadValues("rag_evaluation", "run_id", runningRunIds)) {
                    Object taskRunId = parseObjectOrEmpty(task.getPayload()).get("run_id");
                    if (taskRunId != null) {
                        taskByRunId.putIfAbsent(String.valueOf(taskRunId), task);
                    }
                }
            }

            List<Map<String, Object>> runs = new ArrayList<>();
            for (EvaluationRun row : rows) {
                TaskRecord task = syncEvaluationRun(row, taskByRunId.get(row.getRunId()));
                Map<String, Object> run = new LinkedHashMap<>();
                run.put("run_id", row.getRunId());
                run.put("name", runNameFromRow(row));
                run.put("dataset_id", row.getDatasetId());
                run.put("status", row.getStatus());
                run.put("started_at", DateTimeUtils.formatUtcDatetime(row.getStartedAt()));
                run.put("completed_at", DateTimeUtils.formatUtcDatetime(row.getCompletedAt()));
                run.put("total_items", row.getTotalItems());
                run.put("completed_items", row.getCompletedItems());
                run.put("overall_score", row.getOverallScore());
                run.put("retrieval_config", parseObjectOrEmpty(row.getRetrievalConfig()));
                run.put("metrics", parseObjectOrEmpty(row.getMetrics()));
                if ("running".equals(row.getStatus()) && task != null) {
                    run.put("progress", task.getProgress());
                    run.put("message", task.getMessage());
                }
                runs.add(run);
            }
            return runs;
        } catch (Exception exc) {
            log.error("获取评估运行历史失败: {}", exc.getMessage(), exc);
            throw exc;
        }
    }

    /** 运行结果分页（对应 {@code get_run_results}）。 */
    public Map<String, Object> getRunResults(
            String kbId, String runId, int page, int pageSize, String resultFilter) {
        Matcher matcher = RUN_ID_PATTERN.matcher(runId);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(INVALID_RUN_ID);
        }
        EvaluationRun row = evalRepo.getRun(runId);
        if (row == null || !equalsText(row.getKbId(), kbId)) {
            Map<String, Object> task = taskService.getTask(runId);
            if (task != null) {
                Map<String, Object> fallback = new LinkedHashMap<>();
                fallback.put("run_id", runId);
                fallback.put("status", task.get("status"));
                fallback.put("progress", task.get("progress"));
                fallback.put("message", task.get("message"));
                return fallback;
            }
            throw new IllegalArgumentException("Run not found for " + runId);
        }

        syncEvaluationRun(row);
        int startIndex = (page - 1) * pageSize;
        int total;
        List<Map<String, Object>> pagedItems = new ArrayList<>();
        if (!"all".equals(resultFilter)) {
            total = 0;
            int offset = 0;
            int batchSize = 200;
            while (true) {
                List<EvaluationRunItem> batch = evalRepo.listRunItems(runId, offset, batchSize);
                if (batch == null || batch.isEmpty()) {
                    break;
                }
                for (EvaluationRunItem item : batch) {
                    if (!matchesResultFilter(item, resultFilter)) {
                        continue;
                    }
                    if (startIndex <= total && total < startIndex + pageSize) {
                        pagedItems.add(runItemToDict(item));
                    }
                    total += 1;
                }
                offset += batchSize;
            }
        } else {
            total = evalRepo.countRunItems(runId);
            for (EvaluationRunItem item : evalRepo.listRunItems(runId, startIndex, pageSize)) {
                pagedItems.add(runItemToDict(item));
            }
        }

        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("current_page", page);
        pagination.put("page_size", pageSize);
        pagination.put("total", total);
        pagination.put("total_pages", (total + pageSize - 1) / pageSize);
        pagination.put("result_filter", resultFilter);
        pagination.put("error_only", "legacy_errors".equals(resultFilter));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run_id", row.getRunId());
        result.put("name", runNameFromRow(row));
        result.put("status", row.getStatus());
        result.put("started_at", DateTimeUtils.formatUtcDatetime(row.getStartedAt()));
        result.put("completed_at", DateTimeUtils.formatUtcDatetime(row.getCompletedAt()));
        result.put("total_items", row.getTotalItems() == null ? 0 : row.getTotalItems());
        result.put("completed_items", row.getCompletedItems() == null ? 0 : row.getCompletedItems());
        result.put("overall_score", row.getOverallScore());
        result.put("retrieval_config", parseObjectOrEmpty(row.getRetrievalConfig()));
        result.put("items", pagedItems);
        result.put("pagination", pagination);
        return result;
    }

    /** 删除运行（对应 {@code delete_run}）。 */
    public void deleteRun(String kbId, String runId) {
        if (!RUN_ID_PATTERN.matcher(runId).matches()) {
            throw new IllegalArgumentException(INVALID_RUN_ID);
        }
        EvaluationRun row = evalRepo.getRun(runId);
        if (row == null || !equalsText(row.getKbId(), kbId)) {
            throw new IllegalArgumentException("Run not found");
        }
        evalRepo.deleteRun(runId);
        log.info("成功删除评估运行: {}", runId);
    }

    // ==================== 任务主体 ====================

    /**
     * 数据集生成任务主体（对应 {@code _generate_dataset_task}）。
     *
     * <p>断点续跑：已存在的题目数作为起始序号，只补生成剩余数量；解析失败/竞态时把残余题目尽力落库。
     */
    public Map<String, Object> generateDatasetTask(TaskService.TaskContext context) {
        context.setProgress(0, "初始化");
        Map<String, Object> payload = context.payload();

        String datasetId = text(payload.get("dataset_id"));
        String kbId = text(payload.get("kb_id"));
        int totalCount = toInt(payload.get("count"), 10);
        int neighborsCount = toInt(payload.get("neighbors_count"), 1);
        int concurrencyCount =
                EvalBenchmarkGeneration.normalizeGenerationConcurrencyCount(payload.get("concurrency_count"));
        String llmModelSpec = payload.get("llm_model_spec") == null
                ? null : String.valueOf(payload.get("llm_model_spec"));
        String generationMode = payload.get("generation_mode") == null
                ? "vector" : String.valueOf(payload.get("generation_mode"));
        int graphExpandTopK = Math.min(Math.max(1, toInt(payload.get("graph_expand_top_k"), 1)), 3);

        Map<String, Object> generationParams = new LinkedHashMap<>();
        generationParams.put("count", totalCount);
        generationParams.put("neighbors_count", neighborsCount);
        generationParams.put("concurrency_count", concurrencyCount);
        generationParams.put("llm_model_spec", llmModelSpec);
        generationParams.put("generation_mode", generationMode);
        generationParams.put("graph_expand_top_k", graphExpandTopK);

        int existingCount = evalRepo.countDatasetItems(datasetId);
        if (existingCount >= totalCount) {
            Map<String, Object> completedMetadata = new LinkedHashMap<>();
            completedMetadata.put("source", "generated");
            completedMetadata.put("status", "completed");
            completedMetadata.put("progress", 100);
            completedMetadata.put("task_id", context.taskId());
            completedMetadata.put("params", generationParams);
            context.setProgress(100, "完成");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dataset_id", datasetId);
            result.put("item_count", existingCount);
            result.put("build_metadata", completedMetadata);
            return result;
        }

        int remainingCount = totalCount - existingCount;
        int[] startIndex = {existingCount};

        Map<String, Object> buildMetadata = new LinkedHashMap<>();
        buildMetadata.put("source", "generated");
        buildMetadata.put("status", "running");
        buildMetadata.put("progress", (int) (99.0 * existingCount / totalCount));
        buildMetadata.put("task_id", context.taskId());
        buildMetadata.put("params", generationParams);

        java.util.function.Consumer<Map<String, Object>> persistBuildMetadata = updates -> {
            buildMetadata.putAll(updates);
            context.runOwnedTransaction(record -> {
                EvaluationDataset updated = evalRepo.updateDatasetInSession(
                        datasetId, Map.of("build_metadata", buildMetadata));
                if (updated == null) {
                    throw new IllegalArgumentException("Dataset not found");
                }
            });
        };

        persistBuildMetadata.accept(Map.of());

        List<Map<String, Object>> buffer = new ArrayList<>();
        java.util.function.BiConsumer<Double, String> reportProgress = (progress, message) -> {
            context.setProgress(progress, message);
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put("progress", Math.max(0, Math.min((int) Math.round(progress), 100)));
            updates.put("message", message != null
                    ? message : String.valueOf(buildMetadata.getOrDefault("message", "")));
            persistBuildMetadata.accept(updates);
        };

        int batchSize = DATASET_PERSIST_BATCH_SIZE;

        Runnable flushItems = () -> {
            if (buffer.isEmpty()) {
                return;
            }
            List<Map<String, Object>> items = buildDatasetItems(datasetId, kbId, buffer, startIndex[0]);
            int nextIndex = startIndex[0] + items.size();
            context.runOwnedTransaction(record -> {
                evalRepo.addDatasetItemsInSession(items);
                EvaluationDataset updated = evalRepo.updateDatasetInSession(
                        datasetId, Map.of("item_count", nextIndex));
                if (updated == null) {
                    throw new IllegalArgumentException("Dataset not found");
                }
            });
            startIndex[0] = nextIndex;
            buffer.clear();
        };

        Runnable flushItemsBestEffort = () -> {
            try {
                flushItems.run();
            } catch (TaskService.TaskContext.Cancelled cancelled) {
                // 取消/租约丢失不能被 best-effort 捕获吞掉（对齐 asyncio.CancelledError 的传播语义）
                throw cancelled;
            } catch (Exception exc) {
                log.error("保存残余题目失败: {}", datasetId, exc);
            }
        };

        try {
            KnowledgeBaseConfig kbConfig = kbManager.getKbConfig(kbId);
            if (!"milvus".equals(kbConfig.kbType())) {
                reportProgress.accept(100.0, "仅支持 commonrag/Milvus 类型知识库生成评估数据集");
                throw new IllegalArgumentException("Unsupported KB type for dataset generation");
            }

            try {
                benchmarkGeneration.iterGeneratedBenchmarkItems(
                        kbId,
                        remainingCount,
                        neighborsCount,
                        llmModelSpec,
                        concurrencyCount,
                        generationMode,
                        graphExpandTopK,
                        existingCount,
                        totalCount,
                        (progress, message) -> reportProgress.accept((double) progress, message),
                        context::raiseIfCancelled,
                        item -> {
                            buffer.add(item);
                            if (buffer.size() >= batchSize) {
                                flushItemsBestEffort.run();
                            }
                        });
            } catch (IllegalArgumentException exc) {
                if ("No chunks found in knowledge base".equals(exc.getMessage())) {
                    reportProgress.accept(100.0, "知识库为空或未解析到chunks");
                }
                throw exc;
            }

            flushItems.run();

            if (startIndex[0] < totalCount) {
                throw new IllegalArgumentException(
                        "仅生成 " + startIndex[0] + "/" + totalCount + " 道有效评估题目");
            }

            context.raiseIfCancelled();
            Map<String, Object> completedMetadata = new LinkedHashMap<>(buildMetadata);
            completedMetadata.put("status", "completed");
            completedMetadata.put("progress", 100);
            completedMetadata.put("message", "完成");
            context.setProgress(100, "完成");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dataset_id", datasetId);
            result.put("item_count", startIndex[0]);
            result.put("build_metadata", completedMetadata);
            return result;
        } catch (Exception exc) {
            if (exc instanceof TaskService.TaskContext.Cancelled
                    && "lease_lost".equals(context.cancellationReason())) {
                throw exc;
            }
            flushItemsBestEffort.run();
            throw exc;
        }
    }

    /**
     * RAG 评估任务主体（对应 {@code _run_evaluation_task}）。
     *
     * <p>逐题评估并落库，每 5 题刷新一次中间指标；异常/取消时收敛为 run 的 failed 与错误文案。
     */
    public Map<String, Object> runEvaluationTask(TaskService.TaskContext context) {
        try {
            Map<String, Object> payload = context.payload();

            String runId = text(payload.get("run_id"));
            String kbId = text(payload.get("kb_id"));
            String datasetId = text(payload.get("dataset_id"));
            Map<String, Object> retrievalConfig = asMap(payload.get("retrieval_config"));

            context.setProgress(5, "加载评估数据集");
            EvaluationDataset datasetRow = evalRepo.getDataset(datasetId);
            if (datasetRow == null || !equalsText(datasetRow.getKbId(), kbId)) {
                throw new IllegalArgumentException("Dataset not found");
            }
            List<EvaluationDatasetItem> datasetItems = evalRepo.listAllDatasetItems(datasetId);
            if (datasetItems.isEmpty()) {
                throw new IllegalArgumentException("Dataset has no items");
            }

            ModelSelectors.ChatAdapter judgeLlm = null;
            if (Boolean.TRUE.equals(datasetRow.getHasGoldAnswers())) {
                Object judgeModelSpec = retrievalConfig.get("judge_llm") != null
                        ? retrievalConfig.get("judge_llm") : retrievalConfig.get("answer_llm");
                if (judgeModelSpec != null && !String.valueOf(judgeModelSpec).isEmpty()) {
                    try {
                        log.debug("Initializing Judge LLM: {}", judgeModelSpec);
                        judgeLlm = modelSelectors.selectModel(String.valueOf(judgeModelSpec));
                    } catch (Exception exc) {
                        log.error("Failed to load judge LLM: {}", exc.getMessage());
                    }
                }
            }

            List<Map<String, Object>> allRetrievalMetrics = new ArrayList<>();
            List<Map<String, Object>> allAnswerMetrics = new ArrayList<>();
            int totalItems = datasetItems.size();

            for (int index = 0; index < totalItems; index++) {
                context.raiseIfCancelled();
                double progress = 10 + ((double) index / totalItems) * 80;
                context.setProgress(progress, "评估 " + (index + 1) + "/" + totalItems);

                EvaluationDatasetItem item = datasetItems.get(index);
                Map<String, Object> questionData = new LinkedHashMap<>();
                questionData.put("query", item.getQueryText());
                questionData.put("gold_chunk_ids", parseArrayOrEmpty(item.getGoldChunkIds()));
                questionData.put("gold_answer", item.getGoldAnswer());

                Map<String, Object> questionResult = EvalEvaluator.evaluateQuestion(
                        kbId,
                        questionData,
                        retrievalConfig,
                        Boolean.TRUE.equals(datasetRow.getHasGoldChunks()),
                        Boolean.TRUE.equals(datasetRow.getHasGoldAnswers()),
                        judgeLlm,
                        modelSelectors,
                        kbManager);

                if (Boolean.TRUE.equals(datasetRow.getHasGoldChunks())
                        && !parseArrayOrEmpty(item.getGoldChunkIds()).isEmpty()) {
                    allRetrievalMetrics.add(asMap(questionResult.get("retrieval_scores")));
                }
                if (Boolean.TRUE.equals(datasetRow.getHasGoldAnswers())
                        && item.getGoldAnswer() != null
                        && !item.getGoldAnswer().isEmpty()
                        && judgeLlm != null) {
                    allAnswerMetrics.add(asMap(questionResult.get("answer_scores")));
                }

                final int itemIndex = index;
                Map<String, Object> detail = asMap(questionResult.get("detail"));
                context.runOwnedTransaction(record -> {
                    Map<String, Object> data = new LinkedHashMap<>(detail);
                    data.put("dataset_item_id", item.getItemId());
                    evalRepo.upsertRunItemInSession(runId, itemIndex, data);
                });

                if ((index + 1) % 5 == 0 || (index + 1) == totalItems) {
                    EvalEvaluator.AggregateResult aggregate =
                            EvalEvaluator.aggregateMetrics(allRetrievalMetrics, allAnswerMetrics, false);
                    Map<String, Object> snapshot = new LinkedHashMap<>();
                    snapshot.put("current_metrics", aggregate.metrics());
                    snapshot.put("completed_items", index + 1);
                    snapshot.put("total_items", totalItems);
                    context.setResult(snapshot);
                    final int completed = index + 1;
                    context.runOwnedTransaction(record -> {
                        EvaluationRun updated = evalRepo.updateRunInSession(
                                runId, Map.of("completed_items", completed));
                        if (updated == null) {
                            throw new IllegalArgumentException("EvaluationRun not found");
                        }
                    });
                }
            }

            context.setProgress(95, "计算最终指标");
            EvalEvaluator.AggregateResult aggregate =
                    EvalEvaluator.aggregateMetrics(allRetrievalMetrics, allAnswerMetrics, true);
            context.raiseIfCancelled();
            context.setProgress(100, "完成");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("run_id", runId);
            result.put("completed_items", totalItems);
            result.put("metrics", aggregate.metrics());
            result.put("overall_score", aggregate.overallScore());
            return result;
        } catch (Exception exc) {
            if (exc instanceof TaskService.TaskContext.Cancelled
                    && "lease_lost".equals(context.cancellationReason())) {
                throw exc;
            }
            String error = exc.getMessage();
            if (exc instanceof TaskService.TaskContext.Cancelled) {
                if (context.isCancelRequested()) {
                    error = "任务已取消";
                } else if ("timeout".equals(context.cancellationReason())) {
                    error = "任务执行超时";
                } else {
                    error = "服务停止，任务执行中断";
                }
            }
            log.error("Task failed: {}", error);
            context.setMessage("Error: " + error);
            throw exc;
        }
    }

    // ==================== 内部：元数据同步 ====================

    /** 同步数据集的生成状态（对应 {@code _sync_dataset_build_metadata}）。 */
    private void syncDatasetBuildMetadata(EvaluationDataset row) {
        Map<String, Object> metadata = new LinkedHashMap<>(parseObjectOrEmpty(row.getBuildMetadata()));
        if (!"generated".equals(String.valueOf(metadata.get("source")))
                || !Set.of("pending", "running").contains(String.valueOf(metadata.get("status")))) {
            return;
        }

        Object rawTaskId = metadata.get("task_id");
        String taskId = rawTaskId == null ? null : String.valueOf(rawTaskId);
        TaskRecord task = taskId == null || taskId.isEmpty() ? null : taskRepo.getById(taskId);
        if (task == null && (taskId == null || taskId.isEmpty())) {
            task = taskRepo.findLatestByPayload(
                    "dataset_generation",
                    Map.of("dataset_id", row.getDatasetId()),
                    Set.of("pending", "running"));
            if (task != null) {
                taskId = task.getId();
                metadata.put("task_id", taskId);
            }
        }
        if (task == null && (taskId == null || taskId.isEmpty())) {
            return;
        }
        if (task == null) {
            metadata.remove("progress");
            metadata.put("status", "failed");
            metadata.put("message", "生成任务不存在");
        } else if ("success".equals(task.getStatus())) {
            metadata.put("status", "completed");
            metadata.put("progress", 100);
            metadata.put("message", task.getMessage() == null || task.getMessage().isEmpty()
                    ? "完成" : task.getMessage());
        } else if ("failed".equals(task.getStatus()) || "cancelled".equals(task.getStatus())) {
            metadata.remove("progress");
            metadata.put("status", "failed");
            metadata.put("message", firstNonEmpty(task.getError(), task.getMessage(), "生成任务失败"));
        } else {
            metadata.put("status", task.getStatus());
            metadata.put("progress", task.getProgress());
            metadata.put("message", task.getMessage());
        }

        if (!metadata.equals(parseObjectOrEmpty(row.getBuildMetadata()))) {
            evalRepo.updateDataset(row.getDatasetId(), Map.of("build_metadata", metadata));
            row.setBuildMetadata(JSON.toJSONString(metadata));
        }
    }

    /**
     * 同步运行状态（对应 {@code _sync_evaluation_run}）。
     *
     * <p>{@code task} 为 {@code null} 表示"按 run_id 自行检索最新任务"；要传入"确实没有任务"的语义时，
     * 调用方走本方法的重载即可（对应参考实现的 {@code _TASK_NOT_LOADED} 哨兵）。
     */
    private TaskRecord syncEvaluationRun(EvaluationRun row) {
        if (!"running".equals(row.getStatus())) {
            return null;
        }
        return syncEvaluationRun(
                row,
                taskRepo.findLatestByPayload("rag_evaluation", Map.of("run_id", row.getRunId()), null));
    }

    /** 同步运行状态（任务已由调用方批量查出）。 */
    private TaskRecord syncEvaluationRun(EvaluationRun row, TaskRecord task) {
        if (!"running".equals(row.getStatus())) {
            return null;
        }

        String error = null;
        LocalDateTime completedAt = null;
        if (task != null && ("failed".equals(task.getStatus()) || "cancelled".equals(task.getStatus()))) {
            error = firstNonEmpty(task.getError(), task.getMessage(), "评估任务失败");
            completedAt = task.getCompletedAt() != null ? task.getCompletedAt() : DateTimeUtils.utcNowNaive();
        } else if (task == null
                && row.getStartedAt() != null
                && row.getStartedAt().isBefore(DateTimeUtils.utcNowNaive().minusMinutes(1))) {
            error = "评估任务提交中断";
            completedAt = DateTimeUtils.utcNowNaive();
        }

        if (error != null) {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("error", error);
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put("status", "failed");
            updates.put("metrics", metrics);
            updates.put("completed_at", completedAt);
            evalRepo.updateRun(row.getRunId(), updates);
            row.setStatus("failed");
            row.setMetrics(JSON.toJSONString(metrics));
            row.setCompletedAt(completedAt);
        }
        return task;
    }

    // ==================== 内部：数据集构造 ====================

    /** 构造数据集题目记录（对应 {@code _build_dataset_items}）。 */
    private List<Map<String, Object>> buildDatasetItems(
            String datasetId, String kbId, List<Map<String, Object>> questions, int startIndex) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int index = 0; index < questions.size(); index++) {
            Map<String, Object> question = questions.get(index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("item_id", "dataset_item_" + randomHex(12));
            item.put("dataset_id", datasetId);
            item.put("kb_id", kbId);
            item.put("item_index", startIndex + index);
            item.put("query_text", question.get("query"));
            Object goldChunkIds = question.get("gold_chunk_ids");
            item.put("gold_chunk_ids", goldChunkIds instanceof List<?> list ? list : new ArrayList<>());
            item.put("gold_answer", question.get("gold_answer"));
            items.add(item);
        }
        return items;
    }

    /** 构造 JSONL 文本（对应 {@code _build_jsonl_content}）。 */
    private String buildJsonlContent(List<EvaluationDatasetItem> items) {
        List<String> lines = new ArrayList<>();
        for (EvaluationDatasetItem item : items) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", item.getQueryText());
            List<String> goldChunkIds = parseStringList(item.getGoldChunkIds());
            if (!goldChunkIds.isEmpty()) {
                payload.put("gold_chunk_ids", goldChunkIds);
            }
            if (item.getGoldAnswer() != null && !item.getGoldAnswer().isEmpty()) {
                payload.put("gold_answer", item.getGoldAnswer());
            }
            String dumped = EvalBenchmarkGeneration.dumpBenchmarkItem(payload);
            lines.add(dumped.endsWith("\n") ? dumped.substring(0, dumped.length() - 1) : dumped);
        }
        return String.join("\n", lines) + (lines.isEmpty() ? "" : "\n");
    }

    /** 规范化 JSONL 文件名（对应 {@code _safe_jsonl_filename}）。 */
    private String safeJsonlFilename(String name, String fallback) {
        String filename = name == null ? "" : name.strip();
        if (filename.isEmpty()) {
            filename = fallback;
        }
        filename = filename.replaceAll("[\\\\/:*?\"<>|]+", "_").strip();
        if (filename.isEmpty() || ".".equals(filename) || "..".equals(filename)) {
            filename = fallback;
        }
        return filename.endsWith(".jsonl") ? filename : filename + ".jsonl";
    }

    /** 解析上传的 JSONL（对应 {@code _parse_jsonl_questions}）。 */
    private ParsedQuestions parseJsonlQuestions(byte[] fileContent) {
        String content = new String(fileContent == null ? new byte[0] : fileContent, java.nio.charset.StandardCharsets.UTF_8);
        List<Map<String, Object>> questions = new ArrayList<>();
        boolean hasGoldChunks = false;
        boolean hasGoldAnswers = false;

        String[] lines = content.strip().split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            int lineNum = index + 1;
            if (line.strip().isEmpty()) {
                continue;
            }
            Map<String, Object> item;
            try {
                item = JSON.parseObject(line);
            } catch (Exception exc) {
                throw new IllegalArgumentException("第" + lineNum + "行JSON格式错误: " + exc.getMessage());
            }
            if (!item.containsKey("query")) {
                throw new IllegalArgumentException("第" + lineNum + "行缺少必需的'query'字段");
            }
            if (isTruthy(item.get("gold_chunk_ids"))) {
                hasGoldChunks = true;
            }
            if (isTruthy(item.get("gold_answer"))) {
                hasGoldAnswers = true;
            }
            questions.add(item);
        }

        if (questions.isEmpty()) {
            throw new IllegalArgumentException("文件中没有有效的问题数据");
        }
        return new ParsedQuestions(questions, hasGoldChunks, hasGoldAnswers);
    }

    /** 生成的运行名（对应 {@code build_evaluation_run_name}）。 */
    public static String buildEvaluationRunName(LocalDateTime startedAt, String hashValue) {
        LocalDateTime effective = startedAt == null ? DateTimeUtils.utcNowNaive() : startedAt;
        String datePart = effective.format(DATE_PART_FORMAT);
        String source = (hashValue == null || hashValue.isEmpty()) ? randomHex(32) : hashValue;
        String hashPart = source.replaceAll("[^a-fA-F0-9]", "").toLowerCase();
        hashPart = hashPart.length() > 6 ? hashPart.substring(0, 6) : hashPart;
        if (hashPart.length() < 6) {
            hashPart = (hashPart + randomHex(32));
            hashPart = hashPart.substring(0, 6);
        }
        return "eval-" + datePart + "-" + hashPart;
    }

    // ==================== 内部：小工具 ====================

    /** 数据集已完成的并发信号（对应参考实现的 {@code _DatasetAlreadyCompleted}）。 */
    private static final class DatasetAlreadyCompleted extends RuntimeException {
    }

    /** JSONL 解析产物（对应参考实现的三元组）。 */
    private record ParsedQuestions(
            List<Map<String, Object>> questions, boolean hasGoldChunks, boolean hasGoldAnswers) {}

    private static String randomHex(int length) {
        String hex = UUID.randomUUID().toString().replace("-", "");
        return hex.length() >= length ? hex.substring(0, length) : hex;
    }

    private static String stripRunPrefix(String runId) {
        if (runId == null) {
            return null;
        }
        return runId.startsWith("run_") ? runId.substring("run_".length()) : runId;
    }

    private static Map<String, Object> messageResult(String datasetId, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataset_id", datasetId);
        result.put("message", message);
        return result;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean equalsText(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    private static boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value instanceof CharSequence textValue) {
            return textValue.length() > 0;
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return new LinkedHashMap<>();
    }

    /** JSON 对象列 → Map（空值/非法值返回空 Map）。 */
    private static Map<String, Object> parseObjectOrEmpty(String json) {
        if (json == null || json.isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            JSONObject parsed = JSON.parseObject(json);
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (Exception exc) {
            return new LinkedHashMap<>();
        }
    }

    /** JSON 数组列 → 列表（空值/非法值返回空列表）。 */
    private static List<Object> parseArrayOrEmpty(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<Object> values = JSON.parseArray(json, Object.class);
            return values == null ? new ArrayList<>() : values;
        } catch (Exception exc) {
            return new ArrayList<>();
        }
    }

    private static List<String> parseStringList(String json) {
        List<String> values = new ArrayList<>();
        for (Object item : parseArrayOrEmpty(json)) {
            if (item != null) {
                values.add(String.valueOf(item));
            }
        }
        return values;
    }

    private static int toInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return (int) number.doubleValue();
        }
        try {
            return (int) Double.parseDouble(String.valueOf(value).strip());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int envInt(String name, int fallback) {
        String raw = System.getenv(name);
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
