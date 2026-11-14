<template>
  <a-drawer :open="open" :width="800" placement="right" :body-style="{ padding: '0' }"
            @update:open="v => emit('update:open', v)">
    <template #title>
      <div class="dr-head">
        <KbIcon :kb="kb" :size="32" />
        <div class="dr-title">
          <div class="dr-name">
            {{ kb?.name }}
            <a-tag v-if="kb?.isDefault === 1" color="blue" style="margin:0">默认</a-tag>
            <a-tag v-else-if="kb?.builtin === 1" color="gold" style="margin:0">官方</a-tag>
            <a-tag v-if="kb?.archived === 1" style="margin:0">已归档</a-tag>
            <a-tag v-if="attention" color="orange" style="margin:0">需关注</a-tag>
          </div>
          <div class="dr-sub">
            {{ data?.health?.docTotal ?? kb?.docCount ?? 0 }} 个文档 ·
            {{ data?.health?.chunkTotal ?? kb?.chunkTotal ?? 0 }} 个知识块 ·
            {{ modelLabel || '未绑定向量模型' }}
          </div>
        </div>
        <span class="sp"></span>
        <a-button size="small" type="primary" @click="emit('probe', { kb, query: '' })">试检索</a-button>
        <a-button size="small" @click="emit('docs', kb)">文档管理</a-button>
        <a-dropdown :trigger="['click']">
          <a-button size="small"><more-outlined /></a-button>
          <template #overlay>
            <a-menu @click="onMenu">
              <a-menu-item key="edit">编辑设置</a-menu-item>
              <a-menu-item v-if="canGraph" key="graph">知识图谱</a-menu-item>
              <a-menu-item key="archive" :disabled="kb?.isDefault === 1 || kb?.builtin === 1">
                {{ kb?.archived === 1 ? '恢复知识库' : '归档（可恢复）' }}
              </a-menu-item>
            </a-menu>
          </template>
        </a-dropdown>
      </div>
    </template>

    <a-spin :spinning="loading">
      <div class="dr-tabs">
        <span :class="['dr-tab', { on: tab === 'overview' }]" @click="tab = 'overview'">概览</span>
        <span :class="['dr-tab', { on: tab === 'documents' }]" @click="switchTab('documents')">
          文档 <i v-if="docs.length" class="n">{{ docs.length }}</i>
        </span>
        <span v-if="canGraph" :class="['dr-tab', { on: tab === 'graph' }]" @click="switchTab('graph')">图谱</span>
        <span :class="['dr-tab', { on: tab === 'settings' }]" @click="switchTab('settings')">设置</span>
        <span :class="['dr-tab', { on: tab === 'unmatched' }]" @click="switchTab('unmatched')">
          未命中 <i v-if="unmatchedCount" class="n warn">{{ unmatchedCount }}</i>
        </span>
        <span :class="['dr-tab', { on: tab === 'agents' }]" @click="switchTab('agents')">
          智能体 <i v-if="agents.length" class="n">{{ agents.length }}</i>
        </span>
        <span class="sp"></span>
        <a-select v-model:value="days" size="small" style="width:88px" :options="dayOptions" @change="load" />
      </div>

      <div v-if="tab === 'overview'" class="dr-body">
        <div v-if="!data" class="dr-empty">加载中…</div>
        <template v-else>
          <div v-if="healthTip" class="tip-strip" :class="healthTip.type">
            <warning-outlined v-if="healthTip.type === 'warn'" />
            <info-circle-outlined v-else />
            {{ healthTip.text }}
            <a v-if="healthTip.action" class="tip-link" @click="emit('docs', kb)">查看文档 →</a>
          </div>

          <div class="tiles">
            <div class="tile"><div class="n">{{ data.health?.docTotal || 0 }}</div><div class="l">文档</div></div>
            <div class="tile"><div class="n">{{ data.health?.chunkTotal || 0 }}</div><div class="l">知识块</div></div>
            <div class="tile"><div class="n">{{ hitTotal }}</div><div class="l">{{ days }} 天命中</div></div>
            <div class="tile">
              <div class="n">{{ data.coverage?.rate || 0 }}<small>%</small></div>
              <div class="l">覆盖提问（{{ data.coverage?.withHit || 0 }}/{{ data.coverage?.questions || 0 }}）</div>
            </div>
          </div>

          <div class="panel">
            <div class="panel-h">文档健康 <span class="sub">已入库的文档才会参与检索</span><span class="sp"></span>
              <span class="lnk" @click="emit('docs', kb)">查看文档 →</span>
            </div>
            <div class="hbar">
              <i class="ok" :style="{ width: seg('docActive') }"></i>
              <i class="run" :style="{ width: seg('docParsing') }"></i>
              <i class="err" :style="{ width: seg('docFailed') }"></i>
              <i class="off" :style="{ width: seg('docDeprecated') }"></i>
            </div>
            <div class="legend">
              <span><i class="d ok"></i>已入库 {{ data.health?.docActive || 0 }}</span>
              <span><i class="d run"></i>解析中 {{ data.health?.docParsing || 0 }}</span>
              <span><i class="d err"></i>失败 {{ data.health?.docFailed || 0 }}</span>
              <span><i class="d off"></i>已弃用 {{ data.health?.docDeprecated || 0 }}</span>
              <span class="muted">回收站 {{ kb?.recycleCount || 0 }}</span>
            </div>
          </div>

          <div class="panel">
            <div class="panel-h">检索趋势 <span class="sub">命中 {{ hitTotal }} · 未命中 {{ missTotal }}</span><span class="sp"></span></div>
            <svg class="trend" viewBox="0 0 720 150" preserveAspectRatio="none">
              <defs>
                <linearGradient id="kbTrend" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stop-color="#2e6be6" stop-opacity=".18" />
                  <stop offset="100%" stop-color="#2e6be6" stop-opacity="0" />
                </linearGradient>
              </defs>
              <g stroke="#eef0f3" stroke-width="1">
                <line x1="0" y1="20" x2="720" y2="20" /><line x1="0" y1="60" x2="720" y2="60" />
                <line x1="0" y1="100" x2="720" y2="100" /><line x1="0" y1="130" x2="720" y2="130" />
              </g>
              <path :d="areaPath" fill="url(#kbTrend)" />
              <polyline :points="hitPoints" fill="none" stroke="#2e6be6" stroke-width="2" stroke-linejoin="round" />
              <polyline :points="missPoints" fill="none" stroke="#c77c14" stroke-width="1.4" stroke-dasharray="4 3" />
              <g fill="#98a0aa" font-size="9">
                <text x="0" y="147">{{ dayLabels[0] }}</text>
                <text x="330" y="147">{{ dayLabels[1] }}</text>
                <text x="660" y="147">{{ dayLabels[2] }}</text>
              </g>
            </svg>
          </div>

          <div class="two-col">
            <div class="panel">
              <div class="panel-h">Top 命中文档 <span class="sub">{{ days }} 天</span></div>
              <div v-if="!data.topDocs?.length" class="mini-empty">还没有命中文档</div>
              <div v-for="(d, i) in data.topDocs" :key="d.docId" class="rank">
                <span class="rk">{{ i + 1 }}</span>
                <span class="nm" :title="d.docName">{{ d.docName }}</span>
                <span class="bar"><i :style="{ width: barWidth(d.count) }"></i></span>
                <span class="ct">{{ d.count }}</span>
              </div>
            </div>
            <div class="panel">
              <div class="panel-h">未命中问题 <span class="sp"></span>
                <span class="lnk" @click="tab = 'unmatched'">全部 {{ unmatchedCount }} →</span>
              </div>
              <div v-if="!data.unmatched?.length" class="mini-empty">暂无未命中问题</div>
              <div v-for="u in data.unmatched.slice(0, 3)" :key="u.question" class="un-row">
                <a-tag :color="u.reason === 'no_result' ? 'default' : (u.reason === 'feedback' ? 'red' : 'orange')" style="margin:0">
                  {{ reasonText(u.reason) }}
                </a-tag>
                <span class="q" :title="u.question">{{ u.question }}</span>
                <a class="add" @click="addEval(u)">加入评测</a>
              </div>
            </div>
          </div>

          <div class="panel">
            <div class="panel-h">最近活动<span class="sp"></span></div>
            <div v-if="!data.recent?.length" class="mini-empty">暂无活动</div>
            <div v-for="(r, i) in data.recent" :key="i" class="act">
              <i :class="['dot', r.type === 'graph' ? 'warn' : statusDot(r.status)]"></i>
              <span class="act-txt">{{ r.name }}</span>
              <span class="act-tag">{{ r.type === 'graph' ? '构建中' : statusText(r.status) }}</span>
              <span class="tm">{{ fmtTime(r.time) }}</span>
            </div>
          </div>
        </template>
      </div>

      <div v-else-if="tab === 'documents'" class="dr-body">
        <div class="panel">
          <div class="panel-h">文档
            <span class="sub">{{ docs.length }} 个 · {{ data?.health?.chunkTotal || 0 }} 个知识块</span>
            <span class="sp"></span>
            <span class="lnk" @click="emit('docs', kb)">打开文档管理 →</span>
          </div>
          <a-spin :spinning="docsLoading">
            <div v-if="!docs.length" class="mini-empty">还没有文档，去文档管理上传或导入</div>
            <div v-for="d in docs" :key="d.id" class="doc-mini" @click="emit('docs', kb)">
              <span class="doc-type" :style="typeStyle(d.fileType)">{{ (d.fileType || 'file').toUpperCase().slice(0, 4) }}</span>
              <div class="doc-main">
                <div class="doc-name" :title="d.fileName">{{ d.fileName }}</div>
                <div class="doc-meta">{{ d.chunkCount || 0 }} 块 · {{ d.hitCount || 0 }} 命中 · {{ fmtTime(d.updateTime || d.createTime) }}</div>
              </div>
              <span :class="['pill-mini', statusClass(d.status)]">{{ statusText(d.status) }}</span>
            </div>
          </a-spin>
        </div>
      </div>

      <div v-else-if="tab === 'graph' && canGraph" class="dr-body">
        <div class="panel">
          <div class="panel-h">知识图谱
            <span class="sub">{{ kb?.graphEnabled === 1 ? '已开启检索图扩展' : '未开启' }}</span>
            <span class="sp"></span>
            <span v-if="canGraph" class="lnk" @click="emit('graph', kb)">打开图谱工作台 →</span>
          </div>
          <div class="graph-stats">
            <div class="gs"><b>{{ graphInfo.entities || 0 }}</b><span>实体</span></div>
            <div class="gs"><b>{{ graphInfo.triples || 0 }}</b><span>三元组</span></div>
            <div class="gs"><b>{{ graphInfo.building ? '构建中' : '空闲' }}</b><span>构建状态</span></div>
          </div>
          <div v-if="graphInfo.building" class="tip-strip warn">
            构建中 {{ graphInfo.done || 0 }}/{{ graphInfo.total || 0 }}，可离开页面，后台完成后自动结束。
          </div>
          <div v-else-if="graphInfo.failed" class="tip-strip warn">上次构建失败 {{ graphInfo.failed }} 个文档，可到图谱工作台重试。</div>
          <div v-else-if="kb?.graphEnabled !== 1" class="mini-empty">开启知识图谱后，解析完成会自动抽三元组，检索时可一跳扩展。</div>
          <a-button v-if="canGraph" size="small" style="margin-top:10px" @click="emit('graph', kb)">打开图谱</a-button>
        </div>
      </div>

      <div v-else-if="tab === 'settings'" class="dr-body">
        <div class="panel">
          <div class="panel-h">知识库设置<span class="sp"></span>
            <span class="lnk" @click="emit('edit', kb)">编辑设置 →</span>
          </div>
          <div class="set-row"><span>向量模型</span><b>{{ modelLabel || '未绑定' }}</b></div>
          <div class="set-row"><span>检索参数</span><b>{{ paramSummary(kb?.queryParams) }}</b></div>
          <div class="set-row"><span>解析参数</span><b>{{ paramSummary(kb?.parseParams) }}</b></div>
          <div class="set-row"><span>知识图谱</span><b>{{ kb?.graphEnabled === 1 ? '已开启' : '未开启' }}</b></div>
          <div class="set-row"><span>创建人</span><b>{{ kb?.createdBy || '—' }}</b></div>
          <div class="set-note">修改向量模型会按库重嵌入；解析参数只对之后解析的文档生效。</div>
        </div>
      </div>

      <div v-else-if="tab === 'unmatched'" class="dr-body">
        <div class="panel">
          <div class="panel-h">未命中问题 <span class="sub">检索为空/低分 + 用户引用差评</span></div>
          <div v-if="!data?.unmatched?.length" class="mini-empty">暂无未命中问题</div>
          <div v-for="u in data?.unmatched || []" :key="u.question" class="un-item">
            <div class="un-main">
              <div class="un-q">{{ u.question }}</div>
              <div class="un-meta">
                <a-tag :color="u.reason === 'no_result' ? 'default' : (u.reason === 'feedback' ? 'red' : 'orange')" style="margin:0">
                  {{ reasonText(u.reason) }}
                </a-tag>
                <span>出现 {{ u.count }} 次</span>
                <span>最近 {{ fmtTime(u.lastAt) }}</span>
              </div>
            </div>
            <div class="un-acts">
              <a-button size="small" @click="emit('probe', { kb, query: u.question })">试检索</a-button>
              <a-button size="small" type="primary" ghost @click="addEval(u)">加入评测</a-button>
            </div>
          </div>
        </div>
      </div>

      <div v-else class="dr-body">
        <div class="panel">
          <div class="panel-h">使用该库的智能体 <span class="sub">删除/归档会影响这些助手</span></div>
          <div v-if="agentsError" class="mini-empty">
            {{ agentsError }}<a-button size="small" type="link" @click="loadAgents">重试</a-button>
          </div>
          <div v-else-if="!agents.length" class="mini-empty">还没有智能体绑定这个库</div>
          <div v-for="a in agents" :key="a.id" class="agent-row">
            <span class="agent-avatar">{{ (a.name || '?').slice(0, 1) }}</span>
            <div class="agent-main">
              <div class="agent-name">{{ a.name }}
                <a-tag v-if="a.isBuiltin === 1" color="blue" style="margin:0">内置</a-tag>
                <a-tag v-if="a.bindingKind === 'default'" style="margin:0">默认范围</a-tag>
              </div>
              <div class="agent-desc">{{ a.description || '暂无描述' }}</div>
            </div>
          </div>
        </div>
      </div>
    </a-spin>
  </a-drawer>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  InfoCircleOutlined, MoreOutlined, WarningOutlined
} from '@ant-design/icons-vue'
import KbIcon from './KbIcon.vue'
import { addEvalCase, archiveKnowledgeBase, getKbAgents, getKbInsights, graphStatus, listDocuments, unarchiveKnowledgeBase } from '../api'
import { modelRefInfo } from '../utils/modelRef'

