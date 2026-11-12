// 思考帧（纯函数 + 注入）：增量成段/接段、终稿校正、断段信号。
// 思考段与正文分段：正文走节流，思考即时到达——先落正文再记段，否则段会被记在尚未落屏的正文之前。
import { pushTimelineThink, extendTimelineThink } from './timelineWrite.js'

/**
 * @param msg 本轮回答消息
 * @param ctx.flushNow (keepScroll) => void 落屏节流中的正文
 * @param ctx.scroll () => void
 * @param ctx.extendThink/pushThink 段区间推进/新段（默认取 projections 的同一口径）
 */
export const createThoughtFrames = ({ msg, flushNow, scroll, extendThink = extendTimelineThink, pushThink = pushTimelineThink }) => {
  let open = false

  /** 思考增量：接在正在长的段上，或另起一段 */
  const append = t => {
    if (!t) return
    flushNow(false)
    const have = msg.thinking || ''
    if (open) extendThink(msg, have.length, have.length + t.length)
    else pushThink(msg, have.length, have.length + t.length)
    open = true
    msg.thinking = have + t
    msg.thinkLoading = true
    scroll()
  }

  /** 思考终稿：权威全文比本地累积长（接流接缝丢了一片）时，多出来那一截归到刚收场的这一段 */
  const finish = payload => {
    open = false
    msg.thinkLoading = false
    try {
      const j = JSON.parse(payload)
      if (j.thinking && j.thinking.length > (msg.thinking || '').length) {
        const tl = Array.isArray(msg.timeline) ? msg.timeline : []
        const last = tl[tl.length - 1]
        if (last && last.kind === 'think') last.to = j.thinking.length
        msg.thinking = j.thinking
      }
    } catch (e) { /* 无负载（停止生成等） */ }
  }

  /** 正文/工具/过程到达：这一段思考想完了，下一片思考另起一段 */
  const closeRound = () => { open = false }
  /** 接流快照：沿用同一条段（否则下一片增量会另起一段） */
  const setOpen = v => { open = !!v }

  return { append, finish, closeRound, setOpen }
}
