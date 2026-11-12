// 聊天页外壳：右栏开合、深度思考/滚动/浮层/来源联动四组 hook 的接线、
// 输入框 chip、反馈、Esc 停止、全局键盘与窗口监听、粘贴与附件入口。
// 纯判据在 chatChrome.js；引擎值、动作、DOM 与现成 hook 全部由 ctx 注入。
import { ref, reactive, computed, watch, nextTick, getCurrentInstance, onMounted, onUnmounted } from 'vue'
import { GOAL_KEY, OVERLAY_ROOT_CLASSES, MENTION_TIP,
         fileKeyOf, imgKeyOf, menKeyOf, skillKeyOf, histKeyOf,
         questionForIndex, processMsgOf, composerPlaceholderOf, anchorQOf, anchorAOf,
         panelOpenOf, panelStoredOf, hitsOverlayPath, inPanelPath, globalKeydownIntent,
         desiredChipKeys, chipDescriptorOf, prunedChipKeys } from './chatChrome.js'
import { buildQaAnchors } from './chatAnchors.js'
import { buildSessionPlans } from './planPanel.js'
import { planStepCount } from './plan.js'
import { MAX_FILES } from './intake.js'
import { useThinkPanel as _useThinkPanel } from './useThinkPanel.js'
import { useChatScroll as _useChatScroll } from './useChatScroll.js'
import { useChatPanels as _useChatPanels } from './useChatPanels.js'
import { useSourceFocus as _useSourceFocus } from './useSourceFocus.js'

