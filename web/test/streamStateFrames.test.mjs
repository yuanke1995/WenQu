import test from 'node:test'
import assert from 'node:assert/strict'
import { ref } from 'vue'
import { applyCompactState, applyPlanFrame, applyGoalState, applyGoalTurn } from '../src/chat/streamStateFrames.js'

test('压缩状态：start 标会话并置 compacting；结束清标记，completed 补留痕', () => {
  const msg = {}
  const auto = ref(null)
  const live = ref(null)
  let scroll = 0
  applyCompactState(msg, { phase: 'start' }, { sid: 's1', autoCompactSid: auto, liveCompactedSid: live, onScroll: () => { scroll++ } })
  assert.equal(auto.value, 's1')
  assert.equal(msg.compacting, true)

  applyCompactState(msg, { phase: 'end', status: 'completed' }, { sid: 's1', autoCompactSid: auto, liveCompactedSid: live, onScroll: () => { scroll++ } })
  assert.equal(auto.value, null)
  assert.equal(msg.compacting, false)
  assert.equal(live.value, 's1')
  assert.equal(scroll, 2)
})

test('压缩状态：非 completed 的结束不补留痕；坏帧不动', () => {
  const msg = {}
  const auto = ref('x')
  const live = ref(null)
  applyCompactState(msg, { phase: 'end', status: 'failed' }, { sid: 's1', autoCompactSid: auto, liveCompactedSid: live })
  assert.equal(live.value, null)
  applyCompactState(msg, null, { sid: 's1', autoCompactSid: auto, liveCompactedSid: live })
  assert.equal(msg.compacting, false)
})

test('执行计划：数组覆盖写；空/坏载荷保留原值', () => {
  const msg = { plan: ['old'] }
  applyPlanFrame(msg, [{ step: 1 }])
  assert.deepEqual(msg.plan, [{ step: 1 }])
  applyPlanFrame(msg, [])
  assert.deepEqual(msg.plan, [{ step: 1 }])
  applyPlanFrame(msg, '{bad')
  assert.deepEqual(msg.plan, [{ step: 1 }])
})

test('目标态：整份覆盖写，按会话落库', () => {
  const calls = []
  applyGoalState('s1', { goalId: 'g1', status: 'RUNNING' }, (sid, doc) => calls.push([sid, doc]))
  assert.deepEqual(calls[0], ['s1', { goalId: 'g1', status: 'RUNNING' }])
  applyGoalState('s1', { status: 'RUNNING' }, (sid, doc) => calls.push([sid, doc]))
  assert.equal(calls.length, 1)
})

test('跨轮续跑：同会话才触发新一轮探测；异会话/坏载荷不触发', () => {
  let n = 0
  const opts = sid => ({ currentSid: () => 's1', sid, onNewTurn: () => { n++ } })
  applyGoalTurn({ goalId: 'g' }, opts('s1'))
  assert.equal(n, 1)
  applyGoalTurn({ goalId: 'g' }, opts('s2'))
  assert.equal(n, 1)
  applyGoalTurn('{bad', opts('s1'))
  assert.equal(n, 1)
})
