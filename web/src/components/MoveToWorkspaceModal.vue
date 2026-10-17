<template>
  <!-- 移动到工作区：会话/产物共用的归属整理弹窗。
       目标列表刻意包含「已归档」的工作区——归档=不接新活，但整理历史不该被"收工"挡住；
       implicit 兜底容器不进列表（它与「不挂任何工作区」在侧栏同属「会话」栏，给两个入口只会让人猜）。 -->
  <a-modal v-model:open="visible" :title="title" ok-text="移动" cancel-text="取消" :confirm-loading="saving" @ok="confirm">
    <a-radio-group v-model:value="picked" class="mws-list">
      <a-radio v-for="p in targets" :key="p.id" :value="p.id" class="mws-item">
        <span class="mws-name">{{ p.displayName || p.name || '未命名工作区' }}</span>
        <span v-if="p.status === 'archived'" class="mws-arch">已归档</span>
        <span v-if="p.id === currentId" class="mws-cur">当前</span>
      </a-radio>
      <a-radio value="" class="mws-item">
        <span class="mws-name">不挂任何工作区</span>
        <span class="mws-cur">回到「会话」栏</span>
      </a-radio>
    </a-radio-group>
    <div class="mws-note">{{ note }}</div>
  </a-modal>
</template>

<script setup>
import { ref, computed, watch } from 'vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  // 全部工作区（含 implicit 与已归档；组件内过滤 implicit）
  projects: { type: Array, default: () => [] },
  currentId: { type: String, default: '' },
  title: { type: String, default: '移动到工作区' },
  note: { type: String, default: '' },
  saving: { type: Boolean, default: false }
})
const emit = defineEmits(['update:open', 'moved'])

const visible = computed({
  get: () => props.open,
  set: v => emit('update:open', v)
})
const picked = ref('')
const targets = computed(() => props.projects.filter(p => !p.isImplicit))

// 每次打开都回到当前归属：从半路选错里一眼看出"我在哪"
watch(() => props.open, v => { if (v) picked.value = props.currentId || '' })

const confirm = () => emit('moved', picked.value)
</script>

<style scoped>
.mws-list { display: flex; flex-direction: column; gap: 10px; width: 100%; }
.mws-item { align-items: center; }
.mws-name { margin-right: 6px; }
.mws-arch {
  font-size: 11px; line-height: 16px; padding: 0 5px; margin-right: 6px; border-radius: 4px;
  background: var(--app-bg-layer-2); color: var(--app-label-tertiary);
}
.mws-cur {
  font-size: 11px; line-height: 16px; padding: 0 5px; border-radius: 4px;
  background: var(--app-accent-weak); color: var(--app-accent);
}
/* 边界说清：移动改的是归属，不是搬家——已落盘的文件留在旧工作区 */
.mws-note {
  margin-top: 12px; font-size: 12px; line-height: 1.6; color: var(--app-label-tertiary);
  padding: 8px 10px; background: var(--app-bg-layer-2); border-radius: var(--app-radius-sm);
}
</style>
