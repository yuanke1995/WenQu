// @ts-check
// 待办卡恢复（从 useChatEngine 抽出）：会话加载/切换后，把服务端仍挂着、唤醒句柄还在的
// 提问卡与计划卡重建回界面。审批卡与恢复态轮询共用 restoredPoll，留在主文件。
// 依赖全部显式注入；planCardFromRecord/linkPlanLineage 从 planCards 纯模块引入。
import { reactive } from 'vue'
import { planCardFromRecord, linkPlanLineage } from './planCards.js'

export const usePendingCards = ({ chatStreams, currentSessionId, messages, hooks, notify,
                                  attachAskCard, listPendingAsks, listPendingPlans,
                                  cardTargetFor, msgByTurn }) => {

const hydratePendingAsk = async sid => {
  if (!sid) return
  const st = chatStreams.get(sid)
  let items
  try {
    const r = await listPendingAsks(sid)
    items = (r && r.data && Array.isArray(r.data.items)) ? r.data.items : []
  } catch (e) {
    // 静默失败会被读成「我那条没生效」：恢复入口拉不动要出声（不打断正常问答，只提示）
    if (currentSessionId.value === sid) notify.warning('待答提问恢复失败：' + (e?.message || '网络异常'))
    return
  }
  if (currentSessionId.value !== sid) return   // 快速切会话：晚到响应不覆盖当前视图
  const alive = items.filter(x => x && !x.expired && x.live)
  const dead = items.filter(x => x && !x.expired && !x.live)
  if (!alive.length) {
    // 卡片还在但唤醒句柄已不在（进程重启过）：这一轮没人接，说清楚而不是留个点了没反应的输入框
    if (dead.length) notify.warning('这一轮的提问已失效（服务已重启），回答没有继续生成')
    return
  }
  // 每一张还挂着的卡都要建得出来：并行工具调用能在同一轮挂出两三项，只建末条会漏
  let mounted = 0
  for (const item of alive) if (mountOneAsk(st, item)) mounted++
  if (mounted) hooks.scrollForce?.()
}

/** 挂回单张提问卡：本轮气泡优先，其次该轮已有气泡，最后补占位泡。返回是否挂上。 */
function mountOneAsk (st, item) {
  const target = cardTargetFor(st, item.turnId)
  if (target) return attachAskCard(target, { ...item, restored: true }, item.remainingMs)
  const anchor = (item.turnId && msgByTurn(item.turnId)) || null
  if (anchor) return attachAskCard(anchor, { ...item, restored: true }, item.remainingMs)
  // 这一轮的助手消息要到回答完成才落库，此刻视图里没有可挂的气泡：补一个「等你作答」的占位泡，
  // 答完轮询到新回答后整个列表按历史重载，占位泡随之消失
  const bubble = emptyAiBubble(item.turnId)
  attachAskCard(bubble, { ...item, restored: true }, item.remainingMs)
  messages.value = [...messages.value, bubble]
  return true
}

/** 恢复期补的占位 AI 气泡（这一轮还没落库、视图里没有它时的挂载点）。 */
function emptyAiBubble (turnId) {
  return reactive({ role: 'ai', content: '', images: [], sources: [], related: [],
    degradations: [], warnMsg: '', loading: true, retrying: false, thinking: '',
    thinkLoading: false, time: Date.now(), artifacts: [],
    toolCalls: [], plan: null, timeline: [], errorCard: null, model: '',
    turnId: turnId || '' })
}

const hydratePendingPlans = async sid => {
  if (!sid) return
  const st = chatStreams.get(sid)
  if (st && st.msg && st.msg.planCard) return   // 实时卡还挂在这一轮上，不重复挂
  let items
  try {
    const r = await listPendingPlans(sid)
    items = (r && r.data && Array.isArray(r.data.items)) ? r.data.items : []
  } catch (e) { return }   // 恢复拉取失败不提示：错过仍有超时兜底，不该打断正常浏览
  if (currentSessionId.value !== sid) return   // 快速切会话：晚到响应不覆盖当前视图
  const alive = items.filter(x => x && !x.expired && x.live)
  const dead = items.filter(x => x && !x.expired && !x.live)
  if (!alive.length) {
    if (dead.length) notify.warning('这一轮的执行计划已失效（服务已重启），回答没有继续生成')
    return
  }
  const item = alive[alive.length - 1]
  const card = {
    ...planCardFromRecord(item, 'pending'),
    id: item.planApprovalId,
    restored: true,
    timeoutMs: Number(item.timeoutMs) || 0,
    deadline: item.remainingMs > 0 ? Date.now() + item.remainingMs : 0
  }
  // 挂回它自己那一轮（与提问卡同一判据）
  const target = cardTargetFor(st, item.turnId)
  if (target && target.planCard) return   // 这张卡已经挂着了
  if (target) {
    target.planCard = card
    linkPlanLineage(messages.value)   // 恢复出来的新计划也要把旧卡的「查看新一轮」补上
    hooks.scrollForce?.()
    return
  }
  const list = messages.value
  // 这一轮的助手消息要到回答完成才落库，此刻视图里没有可挂的气泡：补一个「等你批准」的占位泡，
  // 批准卡挂上；批准后轮询到新回答，整个列表按历史重载，占位泡随之消失
  const anchor = reactive({ role: 'ai', content: '', images: [], sources: [], related: [],
    degradations: [], warnMsg: '', loading: true, retrying: false, thinking: '',
    thinkLoading: false, time: Date.now(), artifacts: [],
    toolCalls: [], plan: null, planCard: null, timeline: [], errorCard: null, model: '',
    turnId: item.turnId || '' })
  anchor.planCard = card
  messages.value = [...list, anchor]
  linkPlanLineage(messages.value)
  hooks.scrollForce?.()
}
  return { hydratePendingAsk, hydratePendingPlans }
}
