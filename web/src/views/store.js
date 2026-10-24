// ==================== 工作台共享状态 ====================
// 会话列表仍是一份模块级单例，但结构从「一条平铺的游标列表」改成「按工作区分组」：
// 侧栏把会话收进各自的容器里（顶部「会话」= 不属于任何真实工作区；下面「工作区」= 用户自己建的），每个工作区只铺 4 条，
// 需要时点「查看更多 (N)」再展开——对齐 DSH workspace 的折叠口径，也对应 WorkBuddy 的工作区分组。
//
// 为什么分组数据不留在 AppLayout 自己拉：
//   聊天页（autoPick / 模型回填）与移动端会话 sheet 读的都是这份 list；
//   两处各拉一份必然在「新建/删除/置顶/首条消息」这些动作后漂移。
//   store 负责「按工作区取数 + 重新摊平」，视图只决定怎么渲染。
import { ref, reactive, shallowReactive } from 'vue'
import { message } from 'ant-design-vue'
import { listSessions, listProjects, listScheduledJobs, getNewSessionProject } from '../api'
import { announce, onAnnounce } from '../utils/tabSync'

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

// ==================== 任务清单快照（模块级单例，跨路由存活） ====================
// sid → { turnId, items:[{content,status}], updatedAt }：writeTodo 每次整表覆盖，后端只存最新一份。
// 为什么必须挂在这里而不是 useChatEngine 里（2026-10-22 真机复现）：清单是「这一轮跑到哪了」的
// 会话级状态，而这一轮的流是 chatStreams 的单例。轮内切走路由再回来会重建页面与它那份引擎：
// 流还挂在旧实例上继续收 todo 帧（写进旧实例自己的 map），新实例既接不上流（chatStreams.has 拦着）、
// 也再收不到 todo 帧 —— 卡片就冻结在切走那一刻，而正文与工具卡照常长（它们在 chatStreams 的 msg 里），
// 只有整页刷新能救。放进模块级后，谁在渲染读的都是同一份。
export const todosMap = ref({})

// 加载代次：整表刷新与增量加载并发时，旧代次的迟到响应直接丢弃；
// 也让「搜索词刚变、旧词的结果才回来」这种竞态不会把新结果覆盖掉。
let loadGen = 0

// 上一次拿到权威列表的时刻（前台整表刷新与后台静默对齐都写它）。
// 跨页签喊话会整段漏给被浏览器挂起（bfcache）的页签，所以「切回前台且这份数据已经老了」要自己补一次。
let lastSyncAt = 0

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

/** 行的排序口径：置顶在前，其余按更新时间倒序。摊平视图与工作区内重排共用这一份，
    否则跨页签补丁在区里排好的序会和摊平后的序各说各话。 */
