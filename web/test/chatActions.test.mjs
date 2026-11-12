import test from 'node:test'
import assert from 'node:assert/strict'
import { ref, reactive } from 'vue'
import * as pure from '../src/chat/chatActions.js'
import { useChatActions } from '../src/chat/useChatActions.js'

const makeCtx = (over = {}) => {
  const log = []
  const search = { open: 0, close: 0, focus: 0 }
  const share = { load: 0, enable: [], disable: 0, copy: 0, url: 'https://q/shared/tok' }
  const apiCalls = { addEvalCase: [], createSkill: [], deleteMessageGroup: [], debugRetrieval: [] }
  let msgs = [
    { role: 'user', content: '问题一', messageId: 'u1' },
    { role: 'assistant', content: '回答一', messageId: 'a1', turnId: 't1',
      sources: [{ knowledgeId: 'k1' }, { knowledgeId: 'k1' }, { knowledgeId: 'k2' }] }
  ]
  let text = ''
  const state = {
    loading: false, currentSessionId: 's1', currentSessionTitle: '标题',
    goalModeOn: true, goalArmed: false, sessionGoal: null, goalStarting: false,
    planModeOn: false, foreignWorkspace: false,
    slashOpen: false, histOpen: false, addOpen: false, addTab: 'root', addFromRoot: false, addHi: 0
  }

  const api = {
    addEvalCase: async (q, kids) => {
      apiCalls.addEvalCase.push([q, kids])
      return over.addEvalCaseResult || { success: true, data: { added: true } }
    },
    createSkill: async p => { apiCalls.createSkill.push(p); return { success: true } },
    deleteMessageGroup: async mid => {
      apiCalls.deleteMessageGroup.push(mid)
      return over.deleteMessageGroupResult || { success: true }
    },
    debugRetrieval: async q => {
      apiCalls.debugRetrieval.push(q)
      return over.debugRetrievalResult || { success: true, data: { keywordHits: [{ title: '文档', hitRate: 0.5 }] } }
    },
    submitFeedback: async () => ({ success: true })
  }

  const useChatSearch = () => {
    const searchOpen = ref(false)
    const searchInputRef = ref({ focus: () => { search.focus++ } })
    return {
      searchOpen, searchQuery: ref(''), searchPos: ref(0), searchInputRef,
      matchedIdxs: ref([]), searchPosShown: ref(0),
      openSearch: () => { search.open++; searchOpen.value = true },
      closeSearch: () => { search.close++; searchOpen.value = false },
      gotoMatch: () => {}, paintHighlight: () => {}, scrollToMatch: () => {}
    }
  }
  const useSessionShare = () => ({
    loading: ref(false), info: ref({}), url: ref(share.url),
    load: () => { share.load++ },
    enable: r => { share.enable.push(r) },
    disable: () => { share.disable++ },
    copyLink: () => { share.copy++ }
  })

  const ctx = {
    text: () => text, setText: v => { text = v },
    messages: () => msgs,
    loading: () => state.loading,
    currentSessionId: () => state.currentSessionId,
    currentSessionTitle: () => state.currentSessionTitle,
    goalModeOn: () => state.goalModeOn,
    goalArmed: () => state.goalArmed,
    setGoalArmed: v => { state.goalArmed = v },
    sessionGoal: () => state.sessionGoal,
    goalStarting: () => state.goalStarting,
    planModeOn: () => state.planModeOn,
    foreignWorkspace: () => state.foreignWorkspace,
    slashOpen: () => state.slashOpen,
    histOpen: () => state.histOpen,
    addOpen: () => state.addOpen,
    addTab: () => state.addTab,
    addFromRoot: () => state.addFromRoot,
    addHi: () => state.addHi,
    setPanelTrigger: (pos, ch) => log.push(['panelTrigger', pos, ch]),
    send: opts => log.push(['send', opts]),
    moveSteer: (from, to) => log.push(['moveSteer', from, to]),
    closeAllPanels: () => log.push(['closeAllPanels']),
    closeAddPanel: () => log.push(['closeAddPanel']),
    setPlanMode: v => log.push(['setPlanMode', v]),
    startGoalNow: async task => {
      log.push(['startGoalNow', task])
      return over.goalResult !== undefined ? over.goalResult : { id: 'g1' }
    },
    focusInput: () => log.push(['focusInput']),
    focusPanelOwner: () => log.push(['focusPanelOwner']),
    movePanelHi: d => log.push(['movePanelHi', d]),
    confirmPanelHi: () => (over.confirmPanelHi !== undefined ? over.confirmPanelHi : true),
    confirmAddHi: v => log.push(['confirmAddHi', v]),
    backToRoot: () => log.push(['backToRoot']),
    openMentionPanel: () => log.push(['openMentionPanel']),
    openHistPanel: () => log.push(['openHistPanel']),
    openSlashPanel: () => log.push(['openSlashPanel']),
    composerCaret: () => (text || '').length,
    hitsOverlay: () => (over.overlayHit || false),
    openEvents: (turnId, ai, q) => log.push(['openEvents', turnId, ai, q]),
    loadSkills: () => log.push(['loadSkills']),
    loadSessions: () => log.push(['loadSessions']),
    copyText: (t, msg) => log.push(['copyText', t, msg]),
    exportAnswerMd: payload => log.push(['exportAnswerMd', payload]),
    notify: {
      info: t => log.push(['info', t]),
      warning: t => log.push(['warning', t]),
      success: (t, d) => log.push(['success', t, d]),
      error: t => log.push(['error', t])
    },
    router: { push: arg => log.push(['router.push', arg]) },
    route: { path: '/chat' },
    sessionStore: reactive({ list: [{ id: 's1', shared: true }], shareOpenTick: 0 }),
    api,
    box: ref(null),
    useChatSearch, useSessionShare,
    document: undefined,
    window: { open: (...a) => log.push(['window.open', ...a]) },
    HTMLElement: class {}
  }
  if (over.ctx) Object.assign(ctx, over.ctx)
  if (over.api) Object.assign(api, over.api)

  return {
    ctx, log, search, share, apiCalls, state,
    get msgs () { return msgs },
    setMsgs: v => { msgs = v },
    getText: () => text,
    setText: v => { text = v }
  }
}

