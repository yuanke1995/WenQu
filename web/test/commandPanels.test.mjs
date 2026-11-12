import test from 'node:test'
import assert from 'node:assert/strict'
import { ref, nextTick } from 'vue'
import * as P from '../src/chat/commandPanels.js'
import { useCommandPanels } from '../src/chat/useCommandPanels.js'

const icons = {
  CompressOutlined: 'CompressOutlined', AimOutlined: 'AimOutlined',
  FileTextOutlined: 'FileTextOutlined', PlusOutlined: 'PlusOutlined',
  PaperClipOutlined: 'PaperClipOutlined', BookOutlined: 'BookOutlined',
  HistoryOutlined: 'HistoryOutlined', ThunderboltOutlined: 'ThunderboltOutlined',
  DatabaseOutlined: 'DatabaseOutlined', RobotOutlined: 'RobotOutlined',
  AppstoreOutlined: 'AppstoreOutlined'
}

const NAMES = [
  'panelTriggerPos', 'panelTriggerCh', 'panelAnyOpen', 'closeAllPanels', 'panelListSize',
  'getPanelHi', 'setPanelHi', 'slashListEl', 'histListEl', 'addListEl', 'panelListEl',
  'scrollPanelHiIntoView', 'movePanelHi', 'confirmPanelHi', 'stripTriggerToken',
  'composerCaret', 'focusPanelOwner', 'syncPanelQuery', 'onPanelNavKeydown',
  'slashOpen', 'slashQuery', 'slashHi', 'slashCommands', 'openSlashPanel', 'closeSlashPanel',
  'slashQueryNorm', 'slashAliasesOf', 'slashArgPending', 'slashArgsOf', 'slashFiltered', 'runSlashCommand',
  'histOpen', 'histQuery', 'histHi', 'histQueryNorm', 'histListOf', 'histCandidates',
  'histChipLabel', 'pickHistoryRefByClick', 'openHistPanel', 'closeHistPanel',
  'addOpen', 'addTab', 'addHi', 'addSearchEl', 'addHistQuery', 'addCmdQuery', 'addTarget', 'addOnEdit',
  'EDIT_ADD_MOUNT', 'addStore', 'addRootRows', 'addHistPool', 'addHistCandidates',
  'isAddSkillOn', 'toggleAddSkill', 'isAddMentioned', 'toggleAddMention',
  'isAddHistPicked', 'toggleAddHistoryRef', 'ADD_TITLES', 'REF_SUB_TABS',
  'addSearchable', 'addQuery', 'addSearchHint', 'addFootHint', 'addSkillList', 'addCmdList',
  'addRefList', 'addListSize', 'closeAddPanel', 'ensureRefCandidates', 'focusAddSearch',
  'addFromRoot', 'backToRoot', 'switchAddTab', 'switchRefTab', 'confirmRefRow', 'confirmAddHi',
  'openAddPanel', 'toggleAddPanel', 'toggleEditAddPanel', 'runCmdFromAddPanel'
]

