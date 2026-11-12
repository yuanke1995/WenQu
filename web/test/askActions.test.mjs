import test from 'node:test'
import assert from 'node:assert/strict'
import { submitAsk, ignoreAsk } from '../src/chat/askActions.js'

const make = (over = {}) => {
  const calls = { warn: [], err: [], notif: [], retire: 0, stop: 0, poll: [], switch: [], scroll: 0 }
  const a = { id: 'a1', questions: [{ options: ['A', 'B'] }], picks: [[0]], customs: [''], customOn: [false], busy: false, answered: false }
  const ctx = {
    answerApi: async () => ({ success: true }),
    ignoreApi: async () => ({ success: true }),
    notify: { warning: m => calls.warn.push(m), error: m => calls.err.push(m) },
    emitNotifChanged: what => calls.notif.push(what),
    retireAskCard: () => { calls.retire++ },
    stopRestoredPoll: () => { calls.stop++ },
    pollRestoredAnswer: sid => calls.poll.push(sid),
    switchSession: sid => calls.switch.push(sid),
    currentSid: () => 's1',
    scrollSoft: () => { calls.scroll++ },
    ...over
  }
  return { a, m: { ask: a }, calls, ctx }
}

test('提交：正常答完标记 answered、发通知并贴底', async () => {
  const { a, m, calls, ctx } = make()
  await submitAsk(m, ctx)
  assert.equal(a.answered, true)
  assert.deepEqual(calls.notif, ['chat'])
  assert.equal(calls.scroll, 1)
  assert.equal(a.busy, true)
})

test('提交：失败提示并解锁；恢复态失败撤卡重载', async () => {
  const a1 = make({ answerApi: async () => ({ success: false, msg: '提交失败' }) })
  await submitAsk(a1.m, a1.ctx)
  assert.deepEqual(a1.calls.warn, ['提交失败'])
  assert.equal(a1.a.busy, false)

  const a2 = make({ answerApi: async () => ({ success: false, msg: '失效' }) })
  a2.a.restored = true
  await submitAsk(a2.m, a2.ctx)
  assert.equal(a2.calls.retire, 1)
  assert.equal(a2.calls.stop, 1)
  assert.deepEqual(a2.calls.switch, ['s1'])
})

test('提交：恢复态成功 → 撤卡并轮询取回后台答案', async () => {
  const { a, m, calls, ctx } = make()
  a.restored = true
  await submitAsk(m, ctx)
  assert.equal(calls.retire, 1)
  assert.deepEqual(calls.poll, ['s1'])
})

test('忽略：正常标记已忽略；异常提示并解锁', async () => {
  const { a, m, calls, ctx } = make()
  await ignoreAsk(m, ctx)
  assert.equal(a.answered, '（已忽略）')
  assert.equal(calls.scroll, 1)

  const bad = make({ ignoreApi: async () => { throw new Error('网络断了') } })
  await ignoreAsk(bad.m, bad.ctx)
  assert.deepEqual(bad.calls.err, ['网络断了'])
  assert.equal(bad.a.busy, false)
})

test('忙碌/已答的卡不重复提交', async () => {
  const { a, m, ctx } = make()
  a.busy = true
  await submitAsk(m, ctx)
  assert.equal(a.answered, false)
  a.busy = false
  a.answered = true
  await submitAsk(m, ctx)
  assert.equal(a.answered, true)
})
