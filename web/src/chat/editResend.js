// 编辑重发的纯判据：从原消息播种编辑卡、组装重发载荷、判定「空内容 / 未改动」、
// 随行材料的增删限额与键盘意图。依赖以参数注入，视图状态在 useEditResend.js。

/** 编辑卡的初始内容。附件按名字合并展示元数据与重发凭据，只剩展示项的标为不可重发。 */
export const seedEditRows = (m, deps) => {
  const { isPastedText, histItemDigest } = deps
  const metaByName = new Map((m.attachments || []).map(a => [a.name, a]))
  const rows = (m.attachData || []).map(a => {
    const meta = metaByName.get(a.name) || {}
    return { size: meta.size, paste: isPastedText(meta), text: meta.text, uploading: false, error: '', ...a }
  })
  const noHandle = (m.attachments || [])
    .filter(a => !rows.some(r => r.name === a.name))
    .map(a => ({ name: a.name, mime: a.mime, size: a.size, paste: isPastedText(a), text: a.text,
                 fileId: '', uploading: false, unavailable: true, error: '' }))
  return {
    atts: [...rows, ...noHandle],
    imgs: (m.images || []).map(u => ({ dataUrl: u })),
    mentions: [...(m.mentions || [])],
    histRefs: (m.historyRefs || []).filter(h => h && h.messageId)
      .map(h => ({ messageId: h.messageId, role: h.role, digest: histItemDigest(h) })),
    skills: Array.isArray(m.skills) ? [...m.skills] : []
  }
}

const sameBy = (a, b, eq) => a.length === b.length && a.every((x, k) => eq(x, b[k]))

/**
 * 重发载荷与两道判据。
 * · 只有 data: 图片能重发；抓取失败仍以 URL 留在编辑卡，不废掉其它已抓好的图。
 * · 失效附件（fileId 已被清理）不进载荷，但还留在卡里就不算「内容为空」。
 * · 文本/图片/附件/@ 引用/# 引用/技能任一变化才算改动，未改动直接收起卡片。
 */
export const editResendPayload = (state, old, now = Date.now) => {
  const txt = String(state.text || '').trim()
  const okAtts = state.atts.filter(f => f.fileId && !f.error)
  const atts = okAtts.map(f => ({ name: f.name, mime: f.mime, fileId: f.fileId }))
  const attsMeta = okAtts.map(f => ({ name: f.name, mime: f.mime, size: f.size, paste: !!f.paste, text: f.text }))
  const imgsAll = state.imgs.map(p => p.dataUrl)
  const imgs = imgsAll.filter(u => String(u || '').startsWith('data:'))
  const mentions = [...state.mentions]
  const histRefs = state.histRefs.map(h => h.messageId)
  const skills = [...state.skills]
  const empty = !txt && !imgsAll.length && !atts.length && !state.atts.length
  const listOf = v => Array.isArray(v) ? v : []
  const unchanged = txt === old.content
    && sameBy(imgsAll, listOf(old.images), (x, y) => x === y)
    && sameBy(atts, listOf(old.attachData), (x, y) => x.name === y.name && x.mime === y.mime && x.fileId === y.fileId)
    && sameBy(mentions, listOf(old.mentions), (x, y) => x.type === y.type && x.id === y.id)
    && sameBy(histRefs, listOf(old.historyRefs).map(h => h.messageId), (x, y) => x === y)
    && sameBy(skills, listOf(old.skills), (x, y) => x === y)
  const variantCount = (old.variantCount || 1) + 1
  const newUser = {
    role: 'user', content: txt,
    images: imgsAll, attachments: attsMeta, attachData: atts,
    skills, mentions,
    historyRefs: listOf(old.historyRefs).filter(h => histRefs.includes(h.messageId)),
    deepThink: !!old.deepThink,
    time: now(), messageId: null,
    variantCount, variantIndex: variantCount
  }
  return { text: txt, atts, attsMeta, imgs, imgsAll, mentions, histRefs, skills,
           deep: !!old.deepThink, empty, unchanged, newUser }
}

/** 编辑重发的引擎调用实参：参数位固定，集中一处装配避免漏位。 */
export const editResendArgs = (payload, editMessageId, newUser) => [
  payload.text, payload.imgs, null, false, 1, payload.deep,
  payload.atts, payload.skills, payload.mentions, null,
  payload.histRefs.map(mid => ({ messageId: mid })), editMessageId, newUser
]

/** 编辑卡键盘：输入法组合中放行；「+」面板优先消费导航；Enter 重发、Esc 取消。 */
export const editKeydownIntent = (e, { panelOpen = false, panelNav = () => false } = {}) => {
  if (e.isComposing || e.keyCode === 229) return null
  if (panelOpen && panelNav(e)) return 'panel'
  if (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey && !e.metaKey && !e.altKey) return 'send'
  if (e.key === 'Escape') return 'cancel'
  return null
}

/** @ 引用增删：智能体委派是单主语，选中另一位即替换；其余类型受总数上限约束。 */
export const toggleMentionRows = (rows, type, item, max) => {
  let next = [...rows]
  const hit = next.findIndex(mm => mm.type === type && mm.id === item.id)
  if (hit >= 0) { next.splice(hit, 1); return { rows: next } }
  if (type === 'agent') next = next.filter(mm => mm.type !== 'agent')
  else if (next.length >= max) return { rows, error: `一次最多引用 ${max} 个知识库/文档/智能体` }
  next.push({ type, id: item.id,
              name: type === 'doc' ? item.fileName : item.name,
              kbId: type === 'doc' ? (item.kbId || '') : '' })
  return { rows: next }
}

/** # 历史引用增删：候选必须仍在本会话的池子里，摘要按候选重算。 */
export const toggleHistRefRows = (rows, m, pool, max, digest) => {
  if (rows.some(h => h.messageId === m.messageId)) {
    return { rows: rows.filter(h => h.messageId !== m.messageId) }
  }
  if (rows.length >= max) return { rows, error: `一次最多引用 ${max} 条历史消息` }
  const src = (pool || []).find(x => x.messageId === m.messageId)
  if (!src) return { rows }
  return { rows: [...rows, { messageId: m.messageId, role: src.role, digest: digest(src) }] }
}

/** 技能增删上限（与主输入框同规则：一次最多 3 个）。 */
export const addSkillRows = (rows, name, max = 3) => {
  if (rows.includes(name)) return { rows }
  if (rows.length >= max) return { rows, error: `一次最多选用 ${max} 个技能` }
  return { rows: [...rows, name] }
}
