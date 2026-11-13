#!/usr/bin/env node
/**
 * v1 开放契约闸门（B0 口径冻结 / B2 单一真源的地基）。
 *
 * 真源是后端源码里的 @OpenApi + Spring 路由注解（OpenApiRegistry 启动期扫的也是这一份），
 * 快照 contract/openapi.v1.json 是「已经对外承诺过的那一版」。两种用法：
 *   node scripts/check-openapi-contract.mjs --check    CI：删端点/改 scope/改可达性一律失败；
 *                                                     新增也要跑 --update 记录后才放行。
 *   node scripts/check-openapi-contract.mjs --update   重新生成快照，并往 contract/CHANGELOG.md 追加草稿
 *                                                     条目（人工只补「为什么改」）。
 *
 * 刻意不做「自动放行新增」：新增端点虽符合「只增不删」，但没进快照就等于没有承诺记录，
 * 变更日志也就漏了一条。让人显式跑一次 --update，是这扇闸门唯一的价值。
 *
 * 静态扫描而不是起后端拉 openapi.json：CI 里跑一条 node 命令就够，不需要 JDK/数据库/Redis，
 * 也不会因为服务起不来而把契约检查糊过去。
 */
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = fileURLToPath(new URL('..', import.meta.url))
const SRC = join(ROOT, 'src/main/java')
const SNAPSHOT = join(ROOT, 'contract/openapi.v1.json')
const CHANGELOG = join(ROOT, 'contract/CHANGELOG.md')

/** OpenApiScopes 常量的字面值；新增 scope 必须在这里登记，否则脚本直接报错而不是静默记成空 */
const SCOPES = {
  CHAT: 'chat',
  EMBEDDING: 'embedding',
  KB_READ: 'kb.read',
  KB_WRITE: 'kb.write',
  WORKFLOW_RUN: 'workflow.run',
}

const walk = dir => readdirSync(dir, { withFileTypes: true }).flatMap(e => {
  const p = join(dir, e.name)
  return e.isDirectory() ? walk(p) : (p.endsWith('.java') ? [p] : [])
})

function scanFile(file) {
  const src = readFileSync(file, 'utf8')
  // 类级 @RequestMapping 只看类声明之前那段，避免把方法级路由误当成前缀
  const head = src.slice(0, Math.max(0, src.indexOf('public class')))
  const cls = head.match(/@RequestMapping\(\s*"([^"]+)"\s*\)/)
  const prefix = cls ? cls[1].replace(/\/$/, '') : ''
  const out = []
  const ann = /@OpenApi\(([^)]*)\)/g
  let m
  while ((m = ann.exec(src)) !== null) {
    const attrs = m[1]
    const route = src.slice(ann.lastIndex, ann.lastIndex + 600)
      .match(/@(Get|Post|Put|Delete|Patch)Mapping\(\s*"([^"]+)"/)
    if (!route) throw new Error(`${file}: @OpenApi 之后找不到 @*Mapping 路由注解`)
    let path = route[2]
    // Spring 里方法级 "/{id}/run" 虽然以 / 开头，仍是相对类级 @RequestMapping 的；
    // 只有完整写出 /api/ai/v1/... 的才是绝对路径。按「是否以 / 开头」判断会漏拼前缀，
    // 快照里就会留下 /{id}/run 这种根本不存在的路径，闸门随即失真。
    if (!path.startsWith('/api/ai/')) path = `${prefix}/${path.replace(/^\//, '')}`
    const sm = attrs.match(/scope\s*=\s*(?:OpenApiScopes\.([A-Z_]+)|"([^"]*)")/)
    let scope = ''
    if (sm) {
      if (sm[1] && !(sm[1] in SCOPES)) throw new Error(`${file}: 未知 OpenApiScopes.${sm[1]}，请同步 SCOPES 表`)
      scope = sm[1] ? SCOPES[sm[1]] : (sm[2] || '')
    }
    const summary = (attrs.match(/summary\s*=\s*"([^"]*)"/) || [, ''])[1]
    out.push({
      method: route[1].toUpperCase(),
      path,
      scope,
      summary,
      keyAccessible: !/keyAccessible\s*=\s*false/.test(attrs),
    })
  }
  return out
}

