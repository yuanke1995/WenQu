/**
 * 原生问答运行时（深度集成）：client.native.*
 *
 * 覆盖问渠原生 /api/ai/chat 事件协议——这是「把智能体嵌进自己业务系统」的完整形态：
 * 流式正文与思考、工具执行过程、产物实时交付、人在回路三张卡（提问/计划/审批）、
 * 断线接流（snapshot + 增量）、运行中插话（steer）、服务端停止（stop）、附件上传、会话历史。
 * OpenAI 兼容端点（chat.completions）是无状态标准面，装不下这些交互——业务助手类集成用本模块。
 *
 * 两种部署形态：
 * - 直连问渠：new WenquClient({ baseUrl, apiKey })——Key 在调用方进程（Node/服务端脚本）；
 * - 经自有后端代理：new WenquClient({ baseUrl, nativeRoot: 'https://业务域名/api/copilot/q',
 *   headers: () => ({ 'X-Token': 登录态 }) })——Key 恒在代理侧，浏览器不见凭据。
 */

import type { WenquClient } from "./client.js";
import { WenquError } from "./errors.js";
import { sseEvents } from "./sse.js";
import type {
  ArtifactEvent,
  AskUserCard,
  ApprovalCard,
  DonePayload,
  NativeFrame,
  NativeSendParams,
  NativeStreamHandlers,
  PlanApprovalCard,
  SnapshotPayload,
  ToolStatusEvent,
  UploadAttachmentResult,
} from "./types.js";

/** content 常见「字符串套 JSON」双重序列化（done/tool_status/artifact 等结构化载荷）：能 parse 就 parse */
function deepParse(content: unknown): unknown {
  if (typeof content !== "string") return content;
  const s = content.trim();
  if (!s || (s.charAt(0) !== "{" && s.charAt(0) !== "[")) return content;
  try {
    return JSON.parse(s);
  } catch {
    return content;
  }
}

/** tool_status 的人话一行：label → name → hint（与网页端同一取法） */
function statusLine(c: unknown): string {
  if (typeof c === "string") return c;
  if (c && typeof c === "object") {
    const o = c as Record<string, unknown>;
    return String(o.label ?? o.name ?? o.hint ?? "");
  }
  return "";
}

/** 一次流式轮次的句柄：abort 断本地流（服务端按断线宽限后台续跑）；停止整轮用 stop() */
export interface NativeTurnHandle {
  abort(): void;
}

export class NativeChat {
  constructor(private readonly client: WenquClient) {}

  /**
   * 发起一轮对话（POST /chat，SSE 全程消费）。
   * 终止帧：done（onDone 带引用/产物/用量全量）或 error（onError）；
   * ask_user / plan_approval / approval_required 不是终态——服务端挂着这一轮等人工裁决，
   * 流继续开着，答完卡（answerAsk/resolvePlan/resolveApproval）后同一根流长出后续内容。
   */
  send(params: NativeSendParams, handlers: NativeStreamHandlers = {}): NativeTurnHandle {
    if (!params || !params.question || !params.question.trim()) {
      handlers.onError?.("question 不能为空");
      return { abort() {} };
    }
    const body: Record<string, unknown> = { question: params.question };
    for (const k of [
      "sessionId", "agentId", "model", "images", "attachments", "mentions", "historyRefs",
      "skills", "planBaseId", "reasoningLevel", "replaceMessageId", "editMessageId", "variables",
    ] as const) {
      const v = params[k];
      if (v != null) body[k] = v;
    }
    if (params.deepThink) body.deepThink = true;
    if (params.planMode) body.planMode = true;
    if (params.regenerate) body.regenerate = true;
    if (params.fromSteer) body.fromSteer = true;
    return this.stream("chat", { method: "POST", body: JSON.stringify(body) }, handlers);
  }

  /**
   * 断线接流（GET /chat/resume）：先收 snapshot（已产出正文/卡片/时间线），此后接增量。
   * 没在跑的轮回 snapshot{running:false} 并关闭——调用方按历史回显即可。
   */
  resume(sessionId: string, handlers: NativeStreamHandlers = {}): NativeTurnHandle {
    if (!sessionId) {
      handlers.onError?.("resume: sessionId 必填");
      return { abort() {} };
    }
    return this.stream(
      `chat/resume?sessionId=${encodeURIComponent(sessionId)}`,
      { method: "GET" },
      handlers,
    );
  }