const makeCtx = (over = {}) => {
  const calls = {
    runs: [], infos: [], warnings: [], loads: [], toggleHist: [],
    focusInput: 0, replaceRange: [], focus: [], caret: [], editFocus: 0, defer: 0
  }
  const composer = {
    _caret: 0,
    caretIndex() { return this._caret },
    replaceRange(from, to, txt) { calls.replaceRange.push([from, to, txt]) },
    focus() { calls.focus.push(1) },
    setCaret(i) { calls.caret.push(i) }
  }
  const editing = { focus() { calls.editFocus++ } }
  const refs = {
    text: ref(''), loading: ref(false), goalArmed: ref(false), goalEntryVisible: ref(false),
    sid: ref('s1'), title: ref('标题'), skills: ref([]), agents: ref([]),
    kbs: ref([]), docs: ref([]), agentFs: ref([]), kbFs: ref([]), docFs: ref([]),
    editingIdx: ref(null),
    composerRef: ref(composer), editingRef: ref(editing),
    mentionQuery: ref(''), mentionTab: ref('kb'),
    histPool: ref([]), pendingFiles: ref([]), pendingImages: ref([]),
    pendingMentions: ref([]), pickedSkills: ref([]), pendingHistoryRefs: ref([]),
    editAtts: ref([]), editImgs: ref([]), editMentions: ref([]), editSkills: ref([]),
    editHistPool: ref([]), editHistRefs: ref([])
  }
  const ctx = {
    // 值 getter
    text: () => refs.text.value,
    loading: () => refs.loading.value,
    goalArmed: () => refs.goalArmed.value,
    goalEntryVisible: () => refs.goalEntryVisible.value,
    currentSessionId: () => refs.sid.value,
    currentSessionTitle: () => refs.title.value,
    skillList: () => refs.skills.value,
    agentList: () => refs.agents.value,
    mentionKbs: () => refs.kbs.value,
    mentionDocs: () => refs.docs.value,
    mentionAgents: () => refs.agents.value,
    mentionKbFiltered: () => refs.kbFs.value,
    mentionDocFiltered: () => refs.docFs.value,
    mentionAgentFiltered: () => refs.agentFs.value,
    editingIdx: () => refs.editingIdx.value,
    // ref 本身
    composerRef: refs.composerRef,
    editingRef: refs.editingRef,
    mentionQuery: refs.mentionQuery,
    mentionTab: refs.mentionTab,
    histPool: refs.histPool,
    pendingFiles: refs.pendingFiles,
    pendingImages: refs.pendingImages,
    pendingMentions: refs.pendingMentions,
    pickedSkills: refs.pickedSkills,
    pendingHistoryRefs: refs.pendingHistoryRefs,
    editAtts: refs.editAtts,
    editImgs: refs.editImgs,
    editMentions: refs.editMentions,
    editSkills: refs.editSkills,
    editHistPool: refs.editHistPool,
    editHistRefs: refs.editHistRefs,
    // 动作
    toggleHistoryRef: m => calls.toggleHist.push(m),
    focusInput: () => { calls.focusInput++ },
    compactContext: () => calls.runs.push('compact'),
    startGoalNow: t => calls.runs.push(['goal', t]),
    armGoal: v => calls.runs.push(['arm', v]),
    exportSessionMarkdown: (id, t) => calls.runs.push(['export', id, t]),
    createNewSession: () => calls.runs.push('new'),
    pickEditFiles: () => calls.runs.push('pickEditFiles'),
    toggleEditSkill: n => calls.runs.push(['editSkill', n]),
    toggleEditMention: (t, it) => calls.runs.push(['editMention', t, it]),
    toggleEditHistoryRef: m => calls.runs.push(['editHist', m]),
    pickAttachments: () => calls.runs.push('pickAttachments'),
    toggleSkill: n => calls.runs.push(['skill', n]),
    toggleMention: (t, it) => calls.runs.push(['mention', t, it]),
    isMentioned: (t, id) => refs.pendingMentions.value.some(m => m.type === t && m.id === id),
    isHistPicked: id => refs.pendingHistoryRefs.value.some(h => h.messageId === id),
    loadMentionCandidates: () => calls.loads.push('mention'),
    loadAgents: () => calls.loads.push('agents'),
    loadSkills: () => calls.loads.push('skills'),
    // 值 / DOM 全局
    notify: { info: t => calls.infos.push(t), warning: t => calls.warnings.push(t) },
    icons,
    nextTick: fn => fn(),
    setTimeout: fn => { calls.defer++; fn() },
    ...over
  }
  return { ctx, refs, calls, composer, editing }
}

const ev = (key, extra = {}) => {
  let prevented = 0
  const e = { key, preventDefault: () => { prevented++ }, ...extra }
  return { e, get prevented() { return prevented } }
}

// ==================== 纯判据 ====================

