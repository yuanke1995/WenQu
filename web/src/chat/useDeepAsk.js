// ChatPage 的深度执行入口、智能体提问面板、摘要重新压缩与收尾副作用。
// 引擎值、页面动作与 DOM 全局全部经 ctx 注入；纯判据在 deepAsk.js。
import { ref, computed, watch, nextTick, onMounted, onUnmounted, getCurrentInstance } from 'vue'
import { goalEntryAllowed, goalStartText, goalEditKeydownIntent, askOptionParts, curAskAt,
         askTimeState, askNeedsTicker, askAutoSubmitReady, nextAskPage, askPickGuard,
         shouldAdvanceCustom, askPanelKeydownIntent, askCustomKeydownIntent, askPanelPlaceholder,
         normalizeCsumInstr, recompactShouldClose } from './deepAsk.js'

// 只读依赖兼容 getter 函数 / ref / 裸值，页面按手头形态接线即可
const read = v => typeof v === 'function' ? v()
  : v && typeof v === 'object' && 'value' in v ? v.value : v

const stubDocument = { querySelector: () => null, activeElement: null, addEventListener: () => {} }
const stubWindow = { addEventListener: () => {} }

export const useDeepAsk = ctx => {
  const doc = ctx.document || (typeof document !== 'undefined' ? document : stubDocument)
  const win = ctx.window || (typeof window !== 'undefined' ? window : stubWindow)
  const TA = ctx.HTMLTextAreaElement ||
    (typeof HTMLTextAreaElement !== 'undefined' ? HTMLTextAreaElement : null)
  const now = ctx.now || (() => Date.now())
  const setTicker = ctx.setInterval || ((fn, ms) => setInterval(fn, ms))
  const clearTicker = ctx.clearInterval || (id => clearInterval(id))
  const rawNotify = ctx.notify || ctx.message || {}
  const notify = typeof rawNotify === 'function'
    ? { info: rawNotify, warning: rawNotify }
    : { info: rawNotify.info || (() => {}), warning: rawNotify.warning || (() => {}) }

  // 只读引擎值包成 getter：取值与 watch 源共用一份
  const messages = () => read(ctx.messages)
  const loading = () => read(ctx.loading)
  const goalModeOn = () => read(ctx.goalModeOn)
  const sessionGoal = () => read(ctx.sessionGoal)
  const goalTerminal = () => read(ctx.goalTerminal)
  const planModeOn = () => read(ctx.planModeOn)
  const goalEditBusy = () => read(ctx.goalEditBusy)
  const isNarrow = () => read(ctx.isNarrow)
  const searchOpen = () => read(ctx.searchOpen)
  const searchQuery = () => read(ctx.searchQuery)
  const editingIdx = () => read(ctx.editingIdx)
  const editingMessageId = () => read(ctx.editingMessageId)
  const currentSessionId = () => read(ctx.currentSessionId)
  const skillList = () => read(ctx.skillList)
  const pickedSkills = () => read(ctx.pickedSkills)
  const agentList = () => read(ctx.agentList)
  const query = () => ctx.route.query

  // 需要写入的响应式状态由页面传 ref 本身
  const text = ctx.text || ref('')
  const goalStarting = ctx.goalStarting || ref(false)
  const goalEditing = ctx.goalEditing || ref(false)
  const searchPos = ctx.searchPos || ref(0)

  // ==================== 深度执行 ====================
  // 入口显隐：当前智能体允许深度执行 + 本会话还没有执行（终态后允许再起一条）
  const goalEntryVisible = computed(() =>
    goalEntryAllowed(goalModeOn(), sessionGoal(), goalTerminal()))

  // 一句话创建（/deep 与工具条入口共用）：不弹表单，取正文直接进入深度执行
  const startGoalNow = async prefill => {
    const obj = goalStartText(prefill, text.value)
    if (!obj) {
      notify.info('先在输入框写下这件事要做到什么，或打 /deep 深度')
      ctx.focusInput()
      return null
    }
    // 深度执行接管这一轮：挂着的旧计划标记当场收掉，避免两颗模式标记同时亮
    if (planModeOn()) ctx.setPlanMode(false)
    // 建目标是一次异步请求，请求发出前就清框：用户不会盯着满框文字和亮着的发送键以为没发出去
    if (prefill == null) text.value = ''
    goalStarting.value = true
    try {
      const g = await ctx.startGoal(obj)
      // 建失败就把那句还回来；期间用户又打了字就不覆盖他新写的东西
      if (!g && prefill == null && !String(text.value || '').trim()) text.value = obj
      return g
    } finally {
      goalStarting.value = false
    }
  }

  // 就地编辑的键盘：Enter 保存、Esc 取消；Esc 要 stopPropagation，避免顺手把全局停止键 armed
  const onGoalEditKeydown = e => {
    const intent = goalEditKeydownIntent(e)
    if (intent === 'save') {
      e.preventDefault()
      ctx.saveGoalEdit()
    } else if (intent === 'cancel') {
      e.preventDefault()
      e.stopPropagation()
      if (!goalEditBusy()) goalEditing.value = false
    }
  }
  // 点「编辑」聚焦 textarea 并把插入点放到文末（停在句首看着像框没接上）
  watch(goalEditing, on => {
    if (!on) return
    nextTick(() => {
      const el = doc.querySelector('[data-goal-bar] textarea')
      if (!el) return
      if (TA && !(el instanceof TA)) return
      el.focus()
      el.setSelectionRange(el.value.length, el.value.length)
    })
  })

  // ==================== 智能体提问面板 ====================
  // 当前会话存在挂起中的提问时，底部聊天输入框整块让位给提问面板（模型在等答案，此刻也没法发新消息）；
  // 答复后工具终态到达（msg.ask 清空），面板撤下、输入框回归。键盘在「选项+自定义输入行」间循环
  const pendingAsk = computed(() => {
    const list = messages()
    for (let i = list.length - 1; i >= 0; i--) {
      const m = list[i]
      if (m && m.role === 'ai' && m.ask) return m
    }
    return null
  })
  // 同一轮里排在当前卡之后的待答卡数（并行工具调用能一次挂出两张提问卡，一张一答轮着来）
  const askQueueCount = computed(() => ((pendingAsk.value && pendingAsk.value.askQueue) || []).length)
  const askPage = ref(0)              // 当前翻页（0 基）：一卡多问逐题展示
  const askCustomRef = ref(null)
  const askNow = ref(now())           // 倒计时心跳（仅挂起中有 deadline 时走表）
  const askHi = ref(null)             // 键盘高亮行（0..n-1=选项，n=自定义）；null=不高亮
  const askCustomPlaceholder = computed(() => askPanelPlaceholder(isNarrow()))
  let askTimer = null
  const curAsk = computed(() => curAskAt(pendingAsk.value?.ask, askPage.value))
  // 面板底部那句提示：措辞与判据都在引擎那一份，两端一字不差
  const askPanelHint = computed(() => {
    const a = pendingAsk.value?.ask
    return a ? ctx.askHintText(a, askPage.value) : ''
  })
  const askRemaining = computed(() => askTimeState(pendingAsk.value?.ask, askNow.value).remaining)
  const askExpired = computed(() => askTimeState(pendingAsk.value?.ask, askNow.value).expired)
  const askCountdownText = computed(() => askTimeState(pendingAsk.value?.ask, askNow.value).text)

  watch(() => pendingAsk.value?.ask, a => {
    askPage.value = 0
    askHi.value = null
    // 仅在有 deadline 时启心跳；归零后停止（由 onToolStatus 收卡，或用户已提交）
    if (askNeedsTicker(a)) {
      // 挂卡的这一刻就先对表：后台标签页里 1s 心跳会被节流到分钟级，不校准则首屏倒计时按旧值算
      askNow.value = now()
      if (!askTimer) askTimer = setTicker(() => { askNow.value = now() }, 1000)
    } else if (askTimer) {
      clearTicker(askTimer); askTimer = null
    }
    nextTick(() => { const el = askCustomRef.value; if (el && el.focus) el.focus() })
  }, { flush: 'post' })
  // 手动翻页 / 自动跳题后清掉键盘高亮，避免高亮行号与新题不符
  watch(askPage, () => { askHi.value = null })
  watch(askExpired, exp => { if (exp && askTimer) { clearTicker(askTimer); askTimer = null } })

  // 高亮移动时同步焦点：落到自定义输入行→聚焦（可直接打字），落到选项行→失焦（避免误输入）
  const focusAskRow = () => {
    const n = (curAsk.value.options || []).length
    nextTick(() => {
      const el = askCustomRef.value
      if (!el) return
      if (askHi.value === n) el.focus()
      else if (doc.activeElement === el) el.blur()
    })
  }
  // 点一个候选：单选=记录选择并自动跳到下一道未答题；多选=就地勾上/取消、留在本题。
  // 多选题不跳题是刻意的：勾完一个就翻页，用户会以为这一题答完了、后面的项没地方勾
  const onPick = oi => {
    if (!askPickGuard(pendingAsk.value, askExpired.value)) return
    const m = pendingAsk.value
    ctx.pickAskOption(m, askPage.value, oi)
    if (curAsk.value.multi) return
    askHi.value = null
    advanceAfterSelect(askPage.value)
  }
  // 确认某题自定义答案：回车确认并跳下一题；失焦只确认不跳题，避免 blur 先翻页导致 click 记到别的题上。
  // 多选题同样不跳题——自定义那行在多选里只是其中一个勾选项
  const commitCustomAt = (page, doAdvance) => {
    if (!askPickGuard(pendingAsk.value, askExpired.value)) return
    const m = pendingAsk.value
    ctx.commitAskCustom(m, page)
    const q = m.ask.questions[page]
    if (shouldAdvanceCustom(q, doAdvance, () => ctx.askQuestionAnswered(m.ask, page))) {
      askHi.value = null
      advanceAfterSelect(page)
    }
  }
  // 选完本题后跳到下一道未答题；都答完则停在末题（等待自动提交）。「答了没」只问引擎那一份判据
  const advanceAfterSelect = page => {
    const a = pendingAsk.value?.ask
    if (!a) return
    const next = ctx.askNextUnanswered(a, page)
    askPage.value = nextAskPage(a.questions.length, next)
  }
  // 整卡都是单选且都答过 → 自动提交（只触发一次：提交后 answered/busy 置位，条件不再满足）。
  // 卡里含多选题时这条永不成立，等用户自己点提交
  watch(() => {
    const a = pendingAsk.value?.ask
    return askAutoSubmitReady(a, askExpired.value, a ? ctx.askAutoSubmittable(a) : false)
  }, done => { if (done) ctx.askSubmitAll(pendingAsk.value) })

  const onAskCustomKeydown = e => {
    if (askCustomKeydownIntent(e) !== 'commit') return
    e.preventDefault()
    e.stopPropagation()
    commitCustomAt(askPage.value, true)
  }
  const onAskPanelKeydown = e => {
    const a = pendingAsk.value?.ask
    if (!a) return
    const page = askPage.value
    const intent = askPanelKeydownIntent(e, {
      busy: a.busy, answered: a.answered, expired: askExpired.value,
      optionsCount: (curAsk.value.options || []).length,
      inInput: !!(e.target && e.target.tagName === 'INPUT'),
      picks: (a.picks || {})[page] || [],
      customOn: !!(a.customOn || {})[page],
      hi: askHi.value
    })
    if (!intent) return
    if (intent.type === 'move') {
      askHi.value = intent.hi
      focusAskRow()
    } else if (intent.type === 'pick') {
      onPick(intent.index)
    } else if (intent.type === 'custom') {
      commitCustomAt(page)
    }
    if (intent.prevent) e.preventDefault()
  }

  // ==================== 摘要弹窗的「带要求重新压缩」 ====================
  const csumInstrOpen = ref(false)
  const csumInstr = ref('')
  const applyRecompact = async () => {
    // 压完了才收起输入行：没得压/失败的原因引擎已就地提示，输入留着方便改一句再试
    if (recompactShouldClose(await ctx.recompactWith(normalizeCsumInstr(csumInstr.value)))) {
      csumInstrOpen.value = false
    }
  }

  // ==================== 收尾副作用 ====================
  watch(loading, v => { if (v) ctx.hideThinkPanel() })
  watch(loading, v => { if (!v) ctx.disarmEsc() })
  // 切会话/消息增删后旧 Range 已失效：重算（数组引用变化即触发；流式增量不重算，避免每 token 全量扫描）
  watch(messages, () => {
    if (!searchOpen() || !String(searchQuery() || '').trim()) return
    searchPos.value = 0 // 换会话后从头开始，避免停在上一个会话的偏移上
    nextTick(() => { ctx.paintSearchHighlight(); ctx.scrollToMatch() })
  })
  // 编辑卡按下标定位，而切会话/新建/切分支/删当前会话都会整体替换 messages。不绑回原消息
  // 就会让编辑卡跟到另一个会话的第 N 条上
  watch(messages, () => {
    if (editingIdx() == null) return
    if ((messages()[editingIdx()] || {}).messageId !== editingMessageId()) ctx.cancelEdit()
  })

  // ==================== 初始化 ====================
  const initialize = async () => {
    win.addEventListener('keydown', ctx.onGlobalKeydown)
    win.addEventListener('paste', ctx.onGlobalPaste)
    win.addEventListener('resize', ctx.onWindowResize)
    win.addEventListener('orientationchange', ctx.onOrientationChange)
    // 触屏深度思考 sheet 的「点外部关闭」用 pointerdown 而非 click：touch 设备上
    // click 在 touchend 之后才派发，面板内的 @mousedown.stop.prevent 拦不住它
    doc.addEventListener('pointerdown', ctx.onDocPointerDown)
    ctx.refreshSetupGuide()
    ctx.loadPermissionPresets()
    await ctx.ready()
    // 「带着一句要问的话来」：工作区事项的「去聊」带 ask 进来，落进输入框等用户确认发送。
    // 不自动发——事项措辞是给清单看的一句话，发给模型前该让用户过目改写。只填空输入框
    const askPrefill = String(query().ask || '')
    if (askPrefill && !String(text.value || '').trim()) {
      text.value = askPrefill
      ctx.focusInput()
      ctx.router.replace({ path: '/chat', query: { ...query(), ask: undefined } }).catch(() => {})
    }
    // 「去对话点名试用」带进来的技能名：把这条技能钉进本轮已选技能。先摘参数防刷新重复加；
    // 清单可能还没回来、也可能拉在技能刚创建之前——查不到就重拉一次再认，仍不在清单里才忽略并提醒
    const skillPick = String(query().skill || '')
    if (skillPick) {
      ctx.router.replace({ path: '/chat', query: { ...query(), skill: undefined } }).catch(() => {})
      let hit = skillList().find(s => s && s.name === skillPick)
      if (!hit) { await ctx.loadSkills(); hit = skillList().find(s => s && s.name === skillPick) }
      if (!hit) notify.warning(`技能「${skillPick}」不在当前可用清单，已忽略点名`)
      else if (hit.selectable === false) notify.warning(`技能「${skillPick}」是工作区只读技能，不能在对话中点名`)
      else if (!pickedSkills().includes(skillPick)) ctx.toggleSkill(skillPick)
    }
    // 智能体管理页「试一试」带进来的预选：本会话下一问起按它作答。先摘参数防刷新重复覆盖手动换人；
    // 清单可能还没拉或刚建的新智能体不在里面——查不到就重拉一次再认，仍不在才提醒
    const agentPick = String(query().agent || '')
    if (agentPick) {
      ctx.router.replace({ path: '/chat', query: { ...query(), agent: undefined } }).catch(() => {})
      let hit = agentList().find(a => String(a.id) === agentPick)
      if (!hit) { await ctx.loadAgents(); hit = agentList().find(a => String(a.id) === agentPick) }
      if (hit) ctx.pickAgent(hit.id)
      else notify.warning('这位智能体不能直接选用（可能已删除，或是供委派的子智能体）')
    }
    // 工作区事项「去聊」带来的关联标记：把这条事项与当前会话绑上，绑定失败不挡对话
    const itemId = String(query().itemId || '')
    const itemProjectId = String(query().itemProjectId || '')
    if (itemId && itemProjectId) {
      if (currentSessionId()) {
        ctx.updateProjectItem(itemProjectId, itemId, { sessionId: currentSessionId() }).catch(() => {})
      }
      ctx.router.replace({ path: '/chat',
        query: { ...query(), itemId: undefined, itemProjectId: undefined } }).catch(() => {})
    }
    // ready 后会话已加载、消息区进入稳态布局，此时测出的滚动条槽宽才准
    nextTick(ctx.syncScrollbarGutter)
  }

  if (getCurrentInstance()) {
    onMounted(initialize)
    onUnmounted(() => { if (askTimer) { clearTicker(askTimer); askTimer = null } })
  }

  return {
    goalEntryVisible, startGoalNow, onGoalEditKeydown,
    pendingAsk, askQueueCount, askPage, askCustomRef, askNow, askHi, askCustomPlaceholder,
    askTimer, curAsk, askPanelHint, askRemaining, askExpired, askCountdownText,
    askOptionParts, focusAskRow, onPick, commitCustomAt, advanceAfterSelect,
    onAskCustomKeydown, onAskPanelKeydown,
    csumInstrOpen, csumInstr, applyRecompact,
    initialize
  }
}
