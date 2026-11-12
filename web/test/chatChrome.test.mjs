import test from 'node:test'
import assert from 'node:assert/strict'
import { ref, nextTick } from 'vue'
import {
  GOAL_KEY, OVERLAY_ROOT_CLASSES, MENTION_TIP,
  fileKeyOf, imgKeyOf, menKeyOf, skillKeyOf, histKeyOf,
  questionForIndex, processMsgOf, composerPlaceholderOf, anchorQOf, anchorAOf,
  panelOpenOf, panelStoredOf, hitsOverlayPath, inPanelPath, globalKeydownIntent,
  desiredChipKeys, chipDescriptorOf, prunedChipKeys
} from '../src/chat/chatChrome.js'
import { useChatChrome } from '../src/chat/useChatChrome.js'

// ==================== 纯判据 ====================

test('列表映射：向上找最近用户提问；过程卡取含 loading 的最近一条 AI', () => {
  const list = [
    { role: 'user', content: '第一问' },
    { role: 'ai', content: '答一' },
    { role: 'user', content: '' },
    { role: 'ai', content: '答二' },
    { role: 'user', content: '第二问' },
    { role: 'ai', content: '', loading: true }
  ]
  assert.equal(questionForIndex(list, 4), '第一问')
  assert.equal(questionForIndex(list, 1), '第一问')
  assert.equal(questionForIndex(list, 0), '')
  assert.equal(questionForIndex([], 3), '')
  assert.equal(processMsgOf(list), list[5])
  assert.equal(processMsgOf([]), null)
  assert.equal(processMsgOf(list.slice(0, 2)), list[1])
})

test('占位文案：生成中插话/目标轮、目标上膛、窄屏不提示 Enter', () => {
  assert.equal(composerPlaceholderOf({ loading: true, goalTurnStreaming: true, isNarrow: false, goalArmed: false, hasSessionGoal: false }),
    '插话：先回答你这句，目标随后自动继续')
  assert.match(composerPlaceholderOf({ loading: true, goalTurnStreaming: false, isNarrow: false, goalArmed: false, hasSessionGoal: false }), /⌘\/Ctrl\+Enter/)
  assert.match(composerPlaceholderOf({ loading: true, goalTurnStreaming: false, isNarrow: true, goalArmed: false, hasSessionGoal: false }), /立即发送/)
  assert.match(composerPlaceholderOf({ loading: false, goalTurnStreaming: false, isNarrow: false, goalArmed: true, hasSessionGoal: false }), /写下目标/)
  assert.equal(composerPlaceholderOf({ loading: false, goalTurnStreaming: false, isNarrow: false, goalArmed: true, hasSessionGoal: true }).includes('写下目标'), false)
  assert.match(composerPlaceholderOf({ loading: false, goalTurnStreaming: false, isNarrow: true, goalArmed: false, hasSessionGoal: false }), /@ 引用资料/)
  assert.equal(composerPlaceholderOf({ loading: false, goalTurnStreaming: false, isNarrow: true, goalArmed: false, hasSessionGoal: false }).includes('Enter'), false)
})

test('锚点文案：问题空回退、生成中占位、无回答不占字', () => {
  const oneLine = (s, n) => String(s || '').slice(0, n)
  assert.equal(anchorQOf({ q: '问题' }, oneLine), '问题')
  assert.equal(anchorQOf({ q: '' }, oneLine), '（随行图片 / 附件提问）')
  assert.equal(anchorQOf({ q: 'x'.repeat(80) }, oneLine).length, 64)
  assert.equal(anchorAOf({ loading: true, hasAi: true, a: '答' }, oneLine), '回答生成中…')
  assert.equal(anchorAOf({ loading: false, hasAi: false, a: '答' }, oneLine), '')
  assert.equal(anchorAOf({ loading: false, hasAi: true, a: '回答' }, oneLine), '回答')
  assert.equal(anchorAOf({ loading: false, hasAi: true, a: 'y'.repeat(200) }, oneLine).length, 120)
})

test('右栏开合与浮层命中：窄屏收起、宽屏阈值、外点判据', () => {
  assert.equal(panelOpenOf(false, true), true)
  assert.equal(panelOpenOf(true, true), false)
  assert.equal(panelOpenOf(false, false), false)
  assert.equal(panelStoredOf(null, 1400), true)
  assert.equal(panelStoredOf('0', 1400), false)
  assert.equal(panelStoredOf(null, 1100), false)
  const node = classes => ({ classList: { contains: c => classes.includes(c) } })
  assert.equal(hitsOverlayPath([node(['x']), node(['ant-modal-root'])]), true)
  assert.equal(hitsOverlayPath([node(['x'])]), false)
  assert.equal(hitsOverlayPath([]), false)
  assert.equal(inPanelPath([node(['ci-box'])]), true)
  assert.equal(inPanelPath([node(['msg-inline-edit'])]), true)
  assert.equal(inPanelPath([node(['mention-panel'])]), true)
  assert.equal(inPanelPath([node(['other'])]), false)
  assert.deepEqual(OVERLAY_ROOT_CLASSES, ['ant-modal-root', 'ant-dropdown'])
})

