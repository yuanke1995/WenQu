<script setup>
// 知识库图标（列表卡片 / 编辑弹窗图标选择器共用同一套解析口径，对应 AgentAvatar 之于智能体）：
//   icon='wenqu' 或（icon 未配且 isDefault=1，即默认知识库）→ 问渠品牌标 BrandMark；
//   icon=emoji 字符 → 表情圆角块；其余（未配图标的普通库）→ 默认库图标圆角块。
import { computed } from 'vue'
import { DatabaseOutlined } from '@ant-design/icons-vue'
import BrandMark from './BrandMark.vue'

const props = defineProps({
  /** 知识库对象（至少含 icon / isDefault；null → 默认库图标） */
  kb: { type: Object, default: null },
  /** 边长（px） */
  size: { type: Number, default: 24 }
})

const iconText = computed(() => String(props.kb?.icon || '').trim())
const isDefault = computed(() => props.kb?.isDefault === 1)
const showBrand = computed(() => iconText.value === 'wenqu' || (!iconText.value && isDefault.value))
const emoji = computed(() => (showBrand.value || !iconText.value) ? '' : iconText.value)
const blockStyle = computed(() => ({
  width: props.size + 'px',
  height: props.size + 'px',
  fontSize: Math.round(props.size * 0.56) + 'px',
  borderRadius: Math.max(6, Math.round(props.size * 0.28)) + 'px'
}))
</script>

<template>
  <!-- 品牌标自带圆角与品牌渐变，直接作图标（不套底色块） -->
  <BrandMark v-if="showBrand" :size="size" />
  <span v-else class="kb-ic" :style="blockStyle">
    <template v-if="emoji">{{ emoji }}</template>
    <database-outlined v-else />
  </span>
</template>

<style scoped>
.kb-ic {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  line-height: 1;
  background: var(--app-accent-weak);
  color: var(--app-accent);
}
</style>
