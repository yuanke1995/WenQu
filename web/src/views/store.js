// ==================== 工作台共享状态 ====================
// 会话列表仍是一份模块级单例，但结构从「一条平铺的游标列表」改成「按工作区分组」：
// 侧栏把会话收进各自的容器里（顶部「会话」= 不属于任何真实工作区；下面「工作区」= 用户自己建的），每个工作区只铺 4 条，
// 需要时点「查看更多 (N)」再展开——对齐 DSH workspace 的折叠口径，也对应 WorkBuddy 的工作区分组。
//
// 为什么分组数据不留在 AppLayout 自己拉：
//   聊天页（autoPick / 模型回填）与移动端会话 sheet 读的都是这份 list；
//   两处各拉一份必然在「新建/删除/置顶/首条消息」这些动作后漂移。
//   store 负责「按工作区取数 + 重新摊平」，视图只决定怎么渲染。
import { reactive, shallowReactive } from 'vue'
import { message } from 'ant-design-vue'
import { listSessions, listProjects, listScheduledJobs, getNewSessionProject } from '../api'

// 每个工作区首屏铺几条。4 条与基准稿（任务/工作区各 4 条后接「查看更多」）一致：
// 再多一进来就是长列表，再少又看不出这个工作区最近在聊什么。
const FIRST_PAGE_SIZE = 4
// 「会话」那一段（兜底容器 + 没挂工作区的都在这里）是主列表，不是分组预览：它动辄上百条，
// 只铺 4 条等于把最常用的入口压成一行「查看更多」，所以首屏铺 10 条。
const CHAT_FIRST_PAGE_SIZE = 10
// 搜索态每个工作区多铺一些：命中的会话本来就少，搜到了却还要点两次「查看更多」是死胡同
const SEARCH_PAGE_SIZE = 20
// 「查看更多」每次向服务端追加的页大小（游标分页，不按偏移量）
const MORE_PAGE_SIZE = 20

/** 后端约定的「不属于任何真实工作区」占位符：没挂工作区 + implicit 兜底容器（见 SessionService.NO_SPACE_PROJECT） */
export const NO_SPACE_KEY = '__nospace__'

// 「已归档」分组的展开状态跨刷新记忆：它是一个低频但需要稳定位置的分区，
// 每次进来都自动折回去等于让用户每次多点一下。
const ARCHIVED_OPEN_KEY = 'app_archived_open'
const readArchivedOpen = () => {
  try { return localStorage.getItem(ARCHIVED_OPEN_KEY) === '1' } catch (e) { return false }
}
const writeArchivedOpen = v => {
  try { localStorage.setItem(ARCHIVED_OPEN_KEY, v ? '1' : '0') } catch (e) { /* 隐私模式下忽略 */ }
}

export const sessionStore = reactive({
  // 平铺视图（按 置顶→更新时间 排序）：聊天页与移动端 sheet 读它
  list: [],
  loading: false,
  keyword: '',
  // 工作区分组（active）：第一段是「不属于任何真实工作区」（implicit 兜底容器 + 没挂工作区），其余是用户自己建的工作区
  spaces: [],
  // 已归档工作区：只记名单，展开「已归档」时才真正取会话（避免每次刷新都白跑 N 个请求）
  archived: [],
  archivedOpen: readArchivedOpen(),
  archivedLoaded: false,
  archivedLoading: false,
  // 定时任务：只用于会话行上的时钟标记（侧栏没有独立的「任务」分区）
  tasks: [],
  tasksLoading: false,
  tasksLoaded: false,
  // 全量口径（仅统计有消息的会话；各工作区 count 之和）
  total: 0,
  // 任一工作区还有下一页（移动端「查看更多」与批量全选文案用）
  hasMore: false,
  // 跨页信号（路由 push 在同路径下是 no-op，query 不变 watch 不触发，需显式计数驱动）：
  // newChatTick  新建对话请求（聊天页消费后回写 newChatSeen，避免挂载期重复消费）
  // autoPickTick 当前会话被删除等场景 → 聊天页自动落到最近会话或新建
  // shareOpenTick 侧栏会话菜单「分享」→ 聊天页打开分享弹窗（需先切到该会话，故不能只靠路由）
  newChatTick: 0,
  newChatSeen: 0,
  autoPickTick: 0,
  shareOpenTick: 0
})

