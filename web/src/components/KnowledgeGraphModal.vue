<template>
  <a-modal :open="open" @update:open="v => emit('update:open', v)"
           :title="`知识图谱 · ${kb?.name || ''}`" :footer="null" width="940px">
    <!-- 工具栏三段式：左=视图切换 · 中=数据状态 · 右=搜索 + 动作区（构建/清空/刷新） -->
    <div class="graph-toolbar">
      <a-segmented v-model:value="graphView" size="small" :options="GRAPH_VIEWS" @change="onGraphViewChange" />
      <span class="kb-hint graph-stat">
        实体 {{ graphInfo.entities || 0 }} · 三元组 {{ graphInfo.triples || 0 }}
        <template v-if="graphInfo.building">（失败 {{ graphInfo.failed || 0 }}）</template>
      </span>
      <div class="graph-toolbar-actions">
        <!-- §5 按实体搜索定位：命中在当前视图内 → 高亮并打开三元组面板；不在 → 聚焦其邻域子图 -->
        <a-auto-complete v-if="graphView === 'graph'" v-model:value="searchQ" :options="searchOpts"
                         style="width: 210px" size="small" placeholder="搜索实体定位…"
                         :disabled="!graphInfo.triples" @search="onSearchInput" @select="onSearchPick">
          <template #option="{ label, mention }">
            <div class="gs-opt">
              <span class="gs-opt-name">{{ label }}</span>
              <span class="gs-opt-meta">提及 {{ mention }}</span>
            </div>
          </template>
        </a-auto-complete>
        <a-button size="small" :loading="graphBuilding" :disabled="graphInfo.building" @click="doBuild">
          {{ graphInfo.building ? `构建中 ${graphInfo.done || 0}/${graphInfo.total || 0}` : '构建图谱（存量回溯）' }}
        </a-button>
        <a-popconfirm title="清空该库全部图谱数据？（三元组/实体/抽取记录全删，可重新构建）" ok-text="清空" cancel-text="取消" @confirm="doClearGraph">
          <a-button size="small" danger>清空图谱</a-button>
        </a-popconfirm>
        <a-tooltip title="刷新">
          <a-button size="small" @click="refreshGraph">
            <template #icon><reload-outlined /></template>
          </a-button>
        </a-tooltip>
      </div>
    </div>

    <!-- 图视图：力导向图（节点=实体，大小按关联度；边=关系谓词，聚合重复三元组计数） -->
    <div v-show="graphView === 'graph'" class="graph-wrap">
      <div ref="graphChartEl" class="graph-canvas"></div>
      <div v-if="!graphInfo.triples" class="graph-empty">还没有三元组——先点「构建图谱」。</div>
      <div v-else-if="focused" class="graph-focus-bar">
        已聚焦「{{ focused.name }}」的邻域（它不在默认全景视图内）
        <button class="app-link-btn" style="margin-left:auto" @click="backToOverview">返回全景</button>
      </div>
      <!-- §5 点实体看三元组：面板数据来自服务端实体详情（完整关系 + 来源文档 + 源块定位） -->
      <div v-if="selectedDetail" class="graph-panel graph-panel-col">
        <div class="gp-head">
          <b>{{ selectedDetail.entity.name }}</b>
          <span class="gp-sub">{{ selectedDetail.degree }} 条关系<template v-if="aliasText(selectedDetail)"> · 又名 {{ aliasText(selectedDetail) }}</template></span>
          <button class="app-link-btn" style="margin-left:auto" @click="closeEntityPanel">收起</button>
        </div>
        <a-spin :spinning="entityLoading" size="small">
          <div class="gp-rows">
            <div v-for="r in selectedDetail.triples" :key="r.id" class="gp-row">
              <span class="gp-rel">{{ r.subject }} —【{{ r.predicate }}】→ {{ r.object }}</span>
              <span class="gp-doc" :title="r.doc">{{ r.doc || '—' }}</span>
              <button v-if="r.chunkId" class="app-link-btn gp-chunk-btn" @click="openChunk(r)">源块</button>
            </div>
            <div v-if="!selectedDetail.triples.length" class="graph-note">该实体暂无关系</div>
          </div>
        </a-spin>
      </div>
      <div v-else-if="graphInfo.triples" class="graph-note">
        {{ truncated
          ? `图谱较大，展示关联度最高的前 ${viewLimit} 个实体；其余实体用右上角搜索定位。`
          : '点一个节点会聚焦与它相连的整条关系链（其余淡化），下方列出三元组与来源块；拖动节点/空白平移、滚轮缩放。' }}
      </div>
    </div>

    <!-- 列表视图：逐条核对（带来源文档） -->
    <div v-show="graphView === 'list'">
      <a-table :data-source="triples" :columns="tripleCols" size="small" row-key="id"
               :loading="triplesLoading" :pagination="triplePagination"
               :locale="{ emptyText: '还没有三元组（先构建图谱，或确认已开启开关并配置抽取模型）' }"
               @change="onTripleTableChange" />
    </div>

    <!-- §5 源知识块弹层：溯源闭环的最后一跳（三元组 → 块原文 + 所属文档） -->
    <a-modal v-model:open="chunkOpen" :title="chunkData ? `源知识块 · ${chunkData.doc || ''}` : '源知识块'"
             :footer="null" width="680px">
      <div v-if="chunkData" class="chunk-view">
        <div class="chunk-meta">
          <span class="chunk-doc" :title="chunkData.doc">{{ chunkData.doc || '—' }}</span>
          <a-tag v-if="chunkData.chunkIndex != null">第 {{ chunkData.chunkIndex + 1 }} 块</a-tag>
          <span v-if="chunkData.titlePath" class="chunk-path" :title="chunkData.titlePath">{{ chunkData.titlePath }}</span>
        </div>
        <pre class="chunk-content">{{ chunkData.content }}</pre>
        <div v-if="chunkData.truncated" class="graph-note">内容过长，仅展示前部分</div>
      </div>
    </a-modal>
  </a-modal>
