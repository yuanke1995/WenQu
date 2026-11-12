import test from 'node:test'
import assert from 'node:assert/strict'
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
  assert.notEqual(t._open, false)
  assert.equal(t._groupOpen, true)
})

test('定位展开：不在时间线里时只打开卡片本身', () => {
  const t = {}
  const m = { content: 'x', toolCalls: [] }
  openDispatchCard(m, t)
  assert.equal(m._turnOpen, true)
  assert.equal(t._open, true)
})
