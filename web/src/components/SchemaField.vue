<template>
  <a-form-item v-if="visible">
    <template #label>
      <a-tooltip v-if="tipText" :title="tipText" placement="top">
        <span style="display:inline-flex;align-items:center">
          <span v-if="field.core" class="core-dot"></span>{{ field.label }}
          <a-tag v-if="field.debug" color="warning" size="small" style="margin-left:4px">调试</a-tag>
          <question-circle-outlined class="tip-icon" />
        </span>
      </a-tooltip>
      <span v-else style="display:inline-flex;align-items:center">
        <span v-if="field.core" class="core-dot"></span>{{ field.label }}
        <a-tag v-if="field.debug" color="warning" size="small" style="margin-left:4px">调试</a-tag>
      </span>
    </template>

    <template v-if="field.type === 'switch'">
      <a-switch v-model:checked="value" />
    </template>

    <!-- 模型选择（模型供应商库）：值为引用 providerId/modelId，带品牌图标按供应商分组 -->
    <template v-else-if="field.type === 'model'">
      <ModelSelect v-model="value" :type="field.modelType || 'chat'"
                   :allow-clear="!!field.allowClear" :width="field.width || 420"
                   :admin-tip-visible="true" />
    </template>

    <template v-else-if="field.type === 'number'">
      <a-input-number v-model:value="value" :min="field.min" :max="field.max" :step="field.step"
                      :style="{ width: (field.width || 200) + 'px' }" />
    </template>

    <template v-else-if="field.type === 'range'">
      <a-input-number v-model:value="value" :min="field.min" :max="field.max" :step="field.step"
                      :style="{ width: (field.width || 90) + 'px' }" />
      <span style="margin:0 6px;color:#999">{{ field.pairLabel || '~' }}</span>
      <a-input-number v-model:value="pairValue" :min="pairField.min" :max="pairField.max"
                      :step="pairField.step" :style="{ width: (field.width || 90) + 'px' }" />
    </template>

    <template v-else-if="field.type === 'textarea'">
      <a-textarea v-model:value="value" :rows="field.rows || 3" :placeholder="field.ph" />
    </template>

    <template v-else-if="field.type === 'select'">
      <a-select v-model:value="value" :options="field.options" :style="{ width: (field.width || 240) + 'px' }"
                :placeholder="field.ph" @change="onSelectChange" />
    </template>

    <template v-else-if="field.type === 'password'">
      <a-input-password v-model:value="value" :style="{ width: (field.width || 420) + 'px' }"
                        :placeholder="field.ph" />
    </template>

    <template v-else>
      <a-input v-model:value="value" :style="{ width: (field.width || 420) + 'px' }" :placeholder="field.ph" />
    </template>

    <!-- 扩展位：测试连接按钮、状态标签等由使用方插入 -->
    <slot name="extra" :field="field" />

    <span v-if="field.note" style="margin-left:12px;color:#999;font-size:12px">{{ field.note }}</span>
  </a-form-item>
</template>

<script setup>
import { computed } from 'vue'
import { QuestionCircleOutlined } from '@ant-design/icons-vue'
import { FIELDS } from '../configSchema'
import ModelSelect from './ModelSelect.vue'

const props = defineProps({
  field: { type: Object, required: true },
  form: { type: Object, required: true },
  tips: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['change'])

const read = (o, p) => p.split('.').reduce((a, k) => (a == null ? a : a[k]), o)
const write = (o, p, v) => {
  const seg = p.split('.')
  let t = o
  for (let i = 0; i < seg.length - 1; i++) t = t[seg[i]]
  t[seg[seg.length - 1]] = v
}

const value = computed({
  get: () => read(props.form, props.field.path),
  set: v => write(props.form, props.field.path, v)
})

const pairPath = computed(() =>
  props.field.pairKey ? props.field.path.replace(/[^.]+$/, props.field.pairKey) : '')
const pairField = computed(() =>
  FIELDS.find(f => f.path === pairPath.value) || {})
const pairValue = computed({
  get: () => (pairPath.value ? read(props.form, pairPath.value) : undefined),
  set: v => pairPath.value && write(props.form, pairPath.value, v)
})

const tipText = computed(() => (props.field.tips ? props.tips[props.field.tips] : '') || '')

const visible = computed(() => {
  if (!props.field.vif) return true
  for (const cond of props.field.vif.split('&&').map(s => s.trim())) {
    if (!read(props.form, cond)) return false
  }
  return true
})

// 枚举变更需要通知使用方（如切换关键词引擎要校验服务）
const onSelectChange = v => emit('change', props.field, v)
</script>

<style scoped>
.tip-icon {
  color: #bbb;
  font-size: 12px;
  margin-left: 4px;
  cursor: help;
}
.tip-icon:hover {
  color: #1677ff;
}
.core-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #faad14;
  margin-right: 6px;
}
</style>
