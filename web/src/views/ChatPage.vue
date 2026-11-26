<template>
  <div ref="chat2Ref" class="chat2" @mouseenter="railVis = true" @mouseleave="railVis = false; railHoverIdx = -1">
    <!-- 中间：标题栏 + 消息流 + 输入区 -->
    <div class="chat-col">
      <!-- 标题栏（成员会话条 / 工作区标识 / 查找分享状态 / 完全权限确认 / 附件 input）：
           见 chatpage/ChatHeaderBar.vue -->
      <ChatHeaderBar />
    </div>

    <!-- 左侧问题锚点轨：一条刻度 = 一轮提问。不常驻——鼠标进聊天区才淡入（离开即收）；
         刻度默认 14px 短条，悬浮或滚到当前轮才伸长到 24px 并亮主色。点击把那一轮滚到视区顶部。
         预览概览卡在轨外的 .qa-pop（fixed 弹层，轨的 overflow 滚动会裁掉轨内悬浮卡），
         由 showAnchor 按触发刻度的屏幕坐标定位；至少两轮才出现，窄屏不渲染。 -->
    <nav v-if="!isNarrow && qaAnchors.length >= 2" class="qa-rail" :class="{ vis: railVis }"
         aria-label="问答锚点" @mouseleave="railHoverIdx = -1">
      <div v-for="a in qaAnchors" :key="a.idx" class="qa-item"
           :class="{ on: railActiveIdx === a.idx }"
           @mouseenter="showAnchor(a, $event)" @click="gotoAnchor(a)">
        <span class="qa-tick"></span>
      </div>
    </nav>
    <!-- 预览概览卡（轨外弹层）：问题深色一行 + 回答摘录灰字；pointer-events:none 不挡鼠标 -->
    <div v-if="railHoverAnchor" class="qa-pop" :style="railPopStyle" role="tooltip">
      <div class="qa-q">{{ anchorQ(railHoverAnchor) }}</div>
      <div v-if="anchorA(railHoverAnchor)" class="qa-a">{{ anchorA(railHoverAnchor) }}</div>
    </div>

    <!-- 右侧状态栏（可收起）：引用来源 / 产物 / 计划（各自有条件才显示）
         宽窄两态共用这一份 DOM：宽屏由 panelOpen 控制（占位列），窄屏由 mPanelOpen 控制
         （浮层/底部 sheet，默认收起）。两态只靠 .as-sheet 类 + CSS 媒体查询区分。 -->
    <!-- 右栏左缘拖拽条：宽屏两栏此消彼长（消息区 flex:1，右栏定宽）；拖动只改 --rp-w，消息区不会被挤没（有下限）。
         窄屏右栏是浮层/贴底 sheet，盖在消息之上而非占位，拖动语义不成立，故不显示。 -->
    <div v-if="panelOpen && !isNarrow" class="rp-divider" role="separator" aria-orientation="vertical"
         title="拖动调整右栏宽度" @mousedown.prevent="startPanelResize"></div>
    <!-- 右侧状态栏（引用来源 / 产物 / 计划 / 沙盒目录 / 后台进程）：DOM 与 scoped 样式见
         chatpage/ChatRightPanel.vue -->
    <ChatRightPanel />

    <!-- 正文浮层宿主（来源弹窗 / 角标悬浮卡 / 图片灯箱）：DOM 与状态见 components/AnswerViewerHost.vue
         与 chat/answerViewer.js。整页只挂这一处——每条消息各挂一份会让多图切换互相打架。 -->
    <AnswerViewerHost :debug-visible="debugDisplayVisible" :session-id="currentSessionId" />

    <!-- 完整计划 / 版本轨 / 裁决：卡片与右栏的「查看完整计划」都落到这一处抽屉（H5 由移动壳挂同款） -->
    <PlanViewer :open="planViewer.open" :card="planViewer.card" :sid="planViewer.sid"
                :pkey="planViewer.pkey" :title="planViewer.title" :version="planViewer.version"
                :initial-tab="planViewer.tab" :msg="planViewerMsg" :progress="planViewerProgress"
                :adjusts="planViewerAdjusts" :countdown="planCountdownText" :expired="planExpired"
                @close="closePlanViewer" @approve="onViewerApprove"
                @revise="onViewerRevise" @supersede="onViewerSupersede" @locate="onViewerLocate"
                @stop="onViewerStop" @adjust="onViewerAdjust" @locate-source="onViewerLocateSource" />

    <!-- 批准后调整计划：A 插话（不中断）/ B 停止并重新计划；与移动壳共用同一组件 -->
    <PlanAdjustSheet :open="planAdjust.open" :running="planAdjust.running"
                     :done="planAdjust.done" :doing="planAdjust.doing" :total="planAdjust.total"
                     :failed="planAdjust.failed" :step-label="planAdjust.stepLabel"
                     :busy="planAdjust.busy" :error="planAdjust.error"
                     :suggest-replan="planAdjust.suggestReplan"
                     @close="closePlanAdjust" @submit="submitPlanAdjust" />

    <!-- 会话分享（只读链接） -->
    <!-- 各弹窗（分享 / 反馈 / 免责声明 / 检索调试 / 摘要 / 粘贴全文）：见 chatpage/ChatPageModals.vue -->
    <ChatPageModals />

    <!-- 深度思考面板卡：悬浮模型下拉行时在该行右侧弹出（Teleport 到 body + fixed 定位，随悬浮行开合）。
         展示该模型自己的设置：上下文窗口（只报登记上限，不可调）+ 思考强度（档位豆平铺，
         点选即时写入按模型 localStorage 记忆，生效模型下一轮发送立即生效）；模型不支持思考时只展示窗口信息 -->
    <!-- 思考面板卡 / 上下文容量卡（Teleport 到 body 的浮层）：见 chatpage/ChatOverlays.vue -->
    <ChatOverlays />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, nextTick, watch, shallowRef, h, provide } from 'vue'
import { useMemberLiveView } from '../chat/useMemberLiveView'
import ChatMessageList from './chatpage/ChatMessageList.vue'
import ChatRightPanel from './chatpage/ChatRightPanel.vue'
import ChatComposerArea from './chatpage/ChatComposerArea.vue'
import ChatPageModals from './chatpage/ChatPageModals.vue'
import ChatOverlays from './chatpage/ChatOverlays.vue'
import ChatHeaderBar from './chatpage/ChatHeaderBar.vue'
import { pickChatCtx } from './chatpage/chatCtx'
import { usePanelResize } from './usePanelResize'
import { useBrowserSnapshots } from './useBrowserSnapshots'
import { useGoalBarText } from './useGoalBarText'
import { useChatPageView } from './useChatPageView'
import { useGoalBarCollapse } from './useGoalBarCollapse'
import { useRouter, useRoute } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { LoadingOutlined, DownOutlined, CaretRightOutlined, CheckOutlined, CloseCircleOutlined, FileTextOutlined, DownloadOutlined, GlobalOutlined, ApiOutlined,
         ExclamationCircleOutlined, CopyOutlined, LikeOutlined, DislikeOutlined, MoreOutlined,
         DeleteOutlined, BugOutlined, EditOutlined, PlusOutlined, PaperClipOutlined, PauseCircleOutlined,
         PlayCircleOutlined,
         CompressOutlined,
         RobotOutlined, SettingOutlined, ThunderboltOutlined, LockOutlined, RedoOutlined,
         CloseOutlined, DatabaseOutlined, SearchOutlined, UpOutlined, ShareAltOutlined,
         HistoryOutlined, SwapOutlined, InfoCircleOutlined,
         AppstoreOutlined, ClockCircleOutlined, BookOutlined,
         ArrowRightOutlined, OrderedListOutlined, LeftOutlined, FolderOpenOutlined, FolderOutlined, ExportOutlined,
         AimOutlined, EyeOutlined, SaveOutlined, ReloadOutlined, BulbOutlined,
         SafetyCertificateOutlined, ScheduleOutlined } from '@ant-design/icons-vue'
