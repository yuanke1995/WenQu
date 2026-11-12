import test from 'node:test'
import assert from 'node:assert/strict'
import { closeRunningTools, STOP_PLACEHOLDER, markStoppedTurn } from '../src/chat/turnCloseout.js'
import { TURN_STOP_SUFFIX } from '../src/chat/turnStatus.js'

test('工具卡定格：提问卡按未作答收，其它按结果没取到收', () => {
  const msg = { toolCalls: [
    { name: 'askUser', status: 'start' },
    { name: 'read_file', status: 'start' },
    { name: 'done_tool', status: 'done', result: 'ok' }
  ] }
  closeRunningTools(msg, '本轮已停止')
  assert.equal(msg.toolCalls[0].status, 'done')
  assert.match(msg.toolCalls[0].result, /用户未回答这一题（本轮已停止）/)
  assert.equal(msg.toolCalls[1].status, 'error')
  assert.match(msg.toolCalls[1].result, /本轮已停止，这一步的结果没有取到/)
  assert.equal(msg.toolCalls[2].status, 'done')
  assert.equal(msg.toolCalls[2].result, 'ok')
})

test('工具卡定格：无卡/结构异常不抛', () => {
  closeRunningTools(null, 'x')
  closeRunningTools({}, 'x')
})

test('停止标注：占位语被正式标注顶掉，不并列', () => {
  const msg = { content: STOP_PLACEHOLDER }
  markStoppedTurn(msg)
  assert.equal(msg.content, TURN_STOP_SUFFIX.trim())
  assert.ok(!msg.content.includes(STOP_PLACEHOLDER))
})

test('停止标注：空正文/已标注/仍在流式中都不补', () => {
  assert.equal(markStoppedTurn({ content: '', loading: false }), undefined)
  assert.equal((() => { const m = { content: '' }; markStoppedTurn(m); return m.content })(), '')

  const marked = { content: '答案' + TURN_STOP_SUFFIX }
  markStoppedTurn(marked)
  assert.equal(marked.content, '答案' + TURN_STOP_SUFFIX)

  const streaming = { content: '半句', loading: true }
  markStoppedTurn(streaming)
  assert.equal(streaming.content, '半句')
})

test('停止标注：收尾后给正文尾部补正式标注', () => {
  const msg = { content: '答案', loading: false }
  markStoppedTurn(msg)
  assert.ok(msg.content.endsWith(TURN_STOP_SUFFIX))
})
