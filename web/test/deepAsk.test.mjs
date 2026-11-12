import test from 'node:test'
import assert from 'node:assert/strict'
import { ref, reactive, nextTick, markRaw } from 'vue'
import { goalEntryAllowed, goalStartText, goalEditKeydownIntent, askOptionParts, curAskAt,
         askTimeState, askNeedsTicker, askAutoSubmitReady, nextAskPage, askPickGuard,
         shouldAdvanceCustom, askPanelKeydownIntent, askCustomKeydownIntent, askPanelPlaceholder,
         normalizeCsumInstr, recompactShouldClose } from '../src/chat/deepAsk.js'
import { useDeepAsk } from '../src/chat/useDeepAsk.js'

// ---------- 纯判据 ----------

test('准入：允许深度执行且无进行中目标才显示入口，终态可再起一条', () => {
  assert.equal(goalEntryAllowed(true, null, false), true)
  assert.equal(goalEntryAllowed(true, { id: 'g' }, false), false)
  assert.equal(goalEntryAllowed(true, { id: 'g' }, true), true)
  assert.equal(goalEntryAllowed(false, null, true), false)
})

test('正文：prefill 优先、否则取输入框、统一去首尾空白', () => {
  assert.equal(goalStartText(' 目标 ', '输入框'), '目标')
  assert.equal(goalStartText(null, ' 输入框 '), '输入框')
  assert.equal(goalStartText(undefined, ''), '')
  assert.equal(goalStartText('', '输入框'), '')
})

test('就地编辑键位：输入法放行、Enter 保存、修饰键换行、Esc 取消', () => {
  assert.equal(goalEditKeydownIntent({ isComposing: true, key: 'Enter' }), null)
  assert.equal(goalEditKeydownIntent({ keyCode: 229, key: 'Enter' }), null)
  assert.equal(goalEditKeydownIntent({ key: 'Enter' }), 'save')
  assert.equal(goalEditKeydownIntent({ key: 'Enter', shiftKey: true }), null)
  assert.equal(goalEditKeydownIntent({ key: 'Escape' }), 'cancel')
  assert.equal(goalEditKeydownIntent({ key: 'a' }), null)
})

test('选项投影：中英文冒号拆关键词与说明，无冒号整句作关键词', () => {
  assert.deepEqual(askOptionParts('关键词：说明'), { kw: '关键词', rest: '说明' })
  assert.deepEqual(askOptionParts('key: value'), { kw: 'key', rest: ' value' })
  assert.deepEqual(askOptionParts('词：：说明'), { kw: '词', rest: '说明' })
  assert.deepEqual(askOptionParts('整句'), { kw: '整句', rest: '' })
  assert.deepEqual(askOptionParts(''), { kw: '', rest: '' })
  assert.deepEqual(askOptionParts('：开头冒号'), { kw: '：开头冒号', rest: '' })
})

test('分页：取当前题、越界回退末题、无题给空占位', () => {
  const a = { questions: [
    { question: 'Q1', options: [], multi: false },
    { question: 'Q2', options: [], multi: false },
    { question: 'Q3', options: [], multi: false }
  ] }
  assert.equal(curAskAt(a, 0).question, 'Q1')
  assert.equal(curAskAt(a, 2).question, 'Q3')
  assert.equal(curAskAt(a, 9).question, 'Q3')
  const blank = { topic: '', question: '', options: [], multi: false }
  assert.deepEqual(curAskAt(null, 0), blank)
  assert.deepEqual(curAskAt({ questions: [] }, 0), blank)
})

test('倒计时：无 deadline 不计时；mm:ss、过期、已答三态', () => {
  assert.deepEqual(askTimeState({ deadline: 0 }, 1000), { remaining: 0, expired: false, text: '' })
  assert.deepEqual(askTimeState(null, 1000), { remaining: 0, expired: false, text: '' })
  const live = askTimeState({ deadline: 65000, answered: false }, 0)
  assert.equal(live.remaining, 65000)
  assert.equal(live.text, '01:05')
  assert.equal(live.expired, false)
  const exp = askTimeState({ deadline: 1000, answered: false }, 2000)
  assert.equal(exp.remaining, 0)
  assert.equal(exp.expired, true)
  assert.equal(exp.text, '00:00')
  const done = askTimeState({ deadline: 1000, answered: true }, 2000)
  assert.equal(done.expired, false)
  assert.equal(done.text, '')
})

