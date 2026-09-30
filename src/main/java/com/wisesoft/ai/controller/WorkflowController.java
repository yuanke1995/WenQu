package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.model.Workflow;
import com.wisesoft.ai.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 工作流管理接口（个人资产，M0~M3 仅创建者本人可见可管；归属取登录令牌 uid）。
 * <p>
 * 分期：M0 = CRUD + 校验/编译 dry-run；M1 = 运行接口 + trace；M4 = 发布/共享/API 触发。
 * 路径在 SecurityConfig 登录白名单内（与 memory/scheduled 同口径），归属校验在服务层按 uid 做。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/workflow")
@RequiredArgsConstructor
@Tag(name = "工作流", description = "工作流定义：CRUD / DSL 校验与编译 dry-run / 调试运行与 trace（M1）")
public class WorkflowController {

    private final WorkflowService workflowService;

    @Operation(summary = "本人工作流列表", description = "按更新时间倒序；M4 前仅返回本人创建的")
    @GetMapping("/list")
    public ResultJson list() {
        List<Workflow> rows = workflowService.listOwn();
        return ResultJson.ok(rows);
    }

    @Operation(summary = "工作流详情", description = "含完整 DSL（画布加载即还原图形）")
    @GetMapping("/{id}")
    public ResultJson get(@PathVariable String id) {
        return ResultJson.ok(workflowService.getOwn(id));
    }

    @Operation(summary = "新建工作流", description = "body: {name, description?, dsl}；DSL 必须通过结构校验（不收坏图）")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        Workflow row = workflowService.create(str(body, "name"), str(body, "description"), str(body, "dsl"));
        return ResultJson.ok(Map.of("id", row.getId(), "name", row.getName()));
    }

    @Operation(summary = "更新工作流", description = "body: {name?, description?, dsl?}；只传要改的字段，dsl 变更会重新校验")
    @PutMapping("/{id}")
    public ResultJson update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Workflow row = workflowService.update(id, str(body, "name"), str(body, "description"), str(body, "dsl"));
        return ResultJson.ok(Map.of("id", row.getId(), "name", row.getName()));
    }

    @Operation(summary = "删除工作流", description = "只删本人的；运行记录保留（回放/审计价值独立于定义）")
    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable String id) {
        workflowService.delete(id);
        return ResultJson.ok(Map.of("id", id));
    }

    // --------------------------------------------------------------------------------------------------
    // M4：发布与版本
    // --------------------------------------------------------------------------------------------------

    @Operation(summary = "发布工作流", description = "把当前草稿 DSL 发布为新版本（版本号递增）；发布后草稿继续可改，"
            + "已发布版本的行为被冻结——API 触发与智能体绑定跑的都是已发布版本。body: {note?}（发布说明）")
    @PostMapping("/{id}/publish")
    public ResultJson publish(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return ResultJson.ok(workflowService.publish(id, body == null ? null : str(body, "note")));
    }

    @Operation(summary = "下线工作流", description = "已发布 → 草稿：外部 API 触发与智能体绑定随即不可用；版本历史保留")
    @PostMapping("/{id}/unpublish")
    public ResultJson unpublish(@PathVariable String id) {
        return ResultJson.ok(workflowService.unpublish(id));
    }

    @Operation(summary = "版本历史", description = "新→旧：版本号/说明/发布者/时间/current（是否为当前发布版本）")
    @GetMapping("/{id}/versions")
    public ResultJson versions(@PathVariable String id) {
        return ResultJson.ok(workflowService.listVersions(id));
    }

    @Operation(summary = "回滚到指定版本", description = "以该版本 DSL 再发一版（版本号递增，历史不被改写）")
    @PostMapping("/{id}/rollback/{version}")
    public ResultJson rollback(@PathVariable String id, @PathVariable int version) {
        return ResultJson.ok(workflowService.rollback(id, version));
    }

    @Operation(summary = "DSL 校验 + 编译 dry-run", description = "body: {dsl}；返回 errors（结构问题逐条）/ compiled（翻译层是否吃下）/ nodeCount / edgeCount。画布实时校验与保存前校验共用")
    @PostMapping("/validate")
    public ResultJson validate(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(workflowService.validate(str(body, "dsl")));
    }

    @Operation(summary = "调试运行（同步）", description = "body: {inputs?}（开始节点入参，{{start.key}} 取值来源）。"
            + "执行前 fail-fast：必填入参缺失 / llm 节点模型不可用直接报错；"
            + "执行失败不抛 500——返回 status=failed 的 run（error + node_traces 可定位问题节点）；"
            + "每次运行锁定当时 DSL 快照，改画布不影响历史回放")
    @PostMapping("/{id}/run")
    public ResultJson run(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = body == null || !(body.get("inputs") instanceof Map) ? Map.of()
                : (Map<String, Object>) body.get("inputs");
        return ResultJson.ok(workflowService.run(id, inputs));
    }

    @Operation(summary = "运行记录列表", description = "最近 50 条（新→旧）：状态/触发方式/耗时/错误摘要；trace 详情见单条接口")
    @GetMapping("/{id}/run/list")
    public ResultJson runList(@PathVariable String id) {
        return ResultJson.ok(workflowService.listRuns(id));
    }

    @Operation(summary = "运行记录详情", description = "含完整 node_traces（每节点 status/输入输出摘要/耗时/token）与 inputs/outputs")
    @GetMapping("/{id}/run/{runId}")
    public ResultJson runDetail(@PathVariable String id, @PathVariable String runId) {
        return ResultJson.ok(workflowService.getRun(id, runId));
    }

    @Operation(summary = "人工审核：待审批信息", description = "run 处于 waiting_approval 时返回审批卡内容（prompt/超时/挂起时刻）；无待审批返回 null")
    @GetMapping("/{id}/run/{runId}/approval")
    public ResultJson pendingApproval(@PathVariable String id, @PathVariable String runId) {
        return ResultJson.ok(workflowService.pendingApproval(id, runId));
    }

    @Operation(summary = "人工审核：裁决并恢复续跑", description = "body: {approved: true|false}。仅运行发起人可裁决；"
            + "按挂起快照恢复执行（已完成节点短路回放，不重复消耗 LLM 调用），本次请求内同步跑完并返回终态 run；"
            + "批准走 approve 分支、拒绝走 reject 分支")
    @PostMapping("/{id}/run/{runId}/approval")
    public ResultJson resolveApproval(@PathVariable String id, @PathVariable String runId,
                                      @RequestBody Map<String, Object> body) {
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        return ResultJson.ok(workflowService.approveRun(id, runId, approved));
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
