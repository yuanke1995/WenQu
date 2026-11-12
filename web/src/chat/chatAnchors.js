// 右栏锚点轨的数据源：每条提问 + 它之后的第一条回答（纯函数）。
export const buildQaAnchors = list => {
  const out = []
  for (let i = 0; i < (list || []).length; i++) {
    const m = list[i]
    if (!m || m.role !== 'user') continue
    let ai = null
    for (let j = i + 1; j < list.length; j++) { if (list[j].role === 'ai') { ai = list[j]; break } }
    out.push({ idx: i, q: m.content || '', a: ai ? (ai.content || '') : '', loading: !!(ai && ai.loading), hasAi: !!ai })
  }
  return out
}