const props = defineProps({
  open: { type: Boolean, default: false },
  kb: { type: Object, default: null },
  // 库主/管理员级才能进图谱工作台：由知识库页用它的 canManage(kb) 算好传入，
  // 与卡片菜单「知识图谱」同一判据，别在抽屉里再写一套身份判断
  manageable: { type: Boolean, default: false }
})
const emit = defineEmits(['update:open', 'probe', 'docs', 'edit', 'graph', 'changed'])

/** 官方内置库（builtin=1）的图谱随版本分发，卡片菜单也不给入口，这里保持一致 */
const canGraph = computed(() => props.manageable && props.kb?.builtin !== 1)

const loading = ref(false)
const data = ref(null)
const agents = ref([])
const agentsError = ref('')
const docs = ref([])
const docsLoading = ref(false)
const docsLoaded = ref(false)
const graphInfo = ref({})
const tab = ref('overview')
const days = ref(30)
const dayOptions = [
  { value: 7, label: '近 7 天' }, { value: 30, label: '近 30 天' }, { value: 90, label: '近 90 天' }
]

const attention = computed(() => {
  const h = data.value?.health || {}
  return Number(h.docFailed || 0) > 0 || Number(h.docParsing || 0) > 0 || !props.kb?.embeddingRef
})
const modelLabel = computed(() => {
  const ref = props.kb?.embeddingRef || ''
  if (!ref) return ''
  return modelRefInfo(ref)?.displayName || ref.split('/').pop() || ref
})
const unmatchedCount = computed(() => data.value?.unmatched?.length || 0)
const hitTotal = computed(() => (data.value?.trend || []).reduce((n, t) => n + Number(t.hit || 0), 0))
const missTotal = computed(() => (data.value?.trend || []).reduce((n, t) => n + Number(t.miss || 0), 0))
const maxTrend = computed(() => {
  const arr = data.value?.trend || []
  return Math.max(1, ...arr.map(t => Math.max(Number(t.hit || 0), Number(t.miss || 0))))
})
const hitPoints = computed(() => pointsFor('hit'))
const missPoints = computed(() => pointsFor('miss'))
const areaPath = computed(() => {
  const arr = data.value?.trend || []
  if (!arr.length) return ''
  const p = hitPoints.value
  return `M0,130 L${p} L720,130 Z`
})
const dayLabels = computed(() => {
  const arr = data.value?.trend || []
  if (!arr.length) return ['', '', '']
  const fmt = s => String(s || '').slice(5)
  return [fmt(arr[0].date), fmt(arr[Math.floor(arr.length / 2)].date), fmt(arr[arr.length - 1].date)]
})
const healthTip = computed(() => {
  const h = data.value?.health || {}
  if (Number(h.docFailed || 0) > 0) {
    return { type: 'warn', text: `有 ${h.docFailed} 个文档解析失败，失败文档不会参与检索，请到文档页处理。`, action: true }
  }
  if (Number(h.docParsing || 0) > 0) {
    return { type: 'info', text: `有 ${h.docParsing} 个文档正在解析，完成后自动参与检索。`, action: true }
  }
  if (!props.kb?.embeddingRef) return { type: 'warn', text: '该库还没有绑定向量模型，无法参与检索。' }
  return null
})

