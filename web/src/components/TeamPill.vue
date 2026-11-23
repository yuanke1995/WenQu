<template>
  <button
    v-if="sessionId && enabled"
    class="tp-pill"
    :class="{ on: enabled, off: !enabled }"
    type="button"
    :title="enabled ? '智能体团队：点击查看成员 / 任务 / 消息' : '开启智能体团队'"
    @click="$emit('open')"
  >
    <span class="tp-dot" :class="{ run: running }"></span>
    <svg class="tp-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
      <circle cx="9" cy="8" r="3"/><path d="M3 20c0-3 2.7-5 6-5s6 2 6 5"/><circle cx="17" cy="9" r="2"/><path d="M16 20c0-2 1.5-3.5 3.5-3.5S23 18 23 20"/>
    </svg>
    <span class="tp-txt">{{ enabled ? (count ? `团队 · ${count}` : '团队') : '团队' }}</span>
  </button>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import * as api from '../api'

const props = defineProps({ sessionId: { type: String, default: '' } })
defineEmits(['open'])

const enabled = ref(false)
const count = ref(0)
const running = ref(false)

async function load() {
  if (!props.sessionId) return
  try {
    const on = await api.getTeamEnabled(props.sessionId)
    enabled.value = !!(on && on.enabled)
  } catch (e) { enabled.value = false }
  if (!enabled.value) { count.value = 0; running.value = false; return }
  try {
    const res = await api.getTeamMembers(props.sessionId)
    const list = Array.isArray(res) ? res : []
    count.value = list.length
    running.value = list.some(m => m.state === 'RUNNING' || m.state === 'WAITING')
  } catch (e) { /* 忽略 */ }
}

watch(() => props.sessionId, () => load(), { immediate: true })
let timer = null
onMounted(() => { load(); timer = window.setInterval(load, 6000) })
onUnmounted(() => { if (timer) window.clearInterval(timer) })
</script>

<style scoped>
.tp-pill{display:inline-flex;align-items:center;gap:6px;height:28px;padding:0 11px;border-radius:999px;font-size:12px;font-weight:650;line-height:1;transition:background .15s,border-color .15s,color .15s}
.tp-pill.on{border:1px solid #ddd6fe;background:#f5f3ff;color:#7c3aed}
.tp-pill.off{border:1px solid var(--border,#e6e9ed);background:transparent;color:var(--text-2,#5f6570);font-weight:600}
.tp-pill.off:hover{border-color:#ddd6fe;background:#f5f3ff;color:#7c3aed}
.tp-dot{width:6px;height:6px;border-radius:50%;flex:none;background:#c8ced6}
.tp-pill.on .tp-dot{background:#3b8f4e}
.tp-dot.run{background:#3b8f4e;box-shadow:0 0 0 3px rgba(59,143,78,.16)}
.tp-pill.off .tp-dot{background:#c8ced6}
.tp-ico{width:13px;height:13px;flex:none}
.tp-txt{white-space:nowrap}
</style>
