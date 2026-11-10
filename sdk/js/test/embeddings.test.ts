/** embeddings.create + models.listEmbeddings */
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { WenquClient, WenquApiError, WenquError } from "../src/index.js";
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

test("embeddings.create 单条文本", async () => {
  const resp = await client.embeddings.create({ input: "你好", model: "p1/m1" });
  assert.equal(resp.object, "list");
  assert.equal(resp.data.length, 1);
  assert.ok(Array.isArray(resp.data[0].embedding));
  assert.equal(resp.data[0].embedding.length, 3);
  assert.equal(resp.dimension, 3);
  assert.equal(resp.usage.total_tokens, 1);
});

test("embeddings.create 批量 + base64 编码格式", async () => {
  const resp = await client.embeddings.create({
    input: ["甲", "乙", "丙"],
    model: "p1/m1",
    encoding_format: "base64",
  });
  assert.equal(resp.data.length, 3);
  assert.equal(typeof resp.data[1].embedding, "string");
  assert.equal(resp.data[2].index, 2);
});

test("embeddings.create 客户端校验：model 必填", async () => {
  await assert.rejects(
    client.embeddings.create({ input: "x", model: "" }),
    (e: unknown) => e instanceof WenquError,
  );
});

test("embeddings.create 服务端错误：无效 model 引用", async () => {
  await assert.rejects(
    client.embeddings.create({ input: "x", model: "bad/ref" }),
    (e: unknown) => {
      assert.ok(e instanceof WenquApiError);
      assert.equal((e as WenquApiError).status, 400);
      assert.equal((e as WenquApiError).type, "invalid_request_error");
      return true;
    },
  );
});

test("models.listEmbeddings 分组清单按存在性解析", async () => {
  const list = await client.models.listEmbeddings();
  assert.equal(list.object, "list");
  assert.equal(list.data.length, 2);
  assert.equal(list.data[0].providerName, "供应商一");
  assert.equal(list.data[0].models?.[0].ref, "p1/m1");
  // 第二组缺 providerName：取值应为 undefined，ref 仍可用
  assert.equal(list.data[1].providerName, undefined);
  assert.equal(list.data[1].models?.[0].ref, "p2/m2");
});
