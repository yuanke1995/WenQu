// 接流入口（transport 层）：探测服务端是否还有在跑的轮，有则开 GET /chat/resume 接上。
// 状态与回调全部注入，便于单测；引擎只提供 chatStreams/生命周期/占位气泡/开流函数。
export const attachRunningTurn = async (sid, reuseMsg, ctx) => {
  if (!sid) return false
  if (ctx.chatStreams.has(sid) || ctx.lifecycle.isProbing(sid)) return false
  let running = false
  let runTurnId = ''
  if (!ctx.lifecycle.beginProbe(sid)) return false
  try {
    const r = await ctx.getChatRun(sid)
    running = !!(r && r.data && r.data.running)
    runTurnId = r && r.data && typeof r.data.turnId === 'string' ? r.data.turnId : ''
  } catch (e) {
    return false      // 探测失败不烦用户：这一轮按历史回显，下次进会话仍会再探
  } finally {
    ctx.lifecycle.endProbe(sid)
  }
  // 总线上那条其实已收场（巡检未回收）：不接，否则把旧答案当新一轮重播
  if (running && ctx.lifecycle.isSettled(sid, runTurnId)) running = false
  if (!running) {
    // 探不到在飞的轮：目标还在推进时这是「排队+冷启动」真空，用占位气泡顶上
    ctx.ensureGoalPlaceholder()
    return false
  }
  if (ctx.currentSid() !== sid) return false
  // 已有占位气泡时把同一颗交给这条流；reuseMsg 是通道断了要再接时的残影
  ctx.streamAnswer('', [], reuseMsg || ctx.takeGoalPlaceholder(sid), false, 0, false, [], [], [], null, [], '', null, false, true)
  return true
}