import { debugRetrieval, deleteMessageGroup, submitFeedback as apiSubmitFeedback,
         addEvalCase, getSessionShare, enableSessionShare, disableSessionShare,
         getSessionWorkspace, getPermissionPresets, setSessionPermission,
         openProjectFolder, updateProjectItem, createSkill, setUserPreference } from '../api'
import { renderMd, resolveImg, onImgError } from '../utils/markdown'
import { loadSessions, sessionStore } from './store'
import { exportAnswerMd, exportSessionMarkdown } from './exportMd'
import { fmtTokens } from '../utils/token'
import { chatDone, guideSnoozed, refreshSetupGuide, setupGuide } from '../utils/setupGuide'

// 追问改写原因文案见 ./rewriteSkipText
import { rewriteSkipText } from './rewriteSkipText'
import { copyText } from '../utils/clipboard'
import { localExecutorId } from '../utils/localExecutor'
import { useSessionShare } from './shareSession'
import ModelSelect from '../components/ModelSelect.vue'
import ProviderIcon from '../components/ProviderIcon.vue'
import BrandMark from '../components/BrandMark.vue'
import AgentAvatar from '../components/AgentAvatar.vue'
import SetupGuide from '../components/SetupGuide.vue'
import WelcomeCopy from '../components/WelcomeCopy.vue'
import { isNarrow, isCoarse } from '../h5/mobile'
// 输入区外壳（卡片/正文与 chip 同流/工具条/拖拽落点）：与公开分享页共用一份，避免两头各写一套
import ChatInput from '../chat/ChatInput.vue'
// 消息列表外壳（与公开分享页同一份）：行/块/气泡布局 + 提问随行材料 + 降级条 + 动作行 + 兜底正文。
// 本页只保留 SourceList 的直接引用——#sources 插槽给的是带检索词与原文片段的版本
import ChatThread from '../chat/ChatThread.vue'
import SourceList from '../chat/SourceList.vue'
// 定高内滚块（深度思考正文、过程簇里的思考/独白）的贴底跟随，与 NoteBody 共用一份指令；
// 跑轮中簇帽体的上下边缘渐隐（ef-top/ef-bot 切 class）用同一文件里的 vEdgeFade
import { vFollowBottom, vEdgeFade } from '../chat/followBottom'
// 窄屏专属 UI 组件（PC 态不渲染，详见 src/h5/README.md）
import MobileChatHead from '../h5/MobileChatHead.vue'
import AskRecordCard from '../components/AskRecordCard.vue'
import NoteTag from '../components/NoteTag.vue'
import NoteBody from '../components/NoteBody.vue'
import AnswerHeader from '../components/AnswerHeader.vue'
import { roundSteps } from '../chat/roundSummary'
import SubAgentChatCard from '../components/SubAgentChatCard.vue'
import MsgDivider from '../components/MsgDivider.vue'
import ToolSpillButton from '../components/ToolSpillButton.vue'
import SessionEventsPanel from '../components/SessionEventsPanel.vue'
// 右栏「过程」卡：跑轮时实时滚动工具步（数据就是引擎那份消息对象，不另取数）
import RightProcessCard from '../components/RightProcessCard.vue'
import TeamPanel from '../components/TeamPanel.vue'
import TeamPill from '../components/TeamPill.vue'
// 右栏「浏览」卡：助手本轮浏览器操作的只读快照镜像（唯一出口是「在新窗口打开」）
import BrowserPanel from '../components/BrowserPanel.vue'
// 顶栏「子智能体」药丸：派遣在跑时给一个不随正文滚走的位置（PC 浮层 / 触屏底部 sheet 同一份组件）
import SubagentPill from '../components/SubagentPill.vue'
import SkillContentModal from '../components/SkillContentModal.vue'
// 目标终态卡（挂在「设为目标」那条回声下）与目标过程抽屉：PC 与移动壳共用同一份
import GoalTerminalCard from '../components/GoalTerminalCard.vue'
import GoalViewer from '../components/GoalViewer.vue'
// 交付包：对话内一张卡 + 右侧抽屉（清单/批量动作），与 H5 共用同一份判据
import DeliveryCard from '../components/DeliveryCard.vue'
import DeliveryPanel from '../components/DeliveryPanel.vue'
// 产物生成态占位：只在「本轮还没有产物、正在生成/交付文件」时出现
import ArtifactPendingCard from '../components/ArtifactPendingCard.vue'
// 回答正文与它的浮层宿主：与两个分享页共用同一份实现（详见 src/chat/answerViewer.js 顶部说明）
import AnswerBody from '../components/AnswerBody.vue'
import AnswerViewerHost from '../components/AnswerViewerHost.vue'

// 聊天引擎：引擎逻辑见 src/chat/useChatEngine.js，纯函数/常量见 src/chat/projections.js
import { useChatEngine } from '../chat/useChatEngine'
import { useChatSearch } from '../chat/useChatSearch'
import Composer from '../chat/Composer.vue'
import PendingAtts from '../chat/PendingAtts.vue'
import PlanCard from '../components/PlanCard.vue'
import PlanViewer from '../components/PlanViewer.vue'
import PlanAdjustSheet from '../components/PlanAdjustSheet.vue'
import { planStepCount, planExecDetail } from '../chat/plan'
import { pendingAnswerText } from '../chat/askUser'
import { chipLabelOf } from '../chat/thinking'
import { useThinkPanel } from '../chat/useThinkPanel'
import { buildQaAnchors } from '../chat/chatAnchors'
import { buildSessionPlans, planStateText } from '../chat/planPanel'
import { useSourceFocus } from '../chat/useSourceFocus'
import { useChatScroll } from '../chat/useChatScroll'
import { useChatPanels } from '../chat/useChatPanels'
import { useHeadStatus } from '../chat/useHeadStatus'
import { useRunSettings } from '../chat/useRunSettings'
import { parseDeepCommand } from '../chat/deepCommand'
import TurnChangesCard from '../components/TurnChangesCard.vue'
import { splitAtts } from '../chat/attachments'
import { useEditResend } from '../chat/useEditResend'
import { useGoalPanels } from '../chat/useGoalPanels'
import { useSessionWorkspace } from '../chat/useSessionWorkspace'
import { SUPPORTED_EXTS, extOf } from '../chat/intake'
// 正文浮层的共享状态：右栏来源联动（hoveredRef / 点角标定位）与灯箱开关要用
import { openImages, previewUrl, openSource, hoveredRef } from '../chat/answerViewer'
import { approvalArgsText } from '../chat/approval'
import { toolLabel, toolDesc, toolCallsView, executedCount, toolDuration,
         procOpen, toggleProc, procSlice, thinkSegOpen, toggleThinkSeg, thinkSlice, thinkPeek, thinkGrowing,
         turnProcessRows, turnBodyRows, turnOpen,
         toolBrief, prettyIo, liveOutput, groupRunning,
         clusterOpen, toggleCluster, clusterTools, clusterRunning, clusterHasError, clusterProcCount,
         clusterAutoOpen, clusterLiveRows, clusterTitle, clusterLiveText, clusterHeadKind,
         groupHasBar,
         tailNotesTag, tailNotesPeek, tailNotesGrowing, tailOpen, toggleTail,
         groupHasError, toolSearchQueries, retrievalLineTitle,
         fanHasBoard, fanHead, fanTitle, fanRowState, fanBudget, fanRun, runBoardText,
         fmtWindow, externalOrigin, sourceName, fmtSourceScore, scoreTitle,
         fmtMsgTime, fmtSize, errorBrief, histItemTitle, histItemDigest, verLocal, canSwitchPrev, canSwitchNext, verLabel,
         pasteTitle, pasteSub, isPastedText,
         THINK_LEVEL_ON, levelLabel, stopTick, agentBadgeOf, isDispatchTool,
         usageCtxTokens, usageTip, oneLine, toolCardOpen, toggleToolCard, spawnAnchorOf, openDispatchCard } from '../chat/projections'
