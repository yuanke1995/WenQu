<template>
  <!-- 一轮的「过程块」开关（判据全在 projections 的 turn* 那组函数里，两端共用）：
       固定在回答顶部；跑着时是跟着秒走的状态行，答完收成一行总览（DSH TurnProcessNodeView 同款）。
       展开后过程块长在它下面、正文上面；正文本身永远连续。耗时只在这一处报。 -->
  <div v-if="turnShow(m)" class="turn-bar" :class="{ 'is-m': mobile, 'is-top': turnTop(m) }">
    <!-- 完成态·可展开：整行都是热区 -->
    <button v-if="turnToggleable(m) && !noToggle" class="tb-head" :class="{ 'is-open': turnOpen(m) }" type="button" :title="turnTitle(m)" @click.stop="toggleTurn(m)">
      <span class="tb-text" :class="{ warn: turnWarn(m) }">{{ turnHead(m) }}</span>
      <down-outlined class="tb-chevron" :class="{ open: turnOpen(m) }" />
    </button>
    <!-- 完成态·底下没过程可展开：同一行样式，不给三角 -->
    <div v-else-if="turnTop(m)" class="tb-head is-plain" :title="turnTitle(m)">
      <span class="tb-text" :class="{ warn: turnWarn(m) }">{{ turnHead(m) }}</span>
    </div>
    <!-- 跑动态：分割线 + 波纹图标 + 扫光文字（答完前它是这一轮唯一进行中的提示） -->
    <div v-else class="tb-status" :title="turnTitle(m)">
      <svg class="tb-wave" viewBox="0 0 16 16" width="14" height="14" fill="none" aria-hidden="true">
        <path d="M2 6.2c2-2.4 4-2.4 6 0s4 2.4 6 0" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
        <path d="M2 11c2-2.4 4-2.4 6 0s4 2.4 6 0" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" class="tb-wave-b" />
      </svg>
      <span class="tb-text" :class="turnWarn(m) ? 'warn' : 'shimmer'">{{ turnHead(m) }}</span>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, watch } from 'vue'
import { DownOutlined } from '@ant-design/icons-vue'
import { turnShow, turnOpen, toggleTurn, turnHead, turnTitle, turnToggleable, turnWarn, turnTop, holdTick } from '../chat/projections'

// 折叠态必须落在消息对象上（m._turnOpen），不能放组件本地 ref：真正决定过程行渲不渲染的是
// 父级模板里那份摊平结果，本地 ref 管不到它。
const props = defineProps({
  m: { type: Object, required: true },
  mobile: Boolean,
  /** 分享页等不渲染过程块的场景：只报用时，不给一个点了没反应的下开三角 */
  noToggle: Boolean
})

// 跑着的时候这一行是跟着秒走的计时器。主对话页的秒表由问答引擎开轮时挂，分享页没有引擎——
// 由正在进行的这一条自己挂表，答完或卸载就摘（读数判据在 projections 的 turnElapsedMs）。
let releaseTick = null
watch(() => props.m && props.m.loading, running => {
  if (running && !releaseTick) releaseTick = holdTick()
  else if (!running && releaseTick) { releaseTick(); releaseTick = null }
}, { immediate: true })
onBeforeUnmount(() => { if (releaseTick) releaseTick() })
</script>

<style scoped>
.turn-bar { margin: 0 0 4px; }
/* 完成态簇头（DSH TurnProcessNodeView 同款）：无底无框的一行文字，只押一条 0.5px 细底线；
   整行可点，13px 次要灰，悬停加深。数字随行等宽（tabular-nums），读数不抖。 */
.tb-head {
  display: flex; align-items: center; width: 100%; min-width: 0;
  padding: 0 0 7px; border: none; border-bottom: 0.5px solid var(--app-border);
  background: none; color: var(--app-text3); font: inherit; font-size: 13px; line-height: 24px;
  text-align: left; cursor: pointer; user-select: none; transition: color .1s ease;
}
.tb-head:hover { color: var(--app-text2); }
.tb-head.is-plain { cursor: default; }
.tb-text { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-variant-numeric: tabular-nums; }
.tb-chevron { flex: none; margin-left: 4px; font-size: 11px; color: inherit; transition: transform .1s ease; }
.tb-chevron.open { transform: rotate(180deg); }
/* 跑动态状态行（DSH RunningStatus 同款）：顶上一条分割线，波纹图标 + 扫光文字，高 28px */
.tb-status { position: relative; display: flex; align-items: center; gap: 6px; margin-top: 4px; height: 28px; min-width: 0; color: var(--app-accent); }
.tb-status::before {
  content: ''; position: absolute; top: -4px; left: 0; right: 0; height: 0.5px;
  background: var(--app-border); pointer-events: none;
}
.tb-wave { flex: none; }
.tb-wave-b { opacity: .5; animation: tb-wave-b 1.4s ease-in-out infinite alternate; }
@keyframes tb-wave-b { from { opacity: .3; } to { opacity: .85; } }
/* 扫光文字（DSH TextShimmer 简化版）：次要灰底色上一道高光横扫过去，替代旧的整句呼吸变色 */
.tb-text.shimmer {
  color: transparent; background: linear-gradient(105deg, var(--app-text3) 0%, var(--app-text3) 40%, var(--app-text) 50%, var(--app-text3) 60%, var(--app-text3) 100%);
  background-size: 220% 100%; -webkit-background-clip: text; background-clip: text;
  animation: tb-shimmer 1.8s linear infinite;
}
@keyframes tb-shimmer { from { background-position: 120% 0; } to { background-position: -120% 0; } }
/* 异常态（断线自动重试）：静止警示色，扫光只留给正常在跑的状态 */
.tb-text.warn { color: var(--app-warn-text); background: none; animation: none; }
@media (prefers-reduced-motion: reduce) {
  .tb-text.shimmer { animation: none; color: var(--app-text3); background: none; }
  .tb-wave-b { animation: none; opacity: .5; }
  .tb-chevron { transition: none; }
}
/* 触屏形态：整行热区保留（40px），样式同样去框去底，只留细底线 */
.is-m { margin: 0 0 6px; }
.is-m .tb-head { min-height: 40px; padding: 8px 0; font-size: 13px; }
.is-m .tb-status { min-height: 36px; }
</style>
