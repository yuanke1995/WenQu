// 会话动作的纯判据：拖动落点、动作分派、分享态、技能表单、键盘意图与调试阶段投影。
// 无 Vue 与副作用依赖；视图状态、提示与引擎调用在 useChatActions.js。

/** 拖动落点：起点与落点都有效且不同才算一次移动。 */
export const steerDropTarget = (from, to) =>
  (from >= 0 && to >= 0 && from !== to) ? { from, to } : null

/** 插话载荷摘要：只标出这一条带了哪些随行材料。 */
export const steerExtrasText = s => {
  if (!s) return ''
  const bits = []
  if (s.images?.length) bits.push(`${s.images.length} 张图`)
  if (s.atts?.length) bits.push(`+${s.atts.length} 个附件`)
  if (s.skills?.length) bits.push(`技能 ×${s.skills.length}`)
  if (s.mentions?.length) bits.push(`@ ${s.mentions.length}`)
  if (s.historyRefs?.length) bits.push(`# ${s.historyRefs.length}`)
  if (s.planMode) bits.push('先出计划')
  return bits.join(' · ')
}

/** 引用来源折叠行后缀：仅当命中数与引用条数不同才补，避免重复读数。 */
export const hitsNoteText = m => {
  const t = m && m.tokens
  const r = m && m.retrieved
  if (!r || !t || t.hits == null || t.hits <= 0 || t.hits === r.refs) return ''
  return `（${t.hits} 段填入上下文）`
}

/** 向 mi 之前配对最近的用户提问；stopAtAnswer 时遇更早回答即停（同轮归属）。 */
export const findQuestion = (messages, mi, stopAtAnswer = true) => {
  for (let i = mi - 1; i >= 0; i--) {
    const m = messages[i]
    if (!m) continue
    if (m.role === 'user') return m
    if (stopAtAnswer && (m.role === 'assistant' || m.role === 'ai')) return null
  }
  return null
}

/** 本轮可固化的知识块引用：按 knowledgeId 去重、去空。 */
export const evalKnowledgeIds = m =>
  [...new Set((m?.sources || []).map(s => s.knowledgeId).filter(Boolean))]

/** 更多动作分派：把菜单 key 解析成待执行的动作描述，副作用留给调用方。 */
export const moreActionPlan = (key, mi, messages) => {
  if (key === 'debug') return { kind: 'debug', mi }
  if (key === 'addEval') return { kind: 'addEval', mi }
  if (key === 'deleteRound') return { kind: 'deleteRound', mi }
  if (key === 'export') return { kind: 'export', mi }
  if (key === 'events') {
    const ai = messages[mi]
    const q = findQuestion(messages, mi)
    return { kind: 'events', turnId: ai?.turnId, ai, question: q?.content || '' }
  }
  return null
}

/** 顶栏分享图标激活态与侧栏会话行同源：只认本会话且 shared 为真。 */
export const isSessionShared = (list, id) =>
  (list || []).some(s => s.id === id && !!s.shared)

/** 技能名必填（去空白后）。 */
export const solidifyNameValid = name => !!String(name || '').trim()

