/**
 * 「能不能连上」的两条判据，分开暴露给界面（两端壳共用一份，模块级单例）：
 * - online：本机网卡/系统层面的连通性，来自 navigator.onLine + online/offline 事件。
 *   它说的是「这台设备有没有网」，不等于「问渠的服务器能不能访问」。
 * - serverDown：设备自报有网，但接口层（api.js 的 request）的取数根本没拿到 HTTP 响应。
 *   它说的是「服务器这一头不通」，判据只有这一个事实，不做猜测。
 *
 * 为什么要拆：断网时把「服务器不通」写在横幅上是误导（用户会去查服务），
 * 而服务器真挂时说「您已断开网络」同样误导（用户会去查自己的手机）。
 * 两者都是「取数失败」，但不是同一件事，界面必须各说各的。
 */
import { computed, readonly, ref } from 'vue'

const online = ref(typeof navigator === 'undefined' ? true : navigator.onLine !== false)
const unreachable = ref(false)

let wired = false
function setOnline (v) {
  online.value = v !== false
  // 设备自己说断网时，unreachable 这个「上次请求没通」的残值不再有意义：
  // 界面此刻只该说「设备离线」，两条判据同时亮会把人引向服务器。
  if (!online.value) unreachable.value = false
}
function syncFromNavigator () { setOnline(navigator.onLine) }

/** 挂监听 + 给接口层供信号，全局各只装一次（重复调用直接返回同一份状态） */
export function ensureNetworkWatch () {
  if (wired) return
  wired = true
  window.addEventListener('online', syncFromNavigator)
  window.addEventListener('offline', syncFromNavigator)
  // iOS Safari 切后台回前台后 navigator.onLine 会滞后，visibilitychange 补一次对账
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') syncFromNavigator()
  })
}

/** 接口层的一次请求结果喂进来：ok=真拿到了响应（含 4xx/5xx，那都是服务器在说话） */
export function markRequestOutcome (ok) {
  if (ok) {
    unreachable.value = false
    return
  }
  if (online.value) unreachable.value = true
}

/** 本轮取数失败但不是服务器不通（超时/HTTP 状态码/业务失败）：不通判据退回，
 *  免得一次超时把「服务器不通」钉在横幅上不下来——那需要「拿不到响应」这个证据。 */
export function clearUnreachable () { unreachable.value = false }

export const isOnline = readonly(online)
/** 设备自报有网、接口却拿不到响应 ⇒ 判定为服务器侧不通 */
export const serverUnreachable = computed(() => online.value && unreachable.value)
