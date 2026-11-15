---
title: 错误码与限频
group: 指南
order: 8
lead: v1 的错误体只有一种形状，`code` 可编程、`message` 说人话、`request_id` 能拿来查日志。
---

## 错误体

```json
{
  "error": {
    "message": "该 API Key 未开通「runs.write」能力；请在问渠「个人设置 → API 密钥」编辑该 Key 的权限范围后重试",
    "type": "permission_error",
    "code": "permission_error",
    "request_id": "3f8a1c2e-…"
  }
}
```

非 v1 的既有接口仍是平台包络 `{"success":false,"msg":"…"}`——两套形状的边界在命名空间上。

## 状态码

| 状态码 | `code` | 含义与处理 |
|---|---|---|
| 400 | `invalid_request_error` | 参数或业务校验失败（超字数 / 附件过期 / 引用不可见 / 工作流未发布 / 智能体停用） |
| 401 | `authentication_error` | 没带有效 `X-Api-Key`，或登录门禁拦下 |
| 403 | `permission_error` | 三种可能：Key 无效；端点在拒绝名单；v1 端点未登记进开放契约。文案会点名 |
| 404 | `not_found_error` | 资源不存在**或不在可见范围**（刻意同码，避免拿 403/404 差异探测别人的 runId） |
| 409 | `api_error` | 审批卡已处理或已失效（可能已超时）。重拉事件流取新的 `approvalId` |
| 429 | `rate_limit_error` | 限频，窗口 60 秒。退避重试，或让管理员调 `ratelimit.chatPerMinute` |
| 500 | `internal_error` | 服务端异常。拿 `request_id` 对日志，别只贴一句「500 了」 |

## run 链路的专用 code

| `code` | 出现在 | 含义 |
|---|---|---|
| `approval_timeout` | `approval.timed_out`（过程事件） | 审批卡超时：这一步未执行，本轮仍以 `run.done` 收口。要当失败处理由集成方自己判定 |
| `resume_expired` | `run.error` | 事件回放缓冲过期（保留 10 分钟），改拉 `GET /runs/{id}` 取终态与输出 |
| `run_interrupted` | `run.error` | 运行已中断（服务重启 / 副本切换 / 进程被杀）：状态由 `GET /runs/{id}` 在超过 30 分钟且回放缓冲不可用时收敛，不会永远 `running` |
| `invalid_request` / `authentication_error` / `permission_error` / `not_found` / `rate_limit_error` | HTTP 错误体 | 与状态码一一对应 |

## 缺 scope 的文案到底写了什么

> 该 API Key 未开通「runs.write」能力；请在问渠「个人设置 → API 密钥」编辑该 Key 的权限范围后重试

> 该端点不在开放契约注册表内，默认拒绝；请从 GET /api/ai/v1/openapi.json 查已开放端点，
> 或让管理员把该能力登记进开放契约

> 该 API Key 只开放问答链路（问答 / 会话 / 反馈 / 引用溯源等）：这个端点不对 Key 开放

三类 403 的区别很重要：**缺 scope** 去勾选就能解决；**未登记**是平台没开放这个能力；
**拒绝名单**是结构性设计（Key 永远不该有账号自服务能力）。别把三者都当「权限不够，找管理员开」。

## 限频

| 桶 | 默认值 | 说明 |
|---|---|---|
| `chat` | 10 次/分钟 | 问答、建 run、上传共用这个桶 |
| `embedding` | 60 次/分钟 | 每请求最多 256 条文本 |
| `workflow` | 按租户配置 | 触发已发布工作流 |

限频按「桶 + 租户 + 账号/IP」分桶，所以 `GET /v1/me` 里的 `limits` 是**当前配置**，
不是这把 Key 的私有配额。超限统一 429，退避重试即可；不要对 429 做无限重试。

## 排查顺序

1. `request_id` 有没有留下？没有就先在客户端日志里补上。
2. `GET /v1/me`：scope 对不对、代表谁、限流多少。
3. `GET /api/ai/v1/openapi.json`：这个端点到底登记了吗。
4. 还是不懂？把 `request_id` + 请求路径 + 时间点给管理员，服务端日志按它一秒定位。
