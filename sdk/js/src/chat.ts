/**
 * 问答能力（OpenAI 兼容）：
 * - client.chat.completions.create：chat/completions 端点（stream=true 返回 AsyncIterable）
 * - client.responses.create：Responses 端点（stream=true 返回事件 AsyncIterable）
 * 两者的 model 参数都是问渠智能体 id。
 */

import type { WenquClient } from "./client.js";
import { WenquApiError, WenquError } from "./errors.js";
import { sseEvents } from "./sse.js";
import type {
  ChatCompletion,
  ChatCompletionChunk,
  ChatCreateParams,
  ResponseCreateParams,
  ResponseStreamEvent,
  WenquResponse,
} from "./types.js";

const CHAT_COMPLETIONS_PATH = "/api/ai/v1/chat/completions";
const RESPONSES_PATH = "/api/ai/v1/responses";

/** chat/completions 请求体：只带调用方显式给的字段，includeUsage 映射到 stream_options */
function buildChatBody(params: ChatCreateParams): string {
  if (!params.model) throw new WenquError("chat.completions.create: model（智能体 id）必填");
  if (!params.messages || params.messages.length === 0) {
    throw new WenquError("chat.completions.create: messages 不能为空");
  }
  const body: Record<string, unknown> = { model: params.model, messages: params.messages };
  if (params.tools) body.tools = params.tools;
  if (params.stream) {
    body.stream = true;
    if (params.includeUsage) body.stream_options = { include_usage: true };
  }
  return JSON.stringify(body);
}

function buildResponsesBody(params: ResponseCreateParams): string {
  if (!params.model) throw new WenquError("responses.create: model（智能体 id）必填");
  if (params.input == null || params.input === "") throw new WenquError("responses.create: input 不能为空");
  const body: Record<string, unknown> = { model: params.model, input: params.input };
  if (params.instructions != null) body.instructions = params.instructions;
  if (params.stream) body.stream = true;
  return JSON.stringify(body);
}

/** SSE 流式请求的公共前置：带 Accept: text/event-stream 发起 POST，校验响应体存在 */
async function openStream(client: WenquClient, path: string, body: string): Promise<ReadableStream<Uint8Array>> {
  const res = await client.rawRequest(path, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
    body,
  });
  if (!res.body) throw new WenquError("流式响应没有可读 body（当前环境可能不支持 ReadableStream）");
  return res.body;
}

export class ChatCompletions {
  constructor(private readonly client: WenquClient) {}

  /** 非流式：一次性返回 ChatCompletion */
  create(params: ChatCreateParams & { stream?: false }): Promise<ChatCompletion>;
  /** 流式：返回 AsyncIterable<ChatCompletionChunk>，可直接 for await 消费 */
  create(params: ChatCreateParams & { stream: true }): AsyncIterable<ChatCompletionChunk>;
  create(params: ChatCreateParams): Promise<ChatCompletion> | AsyncIterable<ChatCompletionChunk> {
    if (params.stream === true) return this.createStream(params);
    // 走 async 方法：参数校验失败以 rejected Promise 呈现，与网络错误同一通道
    return this.createOnce(params);
  }

  private async createOnce(params: ChatCreateParams): Promise<ChatCompletion> {
    return this.client.jsonRequest<ChatCompletion>(CHAT_COMPLETIONS_PATH, {
      method: "POST",
      body: buildChatBody(params),
    });
  }

  private async *createStream(params: ChatCreateParams): AsyncGenerator<ChatCompletionChunk> {
    const body = await openStream(this.client, CHAT_COMPLETIONS_PATH, buildChatBody(params));
    for await (const ev of sseEvents(body)) {
      if (ev.data === "[DONE]") return;
      let chunk: ChatCompletionChunk;
      try {
        chunk = JSON.parse(ev.data) as ChatCompletionChunk;
      } catch {
        throw new WenquError(`流式帧 JSON 解析失败：${ev.data.slice(0, 200)}`);
      }
      // 流中错误帧（HTTP 200 后后端以 error 字段收尾）：转成异常抛出，不透传给调用方
      if (chunk.error) {
        throw new WenquApiError(chunk.error.message ?? "流式调用失败", { type: chunk.error.type });
      }
      yield chunk;
    }
  }
}

export class Responses {
  constructor(private readonly client: WenquClient) {}

  /** 非流式：一次性返回 WenquResponse */
  create(params: ResponseCreateParams & { stream?: false }): Promise<WenquResponse>;
  /** 流式：返回事件 AsyncIterable（response.created / response.output_text.delta / response.completed / response.failed） */
  create(params: ResponseCreateParams & { stream: true }): AsyncIterable<ResponseStreamEvent>;
  create(params: ResponseCreateParams): Promise<WenquResponse> | AsyncIterable<ResponseStreamEvent> {
    if (params.stream === true) return this.createStream(params);
    // 走 async 方法：参数校验失败以 rejected Promise 呈现，与网络错误同一通道
    return this.createOnce(params);
  }

  private async createOnce(params: ResponseCreateParams): Promise<WenquResponse> {
    return this.client.jsonRequest<WenquResponse>(RESPONSES_PATH, {
      method: "POST",
      body: buildResponsesBody(params),
    });
  }

  private async *createStream(params: ResponseCreateParams): AsyncGenerator<ResponseStreamEvent> {
    const body = await openStream(this.client, RESPONSES_PATH, buildResponsesBody(params));
    for await (const ev of sseEvents(body)) {
      if (ev.data === "[DONE]") return; // responses 流以 response.completed 收尾，兼容服务端补发 [DONE]
      let event: ResponseStreamEvent;
      try {
        event = JSON.parse(ev.data) as ResponseStreamEvent;
      } catch {
        throw new WenquError(`流式事件 JSON 解析失败：${ev.data.slice(0, 200)}`);
      }
      yield event;
    }
  }
}

/** client.chat 命名空间：目前挂 completions，与 OpenAI SDK 的调用形态对齐 */
export class Chat {
  readonly completions: ChatCompletions;

  constructor(client: WenquClient) {
    this.completions = new ChatCompletions(client);
  }
}
