/**
 * blocks 端到端回放核对（配 mock 网关 + 8091 本地栈；与 loadtest.mjs 同一套起法）。
 *
 * 覆盖三件单测证不了的事：
 *   1. 真跑一轮：SSE 事件流 → 服务端落库的 blocks/旧列，两边内容逐字符一致；
 *   2. 前端纯函数 `web/src/chat/blocks.js` 拿落库的 blocks 重建视图，重建出的 content
 *      必须与历史接口返回的 content 逐字符相等（现场与刷新同一本账）；
 *   3. 分享页（免登录公开历史）只出答案与 notice，工具入参/过程/思考一个字都不许出去。
 *
 * 用例（mock 网关按请求体内容分支，见 mock-gateway.mjs 头注释）：
 *   plain  —— 纯正文轮：答案边界就是这一轮
 *   ask    —— askUser 挂起后作答：工具轮 + 答后正文轮，验证「最后一条无工具轮才是答案」
 *   stop   —— 长回答中途叫停：验证 notice{tone:stopped} 与正文尾标同时到位
 *   resume —— 第 3 个 token 处断流再 /chat/resume：验证快照 blocks 可重放且不重不漏
 *   asktimeout —— 提问卡没人答（需 60s 地板）：验证「未作答继续」，默认不跑
 *   budget —— 把 chat.sseTimeoutMs 压到 30s 地板：验证 notice{tone:budget}，跑完恢复原值
 *
 * 用法：
 *   node scripts/loadtest/mock-gateway.mjs &
 *   SERVER_PORT=8091 AI_RATELIMIT_CHAT=200 java -jar target/wenqu.jar &
 *   node scripts/loadtest/blocks-e2e.mjs            # 跑三个用例并打印逐案结论
 *   KEEP=1 node scripts/loadtest/blocks-e2e.mjs     # 保留会话与事件流现场
 *
 * 事件流与落库行落在 fixtures/chat-events/<case>.json，可直接拿来做回归对照（不含密钥与真模型输出）。
 */
import crypto from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { applyBlocks } from '../../web/src/chat/blocks.js'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const FIXTURE_DIR = join(ROOT, 'fixtures', 'chat-events')
const BASE = process.env.BASE || 'http://127.0.0.1:8091/ai'
/** 本地密钥从 config/application-local.yml 读，不写死：写死的那份会随本机改密钥而失效（401 就是这么来的）。 */
const SECRET = process.env.JWT_SECRET ||
  readFileSync(join(ROOT, 'config', 'application-local.yml'), 'utf8').match(/^\s*jwt-secret:\s*(\S+)\s*$/m)?.[1]
/** 本机开发库里没有 'admin'，超级管理员是 yuanke（角色由 c_ai_user_role 装载，令牌 role 声明不算数）。 */
const ADMIN_UID = process.env.E2E_ADMIN_UID || 'yuanke'
const UID = process.env.E2E_UID || ADMIN_UID
const KEEP = process.env.KEEP === '1'
/** turnSeconds() 对机器预算有 30s 地板（`chat.sseTimeoutMs`/1000 再取 max(30,·)），要触到预算就得配长回答。 */
const BUDGET_MS = +(process.env.BUDGET_MS || 30000)
const STREAM_TIMEOUT_MS = +(process.env.STREAM_TIMEOUT_MS || Math.max(120_000, BUDGET_MS + 90_000))
/** CASES=plain,ask 只跑点名的用例；不设则跑全部默认档。 */
const CASES = (process.env.CASES || '').split(',').map((s) => s.trim()).filter(Boolean)
/** 只读核对模式：给一个会话 id 与一个现成 token，只取历史与公开历史做对账，不建用户/供应商/会话，不跑模型。 */
const VERIFY = process.env.VERIFY === '1' || (CASES.length === 1 && CASES[0] === 'verify')
const VERIFY_SESSION = process.env.SESSION || ''
const VERIFY_SHARE = process.env.SHARE_TOKEN || ''
const want = (c) => !CASES.length || CASES.includes(c)

const b64u = (buf) => Buffer.from(buf).toString('base64url')
const hmac = (data) => crypto.createHmac('sha256', SECRET).update(data).digest()