export const useChatChrome = ctx => {
  const isRef = v => !!v && typeof v === 'object' && 'value' in v
  const read = v => isRef(v) ? v.value : (typeof v === 'function' ? v() : v)
  const write = (v, next) => { if (isRef(v)) v.value = next }
  // 只读引擎值允许 getter 或 ref；watch 源两种形状都能直接用。
  const asRef = (v, fallback) => isRef(v) ? v
    : (typeof v === 'function' ? computed(v) : ref(v === undefined ? fallback : v))
  const asSource = v => isRef(v) ? v : (typeof v === 'function' ? v : ref(v))

  const win = ctx.win || (typeof window !== 'undefined' ? window
    : { innerWidth: 0, innerHeight: 0, addEventListener () {}, removeEventListener () {} })
  const doc = ctx.doc || (typeof document !== 'undefined' ? document
    : { documentElement: { style: { getPropertyValue: () => '', setProperty: () => {} } },
        activeElement: null, querySelector: () => null,
        addEventListener () {}, removeEventListener () {} })
  const storage = ctx.storage || (typeof localStorage !== 'undefined' ? localStorage
    : { getItem: () => null, setItem: () => {} })
  const schedule = ctx.setTimeout || setTimeout
  const cancel = ctx.clearTimeout || clearTimeout

  // 提示入口：优先取带分级方法的对象；ctx 只给一个函数时四级共用它。
  const rawNotify = ctx.message || ctx.notify
  const notify = typeof rawNotify === 'function'
    ? { warning: rawNotify, info: rawNotify, success: rawNotify, error: rawNotify }
    : (rawNotify || { warning: () => {}, info: () => {}, success: () => {}, error: () => {} })
  const submitFeedback = (...a) => {
    const fn = ctx.apiSubmitFeedback || (ctx.api && ctx.api.submitFeedback)
    return fn ? fn(...a) : Promise.resolve({ success: false, msg: '提交失败' })
  }
  const oneLine = ctx.oneLine || ((s, n) => String(s || '').replace(/\s+/g, ' ').slice(0, n))
  const maxFiles = read(ctx.MAX_FILES) ?? MAX_FILES
  const viewport = () => ({ w: win.innerWidth || 0, h: win.innerHeight || 0 })
  const styleOf = ctx.styleOf || (typeof getComputedStyle === 'function'
    ? (el => getComputedStyle(el)) : (() => ({ paddingTop: '0px' })))

  // 现成 hook 可由 ctx 覆盖；无覆盖时用同目录实现。
  const useThinkPanel = ctx.useThinkPanel || _useThinkPanel
  const useChatScroll = ctx.useChatScroll || _useChatScroll
  const useChatPanels = ctx.useChatPanels || _useChatPanels
  const useSourceFocus = ctx.useSourceFocus || _useSourceFocus

  const router = ctx.router
  const route = ctx.route

  // 深度执行是否已上膛（还没发出去）：靠输入框里那颗 chip 表示，× 退出。
  // ctx 给了 ref 就共用同一份状态，否则本 composable 自持一份。
  const goalArmed = isRef(ctx.goalArmed) ? ctx.goalArmed : ref(!!read(ctx.goalArmed))
  const goalStarting = isRef(ctx.goalStarting) ? ctx.goalStarting : ref(!!read(ctx.goalStarting))

  // 输入框 DOM ref 由布局层持有并共享：命令面板要在同一份元素上取光标位置。
  const composerRef = ctx.composerRef || ref(null)
  const inlineKeys = ref([])
  const box = ref(null)

  const clusterIconOf = seg => {
    const icons = ctx.CLUSTER_ICONS || {}
    const kind = ctx.clusterHeadKind ? ctx.clusterHeadKind(seg) : ''
    return icons[kind] || ctx.BulbOutlined
  }
  const questionFor = i => questionForIndex(read(ctx.messages), i)

  const goManageAgents = () => {
    write(ctx.agentPickerOpen, false)
    router?.push('/agents')
  }

  const panelStoredRaw = (() => { try { return storage.getItem('app_panel') } catch (e) { return null } })()
  const panelStored = ref(panelStoredOf(panelStoredRaw, win.innerWidth || 0))
  const panelOpen = computed(() => panelOpenOf(!!read(ctx.isNarrow), panelStored.value))
  const mPanelOpen = ref(false)
  const togglePanel = () => {
    if (read(ctx.isNarrow)) { mPanelOpen.value = !mPanelOpen.value; return }
    panelStored.value = !panelStored.value
    try { storage.setItem('app_panel', panelStored.value ? '1' : '0') } catch (e) { /* 忽略：本次会话内仍然可用 */ }
  }

  const processMsg = computed(() => processMsgOf(read(ctx.messages)))
  const qaAnchors = computed(() => (ctx.buildQaAnchors || buildQaAnchors)(read(ctx.messages) || []))
  const anchorQ = a => anchorQOf(a, oneLine)
  const anchorA = a => anchorAOf(a, oneLine)
  const sessionPlans = computed(() => (ctx.buildSessionPlans || buildSessionPlans)(
    read(ctx.messages) || [], ctx.planStepCount || planStepCount))

  const {
    thinkHoverRef, thinkPanelPos, thinkPanelEl, mThinkOpen, thinkHoverModel, thinkPanelVisible,
    toggleThinkSheet, onDocPointerDown, thinkPanelCaps, thinkPanelLevelOptions, thinkPanelCurrentLevel,
    thinkPanelStrengthText, thinkPanelFoot, pillLevelRef, pillLevelBadge, pillLevelTitle, thinkPanelCtxText,
    onModelOptionHover, scheduleThinkHide, onThinkPanelEnter, onThinkPanelLeave, onModelSelectOpenChange
  } = useThinkPanel({
    isCoarse: asRef(ctx.isCoarse, false),
    effectiveModel: asRef(ctx.effectiveModel, ''),
    currentOverrideModel: asRef(ctx.currentOverrideModel, ''),
    modelIndex: asRef(ctx.modelIndex, {}),
    thinkCapsOf: ctx.thinkCapsOf || (() => ({ visible: false, locked: false })),
    levelOptionsOf: ctx.levelOptionsOf || (() => []),
    currentLevelOf: ctx.currentLevelOf || (() => ''),
    deepOnOf: ctx.deepOnOf || (() => false),
    fmtWindow: ctx.fmtWindow || (() => ''),
    viewport, schedule, cancel
  })

  const {
    stickToBottom, tailSpacer, onMessagesScroll, scroll, scrollForce, updateTailSpacer,
    railVis, railHoverIdx, railPopStyle, railHoverAnchor, showAnchor, gotoAnchor, railActiveIdx, updateRailActive
  } = useChatScroll({
    box,
    messagesOf: () => read(ctx.messages),
    qaAnchorsOf: () => qaAnchors.value,
    styleOf
  })

  const {
    eventsOpen, eventsTurn, eventsMsg, eventsQuestion, eventsTitle, openEvents,
    srcOpenOf, toggleSrc, ctxCapOpen, ctxCapPos, ctxCapEl,
    showCtxCap, hideCtxCap, onCtxCapEnter, onCtxCapLeave
  } = useChatPanels({ viewport, schedule, cancel })

  const { hoverSource, unhoverSource, locateSource, jumpToPlanRow } = useSourceFocus({
    hoveredRef: asRef(ctx.hoveredRef, null),
    doc, schedule,
    messagesOf: () => read(ctx.messages),
    lastAiOf: () => read(ctx.lastAi)
  })

  const jumpToPlan = mi => jumpToPlanRow(mi)
  const openPlanFromRail = p => {
    const m = (read(ctx.messages) || [])[p.mi]
    if (!m || !m.planCard) return
    ctx.openPlanViewer?.(m, p.mi)
  }

  const focusSpawn = ({ run, msg }) => {
    const t = run && run.t
    if (!t) return
    ctx.openDispatchCard?.(msg, t)
    const anchor = ctx.spawnAnchorOf ? ctx.spawnAnchorOf(t) : t
    const el = box.value && box.value.querySelector('[data-spawn="' + anchor + '"]')
    if (el) el.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }
  const focusHitl = kind => {
    if (kind === 'approval') {
      const el = box.value && box.value.querySelector('[data-approval-card]')
      if (el) el.scrollIntoView({ block: 'center', behavior: 'smooth' })
      return
    }
    const panel = doc.querySelector('.askp-box, .planp-box')
    if (!panel) return
    panel.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
    const first = panel.querySelector('.askp-opt:not([disabled]), .planp-opt:not([disabled])')
    if (first) first.focus()
  }
  // 通知深链落到审批卡：滚到那一张并闪一圈，不新增常驻 UI。
  const revealApprovalCard = id => {
    nextTick(() => {
      const key = String(id || '')
      const cssLib = ctx.CSS || (typeof CSS !== 'undefined' ? CSS : null)
      const safe = (cssLib && cssLib.escape) ? cssLib.escape(key) : key
      const el = box.value && box.value.querySelector('[data-approval-id="' + safe + '"]')
      if (!el) return
      el.scrollIntoView({ block: 'center', behavior: 'smooth' })
      el.classList.remove('flash')
      void el.offsetWidth
      el.classList.add('flash')
      schedule(() => el.classList.remove('flash'), 1800)
    })
  }

  const disclaimerVisible = ref(false)
  const DISCLAIMER_TEXT = `
### 一、内容生成方式

本系统的回答由人工智能模型基于知识库检索结果**自动生成**，仅供学习与工作参考，不构成任何形式的专业建议（包括但不限于法律、医疗、财务、投资建议），亦不代表系统开发方与运营方的官方立场。

### 二、准确性不作保证

AI 生成内容可能存在**错误、遗漏、过时或与实际情况不符**之处。知识库内容可能更新滞后，回答引用的资料版本可能与最新版本存在差异。重要信息（如操作规范、数据口径、流程要求、审批条件等）请以**官方文档、正式通知或相关业务部门的确认为准**。

### 三、引用来源说明

回答中标注的引用来源仅用于帮助定位参考资料。受文档分块与摘要机制影响，展示的片段可能与原文存在出入，完整含义请以知识块原文及原始文档为准。

### 四、使用限制

请勿仅依赖本系统的回答做出对个人或组织有重大影响的决策。因使用或依赖本系统内容而产生的任何直接或间接损失，系统提供方不承担责任。请遵守信息安全相关规定，**不要在提问中输入密码、密钥、客户隐私等敏感信息**。

### 五、反馈与改进

如发现回答有误或内容不当，可通过回答下方的反馈按钮告知我们，帮助我们持续改进。`

  const feedbackVisible = ref(false)
  const feedbackSubmitting = ref(false)
  const feedbackText = ref('')
  const feedbackTarget = ref(null)
  const openFeedback = (m, rating) => {
    if (m.fb != null && m.fb !== rating) {
      notify.warning(`已评价为「${m.fb === 1 ? '有帮助' : '没帮助'}」，同一回答只能选择一项`)
      return
    }
    feedbackTarget.value = { msg: m, rating }
    feedbackText.value = ''
    feedbackVisible.value = true
  }
  const doSubmitFeedback = async () => {
    const t = feedbackTarget.value
    if (!t || !t.msg.messageId) { notify.warning('该回答不可反馈'); return }
    feedbackSubmitting.value = true
    try {
      const r = await submitFeedback(t.msg.messageId, t.rating, feedbackText.value.trim())
      if (r.success) { t.msg.fb = t.rating; notify.success('感谢反馈'); feedbackVisible.value = false }
      else notify.error(r.msg || '提交失败')
    } catch (e) { notify.error(e.message || '提交失败') }
    finally { feedbackSubmitting.value = false }
  }

  // 生成中按第一次 Esc 只上膛，2s 内再按才真正停止，超时自动回落。
  const escArmed = ref(false)
  let escArmTimer = null
  const armEsc = () => {
    escArmed.value = true
    cancel(escArmTimer)
    escArmTimer = schedule(() => { escArmed.value = false }, 2000)
  }
  const disarmEsc = () => {
    escArmed.value = false
    cancel(escArmTimer)
  }
  const stopNow = () => { disarmEsc(); ctx.stop?.() }

  const composerPlaceholder = computed(() => composerPlaceholderOf({
    loading: !!read(ctx.loading),
    goalTurnStreaming: !!read(ctx.goalTurnStreaming),
    isNarrow: !!read(ctx.isNarrow),
    goalArmed: goalArmed.value,
    hasSessionGoal: !!read(ctx.sessionGoal)
  }))
  const composerFocused = () => {
    const el = composerRef.value && composerRef.value.rootEl
    return !!(el && (doc.activeElement === el || el.contains?.(doc.activeElement)))
  }
  const onGlobalKeydown = e => {
    const intent = globalKeydownIntent(e, {
      loading: !!read(ctx.loading),
      previewOpen: !!read(ctx.previewUrl),
      escArmed: escArmed.value,
      composerFocused: composerFocused(),
      hasText: !!read(ctx.text)
    })
    if (intent === 'stop') stopNow()
    else if (intent === 'arm') armEsc()
    else if (intent === 'clear') write(ctx.text, '')
    else if (intent === 'blur') doc.activeElement?.blur?.()
  }
  const onGlobalPaste = e => ctx.takePastedFiles?.(e)

  // 消息区滚动条槽宽写入 --chat-sbw，供输入框右内边距补偿；覆盖式滚动条槽宽为 0。
  const syncScrollbarGutter = () => {
    const el = box.value
    if (!el) return
    const gutter = el.offsetWidth - el.clientWidth
    const host = (el.closest && el.closest('.chat2')) || doc.documentElement
    const next = gutter > 0 ? gutter + 'px' : '0px'
    if (host.style.getPropertyValue('--chat-sbw') !== next) host.style.setProperty('--chat-sbw', next)
  }
  const onWindowResize = () => {
    updateTailSpacer()
    if (stickToBottom.value) scroll()
    syncScrollbarGutter()
  }
  const onOrientationChange = () => { schedule(onWindowResize, 120) }

  const focusInput = () => nextTick(() => composerRef.value?.focus())
  const openPreviewFromMsg = (m, index) => {
    const resolve = ctx.resolveImg || (u => u)
    ctx.openImages?.((m.images || []).map(resolve), index || 0)
  }
  const onDropFiles = files => ctx.addFiles?.(files)
  // 编辑区一律拦掉浏览器默认粘贴（外部 HTML 会连样式一起搬进正文），正文这步自己插。
  const onComposerPaste = e => {
    const done = () => e.stopPropagation()
    if (ctx.takePastedFiles?.(e) || ctx.takePastedText?.(e)) return done()
    const cd = e.clipboardData
    const plain = (cd && cd.getData ? cd.getData('text/plain') : '') || ''
    const html = (cd && cd.getData ? cd.getData('text/html') : '') || ''
    if (html.indexOf('data-wenqu-kind') >= 0) restoreWenquChips(html)
    if (plain) composerRef.value?.insertText(plain)
    done()
  }
  const pasteView = reactive({ open: false, name: '', text: '' })
  const openPasteView = a => {
    if (!a) return
    if (!a.text) { notify.info('这条粘贴文本的正文未随历史记录保留，无法回看'); return }
    pasteView.name = a.name
    pasteView.text = a.text
    pasteView.open = true
  }
  const attachInput = ref(null)
  const pickAttachments = () => {
    if ((read(ctx.pendingFiles) || []).length >= maxFiles) {
      notify.warning(`一次最多上传 ${maxFiles} 个附件`)
      return
    }
    attachInput.value?.click()
  }
  // 选完即收面板：面板正盖着输入框上方的附件 chip，收起来让用户看到已加上。
  const onAttachChange = e => {
    const files = Array.from(e.target.files || [])
    e.target.value = ''
    if (!files.length) return
    ctx.addFiles?.(files)
    ctx.closeAddPanel?.()
  }

  // 敲 @ 唤起「+」面板并停在「引用」页；只能逐个关其它面板，避免抹掉触发位置。
  const openMentionPanel = () => {
    write(ctx.slashOpen, false)
    write(ctx.histOpen, false)
    write(ctx.addTarget, 'main')
    write(ctx.addTab, 'ref')
    write(ctx.addOpen, true)
    write(ctx.addHi, 0)
    write(ctx.addFromRoot, false)
    write(ctx.mentionQuery, '')
    ctx.ensureRefCandidates?.()
  }
  const hitsOverlay = path => hitsOverlayPath(path, OVERLAY_ROOT_CLASSES)
  // 点面板/输入区之外关闭；composedPath 含换层前的旧祖先链，用它认面板。
  const onDocClickForMention = e => {
    if (!read(ctx.slashOpen) && !read(ctx.histOpen) && !read(ctx.addOpen)) return
    const path = typeof e.composedPath === 'function' ? e.composedPath() : []
    if (hitsOverlay(path)) return
    if (inPanelPath(path)) return
    ctx.closeAllPanels?.()
  }

  const desiredKeys = () => desiredChipKeys({
    goalModeOn: !!read(ctx.goalModeOn),
    goalArmed: goalArmed.value,
    hasSessionGoal: !!read(ctx.sessionGoal),
    files: read(ctx.pendingFiles) || [],
    images: read(ctx.pendingImages) || [],
    mentions: read(ctx.pendingMentions) || [],
    skills: read(ctx.pickedSkills) || [],
    histRefs: read(ctx.pendingHistoryRefs) || []
  })
  const chipOf = key => chipDescriptorOf(key, {
    files: read(ctx.pendingFiles) || [],
    images: read(ctx.pendingImages) || [],
    mentions: read(ctx.pendingMentions) || [],
    skills: read(ctx.pickedSkills) || [],
    histRefs: read(ctx.pendingHistoryRefs) || []
  }, {
    isPastedText: ctx.isPastedText, pasteTitle: ctx.pasteTitle, pasteSub: ctx.pasteSub,
    fmtSize: ctx.fmtSize, histChipLabel: ctx.histChipLabel
  })
  // 只剪「上一次在 DOM 里、这一次不在了」的：程序化新增的条目 DOM 还没同步，全量比会误剪。
  const onKeys = keys => {
    const { removed, goalOff } = prunedChipKeys(keys, inlineKeys.value)
    inlineKeys.value = keys
    if (!removed.length) return
    const gone = new Set(removed)
    const hit = k => gone.has(k)
    const prune = (r, keyOf) => {
      if (isRef(r) && r.value.some(x => hit(keyOf(x)))) r.value = r.value.filter(x => !hit(keyOf(x)))
    }
    prune(ctx.pendingFiles, fileKeyOf)
    prune(ctx.pendingImages, imgKeyOf)
    prune(ctx.pendingMentions, menKeyOf)
    prune(ctx.pendingHistoryRefs, histKeyOf)
    prune(ctx.pickedSkills, skillKeyOf)
    if (goalOff) goalArmed.value = false
  }
  const syncChips = () => {
    const cp = composerRef.value
    if (!cp) return
    cp.sync(desiredKeys())
    cp.repaint()
    inlineKeys.value = cp.domKeys()
  }
  const onChipClick = key => {
    if (key === GOAL_KEY) { focusInput(); return }
    const d = chipOf(key)
    if (d && d.kind === 'file' && d.paste) {
      openPasteView((read(ctx.pendingFiles) || []).find(x => fileKeyOf(x) === key))
    }
  }
  // 粘回自己复制出去的一轮：只认 data-wenqu-* 属性，正文仍取 text/plain。
  const restoreWenquChips = html => {
    const Parser = ctx.DOMParser || (typeof DOMParser !== 'undefined' ? DOMParser : null)
    if (!Parser) return false
    let pasted = null
    try { pasted = new Parser().parseFromString(html, 'text/html') } catch (e) { return false }
    const nodes = Array.from(pasted.querySelectorAll('[data-wenqu-kind]'))
    if (!nodes.length) return false
    let n = 0
    for (const el of nodes) {
      const k = el.getAttribute('data-wenqu-kind')
      if (k === 'file' && ctx.attachUploaded?.({
        name: el.getAttribute('data-wenqu-name'), mime: el.getAttribute('data-wenqu-mime'),
        size: Number(el.getAttribute('data-wenqu-size') || 0), fileId: el.getAttribute('data-wenqu-file-id')
      })) n++
      else if (k === 'img' && ctx.attachImage?.(el.getAttribute('data-wenqu-dataurl'))) n++
      else if (k === 'mention') {
        const type = el.getAttribute('data-wenqu-mtype')
        const id = el.getAttribute('data-wenqu-id')
        const name = el.getAttribute('data-wenqu-name')
        if (type && id && !ctx.isMentioned?.(type, id)) { ctx.toggleMention?.(type, { id, name, fileName: name }); n++ }
      } else if (k === 'skill') {
        const name = el.getAttribute('data-wenqu-name')
        if (name && !(read(ctx.pickedSkills) || []).includes(name)) { ctx.toggleSkill?.(name); n++ }
      } else if (k === 'hist') {
        const id = el.getAttribute('data-wenqu-id')
        const hit = id && (read(ctx.histPool) || []).find(m => String(m.messageId) === String(id))
        if (hit && !ctx.isHistPicked?.(hit.messageId)) { ctx.toggleHistoryRef?.(hit); n++ }
      }
    }
    return n > 0
  }

  const todoOpen = ref(false)
  const srcCardOpen = ref(true)

  // 副作用只在组件 setup 内注册：无实例时不挂全局监听、不排卸载回调。
  const inSetup = !!getCurrentInstance()
  const onMount = inSetup ? onMounted : () => {}
  const onUnmount = inSetup ? onUnmounted : () => {}

  watch(qaAnchors, () => nextTick(updateRailActive), { deep: false })
  watch(() => read(ctx.messages), () => nextTick(() => { updateTailSpacer(); scrollForce() }))
  watch([
    asSource(ctx.pendingFiles), asSource(ctx.pendingImages), asSource(ctx.pendingMentions),
    asSource(ctx.pickedSkills), asSource(ctx.pendingHistoryRefs), asSource(ctx.planModeOn),
    asSource(ctx.goalModeOn), goalArmed, asSource(ctx.goalBarVisible),
    () => read(ctx.sessionGoal)?.status
  ], syncChips, { deep: true })
  onMount(() => doc.addEventListener('click', onDocClickForMention))
  onUnmount(() => {
    win.removeEventListener('keydown', onGlobalKeydown)
    win.removeEventListener('paste', onGlobalPaste)
    win.removeEventListener('resize', onWindowResize)
    win.removeEventListener('orientationchange', onOrientationChange)
    doc.removeEventListener('pointerdown', onDocPointerDown)
    cancel(escArmTimer)
    ctx.stopTick?.()
  })
  onUnmount(() => doc.removeEventListener('click', onDocClickForMention))

  return {
    router, route,
    clusterIconOf, questionFor,
    goalArmed, goalStarting, composerRef, inlineKeys, box,
    thinkHoverRef, thinkPanelPos, thinkPanelEl, mThinkOpen, thinkHoverModel, thinkPanelVisible,
    toggleThinkSheet, onDocPointerDown, thinkPanelCaps, thinkPanelLevelOptions, thinkPanelCurrentLevel,
    thinkPanelStrengthText, thinkPanelFoot, pillLevelRef, pillLevelBadge, pillLevelTitle, thinkPanelCtxText,
    onModelOptionHover, scheduleThinkHide, onThinkPanelEnter, onThinkPanelLeave, onModelSelectOpenChange,
    goManageAgents,
    stickToBottom, tailSpacer, onMessagesScroll, scroll, scrollForce, updateTailSpacer,
    railVis, railHoverIdx, railPopStyle, railHoverAnchor, showAnchor, gotoAnchor, railActiveIdx, updateRailActive,
    panelStoredRaw, panelStored, panelOpen, mPanelOpen, togglePanel, processMsg,
    focusSpawn, focusHitl, revealApprovalCard,
    eventsOpen, eventsTurn, eventsMsg, eventsQuestion, eventsTitle, openEvents,
    srcOpenOf, toggleSrc, ctxCapOpen, ctxCapPos, ctxCapEl, showCtxCap, hideCtxCap, onCtxCapEnter, onCtxCapLeave,
    sessionPlans, jumpToPlan, openPlanFromRail,
    disclaimerVisible, DISCLAIMER_TEXT,
    hoverSource, unhoverSource, locateSource, jumpToPlanRow,
    feedbackVisible, feedbackSubmitting, feedbackText, feedbackTarget, openFeedback, doSubmitFeedback,
    escArmed, escArmTimer, armEsc, disarmEsc, stopNow, composerPlaceholder,
    onGlobalKeydown, onGlobalPaste, syncScrollbarGutter, onWindowResize, onOrientationChange,
    focusInput, openPreviewFromMsg, onDropFiles, onComposerPaste,
    pasteView, openPasteView, attachInput, pickAttachments, onAttachChange,
    openMentionPanel, OVERLAY_ROOT_CLASSES, hitsOverlay, onDocClickForMention,
    qaAnchors, anchorQ, anchorA,
    GOAL_KEY, fileKeyOf, imgKeyOf, menKeyOf, skillKeyOf, histKeyOf, MENTION_TIP,
    desiredKeys, chipOf, onKeys, syncChips, onChipClick, restoreWenquChips,
    todoOpen, srcCardOpen
  }
}
