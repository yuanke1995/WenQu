// 工具卡开合与「让这张卡真的看得见」的定位展开：纯状态操作，不读 .value。
import { turnOpen } from './turnFold.js'
import { turnProcessRows } from './turnProcess.js'
import { clusterOpen, toggleCluster } from './processCluster.js'

export const toolCardOpen = t => {
  if (!t) return false
  return t.subChat ? t._open !== false : !!t._open
}

export const toggleToolCard = t => {
  if (!t) return
  t._open = t.subChat ? t._open === false : !t._open
}

/** 四层依次摊开：本轮过程 → 所在簇 → 所在组条 → 卡片自己。 */
export const openDispatchCard = (m, t) => {
  if (!m || !t) return
  const show = () => { if (!toolCardOpen(t)) toggleToolCard(t) }
  if (!turnOpen(m)) m._turnOpen = true
  for (const s of turnProcessRows(m)) {
    const segs = s.kind === 'cluster' ? (s.segs || []) : [s]
    for (const g of segs) {
      if (!g || g.kind !== 'group' || !Array.isArray(g.tools) || g.tools.indexOf(t) < 0) continue
      if (s.kind === 'cluster' && !clusterOpen(m, s)) toggleCluster(m, s)
      if (g.tools.length > 1 && g.tools[0]._groupOpen !== true) g.tools[0]._groupOpen = true
      show()
      return
    }
  }
  // 时间线里没有它（正文重建失败退回兜底列表那条路）：只管把卡片本身点开
  show()
}
