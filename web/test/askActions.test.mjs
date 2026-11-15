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
    // 收卡那一拍在测试里当场执行：真实定时器会把断言拖过 700ms
    setTimer: fn => fn(),
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

test('提交：答完留一拍即撤卡，不等后端 askUser 终态帧', async () => {
  const { m, calls, ctx } = make()
  await submitAsk(m, ctx)
  assert.equal(calls.retire, 1)
})

test('提交：终态帧先撤了卡，本地那一拍不重复撤、也不动顶上来的下一张', async () => {
  const timers = []
  const { a, m, calls, ctx } = make({ setTimer: fn => { timers.push(fn) } })
  await submitAsk(m, ctx)
  const next = { id: 'a2' }
  m.ask = next                 // 后端终态帧先到，撤卡并把排队的下一张顶上来
  timers.forEach(fn => fn())
  assert.equal(calls.retire, 0)
  assert.equal(m.ask, next)
  assert.equal(a.answered, true)
})

test('提交：还有排队的卡时不本地撤，仍按终态帧一张一张轮换', async () => {
  const { m, calls, ctx } = make()
  m.askQueue = [{ id: 'a2' }]
  await submitAsk(m, ctx)
  assert.equal(calls.retire, 0)
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

test('continued 卡提交：收尾那一行后把整批答案作为用户消息送出，不轮询后台回答', async () => {
  const sent = []
  const { a, m, calls, ctx } = make({ deliverContinuedAnswer: t => sent.push(t) })
  a.continued = true
  a.restored = true
  await submitAsk(m, ctx)
  assert.equal(calls.retire, 1)
  assert.equal(calls.poll.length, 0)
  assert.equal(sent.length, 1)
  const j = JSON.parse(sent[0])
  assert.equal(j.kind, 'answer_to_pending_question')
  assert.equal(j.callId, 'a1')
  assert.deepEqual(j.answers, [{ selected: 'A' }])
})
