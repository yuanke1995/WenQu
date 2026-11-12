// 本轮计时的纯口径：整轮耗时、准备段、人工等待段、接流起表点、状态行标题。
// 跑动中的秒表需要「现在」，由调用方传入（模块不读任何响应式 .value），方便单测与两端共用。
import { durZh } from '../utils/duration.js'

/** 跑动中按整秒向下取整：读数不超过服务端终值，答完那一刻不会往回掉。 */
export const turnElapsedMs = (m, now) => {
  if (!m) return 0
  if (m.loading) {
    const n = Number(now) || 0
    return Math.floor(Math.max(0, n - (Number(m.time) || n)) / 1000) * 1000
  }
  return Number(m.turnMs) || Number(m.tokens && m.tokens.turnWallMs) || 0
}

export const turnPrepMs = m => {
  if (!m) return 0
  return Number(m.turnPrepMs) || Number(m.tokens && m.tokens.turnPrepMs) || 0
}

export const turnWaitMs = m => {
  if (!m) return 0
  return Number(m.humanWaitMs) || Number(m.tokens && m.tokens.humanWaitMs) || 0
}

/** 接流起表点：服务端快照的已跑时长换算成本地钟上的开轮时刻；缺这个数就维持「从接上起表」。 */
export const resumedStartAt = (elapsedMs, clientNow) => {
  const now = Number(clientNow) || 0
  const ran = Number(elapsedMs) || 0
  return ran > 0 ? Math.max(0, now - ran) : now
}

/** 两个起表点取更早的（0/缺 = 没有那一份）：避免接流后秒数往回跳。 */
export const earlierStart = (had, candidate) => {
  const a = Number(had) || 0
  const b = Number(candidate) || 0
  if (a <= 0) return b
  if (b <= 0) return a
  return Math.min(a, b)
}

export const turnTitle = (m, now) => {
  const ms = turnElapsedMs(m, now)
  if (!ms) return '从发出提问到答完实际用的时间'
  const prep = turnPrepMs(m)
  const wait = turnWaitMs(m)
  const parts = []
  if (prep > 0) parts.push('开始回答前的准备 ' + durZh(prep))
  const rest = Math.max(0, ms - prep - wait)
  if (rest > 0) parts.push('模型与工具 ' + durZh(rest))
  if (wait > 0) parts.push('等你答题或批准 ' + durZh(wait))
  return '从发出提问到答完一共 ' + durZh(ms) + (parts.length ? '，其中' + parts.join('、') : '')
}
