<template>
  <!-- 工作流列表（个人资产，M0~M3 仅创建者可见可管）：编辑进画布、运行历史回放、删除。
       布局对齐 ScheduledPanel 同款卡片风格。 -->
  <div class="app-page">
    <!-- 编辑态（editingId !== null）只渲染 FlowEditor 整页替换列表；列表头/列表体随编辑态隐藏 -->
    <template v-if="editingId === null">
    <div class="app-page-head">
      <h1 class="app-page-title">工作流</h1>
      <span class="head-hint-plain">把「检索 → LLM → 条件 → 输出」画成一张图：DSL 是唯一真源，画布只是编辑器</span>
      <button class="app-btn" style="margin-left:auto" @click="openEditor('')">
        <plus-outlined /> 新建工作流
      </button>
    </div>

    <div class="app-page-body">
      <a-spin :spinning="loading">
        <div v-if="!rows.length" class="app-card wf-empty">
          <p class="wf-empty-title">还没有工作流</p>
          <p class="head-hint-plain">
            例如「用户提问 → 知识库检索 → LLM 回答 → 按回答长度路由」——纯鼠标搭建，点运行即可调试，不用写任何 JSON
          </p>
        </div>

        <div v-else class="wf-list">
          <div v-for="r in rows" :key="r.id" class="app-card wf-card">
            <div class="wf-head">
              <span class="wf-name">{{ r.name }}</span>
              <a-tag :color="r.status === 'published' ? 'green' : 'default'">{{ r.status === 'published' ? '已发布' : '草稿' }}</a-tag>
              <span class="wf-meta">{{ summary(r.dsl) }}</span>
            </div>
            <div v-if="r.description" class="wf-desc">{{ r.description }}</div>
            <div class="wf-actions">
              <button class="app-btn ghost small" @click="openEditor(r.id)">
                <edit-outlined /> 编辑画布
              </button>
              <button class="app-btn ghost small" @click="openHistory(r)">
                <history-outlined /> 运行历史
              </button>
              <button class="app-btn ghost small wf-del" @click="doDelete(r)">
                <delete-outlined /> 删除
              </button>
              <span class="wf-meta" style="margin-left:auto">更新于 {{ fmtTime(r.updateTime) }}</span>
            </div>
          </div>
        </div>
      </a-spin>
    </div>
  </template>

    <!-- 画布编辑器：整页替换列表（返回即回列表并刷新） -->
    <FlowEditor v-if="editingId !== null" :workflow-id="editingId" @back="closeEditor" @saved="load" />

    <!-- 运行历史（列表页入口）：点击单条回放节点级 trace -->
    <a-modal v-model:open="historyModal" :title="`运行历史${historyRow ? ' · ' + historyRow.name : ''}`" :footer="null" width="760px">
      <a-spin :spinning="historyLoading">
        <div v-if="!runs.length" class="head-hint-plain">还没有运行记录</div>
        <div v-else class="wf-runs">
          <div v-for="x in runs" :key="x.id" class="wf-run">
            <div class="wf-run-head" @click="openDetail(x)">
              <a-tag :color="runColor(x.status)">{{ runLabel(x.status) }}</a-tag>
              <span class="wf-meta">{{ x.triggerType === 'manual' ? '手动' : x.triggerType }}</span>
              <span class="wf-meta">{{ fmtTime(x.startedAt) }}</span>
              <span class="wf-meta">{{ x.durationMs != null ? fmtMs(x.durationMs) : '' }}</span>
              <a class="wf-run-link">节点 trace →</a>
            </div>
            <div v-if="x.error" class="wf-run-err">{{ x.error }}</div>
          </div>
        </div>
      </a-spin>
      <!-- trace 详情 -->
      <a-modal v-model:open="detailModal" title="运行详情" :footer="null" width="640px">
        <a-spin :spinning="detailLoading">
          <template v-if="detail">
            <div class="wf-run-head" style="margin-bottom:10px">
              <a-tag :color="runColor(detail.status)">{{ runLabel(detail.status) }}</a-tag>
              <span class="wf-meta">{{ fmtTime(detail.startedAt) }}</span>
              <span class="wf-meta">{{ detail.durationMs != null ? fmtMs(detail.durationMs) : '' }}</span>
            </div>
            <div v-if="detail.error" class="wf-run-err" style="margin-bottom:10px">{{ detail.error }}</div>
            <div class="wf-block"><div class="wf-block-label">入参</div>
              <pre class="wf-pre">{{ pretty(detail.inputs) }}</pre></div>
            <div class="wf-block"><div class="wf-block-label">出参</div>
              <pre class="wf-pre">{{ pretty(detail.outputs) }}</pre></div>
            <div class="wf-block"><div class="wf-block-label">节点 trace</div>
              <pre class="wf-pre">{{ prettyTrace(detail.nodeTraces) }}</pre></div>
          </template>
        </a-spin>
      </a-modal>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { PlusOutlined, EditOutlined, DeleteOutlined, HistoryOutlined } from '@ant-design/icons-vue'