test('别名与参数：统一小写、别名切片保留原文', () => {
  assert.deepEqual(P.slashAliasesOf({ cmd: 'Compact', aliases: ['压缩', null] }), ['compact', '压缩'])
  assert.deepEqual(P.slashAliasesOf({ aliases: ['深度'] }), ['深度'])
  assert.equal(P.slashArgsOf({ cmd: 'deep', aliases: ['深度'] }, 'Deep  写个 Plan '), '写个 Plan')
  assert.equal(P.slashArgsOf({ cmd: 'compact' }, 'compact'), '')
  assert.equal(P.slashArgsOf({ cmd: 'compact' }, ''), '')
})

test('带参判定：别名+空格才认，普通空白/非 args 命令不认', () => {
  const cmds = [{ cmd: 'deep', aliases: ['深度'], args: true }, { cmd: 'compact' }]
  assert.equal(P.slashArgPending('deep 一句任务', cmds), true)
  assert.equal(P.slashArgPending('深度 一句任务', cmds), true)
  assert.equal(P.slashArgPending('dee', cmds), false)
  assert.equal(P.slashArgPending('compact 一句', cmds), false)
  assert.equal(P.slashArgPending('随便 话', cmds), false)
})

test('/ 命令过滤：别名前缀、别名+参数、名称/描述包含，空词返回全部', () => {
  const cmds = [
    { cmd: 'compact', name: '压缩上下文', desc: '腾出空间' },
    { cmd: 'deep', aliases: ['深度'], args: true, name: '深度执行', desc: '跨轮' }
  ]
  assert.equal(P.filterSlashCommands(cmds, 'com').length, 1)
  assert.equal(P.filterSlashCommands(cmds, 'com')[0].cmd, 'compact')
  assert.equal(P.filterSlashCommands(cmds, 'deep 写').length, 1)
  assert.equal(P.filterSlashCommands(cmds, '压缩').length, 1)
  assert.equal(P.filterSlashCommands(cmds, '腾出').length, 1)
  assert.equal(P.filterSlashCommands(cmds, '').length, 2)
  assert.equal(P.filterSlashCommands(cmds, 'zzz').length, 0)
})

test('命令页/技能页过滤：名称描述别名合并匹配，技能按 name+description', () => {
  const cmds = [{ cmd: 'compact', name: '压缩上下文', desc: '腾空间' }, { cmd: 'deep', name: '深度执行', desc: '' }]
  assert.equal(P.filterAddCommands(cmds, 'compact').length, 1)
  assert.equal(P.filterAddCommands(cmds, '腾空间').length, 1)
  assert.equal(P.filterAddCommands(cmds, 'zz').length, 0)
  assert.equal(P.filterAddCommands(cmds, '').length, 2)
  const skills = [{ name: '联网', description: '搜索网页' }, { name: '画图', description: '' }]
  assert.equal(P.filterSkills(skills, '网页').length, 1)
  assert.equal(P.filterSkills(skills, '画图').length, 1)
  assert.equal(P.filterSkills(skills, '').length, 2)
})

test('历史候选：内容筛选、最近在前、最多 60 条', () => {
  const pool = Array.from({ length: 80 }, (_, i) =>
    ({ messageId: 'h' + i, content: i === 5 ? '关键词' : '常规' + i }))
  const all = P.histListOf('', pool)
  assert.equal(all.length, 60)
  assert.equal(all[0].messageId, 'h79')
  assert.equal(all[59].messageId, 'h20')
  assert.deepEqual(P.histListOf('关键词', pool).map(m => m.messageId), ['h5'])
  assert.equal(P.histChipLabel({ digest: '摘要' }), '摘要')
  assert.equal(P.histChipLabel({}), '')
})

