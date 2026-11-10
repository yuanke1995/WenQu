/** retrieval.search：成功解包 + 平台包络错误 + 429 */
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

test("retrieval.search 返回解包后的知识块", async () => {
  const result = await client.retrieval.search({
    query: "问渠是什么",
    knowledgeBaseIds: ["kb-1"],
    topK: 5,
    minScore: 0.3,
  });
  assert.equal(result.total, 2);
  assert.equal(result.chunks[0].text, "问渠是 AI 智能体工作台");
  assert.equal(result.chunks[0].score, 0.87);
  assert.deepEqual(result.chunks[0].images, ["img1.png"]);
  assert.equal(result.chunks[1].title, null);
  assert.deepEqual(result.warnings, ["关键词检索失败，仅向量结果"]);
});

test("retrieval.search 平台包络错误映射（400 + msg）", async () => {
  await assert.rejects(
    client.retrieval.search({ query: "fail" }),
    (e: unknown) => {
      assert.ok(e instanceof WenquApiError);
      assert.equal((e as WenquApiError).status, 400);
      assert.match(e instanceof WenquApiError ? e.message : "", /不存在或不可见/);
      return true;
    },
  );
});

test("retrieval.search 平台包络 429 限频", async () => {
  await assert.rejects(
    client.retrieval.search({ query: "ratelimit" }),
    (e: unknown) => {
      assert.ok(e instanceof WenquRateLimitError);
      assert.equal((e as WenquRateLimitError).status, 429);
      return true;
    },
  );
});
