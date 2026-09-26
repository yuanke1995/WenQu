<template>
  <a-select
    :value="modelValue || undefined"
    :style="{ width: pill ? pillWidth + 'px' : (typeof width === 'string' ? width : (width || 320) + 'px') }"
    :placeholder="placeholder || '选择模型'"
    :disabled="disabled"
    :loading="loading"
    :allow-clear="allowClear"
    :class="{ 'ms-pill': pill, 'ms-has-value': !!modelValue }"
    popup-class-name="ms-dropdown"
    dropdown-class-name="ms-dropdown"
    option-label-prop="label"
    show-search
    option-filter-prop="label"
    @change="onChange"
  >
    <template #suffixIcon>
      <loading-outlined v-if="loading" spin class="ms-caret" />
      <down-outlined v-else class="ms-caret" />
    </template>
    <a-select-option v-if="inheritLabel && !pill" value="" :label="inheritLabel">
      <span class="ms-inherit">{{ inheritLabel }}</span>
    </a-select-option>
    <a-select-opt-group v-for="g in groups" :key="g.providerId" :label="g.name">
      <a-select-option
        v-for="m in g.models"
        :key="m.ref"
        :value="m.ref"
        :label="m.displayName"
      />
    </a-select-opt-group>
    <!-- 下拉行自绘（品牌图标+名称，图标按 value 反查 groups）；触发器只显示 label 纯文本 -->
    <template #option="opt">
      <span v-if="opt.value === ''" class="ms-inherit">{{ opt.label }}</span>
      <span v-else-if="!opt.value" class="ms-group-text">{{ opt.label }}</span>
      <span v-else class="ms-option">
        <ProviderIcon :icon="iconOf(opt.value)" :name="providerNameOf(opt.value)" :size="16" />
        <span class="ms-name">{{ opt.label }}</span>
      </span>
    </template>
    <template v-if="!loading && !groups.length" #notFoundContent>
      <div class="ms-empty">
        暂无可用模型
        <div class="ms-empty-tip" v-if="adminTipVisible">请先到「模型供应商」新建供应商并登记模型</div>
      </div>
    </template>
  </a-select>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { DownOutlined, LoadingOutlined } from '@ant-design/icons-vue'
import { listAvailableModels } from '../api.js'
import ProviderIcon from './ProviderIcon.vue'

/**
 * 模型选择器（复用组件）：数据来自 /provider/available，按供应商分组、选项带品牌图标，
 * 值为模型引用串 `{providerId}/{modelId}`（后端 DynamicOpenAiChatModel 据此路由到对应供应商网关）。
 * inheritLabel 传入时在首位加「跟随全局」空值选项（'' = 继承，如智能体的「跟随全局模型」）。
 * pill=true 时渲染成对话工具栏用的紧凑胶囊（与智能体胶囊同构），默认为设置页的常规选框。
 */
