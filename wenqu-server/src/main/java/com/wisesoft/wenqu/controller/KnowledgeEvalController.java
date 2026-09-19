package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.UrlQuote;
import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.knowledge.eval.EvalBenchmarkGeneration;
import com.wisesoft.wenqu.knowledge.eval.EvalService;
import com.wisesoft.wenqu.models.EvaluationDataset;
import com.wisesoft.wenqu.permissions.KnowledgePermissions;
import com.wisesoft.wenqu.permissions.ResourcePermission;
import com.wisesoft.wenqu.repositories.EvaluationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库评估路由，逐端点对齐参考实现 {@code server/routers/knowledge_eval_router.py}
 * （路由器前缀 {@code /evaluation}，参考实现聚合后对外为 {@code /api/evaluation}）。
 *
 * <p>11 个端点：
 * <pre>
 * POST   /api/evaluation/databases/{kb_id}/datasets/upload          上传 JSONL 数据集（multipart）
 * GET    /api/evaluation/databases/{kb_id}/datasets                 数据集列表
 * GET    /api/evaluation/databases/{kb_id}/datasets/{dataset_id}    数据集详情（分页）
 * GET    /api/evaluation/datasets/{dataset_id}/download             导出 JSONL
 * DELETE /api/evaluation/datasets/{dataset_id}                      删除数据集
 * POST   /api/evaluation/databases/{kb_id}/datasets/generate        自动生成数据集
 * POST   /api/evaluation/databases/{kb_id}/datasets/{dataset_id}/resume  恢复生成
 * POST   /api/evaluation/databases/{kb_id}/runs                     发起 RAG 评估
 * GET    /api/evaluation/databases/{kb_id}/runs                     评估运行历史
 * GET    /api/evaluation/databases/{kb_id}/runs/{run_id}            评估运行结果（分页 + 筛选）
 * DELETE /api/evaluation/databases/{kb_id}/runs/{run_id}            删除评估运行
 * </pre>
 *
 * <p>成功响应体统一为 {@code {"message": "success", "data": ...}}（与参考实现一致，非本产品既有
 * {@code ResultJson} 契约）；失败响应体由 {@link ApiHttpException} → {@code {"detail": ...}}。
 *
 * <h3>平台差异（必要替换，不影响状态码与文案）</h3>
 * <ul>
 *   <li>{@code Depends(get_admin_user)} → {@link AuthGuards#requireAdmin()}（方法首行显式调用）；
 *       {@code require_knowledge_base_read/manage} → {@link KnowledgePermissions} 同名方法（返回 uid）。</li>
 *   <li>{@code require_evaluation_dataset_read/manage} 这两个依赖先加载数据集（不存在 → 404
 *       {@code 评估数据集不存在}），再按 {@code dataset.kb_id} 校验知识库权限 →
 *       {@link #requireDatasetPermission(String, ResourcePermission)} 一一对应。</li>
 *   <li>{@code UploadFile} → {@link MultipartFile}；{@code Form(...)} → {@code @RequestParam} +
 *       {@code defaultValue}（FastAPI 的 {@code Form("")} 默认空串等价于此）。</li>
 *   <li>{@code Response(content, media_type="application/x-ndjson")} → {@link ResponseEntity}
 *       携带同一 media type；{@code urllib.parse.quote} → {@link UrlQuote#quote(String, String)}。</li>
 *   <li>pydantic 请求模型（{@code GenerateDatasetRequest} / {@code RunEvaluationRequest}）的字段约束
 *       → 方法内显式校验并抛 422（参考实现的 422 响应体是结构化列表，此处为文本 detail，
 *       与本工程既有路由层处理方式一致）。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/evaluation")
@RequiredArgsConstructor
@Tag(name = "evaluation", description = "知识库评估（数据集构建与 RAG 评估运行）")
public class KnowledgeEvalController {

    /** {@code GenerateDatasetRequest.name} 的 min_length / max_length。 */
    private static final int NAME_MIN_LENGTH = 1;

    private static final int NAME_MAX_LENGTH = 100;

    /** {@code GenerateDatasetRequest.count} 的 ge / le。 */
    private static final int COUNT_MIN = 1;

    private static final int COUNT_MAX = 100;

    /** {@code GenerateDatasetRequest.neighbors_count} 的 ge / le。 */
    private static final int NEIGHBORS_MIN = 0;

    private static final int NEIGHBORS_MAX = 10;

    /** {@code GenerateDatasetRequest.graph_expand_top_k} 的 ge / le。 */
    private static final int GRAPH_EXPAND_MIN = 1;

    private static final int GRAPH_EXPAND_MAX = 3;

    private final EvalService evalService;

    private final EvaluationRepository evaluationRepository;

    // ==================== 数据集 ====================

    /** 上传评估数据集（仅支持 .jsonl，multipart 表单：file / name / description）。 */
    @PostMapping("/databases/{kb_id}/datasets/upload")
    @Operation(summary = "上传评估数据集", description = "响应 {\"message\":\"success\",\"data\":{...}}")
    public Map<String, Object> uploadEvaluationDataset(
            @PathVariable("kb_id") String kbId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("name") String name,
            @RequestParam(value = "description", defaultValue = "") String description) {
        try {
            String uid = KnowledgePermissions.requireKnowledgeBaseManage(kbId);
            String filename = file.getOriginalFilename();
            if (filename == null || !filename.endsWith(".jsonl")) {
                throw new ApiHttpException(400, "仅支持JSONL格式文件");
            }
            Map<String, Object> result =
                    evalService.uploadDataset(
                            kbId, file.getBytes(), filename, name, description, uid);
            return success(result);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("上传评估数据集失败: {}", exc.getMessage(), exc);
            throw new ApiHttpException(500, "上传评估数据集失败: " + exc.getMessage());
        }
    }

    /** 知识库的评估数据集列表。 */
    @GetMapping("/databases/{kb_id}/datasets")
    @Operation(summary = "评估数据集列表")
    public Map<String, Object> listEvaluationDatasets(@PathVariable("kb_id") String kbId) {
        try {
            KnowledgePermissions.requireKnowledgeBaseRead(kbId);
            List<Map<String, Object>> datasets = evalService.listDatasets(kbId);
            return success(datasets);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("获取评估数据集列表失败: {}", exc.getMessage(), exc);
            throw new ApiHttpException(500, "获取评估数据集列表失败: " + exc.getMessage());
        }
    }

    /** 评估数据集详情（分页）。 */
    @GetMapping("/databases/{kb_id}/datasets/{dataset_id}")
    @Operation(summary = "评估数据集详情", description = "page >= 1，1 <= page_size <= 100")
    public Map<String, Object> getEvaluationDataset(
            @PathVariable("kb_id") String kbId,
            @PathVariable("dataset_id") String datasetId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        try {
            KnowledgePermissions.requireKnowledgeBaseRead(kbId);
            if (page < 1) {
                throw new ApiHttpException(400, "页码必须大于0");
            }
            if (pageSize < 1 || pageSize > 100) {
                throw new ApiHttpException(400, "每页大小必须在1-100之间");
            }

            Map<String, Object> dataset =
                    evalService.getDatasetDetail(kbId, datasetId, page, pageSize);
            return success(dataset);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (IllegalArgumentException exc) {
            throw asValueError(exc);
        } catch (Exception exc) {
            log.error("获取评估数据集详情失败: {}", exc.getMessage(), exc);
            throw new ApiHttpException(500, "获取评估数据集详情失败: " + exc.getMessage());
        }
    }

    /** 导出评估数据集 JSONL（依赖先校验「数据集所属知识库的读权限」）。 */
    @GetMapping("/datasets/{dataset_id}/download")
    @Operation(summary = "导出评估数据集 JSONL")
    public ResponseEntity<byte[]> downloadEvaluationDataset(
            @PathVariable("dataset_id") String datasetId) {
        try {
            requireDatasetPermission(datasetId, ResourcePermission.READ);
            Map<String, String> exportInfo = evalService.exportDatasetJsonl(datasetId);
            String filename = exportInfo.get("filename");
            byte[] content = exportInfo.get("content").getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/x-ndjson"))
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + UrlQuote.quote(filename, "/"))
                    .body(content);
        } catch (IllegalArgumentException exc) {
            throw asValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("导出评估数据集失败: {}", exc.getMessage(), exc);
            throw new ApiHttpException(500, "导出评估数据集失败: " + exc.getMessage());
        }
    }

    /** 删除评估数据集（依赖先校验「数据集所属知识库的管理权限」）。 */
    @DeleteMapping("/datasets/{dataset_id}")
    @Operation(summary = "删除评估数据集")
    public Map<String, Object> deleteEvaluationDataset(@PathVariable("dataset_id") String datasetId) {
        try {
            requireDatasetPermission(datasetId, ResourcePermission.MANAGE);
            evalService.deleteDataset(datasetId);
            return success(null);
        } catch (IllegalArgumentException exc) {
            throw asValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("删除评估数据集失败: {}", exc.getMessage(), exc);
            throw new ApiHttpException(500, "删除评估数据集失败: " + exc.getMessage());
        }
    }

    /** 自动生成评估数据集（body 字段同参考实现 GenerateDatasetRequest）。 */
    @PostMapping("/databases/{kb_id}/datasets/generate")
    @Operation(summary = "自动生成评估数据集")
    public Map<String, Object> generateEvaluationDataset(
            @PathVariable("kb_id") String kbId, @RequestBody Map<String, Object> body) {
        try {
            String uid = KnowledgePermissions.requireKnowledgeBaseManage(kbId);

            Map<String, Object> request = body == null ? new LinkedHashMap<>() : body;
            String name = validateName(request.get("name"), "自动生成评估数据集");
            String description = request.get("description") == null ? "" : str(request.get("description"));
            int count = validateInt(request.get("count"), 10, COUNT_MIN, COUNT_MAX, "count");
            int neighborsCount =
                    validateInt(
                            request.get("neighbors_count"),
                            1,
                            NEIGHBORS_MIN,
                            NEIGHBORS_MAX,
                            "neighbors_count");
            int concurrencyCount =
                    validateInt(
                            request.get("concurrency_count"),
                            EvalBenchmarkGeneration.DEFAULT_BENCHMARK_GENERATION_CONCURRENCY,
                            1,
                            EvalBenchmarkGeneration.MAX_BENCHMARK_GENERATION_CONCURRENCY,
                            "concurrency_count");
            String llmModelSpec = str(request.get("llm_model_spec"));
            if (llmModelSpec == null || llmModelSpec.isEmpty()) {
                throw new ApiHttpException(422, "llm_model_spec 不能为空");
            }
            String generationMode =
                    request.get("generation_mode") == null
                            ? "vector"
                            : str(request.get("generation_mode"));
            if (!"vector".equals(generationMode) && !"graph_enhanced".equals(generationMode)) {
                throw new ApiHttpException(422, "generation_mode 取值必须是 vector 或 graph_enhanced 之一");
            }
            int graphExpandTopK =
                    validateInt(
                            request.get("graph_expand_top_k"),
                            EvalBenchmarkGeneration.DEFAULT_GRAPH_EXPAND_TOP_K,
                            GRAPH_EXPAND_MIN,
                            GRAPH_EXPAND_MAX,
                            "graph_expand_top_k");

            Map<String, Object> result =
                    evalService.generateDataset(
                            kbId,
                            name,
                            description,
                            count,
                            neighborsCount,
                            concurrencyCount,
                            llmModelSpec,
                            generationMode,
                            graphExpandTopK,
                            uid);
            return success(result);
        } catch (IllegalArgumentException exc) {
            throw new ApiHttpException(400, exc.getMessage());
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("生成评估数据集失败: {}", exc.getMessage(), exc);
            throw new ApiHttpException(500, "生成评估数据集失败: " + exc.getMessage());
        }
    }

    /** 恢复自动生成评估数据集。 */
    @PostMapping("/databases/{kb_id}/datasets/{dataset_id}/resume")
    @Operation(summary = "恢复评估数据集生成")
    public Map<String, Object> resumeEvaluationDataset(
            @PathVariable("kb_id") String kbId, @PathVariable("dataset_id") String datasetId) {
        try {
            String uid = KnowledgePermissions.requireKnowledgeBaseManage(kbId);
            Map<String, Object> result =
                    evalService.resumeDatasetGeneration(kbId, datasetId, uid);
            return success(result);
        } catch (IllegalArgumentException exc) {
            throw new ApiHttpException(400, exc.getMessage());
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("恢复评估数据集生成失败: {}", exc.getMessage(), exc);
            throw new ApiHttpException(500, "恢复评估数据集生成失败: " + exc.getMessage());
        }
    }

    // ==================== 评估运行 ====================

    /** 发起 RAG 评估（body: dataset_id / name? / model_config?）。 */
    @PostMapping("/databases/{kb_id}/runs")
    @Operation(summary = "发起 RAG 评估", description = "响应 {\"message\":\"success\",\"data\":{\"run_id\":...}}")
    public Map<String, Object> runEvaluation(
            @PathVariable("kb_id") String kbId, @RequestBody Map<String, Object> body) {
        try {
            String uid = KnowledgePermissions.requireKnowledgeBaseManage(kbId);

            Map<String, Object> request = body == null ? new LinkedHashMap<>() : body;
            Object rawDatasetId = request.get("dataset_id");
            String datasetId = str(rawDatasetId);
            if (datasetId == null || datasetId.isEmpty()) {
                throw new ApiHttpException(422, "dataset_id 不能为空");
            }
            String name = null;
            if (request.containsKey("name") && request.get("name") != null) {
                name = validateName(request.get("name"), null);
            }
            Map<String, Object> modelConfig = new LinkedHashMap<>();
            Object rawModelConfig = request.get("model_config");
            if (rawModelConfig instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    modelConfig.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            } else if (rawModelConfig != null) {
                throw new ApiHttpException(422, "model_config 必须是对象");
            }

            String runId = evalService.runEvaluation(kbId, datasetId, name, modelConfig, uid);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("run_id", runId);
            return success(data);
        } catch (IllegalArgumentException exc) {
            throw asValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("启动评估失败: {}", exc.getMessage(), exc);
            throw new ApiHttpException(500, "启动评估失败: " + exc.getMessage());
        }
    }

    /** 知识库评估运行历史。 */
    @GetMapping("/databases/{kb_id}/runs")
    @Operation(summary = "评估运行历史")
    public Map<String, Object> listEvaluationRuns(@PathVariable("kb_id") String kbId) {
        try {
            KnowledgePermissions.requireKnowledgeBaseRead(kbId);
            List<Map<String, Object>> runs = evalService.listRuns(kbId);
            return success(runs);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("获取评估运行历史失败: {}", exc.getMessage(), exc);
            throw new ApiHttpException(500, "获取评估运行历史失败: " + exc.getMessage());
        }
    }

    /** 评估运行结果（分页 + result_filter / error_only 二选一筛选）。 */
    @GetMapping("/databases/{kb_id}/runs/{run_id}")
    @Operation(summary = "评估运行结果", description = "1 <= page_size <= 100；result_filter 与 error_only 互斥")
    public Map<String, Object> getEvaluationRunResults(
            @PathVariable("kb_id") String kbId,
            @PathVariable("run_id") String runId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(value = "result_filter", required = false) String resultFilter,
            @RequestParam(value = "error_only", defaultValue = "false") boolean errorOnly) {
        try {
            KnowledgePermissions.requireKnowledgeBaseRead(kbId);
            if (page < 1) {
                throw new ApiHttpException(400, "页码必须大于0");
            }
            if (pageSize < 1 || pageSize > 100) {
                throw new ApiHttpException(400, "每页大小必须在1-100之间");
            }

            // 参考实现 result_filter: str | None；前端可能把 null 序列化成字面量 "null"，此处等价归一
            String normalizedFilter =
                    (resultFilter == null || resultFilter.isEmpty() || "null".equals(resultFilter))
                            ? null
                            : resultFilter;
            if (normalizedFilter != null
                    && !"all".equals(normalizedFilter)
                    && !"answer_errors".equals(normalizedFilter)
                    && !"errors_or_low_recall".equals(normalizedFilter)) {
                throw new ApiHttpException(400, "无效的评估结果筛选条件");
            }
            if (normalizedFilter != null && errorOnly) {
                throw new ApiHttpException(400, "不能同时使用 result_filter 和 error_only");
            }

            String resolvedFilter =
                    errorOnly ? "legacy_errors" : (normalizedFilter != null ? normalizedFilter : "all");
            Map<String, Object> results =
                    evalService.getRunResults(kbId, runId, page, pageSize, resolvedFilter);
            return success(results);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (IllegalArgumentException exc) {
            throw asValueError(exc);
        } catch (Exception exc) {
            log.error("获取评估运行结果失败: {}", exc.getMessage(), exc);
            throw new ApiHttpException(500, "获取评估运行结果失败: " + exc.getMessage());
        }
    }

    /** 删除评估运行。 */
    @DeleteMapping("/databases/{kb_id}/runs/{run_id}")
    @Operation(summary = "删除评估运行")
    public Map<String, Object> deleteEvaluationRun(
            @PathVariable("kb_id") String kbId, @PathVariable("run_id") String runId) {
        try {
            KnowledgePermissions.requireKnowledgeBaseManage(kbId);
            evalService.deleteRun(kbId, runId);
            return success(null);
        } catch (IllegalArgumentException exc) {
            throw asValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("删除评估运行失败: {}", exc.getMessage(), exc);
            throw new ApiHttpException(500, "删除评估运行失败: " + exc.getMessage());
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 对应参考实现的 {@code require_evaluation_dataset_read} / {@code require_evaluation_dataset_manage}：
     * 先 get_admin_user，再加载数据集（不存在 → 404），最后按数据集所属知识库判权限。
     *
     * @return 当前用户 uid
     */
    private String requireDatasetPermission(String datasetId, ResourcePermission required) {
        String uid = AuthGuards.requireAdmin();
        EvaluationDataset dataset = evaluationRepository.getDataset(datasetId);
        if (dataset == null) {
            throw new ApiHttpException(404, "评估数据集不存在");
        }
        KnowledgePermissions.ensureKnowledgeBasePermission(dataset.getKbId(), required);
        return uid;
    }

    /** 参考实现 {@code except ValueError}：含 "not found" → 404，其余 → 400。 */
    private static ApiHttpException asValueError(IllegalArgumentException exc) {
        String message = exc.getMessage() == null ? "" : exc.getMessage();
        if (message.toLowerCase().contains("not found")) {
            return new ApiHttpException(404, message);
        }
        return new ApiHttpException(400, message);
    }

    /** 成功响应体（与参考实现一致：{"message": "success", "data": ...}）。 */
    private static Map<String, Object> success(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "success");
        response.put("data", data);
        return response;
    }

    /** {@code name: str = Field(min_length=1, max_length=100)}；缺省时用 defaultValue。 */
    private static String validateName(Object raw, String defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        String value = str(raw);
        if (value == null || value.length() < NAME_MIN_LENGTH || value.length() > NAME_MAX_LENGTH) {
            throw new ApiHttpException(422, "name 长度必须在 1-100 之间");
        }
        return value;
    }

    /** pydantic {@code int = Field(default, ge, le)} 的等价校验（缺失取 default，越界 → 422）。 */
    private static int validateInt(
            Object raw, int defaultValue, int min, int max, String fieldName) {
        if (raw == null) {
            return defaultValue;
        }
        int value;
        if (raw instanceof Number number) {
            value = number.intValue();
        } else {
            try {
                value = Integer.parseInt(str(raw));
            } catch (NumberFormatException exc) {
                throw new ApiHttpException(422, fieldName + " 必须是整数");
            }
        }
        if (value < min || value > max) {
            throw new ApiHttpException(422, fieldName + " 必须在 " + min + "-" + max + " 之间");
        }
        return value;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
