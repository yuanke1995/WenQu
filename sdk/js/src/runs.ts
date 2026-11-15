/**
 * v1 run 原语（A2）：建轮 / 状态 / 事件流 / 审批 / 产物 / 插话 / 停止。
 *
 * <p><b>与 native 命名空间是两套协议，不要混用</b>：native 的事件名在 JSON 的
 * {@code type} 字段、content 常是字符串套 JSON、续传靠 sessionId + snapshot；
 * v1 runs 的事件名在 SSE 的 {@code event:} 行、data 是带 runId 的扁平对象、
 * 每帧带 {@code id:}（seq），断线靠 {@code Last-Event-ID} 补增量。</p>
 *
 * <p><b>成功响应是裸对象</b>：v1 命名空间不走平台 {@code {success,data}} 包络，
 * 所以这里统一用 {@code jsonRequest}，不能用 {@code envelopeRequest}。</p>
 */

import type { WenquClient } from "./client.js";
import { WenquError, WenquApiError } from "./errors.js";
import { sseEvents } from "./sse.js";

const ROOT = "/api/ai/v1";

/** 冻结的 v1 事件名（真源：服务端 OpenRunEmitter.EVENTS + run.started 手写帧） */
export type RunEventName =
  | "run.started" | "token" | "tool.status" | "plan.updated"
  | "approval.required" | "artifact.created" | "approval.timed_out"
  | "run.done" | "run.error";

/** 稳定错误码（真源：服务端 OpenRunErrorCode） */
export type RunErrorCode =
  | "internal_error" | "resume_expired" | "approval_timeout"
  | "rate_limited" | "agent_not_found" | "invalid_request";

export interface RunDetail {
  runId: string;
  status: string;
  sessionId: string;
  agentId: string | null;
  createdAt: string | null;
  finishedAt: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  /** 终态帧到达后由回放缓冲提供；否则只有 answer 摘录 */
  output?: Record<string, unknown>;
  answer?: string | null;
  requestId?: string;
  /** Idempotency-Key 命中时为 true */
  idempotent?: boolean;
}

export interface RunCreateParams {
  question: string;
  /** 不传则服务端新建会话；传了必须是签发人自己的会话 */
  sessionId?: string;
  /** 同 Key 重放只回同一 runId，不新起轮 */
  idempotencyKey?: string;
}

export interface RunArtifact {
  id: string;
  name: string;
  filename: string;
  kind: string;
  preview: string;
  ext: string;
  size: number | null;
  createdAt: string | null;
  /** 当场换发的签名 URL（服务端默认 1 小时） */
  downloadUrl: string;
  expiresInSeconds: number;
}

export interface RunArtifactList {
  runId: string;
  count: number;
  artifacts: RunArtifact[];
}

export type RunDecision = "approve" | "reject" | "revise" | "supersede" | "answer" | "ignore";

export interface RunApprovalParams {
  /** 来自 approval.required 事件的 approvalId */
  approvalId: string;
  decision: RunDecision;
  reason?: string;
  /** 工具审批：session = 本次会话放行该工具 */
  scope?: string;
  /** 计划卡 revise/supersede 的修改意见 */
  plan?: string;
  /** 提问卡 answer 的答案数组（与问题下标对齐） */
  answers?: string[];
}

export interface RunApprovalResult {
  runId: string;
  approvalId: string;
  kind: "tool_approval" | "plan_approval" | "ask_user";
  decision: string;
  accepted: boolean;
}

export interface RunSteerResult { runId: string; accepted: boolean; messageId: string }
export interface RunStopResult { runId: string; stopped: boolean; status: string }

/** SSE 上一帧：id 是服务端 seq（从 1 起），event 是 v1 事件名，data 是扁平对象 */
export interface RunEvent {
  id: number;
  event: string;
  data: Record<string, unknown>;
}

export interface RunDoneData extends Record<string, unknown> {
  runId: string;
  status: "succeeded" | "stopped";
  finalContent?: string;
  usage?: Record<string, unknown>;
  sources?: unknown[];
  artifacts?: unknown[];
}

export interface RunErrorData extends Record<string, unknown> {
  runId: string;
  status: "failed";
  code: string;
  message: string;
  request_id?: string;
}

