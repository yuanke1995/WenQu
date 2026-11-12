// 智能体选择状态：清单、会话级待选值（下一问用谁）、当前作答方与归属徽标去重。
// 会话不锁定作答方：每轮按请求解析，换人立即生效；这里只维护「下一问用谁」的前端待选值。
import { ref, computed } from 'vue'

export const createAgentPicker = ({ currentSid, messages, listAgentsApi, listConsultableApi,
                                    remember = () => {}, onPick = () => {} }) => {
  const agentList = ref([])          // 全部主智能体（不含子智能体）
  // 会话ID → 用户为本会话挑的作答智能体（''=默认助手）。纯前端待选值：后端没有"首问锁定"，
  // 每轮都按请求里的 agentId 解析，所以这一项就是"下一问用谁"，换人立即生效、不需要开新会话。
  const agentMap = ref({})
  const defaultAgentId = ref('')     // 默认智能体（isDefault），无则空=全局配置
  // 是否存在「默认智能体」（isDefault=1）：有则后端把空 agentId（全局配置）解析为它——
  // 「默认（全局配置）」与默认智能体是同一套配置，菜单里只留默认智能体一项，避免两行同义的重复项
  const hasDefaultAgent = computed(() => agentList.value.some(a => a.isDefault === 1 || a.isDefault === true))
  /** 本会话最近一次作答的智能体（取最后一条带归属快照的助手消息；没有=null）。
   *  打开旧会话时输入框的待选值跟随它：接着问下去，多半还是想问同一位——而不是刷新后
   *  被拉回全局默认。它只是"下一问用谁"的预填（每轮按请求解析，会话本就不锁定），
   *  用户在胶囊上显式选过人（agentMap 有本会话的键）时仍以显式选择优先。 */
  const lastTurnAgent = computed(() => {
    const list = messages()
    for (let i = list.length - 1; i >= 0; i--) {
      const m = list[i]
      if (m && m.role === 'ai' && m.agentId) return { id: m.agentId, name: m.agentName || '' }
    }
    return null
  })
  /** 本会话下一问的作答智能体（''=默认助手：后端解析为 isDefault 那一个，未设默认则走全局配置）。
   *  待选优先级：用户显式选的 > 本会话上一问的智能体 > 平台默认智能体。
   *  上一问的那位已不在清单里（被删/不再可见，且清单已加载）时不预填它——钉着已消失的人，
   *  每问一轮都要撞一次「智能体不可用」的降级条；回默认助手，与后端降级的去向一致。 */
  const currentAgentId = computed({
    get: () => {
      const memo = agentMap.value[currentSid()]
      if (memo !== undefined) return memo
      const last = lastTurnAgent.value
      if (last && last.id
          && (!agentList.value.length || agentList.value.some(a => a.id === last.id))) return last.id
      return defaultAgentId.value || ''
    },
    set: v => { agentMap.value = { ...agentMap.value, [currentSid()]: v || '' } }
  })
  const pickAgent = id => {
    onPick()
    agentMap.value = { ...agentMap.value, [currentSid()]: id || '' }
  }
  /** 当前生效的智能体名（空=默认助手；没有"锁定"这一说，换人立即生效） */
  const currentAgentName = computed(() => {
    const id = currentAgentId.value
    if (!id) return '默认助手'
    const a = agentList.value.find(x => x.id === id)
    if (a) return a.name
    // 智能体已删除/不再可见，或清单还没拉回来：回退最后一条回答上的名字快照，
    // 别把还挂在待选位上的那位冒充成「默认助手」（发出去后端会降级并明说）
    const last = lastTurnAgent.value
    if (last && last.id === id && last.name) return last.name
    return '默认助手'
  })
  /** 当前生效的智能体对象（仅用于 agent-pill 头像；默认助手 = null，不显示头像） */
  const currentAgent = computed(() => {
    const id = currentAgentId.value
    if (!id) return null
    return agentList.value.find(x => x.id === id) || null
  })
  /**
   * 归属徽标的「去重」判定：会话内首条助手消息、或与上一条助手消息归属不同时才标（同一个人全程一致不必重复）。
   * 是否允许显示由排障显示开关叠加判断（调用处 && debugDisplayVisible），本函数只负责去重，不读配置。
   */
  const showAgentTag = (m, i) => {
    if (m.role !== 'ai' || !m.agentName) return false
    const list = messages()
    for (let k = i - 1; k >= 0; k--) {
      const prev = list[k]
      if (prev.role === 'ai') return prev.agentName !== m.agentName
    }
    return true
  }
  const loadAgents = async () => {
    try {
      // 走 available 接口：问答用户可读的精简列表（管理端 /agent/list 仅管理员）
      const r = await listAgentsApi()
      agentList.value = (r && r.success && Array.isArray(r.data)) ? r.data : []
      // 登记 id→名称，供刷新后还原「本轮咨询了谁」的徽标。另拉一份含子智能体的名称表：
      // agentList 刻意剔除子智能体（它们不当问答角色），只登记它的话，被咨询的子智能体在徽标上
      // 会退化成一串裸 id。拉失败不影响问答，静默即可。
      remember(agentList.value)
      try {
        const cs = await listConsultableApi()
        if (cs && cs.success && Array.isArray(cs.data)) remember(cs.data)
      } catch (e) { /* 非关键路径 */ }
      // 优先默认助手；未设默认时用列表第一个，保证下拉总有可选项
      const def = agentList.value.find(a => a.isDefault === 1 || a.isDefault === true) || agentList.value[0]
      defaultAgentId.value = def ? def.id : ''
      // 刻意不往 agentMap 给当前会话补默认值：那一格代表「用户在本会话显式挑过」，优先级高于 lastTurnAgent，
      // 挂载时写种等于把「打开旧会话跟随上一位作答者」短路掉（没挑过也没上一问时 getter 自己回默认）
    } catch (e) { /* 接口不可用时静默：选择器回退为「默认（全局配置）」 */ }
  }
  return { agentList, agentMap, defaultAgentId, hasDefaultAgent, lastTurnAgent,
           currentAgentId, pickAgent, currentAgentName, currentAgent, showAgentTag, loadAgents }
}
