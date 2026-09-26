<template>
  <div class="app-page">
    <div class="app-page-head">
      <h1 class="app-page-title">定时任务</h1>
      <span class="head-hint-plain">到点自动用「智能体 + 指令」跑一次完整问答，结果落进该任务的专属会话</span>
      <button class="app-btn" style="margin-left:auto" @click="openCreate">
        <plus-outlined /> 新建任务
      </button>
    </div>

    <div class="app-page-body">
      <a-spin :spinning="loading">
        <div v-if="!rows.length" class="app-card sched-empty">
          <p class="sched-empty-title">还没有定时任务</p>
          <p class="head-hint-plain">
            例如「工作日 9 点让助手汇总知识库里的新增内容」——到点自动执行，结果可在执行历史与该任务的专属会话里查看
          </p>
        </div>

        <div v-else class="sched-list">
          <div v-for="r in rows" :key="r.id" class="app-card sched-card">
            <div class="sched-head">
              <span class="sched-name">{{ r.name }}</span>
              <a-tag :color="r.enabled ? 'green' : 'default'">{{ r.enabled ? '已启用' : '已停用' }}</a-tag>
              <a-tag v-if="r.lastRun" :color="runColor(r.lastRun.status)">{{ runLabel(r.lastRun.status) }}</a-tag>
              <span v-if="r.agentName" class="sched-meta">智能体：{{ r.agentName }}</span>
              <span class="sched-meta">cron：{{ r.cron }}（{{ r.timezone }}）</span>
              <span class="sched-meta">
                下次：{{ r.enabled ? (r.nextRunAt ? fmt(r.nextRunAt) : '—') : '已停用' }}
                <template v-if="r.enabled && r.nextRunAt">（{{ humanNext(r.nextRunAt) }}）</template>
              </span>
            </div>
            <div class="sched-prompt">{{ r.prompt }}</div>
            <div class="sched-actions">
              <button class="app-btn ghost small" :disabled="busyId === r.id" @click="doRun(r)">
                <play-circle-outlined /> 立即执行
              </button>
              <button class="app-btn ghost small" @click="doToggle(r)">{{ r.enabled ? '停用' : '启用' }}</button>
              <button class="app-btn ghost small" @click="openEdit(r)">编辑</button>
              <button class="app-btn ghost small" @click="openRuns(r)">
                <history-outlined /> 执行历史
              </button>
              <button class="app-btn ghost small sched-del" @click="doDelete(r)">
                <delete-outlined /> 删除
              </button>
              <a v-if="r.sessionId" class="sched-link" @click="openSession(r.sessionId)">结果会话 →</a>
            </div>
          </div>
        </div>
      </a-spin>
    </div>

    <a-modal v-model:open="showEdit" :title="editing ? '编辑定时任务' : '新建定时任务'"
             :confirm-loading="saving" ok-text="保存" cancel-text="取消" width="640px" @ok="save">
      <a-form :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }">
        <a-form-item label="任务名称" required>
          <a-input v-model:value="form.name" :maxlength="100" placeholder="如：每早知识库巡检" />
        </a-form-item>
        <a-form-item label="智能体">
          <a-select v-model:value="form.agentId" style="width:100%" allow-clear
                    placeholder="不指定则用当前生效的默认智能体" :options="agentOptions" />
        </a-form-item>
        <a-form-item label="执行指令" required>
          <a-textarea v-model:value="form.prompt" :rows="3" :maxlength="2000"
                      placeholder="每轮发给智能体的内容，如：汇总知识库里最近新增文档的要点，列成清单" />
        </a-form-item>
        <a-form-item label="执行周期" required>
          <div class="sched-cron-row">
            <a-input v-model:value="form.cron" placeholder="5 段：分 时 日 月 周，如 0 9 * * 1-5" />
            <a-select style="width:180px" placeholder="常用周期" :options="cronPresetOptions"
                      :value="''" @select="pickCron" />
          </div>
          <div class="sched-hint">5 段写法（分 时 日 月 周）：<code>0 9 * * 1-5</code> = 工作日 9 点；
            <code>*/30 * * * *</code> = 每 30 分钟；<code>0 18 * * 5</code> = 每周五 18 点。保存后按左侧「下次」显示实际触发时间。</div>
        </a-form-item>
        <a-form-item label="时区">
          <a-select v-model:value="form.timezone" style="width:100%" :options="zoneOptions" />
        </a-form-item>
        <a-form-item label="模型">
          <ModelSelect v-model="form.modelRef" type="chat" width="100%" inherit-label="跟随个人默认模型" />
        </a-form-item>
        <a-form-item label="深度思考">
          <a-switch v-model:checked="form.deepThink" />
          <span class="sched-hint" style="margin-left:8px">开启后每轮先思考再检索（更慢，适合需要拆解的复杂问题）</span>
        </a-form-item>
        <a-form-item label="启用">
          <a-switch v-model:checked="form.enabled" />
          <span class="sched-hint" style="margin-left:8px">停用后不再触发（不会补跑停用期间错过的轮次）</span>
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="showRuns" :title="`执行历史${runsJob ? ' · ' + runsJob.name : ''}`" :footer="null" width="760px">
      <a-spin :spinning="runsLoading">
        <div v-if="!runs.length" class="head-hint-plain">还没有执行记录</div>
        <div v-else class="sched-runs">
          <div v-for="x in runs" :key="x.id" class="sched-run">
            <div class="sched-run-head">
              <a-tag :color="runColor(x.status)">{{ runLabel(x.status) }}</a-tag>
              <span class="sched-meta">{{ x.trigger === 'manual' ? '手动' : '定时' }}</span>
              <span class="sched-meta">{{ fmt(x.startedAt) }}</span>
              <span class="sched-meta">{{ duration(x) }}</span>
              <a v-if="x.sessionId" class="sched-link" @click="openSession(x.sessionId)">查看会话 →</a>
            </div>
            <div v-if="x.error" class="sched-err">{{ x.error }}</div>
            <div v-else-if="x.answer" class="sched-preview">{{ x.answer }}</div>
            <div v-else class="head-hint-plain">（该轮无内容）</div>
          </div>
        </div>
      </a-spin>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { PlusOutlined, PlayCircleOutlined, DeleteOutlined, HistoryOutlined } from '@ant-design/icons-vue'
