/** 「由「X」回答」徽标的数据源：按 id（优先）或名称回查归属智能体；找不到返回 null。 */
export const agentBadgeOf = (agents, agentId, agentName) => {
  const list = Array.isArray(agents) ? agents : []
  if (agentId) {
    const byId = list.find(a => a && a.id === agentId)
    if (byId) return byId
  }
  if (agentName) return list.find(a => a && a.name === agentName) || null
  return null
}
