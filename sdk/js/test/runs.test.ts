/** v1 run 原语（A2）：7 端点裸 JSON、SSE id/seq、Last-Event-ID 自动续传、resume_expired 降级。 */

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { WenquClient, WenquAuthError, WenquError, type RunEvent } from "../src/index.js";
import { startMockServer, MOCK_API_KEY, type MockServer } from "./mock-server.js";

let server: MockServer;
let client: WenquClient;

before(async () => {
  server = await startMockServer();
  client = new WenquClient({ baseUrl: server.baseUrl, apiKey: MOCK_API_KEY });
});
after(async () => { await server.close(); });

const fast = { reconnectDelayMs: 0 };

test("create 回裸对象（不是平台包络），Idempotency-Key 命中标志透传", async () => {
  const d = await client.runs.create("agent-1", {
    question: "只回复两个字：收到", idempotencyKey: "idem-1",
  });
  assert.equal(d.runId, "run-new");
  assert.equal(d.status, "running");
  assert.equal(d.idempotent, true);
  assert.equal((d as unknown as Record<string, unknown>).success, undefined);
});

test("create 缺 question 本地拦截", async () => {
  await assert.rejects(() => client.runs.create("agent-1", { question: "" }), WenquError);
});

test("get / artifacts 直接回对象，签名 URL 与有效期都在", async () => {
  const d = await client.runs.get("run-basic");
  assert.equal(d.runId, "run-basic");
  const list = await client.runs.artifacts("run-basic");
  assert.equal(list.count, 1);
  assert.equal(list.artifacts[0].expiresInSeconds, 3600);
  assert.match(list.artifacts[0].downloadUrl, /sig=/);
});

test("approve 统一 decision + 可选字段", async () => {
  const r = await client.runs.approve("run-basic", {
    approvalId: "card-1", decision: "revise", plan: "改成两步", answers: ["A"],
  });
  assert.equal(r.accepted, true);
  assert.equal(r.kind, "tool_approval");
  assert.equal(r.decision, "revise");
});

test("steer / stop", async () => {
  const s = await client.runs.steer("run-basic", "补充一句");
  assert.equal(s.accepted, true);
  assert.equal(s.messageId, "m-1");
  const t = await client.runs.stop("run-basic");
  assert.equal(t.stopped, true);
  await assert.rejects(() => client.runs.steer("run-basic", ""), WenquError);
});

test("stream 基础：id 单调、事件名来自 event: 行、data 是扁平对象", async () => {
  const seen: RunEvent[] = [];
  const tokens: string[] = [];
  const summary = await client.runs.stream("run-basic", {
    onEvent: (e) => seen.push(e),
    onToken: (d) => tokens.push(d),
  }, fast);
  assert.deepEqual(seen.map((e) => e.id), [1, 2, 3]);
  assert.deepEqual(seen.map((e) => e.event), ["run.started", "token", "run.done"]);
  assert.equal(seen[1].data.delta, "收到");
  assert.equal(seen[0].data.runId, "run-basic");
  assert.deepEqual(tokens, ["收到"]);
  assert.equal(summary.status, "succeeded");
  assert.equal(summary.lastEventId, 3);
  assert.equal(summary.reconnects, 0);
});

test("断线自动续传：第二次带 Last-Event-ID: 2，只补 id=3", async () => {
  server.seenLastEventIds.length = 0;
  const seen: RunEvent[] = [];
  let reconnects = 0;
  const summary = await client.runs.stream("run-disconnect", {
    onEvent: (e) => seen.push(e),
    onReconnect: () => { reconnects++; },
  }, fast);
  assert.deepEqual(server.seenLastEventIds, ["", "2"]);
  assert.deepEqual(seen.map((e) => e.id), [1, 2, 3]);
  assert.equal(summary.status, "succeeded");
  assert.equal(summary.reconnects, 1);
  assert.equal(reconnects, 1);
});

test("指定 lastEventId 起点：不带 0 帧、只收增量", async () => {
  server.seenLastEventIds.length = 0;
  const seen: RunEvent[] = [];
  const summary = await client.runs.stream("run-basic", {
    onEvent: (e) => seen.push(e),
  }, { ...fast, lastEventId: 2 });
  assert.deepEqual(server.seenLastEventIds, ["2"]);
  assert.deepEqual(seen.map((e) => e.id), [3]);
  assert.equal(summary.lastEventId, 3);
});

test("seq 单调去重：服务端重复发 id=2 的帧只派发一次", async () => {
  const seen: RunEvent[] = [];
  await client.runs.stream("run-dup", { onEvent: (e) => seen.push(e) }, fast);
  assert.deepEqual(seen.map((e) => e.id), [1, 2, 3]);
});

test("resume_expired 自动降级重拉 GET /runs/{id}，不伪装成新流", async () => {
  let fallback: Record<string, unknown> | undefined;
  const errors: RunEvent[] = [];
  const summary = await client.runs.stream("run-expired", {
    onEvent: (e) => { if (e.event === "run.error") errors.push(e); },
    onFallback: (d) => { fallback = d as unknown as Record<string, unknown>; },
  }, fast);
  assert.equal(summary.status, "resume_expired");
  assert.equal(summary.detail?.status, "succeeded");
  assert.equal(fallback?.runId, "run-expired");
  assert.equal(errors.length, 1);
  assert.equal(errors[0].data.code, "resume_expired");
});

test("run.error 稳定码与 request_id 交回调用方", async () => {
  const summary = await client.runs.stream("run-error", {}, fast);
  assert.equal(summary.status, "failed");
  assert.equal(summary.error?.code, "rate_limited");
  assert.equal(summary.error?.requestId, "rid-e");
});

test("run.done 的 status=stopped 如实收成 stopped", async () => {
  const summary = await client.runs.stream("run-stopped", {}, fast);
  assert.equal(summary.status, "stopped");
});

test("403 不重连：鉴权类错误直接抛，且只请求一次", async () => {
  server.seenLastEventIds.length = 0;
  await assert.rejects(() => client.runs.stream("run-403", {}, fast), WenquAuthError);
  assert.equal(server.seenLastEventIds.length, 1);
});

test("abort 取消返回 cancelled，不误报为断线重连", async () => {
  const controller = new AbortController();
  const summary = await client.runs.stream("run-basic", {
    onEvent: () => controller.abort(),
  }, { ...fast, signal: controller.signal });
  assert.equal(summary.status, "cancelled");
});
