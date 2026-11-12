// 流失败收尾（纯函数 + 注入）：保留已流出的半程内容，挂独立错误卡，收掉各类进行中卡片。
// 自动重试与接流重连的分支不在这里（它们要重新开流，属于传输层控制流）。

/**
 * @param msg 本轮回答消息
 * @param ctx.error 原始异常（错误卡正文）
 * @param ctx.kind  分类（interrupted=断线/冻结；其它=业务错误）
 * @param ctx.flushNow 把节流缓冲落屏（保住半程正文与时间线）
 * @param ctx.settlePreparingCards 早产工具卡收场
 * @param ctx.settleAbortedPlan 计划卡中断收尾
 * @param ctx.dropStream 从在跑登记里摘掉本轮（同一条流才摘）
 * @param ctx.notify 业务错误且本轮不在眼前时的 toast
 * @param ctx.viewing 本轮是否正在眼前
 * @param ctx.scroll 收尾后贴底/保位
 */
export const failTurn = (msg, { error, kind, flushNow, settlePreparingCards, settleAbortedPlan,
                               dropStream, notify, viewing, scroll }) => {
  // 错误不再整体替换正文：flushNow 保留已流出的半程内容与时间线，挂独立错误卡
  // （分类文案 + 重新生成 + 异常详情折叠，对齐主流产品的失败态；不把异常写进气泡，
  //  避免冲掉半程内容、长回答生成到 90% 失败时全部丢失）
  flushNow()
  settlePreparingCards(msg, '本轮中断，这一步没有执行')
  msg.loading = false
  msg.retrying = false
  // 这一轮已经断了，审批卡不会再有结果：撤掉，别留两颗必然点不动的按钮
  msg.approvals = []
  // 计划卡同理：断流/报错不会再有 plan_approval，卡不能停在「生成中」把输入框锁死
  settleAbortedPlan(msg)
  if (msg.planCard) {
    msg.planCard.busy = false
    if (msg.planCard.revising && !msg.planCard.revisingStarted) {
      msg.planCard.revising = false
      msg.planCard.pendingFeedback = ''
    }
  }
  // 本轮失败：正在长的那一段思考必须停下进行中标记（它认的是「这一轮还在流」，
  // 而 loading 已假 → 该段自然收起），否则那一行会永远转下去，把「这一轮断了」读成「它还在想」
  msg.thinkLoading = false
  msg.failed = true
  msg.errorCard = { message: String(error), kind: kind || '', at: Date.now() }
  dropStream()
  // 中断类（断线/切后台冻结/连接被掐断）不弹 toast：这类几乎都是移动网络状态问题，
  // toast 会盖住用户真正要点的「重新生成」按钮，而错误卡已经把话说清楚了。
  // 业务错误只在<b>本轮不在眼前</b>时弹：错误卡说的就是同一句话，同屏弹两份是重复；
  // 切到别的会话时那一屏看不见卡，那时不弹才是真消音。
  if (kind !== 'interrupted' && !viewing()) notify(error)
  // 已上翻看历史的会话原地停留（错误卡与「重新生成」在回答末尾，回到底部按钮足够引导）
  scroll()
}
