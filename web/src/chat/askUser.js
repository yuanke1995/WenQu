// 提问卡（askUser）的纯投影：工具入参 questions + result 答案 → 一张卡要渲染的数据。
// 入参 args 是 JSON 字符串（可能被截断）；result 单问是纯文本，多问是与问题下标对齐的 JSON 数组。

const UNANSWERED_PREFIX = '（用户未回答这一题'
/** 后端对「这题没答」写入的固定前缀：界面统一显示「未作答」，不把整句系统说明当答案。 */
export const askAnswerLabel = a => (typeof a === 'string' && a.startsWith(UNANSWERED_PREFIX)) ? '未作答' : a
/** 整条被全角括号包住 ⇒ 后端写给模型的系统说明，不是用户敲的答案。 */
export const askNoticeText = s => typeof s === 'string' && s.length > 1
  && s.startsWith('（') && s.endsWith('）') && !s.startsWith(UNANSWERED_PREFIX)

export const askUserView = t => {
  let questions = []
  try {
    const j = JSON.parse(t?.args || 'null')
    if (j && Array.isArray(j.questions)) {
      questions = j.questions.map(x => ({
        topic: x.topic || '',
        question: x.question || '',
        options: Array.isArray(x.options) ? x.options : null,
        multi: x.multi === true || String(x.multi) === 'true'
      }))
    }
  } catch (e) { /* 入参截断：仅显示答案 */ }
  const raw = (t && t.result) || ''
  let answers = null
  try { const p = JSON.parse(raw); if (Array.isArray(p)) answers = p } catch (e) { /* 非数组：单问退化为纯文本 */ }
  if (!questions.length) {
    return { topic: '', question: '', options: null, answer: askAnswerLabel(raw), notice: true }
  }
  // ① 没发出去的卡：result 是说明文本，按单条展示（问句压根没问过，不配一行「问」）
  if (!answers) {
    if (questions.length === 1 && !askNoticeText(raw)) answers = [raw]
    else return { topic: '', question: '', options: null, answer: raw, notice: true }
  }
  // ② 截断过的历史数据：只渲染有答案的部分
  const list = questions.slice(0, answers.length).map((q, i) => ({
    topic: q.topic, question: q.question, options: q.options, multi: q.multi,
    answer: askAnswerLabel(answers[i])
  }))
  // many=一卡多问；multi=一题多选。两个词各管一件事
  return { many: true, questions: list }
}

/** 这一题是否已答：勾了候选或开了自定义并填了字。 */
export const askQuestionAnswered = (a, i) => !!a && (((a.picks[i] || []).length > 0) || !!a.customOn[i])

/** 已答题数（提交按钮的「N / M」）。 */
export const askAnsweredCount = a => (a && a.questions ? a.questions.filter((q, i) => askQuestionAnswered(a, i)).length : 0)

/** 下一道未答题下标（先往后找再从头，全答完 -1）。 */
export const askNextUnanswered = (a, page) => {
  if (!a) return -1
  const later = a.questions.findIndex((q, i) => i > page && !askQuestionAnswered(a, i))
  if (later >= 0) return later
  return a.questions.findIndex((q, i) => !askQuestionAnswered(a, i))
}

/** 某题送模型的答案：勾中的候选按题面原样取出，自定义文本并到末尾，用「；」连接。 */
export const askAnswerText = (a, i) => {
  if (!a) return ''
  const q = a.questions[i] || {}
  const opts = q.options || []
  const parts = (a.picks[i] || []).slice().sort((x, y) => x - y).map(oi => opts[oi]).filter(Boolean)
  const c = (a.customs[i] || '').trim()
  if (a.customOn[i] && c) parts.push(c)
  return parts.join('；')
}

/** 全部答完可自动提交只在整卡单选时成立（多选没有「勾完了」这一刻）。 */
export const askAutoSubmittable = a => !!a && a.questions.length > 0
  && a.questions.every((q, i) => !q.multi && askQuestionAnswered(a, i))

/** 点选某题候选：单选压成一项并清自定义；多选 toggle 且与自定义并存。 */
export function pickAskOption (m, page, oi) {
  const a = m && m.ask
  if (!a || a.busy || a.answered) return
  const q = a.questions[page]
  const opts = (q && q.options) || []
  if (!q || oi == null || oi < 0 || oi >= opts.length) return
  const cur = a.picks[page] || []
  if (q.multi) {
    a.picks[page] = cur.includes(oi) ? cur.filter(x => x !== oi)
      : [...cur, oi].sort((x, y) => x - y)
    return
  }
  a.picks[page] = [oi]
  a.customs[page] = ''
  a.customOn[page] = false
}

/** 确认自定义答案：单选有文本即算答并清候选；多选文本非空则与候选并存。 */
export function commitAskCustom (m, page) {
  const a = m && m.ask
  if (!a || a.busy || a.answered) return
  const q = a.questions[page]
  const t = (a.customs[page] || '').trim()
  if (q && q.multi) { a.customOn[page] = !!t; return }
  if (t) { a.picks[page] = []; a.customOn[page] = true }
  else if (a.customOn[page]) a.customOn[page] = false
}

export const askOptionPicked = (a, page, oi) => !!(a && Array.isArray(a.picks[page]) && a.picks[page].includes(oi))
export const askCustomPicked = (a, page) => !!(a && a.customOn[page])

/** 面板底部提示：说清本题怎么选、整卡何时送出，收尾统一「没答的题不替你选」。 */
export const askHintText = (a, page) => {
  if (!a) return ''
  if (a.continued) return '这一轮已经先往下做了；现在作答会作为一条用户消息发给它'
  const qs = a.questions || []
  const cur = qs[page]
  if (cur && cur.multi) return '可勾多个，再点一次取消；没答的题不替你选'
  if (qs.some(q => q && q.multi)) return '选完自动跳下一题；卡里有可多选的题，答完要点提交；没答的题不替你选'
  return '选完自动跳下一题，全部选完自动提交；没答的题不替你选'
}

/**
 * 迟到作答发出去的那条用户消息（DSH {@code answer_to_pending_question} 同形）：
 * 模型侧拿到的是一段紧凑 JSON，带本次调用号与原题；界面侧由 {@link pendingAnswerText} 配对展示。
 * <p>每题一项、与问题下标对齐；没答的那题 selected 为空＝用户跳过（DSH 的 skip 语义）。
 */
export const continuedAnswerPayload = a => {
  if (!a || !Array.isArray(a.questions)) return ''
  return JSON.stringify({
    kind: 'answer_to_pending_question',
    tool: 'askUser',
    callId: a.id,
    questions: a.questions.map(q => ({
      question: q.question || '',
      ...(Array.isArray(q.options) && q.options.length ? { options: q.options } : {})
    })),
    answers: a.questions.map((q, i) => ({ selected: askAnswerText(a, i) }))
  })
}

/** 用户气泡里那条迟到作答的展示文案；不是这种消息就返回 null（调用方照旧显示原文）。 */
export const pendingAnswerText = content => {
  if (typeof content !== 'string' || !content.startsWith('{')) return null
  let j = null
  try { j = JSON.parse(content) } catch (e) { return null }
  if (!j || j.kind !== 'answer_to_pending_question' || !Array.isArray(j.answers)) return null
  const qs = Array.isArray(j.questions) ? j.questions : []
  const lines = j.answers.map((ans, i) => {
    const q = (qs[i] && qs[i].question) || ''
    const v = String((ans && ans.selected) || '').trim()
    return `「${q}」：${v || '（跳过）'}`
  })
  return '回答刚才的问题：\n' + lines.join('\n')
}
