// 一轮问答的 SSE 回调表（从 useChatEngine 的 streamAnswer 外提）。
//
// 拆两个工厂：createFrameHandlers（逐帧：turn_start/snapshot/token/工具卡/计划帧…）
// 与 createClosingHandlers（收尾：done/警告/压缩进度/错误与重试）。
// 二者都接收「引擎依赖 deps」与「本轮局部 turn」，返回回调表的一半；
// streamAnswer 里用 { ...createFrameHandlers(deps, turn), ...createClosingHandlers(deps, turn) } 拼回。
//
// 为什么外提：ESLint 的 max-lines-per-function 把嵌套函数计入父函数，
// 两个工厂留在 streamAnswer 内会让它超 150 行；外提后各自独立计算。

export const createFrameHandlers = (deps, turn) => {
  const {
    appendToolOutput,
    applyAgentDelegated,
    applyApprovalCardFrame,
    applyGoalState,
    applyGoalTurn,
    applyImageFrame,
    applyPlanApproval,
    applyPlanCancelled,
    applyPlanDelta,
    applyPlanFrame,
    applyPlanSuperseded,
    applyRetrievedFrame,
    applyRunCodeProgress,
    applySkillHitFrame,
    applySteerFrame,
    applySubagentStep,
    applyTodoFrame,
    applyToolStatus,
    applyTurnStart,
    applyUsageFrame,
    attachAskCard,
    attachRunningTurn,
    consultNameOf,
    currentSessionId,
    ensureTick,
    isDispatchTool,
    messages,
    pushTimelineTool,
    reasoningLevelParam,
    retireAskCard,
    setGoal,
    setTodos,
    stepBriefOf,
    hooks,
    dispatchTeamEvent,
    buildTurnRequest,
    resetFromContent,
    pushTimelineArtifact,
    linkPlanLineage,
    dropSteeredUserRows,
    replaySnapshotExtras,
    finishResumedShell,
    reduceTurnSnapshot,
    alignTick,
    planCardFromRecord,
    liveCompactedSid,
    autoCompactSid,
  } = deps
  const {
    abort,

    agentId,
    appendProcess,
    appendToken,
    attachments,
    deepThink,
    editMessageId,
    flushNow,
    fromSteer,
    handlers,
    historyRefs,
    liveScroll,
    mentions,
    model,
    msg,
    planBase,
    planMode,
    replaceMsg,
    replacedMessageId,

    sid,
    skills,
    st,
    thought,
  } = turn
  const snapDeps = {
    msg, sid, st, thought, messages, currentSessionId, liveScroll, handlers,
    setTodos, autoCompactSid, liveCompactedSid, planCardFromRecord, alignTick,
    reduceTurnSnapshot, finishResumedShell, resetFromContent, replaySnapshotExtras,
    dropSteeredUserRows, linkPlanLineage, pushTimelineArtifact,
    setAdmitted: v => { turn.admitted = v },
    setReplaying: v => { turn.replayingSnapshot = v },
  }
  const setAdmitted = v => { turn.admitted = v }
  return {
    signal: abort.signal,
    onTurnStart: payload => {
      // 服务端起表基准（开流第一帧）：合并语义在 streamMerge.applyTurnStart 单源，与分享页共用
      setAdmitted(true)
      applyTurnStart(msg, payload, alignTick)
      // 新一轮开始：通知页面清掉上一轮的只读浏览器快照镜像
      hooks.onTurnStart?.(payload, msg)
    },
    // 浏览器只读快照：新事件经 CHAT_EVENT_CB 映射到这里，转给页面自己的镜像状态（不进账本）
    onBrowserSnapshot: (payload, ev) => hooks.onBrowserSnapshot?.(payload, ev),
    onTeamMember: () => dispatchTeamEvent(),
    onTeamMessage: () => dispatchTeamEvent(),
    onTeamTask: () => dispatchTeamEvent(),
    onSnapshot: payload => applySnapshot(payload, snapDeps),
    ...buildTurnRequest({
      deepThink, planMode, planBase, reasoningLevel: reasoningLevelParam.value,
      attachments, skills, mentions, historyRefs,
      replaceMsg, replacedMessageId, editMessageId, fromSteer, agentId, model
    }),
    model,
    onThinking: t => thought.append(t),
    onThinkingDone: payload => thought.finish(payload),
    onToken: (t, ev) => { appendToken(t, ev); msg.thinkLoading = false; thought.closeRound() },
    onProcess: (t, ev) => {
      // 过程独白（<process> 标签内，与正文分流）：累积与落屏在 ./streamBuffer.js
      thought.closeRound()
      appendProcess(t, ev)
    },
    onPlan: p => applyPlanFrame(msg, p),
    onTodo: payload => applyTodoFrame(sid, payload, setTodos),
    onDeepState: payload => applyGoalState(sid, payload, setGoal),
    onDeepTurn: payload => applyGoalTurn(payload, { currentSid: () => currentSessionId.value, sid, onNewTurn: () => { attachRunningTurn(sid).catch(() => {}) } }),
    onUsage: payload => applyUsageFrame(msg, payload),
    onRetrieved: payload => applyRetrievedFrame(msg, payload, liveScroll),
    onSkillHit: payload => applySkillHitFrame(msg, payload, liveScroll),
    onApprovalRequired: payload => applyApprovalCardFrame(msg, payload, liveScroll),
    onAskUser: payload => {
      // 智能体提问（人在回路，一卡多问）：提问面板挂到当前 AI 气泡状态上，桌面壳据此把底部输入框整块
      // 替换成提问面板；用户逐题翻页作答、一次性批量提交后模型继续走。
      // 每题 options 第一项是模型给的推荐项（仅提示用）——超时与忽略都不会替你选答案
      try {
        attachAskCard(msg, typeof payload === 'string' ? JSON.parse(payload) : payload)
        liveScroll()
      } catch (e) { /* 忽略 */ }
    },
    // 计划帧的血缘由 msg.planCard.adjustFrom 自己维护（applyPlanDelta 首帧写、linkPlanLineage 双向指认），
    // 这里没有可用的 userMsg 作用域——传 null 走 handler 默认值，不要再引用那个不存在的变量。
    // 之前写的是裸 userMsg（从未定义），每帧都 ReferenceError、被外层 catch 吞掉，表现为计划卡不更新。
    onDeepPlanDelta: t => applyPlanDelta(msg, t, { userMsg: null, onScroll: liveScroll }),
    onDeepPlanApproval: payload => applyPlanApproval(msg, payload, { userMsg: null, messages: messages.value, onScroll: liveScroll }),
    onDeepPlanCancelled: () => applyPlanCancelled(msg, { onScroll: liveScroll }),
    onDeepPlanSuperseded: () => applyPlanSuperseded(msg, { onScroll: liveScroll }),
    // 配图全量替换、非法帧不动状态：合并语义在 streamMerge.applyImageFrame 单源，与分享页共用
    onImage: imgs2 => applyImageFrame(msg, imgs2),
    onArtifact: payload => applyArtifact(payload, snapDeps),
    // 插话帧到达时先把 120ms 节流里攒着的正文落屏并封口：位置锚点是「模型听到这句之前
    // 已经写出的全部正文」，少算这一段 token，气泡就会落在真实插入点前面一点。
    onSteer: payload => { flushNow(false); applySteerFrame(payload, msg); liveScroll() },
    onToolStatus: rec => applyToolStatus(msg, rec, {
      thinkRoundEnd: () => thought.closeRound(),
      ensureTick: () => ensureTick(),
      liveScroll: () => liveScroll(),
      isDispatchTool: name => isDispatchTool(name),
      pushTimelineTool: (m, card) => pushTimelineTool(m, card),
      retireAskCard: m => retireAskCard(m)
    }),
    onToolOutput: payload => appendToolOutput(msg, payload),
    onSubagentStep: payload => applySubagentStep(msg, payload, {
      isDispatchTool: name => isDispatchTool(name),
      consultNameOf: x => consultNameOf(x),
      stepBriefOf: c => stepBriefOf(c),
      onScroll: () => liveScroll()
    }),
    onRunCodeProgress: payload => applyRunCodeProgress(msg, payload),
    // 会话内 @ 智能体（§4）：本轮由用户 @ 提及的智能体作答（会话绑定不变），归属同步改写
    // （done 的 delegatedAgentName 会再校正一次，两处同值）
    onAgentDelegated: payload => applyAgentDelegated(msg, payload, liveScroll),
  }
}

