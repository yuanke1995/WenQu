<template>
  <!-- 执行过程（事件账本）的移动壳：底部 sheet 装共用面板，取数与渲染不重写一份。
       两种看法共用：带 turnId=只看这一条回答跑过的步，不带=这个会话最近几轮跑了什么。 -->
  <BottomSheet :open="open" :title="title" :subtitle="subtitle" max-height="82dvh" @close="$emit('close')">
    <SessionEventsPanel :open="open" :session-id="sessionId" :turn-id="turnId"
                        :message="message" :question="question" :viewer="false"
                        @citation="src => $emit('citation', src)" @preview="(u, i) => $emit('preview', u, i)" />
  </BottomSheet>
</template>

<script setup>
import { computed } from 'vue'
import BottomSheet from './BottomSheet.vue'
import SessionEventsPanel from '../components/SessionEventsPanel.vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  sessionId: { type: String, default: '' },
  /** 非空=只看这一轮；空=按会话列出最近几轮 */
  turnId: { type: String, default: '' },
  /** 对照视图的那一半（从单轮 sheet 进来才有）：这条回答与它的问题 */
  message: { type: Object, default: null },
  question: { type: String, default: '' }
})
defineEmits(['close', 'citation', 'preview'])

const title = computed(() => props.turnId ? '执行过程 · 这一轮' : '执行过程（事件账本）')
const subtitle = computed(() => props.turnId
  ? '这一条回答是怎么跑出来的：工具步与耗时、三张卡、产物、停止与失败'
  : '只服务事后回看，不参与智能体的回答；账本按天保留')
</script>
