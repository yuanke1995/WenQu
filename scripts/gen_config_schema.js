#!/usr/bin/env node
/**
 * 配置 schema 一致性校验
 *
 * 背景：schema 化之后 configSchema.js 成为唯一真源（Settings.vue 模板里已无字段信息），
 * 生成器无法再从模板重新提取，因此本脚本改为「校验器」：以后端 ConfigService.java 的
 * EDITABLE / defaults() / TIER 为准，检查 schema 是否漏配、多余或 tier 不一致。
 *
 * 用法：node scripts/gen_config_schema.js
 */
const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '..')
const javaPath = ROOT + '/src/main/java/com/wisesoft/ai/service/ConfigService.java'
const schemaPath = ROOT + '/web/src/configSchema.js'
const java = fs.readFileSync(javaPath, 'utf8')
const schema = fs.readFileSync(schemaPath, 'utf8')

// ---- 后端：EDITABLE 白名单 ----
const editable = [...java.slice(0, java.indexOf('Map<String, Integer> TIER'))
  .matchAll(/Map\.entry\("([a-zA-Z.]+)",/g)].map(m => m[1])

// ---- 后端：defaults() 默认值 ----
const dStart = java.indexOf('private Map<String, String> defaults()')
const dEnd = java.indexOf('private void syncProperties()')
const defaults = {}
for (const m of java.slice(dStart, dEnd).matchAll(/d\.put\("([a-zA-Z.]+)",\s*"?([^")]*)/g)) {
  defaults[m[1]] = m[2].trim()
}

// ---- 后端：TIER ----
const tierBlock = java.slice(java.indexOf('Map<String, Integer> TIER'), java.indexOf('private final AiConfigMapper'))
const TIER = {}
for (const m of tierBlock.matchAll(/Map\.entry\("([a-zA-Z.]+)",\s*([123])\)/g)) TIER[m[1]] = Number(m[2])

// ---- schema 字段 ----
const fields = []
for (const line of schema.split('\n')) {
  const m = line.match(/^\s*\{ panel: "([a-zA-Z]+)", section: (-?\d+), group: "([a-zA-Z]+)", key: "([a-zA-Z]+)", path: "([a-zA-Z.]+)".*tier: (\d)/)
  if (m) {
    // 提交到后端的键名可能是 submitKey（如 upload.maxFileSizeMB → upload.maxFileSize）
    const sk = line.match(/submitKey: "([a-zA-Z]+)"/)
    fields.push({ panel: m[1], section: +m[2], group: m[3], key: m[4], path: m[5], tier: +m[6],
      backendKey: m[3] + '.' + (sk ? sk[1] : m[4]) })
  }
}
const byFull = new Map()
for (const f of fields) byFull.set(f.backendKey, f)

let problems = 0

console.log('后端 EDITABLE：' + editable.length + ' 项')
console.log('schema 字段：' + fields.length + ' 项')
console.log('')

const missing = editable.filter(k => !byFull.has(k))
if (missing.length) {
  problems++
  console.log('【后端有、schema 没有】这些配置项不会出现在设置页（不会渲染也不会提交）：')
  for (const k of missing) {
    console.log('  ' + k.padEnd(46) + ' 默认值=' + (defaults[k] !== undefined ? defaults[k] : '(defaults 中缺失，snapshot 不会返回)'))
  }
  console.log('')
}

const extra = [...byFull.keys()].filter(k => !editable.includes(k))
  .map(k => k + (byFull.get(k).key !== k.split('.')[1] ? '（schema 字段 ' + byFull.get(k).group + '.' + byFull.get(k).key + '）' : ''))
if (extra.length) {
  problems++
  console.log('【schema 有、后端 EDITABLE 没有】提交时会被后端静默丢弃：')
  for (const k of extra) console.log('  ' + k)
  console.log('')
}

const tierDiff = []
for (const [k, f] of byFull) {
  const want = TIER[k] !== undefined ? TIER[k] : 2
  if (f.tier !== want) tierDiff.push(k + '（schema=' + f.tier + ' 后端=' + want + '）')
}
if (tierDiff.length) {
  problems++
  console.log('【tier 与后端不一致】：')
  for (const t of tierDiff) console.log('  ' + t)
  console.log('')
}

const noPath = fields.filter(f => !f.path)
if (noPath.length) {
  problems++
  console.log('【缺少 path】会导致 buildDefaultForm 崩溃：')
  for (const f of noPath) console.log('  ' + f.group + '.' + f.key)
  console.log('')
}

if (!problems) console.log('一致，无问题')
process.exit(problems ? 1 : 0)
