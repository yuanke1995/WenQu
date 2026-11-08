<template>
  <!-- 终态卡：挂在「设为目标」那条用户回声下（按 goalId 认），刷新后由历史重建。
       完成/未完成/取消都留一张，说清「留下了什么」；动作只保留回看与后续选择。 -->
  <div class="gt-card" :class="'gt-' + tone">
    <div class="gt-head">
      <span class="gt-ic"><check-outlined v-if="tone === 'achieved'" /><exclamation-circle-outlined v-else-if="tone === 'failed'" /><stop-outlined v-else /></span>
      <span class="gt-title">{{ title }}</span>
      <span v-if="version" class="gt-ver">{{ version }}</span>
      <span class="gt-state">{{ stateLabel }}</span>
    </div>
    <div v-if="note" class="gt-note">{{ note }}</div>
    <div class="gt-actions">
      <button type="button" class="gt-btn" @click="$emit('view')">查看过程</button>
      <button v-if="canEdit" type="button" class="gt-btn" @click="$emit('edit')">修改目标</button>
      <button v-else type="button" class="gt-btn" @click="$emit('restart')">重新设定目标</button>
      <button type="button" class="gt-btn quiet" @click="$emit('delete')">删除记录</button>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { CheckOutlined, ExclamationCircleOutlined, StopOutlined } from '@ant-design/icons-vue'
import { goalStateLabelOf } from '../chat/projections'

const props = defineProps({
  goal: { type: Object, required: true },
  title: { type: String, default: '' },
  note: { type: String, default: '' },
  version: { type: String, default: '' }
})
defineEmits(['view', 'edit', 'restart', 'delete'])

const tone = computed(() => {
  const s = props.goal && props.goal.status
  if (s === 'ACHIEVED') return 'achieved'
  if (s === 'FAILED') return 'failed'
  return 'cancelled'
})
const stateLabel = computed(() => goalStateLabelOf(props.goal))
const canEdit = computed(() => props.goal && props.goal.status === 'FAILED')
</script>

<style scoped>
/* 挂在用户气泡下沿，宽度跟着气泡走；配色比正文安静一档，不与「正在回答」的正文抢注意力 */
.gt-card {
  width: min(520px, 100%);
  box-sizing: border-box;
  margin-top: 6px;
  padding: 10px 12px;
  border: 1px solid var(--app-border);
  border-radius: 10px;
  background: var(--app-panel-2);
  display: flex; flex-direction: column; gap: 6px;
  font-size: 12px; line-height: 18px;
}
.gt-head { display: flex; align-items: center; gap: 6px; min-width: 0; }
.gt-ic { display: inline-flex; font-size: 14px; flex: none; }
.gt-title { flex: none; font-size: 13px; font-weight: 600; color: var(--app-text); }
.gt-ver { flex: none; font-size: 11px; line-height: 16px; padding: 0 5px; border-radius: 5px; background: var(--app-panel-3, var(--app-panel)); color: var(--app-text3); }
.gt-state { flex: none; margin-left: auto; font-size: 11px; line-height: 18px; padding: 0 6px; border-radius: 6px; color: var(--app-text3); background: var(--app-panel); }
.gt-note { color: var(--app-text3); word-break: break-word; }
.gt-actions { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 2px; }
.gt-btn {
  height: 26px; padding: 0 10px; border-radius: 7px;
  border: 1px solid var(--app-border); background: transparent;
  font-size: 12px; color: var(--app-text2); cursor: pointer;
}
.gt-btn:hover { color: var(--app-accent); border-color: var(--app-accent); background: var(--app-accent-weak); }
.gt-btn.quiet { color: var(--app-text3); border-color: transparent; }
.gt-btn.quiet:hover { color: var(--app-danger); background: var(--app-danger-weak); border-color: transparent; }
.gt-achieved .gt-ic { color: var(--app-success, #067647); }
.gt-failed .gt-ic { color: var(--app-danger); }
.gt-cancelled .gt-ic { color: var(--app-text3); }
.gt-achieved .gt-state { color: var(--app-success, #067647); }
.gt-failed .gt-state { color: var(--app-danger); }
</style>
