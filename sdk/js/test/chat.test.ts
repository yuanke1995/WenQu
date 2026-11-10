/** chat.completions 非流式/流式 + responses 非流式/流式 */
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { WenquClient, WenquApiError, WenquRateLimitError } from "../src/index.js";
import { startMockServer, MOCK_API_KEY, type MockServer } from "./mock-server.js";

let server: MockServer;
let client: WenquClient;

before(async () => {
  server = await startMockServer();
  client = new WenquClient({ baseUrl: server.baseUrl, apiKey: MOCK_API_KEY });
});

after(async () => {
  await server.close();
});

test("chat.completions 非流式返回 OpenAI 形状", async () => {
  const completion = await client.chat.completions.create({
    model: "agent-1",
    messages: [{ role: "user", content: "你好" }],
  });
  assert.equal(completion.object, "chat.completion");
  assert.equal(completion.choices[0].message.content, "你好，我是问渠");
  assert.equal(completion.choices[0].finish_reason, "stop");
  assert.equal(completion.usage?.total_tokens, 12);
});

test("chat.completions 流式：for await 收增量，includeUsage 透传后带 usage 尾帧", async () => {
  const parts: string[] = [];
  let usage: { total_tokens: number } | undefined;
  for await (const chunk of client.chat.completions.create({
    model: "agent-1",
    messages: [{ role: "user", content: "你好" }],
    stream: true,
    includeUsage: true,
  })) {
    assert.equal(chunk.object, "chat.completion.chunk");
    const delta = chunk.choices[0]?.delta?.content;
    if (delta) parts.push(delta);
    if (chunk.usage) usage = chunk.usage;
  }
  assert.equal(parts.join(""), "你好，我是问渠");
  assert.equal(usage?.total_tokens, 12);
});

test("chat.completions 流式：不带 includeUsage 时无 usage 尾帧", async () => {
  let usageSeen = false;
  for await (const chunk of client.chat.completions.create({
    model: "agent-1",
    messages: [{ role: "user", content: "你好" }],
    stream: true,
  })) {
    if (chunk.usage) usageSeen = true;
  }
  assert.equal(usageSeen, false);
});

test("chat.completions 错误映射：404 OpenAI 风格", async () => {
  await assert.rejects(
    client.chat.completions.create({ model: "agent-404", messages: [{ role: "user", content: "x" }] }),
    (e: unknown) => {
      assert.ok(e instanceof WenquApiError);
      assert.equal((e as WenquApiError).status, 404);
      assert.equal((e as WenquApiError).type, "not_found");
      assert.match((e as WenquApiError).message, /不存在/);
      return true;
    },
  );
});

test("chat.completions 错误映射：429 限频", async () => {
  await assert.rejects(
    client.chat.completions.create({ model: "agent-429", messages: [{ role: "user", content: "x" }] }),
    (e: unknown) => {
      assert.ok(e instanceof WenquRateLimitError);
      assert.equal((e as WenquRateLimitError).status, 429);
      return true;
    },
  );
});

test("responses 非流式", async () => {
  const resp = await client.responses.create({ model: "agent-1", input: "你好" });
  assert.equal(resp.status, "completed");
  assert.equal(resp.output[0].content[0].text, "你好，我是问渠");
  assert.equal(resp.usage?.total_tokens, 12);
});

test("responses 流式：created → delta* → completed", async () => {
  const events: string[] = [];
  const deltas: string[] = [];
  let finalText: string | undefined;
  for await (const ev of client.responses.create({ model: "agent-1", input: "你好", stream: true })) {
    events.push(ev.type);
    if (ev.type === "response.output_text.delta") deltas.push(ev.delta);
    if (ev.type === "response.completed") finalText = ev.response.output[0].content[0].text;
  }
  assert.deepEqual(events, [
    "response.created",
    "response.output_text.delta",
    "response.output_text.delta",
    "response.completed",
  ]);
  assert.equal(deltas.join(""), "你好，我是问渠");
  assert.equal(finalText, "你好，我是问渠");
});
