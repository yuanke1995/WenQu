<template>
  <!-- 运行历史页（M4）：run 列表 + 节点级 trace 回放 + 待审批裁决。
       与画布内「运行历史弹窗」的区别：这里是独立页（列表页/画布都能进），
       审批卡在此收口——挂起的运行不用回画布也能裁决。 -->
  <div class="app-page">
    <div class="app-page-head">
      <button class="app-btn ghost small" @click="$emit('back')"><arrow-left-outlined /> 返回</button>
      <h1 class="app-page-title">运行历史{{ name ? ' · ' + name : '' }}</h1>
      <span class="head-hint-plain">每次运行锁定当时的 DSL 快照；点开单条可看节点级输入输出与耗时</span>
      <button class="app-btn ghost small" style="margin-left:auto" :disabled="loading" @click="load">
        <reload-outlined /> 刷新
      </button>
    </div>

    <div class="app-page-body wf-runs-body">
      <a-spin :spinning="loading">
        <div v-if="!runs.length" class="app-card wf-empty">
          <p class="wf-empty-title">还没有运行记录</p>
          <p class="head-hint-plain">在画布里点「运行」调试一次，这里就会留下完整的节点 trace</p>
        </div>

        <div v-else class="wf-runs">
          <div v-for="x in runs" :key="x.id" class="app-card wf-run" :class="{ active: detail && detail.id === x.id }">
            <div class="wf-run-head" @click="openDetail(x)">
              <a-tag :color="runColor(x.status)">{{ runLabel(x.status) }}</a-tag>
              <a-tag>{{ triggerLabel(x.triggerType) }}</a-tag>
              <a-tag v-if="x.version != null" color="blue">v{{ x.version }}</a-tag>
              <span class="wf-meta">{{ fmtTime(x.startedAt) }}</span>
              <span class="wf-meta">{{ x.durationMs != null ? fmtMs(x.durationMs) : '' }}</span>
              <a class="wf-run-link">节点 trace →</a>
            </div>
            <div v-if="x.error" class="wf-run-err">{{ x.error }}</div>
          </div>
        </div>
      </a-spin>

      <!-- 单条详情 -->
      <div v-if="detail" class="app-card wf-detail">
        <div class="wf-run-head" style="margin-bottom:10px">
          <a-tag :color="runColor(detail.status)">{{ runLabel(detail.status) }}</a-tag>
          <a-tag>{{ triggerLabel(detail.triggerType) }}</a-tag>
          <a-tag v-if="detail.version != null" color="blue">v{{ detail.version }}</a-tag>
          <span class="wf-meta">{{ fmtTime(detail.startedAt) }}</span>
          <span class="wf-meta">{{ detail.durationMs != null ? fmtMs(detail.durationMs) : '' }}</span>
          <span class="wf-meta">run {{ detail.id }}</span>
        </div>
        <div v-if="detail.error" class="wf-run-err" style="margin-bottom:10px">{{ detail.error }}</div>

        <!-- M5 失败检查点续跑：failed/timeout 且带快照的运行从失败点续跑（已成功节点不重复消耗） -->
        <div v-if="canResume" class="wf-resume">
          <div class="wf-resume-txt">
            已完成节点已存入检查点——续跑只重新执行失败节点及其下游（LLM 等已成功节点不重复消耗）
          </div>
          <button class="app-btn small" :disabled="resuming" @click="doResume">▶ 从失败点续跑</button>
        </div>

        <!-- 待审批裁决卡（M4 收口：挂起的运行在这里就能批/拒，不必回画布） -->
        <div v-if="detail.status === 'waiting_approval'" class="wf-approval">
          <div class="wf-approval-title">✋ 等待人工审核</div>
          <div class="wf-approval-prompt">{{ approval ? (approval.requestArgs && safeParse(approval.requestArgs)?.prompt) || '（无提示内容）' : '（审批信息加载中）' }}</div>
          <div class="wf-approval-foot">
            <span class="wf-meta">挂起于 {{ fmtTime(approval?.createdAt || detail.startedAt) }}</span>
            <button class="app-btn small" :disabled="approving" @click="doApprove(true)">✓ 批准（approve）</button>
            <button class="app-btn danger small" :disabled="approving" @click="doApprove(false)">✕ 拒绝（reject）</button>
          </div>
        </div>

        <div class="wf-block"><div class="wf-block-label">入参</div><pre class="wf-pre">{{ pretty(detail.inputs) }}</pre></div>
        <div class="wf-block"><div class="wf-block-label">出参</div><pre class="wf-pre">{{ pretty(detail.outputs) }}</pre></div>
        <div class="wf-block">
          <div class="wf-block-label">节点 trace（{{ traces.length }}）</div>
          <!-- 时间线式排布：左轨圆点按状态着色，执行顺序一眼可读 -->
          <div class="wf-timeline">
            <div v-for="(t, i) in traces" :key="i" class="wf-tl-item">
              <div class="wf-tl-rail">
                <span class="wf-tl-dot" :class="'td-' + t.status" />
                <span v-if="i < traces.length - 1" class="wf-tl-line" />
              </div>
              <div class="wf-tl-body">
                <div class="wf-tl-head" @click="t._open = !t._open">
                  <span class="wf-trace-name">{{ t.nodeId }}<span class="wf-meta">（{{ t.type }}）</span></span>
                  <a-tag :color="runColor(t.status)" class="wf-tl-tag">{{ runLabel(t.status) }}</a-tag>
                  <span class="wf-meta">{{ t.elapsedMs != null ? t.elapsedMs + ' ms' : '' }}</span>
                  <span v-if="t.completionTokens != null" class="wf-meta">out {{ t.completionTokens }} tok</span>
                  <span class="wf-trace-toggle">{{ t._open ? '收起' : '展开' }}</span>
                </div>
                <div v-if="t.error" class="wf-run-err">{{ t.error }}</div>
                <div v-if="t._open" class="wf-trace-body">
                  <div v-if="t.input" class="wf-block"><div class="wf-block-label">输入</div><pre class="wf-pre">{{ pretty(JSON.stringify(t.input)) }}</pre></div>
                  <div v-if="t.output" class="wf-block"><div class="wf-block-label">输出</div><pre class="wf-pre">{{ pretty(JSON.stringify(t.output)) }}</pre></div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import {
  listWorkflowRuns, getWorkflowRun, getWorkflowPendingApproval, resolveWorkflowApproval, resumeWorkflowRun
} from '../api'

