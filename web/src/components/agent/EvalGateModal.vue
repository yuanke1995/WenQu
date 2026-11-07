<template>
  <!-- 保存前检查（效果图场景 04）：智能体编辑保存时的轻门禁。
       口径与后端 gate-check 同源：hint 默认给三动作；block 且低于门槛才真正拦保存 -->
  <a-modal :open="open" :width="540" :footer="null" :closable="true" destroy-on-close
           @cancel="emit('update:open', false)">
    <template #title>保存前检查</template>

    <div v-if="info" class="gate-body">
      <!-- 无评测集：轻提示，不拦 -->
      <div v-if="!info.hasSuite" class="gate-status muted">
        <span class="ico">○</span>
        <div>
          <div class="t">尚未建立评测集</div>
          <div class="s">这次保存后不会自动回归。建好评测集后，改动就能看到「变好还是变坏」。</div>
        </div>
      </div>

      <!-- 有变更未评测 -->
      <div v-else-if="gateIs('changed')" class="gate-status warn">
        <span class="ico">!</span>
        <div>
          <div class="t">有变更未评测</div>
          <div class="s">最近一次成功运行基于 v{{ info.lastRun?.agentVersion ?? '?' }}（通过率 {{ pct(info.lastRun?.passRate) }}，门槛 {{ pct(info.thresholdRate) }}）。
            当前编辑内容将保存为 v{{ info.agentVersion + 1 }}，尚未回归。</div>
        </div>
      </div>

      <!-- 低于门槛 -->
      <div v-else-if="gateIs('below')" class="gate-status bad">
        <span class="ico">!</span>
        <div>
          <div class="t">低于门槛</div>
          <div class="s">通过率 {{ pct(info.lastRun?.passRate) }} 低于门槛 {{ pct(info.thresholdRate) }}{{ blocked ? '；门禁模式为阻断，本次保存被拒绝' : '；本次保存仍被允许，建议先修复再发布' }}。</div>
        </div>
      </div>

      <!-- 变更清单 -->
      <div v-if="info.changes?.length" class="change-list">
        <div class="k">本次变更 · {{ info.changes.length }} 处</div>
        <div v-for="(c, i) in info.changes" :key="c.field" class="change-item">
          <span class="num">{{ i + 1 }}</span>
          <span class="t">{{ c.label || c.field }}</span>
          <span class="b">{{ clip(c.from) }} → {{ clip(c.to) }}</span>
        </div>
      </div>
      <div v-else-if="info.hasSuite" class="change-list">
        <div class="k">本次保存没有配置变更（改的是评测集之外的内容）</div>
      </div>

      <!-- 建议动作 -->
      <div v-if="info.hasSuite && info.caseCount" class="last-run">
        <div>
          <div class="t">建议保存后跑一次回归：<b>{{ info.caseCount }} 条用例 · 约 {{ estMinutes }} 分钟 · 预计 {{ info.caseCount * 2 }} 次模型调用</b></div>
          <div class="s">评测默认只读，不会执行需要人工确认的工具。</div>
        </div>
      </div>
    </div>

    <div class="gate-foot">
      <span class="mode-hint">门禁模式：{{ info?.gateMode === 'block' ? '阻断（低于门槛拒绝保存）' : '提示（管理员可配置阻断）' }}</span>
      <span class="sp"></span>
      <template v-if="blocked">
        <a-button @click="emit('update:open', false)">知道了</a-button>
      </template>
      <template v-else>
        <a-button @click="emit('update:open', false)">取消</a-button>
        <a-button :disabled="saving" @click="emit('confirm', 'save')">仍然保存</a-button>
        <a-button type="primary" :loading="saving" :disabled="!info?.hasSuite || !info?.caseCount"
                  @click="emit('confirm', 'saveAndRun')">
          {{ info?.hasSuite ? '保存并运行回归' : '保存' }}
        </a-button>
      </template>
    </div>
  </a-modal>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  /** gate-check 响应：{gate, gateMode, thresholdRate, agentVersion, lastRun, changes, hasSuite, caseCount} */
  info: { type: Object, default: null },
  saving: { type: Boolean, default: false }
})
const emit = defineEmits(['update:open', 'confirm'])

const gateIs = g => props.info?.gate === g
/** block 模式只在「低于门槛」时真正拦保存；有变更未评测始终提示级（plan §3.3） */
const blocked = computed(() => props.info?.gateMode === 'block' && gateIs('below'))
const estMinutes = computed(() => Math.max(1, Math.ceil((props.info?.caseCount || 0) * 3.4 / 60)))
const pct = v => (v == null ? '—' : (Math.round(v) === v ? v.toFixed(0) : v.toFixed(1)) + '%')
/** diff 值展示：空值显示「空」，长文本截断 */
const clip = v => {
  if (v == null || v === '') return '空'
  const s = String(v).replace(/\s+/g, ' ')
  return s.length > 24 ? s.slice(0, 24) + '…' : s
}
</script>

<style scoped>
.gate-body { padding: 2px 0 4px; }
.gate-status { display: flex; gap: 10px; align-items: flex-start; border: 1px solid var(--app-border);
  border-radius: 8px; padding: 10px 12px; }
.gate-status.warn { background: var(--app-warn-weak, #faf3e6); border-color: var(--app-warn-border, #f0dfb6); }
.gate-status.bad { background: var(--app-danger-weak, #fff2f0); border-color: var(--app-danger-border, #ffccc7); }
.gate-status.muted { background: var(--app-panel-2, #fafbfc); }
.gate-status .ico { width: 22px; height: 22px; border-radius: 50%; background: #fff; flex: none;
  display: inline-flex; align-items: center; justify-content: center; font-weight: 700;
  color: var(--app-warn-text, #a3691b); }
.gate-status.bad .ico { color: var(--app-danger-text, #cf1322); }
.gate-status.muted .ico { color: var(--app-text3); }
.gate-status .t { font-size: 12px; font-weight: 600; color: var(--app-text); }
.gate-status.warn .t { color: var(--app-warn-text, #a3691b); }
.gate-status.bad .t { color: var(--app-danger-text, #cf1322); }
.gate-status .s { font-size: 11px; color: var(--app-text2); line-height: 1.6; margin-top: 3px; }

.change-list { margin-top: 12px; }
.change-list .k { font-size: 11px; color: var(--app-text3); margin-bottom: 6px; }
.change-item { display: flex; align-items: center; gap: 8px; padding: 6px 0;
  border-bottom: 1px dashed var(--app-border); font-size: 12px; }
.change-item:last-child { border-bottom: none; }
.change-item .num { width: 18px; height: 18px; border-radius: 4px; background: var(--app-accent-weak);
  color: var(--app-accent); font-size: 10px; font-weight: 600; flex: none;
  display: inline-flex; align-items: center; justify-content: center; }
.change-item .t { color: var(--app-text); font-weight: 500; flex: none; }
.change-item .b { margin-left: auto; color: var(--app-text3); font-size: 11px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.last-run { margin-top: 12px; padding: 10px 12px; border: 1px solid var(--app-border);
  border-radius: 8px; background: var(--app-panel-2, #fafbfc); }
.last-run .t { font-size: 12px; color: var(--app-text2); }
.last-run .t b { color: var(--app-text); }
.last-run .s { font-size: 11px; color: var(--app-text3); margin-top: 3px; }

.gate-foot { display: flex; align-items: center; gap: 8px; margin-top: 14px; padding-top: 12px;
  border-top: 1px solid var(--app-border); }
.mode-hint { font-size: 11px; color: var(--app-text3); }
.gate-foot .sp { flex: 1; }
</style>
