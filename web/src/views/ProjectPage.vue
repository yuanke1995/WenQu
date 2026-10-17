<template>
  <div class="app-page">
    <div class="app-page-head">
      <h1 class="app-page-title">
        {{ form.name || (isImplicit ? '会话' : '未命名工作区') }}
        <span v-if="form.status === 'archived'" class="pj-tag">已归档</span>
      </h1>
      <span class="head-hint-plain" :title="isImplicit ? '' : HEAD_HINT">
        {{ isImplicit ? '这里的会话与文件还没有归到任何工作区下' : HEAD_HINT }}
      </span>
      <div class="art-tools">
        <a-input v-model:value="kw" allow-clear placeholder="搜索会话 / 文件 / 任务…" class="pj-search" />
        <button class="app-btn ghost" :disabled="loading" @click="load"><reload-outlined /> 刷新</button>
        <button class="app-btn" :disabled="isArchived" :title="isArchived ? '工作区已归档，恢复后才能新建对话' : ''" @click="goChat">
          <plus-outlined /> 新建对话
        </button>
      </div>
    </div>

    <div class="app-page-body">
      <a-skeleton v-if="firstLoad" active :paragraph="{ rows: 6 }" />

      <template v-else>
        <!-- ① 工作区约定：这个工作区区别于「一堆会话」的地方，常驻且就地编辑，不再藏在折叠里 -->
        <div class="app-card pj-card">
          <div class="pj-card-head">
            <span class="pj-label">工作区约定</span>
            <span class="pj-hint">写一次，这个工作区里之后的每条对话都按它执行</span>
            <span v-if="instrEditing && instrDirty" class="pj-dirty">未保存</span>
            <div class="pj-card-act">
              <template v-if="instrEditing">
                <button v-if="saved.instructions" class="app-btn ghost" :disabled="saving" @click="cancelInstr">取消</button>
                <button class="app-btn" :disabled="saving || !instrDirty" @click="saveInstr">
                  {{ saving ? '保存中…' : '保存约定' }}
                </button>
              </template>
              <button v-else class="app-btn ghost" @click="instrEdit = true">编辑</button>
            </div>
          </div>

          <a-textarea v-if="instrEditing" v-model:value="form.instructions" :rows="4" :maxlength="2000" show-count
                      placeholder="例如：回答先给结论；涉及考勤规则必须引用《考勤制度》原文条号；不猜测制度里没写的口径。" />
          <template v-else>
            <div class="pj-instr" :class="{ clamped: !instrFull }">{{ saved.instructions }}</div>
            <button v-if="instrLong" class="pj-link-btn" @click="instrFull = !instrFull">
              {{ instrFull ? '收起' : '展开全文' }}
            </button>
          </template>
        </div>

        <!-- ② 工作区设置：不常改的一行摘要，展开才给表单；删除这类破坏性操作也收在这里 -->
        <div class="app-card pj-card pj-card-tight">
          <button class="pj-fold-head" type="button" @click="settingsOpen = !settingsOpen">
            <span class="pj-label">工作区设置</span>
            <span class="pj-hint">{{ settingsSummary }}</span>
            <span v-if="settingsDirty" class="pj-dirty">未保存</span>
            <span class="pj-caret" :class="{ open: settingsOpen }">▾</span>
          </button>

          <div v-show="settingsOpen" class="pj-settings">
            <div class="pj-row">
              <span class="pj-label">工作区名</span>
              <a-input v-model:value="form.name" class="pj-name-input" :maxlength="60"
                       :placeholder="isImplicit ? '留空则显示「会话」' : ''" />
            </div>
            <div class="pj-row">
              <span class="pj-label">默认助手</span>
              <a-select v-model:value="form.defaultAgentId" class="pj-agent-select" allow-clear
                        placeholder="不设 = 跟随我的默认助手" :options="agentOptions" />
              <span class="pj-hint">助手带的知识范围与工具会一起生效</span>
            </div>
            <div class="pj-row">
              <span class="pj-label">工作目录</span>
              <code class="pj-path">{{ form.workdirPath || ('projects/' + id) }}</code>
              <button class="app-btn ghost" @click="copyPath"><copy-outlined /> 复制</button>
              <span class="pj-hint">这个工作区里写下的文件都在这里</span>
            </div>
            <div class="pj-row">
              <span class="pj-label">状态</span>
              <button v-if="!isImplicit" class="app-btn ghost" :disabled="archiving" @click="toggleArchive">
                {{ form.status === 'archived' ? '恢复' : '归档' }}
              </button>
              <span class="pj-hint">归档后收进侧栏折叠区：定时任务自动暂停，恢复时重新启用；会话与产物一条不动</span>
              <button v-if="!isImplicit" class="app-btn danger pj-del" :disabled="deleting" @click="removeProject">
                {{ deleting ? '删除中…' : '删除工作区' }}
              </button>
            </div>
            <div v-if="settingsDirty" class="pj-row">
              <button class="app-btn" :disabled="saving" @click="saveSettings">
                {{ saving ? '保存中…' : '保存设置' }}
              </button>
            </div>
          </div>
        </div>

        <!-- ③ 内容区：计数只在这里出现一次；产物并入文件（同一份内容的两个拷贝不再各列一遍） -->
        <a-tabs v-model:activeKey="tab" class="pj-tabs">
          <a-tab-pane key="files">
            <template #tab><span>文件 <span class="pj-count">{{ fileRows.length }}</span></span></template>
            <!-- 工具条：整区导出 + 截断提示（此前静默只列最近 300 个，看着像"就这些"） -->
            <div v-if="fileRows.length && !filesError" class="pj-filebar">
              <span v-if="filesTruncated" class="pj-hint">
                只列了最近 {{ fileRows.length }} 个（目录里共 {{ filesTotal }} 个），更早的可用导出打包查看
              </span>
              <button class="pj-act pj-filebar-btn" :disabled="exporting" @click="doExportZip">
                {{ exporting ? '导出中…' : '打包导出' }}
              </button>
            </div>
            <div v-if="filesError" class="pj-empty pj-err">
              {{ filesError }}
              <button class="pj-link-btn" @click="load">重试</button>
            </div>
            <div v-else-if="!filteredFiles.length" class="pj-empty">
              {{ kw ? '没有匹配的文件' : '还没有文件。让它在这个工作区里跑一段会写文件的命令，或把结果交付成产物，文件就会出现在这里。' }}
              <button v-if="!kw && !isArchived" class="pj-link-btn" @click="goChat">去对话</button>
            </div>
            <div v-else class="pj-file-list">
              <div v-for="f in filteredFiles" :key="f.key" class="pj-item">
                <span class="pj-item-title" :title="f.path || f.name">{{ f.name }}</span>
                <span class="pj-badge-cell">
                  <span v-if="f.artifact" class="pj-badge" title="已作为产物交付过（与这个文件是同一次产出的两份）">已交付</span>
                </span>
                <span class="pj-item-meta">{{ fmtSize(f.size) }}</span>
                <span class="pj-item-meta">{{ fmtAgo(f.at) }}</span>
                <button v-if="f.path && previewable(f)" class="pj-act" @click="openPreview(f)">预览</button>
                <button v-if="f.path" class="pj-act" @click="download(f)">下载</button>
                <a v-else :href="f.artifact.url" :download="f.name" class="pj-act">下载</a>
                <button v-if="f.path" class="pj-act pj-act-danger" title="删除这个文件" @click="askDeleteFile(f)">删除</button>
              </div>
            </div>
          </a-tab-pane>

          <a-tab-pane key="sessions">
            <template #tab><span>会话 <span class="pj-count">{{ sessionTotal }}</span></span></template>
            <div v-if="sessionTotal > sessions.length" class="pj-hint pj-tabnote">
              这里只列最近 {{ sessions.length }} 条（共 {{ sessionTotal }} 条），更早的在左侧栏这个工作区里翻
            </div>
            <div v-if="!filteredSessions.length" class="pj-empty">
              {{ kw ? '没有匹配的会话' : '还没有对话。' }}
              <button v-if="!kw && !isArchived" class="pj-link-btn" @click="goChat">新建一条对话</button>
              <span v-if="!kw">，就会落在这个工作区里，并自动带上上面的约定。</span>
            </div>
            <div v-else class="pj-file-list">
              <a v-for="s in filteredSessions" :key="s.id" class="pj-item" :href="'/chat?sid=' + s.id"
                 @click.prevent="openProjectSession(s)">
                <span class="pj-item-title">{{ s.title || '新对话' }}</span>
                <span class="pj-item-meta">{{ fmtAgo(toMs(s.updateTime)) }}</span>
              </a>
            </div>
          </a-tab-pane>

          <a-tab-pane key="auto">
            <template #tab><span>自动化 <span class="pj-count">{{ jobs.length + workflows.length }}</span></span></template>

            <div class="pj-block">
              <div class="pj-label">
                定时任务 <span class="pj-count">{{ jobs.length }}</span>
                <span class="pj-hint">到点自己跑，结果落进这个工作区</span>
                <button class="app-btn ghost pj-block-act" :disabled="isArchived"
                        :title="isArchived ? '工作区已归档，恢复后才能新建任务' : ''" @click="jobFormOpen = !jobFormOpen">
                  {{ jobFormOpen ? '收起' : '新建任务' }}
                </button>
              </div>
              <div v-if="jobFormOpen" class="pj-job-form">
                <a-input v-model:value="jobForm.name" placeholder="任务名，例如：每周一汇总上周工单" :maxlength="100" :disabled="isArchived" />
                <a-textarea v-model:value="jobForm.prompt" :rows="2" :maxlength="2000" :disabled="isArchived"
                            placeholder="每轮发给智能体的指令，例如：汇总上周新增工单，按处理人分组，输出一份 Markdown" />
                <div class="pj-row" style="margin-top:0">
                  <a-input v-model:value="jobForm.cron" placeholder="cron，例如 0 9 * * 1（每周一 9 点）" class="pj-cron-input" :disabled="isArchived" />
                  <button class="app-btn" :disabled="jobSaving || isArchived" @click="createJob">{{ jobSaving ? '创建中…' : '创建并启用' }}</button>
                </div>
              </div>
              <div v-if="!filteredJobs.length" class="pj-empty">
                {{ kw ? '没有匹配的定时任务' : '还没有定时任务。建一个，它就能每周自己跑一次。' }}
              </div>
              <div v-else class="pj-file-list">
                <div v-for="j in filteredJobs" :key="j.id" class="pj-item">
                  <span class="pj-item-title" :title="j.prompt">{{ j.name }}</span>
                  <code class="pj-cron">{{ j.cron }}</code>
                  <span class="pj-item-meta">{{ j.enabled ? ('下次 ' + fmtTime(j.nextRunAt)) : '已停用' }}</span>
                  <button class="pj-act" :disabled="isArchived" :title="isArchived ? '工作区已归档，恢复后才能试跑' : ''" @click="runJob(j)">试跑</button>
                </div>
              </div>
            </div>

            <div class="pj-block">
              <div class="pj-label">
                工作流 <span class="pj-count">{{ workflows.length }}</span>
                <span class="pj-hint">可视化编排，手动或定时触发</span>
                <a class="app-btn ghost pj-block-act" href="/agents?tab=workflow">去画布</a>
              </div>
              <div v-if="!filteredWorkflows.length" class="pj-empty">
                {{ kw ? '没有匹配的工作流' : '还没有工作流。在「智能体 → 工作流」里建一个，保存时把归属选成这个工作区，它就会出现在这里。' }}
              </div>
              <div v-else class="pj-file-list">
                <div v-for="w in filteredWorkflows" :key="w.id" class="pj-item">
                  <span class="pj-item-title">{{ w.name }}</span>
                  <span class="pj-item-meta">{{ w.status === 'published' ? '已发布' : '草稿' }}</span>
                  <span class="pj-item-meta">{{ fmtAgo(toMs(w.updateTime)) }}</span>
                  <button class="pj-act" :disabled="isArchived" :title="isArchived ? '工作区已归档，恢复后才能运行' : ''" @click="runWf(w)">运行</button>
                </div>
              </div>
            </div>
        </a-tab-pane>

        <a-tab-pane key="runs">
          <template #tab><span>运行 <span class="pj-count">{{ runs.length }}</span></span></template>
          <div v-if="runsError" class="pj-empty pj-err">
            {{ runsError }}
            <button class="pj-link-btn" @click="loadRuns">重试</button>
          </div>
          <div v-else-if="!filteredRuns.length" class="pj-empty">
            {{ kw ? '没有匹配的运行记录' : '还没有运行记录。定时任务到点自己跑、工作流手动或定时触发，记录都会落在这里。' }}
          </div>
          <div v-else class="pj-file-list">
            <div v-for="x in filteredRuns" :key="x.kind + ':' + x.id" class="pj-item">
              <span class="pj-item-title">
                <span class="run-kind">{{ x.kind === 'scheduled' ? '定时任务' : '工作流' }}</span>
                {{ x.name }}
                <span v-if="x.error" class="run-err-ic" :title="x.error">!</span>
              </span>
              <span class="run-status" :class="'rs-' + x.status">{{ runStatusText(x.status) }}</span>
              <span class="pj-item-meta">{{ runTriggerText(x.trigger) }}</span>
              <span class="pj-item-meta">{{ fmtAgo(toMs(x.startedAt)) }}</span>
              <span v-if="x.kind === 'scheduled' && x.sessionId" class="pj-act" @click="openRunSession(x.sessionId)">结果会话</span>
              <span v-else-if="x.kind === 'workflow'" class="pj-act" @click="openWfRun(x)">运行详情</span>
            </div>
          </div>
        </a-tab-pane>
      </a-tabs>
      </template>
    </div>

    <!-- 删除二选一：保留工作目录（默认）/ 连文件一起删；会话与产物不受影响 -->
    <DeleteProjectModal v-model:open="delOpen"
                        :project-name="form.name || '未命名工作区'"
                        files-known
                        :file-count="fileRows.length"
                        :total-size="fileTotalSize"
                        :saving="deleting"
                        @confirm="doRemoveProject" />

    <!-- 文件预览：文本纯文本展示（html/svg 也不渲染成页面）、图片按 blob URL 显示 -->
    <a-modal v-model:open="previewOpen" :title="previewState ? previewState.name : ''"
             :footer="null" width="min(860px, 92vw)" @after-close="closePreview">
      <pre v-if="previewState && previewState.kind === 'text'" class="pv-text">{{ previewState.text }}</pre>
      <div v-else-if="previewState && previewState.kind === 'image'" class="pv-img-wrap">
        <img :src="previewState.url" :alt="previewState.name" class="pv-img" />
      </div>
      <a-spin v-else :spinning="previewBusy" />
    </a-modal>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { ReloadOutlined, PlusOutlined, CopyOutlined } from '@ant-design/icons-vue'
