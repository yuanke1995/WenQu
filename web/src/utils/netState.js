/**
 * 连通性横幅的唯一判据：接口层的一次请求有没有拿到 HTTP 响应。
 *
 * - noResponse：fetch 在拿到任何响应之前抛错才成立；拿到响应（含 4xx/5xx、业务
 *   success=false）当场退回，自己掐的超时也不算。没有请求就没有证据，默认 false。
 *   横幅是否出现只看这一条——这是「事实层」。
 * - online：navigator.onLine，只用来在横幅已经亮起时选一句解释文案——这是「解释层」。
 *   本机部署时断外网但 localhost 照常通，拿它当判据会把「问得出话」说成「消息发不出去」；
 *   反过来设备有网但服务没起时它也帮不上忙。它解释原因，不判事实。
 *
 * 两层分开的理由：判据要的是证据（请求真的没拿到响应），文案要的是方向（去查设备还是查服务）。
 * 断网时不再清判据——那会连带把「服务器真没起来」的证据一起抹掉。
 */
import { readonly, ref } from 'vue'

const online = ref(typeof navigator === 'undefined' ? true : navigator.onLine !== false)
const noResponse = ref(false)

let wired = false
function syncFromNavigator () { online.value = navigator.onLine !== false }

/** 挂监听只刷新文案用的 online，全局只装一次（重复调用直接返回） */
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

/** 一次请求的结果喂进来：ok=真拿到了响应（含 4xx/5xx，那都是服务器在说话） */
export function markRequestOutcome (ok) {
  noResponse.value = !ok
}

/** 本轮取数失败但不是「没拿到响应」（超时/HTTP 状态码/业务失败）：判据退回，
 *  免得一次超时把横幅钉住——那需要「拿不到响应」这个证据。 */
export function clearUnreachable () { noResponse.value = false }

export const isOnline = readonly(online)
/** 横幅唯一判据：请求真的没拿到任何响应 */
export const serverUnreachable = readonly(noResponse)
