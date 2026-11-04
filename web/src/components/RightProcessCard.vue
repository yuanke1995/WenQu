<template>
  <!-- 右栏「本轮」卡：这一轮跑了哪几步、每一步在做什么事。
       行内不报单步耗时——耗时只在气泡那条状态行（TurnBar）报整轮一个数，逐步时长与事件账本
       归「查看全部步骤」弹窗（SessionEventsPanel 走 getSessionEvents 拉全量留痕）。
       参数摘要与气泡工具卡同一把尺（toolBrief：命令/检索词/路径/文件名/问了谁）；
       230px 的栏宽放不下一行同时容纳动作名与参数，故参数单独一行。 -->
  <div v-if="msg" class="rpc-card">
    <button type="button" class="rpc-head" @click="open = !open">
      <span class="rpc-title">本轮<template v-if="headTail"> · {{ headTail }}</template></span>
      <caret-right-outlined class="rpc-caret" :class="{ open: open }" />
    </button>
    <div v-if="open" class="rpc-body">
      <div v-if="!steps.length" class="rpc-tip">{{ running ? '正在准备…' : '这一轮没有调用工具，直接作答。' }}</div>
      <div v-else class="rpc-list" ref="listEl">
        <div v-for="(t, ti) in shown" :key="ti" class="rpc-step"
             :class="{ run: isRun(t), err: t.status === 'error', off: t.denied }">
          <div class="rpc-row">
            <span class="rpc-ic">
              <loading-outlined v-if="isRun(t)" spin />
              <check-outlined v-else-if="t.status === 'done'" />
              <close-circle-outlined v-else-if="t.status === 'error'" />
              <pause-outlined v-else />
            </span>
            <span class="rpc-name" :title="toolDesc(t.name, t.hint)">{{ toolLabel(t.name, t.label) }}</span>
            <!-- 完成的不写「完成」：勾已经说了；只有需要解释的状态才占字位 -->
            <span v-if="t.denied" class="rpc-st off" title="被守卫挡下，没有真正执行">被挡下</span>
            <span v-else-if="isRun(t)" class="rpc-st run">进行中</span>
            <span v-else-if="t.status === 'error'" class="rpc-st err">失败</span>
          </div>
          <div v-if="briefOf(t)" class="rpc-arg" :title="briefOf(t)">{{ briefOf(t) }}</div>
        </div>
        <div v-if="omitted" class="rpc-more">前面还有 {{ omitted }} 步</div>
      </div>
      <!-- 等人拍板的那一句：卡片本体在输入框上方，这里只负责「看得见 + 点得回去」；
           没在等的时候这一行让位给失败步的原因 -->
      <div v-if="hold" class="rpc-hold" :class="{ bad: hold.bad, act: !!hold.go }"
           :title="hold.tip" @click="hold.go && $emit('open-hitl', hold.kind)">
        <span class="rpc-hold-ic">
          <pause-outlined v-if="!hold.bad" />
          <close-circle-outlined v-else />
        </span>
        <span class="rpc-hold-t">{{ hold.text }}</span>
      </div>
      <div class="rpc-foot" @click="$emit('open-events')">查看全部步骤 ›</div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { CaretRightOutlined, CheckOutlined, CloseCircleOutlined, LoadingOutlined, PauseOutlined } from '@ant-design/icons-vue'
// 展示口径与气泡内工具卡同一把尺：toolLabel（中文映射）、toolBrief（关键入参摘要）；
// 卡头/等待/失败那几句的判定与移动端 sheet 共用 roundSummary，两端不许各写一套。
// 耗时那一份（toolDuration）刻意不再引——右栏不报单步时长。
import { toolLabel, toolDesc, toolBrief } from '../chat/projections'
import { roundSteps, roundHold, roundHeadTail, roundPhase, isRunStep } from '../chat/roundSummary'

