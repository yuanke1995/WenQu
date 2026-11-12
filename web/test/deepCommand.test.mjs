import test from 'node:test'
import assert from 'node:assert/strict'
import { parseDeepCommand } from '../src/chat/deepCommand.js'

test('深度执行：/deep 与 /深度 是同一个入口，可带任务原文', () => {
  assert.deepEqual(parseDeepCommand('/deep 把这份材料整理成报告'), { matched: true, task: '把这份材料整理成报告' })
  assert.deepEqual(parseDeepCommand('/深度   整理报告  '), { matched: true, task: '整理报告' })
  assert.deepEqual(parseDeepCommand('/DEEP 大小写不敏感'), { matched: true, task: '大小写不敏感' })
  assert.deepEqual(parseDeepCommand('/deep'), { matched: true, task: '' })
})

test('深度执行：旧 /plan、/goal 与普通文本不再命中', () => {
  for (const raw of ['/plan 出计划', '/计划 出计划', '/goal 目标', '/目标 目标', '/deepseek 不是命令', 'deep 没有斜杠', '']) {
    assert.equal(parseDeepCommand(raw).matched, false, raw)
  }
})