test('全局 Esc 意图：输入法放行、灯箱方向键放行、生成中两段式、空闲清输入或失焦', () => {
  const base = { loading: false, previewOpen: false, escArmed: false, composerFocused: true, hasText: false }
  assert.equal(globalKeydownIntent({ isComposing: true, key: 'Escape' }, base), 'ignore')
  assert.equal(globalKeydownIntent({ keyCode: 229, key: 'Escape' }, base), 'ignore')
  assert.equal(globalKeydownIntent({ key: 'ArrowLeft' }, { ...base, previewOpen: true }), 'ignore')
  assert.equal(globalKeydownIntent({ key: 'Escape' }, { ...base, previewOpen: true }), 'ignore')
  assert.equal(globalKeydownIntent({ key: 'Enter' }, base), 'ignore')
  assert.equal(globalKeydownIntent({ key: 'Escape' }, { ...base, loading: true }), 'arm')
  assert.equal(globalKeydownIntent({ key: 'Escape' }, { ...base, loading: true, escArmed: true }), 'stop')
  assert.equal(globalKeydownIntent({ key: 'Escape' }, { ...base, composerFocused: false }), 'ignore')
  assert.equal(globalKeydownIntent({ key: 'Escape' }, { ...base, hasText: true }), 'clear')
  assert.equal(globalKeydownIntent({ key: 'Escape' }, base), 'blur')
})

test('chip 键映射：各类 keyOf 与应有集合（目标 chip 只在三条件齐备时入列）', () => {
  assert.equal(fileKeyOf({ chipKey: 'f1' }), 'f1')
  assert.equal(fileKeyOf(null), '')
  assert.equal(imgKeyOf({ chipKey: 'p2' }), 'p2')
  assert.equal(menKeyOf({ type: 'doc', id: 'd1' }), 'm:doc:d1')
  assert.equal(skillKeyOf('s1'), 's:s1')
  assert.equal(histKeyOf({ messageId: 'h9' }), 'h:h9')
  const keys = desiredChipKeys({
    goalModeOn: true, goalArmed: true, hasSessionGoal: false,
    files: [{ chipKey: 'f1' }], images: [{ chipKey: 'p1' }],
    mentions: [{ type: 'kb', id: 'k1' }], skills: ['s1'], histRefs: [{ messageId: 'h1' }]
  })
  assert.deepEqual(keys, [GOAL_KEY, 'f1', 'p1', 'm:kb:k1', 's:s1', 'h:h1'])
  assert.deepEqual(desiredChipKeys({ goalModeOn: false, goalArmed: true, hasSessionGoal: false }), [])
  assert.deepEqual(desiredChipKeys({ goalModeOn: true, goalArmed: true, hasSessionGoal: true }), [])
  assert.deepEqual(desiredChipKeys({ goalModeOn: true, goalArmed: true, hasSessionGoal: false,
                                    files: [{}] }), [GOAL_KEY])
})

test('chip 描述：目标/附件/图片/@/技能/历史各类型与上传态', () => {
  const deps = {
    isPastedText: f => !!f.paste, pasteTitle: n => '粘贴:' + n, pasteSub: () => '全文',
    fmtSize: n => n + 'B', histChipLabel: h => h.digest || ''
  }
  const state = {
    files: [{ chipKey: 'f1', name: 'a.txt', size: 12, fileId: 'F1' },
            { chipKey: 'f2', name: 'p.txt', paste: true, text: '内容', uploading: true }],
    images: [{ chipKey: 'p1', dataUrl: 'data:image/png;base64,AA' }],
    mentions: [{ type: 'doc', id: 'd1', name: '文档' }],
    skills: ['s1'], histRefs: [{ messageId: 'h1', digest: '摘要' }]
  }
  assert.equal(chipDescriptorOf(null, state, deps), null)
  assert.equal(chipDescriptorOf(GOAL_KEY, state, deps).kind, 'goal')
  const f = chipDescriptorOf('f1', state, deps)
  assert.equal(f.kind, 'file')
  assert.equal(f.sub, '12B')
  assert.equal(f.fileId, 'F1')
  const paste = chipDescriptorOf('f2', state, deps)
  assert.equal(paste.paste, true)
  assert.equal(paste.name, '粘贴:p.txt')
  assert.equal(paste.state, 'uploading')
  assert.equal(chipDescriptorOf('p1', state, deps).thumb, 'data:image/png;base64,AA')
  const men = chipDescriptorOf('m:doc:d1', state, deps)
  assert.equal(men.icon, 'mention')
  assert.equal(men.title, MENTION_TIP.doc)
  assert.equal(chipDescriptorOf('s:s1', state, deps).avatar, 's')
  assert.equal(chipDescriptorOf('h:h1', state, deps).name, '摘要')
  assert.equal(chipDescriptorOf('h:missing', state, deps), null)
  assert.equal(chipDescriptorOf('s:missing', state, deps), null)
  assert.equal(chipDescriptorOf('f9', state, deps), null)
  assert.equal(chipDescriptorOf('?x', state, deps), null)
})

