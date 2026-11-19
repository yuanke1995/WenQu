<template>
  <div class="bp-panel rp-card">
    <div v-if="!snapshots.length" class="bp-empty">本轮还没有浏览器操作</div>
    <template v-else>
      <!-- 顶部：当前 URL（只读）+ 运行环境徽标 + 时间 + 放大按钮 -->
      <div class="bp-head">
        <div class="bp-url" :title="current.url || ''">{{ current.url || '—' }}</div>
        <div class="bp-meta">
          <span class="bp-runtime">{{ runtimeLabel }}</span>
          <span class="bp-time">{{ timeOf(current.ts) }}</span>
          <button type="button" class="bp-expand" title="放大到全屏查看" @click="expanded = true">全屏</button>
        </div>
        <button type="button" class="bp-open" :disabled="!current.url" @click="openCurrent">在新窗口打开</button>
      </div>
      <!-- 快照列表：到达顺序倒序（最新的排最前），仅最新一条默认展开，点时间行开合 -->
      <div class="bp-list">
        <div v-for="(s, i) in snapshots" :key="s && (s.turnId || '') + '|' + (s && s.ts) + '|' + i" class="bp-item">
          <button type="button" class="bp-item-head" :title="s.text ? (opened[i] ? '收起快照' : '展开快照') : ''" @click="toggle(i)">
            <span class="bp-caret" :class="{ open: opened[i] }">›</span>
            <span class="bp-item-time">{{ timeOf(s.ts) }}</span>
            <span v-if="i === 0" class="bp-new">最新</span>
          </button>
          <pre v-if="opened[i]" class="bp-text">{{ s.text || '' }}</pre>
        </div>
      </div>
    </template>
  </div>

  <!-- 全屏只读浮层：侧栏太窄看不清长快照时铺开看，仍然不能导航/点击回灌模型 -->
  <Teleport to="body">
    <div v-if="expanded && snapshots.length" class="bp-fs" role="dialog" aria-label="浏览器快照全屏查看" @click.self="expanded = false">
      <div class="bp-fs-inner">
        <div class="bp-fs-head">
          <div class="bp-fs-url" :title="current.url || ''">{{ current.url || '—' }}</div>
          <div class="bp-fs-actions">
            <span class="bp-runtime">{{ runtimeLabel }}</span>
            <button type="button" class="bp-open" :disabled="!current.url" @click="openCurrent">在新窗口打开</button>
            <button type="button" class="bp-fs-close" title="关闭全屏" @click="expanded = false">✕</button>
          </div>
        </div>
        <div class="bp-fs-list">
          <div v-for="(s, i) in snapshots" :key="'fs|' + (s.turnId || '') + '|' + (s && s.ts) + '|' + i" class="bp-item">
            <button type="button" class="bp-item-head" @click="toggleFs(i)">
              <span class="bp-caret" :class="{ open: fsOpened[i] }">›</span>
              <span class="bp-item-time">{{ timeOf(s.ts) }}</span>
              <span v-if="i === 0" class="bp-new">最新</span>
            </button>
            <pre v-if="fsOpened[i]" class="bp-text bp-text-fs">{{ s.text || '' }}</pre>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'

const props = defineProps({
  snapshots: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false }
})

// 每条快照的开合态按下标存：收到新快照时整体后移一位，原「最新」仍保持展开
const opened = reactive({})
const fsOpened = reactive({})
watch(() => props.snapshots.length, n => {
  if (!n) return
  const next = {}
  for (let i = 0; i < n; i++) next[i] = i === 0 || opened[i - 1] === true
  for (const k of Object.keys(opened)) delete opened[k]
  Object.assign(opened, next)
  // 全屏默认也只展开最新一条
  for (const k of Object.keys(fsOpened)) delete fsOpened[k]
  fsOpened[0] = true
}, { immediate: true })

