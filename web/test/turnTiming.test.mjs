import test from 'node:test'
import assert from 'node:assert/strict'
import { durZh } from '../src/utils/duration.js'
import { turnElapsedMs, turnPrepMs, turnWaitMs, resumedStartAt, earlierStart, turnTitle } from '../src/chat/turnTiming.js'

test('人读时长：秒/分/小时与不足 1 秒', () => {
  assert.equal(durZh(500), '不到 1 秒')
  assert.equal(durZh(1500), '1.5 秒')
  assert.equal(durZh(2000), '2 秒')
  assert.equal(durZh(125000), '2 分 5 秒')
  assert.equal(durZh(7200000), '2 小时')
})

test('整轮耗时：跑动中按整秒向下取整，答完用服务端值', () => {
  assert.equal(turnElapsedMs({ loading: true, time: 1000 }, 3900), 2000)
  assert.equal(turnElapsedMs({ loading: true, time: 5000 }, 4000), 0)
  assert.equal(turnElapsedMs({ turnMs: 1234 }, 9999), 1234)
  assert.equal(turnElapsedMs({ tokens: { turnWallMs: 2000 } }, 0), 2000)
  assert.equal(turnElapsedMs(null, 0), 0)
})

test('准备段与人工等待段：顶层优先，缺失回落 tokens', () => {
  assert.equal(turnPrepMs({ turnPrepMs: 100, tokens: { turnPrepMs: 200 } }), 100)
  assert.equal(turnPrepMs({ tokens: { turnPrepMs: 200 } }), 200)
  assert.equal(turnWaitMs({ humanWaitMs: 0, tokens: { humanWaitMs: 300 } }), 300)
  assert.equal(turnPrepMs(null), 0)
})

test('接流起表：有已跑时长则回拨，两个起表点取更早', () => {
  assert.equal(resumedStartAt(2000, 10000), 8000)
  assert.equal(resumedStartAt(0, 10000), 10000)
  assert.equal(earlierStart(0, 500), 500)
  assert.equal(earlierStart(500, 0), 500)
  assert.equal(earlierStart(900, 500), 500)
})

test('状态行标题：总时长 + 准备/模型与工具/等待的拆分', () => {
  assert.equal(turnTitle({}, 0), '从发出提问到答完实际用的时间')
  assert.equal(turnTitle({ turnMs: 10000, turnPrepMs: 2000, humanWaitMs: 3000 }, 0),
    '从发出提问到答完一共 10 秒，其中开始回答前的准备 2 秒、模型与工具 5 秒、等你答题或批准 3 秒')
})