test('chip 剪枝判据：只列被删的键，深度执行 chip 被删即退出模式', () => {
  const r = prunedChipKeys(['goal', 'f1'], ['goal', 'f1', 'f2'])
  assert.deepEqual(r.removed, ['f2'])
  assert.equal(r.goalOff, false)
  const g = prunedChipKeys(['f1'], ['goal', 'f1'])
  assert.deepEqual(g.removed, ['goal'])
  assert.equal(g.goalOff, true)
  assert.deepEqual(prunedChipKeys([], []).removed, [])
})

// ==================== composable ====================

const THINK_REFS = ['thinkHoverRef', 'thinkPanelPos', 'thinkPanelEl', 'mThinkOpen', 'thinkHoverModel',
  'thinkPanelVisible', 'thinkPanelCaps', 'thinkPanelLevelOptions', 'thinkPanelCurrentLevel',
  'thinkPanelStrengthText', 'thinkPanelFoot', 'pillLevelRef', 'pillLevelBadge', 'pillLevelTitle',
  'thinkPanelCtxText']
const THINK_FNS = ['toggleThinkSheet', 'onDocPointerDown', 'onModelOptionHover', 'scheduleThinkHide',
  'onThinkPanelEnter', 'onThinkPanelLeave', 'onModelSelectOpenChange']
const SCROLL_REFS = ['stickToBottom', 'tailSpacer', 'railVis', 'railHoverIdx', 'railPopStyle',
  'railHoverAnchor', 'railActiveIdx']
const SCROLL_FNS = ['onMessagesScroll', 'scroll', 'scrollForce', 'updateTailSpacer', 'showAnchor',
  'gotoAnchor', 'updateRailActive']
const PANEL_REFS = ['eventsOpen', 'eventsTurn', 'eventsMsg', 'eventsQuestion', 'eventsTitle',
  'ctxCapOpen', 'ctxCapPos', 'ctxCapEl']
const PANEL_FNS = ['openEvents', 'srcOpenOf', 'toggleSrc', 'showCtxCap', 'hideCtxCap',
  'onCtxCapEnter', 'onCtxCapLeave']
const SOURCE_FNS = ['hoverSource', 'unhoverSource', 'locateSource', 'jumpToPlanRow']

const composerStubs = { sync: () => {}, repaint: () => {}, domKeys: () => [] }

const makeStubs = () => {
  const captured = {}
  const fired = []
  const mk = (tag, refInit, fns) => opts => {
    captured[tag] = opts
    const out = {}
    for (const [k, v] of Object.entries(refInit)) out[k] = ref(v)
    for (const k of fns) out[k] = (...args) => { fired.push([tag, k, args]) }
    return out
  }
  return {
    captured, fired,
    useThinkPanel: mk('think', Object.fromEntries(THINK_REFS.map(k => [k, null])), THINK_FNS),
    useChatScroll: mk('scroll', { stickToBottom: true, tailSpacer: 0, railVis: false,
      railHoverIdx: -1, railPopStyle: {}, railHoverAnchor: null, railActiveIdx: -1 }, SCROLL_FNS),
    useChatPanels: mk('panels', Object.fromEntries(PANEL_REFS.map(k => [k, null])), PANEL_FNS),
    useSourceFocus: mk('source', {}, SOURCE_FNS)
  }
}

const makeDomParser = () => class {
  parseFromString (html) {
    const nodes = []
    for (const tag of String(html).match(/<[^>]*data-wenqu-kind[^>]*>/gi) || []) {
      const attrs = {}
      const re = /data-([\w-]+)="([^"]*)"/g
      let m
      while ((m = re.exec(tag))) attrs[m[1]] = m[2]
      nodes.push({ getAttribute: k => attrs[k.replace(/^data-/, '')] ?? null })
    }
    return { querySelectorAll: sel => (sel === '[data-wenqu-kind]' ? nodes : []) }
  }
}

