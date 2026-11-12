import test from 'node:test'
import assert from 'node:assert/strict'
import { applyTurnSnapshot, settlePreparingCards, finishResumedShell, replaySnapshotExtras } from '../src/chat/streamReducer.js'

const ctx = (over = {}) => ({
  setTodos: () => {},
  autoCompactSid: { value: null },
  liveCompactedSid: { value: null },
  planCardFromRecord: () => ({ from: 'record' }),
  ...over
})

test('快照：running!==true 不应用；基础字段覆盖，空数组不覆盖已有', () => {
  assert.equal(applyTurnSnapshot({}, { running: false }, 's1', ctx()), false)
  const msg = { sources: [{ id: 1 }], artifacts: [{ id: 'a' }] }
  const ok = applyTurnSnapshot(msg, {
    running: true, content: '正文', thinking: '想', processText: '说', thinkingOpen: true,
    toolCalls: [{ name: 't' }], sources: [], artifacts: [], images: [], model: 'm', turnId: 'turn1',
  }, 's1', ctx())
  assert.equal(ok, true)
  assert.equal(msg.content, '正文')
  assert.equal(msg.thinking, '想')
  assert.equal(msg.thinkLoading, true)
  assert.equal(msg.toolCalls.length, 1)
  assert.deepEqual(msg.sources, [{ id: 1 }])
  assert.deepEqual(msg.artifacts, [{ id: 'a' }])
  assert.equal(msg.turnId, 'turn1')
})

test('快照：时间线在工具清单之后重建；思考全文无段时补段', () => {
  const msg = { thinking: '想一想' }
  applyTurnSnapshot(msg, {
    running: true,
    toolCalls: [{ name: 'read_file' }, { name: 'write_file' }],
    timeline: [{ kind: 'tool', i: 1 }, { kind: 'think', from: 0, to: 3 }],
  }, 's1', ctx())
  assert.equal(msg.timeline.length, 2)
  assert.equal(msg.timeline[0].tool.name, 'write_file')
  assert.equal(msg.timeline[1].kind, 'think')
  // 只有思考全文、没有 think 段时头部补一段
  const onlyText = { thinking: '想' }
  applyTurnSnapshot(onlyText, { running: true, content: 'x', toolCalls: [{ name: 't' }],
    timeline: [{ kind: 'tool', i: 0 }] }, 's1', ctx())
  assert.equal(onlyText.timeline[0].kind, 'think')
})

test('快照：计划卡 pending 用 remainingMs 换算 deadline，保留用户 busy 与意见', () => {
  const msg = { planCard: { busy: true, editText: '我的意见' } }
  applyTurnSnapshot(msg, {
    running: true,
    planCard: { status: 'pending', remainingMs: 60000, versions: [{ plan: 'p', feedback: 'f' }] },
  }, 's1', ctx())
  assert.equal(msg.planCard.busy, true)
  assert.equal(msg.planCard.editText, '我的意见')
  assert.ok(msg.planCard.deadline > Date.now())
  assert.deepEqual(msg.planCard.versions, [{ plan: 'p', feedback: 'f' }])
  // 只有 planRecord 时走派生函数
  const m2 = {}
  applyTurnSnapshot(m2, { running: true, planRecord: { x: 1 } }, 's1', ctx())
  assert.deepEqual(m2.planCard, { from: 'record' })
})

test('快照：状态型事件回放（plan/retrieved/usage/todo/warn/compact_state）', () => {
  let todos = null
  const c = ctx({ setTodos: (sid, j) => { todos = { sid, j } } })
  const msg = {}
  applyTurnSnapshot(msg, { running: true, events: {
    plan: '["a"]', retrieved: '{"keywords":2,"refs":3,"terms":["x"]}',
    usage: '{"total":10}', todo: '{"items":[{"content":"x"}]}', warn: '注意',
    compact_state: '{"phase":"start"}',
  } }, 's1', c)
  assert.deepEqual(msg.plan, ['a'])
  assert.deepEqual(msg.retrieved, { keywords: 2, refs: 3, terms: ['x'] })
  assert.deepEqual(msg.tokensPreview, { total: 10 })
  assert.equal(todos.sid, 's1')
  assert.equal(todos.j.items.length, 1)
  assert.equal(msg.warnMsg, '注意')
  assert.equal(msg.compacting, true)
  assert.equal(c.autoCompactSid.value, 's1')
})


