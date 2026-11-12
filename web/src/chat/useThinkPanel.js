// 深度思考面板（宽屏：悬浮模型下拉行弹出；触屏：常驻入口点开底部 sheet）。
// 面板内档位/上下文/能力判断全部经 thinkHoverModel 取「当前模型」——做成读写 computed：
// 宽屏返回真实悬浮值，触屏返回生效模型，20 处消费点一行都不用改。
import { ref, computed, watch, nextTick, onUnmounted } from 'vue'
import { thinkStrengthTextOf, thinkPanelFootOf, pillLevelBadgeOf, pillLevelTitleOf } from './thinking.js'

const THINK_PANEL_W = 296      // .thinkp 264 + 面板卡左右内边距 32
const THINK_PANEL_GAP = 10
const THINK_EDGE = 8
const THINK_ENTER_DELAY = 120  // 扫过多行不闪面板
const THINK_LEAVE_DELAY = 200  // 留出移入面板的时间

/**
 * @param ctx.isCoarse ref<boolean>
 * @param ctx.effectiveModel/currentOverrideModel/modelIndex ref/computed
 * @param ctx.thinkCapsOf/levelOptionsOf/currentLevelOf/deepOnOf 引擎侧档位口径
 * @param ctx.fmtWindow (n) => string
 * @param ctx.viewport () => ({ w, h })（测试可注入）
 */
