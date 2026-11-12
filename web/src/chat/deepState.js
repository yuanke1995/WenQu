// 深度执行的纯状态/文案口径：状态词、轮次、预算、停因、终态卡与动作判据。
// 入参一律是后端下发的目标视图模型，不读任何 .value；条子、详情面、回声气泡共用同一份，
// 各处各写一遍必然漂移。抽成独立模块是为了能在 Node 里直接测（不经过 views/store 等浏览器依赖）。
// ==================== 深度执行的状态口径 ====================
// 形状对齐 DSH 的目标：一个跨轮自动推进的目标 + 一个阶段，没有验收标准、没有人工确认、
// 没有逐条核验、没有倒计时。用户的动作只剩三个：改目标原文、停止/继续、删除。
// 摘要条、右栏/移动详情面、目标卡三处共用同一套词与同一份判据：各处各写一遍必然漂移
// （同一条目标，条子说一个词、卡里说另一个词就是那种）。入参一律是目标视图模型本身，不读任何 .value。
// 后端状态：QUEUED/RUNNING/PAUSED/VERIFYING/WAITING_APPROVAL/
// WAITING_EVENT/ACHIEVED/FAILED/CANCELLED。老行的其余状态读取时已归一。
/** 不再往下推的那几档：详情面转只读，条子也不再露 */
export const GOAL_ENDED = ['ACHIEVED', 'CANCELLED', 'FAILED', 'ENDED_INCOMPLETE']
/** 还归用户管的几档：条子在这些档上出现（暂停/等待也是「用户可以接着处理」的活目标） */
export const GOAL_LIVE = ['QUEUED', 'RUNNING', 'PAUSED', 'VERIFYING', 'WAITING_APPROVAL', 'WAITING_EVENT']
/** 条子可见的范围：FAILED 也要能看见（否则用户没有入口点「继续」把预算/核验失败的目标救回来） */
export const GOAL_VISIBLE = [...GOAL_LIVE, 'FAILED']
/** 正在往前的几档：暂停/等待不算「在跑」，但都还是活目标 */
export const GOAL_MOVING = ['QUEUED', 'RUNNING', 'VERIFYING', 'WAITING_APPROVAL', 'WAITING_EVENT']
/** 「已让位」不是独立状态，而是暂停的一种成因：用户插话/提问把后台轮挤开了，答完会自动接回 */
export const goalPreemptedOf = g => !!g && g.status === 'PAUSED' && g.endReason === 'preempted'

export const goalTerminalOf = g => !!g && GOAL_ENDED.includes(g.status)
export const goalBarVisibleOf = g => !!g && GOAL_VISIBLE.includes(g.status)
export const goalPhraseOf = g => {
  const s = g && g.status
  if (g && g.phase === 'COMPLETION_CHECK') return '确认中的目标'
  if (GOAL_MOVING.includes(s)) return '进行中的目标'
  if (s === 'PAUSED') return goalPreemptedOf(g) ? '已让位的目标' : '已暂停的目标'
  if (s === 'ACHIEVED') return '已完成的目标'
  if (GOAL_ENDED.includes(s)) return '已结束的目标'
  return ''
}
export const goalStateLabelOf = g => {
  const s = g && g.status
  if (g && g.stale && GOAL_LIVE.includes(s)) return '待恢复'
  if (g && g.phase === 'COMPLETION_CHECK') return '确认中'
  if (s === 'VERIFYING') return '核验中'
  if (s === 'WAITING_APPROVAL') return '待确认'
  if (s === 'WAITING_EVENT') return '等待中'
  if (s === 'QUEUED' || s === 'RUNNING') return '进行中'
  if (s === 'PAUSED') return goalPreemptedOf(g) ? '已让位' : '已暂停'
  if (s === 'ACHIEVED') return '已完成'
  if (s === 'FAILED') return '未完成'
  if (s === 'CANCELLED' || s === 'ENDED_INCOMPLETE') return '已取消'
  return ''
}
export const goalStateClassOf = g => {
  const s = g && g.status
  if (g && g.stale && GOAL_LIVE.includes(s)) return 'paused'
  if (GOAL_MOVING.includes(s)) return 'running'
  if (s === 'PAUSED') return 'paused'
  if (s === 'ACHIEVED') return 'achieved'
  if (s === 'FAILED') return 'failed'
  if (s === 'CANCELLED' || s === 'ENDED_INCOMPLETE') return 'cancelled'
  return ''
}
/** 条子上那一行轮次：只说自动续跑已经跑到第几轮。
 *  安全上限（maxRounds）是兜底机制、不是给用户的预算，不对用户展示——到了上限由停因那行说。 */
