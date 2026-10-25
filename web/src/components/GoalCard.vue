<template>
  <!--
    目标卡：只读详情面（目标模式的「事实面」）。
    只挂在移动壳「状态与来源」sheet 的目标小节（PC 右栏的目标卡已整删，状态只看输入框顶部那条目标条）。
    动作面不在这张卡上——改原文 / 停止 / 继续 / 删除只在输入框顶部那条
    目标条上，这里只回答两件事：现在什么状态、为什么停在这里（跑到第几轮由目标条说）。
    文案只讲用户结果，不露框架机制（TASK_STATE/beginTask 这类词不许出现）。
    版面硬约束：容器很窄，所以原文超过三行折起来，不铺清单。
  -->
  <div class="goal-card" :class="'goal-' + (card.status || '').toLowerCase()">
    <div class="goal-card-head">
      <!-- 「目标」二字不在这里说：外壳的「目标」小节标题已报过卡名，
           卡头再说一遍就是同一个词出现两回。图标只做视觉锚点。 -->
      <aim-outlined class="goal-card-ic" />
      <span class="goal-state" :class="'st-' + stateClass">
        <i class="goal-state-dot" />{{ stateLabel }}
      </span>
    </div>

    <!-- 目标原文：超过三行折叠，展开入口放在原文下面，长目标也能一眼读到状态与轮次 -->
    <div class="goal-objective" :class="{ clamped: needsFold && !open }">{{ card.objective }}</div>
    <button v-if="needsFold" type="button" class="goal-more" @click="open = !open">
      {{ open ? '收起' : '展开' }}
    </button>

    <!-- 后端给的那句人话提示（如「第 3 轮自动接着推进」），只展示结果，不解释机制 -->
    <div v-if="card.hint" class="goal-hint">{{ card.hint }}</div>

    <!-- 任务运行时（2026-10-22）：阶段/等待/核验中说人话；预算与结果只在这张事实面上出现 -->
    <div v-if="phaseLine" class="goal-phase">{{ phaseLine }}</div>
    <div v-if="budgetLine" class="goal-budget">{{ budgetLine }}</div>
    <div v-if="outcomeLine" class="goal-outcome">{{ outcomeLine }}</div>

    <!-- 停下来的原因：状态词只说「已结束 / 已暂停」，卡在哪全靠这一行说（不点进任何清单也读得到） -->
    <div v-if="blockReason" class="goal-block-reason">
      <exclamation-circle-outlined /> {{ blockReason }}
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { AimOutlined, ExclamationCircleOutlined } from '@ant-design/icons-vue'
import { goalStateLabelOf, goalStateClassOf, goalBlockReasonOf, goalOutcomeLineOf } from '../chat/projections'

// 纯展示卡：没有动作、没有事件、不改状态——目标的四个动作只在输入框顶部那条目标条上。
const props = defineProps({
  /** 目标视图模型（goal_state 帧/右栏回显同一份）：{goalId,status,objective,round,maxRounds,hint,endReason,blockedReason,...} */
  card: { type: Object, required: true }
})

const open = ref(false)
const needsFold = computed(() => (props.card.objective || '').length > 120)

// 状态词与判据一律走共享口径（摘要条、右栏头、这张卡说的是同一句话）；
// 停在哪由 goalBlockReasonOf 那行原因说，不在状态词里挤第二层意思。
// 轮次不在这张卡上：跑到了第几轮由输入框顶部那条目标条说（第 x 轮坐在「进行中」前面）。
const stateClass = computed(() => goalStateClassOf(props.card))
const stateLabel = computed(() => goalStateLabelOf(props.card))
const blockReason = computed(() => goalBlockReasonOf(props.card))

