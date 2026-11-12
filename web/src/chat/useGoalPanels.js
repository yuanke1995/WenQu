// 目标条上的取消二次确认、过程抽屉、交付抽屉，以及随目标/会话变化的收口。
import { ref, computed, watch } from 'vue'
import { goalPanelActionPlan, goalTurnAnchor, goalKeyOf } from './goalPanels.js'

export const useGoalPanels = ctx => {
  const goalCancelArmed = ref(false)
  const goalViewerOpen = ref(false)
  const deliveryOpen = ref(false)
  const deliveryTarget = ref(null)

  const armGoalCancel = () => { goalCancelArmed.value = true }
  const confirmGoalCancel = async () => {
    goalCancelArmed.value = false
    await ctx.runGoalAction('cancel')
  }
  const openGoalViewer = () => { goalViewerOpen.value = true }
  const openDelivery = pkg => {
    if (!pkg) return
    deliveryTarget.value = pkg
    deliveryOpen.value = true
  }
  const onGoalViewerAction = async action => {
    const plan = goalPanelActionPlan(action)
    if (plan.closeBefore) goalViewerOpen.value = false
    if (plan.inlineEdit) { ctx.startGoalInlineEdit(); return }
    if (plan.armCancel) { goalCancelArmed.value = true; return }
    await ctx.runGoalAction(action)
    if (plan.closeAfter) goalViewerOpen.value = false
  }
  const jumpToGoalTurn = turnId => {
    goalViewerOpen.value = false
    const anchor = goalTurnAnchor(ctx.messages(), turnId)
    if (!anchor) return
    ctx.scrollToRow(anchor.userIdx)
  }

  watch(computed(() => goalKeyOf(ctx.goal())), () => { goalCancelArmed.value = false })
  // 抽屉读的是「当前会话的当前目标」；换会话不关会拿 B 的会话号去读 A 的目标账本。
  watch(ctx.currentSessionId, () => {
    goalViewerOpen.value = false
    deliveryOpen.value = false
    deliveryTarget.value = null
  })

  return { goalCancelArmed, goalViewerOpen, deliveryOpen, deliveryTarget,
           armGoalCancel, confirmGoalCancel, openGoalViewer, openDelivery,
           onGoalViewerAction, jumpToGoalTurn }
}