import { getProject, updateProject, deleteProject, listArtifacts, listSessions, listAvailableAgents, listProjectFiles,
         downloadProjectFile, listScheduledJobs, createScheduledJob, runScheduledJob, listProjectWorkflows, runWorkflow,
         setProjectStatus, setNewSessionProject, getProjectRuns,
         previewProjectFile, exportProjectZip, deleteProjectFile } from '../api'
import DeleteProjectModal from '../components/DeleteProjectModal.vue'
import { sessionStore, rememberSession } from './store'

const HEAD_HINT = '一个工作目录 + 自己的约定 + 自己的助手；这里发生的一切都归到它名下'

const route = useRoute()
const router = useRouter()
/** 必须跟着路由走：/project/A → /project/B 复用同一个组件实例，取一次快照会让第二个工作区打不开 */
const id = computed(() => String(route.params.id || ''))
const loading = ref(false)
const saving = ref(false)
const deleting = ref(false)
const archiving = ref(false)
/** 删除二选一弹窗（保留文件 / 连文件一起删） */
const delOpen = ref(false)
/** 工作目录总大小：删除弹窗里说清「连文件一起删」删的是多少 */
const fileTotalSize = computed(() => fileRows.value.reduce((n, f) => n + (Number(f.size) || 0), 0))
const isImplicit = ref(false)
/** 归档=收工：新建对话/任务/运行一律禁用（后端同样拒绝，双保险；这里先给可见的反馈） */
const isArchived = computed(() => form.value.status === 'archived')
/** saved = 服务端当前值（读态显示它）；form = 编辑中的副本。分开才判得出「未保存」 */
const saved = ref({ instructions: '', defaultAgentId: '', name: '' })
const form = ref({ name: '', instructions: '', defaultAgentId: undefined, workdirPath: '', status: 'active' })
const sessions = ref([])
/** 会话总数（列表接口只返回首页 50 条，计数要用 total） */
const sessionTotal = ref(0)
const artifacts = ref([])
const files = ref([])
const filesError = ref('')
/** 目录实际文件数与是否截断（后端给的，别再把「只列最近 300 个」当成「就这些」） */
const filesTotal = ref(0)
const filesTruncated = ref(false)
const jobs = ref([])
const workflows = ref([])
const runs = ref([])
const runsError = ref('')
const jobFormOpen = ref(false)
const jobSaving = ref(false)
const jobForm = ref({ name: '', prompt: '', cron: '0 9 * * 1' })
const agents = ref([])
const kw = ref('')
const loadedOnce = ref(false)
const instrEdit = ref(false)
const instrFull = ref(false)
const settingsOpen = ref(false)
const tab = ref('files')

