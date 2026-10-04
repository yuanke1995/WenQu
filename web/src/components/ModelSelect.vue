<template>
  <a-select
    ref="rootRef"
    :value="modelValue || undefined"
    :style="{ width: compact ? '104px' : (pill ? pillWidth + 'px' : (typeof width === 'string' ? width : (width || 320) + 'px')) }"
    :placeholder="placeholder || '选择模型'"
    :disabled="disabled"
    :loading="loading"
    :allow-clear="allowClear"
    :class="{ 'ms-pill': pill, 'ms-has-value': !!modelValue, 'ms-compact': compact }"
    
    popup-class-name="ms-dropdown"
    :dropdown-match-select-width="false"
    option-label-prop="label"
    show-search
    option-filter-prop="label"
    @change="onChange"
    @dropdown-visible-change="v => emit('open-change', v)"
  >
    <template #suffixIcon>
      <loading-outlined v-if="loading" spin class="ms-caret" />
      <down-outlined v-else-if="!compact" class="ms-caret" />
    </template>
    <a-select-option v-if="inheritLabel && !pill" value="" :label="inheritLabel">
      <span class="ms-inherit">{{ inheritLabel }}</span>
    </a-select-option>
    <!-- 引用失配兜底：值不在当前列表（模型/供应商登记变化导致）时，antd 触发器会原样显示
         含供应商 UUID 的引用串；补一个隐藏选项让触发器只显示模型 id 部分 -->
    <a-select-option v-if="orphanRef" :value="orphanRef" :label="orphanLabel" class="ms-orphan" />
    <a-select-opt-group v-for="g in groups" :key="g.providerId" :label="g.name">
      <!-- 行悬浮事件挂 option 上：rc-select 会把 option 的额外事件铺到行根元素（含行内边距区），
           统一转成 option-hover(ref, 行元素) 透传，父层据此按悬浮行弹 per-item 面板（聊天页深度思考） -->
      <a-select-option
        v-for="m in g.models"
        :key="m.ref"
        :value="m.ref"
        :label="m.displayName"
        @mouseenter="emitOptionHover(m.ref, $event)"
        @mouseleave="emitOptionHover('', $event)"
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
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { DownOutlined, LoadingOutlined } from '@ant-design/icons-vue'
import { listAvailableModels } from '../api.js'
import ProviderIcon from './ProviderIcon.vue'

/**
 * 模型选择器（复用组件）：数据来自 /provider/available，按供应商分组、选项带品牌图标，
 * 值为模型引用串 `{providerId}/{modelId}`（后端 DynamicOpenAiChatModel 据此路由到对应供应商网关）。
 * inheritLabel 传入时在首位加「跟随全局」空值选项（'' = 继承，如智能体的「跟随全局模型」）。
 * pill=true 时渲染成对话工具栏用的紧凑胶囊（与智能体胶囊同构），默认为设置页的常规选框。
 * 事件：option-hover(ref, rowEl) 下拉模型行悬浮/离开（ref=''=离开）、open-change(open) 下拉开合。
 */
