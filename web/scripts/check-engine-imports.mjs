// 引擎抽取的静态完整性校验：projections 的每个导出名若被消费方使用，必须出现在该文件的 import 里。
// 起因：M1 引擎抽取时 useChatEngine 漏 import THINK_CAPS 等 7 个名字——编译与静态检查全绿，
// 只有真浏览器渲染到 deepThinkOn 才抛 ReferenceError（引擎代码随 ChatPage 分包，报错堆栈还指不到源文件）。
// 本检查把这类漏接前移到静态：dev 不装依赖也能跑，串进 npm run check。
//
// 判据口径：只查「裸标识符使用」——import 语句区间外的 \b名字\b 命中。属性访问（obj.name）与
// 模板里同名局部变量都会误报，故对报告结果人工确认一遍再改；宁漏报不误报的实现见 inImport 区间过滤。
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const W = path.join(ROOT, 'src')

// 解构花括号/return 块内的名字提取：先剥掉整行行首与行尾注释（注释里提到引擎名字会被误当引用），
// 再取 ASCII 标识符。中文说明不含 ASCII 词，天然安全。
const destructureNames = text => text.split('\n')
  .map(l => l.replace(/\/\/.*$/, ''))
  .flatMap(l => l.match(/[A-Za-z_$][\w$]*/g) || [])

const projPath = path.join(W, 'chat/projections.js')
if (!fs.existsSync(projPath)) {
  console.log('SKIP  未找到 src/chat/projections.js（引擎抽取前的老结构），跳过')
  process.exit(0)
}
const proj = fs.readFileSync(projPath, 'utf8')
const m = proj.match(/export \{([\s\S]*?)\n\}/)
if (!m) { console.error('FAIL  projections.js 未找到 export { ... } 块'); process.exit(1) }
const names = m[1].split(/[,\n]/).map(s => s.trim()).filter(s => s && !s.startsWith('//'))

// 消费方清单：引擎 + 两套壳（PC ChatPage / 移动壳与它的子组件）。文件不存在即跳过（渐进落地）。
const CONSUMERS = [
  ['chat/useChatEngine.js', /from\s*'\.\/projections'/],
  ['views/ChatPage.vue', /from\s*'\.\.\/chat\/projections'/],
  ['h5/MobileChatPage.vue', /from\s*'\.\.\/chat\/projections'/],
  ['h5/MobileMsgRow.vue', /from\s*'\.\.\/chat\/projections'/],
  ['h5/MobileModelSheet.vue', /from\s*'\.\.\/chat\/projections'/],
  ['h5/MobileAttachSheet.vue', /from\s*'\.\.\/chat\/projections'/],
  ['h5/MobileRefSheet.vue', /from\s*'\.\.\/chat\/projections'/]
]

let bad = 0
console.log(`projections 导出 ${names.length} 个，检查 ${CONSUMERS.length} 个消费方`)
for (const [rel, fromRe] of CONSUMERS) {
  const file = path.join(W, rel)
  if (!fs.existsSync(file)) { console.log(`SKIP  ${rel}（不存在）`); continue }
  const src = fs.readFileSync(file, 'utf8')
  // 收集该文件的 import 名与 import 区间（[^}]* 不跨语句，避免贪婪跨多条 import 误报）
  const re = new RegExp(`import\\s*\\{([^}]*)\\}\\s*${fromRe.source}`, 'g')
  const got = new Set()
  const spans = []
  let mm
  while ((mm = re.exec(src))) {
    mm[1].split(/[,\n]/).forEach(s => { const t = s.trim(); if (t) got.add(t) })
    spans.push([mm.index, mm.index + mm[0].length])
  }
  const inImport = idx => spans.some(([a, b]) => idx >= a && idx < b)
  const missing = []
  for (const n of names) {
    if (got.has(n)) continue
    const reUse = new RegExp('\\b' + n + '\\b', 'g')
    let u
    while ((u = reUse.exec(src))) if (!inImport(u.index)) { missing.push(n); break }
  }
  if (missing.length) { bad++; console.log(`FAIL  ${rel} 使用了未 import 的名字：${missing.join(', ')}`) }
  else console.log(`PASS  ${rel} 的 projections 引用完整`)
}
if (bad) { console.log('\n修复：把缺失名字补进对应文件的 from \'.../projections\' import'); process.exit(1) }

