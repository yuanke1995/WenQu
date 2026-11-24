import test from 'node:test'
import assert from 'node:assert/strict'
import { rememberAgents } from '../src/chat/consult.js'
import { toolBrief, prettyIo, liveOutput, oneLine } from '../src/chat/toolBrief.js'

test('工具摘要：白名单字段优先，数组转 JSON，非 JSON 纯文本截断', () => {
  assert.equal(toolBrief({ name: 'execute', args: JSON.stringify({ command: 'ls -la' }) }), 'ls -la')
  assert.equal(toolBrief({ name: 'x', args: JSON.stringify({ kbIds: ['a', 'b'] }) }), '["a","b"]')
  assert.equal(toolBrief({ name: 'x', args: 'plain text' }), 'plain text')
  assert.equal(toolBrief({ name: 'x', args: JSON.stringify({ other: 'v' }) }), 'v')
  assert.equal(toolBrief({ name: 'x' }), '')
})

test('工具摘要：早产卡只报正在生成，带 brief 时前置', () => {
  assert.equal(toolBrief({ preparing: true, chars: 1234 }), '正在生成 1,234 字')
  assert.equal(toolBrief({ preparing: true, chars: 0 }), '正在生成内容')
  assert.equal(toolBrief({ preparing: true, chars: 5, brief: '报告.md' }), '报告.md · 正在生成 5 字')
})

test('工具摘要：派遣亮助手名，技能读亮裸技能名', () => {
  rememberAgents([{ id: 'a1', name: '调研助手' }])
  assert.equal(toolBrief({ name: 'agent_spawn', args: JSON.stringify({ agentId: 'a1' }) }), '调研助手')
  assert.equal(toolBrief({ name: 'read_skill', args: JSON.stringify({ skillId: '写报告_workspace' }) }), '写报告')
})

test('工具摘要：团队工具亮成员名/任务标题，不把消息正文搬上标题', () => {
  assert.equal(toolBrief({ name: 'spawnMember', args: JSON.stringify({ member_name: 'reviewer', agent_ref: 'a1b2c3' }) }), 'reviewer')
  assert.equal(toolBrief({ name: 'createTask', args: JSON.stringify({ subject: '写一句团队口号', owner: 'writer' }) }), '写一句团队口号')
  assert.equal(toolBrief({ name: 'sendMessage', args: JSON.stringify({ to_member: 'writer', message: '很长的一段正文' }) }), 'writer')
  assert.equal(toolBrief({ name: 'team_task_depend', args: JSON.stringify({ from: '调研', to: '汇总' }) }), '调研')
})

test('工具摘要：统一入口 team(action=…) 把动作换成中文并带上关键入参', () => {
  assert.equal(toolBrief({ name: 'team', args: JSON.stringify({ action: 'listTasks' }) }), '查看团队任务')
  assert.equal(toolBrief({ name: 'team', args: JSON.stringify({ action: 'sendMessage', to_member: 'writer' }) }), '给成员发消息 · writer')
  assert.equal(toolBrief({ name: 'team', args: JSON.stringify({ action: 'createTask', subject: '写口号', owner: 'writer' }) }), '创建团队任务 · 写口号')
})

test('实时输出：运行中取尾部，超长加省略提示；终态用 result', () => {
  assert.equal(liveOutput({ status: 'start', liveOutput: 'x'.repeat(5000), outputTruncated: false }).length, 4000)
  assert.ok(liveOutput({ status: 'start', liveOutput: 'abc', outputTruncated: true }).startsWith('…（前面输出已省略）'))
  assert.equal(liveOutput({ status: 'done', result: '全文' }), '全文')
  assert.equal(liveOutput({ status: 'start' }), '')
})

test('prettyIo 与 oneLine', () => {
  assert.equal(prettyIo('{"a":1}'), '{\n  "a": 1\n}')
  assert.equal(prettyIo('plain'), 'plain')
  assert.equal(prettyIo(''), '')
  assert.equal(oneLine('a  b\nc', 10), 'a b c')
})
