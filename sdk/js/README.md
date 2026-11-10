# @wenqu/sdk

问渠 WenQu（AI 智能体工作台）官方 JavaScript/TypeScript SDK。几行代码即可在外部项目中调用问渠的**问答、向量、知识库检索、知识库管理与工作流**能力。

- 零运行时依赖：只用 `fetch` / `ReadableStream` / `FormData` 等标准 API，Node ≥ 18 与浏览器双端可用。
- OpenAI 兼容语义：问答与向量端点对齐 OpenAI `chat/completions`、`responses`、`embeddings` 的形状，迁移成本低。
- TypeScript strict 全量类型，流式返回 `AsyncIterable`，可直接 `for await`。

## 安装

```bash
npm i @wenqu/sdk
```

本地开发期也可以直接从仓库安装：

```bash
npm i file:/path/to/WenQu/sdk/js
```

## 创建客户端

```ts
import { WenquClient } from "@wenqu/sdk";

const client = new WenquClient({
  baseUrl: "http://host:8090/ai", // 后端 context-path 是 /ai，SDK 不自动补，传什么用什么
  apiKey: "sk-xxx",               // 在问渠「API Key 管理」页签发，以 X-Api-Key 请求头发送
});
```

## 快速上手

### 问答（chat completions）

`model` 填**问渠智能体 id**（在问渠智能体页查看）。多轮对话靠重发完整 `messages`（无状态，OpenAI 原生用法）。

```ts
const completion = await client.chat.completions.create({
  model: "你的智能体id",
  messages: [
    { role: "system", content: "用中文简洁回答" },
    { role: "user", content: "问渠是什么？" },
  ],
});
console.log(completion.choices[0].message.content);
console.log(completion.usage); // { prompt_tokens, completion_tokens, total_tokens }
```

也支持 Function calling（只接受只读工具，副作用工具平台侧不暴露）：

```ts
const resp = await client.chat.completions.create({
  model: "你的智能体id",
  messages: [{ role: "user", content: "北京今天天气如何？" }],
  tools: [
    { type: "function", function: { name: "get_weather", parameters: { type: "object", properties: { city: { type: "string" } } } } },
  ],
});
if (resp.choices[0].finish_reason === "tool_calls") {
  // 执行工具后带 role=tool 消息再发下一轮
}
```

### Responses API

```ts
const result = await client.responses.create({
  model: "你的智能体id",
  input: "帮我总结一下知识库里的产品手册",
  instructions: "面向新用户",
});
console.log(result.output[0].content[0].text);
```

### 向量（embeddings）

`model` 填**向量模型引用** `providerId/modelId`（必填，问渠向量按库绑定、无全局默认）。先看可用清单：

```ts
const list = await client.models.listEmbeddings();
for (const group of list.data) {
  console.log(group.providerName, group.models?.map((m) => m.ref));
}

const { data, usage, dimension } = await client.embeddings.create({
  input: ["问渠是 AI 智能体工作台", "第二段文本"], // 单批 ≤256 条、单条 ≤8192 token
  model: "providerId/modelId",
});
console.log(data[0].embedding, usage, dimension);
```

### 知识库检索

```ts
const result = await client.retrieval.search({
  query: "如何配置向量模型？",
  knowledgeBaseIds: ["kb-1"], // 可选：空 = 本人可见全部库
  topK: 10,                   // 1-50，默认 20
  minScore: 0.5,              // 按融合分过滤
});
for (const chunk of result.chunks) {
  console.log(chunk.score, chunk.titlePath, chunk.text);
}
if (result.warnings?.length) console.warn("检索降级：", result.warnings);
```

### 知识库管理

```ts
const { knowledgeBases } = await client.kbs.list();
const kb = await client.kbs.get(knowledgeBases[0].id);

// 建库（embeddingRef 必填）
const created = await client.kbs.create({
  name: "新资料库",
  embeddingRef: "providerId/modelId",
  description: "外部系统同步进来的资料",
});

// 上传文档（浏览器 File / Node Blob / 字节 / 文本皆可）
const doc = await client.kbs.uploadDocument(created.id, {
  file: new TextEncoder().encode("文档内容"),
  filename: "notes.txt",
  description: "同步备注",
});
// 同步返回 status=2（解析中），解析/分块/向量化走后台队列：
const docs = await client.kbs.listDocuments(created.id);
// status：0 生效 / 1 已弃用 / 2 解析中 / 3 解析失败（看 failReason）
```