import ModelSelect from '../components/ModelSelect.vue'
import {
  listScheduledJobs, createScheduledJob, updateScheduledJob, deleteScheduledJob,
  toggleScheduledJob, runScheduledJob, listScheduledRuns, listAvailableAgents
} from '../api'

const router = useRouter()

const rows = ref([])
const loading = ref(false)
const saving = ref(false)
const busyId = ref('')
const showEdit = ref(false)
const editing = ref(null)
const showRuns = ref(false)
const runsJob = ref(null)
const runs = ref([])
const runsLoading = ref(false)
const agentOptions = ref([])

const blank = () => ({
  name: '', agentId: '', prompt: '', cron: '0 9 * * 1-5',
  timezone: 'Asia/Shanghai', modelRef: '', deepThink: false, enabled: true
})
const form = ref(blank())

// 常用周期预设（点选即填 cron；不用 Dropdown——它在合成事件下打不开，Select 更稳）
const CRON_PRESETS = [
  { value: '0 9 * * 1-5', label: '工作日 9 点（0 9 * * 1-5）' },
  { value: '0 9 * * *', label: '每天 9 点（0 9 * * *）' },
  { value: '*/30 * * * *', label: '每 30 分钟（*/30 * * * *）' },
  { value: '0 * * * *', label: '每小时（0 * * * *）' },
  { value: '0 18 * * 5', label: '每周五 18 点（0 18 * * 5）' },
  { value: '0 8 1 * *', label: '每月 1 号 8 点（0 8 1 * *）' }
]
const cronPresetOptions = CRON_PRESETS.map(p => ({ value: p.value, label: p.label }))
const zoneOptions = [
  { value: 'Asia/Shanghai', label: 'Asia/Shanghai（北京）' },
  { value: 'Asia/Tokyo', label: 'Asia/Tokyo（东京）' },
  { value: 'UTC', label: 'UTC' },
  { value: 'America/New_York', label: 'America/New_York（纽约）' }
]

const pickCron = v => { form.value.cron = v }

const load = async () => {
  loading.value = true
  try {
    const r = await listScheduledJobs()
    rows.value = (r && r.data) || []
  } catch (e) {
    message.error('定时任务加载失败：' + (e.message || '请刷新重试'))
    rows.value = []
  } finally {
    loading.value = false
  }
}

const loadAgents = async () => {
  try {
    const r = await listAvailableAgents()
    const list = (r && r.data) || []
    agentOptions.value = list.map(a => ({
      value: a.id || a.agentId,
      label: a.name || a.id || a.agentId
    })).filter(o => o.value)
  } catch (e) {
    // 拿不到就只留"默认智能体"一个选项；不弹错误（不影响建任务）
    agentOptions.value = []
  }
}

const openCreate = () => {
  editing.value = null
  form.value = blank()
  showEdit.value = true
  loadAgents()
}

const openEdit = row => {
  editing.value = row
  form.value = {
    name: row.name || '', agentId: row.agentId || '', prompt: row.prompt || '',
    cron: row.cron || '', timezone: row.timezone || 'Asia/Shanghai',
    modelRef: row.modelRef || '', deepThink: !!row.deepThink, enabled: !!row.enabled
  }
  showEdit.value = true
  loadAgents()
}

const save = async () => {
  const f = form.value
  if (!f.name.trim()) { message.warning('请填写任务名称'); return }
  if (!f.prompt.trim()) { message.warning('请填写执行指令'); return }
  if (!f.cron.trim()) { message.warning('请填写执行周期'); return }
  saving.value = true
  try {
    const body = {
      name: f.name.trim(), prompt: f.prompt.trim(), cron: f.cron.trim(),
      timezone: f.timezone, agentId: f.agentId || '', modelRef: f.modelRef || '',
      deepThink: f.deepThink, enabled: f.enabled
    }
    const r = editing.value
      ? await updateScheduledJob(editing.value.id, body)
      : await createScheduledJob(body)
    if (r && r.success) {
      message.success(editing.value ? '已保存' : '已创建')
      showEdit.value = false
      await load()
    } else {
      message.error((r && r.msg) || '保存失败')
    }
  } catch (e) {
    message.error('保存失败：' + (e.message || ''))
  } finally {
    saving.value = false
  }
}

