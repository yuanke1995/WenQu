// @ts-check
// 待批准的计划卡 + 倒计时（从 useChatEngine 抽出）。
// 倒着找最后一条还挂着 pending/revising 计划的助手消息；仅待确认卡挂 deadline 时走 1 秒心跳。
import { ref, computed, watch, onUnmounted } from 'vue'

export const usePendingPlan = ({ messages }) => {

/**
 * 待批准的计划卡（两端共用，与提问面板同判据）：倒着找最后一条还挂着 pending 计划的助手消息。
 * <p>计划数据两端都拿得到（hydratePendingPlans 在引擎内），派生放在这里两端共用同一份，
 * 不会出现「卡片在 messages 里、界面上一个字都没有」。
 */
const pendingPlan = computed(() => {
  const list = messages.value
  for (let i = list.length - 1; i >= 0; i--) {
    const m = list[i]
    // 退回重出期间（revising）也必须继续算「待确认」：否则底部确认栏会在升版途中消失，
    // 打字框冒出来，用户可能在模型重做计划的同一轮里又发一条新消息
    if (m && m.role === 'ai' && m.planCard && (m.planCard.status === 'pending' || m.planCard.revising)) return m
  }
  return null
})
const curPlanCard = computed(() => pendingPlan.value?.planCard || null)
// 倒计时心跳（仅待确认卡挂 deadline 时走表，与提问卡的 askNow 同一套节奏）
const planNow = ref(Date.now())
let planTimer = null
watch(() => messages.value.some(m => m.planCard && m.planCard.status === 'pending' && m.planCard.deadline), v => {
  if (v && !planTimer) planTimer = setInterval(() => { planNow.value = Date.now() }, 1000)
  if (!v && planTimer) { clearInterval(planTimer); planTimer = null }
})
onUnmounted(() => { if (planTimer) clearInterval(planTimer) })
// 已过 deadline（后端超时收尾的 plan_cancelled 马上到）：操作锁住，避免批准必然失败的那一下
const planExpired = computed(() => {
  const c = curPlanCard.value
  return !!(c && c.deadline && planNow.value >= c.deadline)
})
const planCountdownText = computed(() => {
  const c = curPlanCard.value
  if (!c || !c.deadline) return ''
  const s = Math.max(0, Math.ceil((c.deadline - planNow.value) / 1000))
  return String(Math.floor(s / 60)).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0')
})
  return { pendingPlan, curPlanCard, planExpired, planCountdownText }
}
