// ==================== 新手配置引导：共享状态（仿 utils/modelRef.js 模块级缓存模式） ====================
// 判定口径（与侧栏 tag / 对话页引导卡共用同一份状态，避免各页面各自拉数口径漂移）：
//   ① 添加聊天模型：/provider/available 存在 type=chat 的模型（含供应商登记）
//   ② 设置默认聊天模型：/user/preference 的 defaultModel 非空且仍存在于可用 chat 模型列表
//      （失效引用视为未设置——挂了不存在模型的引用，引导不该消失）
//   ③ 添加向量模型：存在 type=embedding 的模型（建知识库硬依赖）
// 失败静默保留旧状态；首次未加载成功时 loaded=false，消费方据此不显示任何引导
// （宁可不引导，不误报——绝不能给已配好的存量用户弹出假引导）。
import { computed, reactive } from 'vue'
import { getUserPreference, listAvailableModels } from '../api.js'

const TTL_MS = 15000

export const setupGuide = reactive({
  loaded: false,          // 首次拉取成功前为 false：所有消费方在 loaded=false 时不得显示引导
  chatModels: [],         // [{ ref, displayName, providerName }]
  embeddingCount: 0,
  defaultModel: ''        // /user/preference 的 defaultModel 原始引用
})

export const chatReady = computed(() => setupGuide.chatModels.length > 0)
// 失效引用（默认模型指向已删除/停用的模型）视为未设置
export const defaultReady = computed(() =>
  Boolean(setupGuide.defaultModel) && setupGuide.chatModels.some(m => m.ref === setupGuide.defaultModel))
export const chatDone = computed(() => chatReady.value && defaultReady.value)
export const embeddingReady = computed(() => setupGuide.embeddingCount > 0)
// 未完成项数（①②③ 合计，含向量模型项）——侧栏入口「还差 N 项」
export const pendingCount = computed(() =>
  (chatReady.value ? 0 : 1) + (defaultReady.value ? 0 : 1) + (embeddingReady.value ? 0 : 1))

let lastFetched = 0
let inflight = null // 并发去重：AppLayout force 与页面 TTL 刷新同时发生时只发一次请求

async function fetchOnce() {
  try {
    // Promise.all 两请求并发；任一 reject 视为本次对账失败——保留旧状态（loaded 首次成功才置 true）
    const [groups, prefRes] = await Promise.all([listAvailableModels(), getUserPreference()])
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