import { useChatChrome } from '../chat/useChatChrome'
import { useCommandPanels } from '../chat/useCommandPanels'
import { useChatActions } from '../chat/useChatActions'
import { subscribeMemberLive } from '../api'
import { usePlanPanels } from '../chat/usePlanPanels'
import { useDeepAsk } from '../chat/useDeepAsk'

const page = {}
const router = useRouter()
const route = useRoute()
// 团队成员会话视图：从「团队」面板点成员打开，顶部提供返回主会话
const memberViewRoot = computed(() => { const v = route.query.teamRoot; return Array.isArray(v) ? v[0] : (v || '') })
const memberViewName = computed(() => { const v = route.query.memberName; return Array.isArray(v) ? v[0] : (v || '') })
// 输入框与编辑卡的 DOM ref 由布局层持有：命令面板与两个域指向同一份元素。
const composerRef = ref(null)
const editingRef = ref(null)
const goalArmed = ref(false)
const goalStarting = ref(false)
const CLUSTER_ICONS = { search: SearchOutlined, web: GlobalOutlined, tools: ApiOutlined, thinking: BulbOutlined }
// 具名引用：命名空间 import 会把整个图标库与 api 模块拖进本页分包。
const api = { debugRetrieval, deleteMessageGroup, submitFeedback: apiSubmitFeedback,
              addEvalCase, getSessionShare, enableSessionShare, disableSessionShare,
              getSessionWorkspace, getPermissionPresets, setSessionPermission,
              openProjectFolder, updateProjectItem, createSkill }
const icons = { CompressOutlined, AimOutlined, FileTextOutlined, PlusOutlined,
                PaperClipOutlined, BookOutlined, HistoryOutlined, ThunderboltOutlined,
                DatabaseOutlined, RobotOutlined, AppstoreOutlined }

// 浏览器能力只读镜像：SSE 事件 browser_snapshot → onBrowserSnapshot 回调（引擎经 hooks 转来）。
// 同一轮最多保留最近 20 条、最新在前；turn_start / 切会话时清空旧快照。
// 浏览器快照栈已抽到 useBrowserSnapshots.js
const { browserSnapshots, clearBrowserSnapshots, onBrowserSnapshot } = useBrowserSnapshots()

// 右栏宽度拖拽已抽到 usePanelResize.js（chat2Ref 绑到 .chat2，startPanelResize 绑拖拽热区）。
const { chat2Ref, rpWidth, startPanelResize } = usePanelResize()

// 引擎先装配：所有视图域的 getter 都能安全读取；视图回调经 page 惰性取值。
const engine = useChatEngine({
  chatPath: '/chat',
  planModeEnabled: true,
  onBrowserSnapshot,
  onTurnStart: () => clearBrowserSnapshots(),
  scrollFollow: () => nextTick(() => { page.chrome?.updateTailSpacer?.(); page.chrome?.scroll?.() }),
  scrollForce: () => nextTick(() => { page.chrome?.updateTailSpacer?.(); page.chrome?.scrollForce?.() }),
  scrollSoft: () => page.chrome?.scroll?.(),
  revealApproval: id => page.chrome?.revealApprovalCard?.(id),
  closePanels: () => page.panels?.closeAllPanels?.(),
  focusInput: () => page.chrome?.focusInput?.()
})

