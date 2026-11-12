import test from 'node:test'
import assert from 'node:assert/strict'
import { mergeDoneToolCalls } from '../src/chat/toolCalls.js'

test('终态合并：优先并进仍在 start 的那张卡，补齐全文与状态', () => {
  const m = { toolCalls: [{ name: 'search', status: 'start', args: '短' }] }
  mergeDoneToolCalls(m, [{ name: 'search', status: 'done', args: '全文', result: '结果', elapsedMs: 1200, spillId: 's1' }])
  assert.equal(m.toolCalls.length, 1)
  assert.equal(m.toolCalls[0].status, 'done')
  assert.equal(m.toolCalls[0].args, '全文')
  assert.equal(m.toolCalls[0].result, '结果')
  assert.equal(m.toolCalls[0].spillId, 's1')
  assert.equal(m.toolCalls[0]._merged, true)
})

test('终态合并：实时帧已把卡改成 done 时，按同名未认领匹配，不再 push 第二张', () => {
  const m = { toolCalls: [{ name: 'read', status: 'done', _merged: false }] }
  mergeDoneToolCalls(m, [{ name: 'read', status: 'done', result: 'x' }])
  assert.equal(m.toolCalls.length, 1)
  assert.equal(m.toolCalls[0].result, 'x')
})

test('终态合并：实时帧丢了这张卡时自己 push，并标已认领', () => {
  const m = { toolCalls: [] }
  mergeDoneToolCalls(m, [{ name: 'unique', status: 'done', subChat: { answer: 'a' }, fanOut: { total: 1 } }])
  assert.equal(m.toolCalls.length, 1)
  assert.equal(m.toolCalls[0]._merged, true)
  assert.deepEqual(m.toolCalls[0].subChat, { answer: 'a' })
  assert.deepEqual(m.toolCalls[0].fanOut, { total: 1 })
})

test('终态合并：同名两次调用不会被合成一张', () => {
  const m = { toolCalls: [
    { name: 'same', status: 'done', _merged: true },
    { name: 'same', status: 'done', _merged: false },
  ] }
  mergeDoneToolCalls(m, [{ name: 'same', status: 'done', result: 'second' }])
  assert.equal(m.toolCalls.length, 2)
  assert.equal(m.toolCalls[1].result, 'second')
})

test('终态合并：空消息直接返回', () => {
  assert.doesNotThrow(() => mergeDoneToolCalls(null, []))
})
