import test from 'node:test'
import assert from 'node:assert/strict'
import { bareToolName, toolLabel, toolDesc, executedCount, toolDuration, toolRunning } from '../src/chat/tools.js'

test('工具名：后端展示名优先，裸名回落本地映射，MCP 前缀去掉再映射', () => {
  assert.equal(toolLabel('search_knowledge', '检索知识库'), '检索知识库')
  assert.equal(toolLabel('search_knowledge', 'search_knowledge'), '检索知识库')
  assert.equal(toolLabel('w_q_sql_query', 'w_q_sql_query'), 'w_q_sql_query')
  assert.equal(toolLabel('w_q_webSearch'), '联网搜索')
  assert.equal(toolLabel('unknown_tool'), 'unknown_tool')
  assert.equal(bareToolName('w_q_sql_query'), 'sql_query')
})

test('工具说明：后端 hint 优先，其次本地表（含 MCP 裸名）', () => {
  assert.equal(toolDesc('execute', '后端说明'), '后端说明')
  assert.equal(toolDesc('execute'), '在隔离的执行环境里跑一条命令，把输出带回来')
  assert.equal(toolDesc('w_q_sql_query'), '')
})

test('已执行计数：denied 不计数', () => {
  assert.equal(executedCount([{ name: 'a' }, { name: 'b', denied: true }, null]), 1)
  assert.equal(executedCount(null), 0)
})

test('耗时口径：毫秒/秒/分/时', () => {
  assert.equal(toolDuration(320), '320ms')
  assert.equal(toolDuration(1234), '1.2s')
  assert.equal(toolDuration(65000), '1m5s')
  assert.equal(toolDuration(3600000), '1h')
})

test('运行态：任一工具 start 即运行中', () => {
  assert.equal(toolRunning({ toolCalls: [{ status: 'done' }, { status: 'start' }] }), true)
  assert.equal(toolRunning({ toolCalls: [{ status: 'done' }] }), false)
  assert.equal(toolRunning(null), false)
})