const {
  // 输入与发送
  text, canSend, send, stop, steerQueue, sendSteer, editSteer, dropSteer, moveSteer,
  streamAnswer, resolveApproval, askSubmitAll, ignoreAsk,
  // 提问卡的选择状态口径全在引擎（单选/多选同一形状）：面板只读这些，不自己数选中态
  pickAskOption, commitAskCustom, askOptionPicked, askCustomPicked, askQuestionAnswered,
  askAnsweredCount, askNextUnanswered, askAutoSubmittable, askHintText,
  // 计划模式（人在回路）：斜杠命令开关 + 计划卡裁决动作
  planModeOn, setPlanMode, submitPlanApproval, revisePlanInPlace, continuePlanConversation,
  pendingPlan, curPlanCard, planExpired, planCountdownText,
  // 批准后调整（A/B）与执行进度/调整记录：判据与取数都在引擎，页面只接线
  adjustPlanAfterApproval, planExecProgressOf, planAdjustsOfMsg,
  // 工具审批卡倒计时（真实剩余秒数；到点按钮锁住）
  approvalLeftSecOf,
  // 目标模式：平台总闸 goalModeOn + 会话级目标态 + 全部动作。
  // 摘要条与就地编辑的词汇、显隐、可用性都在引擎侧（与移动壳共用一份），本页只画形状
  sessionGoal, goalModeOn, startGoal, runGoalAction,
  goalTerminal, goalBarVisible, goalStateLabel, goalStateClass, goalRoundLine,
  goalBarTitle, goalBarMeta, goalBarError,
  goalCanPause, goalCanResume, goalCanEdit, goalCanCancel, goalCanClearRecord,
  goalPreempted, goalVersionLabel, goalProgressPercent,
  goalTerminalCard, goalTerminalTitle, goalTerminalNote, goalTurnStreaming,
  goalDelivery, deliveryPackages, deliveryCardOfMessage, downloadDeliveryZip, artifactPendingOf, freshDeliveryOf,
  goalEditing, goalEdit, goalEditBusy, goalEditSaveable,
  startGoalInlineEdit, saveGoalEdit,
  // 思考能力 / 档位（悬浮面板消费）
  thinkCapsOf, reasoningLevelsOf, deepOnOf, levelOptionsOf, currentLevelOf, setThinkLevel, deepThinkOn,
  // 技能
  skillList, pickedSkills, toggleSkill, skillAvaStyle, loadSkills, skillDoc,
  // 智能体
  agentList, hasDefaultAgent, currentAgentId, isAdmin, agentPickerOpen,
  currentAgentName, currentAgent, pickAgent, modelLabelOf, modelSwitchInfo, loadAgents,
  // 会话与消息
  currentSessionId, loading, messages, currentSessionTitle,
  // 模型
  currentOverrideModel, userDefaultModel, modelIndex, effectiveModel, effectiveModelLabel, effectiveModelIcon,
  effectiveModelProvider, debugEntryVisible, debugDisplayVisible, showRelatedSuggestions, effectiveModelIssue,
  // 检索 / 用量 / 来源
  lastAi, groupedSources, ctxTokens, ctxCapData, ctxRingDash,
  ctxRingLevel, panelAi, sessionArtifacts, sessionTodos,
  // 清单的计数与显隐判据吃引擎那一份，PC 与移动壳共用。
  todoSummary, todoBarVisible,
  // 这一会话的历史有没有取回来（与「取回来了、就是空的」是两条判据，见引擎 historyError 注释）
  historyError, retryHistory,
  // 沙盒目录同样整族吃引擎：取数、判据、折树、下载与存为产物
  sandboxFiles, sandboxRows, sandboxCount, sandboxBytes, sandboxRest,
  sandboxErr, sandboxLocalOffline, sandboxLoading, sandboxTaken, sessionSandboxActive,
  loadSandboxFiles, toggleSandboxDir, sandboxDownload, sandboxTake, sbSize,
  // 后台进程那一族同样吃引擎（本机执行器 + 沙盒两路）：本页只留这张卡的摆法
  procs, procNotices, procErr, procLoading, procCardVisible, killingTag, killingAll,
  loadSandboxProcs, procAge, killProc, killAllProcs,
  // 图片与附件（输入框里的 chip 删除走编辑区 → onKeys 剪枝；顶替态没有编辑区，由待发条按下标移除）
  pendingImages, removePendingImage, MAX_FILES, pendingFiles, removePendingFile, hasUploadingFile, addFiles,
  takePastedFiles, takePastedText, attachUploaded, attachImage,
  // @ 引用（面板与筛选词在「+」面板一节，高亮下标用本页自己的 addHi）
  mentionTab, mentionQuery, mentionLoading, mentionKbs, mentionDocs, pendingMentions,
  isMentioned, toggleMention, removeMention, loadMentionCandidates, MAX_MENTIONS,
  mentionDocStatus, mentionKbFiltered, mentionAgents, mentionAgentFiltered, mentionDocFiltered,
  // # 历史引用（上限常量在编辑卡里同样要用到）
  pendingHistoryRefs, histPool, isHistPicked, toggleHistoryRef, removeHistoryRef, MAX_HISTORY_REFS,
  // 会话生命周期 / 重新生成 / 分支 / 初始化
  switchSession, createNewSession, workspaceEpoch, regenerate, variantSwitching, switchBranch, ready,
  // 手动压缩上下文（/compact：命令面板、分隔线与摘要弹窗）
  compacting, manualCompacting, compactNotice, compactContext,
  compactSummaryOpen, compactSummary, compactSummaryLoading, openCompactSummary, recompactWith,
  // 「已压缩上下文」分隔线的判据（引擎里，与 H5 共用）：持久那份按消息行标记，另一档补本轮压完到落库之间
  compactDividerAt, liveCompactAt
} = engine

// 切会话即清空上一会话的浏览器快照（新会话/重新进入都不该看到上一轮的浏览器动作）
watch(currentSessionId, () => clearBrowserSnapshots())

// 目标条的进度读数：轮次之外再给「已跑多久 / 预算占多少」，让条子一屏读完
// 目标条文本口径已抽到 useGoalBarText.js
const { goalElapsedText, goalRoundsCount, goalNextVersionLabel } = useGoalBarText({ sessionGoal })

// 顶栏状态胶囊 + 右栏分段（见 ../chat/useHeadStatus）
const {
  processSteps, processFailCount, headStatusClass, headStatusText, headStatusTitle,
  railTab, fileTabCount, filesTabEmpty, processMsgIndex, headStatusClick
} = useHeadStatus({
  processMsg: () => processMsg.value, roundSteps,
  panelOpen: () => panelOpen.value, mPanelOpen: () => mPanelOpen.value,
  isNarrow: () => isNarrow.value, togglePanel: (...a) => togglePanel(...a),
  deliveryPackages, sandboxCount, procs, sandboxErr, sandboxLoading, procCardVisible, messages
})


// 成员轮直播：订阅/收台逻辑已抽到 useMemberLiveView.js（与主问答同一条事件契约，只换出口）。
const { memberLive, memberLiveStatus } = useMemberLiveView({
  messages, currentSessionId, memberViewRoot,
  switchSession: sid => engine.switchSession(sid),
  scroll: () => page.chrome?.scroll?.(),
  subscribeApi: (sid, opts) => subscribeMemberLive(sid, opts),
  notify: message,
})

// 运行设置（见 ../chat/useRunSettings）
const {
  runSettingsModel, runSettingsModelLabel, runSettingsCaps, runSettingsLevels,
  runSettingsLevel, runSettingsHint, runSettingsWindow, isRunModelDefault,
  setRunModelDefault, ctxPctText, runSettingsUsage, kbScopeText
} = useRunSettings({
  currentOverrideModel, effectiveModel, effectiveModelLabel, userDefaultModel,
  modelIndex, pendingMentions, ctxCapData,
  modelLabelOf, thinkCapsOf, levelOptionsOf, currentLevelOf, fmtWindow, fmtTokens,
  setUserPreference, message
})

const ask = q => { text.value = q; nextTick(send) }

