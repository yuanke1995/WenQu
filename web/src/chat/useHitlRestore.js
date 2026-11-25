// HITL 恢复（从 useChatEngine 抽出）：待批准审批卡的恢复、通知深链落点、恢复态答案轮询、
// 以及提问卡动作上下文（askCtx/askSubmitAll/ignoreAsk）。restoredPoll 这个「同一时刻只留一份」
// 的轮询句柄随块一起搬进来，因此 stopRestoredPoll/pollRestoredAnswer 由本模块导出、主文件解构接回。
// 依赖全部显式注入；reactive 从 vue 引入。
import { reactive } from 'vue'

/** 单张审批卡的恢复态载荷。timeoutMs 传「还剩多少」而不是整窗（卡片倒计时直接读 deadline）。 */
function buildApprovalCard (item) {
  const remainMs = Number(item.remainingMs) || 0
  return {
    id: item.approvalId, tool: item.tool, label: item.label, hint: item.hint, args: item.args,
    reason: item.reason || '',
    subagent: item.subagent || (item.parentSessionId ? { kind: 'subagent', agent: '子助手' } : null),
    timeoutMs: remainMs, deadline: remainMs > 0 ? Date.now() + remainMs : 0, busy: false, restored: true
  }
}

/** 没有可挂的卡时按深链/普通两种口径给终态：普通调用 none；深链区分 dead（窗口内但句柄没了）/gone。 */
function partialVerdict (onlyId, dead) {
  if (!onlyId) return 'none'
  return dead.length ? 'dead' : 'gone'
}

/** 深链记录归属/状态判定：missing / foreign / pending / done（已处理的终态）。 */
function recVerdict (rec, sid) {
  if (!rec) return 'missing'
  // 链接里的审批不属于当前会话（旧链接/会话已切走）：不裁决也不冒充失效。
  // 子助手分支上翻的卡 sessionId 是临时子会话，归属看 parentSessionId
  const recSid = rec.sessionId ? String(rec.sessionId) : ''
  const recParent = rec.parentSessionId ? String(rec.parentSessionId) : ''
  if ((recSid || recParent) && recSid !== String(sid) && recParent !== String(sid)) return 'foreign'
  return rec.status === 'PENDING' ? 'pending' : 'done'
}

/** 屏上已挂着本次深链点名（或未点名时任意）的实时审批卡 → 视为 exists，不重复挂。 */
function alreadyLive (st, onlyId) {
  const live = (st && st.msg && Array.isArray(st.msg.approvals)) ? st.msg.approvals : []
  return live.length > 0 && (!onlyId || live.some(x => String(x.id) === String(onlyId)))
}

