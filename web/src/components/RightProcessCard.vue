<template>
  <!-- 右栏「过程」卡：跑轮时实时滚动工具步，空闲时收成上一轮的摘要。
       数据就是引擎在维护的那份消息对象（m.toolCalls，随 SSE 逐条长出来），
       不另起取数——弹窗账本（SessionEventsPanel）才走 getSessionEvents 拉全量留痕。 -->
  <div v-if="msg" class="rpc-card">
    <button type="button" class="rpc-head" @click="open = !open">
      <span class="rpc-title">过程 · {{ running ? '跑轮中' : '上一轮' }}<template v-if="steps.length"> · {{ executedCount(steps) }} 步</template></span>
      <span v-if="running" class="rpc-live">实时</span>
      <caret-right-outlined class="rpc-caret" :class="{ open: open }" />
    </button>
    <div v-if="open" class="rpc-body">
      <div v-if="!steps.length" class="rpc-tip">{{ running ? '正在准备…' : '这一轮没有调用工具，直接作答。' }}</div>
      <div v-else class="rpc-list" ref="listEl">
        <div v-for="(t, ti) in shown" :key="ti" class="rpc-row"
             :class="{ run: t.status === 'start' && !t.denied, err: t.status === 'error', off: t.denied }">
          <span class="rpc-ic">
            <loading-outlined v-if="t.status === 'start' && !t.denied" spin />
            <check-outlined v-else-if="t.status === 'done'" />
            <close-circle-outlined v-else-if="t.status === 'error'" />
            <pause-outlined v-else />
          </span>
          <span class="rpc-name" :title="toolDesc(t.name, t.hint)">{{ toolLabel(t.name, t.label) }}</span>
          <span v-if="t.denied" class="rpc-dur" title="被守卫挡下，没有真正执行">被挡下</span>
          <!-- 在跑的排在前：start 那一份帧带的是 elapsedMs=0，按耗时先判会给正在跑的那一步报「0ms」，
               而「进行中」这一条永远轮不到——并发派遣一屏三行 0ms，读起来像三步都跑完了 -->
          <span v-else-if="t.status === 'start'" class="rpc-dur is-run">进行中</span>
          <span v-else-if="t.elapsedMs != null" class="rpc-dur">{{ toolDuration(Number(t.elapsedMs) || 0) }}</span>
        </div>
        <div v-if="omitted" class="rpc-more">前面还有 {{ omitted }} 步</div>
      </div>
      <div v-if="running" class="rpc-phase"><span class="rpc-dot" />{{ phaseText }}</div>
      <div class="rpc-foot" @click="$emit('open-events')">查看完整过程 ›</div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { CaretRightOutlined, CheckOutlined, CloseCircleOutlined, LoadingOutlined, PauseOutlined } from '@ant-design/icons-vue'
// 展示口径与气泡内时间线同一把尺：toolCallsView（并掉重复卡）、toolLabel（中文映射）、
// toolDuration（耗时文案）、executedCount（只数真执行过的）——右栏不另写一套，免得两处对不上
import { toolLabel, toolDesc, toolCallsView, executedCount, toolDuration } from '../chat/projections'

// msg=最近一条 AI 消息（含跑轮中 loading 的那条）：跑轮时它就是实时流，结束后它就是上一轮摘要
const props = defineProps({ msg: { type: Object, default: null } })
defineEmits(['open-events'])

const open = ref(true)
const listEl = ref(null)
const MAX_ROWS = 8

const running = computed(() => !!props.msg && props.msg.loading === true)
const steps = computed(() => toolCallsView(props.msg && props.msg.toolCalls))
const shown = computed(() => steps.value.slice(-MAX_ROWS))
const omitted = computed(() => Math.max(0, steps.value.length - MAX_ROWS))

// 跑轮开始时自动展开（用户手动收起后不抢：只在新一轮从静止变跑动时拉一次）
watch(running, (v, old) => { if (v && !old) open.value = true })

// 新步骤长出来时把列表滚到最底：盯的永远是最新那一步（与正文贴底跟随同一取向）
watch(() => [steps.value.length, open.value], () => {
  nextTick(() => { if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight })
})

// 跑轮中的阶段行：先思考、有 start 卡在跑就是执行工具、否则在输出
const phaseText = computed(() => {
  const m = props.msg
  if (!m) return ''
  if (steps.value.some(t => t.status === 'start' && !t.denied)) return '执行工具中'
  if (m.thinkLoading || (m.thinking && !m.content)) return '正在思考'
  return '正在输出'
})
</script>

<style scoped>
.rpc-card { background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 10px; padding: 10px 12px; }
.rpc-head { display: flex; align-items: center; gap: 6px; width: 100%; background: none; border: 0; padding: 0; text-align: left; cursor: pointer; font: inherit; }
.rpc-title { font-size: 11px; color: var(--app-text3); }
.rpc-live {
  flex: none; font-size: 10px; line-height: 1; padding: 3px 6px; border-radius: 8px;
  color: var(--app-accent); background: var(--app-accent-weak);
}
.rpc-caret { margin-left: auto; font-size: 10px; color: var(--app-text3); transition: transform .15s; }
.rpc-caret.open { transform: rotate(90deg); }
.rpc-body { margin-top: 8px; }
.rpc-tip { font-size: 11px; color: var(--app-text3); line-height: 1.6; }
.rpc-list { max-height: 180px; overflow-y: auto; display: flex; flex-direction: column; gap: 2px; }
.rpc-row { display: flex; align-items: center; gap: 6px; font-size: 12px; padding: 2px 0; min-width: 0; }
.rpc-row.err .rpc-name { color: var(--app-danger); }
.rpc-row.off .rpc-name { color: var(--app-text3); }
.rpc-ic { flex: none; font-size: 11px; color: var(--app-text3); }
.rpc-row.run .rpc-ic { color: var(--app-accent); }
.rpc-row.err .rpc-ic { color: var(--app-danger); }
.rpc-name { flex: 1; min-width: 0; color: var(--app-text2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rpc-row.run .rpc-name { color: var(--app-text); }
.rpc-dur { flex: none; font-size: 11px; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.rpc-dur.is-run { color: var(--app-accent); }
.rpc-more { font-size: 11px; color: var(--app-text3); padding: 2px 0; }
.rpc-phase { display: flex; align-items: center; gap: 6px; font-size: 11px; color: var(--app-accent); margin-top: 6px; padding-top: 6px; border-top: 1px solid var(--app-border); }
.rpc-dot { flex: none; width: 6px; height: 6px; border-radius: 50%; background: var(--app-accent); animation: rpc-blink 1.2s ease-in-out infinite; }
@keyframes rpc-blink { 0%, 100% { opacity: 1; } 50% { opacity: 0.25; } }
.rpc-foot { font-size: 11px; color: var(--app-accent); cursor: pointer; margin-top: 6px; padding-top: 6px; border-top: 1px solid var(--app-border); }
.rpc-foot:hover { color: var(--app-text2); }
</style>
