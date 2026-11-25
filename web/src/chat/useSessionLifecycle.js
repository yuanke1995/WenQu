// 会话生命周期（从 useChatEngine 抽出）：历史重试、新建/删除/自动落点、
// 跨页「新建对话」带的预选智能体消费。
import { ref } from 'vue'
import { createSessionActions } from './sessionActions.js'

export const useSessionLifecycle = ({
  currentSessionId, messages, historyError, sessionStore, chatStreams, agentMap, hooks,
  switchSession, settleSteerOnLeave, router, newSession, deleteSessionApi, loadSessions,
  isLoggedIn, notify,
}) => {
  /**
   * 重试取当前会话的历史（两端错误卡上那颗钮用）。
   * 直接走 switchSession：同 sid 有在途加载时它会复用结果，不会并发发两份。
   */
  const retryHistory = async () => {
    const sid = currentSessionId.value
    if (!sid) return
    historyError.value = ''
    await switchSession(sid)
  }

  const creatingSession = ref(false)
  /** 归属可能在会话没换 id 的情况下改变（服务端复用空会话时会改挂），页面上凡是按工作区判的东西
      （权限档位药丸、目录提示、头部标识）都要靠这个重新取一次工作区摘要 */
  const workspaceEpoch = ref(0)

  const { createNewSession, deleteSession: handleDeleteSession, autoPick } = createSessionActions({
    creating: creatingSession,
    workspaceEpoch,
    currentSid: () => currentSessionId.value,
    setCurrentSid: sid => { currentSessionId.value = sid },
    list: () => sessionStore.list,
    streams: chatStreams,
    settleSteer: () => settleSteerOnLeave(),
    switchSession: sid => switchSession(sid),
    clearView: () => { messages.value = []; historyError.value = '' },
    setAgentMemory: (sid, agentId) => { agentMap.value = { ...agentMap.value, [sid]: agentId || '' } },
    replaceRoute: sid => router.replace({ path: hooks.chatPath, query: { sid } }),
    replaceHome: () => router.replace(hooks.chatPath),
    focusInput: () => hooks.focusInput?.(),
    createApi: newSession,
    deleteApi: deleteSessionApi,
    reloadSessions: loadSessions,
    isLoggedIn,
    notifyError: msg => notify.error(msg),
    notifySuccess: msg => notify.success(msg),
  })

  /**
   * 跨页「新建对话」带的预选智能体（智能体页「试一试」）：消费一次即清。
   * 不能留在 query 里等对话页再读——createNewSession 会用 query:{sid} 写地址栏，兄弟参数会被抹掉；
   * 直接把 id 交给 createNewSession 的 presetAgentId，落进新会话的待选值最稳。
   */
  const takeNewSessionAgent = () => {
    const agent = sessionStore.newChatAgent || undefined
    sessionStore.newChatAgent = ''
    return agent
  }

  return { retryHistory, creatingSession, workspaceEpoch, createNewSession,
           handleDeleteSession, autoPick, takeNewSessionAgent }
}
