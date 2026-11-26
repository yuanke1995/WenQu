import { ref, reactive, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isAdminSync, isLoggedIn } from '../utils/auth'
import { message } from 'ant-design-vue'
import { applyPlanFrame, applyGoalState, applyGoalTurn } from './streamStateFrames.js'
import { applyPlanDelta, applyPlanApproval, applyPlanCancelled, applyPlanSuperseded } from './streamPlanFrames.js'
import { applyImageFrame, applyTurnStart } from './streamMerge.js'
import { applyRetrievedFrame, applySkillHitFrame, applyAgentDelegated, applyApprovalCardFrame, applyRunCodeProgress, applyTodoFrame, applyUsageFrame } from './streamInfoFrames.js'
import { applySubagentStep } from './streamSubagentFrames.js'
import { applyToolStatus, appendToolOutput } from './streamToolFrames.js'
import { buildTurnRequest } from './turnRequest.js'

import { sendQuestion, resumeChat, getChatRun, newSession, getHistory, deleteSessionApi, switchMessageVariant, compactSessionApi, sessionHistorySummaryApi, listAvailableAgents, listConsultableAgents,
// ==================== 聊天引擎 composable ====================
// 承载聊天页的「引擎逻辑」：思考/模型/智能体状态、会话生命周期、附件与 @ 引用载荷、
// 发送与 SSE 流式回答、审批、重新生成与分支切换。与视图的接缝全部收在 hooks 里
// （滚动、收面板、聚焦），PC 端由 ChatPage 按原行为传参；移动壳可传自己的落点
// （如 '/m/chat'）与滚动实现复用同一套引擎。纯函数/常量见 ./projections.js。
         listAvailableSkills, approveToolCall, getToolApproval, answerAgentAsk, ignoreAgentAsk, stopChatTurn, steerChatTurn,
         enqueueSteer, steerQueueList, steerQueued, dropQueued,
         listPendingAsks, listPendingApprovals, resolvePlanApproval, supersedePlanApproval, revisePlanApproval, listPendingPlans, updateSessionModelApi, getSessionModelApi,
         listKnowledgeBases, listDocuments, uploadChatAttachment, getSessionTodosApi,
         listSandboxFiles, downloadSandboxFile, sandboxFileToArtifact,
         listSessionProcs, killSessionProc, killAllSessionProcs,
         startGoalApi, goalActionApi,
         downloadArtifactsZip,
         refreshArtifactSignsApi } from './chatTransport.js'
import { sessionStore, loadSessions, chatStreams, todosMap, markSessionActive, touchSessionRow, refreshProcBadges } from '../views/store'
import { markLive, splitAtts } from './attachments'
// 取材判定层：图片压缩参数、附件白名单与各项限额都在这里（分享页共用同一份，见 ./intake.js）
import { MAX_IMAGES, MAX_FILES, classifyIntake, rejectText, REJECT, compressImage, pasteFilesOf,
         isLongPaste, pasteNameOf } from './intake'
import { approvalStatusText } from './approval'
import {
         groupSources, ensureTick, alignTick, restoreTimeline, extendTimelineText, extendTimelineProcess,
         pushTimelineArtifact, pushTimelineSteer, ensureThinkSegment,





         artifactPendingOf,
         rememberAgents, applyBlocks } from './projections'
import { createThinkingLevels } from './thinkingLevels.js'
import { createSkillPicker } from './skillPicker.js'
import { createMentionPicker } from './mentionPicker.js'
import { createAttachmentPicker } from './attachmentPicker.js'
import { useSandboxFiles } from './useSandboxFiles.js'
import { useSandboxProcs } from './useSandboxProcs.js'
import { useContextPanel } from './useContextPanel.js'
import { useGoalProjection } from './useGoalProjection.js'
import { useCompactSession } from './useCompactSession.js'
import { useDeliveryPackages } from './useDeliveryPackages.js'
import { createAgentPicker } from './agentPicker.js'
import { createModelSelection } from './modelSelection.js'
import { createTurnDividers } from './turnDividers.js'
import { useSteerDispatch } from './useSteerDispatch.js'
import { usePlanActions } from './usePlanActions.js'
import { useGoalEntry } from './useGoalEntry.js'
import { useTurnReconnect } from './useTurnReconnect.js'
import { useChatRouting } from './useChatRouting.js'
import { useSessionLifecycle } from './useSessionLifecycle.js'
import { useEngineBootstrap } from './useEngineBootstrap.js'
import { useSteerAndVersion } from './useSteerAndVersion.js'
import { switchVersion as switchVersionImpl, regenerate as regenerateImpl } from './versionActions.js'
import { buildHistoryList, dropSteeredUserRows } from './historyRows.js'
import { createSend } from './sendFlow.js'
import { settleAbortedPlan } from './planAbort.js'
import { failTurn } from './turnFail.js'
import { createSessionSwitch } from './sessionSwitch.js'
import { planCardFromRecord, linkPlanLineage } from './planCards.js'
import { usePendingCards } from './usePendingCards.js'
import { useApprovalClock } from './useApprovalClock.js'
import { useSessionTodos } from './useSessionTodos.js'
import { useGoalState } from './useGoalState.js'
import { useHistoryRefs } from './useHistoryRefs.js'
import { usePendingPlan } from './usePendingPlan.js'
import { pushTimelineTool } from './timelineWrite.js'
import { isDispatchTool, consultNameOf, stepBriefOf } from './projections'
import { createFrameHandlers, createClosingHandlers } from './streamAnswerHandlers.js'
import { useHitlRestore } from './useHitlRestore.js'
import { createStreamBuffer } from './streamBuffer.js'
import { applyCompactState } from './streamStateFrames.js'
import { createThoughtFrames } from './streamThoughtFrames.js'
import { closeoutDoneTurn } from './turnDoneCloseout.js'
import { applyDonePayload } from './applyDonePayload.js'
import { applyTurnSnapshot as reduceTurnSnapshot, settlePreparingCards, replaySnapshotExtras } from './streamReducer.js'
import { askAnswerText, askAutoSubmittable, askQuestionAnswered, askAnsweredCount, askNextUnanswered,
         pickAskOption, commitAskCustom, askOptionPicked, askCustomPicked, askHintText } from './askUser.js'
import { submitAsk as submitAskImpl, ignoreAsk as ignoreAskImpl } from './askActions.js'
import { attachAskCard, retireAskCard } from './askReducer.js'
import { planVersionOf, planStepCount } from './plan'
// 裁决完卡片立刻让铃铛角标重算（后端会把已处理的待办通知收敛为已读），不等 30s 轮询
import { emitNotifChanged } from './useNotifications'
import { setPlanReplanned } from './planUiState'

