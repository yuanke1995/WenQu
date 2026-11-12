import test from 'node:test'
import assert from 'node:assert/strict'
import { applyToolStatus, appendToolOutput } from '../src/chat/streamToolFrames.js'

const ctxOf = () => {
  const calls = { think: 0, tick: 0, scroll: 0, timeline: [], retired: 0 }
  return {
    ctx: {
      thinkRoundEnd: () => { calls.think++ },
      ensureTick: () => { calls.tick++ },
      liveScroll: () => { calls.scroll++ },
      isDispatchTool: name => name === 'dispatch',
      pushTimelineTool: (m, card) => { calls.timeline.push(card.name) },
      retireAskCard: () => { calls.retired++ }
    },
    calls
  }
}

test('start：新卡入列并进时间线；askUser 不渲染通用卡', () => {
  const msg = {}
  const { ctx, calls } = ctxOf()
  applyToolStatus(msg, { name: 'read_file', status: 'start', args: 'a' }, ctx)
  assert.equal(msg.toolCalls.length, 1)
  assert.equal(msg.toolCalls[0].status, 'start')
  assert.deepEqual(calls.timeline, ['read_file'])
  assert.equal(calls.tick, 1)
  assert.equal(calls.think, 1)

  applyToolStatus(msg, { name: 'askUser', status: 'start' }, ctx)
  assert.equal(msg.toolCalls.length, 1)
})

test('start：早产卡先亮，后续进度就地更新，正式 start 原地升级', () => {
  const msg = {}
  const { ctx, calls } = ctxOf()
  applyToolStatus(msg, { name: 'write_file', status: 'start', preparing: true, chars: 3 }, ctx)
  assert.equal(msg.toolCalls[0].preparing, true)
  applyToolStatus(msg, { name: 'write_file', status: 'start', preparing: true, chars: 9, brief: '在写' }, ctx)
  assert.equal(msg.toolCalls.length, 1)
  assert.equal(msg.toolCalls[0].chars, 9)
  assert.equal(msg.toolCalls[0].brief, '在写')

  applyToolStatus(msg, { name: 'write_file', status: 'start', args: '{}', label: '写文件' }, ctx)
  assert.equal(msg.toolCalls.length, 1)
  const card = msg.toolCalls[0]
  assert.equal(card.preparing, false)
  assert.equal(card.chars, undefined)
  assert.equal(card.args, '{}')
  assert.equal(card.label, '写文件')
  assert.deepEqual(calls.timeline, ['write_file'])
})

test('start：重复帧幂等，不推第二张卡', () => {
  const msg = {}
  const { ctx, calls } = ctxOf()
  applyToolStatus(msg, { name: 'read_file', status: 'start', args: 'x' }, ctx)
  applyToolStatus(msg, { name: 'read_file', status: 'start', args: 'x' }, ctx)
  assert.equal(msg.toolCalls.length, 1)
  assert.equal(calls.timeline.length, 1)
})

test('start：派遣工具挂本地起表点', () => {
  const msg = {}
  const { ctx } = ctxOf()
  const before = Date.now()
  applyToolStatus(msg, { name: 'dispatch', status: 'start' }, ctx)
  assert.ok(msg.toolCalls[0]._liveStart >= before)
})

test('终态：按名字+入参认领 start 卡并合并字段', () => {
  const msg = {}
  const { ctx } = ctxOf()
  applyToolStatus(msg, { name: 'search', status: 'start', args: 'q1' }, ctx)
  applyToolStatus(msg, { name: 'search', status: 'start', args: 'q2' }, ctx)
  applyToolStatus(msg, { name: 'search', status: 'done', args: 'q2', elapsedMs: 120, startedAtMs: 5,
                         waitedMs: 30, result: 'ok', subChat: { steps: [] } }, ctx)
  const [a, b] = msg.toolCalls
  assert.equal(a.status, 'start')
  assert.equal(b.status, 'done')
  assert.equal(b.elapsedMs, 120)
  assert.equal(b.startedAtMs, 5)
  assert.equal(b.waitedMs, 30)
  assert.equal(b.result, 'ok')
  assert.deepEqual(b.subChat, { steps: [] })
})

test('终态：认不到 start 卡就补一张；askUser 终态撤提问卡、run_code 清看板', () => {
  const msg = { ask: { id: 1 }, runBoard: { rows: [] } }
  const { ctx, calls } = ctxOf()
  applyToolStatus(msg, { name: 'search', status: 'error', result: 'x' }, ctx)
  assert.equal(msg.toolCalls.length, 1)
  assert.equal(msg.toolCalls[0].status, 'error')

  applyToolStatus(msg, { name: 'askUser', status: 'done' }, ctx)
  assert.equal(calls.retired, 1)
  applyToolStatus(msg, { name: 'run_code', status: 'done' }, ctx)
  assert.equal(msg.runBoard, null)
})

test('坏载荷：不抛错也不动卡片', () => {
  const msg = {}
  const { ctx, calls } = ctxOf()
  applyToolStatus(msg, '{bad', ctx)
  applyToolStatus(msg, null, ctx)
  assert.equal(msg.toolCalls, undefined)
  assert.equal(calls.think, 0)
})

test('实时输出：追加到进行中的同名卡；无卡/无增量忽略', () => {
  const msg = { toolCalls: [{ name: 'run_code', status: 'start' }] }
  appendToolOutput(msg, { name: 'run_code', delta: 'a' })
  appendToolOutput(msg, { name: 'run_code', delta: 'b' })
  assert.equal(msg.toolCalls[0].liveOutput, 'ab')
  appendToolOutput(msg, { name: 'other', delta: 'x' })
  appendToolOutput(msg, { name: 'run_code' })
  assert.equal(msg.toolCalls[0].liveOutput, 'ab')
})

test('实时输出：超上限保留尾部并标记截断', () => {
  const msg = { toolCalls: [{ name: 't', status: 'start', liveOutput: 'x'.repeat(65535) }] }
  appendToolOutput(msg, { name: 't', delta: 'END' })
  assert.equal(msg.toolCalls[0].liveOutput.length, 65536)
  assert.ok(msg.toolCalls[0].liveOutput.endsWith('END'))
  assert.equal(msg.toolCalls[0].outputTruncated, true)
})