const doRun = async row => {
  busyId.value = row.id
  try {
    const r = await runScheduledJob(row.id)
    if (r && r.success) {
      message.success('已触发执行，跑完可在执行历史里看结果')
      // 执行是异步的：稍等一下再刷新，让状态/下一次时间回显
      setTimeout(load, 2500)
    } else {
      message.error((r && r.msg) || '触发失败')
    }
  } catch (e) {
    message.error('触发失败：' + (e.message || ''))
  } finally {
    busyId.value = ''
  }
}

const doToggle = async row => {
  try {
    const r = await toggleScheduledJob(row.id, !row.enabled)
    if (r && r.success) { message.success(row.enabled ? '已停用' : '已启用'); await load() }
    else message.error((r && r.msg) || '操作失败')
  } catch (e) {
    message.error('操作失败：' + (e.message || ''))
  }
}

const doDelete = row => {
  Modal.confirm({
    title: '删除该定时任务？',
    content: '任务会被删除（不再触发）；已产生的执行记录与结果会话会保留。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      try {
        const r = await deleteScheduledJob(row.id)
        if (r && r.success) { message.success('已删除'); await load() }
        else message.error((r && r.msg) || '删除失败')
      } catch (e) {
        message.error('删除失败：' + (e.message || ''))
      }
    }
  })
}

const openRuns = async row => {
  runsJob.value = row
  runs.value = []
  showRuns.value = true
  runsLoading.value = true
  try {
    const r = await listScheduledRuns(row.id, 30)
    runs.value = (r && r.data) || []
  } catch (e) {
    message.error('执行历史加载失败：' + (e.message || ''))
  } finally {
    runsLoading.value = false
  }
}

const openSession = sid => {
  if (sid) router.push({ path: '/chat', query: { sid } })
}

const runLabel = s => ({ running: '执行中', succeeded: '成功', failed: '失败', skipped: '已跳过' }[s] || s)
const runColor = s => ({ running: 'processing', succeeded: 'green', failed: 'red', skipped: 'orange' }[s] || 'default')

const fmt = t => (t ? String(t).replace('T', ' ').slice(0, 16) : '—')

const duration = x => {
  if (!x.startedAt || !x.finishedAt) return '—'
  const ms = new Date(String(x.finishedAt).replace(' ', 'T')) - new Date(String(x.startedAt).replace(' ', 'T'))
  if (!Number.isFinite(ms) || ms < 0) return '—'
  return ms >= 60000 ? `${(ms / 60000).toFixed(1)} 分钟` : `${Math.round(ms / 1000)} 秒`
}

const humanNext = t => {
  if (!t) return ''
  const target = new Date(String(t).replace(' ', 'T'))
  const ms = target - new Date()
  if (!Number.isFinite(ms)) return ''
  if (ms <= 0) return '即将执行'
  const min = Math.round(ms / 60000)
  if (min < 60) return `${min} 分钟后`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour} 小时后`
  return `${Math.floor(hour / 24)} 天后`
}

onMounted(load)
</script>

<style scoped>
.sched-list { display: flex; flex-direction: column; gap: 10px; }
.sched-card { display: flex; flex-direction: column; gap: 8px; }
.sched-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.sched-name { font-weight: 500; font-size: 14px; }
.sched-meta { font-size: 12px; color: var(--app-text3); }
.sched-prompt {
  font-size: 13px; color: var(--app-text2);
  background: var(--app-panel); border-radius: 6px; padding: 6px 10px;
  white-space: pre-wrap; word-break: break-word;
}
.sched-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.sched-link { font-size: 12px; color: var(--app-accent); cursor: pointer; }
.sched-del { color: var(--app-danger, #d4380d); }
.sched-empty { text-align: center; padding: 28px 16px; }
.sched-empty-title { margin: 0 0 6px; font-weight: 500; }
.sched-cron-row { display: flex; gap: 8px; align-items: center; }
.sched-hint { font-size: 12px; color: var(--app-text3); margin-top: 4px; line-height: 1.6; }
.sched-hint code { background: var(--app-panel); padding: 1px 4px; border-radius: 3px; }
.sched-runs { display: flex; flex-direction: column; gap: 10px; }
.sched-run { border-bottom: 1px solid var(--app-border); padding-bottom: 8px; }
.sched-run:last-child { border-bottom: none; }
.sched-run-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.sched-err { margin-top: 6px; font-size: 13px; color: var(--app-danger, #d4380d); }
.sched-preview {
  margin-top: 6px; font-size: 13px; color: var(--app-text2); white-space: pre-wrap;
  max-height: 120px; overflow: hidden;
}
</style>
