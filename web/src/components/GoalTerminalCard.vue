<template>
  <!-- 终态卡：挂在「深度执行」那条用户回声下（按 goalId 认），刷新后由历史重建。
       完成/未完成/取消都留一张，说清「留下了什么」；动作只保留回看与后续选择。 -->
  <div class="gt-card" :class="'gt-' + tone">
    <div class="gt-head">
      <span class="gt-ic"><check-outlined v-if="tone === 'achieved'" /><exclamation-circle-outlined v-else-if="tone === 'failed'" /><stop-outlined v-else /></span>
      <span class="gt-title">{{ title }}</span>
      <span v-if="version" class="gt-ver">{{ version }}</span>
      <span v-if="meta" class="gt-meta">{{ meta }}</span>
    </div>
    <div v-if="note" class="gt-note">{{ note }}</div>
    <div class="gt-actions">
      <button v-if="canEdit" type="button" class="gt-btn" @click="$emit('edit')">修改任务</button>
      <button v-else type="button" class="gt-btn" @click="$emit('restart')">重新设定任务</button>
      <button type="button" class="gt-btn" @click="$emit('view')">查看过程</button>
      <button v-if="hasDelivery" type="button" class="gt-btn" @click="$emit('delivery')">查看交付</button>
      <button type="button" class="gt-btn quiet" @click="$emit('delete')">删除记录</button>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { CheckOutlined, ExclamationCircleOutlined, StopOutlined } from '@ant-design/icons-vue'


const props = defineProps({
  goal: { type: Object, required: true },
  title: { type: String, default: '' },
  note: { type: String, default: '' },
  version: { type: String, default: '' },
  /** 这个目标有产出交付包：多给一颗「查看交付」（没有产出的目标不给死入口） */
  hasDelivery: { type: Boolean, default: false }
})
defineEmits(['view', 'delivery', 'edit', 'restart', 'delete'])

const tone = computed(() => {
  const s = props.goal && props.goal.status
  if (s === 'ACHIEVED') return 'achieved'
  if (s === 'FAILED') return 'failed'
  return 'cancelled'
})
const canEdit = computed(() => props.goal && props.goal.status === 'FAILED')
const fmtDuration = ms => {
  const s = Math.round((Number(ms) || 0) / 1000)
  if (s <= 0) return ''
  if (s < 60) return s + ' 秒'
  const m = Math.floor(s / 60)
  if (m < 60) return m + ' 分' + (s % 60 ? ' ' + (s % 60) + ' 秒' : '')
  return Math.floor(m / 60) + ' 小时' + (m % 60 ? ' ' + (m % 60) + ' 分' : '')
}
/** 终态卡右上角：共跑了几轮、用了多久；两个数都没有就不占位 */
const meta = computed(() => {
  const g = props.goal || {}
  const b = g.budget || {}
  const rounds = Number(b.rounds) || Number(g.round) || 0
  const dur = fmtDuration(b.ms)
  return [rounds > 0 ? `共 ${rounds} 轮` : '', dur ? `用时 ${dur}` : ''].filter(Boolean).join(' · ')
})
</script>

<style scoped>
/* 挂在用户气泡下沿、右对齐；配色比正文安静一档，不与「正在回答」的正文抢注意力。
   版式对齐 goal-mode-ux：标题 + 轮次/用时 + 一句留痕说明 + 底部文字操作。 */
.gt-card {
  width: min(560px, 100%);
  box-sizing: border-box;
  margin: 8px 0 0 auto;
  border: 1px solid var(--app-border);
  border-radius: 10px;
  background: var(--app-panel);
  display: flex; flex-direction: column;
  font-size: 12px; line-height: 18px;
  overflow: hidden;
}
.gt-head { display: flex; align-items: center; gap: 7px; min-width: 0; padding: 9px 12px; font-size: 12px; }
.gt-ic { display: inline-flex; font-size: 14px; flex: none; color: var(--app-text3); }
.gt-title { flex: none; font-size: 12.5px; font-weight: 600; color: var(--app-text); }
.gt-ver { flex: none; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 9.5px; line-height: 16px; padding: 0 5px; border-radius: 4px; border: 1px solid var(--app-border); color: var(--app-text3); }
.gt-meta { margin-left: auto; flex: none; font-size: 10.5px; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.gt-state { flex: none; font-size: 10.5px; line-height: 18px; padding: 0 6px; border-radius: 6px; color: var(--app-text3); background: var(--app-panel-2); }
.gt-note { padding: 0 12px 8px; color: var(--app-text2); line-height: 1.65; word-break: break-word; }
.gt-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 8px 12px; border-top: 1px dashed var(--app-border); background: var(--app-panel-2); }
.gt-btn {
  height: auto; padding: 0; border: none; background: none;
  font-size: 11px; color: var(--app-accent); cursor: pointer;
}
.gt-btn:hover { text-decoration: underline; }
.gt-btn.quiet { color: var(--app-text3); margin-left: auto; }
.gt-btn.quiet:hover { color: var(--app-danger); }
.gt-achieved { border-color: var(--app-ok-border, #cfe4d3); }
.gt-achieved .gt-ic { color: var(--app-ok); }
.gt-achieved .gt-state { color: var(--app-ok); background: var(--app-ok-weak); }
.gt-failed { border-color: var(--app-danger-border, #efc8c0); }
.gt-failed .gt-ic { color: var(--app-danger); }
.gt-failed .gt-state { color: var(--app-danger); background: var(--app-danger-weak); }
.gt-cancelled .gt-ic { color: var(--app-text3); }
</style>
