// @ts-check
// 审批卡的秒针与清理（从 useChatEngine 抽出）：
// 只在本轮消息里还有带 deadline 的审批卡时跑 1 秒一跳的 ref，供倒计时显示；
// 并提供「清地址栏 approval 参数」与「撤掉屏上同一张卡」两个收尾动作。
import { ref, watch, onUnmounted } from 'vue'
import { approvalLeftSec } from './approval'

export const useApprovalClock = ({ messages, route, router, hooks }) => {
  const approvalNow = ref(Date.now())
  let approvalTimer = null
  // 有带 deadline 的审批卡才起表，卡撤完即停——不空转
  watch(() => messages.value.some(m => m && Array.isArray(m.approvals)
    && m.approvals.some(a => a && a.deadline)), v => {
    if (v && !approvalTimer) approvalTimer = setInterval(() => { approvalNow.value = Date.now() }, 1000)
    if (!v && approvalTimer) { clearInterval(approvalTimer); approvalTimer = null }
  })
  onUnmounted(() => { if (approvalTimer) { clearInterval(approvalTimer); approvalTimer = null } })
  const approvalLeftSecOf = card => approvalLeftSec(card, approvalNow.value)

  /** 清掉地址栏的 approval 参数（刷新不再把已经处理过/失效的卡再恢复一遍） */
  const clearApprovalQuery = approvalId => {
    if (String(route.query.approval || '') !== String(approvalId)) return
    const q = { ...route.query }
    delete q.approval
    router.replace({ path: hooks.chatPath, query: q }).catch(() => {})
  }

  /** 撤掉屏上同一张（另一页签已裁决/已超时）：别留两颗点了只会报错的按钮 */
  const clearApprovalCard = approvalId => {
    let changed = false
    for (const m of messages.value) {
      if (!m || !Array.isArray(m.approvals)) continue
      const next = m.approvals.filter(a => !a || String(a.id) !== String(approvalId))
      if (next.length !== m.approvals.length) {
        m.approvals = next
        changed = true
      }
    }
    if (changed) hooks.scrollSoft?.()
  }

  return { approvalNow, approvalLeftSecOf, clearApprovalQuery, clearApprovalCard }
}
