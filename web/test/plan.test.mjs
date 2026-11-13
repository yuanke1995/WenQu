import test from 'node:test'
import assert from 'node:assert/strict'
import {
  planOutline, planStepCount, diffSteps, planExecProgress, planTodosOfTurn, planCardExpanded
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

test('planExecProgress：本轮不在跑（暂停/收尾）时 doing 不冒充「进行中」', () => {
  const card = { status: 'approved' }
  const todos = { turnId: 't1', items: [
    { content: 'a', status: 'done' },
    { content: 'b', status: 'doing' }
  ] }
  const paused = planExecProgress(card, todos, 't1', false)
  assert.deepEqual(paused.items.map(x => x.status), ['done', 'wait'])
  assert.equal(paused.running, false)

  // 跑着的时候必须还是 doing（否则转圈和「进行第 N 步」都会消失）
  const live = planExecProgress(card, todos, 't1', true)
  assert.deepEqual(live.items.map(x => x.status), ['done', 'doing'])
})

test('planExecProgress：冻结快照里残留的 doing 也落成未完成（暂停后不转圈）', () => {
  const card = { status: 'approved' }
  const snapshot = { items: [
    { text: 'a', status: 'done' },
    { text: 'b', status: 'doing' }
  ], done: 1, failed: 0, total: 2 }
  const p = planExecProgress(card, null, 't1', false, snapshot)
  assert.deepEqual(p.items.map(x => x.status), ['done', 'wait'])
})

test('planTodosOfTurn：新回合气泡还没有轮身份时，不能认上一轮那份清单', () => {
  // 旧计划执行完 → 用户点「调整计划」发新一轮：旧气泡带 t1，实时清单也还是 t1（6/6）
  const oldAi = { role: 'ai', turnId: 't1' }
  const liveAi = { role: 'ai' }            // 本轮气泡：done 之前没有 turnId
  const todos = { turnId: 't1', items: [{ content: 'x', status: 'done' }] }
  assert.equal(planTodosOfTurn(todos, liveAi, [{ role: 'user' }, oldAi, { role: 'user' }, liveAi]), false)
  // 本轮自己写的清单（t2）还没在任何气泡上出现过 → 就是本轮的
  assert.equal(planTodosOfTurn({ turnId: 't2', items: [] }, liveAi, [oldAi, liveAi]), true)
  // 历史消息按轮身份严格对：对不上就不认
  assert.equal(planTodosOfTurn(todos, oldAi, [oldAi]), true)
  assert.equal(planTodosOfTurn(todos, { role: 'ai', turnId: 't9' }, []), false)
  // 老服务端清单不带轮身份：无从判断，维持原样认它；没有清单就是不认
  assert.equal(planTodosOfTurn({ items: [] }, liveAi, []), true)
  assert.equal(planTodosOfTurn(null, liveAi, []), false)
})

test('planCardExpanded：本轮在跑时点收起必须真收起（深度执行里点不动的那颗折叠键）', () => {
  // 没表态：执行中自动铺开，跑完收回一行
  assert.equal(planCardExpanded({ live: false, running: true, choice: undefined }), true)
  assert.equal(planCardExpanded({ live: false, running: false, choice: undefined }), false)
  // 用户点过：以用户为准，正在跑也照收；重挂后读回同一份选择仍是收起
  assert.equal(planCardExpanded({ live: false, running: true, choice: false }), false)
  assert.equal(planCardExpanded({ live: false, running: true, choice: true }), true)
  assert.equal(planCardExpanded({ live: false, running: false, choice: false }), false)
  // 待确认/生成中恒展开：裁决入口不能被收起
  assert.equal(planCardExpanded({ live: true, running: false, choice: false }), true)
})
