<template>
  <div class="kb-page">
    <div class="app-page-head">
      <h3 class="app-page-title">知识库</h3>
      <span class="head-hint-plain">文档的容器：检索按库隔离，向量/检索/解析参数随库（留空继承全局模板）</span>
      <button class="app-btn" style="margin-left:auto" @click="openCreate">
        <plus-outlined /> 新建知识库
      </button>
    </div>

    <a-spin :spinning="loading">
      <div class="kb-cards">
        <div v-for="kb in list" :key="kb.id" class="app-card kb-card" @click="openDocs(kb)">
          <div class="kb-card-head">
            <KbIcon :kb="kb" :size="22" />
            <span class="kb-name">{{ kb.name }}</span>
            <a-tag v-if="kb.isDefault === 1" color="blue" style="margin-left:auto">默认</a-tag>
          </div>
          <p class="kb-card-desc" :title="kb.description || ''">{{ kb.description || '暂无描述' }}</p>
          <div class="kb-card-meta">
            <span :class="{ 'kb-zero': !kb.docCount }">{{ kb.docCount }} 个文档</span>
            <span class="kb-meta-sep">·</span>
            <span v-if="kb.embeddingRef" :title="kb.embeddingRef">{{ modelRefInfo(kb.embeddingRef)?.displayName || '自定义向量模型' }}</span>
            <span v-else class="kb-warn">未绑定向量模型</span>
            <span class="kb-meta-sep">·</span>
            <span v-if="kb.queryParams" class="kb-params" title="库级检索参数已覆盖全局">检索已自定义</span>
            <span v-if="kb.parseParams" class="kb-params" title="库级解析参数已覆盖全局">解析已自定义</span>
            <span v-if="!kb.queryParams && !kb.parseParams" class="kb-dim">继承全局参数</span>
          </div>
          <div class="kb-card-actions" @click.stop>
            <template v-if="isAdmin || kb.createdBy === myUid">
              <button class="app-link-btn" @click="openEdit(kb)">编辑</button>
              <button class="app-link-btn" @click="openGraph(kb)">图谱</button>
              <!-- 默认库是兜底归属（不可删），删除按钮直接不渲染，只留 disabled 样式会误导可点 -->
              <button v-if="kb.isDefault !== 1" class="app-link-btn danger" @click="onDelete(kb)">删除</button>
            </template>
            <button class="app-link-btn" style="margin-left:auto" @click="openDocs(kb)">文档管理 →</button>
          </div>
        </div>
        <div v-if="!loading && !list.length" class="kb-empty">还没有知识库，点右上角「新建知识库」创建</div>
      </div>
    </a-spin>

    <!-- 知识库新建/编辑共用一个弹窗组件（文档列表页「知识库配置」也用它，避免两份表单走样） -->
    <KnowledgeBaseEditModal v-model:open="showEdit" :kb="editing" @saved="load" />

    <!-- 图谱浏览（图视图 / 列表 + 构建/状态） -->
    <a-modal v-model:open="graphModal" :title="`知识图谱 · ${graphKb?.name || ''}`" :footer="null" width="860px" @after-open="onGraphModalOpen">
      <!-- 工具栏三段式：左=视图切换（Segmented，导航语义）· 中=数据状态 · 右=动作区（构建/清空/刷新），
           切换控件与动作按钮从样式上就分组，避免「分不清是 tab 还是按钮」 -->
      <div class="graph-toolbar">
        <a-segmented v-model:value="graphView" size="small" :options="GRAPH_VIEWS" @change="onGraphViewChange" />
        <span class="kb-hint graph-stat">
          实体 {{ graphInfo.entities || 0 }} · 三元组 {{ graphInfo.triples || 0 }}
          <template v-if="graphInfo.building">（失败 {{ graphInfo.failed || 0 }}）</template>
        </span>
        <div class="graph-toolbar-actions">
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

      <!-- 图视图：力导向图（节点=实体、边=关系谓词；点节点 → 下方显示该实体的关系清单） -->
      <div v-show="graphView === 'graph'" class="graph-wrap">
        <div ref="graphChartEl" class="graph-canvas"></div>
        <div v-if="!graphInfo.triples" class="graph-empty">还没有三元组——先点「构建图谱」。</div>
        <div v-if="selectedEntity" class="graph-panel">
          <b>{{ selectedEntity }}</b> 的关系（{{ entityRelations.length }}）：
          <span v-for="(r, i) in entityRelations" :key="i" class="graph-rel">
            {{ r.source }} —【{{ r.value }}】→ {{ r.target }}
          </span>
          <button class="app-link-btn" style="margin-left:auto" @click="selectedEntity = null">收起</button>
        </div>
        <div v-else-if="graphInfo.triples" class="graph-note">点一个节点可在下方查看它的关系；拖动节点/空白平移、滚轮缩放。</div>
        <div v-else-if="graphInfo.triples > graphSampled" class="graph-note">图示展示前 {{ graphSampled }} 条关系（按提及度优先），完整清单请切「列表」。</div>
      </div>

      <!-- 列表视图：逐条核对（带来源文档） -->
      <div v-show="graphView === 'list'">
        <a-table :data-source="triples" :columns="tripleCols" size="small" row-key="id"
                 :loading="triplesLoading" :pagination="triplePagination"
                 :locale="{ emptyText: '还没有三元组（先构建图谱，或确认已开启开关并配置抽取模型）' }"
                 @change="onTripleTableChange" />
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import * as echarts from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
echarts.use([GraphChart, TooltipComponent, CanvasRenderer])
import { listKnowledgeBases, deleteKnowledgeBase, graphBuild, graphStatus, graphTriples, graphClear } from '../api'
import KnowledgeBaseEditModal from '../components/KnowledgeBaseEditModal.vue'
import KbIcon from '../components/KbIcon.vue'
import { loadModelIndex, modelRefInfo } from '../utils/modelRef'
import { isAdminSync, ensureAuth } from '../utils/auth'

