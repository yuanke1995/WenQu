// 思考能力三态与强度档位：纯常量/查表，双端共用。

export const THINK_CAPS = {
  none: { visible: false, locked: false, on: false },
  switchable: { visible: true, locked: false, on: null }, // on=null → 读按模型记忆
  always: { visible: true, locked: true, on: true }
}

export const REASONING_LEVELS = [
  { value: 'low', label: '低' },
  { value: 'medium', label: '中' },
  { value: 'high', label: '高' },
  { value: 'xhigh', label: '超高' },
  { value: 'max', label: '极致' }
]

/** ON=开启思考（不带强度：模型未登记档位时用），off=关闭思考；其余为具体档位 */
export const THINK_LEVEL_ON = '__on__'

export const levelLabel = v => REASONING_LEVELS.find(x => x.value === v)?.label || '默认强度'

// ==================== 思考面板/模型胶囊的展示文案（ChatPage 与移动壳共用口径） ====================

/** 强度行文本：未开启 / 已开启（模型未登记档位）/ 具体档位名 */
export const thinkStrengthTextOf = (on, level) =>
  !on ? '未开启' : (level === THINK_LEVEL_ON ? '已开启' : levelLabel(level))

/** 面板底部提示：不支持思考的模型给一句说明，避免「为什么没有强度行」的疑惑 */
export const thinkPanelFootOf = caps => {
  if (!caps || !caps.visible) return '该模型不支持深度思考。'
  return caps.locked
    ? '该模型始终深度思考，强度由管理员在模型库登记。'
    : '强度越高，思考越深入，回答耗时相应增加。'
}

/**
 * 模型胶囊尾部的小标记 = 此刻显示的那个模型的思考档位（低/中/高/超高/极致）。
 * 不思考或不支持思考就不挂标记——标记缺席本身就是「这一轮不思考」的答复。
 * 模型登记未加载完成时也不挂：此时档位只能取到兜底值，先渲染会闪「思」再跳成真实档位。
 */
export const pillLevelBadgeOf = ({ ref, modelInfo, capsVisible, level }) => {
  if (!ref || !modelInfo || !capsVisible) return ''
  if (!level || level === 'off') return ''
  return level === THINK_LEVEL_ON ? '思' : levelLabel(level)
}

/** 胶囊标记的悬浮说明：调整入口两端不同（宽屏 hover 行 / 触屏旁边的「思考」按钮） */
export const pillLevelTitleOf = ({ ref, badge, locked, level, coarse }) => {
  if (!ref || !badge) return ''
  const how = coarse ? '点旁边的「思考」可调整' : '悬浮下拉里的模型行可调整'
  if (locked) return `该模型始终深度思考，强度${levelLabel(level)}由管理员在模型库登记，不可调`
  return level === THINK_LEVEL_ON
    ? `已开启思考（该模型未登记强度档位），${how}`
    : `思考强度 ${levelLabel(level)}，${how}`
}

/** 档位豆的短标签：一行放不下「思考 · 超高」这种长句，完整说法留在悬浮提示里 */
export const chipLabelOf = v => (v === 'off' ? '关闭' : v === THINK_LEVEL_ON ? '开启' : levelLabel(v))
