import { ref, computed, watch } from 'vue'
import { message } from 'ant-design-vue'
import { createWorkflowFromAgent } from '../api'
import { topoWindow, focusedGraph } from '../utils/topoLayout'
import { isSub, splitList } from './agentBasics'

/**
 * 智能体「委派编排视图」的全部状态与动作：列表承载规模（搜索/筛选/窗口化滚动），
 * 拓扑只画一位主智能体的链路；另含把已保存委派关系一键转工作流。
 * 从 AgentsPage.vue 搬出，逻辑逐字不变；对外导出名与原文件一致，模板无需改动。
 *
 * @param agents    智能体列表 ref
 * @param editingId 当前编辑中的智能体 id ref（转工作流前置校验用）
 */
export function useDelegationTopo ({ agents, editingId, route, router }) {
  const topoOpen = ref(false)
  const topoMode = ref('list')     // list=检索与概览；topo=看某一位主智能体的委派链路
  const topoQuery = ref('')
  const topoFilter = ref('all')
  const topoFocusId = ref('')
  const topoHover = ref('')        // 悬停节点 key（'m'=主节点，其余为子节点 key）
  const topoListEl = ref(null)
  const topoScrollTop = ref(0)
  const TOPO = { pad: 16, rowH: 64, rowGap: 14, nodeW: 216, gapG: 64, subW: 248 }

  const topoMains = computed(() => agents.value.filter(a => !isSub(a)))
  const topoSubs = computed(() => agents.value.filter(isSub))

  /** 一行 = 一位主智能体：解析出来的子智能体（按清单顺序）与悬空 id（失效引用） */
  const topoRows = computed(() => {
    const subById = new Map(topoSubs.value.map(a => [String(a.id), a]))
    return topoMains.value.map(a => {
      const declared = splitList(a.subAgentIds)
      const subs = []
      const missingIds = []
      for (const id of declared) {
        const s = subById.get(String(id))
        if (s) subs.push(s)
        else missingIds.push(String(id))
      }
      return { agent: a, configured: declared.length > 0, subs, missingIds }
    })
  })

  const topoParentCount = computed(() => {
    const map = {}
    for (const r of topoRows.value) for (const s of r.subs) map[String(s.id)] = (map[String(s.id)] || 0) + 1
    return map
  })

  const topoSummary = computed(() => {
    let missing = 0
    for (const r of topoRows.value) missing += r.missingIds.length
    const parents = new Set(Object.keys(topoParentCount.value))
    const orphans = topoSubs.value.filter(s => !parents.has(String(s.id)))
    return {
      mains: topoRows.value.length,
      subs: topoSubs.value.length,
      orphans: orphans.length,
      orphanNames: orphans.map(s => s.name || s.id),
      missing,
      defaultMains: topoRows.value.filter(r => !r.configured).length
    }
  })
  const topoOrphanNames = computed(() => {
    const names = topoSummary.value.orphanNames
    return names.length > 4 ? `${names.slice(0, 4).join('、')} 等 ${names.length} 个` : names.join('、')
  })
  const topoOrphanTitle = computed(() => (topoSummary.value.orphanNames.length
    ? `没进入任何主智能体委派清单的子智能体：${topoSummary.value.orphanNames.join('、')}` : ''))

  const topoFilters = computed(() => {
    const rows = topoRows.value
    return [
      { key: 'all', label: '全部', count: rows.length },
      { key: 'configured', label: '已配置', count: rows.filter(r => r.configured).length },
      { key: 'default', label: '默认不限', count: rows.filter(r => !r.configured).length },
      { key: 'dangling', label: '有失效引用', count: rows.filter(r => r.missingIds.length).length }
    ]
  })

  const topoFiltered = computed(() => {
    const q = topoQuery.value.toLowerCase()
    return topoRows.value.filter(r => {
      if (topoFilter.value === 'configured' && !r.configured) return false
      if (topoFilter.value === 'default' && r.configured) return false
      if (topoFilter.value === 'dangling' && !r.missingIds.length) return false
      if (!q) return true
      return String(r.agent.name || '').toLowerCase().includes(q)
        || String(r.agent.description || '').toLowerCase().includes(q)
    })
  })

  /**
   * 窗口化滚动：几百个主智能体时只渲染可视区附近的行（算式与行高在 utils/topoLayout 里单测钉住）。
   */
  const onTopoScroll = e => { topoScrollTop.value = e.target.scrollTop }
  const topoWin = computed(() => {
    const rows = topoFiltered.value
    const w = topoWindow(rows.length, topoScrollTop.value)
    return { ...w, rows: rows.slice(w.start, w.end) }
  })
  watch([topoQuery, topoFilter], () => {
    topoScrollTop.value = 0
    if (topoListEl.value) topoListEl.value.scrollTop = 0
  })

  const topoFocus = computed(() => topoRows.value.find(r => r.agent.id === topoFocusId.value) || null)
  // 列表刷新后聚焦对象可能已不存在（被删/改了用途）：回列表，别留一张空弹窗
  watch(topoFocus, v => { if (!v && topoMode.value === 'topo') topoMode.value = 'list' })
  const topoMainOptions = computed(() => topoMains.value.map(a => ({ value: a.id, label: a.name || a.id })))

  /**
   * 打开编排视图。带智能体进 = 直接看它的链路：
   * 主智能体看自己派出去的，子智能体看委派它的第一位主智能体（卡片上「被谁委派」那个入口）。
   */
  const openTopo = a => {
    topoQuery.value = ''
    topoFilter.value = 'all'
    topoScrollTop.value = 0
    topoHover.value = ''
    let focus = ''
    if (a) {
      if (isSub(a)) {
        const parent = topoRows.value.find(r => r.subs.some(s => String(s.id) === String(a.id)))
        focus = parent ? parent.agent.id : ''
      } else {
        focus = String(a.id)
      }
    }
    topoFocusId.value = focus
    topoMode.value = focus ? 'topo' : 'list'
    topoOpen.value = true
  }
  const focusTopo = a => {
    topoFocusId.value = String(a.id)
    topoMode.value = 'topo'
    topoHover.value = ''
  }

  /** 聚焦拓扑布局：最多两列（一列 8 个），画布高度只与这一位的委派数有关 */
  const topoGraph = computed(() => {
    const r = topoFocus.value
    const items = r ? [
      ...r.subs.map(s => ({ key: String(s.id), agent: s, miss: false })),
      ...r.missingIds.map(id => ({ key: 'x' + id, id, agent: null, miss: true }))
    ] : []
    return focusedGraph(items, TOPO)
  })
  const subMeta = a => {
    const n = topoParentCount.value[String(a.id)] || 0
    return n ? `被 ${n} 个主智能体委派` : '暂无人委派'
  }

  /**
   * 按显示宽度截断：全角 = 1 字宽、半角 ≈ 0.55。
   * 纯字数截断不可靠——中文按字号折算一行只装得下十几字，超宽文本会溢出节点矩形、
   * 被 SVG 视口右缘裁掉（首版"显示不全"的根因）。maxFull = 该行可容纳的全角字数。
   */
  const trunc = (s, maxFull) => {
    const t = String(s == null ? '' : s)
    let w = 0
    for (let i = 0; i < t.length; i++) {
      w += t.charCodeAt(i) > 255 ? 1 : 0.55
      if (w > maxFull) return t.slice(0, i) + '…'
    }
    return t
  }
  // 每行可容纳的全角字数（节点宽 - 左右内边距 12×2，按字号折算：名称 13px / 描述与元信息 11px）
  const MAIN_NAME = Math.floor((TOPO.nodeW - 24) / 13)
  const MAIN_LINE = Math.floor((TOPO.nodeW - 24) / 11)
  const SUB_NAME = Math.floor((TOPO.subW - 24) / 13)
  const SUB_LINE = Math.floor((TOPO.subW - 24) / 11)

  // ---- 委派编排一键转工作流 ----
  const toWfLoading = ref(false)
  /**
   * 把该智能体已保存的委派编排（subAgentIds 并行委派）转成工作流：
   * 后端按库里的委派关系生成「start 扇出 subagent → template 聚合 → llm 总结 → end」
   * 并创建草稿工作流；成功后跳到「工作流」Tab，列表首行（更新时间倒序）就是它。
   */
  const toWorkflow = async () => {
    if (!editingId.value) {
      message.warning('请先保存智能体（委派关系以已保存的为准），再转换工作流')
      return
    }
    toWfLoading.value = true
    try {
      const r = await createWorkflowFromAgent(editingId.value)
      if (r && r.success) {
        message.success(`已按已保存的委派关系创建工作流「${r.data.name}」，可在画布继续调整后发布`)
        router.replace({ query: { ...route.query, tab: 'workflow' } })
      } else {
        message.error((r && r.msg) || '转换失败')
      }
    } catch (e) {
      message.error('转换失败：' + (e.message || ''))
    } finally {
      toWfLoading.value = false
    }
  }

  /** 悬停高亮：主节点悬停 = 整条链路；子节点悬停 = 它那一条边（其余淡化） */
  const edgeCls = e => {
    const h = topoHover.value
    if (!h) return ''
    if (h === 'm') return 'on'
    return h === e.key ? 'on' : 'dim'
  }
  const nodeCls = key => {
    const h = topoHover.value
    if (!h || h === 'm' || h === key) return ''
    return 'dim'
  }

  return {
    topoOpen, topoMode, topoQuery, topoFilter, topoFocusId, topoHover, topoListEl, topoScrollTop, TOPO,
    topoMains, topoSubs, topoRows, topoParentCount, topoSummary, topoOrphanNames, topoOrphanTitle,
    topoFilters, topoFiltered, onTopoScroll, topoWin, topoFocus, topoMainOptions, openTopo, focusTopo,
    topoGraph, subMeta, trunc, MAIN_NAME, MAIN_LINE, SUB_NAME, SUB_LINE, toWfLoading, toWorkflow, edgeCls, nodeCls
  }
}
