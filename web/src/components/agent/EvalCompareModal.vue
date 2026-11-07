<template>
  <!-- 版本对比（效果图场景 03）：同一套用例两次运行并排，只看变化不比绝对值 -->
  <a-modal :open="open" :width="980" :footer="null" destroy-on-close @cancel="emit('update:open', false)">
    <template #title>版本对比 · {{ labelOf(runA) }} → {{ labelOf(runB) }}</template>

    <div class="cmp-body">
      <div class="cmp-pickers">
        <div class="run-pick">
          <span class="side-lab a">A</span>
          <a-select v-model:value="runAId" size="small" style="flex:1" :options="succeededOptions"
                    placeholder="选一次成功运行" :disabled="loading" />
        </div>
        <span class="vs">对比</span>
        <div class="run-pick">
          <span class="side-lab b">B</span>
          <a-select v-model:value="runBId" size="small" style="flex:1" :options="succeededOptions"
                    placeholder="选一次成功运行" :disabled="loading" />
        </div>
      </div>

      <a-spin :spinning="loading">
        <div class="cmp-metrics">
          <div class="cmp-metric">
            <div class="k">通过率</div>
            <div class="vals"><span class="a">{{ pct(m?.passRateA) }}</span><span class="b">{{ pct(m?.passRateB) }}</span>
              <span class="d" :class="dir(m?.passRateB - m?.passRateA)">{{ rateDelta }}</span></div>
          </div>
          <div class="cmp-metric">
            <div class="k">平均综合分</div>
            <div class="vals"><span class="a">{{ num(m?.avgScoreA) }}</span><span class="b">{{ num(m?.avgScoreB) }}</span>
              <span class="d" :class="dir(m?.avgScoreB - m?.avgScoreA)">{{ scoreDelta }}</span></div>
          </div>
          <div class="cmp-metric">
            <div class="k">平均耗时</div>
            <div class="vals"><span class="a">{{ secs(m?.avgLatencyA) }}</span><span class="b">{{ secs(m?.avgLatencyB) }}</span>
              <span class="d flat">{{ secsDelta }}</span></div>
          </div>
          <div class="cmp-metric">
            <div class="k">平均 token</div>
            <div class="vals"><span class="a">{{ num(m?.avgTokensA) }}</span><span class="b">{{ num(m?.avgTokensB) }}</span>
              <span class="d flat">{{ tokenDelta }}</span></div>
          </div>
        </div>

        <div class="filter-row">
          <span class="chip" :class="{ on: filter === 'changed' }" @click="filter = 'changed'">
            有变化 {{ data?.counts?.changed ?? 0 }}</span>
          <span class="chip" :class="{ on: filter === 'worse' }" @click="filter = 'worse'">变差 {{ data?.counts?.worse ?? 0 }}</span>
          <span class="chip" :class="{ on: filter === 'better' }" @click="filter = 'better'">变好 {{ data?.counts?.better ?? 0 }}</span>
          <span class="chip" :class="{ on: filter === 'onlyBFailed' }" @click="filter = 'onlyBFailed'">仅 B 失败 {{ data?.counts?.onlyBFailed ?? 0 }}</span>
          <span class="chip" :class="{ on: filter === 'all' }" @click="filter = 'all'">全部 {{ data?.rows?.length || 0 }}</span>
          <span class="sp"></span>
          <span class="conclusion" :class="conclusionClass">{{ data?.conclusion }}</span>
        </div>

        <div class="cmp-table">
          <a-table size="small" :data-source="rows" :pagination="false" row-key="caseId"
                   :locale="{ emptyText: '同一用例在两次运行中还没有可比的变化' }">
            <a-table-column title="用例" data-index="question" :ellipsis="true" />
            <a-table-column title="A" :width="90" data-index="aScore">
              <template #default="{ record }">
                <span :class="scoreClass(record.aScore, record.aPassed)">{{ scoreText(record.aScore) }}</span>
              </template>
            </a-table-column>
            <a-table-column title="B" :width="90" data-index="bScore">
              <template #default="{ record }">
                <span :class="scoreClass(record.bScore, record.bPassed)">{{ scoreText(record.bScore) }}</span>
              </template>
            </a-table-column>
            <a-table-column title="变化" :width="100" key="delta">
              <template #default="{ record }">
                <span v-if="record.verdict === 'new'" class="delta-badge flat">新增</span>
                <span v-else-if="record.verdict === 'removed'" class="delta-badge flat">已删</span>
                <span v-else-if="record.delta == null" class="delta-badge flat">—</span>
                <span v-else-if="record.delta <= -1" class="delta-badge bad">▼ {{ Math.abs(record.delta) }}</span>
                <span v-else-if="record.delta >= 1" class="delta-badge good">▲ {{ record.delta }}</span>
                <span v-else class="delta-badge flat">±{{ Math.abs(record.delta) }}</span>
              </template>
            </a-table-column>
            <a-table-column title="说明" :ellipsis="true" key="note">
              <template #default="{ record }">
                <span class="note">{{ record.note || verdictText(record.verdict) }}</span>
              </template>
            </a-table-column>
          </a-table>
        </div>
      </a-spin>
    </div>

    <div class="cmp-foot">
      <a-button size="small" type="link" :disabled="!runAId || !succeeded" @click="doSetBaseline">把 A 设为基线</a-button>
      <span class="sp"></span>
      <a-button @click="emit('update:open', false)">关闭</a-button>
    </div>
  </a-modal>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { agentEvalCompare, agentEvalSetBaseline } from '../../api'

