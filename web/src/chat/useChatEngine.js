// ==================== 聊天引擎（ChatPage 抽出的引擎 composable，M1 引擎抽取） ====================
// 承载聊天页的「引擎逻辑」：思考/模型/智能体状态、会话生命周期、附件与 @ 引用载荷、
// 发送与 SSE 流式回答、审批、重新生成与分支切换。与视图的接缝全部收在 hooks 里
// （滚动、收面板、聚焦），PC 端由 ChatPage 按原行为传参；移动壳可传自己的落点
// （如 '/m/chat'）与滚动实现复用同一套引擎。纯函数/常量见 ./projections.js。
import { ref, reactive, computed, watch, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isAdminSync } from '../utils/auth'
import { message } from 'ant-design-vue'
import { sendQuestion, resumeChat, getChatRun, newSession, getHistory, deleteSessionApi, switchMessageVariant, compactSessionApi, sessionHistorySummaryApi, getConfig, getRuntimeConfig, listAvailableAgents, listConsultableAgents,
         listAvailableSkills, getUserPreference, approveToolCall, answerAgentAsk, ignoreAgentAsk, stopChatTurn, steerChatTurn,
         listPendingAsks, listPendingApprovals, resolvePlanApproval, supersedePlanApproval, listPendingPlans, updateSessionModelApi, getSessionModelApi,
         listKnowledgeBases, listDocuments, uploadChatAttachment, getSessionTodosApi,
         refreshArtifactSigns as refreshArtifactSignsApi } from '../api'
import { sessionStore, loadSessions, chatStreams, markSessionActive } from '../views/store'
import { fmtTokens } from '../utils/token'
import { loadModelIndex } from '../utils/modelRef'
import { THINK_CAPS, REASONING_LEVELS, THINK_LEVEL_ON, levelLabel, CTX_WINDOW_STEPS, fmtWindow,
         groupSources, ensureTick, mergeDoneToolCalls, restoreTimeline, extendTimelineText, extendTimelineProcess,
         pushTimelineTool, pushTimelineArtifact, pushTimelineThink, extendTimelineThink, ensureThinkSegment,
         snapshotVersion, applyVersion, toolCallsView, histItemDigest,
         DEFAULT_SAMPLE_QUESTIONS, parseSampleQuestions, PASTE_TEXT_BASE, rememberAgents } from './projections'

export function useChatEngine (hooks = {}) {
  const route = useRoute()
  const router = useRouter()
  // hooks 全部可选（缺省安全）：PC 端传原行为的实现；hook 实现方自己决定滚动时序
  const {
    chatPath = '/chat',      // 引擎内所有 route.path 判断与 router.replace 的落点（移动壳传 '/m/chat'）
    scrollFollow = () => {}, // 替代原 liveScroll 体内的 nextTick(() => { updateTailSpacer(); scroll() })
    scrollForce = () => {},  // 替代原 nextTick(() => { updateTailSpacer(); scrollForce() })
    scrollSoft = () => {},   // 替代原 resolveApproval 里的 scroll() 调用
    closePanels = () => {},  // send() 清空面板时调用（PC 传 closeAllPanels）
    focusInput = () => {}    // 替代原 focusInput() 的 DOM 聚焦
  } = hooks

const text = ref('')
/**
 * 计划模式（人在回路）入口开关：仅传入 planModeEnabled=true 的壳（PC ChatPage 的「+」面板）可开启。
 * 状态本身存 localStorage（桌面/移动同源共享），移动壳没有计划卡 UI——引擎侧按 planModeEnabled
 * 兜底拦下，防止桌面开的开关让移动端发出一轮「没人能批准」的计划问答。
 */
const planModeEnabled = hooks.planModeEnabled === true
const planModeOn = ref(planModeEnabled && (() => {
  try { return localStorage.getItem('ai_plan_mode') === '1' } catch (e) { return false }
})())
const setPlanMode = v => {
  if (!planModeEnabled) return
  planModeOn.value = !!v
  try { localStorage.setItem('ai_plan_mode', planModeOn.value ? '1' : '0') } catch (e) { /* 存储不可用忽略 */ }
}
/** 按模型引用取思考能力 / 支持档位（悬浮面板与发送链路共用同一套口径） */
const thinkCapsOf = ref => THINK_CAPS[modelIndex.value[ref]?.thinking || 'switchable'] || THINK_CAPS.switchable
const reasoningLevelsOf = ref => {
  const raw = modelIndex.value[ref]?.reasoningLevels
  return Array.isArray(raw) ? raw : []
}
const deepThinkDefaults = (() => {
  try {
    const raw = localStorage.getItem('ai_deep_think')
    if (raw === '1' || raw === '0') return { __default: raw === '1' } // 旧版全局开关 → 迁移为默认值
    const parsed = raw ? JSON.parse(raw) : {}
    return typeof parsed === 'object' && parsed ? parsed : {}
  } catch (e) { return {} }
})()
const deepThinkMap = ref({ ...deepThinkDefaults })
/** 某模型本轮是否思考：恒思考强制开；否则按模型记忆（等级下拉的「关闭思考」写 0，选档位/开启写 1） */
const deepOnOf = ref => {
  if (thinkCapsOf(ref).locked) return true
  const explicit = deepThinkMap.value[ref]
  if (explicit !== undefined) return explicit === 1
  return deepThinkMap.value.__default === true
}
/** 生效模型本轮是否思考（状态栏展示 + 发送链路取值） */
const deepThinkOn = computed(() => deepOnOf(effectiveModel.value))

/** 等级下拉可选项：恒思考锁定为开；可开关模型给「关闭思考」+ 其支持档位；
 *  未登记档位时给「开启思考 / 关闭思考」两项（强度不可选但思考可开，不留死角）。 */
const levelOptionsOf = ref => {
  const caps = thinkCapsOf(ref)
  const levels = reasoningLevelsOf(ref)
  if (caps.locked) return [{ value: levels[0] || THINK_LEVEL_ON, label: '恒思考 · ' + levelLabel(levels[0]) }]
  const opts = [{ value: 'off', label: '关闭思考' }]
  if (levels.length) {
    for (const lv of REASONING_LEVELS) {
      if (levels.includes(lv.value)) opts.push({ value: lv.value, label: '思考 · ' + lv.label })
    }
  } else {
    // 模型未登记强度档位：只给开关，强度交网关默认
    opts.unshift({ value: THINK_LEVEL_ON, label: '开启思考' })
  }
  return opts
}
/** 某模型当前选中：off=不思考；档位/ON=思考；恒思考模型恒为开（其首个支持档位） */
const currentLevelOf = ref => {
  const levels = reasoningLevelsOf(ref)
  if (thinkCapsOf(ref).locked) return levels[0] || THINK_LEVEL_ON
  if (!deepOnOf(ref)) return 'off'
  const explicit = levelMap.value[ref]
  if (explicit && levelOptionsOf(ref).some(o => o.value === explicit)) return explicit
  const modelDefault = modelIndex.value[ref]?.defaultReasoningLevel
  if (modelDefault && levels.includes(modelDefault)) return modelDefault
  return levels[0] || THINK_LEVEL_ON
}
/** 按模型记忆的等级选择（与深度思考开关同一套 per-model 记忆风格） */
const levelMap = ref(readLevels())
function readLevels() {
  try {
    const raw = localStorage.getItem('ai_think_level')
    const parsed = raw ? JSON.parse(raw) : {}
    return (parsed && typeof parsed === 'object') ? parsed : {}
  } catch (e) { return {} }
}
/** 写入某模型的思考强度（面板点档位按悬浮模型写；不传=生效模型）：
 *  等级与开关联动记忆，选档位/开启=开思考、选关闭=关思考，落 localStorage 立即生效 */
const setThinkLevel = (v, modelRef) => {
  const target = modelRef || effectiveModel.value
  if (loading.value || thinkCapsOf(target).locked) return
  const on = v !== 'off'
  deepThinkMap.value = { ...deepThinkMap.value, [target]: on ? 1 : 0 }
  levelMap.value = { ...levelMap.value, [target]: v }
  try {
    localStorage.setItem('ai_deep_think', JSON.stringify(deepThinkMap.value))
    localStorage.setItem('ai_think_level', JSON.stringify(levelMap.value))
  } catch (e) { /* 存储不可用忽略 */ }
}
/** 生效模型的当前档位 / 本轮下发给后端的思考强度（空串=不指定，后端回落模型登记默认档位） */
const currentThinkLevel = computed(() => currentLevelOf(effectiveModel.value))
const reasoningLevelParam = computed(() => {
  if (!deepThinkOn.value) return ''
  const v = currentThinkLevel.value
  return (v && v !== 'off' && v !== THINK_LEVEL_ON) ? v : ''
})
/** 某模型的窗口可调区间：null=不可调（未登记窗口/下限，或 min≥max）——面板窗口行保持只读 */
const ctxRangeOf = ref => {
  const info = modelIndex.value[ref]
  if (!info) return null
  const max = info.contextWindow
  const min = info.contextWindowMin
  if (!max || max <= 0 || !min || min <= 0 || min >= max) return null
  return { min, max }
}
/** 档位选项：区间内的 2 的幂点 + 两端（下限不在幂上时补头，上限同理补尾） */
const ctxWindowOptionsOf = ref => {
  const range = ctxRangeOf(ref)
  if (!range) return []
  const mids = CTX_WINDOW_STEPS.filter(v => v > range.min && v < range.max)
  return [range.min, ...mids, range.max].map(v => ({ value: v, label: fmtWindow(v) }))
}
/** 按模型记忆的窗口档位选择（与思考强度同一套 per-model localStorage 记忆风格；未记忆=上限） */
const ctxWindowMap = ref((() => {
  try {
    const raw = localStorage.getItem('ai_ctx_window')
    const parsed = raw ? JSON.parse(raw) : {}
    return (parsed && typeof parsed === 'object') ? parsed : {}
  } catch (e) { return {} }
})())
/** 某模型当前生效窗口：记忆值落在区间内用记忆值，否则默认给最大 */
const effectiveCtxWindowOf = ref => {
  const range = ctxRangeOf(ref)
  if (!range) return null
  const v = ctxWindowMap.value[ref]
  return (v >= range.min && v <= range.max) ? v : range.max
}
/** 面板点选窗口档位：写按模型 localStorage 记忆，下一轮发送立即生效 */
const setCtxWindow = (v, modelRef) => {
  const target = modelRef || effectiveModel.value
  if (loading.value || !ctxRangeOf(target)) return
  ctxWindowMap.value = { ...ctxWindowMap.value, [target]: v }
  try {
    localStorage.setItem('ai_ctx_window', JSON.stringify(ctxWindowMap.value))
  } catch (e) { /* 存储不可用忽略 */ }
}
/** 本轮下发后端的窗口档位（token）：可调模型始终显式下发（含默认上限）；不可调/未登记=null，后端走原逻辑 */
const contextWindowParam = computed(() => {
  const range = ctxRangeOf(effectiveModel.value)
  return range ? effectiveCtxWindowOf(effectiveModel.value) : null
})
const canSend = computed(() => !!(text.value.trim() || pendingImages.value.length || pendingFiles.value.length))
// ==================== 技能（输入框「+」菜单选用，仅对本轮生效） ====================
const skillList = ref([])                 // 可用技能（后端 /skill/available：未停用技能精简列表）
const pickedSkills = ref([])              // 本轮选用的技能名（发送后清空）
const SKILL_AVA_COLORS = ['#e8f1ff', '#fff3e0', '#e8f5e9', '#fdeaf3', '#ede7f6', '#e0f4f1']
const loadSkills = async () => {
  try {
    const r = await listAvailableSkills()
    skillList.value = (r && r.success && Array.isArray(r.data)) ? r.data : []
  } catch (e) { /* 接口不可用时静默：菜单只显示附件入口 */ }
}
const toggleSkill = name => {
  const i = pickedSkills.value.indexOf(name)
  if (i >= 0) pickedSkills.value.splice(i, 1)
  else {
    if (pickedSkills.value.length >= 3) { message.warning('一次最多选用 3 个技能'); return }
    pickedSkills.value.push(name)
  }
}
const skillAvaStyle = name => {
  const i = skillList.value.findIndex(s => s.name === name)
  const color = SKILL_AVA_COLORS[(i < 0 ? name.length : i) % SKILL_AVA_COLORS.length]
  return { background: color }
}
// ==================== 智能体（P5）：本轮作答方，会话不再锁定 ====================
const agentList = ref([])                       // 全部主智能体
// 会话ID → 用户为本会话挑的作答智能体（''=默认助手）。纯前端待选值：P5 起后端没有"首问锁定"，
// 每轮都按请求里的 agentId 解析，所以这一项就是"下一问用谁"，换人立即生效、不需要开新会话。
const agentMap = ref({})
const defaultAgentId = ref('')                  // 默认智能体（isDefault），无则空=全局配置
// 是否存在「默认智能体」（isDefault=1）：有则后端把空 agentId（全局配置）解析为它——
// 「默认（全局配置）」与默认智能体是同一套配置，菜单里只留默认智能体一项，避免两行同义的重复项
const hasDefaultAgent = computed(() => agentList.value.some(a => a.isDefault === 1 || a.isDefault === true))
/** 本会话下一问的作答智能体（''=默认助手：后端解析为 isDefault 那一个，未设默认则走全局配置） */
const currentAgentId = computed({
  get: () => {
    const memo = agentMap.value[currentSessionId.value]
    if (memo !== undefined) return memo
    return defaultAgentId.value || ''
  },
  set: v => { agentMap.value = { ...agentMap.value, [currentSessionId.value]: v || '' } }
})
const pickAgent = id => {
  agentPickerOpen.value = false
  agentMap.value = { ...agentMap.value, [currentSessionId.value]: id || '' }
  if (!id) message.success('已切回「默认助手」')
  else {
    const a = agentList.value.find(x => x.id === id)
    if (a) message.success(`已切换为「${a.name}」，下一问起由它作答`)
  }
}
/** 当前生效的智能体名（空 = 默认助手；P5 起没有"锁定"这一说，换人立即生效） */
const currentAgentName = computed(() => {
  const id = currentAgentId.value
  if (!id) return '默认助手'
  const a = agentList.value.find(x => x.id === id)
  return a ? a.name : '默认助手'
})
/** 当前生效的智能体对象（仅用于 agent-pill 头像；默认助手 = null，不显示头像） */
const currentAgent = computed(() => {
  const id = currentAgentId.value
  if (!id) return null
  return agentList.value.find(x => x.id === id) || null
})
/**
 * 归属徽标的「去重」判定：会话内首条助手消息、或与上一条助手消息归属不同时才标（同一个人全程一致不必重复）。
 * 是否允许显示由排障显示开关叠加判断（调用处 && debugDisplayVisible），本函数只负责去重，不读配置。
 */
const showAgentTag = (m, i) => {
  if (m.role !== 'ai' || !m.agentName) return false
  for (let k = i - 1; k >= 0; k--) {
    const prev = messages.value[k]
    if (prev.role === 'ai') return prev.agentName !== m.agentName
  }
  return true
}
const isAdmin = ref(isAdminSync())
const agentPickerOpen = ref(false)
/** 模型引用 → 展示名（模型库友好名优先，查不到回退原始引用 provider/model） */
/**
 * 模型的展示名。模型库里查不到时（模型被移出列表、所在供应商已停用）退化成引用串里的模型名那一段——
 * 直接把 `供应商UUID/deepseek-v4-pro` 抛到界面上，用户读到的是「这是什么」。
 */
const modelLabelOf = ref => {
  if (!ref) return ''
  const hit = modelIndex.value[ref]?.displayName
  if (hit) return hit
  const i = ref.indexOf('/')
  return i === -1 ? ref : ref.slice(i + 1)
}
/**
 * 「模型已切换」分隔记录：本轮回答与上一条助手消息的落库模型不同时，在本条回答上方挂一条居中分隔。
 * 只在发消息产生新回答时出现（切换选择器本身不产生任何记录）；两侧都有模型引用才比对——
 * 旧消息/工作流轮没有 model，无法判定是否切换，不渲染。返回 {fromLabel,toLabel} 或 null。
 */
const modelSwitchInfo = (m, i) => {
  if (m.role !== 'ai' || !m.model) return null
  for (let k = i - 1; k >= 0; k--) {
    const prev = messages.value[k]
    if (prev.role !== 'ai') continue
    if (!prev.model || prev.model === m.model) return null
    return { fromLabel: modelLabelOf(prev.model), toLabel: modelLabelOf(m.model) }
  }
  return null
}
/**
 * 「已压缩上下文」分隔记录：本轮回答装配历史时，摘要覆盖点比上一轮更靠后 → 分隔线画在这条提问<b>上方</b>，
 * 读作「此线以上的早期对话已折进摘要，以下逐字在场」。
 * <p>手动 /compact 与自动压缩共用这一条判据：手动压缩当场推后了覆盖点，但它不产生消息行，
 * 于是下一轮装配读到的就是那个新值——相对上一轮仍是推进，线正好落在压缩发生的那个缝上
 * （手动压缩当时挂在流尾的临时条也在同一个位置，两者不会同时出现）。
 * <p>落库前那批旧消息没有 summaryUntil：无从判定，不画——线放错位置比不放更坏。
 */
const compactDividerAt = (m, i) => {
  if (m.role !== 'user') return false
  for (let k = i + 1; k < messages.value.length; k++) {
    const cur = messages.value[k]
    if (cur.role === 'user') continue
    const until = Number(cur.tokens?.summaryUntil) || 0
    if (until <= 0) return false
    for (let j = k - 1; j >= 0; j--) {
      const prev = messages.value[j]
      if (prev.role !== 'ai' || prev === cur) continue
      return until > (Number(prev.tokens?.summaryUntil) || 0)
    }
    return true   // 本会话第一条回答就带着覆盖点：说明压缩发生在它之前
  }
  return false
}
const loadAgents = async () => {
  try {
    // 走 available 接口：问答用户可读的精简列表（管理端 /agent/list 仅管理员）
    const r = await listAvailableAgents()
    agentList.value = (r && r.success && Array.isArray(r.data)) ? r.data : []
    // 登记 id→名称，供刷新后还原「本轮咨询了谁」的徽标（见 projections.rememberAgents）。
    // 另拉一份含子智能体的名称表：agentList 刻意剔除子智能体（它们不当问答角色），
    // 只登记它的话，被咨询的子智能体在徽标上会退化成一串裸 id。拉失败不影响问答，静默即可。
    rememberAgents(agentList.value)
    try {
      const cs = await listConsultableAgents()
      if (cs && cs.success && Array.isArray(cs.data)) rememberAgents(cs.data)
    } catch (e) { /* 非关键路径 */ }
    // 优先默认助手；未设默认时用列表第一个，保证下拉总有可选项
    const def = agentList.value.find(a => a.isDefault === 1 || a.isDefault === true) || agentList.value[0]
    defaultAgentId.value = def ? def.id : ''
    // 当前会话尚未挑过作答智能体：落到默认智能体（P5 起没有「自动派遣」这一档）
    if (!(currentSessionId.value in agentMap.value)) {
      agentMap.value = { ...agentMap.value, [currentSessionId.value]: defaultAgentId.value || '' }
    }
  } catch (e) { /* 接口不可用时静默：选择器回退为「默认（全局配置）」 */ }
}
const currentSessionId = ref(null)
// 「当前会话正在回答」：流式记录按会话各存一条（chatStreams，store.js 模块单例，跨路由存活）。
// 语义变化：以前是页面全局布尔（任何会话在答都不能切会话/新建，点击被静默吞掉），
// 现在只表示"正在看的这个会话在答"——其它会话后台流不影响新建/切换/发送；
// 同一会话内仍互斥（输入/重新生成/停止都作用于当前会话），守卫写法不用动。
const loading = computed(() => chatStreams.has(currentSessionId.value))
const messages = ref([])
const currentSessionTitle = computed(() => {
  const s = sessionStore.list.find(x => x.id === currentSessionId.value)
  return s?.title || '新对话'
})
const roundCount = computed(() => messages.value.filter(m => m.role === 'ai' && !m.loading).length)
// ==================== 模型切换：会话级覆盖 > 智能体 > 个人默认 > 全局 ====================
const modelMap = ref({})            // 会话ID → 用户手动选择的模型引用（按会话记忆；空=跟随）
// 会话ID → 那个引用此刻还能不能用（后端判据随会话列表下发：停用供应商、把模型从列表移除
// 都不会让会话里钉着的历史值自己消失，界面必须报出来，而不是等回答时撞一句网关错）
const modelIssueMap = ref({})
const currentOverrideModel = computed({
  get: () => modelMap.value[currentSessionId.value] || '',
  set: v => {
    const val = v || ''
    const sid = currentSessionId.value
    modelMap.value = { ...modelMap.value, [sid]: val }
    // 切换即落库（会话级模型覆盖，刷新/换端后仍保留；清除=空串回个人默认）。
    // 失败要提醒：静默吞掉的话用户以为已记住，刷新后选择丢失无从排查。
    // 会话尚无 ID（极端时序）只记本地，等首问随载荷落库
    if (sid) updateSessionModelApi(sid, val).catch(e => message.error(`会话模型保存失败：${e?.message || '请稍后重试'}`))
  }
})
/** 会话列表 → modelMap 回填（seed-once：本地已有的选择不动——切换已即时落库，避免慢响应竞态把 UI 回退） */
const seedSessionModels = () => {
  const patch = {}
  const issues = {}
  for (const s of sessionStore.list) {
    if (!s || !s.id) continue
    if (!(s.id in modelMap.value)) patch[s.id] = s.model || ''
    // 可用性每次重算：它随管理员停启用供应商/模型而变，不像选择那样归本地
    issues[s.id] = s.modelIssue || ''
  }
  if (Object.keys(patch).length) modelMap.value = { ...modelMap.value, ...patch }
  modelIssueMap.value = { ...modelIssueMap.value, ...issues }
}
// 监听列表本身，而不是在挂载流程里调一次：会话列表由侧栏（AppLayout）与聊天页各自触发加载，
// 并发时 loadSessions 的加载代次守卫会丢弃较早的一次（列表还没写入），挂载时点读到的可能是空列表。
// deep 兼顾「查看更多」的 push 增量；immediate 兼顾列表在引擎初始化前就已加载的情形。
// seed 幂等且只做字典合并，列表每次变动重跑无副作用。
watch(() => sessionStore.list, seedSessionModels, { deep: true, immediate: true })
const userDefaultModel = ref('')    // 个人默认模型（个人设置，后端 /user/preference）
const userDefaultModelIssue = ref('')  // 它此刻能不能用（同样由后端判，空=可用/未设置）
const modelIndex = ref({})          // 引用 → { displayName, providerName, icon }（展示映射）

/** 实际生效的模型（引用或遗留名）：会话覆盖 > 个人默认（智能体不绑模型、全局兜底已移除；空=未指定，发送时引导选择） */
const effectiveModel = computed(() => {
  return currentOverrideModel.value || userDefaultModel.value
})
/** 生效模型的展示名（引用串在模型库里映射成 友好名；查不到回退模型名那一段，不抛供应商 UUID） */
const effectiveModelLabel = computed(() => modelLabelOf(effectiveModel.value))
const effectiveModelIcon = computed(() => {
  const info = modelIndex.value[effectiveModel.value]
  return info ? info.icon : ''
})
const effectiveModelProvider = computed(() => {
  const info = modelIndex.value[effectiveModel.value]
  return info ? info.providerName : ''
})
/** 生效模型来源（状态栏打标）：会话指定 / 个人默认；都没有则不显示标签 */
const modelSourceLabel = computed(() => {
  if (currentOverrideModel.value) return '会话指定'
  if (userDefaultModel.value) return '个人默认'
  return ''
})
/**
 * 生效模型此刻不能用的原因（空=能用）。报的是「这一轮根本发不出去」的那一份：
 * 会话钉着失效的就说会话的，跟随个人默认时就说默认的。
 * 判定归后端（要看供应商与模型登记的启停状态），前端只负责把它摆到选择器上。
 */
const effectiveModelIssue = computed(() => {
  if (currentOverrideModel.value) return modelIssueMap.value[currentSessionId.value] || ''
  return userDefaultModel.value ? userDefaultModelIssue.value : ''
})
// 「检索调试」/「加入评测集」菜单项专属（管理端点，普通用户必 403）：只在管理员身份下从 /config 读取
const debugEntryVisible = ref(false)
// 排障显示开关（chat.retrievalDebugEnabled，设置页「检索调试入口」）：
// 统一控制回答气泡上的「由 X 回答」归属徽标、「已派遣 X」路由提示、引用分值（右栏来源列表分数 + 角标悬浮卡分数）。
// 值必须走 /config/public（管理员/普通用户都能读）——这些是"给不给用户看"的显隐，普通用户也要拿到同一个开关值；
// /config 是管理端点，普通用户调它 403 并触发全局 403 提示（角色未授权），故不复用。
const debugDisplayVisible = ref(false)
// 空态示例问题（新会话空态的引导卡）：生效文案走 /config/public 的 ui.sampleQuestions
// （个人覆盖 > 系统全局，管理员在系统设置改默认，用户在个人设置→对话偏好改自己的或关掉）。
// null=配置还没取到/取失败（用内置默认，别让用户对着空白首屏）；空串=明确不展示
const sampleQuestionsRaw = ref(null)
const sampleQuestions = computed(() => sampleQuestionsRaw.value == null
  ? DEFAULT_SAMPLE_QUESTIONS : parseSampleQuestions(sampleQuestionsRaw.value))
// 右栏「最近一次检索/本次用量」的锚定轮。带计划卡的行也算：被「继续对话」取代那轮正文留空，
// 但它确实检索过，不算进来右栏就会退回更早那一轮
const lastAi = computed(() => [...messages.value].reverse().find(m => m.role === 'ai' && !m.loading && (m.content || m.sources?.length || m.planCard)))
const lastRetrieved = computed(() => lastAi.value?.retrieved || null)
const lastSources = computed(() => lastAi.value?.sources || [])
// 引用来源按文档分组：先看到"引用了哪几个文档、各几段"，再按需展开看具体片段
// （平铺 N 行时同一文档的片段会重复出现文件名，反而看不出引用了几个来源）
// 分组内核抽为纯函数 groupSources（projections）：这里只保留响应式包装
const groupedSources = computed(() => groupSources(lastSources.value))

// 本次用量（Token 消耗可视化，1.9）：来自 done 事件的 tokens（上下文实际/预算/块数 + 输出估算）
const lastTokens = computed(() => lastAi.value?.tokens || null)
// 流式中的 prompt 侧用量（后端 usage 事件预下发的估算，onUsage 挂在 msg.tokensPreview）：
// 只看最新一轮 AI 消息——该轮生成中且估算已到才返回；否则为 null（回落 lastTokens，
// 与旧口径一致：流式期间显示上一完成轮的用量）。done 的实测 tokens 到达后自然切换为终值。
const liveTokensPreview = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const m = messages.value[i]
    if (m.role !== 'ai') continue
    if (m.loading && m.tokensPreview) return m.tokensPreview
    break
  }
  return null
})
// 容量圆环/明细卡数据源：流式估算优先（生成一开始就亮），无估算回落已完成轮的实测 tokens
const ctxTokens = computed(() => liveTokensPreview.value || lastTokens.value)
// ==================== 上下文容量面板（工具栏圆环悬浮：用量/窗口 + 分类占比 + 缓存命中） ====================
/** 分类展示名与配色（与后端 agentx FrameworkCtxParts 的键一一对应；占比条按此顺序堆叠）。
 *  按 AgentScope 的消息结构分类：MsgRole 只有 SYSTEM/USER/ASSISTANT/TOOL 四种，内容块再按类型细分。
 *  手写运行时（工作流旁路）仍按旧十类下发，本表只认框架那套键——两套分类不同时展示，
 *  键名对不上时那一类不渲染（下面 filter 天然过滤掉），不会出现半截错位的构成条。 */
