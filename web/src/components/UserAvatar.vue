<script setup>
// 用户头像（三态渲染，与后端 c_ai_user.avatar 口径一致）：
//   avatar 以 / 开头 → 上传图片 URL（<img> 圆形裁切）；
//   avatar 为 emoji 字符 → 表情块；空 → 昵称首字（兜底）。
import { computed } from 'vue'

const props = defineProps({
  /** 头像取值（后端 avatar 字段；空=昵称首字） */
  avatar: { type: String, default: '' },
  /** 兜底名（头像为空时取首字） */
  name: { type: String, default: '' },
  /** 边长（px） */
  size: { type: Number, default: 24 }
})

const kind = computed(() => {
  const a = (props.avatar || '').trim()
  if (!a) return 'initial'
  if (a.startsWith('/')) return 'img'
  return 'emoji'
})
const initialChar = computed(() => {
  const n = (props.name || '').trim()
  return (n || '游').charAt(0).toUpperCase() || '游'
})
const fontSize = computed(() => Math.round(props.size * 0.42) + 'px')
</script>

<template>
  <img v-if="kind === 'img'" class="ua" :style="{ width: size + 'px', height: size + 'px' }" :src="avatar" alt="" />
  <span v-else class="ua" :style="{ width: size + 'px', height: size + 'px', fontSize }">
    {{ kind === 'emoji' ? avatar : initialChar }}
  </span>
</template>

<style scoped>
.ua {
  border-radius: 50%;
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  line-height: 1;
  background: var(--app-accent-weak);
  color: var(--app-accent);
  object-fit: cover;
  user-select: none;
}
</style>
