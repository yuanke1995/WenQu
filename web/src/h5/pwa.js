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
