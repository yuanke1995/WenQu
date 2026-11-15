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

