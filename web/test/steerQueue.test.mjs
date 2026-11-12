import test from 'node:test'
import assert from 'node:assert/strict'
import { ref } from 'vue'
import { createSteerQueue, steerNeedsNextRound } from '../src/chat/steerQueue.js'

const setup = ({
  sid = 's1', live = true,
  steerRet = { data: { accepted: true, messageId: 'id1' } },
  steerItemRet = { data: { accepted: true } },
  enqueueThrows = false,
  rows = []
} = {}) => {
  const sidNow = ref(sid)
  const text = ref('')
  const calls = { api: [], enq: [], steerItem: [], drop: [], list: [], notes: [] }
  const q = createSteerQueue({
    currentSid: () => sidNow.value,
    hasLiveStream: () => live,
    steerApi: async (s, t, imgs = []) => {
      calls.api.push([s, t, imgs])
      if (steerRet instanceof Error) throw steerRet
      return steerRet
    },
    enqueueApi: async (s, t, imgs = []) => {
      if (enqueueThrows) throw new Error('queue down')
      calls.enq.push([s, t, imgs])
      return { data: { id: 'q1', status: 'PENDING' } }
    },
    listApi: async s => { calls.list.push(s); return { data: rows } },
    steerItemApi: async (s, id) => {
      calls.steerItem.push([s, id])
      if (steerItemRet instanceof Error) throw steerItemRet
      return steerItemRet
    },
    dropApi: async (s, id) => { calls.drop.push([s, id]); return { data: { ok: true } } },
    notify: (m, level) => calls.notes.push([level || 'warning', m]),
    textOf: () => text.value,
    setText: v => { text.value = v }
  })
  return { q, calls, text, sidNow }
}

test('带材料判定：附件/技能/引用/历史/计划模式任一即算，图片已可随本轮插话', () => {
  assert.equal(steerNeedsNextRound({ text: 'x' }), false)
  assert.equal(steerNeedsNextRound({ images: ['data:image/png;base64,x'] }), false)
  assert.equal(steerNeedsNextRound({ atts: [1] }), true)
  assert.equal(steerNeedsNextRound({ skills: [1] }), true)
  assert.equal(steerNeedsNextRound({ mentions: [1] }), true)
  assert.equal(steerNeedsNextRound({ historyRefs: [1] }), true)
  assert.equal(steerNeedsNextRound({ planMode: true }), true)
})