const CTX_PART_META = [
  { key: 'system', label: '系统提示词', color: '#722ed1' },
  { key: 'history', label: '历史对话', color: '#1677ff' },
  { key: 'input', label: '本轮提问', color: '#8c8c8c' },
  { key: 'retrieval', label: '检索资料', color: '#52c41a' },
  { key: 'toolOutput', label: '工具结果', color: '#fa8c16' },
  { key: 'thinking', label: '思考内容', color: '#13c2c2' },
  { key: 'toolSchema', label: '工具声明', color: '#eb2f96' }
]
/** 容量数据：窗口取模型登记/用户档位（tokens.window 优先，回落 modelIndex），用量取本轮结束时的
 *  上下文占用（tokens.context，无则 prompt）；数据源走 ctxTokens（流式期间=usage 帧，完成后=done 的
 *  实测 tokens），生成一开始即点亮。
 *  用量为什么优先 context 而不是 prompt：prompt 是各轮请求的输入之和（账单口径，工具调用会让它
 *  逐轮累加、远大于真实占用），而本面板回答的是「窗口被占了多少」——分类之和就是按 context 校准的，
 *  分子分母必须同一个口径，否则顶部百分比与下面分段条各说各话。 */
const ctxCapData = computed(() => {
  const t = ctxTokens.value
  const info = modelIndex.value[effectiveModel.value]
  const window_ = (t && t.window) || info?.contextWindow || 0
  const used = t ? (Number(t.context) || Number(t.prompt) || 0) : 0
  const parts = (t && t.parts && typeof t.parts === 'object') ? t.parts : null
  const rows = parts
    ? CTX_PART_META.filter(m => Number(parts[m.key]) > 0)
        .map(m => ({ ...m, tokens: Number(parts[m.key]) }))
    : []
  const sum = rows.reduce((a, r) => a + r.tokens, 0)
  const pct = window_ > 0 ? Math.min(100, Math.round(used / window_ * 1000) / 10) : 0
  // 缓存命中：优先 cachedLast（末轮命中量，与上面的 used 同为「此刻上下文」口径）。
  // 没有它才回落 cached（各轮命中之和）——那是账单口径，配上 context 当分母能算出 >100% 的命中率。
  const cached = t ? (Number(t.cachedLast) || Number(t.cached) || 0) : 0
  return {
    window: window_, used, pct, rows, sum,
    // 填充条宽=用量/窗口（不取整：窗口大用量小时 pct 取整会成 0 宽），填充内段宽按构成比分
    fillPct: window_ > 0 ? Math.min(100, used / window_ * 100) : 0,
    // 分类占比以分类之和为分母（后端按 context 校准后两者相等；无 parts 时退化为空列表）
    pctOf: r => sum > 0 ? (r.tokens / sum * 100) : 0,
    cached, cacheRate: (cached > 0 && used > 0) ? Math.round(cached / used * 1000) / 10 : null
  }
})
// 工具栏容量圆环：r=7.5 周长固定，按 pct 撑 stroke-dasharray；告警档位与右栏占用条一致
const CTX_RING_C = 2 * Math.PI * 7.5
const ctxRingDash = computed(() => {
  const p = Math.min(100, Math.max(0, ctxCapData.value.pct))
  return `${(p / 100 * CTX_RING_C).toFixed(2)} ${CTX_RING_C.toFixed(2)}`
})
const ctxRingLevel = computed(() => ctxCapData.value.pct >= 95 ? 'danger' : ctxCapData.value.pct >= 80 ? 'warn' : '')
// ==================== 右栏：运行控制 / 本会话产物（执行过程卡已移除：消息流内已有完整执行明细） ====================
// panelAi = 最后一轮 AI 消息（含进行中）——供「运行控制」卡（停止/重试本轮）定位重发目标，
// 与「最近一次检索/本次用量」用的 lastAi（仅已完成轮）口径不同
const panelAi = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    if (messages.value[i].role === 'ai') return messages.value[i]
  }
  return null
})
// 右栏「重试本轮」：与消息流内重试同源——regenerate 向前配对用户问题后整轮重发；
// 工具级单步重试需要后端重执行机制，暂未实现
const retryPanelRound = () => {
  const m = panelAi.value
  if (!m) return
  const idx = messages.value.indexOf(m)
  if (idx >= 0) regenerate(idx)
}
// 本会话产物：汇总所有轮次的 artifact（历史恢复的消息同样带 artifacts），最新一轮在前
const sessionArtifacts = computed(() => {
  const out = []
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const m = messages.value[i]
    if (m.role !== 'ai' || !Array.isArray(m.artifacts)) continue
    for (const a of m.artifacts) if (a && a.url) out.push(a)
  }
  return out
})

// ==================== 产物签名换发 ====================
// 产物下载 URL 带 HMAC 签名，有效期 1 小时（images.authExpireSeconds）。SSE 下发与历史接口
// 给的那份 URL「签好就不变」，页面开久后点下载必然 401「图片链接无效或已过期」——而同一文件在
// 「我的产物」页正常，因为那个列表每次进页面都现场重签。这里在会话产物出现/切换时换一批新鲜签名，
// 就地改写各消息里 artifact 的 url（保持对象引用不变，视图自动更新）。
// 入参用产物 id 而非 url：服务端只给库里归属自己的产物重签，前端无法构造任意路径。
let refreshSeq = 0
const refreshArtifactSigns = async () => {
  const seq = ++refreshSeq
  // 收集本会话全部产物（按 id 去重；无 id 的历史遗留条目跳过）
  const byId = new Map()
  for (const m of messages.value) {
    if (m.role !== 'ai' || !Array.isArray(m.artifacts)) continue
    for (const a of m.artifacts) if (a && a.id && a.url) byId.set(a.id, a)
  }
  if (!byId.size) return
  try {
    const res = await refreshArtifactSignsApi([...byId.keys()])
    // 请求期间可能又切了会话、又出了新产物，丢弃过期结果，避免把 A 会话的签名写进 B 会话
    if (seq !== refreshSeq) return
    const rows = Array.isArray(res?.data) ? res.data : []
    for (const r of rows) {
      const a = byId.get(r?.id)
      if (a && r.url) a.url = r.url
    }
  } catch (e) { /* 换签失败静默降级：仍可沿用旧签名，不阻断浏览 */ }
}
// 会话累计 tokens：只累加消息里真实记录的用量（tokens 随 done 事件下发、未落库，
// 历史恢复的轮没有该字段——不计入，也不冒充 0）
const sessionTokens = computed(() => {
  let total = 0, output = 0, rounds = 0
  for (const m of messages.value) {
    if (m.role !== 'ai' || !m.tokens || typeof m.tokens !== 'object') continue
    rounds++
    total += Number(m.tokens.total) || 0
    output += Number(m.tokens.output) || 0
  }
  return { total, output, rounds }
})
const sessionTokensLabel = computed(() => sessionTokens.value.rounds ? fmtTokens(sessionTokens.value.total) + ' tokens' : '用量未记录')
// 会话累计检索/工具：检索轮数按「该轮有检索行或引用」计；精确检索次数按 searchKnowledge 终态记录计
const sessionRetrieval = computed(() => {
  let rounds = 0, refs = 0, search = 0, tools = 0
  for (const m of messages.value) {
    if (m.role !== 'ai') continue
    const list = toolCallsView(m.toolCalls)
    tools += list.length
    for (const t of list) if (t.name === 'searchKnowledge' && t.status !== 'start') search++
    if (m.retrieved || (Array.isArray(m.sources) && m.sources.length)) {
      rounds++
      refs += Array.isArray(m.sources) ? m.sources.length : 0
    }
  }
  return { rounds, refs, search, tools }
})
// 上下文占用条（context/budget）已删：budget 是 legacy 时代的检索配额（窗口×安全系数−最大输出），
// 框架引擎下这套分配不存在，报上来只会让「上下文 3.4K / 预算 120K」里的 120K 无人认领。
// 上下文占用了窗口多少，现在由容量卡的 ctxCapData 回答（context/window，>80% 警告 / >95% 危险）。
// ==================== 图片（「+」菜单选择 / 拖入 / 粘贴 → 压缩为 dataURL） ====================
const pendingImages = ref([])
const compressImage = file => new Promise((resolve, reject) => {
  const img = new Image()
  const url = URL.createObjectURL(file)
  img.onload = () => {
    const max = 1280
    let { width, height } = img
    if (width > max || height > max) {
      const ratio = Math.min(max / width, max / height)
      width = Math.round(width * ratio); height = Math.round(height * ratio)
    }
    const canvas = document.createElement('canvas')
    canvas.width = width; canvas.height = height
    canvas.getContext('2d').drawImage(img, 0, 0, width, height)
    URL.revokeObjectURL(url)
    resolve(canvas.toDataURL('image/jpeg', 0.85))
  }
  img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('图片加载失败')) }
  img.src = url
})
const removePendingImage = i => pendingImages.value.splice(i, 1)
// ==================== 附件上传（「+」菜单选择/拖入/粘贴 → 先传换 fileId，问答请求只带 fileId） ====================
// 此前附件以 base64 内联在 /chat 的 JSON body 里：5×15MB 会打出 ~100MB 的字符串，弱网必挂、
// 断线要整包重传、后端也得先把整包读进内存。现在文件先走 multipart 上传落盘换号，请求体只带 id。
const MAX_FILES = 5
const MAX_FILE_MB = 15
// 与后端 ChatAttachmentService 的类型白名单一致（doc/ppt 老格式未引入 scratchpad，不支持）
const SUPPORTED_EXTS = ['pdf', 'docx', 'xls', 'xlsx', 'pptx', 'txt', 'md', 'markdown', 'csv', 'tsv', 'json', 'log',
  'xml', 'yml', 'yaml', 'html', 'htm', 'java', 'js', 'ts', 'jsx', 'tsx', 'vue', 'py', 'sql', 'sh', 'bat',
  'c', 'h', 'cpp', 'hpp', 'cs', 'go', 'rs', 'rb', 'php', 'css', 'scss', 'less', 'properties', 'ini', 'conf', 'toml']
const pendingFiles = ref([])
const extOf = name => {
  const dot = (name || '').lastIndexOf('.')
  return dot < 0 ? '' : name.slice(dot + 1).toLowerCase()
}
/** 上传单个附件换 fileId（上传中就挂进列表，用户能看到进度/失败态，而不是点发送后才知道没传上去）；
 *  list 缺省挂主输入框，编辑卡复用时传自己的列表；extra 是只给本地呈现用的附加字段（如粘贴文本的原文） */
const uploadOneFile = async (f, list = pendingFiles, extra) => {
  // 先 reactive 再入列：直接改裸对象绕过代理的 set 陷阱，进度/失败态不会重渲染，
  // 卡片会停在「上传中…」直到下一次无关渲染才突然变成体积（实测踩过）
  const item = reactive({ name: f.name, size: f.size, mime: f.type || '', fileId: '', uploading: true, error: '', ...(extra || {}) })
  list.value.push(item)
  try {
    const r = await uploadChatAttachment(f)
    const d = r?.data || {}
    if (!d.fileId) throw new Error('上传未返回文件标识')
    item.fileId = d.fileId
    item.name = d.name || item.name
    item.mime = d.mime || item.mime
    if (d.size) item.size = d.size
    item.uploading = false
  } catch (e) {
    item.uploading = false
    item.error = e.message || '上传失败'
    message.error(`附件上传失败：${f.name} — ${item.error}`)
  }
}
/** 是否存在还没传完的附件（发送前拦一道：不然用户以为发出去了，其实附件没带上） */
const hasUploadingFile = () => pendingFiles.value.some(f => f.uploading)
/** 统一入口：图片走压缩预览，其余按附件校验后挂起（类型/数量/体积，口径与后端校验一致）；
 *  target 缺省挂主输入框，编辑卡复用时传 { images, files } 两个 ref 列表 */
