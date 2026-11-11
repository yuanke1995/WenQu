<template>
  <a-modal :open="open" @update:open="v => emit('update:open', v)"
           :title="`知识图谱 · ${kb?.name || ''}`" :footer="null" width="96vw"
           wrap-class-name="kb-graph-workspace"
           :body-style="{ padding: '12px 16px', height: 'calc(100vh - 150px)', overflow: 'hidden' }">
    <div class="gx-shell">
      <div class="gx-body">
        <!-- 左栏：实体搜索与列表 -->
        <aside class="gx-left">
          <a-auto-complete v-model:value="searchQ" :options="searchOpts" class="gx-search"
                           placeholder="搜索实体定位…" :disabled="!graphInfo.triples"
                           @search="onSearchInput" @select="onSearchPick">
            <template #option="{ label, mention }">
              <div class="gs-opt">
                <span class="gs-opt-name">{{ label }}</span>
                <span class="gs-opt-meta">提及 {{ mention }}</span>
              </div>
            </template>
          </a-auto-complete>
          <div class="gx-types">
            <span :class="['gx-type', { on: entityTypeFilter === 'all' }]" @click="setEntityType('all')">
              全部 {{ allEntities.length }}
            </span>
            <span v-for="t in typeCounts" :key="t.label"
                  :class="['gx-type', { on: entityTypeFilter === t.label }]"
                  @click="setEntityType(t.label)">
              <i :style="{ background: t.color }"></i>{{ t.label }} {{ t.count }}
            </span>
          </div>
          <div class="gx-sec">实体 <span>{{ entityList.length }} / {{ graphInfo.entities || 0 }}</span></div>
          <div class="gx-entities">
            <div v-for="e in filteredEntities" :key="e.id"
                 :class="['gx-entity', { on: selectedDetail?.entity?.id === e.id }]"
                 @click="pickEntity(e)">
              <i class="gx-dot" :style="{ background: typeColor(typeOf(e)) }"></i>
              <span class="gx-name" :title="e.name">{{ e.name }}</span>
              <span class="gx-deg">{{ e.degree || e.mentionCount || 0 }}</span>
            </div>
            <div v-if="!filteredEntities.length" class="gx-empty-mini">
              {{ searchQ ? '没有匹配实体' : '该类型暂无实体' }}
            </div>
          </div>
          <div class="gx-left-foot">点击实体查看关系与源块；拖动/滚轮操作画布。旧数据未分类：⋯ → 补全实体类型。</div>
        </aside>

        <!-- 中栏：图视图 / 列表 -->
        <section class="gx-center">
          <div class="graph-toolbar">
            <a-segmented v-model:value="graphView" size="small" :options="GRAPH_VIEWS" @change="onGraphViewChange" />
            <a-select v-model:value="nodeLimit" size="small" style="width:104px"
                      :options="nodeLimitOptions" @change="onNodeLimitChange" />
            <span class="kb-hint graph-stat">
              展示 {{ displayedCount }} / {{ graphInfo.entities || 0 }} 实体 · 三元组 {{ graphInfo.triples || 0 }}
              <template v-if="graphInfo.building">（进行中 {{ graphInfo.done || 0 }}/{{ graphInfo.total || 0 }}）</template>
              <template v-else-if="graphInfo.failed">（失败 {{ graphInfo.failed || 0 }}）</template>
            </span>
            <span class="sp"></span>
            <a-button size="small" :loading="graphBuilding" :disabled="graphInfo.building" @click="doBuild(false)">
              {{ graphInfo.building ? `构建中 ${graphInfo.done || 0}/${graphInfo.total || 0}` : '构建图谱' }}
            </a-button>
            <a-dropdown :trigger="['click']">
              <a-button size="small"><more-outlined /> 更多</a-button>
              <template #overlay>
                <a-menu @click="onGraphMenu">
                  <a-menu-item key="build" :disabled="graphInfo.building">构建 / 重建图谱（存量回溯）</a-menu-item>
                  <a-menu-item key="types" :disabled="graphInfo.building">补全实体类型（重建抽取）…</a-menu-item>
                  <a-menu-divider />
                  <a-menu-item key="clear" danger>清空图谱数据…</a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
            <a-tooltip title="刷新">
              <a-button size="small" @click="refreshGraph">
                <template #icon><reload-outlined /></template>
              </a-button>
            </a-tooltip>
            <a-tooltip title="重新计算力导向布局">
              <a-button size="small" @click="relayout">重新布局</a-button>
            </a-tooltip>
          </div>

          <div v-show="graphView === 'graph'" class="graph-wrap">
            <div ref="graphChartEl" class="graph-canvas"></div>
            <div v-if="!graphInfo.triples" class="graph-empty">还没有三元组——先点「构建图谱」。</div>
            <div v-else-if="focused" class="graph-focus-bar">
              已聚焦「{{ focused.name }}」的邻域（它不在默认全景视图内）
              <button class="app-link-btn" style="margin-left:auto" @click="backToOverview">返回全景</button>
            </div>
            <div v-if="graphInfo.triples" class="gx-legend">
              <span v-for="t in typeCounts" :key="t.label">
                <i class="lg" :style="{ background: t.color }"></i>{{ t.label }} {{ t.count }}
              </span>
              <span class="lg-note">节点大小 = 关联度 · 颜色 = 实体类型</span>
            </div>
            <div v-if="truncated && entityTypeFilter === 'all'" class="gx-trunc">
              还有 {{ Math.max(0, (graphInfo.entities || 0) - displayedCount) }} 个实体未展示，可增大展示数量或搜索定位
            </div>
          </div>

          <div v-show="graphView === 'list'" class="gx-list">
            <a-table :data-source="triples" :columns="tripleCols" size="small" row-key="id"
                     :loading="triplesLoading" :pagination="triplePagination"
                     :locale="{ emptyText: '还没有三元组（先构建图谱，或确认已开启开关并配置抽取模型）' }"
                     @change="onTripleTableChange" />
          </div>
        </section>

        <!-- 右栏：实体详情（常驻） -->
        <aside class="gx-right">
          <template v-if="selectedDetail">
            <div class="gx-detail-head">
              <span class="gx-detail-avatar">{{ (selectedDetail.entity.name || '?').slice(0, 1) }}</span>
              <div class="gx-detail-title">
                <div class="nm">{{ selectedDetail.entity.name }}</div>
                <div class="sub">
                  <span class="gx-type-badge"
                        :style="{ background: typeColor(selectedDetail.entity.type || '其他') + '22',
                                  color: typeColor(selectedDetail.entity.type || '其他') }">
                    {{ selectedDetail.entity.type || '其他' }}
                  </span>
                  {{ selectedDetail.degree }} 条关系<template v-if="detailDocCount"> · 出现于 {{ detailDocCount }} 个文档</template><template v-if="aliasText(selectedDetail)"> · 又名 {{ aliasText(selectedDetail) }}</template>
                </div>
              </div>
              <button class="app-link-btn" @click="closeEntityPanel">收起</button>
            </div>
            <a-spin :spinning="entityLoading" size="small">
              <div class="gp-cards">
                <div v-for="r in selectedDetail.triples" :key="r.id" class="gp-card">
                  <div class="gp-triple">
                    <template v-if="r.subject === selectedDetail.entity.name">
                      <span class="gp-pred">{{ r.predicate }}</span>
                      <b>{{ r.object }}</b>
                    </template>
                    <template v-else-if="r.object === selectedDetail.entity.name">
                      <b>{{ r.subject }}</b>
                      <span class="gp-pred">{{ r.predicate }}</span>
                    </template>
                    <template v-else>
                      <b>{{ r.subject }}</b>
                      <span class="gp-pred">{{ r.predicate }}</span>
                      <b>{{ r.object }}</b>
                    </template>
                  </div>
                  <div class="gp-card-foot">
                    <span class="gp-doc" :title="r.doc">{{ r.doc || '来源文档不可用' }}</span>
                    <button v-if="r.chunkId" class="app-link-btn" @click="openChunk(r)">源块 →</button>
                  </div>
                </div>
                <div v-if="!selectedDetail.triples.length" class="graph-note">该实体暂无关系</div>
              </div>
            </a-spin>
          </template>
          <template v-else>
            <div class="gx-right-empty">
              <apartment-outlined class="gx-right-ic" />
              <div class="gx-right-title">选择实体查看关系</div>
              <div class="gx-right-sub">点击左侧实体或图中节点，右侧展示完整三元组与源块。</div>
            </div>
            <div class="gx-right-stats">
              <div><b>{{ graphInfo.entities || 0 }}</b><span>实体</span></div>
              <div><b>{{ graphInfo.triples || 0 }}</b><span>三元组</span></div>
            </div>
            <div class="gx-right-note">
              {{ truncated
                ? `图谱较大，展示关联度最高的前 ${viewLimit} 个实体；其余用左侧搜索定位。`
                : '拖动节点/空白平移，滚轮缩放；点节点聚焦整条关系链。' }}
            </div>
          </template>
          <div v-if="graphInfo.failed" class="gx-right-fail">
            <span>抽取失败 {{ graphInfo.failed }} 个文档，可重新构建补齐</span>
            <button class="app-link-btn" :disabled="graphBuilding || graphInfo.building" @click="doBuild(false)">重试</button>
          </div>
        </aside>
      </div>

      <div v-if="graphInfo.building" class="gx-build">
        <loading-outlined spin />
        <span>存量回溯构建中 · {{ graphInfo.done || 0 }} / {{ graphInfo.total || 0 }} 个文档</span>
        <span class="gx-build-track"><i :style="{ width: buildPct + '%' }"></i></span>
        <span class="gx-build-note">可离开页面，后台完成</span>
      </div>
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
import { message, Modal } from 'ant-design-vue'
import { ApartmentOutlined, LoadingOutlined, MoreOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import * as echarts from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
// 深路径取内部 ecData 写入器：force graph 的 emphasis.focus 只认 'adjacency'（trajectory 仅桑基图支持），
// 想聚焦「整条关系链」只能自算索引集后写回元素（GraphView 内部给节点写 focus 用的就是它）
import { getECData } from 'echarts/lib/util/innerStore.js'
echarts.use([GraphChart, TooltipComponent, CanvasRenderer])
import {
  graphBuild, graphRebuildTypes, graphStatus, graphTriples, graphClear,
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
  current: triplePage.value, total: tripleTotal.value, pageSize: 20,
  size: 'small', showSizeChanger: false, showTotal: t => `共 ${t} 条`
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
/** 最近一次渲染的入参：重新布局直接用它重算，不再打接口 */
const lastRender = ref({ data: null, focus: null })
const VIEW_LIMIT = 300
/** 漫游缩放级别低于此值时隐藏连线上的关系标签（见 renderFromData 内 graphRoam 处理） */
const EDGE_LABEL_MIN_ZOOM = 0.8
/** 默认只画关联度最高的一小撮实体：1000+ 节点全量渲染会糊成毛线团 */
const nodeLimit = ref(40)
const nodeLimitOptions = [
  { value: 40, label: '40 个实体' }, { value: 100, label: '100 个实体' },
  { value: 200, label: '200 个实体' }, { value: 500, label: '500 个实体' }
]
const onNodeLimitChange = () => nextTick(renderGraphChart)
let chainFocusSets = null        // 节点数据下标 → 所在连通链的 {node:[], edge:[]} 下标集（聚焦范围）

const closeEntityPanel = () => { selectedDetail.value = null; clearNodeFocus() }

/** 右栏「出现于 N 个文档」：按当前实体三元组里的来源文档去重 */
const detailDocCount = computed(() => {
  const rows = selectedDetail.value?.triples || []
  return new Set(rows.map(r => r.docId || r.doc).filter(Boolean)).size
})

/** 实体类型：与后端抽取 prompt/白名单同口径；颜色只做视觉区分，不表达优先级 */
const ENTITY_TYPE_ORDER = ['人物', '机构', '法规', '概念', '产品', '地点', '时间', '其他']
const TYPE_COLORS = {
  人物: '#7c5cd6', 机构: '#2e6be6', 法规: '#c77c14', 概念: '#0f8a8a',
  产品: '#3b8f4e', 地点: '#c2477d', 时间: '#8fa3bf', 其他: '#a8b6c8'
}
const entityTypeFilter = ref('all')
const typeColor = t => TYPE_COLORS[t] || TYPE_COLORS['其他']
const typeOf = n => TYPE_COLORS[n.type] ? n.type : '其他'

const aliasText = detail => (detail.entity.aliases || []).join('、')

async function renderGraphChart() {
  if (!kbId() || !graphChartEl.value) return
  let data = null
  try {
    const r = await graphViewData(kbId(), nodeLimit.value)
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

/** 重新布局：用最近一次渲染数据整体重建力模拟，不重新拉接口 */
function relayout () {
  const lr = lastRender.value
  if (!lr.data) { renderGraphChart(); return }
  renderFromData(lr.data, lr.focus)
}

/** 一次性 setOption（此后只 dispatchAction，不再 setOption——会重建力模拟与漫游坐标系） */
function renderFromData(data, focus) {
  disposeChart()
  selectedDetail.value = null
  nameToId = new Map()
  lastRender.value = { data, focus }
  if (!data || !data.nodes?.length || !graphChartEl.value) return
  const cssVar = n => (getComputedStyle(document.documentElement).getPropertyValue(n) || '').trim()
  const allNodes = data.nodes
  const nodes = entityTypeFilter.value === 'all'
    ? allNodes
    : allNodes.filter(n => typeOf(n) === entityTypeFilter.value)
  if (!nodes.length) return
  const edges = data.edges || []
  const idToName = new Map()
  nodes.forEach(n => { nameToId.set(n.name, n.id); idToName.set(n.id, n.name) })
  const degrees = nodes.map(n => Number(n.degree || 1)).sort((a, b) => a - b)
  const maxDegree = Math.max(1, ...degrees)
  // 只给关联度前 35% 的节点常显标签，其余靠 hover/tooltip
  const labelThreshold = degrees[Math.max(0, Math.floor(degrees.length * 0.65))] || 1
  // 节点**不能带 id 字段**：echarts graph 在节点有 id 时以 id 作为 links 的匹配键，
  // 下面按 name 引用的连线会被整体静默丢弃。
  // 实体 id 的映射走 nameToId（点击换 id 查详情），不进 echarts。
  const chartNodes = nodes.map(n => {
    const deg = Number(n.degree || 1)
    const item = {
      name: n.name,
      symbolSize: focus && focus.id === n.id ? 46 : Math.min(46, 12 + Math.round((deg / maxDegree) * 30)),
      label: { show: deg >= labelThreshold, fontSize: 10 },
      itemStyle: { color: focus && focus.id === n.id ? '#e6a23c' : typeColor(typeOf(n)), borderColor: '#fff', borderWidth: 1 },
      emphasis: { focus: 'adjacency', label: { show: true, fontWeight: 'bold' } }
    }
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
  // 同一对实体间的多条平行关系（如 属于+属性为）默认线与标签完全重合、叠成重影
  // （原先靠 labelLayout 兜底，但该配置会触发 echarts 6.1 边标签缩放爆炸 bug，见下方 edgeLabel 注释）。
  // 改按无向对分组，给平行边对称递增 curveness 扇形展开：线分开，标签随之分开。
  const pairCount = new Map()
  const pairKey = l => (l.source < l.target ? l.source + '\u0000' + l.target : l.target + '\u0000' + l.source)
  chartLinks.forEach(l => {
    const k = pairKey(l)
    pairCount.set(k, (pairCount.get(k) || 0) + 1)
  })
  const pairIdx = new Map()
  chartLinks.forEach(l => {
    const k = pairKey(l)
    const n = pairCount.get(k)
    if (n < 2) return
    const i = pairIdx.get(k) || 0
    pairIdx.set(k, i + 1)
    l.lineStyle = { curveness: (i - (n - 1) / 2) * 0.25 }
  })
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
        ? `<b>${p.data.source}</b> —【${p.data.predicate}】→ <b>${p.data.target}</b><br/><span style="color:#999">点节点查看关系与来源块</span>`
        : `<b>${p.name}</b><br/><span style="color:#999">点击查看关系与来源块</span>`
    },
    series: [{
      type: 'graph', layout: 'force', roam: true, draggable: true, cursor: 'grab',
      left: -pad, top: -pad, width: wrapRect.width + pad * 2, height: wrapRect.height + pad * 2,
      force: { repulsion: 320, edgeLength: [60, 150], gravity: 0.2, layoutAnimation: true },
      data: chartNodes,
      links: chartLinks,
      label: { color: cssVar('--app-text'), position: 'right',
        formatter: p => String(p.name || '').length > 14 ? String(p.name).slice(0, 14) + '…' : String(p.name || '') },
      lineStyle: { color: 'rgba(152,160,170,.55)', curveness: 0.05 },
      // 切勿加回 labelLayout（如 hideOverlap）：echarts 6.1 中 edgeLabel.rotate 与 labelLayout
      // 同时配置时，滚轮缩小会让边标签 scale 按 1/zoom 累积爆炸成巨字且放大不恢复（实测复现）。
      // 节点标签防重叠已由关联度阈值控制，边标签则靠下方 graphRoam 按缩放级别整体显隐。
      edgeLabel: { show: nodes.length <= 80, fontSize: 10, color: cssVar('--app-text2'), formatter: '{c}', rotate: 0 },
      edgeSymbol: ['none', 'arrow'], edgeSymbolSize: 6,
      itemStyle: { color: '#4f6ef2' }
    }]
  }, true)
  applyChainFocus()
  // 缩小到阈值以下时隐藏边标签：rotate:0 的边标签不随缩放变小（固定 10px），
  // 缩得越小越糊成一团，直接藏掉；放大回来恢复。roamZoom 靠 graphRoam 事件的
  // payload.zoom（本次缩放系数）累积，与视图坐标系同步；重建图表时自然归 1。
  let roamZoom = 1
  chartInstance.on('graphRoam', p => {
    if (p.zoom == null) return
    roamZoom *= p.zoom
    const show = nodes.length <= 80 && roamZoom >= EDGE_LABEL_MIN_ZOOM
    chartInstance.getZr().storage.traverse(el => {
      if (el.type !== 'ec-line') return
      const text = el.parent?.getTextContent?.()
      if (text) text.ignore = !show
    })
    chartInstance.getZr().refresh()
  })
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

/** 左栏实体列表：取当前全景节点按关联度排序，搜索框做本地过滤（跨全量走上方远程搜索） */
const allEntities = computed(() => {
  const nodes = overviewData.value?.nodes || []
  return [...nodes].sort((a, b) => Number(b.degree || 0) - Number(a.degree || 0))
})
const typeCounts = computed(() => {
  const counts = new Map()
  for (const n of allEntities.value) {
    const t = typeOf(n)
    counts.set(t, (counts.get(t) || 0) + 1)
  }
  return ENTITY_TYPE_ORDER.filter(t => counts.has(t))
    .map(t => ({ label: t, count: counts.get(t), color: typeColor(t) }))
})
const entityList = computed(() => entityTypeFilter.value === 'all'
  ? allEntities.value
  : allEntities.value.filter(n => typeOf(n) === entityTypeFilter.value))
const displayedCount = computed(() => entityList.value.length)
const filteredEntities = computed(() => {
  const q = String(searchQ.value || '').trim().toLowerCase()
  if (!q) return entityList.value
  return entityList.value.filter(e => String(e.name || '').toLowerCase().includes(q))
})

/** 类型筛选：只重画当前全景，不重新请求 */
function setEntityType (t) {
  entityTypeFilter.value = t
  if (overviewData.value) renderFromData(overviewData.value, null)
}
const buildPct = computed(() => {
  const total = Number(graphInfo.value.total || 0)
  const done = Number(graphInfo.value.done || 0)
  return total > 0 ? Math.min(100, Math.round(done * 100 / total)) : 0
})

function pickEntity (e) {
  if (!e) return
  loadEntityDetail(e.id)
  if (chartInstance && e.name) {
    focusNodeVisual(e.name)
    chartInstance.dispatchAction({ type: 'showTip', seriesIndex: 0, name: e.name })
  }
}

// ==================== 构建 / 清空 ====================
const doBuild = async (forceTypes = false) => {
  graphBuilding.value = true
  try {
    const r = forceTypes ? await graphRebuildTypes(kbId()) : await graphBuild(kbId())
    if (r.success) {
      message.success(forceTypes ? '已开始补全实体类型（重建抽取）' : '构建已开始')
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

/** 写操作收进 ⋯：清空是破坏性动作，仍需二次确认 */
function onGraphMenu ({ key }) {
  if (key === 'build') {
    doBuild(false)
  } else if (key === 'types') {
    Modal.confirm({
      title: '补全存量实体类型？',
      content: '将清空本库抽取账本并重新抽取全部知识块，会重新消耗抽取模型额度；实体与三元组按幂等去重，已有关系不会丢。',
      okText: '开始补全', cancelText: '取消',
      onOk: () => doBuild(true)
    })
  } else if (key === 'clear') {
    Modal.confirm({
      title: '清空该库全部图谱数据？',
      content: '三元组、实体与抽取记录会全部删除，已开启的 GraphRAG 开关不受影响，可重新构建。',
      okText: '清空', okType: 'danger', cancelText: '取消',
      onOk: doClearGraph
    })
  }
}

const onWinResize = () => {
  if (!chartInstance) return
  chartInstance.resize()
  applyChainFocus()   // resize 触发重渲染会用回 adjacency 兜底范围，这里重新写回链索引
}
window.addEventListener('resize', onWinResize)

watch(() => props.open, open => {
  if (open) {
    // a-modal 内容是懒挂载：open 置真后的下一个 tick 容器才在 DOM
    triplePage.value = 1
    graphView.value = 'graph'
    nodeLimit.value = 40
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
.graph-canvas { height: 100%; min-height: 0; width: 100%; }
:global(.kb-graph-workspace .ant-modal) { max-width: 96vw; top: 24px; }
:global(.kb-graph-workspace .ant-modal-body) { overflow: hidden; }
/* 三栏工作台：左实体列表 / 中图 / 右实体详情 */
.gx-shell { height: 100%; display: flex; flex-direction: column; gap: 10px; }
.gx-body { flex: 1; min-height: 0; display: flex; gap: 12px; }
.gx-left { width: 228px; flex: none; display: flex; flex-direction: column; min-height: 0;
  border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel); padding: 10px; }
.gx-search { width: 100%; margin-bottom: 8px; }
.gx-types { display: flex; flex-wrap: wrap; gap: 4px; margin-bottom: 7px; }
.gx-type { display: inline-flex; align-items: center; gap: 4px; height: 19px; padding: 0 7px;
  border: 1px solid var(--app-border); border-radius: 999px; background: var(--app-panel-2);
  font-size: 10px; color: var(--app-text3); cursor: pointer; white-space: nowrap; }
.gx-type i { width: 6px; height: 6px; border-radius: 50%; display: inline-block; }
.gx-type.on { color: var(--app-accent); border-color: var(--app-accent-border); background: var(--app-accent-weak); }
.gx-type-badge { display: inline-block; font-size: 9.5px; padding: 1px 6px; border-radius: 999px;
  margin-right: 6px; font-weight: 500; vertical-align: 1px; }
.gx-sec { display: flex; align-items: center; justify-content: space-between; padding: 0 2px 6px;
  font-size: 10.5px; font-weight: 600; color: var(--app-text3); letter-spacing: .03em; }
.gx-entities { flex: 1; min-height: 0; overflow-y: auto; }
.gx-entity { display: flex; align-items: center; gap: 7px; padding: 6px 7px; border-radius: 6px;
  font-size: 11.5px; color: var(--app-text2); cursor: pointer; }
.gx-entity:hover { background: var(--app-panel-2); }
.gx-entity.on { background: var(--app-accent-weak); color: var(--app-accent); font-weight: 500; }
.gx-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--app-accent); flex: none; }
.gx-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.gx-deg { font-size: 10px; color: var(--app-text3); flex: none; }
.gx-empty-mini { padding: 18px 6px; text-align: center; font-size: 11px; color: var(--app-text3); }
.gx-left-foot { flex: none; margin-top: 8px; padding-top: 8px; border-top: 1px solid var(--app-border);
  font-size: 10px; color: var(--app-text3); line-height: 1.6; }
.gx-center { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; }
.gx-center .graph-toolbar { flex: none; margin-bottom: 8px; }
.gx-center .graph-wrap { flex: 1; min-height: 0; border: 1px solid var(--app-border); border-radius: 8px;
  background: var(--app-panel); overflow: hidden; }
.gx-list { flex: 1; min-height: 0; overflow: auto; }
.gx-right { width: 338px; flex: none; display: flex; flex-direction: column; min-height: 0; overflow: hidden;
  border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel); padding: 12px; }
.gx-right .gp-rows-tall { flex: 1; min-height: 0; max-height: none; overflow-y: auto; }
.gx-right :deep(.ant-spin-nested-loading) { flex: 1; min-height: 0; display: flex; }
.gx-right :deep(.ant-spin-container) { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.gx-detail-head { flex: none; display: flex; align-items: center; gap: 9px; padding-bottom: 10px;
  border-bottom: 1px solid var(--app-border); margin-bottom: 8px; }
.gx-detail-avatar { width: 30px; height: 30px; border-radius: 8px; background: var(--app-accent-weak);
  color: var(--app-accent); font-size: 13px; font-weight: 600; display: inline-flex; align-items: center;
  justify-content: center; flex: none; }
.gx-detail-title { flex: 1; min-width: 0; }
.gx-detail-title .nm { font-size: 14px; font-weight: 650; color: var(--app-text); overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap; }
.gx-detail-title .sub { font-size: 10.5px; color: var(--app-text3); margin-top: 2px; }
.gp-cards { flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 7px; }
.gp-card { border: 1px solid var(--app-border); border-radius: 8px; padding: 8px 9px; background: #fff; }
.gp-triple { font-size: 11.5px; line-height: 1.65; color: var(--app-text2); word-break: break-word; }
.gp-triple b { color: var(--app-text); font-weight: 500; }
.gp-pred { display: inline-block; font-size: 10px; color: var(--app-accent); background: var(--app-accent-weak);
  border-radius: 3px; padding: 0 5px; margin: 0 4px; vertical-align: 1px; }
.gp-card-foot { display: flex; align-items: center; gap: 8px; margin-top: 5px; padding-top: 5px;
  border-top: 1px dashed var(--app-border); }
.gp-card-foot .gp-doc { flex: 1; min-width: 0; font-size: 10px; color: var(--app-text3); overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap; }
.gp-cards .graph-note { text-align: center; padding: 16px 0; }
.gx-legend { position: absolute; left: 10px; bottom: 10px; display: flex; align-items: center; gap: 12px;
  background: rgba(255,255,255,.94); border: 1px solid var(--app-border); border-radius: 6px;
  padding: 5px 9px; font-size: 10px; color: var(--app-text3); pointer-events: none; }
.gx-legend .lg { width: 7px; height: 7px; border-radius: 50%; display: inline-block; margin-right: 4px; }
.gx-legend .lg-note { border-left: 1px solid var(--app-border); padding-left: 10px; }
.gx-trunc { position: absolute; right: 10px; bottom: 10px; max-width: 300px; font-size: 10px;
  color: var(--app-text3); background: rgba(255,255,255,.94); border: 1px solid var(--app-border);
  border-radius: 6px; padding: 5px 9px; line-height: 1.5; pointer-events: none; }
.gx-right-empty { display: flex; flex-direction: column; align-items: center; text-align: center;
  padding: 26px 8px 18px; }
.gx-right-ic { font-size: 30px; color: var(--app-text3); margin-bottom: 8px; }
.gx-right-title { font-size: 13px; font-weight: 600; color: var(--app-text2); }
.gx-right-sub { font-size: 11px; color: var(--app-text3); line-height: 1.6; margin-top: 4px; }
.gx-right-stats { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 6px; }
.gx-right-stats > div { background: var(--app-panel-2); border-radius: 8px; padding: 9px 11px; }
.gx-right-stats b { display: block; font-size: 18px; font-weight: 650; }
.gx-right-stats span { font-size: 10.5px; color: var(--app-text3); }
.gx-right-note { margin-top: auto; padding-top: 8px; border-top: 1px solid var(--app-border);
  font-size: 10.5px; color: var(--app-text3); line-height: 1.6; }
.gx-right-fail { flex: none; display: flex; align-items: center; gap: 8px; margin-top: 8px; padding: 7px 9px;
  border: 1px solid var(--app-warn-border); background: #fffdf7; border-radius: 6px;
  font-size: 10px; color: var(--app-warn-text); line-height: 1.5; }
.gx-right-fail span { flex: 1; }
.gx-right-fail .app-link-btn { flex: none; font-size: 10.5px; }
.gx-build { flex: none; display: flex; align-items: center; gap: 10px; padding: 8px 12px;
  border: 1px solid var(--app-warn-border); background: #fffdf7; border-radius: 8px;
  font-size: 11px; color: var(--app-warn-text); }
.gx-build-track { width: 170px; height: 5px; border-radius: 999px; background: var(--app-warn-border); overflow: hidden; }
.gx-build-track i { display: block; height: 100%; background: var(--app-warn); }
.gx-build-note { margin-left: auto; font-size: 10.5px; color: var(--app-text3); }
.graph-focus-bar {
  position: absolute; left: 10px; right: 10px; bottom: 10px;
  display: flex; align-items: center; gap: 8px; margin: 0; padding: 6px 10px;
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
