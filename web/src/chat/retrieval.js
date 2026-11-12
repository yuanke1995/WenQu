// 检索词的派生与检索状态行文案：纯函数，PC/H5 共用同一份判据。

/** 精确检索工具实际用过的检索词（模型可主动改词二次检索，与主链路 retrieved 不同源）。 */
export const toolSearchQueries = m => {
  if (!Array.isArray(m?.toolCalls)) return []
  const qs = []
  for (const t of m.toolCalls) {
    if (t.name !== 'searchKnowledge' || t.status === 'start') continue
    try {
      const q = JSON.parse(t.args || '{}').query
      if (q) qs.push(q)
    } catch (e) { /* args 非 JSON 时忽略 */ }
  }
  return qs
}

/** 检索状态行：关键词为 0 时不说（停用词滤净的 0 是噪音）；无检索但有精确检索词时给「精确检索」。 */
export const retrievalLineTitle = m => {
  const r = m?.retrieved || null
  const keywords = r ? (r.keywords || 0) : 0
  const refs = r ? (r.refs || 0) : (Array.isArray(m?.sources) ? m.sources.length : 0)
  let s = keywords > 0 ? `搜索 ${keywords} 个关键词` : ''
  if (refs > 0) s += (s ? '，' : '') + `参考 ${refs} 段资料`
  if (!s && toolSearchQueries(m).length) s = '精确检索'
  return s
}
