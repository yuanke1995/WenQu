// 引擎的挂载初始化与产物签名续签（从 useChatEngine 抽出）。
// ready：先 loadSessions，再拉智能体/技能候选（不阻塞首屏），按 sid / newChatTick / autoPick
// 三分支落位，聚焦输入框，最后拉配置类数据。
// 签名续签：产物 URL 的 HMAC 签名 1 小时过期，页面长开需每 30 分钟换一批。
import { onUnmounted } from 'vue'
import { isAdminSync } from '../utils/auth'
import {
  getConfig, getRuntimeConfig, getUserPreference,
} from '../api'
import { loadModelIndex } from '../utils/modelRef'

export const useEngineBootstrap = ({
  loadSessions, loadAgents, loadSkills, route, switchSession, sessionStore,
  createNewSession, takeNewSessionAgent, autoPick, hooks,
  debugEntryVisible, debugDisplayVisible, showRelatedSuggestions,
  userDefaultModel, userDefaultModelIssue, modelIndex, refreshArtifactSigns,
}) => {
  // ==================== 挂载初始化 ====================
  const ready = async () => {
    await loadSessions()
    loadAgents()       // 智能体下拉候选（不阻塞首屏）
    loadSkills()       // 技能菜单候选（输入框「+」菜单，不阻塞首屏）
    const sid = route.query.sid
    if (sid) {
      await switchSession(sid)
    } else if (sessionStore.newChatTick > sessionStore.newChatSeen) {
      // 其它页面点过「新建对话」后跳转过来：消费该信号，直接进空会话；
      // 「试一试」带的预选智能体一并消费，落进新会话的下一问待选值
      sessionStore.newChatSeen = sessionStore.newChatTick
      await createNewSession(takeNewSessionAgent())
    } else {
      await autoPick()
    }
    hooks.focusInput?.()
    // 检索调试入口是管理员调参（chat.retrievalDebugEnabled，配置里标 debug）：只有管理员才拉 /config
    // ——/config 是管理端点，普通用户调它会 403，触发全局 403 提示（角色未授权），故只对管理员读
    if (isAdminSync()) {
      getConfig().then(r => {
        if (!r.success) return
        debugEntryVisible.value = r.data?.chat?.retrievalDebugEnabled?.value === 'true'
      }).catch(() => {})
    }
    // 同一个开关的「显示」语义走公开端点：归属徽标/派遣提示/引用分值的显隐对所有人生效（含普通用户）
    getRuntimeConfig().then(r => {
      if (!r.success) return
      debugDisplayVisible.value = r.data?.ui?.debugEntry === true
      // 个人显示偏好：缺省/未下发时保持默认开
      showRelatedSuggestions.value = r.data?.ui?.showRelatedSuggestions !== false
    }).catch(() => {})
    getUserPreference().then(r => {
      userDefaultModel.value = (r && r.data && r.data.defaultModel) || ''
      userDefaultModelIssue.value = (r && r.data && r.data.defaultModelIssue) || ''
    }).catch(() => {})
    loadModelIndex().then(idx => { modelIndex.value = idx || {} }).catch(() => {})
  }

  // 产物签名定时续签：签名有效期 1 小时，切会话/历史恢复时换过一次只覆盖「刚进来」的那批；
  // 页面连续开着不动超过一小时后，卡片上的 URL 又会全部过期。
  // 每 30 分钟换一次，留足冗余；无产物时定时器空转（首行 byId 为空直接返回，不发请求）。
  // 标签页隐藏时跳过——不可见的页面不需要能下载，省掉无谓请求。
  let signTimer = null
  const stopSignTimer = () => { if (signTimer) { window.clearInterval(signTimer); signTimer = null } }
  if (typeof window !== 'undefined') {
    signTimer = window.setInterval(() => {
      if (document.visibilityState === 'hidden') return
      refreshArtifactSigns()
    }, 30 * 60 * 1000)
    // 视图卸载时清掉，避免定时器随会话累积（引擎每进一次聊天页就 new 一个）。
    // onUnmounted 必须在 setup 的同步执行期注册才有当前实例上下文，所以包一层 try：
    // 引擎也可能被非组件场景调用，此时退化成由 ready 前的分支自行清理。
    try { onUnmounted(stopSignTimer) } catch (e) { /* 无当前实例：定时器随页面存活，可接受 */ }
  }

  return { ready, stopSignTimer }
}