function mintToken(uid, role = 'user', ttlSeconds = 7200) {
  const now = Math.floor(Date.now() / 1000)
  const head = b64u(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const payload = b64u(JSON.stringify({
    sub: uid, role, iss: 'wenqu', aud: 'wenqu-api', iat: now, exp: now + ttlSeconds, ep: 0,
  }))
  return `${head}.${payload}.${b64u(hmac(`${head}.${payload}`))}`
}

/** 配置写入专用：被范围校验拒掉时必须炸出来。`chat.askTimeoutMs` 有 ≥60000 的地板——
 *  静默当成功（只回 success:false）会让「引擎为什么不等我配的超时」这种判断整个走偏。 */
async function putConfig(token, patch) {
  const r = await api('/api/ai/config', { method: 'PUT', token, body: patch })
  if (!r.json?.success) throw new Error(`配置写入被拒 ${JSON.stringify(patch)}：${r.text.slice(0, 160)}`)
  return r.json?.data
}

async function api(path, { method = 'GET', token, body } = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch { /* 非 JSON 响应按原样返回 */ }
  return { status: res.status, json, text }
}

/** 跑一轮问答，收下每一个 SSE 事件（拆信封后的原始载荷）。signal 用于模拟中途断线。 */
async function chatStream(token, payload, onEvent, signal) {
  const events = []
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), STREAM_TIMEOUT_MS)
  if (signal) signal.addEventListener('abort', () => ctrl.abort(), { once: true })
  try {
    const res = await fetch(`${BASE}/api/ai/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(payload),
      signal: ctrl.signal,
    })
    if (!res.ok || !res.body) throw new Error(`HTTP ${res.status} ${await res.text()}`)
    let buf = ''
    for await (const frame of res.body) {
      buf += Buffer.from(frame).toString('utf8')
      let idx
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const raw = buf.slice(0, idx)
        buf = buf.slice(idx + 2)
        const evLine = raw.split('\n').find((l) => l.startsWith('event:'))
        if (!evLine) continue
        const type = evLine.slice(6).trim()
        const dataLine = raw.split('\n').find((l) => l.startsWith('data:'))
        let data
        try { data = JSON.parse(dataLine?.slice(5) ?? '') } catch { data = dataLine?.slice(5) ?? '' }
        // 信封是 {type, content, sessionId}：content 可能是 JSON 串，也可能就是裸文本
        if (data && typeof data === 'object' && typeof data.content === 'string') {
          try { data = JSON.parse(data.content) } catch { data = data.content }
        }
        events.push({ type, data })
        if (process.env.DEBUG_EVENTS && type !== 'token') console.log(`  · ${type} ${JSON.stringify(data).slice(0, 140)}`)
        if (onEvent) await onEvent(type, data, events)
      }
    }
  } finally {
    clearTimeout(timer)
  }
  return events
}

/** 接流通道（GET SSE）：收下每一帧，用于验证「断开后重连能拿回快照」。 */
async function chatStreamGet(path, token, onEvent) {
  const events = []
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), Math.min(STREAM_TIMEOUT_MS, 20_000))
  try {
    const res = await fetch(`${BASE}${path}`, {
      headers: { Accept: 'text/event-stream', Authorization: `Bearer ${token}` },
      signal: ctrl.signal,
    })
    if (!res.ok || !res.body) throw new Error(`HTTP ${res.status} ${await res.text()}`)
    let buf = ''
    for await (const frame of res.body) {
      buf += Buffer.from(frame).toString('utf8')
      let idx
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const raw = buf.slice(0, idx)
        buf = buf.slice(idx + 2)
        const evLine = raw.split('\n').find((l) => l.startsWith('event:'))
        if (!evLine) continue
        const type = evLine.slice(6).trim()
        const dataLine = raw.split('\n').find((l) => l.startsWith('data:'))
        let data
        try { data = JSON.parse(dataLine?.slice(5) ?? '') } catch { data = dataLine?.slice(5) ?? '' }
        if (data && typeof data === 'object' && typeof data.content === 'string') {
          try { data = JSON.parse(data.content) } catch { data = data.content }
        }
        events.push({ type, data })
        if (onEvent) await onEvent(type, data, events)
        // 快照已到手就够验证了：主动断，免得挂在这一轮的长流上
        if (type === 'snapshot' && events.length >= 1) { ctrl.abort(); return events }
      }
    }
  } catch (e) {
    if (e.name !== 'AbortError') throw e
  } finally {
    clearTimeout(timer)
  }
  return events
}

/**
 * 旁观接流（第二路观众）：一直读到服务端把连接关掉，记录它什么时候关、关之前收到了什么。
 * 用于验证「停止后别的标签立刻收尾、发起者那一路还能收到 done」这半边。
 */
function openViewer(path, token) {
  const started = Date.now()
  const state = { events: [], closed: false, closedAt: null, error: null }
  const ctrl = new AbortController()
  state.abort = () => ctrl.abort()
  state.done = (async () => {
    try {
      const res = await fetch(`${BASE}${path}`, {
        headers: { Accept: 'text/event-stream', Authorization: `Bearer ${token}` },
        signal: ctrl.signal,
      })
      if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`)
      let buf = ''
      for await (const frame of res.body) {
        buf += Buffer.from(frame).toString('utf8')
        let idx
        while ((idx = buf.indexOf('\n\n')) >= 0) {
          const raw = buf.slice(0, idx)
          buf = buf.slice(idx + 2)
          const evLine = raw.split('\n').find((l) => l.startsWith('event:'))
          if (!evLine) continue
          const type = evLine.slice(6).trim()
          const dataLine = raw.split('\n').find((l) => l.startsWith('data:'))
          let data
          try { data = JSON.parse(dataLine?.slice(5) ?? '') } catch { data = dataLine?.slice(5) ?? '' }
          if (data && typeof data === 'object' && typeof data.content === 'string') {
            try { data = JSON.parse(data.content) } catch { data = data.content }
          }
          state.events.push({ type, data, raw: (dataLine?.slice(5) ?? '').slice(0, 300) })
        }
      }
    } catch (e) {
      if (e.name !== 'AbortError') state.error = e.message
    } finally {
      state.closed = true
      state.closedAt = Date.now() - started
    }
  })()
  return state
}

const find = (events, type) => events.find((e) => e.type === type)
const kinds = (blocks) => (blocks?.blocks ?? []).map((b) => b.kind)
/** 键序无关的比较口径：blocks 与旧列各写各的字段顺序，逐字符比会假报不一致。 */
const canonical = (value) => JSON.stringify(value, (k, v) =>
  v && typeof v === 'object' && !Array.isArray(v)
    ? Object.fromEntries(Object.keys(v).sort().map((kk) => [kk, v[kk]]))
    : v)

/** 落库行 + 前端重建 + 分享页三份对账，逐案打印结论。 */
function check(caseName, events, historyRow, guestRow) {
  const problems = []
  const done = find(events, 'done')?.data ?? {}
  const blocks = historyRow?.blocks
  if (!historyRow) {
    console.log(`\n[${caseName}] ✗ 历史里读不到助手消息（这一轮没跑成或没落库）`)
    return { ok: false, problems: ['没有助手行'], summary: { case: caseName }, events, historyRow, guestRow }
  }
  if (!blocks) problems.push('历史行没有 blocks（落库或映射断了）')

  const msg = { role: 'ai' }
  if (blocks) applyBlocks(msg, blocks)
  if (blocks && msg.content !== historyRow.content) {
    problems.push('blocks 重建的正文与落库 content 不相等')
  }
  const notice = (blocks?.blocks ?? []).filter((b) => b.kind === 'notice')
  const toolBlocks = (blocks?.blocks ?? []).filter((b) => b.kind === 'tool')
  const toolCount = Array.isArray(historyRow.toolCalls) ? historyRow.toolCalls.length : 0
  if (toolBlocks.length !== toolCount) problems.push(`工具块数 ${toolBlocks.length} ≠ tool_calls ${toolCount}`)
  if (blocks && canonical(msg.toolCalls) !== canonical(historyRow.toolCalls ?? [])) {
    problems.push('blocks 重建的工具卡与 tool_calls 列不一致')
  }

  // 分享页：只许出答案文本块（+ 结构化 notice），过程/思考/工具入参一个字都不能出去
  const guestBlocks = guestRow?.blocks
  if (guestBlocks) {
    for (const b of guestBlocks.blocks ?? []) {
      if (b.kind !== 'text' && b.kind !== 'notice') problems.push(`分享页漏出 ${b.kind} 块`)
      if (b.kind === 'text' && b.channel !== 'answer') problems.push('分享页漏出非答案正文')
      if (b.args || b.result || b.error) problems.push('分享页漏出工具入参/输出')
    }
    if (JSON.stringify(guestBlocks) !== JSON.stringify(blocks)) {
      const leakedTool = toolBlocks.some((t) => JSON.stringify(guestBlocks).includes(String(t.args ?? '')))
      if (leakedTool) problems.push('分享页里能找到工具入参原文')
    }
  } else if (blocks && (toolBlocks.length || notice.length)) {
    problems.push('有工具/notice 的轮次分享页拿不到 blocks')
  }

  const summary = {
    case: caseName,
    events: events.map((e) => e.type),
    contentChars: (historyRow?.content ?? '').length,
    blockKinds: kinds(blocks),
    noticeTones: notice.map((n) => n.tone),
    answerBlocks: (blocks?.blocks ?? []).filter((b) => b.channel === 'answer').length,
    doneHasBlocks: !!done.blocks,
    guestBlockKinds: kinds(guestBlocks),
  }
  console.log(`\n[${caseName}] ${JSON.stringify(summary, null, 2)}`)
  if (problems.length) {
    for (const p of problems) console.log(`  ✗ ${p}`)
    console.log(`  落库 content=${JSON.stringify(historyRow?.content)}`)
    console.log(`  重建 content=${JSON.stringify(msg.content)}`)
  } else {
    console.log('  ✓ 落库/重建/分享三方一致')
  }
  return { ok: problems.length === 0, problems, summary, events, historyRow, guestRow }
}

