// 目标条的文本口径（从 ChatPage.vue 抽出）：已跑时长、轮数、下一版标签。
// 纯 computed，只依赖 sessionGoal（会话级目标对象）。供摘要条与就地编辑确认框使用。
import { computed } from 'vue'

export const useGoalBarText = ({ sessionGoal }) => {
  const goalElapsedText = computed(() => {
    const b = sessionGoal.value && sessionGoal.value.budget
    const ms = Number(b && b.ms) || 0
    if (ms <= 0) return ''
    const s = Math.round(ms / 1000)
    if (s < 60) return `已跑 ${s} 秒`
    const m = Math.floor(s / 60)
    const r = s % 60
    if (m < 60) return `已跑 ${m} 分${r ? ' ' + r + ' 秒' : ''}`
    return `已跑 ${Math.floor(m / 60)} 小时${m % 60 ? ' ' + (m % 60) + ' 分' : ''}`
  })
  const goalRoundsCount = computed(() => {
    const g = sessionGoal.value
    return Number(g && g.budget && g.budget.rounds) || Number(g && g.round) || 0
  })
  const goalNextVersionLabel = computed(() => {
    const n = Number(sessionGoal.value && sessionGoal.value.objectiveVersion) || 0
    return 'v' + (n + 2)
  })
  return { goalElapsedText, goalRoundsCount, goalNextVersionLabel }
}