  /** 这一会话是否还有一轮在跑（刷新接流前先探测） */
  runStatus(sessionId: string): Promise<Record<string, unknown>> {
    return this.client.nativeEnvelopeRequest<Record<string, unknown>>(
      `chat/run?sessionId=${encodeURIComponent(sessionId)}`,
    );
  }

  /** 服务端停止整轮（生成中/工具中/等裁决都能停，并解开人工等待）；与 abort 的「断流」不同 */
  stop(sessionId: string): Promise<{ stopped: boolean }> {
    return this.client.nativeEnvelopeRequest<{ stopped: boolean }>("chat/stop", {
      method: "POST",
      body: JSON.stringify({ sessionId }),
    });
  }

  /** 运行中插话：这句话在下一次模型调用前送进正在跑的这一轮。accepted=false 表示轮已收尾，把话放回输入框 */
  steer(sessionId: string, text: string): Promise<{ accepted: boolean; messageId: string }> {
    return this.client.nativeEnvelopeRequest("chat/steer", {
      method: "POST",
      body: JSON.stringify({ sessionId, text }),
    });
  }

  /** 上传问答附件换 fileId（发送轮只带引用不内联内容） */
  async uploadAttachment(file: Blob | Uint8Array | ArrayBuffer | string, filename?: string): Promise<UploadAttachmentResult> {
    const form = new FormData();
    const blob = file instanceof Blob ? file
      : file instanceof Uint8Array ? new Blob([file])
      : file instanceof ArrayBuffer ? new Blob([new Uint8Array(file)])
      : new Blob([file]);
    const name = filename ?? (typeof File !== "undefined" && file instanceof File ? file.name : undefined);
    if (!name) throw new WenquError("uploadAttachment: 非 File 输入必须提供 filename");
    form.append("file", blob, name);
    const res = await this.client.nativeRequest("chat/attachment", { method: "POST", body: form });
    const body = (await res.json()) as { success?: boolean; msg?: string; data?: UploadAttachmentResult };
    if (body && body.success === false) throw new WenquError(body.msg || "上传失败");
    return body.data as UploadAttachmentResult;
  }

  /** 会话历史（消息行数组或 {messages:[...]}，按形态归一为数组） */
  async history(sessionId: string): Promise<Array<Record<string, unknown>>> {
    const data = await this.client.nativeEnvelopeRequest<unknown>(`session/${encodeURIComponent(sessionId)}`);
    if (Array.isArray(data)) return data as Array<Record<string, unknown>>;
    const m = (data as { messages?: unknown } | null)?.messages;
    if (Array.isArray(m)) return m as Array<Record<string, unknown>>;
    throw new WenquError("history: 返回形态不认识");
  }

  /** 会话过程账本（结构性事件；turn 传轮次 id 只看那一轮） */
  sessionEvents(sessionId: string, turn?: string): Promise<Record<string, unknown>> {
    const q = turn ? `?turn=${encodeURIComponent(turn)}` : "";
    return this.client.nativeEnvelopeRequest<Record<string, unknown>>(
      `session/${encodeURIComponent(sessionId)}/events${q}`,
    );
  }

  // ==================== 人在回路三张卡 ====================

  /** 回答智能体提问（answers 与问题下标对齐；ignore=true 不作答——超时与忽略都不替用户选） */
  answerAsk(askId: string, answers?: string[], ignore = false): Promise<unknown> {
    return this.client.nativeEnvelopeRequest(`ask-user/${encodeURIComponent(askId)}`, {
      method: "POST",
      body: JSON.stringify(ignore ? { ignore: true } : { answers: answers ?? [] }),
    });
  }

  /** 待答提问卡恢复（刷新/换设备后重建卡片；live=false 表示唤醒句柄已不在） */
  async pendingAsks(sessionId: string): Promise<Array<AskUserCard & { live?: boolean; expired?: boolean }>> {
    const data = await this.client.nativeEnvelopeRequest<{ items?: unknown }>(
      `ask-user/pending?sessionId=${encodeURIComponent(sessionId)}`,
    );
    return Array.isArray(data?.items) ? (data.items as Array<AskUserCard & { live?: boolean; expired?: boolean }>) : [];
  }

