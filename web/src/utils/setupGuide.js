// ==================== 新手配置引导：共享状态（仿 utils/modelRef.js 模块级缓存模式） ====================
// 两层清单，口径分开：
//   【必配层】不配置就完全用不了的硬依赖（琥珀待办，计入 pendingCount）：
//     ① 添加聊天模型：/provider/available 存在对话类模型（type=chat，或全模态 omni——本质是对话模型）
//     ② 设置默认聊天模型：/user/preference 的 defaultModel 非空且仍存在于可用 chat 模型列表
//        （失效引用视为未设置——挂了不存在模型的引用，引导不该消失）
//   【可选层】只影响知识库、不影响对话（弱化建议，不计入侧栏 pendingCount）：
//     ③ 添加向量模型：存在 type=embedding 的模型（建知识库硬依赖，但只聊天的人不需要）
//     侧栏「还差 N 项」只统计必配层，知识库缺口交给知识库菜单与知识库页提示，
//     避免只用对话的用户永远看到一条消不掉的待办。
// 失败静默保留旧状态；首次未加载成功时 loaded=false，消费方据此不显示任何引导
// （宁可不引导，不误报——绝不能给已配好的存量用户弹出假引导）。
import { computed, reactive, ref } from 'vue'
import { getUserPreference, listAvailableModels, listExecutors, listProviders } from '../api.js'
import { isAdminSync, menuHasPath, profileSync } from './auth.js'

const TTL_MS = 15000

export const setupGuide = reactive({
  loaded: false,          // 首次拉取成功前为 false：所有消费方在 loaded=false 时不得显示引导
  chatModels: [],         // [{ ref, displayName, providerName }]
  embeddingCount: 0,
  defaultModel: '',       // /user/preference 的 defaultModel 原始引用
  executors: [],          // 本机执行器清单（含 online）：标准档引导用
  providers: [],          // [{ name, typeCounts }]：仅可管理供应商的用户可见，用于区分空态
  providersKnown: false   // true=已成功拿到供应商列表（含空数组）；false=未获取/无权限
})

// ---------- 必配层判定（聊天） ----------
export const chatReady = computed(() => setupGuide.chatModels.length > 0)
// 失效引用（默认模型指向已删除/停用的模型）视为未设置
export const defaultReady = computed(() =>
  Boolean(setupGuide.defaultModel) && setupGuide.chatModels.some(m => m.ref === setupGuide.defaultModel))
export const chatDone = computed(() => chatReady.value && defaultReady.value)
// 已完成步数 / 未完成项数：只算聊天必需的两步（与欢迎卡、侧栏、抽屉共用同一口径）
export const chatDoneCount = computed(() => (chatReady.value ? 1 : 0) + (defaultReady.value ? 1 : 0))
export const pendingCount = computed(() => (chatReady.value ? 0 : 1) + (defaultReady.value ? 0 : 1))

// ---------- 可选层判定（知识库） ----------
export const embeddingReady = computed(() => setupGuide.embeddingCount > 0)

// ---------- 可选层判定（工具调用：本机执行器） ----------
// 只在标准档（个人/小团队本机部署）提示；企业档多为服务器部署，本机执行器不是必选项。
export const executorVisible = computed(() => profileSync() === 'standard')
export const executorOnlineCount = computed(() => setupGuide.executors.filter(e => e && e.online).length)
export const executorReady = computed(() => executorOnlineCount.value > 0)

// 能否自行登记供应商：没有「智能体」菜单的用户只能看到联系管理员的说明
export const canManageProviders = computed(() => Boolean(isAdminSync() || menuHasPath('/agents')))

// ---------- 「稍后再说」：24 小时内收起欢迎卡（侧栏入口保留，用户可随时主动进入） ----------
// 放在模块级而不是组件内：欢迎区的标题/副标题也要跟着一起隐藏，否则会只剩一句
// 「先完成 2 步」悬在空白处。
const SNOOZE_KEY = 'wenqu.setupGuide.snoozeUntil'
const readSnooze = () => {
  try {
    const until = Number(localStorage.getItem(SNOOZE_KEY) || 0)
    return Number.isFinite(until) && Date.now() < until
  } catch (e) {
    return false
  }
}
export const guideSnoozed = ref(readSnooze())
export const snoozeGuide = () => {
  try { localStorage.setItem(SNOOZE_KEY, String(Date.now() + 24 * 60 * 60 * 1000)) } catch (e) { /* 隐私模式：仅本次会话生效 */ }
  guideSnoozed.value = true
}

let lastFetched = 0
let lastProviderFetched = 0
let inflight = null // 并发去重：AppLayout force 与页面 TTL 刷新同时发生时只发一次请求

async function fetchOnce() {
  try {
    const known = Boolean(isAdminSync() || menuHasPath('/agents'))
    const [groups, prefRes, execRes] = await Promise.all([
      listAvailableModels(),
      getUserPreference(),
      executorVisible.value ? listExecutors().catch(() => null) : Promise.resolve(null)
    ])
    const chatModels = []
    let embeddingCount = 0
    for (const g of groups || []) {
      for (const m of g.models || []) {
        if (m.type === 'chat' || m.type === 'omni') chatModels.push({ ref: m.ref, displayName: m.displayName, providerName: g.name })
        else if (m.type === 'embedding') embeddingCount++
      }
    }
    setupGuide.chatModels = chatModels
    setupGuide.embeddingCount = embeddingCount
    setupGuide.executors = (execRes && Array.isArray(execRes.data)) ? execRes.data : []
    setupGuide.defaultModel = (prefRes && prefRes.data && prefRes.data.defaultModel) || ''
    // 供应商列表只为区分空态：仅在没有聊天模型时拉，且 5 分钟内不重复拉
    // （/provider 会带用量与引用统计，不能跟着 15s TTL 频繁请求）
    const needProviders = known && chatModels.length === 0 &&
      (!setupGuide.providersKnown || Date.now() - lastProviderFetched > 5 * 60 * 1000)
    if (needProviders) {
      try {
        const providerRes = await listProviders()
        if (providerRes && Array.isArray(providerRes.data)) {
          setupGuide.providers = providerRes.data
            .filter(p => p && p.enabled !== false)
            .map(p => ({ name: p.name, typeCounts: p.typeCounts || {} }))
          setupGuide.providersKnown = true
          lastProviderFetched = Date.now()
        }
      } catch (e) {
        // 无权限或网络失败：保持未知，文案退回通用说法
      }
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
