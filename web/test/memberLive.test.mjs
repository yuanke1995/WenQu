// 成员轮直播：接流层（memberLive）与直播态拼装（memberLiveState）。
// 这一层存在的理由就是「成员页看得到它正在长出来」——所以用例只钉两件事：
// 帧要按主问答同一套回调派发出去，一轮收尾后要交回历史（不许把直播态留在屏上当正式回答）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createMemberLiveState } from '../src/chat/memberLiveState.js'
import { createMemberLive } from '../src/chat/memberLive.js'

const makeState = () => {
  const appended = []
  const reloads = []
  const state = createMemberLiveState({
    appendMessage: m => appended.push(m),
    reload: sid => reloads.push(sid),
    onScroll: () => {},
    setTodos: () => {}
  })
  return { state, appended, reloads }
}

// 正文按节流落屏（120ms）：等它自然刷出来，而不是调一个只为测试存在的 flush 口
const settle = () => new Promise(r => setTimeout(r, 200))

test('直播态：token 帧长成正文，工具帧进 toolCalls，收尾后交回历史', async () => {
  const { state, appended, reloads } = makeState()
  state.applySnapshot({ done: false, frames: [] }, 'memb-1')
  assert.equal(appended.length, 1, '快照到达后直播气泡进视图')

  state.onFrame('onToken', '我先查一下')
  state.onFrame('onToken', '数据库。')
  await settle()
  const msg = state.current()
  assert.equal(msg.content, '我先查一下数据库。')
  assert.equal(msg.loading, true)

  state.onFrame('onToolStatus', JSON.stringify({ name: 'w_q_db_overview', status: 'start', args: '{}', startedAtMs: 1 }))
  assert.equal(msg.toolCalls.length, 1)
  state.onFrame('onToolStatus', JSON.stringify({ name: 'w_q_db_overview', status: 'done', args: '{}', elapsedMs: 1200 }))
  assert.equal(msg.toolCalls.length, 1, '终态帧不能多出一张卡')
  assert.equal(msg.toolCalls[0].status, 'done')

  state.finish('done')
  assert.deepEqual(reloads, ['memb-1'], '轮结束按历史重载，落库那份才是权威')
  assert.equal(state.current(), null)
})

test('直播态：审批帧挂上可点的审批卡（成员页要能就地批准）', () => {
  const { state } = makeState()
  state.applySnapshot({ done: false, frames: [] }, 'memb-2')
  state.onFrame('onApprovalRequired', JSON.stringify({
    approvalId: 'call_1', tool: 'w_q_sql_query', label: '执行 SQL', timeoutMs: 60000, args: '{"sql":"select 1"}'
  }))
  const card = state.current().approvals[0]
  assert.equal(card.id, 'call_1')
  assert.ok(card.deadline > Date.now(), '卡片要自带倒计时截止点')
})

test('直播态：recognized 以外的帧静默跳过，不打断整条直播', () => {
  const { state } = makeState()
  state.applySnapshot({ done: false, frames: [] }, 'memb-3')
  state.onFrame('onDeepPlanDelta', '随便什么')
  state.onFrame('onNobodyKnows', 'x')
  assert.ok(state.current(), '直播态仍在')
})

/** 桩接流：记下回调，测试自己按后端顺序喂帧（不做网络） */
const makeLive = () => {
  const calls = []
  const ends = []
  let opts = null
  const live = createMemberLive({
    subscribe: (sid, o) => { calls.push(sid); opts = o },
    onFrame: () => {}, onSnapshot: () => {}, onEnd: why => ends.push(why), onWarn: () => {}
  })
  return { live, calls, ends, opts: () => opts }
}

test('接流层：快照带 done 时立刻收尾（这一轮在进页面之前就跑完了）', () => {
  const { live, ends, opts } = makeLive()
  live.start('memb-4')
  opts().onSnapshot({ done: true, frames: [] })
  assert.deepEqual(ends, ['done'], '快照即终态：不再挂着等一条永远不会来的流')
  assert.equal(live.activeSid(), '')
})

test('接流层：done 帧收尾一次；快照不带 done 则保持接流', () => {
  const { live, ends, opts } = makeLive()
  live.start('memb-5')
  opts().onSnapshot({ done: false, frames: [{ event: 'token', data: '你' }] })
  assert.equal(live.activeSid(), 'memb-5')
  opts().onFrame('token', '好')
  opts().onDone()
  opts().onDone()
  assert.deepEqual(ends, ['done'], '多路终态只通知一次')
})

