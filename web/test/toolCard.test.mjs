import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { toolCardOpen, toggleToolCard, openDispatchCard } from '../src/chat/toolCard.js'

test('工具卡开合：派遣卡默认展开，普通卡默认收起', () => {
  assert.equal(toolCardOpen({ subChat: {} }), true)
  assert.equal(toolCardOpen({ subChat: {}, _open: false }), false)
  assert.equal(toolCardOpen({}), false)
  assert.equal(toolCardOpen({ _open: true }), true)
  const sub = { subChat: {} }
  toggleToolCard(sub)
  assert.equal(sub._open, false)
  const plain = {}
  toggleToolCard(plain)
  assert.equal(plain._open, true)
})

test('定位展开：本轮/簇/组条/卡片四层一起打开', () => {
  const t = { name: 'agent_spawn', status: 'done', subChat: {} }
  t._open = false
  const m = { content: '正文', timeline: [{ kind: 'tool', tool: t }, { kind: 'tool', tool: { name: 'b' } }], toolCalls: [t, { name: 'b' }] }
  // 用工具清单兜底成组（无 timeline 分组时）
  openDispatchCard(m, t)
  assert.equal(m._turnOpen, true)
  assert.equal(t._open, true)
  assert.equal(t._groupOpen, true)
})

test('定位展开：派遣卡默认态（_open 未标）也要落到 _open=true，簇帽体行才展详情', () => {
  const t = { name: 'agent_spawn', status: 'done', subChat: {} }
  const m = { content: '正文', timeline: [{ kind: 'tool', tool: t }], toolCalls: [t] }
  // toolCardOpen 认为它已经开着，靠 toggle 是翻不开「认 _open 才算开」的帽体行
  openDispatchCard(m, t)
  assert.equal(t._open, true)
})

test('定位展开：不在时间线里时只打开卡片本身', () => {
  const t = {}
  const m = { content: 'x', toolCalls: [] }
  openDispatchCard(m, t)
  assert.equal(m._turnOpen, true)
  assert.equal(t._open, true)
})

test('定位锚：派遣卡的两种渲染形态（工具卡 / 过程簇帽体行）都带 data-spawn', () => {
  const files = {
    ChatPage: ['src/views/ChatPage.vue', ['tl-card', 'tl-live-row']],
    MobileMsgRow: ['src/h5/MobileMsgRow.vue', ['card tool-card', 'live-row']]
  }
  const missing = []
  for (const [name, [file, classes]] of Object.entries(files)) {
    const src = readFileSync(file, 'utf8')
    for (const cls of classes) {
      // 同一支派遣工具按簇/非簇两种形态渲染；哪条链路少锚点，点药丸就定位不到
      if (!new RegExp(`class="${cls}"[^>]*:data-spawn=`).test(src)) missing.push(`${name}: .${cls}`)
    }
  }
  assert.deepEqual(missing, [], '这些渲染形态缺定位锚，药丸点行会找不到目标')
})
