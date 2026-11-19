import test from 'node:test'
import assert from 'node:assert/strict'
import { insertSpan } from '../src/chat/composerSelection.js'

test('折叠光标：在光标处插入，from===to', () => {
  assert.deepEqual(insertSpan(5, 5, 20), [5, 5])
  assert.deepEqual(insertSpan(0, 0, 20), [0, 0])
})

test('有选区：整段替换，用 to 作为终点而不是起点', () => {
  // 「帮我打开 https://www.baidu.com/ 看看页面标题是什么」里 URL 的下标
  const from = 5
  const to = 5 + 'https://www.baidu.com/'.length
  assert.deepEqual(insertSpan(from, to, 40), [from, to])
})

test('逆向选区（从右往左选）归一为从小到大的区间', () => {
  const to = 10
  const from = 3
  assert.deepEqual(insertSpan(from, to, 40), [3, 10])
})

test('越界下标被夹到正文长度内，不产生非法区间', () => {
  assert.deepEqual(insertSpan(-1, -1, 8), [8, 8])
  assert.deepEqual(insertSpan(6, 999, 8), [6, 8])
  assert.deepEqual(insertSpan(12, 20, 8), [8, 8])
})