export const goalRoundLineOf = g => {
  if (!g) return ''
  const round = Number(g.round) || 0
  if (round <= 0) return '准备开始'
  return `第 ${round} 轮`
}
/** 停下来的原因：只说「为什么在这儿停住」，与用户自己按的暂停分开说（后者不给原因行）。
 *  轮数上限那条只在真停住（PAUSED）时才说：继续跑之后 endReason 清掉之前，这句话不该挂在一个正在跑的目标上。 */
export const goalBlockReasonOf = g => {
  if (!g) return ''
  const reason = g.endReason || ''
  if (reason === 'round-limit') {
    return g.status === 'PAUSED' ? `已到自动推进上限（${goalRoundLineOf(g)}），先停在这里` : ''
  }
  if (reason === 'blocked') return `受阻：${String(g.blockedReason || '').trim() || '助手报告目标无法继续'}`
  if (reason === 'budget') return '已达到本次目标的预算上限，先停在这里'
  if (reason === 'no-progress') return '连续几轮没有实质进展，先停在这里'
  if (reason === 'verification-failed') return '连续提交的证据都没通过核验，先停在这里'
  if (reason === 'completion-unconfirmed') return '连续完成检查仍未提交完成或剩余项，先停在这里'
  if (reason === 'orphaned') return '上一次执行中断，已停下；可以继续恢复'
  if (reason === 'turn-failed' && g.blockedReason) return `这一轮没跑完：${String(g.blockedReason).trim()}`
  return ''
}
/** 摘要条的悬浮行：条子放不下的那些字（目标原文、轮次、后端给的那句提示、等待原因、停因）一次给全 */
export const goalBarTitleOf = g => {
  if (!g) return ''
  return [g.objective, goalRoundLineOf(g), g.hint, g.waitingReason, goalBlockReasonOf(g)].filter(Boolean).join('\n')
}
/** 条子第二行那句话：先给「这句答完自动继续」这类提示，再说等待谁、最后说为什么停。
 *  触屏与 PC 同一条判据——两处各拼一份，同一条目标就会在两端说出两句不同的话。 */
