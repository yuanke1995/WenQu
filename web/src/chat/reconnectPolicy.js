// 传输断线后的重挂策略（纯函数，不读 Vue、不开定时器）。
// 口径与 DSH 一致：连接是连接、轮次是轮次。连接断了只说明「这条通道没了」，
// 服务端那一轮照样在跑；客户端要做的只是按退避重新挂回去，直到挂上或探到那一轮已经收场。

export const RECONNECT_BASE_MS = 500
export const RECONNECT_MAX_MS = 10_000

/**
 * 第 attempt 次重挂前的等待（attempt 从 0 起）：指数退避、封顶后不再增长，
 * 实际等待取 50~100% 的抖动——一批断在同一台代理上的客户端不会齐步砸回去。
 * @param opts.random 注入 [0,1) 随机源，单测用
 */
export const reconnectDelay = (attempt, opts = {}) => {
  const baseMs = Number(opts.baseMs) > 0 ? Number(opts.baseMs) : RECONNECT_BASE_MS
  const maxMs = Number(opts.maxMs) > 0 ? Number(opts.maxMs) : RECONNECT_MAX_MS
  const random = typeof opts.random === 'function' ? opts.random : Math.random
  const n = Math.max(0, Math.floor(Number(attempt) || 0))
  const cap = Math.min(maxMs, baseMs * Math.pow(2, n))
  const r = Number(random())
  const jitter = 0.5 + 0.5 * (Number.isFinite(r) ? Math.min(Math.max(r, 0), 1) : 1)
  return Math.round(cap * jitter)
}

/**
 * 一次重挂尝试（GET /chat/run 探测 + GET /chat/resume 挂载）的结果怎么走：
 *  attached → 已接回，重挂循环结束
 *  idle     → 服务端没有在跑的轮：这一轮已经收场，按历史重显真实结果（不是失败）
 *  其余     → busy（别的路径正在接）/ running（探测得到但没挂上）/ unknown（探测本身失败，多半网还没回来）
 *             一律继续退避重试。unknown 绝不当收场——网断了就按「还在跑」处理，等它回来。
 */
export const reconnectNext = status => {
  if (status === 'attached') return 'attached'
  if (status === 'idle') return 'settle'
  return 'retry'
}
