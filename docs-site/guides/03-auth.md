---
title: 认证与安全
group: 开始
order: 3
lead: 一把 Key 代表一个人。它能碰的面是固定的白名单，不是「这个账号在界面上能点什么」。
---

## 地址拼法

后端 context-path 是 `/ai`，完整地址 = **服务地址 + `/ai` + 接口路径**：

```bash
# 本机实例
__BASE__/api/ai/v1/me

# 线上（同一台机器反代到 /ai）同形：
https://your-host/ai/api/ai/v1/me
```

## 认证头

```bash
X-Api-Key: sk-xxxxxxxx
```

- `Authorization: Bearer sk-…` 等价（给 OpenAI SDK 用，`Authorization` 里必须是 `sk-` 开头）。
- MCP 入口例外：它只认 `Authorization: Bearer`。
- 分享 token 是另一条通道（L0 / 公开链接），不与 Key 混用。

## 两类响应体

**v1 命名空间**（`/api/ai/v1/**`）：成功是裸对象或业务形状，错误统一 OpenAI 形状：

```json
{
  "error": {
    "message": "该 API Key 未开通「runs.write」能力；请在问渠「个人设置 → API 密钥」编辑该 Key 的权限范围后重试",
    "type": "permission_error",
    "code": "permission_error",
    "request_id": "3f8a1c2e…"
  }
}
```

**非 v1 的既有接口**（如原生问答 `/api/ai/chat`）：走平台包络 `{"success":true,"data":…}` /
`{"success":false,"msg":"原因"}`。两套形状的边界就在命名空间上，SDK 已各自抹平，手写 curl 时别按同一种解。

## scope：Key 的能力范围

每把 Key 一份 scope，签发时勾选、之后可在「个人设置 → API 密钥」编辑。只约束 Key 通道（登录态不受影响），
收紧**立即生效**。

| scope | 默认开通 | 覆盖能力 |
|---|---|---|
| `chat` | 是 | 问答（`/v1/chat/completions`、`/v1/responses`） |
| `embedding` | 是 | 向量化（`/v1/embeddings`） |
| `kb.read` | 是 | 知识检索与知识库只读（`/v1/retrieval`、`/v1/kbs` 读侧） |
| `workflow.run` | 是 | 触发已发布工作流（`/v1/workflows`） |
| `kb.write` | **否** | 建库与上传文档（`/v1/kbs` 写侧） |
| `runs.read` | **否** | 查 run 状态 / 事件流 / 产物 |
| `runs.write` | **否** | 建 run、插话、停止、提交审批 |

`kb.write`、`runs.read`、`runs.write` **不进默认集**：存量 Key 不应因为平台升级就自动多出写库或跑长任务的权限。
缺哪个就 403 点名哪个，报错原文里写着去哪一列勾。

## Key 碰不到什么

Key 只开放**问答链路**。以下是硬性拒绝（HTTP 403，当场给出原因，不会静默落到别的鉴权层）：

| 端点前缀 | 为什么不给 |
|---|---|
| `/api/ai/api-key/**` | Key 管理——否则泄露的 Key 能给自己签发续命、改绑定换身份 |
| `/api/ai/user/**` | 改密 / 资料 / 偏好 / 个人设置——贴在配置文件里的密钥不该拥有账号自服务能力 |
| `/api/ai/auth/**` | 登录鉴权（唯一例外 `GET /auth/me`，只读身份自检） |
| `/api/ai/skill/**`、`/api/ai/mcp/**`、`/api/ai/market/**`、`/api/ai/scheduled/**`、`/api/ai/credential/**` | 能力配置面：技能 / MCP / 市场 / 定时任务 / 工作流凭据 |
| `/api/ai/session-shares/**`、`DELETE /api/ai/session(s)…` | 分享管理、删历史（保留审计线索） |
| `/api/ai/agent/{id}/publish · /share` | 智能体发布与分享配置 |

白名单之外的一切端点对 Key 一律 403——即使签发人在界面上是管理员。**Key 的 API 面恒为问答白名单，不受角色影响。**

## 契约注册表：v1 只认登记过的端点

`/api/ai/v1/**` 的唯一真源是后端的开放契约注册表（`@OpenApi` 注解 + Spring 路由，启动期扫描）：

- v1 命名空间里**出现未标注的路由直接启动失败**——新增端点必须显式声明它对集成方的可见性；
- 未登记的 v1 路径对 Key 一律拒绝，错误文案会指向 `GET /api/ai/v1/openapi.json`；
- 契约变更走 CI：删除端点 / 改 scope / 改可达性一律失败，新增必须先跑 `--update` 登记。

## 集成方守则

- 一把 Key 一个用途，别多系统共用；不用了先「停用」而不是删除——停用保留审计线索，删除记录即消失。
- Key 等于签发人的问答身份与额度，按敏感凭据保管（配置文件 / 密钥管理服务），不进前端代码与日志。
- 明文由服务端加密保管（列表点「眼睛」可回显）；怀疑泄露就停用重签。
- 轮询注意限频，见[错误码与限频](#errors)。
