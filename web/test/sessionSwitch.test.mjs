import test from 'node:test'
import assert from 'node:assert/strict'
import { ref } from 'vue'
import { createSessionSwitch } from '../src/chat/sessionSwitch.js'

const setup = ({ sid = 's1', stream = null, goalPlaceholder = null, deep = '',
                 history = { success: true, data: [] } } = {}) => {
  const calls = { settle: 0, urls: [], home: 0, models: [], todos: [], goals: [], lineage: 0, ensTodos: [], signs: 0,
                  attach: [], hydrates: [], deep: [], scroll: 0, loads: 0, picks: 0 }
  const state = { sid }
  const modelMap = ref({})
  const todosMap = ref({})
  const goalsMap = ref({})
  const historyError = ref('旧错误')
  const chatStreams = new Map(stream ? [['s1', stream]] : [])
  const messages = ref([{ role: 'user', content: '旧' }])
  const sw = createSessionSwitch({
    currentSid: () => state.sid,
    setCurrentSid: v => { state.sid = v },
    settleSteer: () => { calls.settle++ },
    dropGoalPlaceholderIfOther: v => { state.ph = null; calls.phDropped = v },
    goalPlaceholderFor: () => goalPlaceholder,
    modelMap,
    getSessionModel: async s => { calls.models.push(s); return { success: true, data: { model: 'v/m' } } },
    todosMap,
    getSessionTodos: async s => { calls.todos.push(s); return { success: true, data: { todos: [{ content: 't' }] } } },
    setTodos: (s, t) => { calls.ensTodos.push([s, t]) },
    goalsMap,
    loadGoalFor: async s => { calls.goals.push(s); return { plan: 1 } },
    setGoal: () => {},
    syncUrl: s => { calls.urls.push(s) },
    replaceHome: () => { calls.home++ },
    urlSid: () => 's1',
    deepApprovalOf: () => deep,
    getHistory: async (s, o) => { calls.hist = [s, o]; return history },
    buildList: rows => rows.map(r => ({ ...r })),
    dropSteered: l => l,
    historyError,
    chatStreams,
    messages,
    linkPlanLineage: () => { calls.lineage++ },
    ensureTurnTodos: (s, l) => { calls.ensTodos.push([s, l.length]) },
    refreshArtifactSigns: () => { calls.signs++ },
    attachRunningTurn: async s => { calls.attach.push(s) },
    hydrateAsk: s => { calls.hydrates.push(['ask', s]) },
    hydratePlans: s => { calls.hydrates.push(['plans', s]) },
    hydrateApprovals: (s, id) => { calls.hydrates.push(['approvals', s, id]) },
    handleDeepLink: (s, id) => { calls.deep.push([s, id]) },
    scrollForce: () => { calls.scroll++ },
    loadSessions: async () => { calls.loads++ },
    autoPick: async () => { calls.picks++ }
  })
  return { sw, calls, state, modelMap, todosMap, goalsMap, historyError, messages, chatStreams }
}

test('切换会话：结算旧队列、补拉缺失的会话级数据、加载历史并恢复卡片', async () => {
  const a = setup({ history: { success: true, data: [{ role: 'user', content: 'q' }] } })
  await a.sw.switchSession('s2')
  assert.equal(a.calls.settle, 1)
  assert.equal(a.state.sid, 's2')
  assert.deepEqual(a.calls.urls, ['s2'])
  assert.deepEqual(a.calls.models, ['s2'])
  assert.deepEqual(a.calls.todos, ['s2'])
  assert.deepEqual(a.calls.goals, ['s2'])
  assert.equal(a.calls.lineage, 1)
  assert.equal(a.calls.signs, 1)
  assert.deepEqual(a.calls.attach, ['s2'])
  assert.deepEqual(a.calls.hydrates, [['ask', 's2'], ['plans', 's2'], ['approvals', 's2', '']])
  assert.equal(a.calls.scroll, 1)
  assert.equal(a.historyError.value, '')
  assert.equal(a.messages.value.length, 1)
})

test('同会话重铺：不清插话队列，占位气泡不动', async () => {
  const a = setup({ sid: 's1' })
  await a.sw.switchSession('s1')
  assert.equal(a.calls.settle, 0)
  assert.equal(a.calls.phDropped, 's1')
})

test('已有数据的会话不重复补拉', async () => {
  const a = setup()
  a.modelMap.value = { s2: 'v/m' }
  a.todosMap.value = { s2: [] }
  a.goalsMap.value = { s2: {} }
  await a.sw.switchSession('s2')
  assert.equal(a.calls.models.length, 0)
  assert.equal(a.calls.todos.length, 0)
  assert.equal(a.calls.goals.length, 0)
})