export function useChatEngine (hooks = {}) {
  const route = useRoute()
  const router = useRouter()
  // hooks 全部可选（缺省安全）：PC 端传原行为的实现；hook 实现方自己决定滚动时序。
  // 可用字段：chatPath / scrollFollow / scrollForce / scrollSoft / closePanels / focusInput。
  // 这里不再解构：调用点统一走 hooks.xxx?.()，解构出来的默认值反而会和调用点漂移。
const text = ref('')
/**
 * 计划模式（人在回路）已并入深度执行：用户不再单独开关，只有引擎内部的
 * 「退回重出 / 带着意见重做」会把这一轮强制设为先出计划。变量保留给这两条内部路径，
 * 初始恒 false，也不落 localStorage（旧键 ai_plan_mode 不再读取，避免残留值让下一轮冒出一张计划卡）。
 * 这道闸仍是必要的：没有计划卡 UI 的壳（如分享页）被拦在门外。
 */
// 团队实时事件的中转：聊天流里收到 team_* 帧（CHAT_EVENT_CB 映射到 onTeam*）后，
// 用 window 自定义事件通知旁侧的团队面板/胶囊去抖重拉。帧本身不进正文时间线。
function dispatchTeamEvent () {
  try { window.dispatchEvent(new CustomEvent('wq:team-event')) } catch (e) { /* 无 window 时忽略 */ }
}

const planModeEnabled = hooks.planModeEnabled === true
const planModeOn = ref(false)
const setPlanMode = v => {
  if (!planModeEnabled) return
  planModeOn.value = !!v
}
// 思考档位状态（按模型记忆 / 可选项 / 下发参数）在 ./thinkingLevels.js
const { thinkCapsOf, reasoningLevelsOf, deepThinkMap, levelMap, deepOnOf, deepThinkOn,
        levelOptionsOf, currentLevelOf, setThinkLevel, currentThinkLevel, reasoningLevelParam } = createThinkingLevels({
  modelIndexOf: () => modelIndex.value,
  effectiveModelOf: () => effectiveModel.value,
  isBusy: () => loading.value
})
const canSend = computed(() => !!(text.value.trim() || pendingImages.value.length || pendingFiles.value.length))
// ==================== 技能（输入框「+」菜单选用，仅对本轮生效） ====================
// 技能清单/点名/头像底色在 ./skillPicker.js
const { skillList, pickedSkills, skillDoc, loadSkills, toggleSkill, skillAvaStyle } = createSkillPicker({
  listApi: sid => listAvailableSkills(sid),
  currentSid: () => currentSessionId.value,
  notifyWarning: msg => message.warning(msg)
})
// ==================== 智能体：本轮作答方，会话不再锁定 ====================
// 清单/会话待选/当前作答方/徽标去重在 ./agentPicker.js
const isAdmin = ref(isAdminSync())
const agentPickerOpen = ref(false)
const { agentList, agentMap, defaultAgentId, hasDefaultAgent, currentAgentId,
        pickAgent, currentAgentName, currentAgent, showAgentTag, loadAgents } = createAgentPicker({
  currentSid: () => currentSessionId.value,
  messages: () => messages.value,
  listAgentsApi: listAvailableAgents,
  listConsultableApi: listConsultableAgents,
  remember: rememberAgents,
  onPick: () => { agentPickerOpen.value = false }
})
// 分隔线判据（模型切换/上下文压缩）在 ./turnDividers.js
const { modelLabelOf, modelSwitchInfo, compactDividerAt, liveCompactAt } = createTurnDividers({
  modelIndexOf: () => modelIndex.value,
  messages: () => messages.value,
  isLive: () => loading.value,
  liveCompactedSid: () => liveCompactedSid.value,
  currentSid: () => currentSessionId.value
})
const currentSessionId = ref(null)
// 「工作区」那一路技能随会话走：换会话必须重拉清单，否则「+」里还是上一个会话的技能（或空着不给看）
watch(currentSessionId, () => { loadSkills() })
// 正在重挂的会话（sid）：连接断了、服务端那一轮还活着，见下方 turnReconnect
const reconnectingSids = new Set()
// 「当前会话正在回答」：流式记录按会话各存一条（chatStreams，store.js 模块单例，跨路由存活）。
// 只表示"正在看的这个会话在答"——其它会话后台流不影响新建/切换/发送；
// 同一会话内仍互斥（输入/重新生成/停止都作用于当前会话），守卫写法不用动。
// 正在重挂的会话同样算「在答」：这时本地已经没有流（那条通道死了），但服务端那一轮还在跑、
// 还占着会话互斥。不把它算进来，输入框会放行一条必被服务端拒掉的新提问。
const loading = computed(() => chatStreams.has(currentSessionId.value) ||
  reconnectingSids.has(currentSessionId.value))
const messages = ref([])
/**
 * 当前会话的历史**没取回来**的原因（空串=取到了，不论有没有内容）。
 * <p>必须有这一格：拉取失败与空会话不能长成同一张欢迎页，两端各自按它决定露错误卡还是露空态。
 */
const historyError = ref('')
const currentSessionTitle = computed(() => {
  const s = sessionStore.list.find(x => x.id === currentSessionId.value)
  return s?.title || '新对话'
})
// ==================== 模型切换：会话级覆盖 > 智能体 > 个人默认 > 全局 ====================
// 选择状态与生效模型口径在 ./modelSelection.js
const { modelMap, modelIssueMap, currentOverrideModel, seedSessionModels,
        userDefaultModel, userDefaultModelIssue, modelIndex,
        effectiveModel, effectiveModelLabel, effectiveModelIcon, effectiveModelProvider,
        modelSourceLabel, effectiveModelIssue } = createModelSelection({
  currentSid: () => currentSessionId.value,
  sessionList: () => sessionStore.list,
  updateSessionApi: updateSessionModelApi,
  notifyError: msg => message.error(msg),
  labelOf: ref => modelLabelOf(ref)
})
// 监听列表本身，而不是在挂载流程里调一次：会话列表由侧栏（AppLayout）与聊天页各自触发加载，
// 并发时 loadSessions 的加载代次守卫会丢弃较早的一次（列表还没写入），挂载时点读到的可能是空列表。
// deep 兼顾「查看更多」的 push 增量；immediate 兼顾列表在引擎初始化前就已加载的情形。
// seed 幂等且只做字典合并，列表每次变动重跑无副作用。
watch(() => sessionStore.list, seedSessionModels, { deep: true, immediate: true })
// 「检索调试」/「加入评测集」菜单项专属（管理端点，普通用户必 403）：只在管理员身份下从 /config 读取
const debugEntryVisible = ref(false)
// 排障显示开关（chat.retrievalDebugEnabled，设置页「检索调试入口」）：
// 统一控制回答气泡上的「由 X 回答」归属徽标、「已派遣 X」路由提示、引用分值（右栏来源列表分数 + 角标悬浮卡分数）。
// 值必须走 /config/public（管理员/普通用户都能读）——这些是"给不给用户看"的显隐，普通用户也要拿到同一个开关值；
// /config 是管理端点，普通用户调它 403 并触发全局 403 提示（角色未授权），故不复用。
const debugDisplayVisible = ref(false)
// 「接下来可以」推荐追问是否展示：个人显示偏好（个人设置 → 问答偏好 → 对话显示），
// 与排障开关同一份公开运行时配置下发（/config/public 的 ui.showRelatedSuggestions，按登录用户读回）。
// 默认开：普通用户也能拿到自己的开关值，PC 与移动壳同吃这一份。
const showRelatedSuggestions = ref(true)
// 右栏「引用来源」卡与输入框容量圆环锚定的轮。带计划卡的行也算：被「继续对话」取代那轮正文留空，
// 但它确实检索过，不算进来这两处就会退回更早那一轮
const lastAi = computed(() => [...messages.value].reverse().find(m => m.role === 'ai' && !m.loading && (m.content || m.sources?.length || m.planCard)))
const lastSources = computed(() => lastAi.value?.sources || [])
// 引用来源按文档分组：先看到"引用了哪几个文档、各几段"，再按需展开看具体片段
// （平铺 N 行时同一文档的片段会重复出现文件名，反而看不出引用了几个来源）
// 分组内核抽为纯函数 groupSources（projections）：这里只保留响应式包装
const groupedSources = computed(() => groupSources(lastSources.value))

// 本次用量（Token 消耗可视化，1.9）：来自 done 事件的 tokens（上下文实际/预算/块数 + 输出估算）
const lastTokens = computed(() => lastAi.value?.tokens || null)
// 流式中的用量预览（后端 usage 事件：第一次模型回包后到、之后每一步各刷一次，都是已发生那几次调用的
// 网关实测值；onUsage 挂在 msg.tokensPreview）：只看最新一轮 AI 消息——该轮生成中且预览已到才返回；否则为 null
// （回落 lastTokens：流式期间显示上一完成轮的用量）。done 的实测 tokens 到达后自然切换为终值。
const liveTokensPreview = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const m = messages.value[i]
    if (m.role !== 'ai') continue
    if (m.loading && m.tokensPreview) return m.tokensPreview
    break
  }
  return null
})
// 容量圆环/明细卡数据源：流式预览优先（生成一开始就亮），无预览回落已完成轮的实测 tokens
const ctxTokens = computed(() => liveTokensPreview.value || lastTokens.value)
// 上下文容量面板与右栏产物投影已抽到 useContextPanel.js（纯展示投影 + 签名换发）。
// 这里解构出同名符号，下面所有读写点一行不改。
const {
  ctxCapData, ctxRingDash, ctxRingLevel,
  panelAi, sessionArtifacts, sessionRetrieval, sessionTokens, refreshArtifactSigns,
} = useContextPanel({
  ctxTokens, effectiveModel, modelIndex, messages, lastAi,
  refreshApi: refreshArtifactSignsApi, notify: message,
})
// ==================== 图片（「+」菜单选择 / 拖入 / 粘贴 → 压缩为 dataURL） ====================
// 压缩参数、附件白名单与各项限额已抽到 ./intake.js：分享页要用同一份口径，
// 附件/图片的待发状态与上传逻辑已抽到 createAttachmentPicker（见 attachmentPicker.js）。
// 这里解构出同名变量，下面所有读写点不动，行为等价。
const {
  pendingImages, removePendingImage, pendingFiles, hasUploadingFile,
  addFiles, removePendingFile, attachUploaded, attachImage, takePastedFiles, takePastedText,
} = createAttachmentPicker({
  uploadApi: uploadChatAttachment, notify: message, markLive,
  maxImages: MAX_IMAGES, maxFiles: MAX_FILES,
  classify: classifyIntake, rejectText, REJECT, compressImage,
  pasteFilesOf, pasteNameOf, isLongPaste,
})
// 引用只对当轮生效（与会话级智能体绑定不同）：不落库、不跨轮继承，重新生成时随内存消息重发。
// @ 引用选取的状态与加载逻辑已抽到 createMentionPicker（见 mentionPicker.js）。
// 这里只解构出本文件继续用的名字，下面所有读写点一行不改。
const {
  mentionOpen, mentionTab, mentionQuery, mentionLoading, mentionKbs, mentionDocs,
  pendingMentions, mentionHi, MAX_MENTIONS, isMentioned, toggleMention, removeMention,
  switchMentionTab, loadMentionCandidates, mentionDocStatus, mentionQueryNorm,
  mentionKbFiltered, mentionAgents, mentionAgentFiltered, mentionDocFiltered,
} = createMentionPicker({
  listKbApi: listKnowledgeBases, listDocApi: listDocuments,
  agentList, notify: message,
})
// 历史消息引用已抽到 useHistoryRefs.js。这里解构接回同名符号。
const { MAX_HISTORY_REFS, pendingHistoryRefs, histPool, isHistPicked, toggleHistoryRef, removeHistoryRef } =
  useHistoryRefs({ messages, notify: message })
