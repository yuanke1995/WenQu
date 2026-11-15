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
      <div v-else class="rpc-list" :class="{ 'has-err': shownFailedOpen }" ref="listEl">
        <div v-for="(t, ti) in shown" :key="ti" class="rpc-step"
             :class="{ run: isRun(t), err: t.status === 'error', off: t.denied, open: t.status === 'error' && t._open }"
             @click="t.status === 'error' && (t._open = !t._open)">
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
            <caret-right-outlined v-if="t.status === 'error'" class="rpc-err-caret" :class="{ open: t._open }" />
          </div>
          <div v-if="briefOf(t)" class="rpc-arg" :title="briefOf(t)">{{ briefOf(t) }}</div>
          <!-- 失败步展开：原因、能做的下一步都在原地。已完成的轮次没有「单步重跑」接口，
               所以只给日志 / 复制错误 / 重新生成本轮，并明说重新生成会重跑整轮。 -->
          <div v-if="t.status === 'error' && t._open" class="rpc-errbox" @click.stop>
            <div class="rpc-err-msg">{{ errInfo(t).lead }}</div>
            <div v-if="errInfo(t).raw !== errInfo(t).lead" class="rpc-err-raw" :title="errInfo(t).raw">{{ errInfo(t).raw }}</div>
            <div class="rpc-err-acts">
              <button type="button" class="rpc-err-act" @click.stop="$emit('open-events')">查看日志</button>
              <button type="button" class="rpc-err-act ghost" @click.stop="copyErr(t)">复制错误</button>
              <button type="button" class="rpc-err-act ghost" :disabled="running" @click.stop="$emit('regenerate')">重新生成本轮</button>
            </div>
            <div class="rpc-err-note">重新生成会重跑整轮，不会只补这一步。</div>
          </div>
        </div>
        <div v-if="omitted" class="rpc-more">前面还有 {{ omitted }} 步</div>
      </div>
      <!-- 等人拍板的那一句：卡片本体在输入框上方，这里只负责「看得见 + 点得回去」；
           没在等的时候这一行让位给失败步的原因。失败步已经展开时不再重复同一句 -->
      <div v-if="showHold" class="rpc-hold" :class="{ bad: hold.bad, act: !!hold.go }"
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
import { copyText } from '../utils/clipboard'

// msg=最近一条 AI 消息（含跑轮中 loading 的那条）：跑轮时它就是实时流，结束后它就是上一轮
const props = defineProps({ msg: { type: Object, default: null } })
defineEmits(['open-events', 'open-hitl', 'regenerate'])

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
/** 失败原因只取第一行：工具返回常是整段说明，右栏放不下；完整留痕在「查看全部步骤」里。
 *  错误步必须 error 优先：失败时 result 可能只是半截输出（如参数摘要 main），拿它当原因会把真异常藏掉 */
const errRaw = t => {
  const raw = t.error || t.result || t.hint || ''
  const s = typeof raw === 'string' ? raw
    : (raw && typeof raw === 'object' ? (raw.message || JSON.stringify(raw)) : String(raw))
  return s.split(/\r?\n/)[0] || '这一步没有返回可读原因'
}
/** 把常见异常翻成一句人话；翻不了就原样显示。原始错误保留给「复制错误」与日志 */
const errInfo = t => {
  const raw = errRaw(t)
  const noFile = raw.match(/(?:NoSuchFileException|FileNotFoundException):\s*(.+)$/i)
  if (noFile) {
    const path = noFile[1].trim()
    const base = path.split('/').filter(Boolean).pop() || path
    // 只露文件名（长哈希截中段）：完整路径在下面那行原始错误与「复制错误」里
    const short = base.length > 20 ? base.slice(0, 8) + '…' + base.slice(-6) : base
    return { lead: '找不到文件：' + short, raw }
  }
  if (/timeout|timed out|超时/i.test(raw)) return { lead: '工具调用超时', raw }
  if (/Permission denied|AccessDenied|拒绝访问/i.test(raw)) return { lead: '没有权限访问目标文件或目录', raw }
  if (/Connection refused|ECONNREFUSED/i.test(raw)) return { lead: '连接被拒绝', raw }
  return { lead: raw, raw }
}
const copyErr = t => copyText(errRaw(t), '已复制错误信息')
// 等人拍板（或失败步）那一句：判据与移动端 sheet 同一份
const hold = computed(() => roundHold(props.msg))
// 失败步默认展开：右栏的作用就是让人看见「哪一步没成、现在能做什么」，收着等于没说
const shownFailedOpen = computed(() => shown.value.some(t => t.status === 'error' && t._open))
const showHold = computed(() => !!hold.value && !(hold.value.bad && shownFailedOpen.value))
watch(steps, list => {
  const f = list.find(t => t.status === 'error')
  if (f && f._open === undefined) f._open = true
}, { immediate: true })
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
/* 失败步展开时放开列表高度：错误原因与「日志 / 复制 / 重新生成」必须在原地看全，
   而不是藏在 190px 滚动区里；只有单步清单时才维持紧凑高度 */
.rpc-list.has-err { max-height: min(58vh, 520px); }
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
/* 失败步：整行可点开详情；caret 与错误框只在 error 这一档出现 */
.rpc-step.err { cursor: pointer; }
.rpc-err-caret { flex: none; font-size: 9px; color: var(--app-text3); transition: transform .12s; }
.rpc-err-caret.open { transform: rotate(90deg); }
.rpc-errbox {
  margin: 4px 0 2px 17px; padding: 7px 8px; border-radius: 7px;
  border: 1px solid var(--app-danger-border); background: var(--app-danger-weak);
}
.rpc-err-msg {
  font-size: 10.5px; line-height: 1.55; color: var(--app-danger-text);
  word-break: break-word;
}
/* 原始异常：等宽小字、最多三行，完整内容在 title 与「复制错误」里 */
.rpc-err-raw {
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical;
  overflow: hidden; margin-top: 3px; font-family: var(--mono);
  font-size: 9px; line-height: 1.5; color: var(--app-danger-text); opacity: .72;
  word-break: break-all;
}
.rpc-err-acts { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 6px; }
.rpc-err-act {
  height: 22px; padding: 0 6px; border-radius: 5px; white-space: nowrap;
  border: 1px solid var(--app-danger-border); background: var(--app-panel);
  color: var(--app-danger-text); font: inherit; font-size: 10px; cursor: pointer;
}
.rpc-err-act:hover:not(:disabled) { background: var(--app-danger-weak); }
.rpc-err-act:disabled { opacity: .5; cursor: default; }
.rpc-err-act.ghost { border-color: var(--app-border); background: transparent; color: var(--app-text2); }
.rpc-err-act.ghost:hover:not(:disabled) { background: var(--app-panel-2); }
.rpc-err-note { margin-top: 5px; font-size: 9.5px; line-height: 1.45; color: var(--app-text3); }
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
