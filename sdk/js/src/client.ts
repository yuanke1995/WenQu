/**
 * SDK 入口客户端：负责构造校验、fetch 封装、鉴权头注入与错误映射。
 * 各能力模块（chat/embeddings/retrieval/kbs/workflow/native）通过本类的内部请求方法发请求。
 */

import type { PlatformEnvelope } from "./types.js";
import { WenquApiError, WenquError, errorFromResponse } from "./errors.js";
import { Chat, Responses } from "./chat.js";
import { Embeddings, Models } from "./embeddings.js";
import { Retrieval } from "./retrieval.js";
import { Kbs } from "./kb.js";
import { Workflows } from "./workflow.js";
import { NativeChat } from "./native.js";

export interface WenquClientOptions {
  /**
   * 服务根地址，形如 `http://host:8090/ai`。
   * 后端 context-path 是 /ai，SDK 不自动补——传什么用什么，所有请求 = baseUrl + /api/ai/**。
   */
  baseUrl: string;
  /**
   * API Key（sk-xxx），以 X-Api-Key 请求头发送。
   * 直连问渠时必填；<b>经自有后端代理</b>（代理持 Key、nativeRoot 指到代理路径）时可省略——
   * 浏览器不该拿到 Key，代理自身的登录态用 headers 注入。
   */
  apiKey?: string;
  /**
   * 原生问答协议（深度集成：人在回路卡片/产物交付/断线接流）的端点根。
   * 缺省 = baseUrl + /api/ai；嵌入方持 Key 的代理把 /api/ai/** 原样转发时，
   * 这里填代理自己的前缀（如 https://业务域名/api/copilot/q）。
   */
  nativeRoot?: string;
  /**
   * 附加请求头（每次请求求值一次）：代理自身的登录态（如 X-Token）在这里注入。
   * 用函数形态是因为令牌会刷新——固定字典在过期后不会更新。
   */
  headers?: Record<string, string> | (() => Record<string, string> | Promise<Record<string, string>>);
  /** 自定义 fetch 实现（缺省用全局 fetch；Node ≥18 与浏览器均有） */
  fetch?: typeof globalThis.fetch;
}

export class WenquClient {
  /** 归一化后的根地址（去尾部斜杠） */
  readonly baseUrl: string;
  /** 原生问答端点根（去尾部斜杠）：直连 = baseUrl + /api/ai，代理形态 = 代理前缀 */
  readonly nativeRoot: string;

  readonly chat: Chat;
  readonly responses: Responses;
  readonly embeddings: Embeddings;
  readonly models: Models;
  readonly retrieval: Retrieval;
  readonly kbs: Kbs;
  readonly workflows: Workflows;
  /** 原生问答运行时（深度集成）：send/resume/steer/stop + 人在回路卡片 + 附件与历史 */
  readonly native: NativeChat;

  private readonly apiKey: string;
  private readonly extraHeaders: WenquClientOptions["headers"];
  private readonly fetchImpl: typeof globalThis.fetch;

  constructor(options: WenquClientOptions) {
    if (!options || typeof options.baseUrl !== "string" || options.baseUrl.trim() === "") {
      throw new WenquError("baseUrl 必填，形如 http://host:8090/ai（含后端 context-path，SDK 不自动补 /ai）");
    }
    if (options.apiKey != null && typeof options.apiKey !== "string") {
      throw new WenquError("apiKey 传值时必须是字符串（sk-xxx，在问渠 API Key 管理页签发）");
    }
    const impl = options.fetch ?? ((...args: Parameters<typeof globalThis.fetch>) => globalThis.fetch(...args));
    // 默认实现必须包一层箭头函数而不是直接存 globalThis.fetch 引用：
    // 浏览器的 fetch 以脱离 window 的 this 调用会抛 Illegal invocation（Node 的 undici 不检查，测不出）
    if (typeof impl !== "function") {
      throw new WenquError("当前环境没有全局 fetch（Node <18），请通过 options.fetch 传入实现");
    }
    this.baseUrl = options.baseUrl.replace(/\/+$/, "");
    this.nativeRoot = (options.nativeRoot ?? `${this.baseUrl}/api/ai`).replace(/\/+$/, "");
    this.apiKey = options.apiKey?.trim() ?? "";
    this.extraHeaders = options.headers;
    this.fetchImpl = impl;

    this.chat = new Chat(this);
    this.responses = new Responses(this);
    this.embeddings = new Embeddings(this);
    this.models = new Models(this);
    this.retrieval = new Retrieval(this);
    this.kbs = new Kbs(this);
    this.workflows = new Workflows(this);
    this.native = new NativeChat(this);
  }

