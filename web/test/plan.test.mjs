import test from 'node:test'
import assert from 'node:assert/strict'
import {
  planOutline, planStepCount, diffSteps, planExecProgress
} from '../src/chat/plan.js'

test('planOutline：目标 + 顶层步骤，缩进子项并入上一步', () => {
  const plan = `# 执行计划
目标是给问答补一条回归测试。

1. 补检索门单测：覆盖低分保底
   - 用 0.192 的夹具
2. 跑全量测试
`
  const o = planOutline(plan)
  assert.equal(o.goal, '目标是给问答补一条回归测试。')
  assert.equal(o.steps.length, 2)
  assert.equal(o.steps[0].title, '补检索门单测')
  assert.match(o.steps[0].detail, /覆盖低分保底/)
  assert.match(o.steps[0].detail, /0\.192/)
  assert.equal(planStepCount(plan), 2)
})

test('planOutline：只有封面词时 goal 为空，不把标签当目标', () => {
  const o = planOutline('# 执行计划\n\n- 第一步\n- 第二步')
  assert.equal(o.goal, '')
  assert.equal(o.steps.length, 2)
})

test('diffSteps：同位置变化与新增都标出来', () => {
  const d = diffSteps('- 甲\n- 乙\n- 丙', '- 甲\n- 乙改\n- 丁\n- 戊')
  assert.deepEqual([...d.changed].sort(), [1, 2])
  assert.deepEqual([...d.added], [3])
  assert.deepEqual(d.removed, [])
  assert.equal(d.changedCount, 2)
  assert.equal(d.addedCount, 1)
})

test('diffSteps：新版变短时旧版多出的步骤进 removed', () => {
  const d = diffSteps('- 甲\n- 乙\n- 丙', '- 甲\n- 乙')
  assert.deepEqual(d.removed, ['丙'])
  assert.equal(d.removedCount, 1)
})

test('planExecProgress：未批准不编造进度', () => {
  assert.equal(planExecProgress({ status: 'pending' }, { items: [{ content: 'x', status: 'done' }] }, 't1', false), null)
})

test('planExecProgress：清单不属于本轮且无快照时返回 null', () => {
  const card = { status: 'approved' }
  const todos = { turnId: 't2', items: [{ content: 'x', status: 'done' }] }
  assert.equal(planExecProgress(card, todos, 't1', false), null)
})

test('planExecProgress：本轮清单命中时按 done/doing/failed 映射', () => {
  const card = { status: 'approved' }
  const todos = { turnId: 't1', items: [
    { content: 'a', status: 'done' },
    { content: 'b', status: 'doing' },
    { content: 'c', status: 'failed' },
    { content: 'd', status: 'wait' }
  ] }
  const p = planExecProgress(card, todos, 't1', true)
  assert.deepEqual(p.items.map(x => x.status), ['done', 'doing', 'failed', 'wait'])
  assert.equal(p.done, 1)
  assert.equal(p.failed, 1)
  assert.equal(p.total, 4)
  assert.equal(p.running, true)
})

test('planExecProgress：回退冻结快照时不再显示 running', () => {
  const card = { status: 'approved' }
  const todos = { turnId: 't2', items: [{ content: 'x', status: 'done' }] }
  const snapshot = { items: [{ text: '旧一轮', status: 'done' }], done: 1, failed: 0, total: 1 }
  const p = planExecProgress(card, todos, 't1', false, snapshot)
  assert.equal(p.total, 1)
  assert.equal(p.running, false)
  assert.deepEqual(p.items, snapshot.items)
})