const ALL_KEYS = [
  'armGoal', 'inlineSlash', 'sendNow',
  'steerDragIdx', 'steerDragOver', 'onSteerDragStart', 'onSteerDragOver', 'onSteerDragEnd', 'onSteerDrop', 'steerExtras',
  'searchOpen', 'searchQuery', 'searchPos', 'searchInputRef', 'matchedIdxs', 'searchPosShown',
  'openSearch', 'closeSearch', 'gotoMatch', 'paintSearchHighlight', 'scrollToMatch',
  'onSearchHotkey', 'onDocEscKeydown',
  'shareVisible', 'shareLoading', 'shareInfo', 'shareUrl', 'loadShare', 'enableShare', 'stopShare', 'copyShareLink',
  'shareActive', 'openShare', 'consumeShareOpen', 'doEnableShare', 'regenerateShareLink', 'doDisableShare', 'openSharedPage',
  'onPanelNavKeydown', 'onInputKeydown', 'hitsNote',
  'copyAnswer', 'copyUserMessage', 'onMoreAction', 'addToEval',
  'solidifyOpen', 'solidifyBusy', 'solidifyForm', 'gotoSkillsPage', 'solidifySlug', 'openSolidify', 'submitSolidify',
  'exportRound', 'deleteRound',
  'debugVisible', 'debugLoading', 'debugQuestion', 'debugResult', 'openDebug', 'runDebug', 'debugStages'
]

test('纯判据：拖动落点、插话摘要、来源后缀、配对提问、知识块去重', () => {
  assert.deepEqual(pure.steerDropTarget(0, 2), { from: 0, to: 2 })
  assert.equal(pure.steerDropTarget(1, 1), null)
  assert.equal(pure.steerDropTarget(-1, 2), null)
  assert.equal(pure.steerExtrasText({
    images: [1, 2], atts: [1], skills: ['a'], mentions: [1, 2, 3], historyRefs: [1], planMode: true
  }), '2 张图 · +1 个附件 · 技能 ×1 · @ 3 · # 1 · 先出计划')
  assert.equal(pure.steerExtrasText(null), '')
  assert.equal(pure.hitsNoteText({ tokens: { hits: 3 }, retrieved: { refs: 5 } }), '（3 段填入上下文）')
  assert.equal(pure.hitsNoteText({ tokens: { hits: 5 }, retrieved: { refs: 5 } }), '')
  assert.equal(pure.hitsNoteText({ tokens: { hits: 0 }, retrieved: { refs: 5 } }), '')

  const msgs = [
    { role: 'user', content: 'q1' }, { role: 'assistant', content: 'a1' },
    { role: 'user', content: 'q2' }, { role: 'assistant', content: 'a2' }
  ]
  assert.equal(pure.findQuestion(msgs, 3).content, 'q2')
  assert.equal(pure.findQuestion(msgs, 2), null)
  assert.equal(pure.findQuestion(msgs, 2, false).content, 'q1')
  assert.equal(pure.findQuestion(msgs, 0), null)
  assert.deepEqual(pure.evalKnowledgeIds({ sources: [{ knowledgeId: 'k1' }, { knowledgeId: 'k1' }, { knowledgeId: '' }, {}] }), ['k1'])
})

