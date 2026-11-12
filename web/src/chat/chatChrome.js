// 聊天页外壳的纯判据：输入框 chip 键与描述、占位文案、锚点文案、右栏开合、
// 全局键盘意图、浮层命中与列表映射。响应式状态与副作用在 useChatChrome.js。

export const GOAL_KEY = 'goal'
export const OVERLAY_ROOT_CLASSES = ['ant-modal-root', 'ant-dropdown']
export const MENTION_TIP = {
  kb: '本轮检索收窄到该知识库',
  agent: '本轮会强制咨询这位助手：它带着自己的知识库查一遍，回答仍由当前助手汇总',
  doc: '该文档内容直接带入本轮上下文'
}

export const fileKeyOf = f => f?.chipKey || ''
export const imgKeyOf = p => p?.chipKey || ''
export const menKeyOf = m => `m:${m.type}:${m.id}`
export const skillKeyOf = n => `s:${n}`
export const histKeyOf = h => `h:${h.messageId}`

/** 本轮引用反馈要回填的问题：向上找最近一条用户提问，没有则空串。 */
export const questionForIndex = (list, i) => {
  const msgs = list || []
  for (let j = i - 1; j >= 0; j--) {
    const m = msgs[j]
    if (m && m.role === 'user' && m.content) return m.content
  }
  return ''
}

/** 右栏「过程」卡取最近一条 AI 消息，含跑轮中 loading 的那条。 */
export const processMsgOf = list => {
  const msgs = list || []
  for (let i = msgs.length - 1; i >= 0; i--) if (msgs[i].role === 'ai') return msgs[i]
  return null
}

/** 主输入框占位文案：生成中的插话、目标待发、窄屏不提示 Enter/Shift+Enter。 */
export const composerPlaceholderOf = ({ loading, goalTurnStreaming, isNarrow, goalArmed, hasSessionGoal }) => {
  if (loading) {
    if (goalTurnStreaming) return '插话：先回答你这句，目标随后自动继续'
    return isNarrow
      ? '补一句方向（先排到上面，点「立即发送」才送出）'
      : '补一句方向（Enter 先排到上面 · ⌘/Ctrl+Enter 立刻插进本轮）'
  }
  if (goalArmed && !hasSessionGoal) return '写下目标，回车开始（助手会自己接着往下推进）'
  return isNarrow
    ? '问点什么？（@ 引用资料 · / 快捷命令）'
    : '问点什么？Enter 发送，Shift+Enter 换行（@ 引用资料，/ 快捷命令，# 引用历史问答）'
}

/** 右栏锚点预览卡两行：问题截一行、回答摘录；截断口径由调用方注入。 */
export const anchorQOf = (a, oneLine = (s => s)) => oneLine(a.q, 64) || '（随行图片 / 附件提问）'
export const anchorAOf = (a, oneLine = (s => s)) =>
  a.loading ? '回答生成中…' : (a.hasAi ? oneLine(a.a, 120) : '')

/** 右栏是否展开：窄屏一律收起，宽屏按存储位。 */
export const panelOpenOf = (isNarrow, stored) => !isNarrow && !!stored
/** 未显式关过右栏、且视口够宽时默认展开。 */
export const panelStoredOf = (raw, innerWidth) => raw !== '0' && innerWidth > 1200

/** 事件路径命中挂在 body 上的浮层：这些层自己吃掉的那一下不算点了别处。 */
export const hitsOverlayPath = (path, classes = OVERLAY_ROOT_CLASSES) =>
  (path || []).some(n => n.classList && classes.some(c => n.classList.contains(c)))

/** 事件路径命中面板/输入卡/行内编辑区：这些都算点在面板内，不收起。 */
export const inPanelPath = path => (path || []).some(n => n && n.classList
  && (n.classList.contains('mention-panel') || n.classList.contains('ci-box')
      || n.classList.contains('msg-inline-edit')))

