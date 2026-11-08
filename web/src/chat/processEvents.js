// 执行过程（事件账本）的展示口径。
// 账本里存的是「帧」，人要看的是「这一轮做了什么」——这里把帧翻成人话，
// 并把 usage / retrieved 这类累计快照合并成一个节点（它们不是多次调用）。
//
// 边界：只做展示投影，不改变后端契约、不参与智能体回答。合并掉的快照仍可展开看逐帧。
import { toolDuration, toolLabel, toolBrief } from './projections'

const LABELS = {
  tool: '工具', approval: '工具审批', approval_required: '工具待批准', ask_user: '提问卡',
  plan: '本轮步骤', plan_approval: '计划待批准', plan_cancelled: '计划未批准',
  plan_superseded: '计划被取代', artifact: '产物', subagent: '子智能体',
  subagent_route: '派遣路由', agent: '智能体', agent_delegated: '委派作答', agent_consulted: '智能体咨询',
  retrieved: '检索', usage: '用量', todo: '任务清单', error: '失败', warn: '提醒',
  steer: '中途插话', stop: '用户停止', interrupted: '中断收尾', detach: '断线中止',
  goal_state: '目标态', goal_turn: '目标续跑', goal_verify: '完成申请',
  goal_note: '目标进展', goal_outcome: '目标结果', done: '本轮结束'
}
const TONES = {
  tool: 'tool', retrieved: 'retr', usage: 'use', artifact: 'art',
  ask_user: 'card', approval: 'card', approval_required: 'card',
  plan: 'card', plan_approval: 'card', plan_cancelled: 'card', plan_superseded: 'card',
  subagent: 'sub', subagent_route: 'sub', agent: 'sub', agent_delegated: 'sub', agent_consulted: 'sub',
  error: 'err', warn: 'warn', stop: 'err', interrupted: 'warn', detach: 'warn',
  done: 'done'
}
const SNAPSHOT_TYPES = new Set(['usage', 'retrieved'])

const parse = v => {
  if (v && typeof v === 'object') return v
  if (typeof v !== 'string') return null
  try { return JSON.parse(v) } catch (e) { return null }
}
const num = v => {
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}
/** token 数的读法：2.2 万写成 22.3k，避免 22315 与 116106 混在一起读不出量级 */
const kNum = v => {
  const n = num(v)
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1).replace(/\.0$/, '') + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1).replace(/\.0$/, '') + 'k'
  return String(n)
}
const sizeText = v => {
  const n = num(v)
  if (!n) return ''
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1).replace(/\.0$/, '') + ' KB'
  return (n / 1024 / 1024).toFixed(1).replace(/\.0$/, '') + ' MB'
}
const oneLine = s => String(s == null ? '' : s).replace(/\s+/g, ' ').trim()
const isJsonLike = s => {
  if (typeof s !== 'string') return false
  const t = s.trim()
  return t.startsWith('{') || t.startsWith('[')
}
/** 通用兜底：JSON 不再原样铺给用户，挑一个最像「结论」的字段 */
function humanize (v) {
  if (v == null) return ''
  if (typeof v === 'string') {
    const p = parse(v)
    if (p != null) return humanize(p)
    // 半截 JSON（历史行被 240 字截断）：没有一个能读的字段，宁可明说也不把花括号铺给用户
    return isJsonLike(v) ? '原始记录（已截断）' : oneLine(v)
  }
  if (Array.isArray(v)) {
    if (!v.length) return '空'
    if (v.every(x => typeof x === 'string')) return oneLine(v.slice(0, 3).join(' · '))
    const first = humanize(v[0])
    return (first || '共 ' + v.length + ' 项') + (v.length > 1 ? ' 等 ' + v.length + ' 项' : '')
  }
  if (typeof v === 'object') {
    for (const k of ['question', 'label', 'name', 'title', 'filename', 'path',
      'summary', 'text', 'description', 'reason', 'message', 'error', 'objective']) {
      const x = v[k]
      if (typeof x === 'string' && x.trim()) return oneLine(x)
    }
    return Object.keys(v).length + ' 个字段'
  }
  return oneLine(v)
}

