// 计划卡裁决动作（actions 层）：批准/拒绝、同一轮退回重出。
export const submitPlanApproval = async (m, action, ctx) => {
  const c = m && m.planCard
  if (!c || c.busy || c.status !== 'pending') return
  const approved = action === 'approve'
  c.busy = true
  try {
    const r = await ctx.resolveApi(c.id, approved)
    if (r && r.success === false) {
      ctx.notify.warning(r.msg || '提交失败')
      c.busy = false
      // 恢复态卡：本轮已无人接收，撤下并重载
      if (c.restored) { m.planCard = null; ctx.stopRestoredPoll(); ctx.switchSession(ctx.currentSid()) }
      return
    }
    ctx.emitNotifChanged('chat')
    if (approved) {
      c.status = 'approved'
      if (c.restored) {
        m.planCard = null
        ctx.pollRestoredAnswer(ctx.currentSid())
        return
      }
    } else {
      c.status = 'rejected'
      if (c.restored) { m.planCard = null; ctx.stopRestoredPoll(); ctx.switchSession(ctx.currentSid()) }
    }
    ctx.scrollSoft()
  } catch (e) {
    ctx.notify.error(e.message || '提交失败')
    if (m.planCard) m.planCard.busy = false
  }
}

/** 同一轮退回重出：意见不进消息流，卡片原地升版；恢复态没有本设备流，改走转到对话修改。 */
export const revisePlanInPlace = async (m, text, ctx) => {
  const c = m && m.planCard
  if (!c || c.busy || c.status !== 'pending') return false
  if (c.restored) { ctx.notify.warning('这张计划卡是恢复的，修改请用「转到对话修改」'); return false }
  const q = String(text || '').trim()
  if (!q) { ctx.notify.warning('先写下要改什么，再提交修改'); return false }
  c.busy = true
  c.pendingFeedback = q
  try {
    const r = await ctx.reviseApi(c.id, q)
    if (r && r.success === false) {
      ctx.notify.warning(r.msg || '提交失败')
      c.busy = false
      c.pendingFeedback = ''
      return false
    }
    ctx.emitNotifChanged('chat')
    c.revising = true
    c.revisingStarted = false
    c.deadline = 0
    ctx.scrollSoft()
    return true
  } catch (e) {
    ctx.notify.error(e.message || '提交失败')
    c.busy = false
    c.pendingFeedback = ''
    return false
  }
}

/** 批准后插话调整：复用 steer 链路；失败不回填主输入框（面板保留原文）。 */
export const steerPlanAdjustment = async (m, raw, ctx) => {
  const q = String(raw || '').trim()
  if (!q) return { ok: false, reason: 'empty' }
  if (!m || !m.planCard || m.planCard.status !== 'approved') return { ok: false, reason: 'state' }
  if (!ctx.messages().includes(m)) return { ok: false, reason: 'state' }
  // 另一轮在跑（不是这张卡所属的那一轮）：不能往里插话，也不能装作已送达
  if (ctx.loading() && !m.loading) return { ok: false, reason: 'busy' }
  if (!m.loading) return { ok: false, reason: 'ended' }
  const id = await ctx.steer(q)
  if (!id) return { ok: false, reason: 'ended' }
  return { ok: true }
}

/** 面板统一入口：A 插话调整 / B 停止并重新计划。 */
export const adjustPlanAfterApproval = (m, text, mode, ctx) =>
  mode === 'replan' ? ctx.replan(m, text) : steerPlanAdjustment(m, text, ctx)

/**
 * 批准后调整：停止并重新计划。旧卡冻结当前进度后保留记录，意见作为新一轮问题发出；
 * 新一轮强制计划模式，并带 planBaseId，服务端据此把旧计划与进度拼进提示词、给新卡落血缘。
 * @returns {Promise<{ok:boolean, reason?:string}>}
 */
export const replanAfterApproval = async (m, raw, ctx) => {
  const q = String(raw || '').trim()
  if (!q) return { ok: false, reason: 'empty' }
  const c = m && m.planCard
  if (!c || c.status !== 'approved') return { ok: false, reason: 'state' }
  if (c.restored) return { ok: false, reason: 'restored' }
  if (!ctx.messages().includes(m)) return { ok: false, reason: 'state' }
  const sid = ctx.sid()
  if (!sid) return { ok: false, reason: 'state' }
  if (!ctx.model()) return { ok: false, reason: 'model' }
  // 同一会话同时只能跑一轮：别把「另一轮在跑」当成可停止的那一轮
  if (ctx.loading() && !m.loading) return { ok: false, reason: 'busy' }

  // 1. 冻结旧卡进度：新一轮一写 todo，实时清单就不属于旧轮了；先留一份，界面在历史清单到达前也不闪空
  const prog = ctx.progressOf(m)
  if (prog) {
    c.execSnapshot = {
      items: prog.items.map(it => ({ text: it.text, status: it.status })),
      done: prog.done,
      total: prog.total,
      failed: prog.failed
    }
  }

  // 2. 执行中的轮次：等「本地收尾 + 服务端确认停止」两件事都完成，再发新一轮（否则会撞会话互斥）
  if (m.loading) {
    if (ctx.hasLocalStream(sid)) {
      const serverAnswered = await ctx.stop()
      await ctx.settle(m)
      if (!serverAnswered || m.loading) return { ok: false, reason: 'stop-failed' }
    } else {
      await ctx.settle(m)
      if (m.loading) return { ok: false, reason: 'stop-failed' }
    }
  }

  // 3. 旧卡定格「已重新规划」：不是作废，执行记录与产物都留着。
  // 同时写一条本机标记：新计划落库前刷新/换页，旧卡也不会退回「已批准」并重新露出调整入口
  c.replannedAt = Date.now()
  if (c.id) ctx.markReplanned(sid, c.id, c.replannedAt)
  const snap = c.execSnapshot
  const from = {
    cardId: c.id || '',
    version: ctx.versionOf(c),
    done: snap ? snap.done : 0,
    total: snap ? snap.total : (ctx.stepCount(c.plan) || 0),
    failed: snap ? snap.failed : 0
  }
  ctx.launch(q, from)
  return { ok: true }
}