</template>

<script setup>
// 图谱弹窗（§5 会话图谱可视化）：库列表页与库详情页共用。
// 数据分层：全景走 /view 聚合接口（度数/关系在库内 GROUP BY，前端不再拿 500 条采样自行统计）；
// 点实体走 /entity 完整三元组（带来源文档与 chunkId）；搜索命中不在视图内 → /neighbor 邻域子图整体重渲染。
// echarts 交互铁律沿用：init + 一次 setOption 之后绝不再 setOption（会重建力模拟与漫游坐标系），
// 高亮/定位用 dispatchAction（运行时动作，不动模拟），聚焦切换=整体 dispose 重建。
import { ref, computed, watch, nextTick, onBeforeUnmount } from 'vue'
import { message } from 'ant-design-vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import * as echarts from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
// 深路径取内部 ecData 写入器：force graph 的 emphasis.focus 只认 'adjacency'（trajectory 仅桑基图支持），
// 想聚焦「整条关系链」只能自算索引集后写回元素（GraphView 内部给节点写 focus 用的就是它）
import { getECData } from 'echarts/lib/util/innerStore.js'
echarts.use([GraphChart, TooltipComponent, CanvasRenderer])
import {
  graphBuild, graphStatus, graphTriples, graphClear,
  graphViewData, graphEntityDetail, graphNeighbor, graphSearchEntity, graphChunk
} from '../api'

const props = defineProps({
  open: { type: Boolean, default: false },
  /** 知识库对象（至少 id/name；builtin 仅用于按钮显隐口径，由入口决定） */
  kb: { type: Object, default: null }
})
const emit = defineEmits(['update:open'])

const graphInfo = ref({})
const graphBuilding = ref(false)
const triples = ref([])
const triplesLoading = ref(false)
const triplePage = ref(1)
const tripleTotal = ref(0)
const tripleCols = [
  { title: '主体', dataIndex: 'subject', key: 'subject', width: 150, ellipsis: true },
  { title: '关系', dataIndex: 'predicate', key: 'predicate', width: 90 },
  // 客体是最长的一列，吃掉剩余弹性宽度；来源文档基本是同名文件，固定宽即可
  { title: '客体', dataIndex: 'object', key: 'object', ellipsis: true },
  { title: '来源文档', dataIndex: 'doc', key: 'doc', width: 180, ellipsis: true }
]
const triplePagination = computed(() => ({
  current: triplePage.value, total: tripleTotal.value, pageSize: 20, showSizeChanger: false
}))

const kbId = () => props.kb?.id

const refreshGraph = async () => {
  if (!kbId()) return
  try {
    const st = await graphStatus(kbId())
    if (st.success) graphInfo.value = st.data || {}
  } catch (e) { message.error(e.message || '图谱状态加载失败') }
  if (graphView.value === 'graph') nextTick(renderGraphChart)
  else loadTripleList()
}

// 列表页码变化只在列表视图发生；图视图不消费这份数据
const onTripleTableChange = pg => {
  triplePage.value = pg.current
  loadTripleList()
}

