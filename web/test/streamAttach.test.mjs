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
  assert.equal(await attachRunningTurn('', null, makeCtx()), false)
  const c1 = makeCtx(); c1.chatStreams.set('s1', {})
  assert.equal(await attachRunningTurn('s1', null, c1), false)
  const c2 = makeCtx(); c2.lifecycle.beginProbe('s1')
  assert.equal(await attachRunningTurn('s1', null, c2), false)
})

test('接流：探到在跑 → 开 resume 流并沿用占位/残影气泡', async () => {
  const c = makeCtx()
  const ok = await attachRunningTurn('s1', null, c)
  assert.equal(ok, true)
  assert.equal(c.calls.stream.length, 1)
  const args = c.calls.stream[0]
  assert.equal(args[0], '')
  assert.equal(args[14], true) // resume=true
  assert.deepEqual(args[2], { placeholderOf: 's1' })
  assert.equal(c.calls.placeholder, 0)
})

test('接流：没在跑 → 用占位气泡顶上，不开流', async () => {
  const c = makeCtx({ getChatRun: async () => ({ data: { running: false } }) })
  assert.equal(await attachRunningTurn('s1', null, c), false)
  assert.equal(c.calls.placeholder, 1)
  assert.equal(c.calls.stream.length, 0)
})

test('接流：已收场的轮不接（防旧答案重播）；非当前会话不接', async () => {
  const c = makeCtx()
  c.lifecycle.rememberSettled('s1', 't1')
  assert.equal(await attachRunningTurn('s1', null, c), false)
  assert.equal(c.calls.placeholder, 1)
  assert.equal(c.calls.stream.length, 0)

  const c2 = makeCtx({ currentSid: () => 'other' })
  assert.equal(await attachRunningTurn('s1', null, c2), false)
  assert.equal(c2.calls.stream.length, 0)
})

test('接流：探测失败静默返回，不占位不发流', async () => {
  const c = makeCtx({ getChatRun: async () => { throw new Error('net') } })
  assert.equal(await attachRunningTurn('s1', null, c), false)
  assert.equal(c.calls.stream.length, 0)
})
