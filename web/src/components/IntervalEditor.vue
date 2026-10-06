<template>
  <span class="ie">
    <!-- 展示态：把毫秒还原成「1 天」这类可读写法，点一下进入编辑 -->
    <a-tooltip v-if="!editing" :title="paused ? '已暂停（间隔写 0），点击恢复' : '点击调整间隔'">
      <button class="ie-btn" :class="{ off: paused }" @click="startEdit">{{ paused ? '已暂停' : display }}</button>
    </a-tooltip>

    <span v-else class="ie-edit">
      <a-input-number v-model:value="amount" size="small" :min="1" :max="999999" :step="1"
                      style="width: 68px" @press-enter="commit" />
      <a-select v-model:value="unit" size="small" :options="unitOptions" style="width: 82px" @change="commit" />
      <a-tooltip title="取消">
        <button class="app-icon-btn" aria-label="取消编辑" @click="editing = false"><close-outlined /></button>
      </a-tooltip>
      <a-tooltip title="保存">
        <button class="app-icon-btn ie-ok" aria-label="保存间隔" :disabled="saving" @click="commit">
          <check-outlined />
        </button>
      </a-tooltip>
    </span>
  </span>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { CloseOutlined, CheckOutlined } from '@ant-design/icons-vue'

const props = defineProps({
  /** 当前间隔（毫秒）；≤0 视为已暂停 */
  modelValue: { type: Number, default: 0 },
  saving: { type: Boolean, default: false },
  paused: { type: Boolean, default: false }
})
const emit = defineEmits(['save'])

// 单位目录与 SchemaField 的 ms 换算保持一致：优先能整除的最大单位（7 天而不是 604800000 毫秒）
const UNITS = [
  { value: 1000, label: '秒' },
  { value: 60000, label: '分钟' },
  { value: 3600000, label: '小时' },
  { value: 86400000, label: '天' }
]
const unitOptions = UNITS.map(u => ({ value: u.value, label: u.label }))

/** 当前值能整除的最大单位；整除不了就退到「分钟」量级，避免出现 1.5 天这种读法 */
const autoUnit = ms => {
  if (!ms || ms <= 0) return 60000
  return UNITS.map(u => u.value).find(u => ms % u === 0) || 60000
}

const display = computed(() => {
  const ms = Number(props.modelValue) || 0
  if (ms <= 0) return '已暂停'
  const u = autoUnit(ms)
  const hit = UNITS.find(x => x.value === u)
  const n = ms / u
  return `${Number.isInteger(n) ? n : Math.round(n * 10) / 10} ${hit.label}`
})

const editing = ref(false)
const amount = ref(null)
const unit = ref(60000)

const startEdit = () => {
  const ms = Number(props.modelValue) || 0
  unit.value = autoUnit(ms)
  // 暂停态（0）没有可换算的值，给个常见缺省值让用户直接改，而不是显示空白输入框
  amount.value = ms > 0 ? Math.max(1, Math.round(ms / unit.value)) : 1
  editing.value = true
}

const commit = () => {
  const n = Number(amount.value)
  if (!Number.isFinite(n) || n <= 0) return
  const ms = Math.round(n * unit.value)
  editing.value = false
  // 值没变就不发请求：避免误触点保存产生无意义的写入与「已保存」提示
  if (ms === Number(props.modelValue)) return
  emit('save', ms)
}

// 外部刷新（保存后 loadSchedule 回来）时若正在编辑，不要打断用户输入
watch(() => props.saving, v => { if (!v) editing.value = false })
</script>

<style scoped>
/* 展示态做成按钮而非纯文本：它可点，样式要与纯文本明显区分，否则用户不会想到能在这里改 */
.ie-btn {
  border: 1px dashed var(--app-border); background: transparent; cursor: pointer;
  border-radius: 6px; padding: 1px 8px; font-size: 12px; color: var(--app-text);
  font-family: inherit; line-height: 20px;
}
.ie-btn:hover { border-color: var(--app-accent); color: var(--app-accent); }
.ie-btn.off { color: var(--app-warn-text); border-color: var(--app-warn-border); }
.ie-edit { display: inline-flex; align-items: center; gap: 4px; }
.ie-ok { color: var(--app-ok); }
</style>
