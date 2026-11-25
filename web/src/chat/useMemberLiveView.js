// 成员轮直播视图：进成员会话页就挂观众席，把后台起跑的成员轮按主问答同一套事件
// 拼成一条「直播态」气泡；轮一结束按历史重载（落库那份是权威，直播只是让人看得见它在长）。
// 从 ChatPage.vue 抽出：这块只关心「成员轮直播的订阅与收台」，与页面其余接线无关。
import { ref, watch, onUnmounted } from 'vue'
import { createMemberLive } from './memberLive.js'
import { createMemberLiveState } from './memberLiveState.js'

export const useMemberLiveView = ({ messages, currentSessionId, memberViewRoot,
                                    switchSession, scroll, subscribeApi, notify }) => {
  const memberLiveState = createMemberLiveState({
    appendMessage: m => {
      const list = messages.value || []
      if (!list.includes(m)) messages.value = [...list, m]
    },
    // 只重载正在看的这一条：换了成员/离开之后才回来的收尾，重载会把用户正在看的别处冲掉
    reload: sid => {
      if (currentSessionId.value === sid) switchSession(sid).catch(() => {})
    },
    onScroll: () => scroll(),
    setTodos: () => {}
  })
  // 直播连接状态：成员页标题旁那颗小标用它说「直播中 / 重连中」
  const memberLiveStatus = ref('') // '' | 'live' | 'reconnecting' | 'done' | 'closed'
  const memberLive = createMemberLive({
    subscribe: (sid, opts) => subscribeApi(sid, opts),
    onFrame: (cb, content) => memberLiveState.onFrame(cb, content),
    onSnapshot: (snap, sid) => memberLiveState.applySnapshot(snap, sid),
    onEnd: why => memberLiveState.finish(why),
    onStatus: st => { memberLiveStatus.value = st },
    setTimer: (fn, ms) => setTimeout(fn, ms),
    clearTimer: t => clearTimeout(t),
    onWarn: text => { try { notify.warning(text) } catch (e) { /* 提示失败不影响直播 */ } }
  })
  // 进成员会话页就挂上，换成员/离开就摘掉：一条成员会话同一时刻只需要一个观众
  watch([memberViewRoot, currentSessionId], ([root, sid]) => {
    if (root && sid) memberLive.start(sid)
    else memberLive.stop()
  }, { immediate: true })
  onUnmounted(() => memberLive.stop())

  return { memberLive, memberLiveStatus }
}
