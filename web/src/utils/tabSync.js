// ==================== 同源页签之间的会话列表喊话 ====================
// 侧栏那份会话列表是「每个页签各持一份内存快照」：整栏只在壳层挂载时拉一次，
// 所以在 A 页签里新建/删除/改名一个会话，B 页签完全不会知道，要用户自己刷新页面才看到。
// 这里把「列表变了」这件事喊给其它页签，让它们做定点补丁（见 views/store.js 的 applyRemoteRow）。
//
// 为什么用 BroadcastChannel 而不是轮询/服务端推送：
//   轮询要么 30s 才同步一次、要么一直在打接口；服务端本来也不知道「这个用户的另一个页签该刷新了」，
//   而变更就发生在本机另一个页签里——它自己就是最便宜的信号源。
// 方向天然正确：BroadcastChannel 的 onmessage 不会回给发送方自己，
//   所以发起变更的那个页签照常走它原本的整表刷新，只有别的页签收消息，不需要额外防回声。
// 退回 localStorage 的原因：老 Safari 没有 BroadcastChannel，而 storage 事件同样只在其它页签触发，
//   语义正好一样。两条通道不同时用，否则会各收一次消息、补丁跑两遍。
const CHANNEL_NAME = 'wenqu-session-list'
const FALLBACK_KEY = 'wenqu-session-announce'

/** 本页签的身份：退路通道要靠它滤掉自己写进去的那一条 */
export const TAB_ID = Math.random().toString(36).slice(2) + Date.now().toString(36)

const channel = (() => {
  try { return typeof BroadcastChannel === 'function' ? new BroadcastChannel(CHANNEL_NAME) : null } catch (e) { return null }
})()

/**
 * 喊一句给同源其它页签。消息形如 { k: 'row' | 'drop' | 'spaces', ... }（认不出的 k 在 store 那边
 * 按「列表大概变了」整表兜底，所以加新 k 不会坏，只会退化成多刷一次）。
 * 广播本身不许影响这次操作：发不出去（配额/隐私模式）就当没喊，用户看到的仍是本页签的权威刷新。
 */
export function announce (msg) {
  const payload = { ...msg, tab: TAB_ID, at: Date.now() }
  if (channel) {
    try { channel.postMessage(payload); return } catch (e) { /* 退回 storage */ }
  }
  try {
    // nonce 必需：同一秒内连发两条内容相同的变化，值没变就不会触发 storage 事件
    localStorage.setItem(FALLBACK_KEY, JSON.stringify({ ...payload, nonce: Math.random() }))
  } catch (e) { /* 隐私模式下 localStorage 也会抛，静默 */ }
}

/**
 * 收别的页签喊来的消息，返回退订函数。
 * 只在这条通道可用时才注册；本页签自己发的消息不会到这里（见文件头）。
 */
export function onAnnounce (handler) {
  if (channel) {
    const onMessage = e => { if (e.data && e.data.tab !== TAB_ID && e.data.k) handler(e.data) }
    channel.addEventListener('message', onMessage)
    return () => channel.removeEventListener('message', onMessage)
  }
  const onStorage = e => {
    if (e.key !== FALLBACK_KEY || !e.newValue) return
    let msg = null
    try { msg = JSON.parse(e.newValue) } catch (err) { return }
    if (!msg || msg.tab === TAB_ID || !msg.k) return
    handler(msg)
  }
  window.addEventListener('storage', onStorage)
  return () => window.removeEventListener('storage', onStorage)
}
