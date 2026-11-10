/**
 * 原生问答运行时（深度集成）测试：代理形态（nativeRoot + headers 注入登录态、不带 X-Api-Key），
 * 覆盖流式分发、人在回路卡片、断线接流、stop/steer、附件上传与包络端点。
 */
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { WenquClient } from "../src/index.js";
import type { ArtifactEvent, AskUserCard, DonePayload, SnapshotPayload, ToolStatusEvent } from "../src/index.js";
import { startMockServer, type MockServer } from "./mock-server.js";

let server: MockServer;
let client: WenquClient;

before(async () => {
  server = await startMockServer();
  // 代理形态：nativeRoot 指到 mock 的 /copilot/q，附加头每次现取（模拟令牌刷新）；
  // 刻意不传 apiKey——浏览器不该拿到 Key，凭据在代理那一侧
  client = new WenquClient({
    baseUrl: server.baseUrl,
    nativeRoot: `${server.baseUrl.replace(/\/ai$/, "")}/copilot/q`,
    headers: () => ({ "X-Test-Login": "tok-1" }),
  });
});

after(async () => {
  await server.close();
});

/** 把回调式流包成 Promise 等终局，便于断言 */
function collect(params: Parameters<typeof client.native.send>[0]) {
  return new Promise<Record<string, unknown>>((resolve, reject) => {
    const texts: string[] = [];
    const tools: ToolStatusEvent[] = [];
    const artifacts: ArtifactEvent[] = [];
    const asks: AskUserCard[] = [];
    const events: string[] = [];
    client.native.send(params, {
      onEvent: (f) => events.push(f.type),
      onText: (t) => texts.push(t),
      onTool: (e) => tools.push(e),
      onArtifact: (a) => artifacts.push(a),
      onAskUser: (c) => asks.push(c),
      onDone: (p: DonePayload) => resolve({ texts, tools, artifacts, asks, events, done: p }),
      onError: (m) => reject(new Error(m)),
    });
  });
}

test("send：token 增量、tool_status 整包与人话行、artifact、done 双重序列化解开", async () => {
  const r = await collect({ question: "你好", sessionId: "sess-1", agentId: "agent-1" });
  assert.deepEqual(r.texts, ["你好", "，这是原生流"]);
  const tool = (r.tools as ToolStatusEvent[])[0];
  assert.equal(tool.name, "search");
  assert.equal((r.artifacts as ArtifactEvent[])[0].filename, "form.json");
  const done = r.done as DonePayload;
  assert.equal(done.messageId, "msg-1");
  assert.equal(done.sessionId, "sess-1"); // 帧上的会话 id 合入载荷顶层
  assert.equal(done.finalContent, "你好，这是原生流");
  assert.equal((done.sources ?? []).length, 1);
  // 未知/透传事件（turn_start）经 onEvent 可见
  assert.ok((r.events as string[]).includes("turn_start"));
});

test("send：ask_user 不是终态——卡片帧后同一根流继续长出后续与 done", async () => {
  const r = await collect({ question: "这轮会出提问卡", sessionId: "sess-1" });
  const asks = r.asks as AskUserCard[];
  assert.equal(asks.length, 1);
  assert.equal(asks[0].askId, "ask-1");
  assert.equal(asks[0].questions[0].options?.length, 2);
  assert.deepEqual(r.texts, ["先问一句", "，收到答案继续"]);
  assert.equal((r.done as DonePayload).messageId, "msg-2");
});

test("resume：running=true 收快照后续增量，done 收口", async () => {
  const { snap, texts } = await new Promise<{ snap: SnapshotPayload; texts: string[] }>((resolve, reject) => {
    const texts: string[] = [];
    let snap: SnapshotPayload | undefined;
    client.native.resume("sess-1", {
      onSnapshot: (s) => { snap = s; },
      onText: (t) => texts.push(t),
      onDone: () => resolve({ snap: snap as SnapshotPayload, texts }),
      onError: (m) => reject(new Error(m)),
    });
  });
  assert.equal(snap.running, true);
  assert.deepEqual(texts, ["继续的部分"]);
});

