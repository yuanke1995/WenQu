// 会话动作出口：深度执行、插话排序、查找/分享、复制与导出、固化技能、删除与调试。
// 纯判据在 chatActions.js；引擎值、提示、动作与 hooks 由 ctx 注入。
import { ref, reactive, computed, watch, onMounted, onUnmounted, getCurrentInstance, h } from 'vue'
import { parseDeepCommand } from './deepCommand.js'
import {
  steerDropTarget, steerExtrasText, hitsNoteText, findQuestion, evalKnowledgeIds,
  moreActionPlan, isSessionShared, solidifyNameValid, solidifySlug as makeSlug,
  seedSolidifyForm, solidifyPayload, searchHotkeyIntent, docEscShouldClose,
  panelNavIntent, panelTriggerChar, projectDebugStages, deepCommandIntent
} from './chatActions.js'

export const useChatActions = ctx => {
  const notify = ctx.notify || { info() {}, warning() {}, success() {}, error() {} }
  const api = ctx.api || ctx
  const hitsOverlay = ctx.hitsOverlay || (() => false)
  const domDocument = ctx.document || (typeof document !== 'undefined' ? document : undefined)
  const domWindow = ctx.window || (typeof window !== 'undefined' ? window : undefined)
  const HTMLElementCtor = ctx.HTMLElement || (typeof HTMLElement !== 'undefined' ? HTMLElement : class {})

  // ==================== 深度执行入口 ====================
  /** 上膛统一出口：开启时收掉计划标记，关闭不涉及冲突。 */
  const armGoal = v => {
    ctx.setGoalArmed(!!v)
    if (v && ctx.planModeOn()) ctx.setPlanMode(false)
  }

  /** 发送时兜底解析 /deep、/深度：面板没开或直接敲命令时也能回车即执行。 */
  const inlineSlash = () => {
    const intent = deepCommandIntent(parseDeepCommand(ctx.text()), ctx.goalModeOn(), ctx.goalArmed())
    if (!intent.matched) return false
    if (intent.kind === 'unsupported') { notify.info('这个助手没有开启深度执行'); return true }
    ctx.setText('')
    if (intent.kind === 'start') ctx.startGoalNow(intent.task)
    else armGoal(intent.armed)
    return true
  }

  const sendNow = async () => {
    if (ctx.foreignWorkspace()) {
      notify.warning('这个工作区属于另一台电脑：请在那台电脑上使用（侧栏「这台电脑」可查看当前设备）')
      return
    }
    if (inlineSlash()) { ctx.closeAllPanels(); return }
    if (ctx.goalModeOn() && ctx.goalArmed() && !ctx.sessionGoal() && String(ctx.text() || '').trim()) {
      ctx.setGoalArmed(false)
      if (ctx.planModeOn()) ctx.setPlanMode(false)
      ctx.closeAllPanels()
      const g = await ctx.startGoalNow()
      if (!g) ctx.setGoalArmed(true)
      return
    }
    if (ctx.goalStarting()) return
    ctx.closeAddPanel()
    // 生成中的「发一句」由 sendFlow 自己判定：插话当场送达，不在这里再分两档
    ctx.send()
  }

  // ==================== 插话队列拖动排序 ====================
  const steerDragIdx = ref(-1)
  const steerDragOver = ref(-1)
  const onSteerDragStart = (i, e) => {
    steerDragIdx.value = i
    try { e.dataTransfer.setData('text/plain', String(i)); e.dataTransfer.effectAllowed = 'move' } catch (err) { /* 忽略 */ }
  }
  const onSteerDragOver = i => { steerDragOver.value = i }
  const onSteerDragEnd = () => { steerDragIdx.value = -1; steerDragOver.value = -1 }
  const onSteerDrop = () => {
    const target = steerDropTarget(steerDragIdx.value, steerDragOver.value)
    if (target) ctx.moveSteer(target.from, target.to)
    onSteerDragEnd()
  }
  const steerExtras = s => steerExtrasText(s)

  // ==================== 会话内查找 ====================
  const {
    searchOpen, searchQuery, searchPos, searchInputRef, matchedIdxs, searchPosShown,
    openSearch, closeSearch, gotoMatch, paintHighlight: paintSearchHighlight, scrollToMatch
  } = ctx.useChatSearch({
    boxRef: ctx.box,
    messages: () => ctx.messages()
  })
  const onSearchHotkey = e => {
    const intent = searchHotkeyIntent(e, searchOpen.value)
    if (intent === 'focus') { e.preventDefault(); searchInputRef.value?.focus?.() }
    else if (intent === 'open') { e.preventDefault(); openSearch() }
    else if (intent === 'close') closeSearch()
  }

  // ==================== 全局 Esc 兜底 ====================
  const onDocEscKeydown = e => {
    const path = typeof e.composedPath === 'function' ? e.composedPath() : (Array.isArray(e.path) ? e.path : [])
    const shouldClose = docEscShouldClose(e, {
      panelsOpen: ctx.slashOpen() || ctx.histOpen() || ctx.addOpen(),
      overlayHit: hitsOverlay,
      path,
      isHTMLElement: n => n instanceof HTMLElementCtor
    })
    if (!shouldClose) return
    e.preventDefault()
    ctx.closeAllPanels()
    ctx.focusInput()
  }

  // 仅在组件 setup 内注册文档监听；无组件实例时不注册，处理器仍可独立调用。
  if (getCurrentInstance()) {
    onMounted(() => domDocument?.addEventListener?.('keydown', onSearchHotkey))
    onUnmounted(() => domDocument?.removeEventListener?.('keydown', onSearchHotkey))
    onMounted(() => domDocument?.addEventListener?.('keydown', onDocEscKeydown))
    onUnmounted(() => domDocument?.removeEventListener?.('keydown', onDocEscKeydown))
  }

  // ==================== 会话分享 ====================
  const shareVisible = ref(false)
  const {
    loading: shareLoading, info: shareInfo, url: shareUrl,
    load: loadShare, enable: enableShare, disable: stopShare, copyLink: copyShareLink
  } = ctx.useSessionShare({ sessionId: () => ctx.currentSessionId() })
  const shareActive = computed(() => isSessionShared(ctx.sessionStore.list, ctx.currentSessionId()))
  const openShare = async () => {
    if (!ctx.messages().length) { notify.info('当前会话还没有内容可分享'); return }
    shareVisible.value = true
    await loadShare()
  }
  const consumeShareOpen = async () => {
    for (let i = 0; i < 40 && !ctx.messages().length; i++) await new Promise(r => setTimeout(r, 50))
    await openShare()
  }
  watch(() => ctx.sessionStore.shareOpenTick, () => { if (ctx.route.path === '/chat') consumeShareOpen() })
  const doEnableShare = () => enableShare(false)
  const regenerateShareLink = () => enableShare(true)
  const doDisableShare = () => stopShare()
  const openSharedPage = () => { if (shareUrl.value) domWindow?.open?.(shareUrl.value, '_blank', 'noopener') }

  // ==================== 面板与输入框键盘 ====================
  const onPanelNavKeydown = e => {
    const plan = panelNavIntent(e, {
      addOpen: ctx.addOpen(), addTab: ctx.addTab(), addFromRoot: ctx.addFromRoot(), addHi: ctx.addHi()
    })
    if (!plan) return false
    if (plan.kind === 'move') { e.preventDefault(); ctx.movePanelHi(plan.delta); return true }
    if (plan.kind === 'back') { e.preventDefault(); ctx.backToRoot(); return true }
    if (plan.kind === 'close') { e.preventDefault(); ctx.closeAllPanels(); ctx.focusPanelOwner(); return true }
    if (plan.kind === 'confirmAdd') { e.preventDefault(); ctx.confirmAddHi(plan.hi); return true }
    if (plan.kind === 'confirmHi') {
      if (ctx.confirmPanelHi()) { e.preventDefault(); return true }
      return false
    }
    return false
  }

  const onInputKeydown = e => {
    if (e.isComposing || e.keyCode === 229) return
    if (ctx.slashOpen() || ctx.histOpen() || ctx.addOpen()) {
      // 面板导航可由调用方注入唯一实现；未注入时用本 composable 的等价版本。
      const nav = ctx.onPanelNavKeydown || onPanelNavKeydown
      if (nav(e)) return
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault(); sendNow(); return
    }
    if (e.key === '@' || e.key === '#' || e.key === '/') {
      const pos = ctx.composerCaret()
      const before = ctx.text().slice(0, pos)
      const ch = panelTriggerChar(e, before)
      if (ch) {
        ctx.setPanelTrigger(pos, ch)
        if (ch === '@') ctx.openMentionPanel()
        else if (ch === '#') ctx.openHistPanel()
        else ctx.openSlashPanel()
      }
    }
  }

  const hitsNote = m => hitsNoteText(m)

  // ==================== 复制与更多动作 ====================
  const copyAnswer = async mi => {
    const m = ctx.messages()[mi]
    if (!m || !m.content) { notify.warning('该回答无可复制内容'); return }
    await ctx.copyText(m.content.trim())
  }
  const copyUserMessage = m => {
    const txt = (m?.content || '').trim()
    if (!txt) { notify.warning('该消息无可复制内容'); return }
    ctx.copyText(txt, '已复制')
  }
  const onMoreAction = (key, mi) => {
    const plan = moreActionPlan(key, mi, ctx.messages())
    if (!plan) return
    if (plan.kind === 'debug') openDebug(plan.mi)
    else if (plan.kind === 'addEval') addToEval(plan.mi)
    else if (plan.kind === 'deleteRound') deleteRound(plan.mi)
    else if (plan.kind === 'export') exportRound(plan.mi)
    else if (plan.kind === 'events') ctx.openEvents(plan.turnId, plan.ai, plan.question)
  }
  const addToEval = async mi => {
    const kids = evalKnowledgeIds(ctx.messages()[mi])
    if (!kids.length) { notify.warning('本轮没有可固化的知识块引用'); return }
    const question = findQuestion(ctx.messages(), mi)
    if (!question?.content) { notify.warning('找不到本轮对应的提问'); return }
    try {
      const r = await api.addEvalCase(question.content, kids)
      if (r && r.success !== false) {
        if (r.data?.added === false) notify.info(r.data.reason || '评估集已存在相同问题')
        else notify.success('已加入评测集')
      } else notify.error(r?.msg || '加入评测集失败')
    } catch (e) { notify.error(e.message || '加入评测集失败') }
  }

  // ==================== 固化为技能 ====================
  const solidifyOpen = ref(false)
  const solidifyBusy = ref(false)
  const solidifyForm = reactive({ name: '', description: '', content: '' })
  const gotoSkillsPage = () => ctx.router.push({ path: '/agents', query: { tab: 'skills' } })
  const solidifySlug = makeSlug
  const openSolidify = mi => {
    const m = ctx.messages()[mi]
    if (!m || !m.content) { notify.warning('这一轮还没有可固化的回答'); return }
    const question = findQuestion(ctx.messages(), mi)
    const seed = seedSolidifyForm(String(question?.content || ''))
    solidifyForm.name = seed.name
    solidifyForm.description = seed.description
    solidifyForm.content = seed.content
    solidifyOpen.value = true
  }
  const submitSolidify = async () => {
    const name = String(solidifyForm.name || '').trim()
    if (!solidifyNameValid(name)) { notify.warning('给技能起个名字'); return }
    if (solidifyBusy.value) return
    solidifyBusy.value = true
    try {
      await api.createSkill(solidifyPayload(solidifyForm))
      solidifyOpen.value = false
      ctx.loadSkills()
      notify.success(h('span', null, ['已创建技能「' + name + '」，',
        h('a', { onClick: gotoSkillsPage }, '去技能页查看')]), 5)
    } catch (e) {
      notify.error(e.message || '创建技能失败')
    } finally {
      solidifyBusy.value = false
    }
  }

  // ==================== 单轮导出与删除 ====================
  const exportRound = mi => {
    const m = ctx.messages()[mi]
    if (!m || !m.content) { notify.warning('该回答无可导出内容'); return }
    const question = findQuestion(ctx.messages(), mi)
    ctx.exportAnswerMd({ answer: m, question, title: ctx.currentSessionTitle() })
  }
  const deleteRound = async mi => {
    const mid = ctx.messages()[mi]?.messageId
    if (!mid) { notify.warning('该轮对话不可删除'); return }
    try {
      const r = await api.deleteMessageGroup(mid)
      if (r.success) {
        let from = mi
        if (mi > 0 && ctx.messages()[mi - 1]?.role === 'user') from = mi - 1
        ctx.messages().splice(from, mi - from + 1)
        notify.success('已删除本轮对话')
        ctx.loadSessions()
      } else notify.error(r.msg || '删除失败')
    } catch (e) { notify.error(e.message || '删除失败') }
  }

  // ==================== 检索调试 ====================
  const debugVisible = ref(false)
  const debugLoading = ref(false)
  const debugQuestion = ref('')
  const debugResult = ref(null)
  const openDebug = mi => {
    const q = findQuestion(ctx.messages(), mi, false)
    if (q) debugQuestion.value = q.content
    debugVisible.value = true
    runDebug()
  }
  const runDebug = async () => {
    const q = String(debugQuestion.value || '').trim()
    if (!q) { notify.warning('请输入问题'); return }
    debugLoading.value = true
    debugResult.value = null
    try {
      const r = await api.debugRetrieval(q)
      if (r.success) debugResult.value = r.data
      else notify.error(r.msg || '调试失败')
    } catch (e) { notify.error(e.message || '调试失败') }
    finally { debugLoading.value = false }
  }
  const debugStages = computed(() => projectDebugStages(debugResult.value))

  return {
    armGoal, inlineSlash, sendNow,
    steerDragIdx, steerDragOver, onSteerDragStart, onSteerDragOver, onSteerDragEnd, onSteerDrop, steerExtras,
    searchOpen, searchQuery, searchPos, searchInputRef, matchedIdxs, searchPosShown,
    openSearch, closeSearch, gotoMatch, paintSearchHighlight, scrollToMatch,
    onSearchHotkey, onDocEscKeydown,
    shareVisible, shareLoading, shareInfo, shareUrl, loadShare, enableShare, stopShare, copyShareLink,
    shareActive, openShare, consumeShareOpen, doEnableShare, regenerateShareLink, doDisableShare, openSharedPage,
    onPanelNavKeydown, onInputKeydown, hitsNote,
    copyAnswer, copyUserMessage, onMoreAction, addToEval,
    solidifyOpen, solidifyBusy, solidifyForm, gotoSkillsPage, solidifySlug, openSolidify, submitSolidify,
    exportRound, deleteRound,
    debugVisible, debugLoading, debugQuestion, debugResult, openDebug, runDebug, debugStages
  }
}
