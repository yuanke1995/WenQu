// 页面视图导航：成员会话「返回主会话」、顶栏团队胶囊打开右栏团队分段、
// 成员会话进入时让位到「过程」分段。从 ChatPage.vue 抽出，纯 ref 操作、无副作用。
import { watch } from 'vue'

export function useChatPageView ({ router, memberViewRoot, railTab, isNarrow, mPanelOpen, panelOpen }) {
  function backToRootSession () {
    const sid = memberViewRoot.value
    if (sid) router.push({ path: '/chat', query: { sid } }).catch(() => {})
  }

  // 顶栏团队胶囊 → 打开右栏「团队」分段（宽屏占位列 / 窄屏 sheet）
  function openTeamPanel () {
    try {
      railTab.value = 'team'
      if (isNarrow.value) mPanelOpen.value = true
      else panelOpen.value = true
    } catch (e) { /* 忽略 */ }
  }

  // 成员会话页里「团队」分段不适用（团队是根会话的概念）：进入时自动让位到「过程」
  watch(memberViewRoot, (v) => {
    try { if (v && railTab.value === 'team') railTab.value = 'process' } catch (e) { /* 忽略 */ }
  })

  return { backToRootSession, openTeamPanel }
}
