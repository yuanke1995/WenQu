<template>
  <a-form-item v-if="visible">
    <template #label>
      <a-tooltip v-if="tipText" :title="tipText" placement="top"
                 :overlay-inner-style="{ whiteSpace: 'pre-line' }">
        <span style="display:inline-flex;align-items:center">
          <span v-if="field.core" class="core-dot"></span>{{ field.label }}
          <a-tag v-if="field.debug" color="warning" size="small" style="margin-left:4px">调试</a-tag>
          <a-tag v-if="field.personal" color="blue" size="small" style="margin-left:4px">个人可覆盖</a-tag>
          <question-circle-outlined class="tip-icon" />
        </span>
      </a-tooltip>
      <span v-else style="display:inline-flex;align-items:center">
        <span v-if="field.core" class="core-dot"></span>{{ field.label }}
        <a-tag v-if="field.debug" color="warning" size="small" style="margin-left:4px">调试</a-tag>
        <a-tag v-if="field.personal" color="blue" size="small" style="margin-left:4px">个人可覆盖</a-tag>
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
      <!-- ms 类字段（schema 标了 presets）：按「数值 + 单位」录入，落库仍是毫秒。
           直接填 86400000 这类裸毫秒数没人读得懂，旁边挂换算提示也只是少算一次——
           根治办法是让输入框本身就带单位，选「天」填 7 即 604800000 -->
      <template v-if="msUnits.length">
        <a-input-number v-model:value="msAmount" :min="msAmountMin" :max="msAmountMax"
                        :precision="msUnit === 1 ? 0 : 2" :step="1" style="width: 130px" />
        <a-select v-model:value="msUnit" :options="msUnits" style="width: 92px; margin-left: 8px" />
        <span v-if="msUnit !== 1 && value != null" class="num-hint">= {{ value }} ms</span>
      </template>
      <a-input-number v-else v-model:value="value" :min="field.min" :max="field.max" :step="field.step"
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
import { computed, ref, watch } from 'vue'
import { QuestionCircleOutlined } from '@ant-design/icons-vue'
import { FIELDS, isVisible } from '../configSchema'
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

// 条件显隐统一走 configSchema.isVisible（path / path=v1,v2 两种形态），blocksOf 过滤分节标题用的是同一份逻辑
const visible = computed(() => isVisible(props.field, props.form))

// ms 类字段（schema 里 presets 为 [毫秒, 中文标签] 数组）：数值按所选单位录入，落库仍是毫秒。
// 可用单位由字段自身的 presets 量级 + min/max 决定——超时类只给秒/分钟，清理间隔给到天，
// 免得在「检索超时」下拉里出现「天」这种注定越界的选项
const MS_UNIT_CATALOG = [[1, '毫秒'], [1000, '秒'], [60000, '分钟'], [3600000, '小时'], [86400000, '天']]
const msUnits = computed(() => {
  const ps = props.field.presets
  if (!ps || !ps.length) return []
  const vals = ps.map(p => p[0])
  const lo = Number.isFinite(props.field.min) ? props.field.min : 0
  const hi = Number.isFinite(props.field.max) ? props.field.max : Math.max(...vals) * 4
  return MS_UNIT_CATALOG
    .filter(([u]) => u >= lo && u <= hi)
    .map(([value, label]) => ({ value, label }))
})

/** 未手动选单位时按当前值挑：优先能整除的最大单位（7 天而不是 604800000 毫秒），否则够得上 1 的最大单位 */
const autoUnit = () => {
  const opts = msUnits.value.map(o => o.value).sort((a, b) => b - a)
  if (!opts.length) return 1
  const v = Number(value.value)
  if (!Number.isFinite(v) || v <= 0) return opts.find(u => u === 60000) || opts[opts.length - 1]
  return opts.find(u => v % u === 0) || opts.find(u => v / u >= 1) || 1
}
const unitOverride = ref(null)
const msUnit = computed({
  get: () => unitOverride.value || autoUnit(),
  set: u => { unitOverride.value = u }
})
const msAmount = computed({
  get: () => {
    const v = Number(value.value)
    if (!Number.isFinite(v)) return null
    const n = v / msUnit.value
    return msUnit.value === 1 ? Math.round(n) : Math.round(n * 100) / 100
  },
  set: v => { value.value = v == null ? null : Math.round(v * msUnit.value) }
})
const msAmountMin = computed(() => (Number.isFinite(props.field.min) ? props.field.min / msUnit.value : undefined))
const msAmountMax = computed(() => (Number.isFinite(props.field.max) ? props.field.max / msUnit.value : undefined))
// 切面板/分组时组件实例会被复用（v-for key 是序号），单位选择必须随字段重置，否则带着上一个字段的单位
watch(() => props.field.path, () => { unitOverride.value = null })

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
.num-presets { flex: none; }
.num-hint {
  font-size: 12px;
  color: var(--app-text3);
  margin-left: 8px;
  white-space: nowrap;
}
</style>
