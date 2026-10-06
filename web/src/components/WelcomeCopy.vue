<template>
  <div class="wc" :class="'wc--' + variant">
    <h2 class="wc-title">{{ title }}</h2>
    <p class="wc-sub">
      <Transition name="wc-fade" mode="out-in">
        <span :key="hint">{{ hint }}</span>
      </Transition>
    </p>
  </div>
</template>

<script setup>
import { computed, onUnmounted, ref } from 'vue'

const props = defineProps({
  variant: { type: String, default: 'pc' },      // pc | mobile（只影响字号节奏，两端空态观感不同）
  agentName: { type: String, default: '' },       // 已指定的智能体（自动派遣/默认传空）
  thinkOn: { type: Boolean, default: false },
  attachCount: { type: Number, default: 0 },      // 待发送的图片 + 文件
  mentions: { type: Array, default: () => [] }    // @ 引用的知识库/文档名
})

const ROTATE_MS = 4200
// 一条心跳同时驱动「整点换问候」与「副标题轮播」，不必为问候单开定时器
const now = ref(Date.now())
const timer = setInterval(() => { now.value = Date.now() }, ROTATE_MS)
onUnmounted(() => clearInterval(timer))

const title = computed(() => {
  const d = new Date(now.value)
  const h = d.getHours()
  return h < 5 ? '夜深了'
    : h < 11 ? '早上好'
    : h < 13 && d.getMinutes() < 30 ? '中午好'
    : h < 18 ? '下午好'
    : h < 23 ? '晚上好' : '夜深了'
})

// 通用引导只讲「能问什么、拿到什么」，不提检索链路等内部机制
const BASE = [
  '有什么想问的，直接说就好',
  '文档、图片拖进来，照样能问',
  '长文丢过来，帮你理成几条要点',
  '答案里的出处都能点开，回原文再核一遍',
  '拿不准的问题，可以让我先想清楚再答'
]
const hints = computed(() => {
  const live = []
  if (props.mentions.length) {
    const more = props.mentions.length > 2 ? '等' : ''
    live.push(`这一轮只在「${props.mentions.slice(0, 2).join('、')}${more}」里找答案`)
  }
  if (props.agentName) live.push(`这一轮由「${props.agentName}」回答你`)
  if (props.attachCount) live.push(`已带上 ${props.attachCount} 份资料，直接问它的内容就行`)
  if (props.thinkOn) live.push('深度思考已开启，回答前会先把问题理一遍')
  return [...live, ...BASE]
})
const hint = computed(() => {
  const list = hints.value
  return list[Math.floor(now.value / ROTATE_MS) % list.length]
})
</script>

<style scoped>
.wc { text-align: center; }
.wc-title { margin: 14px 0 6px; font-size: 16px; font-weight: 500; }
/* 定高：轮播换长短句时不推动下面的示例卡，避免空态轻微跳动 */
.wc-sub { color: var(--app-text3); margin: 0 0 18px; min-height: 20px; line-height: 20px; }
.wc--mobile .wc-title { margin: 12px 0 0; font-size: 19px; font-weight: 600; }
.wc--mobile .wc-sub { margin: 6px 0 16px; font-size: 13px; }

.wc-fade-enter-active, .wc-fade-leave-active { transition: opacity .32s ease; }
.wc-fade-enter-from, .wc-fade-leave-to { opacity: 0; }
</style>
