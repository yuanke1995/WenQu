// 聊天区滚动：贴底跟随、尾随留白（问题置顶）、右轨锚点（悬浮预览 + 当前轮点亮）。
// DOM 读取经注入（styleOf/元素对象），便于在无 DOM 的单测里验证判据。
import { ref, computed, nextTick } from 'vue'

/**
 * @param ctx.box ref<HTMLElement> 消息滚动容器
 * @param ctx.messages ref<消息[]>（换会话时重置贴底与留白）
 * @param ctx.qaAnchors ref<锚点[]>（轨上点亮用）
 * @param ctx.styleOf (el) => CSSStyleDeclaration（测试注入）
 */
export const useChatScroll = ({ box, messagesOf, qaAnchorsOf, styleOf = el => getComputedStyle(el) }) => {
  const stickToBottom = ref(true)
  const AUTO_SCROLL_MARGIN = 80
  // 尾随留白高度（px）：把本轮问题下方补足到一屏，使「贴底」的落点正好是问题置顶。
  // 于是「问题拉到最上」与「回答时跟到底部」不再互斥。
  const tailSpacer = ref(0)

  const scroll = () => nextTick(() => {
    if (box.value && stickToBottom.value) {
      box.value.scrollTop = box.value.scrollHeight
      stickToBottom.value = true
    }
  })
  const scrollForce = () => nextTick(() => {
    if (box.value) {
      box.value.scrollTop = box.value.scrollHeight
      stickToBottom.value = true
    }
  })
  // 尾随留白：问题顶到内容底的距离补到一屏（含容器上内边距），贴底落点即问题置顶。
  // 量的是「问题顶→内容底」，与留白自身无关，故可在留白生效时重复计算而不自激。
  const updateTailSpacer = () => {
    const el = box.value
    if (!el) return
    const users = el.querySelectorAll('.row.user')
    const q = users[users.length - 1]
    if (!q) { tailSpacer.value = 0; return }
    const padTop = parseFloat(styleOf(el).paddingTop) || 0
    const qTop = q.getBoundingClientRect().top - el.getBoundingClientRect().top
    // 减的是 DOM 上留白的实际高度（不是 ref：ref 会先于渲染领先一两拍，那样量出的内容底偏小，
    // 留白算大、贴底就一点点越过落点，且渲染越重越明显）
    const sp = el.querySelector('.tail-spacer')
    const spH = sp ? sp.getBoundingClientRect().height : 0
    const tail = (el.scrollHeight - spH) - (el.scrollTop + qTop)
    tailSpacer.value = Math.max(0, Math.round(el.clientHeight - padTop - tail))
  }

  // ==================== 右轨锚点 ====================
  const railVis = ref(false)
  const railHoverIdx = ref(-1)
  const railPopStyle = ref({})
  const railHoverAnchor = computed(() => (railHoverIdx.value >= 0 ? qaAnchorsOf()[railHoverIdx.value] : null))
  const showAnchor = (a, evt) => {
    railHoverIdx.value = qaAnchorsOf().indexOf(a)
    const r = evt.currentTarget.getBoundingClientRect()
    // 卡高最多约 130px：贴着刻度垂直居中摆，顶/底出屏就夹回视区内
    const h = (typeof window !== 'undefined' && window.innerHeight) || 800
    const top = Math.min(Math.max(r.top - 18, 12), h - 150)
    railPopStyle.value = { left: (r.right + 12) + 'px', top: top + 'px' }
  }
  const gotoAnchor = a => {
    box.value?.querySelector(`[data-row-index="${a.idx}"]`)
      ?.scrollIntoView({ block: 'start', behavior: 'smooth' })
  }
  /** 当前视区落在哪一轮（轨上点亮）：取「行顶已越过视区上三分之一」的最后一条提问 */
  const railActiveIdx = ref(-1)
  const updateRailActive = () => {
    const el = box.value
    const anchors = qaAnchorsOf()
    if (!el || !anchors.length) { railActiveIdx.value = -1; return }
    const base = el.getBoundingClientRect().top
    const probe = base + el.clientHeight / 3
    let cur = -1
    for (const a of anchors) {
      const node = el.querySelector(`[data-row-index="${a.idx}"]`)
      if (node && node.getBoundingClientRect().top <= probe) cur = a.idx
      else if (cur >= 0) break
    }
    railActiveIdx.value = cur
  }
  const onMessagesScroll = () => {
    const el = box.value
    if (!el) return
    stickToBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < AUTO_SCROLL_MARGIN
    updateRailActive()
  }

  return { stickToBottom, tailSpacer, AUTO_SCROLL_MARGIN, onMessagesScroll, scroll, scrollForce,
           updateTailSpacer, railVis, railHoverIdx, railPopStyle, railHoverAnchor, showAnchor, gotoAnchor,
           railActiveIdx, updateRailActive }
}
