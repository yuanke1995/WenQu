// 「批量管理」勾选态的唯一实现：智能体 / MCP / 服务商 / 工作流 / 定时任务 / 技能六个运营页共用。
// 这五件套（batchMode、selected、allChecked、someChecked、toggleAll）若各页各写一份，
// 必然漂出「退出批量时忘了清空勾选」这类单页 bug，且从界面上看不出来。
//
// 纪律：页面只提供 selectableIds（当前筛选后可被删的非内置项），勾选判断、全选、退出清空都在这里，
// 避免某个页面改了半处（例如退出批量时忘了清空勾选）导致行为分叉。
import { computed, ref } from 'vue'

/**
 * @param selectableIds computed<Array<string|number>> 可勾选集合
 * @param keyOf (item) => string|number 选择器键（默认取 .id；技能这类以 来源+名称 为键的页面自己给）
 */
export const useBatchSelect = (selectableIds, { keyOf = item => item?.id } = {}) => {
  // 批量模式默认关闭：卡片不显示勾选框，点「批量管理」才进入（退出即清空勾选）
  const batchMode = ref(false)
  const selected = ref([])

  const selectableKeys = computed(() => (selectableIds.value || []).map(keyOf))

  const allChecked = computed(() =>
    selectableKeys.value.length > 0 && selectableKeys.value.every(k => selected.value.includes(k)))

  const someChecked = computed(() => selected.value.length > 0 && !allChecked.value)

  const isSelected = key => selected.value.includes(key)

  const toggleSelect = key => {
    if (selected.value.includes(key)) {
      selected.value = selected.value.filter(x => x !== key)
    } else {
      selected.value = [...selected.value, key]
    }
  }

  const toggleBatchMode = () => {
    batchMode.value = !batchMode.value
    if (!batchMode.value) selected.value = []
  }

  /** 显式退出（「取消」按钮 / 操作完成后收尾），与再次点「批量管理」等价 */
  const exitBatch = () => {
    batchMode.value = false
    selected.value = []
  }

  const toggleAll = () => {
    selected.value = allChecked.value ? [] : [...selectableKeys.value]
  }

  return {
    batchMode,
    selected,
    selectableKeys,
    allChecked,
    someChecked,
    isSelected,
    toggleSelect,
    toggleBatchMode,
    exitBatch,
    toggleAll
  }
}

/**
 * 批量操作结果的统一口径：全成功一条 message；有失败则弹明细（逐条「名称：原因」）。
 *
 * 口径来自原本 5 份逐字相同的 reportBatch：读 r.data，失败项展示 name/id + error。
 * 不合并会漂成「这个页面提示成功、那个页面静默吞掉失败」。
 *
 * @param r 后端返回（形如 { data: { succeeded: [], failed: [{ name, id, error }] } }）
 * @param verb 动作名，如「删除」「启用」
 * @param unit 量词，如「个智能体」
 * @param ui { message, Modal }（由调用方注入，composable 不去全局取 antd 实例）
 */
export const reportBatchResult = (r, verb, unit, { message, Modal }) => {
  const data = (r && r.data) || {}
  const okCount = (data.succeeded || []).length
  const failed = data.failed || []
  if (!failed.length) {
    message.success(`已${verb} ${okCount} ${unit}`)
    return
  }
  Modal.warning({
    title: `${verb}完成：成功 ${okCount} 个，失败 ${failed.length} 个`,
    content: failed.map(f => `「${f.name || f.id}」：${f.error}`).join('；'),
    okText: '知道了'
  })
}