test('高亮循环与摘除区间', () => {
  assert.equal(P.moveHi(0, 1, 3), 1)
  assert.equal(P.moveHi(0, -1, 3), 2)
  assert.equal(P.moveHi(2, 1, 3), 0)
  assert.equal(P.moveHi(4, 1, 0), 4)
  assert.deepEqual(P.triggerStripRange(3, 8), [3, 8])
  assert.deepEqual(P.triggerStripRange(3, 3), [3, 4])
  assert.deepEqual(P.triggerStripRange(3, 1), [3, 4])
})

test('输入同步判据：光标/触发符/空白收起，带参命令放行空白', () => {
  const cmds = [{ cmd: 'deep', args: true }]
  assert.deepEqual(P.panelQueryIntent({ text: '/com', pos: 0, ch: '/', caret: 4 }), { close: false, q: 'com' })
  assert.deepEqual(P.panelQueryIntent({ text: '/com', pos: 0, ch: '/', caret: 0 }), { close: true, q: '' })
  assert.deepEqual(P.panelQueryIntent({ text: 'xcom', pos: 0, ch: '/', caret: 4 }), { close: true, q: '' })
  assert.deepEqual(P.panelQueryIntent({ text: '/a b', pos: 0, ch: '/', caret: 4 }), { close: true, q: 'a b' })
  assert.deepEqual(P.panelQueryIntent({ text: '/deep 任务', pos: 0, ch: '/', caret: 8,
    slashOpen: true, commands: cmds }), { close: false, q: 'deep 任务' })
  assert.deepEqual(P.panelQueryIntent({ text: '/deep 任务', pos: 0, ch: '/', caret: 8,
    slashOpen: false, commands: cmds }), { close: true, q: 'deep 任务' })
})

test('提示文案与列表投影', () => {
  assert.equal(P.addSearchHintOf('ref'), '搜索知识库、文档、智能体或技能')
  assert.equal(P.addSearchHintOf('root'), '')
  assert.match(P.addFootHintOf('root', false, 'kb'), /↑↓ 选择/)
  assert.match(P.addFootHintOf('root', true, 'kb'), /粘贴/)
  assert.match(P.addFootHintOf('ref', false, 'skill'), /下一轮自动取消/)
  assert.match(P.addFootHintOf('ref', true, 'skill'), /一次最多 3 个/)
  assert.match(P.addFootHintOf('hist', false, 'kb'), /最多 10 条/)
  assert.match(P.addFootHintOf('cmd', false, 'kb'), /命令立即执行/)
  const lists = { kb: ['k'], doc: ['d'], agent: ['a'], skill: ['s'] }
  assert.deepEqual(P.addRefListOf('agent', lists), ['a'])
  assert.deepEqual(P.addRefListOf('doc', lists), ['d'])
  assert.equal(P.addListSizeOf('ref', { ref: 4 }), 4)
  assert.equal(P.addListSizeOf('cmd', {}), 0)
})

test('键盘意图：输入法放行、方向/Esc/Tab/Enter 分派', () => {
  const I = P.panelKeydownIntent
  assert.equal(I({ isComposing: true, key: 'ArrowDown' }), null)
  assert.equal(I({ keyCode: 229, key: 'Enter' }), null)
  assert.equal(I({ key: 'ArrowDown' }), 'down')
  assert.equal(I({ key: 'ArrowUp' }), 'up')
  assert.equal(I({ key: 'Escape' }), 'close')
  assert.equal(I({ key: 'Escape' }, { addOpen: true, addTab: 'ref', addFromRoot: true }), 'back')
  assert.equal(I({ key: 'Escape' }, { addOpen: true, addTab: 'ref', addFromRoot: false }), 'close')
  assert.equal(I({ key: 'Tab' }, { addOpen: true, addTab: 'root' }), 'tab-root')
  assert.equal(I({ key: 'Tab' }, { addOpen: true, addTab: 'ref' }), 'tab-back')
  assert.equal(I({ key: 'Tab' }, { addOpen: false }), null)
  assert.equal(I({ key: 'Enter' }), 'enter')
  assert.equal(I({ key: 'Enter', shiftKey: true }), null)
  assert.equal(I({ key: 'a' }), null)
})

