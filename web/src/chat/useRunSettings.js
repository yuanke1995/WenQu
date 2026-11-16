import { computed } from 'vue'

/**
 * ChatPage 顶部「运行设置」卡片的读数与动作：以当前显式选择的模型为准，
 * 跟随默认时用生效模型；以及容量圆环旁的百分比、本轮占用、检索范围。
 * 从 ChatPage.vue 搬出，逻辑逐字不变。
 *
 * 所有外部值用 getter 注入（引擎值可能是惰性 getter），避免在 setup 期间取值触发 TDZ。
 */
export function useRunSettings ({
  currentOverrideModel, effectiveModel, effectiveModelLabel, userDefaultModel,
  modelIndex, pendingMentions, ctxCapData,
  modelLabelOf, thinkCapsOf, levelOptionsOf, currentLevelOf, fmtWindow, fmtTokens,
  setUserPreference, message
}) {
  const runSettingsModel = computed(() => currentOverrideModel.value || effectiveModel.value)
  const runSettingsModelLabel = computed(() => modelLabelOf(runSettingsModel.value) || effectiveModelLabel.value || '当前模型')
  const runSettingsCaps = computed(() => thinkCapsOf(runSettingsModel.value))
  const runSettingsLevels = computed(() => levelOptionsOf(runSettingsModel.value))
  const runSettingsLevel = computed(() => currentLevelOf(runSettingsModel.value))
  const runSettingsHint = computed(() => runSettingsCaps.value.locked
    ? '该模型始终思考，强度由管理员在模型库登记'
    : '强度越高，思考越深入，回答耗时相应增加')
  const runSettingsWindow = computed(() => {
    const info = modelIndex.value[runSettingsModel.value]
    return info && info.contextWindow ? fmtWindow(info.contextWindow) : ''
  })
  const isRunModelDefault = computed(() => !!runSettingsModel.value && runSettingsModel.value === userDefaultModel.value)
  const setRunModelDefault = async () => {
    const ref = runSettingsModel.value
    if (!ref) return
    try {
      await setUserPreference({ defaultModel: ref })
      userDefaultModel.value = ref
      message.success('已设为默认模型')
    } catch (e) {
      message.error((e && e.message) || '设置默认模型失败')
    }
  }
  // 输入区容量圆环旁的那颗百分比：与圆环同源（ctxCapData 的实测 used/window），没有真数就不显示
  const ctxPctText = computed(() => {
    const used = Number(ctxCapData.value && ctxCapData.value.used) || 0
    const win = Number(ctxCapData.value && ctxCapData.value.window) || 0
    if (!used || !win) return ''
    const pct = (used / win) * 100
    return pct > 0 && pct < 1 ? '<1%' : Math.round(pct * 10) / 10 + '%'
  })
  // 运行设置里的「本轮占用」只对生效模型成立：选了另一个模型时，本轮的 tokens 不是它的
  const runSettingsUsage = computed(() => {
    const used = Number(ctxCapData.value && ctxCapData.value.used) || 0
    if (!used || runSettingsModel.value !== effectiveModel.value) return ''
    return fmtTokens(used) + (ctxPctText.value ? ' · ' + ctxPctText.value : '')
  })
  // 运行设置面板里的检索范围：只报本轮显式 @ 的知识库；没有就是按智能体默认范围
  const kbScopeText = computed(() => {
    const kbs = (pendingMentions.value || []).filter(x => x.type === 'kb')
    return kbs.length ? kbs.map(x => x.name || x.id).join('、') : '按智能体默认范围'
  })

  return {
    runSettingsModel, runSettingsModelLabel, runSettingsCaps, runSettingsLevels,
    runSettingsLevel, runSettingsHint, runSettingsWindow, isRunModelDefault,
    setRunModelDefault, ctxPctText, runSettingsUsage, kbScopeText
  }
}
