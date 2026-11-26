import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'

// chatpage/*.vue 是 ChatPage 拆出来的子组件，它们「以为自己还在父页作用域里」：
//  - 模板里直接写 <ChatInput />、<PlusOutlined /> —— 子组件不会继承父页的 import，
//    漏了就只是运行期一条 Failed to resolve component 警告，页面默默少一块（不报错）；
//  - 模板里直接写 onKeys（以为是父页变量）——实际只有 store.onKeys 存在，
//    漏了就渲染成 undefined，交互静默失效。
// 这两类都过得了 lint（.vue 关了 no-unused-vars）与 build（只做语法/模块解析），
// 2026-11 拆分时就因此让整个消息流与输入区没渲染出来。故这里做静态对账。

const DIR = 'src/views/chatpage'
const CTX = `${DIR}/chatCtx.js`

const NATIVE = new Set(`div span button input textarea svg path img a p i b em strong ul ol li nav
section header footer label select option table tr td th pre code h1 h2 h3 h4 br hr slot component
transition transition-group keep-alive teleport suspense aside summary details circle template main
small sup sub figure figcaption dl dt dd iframe video audio source canvas
kbd mark time del ins q cite abbr s u var samp dfn fieldset legend optgroup`.split(/\s+/))

const pascal = k => k.split('-').map(s => s ? s[0].toUpperCase() + s.slice(1) : s).join('')

const files = readdirSync(DIR).filter(f => f.endsWith('.vue')).map(f => `${DIR}/${f}`)

const templateOf = src => {
  const m = /<template>([\s\S]*)<\/template>/.exec(src)
  return m ? m[1] : ''
}

const importedNames = src => {
  const out = new Set()
  const re = /import\s+(?:([A-Za-z_$][\w$]*)\s*,?\s*)?(?:\{([^}]*)\})?/g
  let m
  while ((m = re.exec(src))) {
    if (m[1]) out.add(m[1])
    if (m[2]) {
      for (const n of m[2].split(',')) {
        const name = n.trim().split(/\s+as\s+/).pop().trim()
        if (name) out.add(name)
      }
    }
  }
  return out
}

const ctxKeys = () => {
  const src = readFileSync(CTX, 'utf8')
  const m = /CHAT_CTX_KEYS\s*=\s*\[([\s\S]*?)\n\]/.exec(src)
  assert.ok(m, 'chatCtx.js 缺少 CHAT_CTX_KEYS')
  return new Set([...m[1].matchAll(/'([A-Za-z_$][\w$]*)'/g)].map(x => x[1]))
}

test('子组件模板里用到的组件/图标都必须在自己的 script 里 import', () => {
  const problems = []
  for (const file of files) {
    const src = readFileSync(file, 'utf8')
    const have = importedNames(src)
    const tags = new Set([...templateOf(src).matchAll(/<([A-Za-z][A-Za-z0-9-]*)/g)].map(m => m[1]))
    for (const t of tags) {
      if (NATIVE.has(t.toLowerCase())) continue
      const name = /^[A-Z]/.test(t) ? t : pascal(t)
      if (/^A[A-Z]/.test(name)) continue          // ant-design 全局注册
      if (have.has(name)) continue
      problems.push(`${file}: <${t} /> 未 import`)
    }
  }
  assert.deepEqual(problems, [], '子组件漏 import（运行期只会打一条警告，那块 UI 会静默消失）：')
})

test('子组件读的 store.xxx 必须在 CHAT_CTX_KEYS 里，且父页确实传了', () => {
  const keys = ctxKeys()
  const page = readFileSync('src/views/ChatPage.vue', 'utf8')
  const lit = (page.split('pickChatCtx({')[1] || '').split('}))')[0]

  const problems = []
  const provided = new Set()
  for (const part of lit.replace(/\n/g, ' ').split(',')) {
    const p = part.trim()
    if (/^[A-Za-z_$][\w$]*$/.test(p)) provided.add(p)
  }

  for (const file of files) {
    const src = readFileSync(file, 'utf8')
    const used = new Set([
      ...[...templateOf(src).matchAll(/store\.([A-Za-z_$][\w$]*)/g)].map(m => m[1]),
      ...[...src.matchAll(/\bset(?:Ref)?\(\s*'([A-Za-z_$][\w$]*)'/g)].map(m => m[1]),
    ])
    for (const k of used) {
      if (!keys.has(k)) problems.push(`${file}: store.${k} 不在 CHAT_CTX_KEYS`)
      else if (!provided.has(k)) problems.push(`${file}: store.${k} 未在父页 pickChatCtx 里传入`)
    }
  }
  assert.deepEqual(problems, [], '子组件与上下文字典不同步：')
})