const addFiles = (files, target, extra) => {
  const imgList = target?.images || pendingImages
  const fileList = target?.files || pendingFiles
  for (const f of files) {
    // 粘贴进来的文件可能没有名字（剪贴板只给了个 blob），提示里不能留一个空位
    const label = f.name || '未命名文件'
    if (f.type.startsWith('image/')) {
      if (imgList.value.length >= 5) { message.warning('最多上传 5 张图片'); continue }
      compressImage(f).then(dataUrl => imgList.value.push({ dataUrl }))
        .catch(() => message.error(`图片处理失败: ${label}`))
      continue
    }
    if (fileList.value.length >= MAX_FILES) { message.warning(`一次最多上传 ${MAX_FILES} 个附件`); break }
    if (!SUPPORTED_EXTS.includes(extOf(f.name))) {
      message.warning(`暂不支持的附件类型：${label}（支持 PDF / Word / Excel / PPT / 文本与代码文件）`)
      continue
    }
    if (f.size > MAX_FILE_MB * 1024 * 1024) { message.warning(`单个附件不能超过 ${MAX_FILE_MB}MB：${label}`); continue }
    uploadOneFile(f, fileList, extra)
  }
}
const removePendingFile = i => pendingFiles.value.splice(i, 1)
// ==================== 粘贴文件 → 附件 ====================
/** 剪贴板里的文件：files 命中即用；部分浏览器只把它挂在 items 上，空了再退一路。
 *  两路不合流，否则同一个文件会入列两次 */
const pasteFilesOf = cd => {
  const files = Array.from(cd?.files || [])
  if (files.length) return files
  return Array.from(cd?.items || []).filter(i => i.kind === 'file').map(i => i.getAsFile()).filter(Boolean)
}
/** 粘贴带文件（截图、也带 PDF / Word / 文本等真附件）：与「+」选择、拖入走同一分流口径。
 *  必须 preventDefault——否则浏览器会把文件名当文本插进正文；返回 true 表示已消费，调用方按需截断冒泡 */
const takePastedFiles = (e, target) => {
  const files = pasteFilesOf(e.clipboardData)
  if (!files.length) return false
  e.preventDefault()
  addFiles(files, target)
  return true
}
// ==================== 长文本粘贴 →「粘贴的文本」附件 ====================
// 整篇文档、大段日志塞进输入框既读不清也发不出去（问题正文有长度上限，超限直接报错）。
// 超过阈值就把它包成一个 .txt 文件，走与「选择文件」完全相同的管线：卡片呈现、随本轮上传、
// 内容进上下文。粘贴文本的原文只留在内存（卡片点开看全文用），落库的仍只有名称/类型/体积。
const PASTE_TEXT_MIN_CHARS = 800
/** 同一次可连续粘贴多段：重名时补序号，避免编辑卡按名称回填体积时串号 */
const pasteNameOf = list => {
  const used = new Set((list || []).map(f => f.name))
  if (!used.has(`${PASTE_TEXT_BASE}.txt`)) return `${PASTE_TEXT_BASE}.txt`
  let n = 2
  while (used.has(`${PASTE_TEXT_BASE} ${n}.txt`)) n++
  return `${PASTE_TEXT_BASE} ${n}.txt`
}
/** 输入框粘贴事件里取长文本：带文件的粘贴让给文件管线，短文本照常内联可编辑。
 *  返回 true 表示这次粘贴已被消费（原文不再落进输入框），调用方按需要截断冒泡 */
const takePastedText = (e, target) => {
  const cd = e.clipboardData
  if (!cd || pasteFilesOf(cd).length) return false
  const raw = cd.getData('text/plain')
  if (!raw || raw.length < PASTE_TEXT_MIN_CHARS) return false
  const fileList = target?.files || pendingFiles
  e.preventDefault()
  addFiles([new File([raw], pasteNameOf(fileList.value), { type: 'text/plain' })], target,
    { paste: true, text: raw })
  message.info('粘贴的长文本已转为附件，点卡片可查看全文')
  return true
}
// ==================== @ 引用（本轮显式指定知识库/文档/智能体） ====================
// 语义：kb=本轮检索收窄到该库；doc=该文档内容块强制前置进上下文（不经检索、不受相关性门/去冗余约束）；
//       agent=临时委派该智能体作答本轮（人设/知识库/工具整轮按它执行，会话绑定不变——落点在后端轮级覆盖）。
// 交互：输入框敲 @ 唤起候选面板（面板不抢焦点，筛选词实时取「@ 到光标」之间的正文 + ↑↓ 选 + Enter 确认 + 多选），
//       选中项以 chip 显示在输入框上方。引用字符 @ 本身照常留在输入框里（用户可任意位置输入 @），
//       确认后会把「@ + 筛选词」这一段从正文里摘掉，不留残渣。
// 引用只对当轮生效（与会话级智能体绑定不同）：不落库、不跨轮继承，重新生成时随内存消息重发。
const mentionOpen = ref(false)
const mentionTab = ref('kb')
const mentionQuery = ref('')
const mentionLoading = ref(false)
const mentionKbs = ref([])
const mentionDocs = ref([])
const pendingMentions = ref([])
const mentionHi = ref(0)
const MAX_MENTIONS = 10

const isMentioned = (type, id) => pendingMentions.value.some(m => m.type === type && m.id === id)
const toggleMention = (type, item) => {
  const id = item.id
  if (isMentioned(type, id)) {
    pendingMentions.value = pendingMentions.value.filter(m => !(m.type === type && m.id === id))
    return
  }
  // 智能体委派是单主语：一轮只能由一个智能体作答，再选一个=替换（不是多选累加）。
  // 总量仍守 MAX_MENTIONS（后端 chat.maxMentionsPerMessage 同限，超了会 400）
  if (type === 'agent') {
    const hadAgent = pendingMentions.value.some(m => m.type === 'agent')
    if (!hadAgent && pendingMentions.value.length >= MAX_MENTIONS) {
      message.warning(`一次最多引用 ${MAX_MENTIONS} 个知识库/文档/智能体`)
      return
    }
    pendingMentions.value = pendingMentions.value.filter(m => m.type !== 'agent')
  } else if (pendingMentions.value.length >= MAX_MENTIONS) {
    message.warning(`一次最多引用 ${MAX_MENTIONS} 个知识库/文档/智能体`)
    return
  }
  pendingMentions.value.push({
    type,
    id,
    name: type === 'kb' ? item.name : (type === 'agent' ? item.name : item.fileName),
    kbId: type === 'doc' ? (item.kbId || '') : ''
  })
}
const removeMention = i => pendingMentions.value.splice(i, 1)
/** 切库/文档/智能体：候选集换了，高亮下标要收敛回合法范围（否则 kb 第 20 条切到 doc 只有 3 条时按 Enter 会选空） */
const switchMentionTab = t => {
  mentionTab.value = t
  const n = t === 'kb' ? mentionKbFiltered.value.length
    : t === 'agent' ? mentionAgentFiltered.value.length : mentionDocFiltered.value.length
  if (mentionHi.value >= n) mentionHi.value = 0
}

/** 候选懒加载（首次打开面板时拉取）：库按共享范围过滤（/kb/list 已做），文档同理（/document/list 已做） */
const loadMentionCandidates = async () => {
  mentionLoading.value = true
  try {
    const [kbRes, docRes] = await Promise.all([listKnowledgeBases(), listDocuments()])
    mentionKbs.value = (kbRes?.data || []).map(k => ({
      id: k.id, name: k.name, desc: k.description || '', docCount: k.docCount
    }))
    mentionDocs.value = (docRes?.data || []).map(d => ({
      id: d.id, fileName: d.fileName, kbId: d.kbId, status: d.status
    }))
  } catch (e) {
    message.error('引用候选加载失败：' + (e.message || ''))
  } finally {
    mentionLoading.value = false
  }
}

/**
 * 文档可用性提示（与文档页状态口径一致：0=已入库 / 1=已停用 / 2=解析中 / 3=解析失败）。
 * 只有 0 能引用出内容，其余如实说明——不能让用户 @ 了一个还没解析完的文档后以为系统没生效。
 */
const mentionDocStatus = d => {
  if (d.status === 0) return '已入库'
  if (d.status === 1) return '已停用（不参与引用）'
  if (d.status === 2) return '解析中（引用后可能没有内容）'
  if (d.status === 3) return '解析失败（引用不到内容）'
  return ''
}

const mentionMatch = (text, q) => !q || String(text || '').toLowerCase().includes(q.toLowerCase())
// mentionQuery 现在直接来自输入框正文（「@ 到光标」之间），不再有唤起键混进搜索框的问题
const mentionQueryNorm = computed(() => mentionQuery.value.trim())
const mentionKbFiltered = computed(() =>
  mentionKbs.value.filter(k => mentionMatch(k.name, mentionQueryNorm.value) || mentionMatch(k.desc, mentionQueryNorm.value)))
// 智能体候选复用对话页下拉的同一份可读主智能体列表（available 口径：不含子智能体、按共享范围过滤）
const mentionAgents = computed(() =>
  agentList.value.map(a => ({ id: a.id, name: a.name, desc: a.description || '', icon: a.icon, isBuiltin: a.isBuiltin })))
const mentionAgentFiltered = computed(() =>
  mentionAgents.value.filter(a => mentionMatch(a.name, mentionQueryNorm.value) || mentionMatch(a.desc, mentionQueryNorm.value)))
const mentionDocFiltered = computed(() =>
  mentionDocs.value
    .filter(d => mentionMatch(d.fileName, mentionQueryNorm.value))
    .slice(0, 120))
const MAX_HISTORY_REFS = 10
const pendingHistoryRefs = ref([])
// 候选池：当前会话已落库的消息（有 messageId=服务端已确认、非流式中、正文非空）。
// 历史回放的消息自带 messageId（getHistory 下发），当场发的消息在 done 事件回填后也可引用
const histPool = computed(() => messages.value.filter(m =>
  m.messageId && !m.loading && m.content && String(m.content).trim()
    && (m.role === 'user' || m.role === 'ai' || m.role === 'assistant')))
const isHistPicked = id => pendingHistoryRefs.value.some(h => h.messageId === id)
const toggleHistoryRef = m => {
  if (isHistPicked(m.messageId)) {
    pendingHistoryRefs.value = pendingHistoryRefs.value.filter(h => h.messageId !== m.messageId)
    return
  }
  if (pendingHistoryRefs.value.length >= MAX_HISTORY_REFS) {
    message.warning(`一次最多引用 ${MAX_HISTORY_REFS} 条历史消息`)
    return
  }
  pendingHistoryRefs.value.push({ messageId: m.messageId, role: m.role, digest: histItemDigest(m) })
}
const removeHistoryRef = i => pendingHistoryRefs.value.splice(i, 1)
// ==================== 会话 ====================
// 失效会话回退防重入：回退链路（autoPick 选中的会话）再失败时不级联触发
let sessionRecovering = false
/**
 * 各会话当前任务清单：sid → {turnId, items:[{content,status}], updatedAt}。
 * 实时由 SSE 的 todo 事件写入，切会话/刷新按需回捞（后端整表覆盖，只存最新一份）。
 */
const todosMap = ref({})
const sessionTodos = computed(() => todosMap.value[currentSessionId.value] || null)
function setTodos (sid, doc) { todosMap.value = { ...todosMap.value, [sid]: doc } }

/** switchSession 的在途加载：同一 sid 只允许一个 getHistory 在飞，重复调用直接复用它的结果。
 *  为什么需要：刷新落地时会连着调两次同一个 sid——一次来自首屏/地址栏，一次来自
 *  finishResumedShell（接流气泡收场后按历史重显）。两次都 await getHistory，谁先返回谁写
 *  messages.value，晚返回的那份就会用**旧快照**覆盖掉已经接回视图的 live 气泡，
 *  症状是「正在回答时刷一下，正在跑的那一条连同它上面的提问一起没了」。 */
const historyLoads = new Map()

const switchSession = async sid => {
  // 不再被"正在回答"拦截：流式回调改写的是 chatStreams 里的消息对象，切走不影响后台流
  currentSessionId.value = sid
  // 该会话已存的模型覆盖：列表已加载的会话由 sessionStore.list 的 watch 回填；
  // 列表外的会话（通知/分享深链进老会话，或侧栏尚未翻到）在这里按需拉一次——
  // 否则选择器会显示个人默认，而后端仍按会话已存模型作答，界面与真实不符
  if (sid && !(sid in modelMap.value)) {
    getSessionModelApi(sid).then(r => {
      // 迟到的响应不覆盖界面上已改的选择（用户在这期间又切了模型）
      if (r && r.success && !(sid in modelMap.value)) {
        modelMap.value = { ...modelMap.value, [sid]: r.data?.model || '' }
      }
    }).catch(() => {})
  }
  // 任务清单：该会话没拉过才捞一次（右栏卡据此回显；正在跑的那轮由 todo 事件实时覆盖）
  if (sid && !(sid in todosMap.value)) {
    getSessionTodosApi(sid).then(r => {
      if (r && r.success && r.data?.todos && !(sid in todosMap.value)) setTodos(sid, r.data.todos)
    }).catch(() => {})
  }
  // 同步 URL query：侧边栏高亮与刷新恢复都依赖 sid 在地址上。
  // 必须保留兄弟参数：?approval=<id>（工具审批通知深链）直接写 query:{sid} 会被抹掉，
  // 恢复横幅能否出现变成异步时序的侥幸（fetch 先回才亮），刷新后则永久丢失。
  router.replace({ path: hooks.chatPath, query: { ...route.query, sid } }).catch(() => {})
  // 同一会话已在加载：复用它的结果，别再发一次请求、也别让两份快照互相覆盖
  if (historyLoads.has(sid)) return historyLoads.get(sid)
  const task = (async () => {
  try {
    // 403（会话归属拒绝）由本函数静默回退处理，不触发全局提示
    const r = await getHistory(sid, { silentForbidden: true })
    // 快速连续切换时晚到的历史响应不覆盖当前视图
    if (r.success && Array.isArray(r.data) && currentSessionId.value === sid) {
      const list = r.data
        // 带 plan 快照的行即使正文为空也要留：被「继续对话」取代的那轮正文就是空的（状态只写在
        // 计划卡角标上），只按正文过滤会让整张计划卡随刷新一起蒸发
        .filter(m => m && (m.content || (Array.isArray(m.images) && m.images.length) || m.plan))
        .map(m => {
          const msg = {
            role: m.role === 'user' ? 'user' : 'ai',
            content: String(m.content || ''),
            messageId: m.messageId || m.id || null,
            // 本轮身份（随消息落库）：气泡「过程」按它取这一轮的事件账本；存量消息无此字段则无入口
            turnId: typeof m.turnId === 'string' ? m.turnId : '',
            fb: (m.fb === 0 || m.fb === 1) ? m.fb : null,
            images: Array.isArray(m.images) ? m.images : [],
            attachments: Array.isArray(m.attachments) ? m.attachments : [],
            sources: Array.isArray(m.sources) ? m.sources : [],
            // 「接下来可以」推荐（随消息落库后回显）：此前恒为空数组，刷新后推荐整块消失
            related: Array.isArray(m.related) ? m.related : [],
            // 本轮降级事件（随消息落库后回显）：此前只活在流式那一瞬，刷新后降级条整块消失
            degradations: Array.isArray(m.degradations) ? m.degradations : [],
            // 本轮警示条（同上）：沙盒基建异常这类当场看得见的异常，刷新后必须还在
            warnMsg: typeof m.warnMsg === 'string' ? m.warnMsg : '',
            // @ 引用（随用户消息常驻落库）：刷新/历史回显保留引用标注，重新生成/编辑重发据此原样带出
            mentions: Array.isArray(m.mentions) ? m.mentions : [],
            // # 历史引用（同上）
            historyRefs: Array.isArray(m.historyRefs) ? m.historyRefs : [],
            // 本轮终止原因（叫停且该轮没产出回答时记在用户提问上）：刷新后据此在问题下补一行说明
            endReason: m.endReason || '',
            thinking: m.thinking || '',
            time: m.createTime ? new Date(m.createTime).getTime() : null,
            artifacts: Array.isArray(m.artifacts) ? m.artifacts : [],
            toolCalls: Array.isArray(m.toolCalls) ? m.toolCalls : [],
            // 计划批准卡（随助手消息落库）：历史按轮重建气泡里的计划卡（已批准/未批准 + 末版正文 + 修改记录），
            // 有多少轮计划就回显多少张。此前计划只活在实时流里，刷新/切会话后除「还在等确认」外全丢
            planCard: (m.plan && typeof m.plan === 'object' && m.plan.plan) ? planCardFromRecord(m.plan) : null,
            // 过程独白全文（随消息落库）：历史回显时间线 process 段的区间数据源（旧消息无此字段则为空）
            processText: typeof m.processText === 'string' ? m.processText : '',
            // Token 用量（随消息落库）：历史会话的「本次用量/会话累计」回看数据源（旧消息无此字段则为 null）
            tokens: (m.tokens && typeof m.tokens === 'object') ? m.tokens : null,
            // 回答归属（随消息落库的当轮智能体快照）：历史回显「这条是谁答的」，旧消息无此字段则为空
            agentId: typeof m.agentId === 'string' ? m.agentId : '',
            agentName: typeof m.agentName === 'string' ? m.agentName : '',
            // 本轮生效模型引用（随助手消息落库）：「模型已切换」分隔记录的比对数据源，旧消息无此字段则为空
            model: typeof m.model === 'string' ? m.model : '',
            // 分支版本（编辑重发/重新生成的持久化多版本）：‹ n/N › 切换器的数据源，无版本的消息为 null
            variantCount: m.variantCount || null,
            variantIndex: m.variantIndex || null,
            retrieved: (() => { try { return m.retrieved ? JSON.parse(m.retrieved) : null } catch (e) { return null } })(),
            // 编排视图：历史消息的检索状态行含 branches（随 retrieved 持久化），恢复时一并回显编排面板
            subagents: (() => {
              try {
                const r = m.retrieved ? JSON.parse(m.retrieved) : null
                return (r && Array.isArray(r.branches)) ? r.branches : []
              } catch (e) { return [] }
            })(),
            // 按需委派路由结果（同样随 retrieved 持久化）
            subagentRoute: (() => {
              try {
                const r = m.retrieved ? JSON.parse(m.retrieved) : null
                return (r && r.route) ? r.route : null
              } catch (e) { return null }
            })(),
            // 回答时间线（随消息落库）：重建后刷新页仍是「正文—工具—产物」交错的过程视图
            timeline: []
          }
          if (Array.isArray(m.timeline)) msg.timeline = restoreTimeline(msg, m.timeline)
          // 存量消息没落思考分段（改动前只存一整块全文）：补一段在头部，别让它从界面上消失
          ensureThinkSegment(msg)
          return msg
        })
      // 该会话正在流式回答：把 live 消息接回视图尾部。流式中的这轮前后端不落库助手消息，
      // getHistory 里没有这条；用户消息在轮开始时已即时落库，顺序正好衔接。
      // （已被中断的轮次后端会把半程回答按截断态兜底落库——那类轮不再有 live 消息，走上面列表正常回显）
      const st = chatStreams.get(sid)
      if (st && st.msg.loading) list.push(st.msg)
      messages.value = list
      // 产物换发新鲜签名（fire-and-forget）：历史接口给的 URL 签好就不变，1 小时后过期即 401。
      // 进会话/切会话都换一次，避免"打开页面久了产物点不动"
      refreshArtifactSigns()
      // 断线重连接流：这个会话若还有一轮在跑（刷新前自己发的、或另一台设备上发的），把它接回来，
      // 用户看到的是「过程继续长出来」而不是等落库。必须先探再恢复卡片——待作答的提问卡要挂在
      // 这颗接回来的气泡上，否则同一轮会出现两颗进行中气泡
      if (!st) await attachRunningTurn(sid)
      const live = chatStreams.get(sid)
      // 提问卡恢复（fire-and-forget）：等待作答期间断线不再中止本轮，所以刷新/换设备后进来仍能把那张卡答完
      if (!live || !live.msg.ask) hydratePendingAsk(sid)
      // 计划批准卡恢复（同上）：计划等待期间断线不中止本轮，刷新/换设备后仍能批准（回答转后台生成落库）
      if (!live || !live.msg.planCard) hydratePendingPlans(sid)
      // 工具审批卡恢复：接流形态下这是第四张卡——本轮豁免宽限一直等人批准，卡片看不见就成了「莫名不动」
      if (live && !live.msg.approval) hydratePendingApprovals(sid)
      // 先按新列表重算尾随留白、等它落屏再贴底：落点是本轮问题置顶
      //（列表短于一屏时留白为 0，落点即内容底）
      hooks.scrollForce?.()
    } else if (currentSessionId.value === sid) {
      messages.value = []
    }
  } catch (e) {
    if (currentSessionId.value === sid) messages.value = []
    // 非本人/已删除的会话（403/404）：静默清掉地址栏 sid，回退到自己的最近会话或新建
    // （autoPick 与首屏落地同一逻辑）；先刷新列表，避免用陈旧列表再选中已失效会话
    if (!sessionRecovering && (e?.status === 403 || e?.status === 404)
        && String(route.query.sid || '') === String(sid)) {
      sessionRecovering = true
      currentSessionId.value = null
      router.replace({ path: hooks.chatPath }).catch(() => {})
      try {
        await loadSessions()
        await autoPick()
      } catch (err) {
        /* 回退失败则停在空白会话，不再打扰 */
      } finally {
        sessionRecovering = false
      }
    }
  } finally {
    // 本次在途加载结束：清掉登记，让下一次 switchSession 能正常重新拉历史
    historyLoads.delete(sid)
  }
  })()
  historyLoads.set(sid, task)
  return task
}