const rowOrder = (a, b) => {
  const pa = a.isPinned === 1 ? 1 : 0
  const pb = b.isPinned === 1 ? 1 : 0
  if (pa !== pb) return pb - pa
  return timeOf(b.updateTime) - timeOf(a.updateTime)
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
  items.sort(rowOrder)
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
    lastSyncAt = Date.now()
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

// ---------- 跨页签对齐 ----------
// 别的页签改了列表时这一栏该做什么。判据只有一条：**能定点补的就别重拉**。
// 整表重拉会连着坏三件事（都是 loadSessions 的既有行为，用在后台对齐上就成了打架）：
//   1. 它抬 loadGen —— 用户刚点的「查看更多」还在路上，响应回来发现代次不对就被静默丢掉，
//      表现为「点了没反应」；
//   2. 它把每个工作区重建回首屏 —— 用户铺开的那二十几条折回 4 条，滚动位置跟着跳；
//   3. 它置 loading —— 用户什么都没做，整栏先闪一次转圈。
// 所以删除/改名/置顶/移动/新会话这些「喊话里就带着答案」的走补丁；
// 只有工作区结构变了、或收到认不出的喊话，才走 resyncSessions 的静默对齐。

/** 本地补进去的行：乐观抬进列表的新会话、从工作区页点进来的旧会话、别的页签喊来的新行。
    服务端那一窗里可能还没有它们（首条消息还没落库 / 本来就排在窗口之外），
    静默对齐不许把它们抹掉——行一没，转圈状态和高亮跟着没。服务端认账后即从这份名单里划掉。 */
const localRows = new Set()

/** 某条会话此刻躺在哪个工作区的已加载行里（含已归档区：它展开时同样有行） */
function locateRow (sid) {
  for (const space of [...sessionStore.spaces, ...sessionStore.archived]) {
    const idx = space.items.findIndex(s => s.id === sid)
    if (idx >= 0) return { space, idx }
  }
  return null
}

/** 按归属找已加载出来的工作区；空归属 = 侧栏第一段「会话」 */
function findSpace (projectId) {
  const key = projectId || NO_SPACE_KEY
  return [...sessionStore.spaces, ...sessionStore.archived]
    .find(s => s.key === key || (projectId && s.id === projectId))
}

/**
 * 从某个工作区的两份视图里摘掉一行。首屏快照必须一起摘，
 * 否则「收起」会把刚删掉的会话原样放回来（收起走的是快照，不发请求）。
 */
function detachRow (space, sid) {
  const i = space.items.findIndex(s => s.id === sid)
  if (i < 0) return false
  space.items.splice(i, 1)
  const j = space.firstItems.findIndex(s => s.id === sid)
  if (j >= 0) space.firstItems.splice(j, 1)
  space.count = Math.max(0, (space.count || 0) - 1)
  localRows.delete(sid)
  return true
}

/** 把本地补的行抬回这一窗：按 rowOrder 归位，并把它算进段首计数（服务端那份计数还不认它） */
function carryLocalRows (space, prevItems) {
  const missing = prevItems.filter(s => localRows.has(s.id) && !space.items.some(x => x.id === s.id))
  if (!missing.length) return
  space.items = [...missing, ...space.items].sort(rowOrder)
  space.firstItems = [...missing, ...space.firstItems].sort(rowOrder)
  space.count = (space.count || 0) + missing.length
}

/**
 * 别的页签改了一行：只改它喊出来的那几个字段。
 * 「只改带了的那几个」是重点——重命名的喊话不带置顶标记，照单全收等于把别人的置顶抹了。
 */
function applyRemoteRow (p) {
  const sid = String(p.sid || '')
  if (!sid) return
  const hit = locateRow(sid)
  if (hit) {
    const patch = {}
    for (const k of ['title', 'updateTime', 'isPinned', 'isFavorite', 'shared', 'messageCount']) {
      if (p[k] !== undefined) patch[k] = p[k]
    }
    Object.assign(hit.space.items[hit.idx], patch)
    const snap = hit.space.firstItems.find(s => s.id === sid)
    if (snap) Object.assign(snap, patch)
    if (p.projectId !== undefined && (hit.space.id || '') !== (p.projectId || '')) {
      // 换了归属：从旧区摘掉、插进新区头部。新区还没加载出来（例如归档区折着）就只摘不插，
      // 下次整表刷新会把它放回该在的地方——比凭空造一个计数不对的工作区诚实。
      const row = hit.space.items[hit.idx]
      detachRow(hit.space, sid)
      const to = findSpace(p.projectId)
      if (to) {
        row.projectId = to.id || ''
        to.items.unshift(row)
        to.firstItems.unshift(row)
        to.count = (to.count || 0) + 1
      }
    } else if (patch.updateTime !== undefined || patch.isPinned !== undefined) {
      hit.space.items.sort(rowOrder)
      hit.space.firstItems.sort(rowOrder)
    }
    rebuildFlat()
    return
  }
  // 本地还没有这一行。只有「新会话抬进列表」那种喊话补得出来——标题和归属得到齐一样，
  // 缺一样就不知道该插进哪个区、显示什么，宁可等静默对齐去服务端取权威的那一份。
  if (p.title === undefined || p.projectId === undefined || sessionStore.keyword) return
  const space = findSpace(p.projectId)
  if (!space) return
  const row = {
    id: sid,
    title: p.title,
    messageCount: p.messageCount ?? 1,
    updateTime: p.updateTime || new Date().toISOString(),
    isPinned: p.isPinned ?? 0,
    isFavorite: p.isFavorite ?? 0,
    shared: p.shared ?? 0,
    projectId: space.id || ''
  }
  space.items.unshift(row)
  space.firstItems.unshift(row)
  space.count = (space.count || 0) + 1
  localRows.add(sid)
  rebuildFlat()
}

/** 别的页签删了会话：按 id 摘行，段首计数与摊平视图一起结算，不留「行没了但 (N) 还是旧的」 */
function applyRemoteDrop (ids) {
  let changed = false
  for (const sid of (ids || []).map(String).filter(Boolean)) {
    for (const space of [...sessionStore.spaces, ...sessionStore.archived]) {
      if (detachRow(space, sid)) { changed = true; break }
    }
  }
  if (changed) rebuildFlat()
}

const RESYNC_DEBOUNCE_MS = 1200
// 页签被浏览器挂起（bfcache）期间喊话会整段丢，切回前台按这条判据自己补一次
const STALE_AFTER_MS = 30000
let resyncInFlight = false
let resyncQueued = false
let resyncTimer = null
let stopAnnounce = null
let onTabVisible = null

/** 排一次静默对齐：合并窗口内的重复喊话（批量删除连着喊好几条，一次对齐就够） */
function queueResync ({ withProjects = false } = {}) {
  clearTimeout(resyncTimer)
  resyncTimer = setTimeout(() => resyncSessions({ withProjects }), RESYNC_DEBOUNCE_MS)
}

/**
 * 后台静默对齐一次——收不懂的喊话、以及切回前台发现数据老了才走这里。
 * 与 loadSessions 的三条区别就是它不打架的原因：
 *   1. 不抬 loadGen：用户主动发起的在途请求照常落地，且它一落地本次结果立即作废；
 *   2. 不置 loading：整栏不闪转圈、不出现「加载中」空态；
 *   3. 工作区对象复用 + 按**当前已铺开的条数**取数并整窗替换：点过的「查看更多」不折回首屏，
 *      游标换成这一窗之后的新游标，接着往下翻仍然接得上。
 * 单个区取数失败时保留上一次的数据、不弹错也不挂「加载失败」：用户没做这个动作，
 * 后台对齐失败冒出来的红字只会把人吓到；真该报错的是他自己点的那一次刷新。
 */
export async function resyncSessions ({ withProjects = false } = {}) {
  if (resyncInFlight) { resyncQueued = true; return }
  // 前台整表刷新正在跑：它拿的就是权威数据，等它落地后再决定要不要补
  if (sessionStore.loading) {
    clearTimeout(resyncTimer)
    resyncTimer = setTimeout(() => resyncSessions({ withProjects }), RESYNC_DEBOUNCE_MS)
    return
  }
  const gen = loadGen
  const kw = sessionStore.keyword
  resyncInFlight = true
  try {
    let projects = projectsCache
    if (withProjects || !projectsLoaded) {
      try {
        projects = ((await listProjects()) || {}).data || []
        projectsCache = projects
        projectsLoaded = true
      } catch (e) { /* 工作区结构这一轮对不了，会话行照常补 */ }
    }
    if (gen !== loadGen) return
    const defs = projects.length
      ? [noSpaceSpace(), ...projects.filter(p => p.status !== 'archived' && !p.isImplicit).map(spaceOfProject)]
      : [allSessionsSpace()]
    // 结构没变的工作区一律沿用原对象：换成新对象等于把展开态与已加载行一起丢掉
    sessionStore.spaces = defs.map(def => sessionStore.spaces.find(s => s.key === def.key) || def)
    const archivedDefs = projects.filter(p => p.status === 'archived' && !p.isImplicit).map(spaceOfProject)
    sessionStore.archived = archivedDefs.map(def => sessionStore.archived.find(s => s.key === def.key) || def)
    const targets = [...sessionStore.spaces, ...(sessionStore.archivedOpen ? sessionStore.archived : [])]
    await Promise.all(targets.map(async space => {
      const head = firstPageSize(space, kw)
      const prevItems = space.items
      try {
        const page = await fetchSpacePage(space, kw, Math.max(head, prevItems.length))
        if (gen !== loadGen) return
        page.items.forEach(s => localRows.delete(s.id))
        space.items = page.items
        space.firstItems = page.items.slice(0, head)
        space.count = page.count
        space.hasMore = page.hasMore
        space.nextCursor = page.nextCursor
        // 收起态下首屏快照就是全部，它的游标跟着一起换；已展开的保留原快照游标，
        // 与既有的「查看更多 → 收起」同口径（那条路本来也不重新请求）
        if (!space.expanded) { space.firstNextCursor = page.nextCursor; space.firstHasMore = page.hasMore }
        carryLocalRows(space, prevItems)
        space.error = ''
        space.loaded = true
      } catch (e) { /* 保留上一次的数据 */ }
    }))
    if (gen !== loadGen) return
    lastSyncAt = Date.now()
    rebuildFlat()
    if (!sessionStore.tasksLoaded) loadTasks()
  } finally {
    resyncInFlight = false
    if (resyncQueued) { resyncQueued = false; queueResync({ withProjects }) }
  }
}

/**
 * 挂上跨页签订阅。壳层（AppLayout）挂载时调一次就够——PC 侧栏与 H5 会话 sheet 读的是同一份 store，
 * 补丁打在 store 上，两端一起跟上。
 */
export function startSessionTabSync () {
  if (stopAnnounce) return
  stopAnnounce = onAnnounce(m => {
    if (m.k === 'row') applyRemoteRow(m)
    else if (m.k === 'drop') applyRemoteDrop(m.ids)
    else if (m.k === 'spaces') queueResync({ withProjects: true })
    else queueResync({})    // 认不出的喊话一律按「列表大概变了」兜底，宁可多对齐一次
  })
  onTabVisible = () => {
    if (document.visibilityState !== 'visible') return
    if (Date.now() - lastSyncAt > STALE_AFTER_MS) resyncSessions({ withProjects: true })
  }
  document.addEventListener('visibilitychange', onTabVisible)
}

export function stopSessionTabSync () {
  if (stopAnnounce) { stopAnnounce(); stopAnnounce = null }
  if (onTabVisible) { document.removeEventListener('visibilitychange', onTabVisible); onTabVisible = null }
  clearTimeout(resyncTimer)
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
    announce({ k: 'row', sid, title: existing.title, updateTime: now, messageCount: existing.messageCount })
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
  localRows.add(sid)
  rebuildFlat()
  // 喊给别的页签：这是「新会话出现在侧栏」的唯一时刻——空会话列表里本来就不露，
  // 等首条消息落库再靠别的页签自己去拉，用户在那个页签里看到的就是「我新建的会话没了」。
  announce({ k: 'row', sid, projectId: space.id || '', title: item.title, updateTime: now, messageCount: 1 })
}

/**
 * 一整轮答完，把这一行按新的更新时间归位（不整表重拉）。
 * <p>服务端每轮都会把会话的 update_time 推到当下，列表又正是按它倒序——本地不跟着重排，
 * 老会话再问一次就停在原位，要等下一次整表刷新才跳顶；而别的页签改一下(updateTime)是会重排的
 * （applyRemoteRow），同两路口径打架。
 * <p>只动已加载出来的行：没加载到的行侧栏本来就看不见，整表刷新时自然由服务端排序放到位。
 * 搜索态不动：那一栏的关键字命中顺序由服务端给定，本地猜不出该插在哪。
 */
export function touchSessionRow (sid) {
  if (!sid || sessionStore.keyword) return
  const hit = locateRow(sid)
  if (!hit) return
  const now = new Date().toISOString()
  hit.space.items[hit.idx].updateTime = now
  const snap = hit.space.firstItems.find(s => s.id === sid)
  if (snap) snap.updateTime = now
  hit.space.items.sort(rowOrder)
  hit.space.firstItems.sort(rowOrder)
  rebuildFlat()
  announce({ k: 'row', sid, updateTime: now })
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
  localRows.add(session.id)
  rebuildFlat()
}
