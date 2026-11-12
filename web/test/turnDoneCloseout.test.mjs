import test from 'node:test'
import assert from 'node:assert/strict'
import { closeoutDoneTurn } from '../src/chat/turnDoneCloseout.js'
import { TURN_STOP_SUFFIX } from '../src/chat/turnStatus.js'

const setup = ({ serverStopped = false, surrendered = false, isFirst = false, prev = null,
                 degradations = [], versions = null } = {}) => {
  const calls = { settle: [], mark: 0, fallback: 0, drop: 0, scroll: 0, touch: 0, reload: 0 }
  const msg = { loading: true, content: '答案', ask: { id: 1 }, askQueue: [1], approvals: [{ id: 1 }],
                retrieved: { refs: 9 }, degraded: 0, versions, vIndex: versions ? 0 : null }
  const ctx = {
    st: { serverStopped, surrendered }, prev, isFirstMessage: isFirst,
    sources: [{ id: 's' }], related: [{ q: 1 }], messageId: 'm1', degradations,
    stopPlaceholder: '__ph__',
    settlePreparingCards: (m, why) => { calls.settle.push(why) },
    markStoppedTurn: m => { calls.mark++; m.content += TURN_STOP_SUFFIX },
    onModelFallback: () => { calls.fallback++ },
    dropStream: () => { calls.drop++ },
    scroll: () => { calls.scroll++ },
    touchRow: () => { calls.touch++ },
    reloadSessions: () => { calls.reload++ }
  }
  return { msg, ctx, calls }
}

test('常规收尾：卡片撤场、用量来源回填、列表归位', () => {
  const s = setup()
  closeoutDoneTurn(s.msg, s.ctx)
  assert.equal(s.msg.loading, false)
  assert.deepEqual(s.calls.settle, ['本轮已结束，这一步没有执行'])
  assert.equal(s.msg.ask, null)
  assert.deepEqual(s.msg.askQueue, [])
  assert.deepEqual(s.msg.approvals, [])
  assert.ok(s.msg.doneTime > 0)
  assert.deepEqual(s.msg.sources, [{ id: 's' }])
  assert.equal(s.msg.retrieved.refs, 1)
  assert.deepEqual(s.msg.related, [{ q: 1 }])
  assert.equal(s.msg.messageId, 'm1')
  assert.equal(s.calls.drop, 1)
  assert.equal(s.calls.scroll, 1)
  assert.equal(s.calls.touch, 1)
  assert.equal(s.calls.reload, 0)
})

test('服务端确认停止：补终止标注，不写空正文占位', () => {
  const s = setup({ serverStopped: true })
  s.msg.content = ''
  closeoutDoneTurn(s.msg, s.ctx)
  assert.equal(s.msg.stoppedTurn, true)
  assert.equal(s.calls.mark, 1)
  assert.notEqual(s.msg.content, '__ph__')
})

test('空正文：计划被拒/取代/让位时不写占位，其余写', () => {
  const a = setup()
  a.msg.content = ''
  closeoutDoneTurn(a.msg, a.ctx)
  assert.equal(a.msg.content, '__ph__')

  const b = setup()
  b.msg.content = ''
  b.msg.planStopped = true
  closeoutDoneTurn(b.msg, b.ctx)
  assert.equal(b.msg.content, '')

  const c = setup({ surrendered: true })
  c.msg.content = ''
  closeoutDoneTurn(c.msg, c.ctx)
  assert.equal(c.msg.content, '')
})

test('模型降级：同步清本地会话模型并重拉列表', () => {
  const s = setup({ degradations: [{ code: 'modelFallback' }] })
  closeoutDoneTurn(s.msg, s.ctx)
  assert.equal(s.calls.fallback, 1)
})

test('首条消息收尾重拉列表', () => {
  const s = setup({ isFirst: true })
  closeoutDoneTurn(s.msg, s.ctx)
  assert.equal(s.calls.reload, 1)
})

test('多版本：重新生成完成追加版本并切到最新；自动重试不追加', () => {
  const s = setup({ versions: [{ content: 'v1' }] })
  s.msg.content = 'v2'
  closeoutDoneTurn(s.msg, s.ctx)
  assert.equal(s.msg.versions.length, 2)
  assert.equal(s.msg.versions[1].content, 'v2')
  assert.equal(s.msg.vIndex, 1)

  const retry = setup({ versions: [{ content: 'v1' }], prev: { sid: 's1' } })
  closeoutDoneTurn(retry.msg, retry.ctx)
  assert.equal(retry.msg.versions.length, 1)
})
