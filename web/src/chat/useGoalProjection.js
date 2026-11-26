// @ts-check
// 目标条与详情面的状态口径（从 useChatEngine 抽出）。
// 词汇与判据本体在 projections.js（目标卡也读它），这里只包成响应式的一份，
// 供摘要条（PC 与移动壳）与右栏面板用——同一条目标在三处说的必须是同一句话。
// 上次尝试抽这一块时把 20 个投影函数当作参数注入，依赖爆炸而回退；
// 这次直接在模块内从 projections.js 导入，只注入真正运行时才有的四个对象。
import { ref, reactive, computed, watch } from 'vue'
import {
  goalTerminalOf, goalBarVisibleOf, goalPhraseOf, goalStateLabelOf, goalStateClassOf,
  goalBlockReasonOf, goalBarTitleOf, goalBarMetaOf, goalRoundLineOf,
  goalCanPauseOf, goalCanResumeOf, goalCanEditOf, goalCanCancelOf, goalCanClearRecordOf,
  goalPreemptedOf, goalVersionLabelOf, goalProgressPercentOf,
  goalTerminalCardOf, goalTerminalTitleOf, goalTerminalNoteOf,
} from './projections'

export const useGoalProjection = ({ sessionGoal, currentSessionId, chatStreams, messages, goalAct }) => {
  // ==================== 目标条与详情面的状态口径 ====================
  // 词汇与判据本体在 projections.js（目标卡也读它），这里只把它包成响应式的一份，
  // 供摘要条（PC 与移动壳）与右栏面板用——同一条目标在三处说的必须是同一句话。
  const goalTerminal = computed(() => goalTerminalOf(sessionGoal.value))
  const goalBarVisible = computed(() => goalBarVisibleOf(sessionGoal.value))
  const goalPhrase = computed(() => goalPhraseOf(sessionGoal.value))
  const goalStateLabel = computed(() => goalStateLabelOf(sessionGoal.value))
  const goalStateClass = computed(() => goalStateClassOf(sessionGoal.value))
  const goalRoundLine = computed(() => goalRoundLineOf(sessionGoal.value))
  const goalBlockReason = computed(() => goalBlockReasonOf(sessionGoal.value))
  const goalBarTitle = computed(() => goalBarTitleOf(sessionGoal.value))
  const goalBarMeta = computed(() => goalBarMetaOf(sessionGoal.value))
  const goalCanPause = computed(() => goalCanPauseOf(sessionGoal.value))
  const goalCanResume = computed(() => goalCanResumeOf(sessionGoal.value))
  const goalCanEdit = computed(() => goalCanEditOf(sessionGoal.value))
  /** 取消 / 删除记录是两件事：取消任何活目标（含未完成）都能点，删除只在终态出现 */
  const goalCanCancel = computed(() => goalCanCancelOf(sessionGoal.value))
  const goalCanClearRecord = computed(() => goalCanClearRecordOf(sessionGoal.value))
  const goalPreempted = computed(() => goalPreemptedOf(sessionGoal.value))
  /** 条子三段：身份（原文+版本）/ 进度（轮次+细条）/ 动作——token、时间都在详情抽屉里 */
  const goalVersionLabel = computed(() => goalVersionLabelOf(sessionGoal.value))
  const goalProgressPercent = computed(() => goalProgressPercentOf(sessionGoal.value))
  /** 消息流里的终态卡：挂在「设为目标」那条回声下，按 goalId 认；删除记录后 sessionGoal 变 null 即消失 */
  const goalTerminalCard = computed(() => sessionGoal.value && goalTerminalCardOf(sessionGoal.value) ? sessionGoal.value : null)
  const goalTerminalTitle = computed(() => goalTerminalTitleOf(sessionGoal.value))
  const goalTerminalNote = computed(() => goalTerminalNoteOf(sessionGoal.value))
  /** 目标轮正在浏览器里流式呈现（turnId 以 goal: 开头）：这时用户发送＝插话，不得排成 steer */
  const goalTurnStreaming = computed(() => {
    const sid = currentSessionId.value
    const st = sid ? chatStreams.get(sid) : null
    return !!(st && st.msg && String(st.msg.turnId || '').startsWith('goal:'))
  })
  /** 本地让出目标轮的呈现通道：只断开这一侧，不请服务端停止。
   *  标记 surrendered 让收尾不把这段空回答写成「已停止生成」——它实际是被用户这句打断的。 */
  const surrenderGoalStream = () => {
    const sid = currentSessionId.value
    const st = sid ? chatStreams.get(sid) : null
    if (!st || !st.msg || !String(st.msg.turnId || '').startsWith('goal:')) return false
    st.surrendered = true
    chatStreams.delete(sid)
    const m = st.msg
    m.loading = false
    // 这一轮还没来得及吐出任何一段正文（只剩一颗刚点亮的空壳）：直接撤下，
    // 否则用户那句插话的下面会留一行空白气泡。已经跑出内容/工具过程的不动——那是用户要回看的痕。
    if (!String(m.content || '').trim() && !(m.toolCalls || []).length && !(m.artifacts || []).length
        && !(m.timeline || []).length && !m.errorCard) {
      const i = messages.value.indexOf(m)
      if (i >= 0) messages.value.splice(i, 1)
    }
    try { st.abort?.abort() } catch (e) { /* 已断开 */ }
    return true
  }
  /** 条子里那一行红字：动作失败的原文（条子就在眼睛前面，不弹层；悬浮行与详情面带着细节） */
  const goalBarError = computed(() => sessionGoal.value?.actionError || '')
  const runGoalAction = action => goalAct(action)

  // ---- 就地编辑态（PC 在条子里长开，移动壳在底部 sheet 里开：同一份草稿、同一保存出口）----
  // DSH 口径下目标原文可改：改的是「接下来往哪走」，已跑的轮次与历史都留在原目标上。
  const goalEditing = ref(false)
  const goalEditBusy = ref(false)
  const goalEdit = reactive({ goalId: '', objective: '' })
  /** 能保存的判据：原文非空，且与当前目标原文确实不同（原样提交等于没改，不给点保存） */
  const goalEditSaveable = computed(() => {
    const g = sessionGoal.value
    const obj = String(goalEdit.objective || '').trim()
    return !!g && !!obj && obj !== String(g.objective || '').trim()
  })
  const startGoalInlineEdit = () => {
    const g = sessionGoal.value
    if (!g) return false
    goalEdit.goalId = g.goalId
    goalEdit.objective = g.objective || ''
    goalEditing.value = true
    return true
  }
  const saveGoalEdit = async () => {
    if (goalEditBusy.value) return false
    if (!goalEditSaveable.value) return false
    const objective = String(goalEdit.objective || '').trim()
    goalEditBusy.value = true
    try {
      const r = await goalAct('edit', { objective })
      if (r) goalEditing.value = false
      return !!r
    } finally {
      goalEditBusy.value = false
    }
  }
  // 目标换了（另起/清除）或状态迁出可编辑那两档，就地编辑态必须撤下：
  // 留着草稿会在下一条目标上按 Enter 写花它（与 DSH 的 goalId 变化重置同一件事）
  watch(() => sessionGoal.value && (sessionGoal.value.goalId + ':' + sessionGoal.value.status), (cur, prev) => {
    if (cur !== prev) goalEditing.value = false
  })

  return {
    goalTerminal, goalBarVisible, goalPhrase, goalStateLabel, goalStateClass,
    goalRoundLine, goalBlockReason, goalBarTitle, goalBarMeta,
    goalCanPause, goalCanResume, goalCanEdit, goalCanCancel, goalCanClearRecord,
    goalPreempted, goalVersionLabel, goalProgressPercent,
    goalTerminalCard, goalTerminalTitle, goalTerminalNote,
    goalTurnStreaming, surrenderGoalStream, goalBarError, runGoalAction,
    goalEditing, goalEditBusy, goalEdit, goalEditSaveable, startGoalInlineEdit, saveGoalEdit,
  }
}
