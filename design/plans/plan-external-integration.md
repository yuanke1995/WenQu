# 问渠对外集成能力补齐 · 实施计划

## 仓库调研结论

### 已有的开放能力（相当完整）

| 通道 | 端点 | 现状 |
|------|------|------|
| OpenAI Chat Completions | `POST /api/ai/v1/chat/completions` | ✅ 完整（含 streaming、function calling 只暴露只读工具） |
| OpenAI Responses | `POST /api/ai/v1/responses` | ✅ 完整（streaming/非流式双形态） |
| 原生问答 + SSE | `POST /api/ai/chat` | ✅ 完整（thinking/token/tool/plan/done 全套事件） |
| 工作流 API | `POST /api/ai/v1/workflows/{id}/run` | ✅ 完整 |
| 工作流 Webhook | `POST /api/ai/v1/workflows/webhook/{token}` | ✅ 完整 |
| MCP Server（智能体级） | `/ai/mcp/{token}` | ✅ 完整 |
| MCP Server（平台级） | `/ai/mcp` | ✅ 完整 |
| 智能体轮终 Webhook | 智能体配置回调地址 | ✅ 完整（HMAC + 4 级退避） |
| iframe 嵌入 | `/s/{token}?embed=1` | ✅ 完整（紧凑模式） |
| API Key 鉴权 | `X-Api-Key` | ✅ 统一通道，配额/限流/台账 |
| 对接文档 | `/api-docs` 前端页面 | ✅ 动态生成 |

### 缺失/薄弱的环节

| 缺失项 | 严重程度 | 根因 |
|--------|----------|------|
| **Embeddings API** | 高 | `DynamicEmbeddingModel` 内部已有完整的向量路由/缓存/额度登记逻辑，但没有 Controller 暴露。向量模型按 KB 绑定（`providerId/modelId` 引用），对外需要指定用哪个模型 |
| **知识库管理 API** | 高 | 现有 KnowledgeBaseController/DocumentController 全部走 RBAC（管理员级），API Key 白名单里没有。外部项目无法通过 API 创建库、导入文档、触发检索 |
| **检索 API（只读）** | 中 | 内部有 `HybridRetrievalService`，可以暴露一个只读检索端点让外部项目直接拿检索结果（不经过智能体对话） |
| **JS SDK / npm 包** | 高 | 外部项目要自己写 SSE 解析、错误处理、重连、Token 计数、引用溯源解析 |
| **Python SDK** | 中 | AI/数据科学场景高频，对接 LangChain/LlamaIndex |
| **轻量聊天 Widget** | 中 | iframe 嵌入比较重（整个 SPA），缺一个可配置的浮动聊天框组件（`<wenqu-chatbot>` 自定义元素） |

### 约束与设计原则

1. **API Key 通道复用**：所有新增端点走 `X-Api-Key` 鉴权，配额/限流/台账自动归到 Key 签发人名下
2. **只读优先**：Embeddings、检索等通用能力默认只读；知识库写入（导入/删除）走显式 scope 或特殊权限
3. **OpenAI 兼容**：Embeddings API 走 `/api/ai/v1/embeddings` 语义，方便 SDK 零改造切换 endpoint
4. **前端嵌入安全**：Widget 走游客分享 token（`/s/{token}`），不接受 JWT，避免前端存凭据

---

## 实施步骤（按依赖排序）

### 后端 API 补齐

#### Embeddings API 端点
- **文件**：新建 `OpenAiEmbeddingsController.java`（放在 `controller/` 下，与 `OpenAiCompatController` 同级）
- **路径**：`POST /api/ai/v1/embeddings`
- **参数**：`input`（string 或 string[]）、`model`（向量模型引用 `providerId/modelId`，必填——向量模型按库绑定，无全局默认）、`encoding_format`、`dimensions`
- **实现**：注入 `DynamicEmbeddingModel`，调 `forRef(model).embed(texts)`，结果转 OpenAI 兼容格式 `{object: "list", data: [{object: "embedding", index, embedding, dimension}], model, usage}`
- **鉴权**：
  - API Key 白名单放行（SecurityConfig.isPublicUserEndpoint 新增）
  - JWT 同样可调
- **限流**：走 `rateLimitService.checkRateLimit("embedding", uid)`
- **计量**：usageLog 按 api_key_id 落 `c_ai_usage_log`
- **边界**：单批 ≤ 256 条，单条 ≤ 8192 token，超限 400

