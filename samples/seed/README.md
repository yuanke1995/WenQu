# seed · 签发一把样例专用 Key

```bash
cd samples
cp .env.example .env        # 填 WENQU_BASE_URL / WENQU_ADMIN_ID / WENQU_ADMIN_PASSWORD
node seed/seed.mjs
```

脚本真做的事：登录拿 JWT → 签发 `samples-seed` Key（**7 个 scope 全开**，
因为 `runs.read` / `runs.write` / `kb.write` 不在默认集）→ 用新 Key 真调一次
`GET /v1/me` 自检 → 查已发布分享 token → 把结果写回 `samples/.env`。

## 为什么不是「三条命令创建 Key、智能体、知识库」

方案原文是三条命令起步，但按真实链路做不到，脚本不假装做到：

- `POST /api/ai/api-key` **拒绝 `X-Api-Key`**（防一把泄露的 Key 给自己续命），
  必须先有登录 JWT —— 鸡生蛋无解，最少「登录 + 签发 + 自检」三步。
- 创建智能体、发布工作流、发布分享都是登录态内部端点，不是 v1；
  知识库 `embeddingRef` 必填，得先登记向量模型供应商。
- 本地也没有 `dynamic-form` 那种 `/login?user=admin` 免口令捷径，
  只有 `POST /api/ai/auth/login`（需 identifier/password）与首次部署的
  `POST /api/ai/auth/initialize`。

所以脚本把**能查的查出来并写进 `.env`，查不到的明确报缺**：
已发布工作流 id、向量模型供应商与有文档的知识库、智能体的 `callback_url`/`callback_secret`。

## 失败路径

- 账号密码错 → `POST /auth/login` 返回平台包络错误，脚本把 `msg` 原样抛出。
- 实例没起 / `WENQU_BASE_URL` 少了 `/ai` → 404 或请求失败，先 `curl $BASE/api/ai/v1/openapi.json`。
- 想缩小权限：改脚本里的 `SCOPES`，例如只留 `["chat","runs.read","runs.write"]`；
  之后打 `kb.write` 端点会收到 403「该 API Key 未开通「kb.write」能力…」。
