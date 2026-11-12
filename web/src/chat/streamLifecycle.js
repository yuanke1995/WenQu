// 接流生命周期（transport 层）：探测去重、重连计数、已收场轮登记。
// 纯状态容器，不读 Vue .value；引擎只调用这些方法。

export const createStreamLifecycle = ({ maxSettled = 80 } = {}) => {
  const retryOf = new Map()     // sid → 连续接流失败次数（接上即归零）
  const probing = new Set()     // 正在探测的会话：挡住轮询/目标帧/重连三路同时探测
  const settled = new Set()     // sid|turnId：已收场的轮，挡住总线回收前的鬼魂

  return {
    isProbing: sid => probing.has(sid),
    /** @returns false=已有探测在飞，调用方不要再发请求 */
    beginProbe (sid) {
      if (probing.has(sid)) return false
      probing.add(sid)
      return true
    },
    endProbe (sid) { probing.delete(sid) },

    isSettled: (sid, turnId) => !!sid && !!turnId && settled.has(sid + '|' + turnId),
    rememberSettled (sid, turnId) {
      if (!sid || !turnId) return
      settled.add(sid + '|' + turnId)
      while (settled.size > maxSettled) settled.delete(settled.values().next().value)
    },

    retryCount: sid => retryOf.get(sid) || 0,
    bumpRetry (sid) {
      const n = (retryOf.get(sid) || 0) + 1
      retryOf.set(sid, n)
      return n
    },
    resetRetry: sid => { retryOf.delete(sid) }
  }
}

/**
 * 接流首片去重：快照与增量唯一的接缝是「正文已进缓冲、事件号未推进」那一条。
 * pos 是这条增量应用后的权威字符数；本地已够长就丢整片，否则截到 pos。
 */
export const dropResumedOverlap = (have, delta, ev) => {
  const pos = ev && typeof ev.pos === 'number' ? ev.pos : -1
  if (pos < 0 || have.length + delta.length <= pos) return delta
  if (have.length >= pos) return ''
  return delta.slice(0, pos - have.length)
}
