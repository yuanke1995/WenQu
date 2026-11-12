import test from 'node:test'
import assert from 'node:assert/strict'
import { createTurnDividers } from '../src/chat/turnDividers.js'

const setup = ({ index = {}, msgs = [], live = false, compactedSid = '', sid = 's1' } = {}) => {
  const state = { msgs, live, compactedSid, sid }
  const d = createTurnDividers({
    modelIndexOf: () => index,
    messages: () => state.msgs,
    isLive: () => state.live,
    liveCompactedSid: () => state.compactedSid,
    currentSid: () => state.sid
  })
  return { d, state }
}

test('展示名：模型库友好名优先，查不到退模型名段', () => {
  const { d } = setup({ index: { 'v1/deepseek': { displayName: '深度求索' } } })
  assert.equal(d.modelLabelOf(''), '')
  assert.equal(d.modelLabelOf('v1/deepseek'), '深度求索')
  assert.equal(d.modelLabelOf('v1/unknown'), 'unknown')
  assert.equal(d.modelLabelOf('bare'), 'bare')
})

test('模型切换分隔：展示名不同才返回，首条/缺模型不报', () => {
  const { d } = setup({ msgs: [{ role: 'user' }, { role: 'ai', model: 'v/a' }, { role: 'ai', model: 'v/b' }] })
  assert.equal(d.modelSwitchInfo({ role: 'ai' }, 0), null)
  assert.equal(d.modelSwitchInfo({ role: 'user', model: 'v/b' }, 1), null)
})

test('模型切换分隔：与上一条助手消息比对展示名', () => {
  const { d } = setup({
    index: { 'v/a': { displayName: 'A' }, 'v/b': { displayName: 'B' } },
    msgs: [{ role: 'ai', model: 'v/a' }]
  })
  assert.deepEqual(d.modelSwitchInfo({ role: 'ai', model: 'v/b' }, 1), { fromLabel: 'A', toLabel: 'B' })
  assert.equal(d.modelSwitchInfo({ role: 'ai', model: 'v/a' }, 1), null)
})

test('压缩分隔：只看提问之后的第一条非用户消息带不带 compacted', () => {
  const { d } = setup({ msgs: [
    { role: 'user' },
    { role: 'user' },
    { role: 'ai', tokens: { compacted: 1 } },
    { role: 'user' }
  ] })
  assert.equal(d.compactDividerAt({ role: 'user' }, 0), true)
  assert.equal(d.compactDividerAt({ role: 'ai' }, 2), false)
})

test('流式压缩线：仅当本会话在跑且是最后一条提问', () => {
  const { d, state } = setup({ msgs: [{ role: 'user' }, { role: 'ai' }], live: true, compactedSid: 's1' })
  assert.equal(d.liveCompactAt({ role: 'user' }, 0), true)
  assert.equal(d.liveCompactAt({ role: 'ai' }, 1), false)
  state.compactedSid = 's2'
  assert.equal(d.liveCompactAt({ role: 'user' }, 0), false)
  state.compactedSid = 's1'
  state.live = false
  assert.equal(d.liveCompactAt({ role: 'user' }, 0), false)
})
