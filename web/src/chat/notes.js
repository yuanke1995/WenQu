// 分组条与行尾思考/独白（note/tail）的统一口径：纯函数，不读 .value。
import { thinkSlice, thinkPeek, thinkGrowing, thinkSegOpen, toggleThinkSeg, procSlice, procOpen, toggleProc, peekText } from './segments.js'

/** 这一组要不要出自己的组条（并行批次）。 */
export const groupHasBar = seg => (seg?.tools || []).length > 1

/** 行尾那一项（思考/独白）的四个口径统一出口。 */
export const noteText = (m, n) => (n.kind === 'think' ? thinkSlice(m, n) : procSlice(m, n))
export const noteTag = n => (n.kind === 'think' ? '深度思考' : '过程说明')
export const noteOpen = (m, n) => (n.kind === 'think' ? thinkSegOpen(m, n) : procOpen(m, n))
export const noteGrowing = (m, n) => (n.kind === 'think' ? thinkGrowing(m, n) : !!(m && m.loading && procOpen(m, n)))
export const toggleNote = (m, n) => (n.kind === 'think' ? toggleThinkSeg(m, n) : toggleProc(m, n))
export const notePeek = (m, n) => (n.kind === 'think' ? thinkPeek(m, n) : peekText(procSlice(m, n)))

/** 帽体尾行：取第一段有内容的提要。 */
export const tailNotesPeek = (m, notes) => {
  for (const n of notes || []) {
    const p = notePeek(m, n)
    if (p) return p
  }
  return ''
}

/** 尾行整组的类别名：思考+独白混合＝思考与说明。 */
export const tailNotesTag = notes => {
  const ns = notes || []
  if (!ns.length) return ''
  const kinds = new Set(ns.map(n => n.kind))
  return kinds.size > 1 ? '思考与说明' : (ns[0].kind === 'process' ? '过程说明' : '深度思考')
}

export const tailNotesGrowing = (m, notes) => (notes || []).some(n => noteGrowing(m, n))

/** 尾行开合态：挂在消息上，键用簇的 key（段对象每帧重算，状态不能挂段上）。 */
export const tailOpen = (m, cl) => !!(m && cl && m._tailOpen && m._tailOpen[cl.key])
export const toggleTail = (m, cl) => {
  if (!m || !cl) return
  if (!m._tailOpen) m._tailOpen = {}
  m._tailOpen[cl.key] = !tailOpen(m, cl)
}
