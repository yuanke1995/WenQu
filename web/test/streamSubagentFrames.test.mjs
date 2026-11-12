import test from 'node:test'
import assert from 'node:assert/strict'
import { applySubagentStep } from '../src/chat/streamSubagentFrames.js'

const setup = (cards = []) => {
  const calls = { scroll: 0 }
  const msg = { toolCalls: cards }
  const ctx = {
    isDispatchTool: name => name === 'consult',
    consultNameOf: x => x.agent,
    stepBriefOf: c => '步骤:' + c.name,
    onScroll: () => { calls.scroll++ }
  }
  return { msg, ctx, calls }
}

test('完整过程：非 tool_status 帧进抽屉，按类型+内容前缀去重', () => {
  const { msg, ctx, calls } = setup([{ name: 'consult', status: 'start', agent: 'A' }])
  applySubagentStep(msg, { agent: 'A', type: 'retrieval', content: '找资料' }, ctx)
  applySubagentStep(msg, { agent: 'A', type: 'retrieval', content: '找资料' }, ctx)
  const card = msg.toolCalls[0]
  assert.equal(card._frames.length, 1)
  assert.equal(card._frames[0].t, 'retrieval')
  assert.equal(calls.scroll, 1)
})

test('完整过程：上限 240 帧，超出丢最早', () => {
  const { msg, ctx } = setup([{ name: 'consult', status: 'start', agent: 'A' }])
  for (let i = 0; i < 245; i++) applySubagentStep(msg, { agent: 'A', type: 't', content: 'c' + i }, ctx)
  const card = msg.toolCalls[0]
  assert.equal(card._frames.length, 240)
  assert.equal(card._frames.at(-1).c, 'c244')
  assert.equal(card._frames[0].c, 'c5')
})

test('早产进度帧：只更新卡头当前步骤，不进抽屉', () => {
  const { msg, ctx, calls } = setup([{ name: 'consult', status: 'start', agent: 'A' }])
  applySubagentStep(msg, { agent: 'A', type: 'tool_status', content: JSON.stringify({ name: 'read', preparing: true }) }, ctx)
  const card = msg.toolCalls[0]
  assert.equal(card._live, '步骤:read')
  assert.equal(card._frames, undefined)
  assert.equal(calls.scroll, 1)
})

test('tool_status start：写当前步骤；终态：追加迷你时间线并清当前步骤', () => {
  const { msg, ctx } = setup([{ name: 'consult', status: 'start', agent: 'A', _live: '旧' }])
  applySubagentStep(msg, { agent: 'A', type: 'tool_status', content: JSON.stringify({ name: 'read', status: 'start' }) }, ctx)
  assert.equal(msg.toolCalls[0]._live, '步骤:read')

  applySubagentStep(msg, { agent: 'A', type: 'tool_status',
    content: JSON.stringify({ name: 'read', label: '读文件', status: 'error', elapsedMs: 12 }) }, ctx)
  const card = msg.toolCalls[0]
  assert.equal(card._live, '')
  assert.deepEqual(card._steps, [{ name: 'read', label: '读文件', ms: 12, denied: false, st: 'error' }])
})

test('迷你时间线：只留最近 20 步', () => {
  const { msg, ctx } = setup([{ name: 'consult', status: 'start', agent: 'A' }])
  for (let i = 0; i < 22; i++) {
    applySubagentStep(msg, { agent: 'A', type: 'tool_status', content: JSON.stringify({ name: 'n' + i, status: 'done' }) }, ctx)
  }
  const steps = msg.toolCalls[0]._steps
  assert.equal(steps.length, 20)
  assert.equal(steps.at(-1).name, 'n21')
  assert.equal(steps[0].name, 'n2')
})

test('名字查不到：只跑一支派遣卡时才敢认领；两支并发不认', () => {
  const one = setup([{ name: 'consult', status: 'start', agent: 'B' }])
  applySubagentStep(one.msg, { agent: 'A', type: 'tool_status', content: JSON.stringify({ name: 'read', status: 'start' }) }, one.ctx)
  assert.equal(one.msg.toolCalls[0]._live, '步骤:read')

  const two = setup([{ name: 'consult', status: 'start', agent: 'B' }, { name: 'consult', status: 'start', agent: 'C' }])
  applySubagentStep(two.msg, { agent: 'A', type: 'tool_status', content: JSON.stringify({ name: 'read', status: 'start' }) }, two.ctx)
  assert.equal(two.msg.toolCalls[0]._live, undefined)
  assert.equal(two.msg.toolCalls[1]._live, undefined)
})

test('无 agent 忽略；非起止状态只进抽屉、不写当前步骤', () => {
  const { msg, ctx, calls } = setup([{ name: 'consult', status: 'start', agent: 'A' }])
  applySubagentStep(msg, { type: 'tool_status' }, ctx)
  assert.equal(calls.scroll, 0)
  applySubagentStep(msg, { agent: 'A', type: 'tool_status', content: JSON.stringify({ name: 'read', status: 'progress' }) }, ctx)
  assert.equal(msg.toolCalls[0]._live, undefined)
  assert.equal(msg.toolCalls[0]._frames.length, 1)  // progress 仍进完整过程
})
