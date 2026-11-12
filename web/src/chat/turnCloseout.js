// 收口口径（纯函数）：中断时定格还在跑的工具卡、把「已停止本轮」补到正文尾部。
import { TURN_STOP_SUFFIX } from './turnStatus.js'

/**
 * 把还挂着「进行中」的工具卡就地定格。
 * 提问卡按「未作答」收（那句前缀是 `askAnswerLabel` 的判据，改了它就渲染不出「未作答」）；
 * 其它工具只说「结果没取到」——中断是协作式的，那一步可能已经跑完，只是终态帧赶不上这条通道，
 * 写成「没有执行」就是把一件没把握的事说成事实。耗时宁可不写：前端分不清这段时间里有多少是
 * 人在读卡（后端落的那份是扣掉人工等待的机器时间）。
 */
export const closeRunningTools = (msg, why) => {
  if (!msg || !Array.isArray(msg.toolCalls)) return
  for (const t of msg.toolCalls) {
    if (t.status !== 'start') continue
    const ask = t.name === 'askUser'
    t.status = ask ? 'done' : 'error'
    t.result = ask ? `（用户未回答这一题（${why}））` : `${why}，这一步的结果没有取到`
  }
}

/** 空正文那一支的占位收束语：与「⏹ 已停止本轮」是同一件事的两种说法，只能留一个 */
export const STOP_PLACEHOLDER = '（已停止生成）'

/**
 * 把「已停止本轮」补到这一轮正文尾部。
 * <p>服务端只把它追加在<b>落库</b>的那一份上，而叫停时这条通道已经 abort，终态帧赶不上来——
 * 不就地补这一句，用户要刷新才看得见。
 * <p>只在整轮收尾后补：正文还在流式累积时补，下一帧 flush 会把 `full` 盖回来、把这半句冲掉。
 * <p>/chat/stop 的确认与 done 收尾谁先到不确定（两种顺序都会出）：
 * 占位语已经写上时由正式标注<b>顶掉它</b>，不并列——否则界面上就是「（已停止生成）」与
 * 「已停止本轮」两句并排，而它们说的是同一件事。
 */
export const markStoppedTurn = msg => {
  if (!msg || msg.loading) return
  if (msg.content === STOP_PLACEHOLDER) {
    msg.content = TURN_STOP_SUFFIX.trim()
    return
  }
  if (msg.content === '' || msg.content.endsWith(TURN_STOP_SUFFIX)) return
  msg.content += TURN_STOP_SUFFIX
}
