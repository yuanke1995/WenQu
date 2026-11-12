// 快捷命令 / 历史引用 / 「+」面板的纯判据：命令过滤、高亮导航、列表投影、
// 触发词剥离、提示文案与键盘意图。依赖以参数注入，视图状态在 useCommandPanels.js。

/** 命令可用的触发词：cmd 与中英文别名统一小写，供前缀补全与带参判定共用。 */
export const slashAliasesOf = c =>
  [c.cmd, ...(c.aliases || [])].filter(Boolean).map(a => String(a).toLowerCase())

/** 带参命令判定：别名打完整后再出现的空白，是给命令传任务而非结束筛选。 */
export const slashArgPending = (q, commands) => {
  const s = String(q || '').trimStart().toLowerCase()
  return commands.some(c => c.args && slashAliasesOf(c).some(a => s.startsWith(a + ' ')))
}

/** 命令参数：别名 + 空格之后的部分，按原文切片以保留大小写与用词。 */
export const slashArgsOf = (c, rawQuery) => {
  const raw = String(rawQuery || '').trim()
  const lower = raw.toLowerCase()
  for (const a of slashAliasesOf(c)) {
    if (lower.startsWith(a)) return raw.slice(a.length).trim()
  }
  return ''
}

/** / 命令过滤：别名前缀补全或「别名 + 空格 + 参数」命中，或名称/描述包含筛选词。 */
export const filterSlashCommands = (commands, q) => {
  const s = String(q || '').trim().toLowerCase()
  if (!s) return commands
  return commands.filter(c => {
    const aliases = slashAliasesOf(c)
    if (aliases.some(a => a.startsWith(s) || s.startsWith(a + ' '))) return true
    return String(c.name || '').toLowerCase().includes(s)
      || String(c.desc || '').toLowerCase().includes(s)
  })
}

/** 命令页过滤：名称/描述/别名任一包含筛选词即命中。 */
export const filterAddCommands = (commands, q) => {
  const s = String(q || '').trim().toLowerCase()
  if (!s) return commands
  return commands.filter(c =>
    `${c.name} ${c.desc || ''} ${slashAliasesOf(c).join(' ')}`.toLowerCase().includes(s))
}

/** 技能页过滤：名称或描述包含筛选词。 */
export const filterSkills = (skills, q) => {
  const s = String(q || '').trim().toLowerCase()
  if (!s) return skills
  return skills.filter(x => `${x.name || ''} ${x.description || ''}`.toLowerCase().includes(s))
}

/** 历史候选口径：内容筛选 + 最近在前 + 最多 60 条（防超长会话渲染卡顿）。 */
export const histListOf = (q, pool = []) => {
  const s = String(q || '').toLowerCase()
  const list = pool.filter(m => !s || String(m.content).toLowerCase().includes(s))
  return list.slice(-60).reverse()
}

/** 历史 chip 的摘要标签。 */
export const histChipLabel = h => (h && h.digest) || ''

/** 高亮下标循环移动；无候选时保持原值。 */
export const moveHi = (current, dir, n) => n ? (current + dir + n) % n : current

/** 触发片段摘除区间：从触发字符到 max(光标, 触发字符 + 1)。 */
export const triggerStripRange = (pos, caret) => [pos, Math.max(caret, pos + 1)]

/** 输入同步判据：光标离开触发符、触发符被改写、筛选词出现空白都要收起面板；
 *  带参命令（由 slashOpen + commands 判定）的空白是传参，不收起。返回 { close, q }。 */
export const panelQueryIntent = ({ text, pos, ch, caret, slashOpen = false, commands = [] }) => {
  if (caret <= pos || text[pos] !== ch) return { close: true, q: '' }
  const q = text.slice(pos + 1, caret)
  const allowArg = slashOpen && slashArgPending(q, commands)
  if (/\s/.test(q) && !allowArg) return { close: true, q }
  return { close: false, q }
}

/** 面板搜索框占位文案：按当前页签给一份词。 */
export const addSearchHintOf = tab => ({
  ref: '搜索知识库、文档、智能体或技能',
  hist: '筛选历史问答',
  cmd: '搜索命令'
}[tab] || '')

/** 底部操作提示：一级/二级页签、编辑态、引用子页签各自的导航与口径说明。 */
export const addFootHintOf = (tab, edit, mentionTab) => {
  if (tab === 'root') {
    const nav = '↑↓ 选择 · Enter 进入 · Tab 进/退层 · Esc 关闭'
    return nav + (edit ? '　|　文件图片也可以直接粘贴进来'
      : '　|　输入框可直接敲 @ 引用、# 历史、/ 命令；文件也可直接拖进来')
  }
  const nav = '↑↓ 选择 · Enter 确认 · Tab 返回 · Esc 关闭'
  const scope = edit ? '这一问' : '本轮'
  if (tab === 'ref') {
    if (mentionTab === 'skill') {
      return nav + `　|　选用的技能随${scope}带上`
        + (edit ? '（一次最多 3 个）' : '，下一轮自动取消（一次最多 3 个）')
    }
    return nav + `　|　引用只对${scope}生效：知识库 = 检索只在这些库里找；文档 = 内容直接带入上下文；智能体 = 强制咨询它（它带自己的知识库查一遍，回答仍由当前助手汇总）`
  }
  if (tab === 'hist') return nav + `　|　勾选的历史问答作为${scope}上下文带给模型（最多 10 条）`
  return nav + '　|　命令立即执行，计划、目标可跟一句任务'
}

/** 引用页当前子页签的候选投影；未知页签回落到文档列表。 */
export const addRefListOf = (mentionTab, { kb, doc, agent, skill }) =>
  ({ kb, agent, skill }[mentionTab] || doc)

/** 面板当前候选数投影。 */
export const addListSizeOf = (tab, counts) => counts[tab] || 0

/** 面板键盘意图：输入法组合中放行；↑↓ 移动、Esc 关闭/退回、Tab 进/退层、Enter 确认。 */
export const panelKeydownIntent = (e, { addOpen = false, addTab = 'root', addFromRoot = false } = {}) => {
  if (e.isComposing || e.keyCode === 229) return null
  if (e.key === 'ArrowDown') return 'down'
  if (e.key === 'ArrowUp') return 'up'
  if (e.key === 'Escape') return addOpen && addTab !== 'root' && addFromRoot ? 'back' : 'close'
  if (e.key === 'Tab' && addOpen) return addTab === 'root' ? 'tab-root' : 'tab-back'
  if (e.key === 'Enter' && !e.shiftKey) return 'enter'
  return null
}
