/**
 * 工作流：client.workflows.*（需 API Key 开通 workflow.run scope）
 * 触发的是已发布版本的 DSL；运行失败不抛错——返回 status=failed 的 run，读 error/nodeTraces 定位。
 */

import type { WenquClient } from "./client.js";
import { WenquError } from "./errors.js";
import type { WorkflowRun, WorkflowRunParams } from "./types.js";

function enc(segment: string): string {
  return encodeURIComponent(segment);
}

export class Workflows {
  constructor(private readonly client: WenquClient) {}

  /** 同步触发运行，返回 run 全量信息（status/outputs/nodeTraces/durationMs） */
  async run(workflowId: string, params?: WorkflowRunParams): Promise<WorkflowRun> {
    if (!workflowId) throw new WenquError("workflows.run: workflowId 必填");
    return this.client.envelopeRequest<WorkflowRun>(`/api/ai/v1/workflows/${enc(workflowId)}/run`, {
      method: "POST",
      body: JSON.stringify({ inputs: params?.inputs ?? {} }),
    });
  }

  /** 按 runId 补查运行结果（同步触发已直接返回，本方法供回溯核对） */
  async getRun(workflowId: string, runId: string): Promise<WorkflowRun> {
    if (!workflowId) throw new WenquError("workflows.getRun: workflowId 必填");
    if (!runId) throw new WenquError("workflows.getRun: runId 必填");
    return this.client.envelopeRequest<WorkflowRun>(
      `/api/ai/v1/workflows/${enc(workflowId)}/run/${enc(runId)}`,
    );
  }
}
