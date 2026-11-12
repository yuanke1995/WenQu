import test from 'node:test'
import assert from 'node:assert/strict'
import { fanHasBoard, fanHead, fanTitle, fanRowState, fanRun, runBoardText, fanBudget, groupRunning, groupHasError } from '../src/chat/runBoard.js'

test('扇出看板：有无看板、头文案与收尾类型', () => {
  assert.equal(fanHasBoard({ total: 0, inFlight: 0 }), false)
  assert.equal(fanHasBoard({ inFlight: 2 }), true)
  assert.equal(fanHead({ status: 'start', fanOut: { total: 3, inFlight: 2 } }), '子调用 3 次 · 在途 2')
  assert.equal(fanHead({ status: 'done', fanOut: { total: 3 } }), '扇出 3 次')
  assert.equal(fanTitle({ total: 2, inFlight: 1 }), '扇出 2 次工具调用（还有 1 条在跑）')
  assert.equal(fanRun({ run: { kind: 'ok' } }), '')
  assert.equal(fanRun({ run: { kind: 'exit', exit: 3 } }), '退出码 3：脚本可能没跑完')
  assert.equal(fanRun({ run: { kind: 'timeout' } }), '追踪超时：脚本可能还在后台跑')
  assert.equal(fanRun({ run: { kind: 'infra' } }), '执行环境不可用（不是脚本写错）')
})

test('运行水位：子调用/在途/交付', () => {
  assert.equal(runBoardText({ runBoard: { total: 5, max: 20, inFlight: 2, delivered: 1 } }), '脚本运行中 · 子调用 5/20 次 · 在途 2 · 交付 1 个文件')
  assert.equal(runBoardText({}), '')
  assert.equal(fanBudget({ max: 20, used: 4, parallel: 4, delivered: 2 }), '本轮额度 4/20 · 并发上限 4 · 交付 2 个文件')
  assert.equal(fanBudget(null), '')
})

test('分组状态与行状态', () => {
  const g = { tools: [{ status: 'start' }, { status: 'error' }] }
  assert.equal(groupRunning(g), true)
  assert.equal(groupHasError(g), true)
  assert.equal(fanRowState({ status: 'start', running: 2 }), '在跑×2')
  assert.equal(fanRowState({ status: 'done', elapsedMs: 1200 }), '1.2s')
})
