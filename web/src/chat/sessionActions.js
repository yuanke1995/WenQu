// 会话生命周期动作（actions 层）：新建（复用空会话优先）、自动落到最近会话、删除后的落点。
import { ref } from 'vue'

/**
 * @param creating        ref<boolean> 新建在途标志（防连点）
 * @param workspaceEpoch  ref<number> 会话 id 未变但归属可能改挂时，通知页面重取工作区摘要
 * @param currentSid      () => 当前会话 ID
 * @param setCurrentSid   sid => void
 * @param list            会话列表（store.list 的当前值）
 * @param streams         在跑的流（chatStreams）
 * @param settleSteer     离开会话时结算插话队列
 * @param switchSession   切/重铺会话
 * @param clearView       清空消息与历史错误标记
 * @param setAgentMemory  (sid, agentId) => void
 * @param replaceRoute    sid => void（写地址栏）
 * @param replaceHome     () => void（清掉 sid）
 * @param focusInput      () => void
 * @param createApi       () => Promise<{success,data?}>
 * @param deleteApi       sid => Promise
 * @param reloadSessions  () => Promise
 * @param isLoggedIn      () => boolean（登出后 store 复位的收尾动作不许再建会话）
 * @param notifyError     msg => void
 * @param notifySuccess   msg => void
 */
export const createSessionActions = ({
  creating, workspaceEpoch, currentSid, setCurrentSid, list, streams,
  settleSteer, switchSession, clearView, setAgentMemory, replaceRoute, replaceHome,
  focusInput, createApi, deleteApi, reloadSessions, isLoggedIn = () => true, notifyError, notifySuccess
}) => {
  /**
   * 新建会话。presetAgentId：由「切换智能体」触发时带上目标智能体，落为新会话的待选值
   * （新会话尚未绑定，首问发出时才由后端锁定）。
   */
  const createNewSession = async presetAgentId => {
    if (creating.value) return
    // 登出（清令牌 + 复位 store）之后，挂载/复位引发的收尾动作仍在排队，这里一律作废：
    // 否则会以无令牌身份发出 /session/new，弹一句「创建会话失败：请先登录」
    if (!isLoggedIn()) return
    // 落到空/新会话同样是一次「离开」：上一会话排队中的插话对新会话不生效
    settleSteer()
    // 空会话复用排除正在流式的会话：列表里的 messageCount 是快照（首条消息 done 后才刷新），
    // 流式中的会话可能仍记 0——命中它会把当前视图清空而不是开新会话
    const emptySid = list().find(s => (s.messageCount ?? 0) === 0 && !streams.has(s.id))?.id
    if (emptySid) {
      if (presetAgentId !== undefined) setAgentMemory(emptySid, presetAgentId)
      if (currentSid() !== emptySid) await switchSession(emptySid)
      // 这一句把视图判成「就是空的」（列表里 messageCount=0），所以失败标记要一起清：
      // 留着它，界面上会在一条新会话上方画一张「历史没取回来」的错误卡
      else clearView()
      replaceRoute(emptySid)
      focusInput()
      return
    }
    creating.value = true
    const prevSid = currentSid()
    try {
      const r = await createApi()
      if (r.success && r.data?.sessionId) {
        const sid = r.data.sessionId
        setCurrentSid(sid)
        // 服务端领走的往往就是手上这条空会话：id 没变，但归属已经改挂到这次点名的工作区。
        // 页面那个「切会话才重取工作区摘要」的判据不会动，所以要单独喊它一次。
        if (sid === prevSid) workspaceEpoch.value++
        if (presetAgentId !== undefined) setAgentMemory(sid, presetAgentId)
        clearView()
        replaceRoute(sid)
        await reloadSessions()
        focusInput()
      }
    } catch (e) {
      // 401 已由全局 app:unauthorized 提示并回登录页，不再叠一条业务失败
      if (e.status !== 401) notifyError('创建会话失败: ' + (e.message || '未知错误'))
    } finally {
      creating.value = false
    }
  }

  /** 删除后的落点：还有别的非空会话就切过去，否则回空白页 */
  const deleteSession = async sid => {
    try {
      await deleteApi(sid)
      notifySuccess('会话已删除')
      if (sid === currentSid()) {
        const remaining = list().filter(s => s.id !== sid && (s.messageCount ?? 0) > 0)
        if (remaining.length > 0) await switchSession(remaining[0].id)
        else {
          settleSteer()
          clearView()
          setCurrentSid(null)
          replaceHome()
        }
      }
      await reloadSessions()
    } catch (e) { notifyError(e.message || '删除失败') }
  }

  /** 删除当前会话/路由空 sid 后的自动落点：最近一个非空会话，没有就新建 */
  const autoPick = async () => {
    if (!isLoggedIn()) return
    const first = list().find(s => (s.messageCount ?? 0) > 0)
    if (first) await switchSession(first.id)
    else await createNewSession()
  }

  return { createNewSession, deleteSession, autoPick }
}
