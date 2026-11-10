import { ref } from 'vue'
import { listMarketUpdates } from '../api'

/**
 * 市场可更新数量：侧栏「市场」红点与市场页批量入口共用。
 * <p>不做轮询：布局挂载时拉一次，市场页安装/更新/上下架后调用 {@link refreshMarketUpdates} 失效；
 * 同一时刻只允许一个请求在飞，避免刷新按钮连点把计数打乱。</p>
 */
export const marketUpdateCount = ref(0)

let inflight = null

export function refreshMarketUpdates () {
  if (inflight) return inflight
  inflight = listMarketUpdates()
    .then(r => {
      if (r && r.success) marketUpdateCount.value = Number(r.data?.count || 0)
    })
    .catch(() => { /* 计数是辅助信息：拉不到保留上一次，不打断页面 */ })
    .finally(() => { inflight = null })
  return inflight
}
