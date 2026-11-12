// 委派/咨询另一位助手的展示口径：子对话视图、任务原文、定位锚、顶栏子智能体一行。
// 数据来自后端随工具卡落库的 subChat，刷新/换设备/历史回看与实时同构。
import { toolDuration } from './tools.js'

// 工具名两代都认：自研派遣 consultAgent，框架驱使后 agent_spawn
const CONSULT_TOOL_NAMES = new Set(['consultAgent', 'agent_spawn'])

/** 助手 id → 名称：引擎拉到助手列表时登记一份，供刷新后从工具入参还原「咨询了谁」。
 *  不要求每个调用方都传 agentList——移动端拿不到，否则会变成 PC 有名字、H5 只有 id。 */
const agentNames = new Map()
export const rememberAgents = list => {
  if (!Array.isArray(list)) return
  list.forEach(a => { if (a && a.id && a.name) agentNames.set(a.id, a.name) })
}

/** 被咨询那位的名字：优先后端随卡带的那一份，退回按入参 id 查登记表。 */
export const consultNameOf = t => {
  const n = t?.subChat?.agentName
  if (n) return n
  let id = ''
  try {
    const o = JSON.parse(t?.args || 'null')
    id = o?.agent_id || o?.agentId || ''
  } catch (e) { return '' }
  return agentNames.get(id) || ''
}

/** 工具卡上的子对话视图；null = 这条记录没有子对话，退回普通工具卡。 */
export const consultChatView = t => {
  const s = t?.subChat
  if (!s || typeof s !== 'object') return null
  const status = s.status || 'done'
  return {
    id: s.agentId || '',
    name: s.agentName || consultNameOf(t),
    question: s.question || '',
    answer: s.answer || '',
    steps: Number(s.toolSteps) || 0,
    // 落库的执行时间线：{n=工具名, l=中文名, ms=单步耗时, d=被挡下, s=done|error}
    stepList: (Array.isArray(s.steps) ? s.steps : []).map(x => (x && typeof x === 'object') ? {
      name: String(x.n || ''),
      label: String(x.l || ''),
      ms: Number(x.ms) || 0,
      denied: !!x.d,
      st: x.s === 'error' ? 'error' : 'done'
    } : null).filter(Boolean),
    dur: toolDuration(Number(s.elapsedMs) || 0),
    status
  }
}

export const isDispatchTool = name => CONSULT_TOOL_NAMES.has(name)

/** 派遣任务原文：跑完有 subChat.question，跑前只有模型写入的 task。 */
export const dispatchTaskOf = t => {
  const q = t?.subChat?.question
  if (q) return String(q)
  try { return String(JSON.parse(t?.args || 'null')?.task || '') } catch (e) { return '' }
}

/** 卡片定位锚：同一位助手不会在同一毫秒被派两次。 */
export const spawnAnchorOf = t => (Number(t?.startedAtMs) || 0) + '-' + (t?.name || '')

/** 顶栏「子智能体」一排：把派遣卡摊成一支一行；live 只活一次，刷新后退回卡片步数。 */
export const subagentRunsOf = m => {
  if (!m || !Array.isArray(m.toolCalls)) return []
  return m.toolCalls.filter(t => isDispatchTool(t.name)).map(t => ({
    t,
    name: consultNameOf(t) || '另一位助手',
    task: dispatchTaskOf(t),
    running: t.status === 'start',
    failed: t.status === 'error' || !!(t.subChat && t.subChat.status && t.subChat.status !== 'done'),
    live: t.status === 'start' ? String(t._live || '') : '',
    liveStart: Number(t._liveStart) || 0,
    elapsedMs: Number((t.subChat && t.subChat.elapsedMs) || t.elapsedMs) || 0
  }))
}