// ==================== 会话 ====================
/**
 * 各会话当前任务清单：sid → {turnId, items:[{content,status}], updatedAt}。
 * 实时由 SSE 的 todo 事件写入，切会话/刷新按需回捞（后端整表覆盖，只存最新一份）。
 * 数据本身是模块单例（store.js）：轮内切走路由再回来会重建这个引擎，清单挂在实例上就会
 * 只剩切走那一刻的旧值、且再也不更新（见 store.js 里 todosMap 的注释）。
 */
// 会话任务清单与计划执行进度已抽到 useSessionTodos.js。
// sessionGoal 定义在本块之后，故以惰性 getter 传入；这里解构接回同名符号，模板引用不变。
const {
  sessionTodos, setTodos, ensureTurnTodos,
  planExecProgressOf, planAdjustsOfMsg,
  todoSummary, todoAllDone, todoBarVisible,
} = useSessionTodos({
  currentSessionId, messages, loading, panelAi, sessionGoalOf: () => sessionGoal,
})

/**
 * 各会话当前目标（目标模式）：sid → 目标视图模型。
 * 实时由 SSE 的 goal_state 帧写入（阶段迁移/轮次推进/停因的唯一帧），
 * 切会话/刷新按需回捞（后端读 DB 不读框架：重启/换副本后面板照常长出来）。
 * 与 sessionTodos 同形：会话级一份、不挂气泡——目标横跨多轮，不是某一轮的东西。
 */