const props = defineProps({
  workflowId: { type: String, required: true },
  name: { type: String, default: '' }
})
defineEmits(['back'])

const runs = ref([])
const loading = ref(false)
const detail = ref(null)
const traces = ref([])
const approval = ref(null)
const approving = ref(false)

const load = async () => {
  loading.value = true
  try {
    const r = await listWorkflowRuns(props.workflowId)
    runs.value = (r && r.data) || []
  } catch (e) {
    message.error('运行历史加载失败：' + (e.message || ''))
  } finally {
    loading.value = false
  }
}

const openDetail = async row => {
  try {
    const r = await getWorkflowRun(props.workflowId, row.id)
    detail.value = (r && r.data) || null
    traces.value = (safeParse(detail.value?.nodeTraces) || []).map(t => ({ ...t, _open: false }))
    approval.value = null
    if (detail.value && detail.value.status === 'waiting_approval') {
      try {
        const ar = await getWorkflowPendingApproval(props.workflowId, detail.value.id)
        approval.value = ar.data || null
      } catch (e) { approval.value = null }
    }
    // 列表里同步更新这一条（裁决后续跑的终态要回写进列表）
    const idx = runs.value.findIndex(x => x.id === row.id)
    if (idx >= 0 && detail.value) runs.value[idx] = detail.value
  } catch (e) {
    message.error('运行详情加载失败：' + (e.message || ''))
  }
}

/** 裁决并同步续跑：接口返回终态 run，就地刷新详情与列表 */
const doApprove = async approved => {
  if (!detail.value) return
  approving.value = true
  try {
    const r = await resolveWorkflowApproval(props.workflowId, detail.value.id, approved)
    const run = (r && r.data) || null
    message.success(approved ? '已批准，工作流已续跑完成' : '已拒绝，工作流按 reject 分支续跑')
    if (run) {
      const idx = runs.value.findIndex(x => x.id === run.id)
      if (idx >= 0) runs.value[idx] = run
      detail.value = run
      traces.value = (safeParse(run.nodeTraces) || []).map(t => ({ ...t, _open: false }))
      approval.value = null
    } else {
      await load()
    }
  } catch (e) {
    message.error('裁决失败：' + (e.message || ''))
  } finally {
    approving.value = false
  }
}

// ---- M5 失败检查点续跑 ----
const resuming = ref(false)
/** 可续跑 = 失败/超时终态且带检查点快照（后端写快照的前提是已有成功节点，无快照时按钮不出现） */
const canResume = computed(() =>
  !!detail.value
  && (detail.value.status === 'failed' || detail.value.status === 'timeout')
  && !!detail.value.stateSnapshot)

/** 从失败点续跑：接口同步返回终态 run，就地刷新（与 doApprove 同款刷新模式） */
const doResume = async () => {
  if (!detail.value) return
  resuming.value = true
  try {
    const r = await resumeWorkflowRun(props.workflowId, detail.value.id)
    const run = (r && r.data) || null
    if (run) {
      message.success(run.status === 'success' ? '续跑完成' : `续跑结束（${runLabel(run.status)}），可再次续跑或重跑`)
      const idx = runs.value.findIndex(x => x.id === run.id)
      if (idx >= 0) runs.value[idx] = run
      detail.value = run
      traces.value = (safeParse(run.nodeTraces) || []).map(t => ({ ...t, _open: false }))
    } else {
      await load()
    }
  } catch (e) {
    message.error('续跑失败：' + (e.message || ''))
  } finally {
    resuming.value = false
  }
}

