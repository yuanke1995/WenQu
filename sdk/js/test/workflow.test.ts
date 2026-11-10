/** workflows.run / getRun：成功、failed 不抛错、404 */
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { WenquClient, WenquApiError } from "../src/index.js";
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

test("workflows.run 同步触发返回 run 对象", async () => {
  const run = await client.workflows.run("wf-1", { inputs: { question: "你好" } });
  assert.equal(run.id, "run-1");
  assert.equal(run.status, "success");
  assert.deepEqual(run.outputs, { answer: "已完成" });
  assert.equal(run.durationMs, 120);
  assert.ok(Array.isArray(run.nodeTraces));
});

test("workflows.run 失败不抛错：读 status=failed 与 error", async () => {
  const run = await client.workflows.run("wf-1", { inputs: { fail: true } });
  assert.equal(run.status, "failed");
  assert.equal(run.error, "LLM 节点执行失败");
  assert.equal(run.outputs, null);
});

test("workflows.getRun 补查运行结果", async () => {
  const run = await client.workflows.getRun("wf-1", "run-1");
  assert.equal(run.id, "run-1");
  assert.equal(run.status, "success");
});

test("workflows.run 不存在的工作流：平台包络 404", async () => {
  await assert.rejects(
    client.workflows.run("wf-x"),
    (e: unknown) => {
      assert.ok(e instanceof WenquApiError);
      assert.equal((e as WenquApiError).status, 404);
      return true;
    },
  );
});
