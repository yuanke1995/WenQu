package com.wisesoft.ai.service;

/**
 * 工作流人工审核节点的挂起信号：节点体内已落 PENDING 审批记录，图执行到此无法继续，
 * 抛出本异常让引擎终止本轮 run（run 落 waiting_approval + 状态快照）；
 * 审批接口裁决后按快照短路重放恢复执行（见 WorkflowService.approveRun）。
 * <p>
 * 必须是 RuntimeException：要穿过 StateGraph 的 CompletableFuture 异常链一路冒到 run 收口处。
 *
 * @author yuanke
 */
public class WorkflowSuspendException extends RuntimeException {

    /** 审批记录 id（c_ai_tool_approval） */
    public final String approvalId;
    /** 挂起的审核节点 id */
    public final String nodeId;
    /** 审批卡展示信息（prompt/approvalId/timeoutSeconds，进 trace 的 waiting 条目） */
    public final transient Object traceOutput;

    public WorkflowSuspendException(String approvalId, String nodeId, Object traceOutput) {
        super("等待人工审核（approvalId=" + approvalId + "，节点 " + nodeId + "）");
        this.approvalId = approvalId;
        this.nodeId = nodeId;
        this.traceOutput = traceOutput;
    }
}
