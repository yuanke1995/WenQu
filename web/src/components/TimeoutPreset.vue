<template>
  <a-form-item label="耐心档位">
    <div class="tp-row">
      <a-radio-group :value="matched" size="small" button-style="solid" @change="onPick">
        <a-radio-button v-for="t in TIERS" :key="t.key" :value="t.key">{{ t.label }}</a-radio-button>
      </a-radio-group>
      <!-- 当前组合对不上任何档位时只读地标一个「自定义」，不提供第四种可选模式 -->
      <span v-if="!matched" class="tp-custom">自定义</span>
      <span class="tp-now">{{ nowText }}</span>
    </div>
    <div class="tp-hint">{{ matched ? current.hint : customHint }}</div>
  </a-form-item>
</template>

<script setup>
import { computed } from 'vue'

/**
 * 「耐心档位」：一次写齐四个超时，避免管理员逐个改时配出互相打架的组合
 * （典型：提问等待调到 30 分钟，回答超时还是 5 分钟——靠后端「人工等待不计入机器预算」的台账兜着，
 *  但两个数字摆在一起仍然自相矛盾）。
 *
 * <p>这不是第四种模式，只是一个批量填值控件：选完仍可在下方单独微调任一项，
 * 填出的组合对不上档位时显示「自定义」。档位值须与 schema 的 def/presets 上限保持一致，
 * 前端 buildPayload 只认 schema 字段，多写的键不会提交。
 */
const props = defineProps({
  form: { type: Object, required: true }
})

/** 档位覆盖的四个键：短键 → 中文单字（用于「当前组合」摘要） */
const FIELDS = [
  ['sseTimeoutMs', '回答'],
  ['approvalTimeoutMs', '审批'],
  ['askTimeoutMs', '提问'],
  ['planTimeoutMs', '计划']
]

const TIERS = [
  {
    key: 'standard',
    label: '标准',
    v: { sseTimeoutMs: 300000, approvalTimeoutMs: 120000, askTimeoutMs: 600000, planTimeoutMs: 600000 },
    hint: '日常问答与轻智能体；与历史默认一致，升级后不动就是这一档。'
  },
  {
    key: 'long',
    label: '长任务',
    v: { sseTimeoutMs: 600000, approvalTimeoutMs: 300000, askTimeoutMs: 1800000, planTimeoutMs: 1800000 },
    hint: '深度思考、多轮工具调用、需要读一读再批。'
  },
  {
    key: 'unattended',
    label: '无人值守',
    v: { sseTimeoutMs: 1800000, approvalTimeoutMs: 600000, askTimeoutMs: 3600000, planTimeoutMs: 3600000 },
    hint: '挂机跑长流程；没人答的卡片最多等一小时再按「未作答」继续。'
  }
]

const chat = computed(() => props.form?.chat || {})

/** 四项全部命中才算某档；只比数值，不比顺序 */
const matched = computed(() => {
  const c = chat.value
  const tier = TIERS.find(t => Object.entries(t.v).every(([k, ms]) => Number(c[k]) === ms))
  return tier ? tier.key : ''
})
const current = computed(() => TIERS.find(t => t.key === matched.value) || null)

const fmt = ms => {
  if (!Number.isFinite(ms) || ms <= 0) return '—'
  const minutes = ms / 60000
  return `${Number.isInteger(minutes) ? minutes : minutes.toFixed(1)} 分钟`
}

const nowText = computed(() => FIELDS.map(([k, label]) => `${label} ${fmt(Number(chat.value[k]))}`).join(' · '))

const customHint = computed(() => `当前是自定义组合：${nowText.value}。四项互不约束，单项改完容易和其余三项打架，建议先选一档再微调。`)

const onPick = e => {
  const tier = TIERS.find(t => t.key === e?.target?.value)
  if (!tier || !props.form?.chat) return
  for (const [k, ms] of Object.entries(tier.v)) props.form.chat[k] = ms
}
</script>

<style scoped>
.tp-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.tp-now { font-size: 12px; color: var(--app-text3); }
.tp-custom {
  font-size: 12px; line-height: 20px; padding: 1px 8px; border-radius: 999px;
  color: var(--app-text2); background: var(--app-panel-2); border: 1px solid var(--app-border);
}
.tp-hint { margin-top: 6px; font-size: 12px; line-height: 1.7; color: var(--app-text3); }
</style>
