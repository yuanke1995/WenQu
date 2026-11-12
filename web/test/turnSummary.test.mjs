import test from 'node:test'
import assert from 'node:assert/strict'
import { turnHead, turnTop } from '../src/chat/turnSummary.js'
import { TURN_STOP_SUFFIX, TURN_TIMEOUT_SUFFIX, TURN_TRUNC_SUFFIX } from '../src/chat/turnStatus.js'

test('状态行：跑动中按重试/压缩/溯源三档，并带用时', () => {
  assert.equal(turnHead({ loading: true }, 12000), '正在溯源，用时 12 秒 …')
  assert.equal(turnHead({ loading: true, retrying: true }, 0), '连接中断，正在自动重试 …')
  assert.equal(turnHead({ loading: true, compacting: true }, 0), '正在压缩上下文 …')
})

test('状态行：终态标注按类型说话，超时/中断不报「已完成」', () => {
  assert.equal(turnHead({ content: 'x' + TURN_STOP_SUFFIX }, 3000), '这一轮已停止，用时 3 秒')
  assert.equal(turnHead({ content: 'x' + TURN_TIMEOUT_SUFFIX }, 0), '这一轮等待确认超时')
  assert.equal(turnHead({ content: 'x' + TURN_TRUNC_SUFFIX }, 0), '这一轮中断')
})

test('状态行：错误卡/失败时兜底「这一轮中断」，无尾注时认引擎旗标', () => {
  assert.equal(turnHead({ failed: true }, 0), '这一轮中断')
  assert.equal(turnHead({ stoppedTurn: true }, 2000), '这一轮已停止，用时 2 秒')
  assert.equal(turnHead({ planStopped: true }, 0), '这一轮已停止')
  assert.equal(turnHead({}, 5000), '已完成，用时 5 秒')
})

test('turnTop：答完且没有过程可展开时用纯文字行', () => {
  assert.equal(turnTop({ loading: false }, 1000), true)
  assert.equal(turnTop({ loading: true }, 1000), false)
  assert.equal(turnTop({ toolCalls: [{ name: 'a' }] }, 1000), true)
})