test("resume：没在跑的会话按 snapshot{running:false} 即终局（onDone 收到 snapshotOnly）", async () => {
  const done = await new Promise<DonePayload>((resolve, reject) => {
    client.native.resume("idle", {
      onDone: (p) => resolve(p),
      onError: (m) => reject(new Error(m)),
    });
  });
  assert.equal((done as unknown as Record<string, unknown>).snapshotOnly, true);
});

test("代理回 HTTP 200 但 Content-Type 是 JSON 信封（未配置形态）：分流走 onError，不当流读", async () => {
  const msg = await new Promise<string>((resolve, reject) => {
    client.native.send({ question: "信封错误", sessionId: "sess-1" }, {
      onError: (m) => resolve(m),
      onDone: () => reject(new Error("不该收到 done")),
    });
  });
  assert.match(msg, /wenqu\.proxy\.api-key/);
});

test("runStatus / stop / steer 包络端点", async () => {
  const st = await client.native.runStatus("sess-1");
  assert.equal(st.running, true);
  const stop = await client.native.stop("sess-1");
  assert.equal(stop.stopped, true);
  const steer = await client.native.steer("sess-1", "换个方向");
  assert.equal(steer.accepted, true);
  assert.equal(steer.messageId, "msg-steer");
});

test("uploadAttachment：multipart 字段 file，返回 fileId", async () => {
  const r = await client.native.uploadAttachment(new Blob(["hello wenqu"]), "note.txt");
  assert.equal(r.fileId, "f-1");
  assert.equal(r.name, "note.txt");
});

test("人在回路三张卡：answerAsk / resolvePlan / resolveApproval 与 pending 恢复", async () => {
  const answered = await client.native.answerAsk("ask-1", ["方案A"]) as { delivered: boolean; answers: string[] };
  assert.equal(answered.delivered, true);
  assert.deepEqual(answered.answers, ["方案A"]);
  const ignored = await client.native.answerAsk("ask-1", undefined, true) as { ignore: boolean };
  assert.equal(ignored.ignore, true);
  const plan = await client.native.resolvePlan("p-1", { approved: false, revise: true, plan: "第二步改一下" }) as { decision: Record<string, unknown> };
  assert.equal(plan.decision.revise, true);
  assert.equal(plan.decision.plan, "第二步改一下");
  const ok = await client.native.resolveApproval("t-1", true);
  assert.equal(ok, "已批准");
  const asks = await client.native.pendingAsks("sess-1");
  assert.equal(asks[0].askId, "ask-1");
  const plans = await client.native.pendingPlans("sess-1");
  assert.equal(plans[0].planApprovalId, "p-1");
  const approvals = await client.native.pendingApprovals("sess-1");
  assert.equal(approvals[0].approvalId, "t-1");
});

test("history：{messages:[...]} 归一为数组；sessionEvents 可用", async () => {
  const rows = await client.native.history("sess-1");
  assert.equal(rows.length, 2);
  assert.equal(rows[1].role, "assistant");
  const ev = await client.native.sessionEvents("sess-1", "turn-1");
  assert.ok(Array.isArray((ev as Record<string, unknown>).items));
});

test("send 参数校验：question 为空走 onError，不开流", async () => {
  const msg = await new Promise<string>((resolve) => {
    client.native.send({ question: "  " }, { onError: (m) => resolve(m) });
  });
  assert.match(msg, /question/);
});

test("abort：断本地流收 onAbort（服务端语义为断线续跑，停止整轮要另调 stop）", async () => {
  const aborted = await new Promise<boolean>((resolve) => {
    const handle = client.native.send({ question: "慢一点", sessionId: "sess-1" }, {
      onAbort: () => resolve(true),
      onError: () => resolve(false),
    });
    handle.abort();
  });
  assert.equal(aborted, true);
});