export const useHitlRestore = ({
  chatStreams, currentSessionId, messages, hooks, text, send, switchSession,
  listPendingApprovals, getToolApproval, getHistory, answerAgentAsk, ignoreAgentAsk,
  retireAskCard, emitNotifChanged, submitAskImpl, ignoreAskImpl,
  cardTargetFor, approvalStatusText, clearApprovalCard, clearApprovalQuery, notify,
}) => {
  /** 恢复态轮询句柄（同一时刻只留一份：切会话/答完即停） */
  let restoredPoll = null
  const stopRestoredPoll = () => { if (restoredPoll) { clearInterval(restoredPoll); restoredPoll = null } }

  const hydratePendingApprovals = async (sid, onlyId = '') => {
    if (!sid) return 'none'
    const st = chatStreams.get(sid)
    if (alreadyLive(st, onlyId)) return 'exists'   // 点名的实时卡已在屏上
    let items
    try {
      const r = await listPendingApprovals(sid)
      items = (r && r.data && Array.isArray(r.data.items)) ? r.data.items : []
    } catch (e) { return 'error' }   // 拉不动不提示：审批自己有超时兜底，不该打断正常浏览
    if (currentSessionId.value !== sid) return 'none'   // 快速切会话：晚到响应不覆盖当前视图
    const matched = items.filter(x => x && x.approvalId && (!onlyId || x.approvalId === onlyId))
    const alive = matched.filter(x => x.live)
    if (!alive.length) return partialVerdict(onlyId, matched.filter(x => !x.live))
    const mounted = mountAliveApprovals(st, alive)
    if (!onlyId && mounted) hooks.scrollForce?.()
    if (onlyId) return mounted ? 'mounted' : 'exists'
    return mounted ? 'mounted' : 'none'
  }

  /** 把 alive 里的卡挂回各自那一轮（有气泡）或归进 orphanByTurn（补占位泡）。返回挂上的卡数。 */
  function mountAliveApprovals (st, alive) {
    const orphanByTurn = new Map()
    let mounted = 0
    for (const item of alive) {
      const card = buildApprovalCard(item)
      const target = cardTargetFor(st, item.turnId)
      if (target) {
        const list = Array.isArray(target.approvals) ? target.approvals : []
        if (list.some(x => String(x.id) === String(card.id))) continue
        target.approvals = [...list, card]
        mounted++
        continue
      }
      const key = item.turnId || ''
      if (!orphanByTurn.has(key)) orphanByTurn.set(key, { turnId: item.turnId || '', cards: [] })
      orphanByTurn.get(key).cards.push(card)
    }
    return mounted + appendOrphanBubbles(orphanByTurn)
  }

  /** 这一轮视图里没有气泡（别的设备上跑、或助手消息还没落库）：补一个「等你批准执行」的占位泡。 */
  function appendOrphanBubbles (orphanByTurn) {
    let n = 0
    for (const g of orphanByTurn.values()) {
      const anchor = reactive({ role: 'ai', content: '', images: [], sources: [], related: [],
        degradations: [], warnMsg: '', loading: true, retrying: false, thinking: '',
        thinkLoading: false, time: Date.now(), artifacts: [],
        toolCalls: [], plan: null, planCard: null, timeline: [], errorCard: null,
        model: '', approvals: g.cards, turnId: g.turnId })
      messages.value = [...messages.value, anchor]
      n += g.cards.length
    }
    return n
  }

  /**
   * 通知深链（/chat?sid=&approval=<id>）的落点：
   * - 还在等批准：挂回对话里那一步，滚动过去并闪一圈（hooks.revealApproval），不再另画顶部横幅；
   * - 已批准/已拒绝/已超时：只给一句结果，并把 approval 参数清掉；
   * - 窗口过了或唤醒句柄已不在：直接说失效，不给一个点了只会报错的按钮。
   */
  const handleApprovalDeepLink = async (sid, approvalId) => {
    if (!sid || !approvalId || currentSessionId.value !== sid) return
    let rec
    try {
      const r = await getToolApproval(approvalId)
      rec = (r && r.data) || null
    } catch (e) {
      notify.error('恢复审批失败：' + (e.message || '网络异常'))
      return
    }
    if (currentSessionId.value !== sid) return
    const verdict = recVerdict(rec, sid)
    if (verdict === 'missing') {
      notify.info('该审批请求不存在或已失效')
      clearApprovalCard(approvalId); clearApprovalQuery(approvalId); return
    }
    if (verdict === 'foreign') { clearApprovalQuery(approvalId); return }
    if (verdict !== 'pending') {
      notify.info('该审批已处理：' + approvalStatusText(rec.status))
      clearApprovalCard(approvalId); clearApprovalQuery(approvalId); return
    }
    const res = await hydratePendingApprovals(sid, approvalId)
    if (currentSessionId.value !== sid) return
    settleDeepLinkResult(res, approvalId)
  }

  /** 按 hydratePendingApprovals 的结果收尾：挂上→闪一圈；失败/失效→说清楚并清参数。 */
  function settleDeepLinkResult (res, approvalId) {
    if (res === 'mounted' || res === 'exists') {
      hooks.revealApproval?.(approvalId)
      clearApprovalQuery(approvalId)
      return
    }
    if (res === 'error') {
      // 待批列表没取回来：别把它说成「已失效」，也别清参数——刷新还能再试
      notify.warning('待审批记录没取回来，稍后刷新再试')
      return
    }
    notify.warning(res === 'dead'
      ? '该审批已失效（本轮已结束或服务已重启），无法再批准'
      : '该审批已超时或已失效，无法再批准')
    clearApprovalCard(approvalId)
    clearApprovalQuery(approvalId)
  }

  /**
   * 恢复态卡片答完后的答案取回：本设备没有这一轮的流式通道，回答在后台生成。
   * 先记下当前历史条数，每 4s 比一次——多出那条（助手回答落库）即完成，重载会话视图。
   */
  const pollRestoredAnswer = async sid => {
    stopRestoredPoll()
    let baseline = -1
    try {
      const r = await getHistory(sid, { silentForbidden: true })
      baseline = Array.isArray(r?.data) ? r.data.length : -1
    } catch (e) { /* 拿不到基线就不轮询，用户自己回到会话仍能看到答案 */ }
    if (baseline < 0) return
    let ticks = 0
    restoredPoll = setInterval(async () => {
      if (currentSessionId.value !== sid) { stopRestoredPoll(); return }
      if (++ticks > 45) {
        stopRestoredPoll()
        notify.warning('这一轮回答还在生成，稍后回到本会话查看')
        return
      }
      try {
        const r = await getHistory(sid, { silentForbidden: true })
        const list = Array.isArray(r?.data) ? r.data : null
        if (!list || list.length <= baseline) return
        stopRestoredPoll()
        await switchSession(sid)
      } catch (e) { /* 单次失败等下一轮 */ }
    }, 4000)
  }

  // 提问卡动作在 ./askActions.js（actions 层），依赖经 ctx 注入。
  const askCtx = () => ({
    answerApi: answerAgentAsk, ignoreApi: ignoreAgentAsk, notify: notify, emitNotifChanged,
    retireAskCard: m => retireAskCard(m), stopRestoredPoll: () => stopRestoredPoll(),
    pollRestoredAnswer: sid => pollRestoredAnswer(sid), switchSession: sid => switchSession(sid),
    // 迟到作答（DSH continued）：走与用户亲手打字完全同一条发送出口（跑着就插话、空闲就新一轮）
    deliverContinuedAnswer: t => { text.value = t; send() },
    currentSid: () => currentSessionId.value, scrollSoft: () => hooks.scrollSoft?.()
  })
  const askSubmitAll = m => submitAskImpl(m, askCtx())
  const ignoreAsk = m => ignoreAskImpl(m, askCtx())

  return {
    stopRestoredPoll, hydratePendingApprovals, handleApprovalDeepLink,
    pollRestoredAnswer, askSubmitAll, ignoreAsk,
  }
}
