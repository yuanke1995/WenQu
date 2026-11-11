package com.wenqu.ai.controller;

import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.security.Permission;
import com.wenqu.ai.service.AgentEvalService;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 智能体评测与发布门禁（智能体评测立项方案.md §6.1）。
 * <p>
 * 资产口径：评测数据按<b>智能体归属</b>隔离——读端点要求智能体对当前用户可读
 * （{@code AgentService.get}，含共享范围），写端点与「运行回归」要求可管理（谁建归谁，
 * 管理员按现有管理口径放行）；归属校验收在 {@code AgentEvalService} 的 mustReadable /
 * mustManageable，本控制器不重复判定。权限点 {@code agentEval:read} / {@code agentEval:write}。
 * <p>与 {@code /api/ai/eval/**}（检索评估）是两条线：那边回答「检索参数哪组好」，
 * 这边回答「这个智能体这一版好不好」，前缀与权限点都分开，不共用。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/agent-eval")
@RequiredArgsConstructor
@Tag(name = "智能体评测", description = "评测集管理、回归运行、门禁状态与版本对比")
public class AgentEvalController {

    private final AgentEvalService agentEvalService;

    @Operation(summary = "评测总览", description = "可见智能体逐行门禁状态 + 汇总统计 + 近 6 周通过率趋势 + 最近回归")
    @Permission("agentEval:read")
    @GetMapping("/summary")
    public ResultJson summary() {
        return ResultJson.ok(agentEvalService.summary());
    }

    @Operation(summary = "套件与用例", description = "无套件时 suite 为 null（未评测态）")
    @Permission("agentEval:read")
    @GetMapping("/agents/{agentId}/suite")
    public ResultJson suite(@PathVariable("agentId") String agentId) {
        try {
            return ResultJson.ok(agentEvalService.suiteDetail(agentId));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "创建/更新套件设置", description = "门槛、单条通过分、启停、名称；一个智能体一个套件，重复调用即更新")
    @Permission("agentEval:write")
    @PostMapping("/agents/{agentId}/suite")
    public ResultJson saveSuite(@PathVariable("agentId") String agentId,
                                @RequestBody(required = false) Map<String, Object> body) {
        try {
            return ResultJson.ok(agentEvalService.saveSuite(agentId, body));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "批量维护用例", description = "body: {add:[{question,expectedKnowledgeIds,expectedDocIds,note,tags}],update:[{id,...}],delete:[id]}")
    @Permission("agentEval:write")
    @PostMapping("/agents/{agentId}/cases")
    public ResultJson mutateCases(@PathVariable("agentId") String agentId,
                                  @RequestBody(required = false) JSONObject body) {
        try {
            return ResultJson.ok(agentEvalService.mutateCases(agentId, body));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "从会话采样生成用例", description = "body: {count, onlyBad}；差评轮次优先，问题+该轮实际引用作为期望引用，同问题去重")
    @Permission("agentEval:write")
    @PostMapping("/agents/{agentId}/cases/generate")
    public ResultJson generateCases(@PathVariable("agentId") String agentId,
                                    @RequestBody(required = false) JSONObject body) {
        try {
            return ResultJson.ok(agentEvalService.generateFromSessions(agentId, body));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "启动回归", description = "body: {caseIds?, trigger?, allowSideEffects?}；默认跑全部启用用例；评测只读（需人工确认的工具自动拒绝）")
    @Permission("agentEval:write")
    @PostMapping("/agents/{agentId}/runs")
    public ResultJson startRun(@PathVariable("agentId") String agentId,
                               @RequestBody(required = false) JSONObject body) {
        try {
            List<String> caseIds = body == null || body.getJSONArray("caseIds") == null
                    ? null : body.getJSONArray("caseIds").toJavaList(String.class);
            String trigger = body == null ? null : body.getString("trigger");
            boolean allowSideEffects = body != null && Boolean.TRUE.equals(body.getBoolean("allowSideEffects"));
            return ResultJson.ok(agentEvalService.startRun(agentId, trigger, caseIds, allowSideEffects));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "运行历史", description = "分页；page 默认 1，size 默认 20 上限 100")
    @Permission("agentEval:read")
    @GetMapping("/agents/{agentId}/runs")
    public ResultJson runs(@PathVariable("agentId") String agentId,
                           @RequestParam(value = "page", defaultValue = "1") int page,
                           @RequestParam(value = "size", defaultValue = "20") int size) {
        try {
            return ResultJson.ok(agentEvalService.runsOf(agentId, page, size));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "运行详情", description = "运行行 + 逐条结果 + 期望引用标题对照；running 时前端按 done/total 展示进度")
    @Permission("agentEval:read")
    @GetMapping("/runs/{runId}")
    public ResultJson run(@PathVariable("runId") String runId) {
        try {
            return ResultJson.ok(agentEvalService.runDetail(runId));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "取消运行", description = "置取消标记并叫停在跑用例；已完成用例结果保留")
    @Permission("agentEval:write")
    @PostMapping("/runs/{runId}/cancel")
    public ResultJson cancelRun(@PathVariable("runId") String runId) {
        try {
            return ResultJson.ok(agentEvalService.cancelRun(runId));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "设为基线", description = "把指定成功运行设为该智能体「较上版」的比较锚点；body: {runId}")
    @Permission("agentEval:write")
    @PostMapping("/agents/{agentId}/set-baseline")
    public ResultJson setBaselineOfAgent(@PathVariable("agentId") String agentId,
                                         @RequestBody(required = false) JSONObject body) {
        try {
            String runId = body == null ? null : body.getString("runId");
            if (runId == null || runId.isBlank()) throw new BizException("缺少 runId");
            return ResultJson.ok(agentEvalService.setBaseline(agentId, runId));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "两次运行对比", description = "A/B 逐用例对比：变好/变差/仅B失败/持平，结论与逐题变化清单")
    @Permission("agentEval:read")
    @GetMapping("/agents/{agentId}/compare")
    public ResultJson compare(@PathVariable("agentId") String agentId,
                              @RequestParam("runA") String runA,
                              @RequestParam("runB") String runB) {
        try {
            return ResultJson.ok(agentEvalService.compare(agentId, runA, runB));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "门禁状态", description = "五态：none/unevaluated/changed/running/pass/below；编辑器与列表同源判据")
    @Permission("agentEval:read")
    @GetMapping("/agents/{agentId}/gate")
    public ResultJson gate(@PathVariable("agentId") String agentId) {
        try {
            return ResultJson.ok(agentEvalService.gateStatus(agentId));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "保存前检查", description = "body = 智能体保存 payload（原样透传）；返回门禁状态 + 本次保存的字段级变更清单，不落任何数据")
    @Permission("agentEval:write")
    @PostMapping("/agents/{agentId}/gate")
    public ResultJson gateCheck(@PathVariable("agentId") String agentId,
                                @RequestBody(required = false) Map<String, Object> payload) {
        try {
            return ResultJson.ok(agentEvalService.gateCheck(agentId, payload));
        } catch (BizException e) {
            return ResultJson.error(e.getCode(), e.getMessage());
        }
    }
}