// 共享上下文：引擎值一律惰性 getter，动作/工具直接传，跨域引用经 page 惰性取值。
const g = {
  notify: message, message,
  router, route, sessionStore, loadSessions,
  nextTick, now: () => Date.now(),
  setTimeout, clearTimeout, setInterval, clearInterval,
  win: window, doc: document, window, document, storage: localStorage,
  isNarrow, isCoarse,
  api, apiSubmitFeedback, updateProjectItem,
  copyText, exportAnswerMd, exportSessionMarkdown, parseDeepCommand,
  resolveImg, openImages, previewUrl, openSource, hoveredRef,
  fmtSize, fmtWindow, oneLine, histItemDigest, histItemTitle, isPastedText,
  pasteTitle, pasteSub, histChipLabel: histItemTitle,
  clusterHeadKind, spawnAnchorOf, openDispatchCard, stopTick,
  buildQaAnchors, buildSessionPlans, planStepCount, planStateText,
  CLUSTER_ICONS, BulbOutlined, icons,
  MAX_FILES, MAX_MENTIONS, MAX_HISTORY_REFS,
  useThinkPanel, useChatScroll, useChatPanels, useSourceFocus,
  useChatSearch, useSessionShare, refreshSetupGuide,
  messages: () => messages.value,
  loading: () => loading.value,
  text: () => text.value,
  currentSessionId: () => currentSessionId.value,
  currentSessionTitle: () => currentSessionTitle.value,
  currentLevelOf, currentOverrideModel: () => currentOverrideModel.value,
  effectiveModel: () => effectiveModel.value,
  lastAi: () => lastAi.value,
  modelIndex: () => modelIndex.value,
  goalBarVisible: () => goalBarVisible.value,
  goalModeOn: () => goalModeOn.value,
  goalTurnStreaming: () => goalTurnStreaming.value,
  sessionGoal: () => sessionGoal.value,
  goalTerminal: () => goalTerminal.value,
  goalEditing: () => goalEditing.value,
  goalEditBusy: () => goalEditBusy.value,
  planModeOn: () => planModeOn.value,
  pendingPlan: () => pendingPlan.value,
  curPlanCard: () => curPlanCard.value,
  planExpired: () => planExpired.value,
  pendingFiles: () => pendingFiles.value,
  pendingImages: () => pendingImages.value,
  pendingMentions: () => pendingMentions.value,
  pickedSkills: () => pickedSkills.value,
  pendingHistoryRefs: () => pendingHistoryRefs.value,
  histPool: () => histPool.value,
  mentionQuery: () => mentionQuery.value,
  mentionTab: () => mentionTab.value,
  mentionKbs: () => mentionKbs.value,
  mentionDocs: () => mentionDocs.value,
  mentionAgents: () => mentionAgents.value,
  mentionKbFiltered: () => mentionKbFiltered.value,
  mentionDocFiltered: () => mentionDocFiltered.value,
  mentionAgentFiltered: () => mentionAgentFiltered.value,
  agentList: () => agentList.value,
  agentPickerOpen: () => agentPickerOpen.value,
  skillList: () => skillList.value,
  compactContext: () => compactContext.value,
  askAutoSubmittable: () => askAutoSubmittable.value,
  askHintText: () => askHintText.value,
  askNextUnanswered: () => askNextUnanswered.value,
  askQuestionAnswered: () => askQuestionAnswered.value,
  pickedSkillsRef: pickedSkills,
  // 引擎动作
  send, stop, streamAnswer, moveSteer, setPlanMode, setThinkLevel,
  addFiles, attachImage, attachUploaded, takePastedFiles, takePastedText,
  toggleMention, toggleSkill, toggleHistoryRef, isMentioned, isHistPicked,
  loadAgents, loadSkills, loadMentionCandidates, createNewSession,
  submitPlanApproval, revisePlanInPlace, continuePlanConversation,
  adjustPlanAfterApproval, planExecProgressOf, planAdjustsOfMsg,
  askSubmitAll, commitAskCustom, pickAskOption, ignoreAsk,
  startGoal, saveGoalEdit, startGoalInlineEdit, recompactWith, ready,
  pickAgent, switchBranch, regenerate, retryHistory
}

/** 执行详情：工具/产物按 stepId 归到步骤上；没有归属数据时卡片只报状态。 */
const planExecDetailOf = m => planExecDetail(planExecProgressOf(m), m && m.toolCalls, (m && m.artifacts) || [])

/** 帽体里有没有把某一步摊开在读：有则顶部渐隐要撤——贴顶吸附的行头正落在 24px 渐隐带里，
 *  不撤会被整行抹成透明（见 .tl-cluster-live.reading） */
const clusterReading = seg => clusterLiveRows(seg).some(r => r.t && r.t._open)

// ---- 编辑重发：先建，命令面板要复用它的编辑态列表 ----
const {
  editingIdx, editingMessageId, editingText, editingBusy,
  editImgs, editAtts, editMentions, editHistRefs,
  editSkills, editAttachInput, setEditingRef, setEditAttachInputRef,
  autosizeEditing, probeEditAtts, editMessage, cancelEdit,
  pickEditFiles, onEditAttachChange, editAddCount, toggleEditSkill,
  toggleEditHistoryRef, toggleEditMention, onEditPaste, removeEditImage,
  removeEditAtt, removeEditMention, editHistPool, addEditSkill,
  removeEditSkill, addEditHistRef, removeEditHistRef, previewEditImage,
  onEditKeydown, confirmEdit
} = (page.editResend = useEditResend({
    messages: () => messages.value,
    setMessages: list => { messages.value = list },
    loading: () => loading.value,
    histPool: () => histPool.value,
    addOpen: () => page.panels?.addOpen.value ?? false,
    addTarget: () => page.panels?.addTarget.value ?? '',
    addOnEdit: () => page.panels?.addOnEdit.value ?? false,
    closeAddPanel: () => page.panels?.closeAddPanel(),
    onPanelNavKeydown: e => page.panels?.onPanelNavKeydown(e),
    maxMentions: () => MAX_MENTIONS,
    maxHistoryRefs: () => MAX_HISTORY_REFS,
    resend: (...args) => streamAnswer(...args),
    notify: message, editingRef,
    isPastedText, histItemDigest, splitAtts, resolveImg, openImages,
    addFiles: (files, into) => addFiles(files, into),
    takePastedFiles: (e, into) => takePastedFiles(e, into),
    takePastedText: (e, into) => takePastedText(e, into)
  }))

// ---- 命令面板：先于页面壳层，壳层要写它的开合与高亮 ref ----
const {
  panelTriggerPos, panelTriggerCh, panelAnyOpen, closeAllPanels,
  panelListSize, getPanelHi, setPanelHi, slashListEl,
  histListEl, addListEl, panelListEl, scrollPanelHiIntoView,
  movePanelHi, confirmPanelHi, stripTriggerToken, composerCaret,
  focusPanelOwner, syncPanelQuery, onPanelNavKeydown, slashOpen,
  slashQuery, slashHi, slashCommands, openSlashPanel,
  closeSlashPanel, slashQueryNorm, slashAliasesOf, slashArgPending,
  slashArgsOf, slashFiltered,
  runSlashCommand, histOpen, histQuery, histHi,
  histQueryNorm, histListOf, histCandidates,
  histChipLabel, pickHistoryRefByClick, openHistPanel, closeHistPanel,
  addOpen, addTab, addHi, addSearchEl,
  addHistQuery, addCmdQuery, addTarget, addOnEdit,
  EDIT_ADD_MOUNT, addStore, addRootRows, addHistPool,
  addHistCandidates, isAddSkillOn, toggleAddSkill, isAddMentioned,
  toggleAddMention, isAddHistPicked, toggleAddHistoryRef, ADD_TITLES,
  REF_SUB_TABS, addSearchable, addQuery, addSearchHint,
  addFootHint, addSkillList, addCmdList, addRefList,
  addListSize, closeAddPanel, ensureRefCandidates, focusAddSearch,
  addFromRoot, backToRoot, switchAddTab, switchRefTab,
  confirmRefRow, confirmAddHi, openAddPanel, toggleAddPanel,
  toggleEditAddPanel, runCmdFromAddPanel
} = (page.panels = useCommandPanels({
    ...g,
    composerRef, editingRef,
    mentionQuery, mentionTab, histPool,
    pendingFiles, pendingImages, pendingMentions, pickedSkills, pendingHistoryRefs,
    editAtts: page.editResend.editAtts,
    editImgs: page.editResend.editImgs,
    editMentions: page.editResend.editMentions,
    editSkills: page.editResend.editSkills,
    editHistRefs: page.editResend.editHistRefs,
    editHistPool: page.editResend.editHistPool,
    editingIdx: page.editResend.editingIdx,
    toggleEditMention: page.editResend.toggleEditMention,
    toggleEditSkill: page.editResend.toggleEditSkill,
    toggleEditHistoryRef: page.editResend.toggleEditHistoryRef,
    pickEditFiles: page.editResend.pickEditFiles,
    focusInput: () => page.chrome?.focusInput?.(),
    pickAttachments: () => page.chrome?.pickAttachments?.(),
    armGoal: (...a) => page.actions?.armGoal?.(...a),
    startGoalNow: (...a) => page.deepask?.startGoalNow?.(...a),
    goalEntryVisible: () => page.deepask?.goalEntryVisible.value ?? false,
    goalArmed: () => goalArmed.value,
    notify: message
  }))

