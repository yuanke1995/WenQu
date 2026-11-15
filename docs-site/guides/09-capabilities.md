---
title: 能力清单（问答 / 检索 / 工作流 / 嵌入 / MCP）
group: 指南
order: 9
lead: v1 注册表里已有的能力，以及两条不在注册表内、但仍然可用的既有链路。
---

## 端点全景

| 分类 | 端点 | scope |
|---|---|---|
| 问答 | `POST /v1/chat/completions` · `POST /v1/responses` | `chat` |
| 运行 | `POST /v1/agents/{id}/runs` 及其 6 个派生端点 | `runs.read` / `runs.write` |
| 检索 | `POST /v1/retrieval` | `kb.read` |
| 知识库 | `GET/POST /v1/kbs…` | `kb.read` / `kb.write` |
| 向量 | `POST /v1/embeddings` · `GET /v1/models/embeddings` | `embedding` |
| 工作流 | `POST /v1/workflows/{id}/run` · `GET …/run/{runId}` | `workflow.run` |
| 工作流入站 | `POST /v1/workflows/webhook/{token}` | 令牌即凭据 |
| 自检 | `GET /v1/me` · `GET /v1/openapi.json` | 任意有效 Key |

完整签名（含参数与错误）见[API 参考](#api-reference)，那份表是构建期从契约快照生成的，不手写。

## 问答：原生 SSE 链路 {#cap-chat}

`POST /api/ai/chat` 是站内同款的原生流式接口——它**不在 v1 注册表内**（属于既有问答链路，按问答白名单放行），
所以严格说不带「只增不删」承诺；但阶段事件比 `chat/completions` 丰富，L1 前端集成与内部代理用的就是它。

```bash
curl -N -X POST __BASE__/api/ai/chat \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: sk-你的Key" \
  -d '{"question":"如何创建评分组件？","agentId":"<智能体id>"}'
```

请求体主要字段：`question`（必填 ≤8000 字）、`sessionId`（续聊）、`agentId`、`model`、`deepThink`、
`planMode`、`reasoningLevel`、`images`（≤9 张 data URL）、`attachments`（`fileId` 来自
`POST /api/ai/chat/attachment`）、`mentions`（kb/doc/agent 三类 @ 引用）、`historyRefs`。

事件：`thinking` / `thinking_done`、`token`、`image`、`tool_status` / `tool_output`、
`plan` / `todo` / `retrieved` / `usage` / `warn`，收尾 `done`，失败 `error`。
`done` 里带 `sources`（引用块，可用于溯源）、`messageId`、`finalContent`（权威全文）、`finalImages`。

相关端点：`POST /api/ai/session/new`（建会话）、`POST /api/ai/chat/stop`、`POST /api/ai/feedback`、
`GET /api/ai/session/{id}`。

> 这条链路没有 run 身份、没有审批回传、没有续传序号。需要过程能力就上 [run 原语](#run-events)。

## 检索：把问渠当搜索引擎 {#cap-retrieval}

```bash
curl -X POST __BASE__/api/ai/v1/retrieval \
  -H "X-Api-Key: sk-你的Key" -H "Content-Type: application/json" \
  -d '{"query":"报销流程","knowledgeBaseIds":["<kbId>"],"topK":5}'
```

- `query` 必填（≤8000 字）；`knowledgeBaseIds` 省略 = 签发人可见的全部库；`topK` 1–50（默认 20）；`minScore` 按融合分过滤。
- 返回 `{chunks:[{id,text,score,title,titlePath,docId,kbId,chunkIndex,images}], total, warnings?}`。
- 不执行重排；链路降级会在 `warnings` 里如实说明，不会假装正常。

## 知识库：读写分离 {#cap-kb}

| 端点 | scope | 说明 |
|---|---|---|
| `GET /v1/kbs` | `kb.read` | 可见库列表（含文档数） |
| `GET /v1/kbs/{id}` | `kb.read` | 库详情 |
| `GET /v1/kbs/{id}/documents` | `kb.read` | 文档列表与解析进度（0 生效 / 1 弃用 / 2 解析中 / 3 失败） |
| `POST /v1/kbs` | `kb.write` | 建库：`name` + `embeddingRef`（向量模型引用，必填） |
| `POST /v1/kbs/{id}/documents` | `kb.write` | multipart 传 `file`；同步回文档行，解析走后台队列 |

```bash
curl -X POST __BASE__/api/ai/v1/kbs/<kbId>/documents \
  -H "X-Api-Key: sk-你的Key" \
  -F "file=@员工手册.docx"
```

`kb.write` **默认不开通**：签发时不勾就没有写库能力（403 点名缺哪项）。可见性与网页端一致——
签发人看不到的库与文档，从这里同样拿不到。

## 向量：OpenAI 兼容 {#cap-embedding}

```bash
curl -X POST __BASE__/api/ai/v1/embeddings \
  -H "X-Api-Key: sk-你的Key" -H "Content-Type: application/json" \
  -d '{"input":["你好，世界"],"model":"<providerId>/<modelId>"}'
```

- `model` 必填（问渠向量按知识库绑定，没有全局默认）；可用清单 `GET /v1/models/embeddings`。
- `input` 单批 ≤256 条、单条 ≤8192 token；`encoding_format` 支持 `float` / `base64`；`dimensions` 可裁剪。
- 响应是 OpenAI 标准形状，任何 OpenAI SDK 改 `base_url` 即可用；用量按 `kind=embedding` 单独记账。

## 工作流：当 RPC 调 {#cap-workflow}

```bash
curl -X POST __BASE__/api/ai/v1/workflows/<工作流id>/run \
  -H "X-Api-Key: sk-你的Key" -H "Content-Type: application/json" \
  -d '{"inputs":{"city":"杭州"}}'
```

- `inputs` 对应开始节点入参，可整体省略。
- **同步返回** run 全量：`status / outputs / nodeTraces / durationMs`。
- 失败不抛 500：回 `status=failed` 的 run，看 `error` 与 `nodeTraces` 定位失败节点。
- 约束：工作流对签发人**可见**才可触发（不可见一律 404，不泄露存在性）；未发布 → 400；
  含人工审核节点 → 400（外部调用方没有审批交互面）。
- 补查：`GET /v1/workflows/{id}/run/{runId}`。另有入站触发
  `POST /v1/workflows/webhook/{token}`（令牌即凭据，不走 Key）。

## 前端嵌入：分享 token，不碰 Key {#cap-embed}

两条路，凭据都是**分享 token**（智能体「公开发布」得到）：

```html
<script src="https://your-host/wenqu-embed.js"></script>
<script>
  WenquChat.init({
    token: '<分享token>',        // 公开发布链接 /s/ 后面那段
    title: '在线咨询',
    color: '#3b82f6',
    placeholder: '问我任何问题',
    position: 'bottom-right'     // 或 bottom-left
  })
</script>
```

```html
<iframe src="https://your-host/s/<分享token>?embed=1&color=%233b82f6"
        width="420" height="640" style="border:0;border-radius:12px"></iframe>
```

- 加载器约 3KB、零依赖；浮动按钮点击才懒加载对话面板。
- `WenquChat.init()` 返回 `{open, close, toggle, destroy}`。
- iframe 参数（`embed=1` 时生效）：`no-tool=1` / `compact=1` / `color=#rrggbb` / `placeholder=…` / `autoOpen=1`。
- 游客额度闸照常生效：每条分享有每日轮次与 token 上限，超限 429。
- **嵌入脚本挂在前端站点，不在后端 API 实例上**——本地起前后端两个进程时，`src` 要指向前端 5800，不是 8090。

## MCP 接入 {#cap-mcp}

平台级 MCP 入口把问渠能力暴露成标准 MCP Server（Streamable HTTP），凭据是 `Authorization: Bearer`：

```json
{
  "mcpServers": {
    "wenqu": { "url": "__BASE__/mcp", "headers": { "Authorization": "Bearer sk-你的Key" } }
  }
}
```

固定元工具集：`wenqu_ask`、`wenqu_search_knowledge`、`wenqu_list_knowledge_bases`、`wenqu_list_agents`。

启用条件（缺一不可）：① 该 Key 在个人设置里打开了「MCP」开关；② 管理员在「系统设置 → MCP 服务（双向）」
开启「对外提供 MCP 端点」；③ 对外域名访问时，前端域名要进「允许的 Origin」（Claude / Cursor 这类本地进程不带
Origin，不受影响）。可见范围与签发人在网页上看到的完全一致。

> 想让某一个智能体单独对外，用它的「公开发布 → MCP 端点」`__BASE__/mcp/{token}`——凭据是分享 token，不是 Key。
