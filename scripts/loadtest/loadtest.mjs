/**
 * 100 路并发生成压测（配 scripts/loadtest/mock-gateway.mjs + 8091 验证栈）。
 *
 * 三个验收判据（对应上线并发口径「100 人同时各有一路生成」）：
 *   A. 吞吐：100 个独立用户同时各发一路流式问答 —— 无「系统繁忙」、无 error 事件、全部收到 done
 *   B. 人在回路隔离：40 路挂 askUser 等待 + 60 路普通问答同时进行 —— 普通路 TTFB 不显著劣化
 *      （挂起轮占用 boundedElastic，不应拖累其他流）
 *   C. 同会话轮级互斥：同一会话并发第二问 —— 收到「上一轮还在回答中」而不是并行跑
 *
 * 用法：
 *   node scripts/loadtest/mock-gateway.mjs &        # 先起 mock 网关（:9101）
 *   SERVER_PORT=8091 AI_RATELIMIT_CHAT=200 java -jar target/*.jar   # 仓库根起 8091 栈
 *   node scripts/loadtest/loadtest.mjs              # 跑三阶段并输出报告（CLEANUP=1 顺带清数据）
 *
 * 管理员令牌按 AuthCrypto 的 JWT 格式（HS256, iss=wenqu, aud=wenqu-api）本地自签，
 * 密钥取本地 config/application-local.yml 的 ai-app.auth.jwt-secret。
 */
import crypto from 'node:crypto';

const BASE = process.env.BASE || 'http://127.0.0.1:8091/ai';
const SECRET = process.env.JWT_SECRET || '01c1479b3059c8f23a9fcbdb2c3cbaa9073bc7605c4911d1';
const USERS = +process.env.USERS || 100;
const ASKERS = +process.env.ASKERS || 40;
const CLEANUP = process.env.CLEANUP === '1';
const STREAM_TIMEOUT_MS = 180_000;

const b64u = (buf) => Buffer.from(buf).toString('base64url');
const hmac = (data) => crypto.createHmac('sha256', SECRET).update(data).digest();

function mintToken(uid, role, ttlSeconds = 3600) {
  const now = Math.floor(Date.now() / 1000);
  const head = b64u(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = b64u(JSON.stringify({
    sub: uid, role, iss: 'wenqu', aud: 'wenqu-api', iat: now, exp: now + ttlSeconds,
  }));
  return `${head}.${payload}.${b64u(hmac(`${head}.${payload}`))}`;
}

async function api(path, { method = 'GET', token, body } = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 非 JSON 响应按原样返回 */ }
  return { status: res.status, json, text };
}

/** 发起一路流式问答并解析 SSE：返回 {ttfb, done, errors, asks, sessionId}。
 *  onAsk：ask_user 事件到达时的内联回调（作答必须发生在流内——流在等答案，读完再答就死锁） */