test('心跳/自动提交/跳题/受理/自定义跳题判据', () => {
  assert.equal(askNeedsTicker({ deadline: 10 }), true)
  assert.equal(askNeedsTicker({ deadline: 0 }), false)
  assert.equal(askAutoSubmitReady({ busy: false, answered: false }, false, true), true)
  assert.equal(askAutoSubmitReady({ busy: true, answered: false }, false, true), false)
  assert.equal(askAutoSubmitReady({ busy: false, answered: false }, true, true), false)
  assert.equal(askAutoSubmitReady(null, false, true), false)
  assert.equal(nextAskPage(3, 1), 1)
  assert.equal(nextAskPage(3, -1), 2)
  const m = { ask: { busy: false, answered: false } }
  assert.equal(askPickGuard(m, false), true)
  assert.equal(askPickGuard(m, true), false)
  assert.equal(askPickGuard({ ask: { busy: true } }, false), false)
  assert.equal(askPickGuard(null, false), false)
  assert.equal(shouldAdvanceCustom({ multi: false }, true, () => true), true)
  assert.equal(shouldAdvanceCustom({ multi: false }, true, () => false), false)
  assert.equal(shouldAdvanceCustom({ multi: true }, true, () => true), false)
  assert.equal(shouldAdvanceCustom({ multi: false }, false, () => true), false)
})

test('面板键盘意图：上下/Tab 移高亮、Enter 选中、自定义行、忙时放行', () => {
  const base = { busy: false, answered: false, expired: false, optionsCount: 2,
                 inInput: false, picks: [], customOn: false, hi: null }
  assert.deepEqual(askPanelKeydownIntent({ key: 'ArrowDown' }, base),
                   { type: 'move', hi: 0, prevent: true })
  assert.deepEqual(askPanelKeydownIntent({ key: 'ArrowUp' }, base),
                   { type: 'move', hi: 1, prevent: true })
  assert.deepEqual(askPanelKeydownIntent({ key: 'Tab', shiftKey: true }, { ...base, hi: 0 }),
                   { type: 'move', hi: 2, prevent: true })
  assert.deepEqual(askPanelKeydownIntent({ key: 'ArrowDown' }, { ...base, picks: [1] }),
                   { type: 'move', hi: 2, prevent: true })
  assert.deepEqual(askPanelKeydownIntent({ key: 'ArrowDown' }, { ...base, customOn: true }),
                   { type: 'move', hi: 0, prevent: true })
  assert.deepEqual(askPanelKeydownIntent({ key: 'Enter' }, { ...base, hi: 1 }),
                   { type: 'pick', index: 1, prevent: true })
  assert.deepEqual(askPanelKeydownIntent({ key: ' ' }, { ...base, hi: 2 }),
                   { type: 'custom', prevent: true })
  assert.deepEqual(askPanelKeydownIntent({ key: 'Enter' }, base),
                   { type: 'noop', prevent: true })
  assert.equal(askPanelKeydownIntent({ key: 'Enter' }, { ...base, busy: true }), null)
  assert.equal(askPanelKeydownIntent({ key: 'Enter' }, { ...base, expired: true }), null)
  assert.equal(askPanelKeydownIntent({ key: 'a' }, base), null)
  assert.equal(askPanelKeydownIntent({ key: 'Enter' }, { ...base, inInput: true, hi: 1 }), null)
})

test('自定义行回车、窄屏占位、压缩文本与收起判据', () => {
  assert.equal(askCustomKeydownIntent({ key: 'Enter' }), 'commit')
  assert.equal(askCustomKeydownIntent({ isComposing: true, key: 'Enter' }), null)
  assert.equal(askCustomKeydownIntent({ key: 'a' }), null)
  assert.equal(askPanelPlaceholder(true), '输入你的回答…')
  assert.equal(askPanelPlaceholder(false), '输入你的回答…（Enter 确认）')
  assert.equal(normalizeCsumInstr('  重点  '), '重点')
  assert.equal(normalizeCsumInstr(null), '')
  assert.equal(recompactShouldClose(true), true)
  assert.equal(recompactShouldClose(undefined), false)
})