const creatingSession = ref(false)
/**
 * 新建会话。presetAgentId：由「切换智能体」触发时带上目标智能体，落为新会话的待选值
 * （新会话尚未绑定，首问发出时才由后端锁定）。
 */
const createNewSession = async presetAgentId => {
  if (creatingSession.value) return
  // 空会话复用排除正在流式的会话：列表里的 messageCount 是快照（首条消息 done 后才刷新），
  // 流式中的会话可能仍记 0——命中它会把当前视图清空而不是开新会话
  const emptySid = sessionStore.list.find(s => (s.messageCount ?? 0) === 0
    && !chatStreams.has(s.id))?.id
  if (emptySid) {
    if (presetAgentId !== undefined) {
      agentMap.value = { ...agentMap.value, [emptySid]: presetAgentId || '' }
    }
    if (currentSessionId.value !== emptySid) await switchSession(emptySid)
    else messages.value = []
    router.replace({ path: hooks.chatPath, query: { sid: emptySid } })
    hooks.focusInput?.()
    return
  }
  creatingSession.value = true
  try {
    const r = await newSession()
    if (r.success && r.data?.sessionId) {
      const sid = r.data.sessionId
      currentSessionId.value = sid
      if (presetAgentId !== undefined) {
        agentMap.value = { ...agentMap.value, [sid]: presetAgentId || '' }
      }
      messages.value = []
      router.replace({ path: hooks.chatPath, query: { sid: r.data.sessionId } })
      await loadSessions()
      hooks.focusInput?.()
    }
  } catch (e) {
    message.error('创建会话失败: ' + (e.message || '未知错误'))
  } finally {
    creatingSession.value = false
  }
}

// 路由 query.sid 驱动：只处理"切到某个会话"；sid 清空（新建/删除当前会话）由 tick 信号接管，
// 避免两条链路同时触发 autoPick / createNew 的竞态
watch(() => route.query.sid, sid => {
  if (route.path !== hooks.chatPath || !sid) return
  if (sid !== currentSessionId.value) switchSession(sid)
})
// 侧边栏「新建对话」信号（消费后回写 seen，跨页积累的 tick 只消费一次）
// 注意不依赖 route.query 状态：tick 触发时路由 push 可能尚未完成，条件里查 sid 会偶发落空
// （不再被"正在回答"拦截：当前会话的流转入后台继续跑）
watch(() => sessionStore.newChatTick, async tick => {
  sessionStore.newChatSeen = tick
  if (route.path === hooks.chatPath) await createNewSession()
})
// 当前会话被删除 → 自动落到最近会话或新建
watch(() => sessionStore.autoPickTick, async () => {
  if (route.path === hooks.chatPath) await autoPick()
})
const autoPick = async () => {
  const first = sessionStore.list.find(s => (s.messageCount ?? 0) > 0)
  if (first) await switchSession(first.id)
  else await createNewSession()
}

const handleDeleteSession = async sid => {
  try {
    await deleteSessionApi(sid)
    message.success('会话已删除')
    if (sid === currentSessionId.value) {
      const remaining = sessionStore.list.filter(s => s.id !== sid && (s.messageCount ?? 0) > 0)
      if (remaining.length > 0) await switchSession(remaining[0].id)
      else { messages.value = []; currentSessionId.value = null; router.replace(hooks.chatPath) }
    }
    await loadSessions()
  } catch (e) { message.error(e.message || '删除失败') }
}
// ==================== 发送与流式回答（SSE，事件处理与旧版口径一致） ====================
// async：生成中按发送走插话分支，要等入队结果回来才知道该不该清输入框
const send = async () => {
  const q = text.value.trim()
  const imgs = pendingImages.value.map(p => p.dataUrl)
  // 附件只带 fileId（内容已先上传落盘）；未传完的不带走，并明确拦下这次发送——
  // 默默发出去会让用户以为附件生效了，实际模型根本没看到这份材料
  if (hasUploadingFile()) {
    message.warning('附件还在上传中，请稍候再发送')
    return
  }
  const atts = pendingFiles.value
    .filter(f => f.fileId && !f.error)
    .map(f => ({ name: f.name, mime: f.mime, fileId: f.fileId }))
  const attsMeta = pendingFiles.value
    .filter(f => f.fileId && !f.error)
    .map(f => ({ name: f.name, mime: f.mime, size: f.size, paste: !!f.paste, text: f.text }))
  const skills = [...pickedSkills.value]
  // @ 引用（本轮显式指定的知识库/文档）：与问题一起提交，服务端按可见性校验后收窄检索范围/强制前置
  const mentions = pendingMentions.value.map(m => ({ type: m.type, id: m.id, name: m.name, kbId: m.kbId || '' }))
  // # 历史引用（本轮显式指定的会话历史问答）：只带 messageId，内容由服务端按会话归属查库回填
  const historyRefs = pendingHistoryRefs.value.map(h => ({ messageId: h.messageId }))
  if (!q && !imgs.length && !atts.length) return
  // 生成中再发一句 = 排进插话队列（输入框上方那排 chip），**不发请求**。
  // 主输入框不再因跑着而禁用：想改方向不该被迫先停止本轮。载荷与正常提问完全同权
  // （图片/附件/技能/@/#/模型/思考/计划模式）。发给模型要等用户在 chip 上点「立即发送」。
  if (loading.value) {
    if (!stageSteer({ text: q, images: imgs, atts, attsMeta, skills, mentions, historyRefs,
                      deepThink: deepThinkOn.value, planMode: planModeOn.value })) return
    text.value = ''
    hooks.closePanels?.()
    pendingImages.value = []
    pendingFiles.value = []
    pickedSkills.value = []
    pendingMentions.value = []
    pendingHistoryRefs.value = []
    return
  }
  // 无任何可用模型（会话/智能体/个人默认均未配置）时引导配置，不打无谓请求
  if (!effectiveModel.value) {
    message.warning('未指定模型：请在右上角选择模型，或在个人设置/智能体中配置默认模型')
    return
  }
  text.value = ''
  hooks.closePanels?.()   // 正文清空后触发字符已失效，显式收起（不依赖 input 回调的副作用）
  // 压缩结果条随下一轮退场：它记录的是「刚才压了什么」，继续对话后仍摆在消息流最下方，
  // 会被读成「本轮压缩了 N 轮」（与气泡上的自动压缩提示条混淆）
  compactNotice.value = null
  pendingImages.value = []
  pendingFiles.value = []
  pickedSkills.value = []
  pendingMentions.value = []
  pendingHistoryRefs.value = []
  const deep = deepThinkOn.value
  const planMode = planModeOn.value
  // attachData 留在内存消息上：重新生成/自动重试时可原样重发（历史回放无数据，行为与图片 data: 口径一致）
  const userMsg = reactive({ role: 'user', content: q, images: imgs, attachments: attsMeta, attachData: atts,
                        skills, mentions, historyRefs, deepThink: deep, planMode, time: Date.now(), messageId: null })
  messages.value.push(userMsg)
  // userMsg 传入流式：done 回填本轮用户消息的落库 ID（userMessageId），编辑重发/分支切换从此可用
  streamAnswer(q, imgs, null, messages.value.length === 1, 1, deep, atts, skills, mentions, null, historyRefs, '', userMsg, planMode)
}
/** 工具执行审批：批准/拒绝当前气泡挂起的工具请求；后端以错误结果回给模型继续回答 */
async function resolveApproval (m, approved) {
  // 与 ignoreAsk 同理：等待响应期间本轮的审批终态事件可能已把 m.approval 清空，卡片引用先落本地
  const a = m && m.approval
  if (!a || a.busy) return
  a.busy = true
  try {
    const r = await approveToolCall(a.id, approved)
    if (r && r.success === false) {
      message.warning(r.msg || '审批提交失败')
      a.busy = false
      return
    }
    m.approval = null
    hooks.scrollSoft?.()
  } catch (e) {
    message.error(e.message || '审批提交失败')
    if (m.approval) m.approval.busy = false
  }
}
/**
 * 计划模式（人在回路）：裁决当前气泡挂起的执行计划——批准 / 取消本轮。
 *  action='approve' 批准执行（后端仍兼容带改稿批准，前端不再提供该入口）；
 *  action='cancel' 取消本轮，卡片定格「未批准」。
 *  对计划不满意走 {@link continuePlanConversation}（带着意见继续对话，本版作废）。
 *  恢复态卡（restored）：本设备没有这一轮的流，批准在后台进行，靠轮询取回结果。 */
async function submitPlanApproval (m, action) {
  const c = m && m.planCard
  if (!c || c.busy || c.status !== 'pending') return
  const approved = action === 'approve'
  c.busy = true
  try {
    const r = await resolvePlanApproval(c.id, approved)
    if (r && r.success === false) {
      message.warning(r.msg || '提交失败')
      c.busy = false
      // 恢复态的卡：本轮已无人接收（超时/重启），留着只会让人反复提交，撤下并重载会话
      if (c.restored) { m.planCard = null; m.stage = ''; stopRestoredPoll(); switchSession(currentSessionId.value) }
      return
    }
    if (approved) {
      c.status = 'approved'
      if (c.restored) {
        m.planCard = null
        m.stage = '回答生成中，稍后自动刷新…'
        pollRestoredAnswer(currentSessionId.value, m)
        return
      }
    } else {
      c.status = 'rejected'
      if (c.restored) { m.planCard = null; m.stage = ''; stopRestoredPoll(); switchSession(currentSessionId.value) }
    }
    hooks.scrollSoft?.()
  } catch (e) {
    message.error(e.message || '提交失败')
    if (m.planCard) m.planCard.busy = false
  }
}

/** 旧轮收尾等待：后端先落「取代」收尾消息、前端再把意见作为新消息发出（落库顺序不乱）。
 *  恢复态卡没有本轮的流可等，只给后端落库留一小段时间 */
const planRoundSettled = m => new Promise(resolve => {
  if (!m || m.restored) { setTimeout(resolve, 800); return }
  if (!m.loading) { resolve(); return }
  const t0 = Date.now()
  const tick = () => { if (!m.loading || Date.now() - t0 > 5000) resolve(); else setTimeout(tick, 150) }
  tick()
})

/**
 * 计划「继续对话」（计划模式）：用户对当前计划不满意，把输入框里的意见作为一条正式用户消息发出——
 * 本版计划先按「已被取代」作废（supersede），随后这条消息在**同一会话里继续对话**：完整新一轮
 * （重新检索），模型带着上一版计划按意见产出修改后的计划卡。可反复，直到点「确认」才执行。
 */
async function continuePlanConversation (m, text) {
  const c = m && m.planCard
  if (!c || c.busy || c.status !== 'pending') return
  const q = String(text || '').trim()
  if (!q) { message.warning('先写下要改什么，再点「继续对话」'); return }
  c.busy = true
  try {
    const r = await supersedePlanApproval(c.id)
    if (r && r.success === false) { message.warning(r.msg || '提交失败'); c.busy = false; return }
  } catch (e) {
    message.error(e.message || '提交失败')
    c.busy = false
    return
  }
  c.editText = ''
  await planRoundSettled(m)
  // 旧轮已收尾（或恢复态给了落库时间）：把意见作为用户消息发出，继续同一会话的对话
  const userMsg = reactive({ role: 'user', content: q, images: [], attachments: [], attachData: [],
                            skills: [], mentions: [], historyRefs: [], deepThink: deepThinkOn.value,
                            planMode: true, time: Date.now(), messageId: null })
  messages.value.push(userMsg)
  // 强制计划模式：本轮的目的就是按意见重做计划（后端据此再走计划闸门）
  streamAnswer(q, [], null, messages.value.length === 1, 1, deepThinkOn.value, [], [], [], null, [], '', userMsg, true)
}
/** 智能体提问（一卡多问）：在某题上点选一个选项——只记录选择，不提交（提交由 askSubmitAll 一次性批量完成） */
function pickAskOption (m, page, oi) {
  const a = m && m.ask
  if (!a || a.busy || a.answered) return
  if (!a.questions[page] || oi == null || oi < 0 || oi >= a.questions[page].options.length) return
  a.sels[page] = oi
  a.customs[page] = ''
}
/** 智能体提问（一卡多问）：确认某题自定义答案（回车/失焦）——有文本即标记该题已答（sels=选项数） */
function commitAskCustom (m, page) {
  const a = m && m.ask
  if (!a || a.busy || a.answered) return
  const len = (a.questions[page] && a.questions[page].options ? a.questions[page].options.length : 0)
  const t = (a.customs[page] || '').trim()
  if (t) a.sels[page] = len
  else if (a.sels[page] === len) a.sels[page] = null
}
/**
 * 造一张提问卡（SSE 实时下发与刷新/换设备后重建共用这份形状）。
 * @param remainingMs 倒计时剩余毫秒；不传则按 j.timeoutMs 从此刻起算（实时路径没有时差）
 * j.restored=true 表示这张卡是从服务端待答记录重建的：本设备没有这一轮的流式通道，答完后回答在
 * 后台生成，要靠 pollRestoredAnswer 轮询历史取回。
 */
function buildAskCard (j, remainingMs) {
  let questions
  if (Array.isArray(j.questions) && j.questions.length) {
    questions = j.questions.map(x => ({
      topic: x.topic || '',
      question: x.question || '',
      options: Array.isArray(x.options) ? x.options : []
    }))
  } else if (j.question) {
    // 兼容旧式单问题（等价于一题一卡）
    questions = [{ topic: j.topic || '', question: j.question, options: j.options || [] }]
  } else {
    questions = []
  }
  const n = questions.length
  const ms = Number(remainingMs != null ? remainingMs : j.timeoutMs) || 0
  return {
    id: j.askId,
    questions,
    timeoutMs: Number(j.timeoutMs) || 0,
    deadline: ms > 0 ? Date.now() + ms : 0,
    busy: false,
    answered: false,
    restored: !!j.restored,
    sels: new Array(n).fill(null),   // 每题已选选项下标（null=未答）
    customs: new Array(n).fill('')   // 每题自定义输入
  }
}

/** 把一张提问卡挂到气泡上（同一轮一张的位置；多张请走 attachAskCard 排队） */
function mountAskCard (m, j, remainingMs) {
  m.ask = buildAskCard(j, remainingMs)
  return m.ask
}

/**
 * 挂一张待答卡，位置被占就排进队列。
 * <p>为什么要队列：并行工具调用能在**同一轮**挂出两张提问卡（服务端按 askId 各自阻塞、各自可答），
 * 而实时与恢复都只认「这一轮那一张」——第二张要么盖掉第一张，要么干脆不重建，
 * 于是有一道题在屏幕上根本不存在，只能等它自己超时。排队 + 撤卡时顶上，两张都轮得到答。
 * <p>去重按 askId：恢复会被反复触发（切会话、点开通知、答完一张再同步），同 id 不挂第二遍。
 */
function attachAskCard (m, j, remainingMs) {
  if (!m || !j) return false
  const id = j.askId
  if (m.ask && m.ask.id === id) return false
  const q = Array.isArray(m.askQueue) ? m.askQueue : []
  if (id && q.some(x => x && x.id === id)) return false
  const card = buildAskCard(j, remainingMs)
  if (!m.ask) { m.ask = card } else { q.push(card); m.askQueue = q }
  return true
}

/** 撤下当前卡，让排队的下一张顶上（面板仍是一张一答）；没有排队就什么也不留 */
function retireAskCard (m) {
  if (!m) return
  m.ask = null
  const q = Array.isArray(m.askQueue) ? m.askQueue : null
  if (q && q.length) m.ask = q.shift()
}

/**
 * 恢复出来的卡片挂到哪条气泡——按记录带的 turnId 认（一问一答共用一个 id，与 c_ai_message 同源）。
 * 此前一律挂「这台设备手上的那一轮」，于是更早一轮的待决卡会挂到最新的气泡上：
 * 用户答的是上一轮的题，屏幕上指的却是这一轮，裁决送得回去但看着是错的。
 *   - 这台设备有在跑的流，且它要么没有轮身份（本轮助手消息还没落库，实时气泡拿不到 turnId），
 *     要么正是这一轮 → 挂它；轮身份未知时不另造气泡，宁可照旧挂；
 *   - 明确不是这一轮 → 去找那一轮已落库的气泡；找不到返回 null，调用方补占位泡并盖上 turnId，
 *     下次恢复仍回到同一颗（不叠第二颗）。
 */
const msgByTurn = turnId => {
  if (!turnId) return null
  const list = messages.value || []
  for (let i = list.length - 1; i >= 0; i--) {
    const m = list[i]
    if (m && (m.role === 'ai' || m.role === 'assistant') && m.turnId === turnId) return m
  }
  return null
}
const cardTargetFor = (st, turnId) => {
  if (st && st.msg && (!turnId || !st.msg.turnId || st.msg.turnId === turnId)) return st.msg
  return msgByTurn(turnId)
}

/**
 * 会话加载/切换后恢复待答提问卡：提问已落库、等待期间断线不中止本轮，所以刷新页面或换设备后
 * 那根工具线程还阻塞着，答案照样送得回去。只重建「未过期且唤醒句柄还在」的最新一张——
 * 同一轮连问几卡时，早先那张已随工具终态落成气泡里的问答记录。
 */
const hydratePendingAsk = async sid => {
  if (!sid) return
  const st = chatStreams.get(sid)
  let items
  try {
    const r = await listPendingAsks(sid)
    items = (r && r.data && Array.isArray(r.data.items)) ? r.data.items : []
  } catch (e) {
    // 静默失败会被读成「我那条没生效」：恢复入口拉不动要出声（不打断正常问答，只提示）
    if (currentSessionId.value === sid) message.warning('待答提问恢复失败：' + (e?.message || '网络异常'))
    return
  }
  if (currentSessionId.value !== sid) return   // 快速切会话：晚到响应不覆盖当前视图
  const alive = items.filter(x => x && !x.expired && x.live)
  const dead = items.filter(x => x && !x.expired && !x.live)
  if (!alive.length) {
    // 卡片还在但唤醒句柄已不在（进程重启过）：这一轮没人接，说清楚而不是留个点了没反应的输入框
    if (dead.length) message.warning('这一轮的提问已失效（服务已重启），回答没有继续生成')
    return
  }
  let mounted = 0
  // 每一张还挂着的卡都要建得出来：并行工具调用能在同一轮挂出两三项，只建末条的话
  // 其余那几题在屏幕上不存在，答不了就只能等它自己超时
  for (const item of alive) {
    // 挂回它自己那一轮：本轮气泡已经在界面上就挂它，更早的一轮就去找那一轮的气泡
    const target = cardTargetFor(st, item.turnId)
    if (target) {
      if (attachAskCard(target, { ...item, restored: true }, item.remainingMs)) mounted++
      continue
    }
    // 这一轮的助手消息要到回答完成才落库，此刻视图里没有可挂的气泡：补一个「等你作答」的占位泡，
    // 答题面板照旧挂底部；答完轮询到新回答后整个列表按历史重载，占位泡随之消失
    const anchor = (item.turnId && msgByTurn(item.turnId)) || null
    if (anchor) {
      if (attachAskCard(anchor, { ...item, restored: true }, item.remainingMs)) mounted++
      continue
    }
    const bubble = reactive({ role: 'ai', content: '', images: [], sources: [], related: [],
      degradations: [], warnMsg: '', loading: true, retrying: false, thinking: '',
      thinkLoading: false, stage: '等你作答，回答才不会跑偏', time: Date.now(), artifacts: [],
      toolCalls: [], subagents: [], plan: null, timeline: [], errorCard: null, model: '', delegated: null,
      turnId: item.turnId || '' })
    attachAskCard(bubble, { ...item, restored: true }, item.remainingMs)
    messages.value = [...messages.value, bubble]
    mounted++
  }
  if (mounted) hooks.scrollForce?.()
}

