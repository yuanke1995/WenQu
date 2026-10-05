<template>
  <!-- 模型与思考设置 sheet：把 PC「模型下拉 + 悬浮深度思考面板」两处能力合成一块。
       引擎状态经 provide/inject 取（useChatEngine 必须单实例，不能各 sheet 各调一次）。 -->
  <BottomSheet :open="open" title="模型与思考" :subtitle="effectiveModelLabel || '未指定模型'" max-height="80dvh" @close="$emit('close')">
    <div class="ms">
      <!-- ---- 模型选择 ---- -->
      <div class="ms-section-title">模型</div>
      <div v-if="loadingModels" class="ms-note">加载中…</div>
      <template v-else>
        <button v-if="userDefaultModel" class="ms-row" :class="{ on: !currentOverrideModel }" type="button" @click="pick('')">
          <span class="ms-ico-fallback">默</span>
          <span class="ms-name">跟随个人默认</span>
          <span class="ms-sub">{{ modelLabelOf(userDefaultModel) }}</span>
          <check-outlined v-if="!currentOverrideModel" class="ms-check" />
        </button>
        <template v-for="g in groups" :key="g.providerId">
          <div class="ms-group">{{ g.name }}</div>
          <button v-for="m in g.models" :key="m.ref" class="ms-row" :class="{ on: currentOverrideModel === m.ref }" type="button" @click="pick(m.ref)">
            <ProviderIcon :icon="m.icon" :name="g.name" :size="18" />
            <span class="ms-name">{{ m.displayName }}</span>
            <check-outlined v-if="currentOverrideModel === m.ref" class="ms-check" />
          </button>
        </template>
        <div v-if="!groups.length" class="ms-note">暂无可用模型</div>
      </template>

      <!-- ---- 深度思考 ---- -->
      <div class="ms-section-title">深度思考</div>
      <div v-if="!caps.visible" class="ms-note">该模型不支持深度思考。</div>
      <template v-else>
        <div v-if="caps.locked" class="ms-row static">
          <span class="ms-name">恒思考</span>
          <span class="ms-sub">强度由管理员在模型库登记</span>
        </div>
        <template v-else>
          <button class="ms-row" type="button" :disabled="loading" @click="toggleThink">
            <span class="ms-name">开启思考</span>
            <span class="ms-sub">{{ thinkOn ? '回答前先推理，耗时更长' : '直接作答' }}</span>
            <span class="ms-switch" :class="{ on: thinkOn }" />
          </button>
          <div v-if="thinkOn" class="ms-chips">
            <button v-for="o in levelChips" :key="o.value" class="ms-chip" :class="{ on: currentLevel === o.value }"
                    type="button" :disabled="loading" @click="setLevel(o.value)">
              {{ o.label }}
            </button>
          </div>
        </template>
      </template>

      <!-- ---- 上下文窗口档位 ---- -->
      <template v-if="ctxOptions.length">
        <div class="ms-section-title">上下文窗口</div>
        <div class="ms-chips">
          <button v-for="o in ctxOptions" :key="o.value" class="ms-chip" :class="{ on: currentCtx === o.value }"
                  type="button" :disabled="loading" @click="setWindow(o.value)">
            {{ o.label }}
          </button>
        </div>
        <div class="ms-note">窗口越大能带上更多资料与历史，费用也相应增加</div>
      </template>
    </div>
  </BottomSheet>
</template>

