import test from 'node:test'
import assert from 'node:assert/strict'
import { buildTurnRequest } from '../src/chat/turnRequest.js'

const base = over => buildTurnRequest({ deepThink: false, planMode: false, planBase: null, reasoningLevel: '',
  attachments: [], skills: [], mentions: [], historyRefs: [], replaceMsg: null, replacedMessageId: '',
  editMessageId: '', fromSteer: false, agentId: 'a1', model: '', ...over })

test('默认：不重发、不带血缘、历史引用空数组', () => {
  const r = base()
  assert.equal(r.deepThink, false)
  assert.equal(r.planMode, false)
  assert.equal(r.planBaseId, '')
  assert.equal(r.historyRefs.length, 0)
  assert.equal(r.regenerate, false)
  assert.equal(r.fromSteer, false)
  assert.equal(r.agentId, 'a1')
})

test('重新生成：replaceMsg 非空即 regenerate，带被替换的回答 ID', () => {
  const r = base({ replaceMsg: { content: '旧' }, replacedMessageId: 'm1' })
  assert.equal(r.regenerate, true)
  assert.equal(r.replaceMessageId, 'm1')
})

test('自动重试（replaceMsg 存在但 messageId 为 null）：仍是重发，replaceMessageId 为空', () => {
  const r = base({ replaceMsg: { messageId: null } })
  assert.equal(r.regenerate, true)
  assert.equal(r.replaceMessageId, '')
})

test('计划/血缘/强度/模式/插话原样透传', () => {
  const r = base({ planMode: true, planBase: { planBaseId: 'p1' }, reasoningLevel: 'high', fromSteer: true, editMessageId: 'u1' })
  assert.equal(r.planMode, true)
  assert.equal(r.planBaseId, 'p1')
  assert.equal(r.reasoningLevel, 'high')
  assert.equal(r.fromSteer, true)
  assert.equal(r.editMessageId, 'u1')
})
