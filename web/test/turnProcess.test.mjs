import test from 'node:test'
import assert from 'node:assert/strict'
import { turnProcessRows } from '../src/chat/turnProcess.js'

test('过程块：思考/工具/过程按序成簇，text 不进过程块', () => {
  const m = {
    content: '正文',
    thinking: '想一想',
    processText: '独白',
    toolCalls: [{ name: 'read_file', status: 'done' }],
    timeline: [
      { kind: 'think', from: 0, to: 3 },
      { kind: 'tool', tool: { name: 'read_file', status: 'done' } },
      { kind: 'text', from: 0, to: 2 },
      { kind: 'process', from: 0, to: 2 },
    ],
  }
  const rows = turnProcessRows(m)
  // 正文段不参与过程块，思考/工具/独白连续游程合成一簇（正文与过程交错不影响聚簇）
  assert.equal(rows.length, 1)
  assert.equal(rows[0].kind, 'cluster')
  assert.deepEqual(rows[0].segs.map(s => s.kind), ['think', 'group', 'process'])
  assert.equal(rows[0].grow, true)
})

test('过程块：无时间线时退回工具终态清单（单批工具也进簇）', () => {
  const m = { toolCalls: [{ name: 'a' }, { name: 'b' }] }
  const rows = turnProcessRows(m)
  assert.equal(rows.length, 1)
  assert.equal(rows[0].kind, 'cluster')
  assert.equal(rows[0].segs[0].tools.length, 2)
})

test('过程块：空消息返回空数组；越界切片被裁到原文长度', () => {
  assert.deepEqual(turnProcessRows(null), [])
  const m = { thinking: 'ab', timeline: [{ kind: 'think', from: 1, to: 99 }] }
  const rows = turnProcessRows(m)
  assert.equal(rows[0].kind, 'think')
  assert.equal(rows[0].to, 2)
})
