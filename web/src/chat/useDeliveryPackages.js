// 交付包投影（产物 → 一个可验收的交付对象）：目标包 / 逐轮包 / 消息↔包索引 / ZIP 下载。
// 从 useChatEngine 抽出：这些是纯投影 + 一个下载动作，依赖只有 messages/sessionGoal 与两个下载入口。
import { computed } from 'vue'
import { goalDeliveryOf, turnDeliveriesOf, deliveriesByMessage } from './deliveries.js'
import { downloadDeliveryPackage } from './deliveryDownload.js'
import { deliveryCardOf, deliverySubjectOf } from './projections'

export const useDeliveryPackages = ({ messages, sessionGoalOf, downloadArtifactsZip, notify }) => {
  const goalDelivery = computed(() => goalDeliveryOf(messages.value, sessionGoalOf()))

  const turnDeliveries = computed(() => turnDeliveriesOf(messages.value, {
    currentGoalId: sessionGoalOf() ? sessionGoalOf().goalId : '',
    currentTurns: goalDelivery.value ? goalDelivery.value.turnIds : []
  }))

  const deliveryPackages = computed(() => {
    const out = []
    if (goalDelivery.value) out.push(goalDelivery.value)
    for (let i = turnDeliveries.value.length - 1; i >= 0; i--) out.push(turnDeliveries.value[i])
    return out
  })

  const deliveryByMsg = computed(() => deliveriesByMessage(goalDelivery.value, turnDeliveries.value))

  const deliveryOfMessage = m => (m && deliveryByMsg.value.get(m)) || null
  const deliveryCardOfMessage = m => deliveryCardOf(deliveryOfMessage(m))
  const freshDeliveryOf = m => {
    const id = m && m.freshArtifactId
    if (!id) return false
    const d = deliveryCardOfMessage(m)
    return !!(d && Array.isArray(d.files) && d.files.some(f =>
      String((f && f.id) || '') === String(id) || String((f && f.filename) || '') === String(id)))
  }

  /** 一轮交付包的主语：取该轮之前最近一条用户问题（暂无 UI 接线，保留口径） */
  function turnSubjectOf (aiIndex) {
    for (let j = aiIndex - 1; j >= 0; j--) {
      const u = messages.value[j]
      if (u && u.role === 'user' && String(u.content || '').trim()) {
        return deliverySubjectOf(String(u.content).replace(/[`*#>]/g, '').trim())
      }
    }
    return ''
  }

  const downloadDeliveryZip = async delivery => {
    const pkg = delivery && delivery.files ? delivery : goalDelivery.value
    try {
      await downloadDeliveryPackage(pkg, downloadArtifactsZip)
    } catch (e) {
      notify.error((e && e.message) || '打包下载失败')
    }
  }

  return {
    goalDelivery, turnDeliveries, deliveryPackages, deliveryByMsg,
    deliveryOfMessage, deliveryCardOfMessage, freshDeliveryOf, turnSubjectOf, downloadDeliveryZip,
  }
}
