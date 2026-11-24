<script setup>
// 智能体头像：字段解析（agent.icon / agent.isBuiltin）在这里，绘制统一交给 EntityIcon。
// 列表卡片 / 配置页图标选择器 / 对话页下拉共用同一套口径。
import { computed } from 'vue'
import { RobotOutlined } from '@ant-design/icons-vue'
import EntityIcon from './EntityIcon.vue'

const props = defineProps({
  /** 智能体对象（至少含 icon / isBuiltin；null → 默认机器人） */
  agent: { type: Object, default: null },
  /** 边长（px） */
  size: { type: Number, default: 24 }
})

const icon = computed(() => props.agent?.icon || '')
// 注意：isBuiltin 后端有时给 1、有时给 true，两种都要认（原实现即如此）
const isBrand = computed(() => props.agent?.isBuiltin === 1 || props.agent?.isBuiltin === true)
</script>

<template>
  <EntityIcon :icon="icon" :is-brand-by-default="isBrand" :fallback-icon="RobotOutlined" :size="size" />
</template>