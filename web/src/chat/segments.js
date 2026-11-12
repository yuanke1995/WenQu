// 思考段/过程段的折叠、提要、切片：纯函数，不读 .value。
// 段的边界由引擎侧决定；这里只按段起点 from 维护用户点过的开合态与「正在长的那一段」。

export const procOpen = (m, seg) => {
  const marked = m && m._procOpen ? m._procOpen[seg.from] : undefined
  if (marked === true) return true
  if (marked === false) return false
  if (!m || !m.loading) return false
  const tl = Array.isArray(m.timeline) ? m.timeline : []
  for (let i = tl.length - 1; i >= 0; i--) {
    if (tl[i] && tl[i].kind === 'process') return (Number(tl[i].from) || 0) === (Number(seg.from) || 0)
  }
  return false
}

export const toggleProc = (m, seg) => {
  if (!m._procOpen) m._procOpen = {}
  m._procOpen[seg.from] = !procOpen(m, seg)
}

export const procSlice = (m, seg) => (m.processText || '').slice(seg.from, seg.to)

export const thinkSlice = (m, seg) => (m.thinking || '').slice(seg.from, seg.to)

const THINK_PEEK_CHARS = 80
const THINK_PEEK_RAW_CHARS = 400

/** 提要：取开头压成一行（去代码块/列表符/强调符），流式每帧都算，不拿整段正文做正则。 */
export const peekText = (raw, max = THINK_PEEK_CHARS) => {
  if (!raw) return ''
  const flat = String(raw).slice(0, THINK_PEEK_RAW_CHARS)
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/^\s*[-*+]\s+/gm, '')
    .replace(/[*_`~>#]+/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  if (!flat) return ''
  return flat.length > max ? flat.slice(0, max).trimEnd() : flat
}

export const thinkPeek = (m, seg, max = THINK_PEEK_CHARS) => peekText(thinkSlice(m, seg), max)

/** 此刻正在长的思考段（一轮同时至多一段）：决定转圈挂哪儿、哪段自动展开。 */
export const thinkGrowing = (m, seg) => {
  if (!m || !m.loading || !m.thinkLoading || !seg) return false
  const tl = Array.isArray(m.timeline) ? m.timeline : []
  for (let i = tl.length - 1; i >= 0; i--) {
    if (tl[i] && tl[i].kind === 'think') return (Number(tl[i].from) || 0) === (Number(seg.from) || 0)
  }
  return false
}

/** 用户点过就认点过的；没点过时只自动展开正在长的那一段；收尾后一律收起。 */
export const thinkSegOpen = (m, seg) => {
  const marked = m && m._thinkOpen ? m._thinkOpen[seg.from] : undefined
  if (marked === true) return true
  if (marked === false) return false
  return thinkGrowing(m, seg)
}

export const toggleThinkSeg = (m, seg) => {
  if (!m._thinkOpen) m._thinkOpen = {}
  m._thinkOpen[seg.from] = !thinkSegOpen(m, seg)
}

/** 有思考全文却没有就地段：在时间线头部补一整段（正文重建/快照只给全文时兜底）。 */
export const ensureThinkSegment = m => {
  if (!m || !m.thinking) return m
  const tl = Array.isArray(m.timeline) ? m.timeline : (m.timeline = [])
  if (tl.some(s => s && s.kind === 'think')) return m
  tl.unshift({ kind: 'think', from: 0, to: m.thinking.length })
  return m
}