const loadTripleList = async () => {
  if (!kbId()) return
  triplesLoading.value = true
  try {
    const r = await graphTriples(kbId(), triplePage.value, 20)
    if (r.success) {
      triples.value = r.data?.rows || []
      tripleTotal.value = r.data?.total || 0
    }
  } catch (e) { message.error(e.message || '三元组加载失败') }
  finally { triplesLoading.value = false }
}

// ==================== 视图切换 ====================
const graphView = ref('graph')
const GRAPH_VIEWS = [
  { label: '图视图', value: 'graph' },
  { label: '列表', value: 'list' }
]
const onGraphViewChange = () => {
  if (graphView.value === 'graph') nextTick(renderGraphChart)
  else if (!triples.value.length) loadTripleList()
}

// ==================== 图视图（echarts 力导向图） ====================
const graphChartEl = ref(null)
let chartInstance = null
let nameToId = new Map()      // echarts 节点按 name 引用；点击时换回实体 id
const viewLimit = ref(300)
const truncated = ref(false)
const overviewData = ref(null)   // 全景数据缓存（聚焦模式「返回全景」用，免重拉）
const focused = ref(null)        // {id, name}：邻域聚焦模式
const VIEW_LIMIT = 300
let chainFocusSets = null        // 节点数据下标 → 所在连通链的 {node:[], edge:[]} 下标集（聚焦范围）

const closeEntityPanel = () => { selectedDetail.value = null; clearNodeFocus() }

const aliasText = detail => (detail.entity.aliases || []).join('、')

async function renderGraphChart() {
  if (!kbId() || !graphChartEl.value) return
  let data = null
  try {
    const r = await graphViewData(kbId(), VIEW_LIMIT)
    if (r.success) data = r.data || {}
  } catch (e) { message.error(e.message || '图谱数据加载失败'); return }
  overviewData.value = data
  focused.value = null
  renderFromData(data, null)
}

/** 邻域聚焦（§5 搜索定位兜底）：以实体为中心整体重渲染，与全景同一渲染函数（nodes/edges 同构） */
async function focusEntity(ent) {
  if (!kbId()) return
  try {
    const r = await graphNeighbor(kbId(), ent.id)
    if (!r.success) { message.error(r.msg || '邻域加载失败'); return }
    focused.value = { id: ent.id, name: ent.name || ent.id }
    renderFromData(r.data, focused.value)
  } catch (e) { message.error(e.message || '邻域加载失败') }
}

const backToOverview = () => {
  focused.value = null
  selectedDetail.value = null
  renderFromData(overviewData.value, null)
}

