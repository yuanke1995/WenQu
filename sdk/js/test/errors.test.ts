/** 错误映射与构造校验：401 鉴权、两种错误体、构造参数 */
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { WenquClient, WenquAuthError, WenquError, WenquRateLimitError } from "../src/index.js";
import { startMockServer, MOCK_API_KEY, type MockServer } from "./mock-server.js";

let server: MockServer;
let baseUrl: string;

before(async () => {
  server = await startMockServer();
  baseUrl = server.baseUrl;
});

after(async () => {
  await server.close();
});

test("无效 API Key：401 映射 WenquAuthError", async () => {
  const client = new WenquClient({ baseUrl, apiKey: "sk-wrong" });
  await assert.rejects(
    client.chat.completions.create({ model: "agent-1", messages: [{ role: "user", content: "x" }] }),
    (e: unknown) => {
      assert.ok(e instanceof WenquAuthError);
      assert.equal((e as WenquAuthError).status, 401);
      return true;
    },
  );
});

test("流式请求的 401 同样映射（HTTP 层先于 SSE）", async () => {
  const client = new WenquClient({ baseUrl, apiKey: "sk-wrong" });
  const stream = client.chat.completions.create({
    model: "agent-1",
    messages: [{ role: "user", content: "x" }],
    stream: true,
  });
  await assert.rejects(async () => {
    for await (const _chunk of stream) {
      /* 鉴权失败时不应到达这里 */
      void _chunk;
    }
  }, WenquAuthError);
});

test("429 两种错误体都能解析出 message（OpenAI 风格与平台包络）", async () => {
  const client = new WenquClient({ baseUrl, apiKey: MOCK_API_KEY });
  // OpenAI 风格：chat 端点
  await assert.rejects(
    client.chat.completions.create({ model: "agent-429", messages: [{ role: "user", content: "x" }] }),
    (e: unknown) => {
      assert.ok(e instanceof WenquRateLimitError);
      assert.equal((e as WenquRateLimitError).type, "rate_limit_error");
      assert.match(e instanceof WenquRateLimitError ? e.message : "", /频繁/);
      return true;
    },
  );
  // 平台包络：retrieval 端点
  await assert.rejects(client.retrieval.search({ query: "ratelimit" }), WenquRateLimitError);
});

test("构造校验：baseUrl 必填；apiKey 可省（代理形态），传值必须是字符串；尾部斜杠归一化", () => {
  assert.throws(() => new WenquClient({ baseUrl: "", apiKey: "sk-x" }), WenquError);
  // 非字符串 apiKey 仍拒；空串/缺省合法（Key 在代理那一侧，浏览器不持有）
  assert.throws(() => new WenquClient({ baseUrl: "http://h:8090/ai", apiKey: 123 as unknown as string }), WenquError);
  const client = new WenquClient({ baseUrl: "http://h:8090/ai/", apiKey: "sk-x" });
  assert.equal(client.baseUrl, "http://h:8090/ai");
  // nativeRoot 缺省 = baseUrl + /api/ai；显式给代理前缀时归一化尾斜杠
  assert.equal(client.nativeRoot, "http://h:8090/ai/api/ai");
  const proxied = new WenquClient({ baseUrl: "http://h:8090/ai", nativeRoot: "http://dtbd/api/copilot/q/" });
  assert.equal(proxied.nativeRoot, "http://dtbd/api/copilot/q");
});

test("客户端参数校验：messages/query 为空直接抛 WenquError", async () => {
  const client = new WenquClient({ baseUrl, apiKey: MOCK_API_KEY });
  await assert.rejects(
    client.chat.completions.create({ model: "agent-1", messages: [] }),
    WenquError,
  );
  await assert.rejects(client.retrieval.search({ query: "  " }), WenquError);
});
