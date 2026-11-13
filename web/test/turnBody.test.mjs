import test from 'node:test'
import assert from 'node:assert/strict'
import { turnAnswerText, turnSteerRows, turnBodyRows } from '../src/chat/turnBody.js'

test('正文：无插话时一整片，最后一片带 last', () => {
  const rows = turnBodyRows({ content: '完整回答' })
  assert.deepEqual(rows, [{ kind: 'text', key: 't0', content: '完整回答', last: true }])
  assert.deepEqual(turnBodyRows({ content: '' }), [])
  assert.equal(turnAnswerText({ content: 'x' }), 'x')
})

test('正文：按时间线插话位置切片，拼回去等于原文', () => {
  const rows = turnBodyRows({
    content: '前半后半',
    timeline: [
      { kind: 'text', to: 2 },
      { kind: 'steer', text: '插一句', messageId: 'm1' },
      { kind: 'text', to: 4 },
    ],
  })
  assert.deepEqual(rows.map(r => r.kind), ['text', 'steer', 'text'])
  assert.equal(rows[0].content, '前半')
  assert.equal(rows[1].text, '插一句')
  assert.equal(rows[2].content, '后半')
  assert.equal(rows[2].last, true)
  assert.equal(rows.filter(r => r.kind === 'text').map(r => r.content).join(''), '前半后半')
})

test('正文：空白插话忽略；切点不倒退', () => {
  const rows = turnBodyRows({
    content: 'abcd',
    timeline: [
      { kind: 'text', to: 3 },
      { kind: 'steer', text: '  ' },
      { kind: 'steer', text: '插话', messageId: 'm2' },
      { kind: 'text', to: 1 },
    ],
  })
  const steers = rows.filter(r => r.kind === 'steer')
  assert.equal(steers.length, 1)
  assert.equal(steers[0].key, 's0:m2')
  assert.equal(rows.filter(r => r.kind === 'text').map(r => r.content).join(''), 'abc' + 'd')
})

test('插话清单：只列有正文的 steer 段', () => {
  const list = turnSteerRows({ timeline: [
    { kind: 'steer', text: 'a', messageId: 'm1' },
    { kind: 'steer', text: '   ' },
    { kind: 'text', to: 1 },
  ] })
  assert.deepEqual(list, [{ kind: 'steer', text: 'a', messageId: 'm1', images: [], attachments: [] }])

  // 计划卡「本轮调整」读的就是这份：带图的那条要把图带出去，否则计划里只看到半句话
  const withImg = turnSteerRows({ timeline: [
    { kind: 'steer', text: 'b', messageId: 'm2', images: ['https://img/b.png'] }
  ] })
  assert.deepEqual(withImg[0].images, ['https://img/b.png'])
})
