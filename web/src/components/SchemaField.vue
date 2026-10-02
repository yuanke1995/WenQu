<template>
  <a-form-item v-if="visible">
    <template #label>
      <a-tooltip v-if="tipText" :title="tipText" placement="top"
                 :overlay-inner-style="{ whiteSpace: 'pre-line' }">
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
      <!-- new-password：这是配置项不是登录表单，阻止浏览器把保存的站点凭据自动填进来 -->
      <a-input-password v-model:value="value" autocomplete="new-password" :style="{ width: (field.width || 420) + 'px' }"
                        :placeholder="field.ph" />
    </template>

    <template v-else>
      <a-input v-model:value="value" autocomplete="off" :style="{ width: (field.width || 420) + 'px' }" :placeholder="field.ph" />
    </template>

    <!-- 扩展位：测试连接按钮、状态标签等由使用方插入 -->
    <slot name="extra" :field="field" />
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

// 问号悬浮文案：tips 长说明 + note 短备注合并（note 原先是控件旁内联灰字，现统一收进问号 tip，
// 换行分段展示——设置页不再有裸露的说明文字）
const tipText = computed(() => {
  const t = (props.field.tips ? props.tips[props.field.tips] : '') || ''
  const n = props.field.note || ''
  return [t, n].filter(Boolean).join('\n')
})

const visible = computed(() => {
  if (!props.field.vif) return true
  for (const cond of props.field.vif.split('&&').map(s => s.trim())) {
    // 两种形态：path（truthy 显示）与 path=v1,v2（取值命中任一即显示，用于枚举联动）
    const eq = cond.indexOf('=')
    if (eq > 0) {
      const actual = String(read(props.form, cond.slice(0, eq).trim()) ?? '')
      if (!cond.slice(eq + 1).split(',').map(s => s.trim()).includes(actual)) return false
    } else if (!read(props.form, cond)) return false
  }
  return true
})

// 枚举变更需要通知使用方（如切换关键词引擎要校验服务）
const onSelectChange = v => emit('change', props.field, v)
</script>

<style scoped>
.tip-icon {
  color: var(--app-text3);
  font-size: 12px;
  margin-left: 4px;
  cursor: help;
}
.tip-icon:hover {
  color: var(--app-accent);
}
.core-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--app-warn);
  margin-right: 6px;
}
</style>
