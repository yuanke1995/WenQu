import test from 'node:test'
import assert from 'node:assert/strict'
import { createStreamBuffer } from '../src/chat/streamBuffer.js'

const setup = () => {
  const calls = { text: [], process: [], scroll: 0, timers: [] }
  const msg = {}
  const buf = createStreamBuffer({
    msg,
    extendText: (m, from, to) => { calls.text.push([from, to]) },
    extendProcess: (m, from, to) => { calls.process.push([from, to]) },
    scroll: () => { calls.scroll++ },
    schedule: fn => { calls.timers.push(fn); return calls.timers.length },
    throttleMs: 120
  })
  return { buf, msg, calls }
}

test('token 进缓冲不立刻落屏；节流回调触发后一次落屏', () => {
  const { buf, msg, calls } = setup()
  buf.appendToken('abc')
  assert.equal(msg.content, undefined)
  assert.equal(buf.full(), 'abc')
  assert.equal(calls.timers.length, 1)
  calls.timers[0]()
  assert.equal(msg.content, 'abc')
  assert.deepEqual(calls.text, [[0, 3]])
  assert.equal(calls.scroll, 1)
})

test('节流窗口内多次 token 只排一个定时器', () => {
  const { buf, calls } = setup()
  buf.appendToken('a')
  buf.appendToken('b')
  assert.equal(calls.timers.length, 1)
})

test('flushNow：补齐时间线区间、keepScroll=false 不滚动', () => {
  const { buf, msg, calls } = setup()
  buf.appendToken('abcd')
  buf.flushNow(false)
  assert.equal(msg.content, 'abcd')
  assert.deepEqual(calls.text, [[0, 4]])
  assert.equal(calls.scroll, 0)
  buf.appendToken('ef')
  buf.flushNow()
  assert.deepEqual(calls.text, [[0, 4], [4, 6]])
  assert.equal(calls.scroll, 1)
})

test('接流重置：正文以快照为准，gotToken 随之对齐', () => {
  const { buf, msg } = setup()
  buf.appendToken('x')
  assert.equal(buf.gotToken(), true)
  buf.resetFromContent('快照正文')
  assert.equal(buf.full(), '快照正文')
  assert.equal(buf.gotToken(), true)
  buf.resetFromContent('')
  assert.equal(buf.gotToken(), false)
  assert.equal(msg.content, undefined)
})

test('接流重叠：按绝对位置裁掉已落屏部分', () => {
  const { buf } = setup()
  buf.resetFromContent('hel')
  buf.appendToken('lo!', { pos: 5 })   // 目标位置 5，缓冲已 3 → 只取前 2 个字符
  assert.equal(buf.full(), 'hello')
  buf.appendToken('done', { pos: 9 })  // 9 >= 5+4 → 整片保留
  assert.equal(buf.full(), 'hellodone')
  buf.appendToken('x', { pos: 3 })     // 已覆盖到 3 → 丢弃
  assert.equal(buf.full(), 'hellodone')
})

test('过程独白：先落正文再追加过程段，滚动两次（正文+过程）', () => {
  const { buf, msg, calls } = setup()
  buf.appendToken('正文')
  buf.appendProcess('想了一步')
  assert.equal(msg.content, '正文')
  assert.equal(msg.processText, '想了一步')
  assert.deepEqual(calls.process, [[0, 4]])
  assert.equal(calls.scroll, 1)  // appendToken 只排队；过程落屏前那次 flushNow(false) 不滚
  buf.appendProcess('再想一步')
  assert.equal(msg.processText, '想了一步再想一步')
  assert.deepEqual(calls.process, [[0, 4], [4, 8]])
})

test('过程独白：接流重叠整片丢弃时不写段也不滚动', () => {
  const { buf, msg, calls } = setup()
  msg.processText = 'old'
  buf.appendProcess('old', { pos: 3 })
  assert.equal(msg.processText, 'old')
  assert.equal(calls.process.length, 0)
})
