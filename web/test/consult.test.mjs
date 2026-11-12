import test from 'node:test'
import assert from 'node:assert/strict'
import { rememberAgents, consultNameOf, consultChatView, isDispatchTool, dispatchTaskOf, spawnAnchorOf, subagentRunsOf } from '../src/chat/consult.js'

test('咨询：后端 agentName 优先，其次登记表，工具名两代都认', () => {
  rememberAgents([{ id: 'a1', name: '调研助手' }])
  assert.equal(consultNameOf({ subChat: { agentName: '卡上名字' }, args: '{"agentId":"a1"}' }), '卡上名字')
  assert.equal(consultNameOf({ args: '{"agent_id":"a1"}' }), '调研助手')
  assert.equal(consultNameOf({ args: 'bad-json' }), '')
  assert.equal(isDispatchTool('consultAgent'), true)
  assert.equal(isDispatchTool('agent_spawn'), true)
  assert.equal(isDispatchTool('webSearch'), false)
})

test('子对话视图：无 subChat 返回 null；有则规范化步骤与耗时', () => {
  assert.equal(consultChatView({}), null)
  const v = consultChatView({ subChat: { agentName: 'A', question: '问', answer: '答', toolSteps: 2,
    steps: [{ n: 'search', l: '检索', ms: 12 }, { n: 'x', s: 'error' }, null], elapsedMs: 1234 } })
  assert.equal(v.name, 'A')
  assert.equal(v.steps, 2)
  assert.equal(v.stepList.length, 2)
  assert.equal(v.stepList[1].st, 'error')
  assert.equal(v.dur, '1.2s')
})

test('任务原文与定位锚', () => {
  assert.equal(dispatchTaskOf({ subChat: { question: '跑完了' }, args: '{"task":"原始"}' }), '跑完了')
  assert.equal(dispatchTaskOf({ args: '{"task":"原始"}' }), '原始')
  assert.equal(spawnAnchorOf({ startedAtMs: 100, name: 'agent_spawn' }), '100-agent_spawn')
})

test('顶栏子智能体：只列派遣卡，运行态/失败态/耗时归一口径', () => {
  const runs = subagentRunsOf({ toolCalls: [
    { name: 'webSearch', status: 'start' },
    { name: 'agent_spawn', status: 'start', _live: '正在检索', _liveStart: 5, subChat: { agentName: 'A' } },
    { name: 'consultAgent', status: 'done', elapsedMs: 800, subChat: { status: 'error' } },
  ] })
  assert.equal(runs.length, 2)
  assert.equal(runs[0].name, 'A')
  assert.equal(runs[0].running, true)
  assert.equal(runs[0].live, '正在检索')
  assert.equal(runs[1].failed, true)
  assert.equal(runs[1].elapsedMs, 800)
})
