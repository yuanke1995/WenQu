// ==================== Web Push（浏览器系统级推送）的浏览器侧 ====================
// 这一层只干三件事：判断这台设备到底能不能订阅（并把原因说准）、向浏览器要订阅、把订阅交给平台。
//
// 为什么「能不能」必须逐条判而不是笼统一句「不支持」：这四条的**用户动作完全不同**——
//   ① 平台没配 VAPID 密钥 → 用户什么都做不了（该找管理员）；
//   ② 页面不在安全上下文（http://IP）→ 换 https 地址访问才行，浏览器不给非安全来源推送；
//   ③ iOS/iPadOS 且没装到主屏幕 → Safari 标签页里永远订不上，装成 PWA 才有这条能力；
//   ④ 浏览器权限是 denied → 去站点设置里把「通知」改回允许（代码里再点一次 requestPermission 也没用）。
// 混成一句「当前浏览器不支持推送」就是把人往错的方向支使。
import { computed, ref } from 'vue'
import { webpushConfig, webpushSubscribe, webpushUnsubscribe, webpushTest } from '../api'
import { isIos, isStandalone } from '../h5/pwa'

/** 平台侧状态（/webpush/config）：configured=env 里有可用密钥，enabled=总闸开着 */
const platform = ref({ loaded: false, configured: false, enabled: false, publicKey: null, devices: [] })
/** 本机订阅状态：unknown=还没查过（首屏不许闪任何结论） */
const local = ref({ state: 'unknown', subscribed: false })
const busy = ref(false)

const SW_URL = '/sw.js'

function b64urlToBytes (s) {
  const pad = s.length % 4 ? '='.repeat(4 - (s.length % 4)) : ''
  const bin = atob(s.replace(/-/g, '+').replace(/_/g, '/') + pad)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

/** 取 SW 注册：main.js 只在生产构建注册（dev 注册会缓存模块、干扰热更新），
 *  而推送在 dev 也要能验，所以这里按需自己注册一次——同一个 /sw.js、同一个 scope，不会有两份。 */
async function registration () {
  if (!('serviceWorker' in navigator)) return null
  const nav = await navigator.serviceWorker.getRegistration()
  if (nav) return nav
  return await navigator.serviceWorker.register(SW_URL, { scope: '/' })
}

/**
 * 这台设备现在能不能订阅。返回 {ok, reason}，reason ∈
 * unsupported / insecure / needs-install / not-configured / disabled / denied / ok
 */
export async function pushCapability () {
  if (!('serviceWorker' in navigator) || !('PushManager' in window) || typeof Notification === 'undefined') {
    return { ok: false, reason: 'unsupported' }
  }
  if (!window.isSecureContext) return { ok: false, reason: 'insecure' }
  if (isIos() && !isStandalone()) return { ok: false, reason: 'needs-install' }
  const p = platform.value
  if (!p.loaded) return { ok: false, reason: 'loading' }
  if (!p.configured) return { ok: false, reason: 'not-configured' }
  if (!p.enabled) return { ok: false, reason: 'disabled' }
  if (Notification.permission === 'denied') return { ok: false, reason: 'denied' }
  return { ok: true, reason: 'ok' }
}

/** 拉平台侧配置 + 本机是否已订阅（设置页打开时调一次） */
export async function refreshPushState () {
  try {
    const r = await webpushConfig()
    platform.value = { loaded: true, ...(r && r.data) || {} }
  } catch (e) {
    platform.value = { loaded: true, configured: false, enabled: false, publicKey: null, devices: [] , error: e?.message || '推送配置读取失败' }
  }
  try {
    const reg = 'serviceWorker' in navigator ? await navigator.serviceWorker.getRegistration() : null
    const sub = reg ? await reg.pushManager.getSubscription() : null
    local.value = { state: 'known', subscribed: !!sub }
  } catch (e) {
    local.value = { state: 'known', subscribed: false }
  }
  return platform.value
}

/**
 * 在这台设备上开启推送。浏览器会弹一次授权框（用户拒绝就是 denied）。
 * @returns {ok, reason} —— reason 失败时与 pushCapability 同一套词
 */
export async function enablePush () {
  const cap = await pushCapability()
  if (!cap.ok) return cap
  busy.value = true
  try {
    let permission = Notification.permission
    if (permission === 'default') permission = await Notification.requestPermission()
    if (permission !== 'granted') return { ok: false, reason: 'denied' }
    const reg = await registration()
    if (!reg) return { ok: false, reason: 'unsupported' }
    const existing = await reg.pushManager.getSubscription()
    const sub = existing || await reg.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: b64urlToBytes(platform.value.publicKey)
    })
    const j = sub.toJSON()
    await webpushSubscribe({ endpoint: j.endpoint, keys: { p256dh: j.keys.p256dh, auth: j.keys.auth } })
    local.value = { state: 'known', subscribed: true }
    await refreshPushState()
    return { ok: true, reason: 'ok' }
  } catch (e) {
    // 订阅被浏览器拒（常见于公钥与站点作用域不匹配、或推送服务打不通）：把原文带上，
    // 这一路没有别的可看的地方
    return { ok: false, reason: 'failed', error: e?.message || String(e) }
  } finally {
    busy.value = false
  }
}

/** 关掉这台设备的推送：先问浏览器取消订阅，再把平台上那一行删掉 */
export async function disablePush () {
  busy.value = true
  try {
    const reg = 'serviceWorker' in navigator ? await navigator.serviceWorker.getRegistration() : null
    const sub = reg ? await reg.pushManager.getSubscription() : null
    if (sub) {
      const endpoint = sub.endpoint
      await sub.unsubscribe().catch(() => {})
      await webpushUnsubscribe({ endpoint })
    }
    local.value = { state: 'known', subscribed: false }
    await refreshPushState()
    return { ok: true }
  } catch (e) {
    return { ok: false, error: e?.message || String(e) }
  } finally {
    busy.value = false
  }
}

/** 给本人所有已订阅设备发一条测试推送，逐台回报结果 */
export async function testPush () {
  busy.value = true
  try {
    const r = await webpushTest()
    return { ok: true, results: (r && r.data && r.data.results) || [] }
  } catch (e) {
    return { ok: false, error: e?.message || '测试推送失败' }
  } finally {
    busy.value = false
  }
}

/**
 * 订阅在浏览器侧失效时（推送服务给的地址过期、清过站点数据）由 SW 发消息来喊重订阅。
 * 返回一个卸载函数，调用方在 unmount 时清掉。
 */
export function watchPushMessages (handler) {
  if (typeof window === 'undefined' || !('serviceWorker' in navigator)) return () => {}
  const onMsg = e => {
    const d = e && e.data
    if (d && d.k === 'wq-push') handler(d)
  }
  navigator.serviceWorker.addEventListener('message', onMsg)
  return () => navigator.serviceWorker.removeEventListener('message', onMsg)
}

export const pushPlatform = computed(() => platform.value)
export const pushSubscribedHere = computed(() => local.value.subscribed)
export const pushBusy = computed(() => busy.value)
