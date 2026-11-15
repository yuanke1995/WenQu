/**
 * 委派编排视图的两块纯计算：列表窗口化滚动 与 聚焦拓扑布局。
 * <p>抽出来是为了可测——组件里只剩渲染；行高/可视高度一旦和 CSS 对不上，
 * 表现是「滚到一半空档」或「最后一屏永远滚到底」，只在真机长列表下才看得见。
 */

/** 列表行高（必须与 .ap-topo-row 的 CSS 高度一致） */
export const TOPO_LIST_ROW_H = 52
/** 列表可视高度（必须与 .ap-topo-rows 的 CSS 高度一致） */
export const TOPO_LIST_VIEW_H = 432

/**
 * 窗口化滚动：返回本次要渲染的行区间与上下占位高度。
 * @param {number} total 过滤后的总行数
 * @param {number} scrollTop 容器当前滚动位置
 * @param {{rowH?:number, viewH?:number, overscan?:number}} [opt] overscan=上下各多渲染几行（滚动时不闪白）
 */
export function topoWindow (total, scrollTop, opt = {}) {
  const { rowH = TOPO_LIST_ROW_H, viewH = TOPO_LIST_VIEW_H, overscan = 3 } = opt
  const n = Math.max(0, Number(total) || 0)
  const top = Math.max(0, Number(scrollTop) || 0)
  // 数据变少（换了筛选/列表刷新）时浏览器会夹住 scrollTop，但调用方手里的旧值可能还没更新：
  // 不在这里夹住就会出现「窗口起点超过总数 → 一屏空白」
  const maxStart = Math.max(0, n - 1)
  const start = Math.min(maxStart, Math.max(0, Math.floor(top / rowH) - overscan))
  const end = Math.min(n, start + Math.ceil(viewH / rowH) + overscan * 2)
  return {
    start,
    end,
    from: n ? start + 1 : 0,
    to: end,
    padTop: start * rowH,
    padBottom: Math.max(0, (n - end) * rowH)
  }
}

/**
 * 聚焦拓扑布局：一位主智能体 + 它的子智能体（含失效引用占位）。
 * <p>最多两列、每列 8 个起排——画布高度只随这一位的委派数变化，与智能体总量无关；
 * 超过两列的量交给画布容器滚动，弹窗高度不动。
 * @param {Array<{key:string, id?:string, agent?:object, miss:boolean}>} items
 * @param {{pad:number,rowH:number,rowGap:number,nodeW:number,gapG:number,subW:number,colGap?:number}} g
 */
export function focusedGraph (items, g) {
  const { pad, rowH, rowGap, nodeW, gapG, subW, colGap = 40 } = g
  const mainX = pad
  const subX = pad + nodeW + gapG
  const list = items || []
  const cols = list.length > 8 ? 2 : 1
  const perCol = list.length ? Math.ceil(list.length / cols) : 1
  const colH = n => Math.max(n, 1) * (rowH + rowGap) - rowGap
  const H = colH(perCol) + pad * 2
  const mainY = Math.round(H / 2 - rowH / 2)
  const nodes = list.map((it, i) => {
    const col = Math.floor(i / perCol)
    const row = i % perCol
    const count = Math.min(perCol, list.length - col * perCol)
    // 末列不满时在列内垂直居中，与主节点视觉对称
    const colTop = pad + Math.round((colH(perCol) - colH(count)) / 2)
    return { ...it, x: subX + col * (subW + colGap), y: colTop + row * (rowH + rowGap) }
  })
  const x1 = mainX + nodeW
  const y1 = mainY + rowH / 2
  const edges = nodes.map(n => {
    // 失效引用画到占位符左缘中心（占位符是 26px 的小方块）
    const x2 = n.miss ? n.x + 13 : n.x
    const y2 = n.miss ? y1 : n.y + rowH / 2
    const mx = x1 + (x2 - x1) / 2
    return {
      key: String(n.agent ? n.agent.id : n.key),
      miss: !!n.miss,
      d: `M ${x1} ${y1} C ${mx} ${y1}, ${mx} ${y2}, ${x2} ${y2}`
    }
  })
  const W = subX + (cols - 1) * (subW + colGap) + subW + pad
  return { W, H, mainX, mainY, nodes, edges }
}
