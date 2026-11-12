import test from 'node:test'
import assert from 'node:assert/strict'
import { submitPlanApproval, revisePlanInPlace, steerPlanAdjustment, adjustPlanAfterApproval, replanAfterApproval } from '../src/chat/planActions.js'

const make = (over = {}) => {
  const calls = { warn: [], err: [], notif: [], poll: [], stop: 0, switch: [], scroll: 0 }
  const c = { id: 'p1', status: 'pending', busy: false }
  const m = { planCard: c }
  const ctx = {
    resolveApi: async () => ({ success: true }),
    reviseApi: async () => ({ success: true }),
    notify: { warning: x => calls.warn.push(x), error: x => calls.err.push(x) },
    emitNotifChanged: w => calls.notif.push(w),
    stopRestoredPoll: () => { calls.stop++ },
    pollRestoredAnswer: sid => calls.poll.push(sid),
    switchSession: sid => calls.switch.push(sid),
    currentSid: () => 's1',
    scrollSoft: () => { calls.scroll++ },
    ...over
  }
  return { c, m, calls, ctx }
}

test('批准：正常落 approved 并贴底；恢复态撤卡并轮询', async () => {
  const a = make()
  await submitPlanApproval(a.m, 'approve', a.ctx)
  assert.equal(a.c.status, 'approved')
  assert.equal(a.calls.scroll, 1)
  assert.deepEqual(a.calls.notif, ['chat'])

  const b = make()
  b.c.restored = true
  await submitPlanApproval(b.m, 'approve', b.ctx)
  assert.equal(b.m.planCard, null)
  assert.deepEqual(b.calls.poll, ['s1'])
})

test('拒绝：正常落 rejected；恢复态撤卡并重载', async () => {
  const a = make()
  await submitPlanApproval(a.m, 'reject', a.ctx)
  assert.equal(a.c.status, 'rejected')

  const b = make()
  b.c.restored = true
  await submitPlanApproval(b.m, 'reject', b.ctx)
  assert.equal(b.m.planCard, null)
  assert.equal(b.calls.stop, 1)
  assert.deepEqual(b.calls.switch, ['s1'])
})

test('裁决失败：解锁；恢复态撤卡重载', async () => {
  const a = make({ resolveApi: async () => ({ success: false, msg: '已失效' }) })
  await submitPlanApproval(a.m, 'approve', a.ctx)
  assert.deepEqual(a.calls.warn, ['已失效'])
  assert.equal(a.c.busy, false)

  const b = make({ resolveApi: async () => ({ success: false, msg: '失效' }) })
  b.c.restored = true
  await submitPlanApproval(b.m, 'approve', b.ctx)
  assert.equal(b.m.planCard, null)
  assert.equal(b.calls.stop, 1)
})

test('退回重出：空意见/恢复态拒绝；成功进入 revising 并清倒计时', async () => {
  const a = make()
  assert.equal(await revisePlanInPlace(a.m, '  ', a.ctx), false)
  assert.deepEqual(a.calls.warn, ['先写下要改什么，再提交修改'])

  const r = make()
  r.c.restored = true
  assert.equal(await revisePlanInPlace(r.m, '改', r.ctx), false)

  const s = make()
  s.c.deadline = 12345
  assert.equal(await revisePlanInPlace(s.m, '改第三点', s.ctx), true)
  assert.equal(s.c.revising, true)
  assert.equal(s.c.deadline, 0)
  assert.equal(s.c.pendingFeedback, '改第三点')
})

test('退回重出失败：提示、解锁并清意见', async () => {
  const a = make({ reviseApi: async () => ({ success: false, msg: '提交失败' }) })
  assert.equal(await revisePlanInPlace(a.m, '改', a.ctx), false)
  assert.deepEqual(a.calls.warn, ['提交失败'])
  assert.equal(a.c.busy, false)
  assert.equal(a.c.pendingFeedback, '')
})


test('插话调整：空/状态/忙/结束四类拒绝与成功', async () => {
  const m = { planCard: { status: 'approved' }, loading: true }
  const ctx = { messages: () => [m], loading: () => true, steer: async () => 'id1' }
  assert.equal((await steerPlanAdjustment(m, '  ', ctx)).reason, 'empty')

  const bad = { ...m, planCard: { status: 'pending' } }
  assert.equal((await steerPlanAdjustment(bad, '改', ctx)).reason, 'state')

  const out = { ...m }
  assert.equal((await steerPlanAdjustment(out, '改', { ...ctx, messages: () => [] })).reason, 'state')

  const busy = { ...m, loading: false }
  assert.equal((await steerPlanAdjustment(busy, '改', { ...ctx, messages: () => [busy] })).reason, 'busy')

  const ended = { ...m, loading: false }
  assert.equal((await steerPlanAdjustment(ended, '改', { ...ctx, messages: () => [ended], loading: () => false })).reason, 'ended')

  assert.equal((await steerPlanAdjustment(m, '改', { ...ctx, steer: async () => '' })).reason, 'ended')
  assert.deepEqual(await steerPlanAdjustment(m, '改', ctx), { ok: true })
})

test('调整入口：replan 走重新计划，否则走插话', async () => {
  const m = { planCard: { status: 'approved' }, loading: true }
  let replanned = ''
  const ctx = { replan: (mm, t) => { replanned = t; return { ok: true } }, messages: () => [m], loading: () => true, steer: async () => 'id' }
  await adjustPlanAfterApproval(m, '新方向', 'replan', ctx)
  assert.equal(replanned, '新方向')
  assert.deepEqual(await adjustPlanAfterApproval(m, '改', 'steer', ctx), { ok: true })
})