const agentOptions = computed(() => agents.value.map(a => ({ value: a.id, label: a.name })))
const firstLoad = computed(() => loading.value && !loadedOnce.value)
/** 没写过约定时直接给输入框：第一次进来最该做的事不该藏在「编辑」后面 */
const instrEditing = computed(() => instrEdit.value || !saved.value.instructions)
const instrDirty = computed(() => (form.value.instructions || '').trim() !== (saved.value.instructions || '').trim())
const instrLong = computed(() => (saved.value.instructions || '').length > 120)
const settingsDirty = computed(() => (form.value.name || '') !== saved.value.name
  || (form.value.defaultAgentId || '') !== (saved.value.defaultAgentId || ''))
const agentName = computed(() => {
  const a = agents.value.find(x => x.id === (form.value.defaultAgentId || saved.value.defaultAgentId))
  return a ? a.name : ''
})
const settingsSummary = computed(() =>
  `助手 ${agentName.value || '跟随我的'} · 名称 · 工作目录 · 归档与删除`)

const toMs = v => {
  if (v == null) return 0
  if (typeof v === 'number') return v
  const t = Date.parse(String(v).replace(' ', 'T'))
  return Number.isFinite(t) ? t : 0
}

/** 文件区 = 工作目录文件 ∪ 产物：产物是从工作目录复制出去的同一份东西，不再各列一遍 */
const fileRows = computed(() => {
  const rows = []
  const byName = new Map()
  for (const f of files.value) {
    const row = { key: 'f:' + f.path, name: f.name, path: f.path, size: f.size, at: toMs(f.mtime), artifact: null }
    rows.push(row)
    if (!byName.has(f.name)) byName.set(f.name, row)
  }
  for (const a of artifacts.value) {
    const base = String(a.filename || '').split('/').pop()
    const row = byName.get(base)
    if (row) { row.artifact = a; continue }
    rows.push({ key: 'a:' + a.id, name: base, path: '', size: a.size, at: toMs(a.createTime), artifact: a })
  }
  return rows.sort((x, y) => y.at - x.at)
})

