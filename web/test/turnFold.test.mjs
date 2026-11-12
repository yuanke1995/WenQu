import test from 'node:test'
import assert from 'node:assert/strict'
import { turnOpen, toggleTurn, turnExpandable, turnShow, turnToggleable, turnWarn } from '../src/chat/turnFold.js'

const tool = { name: 'a' }
const withProcess = { toolCalls: [tool] }

test('展开态：跑着恒展开；用户点过优先；无正文有过程默认展开', () => {
  assert.equal(turnOpen({ loading: true, _turnOpen: false }), true)
  assert.equal(turnOpen({ content: '正文', _turnOpen: true }), true)
  assert.equal(turnOpen({ content: '正文', _turnOpen: false }), false)
  assert.equal(turnOpen({ content: '', toolCalls: [tool] }), true)
  assert.equal(turnOpen({ content: '正文', toolCalls: [tool] }), false)
})

test('切换与可展开：有过程才挂折叠三角', () => {
  const m = { content: '正文', toolCalls: [tool] }
  toggleTurn(m)
  assert.equal(m._turnOpen, true)
  assert.equal(turnExpandable(withProcess), true)
  assert.equal(turnExpandable({}), false)
  assert.equal(turnExpandable(null), false)
})

test('状态行：跑着/有耗时/有过程才占行，只有答完才给开关', () => {
  assert.equal(turnShow({ loading: true }, 0), true)
  assert.equal(turnShow({}, 1200), true)
  assert.equal(turnShow(withProcess, 0), true)
  assert.equal(turnShow({}, 0), false)
  assert.equal(turnToggleable(withProcess, 1200), true)
  assert.equal(turnToggleable({ loading: true, toolCalls: [tool] }, 1200), false)
})

test('重试警示只在跑着时成立', () => {
  assert.equal(turnWarn({ loading: true, retrying: true }), true)
  assert.equal(turnWarn({ loading: false, retrying: true }), false)
})