export interface RunStreamHandlers {
  /** 所有帧的统一入口（含将来只增不删新增的事件），先于具名回调触发 */
  onEvent?: (e: RunEvent) => void;
  onStarted?: (d: Record<string, unknown>) => void;
  onToken?: (delta: string) => void;
  onToolStatus?: (d: Record<string, unknown>) => void;
  onPlan?: (d: unknown) => void;
  onApprovalRequired?: (d: Record<string, unknown>) => void;
  onArtifact?: (d: Record<string, unknown>) => void;
  onApprovalTimedOut?: (d: Record<string, unknown>) => void;
  onDone?: (d: RunDoneData) => void;
  onError?: (d: RunErrorData) => void;
  /** 回放缓冲过期：SDK 已自动降级重拉 GET /runs/{id}，这里交回那份状态 */
  onFallback?: (detail: RunDetail) => void;
  onReconnect?: (info: { attempt: number; lastEventId: number; reason: string }) => void;
}

export interface RunStreamOptions {
  /** 从哪个 seq 之后开始（断线恢复时传上次收到的 lastEventId） */
  lastEventId?: number;
  /** 终态前断线的最大重连次数，默认 3 */
  maxReconnects?: number;
  /** 重连退避基数（毫秒），默认 200；测试可传 0 */
  reconnectDelayMs?: number;
  signal?: AbortSignal;
}

export interface RunStreamSummary {
  status: "succeeded" | "failed" | "stopped" | "resume_expired" | "cancelled";
  lastEventId: number;
  reconnects: number;
  /** resume_expired 降级时带回的 GET /runs/{id} 结果 */
  detail?: RunDetail;
  error?: { code: string; message: string; requestId?: string };
}

/** 4xx 里「重试也没用」的那几个：鉴权/权限/不存在/限流直接抛，其余（网络错误、5xx、流中断）可重连 */
function retryable(e: unknown): boolean {
  if (e instanceof WenquApiError) {
    const s = e.status;
    if (s === 401 || s === 403 || s === 404 || s === 429) return false;
  }
  return true;
}

function str(v: unknown, fallback = ""): string {
  return typeof v === "string" ? v : fallback;
}

/** v1 runs 命名空间：挂在 WenquClient.runs */
export class Runs {
  private readonly client: WenquClient;

  constructor(client: WenquClient) {
    this.client = client;
  }

  create(agentId: string, params: RunCreateParams): Promise<RunDetail> {
    if (!params || typeof params.question !== "string" || params.question.trim() === "") {
      return Promise.reject(new WenquError("question 必填"));
    }
    const headers: Record<string, string> = {};
    if (params.idempotencyKey) headers["Idempotency-Key"] = params.idempotencyKey;
    const body: Record<string, unknown> = { question: params.question };
    if (params.sessionId) body.sessionId = params.sessionId;
    return this.client.jsonRequest<RunDetail>(
      `${ROOT}/agents/${encodeURIComponent(agentId)}/runs`,
      { method: "POST", body: JSON.stringify(body), headers });
  }

  get(runId: string): Promise<RunDetail> {
    return this.client.jsonRequest<RunDetail>(`${ROOT}/runs/${encodeURIComponent(runId)}`);
  }

  artifacts(runId: string): Promise<RunArtifactList> {
    return this.client.jsonRequest<RunArtifactList>(`${ROOT}/runs/${encodeURIComponent(runId)}/artifacts`);
  }

  approve(runId: string, params: RunApprovalParams): Promise<RunApprovalResult> {
    if (!params || !params.approvalId) return Promise.reject(new WenquError("approvalId 必填"));
    if (!params.decision) return Promise.reject(new WenquError("decision 必填"));
    const body: Record<string, unknown> = {
      approvalId: params.approvalId, decision: params.decision,
    };
    for (const k of ["reason", "scope", "plan", "answers"] as const) {
      if (params[k] !== undefined) body[k] = params[k];
    }
    return this.client.jsonRequest<RunApprovalResult>(
      `${ROOT}/runs/${encodeURIComponent(runId)}/approvals`,
      { method: "POST", body: JSON.stringify(body) });
  }

  steer(runId: string, text: string): Promise<RunSteerResult> {
    if (typeof text !== "string" || text.trim() === "") {
      return Promise.reject(new WenquError("text 必填"));
    }
    return this.client.jsonRequest<RunSteerResult>(
      `${ROOT}/runs/${encodeURIComponent(runId)}/steer`,
      { method: "POST", body: JSON.stringify({ text }) });
  }

  stop(runId: string): Promise<RunStopResult> {
    return this.client.jsonRequest<RunStopResult>(
      `${ROOT}/runs/${encodeURIComponent(runId)}/stop`, { method: "POST" });
  }

