// 目标条折叠：收起时只留身份行，并撤掉取消确认；进编辑态/换目标时自动展开。
// 从 ChatPage.vue 抽出，只操作入参 ref。
import { watch } from 'vue'

export function useGoalBarCollapse ({ goalOpen, goalCancelArmed, goalEditing, sessionGoal }) {
  function toggleGoalBar () {
    goalOpen.value = !goalOpen.value
    if (!goalOpen.value) goalCancelArmed.value = false
  }

  // 编辑入口不止条子上的按钮（过程抽屉里也能改）：进编辑态一律先摊开
  watch(goalEditing, v => { if (v) goalOpen.value = true })
  // 折叠是「这一条目标」的本地偏好：换到另一条目标时回到展开
  watch(() => sessionGoal.value && sessionGoal.value.goalId, (id, prev) => {
    if (id && id !== prev) goalOpen.value = true
  })

  return { toggleGoalBar }
}
