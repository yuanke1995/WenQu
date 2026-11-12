// 多版本动作（actions 层）：本会话内存版本的即时切换、重新生成，
// 以及后端持久分支的切换（切完重拉会话——分支尾部整段变化，本地 splice 造不出）。
import { ref } from 'vue'
import { snapshotVersion, applyVersion } from './versions.js'

export const switchVersion = (mi, delta, ctx) => {
  const m = ctx.messages()[mi]
  if (!m || !Array.isArray(m.versions) || m.versions.length < 2) return
  const cur = m.vIndex || 0
  const ni = Math.max(0, Math.min(m.versions.length - 1, cur + delta))
  if (ni === cur) return
  m.vIndex = ni
  applyVersion(m, m.versions[ni])
}

export const regenerate = async (mi, ctx) => {
  if (ctx.loading()) return
  const list = ctx.messages()
  for (let i = mi - 1; i >= 0; i--) {
    if (list[i].role !== 'user') continue
    const imgs = (list[i].images || []).filter(u => u.startsWith('data:'))
    const deep = !!list[i].deepThink
    // 附件随消息重发（内存轮与历史轮都带凭据）。已过保留期的那部分先出声再丢掉：
    // 带着死凭据发出去会被后端整轮拒掉，用户看到的就成了「重新生成点了没反应」
    const rawAtts = Array.isArray(list[i].attachData) ? list[i].attachData : []
    let atts = rawAtts
    try {
      const split = await ctx.splitAtts(rawAtts)
      atts = split.ok
      if (split.dead.length) ctx.notifyWarning(`这一轮的 ${split.dead.length} 个附件已过保留期，重新生成不再带上`)
    } catch (e) {
      atts = []
      ctx.notifyWarning(`附件状态没查到（${e.message || '请求失败'}），重新生成不带附件`)
    }
    const skills = Array.isArray(list[i].skills) ? list[i].skills : []
    // @ 引用随内存消息重发（历史回放无该数据则不重发；引用只对当轮检索生效）
    const mentions = Array.isArray(list[i].mentions) ? list[i].mentions : []
    // # 历史引用同口径：重新生成保留原引用（该轮答案本就是基于这些历史得出的）
    const historyRefs = Array.isArray(list[i].historyRefs) ? list[i].historyRefs : []
    // 计划模式同口径：重新生成沿用原消息开启的计划模式（出计划→批准→再回答）
    const planMode = !!list[i].planMode
    // 多版本：首次重新生成前把当前回答快照为 v1（后续版本在 done 时追加）。已有 versions 说明
    // 这条消息本就是多版本序列（当前展示的必然在序列里），无需再快照
    const ai = list[mi]
    if (!Array.isArray(ai.versions) || !ai.versions.length) {
      ai.versions = [snapshotVersion(ai)]
      ai.vIndex = 0
    }
    // 重新计划的那一轮也要带上血缘：内存轮用用户消息上的标记，刷新后的历史轮用计划卡上的来源
    const planBaseId = (list[i].planAdjustFrom && list[i].planAdjustFrom.cardId)
      || (ai.planCard && ai.planCard.adjustFrom && ai.planCard.adjustFrom.cardId) || ''
    // 传消息对象（不是下标）：流式状态已按会话拆分，replace 走对象身份
    ctx.streamAnswer(list[i].content, imgs, ai, false, 1, deep, atts, skills, mentions, null, historyRefs, '', null, planMode, false, false,
                     planBaseId ? { planBaseId } : null)
    return
  }
  ctx.notifyWarning('未找到对应的问题')
}

// 两种数据源统一到一个切换器：
// - m.versions（本会话内存里的重新生成版本）：纯本地切换，零请求；
// - m.variantCount/variantIndex（后端持久分支，编辑重发或刷新后的历史恢复）：调切换接口让后端
//   「当前分支软删留档、目标分支按快照恢复」，随后重拉会话历史刷新整个视图（分支尾部整段变化）。
export const createBranchSwitcher = ({ messages, isLoading, switchVersion: switchVersionFn, switchApi, reload, notifyWarning }) => {
  const variantSwitching = ref(false)
  const switchBranch = async (mi, delta) => {
    const m = messages()[mi]
    if (!m || variantSwitching.value) return
    // 本轮正在回答时不允许切分支：流式那轮的父消息可能正被切走的分支持有，落库会错挂
    if (isLoading()) { notifyWarning('当前正在回答，请先停止或稍候'); return }
    // 本地内存版本优先（重新生成的即时多版本）
    if (Array.isArray(m.versions) && m.versions.length > 1) { switchVersionFn(mi, delta); return }
    if (!m.messageId || !m.variantCount) return
    variantSwitching.value = true
    try {
      const r = await switchApi(m.messageId, delta)
      if (r.success !== false) {
        // 重拉历史：切回的分支整段尾部都在后端恢复，本地 splice 造不出版本视图
        await reload()
      } else {
        notifyWarning(r.msg || '切换失败')
      }
    } catch (e) {
      notifyWarning(e?.message || '切换失败')
    } finally {
      variantSwitching.value = false
    }
  }
  return { variantSwitching, switchBranch }
}