/** 工作区内搜索：五类都过滤（此前漏了定时任务与工作流，搜任务名一片空白） */
const hit = (...texts) => {
  const q = kw.value.trim().toLowerCase()
  if (!q) return true
  return texts.some(t => String(t || '').toLowerCase().includes(q))
}
const filteredFiles = computed(() => fileRows.value.filter(f => hit(f.name, f.path)))
const filteredSessions = computed(() => sessions.value.filter(s => hit(s.title || '新对话')))
const filteredJobs = computed(() => jobs.value.filter(j => hit(j.name, j.prompt)))
const filteredWorkflows = computed(() => workflows.value.filter(w => hit(w.name)))
const filteredRuns = computed(() => runs.value.filter(x => hit(x.name, x.error)))

// ==================== 运行记录（定时任务执行 + 工作流运行合流） ====================
const RUN_STATUS_TEXT = {
  success: '成功', succeeded: '成功', failed: '失败', timeout: '超时',
  running: '进行中', queued: '排队中', waiting_approval: '待审核', skipped: '已跳过'
}
const RUN_TRIGGER_TEXT = { manual: '手动', schedule: '定时', scheduled: '定时', agent: '智能体', api: 'API' }
const runStatusText = s => RUN_STATUS_TEXT[s] || s || '—'
const runTriggerText = t => RUN_TRIGGER_TEXT[t] || t || '—'
const loadRuns = async () => {
  try {
    const r = await getProjectRuns(id.value)
    runs.value = (r && r.data) || []
    runsError.value = ''
  } catch (e) {
    runs.value = []
    runsError.value = e.message || '运行记录加载失败'
  }
}
/** 定时任务的结果会话（一任务一会话，历史都追加在里面） */
const openRunSession = sid => router.push({ path: '/chat', query: { sid } })
/** 工作流运行详情：智能体页工作流面板的运行历史深链（?wf=&run= 原有协议） */
const openWfRun = x => router.push('/agents?tab=workflow&wf=' + x.workflowId + '&run=' + x.id)

