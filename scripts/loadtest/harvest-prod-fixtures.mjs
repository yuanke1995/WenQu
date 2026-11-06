/**
 * 现网真模型轮次 → 可重放夹具（离线）：把只读导出的 TSV 里带 blocks 的助手行转成
 * `fixtures/chat-events/prod-*.json`，与 mock 夹具同形，喂给 `check-fixtures.mjs` 同一套判据。
 *
 * 脱敏口径（保持「正文=文本块拼接」这一不变量）：
 *   - 每个字符串值里的 CJK/全角字符逐字替换为「文」（等长，逐字替换可交换，拼接关系不变）；
 *   - `/app`、`/home`、`/Users` 开头的路径整段换成 `/redacted`；邮箱/URL 同样替换；
 *   - 丢弃 sessionId / 消息 id / model 引用，只留 turnId 与结构。
 * 逐行脱敏后再用 `applyBlocks` 自检：正文/工具卡对不上就跳过该行并提示，不写进夹具。
 *
 * 用法：
 *   node scripts/loadtest/harvest-prod-fixtures.mjs /tmp/prod-rows.tsv
 * TSV 的列顺序见 `check-prod-rows.mjs` 底部注释（只读，不写库）。
 */
import { mkdirSync, writeFileSync } from 'node:fs'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { applyBlocks } from '../../web/src/chat/blocks.js'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const OUT = join(ROOT, 'fixtures', 'chat-events')
const COLS = ['id', 'session_id', 'role', 'create_time', 'end_reason', 'turn_id',
  'content', 'thinking', 'process_text', 'timeline', 'tool_calls', 'artifacts', 'blocks', 'tokens']

const unescape = (s) => s.replace(/\\(.)/g, (_, c) => (c === 'n' ? '\n' : c === 't' ? '\t' : c === 'r' ? '\r' : c === '0' ? '\0' : c))
const canon = (v) => JSON.stringify(v, (k, x) =>
  x && typeof x === 'object' && !Array.isArray(x)
    ? Object.fromEntries(Object.keys(x).sort().map((kk) => [kk, x[kk]]))
    : x)

/** 逐字替换（等长）+ 整段替换（路径/URL/邮箱），对字符串是幂等的局部变换。 */
const redact = (s) => s
  .replace(/[/\\](?:app|home|Users|users)\/[^\s"'`）)]*/g, '/redacted')
  .replace(/https?:\/\/[^\s"'`）)]*/g, 'https://redacted')
  .replace(/[\w.+-]+@[\w-]+\.[\w.-]+/g, 'redacted@example.com')
  .replace(/[\u3000-\u9fff\uff00-\uffef]/g, '文')

const deep = (v) => Array.isArray(v) ? v.map(deep)
  : v && typeof v === 'object' ? Object.fromEntries(Object.entries(v).map(([k, x]) => [k, deep(x)]))
  : typeof v === 'string' ? redact(v) : v

const file = process.argv[2]
if (!file) {
  console.error('用法：node scripts/loadtest/harvest-prod-fixtures.mjs <只读导出的 TSV>')
  process.exit(2)
}

mkdirSync(OUT, { recursive: true })
const rows = readFileSync(file, 'utf8').split('\n').filter((l) => l.trim()).map((line) => {
  const cells = line.split('\t').map(unescape)
  return Object.fromEntries(COLS.map((c, i) => [c, cells[i] === 'NULL' ? '' : cells[i] ?? '']))
})

let written = 0
const skipped = []
for (const row of rows) {
  if (row.role !== 'assistant') continue
  let blocks
  try { blocks = JSON.parse(row.blocks) } catch { continue }
  if (!blocks || !Array.isArray(blocks.blocks)) { skipped.push(`${row.create_time} 无 blocks`); continue }

  const message = deep({
    turnId: row.turn_id,
    role: 'ai',
    createTime: row.create_time,
    endReason: row.end_reason || null,
    blocks,
    content: row.content,
    thinking: row.thinking || null,
    processText: row.process_text || null,
    timeline: row.timeline ? JSON.parse(row.timeline) : [],
    toolCalls: row.tool_calls ? JSON.parse(row.tool_calls) : [],
    artifacts: row.artifacts ? JSON.parse(row.artifacts) : [],
    tokens: row.tokens ? JSON.parse(row.tokens) : null,
  })

  const view = { role: 'ai' }
  applyBlocks(view, message.blocks)
  const diffs = []
  if ((view.content || '') !== (message.content || '')) diffs.push('正文')
  if (canon(view.toolCalls || []) !== canon(message.toolCalls || [])) diffs.push('工具卡')
  const names = new Set((message.artifacts || []).map((a) => a.filename))
  if ((message.blocks.blocks || []).some((b) => b.kind === 'artifact' && !names.has(b.filename))) diffs.push('产物')
  if (diffs.length) { skipped.push(`${row.create_time} 脱敏后 ${diffs.join('/')} 对不上`); continue }

  const name = `prod-${row.create_time.replace(/[-: ]/g, '').slice(0, 14)}-${row.turn_id.slice(0, 6)}`
  const payload = {
    capturedAt: row.create_time.replace(' ', 'T') + '+08:00',
    source: 'production-real-model',
    redacted: 'CJK→文；路径/URL/邮箱→redacted；丢弃 sessionId/messageId/model',
    events: null,
    message,
    guestMessage: null,
  }
  writeFileSync(join(OUT, `${name}.json`), JSON.stringify(payload, null, 2) + '\n')
  written++
}

console.log(`[harvest] 写入 ${written} 份现网夹具 → ${OUT}`)
for (const s of skipped) console.log(`  · 跳过 ${s}`)
if (!written) process.exit(3)