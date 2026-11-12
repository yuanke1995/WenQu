import test from 'node:test'
import assert from 'node:assert/strict'
import { useChatPanels } from '../src/chat/useChatPanels.js'

const setup = () => {
  const timers = []
  const p = useChatPanels({
    viewport: () => ({ w: 1200, h: 800 }),
    schedule: (fn, ms) => { timers.push({ fn, ms }); return timers.length },
    cancel: id => { if (id) timers[id - 1] = null }
  })
  return { p, timers }
}

test('事件抽屉：无参数=会话级；带 turnId=按轮回放并带问题', () => {
  const { p } = setup()
  assert.equal(p.eventsOpen.value, false)
  assert.match(p.eventsTitle.value, /事件账本/)
  p.openEvents()
  assert.equal(p.eventsOpen.value, true)
  assert.equal(p.eventsTurn.value, '')
  assert.equal(p.eventsMsg.value, null)

  const msg = { content: '答' }
  p.openEvents('t1', msg, '问')
  assert.equal(p.eventsTurn.value, 't1')
  assert.deepEqual(p.eventsMsg.value, msg)
  assert.equal(p.eventsQuestion.value, '问')
  assert.match(p.eventsTitle.value, /这一轮/)
})

test('事件抽屉：turnId 非字符串按会话级处理', () => {
  const { p } = setup()
  p.openEvents(42, { content: 'x' }, 'q')
  assert.equal(p.eventsTurn.value, '')
  assert.equal(p.eventsMsg.value, null)
})

test('来源分组：默认展开，点击切换并记住状态', () => {
  const { p } = setup()
  const g = { key: 'doc:1' }
  assert.equal(p.srcOpenOf(g), true)
  p.toggleSrc(g)
  assert.equal(p.srcOpenOf(g), false)
  p.toggleSrc(g)
  assert.equal(p.srcOpenOf(g), true)
})

test('容量浮层：底部圆环悬在上方；矮视口退回行侧', () => {
  const bottom = setup()
  bottom.p.showCtxCap({ getBoundingClientRect: () => ({ top: 500, bottom: 530, right: 1000, left: 960 }) })
  assert.equal(bottom.timers[0].ms, 150)
  bottom.timers[0].fn()
  assert.equal(bottom.p.ctxCapOpen.value, true)
  assert.equal(bottom.p.ctxCapPos.value.bottom, 800 - 500 + 10)
  assert.equal(bottom.p.ctxCapPos.value.top, null)
  assert.equal(bottom.p.ctxCapPos.value.left, 1000 - 268)

  const side = setup()
  side.p.showCtxCap({ getBoundingClientRect: () => ({ top: 100, bottom: 130, right: 400, left: 360 }) })
  side.timers[0].fn()
  assert.equal(side.p.ctxCapPos.value.top, 94)
  assert.equal(side.p.ctxCapPos.value.bottom, null)
  assert.equal(side.p.ctxCapPos.value.left, 410)
})

test('容量浮层：右侧放不下翻到行左；顶部不越出视区', () => {
  const { p, timers } = setup()
  p.showCtxCap({ getBoundingClientRect: () => ({ top: 100, bottom: 130, right: 1150, left: 1100 }) })
  timers[0].fn()
  assert.equal(p.ctxCapPos.value.left, 1100 - 10 - 300)
  assert.equal(p.ctxCapPos.value.top, 100 - 6)
})

test('容量浮层：离开后延迟隐藏；指针进入卡上时取消挂起的隐藏', () => {
  const a = setup()
  a.p.ctxCapOpen.value = true
  a.p.onCtxCapEnter()
  a.p.onCtxCapLeave()
  a.timers[0].fn()
  assert.equal(a.p.ctxCapOpen.value, false)

  const b = setup()
  b.p.ctxCapOpen.value = true
  b.p.hideCtxCap()
  b.p.onCtxCapEnter()
  assert.equal(b.timers[0], null)          // 挂起的隐藏已取消
  b.p.onCtxCapLeave()
  b.timers[1].fn()
  assert.equal(b.p.ctxCapOpen.value, false)
})
