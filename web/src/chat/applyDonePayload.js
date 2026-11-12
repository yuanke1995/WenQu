// done 载荷合并（纯函数 + 注入）：把服务端那一份落库结果合进本轮气泡。
// 计划卡终态、插话结算、编辑重发回填、工具终态、时间线与显示块都在这里，解析失败按旧版/停止生成处理。

/**
 * @param msg 本轮回答消息
 * @param contentJson done 帧原始载荷
 * @param ctx.sid/resume/prev agentId（本轮会话、是否接流、作答智能体）
 * @param ctx.editUserMsg 编辑重发时的本地用户消息（回填落库 ID）
 * @param ctx.currentSid () => string
 * @param ctx.messages ref
 * @param ctx.planCardFromRecord/applyDoneCore/mergeDoneToolCalls/restoreTimeline/ensureThinkSegment/applyBlocks
 * @param ctx.settleAbortedPlan 计划卡中断收尾
 * @param ctx.linkPlanLineage 计划血缘重连
 * @param ctx.flushPendingSteers 插话队列结算
 * @param ctx.rememberSettled (sid, turnId) => void 记本页已收场
 * @param ctx.liveCompactedSid ref
 * @returns {{sources:any[],related:any[],messageId:any,degradations:any[]}} 交给收尾段的公共字段
 */