const fmtTime = s => (s ? String(s).replace('T', ' ').slice(0, 16) : '—')
/** 相对时间：同一天里的七八条用绝对日期根本分不出先后 */
const fmtAgo = ms => {
  const v = Number(ms)
  if (!v) return '—'
  const d = Date.now() - v
  if (d < 0) return fmtMtime(v)
  if (d < 60e3) return '刚刚'
  if (d < 3600e3) return Math.floor(d / 60e3) + ' 分钟前'
  if (d < 86400e3) return Math.floor(d / 3600e3) + ' 小时前'
  if (d < 7 * 86400e3) return Math.floor(d / 86400e3) + ' 天前'
  return fmtMtime(v)
}
const fmtMtime = ms => {
  if (!ms) return '—'
  const d = new Date(Number(ms))
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}
const fmtSize = n => {
  const v = Number(n) || 0
  if (v >= 1024 * 1024) return (v / 1024 / 1024).toFixed(2) + ' MB'
  if (v >= 1024) return (v / 1024).toFixed(1) + ' KB'
  return v + ' B'
}

const patch = async (body, okMsg) => {
  saving.value = true
  try {
    await updateProject(id.value, body)
    if (okMsg) message.success(okMsg)
    await load()
    return true
  } catch (e) {
    message.error('保存失败：' + (e.message || '请重试'))
    return false
  } finally {
    saving.value = false
  }
}

const saveInstr = async () => {
  const v = (form.value.instructions || '').trim()
  if (await patch({ instructions: v }, v ? '约定已保存，之后的对话会按它执行' : '约定已清空')) instrEdit.value = false
}
const cancelInstr = () => {
  form.value.instructions = saved.value.instructions
  instrEdit.value = false
}
const saveSettings = async () => {
  const name = (form.value.name || '').trim()
  if (!name && !isImplicit.value) { message.warning('工作区名不能为空'); return }
  await patch({ name, defaultAgentId: form.value.defaultAgentId || '' }, '设置已保存')
}

const copyPath = async () => {
  const p = form.value.workdirPath || ('projects/' + id.value)
  try {
    await navigator.clipboard.writeText(p)
    message.success('工作目录已复制')
  } catch (e) {
    message.error('复制失败，请手动选中复制')
  }
}

const toggleArchive = async () => {
  const next = form.value.status === 'archived' ? 'active' : 'archived'
  archiving.value = true
  try {
    await setProjectStatus(id.value, next)
    form.value.status = next
    message.success(next === 'archived' ? '已归档（会话与产物一条没动）' : '已恢复')
  } catch (e) {
    message.error('操作失败：' + (e.message || '请重试'))
  } finally {
    archiving.value = false
  }
}

const download = async f => {
  try {
    await downloadProjectFile(id.value, f.path, f.name)
  } catch (e) {
    message.error('下载失败：' + (e.message || '请重试'))
  }
}

// ==================== 文件：预览 / 打包导出 / 删除 ====================
/** 与后端 TEXT_PREVIEW_EXTS / IMAGE_PREVIEW_EXTS 同口径：其余类型不显示「预览」，免得点开才知道不行 */
const PREVIEW_TEXT = new Set(['md', 'markdown', 'txt', 'log', 'csv', 'tsv', 'json', 'yaml', 'yml', 'xml', 'toml', 'ini',
  'conf', 'properties', 'py', 'js', 'mjs', 'ts', 'jsx', 'tsx', 'java', 'go', 'rs', 'sql', 'sh', 'bash', 'zsh',
  'html', 'htm', 'css', 'scss', 'vue'])
const PREVIEW_IMAGE = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg'])
const extOf = name => {
  const dot = String(name || '').lastIndexOf('.')
  return dot < 0 ? '' : String(name).slice(dot + 1).toLowerCase()
}
const previewable = f => {
  const ext = extOf(f.name)
  return PREVIEW_TEXT.has(ext) || PREVIEW_IMAGE.has(ext)
}
const previewOpen = ref(false)
const previewBusy = ref(false)
const previewState = ref(null)   // {kind:'text'|'image', text?, url?, name}
const openPreview = async f => {
  previewBusy.value = true
  try {
    const r = await previewProjectFile(id.value, f.path)
    previewState.value = { ...r, name: f.name }
    previewOpen.value = true
  } catch (e) {
    message.error(e.message || '预览失败')
  } finally {
    previewBusy.value = false
  }
}
const closePreview = () => {
  // 图片预览走 blob:URL，不回收就是内存泄漏
  if (previewState.value && previewState.value.kind === 'image' && previewState.value.url) {
    URL.revokeObjectURL(previewState.value.url)
  }
  previewState.value = null
}
const exporting = ref(false)
const doExportZip = async () => {
  exporting.value = true
  try {
    await exportProjectZip(id.value)
    message.success('已导出')
  } catch (e) {
    message.error(e.message || '导出失败')
  } finally {
    exporting.value = false
  }
}
const askDeleteFile = f => Modal.confirm({
  title: `删除「${f.name}」？`,
  content: '工作区目录里的这个文件会被删掉，已交付为产物的副本不受影响。此操作不可恢复。',
  okText: '删除', okType: 'danger', cancelText: '取消',
  onOk: async () => {
    try {
      await deleteProjectFile(id.value, f.path)
      message.success('已删除')
      await load()
    } catch (e) {
      message.error('删除失败：' + (e.message || '请重试'))
    }
  }
})

