/**
 * 现网落库行核对（离线，只吃一份只读 SQL 导出的 TSV，不连库、不写库）。
 *
 * 与 `check-fixtures.mjs` 同一套判据，只是数据来自现网真模型跑出来的行：
 *   1. 正文：`applyBlocks` 重建的 content 与 `content` 列逐字符相等；
 *   2. 工具卡：重建的 toolCalls 与 `tool_calls` 列键序无关地相等；
 *   3. 产物：每个 `artifact` 块的文件名都在 `artifacts` 列里；
 *   4. notice：tone 合法且 text 非空；
 *   5. 答案边界：`answer` 块非空，且全部排在最后一个工具块之后（服务端定边界，前端不再反推）；
 *   6. 双写：有 blocks 的助手行旧列（content/timeline/tool_calls）必须同时有值——否则回滚即丢显示。
 *
 * 导出（只读，逐列顺序不能变）：
 *   scripts/loadtest/prod-rows.sql 的 SELECT，或见本文件底部 --dump 提示。
 * 用法：
 *   node scripts/loadtest/check-prod-rows.mjs /tmp/prod-rows.tsv
 */
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { applyBlocks } from '../../web/src/chat/blocks.js'

const COLS = ['id', 'session_id', 'role', 'create_time', 'end_reason', 'turn_id',
  'content', 'thinking', 'process_text', 'timeline', 'tool_calls', 'artifacts', 'blocks', 'tokens']
const TONES = new Set(['stopped', 'budget', 'stalled', 'timeout', 'truncated'])

const canon = (v) => JSON.stringify(v, (k, x) =>
  x && typeof x === 'object' && !Array.isArray(x)
    ? Object.fromEntries(Object.keys(x).sort().map((kk) => [kk, x[kk]]))
    : x)

/** mysql -N -B 的转义：\\n \\t \\r \\0 与双反斜杠。 */
const unescape = (s) => s.replace(/\\(.)/g, (_, c) => (c === 'n' ? '\n' : c === 't' ? '\t' : c === 'r' ? '\r' : c === '0' ? '\0' : c))
const parseJson = (s) => {
  if (!s) return null
  try { return JSON.parse(s) } catch { return null }
}

const file = process.argv[2]
if (!file) {
  console.error('用法：node scripts/loadtest/check-prod-rows.mjs <只读导出的 TSV>')
  process.exit(2)
}

const problems = []
let assistants = 0
let withBlocks = 0
const rows = readFileSync(file, 'utf8').split('\n').filter((l) => l.trim()).map((line) => {
  const cells = line.split('\t').map(unescape)
  if (cells.length !== COLS.length) problems.push(`列数不符（${cells.length}≠${COLS.length}）：${line.slice(0, 80)}`)
  return Object.fromEntries(COLS.map((c, i) => [c, cells[i] === 'NULL' ? '' : cells[i] ?? '']))
})