// msg=最近一条 AI 消息（含跑轮中 loading 的那条）：跑轮时它就是实时流，结束后它就是上一轮
const props = defineProps({ msg: { type: Object, default: null } })
defineEmits(['open-events', 'open-hitl'])

const open = ref(true)
const listEl = ref(null)
// 一步两行，帽体按 5 步收：与原先 8 行单行的实际高度相当，不因为改了形态就把下面的卡顶出首屏
const MAX_ROWS = 5

const running = computed(() => !!props.msg && props.msg.loading === true)
const steps = computed(() => roundSteps(props.msg))
const shown = computed(() => steps.value.slice(-MAX_ROWS))
const omitted = computed(() => Math.max(0, steps.value.length - MAX_ROWS))

// 跑轮开始时自动展开（用户手动收起后不抢：只在新一轮从静止变跑动时拉一次）
watch(running, (v, old) => { if (v && !old) open.value = true })

// 新步骤长出来时把列表滚到最底：盯的永远是最新那一步（与正文贴底跟随同一取向）
watch(() => [steps.value.length, open.value], () => {
  nextTick(() => { if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight })
})

const isRun = isRunStep
const briefOf = t => toolBrief(t)
// 等人拍板（或失败步）那一句：判据与移动端 sheet 同一份
const hold = computed(() => roundHold(props.msg))
const headTail = computed(() => roundHeadTail(props.msg, roundPhase(props.msg)))
</script>

<style scoped>
.rpc-card { background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 10px; padding: 10px 12px; }
.rpc-head { display: flex; align-items: center; gap: 6px; width: 100%; background: none; border: 0; padding: 0; text-align: left; cursor: pointer; font: inherit; }
.rpc-title { font-size: 11px; color: var(--app-text3); }
.rpc-caret { margin-left: auto; font-size: 10px; color: var(--app-text3); transition: transform .15s; }
.rpc-caret.open { transform: rotate(90deg); }
.rpc-body { margin-top: 8px; }
.rpc-tip { font-size: 11px; color: var(--app-text3); line-height: 1.6; }
.rpc-list { max-height: 190px; overflow-y: auto; display: flex; flex-direction: column; gap: 4px; }
.rpc-row { display: flex; align-items: center; gap: 6px; font-size: 12px; min-width: 0; }
.rpc-ic { flex: none; font-size: 11px; color: var(--app-text3); }
.rpc-step.run .rpc-ic { color: var(--app-accent); }
.rpc-step.err .rpc-ic { color: var(--app-danger); }
.rpc-name { flex: 1; min-width: 0; color: var(--app-text2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rpc-step.run .rpc-name { color: var(--app-text); }
.rpc-step.err .rpc-name { color: var(--app-danger); }
.rpc-step.off .rpc-name { color: var(--app-text3); }
.rpc-st { flex: none; font-size: 11px; color: var(--app-text3); }
.rpc-st.run { color: var(--app-accent); }
.rpc-st.err { color: var(--app-danger); }
/* 参数单独一行：230px 栏里一行可用宽约 184px，够读约 15 个汉字；缩进对齐动作名左沿 */
.rpc-arg { font-size: 11px; color: var(--app-text2); padding: 1px 0 0 17px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rpc-more { font-size: 11px; color: var(--app-text3); padding: 2px 0; }
.rpc-hold { display: flex; align-items: flex-start; gap: 6px; font-size: 11px; color: var(--app-warn-text); margin-top: 6px; padding-top: 6px; border-top: 1px solid var(--app-border); }
.rpc-hold.bad { color: var(--app-danger); }
.rpc-hold.act { cursor: pointer; }
.rpc-hold.act:hover .rpc-hold-t { text-decoration: underline; }
.rpc-hold-ic { flex: none; padding-top: 1px; }
.rpc-hold-t { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rpc-foot { font-size: 11px; color: var(--app-accent); cursor: pointer; margin-top: 6px; padding-top: 6px; border-top: 1px solid var(--app-border); }
.rpc-foot:hover { color: var(--app-text2); }
</style>
