import test from 'node:test'
import assert from 'node:assert/strict'
import { buildHistoryList, dropSteeredUserRows } from '../src/chat/historyRows.js'

const deps = (over = {}) => ({
  planCardFromRecord: (rec, _, sid) => ({ from: rec.plan, sid }),
  restoreTimeline: (msg, tl) => tl.map(x => ({ ...x, restored: true })),
  applyBlocks: (msg, blocks) => { msg.content = msg.content + '|blocks' },
  ensureThinkSegment: msg => { msg.thinkSegment = true },
  ...over
})

test('过滤：空正文/无图/无计划的行丢弃，带计划快照的空正文保留', () => {
  const list = buildHistoryList([
    { role: 'ai', content: '' },
    { role: 'user', content: '', images: ['data:x'] },
    { role: 'ai', content: '', plan: { plan: { steps: [] } } },
    { role: 'user', content: 'hi' }
  ], 's1', deps())
  assert.equal(list.length, 3)
  assert.equal(list[2].content, 'hi')
})

test('字段归一：角色/时间/引用/附件凭据/失败卡', () => {
  const list = buildHistoryList([{
    role: 'assistant', content: 'a', messageId: 'm1', createTime: '2026-01-01T00:00:00Z',
    attachments: [{ name: 'f', mime: 'text/plain', fileId: 'f1' }, { name: 'nofile' }],
    mentions: [{ id: 'k' }], historyRefs: [{ messageId: 'h' }], failMsg: '出错了',
    tokens: { context: 10 }, agentId: 'ag', agentName: '助手', model: 'v/m',
    variantCount: 2, variantIndex: 1, blocks: { b: 1 }
  }], 's1', deps())
  const m = list[0]
  assert.equal(m.role, 'ai')
  assert.equal(m.messageId, 'm1')
  assert.equal(m.time, new Date('2026-01-01T00:00:00Z').getTime())
  assert.deepEqual(m.attachData, [{ name: 'f', mime: 'text/plain', fileId: 'f1' }])
  assert.deepEqual(m.mentions, [{ id: 'k' }])
  assert.deepEqual(m.historyRefs, [{ messageId: 'h' }])
  assert.deepEqual(m.errorCard, { message: '出错了', kind: 'spoken', at: m.time })
  assert.equal(m.agentId, 'ag')
  assert.equal(m.model, 'v/m')
  assert.equal(m.variantCount, 2)
  assert.equal(m.content, 'a|blocks')
  assert.equal(m.thinkSegment, true)
})

test('时间线：优先 blocks 投影，否则恢复 timeline', () => {
  const withBlocks = buildHistoryList([{ role: 'ai', content: 'a', blocks: { x: 1 }, timeline: [{ kind: 'steer' }] }], 's1', deps())
  assert.equal(withBlocks[0].blocks.x, 1)
  assert.deepEqual(withBlocks[0].timeline, [])

  const withTl = buildHistoryList([{ role: 'ai', content: 'a', timeline: [{ kind: 'tool' }] }], 's1', deps())
  assert.deepEqual(withTl[0].timeline, [{ kind: 'tool', restored: true }])
})

test('计划卡：有 plan 记录时按注入口径重建', () => {
  const list = buildHistoryList([{ role: 'ai', content: 'a', plan: { plan: { steps: [1] } } }], 's9', deps())
  assert.deepEqual(list[0].planCard, { from: { steps: [1] }, sid: 's9' })
})

test('retrieved 解析：坏 JSON 回落 null', () => {
  const ok = buildHistoryList([{ role: 'ai', content: 'a', retrieved: '{"x":1}' }], 's1', deps())
  assert.deepEqual(ok[0].retrieved, { x: 1 })
  const bad = buildHistoryList([{ role: 'ai', content: 'a', retrieved: '{bad' }], 's1', deps())
  assert.equal(bad[0].retrieved, null)
})

test('插话用户行退场：时间线用掉的 messageId 对应行删除，其余保留', () => {
  const list = [
    { role: 'user', content: '插话', messageId: 'u1' },
    { role: 'ai', content: 'a', timeline: [{ kind: 'steer', messageId: 'u1' }] },
    { role: 'user', content: '普通', messageId: 'u2' },
    { role: 'user', content: '无 id' }
  ]
  const out = dropSteeredUserRows(list)
  assert.equal(out.length, 3)
  assert.ok(!out.some(m => m.messageId === 'u1'))
})

test('插话行退场：无可删行时原样返回同一数组', () => {
  const list = [{ role: 'ai', content: 'a' }]
  assert.equal(dropSteeredUserRows(list), list)
})