/**
 * 只读核对：`VERIFY=1 WENQU_TOKEN=… SESSION=… [SHARE_TOKEN=…] node scripts/loadtest/blocks-e2e.mjs`
 * 只发三个 GET（会话历史、分享信息、公开历史），对账口径与本地真跑完全一致；不写任何东西。
 */
async function verifyOnly() {
  const token = process.env.WENQU_TOKEN || ''
  if (!token || !VERIFY_SESSION) {
    throw new Error('只读核对需要 WENQU_TOKEN 与 SESSION（SHARE_TOKEN 可选，不给就查现成的分享）')
  }
  const hist = await api(`/api/ai/session/${VERIFY_SESSION}`, { token })
  if (!hist.json?.success) throw new Error(`取会话历史失败：${hist.text.slice(0, 160)}`)
  const row = assistantRow(hist.json.data)
  let shareToken = VERIFY_SHARE
  if (!shareToken) {
    const info = await api(`/api/ai/session/${VERIFY_SESSION}/share`, { token })
    shareToken = info.json?.data?.token || ''
  }
  let guest = null
  if (shareToken) {
    const pub = await api(`/api/ai/share/session/${shareToken}`)
    guest = (pub.json?.data?.messages ?? []).filter((m) => m.role === 'assistant').pop() ?? null
  }
  const res = check('verify', [], row, guest)
  console.log(`[verify] session=${VERIFY_SESSION} share=${shareToken || '(无)'} → ${res.ok ? '一致' : '不一致'}`)
  process.exit(res.ok ? 0 : 1)
}