// ==================== 流式回答记录（模块级单例，跨路由存活） ====================
// chatStreams: sid → { msg, abort }，每个会话至多一条进行中的流式回答：
//   msg   流式中的 AI 消息对象（reactive）：SSE 回调改写它而不是页面级 messages 数组，
//         切走/新建会话后流继续跑、对象照常更新；切回该会话时把它接回视图尾部
//         （本轮完成前后端不落库助手消息，getHistory 里没有这条）。
//   abort 本轮请求的 AbortController：停止生成 / 删除流式中的会话时中止。
// 后端口径：客户端断开 = 取消订阅立即停止生成、本轮不落库助手消息（ChatRunRuntime sendSseEvent
// 返回 false 短路），所以"放着不管"不等于后台续跑——前台必须持有连接，切换会话才能不断流。
// 必须用 shallowReactive：deep reactive 的 Map 在 get() 时会把存入的 {msg,abort} 包一层代理，
// onDone 里 `chatStreams.get(sid) === st` 恒为 false → 记录永不清理 → 回答完成后 loading 卡死
// （发送按钮停在红色停止态）。shallowReactive 下 get 返回原对象，身份比较成立；msg 自身
// 已是 reactive(fresh)，模板响应性不受影响。
export const chatStreams = shallowReactive(new Map())

// 加载代次：整表刷新与增量加载并发时，旧代次的迟到响应直接丢弃；
// 也让「搜索词刚变、旧词的结果才回来」这种竞态不会把新结果覆盖掉。
let loadGen = 0

// 工作区列表的模块级缓存：会话刷新（发完一条、置顶、删除）不该每次都重拉一次工作区。
// 工作区 CRUD 后调用 loadSessions(undefined, { refreshProjects: true }) 显式失效。
let projectsCache = []
let projectsLoaded = false

// ---------- 工作区结构 ----------

/** 空的工作区骨架。selectable=false：没挂工作区 / 全部会话这种虚拟分组，不给工作区操作入口 */
function makeSpace ({ id = '', key = '', name, isImplicit = false, status = 'active', virtual = false, selectable = true }) {
  return {
    id,
    key,
    name,
    isImplicit,
    status,
    virtual,
    selectable,
    // 当前铺开的会话（首屏 4 条，查看更多后追加）
    items: [],
    // 首屏快照：「收起」按它还原，不重新请求
    firstItems: [],
    count: 0,
    nextCursor: '',
    firstNextCursor: '',
    hasMore: false,
    firstHasMore: false,
    expanded: false,
    loading: false,
    loadingMore: false,
    error: '',
    loaded: false
  }
}

function spaceOfProject (p) {
  return makeSpace({
    id: p.id,
    key: p.id,
    name: p.displayName || p.name || '未命名工作区',
    isImplicit: !!p.isImplicit,
    status: p.status || 'active'
  })
}

function noSpaceSpace () {
  // 「不属于任何真实工作区」不是工作区：不给「打开工作区 / 重命名 / 删除」这些对工作区才成立的入口。
  // 移动端 sheet 里这一段也叫「会话」，与 PC 侧栏同一个名字——不再另造「没挂工作区」这种界面上不存在的名词。
  return makeSpace({ key: NO_SPACE_KEY, name: '会话', virtual: true, selectable: false })
}

function allSessionsSpace () {
  // 工作区接口不可用时的退化分组：至少让会话仍能按「全部会话」列出来
  return makeSpace({ key: '', name: '全部会话', isImplicit: true, virtual: true, selectable: false })
}

