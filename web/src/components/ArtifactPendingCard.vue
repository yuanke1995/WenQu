<template>
  <!--
    产物生成态：只在本轮「还没有产物、模型正在生成/交付文件」时出现，完成后由真实交付卡原位接管。
    数据来自现有 tool_status 的 preparing/chars/brief，不新增后端事件。
    刻意不画百分比：服务端只有入参字符数，没有字段级进度，画比例就是编。
  -->
  <div v-if="visible" class="apc">
    <div class="apc-main">
      <div class="apc-type"><file-text-outlined /></div>
      <div class="apc-info">
        <div class="apc-title">{{ title }}</div>
        <div class="apc-bar" aria-hidden="true"><i /></div>
        <div class="apc-sub">{{ sub }}</div>
      </div>
      <span class="apc-pulse"><i />生成中</span>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { FileTextOutlined } from '@ant-design/icons-vue'

const props = defineProps({
  /** artifactPendingOf(m) 的产出：{ kind: 'generate'|'deliver', filename, chars } */
  pending: { type: Object, default: null }
})

const visible = ref(false)
let timer = 0

const fmtChars = n => {
  const v = Number(n) || 0
  if (v >= 10000) return (v / 10000).toFixed(1) + ' 万'
  return v.toLocaleString('zh-CN')
}

const title = computed(() => {
  const p = props.pending
  if (!p) return ''
  if (p.filename) return p.filename
  return p.kind === 'deliver' ? '正在交付文件' : '正在生成交付文件'
})

const sub = computed(() => {
  const p = props.pending
  if (!p) return ''
  if (p.kind === 'deliver') return '正在存进会话，完成后可预览与下载'
  if (p.chars > 0) return '正在生成交付文件 · 已接收 ' + fmtChars(p.chars) + ' 字'
  return '正在生成交付文件'
})

// 短任务不闪：挂载后 180ms 仍在生成才显示
watch(() => props.pending, p => {
  if (!p) {
    if (timer) { clearTimeout(timer); timer = 0 }
    visible.value = false
    return
  }
  if (visible.value || timer) return
  timer = setTimeout(() => {
    timer = 0
    if (props.pending) visible.value = true
  }, 180)
}, { immediate: true })

onBeforeUnmount(() => { if (timer) clearTimeout(timer) })
</script>

<style scoped>
.apc {
  margin-top: 9px; max-width: 640px;
  border: 1px solid var(--app-border);
  border-radius: 14px;
  background: var(--app-panel);
  overflow: hidden;
  animation: apc-in .18s cubic-bezier(.2, .7, .3, 1) both;
}
.apc-main { display: flex; align-items: flex-start; gap: 11px; padding: 12px 13px; min-height: 84px; }
.apc-type {
  width: 38px; height: 38px; flex: none; border-radius: 9px;
  display: flex; align-items: center; justify-content: center;
  color: var(--app-accent); background: var(--app-accent-weak);
  border: 1px solid var(--app-accent-border);
  font-size: 16px;
}
.apc-info { flex: 1; min-width: 0; }
.apc-title {
  font-size: 12.5px; font-weight: 600; line-height: 1.5;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.apc-bar {
  margin-top: 7px; height: 4px; border-radius: 99px; overflow: hidden;
  background: var(--app-panel-2); border: 1px solid var(--app-border);
}
.apc-bar i {
  display: block; width: 40%; height: 100%; border-radius: 99px;
  background: var(--app-accent);
  animation: apc-slide 1.15s cubic-bezier(.4, 0, .2, 1) infinite;
}
.apc-sub { margin-top: 6px; font-size: 11px; line-height: 1.55; color: var(--app-text2); }
.apc-pulse {
  flex: none; display: inline-flex; align-items: center; gap: 5px;
  font-size: 10px; color: var(--app-accent); background: var(--app-accent-weak);
  border: 1px solid var(--app-accent-border); border-radius: 999px; padding: 2px 8px;
}
.apc-pulse i { width: 5px; height: 5px; border-radius: 50%; background: var(--app-accent); animation: apc-pulse 1.1s ease-in-out infinite; }

@keyframes apc-in { from { opacity: 0; transform: translateY(5px); } to { opacity: 1; transform: translateY(0); } }
@keyframes apc-slide { from { transform: translateX(-110%); } to { transform: translateX(360%); } }
@keyframes apc-pulse { 0%, 100% { opacity: .3; transform: scale(.85); } 50% { opacity: 1; transform: scale(1); } }

@media (prefers-reduced-motion: reduce) {
  .apc { animation: none; }
  .apc-bar i, .apc-pulse i { animation: none; }
  .apc-bar i { width: 100%; opacity: .45; }
}
@media (max-width: 768px) {
  .apc { max-width: 100%; }
}
</style>