// ---------- composable ----------

const askCard = (over = {}) => ({
  id: 'ask-1',
  questions: [
    { topic: 'T1', question: 'Q1', options: ['甲：说明A', '乙'], multi: false },
    { topic: 'T2', question: 'Q2', options: ['丙：说明C', '丁', '戊'], multi: true }
  ],
  picks: { 0: [], 1: [] },
  customOn: { 0: false, 1: false },
  busy: false,
  answered: false,
  deadline: 0,
  askQueue: [],
  ...over
})

const withAsk = (state, ask) => {
  state.msgs = [{ role: 'user', content: '问' }, { role: 'ai', ask }]
  return state.msgs[1].ask
}

const makeCtx = (over = {}) => {
  const state = reactive({
    msgs: [], loading: false, goalModeOn: true, sessionGoal: null, goalTerminal: false,
    planModeOn: false, goalEditBusy: false, isNarrow: false, searchOpen: false,
    searchQuery: '', editingIdx: null, editingMessageId: '', currentSessionId: 'sid-1',
    skillList: [], pickedSkills: [], agentList: []
  })
  const text = ref('')
  const goalStarting = ref(false)
  const goalEditing = ref(false)
  const searchPos = ref(0)
  const log = {
    info: [], warning: [], focusInput: 0, setPlanMode: [], startGoal: [], saveGoalEdit: 0,
    askHintText: [], pickAskOption: [], commitAskCustom: [], askQuestionAnswered: [],
    askNextUnanswered: [], askAutoSubmittable: [], askSubmitAll: [], recompactWith: [],
    hideThinkPanel: 0, disarmEsc: 0, paintSearchHighlight: 0, scrollToMatch: 0, cancelEdit: 0,
    refreshSetupGuide: 0, loadPermissionPresets: 0, ready: 0, loadSkills: 0, toggleSkill: [],
    loadAgents: 0, pickAgent: [], updateProjectItem: [], syncScrollbarGutter: 0,
    windowEvents: [], docEvents: [], routerReplace: [], tickers: [], cleared: []
  }
  let clock = 0
  let tickerId = 0
  const ctx = {
    messages: () => state.msgs,
    loading: () => state.loading,
    goalModeOn: () => state.goalModeOn,
    sessionGoal: () => state.sessionGoal,
    goalTerminal: () => state.goalTerminal,
    planModeOn: () => state.planModeOn,
    goalEditBusy: () => state.goalEditBusy,
    isNarrow: () => state.isNarrow,
    searchOpen: () => state.searchOpen,
    searchQuery: () => state.searchQuery,
    editingIdx: () => state.editingIdx,
    editingMessageId: () => state.editingMessageId,
    currentSessionId: () => state.currentSessionId,
    skillList: () => state.skillList,
    pickedSkills: () => state.pickedSkills,
    agentList: () => state.agentList,
    text, goalStarting, goalEditing, searchPos,
    notify: { info: t => log.info.push(t), warning: t => log.warning.push(t) },
    focusInput: () => { log.focusInput++ },
    setPlanMode: v => log.setPlanMode.push(v),
    startGoal: async obj => { log.startGoal.push(obj); return { id: 'g1' } },
    saveGoalEdit: () => { log.saveGoalEdit++ },
    askHintText: (a, p) => { log.askHintText.push([a, p]); return '提示' + p },
    pickAskOption: (m, p, oi) => log.pickAskOption.push([m, p, oi]),
    commitAskCustom: (m, p) => log.commitAskCustom.push([m, p]),
    askQuestionAnswered: (a, p) => { log.askQuestionAnswered.push([a, p]); return true },
    askNextUnanswered: (a, p) => { log.askNextUnanswered.push([a, p]); return -1 },
    askAutoSubmittable: a => { log.askAutoSubmittable.push(a); return false },
    askSubmitAll: m => log.askSubmitAll.push(m),
    recompactWith: async s => { log.recompactWith.push(s); return true },
    hideThinkPanel: () => { log.hideThinkPanel++ },
    disarmEsc: () => { log.disarmEsc++ },
    paintSearchHighlight: () => { log.paintSearchHighlight++ },
    scrollToMatch: () => { log.scrollToMatch++ },
    cancelEdit: () => { log.cancelEdit++ },
    onGlobalKeydown: () => {}, onGlobalPaste: () => {},
    onWindowResize: () => {}, onOrientationChange: () => {}, onDocPointerDown: () => {},
    refreshSetupGuide: () => { log.refreshSetupGuide++ },
    loadPermissionPresets: () => { log.loadPermissionPresets++ },
    ready: async () => { log.ready++ },
    loadSkills: async () => { log.loadSkills++; state.skillList = [{ name: 'S' }] },
    toggleSkill: n => log.toggleSkill.push(n),
    loadAgents: async () => { log.loadAgents++; state.agentList = [{ id: 'a1' }] },
    pickAgent: id => log.pickAgent.push(id),
    updateProjectItem: async (p, i, patch) => { log.updateProjectItem.push([p, i, patch]); return {} },
    syncScrollbarGutter: () => { log.syncScrollbarGutter++ },
    route: { query: {} },
    router: { replace: async v => { log.routerReplace.push(v); return {} } },
    window: { addEventListener: (t, fn) => log.windowEvents.push([t, fn]) },
    document: { querySelector: () => null, activeElement: null,
                addEventListener: (t, fn) => log.docEvents.push([t, fn]) },
    HTMLTextAreaElement: null,
    now: () => clock,
    setInterval: (fn, ms) => { log.tickers.push({ fn, ms }); return ++tickerId },
    clearInterval: id => log.cleared.push(id),
    ...over
  }
  return { ctx, state, refs: { text, goalStarting, goalEditing, searchPos }, log }
}

