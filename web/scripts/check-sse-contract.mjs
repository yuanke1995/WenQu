// SSE 事件契约的静态校验：三处真源必须两两对齐，任一处漂了就判红。
// 起因：要并列第二种问答运行时（AgentScope 框架引擎）。框架产出的是它自己的 30 类事件，
// 前端认的是问渠 29 个事件名——中间那层词汇表一旦和前后端任何一侧脱节，表现是「事件发了
// 但界面没反应」或「界面等的事件名后端根本没发」，两种都不报错，只让用户看到半个回答。
// 这类漂移在只有一套运行时时不会暴露（字面量散落各处也照样跑），并列两套后就是主要风险源。
//
// 三处真源：
//   1) src/main/java/com/wenqu/ai/engine/RunEventType.java —— 契约登记表（sseName）
//   2) web/src/api.js 的 CHAT_EVENT_CB + 内联的 done/error —— 前端认的名字
//   3) 后端各处 sendSseEvent(...) 的 type 实参字面量 —— 实际发出去的名字
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const WEB = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const BACKEND = path.join(WEB, '..', 'src', 'main', 'java', 'com', 'wenqu', 'ai')

const enumPath = path.join(BACKEND, 'engine', 'RunEventType.java')
const apiPath = path.join(WEB, 'src', 'api.js')
if (!fs.existsSync(enumPath)) {
  console.log('SKIP  未找到 engine/RunEventType.java（词汇表尚未落地），跳过')
  process.exit(0)
}

const failures = []

/** 词汇表里的 SSE 事件名（枚举常量 + sseName 字面量） */
const enumSrc = fs.readFileSync(enumPath, 'utf8')
const vocabulary = new Map()
for (const m of enumSrc.matchAll(/^\s*([A-Z][A-Z0-9_]*)\("([a-z0-9_]+)"\)/gm)) {
  vocabulary.set(m[2], m[1])
}

/** 前端 CHAT_EVENT_CB 的键 + 终止事件 done/error（刻意不在表里，见 api.js 注释） */
const apiSrc = fs.readFileSync(apiPath, 'utf8')
const tableStart = apiSrc.indexOf('const CHAT_EVENT_CB = {')
if (tableStart < 0) {
  console.error('FAIL  在 web/src/api.js 找不到 CHAT_EVENT_CB——前端事件表被改名或删除，契约校验本身失效')
  process.exit(1)
}
const tableSrc = apiSrc.slice(tableStart, apiSrc.indexOf('\n}', tableStart))
const frontend = new Set()
for (const m of tableSrc.matchAll(/([a-z][a-z0-9_]*)\s*:\s*'on[A-Za-z0-9_]+'/g)) frontend.add(m[1])
for (const terminal of ['done', 'error']) {
  if (!new RegExp(`d\\.type\\s*===\\s*'${terminal}'|case\\s*'${terminal}'|'${terminal}'`).test(apiSrc)) {
    failures.push(`前端不再识别终止事件 '${terminal}'，但它仍在词汇表里`)
  }
  frontend.add(terminal)
}

/** 后端实际发出的事件名：sendSseEvent(...) 第二个实参的字面量 */
const emitted = new Map()
const walk = dir => {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) { walk(full); continue }
    if (!full.endsWith('.java')) continue
    const src = fs.readFileSync(full, 'utf8')
    const lines = src.split('\n')
    lines.forEach((line, i) => {
      const stripped = line.replace(/\/\/.*$/, '')
      for (const m of stripped.matchAll(/sendSseEvent\([^,()]+,\s*"([a-z0-9_]+)"/g)) {
        if (!emitted.has(m[1])) emitted.set(m[1], `${path.relative(path.join(WEB, '..'), full)}:${i + 1}`)
      }
    })
  }
}
walk(BACKEND)

// 判据 1：后端发出的名字必须在词汇表里（新增事件必须先登记，否则框架侧永远发不出它）
for (const [name, where] of emitted) {
  if (!vocabulary.has(name)) failures.push(`后端发出事件 '${name}'（${where}）未登记进 RunEventType`)
}
// 判据 2：词汇表与前端一一对应（谁多出来都是断约）
for (const name of vocabulary.keys()) {
  if (!frontend.has(name)) failures.push(`词汇表里的 '${name}' 前端不认（api.js CHAT_EVENT_CB 缺该键）`)
}
for (const name of frontend) {
  if (!vocabulary.has(name)) failures.push(`前端认的 '${name}' 不在词汇表里（框架引擎发不出它）`)
}

console.log(`事件契约核对：词汇表 ${vocabulary.size} · 前端 ${frontend.size} · 后端发出的名字 ${emitted.size}`)
if (failures.length) {
  for (const f of failures) console.error('FAIL  ' + f)
  console.error('\n三处真源必须一起改：engine/RunEventType.java、web/src/api.js 的 CHAT_EVENT_CB、发送点。')
  process.exit(1)
}
console.log('PASS  SSE 事件契约三处对齐')
