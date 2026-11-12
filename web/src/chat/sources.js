// 引用来源的展示口径：分组、展示名、分值标签。入参是后端/流式下发的来源对象，不读 .value。
// 右栏、H5 详情与分享页共用同一份，避免同一条来源在两端说出两个名字。

/** 外部来源（联网/MCP）单独成组，名称与知识库文档区分——它们没有 fileName/docId。 */
export const externalOrigin = s => s.origin === 'WEB' || s.origin === 'MCP'

export const groupSources = sources => {
  const groups = []
  const byKey = new Map()
  const groupOf = s => {
    if (s.origin === 'WEB') return { key: '__web', fileName: '联网来源', icon: 'web' }
    if (s.origin === 'MCP') return { key: '__mcp', fileName: 'MCP 来源', icon: 'mcp' }
    return {
      key: s.docId || '__manual_' + (s.fileName || 'x'),
      fileName: s.fileName || (s.docId ? '来源文档不可用' : '手动补充的知识'),
      icon: 'doc'
    }
  }
  for (const s of sources || []) {
    const gi = groupOf(s)
    let g = byKey.get(gi.key)
    if (!g) {
      g = { key: gi.key, fileName: gi.fileName, icon: gi.icon, items: [] }
      byKey.set(gi.key, g)
      groups.push(g)
    }
    g.items.push(s)
  }
  return groups
}

/** 来源条目展示名：外部来源用站点名，库内来源用文件名；有标题时追加「§ 标题」。 */
export const sourceName = s => externalOrigin(s)
  ? (s.siteName || (s.origin === 'MCP' ? 'MCP 来源' : '联网来源')) + (s.title ? ' §' + s.title : '')
  : (s.fileName || (s.docId ? '来源文档不可用' : '手动补充的知识')) + (s.title ? ' §' + s.title : '')

/** 分值展示：重排分优先，没有就退回原始分；非法值不出数字。 */
export const fmtSourceScore = s => {
  const v = s ? (s.rerankScore != null ? s.rerankScore : s.score) : null
  return (v == null || isNaN(Number(v))) ? '' : Number(v).toFixed(2)
}

/** 分值口径：重排相关度 > 服务商相关度（WEB）> 检索融合分（库内）；MCP 无相关度分。 */
export const scoreTitle = s => {
  if (!s) return ''
  if (s.rerankScore != null) return '重排相关度 ' + s.rerankScore
  if (s.origin === 'WEB') return '服务商相关度 ' + s.score
  if (s.origin === 'MCP') return ''
  return '检索融合分 ' + s.score
}
