<template>
  <a-select
    ref="rootRef"
    :value="modelValue || undefined"
    :style="{ width: compact ? '104px' : (pill ? pillWidth + 'px' : (typeof width === 'string' ? width : (width || 320) + 'px')) }"
    :placeholder="displayPlaceholder"
    :disabled="disabled"
    :loading="loading"
    :allow-clear="allowClear"
    :class="{ 'ms-pill': pill, 'ms-has-value': !!modelValue, 'ms-compact': compact, 'ms-has-issue': !!issue }"

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
    <a-select-option v-if="inheritLabel && !pill" value="" :label="inheritText">
      <span class="ms-inherit">{{ inheritText }}</span>
    </a-select-option>
    <!-- 引用失配兜底：值不在当前列表（模型/供应商登记变化导致）时，antd 触发器会原样显示
         含供应商 UUID 的引用串；补一个隐藏选项让触发器只显示模型 id 部分。
         带 issue（后端判这个引用已经不能用）时这一项要<b>现身</b>并标灰禁用：
         用户点开面板看见的是「我选的那个在这儿，但它已经停了」，而不是列表里凭空少了自己那一项 -->
    <a-select-option
      v-if="orphanRef"
      :value="orphanRef"
      :label="orphanLabelWithIssue"
      :disabled="!!issue"
      :class="issue ? 'ms-dead' : 'ms-orphan'"
    />
    <a-select-opt-group v-for="g in groups" :key="g.providerId" :label="g.name">
      <!-- 行悬浮事件挂 option 上：rc-select 会把 option 的额外事件铺到行根元素（含行内边距区），
           统一转成 option-hover(ref, 行元素) 透传，父层据此按悬浮行弹 per-item 面板（聊天页深度思考） -->
      <a-select-option
        v-for="m in g.models"
        :key="m.ref"
        :value="m.ref"
        :label="m.displayName"
        :disabled="m.quotaBlocked"
        @mouseenter="emitOptionHover(m.ref, $event)"
        @mouseleave="emitOptionHover('', $event)"
      />
    </a-select-opt-group>
    <!-- 下拉行自绘（品牌图标+名称，图标按 value 反查 groups）；触发器只显示 label 纯文本 -->
    <template #option="opt">
      <span v-if="opt.value === ''" class="ms-inherit">{{ opt.label }}</span>
      <span v-else-if="!opt.value" class="ms-group-text">{{ opt.label }}</span>
      <span v-else class="ms-option" :class="{ 'ms-option-blocked': blockedOf(opt.value), 'ms-option-dead': deadOf(opt.value) }">
        <ProviderIcon :icon="iconOf(opt.value)" :name="providerNameOf(opt.value)" :size="16" />
        <span class="ms-name">{{ deadOf(opt.value) ? orphanLabel : opt.label }}</span>
        <!-- 额度不足标记：就地告知「这个选不了、为什么选不了」，而不是等点了才报错。
             原因用 title 原生提示而非 a-tooltip：disabled 的 option 在 rc-select 里可能收不到
             鼠标事件，包一层 tooltip 反而会跟着一起失效，用户就只剩下「灰的」没有解释。 -->
        <span v-if="blockedOf(opt.value)" class="ms-quota-flag" :title="blockedTip(opt.value)">额度不足</span>
        <span v-else-if="deadOf(opt.value)" class="ms-issue-flag" :title="issueTip">{{ issue }}</span>
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
import { listAvailableModels, getUserPreference } from '../api.js'
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
  /** 类型过滤：单值（chat / vision / ocr / embedding / rerank）或逗号分隔多值（如 "vision,ocr"）（空=全部）；
   *  期望 chat 时全模态 omni 一并入选（全模态本质是对话模型） */
  type: { type: String, default: 'chat' },
  /** 「跟随全局」选项：label 用作未指定时触发器显示的文案（如当前生效模型名），下拉项固定显示「跟随全局」 */
  inheritLabel: { type: String, default: '' },
  /**
   * 空值（跟随）时后端实际用哪一个：'personalDefault' = 本人 /user/preference 的 defaultModel。
   * 传了它，「跟随个人默认模型」就会被点名成「跟随个人默认模型（deepseek-chat）」——
   * 不传点名的话，用户看着五个字「跟随个人默认」只能自己去翻个人设置，或者干脆显式选一个
   * （显式选完就不再跟随个人默认了，这是会留下覆盖的）。
   */
  inheritResolve: { type: String, default: '' },
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
  /**
   * 当前值已经不能用的原因（后端判定后由上层下发：空=可用）。非空时：
   * 触发器变红并带上这句原因，下拉里那一项现身、标灰、点不动。
   * 为什么不让组件自己判：启停状态在供应商与模型登记两张表上，前端只能看到「可用清单里没有它」，
   * 那既可能是停用也可能是还没加载完——判不准就会把好的模型标成坏的。
   */
  issue: { type: String, default: '' },
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
  // 期望含 vision 时放宽口径：具备图片理解能力的模型（visionCapable，「聊天+视觉」一体登记形态）同样入选；
  // 期望含 chat 时放行 omni（全模态本质是对话模型，仅多带视觉/音频）——与后端 available / referenceMatchesType 同口径
  const set = new Set(props.type.split(',').map(s => s.trim()).filter(Boolean))
  if (!set.size) return data
  const wantVision = set.has('vision')
  const wantChat = set.has('chat')
  return data
    .map(g => ({ ...g, models: g.models.filter(m =>
      set.has(m.type) || (wantChat && m.type === 'omni') || (wantVision && m.visionCapable)) }))
    .filter(g => g.models.length)
}