watch(() => [props.open, props.kb?.id], ([open]) => {
  if (!open || !props.kb?.id) return
  tab.value = 'overview'
  data.value = null
  agents.value = []
  agentsError.value = ''
  docs.value = []
  docsLoaded.value = false
  graphInfo.value = props.kb?.graph || {}
  load()
})

/** 切库/身份变化导致失去图谱权限时，别把人留在已经隐藏的 tab 上 */
watch(canGraph, allowed => {
  if (!allowed && tab.value === 'graph') tab.value = 'overview'
})

/** Tab 切换：文档/图谱按需加载，避免每次打开抽屉都多打两个请求 */
function switchTab (next) {
  if (next === 'graph' && !canGraph.value) return
  tab.value = next
  if (next === 'documents' && !docsLoaded.value) loadDocs()
  if (next === 'graph') loadGraph()
}

async function loadDocs () {
  if (!props.kb?.id) return
  docsLoading.value = true
  try {
    const r = await listDocuments(props.kb.id)
    docs.value = r.data || []
    docsLoaded.value = true
  } catch (e) {
    message.error(e.message || '文档加载失败')
  } finally {
    docsLoading.value = false
  }
}

async function loadGraph () {
  if (!props.kb?.id || !canGraph.value) return
  try {
    const r = await graphStatus(props.kb.id)
    graphInfo.value = { ...(props.kb?.graph || {}), ...(r.data || {}) }
  } catch (e) {
    graphInfo.value = props.kb?.graph || {}
  }
}

