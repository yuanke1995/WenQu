import test from 'node:test'
import assert from 'node:assert/strict'
import { ref } from 'vue'
import { createModelSelection } from '../src/chat/modelSelection.js'

const setup = ({ sid = 's1', list = [], labelOf = r => `名:${r}` } = {}) => {
  const sidNow = ref(sid)
  const calls = { updates: [], errors: [] }
  const m = createModelSelection({
    currentSid: () => sidNow.value,
    sessionList: () => list,
    updateSessionApi: async (sid, val) => { calls.updates.push([sid, val]) },
    notifyError: msg => calls.errors.push(msg),
    labelOf
  })
  return { m, calls, sidNow }
}

test('生效模型：会话覆盖优先，其次个人默认', () => {
  const { m, sidNow } = setup()
  assert.equal(m.effectiveModel.value, '')
  m.userDefaultModel.value = 'p/default'
  assert.equal(m.effectiveModel.value, 'p/default')
  assert.equal(m.modelSourceLabel.value, '个人默认')
  m.currentOverrideModel.value = 'p/override'
  assert.equal(m.effectiveModel.value, 'p/override')
  assert.equal(m.modelSourceLabel.value, '会话指定')
  sidNow.value = 's2'
  assert.equal(m.effectiveModel.value, 'p/default')
})

test('切入模型即落库；无会话 ID 时只记本地', () => {
  const { m, calls } = setup()
  m.currentOverrideModel.value = 'a/x'
  assert.deepEqual(calls.updates, [['s1', 'a/x']])
  assert.equal(m.modelMap.value.s1, 'a/x')
  m.currentOverrideModel.value = ''
  assert.deepEqual(calls.updates[1], ['s1', ''])
})

test('落库失败给出提醒', async () => {
  const sidNow = ref('s1')
  const errors = []
  const m = createModelSelection({
    currentSid: () => sidNow.value,
    sessionList: () => [],
    updateSessionApi: async () => { throw new Error('500') },
    notifyError: msg => errors.push(msg),
    labelOf: r => r
  })
  m.currentOverrideModel.value = 'a/x'
  await new Promise(r => setTimeout(r, 0))
  assert.equal(errors.length, 1)
  assert.match(errors[0], /会话模型保存失败/)
})

test('会话列表回填：本地已有选择不动，可用性每次重算', () => {
  const list = [{ id: 's1', model: 'a/x', modelIssue: '停用' }, { id: 's2', model: 'b/y', modelIssue: '' }]
  const { m } = setup({ list })
  m.modelMap.value = { s1: 'local/z' }
  m.seedSessionModels()
  assert.equal(m.modelMap.value.s1, 'local/z')
  assert.equal(m.modelMap.value.s2, 'b/y')
  assert.equal(m.modelIssueMap.value.s1, '停用')
  list[0].modelIssue = ''
  m.seedSessionModels()
  assert.equal(m.modelIssueMap.value.s1, '')
})

test('失效原因：会话指定报会话的，跟随默认报默认的', () => {
  const list = [{ id: 's1', model: 'a/x', modelIssue: '供应商已停用' }]
  const { m } = setup({ list })
  m.seedSessionModels()
  m.currentOverrideModel.value = ''
  m.userDefaultModel.value = 'a/x'
  m.userDefaultModelIssue.value = '默认失效'
  assert.equal(m.effectiveModelIssue.value, '默认失效')
  m.currentOverrideModel.value = 'a/x'
  assert.equal(m.effectiveModelIssue.value, '供应商已停用')
})

test('展示信息取 modelIndex，展示名走注入的 labelOf', () => {
  const { m } = setup()
  m.modelIndex.value = { 'a/x': { displayName: 'X', providerName: '供应商', icon: 'i.png' } }
  m.userDefaultModel.value = 'a/x'
  assert.equal(m.effectiveModelLabel.value, '名:a/x')
  assert.equal(m.effectiveModelIcon.value, 'i.png')
  assert.equal(m.effectiveModelProvider.value, '供应商')
  m.userDefaultModel.value = 'z/none'
  assert.equal(m.effectiveModelIcon.value, '')
  assert.equal(m.effectiveModelProvider.value, '')
})
