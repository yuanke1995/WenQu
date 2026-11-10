/**
 * 本地 mock 后端（node:http）：按真实契约实现各端点的成功样例，
 * 外加 OpenAI 风格错误、平台包络错误、429 与 SSE 流样例，供 SDK 测试自起自停。
 */

import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";

/** 测试用 API Key（SDK 构造时传同一个） */
export const MOCK_API_KEY = "sk-test";

export interface MockServer {
  /** 含 /ai 前缀的根地址（验证 SDK 不自动补 context-path） */
  baseUrl: string;
  close(): Promise<void>;
}

function readBody(req: IncomingMessage): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    req.on("data", (c) => chunks.push(Buffer.from(c)));
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}

function sendJson(res: ServerResponse, status: number, obj: unknown): void {
  const text = JSON.stringify(obj);
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  res.end(text);
}

/** OpenAI 风格错误体 */
function openAiError(res: ServerResponse, status: number, type: string, message: string): void {
  sendJson(res, status, { error: { message, type } });
}

/** 平台包络错误体 */
function envelopeError(res: ServerResponse, status: number, msg: string): void {
  sendJson(res, status, { success: false, msg });
}

/** 平台包络成功体 */
function envelopeOk(res: ServerResponse, data: unknown): void {
  sendJson(res, 200, { success: true, data });
}

function sseHead(res: ServerResponse): void {
  res.writeHead(200, {
    "Content-Type": "text/event-stream",
    "Cache-Control": "no-cache",
    Connection: "keep-alive",
  });
}

function sseData(res: ServerResponse, payload: unknown): void {
  res.write(`data: ${JSON.stringify(payload)}\n\n`);
}

function sseEvent(res: ServerResponse, event: string, payload: unknown): void {
  res.write(`event: ${event}\ndata: ${JSON.stringify(payload)}\n\n`);
}

const KB1 = {
  id: "kb-1",
  name: "产品知识库",
  description: "问渠产品资料",
  embeddingRef: "p1/m1",
  docCount: 3,
  isDefault: false,
  createTime: "2026-01-01 10:00:00",
  updateTime: "2026-06-01 12:00:00",
};

const DOC1 = {
  id: "doc-1",
  kbId: "kb-1",
  fileName: "手册.docx",
  fileType: "docx",
  status: 0,
  statusText: "生效",
  chunkCount: 12,
  parseProgress: 100,
  parseDesc: null,
  failReason: null,
  fileSize: 20480,
  description: "产品手册",
  tags: ["官方"],
  version: 1,
  createTime: "2026-01-02 09:00:00",
  updateTime: "2026-01-02 09:05:00",
};

function runObject(inputs: Record<string, unknown> | undefined): Record<string, unknown> {
  if (inputs && "fail" in inputs) {
    return {
      id: "run-1",
      status: "failed",
      outputs: null,
      nodeTraces: [{ nodeId: "llm-1", status: "failed", error: "模型调用失败" }],
      durationMs: 80,
      error: "LLM 节点执行失败",
    };
  }
  return {
    id: "run-1",
    status: "success",
    outputs: { answer: "已完成" },
    nodeTraces: [{ nodeId: "start", status: "success" }, { nodeId: "end", status: "success" }],
    durationMs: 120,
    error: null,
  };
}