export const applyDonePayload = (msg, contentJson, ctx) => {
  let sources = [], related = [], messageId = null, degradations = []
  try {
    const p = JSON.parse(contentJson || '{}')
    sources = Array.isArray(p.sources) ? p.sources : []
    related = Array.isArray(p.related) ? p.related : []
    messageId = p.messageId || null
    degradations = Array.isArray(p.degradations) ? p.degradations : []
    // 计划模式被拒/超时：done 载荷带 planCancelled（plan_cancelled 事件已置过，这里是双保险）；
    // 被「继续对话」取代同理由 planSuperseded 置位。两支都不往正文写收束语——
    // 「未批准 · 已停止本轮」与「已被取代」已经标在计划卡角标上，两句并排是重复
    if (p.planCancelled) msg.planStopped = true
    if (p.planSuperseded) msg.planSuperseded = true
    // 接流时没拿到计划卡（或只拿到 drafting/pending/revising 的中间态）：done 的 planRecord 是
    // 本轮计划的最终落库稿，用它补上/纠正；已有 approved/rejected/superseded 的实时卡不覆盖，
    // 保留本地版本链与执行态。
    if (p.planRecord && typeof p.planRecord === 'object') {
      const finalCard = ctx.planCardFromRecord(p.planRecord, undefined, ctx.sid)
      const cur = msg.planCard
      if (!cur || !['approved', 'rejected', 'superseded'].includes(cur.status)) {
        msg.planCard = finalCard
      }
    }
    // 退回重出没有等到新稿就收尾（后端报错/被停）：解锁并退回原稿状态，别把确认栏锁死
    if (msg.planCard) {
      msg.planCard.busy = false
      if (msg.planCard.revising && !msg.planCard.revisingStarted) {
        msg.planCard.revising = false
        msg.planCard.pendingFeedback = ''
      } else {
        // 半截新稿 / 没写完的首版：卡片落「已停止」，确认栏与输入框随之解锁
        ctx.settleAbortedPlan(msg)
      }
    }
    if (ctx.currentSid() === ctx.sid && msg.planCard) ctx.linkPlanLineage(ctx.messages.value)
    // 插话队列结算：本轮收尾（答完/被停/失败）时结算一次——还没点「立即发送」的在这里
    // 自动作为新一轮发出（本轮已结束，"插进去"没有对象了），纯文本没赶上工具步的由后端回
    // pendingSteers。用户说过的话不许静默丢掉，这是插话机制的底线。
    // 接来的那条流不结算：那些插话是**发起设备**上的人说的，由它自己续发；
    // 在这台只来「看一眼」的设备上自动发出去，等于替别人又问了一遍。
    // origin = 本轮的会话与作答智能体：收尾结算派发的新一轮必须回到发起那一轮的会话——
    // 收尾是异步的（setTimeout），用户此刻可能已经切到别的会话
    if (!ctx.resume) setTimeout(() => ctx.flushPendingSteers(Array.isArray(p.pendingSteers) ? p.pendingSteers : [], { sid: ctx.sid, agentId: ctx.agentId }), 0)
    // 编辑重发：done 带回本轮用户消息的落库 ID——回填到本地新用户消息上，
    // 该消息的 ‹ n/N › 分支切换器与「再次编辑」从此可用
    if (ctx.editUserMsg && p.userMessageId) ctx.editUserMsg.messageId = p.userMessageId
    // 本轮身份：done 带回的 turnId 与刚落库的消息行同源——「过程」入口不该是刷新后才有的东西
    if (p.turnId) msg.turnId = String(p.turnId)
    // 记一笔「这一轮在本页已经收场」：总线那条登记要等巡检回收，别让它被当成还在跑的轮再接一遍
    ctx.rememberSettled(ctx.sid, msg.turnId)
    if (p.tokens && typeof p.tokens === 'object') msg.tokens = p.tokens
    // 本轮压过上下文：按当场收到的压缩起止帧给这一行补上留痕标记。usage/done 两帧都不带
    // compacted（那是非用量键，混进 usage 会让容量面板读到一本空账），只落在库那份里——
    // 不补这一笔，提问上方那条线在答完那一刻（liveCompactAt 的 loading 落了）就消失，
    // 刷新读库才回来，用户读成「压没压过要看运气」。
    if (ctx.liveCompactedSid.value === ctx.sid) msg.tokens = { ...(msg.tokens || {}), compacted: 1 }
    // 用时/正文/配图/模型/思考等公共字段的合并在下面 applyDoneCore 一处做（streamMerge 单源，
    // 与分享页共用；墙钟口径的理由写在那边）
    // 本轮作答方归属（不再有会话锁定，done 的 agentId/agentName 就是这一轮实际作答的智能体）
    if (p.agentId) msg.agentId = p.agentId
    if (p.agentName) msg.agentName = p.agentName
    // 会话内 @ 智能体（§4）：done 的 agentName 是会话绑定口径，本轮由被 @ 的智能体作答时
    // 气泡归属按委派值覆盖（后端落库快照同为委派智能体，刷新后回显一致）
    if (p.delegatedAgentName) {
      msg.agentName = p.delegatedAgentName
      if (p.delegatedAgentId) msg.agentId = p.delegatedAgentId
    }
    // 公共字段一次合并（正文/配图/来源/模型/思考/用时/消息 ID）；返回的 bodyRewritten
    // 吃后端权威宣告（BodyReconcile 一处裁决随 done 下发），旧服务端才回退现场比对
    const bodyRewritten = ctx.applyDoneCore(msg, p)
    msg.thinkLoading = false
    if (Array.isArray(p.artifacts) && p.artifacts.length) msg.artifacts = p.artifacts
    // 每轮文件改动：随 done 下发（本轮没跑/没改动/执行器不在都没有这个键，保留原值不覆盖）
    if (p.changes && typeof p.changes === 'object') msg.changes = p.changes
    // 过程独白以 done 下发的落库版为准（须先于 timeline 恢复赋值：过程段区间指向它）
    if (typeof p.processText === 'string') msg.processText = p.processText
    // done 工具终态原地合并（不整组替换）：保住 timeline 引用与实时到达顺序，终态字段覆盖
    if (Array.isArray(p.toolCalls) && p.toolCalls.length) ctx.mergeDoneToolCalls(msg, p.toolCalls)
    // 时间线以落库版为准：本轮视图与刷新后视图同源（工具/产物下标与终态清单一一对应）
    if (Array.isArray(p.timeline) && p.timeline.length) msg.timeline = ctx.restoreTimeline(msg, p.timeline)
    else if (bodyRewritten) {
      // 正文被引用自检重建、后端没给时间线：只有 text 段的区间失效（正文变了），
      // 思考/过程/工具段各自指向独立的累积文本与卡片对象，仍然有效。
      // 一律清空会让这一轮跑过的操作在答完那一刻整体消失——「已完成」点开只剩一段思考。
      msg.timeline = (Array.isArray(msg.timeline) ? msg.timeline : []).filter(s => s && s.kind !== 'text')
    }
    // 落库时间线没带思考分段（正文被重建导致整条作废、或后端是旧版）：头部补一段，不许看不见
    ctx.ensureThinkSegment(msg)
    // 显示块是终态权威：有它就不再按时间线反推答案；没有则保持上面的旧路径
    if (p.blocks && typeof p.blocks === 'object') {
      ctx.applyBlocks(msg, p.blocks)
      ctx.ensureThinkSegment(msg)
    }
  } catch (e) { /* 旧版/停止生成：无负载 */ }
  return { sources, related, messageId, degradations }
}