/** 恢复态轮询句柄（同一时刻只留一份：切会话/答完即停） */
let restoredPoll = null
const stopRestoredPoll = () => { if (restoredPoll) { clearInterval(restoredPoll); restoredPoll = null } }

/**
 * 计划正文归一化成**字符串**：卡片主体最终交给 renderMd 渲染，喂进去非字符串就是屏幕上那个
 * `[object Object]`（真机踩过：框架 plan_exit 的入参只有一句 summary，后端把整个入参对象当 plan 下发）。
 * 这里做最后一道闸——对象按常见字段名（content/plan/text/summary）取值，都取不到才退成可读的 JSON，
 * 绝不把对象直接透传给渲染层。
 */
const planText = v => {
  if (v == null) return ''
  if (typeof v === 'string') return v
  if (Array.isArray(v)) return v.map(planText).filter(Boolean).join('\n')
  if (typeof v === 'object') {
    for (const k of ['content', 'plan', 'text', 'summary']) {
      const t = planText(v[k])
      if (t) return t
    }
    try { return JSON.stringify(v, null, 2) } catch (e) { return '' }
  }
  return String(v)
}

/**
 * 落库计划快照 → 气泡计划卡字段（历史回显与待批准卡重建共用）：
 * plan=末版正文（卡片主体），versions=已产各版（不含末版，供「修改记录」逐版回看），feedback=产出末版的用户意见。
 * 老消息无 versions 字段 → 退化为单版（与改动前一致）。
 */
const planCardFromRecord = (rec, statusOverride) => {
  const all = (rec && Array.isArray(rec.versions) ? rec.versions : [])
    .filter(v => v && planText(v.plan))
  const st = rec && (rec.status === 'rejected' || rec.status === 'superseded') ? rec.status : 'approved'
  const card = {
    plan: '', status: statusOverride || st,
    busy: false, editText: '', versions: [], feedback: '',
    auto: !!(rec && rec.auto)   // 按消息意图自动开启（历史回显照常标「自动开启」）
  }
  if (all.length) {
    const last = all[all.length - 1]
    card.plan = planText(last.plan)
    card.feedback = last.feedback || ''
    card.versions = all.slice(0, -1).map(v => ({ plan: planText(v.plan), feedback: v.feedback || '' }))
  } else if (rec) {
    card.plan = planText(rec.plan)
  }
  return card
}

/**
 * 会话加载/切换后恢复待批准计划卡（计划模式，与提问卡恢复同一套语义）：计划已落库、等待批准期间
 * 断线不中止本轮，刷新/换设备后那根流水线线程还阻塞着，批准照样送得回去，回答转后台生成落库。
 * 只重建「未过期且唤醒句柄还在」的最新一张。
 */
const hydratePendingPlans = async sid => {
  if (!sid) return
  const st = chatStreams.get(sid)
  if (st && st.msg && st.msg.planCard) return   // 实时卡还挂在这一轮上，不重复挂
  let items
  try {
    const r = await listPendingPlans(sid)
    items = (r && r.data && Array.isArray(r.data.items)) ? r.data.items : []
  } catch (e) { return }   // 恢复拉取失败不提示：错过仍有超时兜底，不该打断正常浏览
  if (currentSessionId.value !== sid) return   // 快速切会话：晚到响应不覆盖当前视图
  const alive = items.filter(x => x && !x.expired && x.live)
  const dead = items.filter(x => x && !x.expired && !x.live)
  if (!alive.length) {
    if (dead.length) message.warning('这一轮的执行计划已失效（服务已重启），回答没有继续生成')
    return
  }
  const item = alive[alive.length - 1]
  const card = {
    ...planCardFromRecord(item, 'pending'),
    id: item.planApprovalId,
    restored: true,
    timeoutMs: Number(item.timeoutMs) || 0,
    deadline: item.remainingMs > 0 ? Date.now() + item.remainingMs : 0
  }
  // 挂回它自己那一轮（与提问卡同一判据）
  const target = cardTargetFor(st, item.turnId)
  if (target && target.planCard) return   // 这张卡已经挂着了
  if (target) {
    target.planCard = card
    hooks.scrollForce?.()
    return
  }
  const list = messages.value
  // 这一轮的助手消息要到回答完成才落库，此刻视图里没有可挂的气泡：补一个「等你批准」的占位泡，
  // 批准卡挂上；批准后轮询到新回答，整个列表按历史重载，占位泡随之消失
  const anchor = reactive({ role: 'ai', content: '', images: [], sources: [], related: [],
    degradations: [], warnMsg: '', loading: true, retrying: false, thinking: '',
    thinkLoading: false, stage: '等你批准执行计划，批准后回答才开始', time: Date.now(), artifacts: [],
    toolCalls: [], subagents: [], plan: null, planCard: null, timeline: [], errorCard: null, model: '',
    delegated: null, turnId: item.turnId || '' })
  anchor.planCard = card
  messages.value = [...list, anchor]
  hooks.scrollForce?.()
}

/**
 * 会话加载/接流后恢复「等你批准执行」的审批卡（与提问卡、计划卡同一套人在回路恢复口径）。
 * 审批只有 120 秒时限、此前只在实时流里下发过一次：刷新或换设备接回这一轮时看不见那张卡，
 * 而本轮又豁免断线宽限、会一直等——用户读到的就是「智能体莫名其妙不动了」。
 * 只重建**未过期且唤醒句柄还在**（live）的最新一张，并按记录带的 turnId 挂回它自己那一轮的气泡：
 * 那一轮在这台设备的视图里还没有气泡时补占位泡（live 就代表它真的卡在审批上，不挂等于看不见）。
 */
const hydratePendingApprovals = async sid => {
  if (!sid) return
  const st = chatStreams.get(sid)
  if (st && st.msg && st.msg.approval) return   // 实时卡还挂在这一轮上，不重复挂
  let items
  try {
    const r = await listPendingApprovals(sid)
    items = (r && r.data && Array.isArray(r.data.items)) ? r.data.items : []
  } catch (e) { return }   // 拉不动不提示：审批自己有超时兜底，不该打断正常浏览
  if (currentSessionId.value !== sid) return   // 快速切会话：晚到响应不覆盖当前视图
  const alive = items.filter(x => x && x.live && x.approvalId)
  if (!alive.length) return
  const item = alive[alive.length - 1]
  // timeoutMs 传「还剩多少」而不是整窗：卡片那行「未处理将在 N 秒后按拒绝处理」直接读它，
  // 恢复出来的卡若给整窗，用户会以为还有两分钟，实际下一秒就超时按拒绝了
  const card = {
    id: item.approvalId, tool: item.tool, label: item.label, hint: item.hint, args: item.args,
    timeoutMs: Number(item.remainingMs) || 0, busy: false, restored: true
  }
  const target = cardTargetFor(st, item.turnId)
  if (target && target.approval) return   // 这张卡已经挂着了
  if (target) {
    target.approval = card
    hooks.scrollForce?.()
    return
  }
  // 这一轮在这台设备的视图里没有气泡（在别的设备上跑、或本轮的助手消息还没落库）：
  // 补一个「等你批准执行」的占位泡。那一轮是真的卡在审批上（live=唤醒句柄还在），
  // 什么都不挂就是用户读到的「智能体莫名其妙不动了」
  const anchor = reactive({ role: 'ai', content: '', images: [], sources: [], related: [],
    degradations: [], warnMsg: '', loading: true, retrying: false, thinking: '',
    thinkLoading: false, stage: '等你批准执行，批准后这一轮才继续', time: Date.now(), artifacts: [],
    toolCalls: [], subagents: [], plan: null, planCard: null, timeline: [], errorCard: null,
    model: '', delegated: null, approval: null, turnId: item.turnId || '' })
  anchor.approval = card
  messages.value = [...messages.value, anchor]
  hooks.scrollForce?.()
}

/**
 * 恢复态卡片答完后的答案取回：本设备没有这一轮的流式通道，回答在后台生成。
 * 先记下当前历史条数，每 4s 比一次——多出那条（助手回答落库）即完成，重载会话视图。
 */
const pollRestoredAnswer = async (sid, msg) => {
  stopRestoredPoll()
  let baseline = -1
  try {
    const r = await getHistory(sid, { silentForbidden: true })
    baseline = Array.isArray(r?.data) ? r.data.length : -1
  } catch (e) { /* 拿不到基线就不轮询，用户自己回到会话仍能看到答案 */ }
  if (baseline < 0) return
  let ticks = 0
  restoredPoll = setInterval(async () => {
    if (currentSessionId.value !== sid) { stopRestoredPoll(); return }
    if (++ticks > 45) {
      stopRestoredPoll()
      if (msg) msg.stage = ''
      message.warning('这一轮回答还在生成，稍后回到本会话查看')
      return
    }
    try {
      const r = await getHistory(sid, { silentForbidden: true })
      const list = Array.isArray(r?.data) ? r.data : null
      if (!list || list.length <= baseline) return
      stopRestoredPoll()
      await switchSession(sid)
    } catch (e) { /* 单次失败等下一轮 */ }
  }, 4000)
}

/** 智能体提问（一卡多问）：一次性批量提交全部答案。未作答的题留空——后端不会替你选，只把「这一题没答」回给模型 */
async function askSubmitAll (m) {
  const a = m && m.ask
  if (!a || a.busy || a.answered) return
  const answers = a.questions.map((q, i) => {
    const sel = a.sels[i]
    if (sel != null && sel < q.options.length) return q.options[sel]
    const c = (a.customs[i] || '').trim()
    if (c) return c
    return ''   // 未答 → 模型收到「这一题用户没回答」，不替用户选
  })
  a.busy = true
  try {
    const r = await answerAgentAsk(a.id, answers)
    if (r && r.success === false) {
      message.warning(r.msg || '回答提交失败')
      a.busy = false
      // 恢复态的卡：本轮已无人接收（超时/重启），留着只会让人反复提交，撤下并重载会话
      if (a.restored) { retireAskCard(m); m.stage = ''; stopRestoredPoll(); switchSession(currentSessionId.value) }
      return
    }
    a.answered = true
    if (a.restored) {
      // 本设备没有这一轮的流，终态事件不会来撤卡：自己撤下面板、把气泡交给后台生成并轮询取回
      retireAskCard(m)
      m.stage = '回答生成中，稍后自动刷新…'
      pollRestoredAnswer(currentSessionId.value, m)
      return
    }
    hooks.scrollSoft?.()
  } catch (e) {
    message.error(e.message || '回答提交失败')
    if (m.ask) m.ask.busy = false
  }
}
/** 忽略智能体提问：不作答，让模型带着「这一题用户没回答」继续推进（与超时同语义） */
async function ignoreAsk (m) {
  // 卡片引用先落本地：这一调用是阻塞等待，响应回来时本轮的 askUser 终态事件多半已把 m.ask 清空
  //（用户点「忽略」正是让模型立刻继续，终态来得最快），直接读 m.ask 会在 await 之后取到 null
  const a = m && m.ask
  if (!a || a.busy) return
  a.busy = true
  try {
    const r = await ignoreAgentAsk(a.id)
    if (r && r.success === false) {
      message.warning(r.msg || '操作失败')
      a.busy = false
      if (a.restored) { retireAskCard(m); m.stage = ''; stopRestoredPoll(); switchSession(currentSessionId.value) }
      return
    }
    if (a.restored) {
      retireAskCard(m)
      m.stage = '回答生成中，稍后自动刷新…'
      pollRestoredAnswer(currentSessionId.value, m)
      return
    }
    a.answered = '（已忽略）'
    hooks.scrollSoft?.()
  } catch (e) {
    message.error(e.message || '操作失败')
    if (m.ask) m.ask.busy = false
  }
}
/**
 * 接流气泡的收场：这一轮其实已经没了（服务端刚好收尾、服务重启、或通道再也接不上）时用它。
 * 不许留一颗转圈的气泡骗用户「还在跑」——摘掉登记、收掉进行中态，并按历史重显真实结果。
 */
const finishResumedShell = (msg, st, sid, reload = true) => {
  if (st && chatStreams.get(sid) === st) chatStreams.delete(sid)
  if (msg) { msg.loading = false; msg.retrying = false }
  if (reload && currentSessionId.value === sid) switchSession(sid)
}

/** 接流的重连计数（按会话；接上即归零）。用法见 streamAnswer 的 onError 分支 */
const resumeRetryOf = new Map()

/**
 * 接回该会话正在跑的这一轮（P0 断线重连接流的入口）。
 * <p>先探一下（GET /chat/run，服务端按内存里的运行登记回答，很便宜）——确有在跑的轮才开那条接流通道，
 * 免得每次切会话都拖一条「什么都没有」的流。reuseMsg 是通道断了要再接时沿用的那颗气泡
 * （内容由下一帧快照重铺，不靠本地残影）。
 *
 * @returns 是否真的发起了接流
 */
const attachRunningTurn = async (sid, reuseMsg) => {
  if (!sid) return false
  if (chatStreams.has(sid)) return false      // 本设备已有这一轮的流（自己发的，或已经接上的）
  let running = false
  try {
    const r = await getChatRun(sid)
    running = !!(r && r.data && r.data.running)
  } catch (e) {
    return false      // 探测失败不烦用户：这一轮按历史回显，下次进会话仍会再探
  }
  if (!running || currentSessionId.value !== sid) return false
  streamAnswer('', [], reuseMsg || null, false, 0, false, [], [], [], null, [], '', null, false, true)
  return true
}

/**
 * 接流首片的重复判别。服务端给 token/process 这类逐字增量带上 pos=「这条增量应用后的权威字符数」。
 * 快照与增量之间唯一可能的接缝是：正文已经进了服务端缓冲、事件号还没推进的那一条——它既在快照里、
 * 又会被发给刚接上的观众。pos 大于本地长度才该追加，否则整片丢弃（或截到 pos 为止）。
 * 真丢了片（pos 比本地+本片还长）不在这儿补：done 带权威全文与落库时间线，那一刻全部归零。
 */
const dropResumedOverlap = (have, delta, ev) => {
  const pos = ev && typeof ev.pos === 'number' ? ev.pos : -1
  if (pos < 0 || have.length + delta.length <= pos) return delta
  if (have.length >= pos) return ''
  return delta.slice(0, pos - have.length)
}

/**
 * 接流第一帧（snapshot）铺进气泡：把服务端这一轮已经产出的东西一次给全，之后的增量与实时流
 * 走同一套回调——观感与「从头一直看着这一轮」等价，差的只是没看见前面逐字流出来的过程。
 * 只认快照里有的字段（缺=那一段还没跑到），空值不覆盖界面已有内容。
 * 待作答的提问卡/待批准的计划卡不在这里造：它们的时限与答案记录以库为准，由会话加载那两条恢复入口
 * 挂到这颗气泡上（那里才有剩余毫秒数与「唤醒句柄还在不在」）。
 */
const applyTurnSnapshot = (msg, snap, sid) => {
  if (!snap || snap.running !== true) return false
  msg.content = typeof snap.content === 'string' ? snap.content : ''
  if (typeof snap.processText === 'string' && snap.processText) msg.processText = snap.processText
  if (typeof snap.thinking === 'string' && snap.thinking) { msg.thinking = snap.thinking; msg.thinkLoading = false }
  if (Array.isArray(snap.toolCalls)) msg.toolCalls = snap.toolCalls
  if (Array.isArray(snap.artifacts) && snap.artifacts.length) msg.artifacts = snap.artifacts
  if (Array.isArray(snap.images) && snap.images.length) msg.images = snap.images
  if (Array.isArray(snap.sources) && snap.sources.length) msg.sources = snap.sources
  if (Array.isArray(snap.subagentBranches) && snap.subagentBranches.length) msg.subagents = snap.subagentBranches
  if (snap.subagentRoute) msg.subagentRoute = snap.subagentRoute
  if (typeof snap.model === 'string' && snap.model) msg.model = snap.model
  // 本轮身份（快照与落库/done 同源）：接回来的气泡也要有「看这一轮的过程」，不许刷新才有
  if (typeof snap.turnId === 'string' && snap.turnId) msg.turnId = snap.turnId
  if (snap.agentName) msg.agentName = snap.agentName
  if (snap.agentId) msg.agentId = snap.agentId
  if (snap.planRecord) msg.planCard = planCardFromRecord(snap.planRecord)
  // 时间线必须在 toolCalls/artifacts 之后重建：段里的下标指向这两份清单，清单没铺好就先建会指空
  if (Array.isArray(snap.timeline) && snap.timeline.length) msg.timeline = restoreTimeline(msg, snap.timeline)
  // 快照只给思考全文、给不了分段（那一轮跑到一半接上来的）：头部补一段，
  // 之后实时流出的每一轮各起一段，接流方看到的与从头看着的一样
  ensureThinkSegment(msg)
  // 状态型事件的最后一份（本轮步骤/检索状态/用量预估/任务清单/阶段文案）按普通事件同一口径解析
  const ev = snap.events || {}
  if (ev.plan) {
    try { const arr = JSON.parse(ev.plan); if (Array.isArray(arr) && arr.length) msg.plan = arr } catch (e) { /* 载荷异常：保留已有 */ }
  }
  if (ev.retrieved) {
    try {
      const j = JSON.parse(ev.retrieved)
      msg.retrieved = { keywords: j.keywords || 0, refs: j.refs || 0, terms: j.terms || [] }
    } catch (e) { /* 忽略 */ }
  }
  if (ev.usage) {
    try { const j = JSON.parse(ev.usage); if (j && typeof j === 'object') msg.tokensPreview = j } catch (e) { /* 忽略 */ }
  }
  if (ev.todo) {
    try { const j = JSON.parse(ev.todo); if (j && Array.isArray(j.items)) setTodos(sid, j) } catch (e) { /* 忽略 */ }
  }
  // 本轮警示条（沙盒基建异常这类）：实时是后发的盖前发的，镜像留的就是最后那一条，口径一致
  if (typeof ev.warn === 'string' && ev.warn) msg.warnMsg = ev.warn
  // 阶段文案是瞬时的进度提示：接流时若本轮已经有正文、或还有没跑完的工具步，那条「正在…」早已过期，
  // 按实时流的同一口径清掉（工具卡片自己会表达进度）。正在等人工裁决时，后端会在快照之后补发一条
  // 准确的 stage，那条才该显示
  const inFlight = (msg.toolCalls || []).some(x => x.status === 'start')
  if (ev.stage && !msg.content && !inFlight) msg.stage = ev.stage
  return true
}

/**
 * 收一轮问答的流。resume=true 时这一轮不是本地发起的：接的是服务端还在跑的那一轮
 * （见 attachRunningTurn），此时不发 POST /chat，改开 GET /chat/resume 通道，
 * 正文与工具卡片等由第一帧 snapshot 补齐、之后与实时流走同一套回调。
 */