/** usage 帧 → 数值（兼容后端未结构化时的 summary JSON） */
function usageOf (e) {
  const o = parse(e && e.usage) || parse(e && e.summary) || {}
  const total = num(o.total) || (num(o.prompt) + num(o.output))
  return {
    prompt: num(o.prompt),
    output: num(o.output),
    finalOutput: num(o.finalOutput),
    cached: num(o.cached),
    cacheWrite: num(o.cacheWrite),
    reasoning: num(o.reasoning),
    context: num(o.context),
    window: num(o.window),
    hits: num(o.hits),
    total
  }
}
/** retrieved 帧 → 数值（keywords 是词数，terms 是词条；refs 是命中段数） */
function retrievalOf (e) {
  const o = parse(e && e.retrieval) || parse(e && e.summary) || {}
  const terms = Array.isArray(o.terms) ? o.terms.filter(x => typeof x === 'string' && x) : []
  return {
    keywords: num(o.keywords) || terms.length,
    refs: num(o.refs),
    terms
  }
}

function toolNode (e, step) {
  const name = e.name || ''
  const label = toolLabel(name, e.label)
  // 账本里的入参是摘要（可能被截断）。解析不出就不展示——完整入参在工具卡里，
  // 这里再铺半截 JSON 只会把「人话」又变回日志。
  const brief = e.argsBrief && parse(e.argsBrief) ? toolBrief({ name, args: e.argsBrief }) : ''
  const status = e.status || ''
  const bad = status === 'error'
  const denied = status === 'denied'
  return {
    kind: 'tool', tone: bad ? 'err' : (denied ? 'warn' : 'tool'),
    label: '工具', at: num(e.at), step,
    title: label || '工具',
    summary: brief,
    statusText: bad ? '失败' : (denied ? '被挡下' : ''),
    statusTone: bad ? 'err' : (denied ? 'warn' : ''),
    duration: e.elapsedMs ? toolDuration(num(e.elapsedMs)) : '',
    elapsedMs: num(e.elapsedMs),
    waitedMs: num(e.waitedMs)
  }
}

/** 节点属于哪些筛选键：一个失败的工具同时属于「工具」与「失败」 */
function nodeFilters (e) {
  switch (e.type) {
    case 'tool': return e.status === 'error' || e.status === 'denied' ? ['tool', 'err'] : ['tool']
    case 'retrieved': return ['retr']
    case 'usage': return ['use']
    case 'artifact': return ['art']
    case 'ask_user':
    case 'approval':
    case 'approval_required':
    case 'plan':
    case 'plan_approval':
    case 'plan_cancelled':
    case 'plan_superseded': return ['card']
    case 'error':
    case 'stop': return ['err']
    default: return ['other']
  }
}

function eventNode (e) {
  const type = e.type || ''
  const tone = TONES[type] || 'done'
  const label = LABELS[type] || type
  const at = num(e.at)
  if (type === 'subagent') {
    return { kind: 'subagent', tone, label, at, title: '', summary: oneLine(e.summary), event: e }
  }
  const data = parse(e.data) || parse(e.summary)
  if (type === 'artifact' && data && typeof data === 'object' && !Array.isArray(data)) {
    const name = oneLine(data.filename || data.name || data.path)
    const meta = [data.ext || data.preview, sizeText(data.size), oneLine(data.description)]
      .filter(Boolean).join(' · ')
    return { kind: 'event', tone, label, at, title: name || '产物', summary: meta }
  }
  if (type === 'ask_user') {
    const qs = (data && Array.isArray(data.questions)) ? data.questions : []
    const first = qs[0] || {}
    const q = oneLine(first.question || first.text || (data && data.question))
    return {
      kind: 'event', tone, label, at,
      title: oneLine(first.topic) || '向你提问',
      summary: q + (qs.length > 1 ? ' · 共 ' + qs.length + ' 题' : '')
    }
  }
  if (type === 'approval_required' || type === 'approval') {
    const who = oneLine((data && (data.label || data.tool)) || '')
    return { kind: 'event', tone, label, at, title: '等待你批准', summary: who }
  }
  if (type === 'plan' && Array.isArray(data)) {
    return { kind: 'event', tone, label, at, title: '计划 ' + data.length + ' 步', summary: humanize(data) }
  }
  if (type === 'plan_approval') {
    return { kind: 'event', tone, label, at, title: '计划待你批准', summary: '等待你的裁决' }
  }
  if (type === 'plan_cancelled' || type === 'plan_superseded') {
    return { kind: 'event', tone, label, at, title: label, summary: '' }
  }
  if (type === 'todo') {
    const items = Array.isArray(data && data.items) ? data.items : (Array.isArray(data) ? data : [])
    return { kind: 'event', tone, label, at, title: '任务清单', summary: items.length ? items.length + ' 项' : '' }
  }
  if (type === 'agent_delegated' || type === 'agent_consulted' || type === 'subagent_route') {
    const who = oneLine((data && (data.name || data.agentName || data.label)) || '')
    return { kind: 'event', tone, label, at, title: who || label, summary: '' }
  }
  if (type === 'done') {
    return { kind: 'event', tone, label, at, title: '本轮结束', summary: '' }
  }
  const text = isJsonLike(e.summary) ? humanize(data || e.summary) : oneLine(e.summary)
  return { kind: 'event', tone, label, at, title: label, summary: text }
}