/** 工作区页 → 会话：走 SPA 导航，并把这条会话先交给侧栏 store（标题/高亮不用等列表刷新） */
const openProjectSession = s => {
  rememberSession(s, id.value)
  router.push({ path: '/chat', query: { sid: s.id } })
}

const goChat = () => {
  // 走 SPA 导航而不是 window.location.href：整页刷新会丢掉模块级的「新会话落点」，
  // 旧实现于是只打开了最近一条对话（而不是新建）。先写落点、再发跨页新建信号，最后切路由。
  setNewSessionProject(id.value)
  sessionStore.newChatTick++
  router.push('/chat')
}

const loadJobs = async () => {
  try {
    const r = await listScheduledJobs(id.value)
    jobs.value = (r && r.data) || []
  } catch (e) {
    jobs.value = []
  }
}

const loadWorkflows = async () => {
  try {
    const r = await listProjectWorkflows(id.value)
    workflows.value = (r && r.data) || []
  } catch (e) {
    workflows.value = []
  }
}

const createJob = async () => {
  const f = jobForm.value
  if (!f.name.trim() || !f.prompt.trim() || !f.cron.trim()) {
    message.warning('任务名、指令、cron 都要填')
    return
  }
  jobSaving.value = true
  try {
    await createScheduledJob({
      name: f.name.trim(), prompt: f.prompt.trim(), cron: f.cron.trim(), enabled: 1, projectId: id.value
    })
    message.success('任务已创建并启用')
    jobForm.value = { name: '', prompt: '', cron: '0 9 * * 1' }
    jobFormOpen.value = false
    await loadJobs()
  } catch (e) {
    message.error('创建失败：' + (e.message || '请检查 cron 写法'))
  } finally {
    jobSaving.value = false
  }
}

const runJob = async j => {
  try {
    await runScheduledJob(j.id)
    message.success('已触发一次，结果会落进这个工作区的会话里（稍等片刻）')
    setTimeout(loadJobs, 4000)
    setTimeout(load, 6000)
  } catch (e) {
    message.error('触发失败：' + (e.message || '请重试'))
  }
}

const runWf = async w => {
  try {
    const r = await runWorkflow(w.id, {})
    message.success('已运行：' + ((r && r.data && r.data.status) || '完成'))
    await loadWorkflows()
  } catch (e) {
    message.error('运行失败：' + (e.message || '请检查工作流是否已发布'))
  }
}

const load = async () => {
  loading.value = true
  try {
    const r = await getProject(id.value)
    const p = (r && r.data) || {}
    isImplicit.value = Boolean(p.isImplicit)
    saved.value = {
      instructions: p.instructions || '',
      defaultAgentId: p.defaultAgentId || '',
      name: p.name || ''
    }
    form.value = {
      name: p.name || '',
      instructions: p.instructions || '',
      defaultAgentId: p.defaultAgentId || undefined,
      workdirPath: p.workdirPath || '',
      status: p.status || 'active'
    }
    instrEdit.value = false
    instrFull.value = false
    const [sr, ar] = await Promise.all([
      listSessions('', '', 50, id.value).catch(() => null),
      listArtifacts(undefined, id.value).catch(() => null)
    ])
    sessions.value = (sr && sr.data && sr.data.items) || []
    sessionTotal.value = (sr && sr.data && sr.data.total) || sessions.value.length
    artifacts.value = (ar && ar.data) || []
    // 文件单独取：宿主目录读不到（如沙盒在远端）时如实提示，不伪装成空列表
    try {
      const fr = await listProjectFiles(id.value)
      const d = (fr && fr.data) || {}
      files.value = d.entries || []
      filesTotal.value = d.total || 0
      filesTruncated.value = !!d.truncated
      filesError.value = ''
    } catch (e) {
      files.value = []
      filesTotal.value = 0
      filesTruncated.value = false
      filesError.value = e.message || '工作区目录暂时读不到'
    }
    await loadJobs()
    await loadWorkflows()
    await loadRuns()
    // 落点只在首次决定：文件是这个工作区的独占视图，有就先看它；空工作区则给「新建对话」的会话空态
    if (!loadedOnce.value) tab.value = fileRows.value.length ? 'files' : 'sessions'
    loadedOnce.value = true
  } catch (e) {
    message.error('工作区加载失败：' + (e.message || '请重试'))
  } finally {
    loading.value = false
  }
}

const removeProject = () => { delOpen.value = true }
/** 删除二选一：withFiles=false 保留工作目录（默认）；true 连文件一起删（后端先删文件、失败即整单失败） */
const doRemoveProject = async withFiles => {
  deleting.value = true
  try {
    await deleteProject(id.value, withFiles)
    message.success(withFiles ? '已删除，工作目录已一起删除' : '已删除')
    delOpen.value = false
    router.push('/chat')
  } catch (e) {
    message.error('删除失败：' + (e.message || '请重试'))
  } finally {
    deleting.value = false
  }
}

onMounted(async () => {
  load()
  try {
    const r = await listAvailableAgents()
    agents.value = (r && r.data) || []
  } catch (e) { agents.value = [] }
})