function pointsFor (key) {
  const arr = data.value?.trend || []
  if (!arr.length) return ''
  const max = maxTrend.value
  return arr.map((t, i) => {
    const x = arr.length === 1 ? 0 : (i / (arr.length - 1)) * 720
    const y = 130 - (Number(t[key] || 0) / max) * 105
    return `${x.toFixed(1)},${y.toFixed(1)}`
  }).join(' ')
}

async function load () {
  if (!props.kb?.id) return
  loading.value = true
  try {
    const [r] = await Promise.all([
      getKbInsights(props.kb.id, days.value),
      loadAgents()
    ])
    data.value = r.data || null
  } catch (e) {
    message.error(e.message || '洞察加载失败')
  } finally {
    loading.value = false
  }
}

/** 绑定智能体单独一条：失败要显式报错，不能吞成空列表——那会把「读不到」显示成「没人用」 */
async function loadAgents () {
  if (!props.kb?.id) return
  agentsError.value = ''
  try {
    const r = await getKbAgents(props.kb.id)
    agents.value = r.data || []
  } catch (e) {
    agents.value = []
    agentsError.value = e.message || '智能体列表加载失败'
  }
}

function seg (key) {
  const h = data.value?.health || {}
  const total = Number(h.docTotal || 0)
  if (!total) return '0%'
  return `${(Number(h[key] || 0) / total) * 100}%`
}

