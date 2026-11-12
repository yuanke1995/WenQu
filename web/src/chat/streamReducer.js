// 接流快照 reducer：把服务端 snapshot 铺进气泡（纯字段写入 + 通过 ctx 注入引擎级状态）。
import { earlierStart, resumedStartAt } from './turnTiming.js'
import { restoreTimeline } from './timelineWrite.js'
import { ensureThinkSegment } from './segments.js'
import { applyBlocks } from './blocks.js'

/**
 * @param ctx { setTodos, autoCompactSid, liveCompactedSid, planCardFromRecord }
 * @returns 是否真的应用了快照（running!==true 返回 false，调用方走历史回显）
 */
export const applyTurnSnapshot = (msg, snap, sid, ctx) => {
  if (!snap || snap.running !== true) return false
  msg.content = typeof snap.content === 'string' ? snap.content : ''
  if (typeof snap.processText === 'string' && snap.processText) msg.processText = snap.processText
  if (typeof snap.thinking === 'string' && snap.thinking) msg.thinking = snap.thinking
  msg.thinkLoading = !!snap.thinkingOpen
  if (Array.isArray(snap.toolCalls)) msg.toolCalls = snap.toolCalls
  if (Array.isArray(snap.artifacts) && snap.artifacts.length) msg.artifacts = snap.artifacts
  if (Array.isArray(snap.images) && snap.images.length) msg.images = snap.images
  if (Array.isArray(snap.sources) && snap.sources.length) msg.sources = snap.sources
  if (typeof snap.model === 'string' && snap.model) msg.model = snap.model
  if (typeof snap.turnId === 'string' && snap.turnId) msg.turnId = snap.turnId
  if (snap.agentName) msg.agentName = snap.agentName
  if (snap.agentId) msg.agentId = snap.agentId
  // 起表点：服务端基准只认第一份，重连拿到的更晚快照不重锚（否则秒表往回跳）
  if (typeof snap.wallMs === 'number') {
    if (!msg.turnClockFromServer) {
      msg.time = Date.now() - snap.wallMs
      msg.turnClockFromServer = true
      ctx.alignTick?.(msg.time)
    }
  } else {
    msg.time = earlierStart(msg.time, resumedStartAt(snap.elapsedMs, Date.now()))
  }
  if (typeof snap.prepMs === 'number') msg.turnPrepMs = snap.prepMs
  // 时间线必须在 toolCalls/artifacts 之后重建（段里的下标指向这两份清单）
  if (Array.isArray(snap.timeline) && snap.timeline.length) msg.timeline = restoreTimeline(msg, snap.timeline)
  if (snap.blocks && typeof snap.blocks === 'object') applyBlocks(msg, snap.blocks)
  if (snap.planCard && typeof snap.planCard === 'object') {
    const prev = msg.planCard
    const card = {
      ...snap.planCard,
      versions: (Array.isArray(snap.planCard.versions) ? snap.planCard.versions : [])
        .map(v => ({ plan: (v && v.plan) || '', feedback: (v && v.feedback) || '' })),
      editText: (prev && prev.editText) || '',
      busy: !!snap.planCard.busy,
      deadline: 0
    }
    if (card.revising && !card.revisingStarted) {
      card.deadline = 0
    } else if (card.status === 'pending' && typeof snap.planCard.remainingMs === 'number') {
      card.deadline = snap.planCard.remainingMs > 0 ? Date.now() + snap.planCard.remainingMs : Date.now()
    }
    if (prev && prev.busy && card.status === 'pending') card.busy = true
    msg.planCard = card
  } else if (snap.planRecord) {
    msg.planCard = ctx.planCardFromRecord(snap.planRecord, undefined, sid)
  }
  ensureThinkSegment(msg)
  const ev = snap.events || {}
  if (ev.plan) {
    try { const arr = JSON.parse(ev.plan); if (Array.isArray(arr) && arr.length) msg.plan = arr } catch (e) { /* 载荷异常保留已有 */ }
  }
  if (ev.retrieved) {
    try {
      const j = JSON.parse(ev.retrieved)
      msg.retrieved = { keywords: j.keywords || 0, refs: j.refs || 0, terms: j.terms || [] }
    } catch (e) { /* 忽略 */ }
  }
  if (ev.usage) {
    try { const j = JSON.parse(ev.usage); if (j && typeof j === 'object') msg.tokensPreview = j } catch (e) { /* 忽略 */ }
  }
  if (ev.todo) {
    try { const j = JSON.parse(ev.todo); if (j && Array.isArray(j.items)) ctx.setTodos(sid, j) } catch (e) { /* 忽略 */ }
  }
  if (typeof ev.warn === 'string' && ev.warn) msg.warnMsg = ev.warn
  if (ev.compact_state) {
    try {
      const j = JSON.parse(ev.compact_state)
      if (j?.phase === 'start') { ctx.autoCompactSid.value = sid; msg.compacting = true }
      else if (j?.status === 'completed') { ctx.liveCompactedSid.value = sid; msg.compacting = false }
    } catch (e) { /* 载荷异常不补 */ }
  }
  return true
}


