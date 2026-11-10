/** kbs 列表/详情/文档列表/创建/上传 + 404/403 错误 */
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

test("kbs.list 返回库列表", async () => {
  const result = await client.kbs.list();
  assert.equal(result.total, 1);
  assert.equal(result.knowledgeBases[0].id, "kb-1");
  assert.equal(result.knowledgeBases[0].embeddingRef, "p1/m1");
});

test("kbs.get 返回单个库", async () => {
  const kb = await client.kbs.get("kb-1");
  assert.equal(kb.name, "产品知识库");
  assert.equal(kb.docCount, 3);
});

test("kbs.get 不存在的库：平台包络 404", async () => {
  await assert.rejects(
    client.kbs.get("kb-x"),
    (e: unknown) => {
      assert.ok(e instanceof WenquApiError);
      assert.equal((e as WenquApiError).status, 404);
      assert.match(e instanceof WenquApiError ? e.message : "", /不存在/);
      return true;
    },
  );
});

test("kbs.listDocuments 返回文档与状态", async () => {
  const result = await client.kbs.listDocuments("kb-1");
  assert.equal(result.total, 1);
  assert.equal(result.documents[0].status, 0);
  assert.equal(result.documents[0].statusText, "生效");
  assert.equal(result.documents[0].chunkCount, 12);
});

test("kbs.create 建库（embeddingRef 必填）", async () => {
  const kb = await client.kbs.create({ name: "新库", embeddingRef: "p1/m1", description: "说明" });
  assert.equal(kb.id, "kb-new");
  assert.equal(kb.name, "新库");
  await assert.rejects(
    client.kbs.create({ name: "缺引用", embeddingRef: "" }),
    (e: unknown) => e instanceof WenquError,
  );
});

test("kbs.uploadDocument multipart 上传（file + description）", async () => {
  const doc = await client.kbs.uploadDocument("kb-1", {
    file: new TextEncoder().encode("hello wenqu"),
    filename: "test.txt",
    description: "测试描述",
  });
  assert.equal(doc.id, "doc-2");
  assert.equal(doc.fileName, "test.txt");
  assert.equal(doc.status, 2);
  assert.equal(doc.statusText, "解析中");
  assert.equal(doc.description, "测试描述");
});

test("kbs.uploadDocument 字符串内容也可上传", async () => {
  const doc = await client.kbs.uploadDocument("kb-1", { file: "hello wenqu", filename: "a.txt" });
  assert.equal(doc.status, 2);
});

test("kbs.uploadDocument 无权限的库：403", async () => {
  await assert.rejects(
    client.kbs.uploadDocument("kb-forbidden", { file: "hello wenqu", filename: "a.txt" }),
    (e: unknown) => {
      assert.ok(e instanceof WenquApiError);
      assert.equal((e as WenquApiError).status, 403);
      return true;
    },
  );
});