test('纯判据：更多动作分派与分享态', () => {
  const msgs = [{ role: 'user', content: '问题' }, { role: 'assistant', content: '答', turnId: 't9' }]
  assert.deepEqual(pure.moreActionPlan('debug', 1, msgs), { kind: 'debug', mi: 1 })
  assert.deepEqual(pure.moreActionPlan('addEval', 1, msgs), { kind: 'addEval', mi: 1 })
  assert.deepEqual(pure.moreActionPlan('deleteRound', 1, msgs), { kind: 'deleteRound', mi: 1 })
  assert.deepEqual(pure.moreActionPlan('export', 1, msgs), { kind: 'export', mi: 1 })
  const ev = pure.moreActionPlan('events', 1, msgs)
  assert.equal(ev.kind, 'events')
  assert.equal(ev.turnId, 't9')
  assert.equal(ev.ai, msgs[1])
  assert.equal(ev.question, '问题')
  assert.equal(pure.moreActionPlan('unknown', 1, msgs), null)
  assert.equal(pure.isSessionShared([{ id: 's1', shared: true }, { id: 's2', shared: false }], 's1'), true)
  assert.equal(pure.isSessionShared([{ id: 's1', shared: 0 }], 's1'), false)
  assert.equal(pure.isSessionShared([], 's1'), false)
})

test('纯判据：技能名、正文骨架、表单预填与请求载荷', () => {
  assert.equal(pure.solidifyNameValid('  x  '), true)
  assert.equal(pure.solidifyNameValid('   '), false)
  assert.equal(pure.solidifySlug('做一个 调研!'), '做一个-调研')
  assert.equal(pure.solidifySlug('!!!', () => 1000), 'chat-skill-' + (1000).toString(36))
  assert.equal(pure.solidifySlug('汉字abc_12-XYZ'), '汉字abc_12-XYZ')
  assert.equal(pure.solidifySlug('a'.repeat(100)).length, 64)
  assert.ok(pure.buildSolidifyContent('问题').startsWith('## 适用场景\n问题'))
  const seed = pure.seedSolidifyForm('问题', () => 1)
  assert.equal(seed.name, '问题')
  assert.equal(seed.description, '问题')
  assert.ok(seed.content.includes('## 做法'))
  assert.deepEqual(pure.solidifyPayload({ name: ' 名 ', description: ' 描述 ', content: '正文' }),
    { name: '名', description: '描述', content: '正文', category: '通用' })
})

test('纯判据：查找热键与全局 Esc 意图', () => {
  assert.equal(pure.searchHotkeyIntent({ ctrlKey: true, key: 'f' }, false), 'open')
  assert.equal(pure.searchHotkeyIntent({ metaKey: true, key: 'F' }, true), 'focus')
  assert.equal(pure.searchHotkeyIntent({ key: 'Escape' }, true), 'close')
  assert.equal(pure.searchHotkeyIntent({ key: 'Escape' }, false), null)
  assert.equal(pure.searchHotkeyIntent({ key: 'f' }, false), null)

  const base = { panelsOpen: true, overlayHit: () => false, path: [], isHTMLElement: () => false }
  assert.equal(pure.docEscShouldClose({ key: 'Escape' }, base), true)
  assert.equal(pure.docEscShouldClose({ key: 'Escape', isComposing: true }, base), false)
  assert.equal(pure.docEscShouldClose({ key: 'Escape', keyCode: 229 }, base), false)
  assert.equal(pure.docEscShouldClose({ key: 'a' }, base), false)
  assert.equal(pure.docEscShouldClose({ key: 'Escape' }, { ...base, panelsOpen: false }), false)
  assert.equal(pure.docEscShouldClose({ key: 'Escape' }, { ...base, overlayHit: () => true }), false)
  const el = { closest: () => ({}) }
  assert.equal(pure.docEscShouldClose({ key: 'Escape' }, { ...base, path: [el], isHTMLElement: n => n === el }), false)
})