import FlowEditor from './FlowEditor.vue'
import { listWorkflows, deleteWorkflow, listWorkflowRuns, getWorkflowRun } from '../api'

const rows = ref([])
const loading = ref(false)
const editingId = ref(null)     // null = 列表；'' = 新建；'id' = 编辑
const historyModal = ref(false)
const historyRow = ref(null)
const historyLoading = ref(false)
const runs = ref([])
const detailModal = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

const load = async () => {
  loading.value = true
  try {
    const r = await listWorkflows()
    rows.value = (r && r.data) || []
  } catch (e) {
    message.error('工作流加载失败：' + (e.message || '请刷新重试'))
    rows.value = []
  } finally {
    loading.value = false
  }
}

const openEditor = id => { editingId.value = id }
const closeEditor = () => { editingId.value = null; load() }

const doDelete = row => {
  Modal.confirm({
    title: '删除该工作流？',
    content: '工作流定义会被删除；已产生的运行记录会保留（回放与审计价值独立于定义存在）。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      try {
        const r = await deleteWorkflow(row.id)
        if (r && r.success) { message.success('已删除'); await load() }
        else message.error((r && r.msg) || '删除失败')
      } catch (e) {
        message.error('删除失败：' + (e.message || ''))
      }
    }
  })
}

const openHistory = async row => {
  historyRow.value = row
  runs.value = []
  historyModal.value = true
  historyLoading.value = true
  try {
    const r = await listWorkflowRuns(row.id)
    runs.value = (r && r.data) || []
  } catch (e) {
    message.error('运行历史加载失败：' + (e.message || ''))
  } finally {
    historyLoading.value = false
  }
}

const openDetail = async row => {
  detailModal.value = true
  detailLoading.value = true
  detail.value = null
  try {
    const r = await getWorkflowRun(historyRow.value.id, row.id)
    detail.value = r.data || null
  } catch (e) {
    message.error('运行详情加载失败：' + (e.message || ''))
  } finally {
    detailLoading.value = false
  }
}

/** DSL 摘要：节点/边数量 */
const summary = dslText => {
  try {
    const d = JSON.parse(dslText)
    return `${(d.nodes || []).length} 节点 / ${(d.edges || []).length} 边`
  } catch (e) { return 'DSL 解析失败' }
}

const pretty = s => {
  try { return JSON.stringify(JSON.parse(s), null, 2) } catch (e) { return s || '（无）' }
}
const prettyTrace = s => {
  const arr = (() => { try { return JSON.parse(s) } catch (e) { return null } })()
  if (!Array.isArray(arr)) return s || '（无）'
  return arr.map(t => {
    const parts = [`[${t.status}] ${t.nodeId}(${t.type}) 耗时 ${t.elapsedMs}ms`]
    if (t.promptTokens != null) parts[0] += ` in ${t.promptTokens}tok`
    if (t.completionTokens != null) parts[0] += ` out ${t.completionTokens}tok`
    if (t.error) parts.push(`  错误：${t.error}`)
    if (t.output) parts.push(`  输出：${JSON.stringify(t.output).slice(0, 200)}`)
    return parts.join('\n')
  }).join('\n\n')
}

const runLabel = s => ({ running: '运行中', success: '成功', failed: '失败', timeout: '超时', waiting_approval: '待审批' }[s] || s)
const runColor = s => ({ running: 'processing', success: 'green', failed: 'red', timeout: 'orange', waiting_approval: 'orange' }[s] || 'default')
const fmtTime = t => (t ? String(t).replace('T', ' ').slice(0, 16) : '—')
const fmtMs = ms => (ms == null ? '' : ms >= 1000 ? (ms / 1000).toFixed(1) + ' s' : ms + ' ms')

onMounted(load)
</script>

<style scoped>
.wf-list { display: flex; flex-direction: column; gap: 10px; }
.wf-card { display: flex; flex-direction: column; gap: 8px; }
.wf-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.wf-name { font-weight: 500; font-size: 14px; }
.wf-meta { font-size: 12px; color: var(--app-text3); }
.wf-desc { font-size: 13px; color: var(--app-text2); }
.wf-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.wf-del { color: var(--app-danger, #d4380d); }
.wf-empty { text-align: center; padding: 28px 16px; }
.wf-empty-title { margin: 0 0 6px; font-weight: 500; }
.wf-runs { display: flex; flex-direction: column; gap: 10px; }
.wf-run { border-bottom: 1px solid var(--app-border); padding-bottom: 8px; }
.wf-run:last-child { border-bottom: none; }
.wf-run-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; cursor: pointer; }
.wf-run-link { font-size: 12px; color: var(--app-accent); margin-left: auto; }
.wf-run-err { margin-top: 6px; font-size: 13px; color: var(--app-danger, #d4380d); }
.wf-block { margin-bottom: 10px; }
.wf-block-label { font-size: 12px; color: var(--app-text3); margin-bottom: 4px; }
.wf-pre {
  background: var(--app-panel); border-radius: 6px; padding: 8px 10px; margin: 0;
  font-size: 12px; max-height: 240px; overflow: auto; white-space: pre-wrap; word-break: break-all;
}
</style>