const phaseLine = computed(() => {
  const c = props.card
  if (['ACHIEVED', 'FAILED', 'CANCELLED'].includes(c.status)) return ''
  if (c.stale) return '执行进程已中断，点「继续」会先核对状态再接着跑'
  if (c.phase === 'COMPLETION_CHECK') return '正在确认是否完成：这一轮不会做新工作'
  if (c.status === 'WAITING_APPROVAL') return '有一项操作在等你确认'
  if (c.status === 'WAITING_EVENT') return '在等外部结果，条件满足后会自动继续'
  if (c.status === 'VERIFYING') return '正在按证据核验完成申请'
  if (c.phase === 'QUEUED' && (c.status === 'RUNNING' || c.status === 'QUEUED')) return '已排队，下一拍开始'
  const stage = { EXPLORE: '探索', PLAN: '规划', EXECUTE: '执行', VERIFY: '核验', DELIVER: '交付' }[c.stage]
  return stage ? `阶段：${stage}` : ''
})

const budgetLine = computed(() => {
  const b = props.card.budget
  if (!b) return ''
  if (props.card.status === 'ACHIEVED') return `共 ${b.rounds} 轮完成`
  // 后端把「这一项不限制」出成 0；四项全 0 时这行没有可说的，只有一项 0 仍要报已用几轮，
  // 否则整行消失等于把进度也一起藏了
  if (!b.maxRounds && !b.maxToolCalls && !b.maxTokens && !b.maxMs) return ''
  const pct = Math.round((Number(b.percent) || 0) * 100)
  return b.maxRounds
    ? `预算：${b.rounds}/${b.maxRounds} 轮 · ${pct}%`
    : `已跑 ${b.rounds} 轮 · 预算占 ${pct}%`
})

const outcomeLine = computed(() => goalOutcomeLineOf(props.card))
</script>

<style scoped>
.goal-card { border: 1px solid var(--border-color, #e5e6eb); border-radius: 10px; padding: 10px 12px; margin: 8px 0; background: var(--bg-color, #fff); }
.goal-card-head { display: flex; align-items: center; gap: 8px; }
.goal-card-ic { color: var(--app-accent); flex: none; }
/* 状态词：色点 + 词，各档一色。进行中那一档的点轻呼吸——静态的蓝点和灰点分不出「在跑」和「挂着」 */
.goal-state { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; padding: 1px 8px; border-radius: 8px; background: #f0f1f3; color: #555; white-space: nowrap; }
.goal-state-dot { flex: none; width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.goal-state.st-achieved { background: var(--app-ok-weak); color: var(--app-ok); }
.goal-state.st-ended { background: var(--app-danger-weak); color: var(--app-danger); }
/* 暂停：用户自己踩的刹车，还归用户管，用警示色而不是「已终结」的红 */
.goal-state.st-paused { background: var(--app-warn-weak); color: var(--app-warn-text); }
.goal-state.st-running { background: var(--app-info-weak); color: var(--app-accent); }
.goal-state.st-running .goal-state-dot { animation: goal-dot-breathe 1.6s ease-in-out infinite; }
@keyframes goal-dot-breathe { 0%, 100% { opacity: 1; } 50% { opacity: .3; } }
@media (prefers-reduced-motion: reduce) { .goal-state.st-running .goal-state-dot { animation: none; } }
.goal-block-reason { margin-top: 8px; display: flex; align-items: flex-start; gap: 6px; font-size: 12.5px; line-height: 1.5; color: var(--app-warn-text); }
/* 原文是这张卡里最该被读到的字：比正文大半号、加半档字重 */
.goal-objective { margin-top: 8px; font-size: 13.5px; font-weight: 500; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.goal-objective.clamped { display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.goal-more { margin-top: 2px; border: none; background: none; color: var(--app-accent); font-size: 12px; cursor: pointer; padding: 0; }
.goal-hint { margin-top: 8px; font-size: 12.5px; line-height: 1.5; color: var(--app-text2, #555); background: var(--app-panel-2); border-radius: 6px; padding: 6px 8px; }
.goal-phase { margin-top: 8px; font-size: 12px; line-height: 1.5; color: var(--app-text2, #555); }
.goal-budget { margin-top: 4px; font-size: 12px; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.goal-outcome { margin-top: 4px; font-size: 12px; color: var(--app-ok); }
</style>