// ==================== composable ====================

test('返回区间全部绑定，初始态干净', () => {
  const { ctx } = makeCtx()
  const p = useCommandPanels(ctx)
  for (const n of NAMES) assert.ok(n in p, '缺绑定 ' + n)
  assert.equal(p.slashOpen.value, false)
  assert.equal(p.histOpen.value, false)
  assert.equal(p.addOpen.value, false)
  assert.equal(p.addTab.value, 'root')
  assert.equal(p.addTarget.value, 'main')
  assert.equal(p.panelTriggerPos.value, -1)
  assert.equal(p.panelTriggerCh.value, '')
  assert.equal(p.panelAnyOpen(), false)
  assert.equal(p.panelListSize(), 0)
  assert.equal(p.addQuery.value, '')
  assert.equal(p.addListSize.value, 4)
  assert.equal(p.addRootRows.value.length, 4)
  assert.equal(p.slashCommands.value.length, 3)
  assert.equal(p.EDIT_ADD_MOUNT, '.msg-inline-edit .add-mount')
})

test('开面板互斥且不抹触发位置；closeAllPanels 清触发态', () => {
  const { ctx } = makeCtx()
  const p = useCommandPanels(ctx)
  p.panelTriggerPos.value = 3
  p.panelTriggerCh.value = '/'
  p.openSlashPanel()
  assert.equal(p.slashOpen.value, true)
  assert.equal(p.histOpen.value, false)
  assert.equal(p.addOpen.value, false)
  assert.equal(p.panelTriggerPos.value, 3)
  assert.equal(p.panelTriggerCh.value, '/')
  p.panelTriggerCh.value = '#'
  p.openHistPanel()
  assert.equal(p.slashOpen.value, false)
  assert.equal(p.histOpen.value, true)
  p.closeAllPanels()
  assert.equal(p.panelAnyOpen(), false)
  assert.equal(p.panelTriggerPos.value, -1)
  assert.equal(p.panelTriggerCh.value, '')
})

test('高亮导航：按当前面板取 ref、循环移动、筛空时不越界', () => {
  const { ctx } = makeCtx()
  const p = useCommandPanels(ctx)
  p.openSlashPanel()
  assert.equal(p.panelListSize(), p.slashFiltered.value.length)
  assert.equal(p.getPanelHi(), p.slashHi)
  p.setPanelHi(2)
  assert.equal(p.slashHi.value, 2)
  p.movePanelHi(1)
  assert.equal(p.slashHi.value, 0)
  p.movePanelHi(-1)
  assert.equal(p.slashHi.value, 2)
  p.slashQuery.value = 'zzzz'
  assert.equal(p.panelListSize(), 0)
  p.movePanelHi(1)
  assert.equal(p.slashHi.value, 2)
})

test('高亮滚回可视区：只改列表自身 scrollTop', () => {
  const { ctx } = makeCtx()
  const p = useCommandPanels(ctx)
  const item = { getBoundingClientRect: () => ({ top: -10, bottom: 0 }) }
  const list = { scrollTop: 50, querySelector: () => item,
    getBoundingClientRect: () => ({ top: 0, bottom: 100 }) }
  p.slashOpen.value = true
  p.slashListEl.value = list
  p.scrollPanelHiIntoView()
  assert.equal(list.scrollTop, 40)
  const item2 = { getBoundingClientRect: () => ({ top: 90, bottom: 150 }) }
  const list2 = { scrollTop: 0, querySelector: () => item2,
    getBoundingClientRect: () => ({ top: 0, bottom: 100 }) }
  p.slashOpen.value = false
  p.histOpen.value = true
  p.histListEl.value = list2
  p.scrollPanelHiIntoView()
  assert.equal(list2.scrollTop, 50)
})