test('纯判据：面板导航意图与触发字符', () => {
  const s = { addOpen: true, addTab: 'root', addFromRoot: false, addHi: 2 }
  assert.equal(pure.panelNavIntent({ isComposing: true, key: 'Enter' }, s), null)
  assert.deepEqual(pure.panelNavIntent({ key: 'ArrowDown' }, s), { kind: 'move', delta: 1 })
  assert.deepEqual(pure.panelNavIntent({ key: 'ArrowUp' }, s), { kind: 'move', delta: -1 })
  assert.deepEqual(pure.panelNavIntent({ key: 'Escape' }, s), { kind: 'close' })
  assert.deepEqual(pure.panelNavIntent({ key: 'Escape' }, { ...s, addTab: 'history', addFromRoot: true }), { kind: 'back' })
  assert.deepEqual(pure.panelNavIntent({ key: 'Tab' }, s), { kind: 'confirmAdd', hi: 2 })
  assert.deepEqual(pure.panelNavIntent({ key: 'Tab' }, { ...s, addTab: 'history' }), { kind: 'back' })
  assert.deepEqual(pure.panelNavIntent({ key: 'Enter', shiftKey: true }, s), null)
  assert.deepEqual(pure.panelNavIntent({ key: 'Enter' }, s), { kind: 'confirmHi' })
  assert.equal(pure.panelNavIntent({ key: 'Tab' }, { ...s, addOpen: false }), null)

  assert.equal(pure.panelTriggerChar({ key: '@' }, ''), '@')
  assert.equal(pure.panelTriggerChar({ key: '#' }, 'abc '), '#')
  assert.equal(pure.panelTriggerChar({ key: '/' }, '前缀（'), '/')
  assert.equal(pure.panelTriggerChar({ key: '@' }, 'abc'), null)
  assert.equal(pure.panelTriggerChar({ key: '@', ctrlKey: true }, ''), null)
  assert.equal(pure.panelTriggerChar({ key: 'a' }, ''), null)
})

test('纯判据：调试阶段投影六段齐全并补标签', () => {
  const d = {
    keywordHits: [{ title: 'K', hitRate: 0.5 }], vectorHits: [{ title: 'V', score: 0.8 }],
    merged: [{ title: 'M', score: 1 }], reranked: [{ title: 'R', score: 2 }],
    rerankApplied: false, rerankSkipReason: '超时',
    finalContext: [{ title: 'F', rerankScore: 3 }], contextGate: { droppedByGate: 2 },
    excluded: [{ title: 'E', score: 0 }]
  }
  const st = pure.projectDebugStages(d)
  assert.equal(st.length, 6)
  assert.match(st[0].name, /关键词命中（1）/)
  assert.equal(st[0].items[0].tag, '命中率 0.5')
  assert.equal(st[1].items[0].tag, '相似度 0.8')
  assert.match(st[3].name, /超时/)
  assert.equal(st[4].items[0].tag, '分 3')
  assert.match(st[4].name, /门拦下 2 块/)
  assert.equal(st[5].items[0].title, 'E')
  assert.equal(st[0].items[0].docName, '')
  assert.deepEqual(pure.projectDebugStages(null), [])
})

test('纯判据：/deep 兜底意图', () => {
  assert.deepEqual(pure.deepCommandIntent({ matched: false, task: '' }, true, false), { matched: false })
  assert.deepEqual(pure.deepCommandIntent({ matched: true, task: '调研' }, true, false),
    { matched: true, kind: 'start', task: '调研' })
  assert.deepEqual(pure.deepCommandIntent({ matched: true, task: '' }, true, false),
    { matched: true, kind: 'toggleArm', armed: true })
  assert.deepEqual(pure.deepCommandIntent({ matched: true, task: '' }, true, true),
    { matched: true, kind: 'toggleArm', armed: false })
  assert.deepEqual(pure.deepCommandIntent({ matched: true, task: 'x' }, false, false),
    { matched: true, kind: 'unsupported' })
})