export const useThinkPanel = ({ isCoarse, effectiveModel, currentOverrideModel, modelIndex,
                                thinkCapsOf, levelOptionsOf, currentLevelOf, deepOnOf, fmtWindow,
                                viewport = () => ({ w: window.innerWidth, h: window.innerHeight }),
                                schedule = setTimeout, cancel = clearTimeout, onViewportChange = f => watch(() => 0, f) }) => {
  const thinkHoverRef = ref('')            // 宽屏：当前悬浮的模型引用（''=无，面板隐藏）
  const thinkPanelPos = ref({ top: 0, left: 0 })
  const thinkPanelEl = ref(null)
  // 触屏（hover:none）没有「悬浮模型行」，由工具条常驻入口点开底部 sheet，模型取当前生效模型
  const mThinkOpen = ref(false)
  const thinkHoverModel = computed({
    get: () => (isCoarse.value ? effectiveModel.value : thinkHoverRef.value),
    // 写入只发生在宽屏的 hover 逻辑里；触屏下若被 scheduleThinkHide 清空，改为关掉 sheet
    set: v => { if (isCoarse.value) mThinkOpen.value = false; else thinkHoverRef.value = v }
  })
  const thinkPanelVisible = computed(() => (isCoarse.value ? mThinkOpen.value : !!thinkHoverRef.value))
  const toggleThinkSheet = () => { mThinkOpen.value = !mThinkOpen.value }
  // 触屏下点面板外关闭：面板自身有 @mousedown.stop.prevent，这里挂 document 只收面板外的点击
  const onDocPointerDown = e => {
    if (!mThinkOpen.value) return
    if (thinkPanelEl.value && thinkPanelEl.value.contains(e.target)) return
    mThinkOpen.value = false
  }
  const thinkPanelCaps = computed(() => thinkCapsOf(thinkHoverModel.value))
  const thinkPanelLevelOptions = computed(() => levelOptionsOf(thinkHoverModel.value))
  const thinkPanelCurrentLevel = computed(() => currentLevelOf(thinkHoverModel.value))
  const thinkPanelStrengthText = computed(() =>
    thinkStrengthTextOf(deepOnOf(thinkHoverModel.value), thinkPanelCurrentLevel.value))
  const thinkPanelFoot = computed(() => thinkPanelFootOf(thinkPanelCaps.value))
  const pillLevelRef = computed(() => currentOverrideModel.value || effectiveModel.value)
  const pillLevelBadge = computed(() => pillLevelBadgeOf({
    ref: pillLevelRef.value,
    modelInfo: modelIndex.value[pillLevelRef.value],
    capsVisible: thinkCapsOf(pillLevelRef.value).visible,
    level: currentLevelOf(pillLevelRef.value)
  }))
  const pillLevelTitle = computed(() => pillLevelTitleOf({
    ref: pillLevelRef.value,
    badge: pillLevelBadge.value,
    locked: thinkCapsOf(pillLevelRef.value).locked,
    level: currentLevelOf(pillLevelRef.value),
    coarse: isCoarse.value
  }))
  /** 窗口行展示值（K/M 口径）：模型登记的上下文窗口上限；未登记整行隐藏 */
  const thinkPanelCtxText = computed(() => {
    const info = modelIndex.value[thinkHoverModel.value]
    if (!info) return ''
    return fmtWindow(info.contextWindow)
  })

  let enterTimer = null
  let leaveTimer = null
  let panelHovered = false
  let panelRect = null                    // 最近一次悬浮行的位置（面板随内容伸缩后仍按它对齐）
  const clearThinkTimers = () => {
    cancel(enterTimer); cancel(leaveTimer)
    enterTimer = leaveTimer = null
  }
  const hideThinkPanel = () => {
    clearThinkTimers()
    thinkHoverModel.value = ''
    panelHovered = false
    panelRect = null
  }
  /** 按悬浮行位置 + 面板实际高度重新收口：面板首选与行顶部对齐，放不下则整体上移。
   *  展开档位列表、换模型后内容高度都会变，须重算（否则面板伸出屏幕底部） */
  const repositionThinkPanel = () => {
    const el = thinkPanelEl.value
    if (!el || !panelRect) return
    const { h } = viewport()
    const maxTop = h - THINK_EDGE - el.offsetHeight
    const top = Math.max(THINK_EDGE, Math.min(panelRect.top - 6, maxTop))
    if (top !== thinkPanelPos.value.top) thinkPanelPos.value = { ...thinkPanelPos.value, top }
  }
  // 监听 thinkHoverRef（裸值）而非 thinkHoverModel）：后者在触屏下等于 effectiveModel，
  // 生效模型一变就会触发一次无意义的重定位（触屏面板位置由 CSS 接管，不参与 JS 定位）
  watch(thinkHoverRef, () => nextTick(repositionThinkPanel))
  /** 悬浮下拉模型行（ModelSelect 透传）：先记下行位置，稍候弹出该模型的设置面板；ref 为空=离开行 */
  const onModelOptionHover = (modelRef, rowEl) => {
    // 触屏没有 hover：模型下拉行的 per-item 设置面板不可达，改由工具条常驻入口承担。
    // 此处必须早退，否则触屏点开下拉时 hover 通路与常驻入口同时生效，两套状态会打架
    if (isCoarse.value) return
    if (!modelRef) { scheduleThinkHide(); return }
    const rect = rowEl ? rowEl.getBoundingClientRect() : null
    panelRect = rect
    cancel(leaveTimer); leaveTimer = null
    cancel(enterTimer)
    enterTimer = schedule(() => {
      enterTimer = null
      thinkHoverRef.value = modelRef
      if (rect) {
        // 右侧放不下时翻到行左侧；top 先按行顶对齐，渲染后 repositionThinkPanel 按实际高度收口
        const { w } = viewport()
        const maxLeft = w - THINK_EDGE - THINK_PANEL_W
        const left = rect.right + THINK_PANEL_GAP <= maxLeft
          ? rect.right + THINK_PANEL_GAP
          : Math.max(THINK_EDGE, rect.left - THINK_PANEL_GAP - THINK_PANEL_W)
        thinkPanelPos.value = { top: Math.max(THINK_EDGE, rect.top - 6), left }
      }
    }, THINK_ENTER_DELAY)
  }
  const scheduleThinkHide = () => {
    cancel(enterTimer); enterTimer = null
    if (leaveTimer || !thinkHoverRef.value) return
    leaveTimer = schedule(() => {
      leaveTimer = null
      if (!panelHovered) hideThinkPanel()
    }, THINK_LEAVE_DELAY)
  }
  const onThinkPanelEnter = () => { panelHovered = true; cancel(leaveTimer); leaveTimer = null }
  const onThinkPanelLeave = () => { panelHovered = false; scheduleThinkHide() }
  /** 下拉开合联动：开着时取消挂起的收起；关闭即收面板——但点面板里的档位会触发「点击外部」
   *  把下拉收起，此时指针仍在面板上（panelHovered=true）不收，保持连续调档 */
  const onModelSelectOpenChange = open => {
    if (open) { cancel(leaveTimer); leaveTimer = null }
    else scheduleThinkHide()
  }
  onUnmounted(clearThinkTimers)

  return { thinkHoverRef, thinkPanelPos, thinkPanelEl, mThinkOpen, thinkHoverModel, thinkPanelVisible,
           toggleThinkSheet, onDocPointerDown, thinkPanelCaps, thinkPanelLevelOptions, thinkPanelCurrentLevel,
           thinkPanelStrengthText, thinkPanelFoot, pillLevelRef, pillLevelBadge, pillLevelTitle, thinkPanelCtxText,
           onModelOptionHover, scheduleThinkHide, onThinkPanelEnter, onThinkPanelLeave, onModelSelectOpenChange,
           clearThinkTimers, hideThinkPanel, repositionThinkPanel, THINK_PANEL_W, THINK_PANEL_GAP, THINK_EDGE }
}
