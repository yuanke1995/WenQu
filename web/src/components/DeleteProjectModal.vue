<template>
  <!-- 删除工作区：只摘掉归属，本机目录里的文件一个都不动。
       所以没有「连文件一起删」这个选项——目录在用户电脑上，问渠没有资格代删。 -->
  <a-modal v-model:open="visible"
           :title="`删除工作区「${projectName}」？`"
           ok-text="删除工作区"
           :ok-button-props="{ danger: true }"
           cancel-text="取消"
           :confirm-loading="saving"
           @ok="confirm">
    <div class="dp-note">
      会话与产物不受影响：它们仍能在「会话」里看到，只是不再归属这个工作区。
      <b>本机目录里的文件不会被删除</b>，仍在你的电脑上原处保留。
    </div>
  </a-modal>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  projectName: { type: String, default: '' },
  saving: { type: Boolean, default: false }
})
const emit = defineEmits(['update:open', 'confirm'])

const visible = computed({
  get: () => props.open,
  set: v => emit('update:open', v)
})
const confirm = () => emit('confirm')
</script>

<style scoped>
.dp-note {
  font-size: 13px; line-height: 1.7; color: var(--app-label-secondary);
  padding: 10px 12px; background: var(--app-bg-layer-2); border-radius: var(--app-radius-sm);
}
</style>
