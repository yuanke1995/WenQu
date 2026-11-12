// 重新生成多版本的纯逻辑：快照/回填、切换边界、版本号文案。
// 版本只存在于当前会话内存里；历史接口按「一题一答」返回，刷新后看到的是最后一版。

export const snapshotVersion = m => ({
  content: m.content || '',
  sources: m.sources || [],
  related: m.related || [],
  thinking: m.thinking || '',
  timeline: m.timeline || [],
  artifacts: m.artifacts || [],
  toolCalls: m.toolCalls || [],
  plan: m.plan || null,
  processText: m.processText || '',
  degradations: m.degradations || [],
  warnMsg: m.warnMsg || '',
  retrieved: m.retrieved || null,
  doneTime: m.doneTime || null,
  // 整轮用时随版本走：切回第 1 版时过程与用时必须同一版
  turnMs: m.turnMs || 0,
  turnPrepMs: m.turnPrepMs || 0,
  humanWaitMs: m.humanWaitMs || 0,
  model: m.model || ''
})

export const applyVersion = (m, v) => {
  m.content = v.content
  m.sources = v.sources
  m.related = v.related
  m.thinking = v.thinking
  m.timeline = v.timeline
  m.artifacts = v.artifacts
  m.toolCalls = v.toolCalls
  m.plan = v.plan
  m.processText = v.processText
  m.degradations = v.degradations
  m.warnMsg = v.warnMsg
  m.retrieved = v.retrieved
  m.doneTime = v.doneTime
  m.turnMs = v.turnMs || 0
  m.turnPrepMs = v.turnPrepMs || 0
  m.humanWaitMs = v.humanWaitMs || 0
  // 模型随版本走：切回旧版本时分隔记录按那一版当时用的模型比对
  m.model = v.model || ''
}

/** 内存 versions（本会话重新生成）优先，其次持久 variant（历史恢复/编辑重发）。 */
export const verLocal = m => Array.isArray(m.versions) && m.versions.length > 1
export const canSwitchPrev = m => verLocal(m) ? (m.vIndex || 0) > 0 : (m.variantIndex || 1) > 1
export const canSwitchNext = m => verLocal(m)
  ? (m.vIndex || 0) < m.versions.length - 1
  : (m.variantIndex || 1) < (m.variantCount || 1)
export const verLabel = m => verLocal(m)
  ? `${(m.vIndex || 0) + 1}/${m.versions.length}`
  : `${m.variantIndex || 1}/${m.variantCount || 1}`