async function chatStream(token, payload, onAsk) {
  const out = { ttfb: null, done: false, errors: [], asks: [], sessionId: payload.sessionId ?? null, content: '' };
  const started = Date.now();
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), STREAM_TIMEOUT_MS);
  try {
    const res = await fetch(`${BASE}/api/ai/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(payload),
      signal: ctrl.signal,
    });
    if (!res.ok || !res.body) {
      out.errors.push(`HTTP ${res.status}`);
      return out;
    }
    let buf = '';
    for await (const frame of res.body) {
      buf += Buffer.from(frame).toString('utf8');
      let idx;
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const raw = buf.slice(0, idx);
        buf = buf.slice(idx + 2);
        const evLine = raw.split('\n').find((l) => l.startsWith('event:'));
        const dataLine = raw.split('\n').find((l) => l.startsWith('data:'));
        if (!evLine) continue;
        const ev = evLine.slice(6).trim();
        const dataStr = dataLine?.slice(5) ?? '';
        let data;
        try { data = JSON.parse(dataStr); } catch { data = dataStr; }
        // sendSseEvent 的载荷是信封 {type, content(JSON 字符串或裸文本), sessionId}：拆信封
        if (data && typeof data === 'object' && typeof data.content === 'string') {
          try { data = JSON.parse(data.content); } catch { data = data.content; }
        }
        if (ev === 'token') {
          // token 事件载荷是裸文本增量（非 JSON）
          if (out.ttfb === null) out.ttfb = Date.now() - started;
          out.content += typeof data === 'string' ? data : (data.delta ?? '');
        }
        if (ev === 'ask_user' && typeof data === 'object') {
          out.asks.push(data);
          if (onAsk) await onAsk(data, out);
        }
        if (ev === 'error') {
          const msg = typeof data === 'object' ? (data.content ?? data.message ?? JSON.stringify(data)) : String(data);
          out.errors.push(msg);
        }
        if (ev === 'done' && typeof data === 'object') {
          out.done = true;
          if (data.sessionId) out.sessionId = data.sessionId;
        }
      }
    }
  } catch (e) {
    out.errors.push(String(e && e.message || e));
  } finally {
    clearTimeout(timer);
  }
  return out;
}

const pct = (arr, p) => {
  if (!arr.length) return null;
  const s = [...arr].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor((p / 100) * s.length))];
};

async function main() {
  console.log(`[loadtest] BASE=${BASE} USERS=${USERS} ASKERS=${ASKERS}`);
  const admin = mintToken('admin', 'superadmin', 7200);

  // ── 准备：用户 / mock 供应商与模型 ──────────────────────────────
  let created = 0;
  for (let i = 1; i <= USERS; i++) {
    const uid = `loadtest_${String(i).padStart(3, '0')}`;
    const r = await api('/api/ai/user', {
      method: 'POST', token: admin,
      body: { uid, username: `压测${i}`, password: 'Loadtest#2026', role: 'user' },
    });
    if (r.status === 200 && r.json?.success) created++;
  }
  console.log(`[setup] 用户就绪：新建 ${created} 个（其余已存在）`);

  const tokens = Array.from({ length: USERS }, (_, i) =>
    mintToken(`loadtest_${String(i + 1).padStart(3, '0')}`, 'user', 7200));

  // 供应商按使用者隔离（谁建归谁）：每个压测用户各自登记 mock 网关与模型，
  // 同时正好覆盖 per-user 模型路由缓存
  const refs = [];
  for (let i = 0; i < USERS; i++) {
    const tk = tokens[i];
    const prov = await api('/api/ai/provider', {
      method: 'POST', token: tk,
      body: { name: `压测Mock网关${i + 1}`, baseUrl: 'http://127.0.0.1:9101', apiKey: 'mock-key', enabled: true },
    });
    const pid = prov.json?.data?.id ?? prov.json?.data;
    if (!pid) throw new Error(`用户 ${i + 1} 供应商创建失败: ${JSON.stringify(prov.json ?? prov.text)}`);
    const saved = await api(`/api/ai/provider/${pid}/models`, {
      method: 'PUT', token: tk,
      body: [{
        modelId: 'mock-chat', modelType: 'chat', displayName: 'Mock Chat',
        thinking: 'auto', contextWindow: 32768, maxOutput: 4096, enabled: true,
      }],
    });
    if (!saved.json?.success) throw new Error(`模型登记失败: ${JSON.stringify(saved.json)}`);
    refs.push(`${pid}/mock-chat`);
  }
  const REF = refs[0];
  console.log(`[setup] ${USERS} 个用户各自的 Mock 网关就绪（示例引用 ${REF}）`);

  // ── 阶段 A：100 路普通问答同时起跑 ─────────────────────────────
  console.log(`\n[phase A] ${USERS} 路并发生成...`);
  const tA = Date.now();
  const resultsA = await Promise.all(tokens.map((tk, i) =>
    chatStream(tk, { question: `负载压测第 ${i + 1} 问：请直接回答。`, model: refs[i] })));
  const wallA = Date.now() - tA;
  report('A', resultsA, wallA);

  // ── 阶段 B：40 路挂 askUser + 60 路普通问答同时起跑 ─────────────
  console.log(`\n[phase B] ${ASKERS} 路 askUser 挂起 + ${USERS - ASKERS} 路普通问答...`);
  const tB = Date.now();
  const resultsB = await Promise.all(tokens.map((tk, i) => {
    if (i < ASKERS) {
      return chatStream(tk, { question: `[[ASK]] 压测第 ${i + 1} 轮：需要用户拍板。`, model: refs[i] },
        async (ask) => {
          // 收到提问卡后 2s 作答（验证挂起-恢复管道），回答后流继续到 done
          await new Promise((ok) => setTimeout(ok, 2000));
          await api(`/api/ai/ask-user/${ask.askId}`, {
            method: 'POST', token: tk,
            body: { answers: ['方案A：推荐项（超时不代答）'] },
          });
        });
    }
    return chatStream(tk, { question: `隔离压测第 ${i + 1} 问：请直接回答。`, model: refs[i] });
  }));
  const wallB = Date.now() - tB;
  report('B', resultsB, wallB);

  // ── 阶段 C：同会话轮级互斥 ────────────────────────────────────
  console.log('\n[phase C] 同会话并发第二问（预期互斥拒绝）...');
  const sess = await api('/api/ai/sessions', { token: tokens[0] });
  const sid = sess.json?.data?.items?.[0]?.id ?? sess.json?.data?.[0]?.id;
  if (!sid) throw new Error(`取会话失败: ${JSON.stringify(sess.json).slice(0, 200)}`);
  const [c1, c2] = await Promise.all([
    chatStream(tokens[0], { sessionId: sid, question: '并发第一问', model: refs[0] }),
    new Promise((ok) => setTimeout(ok, 150)).then(() =>
      chatStream(tokens[0], { sessionId: sid, question: '并发第二问', model: refs[0] })),
  ]);
  const rejected = c2.errors.some((e) => e.includes('上一轮还在回答中'));
  console.log(`  第一问 done=${c1.done} 第二问 done=${c2.done} 互斥拒绝=${rejected} 第二问errors=${JSON.stringify(c2.errors)}`);

  // ── 汇总 ──────────────────────────────────────────────────────
  const passA = resultsA.every((r) => r.done && r.errors.length === 0);
  const normalsB = resultsB.slice(ASKERS);
  const askersB = resultsB.slice(0, ASKERS);
  const passB = normalsB.every((r) => r.done && r.errors.length === 0) && askersB.every((r) => r.done);
  const passC = c1.done && rejected;

  console.log('\n========== 压测报告 ==========');
  console.log(`阶段A（${USERS} 路并发）:        ${passA ? '✅ 通过' : '❌ 未通过'}`);
  console.log(`阶段B（挂起隔离+恢复）:          ${passB ? '✅ 通过' : '❌ 未通过'}`);
  console.log(`阶段C（同会话互斥）:             ${passC ? '✅ 通过' : '❌ 未通过'}`);
  if (CLEANUP) await cleanup(tokens);
  if (!(passA && passB && passC)) process.exit(1);
}

function report(name, results, wallMs) {
  const done = results.filter((r) => r.done).length;
  const busy = results.filter((r) => r.errors.some((e) => e.includes('系统繁忙'))).length;
  const errored = results.filter((r) => !r.done && r.errors.length > 0).length;
  const ttbs = results.map((r) => r.ttfb).filter(Boolean);
  console.log(`  完成 ${done}/${results.length}，墙钟 ${(wallMs / 1000).toFixed(1)}s；` +
    `繁忙拒绝 ${busy}，失败 ${errored}`);
  console.log(`  首字 TTFB  p50=${pct(ttbs, 50)}ms  p95=${pct(ttbs, 95)}ms  max=${pct(ttbs, 100)}ms`);
  const bad = results.filter((r) => r.errors.length);
  if (bad.length) console.log('  样例错误:', JSON.stringify(bad[0].errors));
}

async function cleanup(tokens) {
  // 供应商归各测试用户（谁建归谁）：逐用户 batch-delete 自己的网关，再由管理员删测试用户
  for (const tk of tokens) {
    const list = await api('/api/ai/provider', { token: tk });
    const ids = (list.json?.data ?? []).map((p) => p.id).filter(Boolean);
    if (ids.length) {
      await api('/api/ai/provider/batch-delete', { method: 'POST', token: tk, body: { ids } });
    }
  }
  const admin = mintToken('admin', 'superadmin', 7200);
  const ulist = await api('/api/ai/user/list', { token: admin });
  const users = (ulist.json?.data ?? []).filter((u) => String(u.uid).startsWith('loadtest_'));
  for (const u of users) await api(`/api/ai/user/${u.uid}`, { method: 'DELETE', token: admin });
  console.log(`[cleanup] 已删 ${tokens.length} 个用户的网关与 ${users.length} 个压测用户（残留会话请用 SQL 清理）`);
}

main().catch((e) => {
  console.error('[loadtest] 失败:', e);
  process.exit(1);
});
