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

const projPath = path.join(W, 'chat/projections.js')
if (!fs.existsSync(projPath)) {
  console.log('SKIP  未找到 src/chat/projections.js（引擎抽取前的老结构），跳过')
  process.exit(0)
}
const proj = fs.readFileSync(projPath, 'utf8')
const m = proj.match(/export \{([\s\S]*?)\n\}/)
if (!m) { console.error('FAIL  projections.js 未找到 export { ... } 块'); process.exit(1) }
const names = m[1].split(/[,\n]/).map(s => s.trim()).filter(s => s && !s.startsWith('//'))

// 消费方清单：引擎 + 两套壳（PC ChatPage / 移动壳）。文件不存在即跳过（渐进落地）。
const CONSUMERS = [
  ['chat/useChatEngine.js', /from\s*'\.\/projections'/],
  ['views/ChatPage.vue', /from\s*'\.\.\/chat\/projections'/],
  ['h5/MobileChatPage.vue', /from\s*'\.\.\/chat\/projections'/]
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
console.log('\n全部通过')