export const goalBarMetaOf = g => {
  if (!g) return ''
  return [g.hint, g.waitingReason, goalBlockReasonOf(g)].filter(Boolean).join(' · ')
}
export const goalCanPauseOf = g => !!g && GOAL_MOVING.includes(g.status)
export const goalCanResumeOf = g => !!g && (g.status === 'PAUSED' || g.status === 'FAILED' || (g.stale && GOAL_LIVE.includes(g.status)))
export const goalCanEditOf = g => !!g && (GOAL_LIVE.includes(g.status) || g.status === 'FAILED')
/** 取消：只要还在推进（含未完成）就能结束它并留痕；终态不再给这个动作 */
export const goalCanCancelOf = g => !!g && (GOAL_VISIBLE.includes(g.status) || g.status === 'QUEUED' || g.status === 'RUNNING')
/** 删除记录：只有终态可删（取消与删除是两件事，见 design/mockups/goal-mode-ux） */
export const goalCanClearRecordOf = g => !!g && GOAL_ENDED.includes(g.status)
/** 目标原文版本：v1 是初版；改过一次后条子与终态卡都要说清当前读的是第几版 */
export const goalVersionLabelOf = g => {
  if (!g) return ''
  return 'v' + ((Number(g.objectiveVersion) || 0) + 1)
}
/** 进度细条：取后端预算百分比；没有预算口径时按轮次占比兜底，都没有就不画 */
export const goalProgressPercentOf = g => {
  const b = g && g.budget
  if (!b) return null
  const pct = Number(b.percent)
  return Number.isFinite(pct) ? Math.max(0, Math.min(100, Math.round(pct * 100))) : null
}
/** 终态卡挂在「设为目标」那条回声下：已完成/未完成/已取消都留一张，删除记录后由 sessionGoal=null 一并消失 */
export const goalTerminalCardOf = g => !!g && (g.status === 'ACHIEVED' || g.status === 'FAILED' || g.status === 'CANCELLED' || g.status === 'ENDED_INCOMPLETE') && !(g.status === 'CANCELLED' && g.endReason === 'cleared')
/** 终态卡的标题词：与状态词同一套，不再另起一套「成功/失败」 */
export const goalTerminalTitleOf = g => {
  const s = g && g.status
  if (s === 'ACHIEVED') return '目标已完成'
  if (s === 'FAILED') return '目标未完成'
  if (s === 'CANCELLED' || s === 'ENDED_INCOMPLETE') return '目标已取消'
  return ''
}
/** 终态卡下面那句：说清「留下了什么」，避免用户以为取消把东西也删了 */
export const goalTerminalNoteOf = g => {
  const s = g && g.status
  const rounds = Number(g && g.budget && g.budget.rounds) || Number(g && g.round) || 0
  if (s === 'ACHIEVED') return rounds > 0 ? `共 ${rounds} 轮完成，过程与产物都保留` : '过程与产物都保留'
  if (s === 'FAILED') return '停在未完成：改一下目标就能接着跑；删除记录只撤下这张卡'
  if (s === 'CANCELLED' || s === 'ENDED_INCOMPLETE') return rounds > 0 ? `共 ${rounds} 轮后取消，历史与过程保留` : '已取消，历史与过程保留'
  return ''
}
/** 完成申请的核验结果那一句：只在这份事实面上出现，两端（详情卡 / 回声悬浮）同一个出口 */
export const goalOutcomeLineOf = g => {
  const o = g && g.outcome
  if (!o) return ''
  if (o.state === 'COMPLETED') {
    const n = (o.evidence || []).length
    return n > 0 ? `证据已核验（${n} 条）` : '已完成'
  }
  if (o.state === 'CANCELLED') return o.reason === 'cleared' ? '记录已删除' : '已取消'
  if (o.remaining && o.remaining.length) return `未完成 ${o.remaining.length} 项`
  return ''
}
/** 回声气泡那颗胶囊的状态词。
 *  <p>只给「这一条回声就是当前那个目标」说状态：库里那列存的是 goalId，旧目标的态没有读端，
 *  换过目标之后旧回声退回只标身份——不拿别的目标的态去给这一行编一个状态。
 *  <p>goalId 为空（乐观气泡那几秒、服务端还没回目标号）同样不给词，只给身份。 */
export const goalEchoStateOf = (g, goalId) => {
  if (!g || !goalId || g.goalId !== goalId) return null
  const label = goalStateLabelOf(g)
  return label ? { label, cls: goalStateClassOf(g) } : null
}
/** 回声的悬浮行：命令原文（界面上不再露出 /goal 那串字）+ 条子放不下的那些数 */
export const goalEchoTipOf = (g, goalId) => {
  const head = '/deep 深度执行指令：这一句被设成了本会话的任务，助手会自己跨轮推进直到宣布完成'
  if (!g || !goalId || g.goalId !== goalId) return head
  return [head, goalRoundLineOf(g), g.hint, goalBlockReasonOf(g), goalOutcomeLineOf(g)].filter(Boolean).join('\n')
}
