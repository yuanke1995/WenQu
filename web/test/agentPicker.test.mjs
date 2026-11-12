import test from 'node:test'
import assert from 'node:assert/strict'
import { ref } from 'vue'
import { createAgentPicker } from '../src/chat/agentPicker.js'

const setup = ({ list = [{ id: 'a1', name: '助手一', isDefault: 0 }, { id: 'a2', name: '助手二', isDefault: 1 }],
                 consult = [], sid = 's1', msgs = [] } = {}) => {
  const calls = { picked: 0, remembered: [] }
  const sidNow = ref(sid)
  const p = createAgentPicker({
    currentSid: () => sidNow.value,
    messages: () => msgs,
    listAgentsApi: async () => ({ success: true, data: list }),
    listConsultableApi: async () => ({ success: true, data: consult }),
    remember: l => calls.remembered.push(l),
    onPick: () => { calls.picked++ }
  })
  return { p, calls, msgs, setSid: v => { sidNow.value = v } }
}

test('加载清单：默认智能体优先生效，并登记名称表', async () => {
  const { p, calls } = setup({ consult: [{ id: 'sub', name: '子' }] })
  await p.loadAgents()
  assert.equal(p.agentList.value.length, 2)
  assert.equal(p.defaultAgentId.value, 'a2')
  assert.equal(p.hasDefaultAgent.value, true)
  assert.equal(calls.remembered.length, 2)
  assert.equal(calls.remembered[1][0].id, 'sub')
})

test('无默认时用列表第一个；接口异常静默回落', async () => {
  const { p } = setup({ list: [{ id: 'x', name: 'X' }] })
  await p.loadAgents()
  assert.equal(p.defaultAgentId.value, 'x')
  assert.equal(p.hasDefaultAgent.value, false)

  const boom = createAgentPicker({
    currentSid: () => 's1', messages: () => [],
    listAgentsApi: async () => { throw new Error('net') },
    listConsultableApi: async () => ({ success: false })
  })
  await boom.loadAgents()
  assert.deepEqual(boom.agentList.value, [])
  assert.equal(boom.currentAgentId.value, '')
})

test('待选优先级：显式选择 > 上一问作答者 > 平台默认', async () => {
  const { p, msgs, setSid } = setup()
  msgs.push({ role: 'user', content: '问' }, { role: 'ai', agentId: 'a1', agentName: '助手一' })
  assert.equal(p.currentAgentId.value, 'a1')
  p.pickAgent('a2')
  assert.equal(p.currentAgentId.value, 'a2')
  setSid('s2')
  assert.equal(p.currentAgentId.value, 'a1')
  setSid('s1')
  p.currentAgentId.value = ''
  assert.equal(p.currentAgentId.value, '')
})

test('上一问的智能体已不在清单时回默认，不用已消失的人', async () => {
  const { p, msgs } = setup()
  await p.loadAgents()
  msgs.push({ role: 'ai', agentId: 'gone', agentName: '已删除' })
  assert.equal(p.currentAgentId.value, 'a2')
  assert.equal(p.currentAgentName.value, '助手二')
})

test('清单未拉回时跟随上一问的名字快照', () => {
  const { p, msgs } = setup({ list: [] })
  msgs.push({ role: 'ai', agentId: 'a9', agentName: '旧名' })
  assert.equal(p.currentAgentId.value, 'a9')
  assert.equal(p.currentAgentName.value, '旧名')
  assert.equal(p.currentAgent.value, null)
})

test('pickAgent 关闭选择器并写本会话记忆', () => {
  const { p, calls, setSid } = setup()
  p.pickAgent('a1')
  assert.equal(calls.picked, 1)
  assert.equal(p.agentMap.value.s1, 'a1')
  setSid('s2')
  assert.equal(p.agentMap.value.s2, undefined)
})

test('归属徽标：首条助手显示、同一人连续不重复、换人才显示', () => {
  const { p, msgs } = setup()
  msgs.push(
    { role: 'user', content: 'q' },
    { role: 'ai', agentName: 'A' },
    { role: 'ai', agentName: 'A' },
    { role: 'ai', agentName: 'B' },
    { role: 'ai' }
  )
  assert.equal(p.showAgentTag(msgs[1], 1), true)
  assert.equal(p.showAgentTag(msgs[2], 2), false)
  assert.equal(p.showAgentTag(msgs[3], 3), true)
  assert.equal(p.showAgentTag(msgs[4], 4), false)
})
