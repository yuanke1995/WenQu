/**
 * L1 前端深度集成样例：浏览器 → 自家后端 → 问渠，浏览器零凭据。
 *
 * 零依赖（Node ≥18 内置 http + fetch）。这个进程是「自家后端」：
 *  - 校验自家登录态（演示用固定 X-Session-Token，生产换真实会话）；
 *  - 按白名单把少数路径映射到 v1，绝不做通用反代；
 *  - 只在服务端注入 X-Api-Key，客户端传来的 Authorization / X-Api-Key / X-Session-Token
 *    一律不转发；
 *  - SSE 逐块转发，不缓冲整条流。
 *
 * 参照 dynamic-form 的 CopilotWenquProxyController 白名单思路，但那套转发的是
 * /api/ai/** 内部 native 链路；这里转发的是 /api/ai/v1/** 正式契约。
 */
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const HERE = dirname(fileURLToPath(import.meta.url));

function parseEnv(text) {
  const out = {};
  for (const line of text.split("\n")) {
    const s = line.trim();
    if (!s || s.startsWith("#")) continue;
    const at = s.indexOf("=");
    if (at > 0) out[s.slice(0, at).trim()] = s.slice(at + 1).trim();
  }
  return out;
}

const env = parseEnv(await readFile(join(HERE, "..", ".env"), "utf8").catch(() => ""));
const BASE = (env.WENQU_BASE_URL || "http://127.0.0.1:8090/ai").replace(/\/+$/, "");
const KEY = env.WENQU_API_KEY;
const SESSION = env.PROXY_SESSION_TOKEN || "demo-session";
if (!KEY) {
  console.error("缺少 WENQU_API_KEY：先在 samples/ 下跑 node seed/seed.mjs");
  process.exit(1);
}

/** 白名单：客户端路径 → 上游 v1 路径；不在表内一律 400 */
const ALLOW = {
  "GET /api/me": "/api/ai/v1/me",
  "GET /api/kbs": "/api/ai/v1/kbs",
  "POST /api/retrieval": "/api/ai/v1/retrieval",
  "POST /api/chat": "/api/ai/v1/chat/completions",
};

function upstreamFor(method, pathname) {
  const direct = ALLOW[`${method} ${pathname}`];
  if (direct) return { method, path: direct };
  const run = /^\/api\/runs\/([A-Za-z0-9_-]{1,64})$/.exec(pathname);
  if (method === "GET" && run) {
    return { method: "GET", path: `/api/ai/v1/runs/${encodeURIComponent(run[1])}` };
  }
  return null;
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url || "/", "http://localhost");
  const pathname = url.pathname;

  // 静态页：只服务一个文件，不做目录遍历
  if (req.method === "GET" && (pathname === "/" || pathname === "/index.html")) {
    try {
      const html = await readFile(join(HERE, "public", "index.html"));
      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
      return res.end(html);
    } catch {
      res.writeHead(404); return res.end("index.html 不存在");
    }
  }

  // 自家登录态：浏览器带的是这个，不是问渠的 Key
  if (req.headers["x-session-token"] !== SESSION) {
    res.writeHead(401, { "Content-Type": "application/json; charset=utf-8" });
    return res.end(JSON.stringify({ error: "未登录或登录已过期" }));
  }
  const target = upstreamFor(req.method || "GET", pathname);
  if (!target) {
    res.writeHead(400, { "Content-Type": "application/json; charset=utf-8" });
    return res.end(JSON.stringify({ error: "该路径不在样例代理的白名单内" }));
  }

  let body;
  if (req.method !== "GET" && req.method !== "HEAD") {
    const chunks = [];
    for await (const c of req) chunks.push(c);
    body = Buffer.concat(chunks);
  }
  // 只注入自家服务端的凭据；客户端头一概不带过去
  const headers = {
    "X-Api-Key": KEY,
    "X-Wenqu-Sdk-Version": "0.1.0",
  };
  const rid = String(req.headers["x-request-id"] || "")
    .replace(/[^A-Za-z0-9_-]/g, "").slice(0, 64);
  if (rid) headers["X-Wenqu-Request-Id"] = rid;
  if (body?.length) headers["Content-Type"] = String(req.headers["content-type"] || "application/json");
  if (String(req.headers.accept || "").includes("text/event-stream")) headers.Accept = "text/event-stream";

  try {
    const up = await fetch(BASE + target.path, {
      method: target.method, headers, body: body?.length ? body : undefined,
    });
    const ct = up.headers.get("content-type") || "application/json";
    res.writeHead(up.status, {
      "Content-Type": ct,
      "Cache-Control": up.headers.get("cache-control") || "no-store",
    });
    if (!up.body) return res.end();
    // SSE 与 JSON 都逐块转发：流式不会被压成一整块
    for await (const chunk of up.body) res.write(chunk);
    res.end();
  } catch (e) {
    res.writeHead(502, { "Content-Type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({ error: `上游请求失败：${e.message}` }));
  }
});

const port = Number(process.env.PORT || 3011);
server.listen(port, "127.0.0.1", () => {
  console.log(`[sample] frontend-proxy 监听 http://127.0.0.1:${port}（浏览器零凭据）`);
});
