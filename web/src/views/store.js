// ==================== 工作台共享状态 ====================
// 会话列表为布局侧边栏与聊天页共用：模块级单例 reactive store，
// 避免两处各自拉取导致列表闪烁/不一致；聊天页在新建/删除/首条消息后调 loadSessions 同步。
import { reactive, shallowReactive } from 'vue'
import { message } from 'ant-design-vue'
import { listSessions } from '../api'

export const sessionStore = reactive({
  list: [],
  loading: false,
  // 游标分页状态（后端 /sessions 返回）：hasMore/nextCursor 驱动增量加载；
  // counts/total 为全量口径（仅统计有消息的会话），侧栏组头数字不随加载进度漂移；
  // expanded=已点过「查看更多」（此时底部出现「收起」）；firstPage=最近一次整表加载的
  // 首屏快照（浅拷贝），「收起」按它还原，不做额外请求
  loadingMore: false,
  hasMore: false,
  nextCursor: '',
  counts: { pinned: 0, today: 0, week: 0, earlier: 0 },
  total: 0,
  expanded: false,
  firstPage: null,
  keyword: '',
  // 跨页信号（路由 push 在同路径下是 no-op，query 不变 watch 不触发，需显式计数驱动）：
  // newChatTick  新建对话请求（聊天页消费后回写 newChatSeen，避免挂载期重复消费）
  // autoPickTick 当前会话被删除等场景 → 聊天页自动落到最近会话或新建
  newChatTick: 0,
  newChatSeen: 0,
  autoPickTick: 0
})

// ==================== 流式回答记录（模块级单例，跨路由存活） ====================
// chatStreams: sid → { msg, abort }，每个会话至多一条进行中的流式回答：
//   msg   流式中的 AI 消息对象（reactive）：SSE 回调改写它而不是页面级 messages 数组，
//         切走/新建会话后流继续跑、对象照常更新；切回该会话时把它接回视图尾部
//         （本轮完成前后端不落库助手消息，getHistory 里没有这条）。
//   abort 本轮请求的 AbortController：停止生成 / 删除流式中的会话时中止。
// 后端口径：客户端断开 = 取消订阅立即停止生成、本轮不落库助手消息（RagService sendSseEvent
// 返回 false 短路），所以"放着不管"不等于后台续跑——前台必须持有连接，切换会话才能不断流。
// 必须用 shallowReactive：deep reactive 的 Map 在 get() 时会把存入的 {msg,abort} 包一层代理，
// onDone 里 `chatStreams.get(sid) === st` 恒为 false → 记录永不清理 → 回答完成后 loading 卡死
// （发送按钮停在红色停止态）。shallowReactive 下 get 返回原对象，身份比较成立；msg 自身
// 已是 reactive(fresh)，模板响应性不受影响。
export const chatStreams = shallowReactive(new Map())

// 加载代次：重置加载（loadSessions）与增量加载（loadMoreSessions）并发时，
// 旧代次的迟到的响应直接丢弃，防止旧页数据追加进新列表造成重复/错序
let loadGen = 0

// 分页节奏：首屏 20 条，点「查看更多」每次再渲染 20 条（用户指定交互）
const FIRST_PAGE_SIZE = 20
const MORE_PAGE_SIZE = 20

export async function loadSessions (keyword) {
  if (keyword !== undefined) sessionStore.keyword = keyword
  const gen = ++loadGen
  sessionStore.loading = true
  try {
    const r = await listSessions(sessionStore.keyword, '', FIRST_PAGE_SIZE)
    if (gen !== loadGen) return r
    if (r.success && r.data && Array.isArray(r.data.items)) {
      sessionStore.list = r.data.items
      sessionStore.nextCursor = r.data.nextCursor || ''
      sessionStore.hasMore = Boolean(r.data.hasMore)
      sessionStore.counts = r.data.groupCounts || { pinned: 0, today: 0, week: 0, earlier: 0 }
      sessionStore.total = r.data.total || 0
      // 首屏快照（浅拷贝，与 list 脱钩）：「收起」的数据来源
      sessionStore.firstPage = { items: r.data.items.slice(), nextCursor: sessionStore.nextCursor, hasMore: sessionStore.hasMore }
      sessionStore.expanded = false
    }
    return r
  } catch (e) {
    if (gen === loadGen) message.error('加载会话列表失败: ' + (e.message || '未知错误'))
    return null
  } finally {
    if (gen === loadGen) sessionStore.loading = false
  }
}

