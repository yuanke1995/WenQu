<script setup>
// 实体图标的唯一实现：智能体头像与知识库图标共用，解析口径只写一份。
// KbIcon.vue / AgentAvatar.vue 是它的两个薄封装，各自只把实体字段翻译成这里的三组 props
// （icon / isBrandByDefault / fallbackIcon）。新增实体图标请复用本组件，不要再复制一份。
//
// 解析口径（两个实体一致）：
//   icon='wenqu'，或 icon 未配且该实体是「默认/内置」→ 问渠品牌标 BrandMark；
//   icon 是 emoji 等字符 → 表情圆角块；
//   其余（未配图标的普通实体）→ 各自的兜底图标圆角块。
//
// 两个薄封装 KbIcon.vue / AgentAvatar.vue 只负责把自己的字段名翻译成本组 props。
import { computed } from 'vue'
import BrandMark from './BrandMark.vue'

const props = defineProps({
  /** 图标字符（emoji / 'wenqu'） */
  icon: { type: String, default: '' },
  /** 是否「默认/内置」实体：未配图标时是否展示问渠品牌标 */
  isBrandByDefault: { type: Boolean, default: false },
  /** 兜底图标组件（未配图标且非品牌时显示），如 DatabaseOutlined / RobotOutlined */
  fallbackIcon: { type: [Object, Function], default: null },
  /** 边长（px） */
  size: { type: Number, default: 24 }
})

const iconText = computed(() => String(props.icon || '').trim())
const showBrand = computed(() => iconText.value === 'wenqu' || (!iconText.value && props.isBrandByDefault))
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
  <span v-else class="entity-ic" :style="blockStyle">
    <template v-if="emoji">{{ emoji }}</template>
    <component :is="fallbackIcon" v-else />
  </span>
</template>

<style scoped>
.entity-ic {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  line-height: 1;
  background: var(--app-accent-weak);
  color: var(--app-accent);
}
</style>