// 权限档位解析（纯函数）：会话选过的 > 智能体自己的 > 平台默认档（standard，后端名册 platformDefault 点名）。

export const resolveEffectivePermission = (presets, sessionCode, agentPreset) => {
  const list = presets || []
  return sessionCode || agentPreset || (list.find(p => p.platformDefault) || {}).code || ''
}

export const permissionLabelOf = (presets, code) => {
  const p = (presets || []).find(x => x.code === code)
  return p ? p.label : code
}
