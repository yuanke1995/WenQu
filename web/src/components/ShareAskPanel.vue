<!--
  分享页的提问卡（人在回路）。

  为什么不是复用 ChatPage 那张：它的样式在 ChatPage.vue 的 <style scoped> 里（.askp-* 全局搜不到），
  且状态与桌面壳的输入框替换、键盘导航、askQueue 翻页深耦合。分享页要的是「能答、别卡死」，
  所以这里给一份自用的实现，样式同族但独立。

  ⚠️ 已知债：两页各有一份提问卡实现。要收口的话应把它抽成共享组件、ChatPage 也换过来，
  但那要动 ChatPage 4000 行里的 pendingAsk/askPage/askHi/onPick/askSubmitAll 一整套，风险另计。
-->
<template>
  <div class="sa-box">
    <div class="sa-head">
      <span class="sa-tag">{{ cur.topic || '向用户提问' }}</span>
      <span class="sa-q">{{ cur.question }}</span>
      <span v-if="cur.multi" class="sa-multi">可多选</span>
      <span v-if="answered" class="sa-state ok">已提交，模型继续中…</span>
      <span v-else-if="expired" class="sa-state warn">已超时，智能体将自行判断…</span>
      <span v-else-if="!live" class="sa-state warn">这一轮已失效（服务已重启），作答送不到了</span>
      <span v-if="questions.length > 1" class="sa-pager">
        <button class="sa-page" type="button" :disabled="page <= 0 || busy" @click="page--">‹</button>
        <span class="sa-page-num">{{ page + 1 }} / {{ questions.length }}</span>
        <button class="sa-page" type="button" :disabled="page >= questions.length - 1 || busy" @click="page++">›</button>
      </span>
    </div>

    <div class="sa-opts">
      <button v-for="(op, oi) in options" :key="oi" type="button"
              class="sa-opt" :class="{ sel: picked.has(oi) }"
              :disabled="locked" @click="toggle(oi)">
        <span v-if="cur.multi" class="sa-tick" :class="{ on: picked.has(oi) }"></span>
        <span class="sa-no">{{ oi + 1 }}.</span>
        <span class="sa-kw">{{ op }}</span>
      </button>
      <div class="sa-opt sa-custom" :class="{ sel: customOn }">
        <span v-if="cur.multi" class="sa-tick" :class="{ on: customOn }"></span>
        <span class="sa-no">{{ options.length + 1 }}.</span>
        <input v-model="custom" class="sa-input" :maxlength="2000" :disabled="locked"
               placeholder="自己写（留空则不算一项答案）"
               @focus="customTouched = true" />
      </div>
    </div>

    <div class="sa-foot">
      <span class="sa-hint">点选或写一条，提交后这一轮接着跑；不答就点「忽略」，智能体自己判断。</span>
      <span class="sa-actions">
        <button class="sa-btn ghost" type="button" :disabled="locked" @click="$emit('ignore')">忽略</button>
        <button class="sa-btn primary" type="button" :disabled="locked || !hasAnswer" @click="submit">
          提交（{{ answeredCount }} / {{ questions.length }}）
        </button>
      </span>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'

const props = defineProps({
  // { askId, questions:[{topic?,question,options[],multi?}], timeoutMs, remainingMs, expired, live }
  ask: { type: Object, required: true }
})
const emit = defineEmits(['submit', 'ignore'])

const page = ref(0)
const busy = ref(false)
const answered = ref(false)
// 每题一份答案：Set(选中的下标) + custom 文本
const picks = reactive({})

const questions = computed(() => (Array.isArray(props.ask.questions) ? props.ask.questions : []))
const cur = computed(() => questions.value[page.value] || { question: '', options: [] })
const options = computed(() => (Array.isArray(cur.value.options) ? cur.value.options : []))
const expired = computed(() => !!props.ask.expired)
const live = computed(() => props.ask.live !== false)
const locked = computed(() => busy.value || answered.value || expired.value || !live.value)

function slot () {
  const k = page.value
  if (!picks[k]) picks[k] = { sel: new Set(), custom: '', customTouched: false }
  return picks[k]
}
// 模板里用 computed 包一层，避免每次渲染都新建 Set 导致勾选丢失
const cur$ = computed(slot)
const picked = computed(() => cur$.value.sel)
const custom = computed({
  get: () => cur$.value.custom,
  set: v => { cur$.value.custom = v }
})
const customTouched = computed({
  get: () => cur$.value.customTouched,
  set: v => { cur$.value.customTouched = v }
})
const customOn = computed(() => options.value.length > 0
  ? (cur$.value.custom.trim() !== '' || cur$.value.customTouched)
  : true)