test('steer：成功返回消息 ID，未接受/异常返回 null', async () => {
  const ok = setup()
  assert.equal(await ok.q.steer('  改方向  '), 'id1')
  assert.deepEqual(ok.calls.api, [['s1', '改方向', []]])

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

test('入队：落库排队，不发 steer 请求；无流/空文本不入', async () => {
  const a = setup()
  const item = a.q.stageSteer({ text: ' 插一句 ' })
  assert.equal(item.text, '插一句')
  assert.equal(a.q.steerQueue.value.length, 1)
  assert.equal(a.calls.api.length, 0)
  await Promise.resolve()
  assert.deepEqual(a.calls.enq, [['s1', '插一句', []]])
  assert.equal(item.server, true)
  assert.equal(item.id, 'q1')

  assert.equal(a.q.stageSteer({ text: ' ' }), null)
  const noLive = setup({ live: false })
  assert.equal(noLive.q.stageSteer({ text: 'x' }), null)
})

test('立即发送：服务端队列那条送进本轮，转等待态', async () => {
  const a = setup()
  const item = a.q.stageSteer({ text: '插一句' })
  await Promise.resolve()
  await a.q.sendSteer(item)
  assert.deepEqual(a.calls.steerItem, [['s1', 'q1']])
  assert.equal(item.awaiting, true)
  assert.equal(item.queued, false)
  assert.equal(item.sending, false)
  assert.equal(a.q.steerQueue.value.length, 1)
})

test('立即发送：带材料转待发，本轮不发', async () => {
  const a = setup()
  const item = a.q.stageSteer({ text: '插一句', atts: [{ fileId: 'f1' }] })
  await a.q.sendSteer(item)
  assert.equal(item.deferred, true)
  assert.equal(a.calls.api.length, 0)
  assert.equal(a.calls.steerItem.length, 0)
  assert.equal(a.calls.notes[0][0], 'info')
})

test('立即发送：带图随本轮插话一起送（不再 deferred）', async () => {
  const a = setup()
  const item = a.q.stageSteer({ text: '看这张图', images: ['data:image/png;base64,x'] })
  await Promise.resolve()
  await a.q.sendSteer(item)
  assert.equal(item.deferred, false)
  assert.equal(item.awaiting, true)
  assert.deepEqual(a.calls.enq, [['s1', '看这张图', ['data:image/png;base64,x']]])
})

test('立即发送：没有活轮时留在队列里等下一轮（不退回输入框）', async () => {
  const a = setup({ steerItemRet: { data: { accepted: false } } })
  const item = a.q.stageSteer({ text: '插一句' })
  await Promise.resolve()
  await a.q.sendSteer(item)
  assert.equal(item.queued, true)
  assert.equal(a.q.steerQueue.value.length, 1)
  assert.match(item.lastError, /排队/)
  assert.equal(a.text.value, '')
})

test('立即发送：服务端都没收下时把内容放回输入框（不覆盖已打的字）', async () => {
  const a = setup({ enqueueThrows: true })
  const item = a.q.stageSteer({ text: '插一句' })
  await Promise.resolve()
  a.text.value = '正在打的字'
  await a.q.sendSteer(item)
  assert.equal(a.q.steerQueue.value.length, 0)
  assert.equal(a.text.value, '正在打的字 插一句')
})

test('编辑/删除/排序：本地数组操作，服务端那份一并删', async () => {
  const a = setup()
  const x = a.q.stageSteer({ text: 'A' })
  const y = a.q.stageSteer({ text: 'B' })
  const z = a.q.stageSteer({ text: 'C' })
  await Promise.resolve()
  a.q.moveSteer(2, 0)
  assert.deepEqual(a.q.steerQueue.value.map(i => i.text), ['C', 'A', 'B'])
  a.q.moveSteer(0, 9)
  assert.deepEqual(a.q.steerQueue.value.map(i => i.text), ['A', 'B', 'C'])

  a.q.editSteer(y)
  assert.deepEqual(a.q.steerQueue.value.map(i => i.text), ['A', 'C'])
  assert.equal(a.text.value, 'B')
  assert.equal(a.calls.drop.length, 1)

  a.q.dropSteer(z)
  assert.deepEqual(a.q.steerQueue.value.map(i => i.text), ['A'])
  assert.ok(x)
})

test('恢复：服务端投影替换排队/已送出那段，本地未同步草稿保留', async () => {
  const a = setup({ rows: [
    { id: 'r1', text: '排队的', images: [], status: 'PENDING', attempts: 0, createdAt: 1 },
    { id: 'r2', text: '已送出的', images: [], status: 'STEERED', attempts: 1, createdAt: 2 }
  ] })
  a.q.steerQueue.value = [{ text: '本地草稿', local: true, sid: 's1' }]
  await a.q.restoreSteerQueue('s1')
  assert.deepEqual(a.q.steerQueue.value.map(i => i.text), ['排队的', '已送出的', '本地草稿'])
  assert.equal(a.q.steerQueue.value[0].queued, true)
  assert.equal(a.q.steerQueue.value[1].awaiting, true)
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

test('计划调整排队：落库后投递，成功转「等这一轮听到」', async () => {
  const a = setup({ sid: 's2' })
  const item = a.q.enqueuePlanAdjust('  再加一个栏目 ', 's1')
  assert.equal(item.text, '再加一个栏目')
  assert.equal(item.queued, true)
  await Promise.resolve()
  assert.deepEqual(a.calls.enq, [['s1', '再加一个栏目', []]])

  assert.equal(await a.q.deliverPlanAdjust(item), true)
  assert.deepEqual(a.calls.steerItem, [['s1', 'q1']])
  assert.equal(item.queued, false)
  assert.equal(item.awaiting, true)
})

test('计划调整排队：投递失败留在队列等下一轮，不退回输入框', async () => {
  const a = setup({ steerItemRet: { data: { accepted: false } } })
  const item = a.q.enqueuePlanAdjust('改方向', 's1')
  await Promise.resolve()
  assert.equal(await a.q.deliverPlanAdjust(item), false)
  assert.equal(item.queued, true)
  assert.equal(a.q.steerQueue.value.length, 1)

  a.text.value = '正在打的字'
  await a.q.sendSteer(item)
  assert.equal(item.queued, true)
  assert.equal(a.text.value, '正在打的字')
  assert.equal(a.calls.notes[0][0], 'info')
})

test('计划调整排队：空文本或没有会话不入队', () => {
  const a = setup()
  assert.equal(a.q.enqueuePlanAdjust('  ', 's1'), null)
  assert.equal(a.q.enqueuePlanAdjust('x', ''), null)
  assert.equal(a.q.steerQueue.value.length, 0)
})
