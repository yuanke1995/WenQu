// 过程段聚簇：思考/过程/工具连续游程合成一簇，正文段断簇。
// 纯函数，不读 .value；投影层只做转出。
export const CLUSTER_RUN = { think: 1, process: 1, group: 1 }
export const clusterize = out => {
  const res = []
  let run = []
  const flush = () => {
    if (!run.length) return
    if (run.length === 1 && run[0].kind !== 'group') res.push(run[0])
    else res.push({ kind: 'cluster', segs: run, key: 'c' + res.length })
    run = []
  }
  for (const seg of out) {
    if (seg && CLUSTER_RUN[seg.kind]) { run.push(seg); continue }
    flush()
    res.push(seg)
  }
  flush()
  return res
}

/** 簇内正在长的那一簇（仅末尾簇）标记 grow：流式期自动展开它，本轮结束自动收起，
 *  与 procOpen / thinkSegOpen 同款约定；用户点过就以用户为准（m._clOpen）。 */
export const markGrowingCluster = view => {
  for (const s of view) if (s.kind === 'cluster') s.grow = false
  const last = view[view.length - 1]
  if (last && last.kind === 'cluster') last.grow = true
  return view
}

export const clusterOpen = (m, cl) => {
  const marked = m && m._clOpen ? m._clOpen[cl.key] : undefined
  if (marked === true) return true
  if (marked === false) return false
  return !!(m && m.loading && cl.grow)
}
/** 这一簇是「自动开着」还是「用户点开」的：自动开着＝正在跑的这一簇、用户没碰过它。
 *  只有这一种情况下展开只给当前这一步（见 turnProcessRows）——跑着的时候簇会长到几十段，
 *  全展开就又退回聚簇前的那一排标题行，簇头等于白加。用户主动点开则照旧给全量。 */
export const clusterAutoOpen = (m, cl) => {
  const marked = m && m._clOpen ? m._clOpen[cl.key] : undefined
  return marked === undefined && !!(m && m.loading && cl.grow)
}
export const toggleCluster = (m, cl) => {
  if (!m) return
  if (!m._clOpen) m._clOpen = {}
  m._clOpen[cl.key] = !clusterOpen(m, cl)
}

/** 簇内工具（跨多个 group 汇总）：头部的操作数与状态图标都从这里派生 */
export const clusterTools = cl => {
  const out = []
  for (const s of cl?.segs || []) if (s && s.kind === 'group' && s.tools) out.push(...s.tools)
  return out
}
export const clusterRunning = cl => clusterTools(cl).some(t => t.status === 'start')
export const clusterHasError = cl => clusterTools(cl).some(t => t.status === 'error')
/** 簇内「说了几段」的计数：过程独白与思考段同属一类（一个工具都没跑时它是簇头唯一的信息量，
 *  只数独白会让纯思考簇报成「0 段说明」）。 */
export const clusterProcCount = cl => (cl?.segs || []).filter(s => s && (s.kind === 'process' || s.kind === 'think')).length

// ---- 簇头标题（DSH 的 processTitle 文案法则，问渠词表）----
/* 折叠簇头不再报「已执行 N 个操作」：数字要展开数一遍才有感，而罗列**做过什么**一眼就读完
 * （「已检索知识库并调用工具」「已完成分析，已检索知识库等」）。簇内的事归四类：
 * 检索（kb/检索词类工具）、联网（webSearch 一类出网搜索）、调用工具（其余工具）、分析（思考与过程独白）。 */
