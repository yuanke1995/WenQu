// 计划折叠态、完整计划抽屉与「批准后调整」面板的纯判据：文案截断、消息反查、
// 进度投影、失败文案、准入与键盘意图。视图状态与引擎调用在 usePlanPanels.js，依赖以参数注入。

/** 历史问答引用气泡的 chip 文案：压平空白，空内容给占位，超过 20 字截断。 */
export const historyRefPreviewText = hr => {
  const plain = String((hr && hr.content) || '').replace(/\s+/g, ' ').trim()
  if (!plain) return '历史问答'
  return plain.length > 20 ? plain.slice(0, 20) + '…' : plain
}

/** 空态引导用的本轮 @ 资料名：优先取展示名，缺失时回退 id。 */
export const mentionNamesOf = mentions => (mentions || []).map(m => m.name || m.id)

/** 计划折叠头的状态文案：待确认/生成中优先，其余交给引擎状态文案。 */
export const planHeadTextOf = (list, planStateText) => {
  const plans = list || []
  if (plans.some(p => p.status === 'pending')) return '待确认'
  if (plans.some(p => p.status === 'drafting')) return '生成中'
  return plans[0] ? planStateText(plans[0].status) : ''
}

/** 计划卡自动展开：仅在状态串真的变化、且出现待确认/生成中时顶开，不反复弹回。 */
export const shouldAutoExpandPlan = (cur, prev) => cur !== prev && /pending|drafting/.test(cur)

/** 计划标题：取该计划之前最近一条用户提问的第一行，截断到 60 字；找不到用会话标题。 */
export const planTaskTitle = (messages, i, fallbackTitle) => {
  const list = messages || []
  for (let j = Math.min(i, list.length) - 1; j >= 0; j--) {
    const m = list[j]
    if (m && m.role === 'user' && String(m.content || '').trim()) {
      const line = String(m.content).trim().split('\n')[0]
      return line.length > 60 ? line.slice(0, 60) + '…' : line
    }
  }
  return fallbackTitle || ''
}

/** 抽屉挂在哪条消息：按计划卡对象反查；卡缺失或会话已切换即失效。 */
export const planViewerMessageOf = (messages, card, sid, currentSid) => {
  if (!card || sid !== currentSid) return null
  return (messages || []).find(m => m && m.planCard === card) || null
}

/** 批准后调整面板的初始进度：投影执行清单的计数与「进行中」步骤标签。 */
export const projectPlanProgress = prog => {
  const items = (prog && prog.items) || []
  const doingAt = items.findIndex(it => it.status === 'doing')
  return {
    done: prog ? prog.done : 0,
    doing: doingAt >= 0 ? 1 : 0,
    total: prog ? prog.total : 0,
    failed: prog ? prog.failed : 0,
    stepLabel: doingAt >= 0 ? `第 ${doingAt + 1} 步「${items[doingAt].text}」` : ''
  }
}

/** 可否打开「调整计划」：仅已批准、非恢复、且尚未被重新计划取代的卡。 */
export const canOpenPlanAdjust = card =>
  !!card && card.status === 'approved' && !card.restored && !card.replannedBy && !card.replannedAt

/** 提交失败的面板文案：按引擎给的原因给可执行的下一步；本轮刚结束才建议改用重新计划。 */
export const adjustFailureText = reason => {
  const error = reason === 'ended'
    ? '这一轮刚好结束了。要改计划，请改用「停止并重新计划」。'
    : reason === 'stop-failed'
      ? '这一轮没停下来，先不要重新计划。可以稍后再试。'
      : reason === 'busy'
        ? '还有一轮在跑，等它结束再调整。'
        : reason === 'restored'
          ? '这张计划是恢复的，请回到发起它的设备调整。'
          : reason === 'model'
            ? '还没有可用模型，请先在右上角选择模型。'
            : reason === 'empty'
              ? '先写下要改什么。'
              : '提交失败，请重试。'
  return { error, suggestReplan: reason === 'ended' }
}

/** 计划意见框回车意图：输入法组合、卡不可用或过期、修饰键、空文本都不提交；
 *  恢复态返回 restored，供调用方改走「转到对话修改」。 */
export const planPanelKeydownIntent = (e, card, expired) => {
  if (e.isComposing || e.keyCode === 229) return null
  if (!card || card.busy || expired) return null
  if (e.key !== 'Enter' || e.shiftKey || e.ctrlKey || e.metaKey || e.altKey) return null
  const text = String(card.editText || '').trim()
  if (!text) return null
  return { text, restored: !!card.restored }
}