test('早产卡收场：只标仍 preparing 的卡，正式卡不动', () => {
  const m = { toolCalls: [
    { preparing: true, chars: 3, brief: 'x', status: 'start' },
    { preparing: false, status: 'done' },
  ] }
  settlePreparingCards(m, '本轮中断，这一步没有执行')
  assert.equal(m.toolCalls[0].preparing, false)
  assert.equal(m.toolCalls[0].status, 'error')
  assert.equal(m.toolCalls[0].result, '本轮中断，这一步没有执行')
  assert.equal(m.toolCalls[0].chars, undefined)
  assert.equal(m.toolCalls[1].status, 'done')
  assert.doesNotThrow(() => settlePreparingCards(null, 'x'))
})

test('接流气泡收场：只摘自己那条流，按需重显', () => {
  const st = { id: 1 }
  const streams = new Map([['s1', st]])
  let switched = ''
  const ctx = { chatStreams: streams, currentSid: () => 's1', switchSession: sid => { switched = sid } }
  const msg = { loading: true, retrying: true }
  finishResumedShell(msg, st, 's1', true, ctx)
  assert.equal(streams.has('s1'), false)
  assert.equal(msg.loading, false)
  assert.equal(msg.retrying, false)
  assert.equal(switched, 's1')

  const streams2 = new Map([['s1', { id: 2 }]])
  const ctx2 = { chatStreams: streams2, currentSid: () => 'other', switchSession: () => { throw new Error('不该重显') } }
  finishResumedShell({}, { id: 1 }, 's1', true, ctx2)
  assert.equal(streams2.has('s1'), true)
})


test('快照回放：先清本地增量，再按实时回调重建工具输出/早产卡/子步骤/脚本进度/目标态', () => {
  const calls = { out: [], status: [], sub: [], run: 0, deep: 0 }
  const msg = { toolCalls: [
    { name: 'read', status: 'start', liveOutput: 'old', outputTruncated: true, _steps: [1] },
    { name: 'write', status: 'start', preparing: true, _live: {} },
    { name: 'run_code', status: 'start' }
  ], runBoard: { old: 1 } }
  const handlers = {
    onToolOutput: o => calls.out.push(o),
    onToolStatus: f => calls.status.push(f),
    onSubagentStep: f => calls.sub.push(f),
    onRunCodeProgress: p => { calls.run++; calls.runPayload = p },
    onDeepState: p => calls.deep++
  }
  replaySnapshotExtras(msg, {
    toolOutputs: { read: { text: '全文', truncated: true }, bad: { text: 5 } },
    preparing: [{ name: 'plan' }],
    subagentSteps: [{ agent: 'x' }],
    events: { runcode_progress: { fanOut: 2 }, deep_state: { goalId: 'g1' } }
  }, handlers)
  assert.deepEqual(calls.out, [{ name: 'read', delta: '全文' }])
  assert.equal(msg.toolCalls[0].liveOutput, undefined)
  assert.equal(msg.toolCalls[0].outputTruncated, true)
  assert.equal(msg.toolCalls[0]._steps, undefined)
  assert.deepEqual(calls.status, [{ name: 'plan' }])
  assert.equal(msg.toolCalls.some(t => t.preparing), false)
  assert.deepEqual(calls.sub, [{ agent: 'x' }])
  assert.equal(calls.run, 1)
  assert.equal(calls.runPayload, JSON.stringify({ fanOut: 2 }))
  assert.equal(calls.deep, 1)
})

test('快照回放：无脚本进度且没有在跑的 run_code 时清掉总览看板', () => {
  const msg = { toolCalls: [{ name: 'run_code', status: 'done' }], runBoard: { old: 1 } }
  replaySnapshotExtras(msg, { events: {} }, { onToolOutput: () => {}, onToolStatus: () => {}, onSubagentStep: () => {}, onRunCodeProgress: () => {}, onDeepState: () => {} })
  assert.equal(msg.runBoard, null)
})
