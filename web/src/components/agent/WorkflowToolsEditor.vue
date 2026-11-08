<template>
  <div class="wte">
    <div v-if="!published.length" class="wte-empty">
      {{ loading ? '正在加载工作流…' : '还没有已发布的工作流 —— 先到工作流画布创建并发布，再回来勾选' }}
    </div>
    <div v-for="w in published" :key="w.id" class="wte-row">
      <span class="wte-dot"></span>
      <div class="wte-main">
        <div class="wte-name">{{ w.name }}
          <span class="wte-badge">v{{ w.publishedVersion }}</span>
          <span v-if="isChecked(w.id)" class="wte-badge on">已挂载</span>
        </div>
        <div class="wte-sub">{{ inputSummary(w) }}</div>
      </div>
      <div class="wte-acts">
        <button v-if="!isChecked(w.id)" type="button" class="app-btn ghost small"
                :disabled="modelValue.length >= MAX" @click="add(w)">挂载</button>
        <button v-else type="button" class="app-link-btn danger" @click="remove(w.id)">移除</button>
      </div>
    </div>
    <div v-if="modelValue.length" class="wte-hint">
      已挂 {{ modelValue.length }}/{{ MAX }} 个；工具名 = wf_工作流名_短id，模型在对话中自主决定何时运行。
      引用的工作流下线/删除后自动不再挂载，无需清理这里。
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { listWorkflows } from '../../api'

// 已发布工作流 → 对话工具的勾选编辑器（对外能力第二批）：与 HttpToolsEditor 同一从属形态——
// v-model 是工作流 id 数组（保存整包提交，后端归一 + 存在性/发布态校验）。
const props = defineProps({ modelValue: { type: Array, default: () => [] } })
const emit = defineEmits(['update:modelValue'])

const MAX = 8
const loading = ref(false)
const mine = ref([])

const published = computed(() => (mine.value || []).filter(w => w && w.status === 'published'))
const isChecked = id => (props.modelValue || []).includes(id)

const add = w => {
  if (isChecked(w.id) || props.modelValue.length >= MAX) return
  emit('update:modelValue', [...props.modelValue, w.id])
}
const remove = id => emit('update:modelValue', props.modelValue.filter(x => x !== id))

// 入参摘要：publishedDsl 是发布时的 DSL 快照（字符串），前端解析出 start 入参——
// 挂载前让用户看到「这个工具要传什么」，与画布入参定义同一份事实源
const inputSummary = w => {
  let inputs = []
  try {
    const dsl = typeof w.publishedDsl === 'string' ? JSON.parse(w.publishedDsl) : w.publishedDsl
    const start = (dsl && dsl.nodes || []).find(n => n && n.type === 'start')
    inputs = (start && start.config && start.config.inputs || []).filter(p => p && p.key)
  } catch { /* 解析不出就给通用摘要 */ }
  if (!inputs.length) return '无入参 · 直接运行'
  return '入参：' + inputs.map(p => p.key + (p.required ? '*' : '')).join('、')
}

onMounted(async () => {
  loading.value = true
  try {
    const r = await listWorkflows()
    mine.value = ((r && r.data) || {}).mine || []
  } catch { /* 列表失败保持空态，可重开面板重试 */ }
  loading.value = false
})
</script>

<style scoped>
.wte { display: flex; flex-direction: column; gap: 6px; }
.wte-empty { color: var(--text-3, #999); font-size: 12px; padding: 6px 0; }
.wte-row { display: flex; align-items: center; gap: 8px; padding: 6px 8px;
  border: 1px solid var(--border-1, #eee); border-radius: 8px; }
.wte-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--success, #52c41a); flex: none; }
.wte-main { flex: 1; min-width: 0; }
.wte-name { font-size: 13px; font-weight: 600; display: flex; align-items: center; gap: 6px; }
.wte-sub { font-size: 12px; color: var(--text-3, #999); margin-top: 2px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wte-badge { font-size: 11px; font-weight: 400; color: var(--text-3, #999);
  border: 1px solid var(--border-1, #e5e5e5); border-radius: 999px; padding: 0 6px; }
.wte-badge.on { color: var(--primary, #1677ff); border-color: currentColor; }
.wte-acts { flex: none; }
.wte-hint { font-size: 12px; color: var(--text-3, #999); }
</style>
