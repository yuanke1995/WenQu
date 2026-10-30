<!--
  分享页的计划卡（人在回路）。

  与 ShareAskPanel 同样的理由：ChatPage 的计划确认栏和桌面壳输入框、版本链翻页深耦合，
  样式也在它的 scoped 里，分享页复用不了，所以给一份自用的。

  四种裁决与登录态同语义：
    批准执行 = {approved:true}（可带编辑后全文）
    退回重出 = {revise:true, plan:修改意见}   同一轮内模型按意见出下一版
    继续对话 = {supersede:true, plan:修改意见} 本版作废，意见作为一条用户消息发出
    拒绝     = {approved:false}
-->
<template>
  <div class="sp-box">
    <div class="sp-head">
      <span class="sp-tag">执行计划</span>
      <span class="sp-title">模型拟按下面这份计划执行，等你裁决</span>
      <span v-if="answered" class="sp-state ok">已提交，模型继续中…</span>
      <span v-else-if="expired" class="sp-state warn">已超时，本轮终止</span>
      <span v-else-if="!live" class="sp-state warn">这一轮已失效（服务已重启），裁决送不到了</span>
    </div>

    <pre class="sp-plan">{{ plan }}</pre>

    <div v-if="needOpinion" class="sp-opinion">
      <textarea v-model="opinion" class="sp-textarea" :maxlength="4000" :disabled="locked"
                :placeholder="opinionPlaceholder" rows="3" />
    </div>

    <div class="sp-foot">
      <span class="sp-hint">退回重出会在同一轮里再出一版计划；继续对话则本版作废、把你的意见当作一条新消息发出。</span>
      <span class="sp-actions">
        <button class="sp-btn" type="button" :disabled="locked" @click="resolve({ approved: false })">拒绝</button>
        <button class="sp-btn" type="button" :disabled="locked || !opinion.trim()" @click="resolve({ supersede: true, plan: opinion.trim() })">继续对话</button>
        <button class="sp-btn" type="button" :disabled="locked || !opinion.trim()" @click="resolve({ revise: true, plan: opinion.trim() })">退回重出</button>
        <button class="sp-btn primary" type="button" :disabled="locked" @click="resolve({ approved: true })">批准执行</button>
      </span>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  // { planApprovalId, plan, timeoutMs, remainingMs, expired, live }
  plan: { type: Object, required: true }
})
const emit = defineEmits(['resolve'])

const busy = ref(false)
const answered = ref(false)
const opinion = ref('')

const plan = computed(() => {
  const p = props.plan.plan
  // 后端下发的是权威稿字符串；万一是对象，序列化后给 pre 才不会渲染成 [object Object]
  if (typeof p === 'string') return p
  return p == null ? '（模型没有给出计划正文）' : JSON.stringify(p, null, 2)
})
const expired = computed(() => !!props.plan.expired)
const live = computed(() => props.plan.live !== false)
const locked = computed(() => busy.value || answered.value || expired.value || !live.value)
const needOpinion = computed(() => true)

function resolve (payload) {
  busy.value = true
  emit('resolve', payload)
}

defineExpose({
  settle (ok) {
    busy.value = false
    if (ok) answered.value = true
  }
})
</script>

<style scoped>
.sp-box {
  border: 1px solid var(--app-border, #e5e7eb);
  border-radius: 10px;
  background: var(--app-bg-elevated, #fff);
  padding: 12px 14px;
  margin: 0 0 10px;
}
.sp-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.sp-tag { font-size: 12px; color: #7c3aed; background: #f5f3ff; border-radius: 4px; padding: 1px 6px; }
.sp-title { font-weight: 600; font-size: 14px; }
.sp-state { font-size: 12px; }
.sp-state.ok { color: #16a34a; }
.sp-state.warn { color: #d97706; }
.sp-plan {
  margin: 10px 0; padding: 10px 12px; max-height: 40vh; overflow-y: auto;
  background: #fafafa; border: 1px solid #f0f0f0; border-radius: 8px;
  white-space: pre-wrap; word-break: break-word;
  font-size: 13px; line-height: 1.75; font-family: inherit; color: inherit;
}
.sp-textarea {
  width: 100%; box-sizing: border-box; border: 1px solid #e5e7eb; border-radius: 8px;
  padding: 7px 10px; font-size: 13px; font-family: inherit; resize: vertical; color: inherit;
}
.sp-foot { margin-top: 10px; display: flex; align-items: center; gap: 10px; }
.sp-hint { font-size: 12px; color: #9ca3af; }
.sp-actions { margin-left: auto; display: flex; gap: 8px; }
.sp-btn {
  border: 1px solid #e5e7eb; background: #fff; border-radius: 6px;
  padding: 3px 12px; font-size: 13px; cursor: pointer; color: inherit;
}
.sp-btn.primary { background: #7c3aed; border-color: #7c3aed; color: #fff; }
.sp-btn:disabled { opacity: .45; cursor: not-allowed; }
</style>
