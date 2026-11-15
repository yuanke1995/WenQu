# v1 契约变更日志

## 2026-11-13

- 初始基线：15 个 v1 端点纳入契约快照。

> 为什么改：<!-- 人工补；自动条目只描述「改了什么」 -->

## 2026-11-15

- 新增 `GET /api/ai/v1/runs/{id}`（scope: runs.read）：查询运行状态与输出（与 run.done 同口径）
- 新增 `GET /api/ai/v1/runs/{id}/events`（scope: runs.read）：SSE 事件流（Last-Event-ID 续传，seq 连续）
- 新增 `POST /api/ai/v1/agents/{agentId}/runs`（scope: runs.write）：创建一次智能体运行（run）

> 为什么改：A 期 A1 第一批——把 native 问答链路投影成正式 v1 run 契约（建轮 / 状态 / 事件流），
> 详见 design/plans/对外开放运行原语A1实现方案.md。新增 runs.read / runs.write 两个 scope 不进默认集。

## 2026-11-15

- 新增 `GET /api/ai/v1/runs/{id}/artifacts`（scope: runs.read）：列出本次运行的可下载产物（含签名 URL）
- 新增 `POST /api/ai/v1/runs/{id}/approvals`（scope: runs.write）：提交审批卡（approve/reject/revise/supersede/answer/ignore）
- 新增 `POST /api/ai/v1/runs/{id}/steer`（scope: runs.write）：运行中插话（纯文本，框架收下才落库）
- 新增 `POST /api/ai/v1/runs/{id}/stop`（scope: runs.write）：停止运行（幂等，终态收成 stopped）

> 为什么改：A 期 A1 第二批——补齐 run 控制面（审批 / 产物 / 插话 / 停止），让长任务与人在回路能走正式 v1 契约；同时收口 run.finished 出站回调，新增 approval.timed_out 过程事件。详见 design/plans/对外开放运行原语A1实现方案.md §10.2。


## 2026-11-15

- 修正（不改端点、不改 scope）：`POST /api/ai/v1/workflows/{id}/run` 与 `GET …/run/{runId}` 的响应里
  `inputs` / `outputs` / `nodeTraces` 由「JSON 字符串」改为真正的 JSON（对象 / 数组）。

> 为什么改：这三个字段在库里是 String（存的是序列化后的 JSON），实体直接序列化就成了「字符串套 JSON」——
> `res.data.outputs.answer` 取不到值，而 SDK 的类型声明（`WorkflowRun.outputs: Record<string, unknown>`）
> 与本仓文档写的都是对象。实测同步触发与补查都把出参回成了 `"{\"answer\":\"…\"}"`。
> 对外端点改为解析后返回，内部管理端点 `/api/ai/workflow/**` 形状不变（前端那里按字符串 `JSON.parse`）。
> 解析失败原样回字符串，不因形状问题把 run 变成 500。
