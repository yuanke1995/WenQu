// 整轮用时的秒针：1s 一跳的 tick 驱动重渲染，让「卡住」变成可见的进行中。
// 模块内唯一的响应式状态是 nowTick；挂表/摘表由消费方成对调用，卸载时 stopTick。
import { ref } from 'vue'
import { chatStreams } from '../views/store.js'

export const nowTick = ref(Date.now())
let tickTimer = null
let alignTimer = null
// 挂表引用数：主对话页由引擎开轮时 ensureTick；分享页由正在跑的 TurnBar 自己挂
let tickHolders = 0

export const ensureTick = () => {
  if (tickTimer) return
  tickTimer = setInterval(() => {
    nowTick.value = Date.now()
    if (!chatStreams.size && !tickHolders) { clearInterval(tickTimer); tickTimer = null }
  }, 1000)
}

/** 把秒针相位对齐到本轮起表点：从 anchor 起每满 1 秒报一次，收尾那一刻不会跳过最后一秒。 */
export const alignTick = anchor => {
  const a = Number(anchor) || 0
  if (!a) return
  if (alignTimer) { clearTimeout(alignTimer); alignTimer = null }
  if (tickTimer) { clearInterval(tickTimer); tickTimer = null }
  const wait = 1000 - (((Date.now() - a) % 1000) + 1000) % 1000
  alignTimer = setTimeout(() => {
    alignTimer = null
    // 等对齐的这一拍里别的消费方可能又起了表：清掉重来，否则相位还是旧的
    if (tickTimer) { clearInterval(tickTimer); tickTimer = null }
    nowTick.value = Date.now()
    ensureTick()
  }, wait)
}

/** 挂一次表，返回摘表的那只手（TurnBar 卸载或这一轮答完时调用），重复调用只减一次。 */
export const holdTick = () => {
  tickHolders++
  ensureTick()
  let held = true
  return () => { if (held) { held = false; tickHolders = Math.max(0, tickHolders - 1) } }
}

export const stopTick = () => {
  if (tickTimer) { clearInterval(tickTimer); tickTimer = null }
  if (alignTimer) { clearTimeout(alignTimer); alignTimer = null }
}
