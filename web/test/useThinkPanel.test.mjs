import test from 'node:test'
import assert from 'node:assert/strict'
import { ref, computed } from 'vue'
import { useThinkPanel } from '../src/chat/useThinkPanel.js'

const setup = ({ coarse = false, effective = 'v/m', override = '' } = {}) => {
  const timers = []
  const coarseRef = ref(coarse)
  const modelIndex = ref({ 'v/m': { displayName: 'M', contextWindow: 128000 } })
  const effectiveRef = ref(effective)
  const overrideRef = ref(override)
  const p = useThinkPanel({
    isCoarse: coarseRef,
    effectiveModel: effectiveRef,
    currentOverrideModel: overrideRef,
    modelIndex,
    thinkCapsOf: () => ({ visible: true, locked: false }),
    levelOptionsOf: () => [{ value: 'off' }],
    currentLevelOf: () => 'high',
    deepOnOf: () => true,
    fmtWindow: n => (n ? Math.round(n / 1000) + 'K' : ''),
    viewport: () => ({ w: 1200, h: 800 }),
    schedule: (fn, ms) => { timers.push({ fn, ms }); return timers.length },
    cancel: id => { if (id) timers[id - 1] = null }
  })
  return { p, timers, coarseRef, modelIndex, overrideRef }
}

test('触屏：模型取生效模型，sheet 开合与点外关闭', () => {
  const { p } = setup({ coarse: true })
  assert.equal(p.thinkHoverModel.value, 'v/m')
  assert.equal(p.thinkPanelVisible.value, false)
  p.toggleThinkSheet()
  assert.equal(p.mThinkOpen.value, true)
  assert.equal(p.thinkPanelVisible.value, true)
  p.onDocPointerDown({ target: {} })
  assert.equal(p.mThinkOpen.value, false)
})

test('触屏：点面板内不关闭；悬浮行回调早退不排定时器', () => {
  const { p, timers } = setup({ coarse: true })
  p.mThinkOpen.value = true
  p.onDocPointerDown({ target: 'inner' })
  // el 为空时 contains 不可用 → 关闭；先用假 el 测「内部不关」
  p.thinkPanelEl.value = { contains: t => t === 'inner' }
  p.mThinkOpen.value = true
  p.onDocPointerDown({ target: 'inner' })
  assert.equal(p.mThinkOpen.value, true)
  p.onModelOptionHover('v/m', { getBoundingClientRect: () => ({ right: 10, left: 0, top: 10 }) })
  assert.equal(timers.length, 0)
})

test('宽屏：悬浮 120ms 后弹出，按行右侧定位并记录位置', () => {
  const { p, timers } = setup()
  p.onModelOptionHover('v/m', { getBoundingClientRect: () => ({ right: 600, left: 400, top: 200 }) })
  assert.equal(timers.length, 1)
  assert.equal(timers[0].ms, 120)
  timers[0].fn()
  assert.equal(p.thinkHoverModel.value, 'v/m')
  assert.equal(p.thinkPanelPos.value.left, 610)
  assert.equal(p.thinkPanelPos.value.top, 194)
})

test('宽屏：右侧放不下翻到行左侧', () => {
  const { p, timers } = setup()
  p.onModelOptionHover('v/m', { getBoundingClientRect: () => ({ right: 1150, left: 900, top: 50 }) })
  timers[0].fn()
  assert.equal(p.thinkPanelPos.value.left, 900 - 10 - 296)
})

test('宽屏：离开延迟收起；指针进面板取消收起', () => {
  const { p, timers } = setup()
  p.onModelOptionHover('v/m', { getBoundingClientRect: () => ({ right: 100, left: 50, top: 20 }) })
  timers[0].fn()
  p.onModelOptionHover('', null)   // 离开行
  const hideTimer = timers[1]
  assert.equal(hideTimer.ms, 200)
  p.onThinkPanelEnter()            // 进入面板
  hideTimer.fn()
  assert.equal(p.thinkHoverModel.value, 'v/m')  // 没被收起
})

test('宽屏：面板收起后清空模型；下拉关闭时若指针不在面板上则收起', () => {
  const { p, timers } = setup()
  p.onModelOptionHover('v/m', { getBoundingClientRect: () => ({ right: 100, left: 50, top: 20 }) })
  timers[0].fn()
  p.onModelSelectOpenChange(false)
  timers[1].fn()
  assert.equal(p.thinkHoverModel.value, '')
})

test('重定位：超出屏幕底部时上移收口', () => {
  const { p } = setup()
  p.onModelOptionHover('v/m', { getBoundingClientRect: () => ({ right: 100, left: 50, top: 700 }) })
  p.thinkPanelEl.value = { offsetHeight: 300 }
  p.repositionThinkPanel()
  assert.equal(p.thinkPanelPos.value.top, 800 - 8 - 300)
})

test('展示口径：强度行/底部提示/胶囊标记与窗口文本', () => {
  const { p, coarseRef } = setup()
  assert.equal(p.thinkPanelStrengthText.value, '高')
  assert.match(p.thinkPanelFoot.value, /强度越高/)
  assert.equal(p.pillLevelBadge.value, '高')
  assert.match(p.pillLevelTitle.value, /悬浮下拉/)
  coarseRef.value = true
  assert.match(p.pillLevelTitle.value, /点旁边的「思考」/)
  assert.equal(p.thinkPanelCtxText.value, '128K')
})