test('composable：返回区间全部顶层绑定，初始状态正确', () => {
  const c = makeCtx()
  const a = useChatActions(c.ctx)
  assert.equal(Object.keys(a).length, ALL_KEYS.length)
  assert.deepEqual(Object.keys(a).sort(), ALL_KEYS.slice().sort())
  assert.equal(a.steerDragIdx.value, -1)
  assert.equal(a.steerDragOver.value, -1)
  assert.equal(a.shareVisible.value, false)
  assert.equal(a.solidifyOpen.value, false)
  assert.equal(a.solidifyBusy.value, false)
  assert.equal(a.debugVisible.value, false)
  assert.equal(a.debugLoading.value, false)
  assert.equal(a.debugQuestion.value, '')
  assert.equal(a.debugResult.value, null)
  assert.equal(a.searchOpen.value, false)
  assert.equal(a.shareActive.value, true)
  assert.deepEqual(a.debugStages.value, [])
  assert.deepEqual(a.solidifyForm.name, '')
})

test('composable：armGoal 互斥、inlineSlash 解析 /deep、sendNow 拦下深度命令', async () => {
  const c = makeCtx()
  const a = useChatActions(c.ctx)
  c.state.planModeOn = true
  a.armGoal(true)
  assert.equal(c.state.goalArmed, true)
  assert.ok(c.log.some(x => x[0] === 'setPlanMode' && x[1] === false))

  c.setText('/deep 做个调研')
  assert.equal(a.inlineSlash(), true)
  assert.equal(c.getText(), '')
  assert.ok(c.log.some(x => x[0] === 'startGoalNow' && x[1] === '做个调研'))

  c.log.length = 0
  c.setText('/deep 任务2')
  await a.sendNow()
  assert.equal(c.getText(), '')
  assert.ok(c.log.some(x => x[0] === 'startGoalNow' && x[1] === '任务2'))
  assert.ok(c.log.some(x => x[0] === 'closeAllPanels'))

  c.state.goalModeOn = false
  c.setText('/deep x')
  await a.sendNow()
  assert.ok(c.log.some(x => x[0] === 'info' && /没有开启深度执行/.test(x[1])))
})

test('composable：sendNow 目标创建兜底、外部工作区拦截与普通发送', async () => {
  const c = makeCtx()
  const a = useChatActions(c.ctx)
  c.setText('普通问题')
  await a.sendNow()
  assert.ok(c.log.some(x => x[0] === 'closeAddPanel'))
  assert.deepEqual(c.log.find(x => x[0] === 'send'), ['send', {}])

  c.log.length = 0
  await a.sendNow(true)
  assert.deepEqual(c.log.find(x => x[0] === 'send'), ['send', { steerNow: true }])

  c.log.length = 0
  c.state.goalArmed = true
  c.state.planModeOn = true
  c.setText('立目标')
  await a.sendNow()
  assert.equal(c.state.goalArmed, false)
  assert.ok(c.log.some(x => x[0] === 'setPlanMode' && x[1] === false))
  assert.ok(c.log.some(x => x[0] === 'closeAllPanels'))
  assert.ok(c.log.some(x => x[0] === 'startGoalNow'))

  const c2 = makeCtx({ goalResult: null })
  const a2 = useChatActions(c2.ctx)
  c2.state.goalArmed = true
  c2.setText('立目标')
  await a2.sendNow()
  assert.equal(c2.state.goalArmed, true)

  const c3 = makeCtx()
  const a3 = useChatActions(c3.ctx)
  c3.state.goalStarting = true
  c3.setText('x')
  await a3.sendNow()
  assert.equal(c3.log.some(x => x[0] === 'send'), false)

  const c4 = makeCtx()
  const a4 = useChatActions(c4.ctx)
  c4.state.foreignWorkspace = true
  c4.setText('x')
  await a4.sendNow()
  assert.ok(c4.log.some(x => x[0] === 'warning' && /另一台电脑/.test(x[1])))
  assert.equal(c4.log.some(x => x[0] === 'send'), false)
})

test('composable：插话拖动排序落点有效才 moveSteer 并复位', () => {
  const c = makeCtx()
  const a = useChatActions(c.ctx)
  const e = { dataTransfer: { setData: () => {}, effectAllowed: '' } }
  a.onSteerDragStart(0, e)
  assert.equal(a.steerDragIdx.value, 0)
  a.onSteerDragOver(2)
  assert.equal(a.steerDragOver.value, 2)
  a.onSteerDrop()
  assert.deepEqual(c.log.find(x => x[0] === 'moveSteer'), ['moveSteer', 0, 2])
  assert.equal(a.steerDragIdx.value, -1)
  assert.equal(a.steerDragOver.value, -1)

  c.log.length = 0
  a.onSteerDragStart(1, e)
  a.onSteerDragOver(1)
  a.onSteerDrop()
  assert.equal(c.log.some(x => x[0] === 'moveSteer'), false)

  assert.equal(a.steerExtras({ images: [1, 2] }), '2 张图')
  assert.equal(a.hitsNote({ tokens: { hits: 2 }, retrieved: { refs: 3 } }), '（2 段填入上下文）')
})

