<template>
  <!-- 工作流列表（个人资产）：编辑进画布、运行历史页、发布与版本、删除。
       布局对齐 ScheduledPanel 同款卡片风格。 -->
  <div class="app-page">
    <!-- 编辑态（editingId !== null）或运行历史态，整页替换列表；列表头/列表体随之隐藏 -->
    <template v-if="editingId === null && historyId === null">
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
              <!-- 状态用「色点 + 文字」：一眼分清发布态，不靠大色块 -->
              <span class="wf-dot" :class="r.status === 'published' ? 'dot-pub' : 'dot-draft'" />
              <span class="wf-name">{{ r.name }}</span>
              <span class="wf-status" :class="r.status === 'published' ? 'st-pub' : 'st-draft'">
                {{ r.status === 'published' ? (r.publishedVersion != null ? '已发布 v' + r.publishedVersion : '已发布') : '草稿' }}
              </span>
              <span class="wf-meta">{{ summary(r.dsl) }}</span>
              <!-- 最近一次运行：右侧对齐，扫一眼知道"这个工作流现在能不能跑" -->
              <span class="wf-last">
                <template v-if="r.lastRunStatus">
                  <span class="wf-run-dot" :class="'rd-' + r.lastRunStatus" />
                  <span class="wf-run-txt">{{ runLabel(r.lastRunStatus) }}</span>
                  <span class="wf-meta">{{ fmtTime(r.lastRunAt) }}</span>
                </template>
                <span v-else class="wf-meta">尚未运行</span>
              </span>
            </div>
            <div v-if="r.description" class="wf-desc">{{ r.description }}</div>
            <div class="wf-actions">
              <button class="app-btn ghost small" @click="openEditor(r.id)">
                <edit-outlined /> 编辑画布
              </button>
              <button class="app-btn ghost small" @click="openHistory(r)">
                <history-outlined /> 运行历史
              </button>
              <button class="app-btn ghost small" @click="openVersions(r)">
                <tags-outlined /> 版本
              </button>
              <!-- 发布/下线：已发布版本被冻结，草稿继续可改 -->
              <button v-if="r.status === 'published'" class="app-btn ghost small" :disabled="busyId === r.id" @click="doUnpublish(r)">
                <stop-outlined /> 下线
              </button>
              <button v-else class="app-btn small" :disabled="busyId === r.id" @click="doPublish(r)">
                <cloud-upload-outlined /> 发布
              </button>
              <button class="app-btn ghost small wf-del" @click="doDelete(r)">
                <delete-outlined /> 删除
              </button>
              <span class="wf-meta wf-upd">更新于 {{ fmtTime(r.updateTime) }}</span>
            </div>
          </div>
        </div>
      </a-spin>
    </div>
  </template>

    <!-- 画布编辑器：整页替换列表（返回即回列表并刷新） -->
    <FlowEditor v-if="editingId !== null" :workflow-id="editingId" @back="closeEditor" @saved="load" />

    <!-- 运行历史页（M4：独立页，含 trace 回放与待审批裁决） -->
    <WorkflowRunHistory v-if="historyId !== null" :workflow-id="historyId" :name="historyName" @back="closeHistory" />

    <!-- 版本历史（发布历史 + 回滚） -->
    <a-modal v-model:open="versionModal" :title="`版本历史${versionRow ? ' · ' + versionRow.name : ''}`" :footer="null" width="620px">
      <a-spin :spinning="versionLoading">
        <div v-if="!versions.length" class="head-hint-plain">还没有发布过版本（在画布里点「发布」即冻结第一版）</div>
        <div v-else class="wf-versions">
          <div v-for="v in versions" :key="v.version" class="wf-version">
            <div class="wf-version-head">
              <a-tag :color="v.current ? 'green' : 'default'">v{{ v.version }}{{ v.current ? ' · 当前' : '' }}</a-tag>
              <span class="wf-meta">{{ fmtTime(v.publishedAt) }}</span>
              <span class="wf-meta">{{ v.publishedBy || '' }}</span>
              <button v-if="!v.current" class="app-btn ghost small" :disabled="busyId === versionRow.id" @click="doRollback(v)">
                <rollback-outlined /> 回滚到此版
              </button>
            </div>
            <div v-if="v.note" class="wf-version-note">{{ v.note }}</div>
          </div>
        </div>
      </a-spin>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  PlusOutlined, EditOutlined, DeleteOutlined, HistoryOutlined, CloudUploadOutlined,
  StopOutlined, RollbackOutlined, TagsOutlined
} from '@ant-design/icons-vue'
import FlowEditor from './FlowEditor.vue'
import WorkflowRunHistory from './WorkflowRunHistory.vue'
import {
  listWorkflows, deleteWorkflow, publishWorkflow, unpublishWorkflow,
  listWorkflowVersions, rollbackWorkflow
} from '../api'

const rows = ref([])
const loading = ref(false)
const editingId = ref(null)     // null = 列表；'' = 新建；'id' = 编辑
const historyId = ref(null)     // 非 null = 运行历史页
const historyName = ref('')
const busyId = ref(null)        // 正在执行发布/下线/回滚的工作流 id
const versionModal = ref(false)
const versionLoading = ref(false)
const versionRow = ref(null)
const versions = ref([])

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
const openHistory = row => { historyId.value = row.id; historyName.value = row.name }
const closeHistory = () => { historyId.value = null; load() }

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

