# widget-static · L0 最短路径

纯 HTML，无构建、无依赖、无 API Key。用 HTTP 打开（**不能 `file://`**）：

```bash
cd samples/widget-static
python3 -m http.server 3013
# 浏览器打开（token 换成已发布智能体的分享 token）
# http://127.0.0.1:3013/?origin=http://127.0.0.1:5800&token=<分享token>
```

页面从 URL 读 `origin` 与 `token`，动态注入 `<script src="{origin}/wenqu-embed.js">`，
再调 `WenquChat.init({token, ...})`。右下角浮窗出现即 L0 跑通。

**`origin` 必须填问渠的前端站点**（本机 Vite dev 是 `http://127.0.0.1:5800`），
不是后端 API 实例 `8090` / `8111`：后端只提供 `/ai/api/**`，
`wenqu-embed.js` 与 SPA 路由 `/s/{token}` 都在前端站点上。
本地实测：`5800/wenqu-embed.js` 200、`5800/s/{token}` 200、
`8111/ai/api/ai/share/{token}/info` 200，而 `8090/wenqu-embed.js` 是 404。

## 两个容易踩的坑

- **脚本必须从问渠站点同源加载**。`wenqu-embed.js` 的 `siteBase()` 是从
  `document.currentScript.src` 推导同源根的，iframe 的 `/s/{token}` 要打到那个源上；
  把脚本复制到宿主站点下会导致 iframe 404。跨域加载 script 本身没问题。
- **参数是分享 token，不是 API Key**。先登录问渠 → 智能体「发布分享」
  （`POST /api/ai/agent/{id}/publish`，需要 `agent:publish` 权限），拿到 token；
  `seed/` 会尝试把它读出来写进 `samples/.env`，也可以直接从 `c_ai_agent_share.token` 查。
  token 格式要匹配 `/^[A-Za-z0-9_-]{6,64}$/`。

## 失败路径

- 没带 `?token=` → 页面明确提示，不静默留白。
- `origin` 不可达 → 提示脚本加载失败；先用
  `curl http://127.0.0.1:8090/wenqu-embed.js` 确认。
- token 无效/已停用 → 浮窗里显示「分享链接无效或已停止访问」；
  这个错误来自免登录的 `/api/ai/share/{token}/info`，跟 API Key 权限无关。

## 承诺

L0 是正式承诺（只增不删、变更日志、废弃期）。它只提供问答框，不涉及审批、产物、
插话这些过程能力——那些走 `frontend-proxy`（L1）或 `backend-node`（L2）。