function barWidth (count) {
  const top = data.value?.topDocs?.[0]?.count || 1
  return `${Math.max(6, Math.round(Number(count || 0) / Number(top) * 100))}%`
}

async function addEval (u) {
  try {
    const r = await addEvalCase(u.question, [])
    if (r.data && r.data.added === false) message.info(r.data.reason || '评测集已存在该问题')
    else message.success('已加入评测集')
  } catch (e) {
    message.error(e.message || '加入评测集失败')
  }
}

function reasonText (r) {
  if (r === 'feedback') return '引用差评'
  if (r === 'no_result') return '无结果'
  return '低相关'
}

function statusText (s) {
  return { 0: '已入库', 1: '已弃用', 2: '解析中', 3: '解析失败' }[Number(s)] || '—'
}

function statusClass (s) {
  return { 0: 'ok', 1: 'off', 2: 'run', 3: 'err' }[Number(s)] || 'off'
}

function typeStyle (t) {
  const map = {
    pdf: ['#fff2f0', '#d4552e'], docx: ['#eaf0fd', '#2e6be6'], md: ['#eef0f3', '#98a0aa'],
    markdown: ['#eef0f3', '#98a0aa'], xlsx: ['#eaf5ec', '#3b8f4e'], csv: ['#eaf5ec', '#3b8f4e'],
    url: ['#f0ecfb', '#7c5cd6'], pptx: ['#faf3e6', '#a3691b'], ppt: ['#faf3e6', '#a3691b']
  }
  const [bg, color] = map[String(t || '').toLowerCase()] || ['#eef0f3', '#5f6570']
  return { background: bg, color }
}