const props = defineProps({
  open: { type: Boolean, default: false },
  agentId: { type: String, default: '' },
  /** 运行简表（详情页已加载的历史），只取成功运行做 A/B 候选 */
  runs: { type: Array, default: () => [] },
  /** 打开时默认 B = 该运行（一般传最新一次） */
  initialB: { type: String, default: '' }
})
const emit = defineEmits(['update:open', 'baseline-changed'])

const runAId = ref('')
const runBId = ref('')
const data = ref(null)
const loading = ref(false)
const filter = ref('changed')

const runA = computed(() => (props.runs || []).find(r => r.id === runAId.value))
const runB = computed(() => (props.runs || []).find(r => r.id === runBId.value))

const succeededOptions = computed(() => (props.runs || [])
  .filter(r => r.status === 'succeeded')
  .map(r => ({ value: r.id, label: `${labelOf(r)} · 通过 ${r.passed ?? '—'}/${r.total ?? '—'} · ${pct(r.passRate)}` })))
const succeeded = computed(() => (props.runs || []).some(r => r.id === runAId.value && r.status === 'succeeded'))

function labelOf (r) {
  if (!r) return '—'
  return `v${r.agentVersion ?? '?'} · ${shortTime(r.finishedAt || r.startedAt)}`
}
function shortTime (t) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(5, 16)
}

watch(() => props.open, v => {
  if (!v) return
  filter.value = 'changed'
  const ok = (props.runs || []).filter(r => r.status === 'succeeded')
  // 默认 A=上一次成功、B=最近一次成功（initialB 指定时以它为 B）
  runBId.value = props.initialB && ok.some(r => r.id === props.initialB) ? props.initialB : (ok[0]?.id || '')
  const prev = ok.find(r => r.id !== runBId.value)
  runAId.value = prev?.id || ''
  if (runAId.value && runBId.value) load()
  else data.value = null
})

watch([runAId, runBId], () => { if (props.open && runAId.value && runBId.value) load() })

async function load () {
  loading.value = true
  try {
    const r = await agentEvalCompare(props.agentId, runAId.value, runBId.value)
    if (r.success) data.value = r.data
    else message.error(r.msg || '对比失败')
  } catch (e) { message.error(e.message || '对比失败') }
  finally { loading.value = false }
}

const rows = computed(() => {
  const all = data.value?.rows || []
  if (filter.value === 'all') return all
  return all.filter(x => {
    if (filter.value === 'changed') return ['worse', 'better', 'onlyBFailed', 'new'].includes(x.verdict)
    return x.verdict === filter.value
  })
})
const m = computed(() => data.value?.metrics)
const conclusionClass = computed(() => {
  const c = data.value?.conclusion || ''
  return c.includes('变差') ? 'bad' : c.includes('变好') ? 'good' : ''
})

const fmtD = v => (v == null ? '—' : (v > 0 ? '▲ ' : v < 0 ? '▼ ' : '±') + String(Math.round(Math.abs(v) * 100) / 100))
const rateDelta = computed(() => m.value?.passRateA == null || m.value?.passRateB == null ? '—' : fmtD(m.value.passRateB - m.value.passRateA) + 'pp')
const scoreDelta = computed(() => m.value?.avgScoreA == null || m.value?.avgScoreB == null ? '—' : fmtD(m.value.avgScoreB - m.value.avgScoreA))
const secsDelta = computed(() => m.value?.avgLatencyA == null || m.value?.avgLatencyB == null ? '—' : fmtD((m.value.avgLatencyB - m.value.avgLatencyA) / 1000) + 's')
const tokenDelta = computed(() => m.value?.avgTokensA == null || m.value?.avgTokensB == null ? '—' : fmtD(m.value.avgTokensB - m.value.avgTokensA))