function onChange(v) {
  // 清空时 antd 返回 undefined，统一归一为 ''（=未指定/继承）
  emit('update:modelValue', v || '')
  emit('change', v || '')
}

// ==================== 「跟随」点名 ====================
// 个人默认模型（同页多个选择器共享一次请求）：known=false 表示还没取到或取失败——
// 此时宁可只说「跟随个人默认模型」，也不猜一个名字上去（猜错的表现是用户以为换了就真换了）。
// 带 TTL：个人设置页改完默认模型后，别处的「跟随…」不能一直挂着旧名字（与模型清单同一条口径）
let prefCache = { ts: 0, known: false, ref: '' }
const PREF_TTL = 10000
const personalDefault = ref(prefCache)
async function loadPersonalDefault(force = false) {
  if (!force && prefCache.known && Date.now() - prefCache.ts < PREF_TTL) { personalDefault.value = prefCache; return }
  try {
    prefCache = { ts: Date.now(), known: true, ref: (await getUserPreference()).data?.defaultModel || '' }
  } catch (e) {
    // 取不到保持 known=false：文案退回不点名，且下次挂载还会再试（不缓存一次失败）
    prefCache = { ts: 0, known: false, ref: '' }
  }
  personalDefault.value = prefCache
}
/** 引用 → 展示名（清单里查不到就取模型 id 那一段，与 orphan 兜底同一条口径） */
function refDisplayName(r) {
  for (const g of groups.value) {
    const m = g.models.find(x => x.ref === r)
    if (m) return m.displayName || r
  }
  return r.includes('/') ? r.slice(r.indexOf('/') + 1) : r
}
/** 继承项的完整文案：点名留空时实际生效的那个模型 */
const inheritText = computed(() => {
  const base = props.inheritLabel
  if (!base || props.inheritResolve !== 'personalDefault') return base
  const p = personalDefault.value
  if (!p.known) return base
  return `${base}（${p.ref ? refDisplayName(p.ref) : '未设置'}）`
})

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
/** 失效态的触发器文案：模型名后面直接带原因，用户不点开面板也知道这一轮发不出去 */
const orphanLabelWithIssue = computed(() => orphanLabel.value + (props.issue ? ' ' + props.issue : ''))
/** 这一项是不是「当前钉着但已经不能用」的那一行 */
function deadOf(ref) {
  return !!props.issue && !!orphanRef.value && ref === orphanRef.value
}
const issueTip = computed(() => props.issue
  ? `这个模型${props.issue}。换一个就能继续问。`
  : '')
/**
 * 占位文案同样要报失效：值留空（跟随个人默认）而那个默认已经不能用时，
 * 触发器显示的是占位串——它可能是含供应商 UUID 的原始引用，直接抛给用户看不懂。
 * 没给显式 placeholder 时，留空态显示的是「跟随…」那句（带点名）而不是「选择模型」——
 * 空在这里是合法选项，说「选择模型」等于把「留空=跟随」这件事藏掉了。
 */
