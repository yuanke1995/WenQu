import { ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { listAgentVersions, rollbackAgent } from '../api'

/**
 * 智能体「配置历史版本」：每次保存落一版快照，可查 diff、一键回滚。
 * 从 AgentsPage.vue 搬出，逻辑逐字不变；对外导出名与原文件一致，模板无需改动。
 *
 * @param editingId 当前编辑中的智能体 id ref（版本归属）
 * @param agents    智能体列表 ref（回滚后找回当前条目）
 * @param reload    回滚成功后重拉列表（页面注入，避免 composable 反向依赖页面）
 * @param openEdit  回滚成功后把表单刷成回滚后的配置（页面注入）
 */
export function useAgentVersions ({ editingId, agents, reload, openEdit }) {
  const verOpen = ref(false)
  const verLoading = ref(false)
  const verList = ref([])
  // 展开的版本号集合（默认展开最新一版，正是"最近一次改了什么"）
  const verExpanded = ref([])
  const verRolling = ref(null)

  const openVersions = async () => {
    verOpen.value = true
    await loadVersions()
  }

  const loadVersions = async () => {
    if (!editingId.value) return
    verLoading.value = true
    try {
      const r = await listAgentVersions(editingId.value)
      if (r.success) {
        verList.value = r.data || []
        verExpanded.value = verList.value.length ? [verList.value[0].version] : []
      } else {
        verList.value = []
        message.error(r.msg || '版本列表加载失败')
      }
    } catch (e) {
      verList.value = []
      message.error(e.message || '版本列表加载失败')
    } finally {
      verLoading.value = false
    }
  }

  const toggleVer = version => {
    verExpanded.value = verExpanded.value.includes(version)
      ? verExpanded.value.filter(v => v !== version)
      : [...verExpanded.value, version]
  }

  const doRollback = v => {
    Modal.confirm({
      title: `回滚到 v${v.version}`,
      content: `将把 v${v.version} 的配置（名称/提示词/知识库范围/工具集等）重新应用到当前智能体，`
        + '并生成一条新的版本记录。历史版本不会被改写，本次回滚也可以再回滚。',
      okText: '确认回滚',
      cancelText: '取消',
      onOk: async () => {
        verRolling.value = v.version
        try {
          const r = await rollbackAgent(editingId.value, v.version)
          if (r.success) {
            message.success(`已回滚到 v${v.version}`)
            // 回滚改的是后端配置：把列表与表单都刷成回滚后的状态，避免表单里残留旧值被下次「保存」又写回去
            await reload()
            const a = agents.value.find(x => x.id === editingId.value)
            if (a) openEdit(a)
            await loadVersions()
          } else {
            message.error(r.msg || '回滚失败')
          }
        } catch (e) {
          message.error(e.message || '回滚失败')
        } finally {
          verRolling.value = null
        }
      }
    })
  }

  return {
    verOpen, verLoading, verList, verExpanded, verRolling,
    openVersions, toggleVer, doRollback
  }
}