/** 早产工具卡收场：本轮结束时仍 preparing 的卡标为未执行，停掉永久转圈。 */
export const settlePreparingCards = (m, reason) => {
  if (!m || !Array.isArray(m.toolCalls)) return
  for (const t of m.toolCalls) {
    if (!t || !t.preparing) continue
    t.preparing = false
    delete t.chars
    delete t.brief
    t.status = 'error'
    t.error = reason
    t.result = reason
  }
}

/** 接流气泡收场：从流表摘掉、清 loading/retrying，按需按历史重显。 */
export const finishResumedShell = (msg, st, sid, reload, ctx) => {
  if (st && ctx.chatStreams.get(sid) === st) ctx.chatStreams.delete(sid)
  if (msg) { msg.loading = false; msg.retrying = false }
  if (reload && ctx.currentSid() === sid) ctx.switchSession(sid)
}

/**
 * 快照里属于「运行期增量」的几份：复用实时事件同一套回调重建；先清旧值再回放，
 * 断线重连时不会把上一次接流已经铺过的输出/步骤叠第二遍。
 * @param handlers 实时帧回调（onToolOutput/onToolStatus/onSubagentStep/onRunCodeProgress/onDeepState）
 */
export const replaySnapshotExtras = (msg, snap, handlers) => {
  const ev = snap.events || {}
  if (snap.toolOutputs && typeof snap.toolOutputs === 'object') {
    for (const t of (msg.toolCalls || [])) {
      delete t.liveOutput
      delete t.outputTruncated
    }
    for (const [name, out] of Object.entries(snap.toolOutputs)) {
      if (!out || typeof out.text !== 'string') continue
      handlers.onToolOutput({ name, delta: out.text })
      if (out.truncated) {
        const t = [...(msg.toolCalls || [])].reverse()
          .find(x => x.name === name && x.status === 'start')
        if (t) t.outputTruncated = true
      }
    }
  }
  if (Array.isArray(snap.preparing)) {
    if (Array.isArray(msg.toolCalls)) msg.toolCalls = msg.toolCalls.filter(t => !t.preparing)
    for (const frame of snap.preparing) handlers.onToolStatus(frame)
  }
  if (Array.isArray(snap.subagentSteps)) {
    for (const t of (msg.toolCalls || [])) {
      delete t._live
      delete t._steps
      delete t._frames
      delete t._frameKeys
    }
    for (const frame of snap.subagentSteps) handlers.onSubagentStep(frame)
  }
  if (ev.runcode_progress) {
    handlers.onRunCodeProgress(typeof ev.runcode_progress === 'string'
      ? ev.runcode_progress
      : JSON.stringify(ev.runcode_progress))
  } else {
    const running = (msg.toolCalls || []).some(t => t.name === 'run_code' && t.status === 'start')
    if (!running) msg.runBoard = null
  }
  // 目标态镜像 applyTurnSnapshot 不解析，直接复用实时回调
  if (ev.deep_state) handlers.onDeepState(ev.deep_state)
}
