// 停止本轮（actions 层）：先请服务端真停，再掐本地通道并就地定格工具卡。
import { closeRunningTools, markStoppedTurn } from './turnCloseout.js'

/**
 * @param ctx.reconnect 可选：断线重挂中的会话也要停得掉。
 *   take(sid) → 取消该会话的重挂并返回那颗还没收场的消息（没有则 null）
 *   settle(msg, sid) → 重挂已取消，按历史重显真实结果
 */
export const createStopTurn = ({ currentSid, streams, stopApi, notifyWarning, reconnect }) => () => {
  // 只停当前会话的流；其它会话的后台流不受影响
  const sid = currentSid()
  const st = streams.get(sid)
  // 通道已经断了、正在重挂：本地已经没有流，但服务端那一轮还在，停止仍要落到服务端——
  // 不落的后果与「只 abort 不请求」一样：它转后台跑完、还占着会话互斥（下一问会被拒）
  const pendingMsg = reconnect ? reconnect.take(sid) : null
  if (!st && !pendingMsg) return Promise.resolve(false)
  if (!st) {
    return stopApi(sid).then(() => {
      reconnect.settle(pendingMsg, sid)
      return true
    }).catch(() => {
      reconnect.settle(pendingMsg, sid)
      notifyWarning('服务端没能停止本轮，它可能仍在后台跑完')
      return false
    })
  }
  streams.delete(sid)
  // 先请服务端真停：abort 只断本地通道，而通道断开在「正等你作答 / 批准计划」那一轮的语义是
  // **转后台把本轮跑完**，还占着会话互斥直到人工等待超时（表现：按了停止，下一问发不出去、额度照烧）。
  // 返回值 = 服务端是否给出了明确答复（true 停掉 / false 说明它刚自己结束也算答复；网络失败=false）。
  // 重新计划要等这个答复再开新一轮：只等本地 loading 变假就发，服务端互斥还没让出来，新轮必被拒。
  const server = stopApi(sid).then(r => {
    // 只认服务端确认停掉的那一次：stopped=false 说明这一轮并不归这次停止管（多半刚自己结束），
    // 那一份落库正文里没有终止标注，凭空补一句就是刷新后又消失
    if (r?.data?.stopped === true) {
      st.serverStopped = true
      st.msg.stoppedTurn = true
      markStoppedTurn(st.msg)
    }
    return true
  }).catch(() => {
    notifyWarning('服务端没能停止本轮，它可能仍在后台跑完')
    return false
  })
  // 服务端补发的终态帧已经赶不上这条通道（abort 就在下一行），不在这里定格就是：
  // 正文已经写了「已停止本轮」，那张工具卡还在转圈，要等刷新读库里那份才收
  closeRunningTools(st.msg, '本轮已停止')
  st.abort.abort()  // abort → api.js 按正常结束回调 onDone（气泡收尾为「已停止生成」）
  return server
}
