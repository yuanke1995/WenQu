// 聊天引擎的 transport 边界：问答/审批/计划/目标/沙盒等所有远端调用只从这里进出。
// 目的：useChatEngine 不再直接依赖 api.js——后续 SSE 建流/接流/断线重连也收进本层，
// 引擎只面对「传输门面 + reducer + actions」。
export {
  sendQuestion, resumeChat, getChatRun, newSession, getHistory, deleteSessionApi,
  switchMessageVariant, compactSessionApi, sessionHistorySummaryApi, getConfig, getRuntimeConfig,
  listAvailableAgents, listConsultableAgents, listAvailableSkills, getUserPreference,
  approveToolCall, getToolApproval, answerAgentAsk, ignoreAgentAsk, stopChatTurn, steerChatTurn,
  enqueueSteer, steerQueueList, steerQueued, dropQueued,
  listPendingAsks, listPendingApprovals, resolvePlanApproval, supersedePlanApproval,
  revisePlanApproval, listPendingPlans, updateSessionModelApi, getSessionModelApi,
  listKnowledgeBases, listDocuments, uploadChatAttachment, getSessionTodosApi,
  listSandboxFiles, downloadSandboxFile, sandboxFileToArtifact,
  listSandboxProcs, killSandboxProc,
  startGoalApi, goalActionApi, getPendingGoalApi, getSessionGoalApi, getGoalEventsApi,
  downloadArtifactsZip,
  refreshArtifactSigns as refreshArtifactSignsApi
} from '../api.js'