/** 参数覆盖摘要：空=跟随内置默认，非空=解析出几项覆盖（坏 JSON 原样提示） */
function paramSummary (raw) {
  if (!raw) return '跟随内置默认'
  try {
    const n = Object.keys(JSON.parse(raw) || {}).length
    return n ? `${n} 项已覆盖` : '跟随内置默认'
  } catch (e) {
    return '已自定义'
  }
}

function statusDot (s) {
  return { 0: 'ok', 1: 'off', 2: 'run', 3: 'err' }[Number(s)] || 'off'
}

function fmtTime (t) {
  if (!t) return '—'
  const d = new Date(String(t).replace(' ', 'T'))
  if (Number.isNaN(d.getTime())) return String(t).slice(0, 16)
  const diff = Date.now() - d.getTime()
  if (diff < 60000) return '刚刚'
  if (diff < 3600000) return `${Math.floor(diff / 60000)} 分钟前`
  if (diff < 86400000) return `${Math.floor(diff / 3600000)} 小时前`
  if (diff < 86400000 * 30) return `${Math.floor(diff / 86400000)} 天前`
  return `${d.getMonth() + 1}-${d.getDate()}`
}

async function onMenu ({ key }) {
  if (key === 'edit') emit('edit', props.kb)
  else if (key === 'graph') emit('graph', props.kb)
  else if (key === 'archive') {
    try {
      const archived = props.kb?.archived === 1
      await (archived ? unarchiveKnowledgeBase(props.kb.id) : archiveKnowledgeBase(props.kb.id))
      message.success(archived ? '已恢复' : '已归档，智能体绑定已摘除')
      emit('changed')
    } catch (e) {
      message.error(e.message || '操作失败')
    }
  }
}
</script>

