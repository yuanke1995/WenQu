// 断线重挂控制器（transport 层）：一条按会话的重试循环，「探测 → 接流 → 退避再试」。
// 与 DSH 同口径：连接是连接、轮次是轮次。通道断了只说明这条流没了，服务端那一轮照样在跑；
// 客户端要做的是把它重新挂回去（GET /chat/resume），**绝不重发 POST /chat**——那会变成一轮
// 全新问答（重复落库、重复计费）。所有副作用（定时器 / online 监听 / 探测 / 收尾）全部注入，便于单测。
import { reconnectDelay, reconnectNext } from './reconnectPolicy.js'

/**
 * @param ctx.onDisconnect (msg, sid) => void  通道断了：标成「重连中」（气泡仍是在进行中）
 * @param ctx.onEnd sid => void            循环结束（接上 / 收场 / 被取消），用于撤掉「重连中」登记
 * @param ctx.clearRetrying (msg) => void  不再重挂时摘掉「重连中」标注
 * @param ctx.isViewing sid => boolean     这个会话是否正在被看（切走就停，切回来由 sessionSwitch 接）
 * @param ctx.hasStream sid => boolean     别的路径是否已经把通道接上了
 * @param ctx.online () => boolean         设备是否有网（离线时暂停，等 online 再续）
 * @param ctx.attach (sid, msg) => Promise<'attached'|'idle'|'busy'|'running'|'unknown'>
 * @param ctx.settleByHistory (msg, sid) => void  探到那一轮已收场：按历史重显真实结果
 * @param ctx.setTimer / clearTimer / onOnline / offOnline  定时器与 online 监听的注入点
 */
export const createTurnReconnect = ctx => {
  const loops = new Map()   // sid → { sid, msg, attempt, timer, onOnline, cancelled, onFail }

  /** 摘掉循环登记（所有结束路径都走这里，调用方只在一处收「这个会话不再重挂」） */
  const done = sid => {
    loops.delete(sid)
    if (typeof ctx.onEnd === 'function') ctx.onEnd(sid)
  }

  /** 取消该会话的重挂循环，返回那颗还没收场的消息（没有则 null） */
  const stop = sid => {
    const r = sid ? loops.get(sid) : null
    if (!r) return null
    r.cancelled = true
    if (r.timer) ctx.clearTimer(r.timer)
    if (r.onOnline) ctx.offOnline(r.onOnline)
    done(sid)
    return r.msg
  }

  const arm = r => {
    if (r.cancelled) return
    const wait = reconnectDelay(r.attempt++)
    r.timer = ctx.setTimer(() => { r.timer = 0; run(r) }, wait)
  }

  // 设备自己断网时等它回来自动续上：这时候空转探测只会陪着一起失败
  const waitOnline = r => {
    if (r.onOnline) return
    r.onOnline = () => {
      ctx.offOnline(r.onOnline)
      r.onOnline = null
      if (!r.cancelled) arm(r)
    }
    ctx.onOnline(r.onOnline)
  }

  const leave = r => {
    stop(r.sid)
    ctx.clearRetrying(r.msg)
  }

  const run = async r => {
    if (r.cancelled) return
    // 用户切走了：停。切回来时 sessionSwitch 本来就会接一次（它也是接流入口）
    if (!ctx.isViewing(r.sid)) { leave(r); return }
    // 别的路径已经把通道接上了（例如目标轮交接）：交给它，不再另开一条
    if (ctx.hasStream(r.sid)) { done(r.sid); return }
    if (!ctx.online()) { waitOnline(r); return }
    const status = await ctx.attach(r.sid, r.msg)
    if (r.cancelled) return
    if (!ctx.isViewing(r.sid)) { leave(r); return }
    const next = reconnectNext(status)
    if (next === 'attached') { done(r.sid); return }
    if (next === 'settle') {
      done(r.sid)
      // 服务端没有在跑的轮：这一轮已经收场 → 按历史重显，绝不在这里挂「本轮失败」。
      // 例外是「连一轮都没受理上」：那条提问确实没送出去，交给调用方按未送达收尾
      if (r.admitted || r.resume) ctx.settleByHistory(r.msg, r.sid)
      else r.onFail((r.error || '连接已中断'))
      return
    }
    arm(r)
  }

  return {
    /**
     * 通道断了：登记一条重挂循环。
     * @param opts.admitted 服务端是否受理过这一轮（发过帧）
     * @param opts.resume   断的是不是接流通道（接流通道断了同样要重挂）
     * @param opts.error    断线的原始错误（未送达时的错误卡正文）
     * @param opts.onFail   未送达收尾（错误卡 + 重新生成）
     */
    start (sid, msg, opts = {}) {
      if (!sid || !msg) return
      const prev = loops.get(sid)
      if (prev && prev.msg === msg) return    // 同一条消息已经在重挂了
      if (prev) stop(sid)                     // 上一条消息留下的循环：它已经不属于本轮
      const r = {
        sid, msg, attempt: 0, timer: 0, onOnline: null, cancelled: false,
        admitted: !!opts.admitted, resume: !!opts.resume,
        error: String(opts.error || ''),
        onFail: typeof opts.onFail === 'function' ? opts.onFail : () => {}
      }
      loops.set(sid, r)
      ctx.onDisconnect(msg, sid)
      arm(r)
    },
    stop,
    has: sid => loops.has(sid),
    stopAll () { ;[...loops.keys()].forEach(stop) }
  }
}