// 会话目标状态与轮询已抽到 useGoalState.js。这里解构接回同名符号，模板/其它块引用不变。
const {
  goalsMap, sessionGoal, setGoal, goalModeOn, goalRunning,
  ensureGoalPlaceholder, dropGoalPlaceholder, takeGoalPlaceholder,
  watchGoalTurn, loadGoalFor, dropGoalPlaceholderIfOther, goalPlaceholderFor,
} = useGoalState({
  currentSessionId, currentAgent, messages, hooks,
  attachRunningTurnOf: () => attachRunningTurn,
})

// ==================== 交付包（产物 → 一个可验收的交付对象） ====================
// 投影与 ZIP 下载已抽到 useDeliveryPackages.js。sessionGoal 定义在本块之后，
// 故以惰性 getter 传入（computed 求值时它已初始化）。这里解构接回同名符号，模板引用不变。
const {
  goalDelivery, turnDeliveries, deliveryPackages,
  deliveryOfMessage, deliveryCardOfMessage, freshDeliveryOf, downloadDeliveryZip,
} = useDeliveryPackages({
  messages, sessionGoalOf: () => sessionGoal,
  downloadArtifactsZip, notify: message,
})



// 会话切换编排（历史加载/在途复用/失败回退/接流与卡片恢复）在 ./sessionSwitch.js
const { switchSession, historyLoads } = createSessionSwitch({
  currentSid: () => currentSessionId.value,
  setCurrentSid: sid => { currentSessionId.value = sid },
  settleSteer: () => settleSteerOnLeave(),
  dropGoalPlaceholderIfOther: sid => dropGoalPlaceholderIfOther(sid),
  goalPlaceholderFor: sid => goalPlaceholderFor(sid),
  modelMap,
  getSessionModel: sid => getSessionModelApi(sid),
  todosMap,
  getSessionTodos: sid => getSessionTodosApi(sid),
  setTodos: (sid, todos) => setTodos(sid, todos),
  goalsMap,
  loadGoalFor: sid => loadGoalFor(sid),
  setGoal: (sid, doc) => setGoal(sid, doc),
  syncUrl: sid => router.replace({ path: hooks.chatPath, query: { ...route.query, sid } }).catch(() => {}),
  replaceHome: () => router.replace({ path: hooks.chatPath }).catch(() => {}),
  urlSid: () => route.query.sid,
  deepApprovalOf: () => (route.query.approval ? String(route.query.approval) : ''),
  getHistory: (sid, opts) => getHistory(sid, opts),
  buildList: (rows, sid) => buildHistoryList(rows, sid, {
    planCardFromRecord, restoreTimeline, applyBlocks, ensureThinkSegment
  }),
  dropSteered: list => dropSteeredUserRows(list),
  historyError,
  chatStreams,
  messages,
  linkPlanLineage: list => linkPlanLineage(list),
  ensureTurnTodos: (sid, list) => ensureTurnTodos(sid, list),
  refreshArtifactSigns: () => refreshArtifactSigns(),
  attachRunningTurn: sid => attachRunningTurn(sid),
  hydrateAsk: sid => hydratePendingAsk(sid),
  hydratePlans: sid => hydratePendingPlans(sid),
  hydrateApprovals: (sid, id) => hydratePendingApprovals(sid, id),
  handleDeepLink: (sid, id) => handleApprovalDeepLink(sid, id),
  scrollForce: () => hooks.scrollForce?.(),
  loadSessions: () => loadSessions(),
  autoPick: () => autoPick()
})

// 会话生命周期已抽到 useSessionLifecycle.js。
const { retryHistory, creatingSession, workspaceEpoch, createNewSession,
        handleDeleteSession, autoPick, takeNewSessionAgent } = useSessionLifecycle({
  currentSessionId, messages, historyError, sessionStore, chatStreams, agentMap, hooks,
  switchSession, router, newSession, deleteSessionApi, loadSessions,
  // settleSteerOnLeave 由 useSteerDispatch 在本函数后面才装配（它依赖 streamAnswer），
  // 直接传标识符会触发 TDZ（Cannot access before initialization）→ 整个 setup 抛错白屏。
  // 传惰性包装：真正被调用时（切会话那一刻）它早已初始化。
  settleSteerOnLeave: () => settleSteerOnLeave(),
  isLoggedIn, notify: message,
})

// 路由 watcher 与审批动作已抽到 useChatRouting.js。
const { resolveApproval } = useChatRouting({
  route, hooks, currentSessionId, historyLoads, switchSession,
  createNewSession, autoPick, takeNewSessionAgent, sessionStore, chatStreams, messages,
  notify: message, emitNotifChanged, pollRestoredAnswer: sid => pollRestoredAnswer(sid),
  approveToolCall,
  // 这两个由本函数后面的 useHitlRestore / useApprovalClock 装配，此处直接传标识符会 TDZ。
  // useChatRouting 只在路由 watcher 与点击时调用它们，惰性包装等价。
  handleApprovalDeepLink: (...a) => handleApprovalDeepLink(...a),
  approvalLeftSecOf: (...a) => approvalLeftSecOf(...a),
})

// 待批准计划卡与倒计时已抽到 usePendingPlan.js。这里解构接回同名符号。
const { pendingPlan, curPlanCard, planExpired, planCountdownText } = usePendingPlan({ messages })
// 计划卡中断收尾在 ./planAbort.js（纯模块）。

// 目标模式入口（startGoal / goalAct）已抽到 useGoalEntry.js。
const { startGoal, goalAct } = useGoalEntry({
  currentSessionId, messages, hooks, currentAgentId, sessionGoal, goalsMap, setGoal,
  goalRunning, watchGoalTurn, startGoalApi, goalActionApi, markSessionActive, notify: message,
  // goalBarVisible 在下面才由目标投影装配；对方以 .value 读，getter 同样是惰性求值。
  goalBarVisible: { get value () { return goalBarVisible.value } },
})

// 目标条状态投影与就地编辑已抽到 useGoalProjection.js（投影函数从 projections.js 直接导入）。
// 这里解构出同名符号，下面所有读写点一行不改。
const {
  goalTerminal, goalBarVisible, goalPhrase, goalStateLabel, goalStateClass, goalRoundLine,
  goalBlockReason, goalBarTitle, goalBarMeta, goalCanPause, goalCanResume, goalCanEdit,
  goalCanCancel, goalCanClearRecord, goalPreempted, goalVersionLabel, goalProgressPercent, goalTerminalCard,
  goalTerminalTitle, goalTerminalNote, goalTurnStreaming, surrenderGoalStream, goalBarError, runGoalAction,
  goalEditing, goalEditBusy, goalEdit, goalEditSaveable, startGoalInlineEdit, saveGoalEdit,
} = useGoalProjection({ sessionGoal, currentSessionId, chatStreams, messages, goalAct })

