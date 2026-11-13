import test from 'node:test'
import assert from 'node:assert/strict'
import { reconnectDelay, reconnectNext } from '../src/chat/reconnectPolicy.js'

test('重挂退避：首跳 250~500ms，指数增长，封顶后不再变大', () => {
  assert.equal(reconnectDelay(0, { random: () => 0 }), 250)
  assert.equal(reconnectDelay(0, { random: () => 0.999 }), 500)
  assert.equal(reconnectDelay(1, { random: () => 1 }), 1000)
  assert.equal(reconnectDelay(2, { random: () => 1 }), 2000)
  // 500*2^6=32000 已超帽：封顶 10000 再乘抖动（≤1）
  assert.equal(reconnectDelay(6, { random: () => 1 }), 10000)
  assert.ok(reconnectDelay(30, { random: () => 1 }) <= 10000)
})

test('重挂退避：随机源越界/坏值不产生越界等待', () => {
  assert.ok(reconnectDelay(0, { random: () => -5 }) >= 250)
  assert.ok(reconnectDelay(0, { random: () => 5 }) <= 500)
  assert.ok(reconnectDelay(0, { random: () => NaN }) <= 500)
  assert.equal(reconnectDelay(-3, { random: () => 0 }), 250)
})

test('重挂结果分流：挂上就停、没在跑就按历史收场、其余一律继续重试', () => {
  assert.equal(reconnectNext('attached'), 'attached')
  assert.equal(reconnectNext('idle'), 'settle')
  assert.equal(reconnectNext('running'), 'retry')
  assert.equal(reconnectNext('busy'), 'retry')
  // 探测失败（网还没回来）不许当成「这一轮已收场」，否则会把还在跑的轮判死
  assert.equal(reconnectNext('unknown'), 'retry')
  assert.equal(reconnectNext(undefined), 'retry')
})
