// 计划卡的中断收尾（纯函数）：被停止 / 断流 / 报错时把没走完的卡落到终态。
// 不处理的话卡片会永远停在「生成中…」：pendingPlan 认 drafting/revising，拍板栏与输入框
// 一起被锁死，用户除了刷新没有出口。

/**
 * 退回重出（revise）中途被打断：丢掉半截新稿，把上一份完整稿恢复为当前版
 * （版本链回到原长度）。半截稿既不能批准、也不该当成一版给人看。
 */
export const restoreAbortedDraft = c => {
  const vs = Array.isArray(c.versions) ? c.versions : []
  const prev = vs.length ? vs[vs.length - 1] : null
  if (prev) {
    c.plan = prev.plan || ''
    c.feedback = prev.feedback || ''
    vs.pop()
  }
}

/**
 * 本轮结束时收掉一张没走完的计划卡（被停止 / 断流 / 报错）。
 * <p>退回重出（revise）尤其危险——半截新稿既不能批准（旧裁决已被 revise 消费掉），
 * 也不该当成一版给人看，所以恢复上一份完整稿、卡片落「已停止」。
 * <p>`!revisingStarted` 的那一支不在这里收：那是「意见刚提交、新稿一个字还没来」，原稿还完整待确认。
 * @returns 是否收掉了这张卡
 */
export const settleAbortedPlan = msg => {
  const c = msg && msg.planCard
  if (!c) return false
  if (c.revising && c.revisingStarted) {
    restoreAbortedDraft(c)
    c.status = 'stopped'
    c.revising = false
    c.revisingStarted = false
    c.pendingFeedback = ''
    c.busy = false
    msg.planStopped = true
    return true
  }
  if (c.status === 'drafting' || c.status === 'pending') {
    // 首版计划没写完 / 还挂着待确认就整轮结束（超时、被停）：卡必须落终态，
    // 否则新页签接流留下的待确认卡会永远停在那里。
    c.status = 'stopped'
    c.busy = false
    c.revising = false
    c.revisingStarted = false
    c.pendingFeedback = ''
    msg.planStopped = true
    return true
  }
  return false
}