function toggle (oi) {
  const s = cur$.value.sel
  if (cur.value.multi) {
    if (s.has(oi)) s.delete(oi)
    else s.add(oi)
  } else {
    s.clear()
    s.add(oi)
  }
}

/** 一题的答案：选中的选项文本 + 自定义文本，按顺序并成一项（与登录态「多选并成一项」同形） */
function answerOf (k) {
  const q = questions.value[k]
  const st = picks[k]
  if (!q || !st) return ''
  const opts = Array.isArray(q.options) ? q.options : []
  const parts = [...st.sel].sort((a, b) => a - b).map(i => opts[i]).filter(x => x != null)
  const c = (st.custom || '').trim()
  if (c) parts.push(c)
  return parts.join('、')
}

const hasAnswer = computed(() => answerOf(page.value) !== '')
const answeredCount = computed(() => questions.value.filter((_, i) => answerOf(i) !== '').length)

function submit () {
  // 当前这题没答就不提交：送一个空串会被模型读成「答了但答不上来」
  if (!hasAnswer.value) return
  const answers = questions.value.map((_, i) => answerOf(i))
  busy.value = true
  emit('submit', answers)
}

/** 父组件在请求返回后调用，用于复位按钮态 */
defineExpose({
  settle (ok) {
    busy.value = false
    if (ok) answered.value = true
  }
})

watch(() => props.ask.askId, () => {
  page.value = 0
  busy.value = false
  answered.value = false
  Object.keys(picks).forEach(k => delete picks[k])
})
</script>

<style scoped>
.sa-box {
  border: 1px solid var(--app-border, #e5e7eb);
  border-radius: 10px;
  background: var(--app-bg-elevated, #fff);
  padding: 12px 14px;
  margin: 0 0 10px;
}
.sa-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.sa-tag {
  font-size: 12px; color: #2563eb; background: #eff6ff;
  border-radius: 4px; padding: 1px 6px;
}
.sa-q { font-weight: 600; font-size: 14px; }
.sa-multi { font-size: 12px; color: #6b7280; border: 1px solid #e5e7eb; border-radius: 4px; padding: 0 5px; }
.sa-state { font-size: 12px; }
.sa-state.ok { color: #16a34a; }
.sa-state.warn { color: #d97706; }
.sa-pager { margin-left: auto; display: flex; align-items: center; gap: 4px; }
.sa-page {
  border: 1px solid #e5e7eb; background: #fff; border-radius: 4px;
  width: 22px; height: 22px; line-height: 1; cursor: pointer;
}
.sa-page:disabled { opacity: .4; cursor: not-allowed; }
.sa-page-num { font-size: 12px; color: #6b7280; }
.sa-opts { margin-top: 10px; display: flex; flex-direction: column; gap: 6px; }
.sa-opt {
  display: flex; align-items: center; gap: 8px; text-align: left;
  border: 1px solid #e5e7eb; border-radius: 8px; padding: 7px 10px;
  background: #fff; cursor: pointer; font-size: 13px; color: inherit;
}
.sa-opt:hover:not(:disabled) { border-color: #93c5fd; }
.sa-opt.sel { border-color: #2563eb; background: #f5f8ff; }
.sa-opt:disabled { opacity: .55; cursor: not-allowed; }
.sa-custom { cursor: text; }
.sa-tick {
  width: 13px; height: 13px; border: 1px solid #c4c8cf; border-radius: 3px; flex: none;
}
.sa-tick.on { background: #2563eb; border-color: #2563eb; }
.sa-no { color: #9ca3af; font-size: 12px; flex: none; }
.sa-kw { word-break: break-word; }
.sa-input {
  flex: 1; border: 0; outline: none; background: transparent;
  font-size: 13px; color: inherit; font-family: inherit;
}
.sa-foot { margin-top: 10px; display: flex; align-items: center; gap: 10px; }
.sa-hint { font-size: 12px; color: #9ca3af; }
.sa-actions { margin-left: auto; display: flex; gap: 8px; }
.sa-btn {
  border: 1px solid #e5e7eb; background: #fff; border-radius: 6px;
  padding: 3px 12px; font-size: 13px; cursor: pointer; color: inherit;
}
.sa-btn.primary { background: #2563eb; border-color: #2563eb; color: #fff; }
.sa-btn:disabled { opacity: .45; cursor: not-allowed; }
</style>
