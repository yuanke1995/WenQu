// 候选面板（/ 命令、# 历史引用、「+」面板）的状态、判据与动作出口。
// 纯判据在 commandPanels.js；引擎值、随行材料、会话动作、DOM 全局一律经 ctx 注入。
// 输入框是唯一输入源：面板只做展示 + 键盘导航，筛选词由 syncPanelQuery 从正文实时取，
// 确认后用 stripTriggerToken 摘掉「触发字符 + 筛选词」这段，焦点全程留在编辑区。
import { ref, computed, watch, nextTick } from 'vue'
import { slashAliasesOf, slashArgPending, slashArgsOf, filterSlashCommands,
         filterAddCommands, filterSkills, histListOf, histChipLabel, moveHi,
         triggerStripRange, panelQueryIntent, addSearchHintOf, addFootHintOf,
         addRefListOf, addListSizeOf, panelKeydownIntent } from './commandPanels.js'

export const useCommandPanels = (ctx = {}) => {
  // DOM/调度全局经 ctx 注入并给浏览器默认值，保证 node 环境可跑。
  const tick = ctx.nextTick || nextTick
  const defer = ctx.setTimeout || ((fn, ms) => setTimeout(fn, ms))
  const message = ctx.notify || { info() {}, warning() {} }
  const { CompressOutlined, AimOutlined, FileTextOutlined, PlusOutlined,
          PaperClipOutlined, BookOutlined, HistoryOutlined, ThunderboltOutlined,
          DatabaseOutlined, RobotOutlined, AppstoreOutlined } = ctx.icons || {}

  // 触发字符在正文里的位置与字符本身（keydown 时记录：字符尚未进 DOM，插入后下标即为 pos）。
  const panelTriggerPos = ref(-1)
  const panelTriggerCh = ref('')

  const slashOpen = ref(false)
  const slashQuery = ref('')
  const slashHi = ref(0)
  const histOpen = ref(false)
  const histQuery = ref('')
  const histHi = ref(0)
  const addOpen = ref(false)
  const addTab = ref('root')
  const addHi = ref(0)
  const addSearchEl = ref(null)
  const addHistQuery = ref('')
  const addCmdQuery = ref('')
  const addTarget = ref('main')
  const addFromRoot = ref(false)
  const slashListEl = ref(null)
  const histListEl = ref(null)
  const addListEl = ref(null)

  const panelAnyOpen = () => slashOpen.value || histOpen.value || addOpen.value
  const closeAllPanels = () => {
    slashOpen.value = false
    histOpen.value = false
    addOpen.value = false
    panelTriggerPos.value = -1
    panelTriggerCh.value = ''
  }
  const panelListSize = () => {
    if (slashOpen.value) return slashFiltered.value.length
    if (histOpen.value) return histCandidates.value.length
    if (addOpen.value) return addListSize.value
    return 0
  }
  // 取回的必须是 ref 本身而非 .value（movePanelHi 还要再读 .value）。
  const getPanelHi = () => (slashOpen.value ? slashHi : histOpen.value ? histHi : addHi)
  const setPanelHi = v => { getPanelHi().value = v }
  // 各面板的可滚列表（同时只渲染一个；取用顺序与 getPanelHi 一致）。
  const panelListEl = () => (slashOpen.value ? slashListEl : histOpen.value ? histListEl : addListEl).value

  /** 高亮滑出列表可视区时把列表滚回来。手算 scrollTop 而非 scrollIntoView：
   *  后者会连祖先滚动容器（对话区）一起滚，方向键一按整页跳位。 */
  const scrollPanelHiIntoView = () => {
    const list = panelListEl()
    const item = list && list.querySelector('.mention-item.hi')
    if (!item) return
    const lr = list.getBoundingClientRect()
    const ir = item.getBoundingClientRect()
    if (ir.top < lr.top) list.scrollTop += ir.top - lr.top
    else if (ir.bottom > lr.bottom) list.scrollTop += ir.bottom - lr.bottom
  }
  const movePanelHi = dir => {
    const n = panelListSize()
    if (!n) return
    setPanelHi(moveHi(getPanelHi().value, dir, n))
    tick(scrollPanelHiIntoView)
  }

  /** 光标在正文里的下标（chip 算零宽，与 text 同一口径）；焦点不在编辑区时退回末尾。 */
  const composerCaret = () => {
    const cp = ctx.composerRef.value
    if (!cp) return ctx.text().length
    const i = cp.caretIndex()
    return i < 0 ? ctx.text().length : i
  }
  /** 摘掉「触发字符 + 其后已输入的筛选词」：引用已由 chip 承载，正文里不该残留这段触发文本。 */
  const stripTriggerToken = () => {
    if (panelTriggerPos.value < 0) return
    const cp = ctx.composerRef.value
    if (!cp) return
    const from = panelTriggerPos.value
    const [, to] = triggerStripRange(from, composerCaret())
    cp.replaceRange(from, to, '')
    cp.focus()
    cp.setCaret(from)
    panelTriggerPos.value = -1
    panelTriggerCh.value = ''
  }
  /** 面板收掉后焦点回谁那儿：编辑卡开的回卡内正文，其余回主输入框。 */
  const focusPanelOwner = () => {
    if (addOnEdit.value) { ctx.editingRef.value?.focus?.(); return }
    ctx.focusInput()
  }

  /** 输入框内容/光标变化时同步筛选词与高亮：候选集变了，原高亮下标已无意义，回到第一条。 */
  const syncPanelQuery = () => {
    if (!panelAnyOpen() || panelTriggerPos.value < 0) return
    const caret = composerCaret()
    const { close, q } = panelQueryIntent({
      text: ctx.text(), pos: panelTriggerPos.value, ch: panelTriggerCh.value,
      caret, slashOpen: slashOpen.value, commands: slashCommands.value
    })
    if (close) { closeAllPanels(); return }
    if (slashOpen.value) slashQuery.value = q
    else if (histOpen.value) histQuery.value = q
    else if (addOpen.value) ctx.mentionQuery.value = q
    setPanelHi(0)
    tick(scrollPanelHiIntoView)
  }

  // ==================== / 快捷命令面板（会话操作） ====================
  // 命令一律是会话级动作，选中即执行、不往正文插任何东西。
  // cmd=英文别名（打 /compact 直接命中）；args=认「别名 + 空格 + 一句任务」；
  // noGate=不碰正在跑的这一轮，生成中也照样能用。
  const slashCommands = computed(() => [
    { key: 'act-compact', cmd: 'compact', icon: CompressOutlined, name: '压缩上下文',
      desc: '早期对话压缩为摘要，腾出上下文空间',
      run: () => ctx.compactContext() },
    ...(ctx.goalEntryVisible() ? [{ key: 'act-deep', cmd: 'deep', aliases: ['深度'], icon: AimOutlined,
      noGate: true, args: true, name: '深度执行', desc: '跨轮自动推进，直到完成',
      run: args => {
        const task = String(args || '').trim()
        if (task) { ctx.startGoalNow(task); return }
        ctx.armGoal(!ctx.goalArmed())
      } }] : []),
    { key: 'act-export', icon: FileTextOutlined, name: '导出会话 Markdown',
      desc: '把当前会话全部问答导出为 .md 文件',
      run: () => {
        if (!ctx.currentSessionId()) { message.warning('当前没有可导出的会话'); return }
        ctx.exportSessionMarkdown(ctx.currentSessionId(), ctx.currentSessionTitle())
      } },
    { key: 'act-new', icon: PlusOutlined, name: '新建会话',
      desc: '开一个空白会话（当前会话保留在侧边栏）',
      run: () => ctx.createNewSession() }
  ])
  /** 只能逐个关其它面板，不能调 closeAllPanels：调用点刚把触发字符的位置记进
   *  panelTriggerPos/Ch，closeAllPanels 会顺手抹掉它，筛选词通道就断了。 */
  const openSlashPanel = () => {
    addOpen.value = false
    histOpen.value = false
    slashOpen.value = true
    slashQuery.value = ''
    slashHi.value = 0
    if (panelTriggerCh.value !== '/') { panelTriggerPos.value = -1; panelTriggerCh.value = '' }
  }
  const closeSlashPanel = () => closeAllPanels()
  const slashQueryNorm = computed(() => slashQuery.value.trim())
  const slashArgPendingOf = q => slashArgPending(q, slashCommands.value)
  const slashArgsOfCmd = c => slashArgsOf(c, slashQuery.value)
  const slashFiltered = computed(() => filterSlashCommands(slashCommands.value, slashQueryNorm.value))
  const runSlashCommand = c => {
    // 命令会改会话状态，不能和流式回答打架：/ 面板与「+」面板同一道闸。
    // 挡下时顺手摘掉触发片段，否则「/新建会话」留在正文里，下一按 Enter 就当问题发出去了。
    if (ctx.loading() && !c.noGate) {
      stripTriggerToken()
      closeAllPanels()
      message.info('生成中，请等这轮回答结束后再执行')
      return
    }
    // 参数先取：下面 stripTriggerToken/closeAllPanels 会把触发片段与筛选词清掉。
    const args = slashArgsOfCmd(c)
    stripTriggerToken()
    closeAllPanels()
    c.run?.(args)
  }

  /** Enter 确认高亮项：/ 命令（执行）/ # 历史引用（加 chip）/「+」面板当前页。
   *  返回 false = 没有可确认的候选（筛空了），本次回车交回输入框照常发送。 */
  const confirmPanelHi = () => {
    const i = getPanelHi().value
    if (slashOpen.value) {
      const c = slashFiltered.value[i]
      if (!c) return false
      runSlashCommand(c)
      return true
    }
    if (histOpen.value) {
      const m = histCandidates.value[i]
      if (!m) return false
      stripTriggerToken()
      ctx.toggleHistoryRef(m)
      closeAllPanels()
      return true
    }
    if (addOpen.value) return confirmAddHi(i)
    return false
  }

  // ==================== # 历史引用面板（勾选本会话历史问答 → 本轮上下文） ====================
  const histQueryNorm = computed(() => histQuery.value.trim())
  const histListOfRows = (q, pool) => histListOf(q, pool || ctx.histPool.value)
  const histCandidates = computed(() => histListOfRows(histQueryNorm.value))
  const pickHistoryRefByClick = m => {
    stripTriggerToken()
    ctx.toggleHistoryRef(m)
    closeAllPanels()
  }
  const openHistPanel = () => {
    addOpen.value = false
    slashOpen.value = false
    histOpen.value = true
    histQuery.value = ''
    histHi.value = 0
  }
  const closeHistPanel = () => closeAllPanels()

  // ==================== 「+」面板（附件 / 引用资料 / 历史 / 命令） ====================
  // 唯一的候选面板，两个入口共用同一块：主输入框点「+」或敲 @，就地编辑卡点「+」。
  // 入口决定写入目标 addTarget，选中结果与计数全部经 addStore 这一层读写。
  const addOnEdit = computed(() => addTarget.value === 'edit' && ctx.editingIdx() != null)
  /** 编辑态下面板整块搬进卡内挂载点；主输入框那一份靠 disabled 原地不动。 */
  const EDIT_ADD_MOUNT = '.msg-inline-edit .add-mount'
  /** 目标适配器：面板不关心自己往哪写，两套随行内容在这一层收敛成同一套读写接口。 */
  const addStore = computed(() => addOnEdit.value ? {
    files: ctx.editAtts, images: ctx.editImgs, mentions: ctx.editMentions, skills: ctx.editSkills,
    histPool: ctx.editHistPool, histPicked: () => ctx.editHistRefs.value,
    pickFiles: ctx.pickEditFiles,
    toggleSkill: ctx.toggleEditSkill, skillOn: n => ctx.editSkills.value.includes(n),
    toggleMention: ctx.toggleEditMention,
    mentionOn: (t, id) => ctx.editMentions.value.some(m => m.type === t && m.id === id),
    toggleHist: ctx.toggleEditHistoryRef,
    histOn: id => ctx.editHistRefs.value.some(h => h.messageId === id)
  } : {
    files: ctx.pendingFiles, images: ctx.pendingImages, mentions: ctx.pendingMentions, skills: ctx.pickedSkills,
    histPool: ctx.histPool, histPicked: () => ctx.pendingHistoryRefs.value,
    pickFiles: ctx.pickAttachments,
    toggleSkill: ctx.toggleSkill, skillOn: n => ctx.pickedSkills.value.includes(n),
    toggleMention: ctx.toggleMention, mentionOn: ctx.isMentioned,
    toggleHist: ctx.toggleHistoryRef, histOn: ctx.isHistPicked
  })
  /** 一级菜单的行：计数与描述按写入目标现算；快捷命令只属于主输入框。 */
  const addRootRows = computed(() => {
    const s = addStore.value
    const edit = addOnEdit.value
    const scope = edit ? '这一问' : '本轮'
    const rows = [
      { key: 'file', name: '上传文件', icon: PaperClipOutlined,
        desc: edit ? '文件读出正文供模型看，图片压缩后随这一问重发；也可直接粘贴进来（各最多 5 个）'
                   : '也可直接粘贴或拖进来：文件读出正文供模型看，图片压缩后随本轮提问（各最多 5 个）',
        badge: () => s.files.value.length + s.images.value.length, run: () => s.pickFiles() },
      { key: 'ref', name: '引用资料', icon: BookOutlined, kbd: edit ? '' : '@',
        desc: `知识库 / 文档 / 智能体 / 技能，只对${scope}生效`,
        badge: () => s.mentions.value.length + s.skills.value.length, run: () => switchAddTab('ref') },
      { key: 'hist', name: '引用历史问答', icon: HistoryOutlined, kbd: edit ? '' : '#',
        desc: `勾选本会话的问答作为${scope}上下文（最多 10 条）`,
        badge: () => s.histPicked().length, run: () => switchAddTab('hist') }
    ]
    if (!edit) rows.push({ key: 'cmd', name: '快捷命令', icon: ThunderboltOutlined, kbd: '/',
      desc: '压缩上下文、深度执行、导出会话、新建会话', badge: () => 0, run: () => switchAddTab('cmd') })
    return rows
  })
  const addHistPool = computed(() => addStore.value.histPool.value)
  const addHistCandidates = computed(() => histListOfRows(addHistQuery.value, addHistPool.value))
  const isAddSkillOn = n => addStore.value.skillOn(n)
  const toggleAddSkill = n => addStore.value.toggleSkill(n)
  const isAddMentioned = (t, id) => addStore.value.mentionOn(t, id)
  const toggleAddMention = (t, item) => addStore.value.toggleMention(t, item)
  const isAddHistPicked = id => addStore.value.histOn(id)
  const toggleAddHistoryRef = m => addStore.value.toggleHist(m)
  const ADD_TITLES = { ref: '引用资料', hist: '引用历史问答', cmd: '快捷命令' }
  const REF_SUB_TABS = [
    { key: 'kb', label: '知识库', icon: DatabaseOutlined, count: () => ctx.mentionKbs().length },
    { key: 'doc', label: '文档', icon: FileTextOutlined, count: () => ctx.mentionDocs().length },
    { key: 'agent', label: '智能体', icon: RobotOutlined, count: () => ctx.mentionAgents().length },
    { key: 'skill', label: '技能', icon: AppstoreOutlined, count: () => ctx.skillList().length }
  ]
  // 搜索框一页一词：切页签不串味（引用页那一份就是 mentionQuery，四个子页签共用）。
  const addSearchable = computed(() => ['ref', 'hist', 'cmd'].includes(addTab.value))
  const addQuery = computed({
    get: () => addTab.value === 'ref' ? ctx.mentionQuery.value
      : addTab.value === 'hist' ? addHistQuery.value
        : addTab.value === 'cmd' ? addCmdQuery.value : '',
    set: v => {
      if (addTab.value === 'ref') ctx.mentionQuery.value = v
      else if (addTab.value === 'hist') addHistQuery.value = v
      else if (addTab.value === 'cmd') addCmdQuery.value = v
    }
  })
  const addSearchHint = computed(() => addSearchHintOf(addTab.value))
  const addFootHint = computed(() => addFootHintOf(addTab.value, addOnEdit.value, ctx.mentionTab.value))
  const addSkillList = computed(() => filterSkills(ctx.skillList(), ctx.mentionQuery.value))
  const addCmdList = computed(() => filterAddCommands(slashCommands.value, addCmdQuery.value))
  /** 引用页当前子页签的候选（点击与 Enter 都读这一份，下标才对得上）。 */
  const addRefList = computed(() => addRefListOf(ctx.mentionTab.value, {
    kb: ctx.mentionKbFiltered(), doc: ctx.mentionDocFiltered(),
    agent: ctx.mentionAgentFiltered(), skill: addSkillList.value
  }))
  const addListSize = computed(() => addListSizeOf(addTab.value, {
    root: addRootRows.value.length, ref: addRefList.value.length,
    hist: addHistCandidates.value.length, cmd: addCmdList.value.length
  }))
  // 候选集变了，原高亮下标已无意义：回到第一条，并把列表滚回顶部区域。
  watch(addQuery, () => { addHi.value = 0; tick(scrollPanelHiIntoView) })

  const closeAddPanel = () => { addOpen.value = false }
  /** 引用候选懒加载（首次进入引用页才拉）：库/文档走列表接口，智能体与技能清单兜底再拉一次。 */
  const ensureRefCandidates = () => {
    if (!ctx.mentionKbs().length && !ctx.mentionDocs().length) ctx.loadMentionCandidates()
    if (!ctx.agentList().length) ctx.loadAgents()
    if (!ctx.skillList().length) ctx.loadSkills()
  }
  /** 可搜索的页签把焦点交给面板内搜索框，否则交回输入框。退回一级时焦点必须挪出
   *  keydown 事件流（同流里原生 focus 会落回 BODY），用 setTimeout 延一拍。 */
  const focusAddSearch = () => tick(() => {
    if (addSearchable.value) { addSearchEl.value?.focus?.(); return }
    defer(() => {
      if (addOnEdit.value) { ctx.editingRef.value?.focus?.(); return }
      const cp = ctx.composerRef.value
      if (!cp) return
      cp.focus()
      cp.setCaret(ctx.text().length)
    }, 0)
  })
  /** 二级 → 返回一级菜单。@ 触发的筛选片段还挂在正文里时先摘掉。 */
  const backToRoot = () => {
    if (panelTriggerPos.value >= 0) stripTriggerToken()
    addTab.value = 'root'
    addHi.value = 0
    focusAddSearch()
  }
  const switchAddTab = k => {
    if (k !== addTab.value) {
      // 切到别的页签就意味着改由搜索框接手筛选，先把正文里的触发片段摘掉
      // （词本身留在 mentionQuery 里，回到「引用」页还能接着筛）。
      if (panelTriggerPos.value >= 0) stripTriggerToken()
      addTab.value = k
      addHi.value = 0
      addFromRoot.value = true
      if (k === 'ref') ensureRefCandidates()
    }
    focusAddSearch()
  }
  const switchRefTab = k => {
    ctx.mentionTab.value = k
    addHi.value = 0
    tick(scrollPanelHiIntoView)
  }
  /** 引用资料行确认。敲 @ 进来的，确认后摘触发片段、收面板、焦点回输入框；
   *  点「+」进来的，勾选后留在面板里继续挑——引用与技能都可多选。 */
  const confirmRefRow = (type, item) => {
    const byTrigger = panelTriggerPos.value >= 0 && panelTriggerCh.value === '@'
    if (byTrigger) { stripTriggerToken(); closeAllPanels() }
    if (type === 'skill') toggleAddSkill(item.name)
    else toggleAddMention(type, item)
    if (byTrigger) ctx.focusInput()
  }
  /** Enter 确认「+」面板当前页的高亮项。返回 false = 这一页没有可确认的候选。 */
  const confirmAddHi = i => {
    const t = addTab.value
    if (t === 'root') {
      const row = addRootRows.value[i]
      if (!row) return false
      row.run()
      return true
    }
    if (t === 'ref') {
      const item = addRefList.value[i]
      if (!item) return false
      confirmRefRow(ctx.mentionTab.value, item)
      return true
    }
    if (t === 'hist') {
      const m = addHistCandidates.value[i]
      if (!m) return false
      toggleAddHistoryRef(m)
      return true
    }
    const c = addCmdList.value[i]
    if (!c) return false
    runCmdFromAddPanel(c)
    return true
  }
  /** 两个入口共用的开面板动作：先复位筛选与层级，再按入口标定写入目标。 */
  const openAddPanel = target => {
    closeAllPanels()
    addHistQuery.value = ''
    addCmdQuery.value = ''
    ctx.mentionQuery.value = ''
    addHi.value = 0
    addTab.value = 'root'
    addFromRoot.value = false
    addTarget.value = target
    addOpen.value = true
    focusAddSearch()
  }
  const toggleAddPanel = () => {
    if (addOpen.value && !addOnEdit.value) { closeAddPanel(); return }
    openAddPanel('main')
  }
  /** 就地编辑卡的「+」：同一块面板，选中结果落到这张卡的随行内容上。 */
  const toggleEditAddPanel = () => {
    if (addOpen.value && addOnEdit.value) { closeAddPanel(); return }
    openAddPanel('edit')
  }
  /** 命令页点选：先收面板，执行与生成中的拦截都在 runSlashCommand 里。 */
  const runCmdFromAddPanel = c => {
    closeAddPanel()
    runSlashCommand(c)
  }

  /** 面板键盘导航（/ # 触发面板与「+」面板共用一套）。返回 true = 本次按键已被面板消费。 */
  const onPanelNavKeydown = e => {
    const intent = panelKeydownIntent(e, {
      addOpen: addOpen.value, addTab: addTab.value, addFromRoot: addFromRoot.value
    })
    if (!intent) return false
    if (intent === 'down' || intent === 'up') {
      e.preventDefault(); movePanelHi(intent === 'down' ? 1 : -1); return true
    }
    if (intent === 'back') { e.preventDefault(); backToRoot(); return true }
    if (intent === 'close') { e.preventDefault(); closeAllPanels(); focusPanelOwner(); return true }
    if (intent === 'tab-root') { e.preventDefault(); confirmAddHi(addHi.value); return true }
    if (intent === 'tab-back') { e.preventDefault(); backToRoot(); return true }
    if (intent === 'enter') {
      if (confirmPanelHi()) { e.preventDefault(); return true }
      return false
    }
    return false
  }

  return {
    // 共用键盘导航通道
    panelTriggerPos, panelTriggerCh, panelAnyOpen, closeAllPanels, panelListSize,
    getPanelHi, setPanelHi, slashListEl, histListEl, addListEl, panelListEl,
    scrollPanelHiIntoView, movePanelHi, confirmPanelHi, stripTriggerToken,
    composerCaret, focusPanelOwner, syncPanelQuery, onPanelNavKeydown,
    // / 快捷命令面板
    slashOpen, slashQuery, slashHi, slashCommands, openSlashPanel, closeSlashPanel,
    slashQueryNorm, slashAliasesOf, slashArgPending: slashArgPendingOf,
    slashArgsOf: slashArgsOfCmd, slashFiltered, runSlashCommand,
    // # 历史引用面板
    histOpen, histQuery, histHi, histQueryNorm, histListOf: histListOfRows, histCandidates,
    histChipLabel, pickHistoryRefByClick, openHistPanel, closeHistPanel,
    // 「+」面板
    addOpen, addTab, addHi, addSearchEl, addHistQuery, addCmdQuery, addTarget, addOnEdit,
    EDIT_ADD_MOUNT, addStore, addRootRows, addHistPool, addHistCandidates,
    isAddSkillOn, toggleAddSkill, isAddMentioned, toggleAddMention,
    isAddHistPicked, toggleAddHistoryRef, ADD_TITLES, REF_SUB_TABS,
    addSearchable, addQuery, addSearchHint, addFootHint, addSkillList, addCmdList,
    addRefList, addListSize, closeAddPanel, ensureRefCandidates, focusAddSearch,
    addFromRoot, backToRoot, switchAddTab, switchRefTab, confirmRefRow, confirmAddHi,
    openAddPanel, toggleAddPanel, toggleEditAddPanel, runCmdFromAddPanel
  }
}
