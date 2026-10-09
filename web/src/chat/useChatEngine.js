// ==================== 聊天引擎（ChatPage 抽出的引擎 composable，M1 引擎抽取） ====================
// 承载聊天页的「引擎逻辑」：思考/模型/智能体状态、会话生命周期、附件与 @ 引用载荷、
// 发送与 SSE 流式回答、审批、重新生成与分支切换。与视图的接缝全部收在 hooks 里
// （滚动、收面板、聚焦），PC 端由 ChatPage 按原行为传参；移动壳可传自己的落点
// （如 '/m/chat'）与滚动实现复用同一套引擎。纯函数/常量见 ./projections.js。
import { ref, reactive, computed, watch, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isAdminSync } from '../utils/auth'
import { message } from 'ant-design-vue'
import { sendQuestion, newSession, getHistory, deleteSessionApi, switchMessageVariant, compactSessionApi, getConfig, getRuntimeConfig, listAvailableAgents,
         listAvailableSkills, getUserPreference, approveToolCall, answerAgentAsk, ignoreAgentAsk, stopChatTurn, steerChatTurn,
         listPendingAsks, resolvePlanApproval, supersedePlanApproval, listPendingPlans, updateSessionModelApi, getSessionModelApi,
         listKnowledgeBases, listDocuments, uploadChatAttachment,
         refreshArtifactSigns as refreshArtifactSignsApi } from '../api'