/** 技能名：汉字/字母/数字/下划线/连字符，≤64；剥空退回带时刻的兜底名。 */
export const solidifySlug = (q, now = Date.now) => {
  const s = String(q || '').replace(/[^一-鿿A-Za-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 64)
  return s || ('chat-skill-' + now().toString(36))
}

/** 技能正文骨架：预填适用场景与做法占位，改两行即可复用。 */
export const buildSolidifyContent = question => '## 适用场景\n' + question +
  '\n\n## 做法\n1. （把上面回答的关键步骤整理成可复用做法）\n2. …\n\n## 输出要求\n…'

/** 固化表单预填：名、描述、正文骨架。 */
export const seedSolidifyForm = (question, now = Date.now) => {
  const q = String(question || '')
  return {
    name: solidifySlug(q, now),
    description: q.slice(0, 80),
    content: buildSolidifyContent(q)
  }
}

/** 创建技能的请求载荷：固定归入「通用」分类。 */
export const solidifyPayload = form => ({
  name: String(form.name || '').trim(),
  description: String(form.description || '').trim(),
  content: form.content || '',
  category: '通用'
})

/** 查找热键意图：Ctrl/⌘+F 开或聚焦，Esc 在查找开着时关闭。 */
export const searchHotkeyIntent = (e, searchOpen) => {
  if ((e.ctrlKey || e.metaKey) && (e.key === 'f' || e.key === 'F')) return searchOpen ? 'focus' : 'open'
  if (e.key === 'Escape' && searchOpen) return 'close'
  return null
}

/** 全局 Esc 兜底：候选面板开着、且焦点不在弹窗或面板内时收掉面板。 */
export const docEscShouldClose = (e, { panelsOpen, overlayHit, path, isHTMLElement }) => {
  if (e.key !== 'Escape' || e.isComposing || e.keyCode === 229) return false
  if (!panelsOpen) return false
  if (overlayHit(path)) return false
  return !path.some(n => isHTMLElement(n) &&
    (n.closest?.('.mention-panel') || n.closest?.('.ci-box')))
}

/** 面板键盘导航意图：返回待执行动作；Enter 是否消费由调用方确认候选后决定。 */
export const panelNavIntent = (e, { addOpen, addTab, addFromRoot, addHi }) => {
  if (e.isComposing || e.keyCode === 229) return null
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') return { kind: 'move', delta: e.key === 'ArrowDown' ? 1 : -1 }
  if (e.key === 'Escape') {
    if (addOpen && addTab !== 'root' && addFromRoot) return { kind: 'back' }
    return { kind: 'close' }
  }
  if (e.key === 'Tab' && addOpen) {
    if (addTab === 'root') return { kind: 'confirmAdd', hi: addHi }
    return { kind: 'back' }
  }
  if (e.key === 'Enter' && !e.shiftKey) return { kind: 'confirmHi' }
  return null
}

/** @ / # / 唤起面板：触发位置限行首或空白/开括号后，避免邮箱与 URL 误开。 */
export const panelTriggerChar = (e, before) => {
  if (e.key !== '@' && e.key !== '#' && e.key !== '/') return null
  if (e.ctrlKey || e.metaKey || e.altKey) return null
  if (before && !/[\s（(【[]$/.test(before)) return null
  return e.key
}

/** 调试阶段投影：把检索结果按命中链路铺成六个阶段，每项补齐标题与标签。 */
export const projectDebugStages = d => {
  if (!d) return []
  const map = (items, tagFn) => (items || []).map(it => ({
    title: it.title || '（无标题）',
    docName: it.docName || '',
    snippet: it.snippet || '',
    tag: tagFn(it)
  }))
  return [
    { name: `关键词命中（${(d.keywordHits || []).length}）`, items: map(d.keywordHits, it => '命中率 ' + (it.hitRate ?? 0)) },
    { name: `向量命中（${(d.vectorHits || []).length}）`, items: map(d.vectorHits, it => '相似度 ' + (it.score ?? 0)) },
    { name: `合并后（${(d.merged || []).length}）`, items: map(d.merged, it => '分 ' + (it.score ?? 0)) },
    { name: `重排后（${(d.reranked || []).length}）` + (d.rerankApplied ? '' : `（${d.rerankSkipReason || '未重排'}）`), items: map(d.reranked, it => '分 ' + (it.score ?? 0)) },
    { name: `最终上下文（${(d.finalContext || []).length}/8 · 门拦下 ${d.contextGate?.droppedByGate ?? 0} 块）`, items: map(d.finalContext, it => '分 ' + (it.rerankScore ?? it.score ?? 0)) },
    { name: `被排除（${(d.excluded || []).length}）`, items: map(d.excluded, it => '分 ' + (it.score ?? 0)) }
  ]
}

/** 发送时的 /deep 兜底意图：无匹配放行、未开启提示、带任务立目标、无任务翻转上膛。 */
export const deepCommandIntent = (parsed, goalModeOn, goalArmed) => {
  if (!parsed || !parsed.matched) return { matched: false }
  if (!goalModeOn) return { matched: true, kind: 'unsupported' }
  if (parsed.task) return { matched: true, kind: 'start', task: parsed.task }
  return { matched: true, kind: 'toggleArm', armed: !goalArmed }
}