test('触发片段摘除：replaceRange/focus/setCaret 落位并清触发态', () => {
  const { ctx, refs, calls, composer } = makeCtx()
  refs.text.value = '/comX'
  composer._caret = 5
  const p = useCommandPanels(ctx)
  p.panelTriggerPos.value = 0
  p.panelTriggerCh.value = '/'
  p.stripTriggerToken()
  assert.deepEqual(calls.replaceRange[0], [0, 5, ''])
  assert.equal(calls.caret[0], 0)
  assert.ok(calls.focus.length >= 1)
  assert.equal(p.panelTriggerPos.value, -1)
  assert.equal(p.panelTriggerCh.value, '')
  refs.composerRef.value = null
  assert.equal(p.composerCaret(), 5)
})

test('同步筛选词：写到 / # @ 对应面板', () => {
  const { ctx, refs, composer } = makeCtx()
  const p = useCommandPanels(ctx)
  p.panelTriggerPos.value = 0
  p.panelTriggerCh.value = '/'
  p.openSlashPanel()
  refs.text.value = '/com'
  composer._caret = 4
  p.syncPanelQuery()
  assert.equal(p.slashQuery.value, 'com')
  assert.equal(p.slashHi.value, 0)

  p.panelTriggerPos.value = 0
  p.panelTriggerCh.value = '#'
  p.openHistPanel()
  refs.text.value = '#abc'
  composer._caret = 4
  p.syncPanelQuery()
  assert.equal(p.histQuery.value, 'abc')

  p.panelTriggerPos.value = 0
  p.panelTriggerCh.value = '@'
  p.slashOpen.value = false
  p.histOpen.value = false
  p.addOpen.value = true
  p.addTab.value = 'ref'
  refs.text.value = '@知识'
  composer._caret = 3
  p.syncPanelQuery()
  assert.equal(ctx.mentionQuery.value, '知识')
})

test('同步筛选词：光标离开/触发符改写/空白收起面板', () => {
  const { ctx, refs, composer } = makeCtx()
  const p = useCommandPanels(ctx)
  p.panelTriggerPos.value = 0
  p.panelTriggerCh.value = '@'
  p.addOpen.value = true
  p.addTab.value = 'ref'
  refs.text.value = '@a b'
  composer._caret = 4
  p.syncPanelQuery()
  assert.equal(p.addOpen.value, false)

  p.panelTriggerPos.value = 2
  p.panelTriggerCh.value = '/'
  p.slashOpen.value = true
  refs.text.value = 'ab/x'
  composer._caret = 1
  p.syncPanelQuery()
  assert.equal(p.slashOpen.value, false)
})

test('命令执行：生成中被闸挡（noGate 例外）、参数先取、摘片段、执行 run', () => {
  const { ctx, refs, calls, composer } = makeCtx()
  const p = useCommandPanels(ctx)
  const c = { cmd: 'compact', run: args => calls.runs.push(['run', args]) }
  p.panelTriggerPos.value = 0
  p.panelTriggerCh.value = '/'
  refs.text.value = '/compact'
  composer._caret = 8
  refs.loading.value = true
  p.runSlashCommand(c)
  assert.equal(calls.runs.length, 0)
  assert.equal(calls.infos[0], '生成中，请等这轮回答结束后再执行')
  assert.equal(p.panelTriggerPos.value, -1)

  refs.loading.value = false
  p.panelTriggerPos.value = 0
  p.panelTriggerCh.value = '/'
  p.slashQuery.value = 'deep 写个任务'
  const c2 = { cmd: 'deep', run: args => calls.runs.push(['run', args]) }
  p.runSlashCommand(c2)
  assert.deepEqual(calls.runs[0], ['run', '写个任务'])
  assert.equal(p.panelAnyOpen(), false)
  assert.equal(p.panelTriggerPos.value, -1)

  p.panelTriggerPos.value = 0
  p.panelTriggerCh.value = '/'
  refs.loading.value = true
  const c3 = { cmd: 'deep', noGate: true, run: () => calls.runs.push('noGate') }
  p.runSlashCommand(c3)
  assert.ok(calls.runs.includes('noGate'))
})

