import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

// 子组件上下文（chatpage/*.vue）靠父页 `pickChatCtx({ ...493 个裸标识符... })` 装配。
// 这个对象字面量在 setup 里**立即求值**，所以它引用的每个变量都必须在那一行之前已用
// const/let 声明；否则触发 JS 的 TDZ（Cannot access 'x' before initialization），
// 表现为页面一加载就白屏。
//
// 这类缺陷 lint（不识 TDZ）、vite build（只做语法与模块解析）、以及当时的全部单测
// 都抓不到——2026-11 拆分 ChatPage 时就是这样漏出去的（415 个键在声明前被引用）。
// 故这里做静态时序对账：按出现顺序收集声明，断言调用点之前全部就绪。

const CHATPAGE = 'src/views/ChatPage.vue'
const CTX = 'src/views/chatpage/chatCtx.js'

const scriptSetup = file => {
  const src = readFileSync(file, 'utf8')
  const m = /<script setup>([\s\S]*?)<\/script>/.exec(src)
  assert.ok(m, `${file} 缺少 <script setup>`)
  return { body: m[1], offset: src.slice(0, m.index).split('\n').length }
}

// 按出现顺序记录「名字 -> 行号」，覆盖单行/多行 import 与单行/多行解构。
const collectDecls = (lines, offset) => {
  const decls = new Map()
  const add = (name, absLine) => { if (!decls.has(name)) decls.set(name, absLine) }
  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    if (/^\s*import\b/.test(line)) {
      let buf = line
      while (!/from\s+['"]/.test(buf) && i + 1 < lines.length) buf += ' ' + lines[++i]
      const spec = buf.replace(/^\s*import\s*/, '').split(' from ')[0]
      for (const n of spec.match(/[A-Za-z_$][\w$]*/g) || []) {
        if (n !== 'as' && n !== 'type') add(n, offset + i)
      }
    } else if (/^\s*(?:const|let|var)\s*\{/.test(line)) {
      let buf = line
      const start = i
      while (!buf.includes('}') && i + 1 < lines.length) buf += '\n' + lines[++i]
      for (const n of (buf.split('}', 1)[0].match(/[A-Za-z_$][\w$]*/g) || [])) {
        if (n !== 'const' && n !== 'let' && n !== 'var') add(n, offset + start)
      }
    } else {
      const m = /^\s*(?:const|let|var)\s+([A-Za-z_$][\w$]*)/.exec(line)
      if (m) add(m[1], offset + i)
    }
    i += 1
  }
  return decls
}

const ctxKeys = () => {
  const src = readFileSync(CTX, 'utf8')
  const m = /CHAT_CTX_KEYS\s*=\s*\[([\s\S]*?)\]/.exec(src)
  assert.ok(m, 'chatCtx.js 缺少 CHAT_CTX_KEYS')
  return [...m[1].matchAll(/'([A-Za-z_$][\w$]*)'/g)].map(x => x[1])
}

test('ChatPage 的子组件上下文装配不得早于任何键的声明（TDZ 白屏）', () => {
  const { body, offset } = scriptSetup(CHATPAGE)
  const lines = body.split('\n')
  const callIdx = lines.findIndex(l => l.includes('pickChatCtx({'))
  assert.ok(callIdx >= 0, 'ChatPage 未调用 pickChatCtx')
  const callLine = offset + callIdx

  const decls = collectDecls(lines, offset)
  const keys = ctxKeys()
  assert.ok(keys.length > 100, `上下文键数异常：${keys.length}`)

  const late = []
  const missing = []
  for (const k of keys) {
    if (!decls.has(k)) { missing.push(k); continue }
    if (decls.get(k) > callLine) late.push(`${k}(声明于 ${decls.get(k)}，装配于 ${callLine})`)
  }

  assert.deepEqual(missing, [], '这些键在 setup 里找不到声明（拼错或已删）：')
  assert.deepEqual(late, [], '这些键在 pickChatCtx 求值时还没初始化，会抛 ReferenceError 白屏：')
})

test('provide(chatCtx) 与装配在同一处，且在 setup 内', () => {
  const src = readFileSync(CHATPAGE, 'utf8')
  const { body } = scriptSetup(CHATPAGE)
  assert.match(body, /provide\('chatCtx', chatCtx\)/, '必须先 provide 再渲染子组件')
  const call = body.indexOf('pickChatCtx({')
  const prov = body.indexOf("provide('chatCtx'")
  assert.ok(prov > call, 'provide 必须在装配之后')
  assert.ok(!/[\s\S]*export/.test(src.slice(src.indexOf('</script>'))), '')
})