const streamAnswer = (question, imgs, replaceMsg, isFirstMessage, autoRetry = 1, deepThink = false,
                      attachments = [], skills = [], mentions = [], prev = null, historyRefs = [],
                      editMessageId = '', editUserMsg = null, planMode = false, resume = false,
                      fromSteer = false) => {
  // prev = 自动重试上下文 { sid, agentId, model }：沿用原会话与原选择，不读当前 UI 态
  //（重试定时器触发时用户可能已切到别的会话/换了模型）
  const sid = prev ? prev.sid : currentSessionId.value
  const agentId = prev ? prev.agentId : (currentAgentId.value || '')
  // 会话已有 ID 时**不再**随发送带模型：会话里存的那一份是权威（选择器改动即时 PUT 落库，后端也按它作答）。
  // 把页签内存里的那一份发上去，等于用一个端的选择盖掉另一个端刚改过的值——真机 sbx-3：A 端刚切到
  // 可用模型，B 端一句重发又把已停用的写回会话，两边来回盖、每轮都在重建实例。
  // 只有还没有会话 ID 的首问才随载荷带过去（那时 PUT 无处可落，后端补建会话后据此落库）
  const model = prev ? prev.model : (currentSessionId.value ? '' : (currentOverrideModel.value || ''))
  // 重新生成：被替换的旧回答 ID 必须在下面 fresh 覆盖之前捕获（fresh 会把 messageId 置空，
  // 覆盖后再读就永远是空 → 后端不软删旧回答 → 刷新后同一问题出现两条答案）
  const replacedMessageId = replaceMsg && replaceMsg.messageId ? replaceMsg.messageId : ''
  // 流式回调统一改写 msg 对象（而非 messages.value[idx]）：切走会话后 messages 数组已换人，
  // 下标会指错位置；对象引用由 chatStreams 持有，切回来时 switchSession 把它接回视图尾部
  // model 先按前端解析的生效引用预填（覆盖>个人默认，与后端 resolveModel 同序）：「模型已切换」
  // 分隔记录在本轮回答一出现就能比对；done 再用后端权威值校正
  // stage 预置检索档（两档口径见 projections 的阶段文案映射）：与后端首条事件映射结果同词，开场不闪词
  const fresh = { role: 'ai', content: '', images: [], sources: [], related: [], degradations: [], warnMsg: '', loading: true, retrying: false, thinking: '', thinkLoading: false, stage: resume ? '正在接回这一轮…' : '正在检索资料…', time: Date.now(), artifacts: [], toolCalls: [], subagents: [], plan: null, planCard: null, timeline: [], errorCard: null, model: model || effectiveModel.value, delegated: null }
  const msg = replaceMsg ? Object.assign(replaceMsg, fresh, { messageId: null, fb: null }) : reactive(fresh)
  if (!replaceMsg) messages.value.push(msg)
  const viewing = () => currentSessionId.value === sid  // 只有正在看这个会话才滚动/贴底
  // 流式期间：先按新高度重算留白、等它落屏，再贴底——留白让贴底落点停在本轮问题置顶处，
  // 回答长过一屏后留白归零，贴底就自然变成跟着最新内容走（用户上翻仍会解除跟随）
  const liveScroll = () => { if (viewing()) hooks.scrollFollow?.() }
  const abort = new AbortController()
  const st = { msg, abort }
  chatStreams.set(sid, st)
  // 首条消息发出即把会话抬进侧栏列表（列表隐藏空会话，等 onDone 才刷新的话长回答期间不可见）
  if (isFirstMessage) markSessionActive(sid, question)
  // 本轮视角归位：留白补足并落屏后贴底，问题正好落在视口最上（与空会话首问同一落点）
  if (viewing()) hooks.scrollForce?.()
  let full = ''
  let gotToken = false
  // 流式渲染节流：token 只进缓冲 full，每 120ms 批量刷一次 msg.content 与时间线文本区间——
  // 每个 token 都写响应式字段会让整页消息列表重跑渲染管线（renderMd 全量 × 消息数），
  // 长回答越流越卡。done/停止等终态路径 flushNow() 保底：最终态完整、不丢已流出内容。
  let flushTimer = null
  let flushedLen = 0
  // 当前这段思考是否还开着：正文/工具/收尾帧一到就断段，下一片思考另起一段就地长
  let thinkRoundOpen = false
  const flushNow = (keepScroll = true) => {
    if (flushTimer) { clearTimeout(flushTimer); flushTimer = null }
    if (full.length > flushedLen) {
      extendTimelineText(msg, flushedLen, full.length)
      flushedLen = full.length
    }
    if (msg.content !== full) msg.content = full
    // 正文增长必须在这里推进视角：token 不走 liveScroll（只进缓冲、由这里批量落屏），
    // 少这一下长回答就只跟到最后一个 stage 事件，新内容滚出屏幕看不见。
    // keepScroll=false：调用方（过程独白增量）随后自己会补 liveScroll，避免同一次落屏量两遍 DOM
    if (keepScroll) liveScroll()
  }
  const flushSoon = () => {
    if (flushTimer) return
    // 包一层：flushNow 现带 keepScroll 形参，避免把定时器回调的实参当成它
    flushTimer = setTimeout(() => flushNow(), 120)
  }
  const handlers = {
    signal: abort.signal,
    onSnapshot: payload => {
      // 接流的第一帧（只有 resume 那条通道会有这一帧）：本轮已产出的东西一次铺进气泡
      let snap = null
      try { snap = typeof payload === 'string' ? JSON.parse(payload) : payload } catch (e) { snap = null }
      if (!applyTurnSnapshot(msg, snap, sid)) {
        // 这一轮其实已经收尾（或压根不在跑/不是本人的）：这颗空气泡不该留下，按历史重显真实结果
        finishResumedShell(msg, st, sid, false)
        return
      }
      full = msg.content || ''
      flushedLen = full.length
      gotToken = full.length > 0
      resumeRetryOf.delete(sid)   // 接上了：重连计数归零（这一轮的通道之后断了还有三次机会）
      liveScroll()
    },
    deepThink,
    // 计划模式（人在回路）：true=本轮先产出执行计划，经用户批准/编辑后再正式回答
    planMode: planMode || false,
    // 思考强度档位（低/中/高/超高/极致）：空串=不指定，后端回落模型登记的默认档位
    reasoningLevel: reasoningLevelParam.value,
    // 上下文窗口档位（token）：仅模型登记了「最小~最大」区间时下发（null=后端用登记上限/全局默认）
    contextWindow: contextWindowParam.value,
    attachments,
    skills,
    mentions,
    // # 历史引用（[{messageId}]，重新生成时随内存消息原样重发）：服务端按会话归属校验后前置内容
    historyRefs: Array.isArray(historyRefs) && historyRefs.length ? historyRefs : [],
    // 重发标记（重新生成/自动重试走 replaceMsg 路径）：后端跳过用户消息重复落库
    regenerate: replaceMsg != null,
    // 被替换的旧回答消息 ID（仅重新生成时非空：自动重试的那一轮还没落库，messageId 为 null）；
    // 后端据此在落库前软删旧行，历史里只留最新一版
    replaceMessageId: replacedMessageId,
    // 编辑重发：被编辑的用户消息 ID（后端软删其旧分支留档，编辑内容作为新分支重新生成）
    editMessageId,
    // 插话续发：这句话是用户**在上一轮回答跑到一半时补的方向**，不是一次新提问。
    // 后端据此在问题前加一句上下文声明——否则短问答（模型不调工具，插话必然赶不上工具步）
    // 里模型会当成用户又问了一遍，插话最核心的"改了方向立刻生效"在这些场景直接失效
    fromSteer: !!fromSteer,
    agentId,
    // 会话级模型覆盖：仅用户手动切换时传（空=后端按 个人默认>无 兜底解析，全局模型默认已退役）
    model,
    onThinking: t => {
      if (!t) return
      const have = msg.thinking || ''
      if (thinkRoundOpen) extendTimelineThink(msg, have.length, have.length + t.length)
      else pushTimelineThink(msg, have.length, have.length + t.length)
      thinkRoundOpen = true
      msg.thinking = have + t
      msg.thinkLoading = true
      liveScroll()
    },
    onThinkingDone: payload => {
      thinkRoundOpen = false
      msg.thinkLoading = false
      try {
        const j = JSON.parse(payload)
        // 权威全文比本地累积长（接流接缝丢了一片）：多出来那一截归到刚收场的这一段，别让它悬在段外
        if (j.thinking && j.thinking.length > (msg.thinking || '').length) {
          const tl = Array.isArray(msg.timeline) ? msg.timeline : []
          const last = tl[tl.length - 1]
          if (last && last.kind === 'think') last.to = j.thinking.length
          msg.thinking = j.thinking
        }
      } catch (e) { /* 兼容旧 payload */ }
    },
    onToken: (t, ev) => { gotToken = true; full += dropResumedOverlap(full, t, ev); flushSoon(); msg.stage = ''; msg.thinkLoading = false; thinkRoundOpen = false },
    onProcess: (t, ev) => {
      // 过程独白（<process> 标签内，与正文分流）：累积 processText 并推进时间线过程段（灰字弱化渲染）。
      // 后端按 token 增量下发，这里逐条追加即成流式。先 flushNow 落屏节流中的正文：正文 token 走
      // 120ms 节流而过程事件即时到达，不先刷正文，过程段会被记在尚未落屏的正文之前——实时视图里
      // 灰字块跳到正文上方（done 用落库版时间线校正后又会跳回去，一来一回正是「块突然出现又移位」）
      flushNow(false)
      thinkRoundOpen = false
      const have = msg.processText || ''
      const add = dropResumedOverlap(have, t, ev)
      if (!add) return                       // 接流时这一片已被快照覆盖
      msg.processText = have + add
      extendTimelineProcess(msg, have.length, msg.processText.length)
      liveScroll()
    },
    onStage: s => { msg.stage = s; liveScroll() },
    onPlan: p => {
      // 本轮执行计划（后端按配置确定会跑的步骤）：右栏清单逐项点亮的数据源；仅实时，历史轮无此字段
      try {
        const arr = typeof p === 'string' ? JSON.parse(p) : p
        if (Array.isArray(arr) && arr.length) msg.plan = arr
      } catch (e) { /* 忽略 */ }
    },
    onTodo: payload => {
      // 任务清单整表快照（writeTodo 每次传完整清单）：按会话存，切走再回来仍是最后那份
      try {
        const j = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (j && Array.isArray(j.items)) setTodos(sid, j)
      } catch (e) { /* 载荷异常：保留上一份，不清空界面 */ }
    },
    onUsage: payload => {
      // 用量预下发（usage 事件，生成一开始就到）：prompt 侧估算先行点亮容量圆环/明细卡；
      // done 下发的实测 tokens 仍是终值（落库/累计/输出侧都只认它），此处只挂流式中的估算视图
      try {
        const j = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (j && typeof j === 'object') msg.tokensPreview = j
      } catch (e) { /* 忽略 */ }
    },
    onRetrieved: payload => {
      try {
        const j = JSON.parse(payload)
        msg.retrieved = { keywords: j.keywords || 0, refs: j.refs || 0, terms: j.terms || [] }
        msg.stage = ''
        liveScroll()
      } catch (e) { /* 忽略 */ }
    },
    onApprovalRequired: payload => {
      // 工具执行审批（人在回路）：卡片挂到当前 AI 气泡，批准/拒绝后模型继续走
      try {
        const j = typeof payload === 'string' ? JSON.parse(payload) : payload
        msg.approval = { id: j.approvalId, tool: j.tool, label: j.label, hint: j.hint, args: j.args, timeoutMs: j.timeoutMs, busy: false }
        liveScroll()
      } catch (e) { /* 忽略 */ }
    },
    onAskUser: payload => {
      // 智能体提问（人在回路，一卡多问）：提问面板挂到当前 AI 气泡状态上，桌面壳据此把底部输入框整块
      // 替换成提问面板；用户逐题翻页作答、一次性批量提交后模型继续走。
      // 每题 options 第一项是模型给的推荐项（仅提示用）——超时与忽略都不会替你选答案
      try {
        attachAskCard(msg, typeof payload === 'string' ? JSON.parse(payload) : payload)
        liveScroll()
      } catch (e) { /* 忽略 */ }
    },
    onPlanDelta: t => {
      // 计划模式：计划正文流式增量进计划卡（终版以 plan_approval 下发的落库稿为准）
      if (!msg.planCard) msg.planCard = { plan: '', status: 'drafting', busy: false, editText: '', versions: [], feedback: '' }
      const c = msg.planCard
      if (c.status !== 'drafting') return   // 已进入待批准态：迟到的增量丢弃（以权威稿为准）
      c.plan += t
      liveScroll()
    },
    onPlanApproval: payload => {
      // 计划生成完毕：卡片转「待确认」，挂上裁决句柄与倒计时；确认/继续对话/取消见 submitPlanApproval 一族
      try {
        const j = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (!j || !j.planApprovalId) return
        if (!msg.planCard) msg.planCard = { plan: '', status: 'drafting', busy: false, editText: '', versions: [], feedback: '' }
        const c = msg.planCard
        c.id = j.planApprovalId
        // 后端权威稿（已剥标签/护栏截断）覆盖流式累积；过 planText 归一化，
        // 非字符串直接透传会让卡片渲染成 [object Object]
        c.plan = planText(j.plan) || c.plan
        c.timeoutMs = Number(j.timeoutMs) || 0
        c.deadline = c.timeoutMs > 0 ? Date.now() + c.timeoutMs : 0
        c.status = 'pending'
        c.busy = false                   // 解锁裁决控件（防御：极端情况下残留的 busy 不卡死面板）
        c.auto = !!j.auto                // 按消息意图自动开启（卡片标「自动开启」）
        liveScroll()
      } catch (e) { /* 忽略 */ }
    },
    onPlanCancelled: () => {
      // 计划被拒/超时：卡片定格为「未批准」，整轮就此收束（后端不再发回答，done 也无正文）
      if (msg.planCard) { msg.planCard.status = 'rejected'; msg.planCard.busy = false }
      msg.planStopped = true
      liveScroll()
    },
    onPlanSuperseded: () => {
      // 用户对计划提出修改、继续对话：本版定格「已被取代」，整轮就此收束（新一轮将按意见重做计划）
      if (msg.planCard) { msg.planCard.status = 'superseded'; msg.planCard.busy = false }
      msg.planSuperseded = true
      liveScroll()
    },
    onImage: imgs2 => {
      try {
        const parsed = JSON.parse(imgs2)
        msg.images = Array.isArray(parsed) ? parsed : []
      } catch (e) { msg.images = [] }
    },
    onArtifact: payload => {
      try {
        const a = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (!a || !a.url) return
        if (!Array.isArray(msg.artifacts)) msg.artifacts = []
        msg.artifacts.push(a)
        // 产物按生成时刻插入时间线（只记下标），刷新后仍在原位而不是堆到气泡底部
        pushTimelineArtifact(msg, msg.artifacts.length - 1)
        liveScroll()
      } catch (e) { /* 忽略 */ }
    },
    onToolStatus: rec => {
      try {
        const t = typeof rec === 'string' ? JSON.parse(rec) : rec
        if (!t || !t.name) return
        // 工具事件也是「这一段思考想完了」的信号：后端在 tool/requested 前会补收尾帧，
        // 这里再断一次是防漏（漏断会把下一轮思考接在同一行后面，正是用户要摆脱的叠加）
        thinkRoundOpen = false
        if (!Array.isArray(msg.toolCalls)) msg.toolCalls = []
        if (t.status === 'start') {
          // askUser 的「进行中」由提问卡（m.ask）表达，不渲染通用工具卡——否则一问两卡
          if (t.name === 'askUser') return
          // 幂等兜底：同一次调用可能被快照与增量各给一遍（后端按事件序号过滤，这里再守一道）。
          // 重复的 start 会多出一颗转圈卡片，要等 done 才被终态盖掉——刷新那一刻最容易撞见
          if (msg.toolCalls.some(x => x.status === 'start' && x.name === t.name && (x.args || '') === (t.args || ''))) return
          const rec = { ...t, startAt: Date.now() }
          msg.toolCalls.push(rec)
          pushTimelineTool(msg, rec)
          // 进入工具阶段：上一句阶段文案（如「正在检索资料…」）已过期，清掉避免工具跑完又复活。
          // 该阶段由工具卡片自己表达（转圈 + 实时耗时），底部进度行不重复描述
          msg.stage = ''
          ensureTick()
        } else {
          // askUser 已出终态（用户已答，或超时/忽略后按「未作答」收尾）：问答记录卡接管展示，撤掉提问卡
          if (t.name === 'askUser' && msg.ask) retireAskCard(msg)
          if (t.name === 'run_code') msg.runBoard = null
          const list = msg.toolCalls
          const last = [...list].reverse().find(x => x.name === t.name && x.status === 'start')
          if (last) {
            last.status = t.status
            last.elapsedMs = t.elapsedMs || 0
            // 起点以服务端那份为准：跨度是在同一个钟上减的，混进前端收到帧的时刻就会被时钟差与网络排队带歪
            // 等待账同理：漏了它，簇头就会把用户读卡答题那几分钟也算进机器时间
            if (t.startedAtMs) last.startedAtMs = t.startedAtMs
            if (t.waitedMs) last.waitedMs = t.waitedMs
            if (t.args) last.args = t.args
            if (t.result != null) last.result = t.result
            if (t.error) last.error = t.error
            // 咨询的「子对话」只存在于终态那一份（start 那会儿还没有回答）：这里逐字段挑着合并，
            // 漏一行就是实时看不到、刷新才冒出来——两条路必须同构
            if (t.subChat) last.subChat = t.subChat
          } else {
            const rec = { ...t }
            list.push(rec)
            pushTimelineTool(msg, rec)
          }
        }
        liveScroll()
      } catch (e) { /* 忽略 */ }
    },
    onToolOutput: payload => {
      try {
        const o = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (!o || !o.delta) return
        const list = msg.toolCalls
        if (!Array.isArray(list)) return
        const live = [...list].reverse().find(x => x.name === o.name && x.status === 'start')
        if (!live) return
        const MAX = 65536
        let out = (live.output || '') + o.delta
        if (out.length > MAX) { out = out.slice(out.length - MAX); live.outputTruncated = true }
        live.output = out
      } catch (e) { /* 忽略 */ }
    },
    onRunCodeProgress: payload => {
      // 脚本内的并发与额度（P3b 实时面板）：进度帧挂到"正在跑"的那张 run_code 卡上。
      // 与 tool_output 同一条规矩——接流回来的观众手里没有这张卡（start 记录不入快照），
      // 帧就丢弃，跑完后终态卡片自己带完整清单，不凭空造一张卡。
      try {
        const p = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (!p || !p.fanOut) return
        const list = msg.toolCalls
        if (!Array.isArray(list)) return
        const live = [...list].reverse().find(x => x.name === 'run_code' && x.status === 'start')
        if (!live) return
        live.fanOut = p.fanOut
        // 同一份数据在消息上留一份（总览用）：一段脚本跑完由下面的终态清空，
        // 免得过程区一直挂着「脚本运行中」
        msg.runBoard = p.fanOut
      } catch (e) { /* 忽略 */ }
    },
    onSubagentStep: payload => {
      // 全循环分支的实时过程（P3a 刀2）：那一支自己那轮正在跑第几步，此前只有事后那个「跑了 N 步工具」。
      // 分支按序号 i 归位（编排视图的 id 就是它），只增不删；实时态不落库，刷新后仍看步数与回报全文。
      try {
        const s = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (!s || s.i == null) return
        const list = msg.subagents || (msg.subagents = [])
        const b = list.find(x => Number(x.id) === Number(s.i))
        if (!b) return
        let c = s.content
        if (typeof c === 'string') {
          try { c = JSON.parse(c) } catch (e) { c = { text: c } }
        }
        c = c || {}
        const rows = b.live || (b.live = [])
        if (s.type === 'tool_status') {
          if (c.status === 'start') rows.push({ name: c.name || '', label: c.label || '', status: 'start', elapsedMs: 0, out: '' })
          else {
            const live = [...rows].reverse().find(r => r.status === 'start' && r.name === c.name)
            if (live) { live.status = c.status || 'done'; live.elapsedMs = c.elapsedMs || 0 }
            else rows.push({ name: c.name || '', label: c.label || '', status: c.status || 'done', elapsedMs: c.elapsedMs || 0, out: '' })
          }
        } else if (s.type === 'tool_output') {
          const live = [...rows].reverse().find(r => r.status === 'start')
          if (live) live.out = ((live.out || '') + (c.delta || '')).slice(-400)
        } else if (c.text) {
          rows.push({ status: 'stage', stage: String(c.text).slice(0, 60), name: '', elapsedMs: 0 })
        }
        if (rows.length > 30) rows.splice(0, rows.length - 30)
      } catch (e) { /* 忽略 */ }
    },
    onSubagent: payload => {
      try {
        const b = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (!b || b.id == null) return
        const list = msg.subagents || (msg.subagents = [])
        const i = list.findIndex(x => x.id === b.id)
        if (i >= 0) list[i] = { ...list[i], ...b }
        else list.push(b)
        liveScroll()
      } catch (e) { /* 忽略 */ }
    },
    onSubagentRoute: payload => {
      try {
        const r = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (!r) return
        msg.subagentRoute = { candidates: r.candidates || 0, picked: r.picked || 0, names: r.names || [], reasons: r.reasons || {} }
      } catch (e) { /* 忽略 */ }
    },
    // 会话内 @ 智能体（§4）：本轮由用户 @ 提及的智能体作答（会话绑定不变）。用户指令，常显徽标；
    // 归属同步改写（done 的 delegatedAgentName 会再校正一次，两处同值）
    // 模型自主咨询跑通（P5）：常显徽标的数据源之一。@ 强制咨询走另一条事件，
    // 两处由投影视图的合并函数按 id 去重，不在这里判重
    onAgentConsulted: payload => {
      try {
        const r = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (!r || !r.name) return
        msg.consulted = [...(msg.consulted || []), { id: r.id || '', name: r.name }]
        liveScroll()
      } catch (e) { /* 忽略 */ }
    },
    onAgentDelegated: payload => {
      try {
        const r = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (!r || !r.name) return
        msg.delegated = { name: r.name, description: r.description || '' }
        msg.agentName = r.name
        if (r.id) msg.agentId = r.id
        liveScroll()
      } catch (e) { /* 忽略 */ }
    },
    onDone: contentJson => {
      flushNow()   // 终态保底：把缓冲里未刷的正文与时间线刷进响应式（停止生成/最终正文对比都依赖它）
      let sources = [], related = [], messageId = null, degradations = []
      try {
        const p = JSON.parse(contentJson || '{}')
        sources = Array.isArray(p.sources) ? p.sources : []
        related = Array.isArray(p.related) ? p.related : []
        messageId = p.messageId || null
        degradations = Array.isArray(p.degradations) ? p.degradations : []
        // 计划模式被拒/超时：done 载荷带 planCancelled（plan_cancelled 事件已置过，这里是双保险——
        // 正文为空时收束语按「计划未批准」口径而不是「已停止生成」）；被「继续对话」取代同理由
        // planSuperseded 置位，但那轮正文留空（状态只写在计划卡角标上）
        if (p.planCancelled) msg.planStopped = true
        if (p.planSuperseded) msg.planSuperseded = true
        // 插话队列结算：本轮收尾（答完/被停/失败）时结算一次——还没点「立即发送」的在这里
        // 自动作为新一轮发出（本轮已结束，"插进去"没有对象了），纯文字没赶上工具步的由后端回
        // pendingSteers。用户说过的话不许静默丢掉，这是插话机制的底线。
        // 接来的那条流不结算：那些插话是**发起设备**上的人说的，由它自己续发；
        // 在这台只来「看一眼」的设备上自动发出去，等于替别人又问了一遍
        if (!resume) setTimeout(() => flushPendingSteers(Array.isArray(p.pendingSteers) ? p.pendingSteers : []), 0)
        // 编辑重发：done 带回本轮用户消息的落库 ID——回填到本地新用户消息上，
        // 该消息的 ‹ n/N › 分支切换器与「再次编辑」从此可用
        if (editUserMsg && p.userMessageId) editUserMsg.messageId = p.userMessageId
        // 本轮身份：done 带回的 turnId 与刚落库的消息行同源——「过程」入口不该是刷新后才有的东西
        if (p.turnId) msg.turnId = String(p.turnId)
        if (p.tokens && typeof p.tokens === 'object') msg.tokens = p.tokens
        // 本轮作答方归属（P5：不再有会话锁定，done 的 agentId/agentName 就是这一轮实际作答的智能体）
        if (p.agentName) msg.agentName = p.agentName
        // 会话内 @ 智能体（§4）：done 的 agentName 是会话绑定口径，本轮由被 @ 的智能体作答时
        // 气泡归属按委派值覆盖（后端落库快照同为委派智能体，刷新后回显一致）
        if (p.delegatedAgentName) {
          msg.agentName = p.delegatedAgentName
          if (p.delegatedAgentId) msg.agentId = p.delegatedAgentId
        }
        // 生效模型以后端权威解析为准（请求未带覆盖时后端回落个人默认，前端预填值在此校正）
        if (typeof p.model === 'string' && p.model) msg.model = p.model
        if (p.thinking) msg.thinking = p.thinking
        msg.thinkLoading = false
        if (typeof p.finalContent === 'string' && p.finalContent !== '') {
          // 最终正文与流式累积不一致（引用自检重建/related 清理等改写了正文）：文本区间失效，
          // 以 done 下发的落库版时间线为准（区间已按最终正文夹取）；后端没给时间线才回退底部汇总
          if (p.finalContent !== msg.content) msg.content = p.finalContent
        }
        if (Array.isArray(p.finalImages)) msg.images = p.finalImages
        if (Array.isArray(p.artifacts) && p.artifacts.length) msg.artifacts = p.artifacts
        // 过程独白以 done 下发的落库版为准（须先于 timeline 恢复赋值：过程段区间指向它）
        if (typeof p.processText === 'string') msg.processText = p.processText
        // done 工具终态原地合并（不整组替换）：保住 timeline 引用与实时到达顺序，终态字段覆盖
        if (Array.isArray(p.toolCalls) && p.toolCalls.length) mergeDoneToolCalls(msg, p.toolCalls)
        // 时间线以落库版为准：本轮视图与刷新后视图同源（工具/产物下标与终态清单一一对应）
        if (Array.isArray(p.timeline) && p.timeline.length) msg.timeline = restoreTimeline(msg, p.timeline)
        else if (msg.content !== full) msg.timeline = []
        // 落库时间线没带思考分段（正文被重建导致整条作废、或后端是旧版）：头部补一段，不许看不见
        ensureThinkSegment(msg)
        // 编排视图：done 下发分支最终状态，覆盖实时 subagent 事件收敛到终态
        if (Array.isArray(p.subagentBranches) && p.subagentBranches.length) msg.subagents = p.subagentBranches
        if (p.subagentRoute) msg.subagentRoute = p.subagentRoute
      } catch (e) { /* 旧版/停止生成：无负载 */ }
      // 计划模式被拒/超时的整轮：无正文，气泡收束语与「已停止生成」区分开（计划卡停在「未批准」态）。
      // 被「继续对话」取代的整轮不写收束语——「已被取代」已经标在计划卡角标上，两句并排是重复
      if (msg.content === '' && !msg.planSuperseded) {
        msg.content = msg.planStopped ? '（计划未批准，本轮已停止）' : '（已停止生成）'
      }
      msg.loading = false
      // 整轮已收口：提问卡（若还在）撤掉——超时/忽略走的是 done 前的 tool_status 终态，此处兜底
      // 队列一起清：这一轮不会再有可答的卡了
      msg.ask = null
      msg.askQueue = []
      // 整轮耗时（右栏「生成回答」行的 duration）；历史恢复的消息无此值则不显示
      msg.doneTime = Date.now()
      // 生成完成：编排卡片收起为一行（用户未手动干预时），避免答案出来后还占着版面
      if (msg.saOpen && !msg.saTouched) msg.saOpen = false
      msg.sources = sources
      if (msg.retrieved && Array.isArray(sources)) msg.retrieved.refs = sources.length
      msg.related = related
      msg.messageId = messageId
      msg.degradations = degradations
      // 后端替用户改过模型选择（会话里钉着的那个已停用，已清除并改用他的默认模型）：
      // 本地内存里那份同样作废，否则选择器还挂着已停用的模型，用户看到的是「它自己没改过来」
      if (degradations.some(d => d && d.code === 'modelFallback')) {
        modelMap.value = { ...modelMap.value, [sid]: '' }
        modelIssueMap.value = { ...modelIssueMap.value, [sid]: '' }
        loadSessions()
      }
      // 记录仍指向本轮才清（防止误删同会话新一轮的记录）
      if (chatStreams.get(sid) === st) chatStreams.delete(sid)
      // 收尾同样走 liveScroll：跟随中的会话落到最新，用户已上翻看历史的会话原地不动——
      // 答案写完不该把读者的位置抢走
      liveScroll()
      if (isFirstMessage) loadSessions()
      // 重新生成：把刚完成的这一版追加进版本序列并切到它（气泡底部出现 ‹ n/N › 切换器）。
      // 自动重试（prev 非空）不追加：那是同一版本的重试，不是新版本——否则失败重试一次就多出一版
      if (!prev && Array.isArray(msg.versions) && msg.versions.length) {
        msg.versions.push(snapshotVersion(msg))
        msg.vIndex = msg.versions.length - 1
      }
    },
    onWarn: w => { msg.warnMsg = w; liveScroll() },
    onError: (e, kind) => {
      // 自动重试收紧到「连接压根没建起来」这一种：原条件 `!gotToken` 不区分网络错误与
      // 服务端 5xx/限流 —— 移动端网络抖动频繁，用户会看到「消息发出去，卡 2.5 秒，
      // 自己又跑了一遍」，而服务端其实已受理（可能已扣费）。判定用 navigator.onLine
      // 优先（移动端可靠维护），再退回 fetch 的典型网络错误特征。
      const netDown = (typeof navigator !== 'undefined' && navigator.onLine === false) ||
        /Failed to fetch|NetworkError|ERR_INTERNET|ERR_NETWORK|network ?error/i.test(String(e))
      if (autoRetry > 0 && !gotToken && netDown) {
        msg.retrying = true
        liveScroll()
        setTimeout(() => {
          // 记录仍是本轮且气泡还在流式态才重试；用户已停止/记录已被清理则直接收尾
          if (chatStreams.get(sid) === st && msg.loading) {
            streamAnswer(question, imgs, msg, false, 0, deepThink, attachments, skills, mentions, { sid, agentId, model }, historyRefs, '', null, planMode)
          } else {
            msg.retrying = false
            if (chatStreams.get(sid) === st) chatStreams.delete(sid)
          }
        }, 2500)
        return
      }
      if (resume && kind === 'interrupted') {
        // 接来的通道断了 ≠ 这一轮失败了：服务端那一轮多半还在跑（宽限期内没停）。
        // 报「本轮失败」是假消息（用户会重新生成，而后端那一份照样跑完、两份答案打架）——
        // 改为静默再接（按会话计数、最多三次、接上即归零），实在接不上就收掉气泡按历史重显
        if (chatStreams.get(sid) === st) chatStreams.delete(sid)
        const tries = (resumeRetryOf.get(sid) || 0) + 1
        if (tries > 3) {
          resumeRetryOf.delete(sid)
          finishResumedShell(msg, st, sid)
          return
        }
        resumeRetryOf.set(sid, tries)
        setTimeout(async () => {
          if (currentSessionId.value !== sid) return
          // 接不上（这一轮刚好收尾了/服务重启了）必须收尾：这颗气泡是接流来的，本地没有别的
          // 路径会把它从 loading 里放出来——不接住就是一颗永久转圈的泡，用户只能刷新
          const attached = await attachRunningTurn(sid, msg)
          if (!attached) finishResumedShell(msg, null, sid)
        }, 1500)
        return
      }
      // 错误不再整体替换正文：flushNow 保留已流出的半程内容与时间线，挂独立错误卡
      // （分类文案 + 重新生成 + 异常详情折叠，对齐主流产品的失败态；此前「😅+裸异常」写进气泡
      //  会冲掉半程内容，长回答生成到 90% 失败时全部丢失）
      flushNow()
      msg.loading = false
      msg.retrying = false
      // 本轮失败：正在长的那一段思考必须停下进行中标记（它认的是「这一轮还在流」，
      // 而 loading 已假 → 该段自然收起），否则那一行会永远转下去，把「这一轮断了」读成「它还在想」
      msg.thinkLoading = false
      msg.failed = true
      msg.errorCard = { message: String(e), kind: kind || '', at: Date.now() }
      if (chatStreams.get(sid) === st) chatStreams.delete(sid)
      // 中断类（断线/切后台冻结/连接被掐断）不弹 toast：这类几乎都是移动网络状态问题，
      // toast 会盖住用户真正要点的「重新生成」按钮，而错误卡已经把话说清楚了。
      // 业务错误仍弹 —— 那是需要立即知道的服务端异常。
      if (kind !== 'interrupted') message.error(e)
      // 同上：已上翻看历史的会话原地停留（错误卡与「重新生成」在回答末尾，回到底部按钮足够引导）
      liveScroll()
    }
  }
  // 传输分岔：本地发起的轮发 POST /chat；接流只开一条 GET /chat/resume 挂到服务端那一轮上，
  // 事件派发与 done/error 收尾两条路径完全共用（这是刻意的设计——两套 UI 语义不许分叉）
  if (resume) resumeChat(sid, handlers)
  else sendQuestion(sid, question, imgs, handlers)
}
const switchVersion = (mi, delta) => {
  const m = messages.value[mi]
  if (!m || !Array.isArray(m.versions) || m.versions.length < 2) return
  const cur = m.vIndex || 0
  const ni = Math.max(0, Math.min(m.versions.length - 1, cur + delta))
  if (ni === cur) return
  m.vIndex = ni
  applyVersion(m, m.versions[ni])
}
const regenerate = mi => {
  if (loading.value) return
  for (let i = mi - 1; i >= 0; i--) {
    if (messages.value[i].role === 'user') {
      const imgs = (messages.value[i].images || []).filter(u => u.startsWith('data:'))
      const deep = !!messages.value[i].deepThink
      // 附件/技能随内存消息重发（历史回放的消息无 attachData，则不带附件重试）
      const atts = Array.isArray(messages.value[i].attachData) ? messages.value[i].attachData : []
      const skills = Array.isArray(messages.value[i].skills) ? messages.value[i].skills : []
      // @ 引用随内存消息重发（历史回放无该数据则不重发；引用只对当轮检索生效）
      const mentions = Array.isArray(messages.value[i].mentions) ? messages.value[i].mentions : []
      // # 历史引用同口径：重新生成保留原引用（该轮答案本就是基于这些历史得出的）
      const historyRefs = Array.isArray(messages.value[i].historyRefs) ? messages.value[i].historyRefs : []
      // 计划模式同口径：重新生成沿用原消息开启的计划模式（出计划→批准→再回答）
      const planMode = !!messages.value[i].planMode
      // 多版本：首次重新生成前把当前回答快照为 v1（后续版本在 done 时追加）。已有 versions 说明
      // 这条消息本就是多版本序列（当前展示的必然在序列里），无需再快照
      const ai = messages.value[mi]
      if (!Array.isArray(ai.versions) || !ai.versions.length) {
        ai.versions = [snapshotVersion(ai)]
        ai.vIndex = 0
      }
      // 传消息对象（不是下标）：流式状态已按会话拆分，replace 走对象身份
      streamAnswer(messages.value[i].content, imgs, ai, false, 1, deep, atts, skills, mentions, null, historyRefs, '', null, planMode)
      return
    }
  }
  message.warning('未找到对应的问题')
}
// ==================== 分支版本切换（持久化多版本：编辑重发 + 重新生成刷新后仍可切） ====================
// 两种数据源统一到一个切换器：
// - m.versions（本会话内存里的重新生成版本）：纯本地切换，零请求；
// - m.variantCount/variantIndex（后端持久分支，编辑重发或刷新后的历史恢复）：调切换接口让后端
//   「当前分支软删留档、目标分支按快照恢复」，随后重拉会话历史刷新整个视图（分支尾部整段变化）。
const variantSwitching = ref(false)
const switchBranch = async (mi, delta) => {
  const m = messages.value[mi]
  if (!m || variantSwitching.value) return
  // 本轮正在回答时不允许切分支：流式那轮的父消息可能正被切走的分支持有，落库会错挂
  if (loading.value) { message.warning('当前正在回答，请先停止或稍候'); return }
  // 本地内存版本优先（重新生成的即时多版本）
  if (Array.isArray(m.versions) && m.versions.length > 1) { switchVersion(mi, delta); return }
  if (!m.messageId || !m.variantCount) return
  variantSwitching.value = true
  try {
    const r = await switchMessageVariant(m.messageId, delta)
    if (r.success !== false) {
      // 重拉历史：切回的分支整段尾部都在后端恢复，本地 splice 造不出版本视图
      await switchSession(currentSessionId.value)
    } else {
      message.warning(r.msg || '切换失败')
    }
  } catch (e) {
    message.warning(e?.message || '切换失败')
  } finally {
    variantSwitching.value = false
  }
}

