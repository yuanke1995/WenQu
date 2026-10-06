// ==================== 工具审批恢复（通知 → 会话） ====================
// 点开 tool.approval 通知（/chat?sid=&approval=<id>）到达会话时，按 approvalId 重建审批卡：
// 审批是「人在回路」的时限动作，刷新/换设备后那个内联卡就没了，只有这里能把它找回来。
// PC 壳（ChatPage 顶部横幅）与移动壳（MobileChatPage 顶部横幅）共用这一份状态机——
// 「批准/拒绝 → 重取状态」的两端口径必须一致：内存态可能已失效，DB 终态即准。
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { approveToolCall, getToolApproval } from '../api'

export function useApprovalRecovery () {
  const route = useRoute()
  const approval = ref(null)
  const busy = ref(false)
  const statusText = computed(() => {
    const s = approval.value?.status
    return { PENDING: '待处理', APPROVED: '已批准', REJECTED: '已拒绝', TIMEOUT: '已超时' }[s] || (s || '未知')
  })
  const statusClass = computed(() => {
    const s = approval.value?.status
    return s === 'PENDING' ? 'pending' : (s === 'APPROVED' ? 'ok' : 'err')
  })
  const load = async () => {
    const id = route.query.approval
    if (!id) { approval.value = null; return }
    try {
      const r = await getToolApproval(id)
      approval.value = r?.data || null
      if (!approval.value) message.info('该审批请求不存在或已失效（可能已超时）')
    } catch (e) { approval.value = null; message.error('恢复审批失败：' + (e.message || '')) }
  }
  const resolve = async approved => {
    if (!approval.value) return
    busy.value = true
    try {
      await approveToolCall(approval.value.id, approved)
      message.success(approved ? '已批准' : '已拒绝')
      await load()   // 刷新状态：内存态可能已失效，DB 终态即准
    } catch (e) { message.error('审批失败：' + (e.message || '')) }
    finally { busy.value = false }
  }
  const dismiss = () => { approval.value = null }

  onMounted(load)
  watch(() => route.query.approval, load)

  return { approval, busy, statusText, statusClass, load, resolve, dismiss }
}
