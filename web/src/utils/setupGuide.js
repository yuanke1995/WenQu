// ==================== 新手配置引导：共享状态（仿 utils/modelRef.js 模块级缓存模式） ====================
// 两层清单，口径分开：
//   【必配层】不配置就完全用不了的硬依赖（琥珀待办）：
//     ① 添加聊天模型：/provider/available 存在 type=chat 的模型（含供应商登记）
//     ② 设置默认聊天模型：/user/preference 的 defaultModel 非空且仍存在于可用 chat 模型列表
//        （失效引用视为未设置——挂了不存在模型的引用，引导不该消失）
//     ③ 添加向量模型：存在 type=embedding 的模型（建知识库硬依赖）
//   【进阶层】配置了效果更好、不配置自动回落默认方案的可选增强（弱化建议，可跳过）：
//     视觉模型（/user/preference.defaultVisionModel）、重排模型（含生效开关）、
//     记忆向量化模型、兜底抽取模型（后三者取 /user/settings 个人值，键与 config-schema.json 同源）
// 失败静默保留旧状态；首次未加载成功时 loaded=false，消费方据此不显示任何引导
// （宁可不引导，不误报——绝不能给已配好的存量用户弹出假引导）。
import { computed, reactive } from 'vue'
import { getUserPreference, getUserSettings, listAvailableModels } from '../api.js'

const TTL_MS = 15000

export const setupGuide = reactive({
  loaded: false,          // 首次拉取成功前为 false：所有消费方在 loaded=false 时不得显示引导
  chatModels: [],         // [{ ref, displayName, providerName }]
  embeddingCount: 0,
  defaultModel: '',       // /user/preference 的 defaultModel 原始引用
  defaultVisionModel: '', // /user/preference 的 defaultVisionModel（进阶层：视觉模型）
  // ---- 进阶层（/user/settings）：拉取失败时 settingsLoaded=false，建议整体不显示（不拖垮必配层） ----
  settingsLoaded: false,
  settingValues: {},      // 本人个人值（path → 字符串）
  settingGlobals: {},     // 系统全局值（开关生效判定参照）
  settingDefs: {}         // 字段默认值（fields 摊平，开关兜底用）
})

// ---------- 必配层判定 ----------
export const chatReady = computed(() => setupGuide.chatModels.length > 0)
// 失效引用（默认模型指向已删除/停用的模型）视为未设置
export const defaultReady = computed(() =>
  Boolean(setupGuide.defaultModel) && setupGuide.chatModels.some(m => m.ref === setupGuide.defaultModel))
export const chatDone = computed(() => chatReady.value && defaultReady.value)
export const embeddingReady = computed(() => setupGuide.embeddingCount > 0)
// 未完成项数（①②③ 合计，含向量模型项）——侧栏入口「还差 N 项」
export const pendingCount = computed(() =>
  (chatReady.value ? 0 : 1) + (defaultReady.value ? 0 : 1) + (embeddingReady.value ? 0 : 1))

// ---------- 进阶层判定（「配置了效果更好」，值非空即视为已配置——建议项宽松，不做失效引用校验） ----------
const notEmpty = v => v !== undefined && v !== null && String(v).trim() !== ''
// 开关生效值：个人值优先 → 全局值 → schema 默认（settings 里 switch 存字符串 'true'/'false'，def 可能是布尔）
function effectiveSwitch(path) {
  const p = setupGuide.settingValues[path]
  if (notEmpty(p)) return String(p) === 'true'
  const g = setupGuide.settingGlobals[path]
  if (notEmpty(g)) return String(g) === 'true'
  const d = setupGuide.settingDefs[path]
  return d === true || String(d) === 'true'
}
export const advVisionReady = computed(() => notEmpty(setupGuide.defaultVisionModel))
// 重排：模型非空且生效开关为开（schema 默认关——只配模型不开开关不会生效，不能算已配置）
export const advRerankReady = computed(() =>
  effectiveSwitch('retrieval.rerank.enabled') && notEmpty(setupGuide.settingValues['retrieval.rerank.model']))
// 用户显式关闭重排（个人值='false'）→ 尊重选择，不再建议重排模型
export const rerankDeclined = computed(() =>
  String(setupGuide.settingValues['retrieval.rerank.enabled'] ?? '') === 'false')
export const advMemoryReady = computed(() => notEmpty(setupGuide.settingValues['memory.embeddingRef']))
export const advGraphReady = computed(() => notEmpty(setupGuide.settingValues['graphrag.modelRef']))
// 进阶未配置数：侧栏入口弱化态「N 项可选」；settings 未加载成功返回 0（宁可不引导，不误报）
export const advPendingCount = computed(() => {
  if (!setupGuide.settingsLoaded) return 0
  return (advVisionReady.value ? 0 : 1)
    + (rerankDeclined.value || advRerankReady.value ? 0 : 1)
    + (advMemoryReady.value ? 0 : 1)
    + (advGraphReady.value ? 0 : 1)
})

let lastFetched = 0
let inflight = null // 并发去重：AppLayout force 与页面 TTL 刷新同时发生时只发一次请求

async function fetchOnce() {
  try {
    // 建议层数据单独 catch：/user/settings 失败只隐藏建议区，不影响必配引导的展示与对账
    const [groups, prefRes, settingsRes] = await Promise.all([
      listAvailableModels(),
      getUserPreference(),
      getUserSettings().catch(() => null)
    ])
    const chatModels = []
    let embeddingCount = 0
    for (const g of groups || []) {
      for (const m of g.models || []) {
        if (m.type === 'chat') chatModels.push({ ref: m.ref, displayName: m.displayName, providerName: g.name })
        else if (m.type === 'embedding') embeddingCount++
      }
    }
    setupGuide.chatModels = chatModels
    setupGuide.embeddingCount = embeddingCount
    setupGuide.defaultModel = (prefRes && prefRes.data && prefRes.data.defaultModel) || ''
    setupGuide.defaultVisionModel = (prefRes && prefRes.data && prefRes.data.defaultVisionModel) || ''
    const d = settingsRes && settingsRes.data
    if (d && typeof d === 'object') {
      const defs = {}
      for (const f of d.fields || []) defs[f.path] = f.def
      setupGuide.settingValues = d.values || {}
      setupGuide.settingGlobals = d.globals || {}
      setupGuide.settingDefs = defs
      setupGuide.settingsLoaded = true
    } else {
      setupGuide.settingsLoaded = false
    }
    setupGuide.loaded = true
    lastFetched = Date.now()
  } catch (e) {
    // 失败静默：区分「失败」与「为空」——网络异常不把状态清零（避免误报「未配置」）。
    // 首次即失败则维持 loaded=false，消费方不显示引导。
  } finally {
    inflight = null
  }
}

/** 拉取/对账引导状态：TTL 15s；force=true 跳过 TTL（配置动作后立即对账用）。返回当前状态。 */
export function refreshSetupGuide(force = false) {
  if (!force && Date.now() - lastFetched < TTL_MS) return Promise.resolve(setupGuide)
  if (!inflight) inflight = fetchOnce()
  return inflight
}