export const createClosingHandlers = (deps, turn) => {
  const {
    STOP_PLACEHOLDER,
    applyBlocks,
    applyCompactState,
    applyDoneCore,
    applyDonePayload,
    autoCompactSid,
    chatStreams,
    closeoutDoneTurn,
    currentSessionId,
    ensureThinkSegment,
    flushPendingSteers,
    linkPlanLineage,
    liveCompactedSid,
    loadSessions,
    markStoppedTurn,
    mergeDoneToolCalls,
    messages,
    modelIssueMap,
    modelMap,
    planCardFromRecord,
    restoreTimeline,
    settleAbortedPlan,
    settlePreparingCards,
    streamLifecycle,
    touchSessionRow,
  } = deps
  const {

    agentId,
    attachments,
    autoRetry,
    beginReconnect,
    deepThink,
    editUserMsg,
    failNow,
    flushNow,
    gotToken,
    historyRefs,
    imgs,
    isFirstMessage,
    liveScroll,
    mentions,
    model,
    msg,
    planBase,
    planMode,
    prev,
    question,
    resume,
    sid,
    skills,
    st,
    streamAnswer,
  } = turn
  const getAdmitted = () => turn.admitted
  return {
    onDone: contentJson => {
      flushNow()   // 终态保底：把缓冲里未刷的正文与时间线刷进响应式（停止生成/最终正文对比都依赖它）
      const { sources, related, messageId, degradations } = applyDonePayload(msg, contentJson, {
        sid, resume, agentId, editUserMsg,
        currentSid: () => currentSessionId.value,
        messages,
        planCardFromRecord,
        settleAbortedPlan,
        linkPlanLineage: list => linkPlanLineage(list),
        flushPendingSteers: (left, origin) => flushPendingSteers(left, origin),
        rememberSettled: (sid2, turnId) => streamLifecycle.rememberSettled(sid2, turnId),
        liveCompactedSid,
        applyDoneCore: (m, p) => applyDoneCore(m, p),
        mergeDoneToolCalls: (m, calls) => mergeDoneToolCalls(m, calls),
        restoreTimeline: (m, tl) => restoreTimeline(m, tl),
        ensureThinkSegment: m => ensureThinkSegment(m),
        applyBlocks: (m, blocks) => applyBlocks(m, blocks)
      })
      // 收尾口径在 ./turnDoneCloseout.js（定格本轮：卡片收场/收束语/来源用量/侧栏归位/多版本）
      closeoutDoneTurn(msg, {
        st, prev,
        sources, related, messageId, degradations,
        isFirstMessage,
        settlePreparingCards,
        markStoppedTurn,
        stopPlaceholder: STOP_PLACEHOLDER,
        onModelFallback: () => {
          modelMap.value = { ...modelMap.value, [sid]: '' }
          modelIssueMap.value = { ...modelIssueMap.value, [sid]: '' }
          loadSessions()
        },
        dropStream: () => { if (chatStreams.get(sid) === st) chatStreams.delete(sid) },
        scroll: () => liveScroll(),
        touchRow: () => touchSessionRow(sid),
        reloadSessions: () => loadSessions()
      })
    },
    onWarn: w => { msg.warnMsg = w; liveScroll() },
    // 轮内自动压缩（框架在推理前把早期对话折进摘要）：那两次摘要模型调用的字全被按 purpose 滤掉，
    // 不带这一帧就是二十多秒纯静默，用户只能读成「卡住了」。
    // 进度说在气泡那条本来就在走的状态行上（本轮唯一那一条状态文案），不另起一行：
    // 一轮里框架可以压好几次，另起的那条每压完一次撤走、下一次又长出来，还和提问上方的留痕同时挂着。
    onCompactState: payload => applyCompactState(msg, payload, { sid, autoCompactSid, liveCompactedSid, onScroll: liveScroll }),
    onError: (e, kind) => {
      turn.transportError = String(e || '')
      // 自动重试收紧到「提问还没送到服务端」这一种：连接压根没建起来，重发就是重发一条没送出去的提问。
      // 服务端受理过（admitted）就绝不能重发——那会变成一轮全新问答（重复落库、重复计费），
      // 而它本来只断了一条通道。判定用 navigator.onLine 优先（移动端可靠维护），再退回 fetch 的典型网络错误特征。
      const netDown = (typeof navigator !== 'undefined' && navigator.onLine === false) ||
        /Failed to fetch|NetworkError|ERR_INTERNET|ERR_NETWORK|network ?error/i.test(String(e))
      if (autoRetry > 0 && !getAdmitted() && !gotToken() && netDown) {
        msg.retrying = true
        liveScroll()
        setTimeout(() => {
          // 记录仍是本轮且气泡还在流式态才重试；用户已停止/记录已被清理则直接收尾
          if (chatStreams.get(sid) === st && msg.loading) {
            streamAnswer(question, imgs, msg, false, 0, deepThink, attachments, skills, mentions, { sid, agentId, model }, historyRefs, '', null, planMode, false, false, planBase)
          } else {
            msg.retrying = false
            if (chatStreams.get(sid) === st) chatStreams.delete(sid)
          }
        }, 2500)
        return
      }
      // 传输断开 ≠ 本轮失败（DSH 同一口径）：服务端那一轮与连接解耦，断线期间它照跑，
      // 这里只把它重新挂回来。原通道（POST /chat）和接流通道一视同仁——两者断在传输层时
      // 该做的事完全一样，不再按「是不是接流来的」分叉。
      if (kind === 'interrupted') { beginReconnect(); return }
      // 走到这里是本轮真的失败（服务端 error 帧 / 业务失败）：保半程内容 + 错误卡 + 重新生成
      failNow(e, kind)
    }
  }
}


