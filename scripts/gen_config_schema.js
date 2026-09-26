#!/usr/bin/env node
/**
 * 配置字段定义一致性校验
 *
 * schema 化之后字段定义只有一处：src/main/resources/config-schema.json（后端启动加载，
 * 既下发给前端渲染设置页，也用于后端保存校验），因此原先"前端 schema ↔ 后端白名单 / TIER"
 * 的双向比对已无必要（那时的比对靠正则解析源码，连 tool.knowledgeRetrieval.enabled 这种
 * 带点的键都解析不了）。现在只剩一处真源漂移风险需要守住：
 *
 *   字段定义 ↔ ConfigService.defaults()
 *
 * snapshot() 只回显 defaults() 里存在的键，所以「字段有、defaults 没有」会导致该配置项
 * 永远回显表单默认值（用户改了也看不出变化）；反过来「defaults 有、字段没有」属正常的
 * 只读/内部配置（如 embedding.dimensions、eval.lastReport），只做提示不算错误。
 *
 * 用法：node scripts/gen_config_schema.js
 */
const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '..')
const javaPath = ROOT + '/src/main/java/com/wisesoft/ai/service/ConfigService.java'
const schemaPath = ROOT + '/src/main/resources/config-schema.json'

const schema = JSON.parse(fs.readFileSync(schemaPath, 'utf8'))
const java = fs.readFileSync(javaPath, 'utf8')

const dStart = java.indexOf('private Map<String, String> defaults()')
const dEnd = java.indexOf('private void syncProperties()')
const defaults = new Set()
for (const m of java.slice(dStart, dEnd).matchAll(/d\.put\("([a-zA-Z.]+)"/g)) defaults.add(m[1])

const fieldKeys = schema.fields.map(f => f.backendKey)
const dupes = [...new Set(fieldKeys.filter((k, i) => fieldKeys.indexOf(k) !== i))]

console.log('字段定义：' + fieldKeys.length + ' 项 / ' + schema.panels.length + ' 面板 / 文案 ' +
  Object.keys(schema.tips || {}).length + ' 条 / 核心项 ' + (schema.corePaths || []).length + ' 个')
console.log('defaults()：' + defaults.size + ' 项')
console.log('')

let problems = 0

if (dupes.length) {
  problems++
  console.log('【字段 backendKey 重复】：')
  for (const k of dupes) console.log('  ' + k)
  console.log('')
}

const noDefault = fieldKeys.filter(k => !defaults.has(k))
if (noDefault.length) {
  problems++
  console.log('【字段有、defaults() 没有】该配置项回显不出库中值（设置页只会显示表单默认值）：')
  for (const k of noDefault) console.log('  ' + k)
  console.log('')
}

const noField = [...defaults].filter(k => !fieldKeys.includes(k))
if (noField.length) {
  console.log('【defaults() 有、字段没有】属只读/内部配置（不经设置页下发，仅提示）：')
  for (const k of noField) console.log('  ' + k)
  console.log('')
}

const noPath = schema.fields.filter(f => !f.path)
if (noPath.length) {
  problems++
  console.log('【字段缺少 path】会导致 buildDefaultForm 崩：')
  for (const f of noPath) console.log('  ' + f.backendKey)
  console.log('')
}

if (!problems) console.log('字段定义与 defaults() 一致，无问题')
process.exit(problems ? 1 : 0)