const keyOf = e => `${e.method} ${e.path}`
const current = walk(SRC).flatMap(scanFile).sort((a, b) => keyOf(a).localeCompare(keyOf(b)))
if (!current.length) throw new Error('没有扫到任何 @OpenApi 端点，扫描规则可能已失效')
const dup = current.find((e, i) => i > 0 && keyOf(e) === keyOf(current[i - 1]))
if (dup) throw new Error(`路由重复登记：${keyOf(dup)}`)

const mode = process.argv.includes('--update') ? 'update' : 'check'
const prior = existsSync(SNAPSHOT) ? JSON.parse(readFileSync(SNAPSHOT, 'utf8')).endpoints : null
const before = new Map((prior || []).map(e => [keyOf(e), e]))
const now = new Map(current.map(e => [keyOf(e), e]))
const added = current.filter(e => !before.has(keyOf(e)))
const removed = (prior || []).filter(e => !now.has(keyOf(e)))
const changed = current.filter(e => {
  const old = before.get(keyOf(e))
  return old && (old.scope !== e.scope || old.keyAccessible !== e.keyAccessible)
})

if (mode === 'update') {
  mkdirSync(join(ROOT, 'contract'), { recursive: true })
  writeFileSync(SNAPSHOT, JSON.stringify({
    version: 'v1',
    generatedFrom: 'src/main/java 的 @OpenApi + Spring 路由注解（与 OpenApiRegistry 同源）',
    note: '由 scripts/check-openapi-contract.mjs --update 生成；不要手改',
    endpoints: current,
  }, null, 2) + '\n')
  const date = new Date().toISOString().slice(0, 10)
  const lines = [`## ${date}`, '']
  if (!prior) {
    lines.push(`- 初始基线：${current.length} 个 v1 端点纳入契约快照。`)
  } else {
    added.forEach(e => lines.push(`- 新增 \`${keyOf(e)}\`${e.scope ? `（scope: ${e.scope}）` : '（任意有效 Key）'}：${e.summary}`))
    removed.forEach(e => lines.push(`- **删除** \`${keyOf(e)}\`：破坏性变更，需走废弃期`))
    changed.forEach(e => lines.push(`- **变更** \`${keyOf(e)}\`：scope/可达性调整，需公告`))
    if (!added.length && !removed.length && !changed.length) lines.push('- 无契约变化（仅摘要文案调整或例行重生成）。')
  }
  lines.push('', '> 为什么改：<!-- 人工补；自动条目只描述「改了什么」 -->', '')
  const old = existsSync(CHANGELOG) ? readFileSync(CHANGELOG, 'utf8') : '# v1 契约变更日志\n\n'
  writeFileSync(CHANGELOG, old + lines.join('\n') + '\n')
  console.log(`快照已更新：${current.length} 个端点；变更日志已追加 ${date} 条目`)
  process.exit(0)
}

console.log(`当前 v1 端点：${current.length} 个${prior ? `（快照 ${prior.length} 个）` : '（尚无快照，请跑 --update）'}`)
let bad = false
if (!prior) {
  console.error('缺少契约快照 contract/openapi.v1.json，请先跑 --update 生成基线')
  bad = true
} else {
  removed.forEach(e => { console.error(`  [破坏] 端点被删除：${keyOf(e)}（v1 只增不删）`); bad = true })
  changed.forEach(e => {
    const old = before.get(keyOf(e))
    console.error(`  [破坏] ${keyOf(e)} 契约变化：scope ${old.scope || '任意Key'} → ${e.scope || '任意Key'}，`
      + `keyAccessible ${old.keyAccessible} → ${e.keyAccessible}`)
    bad = true
  })
  added.forEach(e => { console.error(`  [待确认] 新增 ${keyOf(e)}：跑 --update 把它写进快照与变更日志`); bad = true })
}
if (bad) {
  console.error('\n契约检查未通过。变更口径见 contract/README.md')
  process.exit(1)
}
console.log('契约检查通过：无删除、无 scope/可达性变更、无未登记新增（只增不删）')
