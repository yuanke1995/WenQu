// ==================== 会话内查找（PC 快捷键 / 移动顶栏入口共用） ====================
// 从 ChatPage.vue 抽出来的原因：这个功能要在移动壳里再做一遍入口，而复制一份 130 行
// 的 Range/Highlight 逻辑必然两端分叉（那边改了行契约这边不跟着改就是静默失效）。
//
// 两端 DOM 契约被刻意统一成 **[data-row-index]** —— 消息行由谁来碾压不重要，
// 只要它带这个属性，定位与"当前这条消息"分组就都能算对（PC 内联模板与 MobileMsgRow 都已带）。
//
// 高亮本体天然共用：::highlight(chat-search) / (chat-search-current) 定义在全局 app.css，
// 走 CSS Custom Highlight API —— 回答正文是 v-html 渲染的，往里插 <mark> 会与重渲染/渲染缓存打架。
// 浏览器不支持该 API 时降级为"只定位不标黄"，功能仍可用（见 paintHighlight）。
import { computed, nextTick, ref, unref, watch } from 'vue'
import { message } from 'ant-design-vue'

/** 参与查找的节点过滤器：代码块与工具/思考过程不参与——那些是过程信息，
 *  把它们算进结果只会让"找那句话"更难（也会让命中亮成一片）。 */
const makeTextFilter = () => ({
  acceptNode: n => {
    const p = n.parentElement
    if (!p || p.closest('pre, code, .code-copy, .tl-io, script, style')) return NodeFilter.FILTER_REJECT
    return NodeFilter.FILTER_ACCEPT
  }
})

/**
 * @param {object} deps
 * @param {import('vue').Ref<HTMLElement|null>} deps.boxRef 消息滚动容器
 * @param {import('vue').Ref<Array>|(() => Array)} deps.messages 消息列表。
 *        允许传 getter 是因为 PC 壳的 messages 来自 useChatEngine 解构（const 声明在调用点之后），
 *        传 `() => messages` 才能绕过暂时性死区。
 *
 * 会话切换的重置刻意**不**做成内部 watch(sessionId)：Vue 的 getter source 在 watch 创建时会立即求值一次，
 * 而 PC 壳的调用点位于 useChatEngine 解构之前（currentSessionId 还在 TDZ），一注册就是 ReferenceError。
 * 改由调用方在自己的安全位置 watch 会话并置 0 —— searchPos 已随返回值暴露。
 */
export const useChatSearch = ({ boxRef, messages }) => {
  const rows = computed(() => (typeof messages === 'function' ? messages() : unref(messages)) || [])
  const searchOpen = ref(false)
  const searchQuery = ref('')
  const searchPos = ref(0)
  const searchInputRef = ref(null)

  const rowOf = idx => boxRef.value?.querySelector(`[data-row-index="${idx}"]`) || null

  /** 命中范围 = 每条消息的正文（用户问题 + 助手回答） */
  const matchedIdxs = computed(() => {
    const q = searchQuery.value.trim().toLowerCase()
    if (!q) return []
    const out = []
    rows.value.forEach((m, i) => {
      if ((m.content || '').toLowerCase().includes(q)) out.push(i)
    })
    return out
  })

  /** 显示用的序号：切会话/改关键词后匹配数可能变小，这里夹住上界（否则出现 4 / 2 这种读数） */
  const searchPosShown = computed(() => {
    const n = matchedIdxs.value.length
    return n ? Math.min(searchPos.value, n - 1) + 1 : 0
  })

  const clearHighlight = () => {
    try {
      window.CSS?.highlights?.delete('chat-search')
      window.CSS?.highlights?.delete('chat-search-current')
    } catch (e) { /* 不支持该 API 时忽略 */ }
  }

  /** 逐处命中建 Range，并按所属消息分成「全部命中」与「当前这条消息里的命中」两组——
   *  当前项用更醒目的颜色，否则用户不知道该看哪一处。 */
  const collectRanges = (q, currentMsgIdx) => {
    const all = [], cur = []
    const root = boxRef.value
    if (!root || !q) return { all, cur }
    const lower = q.toLowerCase()
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, makeTextFilter())
    while (walker.nextNode()) {
      const node = walker.currentNode
      const rowIdx = node.parentElement?.closest('[data-row-index]')?.dataset.rowIndex
      const target = (currentMsgIdx != null && rowIdx === String(currentMsgIdx)) ? cur : null
      const low = (node.nodeValue || '').toLowerCase()
      let from = 0
      for (;;) {
        const at = low.indexOf(lower, from)
        if (at < 0) break
        const r = document.createRange()
        r.setStart(node, at)
        r.setEnd(node, at + q.length)
        all.push(r)
        if (target) target.push(r)
        from = at + q.length
      }
    }
    return { all, cur }
  }

  const paintHighlight = () => {
    clearHighlight()
    const q = searchQuery.value.trim()
    if (!q) return
    if (!window.CSS?.highlights || typeof window.Highlight !== 'function') return // 不支持：只定位不标黄
    try {
      const list = matchedIdxs.value
      const curMsgIdx = list.length ? list[Math.min(searchPos.value, list.length - 1)] : null
      const { all, cur } = collectRanges(q, curMsgIdx)
      if (all.length) CSS.highlights.set('chat-search', new Highlight(...all))
      if (cur.length) CSS.highlights.set('chat-search-current', new Highlight(...cur))
    } catch (e) { /* Range 失效（渲染中）忽略，下次输入重算 */ }
  }

  const scrollToMatch = () => {
    const list = matchedIdxs.value
    if (!list.length) return
    const mi = list[Math.min(searchPos.value, list.length - 1)]
    rowOf(mi)?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }

  const gotoMatch = delta => {
    const list = matchedIdxs.value
    if (!list.length) return
    searchPos.value = (searchPos.value + delta + list.length) % list.length
    paintHighlight() // 当前项变了：重绘（当前项用更醒目的颜色）
    scrollToMatch()
  }

  const openSearch = () => {
    if (!rows.value.length) { message.info('当前会话还没有消息'); return false }
    searchOpen.value = true
    nextTick(() => searchInputRef.value?.focus?.())
    return true
  }

  const closeSearch = () => {
    searchOpen.value = false
    searchQuery.value = ''
    searchPos.value = 0
    clearHighlight()
  }

  // 关键词变化 → 回到第一个匹配并重绘高亮（消息渲染是异步的，等一帧再画）
  watch(searchQuery, () => {
    searchPos.value = 0
    if (!searchOpen.value) return
    nextTick(() => { paintHighlight(); scrollToMatch() })
  })
  return {
    searchOpen, searchQuery, searchPos, searchInputRef,
    matchedIdxs, searchPosShown,
    openSearch, closeSearch, gotoMatch, paintHighlight, scrollToMatch, clearHighlight, rowOf
  }
}