// 权限：管理员全量；普通用户可自建自管自己的库，可见=自己的库+默认库+别人显式共享的库（只读），后端按共享范围过滤返回
const isAdmin = isAdminSync()
const myUid = ref('')

const router = useRouter()
const openDocs = kb => router.push(`/knowledge/${kb.id}/docs`)

const list = ref([])
const loading = ref(false)
const showEdit = ref(false)
// 编辑目标：null=新建（弹窗内以全局值做模板预填），否则回填该库覆盖值
const editing = ref(null)

const load = async () => {
  loading.value = true
  try {
    loadModelIndex().catch(() => {})
    const r = await listKnowledgeBases()
    list.value = (r && r.data) || []
  } catch (e) {
    message.error('知识库列表加载失败')
  } finally {
    loading.value = false
  }
}

// 新建/编辑弹窗：表单与保存逻辑在 KnowledgeBaseEditModal 组件内（kb=null 时为新建模式）
const openCreate = () => { editing.value = null; showEdit.value = true }
const openEdit = row => { editing.value = row; showEdit.value = true }

// ==================== P1 GraphRAG：图谱构建与三元组浏览 ====================
const graphModal = ref(false)
const graphKb = ref(null)
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
const openGraph = async kb => {
  graphKb.value = kb
  graphModal.value = true
  triplePage.value = 1
  refreshGraph()
}
const refreshGraph = async () => {
  if (!graphKb.value) return
  try {
    const [st, tr] = await Promise.all([
      graphStatus(graphKb.value.id),
      graphTriples(graphKb.value.id, triplePage.value, 20)
    ])
    if (st.success) graphInfo.value = st.data || {}
    if (tr.success) {
      triples.value = tr.data?.rows || []
      tripleTotal.value = tr.data?.total || 0
    }
  } catch (e) { message.error(e.message || '图谱状态加载失败') }
  // 图视图开着时刷新同步重渲染（构建完成/手动刷新都能看到新关系）
  if (graphView.value === 'graph') nextTick(renderGraphChart)
}
const onTripleTableChange = pg => { triplePage.value = pg.current; refreshGraph() }