function usageNode (events) {
  const latest = usageOf(events[events.length - 1])
  const hasNumbers = latest.prompt || latest.output || latest.total
  const frames = events.map(e => {
    const u = usageOf(e)
    return {
      at: num(e.at),
      text: (u.prompt || u.output || u.total)
        ? '输入 ' + kNum(u.prompt) + ' · 输出 ' + kNum(u.output)
          + ' · 缓存 ' + kNum(u.cached) + ' · 上下文 ' + kNum(u.context)
        : humanize(e.summary)
    }
  })
  return {
    kind: 'usage', tone: 'use', label: '用量', at: num(events[events.length - 1].at),
    title: hasNumbers ? '累计 ' + kNum(latest.total) : '用量',
    summary: hasNumbers
      ? '输入 ' + kNum(latest.prompt) + ' · 输出 ' + kNum(latest.output)
        + ' · 缓存 ' + kNum(latest.cached) + ' · 上下文 ' + kNum(latest.context)
        + (latest.window ? ' / ' + kNum(latest.window) : '')
      : humanize(events[events.length - 1].summary),
    frames,
    filters: ['use'],
    usage: hasNumbers ? latest : null
  }
}

function retrievalNode (events) {
  const latest = retrievalOf(events[events.length - 1])
  const hasNumbers = latest.keywords || latest.refs
  const frames = events.map(e => {
    const r = retrievalOf(e)
    return {
      at: num(e.at),
      text: (r.keywords || r.refs)
        ? r.keywords + ' 词 · ' + r.refs + ' 段' + (r.terms.length ? ' · ' + r.terms.slice(0, 3).join(' / ') : '')
        : humanize(e.summary)
    }
  })
  return {
    kind: 'retrieval', tone: 'retr', label: '检索', at: num(events[0].at),
    title: hasNumbers ? latest.keywords + ' 个检索词 · 命中 ' + latest.refs + ' 段' : '检索',
    summary: hasNumbers
      ? latest.terms.slice(0, 5).join(' · ') + (latest.terms.length > 5 ? ' 等' : '')
      : humanize(events[events.length - 1].summary),
    frames,
    filters: ['retr'],
    retrieval: hasNumbers ? latest : null
  }
}

/**
 * 一轮事件 → 展示节点。
 * - usage / retrieved 全局合并成一个节点（同一轮的累计快照，不是多次调用）
 * - tool 按发生顺序编号，编号就是「对照正文」的锚点
 * - 其余事件各占一个节点，保持时间顺序
 */
