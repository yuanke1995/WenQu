// 聊天页右侧浮层与折叠状态：事件抽屉、「引用来源」分组折叠、输入框容量浮层。
import { ref, reactive, computed } from 'vue'

/**
 * @param ctx.viewport () => ({ w, h })（测试可注入）
 * @param ctx.schedule/cancel 定时器（测试可注入）
 */
export const useChatPanels = ({ viewport = () => ({ w: window.innerWidth, h: window.innerHeight }),
                                  schedule = setTimeout, cancel = clearTimeout } = {}) => {
  // 「这一轮是怎么跑出来的」由事件账本按写入顺序枚举，这里给它一个读口——按轮展开，只给摘要。
  // 取数与渲染都在共用组件 SessionEventsPanel 里（移动壳的底部 sheet 用同一份）。
  const eventsOpen = ref(false)
  // 非空=只看这一轮（气泡上的「过程」带进来的 turnId）；空=会话级最近几轮
  const eventsTurn = ref('')
  // 对照视图要的那一半：这一轮那条回答 + 它对应的问题（只有按轮回放时才带）
  const eventsMsg = ref(null)
  const eventsQuestion = ref('')
  const eventsTitle = computed(() => eventsTurn.value ? '执行过程 · 这一轮' : '执行过程（事件账本）')
  const openEvents = (turnId, msg, question) => {
    // 两种看法共用一个面板：不带参数=会话级「最近几轮跑了什么」，
    // 带气泡的 turnId=只看这一条回答「是怎么跑出来的」
    eventsTurn.value = typeof turnId === 'string' ? turnId : ''
    eventsMsg.value = eventsTurn.value ? (msg || null) : null
    eventsQuestion.value = eventsTurn.value ? (question || '') : ''
    eventsOpen.value = true
  }

  // 分组展开态（key → 开/合，默认展开）：**必须存在 computed 外的响应式容器**——
  // computed 每次求值都重建分组对象，直接改非响应式临时对象既不触发重渲染、状态也会随重建被冲掉。
  // 存 reactive map 还能跨重算保留：流式更新 sources 后用户已收起的组不弹回
  const srcOpen = reactive({})
  const srcOpenOf = g => (srcOpen[g.key] !== undefined ? srcOpen[g.key] : true)
  const toggleSrc = g => { srcOpen[g.key] = !srcOpenOf(g) }

  const ctxCapOpen = ref(false)
  const ctxCapPos = ref({ top: 0, left: 0 })
  const ctxCapEl = ref(null)
  let ctxCapTimer = null
  let ctxCapHovered = false
  const showCtxCap = el => {
    cancel(ctxCapTimer)
    ctxCapTimer = schedule(() => {
      if (!el) return
      const rect = el.getBoundingClientRect()
      const { w: vw, h: vh } = viewport()
      const w = 300
      let pos
      if (rect.top >= 360) {
        // 底部工具栏圆环：卡悬在圆环上方、右沿与圆环右沿对齐
        pos = { top: null, bottom: vh - rect.top + 10, left: Math.max(8, rect.right - 268) }
      } else {
        // 上方放不下（矮视口）：退回行侧弹出，顶部与行顶对齐
        const left = rect.right + 10 + w <= vw - 8
          ? rect.right + 10
          : Math.max(8, rect.left - 10 - w)
        pos = { top: Math.min(rect.top - 6, vh - 360), bottom: null, left }
      }
      ctxCapPos.value = pos
      ctxCapOpen.value = true
    }, 150)
  }
  const hideCtxCap = () => {
    cancel(ctxCapTimer)
    ctxCapTimer = schedule(() => { if (!ctxCapHovered) ctxCapOpen.value = false }, 200)
  }
  const onCtxCapEnter = () => { ctxCapHovered = true; cancel(ctxCapTimer) }
  const onCtxCapLeave = () => { ctxCapHovered = false; hideCtxCap() }

  return { eventsOpen, eventsTurn, eventsMsg, eventsQuestion, eventsTitle, openEvents,
           srcOpen, srcOpenOf, toggleSrc,
           ctxCapOpen, ctxCapPos, ctxCapEl, showCtxCap, hideCtxCap, onCtxCapEnter, onCtxCapLeave }
}