/**
 * 计划模式（人在回路）：裁决当前气泡挂起的执行计划——批准 / 取消本轮。
 *  action='approve' 批准执行（后端仍兼容带改稿批准，前端不再提供该入口）；
 *  action='cancel' 取消本轮，卡片定格「未批准」。
 *  对计划不满意走 {@link continuePlanConversation}（带着意见继续对话，本版作废）。
 *  恢复态卡（restored）：本设备没有这一轮的流，批准在后台进行，靠轮询取回结果。 */
// 计划模式动作已抽到 usePlanActions.js（streamAnswer 以惰性包装注入）。
const {
  submitPlanApproval, revisePlanInPlace, continuePlanConversation,
  adjustPlanAfterApproval,
} = usePlanActions({
  currentSessionId, messages, loading, hooks, deepThinkOn, effectiveModel,
  goalRunning: { get value () { return goalRunning.value } },
  chatStreams, streamAnswer: (...a) => streamAnswer(...a), notify: message,
  // steer/stop/enqueuePlanAdjust 在本函数后面才装配（依赖 streamAnswer），对方只在回调里调用。
  steer: (...a) => steer(...a),
  stop: (...a) => stop(...a),
  resolvePlanApproval, revisePlanApproval, supersedePlanApproval, emitNotifChanged,
  stopRestoredPoll: () => stopRestoredPoll(), pollRestoredAnswer: sid => pollRestoredAnswer(sid),
  switchSession,
  enqueuePlanAdjust: (...a) => enqueuePlanAdjust(...a),
  planExecProgressOf, setPlanReplanned, planVersionOf, planStepCount,
})
/**
 * 智能体提问（一卡多问）：在某题上点一个候选——只记录选择，不提交（提交由 askSubmitAll 一次性批量完成）。
 * <p>单选（默认）与多选走同一个状态形状（{@code picks} 永远是下标数组），差别只在写入规则：
 * 单选把数组压成一项并撤掉自定义行（候选与自由输入互斥，沿用旧行为）；多选是 toggle，可与自定义并存。
 * <p>为什么由题面带 {@code multi} 而不是按选项数或问句里有没有「哪些」去猜：猜出来的选择面会把
 * 「两条路选哪条」这种互斥题变成能同时勾三项的自相矛盾答案，而模型问完会照着那个组合往下做。
 */
// 提问卡勾选/自定义/提示口径在 ./askUser.js（纯模块）。
/** 造一张提问卡（SSE 实时下发与刷新/换设备后重建共用这份形状）。
 * @param remainingMs 倒计时剩余毫秒；不传则按 j.timeoutMs 从此刻起算（实时路径没有时差）
 * j.restored=true 表示这张卡是从服务端待答记录重建的：本设备没有这一轮的流式通道，答完后回答在
 * 后台生成，要靠 pollRestoredAnswer 轮询历史取回。
 */
// 提问卡构建/排队/撤卡在 ./askReducer.js（reducer 层）。
// 早产卡收场在 ./streamReducer.js（reducer 层）。
/**
 * 恢复出来的卡片挂到哪条气泡——按记录带的 turnId 认（一问一答共用一个 id，与 c_ai_message 同源）。
 * 挂「这台设备手上的那一轮」会让更早一轮的待决卡挂到最新的气泡上：
 * 用户答的是上一轮的题，屏幕上指的却是这一轮，裁决送得回去但看着是错的。
 *   - 这台设备有在跑的流，且它要么没有轮身份（本轮助手消息还没落库，实时气泡拿不到 turnId），
 *     要么正是这一轮 → 挂它；轮身份未知时不另造气泡，宁可照旧挂；
 *   - 明确不是这一轮 → 去找那一轮已落库的气泡；找不到返回 null，调用方补占位泡并盖上 turnId，
 *     下次恢复仍回到同一颗（不叠第二颗）。
 */
const msgByTurn = turnId => {
  if (!turnId) return null
  const list = messages.value || []
  for (let i = list.length - 1; i >= 0; i--) {
    const m = list[i]
    if (m && (m.role === 'ai' || m.role === 'assistant') && m.turnId === turnId) return m
  }
  return null
}
const cardTargetFor = (st, turnId) => {
  if (st && st.msg && (!turnId || !st.msg.turnId || st.msg.turnId === turnId)) return st.msg
  return msgByTurn(turnId)
}

/**
 * 会话加载/切换后恢复待答提问卡：提问已落库、等待期间断线不中止本轮，所以刷新页面或换设备后
 * 那根工具线程还阻塞着，答案照样送得回去。只重建「未过期且唤醒句柄还在」的最新一张——
 * 同一轮连问几卡时，早先那张已随工具终态落成气泡里的问答记录。
 */
// 提问卡与计划卡的恢复已抽到 usePendingCards.js（审批卡与恢复态轮询共用 restoredPoll，留在本文件）。
// 这里解构出同名函数，下面所有调用点不改。
const { hydratePendingAsk, hydratePendingPlans } = usePendingCards({
  chatStreams, currentSessionId, messages, hooks, notify: message,
  attachAskCard, listPendingAsks, listPendingPlans, cardTargetFor, msgByTurn,
})

// 审批倒计时/清理（秒针 ref、清地址栏参数、撤同卡）
const { approvalNow, approvalLeftSecOf, clearApprovalQuery, clearApprovalCard } = useApprovalClock({
  messages, route, router, hooks,
})

// HITL 恢复（审批卡恢复/通知深链/恢复态轮询/提问卡动作）已抽到 useHitlRestore.js。
// restoredPoll 随块搬进模块；stopRestoredPoll/pollRestoredAnswer 等由模块导出、这里解构接回，
// planCtx（较早定义、惰性调用）与 onUnmounted 用的是解构后的同名符号。
const {
  stopRestoredPoll, hydratePendingApprovals, handleApprovalDeepLink,
  pollRestoredAnswer, askSubmitAll, ignoreAsk,
} = useHitlRestore({
  chatStreams, currentSessionId, messages, hooks, text, send: () => send(), switchSession,
  listPendingApprovals, getToolApproval, getHistory, answerAgentAsk, ignoreAgentAsk,
  retireAskCard, emitNotifChanged, submitAskImpl, ignoreAskImpl,
  cardTargetFor, approvalStatusText, clearApprovalCard, clearApprovalQuery, notify: message,
})

// 接流与断线重挂已抽到 useTurnReconnect.js。
const { finishResumedShell, streamLifecycle, attachRunningTurn, turnReconnect, cancelReconnect } = useTurnReconnect({
  chatStreams, currentSessionId, switchSession, getChatRun, reconnectingSids,
  ensureGoalPlaceholder, takeGoalPlaceholder, streamAnswer: (o) => streamAnswer(o),
})

