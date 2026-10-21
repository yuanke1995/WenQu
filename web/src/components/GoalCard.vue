<template>
  <!--
    目标卡（目标模式的「详情面」）：与 PlanCard.vue / AskRecordCard.vue 同族，
    裁决区在底部输入框顶替位（goalp-*，见 ChatPage.vue），这张卡只讲状态与证据。
    两端共用：PC 挂在右栏「目标」面板里，移动壳挂在「状态与来源」sheet 的目标小节
    （外壳不同、取数同源）。进行中的操作面不在这张卡上，在输入框正上方那条摘要条。
    文案只讲用户结果，不露框架机制（TASK_STATE/beginTask/verifier 这类词不许出现）。
  -->
  <div class="goal-card" :class="'goal-' + (card.status || '').toLowerCase()">
    <div class="goal-card-head">
      <aim-outlined class="goal-card-ic" />
      <span class="goal-card-title">目标</span>
      <span class="goal-state" :class="'st-' + stateClass">{{ stateLabel }}</span>
      <span v-if="card.turnIndex" class="goal-turn">
        执行轮次 {{ card.turnIndex }}
      </span>
    </div>

    <!-- 目标原文 -->
    <div class="goal-objective" :class="{ clamped: needsFold && !open }">{{ card.objective }}</div>
    <button v-if="needsFold" type="button" class="goal-more" @click="open = !open">
      {{ open ? '收起' : '展开' }}
    </button>

    <!-- 「等你核验」那一档的一句人话：没有标准 vs 有标准待勾，是两件事 -->
    <div v-if="card.hint" class="goal-hint">{{ card.hint }}</div>

    <!-- 受阻原因：状态词收成「受阻」之后，卡在哪全靠这一行说（不点进标准清单也读得到） -->
    <div v-if="blockReason" class="goal-block-reason">
      <exclamation-circle-outlined /> {{ blockReason }}
    </div>

    <!-- 验收标准逐条：未验/通过/失败/已过期；人工核验的直接在行上勾 -->
    <div v-if="card.criteria && card.criteria.length" class="goal-criteria">
      <div v-for="c in card.criteria" :key="c.key || c.id" class="goal-criterion" :class="'cv-' + criterionClass(c)">
        <span class="goal-criterion-box">{{ criterionMark(c) }}</span>
        <span class="goal-criterion-text">{{ c.text }}</span>
        <span v-if="c.drafted" class="goal-criterion-drafted">助手起草</span>
        <span v-if="c.verification && c.verification.stale" class="goal-criterion-stale">已过期</span>
        <span v-else-if="c.verification" class="goal-criterion-outcome">{{ outcomeLabel(c.verification.outcome) }}</span>
        <span v-else class="goal-criterion-outcome muted">未验</span>
        <span v-if="canMark(c)" class="goal-criterion-acts">
          <button type="button" @click="$emit('mark', { key: c.key, outcome: 'PASSED' })">达成</button>
          <button type="button" @click="$emit('mark', { key: c.key, outcome: 'FAILED' })">没过</button>
        </span>
      </div>
    </div>

    <!-- 同步异常不许静默 -->
    <div v-if="card.syncWarn" class="goal-sync-warn">
      <exclamation-circle-outlined /> {{ card.syncWarn }}
    </div>

    <!-- 未完成清单（不再往前推的那几档：不悄然截断，说清还差哪几条） -->
    <div v-if="stopped && remaining.length" class="goal-remaining">
      <div class="goal-remaining-head">还差 {{ remaining.length }} 条没验过：</div>
      <div v-for="(t, i) in remaining" :key="i" class="goal-remaining-item">{{ t }}</div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { AimOutlined, ExclamationCircleOutlined } from '@ant-design/icons-vue'
import { goalStateLabelOf, goalStateClassOf, goalBlockReasonOf, goalRemainingOf } from '../chat/projections'

// 只读展示卡：除「人工核验」逐条勾选外不出动作；其余裁决动作在输入框顶替面板（待确认）
// 与输入框上方那条摘要条（进行中）上。人工核验必须留一个入口，否则这种标准永远验不出 PASSED。
const props = defineProps({
  /** 目标视图模型（goal_state 帧/右栏回显同一份）：{goalId,status,objective,criteria,turnIndex,syncWarn,hint,endReason,...} */
  card: { type: Object, required: true }
})
defineEmits(['mark'])

