// 智能体页共享的纯函数：无响应式状态、无 DOM，供 AgentsPage 与各 composable 共用同一口径。
// 从 AgentsPage.vue 搬出，行为逐字不变。
export const isDefault = a => a.isDefault === 1 || a.isDefault === true
/** 已停用：对话页不可选、@/委派不可用、分享链接失效；管理列表仍可见以便恢复 */
export const isDisabledAgent = a => a.disabled === 1 || a.disabled === true
/** 系统内置（如默认「知识库助手」）：不可删除，卡片上以「内置」标记区分 */
export const isBuiltin = a => a.isBuiltin === 1 || a.isBuiltin === true
/** 平台预置的内置助手（created_by=system，如网页操作助手）：全员可用，管理员可配置，不可删除、不可设为默认 */
export const isPreset = a => a.createdBy === 'system' && !isBuiltin(a)
/** 子智能体：不直接参与对话，只能被主智能体委派 */
export const isSub = a => a.isSubagent === 1 || a.isSubagent === true
/** 逗号串 → 数组（具体项） */
export const splitList = v => (v ? String(v).split(',').filter(Boolean) : [])
/** 三态原始值 → 字符串（null/undefined 归 ''，其余 String） */
export const triStr = v => (v === null || v === undefined ? '' : String(v))
/** 时间显示：ISO 串压到秒，去掉 T */
export const fmtTime = t => (t ? String(t).replace('T', ' ').slice(0, 19) : '')
/** diff 值预览：长文本压成单行短串，完整值走 title 悬浮 */
export const clip = s => {
  const t = String(s ?? '')
  return t.length > 80 ? t.slice(0, 80) + '…' : t
}
