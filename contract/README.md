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

## 发布排期（B1，2026-11-15 定）

**SDK 发包排在整个方案最后**：暂无 npm / PyPI 账号，且契约尚未冻结。契约没冻结就发 0.x，
等于把还没稳定的形状写进「只增不删 + 废弃期」的承诺里，之后每改一次都要公告一次。

- 在此之前 B1 只保留仓库内可自证的部分：双端 `X-Wenqu-Sdk-Version` 版本头、
  `scripts/check-sdk-version.mjs` 四处版本一致性闸门、CI 的 `sdk` job（strict build + 两套测试）。
- **不发** npm `@wenqu/sdk` / PyPI `wenqu-ai`，**不出** 私网 tgz / wheel，也不做
  「干净容器装包跑 quickstart」这类发布验收。CI 里没有 publish job，别自行补。
- 开 B1 的前置条件（两条同时满足）：① A3 / M3 的 run 契约冻结并稳定两周；
  ② 发布账号与私网分发渠道就绪。版本号仍按 0.x 在仓库内同步，只代表对齐，不代表已发布。

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

## 文档站（B2，2026-11-15）

对外文档只有一份产物，两处展示：独立部署的文档站 + 应用内「对接文档」页（iframe 同一份 HTML）。
这一页不再手写正文——以前手写的那份是「第二份真源」，改一处必然漂移。

- 构建：`node scripts/build-docs.mjs`（`--check` 只校验，`--out=` 换目录）；零第三方依赖。
- 真源三处，构建期合并：`contract/openapi.v1.json`（端点表，自动）+ `docs-site/guides/*.md`（正文，人写）
  + `samples/*` 与 `contract/CHANGELOG.md`（样例与变更记录）。
- 产物 `web/public/docs/` **提交入库**：应用内页面靠它工作，不能要求每个部署都先跑一次文档构建。
- CI 的 `docs` job 跑 `--check`：改了真源却没重建就失败。**不要改断言**，重新构建并提交。
- 正文里的服务地址一律写 `__BASE__`，由 `docs.js` 在浏览器按 `?base=` → `localStorage` →
  `location.origin + '/ai'` 三级回落替换；同一份产物因此在本地、同域线上、独立站三种场景都成立。

**口径不变**：三层承诺分级、只增不删、SDK 发包排最后，三处（文档站 / SDK README / 应用内页）引用同一份措辞。

## 待拍板（方案 09 节）

| 编号 | 问题 | 当前建议 |
|---|---|---|
| D1 | L1 何时转正 | A3：run 契约冻结并稳定两周后 |
| D2 | run 默认是否持久会话 | 默认无状态，显式传 `sessionId` 才持久 |
| D3 | 租户级对外总开关 | 2026-11-14 退役：`apiOpen.enabled` 设置项已删，Key 通道恒开（各 Key 可单独停用） |
