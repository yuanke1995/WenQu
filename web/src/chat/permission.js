// 权限档位解析（纯函数）：会话选过的 > 智能体自己的 > 平台默认档；高于平台上限的按上限报。
// 上限与默认档都取自后端名册（available/platformDefault），前端不重抄 PermissionPolicyResolver。

export const resolveEffectivePermission = (presets, sessionCode, agentPreset) => {
  const list = presets || []
  const code = sessionCode || agentPreset || (list.find(p => p.platformDefault) || {}).code || ''
  if (!code) return ''
  const p = list.find(x => x.code === code)
  if (!p || p.available !== false) return code
  // 名册按能力递增排列：可用项里排最后的那一档就是平台上限
  const ceiling = list.filter(x => x.available !== false).slice(-1)[0]
  return ceiling ? ceiling.code : code
}

export const permissionLabelOf = (presets, code) => {
  const p = (presets || []).find(x => x.code === code)
  return p ? p.label : code
}