async function main() {
  if (VERIFY) return verifyOnly()
  if (!SECRET) throw new Error('没拿到本地 jwt-secret（config/application-local.yml 的 ai-app.auth.jwt-secret）')
  const admin = mintToken(ADMIN_UID, 'superadmin')
  const user = mintToken(UID)
  if (UID !== ADMIN_UID) {
    const created = await api('/api/ai/user', {
      method: 'POST', token: admin,
      body: { uid: UID, username: 'blocks 端到端', password: 'Blocks#E2e2026', role: 'user' },
    })
    console.log(`[setup] 独立压测账号 ${UID}：${created.json?.success ? '已就绪' : created.text}`)
  }
  /** MODEL_REF：直接复用现成（真实）模型引用，跳过 mock 供应商登记；不给就现建 mock。 */
  let ref = process.env.MODEL_REF?.trim()
  if (ref) {
    console.log(`[setup] 复用现成模型引用 ${ref}（MODEL_REF）`)
  } else {
    const prov = await api('/api/ai/provider', {
      method: 'POST', token: user,
      body: { name: 'blocksE2E Mock 网关', baseUrl: process.env.MOCK_BASE_URL || 'http://127.0.0.1:9101', apiKey: 'mock-key', enabled: true },
    })
    const pid = prov.json?.data?.id ?? prov.json?.data
    if (!pid) throw new Error(`供应商创建失败：${prov.text}`)
    const saved = await api(`/api/ai/provider/${pid}/models`, {
      method: 'PUT', token: user,
      body: [{
        modelId: 'mock-chat', modelType: 'chat', displayName: 'Mock Chat',
        thinking: 'auto', contextWindow: 32768, maxOutput: 4096, enabled: true,
      }],
    })
    if (!saved.json?.success) throw new Error(`模型登记失败：${saved.text}`)
    ref = `${pid}/mock-chat`
  }
  mkdirSync(FIXTURE_DIR, { recursive: true })
  console.log(`[setup] BASE=${BASE} uid=${UID} model=${ref}`)

  const results = []
  const sessionId = crypto.randomUUID()

  // ── plain：纯正文轮 ────────────────────────────────────────────
  if (want('plain')) {
    const events = await chatStream(user, { sessionId, question: 'blocks 端到端第一问：请直接回答。', model: ref })
    const row = await historyRow(user, sessionId, events)
    const guest = await guestRow(sessionId, user)
    results.push(check('plain', events, row, guest))
    writeFixture('plain', { events, row, guest })
  }

  // ── ask：挂起后作答，工具轮 + 正文轮 ───────────────────────────
  if (want('ask')) {
    const askSession = crypto.randomUUID()
    const events = await chatStream(user, {
      sessionId: askSession, question: '[[ASK]] blocks 端到端第二问：需要拍板。', model: ref,
    }, async (type, data) => {
      if (type !== 'ask_user' || !data?.askId) return
      await new Promise((ok) => setTimeout(ok, 300))
      await api(`/api/ai/ask-user/${data.askId}`, {
        method: 'POST', token: user, body: { answers: ['方案A：推荐项（超时不代答）'] },
      })
    })
    const row = await historyRow(user, askSession, events)
    const guest = await guestRow(askSession, user)
    results.push(check('ask', events, row, guest))
    writeFixture('ask', { events, row, guest })
  }

  // ── stop：长回答中途叫停 ───────────────────────────────────────
  if (want('stop')) {
    const stopSession = crypto.randomUUID()
    const pending = chatStream(user, {
      sessionId: stopSession, question: 'blocks 端到端第三问：写一篇两千字的长文。', model: ref,
    })
    await new Promise((ok) => setTimeout(ok, 2500))
    const stopped = await api('/api/ai/chat/stop', {
      method: 'POST', token: user, body: { sessionId: stopSession },
    })
    console.log(`[stop] 请求已发：${stopped.text}`)
    const events = await pending
    await new Promise((ok) => setTimeout(ok, 800))
    const row = await historyRow(user, stopSession, events)
    const guest = await guestRow(stopSession, user)
    const res = check('stop', events, row, guest)
    if (!res.problems.some((p) => p.includes('notice'))) {
      const notice = (row?.blocks?.blocks ?? []).filter((b) => b.kind === 'notice')
      if (!notice.length || notice[0].tone !== 'stopped') {
        console.log('  ✗ 被叫停的那一轮没有 notice{tone:stopped}')
        res.ok = false
        res.problems.push('缺少 stopped 的 notice 块')
      }
    }
    results.push(res)
    writeFixture('stop', { events, row, guest })
  }

  // ── budget：整轮机器预算压到下限（turnSeconds 有 30s 地板），需要回答比预算更长的网关 ──
  // 只在显式给了 BUDGET_MS 时跑：默认 mock 网关 2.4s 就答完，压预算只会跑出一轮普通问答。
  if (!want('budget')) {
    // 未点名
  } else if (!process.env.BUDGET_MS) {
    console.log('[budget] 未设 BUDGET_MS，跳过（用长回答网关跑这一档：PORT=9105 CHUNKS=1200 CHUNK_MS=40 + BUDGET_MS=30000）')
  } else {
    // 配置项是 {value,editable,tier,source} 的对象形态，取值要读 .value；写回时只给字符串。
    const before = (await api('/api/ai/config', { token: admin })).json?.data?.chat?.sseTimeoutMs?.value
    const budgetSession = crypto.randomUUID()
    const saved = await api('/api/ai/config', {
      method: 'PUT', token: admin, body: { chat: { sseTimeoutMs: String(BUDGET_MS) } },
    })
    if (!saved.json?.success) {
      console.log('[budget] 改预算失败，跳过这一用例：', saved.text)
    } else {
      try {
        const events = await chatStream(user, {
          sessionId: budgetSession, question: 'blocks 端到端第四问：预算内答不完。', model: ref,
        })
        const row = await historyRow(user, budgetSession, events)
        const guest = await guestRow(budgetSession, user)
        const res = check('budget', events, row, guest)
        const notice = (row?.blocks?.blocks ?? []).filter((b) => b.kind === 'notice')
        if (notice[0]?.tone !== 'budget') {
          console.log(`  ✗ 预算收轮的那一轮 notice.tone 不是 budget（实际 ${notice[0]?.tone}）`)
          res.ok = false
          res.problems.push('notice 的 tone 不对')
        }
        results.push(res)
        writeFixture('budget', { events, row, guest })
      } finally {
        await api('/api/ai/config', {
          method: 'PUT', token: admin,
          body: { chat: { sseTimeoutMs: String(before ?? 300000) } },
        })
      }
    }
  }

  // ── resume：中途断开再接流，快照里的 blocks 必须能重建出「已产出的那一截」 ──
  if (want('resume')) {
    const resumeSession = crypto.randomUUID()
    const aborter = new AbortController()
    const first = chatStream(user, {
      sessionId: resumeSession, question: 'blocks 端到端第五问：中途断开再接回来。', model: ref,
    }, (type, data, events) => {
      // 收到第三个 token 就主动断：模拟关页面/切网络，服务端这一轮仍在跑
      if (type === 'token' && events.filter((e) => e.type === 'token').length >= 3) aborter.abort()
    }, aborter.signal).catch(() => [])
    await first
    await new Promise((ok) => setTimeout(ok, 400))
    const snapshots = []
    const resumed = await chatStreamGet(
      `/api/ai/chat/resume?sessionId=${encodeURIComponent(resumeSession)}`, user, (type, data) => snapshots.push({ type, data }))
    const snap = snapshots.find((s) => s.type === 'snapshot')?.data
    const resumedText = { role: 'ai' }
    if (snap?.blocks) applyBlocks(resumedText, snap.blocks)
    // 接流后让这一轮跑完，再拿落库终稿对照：快照重建出的文本必须是终稿的前缀（且不重复补段）
    await new Promise((ok) => setTimeout(ok, 6000))
    const row = await historyRow(user, resumeSession, resumed)
    const content = row?.content ?? ''
    const problems = []
    if (!snap?.blocks) problems.push('接流快照里没有 blocks')
    if (snap && !content.startsWith(resumedText.content)) {
      problems.push('快照重建的正文不是落库终稿的前缀（接流重放会错位或重复）')
    }
    const ok = problems.length === 0
    console.log(`\n[resume] ${JSON.stringify({
      snapshotBlocks: kinds(snap?.blocks), resumedChars: resumedText.content.length,
      finalChars: content.length, prefixOk: content.startsWith(resumedText.content),
    })}`)
    problems.forEach((p) => console.log(`  ✗ ${p}`))
    if (ok) console.log('  ✓ 快照 blocks 可重放，且与终稿同源')
    results.push({ ok, problems, events: resumed, historyRow: row, guestRow: null })
    writeFixture('resume', { events: resumed, row, guest: null })
  }

  // ── asktimeout：提问卡没人答，按「未作答」继续而不是收轮 ─────────
  // 默认不跑（要 60s 起步）：`chat.askTimeoutMs` 有 ≥60000 的地板，压到 1.5s 会被配置接口以 400 拒掉。
  if (!want('asktimeout')) {
    // 未点名
  } else if (!process.env.ASKTIMEOUT_CASE) {
    console.log('[asktimeout] 默认跳过（ASKTIMEOUT_CASE=1 单独跑，约 65s）')
  } else {
    const before = (await api('/api/ai/config', { token: admin })).json?.data?.chat?.askTimeoutMs?.value
    const tSession = crypto.randomUUID()
    await putConfig(admin, { chat: { askTimeoutMs: '60000' } })
    try {
      let events = []
      try {
        events = await chatStream(user, {
          sessionId: tSession, question: '[[ASK]] blocks 端到端第六问：没人回答这一题。', model: ref,
        })
      } catch (e) {
        // 断线诊断：把客户端看到的错误形态与已经收到的事件打出来（服务端有没有 warn/收口另看日志）
        console.log(`  [diag] 流中断 name=${e?.name} message=${e?.message} cause=${e?.cause?.message ?? e?.cause}`)
        console.log(`  [diag] 已收事件：${events.length ? [...new Set(events.map((x) => x.type))].join(',') : '无'}`)
        throw e
      }
      const row = await historyRow(user, tSession, events)
      const warns = events.filter((e) => e.type === 'warn').map((e) => String(e.data))
      const problems = []
      if (!warns.some((w) => w.includes('未作答'))) problems.push('没有「未作答继续」的 warn')
      if (!events.some((e) => e.type === 'done')) problems.push('超时后这一轮没有 done')
      if (!row?.blocks) problems.push('落库没有 blocks')
      console.log(`\n[asktimeout] ${JSON.stringify({
        warns, blockKinds: kinds(row?.blocks), done: events.some((e) => e.type === 'done'),
      })}`)
      problems.forEach((p) => console.log(`  ✗ ${p}`))
      if (!problems.length) console.log('  ✓ 提问超时按未作答继续，工具卡与答案都落库')
      results.push({ ok: problems.length === 0, problems, events, historyRow: row })
      writeFixture('asktimeout', { events, row, guest: null })
    } finally {
      await putConfig(admin, { chat: { askTimeoutMs: String(before ?? 600000) } })
    }
  }

  // ── artifact：工具交付产物 → 产物块 / 落库 artifacts 列 / 分享页裁剪 ──
  if (want('artifact')) {
    const aSession = crypto.randomUUID()
    const events = await chatStream(user, {
      sessionId: aSession, question: '[[ARTIFACT]] 浏览器渲染检查第四问：交付一个产物。', model: ref,
    })
    const row = await historyRow(user, aSession, events)
    const guest = await guestRow(aSession, user)
    const blocksList = row?.blocks?.blocks ?? []
    const artifactBlocks = blocksList.filter((b) => b.kind === 'artifact')
    const artifactCol = Array.isArray(row?.artifacts) ? row.artifacts : []
    const guestKinds = kinds(guest?.blocks)
    const problems = []
    if (artifactBlocks.length !== 1) problems.push(`产物块应为 1，实际 ${artifactBlocks.length}`)
    if (artifactCol.length !== 1) problems.push(`artifacts 列应为 1，实际 ${artifactCol.length}`)
    const b0 = artifactBlocks[0] ?? {}
    const c0 = artifactCol[0] ?? {}
    if (b0 && c0 && b0.filename !== c0.filename) problems.push('产物块的 filename 与 artifacts 列不一致')
    if (guestKinds.includes('artifact')) problems.push('分享页漏出产物块')
    console.log(`\n[artifact] ${JSON.stringify({
      blockKinds: blocksList.map((b) => b.kind), artifactFilename: b0.filename,
      artifactColKeys: Object.keys(c0).slice(0, 10), guestKinds,
    })}`)
    problems.forEach((p) => console.log(`  ✗ ${p}`))
    if (!problems.length) console.log('  ✓ 产物块、artifacts 列、分享页裁剪三处一致')
    results.push({ ok: problems.length === 0, problems, events, historyRow: row, guestRow: guest })
    writeFixture('artifact', { events, row, guest })
  }

  // ── workflow：绑定工作流的智能体走 ChatflowBridge，验证 done 形状与计时三处同源 ──
  if (want('workflow')) {
    if (!process.env.WORKFLOW_CASE) {
      console.log('[workflow] 默认跳过（WORKFLOW_CASE=1 单独跑：会用当前模型现场建一条最小工作流并发布）')
    } else {
      const dsl = {
        nodes: [
          { id: 'start', type: 'start', config: { inputs: [{ key: 'question', required: true }] } },
          { id: 'llm1', type: 'llm', config: { modelRef: ref, prompt: '{{start.question}}' } },
          { id: 'end', type: 'end', config: { outputs: { answer: '{{llm1.text}}' } } },
        ],
        edges: [{ from: 'start', to: 'llm1' }, { from: 'llm1', to: 'end' }],
      }
      const wf = await api('/api/ai/workflow', {
        method: 'POST', token: user,
        body: { name: `blocks E2E 工作流 ${Date.now()}`, description: 'blocks 端到端用例', dsl: JSON.stringify(dsl) },
      })
      const wid = wf.json?.data?.id
      if (!wid) throw new Error(`建工作流失败：${wf.text.slice(0, 200)}`)
      const pub = await api(`/api/ai/workflow/${wid}/publish`, { method: 'POST', token: user, body: { note: 'e2e' } })
      if (!pub.json?.success) throw new Error(`发布工作流失败：${pub.text.slice(0, 200)}`)
      const ag = await api('/api/ai/agent', {
        method: 'POST', token: user,
        body: { name: `blocks E2E 工作流智能体 ${Date.now()}`, description: 'e2e', workflowId: wid },
      })
      const aid = ag.json?.data?.id ?? ag.json?.data
      if (!aid) throw new Error(`建智能体失败：${ag.text.slice(0, 200)}`)
      const wSession = crypto.randomUUID()
      const events = await chatStream(user, {
        sessionId: wSession, question: '工作流轮：请回答这一问。', agentId: aid,
      })
      const row = await historyRow(user, wSession, events)
      const guest = await guestRow(wSession, user)
      const done = events.find((e) => e.type === 'done')?.data
      const wfRun = done?.workflowRun
      const tokens = row?.tokens ?? {}
      const problems = []
      if (!done) problems.push('工作流轮没有 done')
      if (!done?.blocks) problems.push('done 里没有 blocks')
      const kindsDone = (done?.blocks?.blocks ?? []).map((b) => b.kind + ':' + b.channel)
      if (kindsDone.length && kindsDone.join(',') !== 'text:answer') problems.push(`done.blocks 应为单 answer 块，实际 ${kindsDone}`)
      if (typeof done?.turnWallMs !== 'number' || done.turnWallMs <= 0) problems.push('done 缺 turnWallMs')
      if (typeof done?.prepMs !== 'number') problems.push('done 缺 prepMs')
      if (tokens.turnWallMs == null) problems.push('落库 tokens 缺 turnWallMs（刷新后计时会没有）')
      if (tokens.turnWallMs != null && done?.turnWallMs != null && tokens.turnWallMs !== done.turnWallMs) {
        problems.push(`done.turnWallMs(${done.turnWallMs}) ≠ tokens.turnWallMs(${tokens.turnWallMs})，现场与刷新会报两个数`)
      }
      if (!wfRun?.runId) problems.push('done 缺 workflowRun')
      if (kinds(guest?.blocks).some((k) => k !== 'text')) problems.push('分享页漏出非文本块')
      console.log(`\n[workflow] ${JSON.stringify({
        doneKeys: Object.keys(done ?? {}).slice(0, 18), kindsDone, workflowRun: wfRun,
        turnWallMs: done?.turnWallMs, prepMs: done?.prepMs, tokensTurnWallMs: tokens.turnWallMs,
        finalContentChars: (done?.finalContent ?? '').length,
      })}`)
      problems.forEach((p) => console.log(`  ✗ ${p}`))
      if (!problems.length) console.log('  ✓ 工作流轮：单 answer 块 + 服务端计时三处同源')
      results.push({ ok: problems.length === 0, problems, events, historyRow: row, guestRow: guest })
      writeFixture('workflow', { events, row, guest })
    }
  }

  // ── plan：计划卡（plan_write → plan_exit → 批准 → 执行） ──────────
  if (want('plan')) {
    if (!process.env.PLAN_CASE) {
      console.log('[plan] 默认跳过（PLAN_CASE=1 单独跑：mock 会连发 plan_write/plan_exit 两个工具调用）')
    } else {
      const planMode = ['reject', 'supersede'].includes(process.env.PLAN_CASE) ? process.env.PLAN_CASE : 'approve'
      const pSession = crypto.randomUUID()
      let approved = false
      const events = await chatStream(user, {
        sessionId: pSession, question: '[[PLAN]] 计划卡用例：先出计划，批准后执行。', model: ref, planMode: true,
      }, async (type, data) => {
        if (type !== 'deep_plan_approval' || approved || !data?.planApprovalId) return
        approved = true
        await new Promise((ok) => setTimeout(ok, 400))
        const r = await api(`/api/ai/deep/plan-approval/${data.planApprovalId}`, {
          method: 'POST', token: user,
          body: planMode === 'supersede'
            ? { approved: false, supersede: true, plan: '先按另一个方向做' }
            : { approved: planMode === 'approve' },
        })
        console.log(`  [plan] ${planMode} → ${r.text.slice(0, 90)}`)
      })
      const row = await historyRow(user, pSession, events)
      const done = events.find((e) => e.type === 'done')?.data
      const problems = []
      if (!approved) problems.push('没收到 deep_plan_approval 卡')
      if (!done) problems.push('批准后这一轮没有 done')
      if (planMode === 'approve') {
        if (done && done.planRecord?.status !== 'approved') {
          problems.push(`done.planRecord.status 应为 approved，实际 ${done.planRecord?.status}`)
        }
        if (!done?.finalContent) problems.push('批准后没有正文')
      } else if (planMode === 'reject') {
        if (done && done.planRecord?.status !== 'rejected') {
          problems.push(`拒绝后 done.planRecord.status 应为 rejected，实际 ${done.planRecord?.status}`)
        }
        if (done && done.planCancelled !== true) problems.push('拒绝后 done 缺 planCancelled=true')
      } else {
        if (done && done.planRecord?.status !== 'superseded') {
          problems.push(`取代后 done.planRecord.status 应为 superseded，实际 ${done.planRecord?.status}`)
        }
        if (done && done.planSuperseded !== true) problems.push('取代后 done 缺 planSuperseded=true')
        if (!events.some((e) => e.type === 'deep_plan_superseded')) problems.push('流里没有 deep_plan_superseded 帧')
      }
      if (!(row?.blocks?.blocks ?? []).length) problems.push('落库没有 blocks')
      console.log(`\n[plan] ${JSON.stringify({
        events: [...new Set(events.map((e) => e.type))],
        planRecord: done?.planRecord ? { status: done.planRecord.status, versions: (done.planRecord.versions ?? []).length } : null,
        blockKinds: kinds(row?.blocks), finalContentChars: (done?.finalContent ?? '').length,
      })}`)
      problems.forEach((p) => console.log(`  ✗ ${p}`))
      if (!problems.length) {
        console.log(planMode === 'approve'
          ? '  ✓ 计划卡 → 批准 → 执行，落库与 done 一致'
          : (planMode === 'reject'
            ? '  ✓ 计划卡 → 拒绝 → 本轮终止，done 带 planCancelled'
            : '  ✓ 计划卡 → 继续对话 → 本轮作废，done 带 planSuperseded 且正文为空'))
      }
      results.push({ ok: problems.length === 0, problems, events, historyRow: row })
      writeFixture(planMode === 'approve' ? 'plan' : (planMode === 'reject' ? 'planreject' : 'plansupersede'), { events, row, guest: null })
    }
  }

  // ── approval：工具审批卡（同一轮两次 write_file → 两次确认） ──────
  if (want('approval')) {
    if (!process.env.APPROVAL_CASE) {
      console.log('[approval] 默认跳过（APPROVAL_CASE=1 单独跑：需要 toolApprovalMode=ask 的智能体 + 连发两次 write_file）')
    } else {
      // 审批只在「有副作用工具」上生效：联网搜索是唯一由配置决定的那一把（webSearch.requireApproval）
      const before = (await api('/api/ai/config', { token: admin })).json?.data ?? {}
      await putConfig(admin, {
        chat: {},
        webSearch: { enabled: 'true', provider: 'generic', baseUrl: 'http://127.0.0.1:8888', requireApproval: 'true' },
      }).catch((e) => console.log(`  [approval] 配联网搜索失败：${e.message}`))
      const ag = await api('/api/ai/agent', {
        method: 'POST', token: user,
        body: { name: `blocks E2E 审批智能体 ${Date.now()}`, description: 'e2e', toolApprovalMode: 'ask', permissionPreset: 'standard' },
      })
      const aid = ag.json?.data?.id ?? ag.json?.data
      if (!aid) throw new Error(`建审批智能体失败：${ag.text.slice(0, 200)}`)
      const mode = process.env.APPROVAL_CASE === 'timeout' ? 'timeout' : 'approve'
      const apSession = crypto.randomUUID()
      let approvals = 0
      if (mode === 'timeout') {
        // 审批等待预算压到地板（各超时键都有下限，写不进去会直接抛）
        await putConfig(admin, { chat: { approvalTimeoutMs: '60000' } })
      }
      const events = await chatStream(user, {
        sessionId: apSession, question: '[[APPROVAL]] 审批用例：连做两次写文件。', model: ref, agentId: aid,
      }, async (type, data) => {
        if (type !== 'approval_required' || !data?.approvalId) return
        approvals++
        if (mode === 'timeout') {
          console.log(`  [approval] #${approvals} 不作答，等超时`)
          return
        }
        await new Promise((ok) => setTimeout(ok, 300))
        const r = await api(`/api/ai/tool-approval/${data.approvalId}`, {
          method: 'POST', token: user, body: { approved: true },
        })
        console.log(`  [approval] #${approvals} 批准 → ${r.text.slice(0, 80)}`)
      })
      const row = await historyRow(user, apSession, events)
      const done = events.find((e) => e.type === 'done')?.data
      const toolBlocks = (row?.blocks?.blocks ?? []).filter((b) => b.kind === 'tool')
      const problems = []
      const warns = events.filter((e) => e.type === 'warn').map((e) => String(e.data))
      const notice = (done?.blocks?.blocks ?? []).filter((b) => b.kind === 'notice')
      if (mode === 'approve') {
        if (approvals !== 2) problems.push(`应收到两次审批卡，实际 ${approvals}`)
        if (!done) problems.push('批完后这一轮没有 done')
        if (toolBlocks.length !== 2) problems.push(`工具块应为 2，实际 ${toolBlocks.length}`)
        if (!done?.finalContent) problems.push('批完后没有正文')
      } else {
        if (approvals < 1) problems.push('没收到审批卡')
        if (!warns.some((w) => w.includes('超时'))) problems.push('超时后没有 warn')
        if (!done) problems.push('超时后这一轮没有 done')
        if (notice[0]?.tone !== 'timeout') problems.push(`notice.tone 应为 timeout，实际 ${notice[0]?.tone}`)
      }
      console.log(`\n[approval:${mode}] ${JSON.stringify({
        approvals, events: [...new Set(events.map((e) => e.type))],
        toolBlocks: toolBlocks.map((t) => `${t.name}:${t.status}`), notice: notice.map((n) => n.tone),
        warns: warns.slice(0, 2), finalContentChars: (done?.finalContent ?? '').length,
      })}`)
      problems.forEach((p) => console.log(`  ✗ ${p}`))
      if (!problems.length) {
        console.log(mode === 'approve'
          ? '  ✓ 两次审批都放行，两个工具卡 + 答后正文都在'
          : '  ✓ 审批超时：warn + notice#timeout + done 都到')
      }
      results.push({ ok: problems.length === 0, problems, events, historyRow: row })
      writeFixture(mode === 'approve' ? 'approval' : 'approvaltimeout', { events, row, guest: null })
      // 用完还原联网搜索（本地栈别留下一个默认关掉被打开的状态）
      // 还原成平台默认值而不是 before：本用例自己必须先打开联网搜索，
      // 若上一次运行没收干净，before 读到的就已经是 true。
      await putConfig(admin, {
        webSearch: { enabled: 'false', provider: 'tavily', baseUrl: '' },
        chat: { approvalTimeoutMs: '120000' },
      }).catch((e) => console.log(`  [approval] 还原配置失败：${e.message}`))
    }
  }

  // ── richtext：思考 + 过程独白 + 正文 + 推荐块（验 think/process 两类块） ──
  if (want('richtext')) {
    const rSession = crypto.randomUUID()
    const events = await chatStream(user, {
      sessionId: rSession, question: '[[RICHTEXT]] 富文本用例：带思考与过程。', model: ref,
    })
    const row = await historyRow(user, rSession, events)
    const done = events.find((e) => e.type === 'done')?.data
    const blocksList = row?.blocks?.blocks ?? []
    const kinds = blocksList.map((b) => b.kind)
    const processText = blocksList.filter((b) => b.kind === 'process').map((b) => b.text).join('')
    const thinkText = blocksList.filter((b) => b.kind === 'think').map((b) => b.text).join('')
    const answerText = blocksList.filter((b) => b.kind === 'text' && b.channel === 'answer').map((b) => b.text).join('')
    const problems = []
    if (!kinds.includes('process')) problems.push('没有 process 块')
    if (blocksList.filter((b) => b.kind === 'process').length !== 1) problems.push('过程块被拆成多段（过程与正文同 delta 时顺序错了）')
    if (!kinds.includes('think')) problems.push('没有 think 块（mock 的 reasoning_content 没被框架收下？）')
    if (processText !== '正在核对引用编号。') problems.push(`process 块文本不对：${JSON.stringify(processText)}`)
    if (answerText !== '这是最终答案。') problems.push(`答案文本不对：${JSON.stringify(answerText)}`)
    if (String(row?.content ?? '') !== '这是最终答案。') problems.push('落库 content 混进了标签或过程')
    if (!(done?.related ?? []).includes('建议一')) problems.push('推荐块没被解析成 related')
    console.log(`\n[richtext] ${JSON.stringify({
      kinds, process: processText, thinkChars: thinkText.length, answer: answerText,
      related: done?.related, contentCol: row?.content,
    })}`)
    problems.forEach((p) => console.log(`  ✗ ${p}`))
    if (!problems.length) console.log('  ✓ 思考/过程/正文/推荐块四路分流正确，正文没有标签残留')
    results.push({ ok: problems.length === 0, problems, events, historyRow: row })
    writeFixture('richtext', { events, row, guest: null })
  }

  // ── stall：空转（同一工具同一结果连发）→ 引擎自动终止 ────────────
  if (want('stall')) {
    const sSession = crypto.randomUUID()
    const events = await chatStream(user, {
      sessionId: sSession, question: '[[LOOP]] 空转用例：反复读同一个文件。', model: ref,
    })
    const row = await historyRow(user, sSession, events)
    const done = events.find((e) => e.type === 'done')?.data
    const notice = ((row?.blocks?.blocks ?? []).filter((b) => b.kind === 'notice'))
    const toolBlocks = (row?.blocks?.blocks ?? []).filter((b) => b.kind === 'tool')
    const warns = events.filter((e) => e.type === 'warn').map((e) => String(e.data))
    const STALL = '连续多步没有产生新信息，已自动终止本轮'
    const problems = []
    if (!warns.some((w) => w.includes('连续多步没有产生新信息'))) problems.push('没有空转 warn')
    if (notice[0]?.tone !== 'stalled') problems.push(`notice.tone 应为 stalled，实际 ${notice[0]?.tone}`)
    if (!done) problems.push('空转收轮没有 done')
    if (done && !String(done.finalContent ?? '').includes(STALL)) problems.push('done.finalContent 没带空转尾标')
    if (toolBlocks.length < 5) problems.push(`工具卡应 ≥5，实际 ${toolBlocks.length}`)
    console.log(`\n[stall] ${JSON.stringify({
      warns: warns.slice(0, 2), notice: notice.map((n) => n.tone), toolBlocks: toolBlocks.length,
      contentTail: String(done?.finalContent ?? '').slice(-40),
    })}`)
    problems.forEach((p) => console.log(`  ✗ ${p}`))
    if (!problems.length) console.log('  ✓ 空转终止：warn + notice#stalled + 尾标，工具卡保留')
    results.push({ ok: problems.length === 0, problems, events, historyRow: row })
    writeFixture('stall', { events, row, guest: null })
  }

  // ── detach：停止时「别的标签立刻收尾，发起者还能收到 done」 ────────
  // 需要长回答网关（默认 mock 2.4s 就答完了，来不及在跑到一半时接第二路观众）。
  if (want('detach')) {
    if (!process.env.DETACH_CASE) {
      console.log('[detach] 默认跳过（DETACH_CASE=1 + 长回答网关单独跑）')
    } else {
      const sid = crypto.randomUUID()
      const events = []
      const turn = chatStream(user, {
        sessionId: sid, question: 'blocks 端到端第七问：双路接流。', model: ref,
      }, (type, data) => events.push({ type, data }))
      await new Promise((ok) => setTimeout(ok, 1500))
      const viewer = openViewer(`/api/ai/chat/resume?sessionId=${encodeURIComponent(sid)}`, user)
      await new Promise((ok) => setTimeout(ok, 900))
      const viewerSawSnapshot = viewer.events.some((e) => e.type === 'snapshot' && e.data?.blocks)
      const stoppedAt = Date.now()
      const stopped = await api('/api/ai/chat/stop', { method: 'POST', token: user, body: { sessionId: sid } })
      await turn
      await viewer.done
      const done = events.find((e) => e.type === 'done')?.data
      const notice = (done?.blocks?.blocks ?? []).filter((b) => b.kind === 'notice')
      const problems = []
      if (!viewerSawSnapshot) problems.push('第二路接流没拿到带 blocks 的快照')
      if (!viewer.closed) problems.push('第二路接流没被关掉')
      if (viewer.closed && viewer.closedAt > 4000) problems.push(`第二路接流关得太慢：${viewer.closedAt}ms`)
      if (!done) problems.push('发起者这一路没收到 done（停止后仍应发终态）')
      if (notice[0]?.tone !== 'stopped') problems.push(`发起者 done 里没有 notice#stopped（实际 ${notice[0]?.tone}）`)
      console.log(`\n[detach] ${JSON.stringify({
        stopAccepted: !!stopped.json?.data?.stopped,
        viewerSawSnapshot, viewerClosedAfterMs: viewer.closedAt, viewerFrames: viewer.events.map((e) => e.type),
        viewerSnapshotRaw: viewer.events.find((e) => e.type === 'snapshot')?.raw,
        initiatorDone: !!done, initiatorNotice: notice.map((n) => n.tone),
        initiatorDoneAfterMs: done ? Date.now() - stoppedAt : null,
      })}`)
      problems.forEach((p) => console.log(`  ✗ ${p}`))
      if (!problems.length) console.log('  ✓ 旁观接流立刻收尾，发起者仍收到带 notice 的 done')
      results.push({ ok: problems.length === 0, problems, events, historyRow: null })
      writeFixture('detach', {
        events, guest: null, viewer: { frames: viewer.events.map((e) => e.type), closedAt: viewer.closedAt },
      })
    }
  }

  if (!KEEP) {
    for (const sid of [sessionId]) await api(`/api/ai/session/${sid}`, { method: 'DELETE', token: user })
  }

  const failed = results.filter((r) => !r.ok)
  console.log(`\n[结论] ${results.length - failed.length}/${results.length} 个用例一致；`
    + `夹具目录 ${FIXTURE_DIR}${KEEP ? '（数据已保留）' : ''}`)
  if (failed.length) process.exit(1)
}