/** 全局 Esc 意图：输入法组合与灯箱方向键放行；生成中两段式停止，空闲时清输入或失焦。 */
export const globalKeydownIntent = (e, { loading, previewOpen, escArmed, composerFocused, hasText }) => {
  if (e.isComposing || e.keyCode === 229) return 'ignore'
  if (previewOpen && ['Escape', 'ArrowLeft', 'ArrowRight'].includes(e.key)) return 'ignore'
  if (e.key !== 'Escape') return 'ignore'
  if (loading) return escArmed ? 'stop' : 'arm'
  if (!composerFocused) return 'ignore'
  return hasText ? 'clear' : 'blur'
}

/** 数组里应有的 chip 键集合（集合语义，顺序由编辑区自己保持）。 */
export const desiredChipKeys = ({ goalModeOn, goalArmed, hasSessionGoal,
                                 files = [], images = [], mentions = [], skills = [], histRefs = [] }) => [
  ...(goalModeOn && goalArmed && !hasSessionGoal ? [GOAL_KEY] : []),
  ...files.map(fileKeyOf),
  ...images.map(imgKeyOf),
  ...mentions.map(menKeyOf),
  ...skills.map(skillKeyOf),
  ...histRefs.map(histKeyOf)
].filter(Boolean)

/** key → 画一颗 chip 所需的全部信息；随行数组与格式化口径以参数注入。 */
export const chipDescriptorOf = (key, state, deps = {}) => {
  const { isPastedText = () => false, pasteTitle = (n => n), pasteSub = () => '',
          fmtSize = () => '', histChipLabel = () => '' } = deps
  const { files = [], images = [], mentions = [], skills = [], histRefs = [] } = state
  if (!key) return null
  if (key === GOAL_KEY) {
    return {
      kind: 'goal', icon: 'aim', name: '深度执行', head: true,
      title: '深度执行：写下任务，回车开始（先出计划，助手会自己接着往下推进）',
      delTitle: '退出深度执行'
    }
  }
  if (/^f\d+$/.test(key)) {
    const f = files.find(x => fileKeyOf(x) === key)
    if (!f) return null
    const paste = isPastedText(f)
    return {
      kind: 'file', icon: 'file', paste,
      name: paste ? pasteTitle(f.name) : f.name,
      sub: paste ? pasteSub(f) : (f.uploading ? '上传中…' : (f.error ? '上传失败' : fmtSize(f.size))),
      state: f.uploading ? 'uploading' : (f.error ? 'err' : (f.expired ? 'expired' : '')),
      title: f.error || (paste ? '点击查看粘贴的全文' : f.name),
      delTitle: '移除该附件',
      fileId: f.fileId || '', mime: f.mime || '', size: f.size || 0
    }
  }
  if (/^p\d+$/.test(key)) {
    const p = images.find(x => imgKeyOf(x) === key)
    if (!p) return null
    return { kind: 'img', icon: 'img', name: '', thumb: p.dataUrl,
             title: '待发送图片（悬浮可看大图）', delTitle: '移除该图片' }
  }
  if (key.startsWith('m:')) {
    const mm = mentions.find(x => menKeyOf(x) === key)
    if (!mm) return null
    return {
      kind: 'mention', mod: mm.type, icon: mm.type === 'doc' ? 'mention' : mm.type,
      name: mm.name || mm.id,
      title: MENTION_TIP[mm.type] || '该引用只对本轮生效', delTitle: '移除该引用',
      mtype: mm.type, id: mm.id
    }
  }
  if (key.startsWith('s:')) {
    const n = key.slice(2)
    if (!skills.includes(n)) return null
    return { kind: 'skill', name: n, avatar: n.slice(0, 1),
             title: '本轮带上这个技能', delTitle: '移除该技能' }
  }
  if (key.startsWith('h:')) {
    const h = histRefs.find(x => histKeyOf(x) === key)
    if (!h) return null
    return {
      kind: 'hist', icon: 'hist', name: histChipLabel(h) || h.messageId,
      title: '作为本轮上下文带给模型', delTitle: '移除该引用', messageId: h.messageId
    }
  }
  return null
}

/** DOM 报回顺序 → 需要剪掉的键；深度执行 chip 被删即退出该模式。 */
export const prunedChipKeys = (keys, inlineKeys) => {
  const keep = new Set(keys)
  const removed = (inlineKeys || []).filter(k => !keep.has(k))
  return { removed, goalOff: removed.includes(GOAL_KEY) }
}
