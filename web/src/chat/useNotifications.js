// ==================== 站内通知：共用取数口径 ====================
// PC 铃铛（AppLayout 侧栏 foot 的 popover）与移动壳（顶栏铃铛 → 通知 sheet）共用这一份：
// 未读数 30s 轮询 + 列表拉取 + 乐观置已读（失败回滚）。
// 两端只差呈现与「点开通知后跳哪」——故这里只给数据与读状态，**不含任何 router 语义**
//（审批/会话/知识库的深链落点各端自持：PC 跳 /chat，移动壳跳 /m/chat）。
// 后端触发面：解析终态、工作流失败/超时/挂起待审核、网页源刷新失败、定时任务终态、
// 检索评估下滑预警、模型额度不足，以及问答运行时的三类：**一轮收口**（跑完/没跑成/提前停了）、
// **人在回路待决**（提问卡、工具审批卡、计划确认卡）、**目标终态**（达成/停住）。
// 接收人=资源归属人（问答与目标取会话行上的归属人；平台预警=管理员）。
import { onMounted, onUnmounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { BellOutlined, CheckCircleFilled, CloseCircleFilled, ExclamationCircleFilled, TrophyFilled, FlagFilled } from '@ant-design/icons-vue'
import { notificationList, notificationUnreadCount, notificationMarkRead, notificationMarkAllRead,
  notificationMarkUnread, notificationDelete } from '../api'

/** 类型 → 图标/语义色：成功绿、失败红、等待/超时黄 */
export const NOTIF_ICONS = {
  'parse.done': CheckCircleFilled,
  'parse.failed': CloseCircleFilled,
  'parse.batch.failed': CloseCircleFilled,
  'workflow.failed': CloseCircleFilled,
  'workflow.timeout': ExclamationCircleFilled,
  'workflow.approval': ExclamationCircleFilled,
  'web.refresh.failed': CloseCircleFilled,
  'schedule.done': CheckCircleFilled,
  'schedule.failed': CloseCircleFilled,
  'schedule.expired': ExclamationCircleFilled,
  'eval.decline': ExclamationCircleFilled,
  'agentEval.done': CheckCircleFilled,
  'agentEval.failed': CloseCircleFilled,
  'tool.approval': ExclamationCircleFilled,
  'tool.ask': ExclamationCircleFilled,
  'plan.approval': ExclamationCircleFilled,
  'chat.turn.done': CheckCircleFilled,
  'chat.turn.failed': CloseCircleFilled,
  // 目标是跨轮的一件事，图标要与「一轮跑完」分得开：奖杯=达成了，旗子=它自己停下了
  'goal.achieved': TrophyFilled,
  'goal.failed': FlagFilled,
  // 额度不足是「需要人处理」而非「系统故障」：用警告色而非错误色——
  // 红叉会让人以为是 bug，实际是账户该充值了
  'ops.alert': CloseCircleFilled,
  'model.quota': ExclamationCircleFilled,
  // 租户开通：申请要处理、已开通是回执
  'tenant.request': ExclamationCircleFilled,
  'tenant.approved': CheckCircleFilled,
  'quota': ExclamationCircleFilled,
  // 数据看板：阈值告警要人看，周报是定期回执
  'dashboard.alert': ExclamationCircleFilled,
  'dashboard.weekly': CheckCircleFilled
}
const NOTIF_TONES = {
  'parse.done': 'ok',
  'parse.failed': 'err',
  'parse.batch.failed': 'err',
  'workflow.failed': 'err',
  'workflow.timeout': 'warn',
  'workflow.approval': 'warn',
  'web.refresh.failed': 'err',
  'schedule.done': 'ok',
  'schedule.failed': 'err',
  'schedule.expired': 'warn',
  'eval.decline': 'warn',
  'agentEval.done': 'ok',
  'agentEval.failed': 'err',
  'tool.approval': 'warn',
  'tool.ask': 'warn',
  'plan.approval': 'warn',
  'chat.turn.done': 'ok',
  'chat.turn.failed': 'err',
  'goal.achieved': 'ok',
  // 目标停住是「等人补东西或决定继不继续」，不是系统坏了：与审批、提问同一档
  'goal.failed': 'warn',
  'ops.alert': 'err',
  'model.quota': 'warn',
  'tenant.request': 'warn',
  'tenant.approved': 'ok',
  'quota': 'warn',
  'dashboard.alert': 'warn',
  'dashboard.weekly': 'ok'
}
export const notifIcon = t => NOTIF_ICONS[t] || BellOutlined
export const notifClass = t => NOTIF_TONES[t] || ''

/**
 * 类型 → 中文标签。三处共用同一批词（铃铛/通知 sheet 的列表行、通知中心的筛选下拉、
 * 个人设置里「收哪些类」那一屏），顺序就是用户读通知时的自然分组顺序。
 * 新增通知类型时必须同时补这里——不补的话界面上会露出裸类型常量（如 chat.turn.done）。
 */
export const NOTIF_LABELS = {
  'parse.done': '解析完成',
  'parse.failed': '解析失败',
  'parse.batch.failed': '批量解析失败',
  'workflow.failed': '工作流失败',
  'workflow.timeout': '工作流超时',
  'workflow.approval': '工作流待审核',
  'web.refresh.failed': '网页源刷新失败',
  'schedule.done': '定时任务完成',
  'schedule.failed': '定时任务失败',
  'schedule.expired': '定时任务到期停用',
  'eval.decline': '检索评估下滑',
  'agentEval.done': '评测完成',
  'agentEval.failed': '评测失败',
  'tool.approval': '工具审批待决',
  'tool.ask': '智能体提问待答',
  'plan.approval': '深度执行计划待确认',
  'chat.turn.done': '一轮跑完',
  'chat.turn.failed': '一轮没跑成',
  'goal.achieved': '目标达成',
  'goal.failed': '目标停住',
  'ops.alert': '巡检异常',
  'model.quota': '模型额度不足',
  // 租户两类拆开：申请要处理，已开通只是回执
  'tenant.request': '租户开通申请',
  'tenant.approved': '租户已开通',
  'quota': '用量配额触顶',
  'dashboard.alert': '看板预警',
  'dashboard.weekly': '看板周报'
}
export const notifLabel = t => NOTIF_LABELS[t] || t

/** 全部通知类型（标签 + 图标 + 语义色），由上面三张表派生——不再在页面里各抄一份 */
export const NOTIF_TYPES = Object.keys(NOTIF_LABELS)
  .map(t => ({ type: t, label: NOTIF_LABELS[t], icon: notifIcon(t), tone: notifClass(t) }))

// ==================== 语义桶：待处理 / 异常 / 完成 ====================
// 与 NOTIF_LABELS 同为单一事实源：新增类型必须同时补标签与桶。
// 顺序就是界面上的页签顺序；info 是兜底（未知新类型），只进「全部」。
export const NOTIF_BUCKETS = {
  action: {
    label: '待处理',
    tone: 'warn',
    types: ['tool.approval', 'tool.ask', 'plan.approval', 'workflow.approval',
            'model.quota', 'quota', 'tenant.request']
  },
  alert: {
    label: '异常',
    tone: 'err',
    types: ['parse.failed', 'parse.batch.failed', 'workflow.failed', 'workflow.timeout',
            'web.refresh.failed', 'schedule.failed', 'eval.decline', 'chat.turn.failed',
            'goal.failed', 'ops.alert', 'dashboard.alert']
  },
  done: {
    label: '完成',
    tone: 'ok',
    types: ['parse.done', 'schedule.done', 'schedule.expired', 'chat.turn.done', 'goal.achieved', 'tenant.approved', 'dashboard.weekly']
  }
}
export const NOTIF_BUCKET_ORDER = ['action', 'alert', 'done']
const BUCKET_BY_TYPE = Object.fromEntries(
  Object.entries(NOTIF_BUCKETS).flatMap(([bucket, meta]) => meta.types.map(t => [t, bucket]))
)
export const notifBucket = t => BUCKET_BY_TYPE[t] || 'info'
export const notifBucketLabel = bucket => NOTIF_BUCKETS[bucket]?.label || ''

// ==================== 文案清洗与主体抽取 ====================
// 与后端 NotificationService.clean 同一套规则：URL 去 query/fragment，超长只留 host/…
// 历史脏数据在渲染层再洗一遍；新数据落库前已清洗，这里幂等。
const URL_RE = /https?:\/\/[^\s，。；、）)】」》]+/g
export function notifCleanText (s) {
  if (!s) return ''
  let t = String(s).replace(/\s+/g, ' ').trim()
  t = t.replace(URL_RE, m => {
    const base = m.split(/[?#]/)[0].replace(/^https?:\/\//, '')
    return base.length > 48 ? base.split('/')[0] + '/…' : base
  })
  return t.length > 240 ? t.slice(0, 240) + '…' : t
}

const firstSegment = s => (s || '').split(' · ')[0]

/** 组标题主体：会话取 content 第一段；工作流/供应商从 title 提名字；其余用 title */
export function notifSubject (n) {
  const content = notifCleanText(n?.content)
  const title = notifCleanText(n?.title)
  if (n?.refType === 'session' && content) return firstSegment(content)
  if (n?.refType === 'workflow') {
    return title.match(/工作流「[^」]+」/)?.[0] || firstSegment(content) || title
  }
  if (n?.refType === 'provider') {
    return title.match(/模型供应商「[^」]+」/)?.[0] || firstSegment(content) || title
  }
  return firstSegment(content) || title
}

/** 组副标题明细：content 第二段起；没有则用 title */
export function notifDetail (n) {
  const content = notifCleanText(n?.content)
  const parts = content.split(' · ')
  return parts.length > 1 ? parts.slice(1).join(' · ') : notifCleanText(n?.title)
}

// ==================== 深链：三端唯一事实源 ====================
// PC 与移动壳只差 /chat ↔ /m/chat 前缀；审批类带 refSub 直达可裁决位置。
export function notifTarget (n, { mobile = false } = {}) {
  if (!n) return null
  const chatPath = mobile ? '/m/chat' : '/chat'
  if (n.type === 'workflow.approval' && n.refId && n.refSub) {
    return { path: '/agents', query: { tab: 'workflow', wf: n.refId, run: n.refSub } }
  }
  if (n.type === 'tool.approval' && n.refId && n.refSub) {
    return { path: chatPath, query: { sid: n.refId, approval: n.refSub } }
  }
  if (n.refType === 'kb' && n.refId) return `/knowledge/${n.refId}/docs`
  if (n.refType === 'workflow' && n.refId) return { path: '/agents', query: { tab: 'workflow' } }
  if (n.refType === 'session' && n.refId) return { path: chatPath, query: { sid: n.refId } }
  if (n.refType === 'provider' && n.refId) return { path: '/agents', query: { tab: 'providers' } }
  if (n.refType === 'ops') return '/security'
  if (n.refType === 'dashboard') return { path: '/quality', query: { view: 'dashboard', tab: 'overview' } }
  if (n.type === 'tenant.request') {
    return { path: '/tenants', query: { tab: 'requests' } }
  }
  if (n.refType === 'tenant' || n.type === 'tenant.approved') {
    return { path: '/tenants', query: { tab: 'tenants' } }
  }
  if (n.refType === 'quota' || n.type === 'quota') {
    return { path: '/quality', query: { view: 'dashboard', tab: 'cost' } }
  }
  return null
}

// ==================== 展示层聚合：只折叠，不合并数据 ====================
/**
 * 仅「待处理」桶、且有 refType+refId 的条目参与聚合；同一 key ≥2 条折成一组。
 * 每条仍保留自己的 refSub，展开后各自深链；组头只负责概览。
 */
export function groupNotifications (items, { group = true } = {}) {
  const list = items || []
  if (!group) return list.map(item => ({ kind: 'single', item }))
  const grouped = new Map()
  const out = []
  for (const item of list) {
    const key = item?.refType && item?.refId ? `${item.refType}|${item.refId}` : null
    // 已处理（actionable=false）不参与聚合：它不再等人操作，没必要和待处理项挤在一起
    if (key && notifBucket(item.type) === 'action' && item.actionable !== false) {
      let g = grouped.get(key)
      if (!g) {
        g = { kind: 'group', key, items: [], latest: item }
        grouped.set(key, g)
        out.push(g)
      }
      g.items.push(item)
      if ((parseTime(item.createTime)?.getTime() || 0) > (parseTime(g.latest.createTime)?.getTime() || 0)) {
        g.latest = item
      }
    } else {
      out.push({ kind: 'single', item })
    }
  }
  // 只有 1 条的组退回单条样式
  return out.map(g => (g.kind === 'group' && g.items.length < 2)
    ? { kind: 'single', item: g.items[0] }
    : g)
}

/** 跨端角标同步：中心页操作 → 浮层/sheet 角标；浮层操作 → 中心页列表 */
export function emitNotifChanged (source) {
  try {
    window.dispatchEvent(new CustomEvent('app:notif-changed', { detail: { source } }))
  } catch { /* 非浏览器环境忽略 */ }
}

// 时间字段稳健解析（ISO/数组/对象三种形态后端都可能给，与会话时间的解析同口径）
const parseTime = v => {
  if (!v) return null
  if (typeof v === 'number') return new Date(v)
  if (typeof v === 'string') { const d = new Date(v); return isNaN(d.getTime()) ? null : d }
  if (Array.isArray(v)) return new Date(v[0], (v[1] || 1) - 1, v[2] || 1, v[3] || 0, v[4] || 0, v[5] || 0)
  if (typeof v === 'object' && v.year) return new Date(v.year, (v.monthValue || 1) - 1, v.dayOfMonth || 1, v.hour || 0, v.minute || 0, v.second || 0)
  return null
}
/** 时间字段 → Date（与 notifTime 同口径；供日期分段使用） */
export const notifDate = v => parseTime(v)

/** 相对时间：一周内「x 分钟/小时/天前」，更早给日期时间 */
export const notifTime = v => {
  const d = parseTime(v)
  if (!d) return ''
  const diff = Date.now() - d.getTime()
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  if (diff < 7 * 86_400_000) return `${Math.floor(diff / 86_400_000)} 天前`
  const p = n => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/**
 * 通知取数（列表 + 未读数轮询）。**一个页面只应调用一次**：
 * PC 由 AppLayout 调用（铃铛 popover 与侧栏未读共用），移动壳由 MobileChatPage 调用后
 * 经 provide('wqNotif') 给通知 sheet——各调一次会造出两份未读数，红点与列表会各说各话。
 */
export function useNotifications ({ limit = 50, pollMs = 30000 } = {}) {
  const items = ref([])
  const loading = ref(false)
  const unreadCount = ref(0)
  const error = ref('')
  let timer = null

  // 未读数轮询：失败静默（登录态失效由 app:unauthorized 全局接管，轮询自身不弹错）
  const refreshUnread = async () => {
    try {
      const res = await notificationUnreadCount()
      // request() 返回响应信封 {success,code,msg,data}，业务体在 data 里（与 store.js 取值口径一致）
      unreadCount.value = res?.data?.count || 0
    } catch { /* 下一轮再试 */ }
  }
  const loadList = async () => {
    loading.value = true
    error.value = ''
    try {
      const res = await notificationList({ limit })
      items.value = res?.data?.items || []
      unreadCount.value = res?.data?.unreadCount || 0
    } catch (e) {
      error.value = e.message || '通知加载失败'
      message.error(error.value)
    } finally { loading.value = false }
  }
  /** 单条置已读：先本地乐观更新、失败回滚（两端同一语义） */
  const markRead = n => {
    if (!n || n.readFlag) return
    n.readFlag = 1
    unreadCount.value = Math.max(0, unreadCount.value - 1)
    notificationMarkRead([n.id]).catch(() => { n.readFlag = 0; refreshUnread() })
  }
  /** 标记未读：先本地乐观更新、失败回滚（用于「稍后再看」） */
  const markUnread = async n => {
    if (!n || !n.readFlag) return
    n.readFlag = 0
    unreadCount.value += 1
    try {
      await notificationMarkUnread([n.id])
    } catch (e) {
      n.readFlag = 1
      unreadCount.value = Math.max(0, unreadCount.value - 1)
      message.error(e.message || '标记未读失败')
      refreshUnread()
    }
  }
  /** 批量置已读：手机批量管理用；失败回滚并刷新未读数 */
  const markManyRead = async ids => {
    if (!ids?.length) return
    const set = new Set(ids)
    const wasUnread = items.value.filter(n => set.has(n.id) && !n.readFlag).length
    try {
      await notificationMarkRead(ids)
      items.value.forEach(n => { if (set.has(n.id)) n.readFlag = 1 })
      unreadCount.value = Math.max(0, unreadCount.value - wasUnread)
      emitNotifChanged('widget')
    } catch (e) {
      message.error(e.message || '标记已读失败')
      refreshUnread()
    }
  }
  /** 删除单条：乐观移除；失败恢复原列表并提示 */
  const remove = async n => {
    if (!n) return false
    const snapshot = items.value
    items.value = items.value.filter(x => x.id !== n.id)
    if (!n.readFlag) unreadCount.value = Math.max(0, unreadCount.value - 1)
    try {
      await notificationDelete([n.id])
      return true
    } catch (e) {
      items.value = snapshot
      if (!n.readFlag) unreadCount.value += 1
      message.error(e.message || '删除失败')
      return false
    }
  }
  /** 批量删除：手机批量管理用；失败回滚 */
  const removeMany = async ids => {
    if (!ids?.length) return false
    const snapshot = items.value
    const removedUnread = items.value.filter(n => ids.includes(n.id) && !n.readFlag).length
    items.value = items.value.filter(n => !ids.includes(n.id))
    unreadCount.value = Math.max(0, unreadCount.value - removedUnread)
    try {
      await notificationDelete(ids)
      return true
    } catch (e) {
      items.value = snapshot
      unreadCount.value += removedUnread
      message.error(e.message || '删除失败')
      return false
    }
  }
  const markAllRead = async () => {
    try {
      await notificationMarkAllRead()
      items.value = items.value.map(n => ({ ...n, readFlag: 1 }))
      unreadCount.value = 0
      // 浮层/sheet 里点「全部已读」后，通知中心页要跟着刷新
      emitNotifChanged('widget')
    } catch (e) { message.error(e.message || '操作失败') }
  }

  // 切回页面/窗口聚焦立即刷新未读数（登录态失效由全局拦截器接管，这里只静默刷新）
  const onVisible = () => { if (document.visibilityState === 'visible') refreshUnread() }
  // 系统级通知刚弹出来时由 main.js 转成这条事件（否则未读角标要等下一次轮询才跟上，
  // 而「通知都弹了角标还是 0」是最容易被当成 bug 报回来的那种不一致）
  const onAppPush = () => refreshUnread()
  // 任一端操作完成后都刷新未读数：中心页改已读、浮层/sheet 改已读都让各处角标立刻一致。
  // 这里只刷未读数、不再广播，不会形成事件回环。
  const onChanged = () => refreshUnread()
  onMounted(() => {
    refreshUnread()
    timer = setInterval(refreshUnread, pollMs)
    window.addEventListener('focus', refreshUnread)
    document.addEventListener('visibilitychange', onVisible)
    window.addEventListener('app:push', onAppPush)
    window.addEventListener('app:notif-changed', onChanged)
  })
  onUnmounted(() => {
    clearInterval(timer)
    window.removeEventListener('focus', refreshUnread)
    document.removeEventListener('visibilitychange', onVisible)
    window.removeEventListener('app:push', onAppPush)
    window.removeEventListener('app:notif-changed', onChanged)
  })

  return { items, loading, unreadCount, error, refreshUnread, loadList, markRead, markAllRead,
    markUnread, markManyRead, remove, removeMany }
}