/**
 * 某一段的首屏条数：「会话」这一段（以及工作区接口不可用时退化出的「全部会话」）是主列表，
 * 铺 10 条；真实工作区各组是分组预览，铺 4 条。
 * 搜索态不分主次——命中的本来就少，还要点两次「查看更多」是死胡同。
 */
function firstPageSize (space, kw) {
  if (kw) return SEARCH_PAGE_SIZE
  const k = space && space.key
  return (k === NO_SPACE_KEY || k === '') ? CHAT_FIRST_PAGE_SIZE : FIRST_PAGE_SIZE
}

/**
 * 取一个工作区的首屏/下一页数据。
 * 空会话（messageCount=0）在展示层过滤掉：后端列表本来就只回有消息的会话，
 * 这里再兜一次是因为本地乐观插入（markSessionActive）也会往同一个数组里写。
 */
async function fetchSpacePage (space, keyword, size) {
  const r = await listSessions(keyword, '', size, space.key)
  const d = (r && r.data) || {}
  const items = (d.items || []).filter(s => (s.messageCount ?? 0) > 0)
  return {
    items,
    count: Number(d.total || 0),
    nextCursor: d.nextCursor || '',
    hasMore: !!d.hasMore
  }
}

/** 把工作区的响应落成可渲染结构，并保留首屏快照（收起时不重新请求） */
function applyFirstPage (space, page) {
  return {
    ...space,
    ...page,
    firstItems: page.items.slice(),
    firstNextCursor: page.nextCursor,
    firstHasMore: page.hasMore,
    loaded: true,
    loading: false
  }
}

// ---------- 摊平 + 排序 ----------

const timeOf = v => {
  if (!v) return 0
  if (typeof v === 'number') return v
  if (typeof v === 'string') { const t = new Date(v).getTime(); return isNaN(t) ? 0 : t }
  if (Array.isArray(v)) return new Date(v[0], (v[1] || 1) - 1, v[2] || 1, v[3] || 0, v[4] || 0, v[5] || 0).getTime()
  if (typeof v === 'object' && v.year) {
    return new Date(v.year, (v.monthValue || 1) - 1, v.dayOfMonth || 1, v.hour || 0, v.minute || 0, v.second || 0).getTime()
  }
  return 0
}

/**
 * 重新摊平：spaces 里每个工作区各自只加载了一部分，聊天页需要的是一条按
 * 置顶→更新时间排序的全局列表（autoPick 取第一条）。去重按 id——
 * 首次加载与增量加载之间可能有同一会话（期间更新过）出现在两页里。
 */
function rebuildFlat () {
  const items = []
  const seen = new Set()
  // 已归档工作区也并入摊平视图：聊天页的标题/模型回填读 list，
  // 点开归档区里的会话时不能因为"它不在活动工作区"就退回「新对话」。
  // 归档区未展开时 items 为空，不产生额外行。
  for (const space of [...sessionStore.spaces, ...sessionStore.archived]) {
    for (const s of space.items) {
      if (!s || !s.id || seen.has(s.id)) continue
      seen.add(s.id)
      items.push(s)
    }
  }
  items.sort((a, b) => {
    const pa = a.isPinned === 1 ? 1 : 0
    const pb = b.isPinned === 1 ? 1 : 0
    if (pa !== pb) return pb - pa
    return timeOf(b.updateTime) - timeOf(a.updateTime)
  })
  sessionStore.list = items
  sessionStore.total = sessionStore.spaces.reduce((n, s) => n + (s.count || 0), 0)
  sessionStore.hasMore = sessionStore.spaces.some(s => s.hasMore)
}

// ---------- 会话拉取 ----------