/** 一次性 setOption（此后只 dispatchAction，不再 setOption——会重建力模拟与漫游坐标系） */
function renderFromData(data, focus) {
  disposeChart()
  selectedDetail.value = null
  nameToId = new Map()
  if (!data || !data.nodes?.length || !graphChartEl.value) return
  const cssVar = n => (getComputedStyle(document.documentElement).getPropertyValue(n) || '').trim()
  const nodes = data.nodes
  const edges = data.edges || []
  const idToName = new Map()
  nodes.forEach(n => { nameToId.set(n.name, n.id); idToName.set(n.id, n.name) })
  const maxDegree = Math.max(1, ...nodes.map(n => n.degree || 1))
  // 节点**不能带 id 字段**：echarts graph 在节点有 id 时以 id 作为 links 的匹配键，
  // 下面按 name 引用的连线会被整体静默丢弃（表现为「只有点没有线」，实测踩过）。
  // 实体 id 的映射走 nameToId（点击换 id 查详情），不进 echarts。
  const chartNodes = nodes.map(n => {
    const item = {
      name: n.name,
      symbolSize: focus && focus.id === n.id ? 46 : Math.min(44, 12 + Math.round(((n.degree || 1) / maxDegree) * 28)),
      label: { show: true, fontSize: 10 },
      // adjacency 只作为兜底范围（链索引集由 applyChainFocus 覆写）；悬停节点才触发聚焦，悬停连线不淡化全图
      emphasis: { focus: 'adjacency', label: { fontWeight: 'bold' } }
    }
    if (focus && focus.id === n.id) item.itemStyle = { color: '#e6a23c' }
    return item
  })
  const chartLinks = edges.map(e => {
    const s = idToName.get(e.source)
    const t = idToName.get(e.target)
    if (!s || !t) return null
    return {
      source: s, target: t,
      value: (e.count || 1) > 1 ? `${e.predicate} ×${e.count}` : e.predicate,
      predicate: e.predicate
    }
  }).filter(Boolean)
  // 整条关系链：按实体无向遍历求连通分量，记每个节点的分量内 点/线 数据下标
  chainFocusSets = buildChainFocusSets(nodes, edges, chartLinks)
  viewLimit.value = data.limit || VIEW_LIMIT
  truncated.value = !!data.truncated
  // echarts 走 canvas 渲染，不解析 CSS 变量——读变量当前实际值（随亮暗主题），直接写 var(--app-*) 会静默失效
  chartInstance = echarts.init(graphChartEl.value)
  // 漫游只覆盖「初始布局矩形」：向四周外扩 1600px，连续平移不会拖出可及范围
  const wrapRect = graphChartEl.value.getBoundingClientRect()
  const pad = 1600
  chartInstance.setOption({
    backgroundColor: 'transparent',
    tooltip: {
      formatter: p => p.dataType === 'edge'
        ? `<b>${p.data.source}</b> —【${p.data.predicate}】→ <b>${p.data.target}</b><br/><span style="color:#999">点节点查看三元组与来源块</span>`
        : `<b>${p.name}</b><br/><span style="color:#999">点击查看该实体的三元组与来源块</span>`
    },
    series: [{
      type: 'graph', layout: 'force', roam: true, draggable: true, cursor: 'grab',
      left: -pad, top: -pad, width: wrapRect.width + pad * 2, height: wrapRect.height + pad * 2,
      force: { repulsion: 320, edgeLength: [60, 150], gravity: 0.2, layoutAnimation: true },
      data: chartNodes,
      links: chartLinks,
      label: { color: cssVar('--app-text'), position: 'right' },
      lineStyle: { color: cssVar('--app-text3'), curveness: 0.05 },
      edgeLabel: { show: true, fontSize: 10, color: cssVar('--app-text2'), formatter: '{c}' },
      edgeSymbol: ['none', 'arrow'], edgeSymbolSize: 7,
      itemStyle: { color: '#4f6ef2' }
    }]
  }, true)
  applyChainFocus()
  // 点节点 = 拉服务端实体详情（完整三元组 + 溯源）+ 聚焦该节点所在的整条关系链
  chartInstance.on('click', p => {
    if (p.dataType === 'node') {
      const id = nameToId.get(p.name)
      if (id) {
        loadEntityDetail(id)
        focusNodeVisual(p.name)
      }
    }
  })
  if (focus) {
    // 聚焦实体高亮 + 居中提示（dispatchAction 是运行时动作，不重建力模拟）
    focusNodeVisual(focus.name)
    chartInstance.dispatchAction({ type: 'showTip', seriesIndex: 0, name: focus.name })
  }
  // 鼠标离开元素/画布后 echarts 会清掉 blur，按选中点重聚焦（zr 随 dispose 一起销毁，不需要解绑）
  chartInstance.getZr().on('mouseout', refocusChain)
  chartInstance.getZr().on('globalout', refocusChain)
}

/** 连通分量 → 每个节点的聚焦索引集（{node:[], edge:[]} 均为当前渲染内的数据下标），供 blur 机制「保留不淡化」用 */
function buildChainFocusSets(nodes, edges, chartLinks) {
  const idIndex = new Map(nodes.map((n, i) => [n.id, i]))
  const adj = new Map(nodes.map(n => [n.id, []]))
  edges.forEach(e => {
    if (adj.has(e.source) && adj.has(e.target)) {
      adj.get(e.source).push(e.target)
      adj.get(e.target).push(e.source)
    }
  })
  const compOfId = new Map()
  const compNodes = []
  const compEdges = []
  nodes.forEach(n => {
    if (compOfId.has(n.id)) return
    const c = compNodes.length
    compNodes.push([])
    compEdges.push([])
    const stack = [n.id]
    compOfId.set(n.id, c)
    while (stack.length) {
      const cur = stack.pop()
      compNodes[c].push(idIndex.get(cur))
      for (const next of adj.get(cur)) {
        if (!compOfId.has(next)) { compOfId.set(next, c); stack.push(next) }
      }
    }
  })
  chartLinks.forEach((l, i) => {
    const c = compOfId.get(nameToId.get(l.source))
    if (c != null) compEdges[c].push(i)
  })
  return nodes.map(n => {
    const c = compOfId.get(n.id)
    return { node: compNodes[c], edge: compEdges[c] }
  })
}

