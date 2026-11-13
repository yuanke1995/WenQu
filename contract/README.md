# v1 开放契约：口径与变更纪律

这份是**对外承诺的唯一口径**。文档站首屏、SDK README、应用内「对接文档」三处引用同一份措辞，
不在各自页面里重写一遍——写三份就一定会漂移。

## 三层接入与承诺分级

| 层 | 谁持凭据 | 形态 | 承诺 |
|---|---|---|---|
| **L0 · 零代码嵌入** | 分享 token（浏览器不持 Key） | `wenqu-embed.js` / iframe | **正式** |
| **L1 · 前端深度集成** | 业务后端代理，浏览器零凭据 | `client.native` 事件协议 | **实验性** |
| **L2 · 后端集成** | API Key / 服务账号（只在服务端） | v1 REST + webhook + MCP | **正式** |

- **正式** = 只增不删 + 变更日志 + 至少两个 minor 的废弃期。
- **实验性** = 字段可改，接口变更提前在文档站公告。
- 只正式承诺 L0 / L2：三层全部正式会把维护面直接翻三倍。L1 在 run 契约冻结并稳定两周后转正（A3）。

**凭据边界一句话**：API Key 只在服务端，浏览器零凭据。L1 + L2 可以共用同一个业务后端，Key 仍然不进浏览器。
Key 恒以签发人本人身份发问，不存在机器号或改绑别人的口子。

## 兼容策略

- **v1 端点只增不删**：删除、改 HTTP 方法、改 scope、改 `keyAccessible` 都是破坏性变更。
- **SDK 0.x**：允许 minor 破坏，但必须提前公告；`@wenqu/sdk` 与 `wenqu-ai` 版本号同步
  （闸门见 `scripts/check-sdk-version.mjs`）。
- **v1 chat/completions** 是「无状态、文本、收集型单块下发」的兼容面；逐 token 真增量、
  长任务、人在回路只承诺在 run 原语上（A 期，契约冻结后）。别把两种承诺混用。

## 契约快照与变更流程

真源 = 后端源码里的 `@OpenApi` + Spring 路由注解（`OpenApiRegistry` 启动期扫的也是这一份），
运行时由 `GET /api/ai/v1/openapi.json` 实时生成；应用内「对接文档」页直接读它，不维护第二份端点清单。

```bash
node scripts/check-openapi-contract.mjs --check    # CI 每次跑：只增不删 + 新增要登记
node scripts/check-openapi-contract.mjs --update   # 改了契约后跑：更新快照 + 追加变更日志草稿
```

- `--check` 发现**删除端点 / scope 变化 / 可达性变化**直接失败；**新增端点**也要求先跑 `--update`，
  否则等于承诺了却没留记录、变更日志漏条目。
- `--update` 写 `contract/openapi.v1.json`，并往 `contract/CHANGELOG.md` 追加「改了什么」的草稿条目；
  **「为什么改」由人工补**，脚本不编。
- CI 的 `contract` job 跑 `--check`；契约检查失败时不要改断言，先按上面流程更新快照。

## 待拍板（方案 09 节）

| 编号 | 问题 | 当前建议 |
|---|---|---|
| D1 | L1 何时转正 | A3：run 契约冻结并稳定两周后 |
| D2 | run 默认是否持久会话 | 默认无状态，显式传 `sessionId` 才持久 |
| D3 | 租户级对外总开关 | 已按「要」落地：`apiOpen.enabled`（系统设置 → 开放 API → 对外集成） |