// ==================== 图视图（echarts 力导向图：节点=实体、边=关系） ====================
// 交互铁律：**init + 一次 setOption 之后绝不再碰图表状态**（部分 setOption 会重建力模拟与漫游坐标系
// ——roam/拖拽/滚轮就此失灵，前两版都栽在这）。邻居信息走「点击节点 → 图下方关系面板」，纯 Vue 状态。
const graphView = ref('graph')   // 默认图视图；列表是核对清单
const GRAPH_VIEWS = [            // 视图切换选项（Segmented：导航语义，与动作按钮分组）
  { label: '图视图', value: 'graph' },
  { label: '列表', value: 'list' }
]
const graphChartEl = ref(null)
const graphSampled = 500         // 图视图一次拉取的关系上限（后端放宽到 500，超出部分走列表）
let chartInstance = null
const graphLinks = ref([])       // 当前关系（关系面板用）
const selectedEntity = ref(null) // 点选的实体（null=未选）

const entityRelations = computed(() => {
  if (!selectedEntity.value) return []
  return graphLinks.value.filter(l => l.source === selectedEntity.value || l.target === selectedEntity.value)
})

const onGraphViewChange = () => { if (graphView.value === 'graph') nextTick(renderGraphChart) }

watch(graphModal, open => {
  if (open) nextTick(() => { if (graphView.value === 'graph') renderGraphChart() })
  else disposeChart()
})

function disposeChart() {
  if (chartInstance) { chartInstance.dispose(); chartInstance = null }
}

async function renderGraphChart() {
  if (!graphKb.value || !graphChartEl.value) return
  // echarts 走 canvas 渲染，**不解析 CSS 变量**——必须读变量当前实际值（随主题取亮/暗色值），
  // 图表文字/连线颜色才能跟随亮暗主题；直接写 var(--app-*) 会静默失效显示默认黑
  const cssVar = n => (getComputedStyle(document.documentElement).getPropertyValue(n) || '').trim()
  let rows = []
  try {
    const r = await graphTriples(graphKb.value.id, 1, graphSampled)
    rows = r.success ? (r.data?.rows || []) : []
  } catch (e) { message.error(e.message || '图谱数据加载失败'); return }
  disposeChart()
  selectedEntity.value = null
  if (!rows.length) { graphLinks.value = []; return }   // 空态由模板的 .graph-empty 兜底
  // 节点度数（连线越多的实体越大）
  const degree = {}
  const nodeNames = []
  const seen = new Set()
  graphLinks.value = rows.map(t => {
    for (const n of [t.subject, t.object]) {
      degree[n] = (degree[n] || 0) + 1
      if (!seen.has(n)) { seen.add(n); nodeNames.push(n) }
    }
    return { source: t.subject, target: t.object, value: t.predicate, doc: t.doc }
  })
  chartInstance = echarts.init(graphChartEl.value)
  // 一次性 setOption，此后只读不写——交互全部交给 echarts 原生 roam（拖拽/平移/滚轮缩放）
  // 关键：graph 漫游只覆盖「初始布局矩形」，平移出新区域后就拖不动了（echarts 内部行为）。
  // 把视图矩形向四周外扩 1600px，漫游可及范围远超连续平移所需；力布局以中心聚类，视觉不受影响。
  const wrapRect = graphChartEl.value.getBoundingClientRect()
  const pad = 1600
  chartInstance.setOption({
    backgroundColor: 'transparent',
    tooltip: {
      formatter: p => p.dataType === 'edge'
        ? `<b>${p.data.source}</b> —【${p.data.value}】→ <b>${p.data.target}</b><br/><span style="color:#999">来源：${p.data.doc || '—'}</span>`
        : `<b>${p.name}</b><br/><span style="color:#999">点击查看该实体的关系</span>`
    },
    series: [{
      type: 'graph', layout: 'force', roam: true, draggable: true, cursor: 'grab',
      left: -pad, top: -pad, width: wrapRect.width + pad * 2, height: wrapRect.height + pad * 2,
      force: { repulsion: 320, edgeLength: [60, 150], gravity: 0.2, layoutAnimation: true },
      data: nodeNames.map(n => ({
        name: n,
        symbolSize: Math.min(48, 14 + (degree[n] || 1) * 4),
        label: { show: true, fontSize: 10 }
      })),
      links: graphLinks.value,
      label: { color: cssVar('--app-text'), position: 'right' },
      lineStyle: { color: cssVar('--app-text3'), curveness: 0.05 },
      edgeLabel: { show: true, fontSize: 10, color: cssVar('--app-text2'), formatter: '{c}' },
      edgeSymbol: ['none', 'arrow'], edgeSymbolSize: 7,
      itemStyle: { color: '#4f6ef2' }
    }]
  }, true)
  // 点节点 = 在图下方显示该实体的关系清单（纯 Vue 状态，零图表突变）
  chartInstance.on('click', p => {
    if (p.dataType === 'node') {
      window.__lastNodeClick = p.name   // 调试口：自动化测试/排障用，无副作用
      selectedEntity.value = p.name
    }
  })
}

