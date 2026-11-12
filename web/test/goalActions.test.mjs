import test from 'node:test'
import assert from 'node:assert/strict'
import { goalAct } from '../src/chat/goalActions.js'

const make = (over = {}) => {
  const calls = { set: [], watch: [], warn: [], err: [] }
  const goal = { goalId: 'g1', status: 'RUNNING', busy: false, actionError: '' }
  const ctx = {
    goal: () => goal,
    goalOf: () => goal,
    setGoal: (sid, v) => calls.set.push({ sid, v }),
    currentSid: () => 's1',
    action: async () => ({ success: true, data: { goalId: 'g1', status: 'RUNNING' } }),
    watchGoalTurn: sid => calls.watch.push(sid),
    barVisible: () => true,
    notify: { warning: m => calls.warn.push(m), error: m => calls.err.push(m) },
    ...over
  }
  return { goal, calls, ctx }
}

test('目标动作：无目标/忙时直接返回', async () => {
  const { ctx } = make({ goal: () => null })
  assert.equal(await goalAct('pause', {}, ctx), null)
  const busy = make()
  busy.goal.busy = true
  assert.equal(await goalAct('pause', {}, busy.ctx), null)
})

test('目标动作：成功写回目标视图；delete 清空；resume 触发接流', async () => {
  const a = make()
  const r = await goalAct('pause', {}, a.ctx)
  assert.equal(r.status, 'RUNNING')
  assert.equal(a.calls.set.length, 1)
  assert.equal(a.calls.watch.length, 0)
  assert.equal(a.goal.busy, false)

  const b = make({ action: async () => ({ success: true, data: null }) })
  await goalAct('delete', {}, b.ctx)
  assert.equal(b.calls.set[0].v, null)

  const c = make()
  await goalAct('resume', {}, c.ctx)
  assert.deepEqual(c.calls.watch, ['s1'])
})

test('目标动作：edit 后仍在跑也触发接流；失败落在 actionError，条子不可见才弹', async () => {
  const a = make()
  await goalAct('edit', { objective: 'x' }, a.ctx)
  assert.deepEqual(a.calls.watch, ['s1'])

  const b = make({ action: async () => ({ success: false, msg: '不让改' }), barVisible: () => false })
  assert.equal(await goalAct('edit', {}, b.ctx), null)
  assert.equal(b.goal.actionError, '不让改')
  assert.deepEqual(b.calls.warn, ['不让改'])

  const c = make({ action: async () => ({ success: false, msg: '不让改' }), barVisible: () => true })
  await goalAct('edit', {}, c.ctx)
  assert.equal(c.calls.warn.length, 0)
})

test('目标动作：异常也内联错误并解除 busy', async () => {
  const a = make({ action: async () => { throw new Error('网络断了') }, barVisible: () => false })
  assert.equal(await goalAct('pause', {}, a.ctx), null)
  assert.equal(a.goal.actionError, '网络断了')
  assert.deepEqual(a.calls.err, ['网络断了'])
  assert.equal(a.goal.busy, false)
})
