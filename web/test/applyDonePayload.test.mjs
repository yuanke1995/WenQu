import test from 'node:test'
import assert from 'node:assert/strict'
import { ref } from 'vue'
import { applyDonePayload } from '../src/chat/applyDonePayload.js'

const setup = ({ sid = 's1', resume = false, card = null, liveCompactedSid = 's2', bodyRewritten = false } = {}) => {
  const calls = { plan: [], abort: 0, lineage: 0, flush: [], settled: [], core: 0, tools: 0, timeline: 0,
                  thinkSeg: 0, blocks: 0 }
  const msg = { planCard: card, timeline: [{ kind: 'text' }, { kind: 'tool' }] }
  const editUserMsg = { messageId: null }
  const flushPendingSteers = (left, origin) => { calls.flush.push([left, origin]) }
  const ctx = {
    sid, resume, agentId: 'ag1', editUserMsg,
    currentSid: () => sid,
    messages: ref([msg]),
    planCardFromRecord: rec => { calls.plan.push(rec); return { status: 'fromRecord', rec } },
    settleAbortedPlan: m => { calls.abort++; if (['drafting', 'pending'].includes(m.planCard.status)) m.planCard.status = 'stopped' },
    linkPlanLineage: () => { calls.lineage++ },
    flushPendingSteers,
    rememberSettled: (s, t) => { calls.settled.push([s, t]) },
    liveCompactedSid: ref(liveCompactedSid),
    applyDoneCore: (m, p) => { calls.core++; m.content = p.content || m.content; return bodyRewritten },
    mergeDoneToolCalls: () => { calls.tools++ },
    restoreTimeline: (m, tl) => { calls.timeline++; return tl.map(x => ({ ...x, restored: true })) },
    ensureThinkSegment: () => { calls.thinkSeg++ },
    applyBlocks: (m, b) => { calls.blocks++; m.content = m.content + '|b' }
  }
  return { msg, ctx, calls, editUserMsg }
}

test('坏载荷：不抛错，返回空字段', () => {
  const s = setup()
  const out = applyDonePayload(s.msg, '{bad', s.ctx)
  assert.deepEqual(out, { sources: [], related: [], messageId: null, degradations: [] })
  assert.equal(s.calls.core, 0)
})

test('公共字段：来源/推荐/消息 ID/降级原样取出', () => {
  const s = setup()
  const out = applyDonePayload(s.msg, JSON.stringify({
    sources: [{ id: 1 }], related: [{ q: 'r' }], messageId: 'm1', degradations: [{ code: 'x' }], content: '终稿'
  }), s.ctx)
  assert.deepEqual(out.sources, [{ id: 1 }])
  assert.deepEqual(out.related, [{ q: 'r' }])
  assert.equal(out.messageId, 'm1')
  assert.deepEqual(out.degradations, [{ code: 'x' }])
  assert.equal(s.msg.content, '终稿')
})

test('计划：终态稿覆盖 drafting 卡，已裁决卡保留；被拒/取代置位', () => {
  const drafting = setup({ card: { status: 'drafting', busy: true } })
  applyDonePayload(drafting.msg, JSON.stringify({ planRecord: { plan: 'p' }, planCancelled: true }), drafting.ctx)
  assert.equal(drafting.msg.planCard.status, 'fromRecord')
  assert.equal(drafting.calls.abort, 1)  // drafting 卡落终态
  assert.equal(drafting.msg.planStopped, true)
  assert.equal(drafting.calls.lineage, 1)

  const approved = setup({ card: { status: 'approved' } })
  applyDonePayload(approved.msg, JSON.stringify({ planRecord: { plan: 'p2' }, planSuperseded: true }), approved.ctx)
  assert.equal(approved.msg.planCard.status, 'approved')
  assert.equal(approved.msg.planSuperseded, true)
  assert.equal(approved.msg.planCard.busy, false)
})

