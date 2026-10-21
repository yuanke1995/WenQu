<template>
  <!--
    目标卡（目标模式的第五张人在回路卡的「记录面」）：与 PlanCard.vue / AskRecordCard.vue 同族，
    裁决区在底部输入框顶替位（goalp-*，见 ChatPage.vue），这张卡只讲状态与证据。
    两端共用：PC 挂在 AI 气泡下（ChatThread），移动壳挂在 MobileMsgRow；右栏「目标」面板
    复用同一组件（外壳不同、取数同源——SessionEventsPanel 那套「渲染与取数共用」的做法）。
    文案只讲用户结果，不露框架机制（TASK_STATE/beginTask/verifier 这类词不许出现）。
  -->
  <div class="goal-card" :class="'goal-' + (card.status || '').toLowerCase()">
    <div class="goal-card-head">
      <aim-outlined class="goal-card-ic" />
      <span class="goal-card-title">目标</span>
      <span class="goal-state" :class="'st-' + stateClass">{{ stateLabel }}</span>
      <span v-if="card.turnIndex && card.turnsMax" class="goal-turn">
        第 {{ card.turnIndex }}/{{ card.turnsMax }} 轮
      </span>
    </div>

    <!-- 目标原文 -->
    <div class="goal-objective" :class="{ clamped: needsFold && !open }">{{ card.objective }}</div>
    <button v-if="needsFold" type="button" class="goal-more" @click="open = !open">
      {{ open ? '收起' : '展开' }}
    </button>

    <!-- 预算条：四条一行的「已用/上限」 -->
    <div v-if="card.budget" class="goal-budget">
      <div v-for="d in budgetDims" :key="d.key" class="goal-budget-dim" :class="{ warn: d.ratio >= 0.8, over: d.ratio >= 1 }">
        <span class="goal-budget-label">{{ d.label }}</span>
        <span class="goal-budget-bar"><i :style="{ width: Math.min(100, d.ratio * 100) + '%' }" /></span>
        <span class="goal-budget-num">{{ d.used }}{{ d.max ? '/' + d.max : '' }}</span>
      </div>
    </div>

    <!-- 验收标准逐条：未验/通过/失败/已过期 -->
    <div v-if="card.criteria && card.criteria.length" class="goal-criteria">
      <div v-for="c in card.criteria" :key="c.key || c.id" class="goal-criterion" :class="'cv-' + criterionClass(c)">
        <span class="goal-criterion-box">{{ criterionMark(c) }}</span>
        <span class="goal-criterion-text">{{ c.text }}</span>
        <span v-if="c.verification && c.verification.stale" class="goal-criterion-stale">已过期</span>
        <span v-else-if="c.verification" class="goal-criterion-outcome">{{ outcomeLabel(c.verification.outcome) }}</span>
        <span v-else class="goal-criterion-outcome muted">未验</span>
      </div>
    </div>

    <!-- 同步异常不许静默 -->
    <div v-if="card.syncWarn" class="goal-sync-warn">
      <exclamation-circle-outlined /> {{ card.syncWarn }}
    </div>

    <!-- 未完成清单（预算用满/主动收尾时：不悄然截断） -->
    <div v-if="terminal && remaining.length" class="goal-remaining">
      <div class="goal-remaining-head">还差 {{ remaining.length }} 条没验过：</div>
      <div v-for="(t, i) in remaining" :key="i" class="goal-remaining-item">{{ t }}</div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { AimOutlined, ExclamationCircleOutlined } from '@ant-design/icons-vue'

// 只读展示卡：全部裁决动作在输入框顶替面板（待确认）与右栏面板（进行中）上，
// 这里不 emit 任何东西——与 PlanCard.vue 同一条契约（props 进、零 emits）。
const props = defineProps({
  /** 目标视图模型（goal_state 帧/右栏回显同一份）：{goalId,status,objective,criteria,budget,turnIndex,turnsMax,syncWarn,endReason,...} */
  card: { type: Object, required: true }
})

const open = ref(false)
const needsFold = computed(() => (props.card.objective || '').length > 120)

const stateClass = computed(() => {
  switch (props.card.status) {
    case 'AWAITING_CONFIRMATION': return 'pending'
    case 'RUNNING': return 'running'
    case 'VERIFYING': return 'verifying'
    case 'WAITING_INPUT': return 'waiting'
    case 'PAUSED': return 'paused'
    case 'BUDGET_EXCEEDED': return 'budget'
    case 'ACHIEVED': return 'achieved'
    default: return 'ended'
  }
})

const stateLabel = computed(() => {
  switch (props.card.status) {
    case 'DRAFT': return '起草中'
    case 'CLARIFYING': return '补充中'
    case 'AWAITING_CONFIRMATION': return '等你确认验收标准'
    case 'RUNNING': return '进行中'
    case 'VERIFYING': return '正在核验'
    case 'WAITING_INPUT': return '等你回答'
    case 'PAUSED': return '已暂停'
    case 'BUDGET_EXCEEDED': return '预算用满'
    case 'ACHIEVED': return '已达成'
    case 'FAILED': return '未达成收尾'
    case 'CANCELLED': return '已取消'
    default: return '已结束'
  }
})

const terminal = computed(() => ['ACHIEVED', 'BUDGET_EXCEEDED', 'FAILED', 'CANCELLED', 'ENDED_INCOMPLETE'].includes(props.card.status))

/** 还没通过验证的标准（未完成清单的数据源：过期也不算过） */
const remaining = computed(() => (props.card.criteria || []).filter(c =>
  c.kind !== 'CONSTRAINT' && c.status === 'CONFIRMED' && (!c.verification || c.verification.outcome !== 'PASSED' || c.verification.stale)))

