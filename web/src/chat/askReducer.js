// 提问卡 reducer（恢复态/实时共用）：构建卡片、挂卡排队、撤卡顶上。

export function buildAskCard (j, remainingMs) {
  const questions = Array.isArray(j.questions)
    ? j.questions.map(x => ({
      topic: x.topic || '',
      question: x.question || '',
      options: Array.isArray(x.options) ? x.options : [],
      // multi 缺字段=单选：显式 false 与不写两种写法在这里同形
      multi: x.multi === true || String(x.multi) === 'true'
    }))
    : []
  const n = questions.length
  const ms = Number(remainingMs != null ? remainingMs : j.timeoutMs) || 0
  return {
    id: j.askId,
    questions,
    timeoutMs: Number(j.timeoutMs) || 0,
    deadline: ms > 0 ? Date.now() + ms : 0,
    busy: false,
    answered: false,
    restored: !!j.restored,
    picks: questions.map(() => []),
    customs: new Array(n).fill(''),
    customOn: new Array(n).fill(false)
  }
}

/** 挂一张待答卡；同一轮可并行挂多张，位置被占就排队（按 askId 去重）。 */
export function attachAskCard (m, j, remainingMs) {
  if (!m || !j) return false
  const id = j.askId
  if (m.ask && m.ask.id === id) return false
  const q = Array.isArray(m.askQueue) ? m.askQueue : []
  if (id && q.some(x => x && x.id === id)) return false
  const card = buildAskCard(j, remainingMs)
  if (!m.ask) { m.ask = card } else { q.push(card); m.askQueue = q }
  return true
}

/** 撤下当前卡，让排队的下一张顶上；没有排队则清空。 */
export function retireAskCard (m) {
  if (!m) return
  m.ask = null
  const q = Array.isArray(m.askQueue) ? m.askQueue : null
  if (q && q.length) m.ask = q.shift()
}