/** /session/{id} 直接回消息数组（老形态是 {messages:[..]}），两种都认。 */
const assistantRow = (history) => {
  const arr = Array.isArray(history) ? history : (history?.messages ?? [])
  return arr.filter((m) => m.role === 'assistant').pop() ?? null
}

/** 拿不到助手行时把两边现场打出来：多半是这一轮根本没跑成（error 事件）或历史接口换了形态。 */
/** /api/ai 前缀不能少：BASE 只是 context path `/ai`，控制器自己挂在 `/api/ai` 下。 */
async function historyRow(token, sessionId, events) {
  const res = await api(`/api/ai/session/${sessionId}`, { token })
  const row = assistantRow(res.json?.data)
  if (!row) {
    console.log('  [diag] 事件类型：', events.map((e) => e.type).join(','))
    const err = find(events, 'error')
    if (err) console.log('  [diag] error 载荷：', JSON.stringify(err.data))
    console.log('  [diag] 历史响应：', res.text.slice(0, 300))
  }
  return row
}

/** 分享页读的那一份：建分享（幂等）后走免登录读端点 `/share/session/{token}`。 */
async function guestRow(sessionId, token) {
  const created = await api(`/api/ai/session/${sessionId}/share`, { method: 'POST', token, body: {} })
  const shareToken = created.json?.data?.token
  if (!shareToken) {
    console.log('  [diag] 建分享失败：', created.text)
    return null
  }
  const pub = await api(`/api/ai/share/session/${shareToken}`)
  const messages = pub.json?.data?.messages ?? []
  if (!messages.length) console.log('  [diag] 公开历史为空：', pub.text.slice(0, 200))
  return messages.filter((m) => m.role === 'assistant').pop() ?? null
}

function writeFixture(name, { events, row, guest }) {
  /** FIXTURE_PREFIX：真模型跑同一个用例时别覆盖 mock 夹具（如 FIXTURE_PREFIX=realmodel-）。 */
  const file = join(FIXTURE_DIR, `${process.env.FIXTURE_PREFIX || ''}${name}.json`)
  writeFileSync(file, JSON.stringify({
    capturedAt: new Date().toISOString(),
    events: events.map((e) => ({ type: e.type, data: e.data })),
    message: row, guestMessage: guest,
  }, null, 2))
  console.log(`  fixture → ${file}`)
}

main().catch((e) => { console.error('[FAIL]', e.message); process.exit(1) })
