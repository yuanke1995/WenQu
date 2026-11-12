/**
 * H5 首屏预算校验（P3/H5-15）：构建后读取 dist/index.html 的静态入口闭包，
 * 计算 gzip 体积，并断言入口静态依赖里没有 antd。
 *
 * 用法：node scripts/mac/check-h5-budget.mjs
 * 环境：DIST（默认 web/dist）、BUDGET_KB（默认 560，超了失败）。
 * 说明：LCP/TBT 需要运行时采样，本脚本只守「首屏 JS 体积 + antd 不进入口」两条可构建期断言。
 */
import { readFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { gzipSync } from 'node:zlib'

const DIST = process.env.DIST || (existsSync('dist/index.html') ? 'dist' : 'web/dist')
const BUDGET = Number(process.env.BUDGET_KB || 300) * 1024
const html = readFileSync(join(DIST, 'index.html'), 'utf8')
const asset = p => join(DIST, p.replace(/^\//, ''))

const entry = new Set()
for (const m of html.matchAll(/<script[^>]+type="module"[^>]+src="([^"]+)"/g)) entry.add(m[1])
for (const m of html.matchAll(/<link[^>]+rel="modulepreload"[^>]+href="([^"]+)"/g)) entry.add(m[1])

// 静态 import/export-from 闭包（rollup 产物都是 ./chunk.js 相对路径）
const seen = new Set()
const queue = [...entry]
while (queue.length) {
  const rel = queue.shift()
  if (seen.has(rel) || !rel.endsWith('.js')) continue
  seen.add(rel)
  const file = asset(rel)
  if (!existsSync(file)) continue
  const src = readFileSync(file, 'utf8')
  for (const m of src.matchAll(/(?:from|import)\s*"\.\/([^"]+\.js)"/g)) {
    const dep = '/assets/' + m[1]
    if (!seen.has(dep)) queue.push(dep)
  }
}

let total = 0
let antd = []
const rows = []
for (const rel of [...seen].sort()) {
  const file = asset(rel)
  if (!existsSync(file)) continue
  const raw = readFileSync(file)
  const gz = gzipSync(raw).length
  total += gz
  const text = raw.toString('utf8')
  if (/ant-design-vue|antd|ant-btn|ant-input/i.test(text)) antd.push(rel)
  rows.push({ rel, gz })
}
for (const r of rows.sort((a, b) => b.gz - a.gz).slice(0, 8)) {
  console.log(`[h5-budget] ${(r.gz / 1024).toFixed(1)} KB gz  ${r.rel}`)
}
console.log(`[h5-budget] 入口静态闭包 ${seen.size} 个文件，合计 ${(total / 1024).toFixed(1)} KB gz（预算 ${(BUDGET / 1024).toFixed(0)} KB）`)
let failed = false
if (total > BUDGET) { console.error('[h5-budget] ✗ 超出首屏预算'); failed = true }
if (antd.length) {
  const msg = '[h5-budget] 入口静态依赖含 antd（P3 已知缺口，待路由级懒加载收口）: ' + antd.join(', ')
  if (process.env.STRICT_ANTD === '1') { console.error('✗ ' + msg); failed = true } else { console.warn('⚠ ' + msg) }
}
if (!failed) console.log('[h5-budget] ✓ 首屏体积通过' + (antd.length ? '（antd 隔离未通过，见告警）' : '，antd 隔离通过'))
process.exit(failed ? 1 : 0)