const makeCtx = (over = {}) => {
  const msgs = ref([])
  const timers = []
  const notices = { warning: [], info: [], success: [], error: [] }
  const pushes = []
  const calls = { addFiles: [], closeAddPanel: [], closeAllPanels: [], stop: [], openDispatchCard: [],
                  openPlanViewer: [], openImages: [], ensureRefCandidates: [], stopTick: [],
                  attachUploaded: [], attachImage: [], toggleMention: [], toggleSkill: [],
                  toggleHistoryRef: [], feedback: [], takePastedFiles: [], takePastedText: [] }
  const stubs = makeStubs()
  const hostStyle = { v: '', getPropertyValue (k) { return k === '--chat-sbw' ? this.v : '' },
                      setProperty (k, v) { if (k === '--chat-sbw') this.v = v } }
  const docQuery = {}
  const win = { innerWidth: 1400, innerHeight: 900, addEventListener () {}, removeEventListener () {} }
  const doc = {
    activeElement: null,
    documentElement: { style: hostStyle },
    querySelector: sel => docQuery[sel] || null,
    addEventListener () {}, removeEventListener () {}
  }
  const storage = { v: null, getItem () { return this.v }, setItem (k, v) { this.v = v } }
  const fakeTimer = (fn, ms) => { timers.push({ fn, ms }); return timers.length }
  const fakeCancel = id => { if (id) timers[id - 1] = null }
  const ctx = {
    messages: () => msgs.value,
    loading: () => false,
    goalTurnStreaming: ref(false),
    isNarrow: ref(false),
    isCoarse: ref(false),
    sessionGoal: ref(null),
    goalModeOn: ref(false),
    planModeOn: ref(false),
    goalBarVisible: ref(false),
    text: ref(''),
    previewUrl: ref(null),
    pendingFiles: ref([]),
    pendingImages: ref([]),
    pendingMentions: ref([]),
    pickedSkills: ref([]),
    pendingHistoryRefs: ref([]),
    lastAi: () => null,
    hoveredRef: ref(null),
    agentPickerOpen: ref(false),
    slashOpen: ref(false),
    histOpen: ref(false),
    addOpen: ref(false),
    addTab: ref(''),
    addTarget: ref(''),
    addHi: ref(0),
    addFromRoot: ref(false),
    mentionQuery: ref(''),
    histPool: ref([]),
    effectiveModel: ref('m/a'),
    currentOverrideModel: ref(''),
    modelIndex: ref({}),
    thinkCapsOf: () => ({ visible: true, locked: false }),
    levelOptionsOf: () => [],
    currentLevelOf: () => 'high',
    deepOnOf: () => true,
    fmtWindow: n => (n ? n + 'K' : ''),
    router: { push: p => pushes.push(p) },
    route: { path: '/chat' },
    api: {},
    win, doc, storage,
    CSS: { escape: s => 'esc:' + s },
    DOMParser: makeDomParser(),
    setTimeout: fakeTimer,
    clearTimeout: fakeCancel,
    notify: { warning: t => notices.warning.push(t), info: t => notices.info.push(t),
              success: t => notices.success.push(t), error: t => notices.error.push(t) },
    apiSubmitFeedback: async (...a) => { calls.feedback.push(a); return { success: true } },
    oneLine: (s, n) => String(s || '').slice(0, n),
    MAX_FILES: 5,
    CLUSTER_ICONS: { search: 'S' },
    clusterHeadKind: seg => seg.kind,
    BulbOutlined: 'B',
    openDispatchCard: (...a) => calls.openDispatchCard.push(a),
    spawnAnchorOf: t => 'a' + t,
    openPlanViewer: (...a) => calls.openPlanViewer.push(a),
    openImages: (...a) => calls.openImages.push(a),
    resolveImg: u => u,
    addFiles: (...a) => calls.addFiles.push(a),
    closeAddPanel: () => calls.closeAddPanel.push(1),
    closeAllPanels: () => calls.closeAllPanels.push(1),
    ensureRefCandidates: () => calls.ensureRefCandidates.push(1),
    stopTick: () => calls.stopTick.push(1),
    stop: () => calls.stop.push(1),
    takePastedFiles: e => { calls.takePastedFiles.push(e); return false },
    takePastedText: e => { calls.takePastedText.push(e); return false },
    isPastedText: f => !!f.paste,
    pasteTitle: n => '粘贴:' + n,
    pasteSub: () => '全文',
    fmtSize: n => n + 'B',
    histChipLabel: h => h.digest || '',
    attachUploaded: a => { calls.attachUploaded.push(a); return true },
    attachImage: u => { calls.attachImage.push(u); return true },
    isMentioned: () => false,
    toggleMention: (...a) => calls.toggleMention.push(a),
    toggleSkill: n => calls.toggleSkill.push(n),
    isHistPicked: () => false,
    toggleHistoryRef: h => calls.toggleHistoryRef.push(h),
    ...stubs,
    ...over
  }
  return { ctx, msgs, timers, notices, pushes, calls, stubs, hostStyle, docQuery, win, doc, storage }
}