// 接流快照 reducer 在 ./streamReducer.js（reducer 层），引擎只注入状态与派生函数。
/**
 * 收一轮问答的流。resume=true 时这一轮不是本地发起的：接的是服务端还在跑的那一轮
 * （见 attachRunningTurn），此时不发 POST /chat，改开 GET /chat/resume 通道，
 * 正文与工具卡片等由第一帧 snapshot 补齐、之后与实时流走同一套回调。
 */
// 13 处调用点仍是位置参数（sendFlow/planActions/steerDispatch/versionActions/移动壳等），
// 这里保持原签名不变、内部收敛成一份 opts，避免大范围改调用方。
// 参数位多（13 处调用点用位置参数），这里用 rest 收尾把 17 个参数压到 4 个具名 + 1 个 rest。
const streamAnswer = (question, imgs, replaceMsg, isFirstMessage, ...rest) => {
  const [autoRetry = 1, deepThink = false, attachments = [], skills = [], mentions = [],
    prev = null, historyRefs = [], editMessageId = '', editUserMsg = null,
    planMode = false, resume = false, fromSteer = false, planBase = null] = rest
  const ctx = openTurn({ question, imgs, replaceMsg, isFirstMessage, autoRetry, deepThink,
    attachments, skills, mentions, prev, historyRefs, editMessageId, editUserMsg, planMode, resume,
    fromSteer, planBase })
  const handlers = buildHandlers(ctx)
  if (resume) resumeChat(ctx.sid, handlers)
  else sendQuestion(ctx.sid, question, imgs, handlers)
}

/** 开一轮：建气泡、登记通道、装配 deps 与两个回调工厂。返回本轮上下文。 */
/** 建（或复用被替换的）本轮 AI 气泡：接流时沿用原起表点，避免数字跳回 0。 */
function makeTurnMessage (replaceMsg, resume, agentId, turnAgentName) {
  const fresh = { role: 'ai', content: '', images: [], sources: [], related: [], degradations: [], warnMsg: '', loading: true, retrying: false, thinking: '', thinkLoading: false, time: Date.now(), turnClockFromServer: false, turnMs: 0, humanWaitMs: 0, artifacts: [], toolCalls: [], skillHits: [], plan: null, planCard: null, timeline: [], errorCard: null, model: '', goalWaiting: false, agentId: agentId || '', agentName: turnAgentName }
  const heldStartAt = resume && replaceMsg ? Number(replaceMsg.time) || 0 : 0
  const msg = replaceMsg ? Object.assign(replaceMsg, fresh, { messageId: null, fb: null }) : reactive(fresh)
  if (heldStartAt) msg.time = heldStartAt
  return msg
}

function openTurn (t) {
  const { question, imgs, replaceMsg, isFirstMessage, deepThink, attachments, skills, mentions,
    prev, historyRefs, editMessageId, editUserMsg, planMode, resume, fromSteer, planBase, autoRetry } = t
  // prev = 自动重试上下文 { sid, agentId, model }：沿用原会话与原选择，不读当前 UI 态
  const sid = prev ? prev.sid : currentSessionId.value
  const agentId = prev ? prev.agentId : (currentAgentId.value || '')
  const turnAgentName = agentId ? (agentList.value.find(a => a.id === agentId)?.name || '') : ''
  // 会话已有 ID 时不随发送带模型：会话里那份是权威（见原注释）
  const model = prev ? prev.model : (currentSessionId.value ? '' : (currentOverrideModel.value || ''))
  const replacedMessageId = replaceMsg && replaceMsg.messageId ? replaceMsg.messageId : ''
  const msg = makeTurnMessage(replaceMsg, resume, agentId, turnAgentName)
  if (!resume) dropGoalPlaceholder()
  if (!replaceMsg && currentSessionId.value === sid) messages.value.push(msg)
  const viewing = () => currentSessionId.value === sid
  let replayingSnapshot = false
  const liveScroll = () => { if (!replayingSnapshot && viewing()) hooks.scrollFollow?.() }
  const abort = new AbortController()
  const st = { msg, abort }
  chatStreams.set(sid, st)
  let admitted = false
  let transportError = ''
  const failNow = (error, kind) => failTurn(msg, {
    error, kind, flushNow: () => flushNow(), settlePreparingCards, settleAbortedPlan,
    dropStream: () => { if (chatStreams.get(sid) === st) chatStreams.delete(sid) },
    notify: m => message.error(m), viewing, scroll: () => liveScroll()
  })
  const beginReconnect = () => {
    if (chatStreams.get(sid) === st) chatStreams.delete(sid)
    turnReconnect.start(sid, msg, { admitted, resume, error: transportError, onFail: e => failNow(e, 'unreachable') })
  }
  ensureTick()
  if (isFirstMessage) markSessionActive(sid, question)
  if (viewing()) hooks.scrollForce?.()
  const { flushNow, resetFromContent, gotToken } = createStreamBuffer({
    msg, extendText: (m, from, to) => extendTimelineText(m, from, to),
    extendProcess: (m, from, to) => extendTimelineProcess(m, from, to), scroll: () => liveScroll()
  })
  const thought = createThoughtFrames({ msg, flushNow, scroll: () => liveScroll() })
  const deps = {
    stopPlaceholder: '（已停止本轮）', resetFromContent, hooks, stepBriefOf, pushTimelineTool, isDispatchTool,
    consultNameOf, appendToolOutput, applyAgentDelegated, applyApprovalCardFrame, applyGoalState, applyGoalTurn,
    applyImageFrame, applyPlanApproval, applyPlanCancelled, applyPlanDelta, applyPlanFrame, applyPlanSuperseded,
    applyRetrievedFrame, applyRunCodeProgress, applySkillHitFrame, applySteerFrame, applySubagentStep, applyTodoFrame,
    applyToolStatus, applyTurnStart, applyUsageFrame, attachAskCard, attachRunningTurn, currentSessionId,
    ensureTick, messages, reasoningLevelParam, retireAskCard, setGoal, setTodos,
    dispatchTeamEvent, buildTurnRequest, pushTimelineArtifact, linkPlanLineage, dropSteeredUserRows, replaySnapshotExtras,
    finishResumedShell, reduceTurnSnapshot, alignTick, planCardFromRecord, liveCompactedSid, autoCompactSid,
    msg, sid, st, thought, liveScroll, abort,
    agentId, attachments, deepThink, editMessageId, flushNow, fromSteer,
    historyRefs, mentions, model, planBase, planMode, replaceMsg,
    replacedMessageId, resume, skills, streamAnswer, imgs, question,
    autoRetry, beginReconnect, failNow, gotToken, prev, isFirstMessage,
    editUserMsg, modelMap, modelIssueMap, loadSessions, touchSessionRow, streamLifecycle,
    applyDonePayload, closeoutDoneTurn, applyCompactState,
  }
  const turnCtx = { handlers: null, get admitted () { return admitted }, set admitted (v) { admitted = v }, get transportError () { return transportError }, set transportError (v) { transportError = v }, get replayingSnapshot () { return replayingSnapshot }, set replayingSnapshot (v) { replayingSnapshot = v } }
  return { sid, imgs, deps, turn: turnCtx }
}