/** 把链索引写到节点元素上：hover/点选的 blur 范围都从这里取（renderFromData 与 resize 重建后需重写） */
function applyChainFocus() {
  if (!chartInstance || !chainFocusSets) return
  const data = chartInstance.getModel()?.getSeriesByIndex(0)?.getData()
  if (!data) return
  for (let i = 0; i < data.count(); i++) {
    const el = data.getItemGraphicEl(i)
    if (el) getECData(el).focus = chainFocusSets[i]
  }
}

// 节点聚焦（点选 / 悬停 / 搜索定位共用）：highlight 触发模糊范围 = 该点所在整条关系链，其余淡化。
// 换点先 downplay 旧点，防止上一个点的强调残留；全程 dispatchAction，不 setOption（会重建力模拟与漫游坐标系）
let focusedNodeName = null

function focusNodeVisual(name) {
  if (!chartInstance || !name) return
  if (focusedNodeName && focusedNodeName !== name) chartInstance.dispatchAction({ type: 'downplay', seriesIndex: 0 })
  focusedNodeName = name
  chartInstance.dispatchAction({ type: 'highlight', seriesIndex: 0, name })
}

function clearNodeFocus() {
  if (!chartInstance || !focusedNodeName) return
  chartInstance.dispatchAction({ type: 'downplay', seriesIndex: 0 })
  focusedNodeName = null
}

// 点选聚焦要「钉住」：鼠标移开元素时 echarts 会按 hover 语义清掉全部 blur，
// 这里在其后按当前选中点重新聚焦，保持「选中即整条链常亮」
function refocusChain() {
  if (focusedNodeName && chartInstance) {
    chartInstance.dispatchAction({ type: 'highlight', seriesIndex: 0, name: focusedNodeName })
  }
}

function disposeChart() {
  if (chartInstance) { chartInstance.dispose(); chartInstance = null }
  focusedNodeName = null
  chainFocusSets = null
}

// ==================== 实体详情（点实体看三元组） ====================
const selectedDetail = ref(null)
const entityLoading = ref(false)

const loadEntityDetail = async entityId => {
  if (!kbId()) return
  entityLoading.value = true
  try {
    const r = await graphEntityDetail(kbId(), entityId)
    if (r.success) selectedDetail.value = r.data || null
    else message.error(r.msg || '实体详情加载失败')
  } catch (e) { message.error(e.message || '实体详情加载失败') }
  finally { entityLoading.value = false }
}

// ==================== 源知识块弹层 ====================
const chunkOpen = ref(false)
const chunkData = ref(null)

const openChunk = async r => {
  chunkData.value = null
  chunkOpen.value = true
  try {
    const res = await graphChunk(kbId(), r.chunkId)
    if (res.success) chunkData.value = res.data
    else message.error(res.msg || '源块加载失败')
  } catch (e) { message.error(e.message || '源块加载失败') }
}

// ==================== 实体搜索（定位） ====================
const searchQ = ref('')
const searchOpts = ref([])
const searchHits = ref([])
let searchTimer = null

const onSearchInput = q => {
  clearTimeout(searchTimer)
  if (!q || !q.trim()) { searchOpts.value = []; searchHits.value = []; return }
  searchTimer = setTimeout(async () => {
    try {
      const r = await graphSearchEntity(kbId(), q.trim())
      const hits = r.success ? (r.data || []) : []
      searchHits.value = hits
      searchOpts.value = hits.map(h => ({ value: h.id, label: h.name, mention: h.mentionCount ?? 0 }))
    } catch (e) { /* 搜索失败静默（下拉留空），不打断浏览 */ }
  }, 250)
}

const onSearchPick = async entityId => {
  const ent = searchHits.value.find(h => h.id === entityId)
  searchQ.value = ''
  searchOpts.value = []
  if (!ent) return
  const inView = overviewData.value?.nodes?.some(n => n.id === entityId)
  if (inView && !focused.value) {
    // 已在全景里：直接聚焦 + 打开三元组面板（不动图表状态）
    loadEntityDetail(entityId)
    const name = [...nameToId.entries()].find(([, id]) => id === entityId)?.[0]
    if (chartInstance && name) {
      focusNodeVisual(name)
      chartInstance.dispatchAction({ type: 'showTip', seriesIndex: 0, name })
    }
  } else {
    await focusEntity(ent)
  }
}