### 工作流

触发**已发布**版本的工作流；运行失败不抛错，读 `status` 分流：

```ts
const run = await client.workflows.run("工作流id", {
  inputs: { question: "生成本周摘要" }, // 对应 DSL 里的 {{start.key}}
});
if (run.status === "success") {
  console.log(run.outputs, run.durationMs);
} else {
  console.error(run.error, run.nodeTraces); // 定位失败节点
}

// 事后补查
const again = await client.workflows.getRun("工作流id", run.id);
```

## 流式用法

`stream: true` 时返回 `AsyncIterable`，直接 `for await`：

```ts
for await (const chunk of client.chat.completions.create({
  model: "你的智能体id",
  messages: [{ role: "user", content: "讲个故事" }],
  stream: true,
  includeUsage: true, // 透传 stream_options.include_usage：尾帧带 usage
})) {
  process.stdout.write(chunk.choices[0]?.delta?.content ?? "");
  if (chunk.usage) console.log("\n用量：", chunk.usage); // choices 为空的 usage 尾帧
}
```

Responses 流式按事件消费（`response.created` / `response.output_text.delta` / `response.completed` / `response.failed`）：

```ts
for await (const event of client.responses.create({
  model: "你的智能体id",
  input: "讲个故事",
  stream: true,
})) {
  if (event.type === "response.output_text.delta") process.stdout.write(event.delta);
  if (event.type === "response.failed") console.error("本轮失败", event.response.status);
}
```

浏览器端渲染示例：`for await` 中把 `delta` 追加到 DOM 即可，无需额外库。

## 错误处理

所有错误都继承 `WenquError`，按类型分流即可（后端 OpenAI 风格 `{error:{message,type}}` 与平台包络 `{success:false,msg}` 两种错误体已统一映射）：

| 错误类 | 触发场景 |
| --- | --- |
| `WenquAuthError` | 401/403：API Key 无效、scope 未开通、资源不可见 |
| `WenquRateLimitError` | 429：按调用身份限频，稍后重试 |
| `WenquApiError` | 其他非 2xx / 包络 `success:false`（带 `status`、OpenAI 风格错误带 `type`） |
| `WenquError` | 基类；参数校验、网络失败也用它 |

```ts
import { WenquAuthError, WenquRateLimitError, WenquError } from "@wenqu/sdk";

try {
  await client.retrieval.search({ query: "问题" });
} catch (e) {
  if (e instanceof WenquRateLimitError) {
    // 限频：退避后重试
  } else if (e instanceof WenquAuthError) {
    // 鉴权/scope：检查 Key 与开通的能力
  } else if (e instanceof WenquError) {
    console.error(e.message, e.status, e.type);
  }
}
```

流式的错误同样以异常呈现：HTTP 层失败在进入 `for await` 时抛出；流中错误帧（后端以 `error` 字段收尾）会抛出 `WenquApiError` 而不是混在正常 chunk 里。

## API Key scope

API Key 按能力开通 scope，未开通调用对应端点返回 403（`WenquAuthError`）：

| scope | 覆盖能力 | 说明 |
| --- | --- | --- |
| `chat` | `chat/completions`、`responses` | 问答能力 |
| `embedding` | `embeddings`、`models/embeddings` 清单 | 向量能力 |
| `kb.read` | `retrieval`、`kbs` 列表/详情/文档列表 | 知识库只读 |
| `kb.write` | `kbs` 建库、上传文档 | **默认不放开**，需在 Key 编辑里显式勾选 |
| `workflow.run` | `workflows/{id}/run` 触发与补查 | 工作流执行 |

给外部系统签发 Key 时按最小能力开通即可。

## 开发

```bash
cd sdk/js
npm install        # 仅 devDependencies（typescript、@types/node）
npm run build      # tsc 编译到 dist/
npm test           # 编译测试到 dist-test/ 后 node --test（mock server 随机端口自起自停）
```
