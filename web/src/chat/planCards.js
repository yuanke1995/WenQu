// 计划卡的构建与血缘补齐（纯函数，从 useChatEngine 抽出）。
// planCardFromRecord：把一条落库的计划记录重建为前端用的 planCard 对象（历史恢复/接流快照都读它）；
// linkPlanLineage：历史计划血缘——新卡记 planBaseId、旧卡无反向字段，载入后按 id 双向补齐。
import { planText } from './planText.js'
import { planVersionOf } from './plan'
import { planReplannedAtOf } from './planUiState'

export const planCardFromRecord = (rec, statusOverride, sid = '') => {
  const all = (rec && Array.isArray(rec.versions) ? rec.versions : []).filter(v => v && planText(v.plan))
  const card = {
    plan: '', status: statusOverride || defaultStatus(rec),
    busy: false, editText: '', versions: [], feedback: '',
    auto: !!(rec && rec.auto),
    id: (rec && typeof rec.id === 'string') ? rec.id : '',
    adjustFrom: adjustFromOf(rec)
  }
  fillBody(card, rec, all)
  // 本机「重新计划」标记：新计划落库前刷新，旧卡也保持「已重新规划」而不是退回「已批准」
  if (sid && card.id && card.status === 'approved') {
    const at = planReplannedAtOf(sid, card.id)
    if (at) card.replannedAt = at
  }
  return card
}

/** 状态：记录是 rejected/superseded 才取它，其余一律按 approved（历史回显）。 */
function defaultStatus (rec) {
  return rec && (rec.status === 'rejected' || rec.status === 'superseded') ? rec.status : 'approved'
}

/** 血缘：旧记录没有 planBaseId → null（界面按无血缘处理）。 */
function adjustFromOf (rec) {
  if (!(rec && typeof rec.planBaseId === 'string' && rec.planBaseId)) return null
  return {
    cardId: rec.planBaseId,
    version: Number(rec.planBaseVersion) || 1,
    done: Number(rec.planBaseDone) || 0,
    total: Number(rec.planBaseTotal) || 0,
    failed: Number(rec.planBaseFailed) || 0
  }
}

/** 正文与版本链：有多版时最后一版是当前稿、前面的进 versions；否则用 rec.plan。 */
function fillBody (card, rec, all) {
  if (all.length) {
    const last = all[all.length - 1]
    card.plan = planText(last.plan)
    card.feedback = last.feedback || ''
    card.versions = all.slice(0, -1).map(v => ({ plan: planText(v.plan), feedback: v.feedback || '' }))
  } else if (rec) {
    card.plan = planText(rec.plan)
  }
}

/**
 * 历史计划血缘：新卡记着 planBaseId，旧卡没有反向字段；载入后按 id 双向补齐，
 * 让「查看新一轮」在刷新/换设备后仍可点。缺 id 的存量卡不连——宁可不显示，也不猜错关系。
 */
export function linkPlanLineage (list) {
  if (!Array.isArray(list)) return
  const byId = new Map()
  for (const m of list) {
    const id = m && m.planCard && m.planCard.id
    if (id) byId.set(id, m.planCard)
  }
  for (const m of list) {
    const c = m && m.planCard
    const from = c && c.adjustFrom
    if (!c || !from || !from.cardId || !c.id) continue
    const base = byId.get(from.cardId)
    if (base && !base.replannedBy) {
      base.replannedBy = { cardId: c.id, version: planVersionOf(c) }
    }
  }
}