#### 知识库检索 API（只读）
- **文件**：新建 `KbApiController.java`（v1 命名空间独立控制器，与网页管理端点分开）
- **路径**：`POST /api/ai/v1/retrieval`
- **参数**：`query`（必填 ≤8000 字）、`knowledgeBaseIds`（可选，不传走 Key 签发人可见的全部库）、`topK`（1-50，默认 20）、`minScore`（可选，按融合分过滤）
- **实现**：复用 `HybridRetrievalService.search(query, diag, kbIds, false)`，返回 `{chunks: [{id, text, score, title, titlePath, docId, kbId, chunkIndex, images}], total, warnings?}`
- **鉴权**：API Key 白名单放行；知识库可见性按签发人权限过滤（显式点名的不可见库按不存在处理）
- **注意**：不执行重排（重排耗时长），只返回混合融合后的分数；检索链路降级在 `warnings` 如实说明

#### 知识库管理 API（有限写入）
- **文件**：`KbApiController.java`（与检索同控制器）
- **路径与 scope**：
  - `POST /api/ai/v1/kbs` → 创建库（需要 `scope: kb.write`）
  - `POST /api/ai/v1/kbs/{id}/documents` → 上传文档（multipart，需要 `scope: kb.write`）
  - `GET /api/ai/v1/kbs` / `GET /api/ai/v1/kbs/{id}` → 库列表/详情（`scope: kb.read`，精简视图不外泄解析参数原文）
  - `GET /api/ai/v1/kbs/{id}/documents` → 列文档（`scope: kb.read`，含状态/进度/失败原因）
- **scope 设计**：API Key 签发时勾选能力范围（默认集=chat/embedding/kb.read/workflow.run，不含 kb.write）；`kb.write` 显式勾选才放开，收紧下一次调用即生效

#### API Key scope 机制
- **文件**：`ApiKey` model 新增 `scopes` 字段（JSON 数组：`["chat", "embedding", "kb.read", "kb.write", "workflow.run"]`）
- **Service**：`ApiKeyService` 新增 scope 校验方法，`OpenAiCompatController`/新 Controller 在 API Key 通道时调用校验
- **前端**：ApiKeysPanel 创建/编辑 Key 时显示 scope 多选
- **schema**：不涉及设置页参数，无需改 `config-schema.json`

#### SecurityConfig 白名单更新
- 在 `isPublicUserEndpoint` 中逐条列名新增：
  - `POST /api/ai/v1/embeddings`
  - `POST /api/ai/v1/retrieval`
  - `POST /api/ai/v1/kbs`（scope 校验在 Controller 内）
  - `POST /api/ai/v1/kbs/{id}/documents`
  - `GET /api/ai/v1/kbs`
  - `GET /api/ai/v1/kbs/{id}`
  - `GET /api/ai/v1/kbs/{id}/documents`

### 前端嵌入增强

#### 嵌入模式参数化
- **文件**：`ShareChatPage.vue`
- **增强**：现有 `?embed=1` 基础上增加参数：
  - `?embed=1&no-tool=1` → 隐藏工具执行区
  - `?embed=1&compact=1` → 更紧凑的单列布局
  - `?embed=1&color=...` → 主题色覆盖（品牌色）
  - `?embed=1&placeholder=...` → 输入框占位提示
  - `?embed=1&autoOpen=1` → 加载后自动聚焦输入框

#### 嵌入加载器（轻量 JS）
- **产物**：`wenqu-embed.js`（约 4KB，零依赖）
- **位置**：`web/public/wenqu-embed.js`，随前端构建发布到站点根 `{origin}/wenqu-embed.js`（与 `/s/{token}` 同源，iframe 地址由脚本自身 src 推导，宿主不需要重复配置站点地址）
- **用法**：
  ```html
  <script src="https://wenqu.host/wenqu-embed.js"></script>
  <script>
    WenquChat.init({
      token: 'abc123',        // 分享 token（/s/{token}）
      position: 'bottom-right',
      color: '#3b82f6',
      placeholder: '问我任何问题',
      height: 560,
    })
  </script>
  ```