// ---- 页面壳层：思考/滚动/浮层/来源联动四组 hook 与 chip 同步 ----
const {
  clusterIconOf, questionFor, inlineKeys, box,
  thinkHoverRef, thinkPanelPos, thinkPanelEl, mThinkOpen,
  thinkHoverModel, thinkPanelVisible, toggleThinkSheet, onDocPointerDown,
  thinkPanelCaps, thinkPanelLevelOptions, thinkPanelCurrentLevel, thinkPanelStrengthText,
  thinkPanelFoot, pillLevelRef, pillLevelBadge, pillLevelTitle,
  thinkPanelCtxText, onModelOptionHover, scheduleThinkHide, onThinkPanelEnter,
  onThinkPanelLeave, onModelSelectOpenChange, goManageAgents, stickToBottom,
  tailSpacer, onMessagesScroll, scroll, scrollForce,
  updateTailSpacer, railVis, railHoverIdx, railPopStyle,
  railHoverAnchor, showAnchor, gotoAnchor, railActiveIdx,
  updateRailActive, panelStoredRaw, panelStored, panelOpen,
  mPanelOpen, togglePanel, processMsg, focusSpawn,
  focusHitl, revealApprovalCard, eventsOpen, eventsTurn,
  eventsMsg, eventsQuestion, eventsTitle, openEvents,
  srcOpenOf, toggleSrc, ctxCapOpen, ctxCapPos,
  ctxCapEl, showCtxCap, hideCtxCap, onCtxCapEnter,
  onCtxCapLeave, sessionPlans, jumpToPlan, openPlanFromRail,
  disclaimerVisible, DISCLAIMER_TEXT, hoverSource, unhoverSource,
  locateSource, jumpToPlanRow, feedbackVisible, feedbackSubmitting,
  feedbackText, feedbackTarget, openFeedback, doSubmitFeedback,
  escArmed, escArmTimer, armEsc, disarmEsc,
  stopNow, composerPlaceholder, onGlobalKeydown, onGlobalPaste,
  syncScrollbarGutter, onWindowResize, onOrientationChange, focusInput,
  openPreviewFromMsg, onDropFiles, onComposerPaste, pasteView,
  openPasteView, attachInput, pickAttachments, onAttachChange,
  openMentionPanel, OVERLAY_ROOT_CLASSES, hitsOverlay, onDocClickForMention,
  qaAnchors, anchorQ, anchorA, GOAL_KEY,
  fileKeyOf, imgKeyOf, menKeyOf, skillKeyOf,
  histKeyOf, MENTION_TIP, desiredKeys, chipOf,
  onKeys, syncChips, onChipClick, restoreWenquChips,
  todoOpen, srcCardOpen, goalOpen
} = (page.chrome = useChatChrome({
    ...g, composerRef,
    text, pendingFiles, pendingImages, pendingMentions, pickedSkills,
    pendingHistoryRefs, histPool, agentPickerOpen,
    goalArmed, goalStarting, mentionQuery,
    slashOpen: page.panels.slashOpen,
    histOpen: page.panels.histOpen,
    addOpen: page.panels.addOpen,
    addTab: page.panels.addTab,
    addTarget: page.panels.addTarget,
    addHi: page.panels.addHi,
    addFromRoot: page.panels.addFromRoot,
    closeAddPanel: page.panels.closeAddPanel,
    closeAllPanels: page.panels.closeAllPanels,
    ensureRefCandidates: page.panels.ensureRefCandidates,
    openPlanViewer: (...a) => page.plan?.openPlanViewer?.(...a),
    openSlashPanel: page.panels.openSlashPanel,
    openHistPanel: page.panels.openHistPanel,
    notify: message
  }))

// ---- 页面动作：发送、插话、查找、分享、复制、固化与调试 ----
const {
  inlineSlash, sendNow, steerDragIdx, steerDragOver,
  onSteerDragStart, onSteerDragOver, onSteerDragEnd, onSteerDrop,
  steerExtras, searchOpen, searchQuery, searchPos,
  searchInputRef, matchedIdxs, searchPosShown, openSearch,
  closeSearch, gotoMatch, paintSearchHighlight, scrollToMatch,
  onSearchHotkey, onDocEscKeydown, shareVisible, shareLoading,
  shareInfo, shareUrl, loadShare, enableShare,
  stopShare, copyShareLink, shareActive, openShare,
  consumeShareOpen, doEnableShare, regenerateShareLink, doDisableShare,
  openSharedPage, onInputKeydown, hitsNote, copyAnswer,
  copyUserMessage, onMoreAction, addToEval, solidifyOpen,
  solidifyBusy, solidifyForm, gotoSkillsPage, solidifySlug,
  submitSolidify, exportRound, deleteRound,
  debugVisible, debugLoading, debugQuestion, debugResult,
  openDebug, runDebug, debugStages
} = (page.actions = useChatActions({
    ...g,
    box: page.chrome.box,
    focusInput: page.chrome.focusInput,
    openEvents: page.chrome.openEvents,
    openMentionPanel: page.chrome.openMentionPanel,
    hitsOverlay: page.chrome.hitsOverlay,
    closeAddPanel: page.panels.closeAddPanel,
    closeAllPanels: page.panels.closeAllPanels,
    backToRoot: page.panels.backToRoot,
    composerCaret: page.panels.composerCaret,
    confirmAddHi: page.panels.confirmAddHi,
    confirmPanelHi: page.panels.confirmPanelHi,
    focusPanelOwner: page.panels.focusPanelOwner,
    movePanelHi: page.panels.movePanelHi,
    openSlashPanel: page.panels.openSlashPanel,
    openHistPanel: page.panels.openHistPanel,
    onPanelNavKeydown: page.panels.onPanelNavKeydown,
    setPanelTrigger: (pos, ch) => { panelTriggerPos.value = pos; panelTriggerCh.value = ch },
    addOpen: () => page.panels.addOpen.value,
    addTab: () => page.panels.addTab.value,
    addHi: () => page.panels.addHi.value,
    addFromRoot: () => page.panels.addFromRoot.value,
    slashOpen: () => page.panels.slashOpen.value,
    histOpen: () => page.panels.histOpen.value,
    goalArmed: () => goalArmed.value,
    goalStarting: () => goalStarting.value,
    setGoalArmed: v => { goalArmed.value = !!v },
    setText: v => { text.value = v },
    startGoalNow: (...a) => page.deepask?.startGoalNow?.(...a),
    foreignWorkspace: () => page.workspace?.foreignWorkspace.value ?? false
  }))

// ---- 目标条 / 交付抽屉 ----
const {
  goalCancelArmed, goalViewerOpen, deliveryOpen, deliveryTarget,
  armGoalCancel, confirmGoalCancel, openGoalViewer, openDelivery,
  onGoalViewerAction, jumpToGoalTurn
} = (page.goalPanels = useGoalPanels({
    goal: () => sessionGoal.value,
    currentSessionId,
    messages: () => messages.value,
    runGoalAction: a => runGoalAction(a),
    startGoalInlineEdit: () => startGoalInlineEdit(),
    scrollToRow: i => {
      const el = box.value?.querySelector(`[data-row-index="${i}"]`)
      el?.scrollIntoView({ block: 'start', behavior: 'smooth' })
    }
  }))

