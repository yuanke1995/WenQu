// 过程块状态行：标题（turnHead）与无三角纯文字行（turnTop）。纯函数，耗时由调用方传入。
import { durZh } from '../utils/duration.js'
import { turnMarker } from './turnStatus.js'
import { turnShow } from './turnFold.js'

/** 终态标注 → 状态行那一档的说法（只此一处，PC/H5/分享页共用）。 */
const TURN_MARKER_HEAD = {
  stopped: '这一轮已停止', budget: '这一轮已到时间上限',
  timeout: '这一轮等待确认超时', truncated: '这一轮中断'
}

export const turnHead = (m, elapsedMs) => {
  const ms = Number(elapsedMs) || 0
  const dur = ms ? '，用时 ' + durZh(ms) : ''
  // 进行中这一句是本轮唯一的状态行：压缩那二十多秒也说在这儿
  if (m && m.loading) return (m.retrying ? '连接中断，正在自动重试' : m.compacting ? '正在压缩上下文' : '正在溯源') + dur + ' …'
  // 终态取正文尾注那一份；叫停/上限/超时都不算「完成」，也不报成「中断」
  const mark = m ? turnMarker(m) : ''
  const spoken = mark === 'truncated' || !!(m && (m.errorCard || m.failed))
  if (spoken) return (TURN_MARKER_HEAD[mark] || '这一轮中断') + dur
  if (mark) return TURN_MARKER_HEAD[mark] + dur
  // 正文为空那一支没有尾注：认引擎与计划卡打下的旗标
  if (m && (m.stoppedTurn || m.planStopped)) return '这一轮已停止' + dur
  return '已完成' + dur
}

/** 已答完且没有过程可展开时用无三角的纯文字行；跑动中走状态行形态。 */
export const turnTop = (m, elapsedMs) => !!(m && !m.loading) && turnShow(m, elapsedMs)