const onWinResize = () => { if (chartInstance) chartInstance.resize() }
const doBuild = async () => {
  graphBuilding.value = true
  try {
    const r = await graphBuild(graphKb.value.id)
    if (r.success) {
      message.success(`构建已开始（${r.data?.total || 0} 个文档），可点「刷新」看进度`)
      // 构建中轮询进度（3s 一次，弹窗开着才轮询）
      const timer = setInterval(() => {
        if (!graphModal.value) { clearInterval(timer); return }
        graphStatus(graphKb.value.id).then(st => {
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
    const r = await graphClear(graphKb.value.id)
    if (r.success) { message.success('图谱已清空'); refreshGraph() }
    else message.error(r.msg || '清空失败')
  } catch (e) { message.error(e.message || '清空失败') }
}

const onDelete = async row => {
  try {
    const r = await deleteKnowledgeBase(row.id)
    if (r && r.success === false) {
      message.warning(r.msg || '无法删除')
      return
    }
    message.success('已删除（关联智能体已同步摘除该库）')
    await load()
  } catch (e) {
    message.error(e.message || '删除失败')
  }
}

onMounted(() => {
  load()
  ensureAuth().then(me => { myUid.value = me.user || '' })
  window.addEventListener('resize', onWinResize)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onWinResize)
  disposeChart()
})
</script>

<style scoped>
.kb-page { padding: 4px 2px; }
.kb-cards {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px; align-items: stretch;
}
.kb-card { cursor: pointer; display: flex; flex-direction: column; gap: 8px; transition: border-color .15s, box-shadow .15s; }
.kb-card:hover { border-color: var(--app-accent); box-shadow: 0 4px 16px -6px rgba(46, 107, 230, .25); }
.kb-card-head { display: flex; align-items: center; gap: 8px; min-width: 0; }
.kb-name { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.kb-card-desc {
  margin: 0; font-size: 12px; color: var(--app-text3); line-height: 1.6; min-height: 38px;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.kb-card-meta { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; font-size: 11px; color: var(--app-text2); }
.kb-meta-sep { color: var(--app-text3); }
.kb-card-actions { display: flex; align-items: center; gap: 10px; border-top: 1px solid var(--app-border); padding-top: 8px; margin-top: auto; }
.kb-dim { color: var(--app-text3); font-size: 11px; }
.kb-params { color: var(--app-ok); font-size: 11px; }
.kb-warn { color: var(--app-danger); font-size: 11px; }
.kb-zero { color: var(--app-text3); }
.kb-empty { grid-column: 1 / -1; text-align: center; color: var(--app-text3); font-size: 12px; padding: 40px 0; }
.kb-hint { font-size: 12px; color: var(--app-text3); line-height: 1.6; margin-top: 4px; }

/* 图谱：工具栏 + 力导向图画布 */
.graph-toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.graph-stat { white-space: nowrap; }
/* 动作区整体靠右，与左侧视图切换/中间状态拉开距离 */
.graph-toolbar-actions { margin-left: auto; display: flex; align-items: center; gap: 8px; }
.graph-wrap { position: relative; }
.graph-canvas { height: 480px; width: 100%; }
.graph-panel {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  border: 1px solid var(--app-border); border-radius: 8px; padding: 8px 10px; margin-top: 6px;
  background: var(--app-bg, var(--app-panel-2)); font-size: 12px;
}
.graph-rel {
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 4px;
  padding: 2px 8px; font-size: 12px; font-family: ui-monospace, Menlo, monospace;
}
.graph-empty, .graph-note { font-size: 12px; color: var(--app-text3); }
.graph-empty { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; }
</style>
