<template>
  <span class="provider-icon" :title="name" :style="{ width: size + 'px', height: size + 'px' }">
    <!-- http(s) 图片 URL：直接渲染 -->
    <img v-if="isUrl" :src="icon" :alt="name" class="pi-img" />

    <!-- 内置官方 SVG path（fill=currentColor，跟随文字色） -->
    <svg v-else-if="svgPath" viewBox="0 0 24 24" class="pi-svg" aria-hidden="true">
      <path :d="svgPath" fill="currentColor" />
    </svg>

    <!-- 品牌徽标 / 字母兜底：品牌色圆角块 + 首字符 -->
    <span v-else class="pi-badge" :style="{ background: badgeColor }">{{ badgeLabel }}</span>
  </span>
</template>

<script setup>
import { computed } from 'vue'
import { BRAND_PATHS, BRAND_BADGES } from '../assets/providerIcons.js'

/**
 * 供应商图标渲染（三段兜底）：
 * 1. icon 为 http(s) URL → 图片
 * 2. icon 命中内置官方 SVG（openai/anthropic/gemini/ollama/qwen(阿里云)/openrouter/huggingface）→ 品牌矢量图
 * 3. 其余（内置品牌徽标 key 或空）→ 品牌色字母徽标；空 key 按供应商名首字符 + 确定性色相生成
 */
const props = defineProps({
  /** 图标：内置 key / http(s) URL / 空 */
  icon: { type: String, default: '' },
  /** 供应商名（字母徽标兜底用） */
  name: { type: String, default: '' },
  /** 尺寸 px */
  size: { type: Number, default: 18 },
})

const isUrl = computed(() => /^https?:\/\//i.test(props.icon || ''))

const svgPath = computed(() => BRAND_PATHS[(props.icon || '').trim()] || '')

const badge = computed(() => BRAND_BADGES[(props.icon || '').trim()] || null)

const badgeLabel = computed(() => {
  if (badge.value) return badge.value.label
  const n = (props.name || '').trim()
  return n ? n.charAt(0).toUpperCase() : '?'
})

const badgeColor = computed(() => {
  if (badge.value) return badge.value.color
  // 供应商名确定性色相（同名单色，跨刷新稳定）
  const n = (props.name || 'custom') + ''
  let h = 0
  for (let i = 0; i < n.length; i++) h = (h * 31 + n.charCodeAt(i)) % 360
  return `hsl(${h}, 62%, 48%)`
})
</script>

<style scoped>
.provider-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  vertical-align: middle;
}
.pi-img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  border-radius: 4px;
}
.pi-svg {
  width: 100%;
  height: 100%;
}
.pi-badge {
  width: 100%;
  height: 100%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  color: #fff;
  font-weight: 700;
  /* 字号跟随尺寸：徽标最小可用 12px 起 */
  font-size: calc(v-bind('size') * 0.58px);
  line-height: 1;
  user-select: none;
}
</style>