// 剥注释与字符串：注释/文案里提到引擎名字会被当成裸引用（本仓已三次踩这类误报）。
const stripNoise = s => s
  .replace(/<!--[\s\S]*?-->/g, ' ')
  .replace(/\/\*[\s\S]*?\*\//g, ' ')
  .replace(/^[ \t]*\/\/.*$/gm, ' ')
  .replace(/\/\/[^\n"'`<>]*$/gm, ' ')
  .replace(/'[^'\n]*'/g, "''").replace(/"[^"\n]*"/g, '""').replace(/`[\s\S]*?`/g, '``')

// 消费方清单：解构引擎的两套壳 + 经 inject 取引擎的 sheet 族。
const listConsumers = () => {
  const dir = path.join(W, 'h5')
  const files = fs.existsSync(dir) ? fs.readdirSync(dir).filter(x => x.endsWith('.vue')).map(x => 'h5/' + x) : []
  return ['views/ChatPage.vue', 'h5/MobileChatPage.vue', ...files]
    .filter(rel => fs.existsSync(path.join(W, rel)))
}

// ==================== 第二类漏接：引用了引擎导出名却没解构 ====================
// 症状与起因同上一类：名字拼漏/引擎没导出，编译与静态检查全绿，只有真浏览器渲染到那条分支才抛
// ReferenceError。ChatPage 的引擎解构面此前无人检查，同一类漏接已复发三次（THINK_CAPS、
// MAX_HISTORY_REFS、goalModeOn），故把「文件里裸引用了引擎导出名，却不在该文件的解构名单里」前移到静态。
// 判据口径照抄第一类：只认裸标识符，属性访问（obj.name）、对象键（name:）与注释字符串先剥掉；
// 文件自己 const/let/function 声明过的同名字不算漏接。宁漏报不误报。
const engPath = path.join(W, 'chat/useChatEngine.js')
if (fs.existsSync(engPath)) {
  const eng = fs.readFileSync(engPath, 'utf8')
  // 取**最后一个** return { ... }（文件里还有 11 个函数内部的 return {，用首个会抓错块）
  const idx = eng.lastIndexOf('return {')
  const tail = idx >= 0 ? eng.slice(idx) : ''
  const end = tail.indexOf('\n  }')
  if (idx < 0 || end < 0) { console.log('FAIL  useChatEngine 未找到 return 块'); process.exit(1) }
  const keys = new Set(destructureNames(tail.slice('return {'.length, end)))
  let bad2 = 0
  for (const rel of listConsumers()) {
    const src = fs.readFileSync(path.join(W, rel), 'utf8')
    const re = /const\s*\{([^}]*)\}\s*=\s*(engine|useChatEngine\s*\()/g
    const destructured = new Set()
    let found = 0, mm
    while ((mm = re.exec(src))) {
      found++
      destructureNames(mm[1]).forEach(n => destructured.add(n))
    }
    if (!found) { console.log(`SKIP  ${rel} 没有引擎解构`); continue }
    const body = stripNoise(src).replace(/<style[\s\S]*?<\/style>/gi, ' ')
    const locals = new Set((body.match(/\b(?:const|let|var|function|class)\s+([A-Za-z_$][\w$]*)/g) || [])
      .map(s => s.replace(/\b(?:const|let|var|function|class)\s+/, '')))
    const missing = []
    for (const n of keys) {
      if (destructured.has(n) || locals.has(n)) continue
      const useRe = new RegExp('(?<![.$?\\w-])' + n + '(?![\\w$-])(?!\\s*:)')
      if (useRe.test(body)) missing.push(n)
    }
    if (missing.length) { bad2++; console.log(`FAIL  ${rel} 用了引擎导出名却没解构：${missing.join(', ')}`) }
    else console.log(`PASS  ${rel} 的引擎引用完整（解构 ${destructured.size} 名）`)
  }
  if (bad2) { console.log('\n修复：把名字补进该文件的引擎解构，或改用本页已有状态'); process.exit(1) }
}
console.log('\n全部通过')
