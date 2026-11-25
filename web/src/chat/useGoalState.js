// 会话目标（目标模式）状态 + 后台续跑轮轮询 + 占位气泡（从 useChatEngine 抽出）。
// 目标由 SSE goal_state 帧写入、切会话/刷新按需回捞；RUNNING 时 2.5s 一跳探测接流轮、每三跳取一次面板。
// goalPlaceholder 是本模块私有可变状态，对外经 dropGoalPlaceholderIfOther / goalPlaceholderFor 暴露。
import { ref, reactive, computed, watch, onUnmounted } from 'vue'
import { chatStreams } from '../views/store'
import { ensureTick } from './projections'
import { getChatRun, getPendingGoalApi, getSessionGoalApi } from './chatTransport.js'

export const useGoalState = ({ currentSessionId, currentAgent, messages, hooks, attachRunningTurnOf }) => {

const goalsMap = ref({})
const sessionGoal = computed(() => goalsMap.value[currentSessionId.value] || null)
function setGoal (sid, doc) { goalsMap.value = { ...goalsMap.value, [sid]: doc } }


/** 目标模式开不开：只认当前智能体那一格（服务端 AgentService 已归一成 1/0）。
 *  平台级总闸已去掉——判据与后端 GoalService.enabledFor 同源（引擎侧目标上下文注入也看它），
 *  界面露入口而引擎不再带目标上下文就是「教了用户一件没有的能力」那种漂移 */
const goalModeOn = computed(() => {
  const a = currentAgent.value
  return !a || Number(a.goalEnabled) === 1
})

/**
 * 目标态的两条兜底：面板取数 + 接回后台那一轮。
 * <p>后台续跑轮由服务端自己起（没有浏览器发起通道），它登记在运行总线上，但「有没有人在看」
 * 只能由这一侧决定：本地没有这一轮的流、服务端却还在跑，就开一条接流通道。不接的表现为
 * 目标自己跑了好几轮、聊天区一颗气泡都没长——正文全在库里，刷新才读得到。
 * <p>只有 RUNNING 才可能有轮在飞：暂停与终态都不开轮询、也不去探测（白跑请求）。
 * 接流探测要勤（2.5 秒一跳，一轮通常跑一两分钟，太慢就等于没接上），面板取数不必（每三跳一次）。
 * 有动作在飞时不覆盖（busy 态是本地的乐观锁，被轮询冲掉会让人以为按钮没生效）。
 */
const goalRunning = computed(() => sessionGoal.value?.status === 'RUNNING')
let goalPollTimer = null
let goalTick = 0
/**
 * 目标轮的占位气泡。从「按下回车」到「总线上探得到这一轮」之间有一段真空：调度器一拍 2 秒，
 * 加上实例冷启动装配（模型客户端 / MCP / 记忆 / 沙盒供给）5 秒起、可拖到数十秒，
 * 那几秒里聊天区只有用户那句话——看着像根本没发出去。
 * <p>这颗气泡只带 loading，TurnBar 当场说「正在溯源」；总线一登记上就把**同一颗**交给接流通道
 * （attachRunningTurn 的 reuseMsg），不另起一颗、秒数也不倒回去。它不是那一轮本身，所以目标一停下
 * 或转终态就撤——没有真轮接上时不许留一行呼吸中的假状态。
 */
let goalPlaceholder = null
// 占位气泡挂在哪条会话上：同一会话的历史重铺（新建后补 sid、侧栏回填都会触发一次）不许把它抹掉
let goalPlaceholderSid = null
const ensureGoalPlaceholder = () => {
  const sid = currentSessionId.value
  if (!sid || !goalRunning.value) return
  if (chatStreams.has(sid)) return                          // 真流已经接上，不缺这一颗
  if (goalPlaceholder && messages.value.includes(goalPlaceholder)) return
  if (messages.value.some(m => m && m.loading)) return      // 已有在跑的气泡，别叠第二行状态
  goalPlaceholder = reactive({ role: 'ai', content: '', images: [], sources: [], related: [],
    degradations: [], warnMsg: '', loading: true, retrying: false, thinking: '', thinkLoading: false,
    time: Date.now(), turnMs: 0, humanWaitMs: 0, artifacts: [], toolCalls: [], plan: null,
    planCard: null, timeline: [], errorCard: null, model: '', messageId: null, goalWaiting: true })
  goalPlaceholderSid = sid
  messages.value.push(goalPlaceholder)
  ensureTick()   // 秒针：这一行是跟着秒走的计时器，不挂表数字停在 0 秒不动
  hooks.scrollFollow?.()
}
/** 撤下占位气泡（目标停了/终态/用户自己发了一轮）；已经交给接流通道的那颗不归这里管 */
const dropGoalPlaceholder = () => {
  const m = goalPlaceholder
  goalPlaceholder = null
  goalPlaceholderSid = null
  if (!m) return
  const i = messages.value.indexOf(m)
  if (i >= 0) { m.loading = false; messages.value.splice(i, 1) }
}
/** 把占位气泡交给接流通道：交接即脱手，之后它是一颗普通 live 气泡 */
const takeGoalPlaceholder = sid => {
  const m = goalPlaceholder
  if (!m || sid !== currentSessionId.value || !messages.value.includes(m)) return null
  goalPlaceholder = null
  goalPlaceholderSid = null
  return m
}
const watchGoalTurn = sid => {
  if (chatStreams.has(sid)) return      // 这一轮本地已经在看了，别另起一颗气泡
  ensureGoalPlaceholder()               // 探测那一跳（几百毫秒）也别让聊天区空着
  getChatRun(sid).then(r => {
    if (!r || !r.success || !r.data || !r.data.running) return
    if (currentSessionId.value !== sid) return
    attachRunningTurnOf()(sid).catch(() => {})
  }).catch(() => { /* 探测失败不提示：下一跳再试 */ })
}
/** 切会话时的目标回捞：先取 /goal/pending（还活着的那一条：RUNNING/PAUSED），没有再取
 *  /session/{id}/goal（当前/最近一条，含已达成/已删除的终态记录）。
 *  两个口的形状不同：/goal/pending 套一层 goal，/session/{id}/goal 直接给视图。 */
const loadGoalFor = async sid => {
  const r = await getPendingGoalApi(sid).catch(() => null)
  if (r && r.success && r.data && r.data.goal) return r.data.goal
  if (!r || !r.success) return null      // 拉取失败≠没有目标：不许拿「最近一条」把失败盖成空态
  const g = await getSessionGoalApi(sid).catch(() => null)
  if (g && g.success && g.data && g.data.goalId) return g.data
  return null
}
const pollGoal = () => {
  const sid = currentSessionId.value
  if (!sid) return
  const cur = goalsMap.value[sid]
  if (cur && cur.busy) return
  getPendingGoalApi(sid).then(r => {
    if (!r || !r.success || !r.data) return
    if (currentSessionId.value !== sid) return
    const g = r.data.goal
    if (!g) {
      // 服务端已经没有「还归用户管」的这一条了（跑完/失败/被删），本地那份 RUNNING 是陈的：
      // 不结算的话目标条永远「进行中」、占位气泡的秒数永远涨。后台标签页回来必现——
      // setInterval 被浏览器掐住的那几分钟里终态那帧 goal_state 没人收，只能靠这一跳对账。
      const prev = goalsMap.value[sid]
      if (!prev || !prev.goalId) return
      getSessionGoalApi(sid).then(t => {
        if (!t || !t.success) return                       // 拉取失败≠没有目标：不许盖成空态
        if (currentSessionId.value !== sid) return
        const term = t.data && t.data.goalId === prev.goalId ? t.data : null
        setGoal(sid, term)                                 // 库里真没这条了就直接撤下（term=null）
      }).catch(() => {})
      return
    }
    const prev = goalsMap.value[sid]
    if (prev && prev.busy) return
    // 同一目标同一档不重写，避免无谓的响应式抖动；轮次/提示/停因变了是实质进展，要收下
    if (prev && prev.goalId === g.goalId && prev.status === g.status && prev.round === g.round
        && prev.hint === g.hint && prev.endReason === g.endReason) return
    setGoal(sid, { ...g })
  }).catch(() => { /* 拉不动不提示：目标面板下次帧/下次轮询再试 */ })
}
watch(goalRunning, v => {
  if (v && !goalPollTimer) {
    goalTick = 2   // 第一跳就取一次面板（接流探测不必等）
    goalPollTimer = setInterval(() => {
      const sid = currentSessionId.value
      if (!sid) return
      if (goalRunning.value) watchGoalTurn(sid)
      if (goalTick++ % 3 === 0) pollGoal()
    }, 2500)
  }
  if (!v && goalPollTimer) { clearInterval(goalPollTimer); goalPollTimer = null }
  if (!v) dropGoalPlaceholder()   // 不再自动推进＝这一轮不会再来了，占位那一行必须跟着撤
})
onUnmounted(() => { if (goalPollTimer) clearInterval(goalPollTimer) })
  /** 切会话时：占位气泡不属于新会话就清掉（旧的占位不能跨会话留一行假状态）。 */
  const dropGoalPlaceholderIfOther = sid => {
    if (goalPlaceholderSid !== sid) { goalPlaceholder = null; goalPlaceholderSid = null }
  }
  /** 取本会话当前该接流的占位气泡（RUNNING 且属于这个会话才有）。 */
  const goalPlaceholderFor = sid =>
    (goalPlaceholder && goalPlaceholderSid === sid && goalRunning.value ? goalPlaceholder : null)

  return {
    goalsMap, sessionGoal, setGoal, goalModeOn, goalRunning,
    ensureGoalPlaceholder, dropGoalPlaceholder, takeGoalPlaceholder,
    watchGoalTurn, loadGoalFor, dropGoalPlaceholderIfOther, goalPlaceholderFor,
  }
}
