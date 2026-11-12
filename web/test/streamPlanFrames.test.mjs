import test from 'node:test'
import assert from 'node:assert/strict'
import { planText } from '../src/chat/planText.js'
import { applyPlanDelta, applyPlanApproval, applyPlanCancelled, applyPlanSuperseded } from '../src/chat/streamPlanFrames.js'

test('planText：字符串/数组/对象字段优先/兜底 JSON/空值', () => {
  assert.equal(planText('a'), 'a')
  assert.equal(planText(['a', '', 'b']), 'a\nb')
  assert.equal(planText({ summary: 's', plan: 'p' }), 'p')
  assert.equal(planText({ content: { text: 'nested' } }), 'nested')
  assert.equal(planText({ foo: 1 }), JSON.stringify({ foo: 1 }, null, 2))
  assert.equal(planText(null), '')
  assert.equal(planText(5), '5')
})

test('草稿增量：新卡 drafting、带上血缘、逐字追加', () => {
  const msg = {}
  let scroll = 0
  applyPlanDelta(msg, '第一段', { userMsg: { planAdjustFrom: { cardId: 'c1' } }, onScroll: () => { scroll++ } })
  assert.equal(msg.planCard.status, 'drafting')
  assert.deepEqual(msg.planCard.adjustFrom, { cardId: 'c1' })
  applyPlanDelta(msg, '第二段', { onScroll: () => { scroll++ } })
  assert.equal(msg.planCard.plan, '第一段第二段')
  assert.equal(scroll, 2)
})

test('草稿增量：已批准卡不再追加', () => {
  const msg = { planCard: { status: 'approved', plan: '定稿' } }
  applyPlanDelta(msg, '迟到', {})
  assert.equal(msg.planCard.plan, '定稿')
})

test('退回重出：第一份新稿到达时归档旧版，feedback 归属各自版本', () => {
  const msg = { planCard: { status: 'pending', plan: '旧稿', feedback: '旧意见',
                            versions: [], revising: true, revisingStarted: false, pendingFeedback: '新意见' } }
  applyPlanDelta(msg, '新', {})
  const c = msg.planCard
  assert.equal(c.revisingStarted, true)
  assert.equal(c.status, 'drafting')
  assert.equal(c.plan, '新')
  assert.equal(c.feedback, '新意见')
  assert.deepEqual(c.versions, [{ plan: '旧稿', feedback: '旧意见' }])
})

test('批准终稿：id/倒计时/待确认；帧带血缘故能回链旧卡', () => {
  const old = { planCard: { id: 'c1' } }
  const msg = {}
  let scroll = 0
  applyPlanApproval(msg, JSON.stringify({
    planApprovalId: 'c2', plan: { summary: '终稿' }, timeoutMs: 5000,
    planBaseId: 'c1', planBaseVersion: 2, planBaseDone: 1, planBaseTotal: 3
  }), { messages: [old], onScroll: () => { scroll++ } })
  const c = msg.planCard
  assert.equal(c.id, 'c2')
  assert.equal(c.status, 'pending')
  assert.equal(c.plan, '终稿')
  assert.equal(c.timeoutMs, 5000)
  assert.ok(c.deadline >= Date.now() + 5000)
  assert.deepEqual(c.adjustFrom, { cardId: 'c1', version: 2, done: 1, total: 3, failed: 0 })
  assert.equal(old.planCard.replannedBy.cardId, 'c2')
  assert.equal(scroll, 1)
})

test('批准终稿：缺 id 忽略；无血缘时不回链', () => {
  const msg = {}
  applyPlanApproval(msg, JSON.stringify({ plan: 'x' }), {})
  assert.equal(msg.planCard, undefined)

  applyPlanApproval(msg, JSON.stringify({ planApprovalId: 'c9', plan: 'y' }), {})
  assert.equal(msg.planCard.adjustFrom, undefined)
  assert.equal(msg.planCard.status, 'pending')
})

test('被拒：恢复半截稿并定格未批准；被取代同理', () => {
  const rejected = { planCard: { status: 'drafting', revising: true, revisingStarted: true, busy: true,
                                 plan: '半截', pendingFeedback: 'x', versions: [{ plan: '完整', feedback: '' }] } }
  applyPlanCancelled(rejected, {})
  assert.equal(rejected.planCard.status, 'rejected')
  assert.equal(rejected.planCard.plan, '完整')
  assert.equal(rejected.planCard.busy, false)
  assert.equal(rejected.planStopped, true)

  const superseded = { planCard: { status: 'drafting', revising: false, busy: true, plan: '稿' } }
  applyPlanSuperseded(superseded, {})
  assert.equal(superseded.planCard.status, 'superseded')
  assert.equal(superseded.planSuperseded, true)
})