/** 装配两个回调工厂（帧回调在前、收尾在后，顺序与原对象字面量一致），并回填 handlers 供快照重放。 */
function buildHandlers (ctx) {
  const handlers = { ...createFrameHandlers(ctx.deps, ctx.turn), ...createClosingHandlers(ctx.deps, ctx.turn) }
  // onSnapshot 里 replaySnapshotExtras 要用同一份 handlers 重放工具输出/早产卡帧
  ctx.turn.handlers = handlers
  return handlers
}
const switchVersion = (mi, delta) => switchVersionImpl(mi, delta, { messages: () => messages.value })
const regenerate = mi => regenerateImpl(mi, {
  loading: () => loading.value,
  messages: () => messages.value,
  splitAtts,
  notifyWarning: msg => message.warning(msg),
  streamAnswer
})
// 分支切换 / 停止 / 插话队列装配已抽到 useSteerAndVersion.js。
const { variantSwitching, switchBranch, stop, steerQueue, steerResend, steer, stageSteer,
        sendSteer, editSteer, dropSteer, moveSteer, dropApplied,
        enqueuePlanAdjust } = useSteerAndVersion({
  messages, loading, currentSessionId, chatStreams, text, goalTerminal,
  switchVersion, switchSession, cancelReconnect, finishResumedShell,
  switchMessageVariant, stopChatTurn, steerChatTurn, enqueueSteer,
  steerQueueList, steerQueued, dropQueued, attachImage, attachUploaded, notify: message,
})

// ==================== 发送与流式回答（SSE） ====================
// 主流程在 ./sendFlow.js；这里只装配引擎侧的 refs 与动作。
const send = createSend({
  text, pendingImages, pendingFiles, pickedSkills, pendingMentions, pendingHistoryRefs,
  hasUploadingFile: () => hasUploadingFile(),
  loading, goalTurnStreaming, surrenderGoalStream: () => surrenderGoalStream(),
  stageSteer, sendSteer, setPlanMode, planModeOn, effectiveModel,
  // 这两格声明在本段之后：用存取器转发，工厂调用时不触发 TDZ
  compactNotice: { get value () { return compactNotice.value }, set value (v) { compactNotice.value = v } },
  liveCompactedSid: { get value () { return liveCompactedSid.value }, set value (v) { liveCompactedSid.value = v } },
  deepThinkOn,
  closePanels: () => hooks.closePanels?.(),
  messages, streamAnswer,
  notifyWarning: msg => message.warning(msg)
})

// 插话派发已抽到 useSteerDispatch.js。这里解构出同名函数，下面调用点不改。
const { applySteerFrame, settleSteerOnLeave } = useSteerDispatch({
  messages, currentSessionId, loading, text, steerQueue, steerResend, dropApplied, pushTimelineSteer,
  streamAnswer, notify: message,
})
// 手动压缩与摘要面板已抽到 useCompactSession.js。这里解构出同名符号，下面读写点不改。
const {
  manualCompacting, autoCompactSid, liveCompactedSid, compacting, compactNotice, compactContext,
  compactSummaryOpen, compactSummaryLoading, compactSummary, openCompactSummary, recompactWith,
} = useCompactSession({
  currentSessionId, loading, notify: message,
  compactApi: compactSessionApi, summaryApi: sessionHistorySummaryApi,
})

// 挂载初始化与产物签名续签已抽到 useEngineBootstrap.js。
const { ready } = useEngineBootstrap({
  loadSessions, loadAgents, loadSkills, route, switchSession, sessionStore,
  createNewSession, takeNewSessionAgent, autoPick, hooks,
  debugEntryVisible, debugDisplayVisible, showRelatedSuggestions,
  userDefaultModel, userDefaultModelIssue, modelIndex, refreshArtifactSigns,
})

