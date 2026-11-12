// 聊天页头部的工作区标识、权限档位判据。取数与响应式状态在 useSessionWorkspace.js。

/** 会话挂在另一台电脑的工作区：历史照常能看，但这台机器不能派文件/命令过去。 */
export const isForeignWorkspace = (ws, localId) =>
  !!(ws && ws.isLocal && localId && ws.executorId && ws.executorId !== localId)

/** 未就绪原因按「异机 > 没绑目录 > 执行器离线」排序；隐式工作区不提示。 */
export const workspaceSetupHintOf = (ws, foreign) => {
  if (!ws || ws.implicit) return ''
  const name = ws.name || '这个工作区'
  if (foreign) return `「${name}」的文件夹在另一台电脑上，这台电脑不能继续使用。`
  if (!ws.roots || !ws.roots.length) return `「${name}」还没绑定本机文件夹，文件与命令暂不可用。`
  if (ws.isLocal && ws.executorOnline === false) return '本机执行器不在线，文件与命令暂不可用。'
  return ''
}

export const workspaceTitleOf = ws => {
  if (!ws) return ''
  let t = '当前工作区：' + ws.name
  if (ws.archived) t += '（已归档）'
  return t + '（点开进工作区页）'
}

/** 会话选过的档位；没选过=空串，由智能体自己的档位或平台默认档兜底。 */
export const permissionCodeOf = ws => (ws && ws.permissionPreset) ? String(ws.permissionPreset) : ''

export const workspaceRefreshNotice = ws => ws && ws.executorOnline
  ? { ok: true, text: '执行器已连上' }
  : { ok: false, text: '还没连上：去那台电脑的终端确认 wenqu-agent 正在运行' }

/** 选档分派：完全权限先过风险确认；已经是这一档就不重复问、也不重复写。 */
export const permissionPickPlan = (key, effective) => {
  if (key !== 'autonomous') return { confirm: false, apply: key }
  if (effective === 'autonomous') return { confirm: false, apply: null }
  return { confirm: true, apply: null }
}
