// @ts-check
// 分支版本切换 / 停止本轮 / 运行中插话队列的装配（从 useChatEngine 抽出）。
// 三者都是「一轮之外的动作」，共用当前会话与流登记；插话队列的恢复在三个时刻各读一次投影。
import { watch } from 'vue'
import { createBranchSwitcher } from './versionActions.js'
import { createStopTurn } from './stopTurn.js'
import { createSteerQueue } from './steerQueue.js'

export const useSteerAndVersion = ({
  messages, loading, currentSessionId, chatStreams, text, goalTerminal,
  switchVersion, switchSession, cancelReconnect, finishResumedShell,
  switchMessageVariant, stopChatTurn, steerChatTurn, enqueueSteer,
  steerQueueList, steerQueued, dropQueued, attachImage, attachUploaded, notify,
}) => {
  // ==================== 分支版本切换（持久化多版本：编辑重发 + 重新生成刷新后仍可切） ====================
  const { variantSwitching, switchBranch } = createBranchSwitcher({
    messages: () => messages.value,
    isLoading: () => loading.value,
    switchVersion,
    switchApi: switchMessageVariant,
    reload: () => switchSession(currentSessionId.value),
    notifyWarning: msg => notify.warning(msg),
  })

  // 收口口径（工具卡定格/停止标注）在 ./turnCloseout.js；停止动作在 ./stopTurn.js
  const stop = createStopTurn({
    currentSid: () => currentSessionId.value,
    streams: chatStreams,
    stopApi: stopChatTurn,
    notifyWarning: msg => notify.warning(msg),
    // 通道断了、正在重挂时本地已经没有流：停止仍要落到服务端（否则那一轮转后台跑完还占着会话锁）
    reconnect: {
      take: sid => cancelReconnect(sid),
      settle: (msg, sid) => finishResumedShell(msg, null, sid),
    },
  })

  // 运行中插话队列（服务端持有、刷新可恢复）在 ./steerQueue.js（actions 层）
  const { steerQueue, steerResend, steer, stageSteer, sendSteer, editSteer, dropSteer, moveSteer,
          dropApplied, restoreSteerQueue, enqueuePlanAdjust } = createSteerQueue({
    currentSid: () => currentSessionId.value,
    hasLiveStream: sid => chatStreams.has(sid),
    steerApi: steerChatTurn,
    enqueueApi: enqueueSteer,
    listApi: steerQueueList,
    steerItemApi: steerQueued,
    dropApi: dropQueued,
    notify: (msg, level) => (level === 'info' ? notify.info(msg) : notify.warning(msg)),
    textOf: () => text.value,
    setText: v => { text.value = v },
    // 取回队列条目时材料一并回到输入框（图按 data URL 还原，附件按 fileId 挂回）
    attachImage: u => attachImage(u),
    attachUploaded: a => attachUploaded(a),
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

  return { variantSwitching, switchBranch, stop, steerQueue, steerResend, steer, stageSteer,
           sendSteer, editSteer, dropSteer, moveSteer, dropApplied, enqueuePlanAdjust }
}