// 目标条折叠：收起时只留身份行（原文/版本/状态），进度与动作键藏起来；
// 视图导航与目标条折叠已抽到 useChatPageView / useGoalBarCollapse（此处依赖均已就绪）
const { backToRootSession, openTeamPanel } = useChatPageView({
  router, memberViewRoot, railTab, isNarrow, mPanelOpen, panelOpen
})
const { toggleGoalBar } = useGoalBarCollapse({
  goalOpen, goalCancelArmed, goalEditing, sessionGoal
})

// ---- 会话工作区与权限档位 ----
const {
  sessionWorkspace, permissionPresets, sessionPermission, permissionSaving,
  dangerOpen, dangerAck, dangerSaving, workspaceRefreshing,
  effectivePermission, permissionLabel, wsTitle, foreignWorkspace,
  workspaceSetupHint, skillDocEntry, loadPermissionPresets, applyPermission,
  pickPermission, confirmDanger, loadSessionWorkspace, goBindWorkspace,
  openLocalFolder, refreshSessionWorkspace
} = (page.workspace = useSessionWorkspace({
    currentSessionId,
    workspaceEpoch,
    agent: () => currentAgent.value,
    skillList: () => skillList.value,
    skillDoc: () => skillDoc.value,
    localExecutorId: () => localExecutorId(),
    api: { getSessionWorkspace, getPermissionPresets, setSessionPermission, openProjectFolder },
    notify: message,
    router
  }))

// ---- 计划视图与批准后调整 ----
const {
  planOpen, planHeadText, mentionNames, historyRefPreview,
  planViewer, openPlanViewer, closePlanViewer, onViewerApprove,
  onViewerRevise, onViewerSupersede, onViewerLocate, planMsgOfViewer,
  planTaskTitleOf, planViewerMsg, planViewerProgress, onViewerStop,
  planViewerAdjusts, onViewerAdjust, onViewerLocateSource, locatePlanCardById,
  planAdjust, openPlanAdjust, closePlanAdjust, submitPlanAdjust,
  locateReplanned, locatePlanSource, planEditPlaceholder, onPlanPanelKeydown
} = (page.plan = usePlanPanels({
    messages: () => messages.value,
    currentSessionId, currentSessionTitle, curPlanCard, planExpired,
    pendingPlan, pendingMentions, isNarrow,
    sessionPlans: page.chrome.sessionPlans,
    planStateText, planExecProgressOf, planAdjustsOfMsg,
    submitPlanApproval, revisePlanInPlace, continuePlanConversation,
    adjustPlanAfterApproval,
    jumpToPlan: page.chrome.jumpToPlan,
    stopNow: page.chrome.stopNow,
    notify: message
  }))

// ---- 深度执行、提问面板与初始化 ----
const {
  goalEntryVisible, startGoalNow, onGoalEditKeydown, pendingAsk,
  askQueueCount, askPage, askCustomRef, askNow,
  askHi, askCustomPlaceholder, askTimer, curAsk,
  askPanelHint, askRemaining, askExpired, askCountdownText,
  askOptionParts, focusAskRow, onPick, commitCustomAt,
  advanceAfterSelect, onAskCustomKeydown, onAskPanelKeydown, csumInstrOpen,
  csumInstr, applyRecompact, initialize
} = (page.deepask = useDeepAsk({
    ...g,
    text, goalEditing, goalStarting, goalEditBusy,
    editingIdx: page.editResend.editingIdx,
    editingMessageId: page.editResend.editingMessageId,
    cancelEdit: page.editResend.cancelEdit,
    searchOpen: page.actions.searchOpen,
    searchQuery: page.actions.searchQuery,
    searchPos: page.actions.searchPos,
    paintSearchHighlight: page.actions.paintSearchHighlight,
    scrollToMatch: page.actions.scrollToMatch,
    onGlobalKeydown: page.chrome.onGlobalKeydown,
    onGlobalPaste: page.chrome.onGlobalPaste,
    onDocPointerDown: page.chrome.onDocPointerDown,
    onWindowResize: page.chrome.onWindowResize,
    onOrientationChange: page.chrome.onOrientationChange,
    syncScrollbarGutter: page.chrome.syncScrollbarGutter,
    hideThinkPanel: page.chrome.hideThinkPanel,
    disarmEsc: page.chrome.disarmEsc,
    focusInput: page.chrome.focusInput,
    loadPermissionPresets: page.workspace.loadPermissionPresets,
    notify: message
  }))