test('退回重出未开始：只解锁，不落终态', () => {
  const s = setup({ card: { status: 'pending', busy: true, revising: true, revisingStarted: false, pendingFeedback: '意见' } })
  applyDonePayload(s.msg, '{}', s.ctx)
  assert.equal(s.msg.planCard.revising, false)
  assert.equal(s.msg.planCard.pendingFeedback, '')
  assert.equal(s.msg.planCard.status, 'pending')
  assert.equal(s.calls.abort, 0)
})

test('插话结算：本地轮收尾后才调度；接流不结算', async () => {
  const a = setup({ resume: false })
  applyDonePayload(a.msg, JSON.stringify({ pendingSteers: ['再改'] }), a.ctx)
  await new Promise(r => setTimeout(r, 0))
  assert.deepEqual(a.calls.flush[0], [['再改'], { sid: 's1', agentId: 'ag1' }])

  const b = setup({ resume: true })
  applyDonePayload(b.msg, JSON.stringify({ pendingSteers: ['x'] }), b.ctx)
  await new Promise(r => setTimeout(r, 0))
  assert.equal(b.calls.flush.length, 0)
})

test('编辑重发回填、轮次身份、收场登记与用量', () => {
  const s = setup({ liveCompactedSid: 's1' })
  applyDonePayload(s.msg, JSON.stringify({ userMessageId: 'u1', turnId: 42, tokens: { context: 9 } }), s.ctx)
  assert.equal(s.editUserMsg.messageId, 'u1')
  assert.equal(s.msg.turnId, '42')
  assert.deepEqual(s.calls.settled, [['s1', '42']])
  assert.deepEqual(s.msg.tokens, { context: 9, compacted: 1 })
})

test('工具终态/时间线/思考段与显示块', () => {
  const plain = setup()
  applyDonePayload(plain.msg, JSON.stringify({ toolCalls: [{ id: 1 }], timeline: [{ kind: 'tool' }] }), plain.ctx)
  assert.equal(plain.calls.tools, 1)
  assert.equal(plain.calls.timeline, 1)
  assert.equal(plain.msg.timeline[0].restored, true)
  assert.equal(plain.calls.thinkSeg, 1)

  const blocks = setup()
  applyDonePayload(blocks.msg, JSON.stringify({ content: 'a', blocks: { x: 1 } }), blocks.ctx)
  assert.equal(blocks.calls.blocks, 1)
  assert.equal(blocks.calls.thinkSeg, 2)  // 时间线恢复 + 显示块后各一次
})

test('正文被重建但无时间线：只滤掉 text 段，工具/思考段保留', () => {
  const s = setup({ bodyRewritten: true })
  applyDonePayload(s.msg, JSON.stringify({ content: '重写' }), s.ctx)
  assert.deepEqual(s.msg.timeline, [{ kind: 'tool' }])
})

test('产物/改动/过程独白按载荷覆盖，缺键保留原值', () => {
  const s = setup()
  s.msg.artifacts = [{ old: 1 }]
  s.msg.changes = { old: 1 }
  s.msg.processText = 'old'
  applyDonePayload(s.msg, JSON.stringify({ artifacts: [{ new: 1 }], changes: { new: 1 }, processText: 'new' }), s.ctx)
  assert.deepEqual(s.msg.artifacts, [{ new: 1 }])
  assert.deepEqual(s.msg.changes, { new: 1 })
  assert.equal(s.msg.processText, 'new')

  applyDonePayload(s.msg, JSON.stringify({ artifacts: [], changes: null, processText: 5 }), s.ctx)
  assert.deepEqual(s.msg.artifacts, [{ new: 1 }])
  assert.deepEqual(s.msg.changes, { new: 1 })
  assert.equal(s.msg.processText, 'new')
})

test('作答归属：委派值覆盖普通归属', () => {
  const s = setup()
  applyDonePayload(s.msg, JSON.stringify({ agentId: 'a1', agentName: '甲', delegatedAgentId: 'd1', delegatedAgentName: '乙' }), s.ctx)
  assert.equal(s.msg.agentId, 'd1')
  assert.equal(s.msg.agentName, '乙')
})
