---
title: 事件契约（run 原语）
group: 参考
order: 5
lead: 审批、产物、插话、停止、计划都在同一条 run 上。这是对外唯一的运行原语，不复制引擎。
---

## 为什么要 run

`chat/completions` 是「无状态、文本、收集型单块下发」的兼容面。它没有任务身份、没有过程事件、
没有人在回路的入口。run 原语是把**已有的站内运行过程**投影成一条对外可订阅的链路：

- 一条 run = 一次智能体运行，有稳定 id、有终态、有事件序列；
- 审批 / 产物 / 插话 / 停止都挂在**同一个 runId** 上，不另开接口族；
- 实现是投影（复用 native 会话与断线接流），引擎没变——所以站内看到的行为与对外一致。

## 七个端点

| 方法 | 路径 | scope | 要点 |
|---|---|---|---|
| POST | `/v1/agents/{agentId}/runs` | `runs.write` | `Idempotency-Key` 去重；默认无状态，显式传 `sessionId` 才持久 |
| GET | `/v1/runs/{id}` | `runs.read` | 状态 / 输出 / 错误，与 `run.done` 同口径 |
| GET | `/v1/runs/{id}/events` | `runs.read` | SSE，`Last-Event-ID` 续传，`id:` = `seq` |
| POST | `/v1/runs/{id}/approvals` | `runs.write` | 统一 `decision` 枚举，按 `approvalId` 提交 |
| GET | `/v1/runs/{id}/artifacts` | `runs.read` | 只回可下载项 + 当场换发的签名 URL（1 小时） |
| POST | `/v1/runs/{id}/steer` | `runs.write` | 运行中插话（纯文本），框架收下才落库 |
| POST | `/v1/runs/{id}/stop` | `runs.write` | 幂等；已结束返 200 + 终态 |

## 建一次 run

```bash
curl -X POST __BASE__/api/ai/v1/agents/<智能体id>/runs \
  -H "X-Api-Key: sk-你的Key" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: order-20261115-001" \
  -d '{"question":"帮我核对这份报销单","sessionId":"<可选，续聊传上一次的>"}'
```

```json
{
  "runId": "9f2c7a4b8e1d4f6ba0c3d5e7f9012345",
  "status": "running",
  "sessionId": "…",
  "agentId": "…",
  "createdAt": "2026-11-15T19:06:12"
}
```

- `question` 必填；`sessionId` 不传就是一次性运行（D2：默认无状态）。
- 同一 `Idempotency-Key` 重放直接回**同一个 runId**，返回体多一个 `idempotent: true`，不新起一轮。
- 出站账号恒为 Key 签发人；智能体停用会当场 400（不能靠建新 run 绕开）。

## 事件表

`event:` 行是下面 9 个名字之一，`id:` 是 `seq`（从 1 连续），`data:` 是带 `runId` 的扁平 JSON。

| 事件 | 含义 | `data` 关键字段 |
|---|---|---|
| `run.started` | 运行已建立（永远是 `seq=1`） | `runId` `sessionId` `agentId` `status=running` |
| `token` | 正文**真增量** | `delta` |
| `tool.status` | 工具执行进度 | `native:{name,status,elapsedMs,stepId}` |
| `plan.updated` | 计划最新快照（不做逐条 diff） | `plan` |
| `approval.required` | 需要人决定 | `approvalId` `kind` `payload` |
| `artifact.created` | 产生了可下载产物 | `artifact:{id,filename,ext,size,preview,description}` |
| `approval.timed_out` | 审批超时（非超时的 `warn` 不投） | `code=approval_timeout` `message` |
| `run.done` | 终态成功 | `status` `finalContent` `usage` `sources` `artifacts` |
| `run.error` | 终态失败 | `status=failed` `code` `message` `request_id` |

`thinking` 默认不对外；由智能体配置显式开启才发。**新增事件不升级 SDK**：SDK 的 `onEvent` 是兜底的，
未知事件原样交回，契约只增不删。

## 审批：回同一条 run

```text
event: approval.required
id: 12
data: {"runId":"9f2c…","approvalId":"ap_88","kind":"tool_approval","payload":{…}}
```

```bash
curl -X POST __BASE__/api/ai/v1/runs/<runId>/approvals \
  -H "X-Api-Key: sk-你的Key" -H "Content-Type: application/json" \
  -d '{"approvalId":"ap_88","decision":"approve"}'
```

`decision` 统一为 `approve | reject | revise | supersede | answer | ignore`；`kind` 是
`tool_approval` / `plan_approval` / `ask_user`。`answer` 对应问答卡，其他按计划/工具审批处理。

- 卡片已处理或已超时 → **409**，文案是「已处理或已失效」。重新拉事件流取新的 `approvalId`，
  不要拿旧 id 重试。
- 审批卡不在本副本时由网关内部转发到宿主副本，调用方无感。
- 超时后终态按 `run.error(code=approval_timeout)` 收口，不是永远 `running`。

## 续传：`Last-Event-ID` + seq

```bash
# 首次
curl -N __BASE__/api/ai/v1/runs/<runId>/events -H "X-Api-Key: sk-你的Key"

# 断线重连：把最后收到的 id 带回来
curl -N __BASE__/api/ai/v1/runs/<runId>/events \
  -H "X-Api-Key: sk-你的Key" -H "Last-Event-ID: 42"
```

- `id:` 从 1 连续递增，**没有空洞**；客户端按 `id` 去重即可，不需要自己数帧。
- 回放缓冲保留 **10 分钟**（Redis，短 TTL）。过期后流里回一帧
  `run.error(code=resume_expired)`，提示「请重拉 GET /runs/{id}」——不会把过期伪装成一条新流从头开始。
- 终止条件：收到 `run.done` / `run.error` 即结束；已结束的 run 再订阅，回放完直接关流。

## 终态查询

```json
{
  "runId": "9f2c…",
  "status": "succeeded",
  "sessionId": "…",
  "agentId": "…",
  "createdAt": "2026-11-15T19:06:12",
  "finishedAt": "2026-11-15T19:06:41",
  "errorCode": null,
  "errorMessage": null,
  "output": { "finalContent": "…", "usage": {…}, "sources": […], "artifacts": […] }
}
```

`status` ∈ `running | succeeded | failed | stopped`。停止后的终态是 `stopped`（不是 `succeeded`），
`run.done` 的 `status` 同步改判——按状态分支处理的重投逻辑才不会误判。

## 与 chat/completions 的边界

| | `chat/completions` | `runs` |
|---|---|---|
| 承诺 | 正式（形状只增不删） | 正式 |
| 增量 | 收集型，非真逐 token | `token` 是真 `delta` |
| 任务身份 | 无 | `runId` + 终态 + 幂等键 |
| 人在回路 | 无 | `approval.required` + `/approvals` |
| 产物 | 只在最终文本里 | `artifact.created` + `/artifacts` 签名 URL |
| 长任务 | 单次请求周期 | 事件流可断线重连，终态可补查 |

> 别把两种承诺混用：要过程能力就用 runs，别指望 OpenAI 兼容面回审批卡。