test('在途加载复用：同一会话只发一次历史请求', async () => {
  let release
  const gate = new Promise(r => { release = r })
  const a = setup()
  a.sw = createSessionSwitch({
    currentSid: () => 's1', setCurrentSid: () => {},
    settleSteer: () => {}, dropGoalPlaceholderIfOther: () => {}, goalPlaceholderFor: () => null,
    modelMap: ref({}), getSessionModel: async () => ({}), todosMap: ref({}), getSessionTodos: async () => ({}),
    setTodos: () => {}, goalsMap: ref({}), loadGoalFor: async () => ({}), setGoal: () => {},
    syncUrl: () => {}, replaceHome: () => {}, urlSid: () => 's1', deepApprovalOf: () => '',
    getHistory: async () => { a.calls.hist = (a.calls.hist || 0) + 1; await gate; return { success: true, data: [] } },
    buildList: r => r, dropSteered: l => l, historyError: ref(''), chatStreams: new Map(), messages: ref([]),
    linkPlanLineage: () => {}, ensureTurnTodos: () => {}, refreshArtifactSigns: () => {},
    attachRunningTurn: async () => {}, hydrateAsk: () => {}, hydratePlans: () => {}, hydrateApprovals: () => {},
    handleDeepLink: () => {}, scrollForce: () => {}, loadSessions: async () => {}, autoPick: async () => {}
  })
  const p1 = a.sw.switchSession('s1')
  const p2 = a.sw.switchSession('s1')
  release()
  await Promise.all([p1, p2])
  assert.equal(a.calls.hist, 1)
})

test('晚到的历史响应不覆盖当前视图', async () => {
  const a = setup()
  const p = a.sw.switchSession('s2')
  a.state.sid = 's9'
  await p
  assert.equal(a.messages.value[0].content, '旧')
})

test('live 流与目标占位气泡接回视图尾部', async () => {
  const live = { msg: { loading: true, content: '流中' } }
  const ph = { role: 'user', goalWaiting: true }
  const a = setup({ stream: live, goalPlaceholder: ph })
  await a.sw.switchSession('s1')
  assert.equal(a.messages.value.at(-1).content, '流中')
  assert.equal(a.calls.attach.length, 0) // 已有 live，不再探接流
  assert.deepEqual(a.calls.hydrates, [['ask', 's1'], ['plans', 's1'], ['approvals', 's1', '']])
})

test('历史接口返回失败结构：清空视图并给失败文案', async () => {
  const a = setup({ history: { success: false, msg: '后端忙' } })
  await a.sw.switchSession('s2')
  assert.deepEqual(a.messages.value, [])
  assert.equal(a.historyError.value, '后端忙')
})

test('403/404：静默回退到最近会话或新建，不显示失败卡', async () => {
  const a = setup()
  a.sw = createSessionSwitch({
    currentSid: () => 's1', setCurrentSid: v => { a.state.sid = v },
    settleSteer: () => {}, dropGoalPlaceholderIfOther: () => {}, goalPlaceholderFor: () => null,
    modelMap: ref({}), getSessionModel: async () => ({}), todosMap: ref({}), getSessionTodos: async () => ({}),
    setTodos: () => {}, goalsMap: ref({}), loadGoalFor: async () => ({}), setGoal: () => {},
    syncUrl: () => {}, replaceHome: () => { a.calls.home++ }, urlSid: () => 's1', deepApprovalOf: () => '',
    getHistory: async () => { const e = new Error('gone'); e.status = 404; throw e },
    buildList: r => r, dropSteered: l => l, historyError: a.historyError, chatStreams: new Map(), messages: a.messages,
    linkPlanLineage: () => {}, ensureTurnTodos: () => {}, refreshArtifactSigns: () => {},
    attachRunningTurn: async () => {}, hydrateAsk: () => {}, hydratePlans: () => {}, hydrateApprovals: () => {},
    handleDeepLink: () => {}, scrollForce: () => {}, loadSessions: async () => { a.calls.loads++ }, autoPick: async () => { a.calls.picks++ }
  })
  await a.sw.switchSession('s1')
  assert.equal(a.state.sid, null)
  assert.equal(a.calls.home, 1)
  assert.equal(a.calls.loads, 1)
  assert.equal(a.calls.picks, 1)
  assert.equal(a.historyError.value, '')  // 切换开始即清失败标记；403 走静默回退不写错误
})

test('普通错误：清空视图并显示失败文案，不触发回退', async () => {
  const a = setup()
  a.sw = createSessionSwitch({
    currentSid: () => 's1', setCurrentSid: () => {}, settleSteer: () => {}, dropGoalPlaceholderIfOther: () => {},
    goalPlaceholderFor: () => null, modelMap: ref({}), getSessionModel: async () => ({}), todosMap: ref({}),
    getSessionTodos: async () => ({}), setTodos: () => {}, goalsMap: ref({}), loadGoalFor: async () => ({}), setGoal: () => {},
    syncUrl: () => {}, replaceHome: () => { a.calls.home++ }, urlSid: () => 's1', deepApprovalOf: () => '',
    getHistory: async () => { const e = new Error('500'); e.status = 500; throw e },
    buildList: r => r, dropSteered: l => l, historyError: a.historyError, chatStreams: new Map(), messages: a.messages,
    linkPlanLineage: () => {}, ensureTurnTodos: () => {}, refreshArtifactSigns: () => {},
    attachRunningTurn: async () => {}, hydrateAsk: () => {}, hydratePlans: () => {}, hydrateApprovals: () => {},
    handleDeepLink: () => {}, scrollForce: () => {}, loadSessions: async () => {}, autoPick: async () => {}
  })
  await a.sw.switchSession('s1')
  assert.deepEqual(a.messages.value, [])
  assert.equal(a.historyError.value, '500')
  assert.equal(a.calls.home, 0)
})
