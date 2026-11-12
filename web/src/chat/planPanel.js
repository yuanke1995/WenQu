// 右栏「计划」列表（纯函数）：本会话每轮出过的计划各列一条（待确认置顶，其余最新在前）。
// 标签取该轮的用户问题——计划卡没有标题，问题最能认出是哪一份；行上补「第几版 + 几步」。

/**
 * @param list 消息列表
 * @param stepCountOf (plan) => number 步骤数口径（引擎侧 planStepCount）
 */
export const buildSessionPlans = (list, stepCountOf) => {
  const out = []
  const msgs = list || []
  for (let i = msgs.length - 1; i >= 0; i--) {
    const m = msgs[i]
    if (!m || m.role !== 'ai' || !m.planCard) continue
    let label = ''
    for (let j = i - 1; j >= 0; j--) {
      const prev = msgs[j]
      if (prev && prev.role === 'user') { label = String(prev.content || '').replace(/\s+/g, ' ').trim(); break }
    }
    const c = m.planCard
    out.push({
      mi: i,
      label: label || '（无提问）',
      status: c.status,
      vers: Array.isArray(c.versions) ? c.versions.length : 0,
      steps: stepCountOf(c.plan)
    })
  }
  // 待确认的计划必须露在最上面（它卡着本轮，不是历史导航）；同一状态内保持最新在前
  return out.sort((a, b) => Number(b.status === 'pending') - Number(a.status === 'pending'))
}

export const planStateText = st => ({
  drafting: '生成中', pending: '待确认', approved: '已批准', superseded: '已被取代', rejected: '未批准'
}[st] || st)