const build = (over = {}) => {
  const c = makeCtx(over)
  return { ...c, e: useDeepAsk(c.ctx) }
}

test('composable：区间内 26 个顶层声明名全部返回，另暴露初始化入口', () => {
  const { e } = build()
  const names = ['goalEntryVisible', 'startGoalNow', 'onGoalEditKeydown',
    'pendingAsk', 'askQueueCount', 'askPage', 'askCustomRef', 'askNow', 'askHi',
    'askCustomPlaceholder', 'askTimer', 'curAsk', 'askPanelHint', 'askRemaining',
    'askExpired', 'askCountdownText', 'askOptionParts', 'focusAskRow', 'onPick',
    'commitCustomAt', 'advanceAfterSelect', 'onAskCustomKeydown', 'onAskPanelKeydown',
    'csumInstrOpen', 'csumInstr', 'applyRecompact']
  for (const n of names) assert.ok(n in e, n)
  assert.equal(typeof e.askOptionParts, 'function')
  assert.equal(typeof e.initialize, 'function')
})

test('入口显隐：随 ctx 的准入值联动', () => {
  const { e, state } = build()
  assert.equal(e.goalEntryVisible.value, true)
  state.sessionGoal = { id: 'g' }
  assert.equal(e.goalEntryVisible.value, false)
  state.goalTerminal = true
  assert.equal(e.goalEntryVisible.value, true)
  state.goalModeOn = false
  assert.equal(e.goalEntryVisible.value, false)
})

test('一句话创建：空正文提示并聚焦，不发起请求', async () => {
  const { e, log } = build()
  assert.equal(await e.startGoalNow(), null)
  assert.match(log.info[0], /先在输入框/)
  assert.equal(log.focusInput, 1)
  assert.equal(log.startGoal.length, 0)
})