/**
 * 换工作区：路由复用同一个组件实例，onMounted 不会再跑，所以这里自己结算。
 * 先把上一个工作区的读态清干净再取新的——取不到时留下的必须是空，不能是别的工作区的约定与文件。
 */
watch(id, () => {
  loadedOnce.value = false
  kw.value = ''
  saved.value = { instructions: '', defaultAgentId: '', name: '' }
  form.value = { name: '', instructions: '', defaultAgentId: undefined, workdirPath: '', status: 'active' }
  sessions.value = []
  sessionTotal.value = 0
  artifacts.value = []
  files.value = []
  filesError.value = ''
  jobs.value = []
  workflows.value = []
  runs.value = []
  runsError.value = ''
  instrEdit.value = false
  instrFull.value = false
  settingsOpen.value = false
  jobFormOpen.value = false
  load()
})
</script>

<style scoped>
.pj-search { width: 220px; }
.pj-card { padding: var(--app-sp-4); margin-bottom: var(--app-sp-3); }
.pj-card-tight { padding: var(--app-sp-3) var(--app-sp-4); }
.pj-label { font-weight: 600; margin-bottom: var(--app-sp-2); display: flex; align-items: center; gap: var(--app-sp-2); }
.pj-hint { font-weight: 400; font-size: var(--app-fs-sm); color: var(--app-label-tertiary); }
.pj-row { display: flex; align-items: center; gap: var(--app-sp-2); margin-top: var(--app-sp-3); flex-wrap: wrap; }
.pj-row:first-child { margin-top: 0; }
.pj-row .pj-label { margin-bottom: 0; }

/* 卡片标题行：标题 + 说明靠左、动作靠右，避免动作与说明挤在一起 */
.pj-card-head { display: flex; align-items: center; gap: var(--app-sp-2); flex-wrap: wrap; margin-bottom: var(--app-sp-2); }
.pj-card-head .pj-label { margin-bottom: 0; }
.pj-card-act { margin-left: auto; display: flex; gap: var(--app-sp-2); }
/* 未保存留痕：编辑区在卡片里，页头按钮看不到，就地提示 */
.pj-dirty {
  flex-shrink: 0; font-size: var(--app-fs-xs); line-height: 18px; padding: 0 var(--app-sp-2);
  border-radius: 999px; background: var(--app-warn-weak); color: var(--app-warn-text);
}

/* 约定读态：保留换行的纯文本，超长先夹三行 */
.pj-instr {
  white-space: pre-wrap; word-break: break-word; font-size: var(--app-fs-md); line-height: 1.7;
  color: var(--app-label-primary);
}
.pj-instr.clamped {
  display: -webkit-box; -webkit-line-clamp: 3; line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden;
}

