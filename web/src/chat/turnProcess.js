// 过程块投影：时间线里的思考/过程/工具段按发生顺序聚簇，text 段属于正文流不进过程块。
// 无时间线时退回工具终态清单（正文重建回退）。纯函数，不读 .value。
import { clusterize, markGrowingCluster } from './processCluster.js'

const toolCallsView = list => Array.isArray(list) ? list : []

/** 过程块：时间线里的全部思考/过程/工具段，按发生顺序聚簇。
 *  text 段一律属于正文流，不进过程块；不做任何句子推断。 */
export const turnProcessRows = m => {
  const tl = Array.isArray(m && m.timeline) ? m.timeline : []
  const out = []
  let group = null
  const flushGroup = () => { if (group) { out.push(group); group = null } }
  for (const seg of tl) {
    if (!seg) continue
    if (seg.kind === 'text' || seg.kind === 'artifact') continue
    if (seg.kind === 'tool') {
      if (!seg.tool) continue
      if (!group) group = { kind: 'group', tools: [] }
      group.tools.push(seg.tool)
      continue
    }
    if (seg.kind === 'think') {
      flushGroup()
      const tlen = String((m && m.thinking) || '').length
      const f = Math.min(Number(seg.from) || 0, tlen)
      const t = Math.min(Number(seg.to) || 0, tlen)
      if (t > f) out.push({ kind: 'think', from: f, to: t })
      continue
    }
    if (seg.kind === 'process') {
      flushGroup()
      const plen = String((m && m.processText) || '').length
      const f = Math.min(Number(seg.from) || 0, plen)
      const t = Math.min(Number(seg.to) || 0, plen)
      if (t > f) out.push({ kind: 'process', from: f, to: t })
      continue
    }
  }
  flushGroup()
  if (!out.length && m && Array.isArray(m.toolCalls) && m.toolCalls.length) {
    // 正文重建回退：没有时间线可依，工具按终态清单成组展示
    const tools = toolCallsView(m.toolCalls)
    if (tools.length) out.push({ kind: 'group', tools })
  }
  return markGrowingCluster(clusterize(out), m)
}
