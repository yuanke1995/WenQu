<template>
  <a-drawer :open="open" :width="device === 'h5' ? '100%' : 430" placement="right"
            :closable="true" :body-style="{ padding: '0' }" @close="$emit('close')">
    <template #title>
      <div class="gv-head">
        <span class="gv-title">深度执行过程</span>
        <span v-if="goal" class="gv-state" :class="'st-' + stateClass">{{ stateLabel }}</span>
      </div>
    </template>
    <div v-if="goal" class="gv-body">
      <!-- 身份：这一整页讲的都是同一条目标；版本与时间让人确认「看的是哪一版、跑了多久」 -->
      <section class="gv-sec">
        <div class="gv-objective">{{ goal.objective }}</div>
        <div class="gv-meta">
          <span>{{ version }}</span>
          <span v-if="goal.startedAt">开始 {{ shortTime(goal.startedAt) }}</span>
          <span v-if="goal.endedAt">结束 {{ shortTime(goal.endedAt) }}</span>
        </div>
      </section>
      <!-- 版本轨：改过一次原文就多一格；当前版本高亮，其余只是历史痕迹 -->
      <section v-if="versions.length > 1" class="gv-sec">
        <div class="gv-sec-t">版本</div>
        <div class="gv-track-line">
          <span v-for="v in versions" :key="v" class="gv-ver" :class="{ cur: v === goalVersion }">v{{ v }}</span>
        </div>
      </section>
      <!-- 预算：token/时间/工具次数只在详情里给，条子上只留轮次与细进度 -->
      <section v-if="budgetRows.length" class="gv-sec">
        <div class="gv-sec-t">用量与预算</div>
        <div v-for="b in budgetRows" :key="b.label" class="gv-budget">
          <span class="gv-budget-k">{{ b.label }}</span>
          <span class="gv-budget-bar"><i :style="{ width: b.pct + '%' }" /></span>
          <span class="gv-budget-v">{{ b.value }}</span>
        </div>
      </section>
      <!-- 按轮时间线：每轮一行，点「跳到这一轮」回消息流定位 -->
      <section class="gv-sec">
        <div class="gv-sec-t">轮次</div>
        <div v-if="!rounds.length" class="gv-empty">{{ loading ? '读取中…' : '还没有跑到任何一轮' }}</div>
        <div v-for="r in rounds" :key="r.turnId || r.n" class="gv-round">
          <div class="gv-round-head">
            <span class="gv-round-n">第 {{ r.n }} 轮</span>
            <span v-if="r.at" class="gv-round-at">{{ shortTime(r.at) }}</span>
            <button v-if="r.turnId" type="button" class="gv-jump" @click="$emit('jump', r.turnId)">跳到这一轮 ›</button>
          </div>
          <div v-if="r.summary" class="gv-round-s">{{ r.summary }}</div>
          <div v-if="r.badges.length" class="gv-badges">
            <span v-for="(b, bi) in r.badges" :key="bi" class="gv-badge">{{ b }}</span>
          </div>
        </div>
      </section>
      <!-- 结果：完成证据 / 未完成缺口 / 取消留痕，与终态卡说同一件事 -->
      <section v-if="outcomeLines.length" class="gv-sec">
        <div class="gv-sec-t">结果</div>
        <div v-for="(line, li) in outcomeLines" :key="li" class="gv-out-line">{{ line }}</div>
      </section>
    </div>
    <div v-if="goal" class="gv-foot">
      <button v-if="canPause" type="button" class="gv-btn" @click="$emit('action', 'pause')">暂停执行</button>
      <button v-else-if="canResume" type="button" class="gv-btn" @click="$emit('action', 'resume')">继续执行</button>
      <button v-if="canEdit" type="button" class="gv-btn" @click="$emit('action', 'edit')">修改任务</button>
      <button v-if="canCancel" type="button" class="gv-btn quiet" @click="$emit('action', 'cancel')">取消执行</button>
      <button v-if="canDelete" type="button" class="gv-btn quiet" @click="$emit('action', 'delete')">删除记录</button>
    </div>
  </a-drawer>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { getGoalEventsApi } from '../api'
import { fmtTokens } from '../utils/token'
import { goalStateLabelOf, goalStateClassOf, goalCanPauseOf, goalCanResumeOf, goalCanEditOf,
         goalCanCancelOf, goalCanClearRecordOf, goalVersionLabelOf } from '../chat/projections'

const props = defineProps({
  open: { type: Boolean, default: false },
  sessionId: { type: String, default: '' },
  /** 当前目标视图；终态也传（历史回看用） */
  goal: { type: Object, default: null },
  /** 'pc' | 'h5'：移动壳走全屏 sheet，桌面走右栏抽屉 */
  device: { type: String, default: 'pc' }
})
defineEmits(['close', 'action', 'jump'])

const events = ref([])
const loading = ref(false)

