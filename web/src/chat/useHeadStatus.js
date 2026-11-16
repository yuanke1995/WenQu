import { computed, ref } from 'vue'

/**
 * ChatPage 顶栏状态胶囊 + 右栏分段计数：只读最后一条 AI 消息算过程步，
 * 不另起一套判据。从 ChatPage.vue 搬出，逻辑逐字不变。
 *
 * @param processMsg 最后一条 AI 消息 getter
 * @param roundSteps 过程步投影函数
 * @param panelOpen/mPanelOpen/isNarrow 右栏开合与窄屏判定 getter
 * @param togglePanel 开合右栏动作
 * @param deliveryPackages/sandboxCount/procs/sandboxErr/sandboxLoading/procCardVisible/messages 右栏各页签计数源
 */
export function useHeadStatus ({
  processMsg, roundSteps, panelOpen, mPanelOpen, isNarrow, togglePanel,
  deliveryPackages, sandboxCount, procs, sandboxErr, sandboxLoading, procCardVisible, messages
}) {
  // processMsg / panelOpen / mPanelOpen / isNarrow 用 getter 注入：它们有的定义在 useChatChrome 之后，
  // getter 延迟到调用时才解引用，避开 setup 期的 TDZ。
  const processSteps = computed(() => (processMsg() ? roundSteps(processMsg()) : []))
  const processFailCount = computed(() => processSteps.value.filter(t => t.status === 'error').length)
  const headStatusClass = computed(() => ({
    bad: processFailCount.value > 0,
    run: !!(processMsg() && processMsg().loading),
    on: panelOpen()
  }))
  const headStatusText = computed(() => {
    if (processFailCount.value) return `本轮 ${processFailCount.value} 步失败`
    if (processMsg() && processMsg().loading) return '正在执行'
    return '状态'
  })
  const headStatusTitle = computed(() => {
    if (processFailCount.value) return `本轮有 ${processFailCount.value} 步失败，点开右栏看过程`
    if (processMsg() && processMsg().loading) return '本轮正在执行，点开右栏看过程'
    return panelOpen() ? '收起状态栏' : '展开状态栏'
  })
  // 右栏分段与计数：过程 / 来源 / 文件各一份，失败数只压在过程页签上
  const railTab = ref('process')
  const fileTabCount = computed(() => (deliveryPackages.value?.length || 0)
    + (Number(sandboxCount.value) || 0) + (procs.value?.length || 0))
  // 文件页签的空态不能只看计数：读取失败/正在读时 count 可能是 0，但下面确实有错误卡要显示
  const filesTabEmpty = computed(() => !fileTabCount.value && !sandboxErr.value && !sandboxLoading.value && !procCardVisible.value)
  // 最后一条 AI 消息的下标：右栏「重新生成本轮」要按这条消息重跑整轮
  const processMsgIndex = computed(() => {
    const list = messages.value || []
    for (let i = list.length - 1; i >= 0; i--) if (list[i].role === 'ai') return i
    return -1
  })

  // 顶栏状态胶囊：先切到过程页签再开栏；已经停在过程页签时再点才收起
  const headStatusClick = () => {
    const openNow = isNarrow() ? mPanelOpen() : panelOpen()
    if (openNow && railTab.value === 'process') { togglePanel(); return }
    railTab.value = 'process'
    if (!openNow) togglePanel()
  }

  return {
    processSteps, processFailCount, headStatusClass, headStatusText, headStatusTitle,
    railTab, fileTabCount, filesTabEmpty, processMsgIndex, headStatusClick
  }
}
