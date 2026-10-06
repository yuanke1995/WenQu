// ==================== 聊天引擎（ChatPage 抽出的引擎 composable，M1 引擎抽取） ====================
// 承载聊天页的「引擎逻辑」：思考/模型/智能体状态、会话生命周期、附件与 @ 引用载荷、
// 发送与 SSE 流式回答、审批、重新生成与分支切换。与视图的接缝全部收在 hooks 里
// （滚动、收面板、聚焦），PC 端由 ChatPage 按原行为传参；移动壳可传自己的落点
// （如 '/m/chat'）与滚动实现复用同一套引擎。纯函数/常量见 ./projections.js。
import { ref, reactive, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isAdminSync } from '../utils/auth'
import { message } from 'ant-design-vue'
import { sendQuestion, newSession, getHistory, deleteSessionApi, switchMessageVariant, compactSessionApi, getConfig, getRuntimeConfig, listAvailableAgents,
         listAvailableSkills, getUserPreference, approveToolCall, answerAgentAsk, ignoreAgentAsk,
         listKnowledgeBases, listDocuments, uploadChatAttachment } from '../api'
import { sessionStore, loadSessions, chatStreams, markSessionActive } from '../views/store'
import { fmtTokens } from '../utils/token'
import { loadModelIndex } from '../utils/modelRef'
import { THINK_CAPS, REASONING_LEVELS, THINK_LEVEL_ON, levelLabel, CTX_WINDOW_STEPS, fmtWindow,
         groupSources, ensureTick, mergeDoneToolCalls, restoreTimeline, extendTimelineText, extendTimelineProcess,
         pushTimelineTool, pushTimelineArtifact, snapshotVersion, applyVersion, toolCallsView, histItemDigest } from './projections'

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
  set: v => { modelMap.value = { ...modelMap.value, [currentSessionId.value]: v || '' } }
})
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
 *  list 缺省挂主输入框，编辑卡复用时传自己的列表 */
const uploadOneFile = async (f, list = pendingFiles) => {
  const item = { name: f.name, size: f.size, mime: f.type || '', fileId: '', uploading: true, error: '' }
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
const addFiles = (files, target) => {
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
    uploadOneFile(f, fileList)
  }
}
const removePendingFile = i => pendingFiles.value.splice(i, 1)
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
            related: [],
            thinking: m.thinking || '',
            thinkOpen: false,
            time: m.createTime ? new Date(m.createTime).getTime() : null,
            artifacts: Array.isArray(m.artifacts) ? m.artifacts : [],
            toolCalls: Array.isArray(m.toolCalls) ? m.toolCalls : [],
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
    .map(f => ({ name: f.name, mime: f.mime, size: f.size }))
  const skills = [...pickedSkills.value]
  // @ 引用（本轮显式指定的知识库/文档）：与问题一起提交，服务端按可见性校验后收窄检索范围/强制前置
  const mentions = pendingMentions.value.map(m => ({ type: m.type, id: m.id, name: m.name, kbId: m.kbId || '' }))
  // # 历史引用（本轮显式指定的会话历史问答）：只带 messageId，内容由服务端按会话归属查库回填
  const historyRefs = pendingHistoryRefs.value.map(h => ({ messageId: h.messageId }))
  if ((!q && !imgs.length && !atts.length) || loading.value) return
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
  // attachData 留在内存消息上：重新生成/自动重试时可原样重发（历史回放无数据，行为与图片 data: 口径一致）
  const userMsg = reactive({ role: 'user', content: q, images: imgs, attachments: attsMeta, attachData: atts,
                        skills, mentions, historyRefs, deepThink: deep, time: Date.now(), messageId: null })
  messages.value.push(userMsg)
  // userMsg 传入流式：done 回填本轮用户消息的落库 ID（userMessageId），编辑重发/分支切换从此可用
  streamAnswer(q, imgs, null, messages.value.length === 1, 1, deep, atts, skills, mentions, null, historyRefs, '', userMsg)
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
/** 智能体提问：提交用户点选/输入的答案；答案作为工具结果回给模型继续本轮。桌面壳的提问面板
 *  依赖本函数（面板挂在消息上，点选即答）；卡片保留到 done 工具状态到达（问答记录卡接管展示） */
