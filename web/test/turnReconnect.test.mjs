import test from 'node:test'
import assert from 'node:assert/strict'
import { createTurnReconnect } from '../src/chat/turnReconnect.js'

// 假定时器：按登记顺序手动触发，能断言「取消后不再触发」
const makeHarness = (over = {}) => {
  const timers = []
  const onlineFns = []
  const calls = { attach: [], settle: [], fail: [], clear: [], disconnected: [] }
  const ctx = {
    isOnline: true,
    viewing: 's1',
    stream: false,
    status: 'running',
    onDisconnect: (msg, sid) => calls.disconnected.push([sid, msg]),
    onEnd: () => {},
    clearRetrying: msg => calls.clear.push(msg),
    isViewing: sid => ctx.viewing === sid,
    hasStream: () => ctx.stream,
    online: () => ctx.isOnline,
    attach: async (sid, msg) => { calls.attach.push([sid, msg]); return ctx.status },
    settleByHistory: (msg, sid) => calls.settle.push([sid, msg]),
    setTimer: (fn, ms) => { const t = { fn, ms, cleared: false }; timers.push(t); return t },
    clearTimer: t => { t.cleared = true },
    onOnline: fn => onlineFns.push(fn),
    offOnline: fn => { const i = onlineFns.indexOf(fn); if (i >= 0) onlineFns.splice(i, 1) },
    ...over
  }
  const ctrl = createTurnReconnect(ctx)
  const flush = async (max = 50) => {
    let n = 0
    while (timers.length && n++ < max) {
      const t = timers.shift()
      if (!t.cleared) t.fn()
      for (let i = 0; i < 4; i++) await Promise.resolve()
    }
  }
  return { ctx, ctrl, calls, timers, onlineFns, flush }
}

const msgOf = () => ({ loading: false, retrying: false })

test('断线：先标记在跑，再按退避探测；接上就停', async () => {
  const h = makeHarness()
  const msg = msgOf()
  h.ctrl.start('s1', msg, { admitted: true })
  assert.deepEqual(h.calls.disconnected, [['s1', msg]])   // 「重连中」标注由 onDisconnect 落
  assert.equal(h.ctrl.has('s1'), true)
  assert.equal(h.calls.attach.length, 0)      // 首跳也要等一个退避，不立刻砸回去

  h.ctx.status = 'attached'
  await h.flush()
  assert.equal(h.calls.attach.length, 1)
  assert.equal(h.ctrl.has('s1'), false)       // 接上了：循环结束
})

test('断线：探测失败/没挂上都继续重试，直到探到那一轮已收场', async () => {
  const h = makeHarness()
  h.ctx.status = 'unknown'
  h.ctrl.start('s1', msgOf(), { admitted: true })
  await h.flush()
  assert.ok(h.calls.attach.length >= 1)
  assert.equal(h.ctrl.has('s1'), true)        // 网还没回来：不许当收场

  h.ctx.status = 'idle'
  await h.flush()
  assert.equal(h.ctrl.has('s1'), false)
  assert.equal(h.calls.settle.length, 1)      // 收场走历史重显，不挂错误卡
  assert.equal(h.calls.fail.length, 0)
})

test('断线：受理过的轮收场 → 按历史重显；从未受理 → 按未送达收尾', async () => {
  const h1 = makeHarness()
  h1.ctx.status = 'idle'
  h1.ctrl.start('s1', msgOf(), { admitted: true })
  await h1.flush()
  assert.equal(h1.calls.settle.length, 1)
  assert.equal(h1.calls.fail.length, 0)

  const h2 = makeHarness()
  h2.ctx.status = 'idle'
  h2.ctrl.start('s1', msgOf(), { admitted: false, resume: false, error: '请求失败: Failed to fetch', onFail: e => h2.calls.fail.push(e) })
  await h2.flush()
  assert.equal(h2.calls.settle.length, 0)
  assert.deepEqual(h2.calls.fail, ['请求失败: Failed to fetch'])
})

test('断线：接流通道的断线同样重挂（不区分是不是本地发起的）', async () => {
  const h = makeHarness()
  h.ctx.status = 'idle'
  h.ctrl.start('s1', msgOf(), { admitted: false, resume: true })
  await h.flush()
  assert.equal(h.calls.settle.length, 1)      // 接流来的：按历史重显，不判失败
  assert.equal(h.calls.fail.length, 0)
})

test('断线：切走就停；切回来留给 sessionSwitch 接', async () => {
  const h = makeHarness()
  const msg = msgOf()
  h.ctx.viewing = 'other'
  h.ctrl.start('s1', msg, { admitted: true })
  await h.flush()
  assert.equal(h.calls.attach.length, 0)
  assert.equal(h.ctrl.has('s1'), false)
  assert.equal(msg.retrying, false)           // 不再重挂：摘掉「重连中」标注
})

test('断线：离线时暂停，等 online 再续（不空转探测）', async () => {
  const h = makeHarness()
  h.ctx.isOnline = false
  h.ctrl.start('s1', msgOf(), { admitted: true })
  await h.flush()
  assert.equal(h.calls.attach.length, 0)
  assert.equal(h.onlineFns.length, 1)

  h.ctx.isOnline = true
  h.ctx.status = 'attached'
  h.onlineFns[0]()
  await h.flush()
  assert.equal(h.calls.attach.length, 1)
  assert.equal(h.ctrl.has('s1'), false)
})

test('断线：停止/卸载取消后不再探测，并交出那颗没收场的消息', async () => {
  const h = makeHarness()
  const msg = msgOf()
  h.ctrl.start('s1', msg, { admitted: true })
  assert.equal(h.ctrl.stop('s1'), msg)
  await h.flush()
  assert.equal(h.calls.attach.length, 0)
  assert.equal(h.ctrl.has('s1'), false)

  const h2 = makeHarness()
  h2.ctrl.start('s1', msgOf(), { admitted: true })
  h2.ctrl.start('s2', msgOf(), { admitted: true })
  h2.ctrl.stopAll()
  await h2.flush()
  assert.equal(h2.calls.attach.length, 0)
})

test('断线：别的路径已接上 / 同一条消息重复 start 都不另开一条', async () => {
  const h = makeHarness()
  h.ctx.stream = true
  h.ctrl.start('s1', msgOf(), { admitted: true })
  await h.flush()
  assert.equal(h.calls.attach.length, 0)
  assert.equal(h.ctrl.has('s1'), false)

  const h2 = makeHarness()
  h2.ctx.status = 'attached'
  const msg = msgOf()
  h2.ctrl.start('s1', msg, { admitted: true })
  h2.ctrl.start('s1', msg, { admitted: true })
  await h2.flush()
  assert.equal(h2.calls.attach.length, 1)
})
