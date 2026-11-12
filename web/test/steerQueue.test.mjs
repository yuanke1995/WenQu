import test from 'node:test'
import assert from 'node:assert/strict'
import { ref } from 'vue'
import { createSteerQueue, steerNeedsNextRound } from '../src/chat/steerQueue.js'

const setup = ({ sid = 's1', live = true, steerRet = { data: { accepted: true, messageId: 'id1' } } } = {}) => {
  const sidNow = ref(sid)
  const text = ref('')
  const calls = { api: [], notes: [] }
  const q = createSteerQueue({
    currentSid: () => sidNow.value,
    hasLiveStream: () => live,
    steerApi: async (s, t) => { calls.api.push([s, t]); if (steerRet instanceof Error) throw steerRet; return steerRet },
    notify: (m, level) => calls.notes.push([level || 'warning', m]),
    textOf: () => text.value,
    setText: v => { text.value = v }
  })
  return { q, calls, text, sidNow }
}

test('带材料判定：图片/附件/技能/引用/历史/计划模式任一即算', () => {
  assert.equal(steerNeedsNextRound({ text: 'x' }), false)
  assert.equal(steerNeedsNextRound({ images: [1] }), true)
  assert.equal(steerNeedsNextRound({ atts: [1] }), true)
  assert.equal(steerNeedsNextRound({ mentions: [1] }), true)
  assert.equal(steerNeedsNextRound({ planMode: true }), true)
})

test('steer：成功返回消息 ID，未接受/异常返回 null', async () => {
  const ok = setup()
  assert.equal(await ok.q.steer('  改方向  '), 'id1')
  assert.deepEqual(ok.calls.api, [['s1', '改方向']])

  const rejected = setup({ steerRet: { data: { accepted: false } } })
  assert.equal(await rejected.q.steer('x'), null)

  const boom = setup({ steerRet: new Error('net') })
  assert.equal(await boom.q.steer('x'), null)
  assert.match(boom.calls.notes[0][1], /net/)
})

test('steer：空文本或没有会话不发请求', async () => {
  const empty = setup()
  assert.equal(await empty.q.steer('   '), null)
  assert.equal(empty.calls.api.length, 0)

  const noSid = setup({ sid: '' })
  assert.equal(await noSid.q.steer('x'), null)
  assert.equal(noSid.calls.api.length, 0)
})

test('入队：只进本地队列，不发请求；无流/空文本不入', () => {
  const a = setup()
  const item = a.q.stageSteer({ text: ' 插一句 ' })
  assert.equal(item.text, '插一句')
  assert.equal(a.q.steerQueue.value.length, 1)
  assert.equal(a.calls.api.length, 0)

  assert.equal(a.q.stageSteer({ text: ' ' }), null)
  const noLive = setup({ live: false })
  assert.equal(noLive.q.stageSteer({ text: 'x' }), null)
})

test('立即发送：纯文字成功后条目转为等待态', async () => {
  const a = setup()
  const item = a.q.stageSteer({ text: '插一句' })
  await a.q.sendSteer(item)
  assert.equal(item.awaiting, true)
  assert.equal(item.messageId, 'id1')
  assert.equal(item.sending, false)
  assert.equal(a.q.steerQueue.value.length, 1)
})

test('立即发送：带材料转待发，本轮不发', async () => {
  const a = setup()
  const item = a.q.stageSteer({ text: '插一句', images: ['data:x'] })
  await a.q.sendSteer(item)
  assert.equal(item.deferred, true)
  assert.equal(a.calls.api.length, 0)
  assert.equal(a.calls.notes[0][0], 'info')
})

test('立即发送：送不出去时撤下条目并把内容放回输入框（不覆盖已打的字）', async () => {
  const a = setup({ steerRet: { data: { accepted: false } } })
  const item = a.q.stageSteer({ text: '插一句' })
  await a.q.sendSteer(item)
  assert.equal(a.q.steerQueue.value.length, 0)
  assert.equal(a.text.value, '插一句')

  a.text.value = '正在打的字'
  const b = a.q.stageSteer({ text: '另一句' })
  await a.q.sendSteer(b)
  assert.equal(a.text.value, '正在打的字 另一句')

  a.text.value = '正在打的字 另一句'
  const c = a.q.stageSteer({ text: '另一句' })
  await a.q.sendSteer(c)
  assert.equal(a.text.value, '正在打的字 另一句')
})

test('编辑/删除/排序：纯数组操作', () => {
  const a = setup()
  const x = a.q.stageSteer({ text: 'A' })
  const y = a.q.stageSteer({ text: 'B' })
  const z = a.q.stageSteer({ text: 'C' })
  a.q.moveSteer(2, 0)
  assert.deepEqual(a.q.steerQueue.value.map(i => i.text), ['C', 'A', 'B'])
  a.q.moveSteer(0, 9)
  assert.deepEqual(a.q.steerQueue.value.map(i => i.text), ['A', 'B', 'C'])

  a.q.editSteer(y)
  assert.deepEqual(a.q.steerQueue.value.map(i => i.text), ['A', 'C'])
  assert.equal(a.text.value, 'B')

  a.q.dropSteer(z)
  assert.deepEqual(a.q.steerQueue.value.map(i => i.text), ['A'])
  assert.ok(x)
})

test('steer 帧应用后撤下对应等待条目', () => {
  const a = setup()
  const item = a.q.stageSteer({ text: 'X' })
  item.awaiting = true
  item.messageId = 'm9'
  a.q.dropApplied('m9')
  assert.equal(a.q.steerQueue.value.length, 0)
  a.q.dropApplied('')
})