  /**
   * 裁决执行计划：批准 {approved:true}；退回重出 {approved:false,revise:true,plan:"修改意见"}；
   * 作废继续对话 {supersede:true,plan:"意见"}；拒绝 {approved:false}＝本轮终止。
   */
  resolvePlan(planApprovalId: string, decision: { approved?: boolean; revise?: boolean; supersede?: boolean; plan?: string }): Promise<unknown> {
    return this.client.nativeEnvelopeRequest(`plan-approval/${encodeURIComponent(planApprovalId)}`, {
      method: "POST",
      body: JSON.stringify(decision),
    });
  }

  /** 待批准计划恢复（含版本链） */
  async pendingPlans(sessionId: string): Promise<Array<PlanApprovalCard & { live?: boolean; expired?: boolean }>> {
    const data = await this.client.nativeEnvelopeRequest<{ items?: unknown }>(
      `plan-approval/pending?sessionId=${encodeURIComponent(sessionId)}`,
    );
    return Array.isArray(data?.items) ? (data.items as Array<PlanApprovalCard & { live?: boolean; expired?: boolean }>) : [];
  }

  /** 裁决工具执行（拒绝/超时后模型收到未执行错误并继续回答） */
  resolveApproval(approvalId: string, approved: boolean, reason?: string): Promise<unknown> {
    return this.client.nativeEnvelopeRequest(`tool-approval/${encodeURIComponent(approvalId)}`, {
      method: "POST",
      body: JSON.stringify(reason ? { approved, reason } : { approved }),
    });
  }

  /** 待裁决工具审批恢复 */
  async pendingApprovals(sessionId: string): Promise<Array<ApprovalCard & { live?: boolean }>> {
    const data = await this.client.nativeEnvelopeRequest<{ items?: unknown }>(
      `tool-approval/pending?sessionId=${encodeURIComponent(sessionId)}`,
    );
    return Array.isArray(data?.items) ? (data.items as Array<ApprovalCard & { live?: boolean }>) : [];
  }

  /** 工具大输出整份读回（tool_output 事件里的 spillId） */
  toolOutput(spillId: string): Promise<Record<string, unknown>> {
    return this.client.nativeEnvelopeRequest<Record<string, unknown>>(`tool-output/${encodeURIComponent(spillId)}`);
  }

  // ==================== SSE 消费 ====================

