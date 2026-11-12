import test from 'node:test'
import assert from 'node:assert/strict'
import { snapshotVersion, applyVersion, verLocal, canSwitchPrev, canSwitchNext, verLabel } from '../src/chat/versions.js'

test('版本快照：缺省值补齐，用时与模型随版本走', () => {
  const v = snapshotVersion({ content: 'a', turnMs: 1200, model: 'm1' })
  assert.equal(v.content, 'a')
  assert.deepEqual(v.sources, [])
  assert.equal(v.plan, null)
  assert.equal(v.turnMs, 1200)
  assert.equal(v.turnPrepMs, 0)
  assert.equal(v.model, 'm1')
})

test('版本回填：把快照写回消息，模型与用时一起切', () => {
  const m = { content: 'new' }
  const v = snapshotVersion({ content: 'old', turnMs: 300, model: 'm-old', sources: [{ id: 1 }] })
  applyVersion(m, v)
  assert.equal(m.content, 'old')
  assert.equal(m.turnMs, 300)
  assert.equal(m.model, 'm-old')
  assert.deepEqual(m.sources, [{ id: 1 }])
})

test('切换边界：内存多版本优先', () => {
  const m = { versions: [{}, {}, {}], vIndex: 1, variantIndex: 1, variantCount: 5 }
  assert.equal(verLocal(m), true)
  assert.equal(canSwitchPrev(m), true)
  assert.equal(canSwitchNext(m), true)
  assert.equal(verLabel(m), '2/3')
  assert.equal(canSwitchPrev({ versions: [{}, {}], vIndex: 0 }), false)
  assert.equal(canSwitchNext({ versions: [{}, {}], vIndex: 1 }), false)
})

test('切换边界：无内存版本时看持久 variant', () => {
  const m = { variantIndex: 1, variantCount: 1 }
  assert.equal(verLocal(m), false)
  assert.equal(canSwitchPrev(m), false)
  assert.equal(canSwitchNext(m), false)
  assert.equal(verLabel(m), '1/1')
  assert.equal(canSwitchNext({ variantIndex: 1, variantCount: 2 }), true)
  assert.equal(canSwitchPrev({ variantIndex: 2, variantCount: 2 }), true)
})
