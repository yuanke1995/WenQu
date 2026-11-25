// ==================== 聊天引擎 composable ====================
// 承载聊天页的「引擎逻辑」：思考/模型/智能体状态、会话生命周期、附件与 @ 引用载荷、
// 发送与 SSE 流式回答、审批、重新生成与分支切换。与视图的接缝全部收在 hooks 里
// （滚动、收面板、聚焦），PC 端由 ChatPage 按原行为传参；移动壳可传自己的落点
// （如 '/m/chat'）与滚动实现复用同一套引擎。纯函数/常量见 ./projections.js。
import { ref, reactive, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isAdminSync, isLoggedIn } from '../utils/auth'
import { message } from 'ant-design-vue'
import { sendQuestion, resumeChat, getChatRun, newSession, getHistory, deleteSessionApi, switchMessageVariant, compactSessionApi, sessionHistorySummaryApi, getConfig, getRuntimeConfig, listAvailableAgents, listConsultableAgents,
         listAvailableSkills, getUserPreference, approveToolCall, getToolApproval, answerAgentAsk, ignoreAgentAsk, stopChatTurn, steerChatTurn,
         enqueueSteer, steerQueueList, steerQueued, dropQueued,
         listPendingAsks, listPendingApprovals, resolvePlanApproval, supersedePlanApproval, revisePlanApproval, listPendingPlans, updateSessionModelApi, getSessionModelApi,
         listKnowledgeBases, listDocuments, uploadChatAttachment, getSessionTodosApi,
         listSandboxFiles, downloadSandboxFile, sandboxFileToArtifact,
         listSessionProcs, killSessionProc, killAllSessionProcs,
         startGoalApi, goalActionApi, getPendingGoalApi, getSessionGoalApi,
         downloadArtifactsZip,
         refreshArtifactSignsApi } from './chatTransport.js'
import { sessionStore, loadSessions, chatStreams, todosMap, markSessionActive, touchSessionRow, refreshProcBadges } from '../views/store'
import { markLive, splitAtts } from './attachments'
// 取材判定层：图片压缩参数、附件白名单与各项限额都在这里（分享页共用同一份，见 ./intake.js）
import { MAX_IMAGES, MAX_FILES, classifyIntake, rejectText, REJECT, compressImage, pasteFilesOf,
         isLongPaste, pasteNameOf } from './intake'
import { loadModelIndex } from '../utils/modelRef'
import { approvalStatusText, approvalLeftSec } from './approval'
import {
         groupSources, ensureTick, alignTick, mergeDoneToolCalls, restoreTimeline, extendTimelineText, extendTimelineProcess,
         pushTimelineTool, pushTimelineArtifact, pushTimelineSteer, ensureThinkSegment,
         histItemDigest,





         artifactPendingOf,
         turnSteerRows,
         rememberAgents, isDispatchTool, consultNameOf, stepBriefOf, applyBlocks } from './projections'
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
import { switchVersion as switchVersionImpl, regenerate as regenerateImpl, createBranchSwitcher } from './versionActions.js'
import { STOP_PLACEHOLDER, markStoppedTurn } from './turnCloseout.js'
import { createSteerQueue } from './steerQueue.js'
import { createStopTurn } from './stopTurn.js'
import { buildHistoryList, dropSteeredUserRows } from './historyRows.js'
import { createSessionActions } from './sessionActions.js'
import { createSend } from './sendFlow.js'
import { settleAbortedPlan } from './planAbort.js'
import { failTurn } from './turnFail.js'
import { createSessionSwitch } from './sessionSwitch.js'
import { planCardFromRecord, linkPlanLineage } from './planCards.js'
import { applyPlanDelta, applyPlanApproval, applyPlanCancelled, applyPlanSuperseded } from './streamPlanFrames.js'
import { createStreamBuffer } from './streamBuffer.js'
import { applyCompactState, applyPlanFrame, applyGoalState, applyGoalTurn } from './streamStateFrames.js'
import { createThoughtFrames } from './streamThoughtFrames.js'
import { buildTurnRequest } from './turnRequest.js'
import { applySubagentStep } from './streamSubagentFrames.js'
import { closeoutDoneTurn } from './turnDoneCloseout.js'
import { applyDonePayload } from './applyDonePayload.js'
import { applyToolStatus, appendToolOutput } from './streamToolFrames.js'
import { applyTodoFrame, applyUsageFrame, applyRetrievedFrame, applySkillHitFrame, applyApprovalCardFrame, applyRunCodeProgress, applyAgentDelegated } from './streamInfoFrames.js'
import { createStreamLifecycle } from './streamLifecycle.js'
import { applyTurnSnapshot as reduceTurnSnapshot, settlePreparingCards, finishResumedShell as finishResumedShellImpl, replaySnapshotExtras } from './streamReducer.js'
import { attachRunningTurn as attachRunningTurnImpl } from './streamAttach.js'
import { createTurnReconnect } from './turnReconnect.js'
import { goalAct as goalActImpl } from './goalActions.js'
import { askAnswerText, askAutoSubmittable, askQuestionAnswered, askAnsweredCount, askNextUnanswered,
         pickAskOption, commitAskCustom, askOptionPicked, askCustomPicked, askHintText } from './askUser.js'
import { submitAsk as submitAskImpl, ignoreAsk as ignoreAskImpl } from './askActions.js'
import { attachAskCard, retireAskCard } from './askReducer.js'
import { resolveApproval as resolveApprovalImpl } from './approvalActions.js'
import { submitPlanApproval as submitPlanApprovalImpl, revisePlanInPlace as revisePlanInPlaceImpl,
         steerPlanAdjustment as steerPlanAdjustmentImpl, adjustPlanAfterApproval as adjustPlanAfterApprovalImpl,
         replanAfterApproval as replanAfterApprovalImpl } from './planActions.js'
import { planExecProgress, planTodosOfTurn, planAdjustsOf, planVersionOf, planStepCount } from './plan'
// 起表/配图/done 公共合并语义单源（与分享页共用，分叉史见 streamMerge 头注释）
import { applyTurnStart, applyImageFrame, applyDoneCore } from './streamMerge'
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
const MAX_HISTORY_REFS = 10
const pendingHistoryRefs = ref([])
// 候选池：当前会话已落库的消息（有 messageId=服务端已确认、非流式中、正文非空）。
// 历史回放的消息自带 messageId（getHistory 下发），当场发的消息在 done 事件回填后也可引用
const histPool = computed(() => messages.value.filter(m =>
  m.messageId && !m.loading && m.content && String(m.content).trim()
    && (m.role === 'user' || m.role === 'ai' || m.role === 'assistant')))
const isHistPicked = id => pendingHistoryRefs.value.some(h => h.messageId === id)
const toggleHistoryRef = m => {
  if (isHistPicked(m.messageId)) {
    pendingHistoryRefs.value = pendingHistoryRefs.value.filter(h => h.messageId !== m.messageId)
    return
  }
  if (pendingHistoryRefs.value.length >= MAX_HISTORY_REFS) {
    message.warning(`一次最多引用 ${MAX_HISTORY_REFS} 条历史消息`)
    return
  }
  pendingHistoryRefs.value.push({ messageId: m.messageId, role: m.role, digest: histItemDigest(m) })
}
const removeHistoryRef = i => pendingHistoryRefs.value.splice(i, 1)
// ==================== 会话 ====================
/**
 * 各会话当前任务清单：sid → {turnId, items:[{content,status}], updatedAt}。
 * 实时由 SSE 的 todo 事件写入，切会话/刷新按需回捞（后端整表覆盖，只存最新一份）。
 * 数据本身是模块单例（store.js）：轮内切走路由再回来会重建这个引擎，清单挂在实例上就会
 * 只剩切走那一刻的旧值、且再也不更新（见 store.js 里 todosMap 的注释）。
 */
const sessionTodos = computed(() => todosMap.value[currentSessionId.value] || null)
function setTodos (sid, doc) { todosMap.value = { ...todosMap.value, [sid]: doc } }

/**
 * 历史轮次的任务清单：会话表只留最新一份，旧已批准计划刷新后要靠账本读回它那一轮的 N/M。
 * 按 `${sid}|${turnId}` 缓存，切走再回来不重复请求；拉不到的轮次保持“没有清单”，不拿当前清单冒充。
 */
const turnTodos = ref({})
const turnTodoPending = new Set()
const turnTodoKey = (sid, turnId) => String(sid || '') + '|' + String(turnId || '')
const turnTodosOf = (sid, turnId) => {
  if (!sid || !turnId) return null
  return turnTodos.value[turnTodoKey(sid, turnId)] || null
}
/** 取一张已批准计划所属轮次的清单（无 turnId/不是已批准/已拉到/正在拉都不发请求） */
function ensureTurnTodos (sid, list) {
  if (!sid) return
  const ids = new Set()
  for (const m of (list || [])) {
    if (!m || !m.planCard || m.planCard.status !== 'approved') continue
    if (!m.turnId) continue
    if (sessionTodos.value && sessionTodos.value.turnId === m.turnId) continue
    const key = turnTodoKey(sid, m.turnId)
    if (turnTodos.value[key] || turnTodoPending.has(key)) continue
    ids.add(m.turnId)
  }
  for (const turnId of ids) {
    const key = turnTodoKey(sid, turnId)
    turnTodoPending.add(key)
    getSessionTodosApi(sid, turnId).then(r => {
      if (r && r.success && r.data && r.data.todos) {
        turnTodos.value = { ...turnTodos.value, [key]: r.data.todos }
      }
    }).catch(() => { /* 拉不到就保持无进度：不猜、不拿当前清单顶替 */ })
      .finally(() => turnTodoPending.delete(key))
  }
}

// 新一轮开始写清单后，旧已批准计划的分步进度会被最新一份顶掉：按轮把账本清单补回来
// （只在新一轮出现时触发，同一轮内的实时更新仍走 sessionTodos，不重复拉取）
watch(() => sessionTodos.value && sessionTodos.value.turnId, tid => {
  if (tid) ensureTurnTodos(currentSessionId.value, messages.value)
})

