<script setup>
// 智能体头像（列表卡片 / 配置页图标选择器 / 对话页下拉共用同一套解析口径）：
//   icon='wenqu' 或（icon 未配且 isBuiltin=1，即内置「问渠」）→ 问渠品牌标 BrandMark；
//   icon=emoji 字符 → 表情圆角块；其余（未配图标的普通智能体）→ 默认机器人圆角块。
import { computed } from 'vue'
import { RobotOutlined } from '@ant-design/icons-vue'
import BrandMark from './BrandMark.vue'

const props = defineProps({
  /** 智能体对象（至少含 icon / isBuiltin；null → 默认机器人） */
  agent: { type: Object, default: null },
  /** 边长（px） */
  size: { type: Number, default: 24 }
})

const iconText = computed(() => String(props.agent?.icon || '').trim())
const isBuiltin = computed(() => props.agent?.isBuiltin === 1 || props.agent?.isBuiltin === true)
const showBrand = computed(() => iconText.value === 'wenqu' || (!iconText.value && isBuiltin.value))
const emoji = computed(() => (showBrand.value || !iconText.value) ? '' : iconText.value)
const blockStyle = computed(() => ({
  width: props.size + 'px',
  height: props.size + 'px',
  fontSize: Math.round(props.size * 0.56) + 'px',
  borderRadius: Math.max(6, Math.round(props.size * 0.28)) + 'px'
}))
</script>

<template>
  <!-- 品牌标自带圆角与品牌渐变，直接作头像（不套底色块） -->
  <BrandMark v-if="showBrand" :size="size" />
  <span v-else class="agent-ava" :style="blockStyle">
    <template v-if="emoji">{{ emoji }}</template>
    <robot-outlined v-else />
  </span>
</template>

<style scoped>
.agent-ava {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  line-height: 1;
  background: var(--app-accent-weak);
  color: var(--app-accent);
}
</style>
