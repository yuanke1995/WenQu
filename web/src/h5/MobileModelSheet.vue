<template>
  <!-- 模型与思考设置 sheet：把 PC「模型下拉 + 悬浮深度思考面板」两处能力合成一块。
       引擎状态经 provide/inject 取（useChatEngine 必须单实例，不能各 sheet 各调一次）。 -->
  <BottomSheet :open="open" title="智能体与模型" :subtitle="currentAgentName" max-height="80dvh" @close="$emit('close')">
    <div class="ms">
      <!-- ---- 智能体 ---- -->
      <div class="ms-section-title">智能体</div>
      <div v-if="agentLocked" class="ms-note">本会话已绑定智能体，切换会开启新会话（人设/知识库/工具集随智能体变）</div>
      <button class="ms-row" :class="{ on: currentAgentId === AUTO_AGENT }" type="button" @click="pickAgentRow(AUTO_AGENT)">
        <span class="ms-ico-fallback">自</span>
        <span class="ms-name">自动派遣</span>
        <span class="ms-sub">按名称与描述挑最合适的</span>
        <check-outlined v-if="currentAgentId === AUTO_AGENT" class="ms-check" />
      </button>
      <button v-if="!hasDefaultAgent" class="ms-row" :class="{ on: !currentAgentId }" type="button" @click="pickAgentRow('')">
        <span class="ms-ico-fallback">默</span>
        <span class="ms-name">默认（全局配置）</span>
        <check-outlined v-if="!currentAgentId" class="ms-check" />
      </button>
      <button v-for="a in agentList" :key="a.id" class="ms-row agent" :class="{ on: currentAgentId === a.id }" type="button" @click="pickAgentRow(a.id)">
        <AgentAvatar :agent="a" :size="20" />
        <span class="ms-name">{{ a.name }}</span>
        <span v-if="a.description" class="ms-sub">{{ a.description }}</span>
        <check-outlined v-if="currentAgentId === a.id" class="ms-check" />
      </button>

      <!-- ---- 模型选择 ---- -->
      <div class="ms-section-title">模型</div>
      <!-- 当前生效模型所属供应商欠费：就地告知（与 PC 选择器的额度标记同一件事），
           不处理的话用户会带着一个必失败的模型继续对话，报错时才第一次知道 -->
      <div v-if="currentBlockedTip" class="ms-note warn"><exclamation-circle-outlined /> {{ currentBlockedTip }}</div>
      <div v-if="loadingModels" class="ms-note">加载中…</div>
      <template v-else>
        <button v-if="userDefaultModel" class="ms-row" :class="{ on: !currentOverrideModel }" type="button" @click="pick('')">
          <span class="ms-ico-fallback">默</span>
          <span class="ms-name">跟随个人默认</span>
          <span class="ms-sub">{{ modelLabelOf(userDefaultModel) }}</span>
          <check-outlined v-if="!currentOverrideModel" class="ms-check" />
        </button>
        <template v-for="g in groups" :key="g.providerId">
          <div class="ms-group">{{ g.name }}<span v-if="g.quotaBlocked" class="ms-quota-flag">额度不足</span></div>
          <button v-for="m in g.models" :key="m.ref" class="ms-row" :class="{ on: currentOverrideModel === m.ref }" type="button" @click="pick(m.ref)">
            <ProviderIcon :icon="m.icon" :name="g.name" :size="18" />
            <span class="ms-name">{{ m.displayName }}</span>
            <span v-if="g.quotaBlocked" class="ms-quota-flag">额度不足</span>
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

      <!-- ---- 上下文压缩（PC 端 /compact 命令的移动入口）---- -->
      <div class="ms-section-title">上下文</div>
      <button class="ms-row" type="button" :disabled="loading || compacting" @click="doCompact">
        <span class="ms-ico-fallback">压</span>
        <span class="ms-name">压缩上下文</span>
        <span class="ms-sub">{{ compacting ? '压缩中…' : '早期对话并入摘要' }}</span>
      </button>
      <div class="ms-note">把较早的对话压缩成摘要，为后续提问腾出空间；完整记录仍可在会话中回看（PC 端也可用 /compact 命令）</div>
    </div>
  </BottomSheet>
