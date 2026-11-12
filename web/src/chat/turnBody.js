// 本轮正文流的分段：正文切片 + 插话位置。纯函数，不读 .value。

export const turnAnswerText = m => String((m && m.content) || '')

/** 本轮插话段清单：计划卡「本轮调整」用（要清单，不要位置）。 */
export const turnSteerRows = m => {
  const tl = Array.isArray(m && m.timeline) ? m.timeline : []
  return tl.filter(s => s && s.kind === 'steer' && String(s.text || '').trim())
    .map(s => ({ kind: 'steer', text: String(s.text), messageId: s.messageId || '' }))
}

/**
 * 正文流按时间线还原插话位置：后端在插话被写进上下文前先 flush 正文成 text 段、再插 steer 段，
 * 所以 text 段的 to 就是「这句话被听到前模型已写出多少正文」，遇到 steer 就在这个下标下刀。
 * 没有插话只返回一整片；切片拼起来仍等于 content。key 用切点下标，流式最后一片起点不变。
 */
export const turnBodyRows = m => {
  const content = String((m && m.content) || '')
  const tl = Array.isArray(m && m.timeline) ? m.timeline : []
  const cuts = []
  let read = 0
  for (const seg of tl) {
    if (!seg) continue
    if (seg.kind === 'text') {
      const to = Number(seg.to) || 0
      if (to > read) read = Math.min(to, content.length)
    } else if (seg.kind === 'steer' && String(seg.text || '').trim()) {
      cuts.push({ at: read, text: String(seg.text), messageId: seg.messageId || '' })
    }
  }
  if (!cuts.length) return content ? [{ kind: 'text', key: 't0', content, last: true }] : []
  const rows = []
  let pos = 0
  cuts.forEach((c, ci) => {
    const at = Math.min(Math.max(c.at, pos), content.length)
    if (at > pos) rows.push({ kind: 'text', key: 't' + pos, content: content.slice(pos, at) })
    rows.push({ kind: 'steer', key: 's' + ci + ':' + (c.messageId || ''), text: c.text })
    pos = at
  })
  if (pos < content.length) rows.push({ kind: 'text', key: 't' + pos, content: content.slice(pos) })
  for (let i = rows.length - 1; i >= 0; i--) {
    if (rows[i].kind === 'text') { rows[i].last = true; break }
  }
  return rows
}
