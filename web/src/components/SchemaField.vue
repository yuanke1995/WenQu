<template>
  <!-- 裸控件模式（bare）：用在 a-form 之外（如运行状态表格的行内参数）。
       a-form-item 依赖 Form 上下文提供 label 栅格与校验，脱离 a-form 渲染会整块塌掉不显示，
       故此处不套 a-form-item，由使用方自带标签与提示。 -->
  <template v-if="bare">
    <template v-if="field.type === 'switch'">
      <a-switch v-model:checked="value" size="small" />
    </template>
    <template v-else-if="field.type === 'number'">
      <a-input-number v-if="msUnits.length" v-model:value="msAmount" size="small"
                      :min="msAmountMin" :max="msAmountMax" :step="1" style="width: 76px" />
      <a-input-number v-else v-model:value="value" size="small" :min="field.min" :max="field.max"
                      :step="field.step" style="width: 84px" />
    </template>
    <template v-else-if="field.type === 'select'">
      <a-select v-model:value="value" size="small" :options="field.options" style="width: 120px" />
    </template>
    <template v-else-if="field.type === 'multiselect'">
      <a-select :value="rawMulti" :mode="field.freeInput === false ? 'multiple' : 'tags'" size="small" :options="multiOptions" allow-clear
                option-filter-prop="label" :max-tag-count="2" style="width: 180px"
                @update:value="v => value = normalizeMulti(v)" />
    </template>
    <template v-else-if="field.type === 'model'">
      <ModelSelect v-model="value" :type="field.modelType || 'chat'" :allow-clear="!!field.allowClear" width="100%" />
    </template>
    <a-input-password v-else-if="field.type === 'password'" v-model:value="value" size="small" style="width: 120px" />
    <a-input v-else v-model:value="value" size="small" style="width: 120px" />
  </template>

  <a-form-item v-else-if="visible" :data-fix="field.backendKey">
    <template #label>
      <a-tooltip v-if="tipText" :title="tipText" placement="top"
                 :overlay-inner-style="{ whiteSpace: 'pre-line' }">
        <span style="display:inline-flex;align-items:center">
          <span>{{ field.label }}</span>
          <a-tag v-if="field.debug" color="warning" size="small" style="margin-left:4px">调试</a-tag>
          <a-tag v-if="field.personal" color="blue" size="small" style="margin-left:4px">个人可覆盖</a-tag>
          <a-tooltip v-if="srcTag" :title="srcTag.tip">
            <a-tag :color="srcTag.color" size="small" style="margin-left:4px">{{ srcTag.text }}</a-tag>
          </a-tooltip>
          <question-circle-outlined class="tip-icon" />
        </span>
      </a-tooltip>
      <span v-else style="display:inline-flex;align-items:center">
        <span>{{ field.label }}</span>
        <a-tag v-if="field.debug" color="warning" size="small" style="margin-left:4px">调试</a-tag>
        <a-tag v-if="field.personal" color="blue" size="small" style="margin-left:4px">个人可覆盖</a-tag>
        <a-tooltip v-if="srcTag" :title="srcTag.tip">
          <a-tag :color="srcTag.color" size="small" style="margin-left:4px">{{ srcTag.text }}</a-tag>
        </a-tooltip>
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

    <!-- 多选名单（逗号串）：表单里是字符串数组，提交时 join(',') 还原成后端那一个字符串键
         （见 SettingsPage.buildPayload）。候选由调用方给（options prop），字段自己不去猜数据源 -->
    <template v-else-if="field.type === 'multiselect'">
      <a-select :value="rawMulti" :mode="field.freeInput === false ? 'multiple' : 'tags'" :options="multiOptions" allow-clear show-search
                option-filter-prop="label" :max-tag-count="field.maxTagCount || 8"
                :style="{ width: (field.width || 420) + 'px' }" :placeholder="field.ph"
                @update:value="v => value = normalizeMulti(v)">
        <!-- 标签只用工具自己的名字：候选行里的「族 · 中文名」是为了在长列表里认得出，
             挂成标签后族名是噪声（同一个工具不会因为少个前缀而认不出），4 个标签就能把
             输入行撑满两行。注意这会覆盖 antd 从 option.label 自动取的标签文案 -->
        <template #tagRender="{ label, closable, onClose }">
          <span class="ms-tag">
            {{ tagText(label) }}
            <close-outlined v-if="closable" class="ms-tag-x" @click="onClose" />
          </span>
        </template>
      </a-select>
    </template>

    <template v-else-if="field.type === 'number'">
      <!-- ms 类字段（schema 标了 presets）：按「数值 + 单位」录入，落库仍是毫秒。
           直接填 86400000 这类裸毫秒数没人读得懂——根治办法是让输入框本身就带单位，
           选「天」填 7 即 604800000。用户调的是「等多久」，换算出来的毫秒数不用回显给他 -->
      <template v-if="msUnits.length">
        <!-- flex 垂直居中：antd 的 input-number 与 select 各自 inline-block 按基线排，会差半个像素行 -->
        <span class="ms-row">
          <a-input-number v-model:value="msAmount" :min="msAmountMin" :max="msAmountMax"
                          :step="1" style="width: 96px" />
          <a-select v-model:value="msUnit" :options="msUnits" style="width: 84px; margin-left: 6px" />
        </span>
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
import { QuestionCircleOutlined, CloseOutlined } from '@ant-design/icons-vue'
import { FIELDS, isVisible } from '../configSchema'
import ModelSelect from './ModelSelect.vue'

