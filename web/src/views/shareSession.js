// ==================== 会话分享（只读链接）的状态机 ====================
// PC 标题栏弹窗与移动「状态与来源」sheet 共用同一份。抽出来的理由不是少写代码，而是
// 后端对 session_id 唯一约束（一条会话一条分享），且**重新开启会换 token 使旧链接立即失效**——
// 两端对这个语义必须是同一个口径，否则一端提示换链、一端静默复用，用户手里的旧链接就变成薛定谔的有效。
//
// 链接本身是平台无关的：token 落在 path 段（/shared/{token}），后端免登录读取，
// 阅读页 SharedSessionPage.vue 已带 @media ≤768px 适配 —— H5 不需要第二套链接协议，也不需要第二张页面。
import { computed, ref, unref } from 'vue'
import { message } from 'ant-design-vue'
import { getSessionShare, enableSessionShare, disableSessionShare } from '../api'
import { markSessionShared } from './store'
import { copyText } from '../utils/clipboard'

/** 分享链接的唯一拼装处（只读携带者可看不可续聊） */
export const shareUrlOf = token => (token ? `${location.origin}/shared/${token}` : '')

const emptyInfo = () => ({ enabled: false, token: '', visitCount: 0 })

/**
 * @param {object} deps
 * @param {import('vue').Ref<string>|(() => string)} deps.sessionId 会话 id。
 *        允许传 getter 是因为 PC 壳的 currentSessionId 来自 useChatEngine 解构（const 声明在调用点之后），
 *        传 `() => currentSessionId` 才能绕过暂时性死区。
 */
export const useSessionShare = ({ sessionId }) => {
  const sidOf = () => (typeof sessionId === 'function' ? sessionId() : unref(sessionId))
  const loading = ref(false)
  const info = ref(emptyInfo())
  const url = computed(() => shareUrlOf(info.value.token))

  const reset = () => { info.value = emptyInfo() }

  /** 拉取当前分享状态（打开面板时调；会话切换后由调用方自行 reset） */
  const load = async () => {
    loading.value = true
    try {
      const r = await getSessionShare(sidOf())
      info.value = r?.data || emptyInfo()
      // 顺带校正侧栏标记：分享可以在另一台设备上开启，本机列表里的 shared 还是旧的。
      // 回写的是服务端权威值，不会覆盖本地刚做的乐观更新（值本来就一致）。
      markSessionShared(sidOf(), Boolean(info.value.enabled))
    } catch (e) {
      message.error('读取分享状态失败：' + (e.message || ''))
      reset()
    } finally {
      loading.value = false
    }
  }

  /** 生成；regenerate=true 时用于「换一个新链接」（后端会换新 token，旧链接立即失效） */
  const enable = async (regenerate = false) => {
    loading.value = true
    try {
      const r = await enableSessionShare(sidOf())
      info.value = { ...(r?.data || {}), enabled: true, visitCount: 0 }
      // 回写侧栏列表项：会话分享弹窗开着时侧栏就在旁边，图标必须立刻出现/保持，
      // 不能等下一次 loadSessions（用户会以为链接没生效而重复生成）
      markSessionShared(sidOf(), true)
      message.success(regenerate ? '已换新链接，旧链接立即失效' : '分享链接已生成')
      return true
    } catch (e) {
      message.error('生成失败：' + (e.message || ''))
      return false
    } finally {
      loading.value = false
    }
  }

  const disable = async () => {
    loading.value = true
    try {
      await disableSessionShare(sidOf())
      reset()
      markSessionShared(sidOf(), false)
      message.success('已停止分享，链接立即失效')
      return true
    } catch (e) {
      message.error('停止失败：' + (e.message || ''))
      return false
    } finally {
      loading.value = false
    }
  }

  const copyLink = () => copyText(url.value, '链接已复制')

  return { loading, info, url, load, enable, disable, copyLink, reset }
}
