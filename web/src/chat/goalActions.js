// 目标条动作（actions 层）：pause/resume/edit/cancel/delete 的统一出口。
// 失败先落在目标文档的 actionError 上（条子就在眼前），画不出来时才退回提示层。
export const goalAct = async (action, extra, ctx) => {
  const g = ctx.goal()
  if (!g || !g.goalId) return null
  if (g.busy) return null
  g.busy = true
  g.actionError = ''
  // 会话在 await 前定格：响应回来前用户切走的话，不能把 A 的目标态盖进 B
  const sid = ctx.currentSid()
  try {
    const r = await ctx.action(g.goalId, action, extra || {})
    if (r && r.success === false) {
      g.actionError = r.msg || '操作失败'
      if (!ctx.barVisible()) ctx.notify.warning(g.actionError)
      g.busy = false
      return null
    }
    if (action === 'delete') ctx.setGoal(sid, null)
    else if (r && r.data) ctx.setGoal(sid, { ...r.data })
    // 继续/改目标后仍在跑：接流不等探测那一跳
    if (action === 'resume' || (action === 'edit' && ctx.goalOf(sid)?.status === 'RUNNING')) {
      ctx.watchGoalTurn(sid)
    }
    return r && r.data ? r.data : null
  } catch (e) {
    g.actionError = e.message || '操作失败'
    if (!ctx.barVisible()) ctx.notify.error(g.actionError)
    g.busy = false
    return null
  } finally {
    const cur = ctx.goalOf(sid)
    if (cur) cur.busy = false
  }
}
