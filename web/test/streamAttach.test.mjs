import test from 'node:test'
import assert from 'node:assert/strict'
import { attachRunningTurn } from '../src/chat/streamAttach.js'
import { createStreamLifecycle } from '../src/chat/streamLifecycle.js'

const makeCtx = (over = {}) => {
  const calls = { stream: [], placeholder: 0, taken: [] }
  return {
    calls,
    chatStreams: new Map(),
    lifecycle: createStreamLifecycle(),
    getChatRun: async () => ({ data: { running: true, turnId: 't1' } }),
    ensureGoalPlaceholder: () => { calls.placeholder++ },
    takeGoalPlaceholder: sid => { calls.taken.push(sid); return { placeholderOf: sid } },
    currentSid: () => 's1',
    streamAnswer: (...args) => { calls.stream.push(args) },
    ...over
  }
}

test('接流：无会话/已有流/正在探测都不重复开', async () => {
  assert.deepEqual(await attachRunningTurn('', null, makeCtx()), { status: 'idle' })
  const c1 = makeCtx(); c1.chatStreams.set('s1', {})
  assert.deepEqual(await attachRunningTurn('s1', null, c1), { status: 'busy' })
  const c2 = makeCtx(); c2.lifecycle.beginProbe('s1')
  assert.deepEqual(await attachRunningTurn('s1', null, c2), { status: 'busy' })
})

test('接流：探到在跑 → 开 resume 流并沿用占位/残影气泡', async () => {
  const c = makeCtx()
  const r = await attachRunningTurn('s1', null, c)
  assert.deepEqual(r, { status: 'attached' })
  assert.equal(c.calls.stream.length, 1)
  const args = c.calls.stream[0]
  assert.equal(args[0], '')
  assert.equal(args[14], true) // resume=true
  assert.deepEqual(args[2], { placeholderOf: 's1' })
  assert.equal(c.calls.placeholder, 0)
})

test('接流：没在跑 → 用占位气泡顶上，不开流', async () => {
  const c = makeCtx({ getChatRun: async () => ({ data: { running: false } }) })
  assert.deepEqual(await attachRunningTurn('s1', null, c), { status: 'idle' })
  assert.equal(c.calls.placeholder, 1)
  assert.equal(c.calls.stream.length, 0)
})

test('接流：已收场的轮不接（防旧答案重播）；非当前会话不接', async () => {
  const c = makeCtx()
  c.lifecycle.rememberSettled('s1', 't1')
  assert.deepEqual(await attachRunningTurn('s1', null, c), { status: 'idle' })
  assert.equal(c.calls.placeholder, 1)
  assert.equal(c.calls.stream.length, 0)

  const c2 = makeCtx({ currentSid: () => 'other' })
  assert.deepEqual(await attachRunningTurn('s1', null, c2), { status: 'running' })
  assert.equal(c2.calls.stream.length, 0)
})

test('接流：探测失败与「没在跑」必须分开（前者网还没回来，不能当收场）', async () => {
  const c = makeCtx({ getChatRun: async () => { throw new Error('net') } })
  assert.deepEqual(await attachRunningTurn('s1', null, c), { status: 'unknown' })
  assert.equal(c.calls.stream.length, 0)
  assert.equal(c.calls.placeholder, 0)
})