test('命令集按目标态现算，筛选词按别名/名称/描述命中', () => {
  const { ctx, refs, calls } = makeCtx()
  const p = useCommandPanels(ctx)
  assert.deepEqual(p.slashCommands.value.map(c => c.key), ['act-compact', 'act-export', 'act-new'])
  refs.goalEntryVisible.value = true
  assert.equal(p.slashCommands.value.length, 4)
  const deep = p.slashCommands.value[1]
  assert.equal(deep.cmd, 'deep')
  deep.run('一句任务')
  assert.deepEqual(calls.runs.at(-1), ['goal', '一句任务'])
  refs.goalArmed.value = true
  deep.run('')
  assert.deepEqual(calls.runs.at(-1), ['arm', false])
  p.slashQuery.value = 'com'
  assert.equal(p.slashFiltered.value.length, 1)
  assert.equal(p.slashFiltered.value[0].cmd, 'compact')
})

test('「+」面板：双入口写入目标、切页签、退回一级', () => {
  const { ctx, refs, calls } = makeCtx()
  const p = useCommandPanels(ctx)
  p.toggleAddPanel()
  assert.equal(p.addOpen.value, true)
  assert.equal(p.addTarget.value, 'main')
  assert.equal(p.addTab.value, 'root')
  p.toggleAddPanel()
  assert.equal(p.addOpen.value, false)

  p.openAddPanel('main')
  p.switchAddTab('ref')
  assert.equal(p.addTab.value, 'ref')
  assert.equal(p.addFromRoot.value, true)
  assert.ok(calls.loads.includes('mention'))
  assert.ok(calls.loads.includes('agents'))
  assert.ok(calls.loads.includes('skills'))
  p.backToRoot()
  assert.equal(p.addTab.value, 'root')
  assert.equal(p.addHi.value, 0)

  refs.editingIdx.value = 0
  p.toggleEditAddPanel()
  assert.equal(p.addTarget.value, 'edit')
  assert.equal(p.addOnEdit.value, true)
  p.toggleEditAddPanel()
  assert.equal(p.addOpen.value, false)
})

test('确认高亮：root 进页签、ref/hist 勾选、cmd 执行命令，空候选返回 false', () => {
  const { ctx, refs, calls } = makeCtx()
  const p = useCommandPanels(ctx)
  p.openAddPanel('main')
  assert.equal(p.confirmAddHi(0), true)
  assert.ok(calls.runs.includes('pickAttachments'))
  assert.equal(p.confirmAddHi(99), false)

  refs.histPool.value = [{ messageId: 'h1', content: '问', digest: '摘要' }]
  p.addTab.value = 'hist'
  p.addHistQuery.value = ''
  assert.equal(p.addHistCandidates.value.length, 1)
  assert.equal(p.confirmAddHi(0), true)
  assert.ok(calls.toggleHist.some(m => m.messageId === 'h1'))

  p.addTab.value = 'cmd'
  p.addCmdQuery.value = ''
  assert.equal(p.confirmAddHi(0), true)
  assert.ok(calls.runs.includes('compact'))
  assert.equal(p.addOpen.value, false)
})