/**
 * 重新加载全部工作区。
 * 每个工作区一次请求（size=4）：不能只拉一页全局列表再按 projectId 分桶——
 * 最近 20 条可能全在同一个工作区，其它工作区会显示成空的（与「每个工作区有自己的最近会话」不符）。
 * 工作区数是个位数，N 个并发请求换正确分组，值得；工作区数上去后应换成一次返回
 * 「各工作区计数 + 首屏」的 overview 接口，而不是把 N 继续放大。
 *
 * @param keyword 可选：新的搜索词；不传沿用当前 keyword
 * @param opts.refreshProjects 工作区 CRUD 后传 true 失效缓存
 */
export async function loadSessions (keyword, { refreshProjects = false } = {}) {
  if (keyword !== undefined) sessionStore.keyword = keyword
  const kw = sessionStore.keyword
  const gen = ++loadGen
  sessionStore.loading = true
  try {
    let projects = projectsCache
    if (refreshProjects || !projectsLoaded) {
      try {
        const r = await listProjects()
        projects = (r && r.data) || []
        projectsCache = projects
        projectsLoaded = true
      } catch (e) {
        // 工作区接口挂了不等于会话接口挂了：退化成「全部会话」一个分组，列表仍可读。
        // 不把 projectsLoaded 置 true —— 下一次刷新会重试。
        projects = []
      }
    }
    if (gen !== loadGen) return null

    // 「不属于任何真实工作区」始终参与取数且排在最前：侧栏第一段「会话」就是它——
    // implicit 兜底容器的会话也算在内，所以它不再出现在「工作区」列表里。
    // 工作区接口不可用时退化成「全部会话」一个分组，此时不再单列这一段（否则同一批会话出现两次）。
    const defs = projects.length
      ? [noSpaceSpace(), ...projects.filter(p => p.status !== 'archived' && !p.isImplicit).map(spaceOfProject)]
      : [allSessionsSpace()]

    const loaded = await Promise.all(defs.map(async def => {
      try {
        return applyFirstPage(def, await fetchSpacePage(def, kw, firstPageSize(def, kw)))
      } catch (e) {
        // 单个工作区失败不拖垮整栏：该工作区显示「加载失败 + 重试」，其余工作区照常
        return { ...def, error: e.message || '加载失败', loaded: true }
      }
    }))
    if (gen !== loadGen) return null

    sessionStore.spaces = loaded.filter(s => {
      // 「不属于任何真实工作区」：位置固定（第一段「会话」），0 条时也保留——空态本身就是信息
      if (s.key === NO_SPACE_KEY) return true
      // 搜索态：0 命中的工作区整组隐藏，否则一屏全是「暂无会话」
      if (kw) return s.count > 0 || !!s.error
      // 普通态：用户显式建的工作区即使还空着也保留——刚建完就要看得见，才谈得上往里放会话
      return true
    })
    sessionStore.archived = projects.filter(p => p.status === 'archived' && !p.isImplicit).map(spaceOfProject)
    sessionStore.archivedLoaded = false
    rebuildFlat()

    // 已归档分区如果是展开态，按新数据重新取一遍（搜索态不显示归档，也就没必要取）
    if (sessionStore.archivedOpen && !kw) loadArchived()
    if (!sessionStore.tasksLoaded) loadTasks()
    return { spaces: sessionStore.spaces }
  } catch (e) {
    if (gen === loadGen) message.error('加载会话列表失败: ' + (e.message || '未知错误'))
    return null
  } finally {
    if (gen === loadGen) sessionStore.loading = false
  }
}

/** 单个工作区失败后的重试（只重跑这一个工作区，不动其它已加载内容） */
export async function reloadSpace (space) {
  if (!space || space.loading) return
  space.loading = true
  const gen = loadGen
  try {
    const page = await fetchSpacePage(space, sessionStore.keyword, firstPageSize(space, sessionStore.keyword))
    if (gen !== loadGen) return
    Object.assign(space, applyFirstPage(space, page), { error: '' })
    rebuildFlat()
  } catch (e) {
    space.error = e.message || '加载失败'
  } finally {
    space.loading = false
  }
}

