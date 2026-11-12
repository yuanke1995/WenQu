import test from 'node:test'
import assert from 'node:assert/strict'
import { ref } from 'vue'
import { useChatScroll } from '../src/chat/useChatScroll.js'
import { buildQaAnchors } from '../src/chat/chatAnchors.js'

const setup = ({ anchors = [], nodeTops = {}, tailH = 0, qTopAbs = 200 } = {}) => {
  const q = { getBoundingClientRect: () => ({ top: qTopAbs }) }
  const sp = { getBoundingClientRect: () => ({ height: tailH }) }
  const scrolled = []
  const el = {
    scrollHeight: 1000, scrollTop: 900, clientHeight: 200,
    querySelectorAll: sel => (sel === '.row.user' ? [q] : []),
    querySelector: sel => (sel === '.tail-spacer' ? sp : (nodeTops[sel] ? { getBoundingClientRect: () => ({ top: nodeTops[sel] }), scrollIntoView: o => scrolled.push(o) } : null)),
    getBoundingClientRect: () => ({ top: 100 })
  }
  const box = ref(el)
  const qa = ref(anchors)
  const c = useChatScroll({ box, messagesOf: () => [], qaAnchorsOf: () => qa.value, styleOf: () => ({ paddingTop: '0' }) })
  return { c, el, qa, scrolled, box }
}

test('换会话的 qaAnchors：只画提问行并带其后第一条回答', () => {
  const list = [
    { role: 'user', content: 'q1' },
    { role: 'ai', content: 'a1', loading: true },
    { role: 'ai', content: 'a2' },
    { role: 'user', content: 'q2' }
  ]
  const a = buildQaAnchors(list)
  assert.equal(a.length, 2)
  assert.deepEqual(a[0], { idx: 0, q: 'q1', a: 'a1', loading: true, hasAi: true })
  assert.deepEqual(a[1], { idx: 3, q: 'q2', a: '', loading: false, hasAi: false })
  assert.deepEqual(buildQaAnchors(null), [])
})

test('滚动贴底判定：小于阈值即跟随，超过则暂停', () => {
  const { c, el } = setup()
  c.onMessagesScroll()
  assert.equal(c.stickToBottom.value, true)
  el.scrollTop = 100
  c.onMessagesScroll()
  assert.equal(c.stickToBottom.value, false)
})

test('scroll 仅在跟随态贴底；scrollForce 总是贴底', async () => {
  const { c, el } = setup()
  el.scrollTop = 100
  c.onMessagesScroll()
  await c.scroll()
  assert.equal(el.scrollTop, 100)
  await c.scrollForce()
  assert.equal(el.scrollTop, 1000)
  assert.equal(c.stickToBottom.value, true)
})

test('尾随留白：按问题顶到内容底补一屏，扣除已渲染留白高度', () => {
  const { c } = setup({ tailH: 50 })
  c.updateTailSpacer()
  // 内容底 = scrollHeight - 已渲染留白 = 950；问题顶相对容器 = 200-100 = 100
  // tail = 950 - (900 + 100) = -50；留白 = clientHeight - 0 - (-50) = 250
  assert.equal(c.tailSpacer.value, 250)
})

test('尾随留白：没有提问行时归零', () => {
  const { c, box } = setup()
  box.value = { querySelectorAll: () => [], getBoundingClientRect: () => ({ top: 0 }), scrollHeight: 0, scrollTop: 0, clientHeight: 0 }
  c.updateTailSpacer()
  assert.equal(c.tailSpacer.value, 0)
})

test('右轨点亮：取行顶越过视区上三分之一的最后一条提问', () => {
  const anchors = [{ idx: 5 }, { idx: 9 }]
  const { c } = setup({ anchors, nodeTops: { '[data-row-index="5"]': 150, '[data-row-index="9"]': 400 } })
  c.updateRailActive()
  assert.equal(c.railActiveIdx.value, 5)
})

test('右轨点亮：没有锚点或没有容器时清空', () => {
  const { c, box } = setup({ anchors: [] })
  c.updateRailActive()
  assert.equal(c.railActiveIdx.value, -1)
  box.value = null
  c.updateRailActive()
  assert.equal(c.railActiveIdx.value, -1)
})

test('悬浮预览卡：按刻度右侧定位并夹回视区', () => {
  const anchors = [{ idx: 1 }]
  const { c } = setup({ anchors })
  c.showAnchor(anchors[0], { currentTarget: { getBoundingClientRect: () => ({ top: 500, right: 100 }) } })
  assert.equal(c.railHoverIdx.value, 0)
  assert.equal(c.railPopStyle.value.left, '112px')
  assert.equal(c.railPopStyle.value.top, '482px')
})

test('gotoAnchor：按行号滚到该行顶部', () => {
  const anchors = [{ idx: 7 }]
  const { c, scrolled } = setup({ anchors, nodeTops: { '[data-row-index="7"]': 300 } })
  c.gotoAnchor(anchors[0])
  assert.deepEqual(scrolled, [{ block: 'start', behavior: 'smooth' }])
})