/** 计划执行进度：实时清单（属于这一轮）→ 该轮账本清单 → 收尾冻结快照，依次回退 */
const planExecProgressOf = m => {
  if (!m || !m.planCard) return null
  const sid = currentSessionId.value
  const turnId = m.turnId || ''
  const live = sessionTodos.value
  // 目标已暂停：这一轮即使还在收尾也不再算「正在跑」，卡上不能继续转圈说进行中
  const goalPaused = !!sessionGoal.value && sessionGoal.value.status === 'PAUSED'
  const running = loading.value && m === panelAi.value && !goalPaused
  if (live && planTodosOfTurn(live, m, messages.value)) {
    return planExecProgress(m.planCard, live, turnId, running, m.planCard.execSnapshot)
  }
  const hist = turnTodosOf(sid, turnId)
  if (hist) return planExecProgress(m.planCard, hist, turnId, false, m.planCard.execSnapshot)
  return planExecProgress(m.planCard, null, turnId, false, m.planCard.execSnapshot)
}
/** 计划卡上的「本轮调整」：仅已批准计划，取本轮时间线里的 steer 段 */
const planAdjustsOfMsg = m => planAdjustsOf(m && m.planCard, turnSteerRows(m))

/**
 * 最新一轮挂着计划卡（深度执行 / 执行计划）：这一轮的进度归卡，底部任务清单不再另占一栏。
 * 计划卡的步骤行读的就是同一份清单，两边都画＝同一份进度说两遍；计划跑完也不放开——
 * 卡收成一行后仍写着「7/7 已完成」，与底部清单完全重复。
 * 未批准的卡不算：那张卡只报「未批准」，步骤与进度都不画，清单是唯一还能看到执行项的地方。
 * 只认最新那条助手消息：旧计划收场、用户又开始普通提问时，新一轮没有计划卡，清单照常露。
 */
const planOwnsTodos = computed(() => {
  const list = messages.value
  let ai = null
  for (let i = list.length - 1; i >= 0; i--) {
    if (list[i] && list[i].role === 'ai') { ai = list[i]; break }
  }
  const c = ai && ai.planCard
  return !!(c && c.status !== 'rejected' && !c.replannedBy && !c.replannedAt)
})

/**
 * 清单计数行：只报非零的几档（「3 已完成 · 1 进行中 · 5 待处理」）。
 * 不报「x/y」那种分数：它看不出进行中那一项是谁，而长任务里用户问的正是「现在卡在哪」。
 */
const todoSummary = computed(() => {
  const items = (sessionTodos.value && sessionTodos.value.items) || []
  const n = s => items.filter(i => i.status === s).length
  const done = n('done')
  const doing = n('doing')
  const failed = n('failed')
  const parts = []
  if (done) parts.push(`${done} 已完成`)
  if (doing) parts.push(`${doing} 进行中`)
  if (failed) parts.push(`${failed} 失败`)
  if (items.length - done - doing - failed > 0) parts.push(`${items.length - done - doing - failed} 待处理`)
  return parts.length ? parts.join(' · ') : `${items.length} 项`
})

/** 清单是否全部收口：全 done 之后它就从「还剩几步」变成「这一轮做成了什么」，
 *  不再是进行中的进度板，去留按「还看不看得到最后一轮」来定（见 todoBarVisible）。 */
const todoAllDone = computed(() => {
  const items = (sessionTodos.value && sessionTodos.value.items) || []
  return items.length > 0 && items.every(i => i.status === 'done')
})

/**
 * 任务清单何时露：
 *  - 未完成：照常跨轮保留（目标模式/长任务靠它接着推，新一轮不自动清）；
 *  - 全 done：留在它自己那一轮里给人看最后一眼，**下一次用户发话（新一轮开始）收场**。
 * 判据是「清单所属轮之后是否已出现用户消息」：轮身份用清单的 turnId 去消息流里认——
 * 实时 done/接流快照与历史回放都会给用户行和回答行盖上同一个 turnId（与落库同源）。
 * 认不出来（存量清单没 turnId、或那一轮的消息已被过滤）按「还没开始新一轮」处理：
 * 宁可多留一条，也不能把正在推进的清单藏掉。
 * <p>两端同吃这一份派生态，页面只留折叠态这种纯 UI 开关。
 */
const todoBarVisible = computed(() => {
  // 这一轮的进度已经在计划卡上，清单不重复占一栏
  if (planOwnsTodos.value) return false
  const doc = sessionTodos.value
  const items = (doc && doc.items) || []
  if (!items.length) return false
  if (!todoAllDone.value) return true
  const tid = doc.turnId
  if (!tid) return true
  const list = messages.value
  let idx = -1
  for (let i = list.length - 1; i >= 0; i--) {
    if (list[i] && list[i].turnId === tid) { idx = i; break }
  }
  if (idx < 0) return true
  for (let i = idx + 1; i < list.length; i++) {
    if (list[i] && list[i].role === 'user') return false
  }
  return true
})

/**
 * 各会话当前目标（目标模式）：sid → 目标视图模型。
 * 实时由 SSE 的 goal_state 帧写入（阶段迁移/轮次推进/停因的唯一帧），
 * 切会话/刷新按需回捞（后端读 DB 不读框架：重启/换副本后面板照常长出来）。
 * 与 sessionTodos 同形：会话级一份、不挂气泡——目标横跨多轮，不是某一轮的东西。
 */
const goalsMap = ref({})
const sessionGoal = computed(() => goalsMap.value[currentSessionId.value] || null)
function setGoal (sid, doc) { goalsMap.value = { ...goalsMap.value, [sid]: doc } }

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
    attachRunningTurn(sid).catch(() => {})
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