const displayPlaceholder = computed(() => {
  const base = props.placeholder || inheritText.value || '选择模型'
  if (!props.issue || (props.modelValue || '').trim()) return base
  const i = base.indexOf('/')
  return (i === -1 ? base : base.slice(i + 1)) + ' ' + props.issue
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

// ==================== 额度不足标记（引路标志） ====================
/**
 * 该模型所属供应商是否额度不足。
 * 后端按供应商级打标（余额/配额是网关账户级的事，一个模型欠费意味着同一 Key 下全部模型都调不通），
 * 因此这里查分组头即可，不必逐模型找。
 */
function blockedOf(ref) {
  const g = groups.value.find(x => x.models.some(m => m.ref === ref))
  return !!(g && g.quotaBlocked)
}
/** 悬浮说明：为什么选不了 + 怎么恢复（不写「联系管理员」——供应商是本人登记的，只能自己处理） */
function blockedTip(ref) {
  const g = groups.value.find(x => x.models.some(m => m.ref === ref))
  const why = (g && g.quotaMessage) ? `（${g.quotaMessage}）` : ''
  return `${(g && g.name) || '该供应商'}额度不足或套餐已到期${why}。充值或换用其他供应商后即可重新选择；`
    + '若已充值，点该供应商的「测试连接」验证一下就会恢复可选。'
}

onMounted(() => {
  load()
  if (props.inheritResolve) loadPersonalDefault()
})

// 同一实例被切到不同类型槽位时（如个人设置聊天↔视觉共用一个选择器）按缓存重过滤，
// 否则新槽位展示的还是上一个类型的模型列表
watch(() => props.type, () => {
  if (cache.data.length) groups.value = filter(cache.data)
})

defineExpose({ refresh: () => { load(true); if (props.inheritResolve) loadPersonalDefault(true) } })

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
watch([() => props.modelValue, () => groups.value, inheritText,
       () => props.placeholder, () => props.pill, () => props.width], measurePill, { immediate: true })
</script>

<style scoped>
.ms-option {
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 100%;
}
/* 模型名可压缩、红标不可压：额度标记是「为什么选不了」的答案，被 ellipsis 吃掉就白做了 */
.ms-option .ms-name {
  min-width: 0;
  flex: 1 1 auto;
}
.ms-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ms-inherit {
  color: #888;
}
/* 额度不足行：整行压暗 + 「额度不足」红标。压暗用 opacity 而非改色，
   保证品牌图标与文字都一起变淡（antd disabled 已会给底色，这里只补视觉层次） */
.ms-option-blocked {
  opacity: 0.55;
}
/* 已失效的那一项（当前会话/个人默认钉着但已经不能用）：同样压暗，红标说的是原因 */
.ms-option-dead {
  opacity: 0.55;
}
.ms-issue-flag {
  flex: none;
  margin-left: auto;
  padding: 0 6px;
  border-radius: 999px;
  font-size: 11px;
  line-height: 17px;
  color: var(--app-danger-text);
  background: var(--app-danger-weak);
  cursor: help;
}
/* 触发器进入失效态：文字与占位都提红，扫一眼就知道这一轮发不出去（红标在文案里自带原因） */
.ms-has-issue :deep(.ant-select-selection-item),
.ms-has-issue :deep(.ant-select-selection-placeholder) {
  color: var(--app-danger-text) !important;
}
.ms-quota-flag {
  flex: none;
  margin-left: auto;
  padding: 0 6px;
  border-radius: 999px;
  font-size: 11px;
  line-height: 17px;
  color: var(--app-danger-text);
  background: var(--app-danger-weak);
  cursor: help;
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
/* 悬浮显示手型：show-search 会在 selector 里注入搜索输入框，antd 默认给文本光标（I 形），
   胶囊是点击打开下拉的按钮语义，统一强制 pointer（含内部 input 与 item/placeholder） */
.ms-pill,
.ms-pill :deep(.ant-select-selector),
.ms-pill :deep(.ant-select-selection-search-input) {
  cursor: pointer !important;
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
/* 选中行：不加底色（antd 默认的灰底会被这两条盖掉），蓝字+加重即选中标记 */
.ms-dropdown .ant-select-item-option-selected {
  background: transparent !important;
  font-weight: 500 !important;
  color: var(--app-accent) !important;
}
/* 行悬浮/键盘导航高亮：必须排在 selected 之后——悬浮中的行同时带 selected+active 两个类，
   同权重 !important 按源序取胜，active 在后才能让悬浮底盖过 selected 的透明底。
   底色用别名层第三级 --app-bg-layer-3（悬浮/选中底）：--app-panel-2（近白）在纯白
   面板上只暗 ~2%，观感是一层擦不干净的脏影子而非高亮（用户反馈过），这里要清晰可辨 */
.ms-dropdown .ant-select-item-option-active {
  background: var(--app-bg-layer-3) !important;
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
