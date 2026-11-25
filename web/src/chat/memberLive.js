// 团队成员轮直播的接流层（transport）：成员页打开时挂上，页面关掉就摘。
//
// 为什么需要这一层：成员轮由框架唤醒在后台起跑、没有请求线程，它的 SSE 出口是一次性的
// 收集型通道（写完即丢）。成员会话页因此只能等整轮跑完读历史——看到的是「一次性蹦出来的结果」。
// 后端在起跑那一刻开了直播台，这里就是观众席：先收一帧 snapshot（这一段已产生的全部帧，按原顺序），
// 之后逐帧增量——事件名与主问答<b>完全同一套</b>，所以回调表直接复用 CHAT_EVENT_CB。
//
// 与主问答的差别只有一处：这一轮不是本页发起的，所以不建气泡、不改 messages 里的历史消息，
// 而是把帧交给外面那份「直播态」展示（见 useMemberLive）：正文、思考、工具行、审批卡都在那里拼。
//
// 生命周期：
//   start(sid) —— 挂上（已有在挂的先摘）
//   stop(sid)  —— 摘掉（页面关闭/切换成员/组件卸载）
//   收到达 done（轮结束/服务端收台）—— 自己摘掉，并通知上层「这一轮结束了」

import { CHAT_EVENT_CB } from './eventContract.generated.js'

/** SSE 帧 → 回调名（与主问答同一张生成表；done/error 由链路自己收尾） */
const CALLBACK_OF = name => CHAT_EVENT_CB[name] || null

/**
 * @param ctx.subscribe (sid, opts) => void  真实接流口（页面注入 subscribeMemberLive）；
 *       单测注入桩函数，让这一层不依赖 api.js 那整条网络栈
 * @param ctx.onFrame   (cb, content, frame) => void  逐帧回调：cb 已是 CHAT_EVENT_CB 里的回调名
 * @param ctx.onSnapshot(snap, sid) => void           快照到达（含 frames 与 done；sid=这条直播的会话）
 * @param ctx.onEnd     (reason) => void              'done' 轮结束 / 'closed' 无直播或连不上
 * @param ctx.onWarn    (text) => void                传输层问题（断线等），不打断页面
 */
export const createMemberLive = ctx => {
  let state = null // { sid, abort, ended, attempts, timer }

  const stop = sid => {
    if (!state) return
    if (sid && state.sid !== sid) return
    const cur = state
    state = null
    cur.ended = true
    if (cur.timer) { try { ctx.clearTimer ? ctx.clearTimer(cur.timer) : clearTimeout(cur.timer) } catch (e) { /* 忽略 */ } }
    try { cur.abort.abort() } catch (e) { /* 已断开 */ }
  }

  /**
   * 挂接流。reconnect=true 是断线后的重挂：同一成员沿用同一颗 token（观众身份没变），
   * 只是换一条连接——后端每次订阅都会先回 snapshot（含积压），所以重连后正文不会缺一段。
   */
  const connect = (token, reconnect) => {
    const sid = token.sid
    const abort = new AbortController()
    token.abort = abort
    // 这一次连接有没有真的收到过直播（snapshot 或任意帧）。没有就退避重连，
    // 收到过又断才说明是「看着看着断了」——两者对用户的意义完全不同。
    token.sawFrames = false
    state = token
    if (!reconnect && typeof ctx.onStatus === 'function') ctx.onStatus('live')
    ctx.subscribe(sid, {
      signal: abort.signal,
      // 一轮的正文可能很久没有新帧（模型在想），但心跳每 15s 一次，看门狗给足余量
      idleTimeoutMs: 180000,
      onFrame: (name, content) => {
        if (token.ended) return
        const cb = CALLBACK_OF(name)
        if (!cb) return
        try {
          ctx.onFrame(cb, content, { type: name })
        } catch (e) {
          // 单帧渲染失败不能把整条直播拖死：记一笔继续收
          if (ctx.onWarn) ctx.onWarn('直播帧处理失败：' + (e && e.message ? e.message : e))
        }
      },
      onSnapshot: snap => {
        if (token.ended) return
        token.sawFrames = true
        ctx.onSnapshot(snap || { done: false, frames: [] }, sid)
        // 快照就带着 done：这一轮在我们进页面之前已跑完——历史里已有结果，直播到此为止
        if (snap && snap.done) endWith('done', token)
      },
      // 正常收尾（收到 done 帧）
      onDone: () => endWith(token.sawFrames ? 'done' : 'closed', token),
      onError: (err, kind) => onBroken(token, err, kind)
    })
  }

  /**
   * 连接断了：成员轮多半还在跑（后台轮本就不随连接生死），所以先退避重连，
   * 重连也拿不到直播了才收场退回历史。与主问答的 turnReconnect 同一取向：
   * 连接是连接、轮次是轮次，断线不等于这一轮结束。
   */
  const onBroken = (token, err, kind) => {
    if (token.ended) return
    // 一帧 snapshot 都没收到过就断了 = 后端根本没有这一场的直播台（空流 / 204）。
    // 这不是「看着看着断了」，而是「现在没有东西可看」：收场退回历史即可，
    // 标成重连中会让标题一直闪「重连中」并无限退避重连（成员空闲时每进一次页面都必然发生）。
    // 空流在 streamChatSse 里走的是「连接被提前关闭」那条（EOF 且没收到 done），
    // 所以判定必须放在这里，而不是只放在 onDone。
    if (!token.sawFrames) {
      endWith('closed', token)
      return
    }
    // 用户主动断开（切走成员/关页）不算问题
    if (kind === 'aborted' || (err && /abort/i.test(String(err)))) return
    if (state !== token) return
    token.attempts = (token.attempts || 0) + 1
    const delays = [1000, 2000, 4000, 8000, 15000]
    if (token.attempts > delays.length) {
      endWith('closed', token)
      if (ctx.onWarn) ctx.onWarn('成员直播已断开（重连多次未接上），按历史显示这一轮')
      return
    }
    const wait = delays[token.attempts - 1]
    if (ctx.onWarn && token.attempts === 1) ctx.onWarn('成员直播连接中断，正在重连…')
    if (typeof ctx.onStatus === 'function') ctx.onStatus('reconnecting')
    const setT = ctx.setTimer || setTimeout
    token.timer = setT(() => {
      token.timer = null
      if (token.ended || state !== token) return
      connect(token, true)
    }, wait)
  }

  const start = sid => {
    if (!sid || typeof ctx.subscribe !== 'function') return
    if (state && state.sid === sid && !state.ended) return // 同一成员重复调用：复用现有接流
    stop()
    connect({ sid, abort: null, ended: false, attempts: 0, timer: null }, false)
  }

  const endWith = (reason, token) => {
    if (!token || token.ended) return
    token.ended = true
    if (state === token) state = null
    if (token.timer) { try { ctx.clearTimer ? ctx.clearTimer(token.timer) : clearTimeout(token.timer) } catch (e) { /* 忽略 */ } token.timer = null }
    try { if (token.abort) token.abort.abort() } catch (e) { /* 已断开 */ }
    if (typeof ctx.onStatus === 'function') ctx.onStatus(reason === 'done' ? 'done' : 'closed')
    ctx.onEnd(reason)
  }

  const activeSid = () => (state && !state.ended ? state.sid : '')

  return { start, stop, activeSid }
}