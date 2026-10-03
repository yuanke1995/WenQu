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
        index[m.ref] = {
          displayName: m.displayName,
          providerName: g.name,
          icon: g.icon,
          type: m.type,
          thinking: m.thinking,
          // 思考强度：支持档位数组 + 模型默认档位（聊天页等级选择据此给可选项）
          reasoningLevels: Array.isArray(m.reasoningLevels) ? m.reasoningLevels : [],
          defaultReasoningLevel: m.defaultReasoningLevel || '',
          // 上下文窗口（未登记为 null=用全局默认；聊天页模型悬浮面板展示/调整用）
          // contextWindowMin：可选下限（null=不可调，面板窗口行保持只读）
          contextWindow: m.contextWindow ?? null,
          contextWindowMin: m.contextWindowMin ?? null
        }
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
