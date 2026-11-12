// 运行中插话队列（纯前端）：入队、立即发送、队列编辑与排序。
// 没点「立即发送」的条目只活在这里，一个请求都不发——反悔窗口因此是白拿的。
import { ref, reactive } from 'vue'

/** 带材料的插话只能等本轮结束按新一轮发出（这轮的上下文在请求构建期已定型） */
export const steerNeedsNextRound = s =>
  !!(s && (s.images?.length || s.atts?.length || s.skills?.length
        || s.mentions?.length || s.historyRefs?.length || s.planMode))

export const createSteerQueue = ({ currentSid, hasLiveStream, steerApi, notify, textOf, setText }) => {
  const steerQueue = ref([])
  /** 后端交回的「已经说过、模型却没收到」那几条：排在本地队列前面，本轮一空出来就补发 */
  const steerResend = ref([])

  /**
   * 运行中插话：本轮还在跑时补一句方向，由框架在<b>下一次调模型之前</b>送进正在跑的那一轮。
   * 工具循环由框架持有，掐不断正在执行的那一步——所以这不是"打断"，是"赶在下一步想之前说到"。
   * 服务端同时把它落成一条普通用户消息。
   * @returns 消息 ID（前端拿它认队列里那一条，并在模型收到这句时撤下）；null=没有在跑的轮 / 框架不收这句 / 超出单条字数上限
   */
  const steer = async (q, sidArg = '') => {
    const sid = sidArg || currentSid()
    const content = String(q || '').trim()
    if (!sid || !content) return null
    try {
      const r = await steerApi(sid, content)
      const d = r && r.data
      return (d && d.accepted && d.messageId) ? d.messageId : null
    } catch (e) {
      notify(e?.message || '插话没送出去')
      return null
    }
  }

  /**
   * 计划「调整计划」的排队：A 档提交时这一轮暂时收不下（目标轮刚收尾/轮间间隙），
   * 不把原文退回去让用户重打，而是挂着等下一轮开跑再送。
   * 条目带 sid：投递目标由发起时那个会话决定，不看投递时刻的当前会话。
   */
  const enqueuePlanAdjust = (text, sid) => {
    const content = String(text || '').trim()
    if (!content || !sid) return null
    const item = reactive({ text: content, planAdjust: true, queued: true, sid,
                            sending: false, awaiting: false, attempts: 0,
                            at: Date.now(), nextAt: 0 })
    steerQueue.value = [...steerQueue.value, item]
    return item
  }

  /**
   * 把排队的调整送进正在跑的那一轮：成功转「已送出，等这一轮听到」，
   * 失败留在队列里等下一拍（退避由调用方的节奏 + nextAt 控制）。
   */
  const deliverPlanAdjust = async item => {
    if (!item || !item.planAdjust || !item.queued || item.sending) return false
    item.sending = true
    const id = await steer(item.text, item.sid)
    item.sending = false
    if (!id) {
      item.attempts += 1
      item.nextAt = Date.now() + Math.min(2000 + item.attempts * 1000, 12000)
      return false
    }
    item.queued = false
    item.awaiting = true
    item.messageId = id
    return true
  }

  /**
   * 生成中按发送：**只入队，不发请求**（等用户点那条上的「立即发送」，或由 ⌘/Ctrl+Enter 入队后马上送）。
   * 载荷与正常提问完全同权（图片/附件/技能/@ 引用/# 历史引用/思考/计划模式）。
   * @returns 入队成功的那条（调用方要拿它马上走「立即发送」时就用它）；null=没入成，**输入框必须原样保留**
   */
  const stageSteer = payload => {
    const content = String(payload?.text || '').trim()
    if (!content) return null
    const sid = currentSid()
    if (!sid || !hasLiveStream(sid)) return null
    const item = reactive({ ...payload, text: content, sending: false })
    steerQueue.value = [...steerQueue.value, item]
    return item
  }

  /**
   * 点「立即发送」：把这一条作为插话真正发出去。
   * - 纯文字 → POST /chat/steer。送出去之后这条**留在队列里**换成「等这一轮听到」的样子：
   *   框架只在下一次调模型之前把它写进上下文，那一刻才由 {@code steer} 帧把它取走、变成回答时间线里
   *   的一段（夹在正文与工具之间，正是它真被听到的位置）。所以队列里那句的语义是「还没进模型的话」，
   *   界面上不另推一颗独立气泡——两处各显示一遍，反而说不清它到底进没进去。
   * - 带材料 → 这轮塞不进去，标成待发，本轮一结束按原载荷开新一轮——条目留在队列里等这一刻。
   * - 没有在跑的轮 / 框架不收这句 → 退回输入框，别让用户重打一遍。
   */
  const sendSteer = async item => {
    if (!item || item.sending) return
    // 调整计划的排队项：手动点「立即发送」＝马上再试一次，失败继续排队（不退回输入框）
    if (item.planAdjust) {
      const ok = await deliverPlanAdjust(item)
      if (!ok) notify('还没赶上可插话的一轮，这句继续排队等下一轮开跑', 'info')
      return
    }
    if (steerNeedsNextRound(item)) {
      item.deferred = true
      notify('这句带着材料，本轮的上下文已经定型——等这轮结束就自动接着发', 'info')
      return
    }
    item.sending = true
    const id = await steer(item.text)
    if (id) {
      item.sending = false
      item.awaiting = true
      item.messageId = id
      return
    }
    item.sending = false
    // 没有在跑的轮（刚收尾、正等人裁决、或这一轮已经不在执行中）：撤下这一条并把内容**放回输入框**。
    // 放回时不覆盖用户正在打的字——他多半已经在为下一句打字了，直接覆盖等于把那段话吃掉。
    steerQueue.value = steerQueue.value.filter(s => s !== item)
    const cur = String(textOf() || '')
    if (!cur.trim()) setText(item.text)
    else if (!cur.includes(item.text)) setText(cur.trim() + ' ' + item.text)
    notify('这句没能插进正在跑的这一轮——内容放回输入框了')
  }

  /** 「修改」：取回输入框改（东西还在本地，就是一次数组操作） */
  const editSteer = item => {
    steerQueue.value = steerQueue.value.filter(s => s !== item)
    setText(item.text)
  }

  /** 「删除」：丢弃这条还没发出的插话 */
  const dropSteer = item => {
    steerQueue.value = steerQueue.value.filter(s => s !== item)
  }

  /**
   * 拖动排序：把 from 位置的条目挪到 to 位置（HTML5 拖拽的落点语义是"插到 to 之前"）。
   * <p>纯数组操作，不发任何请求——队列本来就是本地的。顺序有意义：收尾结算按数组顺序依次发出，
   * 用户把「先看 A 再看 B」和「先看 B 再看 A」排出来，就该按他排的顺序进模型。
   */
  const moveSteer = (from, to) => {
    if (from === to || from < 0 || to < 0) return
    const arr = [...steerQueue.value]
    if (from >= arr.length) return
    const [item] = arr.splice(from, 1)
    arr.splice(Math.min(to, arr.length), 0, item)
    steerQueue.value = arr
  }

  /** steer 帧到了：撤下队列里等它的那一条（没有也不报错——别的设备上看不到发起方的队列） */
  const dropApplied = id => {
    if (!id) return
    steerQueue.value = steerQueue.value.filter(s => !(s.awaiting && s.messageId === id))
  }

  return { steerQueue, steerResend, steer, stageSteer, sendSteer, editSteer, dropSteer, moveSteer, dropApplied,
           enqueuePlanAdjust, deliverPlanAdjust }
}
