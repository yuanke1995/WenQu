import test from 'node:test'
import assert from 'node:assert/strict'
import { createThinkingLevels } from '../src/chat/thinkingLevels.js'

const storageOf = (init = {}) => {
  const m = new Map(Object.entries(init))
  return { getItem: k => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, v), dump: () => Object.fromEntries(m) }
}

const setup = ({ models = {}, effective = 'a/x', busy = false, storage = storageOf(), remote = null } = {}) => {
  let busyNow = busy
  const t = createThinkingLevels({
    modelIndexOf: () => models,
    effectiveModelOf: () => effective,
    isBusy: () => busyNow,
    storage,
    remote
  })
  return { t, storage, setBusy: v => { busyNow = v } }
}

test('可开关模型：默认关思考，选档位后开启并落盘', () => {
  const { t, storage } = setup({ models: { 'a/x': { thinking: 'switchable', reasoningLevels: ['low', 'high'] } } })
  assert.equal(t.deepOnOf('a/x'), false)
  assert.equal(t.currentLevelOf('a/x'), 'off')
  t.setThinkLevel('high')
  assert.equal(t.deepOnOf('a/x'), true)
  assert.equal(t.currentLevelOf('a/x'), 'high')
  assert.equal(t.reasoningLevelParam.value, 'high')
  assert.deepEqual(JSON.parse(storage.dump().ai_deep_think), { 'a/x': 1 })
  assert.deepEqual(JSON.parse(storage.dump().ai_think_level), { 'a/x': 'high' })
})

test('关闭思考：档位记为 off，下发参数为空串', () => {
  const { t } = setup({ models: { 'a/x': { thinking: 'switchable', reasoningLevels: ['low', 'high'] } } })
  t.setThinkLevel('low')
  t.setThinkLevel('off')
  assert.equal(t.deepOnOf('a/x'), false)
  assert.equal(t.currentLevelOf('a/x'), 'off')
  assert.equal(t.reasoningLevelParam.value, '')
})

test('恒思考模型：强制开、选项锁定为首个档位、改档位无效', () => {
  const { t } = setup({ models: { 'a/x': { thinking: 'always', reasoningLevels: ['max'] } } })
  assert.equal(t.deepOnOf('a/x'), true)
  assert.deepEqual(t.levelOptionsOf('a/x'), [{ value: 'max', label: '恒思考 · 极致' }])
  assert.equal(t.currentLevelOf('a/x'), 'max')
  t.setThinkLevel('low')
  assert.equal(t.currentLevelOf('a/x'), 'max')
})

test('未登记档位：只给开关，当前为开启态且下发参数仍为空串', () => {
  const { t } = setup({ models: { 'a/x': { thinking: 'switchable' } } })
  const opts = t.levelOptionsOf('a/x')
  assert.deepEqual(opts.map(o => o.value), ['__on__', 'off'])
  t.setThinkLevel('__on__')
  assert.equal(t.deepOnOf('a/x'), true)
  assert.equal(t.currentLevelOf('a/x'), '__on__')
  assert.equal(t.reasoningLevelParam.value, '')
})

test('登记默认档位：未单独选择时用模型默认', () => {
  const { t } = setup({ models: { 'a/x': { thinking: 'switchable', reasoningLevels: ['low', 'high'], defaultReasoningLevel: 'high' } } })
  t.setThinkLevel('__on__')
  assert.equal(t.currentLevelOf('a/x'), 'high')
})

test('__default 记忆：无逐模型选择时按默认开关走', () => {
  const { t } = setup({
    models: { 'a/x': { thinking: 'switchable', reasoningLevels: ['low'] } },
    storage: storageOf({ ai_deep_think: JSON.stringify({ __default: true }) })
  })
  assert.equal(t.deepOnOf('a/x'), true)
})

test('跑动中禁止改档位', () => {
  const { t, setBusy } = setup({ models: { 'a/x': { thinking: 'switchable', reasoningLevels: ['low', 'high'] } } })
  t.setThinkLevel('high')
  assert.equal(t.currentLevelOf('a/x'), 'high')
  setBusy(true)
  t.setThinkLevel('low')
  assert.equal(t.currentLevelOf('a/x'), 'high')
})

test('坏存储不抛错：回落空记忆', () => {
  const { t } = setup({
    models: { 'a/x': { thinking: 'switchable', reasoningLevels: ['low'] } },
    storage: storageOf({ ai_deep_think: '{bad', ai_think_level: 'null' })
  })
  assert.equal(t.deepOnOf('a/x'), false)
  assert.equal(t.currentLevelOf('a/x'), 'off')
})

test('未知模型引用按可开关口径兜底', () => {
  const { t } = setup({ models: {}, effective: 'z/none' })
  assert.deepEqual(t.thinkCapsOf('z/none'), { visible: true, locked: false, on: null })
  assert.equal(t.currentLevelOf('z/none'), 'off')
  assert.equal(t.deepThinkOn.value, false)
})

test('服务端回灌：服务端为准，本机独有的模型补写上行（换设备不丢）', () => {
  const saved = []
  const remote = { save: (ref, level) => { saved.push([ref, level]) } }
  const { t, storage } = setup({
    models: {
      'a/x': { thinking: 'switchable', reasoningLevels: ['low', 'high'] },
      'b/y': { thinking: 'switchable', reasoningLevels: ['low', 'max'] }
    },
    storage: storageOf({
      ai_deep_think: JSON.stringify({ 'a/x': 1, 'b/y': 1 }),
      ai_think_level: JSON.stringify({ 'a/x': 'high', 'b/y': 'max' })
    }),
    remote
  })
  t.hydrateThinkLevels({ 'a/x': 'low' })
  assert.equal(t.currentLevelOf('a/x'), 'low')
  assert.deepEqual(saved, [['b/y', 'max']])
  assert.equal(t.currentLevelOf('b/y'), 'max')
  assert.deepEqual(JSON.parse(storage.dump().ai_think_level), { 'a/x': 'low', 'b/y': 'max' })
})

test('改档位写穿透服务端：档位 / 关闭 / 开启未指定强度三种口径', async () => {
  const saved = []
  const remote = { save: (ref, level) => { saved.push([ref, level]); return Promise.resolve() } }
  const { t } = setup({
    models: {
      'a/x': { thinking: 'switchable', reasoningLevels: ['low', 'high'] },
      'b/y': { thinking: 'switchable' }
    },
    effective: 'a/x',
    remote
  })
  t.setThinkLevel('high')
  t.setThinkLevel('off')
  t.setThinkLevel('__on__', 'b/y')
  await Promise.resolve()
  assert.deepEqual(saved, [['a/x', 'high'], ['a/x', 'off'], ['b/y', 'on']])
})

test('未登录（无 remote）：只落本机，不抛错', () => {
  const { t, storage } = setup({ models: { 'a/x': { thinking: 'switchable', reasoningLevels: ['low'] } } })
  t.setThinkLevel('low')
  assert.deepEqual(JSON.parse(storage.dump().ai_think_level), { 'a/x': 'low' })
})