/* ---- 工作区设置折叠 ---- */
.pj-fold-head {
  display: flex; align-items: center; gap: var(--app-sp-2); width: 100%;
  background: transparent; border: 0; padding: 0; cursor: pointer; font: inherit; text-align: left;
}
.pj-fold-head .pj-label { margin-bottom: 0; }
.pj-caret { margin-left: auto; color: var(--app-label-tertiary); transition: transform .15s; }
.pj-caret.open { transform: rotate(180deg); }
.pj-settings { margin-top: var(--app-sp-3); padding-top: var(--app-sp-3); border-top: 1px solid var(--app-border-l1); }
.pj-agent-select { min-width: 240px; }
.pj-name-input { max-width: 320px; }
.pj-cron-input { max-width: 260px; }
.pj-del { margin-left: auto; }
.pj-path {
  font-size: var(--app-fs-sm); color: var(--app-label-secondary); max-width: 420px;
  background: var(--app-bg-layer-2); padding: 1px 5px; border-radius: var(--app-radius-sm);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

/* ---- 内容区 ---- */
/* 运行记录行：来源徽章 + 状态徽章（成功/失败/进行中/待审核各一色，均为弱底不抢标题） */
.run-kind {
  flex: none; font-size: 11px; line-height: 16px; padding: 0 5px; margin-right: 6px; border-radius: 4px;
  background: var(--app-bg-layer-2); color: var(--app-label-secondary);
}
.run-status {
  flex: none; min-width: 44px; text-align: center; font-size: 11px; line-height: 16px; padding: 0 5px;
  border-radius: 4px; background: var(--app-bg-layer-2); color: var(--app-label-secondary);
}
.rs-success, .rs-succeeded { background: var(--app-ok-weak); color: var(--app-ok); }
.rs-failed, .rs-timeout { background: var(--app-danger-weak); color: var(--app-danger-text); }
.rs-running, .rs-queued { background: var(--app-accent-weak); color: var(--app-accent); }
.rs-waiting_approval { background: var(--app-warn-weak); color: var(--app-warn-text); }
.run-err-ic {
  display: inline-flex; align-items: center; justify-content: center; width: 14px; height: 14px;
  margin-left: 6px; border-radius: 50%; background: var(--app-danger-weak); color: var(--app-danger-text);
  font-size: 10px; cursor: help; vertical-align: middle;
}
.pj-tabs { margin-top: var(--app-sp-1); }
.pj-block { margin-bottom: var(--app-sp-4); }
.pj-block:last-child { margin-bottom: 0; }
.pj-block-act { margin-left: auto; }
.pj-tabnote { margin-bottom: var(--app-sp-2); }
/* 整行可点：命中区铺满，行 hover 有反馈（此前只有文字那几个字能点） */
.pj-item {
  display: flex; align-items: center; gap: var(--app-sp-2); padding: var(--app-sp-2) var(--app-sp-1);
  border-bottom: 1px dashed var(--app-border-l1); color: inherit; border-radius: var(--app-radius-sm);
  margin: 0 calc(-1 * var(--app-sp-1));
}
.pj-file-list { border-top: 1px solid var(--app-border-l1); }
.pj-file-list .pj-item:last-child { border-bottom: none; }
.pj-item:hover { background: var(--app-interactive-hover); }
.pj-item-title { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pj-item-meta { font-size: var(--app-fs-sm); color: var(--app-label-tertiary); flex-shrink: 0; min-width: 72px; text-align: right; }
/* 角标占固定宽：不占位的行会把「大小 / 时间」整列拽偏，一屏之内看着像没对齐 */
.pj-badge-cell { flex: none; width: 54px; display: flex; justify-content: flex-end; }
.pj-badge {
  font-size: var(--app-fs-xs); line-height: 18px; padding: 0 var(--app-sp-1);
  border-radius: var(--app-radius-sm); background: var(--app-accent-weak); color: var(--app-accent); cursor: help;
}
.pj-cron {
  flex-shrink: 0; font-size: var(--app-fs-xs); color: var(--app-label-secondary);
  background: var(--app-bg-layer-2); padding: 1px 5px; border-radius: var(--app-radius-sm);
}
/* 行内动作：看着像链接，实际是按钮（Tab 能到、回车能触发、聚焦有环） */
.pj-act {
  flex-shrink: 0; border: 0; background: transparent; cursor: pointer; padding: 0 2px;
  color: var(--app-accent); font-size: var(--app-fs-sm); font-family: inherit;
}
.pj-act:hover { text-decoration: underline; text-underline-offset: 2px; }
.pj-link-btn {
  border: 0; background: transparent; padding: 0 2px; cursor: pointer;
  color: var(--app-accent); font-size: inherit; font-family: inherit;
  text-decoration: underline; text-underline-offset: 2px;
}
.pj-job-form { display: flex; flex-direction: column; gap: var(--app-sp-2); margin-bottom: var(--app-sp-3); }
.pj-empty { font-size: var(--app-fs-md); color: var(--app-label-tertiary); padding: var(--app-sp-2) 0; }
.pj-err { color: var(--app-danger-text); }
.pj-count {
  font-size: var(--app-fs-xs); line-height: 16px; padding: 0 var(--app-sp-1);
  border-radius: var(--app-radius-sm); background: var(--app-accent-weak); color: var(--app-accent);
}
.pj-tag {
  font-size: var(--app-fs-xs); font-weight: 400; padding: 1px 6px; border-radius: var(--app-radius-sm);
  background: var(--app-accent-weak); color: var(--app-accent); vertical-align: middle;
}

/* 文件区工具条：截断提示 + 打包导出 */
.pj-filebar { display: flex; align-items: center; gap: var(--app-sp-2); margin-bottom: var(--app-sp-2); }
.pj-filebar-btn { margin-left: auto; flex: none; padding: 4px 6px; }
.pj-act-danger { color: var(--app-danger); }
/* 预览：文本按纯文本铺（等宽 + 可横向滚动），图片按内容宽度不放大 */
.pv-text {
  margin: 0; padding: var(--app-sp-3); max-height: 62vh; overflow: auto;
  background: var(--app-bg-layer-2); border-radius: var(--app-radius-sm);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; line-height: 1.7; white-space: pre-wrap;
  word-break: break-word; color: var(--app-label-primary);
}
.pv-img-wrap { display: flex; justify-content: center; max-height: 62vh; overflow: auto; }
.pv-img { max-width: 100%; height: auto; }

/* ==================== 窄屏（手机）：/project 已进守卫白名单，这里做真适配 ====================
   卡片列表形态天然适合竖排：行内元素允许换行、输入类控件收回全宽、元信息列压缩；
   页头两组工具各自占一行（.app-page-head 的全局折行兜底 + 搜索框吃掉剩余宽度）。 */
@media (max-width: 768px) {
  .pj-search { flex: 1; width: auto; min-width: 0; }
  /* 短标签不许折行：窄屏下「工作区设置」折成两行很难看（内容挤的是摘要，不该挤标题） */
  .pj-label { white-space: nowrap; }
  .pj-fold-head .pj-hint { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  /* 文件/会话/任务/运行行：标题与操作换行后仍对齐，元信息不再占固定 72px */
  .pj-item { flex-wrap: wrap; row-gap: 4px; }
  .pj-item-title { flex: 1 1 100%; order: -1; }
  .pj-item-meta { min-width: 0; }
  .pj-badge-cell { width: auto; margin-left: auto; }
  .pj-act { padding: 6px 4px; }   /* 触摸热区：行内小按钮加到可点尺寸 */
  /* 表单类控件收回全宽（cron/名称/助手选择原本都是定宽横排） */
  .pj-cron-input, .pj-name-input, .pj-agent-select { max-width: none; width: 100%; }
  .pj-path { max-width: 100%; }
  .pj-job-form .pj-row { flex-wrap: wrap; row-gap: 8px; }
  /* 删除弹窗选项说明文字在窄屏占满 */
  .dp-item .dp-d { white-space: normal; }
}
</style>
