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

/** 帽体上下边缘渐隐：内容还能往上/往下滚时给对应边缘压一道 24px 渐隐，滚到边即撤。
 *  跑轮中的簇帽体用（DSH 同款观感）：步骤只增不删，帽内永远停在最新一步，上沿渐隐
 *  就是「上面还有历史步骤」的那一眼可读的暗示。指令只切 ef-top / ef-bot 两个 class，
 *  渐隐本体（mask-image）由页面的样式定义——指令不掺样式，暗色亮色同过一套令牌。 */
export const vEdgeFade = {
  mounted(el) {
    el._efSync = () => {
      const top = el.scrollTop > 1
      const bot = el.scrollHeight - el.scrollTop - el.clientHeight > 1
      if (el._efTop !== top) { el._efTop = top; el.classList.toggle('ef-top', top) }
      if (el._efBot !== bot) { el._efBot = bot; el.classList.toggle('ef-bot', bot) }
    }
    el.addEventListener('scroll', el._efSync, { passive: true })
    el._efSync()
  },
  updated(el) { el._efSync() },
  unmounted(el) { el.removeEventListener('scroll', el._efSync) }
}
