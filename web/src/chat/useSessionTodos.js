// @ts-check
// 会话任务清单（待办）+ 计划执行进度投影（从 useChatEngine 抽出）。
// 清单是模块单例（store.todosMap），实时由 SSE todo 事件写、切会话/刷新按需回捞；
// 历史轮次清单按 `${sid}|${turnId}` 缓存；计划执行进度实时→账本→快照依次回退。
// 依赖：currentSessionId/messages/loading/panelAi 四个 ref + sessionGoal 惰性 getter（定义在本块之后）。
import { ref, computed, watch } from 'vue'
import { todosMap } from '../views/store'
import { getSessionTodosApi } from './chatTransport.js'
import { planExecProgress, planTodosOfTurn, planAdjustsOf } from './plan'
import { turnSteerRows } from './projections'

export const useSessionTodos = ({ currentSessionId, messages, loading, panelAi, sessionGoalOf }) => {

const sessionTodos = computed(() => todosMap.value[currentSessionId.value] || null)
function setTodos (sid, doc) { todosMap.value = { ...todosMap.value, [sid]: doc } }

/**
 * 历史轮次的任务清单：会话表只留最新一份，旧已批准计划刷新后要靠账本读回它那一轮的 N/M。
 * 按 `${sid}|${turnId}` 缓存，切走再回来不重复请求；拉不到的轮次保持“没有清单”，不拿当前清单冒充。
 */
const turnTodos = ref({})
const turnTodoPending = new Set()
const turnTodoKey = (sid, turnId) => String(sid || '') + '|' + String(turnId || '')
const turnTodosOf = (sid, turnId) => {
  if (!sid || !turnId) return null
  return turnTodos.value[turnTodoKey(sid, turnId)] || null
}
/** 取一张已批准计划所属轮次的清单（无 turnId/不是已批准/已拉到/正在拉都不发请求） */
function ensureTurnTodos (sid, list) {
  if (!sid) return
  const ids = new Set()
  for (const m of (list || [])) {
    if (!m || !m.planCard || m.planCard.status !== 'approved') continue
    if (!m.turnId) continue
    if (sessionTodos.value && sessionTodos.value.turnId === m.turnId) continue
    const key = turnTodoKey(sid, m.turnId)
    if (turnTodos.value[key] || turnTodoPending.has(key)) continue
    ids.add(m.turnId)
  }
  for (const turnId of ids) {
    const key = turnTodoKey(sid, turnId)
    turnTodoPending.add(key)
    getSessionTodosApi(sid, turnId).then(r => {
      if (r && r.success && r.data && r.data.todos) {
        turnTodos.value = { ...turnTodos.value, [key]: r.data.todos }
      }
    }).catch(() => { /* 拉不到就保持无进度：不猜、不拿当前清单顶替 */ })
      .finally(() => turnTodoPending.delete(key))
  }
}

// 新一轮开始写清单后，旧已批准计划的分步进度会被最新一份顶掉：按轮把账本清单补回来
// （只在新一轮出现时触发，同一轮内的实时更新仍走 sessionTodos，不重复拉取）
watch(() => sessionTodos.value && sessionTodos.value.turnId, tid => {
  if (tid) ensureTurnTodos(currentSessionId.value, messages.value)
})

/** 计划执行进度：实时清单（属于这一轮）→ 该轮账本清单 → 收尾冻结快照，依次回退 */
const planExecProgressOf = m => {
  if (!m || !m.planCard) return null
  const sid = currentSessionId.value
  const turnId = m.turnId || ''
  const live = sessionTodos.value
  // 目标已暂停：这一轮即使还在收尾也不再算「正在跑」，卡上不能继续转圈说进行中
  const goalPaused = !!sessionGoalOf().value && sessionGoalOf().value.status === 'PAUSED'
  const running = loading.value && m === panelAi.value && !goalPaused
  if (live && planTodosOfTurn(live, m, messages.value)) {
    return planExecProgress(m.planCard, live, turnId, running, m.planCard.execSnapshot)
  }
  const hist = turnTodosOf(sid, turnId)
  if (hist) return planExecProgress(m.planCard, hist, turnId, false, m.planCard.execSnapshot)
  return planExecProgress(m.planCard, null, turnId, false, m.planCard.execSnapshot)
}
/** 计划卡上的「本轮调整」：仅已批准计划，取本轮时间线里的 steer 段 */
const planAdjustsOfMsg = m => planAdjustsOf(m && m.planCard, turnSteerRows(m))

/**
 * 最新一轮挂着计划卡（深度执行 / 执行计划）：这一轮的进度归卡，底部任务清单不再另占一栏。
 * 计划卡的步骤行读的就是同一份清单，两边都画＝同一份进度说两遍；计划跑完也不放开——
 * 卡收成一行后仍写着「7/7 已完成」，与底部清单完全重复。
 * 未批准的卡不算：那张卡只报「未批准」，步骤与进度都不画，清单是唯一还能看到执行项的地方。
 * 只认最新那条助手消息：旧计划收场、用户又开始普通提问时，新一轮没有计划卡，清单照常露。
 */
const planOwnsTodos = computed(() => {
  const list = messages.value
  let ai = null
  for (let i = list.length - 1; i >= 0; i--) {
    if (list[i] && list[i].role === 'ai') { ai = list[i]; break }
  }
  const c = ai && ai.planCard
  return !!(c && c.status !== 'rejected' && !c.replannedBy && !c.replannedAt)
})

/**
 * 清单计数行：只报非零的几档（「3 已完成 · 1 进行中 · 5 待处理」）。
 * 不报「x/y」那种分数：它看不出进行中那一项是谁，而长任务里用户问的正是「现在卡在哪」。
 */
const todoSummary = computed(() => {
  const items = (sessionTodos.value && sessionTodos.value.items) || []
  const n = s => items.filter(i => i.status === s).length
  const done = n('done')
  const doing = n('doing')
  const failed = n('failed')
  const parts = []
  if (done) parts.push(`${done} 已完成`)
  if (doing) parts.push(`${doing} 进行中`)
  if (failed) parts.push(`${failed} 失败`)
  if (items.length - done - doing - failed > 0) parts.push(`${items.length - done - doing - failed} 待处理`)
  return parts.length ? parts.join(' · ') : `${items.length} 项`
})

/** 清单是否全部收口：全 done 之后它就从「还剩几步」变成「这一轮做成了什么」，
 *  不再是进行中的进度板，去留按「还看不看得到最后一轮」来定（见 todoBarVisible）。 */
const todoAllDone = computed(() => {
  const items = (sessionTodos.value && sessionTodos.value.items) || []
  return items.length > 0 && items.every(i => i.status === 'done')
})

/**
 * 任务清单何时露：
 *  - 未完成：照常跨轮保留（目标模式/长任务靠它接着推，新一轮不自动清）；
 *  - 全 done：留在它自己那一轮里给人看最后一眼，**下一次用户发话（新一轮开始）收场**。
 * 判据是「清单所属轮之后是否已出现用户消息」：轮身份用清单的 turnId 去消息流里认——
 * 实时 done/接流快照与历史回放都会给用户行和回答行盖上同一个 turnId（与落库同源）。
 * 认不出来（存量清单没 turnId、或那一轮的消息已被过滤）按「还没开始新一轮」处理：
 * 宁可多留一条，也不能把正在推进的清单藏掉。
 * <p>两端同吃这一份派生态，页面只留折叠态这种纯 UI 开关。
 */
const todoBarVisible = computed(() => {
  // 这一轮的进度已经在计划卡上，清单不重复占一栏
  if (planOwnsTodos.value) return false
  const doc = sessionTodos.value
  const items = (doc && doc.items) || []
  if (!items.length) return false
  if (!todoAllDone.value) return true
  const tid = doc.turnId
  if (!tid) return true
  const list = messages.value
  let idx = -1
  for (let i = list.length - 1; i >= 0; i--) {
    if (list[i] && list[i].turnId === tid) { idx = i; break }
  }
  if (idx < 0) return true
  for (let i = idx + 1; i < list.length; i++) {
    if (list[i] && list[i].role === 'user') return false
  }
  return true
})
  return {
    sessionTodos, setTodos, turnTodos, turnTodosOf, ensureTurnTodos,
    planExecProgressOf, planAdjustsOfMsg, planOwnsTodos,
    todoSummary, todoAllDone, todoBarVisible,
  }
}