const expanded = ref(false)
const current = computed(() => props.snapshots[0] || {})
const runtimeLabel = computed(() => {
  const r = current.value.runtime
  if (typeof r === 'string' && r.startsWith('local-')) return '本机'
  if (r === 'cloud') return '云端'
  return r || '—'
})
const timeOf = ts => {
  const n = Number(ts)
  if (!Number.isFinite(n) || n <= 0) return ''
  try { return new Date(n).toLocaleTimeString('zh-CN') } catch (e) { return '' }
}
const toggle = i => { opened[i] = !opened[i] }
const toggleFs = i => { fsOpened[i] = !fsOpened[i] }
// 只读镜像：这里不回灌任何导航给模型，唯一出口是新窗口打开当前 URL
const openCurrent = () => {
  const url = current.value.url
  if (url) window.open(url, '_blank', 'noopener')
}
</script>

<style scoped>
.bp-panel { padding: 10px 12px; }
.bp-empty { padding: 24px 10px; text-align: center; font-size: 11.5px; color: var(--app-text3); }
.bp-head { display: flex; flex-direction: column; gap: 5px; padding-bottom: 8px; border-bottom: 1px solid var(--app-border); }
.bp-url { font-size: 12px; color: var(--app-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.bp-meta { display: flex; align-items: center; gap: 8px; font-size: 10.5px; color: var(--app-text3); }
.bp-runtime { padding: 1px 7px; border-radius: 9px; border: 1px solid var(--app-border); background: var(--app-panel); color: var(--app-accent); }
.bp-expand { margin-left: auto; background: transparent; border: 1px solid var(--app-border); border-radius: 7px;
  padding: 3px 9px; font: inherit; font-size: 11px; color: var(--app-accent); cursor: pointer; }
.bp-expand:hover { border-color: var(--app-accent); background: var(--app-panel); }
.bp-open { align-self: flex-start; background: transparent; border: 1px solid var(--app-border); border-radius: 7px;
  padding: 3px 9px; font: inherit; font-size: 11px; color: var(--app-accent); cursor: pointer; }
.bp-open:hover:not(:disabled) { border-color: var(--app-accent); background: var(--app-panel); }
.bp-open:disabled { opacity: .5; cursor: not-allowed; }
.bp-list { margin-top: 6px; max-height: 460px; overflow-y: auto; }
.bp-item { padding: 5px 0; border-top: 1px solid var(--app-border); }
.bp-item:first-child { border-top: 0; }
.bp-item-head { display: flex; align-items: center; gap: 6px; width: 100%; padding: 0; border: 0; background: transparent;
  text-align: left; cursor: pointer; font: inherit; font-size: 11px; color: var(--app-text3); }
.bp-caret { font-size: 13px; line-height: 1; transition: transform .15s; }
.bp-caret.open { transform: rotate(90deg); }
.bp-item-time { font-variant-numeric: tabular-nums; }
.bp-new { margin-left: auto; color: var(--app-accent); font-size: 10px; }
.bp-text { margin: 5px 0 0; padding: 8px 9px; border-radius: 7px; background: var(--app-panel); color: var(--app-text);
  font: inherit; font-size: 11.5px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }

/* 全屏浮层 */
.bp-fs { position: fixed; inset: 0; z-index: 1000; background: rgba(0, 0, 0, .42);
  display: flex; align-items: center; justify-content: center; padding: 24px; }
.bp-fs-inner { width: min(920px, 100%); max-height: 86vh; display: flex; flex-direction: column;
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 14px;
  box-shadow: var(--app-shadow-lg); padding: 16px 18px; overflow: hidden; }
.bp-fs-head { display: flex; align-items: center; gap: 12px; padding-bottom: 12px; border-bottom: 1px solid var(--app-border); }
.bp-fs-url { flex: 1; min-width: 0; font-size: 13px; color: var(--app-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.bp-fs-actions { display: flex; align-items: center; gap: 8px; flex: none; }
.bp-fs-close { background: transparent; border: 0; cursor: pointer; font-size: 16px; color: var(--app-text3); padding: 2px 6px; }
.bp-fs-close:hover { color: var(--app-text); }
.bp-fs-list { margin-top: 10px; overflow-y: auto; }
.bp-text-fs { font-size: 13px; line-height: 1.7; background: var(--app-panel-2); }
@media (max-width: 768px) {
  .bp-fs { padding: 8px; }
  .bp-fs-inner { max-height: 92dvh; padding: 12px 14px; }
}
</style>