const stateLabel = computed(() => goalStateLabelOf(props.goal))
const stateClass = computed(() => goalStateClassOf(props.goal))
const goalVersion = computed(() => Number(props.goal && props.goal.objectiveVersion) || 0)
const version = computed(() => goalVersionLabelOf(props.goal))
const versions = computed(() => {
  const out = []
  for (let i = 0; i <= goalVersion.value; i++) out.push(i + 1)
  return out
})
const canPause = computed(() => goalCanPauseOf(props.goal))
const canResume = computed(() => goalCanResumeOf(props.goal))
const canEdit = computed(() => goalCanEditOf(props.goal))
const canCancel = computed(() => goalCanCancelOf(props.goal))
const canDelete = computed(() => goalCanClearRecordOf(props.goal))

const pctOf = (used, max) => {
  const m = Number(max) || 0
  if (!m) return 0
  return Math.max(0, Math.min(100, Math.round((Number(used) || 0) * 100 / m)))
}
const budgetRows = computed(() => {
  const b = props.goal && props.goal.budget
  if (!b) return []
  const rows = []
  if (b.maxRounds) rows.push({ label: '轮次', value: `${b.rounds || 0}/${b.maxRounds}`, pct: pctOf(b.rounds, b.maxRounds) })
  else rows.push({ label: '轮次', value: String(b.rounds || 0), pct: 0 })
  if (b.maxTokens) rows.push({ label: 'Token', value: `${fmtTokens(b.tokens || 0)}/${fmtTokens(b.maxTokens)}`, pct: pctOf(b.tokens, b.maxTokens) })
  else rows.push({ label: 'Token', value: fmtTokens(b.tokens || 0), pct: 0 })
  if (b.maxMs) rows.push({ label: '时间', value: `${fmtDuration(b.ms)}/${fmtDuration(b.maxMs)}`, pct: pctOf(b.ms, b.maxMs) })
  else rows.push({ label: '时间', value: fmtDuration(b.ms), pct: 0 })
  if (b.maxToolCalls) rows.push({ label: '工具', value: `${b.toolCalls || 0}/${b.maxToolCalls}`, pct: pctOf(b.toolCalls, b.maxToolCalls) })
  else rows.push({ label: '工具', value: String(b.toolCalls || 0), pct: 0 })
  return rows
})

/** 目标轮 id 形如 {@code goal:{goalId}:{n}}：没有 goal_turn 帧（旧账本/中途断线）时从它补轮次，
 *  否则那一轮会显示成「第 0 轮」并排到时间线最前 */
const roundNoOf = turnId => {
  const m = /^goal:.+:(\d+)$/.exec(String(turnId || ''))
  return m ? Number(m[1]) : 0
}
/** 时间线只认两件事：后台轮开跑（goal_turn）与轮内留痕（goal_note）；其余状态帧不进时间线 */
const rounds = computed(() => {
  const byTurn = new Map()
  for (const e of events.value || []) {
    const turnId = e.turnId || ''
    const type = e.type || ''
    if (type !== 'deep_turn' && type !== 'deep_note') continue
    const key = turnId || ('evt-' + e.id)
    let row = byTurn.get(key)
    if (!row) {
      row = { turnId, n: roundNoOf(turnId), at: e.at || '', summary: '', badges: [] }
      byTurn.set(key, row)
    }
    if (type === 'deep_turn') {
      const p = parsePayload(e.payload)
      row.n = Number(p.n) || row.n
      row.at = p.startedAt ? new Date(Number(p.startedAt)).toISOString() : row.at
    } else {
      const p = parsePayload(e.payload)
      row.badges = []
      if (p.summary) row.summary = String(p.summary)
      if (p.plan) row.badges.push(`计划 ${p.plan}`)
      if (p.facts) row.badges.push(`事实 ${p.facts}`)
      if (p.artifacts) row.badges.push(`产物 ${p.artifacts}`)
      if (p.remaining) row.badges.push(`剩余 ${p.remaining}`)
      if (p.completionCheck) row.badges.push('完成检查')
    }
  }
  const out = [...byTurn.values()]
  // 有轮次号的按编号排；取不到号的（旧账本/断线只留下 goal_note）排到最后，
  // 再按最大号依次补编号——否则时间线上会出现「第 0 轮」这种东西
  out.sort((a, b) => {
    const na = Number(a.n) || 0
    const nb = Number(b.n) || 0
    if (na && nb) return na - nb
    if (na) return -1
    if (nb) return 1
    return 0
  })
  let next = out.reduce((max, r) => Math.max(max, Number(r.n) || 0), 0) + 1
  for (const r of out) if (!Number(r.n)) r.n = next++
  return out
})

const outcomeLines = computed(() => {
  const g = props.goal
  if (!g || !g.outcome) return []
  const o = g.outcome
  const lines = []
  if (o.summary) lines.push(o.summary)
  if (o.state === 'COMPLETED') {
    const n = (o.evidence || []).length
    lines.push(n ? `证据已核验（${n} 条）` : '已完成')
  } else if (o.state === 'CANCELLED') {
    lines.push(o.reason === 'cleared' ? '记录已删除' : '已取消，历史与过程保留')
  }
  for (const r of (o.remaining || []).slice(0, 6)) lines.push('未完成：' + (typeof r === 'string' ? r : JSON.stringify(r)))
  if (o.reason && o.state !== 'CANCELLED') lines.push('结束原因：' + o.reason)
  return lines
})