- **实现**：浮动按钮 → 点击展开 iframe（`/s/{token}?embed=1&...`，懒加载：首次展开才建会话）；面板自带标题栏与关闭按钮；`init()` 返回 `{open, close, toggle, destroy}` 供宿主程序化控制；token 只接受安全字符集、color 只接受合法 hex（不给注入面）

#### 对接文档扩展
- **文件**：`ApiDocsPage.vue`
- **新增章节**：
  - Embeddings API 文档 + curl/SDK 示例
  - 知识库检索 API 文档
  - 知识库管理 API 文档
  - 前端嵌入指南（iframe 直接用 + JS Widget）
  - API Key scope 说明

### SDK

#### JavaScript/TypeScript SDK
- **产物**：`@wenqu/sdk`（npm 包）
- **结构**：
  ```
  @wenqu/sdk/
  ├── src/
  │   ├── index.ts
  │   ├── chat.ts          // ChatCompletions / Responses 封装
  │   ├── embeddings.ts    // Embeddings API
  │   ├── retrieval.ts     // 知识库检索
  │   ├── workflow.ts      // 工作流触发
  │   ├── types.ts         // 类型定义
  │   └── errors.ts        // 错误码映射
  ├── examples/
  │   ├── chat.ts
  │   ├── embeddings.ts
  │   ├── retrieval.ts
  │   └── widget.ts        // 嵌入 Widget 的程序化控制
  └── package.json
  ```
- **核心 API**：
  ```ts
  const client = new Wenqu({ baseUrl: 'https://wenqu.host/ai', apiKey: 'sk-xxx' })
  const resp = await client.chat.completions.create({
    model: 'agent-id',
    messages: [{ role: 'user', content: '你好' }],
    stream: true,
  })
  for await (const chunk of resp) console.log(chunk)

  const emb = await client.embeddings.create({ input: ['你好', '世界'], model: 'provider/embedding-model' })

  const hits = await client.retrieval.search({ query: '部署流程', knowledgeBaseIds: ['kb-xxx'], topK: 10 })

  const run = await client.workflows.run({ workflowId: 'wf-xxx', input: { key: 'val' } })
  ```
- **Stream 处理**：用 `fetch` + `ReadableStream` 解析 SSE，提供 async iterator
- **错误处理**：把 OpenAI 风格的 error 统一映射为 typed Error

#### Python SDK
- **产物**：`wenqu-ai`（PyPI 包）
- **核心 API**（对齐 JS 版）：
  ```python
  from wenqu import WenquClient
  client = WenquClient(base_url="https://wenqu.host/ai", api_key="sk-xxx")

  # Chat（同步）
  resp = client.chat.completions.create(model="agent-id", messages=[{"role":"user","content":"你好"}])
  print(resp.choices[0].message.content)

  # Chat（流式）
  for chunk in client.chat.completions.create(model="agent-id", messages=[...], stream=True):
      print(chunk.choices[0].delta.content or "", end="")

  # Embeddings
  emb = client.embeddings.create(input=["你好", "世界"], model="provider/emb-model")
  print(emb.data[0].embedding)

  # LangChain 集成
  from wenqu.langchain import WenquEmbeddings, WenquChat
  embeddings = WenquEmbeddings(client=client, model="provider/emb-model")
  llm = WenquChat(client=client, model="agent-id")
  ```
- **LangChain/LlamaIndex 适配层**：作为可选子模块

#### SDK 交付位置
- SDK 代码放在仓库 `sdk/js/` 和 `sdk/python/` 子目录下（独立 package.json/pyproject.toml，不混进 web/ 和 src/）
- 发布流程：CI 构建 → 手动发布到 npm/pypi（私有部署场景亦可只做内部 registry 或直接归档到仓库）

---

## 依赖与考量

### Embeddings API 的模型引用选择
问渠的向量模型是**按知识库绑定**的（`kb.embedding_ref`），没有全局默认向量模型。对外暴露 Embeddings API 时要求调用方显式传 `model=providerId/modelId`（与现有 OpenAI 兼容端点 `model` 必填的口径一致）；引用无效返回 400 并附可用向量模型清单获取方式。

### Embeddings 计量与配额
- 向量用量单独记账（`usage_log` 新增 `type=embedding`），与聊天分开统计
- 复用 `DynamicEmbeddingModel.QuotaAwareEmbeddingModel` 的额度登记逻辑