// ==================== 构建 / 清空 ====================
const doBuild = async () => {
  graphBuilding.value = true
  try {
    const r = await graphBuild(kbId())
    if (r.success) {
      message.success(`构建已开始（${r.data?.total || 0} 个文档），可点「刷新」看进度`)
      const timer = setInterval(() => {
        if (!props.open) { clearInterval(timer); return }
        graphStatus(kbId()).then(st => {
          if (st.success) {
            graphInfo.value = st.data || {}
            if (!st.data?.building) { clearInterval(timer); message.success('图谱构建完成'); refreshGraph() }
          }
        }).catch(() => clearInterval(timer))
      }, 3000)
    } else message.error(r.msg || '构建启动失败')
  } catch (e) { message.error(e.message || '构建启动失败') }
  finally { graphBuilding.value = false }
}

const doClearGraph = async () => {
  try {
    const r = await graphClear(kbId())
    if (r.success) {
      message.success('图谱已清空')
      focused.value = null
      overviewData.value = null
      selectedDetail.value = null
      refreshGraph()
    } else message.error(r.msg || '清空失败')
  } catch (e) { message.error(e.message || '清空失败') }
}

const onWinResize = () => {
  if (!chartInstance) return
  chartInstance.resize()
  applyChainFocus()   // resize 触发重渲染会用回 adjacency 兜底范围，这里重新写回链索引
}
window.addEventListener('resize', onWinResize)

watch(() => props.open, open => {
  if (open) {
    // a-modal 内容是懒挂载：open 置真后的下一个 tick 容器才在 DOM（P1 原实现同款时序，实测够用）
    triplePage.value = 1
    graphView.value = 'graph'
    searchQ.value = ''
    nextTick(() => refreshGraph())
  } else {
    disposeChart()
    selectedDetail.value = null
    focused.value = null
    overviewData.value = null
    graphInfo.value = {}
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onWinResize)
  disposeChart()
})
</script>

<style scoped>
.kb-hint { font-size: 12px; color: var(--app-text3); line-height: 1.6; margin-top: 4px; }
/* 图谱：工具栏 + 力导向图画布 */
.graph-toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.graph-stat { white-space: nowrap; }
/* 动作区整体靠右，与左侧视图切换/中间状态拉开距离 */
.graph-toolbar-actions { margin-left: auto; display: flex; align-items: center; gap: 8px; }
.graph-wrap { position: relative; }
.graph-canvas { height: 480px; width: 100%; }
.graph-focus-bar {
  display: flex; align-items: center; gap: 8px; margin-top: 6px; padding: 6px 10px;
  border: 1px solid var(--app-warn-border); border-radius: 8px; background: var(--app-warn-weak);
  font-size: 12px; color: var(--app-warn-text);
}
.graph-panel {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  border: 1px solid var(--app-border); border-radius: 8px; padding: 8px 10px; margin-top: 6px;
  background: var(--app-bg, var(--app-panel-2)); font-size: 12px;
}
/* 实体详情面板改纵向：头部一行 + 关系清单滚动区（三元组多了不能把弹窗撑爆） */
.graph-panel-col { flex-direction: column; align-items: stretch; }
.gp-head { display: flex; align-items: center; gap: 8px; }
.gp-sub { color: var(--app-text3); }
.gp-rows { max-height: 180px; overflow: auto; display: flex; flex-direction: column; gap: 4px; margin-top: 6px; }
.gp-row { display: flex; align-items: center; gap: 8px; }
.gp-rel {
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 4px;
  padding: 2px 8px; font-size: 12px; font-family: ui-monospace, Menlo, monospace;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 55%;
}
.gp-doc { color: var(--app-text3); font-size: 11px; flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.gp-chunk-btn { flex: none; }
.graph-empty, .graph-note { font-size: 12px; color: var(--app-text3); }
.graph-empty { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; }
/* 搜索下拉行：实体名 + 提及次数 */
.gs-opt { display: flex; align-items: center; gap: 8px; }
.gs-opt-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.gs-opt-meta { margin-left: auto; font-size: 11px; color: var(--app-text3); flex: none; }
/* 源知识块弹层 */
.chunk-view { display: flex; flex-direction: column; gap: 10px; }
.chunk-meta { display: flex; align-items: center; gap: 8px; font-size: 12px; }
.chunk-doc { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chunk-path { color: var(--app-text3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chunk-content {
  margin: 0; max-height: 420px; overflow: auto; white-space: pre-wrap; word-break: break-word;
  background: var(--app-panel-2); border: 1px solid var(--app-border); border-radius: 8px;
  padding: 12px; font-size: 12px; line-height: 1.7;
}
</style>