for (const row of rows) {
  if (row.role !== 'assistant') continue
  assistants++
  const blocks = parseJson(row.blocks)
  if (!blocks || !Array.isArray(blocks.blocks)) {
    console.log(`[跳过] ${row.create_time} ${row.id.slice(0, 8)} 无 blocks（存量行，前端走 timeline 兜底）`)
    continue
  }
  withBlocks++

  const view = { role: 'ai' }
  applyBlocks(view, blocks)
  const tag = `${row.create_time} ${row.turn_id.slice(0, 8)}`

  if ((view.content || '') !== (row.content || '')) {
    problems.push(`${tag}: 正文不一致\n  落库=${JSON.stringify((row.content || '').slice(0, 120))}\n  重建=${JSON.stringify((view.content || '').slice(0, 120))}`)
  }
  if (canon(view.toolCalls || []) !== canon(parseJson(row.tool_calls) || [])) {
    problems.push(`${tag}: 工具卡与 tool_calls 列不一致（重建 ${view.toolCalls.length} 张 / 落库 ${(parseJson(row.tool_calls) || []).length} 张）`)
  }
  const artifacts = new Set((parseJson(row.artifacts) || []).map((a) => a.filename))
  for (const b of blocks.blocks.filter((x) => x.kind === 'artifact')) {
    if (!artifacts.has(b.filename)) problems.push(`${tag}: 产物块 ${b.filename} 在 artifacts 列里找不到`)
  }
  for (const n of blocks.blocks.filter((x) => x.kind === 'notice')) {
    if (!TONES.has(n.tone)) problems.push(`${tag}: 未知的 notice.tone=${n.tone}`)
    if (!n.text || !String(n.text).trim()) problems.push(`${tag}: notice.text 为空`)
  }

  const answer = blocks.blocks
    .filter((x) => x.kind === 'text' && x.channel === 'answer')
    .map((x) => x.text || '').join('')
  if (!answer.trim()) problems.push(`${tag}: 没有非空 answer 块`)
  const lastTool = blocks.blocks.map((x) => x.kind).lastIndexOf('tool')
  const answerIdx = blocks.blocks.findIndex((x) => x.kind === 'text' && x.channel === 'answer')
  if (answerIdx >= 0 && answerIdx < lastTool) problems.push(`${tag}: answer 块#${answerIdx} 排在工具块#${lastTool} 之前`)
  const narr = blocks.blocks.filter((x) => x.kind === 'text' && x.channel === 'narration').length
  if (answer && row.content && !row.content.includes(answer.trim().slice(0, 40))) {
    problems.push(`${tag}: content 列里找不到 answer 开头`)
  }

  if (!(row.content || '').trim()) problems.push(`${tag}: 有 blocks 但 content 列为空（双写失效）`)
  if (!row.timeline && view.toolCalls.length) problems.push(`${tag}: 有工具卡但 timeline 列为空（回滚即丢顺序）`)

  const legacyChars = (row.content || '').length + (row.thinking || '').length + (row.process_text || '').length
    + (row.timeline || '').length + (row.tool_calls || '').length + (row.artifacts || '').length
  const ratio = legacyChars ? (row.blocks.length / legacyChars) : Infinity
  console.log(`[行] ${tag} 块=${blocks.blocks.length}(${blocks.blocks.map((x) => x.kind[0]).join('')}) `
    + `answer=${answer.length}字 旁白=${narr} 工具=${view.toolCalls.length} 产物=${artifacts.size} `
    + `think=${(view.thinking || '').length}字 过程=${(view.processText || '').length}字 `
    + `notice=${blocks.blocks.filter((x) => x.kind === 'notice').map((x) => x.tone).join(',') || '-'} `
    + `旧列和=${legacyChars} blocks=${row.blocks.length}(${ratio.toFixed(2)}×)`)
}

console.log(`\n[prod-rows] 助手行 ${assistants} 条，其中带 blocks ${withBlocks} 条`)
if (problems.length) {
  for (const p of problems) console.log(`  ✗ ${p}`)
  process.exit(1)
}
if (!withBlocks) {
  console.log('  ！没有带 blocks 的行可核对（部署后还没有新轮次）')
  process.exit(3)
}
console.log('  ✓ 正文 / 工具卡 / 产物 / notice / 答案边界 / 双写 六处全部一致')

/* --dump 用的只读 SQL（列顺序必须与 COLS 一致）：
SELECT id, session_id, role, create_time, IFNULL(end_reason,''), IFNULL(turn_id,''),
       IFNULL(content,''), IFNULL(thinking,''), IFNULL(process_text,''), IFNULL(timeline,''),
       IFNULL(tool_calls,''), IFNULL(artifacts,''), IFNULL(blocks,''), IFNULL(tokens,'')
FROM c_ai_message WHERE create_time>='2026-11-06 19:22:45' ORDER BY create_time;
*/
