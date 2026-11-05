// 工具审批卡的共享口径：状态文案、参数展示与剩余秒数。
// PC（ChatPage）与移动壳（MobileMsgRow）同吃这一份，避免两端对同一个状态各写一遍。

export const approvalStatusText = status => ({
  PENDING: '待处理',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
  TIMEOUT: '已超时',
  REVISED: '已退回重出',
  SUPERSEDED: '已被取代',
  STOPPED: '已随本轮停止',
  INTERRUPTED: '已随本轮中断'
}[status] || (status || '未知'))

/** 空参数（含落库的 "{}"）不渲染参数块：一张只写「{}」的空框没有信息量 */
export const approvalArgsText = args => {
  const s = args == null ? '' : String(args).trim()
  return s === '' || s === '{}' ? '' : s
}

/** 剩余秒数（向上取整，到点即 0）；卡上没有 deadline 的按 0 处理 */
export const approvalLeftSec = (card, now) => {
  if (!card || !card.deadline) return 0
  return Math.max(0, Math.ceil((card.deadline - now) / 1000))
}
