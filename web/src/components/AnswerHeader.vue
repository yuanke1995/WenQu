<template>
  <div v-if="show" class="ans-head" :class="{ 'is-warn': warn, 'is-run': running, 'no-id': !hasIdentity }">
    <span v-if="agent" class="ah-ava"><AgentAvatar :agent="agent" :size="26" /></span>
    <div class="ah-id">
      <div v-if="hasIdentity" class="ah-name">
        <span class="ah-agent">{{ name }}</span>
        <span v-if="modelLabel" class="ah-model" :title="modelLabel">{{ modelLabel }}</span>
      </div>
      <div class="ah-meta">
        <svg v-if="running" class="ah-wave" viewBox="0 0 16 16" width="14" height="14" fill="none" aria-hidden="true">
          <path d="M2 6.2c2-2.4 4-2.4 6 0s4 2.4 6 0" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
          <path d="M2 11c2-2.4 4-2.4 6 0s4 2.4 6 0" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" class="ah-wave-b" />
        </svg>
        <span class="ah-state" :class="{ warn, run: running }" :title="headTitle">{{ head }}</span>
        <template v-if="stepCount">
          <span class="ah-dot"></span>
          <span class="ah-steps">{{ stepCount }} 步</span>
        </template>
        <template v-if="failCount">
          <span class="ah-dot"></span>
          <span class="ah-fail">{{ failCount }} 步失败</span>
        </template>
      </div>
    </div>
    <div class="ah-acts">
      <AnswerOutline v-if="!running" :msg-index="msgIndex" />
      <button v-if="toggleable" type="button" class="ah-btn" :class="{ warn: failCount }"
              :aria-expanded="open" :title="open ? '收起过程' : '展开过程'" @click.stop="onToggle">
        <span>过程</span>
        <span v-if="stepCount" class="ah-count">{{ stepCount }}</span>
        <span v-if="failCount" class="ah-fail-dot" :title="failCount + ' 步失败'"></span>
        <DownOutlined class="ah-caret" :class="{ open }" />
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, watch } from 'vue'
import { DownOutlined } from '@ant-design/icons-vue'
import AgentAvatar from './AgentAvatar.vue'
import AnswerOutline from './AnswerOutline.vue'
import { turnShow, turnOpen, toggleTurn, turnHead, turnToggleable, turnWarn, turnExpandable, holdTick } from '../chat/projections'
import { roundSteps } from '../chat/roundSummary'

const props = defineProps({
  m: { type: Object, required: true },
  /** 回答身份：{ icon, isBuiltin, ... }；查不到就不挂头像 */
  agent: { type: Object, default: null },
  /** 作答智能体名；空＝还没拿到归属（全局配置作答），此时不显示身份 */
  name: { type: String, default: '' },
  modelLabel: { type: String, default: '' },
  msgIndex: { type: Number, default: -1 }
})

const show = computed(() => props.m && props.m.role === 'ai' && turnShow(props.m))
// 归属是「这一轮由谁作答」：没选智能体（走全局配置）时后端也没有这个人，
// 前端不拿「问渠」顶名——查不到归属就整块身份不显示，只留状态行。
const hasIdentity = computed(() => !!(props.name || props.agent))
const running = computed(() => !!props.m.loading)
const warn = computed(() => turnWarn(props.m))
const head = computed(() => turnHead(props.m))
const headTitle = computed(() => head.value)
const open = computed(() => turnOpen(props.m))
const toggleable = computed(() => turnToggleable(props.m))
const steps = computed(() => roundSteps(props.m))
// 只有真的能展开过程时才报步数：时间线作废的轮次里，toolCalls 的数量点了未必有东西可看
const stepCount = computed(() => (turnExpandable(props.m) ? steps.value.length : 0))
const failCount = computed(() => steps.value.filter(t => t.status === 'error').length)

const onToggle = () => { toggleTurn(props.m) }