### 知识库写入的安全边界
- 默认 API Key 只有 `kb.read` 权限（只读检索 + 读库详情 + 读文档列表）
- `kb.write` 需要显式勾选，且写入操作的文件类型/大小/并发与内部一致（15MB/文件、docx/pdf/xlsx/ppt/txt/md/csv、并发抢占队列）
- 写入触发的解析/向量化走后台队列（`ParseQueueService`），API 同步返回 taskId，客户端可轮询状态

### SDK 的 auth 设计
- JS SDK 同时支持 API Key（后端服务调用）和分享 token（前端嵌入场景）两种模式
- Python SDK 主要面向后端，只用 API Key
- 嵌入 Widget 只用分享 token，不接触 API Key（前端存 Key 有泄露风险）

### 版本与向后兼容
- 所有 `/v1/` 端点承诺向后兼容，字段只增不删
- SDK 按 semver，minor 增功能、major 破坏

---

## 验证清单

### 后端 API
- [x] `POST /api/ai/v1/embeddings`：单条/批量/错误码/限流/计量台账（`mvn compile` + `mvn test` 通过）
- [x] `POST /api/ai/v1/retrieval`：query/多库/topK/权限过滤（调用方看不到的库不返回）
- [x] `POST /api/ai/v1/kbs`：创建 + scope 校验 + 无 scope 拒绝
- [x] `POST /api/ai/v1/kbs/{id}/documents`：上传 + 返回文档行（status=2 解析中，轮询列表看进度）+ 无 scope 拒绝
- [x] SecurityConfig 白名单端点在 API Key 通道下可达、JWT 通道下同样可达
- [x] API Key scope 未勾选时，端点返回 403 + 明确提示（文案点名缺哪个 scope 与去哪儿开）

### 前端嵌入
- [x] `ShareChatPage` 参数化：no-tool/compact/color/placeholder/autoOpen（color 只接受合法 hex，脏值忽略）
- [x] `wenqu-embed.js`：浮动按钮 → 展开/收起（iframe 懒加载）、`{open,close,toggle,destroy}` 程序化控制、主题色覆盖、移动端窄屏适配
- [x] ApiDocsPage 新增章节：Embeddings / 知识库 API / 前端嵌入 / 权限与 scope（`npm run build` 通过）
- [ ] 真机浏览器回归（各参数组合渲染、挂件在宿主页面展开收起）——待起本地栈人工确认

### SDK
- [x] JS SDK（`sdk/js`，@wenqu/sdk）：tsc strict 零错误；node:test 32/32 通过（本地 mock server 全链路）
- [x] Python SDK（`sdk/python`，wenqu-ai）：unittest 40/40 通过（含 base64/dimensions/256 条上限）
- [x] 两个 SDK 都对 mock server 完成全链路（含 OpenAI 错误体与平台包络两种形状）
- [x] README 有可复制运行的快速入门示例（中文，含 scope 说明）
- [ ] LangChain 适配层对真实 langchain-core 的行为测试（可选依赖未装，已验证语法与缺依赖提示路径）
- [ ] 对真实后端 8090 的端到端联调（需本地栈起服务 + 登记向量模型）

### 回归
- [x] `mvn test` 全绿（含修正 OpenAiCompatToolCallsTest 构造参数）
- [x] `web npm test` 8/8、`npm run build` 通过

---

## 风险与处理

| 风险 | 影响 | 处理 |
|------|------|------|
| Embeddings 模型引用选择不直观 | 外部调用方不知道该传什么 model | `GET /api/ai/v1/models/embeddings` 返回当前账号可用的向量模型清单（脱敏） |
| 知识库写入被滥用（批量塞垃圾文件） | 存储和解析资源被吃爆 | 并发抢占队列 + 单租户存储配额（已有 `TenantDiskInventory`）+ 单 Key 日写入上限 |
| SDK 与后端版本不同步 | 字段对不上导致功能坏 | 请求带 `X-Wenqu-Sdk-Version`，后端在 debug 日志里比对；API 新增字段一律追加、不删除 |
| 嵌入 Widget 的 XSS/CSRF | 安全风险 | iframe 的 src 只接受已知 token 格式；postMessage 校验 origin；不接受任意 URL 注入 |
| 公开 Embeddings API 被当免费向量服务滥用 | 额度被快速耗尽 | 限流（`rateLimitService` 按 Key 桶）+ 配额（`ModelQuotaService` 供应商级额度）+ Key 可停 |
