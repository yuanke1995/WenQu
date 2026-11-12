import test from 'node:test'
import assert from 'node:assert/strict'
import { turnMarker, TURN_STOP_SUFFIX, TURN_BUDGET_SUFFIX, TURN_TIMEOUT_SUFFIX, TURN_TRUNC_SUFFIX } from '../src/chat/turnStatus.js'
import { degradationItems, degradationWarn } from '../src/chat/degradation.js'

test('终态标注：按整串字面量识别，优先级互斥', () => {
  assert.equal(turnMarker({ content: '答案' + TURN_STOP_SUFFIX }), 'stopped')
  assert.equal(turnMarker({ content: '答案' + TURN_BUDGET_SUFFIX }), 'budget')
  assert.equal(turnMarker({ content: '答案' + TURN_TIMEOUT_SUFFIX }), 'timeout')
  assert.equal(turnMarker({ content: '答案' + TURN_TRUNC_SUFFIX }), 'truncated')
  assert.equal(turnMarker({ content: '普通答案' }), '')
  assert.equal(turnMarker(null), '')
})

test('降级条：有错误卡时去掉同一失败的口径，其余保留', () => {
  const list = [{ code: 'streamError' }, { code: 'other' }]
  assert.equal(degradationItems({ degradations: list }), list)
  assert.deepEqual(degradationItems({ degradations: list, errorCard: {} }), [{ code: 'other' }])
  assert.deepEqual(degradationItems(null), [])
})

test('警示条：正文已写同一句则不重复，超时终态与尾注去重', () => {
  assert.equal(degradationWarn({ warnMsg: '网络抖动' }), '网络抖动')
  assert.equal(degradationWarn({ warnMsg: '网络抖动', content: '...网络抖动...' }), '')
  assert.equal(degradationWarn({ warnMsg: '本轮已超时结束，等你确认的那一步已作废', content: 'x' + TURN_TIMEOUT_SUFFIX }), '')
  assert.equal(degradationWarn({ warnMsg: '' }), '')
})
