/**
 * SDK 入口客户端：负责构造校验、fetch 封装、鉴权头注入与错误映射。
 * 各能力模块（chat/embeddings/retrieval/kb/workflow）通过本类的内部请求方法发请求。
 */

import type { PlatformEnvelope } from "./types.js";
import { WenquApiError, WenquError, errorFromResponse } from "./errors.js";
import { Chat, Responses } from "./chat.js";
import { Embeddings, Models } from "./embeddings.js";
import { Retrieval } from "./retrieval.js";
import { Kbs } from "./kb.js";
import { Workflows } from "./workflow.js";

export interface WenquClientOptions {
  /**
   * 服务根地址，形如 `http://host:8090/ai`。
   * 后端 context-path 是 /ai，SDK 不自动补——传什么用什么，所有请求 = baseUrl + /api/ai/v1/**。
   */
  baseUrl: string;
  /** API Key（sk-xxx），以 X-Api-Key 请求头发送 */
  apiKey: string;
  /** 自定义 fetch 实现（缺省用全局 fetch；Node ≥18 与浏览器均有） */
  fetch?: typeof globalThis.fetch;
}

export class WenquClient {
  /** 归一化后的根地址（去尾部斜杠） */
  readonly baseUrl: string;

  readonly chat: Chat;
  readonly responses: Responses;
  readonly embeddings: Embeddings;
  readonly models: Models;
  readonly retrieval: Retrieval;
  readonly kbs: Kbs;
  readonly workflows: Workflows;

  private readonly apiKey: string;
  private readonly fetchImpl: typeof globalThis.fetch;

  constructor(options: WenquClientOptions) {
    if (!options || typeof options.baseUrl !== "string" || options.baseUrl.trim() === "") {
      throw new WenquError("baseUrl 必填，形如 http://host:8090/ai（含后端 context-path，SDK 不自动补 /ai）");
    }
    if (typeof options.apiKey !== "string" || options.apiKey.trim() === "") {
      throw new WenquError("apiKey 必填（sk-xxx，在问渠 API Key 管理页签发）");
    }
    const impl = options.fetch ?? globalThis.fetch;
    if (typeof impl !== "function") {
      throw new WenquError("当前环境没有全局 fetch（Node <18），请通过 options.fetch 传入实现");
    }
    this.baseUrl = options.baseUrl.replace(/\/+$/, "");
    this.apiKey = options.apiKey;
    this.fetchImpl = impl;

    this.chat = new Chat(this);
    this.responses = new Responses(this);
    this.embeddings = new Embeddings(this);
    this.models = new Models(this);
    this.retrieval = new Retrieval(this);
    this.kbs = new Kbs(this);
    this.workflows = new Workflows(this);
  }

  /**
   * 内部：发送请求并返回原始 Response。鉴权头统一在此注入；非 2xx 抛映射后的错误。
   * 流式（SSE）与 multipart 场景直接用本方法。
   */
  async rawRequest(path: string, init: RequestInit = {}): Promise<Response> {
    const headers = new Headers(init.headers);
    headers.set("X-Api-Key", this.apiKey);
    let res: Response;
    try {
      res = await this.fetchImpl(this.baseUrl + path, { ...init, headers });
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
    const body = (await res.json()) as PlatformEnvelope<T>;
    if (body && body.success === false) {
      throw new WenquApiError(body.msg || "请求失败");
    }
    return body.data as T;
  }
}

/** body 为字符串（JSON）且调用方未指定 Content-Type 时补 application/json；FormData 交给 fetch 自动带 boundary */
function withJsonContentType(init: RequestInit): RequestInit {
  if (typeof init.body !== "string") return init;
  const headers = new Headers(init.headers);
  if (!headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  return { ...init, headers };
}
