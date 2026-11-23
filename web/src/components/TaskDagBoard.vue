<template>
  <div class="dag-wrap">
    <div class="dag-canvas" :style="{ width: layout.width + 'px', height: layout.height + 'px' }">
      <svg class="dag-edges" :width="layout.width" :height="layout.height">
        <defs>
          <marker id="dag-ar" markerWidth="9" markerHeight="9" refX="7" refY="3" orient="auto">
            <path d="M0,0 L7,3 L0,6" fill="none" stroke="#b9c0c9" stroke-width="1.4"/>
          </marker>
          <marker id="dag-ar-dark" markerWidth="9" markerHeight="9" refX="7" refY="3" orient="auto">
            <path d="M0,0 L7,3 L0,6" fill="none" stroke="#8f98a3" stroke-width="1.4"/>
          </marker>
        </defs>
        <path
          v-for="(e, i) in edgePaths" :key="i"
          :d="e.d" fill="none"
          :stroke="e.done ? '#8f98a3' : '#cfd5dc'"
          :stroke-width="e.done ? 1.7 : 1.5"
          :stroke-dasharray="e.done ? '0' : '4 3'"
          :marker-end="e.done ? 'url(#dag-ar-dark)' : 'url(#dag-ar)'"
        />
      </svg>
      <div
        v-for="n in positioned" :key="n.id"
        class="dag-node" :class="[stateClass(n.state), { sel: n.id === selectedId }]"
        :style="{ left: n.x + 'px', top: n.y + 'px', width: NODE_W + 'px', height: NODE_H + 'px' }"
        @click="select(n)"
      >
        <span class="dn-chip" :class="stateClass(n.state)">{{ stateLabel(n.state) }}</span>
        <div class="dn-title" :title="n.subject">{{ n.subject }}</div>
        <div class="dn-meta">
          <span class="dn-owner">{{ n.ownerName || '未认领' }}</span>
          <span v-if="n.blockedCount > 0" class="dn-dep">待前置 {{ n.blockedCount }}</span>
        </div>
      </div>
      <div v-if="!nodes.length" class="dag-empty">还没有任务。复杂协作可让模型先建任务节点。</div>
    </div>

    <aside class="dag-side">
      <template v-if="selected">
        <div class="ds-head">
          <div class="ds-title">{{ selected.subject }}</div>
          <div v-if="selected.description" class="ds-desc">{{ selected.description }}</div>
          <div class="ds-meta">
            <span>认领人：{{ selected.ownerName || '未认领' }}</span>
            <span v-if="selected.blockedCount > 0">待前置：{{ selected.blockedCount }}</span>
          </div>
        </div>
        <div class="ds-sec">事件时间线</div>
        <div v-if="!events.length" class="ds-empty">{{ loadingEvents ? '加载中…' : '暂无事件' }}</div>
        <div v-else class="ds-tl">
          <div v-for="(e, i) in events" :key="i" class="ds-ev">
            <i class="ds-dot"></i>
            <div>
              <div class="ds-kind">{{ eventLabel(e.kind) }}</div>
              <div class="ds-at">{{ e.at }}</div>
              <div v-if="e.detail" class="ds-detail">{{ e.detail }}</div>
            </div>
          </div>
        </div>
      </template>
      <div v-else class="ds-placeholder">点左侧任一节点，看它的状态、认领人与事件时间线。</div>
    </aside>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import * as api from '../api'

const props = defineProps({
  sessionId: { type: String, default: '' },
  nodes: { type: Array, default: () => [] },
  edges: { type: Array, default: () => [] }
})

const NODE_W = 196
const NODE_H = 64
const COL_GAP = 92
const ROW_GAP = 24
const PAD = 26

const selectedId = ref('')
const events = ref([])
const loadingEvents = ref(false)

function stateClass(s) {
  if (s === 'COMPLETED') return 'done'
  if (s === 'IN_PROGRESS') return 'prog'
  if (s === 'READY') return 'ready'
  if (s === 'FAILED') return 'fail'
  return 'wait'
}
function stateLabel(s) {
  return ({ PENDING: '待前置', READY: '可认领', IN_PROGRESS: '执行中', COMPLETED: '完成', FAILED: '失败', CANCELLED: '已取消' })[s] || s
}
function eventLabel(kind) {
  return ({ created: '创建任务', depended: '登记依赖', ready: '进入就绪', blocked: '等待前置', claimed: '认领', completed: '完成', failed: '失败', cancelled: '取消' })[kind] || kind
}

// 分层：最长路径定 level，得到从左到右的 DAG 列
const levels = computed(() => {
  const ids = new Set(props.nodes.map(n => n.id))
  const lv = new Map(props.nodes.map(n => [n.id, 0]))
  let guard = 0, changed = true
  while (changed && guard++ < props.nodes.length + 2) {
    changed = false
    for (const e of props.edges) {
      if (!ids.has(e.from) || !ids.has(e.to)) continue
      const nl = (lv.get(e.from) || 0) + 1
      if (nl > (lv.get(e.to) || 0)) { lv.set(e.to, nl); changed = true }
    }
  }
  return lv
})

const positioned = computed(() => {
  const byLevel = new Map()
  for (const n of props.nodes) {
    const l = levels.value.get(n.id) || 0
    if (!byLevel.has(l)) byLevel.set(l, [])
    byLevel.get(l).push(n)
  }
  const out = []
  for (const [l, arr] of [...byLevel.entries()].sort((a, b) => a[0] - b[0])) {
    arr.forEach((n, i) => {
      out.push({ ...n, x: PAD + l * (NODE_W + COL_GAP), y: PAD + i * (NODE_H + ROW_GAP), _lvl: l })
    })
  }
  return out
})