const EXPECTED = [
  'router', 'route', 'clusterIconOf', 'questionFor', 'goalArmed', 'goalStarting', 'composerRef',
  'inlineKeys', 'box', ...THINK_REFS, ...THINK_FNS, 'goManageAgents', ...SCROLL_REFS, ...SCROLL_FNS,
  'panelStoredRaw', 'panelStored', 'panelOpen', 'mPanelOpen', 'togglePanel', 'processMsg',
  'focusSpawn', 'focusHitl', 'revealApprovalCard', ...PANEL_REFS, ...PANEL_FNS,
  'sessionPlans', 'jumpToPlan', 'openPlanFromRail', 'disclaimerVisible', 'DISCLAIMER_TEXT',
  ...SOURCE_FNS, 'feedbackVisible', 'feedbackSubmitting', 'feedbackText', 'feedbackTarget',
  'openFeedback', 'doSubmitFeedback', 'escArmed', 'escArmTimer', 'armEsc', 'disarmEsc', 'stopNow',
  'composerPlaceholder', 'onGlobalKeydown', 'onGlobalPaste', 'syncScrollbarGutter', 'onWindowResize',
  'onOrientationChange', 'focusInput', 'openPreviewFromMsg', 'onDropFiles', 'onComposerPaste',
  'pasteView', 'openPasteView', 'attachInput', 'pickAttachments', 'onAttachChange',
  'openMentionPanel', 'OVERLAY_ROOT_CLASSES', 'hitsOverlay', 'onDocClickForMention',
  'qaAnchors', 'anchorQ', 'anchorA', 'GOAL_KEY', 'fileKeyOf', 'imgKeyOf', 'menKeyOf', 'skillKeyOf',
  'histKeyOf', 'MENTION_TIP', 'desiredKeys', 'chipOf', 'onKeys', 'syncChips', 'onChipClick',
  'restoreWenquChips', 'todoOpen', 'srcCardOpen'
]

test('接线完整性：区间声明与四组 hook 解构名字全部返回', () => {
  const { ctx } = makeCtx()
  const api = useChatChrome(ctx)
  const missing = EXPECTED.filter(k => !(k in api))
  assert.deepEqual(missing, [])
  assert.equal(api.stickToBottom.value, true)
  assert.equal(typeof api.onDocPointerDown, 'function')
  assert.equal(typeof api.jumpToPlanRow, 'function')
  assert.equal(api.todoOpen.value, false)
  assert.equal(api.srcCardOpen.value, true)
  assert.equal(api.disclaimerVisible.value, false)
  assert.match(api.DISCLAIMER_TEXT, /内容生成方式/)
  assert.equal(api.escArmed.value, false)
})

test('hook 接线入参：惰性 getter 取到消息与锚点，DOM 与定时器经 ctx 注入', () => {
  const { ctx, msgs, stubs } = makeCtx()
  msgs.value = [{ role: 'user', content: '问' }]
  const api = useChatChrome(ctx)
  assert.deepEqual(stubs.captured.scroll.messagesOf(), msgs.value)
  assert.equal(stubs.captured.scroll.qaAnchorsOf().length, 1)
  assert.equal(stubs.captured.scroll.qaAnchorsOf()[0].q, '问')
  assert.equal(stubs.captured.think.isCoarse.value, false)
  assert.equal(stubs.captured.source.hoveredRef.value, null)
  assert.equal(typeof stubs.captured.panels.viewport, 'function')
  assert.equal(api.qaAnchors.value.length, 1)
  assert.equal(api.anchorQ(api.qaAnchors.value[0]), '问')
  assert.equal(api.anchorA(api.qaAnchors.value[0]), '')
})

test('右栏开合：宽屏翻存储位并持久化；窄屏只翻移动端抽屉', () => {
  const { ctx, storage } = makeCtx()
  const api = useChatChrome(ctx)
  assert.equal(api.panelOpen.value, true)
  assert.equal(storage.getItem('app_panel'), null)
  api.togglePanel()
  assert.equal(api.panelOpen.value, false)
  assert.equal(storage.getItem('app_panel'), '0')
  api.togglePanel()
  assert.equal(api.panelOpen.value, true)
  assert.equal(storage.getItem('app_panel'), '1')

  const narrow = makeCtx()
  narrow.ctx.isNarrow = ref(true)
  const n = useChatChrome(narrow.ctx)
  assert.equal(n.panelOpen.value, false)
  n.togglePanel()
  assert.equal(n.mPanelOpen.value, true)
  assert.equal(narrow.storage.getItem('app_panel'), null)
})

test('占位文案 computed 与过程卡/计划列表随引擎值联动', () => {
  const { ctx, msgs } = makeCtx()
  const loadingRef = ref(false)
  ctx.loading = () => loadingRef.value
  const api = useChatChrome(ctx)
  assert.match(api.composerPlaceholder.value, /Enter 发送/)
  loadingRef.value = true
  assert.match(api.composerPlaceholder.value, /补一句方向/)
  loadingRef.value = false
  api.goalArmed.value = true
  assert.match(api.composerPlaceholder.value, /写下目标/)
  api.goalArmed.value = false
  ctx.isNarrow.value = true
  assert.match(api.composerPlaceholder.value, /@ 引用资料/)

  msgs.value = [
    { role: 'user', content: '问' },
    { role: 'ai', content: '答', loading: true },
    { role: 'ai', content: '带计划', planCard: { status: 'pending', versions: [1, 2], plan: '1. a\n2. b' } }
  ]
  assert.equal(api.processMsg.value.content, '带计划')
  assert.equal(api.sessionPlans.value.length, 1)
  assert.equal(api.sessionPlans.value[0].label, '问')
  assert.equal(api.sessionPlans.value[0].vers, 2)
  assert.equal(api.questionFor(2), '问')
  assert.equal(api.anchorA(api.qaAnchors.value[0]), '回答生成中…')
})