test('一句话创建：清框、收计划标记、发起请求并翻转 starting', async () => {
  const { e, refs, log } = build({ planModeOn: () => true })
  refs.text.value = '  把这件事做完  '
  const g = await e.startGoalNow()
  assert.deepEqual(log.startGoal, ['把这件事做完'])
  assert.deepEqual(log.setPlanMode, [false])
  assert.equal(refs.text.value, '')
  assert.equal(refs.goalStarting.value, false)
  assert.deepEqual(g, { id: 'g1' })
})

test('一句话创建：失败归还正文、用户已打字不覆盖、prefill 不清框', async () => {
  const c1 = build({ startGoal: async () => null })
  c1.refs.text.value = '原句'
  await c1.e.startGoalNow()
  assert.equal(c1.refs.text.value, '原句')

  const c2 = build({ startGoal: async () => { c2.refs.text.value = '用户新写的'; return null } })
  c2.refs.text.value = '原句'
  await c2.e.startGoalNow()
  assert.equal(c2.refs.text.value, '用户新写的')

  const c3 = build()
  c3.refs.text.value = '输入框内容'
  await c3.e.startGoalNow('/deep 预填')
  assert.equal(c3.refs.text.value, '输入框内容')
  assert.deepEqual(c3.log.startGoal, ['/deep 预填'])
})

test('就地编辑键盘：Enter 保存、Esc 取消并阻止冒泡、忙时不取消', () => {
  const { e, refs, state, log } = build()
  let prevented = 0, stopped = 0
  const ev = (key, extra = {}) => ({ key, preventDefault: () => prevented++,
                                     stopPropagation: () => stopped++, ...extra })
  e.onGoalEditKeydown(ev('Enter'))
  assert.equal(log.saveGoalEdit, 1)
  e.onGoalEditKeydown(ev('Escape'))
  assert.equal(refs.goalEditing.value, false)
  state.goalEditBusy = true
  refs.goalEditing.value = true
  e.onGoalEditKeydown(ev('Escape'))
  assert.equal(refs.goalEditing.value, true)
  e.onGoalEditKeydown(ev('a'))
  assert.equal(prevented, 3)
  assert.equal(stopped, 2)
})

test('就地编辑聚焦：打开编辑后聚焦 textarea 并把插入点放到文末', async () => {
  let focused = 0, sel = null
  const el = { value: 'abc', focus: () => focused++, setSelectionRange: (a, b) => { sel = [a, b] } }
  const { e, refs } = build({ document: { querySelector: () => el, activeElement: null,
                                          addEventListener: () => {} } })
  refs.goalEditing.value = true
  await nextTick(); await nextTick()
  assert.equal(focused, 1)
  assert.deepEqual(sel, [3, 3])
})

test('提问面板取数：最后一条挂起卡、队列数、分页当前题、提示经 ctx', () => {
  const { e, state, log } = build()
  state.msgs = [{ role: 'user', content: '问' },
                { role: 'ai', ask: askCard(), askQueue: ['x', 'y'] }]
  const a = state.msgs[1].ask
  assert.equal(e.pendingAsk.value.role, 'ai')
  assert.equal(e.askQueueCount.value, 2)
  assert.equal(e.curAsk.value.question, 'Q1')
  e.askPage.value = 5
  assert.equal(e.curAsk.value.question, 'Q2')
  assert.equal(e.askPanelHint.value, '提示5')
  assert.equal(log.askHintText.at(-1)[0], a)
})

test('倒计时三态 computed 与窄屏占位文案', () => {
  const { e, state } = build()
  const proxy = withAsk(state, askCard({ deadline: 65000 }))
  assert.equal(e.askRemaining.value, 65000)
  assert.equal(e.askCountdownText.value, '01:05')
  assert.equal(e.askExpired.value, false)
  assert.equal(e.askCustomPlaceholder.value, '输入你的回答…（Enter 确认）')
  state.isNarrow = true
  assert.equal(e.askCustomPlaceholder.value, '输入你的回答…')
  e.askNow.value = 66000
  assert.equal(e.askRemaining.value, 0)
  assert.equal(e.askExpired.value, true)
  assert.equal(e.askCountdownText.value, '00:00')
  proxy.answered = true
  assert.equal(e.askExpired.value, false)
  assert.equal(e.askCountdownText.value, '')
})

