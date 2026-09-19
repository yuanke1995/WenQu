package com.wisesoft.wenqu.knowledge.eval;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.EvaluationDataset;
import com.wisesoft.wenqu.models.EvaluationRun;
import com.wisesoft.wenqu.models.TaskRecord;
import com.wisesoft.wenqu.repositories.EvaluationRepository;
import com.wisesoft.wenqu.service.TaskService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 评估相关的 Durable Task 处理器，对齐参考实现 {@code knowledge/eval/service.py} 的模块级函数：
 * {@code run_dataset_generation_task} / {@code finish_dataset_generation_task} /
 * {@code fail_dataset_generation_task} / {@code run_rag_evaluation_task} /
 * {@code finish_rag_evaluation_task} / {@code fail_rag_evaluation_task}。
 *
 * <p>这些函数由 {@code service/TaskRegistry} 按类名 + 方法名反射加载（见该类的 TASK_DEFINITIONS），
 * 因此类名与方法名必须与注册表登记值保持一致：类为
 * {@code com.wisesoft.wenqu.knowledge.eval.EvalTaskService}，方法名为 {@code runDatasetGenerationTask} 等。
 *
 * <p>成功/失败钩子在 Task 终态事务内执行（见 {@code TaskRepository.finishOwned} 的 before-change 钩子），
 * 因此其中的领域写操作与任务终态同批提交——与参考实现「在 Task 成功事务内提交领域完成事实」一致。
 */
@Service
public class EvalTaskService {

    private final EvalService evalService;
    private final EvaluationRepository evalRepo;

    public EvalTaskService(EvalService evalService, EvaluationRepository evalRepo) {
        this.evalService = evalService;
        this.evalRepo = evalRepo;
    }

    // ==================== 数据集生成任务 ====================

    /** 从持久 payload 重建数据集生成 Handler（对应 {@code run_dataset_generation_task}）。 */
    public Object runDatasetGenerationTask(TaskService.TaskContext context) {
        return evalService.generateDatasetTask(context);
    }

    /** 在 Task 成功事务内提交数据集完成事实（对应 {@code finish_dataset_generation_task}）。 */
    public void finishDatasetGenerationTask(TaskRecord taskRecord, Object result) {
        Map<String, Object> payload = parseObject(taskRecord.getPayload());
        Map<String, Object> resultMap = result instanceof Map<?, ?> ? castMap(result) : new LinkedHashMap<>();
        if (!(result instanceof Map<?, ?>)
                || !equalsText(resultMap.get("dataset_id"), payload.get("dataset_id"))) {
            throw new IllegalArgumentException("Dataset generation result does not match Task payload");
        }

        String datasetId = String.valueOf(resultMap.get("dataset_id"));
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("item_count", toInt(resultMap.get("item_count"), 0));
        updates.put("build_metadata", resultMap.get("build_metadata"));
        EvaluationDataset updated = evalRepo.updateDatasetInSession(datasetId, updates);
        if (updated == null) {
            throw new IllegalArgumentException("Dataset not found during Task completion");
        }
    }

    /** 在 Task 失败事务内收敛数据集构建状态（对应 {@code fail_dataset_generation_task}）。 */
    public void failDatasetGenerationTask(TaskRecord taskRecord, String error) {
        Map<String, Object> payload = parseObject(taskRecord.getPayload());
        Object rawDatasetId = payload.get("dataset_id");
        if (rawDatasetId == null) {
            return;
        }
        String datasetId = String.valueOf(rawDatasetId);

        EvaluationDataset dataset = evalRepo.getDatasetForUpdate(datasetId);
        if (dataset == null) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(parseObject(dataset.getBuildMetadata()));
        if ("completed".equals(String.valueOf(metadata.get("status")))) {
            return;
        }
        metadata.put("status", "failed");
        metadata.put("task_id", taskRecord.getId());
        metadata.put("progress", 100);
        metadata.put("error_message", error);
        metadata.put("message", error);
        evalRepo.updateDatasetInSession(datasetId, Map.of("build_metadata", metadata));
    }

    // ==================== RAG 评估任务 ====================

    /** 从持久 payload 重建 RAG 评估 Handler（对应 {@code run_rag_evaluation_task}）。 */
    public Object runRagEvaluationTask(TaskService.TaskContext context) {
        return evalService.runEvaluationTask(context);
    }

    /** 在 Task 成功事务内提交评估 Run 完成事实（对应 {@code finish_rag_evaluation_task}）。 */
    public void finishRagEvaluationTask(TaskRecord taskRecord, Object result) {
        Map<String, Object> payload = parseObject(taskRecord.getPayload());
        Map<String, Object> resultMap = result instanceof Map<?, ?> ? castMap(result) : new LinkedHashMap<>();
        if (!(result instanceof Map<?, ?>)
                || !equalsText(resultMap.get("run_id"), payload.get("run_id"))) {
            throw new IllegalArgumentException("Evaluation result does not match Task payload");
        }

        String runId = String.valueOf(resultMap.get("run_id"));
        EvaluationRun run = evalRepo.getRunForUpdate(runId);
        if (run == null) {
            throw new IllegalArgumentException("EvaluationRun not found during Task completion");
        }
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("status", "completed");
        updates.put("completed_items", toInt(resultMap.get("completed_items"), 0));
        updates.put("metrics", resultMap.get("metrics"));
        updates.put("overall_score", resultMap.get("overall_score"));
        updates.put("completed_at", DateTimeUtils.utcNowNaive());
        evalRepo.updateRunInSession(runId, updates);
    }

    /** 在 Task 失败事务内收敛评估 Run（对应 {@code fail_rag_evaluation_task}）。 */
    public void failRagEvaluationTask(TaskRecord taskRecord, String error) {
        Map<String, Object> payload = parseObject(taskRecord.getPayload());
        Object rawRunId = payload.get("run_id");
        if (rawRunId == null) {
            return;
        }
        String runId = String.valueOf(rawRunId);

        EvaluationRun run = evalRepo.getRunForUpdate(runId);
        if (run == null || "completed".equals(run.getStatus())) {
            return;
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("error", error);
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("status", "failed");
        updates.put("metrics", metrics);
        evalRepo.updateRunInSession(runId, updates);
    }

    // ==================== 工具 ====================

    private static Map<String, Object> parseObject(String json) {
        if (json == null || json.isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = JSON.parseObject(json);
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (Exception exc) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static boolean equalsText(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        return String.valueOf(left).equals(String.valueOf(right));
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
}