const props = defineProps({
  modelValue: { type: String, default: '' },
  /** 类型过滤：单值（chat / vision / ocr / embedding / rerank）或逗号分隔多值（如 "vision,ocr"）（空=全部） */
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
  /** 紧凑形态（窄屏工具条）：固定窄宽 + 省略号 + 不显示下拉箭头。
   *  窄屏工具条的横向空间被「智能体 / 思考 / 模型」三方分掉，模型名不收敛会把整条挤到换行
   *  （实测 deepseek-flash 会把「思考」按钮压成竖排两字）。
   *  下拉面板仍是全宽列表、名称完整，不影响选择。
   *  不用「纯图标」形态：vc-select 没有 #label 插槽（那是 Form 的），无法在触发器里注入图标。 */
  compact: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  /** 空数据时是否提示管理员去「模型供应商」页登记（非管理员界面可不提示） */
  adminTipVisible: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue', 'change', 'option-hover', 'open-change'])

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
  // 支持逗号分隔多类型（如 "vision,ocr"：知识库图片描述模型两者皆可）；
  // 期望含 vision 时放宽口径：具备图片理解能力的模型（visionCapable，「聊天+视觉」一体登记形态）同样入选
  const set = new Set(props.type.split(',').map(s => s.trim()).filter(Boolean))
  if (!set.size) return data
  const wantVision = set.has('vision')
  return data
    .map(g => ({ ...g, models: g.models.filter(m => set.has(m.type) || (wantVision && m.visionCapable)) }))
    .filter(g => g.models.length)
}

function onChange(v) {
  // 清空时 antd 返回 undefined，统一归一为 ''（=未指定/继承）
  emit('update:modelValue', v || '')
  emit('change', v || '')
}

/** 下拉行悬浮透传：value=模型引用、''=离开行；rowEl 为行根元素（同步捕获，供父层定位弹层）。
 *  未监听时是 no-op，不影响其它页面的既有用法 */
function emitOptionHover(value, e) {
  emit('option-hover', value, e && e.currentTarget ? e.currentTarget : null)
}

// 引用失配检测：值解析不到任何选项时为真（ref = providerId/modelId，providerId 不含斜杠，
// 取第一个 / 之后作为展示文案）
const orphanRef = computed(() => {
  const v = (props.modelValue || '').trim()
  if (!v) return ''
  return groups.value.some(g => g.models.some(m => m.ref === v)) ? '' : v
})
const orphanLabel = computed(() => {
  const v = orphanRef.value
  if (!v) return ''
  const i = v.indexOf('/')
  return i === -1 ? v : v.slice(i + 1)
})

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

// 同一实例被切到不同类型槽位时（如个人设置聊天↔视觉共用一个选择器）按缓存重过滤，
// 否则新槽位展示的还是上一个类型的模型列表
watch(() => props.type, () => {
  if (cache.data.length) groups.value = filter(cache.data)
})

defineExpose({ refresh: () => load(true) })

// 幽灵胶囊下宽度随触发器文案收缩（近似 ZCode 的 w-fit）：渲染后用 Range 量真实 DOM 文本宽。
// 不用 canvas 预估——触发器实际渲染字体（sans-serif 系）与 canvas 字体栈解析结果不一致，
// canvas 会量窄 ~14%（deepseek-flash 实测：渲染 111px vs canvas 97px），边界名仍会截断；
// 且 placeholder（生效模型名）是异步解析后到的，必须量渲染结果而不是按 props 时序预估。
const rootRef = ref(null)
const pillWidth = ref(props.width)
const PILL_MAX = 300      // 极端长名兜底，防挤爆工具栏
const PILL_CHROME = 40    // 左内边距 12 + 箭头/清除位 24 + 渲染取整缓冲 4
async function measurePill() {
  if (!props.pill) return
  await nextTick()
  const root = rootRef.value && rootRef.value.$el
  const item = root && (root.querySelector('.ant-select-selection-item')
                     || root.querySelector('.ant-select-selection-placeholder'))
  if (!item) return
  const range = document.createRange()
  range.selectNodeContents(item)
  const w = range.getBoundingClientRect().width
  if (!w) return
  // 无下限：宽度严格随文字伸缩（短名收窄、长名放宽），量宽来自渲染后 DOM，不需要防量窄余量
  pillWidth.value = Math.min(PILL_MAX, Math.ceil(w) + PILL_CHROME)
}
watch([() => props.modelValue, () => groups.value, () => props.inheritLabel,
       () => props.placeholder, () => props.pill, () => props.width], measurePill, { immediate: true })
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
  color: var(--app-text3);
}
.ms-empty-tip {
  font-size: 12px;
  color: var(--app-text3);
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
  background: var(--app-panel-2);
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
/* 紧凑形态：模型名超宽即省略，不换行、不撑破工具条。
   padding-right 归零是因为下面已隐藏箭头（compact 时不渲染 suffixIcon）。 */
.ms-compact :deep(.ant-select-selector) { padding-right: 8px !important; }
.ms-compact :deep(.ant-select-selection-item) {
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.ms-pill.ms-has-value :deep(.ant-select-selection-item) {
  color: var(--app-text);
}
/* item 与 placeholder 的右内边距一并归零：selector 的 padding-right 24px 已预留箭头位，
   antd 默认给 placeholder 的 17px 右内边距是重复预留，会额外挤掉 17px 文本宽导致截断 */
.ms-pill :deep(.ant-select-selection-item),
.ms-pill :deep(.ant-select-selection-placeholder) {
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
  /* 宽度自适应内容（dropdownMatchSelectWidth=false），模型名完整展示；仅对极端长名兜底限宽 */
  max-width: min(72vw, 480px) !important;
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
  background: var(--app-panel-2) !important;
}
.ms-dropdown .ant-select-item-option-selected {
  background: transparent !important;
  font-weight: 500 !important;
  color: var(--app-accent) !important;
}
/* 引用失配兜底选项：只参与触发器文案解析，不在下拉里出现 */
.ms-dropdown .ms-orphan {
  display: none !important;
}
</style>

<!-- ==================== 窄屏（≤768px）====================
     触屏的深度思考设置不走「悬浮模型行」，改由聊天页工具条常驻入口点开底部 sheet
     （见 ChatPage 的 .think-entry），因此**不需要**给 option 加点击事件。 -->
<style>
@media (max-width: 768px) {
  /* 72vw 在 375px 屏上只有 270px，供应商分组标题与模型名会被截断 —— 窄屏放开到接近全屏 */
  .ms-dropdown { max-width: calc(100vw - 16px) !important; }
  /* 触控热区：30px 的行在手指上偏小 */
  .ms-dropdown .ant-select-item-option { min-height: 40px !important; line-height: 24px !important; }
}
</style>
