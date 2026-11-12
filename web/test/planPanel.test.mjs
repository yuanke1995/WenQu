import test from 'node:test'
import assert from 'node:assert/strict'
import { buildSessionPlans, planStateText } from '../src/chat/planPanel.js'

const steps = plan => (String(plan || '').match(/\d\./g) || []).length

test('计划列表：倒序收集，待确认置顶，同状态保持最新在前', () => {
  const list = [
    { role: 'user', content: '任务一' },
    { role: 'ai', planCard: { status: 'approved', plan: '1. a', versions: [{}, {}] } },
    { role: 'user', content: '任务二' },
    { role: 'ai', planCard: { status: 'pending', plan: '1. b 2. c' } },
    { role: 'assistant', content: '无卡' }
  ]
  const p = buildSessionPlans(list, steps)
  assert.equal(p.length, 2)
  assert.equal(p[0].status, 'pending')
  assert.equal(p[0].mi, 3)
  assert.equal(p[0].label, '任务二')
  assert.equal(p[0].steps, 2)
  assert.equal(p[0].vers, 0)
  assert.equal(p[1].status, 'approved')
  assert.equal(p[1].label, '任务一')
  assert.equal(p[1].vers, 2)
  assert.equal(p[1].steps, 1)
})

test('计划列表：找不到前序提问用占位；非 AI/无卡行跳过', () => {
  const list = [
    { role: 'ai', planCard: { status: 'drafting', plan: '' } },
    { role: 'user', content: '  ' }
  ]
  const p = buildSessionPlans(list, steps)
  assert.equal(p.length, 1)
  assert.equal(p[0].label, '（无提问）')
  assert.equal(p[0].status, 'drafting')
})

test('计划列表：空/空值输入返回空数组', () => {
  assert.deepEqual(buildSessionPlans(null, steps), [])
  assert.deepEqual(buildSessionPlans([], steps), [])
})

test('状态文案：五态映射，未知原样返回', () => {
  assert.equal(planStateText('drafting'), '生成中')
  assert.equal(planStateText('pending'), '待确认')
  assert.equal(planStateText('approved'), '已批准')
  assert.equal(planStateText('superseded'), '已被取代')
  assert.equal(planStateText('rejected'), '未批准')
  assert.equal(planStateText('stopped'), 'stopped')
})
