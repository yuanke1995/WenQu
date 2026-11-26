// @ts-check
// 提问卡动作（actions 层）：一次性批量提交 / 忽略（与超时同语义）。
import { askAnswerText, continuedAnswerPayload } from './askUser.js'

/**
 * 答复送达后让面板多留的这一拍：只为让「已提交」这个态被看见，随后即撤卡。
 * <p>不干等后端那条 askUser 终态帧——它要等框架续跑把工具结算写进事件流，通常还要几秒，
 * 而面板此刻顶替着输入框：一张已交卷的卡占着输入位，想补一句话都没地方打。
 * 与分享页同一口径（ShareChatPage 提交后 700ms 撤面板）。
 */
export const ASK_ANSWERED_SETTLE_MS = 700

/** 答完的卡按一拍节拍撤下；终态帧先撤了（或队列里顶上来下一张）就什么都不做。 */
const retireAnsweredSoon = (m, a, ctx) => {
  // 排队中的下一张不在这里顶上来：终态帧按工具名认卡，本地抢在帧前撤、把下一张暴露出去，
  // 那一帧随后会把它当成自己的卡再撤一次——有队列时仍按帧轮换
  if (Array.isArray(m.askQueue) && m.askQueue.length) return
  const later = ctx.setTimer || ((fn, ms) => setTimeout(fn, ms))
  later(() => {
    if (m.ask === a) ctx.retireAskCard(m)
  }, ASK_ANSWERED_SETTLE_MS)
}

/** 一卡多问批量提交；未作答的题留空，后端把「这一题没答」回给模型。 */
export const submitAsk = async (m, ctx) => {
  const a = m && m.ask
  if (!a || a.busy || a.answered) return
  const answers = a.questions.map((q, i) => askAnswerText(a, i))
  a.busy = true
  try {
    const r = await ctx.answerApi(a.id, answers)
    if (r && r.success === false) {
      ctx.notify.warning(r.msg || '回答提交失败')
      a.busy = false
      // 恢复态卡：本轮已无人接收，撤卡并重载会话
      if (a.restored) { ctx.retireAskCard(m); ctx.stopRestoredPoll(); ctx.switchSession(ctx.currentSid()) }
      return
    }
    ctx.emitNotifChanged('chat')
    // 前台等待已结束的卡（DSH continued）：那次工具调用早已结算，整批答案作为一条用户消息
    // steer 进会话——跑着就插进本轮、空闲就起新一轮，与用户亲手打字同一条出口
    if (a.continued) {
      const payload = continuedAnswerPayload(a)
      ctx.retireAskCard(m)
      ctx.deliverContinuedAnswer(payload)
      return
    }
    a.answered = true
    if (a.restored) {
      // 本设备没有这一轮的流：自己撤卡，气泡交给后台生成并轮询取回
      ctx.retireAskCard(m)
      ctx.pollRestoredAnswer(ctx.currentSid())
      return
    }
    ctx.scrollSoft()
    retireAnsweredSoon(m, a, ctx)
  } catch (e) {
    ctx.notify.error(e.message || '回答提交失败')
    if (m.ask) m.ask.busy = false
  }
}

/** 忽略提问：不作答，让模型带着「这一题用户没回答」继续推进（与超时同语义）。 */
export const ignoreAsk = async (m, ctx) => {
  // 卡片引用先落本地：响应回来时 askUser 终态事件多半已把 m.ask 清空
  const a = m && m.ask
  if (!a || a.busy) return
  a.busy = true
  try {
    const r = await ctx.ignoreApi(a.id)
    if (r && r.success === false) {
      ctx.notify.warning(r.msg || '操作失败')
      a.busy = false
      if (a.restored) { ctx.retireAskCard(m); ctx.stopRestoredPoll(); ctx.switchSession(ctx.currentSid()) }
      return
    }
    ctx.emitNotifChanged('chat')
    if (a.restored) {
      ctx.retireAskCard(m)
      ctx.pollRestoredAnswer(ctx.currentSid())
      return
    }
    a.answered = '（已忽略）'
    ctx.scrollSoft()
  } catch (e) {
    ctx.notify.error(e.message || '操作失败')
    if (m.ask) m.ask.busy = false
  }
}