const stop = () => {
  // 只停当前会话的流；其它会话的后台流不受影响
  const st = chatStreams.get(currentSessionId.value)
  if (st) {
    const sid = currentSessionId.value
    chatStreams.delete(sid)
    // 先请服务端真停：abort 只断本地通道，而通道断开在「正等你作答 / 批准计划」那一轮的语义是
    // **转后台把本轮跑完**，还占着会话互斥直到人工等待超时（表现：按了停止，下一问发不出去、额度照烧）。
    stopChatTurn(sid).catch(() => message.warning('服务端没能停止本轮，它可能仍在后台跑完'))
    st.abort.abort()  // abort → api.js 按正常结束回调 onDone（气泡收尾为「已停止生成」）
  }
}

/**
 * 运行中插话队列：**纯前端数组**，显示在输入框上方（与 @引用/技能 chip 同一带）。
 * 没点「立即发送」的条目只活在这里，一个请求都不发——反悔窗口因此是白拿的。
 */
const steerQueue = ref([])

/**
 * 运行中插话：本轮还在跑时补一句方向，随**下一个工具步**送达模型。
 * 工具循环由后端框架持有，掐不断正在执行的那一步——所以这不是"打断"，是"赶在下一次工具结果里说到"。
 * 服务端同时把它落成一条普通用户消息。
 * @returns 消息 ID（前端挂到那条气泡上）；null=没有在跑的轮 / 超出单轮限额
 */
const steer = async (q) => {
  const sid = currentSessionId.value
  const text = String(q || '').trim()
  if (!sid || !text) return null
  try {
    const r = await steerChatTurn(sid, text)
    const d = r && r.data
    return (d && d.accepted && d.messageId) ? d.messageId : null
  } catch (e) {
    message.warning(e?.message || '插话没送出去')
    return null
  }
}

/**
 * 运行中插话队列（**纯前端**，显示在输入框上方一排 chip，与 @引用/技能 chip 同一带）。
 *
 * 模型很简单：**没点「立即发送」= 还没发出去**。
 * - 生成中按 Enter / 点发送 → 只把这句话放进队列，一个请求都不发；
 * - 队列里每条带「立即发送 / 修改 / 删除」三个动作，前两个都只是本地数组操作；
 * - 只有点了「立即发送」，才真发出去（纯文字走 /chat/steer，模型随下一个工具步看到；
 *   带材料的这轮塞不进去，等本轮结束按原载荷开新一轮）。
 *
 * 反悔窗口因此是白拿的：东西还在本地，删掉/改掉就是改数组。**不需要任何后端接口**——
 * 此前为"入队即落库 + 可撤回"加过 steer_flag 列与 /chat/steer/recall 端点，那是在解决
 * 一个自造的问题（已经发出去的东西怎么撤），而"点了才发"的模型里根本不存在它。
 */

