# 问渠对外集成样例

按《对外集成执行方案》B3 落地的**可启动宿主**，不是代码片段。四个样例对应三层接入形态：

| 样例 | 形态 | 凭据 | 演示什么 | 本地可否实机跑 |
|---|---|---|---|---|
| `widget-static/` | L0 零代码嵌入 | 分享 token（浏览器不持 Key） | `wenqu-embed.js` 浮窗 + iframe | 可（需分享 token 与前端站点 5800） |
| `frontend-proxy/` | L1 前端深度集成 | 自家后端持 Key，浏览器零凭据 | 白名单代理、登录态隔离、SSE 转发 | 可 |
| `backend-node/` | L2 后端集成 | API Key（只在服务端） | 建 run、`run.finished` 验签与幂等 | 可（回调需本地配置，见下） |
| `backend-python/` | L2 后端集成 | API Key（只在服务端） | 知识检索 + 工作流触发 | 检索可；工作流需先发布 |

`seed/` 负责签发一把样例专用 Key 并生成 `.env`。

## 先说三条现实约束（别照方案原文硬套）

1. **SDK 尚未发包**（B1 排在项目最后，暂无 npm/PyPI 账号）。样例用仓库内引用：
   JS 是 `file:../../sdk/js`，Python 是 `-e ../../sdk/python`。JS SDK 的 `dist/` 不入库，
   所以安装样例依赖前**必须先构建 SDK**：
   ```bash
   cd ../../sdk/js && npm ci && npm run build && cd ../../samples/backend-node && npm install
   ```
   B1 发包后，把 `file:` 改成 `@wenqu/sdk`、把 `-e` 改成 `wenqu-ai` 即可，其余代码不动。
2. **`seed/` 不是「三条命令」。** 创建 Key 只能走登录 JWT 通道（`/api/ai/api-key` 明确拒绝
   `X-Api-Key`，否则一把泄露的 Key 能给自己续命），所以必须先登录拿令牌再签 Key；
   向量模型供应商、已发布工作流、分享 token 都得人工预置。脚本把这些**查出来并写进 `.env`**，
   缺哪样就明确报缺哪样，不假装创建成功。
3. **webhook 回调地址在保存期过 SSRF 校验**，生产必须是公网可达地址；
   `127.0.0.1` / `localhost` / 内网段一律拒绝。`backend-node/README.md` 给了一条
   **仅本地验证用**的直写库命令绕过保存期校验（投递期不校验），生产不要这么做。

## 起步

```bash
cp .env.example .env          # 填 WENQU_BASE_URL / 登录账号 / 智能体 id
cd seed && node seed.mjs      # 真登录、真签 Key、写回 ../.env、报告还缺什么
```

`WENQU_BASE_URL` 要带 context-path（本机是 `http://127.0.0.1:8090/ai`）。样例进程与问渠实例
是两个进程，浏览器只跟样例进程说话。
另外 **L0 的嵌入脚本在前端站点**（本机 Vite dev `5800`），不在后端 API 实例上；
`widget-static/README.md` 里的 `origin` 别填 8090/8111。

## 失败路径（照后端真实文案）

v1 端点的错误体统一是 OpenAI 形状 `{"error":{"message","type","code","request_id"}}`：

| 现象 | 原因 / 文案要点 | 怎么办 |
|---|---|---|
| 401 `authentication_error` | 没带有效 `X-Api-Key` | 检查 `.env` 的 Key、代理有没有注入 |
| 403 `permission_error` | 「该 API Key 未开通「runs.write」能力；请在问渠「个人设置 → API 密钥」编辑该 Key 的权限范围后重试」 | `runs.read`/`runs.write`/`kb.write` **不在默认集**，签发时要显式勾选 |
| 403 `permission_error` | 「该端点不在开放契约注册表内，默认拒绝；请从 GET /api/ai/v1/openapi.json 查已开放端点」 | 代理白名单别放行未登记路径 |
| 404 `not_found_error` | 知识库/工作流/分享链接不存在，或 run 不属于这把 Key（后端刻意让「不存在」与「非本人」同码） | 换 id 或换 Key |
| 409 `api_error` | 审批卡「已处理或已失效（可能已超时）」 | 重新拉事件流取新的 `approvalId` |
| 400 `invalid_request_error` | `query 不能为空` / 「工作流「X」尚未发布」 | 补参数，或先发布工作流 |

成功体**不统一**：`v1/runs/*` 是裸对象，`v1/retrieval` 与 `v1/workflows/*` 是
`{success,data,msg}` 平台包络——两个 SDK 已各自抹平（`jsonRequest` vs `envelopeRequest`），
手写 curl 时别按同一种解。

## 承诺口径

L0 / L2 正式（只增不删、变更日志、废弃期）；L1 实验性（字段可改，变更提前公告），
稳定的 run 契约冻结后才摘标。契约真源见 `contract/openapi.v1.json` 与
`contract/README.md`；`GET /api/ai/v1/openapi.json` 是运行时同源清单。
