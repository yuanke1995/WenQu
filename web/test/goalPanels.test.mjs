import test from 'node:test'
import assert from 'node:assert/strict'
import { ref, nextTick } from 'vue'
import { goalPanelActionPlan, goalTurnAnchor, goalKeyOf } from '../src/chat/goalPanels.js'
import { useGoalPanels } from '../src/chat/useGoalPanels.js'

test('动作分派：edit / cancel 不跑引擎动作，delete 跑完才关抽屉', () => {
  assert.deepEqual(goalPanelActionPlan('edit'),
    { closeBefore: true, inlineEdit: true, armCancel: false, run: false, closeAfter: false })
  assert.deepEqual(goalPanelActionPlan('cancel'),
    { closeBefore: true, inlineEdit: false, armCancel: true, run: false, closeAfter: false })
  assert.deepEqual(goalPanelActionPlan('delete'),
    { closeBefore: false, inlineEdit: false, armCancel: false, run: true, closeAfter: true })
  assert.equal(goalPanelActionPlan('pause').closeAfter, false)
  assert.equal(goalPanelActionPlan('pause').run, true)
})

test('跳轮：按 turnId 找回答行并回退到提问行；找不到返回 null', () => {
  const rows = [
    { role: 'user', content: '问1' },
    { role: 'ai', turnId: 't1' },
    { role: 'tool', turnId: 't1' },
    { role: 'user', content: '问2' },
    { role: 'ai', turnId: 't2' }
  ]
  assert.deepEqual(goalTurnAnchor(rows, 't1'), { aiIdx: 1, userIdx: 0 })
  assert.deepEqual(goalTurnAnchor(rows, 't2'), { aiIdx: 4, userIdx: 3 })
  assert.equal(goalTurnAnchor(rows, 'nope'), null)
  assert.deepEqual(goalTurnAnchor([{ role: 'ai', turnId: 't3' }], 't3'), { aiIdx: 0, userIdx: 0 })
})

test('目标键：无目标为空；换目标或换状态都会变', () => {
  assert.equal(goalKeyOf(null), '')
  assert.equal(goalKeyOf({}), '')
  assert.notEqual(goalKeyOf({ goalId: 'g1', status: 'RUNNING' }), goalKeyOf({ goalId: 'g1', status: 'PAUSED' }))
  assert.notEqual(goalKeyOf({ goalId: 'g1', status: 'RUNNING' }), goalKeyOf({ goalId: 'g2', status: 'RUNNING' }))
})

const makeCtx = () => {
  const calls = []
  const scrolls = []
  const ctx = {
    goal: () => ({ goalId: 'g1', status: 'RUNNING' }),
    currentSessionId: ref('s1'),
    messages: () => [{ role: 'user' }, { role: 'ai', turnId: 't1' }],
    runGoalAction: async a => calls.push(a),
    startGoalInlineEdit: () => calls.push('inlineEdit'),
    scrollToRow: i => scrolls.push(i)
  }
  return { ctx, calls, scrolls }
}

test('抽屉动作：改目标 / 取消 / 删除 / 暂停各走对应出口', async () => {
  const { ctx, calls } = makeCtx()
  const p = useGoalPanels(ctx)
  p.openGoalViewer()
  await p.onGoalViewerAction('edit')
  assert.deepEqual(calls, ['inlineEdit'])
  assert.equal(p.goalViewerOpen.value, false)

  p.openGoalViewer()
  await p.onGoalViewerAction('cancel')
  assert.equal(p.goalCancelArmed.value, true)
  assert.equal(p.goalViewerOpen.value, false)
  assert.equal(calls.length, 1)

  await p.confirmGoalCancel()
  assert.equal(p.goalCancelArmed.value, false)
  assert.equal(calls.at(-1), 'cancel')

  p.openGoalViewer()
  await p.onGoalViewerAction('pause')
  assert.equal(calls.at(-1), 'pause')
  assert.equal(p.goalViewerOpen.value, true)
  await p.onGoalViewerAction('delete')
  assert.equal(calls.at(-1), 'delete')
  assert.equal(p.goalViewerOpen.value, false)
})

test('交付抽屉：空包不打开；换会话收起抽屉与目标确认态', async () => {
  const { ctx } = makeCtx()
  const p = useGoalPanels(ctx)
  p.openDelivery(null)
  assert.equal(p.deliveryOpen.value, false)
  const pkg = { id: 'd1' }
  p.openDelivery(pkg)
  assert.equal(p.deliveryOpen.value, true)
  assert.deepEqual(p.deliveryTarget.value, pkg)

  p.openGoalViewer()
  p.armGoalCancel()
  ctx.currentSessionId.value = 's2'
  await nextTick()
  assert.equal(p.deliveryOpen.value, false)
  assert.equal(p.deliveryTarget.value, null)
  assert.equal(p.goalViewerOpen.value, false)
})

test('目标态变化撤下取消确认；跳轮交回提问行下标', async () => {
  const { ctx, scrolls } = makeCtx()
  const g = ref({ goalId: 'g1', status: 'RUNNING' })
  ctx.goal = () => g.value
  const p = useGoalPanels(ctx)
  p.armGoalCancel()
  g.value = { goalId: 'g1', status: 'PAUSED' }
  await nextTick()
  assert.equal(p.goalCancelArmed.value, false)

  p.jumpToGoalTurn('t1')
  assert.deepEqual(scrolls, [0])
  assert.equal(p.goalViewerOpen.value, false)
  p.jumpToGoalTurn('missing')
  assert.equal(scrolls.length, 1)
})