test('Esc 待命：上膛 2s 自动回落、再按停止、disarm 清定时器', () => {
  const { ctx, timers, calls } = makeCtx()
  let loading = true
  ctx.loading = () => loading
  const api = useChatChrome(ctx)
  api.onGlobalKeydown({ key: 'Escape' })
  assert.equal(api.escArmed.value, true)
  assert.equal(timers[0].ms, 2000)
  timers[0].fn()
  assert.equal(api.escArmed.value, false)
  assert.equal(calls.stop.length, 0)

  api.armEsc()
  api.stopNow()
  assert.equal(api.escArmed.value, false)
  assert.equal(calls.stop.length, 1)
  assert.equal(timers.at(-1), null)

  // 空闲时清输入 / 失焦
  loading = false
  ctx.text.value = '草稿'
  const composerEl = { contains: () => true }
  api.composerRef.value = { ...composerStubs, rootEl: composerEl }
  ctx.doc.activeElement = composerEl
  api.onGlobalKeydown({ key: 'Escape' })
  assert.equal(ctx.text.value, '')
  let blurred = 0
  ctx.doc.activeElement = { blur: () => { blurred++ } }
  api.onGlobalKeydown({ key: 'Escape' })
  assert.equal(blurred, 1)
})

test('输入框 chip：desiredKeys/chipOf/onKeys/syncChips/onChipClick 联动', () => {
  const { ctx, calls } = makeCtx()
  const api = useChatChrome(ctx)
  ctx.goalModeOn.value = true
  api.goalArmed.value = true
  ctx.pendingFiles.value = [{ chipKey: 'f1', name: 'a.txt', size: 3 }]
  ctx.pendingMentions.value = [{ type: 'kb', id: 'k1', name: '库' }]
  ctx.pickedSkills.value = ['s1']
  assert.deepEqual(api.desiredKeys(), [GOAL_KEY, 'f1', 'm:kb:k1', 's:s1'])
  assert.equal(api.chipOf('f1').name, 'a.txt')
  assert.equal(api.chipOf('goal').kind, 'goal')

  api.inlineKeys.value = [GOAL_KEY, 'f1', 'm:kb:k1', 's:s1']
  api.onKeys([GOAL_KEY, 'f1', 's:s1'])
  assert.deepEqual(ctx.pendingMentions.value, [])
  assert.equal(ctx.pendingFiles.value.length, 1)
  assert.equal(api.goalArmed.value, true)
  api.onKeys(['f1', 's:s1'])
  assert.equal(api.goalArmed.value, false)

  const synced = []
  api.composerRef.value = { ...composerStubs, sync: k => synced.push(k),
                            repaint: () => synced.push('repaint'), domKeys: () => ['f1', 's:s1'] }
  api.syncChips()
  assert.deepEqual(synced[0], ['f1', 's:s1'])
  assert.equal(synced[1], 'repaint')
  assert.deepEqual(api.inlineKeys.value, ['f1', 's:s1'])

  ctx.pendingFiles.value = [{ chipKey: 'f1', name: 'p.txt', paste: true, text: '全文' }]
  api.onChipClick('f1')
  assert.equal(api.pasteView.open, true)
  assert.equal(api.pasteView.text, '全文')
  api.onChipClick('unknown')
  assert.equal(calls.closeAllPanels.length, 0)
})

test('反馈：同一回答改评价拦截；提交成功写回 fb 并收起；失败与异常提示', async () => {
  const { ctx, calls, notices } = makeCtx()
  const api = useChatChrome(ctx)
  const m = { messageId: 'a1', fb: 1 }
  api.openFeedback(m, 0)
  assert.match(notices.warning.at(-1), /同一回答只能选择一项/)
  assert.equal(api.feedbackVisible.value, false)

  const m2 = { messageId: 'a2' }
  api.openFeedback(m2, 1)
  assert.equal(api.feedbackVisible.value, true)
  api.feedbackText.value = '  不错  '
  await api.doSubmitFeedback()
  assert.deepEqual(calls.feedback[0], ['a2', 1, '不错'])
  assert.equal(m2.fb, 1)
  assert.equal(api.feedbackVisible.value, false)
  assert.match(notices.success.at(-1), /感谢反馈/)
  assert.equal(api.feedbackSubmitting.value, false)

  ctx.apiSubmitFeedback = async () => ({ success: false, msg: '后端拒绝' })
  api.openFeedback({ messageId: 'a3' }, 0)
  await api.doSubmitFeedback()
  assert.equal(notices.error.at(-1), '后端拒绝')
  ctx.apiSubmitFeedback = async () => { throw new Error('网络错误') }
  api.openFeedback({ messageId: 'a4' }, 0)
  await api.doSubmitFeedback()
  assert.equal(notices.error.at(-1), '网络错误')
  api.openFeedback({ messageId: '' }, 0)
  await api.doSubmitFeedback()
  assert.match(notices.warning.at(-1), /不可反馈/)
})

