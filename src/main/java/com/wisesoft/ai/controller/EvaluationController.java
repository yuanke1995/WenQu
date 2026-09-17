package com.wisesoft.ai.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.RetrievalEvaluationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 检索量化评估：评估集生成/读取 + 批量参数组对比运行（recall@k/MRR/命中率 + 弃用断言 + 多路对比）
 */
@Slf4j
@Tag(name = "检索评估")
@RestController
@RequestMapping("/api/ai/eval")
public class EvaluationController {

    private final RetrievalEvaluationService evalService;

    public EvaluationController(RetrievalEvaluationService evalService) {
        this.evalService = evalService;
    }

    /** 从历史问答回放生成评估集 */
    @Operation(summary = "追加单条评估 case", description = "差评回流：把「问题 → 引用过的知识块」加入评估集（按问题去重，失效期望块自动剔除）")
    @PostMapping("/case")
    public ResultJson addCase(
            @Parameter(description = "{\"question\": \"问题\", \"knowledgeIds\": [\"期望知识块ID\"]}")
            @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
        String question = body.get("question") == null ? "" : String.valueOf(body.get("question"));
        List<String> knowledgeIds = body.get("knowledgeIds") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        return ResultJson.ok(evalService.addCase(question, knowledgeIds));
    }

    @Operation(summary = "生成评估集（回放 c_ai_message.sources）")
    @PostMapping("/generate")
    public ResultJson<RetrievalEvaluationService.EvalSet> generate(@RequestBody(required = false) Map<String, Object> body) {
        int maxCases = body != null && body.get("maxCases") != null
                ? Integer.parseInt(String.valueOf(body.get("maxCases"))) : 100;
        return ResultJson.ok(evalService.generate(maxCases));
    }

    /** 读取当前评估集 */
    @Operation(summary = "读取评估集")
    @GetMapping("/set")
    public ResultJson<RetrievalEvaluationService.EvalSet> getSet() {
        return ResultJson.ok(evalService.load());
    }

    /** 最近一次自动体检报告（从未运行返回 null） */
    @Operation(summary = "最近自动体检报告", description = "定时体检（默认每日）与手动触发共用；含指标、相对上期变化与红黄绿状态")
    @GetMapping("/last-report")
    public ResultJson getLastReport() {
        return ResultJson.ok(evalService.lastAutoReport());
    }

    /** 手动触发自动体检（同步执行，评估集大时耗时数十秒，前端 loading 等待） */
    @Operation(summary = "立即自动体检", description = "按当前线上参数跑全量评估集并对比上期，结果写 eval.lastReport")
    @PostMapping("/run-auto")
    public ResultJson runAuto() {
        return ResultJson.ok(evalService.runAutoCheck());
    }

    /** 批量运行评估（多组参数对比） */
    @Operation(summary = "运行评估（参数组对比）")
    @PostMapping("/run")
    public ResultJson<RetrievalEvaluationService.EvalResult> run(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return ResultJson.error("请求体不能为空：{caseIds?, groups:[{name,mode,params}], kList:[5,10,20]}");
        }
        // caseIds（可选，空=全量）
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

        // 参数组
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
                        o.getBoolean("rerankEnabled")));
            }
        }

        // k 列表
        List<Integer> kList = new ArrayList<>();
        if (body.get("kList") instanceof List<?> kl) {
            for (Object k : kl) kList.add(Integer.parseInt(String.valueOf(k)));
        }
        if (kList.isEmpty()) kList = List.of(5, 10, 20);

        if (cases.size() > 100) {
            return ResultJson.error("评估 case 数超上限（≤100），请用 caseIds 缩小范围");
        }
        if (groups.size() > 8) {
            return ResultJson.error("参数组数超上限（≤8）");
        }
        return ResultJson.ok(evalService.run(cases, groups, kList));
    }
}