export function buildTurnView (turn, { message } = {}) {
  const events = Array.isArray(turn && turn.events) ? turn.events : []
  const usageEvents = []
  const retrievalEvents = []
  const nodes = []
  let toolStep = 0

  for (const e of events) {
    if (SNAPSHOT_TYPES.has(e.type)) {
      if (e.type === 'usage') usageEvents.push(e)
      else retrievalEvents.push(e)
      continue
    }
    const terminalTool = e.type === 'tool' && (!e.status || e.status !== 'start')
    const n = e.type === 'tool'
      ? toolNode(e, terminalTool ? toolStep++ : null)
      : eventNode(e)
    n.filters = nodeFilters(e)
    nodes.push(n)
  }
  if (retrievalEvents.length) nodes.push(retrievalNode(retrievalEvents))
  if (usageEvents.length) nodes.push(usageNode(usageEvents))

  const toolEvents = events.filter(e => e.type === 'tool' && (!e.status || e.status !== 'start'))
  // 等人不是工具耗时：waitedMs 只挂在触发卡片的那一把工具上，单独成一个高亮节点，
  // 时间轴上才能看出「慢在等你」而不是「慢在跑工具」。
  for (const e of toolEvents) {
    const waitMs = num(e.waitedMs)
    if (!waitMs) continue
    nodes.push({
      kind: 'wait', tone: 'warn', label: '等待',
      at: num(e.startedAtMs) || num(e.at),
      title: '等你作答/批准',
      summary: toolLabel(e.name || '', e.label) || '',
      duration: toolDuration(waitMs),
      filters: ['wait'],
      waitMs
    })
  }

  nodes.sort((a, b) => (a.at || 0) - (b.at || 0))
  nodes.forEach((n, i) => {
    n.key = (n.kind || 'n') + '-' + i
    n.open = false
  })

  const counts = { tool: 0, retr: 0, card: 0, art: 0, err: 0, wait: 0, use: 0, other: 0 }
  for (const n of nodes) {
    for (const f of (n.filters || [])) {
      if (counts[f] != null) counts[f]++
    }
  }
  const errorCount = counts.err
  const subagentCount = events.filter(e => e.type === 'subagent').length
  const lastUsage = usageEvents.length ? usageOf(usageEvents[usageEvents.length - 1]) : null
  const latestUsage = (lastUsage && (lastUsage.prompt || lastUsage.output || lastUsage.total)) ? lastUsage : null
  const lastRetrieval = retrievalEvents.length
    ? retrievalOf(retrievalEvents[retrievalEvents.length - 1]) : null
  const latestRetrieval = (lastRetrieval && (lastRetrieval.keywords || lastRetrieval.refs)) ? lastRetrieval : null

  const totalMs = Math.max(0, num(turn && turn.endedAt) - num(turn && turn.startedAt))
  const waitMs = counts.wait ? toolEvents.reduce((s, e) => s + num(e.waitedMs), 0) : 0
  const times = nodes.map(n => num(n.at)).filter(t => t > 0)
  const firstAt = times.length ? Math.min(...times) : num(turn && turn.startedAt)
  const lastAt = times.length ? Math.max(...times) : num(turn && turn.endedAt)
  const prepMs = Math.max(0, firstAt - num(turn && turn.startedAt))
  const tailMs = Math.max(0, num(turn && turn.endedAt) - lastAt)
  const workMs = Math.max(0, totalMs - prepMs - tailMs - waitMs)
  const phases = []
  if (prepMs) phases.push({ key: 'prep', label: '准备', ms: prepMs, text: toolDuration(prepMs) })
  if (workMs) phases.push({ key: 'gen', label: '执行', ms: workMs, text: toolDuration(workMs) })
  if (waitMs) phases.push({ key: 'wait', label: '等待', ms: waitMs, text: toolDuration(waitMs) })
  if (tailMs) phases.push({ key: 'tail', label: '收尾', ms: tailMs, text: toolDuration(tailMs) })

  // 单轮对照：消息里有几步工具，账本就应当有几条 tool 行。少的是旧账本（工具步还没入账），
  // 明确说出来，不让用户对着两个对不上的编号猜。
  const messageSteps = message && Array.isArray(message.toolCalls)
    ? message.toolCalls.filter(t => t && t.status !== 'start').length
    : 0
  const toolsMissing = message ? Math.max(0, messageSteps - toolEvents.length) : 0

  return {
    nodes,
    count: nodes.length,
    rawCount: events.length,
    toolCount: toolEvents.length,
    usage: latestUsage,
    retrieval: latestRetrieval,
    errorCount,
    subagentCount,
    toolsMissing,
    counts,
    phases,
    durationMs: totalMs,
    // 与折叠头那一行同一个口径（都是 endedAt - startedAt），两处读数才不会像两个数
    durationText: totalMs ? (totalMs / 1000).toFixed(1) + 's' : '—',
    waitMs,
    waitText: waitMs ? toolDuration(waitMs) : '—',
    retrievalText: latestRetrieval ? latestRetrieval.keywords + ' 词 / ' + latestRetrieval.refs + ' 段' : '—',
    usageText: latestUsage ? kNum(latestUsage.total) : '—'
  }
}

/** 折叠头那一行小计：工具×N 是真调用次数；用量/检索只报最新态，不报快照次数 */
export function describeTurn (view) {
  if (!view) return ''
  const parts = []
  if (view.toolCount) parts.push('工具×' + view.toolCount)
  if (view.retrieval) parts.push('检索 ' + view.retrieval.keywords + ' 词 · ' + view.retrieval.refs + ' 段')
  if (view.usage) parts.push('用量 ' + kNum(view.usage.total))
  if (view.waitMs) parts.push('等你 ' + toolDuration(view.waitMs))
  if (view.errorCount) parts.push('失败 ' + view.errorCount)
  if (view.subagentCount) parts.push('子智能体×' + view.subagentCount)
  return parts.join(' · ') || '无结构性事件'
}
