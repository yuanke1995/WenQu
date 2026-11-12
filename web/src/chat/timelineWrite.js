// 时间线写入口与落库恢复：纯数据结构操作，不读 .value。
// 段区间指向正文/思考/过程原文，不复制文本；工具/产物段只存下标，渲染时取回对象。

export const extendTimelineText = (m, from, to) => {
  if (!m) return
  const tl = Array.isArray(m.timeline) ? m.timeline : (m.timeline = [])
  const last = tl[tl.length - 1]
  if (last && last.kind === 'text' && last.to === from) last.to = to
  else tl.push({ kind: 'text', from, to })
}

/** 过程独白段：连续追加自动延伸末段。 */
export const extendTimelineProcess = (m, from, to) => {
  if (!m) return
  const tl = Array.isArray(m.timeline) ? m.timeline : (m.timeline = [])
  const last = tl[tl.length - 1]
  if (last && last.kind === 'process' && last.to === from) last.to = to
  else tl.push({ kind: 'process', from, to })
}

/** 思考段：一次「想完再动手」一段，不与上一段合并。 */
export const pushTimelineThink = (m, from, to) => {
  if (!m) return
  const tl = Array.isArray(m.timeline) ? m.timeline : (m.timeline = [])
  tl.push({ kind: 'think', from, to })
}

export const extendTimelineThink = (m, from, to) => {
  if (!m) return
  const tl = Array.isArray(m.timeline) ? m.timeline : (m.timeline = [])
  const last = tl[tl.length - 1]
  if (last && last.kind === 'think' && last.to === from) last.to = to
  else tl.push({ kind: 'think', from, to })
}

/** 工具段带下标 i：与后端落库口径一致；实时态另存对象引用。 */
export const pushTimelineTool = (m, tool) => {
  if (!m) return
  const tl = Array.isArray(m.timeline) ? m.timeline : (m.timeline = [])
  tl.push({ kind: 'tool', tool, i: Array.isArray(m.toolCalls) ? m.toolCalls.length - 1 : 0 })
}

export const pushTimelineArtifact = (m, index) => {
  if (!m) return
  const tl = Array.isArray(m.timeline) ? m.timeline : (m.timeline = [])
  tl.push({ kind: 'artifact', i: index })
}

/** 插话段：同一条收据可能被读两遍，按 messageId 幂等。 */
export const pushTimelineSteer = (m, text, messageId) => {
  if (!m || !text) return
  const tl = Array.isArray(m.timeline) ? m.timeline : (m.timeline = [])
  const id = messageId || ''
  if (id && tl.some(s => s && s.kind === 'steer' && s.messageId === id)) return
  tl.push({ kind: 'steer', text, messageId: id })
}

/** 用后端落库/下发的段数组重建时间线：文本/思考段用区间，工具/产物按索引取回对象；缺数据跳过。 */
export const restoreTimeline = (m, segs) => {
  const out = []
  for (const s of segs || []) {
    if (!s || !s.kind) continue
    if (s.kind === 'text') {
      out.push({ kind: 'text', from: Number(s.from) || 0, to: Number(s.to) || 0 })
    } else if (s.kind === 'think') {
      out.push({ kind: 'think', from: Number(s.from) || 0, to: Number(s.to) || 0 })
    } else if (s.kind === 'process') {
      const from = Number(s.from) || 0
      const to = Number(s.to) || 0
      const last = out[out.length - 1]
      // 相邻过程段无损合并（早期落库把一段独白切成上百碎片）
      if (last && last.kind === 'process' && last.to === from) last.to = to
      else out.push({ kind: 'process', from, to })
    } else if (s.kind === 'tool') {
      const t = (m.toolCalls || [])[Number(s.i) || 0]
      if (t) out.push({ kind: 'tool', i: Number(s.i) || 0, tool: t })
    } else if (s.kind === 'artifact') {
      out.push({ kind: 'artifact', i: Number(s.i) || 0 })
    } else if (s.kind === 'steer') {
      const t = String(s.text || '')
      if (t) out.push({ kind: 'steer', text: t, messageId: String(s.messageId || '') })
    }
  }
  return out
}
