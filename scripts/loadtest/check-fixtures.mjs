/**
 * blocks 夹具回放核对（离线，不需要起后端）：把 `fixtures/chat-events/*.json` 里落库的那一份
 * 喂给前端纯函数 `web/src/chat/blocks.js`，逐条核对「重建出来的是不是落库那一份」。
 *
 * 查四件事：
 *   1. 正文：`applyBlocks` 重建的 content 与落库 `content` 逐字符相等；
 *   2. 工具卡：重建的 toolCalls 与 `tool_calls` 列键序无关地相等；
 *   3. 产物：每个 `artifact` 块的 filename 都能在 `artifacts` 列里找到；
 *   4. notice：tone 只能是 stopped/budget/stalled/timeout/truncated，且 `text` 非空。
 *
 * 用法：`node scripts/loadtest/check-fixtures.mjs`（全过 exit 0；有差异打印到第一处不同并 exit 1）
 */
import { readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { applyBlocks } from '../../web/src/chat/blocks.js'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const DIR = join(ROOT, 'fixtures', 'chat-events')
const TONES = new Set(['stopped', 'budget', 'stalled', 'timeout', 'truncated'])

/** 键序无关的序列化：blocks 与旧列各写各的字段顺序，逐字符比会假报。 */
const canon = (v) => JSON.stringify(v, (k, x) =>
  x && typeof x === 'object' && !Array.isArray(x)
    ? Object.fromEntries(Object.keys(x).sort().map((kk) => [kk, x[kk]]))
    : x)

const problems = []
let checked = 0
let skipped = 0

for (const file of readdirSync(DIR).sort()) {
  const fixture = JSON.parse(readFileSync(join(DIR, file), 'utf8'))
  const row = fixture.message
  if (!row || !row.blocks || !Array.isArray(row.blocks.blocks)) {
    skipped++
    continue
  }
  checked++
  const view = { role: 'ai' }
  applyBlocks(view, row.blocks)

  if ((view.content || '') !== (row.content || '')) {
    problems.push(`${file}: 正文不一致\n  落库=${JSON.stringify(row.content)}\n  重建=${JSON.stringify(view.content)}`)
  }
  if (canon(view.toolCalls || []) !== canon(row.toolCalls || [])) {
    problems.push(`${file}: 工具卡与 tool_calls 列不一致`)
  }
  const artifacts = new Set((row.artifacts || []).map((a) => a.filename))
  for (const b of row.blocks.blocks.filter((x) => x.kind === 'artifact')) {
    if (!artifacts.has(b.filename)) problems.push(`${file}: 产物块 ${b.filename} 在 artifacts 列里找不到`)
  }
  for (const n of row.blocks.blocks.filter((x) => x.kind === 'notice')) {
    if (!TONES.has(n.tone)) problems.push(`${file}: 未知的 notice.tone=${n.tone}`)
    if (!n.text || !String(n.text).trim()) problems.push(`${file}: notice.text 为空`)
  }
}

console.log(`[fixtures] 核对了 ${checked} 份带 blocks 的夹具（跳过 ${skipped} 份无 blocks 的）`)
if (problems.length) {
  for (const p of problems) console.log(`  ✗ ${p}`)
  process.exit(1)
}
console.log('  ✓ 正文 / 工具卡 / 产物 / notice 四处全部一致')