<script setup>
import { computed, inject, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { CheckOutlined } from '@ant-design/icons-vue'
import BottomSheet from './BottomSheet.vue'
import ProviderIcon from '../components/ProviderIcon.vue'
import { listAvailableModels } from '../api'
import { THINK_LEVEL_ON } from '../chat/projections'

const props = defineProps({ open: { type: Boolean, default: false } })
defineEmits(['close'])

const engine = inject('wqChat')
const {
  currentOverrideModel, userDefaultModel, effectiveModel, effectiveModelLabel, loading,
  thinkCapsOf, levelOptionsOf, currentLevelOf, deepOnOf, setThinkLevel, modelLabelOf,
  ctxWindowOptionsOf, effectiveCtxWindowOf
} = engine

// 模型下拉数据（与 ModelSelect 同源 /provider/available）：10s 模块级缓存，避免每次开 sheet 都打接口。
// 过滤口径必须带 type=chat（与 PC 的 ModelSelect 一致）：不过滤会把 OCR/向量/重排模型也列成可聊模型。
let cache = { ts: 0, data: [] }
const groups = ref([])
const loadingModels = ref(false)
const load = async () => {
  if (Date.now() - cache.ts < 10000 && cache.data.length) { groups.value = cache.data; return }
  loadingModels.value = true
  try {
    const data = await listAvailableModels('chat')
    groups.value = (Array.isArray(data) ? data : [])
      .map(g => ({ ...g, models: (g.models || []).filter(m => m.type === 'chat') }))
      .filter(g => g.models.length)
    cache = { ts: Date.now(), data: groups.value }
  } catch (e) { groups.value = [] } finally { loadingModels.value = false }
}
watch(() => props.open, v => { if (v) load() })

const pick = ref_ => {
  currentOverrideModel.value = ref_ || ''
  message.success(ref_ ? `已切换为「${modelLabelOf(ref_)}」` : '已跟随个人默认模型')
}

const caps = computed(() => thinkCapsOf(effectiveModel.value))
const levelOptions = computed(() => levelOptionsOf(effectiveModel.value))
// 档位 chips 只列「思考强度」（恒思考锁定与开启/关闭由开关行承担，'off' 不进 chips）
const levelChips = computed(() => levelOptions.value.filter(o => o.value !== 'off' && o.value !== THINK_LEVEL_ON))
const currentLevel = computed(() => currentLevelOf(effectiveModel.value))
const thinkOn = computed(() => deepOnOf(effectiveModel.value))
const ctxOptions = computed(() => ctxWindowOptionsOf(effectiveModel.value))
const currentCtx = computed(() => effectiveCtxWindowOf(effectiveModel.value))

const toggleThink = () => {
  if (loading.value) return
  if (thinkOn.value) { setThinkLevel('off'); return }
  const first = levelChips.value[0]
  setThinkLevel(first ? first.value : THINK_LEVEL_ON)
}
const setLevel = v => setThinkLevel(v)
const setWindow = v => engine.setCtxWindow(v)
</script>

<style scoped>
.ms { display: flex; flex-direction: column; gap: 4px; }
.ms-section-title { font-size: 12px; color: var(--app-text3); padding: 12px 2px 4px; }
.ms-group { font-size: 12px; color: var(--app-text3); padding: 8px 2px 2px; }
.ms-row {
  display: flex; align-items: center; gap: 10px; width: 100%;
  min-height: 46px; padding: 8px 10px; border: 1px solid transparent; border-radius: 10px;
  background: transparent; color: var(--app-text); text-align: left; touch-action: manipulation;
}
.ms-row.static { cursor: default; }
.ms-row.on { background: var(--app-accent-weak); border-color: var(--app-accent-border); }
.ms-row:disabled { opacity: .55; }
.ms-ico-fallback {
  width: 18px; height: 18px; flex: none; border-radius: 5px; background: var(--app-panel-2);
  color: var(--app-text3); font-size: 11px; display: inline-flex; align-items: center; justify-content: center;
}
.ms-name { font-size: 14px; }
.ms-sub { font-size: 12px; color: var(--app-text3); margin-left: auto; }
.ms-check { color: var(--app-accent); flex: none; margin-left: 6px; }
.ms-switch {
  margin-left: auto; flex: none; width: 42px; height: 24px; border-radius: 999px;
  background: var(--app-border-strong); position: relative; transition: background .18s;
}
.ms-switch::after {
  content: ''; position: absolute; top: 2px; left: 2px; width: 20px; height: 20px; border-radius: 50%;
  background: #fff; transition: transform .18s; box-shadow: 0 1px 3px rgba(0, 0, 0, .2);
}
.ms-switch.on { background: var(--app-accent); }
.ms-switch.on::after { transform: translateX(18px); }
@media (prefers-reduced-motion: reduce) { .ms-switch, .ms-switch::after { transition: none; } }
.ms-chips { display: flex; flex-wrap: wrap; gap: 8px; padding: 4px 2px 8px; }
.ms-chip {
  min-height: 36px; padding: 0 14px; border: 1px solid var(--app-border); border-radius: 999px;
  background: var(--app-panel); color: var(--app-text2); font-size: 13px; touch-action: manipulation;
}
.ms-chip.on { background: var(--app-accent-weak); border-color: var(--app-accent-border); color: var(--app-accent); font-weight: 500; }
.ms-chip:disabled { opacity: .55; }
.ms-note { font-size: 12px; color: var(--app-text3); padding: 4px 2px 8px; }
</style>