test('@ 面板：打开只关其它面板并停在引用页；外点判据按浮层/面板放行', () => {
  const { ctx, calls } = makeCtx()
  const api = useChatChrome(ctx)
  ctx.slashOpen.value = true
  ctx.histOpen.value = true
  ctx.addTarget.value = 'edit'
  api.openMentionPanel()
  assert.equal(ctx.slashOpen.value, false)
  assert.equal(ctx.histOpen.value, false)
  assert.equal(ctx.addOpen.value, true)
  assert.equal(ctx.addTab.value, 'ref')
  assert.equal(ctx.addTarget.value, 'main')
  assert.equal(ctx.addHi.value, 0)
  assert.equal(ctx.addFromRoot.value, false)
  assert.equal(ctx.mentionQuery.value, '')
  assert.equal(calls.ensureRefCandidates.length, 1)

  const node = classes => ({ classList: { contains: c => classes.includes(c) } })
  api.onDocClickForMention({ composedPath: () => [node(['ant-modal-root'])] })
  assert.equal(calls.closeAllPanels.length, 0)
  api.onDocClickForMention({ composedPath: () => [node(['ci-box'])] })
  assert.equal(calls.closeAllPanels.length, 0)
  api.onDocClickForMention({ composedPath: () => [node(['elsewhere'])] })
  assert.equal(calls.closeAllPanels.length, 1)
  ctx.addOpen.value = false
  ctx.slashOpen.value = false
  ctx.histOpen.value = false
  api.onDocClickForMention({ composedPath: () => [node(['elsewhere'])] })
  assert.equal(calls.closeAllPanels.length, 1)
  assert.equal(api.hitsOverlay([node(['ant-dropdown'])]), true)
})

test('深链与卡片定位：派遣卡/审批卡/答题面板落点，找不到不动', async () => {
  const { ctx, calls, docQuery } = makeCtx()
  const api = useChatChrome(ctx)
  const scrolls = []
  const makeEl = () => ({ classList: { remove () {}, add () {} }, offsetWidth: 1,
                          scrollIntoView: () => scrolls.push(1) })
  const spawnEl = makeEl()
  api.box.value = { querySelector: sel => (sel.includes('data-spawn') ? spawnEl : null) }
  api.focusSpawn({ run: { t: 7 }, msg: { id: 1 } })
  assert.deepEqual(calls.openDispatchCard[0], [{ id: 1 }, 7])
  assert.equal(scrolls.length, 1)
  api.focusSpawn({ run: {} })
  assert.equal(scrolls.length, 1)

  const approvalEl = makeEl()
  api.box.value = { querySelector: () => approvalEl }
  api.focusHitl('approval')
  assert.equal(scrolls.length, 2)
  let focused = 0
  docQuery['.askp-box, .planp-box'] = {
    scrollIntoView: () => scrolls.push(1),
    querySelector: () => ({ focus: () => { focused++ } })
  }
  api.focusHitl('ask')
  assert.equal(scrolls.length, 3)
  assert.equal(focused, 1)

  const card = makeEl()
  api.box.value = { querySelector: sel => (sel === '[data-approval-id="esc:9"]' ? card : null) }
  api.revealApprovalCard(9)
  await nextTick()
  assert.equal(scrolls.length, 4)
})

test('粘贴与附件：四分流、粘贴全文回看、选完收面板、数量上限', () => {
  const { ctx, calls, notices } = makeCtx()
  const api = useChatChrome(ctx)
  let stopped = 0
  const e = { clipboardData: { getData: t => (t === 'text/plain' ? '正文' : '') },
              stopPropagation: () => { stopped++ } }
  let inserted = ''
  api.composerRef.value = { ...composerStubs, insertText: t => { inserted = t } }
  api.onComposerPaste(e)
  assert.equal(inserted, '正文')
  assert.equal(stopped, 1)
  assert.equal(calls.takePastedFiles.length, 1)

  let took = 0
  ctx.takePastedFiles = () => { took++; return true }
  api.onComposerPaste(e)
  assert.equal(stopped, 2)

  api.openPasteView({ name: 'n', text: '' })
  assert.match(notices.info.at(-1), /未随历史记录保留/)
  api.openPasteView({ name: 'n', text: '全文' })
  assert.equal(api.pasteView.open, true)

  let clicked = 0
  api.attachInput.value = { click: () => { clicked++ } }
  api.pickAttachments()
  assert.equal(clicked, 1)
  ctx.pendingFiles.value = Array.from({ length: 5 }, () => ({}))
  api.pickAttachments()
  assert.equal(clicked, 1)
  assert.match(notices.warning.at(-1), /最多上传 5 个/)

  api.onAttachChange({ target: { files: [1, 2], value: 'x' } })
  assert.equal(calls.addFiles.length, 1)
  assert.equal(calls.closeAddPanel.length, 1)
  api.onDropFiles([3])
  assert.equal(calls.addFiles.length, 2)
  api.onGlobalPaste({ q: 1 })
  assert.equal(took, 2)
})