<style scoped>
.dr-head { display: flex; align-items: center; gap: 10px; min-width: 0; width: 100%; }
.dr-title { min-width: 0; }
.dr-name { display: flex; align-items: center; gap: 6px; font-size: 15px; font-weight: 600; }
.dr-sub { font-size: 11px; color: var(--app-text3); margin-top: 3px; }
.dr-head .sp { flex: 1; }
.dr-tabs { display: flex; align-items: center; gap: 20px; padding: 4px 20px 0; border-bottom: 1px solid var(--app-border);
  background: #fff; position: sticky; top: 0; z-index: 3; }
.dr-tab { font-size: 12.5px; color: var(--app-text2); padding: 9px 0 8px; cursor: pointer; position: relative; }
.dr-tab.on { color: var(--app-accent); font-weight: 600; }
.dr-tab.on::after { content: ''; position: absolute; left: 0; right: 0; bottom: -1px; height: 2px; background: var(--app-accent); }
.dr-tab .n { font-style: normal; font-size: 10px; color: var(--app-text3); background: var(--app-panel-2);
  border-radius: 999px; padding: 1px 6px; margin-left: 4px; }
.dr-tab .n.warn { color: var(--app-warn-text); background: var(--app-warn-weak); }
.dr-tabs .sp { flex: 1; }
.dr-body { padding: 14px 20px 20px; }
.dr-empty { padding: 60px 0; text-align: center; color: var(--app-text3); font-size: 12px; }
.tip-strip { display: flex; align-items: center; gap: 8px; border-radius: 7px; padding: 8px 11px; font-size: 11.5px;
  margin-bottom: 10px; line-height: 1.6; }
.tip-strip.warn { background: #fffdf7; border: 1px solid var(--app-warn-border); color: var(--app-warn-text); }
.tip-strip.info { background: var(--app-info-weak, #f0f6ff); border: 1px solid #d6e4ff; color: var(--app-accent); }
.tip-strip .tip-link { margin-left: auto; flex: none; font-size: 11px; color: inherit; cursor: pointer;
  text-decoration: underline; }
.tiles { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.tile { border: 1px solid var(--app-border); background: var(--app-panel-2); border-radius: 8px; padding: 9px 11px; }
.tile .n { font-size: 18px; font-weight: 650; }
.tile .n small { font-size: 10px; color: var(--app-text3); margin-left: 2px; }
.tile .l { font-size: 10.5px; color: var(--app-text3); margin-top: 2px; }
.panel { border: 1px solid var(--app-border); border-radius: 8px; padding: 11px 13px; margin-top: 10px; }
.panel-h { display: flex; align-items: center; gap: 8px; font-size: 12px; font-weight: 600; color: var(--app-text2); }
.panel-h .sub { font-weight: 400; font-size: 10.5px; color: var(--app-text3); }
.panel-h .sp { flex: 1; }
.panel-h .lnk { font-size: 11px; color: var(--app-accent); font-weight: 400; cursor: pointer; }
.hbar { display: flex; height: 8px; border-radius: 999px; overflow: hidden; background: #eef0f3; margin-top: 9px; }
.hbar i { display: block; height: 100%; }
.hbar .ok { background: var(--app-ok); } .hbar .run { background: var(--app-warn); }
.hbar .err { background: var(--app-danger); } .hbar .off { background: #c9cfd6; }
.legend { display: flex; gap: 14px; margin-top: 7px; font-size: 10.5px; color: var(--app-text2); flex-wrap: wrap; }
.legend .d { width: 6px; height: 6px; border-radius: 50%; display: inline-block; margin-right: 4px; }
.legend .d.ok { background: var(--app-ok); } .legend .d.run { background: var(--app-warn); }
.legend .d.err { background: var(--app-danger); } .legend .d.off { background: #c9cfd6; }
.legend .muted { color: var(--app-text3); }
.trend { width: 100%; height: 132px; margin-top: 8px; display: block; }
.two-col { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 10px; }
.rank { display: flex; align-items: center; gap: 8px; font-size: 11.5px; margin-top: 7px; }
.rank .rk { width: 15px; height: 15px; border-radius: 4px; background: var(--app-panel-2); color: var(--app-text3);
  font-size: 9.5px; display: inline-flex; align-items: center; justify-content: center; flex: none; }
.rank .nm { width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--app-text2); }
.rank .bar { flex: 1; height: 5px; border-radius: 999px; background: #eef0f3; overflow: hidden; }
.rank .bar i { display: block; height: 100%; background: var(--app-accent); border-radius: 999px; }
.rank .ct { width: 30px; text-align: right; font-size: 10.5px; color: var(--app-text3); }
.un-row { display: flex; align-items: center; gap: 7px; padding: 7px 0; border-bottom: 1px dashed var(--app-border); }
.un-row:last-child { border-bottom: none; }
.un-row .q { flex: 1; min-width: 0; font-size: 11.5px; color: var(--app-text2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.un-row .add { flex: none; font-size: 10.5px; color: var(--app-accent); cursor: pointer; }
.act { display: flex; align-items: center; gap: 8px; font-size: 11.5px; padding: 5px 0; color: var(--app-text2); }
.act .dot { width: 6px; height: 6px; border-radius: 50%; flex: none; }
.act .dot.ok { background: var(--app-ok); } .act .dot.run { background: var(--app-warn); }
.act .dot.err { background: var(--app-danger); } .act .dot.off { background: #c9cfd6; }
.act-txt { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.act-tag { font-size: 9.5px; color: var(--app-text3); background: var(--app-panel-2); border-radius: 3px; padding: 1px 5px; }
.act .tm { font-size: 10.5px; color: var(--app-text3); flex: none; }
.mini-empty { padding: 16px 0; text-align: center; font-size: 11.5px; color: var(--app-text3); }
.doc-mini { display: flex; align-items: center; gap: 9px; padding: 8px 0; border-bottom: 1px solid var(--app-border); cursor: pointer; }
.doc-mini:last-child { border-bottom: none; }
.doc-mini:hover .doc-name { color: var(--app-accent); }
.doc-type { width: 32px; height: 24px; border-radius: 5px; font-size: 8.5px; font-weight: 600; flex: none;
  display: inline-flex; align-items: center; justify-content: center; letter-spacing: .02em; }
.doc-main { flex: 1; min-width: 0; }
.doc-name { font-size: 12px; color: var(--app-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.doc-meta { font-size: 10.5px; color: var(--app-text3); margin-top: 2px; }
.pill-mini { flex: none; font-size: 10px; padding: 2px 7px; border-radius: 999px; }
.pill-mini.ok { color: var(--app-ok); background: var(--app-ok-weak); }
.pill-mini.run { color: var(--app-warn-text); background: var(--app-warn-weak); }
.pill-mini.err { color: var(--app-danger); background: #fff2f0; }
.pill-mini.off { color: var(--app-text3); background: #eef0f3; }
.graph-stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-top: 10px; }
.graph-stats .gs { border: 1px solid var(--app-border); background: var(--app-panel-2); border-radius: 8px; padding: 9px 11px; }
.graph-stats .gs b { display: block; font-size: 18px; font-weight: 650; }
.graph-stats .gs span { font-size: 10.5px; color: var(--app-text3); }
.set-row { display: flex; align-items: center; gap: 12px; padding: 8px 0; border-bottom: 1px solid var(--app-border); font-size: 11.5px; }
.set-row:last-of-type { border-bottom: none; }
.set-row span { color: var(--app-text3); }
.set-row b { margin-left: auto; color: var(--app-text2); font-weight: 500; }
.set-note { font-size: 10.5px; color: var(--app-text3); line-height: 1.6; margin-top: 8px; }
.un-item { display: flex; align-items: flex-start; gap: 10px; padding: 10px 0; border-bottom: 1px dashed var(--app-border); }
.un-item:last-child { border-bottom: none; }
.un-main { flex: 1; min-width: 0; }
.un-q { font-size: 12px; color: var(--app-text); line-height: 1.5; }
.un-meta { display: flex; align-items: center; gap: 8px; margin-top: 4px; font-size: 10.5px; color: var(--app-text3); }
.un-acts { display: flex; gap: 6px; flex: none; }
.agent-row { display: flex; align-items: center; gap: 10px; padding: 9px 0; border-bottom: 1px solid var(--app-border); }
.agent-row:last-child { border-bottom: none; }
.agent-avatar { width: 28px; height: 28px; border-radius: 8px; background: var(--app-accent-weak); color: var(--app-accent);
  font-size: 12px; font-weight: 600; display: inline-flex; align-items: center; justify-content: center; flex: none; }
.agent-main { min-width: 0; }
.agent-name { font-size: 12px; color: var(--app-text); display: flex; align-items: center; gap: 6px; }
.agent-desc { font-size: 10.5px; color: var(--app-text3); margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
