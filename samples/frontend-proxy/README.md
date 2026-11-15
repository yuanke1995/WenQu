# frontend-proxy · 浏览器零凭据的完整代理拓扑

浏览器 → 自家后端（这个进程，持 Key）→ 问渠 v1。零依赖，Node ≥18 直接跑。

```bash
set -a && . ../.env && set +a
node server.mjs                       # http://127.0.0.1:3011
```

浏览器打开 `http://127.0.0.1:3011/`，页面只认自家的 `X-Session-Token`，
Network 里**不会出现任何 API Key**。等价 curl：

```bash
# 有登录态：代理注入 Key，返回 /v1/me 的真实 scope / 发问身份
curl -s -H 'X-Session-Token: demo-session' http://127.0.0.1:3011/api/me

# 没登录态：401（浏览器的凭据由自家后端管）
curl -s -i http://127.0.0.1:3011/api/me | head -1

# 白名单外：400，不做通用反代
curl -s -H 'X-Session-Token: demo-session' http://127.0.0.1:3011/api/agent/list

# 检索与流式问答
curl -s -H 'X-Session-Token: demo-session' -H 'Content-Type: application/json' \
  -d '{"query":"问渠","topK":3}' http://127.0.0.1:3011/api/retrieval
curl -sN -H 'X-Session-Token: demo-session' -H 'Accept: text/event-stream' \
  -H 'Content-Type: application/json' -d '{"model":"<agentId>","messages":[{"role":"user","content":"你好"}],"stream":true}' \
  http://127.0.0.1:3011/api/chat
```

## 白名单与安全边界

`ALLOW` 只映射 `/api/me`、`/api/kbs`、`/api/retrieval`、`/api/chat` 与
`GET /api/runs/{id}`，其余 400「该路径不在样例代理的白名单内」。这是刻意的：
通用反代等于把整条 Key 权限暴露给浏览器能触达的任意路径。

- 客户端传来的 `Authorization` / `X-Api-Key` / `X-Session-Token` **一律不转发**；
  上游只收到服务端注入的 `X-Api-Key` 与 `X-Wenqu-Sdk-Version`。
- 自家登录态这里用固定 `PROXY_SESSION_TOKEN` 演示，生产必须换成真实会话校验。
- SSE 逐块 `res.write`，不缓冲整条流，所以 `/api/chat` 是真流式。
- 参照了 `dynamic-form` 的 `CopilotWenquProxyController` 白名单思路，但它转发的是
  `/api/ai/**` 内部 native 链路（L1 实验性协议）；这里转发 `/api/ai/v1/**` 正式契约。

## 失败路径

- 401「未登录或登录已过期」：自家登录态没过，跟问渠无关。
- 上游 403「该 API Key 未开通「kb.read」能力…」：代理原样透传状态码与 OpenAI 形状错误体。
- 上游 403「该端点不在开放契约注册表内」：白名单里别加未登记路径。