/** 某个工作区「查看更多」：按游标追加下一页 */
export async function loadSpaceMore (space) {
  if (!space || !space.hasMore || space.loadingMore || space.loading) return
  space.loadingMore = true
  const gen = loadGen
  try {
    const r = await listSessions(sessionStore.keyword, space.nextCursor, MORE_PAGE_SIZE, space.key)
    if (gen !== loadGen) return
    const d = (r && r.data) || {}
    const fresh = (d.items || []).filter(s => (s.messageCount ?? 0) > 0)
    const seen = new Set(space.items.map(s => s.id))
    for (const s of fresh) {
      if (!seen.has(s.id)) { space.items.push(s); seen.add(s.id) }
    }
    space.nextCursor = d.nextCursor || ''
    space.hasMore = !!d.hasMore
    space.count = Number(d.total ?? space.count)
    space.expanded = true
    rebuildFlat()
  } catch (e) {
    message.error('加载更多会话失败: ' + (e.message || '未知错误'))
  } finally {
    space.loadingMore = false
  }
}

/** 收起某个工作区：回到首屏快照，不重新请求 */
export function collapseSpace (space) {
  if (!space) return
  space.items = space.firstItems.slice()
  space.nextCursor = space.firstNextCursor
  space.hasMore = space.firstHasMore
  space.expanded = false
  rebuildFlat()
}

// ---------- 已归档工作区 ----------

/** 展开「已归档」时才拉这些工作区的会话（初版只记名单，避免每次刷新都白跑 N 个请求） */
export async function loadArchived () {
  if (sessionStore.archivedLoading || !sessionStore.archived.length) return
  sessionStore.archivedLoading = true
  // 先给每个归档工作区挂上 loading：模板据此隐藏「暂无会话」并显示头部转圈，
  // 否则懒加载的几百毫秒里，用户看到的是一排「加载完了但是空的」的工作区。
  sessionStore.archived.forEach(s => { s.loading = true })
  const gen = loadGen
  try {
    const list = await Promise.all(sessionStore.archived.map(async space => {
      try {
        return applyFirstPage(space, await fetchSpacePage(space, sessionStore.keyword, FIRST_PAGE_SIZE))
      } catch (e) {
        return { ...space, error: e.message || '加载失败', loading: false, loaded: true }
      }
    }))
    if (gen !== loadGen) return
    sessionStore.archived = list
    sessionStore.archivedLoaded = true
    rebuildFlat()
  } finally {
    sessionStore.archivedLoading = false
  }
}

export function toggleArchived () {
  sessionStore.archivedOpen = !sessionStore.archivedOpen
  writeArchivedOpen(sessionStore.archivedOpen)
  if (sessionStore.archivedOpen && !sessionStore.archivedLoaded) loadArchived()
}

// ---------- 定时任务 ----------

/** 拉本人的定时任务：侧栏「任务」分区 + 会话行时钟标记共用 */
export async function loadTasks (force = false) {
  if (sessionStore.tasksLoading) return
  if (!force && sessionStore.tasksLoaded) return
  sessionStore.tasksLoading = true
  try {
    const r = await listScheduledJobs()
    sessionStore.tasks = (r && r.data) || []
    sessionStore.tasksLoaded = true
  } catch (e) {
    // 任务拉不到只影响这两个提示位，不打断会话列表；下次刷新再试
    sessionStore.tasks = []
  } finally {
    sessionStore.tasksLoading = false
  }
}

// ---------- 兼容旧消费方 ----------

/**
 * 侧边栏展示口径：只摊平活动工作区（不含已归档）并隐藏空会话。
 * <p>批量选择用它而不是 list：list 里可能带着已展开归档区的会话，
 * 那些行在侧栏是折叠着的，全选时不该被静默选中。
 */
export const visibleSessions = () =>
  sessionStore.spaces.flatMap(space => space.items).filter(s => (s.messageCount ?? 0) > 0)

