<template>
  <!--
    目标卡（目标模式的「详情面」）：与 PlanCard.vue / AskRecordCard.vue 同族，
    裁决区在底部输入框顶替位（goalp-*，见 ChatPage.vue），这张卡只讲状态与证据。
    两端共用：PC 挂在右栏「目标」面板里，移动壳挂在「状态与来源」sheet 的目标小节
    （外壳不同、取数同源）。进行中的操作面不在这张卡上，在输入框正上方那条摘要条。
    文案只讲用户结果，不露框架机制（TASK_STATE/beginTask/verifier 这类词不许出现）。
    版面硬约束：右栏只有 ~260px。验收标准若把勾框、原文、结论、两个动作全排一行，
    原文会被挤成一两个字一行的竖排（2026-10-22 改版前的形态），故逐条收成两行——
    原文独占一行，结论与人工核验缩到第二行，勾框跨两行保持左对齐。
  -->
  <div class="goal-card" :class="'goal-' + (card.status || '').toLowerCase()">
    <div class="goal-card-head">
      <aim-outlined class="goal-card-ic" />
      <span class="goal-card-title">目标</span>
      <span class="goal-state" :class="'st-' + stateClass">
        <i class="goal-state-dot" />{{ stateLabel }}
      </span>
      <span v-if="card.turnIndex" class="goal-turn">{{ roundLine }}</span>
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
      <div class="goal-criteria-head">
        <span class="goal-criteria-label">验收标准</span>
        <span v-if="verifiedCount" class="goal-criteria-progress">已验 {{ verifiedCount }}/{{ card.criteria.length }}</span>
      </div>
      <div v-for="c in card.criteria" :key="c.key || c.id" class="goal-criterion" :class="'cv-' + criterionClass(c)">
        <span class="goal-criterion-box">{{ criterionMark(c) }}</span>
        <span class="goal-criterion-text">{{ c.text }}</span>
        <span class="goal-criterion-meta">
          <span v-if="c.drafted" class="goal-criterion-drafted">助手起草</span>
          <span v-if="c.verification && c.verification.stale" class="goal-criterion-chip stale">已过期</span>
          <span v-else-if="c.verification" class="goal-criterion-chip" :class="'oc-' + (c.verification.outcome || '').toLowerCase()">{{ outcomeLabel(c.verification.outcome) }}</span>
          <span v-else class="goal-criterion-chip none">未验</span>
          <span v-if="canMark(c)" class="goal-criterion-acts">
            <button type="button" class="gc-act pass" @click="$emit('mark', { key: c.key, outcome: 'PASSED' })">达成</button>
            <button type="button" class="gc-act fail" @click="$emit('mark', { key: c.key, outcome: 'FAILED' })">没过</button>
          </span>
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
import { goalStateLabelOf, goalStateClassOf, goalBlockReasonOf, goalRemainingOf, goalRoundLineOf } from '../chat/projections'

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
// 「受阻」这一档卡在哪由 goalBlockReasonOf 那行原因说，不在状态词里挤第二层意思。
// 轮次文案同理（goalRoundLineOf）——它同时在摘要条的悬浮行里，两处各写一份就会漂。
const stateClass = computed(() => goalStateClassOf(props.card))
const stateLabel = computed(() => goalStateLabelOf(props.card))
const roundLine = computed(() => goalRoundLineOf(props.card))
const blockReason = computed(() => goalBlockReasonOf(props.card))

/** 这一条不再自己往前推了——未完成清单就在这时候给 */
const stopped = computed(() => ['ACHIEVED', 'FAILED', 'CANCELLED', 'ENDED_INCOMPLETE'].includes(props.card.status))

/** 还没通过验证的标准文字（必须 map 成 text——直接留对象，模板里会把整条标准打成一份 JSON 给用户读） */
const remaining = computed(() => goalRemainingOf(props.card))