test('composable：Ctrl/⌘+F 开/聚焦查找，Esc 关闭', () => {
  const c = makeCtx()
  const a = useChatActions(c.ctx)
  let prevented = 0
  const p = () => { prevented++ }
  a.onSearchHotkey({ ctrlKey: true, key: 'f', preventDefault: p })
  assert.equal(c.search.open, 1)
  assert.equal(a.searchOpen.value, true)
  a.onSearchHotkey({ metaKey: true, key: 'F', preventDefault: p })
  assert.equal(c.search.focus, 1)
  a.onSearchHotkey({ key: 'Escape', preventDefault: p })
  assert.equal(c.search.close, 1)
  assert.equal(a.searchOpen.value, false)
  assert.equal(prevented, 2)
  a.onSearchHotkey({ key: 'a', preventDefault: p })
  assert.equal(prevented, 2)
})

test('composable：全局 Esc 兜底在面板开着且焦点不在弹窗/面板内时收掉', () => {
  const c = makeCtx()
  const a = useChatActions(c.ctx)
  let prevented = 0
  const p = () => { prevented++ }
  const ev = extra => ({ key: 'Escape', composedPath: () => [], preventDefault: p, ...extra })
  a.onDocEscKeydown(ev())
  assert.equal(c.log.some(x => x[0] === 'closeAllPanels'), false)
  c.state.slashOpen = true
  a.onDocEscKeydown(ev())
  assert.ok(c.log.some(x => x[0] === 'closeAllPanels'))
  assert.ok(c.log.some(x => x[0] === 'focusInput'))
  assert.equal(prevented, 1)

  c.log.length = 0
  a.onDocEscKeydown(ev({ isComposing: true }))
  assert.equal(c.log.some(x => x[0] === 'closeAllPanels'), false)
  a.onDocEscKeydown(ev({ key: 'a' }))
  assert.equal(c.log.some(x => x[0] === 'closeAllPanels'), false)

  const c2 = makeCtx({ overlayHit: true })
  const a2 = useChatActions(c2.ctx)
  c2.state.slashOpen = true
  a2.onDocEscKeydown(ev())
  assert.equal(c2.log.some(x => x[0] === 'closeAllPanels'), false)

  const el = { closest: () => ({}) }
  const c3 = makeCtx({ ctx: { HTMLElement: Object } })
  const a3 = useChatActions(c3.ctx)
  c3.state.histOpen = true
  a3.onDocEscKeydown({ key: 'Escape', composedPath: () => [el], preventDefault: p })
  assert.equal(c3.log.some(x => x[0] === 'closeAllPanels'), false)
})

test('composable：面板导航与输入框键盘意图', () => {
  const c = makeCtx()
  const a = useChatActions(c.ctx)
  let prevented = 0
  const p = () => { prevented++ }
  const ev = x => ({ preventDefault: p, ...x })

  assert.equal(a.onPanelNavKeydown(ev({ key: 'ArrowDown' })), true)
  assert.deepEqual(c.log.find(x => x[0] === 'movePanelHi'), ['movePanelHi', 1])

  c.state.addOpen = true
  c.state.addTab = 'history'
  c.state.addFromRoot = true
  assert.equal(a.onPanelNavKeydown(ev({ key: 'Escape' })), true)
  assert.ok(c.log.some(x => x[0] === 'backToRoot'))

  c.state.addTab = 'root'
  c.state.addHi = 3
  assert.equal(a.onPanelNavKeydown(ev({ key: 'Tab' })), true)
  assert.deepEqual(c.log.find(x => x[0] === 'confirmAddHi'), ['confirmAddHi', 3])
  assert.equal(a.onPanelNavKeydown(ev({ key: 'Enter' })), true)

  const c2 = makeCtx({ confirmPanelHi: false })
  const a2 = useChatActions(c2.ctx)
  c2.log.length = 0
  a2.onInputKeydown(ev({ key: 'Enter' }))
  assert.deepEqual(c2.log.find(x => x[0] === 'send'), ['send', {}])

  c2.state.loading = true
  c2.log.length = 0
  a2.onInputKeydown(ev({ key: 'Enter', ctrlKey: true }))
  assert.deepEqual(c2.log.find(x => x[0] === 'send'), ['send', { steerNow: true }])

  c2.state.loading = false
  c2.setText('')
  c2.log.length = 0
  a2.onInputKeydown(ev({ key: '@' }))
  assert.ok(c2.log.some(x => x[0] === 'panelTrigger'))
  assert.ok(c2.log.some(x => x[0] === 'openMentionPanel'))

  c2.setText('abc')
  c2.log.length = 0
  a2.onInputKeydown(ev({ key: '@' }))
  assert.equal(c2.log.some(x => x[0] === 'openMentionPanel'), false)

  c2.setText('')
  c2.log.length = 0
  a2.onInputKeydown(ev({ key: '/' }))
  assert.ok(c2.log.some(x => x[0] === 'openSlashPanel'))
})

