// 计划模式动作（从 useChatEngine 抽出）：计划裁决（批准/取消/改稿）、旧轮收尾等待、
// 「继续对话」重做计划、批准后调整（插话/停止并重新计划）。依赖全部经入参注入，
// streamAnswer 以函数包装传入（它在本模块装配点之后才定义，惰性调用不触发 TDZ）。
import { reactive } from 'vue'
import { submitPlanApproval as submitPlanApprovalImpl, revisePlanInPlace as revisePlanInPlaceImpl,
         steerPlanAdjustment as steerPlanAdjustmentImpl, adjustPlanAfterApproval as adjustPlanAfterApprovalImpl,
         replanAfterApproval as replanAfterApprovalImpl } from './planActions.js'

export const usePlanActions = ({
  currentSessionId, messages, loading, hooks, deepThinkOn, effectiveModel, goalRunning, chatStreams,
  steer, stop, streamAnswer, notify, resolvePlanApproval, revisePlanApproval, supersedePlanApproval,
  emitNotifChanged, stopRestoredPoll, pollRestoredAnswer, switchSession, enqueuePlanAdjust,
  planExecProgressOf, setPlanReplanned, planVersionOf, planStepCount,
}) => {
  // 计划裁决动作在 ./planActions.js（actions 层）。
  const planCtx = () => ({
    resolveApi: resolvePlanApproval, reviseApi: revisePlanApproval, notify, emitNotifChanged,
    stopRestoredPoll: () => stopRestoredPoll(), pollRestoredAnswer: sid => pollRestoredAnswer(sid),
    switchSession: sid => switchSession(sid), currentSid: () => currentSessionId.value,
    scrollSoft: () => hooks.scrollSoft?.()
  })
  const submitPlanApproval = (m, action) => submitPlanApprovalImpl(m, action, planCtx())
  const revisePlanInPlace = (m, text) => revisePlanInPlaceImpl(m, text, planCtx())

  /** 旧轮收尾等待：后端先落「取代」收尾消息、前端再把意见作为新消息发出（落库顺序不乱）。
   *  恢复态卡没有本轮的流可等，只给后端落库留一小段时间 */
  const planRoundSettled = m => new Promise(resolve => {
    if (!m || m.restored) { setTimeout(resolve, 800); return }
    if (!m.loading) { resolve(); return }
    const t0 = Date.now()
    const tick = () => { if (!m.loading || Date.now() - t0 > 5000) resolve(); else setTimeout(tick, 150) }
    tick()
  })

  /**
   * 计划「继续对话」（计划模式）：用户对当前计划不满意，把输入框里的意见作为一条正式用户消息发出——
   * 本版计划先按「已被取代」作废（supersede），随后这条消息在**同一会话里继续对话**：完整新一轮
   * （重新检索），模型带着上一版计划按意见产出修改后的计划卡。可反复，直到点「确认」才执行。
   */
  async function continuePlanConversation (m, text) {
    const c = m && m.planCard
    if (!c || c.busy || c.status !== 'pending') return
    const q = String(text || '').trim()
    if (!q) { notify.warning('先写下要改什么，再点「继续对话」'); return }
    c.busy = true
    try {
      const r = await supersedePlanApproval(c.id, q)
      if (r && r.success === false) { notify.warning(r.msg || '提交失败'); c.busy = false; return }
      emitNotifChanged('chat')
    } catch (e) {
      notify.error(e.message || '提交失败')
      c.busy = false
      return
    }
    // 本地立刻定格「已被取代」，不等 plan_superseded 事件：这一版作废已是既成事实，而等事件的这段
    // 里卡片停在 busy=true——确认/取消/输入框整片灰、再点「继续对话」被开头那道判据静默 return，
    // 恢复态卡片更是没有本轮的流可等（第二次继续对话整个确认栏会冻死）。
    c.status = 'superseded'
    c.busy = false
    c.editText = ''
    m.planSuperseded = true
    await planRoundSettled(m)
    // 旧轮已收尾（或恢复态给了落库时间）：把意见作为用户消息发出，继续同一会话的对话
    const userMsg = reactive({ role: 'user', content: q, images: [], attachments: [], attachData: [],
                              skills: [], mentions: [], historyRefs: [], deepThink: deepThinkOn.value,
                              planMode: true, time: Date.now(), messageId: null })
    messages.value.push(userMsg)
    // 强制计划模式：本轮的目的就是按意见重做计划（后端据此再走计划闸门）
    streamAnswer(q, [], null, messages.value.length === 1, 1, deepThinkOn.value, [], [], [], null, [], '', userMsg, true)
  }
  // 插话调整在 ./planActions.js（actions 层）；重新计划仍在本文件（依赖停止/收尾/开新轮）。
  const steerPlanAdjustment = (m, raw) => steerPlanAdjustmentImpl(m, raw,
    { messages: () => messages.value, loading: () => loading.value, steer: q => steer(q) })

  /**
   * 批准后调整：停止并重新计划。控制流在 ./planActions.js（actions 层），这里只提供引擎侧依赖。
   */
  const replanAfterApproval = (m, raw) => replanAfterApprovalImpl(m, raw, {
    messages: () => messages.value,
    sid: () => currentSessionId.value,
    model: () => effectiveModel.value,
    loading: () => loading.value,
    progressOf: planExecProgressOf,
    hasLocalStream: sid => chatStreams.has(sid),
    stop: () => stop(),
    settle: mm => planRoundSettled(mm),
    markReplanned: setPlanReplanned,
    versionOf: planVersionOf,
    stepCount: planStepCount,
    launch: (q, from) => {
      const userMsg = reactive({
        role: 'user', content: q, images: [], attachments: [], attachData: [],
        skills: [], mentions: [], historyRefs: [], deepThink: deepThinkOn.value,
        planMode: true, planAdjustFrom: from, time: Date.now(), messageId: null
      })
      messages.value.push(userMsg)
      // 强制计划模式：本轮的目就是按意见重做计划；planBaseId 交给服务端拼血缘与上一版上下文
      streamAnswer(q, [], null, messages.value.length === 1, 1, deepThinkOn.value, [], [], [], null, [], '',
                   userMsg, true, false, false, { planBaseId: from.cardId })
    }
  })

  const adjustPlanAfterApproval = (m, text, mode) => adjustPlanAfterApprovalImpl(m, text, mode,
    { replan: (mm, t) => replanAfterApproval(mm, t), messages: () => messages.value, loading: () => loading.value, steer: q => steer(q),
      // 送不进去时的落点：只有目标还在跑（深度执行会自己起下一轮）才值得排队；否则把原因交回面板
      queuePlanAdjust: t => goalRunning.value
        ? enqueuePlanAdjust(t, currentSessionId.value) : null })
  return { submitPlanApproval, revisePlanInPlace, continuePlanConversation, steerPlanAdjustment,
           replanAfterApproval, adjustPlanAfterApproval }
}
