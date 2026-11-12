import test from 'node:test'
import assert from 'node:assert/strict'
import { restoreAbortedDraft, settleAbortedPlan } from '../src/chat/planAbort.js'

const card = over => ({ status: 'pending', busy: true, revising: false, revisingStarted: false,
                        pendingFeedback: '', plan: '旧稿', feedback: '', versions: [], ...over })

test('退回重出中途被打断：恢复上一份完整稿并落已停止', () => {
  const c = card({
    status: 'drafting', revising: true, revisingStarted: true, pendingFeedback: '再细一点',
    plan: '半截新稿', feedback: '半截意见',
    versions: [{ plan: '完整稿', feedback: '完整意见' }]
  })
  const msg = { planCard: c }
  assert.equal(settleAbortedPlan(msg), true)
  assert.equal(c.status, 'stopped')
  assert.equal(c.plan, '完整稿')
  assert.equal(c.feedback, '完整意见')
  assert.equal(c.versions.length, 0)
  assert.equal(c.revising, false)
  assert.equal(c.revisingStarted, false)
  assert.equal(c.pendingFeedback, '')
  assert.equal(c.busy, false)
  assert.equal(msg.planStopped, true)
})

test('退回重出意见刚提交（新稿未开始）：不恢复草稿，但按 pending 落已停止', () => {
  // onDone 那条路对 revising&&!revisingStarted 另有保留分支（不调本函数）；
  // 失败/停止路径直接收口：卡落终态，原稿内容不动
  const c = card({ status: 'pending', revising: true, revisingStarted: false, versions: [{ plan: '旧稿' }] })
  assert.equal(settleAbortedPlan({ planCard: c }), true)
  assert.equal(c.status, 'stopped')
  assert.equal(c.plan, '旧稿')
  assert.equal(c.versions.length, 1)
})

test('首版计划未写完/待确认：直接落已停止', () => {
  for (const status of ['drafting', 'pending']) {
    const c = card({ status })
    const msg = { planCard: c }
    assert.equal(settleAbortedPlan(msg), true)
    assert.equal(c.status, 'stopped')
    assert.equal(c.busy, false)
    assert.equal(msg.planStopped, true)
  }
})

test('已终态/没有卡：不动并返回 false', () => {
  assert.equal(settleAbortedPlan(null), false)
  assert.equal(settleAbortedPlan({}), false)
  const c = card({ status: 'approved' })
  assert.equal(settleAbortedPlan({ planCard: c }), false)
  assert.equal(c.status, 'approved')
})

test('恢复草稿：版本链为空时保持原样', () => {
  const c = card({ plan: '当前', versions: [] })
  restoreAbortedDraft(c)
  assert.equal(c.plan, '当前')
  assert.deepEqual(c.versions, [])
})
