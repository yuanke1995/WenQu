import test from 'node:test'
import assert from 'node:assert/strict'
import {
  goalStateLabelOf, goalStateClassOf, goalRoundLineOf, goalBlockReasonOf,
  goalProgressPercentOf, goalTerminalCardOf, goalTerminalTitleOf, goalTerminalNoteOf,
  goalCanPauseOf, goalCanResumeOf, goalCanEditOf, goalVersionLabelOf
} from '../src/chat/deepState.js'

test('深度执行：状态词与色调覆盖六档', () => {
  assert.equal(goalStateLabelOf({ status: 'RUNNING' }), '进行中')
  assert.equal(goalStateClassOf({ status: 'RUNNING' }), 'running')
  assert.equal(goalStateLabelOf({ status: 'RUNNING', phase: 'COMPLETION_CHECK' }), '确认中')
  assert.equal(goalStateLabelOf({ status: 'RUNNING', stale: true }), '待恢复')
  assert.equal(goalStateClassOf({ status: 'RUNNING', stale: true }), 'paused')
  assert.equal(goalStateLabelOf({ status: 'PAUSED', endReason: 'preempted' }), '已让位')
  assert.equal(goalStateLabelOf({ status: 'ACHIEVED' }), '已完成')
  assert.equal(goalStateClassOf({ status: 'FAILED' }), 'failed')
  assert.equal(goalStateLabelOf({ status: 'ENDED_INCOMPLETE' }), '已取消')
})

test('深度执行：轮次只报已跑轮数，不暴露兜底上限', () => {
  assert.equal(goalRoundLineOf(null), '')
  assert.equal(goalRoundLineOf({ round: 0, maxRounds: 40 }), '准备开始')
  assert.equal(goalRoundLineOf({ round: 3, maxRounds: 40 }), '第 3 轮')
})

test('深度执行：停因只在真停住时挂上限，其他人话可读', () => {
  assert.equal(goalBlockReasonOf({ status: 'PAUSED', endReason: 'round-limit', round: 40 }), '已到自动推进上限（第 40 轮），先停在这里')
  assert.equal(goalBlockReasonOf({ status: 'RUNNING', endReason: 'round-limit', round: 40 }), '')
  assert.equal(goalBlockReasonOf({ status: 'PAUSED', endReason: 'blocked' }), '受阻：助手报告目标无法继续')
  assert.equal(goalBlockReasonOf({ status: 'PAUSED', endReason: 'turn-failed', blockedReason: '模型超时' }), '这一轮没跑完：模型超时')
})

test('深度执行：预算百分比取整并夹在 0–100', () => {
  assert.equal(goalProgressPercentOf({ budget: { percent: 0.426 } }), 43)
  assert.equal(goalProgressPercentOf({ budget: { percent: 1.5 } }), 100)
  assert.equal(goalProgressPercentOf({ budget: { percent: -1 } }), 0)
  assert.equal(goalProgressPercentOf({}), null)
})

test('深度执行：终态卡与动作判据', () => {
  assert.equal(goalTerminalCardOf({ status: 'ACHIEVED' }), true)
  assert.equal(goalTerminalCardOf({ status: 'CANCELLED', endReason: 'cleared' }), false)
  assert.equal(goalTerminalCardOf({ status: 'RUNNING' }), false)
  assert.equal(goalTerminalTitleOf({ status: 'ACHIEVED' }), '目标已完成')
  assert.equal(goalTerminalNoteOf({ status: 'ACHIEVED', budget: { rounds: 3 } }), '共 3 轮完成，过程与产物都保留')
  assert.equal(goalTerminalNoteOf({ status: 'FAILED' }), '停在未完成：改一下目标就能接着跑；删除记录只撤下这张卡')
  assert.equal(goalCanPauseOf({ status: 'RUNNING' }), true)
  assert.equal(goalCanResumeOf({ status: 'PAUSED' }), true)
  assert.equal(goalCanEditOf({ status: 'ACHIEVED' }), false)
})

test('深度执行：目标版本号按数值相加，不拼成 v01', () => {
  assert.equal(goalVersionLabelOf(null), '')
  assert.equal(goalVersionLabelOf({ objectiveVersion: 0 }), 'v1')
  assert.equal(goalVersionLabelOf({ objectiveVersion: 2 }), 'v3')
  assert.equal(goalVersionLabelOf({}), 'v1')
})
