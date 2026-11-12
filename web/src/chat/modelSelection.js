// 模型选择状态：会话级覆盖 > 个人默认（智能体不绑模型），以及生效模型的展示/可用性口径。
// 模型引用 → 展示信息的 modelIndex 也在这里（由模型库加载后回填）。
import { ref, computed } from 'vue'

export const createModelSelection = ({ currentSid, sessionList, updateSessionApi, notifyError, labelOf }) => {
  // 会话ID → 会话行上的模型引用（按会话记忆；空=跟随）。
  // 这份现在有两处来路：聊天页手动切换即时 PUT 落库；后端每轮把实际生效的引用补记一份——所以刷新/
  // 隔天打开旧会话，右下角回显的是这个会话上一轮真用的模型，不是"现在的"个人默认
  const modelMap = ref({})
  // 会话ID → 那个引用此刻还能不能用（后端判据随会话列表下发：停用供应商、把模型从列表移除
  // 都不会让会话里钉着的历史值自己消失，界面必须报出来，而不是等回答时撞一句网关错）
  const modelIssueMap = ref({})
  const currentOverrideModel = computed({
    get: () => modelMap.value[currentSid()] || '',
    set: v => {
      const val = v || ''
      const sid = currentSid()
      modelMap.value = { ...modelMap.value, [sid]: val }
      // 切换即落库（会话级模型覆盖，刷新/换端后仍保留；清除=空串回个人默认）。
      // 失败要提醒：静默吞掉的话用户以为已记住，刷新后选择丢失无从排查。
      // 会话尚无 ID（极端时序）只记本地，等首问随载荷落库
      if (sid) updateSessionApi(sid, val).catch(e => notifyError(`会话模型保存失败：${e?.message || '请稍后重试'}`))
    }
  })
  /** 会话列表 → modelMap 回填（seed-once：本地已有的选择不动——切换已即时落库，避免慢响应竞态把 UI 回退） */
  const seedSessionModels = () => {
    const patch = {}
    const issues = {}
    for (const s of sessionList()) {
      if (!s || !s.id) continue
      if (!(s.id in modelMap.value)) patch[s.id] = s.model || ''
      // 可用性每次重算：它随管理员停启用供应商/模型而变，不像选择那样归本地
      issues[s.id] = s.modelIssue || ''
    }
    if (Object.keys(patch).length) modelMap.value = { ...modelMap.value, ...patch }
    modelIssueMap.value = { ...modelIssueMap.value, ...issues }
  }
  const userDefaultModel = ref('')       // 个人默认模型（个人设置，后端 /user/preference）
  const userDefaultModelIssue = ref('')  // 它此刻能不能用（同样由后端判，空=可用/未设置）
  const modelIndex = ref({})             // 引用 → { displayName, providerName, icon }（展示映射）

  /** 实际生效的模型（引用或遗留名）：会话覆盖 > 个人默认（智能体不绑模型、无全局兜底；空=未指定，发送时引导选择） */
  const effectiveModel = computed(() => currentOverrideModel.value || userDefaultModel.value)
  const effectiveModelLabel = computed(() => labelOf(effectiveModel.value))
  const effectiveModelIcon = computed(() => {
    const info = modelIndex.value[effectiveModel.value]
    return info ? info.icon : ''
  })
  const effectiveModelProvider = computed(() => {
    const info = modelIndex.value[effectiveModel.value]
    return info ? info.providerName : ''
  })
  /** 生效模型来源（状态栏打标）：会话指定 / 个人默认；都没有则不显示标签 */
  const modelSourceLabel = computed(() => {
    if (currentOverrideModel.value) return '会话指定'
    if (userDefaultModel.value) return '个人默认'
    return ''
  })
  /**
   * 生效模型此刻不能用的原因（空=能用）。报的是「这一轮根本发不出去」的那一份：
   * 会话钉着失效的就说会话的，跟随个人默认时就说默认的。
   * 判定归后端（要看供应商与模型登记的启停状态），前端只负责把它摆到选择器上。
   */
  const effectiveModelIssue = computed(() => {
    if (currentOverrideModel.value) return modelIssueMap.value[currentSid()] || ''
    return userDefaultModel.value ? userDefaultModelIssue.value : ''
  })
  return { modelMap, modelIssueMap, currentOverrideModel, seedSessionModels,
           userDefaultModel, userDefaultModelIssue, modelIndex,
           effectiveModel, effectiveModelLabel, effectiveModelIcon, effectiveModelProvider,
           modelSourceLabel, effectiveModelIssue }
}
