// ==================== H5 输入草稿（本机、纯文本、按用户 + 会话隔离） ====================
// 手机上切后台、刷新、被系统回收页面是常态，打了一半的字不能跟着没。
// 边界刻意收窄：
//   ① 只存纯文本。图片/附件是 dataURL 与 fileId，体积大且含隐私，落本地既撑爆配额也越权。
//   ② 换了账号就不该看到上一个人的草稿：退出登录清空（auth.js 的 clearAuth）。
//   ③ 发送成功/手动清空/删除会话由调用方显式删，不靠「文本变空」这种间接判据兜底所有路径。
// 键形如 wq_draft:<uid>:<sid>；还没有会话时 sid 记作 new。
const PREFIX = 'wq_draft:'
const DEBOUNCE_MS = 300

let uid = 'anon'
let timer = null
let pending = null   // { key, text }

const keyOf = sid => PREFIX + uid + ':' + (sid || 'new')

/**
 * 身份以最新一次 /auth/me 为准。页面存活期间身份可能变化（改名后重新拉取，或首拉失败后重试成功）：
 * 换 uid 时把旧键下的草稿整体搬过去，否则刚才打的字会留在旧键里、刷新后再也读不到。
 */
export function setDraftUser (name) {
  const next = name || 'anon'
  if (next === uid) return
  flushDraft()   // 防抖里的那一下还挂在旧键上，先写完再搬
  const from = PREFIX + uid + ':'
  const to = PREFIX + next + ':'
  try {
    const moves = []
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i)
      if (k && k.startsWith(from)) moves.push([k, to + k.slice(from.length)])
    }
    moves.forEach(([k, nk]) => {
      const v = localStorage.getItem(k)
      if (v) localStorage.setItem(nk, v)
      localStorage.removeItem(k)
    })
  } catch (e) {
    // 存储不可用：没有草稿可搬
  }
  uid = next
}

export function readDraft (sid) {
  try { return localStorage.getItem(keyOf(sid)) || '' } catch (e) { return '' }
}

function writeNow (key, text) {
  try {
    if (text) localStorage.setItem(key, text)
    else localStorage.removeItem(key)
  } catch (e) {
    // 配额满 / 隐私模式禁用存储：草稿只是便利，不能因此阻断输入
  }
}

/**
 * 保存一条草稿（300ms 防抖）。
 * <p>键在调用当时就固定：用户打完字立刻切会话时，这次写入仍落在旧会话名下，
 * 不会因为 currentSessionId 已经变了而写进新会话。
 */
export function saveDraft (sid, text) {
  const key = keyOf(sid)
  const value = String(text || '')
  if (pending && pending.key === key) pending = null
  if (timer) { clearTimeout(timer); timer = null }
  if (!value.trim()) { writeNow(key, ''); return }
  pending = { key, text: value }
  timer = setTimeout(() => {
    timer = null
    const p = pending
    pending = null
    if (p) writeNow(p.key, p.text)
  }, DEBOUNCE_MS)
}

/** 立即落盘（切会话、页面隐藏/卸载前）——防抖窗口里的最后一次输入不丢 */
export function flushDraft () {
  if (timer) { clearTimeout(timer); timer = null }
  const p = pending
  pending = null
  if (p) writeNow(p.key, p.text)
}

/** 删掉某会话的草稿（发送成功 / 手动清空 / 删除会话） */
export function clearDraft (sid) {
  const key = keyOf(sid)
  if (pending && pending.key === key) pending = null
  writeNow(key, '')
}

/** 退出登录/令牌失效：清本机全部草稿（不只是当前用户的那一组） */
export function clearAllDrafts () {
  pending = null
  if (timer) { clearTimeout(timer); timer = null }
  try {
    const keys = []
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i)
      if (k && k.startsWith(PREFIX)) keys.push(k)
    }
    keys.forEach(k => localStorage.removeItem(k))
  } catch (e) { /* 存储不可用：本来也没有草稿可清 */ }
}

// 页面被切走/回收前把防抖里的那一下写完（iOS 上 pagehide 是能拿到的最后一个可靠时机）
if (typeof window !== 'undefined') window.addEventListener('pagehide', flushDraft)
