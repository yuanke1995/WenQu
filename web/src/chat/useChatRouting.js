// @ts-check
// 路由 query 与侧栏信号驱动的会话切换（从 useChatEngine 抽出）：
// query.sid 切会话、query.approval 通知深链定位、newChatTick 新建、autoPickTick 自动落点，
// 以及工具审批卡的动作装配（resolveApproval）。
import { watch } from 'vue'
import { resolveApproval as resolveApprovalImpl } from './approvalActions.js'

export const useChatRouting = ({
  route, hooks, currentSessionId, historyLoads, switchSession, handleApprovalDeepLink,
  createNewSession, autoPick, takeNewSessionAgent, sessionStore,
  chatStreams, messages, notify, emitNotifChanged, pollRestoredAnswer, approveToolCall, approvalLeftSecOf,
}) => {
  // 路由 query.sid 驱动：只处理"切到某个会话"；sid 清空（新建/删除当前会话）由 tick 信号接管，
  // 避免两条链路同时触发 autoPick / createNew 的竞态
  watch(() => route.query.sid, sid => {
    if (route.path !== hooks.chatPath || !sid) return
    if (sid !== currentSessionId.value) switchSession(sid)
  })

  // 通知深链的 approval：会话已经落在当前页时（点通知时页面没跳走）也要能定位。
  // 切会话那一次由 switchSession 末尾统一处理；这里只管「当前会话内的 query 变化」，
  // 并且避开正在加载的窗口——否则同一张卡会被两条链路各挂一遍。
  watch(() => route.query.approval, id => {
    if (!id) return
    const sid = String(route.query.sid || '')
    if (!sid || sid !== currentSessionId.value || historyLoads.has(sid)) return
    void handleApprovalDeepLink(sid, String(id))
  })

  // 侧边栏「新建对话」信号（消费后回写 seen，跨页积累的 tick 只消费一次）
  // 注意不依赖 route.query 状态：tick 触发时路由 push 可能尚未完成，条件里查 sid 会偶发落空
  watch(() => sessionStore.newChatTick, async tick => {
    sessionStore.newChatSeen = tick
    if (route.path === hooks.chatPath) await createNewSession(takeNewSessionAgent())
  })

  // 当前会话被删除 → 自动落到最近会话或新建
  watch(() => sessionStore.autoPickTick, async () => {
    if (route.path === hooks.chatPath) await autoPick()
  })

  // 工具审批动作在 ./approvalActions.js（actions 层）。
  const resolveApproval = (m, card, approved, scope) => resolveApprovalImpl(m, card, approved, {
    chatStreams, currentSid: () => currentSessionId.value, notify, emitNotifChanged,
    pollRestoredAnswer: sid => pollRestoredAnswer(sid), scrollSoft: () => hooks.scrollSoft?.(),
    approveApi: approveToolCall, approvalLeftSec: approvalLeftSecOf,
    // 「本会话都允许」时后端会一并批准同工具的其它挂起卡；把它们的卡从所有气泡里摘掉
    dropApprovals: ids => {
      const set = new Set((ids || []).map(String))
      for (const msg of messages.value) {
        if (Array.isArray(msg.approvals)) {
          const left = msg.approvals.filter(x => !set.has(String(x.id)))
          if (left.length !== msg.approvals.length) msg.approvals = left
        }
      }
    }
  }, scope)

  return { resolveApproval }
}