/** 发布：把库里的草稿冻结成一版（未发布的草稿改动画布不影响任何对外行为） */
const doPublish = async row => {
  busyId.value = row.id
  try {
    const r = await publishWorkflow(row.id, '')
    message.success(`已发布 v${r.data.version}（API 触发与智能体绑定将使用这一版）`)
    await load()
  } catch (e) {
    message.error('发布失败：' + (e.message || ''))
  } finally {
    busyId.value = null
  }
}

const doUnpublish = async row => {
  busyId.value = row.id
  try {
    await unpublishWorkflow(row.id)
    message.success('已下线：API 触发与智能体绑定随即不可用')
    await load()
  } catch (e) {
    message.error('下线失败：' + (e.message || ''))
  } finally {
    busyId.value = null
  }
}

const openVersions = async row => {
  versionRow.value = row
  versions.value = []
  versionModal.value = true
  versionLoading.value = true
  try {
    const r = await listWorkflowVersions(row.id)
    versions.value = (r && r.data) || []
  } catch (e) {
    message.error('版本历史加载失败：' + (e.message || ''))
  } finally {
    versionLoading.value = false
  }
}

/** 回滚 = 以该版本 DSL 再发一版（历史不被改写，可再次回滚） */
const doRollback = async v => {
  const row = versionRow.value
  Modal.confirm({
    title: `回滚到 v${v.version}？`,
    content: '会以该版本的 DSL 发布为新版本（版本号递增），当前已发布版本不受影响、仍可回滚回来。',
    okText: '回滚', cancelText: '取消',
    onOk: async () => {
      busyId.value = row.id
      try {
        const r = await rollbackWorkflow(row.id, v.version)
        message.success(`已回滚到 v${v.version}，发布为 v${r.data.version}`)
        versionModal.value = false
        await load()
      } catch (e) {
        message.error('回滚失败：' + (e.message || ''))
      } finally {
        busyId.value = null
      }
    }
  })
}

/** DSL 摘要：节点/边数量 */
const summary = dslText => {
  try {
    const d = JSON.parse(dslText)
    return `${(d.nodes || []).length} 节点 / ${(d.edges || []).length} 边`
  } catch (e) { return 'DSL 解析失败' }
}

const runLabel = s => ({ running: '运行中', success: '成功', failed: '失败', timeout: '超时', waiting_approval: '待审批' }[s] || s)
const runColor = s => ({ running: 'processing', success: 'green', failed: 'red', timeout: 'orange', waiting_approval: 'orange' }[s] || 'default')
const fmtTime = t => (t ? String(t).replace('T', ' ').slice(0, 16) : '—')

onMounted(load)
</script>

<style scoped>
.wf-list { display: flex; flex-direction: column; gap: 10px; }
.wf-card { display: flex; flex-direction: column; gap: 8px; transition: box-shadow .15s, border-color .15s; }
.wf-card:hover { box-shadow: 0 3px 12px rgba(0, 0, 0, .07); }
.wf-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
/* 状态：色点 + 文字（比大色块 tag 轻，扫读快） */
.wf-dot { width: 8px; height: 8px; border-radius: 50%; flex: none; }
.dot-pub { background: #52c41a; box-shadow: 0 0 0 3px rgba(82, 196, 26, .15); }
.dot-draft { background: #c9cdd4; box-shadow: 0 0 0 3px rgba(201, 205, 212, .18); }
.wf-name { font-weight: 500; font-size: 14px; }
.wf-status { font-size: 12px; color: var(--app-text2); }
.st-pub { color: #389e0d; }
.st-draft { color: var(--app-text3); }
.wf-meta { font-size: 12px; color: var(--app-text3); }
.wf-last { display: inline-flex; align-items: center; gap: 6px; margin-left: auto; font-size: 12px; color: var(--app-text3); }
.wf-run-dot { width: 7px; height: 7px; border-radius: 50%; flex: none; }
.rd-success { background: #52c41a; }
.rd-failed { background: #ff4d4f; }
.rd-running { background: #1677ff; }
.rd-timeout { background: #fa8c16; }
.rd-waiting_approval { background: #fa8c16; }
.wf-run-txt { color: var(--app-text2); }
.wf-desc { font-size: 13px; color: var(--app-text2); }
.wf-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; border-top: 1px dashed var(--app-border); padding-top: 8px; }
.wf-del { color: var(--app-danger, #d4380d); }
.wf-upd { margin-left: auto; }
.wf-empty { text-align: center; padding: 28px 16px; }
.wf-empty-title { margin: 0 0 6px; font-weight: 500; }
.wf-versions { display: flex; flex-direction: column; gap: 10px; }
.wf-version { border-bottom: 1px solid var(--app-border); padding-bottom: 8px; }
.wf-version:last-child { border-bottom: none; }
.wf-version-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.wf-version-note { font-size: 13px; color: var(--app-text2); margin-top: 4px; }
</style>
