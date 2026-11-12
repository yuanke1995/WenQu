import test from 'node:test'
import assert from 'node:assert/strict'
import { buildAskCard, attachAskCard, retireAskCard } from '../src/chat/askReducer.js'

test('构建提问卡：题目规范化、倒计时按剩余毫秒', () => {
  const c = buildAskCard({ askId: 'a1', timeoutMs: 60000, restored: true, questions: [
    { topic: 't', question: 'q', options: ['A'], multi: 'true' }, {}
  ] }, 5000)
  assert.equal(c.id, 'a1')
  assert.equal(c.restored, true)
  assert.equal(c.questions.length, 2)
  assert.equal(c.questions[0].multi, true)
  assert.deepEqual(c.picks, [[], []])
  assert.equal(c.customs.length, 2)
  assert.ok(c.deadline > Date.now())
  const noTimeout = buildAskCard({ askId: 'a2' })
  assert.equal(noTimeout.deadline, 0)
})

test('挂卡：同 id 去重；已有卡时排队；撤卡后队首顶上', () => {
  const m = {}
  assert.equal(attachAskCard(m, { askId: 'a1', questions: [] }), true)
  assert.equal(attachAskCard(m, { askId: 'a1', questions: [] }), false)
  assert.equal(attachAskCard(m, { askId: 'a2', questions: [] }), true)
  assert.equal(m.ask.id, 'a1')
  assert.equal(m.askQueue.length, 1)
  retireAskCard(m)
  assert.equal(m.ask.id, 'a2')
  retireAskCard(m)
  assert.equal(m.ask, null)
  assert.doesNotThrow(() => retireAskCard(null))
})
