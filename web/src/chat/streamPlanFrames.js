// 计划卡实时帧（纯函数 + 注入）：草稿增量、批准终稿、被拒/取代收束。
// 卡片对象挂在 msg.planCard 上；血缘回链与滚动由调用侧注入。
import { planText } from './planText.js'
import { planVersionOf } from './plan.js'
import { restoreAbortedDraft } from './planAbort.js'

const ensureCard = msg => {
  if (!msg.planCard) msg.planCard = { plan: '', status: 'drafting', busy: false, editText: '', versions: [], feedback: '' }
  return msg.planCard
}

export const applyPlanDelta = (msg, t, { userMsg = null, onScroll = () => {} } = {}) => {
  // 计划模式：计划正文流式增量进计划卡（终版以 plan_approval 下发的落库稿为准）
  const c = ensureCard(msg)
  // 重新计划：草稿一出现就带来源，卡头「调整自 vN」不用等批准才有
  if (userMsg && userMsg.planAdjustFrom && !c.adjustFrom) {
    c.adjustFrom = { ...userMsg.planAdjustFrom }
  }
  // 退回重出（revise）：第一份新稿增量到达时才归档旧版——此前提交失败仍能回到原稿；
  // feedback 归属「产出它的那条意见」（后端 versions 同一口径）：旧版带走旧 feedback，
  // 新意见立即挂到正在生成的新版上，抽屉里逐版读得出为什么改。
  if (c.revising && !c.revisingStarted) {
    if (planText(c.plan)) c.versions.push({ plan: c.plan, feedback: c.feedback || '' })
    c.feedback = c.pendingFeedback || ''
    c.plan = ''
    c.revisingStarted = true
  }
  if (c.status !== 'drafting' && !(c.revising && c.revisingStarted)) return
  if (c.revising && c.revisingStarted) c.status = 'drafting'
  c.plan += t
  onScroll()
}

export const applyPlanApproval = (msg, raw, { userMsg = null, messages = [], onScroll = () => {} } = {}) => {
  // 计划生成完毕：卡片转「待确认」，挂上裁决句柄与倒计时；确认/继续对话/取消见 submitPlanApproval 一族
  try {
    const j = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (!j || !j.planApprovalId) return
    const c = ensureCard(msg)
    // 没有增量、直接出终稿：这里补一次旧版归档（旧版带走它自己的 feedback，新意见挂到新版）
    if (c.revising && !c.revisingStarted && planText(c.plan)) {
      c.versions.push({ plan: c.plan, feedback: c.feedback || '' })
      c.feedback = c.pendingFeedback || ''
      c.plan = ''
      c.revisingStarted = true
    }
    c.id = j.planApprovalId
    // 重新计划：来源优先取本轮的 userMsg；断线接流/换设备时 userMsg 不存在，
    // 改从 plan_approval 帧自带的血缘字段恢复（后端同一份数据，两个入口不各写一套）
    const frameFrom = (typeof j.planBaseId === 'string' && j.planBaseId)
      ? {
          cardId: j.planBaseId,
          version: Number(j.planBaseVersion) || 1,
          done: Number(j.planBaseDone) || 0,
          total: Number(j.planBaseTotal) || 0,
          failed: Number(j.planBaseFailed) || 0
        }
      : null
    const applyFrom = (userMsg && userMsg.planAdjustFrom) || c.adjustFrom || frameFrom
    if (applyFrom && !c.adjustFrom) c.adjustFrom = { ...applyFrom }
    if (c.adjustFrom && c.adjustFrom.cardId && c.id) {
      const old = messages.find(x => x && x.planCard && x.planCard.id === c.adjustFrom.cardId)
      if (old && !old.planCard.replannedBy) {
        old.planCard.replannedBy = { cardId: c.id, version: planVersionOf(c) }
      }
    }
    // 后端权威稿（已剥标签/护栏截断）覆盖流式累积；过 planText 归一化，
    // 非字符串直接透传会让卡片渲染成 [object Object]
    c.plan = planText(j.plan) || c.plan
    if (c.revising) {
      // 这一版对应的意见写进版本链（当前版 feedback；不改 feedback 会串到旧版说明上）
      c.feedback = c.pendingFeedback || ''
      c.revising = false
      c.revisingStarted = false
      c.pendingFeedback = ''
    }
    c.timeoutMs = Number(j.timeoutMs) || 0
    c.deadline = c.timeoutMs > 0 ? Date.now() + c.timeoutMs : 0
    c.status = 'pending'
    c.busy = false                   // 解锁裁决控件（防御：极端情况下残留的 busy 不卡死面板）
    c.auto = !!j.auto                // 按消息意图自动开启（卡片标「自动开启」）
    onScroll()
  } catch (e) { /* 忽略 */ }
}

const settleCard = msg => {
  if (!msg.planCard) return
  if (msg.planCard.revising && msg.planCard.revisingStarted) restoreAbortedDraft(msg.planCard)
  msg.planCard.busy = false
  msg.planCard.revising = false
  msg.planCard.revisingStarted = false
  msg.planCard.pendingFeedback = ''
}

export const applyPlanCancelled = (msg, { onScroll = () => {} } = {}) => {
  // 计划被拒/超时：卡片定格为「未批准」，整轮就此收束（后端不再发回答，done 也无正文）
  if (msg.planCard) {
    // 退回重出的新稿没写完就整轮收束：恢复上一份完整稿，别把半截内容当终版
    settleCard(msg)
    msg.planCard.status = 'rejected'
  }
  msg.planStopped = true
  onScroll()
}

export const applyPlanSuperseded = (msg, { onScroll = () => {} } = {}) => {
  // 用户对计划提出修改、继续对话：本版定格「已被取代」，整轮就此收束（新一轮将按意见重做计划）
  if (msg.planCard) {
    settleCard(msg)
    msg.planCard.status = 'superseded'
  }
  msg.planSuperseded = true
  onScroll()
}