test('composable：注入 onPanelNavKeydown 时 onInputKeydown 走唯一实现', () => {
  const c = makeCtx({ ctx: { onPanelNavKeydown: () => true } })
  const a = useChatActions(c.ctx)
  c.state.addOpen = true
  let prevented = 0
  a.onInputKeydown({ key: 'Enter', preventDefault: () => { prevented++ } })
  // 注入版返回 true（已消费），不应再落到 sendNow
  assert.equal(c.log.some(x => x[0] === 'send'), false)
  assert.equal(prevented, 0)
})

test('composable：分享态、开关弹窗、换链与预览', async () => {
  const c = makeCtx()
  const a = useChatActions(c.ctx)
  assert.equal(a.shareActive.value, true)
  c.ctx.sessionStore.list = [{ id: 's1', shared: false }]
  assert.equal(a.shareActive.value, false)
  c.ctx.sessionStore.list = [{ id: 's1', shared: true }]

  await a.openShare()
  assert.equal(a.shareVisible.value, true)
  assert.equal(c.share.load, 1)

  await a.consumeShareOpen()
  assert.equal(c.share.load, 2)

  a.doEnableShare()
  a.regenerateShareLink()
  a.doDisableShare()
  assert.deepEqual(c.share.enable, [false, true])
  assert.equal(c.share.disable, 1)

  a.openSharedPage()
  assert.ok(c.log.some(x => x[0] === 'window.open' && x[1] === 'https://q/shared/tok'))

  const c2 = makeCtx()
  const a2 = useChatActions(c2.ctx)
  c2.setMsgs([])
  await a2.openShare()
  assert.equal(a2.shareVisible.value, false)
  assert.ok(c2.log.some(x => x[0] === 'info' && /没有内容可分享/.test(x[1])))
})

test('composable：复制、更多动作分派与加入评测集', async () => {
  const c = makeCtx()
  const a = useChatActions(c.ctx)
  await a.copyAnswer(1)
  assert.deepEqual(c.log.find(x => x[0] === 'copyText'), ['copyText', '回答一', undefined])
  c.log.length = 0
  a.copyUserMessage({ content: '  问题  ' })
  assert.deepEqual(c.log.find(x => x[0] === 'copyText'), ['copyText', '问题', '已复制'])
  c.log.length = 0
  a.copyUserMessage({ content: '  ' })
  assert.ok(c.log.some(x => x[0] === 'warning'))

  c.log.length = 0
  a.onMoreAction('events', 1)
  const ev = c.log.find(x => x[0] === 'openEvents')
  assert.equal(ev[1], 't1')
  assert.equal(ev[3], '问题一')

  c.log.length = 0
  a.onMoreAction('export', 1)
  assert.ok(c.log.some(x => x[0] === 'exportAnswerMd'))

  await a.addToEval(1)
  assert.equal(c.apiCalls.addEvalCase.length, 1)
  assert.equal(c.apiCalls.addEvalCase[0][0], '问题一')
  assert.deepEqual(c.apiCalls.addEvalCase[0][1], ['k1', 'k2'])
  assert.ok(c.log.some(x => x[0] === 'success' && x[1] === '已加入评测集'))

  const c2 = makeCtx({ addEvalCaseResult: { success: true, data: { added: false, reason: '已存在' } } })
  const a2 = useChatActions(c2.ctx)
  await a2.addToEval(1)
  assert.ok(c2.log.some(x => x[0] === 'info' && x[1] === '已存在'))

  const c3 = makeCtx()
  const a3 = useChatActions(c3.ctx)
  c3.setMsgs([{ role: 'user', content: 'q' }, { role: 'assistant', content: 'a' }])
  await a3.addToEval(1)
  assert.ok(c3.log.some(x => x[0] === 'warning' && /没有可固化/.test(x[1])))
})

