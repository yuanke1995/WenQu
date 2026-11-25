// 浏览器快照栈（从 ChatPage.vue 抽出）：内嵌浏览器每次截图推入栈顶，最多留 MAX 张。
// 只用一个 ref 数组，无外部依赖。clearBrowserSnapshots 在开新一轮时清空。
import { ref } from 'vue'

const MAX_BROWSER_SNAPSHOTS = 20

export const useBrowserSnapshots = () => {
  const browserSnapshots = ref([])
  const clearBrowserSnapshots = () => { browserSnapshots.value = [] }
  const onBrowserSnapshot = payload => {
    let snap = payload
    if (typeof snap === 'string') {
      try { snap = JSON.parse(snap) } catch (e) { return }
    }
    if (!snap || typeof snap !== 'object') return
    browserSnapshots.value = [snap, ...browserSnapshots.value].slice(0, MAX_BROWSER_SNAPSHOTS)
  }
  return { browserSnapshots, clearBrowserSnapshots, onBrowserSnapshot }
}
