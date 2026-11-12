import test from 'node:test'
import assert from 'node:assert/strict'
import { goalDeliveryOf, turnDeliveriesOf, deliveriesByMessage } from '../src/chat/deliveries.js'

const art = (id, url) => ({ id, url, filename: id + '.html', size: 10 })
const msg = (turnId, artifacts, extra = {}) => ({ role: 'ai', turnId, artifacts, messageId: 'm-' + turnId, ...extra })

test('目标交付包：只收第一轮与 goal:{id}: 各轮，插入轮不收', () => {
  const goal = { goalId: 'g1', objective: '做一个官网', status: 'ACHIEVED', firstTurnId: 't0', objectiveVersion: 1, round: 2 }
  const list = [
    { role: 'user', content: '做一个官网' },
    msg('t0', [art('a1', '/a')]),
    msg('goal:g1:2', [art('a1', '/a'), art('a2', '/b')]),
    msg('user-chat', [art('x1', '/x')]),
  ]
  const d = goalDeliveryOf(list, goal)
  assert.equal(d.fileCount, 2)
  assert.equal(d.name, '官网 · 交付包')
  assert.equal(d.stateLabel, '已完成')
  assert.equal(d.badgeTone, 'ok')
  assert.equal(d.round, 2)
  assert.deepEqual(d.turnIds, ['t0', 'goal:g1:2'])
  assert.equal(d.anchorMsg.turnId, 'goal:g1:2')
})

test('目标交付包：无产物返回 null；未完成/取消的状态词', () => {
  assert.equal(goalDeliveryOf([], { goalId: 'g1' }), null)
  const goal = { goalId: 'g1', objective: 'x', status: 'CANCELLED', firstTurnId: 't0' }
  const d = goalDeliveryOf([msg('t0', [art('a', '/a')])], goal)
  assert.equal(d.stateLabel, '已取消')
  assert.equal(d.badgeTone, 'muted')
})

test('各轮交付包：普通轮各自成包，旧目标多轮聚成一个包', () => {
  const list = [
    { role: 'user', content: '写个报告' },
    msg('t1', [art('r1', '/r')]),
    msg('goal:old:1', [art('o1', '/o')]),
    msg('goal:old:2', [art('o2', '/o2')]),
  ]
  const out = turnDeliveriesOf(list, { currentGoalId: 'g1', currentTurns: [] })
  assert.equal(out.length, 2)
  const old = out.find(d => d.goalId === undefined && d.files.some(f => f.id === 'o1'))
  assert.equal(old.fileCount, 2)
  assert.deepEqual(old.turnIds, ['goal:old:1', 'goal:old:2'])
})

test('各轮交付包：跳过当前目标轮，避免两张卡', () => {
  const list = [msg('goal:g1:1', [art('a1', '/a')]), msg('t2', [art('x1', '/x')])]
  const out = turnDeliveriesOf(list, { currentGoalId: 'g1', currentTurns: ['goal:g1:1'] })
  assert.equal(out.length, 1)
  assert.equal(out[0].files[0].id, 'x1')
})

test('消息索引：目标包挂最后一轮，其余按轮各一份', () => {
  const g = { anchorMsg: { messageId: 'gm' } }
  const t1 = { anchorMsg: { messageId: 'm1' } }
  const t2 = { anchorMsg: { messageId: 'm2' } }
  const map = deliveriesByMessage(g, [t1, t2])
  assert.equal(map.size, 3)
  assert.equal(map.get({ messageId: 'gm' }), undefined)
})