/** 小节头的「已验 x/y」：只数有确定结论的（通过/未过）。异常与过期都没有结论，不算验过 */
const verifiedCount = computed(() => (props.card.criteria || [])
  .filter(c => c.verification && !c.verification.stale && ['PASSED', 'FAILED'].includes(c.verification.outcome)).length)

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
/* 状态词：色点 + 词，各档一色。进行中那一档的点轻呼吸——静态的蓝点和灰点分不出「在跑」和「挂着」 */
.goal-state { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; padding: 1px 8px; border-radius: 8px; background: #f0f1f3; color: #555; white-space: nowrap; }
.goal-state-dot { flex: none; width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.goal-state.st-achieved { background: var(--app-ok-weak); color: var(--app-ok); }
.goal-state.st-ended { background: var(--app-danger-weak); color: var(--app-danger); }
/* 受阻：卡住但还归用户管，用警示色而不是「已终结」的红 */
.goal-state.st-blocked { background: var(--app-warn-weak); color: var(--app-warn-text); }
.goal-state.st-pending { background: var(--app-warn-weak); color: var(--app-warn-text); }
.goal-state.st-running { background: var(--app-info-weak); color: var(--app-accent); }
.goal-state.st-running .goal-state-dot { animation: goal-dot-breathe 1.6s ease-in-out infinite; }
@keyframes goal-dot-breathe { 0%, 100% { opacity: 1; } 50% { opacity: .3; } }
@media (prefers-reduced-motion: reduce) { .goal-state.st-running .goal-state-dot { animation: none; } }
/* 轮次是背景信息，收成弱标签：不抢状态词的色，也不像原来那样一行灰字滑到右边 */
.goal-turn { margin-left: auto; flex: none; font-size: 11px; line-height: 17px; padding: 0 6px; border-radius: 6px; border: 1px solid var(--border-color, #e5e6eb); background: var(--app-panel-2); color: var(--app-text3, #888); white-space: nowrap; }
.goal-block-reason { margin-top: 8px; display: flex; align-items: flex-start; gap: 6px; font-size: 12.5px; line-height: 1.5; color: var(--app-warn-text); }
/* 原文是这张卡里最该被读到的字：比正文大半号、加半档字重 */
.goal-objective { margin-top: 8px; font-size: 13.5px; font-weight: 500; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.goal-objective.clamped { display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.goal-more { margin-top: 2px; border: none; background: none; color: var(--app-accent); font-size: 12px; cursor: pointer; padding: 0; }
.goal-hint { margin-top: 8px; font-size: 12.5px; line-height: 1.5; color: var(--app-text2, #555); background: var(--app-panel-2); border-radius: 6px; padding: 6px 8px; }

/* 验收标准小节：给清单一个名字 + 一个「还差几条」的总数，标准再多也是一屏能读的小节 */
.goal-criteria { margin-top: 10px; }
.goal-criteria-head { display: flex; align-items: baseline; gap: 8px; margin-bottom: 2px; }
.goal-criteria-label { font-size: 11px; font-weight: 600; color: var(--app-text3, #888); }
.goal-criteria-progress { margin-left: auto; font-size: 11px; color: var(--app-text3, #888); font-variant-numeric: tabular-nums; }
/* 两行式：勾框跨两行（grid-row: span 2），原文在第一行占满，结论与动作退到第二行 */
.goal-criterion { display: grid; grid-template-columns: auto minmax(0, 1fr); column-gap: 8px; row-gap: 2px; padding: 5px 0; }
.goal-criterion-box { grid-row: span 2; align-self: start; margin-top: 2px; width: 15px; height: 15px; border-radius: 4px; border: 1px solid #c9ccd1; display: inline-flex; align-items: center; justify-content: center; font-size: 11px; line-height: 1; }
.goal-criterion.cv-passed .goal-criterion-box { border-color: var(--app-ok); color: var(--app-ok); background: var(--app-ok-weak); }
.goal-criterion.cv-failed .goal-criterion-box { border-color: var(--app-danger); color: var(--app-danger); background: var(--app-danger-weak); }
.goal-criterion.cv-error .goal-criterion-box { border-color: var(--app-warn-text); color: var(--app-warn-text); background: var(--app-warn-weak); }
.goal-criterion.cv-stale .goal-criterion-box { border-color: var(--app-warn-text); color: var(--app-warn-text); }
.goal-criterion.cv-rejected { opacity: .55; }
.goal-criterion-text { font-size: 12.5px; line-height: 1.5; word-break: break-word; }
.goal-criterion-meta { grid-column: 2; display: flex; align-items: center; flex-wrap: wrap; gap: 6px; min-height: 18px; }
/* 结论 chip：一档一色。「未验」不配底色——它不是一个结论，是「还没有结论」 */
.goal-criterion-chip { flex: none; font-size: 11px; line-height: 16px; padding: 0 6px; border-radius: 5px; background: #f0f1f3; color: #888; white-space: nowrap; }
.goal-criterion-chip.none { background: none; padding: 0; color: var(--app-text3, #888); }
.goal-criterion-chip.oc-passed { background: var(--app-ok-weak); color: var(--app-ok); }
.goal-criterion-chip.oc-failed { background: var(--app-danger-weak); color: var(--app-danger); }
.goal-criterion-chip.oc-error { background: var(--app-warn-weak); color: var(--app-warn-text); }
.goal-criterion-chip.stale { background: var(--app-warn-weak); color: var(--app-warn-text); }
.goal-criterion-drafted { flex: none; font-size: 10.5px; color: var(--app-text3); border: 1px solid var(--border-color, #e5e6eb); border-radius: 4px; padding: 0 4px; line-height: 15px; }
/* 人工核验：贴第二行右端，hover 才给颜色暗示——默认两个都是中性钮，不让「达成」看着像默认选项 */
.goal-criterion-acts { flex: none; display: inline-flex; gap: 4px; margin-left: auto; }
.gc-act { border: 1px solid var(--border-color, #e5e6eb); background: var(--app-panel); border-radius: 5px; font-size: 11px; line-height: 17px; padding: 0 8px; cursor: pointer; color: var(--app-text2, #555); }
.gc-act.pass:hover { border-color: var(--app-ok); color: var(--app-ok); background: var(--app-ok-weak); }
.gc-act.fail:hover { border-color: var(--app-danger); color: var(--app-danger); background: var(--app-danger-weak); }
.goal-sync-warn { margin-top: 8px; font-size: 12px; color: var(--app-warn-text); background: var(--app-warn-weak); border-radius: 6px; padding: 6px 8px; }
.goal-remaining { margin-top: 10px; font-size: 12.5px; color: var(--app-danger); }
.goal-remaining-head { font-weight: 600; }
.goal-remaining-item { padding-left: 12px; position: relative; line-height: 1.6; }
.goal-remaining-item::before { content: '·'; position: absolute; left: 2px; }
</style>