// 点「查看更多」增量加载下一页（游标追加；重置加载进行中则让位）
export async function loadMoreSessions () {
  if (!sessionStore.hasMore || sessionStore.loading || sessionStore.loadingMore) return
  const gen = loadGen
  sessionStore.loadingMore = true
  try {
    const r = await listSessions(sessionStore.keyword, sessionStore.nextCursor, MORE_PAGE_SIZE)
    if (gen !== loadGen) return
    if (r.success && r.data && Array.isArray(r.data.items)) {
      sessionStore.list.push(...r.data.items)
      sessionStore.nextCursor = r.data.nextCursor || ''
      sessionStore.hasMore = Boolean(r.data.hasMore)
      sessionStore.expanded = true
    }
  } catch (e) {
    if (gen === loadGen) message.error('加载更多会话失败: ' + (e.message || '未知错误'))
  } finally {
    if (gen === loadGen) sessionStore.loadingMore = false
  }
}

// 「收起」：还原到最近一次整表加载（loadSessions）后的首屏状态（默认 20 条，今天组展开）；
// bump loadGen 使在途的增量加载响应作废，防止迟到的旧页数据追加进已收起的列表
export function collapseSessions () {
  const fp = sessionStore.firstPage
  if (!sessionStore.expanded || !fp) return
  loadGen++
  sessionStore.list = fp.items.slice()
  sessionStore.nextCursor = fp.nextCursor
  sessionStore.hasMore = fp.hasMore
  sessionStore.expanded = false
}

// 侧边栏展示：隐藏空会话（与旧版口径一致，空会话由聊天页"无感复用"逻辑管理）
export const visibleSessions = () => sessionStore.list.filter(s => (s.messageCount ?? 0) > 0)

// 首条消息发出即把会话抬进侧栏列表（不等回答完成）：列表隐藏空会话，若等 onDone 才刷新，
// 长回答生成期间新会话在侧栏不可见。纯本地乐观更新，权威数据仍由 onDone 后的 loadSessions 兜底
export function markSessionActive (sid, question) {
  if (!sid || sessionStore.keyword) return   // 搜索态下列表是关键字过滤结果，交给权威刷新
  const now = new Date().toISOString()
  const title = (question || '').trim().slice(0, 50).trim()
  const lift = item => {
    if (title && !item.title) item.title = title
    item.messageCount = Math.max(item.messageCount ?? 0, 1)
    item.updateTime = now
    // 置顶区保持在前，非置顶里最新更新的排最前（此后 onDone 的整表刷新会恢复权威序）
    if (item.isPinned !== 1) {
      const pinnedCount = sessionStore.list.filter(s => s.isPinned === 1).length
      const idx = sessionStore.list.indexOf(item)
      if (idx !== pinnedCount) {
        sessionStore.list.splice(idx, 1)
        sessionStore.list.splice(pinnedCount, 0, item)
      }
    }
  }
  const item = sessionStore.list.find(s => s.id === sid)
  const wasVisible = !!item && (item.messageCount ?? 0) > 0
  if (item) lift(item)
  else sessionStore.list.splice(sessionStore.list.filter(s => s.isPinned === 1).length, 0,
    { id: sid, title: title || '', messageCount: 1, updateTime: now, isPinned: 0, isFavorite: 0 })
  if (!wasVisible) {
    sessionStore.counts.today = (sessionStore.counts.today || 0) + 1
    sessionStore.total = (sessionStore.total || 0) + 1
  }
  // 首屏快照同步：「收起」按它还原，不同步会把刚抬进来的会话抹掉
  if (sessionStore.firstPage) {
    const fpItem = sessionStore.firstPage.items.find(s => s.id === sid)
    if (fpItem) {
      if (title && !fpItem.title) fpItem.title = title
      fpItem.messageCount = Math.max(fpItem.messageCount ?? 0, 1)
      fpItem.updateTime = now
    } else {
      sessionStore.firstPage.items.splice(sessionStore.firstPage.items.filter(s => s.isPinned === 1).length, 0,
        { id: sid, title: title || '', messageCount: 1, updateTime: now, isPinned: 0, isFavorite: 0 })
    }
  }
}
