import test from 'node:test'
import assert from 'node:assert/strict'
import { createThoughtFrames } from '../src/chat/streamThoughtFrames.js'

const setup = () => {
  const calls = { flush: [], push: [], extend: [], scroll: 0 }
  const msg = { timeline: [] }
  const t = createThoughtFrames({
    msg,
    flushNow: (keep) => { calls.flush.push(keep) },
    scroll: () => { calls.scroll++ },
    pushThink: (m, from, to) => { calls.push.push([from, to]); m.timeline.push({ kind: 'think', from, to }) },
    extendThink: (m, from, to) => { calls.extend.push([from, to]); const last = m.timeline[m.timeline.length - 1]; if (last) last.to = to }
  })
  return { t, msg, calls }
}

test('首片思考：先落正文、另起一段、标记进行中', () => {
  const { t, msg, calls } = setup()
  t.append('想')
  assert.deepEqual(calls.flush, [false])
  assert.deepEqual(calls.push, [[0, 1]])
  assert.equal(msg.thinking, '想')
  assert.equal(msg.thinkLoading, true)
  assert.equal(calls.scroll, 1)
})

test('同一段继续：接在上一段区间上，不新起段', () => {
  const { t, msg, calls } = setup()
  t.append('ab')
  t.append('cd')
  assert.deepEqual(calls.push, [[0, 2]])
  assert.deepEqual(calls.extend, [[2, 4]])
  assert.equal(msg.thinking, 'abcd')
  assert.equal(msg.timeline.length, 1)
  assert.equal(msg.timeline[0].to, 4)
})

test('断段后下一片思考另起一段', () => {
  const { t, msg, calls } = setup()
  t.append('a')
  t.closeRound()
  t.append('b')
  assert.deepEqual(calls.push, [[0, 1], [1, 2]])
  assert.equal(msg.timeline.length, 2)
})

test('空增量忽略', () => {
  const { t, calls } = setup()
  t.append('')
  t.append(null)
  assert.equal(calls.push.length, 0)
  assert.equal(calls.flush.length, 0)
})

test('终稿校正：权威全文更长时把差额归到刚收场的段', () => {
  const { t, msg, calls } = setup()
  t.append('短')
  t.finish(JSON.stringify({ thinking: '更长的权威全文' }))
  assert.equal(msg.thinkLoading, false)
  assert.equal(msg.thinking, '更长的权威全文')
  assert.equal(msg.timeline.at(-1).to, '更长的权威全文'.length)
  assert.equal(calls.extend.length, 0)  // 不新开段，只校正区间

  t.append('x')
  assert.deepEqual(calls.push.at(-1), ['更长的权威全文'.length, '更长的权威全文'.length + 1])
})

test('终稿：无负载/坏 JSON 不抛错；本地更长时保留', () => {
  const { t, msg } = setup()
  t.append('local')
  t.finish('')
  t.finish('{bad')
  assert.equal(msg.thinking, 'local')
  assert.equal(msg.thinkLoading, false)
})

test('快照接入：setOpen 决定下一片是接段还是新段', () => {
  const a = setup()
  a.msg.thinking = '已有'
  a.t.setOpen(true)
  a.t.append('续')
  assert.equal(a.calls.extend.length, 1)

  const b = setup()
  b.msg.thinking = '已有'
  b.t.setOpen(false)
  b.t.append('新')
  assert.equal(b.calls.push.length, 1)
})
