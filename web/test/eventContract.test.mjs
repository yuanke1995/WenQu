import test from 'node:test'
import assert from 'node:assert/strict'
import { CHAT_EVENT_CB, SSE_LABELS, RUN_EVENT_TYPES } from '../src/chat/eventContract.generated.js'

test('事件契约：深度执行词汇只发 deep_*，旧 plan_*/goal_* 不再出现', () => {
  for (const name of ['deep_plan_delta', 'deep_plan_approval', 'deep_plan_cancelled', 'deep_plan_superseded', 'deep_state', 'deep_turn']) {
    assert.ok(RUN_EVENT_TYPES.includes(name), name)
  }
  for (const old of ['plan_delta', 'plan_approval', 'plan_cancelled', 'plan_superseded', 'goal_state', 'goal_turn']) {
    assert.ok(!RUN_EVENT_TYPES.includes(old), old)
    assert.ok(!(old in CHAT_EVENT_CB), old)
    assert.ok(!(old in SSE_LABELS), old)
  }
})

test('事件契约：深度执行回调名与账本文案', () => {
  assert.equal(CHAT_EVENT_CB.deep_plan_approval, 'onDeepPlanApproval')
  assert.equal(CHAT_EVENT_CB.deep_state, 'onDeepState')
  assert.equal(CHAT_EVENT_CB.deep_turn, 'onDeepTurn')
  assert.equal(SSE_LABELS.deep_plan_approval, '深度执行计划待批准')
  assert.equal(SSE_LABELS.deep_state, '深度执行态')
})