const triggerLabel = t => ({ manual: '手动调试', api: 'API 触发', agent: '智能体对话' }[t] || t || '—')
const runLabel = s => ({ running: '运行中', success: '成功', failed: '失败', timeout: '超时', waiting_approval: '待审批', retrying: '重试中' }[s] || s)
const runColor = s => ({ running: 'processing', success: 'green', failed: 'red', timeout: 'orange', waiting_approval: 'orange', retrying: 'orange' }[s] || 'default')
const fmtTime = t => (t ? String(t).replace('T', ' ').slice(0, 16) : '—')
const fmtMs = ms => (ms == null ? '' : ms >= 1000 ? (ms / 1000).toFixed(1) + ' s' : ms + ' ms')
const safeParse = s => { try { return JSON.parse(s) } catch (e) { return null } }
const pretty = s => {
  try { return JSON.stringify(JSON.parse(s), null, 2) } catch (e) { return s || '（无）' }
}

onMounted(load)
</script>

<style scoped>
.wf-runs-body { display: flex; flex-direction: column; gap: 12px; }
.wf-runs { display: flex; flex-direction: column; gap: 10px; }
.wf-run { display: flex; flex-direction: column; gap: 6px; cursor: default; }
.wf-run.active { border-color: var(--app-accent); }
.wf-run-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; cursor: pointer; }
.wf-run-link { font-size: 12px; color: var(--app-accent); margin-left: auto; }
.wf-run-err { font-size: 13px; color: var(--app-danger, #d4380d); }
.wf-meta { font-size: 12px; color: var(--app-text3); }
.wf-empty { text-align: center; padding: 28px 16px; }
.wf-empty-title { margin: 0 0 6px; font-weight: 500; }
.wf-detail { display: flex; flex-direction: column; gap: 10px; }
.wf-block { margin-bottom: 6px; }
.wf-block-label { font-size: 12px; color: var(--app-text3); margin-bottom: 4px; }
.wf-pre {
  background: var(--app-panel); border-radius: 6px; padding: 8px 10px; margin: 0;
  font-size: 12px; max-height: 240px; overflow: auto; white-space: pre-wrap; word-break: break-all;
}
.wf-approval { border: 1px solid #ffd591; background: #fffbe6; border-radius: 8px; padding: 10px 12px; }
.wf-approval-title { font-weight: 600; color: #d46b08; margin-bottom: 6px; }
.wf-approval-prompt { font-size: 13px; white-space: pre-wrap; }
.wf-approval-foot { display: flex; align-items: center; gap: 8px; margin-top: 10px; }
.wf-approval-foot .app-btn { margin-left: 0; }
.wf-approval-foot .wf-meta { margin-right: auto; }
/* M5 失败检查点续跑卡 */
.wf-resume { border: 1px solid var(--app-border); background: var(--app-panel); border-radius: 8px; padding: 10px 12px; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.wf-resume-txt { font-size: 13px; color: var(--app-text2); flex: 1; min-width: 220px; }
.wf-trace { border-top: 1px solid var(--app-border); padding: 6px 0; }
/* trace 时间线：左轨（状态圆点 + 连线）+ 右侧内容 */
.wf-timeline { display: flex; flex-direction: column; }
.wf-tl-item { display: flex; gap: 10px; }
.wf-tl-rail { display: flex; flex-direction: column; align-items: center; flex: none; width: 14px; padding-top: 5px; }
.wf-tl-dot { width: 9px; height: 9px; border-radius: 50%; flex: none; }
.td-success { background: #52c41a; box-shadow: 0 0 0 3px rgba(82, 196, 26, .15); }
.td-failed { background: #ff4d4f; box-shadow: 0 0 0 3px rgba(255, 77, 79, .15); }
.td-waiting { background: #fa8c16; box-shadow: 0 0 0 3px rgba(250, 140, 22, .18); }
.td-retrying { background: #faad14; box-shadow: 0 0 0 3px rgba(250, 173, 20, .18); }
.td-running { background: #1677ff; }
.wf-tl-line { flex: 1; width: 1.5px; background: var(--app-border); margin: 3px 0; }
.wf-tl-body { flex: 1; min-width: 0; padding-bottom: 12px; }
.wf-tl-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; cursor: pointer; }
.wf-tl-tag { margin: 0; }
.wf-trace-name { font-size: 13px; font-weight: 500; font-family: ui-monospace, Menlo, monospace; }
.wf-trace-toggle { font-size: 12px; color: var(--app-accent); margin-left: auto; }
</style>
