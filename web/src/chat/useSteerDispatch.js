// 插话派发（从 useChatEngine 抽出）：steer 帧落到时间线、把插话作为新一轮发出、
// 补发后端没收上的那句、本轮/切会话时结算队列。依赖经显式入参注入（含 streamAnswer 回调）。
import { reactive } from 'vue'

export const useSteerDispatch = ({ messages, currentSessionId, loading, text,
                                    steerQueue, steerResend, dropApplied, pushTimelineSteer,
                                    streamAnswer, notify }) => {

  /**
   * {@code steer} 帧：框架把这句写进了正在跑的这一轮（后端按 inbox/applied 发，位置就是那一刻）。
   * 两件事：撤下队列里等它的那一条（没有也不报错——别的设备上看不到发起方的队列），以及把它长进
   * 这一轮回答的时间线里。
   */
  function applySteerFrame (payload, msg) {
    let f = payload
    try { if (typeof payload === 'string') f = JSON.parse(payload) } catch (e) { return }
    const t = f && typeof f.text === 'string' ? f.text : ''
    if (!t) return
    const id = f.messageId || ''
    dropApplied(id)
    if (id) messages.value = messages.value.filter(m => !(m.role === 'user' && m.messageId === id))
    pushTimelineSteer(msg, t, id, Array.isArray(f.images) ? f.images : [],
                      Array.isArray(f.attachments) ? f.attachments : [])
  }

  /** 按原载荷把一条插话作为完整新一轮发出（与计划「继续对话」同一条通路）
   *  `fromSteer` 让后端在那句话后面补一句身份声明（"这是上一轮还没答完时补的方向，不是新提问"）——
   *  不带它，模型会当成用户又问了一遍（短问答必现：它会复述「你消息里的 X 这一段…」），
   *  插话最核心的「改了方向立刻生效」就废了。 */
  function sendSteerRound (s, origin = null) {
    // origin：发起那一轮的会话与作答智能体（收尾自动派发时用户可能已切走，新一轮必须回到原会话）。
    // 复用自动重试的 prev 通道：「沿用原会话，不读当前 UI 态」。atHome=人还看着这个会话才进气泡列表
    const back = origin ? { sid: origin.sid, agentId: origin.agentId || '', model: '' } : null
    const atHome = !back || back.sid === currentSessionId.value
    const userMsg = reactive({ role: 'user', content: s.text, images: s.images || [],
                          attachments: s.attsMeta || [], attachData: s.atts || [],
                          skills: s.skills || [], mentions: s.mentions || [], historyRefs: s.historyRefs || [],
                          deepThink: !!s.deepThink, planMode: !!s.planMode, fromSteer: true,
                          time: Date.now(), messageId: null })
    if (atHome) messages.value.push(userMsg)
    streamAnswer(s.text, s.images || [], null, atHome && messages.value.length === 1, 1, !!s.deepThink,
                 s.atts || [], s.skills || [], s.mentions || [], back, s.historyRefs || '', '', userMsg,
                 !!s.planMode, false, true)
  }

  /**
   * 补发后端没收上的那句插话：它没进过这一轮的上下文，所以界面上也不该长在这一轮的回答里
   * （那句只留在队列条上，收尾时随这里一起撤下），而是作为<b>新一轮的问题</b>重新出现。
   * <p>库里它仍是点「立即发送」那一刻落的同一条用户消息：后端带 fromSteer 时跳过重复落库，
   * 这颗新气泡直接挂上那个 messageId，刷新后也只有这一条。
   */
  function resendSteer (t, origin = null) {
    const back = origin ? { sid: origin.sid, agentId: origin.agentId || '', model: '' } : null
    const atHome = !back || back.sid === currentSessionId.value
    const existing = t.messageId
            ? messages.value.find(m => m.role === 'user' && m.messageId === t.messageId)
            : null
    // 附件带着引用补发：那句话没进过本轮上下文，随行的材料同样没进去，不能只把字再发一遍
    const attsMeta = Array.isArray(t.attachments) ? t.attachments : []
    const atts = attsMeta.filter(a => a && a.fileId).map(a => ({ name: a.name, mime: a.mime, fileId: a.fileId }))
    const userMsg = existing || reactive({ role: 'user', content: t.text, images: [],
                          attachments: attsMeta, attachData: atts, skills: [], mentions: [], historyRefs: [],
                          deepThink: false, planMode: false, fromSteer: true,
                          time: Date.now(), messageId: t.messageId || null })
    if (atHome && !existing) messages.value.push(userMsg)
    streamAnswer(t.text, [], null, false, 1, false, atts, [], [], back, '', '', userMsg,
                 false, false, true)
  }

  /**
   * 本轮收尾时结算插话：先补发后端没收上的那句，再把还挂在本地队列里的（没点过「立即发送」的、
   * 带材料本轮塞不进去的）作为新一轮交给模型。
   * <p>为什么收尾要自动发：本轮已经结束了，"插话"没有可插入的对象，`/chat/steer` 必然被拒；
   * 用户打的那句话不能因此丢掉。带材料的本来就只有这条路。
   * <p>一次只派一条（同一会话同时只能跑一轮），剩下的等它结束再派——done 会再次走到这里。
   * @param backendLeft 后端 done 里回来的 pendingSteers：[{ text, messageId }]
   */
  function flushPendingSteers (backendLeft = [], origin = null) {
    for (const raw of backendLeft) {
      const t = typeof raw === 'string' ? { text: raw, messageId: '' } : (raw || {})
      if (!t.text) continue
      // 本地队列里同文那条撤下（用户点了发送、又在这句还没退场时手动重打一遍）：补发已经带上它了，
      // 发两遍等于替用户把同一句话讲两次
      steerQueue.value = steerQueue.value.filter(s => s.text !== t.text)
      // 条目带上 origin：补发可能隔了别的轮才轮到（if (loading) return 的排队），到时当前会话
      // 已换人——发回原会话靠条目自己记的那份，不靠当时的 currentSessionId
      if (!steerResend.value.some(x => x.text === t.text)) {
        steerResend.value.push({ ...t, sid: origin?.sid || '', agentId: origin?.agentId || '' })
      }
    }
    // 本轮已经收尾，队列里还挂着「等这一轮听到」的那几条没有对象可插了：上面没被补发接走的，
    // 就是模型已经收到过的（那一帧的留痕长在回答里，或刷新后由库里那条用户消息回显）——
    // 留在条上会被读成「我说了但没人理」。
    steerQueue.value = steerQueue.value.filter(s => !s.awaiting)
    if (loading.value) return   // 已经有人在跑了（用户抢先发了新消息）：留着，下轮结束再结算
    const left = steerResend.value.shift()
    if (left) {
      resendSteer(left, left.sid ? { sid: left.sid, agentId: left.agentId } : null)
      return
    }
    const next = steerQueue.value.find(s => !s.sending && !s.planAdjust)
    if (!next) return
    steerQueue.value = steerQueue.value.filter(s => s !== next)
    sendSteerRound(next, origin)
  }

  /**
   * 离开一个会话（切换/新建/删除当前会话）时结算插话队列。steerQueue 与 steerResend 都是
   * 模块级单例、不分会话：不清的话，条目会以「上一会话那轮还没说完的话」的样子挂在新会话的
   * 输入框上（用户读成新会话的事），旧会话后台轮收尾时更会按当时的 currentSessionId 派发——串话。
   * <p>结算口径：
   * <ul>
   * <li>已送出/在途的（awaiting/sending）：效果已经落在旧会话那一轮里，条目直接撤下。旧会话收尾时
   *     后端按 pendingSteers 交给收尾结算，不依赖这两份本地数组。</li>
   * <li>没发出去的草稿（含带材料等下一轮的 deferred）：文字放回输入框（不覆盖正在打的字，多条按行
   *     合并），材料跟随 editSteer 同一口径不还原——用户自己决定重发还是删掉，说什么都不静默丢。</li>
   * <li>steerResend（后端交回「说过、模型没收到」的）：内容已随 messageId 落在旧会话库里，不丢；
   *     不在这里补发——补发目标是旧会话，而用户已经离开了，轮到它时由带 origin 的收尾结算处理。</li>
   * </ul>
   */
  function settleSteerOnLeave () {
    if (!steerQueue.value.length && !steerResend.value.length) return
    // 只有「还没同步到服务端」的本地草稿需要放回输入框；已经在服务端排队的条目留在那边，
    // 回这个会话时由队列投影恢复——两处都留一份就会变成同一句话发两遍
    const drafts = steerQueue.value
      .filter(s => s.local && !s.awaiting && !s.sending)
      .map(s => String(s.text || '').trim())
      .filter(Boolean)
    steerQueue.value = []
    steerResend.value = []
    if (!drafts.length) return
    const add = drafts.join('\n')
    const cur = String(text.value || '').trim()
    text.value = !cur ? add : (cur.includes(add) ? cur : cur + ' ' + add)
    notify.info('没发出的插话已放回输入框（它属于上一个会话）')
  }
  return { applySteerFrame, sendSteerRound, resendSteer, flushPendingSteers, settleSteerOnLeave }
}
