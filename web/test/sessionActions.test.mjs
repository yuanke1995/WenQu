import test from 'node:test'
import assert from 'node:assert/strict'
import { ref } from 'vue'
import { createSessionActions } from '../src/chat/sessionActions.js'

const setup = ({ list = [], streams = new Set(), sid = 's1', loggedIn = true,
  createRet = { success: true, data: { sessionId: 'new1' } } } = {}) => {
  const calls = {
    settle: 0, switch: [], clear: 0, agent: [], route: [], home: 0, focus: 0,
    create: 0, del: [], reload: 0, err: [], ok: []
  }
  const state = { list, sid }
  const creating = ref(false)
  const workspaceEpoch = ref(0)
  const a = createSessionActions({
    creating,
    workspaceEpoch,
    currentSid: () => state.sid,
    setCurrentSid: v => { state.sid = v },
    list: () => state.list,
    streams,
    settleSteer: () => { calls.settle++ },
    switchSession: async s => { calls.switch.push(s) },
    clearView: () => { calls.clear++ },
    setAgentMemory: (s, id) => { calls.agent.push([s, id]) },
    replaceRoute: s => { calls.route.push(s) },
    replaceHome: () => { calls.home++ },
    focusInput: () => { calls.focus++ },
    createApi: async () => { calls.create++; if (createRet instanceof Error) throw createRet; return createRet },
    deleteApi: async s => { calls.del.push(s) },
    reloadSessions: async () => { calls.reload++ },
    isLoggedIn: () => loggedIn,
    notifyError: m => { calls.err.push(m) },
    notifySuccess: m => { calls.ok.push(m) }
  })
  return { a, calls, creating, workspaceEpoch, state }
}

test('新建：复用空会话并带上预设智能体，不发创建请求', async () => {
  const { a, calls, state } = setup({ list: [{ id: 's0', messageCount: 0 }], sid: 's1' })
  await a.createNewSession('ag1')
  assert.equal(calls.create, 0)
  assert.deepEqual(calls.switch, ['s0'])
  assert.deepEqual(calls.agent, [['s0', 'ag1']])
  assert.deepEqual(calls.route, ['s0'])
  assert.equal(calls.focus, 1)
  assert.equal(state.sid, 's1')
})

test('新建：空会话正是当前会话时只清视图，不再 switch', async () => {
  const { a, calls } = setup({ list: [{ id: 's1', messageCount: 0 }], sid: 's1' })
  await a.createNewSession()
  assert.equal(calls.switch.length, 0)
  assert.equal(calls.clear, 1)
  assert.deepEqual(calls.route, ['s1'])
})

test('新建：流式中的 0 条会话不复用', async () => {
  const streams = new Set(['s0'])
  const { a, calls } = setup({ list: [{ id: 's0', messageCount: 0 }], streams, sid: 's1' })
  await a.createNewSession()
  assert.equal(calls.create, 1)
  assert.deepEqual(calls.route, ['new1'])
})

test('新建：服务端返回同一 id 时递增工作区代次', async () => {
  const { a, calls, workspaceEpoch } = setup({ createRet: { success: true, data: { sessionId: 's1' } }, sid: 's1' })
  await a.createNewSession('ag9')
  assert.equal(workspaceEpoch.value, 1)
  assert.deepEqual(calls.agent, [['s1', 'ag9']])
  assert.equal(calls.reload, 1)
  assert.equal(calls.focus, 1)
})

test('新建：连点防护与失败提示', async () => {
  const { a, calls, creating } = setup({ createRet: new Error('500') })
  const p1 = a.createNewSession()
  creating.value = true
  await a.createNewSession()
  await p1
  assert.equal(calls.create, 1)
  assert.match(calls.err[0], /创建会话失败: 500/)
})

test('删除当前会话：落到剩余非空会话', async () => {
  const { a, calls, state } = setup({
    list: [{ id: 's1', messageCount: 3 }, { id: 's2', messageCount: 2 }, { id: 's3', messageCount: 0 }],
    sid: 's1'
  })
  await a.deleteSession('s1')
  assert.deepEqual(calls.del, ['s1'])
  assert.deepEqual(calls.ok, ['会话已删除'])
  assert.deepEqual(calls.switch, ['s2'])
  assert.equal(calls.reload, 1)
  assert.equal(state.sid, 's1')
})

test('删除当前会话且无剩余：清视图、清 sid、回首页', async () => {
  const { a, calls, state } = setup({ list: [{ id: 's1', messageCount: 3 }], sid: 's1' })
  await a.deleteSession('s1')
  assert.equal(calls.settle, 1)
  assert.equal(calls.clear, 1)
  assert.equal(calls.home, 1)
  assert.equal(state.sid, null)
})

test('删除失败：提示且不刷新列表', async () => {
  const { a, calls } = setup()
  calls.del = []
  const s = setup()
  s.a.deleteSession = undefined
  // 用注入点的替换验证异常路径
  const fail = createSessionActions({
    creating: ref(false), workspaceEpoch: ref(0), currentSid: () => 's1', setCurrentSid: () => {},
    list: () => [], streams: new Set(), settleSteer: () => {}, switchSession: async () => {},
    clearView: () => {}, setAgentMemory: () => {}, replaceRoute: () => {}, replaceHome: () => {},
    focusInput: () => {}, createApi: async () => ({}), deleteApi: async () => { throw new Error('403') },
    reloadSessions: async () => {}, notifyError: m => { s.calls.err.push(m) }, notifySuccess: () => {}
  })
  await fail.deleteSession('s1')
  assert.match(s.calls.err[0], /403/)
  assert.equal(s.calls.reload, 0)
})

test('自动落点：有非空会话切过去，否则新建', async () => {
  const one = setup({ list: [{ id: 's9', messageCount: 1 }] })
  await one.a.autoPick()
  assert.deepEqual(one.calls.switch, ['s9'])

  const none = setup({ list: [] })
  await none.a.autoPick()
  assert.equal(none.calls.create, 1)
})

test('未登录：新建与自动落点都不发请求、不提示（登出后收尾动作作废）', async () => {
  const create = setup({ loggedIn: false, list: [{ id: 's0', messageCount: 0 }] })
  await create.a.createNewSession('ag1')
  assert.equal(create.calls.create, 0)
  assert.deepEqual(create.calls.switch, [])
  assert.deepEqual(create.calls.route, [])
  assert.deepEqual(create.calls.err, [])

  const pick = setup({ loggedIn: false, list: [{ id: 's9', messageCount: 1 }] })
  await pick.a.autoPick()
  assert.deepEqual(pick.calls.switch, [])
  assert.equal(pick.calls.create, 0)
})

test('401 失败不叠业务提示（全局 app:unauthorized 已接管）', async () => {
  const e = new Error('请先登录')
  e.status = 401
  const { a, calls } = setup({ createRet: e })
  await a.createNewSession()
  assert.equal(calls.create, 1)
  assert.deepEqual(calls.err, [])
})
