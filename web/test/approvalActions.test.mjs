import test from 'node:test'
import assert from 'node:assert/strict'
import { resolveApproval } from '../src/chat/approvalActions.js'

const make = (over = {}) => {
  const calls = { warn: [], err: [], notif: [], poll: [], scroll: 0 }
  const card = { id: 'ap1', busy: false }
  const m = { approvals: [card] }
  const ctx = {
    chatStreams: new Map(),
    currentSid: () => 's1',
    notify: { warning: x => calls.warn.push(x), error: x => calls.err.push(x) },
    emitNotifChanged: w => calls.notif.push(w),
    pollRestoredAnswer: sid => calls.poll.push(sid),
    scrollSoft: () => { calls.scroll++ },
    approveApi: async () => ({ success: true }),
    approvalLeftSec: () => 30,
    ...over
  }
  return { card, m, calls, ctx }
}

test('审批：无卡/忙时不动', async () => {
  const { ctx } = make()
  await resolveApproval({ approvals: [] }, null, true, ctx)
  const b = make(); b.card.busy = true
  await resolveApproval(b.m, b.card, true, b.ctx)
})

test('审批：超时不再发请求', async () => {
  const { card, m, calls, ctx } = make({ approvalLeftSec: () => 0 })
  card.deadline = 1
  await resolveApproval(m, card, true, ctx)
  assert.deepEqual(calls.warn, ['这个审批已超时，本轮将按拒绝继续'])
  assert.equal(card.busy, false)
})

test('审批：成功撤卡、发通知并贴底', async () => {
  const { card, m, calls, ctx } = make()
  await resolveApproval(m, card, true, ctx)
  assert.equal(m.approvals.length, 0)
  assert.deepEqual(calls.notif, ['chat'])
  assert.equal(calls.scroll, 1)
})

test('审批：恢复态且本机无流 → 轮询取回后台答案', async () => {
  const { card, m, calls, ctx } = make()
  card.restored = true
  await resolveApproval(m, card, true, ctx)
  assert.deepEqual(calls.poll, ['s1'])
  assert.equal(calls.scroll, 0)
})

test('审批：失败提示并解锁；异常同理', async () => {
  const a = make({ approveApi: async () => ({ success: false, msg: '已失效' }) })
  await resolveApproval(a.m, a.card, true, a.ctx)
  assert.deepEqual(a.calls.warn, ['已失效'])
  assert.equal(a.card.busy, false)

  const b = make({ approveApi: async () => { throw new Error('网络断了') } })
  await resolveApproval(b.m, b.card, true, b.ctx)
  assert.deepEqual(b.calls.err, ['网络断了'])
  assert.equal(b.card.busy, false)
})