  /**
   * 订阅事件流：自动按 SSE {@code id:} 记 seq，断线带 {@code Last-Event-ID} 只补增量；
   * 收到 {@code run.error(code=resume_expired)} 自动降级重拉 {@code get(runId)}；
   * 终态（run.done / run.error）或取消才返回。
   *
   * <p>不会把过期伪装成一条新流从头开始，也不会把同一 seq 的帧重复派发。</p>
   */
  async stream(runId: string, handlers: RunStreamHandlers = {},
               options: RunStreamOptions = {}): Promise<RunStreamSummary> {
    const maxReconnects = options.maxReconnects ?? 3;
    const baseDelay = options.reconnectDelayMs ?? 200;
    const signal = options.signal;
    let lastId = Math.max(0, options.lastEventId ?? 0);
    let reconnects = 0;
    const cancelled = (): RunStreamSummary =>
      ({ status: "cancelled", lastEventId: lastId, reconnects });
    const path = `${ROOT}/runs/${encodeURIComponent(runId)}/events`;

    while (true) {
      if (signal?.aborted) return cancelled();
      const headers: Record<string, string> = { Accept: "text/event-stream" };
      if (lastId > 0) headers["Last-Event-ID"] = String(lastId);

      let res: Response;
      try {
        res = await this.client.rawRequest(path, { headers, signal });
      } catch (e) {
        if (signal?.aborted) return cancelled();
        if (!retryable(e) || reconnects >= maxReconnects) throw e;
        await delay(baseDelay * (reconnects + 1));
        reconnects++;
        handlers.onReconnect?.({ attempt: reconnects, lastEventId: lastId, reason: "请求失败" });
        continue;
      }
      if (!res.body) throw new WenquError("事件流响应没有响应体（res.body 为空）");

      let terminal: RunStreamSummary | null = null;
      let broken: unknown = null;
      try {
        for await (const ev of sseEvents(res.body)) {
          if (signal?.aborted) return cancelled();
          const seq = ev.id ? Number(ev.id) : 0;
          // 服务端续传只回 id > afterId；这里再做一次单调去重，防边界重复
          if (seq > 0 && seq <= lastId) continue;
          if (seq > 0) lastId = seq;
          const name = str(ev.event);
          let data: Record<string, unknown>;
          try {
            data = JSON.parse(ev.data) as Record<string, unknown>;
          } catch {
            throw new WenquError(`v1 事件帧不是合法 JSON：${ev.data.slice(0, 200)}`);
          }
          const frame: RunEvent = { id: seq, event: name, data };
          handlers.onEvent?.(frame);
          switch (name) {
            case "run.started": handlers.onStarted?.(data); break;
            case "token": handlers.onToken?.(str(data.delta)); break;
            case "tool.status": handlers.onToolStatus?.(data); break;
            case "plan.updated": handlers.onPlan?.(data.plan); break;
            case "approval.required": handlers.onApprovalRequired?.(data); break;
            case "artifact.created": handlers.onArtifact?.(data); break;
            case "approval.timed_out": handlers.onApprovalTimedOut?.(data); break;
            case "run.done": {
              const d = data as unknown as RunDoneData;
              handlers.onDone?.(d);
              terminal = { status: d.status === "stopped" ? "stopped" : "succeeded",
                lastEventId: lastId, reconnects };
              break;
            }
            case "run.error": {
              const d = data as unknown as RunErrorData;
              handlers.onError?.(d);
              if (str(d.code) === "resume_expired") {
                const detail = await this.get(runId);
                handlers.onFallback?.(detail);
                terminal = { status: "resume_expired", lastEventId: lastId, reconnects, detail };
              } else {
                terminal = { status: "failed", lastEventId: lastId, reconnects,
                  error: { code: str(d.code, "internal_error"), message: str(d.message),
                    requestId: str(d.request_id) || undefined } };
              }
              break;
            }
            default: break; // 只增不删：未知事件已由 onEvent 交出，不在此打断
          }
          if (terminal) break;
        }
      } catch (e) {
        if (signal?.aborted) return cancelled();
        if (!retryable(e)) throw e;
        broken = e;
      }
      if (terminal) return terminal;
      if (signal?.aborted) return cancelled();
      if (reconnects >= maxReconnects) {
        throw new WenquError(`事件流在终态前断开，重连 ${reconnects} 次仍未完成：run=${runId}`);
      }
      await delay(baseDelay * (reconnects + 1));
      reconnects++;
      handlers.onReconnect?.({ attempt: reconnects, lastEventId: lastId,
        reason: broken instanceof Error ? broken.message : "流在终态前结束" });
    }
  }
}

function delay(ms: number): Promise<void> {
  return ms <= 0 ? Promise.resolve() : new Promise((r) => setTimeout(r, ms));
}
