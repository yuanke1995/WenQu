// 深度执行与智能体提问面板的纯判据：入口准入、正文选取、键盘意图、分页与选项投影、
// 倒计时三态、提交准入、压缩收起判据。引擎值与动作由调用方注入，视图状态在 useDeepAsk.js。

/** 深度执行入口是否可起：当前智能体允许，且本会话没有进行中的执行（终态后允许再起一条）。 */
export const goalEntryAllowed = (goalModeOn, sessionGoal, goalTerminal) =>
  !!goalModeOn && (!sessionGoal || !!goalTerminal)

/** 一句话创建取哪段正文：传入 prefill 用它，否则取输入框当前文本。 */
export const goalStartText = (prefill, text) =>
  String(prefill != null ? prefill : (text || '')).trim()

/** 就地编辑键位：输入法组合中放行；Enter（无修饰）保存，Esc 取消。 */
export const goalEditKeydownIntent = e => {
  if (e.isComposing || e.keyCode === 229) return null
  if (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey && !e.metaKey && !e.altKey) return 'save'
  if (e.key === 'Escape') return 'cancel'
  return null
}

/** 选项展示拆分：「关键词：说明」→ 关键词加粗、说明弱化；无冒号整句作关键词。 */
export const askOptionParts = op => {
  const s = String(op || '')
  const i = s.search(/[：:]/)
  return i > 0
    ? { kw: s.slice(0, i), rest: s.slice(i + 1).replace(/^[：:]\s*/, '') }
    : { kw: s, rest: '' }
}

const emptyQuestion = () => ({ topic: '', question: '', options: [], multi: false })

/** 当前展示的问题：页越界回退末题；无问题给空题占位，模板无需判空。 */
export const curAskAt = (a, page) => {
  if (!a || !a.questions || !a.questions.length) return emptyQuestion()
  const p = Math.min(page, a.questions.length - 1)
  return a.questions[p] || emptyQuestion()
}

const pad2 = n => String(n).padStart(2, '0')

/** 倒计时三态：剩余毫秒、是否已超时、mm:ss 文本。无 deadline 不计时；已答不显示文本、不算超时。 */
export const askTimeState = (a, now) => {
  if (!a || !a.deadline) return { remaining: 0, expired: false, text: '' }
  const remaining = Math.max(0, a.deadline - now)
  const expired = !a.answered && remaining <= 0
  const s = Math.ceil(remaining / 1000)
  const text = a.answered ? '' : pad2(Math.floor(s / 60)) + ':' + pad2(s % 60)
  return { remaining, expired, text }
}

/** 挂卡是否启心跳：有 deadline 才需要；归零后由收卡或提交停表。 */
export const askNeedsTicker = a => !!(a && a.deadline)

/** 整卡自动提交准入：未忙、未答、未过期，且引擎判定整卡可自动提交。 */
export const askAutoSubmitReady = (a, expired, submittable) =>
  !!(a && !a.busy && !a.answered && !expired && submittable)

/** 选完本题后落在哪一页：有下一道未答题就跳过去，否则停在末题等提交。 */
export const nextAskPage = (count, next) => next >= 0 ? next : count - 1

/** 点选项 / 提交自定义答案的统一准入：卡在、未忙、未答、未过期才受理。 */
export const askPickGuard = (m, expired) =>
  !!(m && m.ask && !m.ask.busy && !m.ask.answered && !expired)

/** 自定义答案确认后是否跳题：回车确认、本题单选、且引擎判定已作答才跳。 */
export const shouldAdvanceCustom = (q, doAdvance, answered) =>
  !!(doAdvance && q && !q.multi && (typeof answered === 'function' ? answered() : answered))

/** 提问面板键盘意图：移动高亮行、确认选项/自定义行，或 null 放行。 */
export const askPanelKeydownIntent = (e, state) => {
  const { busy, answered, expired, optionsCount, inInput,
          picks = [], customOn = false, hi = null } = state
  if (busy || answered || expired) return null
  const total = optionsCount + 1
  const last = customOn ? total - 1 : (picks.length ? picks[picks.length - 1] : -1)
  const cur = hi == null ? last : hi
  if (e.key === 'ArrowDown' || (e.key === 'Tab' && !e.shiftKey)) {
    return { type: 'move', hi: (cur + 1) % total, prevent: true }
  }
  if (e.key === 'ArrowUp' || (e.key === 'Tab' && e.shiftKey)) {
    return { type: 'move', hi: (cur - 1 + total) % total, prevent: true }
  }
  if (!inInput && (e.key === 'Enter' || e.key === ' ')) {
    if (hi == null) return { type: 'noop', prevent: true }
    return hi < optionsCount
      ? { type: 'pick', index: hi, prevent: true }
      : { type: 'custom', prevent: true }
  }
  return null
}

/** 自定义输入行回车：输入法选字的回车不算提交。 */
export const askCustomKeydownIntent = e => {
  if (e.isComposing || e.keyCode === 229) return null
  return e.key === 'Enter' ? 'commit' : null
}

/** 提问输入占位文案：窄屏省去按键提示。 */
export const askPanelPlaceholder = isNarrow =>
  isNarrow ? '输入你的回答…' : '输入你的回答…（Enter 确认）'

/** 重新压缩的要求文本：去掉首尾空白后交给引擎。 */
export const normalizeCsumInstr = v => String(v || '').trim()

/** 压缩成功后才收起输入行；失败保留原文方便改一句再试。 */
export const recompactShouldClose = ok => !!ok
