<template>
  <div class="ask-rec">
    <!-- 折叠头：默认收起（问答只是执行过程的一部分，不是回答正文），收起时用首问做摘要 -->
    <button class="ask-rec-head" type="button" :title="open ? '收起问答记录' : '展开问答记录'" @click="collapsed = !collapsed">
      <question-circle-outlined class="ask-rec-h-ic" />
      <span class="ask-rec-h-t">问答记录<template v-if="n > 1">（{{ n }} 问）</template></span>
      <span v-if="!open && firstQ" class="ask-rec-h-sum">{{ firstQ }}</span>
      <caret-right-outlined class="ask-rec-h-caret" :class="{ open }" />
    </button>
    <div v-show="open" class="ask-rec-body">
      <template v-if="v.multi">
        <div v-for="(q, i) in v.questions" :key="i" class="ask-rec-item">
          <div class="ask-rec-q"><question-circle-outlined /> <template v-if="v.questions.length > 1">{{ i + 1 }}. </template>{{ q.question }}</div>
          <div v-if="q.answer" class="ask-rec-a"><check-circle-outlined /> {{ q.answer }}</div>
        </div>
      </template>
      <template v-else>
        <div v-if="v.question" class="ask-rec-q"><question-circle-outlined /> {{ v.question }}</div>
        <div v-if="v.answer" class="ask-rec-a" :class="{ notice: v.notice }">
          <info-circle-outlined v-if="v.notice" />
          <check-circle-outlined v-else />
          {{ v.answer }}
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { CaretRightOutlined, CheckCircleOutlined, InfoCircleOutlined, QuestionCircleOutlined } from '@ant-design/icons-vue'
import { askUserView } from '../chat/projections'

const props = defineProps({ t: { type: Object, required: true } })
const v = computed(() => askUserView(props.t))
const n = computed(() => (v.value.multi ? v.value.questions.length : (v.value.question ? 1 : 0)))
// 折叠态放组件本地 ref：挂在 props.t 上不可靠——历史恢复回填的工具对象是普通对象，
// 改它不触发更新（父级也不一定把它包成 reactive）。默认收起：问答属于执行过程细节。
const collapsed = ref(true)
const open = computed(() => !collapsed.value)
const firstQ = computed(() => (v.value.multi ? (v.value.questions[0] || {}).question : v.value.question))
</script>

<style scoped>
/* 内边距随所在壳走：PC 工具卡头是 4px 9px、移动端是 8px 10px。
   写成变量由外壳覆盖（:deep），避免这里写死后移动端与卡片头错位。

   max-width 上限是必需的，不是保守取值：宿主 .tl-card 是 width:fit-content，
   子元素的内在宽度会参与父级尺寸计算——摘要不限宽时会把工具卡撑到爆、
   折叠头被挤到卡片边框外（图标跑到边框左侧）。定上限后摘要才谈得上省略号。 */
.ask-rec { margin: 6px 0 8px; width: fit-content; max-width: min(100%, 560px); }
.ask-rec-head {
  display: flex; align-items: center; gap: 6px; width: 100%; box-sizing: border-box;
  padding: var(--ask-rec-pad, 4px 9px); min-width: 0;
  background: none; border: 0; cursor: pointer; font-size: 12px; color: var(--app-text3);
  text-align: left; border-radius: 6px;
}
.ask-rec-head:hover { color: var(--app-text2); background: var(--app-bg); }
.ask-rec-h-ic { font-size: 12px; }
.ask-rec-h-t { flex: none; }
.ask-rec-h-sum { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--app-text3); }
.ask-rec-h-caret { flex: none; margin-left: auto; font-size: 11px; transition: transform .15s; }
.ask-rec-h-caret.open { transform: rotate(90deg); }
.ask-rec-body { display: flex; flex-direction: column; gap: 6px; padding: 4px 9px 0; }
.ask-rec-item { display: flex; flex-direction: column; gap: 4px; padding: 6px 8px; background: var(--app-panel-2); border-radius: 8px; }
.ask-rec-q { font-size: 12.5px; line-height: 1.6; color: var(--app-text); display: flex; gap: 6px; align-items: flex-start; white-space: pre-wrap; word-break: break-word; }
.ask-rec-q .anticon { margin-top: 3px; color: var(--app-text3); }
.ask-rec-a { font-size: 12.5px; line-height: 1.6; font-weight: 600; color: var(--app-text); display: flex; gap: 6px; align-items: flex-start; white-space: pre-wrap; word-break: break-word; background: var(--app-panel-2); border-radius: 6px; padding: 6px 8px; }
.ask-rec-a .anticon { margin-top: 3px; color: var(--app-ok); }
/* 未发出的卡（超限被拒/次数用尽）：说明文本，不是「用户答了」 */
.ask-rec-a.notice { font-weight: 400; color: var(--app-text2); background: var(--app-panel); }
.ask-rec-a.notice .anticon { color: var(--app-text3); }
</style>