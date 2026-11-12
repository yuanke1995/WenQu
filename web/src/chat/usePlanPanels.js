// 计划折叠态、完整计划抽屉、批准后调整面板的页面态与动作出口。
// 纯判据在 planPanels.js；引擎取值、动作与提示由调用方经 ctx 注入。
import { ref, reactive, computed, watch } from 'vue'
import { historyRefPreviewText, mentionNamesOf, planHeadTextOf, shouldAutoExpandPlan,
         planTaskTitle, planViewerMessageOf, projectPlanProgress, canOpenPlanAdjust,
         adjustFailureText, planPanelKeydownIntent } from './planPanels.js'

/**
 * @param ctx.messages () => 消息数组（引擎取值函数）
 * @param ctx.currentSessionId/currentSessionTitle/curPlanCard/planExpired/sessionPlans/pendingPlan/pendingMentions/isNarrow ref/computed
 * @param ctx.planStateText/planExecProgressOf/planAdjustsOfMsg 引擎纯函数
 * @param ctx.submitPlanApproval/revisePlanInPlace/continuePlanConversation/adjustPlanAfterApproval/jumpToPlan/stopNow 动作函数
 * @param ctx.notify 通知对象，需带 .info(text)
 */
export const usePlanPanels = ctx => {
  const messages = ctx.messages
  const currentSessionId = ctx.currentSessionId
  const currentSessionTitle = ctx.currentSessionTitle
  const curPlanCard = ctx.curPlanCard
  const planExpired = ctx.planExpired
  const sessionPlans = ctx.sessionPlans
  const pendingPlan = ctx.pendingPlan
  const pendingMentions = ctx.pendingMentions
  const isNarrow = ctx.isNarrow
  const planStateText = ctx.planStateText
  const planExecProgressOf = ctx.planExecProgressOf
  const planAdjustsOfMsg = ctx.planAdjustsOfMsg
  const submitPlanApproval = ctx.submitPlanApproval
  const revisePlanInPlace = ctx.revisePlanInPlace
  const continuePlanConversation = ctx.continuePlanConversation
  const adjustPlanAfterApproval = ctx.adjustPlanAfterApproval
  const jumpToPlan = ctx.jumpToPlan
  const stopNow = ctx.stopNow
  const notify = ctx.notify

  // 计划卡默认折叠；有待确认/生成中计划时自动展开——模型在等人裁决，收起会让这件事消失在侧栏底部。
  const planOpen = ref(false)
  const planHeadText = computed(() => planHeadTextOf(sessionPlans.value, planStateText))
  watch(() => sessionPlans.value.map(p => p.status).join(','), (cur, prev) => {
    if (shouldAutoExpandPlan(cur, prev)) planOpen.value = true
  })

  // 空态引导用：本轮 @ 引用的资料名（副标题说明只在这些资料里找答案）。
  const mentionNames = computed(() => mentionNamesOf(pendingMentions.value))
  // 历史问答引用气泡预览：截断内容做 chip 文案。
  const historyRefPreview = historyRefPreviewText

  // 完整计划抽屉：card 是消息流里同一个计划卡对象，批准/修订/流式增量实时反映，无需另存状态。
  const planViewer = reactive({ open: false, card: null, sid: '', pkey: '', mi: -1, title: '', version: -1, tab: 'plan' })
  /** 打开某轮的完整计划；version=null 按上次停留版本/当前版；tab='exec' 落到执行页签。 */
  const openPlanViewer = (m, i, version = null, tab = 'plan') => {
    if (!m || !m.planCard) return
    planViewer.card = m.planCard
    planViewer.sid = currentSessionId.value || ''
    planViewer.pkey = m.planCard.id || ('i' + i)
    planViewer.mi = i
    planViewer.title = planTaskTitleOf(i)
    planViewer.version = Number.isInteger(version) ? version : -1
    planViewer.tab = tab === 'exec' ? 'exec' : 'plan'
    planViewer.open = true
  }
  const closePlanViewer = () => { planViewer.open = false }
  /** 抽屉里的裁决：动作回引擎，抽屉只收口自身状态。 */
  const onViewerApprove = () => {
    const m = planMsgOfViewer()
    if (!m) return
    submitPlanApproval(m, 'approve')
    closePlanViewer()
  }
  const onViewerRevise = t => {
    const m = planMsgOfViewer()
    if (!m) return
    revisePlanInPlace(m, t)
  }
  const onViewerSupersede = async t => {
    const m = planMsgOfViewer()
    if (!m) return
    closePlanViewer()
    await continuePlanConversation(m, t)
  }
  const onViewerLocate = () => {
    const mi = planViewer.mi
    closePlanViewer()
    if (mi >= 0 && mi < messages().length && messages()[mi]?.planCard === planViewer.card) jumpToPlan(mi)
  }
  /** 抽屉挂在哪条消息：按计划卡对象反查，切会话后自动失效。 */
  const planMsgOfViewer = () => planViewerMessageOf(messages(), planViewer.card, planViewer.sid, currentSessionId.value || '')
  /** 计划标题：该计划之前最近一条用户提问的第一行，截断到 60 字。 */
  const planTaskTitleOf = i => planTaskTitle(messages(), i, currentSessionTitle.value || '')
  // 切会话后抽屉里的卡不在当前消息流里：收起，避免把上一会话的计划批到新会话。
  watch(currentSessionId, () => { planViewer.open = false })

  /** 抽屉当前消息与它的执行进度（执行页签用；切会话/消息流变化时一起失效）。 */
  const planViewerMsg = computed(() => planMsgOfViewer())
  const planViewerProgress = computed(() => {
    const m = planViewerMsg.value
    return m ? planExecProgressOf(m) : null
  })
  /** 执行页签「停止执行」：与输入框旁的停止同一入口。 */
  const onViewerStop = () => { stopNow() }
  /** 抽屉里的本轮调整记录（执行页签）。 */
  const planViewerAdjusts = computed(() => {
    const m = planViewerMsg.value
    return m ? planAdjustsOfMsg(m) : []
  })
  /** 抽屉里点「调整计划」：面板盖在抽屉上，成功后一起收。 */
  const onViewerAdjust = () => {
    const m = planViewerMsg.value
    if (m) openPlanAdjust(m, true)
  }
  /** 抽屉卡头的血缘标：回到旧计划的执行记录。 */
  const onViewerLocateSource = () => {
    const id = planViewer.card && planViewer.card.adjustFrom && planViewer.card.adjustFrom.cardId
    closePlanViewer()
    locatePlanCardById(id)
  }
  /** 按计划卡 id 在消息流里定位（查看新一轮与回溯调整来源共用）。 */
  const locatePlanCardById = id => {
    if (!id) return
    const mi = messages().findIndex(m => m && m.planCard && m.planCard.id === id)
    if (mi >= 0) jumpToPlan(mi)
  }

  // 批准后调整面板：按当前状态算默认档与代价，提交交给引擎，成功收口、失败留在面板给下一步。
  const planAdjust = reactive({
    open: false, m: null, fromViewer: false,
    running: false, done: 0, doing: 0, total: 0, failed: 0, stepLabel: '',
    busy: false, error: '', suggestReplan: false
  })
  // 面板打开期间本轮结束/新清单到达：running 跟着消息对象走，选项与二次确认不撒谎。
  watch(() => planAdjust.m && planAdjust.m.loading, v => {
    if (!planAdjust.open) return
    planAdjust.running = !!v
  })
  // 切会话：面板与它挂的那条消息一起作废，避免把意见发到新会话。
  watch(currentSessionId, () => {
    planAdjust.open = false
    planAdjust.m = null
    planAdjust.error = ''
    planAdjust.suggestReplan = false
  })
  const openPlanAdjust = (m, fromViewer = false) => {
    const c = m && m.planCard
    if (!canOpenPlanAdjust(c)) return
    const proj = projectPlanProgress(planExecProgressOf(m))
    planAdjust.m = m
    planAdjust.fromViewer = !!fromViewer
    planAdjust.running = !!m.loading
    planAdjust.done = proj.done
    planAdjust.doing = proj.doing
    planAdjust.total = proj.total
    planAdjust.failed = proj.failed
    planAdjust.stepLabel = proj.stepLabel
    planAdjust.busy = false
    planAdjust.error = ''
    planAdjust.suggestReplan = false
    planAdjust.open = true
  }
  const closePlanAdjust = () => {
    if (planAdjust.busy) return
    planAdjust.open = false
    planAdjust.m = null
    planAdjust.error = ''
    planAdjust.suggestReplan = false
  }
  const submitPlanAdjust = async ({ text, mode }) => {
    if (planAdjust.busy) return
    const m = planAdjust.m
    if (!m) return
    planAdjust.busy = true
    planAdjust.error = ''
    planAdjust.suggestReplan = false
    let r
    try {
      r = await adjustPlanAfterApproval(m, text, mode)
    } catch (e) {
      r = { ok: false, reason: 'error' }
    }
    planAdjust.busy = false
    if (!r || !r.ok) {
      const failure = adjustFailureText(r && r.reason)
      planAdjust.error = failure.error
      planAdjust.suggestReplan = failure.suggestReplan
      return
    }
    const wasRunning = planAdjust.running
    const fromViewer = planAdjust.fromViewer
    planAdjust.open = false
    planAdjust.m = null
    planAdjust.error = ''
    planAdjust.suggestReplan = false
    if (fromViewer) closePlanViewer()
    notify.info(mode === 'replan'
      ? (wasRunning ? '已停止本轮，正在按新意见重新计划' : '正在按新意见重新计划')
      : '已送到正在执行的一轮，下一步开始前生效')
  }
  const locateReplanned = m => {
    const id = m && m.planCard && m.planCard.replannedBy && m.planCard.replannedBy.cardId
    locatePlanCardById(id)
  }
  const locatePlanSource = m => {
    const id = m && m.planCard && m.planCard.adjustFrom && m.planCard.adjustFrom.cardId
    locatePlanCardById(id)
  }

  // 卡片本体收在共用组件；这里只剩 PC 特有的意见框文案与键盘直达。
  const planEditPlaceholder = computed(() => isNarrow.value
    ? '要改什么？同一轮内按意见升版…'
    : '要改什么？同一轮内按意见升版，不新增气泡…（Enter 提交，Shift+Enter 换行）')
  /** 意见框回车=提交修改（Shift 留给换行，输入法选字的回车不算）；恢复态改走「转到对话修改」。 */
  const onPlanPanelKeydown = e => {
    const intent = planPanelKeydownIntent(e, curPlanCard.value, planExpired.value)
    if (!intent) return
    e.preventDefault()
    if (intent.restored) continuePlanConversation(pendingPlan.value, intent.text)
    else revisePlanInPlace(pendingPlan.value, intent.text)
  }

  return {
    planOpen, planHeadText, mentionNames, historyRefPreview,
    planViewer, openPlanViewer, closePlanViewer, onViewerApprove, onViewerRevise,
    onViewerSupersede, onViewerLocate, planMsgOfViewer, planTaskTitleOf,
    planViewerMsg, planViewerProgress, onViewerStop, planViewerAdjusts, onViewerAdjust,
    onViewerLocateSource, locatePlanCardById,
    planAdjust, openPlanAdjust, closePlanAdjust, submitPlanAdjust, locateReplanned, locatePlanSource,
    planEditPlaceholder, onPlanPanelKeydown
  }
}