import { sessionStore, loadSessions, chatStreams, markSessionActive } from '../views/store'
import { fmtTokens } from '../utils/token'
import { loadModelIndex } from '../utils/modelRef'
import { THINK_CAPS, REASONING_LEVELS, THINK_LEVEL_ON, levelLabel, CTX_WINDOW_STEPS, fmtWindow,
         groupSources, ensureTick, mergeDoneToolCalls, restoreTimeline, extendTimelineText, extendTimelineProcess,
         pushTimelineTool, pushTimelineArtifact, snapshotVersion, applyVersion, toolCallsView, histItemDigest,
         DEFAULT_SAMPLE_QUESTIONS, parseSampleQuestions, PASTE_TEXT_BASE } from './projections'

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
// ==================== 智能体（4.1）：会话级绑定（首问锁定，切换=新会话） ====================
const agentList = ref([])                       // 全部主智能体
// 未绑定会话的待选值：会话ID → 选中的智能体（'__auto__'=自动派遣 / id / ''=全局配置；
// '' 在后端有默认智能体时解析为默认智能体，未设默认才走纯系统设置）
const agentMap = ref({})
// 已绑定会话的绑定镜像：会话ID → {agentId, agentName}，来源为后端 c_ai_session 的绑定
// （done 事件即时回填 + 会话列表兜底，刷新页面后仍能判断锁定态）
const sessionAgent = ref({})
const defaultAgentId = ref('')                  // 默认智能体（isDefault），无则空=全局配置
// 是否存在「默认智能体」（isDefault=1）：有则后端把空 agentId（全局配置）解析为它——
// 「默认（全局配置）」与默认智能体是同一套配置，菜单里只留默认智能体一项，避免两行同义的重复项
const hasDefaultAgent = computed(() => agentList.value.some(a => a.isDefault === 1 || a.isDefault === true))
const AUTO_AGENT = '__auto__'                   // 自动派遣哨兵值（请求时转 "auto"，由后端按描述路由）
/** 会话的智能体绑定（后端权威）：本地镜像优先（本轮刚锁定、会话列表尚未刷新），否则取会话列表项 */
const boundAgentOf = sid => {
  if (!sid) return null
  const local = sessionAgent.value[sid]
  if (local) return local
  const s = sessionStore.list.find(x => x.id === sid)
  // 必须用 != null 同时排除 null 与 undefined：后端未绑定时 agent_id 为 NULL，序列化后是 null（不是字段缺失），
  // 若只判 !== undefined 会把「未绑定」误判成「已绑定为不使用智能体」——表现为会话一进来就锁死，
  // 每次点选都走"开启新会话"分支，点一次建一个空会话。
  if (s && s.agentId != null) return { agentId: s.agentId || '', agentName: s.agentName || '' }
  return null
}
const currentAgentId = computed({
  // 新会话默认「自动派遣」：首问由模型按名称+描述挑最合适的智能体（命中后即锁定，后续轮次不再重路由）；
  // 已绑定会话一律回显锁定值（''=该会话绑定为「不使用智能体」）；没有任何智能体时回退默认助手/全局配置
  get: () => {
    const bound = boundAgentOf(currentSessionId.value)
    if (bound) return bound.agentId || ''
    const memo = agentMap.value[currentSessionId.value]
    if (memo !== undefined) return memo
    return agentList.value.length ? AUTO_AGENT : (defaultAgentId.value || '')
  },
  set: v => { agentMap.value = { ...agentMap.value, [currentSessionId.value]: v || '' } }
})
/** 当前会话是否已锁定智能体（含"已绑定为不使用智能体"；锁定后切换=新会话） */
const agentLocked = computed(() => boundAgentOf(currentSessionId.value) !== null)
const isAdmin = ref(isAdminSync())
const agentPickerOpen = ref(false)
/** 当前生效的智能体名（自动派遣/空 = 走对应模式；已锁定会话显示绑定名） */
const currentAgentName = computed(() => {
  const bound = boundAgentOf(currentSessionId.value)
  if (bound) {
    if (!bound.agentId) return '默认（全局配置）'
    const b = agentList.value.find(x => x.id === bound.agentId)
    return (b && b.name) || bound.agentName || '默认（全局配置）'
  }
  if (currentAgentId.value === AUTO_AGENT) return '自动派遣'
  const a = agentList.value.find(x => x.id === currentAgentId.value)
  return a ? a.name : '默认（全局配置）'
})
/** 当前生效的智能体对象（仅用于 agent-pill 头像；自动派遣 / 全局配置 = null，不显示头像） */
const currentAgent = computed(() => {
  const id = currentAgentId.value
  if (!id || id === AUTO_AGENT) return null
  return agentList.value.find(x => x.id === id) || null
})
/** 选中智能体：未绑定会话记录待选值；已绑定会话按主流约定「切换 = 开启新会话」 */
const pickAgent = id => {
  agentPickerOpen.value = false
  if (agentLocked.value) {
    // 人设/知识库/工具集都随智能体变，同一会话中途换人会让上下文串味 —— 换人即换会话
    const cur = currentAgentName.value
    const target = id === AUTO_AGENT
      ? '自动派遣' : (agentList.value.find(a => a.id === id)?.name || '默认（全局配置）')
    message.info(`本会话已绑定「${cur}」，将开启新会话使用「${target}」`)
    createNewSession(id)
    return
  }
  currentAgentId.value = id || ''
  if (id === AUTO_AGENT) message.success('已切换为「自动派遣」')
  else {
    const a = agentList.value.find(x => x.id === id)
    if (a) message.success(`已切换为「${a.name}」`)
  }
}
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
/** 模型引用 → 展示名（模型库友好名优先，查不到回退原始引用 provider/model） */
const modelLabelOf = ref => modelIndex.value[ref]?.displayName || ref
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
const loadAgents = async () => {
  try {
    // 走 available 接口：问答用户可读的精简列表（管理端 /agent/list 仅管理员）
    const r = await listAvailableAgents()
    agentList.value = (r && r.success && Array.isArray(r.data)) ? r.data : []
    // 优先默认助手；未设默认时用列表第一个，保证下拉总有可选项
    const def = agentList.value.find(a => a.isDefault === 1 || a.isDefault === true) || agentList.value[0]
    defaultAgentId.value = def ? def.id : ''
    // 当前会话尚未选择时：有候选智能体则默认「自动派遣」，否则落到默认智能体
    if (!(currentSessionId.value in agentMap.value)) {
      agentMap.value = { ...agentMap.value, [currentSessionId.value]: agentList.value.length ? AUTO_AGENT : defaultAgentId.value }
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
  for (const s of sessionStore.list) {
    if (s && s.id && !(s.id in modelMap.value)) patch[s.id] = s.model || ''
  }
  if (Object.keys(patch).length) modelMap.value = { ...modelMap.value, ...patch }
}
// 监听列表本身，而不是在挂载流程里调一次：会话列表由侧栏（AppLayout）与聊天页各自触发加载，
// 并发时 loadSessions 的加载代次守卫会丢弃较早的一次（列表还没写入），挂载时点读到的可能是空列表。
// deep 兼顾「查看更多」的 push 增量；immediate 兼顾列表在引擎初始化前就已加载的情形。
// seed 幂等且只做字典合并，列表每次变动重跑无副作用。
watch(() => sessionStore.list, seedSessionModels, { deep: true, immediate: true })
const userDefaultModel = ref('')    // 个人默认模型（个人设置，后端 /user/preference）
const modelIndex = ref({})          // 引用 → { displayName, providerName, icon }（展示映射）

/** 实际生效的模型（引用或遗留名）：会话覆盖 > 个人默认（智能体不绑模型、全局兜底已移除；空=未指定，发送时引导选择） */
const effectiveModel = computed(() => {
  return currentOverrideModel.value || userDefaultModel.value
})
/** 生效模型的展示名（引用串在模型库里映射成 友好名；查不到回退原值） */
const effectiveModelLabel = computed(() => {
  const v = effectiveModel.value
  if (!v) return ''
  const info = modelIndex.value[v]
  return info ? info.displayName : v
})
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
const lastAi = computed(() => [...messages.value].reverse().find(m => m.role === 'ai' && !m.loading && (m.content || m.sources?.length)))
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
/** 分类展示名与配色（与后端 ctxParts 的键一一对应；占比条按此顺序堆叠） */
const CTX_PART_META = [
  { key: 'messages', label: '消息', color: '#1677ff' },
  { key: 'summary', label: '早期摘要', color: '#13c2c2' },
  { key: 'chunks', label: '知识块', color: '#52c41a' },
  { key: 'system', label: '系统提示词', color: '#722ed1' },
  { key: 'toolSchema', label: '系统工具', color: '#fa8c16' },
  { key: 'mcpSchema', label: 'MCP 工具', color: '#eb2f96' },
  { key: 'skill', label: '技能', color: '#a0d911' },
  { key: 'memory', label: '记忆', color: '#2f54eb' },
  { key: 'input', label: '输入', color: '#8c8c8c' },
  { key: 'other', label: '其他', color: '#bfbfbf' }
]
/** 容量数据：窗口取模型登记/用户档位（tokens.window 优先，回落 modelIndex），用量取本轮真实 prompt（无则估算）；
 *  数据源走 ctxTokens（流式期间=usage 预下发的估算，完成后=done 的实测 tokens），生成一开始即点亮 */
const ctxCapData = computed(() => {
  const t = ctxTokens.value
  const info = modelIndex.value[effectiveModel.value]
  const window_ = (t && t.window) || info?.contextWindow || 0
  const used = t ? (Number(t.prompt) || Number(t.context) || 0) : 0
  const parts = (t && t.parts && typeof t.parts === 'object') ? t.parts : null
  const rows = parts
    ? CTX_PART_META.filter(m => Number(parts[m.key]) > 0)
        .map(m => ({ ...m, tokens: Number(parts[m.key]) }))
    : []
  const sum = rows.reduce((a, r) => a + r.tokens, 0)
  const pct = window_ > 0 ? Math.min(100, Math.round(used / window_ * 1000) / 10) : 0
  const cached = t && Number(t.cached) > 0 ? Number(t.cached) : 0
  return {
    window: window_, used, pct, rows, sum,
    // 填充条宽=用量/窗口（不取整：窗口大用量小时 pct 取整会成 0 宽），填充内段宽按构成比分
    fillPct: window_ > 0 ? Math.min(100, used / window_ * 100) : 0,
    // 分类占比以分类之和为分母（校准后与 prompt 一致；无 parts 时退化为空列表）
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
// ===== 上下文占用条（P0 #3）：context/budget，>80% 警告 / >95% 危险 =====
const ctxPct = computed(() => {
  const t = lastTokens.value
  if (!t || !t.budget) return 0
  return Math.min(100, Math.max(0, Math.round((t.context / t.budget) * 100)))
})
const ctxLevel = computed(() => (ctxPct.value > 95 ? 'danger' : (ctxPct.value > 80 ? 'warn' : '')))
// ==================== 图片上传（粘贴，压缩为 dataURL） ====================
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
const addImageFiles = (files, list = pendingImages) => {
  for (const f of files) {
    if (list.value.length >= 5) { message.warning('最多上传 5 张图片'); break }
    if (!f.type.startsWith('image/')) continue
    compressImage(f).then(dataUrl => list.value.push({ dataUrl })).catch(() => message.error(`图片处理失败: ${f.name}`))
  }
}
const removePendingImage = i => pendingImages.value.splice(i, 1)
// ==================== 附件上传（「+」菜单选择/拖入 → 先传换 fileId，问答请求只带 fileId） ====================
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
  const item = { name: f.name, size: f.size, mime: f.type || '', fileId: '', uploading: true, error: '', ...(extra || {}) }
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
    if (f.type.startsWith('image/')) {
      if (imgList.value.length >= 5) { message.warning('最多上传 5 张图片'); continue }
      compressImage(f).then(dataUrl => imgList.value.push({ dataUrl }))
        .catch(() => message.error(`图片处理失败: ${f.name}`))
      continue
    }
    if (fileList.value.length >= MAX_FILES) { message.warning(`一次最多上传 ${MAX_FILES} 个附件`); break }
    if (!SUPPORTED_EXTS.includes(extOf(f.name))) {
      message.warning(`暂不支持的附件类型：${f.name}（支持 PDF / Word / Excel / PPT / 文本与代码文件）`)
      continue
    }
    if (f.size > MAX_FILE_MB * 1024 * 1024) { message.warning(`单个附件不能超过 ${MAX_FILE_MB}MB：${f.name}`); continue }
    uploadOneFile(f, fileList, extra)
  }
}
const removePendingFile = i => pendingFiles.value.splice(i, 1)
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
/** 输入框粘贴事件里取长文本：带图片的粘贴让给图片管线，短文本照常内联可编辑。
 *  返回 true 表示这次粘贴已被消费（原文不再落进输入框），调用方按需要截断冒泡 */
const takePastedText = (e, target) => {
  const cd = e.clipboardData
  if (!cd || Array.from(cd.files || []).some(f => f.type.startsWith('image/'))) return false
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
  // 同步 URL query：侧边栏高亮与刷新恢复都依赖 sid 在地址上。
  // 必须保留兄弟参数：?approval=<id>（工具审批通知深链）直接写 query:{sid} 会被抹掉，
  // 恢复横幅能否出现变成异步时序的侥幸（fetch 先回才亮），刷新后则永久丢失。
  router.replace({ path: hooks.chatPath, query: { ...route.query, sid } }).catch(() => {})
  try {
    // 403（会话归属拒绝）由本函数静默回退处理，不触发全局提示
    const r = await getHistory(sid, { silentForbidden: true })
    // 快速连续切换时晚到的历史响应不覆盖当前视图
    if (r.success && Array.isArray(r.data) && currentSessionId.value === sid) {
      const list = r.data
        .filter(m => m && (m.content || (Array.isArray(m.images) && m.images.length)))
        .map(m => {
          const msg = {
            role: m.role === 'user' ? 'user' : 'ai',
            content: String(m.content || ''),
            messageId: m.messageId || m.id || null,
            fb: (m.fb === 0 || m.fb === 1) ? m.fb : null,
            images: Array.isArray(m.images) ? m.images : [],
            attachments: Array.isArray(m.attachments) ? m.attachments : [],
            sources: Array.isArray(m.sources) ? m.sources : [],
            // 「接下来可以」推荐（随消息落库后回显）：此前恒为空数组，刷新后推荐整块消失
            related: Array.isArray(m.related) ? m.related : [],
            // @ 引用（随用户消息常驻落库）：刷新/历史回显保留引用标注，重新生成/编辑重发据此原样带出
            mentions: Array.isArray(m.mentions) ? m.mentions : [],
            // # 历史引用（同上）
            historyRefs: Array.isArray(m.historyRefs) ? m.historyRefs : [],
            // 本轮终止原因（叫停且该轮没产出回答时记在用户提问上）：刷新后据此在问题下补一行说明
            endReason: m.endReason || '',
            thinking: m.thinking || '',
            thinkOpen: false,
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
          // 会话内 @ 智能体（§4）：落库的归属与会话绑定不同 = 该轮由 @ 提及的智能体作答，
          // 刷新后委派徽标照常回显（绑定镜像缺省时无从比对则不显示，与排障徽标同一宽容口径）
          const bound = boundAgentOf(sid)
          if (msg.agentId && msg.agentName && bound && msg.agentId !== (bound.agentId || '')) {
            msg.delegated = { name: msg.agentName, description: '' }
          }
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
      // 提问卡恢复（fire-and-forget）：等待作答期间断线不再中止本轮，所以刷新/换设备后进来仍能把那张卡答完
      if (!st || !st.msg.ask) hydratePendingAsk(sid)
      // 计划批准卡恢复（同上）：计划等待期间断线不中止本轮，刷新/换设备后仍能批准（回答转后台生成落库）
      if (!st || !st.msg.planCard) hydratePendingPlans(sid)
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
  }
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
  // 已绑定智能体的空会话不复用：它带着自己的绑定（删掉一轮问答不会解锁），复用会让"切换智能体"看起来没生效
  const emptySid = sessionStore.list.find(s => (s.messageCount ?? 0) === 0
    && !chatStreams.has(s.id) && !boundAgentOf(s.id))?.id
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
const send = () => {
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
  // 生成中再发一句 = 挂一条待插话（输入框上方，点「插话」才送出）。主输入框不再因跑着而禁用：
  // 想改方向不该被迫先停止本轮。载荷与正常提问完全同权（图片/附件/技能/@/#/模型/思考/计划模式）
  if (loading.value) {
    text.value = ''
    hooks.closePanels?.()
    stageSteer({ text: q, images: imgs, atts, attsMeta, skills, mentions, historyRefs,
                 deepThink: deepThinkOn.value, planMode: planModeOn.value })
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
  if (!m.approval || m.approval.busy) return
  m.approval.busy = true
  try {
    const r = await approveToolCall(m.approval.id, approved)
    if (r && r.success === false) {
      message.warning(r.msg || '审批提交失败')
      m.approval.busy = false
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
 * 把一张提问卡挂到消息上（SSE 实时下发与刷新/换设备后重建两条路共用）。
 * @param remainingMs 倒计时剩余毫秒；不传则按 j.timeoutMs 从此刻起算（实时路径没有时差）
 * j.restored=true 表示这张卡是从服务端待答记录重建的：本设备没有这一轮的流式通道，答完后回答在
 * 后台生成，要靠 pollRestoredAnswer 轮询历史取回。
 */
function mountAskCard (m, j, remainingMs) {
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
  m.ask = {
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
  return m.ask
}

/**
 * 会话加载/切换后恢复待答提问卡：提问已落库、等待期间断线不中止本轮，所以刷新页面或换设备后
 * 那根工具线程还阻塞着，答案照样送得回去。只重建「未过期且唤醒句柄还在」的最新一张——
 * 同一轮连问几卡时，早先那张已随工具终态落成气泡里的问答记录。
 */
const hydratePendingAsk = async sid => {
  if (!sid) return
  const st = chatStreams.get(sid)
  if (st && st.msg && st.msg.ask) return   // 实时卡还挂在这一轮上，不重复挂
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
  const item = alive[alive.length - 1]
  const list = messages.value
  // 这一轮的助手消息要到回答完成才落库，此刻视图里没有可挂的气泡：补一个「等你作答」的占位泡，
  // 答题面板照旧挂底部；答完轮询到新回答后整个列表按历史重载，占位泡随之消失
  const anchor = reactive({ role: 'ai', content: '', images: [], sources: [], related: [],
    degradations: [], warnMsg: '', loading: true, retrying: false, thinking: '', thinkOpen: false,
    thinkLoading: false, stage: '等你作答，回答才不会跑偏', time: Date.now(), artifacts: [],
    toolCalls: [], subagents: [], plan: null, timeline: [], errorCard: null, model: '', delegated: null })
  mountAskCard(anchor, { ...item, restored: true }, item.remainingMs)
  messages.value = [...list, anchor]
  hooks.scrollForce?.()
}

/** 恢复态轮询句柄（同一时刻只留一份：切会话/答完即停） */
let restoredPoll = null
const stopRestoredPoll = () => { if (restoredPoll) { clearInterval(restoredPoll); restoredPoll = null } }

/**
 * 落库计划快照 → 气泡计划卡字段（历史回显与待批准卡重建共用）：
 * plan=末版正文（卡片主体），versions=已产各版（不含末版，供「修改记录」逐版回看），feedback=产出末版的用户意见。
 * 老消息无 versions 字段 → 退化为单版（与改动前一致）。
 */
const planCardFromRecord = (rec, statusOverride) => {
  const all = (rec && Array.isArray(rec.versions) ? rec.versions : [])
    .filter(v => v && typeof v.plan === 'string')
  const st = rec && (rec.status === 'rejected' || rec.status === 'superseded') ? rec.status : 'approved'
  const card = {
    plan: '', status: statusOverride || st,
    busy: false, editText: '', versions: [], feedback: '',
    auto: !!(rec && rec.auto)   // 按消息意图自动开启（历史回显照常标「自动开启」）
  }
  if (all.length) {
    const last = all[all.length - 1]
    card.plan = last.plan
    card.feedback = last.feedback || ''
    card.versions = all.slice(0, -1).map(v => ({ plan: v.plan, feedback: v.feedback || '' }))
  } else if (rec) {
    card.plan = String(rec.plan || '')
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
  const list = messages.value
  // 这一轮的助手消息要到回答完成才落库，此刻视图里没有可挂的气泡：补一个「等你批准」的占位泡，
  // 批准卡挂上；批准后轮询到新回答，整个列表按历史重载，占位泡随之消失
  const anchor = reactive({ role: 'ai', content: '', images: [], sources: [], related: [],
    degradations: [], warnMsg: '', loading: true, retrying: false, thinking: '', thinkOpen: false,
    thinkLoading: false, stage: '等你批准执行计划，批准后回答才开始', time: Date.now(), artifacts: [],
    toolCalls: [], subagents: [], plan: null, planCard: null, timeline: [], errorCard: null, model: '', delegated: null })
  anchor.planCard = {
    ...planCardFromRecord(item, 'pending'),
    id: item.planApprovalId,
    restored: true,
    timeoutMs: Number(item.timeoutMs) || 0,
    deadline: item.remainingMs > 0 ? Date.now() + item.remainingMs : 0
  }
  messages.value = [...list, anchor]
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
      if (a.restored) { m.ask = null; m.stage = ''; stopRestoredPoll(); switchSession(currentSessionId.value) }
      return
    }
    a.answered = true
    if (a.restored) {
      // 本设备没有这一轮的流，终态事件不会来撤卡：自己撤下面板、把气泡交给后台生成并轮询取回
      m.ask = null
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
  if (!m.ask || m.ask.busy) return
  m.ask.busy = true
  try {
    const r = await ignoreAgentAsk(m.ask.id)
    if (r && r.success === false) {
      message.warning(r.msg || '操作失败')
      m.ask.busy = false
      if (m.ask.restored) { m.ask = null; m.stage = ''; stopRestoredPoll(); switchSession(currentSessionId.value) }
      return
    }
    if (m.ask.restored) {
      m.ask = null
      m.stage = '回答生成中，稍后自动刷新…'
      pollRestoredAnswer(currentSessionId.value, m)
      return
    }
    m.ask.answered = '（已忽略）'
    hooks.scrollSoft?.()
  } catch (e) {
    message.error(e.message || '操作失败')
    if (m.ask) m.ask.busy = false
  }
}
const streamAnswer = (question, imgs, replaceMsg, isFirstMessage, autoRetry = 1, deepThink = false,
                      attachments = [], skills = [], mentions = [], prev = null, historyRefs = [],
                      editMessageId = '', editUserMsg = null, planMode = false) => {
  // prev = 自动重试上下文 { sid, agentId, model }：沿用原会话与原选择，不读当前 UI 态
  //（重试定时器触发时用户可能已切到别的会话/换了模型）
  const sid = prev ? prev.sid : currentSessionId.value
  const agentId = prev ? prev.agentId : (currentAgentId.value === AUTO_AGENT ? 'auto' : (currentAgentId.value || ''))
  const model = prev ? prev.model : (currentOverrideModel.value || '')
  // 重新生成：被替换的旧回答 ID 必须在下面 fresh 覆盖之前捕获（fresh 会把 messageId 置空，
  // 覆盖后再读就永远是空 → 后端不软删旧回答 → 刷新后同一问题出现两条答案）
  const replacedMessageId = replaceMsg && replaceMsg.messageId ? replaceMsg.messageId : ''
  // 流式回调统一改写 msg 对象（而非 messages.value[idx]）：切走会话后 messages 数组已换人，
  // 下标会指错位置；对象引用由 chatStreams 持有，切回来时 switchSession 把它接回视图尾部
  // model 先按前端解析的生效引用预填（覆盖>个人默认，与后端 resolveModel 同序）：「模型已切换」
  // 分隔记录在本轮回答一出现就能比对；done 再用后端权威值校正
  // stage 预置检索档（两档口径见 projections 的阶段文案映射）：与后端首条事件映射结果同词，开场不闪词
  const fresh = { role: 'ai', content: '', images: [], sources: [], related: [], degradations: [], warnMsg: '', loading: true, retrying: false, thinking: '', thinkOpen: true, thinkLoading: false, stage: '正在检索资料…', time: Date.now(), artifacts: [], toolCalls: [], subagents: [], plan: null, planCard: null, timeline: [], errorCard: null, model: model || userDefaultModel.value, delegated: null }
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
  sendQuestion(sid, question, imgs, {
    signal: abort.signal,
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
    agentId,
    // 会话级模型覆盖：仅用户手动切换时传（空=后端按 个人默认>无 兜底解析，全局模型默认已退役）
    model,
    onThinking: t => {
      msg.thinking = (msg.thinking || '') + t
      msg.thinkLoading = true
      liveScroll()
    },
    onThinkingDone: payload => {
      msg.thinkLoading = false
      msg.thinkOpen = false
      try {
        const j = JSON.parse(payload)
        if (j.thinking) msg.thinking = j.thinking
      } catch (e) { /* 兼容旧 payload */ }
    },
    onToken: t => { gotToken = true; full += t; flushSoon(); msg.stage = ''; msg.thinkLoading = false },
    onProcess: t => {
      // 过程独白（<process> 标签内，与正文分流）：累积 processText 并推进时间线过程段（灰字弱化渲染）。
      // 后端按 token 增量下发，这里逐条追加即成流式。先 flushNow 落屏节流中的正文：正文 token 走
      // 120ms 节流而过程事件即时到达，不先刷正文，过程段会被记在尚未落屏的正文之前——实时视图里
      // 灰字块跳到正文上方（done 用落库版时间线校正后又会跳回去，一来一回正是「块突然出现又移位」）
      flushNow(false)
      const prevLen = (msg.processText || '').length
      msg.processText = (msg.processText || '') + t
      extendTimelineProcess(msg, prevLen, msg.processText.length)
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
        msg.approval = { id: j.approvalId, tool: j.tool, args: j.args, timeoutMs: j.timeoutMs, busy: false }
        liveScroll()
      } catch (e) { /* 忽略 */ }
    },
    onAskUser: payload => {
      // 智能体提问（人在回路，一卡多问）：提问面板挂到当前 AI 气泡状态上，桌面壳据此把底部输入框整块
      // 替换成提问面板；用户逐题翻页作答、一次性批量提交后模型继续走。
      // 每题 options 第一项是模型给的推荐项（仅提示用）——超时与忽略都不会替你选答案
      try {
        mountAskCard(msg, typeof payload === 'string' ? JSON.parse(payload) : payload)
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
        c.plan = j.plan || c.plan        // 后端权威稿（已剥标签/护栏截断）覆盖流式累积
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
        if (!Array.isArray(msg.toolCalls)) msg.toolCalls = []
        if (t.status === 'start') {
          // askUser 的「进行中」由提问卡（m.ask）表达，不渲染通用工具卡——否则一问两卡
          if (t.name === 'askUser') return
          const rec = { ...t, startAt: Date.now() }
          msg.toolCalls.push(rec)
          pushTimelineTool(msg, rec)
          // 进入工具阶段：上一句阶段文案（如「正在检索资料…」）已过期，清掉避免工具跑完又复活。
          // 该阶段由工具卡片自己表达（转圈 + 实时耗时），底部进度行不重复描述
          msg.stage = ''
          ensureTick()
        } else {
          // askUser 已出终态（用户已答，或超时/忽略后按「未作答」收尾）：问答记录卡接管展示，撤掉提问卡
          if (t.name === 'askUser' && msg.ask) msg.ask = null
          const list = msg.toolCalls
          const last = [...list].reverse().find(x => x.name === t.name && x.status === 'start')
          if (last) {
            last.status = t.status
            last.elapsedMs = t.elapsedMs || 0
            if (t.args) last.args = t.args
            if (t.result != null) last.result = t.result
            if (t.error) last.error = t.error
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
    onAgentDispatched: payload => {
      try {
        const r = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (!r || !r.name) return
        msg.dispatched = {
          name: r.name, description: r.description || '', fallback: r.fallback === true
        }
        liveScroll()
      } catch (e) { /* 忽略 */ }
    },
    // 会话内 @ 智能体（§4）：本轮由用户 @ 提及的智能体作答（会话绑定不变）。用户指令，常显徽标；
    // 归属同步改写（done 的 delegatedAgentName 会再校正一次，两处同值）
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
    // 会话级绑定结果：后端在首问解析并锁定后就下发（不等整轮结束），输入区立刻切锁定态
    onAgentBound: payload => {
      try {
        const r = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (!r || !r.locked) return
        sessionAgent.value = {
          ...sessionAgent.value,
          [sid]: { agentId: r.agentId || '', agentName: r.agentName || '' }
        }
        if (r.agentName) msg.agentName = r.agentName
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
        // planSuperseded 走另一句收束语
        if (p.planCancelled) msg.planStopped = true
        if (p.planSuperseded) msg.planSuperseded = true
        // 待插话结算：本轮收尾（答完/被停/失败）时结算一次——纯文字没赶上工具步的由后端回
        // pendingSteers，带材料的本来就排在本地等这一刻。用户说过的话不许静默丢掉，
        // 这是插话机制的底线，比"能不能立刻改方向"更重要
        setTimeout(() => flushPendingSteers(Array.isArray(p.pendingSteers) ? p.pendingSteers : []), 0)
        // 编辑重发：done 带回本轮用户消息的落库 ID——回填到本地新用户消息上，
        // 该消息的 ‹ n/N › 分支切换器与「再次编辑」从此可用
        if (editUserMsg && p.userMessageId) editUserMsg.messageId = p.userMessageId
        if (p.tokens && typeof p.tokens === 'object') msg.tokens = p.tokens
        // 会话级绑定：首问后本会话即锁定智能体（agentLocked 由后端下发，含"绑定为不使用智能体"）。
        // 本地镜像先于会话列表刷新生效，输入区立刻切到锁定态（切换=新会话）
        if (p.agentLocked) {
          sessionAgent.value = {
            ...sessionAgent.value,
            [sid]: { agentId: p.agentId || '', agentName: p.agentName || '' }
          }
        }
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
        // 编排视图：done 下发分支最终状态，覆盖实时 subagent 事件收敛到终态
        if (Array.isArray(p.subagentBranches) && p.subagentBranches.length) msg.subagents = p.subagentBranches
        if (p.subagentRoute) msg.subagentRoute = p.subagentRoute
      } catch (e) { /* 旧版/停止生成：无负载 */ }
      // 计划模式被拒/超时的整轮：无正文，气泡收束语与「已停止生成」区分开（计划卡停在「未批准」态）；
      // 被「继续对话」取代的整轮：收束语按取代口径（计划卡停在「已被取代」态）
      if (msg.content === '') msg.content = msg.planSuperseded ? '（你提出了修改，这版计划已被取代）'
        : (msg.planStopped ? '（计划未批准，本轮已停止）' : '（已停止生成）')
      msg.loading = false
      // 整轮已收口：提问卡（若还在）撤掉——超时/忽略走的是 done 前的 tool_status 终态，此处兜底
      if (msg.ask) msg.ask = null
      // 整轮耗时（右栏「生成回答」行的 duration）；历史恢复的消息无此值则不显示
      msg.doneTime = Date.now()
      // 生成完成：编排卡片收起为一行（用户未手动干预时），避免答案出来后还占着版面
      if (msg.saOpen && !msg.saTouched) msg.saOpen = false
      msg.sources = sources
      if (msg.retrieved && Array.isArray(sources)) msg.retrieved.refs = sources.length
      msg.related = related
      msg.messageId = messageId
      msg.degradations = degradations
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
      // 错误不再整体替换正文：flushNow 保留已流出的半程内容与时间线，挂独立错误卡
      // （分类文案 + 重新生成 + 异常详情折叠，对齐主流产品的失败态；此前「😅+裸异常」写进气泡
      //  会冲掉半程内容，长回答生成到 90% 失败时全部丢失）
      flushNow()
      msg.loading = false
      msg.retrying = false
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
  })
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
 * 运行中插话：本轮还在跑时补一句方向，随**下一个工具步**送达模型。
 * 工具循环由后端框架持有，掐不断正在执行的那一步——所以这不是"打断"，是"赶在下一次工具结果里说到"。
 * @returns false=没有在跑的轮 / 超出单轮限额，调用方按普通消息发送即可
 */
const steer = async (q) => {
  const sid = currentSessionId.value
  const text = String(q || '').trim()
  if (!sid || !text) return false
  try {
    const r = await steerChatTurn(sid, text)
    return !!(r && r.data && r.data.accepted)
  } catch (e) {
    message.warning(e?.message || '插话没送出去')
    return false
  }
}

/**
 * 输入框上方的待插话条目。载荷与正常提问完全同权（图片/附件/技能/@ 引用/# 历史引用/思考/计划模式）。
 *
 * 送达时机由**载荷**决定，不是我们偷懒：
 * - 纯文字 → 点「插话」即送后端，随下一个工具步附进工具结果送达模型（这轮的提示词还在长，
 *   但工具返回值是我们唯一能干净插话进去的口子）。
 * - 带材料（图片/附件/技能/@/#/计划模式）→ 正在跑的这轮**塞不进去**：这些都要在请求构建期展开成
 *   上下文（图片走视觉、附件走解析、技能与引用进系统提示词），那一次请求早就发出去了。
 *   所以这条标成 deferred，本轮一结束就按原载荷自动开新一轮发出——材料不会丢，只是生效在下一轮。
 *
 * 没送出前可改可删；送出后不给删除按钮（后端队列没有撤回接口，装了个假的是骗人）。
 * 不进消息流、不参与导出与重新生成。
 */
const steersPending = ref([])

/** 带材料的插话只能等本轮结束按新一轮发出（这轮的上下文已经定型） */
function steerNeedsNextRound (s) {
  return !!(s && (s.images?.length || s.atts?.length || s.skills?.length
          || s.mentions?.length || s.historyRefs?.length || s.planMode))
}

/** 生成中按发送：把这一条挂到输入框上方（不立即送达，留出反悔和改口的余地） */
function stageSteer (payload) {
  const content = String(payload?.text || '').trim()
  if (!content) return
  steersPending.value = [...steersPending.value, { ...payload, text: content, sent: false, deferred: false }]
}

/** 点条目上的「插话」：纯文字送后端随下一个工具步生效；带材料的改成本轮结束后自动发出 */
async function insertSteer (i) {
  const item = steersPending.value[i]
  if (!item || item.sent || item.deferred) return
  if (steerNeedsNextRound(item)) {
    steersPending.value = steersPending.value.map((x, xi) => (xi === i ? { ...x, deferred: true } : x))
    message.info('这条带着材料，本轮上下文已定型——这轮一结束就自动发出')
    return
  }
  if (await steer(item.text)) {
    steersPending.value = steersPending.value.map((x, xi) => (xi === i ? { ...x, sent: true } : x))
    return
  }
  // 没在跑的轮 / 超出单轮限额：退回输入框，别让用户重打一遍
  const back = item.text
  steersPending.value = steersPending.value.filter((_, xi) => xi !== i)
  text.value = back
  message.warning('这轮没能插话，内容已放回输入框')
}

/** 点「编辑」：取回输入框改（只有还没送出的能改） */
function editSteer (i) {
  const item = steersPending.value[i]
  if (!item || item.sent || item.deferred) return
  text.value = item.text
  steersPending.value = steersPending.value.filter((_, xi) => xi !== i)
}

/** 点「删除」：丢弃这条还没送出的插话 */
function dropSteer (i) {
  const item = steersPending.value[i]
  if (!item || item.sent || item.deferred) return
  steersPending.value = steersPending.value.filter((_, xi) => xi !== i)
}

/** 按原载荷把一条插话作为完整新一轮发出（与计划「继续对话」同一条通路） */
function sendSteerRound (s) {
  const userMsg = reactive({ role: 'user', content: s.text, images: s.images || [],
                        attachments: s.attsMeta || [], attachData: s.atts || [],
                        skills: s.skills || [], mentions: s.mentions || [], historyRefs: s.historyRefs || [],
                        deepThink: !!s.deepThink, planMode: !!s.planMode, time: Date.now(), messageId: null })
  messages.value.push(userMsg)
  streamAnswer(s.text, s.images || [], null, messages.value.length === 1, 1, !!s.deepThink,
               s.atts || [], s.skills || [], s.mentions || [], null, s.historyRefs || '', '', userMsg, !!s.planMode)
}

/**
 * 本轮收尾时结算待插话：一次只派一条（同一会话同时只能跑一轮），剩下的等它结束再派
 * ——done 会再次走到这里，直到队列清空。
 * @param backendLeft 后端回来的"进了队列但没赶上工具步"的纯文字插话
 */
function flushPendingSteers (backendLeft = []) {
  // 还留在后端的交回未送达状态（它可能还带着材料，按原载荷发才不丢东西）
  for (const t of backendLeft) {
    const hit = steersPending.value.find(s => s.text === t)
    if (hit) hit.sent = false
    else steersPending.value = [...steersPending.value, { text: t, sent: false, deferred: false }]
  }
  // 已送达并已被消费的（仍是 sent 状态）随本轮退场
  steersPending.value = steersPending.value.filter(s => !s.sent)
  if (loading.value) return   // 已经有人在跑了（用户抢先发了新消息）：留着，下轮结束再结算
  const next = steersPending.value.find(s => !s.sent)
  if (!next) return
  steersPending.value = steersPending.value.filter(s => s !== next)
  sendSteerRound(next)
}
// ==================== 手动压缩会话上下文（/compact） ====================
// 与「按阈值自动压缩」共用同一套摘要机制（后端 RagService.compactSession），差别是显式发起：
// 不等阈值、不看 context.historyCompress 开关，直接压到「除最近 2 轮外全部并入摘要」。
const compacting = ref(false)
// 最近一次压缩结果（本地提示条，不落库）：带 sid，会话切走后不再显示——否则会把别的会话的结论挂在当前会话下
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
    const r = await compactSessionApi(sid, { instruction, model: currentOverrideModel.value || '' })
    const d = (r && r.data) || {}
    // 两种情形都算「没得压」：对话本来就短，或早期对话上次已经压过了（最近 2 轮不会被压掉）
    if (!d.compressedTurns) { message.info('没有可压缩的早期对话（对话还短，或已经压过了）'); return d }
    // keepRecent 是消息条数（一会合一答算两条），提示条按「轮」说话，故折半后再至少保 1
    compactNotice.value = {
      sid, turns: d.compressedTurns, keepTurns: Math.max(1, Math.round((d.keepRecent || 4) / 2)),
      summary: d.summary || '', summaryTokens: d.summaryTokens || 0, partial: !!d.partial
    }
    message.success(d.partial
      ? `已压缩 ${d.compressedTurns} 轮早期对话（会话很长，可再次执行 /compact 继续）`
      : `已压缩 ${d.compressedTurns} 轮早期对话为摘要`)
    return d
  } catch (e) {
    message.error(e?.message || '压缩失败，请稍后重试')
    return null
  } finally {
    hide()
    compacting.value = false
  }
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
    text, canSend, send, stop, steersPending, insertSteer, editSteer, dropSteer, streamAnswer, resolveApproval, pickAskOption, commitAskCustom, askSubmitAll, ignoreAsk,
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
    agentList, agentMap, sessionAgent, defaultAgentId, hasDefaultAgent, AUTO_AGENT, boundAgentOf,
    currentAgentId, agentLocked, isAdmin, agentPickerOpen, currentAgentName, currentAgent, pickAgent,
    showAgentTag, modelLabelOf, modelSwitchInfo, loadAgents,
    // 会话与消息
    currentSessionId, loading, messages, currentSessionTitle, roundCount,
    // 模型
    modelMap, currentOverrideModel, userDefaultModel, modelIndex, effectiveModel, effectiveModelLabel,
    effectiveModelIcon, effectiveModelProvider, modelSourceLabel, debugEntryVisible, debugDisplayVisible,
    // 空态示例问题（新会话空态引导卡；两套壳共用同一份生效值）
    sampleQuestions,
    // 检索 / 用量 / 来源 / 上下文容量
    lastAi, lastRetrieved, lastSources, groupedSources, lastTokens, liveTokensPreview, ctxTokens,
    ctxCapData, ctxRingDash, ctxRingLevel, panelAi, retryPanelRound, sessionArtifacts,
    sessionTokens, sessionTokensLabel, sessionRetrieval, ctxPct, ctxLevel,
    // 图片与附件
    pendingImages, addImageFiles, removePendingImage, MAX_FILES, pendingFiles, hasUploadingFile,
    addFiles, removePendingFile,
    // 长文本粘贴转附件
    takePastedText,
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
    // 手动压缩上下文（/compact：PC 斜杠命令与移动端模型面板共用）
    compacting, compactNotice, compactContext,
    // 重新生成 / 分支切换 / 挂载初始化
    switchVersion, regenerate, variantSwitching, switchBranch, ready
  }
}
