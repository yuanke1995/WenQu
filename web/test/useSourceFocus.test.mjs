import test from 'node:test'
import assert from 'node:assert/strict'
import { ref } from 'vue'
import { useSourceFocus } from '../src/chat/useSourceFocus.js'

const elOf = () => {
  const cls = new Set()
  return {
    classList: { toggle: (c, on) => { on ? cls.add(c) : cls.delete(c) }, remove: c => cls.delete(c), add: c => cls.add(c), has: c => cls.has(c) },
    scrollIntoView: () => { elOf.scrolled = true },
    offsetWidth: 1
  }
}

const setup = ({ lastRef = 'r1', msgIdx = 0, target = 'sup', rowFound = true } = {}) => {
  const last = { content: 'a' }
  const calls = { toggles: [], scrolls: 0, timers: [] }
  const el = elOf()
  const doc = {
    querySelectorAll: sel => { calls.lastSel = sel; return [el] },
    querySelector: sel => {
      if (sel.startsWith('.md')) return target === 'sup' ? el : null
      return rowFound ? el : null
    }
  }
  const hoveredRef = ref(null)
  const f = useSourceFocus({
    hoveredRef,
    doc,
    schedule: (fn, ms) => { calls.timers.push({ fn, ms }); return calls.timers.length },
    messagesOf: () => [last],
    lastAiOf: () => (msgIdx < 0 ? null : last)
  })
  return { f, hoveredRef, calls, el }
}

test('悬停来源：正文角标点亮并在离开时熄灭', () => {
  const { f, hoveredRef, calls } = setup()
  f.hoverSource({ ref: 'r1' })
  assert.equal(hoveredRef.value, 'r1')
  assert.match(calls.lastSel, /data-msg-index="0".*data-ref="r1"/)
  f.unhoverSource({ ref: 'r1' })
  assert.equal(hoveredRef.value, null)
})

test('没有已完成回答时不高亮', () => {
  const { f, hoveredRef } = setup({ msgIdx: -1 })
  f.hoverSource({ ref: 'r1' })
  assert.equal(hoveredRef.value, 'r1')
  f.unhoverSource({ ref: 'r1' })
  assert.equal(hoveredRef.value, null)
})

test('点来源行：正文有角标则滚动闪烁，否则开原文弹窗', () => {
  const a = setup({ target: 'sup' })
  let opened = 0
  a.f.locateSource({ ref: 'r1' }, () => { opened++ })
  assert.equal(opened, 0)
  assert.equal(a.calls.timers[0].ms, 1600)

  const b = setup({ target: 'none' })
  b.f.locateSource({ ref: 'r1' }, () => { opened++ })
  assert.equal(opened, 1)
})

test('计划行跳转：找到行则滚动闪烁，找不到静默', () => {
  const a = setup({ rowFound: true })
  a.f.jumpToPlanRow(3)
  assert.equal(a.calls.timers[0].ms, 1500)

  const b = setup({ rowFound: false })
  b.f.jumpToPlanRow(3)
  assert.equal(b.calls.timers.length, 0)
})