test('粘回自己复制的一轮：按 data-wenqu-* 挂回附件/图片/引用/技能/历史', () => {
  const { ctx, calls } = makeCtx()
  const api = useChatChrome(ctx)
  ctx.histPool.value = [{ messageId: 'h1' }]
  const html = [
    '<span data-wenqu-kind="file" data-wenqu-name="a.txt" data-wenqu-mime="t" data-wenqu-size="12" data-wenqu-file-id="F1"></span>',
    '<span data-wenqu-kind="img" data-wenqu-dataurl="data:image/png;base64,AA"></span>',
    '<span data-wenqu-kind="mention" data-wenqu-mtype="doc" data-wenqu-id="d1" data-wenqu-name="文档"></span>',
    '<span data-wenqu-kind="skill" data-wenqu-name="s1"></span>',
    '<span data-wenqu-kind="hist" data-wenqu-id="h1"></span>'
  ].join('')
  assert.equal(api.restoreWenquChips(html), true)
  assert.equal(calls.attachUploaded.length, 1)
  assert.equal(calls.attachUploaded[0].fileId, 'F1')
  assert.equal(calls.attachImage.length, 1)
  assert.equal(calls.toggleMention.length, 1)
  assert.equal(calls.toggleSkill.length, 1)
  assert.equal(calls.toggleHistoryRef.length, 1)
  assert.equal(api.restoreWenquChips('<p>plain</p>'), false)
})

test('滚动槽宽与 resize：写入 --chat-sbw；改消息触发 watch 里的滚动回调', async () => {
  const { ctx, hostStyle, msgs, stubs, timers } = makeCtx()
  const api = useChatChrome(ctx)
  api.box.value = {
    offsetWidth: 100, clientWidth: 85,
    closest: () => ({ style: hostStyle }),
    querySelector: () => null
  }
  api.syncScrollbarGutter()
  assert.equal(hostStyle.v, '15px')
  api.onWindowResize()
  assert.ok(stubs.fired.some(([t, k]) => t === 'scroll' && k === 'updateTailSpacer'))
  assert.ok(stubs.fired.some(([t, k]) => t === 'scroll' && k === 'scroll'))
  api.onOrientationChange()
  assert.equal(timers.at(-1).ms, 120)

  msgs.value = [{ role: 'user', content: '新问' }]
  await nextTick()
  await new Promise(r => setTimeout(r, 0))
  assert.ok(stubs.fired.some(([t, k]) => t === 'scroll' && k === 'updateRailActive'))
  assert.ok(stubs.fired.some(([t, k]) => t === 'scroll' && k === 'scrollForce'))
})

test('其余绑定：管理智能体跳转、簇图标、图片预览、计划跳转、focusInput', async () => {
  const { ctx, pushes, calls, stubs } = makeCtx()
  const api = useChatChrome(ctx)
  ctx.agentPickerOpen.value = true
  api.goManageAgents()
  assert.equal(ctx.agentPickerOpen.value, false)
  assert.deepEqual(pushes, ['/agents'])
  assert.equal(api.clusterIconOf({ kind: 'search' }), 'S')
  assert.equal(api.clusterIconOf({ kind: 'other' }), 'B')

  api.openPreviewFromMsg({ images: ['u1', 'u2'] }, 1)
  assert.deepEqual(calls.openImages[0], [['u1', 'u2'], 1])
  api.jumpToPlan(3)
  assert.ok(stubs.fired.some(([t, k, a]) => t === 'source' && k === 'jumpToPlanRow' && a[0] === 3))
  api.openPlanFromRail({ mi: 0 })
  assert.equal(calls.openPlanViewer.length, 0)
  ctx.messages = () => [{ role: 'ai', planCard: {} }]
  const api2 = useChatChrome(ctx)
  api2.openPlanFromRail({ mi: 0 })
  assert.equal(calls.openPlanViewer.length, 1)

  let focused = 0
  api.composerRef.value = { ...composerStubs, focus: () => { focused++ } }
  api.focusInput()
  await nextTick()
  assert.equal(focused, 1)
})