  /** 内部：装配本次请求的附加头（代理登录态等，函数形态每次现取——令牌会刷新） */
  async resolveExtraHeaders(): Promise<Record<string, string>> {
    if (!this.extraHeaders) return {};
    return typeof this.extraHeaders === "function" ? await this.extraHeaders() : { ...this.extraHeaders };
  }

  /**
   * 内部：发送请求并返回原始 Response。鉴权头统一在此注入；非 2xx 抛映射后的错误。
   * 流式（SSE）与 multipart 场景直接用本方法。
   */
  async rawRequest(path: string, init: RequestInit = {}): Promise<Response> {
    return this.sendRequest(this.baseUrl + path, init);
  }

  /**
   * 内部：原生问答端点请求（挂在 nativeRoot 下）。与 rawRequest 同一套头装配；
   * 未配 apiKey 的代理形态不带 X-Api-Key（Key 在代理那一侧）。
   */
  async nativeRequest(subPath: string, init: RequestInit = {}): Promise<Response> {
    return this.sendRequest(`${this.nativeRoot}/${subPath.replace(/^\/+/, "")}`, init);
  }

  private async sendRequest(url: string, init: RequestInit): Promise<Response> {
    const headers = new Headers(init.headers);
    for (const [k, v] of Object.entries(await this.resolveExtraHeaders())) headers.set(k, v);
    if (this.apiKey) headers.set("X-Api-Key", this.apiKey);
    let res: Response;
    try {
      res = await this.fetchImpl(url, { ...init, headers });
    } catch (e) {
      throw new WenquError(`网络请求失败：${e instanceof Error ? e.message : String(e)}`);
    }
    if (!res.ok) throw await errorFromResponse(res);
    return res;
  }

  /** 内部：JSON 请求，返回解析后的响应体（OpenAI 兼容端点，无包络） */
  async jsonRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
    const res = await this.rawRequest(path, withJsonContentType(init));
    return (await res.json()) as T;
  }

  /** 内部：平台包络请求，返回解包后的 data（HTTP 2xx 但 success=false 同样抛错） */
  async envelopeRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
    const res = await this.rawRequest(path, withJsonContentType(init));
    return unwrapEnvelope<T>(res);
  }

  /** 内部：原生端点的平台包络请求（{success,data,msg} 形态，与网页端点同一契约） */
  async nativeEnvelopeRequest<T>(subPath: string, init: RequestInit = {}): Promise<T> {
    const res = await this.nativeRequest(subPath, withJsonContentType(init));
    return unwrapEnvelope<T>(res);
  }
}

/** 包络解包：success=false 抛错（msg 优先），成功回 data */
async function unwrapEnvelope<T>(res: Response): Promise<T> {
  const body = (await res.json()) as PlatformEnvelope<T>;
  if (body && body.success === false) {
    throw new WenquApiError(body.msg || "请求失败");
  }
  return body.data as T;
}

/** body 为字符串（JSON）且调用方未指定 Content-Type 时补 application/json；FormData 交给 fetch 自动带 boundary */
function withJsonContentType(init: RequestInit): RequestInit {
  if (typeof init.body !== "string") return init;
  const headers = new Headers(init.headers);
  if (!headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  return { ...init, headers };
}
