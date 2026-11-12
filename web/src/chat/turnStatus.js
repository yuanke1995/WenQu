// 本轮终态标注的原文（与后端 TurnRecorder 同一组常量，逐字一致）与识别。
// 认的是我们自己写下的整串字面量，不拿关键词猜——猜出来的判据会在文案改字那天静默失效。

export const TURN_STOP_SUFFIX = '\n\n> ⏹ 已停止本轮，以上为已生成的部分'
export const TURN_TRUNC_SUFFIX = '\n\n> ⏹ 回答在此处被中断，以上为已生成的部分'
export const TURN_BUDGET_SUFFIX = '\n\n> ⏹ 已到整轮时间上限，后面的步骤没有执行。发一句「继续」可以接着做'
export const TURN_TIMEOUT_SUFFIX = '\n\n> ⏹ 等待确认超时，这一轮没有继续执行'

export const turnMarker = m => {
  const c = m && typeof m.content === 'string' ? m.content : ''
  if (!c) return ''
  if (c.endsWith(TURN_STOP_SUFFIX)) return 'stopped'
  if (c.endsWith(TURN_BUDGET_SUFFIX)) return 'budget'
  if (c.endsWith(TURN_TIMEOUT_SUFFIX)) return 'timeout'
  if (c.endsWith(TURN_TRUNC_SUFFIX)) return 'truncated'
  return ''
}