// 会话切换编排（历史加载/在途复用/失败回退/接流与卡片恢复）在 ./sessionSwitch.js
const { switchSession, historyLoads } = createSessionSwitch({
  currentSid: () => currentSessionId.value,
  setCurrentSid: sid => { currentSessionId.value = sid },
  settleSteer: () => settleSteerOnLeave(),
  dropGoalPlaceholderIfOther: sid => { if (goalPlaceholderSid !== sid) { goalPlaceholder = null; goalPlaceholderSid = null } },
  goalPlaceholderFor: sid => (goalPlaceholder && goalPlaceholderSid === sid && goalRunning.value ? goalPlaceholder : null),
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

/**
 * 重试取当前会话的历史（两端错误卡上那颗钮用）。
 * 直接走 switchSession：同 sid 有在途加载时它会复用结果，不会并发发两份。
 */
const retryHistory = async () => {
  const sid = currentSessionId.value
  if (!sid) return
  historyError.value = ''
  await switchSession(sid)
}

// 会话生命周期（新建/删除/自动落点）在 ./sessionActions.js
const creatingSession = ref(false)
/** 归属可能在会话没换 id 的情况下改变（服务端复用空会话时会改挂），页面上凡是按工作区判的东西
    （权限档位药丸、目录提示、头部标识）都要靠这个重新取一次工作区摘要 */
const workspaceEpoch = ref(0)
const { createNewSession, deleteSession: handleDeleteSession, autoPick } = createSessionActions({
  creating: creatingSession,
  workspaceEpoch,
  currentSid: () => currentSessionId.value,
  setCurrentSid: sid => { currentSessionId.value = sid },
  list: () => sessionStore.list,
  streams: chatStreams,
  settleSteer: () => settleSteerOnLeave(),
  switchSession: sid => switchSession(sid),
  clearView: () => { messages.value = []; historyError.value = '' },
  setAgentMemory: (sid, agentId) => { agentMap.value = { ...agentMap.value, [sid]: agentId || '' } },
  replaceRoute: sid => router.replace({ path: hooks.chatPath, query: { sid } }),
  replaceHome: () => router.replace(hooks.chatPath),
  focusInput: () => hooks.focusInput?.(),
  createApi: newSession,
  deleteApi: deleteSessionApi,
  reloadSessions: loadSessions,
  isLoggedIn,
  notifyError: msg => message.error(msg),
  notifySuccess: msg => message.success(msg)
})

/**
 * 跨页「新建对话」带的预选智能体（智能体页「试一试」）：消费一次即清。
 * 不能留在 query 里等对话页再读——createNewSession 会用 query:{sid} 写地址栏，兄弟参数会被抹掉；
 * 直接把 id 交给 createNewSession 的 presetAgentId，落进新会话的待选值最稳。
 */
const takeNewSessionAgent = () => {
  const agent = sessionStore.newChatAgent || undefined
  sessionStore.newChatAgent = ''
  return agent
}

// 路由 query.sid 驱动：只处理"切到某个会话"；sid 清空（新建/删除当前会话）由 tick 信号接管，
// 避免两条链路同时触发 autoPick / createNew 的竞态
watch(() => route.query.sid, sid => {
  if (route.path !== hooks.chatPath || !sid) return
  if (sid !== currentSessionId.value) switchSession(sid)
})
// 通知深链的 approval：会话已经落在当前页时（点通知时页面没跳走）也要能定位。
// 切会话那一次由 switchSession 末尾统一处理；这里只管「当前会话内的 query 变化」，
// 并且避开正在加载的窗口——否则同一张卡会被两条链路各挂一遍。
watch(() => route.query.approval, id => {
  if (!id) return
  const sid = String(route.query.sid || '')
  if (!sid || sid !== currentSessionId.value || historyLoads.has(sid)) return
  void handleApprovalDeepLink(sid, String(id))
})
// 侧边栏「新建对话」信号（消费后回写 seen，跨页积累的 tick 只消费一次）
// 注意不依赖 route.query 状态：tick 触发时路由 push 可能尚未完成，条件里查 sid 会偶发落空
// （不再被"正在回答"拦截：当前会话的流转入后台继续跑）
watch(() => sessionStore.newChatTick, async tick => {
  sessionStore.newChatSeen = tick
  if (route.path === hooks.chatPath) await createNewSession(takeNewSessionAgent())
})
// 当前会话被删除 → 自动落到最近会话或新建
watch(() => sessionStore.autoPickTick, async () => {
  if (route.path === hooks.chatPath) await autoPick()
})
// 工具审批动作在 ./approvalActions.js（actions 层）。
const resolveApproval = (m, card, approved, scope) => resolveApprovalImpl(m, card, approved, {
  chatStreams, currentSid: () => currentSessionId.value, notify: message, emitNotifChanged,
  pollRestoredAnswer: sid => pollRestoredAnswer(sid), scrollSoft: () => hooks.scrollSoft?.(),
  approveApi: approveToolCall, approvalLeftSec: approvalLeftSecOf,
  // 「本会话都允许」时后端会一并批准同工具的其它挂起卡；把它们的卡从所有气泡里摘掉
  dropApprovals: ids => {
    const set = new Set((ids || []).map(String))
    for (const msg of messages.value) {
      if (Array.isArray(msg.approvals)) {
        const left = msg.approvals.filter(x => !set.has(String(x.id)))
        if (left.length !== msg.approvals.length) msg.approvals = left
      }
    }
  }
}, scope)

/**
 * 待批准的计划卡（两端共用，与提问面板同判据）：倒着找最后一条还挂着 pending 计划的助手消息。
 * <p>计划数据两端都拿得到（hydratePendingPlans 在引擎内），派生放在这里两端共用同一份，
 * 不会出现「卡片在 messages 里、界面上一个字都没有」。
 */
const pendingPlan = computed(() => {
  const list = messages.value
  for (let i = list.length - 1; i >= 0; i--) {
    const m = list[i]
    // 退回重出期间（revising）也必须继续算「待确认」：否则底部确认栏会在升版途中消失，
    // 打字框冒出来，用户可能在模型重做计划的同一轮里又发一条新消息
    if (m && m.role === 'ai' && m.planCard && (m.planCard.status === 'pending' || m.planCard.revising)) return m
  }
  return null
})
const curPlanCard = computed(() => pendingPlan.value?.planCard || null)
// 倒计时心跳（仅待确认卡挂 deadline 时走表，与提问卡的 askNow 同一套节奏）
const planNow = ref(Date.now())
let planTimer = null
watch(() => messages.value.some(m => m.planCard && m.planCard.status === 'pending' && m.planCard.deadline), v => {
  if (v && !planTimer) planTimer = setInterval(() => { planNow.value = Date.now() }, 1000)
  if (!v && planTimer) { clearInterval(planTimer); planTimer = null }
})
onUnmounted(() => { if (planTimer) clearInterval(planTimer) })
// 已过 deadline（后端超时收尾的 plan_cancelled 马上到）：操作锁住，避免批准必然失败的那一下
const planExpired = computed(() => {
  const c = curPlanCard.value
  return !!(c && c.deadline && planNow.value >= c.deadline)
})
const planCountdownText = computed(() => {
  const c = curPlanCard.value
  if (!c || !c.deadline) return ''
  const s = Math.max(0, Math.ceil((c.deadline - planNow.value) / 1000))
  return String(Math.floor(s / 60)).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0')
})
// 计划卡中断收尾在 ./planAbort.js（纯模块）。

// ==================== 目标模式（Goal Mode） ====================
// 对齐 DSH：用户写一句目标，系统自己跨轮往下推进，做完了就停——没有验收标准、没有人工确认、
// 没有逐条核验与倒计时。用户在目标条上只能做三件事：改目标原文、停止/继续、删除。
// 目标是会话级的一份（横跨多轮），不是某一轮的东西；终态记录进右栏面板，条子只在
// RUNNING/PAUSED 两档露出（做完了/删掉了都不是「还等你处理」）。

/**
 * 目标入口（工具条按钮 /goal 命令共用这一出口）：建行即开跑。
 * 目标原文就是模型这一侧唯一要看到的东西——不传验收标准、不传启动策略。
 */
async function startGoal (objective) {
  const sid = currentSessionId.value
  if (!sid) return null
  const obj = String(objective || '').trim()
  if (!obj) { message.warning('先写一句这件事要做到什么'); return null }
  // 用户这句话先落地再发包：建目标是一次同步重活（建行 + 起首轮，冷启动要几秒），
  // 等 POST 回来再 push 的话，输入框已被 startGoalNow 清空、聊天区空空如也，用户以为这句话
  // 根本没发出去。与普通发送同口径（send() 也是先 push 气泡再开流）：goalCommand 等 goalId
  // 回来补上，失败再把这条 optimistic 气泡撤掉（startGoalNow 会把原文还回输入框）。
  // goalPending 补的是那几秒的空窗：两壳的身份胶囊与「撤动作键」都按 goalCommand 判，
  // 只等 goalId 的话这条回声在响应回来前长得跟普通提问一模一样，编辑重发能把目标指令重跑一轮。
  const echo = reactive({ role: 'user', content: obj, goalCommand: '', goalPending: true,
                          images: [], attachments: [], attachData: [],
                          skills: [], mentions: [], historyRefs: [], deepThink: false,
                          time: Date.now(), messageId: null })
  messages.value.push(echo)
  // 与普通提问同一落点：补尾随留白再贴底（=本轮问题置顶）。这里不能用 scrollSoft——它只跟着
  // 「还在贴底」走，用户上翻过就什么都不做，目标这句话既不留白也不归位，要等助手气泡接上才跳
  hooks.scrollForce?.()
  // 失败摘除：只发生在「服务端没写库」与「写库了但响应丢了」两种情形，后者由 catch 里的
  // 回读兜底先判掉，剩下的确实是没建成——气泡撤掉后界面回到发送前的样子
  const dropEcho = () => {
    const i = messages.value.indexOf(echo)
    if (i >= 0) messages.value.splice(i, 1)
  }
  try {
    const r = await startGoalApi(sid, obj, currentAgentId.value || '')
    if (r && r.success === false) { dropEcho(); message.warning(r.msg || '目标创建失败'); return null }
    if (r && r.data) {
      setGoal(sid, { ...r.data })
      // 建目标即开跑：首轮由调度器起（一拍 2 秒）+ 实例冷启动装配，POST 回来的这一刻总线上
      // 通常还探不到。走 watchGoalTurn 而不是直接 attachRunningTurn：先挂占位气泡（「正在溯源」
      // 当场出现），探测到那一轮登记上总线再接流；接不上由 2.5s 轮询继续探（同 attachRunningTurn
      // 那条路径），不许让聊天区在那几秒里空着。
      if (goalRunning.value) watchGoalTurn(sid)
      // 对话里那一行回声服务端同一条已落库（echoGoalCommand），刷新/切会话读的是库里那份；
      // 这里只补命令标注。目标横跨多轮，状态在摘要条与详情面，但「什么时候定了什么目标」
      // 得能在对话里读出来
      echo.goalCommand = r.data.goalId || ''
      echo.goalPending = !echo.goalCommand
      // 侧栏要立刻看得见这条会话：目标会话在第一条回答落地之前 message_count 一直是 0，而会话列表
      // 隐藏空会话——用户在工作区里定了目标，回头看那个工作区读到的是「暂无会话」。
      // 标题用目标原文：这条会话讲的就是这件事，比「新对话」认得出来。
      markSessionActive(sid, obj)
      return r.data
    }
    dropEcho()
    return null
  } catch (e) {
    // 响应丢了不等于没建成：超时/5xx 时服务端可能已经写库并起了首轮（kickContinuation 抛在
    // 提交之后就是这种）。这时候撤气泡 + 还回原文，用户会重发一遍，把刚建好的目标重开一遍。
    // 回读一次会话目标（按 objective + 创建时间认领），确认建成了就按成功结算。
    const g = await getSessionGoalApi(sid).catch(() => null)
    const created = g && g.success && g.data && g.data.goalId
      && String(g.data.objective || '').trim() === obj
      && Date.now() - new Date(g.data.createdAt).getTime() < 120000
    if (created) {
      setGoal(sid, { ...g.data })
      echo.goalCommand = g.data.goalId || ''
      echo.goalPending = !echo.goalCommand
      markSessionActive(sid, obj)
      message.warning('响应中断，但已从服务端确认目标创建成功')
      return g.data
    }
    dropEcho()
    message.error(e.message || '目标创建失败')
    return null
  }
}

// 目标动作在 ./goalActions.js（actions 层），依赖经 ctx 注入。
const goalAct = (action, extra) => goalActImpl(action, extra, {
  goal: () => sessionGoal.value, goalOf: sid => goalsMap.value[sid], setGoal,
  currentSid: () => currentSessionId.value, action: goalActionApi,
  watchGoalTurn, barVisible: () => goalBarVisible.value, notify: message
})

// 目标条状态投影与就地编辑已抽到 useGoalProjection.js（投影函数从 projections.js 直接导入）。
// 这里解构出同名符号，下面所有读写点一行不改。
const {
  goalTerminal,
  goalBarVisible,
  goalPhrase,
  goalStateLabel,
  goalStateClass,
  goalRoundLine,
  goalBlockReason,
  goalBarTitle,
  goalBarMeta,
  goalCanPause,
  goalCanResume,
  goalCanEdit,
  goalCanCancel,
  goalCanClearRecord,
  goalPreempted,
  goalVersionLabel,
  goalProgressPercent,
  goalTerminalCard,
  goalTerminalTitle,
  goalTerminalNote,
  goalTurnStreaming,
  surrenderGoalStream,
  goalBarError,
  runGoalAction,
  goalEditing,
  goalEditBusy,
  goalEdit,
  goalEditSaveable,
  startGoalInlineEdit,
  saveGoalEdit,
} = useGoalProjection({ sessionGoal, currentSessionId, chatStreams, messages, goalAct })

/**
 * 计划模式（人在回路）：裁决当前气泡挂起的执行计划——批准 / 取消本轮。
 *  action='approve' 批准执行（后端仍兼容带改稿批准，前端不再提供该入口）；
 *  action='cancel' 取消本轮，卡片定格「未批准」。
 *  对计划不满意走 {@link continuePlanConversation}（带着意见继续对话，本版作废）。
 *  恢复态卡（restored）：本设备没有这一轮的流，批准在后台进行，靠轮询取回结果。 */
// 计划裁决动作在 ./planActions.js（actions 层）。
const planCtx = () => ({
  resolveApi: resolvePlanApproval, reviseApi: revisePlanApproval, notify: message, emitNotifChanged,
  stopRestoredPoll: () => stopRestoredPoll(), pollRestoredAnswer: sid => pollRestoredAnswer(sid),
  switchSession: sid => switchSession(sid), currentSid: () => currentSessionId.value,
  scrollSoft: () => hooks.scrollSoft?.()
})
const submitPlanApproval = (m, action) => submitPlanApprovalImpl(m, action, planCtx())
const revisePlanInPlace = (m, text) => revisePlanInPlaceImpl(m, text, planCtx())

/** 旧轮收尾等待：后端先落「取代」收尾消息、前端再把意见作为新消息发出（落库顺序不乱）。
 *  恢复态卡没有本轮的流可等，只给后端落库留一小段时间 */
const planRoundSettled = m => new Promise(resolve => {
  if (!m || m.restored) { setTimeout(resolve, 800); return }
  if (!m.loading) { resolve(); return }
  const t0 = Date.now()
  const tick = () => { if (!m.loading || Date.now() - t0 > 5000) resolve(); else setTimeout(tick, 150) }
  tick()
})

/**
 * 计划「继续对话」（计划模式）：用户对当前计划不满意，把输入框里的意见作为一条正式用户消息发出——
 * 本版计划先按「已被取代」作废（supersede），随后这条消息在**同一会话里继续对话**：完整新一轮
 * （重新检索），模型带着上一版计划按意见产出修改后的计划卡。可反复，直到点「确认」才执行。
 */
async function continuePlanConversation (m, text) {
  const c = m && m.planCard
  if (!c || c.busy || c.status !== 'pending') return
  const q = String(text || '').trim()
  if (!q) { message.warning('先写下要改什么，再点「继续对话」'); return }
  c.busy = true
  try {
    const r = await supersedePlanApproval(c.id, q)
    if (r && r.success === false) { message.warning(r.msg || '提交失败'); c.busy = false; return }
    emitNotifChanged('chat')
  } catch (e) {
    message.error(e.message || '提交失败')
    c.busy = false
    return
  }
  // 本地立刻定格「已被取代」，不等 plan_superseded 事件：这一版作废已是既成事实，而等事件的这段
  // 里卡片停在 busy=true——确认/取消/输入框整片灰、再点「继续对话」被开头那道判据静默 return，
  // 恢复态卡片更是没有本轮的流可等（第二次继续对话整个确认栏会冻死）。
  c.status = 'superseded'
  c.busy = false
  c.editText = ''
  m.planSuperseded = true
  await planRoundSettled(m)
  // 旧轮已收尾（或恢复态给了落库时间）：把意见作为用户消息发出，继续同一会话的对话
  const userMsg = reactive({ role: 'user', content: q, images: [], attachments: [], attachData: [],
                            skills: [], mentions: [], historyRefs: [], deepThink: deepThinkOn.value,
                            planMode: true, time: Date.now(), messageId: null })
  messages.value.push(userMsg)
  // 强制计划模式：本轮的目的就是按意见重做计划（后端据此再走计划闸门）
  streamAnswer(q, [], null, messages.value.length === 1, 1, deepThinkOn.value, [], [], [], null, [], '', userMsg, true)
}
// 插话调整在 ./planActions.js（actions 层）；重新计划仍在本文件（依赖停止/收尾/开新轮）。
// eslint-disable-next-line no-unused-vars -- 保留供后续计划调整入口接线
const steerPlanAdjustment = (m, raw) => steerPlanAdjustmentImpl(m, raw,
  { messages: () => messages.value, loading: () => loading.value, steer: q => steer(q) })

/**
 * 批准后调整：停止并重新计划。控制流在 ./planActions.js（actions 层），这里只提供引擎侧依赖。
 */
const replanAfterApproval = (m, raw) => replanAfterApprovalImpl(m, raw, {
  messages: () => messages.value,
  sid: () => currentSessionId.value,
  model: () => effectiveModel.value,
  loading: () => loading.value,
  progressOf: planExecProgressOf,
  hasLocalStream: sid => chatStreams.has(sid),
  stop: () => stop(),
  settle: mm => planRoundSettled(mm),
  markReplanned: setPlanReplanned,
  versionOf: planVersionOf,
  stepCount: planStepCount,
  launch: (q, from) => {
    const userMsg = reactive({
      role: 'user', content: q, images: [], attachments: [], attachData: [],
      skills: [], mentions: [], historyRefs: [], deepThink: deepThinkOn.value,
      planMode: true, planAdjustFrom: from, time: Date.now(), messageId: null
    })
    messages.value.push(userMsg)
    // 强制计划模式：本轮的目就是按意见重做计划；planBaseId 交给服务端拼血缘与上一版上下文
    streamAnswer(q, [], null, messages.value.length === 1, 1, deepThinkOn.value, [], [], [], null, [], '',
                 userMsg, true, false, false, { planBaseId: from.cardId })
  }
})

const adjustPlanAfterApproval = (m, text, mode) => adjustPlanAfterApprovalImpl(m, text, mode,
  { replan: (mm, t) => replanAfterApproval(mm, t), messages: () => messages.value, loading: () => loading.value, steer: q => steer(q),
    // 送不进去时的落点：只有目标还在跑（深度执行会自己起下一轮）才值得排队；否则把原因交回面板
    queuePlanAdjust: t => goalRunning.value
      ? enqueuePlanAdjust(t, currentSessionId.value) : null })

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
const hydratePendingAsk = async sid => {
  if (!sid) return
  const st = chatStreams.get(sid)
  let items
  try {
    const r = await listPendingAsks(sid)
    items = (r && r.data && Array.isArray(r.data.items)) ? r.data.items : []
  } catch (e) {
    // 静默失败会被读成「我那条没生效」：恢复入口拉不动要出声（不打断正常问答，只提示）
    if (currentSessionId.value === sid) message.warning('待答提问恢复失败：' + (e?.message || '网络异常'))
    return
  }
  if (currentSessionId.value !== sid) return   // 快速切会话：晚到响应不覆盖当前视图
  const alive = items.filter(x => x && !x.expired && x.live)
  const dead = items.filter(x => x && !x.expired && !x.live)
  if (!alive.length) {
    // 卡片还在但唤醒句柄已不在（进程重启过）：这一轮没人接，说清楚而不是留个点了没反应的输入框
    if (dead.length) message.warning('这一轮的提问已失效（服务已重启），回答没有继续生成')
    return
  }
  let mounted = 0
  // 每一张还挂着的卡都要建得出来：并行工具调用能在同一轮挂出两三项，只建末条的话
  // 其余那几题在屏幕上不存在，答不了就只能等它自己超时
  for (const item of alive) {
    // 挂回它自己那一轮：本轮气泡已经在界面上就挂它，更早的一轮就去找那一轮的气泡
    const target = cardTargetFor(st, item.turnId)
    if (target) {
      if (attachAskCard(target, { ...item, restored: true }, item.remainingMs)) mounted++
      continue
    }
    // 这一轮的助手消息要到回答完成才落库，此刻视图里没有可挂的气泡：补一个「等你作答」的占位泡，
    // 答题面板照旧挂底部；答完轮询到新回答后整个列表按历史重载，占位泡随之消失
    const anchor = (item.turnId && msgByTurn(item.turnId)) || null
    if (anchor) {
      if (attachAskCard(anchor, { ...item, restored: true }, item.remainingMs)) mounted++
      continue
    }
    const bubble = reactive({ role: 'ai', content: '', images: [], sources: [], related: [],
      degradations: [], warnMsg: '', loading: true, retrying: false, thinking: '',
      thinkLoading: false, time: Date.now(), artifacts: [],
      toolCalls: [], plan: null, timeline: [], errorCard: null, model: '',
      turnId: item.turnId || '' })
    attachAskCard(bubble, { ...item, restored: true }, item.remainingMs)
    messages.value = [...messages.value, bubble]
    mounted++
  }
  if (mounted) hooks.scrollForce?.()
}

/** 恢复态轮询句柄（同一时刻只留一份：切会话/答完即停） */
let restoredPoll = null
const stopRestoredPoll = () => { if (restoredPoll) { clearInterval(restoredPoll); restoredPoll = null } }




/**
 * 会话加载/切换后恢复待批准计划卡（计划模式，与提问卡恢复同一套语义）：计划已落库、等待批准期间
 * 断线不中止本轮，刷新/换设备后那根流水线线程还阻塞着，批准照样送得回去，回答转后台生成落库。
 * 只重建「未过期且唤醒句柄还在」的最新一张。
 */
const hydratePendingPlans = async sid => {
  if (!sid) return
  const st = chatStreams.get(sid)
  if (st && st.msg && st.msg.planCard) return   // 实时卡还挂在这一轮上，不重复挂
  let items
  try {
    const r = await listPendingPlans(sid)
    items = (r && r.data && Array.isArray(r.data.items)) ? r.data.items : []
  } catch (e) { return }   // 恢复拉取失败不提示：错过仍有超时兜底，不该打断正常浏览
  if (currentSessionId.value !== sid) return   // 快速切会话：晚到响应不覆盖当前视图
  const alive = items.filter(x => x && !x.expired && x.live)
  const dead = items.filter(x => x && !x.expired && !x.live)
  if (!alive.length) {
    if (dead.length) message.warning('这一轮的执行计划已失效（服务已重启），回答没有继续生成')
    return
  }
  const item = alive[alive.length - 1]
  const card = {
    ...planCardFromRecord(item, 'pending'),
    id: item.planApprovalId,
    restored: true,
    timeoutMs: Number(item.timeoutMs) || 0,
    deadline: item.remainingMs > 0 ? Date.now() + item.remainingMs : 0
  }
  // 挂回它自己那一轮（与提问卡同一判据）
  const target = cardTargetFor(st, item.turnId)
  if (target && target.planCard) return   // 这张卡已经挂着了
  if (target) {
    target.planCard = card
    linkPlanLineage(messages.value)   // 恢复出来的新计划也要把旧卡的「查看新一轮」补上
    hooks.scrollForce?.()
    return
  }
  const list = messages.value
  // 这一轮的助手消息要到回答完成才落库，此刻视图里没有可挂的气泡：补一个「等你批准」的占位泡，
  // 批准卡挂上；批准后轮询到新回答，整个列表按历史重载，占位泡随之消失
  const anchor = reactive({ role: 'ai', content: '', images: [], sources: [], related: [],
    degradations: [], warnMsg: '', loading: true, retrying: false, thinking: '',
    thinkLoading: false, time: Date.now(), artifacts: [],
    toolCalls: [], plan: null, planCard: null, timeline: [], errorCard: null, model: '',
    turnId: item.turnId || '' })
  anchor.planCard = card
  messages.value = [...list, anchor]
  linkPlanLineage(messages.value)
  hooks.scrollForce?.()
}

/**
 * 会话加载/接流后恢复「等你批准执行」的审批卡（与提问卡、计划卡同一套人在回路恢复口径）。
 * 审批只有 120 秒时限，卡片不落库时刷新或换设备接回这一轮就看不见那张卡，
 * 而本轮又豁免断线宽限、会一直等——用户读到的就是「智能体莫名其妙不动了」。
 * 只重建**未过期且唤醒句柄还在**（live）的审批卡；{@code onlyId} 是通知深链点名的
 * 那一张（同一轮挂着多张时也只挂它），并按记录带的 turnId 挂回它自己那一轮的气泡：
 * 那一轮在这台设备的视图里还没有气泡时补占位泡（live 就代表它真的卡在审批上，不挂等于看不见）。
 *
 * @returns {Promise<'mounted'|'exists'|'dead'|'gone'|'none'>}
 *   mounted=这次挂上了；exists=屏上已有同一张；dead=还在 120 秒窗口里但唤醒句柄已不在；
 *   gone=窗口已过/已不在待批列表；none=与当前会话无关
 */
const hydratePendingApprovals = async (sid, onlyId = '') => {
  if (!sid) return 'none'
  const st = chatStreams.get(sid)
  // 实时卡已挂着：深链点名的那张已在屏上时不重复挂
  const liveCards = (st && st.msg && Array.isArray(st.msg.approvals)) ? st.msg.approvals : []
  if (liveCards.length && (!onlyId || liveCards.some(x => String(x.id) === String(onlyId)))) return 'exists'
  let items
  try {
    const r = await listPendingApprovals(sid)
    items = (r && r.data && Array.isArray(r.data.items)) ? r.data.items : []
  } catch (e) { return 'error' }   // 拉不动不提示：审批自己有超时兜底，不该打断正常浏览
  if (currentSessionId.value !== sid) return 'none'   // 快速切会话：晚到响应不覆盖当前视图
  const matched = items.filter(x => x && x.approvalId && (!onlyId || x.approvalId === onlyId))
  const alive = matched.filter(x => x.live)
  const dead = matched.filter(x => !x.live)
  if (!alive.length) {
    if (!onlyId) return 'none'
    return dead.length ? 'dead' : 'gone'
  }
  // 多卡：alive 全部挂上（按 id 去重）；同一轮没有气泡的合并成一个占位泡
  const orphanByTurn = new Map()
  let mounted = 0
  for (const item of alive) {
    const remainMs = Number(item.remainingMs) || 0
    // timeoutMs 传「还剩多少」而不是整窗：卡片倒计时直接读 deadline，
    // 恢复出来的卡若给整窗，用户会以为还有两分钟，实际下一秒就超时按拒绝了
    const card = {
      id: item.approvalId, tool: item.tool, label: item.label, hint: item.hint, args: item.args,
      reason: item.reason || '',
      subagent: item.subagent || (item.parentSessionId ? { kind: 'subagent', agent: '子助手' } : null),
      timeoutMs: remainMs, deadline: remainMs > 0 ? Date.now() + remainMs : 0, busy: false, restored: true
    }
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
  // 这一轮在这台设备的视图里没有气泡（在别的设备上跑、或本轮的助手消息还没落库）：
  // 补一个「等你批准执行」的占位泡。那一轮是真的卡在审批上（live=唤醒句柄还在），
  // 什么都不挂就是用户读到的「智能体莫名其妙不动了」
  for (const g of orphanByTurn.values()) {
    const anchor = reactive({ role: 'ai', content: '', images: [], sources: [], related: [],
      degradations: [], warnMsg: '', loading: true, retrying: false, thinking: '',
      thinkLoading: false, time: Date.now(), artifacts: [],
      toolCalls: [], plan: null, planCard: null, timeline: [], errorCard: null,
      model: '', approvals: g.cards, turnId: g.turnId })
    messages.value = [...messages.value, anchor]
    mounted += g.cards.length
  }
  if (!onlyId && mounted) hooks.scrollForce?.()
  if (onlyId) return mounted ? 'mounted' : 'exists'
  return mounted ? 'mounted' : 'none'
}

// 审批倒计时：只在屏上还有带 deadline 的审批卡时走表（停/批准后自动停），
// 与计划卡的 planNow 同一套节奏。PC 与移动壳都读这一份。
const approvalNow = ref(Date.now())
let approvalTimer = null
watch(() => messages.value.some(m => m && Array.isArray(m.approvals)
  && m.approvals.some(a => a && a.deadline)), v => {
  if (v && !approvalTimer) approvalTimer = setInterval(() => { approvalNow.value = Date.now() }, 1000)
  if (!v && approvalTimer) { clearInterval(approvalTimer); approvalTimer = null }
})
onUnmounted(() => { if (approvalTimer) { clearInterval(approvalTimer); approvalTimer = null } })
const approvalLeftSecOf = card => approvalLeftSec(card, approvalNow.value)

/** 清掉地址栏的 approval 参数（刷新不再把已经处理过/失效的卡再恢复一遍） */
const clearApprovalQuery = approvalId => {
  if (String(route.query.approval || '') !== String(approvalId)) return
  const q = { ...route.query }
  delete q.approval
  router.replace({ path: hooks.chatPath, query: q }).catch(() => {})
}

/** 撤掉屏上同一张（另一页签已裁决/已超时）：别留两颗点了只会报错的按钮 */
const clearApprovalCard = approvalId => {
  let changed = false
  for (const m of messages.value) {
    if (!m || !Array.isArray(m.approvals)) continue
    const next = m.approvals.filter(a => !a || String(a.id) !== String(approvalId))
    if (next.length !== m.approvals.length) {
      m.approvals = next
      changed = true
    }
  }
  if (changed) hooks.scrollSoft?.()
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
    message.error('恢复审批失败：' + (e.message || '网络异常'))
    return
  }
  if (currentSessionId.value !== sid) return
  if (!rec) {
    message.info('该审批请求不存在或已失效')
    clearApprovalCard(approvalId)
    clearApprovalQuery(approvalId)
    return
  }
  // 链接里的审批不属于当前会话（旧链接/会话已切走）：不在这里裁决，也不冒充「已失效」。
  // 子助手分支上翻的卡 sessionId 是临时子会话，归属看 parentSessionId
  const recSid = rec.sessionId ? String(rec.sessionId) : ''
  const recParent = rec.parentSessionId ? String(rec.parentSessionId) : ''
  if ((recSid || recParent) && recSid !== String(sid) && recParent !== String(sid)) {
    clearApprovalQuery(approvalId)
    return
  }
  if (rec.status !== 'PENDING') {
    message.info('该审批已处理：' + approvalStatusText(rec.status))
    clearApprovalCard(approvalId)
    clearApprovalQuery(approvalId)
    return
  }
  const res = await hydratePendingApprovals(sid, approvalId)
  if (currentSessionId.value !== sid) return
  if (res === 'mounted' || res === 'exists') {
    hooks.revealApproval?.(approvalId)
    clearApprovalQuery(approvalId)
    return
  }
  if (res === 'error') {
    // 待批列表没取回来：别把它说成「已失效」，也别清参数——刷新还能再试
    message.warning('待审批记录没取回来，稍后刷新再试')
    return
  }
  if (res === 'dead') {
    message.warning('该审批已失效（本轮已结束或服务已重启），无法再批准')
  } else {
    message.warning('该审批已超时或已失效，无法再批准')
  }
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
      message.warning('这一轮回答还在生成，稍后回到本会话查看')
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
  answerApi: answerAgentAsk, ignoreApi: ignoreAgentAsk, notify: message, emitNotifChanged,
  retireAskCard: m => retireAskCard(m), stopRestoredPoll: () => stopRestoredPoll(),
  pollRestoredAnswer: sid => pollRestoredAnswer(sid), switchSession: sid => switchSession(sid),
  // 迟到作答（DSH continued）：走与用户亲手打字完全同一条发送出口（跑着就插话、空闲就新一轮）
  deliverContinuedAnswer: t => { text.value = t; send() },
  currentSid: () => currentSessionId.value, scrollSoft: () => hooks.scrollSoft?.()
})
const askSubmitAll = m => submitAskImpl(m, askCtx())
const ignoreAsk = m => ignoreAskImpl(m, askCtx())

/**
 * 接流气泡的收场：这一轮其实已经没了（服务端刚好收尾、服务重启、或通道再也接不上）时用它。
 * 不许留一颗转圈的气泡骗用户「还在跑」——摘掉登记、收掉进行中态，并按历史重显真实结果。
 */
const finishResumedShell = (msg, st, sid, reload = true) => finishResumedShellImpl(msg, st, sid, reload,
  { chatStreams, currentSid: () => currentSessionId.value, switchSession })


// 接流生命周期（探测去重/重连计数/已收场轮）在 ./streamLifecycle.js（transport 层）。
const streamLifecycle = createStreamLifecycle()

// 接流入口在 ./streamAttach.js（transport 层）。
const attachRunningTurn = (sid, reuseMsg) => attachRunningTurnImpl(sid, reuseMsg, {
  chatStreams, lifecycle: streamLifecycle, getChatRun,
  ensureGoalPlaceholder, takeGoalPlaceholder, currentSid: () => currentSessionId.value, streamAnswer
})

// 断线重挂（与 DSH 同口径）：连接是连接、轮次是轮次。通道断了只说明这条流没了，
// 服务端那一轮照样在跑——按会话登记一条重挂循环，退避重试到接上、或探到那一轮已收场为止。
// 循环本体在 ./turnReconnect.js（transport 层，可单测）；这里只注入引擎状态与副作用。
// （reconnectingSids 声明在 loading 上方：它要参与「这个会话还在答」的判定。）
const turnReconnect = createTurnReconnect({
  onDisconnect: (msg, sid) => { msg.retrying = true; msg.loading = true; reconnectingSids.add(sid) },
  onEnd: sid => reconnectingSids.delete(sid),
  clearRetrying: msg => { msg.retrying = false },
  isViewing: sid => currentSessionId.value === sid,
  hasStream: sid => chatStreams.has(sid),
  online: () => typeof navigator === 'undefined' || navigator.onLine !== false,
  attach: async (sid, msg) => (await attachRunningTurn(sid, msg)).status,
  // 探到那一轮已收场：按历史重显真实结果（绝不挂「本轮失败」）
  settleByHistory: (msg, sid) => finishResumedShell(msg, null, sid),
  setTimer: (fn, ms) => setTimeout(fn, ms),
  clearTimer: t => clearTimeout(t),
  onOnline: fn => window.addEventListener('online', fn),
  offOnline: fn => window.removeEventListener('online', fn)
})

/** 取消该会话的重挂（切走 / 停止本轮 / 页面卸载），返回那颗还没收场的消息 */
const cancelReconnect = sid => turnReconnect.stop(sid)

// 接流快照 reducer 在 ./streamReducer.js（reducer 层），引擎只注入状态与派生函数。
/**
 * 收一轮问答的流。resume=true 时这一轮不是本地发起的：接的是服务端还在跑的那一轮
 * （见 attachRunningTurn），此时不发 POST /chat，改开 GET /chat/resume 通道，
 * 正文与工具卡片等由第一帧 snapshot 补齐、之后与实时流走同一套回调。
 */
const streamAnswer = (question, imgs, replaceMsg, isFirstMessage, autoRetry = 1, deepThink = false,
                      attachments = [], skills = [], mentions = [], prev = null, historyRefs = [],
                      editMessageId = '', editUserMsg = null, planMode = false, resume = false,
                      fromSteer = false, planBase = null) => {
  // prev = 自动重试上下文 { sid, agentId, model }：沿用原会话与原选择，不读当前 UI 态
  //（重试定时器触发时用户可能已切到别的会话/换了模型）
  const sid = prev ? prev.sid : currentSessionId.value
  const agentId = prev ? prev.agentId : (currentAgentId.value || '')
  // 作答方在发问这一刻就是确定的：归属随气泡一起写上，不等 done 才补——否则整段流式期间
  // 「由 X 回答」一直空着。没选智能体（空 agentId 走全局配置）就没有归属，不编一个名字顶上。
  const turnAgentName = agentId ? (agentList.value.find(a => a.id === agentId)?.name || '') : ''
  // 会话已有 ID 时**不再**随发送带模型：会话里存的那一份是权威（选择器改动即时 PUT 落库，后端也按它作答）。
  // 把页签内存里的那一份发上去，等于用一个端的选择盖掉另一个端刚改过的值——A 端刚切到
  // 可用模型，B 端一句重发又把已停用的写回会话，两边来回盖、每轮都在重建实例。
  // 只有还没有会话 ID 的首问才随载荷带过去（那时 PUT 无处可落，后端补建会话后据此落库）
  const model = prev ? prev.model : (currentSessionId.value ? '' : (currentOverrideModel.value || ''))
  // 重新生成：被替换的旧回答 ID 必须在下面 fresh 覆盖之前捕获（fresh 会把 messageId 置空，
  // 覆盖后再读就永远是空 → 后端不软删旧回答 → 刷新后同一问题出现两条答案）
  const replacedMessageId = replaceMsg && replaceMsg.messageId ? replaceMsg.messageId : ''
  // 流式回调统一改写 msg 对象（而非 messages.value[idx]）：切走会话后 messages 数组已换人，
  // 下标会指错位置；对象引用由 chatStreams 持有，切回来时 switchSession 把它接回视图尾部
  // model 留空，等 done/接流快照带后端那份权威值回来：前端预填的是「生效引用」（覆盖>个人默认），
  // 后端落库的是模型名那一段，两边写法不同却都叫 model——拿引用去和上一条的落库值比，
  // 同一个模型会被判成「已切换」，done 一到又自己消失（刷新后更是从来没有过这条线）
  const fresh = { role: 'ai', content: '', images: [], sources: [], related: [], degradations: [], warnMsg: '', loading: true, retrying: false, thinking: '', thinkLoading: false, time: Date.now(), turnClockFromServer: false, turnMs: 0, humanWaitMs: 0, artifacts: [], toolCalls: [], skillHits: [], plan: null, planCard: null, timeline: [], errorCard: null, model: '', goalWaiting: false, agentId: agentId || '', agentName: turnAgentName }
  // 接流时被替换的那颗气泡沿用它的起表点：目标占位气泡的起表点是用户按下回车那一刻，
  // 断线重挂的残影是这一轮真实的开轮时刻——两者都不能在接上的一瞬跳回 0（数字变小读成计时坏了）。
  // 服务端快照随后会用 elapsedMs 再对一次表（见 streamReducer 的 earlierStart）。
  const heldStartAt = resume && replaceMsg ? Number(replaceMsg.time) || 0 : 0
  const msg = replaceMsg ? Object.assign(replaceMsg, fresh, { messageId: null, fb: null }) : reactive(fresh)
  if (heldStartAt) msg.time = heldStartAt
  // 用户自己发起一轮（非接流）：占位那一行让位给它，一屏不许有两颗「正在溯源」
  if (!resume) dropGoalPlaceholder()
  // 气泡只长在「正在看的会话」上：后台补发的插话（prev 指向别的会话）由 chatStreams 持有，
  // 切回去时 switchSession 接回视图尾部——直接 push 会把旧会话的气泡推进当前会话的列表
  if (!replaceMsg && currentSessionId.value === sid) messages.value.push(msg)
  const viewing = () => currentSessionId.value === sid  // 只有正在看这个会话才滚动/贴底
  // 流式期间：先按新高度重算留白、等它落屏，再贴底——留白让贴底落点停在本轮问题置顶处，
  // 回答长过一屏后留白归零，贴底就自然变成跟着最新内容走（用户上翻仍会解除跟随）
  // 快照回放期间暂停贴底：子智能体帧可能有上百条，逐帧滚动会让页面抖成筛子；回放完再统一滚一次
  let replayingSnapshot = false
  const liveScroll = () => { if (!replayingSnapshot && viewing()) hooks.scrollFollow?.() }
  const abort = new AbortController()
  const st = { msg, abort }
  chatStreams.set(sid, st)

  // 「服务端受理过这一轮」的唯一证据：它发过帧（POST 通道第一帧是 turn_start，接流第一帧是 snapshot）。
  // 没受理过 = 这条提问压根没送到，与「通道断了但答案还在跑」是两件事，收尾口径不同。
  let admitted = false
  let transportError = ''
  // 本轮真的失败（服务端 error 帧 / 业务失败 / 提问没送出去）：保半程内容 + 错误卡 + 重新生成。
  // 断线**不走这里**——那是连接的事，见下面 beginReconnect。
  const failNow = (error, kind) => failTurn(msg, {
    error, kind,
    flushNow: () => flushNow(),
    settlePreparingCards,
    settleAbortedPlan,
    dropStream: () => { if (chatStreams.get(sid) === st) chatStreams.delete(sid) },
    notify: m => message.error(m),
    viewing,
    scroll: () => liveScroll()
  })
  // 重挂循环：探测 → 接流 → 退避再试（分支判据在 ./reconnectPolicy.js，循环本体在 ./turnReconnect.js）。
  // 本函数只负责把这一轮的事实交给它：受理过没有、断的是不是接流通道、没送出去时怎么收尾。
  const beginReconnect = () => {
    // 旧通道已经死了：从在跑登记里摘掉它，下一次接流才进得去
    if (chatStreams.get(sid) === st) chatStreams.delete(sid)
    turnReconnect.start(sid, msg, {
      admitted, resume, error: transportError,
      onFail: e => failNow(e, 'unreachable')
    })
  }
  // 顶部那条整轮用时从开流第一秒就要跟着跳，不能等第一张工具卡才起表：纯检索、纯思考那几分钟
  // 同样在跑，没这一句它停在「0 秒」不动，看着像卡死
  ensureTick()
  // 首条消息发出即把会话抬进侧栏列表（列表隐藏空会话，等 onDone 才刷新的话长回答期间不可见）
  if (isFirstMessage) markSessionActive(sid, question)
  // 本轮视角归位：留白补足并落屏后贴底，问题正好落在视口最上（与空会话首问同一落点）
  if (viewing()) hooks.scrollForce?.()
  // 流式渲染节流（token/过程批量落屏、接流去重）在 ./streamBuffer.js
  const { flushNow, resetFromContent, appendToken, appendProcess, gotToken } = createStreamBuffer({
    msg,
    extendText: (m, from, to) => extendTimelineText(m, from, to),
    extendProcess: (m, from, to) => extendTimelineProcess(m, from, to),
    scroll: () => liveScroll()
  })
  // 思考帧（增量成段/终稿校正/断段）在 ./streamThoughtFrames.js
  const thought = createThoughtFrames({ msg, flushNow, scroll: () => liveScroll() })
  const handlers = {
    signal: abort.signal,
    // 服务端起表基准（开流第一帧，时长形式）：把秒表锚到服务端收到提问那一刻。
    // 没有它，秒表只能按本地「按下回车」起表，收尾换成落库那份服务端墙钟时会掉一个网络往返
    // 外壳与引擎各发一帧、同一基准（后者 prepMs 更准），
    // 重锚只校正基准，不会改变已显示的量级。绝对时刻不下发，两端钟差进不来。
    onTurnStart: payload => {
      // 服务端起表基准（开流第一帧）：合并语义在 streamMerge.applyTurnStart 单源，与分享页共用
      admitted = true
      applyTurnStart(msg, payload, alignTick)
      // 新一轮开始：通知页面清掉上一轮的只读浏览器快照镜像
      hooks.onTurnStart?.(payload, msg)
    },
    // 浏览器只读快照：新事件经 CHAT_EVENT_CB 映射到这里，转给页面自己的镜像状态（不进账本）
    onBrowserSnapshot: (payload, ev) => hooks.onBrowserSnapshot?.(payload, ev),
    // 团队实时事件：团队面板/胶囊在旁侧监听同一个 window 事件做去抖重拉。
    // 只做通知（不把团队帧塞进正文时间线），面板自己按根会话 id 拉最新成员/任务/消息。
    onTeamMember: () => dispatchTeamEvent(),
    onTeamMessage: () => dispatchTeamEvent(),
    onTeamTask: () => dispatchTeamEvent(),
    onSnapshot: payload => {
      // 接流的第一帧（只有 resume 那条通道会有这一帧）：本轮已产出的东西一次铺进气泡
      let snap = null
      try { snap = typeof payload === 'string' ? JSON.parse(payload) : payload } catch (e) { snap = null }
      if (!reduceTurnSnapshot(msg, snap, sid, { setTodos, autoCompactSid, liveCompactedSid, planCardFromRecord, alignTick })) {
        // 这一轮其实已经收尾（或压根不在跑/不是本人的）：这颗空气泡不该留下，按历史重显真实结果
        finishResumedShell(msg, st, sid, false)
        return
      }
      admitted = true
      resetFromContent(msg.content)
      // 快照里属于「运行期增量」的几份：复用实时事件同一套回调重建（./streamReducer.js）
      replayingSnapshot = true
      try {
        replaySnapshotExtras(msg, snap, handlers)
      } finally {
        replayingSnapshot = false
      }
      // 正在长的思考段：快照接上时必须沿用同一条段，否则下一片增量会另起一段
      thought.setOpen(snap.thinkingOpen)
      // 插话段已在快照里，历史里那条同名用户消息要退场（插话已应用后才接流的入口）
      if (currentSessionId.value === sid) messages.value = dropSteeredUserRows(messages.value)
      // 快照里的计划卡可能带血缘（调整自 vN）：补一次双向指认，旧卡的「查看新一轮」不必等刷新
      if (msg.planCard) linkPlanLineage(messages.value)
      liveScroll()
    },
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
    onImage: imgs2 => {
      // 配图全量替换、非法帧不动状态：合并语义在 streamMerge.applyImageFrame 单源，与分享页共用
      applyImageFrame(msg, imgs2)
    },
    onArtifact: payload => {
      try {
        const a = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (!a || !a.url) return
        if (!Array.isArray(msg.artifacts)) msg.artifacts = []
        msg.artifacts.push(a)
        // 产物按生成时刻插入时间线（只记下标），刷新后仍在原位而不是堆到气泡底部
        pushTimelineArtifact(msg, msg.artifacts.length - 1)
        // 入场动效只认「实时到达」的这一件：历史恢复不会走这里，刷新不会满屏乱动
        msg.freshArtifactId = a.id || a.filename || ''
        if (msg._freshTimer) clearTimeout(msg._freshTimer)
        msg._freshTimer = setTimeout(() => {
          msg.freshArtifactId = ''
          msg._freshTimer = 0
        }, 900)
        liveScroll()
      } catch (e) { /* 忽略 */ }
    },
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
      transportError = String(e || '')
      // 自动重试收紧到「提问还没送到服务端」这一种：连接压根没建起来，重发就是重发一条没送出去的提问。
      // 服务端受理过（admitted）就绝不能重发——那会变成一轮全新问答（重复落库、重复计费），
      // 而它本来只断了一条通道。判定用 navigator.onLine 优先（移动端可靠维护），再退回 fetch 的典型网络错误特征。
      const netDown = (typeof navigator !== 'undefined' && navigator.onLine === false) ||
        /Failed to fetch|NetworkError|ERR_INTERNET|ERR_NETWORK|network ?error/i.test(String(e))
      if (autoRetry > 0 && !admitted && !gotToken() && netDown) {
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
  // 传输分岔：本地发起的轮发 POST /chat；接流只开一条 GET /chat/resume 挂到服务端那一轮上，
  // 事件派发与 done/error 收尾两条路径完全共用（这是刻意的设计——两套 UI 语义不许分叉）
  if (resume) resumeChat(sid, handlers)
  else sendQuestion(sid, question, imgs, handlers)
}
const switchVersion = (mi, delta) => switchVersionImpl(mi, delta, { messages: () => messages.value })
const regenerate = mi => regenerateImpl(mi, {
  loading: () => loading.value,
  messages: () => messages.value,
  splitAtts,
  notifyWarning: msg => message.warning(msg),
  streamAnswer
})
// ==================== 分支版本切换（持久化多版本：编辑重发 + 重新生成刷新后仍可切） ====================
const { variantSwitching, switchBranch } = createBranchSwitcher({
  messages: () => messages.value,
  isLoading: () => loading.value,
  switchVersion,
  switchApi: switchMessageVariant,
  reload: () => switchSession(currentSessionId.value),
  notifyWarning: msg => message.warning(msg)
})

// 收口口径（工具卡定格/停止标注）在 ./turnCloseout.js；停止动作在 ./stopTurn.js
const stop = createStopTurn({
  currentSid: () => currentSessionId.value,
  streams: chatStreams,
  stopApi: stopChatTurn,
  notifyWarning: msg => message.warning(msg),
  // 通道断了、正在重挂时本地已经没有流：停止仍要落到服务端（否则那一轮转后台跑完还占着会话锁）
  reconnect: {
    take: sid => cancelReconnect(sid),
    settle: (msg, sid) => finishResumedShell(msg, null, sid)
  }
})

// 运行中插话队列（服务端持有、刷新可恢复）在 ./steerQueue.js（actions 层）
const { steerQueue, steerResend, steer, stageSteer, sendSteer, editSteer, dropSteer, moveSteer, dropApplied,
        restoreSteerQueue, enqueuePlanAdjust } = createSteerQueue({
  currentSid: () => currentSessionId.value,
  hasLiveStream: sid => chatStreams.has(sid),
  steerApi: steerChatTurn,
  enqueueApi: enqueueSteer,
  listApi: steerQueueList,
  steerItemApi: steerQueued,
  dropApi: dropQueued,
  notify: (msg, level) => (level === 'info' ? message.info(msg) : message.warning(msg)),
  textOf: () => text.value,
  setText: v => { text.value = v },
  // 取回队列条目时材料一并回到输入框（图按 data URL 还原，附件按 fileId 挂回）
  attachImage: u => attachImage(u),
  attachUploaded: a => attachUploaded(a)
})

/**
 * 插话队列的恢复与刷新：队列本身由服务端持有（刷新不丢），前端在三个时刻读一次投影——
 * 进/切会话、本轮开跑（服务端刚 drain 过）、目标终态（服务端已把排队项收敛）。
 * 不再有前端重试定时器：drain 由引擎在框架 inbox/opened 时触发。
 * 只刷新当前会话的「排队/已送出」那段，本地还没同步上去的草稿原样留着。
 */
watch(currentSessionId, sid => { if (sid) restoreSteerQueue(sid) }, { immediate: true })
watch(loading, v => { if (v) restoreSteerQueue() })
watch(goalTerminal, v => { if (v) restoreSteerQueue() })

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

/**
 * {@code steer} 帧：框架把这句写进了正在跑的这一轮（后端按 inbox/applied 发，位置就是那一刻）。
 * 两件事：撤下队列里等它的那一条（没有也不报错——别的设备上看不到发起方的队列），以及把它长进
 * 这一轮回答的时间线里。
 */
function applySteerFrame (payload, msg) {
  let f = payload
  try { if (typeof payload === 'string') f = JSON.parse(payload) } catch (e) { return }
  const t = f && typeof f.text === 'string' ? f.text : ''
  if (!t) return
  const id = f.messageId || ''
  dropApplied(id)
  if (id) messages.value = messages.value.filter(m => !(m.role === 'user' && m.messageId === id))
  pushTimelineSteer(msg, t, id, Array.isArray(f.images) ? f.images : [],
                    Array.isArray(f.attachments) ? f.attachments : [])
}

/** 按原载荷把一条插话作为完整新一轮发出（与计划「继续对话」同一条通路）
 *  `fromSteer` 让后端在那句话后面补一句身份声明（"这是上一轮还没答完时补的方向，不是新提问"）——
 *  不带它，模型会当成用户又问了一遍（短问答必现：它会复述「你消息里的 X 这一段…」），
 *  插话最核心的「改了方向立刻生效」就废了。 */
function sendSteerRound (s, origin = null) {
  // origin：发起那一轮的会话与作答智能体（收尾自动派发时用户可能已切走，新一轮必须回到原会话）。
  // 复用自动重试的 prev 通道：「沿用原会话，不读当前 UI 态」。atHome=人还看着这个会话才进气泡列表
  const back = origin ? { sid: origin.sid, agentId: origin.agentId || '', model: '' } : null
  const atHome = !back || back.sid === currentSessionId.value
  const userMsg = reactive({ role: 'user', content: s.text, images: s.images || [],
                        attachments: s.attsMeta || [], attachData: s.atts || [],
                        skills: s.skills || [], mentions: s.mentions || [], historyRefs: s.historyRefs || [],
                        deepThink: !!s.deepThink, planMode: !!s.planMode, fromSteer: true,
                        time: Date.now(), messageId: null })
  if (atHome) messages.value.push(userMsg)
  streamAnswer(s.text, s.images || [], null, atHome && messages.value.length === 1, 1, !!s.deepThink,
               s.atts || [], s.skills || [], s.mentions || [], back, s.historyRefs || '', '', userMsg,
               !!s.planMode, false, true)
}

/**
 * 补发后端没收上的那句插话：它没进过这一轮的上下文，所以界面上也不该长在这一轮的回答里
 * （那句只留在队列条上，收尾时随这里一起撤下），而是作为<b>新一轮的问题</b>重新出现。
 * <p>库里它仍是点「立即发送」那一刻落的同一条用户消息：后端带 fromSteer 时跳过重复落库，
 * 这颗新气泡直接挂上那个 messageId，刷新后也只有这一条。
 */
function resendSteer (t, origin = null) {
  const back = origin ? { sid: origin.sid, agentId: origin.agentId || '', model: '' } : null
  const atHome = !back || back.sid === currentSessionId.value
  const existing = t.messageId
          ? messages.value.find(m => m.role === 'user' && m.messageId === t.messageId)
          : null
  // 附件带着引用补发：那句话没进过本轮上下文，随行的材料同样没进去，不能只把字再发一遍
  const attsMeta = Array.isArray(t.attachments) ? t.attachments : []
  const atts = attsMeta.filter(a => a && a.fileId).map(a => ({ name: a.name, mime: a.mime, fileId: a.fileId }))
  const userMsg = existing || reactive({ role: 'user', content: t.text, images: [],
                        attachments: attsMeta, attachData: atts, skills: [], mentions: [], historyRefs: [],
                        deepThink: false, planMode: false, fromSteer: true,
                        time: Date.now(), messageId: t.messageId || null })
  if (atHome && !existing) messages.value.push(userMsg)
  streamAnswer(t.text, [], null, false, 1, false, atts, [], [], back, '', '', userMsg,
               false, false, true)
}

/**
 * 本轮收尾时结算插话：先补发后端没收上的那句，再把还挂在本地队列里的（没点过「立即发送」的、
 * 带材料本轮塞不进去的）作为新一轮交给模型。
 * <p>为什么收尾要自动发：本轮已经结束了，"插话"没有可插入的对象，`/chat/steer` 必然被拒；
 * 用户打的那句话不能因此丢掉。带材料的本来就只有这条路。
 * <p>一次只派一条（同一会话同时只能跑一轮），剩下的等它结束再派——done 会再次走到这里。
 * @param backendLeft 后端 done 里回来的 pendingSteers：[{ text, messageId }]
 */
function flushPendingSteers (backendLeft = [], origin = null) {
  for (const raw of backendLeft) {
    const t = typeof raw === 'string' ? { text: raw, messageId: '' } : (raw || {})
    if (!t.text) continue
    // 本地队列里同文那条撤下（用户点了发送、又在这句还没退场时手动重打一遍）：补发已经带上它了，
    // 发两遍等于替用户把同一句话讲两次
    steerQueue.value = steerQueue.value.filter(s => s.text !== t.text)
    // 条目带上 origin：补发可能隔了别的轮才轮到（if (loading) return 的排队），到时当前会话
    // 已换人——发回原会话靠条目自己记的那份，不靠当时的 currentSessionId
    if (!steerResend.value.some(x => x.text === t.text)) {
      steerResend.value.push({ ...t, sid: origin?.sid || '', agentId: origin?.agentId || '' })
    }
  }
  // 本轮已经收尾，队列里还挂着「等这一轮听到」的那几条没有对象可插了：上面没被补发接走的，
  // 就是模型已经收到过的（那一帧的留痕长在回答里，或刷新后由库里那条用户消息回显）——
  // 留在条上会被读成「我说了但没人理」。
  steerQueue.value = steerQueue.value.filter(s => !s.awaiting)
  if (loading.value) return   // 已经有人在跑了（用户抢先发了新消息）：留着，下轮结束再结算
  const left = steerResend.value.shift()
  if (left) {
    resendSteer(left, left.sid ? { sid: left.sid, agentId: left.agentId } : null)
    return
  }
  const next = steerQueue.value.find(s => !s.sending && !s.planAdjust)
  if (!next) return
  steerQueue.value = steerQueue.value.filter(s => s !== next)
  sendSteerRound(next, origin)
}

/**
 * 离开一个会话（切换/新建/删除当前会话）时结算插话队列。steerQueue 与 steerResend 都是
 * 模块级单例、不分会话：不清的话，条目会以「上一会话那轮还没说完的话」的样子挂在新会话的
 * 输入框上（用户读成新会话的事），旧会话后台轮收尾时更会按当时的 currentSessionId 派发——串话。
 * <p>结算口径：
 * <ul>
 * <li>已送出/在途的（awaiting/sending）：效果已经落在旧会话那一轮里，条目直接撤下。旧会话收尾时
 *     后端按 pendingSteers 交给收尾结算，不依赖这两份本地数组。</li>
 * <li>没发出去的草稿（含带材料等下一轮的 deferred）：文字放回输入框（不覆盖正在打的字，多条按行
 *     合并），材料跟随 editSteer 同一口径不还原——用户自己决定重发还是删掉，说什么都不静默丢。</li>
 * <li>steerResend（后端交回「说过、模型没收到」的）：内容已随 messageId 落在旧会话库里，不丢；
 *     不在这里补发——补发目标是旧会话，而用户已经离开了，轮到它时由带 origin 的收尾结算处理。</li>
 * </ul>
 */
function settleSteerOnLeave () {
  if (!steerQueue.value.length && !steerResend.value.length) return
  // 只有「还没同步到服务端」的本地草稿需要放回输入框；已经在服务端排队的条目留在那边，
  // 回这个会话时由队列投影恢复——两处都留一份就会变成同一句话发两遍
  const drafts = steerQueue.value
    .filter(s => s.local && !s.awaiting && !s.sending)
    .map(s => String(s.text || '').trim())
    .filter(Boolean)
  steerQueue.value = []
  steerResend.value = []
  if (!drafts.length) return
  const add = drafts.join('\n')
  const cur = String(text.value || '').trim()
  text.value = !cur ? add : (cur.includes(add) ? cur : cur + ' ' + add)
  message.info('没发出的插话已放回输入框（它属于上一个会话）')
}
// 手动压缩与摘要面板已抽到 useCompactSession.js。这里解构出同名符号，下面读写点不改。
const {
  manualCompacting,
  autoCompactSid,
  liveCompactedSid,
  compacting,
  compactNotice,
  compactContext,
  compactSummaryOpen,
  compactSummaryLoading,
  compactSummary,
  openCompactSummary,
  recompactWith,
} = useCompactSession({
  currentSessionId, loading, notify: message,
  compactApi: compactSessionApi, summaryApi: sessionHistorySummaryApi,
})

// ==================== 挂载初始化 ====================
// 原 ChatPage onMounted 的引擎侧主体（window/document 监听与 refreshSetupGuide 留在视图）：
// 先 loadSessions，再拉智能体/技能候选（不阻塞首屏），然后按 sid / newChatTick / autoPick
// 三分支落位，聚焦输入框，最后拉配置类数据（检索调试入口/排障显示开关/个人默认模型/模型展示映射）。
const ready = async () => {
  await loadSessions()
  loadAgents()       // 智能体下拉候选（不阻塞首屏）
  loadSkills()       // 技能菜单候选（输入框「+」菜单，不阻塞首屏）
  const sid = route.query.sid
  if (sid) {
    await switchSession(sid)
  } else if (sessionStore.newChatTick > sessionStore.newChatSeen) {
    // 其它页面点过「新建对话」后跳转过来：消费该信号，直接进空会话；
    // 「试一试」带的预选智能体一并消费，落进新会话的下一问待选值
    sessionStore.newChatSeen = sessionStore.newChatTick
    await createNewSession(takeNewSessionAgent())
  } else {
    await autoPick()
  }
  hooks.focusInput?.()
  // 检索调试入口是管理员调参（chat.retrievalDebugEnabled，配置里标 debug）：只有管理员才拉 /config
  // ——/config 是管理端点，普通用户调它会 403，触发全局 403 提示（角色未授权），故只对管理员读
  if (isAdminSync()) {
    getConfig().then(r => {
      if (!r.success) return
      debugEntryVisible.value = r.data?.chat?.retrievalDebugEnabled?.value === 'true'
    }).catch(() => {})
  }
  // 同一个开关的「显示」语义走公开端点：归属徽标/派遣提示/引用分值的显隐对所有人生效（含普通用户）
  getRuntimeConfig().then(r => {
    if (!r.success) return
    debugDisplayVisible.value = r.data?.ui?.debugEntry === true
    // 个人显示偏好：缺省/未下发时保持默认开
    showRelatedSuggestions.value = r.data?.ui?.showRelatedSuggestions !== false
  }).catch(() => {})
  getUserPreference().then(r => {
    userDefaultModel.value = (r && r.data && r.data.defaultModel) || ''
    userDefaultModelIssue.value = (r && r.data && r.data.defaultModelIssue) || ''
  }).catch(() => {})
  loadModelIndex().then(idx => { modelIndex.value = idx || {} }).catch(() => {})
}

// 产物签名定时续签：签名有效期 1 小时，切会话/历史恢复时换过一次只覆盖「刚进来」的那批；
// 页面连续开着不动（排查问题、投屏演示、跑长任务）超过一小时后，卡片上的 URL 又会全部过期。
// 每 30 分钟换一次，留足冗余；无产物时定时器空转（首行 byId 为空直接返回，不发请求）。
// 标签页隐藏时跳过——不可见的页面不需要能下载，省掉无谓请求。
let signTimer = null
const stopSignTimer = () => { if (signTimer) { window.clearInterval(signTimer); signTimer = null } }
if (typeof window !== 'undefined') {
  signTimer = window.setInterval(() => {
    if (document.visibilityState === 'hidden') return
    refreshArtifactSigns()
  }, 30 * 60 * 1000)
  // 视图卸载时清掉，避免定时器随会话累积（引擎每进一次聊天页就 new 一个）。
  // onUnmounted 必须在 setup 的同步执行期注册才有当前实例上下文，所以包一层 try：
  // 引擎也可能被非组件场景调用，此时退化成由 ready 前的分支自行清理。
  try { onUnmounted(stopSignTimer) } catch (e) { /* 无当前实例：定时器随页面存活，可接受 */ }
}

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
