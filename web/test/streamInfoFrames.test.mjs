import test from 'node:test'
import assert from 'node:assert/strict'
import { applyTodoFrame, applyUsageFrame, applyRetrievedFrame, applySkillHitFrame,
         applyApprovalCardFrame, applyRunCodeProgress, applyAgentDelegated } from '../src/chat/streamInfoFrames.js'

test('任务清单：整表写入；坏载荷/缺 items 不动', () => {
  const calls = []
  applyTodoFrame('s1', { items: [{ content: 'a' }] }, (sid, j) => calls.push([sid, j]))
  assert.equal(calls.length, 1)
  assert.equal(calls[0][0], 's1')
  assert.deepEqual(calls[0][1].items, [{ content: 'a' }])

  applyTodoFrame('s1', '{bad', (sid, j) => calls.push([sid, j]))
  applyTodoFrame('s1', { items: null }, (sid, j) => calls.push([sid, j]))
  assert.equal(calls.length, 1)
})

test('用量预览：对象覆盖写；坏载荷不动', () => {
  const msg = {}
  applyUsageFrame(msg, { context: 1 })
  assert.deepEqual(msg.tokensPreview, { context: 1 })
  applyUsageFrame(msg, { context: 2 })
  assert.deepEqual(msg.tokensPreview, { context: 2 })
  applyUsageFrame(msg, '{bad')
  assert.deepEqual(msg.tokensPreview, { context: 2 })
})

test('检索：字段归一 + 滚动；坏 JSON 不动', () => {
  let scroll = 0
  const msg = {}
  applyRetrievedFrame(msg, JSON.stringify({ keywords: 3, refs: 2, terms: ['x'] }), () => { scroll++ })
  assert.deepEqual(msg.retrieved, { keywords: 3, refs: 2, terms: ['x'] })
  assert.equal(scroll, 1)
  applyRetrievedFrame(msg, '{bad', () => { scroll++ })
  assert.equal(scroll, 1)
})

test('技能命中：按名字去重，后到的 picked 升级、model 不降级', () => {
  const msg = {}
  applySkillHitFrame(msg, { name: 'doc', source: 'model' })
  applySkillHitFrame(msg, { name: 'doc', source: 'model' })
  assert.deepEqual(msg.skillHits, [{ name: 'doc', source: 'model' }])
  applySkillHitFrame(msg, { name: 'doc', source: 'picked' })
  assert.equal(msg.skillHits[0].source, 'picked')
  applySkillHitFrame(msg, { name: 'doc', source: 'model' })
  assert.equal(msg.skillHits[0].source, 'picked')
  applySkillHitFrame(msg, { name: '  ', source: 'model' })
  assert.equal(msg.skillHits.length, 1)
})

test('审批卡：按 id 去重追加，deadline 从下发时刻起算', () => {
  const msg = {}
  const before = Date.now()
  applyApprovalCardFrame(msg, { approvalId: 'a', tool: 'write', timeoutMs: 1000 })
  assert.equal(msg.approvals.length, 1)
  assert.equal(msg.approvals[0].id, 'a')
  assert.ok(msg.approvals[0].deadline >= before + 1000)
  applyApprovalCardFrame(msg, { approvalId: 'a', tool: 'write' })
  assert.equal(msg.approvals.length, 1)
  applyApprovalCardFrame(msg, { approvalId: 'b', tool: 'shell', subagent: { id: 1 } })
  assert.equal(msg.approvals.length, 2)
  assert.deepEqual(msg.approvals[1].subagent, { id: 1 })
  assert.equal(msg.approvals[1].timeoutMs, 120000)
})

test('脚本进度：挂到进行中的 run_code 卡与消息总览；无卡/无 fanOut 忽略', () => {
  const msg = { toolCalls: [{ name: 'run_code', status: 'start' }] }
  applyRunCodeProgress(msg, { fanOut: { rows: [1] } })
  assert.deepEqual(msg.runBoard, { rows: [1] })
  assert.deepEqual(msg.toolCalls[0].fanOut, { rows: [1] })

  const idle = { toolCalls: [{ name: 'run_code', status: 'done' }] }
  applyRunCodeProgress(idle, { fanOut: { rows: [] } })
  assert.equal(idle.runBoard, undefined)

  applyRunCodeProgress(msg, {})
  assert.deepEqual(msg.runBoard, { rows: [1] })
})

test('@ 智能体归属：名字/ID 改写并滚动；无名字忽略', () => {
  let scroll = 0
  const msg = {}
  applyAgentDelegated(msg, { id: 'a1', name: '甲' }, () => { scroll++ })
  assert.equal(msg.agentId, 'a1')
  assert.equal(msg.agentName, '甲')
  assert.equal(scroll, 1)
  applyAgentDelegated(msg, { id: 'x' }, () => { scroll++ })
  assert.equal(msg.agentName, '甲')
  assert.equal(scroll, 1)
})