const posMap = computed(() => {
  const m = new Map()
  for (const n of positioned.value) m.set(n.id, n)
  return m
})

const edgePaths = computed(() => {
  const out = []
  for (const e of props.edges) {
    const a = posMap.value.get(e.from)
    const b = posMap.value.get(e.to)
    if (!a || !b) continue
    const x1 = a.x + NODE_W, y1 = a.y + NODE_H / 2
    const x2 = b.x, y2 = b.y + NODE_H / 2
    const mx = (x1 + x2) / 2
    out.push({
      d: `M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}`,
      done: a.state === 'COMPLETED'
    })
  }
  return out
})

const layout = computed(() => {
  let maxLvl = 0, maxRows = 1
  const counts = new Map()
  for (const n of props.nodes) {
    const l = levels.value.get(n.id) || 0
    maxLvl = Math.max(maxLvl, l)
    const c = (counts.get(l) || 0) + 1
    counts.set(l, c)
    maxRows = Math.max(maxRows, c)
  }
  const cols = props.nodes.length ? maxLvl + 1 : 1
  return {
    width: PAD * 2 + cols * NODE_W + (cols - 1) * COL_GAP,
    height: PAD * 2 + maxRows * NODE_H + (maxRows - 1) * ROW_GAP
  }
})

const selected = computed(() => props.nodes.find(n => n.id === selectedId.value) || null)

async function select(n) {
  selectedId.value = n.id
  events.value = []
  loadingEvents.value = true
  try {
    const res = await api.getTeamTaskEvents(props.sessionId, n.id)
    events.value = Array.isArray(res) ? res : []
  } catch (e) { events.value = [] } finally { loadingEvents.value = false }
}

// 节点列表变化后，若当前选中已不存在则清空
watch(() => props.nodes, (list) => {
  if (selectedId.value && !list.some(n => n.id === selectedId.value)) {
    selectedId.value = ''
    events.value = []
  }
})
</script>

<style scoped>
.dag-wrap{display:flex;gap:16px;align-items:stretch;min-height:360px}
.dag-canvas{position:relative;flex:1;min-width:0;overflow:auto;border:1px solid var(--border,#e6e9ed);border-radius:12px;background:radial-gradient(circle at 1px 1px,#eceef1 1px,transparent 0) 0 0/18px 18px,var(--panel,#fff)}
.dag-edges{position:absolute;left:0;top:0;pointer-events:none}
.dag-node{position:absolute;border-radius:11px;background:var(--panel,#fff);border:1px solid var(--border,#e6e9ed);box-shadow:0 1px 2px rgba(16,24,40,.04);padding:9px 12px 9px 13px;display:flex;flex-direction:column;justify-content:center;gap:3px;cursor:pointer;transition:box-shadow .15s,transform .05s}
.dag-node:hover{box-shadow:0 4px 14px rgba(16,24,40,.10)}
.dag-node:before{content:"";position:absolute;left:0;top:9px;bottom:9px;width:3px;border-radius:3px}
.dag-node.done:before{background:#3b8f4e}
.dag-node.prog:before{background:#2e6be6}
.dag-node.ready:before{background:#a3691b}
.dag-node.fail:before{background:#cf1322}
.dag-node.wait:before{background:#d8dde3}
.dag-node.sel{box-shadow:0 0 0 2px #ddd6fe,0 4px 14px rgba(124,58,237,.16)}
.dn-chip{position:absolute;top:8px;right:8px;height:16px;padding:0 6px;border-radius:4px;font-size:9px;font-weight:650;display:inline-flex;align-items:center;background:#f2f4f7;color:#98a0aa}
.dn-chip.done{background:#eaf5ec;color:#3b8f4e}
.dn-chip.prog{background:#eef4ff;color:#2e6be6}
.dn-chip.ready{background:#faf3e6;color:#a3691b}
.dn-chip.fail{background:#fff2f0;color:#cf1322}
.dn-title{font-size:12px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;padding-right:46px}
.dn-meta{display:flex;gap:8px;font-size:10px;color:#98a0aa}
.dn-dep{color:#a3691b}
.dag-empty{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font-size:12px;color:#98a0aa}
.dag-side{width:300px;flex:none;border:1px solid var(--border,#e6e9ed);border-radius:12px;background:var(--panel,#fff);padding:14px;overflow:auto}
.ds-head{border-bottom:1px solid var(--border,#eef0f3);padding-bottom:12px;margin-bottom:12px}
.ds-title{font-size:14px;font-weight:700;margin-top:8px;line-height:1.4}
.ds-desc{font-size:12px;color:#5f6570;margin-top:6px;line-height:1.6}
.ds-meta{display:flex;flex-direction:column;gap:3px;margin-top:10px;font-size:11px;color:#98a0aa}
.ds-sec{font-size:11px;font-weight:700;color:#98a0aa;margin-bottom:10px;letter-spacing:.03em}
.ds-empty,.ds-placeholder{font-size:12px;color:#98a0aa;line-height:1.7}
.ds-placeholder{padding:20px 6px;text-align:center}
.ds-tl{display:flex;flex-direction:column;gap:12px;border-left:1px solid #eef0f3;padding-left:14px;margin-left:3px}
.ds-ev{position:relative}
.ds-dot{position:absolute;left:-19px;top:4px;width:8px;height:8px;border-radius:50%;background:#7c3aed}
.ds-kind{font-size:12px;font-weight:650}
.ds-at{font-size:10.5px;color:#98a0aa}
.ds-detail{font-size:11.5px;color:#5f6570;margin-top:2px}
</style>