test('composable：固化技能表单预填、提交与入口', async () => {
  const c = makeCtx()
  const a = useChatActions(c.ctx)
  a.openSolidify(1)
  assert.equal(a.solidifyOpen.value, true)
  assert.equal(a.solidifyForm.name, '问题一')
  assert.equal(a.solidifyForm.description, '问题一')
  assert.ok(a.solidifyForm.content.startsWith('## 适用场景\n问题一'))

  await a.submitSolidify()
  assert.equal(c.apiCalls.createSkill.length, 1)
  assert.equal(c.apiCalls.createSkill[0].name, '问题一')
  assert.equal(c.apiCalls.createSkill[0].category, '通用')
  assert.equal(a.solidifyOpen.value, false)
  assert.ok(c.log.some(x => x[0] === 'loadSkills'))
  assert.ok(c.log.some(x => x[0] === 'success'))
  a.gotoSkillsPage()
  assert.ok(c.log.some(x => x[0] === 'router.push'))

  const c2 = makeCtx()
  const a2 = useChatActions(c2.ctx)
  a2.solidifyForm.name = '   '
  await a2.submitSolidify()
  assert.equal(c2.apiCalls.createSkill.length, 0)
  assert.ok(c2.log.some(x => x[0] === 'warning' && /起个名字/.test(x[1])))

  const c3 = makeCtx()
  const a3 = useChatActions(c3.ctx)
  c3.setMsgs([{ role: 'user', content: 'q' }, { role: 'assistant', content: '' }])
  a3.openSolidify(1)
  assert.equal(a3.solidifyOpen.value, false)
  assert.ok(c3.log.some(x => x[0] === 'warning'))
})

test('composable：删除本轮移除回答与其前的问题并刷新会话', async () => {
  const c = makeCtx()
  const a = useChatActions(c.ctx)
  await a.deleteRound(1)
  assert.deepEqual(c.apiCalls.deleteMessageGroup, ['a1'])
  assert.equal(c.msgs.length, 0)
  assert.ok(c.log.some(x => x[0] === 'success' && x[1] === '已删除本轮对话'))
  assert.ok(c.log.some(x => x[0] === 'loadSessions'))

  const c2 = makeCtx({ deleteMessageGroupResult: { success: false, msg: '无权' } })
  const a2 = useChatActions(c2.ctx)
  await a2.deleteRound(1)
  assert.equal(c2.msgs.length, 2)
  assert.ok(c2.log.some(x => x[0] === 'error' && x[1] === '无权'))

  const c3 = makeCtx()
  const a3 = useChatActions(c3.ctx)
  c3.setMsgs([{ role: 'user', content: 'q' }, { role: 'assistant', content: 'a' }])
  await a3.deleteRound(1)
  assert.ok(c3.log.some(x => x[0] === 'warning' && /不可删除/.test(x[1])))
  a3.exportRound(1)
  const ex = c3.log.find(x => x[0] === 'exportAnswerMd')
  assert.equal(ex[1].title, '标题')
  assert.equal(ex[1].question.content, 'q')
})

test('composable：调试面板拉取检索结果并投影阶段', async () => {
  const c = makeCtx()
  const a = useChatActions(c.ctx)
  a.debugQuestion.value = '调试问题'
  await a.runDebug()
  assert.deepEqual(c.apiCalls.debugRetrieval, ['调试问题'])
  assert.equal(a.debugLoading.value, false)
  assert.ok(a.debugResult.value)
  assert.equal(a.debugStages.value.length, 6)
  assert.match(a.debugStages.value[0].name, /关键词命中/)

  c.log.length = 0
  a.debugQuestion.value = '   '
  await a.runDebug()
  assert.ok(c.log.some(x => x[0] === 'warning' && /请输入问题/.test(x[1])))

  const c2 = makeCtx()
  const a2 = useChatActions(c2.ctx)
  a2.openDebug(1)
  assert.equal(a2.debugVisible.value, true)
  assert.equal(a2.debugQuestion.value, '问题一')
  await new Promise(r => setTimeout(r, 0))
  assert.equal(c2.apiCalls.debugRetrieval.length, 1)
  assert.equal(c2.apiCalls.debugRetrieval[0], '问题一')
})
