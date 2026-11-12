// 提问卡动作（actions 层）：一次性批量提交 / 忽略（与超时同语义）。
import { askAnswerText } from './askUser.js'

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
    a.answered = true
    if (a.restored) {
      // 本设备没有这一轮的流：自己撤卡，气泡交给后台生成并轮询取回
      ctx.retireAskCard(m)
      ctx.pollRestoredAnswer(ctx.currentSid())
      return
    }
    ctx.scrollSoft()
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
