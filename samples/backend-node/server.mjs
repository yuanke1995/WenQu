/**
 * L2 后端集成样例：Express + JS SDK。
 *
 * 两件真事，都不是空 handler：
 *  1. POST /runs —— 用 SDK 真建一次 v1 run（可带 Idempotency-Key）；
 *  2. POST /webhook —— 真收 run.finished：保留 raw body 做 HMAC-SHA256 验签、
 *     常量时间比较、按 X-Wenqu-Run-Id 幂等去重，重复投递不产生第二条业务记录。
 *
 * 签名口径：X-Wenqu-Signature: sha256=<hex(HmacSHA256(secret, rawBodyBytes))>；
 * 没有时间戳/nonce，签名基准就是完整原始 body —— 所以必须先收 Buffer 再验，
 * 绝不能「解析 JSON 再 stringify」（键序与空格一变签名就废）。
 */
import express from "express";
import crypto from "node:crypto";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { WenquClient } from "@wenqu/sdk";

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
const SECRET = env.WENQU_CALLBACK_SECRET || "local-dev-secret";
const AGENT_ID = env.WENQU_AGENT_ID;
const KEY = env.WENQU_API_KEY;
if (!KEY || !AGENT_ID) {
  console.error("缺少 WENQU_API_KEY / WENQU_AGENT_ID：先在 samples/ 下跑 node seed/seed.mjs");
  process.exit(1);
}
const client = new WenquClient({ baseUrl: BASE, apiKey: KEY });

/** 已收回调：runId → 业务记录。生产换数据库唯一键（服务端那边是 uk_wh_run_id）。 */
const deliveries = new Map();

const app = express();

// 注意：这个路由必须在 express.json() 之前，且只对本路由用 raw，保留原始字节
app.post("/webhook", express.raw({ type: () => true }), (req, res) => {
  const raw = Buffer.isBuffer(req.body) ? req.body : Buffer.from("");
  const got = req.get("X-Wenqu-Signature") || "";
  const expected = "sha256=" + crypto.createHmac("sha256", SECRET).update(raw).digest("hex");
  const a = Buffer.from(got), b = Buffer.from(expected);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) {
    console.warn("[webhook] 验签失败，丢弃");
    return res.status(401).json({ error: "invalid signature" });
  }
  if (req.get("X-Wenqu-Event") !== "run.finished") {
    return res.json({ ok: true, ignored: req.get("X-Wenqu-Event") || "" });
  }
  let payload;
  try {
    payload = JSON.parse(raw.toString("utf8"));
  } catch {
    return res.status(400).json({ error: "invalid JSON" });
  }
  const runId = req.get("X-Wenqu-Run-Id") || payload.runId;
  if (!runId) return res.status(400).json({ error: "missing runId" });
  if (deliveries.has(runId)) {
    // 至少一次投递语义：重投直接 2xx，不重复落业务记录
    return res.json({ ok: true, duplicate: true, runId });
  }
  const record = {
    runId, event: req.get("X-Wenqu-Event"), status: payload.status,
    finalContent: payload.finalContent, agentId: payload.agentId,
    receivedAt: new Date().toISOString(),
  };
  deliveries.set(runId, record);
  console.log(`[webhook] 已落库 run=${runId} status=${payload.status} content=${JSON.stringify(payload.finalContent)}`);
  return res.json({ ok: true, duplicate: false, runId });
});

app.post("/runs", express.json(), async (req, res) => {
  const { question, idempotencyKey } = req.body || {};
  if (!question) return res.status(400).json({ error: "question 必填" });
  try {
    const run = await client.runs.create(AGENT_ID, { question, idempotencyKey });
    return res.json(run);
  } catch (e) {
    return res.status(e.status || 500).json({
      error: e.message, code: e.code, requestId: e.requestId,
    });
  }
});

app.get("/runs/:id", async (req, res) => {
  try {
    return res.json(await client.runs.get(req.params.id));
  } catch (e) {
    return res.status(e.status || 500).json({ error: e.message, code: e.code });
  }
});

/** 验证用：查看这个宿主到底收到了几条回调 */
app.get("/deliveries", (_req, res) => res.json({ count: deliveries.size, items: [...deliveries.values()] }));

const port = Number(process.env.PORT || 3010);
app.listen(port, () => console.log(`[sample] backend-node 监听 http://127.0.0.1:${port}`));
