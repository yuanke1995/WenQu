// 交付包的会话级聚合（selectors）：消息列表 + 当前目标 → 目标包 / 各轮包 / 消息索引。
// 纯函数，不读 .value；useChatEngine 只负责把它们包成 computed。
import { deliveryOf, deliverySubjectOf } from './delivery.js'

const artifactFilesOf = m => (Array.isArray(m?.artifacts) ? m.artifacts.filter(a => a && a.url && a.id) : [])

/**
 * 当前目标的交付包：目标第一轮可能沿用用户触发那一轮的普通 turnId，其余轮带 goal:{id}: 前缀。
 * 不按「从第一轮往后全收」——用户插话轮也可能产出文件，但那不是目标的交付物。
 */
export const goalDeliveryOf = (messages, goal) => {
  const g = goal
  if (!g || !g.goalId) return null
  const prefix = 'goal:' + g.goalId + ':'
  const firstTurn = String(g.firstTurnId || '')
  const files = []
  const turnIds = []
  const seen = new Set()
  let anchorKey = ''
  let anchorMsg = null
  for (const m of messages || []) {
    if (!m || m.role !== 'ai' || !Array.isArray(m.artifacts) || !m.artifacts.length) continue
    const tid = String(m.turnId || '')
    if (!tid || !(tid.startsWith(prefix) || (firstTurn && tid === firstTurn))) continue
    let added = false
    for (const a of artifactFilesOf(m)) {
      if (seen.has(a.id)) continue
      seen.add(a.id)
      files.push(a)
      added = true
    }
    if (added) {
      if (!turnIds.includes(tid)) turnIds.push(tid)
      anchorKey = m.messageId || tid
      anchorMsg = m
    }
  }
  if (!files.length) return null
  const stateLabel = g.status === 'ACHIEVED' ? '已完成'
    : g.status === 'FAILED' ? '未完成'
      : g.status === 'CANCELLED' ? '已取消'
        : '正在产出'
  const badgeTone = g.status === 'ACHIEVED' ? 'ok'
    : g.status === 'FAILED' ? 'warn'
      : g.status === 'CANCELLED' ? 'muted' : 'live'
  const pkg = deliveryOf(files, {
    name: (deliverySubjectOf(g.objective) || '本次交付') + ' · 交付包',
    versionLabel: 'v' + ((Number(g.objectiveVersion) || 0) + 1),
    round: Number(g.round) || 0,
    stateLabel,
    badgeTone,
    approved: g.status === 'ACHIEVED',
    scope: 'goal',
    goalId: g.goalId,
    turnIds,
    anchorKey
  })
  if (pkg) pkg.anchorMsg = anchorMsg
  return pkg
}

/** 一轮交付包的主语：取该轮之前最近一条用户问题。 */
const turnSubjectOf = (messages, aiIndex) => {
  for (let j = aiIndex - 1; j >= 0; j--) {
    const u = messages[j]
    if (u && u.role === 'user' && String(u.content || '').trim()) {
      return deliverySubjectOf(String(u.content).replace(/[`*#>]/g, '').trim())
    }
  }
  return ''
}

/**
 * 各轮交付包：普通问答按轮聚合，旧目标（goal: 前缀）按 goalId 聚回一个包。
 * 当前目标的产物已进 goalDelivery，这里跳过它，避免同一批文件出两张卡。
 */
export const turnDeliveriesOf = (messages, { currentGoalId = '', currentTurns = [] } = {}) => {
  const out = []
  const groups = new Map()
  const list = messages || []
  for (let i = 0; i < list.length; i++) {
    const m = list[i]
    if (!m || m.role !== 'ai' || !Array.isArray(m.artifacts) || !m.artifacts.length) continue
    const tid = String(m.turnId || '')
    if (tid && currentTurns.includes(tid)) continue
    const goalId = tid.startsWith('goal:') ? tid.split(':')[1] : ''
    if (goalId && goalId === currentGoalId) continue
    const key = goalId ? 'g:' + goalId : 't:' + (m.messageId || tid || i)
    let g = groups.get(key)
    if (!g) {
      g = { files: [], turnIds: [], anchorKey: '', anchorMsg: null, firstIndex: i }
      groups.set(key, g)
    }
    for (const a of artifactFilesOf(m)) {
      if (!g.files.some(f => f.id === a.id)) g.files.push(a)
    }
    if (tid && !g.turnIds.includes(tid)) g.turnIds.push(tid)
    g.anchorKey = m.messageId || tid
    g.anchorMsg = m
  }
  for (const g of groups.values()) {
    const pkg = deliveryOf(g.files, {
      name: (turnSubjectOf(list, g.firstIndex) || '本次交付') + ' · 交付',
      scope: 'turn',
      turnIds: g.turnIds,
      anchorKey: g.anchorKey
    })
    if (pkg) {
      pkg.anchorMsg = g.anchorMsg
      out.push(pkg)
    }
  }
  return out
}

/** 消息 → 交付包：目标包只挂最后一轮，其余按轮各一份。 */
export const deliveriesByMessage = (goalPkg, turnPkgs) => {
  const map = new Map()
  if (goalPkg && goalPkg.anchorMsg) map.set(goalPkg.anchorMsg, goalPkg)
  for (const d of turnPkgs || []) if (d.anchorMsg) map.set(d.anchorMsg, d)
  return map
}
