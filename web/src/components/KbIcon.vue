<script setup>
// 知识库图标：字段解析（kb.icon / kb.isDefault）在这里，绘制统一交给 EntityIcon。
// 列表卡片与编辑弹窗的图标选择器共用同一套口径。
import { computed } from 'vue'
import { DatabaseOutlined } from '@ant-design/icons-vue'
import EntityIcon from './EntityIcon.vue'

const props = defineProps({
  /** 知识库对象（至少含 icon / isDefault；null → 默认库图标） */
  kb: { type: Object, default: null },
  /** 边长（px） */
  size: { type: Number, default: 24 }
})

const icon = computed(() => props.kb?.icon || '')
const isBrand = computed(() => props.kb?.isDefault === 1)
</script>

<template>
  <EntityIcon :icon="icon" :is-brand-by-default="isBrand" :fallback-icon="DatabaseOutlined" :size="size" />
</template>