test('接流层：一条流没有任何 snapshot 就结束 = 没有直播可看，不标重连、不重连', () => {
  // 后端在没有直播台时回空流；把它当断线会让成员页标题一直闪「重连中」
  const starts = [], ends = [], statuses = [], timers = []
  const live = createMemberLive({
    subscribe: (sid, o) => { starts.push(o) },
    onFrame: () => {}, onSnapshot: () => {},
    onEnd: why => ends.push(why),
    onStatus: st => statuses.push(st),
    onWarn: () => {},
    setTimer: (fn) => { timers.push(fn); return timers.length },
    clearTimer: () => {}
  })
  live.start('memb-empty')
  starts[0].onDone()
  assert.deepEqual(ends, ['closed'], '收场理由是「没有直播」而不是断线')
  assert.deepEqual(statuses, ['live', 'closed'], '状态不经过 reconnecting')
  assert.equal(timers.length, 0, '不排重连')
})

test('接流层：断线后退避重连，重连拿到快照即恢复直播（不退回历史）', () => {
  const starts = []
  const ends = []
  const warns = []
  const timers = []
  const live = createMemberLive({
    subscribe: (sid, o) => { starts.push(o) },
    onFrame: () => {}, onSnapshot: () => {},
    onEnd: why => ends.push(why),
    onWarn: t => warns.push(t),
    setTimer: (fn, ms) => { timers.push({ fn, ms }); return timers.length },
    clearTimer: () => {}
  })
  live.start('memb-r')
  assert.equal(starts.length, 1)
  // 先收到过直播（snapshot），之后的断开才算「看着看着断了」
  starts[0].onSnapshot({ done: false, frames: [] })
  starts[0].onError('读取失败: network', 'interrupted')
  assert.equal(ends.length, 0, '断线不等于这一轮结束：不许立刻收场')
  assert.equal(warns.length, 1, '第一次断线要告诉用户「正在重连」')
  assert.equal(timers.length, 1, '排了一次重连')
  assert.equal(live.activeSid(), 'memb-r', '重连期间仍是「在看」这个成员')
  // 到点重连
  timers[0].fn()
  assert.equal(starts.length, 2, '开了第二条连接')
  starts[1].onSnapshot({ done: false, frames: [] })
  starts[1].onDone()
  assert.deepEqual(ends, ['done'])
})

test('接流层：重连多次仍接不上才收场（如实退回历史）', () => {
  const starts = []
  const ends = []
  const timers = []
  const live = createMemberLive({
    subscribe: (sid, o) => { starts.push(o) },
    onFrame: () => {}, onSnapshot: () => {},
    onEnd: why => ends.push(why), onWarn: () => {},
    setTimer: (fn, ms) => { timers.push({ fn, ms }); return timers.length },
    clearTimer: () => {}
  })
  live.start('memb-x')
  starts[0].onSnapshot({ done: false, frames: [] })
  // 一直断：每次都会排下一次重连，直到超出退避表
  for (let i = 0; i < 10; i++) {
    const last = starts[starts.length - 1]
    if (!last) break
    last.onError('boom', 'interrupted')
    const t = timers.pop()
    if (!t) break
    t.fn()
  }
  assert.deepEqual(ends, ['closed'], '放弃重连后才收场（按历史显示）')
  assert.equal(live.activeSid(), '')
})

test('接流层：用户主动断开（切走成员）不算断线，不重连', () => {
  const starts = []
  const timers = []
  const live = createMemberLive({
    subscribe: (sid, o) => { starts.push(o) },
    onFrame: () => {}, onSnapshot: () => {},
    onEnd: () => {}, onWarn: () => {},
    setTimer: (fn, ms) => { timers.push(fn); return timers.length },
    clearTimer: () => {}
  })
  live.start('memb-y')
  live.stop()
  starts[0].onError('aborted', 'aborted')
  assert.equal(timers.length, 0, '主动断开不排重连')
})

test('接流层：同一成员重复 start 复用，换成员先摘旧的', () => {
  const { live, calls } = makeLive()
  live.start('memb-a')
  assert.equal(live.activeSid(), 'memb-a')
  live.start('memb-a')
  assert.equal(live.activeSid(), 'memb-a')
  assert.deepEqual(calls, ['memb-a'], '同一成员不重复开流')
  live.start('memb-b')
  assert.equal(live.activeSid(), 'memb-b')
  assert.deepEqual(calls, ['memb-a', 'memb-b'])
  live.stop()
  assert.equal(live.activeSid(), '')
})
