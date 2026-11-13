// 工具执行审批动作（actions 层）：批准/拒绝，后端以结果回给模型继续回答。
// scope='session' = 本次会话都允许（同一工具后续不再弹卡），其余情况只批这一步。
export const resolveApproval = async (m, card, approved, ctx, scope) => {
  // 多卡：调用方传具体那张；兼容只传 (m, approved) 的旧调用点（取第一张）
  const a = card && card.id ? card : (m && (m.approvals || [])[0])
  if (!a || a.busy) return
  // 倒计时已到点：后端马上按拒绝收尾，不再发必然失败的请求
  if (a.deadline && ctx.approvalLeftSec(a) <= 0) {
    ctx.notify.warning('这个审批已超时，本轮将按拒绝继续')
    return
  }
  a.busy = true
  // 恢复态卡：本设备没有这一轮的流，批准后后台生成，轮询到落库再重载
  const background = !!a.restored && !(ctx.chatStreams.get(ctx.currentSid())?.msg === m)
  try {
    const r = await ctx.approveApi(a.id, approved, scope)
    if (r && r.success === false) {
      ctx.notify.warning(r.msg || '审批提交失败')
      a.busy = false
      return
    }
    if (approved && scope === 'session') {
      ctx.notify.success(`已设为本次会话都允许：「${a.label || a.tool}」后续不再询问`)
    }
    ctx.emitNotifChanged('chat')
    if (m && Array.isArray(m.approvals)) m.approvals = m.approvals.filter(x => x.id !== a.id)
    if (background) ctx.pollRestoredAnswer(ctx.currentSid())
    else ctx.scrollSoft()
  } catch (e) {
    ctx.notify.error(e.message || '审批提交失败')
    a.busy = false
  }
}
