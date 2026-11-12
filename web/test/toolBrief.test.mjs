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