/**
 * 接流第一帧（只有 resume 通道有）：本轮已产出的东西一次铺进气泡。
 * 依赖全部显式传入（从 createFrameHandlers 提出以保持其 ≤150 行）。
 */
const applySnapshot = (payload, d) => {
  let snap = null
  try { snap = typeof payload === 'string' ? JSON.parse(payload) : payload } catch (e) { snap = null }
  if (!d.reduceTurnSnapshot(d.msg, snap, d.sid, { setTodos: d.setTodos, autoCompactSid: d.autoCompactSid, liveCompactedSid: d.liveCompactedSid, planCardFromRecord: d.planCardFromRecord, alignTick: d.alignTick })) {
    d.finishResumedShell(d.msg, d.st, d.sid, false)
    return
  }
  d.setAdmitted(true)
  d.resetFromContent(d.msg.content)
  d.setReplaying(true)
  try {
    d.replaySnapshotExtras(d.msg, snap, d.handlers)
  } finally {
    d.setReplaying(false)
  }
  d.thought.setOpen(snap.thinkingOpen)
  if (d.currentSessionId.value === d.sid) d.messages.value = d.dropSteeredUserRows(d.messages.value)
  if (d.msg.planCard) d.linkPlanLineage(d.messages.value)
  d.liveScroll()
}

/** 产物到达：入 artifacts、插时间线、标记「刚到达」900ms（入场动效只认它）。 */
const applyArtifact = (payload, d) => {
  try {
    const a = typeof payload === 'string' ? JSON.parse(payload) : payload
    if (!a || !a.url) return
    if (!Array.isArray(d.msg.artifacts)) d.msg.artifacts = []
    d.msg.artifacts.push(a)
    d.pushTimelineArtifact(d.msg, d.msg.artifacts.length - 1)
    d.msg.freshArtifactId = a.id || a.filename || ''
    if (d.msg._freshTimer) clearTimeout(d.msg._freshTimer)
    d.msg._freshTimer = setTimeout(() => {
      d.msg.freshArtifactId = ''
      d.msg._freshTimer = 0
    }, 900)
    d.liveScroll()
  } catch (e) { /* 忽略 */ }
}