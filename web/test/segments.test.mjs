import test from 'node:test'
import assert from 'node:assert/strict'
import { procOpen, toggleProc, procSlice, thinkSlice, thinkPeek, peekText, thinkGrowing, thinkSegOpen, toggleThinkSeg, ensureThinkSegment } from '../src/chat/segments.js'

test('过程段展开：用户点过优先；没点过时只展开正在长的末段', () => {
  const seg = { from: 4 }
  assert.equal(procOpen({ loading: true, timeline: [{ kind: 'process', from: 0, to: 2 }, { kind: 'process', from: 4, to: 6 }] }, seg), true)
  assert.equal(procOpen({ loading: true, timeline: [{ kind: 'process', from: 0, to: 2 }, { kind: 'process', from: 4, to: 6 }] }, { from: 0 }), false)
  assert.equal(procOpen({ loading: true, _procOpen: { 4: false } }, seg), false)
  assert.equal(procOpen({ loading: false }, seg), false)
  const m = {}
  toggleProc(m, seg)
  assert.equal(m._procOpen[4], true)
})

test('切片与提要：去代码块/列表符/标记并压成一行', () => {
  assert.equal(procSlice({ processText: 'abcdef' }, { from: 1, to: 4 }), 'bcd')
  assert.equal(thinkSlice({ thinking: 'xyz' }, { from: 0, to: 2 }), 'xy')
  assert.equal(peekText('```js\ncode\n```\n# 标题\n- 要点'), '标题 要点')
  assert.equal(peekText('a'.repeat(100), 10), 'a'.repeat(10))
  assert.equal(thinkPeek({ thinking: '**重点**内容' }, { from: 0, to: 100 }), '重点内容')
})

test('思考段：正在长的末段自动展开，用户点过优先', () => {
  const seg = { from: 5 }
  const m = { loading: true, thinkLoading: true, timeline: [{ kind: 'think', from: 0, to: 2 }, { kind: 'think', from: 5, to: 9 }] }
  assert.equal(thinkGrowing(m, seg), true)
  assert.equal(thinkGrowing({ ...m, loading: false }, seg), false)
  assert.equal(thinkSegOpen(m, seg), true)
  assert.equal(thinkSegOpen({ ...m, _thinkOpen: { 5: false } }, seg), false)
  const x = {}
  toggleThinkSeg(x, seg)
  assert.equal(x._thinkOpen[5], true)
})

test('ensureThinkSegment：有全文无段时在头部补一段；已有段不动', () => {
  const m = { thinking: '想了很久' }
  ensureThinkSegment(m)
  assert.deepEqual(m.timeline, [{ kind: 'think', from: 0, to: 4 }])
  const hasThink = { thinking: 'x', timeline: [{ kind: 'think', from: 0, to: 1 }] }
  ensureThinkSegment(hasThink)
  assert.equal(hasThink.timeline.length, 1)
  const onlyText = { thinking: 'x', timeline: [{ kind: 'text', from: 0, to: 1 }] }
  ensureThinkSegment(onlyText)
  assert.equal(onlyText.timeline.length, 2)
  assert.equal(ensureThinkSegment(null), null)
})