async function answerAsk (m, text) {
  const t = (text || '').trim()
  if (!m.ask || m.ask.busy || !t) return
  m.ask.busy = true
  try {
    const r = await answerAgentAsk(m.ask.id, t)
    if (r && r.success === false) {
      message.warning(r.msg || '回答提交失败')
      m.ask.busy = false
      return
    }
    m.ask.answered = t
    hooks.scrollSoft?.()
  } catch (e) {
    message.error(e.message || '回答提交失败')
    if (m.ask) m.ask.busy = false
  }
}
/** 忽略智能体提问：不作答，立即按推荐项默认执行（与超时默认同语义的提前触发） */
async function ignoreAsk (m) {
  if (!m.ask || m.ask.busy) return
  m.ask.busy = true
  try {
    const r = await ignoreAgentAsk(m.ask.id)
    if (r && r.success === false) {
      message.warning(r.msg || '操作失败')
      m.ask.busy = false
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
                      editMessageId = '', editUserMsg = null) => {
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
  const fresh = { role: 'ai', content: '', images: [], sources: [], related: [], degradations: [], warnMsg: '', loading: true, retrying: false, thinking: '', thinkOpen: true, thinkLoading: false, stage: '正在思考中…', time: Date.now(), artifacts: [], toolCalls: [], subagents: [], plan: null, timeline: [], errorCard: null, model: model || userDefaultModel.value, delegated: null }
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
      // 智能体提问（人在回路）：提问面板挂到当前 AI 气泡状态上，桌面壳据此把底部输入框整块
      // 替换成提问面板；用户点选/输入/忽略后模型继续走。
      // options 第一项是模型给的推荐项（超时未答按它默认执行）
      try {
        const j = typeof payload === 'string' ? JSON.parse(payload) : payload
        msg.ask = { id: j.askId, topic: j.topic || '', question: j.question, options: j.options || [], timeoutMs: j.timeoutMs, busy: false, answered: '' }
        liveScroll()
      } catch (e) { /* 忽略 */ }
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
          // askUser 已出终态（用户已答或超时默认）：问答记录卡接管展示，撤掉提问卡
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
      if (msg.content === '') msg.content = '（已停止生成）'
      msg.loading = false
      // 整轮已收口：提问卡（若还在）撤掉——超时默认走的是 done 前的 tool_status 终态，此处兜底
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
            streamAnswer(question, imgs, msg, false, 0, deepThink, attachments, skills, mentions, { sid, agentId, model }, historyRefs)
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
      // 多版本：首次重新生成前把当前回答快照为 v1（后续版本在 done 时追加）。已有 versions 说明
      // 这条消息本就是多版本序列（当前展示的必然在序列里），无需再快照
      const ai = messages.value[mi]
      if (!Array.isArray(ai.versions) || !ai.versions.length) {
        ai.versions = [snapshotVersion(ai)]
        ai.vIndex = 0
      }
      // 传消息对象（不是下标）：流式状态已按会话拆分，replace 走对象身份
      streamAnswer(messages.value[i].content, imgs, ai, false, 1, deep, atts, skills, mentions, null, historyRefs)
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
    chatStreams.delete(currentSessionId.value)
    st.abort.abort()  // abort → api.js 按正常结束回调 onDone（气泡收尾为「已停止生成」）
  }
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
  }).catch(() => {})
  getUserPreference().then(r => {
    userDefaultModel.value = (r && r.data && r.data.defaultModel) || ''
  }).catch(() => {})
  loadModelIndex().then(idx => { modelIndex.value = idx || {} }).catch(() => {})
}

  return {
    // 输入与发送
    text, canSend, send, stop, streamAnswer, resolveApproval, answerAsk, ignoreAsk,
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
    // 检索 / 用量 / 来源 / 上下文容量
    lastAi, lastRetrieved, lastSources, groupedSources, lastTokens, liveTokensPreview, ctxTokens,
    ctxCapData, ctxRingDash, ctxRingLevel, panelAi, retryPanelRound, sessionArtifacts,
    sessionTokens, sessionTokensLabel, sessionRetrieval, ctxPct, ctxLevel,
    // 图片与附件
    pendingImages, addImageFiles, removePendingImage, MAX_FILES, pendingFiles, hasUploadingFile,
    addFiles, removePendingFile,
    // @ 引用（本轮显式指定知识库/文档/智能体）
    mentionOpen, mentionTab, mentionQuery, mentionLoading, mentionKbs, mentionDocs, pendingMentions,
    mentionHi, MAX_MENTIONS, isMentioned, toggleMention, removeMention, switchMentionTab,
    loadMentionCandidates, mentionDocStatus, mentionQueryNorm, mentionKbFiltered, mentionAgents,
    mentionAgentFiltered, mentionDocFiltered,
    // # 历史引用（本轮显式指定的会话历史问答）
    MAX_HISTORY_REFS, pendingHistoryRefs, histPool, isHistPicked, toggleHistoryRef, removeHistoryRef,
    // 会话生命周期
    switchSession, creatingSession, createNewSession, autoPick, handleDeleteSession,
    // 手动压缩上下文（/compact：PC 斜杠命令与移动端模型面板共用）
    compacting, compactNotice, compactContext,
    // 重新生成 / 分支切换 / 挂载初始化
    switchVersion, regenerate, variantSwitching, switchBranch, ready
  }
}
