// ==================== 智能体提问恢复（通知 → 会话） ====================
// 点开 tool.ask 通知（/chat?sid=&ask=<id>）到达会话时，按 askId 重建提问卡：
// 提问是「人在回路」的时限动作，刷新/换设备后那个内联卡就没了，只有这里能把它找回来。
// 与工具审批恢复（useApprovalRecovery.js）同构：?ask=<id> 深链 → 取本人记录 → PENDING 可作答；
// 已终态（已回答/超时按推荐项默认）只展示状态。
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { answerAgentAsk, getAgentAsk } from '../api'

export function useAskRecovery () {
  const route = useRoute()
  const ask = ref(null)
  const busy = ref(false)
  const statusText = computed(() => {
    const s = ask.value?.status
    return { PENDING: '待回答', APPROVED: '已回答', TIMEOUT: '已超时·按推荐项默认执行' }[s] || (s || '未知')
  })
  const statusClass = computed(() =>
    (ask.value?.status === 'PENDING' ? 'pending' : (ask.value?.status === 'APPROVED' ? 'ok' : 'err')))
  // requestArgs 为后端落库的 {question, options} JSON；解析失败降级为只提示不可用
  const view = computed(() => {
    let question = null, options = null
    try {
      const j = JSON.parse(ask.value?.requestArgs || 'null')
      question = (j && j.question) || null
      options = j && Array.isArray(j.options) ? j.options : null
    } catch (e) { /* 截断/旧数据 */ }
    return { question, options, answer: ask.value?.answer || '' }
  })
  const load = async () => {
    const id = route.query.ask
    if (!id) { ask.value = null; return }
    try {
      const r = await getAgentAsk(id)
      ask.value = r?.data || null
      if (!ask.value) message.info('该提问不存在或已失效')
    } catch (e) { ask.value = null; message.error('恢复提问失败：' + (e.message || '')) }
  }
  const resolve = async text => {
    const t = (text || '').trim()
    if (!ask.value || busy.value || !t) return
    busy.value = true
    try {
      const r = await answerAgentAsk(ask.value.id, t)
      if (r && r.success === false) { message.warning(r.msg || '回答提交失败'); return }
      message.success('已提交')
      await load()   // 刷新状态：内存态可能已失效，DB 终态即准
    } catch (e) { message.error('回答提交失败：' + (e.message || '')) }
    finally { busy.value = false }
  }
  const dismiss = () => { ask.value = null }

  onMounted(load)
  watch(() => route.query.ask, load)

  return { ask, busy, statusText, statusClass, view, resolve, dismiss }
}
