package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.model.Workflow;
import com.wenqu.ai.service.WorkflowService;
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

    @Operation(summary = "工作流列表", description = "分栏返回 {mine:[我创建的], shared:[共享给我的]}，各自按更新时间倒序；"
            + "每条带 myPermission（MANAGE/READ）与 mine，前端据此渲染可编辑/只读")
    @GetMapping("/list")
    public ResultJson list() {
        return ResultJson.ok(workflowService.listVisible());
    }

    @Operation(summary = "工作流详情", description = "含完整 DSL（画布加载即还原图形）；共享只读者也可加载（只读）。带 myPermission/mine")
    @GetMapping("/{id}")
    public ResultJson get(@PathVariable("id") String id) {
        return ResultJson.ok(workflowService.getVisible(id));
    }

    @Operation(summary = "新建工作流", description = "body: {name, description?, dsl}；DSL 必须通过结构校验（不收坏图）")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        Workflow row = workflowService.create(str(body, "name"), str(body, "description"), str(body, "dsl"));
        return ResultJson.ok(Map.of("id", row.getId(), "name", row.getName()));
    }

    @Operation(summary = "更新工作流", description = "body: {name?, description?, dsl?}；只传要改的字段，dsl 变更会重新校验")
    @PutMapping("/{id}")
    public ResultJson update(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        Workflow row = workflowService.update(id, str(body, "name"), str(body, "description"), str(body, "dsl"));
        return ResultJson.ok(Map.of("id", row.getId(), "name", row.getName()));
    }

    @Operation(summary = "删除工作流", description = "只删本人的；运行记录保留（回放/审计价值独立于定义）")
    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable("id") String id) {
        workflowService.delete(id);
        return ResultJson.ok(Map.of("id", id));
    }

    // --------------------------------------------------------------------------------------------------
    // 批量操作：逐条执行、部分成功是批量的固有语义——返回 succeeded/failed（失败条目带原因）
    // --------------------------------------------------------------------------------------------------

    @Operation(summary = "批量删除工作流", description = "body: {ids:[...]}；仅本人创建的可删；"
            + "返回 {succeeded:[id], failed:[{id,name,error}]}（部分成功是批量固有语义，失败条目逐条带原因）")
    @PostMapping("/batch-delete")
    public ResultJson batchDelete(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(workflowService.batchDelete(ids(body)));
    }

    @Operation(summary = "批量发布工作流", description = "body: {ids:[...], note?}；逐条走单条发布语义"
            + "（草稿校验 → 版本号递增 → 冻结）；校验不过的条目进 failed 带原因")
    @PostMapping("/batch-publish")
    public ResultJson batchPublish(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(workflowService.batchPublish(ids(body), str(body, "note")));
    }

    @Operation(summary = "批量下线工作流", description = "body: {ids:[...]}；逐条已发布 → 草稿；"
            + "非已发布条目进 failed 带原因（不静默跳过）")
    @PostMapping("/batch-unpublish")
    public ResultJson batchUnpublish(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(workflowService.batchUnpublish(ids(body)));
    }

    // --------------------------------------------------------------------------------------------------
    // M4：发布与版本
    // --------------------------------------------------------------------------------------------------

    @Operation(summary = "发布工作流", description = "把当前草稿 DSL 发布为新版本（版本号递增）；发布后草稿继续可改，"
            + "已发布版本的行为被冻结——API 触发与智能体绑定跑的都是已发布版本。body: {note?}（发布说明）")
    @PostMapping("/{id}/publish")
    public ResultJson publish(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body) {
        return ResultJson.ok(workflowService.publish(id, body == null ? null : str(body, "note")));
    }

    @Operation(summary = "下线工作流", description = "已发布 → 草稿：外部 API 触发与智能体绑定随即不可用；版本历史保留")
    @PostMapping("/{id}/unpublish")
    public ResultJson unpublish(@PathVariable("id") String id) {
        return ResultJson.ok(workflowService.unpublish(id));
    }

    @Operation(summary = "版本历史", description = "新→旧：版本号/说明/发布者/时间/current（是否为当前发布版本）")
    @GetMapping("/{id}/versions")
    public ResultJson versions(@PathVariable("id") String id) {
        return ResultJson.ok(workflowService.listVersions(id));
    }

    @Operation(summary = "回滚到指定版本", description = "以该版本 DSL 再发一版（版本号递增，历史不被改写）")
    @PostMapping("/{id}/rollback/{version}")
    public ResultJson rollback(@PathVariable("id") String id, @PathVariable("version") int version) {
        return ResultJson.ok(workflowService.rollback(id, version));
    }

    @Operation(summary = "DSL 校验 + 编译 dry-run", description = "body: {dsl}；返回 errors（结构问题逐条）/ compiled（翻译层是否吃下）/ nodeCount / edgeCount。画布实时校验与保存前校验共用")
    @PostMapping("/validate")
    public ResultJson validate(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(workflowService.validate(str(body, "dsl")));
    }

    @Operation(summary = "调试运行（默认同步；async=true 异步）", description = "body: {inputs?, async?}（开始节点入参，{{start.key}} 取值来源）。"
            + "同步（默认）：本次请求内跑完并返回终态 run，失败不抛 500——返回 status=failed 的 run（error + node_traces 可定位问题节点）。"
            + "异步（async=true）：创建即返回 status=queued 的 run，实际执行在派发池；前端订阅 /run/{runId}/stream 边跑边亮，"
            + "队列满会直接失败（workflow.runQueueCapacity）。执行前 fail-fast：必填入参缺失 / llm 节点模型不可用直接报错；"
            + "每次运行锁定当时 DSL 快照，改画布不影响历史回放")
    @PostMapping("/{id}/run")
    public ResultJson run(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = body == null || !(body.get("inputs") instanceof Map) ? Map.of()
                : (Map<String, Object>) body.get("inputs");
        boolean async = body != null && Boolean.TRUE.equals(body.get("async"));
        return ResultJson.ok(async ? workflowService.runAsync(id, inputs) : workflowService.run(id, inputs));
    }

    @Operation(summary = "运行进度流（SSE）", description = "第 2 期：订阅某次运行的实时进度——节点完成推 trace 事件、"
            + "状态迁移推 status 事件、收口推 done 事件（带终态 run 全量）后关流；订阅瞬间先回放 snapshot（当前状态 + 已产生 trace），"
            + "断线重连不丢时间线；已终态则回放后立即关闭")
    @GetMapping(value = "/{id}/run/{runId}/stream", produces = "text/event-stream;charset=UTF-8")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter runStream(@PathVariable("id") String id,
                                                                                     @PathVariable("runId") String runId) {
        return workflowService.subscribeRunStream(id, runId);
    }

    @Operation(summary = "取消排队中的运行", description = "第 2 期：仅 status=queued 的运行可取消（条件更新 queued→failed，"
            + "与任务启动的 queued→running 互斥）；已在运行 / 已结束报错——运行中的 LLM/HTTP 节点无法安全打断")
    @PostMapping("/{id}/run/{runId}/cancel")
    public ResultJson cancelRun(@PathVariable("id") String id, @PathVariable("runId") String runId) {
        return ResultJson.ok(workflowService.cancelQueuedRun(id, runId));
    }

    @Operation(summary = "运行记录列表", description = "最近 50 条（新→旧）：状态/触发方式/耗时/错误摘要；trace 详情见单条接口")
    @GetMapping("/{id}/run/list")
    public ResultJson runList(@PathVariable("id") String id) {
        return ResultJson.ok(workflowService.listRuns(id));
    }

    @Operation(summary = "运行记录详情", description = "含完整 node_traces（每节点 status/输入输出摘要/耗时/token）与 inputs/outputs")
    @GetMapping("/{id}/run/{runId}")
    public ResultJson runDetail(@PathVariable("id") String id, @PathVariable("runId") String runId) {
        return ResultJson.ok(workflowService.getRun(id, runId));
    }

    @Operation(summary = "人工审核：待审批信息", description = "run 处于 waiting_approval 时返回审批卡内容（prompt/超时/挂起时刻）；无待审批返回 null")
    @GetMapping("/{id}/run/{runId}/approval")
    public ResultJson pendingApproval(@PathVariable("id") String id, @PathVariable("runId") String runId) {
        return ResultJson.ok(workflowService.pendingApproval(id, runId));
    }

    @Operation(summary = "人工审核：裁决并恢复续跑", description = "body: {approved: true|false}。仅运行发起人可裁决；"
            + "按挂起快照恢复执行（已完成节点短路回放，不重复消耗 LLM 调用），本次请求内同步跑完并返回终态 run；"
            + "批准走 approve 分支、拒绝走 reject 分支")
    @PostMapping("/{id}/run/{runId}/approval")
    public ResultJson resolveApproval(@PathVariable("id") String id, @PathVariable("runId") String runId,
                                      @RequestBody Map<String, Object> body) {
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        return ResultJson.ok(workflowService.approveRun(id, runId, approved));
    }

    // --------------------------------------------------------------------------------------------------
    // M5：模板库 / 委派编排转工作流 / 失败检查点续跑
    // --------------------------------------------------------------------------------------------------

    @Operation(summary = "内置模板库", description = "M5：内置工作流模板清单（检索问答 / 并行多视角 / 审核流水线）；"
            + "dsl 字段可直接作为新建接口的 dsl 入参（选用即创建并进画布），llm 节点 modelRef 留空走个人默认模型")
    @GetMapping("/templates")
    public ResultJson templates() {
        return ResultJson.ok(workflowService.templates());
    }

    @Operation(summary = "委派编排转工作流", description = "M5：把智能体的委派编排（subAgentIds 并行委派）一键转成工作流并创建——"
            + "start 扇出子智能体节点并行作答 → 模板节点聚合 → LLM 总结 → 结束；主智能体与全部子智能体须对当前用户可读，"
            + "未配置委派的智能体报错。返回新建的工作流")
    @PostMapping("/from-agent/{agentId}")
    public ResultJson fromAgent(@PathVariable("agentId") String agentId) {
        Workflow row = workflowService.createFromAgent(agentId);
        return ResultJson.ok(Map.of("id", row.getId(), "name", row.getName()));
    }

    @Operation(summary = "失败检查点续跑", description = "M5：对 failed/timeout 且带检查点快照的运行从失败点续跑——"
            + "按 run 锁定的 DSL 快照恢复执行，已成功节点短路回放（不重复消耗 LLM 调用），仅失败节点及其下游真正执行；"
            + "仅运行发起人可续跑；本次请求内同步跑完并返回终态 run。无快照（失败在首个节点前）时报错引导直接重跑")
    @PostMapping("/{id}/run/{runId}/resume")
    public ResultJson resume(@PathVariable("id") String id, @PathVariable("runId") String runId) {
        return ResultJson.ok(workflowService.resumeRun(id, runId));
    }

    // --------------------------------------------------------------------------------------------------
    // 第 1 期：共享范围 + 定时触发 + 终态回调
    // --------------------------------------------------------------------------------------------------

    @Operation(summary = "设置共享范围", description = "body: {shareConfig}（v2 JSON，空串=清空共享回落私有）。"
            + "仅创建者 / 管理范围命中可改；写入前强校验 version=2 且 manage ⊆ read。共享只读者可查看画布（只读）与运行已发布版本")
    @PostMapping("/{id}/share")
    public ResultJson share(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body) {
        return ResultJson.ok(workflowService.share(id, body == null ? null : str(body, "shareConfig")));
    }

    @Operation(summary = "自动化配置（定时 + 回调）", description = "定时 cron（5 段）+ 时区 + 开关 + 回调地址/密钥；"
            + "定时只跑已发布版本，未发布时触发会被跳过。回调密钥只回是否已设置（不回明文）")
    @GetMapping("/{id}/automation")
    public ResultJson automation(@PathVariable("id") String id) {
        return ResultJson.ok(workflowService.automationConfig(id));
    }

    @Operation(summary = "保存自动化配置", description = "body: {scheduleEnabled, cron, timezone, callbackUrl, callbackSecret}；"
            + "仅管理权可改；启用定时需已填 cron；回调地址限 http/https 且不得指向内网")
    @PutMapping("/{id}/automation")
    public ResultJson saveAutomation(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body) {
        return ResultJson.ok(workflowService.saveAutomation(id, body));
    }

    @Operation(summary = "立即触发定时运行", description = "手动跑一次定时任务（跑已发布版本，trigger=schedule）；"
            + "同步返回终态 run 便于直接看 trace；未发布时报错")
    @PostMapping("/{id}/schedule/run-now")
    public ResultJson runScheduleNow(@PathVariable("id") String id) {
        return ResultJson.ok(workflowService.runScheduleNow(id));
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** body.ids → List&lt;String&gt;（元素按字符串收，兼容前端传数字形态） */
    private static List<String> ids(Map<String, Object> body) {
        Object v = body.get("ids");
        if (!(v instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }
}