function parsePayload (raw) {
  if (!raw) return {}
  if (typeof raw === 'object') return raw
  try { return JSON.parse(raw) } catch (e) { return {} }
}
function shortTime (iso) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const p = n => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}
function fmtDuration (ms) {
  const n = Number(ms) || 0
  if (n < 60000) return Math.round(n / 1000) + 's'
  const m = Math.floor(n / 60000)
  if (m < 60) return m + 'm'
  return Math.floor(m / 60) + 'h' + (m % 60) + 'm'
}

let seq = 0
async function load () {
  if (!props.open || !props.sessionId || !props.goal || !props.goal.goalId) return
  const mine = ++seq
  loading.value = true
  try {
    const r = await getGoalEventsApi(props.sessionId, props.goal.goalId)
    if (mine !== seq) return
    events.value = (r && r.success && Array.isArray(r.data)) ? r.data : []
  } catch (e) {
    if (mine === seq) events.value = []
  } finally {
    if (mine === seq) loading.value = false
  }
}
watch(() => props.open, v => { if (v) load() })
watch(() => props.goal && (props.goal.goalId + ':' + props.goal.status), () => { if (props.open) load() })
</script>

<style scoped>
.gv-head { display: flex; align-items: center; gap: 8px; }
.gv-title { font-size: 14px; font-weight: 600; color: var(--app-text); }
.gv-state { font-size: 12px; line-height: 20px; padding: 0 6px; border-radius: 6px; background: var(--app-panel-2); color: var(--app-text3); }
.gv-state.st-running { color: var(--app-accent); background: var(--app-accent-weak); }
.gv-state.st-paused { color: var(--app-warn-text); background: var(--app-warn-weak); }
.gv-state.st-failed { color: var(--app-danger); background: var(--app-danger-weak); }
.gv-body { padding: 16px; display: flex; flex-direction: column; gap: 16px; }
.gv-sec { display: flex; flex-direction: column; gap: 8px; }
.gv-objective { font-size: 14px; line-height: 22px; color: var(--app-text); word-break: break-word; }
.gv-meta { display: flex; flex-wrap: wrap; gap: 10px; font-size: 12px; line-height: 18px; color: var(--app-text3); }
.gv-sec-t { font-size: 12px; line-height: 18px; color: var(--app-text3); }
.gv-track-line { display: flex; flex-wrap: wrap; gap: 6px; }
.gv-ver { font-size: 12px; line-height: 20px; padding: 0 8px; border-radius: 6px; border: 1px solid var(--app-border); color: var(--app-text3); }
.gv-ver.cur { color: var(--app-accent); border-color: var(--app-accent); background: var(--app-accent-weak); }
.gv-budget { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--app-text3); }
.gv-budget-k { flex: none; width: 44px; }
.gv-budget-bar { flex: 1; height: 4px; border-radius: 999px; background: var(--app-border); overflow: hidden; }
.gv-budget-bar i { display: block; height: 100%; background: var(--app-accent); border-radius: 999px; }
.gv-budget-v { flex: none; width: 92px; text-align: right; color: var(--app-text2); }
.gv-empty { font-size: 12px; color: var(--app-text3); }
.gv-round { padding: 8px 10px; border-radius: 8px; background: var(--app-panel-2); display: flex; flex-direction: column; gap: 4px; }
.gv-round-head { display: flex; align-items: center; gap: 8px; }
.gv-round-n { font-size: 13px; font-weight: 600; color: var(--app-text); }
.gv-round-at { font-size: 11px; color: var(--app-text3); }
.gv-jump { margin-left: auto; border: none; background: transparent; padding: 0; font-size: 12px; color: var(--app-text3); cursor: pointer; }
.gv-jump:hover { color: var(--app-accent); }
.gv-round-s { font-size: 12px; line-height: 18px; color: var(--app-text2); word-break: break-word; }
.gv-badges { display: flex; flex-wrap: wrap; gap: 6px; }
.gv-badge { font-size: 11px; line-height: 18px; padding: 0 6px; border-radius: 5px; background: var(--app-panel); color: var(--app-text3); }
.gv-out-line { font-size: 12px; line-height: 18px; color: var(--app-text2); word-break: break-word; }
.gv-foot {
  position: sticky; bottom: 0; display: flex; flex-wrap: wrap; gap: 8px;
  padding: 12px 16px calc(12px + env(safe-area-inset-bottom, 0px));
  border-top: 1px solid var(--app-border); background: var(--app-panel);
}
.gv-btn {
  height: 30px; padding: 0 12px; border-radius: 8px;
  border: 1px solid var(--app-border); background: transparent;
  font-size: 12px; color: var(--app-text2); cursor: pointer;
}
.gv-btn:hover { color: var(--app-accent); border-color: var(--app-accent); background: var(--app-accent-weak); }
.gv-btn.quiet { color: var(--app-text3); }
.gv-btn.quiet:hover { color: var(--app-danger); border-color: transparent; background: var(--app-danger-weak); }
</style>