  /**
   * 打开一条原生 SSE 流并按事件分发。代理层把非 SSE 响应（未配置/登录过期/上游 502）
   * 原样透传时按 Content-Type 分流——不能把 JSON 信封当流读出「流已断开」的冤案。
   */
  private stream(subPath: string, init: RequestInit, handlers: NativeStreamHandlers): NativeTurnHandle {
    const controller = new AbortController();
    let settled = false;
    // 终局只触发一次：done/error/abort/快照即终局互斥。settled 无条件置位（调用方没挂对应回调
    // 也算收口——否则 done 之后流自然结束会误报「未收到 done」），回调存在才调
    const finish = (fn: unknown, arg?: unknown): void => {
      if (settled) return;
      settled = true;
      if (typeof fn === "function") (fn as (a?: unknown) => void)(arg);
    };

    const run = async (): Promise<void> => {
      let res: Response;
      try {
        res = await this.client.nativeRequest(subPath, {
          ...init,
          headers: { Accept: "text/event-stream", ...(init.headers ?? {}) },
          signal: controller.signal,
        });
      } catch (e) {
        // abort 可能落在 fetch 发起前/中：rawRequest 会把 AbortError 包成网络错误，
        // 以 signal 为准判「调用方主动断流」，不误报成连不上
        if (controller.signal.aborted) {
          finish(handlers.onAbort);
          return;
        }
        // rawRequest 对非 2xx 抛映射错误：错误体两种形状都能解出 message
        finish(handlers.onError, e instanceof Error ? e.message : String(e));
        return;
      }
      const ct = ((res.headers.get("content-type") ?? "") + "").toLowerCase();
      if (ct.indexOf("text/event-stream") < 0 || !res.body) {
        // 非 SSE：JSON 信封错误（代理未配置/登录过期等），如实报给 onError
        let msg = `请求失败 ${res.status}`;
        try {
          const d = (await res.json()) as { msg?: string; message?: string; error?: { message?: string } };
          msg = d.msg || d.message || d.error?.message || msg;
        } catch {
          // 拿不到信封就用状态码文案
        }
        finish(handlers.onError, msg);
        return;
      }
      try {
        for await (const ev of sseEvents(res.body)) {
          if (settled) return;
          let frame: NativeFrame;
          try {
            frame = JSON.parse(ev.data) as NativeFrame;
          } catch {
            continue; // 非 JSON 帧（心跳注释等）静默跳过
          }
          if (!frame || !frame.type) continue;
          handlers.onEvent?.(frame);
          const parsed = deepParse(frame.content);
          switch (frame.type) {
            case "token":
              handlers.onText?.(typeof frame.content === "string" ? frame.content : "");
              break;
            case "thinking":
              handlers.onThinking?.(typeof frame.content === "string" ? frame.content : "");
              break;
            case "tool_status": {
              const evt = parsed as ToolStatusEvent;
              handlers.onTool?.(evt);
              handlers.onStatus?.(statusLine(evt));
              break;
            }
            case "artifact": {
              const a = parsed as ArtifactEvent;
              if (a && typeof a === "object" && a.url) handlers.onArtifact?.(a);
              break;
            }
            case "ask_user":
              handlers.onAskUser?.(parsed as AskUserCard);
              break;
            case "approval_required":
              handlers.onApprovalRequired?.(parsed as ApprovalCard);
              break;
            case "plan":
              handlers.onPlan?.(parsed);
              break;
            case "plan_delta":
              handlers.onPlanDelta?.(typeof frame.content === "string" ? frame.content : "");
              break;
            case "plan_approval":
              handlers.onPlanApproval?.(parsed as PlanApprovalCard);
              break;
            case "plan_cancelled":
            case "plan_superseded":
              handlers.onPlanTerminal?.(frame.type, parsed);
              break;
            case "todo":
              handlers.onTodo?.(parsed);
              break;
            case "retrieved":
              handlers.onRetrieved?.(parsed);
              break;
            case "usage":
              handlers.onUsage?.(parsed);
              break;
            case "warn":
              handlers.onWarn?.(typeof frame.content === "string" ? frame.content : "");
              break;
            case "image":
              handlers.onImage?.(parsed);
              break;
            case "goal_state":
              handlers.onGoalState?.(parsed);
              break;
            case "snapshot": {
              const snap = parsed as SnapshotPayload;
              handlers.onSnapshot?.(snap ?? { running: false });
              if (!snap || snap.running !== true) {
                // 没在跑的接流：快照即终局，按正常收尾处理（done 不会再来）
                finish(handlers.onDone, { snapshotOnly: true } as unknown as DonePayload);
                return;
              }
              break;
            }
            case "done": {
              // 帧上的 sessionId 合进载荷（顶层没有才补）：done 载荷本体不带会话字段，
              // 而调用方普遍要按帧里的 id 更新自己的会话指针——不让各家自己从 onEvent 里捞
              const payload = (parsed ?? {}) as DonePayload;
              if (payload.sessionId === undefined && frame.sessionId) payload.sessionId = frame.sessionId;
              finish(handlers.onDone, payload);
              return;
            }
            case "error":
              finish(handlers.onError, typeof frame.content === "string" ? frame.content : "回答失败");
              return;
            default:
              // thinking_done/tool_output/runcode_progress/subagent_step/agent_* /compact_state/
              // steer/turn_start/goal_turn 等新事件经 onEvent 透出，SDK 不拦（契约只增不删）
              break;
          }
        }
        // 流自然结束但没收到 done/error：结果不确定，如实说
        finish(handlers.onError, "流已断开但未收到 done，这一轮结果不确定");
      } catch (e) {
        if (controller.signal.aborted || (e instanceof Error && e.name === "AbortError")) {
          finish(handlers.onAbort);
          return;
        }
        finish(handlers.onError, `连不上问渠：${e instanceof Error ? e.message : String(e)}`);
      }
    };
    void run();
    return {
      abort() {
        controller.abort();
      },
    };
  }
}