const props = defineProps({
  modelValue: { type: String, default: '' },
  /** 类型过滤：chat / vision / embedding / rerank（空=全部） */
  type: { type: String, default: 'chat' },
  /** 「跟随全局」选项：label 用作未指定时触发器显示的文案（如当前生效模型名），下拉项固定显示「跟随全局」 */
  inheritLabel: { type: String, default: '' },
  /** 允许清空（清除后值为 ''，如视觉/向量/重排槽位可空） */
  allowClear: { type: Boolean, default: false },
  /** 胶囊形态：28px 高全圆角，弱化边框（对话工具栏） */
  pill: { type: Boolean, default: false },
  placeholder: { type: String, default: '' },
  /** 宽度：数字=像素；字符串原样使用（如 "100%" 适配表单栅格） */
  width: { type: [Number, String], default: 320 },
  disabled: { type: Boolean, default: false },
  /** 空数据时是否提示管理员去「模型供应商」页登记（非管理员界面可不提示） */
  adminTipVisible: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue', 'change'])

const groups = ref([])
const loading = ref(false)

// 模块级缓存（10s TTL）：同一页面多个选择器（设置页四组/聊天页）共享一次请求
let cache = { ts: 0, data: [] }
const CACHE_MS = 10000

async function load(force = false) {
  if (!force && Date.now() - cache.ts < CACHE_MS) {
    groups.value = filter(cache.data)
    return
  }
  loading.value = true
  try {
    const data = await listAvailableModels()
    cache = { ts: Date.now(), data: data || [] }
    groups.value = filter(data)
  } catch (e) {
    // 静默：选择器拉不到数据时显示空态（不弹全局错误打断页面）
    groups.value = []
  } finally {
    loading.value = false
  }
}

function filter(data) {
  if (!props.type) return data
  return data
    .map(g => ({ ...g, models: g.models.filter(m => m.type === props.type) }))
    .filter(g => g.models.length)
}

function onChange(v) {
  // 清空时 antd 返回 undefined，统一归一为 ''（=未指定/继承）
  emit('update:modelValue', v || '')
  emit('change', v || '')
}

/** 下拉自绘行用：按引用反查所属供应商的图标 / 名称 */
function iconOf(ref) {
  for (const g of groups.value) {
    if (g.models.some(x => x.ref === ref)) return g.icon
  }
  return ''
}
function providerNameOf(ref) {
  for (const g of groups.value) {
    if (g.models.some(x => x.ref === ref)) return g.name
  }
  return ''
}

onMounted(() => load())

defineExpose({ refresh: () => load(true) })

// 幽灵胶囊下宽度随触发器文案收缩（近似 ZCode 的 w-fit）：canvas 实测文本宽 + 内边距/箭头位
const pillWidth = ref(props.width)
let measureCtx = null
function measurePill() {
  if (!props.pill) return
  let label = props.placeholder || props.inheritLabel || '选择模型'
  if (props.modelValue) {
    label = props.modelValue
    for (const g of groups.value) {
      const hit = g.models.find(x => x.ref === props.modelValue)
      if (hit) { label = hit.displayName; break }
    }
  }
  if (!measureCtx) measureCtx = document.createElement('canvas').getContext('2d')
  measureCtx.font = '13px -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif'
  const w = Math.ceil(measureCtx.measureText(label || '').width)
  pillWidth.value = Math.min(props.width, Math.max(120, w + 44))
}
watch([() => props.modelValue, () => groups.value, () => props.inheritLabel,
       () => props.pill, () => props.width], measurePill, { immediate: true })
</script>

<style scoped>
.ms-option {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 100%;
}
.ms-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ms-inherit {
  color: #888;
}
.ms-empty {
  padding: 8px 0;
  text-align: center;
  color: #999;
}
.ms-empty-tip {
  font-size: 12px;
  color: #bbb;
  margin-top: 2px;
}
/* 后缀统一为小箭头（antd 默认的放大镜让选框看起来像搜索框），加载时转圈 */
.ms-caret {
  font-size: 12px !important;
  color: var(--app-text3);
}

/* ==================== 幽灵胶囊形态（对话工具栏，对齐 ZCode 触发器） ====================
 * h-7 rounded-full、常规字重灰字，无边框无底色；hover/展开才出现浅灰底、文字提亮。 */
.ms-pill {
  font-size: 13px;
  vertical-align: middle;
  border-radius: 999px;
  transition: background .15s;
}
.ms-pill:hover,
.ms-pill.ant-select-open {
  background: #f2f3f5;
}
.ms-pill :deep(.ant-select-selector) {
  height: 28px !important;
  border: none !important;
  background: transparent !important;
  box-shadow: none !important;
  border-radius: 999px !important;
  padding: 0 24px 0 12px !important;
  font-size: 13px;
}
.ms-pill :deep(.ant-select-selection-item),
.ms-pill :deep(.ant-select-selection-placeholder) {
  line-height: 26px !important;
  color: var(--app-text3);
}
/* 已指定模型时文字用前景色，「跟随：…」保持灰 */
.ms-pill.ms-has-value :deep(.ant-select-selection-item) {
  color: var(--app-text);
}
.ms-pill :deep(.ant-select-selection-item) {
  padding-inline-end: 0 !important;
}
.ms-pill :deep(.ant-select-selection-search) {
  height: 26px !important;
}
.ms-pill :deep(.ant-select-selection-search-input) {
  height: 26px !important;
}
</style>

<!-- 下拉面板 teleport 到 body，需全局样式（类名经 popup-class-name 传入） -->
<style>
.ms-dropdown {
  padding: 6px !important;
  border-radius: 12px !important;
}
.ms-dropdown .ant-select-item-group {
  font-size: 11px !important;
  line-height: 1 !important;
  color: var(--app-text3) !important;
  padding: 10px 10px 6px !important;
}
.ms-dropdown .ant-select-item-option {
  min-height: 30px !important;
  line-height: 20px !important;
  padding: 5px 10px !important;
  border-radius: 8px !important;
}
.ms-dropdown .ant-select-item-option .ms-option {
  font-size: 13px;
}
.ms-dropdown .ant-select-item-option-active {
  background: #f2f3f5 !important;
}
.ms-dropdown .ant-select-item-option-selected {
  background: transparent !important;
  font-weight: 500 !important;
  color: var(--app-accent) !important;
}
</style>
