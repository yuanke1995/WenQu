package com.wenqu.ai.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.security.Permission;
import com.wenqu.ai.service.RetrievalEvalCatalogService;
import com.wenqu.ai.service.RetrievalEvalRunService;
import com.wenqu.ai.service.RetrievalEvaluationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 检索评估：评估集 / 用例 / 参数组实验 / 答案质量 / 历史与灰度应用。
 * <p>旧端点（/generate、/set、/run、/run-auto、/answer/*）保留兼容；
 * 新前端使用 /overview、/cases、/runs 三组资源型端点。
 */
@Slf4j
@Tag(name = "检索评估", description = "评估集管理、参数组对比、运行历史、答案质量与灰度应用")
@RestController
@RequestMapping("/api/ai/eval")
@RequiredArgsConstructor
public class EvaluationController {

    private final RetrievalEvaluationService evalService;
    private final RetrievalEvalCatalogService catalogService;
    private final RetrievalEvalRunService runService;

    // ==================== 总览 ====================

    @Operation(summary = "检索评估总览", description = "健康度、最近运行、趋势、待处理题、评估集质量、自动体检与灰度应用状态")
    @Permission("eval:read")
    @GetMapping("/overview")
    public ResultJson overview(@RequestParam(value = "scopeKbId", required = false) String scopeKbId) {
        try {
            return ResultJson.ok(runService.overview(scopeKbId));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    // ==================== 评估集 ====================

    @Operation(summary = "评估集列表", description = "按创建时间倒序，包含历史版本；同一 scope 的最新版为当前评估集")
    @Permission("eval:read")
    @GetMapping("/sets")
    public ResultJson sets() {
        return ResultJson.ok(catalogService.listSets());
    }

    @Operation(summary = "读取当前评估集（兼容旧接口）")
    @Permission("eval:read")
    @GetMapping("/set")
    public ResultJson<RetrievalEvaluationService.EvalSet> getSet() {
        return ResultJson.ok(evalService.load());
    }

    @Operation(summary = "生成评估集", description = "从历史问答回放生成新版本；scopeKbId 非空时只保留该知识库的期望块")
    @Permission("eval:write")
    @PostMapping("/generate")
    public ResultJson generate(@RequestBody(required = false) Map<String, Object> body) {
        int maxCases = body != null && body.get("maxCases") != null
                ? Integer.parseInt(String.valueOf(body.get("maxCases"))) : 100;
        String scopeKbId = body == null || body.get("scopeKbId") == null ? null : String.valueOf(body.get("scopeKbId"));
        try {
            Map<String, Object> out = catalogService.generate(maxCases, scopeKbId);
            Object set = out.get("set");
            if (set instanceof com.wenqu.ai.model.RetrievalEvalSet s) {
                out.put("version", s.getVersion());
                out.put("generatedAt", s.getGeneratedAt());
                out.put("source", s.getSource());
            }
            return ResultJson.ok(out);
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "追加单条评估用例（差评回流）", description = "按问题去重，失效期望块自动剔除")
    @Permission("eval:write")
    @PostMapping("/case")
    public ResultJson addCase(@RequestBody Map<String, Object> body) {
        String question = body.get("question") == null ? "" : String.valueOf(body.get("question"));
        List<String> knowledgeIds = body.get("knowledgeIds") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        return ResultJson.ok(evalService.addCase(question, knowledgeIds));
    }

    // ==================== 用例管理 ====================

    @Operation(summary = "用例列表", description = "支持 all/miss/low/stale/unverified/verified/disabled 筛选与关键词搜索")
    @Permission("eval:read")
    @GetMapping("/cases")
    public ResultJson cases(@RequestParam(value = "setId", required = false) String setId,
                            @RequestParam(value = "filter", required = false) String filter,
                            @RequestParam(value = "keyword", required = false) String keyword,
                            @RequestParam(value = "page", defaultValue = "1") int page,
                            @RequestParam(value = "size", defaultValue = "20") int size) {
        try {
            return ResultJson.ok(runService.listCases(setId, filter, keyword, page, size));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "新增用例")
    @Permission("eval:write")
    @PostMapping("/cases")
    public ResultJson createCase(@RequestBody(required = false) JSONObject body) {
        try {
            return ResultJson.ok(catalogService.createCase(body));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "编辑用例", description = "问题、期望块/文档、备注、标签、人工确认、启停用")
    @Permission("eval:write")
    @PatchMapping("/cases/{id}")
    public ResultJson updateCase(@PathVariable("id") String id,
                                 @RequestBody(required = false) JSONObject body) {
        try {
            return ResultJson.ok(catalogService.updateCase(id, body));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "删除用例")
    @Permission("eval:write")
    @DeleteMapping("/cases/{id}")
    public ResultJson deleteCase(@PathVariable("id") String id) {
        try {
            return ResultJson.ok(catalogService.deleteCase(id));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "导入用例", description = "body: {cases:[{question,expectedKnowledgeIds,expectedDocIds,note,tags}]}，同问题跳过")
    @Permission("eval:write")
    @PostMapping("/cases/import")
    public ResultJson importCases(@RequestBody(required = false) JSONObject body) {
        try {
            return ResultJson.ok(catalogService.importCases(body));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "导出用例", description = "返回可再导入的 JSON 内容")
    @Permission("eval:read")
    @GetMapping("/cases/export")
    public ResultJson exportCases(@RequestParam(value = "setId", required = false) String setId) {
        try {
            return ResultJson.ok(catalogService.exportCases(setId));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    // ==================== 运行 / 历史 ====================

    @Operation(summary = "启动异步运行", description = "body: {setId?, caseIds?, groups:[...], kList?, includeJudge?}；返回 runId 后轮询 /runs/{id}")
    @Permission("eval:write")
    @PostMapping("/runs")
    public ResultJson startRun(@RequestBody(required = false) JSONObject body) {
        try {
            return ResultJson.ok(runService.startRun(body));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "运行历史", description = "分页；含指标、组摘要、状态与基线标记")
    @Permission("eval:read")
    @GetMapping("/runs")
    public ResultJson runs(@RequestParam(value = "page", defaultValue = "1") int page,
                           @RequestParam(value = "size", defaultValue = "20") int size,
                           @RequestParam(value = "setId", required = false) String setId) {
        try {
            return ResultJson.ok(runService.listRuns(page, size, setId));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "运行详情", description = "运行行 + 组摘要 + 分群 + 进度 + 期望块标题对照")
    @Permission("eval:read")
    @GetMapping("/runs/{id}")
    public ResultJson runDetail(@PathVariable("id") String id) {
        try {
            return ResultJson.ok(runService.runDetail(id));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "运行逐题结果", description = "按参数组分页；对比与明细共用")
    @Permission("eval:read")
    @GetMapping("/runs/{id}/results")
    public ResultJson runResults(@PathVariable("id") String id,
                                 @RequestParam(value = "group", required = false) String group,
                                 @RequestParam(value = "page", defaultValue = "1") int page,
                                 @RequestParam(value = "size", defaultValue = "100") int size) {
        try {
            return ResultJson.ok(runService.resultsOf(id, group, page, size));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "取消运行")
    @Permission("eval:write")
    @PostMapping("/runs/{id}/cancel")
    public ResultJson cancelRun(@PathVariable("id") String id) {
        try {
            return ResultJson.ok(runService.cancelRun(id));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "设为基线", description = "把指定成功运行设为该评估集的历史对比锚点")
    @Permission("eval:write")
    @PostMapping("/runs/{id}/baseline")
    public ResultJson setBaseline(@PathVariable("id") String id) {
        try {
            return ResultJson.ok(runService.setBaseline(id));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "两次运行对比", description = "逐题首个命中排名变化 + 改善/退化计数")
    @Permission("eval:read")
    @GetMapping("/runs/{id}/compare")
    public ResultJson compare(@PathVariable("id") String id,
                              @RequestParam("base") String base,
                              @RequestParam(value = "group", required = false) String group) {
        try {
            return ResultJson.ok(runService.compare(base, id, group));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "导出运行报告", description = "format=csv|md；返回 filename/content，由前端下载")
    @Permission("eval:read")
    @GetMapping("/runs/{id}/export")
    public ResultJson exportRun(@PathVariable("id") String id,
                                @RequestParam(value = "format", defaultValue = "csv") String format) {
        try {
            return ResultJson.ok(runService.exportRun(id, format));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    // ==================== 灰度应用 ====================

    @Operation(summary = "应用参数组（灰度观察）", description = "body: {runId, groupName}；写入参数并进入观察期，下滑自动回滚")
    @Permission("eval:write")
    @PostMapping("/apply")
    public ResultJson apply(@RequestBody(required = false) JSONObject body) {
        try {
            return ResultJson.ok(runService.applyGroup(body));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "灰度应用状态")
    @Permission("eval:read")
    @GetMapping("/apply/status")
    public ResultJson applyStatus() {
        return ResultJson.ok(runService.applyStatus());
    }

    @Operation(summary = "手动回滚灰度应用")
    @Permission("eval:write")
    @PostMapping("/apply/rollback")
    public ResultJson rollbackApply() {
        try {
            return ResultJson.ok(runService.rollbackApply());
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    // ==================== 旧端点兼容 ====================

    @Operation(summary = "最近自动体检报告", description = "定时体检与手动触发共用；含指标、相对上期变化与红黄绿状态")
    @Permission("eval:read")
    @GetMapping("/last-report")
    public ResultJson getLastReport() {
        return ResultJson.ok(evalService.lastAutoReport());
    }

    @Operation(summary = "立即自动体检", description = "按当前线上参数跑全量评估集并落运行历史")
    @Permission("eval:write")
    @PostMapping("/run-auto")
    public ResultJson runAuto() {
        return ResultJson.ok(runService.runScheduledCheck());
    }

    @Operation(summary = "启动答案级评测（异步）", description = "body: {caseCount, caseIds?}；每条两次模型调用，上限 50")
    @Permission("eval:write")
    @PostMapping("/answer/run")
    public ResultJson startAnswerEval(@RequestBody(required = false) Map<String, Object> body) {
        int caseCount = body == null || body.get("caseCount") == null ? 10 : Integer.parseInt(String.valueOf(body.get("caseCount")));
        List<String> caseIds = body != null && body.get("caseIds") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : null;
        boolean started = evalService.startAnswerEval(caseCount, caseIds);
        if (!started) return ResultJson.error("已有答案评测在运行中，请等它跑完再启动");
        return ResultJson.ok(Map.of("started", true));
    }

    @Operation(summary = "答案级评测进度与最近报告")
    @Permission("eval:read")
    @GetMapping("/answer/report")
    public ResultJson answerEvalStatus() {
        return ResultJson.ok(evalService.answerEvalStatus());
    }

    @Operation(summary = "同步运行评估（兼容旧接口）", description = "参数组对比；新前端使用 /runs 异步端点")
    @Permission("eval:write")
    @PostMapping("/run")
    public ResultJson<RetrievalEvaluationService.EvalResult> run(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return ResultJson.error("请求体不能为空：{caseIds?, groups:[{name,mode,params}], kList:[5,10,20]}");
        }
        List<String> caseIds = null;
        if (body.get("caseIds") instanceof List<?> list && !list.isEmpty()) {
            caseIds = list.stream().map(String::valueOf).toList();
        }
        List<RetrievalEvaluationService.EvalCase> allCases = evalService.load().cases();
        final List<String> selectedIds = caseIds;
        List<RetrievalEvaluationService.EvalCase> cases = selectedIds == null
                ? allCases
                : allCases.stream().filter(c -> selectedIds.contains(c.id())).toList();
        if (cases.isEmpty()) {
            return ResultJson.error("评估集为空或所选 case 不存在，请先 POST /eval/generate 生成");
        }
        List<RetrievalEvaluationService.EvalParams> groups = new ArrayList<>();
        Object g = body.get("groups");
        if (g instanceof List<?> gl && !gl.isEmpty()) {
            JSONArray arr = JSON.parseArray(JSON.toJSONString(gl));
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                groups.add(new RetrievalEvaluationService.EvalParams(
                        o.getString("name") == null ? "组" + (i + 1) : o.getString("name"),
                        o.getString("mode") == null ? "normal" : o.getString("mode"),
                        o.getDouble("vectorWeight"),
                        o.getDouble("keywordWeight"),
                        o.getDouble("vecThreshold"),
                        o.getInteger("keywordLimit"),
                        o.getInteger("topK"),
                        o.getBoolean("rerankEnabled"),
                        o.getString("lowConfidencePolicy"),
                        o.getDouble("top1Floor")));
            }
        }
        List<Integer> kList = new ArrayList<>();
        if (body.get("kList") instanceof List<?> kl) {
            for (Object k : kl) kList.add(Integer.parseInt(String.valueOf(k)));
        }
        if (kList.isEmpty()) kList = List.of(5, 10, 20);
        if (cases.size() > 500) {
            return ResultJson.error("评估 case 数超上限（≤500），请用 caseIds 缩小范围");
        }
        if (groups.size() > 8) {
            return ResultJson.error("参数组数超上限（≤8）");
        }
        return ResultJson.ok(evalService.run(cases, groups, kList));
    }
}
