import test from 'node:test'
import assert from 'node:assert/strict'
import { fmtWindow, usageCtxTokens, usageTip } from '../src/chat/usage.js'

test('窗口：官方口径优先（1M/128K），其余按进制整除回落', () => {
  assert.equal(fmtWindow(0), '')
  assert.equal(fmtWindow(1048576), '1M')
  assert.equal(fmtWindow(1000000), '1M')
  assert.equal(fmtWindow(1572864), '1.5M')
  assert.equal(fmtWindow(128000), '128K')
  assert.equal(fmtWindow(131072), '128K')
  assert.equal(fmtWindow(2048), '2K')
  assert.equal(fmtWindow(1000), '1000')
})

test('用量：占用优先 context，缺失回落 prompt，非对象为 0', () => {
  assert.equal(usageCtxTokens({ context: 120, prompt: 999 }), 120)
  assert.equal(usageCtxTokens({ prompt: 999 }), 999)
  assert.equal(usageCtxTokens(null), 0)
  assert.equal(usageCtxTokens('x'), 0)
})

test('用量：悬停明细三行，送入为 0 时不列', () => {
  assert.equal(usageTip({ context: 1200, prompt: 3000, output: 500 }),
    '上下文占用 1.2K tokens\n本轮累计送入 3.0K tokens\n输出 500 tokens')
  assert.equal(usageTip({ context: 1200, output: 500 }),
    '上下文占用 1.2K tokens\n输出 500 tokens')
  assert.equal(usageTip(null), '')
})