test('点选项：单选记录并跳下一未答题、多选留在本题、忙时不受理', () => {
  const { e, state, log } = build({ askNextUnanswered: () => 1 })
  const proxy = withAsk(state, askCard())
  e.onPick(0)
  assert.equal(log.pickAskOption[0][1], 0)
  assert.equal(log.pickAskOption[0][2], 0)
  assert.equal(e.askPage.value, 1)
  e.onPick(1)
  assert.equal(e.askPage.value, 1)
  assert.equal(log.pickAskOption.length, 2)
  proxy.busy = true
  e.onPick(0)
  assert.equal(log.pickAskOption.length, 2)
})

test('自定义答案：回车确认并跳下一题、失焦只确认、多选不跳', () => {
  const { e, state, log } = build({ askNextUnanswered: () => 1 })
  withAsk(state, askCard())
  e.commitCustomAt(0, true)
  assert.equal(log.commitAskCustom.length, 1)
  assert.equal(e.askPage.value, 1)
  e.askPage.value = 0
  e.commitCustomAt(0, false)
  assert.equal(e.askPage.value, 0)
  e.askPage.value = 1
  e.commitCustomAt(1, true)
  assert.equal(e.askPage.value, 1)
})

test('面板键盘：高亮移动、回车选中选项与自定义行、输入框内放行', () => {
  const { e, state, log } = build()
  withAsk(state, askCard())
  let prevented = 0
  const ev = (key, extra = {}) => ({ key, target: { tagName: 'DIV' },
                                     preventDefault: () => prevented++, ...extra })
  e.onAskPanelKeydown(ev('ArrowDown'))
  assert.equal(e.askHi.value, 0)
  e.onAskPanelKeydown(ev('ArrowUp'))
  assert.equal(e.askHi.value, 2)
  e.onAskPanelKeydown(ev('Enter'))
  assert.equal(log.commitAskCustom.length, 1)
  e.askHi.value = 1
  e.onAskPanelKeydown(ev('Enter'))
  assert.equal(log.pickAskOption.length, 1)
  const before = prevented
  e.onAskPanelKeydown(ev('Enter', { target: { tagName: 'INPUT' } }))
  assert.equal(prevented, before)
})

test('自定义输入行回车：提交并阻止冒泡，输入法选字放行', () => {
  const { e, state, log } = build()
  withAsk(state, askCard())
  let prevented = 0, stopped = 0
  const ev = (extra = {}) => ({ key: 'Enter', preventDefault: () => prevented++,
                                stopPropagation: () => stopped++, ...extra })
  e.onAskCustomKeydown(ev())
  assert.equal(log.commitAskCustom.length, 1)
  assert.equal(prevented, 1)
  assert.equal(stopped, 1)
  e.onAskCustomKeydown(ev({ isComposing: true }))
  assert.equal(log.commitAskCustom.length, 1)
})

test('高亮行聚焦：落到自定义行聚焦输入框、落到选项行失焦', async () => {
  let focused = 0, blurred = 0
  const el = markRaw({ focus: () => focused++, blur: () => blurred++ })
  const { e, state } = build({ document: { querySelector: () => null, activeElement: el,
                                           addEventListener: () => {} } })
  withAsk(state, askCard())
  // 挂卡 watch 会重置高亮并排一次自动聚焦；先让两轮 nextTick 都落地，期间 ref 仍为 null
  await nextTick(); await nextTick()
  e.askCustomRef.value = el
  e.askHi.value = 2
  e.focusAskRow()
  await nextTick()
  assert.equal(focused, 1)
  e.askHi.value = 0
  e.focusAskRow()
  await nextTick()
  assert.equal(blurred, 1)
})

test('重新压缩：成功收起输入行，失败保留原文', async () => {
  const c1 = build()
  c1.e.csumInstrOpen.value = true
  c1.e.csumInstr.value = '  重点保留  '
  await c1.e.applyRecompact()
  assert.deepEqual(c1.log.recompactWith, ['重点保留'])
  assert.equal(c1.e.csumInstrOpen.value, false)

  const c2 = build({ recompactWith: async () => false })
  c2.e.csumInstrOpen.value = true
  c2.e.csumInstr.value = '再试'
  await c2.e.applyRecompact()
  assert.equal(c2.e.csumInstrOpen.value, true)
})