</template>

<script setup>
import { computed, inject, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { CheckOutlined, ExclamationCircleOutlined } from '@ant-design/icons-vue'
import BottomSheet from './BottomSheet.vue'
import ProviderIcon from '../components/ProviderIcon.vue'
import AgentAvatar from '../components/AgentAvatar.vue'
import { listAvailableModels } from '../api'
import { THINK_LEVEL_ON } from '../chat/projections'

const props = defineProps({ open: { type: Boolean, default: false } })
const emit = defineEmits(['close'])

const engine = inject('wqChat')
const {
  currentOverrideModel, userDefaultModel, effectiveModel, effectiveModelLabel, loading,
  compacting, compactContext,
  thinkCapsOf, levelOptionsOf, currentLevelOf, deepOnOf, setThinkLevel, modelLabelOf,
  ctxWindowOptionsOf, effectiveCtxWindowOf,
  agentList, AUTO_AGENT, currentAgentId, currentAgentName, hasDefaultAgent, agentLocked, pickAgent
} = engine

// 智能体切换：已绑定会话由引擎按「切换 = 开启新会话」处理并给出提示（与 PC 下拉同一路径）。
// 未锁定时选完即关 sheet（用户接下来多半要打字）；锁定时不关——引擎刚开了新会话，让提示可读。
const pickAgentRow = id => { pickAgent(id); if (!agentLocked.value) emit('close') }

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

// ==================== 额度不足（供应商级，与 PC ModelSelect 同一后端打标） ====================
// 触屏没有 hover，PC 的 title 提示在这里无效——被欠费拦下的行保持可点，
// 点了弹 toast 说明原因与恢复路径，而不是 disabled 死行让用户对「灰的」一头雾水。
const blockedGroupOf = ref => {
  const g = groups.value.find(x => (x.models || []).some(m => m.ref === ref))
  return (g && g.quotaBlocked) ? g : null
}
const blockedTipOf = g => {
  const why = g.quotaMessage ? `（${g.quotaMessage}）` : ''
  return `${g.name}额度不足或套餐已到期${why}。充值或换用其他供应商后即可恢复；若已充值，在电脑端点该供应商的「测试连接」验证一次即解除。`
}
const currentBlockedTip = computed(() => {
  const g = blockedGroupOf(effectiveModel.value)
  return g ? `当前模型所在的「${g.name}」${g.quotaMessage ? '额度不足：' + g.quotaMessage : '额度不足'}，回答会失败，请在下方换用其他模型。` : ''
})

const pick = ref_ => {
  if (ref_) {
    const g = blockedGroupOf(ref_)
    if (g) { message.warning(blockedTipOf(g)); return }
  }
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
// 压缩后关掉 sheet：结果条在聊天页消息流底部，留在 sheet 里看不到反馈（失败/无需压缩的原因也走 toast）
const doCompact = async () => {
  const d = await compactContext()
  if (d) emit('close')
}
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
/* 智能体行：名字限一行（长名截断），描述占余量、右对齐单行省略——否则长名会把描述挤成多行 */
.ms-row.agent .ms-name { flex: none; max-width: 46%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ms-row.agent .ms-sub { flex: 1; min-width: 0; text-align: right; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
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
.ms-note.warn { color: var(--app-warn-text); line-height: 1.6; }
/* 额度不足标：警告色小 pill。红标不可被 ellipsis 吃掉——它就是「为什么选不了」的答案 */
.ms-quota-flag {
  flex: none; font-size: 11px; padding: 1px 7px; border-radius: 999px;
  color: var(--app-warn-text); background: var(--app-warn-weak); border: 1px solid var(--app-warn-border);
}
.ms-group .ms-quota-flag { margin-left: 6px; }
.ms-row .ms-quota-flag { margin-left: auto; }
.ms-row .ms-check + .ms-quota-flag, .ms-row .ms-quota-flag + .ms-check { margin-left: 6px; }
</style>
