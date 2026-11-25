// 手动压缩会话上下文（/compact）+ 早期对话摘要面板（从 useChatEngine 抽出）。
// 后端一律走 AgentScope 框架的 ConversationCompactor，问渠侧不再有第二套摘要阈值/开关；
// 这里只负责显式发起压缩、并在当场/事后展示摘要结果。
import { ref, computed } from 'vue'
import { fmtTokens } from '../utils/token'

export const useCompactSession = ({ currentSessionId, loading, notify,
                                    compactApi, summaryApi }) => {
  // ==================== 手动压缩会话上下文（/compact） ====================
  // 后端一律走 AgentScope 框架的 ConversationCompactor（FrameworkSessions.compactNow），
  // 问渠侧不再有第二套摘要阈值/开关；这里只负责显式发起并展示当场结果。
  const manualCompacting = ref(false)
  /** 轮内自动压缩进行中的那条会话（框架压缩起止帧带回来，见 streamAnswer 的 onCompactState） */
  const autoCompactSid = ref(null)
  /**
   * 本轮已经压完、但分隔线还没拿到落库标记的那条会话（见 liveCompactAt）。
   * <p>只在当场有效：回答落库后持久判据接管，下一轮发送即清——它是那条线从「进度」换成「留痕」
   * 之间的一段桥，不是第二份状态。
   */
  const liveCompactedSid = ref(null)
  // 这一份只用来闸「再来一次」的入口（摘要面板的重新压缩、模型面板的压缩上下文）：自动那一路
  // 必须再 AND 上 loading，压缩只可能长在跑着的那一轮里，轮一结束就该松闸——只认 end 帧的话，
  // 断线/收尾竞态漏掉那一帧就永远压着不让再压。进度文案不在这里，在气泡那条状态行上。
  const compacting = computed(() => manualCompacting.value
    || (!!autoCompactSid.value && autoCompactSid.value === currentSessionId.value && loading.value))
  /**
   * 手动 /compact 当场这一次的结果（本地状态、下一轮发送即退场）。
   * <p>持久的那份是「已压缩上下文」分隔线：自动压缩在消息行上留 compacted 标记（见 compactDividerAt），
   * 手动压缩不产生消息行，所以它只有当场挂在流尾的这一条。
   * <p>摘要全文两种压缩都会落进会话行，所以刷新后从分隔线点「查看摘要」仍然读得到——丢的只是线，不是摘要。
   * <p>带 sid：会话切走后不再显示，否则会把别的会话的结论挂在当前会话下。
   */
  const compactNotice = ref(null)

  /**
   * 手动压缩当前会话上下文：早期对话并入摘要，完整记录仍留在会话里可回看。
   * @param instruction 这一次摘要的附加要求（可空，如「保留结论与数字、忽略寒暄」），后端并进摘要提示词
   * @returns 后端统计（compressedTurns/summary 等）；无会话/正在回答/失败返回 null（原因已就地提示）
   */
  const compactContext = async (instruction = '') => {
    const sid = currentSessionId.value
    if (!sid) { notify.warning('当前没有可压缩的会话'); return null }
    // 正在回答时不允许压缩：本轮正在读/写同一份摘要，交叉会互相覆盖
    if (loading.value) { notify.warning('正在生成回答，等本轮结束后再压缩'); return null }
    if (compacting.value) return null
    const req = String(instruction || '').trim()
    manualCompacting.value = true
    // 进行中不给常驻 toast：流尾那条「正在压缩上下文」分隔线就是进度（压完原地换成结果那条）
    try {
      const r = await compactApi(sid, req)
      const d = (r && r.data) || {}
      // 无活实例/压缩失败回落：框架在提问前自己整理上下文，这里如实说排到了下一次提问前
      // （那句要求跟着排进去，不是丢了——所以说了要求才算收下，输入行可以收）
      if (d.scheduled) {
        notify.info(req ? '这条要求已记下，下一次提问前折一次早期对话' : '已安排下一次提问前折一次早期对话')
        return d
      }
      // 两种情形都算「没得压」：对话本来就短，或早期对话上次已经压过了（最近 2 次问答不会被压掉）
      if (!d.compressedTurns) { notify.info('没有可压缩的早期对话（对话还短，或已经压过了）'); return d }
      compactNotice.value = {
        sid, turns: d.compressedTurns, keepTurns: Number(d.keepTurns) || 0,
        freedTokens: Number(d.freedTokens) || 0,
        summary: d.summary || '', summaryTokens: d.summaryTokens || 0, partial: !!d.partial
      }
      // 只报用户要的结果（腾出多少），不复述内部计数
      const freed = Number(d.freedTokens) || 0
      notify.success(`已压缩早期对话${freed > 0 ? `，为后续腾出约 ${fmtTokens(freed)} tokens` : ''}`)
      return d
    } catch (e) {
      notify.error(e?.message || '压缩失败，请稍后重试')
      return null
    } finally {
      manualCompacting.value = false
    }
  }

  // ==================== 早期对话摘要面板（流尾结果条与「已压缩上下文」分隔线共用） ====================
  const compactSummaryOpen = ref(false)
  const compactSummaryLoading = ref(false)
  /** 面板内容：打开时快照一份。发送下一轮会清掉 compactNotice，面板若直读它会当场变空 */
  const compactSummary = ref(null)

  const snapshotSummary = (text, tokens, keepTurns, freed) => {
    compactSummary.value = { text, tokens: Number(tokens) || 0, keepTurns: Number(keepTurns) || 0, freed: Number(freed) || 0 }
  }

  /**
   * 打开「早期对话摘要」。当场压过就用 /compact 的返回（多一个「本次腾出」）；
   * 从历史里的分隔线点进来则按会话读当前那份——会话只有<b>一份</b>滚动摘要，每次压缩都往后并入同一份，
   * 所以从哪条线点进来看到的都是生效中的这一版，面板上按这个口径说。
   */
  const openCompactSummary = async () => {
    const n = compactNotice.value
    if (n?.summary) {
      snapshotSummary(n.summary, n.summaryTokens, n.keepTurns, n.freedTokens)
      compactSummaryOpen.value = true
      return
    }
    const sid = currentSessionId.value
    if (!sid || compactSummaryLoading.value) return
    compactSummaryLoading.value = true
    try {
      const d = ((await summaryApi(sid)) || {}).data || {}
      // 取不到不等于没压过：分隔线就在眼前，说「还没有生成过摘要」是跟用户看到的画面吵架。
      // 摘要交回框架那阵子压的会话正文没落库，本进程又没这条会话的活实例时就是这一句
      if (!d.summary) { notify.info('这条会话的早期摘要暂时取不到'); return }
      snapshotSummary(d.summary, d.summaryTokens, d.keepTurns, 0)
      compactSummaryOpen.value = true
    } catch (e) {
      notify.error(e?.message || '摘要读取失败，请稍后重试')
    } finally {
      compactSummaryLoading.value = false
    }
  }

  /**
   * 带着要求再压一次（摘要弹窗里的入口）：压成就把面板刷成新摘要；
   * 排到下一次提问前那句要求也已收下，同样算成功（面板没有新摘要可刷，原因引擎已就地提示）。
   * 真没压成（没得压/失败）返回 false，让弹窗里那行输入留着好改一句再试。
   */
  const recompactWith = async (instruction) => {
    const d = await compactContext(instruction)
    if (!d) return false
    if (d.scheduled) return true
    if (!d.compressedTurns) return false
    snapshotSummary(d.summary, d.summaryTokens, d.keepTurns, d.freedTokens)
    return true
  }

  return {
    manualCompacting, autoCompactSid, liveCompactedSid, compacting, compactNotice,
    compactContext, compactSummaryOpen, compactSummaryLoading, compactSummary,
    snapshotSummary, openCompactSummary, recompactWith,
  }
}