// ===== 布局层装配：页面只保留组件注册、共享 DOM ref 与各视图域的接线 =====
// 消息流子组件（chatpage/ChatMessageList.vue）的上下文：一个 reactive 里装它用到的全部变量与动作。
// 用 provide 而不是 props：键有 150 多个，逐个 props 声明既冗长又容易漏；reactive 还会自动解包 ref，
// 模板里读写与写在父页时完全一致（含 v-model 那几处）。
// 聊天页子组件共享的上下文：键清单与取数在 ./chatpage/chatCtx.js（400+ 键，摊在本文件会顶过阈值）。
// reactive 会自动解包 ref，子组件模板里读写与写在父页时完全一致（含 v-model 那几处）。
const chatCtx = reactive(pickChatCtx({
  ADD_TITLES, DISCLAIMER_TEXT, EDIT_ADD_MOUNT, REF_SUB_TABS, addCmdList, addFootHint, addHi, addHistCandidates, addHistPool, addOnEdit, addOpen, addQuery, addRefList, addRootRows, addSearchHint, addSearchable, addTab, agentBadgeOf, agentList, agentPickerOpen, applyRecompact, approvalArgsText, approvalLeftSecOf, armGoalCancel, artifactPendingOf, ask, askAnsweredCount, askCountdownText, askCustomPicked, askCustomPlaceholder, askExpired, askHi, askOptionParts, askOptionPicked, askPage, askPanelHint,
  askQueueCount, askSubmitAll, autosizeEditing, backToRoot, backToRootSession, browserSnapshots, canSend, canSwitchNext, canSwitchPrev, cancelEdit, chatDone, chipLabelOf, chipOf, closeAddPanel, closeHistPanel, closeSearch, closeSlashPanel, clusterHasError, clusterIconOf, clusterLiveRows, clusterLiveText, clusterOpen, clusterReading, clusterRunning, clusterTitle, commitCustomAt, compactContext, compactDividerAt, compactNotice, compactSummary, compactSummaryLoading, compactSummaryOpen, compacting, composerPlaceholder, confirmDanger, confirmEdit,
  confirmGoalCancel, confirmRefRow, continuePlanConversation, copyShareLink, copyText, copyUserMessage, csumInstr, csumInstrOpen, ctxCapData, ctxCapOpen, ctxCapPos, ctxPctText, ctxRingDash, ctxRingLevel, ctxTokens, curAsk, curPlanCard, currentAgent, currentAgentId, currentAgentName, currentOverrideModel, currentSessionId, currentSessionTitle, dangerAck, dangerOpen, dangerSaving, debugDisplayVisible, debugEntryVisible, debugLoading, debugQuestion, debugResult, debugStages, debugVisible, deepThinkOn, deliveryCardOfMessage, deliveryOpen,
  deliveryPackages, deliveryTarget, disclaimerVisible, doDisableShare, doEnableShare, doSubmitFeedback, downloadDeliveryZip, dropSteer, editAddCount, editAtts, editHistRefs, editImgs, editMentions, editMessage, editSkills, editSteer, editingBusy, editingIdx, editingText, effectiveModel, effectiveModelIssue, effectiveModelLabel, effectivePermission, errorBrief, escArmed, eventsMsg, eventsOpen, eventsQuestion, eventsTitle, eventsTurn, executedCount, fanBudget, fanHasBoard, fanHead, fanRowState, fanRun,
  fanTitle, feedbackSubmitting, feedbackText, feedbackVisible, fileTabCount, filesTabEmpty, fmtMsgTime, fmtSize, fmtSourceScore, fmtTokens, fmtWindow, focusHitl, focusSpawn, freshDeliveryOf, goBindWorkspace, goManageAgents, goalBarError, goalBarMeta, goalBarTitle, goalBarVisible, goalCanCancel, goalCanEdit, goalCanPause, goalCanResume, goalCancelArmed, goalDelivery, goalEdit, goalEditBusy, goalEditSaveable, goalEditing, goalElapsedText, goalNextVersionLabel, goalOpen, goalPreempted, goalProgressPercent, goalRoundLine,
  goalRoundsCount, goalStateClass, goalStateLabel, goalTerminalCard, goalTerminalNote, goalTerminalTitle, goalTurnStreaming, goalVersionLabel, goalViewerOpen, gotoMatch, groupHasBar, groupHasError, groupRunning, groupedSources, guideSnoozed, hasDefaultAgent, headStatusClass, headStatusClick, headStatusText, headStatusTitle, hideCtxCap, histCandidates, histHi, histItemTitle, histOpen, histPool, histQuery, historyError, historyRefPreview, hitsNote, hoverSource, hoveredRef, ignoreAsk, isAddHistPicked, isAddMentioned, isAddSkillOn,
  isAdmin, isCoarse, isDispatchTool, isHistPicked, isNarrow, isRunModelDefault, jumpToGoalTurn, kbScopeText, killAllProcs, killProc, killingAll, killingTag, liveCompactAt, liveOutput, loadSandboxFiles, loadSandboxProcs, loading, locatePlanSource, locateReplanned, locateSource, mPanelOpen, manualCompacting, matchedIdxs, memberLiveStatus, memberViewName, memberViewRoot, mentionAgents, mentionDocStatus, mentionDocs, mentionKbs, mentionLoading, mentionNames, mentionTab, messages, modelLabelOf, modelSwitchInfo,
  onAskCustomKeydown, onAskPanelKeydown, onAttachChange, onChipClick, onComposerPaste, onCtxCapEnter, onCtxCapLeave, onDropFiles, onEditAttachChange, onEditKeydown, onEditPaste, onGoalEditKeydown, onGoalViewerAction, onInputKeydown, onMessagesScroll, onMoreAction, onPanelNavKeydown, onPick, onPlanPanelKeydown, onSteerDragEnd, onSteerDragOver, onSteerDragStart, onSteerDrop, onThinkPanelEnter, onThinkPanelLeave, openCompactSummary, openDelivery, openEvents, openFeedback, openGoalViewer, openImages, openLocalFolder, openPasteView, openPlanAdjust, openPlanFromRail, openPlanViewer,
  openSearch, openShare, openSharedPage, openSource, openTeamPanel, panelAi, panelOpen, pasteSub, pasteTitle, pasteView, pendingAnswerText, pendingAsk, pendingFiles, pendingImages, pendingPlan, permissionLabel, permissionPresets, pickAgent, pickHistoryRefByClick, pickPermission, pickedSkills, pillLevelBadge, pillLevelTitle, planAdjustsOfMsg, planCountdownText, planExecDetailOf, planExecProgressOf, planExpired, planHeadText, planOpen, planStateText, prettyIo, previewEditImage, procAge, procCardVisible, procErr,
  procLoading, procNotices, procOpen, procSlice, processFailCount, processMsg, processMsgIndex, processSteps, procs, questionFor, railTab, ref, refreshSessionWorkspace, regenerate, regenerateShareLink, removeEditAtt, removeEditHistRef, removeEditImage, removeEditMention, removeEditSkill, removePendingFile, removePendingImage, renderMd, resolveApproval, resolveImg, retrievalLineTitle, retryHistory, revisePlanInPlace, rewriteSkipText, router, runBoardText, runCmdFromAddPanel, runDebug, runGoalAction, runSettingsCaps, runSettingsHint,
  runSettingsLevel, runSettingsLevels, runSettingsModel, runSettingsModelLabel, runSettingsUsage, runSettingsWindow, runSlashCommand, sandboxBytes, sandboxCount, sandboxDownload, sandboxErr, sandboxFiles, sandboxLoading, sandboxLocalOffline, sandboxRest, sandboxRows, sandboxTake, sandboxTaken, saveGoalEdit, sbSize, scoreTitle, scrollForce, searchOpen, searchPosShown, searchQuery, sendNow, sendSteer, sessionGoal, sessionPermission, sessionPlans, sessionTodos, sessionWorkspace, setRunModelDefault, setThinkLevel, setupGuide, shareActive,
  shareInfo, shareLoading, shareUrl, shareVisible, showCtxCap, showRelatedSuggestions, skillAvaStyle, skillDoc, skillDocEntry, skillList, slashFiltered, slashHi, slashOpen, slashQuery, solidifyBusy, solidifyForm, solidifyOpen, spawnAnchorOf, srcCardOpen, srcOpenOf, startGoal, startGoalInlineEdit, steerDragIdx, steerExtras, steerQueue, stickToBottom, stopNow, submitPlanApproval, submitSolidify, switchBranch, switchRefTab, syncChips, syncPanelQuery, tailNotesGrowing, tailNotesPeek, tailNotesTag,
  tailOpen, tailSpacer, text, thinkGrowing, thinkHoverModel, thinkPanelCaps, thinkPanelCtxText, thinkPanelCurrentLevel, thinkPanelFoot, thinkPanelLevelOptions, thinkPanelPos, thinkPanelStrengthText, thinkPanelVisible, thinkPeek, thinkSegOpen, thinkSlice, todoBarVisible, todoOpen, todoSummary, toggleAddHistoryRef, toggleAddPanel, toggleCluster, toggleEditAddPanel, toggleGoalBar, togglePanel, toggleProc, toggleSandboxDir, toggleSrc, toggleTail, toggleThinkSeg, toggleThinkSheet, toggleToolCard, toolBrief, toolCardOpen, toolDesc, toolLabel,
  toolSearchQueries, turnBodyRows, turnOpen, turnProcessRows, unhoverSource, usageCtxTokens, usageTip, variantSwitching, verLabel, workspaceRefreshing, workspaceSetupHint, wsTitle, addListEl, addSearchEl, askCustomRef, attachInput,
  box, composerRef, ctxCapEl, histListEl, searchInputRef, setEditAttachInputRef,
  setEditingRef, slashListEl, thinkPanelEl,
}))
provide('chatCtx', chatCtx)

</script>

<style scoped src="./ChatPage.css"></style>

