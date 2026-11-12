import test from 'node:test'
import assert from 'node:assert/strict'
import { extendTimelineText, extendTimelineProcess, pushTimelineThink, extendTimelineThink, pushTimelineTool, pushTimelineArtifact, pushTimelineSteer, restoreTimeline } from '../src/chat/timelineWrite.js'

test('文本/过程/思考段：相邻延伸、断开新起', () => {
  const m = {}
  extendTimelineText(m, 0, 5)
  extendTimelineText(m, 5, 9)
  extendTimelineText(m, 20, 30)
  assert.deepEqual(m.timeline, [{ kind: 'text', from: 0, to: 9 }, { kind: 'text', from: 20, to: 30 }])
  extendTimelineProcess(m, 0, 2)
  extendTimelineProcess(m, 2, 4)
  assert.equal(m.timeline.filter(s => s.kind === 'process').length, 1)
  pushTimelineThink(m, 0, 3)
  extendTimelineThink(m, 3, 6)
  assert.deepEqual(m.timeline.find(s => s.kind === 'think'), { kind: 'think', from: 0, to: 6 })
})

test('工具/产物段：工具段带当前下标', () => {
  const m = { toolCalls: [{}] }
  pushTimelineTool(m, { name: 'a' })
  assert.equal(m.timeline[0].i, 0)
  assert.equal(m.timeline[0].tool.name, 'a')
  pushTimelineArtifact(m, 2)
  assert.deepEqual(m.timeline[1], { kind: 'artifact', i: 2 })
})

test('插话段：同 messageId 幂等，空文本忽略', () => {
  const m = {}
  pushTimelineSteer(m, '插话', 'm1')
  pushTimelineSteer(m, '插话', 'm1')
  pushTimelineSteer(m, '', 'm2')
  assert.equal(m.timeline.length, 1)
  assert.equal(m.timeline[0].text, '插话')
})

test('恢复：按索引取回工具对象，缺索引跳过，相邻过程段合并，思考不合并', () => {
  const m = { toolCalls: [{ name: 'a' }, { name: 'b' }] }
  const out = restoreTimeline(m, [
    { kind: 'think', from: 0, to: 1 },
    { kind: 'think', from: 1, to: 2 },
    { kind: 'process', from: 0, to: 1 },
    { kind: 'process', from: 1, to: 2 },
    { kind: 'tool', i: 1 },
    { kind: 'tool', i: 9 },
    { kind: 'artifact', i: 0 },
    { kind: 'steer', text: 'x', messageId: 's1' },
    { kind: 'unknown' },
  ])
  assert.equal(out.filter(s => s.kind === 'think').length, 2)
  assert.equal(out.filter(s => s.kind === 'process').length, 1)
  assert.equal(out.find(s => s.kind === 'tool').tool.name, 'b')
  assert.equal(out.filter(s => s.kind === 'tool').length, 1)
  assert.equal(out.filter(s => s.kind === 'steer').length, 1)
  assert.equal(out.length, 6)
})