/** 带材料的插话只能等本轮结束按新一轮发出（这轮的上下文在请求构建期已定型） */
function steerNeedsNextRound (s) {
  return !!(s && (s.images?.length || s.atts?.length || s.skills?.length
          || s.mentions?.length || s.historyRefs?.length || s.planMode))
}

/**
 * 生成中按发送：**只入队，不发请求**（等用户点那条上的「立即发送」）。
 * 载荷与正常提问完全同权（图片/附件/技能/@ 引用/# 历史引用/思考/计划模式）。
 * @returns true=已入队（调用方可清输入框）；false=没入成，**输入框必须原样保留**
 */
function stageSteer (payload) {
  const content = String(payload?.text || '').trim()
  if (!content) return false
  const sid = currentSessionId.value
  if (!sid || !chatStreams.has(sid)) return false
  steerQueue.value = [...steerQueue.value, reactive({ ...payload, text: content, sending: false })]
  message.success('已加入排队，点「立即发送」才发给模型')
  return true
}

/**
 * 点「立即发送」：把这一条作为插话真正发出去。
 * - 纯文字 → POST /chat/steer（服务端落成一条普通用户消息，并让它随下一个工具步送到模型）。
 *   成功后条目退场，同时把它作为**一条正常用户气泡**推进消息流——用户自己的话、自己按的发送，
 *   就该长成用户消息的样子（不挂任何「插话」角标：那不是用户说过的东西）。
 * - 带材料 → 这轮塞不进去，标成待发，本轮一结束按原载荷开新一轮——条目留在队列里等这一刻。
 * - 没有在跑的轮 / 超出单轮限额 → 退回输入框，别让用户重打一遍。
 */
async function sendSteer (item) {
  if (!item || item.sending) return
  if (steerNeedsNextRound(item)) {
    item.deferred = true
    message.info('这句带着材料，本轮的上下文已经定型——等这轮结束就自动接着发')
    return
  }
  item.sending = true
  const id = await steer(item.text)
  if (id) {
    steerQueue.value = steerQueue.value.filter(s => s !== item)
    pushSteerBubble(item, id)
    hooks.scrollForce?.()
    return
  }
  item.sending = false
  // 没有在跑的轮（刚收尾）/ 超出单轮限额：撤下这一条并把内容**放回输入框**。
  // 放回时不覆盖用户正在打的字——他多半已经在为下一句打字了，直接覆盖等于把那段话吃掉。
  steerQueue.value = steerQueue.value.filter(s => s !== item)
  if (!text.value.trim()) text.value = item.text
  else if (!text.value.includes(item.text)) text.value = (text.value.trim() + ' ' + item.text)
  message.warning('这一轮已经收尾，没能插进去——内容放回输入框了')
}

/**
 * 把一条插话作为**普通用户气泡**推进消息流（与正常发送的气泡完全同构：图片/附件/技能/
 * @ 引用/# 引用全带上）。
 * <p>`messageId` 是服务端刚落的那条消息 ID：气泡上的复制/编辑/分支切换从此可用，
 * 且刷新后由历史接口按同一个 ID 回显同一条，不会变成两条。
 * <p>刻意不挂「插话」角标：用户要的是"它就是一条我说过的话"，角标反而把它标成了二等公民。
 */
function pushSteerBubble (s, messageId) {
  messages.value.push(reactive({
    role: 'user', content: s.text, images: s.images || [],
    attachments: s.attsMeta || [], attachData: s.atts || [],
    skills: s.skills || [], mentions: s.mentions || [], historyRefs: s.historyRefs || [],
    deepThink: !!s.deepThink, planMode: !!s.planMode,
    time: Date.now(), messageId: messageId || null
  }))
}

/** 「修改」：取回输入框改（东西还在本地，就是一次数组操作） */
function editSteer (item) {
  steerQueue.value = steerQueue.value.filter(s => s !== item)
  text.value = item.text
}

/** 「删除」：丢弃这条还没发出的插话 */
function dropSteer (item) {
  steerQueue.value = steerQueue.value.filter(s => s !== item)
}

/**
 * 拖动排序：把 from 位置的条目挪到 to 位置（HTML5 拖拽的落点语义是"插到 to 之前"）。
 * <p>纯数组操作，不发任何请求——队列本来就是本地的。顺序有意义：收尾结算按数组顺序依次发出，
 * 用户把「先看 A 再看 B」和「先看 B 再看 A」排出来，就该按他排的顺序进模型。
 */
function moveSteer (from, to) {
  if (from === to || from < 0 || to < 0) return
  const arr = [...steerQueue.value]
  if (from >= arr.length) return
  const [item] = arr.splice(from, 1)
  arr.splice(Math.min(to, arr.length), 0, item)
  steerQueue.value = arr
}

/** 按原载荷把一条插话作为完整新一轮发出（与计划「继续对话」同一条通路）
 *  `fromSteer` 让后端在问题前加一句"这是对上一轮回答的补充"——不带它，模型会当成用户又问了一遍
 *  （实测短问答必现：它会复述「你消息里的 X 这一段…」），插话最核心的「改了方向立刻生效」就废了。 */
function sendSteerRound (s) {
  const userMsg = reactive({ role: 'user', content: s.text, images: s.images || [],
                        attachments: s.attsMeta || [], attachData: s.atts || [],
                        skills: s.skills || [], mentions: s.mentions || [], historyRefs: s.historyRefs || [],
                        deepThink: !!s.deepThink, planMode: !!s.planMode, fromSteer: true,
                        time: Date.now(), messageId: null })
  messages.value.push(userMsg)
  streamAnswer(s.text, s.images || [], null, messages.value.length === 1, 1, !!s.deepThink,
               s.atts || [], s.skills || [], s.mentions || [], null, s.historyRefs || '', '', userMsg,
               !!s.planMode, false, true)
}

/**
 * 本轮收尾时结算队列：把还挂着的（没点过「立即发送」的会在这里自动发出）作为新一轮交给模型。
 * <p>为什么收尾要自动发：本轮已经结束了，"插话"没有可插入的对象，`/chat/steer` 必然被拒；
 * 用户打的那句话不能因此丢掉。带材料的本来就只有这条路。
 * <p>一次只派一条（同一会话同时只能跑一轮），剩下的等它结束再派——done 会再次走到这里。
 * @param backendLeft 后端回来的"纯文字进了队列但没赶上工具步"的内容
 */
function flushPendingSteers (backendLeft = []) {
  // 后端交回的：这句已经发过了但没赶上工具步。清掉本地对应条目，它的续发由下面统一处理，
  // 但**不能在界面上凭空多冒一条**（用户看到的是同一条，不该变两条）
  for (const t of backendLeft) {
    const hit = steerQueue.value.find(s => s.text === t)
    if (hit) steerQueue.value = steerQueue.value.filter(s => s !== hit)
  }
  if (loading.value) return   // 已经有人在跑了（用户抢先发了新消息）：留着，下轮结束再结算
  const next = steerQueue.value.find(s => !s.sending)
  if (!next) return
  steerQueue.value = steerQueue.value.filter(s => s !== next)
  sendSteerRound(next)
}
// ==================== 手动压缩会话上下文（/compact） ====================
// 与「按阈值自动压缩」共用同一套摘要机制（后端 HistoryCompactionService.compactSession），差别是显式发起：
// 不等阈值、不看 context.historyCompress 开关，直接压到「除最近 2 次问答外全部并入摘要」。
const compacting = ref(false)
/**
 * 当场这一次的结果（本地状态、不落库，下一轮发送即退场）。
 * <p>持久的那份是「已压缩上下文」分隔线（靠消息行上的摘要覆盖点算出来，见 compactDividerAt）；
 * 这里只负责两件事：压缩完立刻在流尾补一条线（不等下一轮），以及给摘要面板带上
 * 「本次腾出多少」——这个数只在当场拿得到，事后会话里只剩那份滚动摘要本身。
 * <p>带 sid：会话切走后不再显示，否则会把别的会话的结论挂在当前会话下。
 */
const compactNotice = ref(null)

/**
 * 手动压缩当前会话上下文：早期对话并入摘要，完整记录仍留在会话里可回看。
 * @param instruction 附加要求（可空，如「保留结论与数字、忽略寒暄」），随摘要提示词下发给模型
 * @returns 后端统计（compressedTurns/summary 等）；无会话/正在回答/失败返回 null（原因已就地提示）
 */
const compactContext = async (instruction = '') => {
  const sid = currentSessionId.value
  if (!sid) { message.warning('当前没有可压缩的会话'); return null }
  // 正在回答时不允许压缩：本轮正在读/写同一份摘要，交叉会互相覆盖
  if (loading.value) { message.warning('正在生成回答，等本轮结束后再压缩'); return null }
  if (compacting.value) return null
  compacting.value = true
  const hide = message.loading('正在压缩早期对话…', 0)
  try {
    // 不随请求带 model：压缩就该用这条会话本身在用的那个模型（后端按会话存的覆盖→个人默认解析）。
    // 拿页签内存里的旧选择去压，会把历史折进「另一个模型的口吻」里，而用户没做过那次选择
    const r = await compactSessionApi(sid, { instruction })
    const d = (r && r.data) || {}
    // 另一套运行方式（框架引擎）的上下文由系统在提问前自己整理：这里没有「本次腾出多少」可报，
    // 也不该画一条并没有发生的分隔线——如实说排到了下一次提问前
    if (d.scheduled) {
      message.info('这套引擎会在提问前自己整理上下文，已安排下一次提问前折一次早期对话')
      return d
    }
    // 两种情形都算「没得压」：对话本来就短，或早期对话上次已经压过了（最近 2 次问答不会被压掉）
    if (!d.compressedTurns) { message.info('没有可压缩的早期对话（对话还短，或已经压过了）'); return d }
    compactNotice.value = {
      sid, turns: d.compressedTurns, keepTurns: Number(d.keepTurns) || 0,
      freedTokens: Number(d.freedTokens) || 0,
      summary: d.summary || '', summaryTokens: d.summaryTokens || 0, partial: !!d.partial
    }
    // 只报用户要的结果（腾出多少），不复述内部计数
    const freed = Number(d.freedTokens) || 0
    message.success(`已压缩早期对话${freed > 0 ? `，为后续腾出约 ${fmtTokens(freed)} tokens` : ''}`
      + (d.partial ? '（会话很长，可再次执行 /compact 继续）' : ''))
    return d
  } catch (e) {
    message.error(e?.message || '压缩失败，请稍后重试')
    return null
  } finally {
    hide()
    compacting.value = false
  }
}

// ==================== 早期对话摘要面板（流尾结果条与「已压缩上下文」分隔线共用） ====================
const compactSummaryOpen = ref(false)
const compactSummaryLoading = ref(false)
/** 面板内容：打开时快照一份。发送下一轮会清掉 compactNotice，面板若直读它会当场变空 */
const compactSummary = ref(null)

const snapshotSummary = (text, tokens, keepTurns, freed) => {
  compactSummary.value = { text, tokens: Number(tokens) || 0, keepTurns: Number(keepTurns) || 0, freed: Number(freed) || 0 }
}

/**
 * 打开「早期对话摘要」。当场压过就用 /compact 的返回（多一个「本次腾出」）；
 * 从历史里的分隔线点进来则按会话读当前那份——会话只有<b>一份</b>滚动摘要，每次压缩都往后并入同一份，
 * 所以从哪条线点进来看到的都是生效中的这一版，面板上按这个口径说。
 */
const openCompactSummary = async () => {
  const n = compactNotice.value
  if (n?.summary) {
    snapshotSummary(n.summary, n.summaryTokens, n.keepTurns, n.freedTokens)
    compactSummaryOpen.value = true
    return
  }
  const sid = currentSessionId.value
  if (!sid || compactSummaryLoading.value) return
  compactSummaryLoading.value = true
  try {
    const d = ((await sessionHistorySummaryApi(sid)) || {}).data || {}
    if (!d.summary) { message.info('这个会话还没有生成过摘要'); return }
    snapshotSummary(d.summary, d.summaryTokens, d.keepTurns, 0)
    compactSummaryOpen.value = true
  } catch (e) {
    message.error(e?.message || '摘要读取失败，请稍后重试')
  } finally {
    compactSummaryLoading.value = false
  }
}

/** 带着要求再压一次（摘要面板里的入口）：成功后面板刷成新摘要；没得压/失败的原因引擎已就地提示 */
const recompactWith = async (instruction) => {
  const d = await compactContext(instruction)
  if (!d || !d.compressedTurns) return false
  snapshotSummary(d.summary, d.summaryTokens, d.keepTurns, d.freedTokens)
  return true
}

// ==================== 挂载初始化 ====================
// 原 ChatPage onMounted 的引擎侧主体（window/document 监听与 refreshSetupGuide 留在视图）：
// 先 loadSessions，再拉智能体/技能候选（不阻塞首屏），然后按 sid / newChatTick / autoPick
// 三分支落位，聚焦输入框，最后拉配置类数据（检索调试入口/排障显示开关/个人默认模型/模型展示映射）。
const ready = async () => {
  await loadSessions()
  loadAgents()       // 智能体下拉候选（不阻塞首屏）
  loadSkills()       // 技能菜单候选（输入框「+」菜单，不阻塞首屏）
  const sid = route.query.sid
  if (sid) {
    await switchSession(sid)
  } else if (sessionStore.newChatTick > sessionStore.newChatSeen) {
    // 其它页面点过「新建对话」后跳转过来：消费该信号，直接进空会话
    sessionStore.newChatSeen = sessionStore.newChatTick
    await createNewSession()
  } else {
    await autoPick()
  }
  hooks.focusInput?.()
  // 检索调试入口是管理员调参（chat.retrievalDebugEnabled，配置里标 debug）：只有管理员才拉 /config
  // ——/config 是管理端点，普通用户调它会 403，触发全局 403 提示（角色未授权），故只对管理员读
  if (isAdminSync()) {
    getConfig().then(r => {
      if (!r.success) return
      debugEntryVisible.value = r.data?.chat?.retrievalDebugEnabled?.value === 'true'
    }).catch(() => {})
  }
  // 同一个开关的「显示」语义走公开端点：归属徽标/派遣提示/引用分值的显隐对所有人生效（含普通用户）
  getRuntimeConfig().then(r => {
    if (!r.success) return
    debugDisplayVisible.value = r.data?.ui?.debugEntry === true
    // 键缺失（老后端/未重启）按 null 处理 → 用内置默认；下发空串才是「不展示」
    const sq = r.data?.ui?.sampleQuestions
    sampleQuestionsRaw.value = typeof sq === 'string' ? sq : null
  }).catch(() => {})
  getUserPreference().then(r => {
    userDefaultModel.value = (r && r.data && r.data.defaultModel) || ''
    userDefaultModelIssue.value = (r && r.data && r.data.defaultModelIssue) || ''
  }).catch(() => {})
  loadModelIndex().then(idx => { modelIndex.value = idx || {} }).catch(() => {})
}

// 产物签名定时续签：签名有效期 1 小时，切会话/历史恢复时换过一次只覆盖「刚进来」的那批；
// 页面连续开着不动（排查问题、投屏演示、跑长任务）超过一小时后，卡片上的 URL 又会全部过期。
// 每 30 分钟换一次，留足冗余；无产物时定时器空转（首行 byId 为空直接返回，不发请求）。
// 标签页隐藏时跳过——不可见的页面不需要能下载，省掉无谓请求。
let signTimer = null
const stopSignTimer = () => { if (signTimer) { window.clearInterval(signTimer); signTimer = null } }
if (typeof window !== 'undefined') {
  signTimer = window.setInterval(() => {
    if (document.visibilityState === 'hidden') return
    refreshArtifactSigns()
  }, 30 * 60 * 1000)
  // 视图卸载时清掉，避免定时器随会话累积（引擎每进一次聊天页就 new 一个）。
  // onUnmounted 必须在 setup 的同步执行期注册才有当前实例上下文，所以包一层 try：
  // 引擎也可能被非组件场景调用，此时退化成由 ready 前的分支自行清理。
  try { onUnmounted(stopSignTimer) } catch (e) { /* 无当前实例：定时器随页面存活，可接受 */ }
}

  return {
    // 输入与发送
    text, canSend, send, stop, steerQueue, sendSteer, editSteer, dropSteer, moveSteer,
    streamAnswer, resolveApproval, pickAskOption, commitAskCustom, askSubmitAll, ignoreAsk,
    // 计划模式（人在回路）：开关（「+」面板）与计划卡裁决动作；hydrate 供通知深链/切会话点名
    planModeOn, setPlanMode, submitPlanApproval, continuePlanConversation, hydratePendingPlans,
    // 思考能力 / 档位
    thinkCapsOf, reasoningLevelsOf, deepThinkMap, deepOnOf, deepThinkOn, levelOptionsOf, currentLevelOf,
    levelMap, setThinkLevel, currentThinkLevel, reasoningLevelParam,
    // 上下文窗口档位（发送载荷 + 思考面板消费）
    ctxRangeOf, ctxWindowOptionsOf, ctxWindowMap, effectiveCtxWindowOf, setCtxWindow, contextWindowParam,
    // 技能
    skillList, pickedSkills, loadSkills, toggleSkill, skillAvaStyle,
    // 智能体
    agentList, agentMap, defaultAgentId, hasDefaultAgent,
    currentAgentId, isAdmin, agentPickerOpen, currentAgentName, currentAgent, pickAgent,
    showAgentTag, modelLabelOf, modelSwitchInfo, compactDividerAt, loadAgents,
    // 会话与消息
    currentSessionId, loading, messages, currentSessionTitle, roundCount,
    // 模型
    modelMap, currentOverrideModel, userDefaultModel, modelIndex, effectiveModel, effectiveModelLabel,
    effectiveModelIcon, effectiveModelProvider, modelSourceLabel, effectiveModelIssue,
    debugEntryVisible, debugDisplayVisible,
    // 空态示例问题（新会话空态引导卡；两套壳共用同一份生效值）
    sampleQuestions,
    // 检索 / 用量 / 来源 / 上下文容量
    lastAi, lastRetrieved, lastSources, groupedSources, lastTokens, liveTokensPreview, ctxTokens,
    ctxCapData, ctxRingDash, ctxRingLevel, panelAi, retryPanelRound, sessionArtifacts,
    sessionTokens, sessionTokensLabel, sessionRetrieval, sessionTodos,
    // 图片与附件
    pendingImages, removePendingImage, MAX_FILES, pendingFiles, hasUploadingFile,
    addFiles, removePendingFile,
    // 粘贴转附件（文件与图片 / 超长文本）
    takePastedFiles, takePastedText,
    // @ 引用（本轮显式指定知识库/文档/智能体）
    mentionOpen, mentionTab, mentionQuery, mentionLoading, mentionKbs, mentionDocs, pendingMentions,
    mentionHi, MAX_MENTIONS, isMentioned, toggleMention, removeMention, switchMentionTab,
    loadMentionCandidates, mentionDocStatus, mentionQueryNorm, mentionKbFiltered, mentionAgents,
    mentionAgentFiltered, mentionDocFiltered,
    // # 历史引用（本轮显式指定的会话历史问答）
    MAX_HISTORY_REFS, pendingHistoryRefs, histPool, isHistPicked, toggleHistoryRef, removeHistoryRef,
    // 会话生命周期
    switchSession, creatingSession, createNewSession, autoPick, handleDeleteSession,
    // 提问卡恢复（切换会话自动跑；通知深链落到当前会话时页面可再点名一次）
    hydratePendingAsk,
    // 断线重连接流（切换会话自动跑；网络恢复或从后台回到前台时页面可再点名一次）
    attachRunningTurn,
    // 手动压缩上下文（/compact：PC 斜杠命令与移动端模型面板共用）
    compacting, compactNotice, compactContext,
    // 早期对话摘要面板（PC 弹窗与移动端 sheet 共用的数据源）
    compactSummaryOpen, compactSummary, compactSummaryLoading, openCompactSummary, recompactWith,
    // 重新生成 / 分支切换 / 挂载初始化
    switchVersion, regenerate, variantSwitching, switchBranch, ready
  }
}
