/**
 * 计划卡片的纯界面记忆（按会话）：旧计划是否展开成三段式、计划视图上次停在哪个版本。
 *
 * 这些偏好不属于后端数据，也不该写进消息对象（刷新/切会话后消息对象会按落库记录重建，
 * 一写就丢）。放 localStorage：同会话同一条计划的展开态、同一次裁决看的版本，回来还在。
 */
const KEY = 'wenqu_plan_ui'
let cache = null

function read () {
  if (cache) return cache
  try {
    const raw = localStorage.getItem(KEY)
    const parsed = raw ? JSON.parse(raw) : {}
    cache = (parsed && typeof parsed === 'object') ? parsed : {}
  } catch (e) { cache = {} }
  return cache
}
function write () {
  try { localStorage.setItem(KEY, JSON.stringify(cache || {})) } catch (e) { /* 存储不可用忽略 */ }
}
const slot = (sid, pkey) => String(sid || '') + '|' + String(pkey == null ? '' : pkey)

/** 旧计划行是否展开（默认收起） */
export const planLineOpenOf = (sid, pkey) => {
  const v = read()[slot(sid, pkey)]
  return !!(v && v.lineOpen)
}
export const setPlanLineOpen = (sid, pkey, open) => {
  const c = read()
  const k = slot(sid, pkey)
  c[k] = { ...(c[k] || {}), lineOpen: !!open }
  write()
}

/** 计划视图上次停在第几版（0 基；默认当前版） */
export const planViewerVersionOf = (sid, pkey) => {
  const v = read()[slot(sid, pkey)]
  return v && Number.isInteger(v.version) ? v.version : -1
}
export const setPlanViewerVersion = (sid, pkey, version) => {
  const c = read()
  const k = slot(sid, pkey)
  c[k] = { ...(c[k] || {}), version: Number(version) || 0 }
  write()
}

/**
 * 旧计划已被「重新计划」的时刻（按计划卡 id 记）。
 * <p>为什么需要它：重新计划的新一轮要先流式生成、再落 plan 记录；在这段间隙里刷新，
 * 旧卡的历史记录里还没有「被重新规划」的标记，会退回报「已批准」并重新露出调整入口。
 * 本机记住这一刻，刷新后旧卡仍是「已停止 · 已重新规划」；换设备时等新计划落库后由血缘自动接上。
 */
export const planReplannedAtOf = (sid, cardId) => {
  if (!sid || !cardId) return 0
  const v = read()[slot(sid, 'replanned:' + cardId)]
  return v && Number(v.at) ? Number(v.at) : 0
}
export const setPlanReplanned = (sid, cardId, at = Date.now()) => {
  if (!sid || !cardId) return
  const c = read()
  c[slot(sid, 'replanned:' + cardId)] = { at: Number(at) || Date.now() }
  write()
}
