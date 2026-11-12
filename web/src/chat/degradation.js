// 降级/警示条的展示口径：同一件事在这一屏只画一遍（不消音）。
import { turnMarker } from './turnStatus.js'

/** 「这一轮没答成」那几条降级 code（后端 emitRunFailure 同时走 degrade 与 error 帧两条通道）。 */
const FAIL_SPOKEN_CODES = new Set(['streamError', 'modelQuotaExhausted', 'tenantQuotaExhausted',
  'modelUnresponsive', 'upstreamStreamCut', 'frameworkNotLaunched'])

/** 降级条该画哪几条：这一行自己已挂错误卡时，失败原因由卡说，不在条上再画一遍。
 *  判据只认这一行的错误卡，不认 m.failed——分享页失败没有错误卡，拿 failed 当判据会删掉唯一出口。 */
export const degradationItems = m => {
  const list = Array.isArray(m && m.degradations) ? m.degradations : []
  if (!m || !m.errorCard) return list
  return list.filter(d => !(d && FAIL_SPOKEN_CODES.has(d.code)))
}

/** 与正文尾注说同一件终态的那几句警示（后端 warn 帧与尾注两条通道措辞不同）。 */
const TERMINAL_WARN_LINES = ['本轮已超时结束，等你确认的那一步已作废']

/** 警示条那一句要不要单列：同一件终态正文里已经写过了就不再并排画第二遍。 */
export const degradationWarn = m => {
  const w = m && typeof m.warnMsg === 'string' ? m.warnMsg.trim() : ''
  if (!w) return ''
  const c = m && typeof m.content === 'string' ? m.content : ''
  if (c.includes(w)) return ''
  if (TERMINAL_WARN_LINES.includes(w) && turnMarker(m) === 'timeout') return ''
  return w
}