/**
 * 分享状态变更后回写列表项的 shared 标记：侧栏行内的分享图标读的就是它。
 * 不回写的话，生成/停止分享后图标要等下次整表 loadSessions 才出现，用户会以为分享没生效。
 * 纯本地更新、不发请求 —— 权威值仍在服务端，下次 loadSessions 会覆盖。
 */
export function markSessionShared (sid, shared) {
  if (!sid) return
  const flag = shared ? 1 : 0
  const touch = item => { if (item && item.id === sid) item.shared = flag }
  for (const space of sessionStore.spaces) {
    space.items.forEach(touch)
    space.firstItems.forEach(touch)
  }
  for (const space of sessionStore.archived) {
    space.items.forEach(touch)
    space.firstItems.forEach(touch)
  }
  sessionStore.list.forEach(touch)
}

/**
 * 首条消息发出即把会话抬进侧栏（不等回答完成）：列表隐藏空会话，若等 onDone 才刷新，
 * 长回答生成期间新会话在侧栏不可见。纯本地乐观更新，权威数据仍由 onDone 后的 loadSessions 兜底。
 * 属于哪个工作区由 newSessionProjectId 决定（工作区行的 + / 工作区页写入）；没设置就落「不属于任何工作区」。
 */
export function markSessionActive (sid, question) {
  if (!sid || sessionStore.keyword) return   // 搜索态下列表是关键字过滤结果，交给权威刷新
  const now = new Date().toISOString()
  const title = (question || '').trim().slice(0, 50).trim()
  const existing = sessionStore.list.find(s => s.id === sid)
  if (existing) {
    if (title && !existing.title) existing.title = title
    existing.messageCount = Math.max(existing.messageCount ?? 0, 1)
    existing.updateTime = now
    rebuildFlat()
    return
  }
  const pid = getNewSessionProject()
  const space = sessionStore.spaces.find(s => s.id === pid || s.key === pid)
    || sessionStore.spaces.find(s => s.key === NO_SPACE_KEY)
    || sessionStore.spaces[0]
  if (!space) return
  const item = {
    id: sid,
    title: title || '',
    messageCount: 1,
    updateTime: now,
    isPinned: 0,
    isFavorite: 0,
    projectId: space.id || ''
  }
  // 首屏快照同步：不同步的话「收起」会把刚抬进来的会话抹掉
  space.items.unshift(item)
  space.firstItems.unshift(item)
  space.count = (space.count || 0) + 1
  rebuildFlat()
}

/**
 * 把一个「列表里还没加载、但用户明确要打开」的会话（典型：工作区页里点进第 5 条以后的会话）
 * 先塞进它所属工作区的已加载行里。
 *
 * <p>为什么需要：侧栏每个工作区只铺 4 条，聊天页的标题/模型回填读的是摊平后的 list；
 * 用户从工作区页点进一条更早的会话时，标题会退回「新对话」、侧栏也没有高亮行。
 * 这里只补一条当前会话（不改 count，不动分页游标），下次整表刷新自然会被权威数据替换。
 * 找不到工作区时退化成只记标题，至少聊天页标题不再错。
 */
export function rememberSession (session, projectId) {
  if (!session || !session.id) return
  if (sessionStore.list.some(s => s.id === session.id)) return
  const space = [...sessionStore.spaces, ...sessionStore.archived]
    .find(s => s.id === (projectId || session.projectId))
    || sessionStore.spaces.find(s => s.key === NO_SPACE_KEY)
    || sessionStore.spaces[0]
  if (!space) return
  const item = { ...session, projectId: projectId || session.projectId || space.id || '' }
  if (!item.messageCount) item.messageCount = 1
  // 插在置顶行之后：置顶由后端整段返回，永远不会落在这个「没加载到」的分支里
  const pinnedCount = space.items.filter(s => s.isPinned === 1).length
  space.items.splice(pinnedCount, 0, item)
  // 首屏快照也带上：用户点「收起」时当前会话不该跟着消失
  space.firstItems.splice(pinnedCount, 0, item)
  rebuildFlat()
}
