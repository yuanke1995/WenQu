/* 定高内滚容器的贴底跟随（深度思考/过程独白正文用）：那些块有 320px 高度帽，模型还在长时
   新句子全落在帽沿以下，不贴底就等于用户看到的永远是开头几行、要自己滚。值传「这一段是否正在长」：
   不长的段不接管（历史段展开后停在顶部，从头读）。用户往上滚回去读前面即停跟随，滚回底部自动续上。
   ⚠️ 段落行是按序号 key 的，同一个 DOM 节点可能先后代表不同段，故接管/交棒时都要复位贴底态。 */
const FOLLOW_BOTTOM_MARGIN = 24

export const vFollowBottom = {
  mounted(el, binding) {
    el._stick = true
    el._followOn = binding.value === true
    el._onScroll = () => {
      el._stick = el.scrollHeight - el.scrollTop - el.clientHeight < FOLLOW_BOTTOM_MARGIN
    }
    el.addEventListener('scroll', el._onScroll, { passive: true })
    if (el._followOn) el.scrollTop = el.scrollHeight
  },
  updated(el, binding) {
    const on = binding.value === true
    // 刚接管这一段（含节点被复用时换段）：贴底态复位，否则上一段留下的「用户已上翻」会静默关掉跟随
    if (on && el._followOn !== true) el._stick = true
    el._followOn = on
    if (on && el._stick) el.scrollTop = el.scrollHeight
  },
  unmounted(el) {
    el.removeEventListener('scroll', el._onScroll)
  }
}