export const CLUSTER_KIND_DONE = { search: '已检索知识库', web: '已联网搜索', tools: '已调用工具', thinking: '已完成分析' }
export const CLUSTER_KIND_LIVE = { search: '正在检索知识库', web: '正在联网搜索', tools: '正在调用工具', thinking: '正在分析' }
export const clusterKindOfTool = t => {
  const s = String((t && (t.name || t.label)) || '')
  // 联网工具的内部名是 webSearch：不先认出来会落进下面的 search 分支，簇头误报成「检索知识库」
  if (/web_?search|联网|上网搜索/i.test(s)) return 'web'
  return /search|kb|检索|知识库|知识/i.test(s) ? 'search' : 'tools'
}
/** 簇内按发生顺序去重的类别表：罗列与簇头图标都按这个顺序取。 */
export const clusterKinds = cl => {
  const seen = []
  for (const s of cl?.segs || []) {
    if (!s) continue
    if (s.kind === 'think' || s.kind === 'process') { if (!seen.includes('thinking')) seen.push('thinking'); continue }
    if (s.kind === 'group') for (const t of s.tools || []) {
      const k = clusterKindOfTool(t)
      if (!seen.includes(k)) seen.push(k)
    }
  }
  return seen
}
/** 折叠态标题：1 类直说；2 类「A并B」（共享「已」字头时第二个省掉前缀，与 DSH 同一条法则）；
 *  3 类以上取前三个顿号罗列再收「等」。 */
export const clusterTitle = cl => {
  const kinds = clusterKinds(cl)
  if (!kinds.length) return CLUSTER_KIND_DONE.thinking
  if (kinds.length === 1) return CLUSTER_KIND_DONE[kinds[0]]
  if (kinds.length === 2) {
    const a = CLUSTER_KIND_DONE[kinds[0]]
    const b = CLUSTER_KIND_DONE[kinds[1]]
    return b.startsWith('已') ? a + '并' + b.slice(1) : a + '并' + b
  }
  return kinds.slice(0, 3).map(k => CLUSTER_KIND_DONE[k]).join('，') + '等'
}
/** 簇头的类别（定图标与跑动标题）：按簇内已出现的类别累计取第一个，只增不减。
 *  不再跟着「当前在跑的那把工具」走——读取文件一起一落就会在检索/工具之间来回切，簇头闪。 */
export const clusterHeadKind = cl => clusterKinds(cl)[0] || 'thinking'
/** 跑动中的标题（shimmer 那一句）；收尾等待期回退到折叠标题。
 *  进行中的这一簇（m.loading && cl.grow）恒亮「正在…」+ 扫光；类别按累计集合生成，
 *  只在「出现新类别」时变一次，不随每条工具起止在「正在检索知识库/正在调用工具」之间来回闪。
 *  整轮收尾（m.loading 置否）才回退到「已调用工具」。 */
export const clusterLiveText = (m, cl) => {
  if (!(clusterRunning(cl) || (m && m.loading && cl.grow))) return null
  const kinds = clusterKinds(cl)
  if (!kinds.length) return CLUSTER_KIND_LIVE.thinking
  if (kinds.length === 1) return CLUSTER_KIND_LIVE[kinds[0]]
  const live = kinds.slice(0, 2).map(k => CLUSTER_KIND_LIVE[k].replace(/^正在/, ''))
  return '正在' + live.join('并') + (kinds.length > 2 ? '等' : '')
}

/** 跑轮中帽体的轻量清单（一行一步）：自动开着的簇不再把全量工具卡摊到顶层（那会把页面撑长），
 *  改由簇分支就地渲染一个固定高度帽体，里面是这份轻量行——图标 + 动词 + 参数摘要，
 *  与右栏过程卡同一份口径（toolLabel/toolBrief/toolDuration）。思考/独白段不单独占行，
 *  并到它之后第一个工具行的展开详情里（与点开态的挂法一致）；还没换来工具的尾部思考自成一节
 *  （它正是「当前这一步」，不能因为还没有工具就看不见）。 */
export const clusterLiveRows = cl => {
  const rows = []
  let notes = null
  let n = 0
  for (const s of cl?.segs || []) {
    if (!s) continue
    if (s.kind === 'think' || s.kind === 'process') { (notes || (notes = [])).push(s); continue }
    if (s.kind === 'group') {
      for (const t of s.tools || []) {
        const row = { t, idx: ++n }
        if (notes) { row.notes = notes; notes = null }
        rows.push(row)
      }
    }
  }
  // 末尾还挂不出去的思考/独白（还没换来工具的那一段）：整组留着，行上按组渲染——
  // 同组里更早的那几段也要在这一行可见。
  if (notes) rows.push({ tailNotes: notes })
  return rows
}
