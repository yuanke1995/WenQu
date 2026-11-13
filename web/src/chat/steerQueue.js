// 运行中插话队列：服务端持有（刷新可恢复），前端只负责入队/立即发送/编辑的交互。
// 没点「立即发送」的条目会先落库排队——反悔窗口仍在（删掉/改掉只是一次 DELETE），但它不再随刷新蒸发。
import { ref, reactive } from 'vue'

/** 这些材料只能等本轮结束按新一轮发出：这一轮的上下文在请求构建期已定型，
 *  且它们要改的是「这一轮怎么跑/查什么」（检索面、人设、上下文引用），插进去也来不及生效。
 *  图片与文档附件不在此列：两者都是随这句话交出去的内容，steer 已收多模态与附件正文——
 *  图片下一次调模型前一起送进去，附件由服务端按 fileId 现读现抽。 */
export const steerNeedsNextRound = s =>
  !!(s && (s.skills?.length
        || s.mentions?.length || s.historyRefs?.length || s.planMode))

/**
 * @param enqueueApi  入队（服务端落库）
 * @param listApi     队列投影（刷新/换会话恢复）
 * @param steerItemApi 某一条立即发送
 * @param dropApi     丢弃某一条
 */
export const createSteerQueue = ({ currentSid, hasLiveStream, steerApi,
                                   enqueueApi, listApi, steerItemApi, dropApi,
                                   notify, textOf, setText, attachImage, attachUploaded }) => {
  const steerQueue = ref([])
  /** 后端交回的「已经说过、模型却没收到」那几条：排在本地队列前面，本轮一空出来就补发 */
  const steerResend = ref([])

  /** 服务端一行 → 界面一条。PENDING=排队中，STEERED=已送出等这一轮听到。
   *  附件按元信息（name/mime/size/fileId）还原成与本地草稿同一形状：取回重发、随行材料计数都认它。 */
  const fromServer = (row, sidNow) => {
    const attsMeta = (row.attachments || []).map(a => ({
      name: a.name, mime: a.mime || '', size: Number(a.size) || 0, fileId: a.fileId
    })).filter(a => a.fileId)
    return reactive({
      id: row.id, text: String(row.text || ''), images: row.images || [],
      atts: attsMeta, attsMeta,
      server: true, local: false, sid: sidNow,
      queued: row.status === 'PENDING',
      awaiting: row.status === 'STEERED',
      sending: false, deferred: false, attempts: Number(row.attempts) || 0,
      lastError: row.lastError || '', at: Number(row.createdAt) || Date.now()
    })
  }

  /**
   * 从服务端恢复这个会话的队列。**只替换服务端那一段**：本地还没同步上去的
   * （刚打完、网络还没回）必须留着——恢复是补上跨刷新的那段，不是抹掉眼前的话。
   */
  const restoreSteerQueue = async (sidArg = '') => {
    const sidNow = sidArg || currentSid()
    if (!sidNow || !listApi) return
    try {
      const r = await listApi(sidNow)
      if (sidNow !== currentSid()) return   // 期间切走了：这份属于旧会话，不往新会话上盖
      const rows = (r && r.data) || []
      const localKept = steerQueue.value.filter(i => i.local && i.sid === sidNow)
      steerQueue.value = [...rows.map(row => fromServer(row, sidNow)), ...localKept]
    } catch (e) {
      // 恢复失败不清空当前队列：宁可少恢复，也不能把用户眼前的话抹掉
    }
  }

  /**
   * 运行中插话：本轮还在跑时补一句方向，由框架在<b>下一次调模型之前</b>送进正在跑的那一轮。
   * 工具循环由框架持有，掐不断正在执行的那一步——所以这不是"打断"，是"赶在下一步想之前说到"。
   * 服务端同时把它落成一条普通用户消息。
   * @returns 消息 ID（前端拿它认队列里那一条，并在模型收到这句时撤下）；null=没有在跑的轮 / 框架不收这句
   */
  const steer = async (q, sidArg = '', images = []) => {
    const sid = sidArg || currentSid()
    const content = String(q || '').trim()
    if (!sid || !content) return null
    try {
      const r = await steerApi(sid, content, images)
      const d = r && r.data
      return (d && d.accepted && d.messageId) ? d.messageId : null
    } catch (e) {
      notify(e?.message || '插话没送出去')
      return null
    }
  }

  /** 把一条本地草稿同步到服务端（失败只记在行上，不弹提示——用户还没点发送）。
   *  附件只带引用（fileId）：正文由服务端按号现读，队列行也不能塞文件本体。 */
  const syncEnqueue = async item => {
    if (!enqueueApi || !item || !item.local) return
    try {
      const r = await enqueueApi(item.sid, item.text, item.images || [], item.atts || [])
      const d = r && r.data
      if (d && d.id) {
        item.id = d.id
        item.local = false
        item.server = true
        item.queued = d.status === 'PENDING'
      }
    } catch (e) {
      item.lastError = e?.message || '入队失败'
    }
  }

  /**
   * 计划「调整计划」的排队：A 档提交时这一轮暂时收不下（目标轮刚收尾/轮间间隙），
   * 不把原文退回去让用户重打，而是落库排队，下一次轮开跑由服务端 drain 自动送达。
   */
  const enqueuePlanAdjust = (text, sid) => {
    const content = String(text || '').trim()
    if (!content || !sid) return null
    const item = reactive({ text: content, planAdjust: true, queued: true, sid,
                            images: [], sending: false, awaiting: false, deferred: false,
                            local: true, server: false, attempts: 0, lastError: '',
                            at: Date.now(), nextAt: 0 })
    steerQueue.value = [...steerQueue.value, item]
    syncEnqueue(item)
    return item
  }

  /**
   * 把排队的调整送进正在跑的那一轮：成功转「已送出，等这一轮听到」，失败留在队列里等下一次轮开跑
   * （服务端在每轮 inbox/opened 时自动 drain，前端不再自己起重试定时器）。
   */
  const deliverPlanAdjust = async item => {
    if (!item || !item.planAdjust || item.sending) return false
    if (item.server && item.id && steerItemApi) {
      item.sending = true
      try {
        const r = await steerItemApi(item.sid, item.id)
        const d = r && r.data
        item.sending = false
        if (!d || !d.accepted) { item.queued = true; return false }
        item.queued = false
        item.awaiting = true
        return true
      } catch (e) {
        item.sending = false
        item.lastError = e?.message || '送达失败'
        return false
      }
    }
    const id = await steer(item.text, item.sid)
    if (!id) { item.queued = true; return false }
    item.queued = false
    item.awaiting = true
    item.messageId = id
    return true
  }

  /**
   * 生成中按发送：先落服务端队列，紧接着立刻送进正在跑的这一轮（DSH 同口径：按发送就送）。
   * 载荷与正常提问完全同权（图片/附件/技能/@ 引用/# 历史引用/思考/计划模式）。
   * @returns 入队成功的那条（调用方要拿它马上走「立即发送」时就用它）；null=没入成，**输入框必须原样保留**
   */
  const stageSteer = payload => {
    const content = String(payload?.text || '').trim()
    if (!content) return null
    const sid = currentSid()
    if (!sid || !hasLiveStream(sid)) return null
    const item = reactive({ ...payload, text: content, sid, local: true, server: false,
                            sending: false, queued: false, awaiting: false, deferred: false,
                            attempts: 0, lastError: '', at: Date.now() })
    steerQueue.value = [...steerQueue.value, item]
    syncEnqueue(item)
    return item
  }

  /** 材料一并取回输入框：队列行删掉了，手里这份图/附件引用是唯一还回来的机会，不能只还文字。 */
  const restoreMaterials = item => {
    const imgs = item.images || []
    const atts = item.atts || []
    imgs.forEach(u => attachImage?.(u))
    atts.forEach(a => attachUploaded?.({ name: a.name, mime: a.mime, size: a.size, fileId: a.fileId }))
  }

  /** 送不出去时把内容放回输入框（不覆盖正在打的字）——服务端队列也失败了才走这条，保证不丢话。 */
  const returnToInput = item => {
    steerQueue.value = steerQueue.value.filter(s => s !== item)
    const cur = String(textOf() || '')
    if (!cur.trim()) setText(item.text)
    else if (!cur.includes(item.text)) setText(cur.trim() + ' ' + item.text)
    restoreMaterials(item)
  }

  /**
   * 点「立即发送」：把这一条作为插话真正发出去。
   * - 服务端队列里的一条 → POST queue/{id}/steer；没有活轮时留在队列里等下一次轮开跑（行内可见）。
   * - 本地还没同步的一条 → 先入队再走服务端；入队也失败才把内容放回输入框。
   * - 带材料（附件/技能/@/#/计划模式）→ 这轮塞不进去，标成待发，本轮结束按原载荷开新一轮。
   */
  const sendSteer = async item => {
    if (!item || item.sending) return
    if (item.planAdjust) {
      const ok = await deliverPlanAdjust(item)
      if (!ok) notify('还没赶上可插话的一轮，这句已排队，下一轮开跑时自动送达', 'info')
      return
    }
    if (steerNeedsNextRound(item)) {
      item.deferred = true
      notify('这句带着材料，本轮的上下文已经定型——等这轮结束就自动接着发', 'info')
      return
    }
    if (!item.server) {
      await syncEnqueue(item)
      if (!item.server) {
        // 服务端都没收下（网络/权限）：退回输入框，别让用户重打一遍
        returnToInput(item)
        notify('这句没能排队——内容放回输入框了')
        return
      }
    }
    item.sending = true
    try {
      const r = await steerItemApi(item.sid, item.id)
      const d = r && r.data
      item.sending = false
      if (d && d.accepted) {
        item.queued = false
        item.awaiting = true
        return
      }
      // 没有在跑的轮（刚收尾/正等人裁决）：留在队列里，下一轮开跑由服务端 drain 送达
      item.queued = true
      item.lastError = '这一轮暂时收不下（可能正等你作答），已排队等它继续'
      notify('这句还没送进本轮：' + item.lastError, 'info')
    } catch (e) {
      item.sending = false
      item.queued = true
      item.lastError = e?.message || '送达失败（已留在队列里）'
      notify('这句没送出去，已排队：' + item.lastError, 'warning')
    }
  }

  /** 「修改」：从队列撤掉取回输入框改（服务端那份一并删掉），图与附件一起取回 */
  const editSteer = item => {
    if (item.server && dropApi) dropApi(item.sid, item.id).catch(() => {})
    steerQueue.value = steerQueue.value.filter(s => s !== item)
    setText(item.text)
    restoreMaterials(item)
  }

  /** 「删除」：丢弃这条还没发出的插话 */
  const dropSteer = item => {
    if (item.server && dropApi) dropApi(item.sid, item.id).catch(() => {})
    steerQueue.value = steerQueue.value.filter(s => s !== item)
  }

  /**
   * 拖动排序：只调整界面顺序（服务端按创建顺序 drain）。纯数组操作，不发任何请求。
   * 为什么还留着：本轮收尾结算按数组顺序依次发出时它有意义；跨轮由服务端队列决定，界面不做假承诺。
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
    // 服务端那条（STEERED→APPLIED）由队列投影下一拍去掉，这里不重复删
    restoreSteerQueue().catch(() => {})
  }

  return { steerQueue, steerResend, steer, stageSteer, sendSteer, editSteer, dropSteer, moveSteer, dropApplied,
           restoreSteerQueue, enqueuePlanAdjust, deliverPlanAdjust }
}
