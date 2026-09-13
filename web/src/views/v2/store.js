// ==================== v2 工作台共享状态 ====================
// 会话列表为布局侧边栏与聊天页共用：模块级单例 reactive store，
// 避免两处各自拉取导致列表闪烁/不一致；聊天页在新建/删除/首条消息后调 loadSessions 同步。
import { reactive } from 'vue'
import { message } from 'ant-design-vue'
import { listSessions } from '../../api'

export const sessionStore = reactive({
  list: [],
  loading: false,
  keyword: '',
  // 跨页信号（路由 push 在同路径下是 no-op，query 不变 watch 不触发，需显式计数驱动）：
  // newChatTick  新建对话请求（聊天页消费后回写 newChatSeen，避免挂载期重复消费）
  // autoPickTick 当前会话被删除等场景 → 聊天页自动落到最近会话或新建
  newChatTick: 0,
  newChatSeen: 0,
  autoPickTick: 0
})

export async function loadSessions (keyword) {
  if (keyword !== undefined) sessionStore.keyword = keyword
  sessionStore.loading = true
  try {
    const r = await listSessions(sessionStore.keyword)
    if (r.success && Array.isArray(r.data)) {
      sessionStore.list = r.data
    }
    return r
  } catch (e) {
    message.error('加载会话列表失败: ' + (e.message || '未知错误'))
    return null
  } finally {
    sessionStore.loading = false
  }
}

// 侧边栏展示：隐藏空会话（与旧版口径一致，空会话由聊天页"无感复用"逻辑管理）
export const visibleSessions = () => sessionStore.list.filter(s => (s.messageCount ?? 0) > 0)
