import test from 'node:test'
import assert from 'node:assert/strict'
import { failTurn } from '../src/chat/turnFail.js'

const setup = ({ kind = 'business', viewing = false, planStatus = null, revising = false } = {}) => {
  const calls = { flush: 0, settle: [], drop: 0, toasts: [], scroll: 0 }
  const msg = {
    loading: true, retrying: true, approvals: [{ id: 1 }], thinkLoading: true,
    planCard: planStatus ? { status: planStatus, busy: true, revising, revisingStarted: false, pendingFeedback: '意见' } : null
  }
  const ctx = {
    error: new Error('boom'), kind,
    flushNow: () => { calls.flush++ },
    settlePreparingCards: (m, why) => { calls.settle.push(why) },
    settleAbortedPlan: m => {
      if (!m.planCard) return
      if (m.planCard.status === 'drafting' || m.planCard.status === 'pending') m.planCard.status = 'stopped'
    },
    dropStream: () => { calls.drop++ },
    notify: m => { calls.toasts.push(String(m)) },
    viewing: () => viewing,
    scroll: () => { calls.scroll++ }
  }
  return { msg, ctx, calls }
}

test('失败收尾：保留内容、挂错误卡、收掉审批/计划/思考态', () => {
  const { msg, ctx, calls } = setup({ planStatus: 'drafting' })
  failTurn(msg, ctx)
  assert.equal(calls.flush, 1)
  assert.deepEqual(calls.settle, ['本轮中断，这一步没有执行'])
  assert.equal(msg.loading, false)
  assert.equal(msg.retrying, false)
  assert.deepEqual(msg.approvals, [])
  assert.equal(msg.planCard.status, 'stopped')
  assert.equal(msg.planCard.busy, false)
  assert.equal(msg.thinkLoading, false)
  assert.equal(msg.failed, true)
  assert.equal(msg.errorCard.message, 'Error: boom')
  assert.equal(msg.errorCard.kind, 'business')
  assert.equal(calls.drop, 1)
  assert.equal(calls.scroll, 1)
})

test('业务错误且本轮不在眼前：弹一句；在眼前时不弹', () => {
  const away = setup({ kind: 'business', viewing: false })
  failTurn(away.msg, away.ctx)
  assert.equal(away.calls.toasts.length, 1)

  const here = setup({ kind: 'business', viewing: true })
  failTurn(here.msg, here.ctx)
  assert.equal(here.calls.toasts.length, 0)
})

test('中断类错误：无论是否在眼前都不弹 toast', () => {
  for (const viewing of [true, false]) {
    const s = setup({ kind: 'interrupted', viewing })
    failTurn(s.msg, s.ctx)
    assert.equal(s.calls.toasts.length, 0)
  }
})

test('退回重出未开始：失败路径仍按 pending 收口并清掉意见', () => {
  // onDone 的保留分支不调本函数；失败路径统一收口（与 planAbort 语义一致）
  const s = setup({ planStatus: 'pending', revising: true })
  failTurn(s.msg, s.ctx)
  assert.equal(s.msg.planCard.status, 'stopped')
  assert.equal(s.msg.planCard.revising, false)
  assert.equal(s.msg.planCard.pendingFeedback, '')
  assert.equal(s.msg.planCard.busy, false)
})

test('无回应错误对象：错误卡正文仍是可读字符串', () => {
  const s = setup()
  failTurn(s.msg, { ...s.ctx, error: undefined })
  assert.equal(s.msg.errorCard.message, 'undefined')
  assert.equal(s.msg.errorCard.kind, 'business')
})
