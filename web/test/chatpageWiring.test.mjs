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
test('addTab 分支比较只使用真实页签名（root/ref/hist/cmd）', () => {
  // 模板按 addTab 分支渲染：写错页签名不会报错，只会静默落进最后的 v-else（快捷命令）。
  // 2026-11 曾把 'ref' 误写成 'store.ref'，表现为「引用资料」标题下却是快捷命令列表。
  const allowed = new Set(['root', 'ref', 'hist', 'cmd'])
  const problems = []
  let refBranch = false
  for (const file of files) {
    const src = readFileSync(file, 'utf8')
    for (const m of src.matchAll(/store\.addTab\s*[!=]==\s*'([^']*)'/g)) {
      if (!allowed.has(m[1])) problems.push(`${file}: addTab 比较用了不存在的页签 '${m[1]}'`)
      if (m[1] === 'ref') refBranch = true
    }
  }
  assert.deepEqual(problems, [], 'addTab 页签名写错会让模板静默走错分支：')
  assert.ok(refBranch, '「引用资料」的 ref 分支不见了')
})

test('技能正文弹窗的关闭回调必须清 store.skillDoc（ChatHeaderBar 没有裸 skillDoc 变量）', () => {
  // 回归：写成裸 `skillDoc = ''` 时，赋值落不到 chatCtx 上，弹窗点 X / 遮罩都关不掉。
  const src = readFileSync(`${DIR}/ChatHeaderBar.vue`, 'utf8')
  const m = /<SkillContentModal[\s\S]{0,500}?@update:open="([^"]*)"/.exec(src)
  assert.ok(m, 'ChatHeaderBar 里找不到 SkillContentModal 的 update:open 回调')
  assert.ok(/store\.skillDoc/.test(m[1]), `关闭回调没清 store.skillDoc：${m[1]}`)
})

test('执行过程弹窗必须能关闭：受控 a-modal 要绑定 @cancel 写回 store.eventsOpen', () => {
  // 回归：`:open` 是受控属性。只读 store.eventsOpen 却不接 @cancel 时，点 X / 遮罩 / Esc
  // 只发出 cancel 事件，没人把 eventsOpen 置 false，弹窗永远关不掉（用户只能刷新页面）。
  const src = readFileSync(`${DIR}/ChatHeaderBar.vue`, 'utf8')
  const blocks = [...src.matchAll(/<a-modal\b[\s\S]{0,800}?<\/a-modal>/g)].map(m => m[0])
  const modal = blocks.find(b => /:open="store\.eventsOpen"/.test(b))
  assert.ok(modal, 'ChatHeaderBar 里找不到执行过程 a-modal')
  assert.ok(
    /@cancel="[^"]*set\(\s*'eventsOpen'\s*,\s*false\s*\)[^"]*"/.test(modal) || /@update:open=/.test(modal),
    '执行过程弹窗是受控 :open，缺 @cancel/update:open 时点 X / 遮罩 / Esc 都关不掉'
  )
})

// 从 `<a-modal` / `<a-drawer` 起始处跳到开标签结尾（跳过引号里的 '>'，别被 `=>` 骗断）
function openingTagEnd (src, from) {
  let quote = null
  for (let i = from; i < src.length; i++) {
    const c = src[i]
    if (quote) { if (c === quote) quote = null }
    else if (c === '"' || c === "'") quote = c
    else if (c === '>') return i
  }
  return src.length - 1
}

/** 找出「单向 :open 且没有关闭回调」的 a-modal / a-drawer（v-model:open 双向绑定不算） */
function missingCloseModals (src, kind) {
  const out = []
  const re = new RegExp(`<a-${kind}\\b`, 'g')
  let m
  while ((m = re.exec(src))) {
    const tag = src.slice(m.index, openingTagEnd(src, m.index + m[0].length) + 1)
    const oneWay = /(^|\s):open\s*=\s*"/.test(tag)
    const hasClose = /@(cancel|update:open|close)\s*=\s*"/.test(tag)
    if (oneWay && !hasClose) out.push({ line: src.slice(0, m.index).split('\n').length, tag })
  }
  return out
}

test('chatpage 里所有单向 :open 的 a-modal / a-drawer 必须带关闭回调', () => {
  // 回归：`:open` 是单向受控。只读 store.xxx 却不接 @cancel / @close / @update:open 时，
  // 点 X / 遮罩 / Esc 只发事件、没人把开关置 false，弹窗永远关不掉。
  // （v-model:open 是双向绑定，组件自己会写回，不在此列。）
  const problems = files.flatMap(file => {
    const src = readFileSync(file, 'utf8')
    return ['modal', 'drawer'].flatMap(kind =>
      missingCloseModals(src, kind).map(x => `${file}:${x.line} ${x.tag.slice(0, 80)}`)
    )
  })
  assert.deepEqual(problems, [], '以下单向 :open 弹窗缺 @cancel/@close/@update:open，点 X 关不掉：\n' + problems.join('\n'))
})

test('setXxxRef 是 setter 函数，必须调用（store.setXxxRef(el)），不能用 setRef 赋值覆盖', () => {
  // 回归：useEditResend 的 setEditingRef / setEditAttachInputRef 是「把元素写进内部 ref」的 setter，
  // 不是 ref 本身。模板写成 `:ref="el => setRef('setEditAttachInputRef', el)"` 时，
  // setRef 会把 chatCtx 上的这个函数直接替换成 DOM 元素，内部 editAttachInput 永远为 null，
  // 表现为编辑卡「+」面板点「上传文件」不弹文件选择器（点了没反应）。
  const problems = []
  const all = []
  for (const file of files) {
    const src = readFileSync(file, 'utf8')
    all.push(src)
    for (const m of src.matchAll(/setRef\(\s*'(set[A-Za-z0-9_$]*Ref)'/g)) {
      problems.push(`${file}: setRef('${m[1]}') 覆盖了 setter 函数，应改为 store.${m[1]}(el)`)
    }
  }
  const joined = all.join('\n')
  for (const k of ['setEditingRef', 'setEditAttachInputRef']) {
    if (!new RegExp(`store\\.${k}\\(`).test(joined)) problems.push(`没有任何子组件调用 store.${k}(el)`)
  }
  assert.deepEqual(problems, [], '编辑卡 ref setter 接线错误：')
})