const budgetDims = computed(() => {
  const b = props.card.budget || {}
  const dim = (key, label, fmt) => {
    const d = b[key]
    if (!d) return null
    const used = Number(d.used) || 0
    const max = Number(d.max) || 0
    return { key, label, used: fmt(used), max: max ? fmt(max) : 0, ratio: max > 0 ? used / max : 0 }
  }
  const fmtInt = v => v >= 10000 ? (v / 10000).toFixed(1).replace(/\.0$/, '') + ' 万' : String(v)
  return [
    dim('turns', '轮', v => String(v)),
    dim('tokens', 'token', fmtInt),
    dim('toolCalls', '工具', v => String(v)),
    dim('minutes', '分钟', v => String(v))
  ].filter(Boolean)
})

function criterionClass (c) {
  if (c.verification && c.verification.stale) return 'stale'
  if (c.verification) {
    if (c.verification.outcome === 'PASSED') return 'passed'
    if (c.verification.outcome === 'FAILED') return 'failed'
    if (c.verification.outcome === 'ERROR') return 'error'
    return 'unknown'
  }
  if (c.status === 'REJECTED') return 'rejected'
  return 'pending'
}

function criterionMark (c) {
  const cls = criterionClass(c)
  if (cls === 'passed') return '✓'
  if (cls === 'failed') return '✗'
  if (cls === 'error') return '!'
  if (cls === 'stale') return '↺'
  if (cls === 'rejected') return '–'
  return ''
}

function outcomeLabel (o) {
  switch (o) {
    case 'PASSED': return '通过'
    case 'FAILED': return '未过'
    case 'ERROR': return '异常'
    default: return '待验'
  }
}
</script>

<style scoped>
.goal-card { border: 1px solid var(--border-color, #e5e6eb); border-radius: 10px; padding: 10px 12px; margin: 8px 0; background: var(--bg-color, #fff); }
.goal-card-head { display: flex; align-items: center; gap: 8px; }
.goal-card-ic { color: var(--app-accent); }
.goal-card-title { font-weight: 600; font-size: 13px; }
.goal-state { font-size: 12px; padding: 1px 8px; border-radius: 8px; background: #f0f1f3; color: #555; }
.goal-state.st-achieved { background: var(--app-ok-weak); color: var(--app-ok); }
.goal-state.st-budget, .goal-state.st-ended { background: var(--app-danger-weak); color: var(--app-danger); }
.goal-state.st-pending { background: var(--app-warn-weak); color: var(--app-warn-text); }
.goal-state.st-running, .goal-state.st-verifying { background: var(--app-info-weak); color: #1456b8; }
.goal-turn { margin-left: auto; font-size: 12px; color: #888; }
.goal-objective { margin-top: 8px; font-size: 13px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.goal-objective.clamped { display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.goal-more { margin-top: 2px; border: none; background: none; color: var(--app-accent); font-size: 12px; cursor: pointer; padding: 0; }
.goal-budget { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 10px; }
.goal-budget-dim { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #666; min-width: 130px; }
.goal-budget-dim.warn .goal-budget-num { color: var(--app-warn-text); }
.goal-budget-dim.over .goal-budget-num { color: var(--app-danger); }
.goal-budget-bar { width: 56px; height: 4px; border-radius: 2px; background: #eceef1; overflow: hidden; }
.goal-budget-bar i { display: block; height: 100%; background: var(--app-accent); }
.goal-budget-dim.warn .goal-budget-bar i { background: var(--app-warn); }
.goal-budget-dim.over .goal-budget-bar i { background: var(--app-danger); }
.goal-budget-num { font-variant-numeric: tabular-nums; }
.goal-criteria { margin-top: 10px; display: flex; flex-direction: column; gap: 6px; }
.goal-criterion { display: flex; align-items: baseline; gap: 8px; font-size: 12.5px; line-height: 1.5; }
.goal-criterion-box { flex: none; width: 15px; height: 15px; border-radius: 4px; border: 1px solid #c9ccd1; display: inline-flex; align-items: center; justify-content: center; font-size: 11px; transform: translateY(2px); }
.goal-criterion.cv-passed .goal-criterion-box { border-color: var(--app-ok); color: var(--app-ok); background: var(--app-ok-weak); }
.goal-criterion.cv-failed .goal-criterion-box { border-color: var(--app-danger); color: var(--app-danger); background: var(--app-danger-weak); }
.goal-criterion.cv-error .goal-criterion-box { border-color: var(--app-warn-text); color: var(--app-warn-text); background: var(--app-warn-weak); }
.goal-criterion.cv-stale .goal-criterion-box { border-color: var(--app-warn-text); color: var(--app-warn-text); }
.goal-criterion.cv-rejected { opacity: 0.55; }
.goal-criterion-text { flex: 1; }
.goal-criterion-outcome { flex: none; font-size: 11.5px; color: var(--app-ok); }
.goal-criterion-outcome.muted { color: #999; }
.goal-criterion-stale { flex: none; font-size: 11.5px; color: var(--app-warn-text); }
.goal-sync-warn { margin-top: 8px; font-size: 12px; color: var(--app-warn-text); background: var(--app-warn-weak); border-radius: 6px; padding: 6px 8px; }
.goal-remaining { margin-top: 10px; font-size: 12.5px; color: var(--app-danger); }
.goal-remaining-head { font-weight: 600; }
.goal-remaining-item { padding-left: 12px; position: relative; line-height: 1.6; }
.goal-remaining-item::before { content: '·'; position: absolute; left: 2px; }
</style>