// 跑动中这一行是跟着秒走的状态行。原先这口表由 TurnBar 挂；状态行并入回答头后必须在这里补上，
// 否则「正在溯源，用时 3 秒」会停在第一秒。答完或卸载即摘。
let releaseTick = null
watch(running, v => {
  if (v && !releaseTick) releaseTick = holdTick()
  else if (!v && releaseTick) { releaseTick(); releaseTick = null }
}, { immediate: true })
onBeforeUnmount(() => { if (releaseTick) releaseTick() })
</script>

<style scoped>
/* 回答头：一条回答的身份、状态与过程入口。
   吸顶是 CSS sticky（父级 .bubble.ai 就是整条回答），滚出这条回答后自动让位，
   不需要 IntersectionObserver，也不会盖到下一轮。 */
.ans-head {
  position: sticky; top: 0; z-index: 6;
  display: flex; align-items: flex-start; gap: 10px;
  padding: 7px 0 9px; margin: 0 0 12px;
  background: var(--app-panel);
  border-bottom: .5px solid var(--app-border);
}
.ah-ava { flex: none; line-height: 0; }
.ah-id { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 2px; padding-top: 1px; }
.ah-name { display: flex; align-items: center; gap: 7px; min-width: 0; }
.ah-agent { font-size: 14px; font-weight: 600; color: var(--app-text); white-space: nowrap; }
.ah-model {
  min-width: 0; max-width: 260px; height: 18px; padding: 0 6px; border-radius: 5px;
  border: 1px solid var(--app-border); background: var(--app-panel-2);
  font-size: 10.5px; line-height: 16px; color: var(--app-text3);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.ah-meta { display: flex; align-items: center; gap: 6px; min-width: 0; font-size: 11px; color: var(--app-text3); }
.ah-state {
  min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.ah-state.run { color: var(--app-accent); }
/* 跑动状态前的波纹图标：与改版前的整轮状态行同一枚记号，扫光不再做，仅保留明暗呼吸 */
.ah-wave { flex: none; color: var(--app-accent); }
.ah-wave-b { opacity: .5; animation: ah-wave-b 1.4s ease-in-out infinite alternate; }
@keyframes ah-wave-b { from { opacity: .3; } to { opacity: .85; } }
.ah-state.warn { color: var(--app-warn-text); }
.ah-dot { width: 2px; height: 2px; border-radius: 50%; background: var(--app-border-strong); flex: none; }
.ah-steps { flex: none; font-variant-numeric: tabular-nums; }
.ah-fail { flex: none; color: var(--app-danger); white-space: nowrap; }
.ah-acts { margin-left: auto; display: flex; align-items: center; gap: 4px; flex: none; }
.ah-btn {
  height: 26px; padding: 0 7px 0 8px; border: 1px solid transparent; border-radius: 7px;
  background: transparent; color: var(--app-text2); font: inherit; font-size: 11.5px;
  display: inline-flex; align-items: center; gap: 5px; white-space: nowrap; cursor: pointer;
}
.ah-btn:hover { background: var(--app-panel-2); color: var(--app-text); }
.ah-btn.warn { color: var(--app-danger); background: var(--app-danger-weak); border-color: var(--app-danger-border); }
.ah-btn.warn:hover { background: #ffe7e4; }
.ah-count {
  min-width: 16px; height: 16px; padding: 0 4px; border-radius: 8px;
  background: rgba(0, 0, 0, .06); font-size: 10px; line-height: 16px;
  font-variant-numeric: tabular-nums; text-align: center;
}
.ah-btn.warn .ah-count { background: rgba(207, 19, 34, .10); }
.ah-fail-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--app-danger); flex: none; }
.ah-caret { font-size: 10px; transition: transform .12s ease; }
.ah-caret.open { transform: rotate(180deg); }
@media (prefers-reduced-motion: reduce) {
  .ah-wave-b { animation: none; opacity: .5; }
  .ah-caret { transition: none; }
}
@media (max-width: 768px) {
  .ans-head { padding: 6px 0 8px; }
  .ah-model { max-width: 140px; }
  .ah-btn { min-height: 32px; }
}
</style>
