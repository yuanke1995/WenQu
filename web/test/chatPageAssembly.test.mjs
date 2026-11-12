import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

// ChatPage.vue 把页面逻辑拆进了若干 composable，靠 `page.<域>.<成员>` 惰性容器装配。
// 装配是纯文本接线：ChatPage 里写 `page.chrome.hideThinkPanel`，若 useChatChrome 的
// return 里没有这一项，取到的是 undefined——不报错，只是运行到调用点才抛
// TypeError: ctx.xxx is not a function。纯判据测试抓不到这种缺口（它压根没进函数体），
// 故这里做静态对账，两个方向都查：
//   正向：ChatPage 引用的 page.<域>.<成员> 必须在该 composable 的 return 里；
//   反向：ChatPage 从该 composable 解构出的名字也必须在它的 return 里（解构一个
//          不存在的 key 同样是 undefined）。

const chatPage = readFileSync('src/views/ChatPage.vue', 'utf8')

/** 域 → composable 源文件（与 ChatPage 里 `page.<域> = useXxx({` 一一对应） */
const DOMAIN_FILE = {
  editResend: 'src/chat/useEditResend.js',
  panels: 'src/chat/useCommandPanels.js',
  chrome: 'src/chat/useChatChrome.js',
  actions: 'src/chat/useChatActions.js',
  goalPanels: 'src/chat/useGoalPanels.js',
  workspace: 'src/chat/useSessionWorkspace.js',
  plan: 'src/chat/usePlanPanels.js',
  deepask: 'src/chat/useDeepAsk.js'
}

/** 去掉注释（行注释 + 块注释），避免注释里的字样被当成成员名 */
const stripComments = src => src
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/\/\/[^\n]*/g, '')

/** 取 `return { ... }` 的成员名（只看顶层；重命名 `a: b` 取 key `a`） */
const exportedKeys = (src) => {
  const at = src.lastIndexOf('return {')
  assert.ok(at >= 0, '找不到 return 块')
  const open = src.indexOf('{', at)
  let depth = 0
  let end = -1
  for (let i = open; i < src.length; i++) {
    if (src[i] === '{') depth++
    else if (src[i] === '}') {
      depth--
      if (depth === 0) { end = i; break }
    }
  }
  assert.ok(end > 0, 'return 块未闭合')
  const body = stripComments(src.slice(open + 1, end))
  const keys = []
  let depth2 = 0
  let buf = ''
  const flush = () => {
    const part = buf.trim()
    buf = ''
    if (!part) return
    // `a: b` 是重命名导出，对外暴露的是 a
    const name = part.includes(':') ? part.slice(0, part.indexOf(':')).trim() : part
    if (/^[A-Za-z_$][\w$]*$/.test(name)) keys.push(name)
  }
  for (const ch of body) {
    if (ch === '{' || ch === '[' || ch === '(') { depth2++; buf += ch; continue }
    if (ch === '}' || ch === ']' || ch === ')') { depth2--; buf += ch; continue }
    if (ch === ',' && depth2 === 0) { flush(); continue }
    buf += ch
  }
  flush()
  return keys
}

/** ChatPage 里 `page.<域> = useXxx({` 上方那个解构块的成员名 */
const destructuredNames = (dom) => {
  const m = new RegExp('\\}\\s*=\\s*\\(page\\.' + dom + '\\s*=\\s*use[A-Za-z_$][\\w$]*\\s*\\(').exec(chatPage)
  if (!m) return null
  const close = m.index
  const constAt = chatPage.lastIndexOf('const {', close)
  if (constAt < 0) return null
  const blk = chatPage.slice(constAt + 'const {'.length, close)
  return blk.split(',').map(s => s.trim()).filter(Boolean)
}

test('ChatPage 引用的每个 page.<域>.<成员> 都在对应 composable 的 return 里', () => {
  const referenced = new Map()   // 域 → 成员集合
  for (const [, dom, mem] of chatPage.matchAll(/\bpage\.([A-Za-z_$][\w$]*)\??\.([A-Za-z_$][\w$]*)/g)) {
    if (!DOMAIN_FILE[dom]) continue
    if (!referenced.has(dom)) referenced.set(dom, new Set())
    referenced.get(dom).add(mem)
  }
  assert.ok(referenced.size > 0, '没解析出任何 page.<域>.<成员> 引用')

  const missing = []
  for (const [dom, mems] of referenced) {
    const exported = new Set(exportedKeys(readFileSync(DOMAIN_FILE[dom], 'utf8')))
    for (const mem of mems) {
      if (!exported.has(mem)) missing.push(`page.${dom}.${mem}  ← ${DOMAIN_FILE[dom]} 未导出`)
    }
  }
  assert.deepEqual(missing, [], '装配期引用了未导出的成员（运行到调用点才会炸）')
})

test('ChatPage 从每个 composable 解构的成员都真实存在（解到不存在的 key 同样是 undefined）', () => {
  const missing = []
  for (const [dom, file] of Object.entries(DOMAIN_FILE)) {
    const names = destructuredNames(dom)
    if (!names) continue
    const exported = new Set(exportedKeys(readFileSync(file, 'utf8')))
    for (const name of names) {
      if (!exported.has(name)) missing.push(`${dom}: ${name}  ← ${file} 未导出`)
    }
  }
  assert.deepEqual(missing, [], '解构了 composable 没有的成员（拿到 undefined）')
})

test('ChatPage 的每个 page.<域> 都有对应的 composable 装配', () => {
  const assigned = new Set(
    [...chatPage.matchAll(/\(page\.([A-Za-z_$][\w$]*)\s*=\s*use([A-Za-z_$][\w$]*)\s*\(/g)].map(m => m[1])
  )
  const referenced = new Set(
    [...chatPage.matchAll(/\bpage\.([A-Za-z_$][\w$]*)\??\./g)].map(m => m[1])
  )
  const orphans = [...referenced].filter(d => !assigned.has(d))
  assert.deepEqual(orphans, [], '这些 page 域被引用了却没有任何 composable 往里装配')
})
