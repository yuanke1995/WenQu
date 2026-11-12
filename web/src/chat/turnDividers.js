// 回答/提问上方的分隔线判据：模型切换、上下文压缩（持久标记与流式实时两档）。
export const createTurnDividers = ({ modelIndexOf, messages, isLive, liveCompactedSid, currentSid }) => {
  /** 模型引用 → 展示名（模型库友好名优先，查不到回退原始引用 provider/model） */
  /**
   * 模型的展示名。模型库里查不到时（模型被移出列表、所在供应商已停用）退化成引用串里的模型名那一段——
   * 直接把 `供应商UUID/deepseek-v4-pro` 抛到界面上，用户读到的是「这是什么」。
   */
  const modelLabelOf = ref => {
    if (!ref) return ''
    const hit = modelIndexOf()[ref]?.displayName
    if (hit) return hit
    const i = ref.indexOf('/')
    return i === -1 ? ref : ref.slice(i + 1)
  }
  /**
   * 「模型已切换」分隔记录：本轮回答与上一条助手消息的落库模型**展示名**不同时，在本条回答上方挂一条居中分隔。
   * 只在发消息产生新回答时出现（切换选择器本身不产生任何记录）；两侧都有模型引用才比对——
   * 旧消息/工作流轮没有 model，无法判定是否切换，不渲染。返回 {fromLabel,toLabel} 或 null。
   * <p>比展示名而不是比落库原串：同一个模型在库里存过两种写法（早期是「供应商/模型名」整条引用，
   * 现在只存模型名），拿原串比会把没换模型报成换了；何况展示名一样时「模型已切换 X → X」这句话本身就是假的。
   */
  const modelSwitchInfo = (m, i) => {
    if (m.role !== 'ai' || !m.model) return null
    const toLabel = modelLabelOf(m.model)
    const list = messages()
    for (let k = i - 1; k >= 0; k--) {
      const prev = list[k]
      if (prev.role !== 'ai') continue
      if (!prev.model) return null
      const fromLabel = modelLabelOf(prev.model)
      return fromLabel === toLabel ? null : { fromLabel, toLabel }
    }
    return null
  }
  /**
   * 「已压缩上下文」分隔记录：这一轮回答落库的 tokens 带着 {@code compacted} 标记
   * （框架在这一轮里自动压过一次上下文）→ 分隔线画在这条提问<b>上方</b>，读作「压缩发生在这个缝上」。
   * <p>标记由后端在收尾时随消息行落库，刷新后这条线仍在同一个缝上。
   * <p>手动 /compact 不产生消息行，它当场那条线走 {@code compactNotice}（挂在流尾，同一个缝）。
   */
  const compactDividerAt = (m, i) => {
    if (m.role !== 'user') return false
    const list = messages()
    for (let k = i + 1; k < list.length; k++) {
      const cur = list[k]
      if (cur.role === 'user') continue
      return !!cur.tokens?.compacted
    }
    return false
  }
  /**
   * 正在跑的这一轮：压缩<b>已经压完</b>、但这条提问的分隔线还要等本轮收场才由 {@link compactDividerAt}
   * 接管（收尾那一刻才拿到 tokens 那一份，见 onDone 里补的 compacted）。没有这一档，从压完到答完
   * 那几分钟界面上没有任何压缩留痕，用户读成刚才那下没压。
   * <p>认的是「本轮最后一条提问」：与持久判据同一个位子，交接时线不跳。
   */
  const liveCompactAt = (m, i) => {
    if (m.role !== 'user' || !isLive()) return false
    if (liveCompactedSid() !== currentSid()) return false
    const list = messages()
    for (let k = i + 1; k < list.length; k++) {
      if (list[k].role === 'user') return false
    }
    return true
  }
  return { modelLabelOf, modelSwitchInfo, compactDividerAt, liveCompactAt }
}
