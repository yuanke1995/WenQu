// ==================== PWA 安装（A2HS = Add to Home Screen） ====================
// 为什么需要这一段：manifest + Service Worker 齐了只代表「可安装」，不代表团聚入口——
//   · Android / Chromium：beforeinstallprompt 必须被接住（不接住浏览器只在满足条件时自己弹一次，
//     且拦不住；接住后 e.preventDefault() 收下事件，由我们在壳里给出确定性的入口）；
//   · iOS Safari：没有该事件，只能给「分享 → 添加到主屏幕」的图文指引。
// 注册时机：main.js 顶层就装（事件在页面加载后不久就可能触发，晚装会漏掉这一次）。
import { ref } from 'vue'

let deferred = null
/** 是否握着一个可用的安装提示（Android/Chromium；调用 promptInstall 后清掉） */
export const installable = ref(false)

export function registerPwa () {
  try {
    window.addEventListener('beforeinstallprompt', e => {
      e.preventDefault()      // 拦下浏览器自带的迷你提示条：入口由移动壳统一给（位置可控、可解释）
      deferred = e
      installable.value = true
    })
    window.addEventListener('appinstalled', () => {
      deferred = null
      installable.value = false
    })
  } catch (e) { /* 非浏览器环境（SSR/测试）忽略 */ }
}

/** 已在独立窗口运行（已安装）：此时不该再提示安装 */
export const isStandalone = () => {
  try {
    return (window.matchMedia && window.matchMedia('(display-mode: standalone)').matches)
      || window.navigator.standalone === true
  } catch (e) { return false }
}

/** iOS/iPadOS：无 beforeinstallprompt，只能给图文步骤（iPadOS 的 UA 带 Macintosh，靠触点数量兜底识别） */
export const isIos = () => {
  try {
    const ua = navigator.userAgent || ''
    return /iPad|iPhone|iPod/.test(ua) || (ua.includes('Macintosh') && (navigator.maxTouchPoints || 0) > 1)
  } catch (e) { return false }
}

/** 应用内浏览器（微信/QQ/微博/支付宝）：没有「添加到主屏幕」，给了入口只会把人带沟里 */
const isInAppBrowser = () => {
  try { return /MicroMessenger|QQ\/|Weibo|Alipay/i.test(navigator.userAgent || '') } catch (e) { return false }
}

/** 该不该给「安装到桌面」入口：未安装 + 非应用内浏览器 + （有可用提示 或 iOS 需图文指引） */
export const installEntryVisible = () =>
  !isStandalone() && !isInAppBrowser() && (installable.value || isIos())

/** 触发 Android/Chromium 的安装提示；返回是否真的弹了（iOS 或无提示时为 false，调用方改走图文指引） */
export async function promptInstall () {
  if (!deferred) return false
  const ev = deferred
  deferred = null
  installable.value = false
  try {
    ev.prompt()
    await ev.userChoice
    return true
  } catch (e) { return false }
}

// ==================== 新版本就绪 ====================
// sw.js 的 install 里已经 skipWaiting、activate 里 clients.claim，所以新版本会很快接管。
// 但**当前页面**跑的还是旧 JS：提示一次「新版本已就绪」，由用户自己点刷新。
// 不自动刷新是刻意的——正在生成的回答会被整页重载打断。
export const updateReady = ref(false)
// 同一次页面生命周期里点过「稍后」就不再打扰；新版本下一次接管/刷新后自然重来
let updateDismissed = false

/**
 * 接住「新 SW 装好、且当前页面本来就在旧 SW 控制下」这一档。
 * hadController 必须在注册前取：首次安装（无 controller）时 SW 的 claim 不是更新，不该提示。
 * 只提示一次：同一版本重复触发 updatefound 不再把人从回答里拽出来。
 */
export function watchSwUpdate (reg, hadController) {
  if (!reg) return
  const flagIfReady = worker => {
    if (!worker) return
    const done = () => {
      if (worker.state === 'installed' && hadController && !updateDismissed) updateReady.value = true
    }
    if (worker.state === 'installed') done()
    else worker.addEventListener('statechange', done)
  }
  reg.addEventListener('updatefound', () => flagIfReady(reg.installing))
  // 页面打开时可能已经装好、正等着接管（用户上次点了「稍后」又刷新过页面）
  flagIfReady(reg.waiting || reg.installing)
}

/** 稍后再说：本次页面生命周期内不再出现（不自动刷新，也不催第二次） */
export function dismissUpdate () {
  updateDismissed = true
  updateReady.value = false
}

/** 换到新版：重新取 HTML（HTML 不被 SW 缓存）与新的 hash 资源 */
export function applyUpdate () {
  try { window.location.reload() } catch (e) { /* 极端环境下无法重载：保持页面可用 */ }
}