async function route(req: IncomingMessage, res: ServerResponse): Promise<void> {
  const path = new URL(req.url ?? "/", "http://localhost").pathname;
  const method = req.method ?? "GET";

  // 鉴权闸：所有 v1 端点校验 X-Api-Key
  if (req.headers["x-api-key"] !== MOCK_API_KEY) {
    openAiError(res, 401, "authentication_error", "API Key 无效或缺失");
    return;
  }

  // ---------- Chat Completions ----------
  if (path === "/ai/api/ai/v1/chat/completions" && method === "POST") {
    const body = JSON.parse((await readBody(req)).toString("utf8")) as {
      model?: string;
      stream?: boolean;
      stream_options?: { include_usage?: boolean };
    };
    if (body.model === "agent-404") {
      openAiError(res, 404, "not_found", "model 不存在或不可用");
      return;
    }
    if (body.model === "agent-400") {
      openAiError(res, 400, "invalid_request_error", "messages 不能为空");
      return;
    }
    if (body.model === "agent-429") {
      openAiError(res, 429, "rate_limit_error", "请求过于频繁，请稍后再试");
      return;
    }
    if (body.stream) {
      sseHead(res);
      const chunk = (delta: Record<string, unknown>, extra?: Record<string, unknown>): void => {
        sseData(res, {
          id: "chatcmpl-mock",
          object: "chat.completion.chunk",
          created: 1700000000,
          model: body.model,
          choices: [{ index: 0, delta, finish_reason: null }],
          ...extra,
        });
      };
      chunk({ role: "assistant" });
      chunk({ content: "你好" });
      chunk({ content: "，我是问渠" });
      chunk({});
      // OpenAI 语义：仅当请求带 stream_options.include_usage 时补 usage 尾帧（choices 为空）
      if (body.stream_options?.include_usage) {
        sseData(res, {
          id: "chatcmpl-mock",
          object: "chat.completion.chunk",
          created: 1700000000,
          model: body.model,
          choices: [],
          usage: { prompt_tokens: 5, completion_tokens: 7, total_tokens: 12 },
        });
      }
      res.write("data: [DONE]\n\n");
      res.end();
      return;
    }
    sendJson(res, 200, {
      id: "chatcmpl-mock",
      object: "chat.completion",
      created: 1700000000,
      model: body.model,
      choices: [
        { index: 0, message: { role: "assistant", content: "你好，我是问渠" }, finish_reason: "stop" },
      ],
      usage: { prompt_tokens: 5, completion_tokens: 7, total_tokens: 12 },
    });
    return;
  }

  // ---------- Responses ----------
  if (path === "/ai/api/ai/v1/responses" && method === "POST") {
    const body = JSON.parse((await readBody(req)).toString("utf8")) as { model?: string; stream?: boolean };
    if (body.model === "agent-400") {
      openAiError(res, 400, "invalid_request_error", "input 不能为空");
      return;
    }
    const responseObject = (status: string, text: string): Record<string, unknown> => ({
      id: "resp-mock",
      object: "response",
      created_at: 1700000000,
      model: body.model,
      status,
      output: [
        {
          type: "message",
          id: "resp-mock-msg",
          role: "assistant",
          status,
          content: [{ type: "output_text", text, annotations: [] }],
        },
      ],
      usage: { input_tokens: 5, output_tokens: 7, total_tokens: 12 },
    });
    if (body.stream) {
      sseHead(res);
      sseEvent(res, "response.created", {
        type: "response.created",
        response: responseObject("in_progress", ""),
      });
      sseEvent(res, "response.output_text.delta", {
        type: "response.output_text.delta",
        item_id: "resp-mock-msg",
        output_index: 0,
        content_index: 0,
        delta: "你好",
      });
      sseEvent(res, "response.output_text.delta", {
        type: "response.output_text.delta",
        item_id: "resp-mock-msg",
        output_index: 0,
        content_index: 0,
        delta: "，我是问渠",
      });
      sseEvent(res, "response.completed", {
        type: "response.completed",
        response: responseObject("completed", "你好，我是问渠"),
      });
      res.end();
      return;
    }
    sendJson(res, 200, responseObject("completed", "你好，我是问渠"));
    return;
  }

  // ---------- Embeddings ----------
  if (path === "/ai/api/ai/v1/embeddings" && method === "POST") {
    const body = JSON.parse((await readBody(req)).toString("utf8")) as {
      input?: string | string[];
      model?: string;
      encoding_format?: string;
    };
    if (!body.model) {
      openAiError(res, 400, "invalid_request_error", "model 必填，为向量模型引用 providerId/modelId");
      return;
    }
    if (body.model === "bad/ref") {
      openAiError(res, 400, "invalid_request_error", "model 引用无效：「bad/ref」");
      return;
    }
    const texts = typeof body.input === "string" ? [body.input] : body.input ?? [];
    const base64 = (body.encoding_format ?? "").toLowerCase() === "base64";
    sendJson(res, 200, {
      object: "list",
      data: texts.map((_t, i) => ({
        object: "embedding",
        index: i,
        embedding: base64 ? "AAAAAAAA8D8=" : [0.1, 0.2, 0.30000001192092896],
      })),
      model: body.model,
      usage: { prompt_tokens: texts.length, total_tokens: texts.length },
      dimension: 3,
    });
    return;
  }

  // ---------- 向量模型清单 ----------
  if (path === "/ai/api/ai/v1/models/embeddings" && method === "GET") {
    sendJson(res, 200, {
      object: "list",
      data: [
        {
          providerId: "p1",
          providerName: "供应商一",
          models: [{ modelId: "m1", ref: "p1/m1", dimension: 1024 }],
        },
        // 刻意缺 providerName：验证按存在性解析
        { providerId: "p2", models: [{ modelId: "m2", ref: "p2/m2" }] },
      ],
    });
    return;
  }

  // ---------- 知识检索 ----------
  if (path === "/ai/api/ai/v1/retrieval" && method === "POST") {
    const body = JSON.parse((await readBody(req)).toString("utf8")) as { query?: string };
    if (body.query === "fail") {
      envelopeError(res, 400, "指定的知识库均不存在或不可见");
      return;
    }
    if (body.query === "ratelimit") {
      envelopeError(res, 429, "请求过于频繁，请稍后再试");
      return;
    }
    envelopeOk(res, {
      chunks: [
        {
          id: "c1",
          text: "问渠是 AI 智能体工作台",
          score: 0.87,
          title: "文档A",
          titlePath: "第一章",
          docId: "doc-1",
          kbId: "kb-1",
          chunkIndex: 0,
          images: ["img1.png"],
        },
        {
          id: "c2",
          text: "第二块内容",
          score: 0.55,
          title: null,
          titlePath: null,
          docId: "doc-1",
          kbId: "kb-1",
          chunkIndex: 1,
          images: [],
        },
      ],
      total: 2,
      warnings: ["关键词检索失败，仅向量结果"],
    });
    return;
  }

  // ---------- 知识库管理 ----------
  if (path === "/ai/api/ai/v1/kbs" && method === "GET") {
    envelopeOk(res, { knowledgeBases: [KB1], total: 1 });
    return;
  }
  if (path === "/ai/api/ai/v1/kbs" && method === "POST") {
    const body = JSON.parse((await readBody(req)).toString("utf8")) as { name?: string; embeddingRef?: string };
    if (!body.name) {
      envelopeError(res, 400, "知识库名称不能为空");
      return;
    }
    envelopeOk(res, { ...KB1, id: "kb-new", name: body.name, embeddingRef: body.embeddingRef, docCount: 0 });
    return;
  }
  const kbDocMatch = /^\/ai\/api\/ai\/v1\/kbs\/([^/]+)\/documents$/.exec(path);
  if (kbDocMatch && method === "GET") {
    if (kbDocMatch[1] !== "kb-1") {
      envelopeError(res, 404, "知识库不存在");
      return;
    }
    envelopeOk(res, { documents: [DOC1], total: 1 });
    return;
  }
  if (kbDocMatch && method === "POST") {
    if (kbDocMatch[1] === "kb-forbidden") {
      envelopeError(res, 403, "知识库不存在或该身份不可向其上传文档");
      return;
    }
    const raw = await readBody(req);
    const text = raw.toString("utf8");
    const isMultipart = (req.headers["content-type"] ?? "").startsWith("multipart/form-data");
    if (!isMultipart || !text.includes('name="file"') || !text.includes("hello wenqu")) {
      envelopeError(res, 400, "multipart 缺少 file 字段");
      return;
    }
    const hasDescription = text.includes('name="description"');
    envelopeOk(res, {
      ...DOC1,
      id: "doc-2",
      fileName: text.match(/filename="([^"]*)"/)?.[1] ?? null,
      status: 2,
      statusText: "解析中",
      chunkCount: 0,
      parseProgress: 0,
      description: hasDescription ? "测试描述" : null,
    });
    return;
  }
  const kbMatch = /^\/ai\/api\/ai\/v1\/kbs\/([^/]+)$/.exec(path);
  if (kbMatch && method === "GET") {
    if (kbMatch[1] !== "kb-1") {
      envelopeError(res, 404, "知识库不存在");
      return;
    }
    envelopeOk(res, KB1);
    return;
  }

  // ---------- 工作流 ----------
  const wfRunMatch = /^\/ai\/api\/ai\/v1\/workflows\/([^/]+)\/run$/.exec(path);
  if (wfRunMatch && method === "POST") {
    if (wfRunMatch[1] !== "wf-1") {
      envelopeError(res, 404, "工作流不存在");
      return;
    }
    const body = JSON.parse(((await readBody(req)).toString("utf8") || "{}")) as { inputs?: Record<string, unknown> };
    envelopeOk(res, runObject(body.inputs));
    return;
  }
  const wfGetMatch = /^\/ai\/api\/ai\/v1\/workflows\/([^/]+)\/run\/([^/]+)$/.exec(path);
  if (wfGetMatch && method === "GET") {
    if (wfGetMatch[1] !== "wf-1" || wfGetMatch[2] !== "run-1") {
      envelopeError(res, 404, "运行记录不存在");
      return;
    }
    envelopeOk(res, runObject(undefined));
    return;
  }

  envelopeError(res, 404, `mock 未实现的端点：${method} ${path}`);
}

/** 起 mock 服务（随机端口，127.0.0.1），返回 baseUrl 与 close */
export async function startMockServer(): Promise<MockServer> {
  const server = createServer((req, res) => {
    route(req, res).catch((e: unknown) => {
      if (!res.headersSent) sendJson(res, 500, { success: false, msg: String(e) });
      else res.end();
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as AddressInfo;
  return {
    baseUrl: `http://127.0.0.1:${port}/ai`,
    close: () =>
      new Promise<void>((resolve, reject) => {
        // 先断掉 SSE 等长连接，否则 close 会等连接自然结束
        server.closeAllConnections?.();
        server.close((err) => (err ? reject(err) : resolve()));
      }),
  };
}
