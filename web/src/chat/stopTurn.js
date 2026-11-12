// 停止本轮（actions 层）：先请服务端真停，再掐本地通道并就地定格工具卡。
import { closeRunningTools, markStoppedTurn } from './turnCloseout.js'

export const createStopTurn = ({ currentSid, streams, stopApi, notifyWarning }) => () => {
  // 只停当前会话的流；其它会话的后台流不受影响
  const st = streams.get(currentSid())
  if (!st) return Promise.resolve(false)
  const sid = currentSid()
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