test('addStore 适配器：主输入框写主态、编辑卡写编辑态', () => {
  const { ctx, refs, calls } = makeCtx()
  const p = useCommandPanels(ctx)
  refs.pendingMentions.value = [{ type: 'kb', id: 'k1' }]
  assert.equal(p.isAddMentioned('kb', 'k1'), true)
  assert.equal(p.isAddMentioned('kb', 'k2'), false)
  refs.pickedSkills.value = ['s1']
  assert.equal(p.isAddSkillOn('s1'), true)
  p.toggleAddMention('doc', { id: 'd1' })
  assert.ok(calls.runs.some(r => Array.isArray(r) && r[0] === 'mention'))

  p.addTarget.value = 'edit'
  refs.editingIdx.value = 0
  assert.equal(p.addOnEdit.value, true)
  refs.editMentions.value = [{ type: 'kb', id: 'E1' }]
  assert.equal(p.isAddMentioned('kb', 'E1'), true)
  assert.equal(p.isAddMentioned('kb', 'k1'), false)
  refs.editSkills.value = ['es']
  assert.equal(p.isAddSkillOn('es'), true)
  p.toggleAddSkill('zz')
  assert.ok(calls.runs.some(r => Array.isArray(r) && r[0] === 'editSkill'))
  assert.equal(p.addRootRows.value.length, 3)
  assert.equal(p.addRootRows.value.find(r => r.key === 'ref').kbd, '')
  assert.equal(p.addRootRows.value.some(r => r.key === 'cmd'), false)
})

test('引用行确认：@ 触发的摘片段/收面板/回输入框，点 + 的留在面板', () => {
  const { ctx, refs, calls, composer } = makeCtx()
  const p = useCommandPanels(ctx)
  refs.text.value = '@知识'
  composer._caret = 3
  p.panelTriggerPos.value = 0
  p.panelTriggerCh.value = '@'
  p.addOpen.value = true
  p.addTab.value = 'ref'
  p.confirmRefRow('kb', { id: 'k1', name: '库' })
  assert.equal(p.addOpen.value, false)
  assert.equal(calls.focusInput, 1)
  assert.ok(calls.runs.some(r => Array.isArray(r) && r[0] === 'mention'))
  assert.equal(p.panelTriggerPos.value, -1)

  p.addOpen.value = true
  p.addTab.value = 'ref'
  p.confirmRefRow('skill', { name: 's1' })
  assert.equal(p.addOpen.value, true)
  assert.equal(calls.focusInput, 1)
  assert.ok(calls.runs.some(r => Array.isArray(r) && r[0] === 'skill'))
})

test('面板键盘：↑↓/Esc/Tab/Enter 分派并 preventDefault，空候选 Enter 放行', () => {
  const { ctx, calls } = makeCtx()
  const p = useCommandPanels(ctx)
  p.openSlashPanel()
  let k = ev('ArrowDown')
  assert.equal(p.onPanelNavKeydown(k.e), true)
  assert.equal(k.prevented, 1)
  assert.equal(p.slashHi.value, 1)

  k = ev('Escape')
  assert.equal(p.onPanelNavKeydown(k.e), true)
  assert.equal(p.slashOpen.value, false)

  p.openSlashPanel()
  p.slashQuery.value = 'zzzz'
  k = ev('Enter')
  assert.equal(p.onPanelNavKeydown(k.e), false)
  assert.equal(k.prevented, 0)

  k = ev('ArrowDown', { isComposing: true })
  assert.equal(p.onPanelNavKeydown(k.e), false)

  p.openAddPanel('main')
  p.switchAddTab('ref')
  k = ev('Escape')
  assert.equal(p.onPanelNavKeydown(k.e), true)
  assert.equal(p.addTab.value, 'root')
  assert.equal(p.addOpen.value, true)

  k = ev('Tab')
  assert.equal(p.onPanelNavKeydown(k.e), true)
  assert.ok(calls.runs.includes('pickAttachments'))

  p.switchAddTab('hist')
  k = ev('Tab')
  assert.equal(p.onPanelNavKeydown(k.e), true)
  assert.equal(p.addTab.value, 'root')
})

test('watch(addQuery)：候选集变化把高亮复位到 0', async () => {
  const { ctx } = makeCtx()
  const p = useCommandPanels(ctx)
  p.addTab.value = 'cmd'
  p.addHi.value = 5
  p.addCmdQuery.value = 'co'
  await nextTick()
  assert.equal(p.addHi.value, 0)
})
