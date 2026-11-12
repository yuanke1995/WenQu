// 流式文本缓冲（token/过程）：token 只进缓冲，按节流批量刷进响应式与时间线文本区间；
// 终态路径 flushNow 保底，最终态完整、不丢已流出内容。接流去重也在这里（dropResumedOverlap）。
import { dropResumedOverlap } from './streamLifecycle.js'

/**
 * @param msg 本轮回答消息
 * @param ctx.extendText (msg, from, to) => void 推进时间线 text 段区间
 * @param ctx.extendProcess (msg, from, to) => void 推进时间线 process 段区间
 * @param ctx.scroll () => void 跟随滚动（仅 keepScroll=true 时）
 * @param ctx.schedule 定时器（测试可注入）
 * @param ctx.throttleMs 节流窗口，默认 120ms
 */
export const createStreamBuffer = ({ msg, extendText, extendProcess, scroll,
                                     schedule = setTimeout, throttleMs = 120 }) => {
  let full = ''
  let gotToken = false
  let flushedLen = 0
  let flushTimer = null

  const flushNow = (keepScroll = true) => {
    if (flushTimer) { clearTimeout(flushTimer); flushTimer = null }
    if (full.length > flushedLen) {
      extendText(msg, flushedLen, full.length)
      flushedLen = full.length
    }
    if (msg.content !== full) msg.content = full
    // 正文增长必须在这里推进视角：token 不走 scroll（只进缓冲、由这里批量落屏），
    // 少这一下长回答就不再跟着新内容走，新内容滚出屏幕看不见。
    // keepScroll=false：调用方（过程独白增量）随后自己会补一次，避免同一次落屏量两遍 DOM
    if (keepScroll) scroll()
  }

  const flushSoon = () => {
    if (flushTimer) return
    // 包一层：flushNow 带 keepScroll 形参，避免把定时器回调的实参当成它
    flushTimer = schedule(() => flushNow(), throttleMs)
  }

  /** 接流快照铺底：正文以快照为准，已落屏长度与 gotToken 一起对齐 */
  const resetFromContent = content => {
    full = content || ''
    flushedLen = full.length
    gotToken = full.length > 0
  }

  /** 正文 token：接流重叠段丢弃后进缓冲，按节流落屏 */
  const appendToken = (t, ev) => {
    gotToken = true
    full += dropResumedOverlap(full, t, ev)
    flushSoon()
  }

  /** 过程独白：逐条追加即成流式。先 flushNow 落屏节流中的正文——过程事件即时到达，
   *  不先刷正文，过程段会被记在尚未落屏的正文之前，实时视图里灰字块跳到正文上方 */
  const appendProcess = (t, ev) => {
    flushNow(false)
    const have = msg.processText || ''
    const add = dropResumedOverlap(have, t, ev)
    if (!add) return                       // 接流时这一片已被快照覆盖
    msg.processText = have + add
    extendProcess(msg, have.length, msg.processText.length)
    scroll()
  }

  return {
    flushNow,
    flushSoon,
    resetFromContent,
    appendToken,
    appendProcess,
    full: () => full,
    gotToken: () => gotToken
  }
}