test('插话调整送不进去：能排队就排队，排不了才把原因交回面板', async () => {
  const m = { planCard: { status: 'approved' }, loading: true }
  const queued = []
  const base = { messages: () => [m], loading: () => true, steer: async () => '' }
  const r = await adjustPlanAfterApproval(m, ' 改方向 ', 'steer',
    { ...base, queuePlanAdjust: t => { queued.push(t); return { text: t } } })
  assert.deepEqual(r, { ok: true, queued: true })
  assert.deepEqual(queued, ['改方向'])

  assert.equal((await adjustPlanAfterApproval(m, '改', 'steer', base)).reason, 'ended')
  assert.equal((await adjustPlanAfterApproval(m, '改', 'steer',
    { ...base, queuePlanAdjust: () => null })).reason, 'ended')
})

const replanMake = ({ loading = true, hasStream = true, stopRet = true, settleClears = true } = {}) => {
  const calls = { stop: 0, settle: 0, mark: [], launch: [] }
  const c = { id: 'p1', status: 'approved' }
  const m = { planCard: c, loading, todos: [{ text: '步骤一', status: 'done' }, { text: '步骤二', status: 'doing' }], todoDone: 1, todoTotal: 2, todoFailed: 0 }
  const ctx = {
    messages: () => [m],
    sid: () => 's1',
    model: () => 'a/m',
    loading: () => loading,
    progressOf: mm => ({ items: mm.todos, done: mm.todoDone, total: mm.todoTotal, failed: mm.todoFailed }),
    hasLocalStream: () => hasStream,
    stop: async () => { calls.stop++; return stopRet },
    settle: async mm => { calls.settle++; if (settleClears) mm.loading = false },
    markReplanned: (sid, id, at) => calls.mark.push([sid, id, at]),
    versionOf: () => 3,
    stepCount: () => 5,
    launch: (q, from) => calls.launch.push([q, from])
  }
  return { c, m, calls, ctx }
}

test('重新计划：空/未批准/恢复态/不在列表/无会话/无模型 全部拒绝', async () => {
  const a = replanMake()
  assert.equal((await replanAfterApproval(a.m, '  ', a.ctx)).reason, 'empty')
  assert.equal((await replanAfterApproval({ planCard: { status: 'pending' } }, '改', a.ctx)).reason, 'state')
  assert.equal((await replanAfterApproval({ planCard: { status: 'approved', restored: true } }, '改', a.ctx)).reason, 'restored')
  assert.equal((await replanAfterApproval(a.m, '改', { ...a.ctx, messages: () => [] })).reason, 'state')
  assert.equal((await replanAfterApproval(a.m, '改', { ...a.ctx, sid: () => null })).reason, 'state')
  assert.equal((await replanAfterApproval(a.m, '改', { ...a.ctx, model: () => '' })).reason, 'model')
})

test('重新计划：另一轮在跑时拒绝', async () => {
  const a = replanMake({ loading: true })
  a.m.loading = false
  assert.equal((await replanAfterApproval(a.m, '改', a.ctx)).reason, 'busy')
  assert.equal(a.calls.stop, 0)
})

test('重新计划：执行中先停后收尾，冻结进度并带血缘开新轮', async () => {
  const a = replanMake()
  const r = await replanAfterApproval(a.m, '换成方案 B', a.ctx)
  assert.deepEqual(r, { ok: true })
  assert.equal(a.calls.stop, 1)
  assert.equal(a.calls.settle, 1)
  assert.deepEqual(a.c.execSnapshot, { items: [{ text: '步骤一', status: 'done' }, { text: '步骤二', status: 'doing' }], done: 1, total: 2, failed: 0 })
  assert.equal(a.calls.mark.length, 1)
  assert.equal(a.calls.mark[0][0], 's1')
  assert.equal(a.calls.mark[0][1], 'p1')
  assert.equal(a.calls.launch.length, 1)
  assert.deepEqual(a.calls.launch[0][1], { cardId: 'p1', version: 3, done: 1, total: 2, failed: 0 })
})

test('重新计划：服务端停止失败或本地收尾未完成时不开新轮', async () => {
  const noStop = replanMake({ stopRet: false })
  assert.equal((await replanAfterApproval(noStop.m, '改', noStop.ctx)).reason, 'stop-failed')
  assert.equal(noStop.calls.launch.length, 0)

  const stuck = replanMake({ hasStream: false, settleClears: false })
  assert.equal((await replanAfterApproval(stuck.m, '改', stuck.ctx)).reason, 'stop-failed')
  assert.equal(stuck.calls.stop, 0)
  assert.equal(stuck.calls.launch.length, 0)
})

test('重新计划：本设备无流且已收尾时直接开新轮，无进度快照按步数兜底', async () => {
  const a = replanMake({ hasStream: false })
  a.ctx.progressOf = () => null
  const r = await replanAfterApproval(a.m, '改', a.ctx)
  assert.deepEqual(r, { ok: true })
  assert.equal(a.calls.stop, 0)
  assert.equal(a.calls.settle, 1)
  assert.deepEqual(a.calls.launch[0][1], { cardId: 'p1', version: 3, done: 0, total: 5, failed: 0 })
})