function dir (d) {
  if (d == null || Math.abs(d) < 0.01) return 'flat'
  return d > 0 ? 'good' : 'bad'
}
const pct = v => (v == null ? '—' : (Math.round(v) === v ? v.toFixed(0) : v.toFixed(1)) + '%')
const num = v => (v == null ? '—' : String(Math.round(v * 100) / 100))
const secs = v => (v == null ? '—' : (Math.round(v / 100) / 10) + 's')
const scoreText = v => (v == null ? '—' : String(Math.round(v)))
const scoreClass = (v, passed) => (v == null ? '' : passed ? 'cell-good' : 'cell-bad')
function verdictText (v) {
  return { new: 'B 新增的用例', removed: 'B 已删除的用例', flat: '两次基本一致', worse: '', better: '', onlyBFailed: 'A 通过、B 失败' }[v] || ''
}

async function doSetBaseline () {
  try {
    const r = await agentEvalSetBaseline(props.agentId, runAId.value)
    if (r.success) { message.success('已把 A 设为基线'); emit('baseline-changed') }
    else message.error(r.msg || '设置失败')
  } catch (e) { message.error(e.message || '设置失败') }
}
</script>

<style scoped>
.cmp-body { min-height: 320px; }
.cmp-pickers { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.run-pick { flex: 1; display: flex; align-items: center; gap: 8px; border: 1px solid var(--app-border);
  border-radius: 8px; padding: 8px 10px; background: var(--app-panel-2, #fafbfc); }
.side-lab { width: 18px; height: 18px; border-radius: 4px; color: #fff; font-size: 10px; font-weight: 700;
  display: inline-flex; align-items: center; justify-content: center; flex: none; }
.side-lab.a { background: var(--app-text3); }
.side-lab.b { background: var(--app-accent); }
.vs { color: var(--app-text3); font-size: 12px; }

.cmp-metrics { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 12px; }
.cmp-metric { border: 1px solid var(--app-border); border-radius: 8px; padding: 10px 12px; }
.cmp-metric .k { font-size: 11px; color: var(--app-text3); }
.cmp-metric .vals { display: flex; align-items: baseline; gap: 8px; margin-top: 4px; flex-wrap: wrap; }
.cmp-metric .a { font-size: 13px; color: var(--app-text3); }
.cmp-metric .b { font-size: 17px; font-weight: 600; color: var(--app-text); }
.cmp-metric .d { font-size: 11px; }
.d.good { color: var(--app-ok); }
.d.bad { color: var(--app-danger); }
.d.flat { color: var(--app-text3); }

.filter-row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; font-size: 12px; }
.chip { font-size: 11px; padding: 3px 8px; border-radius: 999px; border: 1px solid var(--app-border);
  color: var(--app-text2); background: var(--app-panel); cursor: pointer; user-select: none; }
.chip.on { border-color: var(--app-accent-border, #bcd0f7); color: var(--app-accent); background: var(--app-accent-weak); }
.filter-row .sp { flex: 1; }
.conclusion { font-size: 12px; color: var(--app-text2); white-space: nowrap; }
.conclusion.bad { color: var(--app-danger); font-weight: 600; }
.conclusion.good { color: var(--app-ok); font-weight: 500; }

.cmp-table :deep(.ant-table) { font-size: 11.5px; }
.cell-good { color: var(--app-ok); font-weight: 600; }
.cell-bad { color: var(--app-danger); font-weight: 600; }
.note { color: var(--app-text3); font-size: 11px; }
.delta-badge { display: inline-flex; align-items: center; font-size: 11px; font-weight: 600; padding: 1px 6px; border-radius: 4px; }
.delta-badge.bad { color: var(--app-danger); background: var(--app-danger-weak); }
.delta-badge.good { color: var(--app-ok); background: var(--app-ok-weak); }
.delta-badge.flat { color: var(--app-text3); background: #f1f3f5; }

.cmp-foot { display: flex; align-items: center; gap: 8px; margin-top: 12px; padding-top: 10px;
  border-top: 1px solid var(--app-border); }
.cmp-foot .sp { flex: 1; }
</style>
