<template>
  <!-- 删除工作区二选一：保留文件（默认）/ 连文件一起删。
       默认「保留」是因为删了工作区行就再也找不到那个目录——文件留着只是打不开，
       删了才是真没了；所以连删必须由用户显式选，且要说清大小。 -->
  <a-modal v-model:open="visible"
            :title="`删除工作区「${projectName}」？`"
            :ok-text="withFiles ? '连文件一起删' : '保留文件并删除'"
            :ok-button-props="{ danger: withFiles }"
            cancel-text="取消"
            :confirm-loading="saving"
            @ok="confirm">
    <a-radio-group v-model:value="withFiles" class="dp-list">
      <a-radio :value="false" class="dp-item">
        <span class="dp-t">保留工作目录</span>
        <span class="dp-d">文件仍在盘上，但将再也打不开</span>
      </a-radio>
      <a-radio :value="true" class="dp-item">
        <span class="dp-t">连文件一起删</span>
        <span class="dp-d">工作目录整体删除，不可恢复{{ sizeText }}</span>
      </a-radio>
    </a-radio-group>
    <div class="dp-note">
      会话与产物不受影响：它们仍能在「会话」里看到，只是不再归属这个工作区。
      <template v-if="!filesKnown">工作目录大小可在工作区页的文件区查看。</template>
    </div>
  </a-modal>
</template>

<script setup>
import { ref, computed, watch } from 'vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  projectName: { type: String, default: '' },
  // 文件数未知（如侧栏入口）时 sizeText 不给数字，只说清语义
  filesKnown: { type: Boolean, default: false },
  fileCount: { type: Number, default: 0 },
  totalSize: { type: Number, default: 0 },
  saving: { type: Boolean, default: false }
})
const emit = defineEmits(['update:open', 'confirm'])

const visible = computed({
  get: () => props.open,
  set: v => emit('update:open', v)
})
// 每次打开都回到「保留」：破坏性选项不该是默认选中的那个
const withFiles = ref(false)
watch(() => props.open, v => { if (v) withFiles.value = false })

const sizeText = computed(() => {
  if (!props.filesKnown) return ''
  if (!props.fileCount) return '（目录为空或还没建）'
  return `（${props.fileCount} 个文件，约 ${fmtSize(props.totalSize)}）`
})
const fmtSize = n => {
  const v = Number(n) || 0
  if (v >= 1024 * 1024) return (v / 1024 / 1024).toFixed(1) + ' MB'
  if (v >= 1024) return (v / 1024).toFixed(1) + ' KB'
  return v + ' B'
}

const confirm = () => emit('confirm', withFiles.value)
</script>

<style scoped>
.dp-list { display: flex; flex-direction: column; gap: 12px; width: 100%; }
.dp-item { align-items: flex-start; }
.dp-t { display: block; font-weight: 500; }
.dp-d { display: block; font-size: 12px; color: var(--app-label-tertiary); line-height: 1.6; }
.dp-note {
  margin-top: 12px; font-size: 12px; line-height: 1.6; color: var(--app-label-tertiary);
  padding: 8px 10px; background: var(--app-bg-layer-2); border-radius: var(--app-radius-sm);
}
</style>
