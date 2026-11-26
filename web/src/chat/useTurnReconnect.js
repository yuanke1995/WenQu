// @ts-check
// 接流与断线重挂（从 useChatEngine 抽出）：接流生命周期、接流入口、重挂循环与取消。
// 只注入引擎状态与副作用；循环本体仍在 ./turnReconnect.js（transport 层，可单测）。
import { createStreamLifecycle } from './streamLifecycle.js'
import { finishResumedShell as finishResumedShellImpl } from './streamReducer.js'
import { attachRunningTurn as attachRunningTurnImpl } from './streamAttach.js'
import { createTurnReconnect } from './turnReconnect.js'

export const useTurnReconnect = ({
  chatStreams, currentSessionId, switchSession, getChatRun, reconnectingSids,
  ensureGoalPlaceholder, takeGoalPlaceholder, streamAnswer,
}) => {
  /**
   * 接流气泡的收场：这一轮其实已经没了（服务端刚好收尾、服务重启、或通道再也接不上）时用它。
   * 不许留一颗转圈的气泡骗用户「还在跑」——摘掉登记、收掉进行中态，并按历史重显真实结果。
   */
  const finishResumedShell = (msg, st, sid, reload = true) => finishResumedShellImpl(msg, st, sid, reload,
    { chatStreams, currentSid: () => currentSessionId.value, switchSession })

  // 接流生命周期（探测去重/重连计数/已收场轮）
  const streamLifecycle = createStreamLifecycle()

  // 接流入口：把服务端还在跑的那一轮挂回本地气泡
  const attachRunningTurn = (sid, reuseMsg) => attachRunningTurnImpl(sid, reuseMsg, {
    chatStreams, lifecycle: streamLifecycle, getChatRun,
    ensureGoalPlaceholder, takeGoalPlaceholder, currentSid: () => currentSessionId.value, streamAnswer,
  })

  // 断线重挂（与 DSH 同口径）：连接是连接、轮次是轮次。通道断了只说明这条流没了，
  // 服务端那一轮照样在跑——按会话登记一条重挂循环，退避重试到接上、或探到那一轮已收场为止。
  const turnReconnect = createTurnReconnect({
    onDisconnect: (msg, sid) => { msg.retrying = true; msg.loading = true; reconnectingSids.add(sid) },
    onEnd: sid => reconnectingSids.delete(sid),
    clearRetrying: msg => { msg.retrying = false },
    isViewing: sid => currentSessionId.value === sid,
    hasStream: sid => chatStreams.has(sid),
    online: () => typeof navigator === 'undefined' || navigator.onLine !== false,
    attach: async (sid, msg) => (await attachRunningTurn(sid, msg)).status,
    // 探到那一轮已收场：按历史重显真实结果（绝不挂「本轮失败」）
    settleByHistory: (msg, sid) => finishResumedShell(msg, null, sid),
    setTimer: (fn, ms) => setTimeout(fn, ms),
    clearTimer: t => clearTimeout(t),
    onOnline: fn => window.addEventListener('online', fn),
    offOnline: fn => window.removeEventListener('online', fn),
  })

  /** 取消该会话的重挂（切走 / 停止本轮 / 页面卸载），返回那颗还没收场的消息 */
  const cancelReconnect = sid => turnReconnect.stop(sid)

  return { finishResumedShell, streamLifecycle, attachRunningTurn, turnReconnect, cancelReconnect }
}
