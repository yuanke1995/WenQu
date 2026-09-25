import { listAvailableModels } from '../api.js'

/**
 * 模型引用展示索引：ref（providerId/modelId）→ { displayName, providerName, icon, type }。
 * 供智能体卡片、聊天页状态栏等「只有引用串、没有选择器」的场景把引用渲染成 名称+图标。
 * 10s TTL 模块级缓存，失败静默（展示层兜底显示原始引用）。
 */
let cache = { ts: 0, index: null }

export async function loadModelIndex(force = false) {
  if (!force && cache.index && Date.now() - cache.ts < 10000) return cache.index
  try {
    const groups = await listAvailableModels()
    const index = {}
    for (const g of groups || []) {
      for (const m of g.models || []) {
        index[m.ref] = { displayName: m.displayName, providerName: g.name, icon: g.icon, type: m.type, thinking: m.thinking }
      }
    }
    cache = { ts: Date.now(), index }
  } catch (e) {
    cache = { ts: Date.now(), index: cache.index || {} }
  }
  return cache.index
}

/** 引用展示信息（未加载/查不到返回 null，调用方回退显示原始引用） */
export function modelRefInfo(ref) {
  return cache.index ? cache.index[ref] : null
}
