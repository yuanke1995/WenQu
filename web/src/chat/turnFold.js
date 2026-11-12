// 过程块的折叠/可见性判据：纯函数，不读 .value（跑动中的耗时由调用方传入）。
import { turnAnswerText } from './turnBody.js'
import { turnProcessRows } from './turnProcess.js'

/** 过程块折叠态：用户点过就认点过的；跑着恒展开（不认旧开合态）；无正文的轮次默认展开。 */
export const turnOpen = m => {
  if (!m) return false
  if (m.loading) return true
  const marked = m._turnOpen
  if (marked === true) return true
  if (marked === false) return false
  return !turnAnswerText(m) && turnProcessRows(m).length > 0
}

export const toggleTurn = m => { if (m) m._turnOpen = !turnOpen(m) }

/** 底下有没有过程可展开：没有就别挂折叠三角（点开一片空比不挂更坏）。 */
export const turnExpandable = m => !!m && turnProcessRows(m).length > 0

/** 要不要占一行：跑着、有耗时、或确有过程可展开。 */
export const turnShow = (m, elapsedMs) => !!m && (!!m.loading || Number(elapsedMs) > 0 || turnExpandable(m))

/** 只有答完之后才给折叠开关；跑着时这一条只是状态行。 */
export const turnToggleable = (m, elapsedMs) => turnShow(m, elapsedMs) && !!(m && !m.loading) && turnExpandable(m)

/** 连接断了在自动重试：模板据此换警示配色。 */
export const turnWarn = m => !!(m && m.loading && m.retrying)
