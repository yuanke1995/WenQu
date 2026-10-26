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
import { notificationList, notificationUnreadCount, notificationMarkRead, notificationMarkAllRead } from '../api'

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
  'eval.decline': ExclamationCircleFilled,
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
  'model.quota': ExclamationCircleFilled
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
  'eval.decline': 'warn',
  'tool.approval': 'warn',
  'tool.ask': 'warn',
  'plan.approval': 'warn',
  'chat.turn.done': 'ok',
  'chat.turn.failed': 'err',
  'goal.achieved': 'ok',
  // 目标停住是「等人补东西或决定继不继续」，不是系统坏了：与审批、提问同一档
  'goal.failed': 'warn',
  'model.quota': 'warn'
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
  'eval.decline': '检索评估下滑',
  'tool.approval': '工具审批待决',
  'tool.ask': '智能体提问待答',
  'plan.approval': '执行计划待确认',
  'chat.turn.done': '一轮跑完',
  'chat.turn.failed': '一轮没跑成',
  'goal.achieved': '目标达成',
  'goal.failed': '目标停住',
  'model.quota': '模型额度不足'
}
export const notifLabel = t => NOTIF_LABELS[t] || t

/** 全部通知类型（标签 + 图标 + 语义色），由上面三张表派生——不再在页面里各抄一份 */
export const NOTIF_TYPES = Object.keys(NOTIF_LABELS)
  .map(t => ({ type: t, label: NOTIF_LABELS[t], icon: notifIcon(t), tone: notifClass(t) }))

// 时间字段稳健解析（ISO/数组/对象三种形态后端都可能给，与会话时间的解析同口径）
const parseTime = v => {
  if (!v) return null
  if (typeof v === 'number') return new Date(v)
  if (typeof v === 'string') { const d = new Date(v); return isNaN(d.getTime()) ? null : d }
  if (Array.isArray(v)) return new Date(v[0], (v[1] || 1) - 1, v[2] || 1, v[3] || 0, v[4] || 0, v[5] || 0)
  if (typeof v === 'object' && v.year) return new Date(v.year, (v.monthValue || 1) - 1, v.dayOfMonth || 1, v.hour || 0, v.minute || 0, v.second || 0)
  return null
}
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
    try {
      const res = await notificationList({ limit })
      items.value = res?.data?.items || []
      unreadCount.value = res?.data?.unreadCount || 0
    } catch (e) { message.error(e.message || '通知加载失败') }
    finally { loading.value = false }
  }
  /** 单条置已读：先本地乐观更新、失败回滚（两端同一语义） */
  const markRead = n => {
    if (!n || n.readFlag) return
    n.readFlag = 1
    unreadCount.value = Math.max(0, unreadCount.value - 1)
    notificationMarkRead([n.id]).catch(() => { n.readFlag = 0; refreshUnread() })
  }
  const markAllRead = async () => {
    try {
      await notificationMarkAllRead()
      items.value = items.value.map(n => ({ ...n, readFlag: 1 }))
      unreadCount.value = 0
    } catch (e) { message.error(e.message || '操作失败') }
  }

  // 切回页面/窗口聚焦立即刷新未读数（登录态失效由全局拦截器接管，这里只静默刷新）
  const onVisible = () => { if (document.visibilityState === 'visible') refreshUnread() }
  // 系统级通知刚弹出来时由 main.js 转成这条事件（否则未读角标要等下一次轮询才跟上，
  // 而「通知都弹了角标还是 0」是最容易被当成 bug 报回来的那种不一致）
  const onAppPush = () => refreshUnread()
  onMounted(() => {
    refreshUnread()
    timer = setInterval(refreshUnread, pollMs)
    window.addEventListener('focus', refreshUnread)
    document.addEventListener('visibilitychange', onVisible)
    window.addEventListener('app:push', onAppPush)
  })
  onUnmounted(() => {
    clearInterval(timer)
    window.removeEventListener('focus', refreshUnread)
    document.removeEventListener('visibilitychange', onVisible)
    window.removeEventListener('app:push', onAppPush)
  })

  return { items, loading, unreadCount, refreshUnread, loadList, markRead, markAllRead }
}
