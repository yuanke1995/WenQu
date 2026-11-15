/**
 * 样例起步脚本：真登录 → 真签发一把样例专用 Key → 自检 → 写回 samples/.env。
 *
 * 刻意不假装「三条命令创建 Key、智能体、知识库」：
 * - /api/ai/api-key 拒绝 X-Api-Key（防泄露 Key 自我续命），必须先拿登录 JWT；
 * - 创建智能体/知识库/发布工作流/发布分享都是登录态内部端点，且知识库还要先登记
 *   向量模型供应商（embeddingRef 必填）；这些由人在界面里预置，脚本只负责查出来并报缺。
 *
 * 用法：cd samples && cp .env.example .env（填登录账号），然后 node seed/seed.mjs
 */
import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const HERE = dirname(fileURLToPath(import.meta.url));
const ENV_PATH = join(HERE, "..", ".env");

function parseEnv(text) {
  const out = {};
  for (const line of text.split("\n")) {
    const s = line.trim();
    if (!s || s.startsWith("#")) continue;
    const at = s.indexOf("=");
    if (at < 0) continue;
    out[s.slice(0, at).trim()] = s.slice(at + 1).trim();
  }
  return out;
}

async function api(path, { method = "GET", token, key, body } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (key) headers["X-Api-Key"] = key;
  if (body) headers["Content-Type"] = "application/json";
  const res = await fetch(path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { throw new Error(`${path} 返回的不是 JSON：${text.slice(0, 200)}`); }
  if (!res.ok) {
    const err = json?.error || {};
    throw new Error(`${path} HTTP ${res.status}: ${err.message || json.msg || text.slice(0, 200)}`);
  }
  return json;
}

const env = parseEnv(await readFile(ENV_PATH, "utf8").catch(() => ""));
const base = (env.WENQU_BASE_URL || "http://127.0.0.1:8090/ai").replace(/\/+$/, "");
if (!env.WENQU_ADMIN_ID || !env.WENQU_ADMIN_PASSWORD) {
  console.error("请在 samples/.env 填 WENQU_ADMIN_ID 与 WENQU_ADMIN_PASSWORD（登录态是签发 Key 的前提）");
  process.exit(1);
}

// 1) 登录：平台包络 {success,data:{token,user}}，不是 OpenAI 形状
const login = await api(`${base}/api/ai/auth/login`, {
  method: "POST",
  body: { identifier: env.WENQU_ADMIN_ID, password: env.WENQU_ADMIN_PASSWORD },
});
const token = login?.data?.token;
if (!token) throw new Error("登录响应里没有 data.token，确认账号密码与实例状态");

// 2) 签发样例 Key：scope 全开（runs.* / kb.write 都不在默认集，必须显式给）
const SCOPES = ["chat", "embedding", "kb.read", "kb.write", "workflow.run", "runs.read", "runs.write"];
const created = await api(`${base}/api/ai/api-key`, {
  method: "POST", token,
  body: { name: "samples-seed", scopes: SCOPES },
});
const key = created?.data?.apiKey;
if (!key) throw new Error("签发响应里没有 data.apiKey（明文只回这一次）");

// 3) 自检：用刚签出的 Key 真调一次 /v1/me，证明 scope 与归属都生效
const me = await api(`${base}/api/ai/v1/me`, { key });
console.log(`[seed] Key=${me.keyPrefix || created.data.keyPrefix} scopes=${(me.scopes || []).join(",")} actAs=${me.actAs}`);

// 4) 查已发布分享（widget-static 要的 token）；缺就报缺，不伪造
let shareToken = env.WENQU_SHARE_TOKEN || "";
if (!shareToken && env.WENQU_AGENT_ID) {
  try {
    const pub = await api(`${base}/api/ai/agent/${env.WENQU_AGENT_ID}/publish`, { token });
    shareToken = pub?.data?.token || "";
  } catch (e) {
    console.warn(`[seed] 读取分享配置失败（widget-static 需人工填 WENQU_SHARE_TOKEN）：${e.message}`);
  }
}

// 5) 写回 .env（只覆盖本脚本负责的三个键，其余原样保留）
let text = await readFile(ENV_PATH, "utf8").catch(() => "");
const updates = {
  WENQU_API_KEY: key,
  WENQU_SHARE_TOKEN: shareToken,
  WENQU_CALLBACK_SECRET: env.WENQU_CALLBACK_SECRET || "local-dev-secret",
};
for (const [k, v] of Object.entries(updates)) {
  const re = new RegExp(`^${k}=.*$`, "m");
  text = re.test(text) ? text.replace(re, `${k}=${v}`) : `${text.trimEnd()}\n${k}=${v}\n`;
}
await writeFile(ENV_PATH, text, "utf8");

console.log("[seed] 已写入 samples/.env：WENQU_API_KEY" + (shareToken ? " / WENQU_SHARE_TOKEN" : ""));
console.log("[seed] 仍需人工预置（脚本查不到也不假装创建）：");
console.log("  · 已发布工作流 id → WENQU_WORKFLOW_ID（draft 不能触发，见 backend-python/README.md）");
console.log("  · 向量模型供应商与至少一个有文档的知识库 → backend-python 的检索才有结果");
console.log("  · 智能体 callback_url / callback_secret → backend-node 的 webhook（生产必须公网地址）");