// 本会话沙盒目录的状态与取数已抽到 useSandboxFiles.js（含三个重拉 watch）。
// 这里解构出同名变量，下面所有读写点一行不改。
const {
  sandboxFiles, sandboxErr, sandboxLocalOffline, sandboxLoading,
  sandboxTaken, sessionSandboxActive, loadSandboxFiles,
  sandboxDownload, sandboxTake, sandboxRows, sandboxCount, sandboxBytes, sandboxRest,
  sbSize, toggleSandboxDir,
} = useSandboxFiles({
  currentSessionId, loading, messages, notify: message,
  listApi: listSandboxFiles, downloadApi: downloadSandboxFile, takeApi: sandboxFileToArtifact,
})
// 会话后台进程的状态与取数已抽到 useSandboxProcs.js（重拉 watch 同在模块内）。
// onSandboxRan 与生命周期钩子留在本文件：onUnmounted 还管着恢复态轮询与重挂循环，不归进程块。
const {
  procs, procSides, procNotices, procErr, procLoading, procCardVisible, procScanActive,
  killingTag, killingAll, procAge, sandboxRanOnce,
  loadSandboxProcs, killProc, killAllProcs, stopProcTimer,
} = useSandboxProcs({
  currentSessionId, loading, messages, notify: message,
  listApi: listSessionProcs, killApi: killSessionProc, killAllApi: killAllSessionProcs,
  refreshBadges: refreshProcBadges,
})
const onSandboxRan = () => { sandboxRanOnce.value = true; loadSandboxProcs() }
onMounted(() => window.addEventListener('app:sandbox-ran', onSandboxRan))
onUnmounted(() => {
  window.removeEventListener('app:sandbox-ran', onSandboxRan)
  stopProcTimer()
  // 恢复态轮询的自停条件只看「会话没变」，组件卸载改变不了它——不摘就是离开聊天页后
  // 每 4s 一次 getHistory 最多再打 45 跳
  stopRestoredPoll()
  // 重挂循环同理：页面都卸载了，定时器与 online 监听不能留着
  turnReconnect.stopAll()
})

  return {
    // 输入与发送
    text, canSend, send, stop, steerQueue, sendSteer, editSteer, dropSteer, moveSteer,
    streamAnswer, resolveApproval, askSubmitAll, ignoreAsk,
    // 提问卡的选择状态口径收在引擎一处（单选/多选同一形状），两端面板只读这些、不各自数选中态
    pickAskOption, commitAskCustom, askOptionPicked, askCustomPicked, askQuestionAnswered,
    askAnsweredCount, askNextUnanswered, askAnswerText, askAutoSubmittable, askHintText,
    // 计划模式（人在回路）：开关（/plan 命令）与计划卡裁决动作；hydrate 供通知深链/切会话点名
    planModeOn, setPlanMode, submitPlanApproval, revisePlanInPlace, continuePlanConversation, hydratePendingPlans,
    // 批准后调整（A 插话 / B 停止并重新计划）与执行进度、调整记录：两端共用一份，不在页面里各算一遍
    adjustPlanAfterApproval, planExecProgressOf, planAdjustsOfMsg,
    // 待批准卡的派生态与倒计时（确认栏顶替打字框的判据，两端共用）
    pendingPlan, curPlanCard, planExpired, planCountdownText,
    // 工具审批卡倒计时：PC 直接调 approvalLeftSecOf(card)，移动壳把 approvalNow 传给消息行
    approvalLeftSecOf, approvalNow,
    // 目标模式：会话级目标态、条子口径与全部动作（改目标原文 / 停止 / 继续 / 删除）
    sessionGoal, goalModeOn, startGoal, goalAct, runGoalAction,
    // 目标条与详情面的状态口径（词汇、显隐、动作可用性）＋就地编辑态：两端共用一份，不在页面里各写一遍
    goalTerminal, goalBarVisible, goalPhrase, goalBarTitle, goalBarMeta, goalBarError, goalBlockReason,
    goalStateLabel, goalStateClass, goalRoundLine, goalCanPause, goalCanResume, goalCanEdit,
    goalCanCancel, goalCanClearRecord, goalPreempted, goalVersionLabel, goalProgressPercent,
    goalTerminalCard, goalTerminalTitle, goalTerminalNote, goalTurnStreaming, surrenderGoalStream,
    // 交付包：目标跨轮一个包、普通回答按轮一个包；卡片/抽屉/H5 汇总只认这一份口径
    goalDelivery, turnDeliveries, deliveryPackages, deliveryOfMessage, deliveryCardOfMessage,
    downloadDeliveryZip, artifactPendingOf, freshDeliveryOf,
    goalEditing, goalEdit, goalEditBusy, goalEditSaveable, startGoalInlineEdit, saveGoalEdit,
    // 思考能力 / 档位
    thinkCapsOf, reasoningLevelsOf, deepThinkMap, deepOnOf, deepThinkOn, levelOptionsOf, currentLevelOf,
    levelMap, setThinkLevel, currentThinkLevel, reasoningLevelParam,
    // 技能
    skillList, pickedSkills, loadSkills, toggleSkill, skillAvaStyle, skillDoc,
    // 智能体
    agentList, agentMap, defaultAgentId, hasDefaultAgent,
    currentAgentId, isAdmin, agentPickerOpen, currentAgentName, currentAgent, pickAgent,
    showAgentTag, modelLabelOf, modelSwitchInfo, compactDividerAt, liveCompactAt, loadAgents,
    // 会话与消息
    currentSessionId, loading, messages, currentSessionTitle,
    // 模型
    modelMap, currentOverrideModel, userDefaultModel, modelIndex, effectiveModel, effectiveModelLabel,
    effectiveModelIcon, effectiveModelProvider, modelSourceLabel, effectiveModelIssue,
    debugEntryVisible, debugDisplayVisible, showRelatedSuggestions,
    // 检索 / 用量 / 来源 / 上下文容量
    lastAi, lastSources, groupedSources, lastTokens, liveTokensPreview, ctxTokens,
    ctxCapData, ctxRingDash, ctxRingLevel, panelAi, sessionArtifacts,
    sessionTokens, sessionRetrieval, sessionTodos,
    // 任务清单的派生态只在这一处算（PC 与移动壳同吃这一份，页面只留折叠开关）
    todoSummary, todoAllDone, todoBarVisible,
    // 「这一会话的历史没取回来」与「取回来了、就是空的」是两个事实，分开暴露（见 historyError 注释）
    historyError, retryHistory,
    // 沙盒目录：取数、判据与折树一份两用（PC 右栏那张卡与移动壳「状态与来源」里那段同吃）
    sandboxFiles, sandboxRows, sandboxCount, sandboxBytes, sandboxRest,
    sandboxErr, sandboxLocalOffline, sandboxLoading, sandboxTaken, sessionSandboxActive,
    loadSandboxFiles, toggleSandboxDir, sandboxDownload, sandboxTake, sbSize,
    // 会话后台进程：本机执行器 + 沙盒两路合成，同一族两端口径（露脸判据、秒针、停止结果）只算一份
    procs, procSides, procNotices, procErr, procLoading, procCardVisible, procScanActive,
    killingTag, killingAll, loadSandboxProcs, procAge, killProc, killAllProcs,
    // 图片与附件
    pendingImages, removePendingImage, MAX_FILES, pendingFiles, hasUploadingFile,
    addFiles, removePendingFile, attachUploaded, attachImage,
    // 粘贴转附件（文件与图片 / 超长文本）
    takePastedFiles, takePastedText,
    // @ 引用（本轮显式指定知识库/文档/智能体）
    mentionOpen, mentionTab, mentionQuery, mentionLoading, mentionKbs, mentionDocs, pendingMentions,
    mentionHi, MAX_MENTIONS, isMentioned, toggleMention, removeMention, switchMentionTab,
    loadMentionCandidates, mentionDocStatus, mentionQueryNorm, mentionKbFiltered, mentionAgents,
    mentionAgentFiltered, mentionDocFiltered,
    // # 历史引用（本轮显式指定的会话历史问答）
    MAX_HISTORY_REFS, pendingHistoryRefs, histPool, isHistPicked, toggleHistoryRef, removeHistoryRef,
    // 会话生命周期
    switchSession, creatingSession, createNewSession, workspaceEpoch, autoPick, handleDeleteSession,
    // 提问卡恢复（切换会话自动跑；通知深链落到当前会话时页面可再点名一次）
    hydratePendingAsk,
    // 断线重连接流（切换会话自动跑；网络恢复或从后台回到前台时页面可再点名一次）
    attachRunningTurn,
    // 手动压缩上下文（/compact：PC 斜杠命令与移动端模型面板共用）
    // manualCompacting 只管手动那一路：轮内自动压缩的进度说在状态行上，不再占流尾那一行
    compacting, manualCompacting, compactNotice, compactContext,
    // 早期对话摘要面板（PC 弹窗与移动端 sheet 共用的数据源）
    compactSummaryOpen, compactSummary, compactSummaryLoading, openCompactSummary, recompactWith,
    // 重新生成 / 分支切换 / 挂载初始化
    switchVersion, regenerate, variantSwitching, switchBranch, ready
  }
}