const open = ref(false)
const needsFold = computed(() => (props.card.objective || '').length > 120)

// 状态词与判据一律走共享口径（摘要条、右栏头、这张卡说的是同一句话）；
// 「受阻」这一档卡在哪由 goalBlockReasonOf 那行原因说，不在状态词里挤第二层意思
const stateClass = computed(() => goalStateClassOf(props.card))
const stateLabel = computed(() => goalStateLabelOf(props.card))
const blockReason = computed(() => goalBlockReasonOf(props.card))

/** 这一条不再自己往前推了——未完成清单就在这时候给 */
const stopped = computed(() => ['ACHIEVED', 'FAILED', 'CANCELLED', 'ENDED_INCOMPLETE'].includes(props.card.status))

/** 还没通过验证的标准文字（必须 map 成 text——直接留对象，模板里会把整条标准打成一份 JSON 给用户读） */
const remaining = computed(() => goalRemainingOf(props.card))

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

/**
 * 这条能在卡上直接勾吗：只给「已确认 + 人工核验 + 还没通过」的条目。
 * 机器验证器（命令退出码/产物结构）不给人勾——那等于让用户替机器出证据。
 */
function canMark (c) {
  if (stopped.value || !c) return false
  if (!['WAITING_INPUT', 'RUNNING', 'VERIFYING'].includes(props.card.status)) return false
  if (c.kind === 'CONSTRAINT' || c.status !== 'CONFIRMED') return false
  if (c.verifierId && c.verifierId !== 'manual') return false
  return !(c.verification && c.verification.outcome === 'PASSED' && !c.verification.stale)
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
.goal-state { font-size: 12px; padding: 1px 8px; border-radius: 8px; background: #f0f1f3; color: #555; white-space: nowrap; }
.goal-state.st-achieved { background: var(--app-ok-weak); color: var(--app-ok); }
.goal-state.st-ended { background: var(--app-danger-weak); color: var(--app-danger); }
/* 受阻：卡住但还归用户管，用警示色而不是「已终结」的红 */
.goal-state.st-blocked { background: var(--app-warn-weak); color: var(--app-warn-text); }
.goal-state.st-pending { background: var(--app-warn-weak); color: var(--app-warn-text); }
.goal-state.st-running { background: var(--app-info-weak); color: #1456b8; }
.goal-turn { margin-left: auto; font-size: 12px; color: var(--app-text3, #888); }
.goal-block-reason { margin-top: 8px; display: flex; align-items: flex-start; gap: 6px; font-size: 12.5px; line-height: 1.5; color: var(--app-warn-text); }
.goal-objective { margin-top: 8px; font-size: 13px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.goal-objective.clamped { display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.goal-more { margin-top: 2px; border: none; background: none; color: var(--app-accent); font-size: 12px; cursor: pointer; padding: 0; }
.goal-hint { margin-top: 8px; font-size: 12.5px; line-height: 1.5; color: var(--app-text2, #555); background: var(--app-panel-2); border-radius: 6px; padding: 6px 8px; }
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
.goal-criterion-drafted { flex: none; font-size: 10.5px; color: var(--app-text3); border: 1px solid var(--border-color, #e5e6eb); border-radius: 4px; padding: 0 4px; line-height: 1.5; }
.goal-criterion-acts { flex: none; display: inline-flex; gap: 4px; }
.goal-criterion-acts button { border: 1px solid var(--border-color, #e5e6eb); background: transparent; border-radius: 4px; font-size: 11px; line-height: 1.6; padding: 0 6px; cursor: pointer; color: var(--app-text2, #555); }
.goal-criterion-acts button:hover { border-color: var(--app-accent); color: var(--app-accent); }
.goal-criterion-stale { flex: none; font-size: 11.5px; color: var(--app-warn-text); }
.goal-sync-warn { margin-top: 8px; font-size: 12px; color: var(--app-warn-text); background: var(--app-warn-weak); border-radius: 6px; padding: 6px 8px; }
.goal-remaining { margin-top: 10px; font-size: 12.5px; color: var(--app-danger); }
.goal-remaining-head { font-weight: 600; }
.goal-remaining-item { padding-left: 12px; position: relative; line-height: 1.6; }
.goal-remaining-item::before { content: '·'; position: absolute; left: 2px; }
</style>