test('自动提交 watch：整卡可自动提交时只触发一次', async () => {
  const { e, state, log } = build({ askAutoSubmittable: () => true })
  withAsk(state, askCard())
  await nextTick()
  assert.equal(log.askSubmitAll.length, 1)
  assert.equal(log.askSubmitAll[0].role, 'ai')
  await nextTick()
  assert.equal(log.askSubmitAll.length, 1)
})

test('提问卡心跳：有 deadline 启表并对表，撤卡后停表', async () => {
  const { e, state, log } = build()
  withAsk(state, askCard({ deadline: 5000 }))
  await nextTick()
  assert.equal(log.tickers.length, 1)
  assert.equal(log.tickers[0].ms, 1000)
  assert.equal(e.askNow.value, 0)
  state.msgs = []
  await nextTick(); await nextTick()
  assert.deepEqual(log.cleared, [1])
})

test('收尾 watch：开始回答收思考面板、结束解除 Esc', async () => {
  const { state, log } = build()
  state.loading = true
  await nextTick()
  assert.equal(log.hideThinkPanel, 1)
  state.loading = false
  await nextTick()
  assert.equal(log.disarmEsc, 1)
})

test('messages watch：搜索开启且有关键词时重置偏移并重绘高亮', async () => {
  const { state, refs, log } = build()
  state.searchOpen = true
  state.searchQuery = '关键词'
  refs.searchPos.value = 7
  state.msgs = [{ role: 'user', content: '问' }]
  await nextTick(); await nextTick()
  assert.equal(refs.searchPos.value, 0)
  assert.equal(log.paintSearchHighlight, 1)
  assert.equal(log.scrollToMatch, 1)
})

test('messages watch：切会话后编辑卡下标失配即取消编辑', async () => {
  const { state, log } = build()
  state.editingIdx = 0
  state.editingMessageId = 'u-old'
  state.msgs = [{ role: 'user', content: '新会话', messageId: 'u-new' }]
  await nextTick()
  assert.equal(log.cancelEdit, 1)
})

test('初始化：注册全局监听、拉权限预设与 ready、消费 query 预填', async () => {
  const c = build()
  c.ctx.route.query.ask = '来一句'
  await c.e.initialize()
  assert.equal(c.log.windowEvents.length, 4)
  assert.deepEqual(c.log.windowEvents.map(x => x[0]),
                   ['keydown', 'paste', 'resize', 'orientationchange'])
  assert.equal(c.log.docEvents.length, 1)
  assert.equal(c.log.docEvents[0][0], 'pointerdown')
  assert.equal(c.log.refreshSetupGuide, 1)
  assert.equal(c.log.loadPermissionPresets, 1)
  assert.equal(c.log.ready, 1)
  assert.equal(c.refs.text.value, '来一句')
  assert.equal(c.log.focusInput, 1)
  assert.equal(c.log.routerReplace.length, 1)
  assert.equal(c.log.routerReplace[0].query.ask, undefined)
  await nextTick()
  assert.equal(c.log.syncScrollbarGutter, 1)
})

test('初始化：技能/智能体点名与事项关联都经 ctx 注入', async () => {
  const c = build()
  Object.assign(c.ctx.route.query, { skill: 'S', agent: 'a1', itemId: 'i1', itemProjectId: 'p1' })
  await c.e.initialize()
  assert.equal(c.log.loadSkills, 1)
  assert.deepEqual(c.log.toggleSkill, ['S'])
  assert.equal(c.log.loadAgents, 1)
  assert.deepEqual(c.log.pickAgent, ['a1'])
  assert.deepEqual(c.log.updateProjectItem, [['p1', 'i1', { sessionId: 'sid-1' }]])
  assert.equal(c.log.routerReplace.length, 3)
  await nextTick()
  assert.equal(c.log.syncScrollbarGutter, 1)
})
