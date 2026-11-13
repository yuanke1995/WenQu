// 接流入口（transport 层）：探测服务端是否还有在跑的轮，有则开 GET /chat/resume 接上。
// 状态与回调全部注入，便于单测；引擎只提供 chatStreams/生命周期/占位气泡/开流函数。
//
// 返回值是**尝试结果**而不是布尔：调用方要能区分「接上了」「服务端没有在跑的轮」（=这一轮已收场）
// 与「这次没挂上」（别的路径正在接 / 探测本身失败）。终态收尾与是否继续重试全由调用方按状态决定
// （分流规则见 ./reconnectPolicy.js 的 reconnectNext）。
//   attached —— 已开 resume 流
//   idle     —— 服务端没有在跑的轮（或那一轮已收场）：没开流
//   busy     —— 已有流或已有探测在飞，本次什么都没做
//   running  —— 探到在跑，但当前不在这个会话/没开流
//   unknown  —— 探测请求本身失败（多半是网还没回来）
export const attachRunningTurn = async (sid, reuseMsg, ctx) => {
  if (!sid) return { status: 'idle' }
  if (ctx.chatStreams.has(sid) || ctx.lifecycle.isProbing(sid)) return { status: 'busy' }
  let running = false
  let runTurnId = ''
  if (!ctx.lifecycle.beginProbe(sid)) return { status: 'busy' }
  try {
    const r = await ctx.getChatRun(sid)
    running = !!(r && r.data && r.data.running)
    runTurnId = r && r.data && typeof r.data.turnId === 'string' ? r.data.turnId : ''
  } catch (e) {
    return { status: 'unknown' }      // 探测失败不烦用户：由调用方决定重试或按历史回显
  } finally {
    ctx.lifecycle.endProbe(sid)
  }
  // 总线上那条其实已收场（巡检未回收）：不接，否则把旧答案当新一轮重播
  if (running && ctx.lifecycle.isSettled(sid, runTurnId)) running = false
  if (!running) {
    // 探不到在飞的轮：目标还在推进时这是「排队+冷启动」真空，用占位气泡顶上
    ctx.ensureGoalPlaceholder()
    return { status: 'idle' }
  }
  if (ctx.currentSid() !== sid) return { status: 'running' }
  // 已有占位气泡时把同一颗交给这条流；reuseMsg 是通道断了要再接时的残影
  ctx.streamAnswer('', [], reuseMsg || ctx.takeGoalPlaceholder(sid), false, 0, false, [], [], [], null, [], '', null, false, true)
  return { status: 'attached' }
}