const props = defineProps({
  field: { type: Object, required: true },
  form: { type: Object, required: true },
  tips: { type: Object, default: () => ({}) },
  /** 多选类字段的候选（type=multiselect）：数据源在调用方——同一类字段在不同页面候选不同
   *  （平台的停用名单＝工具全景，智能体的名单＝名册），字段定义里写死一处会锁死另一处 */
  options: { type: Array, default: () => [] },
  /** 裸控件模式：不套 a-form-item（见模板顶部注释）。标签与说明由使用方负责 */
  bare: { type: Boolean, default: false },
  /** 这项此刻由哪一层说话（后端 snapshot 的 source/invalid，见 ConfigService#resolve）。
      只在「不是平台设置」或「库里那行不合字段定义」时出现，正常情况不打扰 */
  source: { type: Object, default: null }
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

// 「谁在说话」徽标：平台设置（库里那行）是常态，不打扰；
// 只有生效值不是来自平台设置、或库里那行根本不合字段定义时才说一句——
// 那正是「界面写着 A、实际跑的是 B」唯一的界面线索
const srcTag = computed(() => {
  const s = props.source
  if (!s) return null
  if (s.invalid) {
    return { text: '值不合定义', color: 'error', tip: '库里这行的值不符合字段定义：' + s.invalid + '。生效的就是这个越界值，与界面写的可能不一样 — 重新保存一次即可修正。' }
  }
  if (s.source === 'CODE_DEFAULT') {
    return { text: '出厂默认', color: 'warning', tip: '配置表里没有这一行，此刻生效的是代码默认值；在这里改一次就会写入配置表。' }
  }
  if (s.source === 'UNREGISTERED') {
    return { text: '无处取值', color: 'error', tip: '字段定义、默认值清单与配置表三处都没有这一项，读到的是空值 — 这条配置实际不生效。' }
  }
  if (s.source === 'USER_OVERRIDE') {
    return { text: '个人覆盖', color: 'blue', tip: '这一项正被成员的个人设置覆盖，全局值对他不生效。' }
  }
  if (s.source === 'EVAL_OVERRIDE') {
    return { text: '评估覆盖', color: 'purple', tip: '检索评估的参数对比正在临时改写这一项，跑完即清。' }
  }
  return null
})

// ==================== 多选名单（逗号串） ====================
// 表单值是数组（控件要数组），落库值是逗号串——两端的换算只在这里与 SettingsPage 的
// fetchAndFill/buildPayload 各一处，别在别处再 split 一次（多一处就是多一个口径）
const normalizeMulti = v => (Array.isArray(v) ? v : [])
  .map(s => String(s).trim()).filter(Boolean)
const rawMulti = computed(() => {
  const v = value.value
  if (Array.isArray(v)) return v
  return String(v ?? '').split(/[,\n\r，;；]+/).map(s => s.trim()).filter(Boolean)
})
// 候选 = 调用方给的名册 + 当前值里名册已经没有的名字。少了后一半，被停用/改名后的工具
// 在界面上会整个消失（标签没了），前端读起来就是「我没停用它」——而它其实还在库里拦着
const multiOptions = computed(() => {
  const given = (props.options || []).map(o => (typeof o === 'string' ? { value: o, label: o } : o))
  const known = new Set(given.map(o => o.value))
  const stale = rawMulti.value.filter(v => !known.has(v))
    .map(v => ({ value: v, label: v + '（已不在名册里）' }))
  return given.concat(stale)
})
/** 标签文案：候选行是「族 · 中文名」，标签只留工具名（见模板里 tagRender 的说明） */
const tagText = label => String(label ?? '').split(' · ').pop()

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
.num-presets { flex: none; }
.ms-row {
  display: inline-flex;
  align-items: center;
}
/* 多选标签：默认 antd 标签在长标签下会把选择框撑得很高，这里收一下字号与内边距 */
.ms-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  padding: 0 6px;
  margin: 2px 4px 2px 0;
  border: 1px solid var(--app-border);
  border-radius: 4px;
  background: var(--app-panel-2);
  color: var(--app-text);
  font-size: 12px;
  line-height: 20px;
}
.ms-tag-x {
  font-size: 10px;
  color: var(--app-text3);
  cursor: pointer;
}
.ms-tag-x:hover {
  color: var(--app-accent);
}
</style>
