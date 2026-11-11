# @wenqu/sdk

问渠 WenQu（AI 智能体工作台）官方 JavaScript/TypeScript SDK。几行代码即可在外部项目中调用问渠的**问答、向量、知识库检索、知识库管理与工作流**能力。

- 零运行时依赖：只用 `fetch` / `ReadableStream` / `FormData` 等标准 API，Node ≥ 18 与浏览器双端可用。
- OpenAI 兼容语义：问答与向量端点对齐 OpenAI `chat/completions`、`responses`、`embeddings` 的形状，迁移成本低。
- TypeScript strict 全量类型，流式返回 `AsyncIterable`，可直接 `for await`。

## 安装

```bash
npm i @wenqu/sdk
```

> 尚未发布到公共 npm；实际分发方式见「[对外分发](#对外分发给其他项目用)」。

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

`apiKey` 可省略——只走 `client.native` 且经自有后端代理时（见「深度集成」），
凭据在代理那一侧，浏览器不持有 Key。

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

## 深度集成（client.native：原生问答协议）

`chat.completions` 是无状态的 OpenAI 标准面；把**智能体助手嵌进自己的业务系统**要的是另一套东西——
流式正文、工具过程、产物实时交付、人在回路三张卡（提问/计划/审批）、断线接流、运行中插话。
`client.native` 覆盖问渠原生 `/api/ai/chat` 事件协议，这些全部内置。

**推荐拓扑（Key 不进浏览器）**：业务后端加一层薄代理，把 `/api/ai/**` 带 `X-Api-Key` 转发给问渠；
前端 SDK 用 `nativeRoot` 指到自家代理，`headers` 注入自家登录态：

```ts
const client = new WenquClient({
  baseUrl: "http://host:8090/ai",
  nativeRoot: "https://业务域名/api/copilot/q",   // 代理前缀（转发问渠 /api/ai/**）
  headers: () => ({ "X-Token": 当前登录令牌() }),  // 函数形态：令牌刷新后自动跟上
});

const turn = client.native.send(
  { question: "帮我生成一个请假表单", sessionId, agentId, variables: { formId: "123" } },
  {
    onText:  (delta) => 追加正文(delta),
    onStatus: (line) => 更新状态条(line),          // 「检索知识库」这类人话一行
    onTool:  (evt)   => 渲染工具进度(evt.name, evt.args, evt.result),
    onArtifact: (a)  => 挂产物卡片(a.url, a.filename),   // deliver_artifact 实时推送
    onAskUser: (card) => 弹提问卡(card.askId, card.questions),
    onPlanApproval: (card) => 弹计划卡(card.planApprovalId, card.plan),
    onApprovalRequired: (c) => 弹审批卡(c.approvalId),
    onDone:  (p) => { 收尾(p.finalContent, p.sources, p.messageId); },
    onError: (m) => 提示(m),
    onAbort: () => 本地断流收尾(),
  },
);
// turn.abort() 只断本地流（服务端后台照跑，可 resume 接回）；停止整轮用 stop()

// 人在回路：答完卡，同一根流继续长出后续
await client.native.answerAsk(card.askId, ["方案A"]);        // 或 (id, undefined, true) 忽略
await client.native.resolvePlan(p.id, { approved: true });   // 或 {revise:true,plan:"意见"} / {supersede:true,plan:"意见"} / {approved:false}
await client.native.resolveApproval(c.approvalId, true);

// 刷新页面/换设备接回正在跑的一轮：先 snapshot 后增量
const st = await client.native.runStatus(sessionId);
if (st.running) client.native.resume(sessionId, { onSnapshot, onText, onDone, onError });

// 运行中插话 / 服务端停止 / 附件 / 历史
await client.native.steer(sessionId, "第二列改成必填");
await client.native.stop(sessionId);
const { fileId } = await client.native.uploadAttachment(file);
const messages = await client.native.history(sessionId);
const asks = await client.native.pendingAsks(sessionId);      // 刷新后重建未答的卡
```

事件契约**只增不删**：每一帧都先到 `onEvent(frame)`，未识别的新事件不报错——
后端加事件类型时 SDK 不用升级也能透传。

直连形态（Node 脚本/服务端）：不传 `nativeRoot` 与 `headers`，传 `apiKey` 即可，端点自动落在 `{baseUrl}/api/ai/**`。

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

## 对外分发（给其他项目用）

SDK 暂不发布到公共 npm，对外交付以 tarball 为主，按「打包 → 安装 → 接入」三步走。

### 1. 打包（在 WenQu 仓库）

```bash
cd sdk/js
npm ci
npm run build        # tsc 编译到 dist/
npm pack             # 产出 wenqu-sdk-0.1.0.tgz
```

产物只含 `dist/` 与 `package.json`（约 28KB），零运行时依赖，不依赖 node_modules。
`dist/` 不在版本库里，拿到仓库副本的一方必须先 build 才能 pack。

### 2. 对方项目安装

| 方式 | 命令 | 适用场景 |
| --- | --- | --- |
| tarball 拷贝 | `npm i /path/to/wenqu-sdk-0.1.0.tgz` | 推荐，跨机器/跨项目，版本固定 |
| 本地路径 | `npm i file:/path/to/WenQu/sdk/js` | 同一台机器，目录软链，SDK 重新 build 后即生效 |
| 私有 registry | `npm publish --registry http://内网地址` 后 `npm i @wenqu/sdk` | 多项目长期共用、CI 也要安装 |

本地路径与 tarball 两种方式都要求 `dist/` 已存在。

公共 npm 发不了：包名 `@wenqu/sdk` 是 scoped 包，需要拥有 `@wenqu` scope（npm 组织）；要上公网需改包名或新建组织。

### 3. 接入

装包前先在问渠「API Key 管理」签发 Key，按需勾选 scope（见「API Key scope」）。

```ts
import { WenquClient } from "@wenqu/sdk";

const client = new WenquClient({
  baseUrl: "http://host:8090/ai", // /ai 必须带，SDK 不自动补
  apiKey: "sk-xxx",
});

const resp = await client.chat.completions.create({
  model: "智能体id",
  messages: [{ role: "user", content: "你好" }],
});
```

浏览器场景不要把 Key 放进前端：业务后端转发 `/api/ai/**`，前端用 `nativeRoot` + `headers` 指过去（见「深度集成」）。不想写代码可直接用 `wenqu-embed.js` 或 iframe 嵌入。

### 4. 注意事项

- **纯 ESM**（`"type": "module"`，无 CJS 产物）：CJS 项目里 `require("@wenqu/sdk")` 在 Node < 22.12 会报错，改用 `await import()` 或升级 Node。
- **Node ≥ 18**；浏览器需支持 `fetch` / `ReadableStream`。
- **升级**：重新 pack 后重装 tarball。沿用同一版本号时个别 npm 缓存场景需 `--force` 或 `npm cache clean --force`，建议每次 `npm version patch` 再 pack。
- **类型**：自带 `dist/*.d.ts`，不需要额外安装 `@types`。

## 开发

```bash
cd sdk/js
npm install        # 仅 devDependencies（typescript、@types/node）
npm run build      # tsc 编译到 dist/
npm test           # 编译测试到 dist-test/ 后 node --test（mock server 随机端口自起自停）
```
