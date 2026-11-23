<template>
  <div class="team-panel">
    <!-- 头部 -->
    <header class="tp-head">
      <div class="tp-head-l">
        <span class="tp-ic">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="9" cy="8" r="3"/><path d="M3 20c0-3 2.7-5 6-5s6 2 6 5"/><circle cx="17" cy="9" r="2"/><path d="M16 20c0-2 1.5-3.5 3.5-3.5S23 18 23 20"/>
          </svg>
        </span>
        <div>
          <div class="tp-title">智能体团队</div>
          <div class="tp-sub">本会话派出、可被消息唤醒的成员</div>
        </div>
      </div>
      <a-switch :checked="enabled" size="small" @change="onToggle" />
    </header>

    <div v-if="!enabled" class="tp-off">
      <div class="tp-off-ic">◎</div>
      <div class="tp-off-t">团队未开启</div>
      <div class="tp-off-s">开启后，本会话派出的子助手会变成可继续、可发消息的成员</div>
      <button class="tp-off-btn" type="button" @click="onToggle(true)">开启智能体团队</button>
    </div>

    <template v-else>
      <!-- 团队成员 -->
      <section class="card">
        <div class="card-hd">
          <span class="card-t">团队成员</span>
          <span class="card-meta">{{ summary }}</span>
        </div>
        <div class="mem-list">
          <!-- 根：就是当前对话本身——用真实智能体名与会话标题，不写死、不猜状态 -->
          <div class="mem-row lead">
            <AgentAvatar v-if="leadAgent" :agent="leadAgent" :size="30" /><span v-else class="avatar lead">{{ (agentName || '问渠').slice(0, 1) }}</span>
            <div class="mem-main">
              <div class="mem-top"><span class="mem-name" :title="agentName || 'lead'">{{ agentName || 'lead' }}</span><span class="tag-root">根</span></div>
              <div v-if="sessionTitle" class="mem-sub" :title="sessionTitle">{{ sessionTitle }}</div>
            </div>
          </div>

          <div v-if="loading && !members.length" class="hint">加载中…</div>
          <div v-else-if="!members.length" class="hint">还没有成员，让助手「委派」一位试试</div>

          <div v-else class="mem-tree">
            <div v-for="row in memberRows" :key="row.m.sessionId" class="mem-row clickable nested"
                 :class="{ deep: row.depth > 1 }"
                 :style="row.depth > 1 ? { paddingLeft: (8 + (row.depth - 1) * 16) + 'px' } : null"
                 @click="openMember(row.m)">
              <AgentAvatar v-if="agentOf(row.m)" :agent="agentOf(row.m)" :size="30" /><span v-else class="avatar" :style="{ background: avatarBg(row.color) }">{{ avatarText(row.m) }}</span>
              <div class="mem-main">
                <div class="mem-top">
                  <span class="mem-name" :title="displayName(row.m)">{{ displayName(row.m) }}</span>
                  <span v-if="codeName(row.m)" class="tag-code">{{ codeName(row.m) }}</span>
                </div>
                <div v-if="subText(row.m)" class="mem-sub" :title="subText(row.m)">{{ subText(row.m) }}</div>
              </div>
              <span class="mem-state" :class="statusClass(row.m.state)"><i class="d"></i>{{ statusLabel(row.m.state) }}</span>
              <div class="mem-acts" @click.stop>
                <button class="iact" type="button" title="进入会话发消息" @click="openMember(row.m)">
                  <svg viewBox="0 0 24 24"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/><path d="M8 9h8M8 13h5"/></svg>
                </button>
                <button v-if="busy(row.m.state)" class="iact danger" type="button" title="叫停该成员当前轮" @click="interrupt(row.m)">
                  <svg viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>
                </button>
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- 任务图 -->
      <section class="card">
        <div class="card-hd">
          <span class="card-t">任务图</span>
          <span class="card-meta">{{ tasks.nodes.length }} 项 · {{ tasks.edges.length }} 依赖</span>
        </div>
        <div v-if="!tasks.nodes.length" class="hint">还没有任务。复杂协作可让模型拆成有依赖的节点</div>
        <div v-else class="task-list">
          <div v-for="n in tasks.nodes" :key="n.id" class="task-row clickable" @click="openTask(n)">
            <span class="tbox" :class="taskClass(n.state)">{{ taskMark(n.state) }}</span>
            <span class="task-name" :title="n.subject">{{ n.subject }}</span>
            <span class="task-state" :class="taskClass(n.state)">{{ taskLabel(n.state) }}</span>
          </div>
        </div>
        <div v-if="tasks.nodes.length" class="card-ft">
          <span class="link" @click="openTaskCanvas">展开画布</span>
        </div>
      </section>

      <!-- 团队动态：成员消息 + 任务变化 合并成一条时间线 -->
      <section class="card">
        <div class="card-hd">
          <span class="card-t">团队动态</span>
          <span class="card-meta">最近 {{ feed.length }} 条</span>
        </div>
        <div v-if="!feed.length" class="hint">还没有动态</div>
        <div v-else class="feed-list">
          <div v-for="it in feed" :key="it.key" class="feed">
            <span class="feed-tag" :class="it.kind">{{ it.kind === 'msg' ? '消息' : '任务' }}</span>
            <div class="feed-body">
              <div class="feed-line">
                <b v-if="it.kind === 'msg'">{{ it.from }} → {{ it.to }}</b>
                <b v-else>{{ it.subject }}</b>
                <span class="feed-time">{{ relTime(it.at) }}</span>
              </div>
              <div class="feed-text">{{ it.kind === 'msg' ? it.text : (it.detail || eventLabel(it.ekind)) }}</div>
            </div>
          </div>
        </div>
      </section>
    </template>

    <!-- 任务时间线 -->
    <a-modal v-model:open="taskOpen" :title="taskDetail.subject || '任务'" :footer="null" width="560px">
      <div class="task-detail">
        <div class="td-top">
          <span class="task-state" :class="taskClass(taskDetail.state)">{{ taskLabel(taskDetail.state) }}</span>
          <span class="td-owner">{{ taskDetail.ownerName || '未认领' }}</span>
        </div>
        <div v-if="taskDetail.description" class="td-desc">{{ taskDetail.description }}</div>
        <div class="td-sec">事件时间线</div>
        <div v-if="!taskEvents.length" class="hint">暂无事件</div>
        <div v-else class="td-tl">
          <div v-for="(e, i) in taskEvents" :key="i" class="td-ev">
            <i class="td-dot"></i>
            <div>
              <div class="td-kind">{{ eventLabel(e.kind) }}</div>
              <div class="td-at">{{ e.at }}</div>
              <div v-if="e.detail" class="td-detail">{{ e.detail }}</div>
            </div>
          </div>
        </div>
      </div>
    </a-modal>

    <!-- 任务图全画布 -->
    <a-modal v-model:open="canvasOpen" title="任务图 · 本会话" :footer="null" width="min(1120px, 94vw)" :body-style="{ padding: '14px' }">
      <TaskDagBoard :session-id="sessionId" :nodes="tasks.nodes" :edges="tasks.edges" />
    </a-modal>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import * as api from '../api'
import TaskDagBoard from './TaskDagBoard.vue'
import AgentAvatar from './AgentAvatar.vue'

const props = defineProps({
  sessionId: { type: String, default: '' },
  sessionTitle: { type: String, default: '' },
  agentName: { type: String, default: '' },
  agent: { type: Object, default: null },
  agents: { type: Array, default: () => [] }
})
const router = useRouter()

const enabled = ref(false)
const members = ref([])
const tasks = ref({ nodes: [], edges: [] })
const messages = ref([])
const feed = ref([])
const loading = ref(false)

const taskOpen = ref(false)
const taskDetail = ref({})
const taskEvents = ref([])
const canvasOpen = ref(false)

const AV = [
  'linear-gradient(135deg,#8B7CF6,#6D28D9)',
  'linear-gradient(135deg,#22B8A6,#0E7490)',
  'linear-gradient(135deg,#F5A442,#C2761B)',
  'linear-gradient(135deg,#5E93F5,#2A5FE0)',
  'linear-gradient(135deg,#EC6F9E,#B02A5B)'
]
function avatarBg(i) { return AV[i % AV.length] }
// 按 agentId / agentName 解析真实智能体对象，交给 AgentAvatar 渲染真实 logo
function agentOf(m) {
  const list = props.agents || []
  if (!list.length || !m) return null
  return list.find(a => a.id === m.agentId) || list.find(a => a.name === m.agentName) || null
}
const leadAgent = computed(() => props.agent || (props.agents || []).find(a => a.name === props.agentName) || null)

function realName(m) {
  const raw = (m.name && m.name !== m.sessionId) ? m.name
    : (m.agentName && m.agentName !== m.sessionId ? m.agentName : '')
  if (!raw) return ''
  const s = String(raw).trim()
  if (!s) return ''
  return s.includes('/') ? s.slice(s.lastIndexOf('/') + 1) : s
}
function memberNo(m) {
  const idx = members.value.findIndex(x => x.sessionId === m.sessionId)
  return idx >= 0 ? idx + 1 : 0
}
function displayName(m) {
  const rn = realName(m)
  if (rn) return rn
  const no = memberNo(m)
  return no ? `成员 ${no}` : '成员'
}
// 成员代号（reviewer/retriever…）只有在与显示名不同且是代码样式时才显示，避免「取数助手 取数助手」这种重复
function codeName(m) {
  const code = (m.memberName || '').trim()
  if (!code || !/^[a-zA-Z][a-zA-Z0-9\-/]*$/.test(code)) return ''
  if (code.toLowerCase() === displayName(m).toLowerCase()) return ''
  if ((m.agentName || '').toLowerCase() === code.toLowerCase()) return ''
  return code
}
// 副标题：只在能提供「职责」信息、且不与名字重复时显示
function subText(m) {
  const r = (m.role || '').trim()
  const nm = displayName(m)
  if (!r) return ''
  if (r === nm || r.includes(nm)) return ''
  return r
}
function avatarText(m) {
  const rn = realName(m)
  if (rn) return rn.slice(0, 1).toUpperCase()
  const no = memberNo(m)
  return no ? String(no) : '员'
}
function busy(s) { return s === 'RUNNING' || s === 'WAITING' }
function statusClass(s) {
  if (s === 'RUNNING') return 'run'
  if (s === 'WAITING') return 'wait'
  if (s === 'STOPPED') return 'stop'
  return 'idle'
}
function statusLabel(s) {
  return ({ RUNNING: '执行中', WAITING: '等消息', IDLE: '空闲', STOPPED: '已停止', OFFLINE: '离线' })[s] || '空闲'
}
function taskClass(s) {
  if (s === 'COMPLETED') return 'done'
  if (s === 'IN_PROGRESS') return 'prog'
  if (s === 'READY') return 'ready'
  if (s === 'FAILED') return 'fail'
  return 'wait'
}
function taskMark(s) {
  return ({ COMPLETED: '✓', IN_PROGRESS: '…', FAILED: '×' })[s] || '·'
}
function taskLabel(s) {
  return ({ PENDING: '待前置', READY: '可认领', IN_PROGRESS: '执行中', COMPLETED: '完成', FAILED: '失败', CANCELLED: '已取消' })[s] || s
}
function eventLabel(kind) {
  return ({ created: '创建任务', depended: '登记依赖', ready: '进入就绪', blocked: '等待前置', claimed: '认领', completed: '完成', failed: '失败', cancelled: '取消' })[kind] || kind
}
function relTime(at) {
  if (!at) return ''
  const t = Date.parse(at.replace(' ', 'T'))
  if (Number.isNaN(t)) return ''
  const s = Math.max(0, Math.floor((Date.now() - t) / 1000))
  if (s < 60) return '刚刚'
  if (s < 3600) return `${Math.floor(s / 60)} 分钟前`
  if (s < 86400) return `${Math.floor(s / 3600)} 小时前`
  return `${Math.floor(s / 86400)} 天前`
}

const anyRunning = computed(() => members.value.some(m => m.state === 'RUNNING' || m.state === 'WAITING'))
// 树形成员：从 lead 根往下按 parentId 展开（父不在成员表里的都算根的直系子级）
const memberRows = computed(() => {
  const ids = new Set(members.value.map(m => m.sessionId))
  const childMap = new Map()
  for (const m of members.value) {
    const key = (m.parentId && ids.has(m.parentId)) ? m.parentId : '__root__'
    if (!childMap.has(key)) childMap.set(key, [])
    childMap.get(key).push(m)
  }
  const colorOf = new Map(members.value.map((m, i) => [m.sessionId, i]))
  const rows = []
  const walk = (key, depth) => {
    for (const m of (childMap.get(key) || [])) {
      rows.push({ m, depth, color: colorOf.get(m.sessionId) ?? 0 })
      walk(m.sessionId, depth + 1)
    }
  }
  walk('__root__', 1)
  return rows
})
const summary = computed(() => {
  const run = members.value.filter(m => m.state === 'RUNNING').length
  const idle = members.value.length - run
  const parts = []
  if (run) parts.push(`${run} 执行中`)
  if (idle > 0) parts.push(`${idle} 空闲/离线`)
  return parts.join(' · ') || '暂无成员'
})

async function load() {
  if (!props.sessionId) return
  loading.value = true
  try {
    const on = await api.getTeamEnabled(props.sessionId)
    enabled.value = !!(on && on.enabled)
  } catch (e) { /* 读不到按关闭 */ }
  if (!enabled.value) { loading.value = false; return }
  try {
    const res = await api.getTeamMembers(props.sessionId)
    members.value = Array.isArray(res) ? res : []
  } catch (e) { members.value = [] } finally { loading.value = false }
  try {
    const g = await api.getTeamTasks(props.sessionId)
    tasks.value = g && g.nodes ? g : { nodes: [], edges: [] }
  } catch (e) { /* 忽略 */ }
  try {
    const msgs = await api.getTeamMessages(props.sessionId)
    messages.value = Array.isArray(msgs) ? msgs : []
  } catch (e) { /* 忽略 */ }
  await buildFeed()
}

// 团队动态：成员消息 + 任务事件合并（任务事件按 state 变化才拉，缓存避免每轮都打）
const taskEvCache = new Map()
function tsOf(at) { if (!at) return 0; const t = Date.parse(String(at).replace(' ', 'T')); return Number.isNaN(t) ? 0 : t }
async function buildFeed() {
  const items = []
  for (const mm of messages.value) {
    items.push({ kind: 'msg', key: 'm' + mm.id, from: mm.from, to: mm.to, text: mm.text, at: mm.at })
  }
  for (const n of tasks.value.nodes.slice(0, 12)) {
    let c = taskEvCache.get(n.id)
    if (!c || c.state !== n.state) {
      let evs = c ? c.events : []
      try { evs = await api.getTeamTaskEvents(props.sessionId, n.id) } catch (e) { /* 保留旧缓存 */ }
      c = { state: n.state, events: Array.isArray(evs) ? evs : [] }
      taskEvCache.set(n.id, c)
    }
    ;(c.events || []).forEach((e, i) => {
      items.push({ kind: 'task', key: 't' + n.id + '-' + i, subject: n.subject, ekind: e.kind, detail: e.detail, at: e.at })
    })
  }
  items.sort((a, b) => tsOf(b.at) - tsOf(a.at))
  feed.value = items.slice(0, 16)
}

async function onToggle(v) {
  const next = typeof v === 'boolean' ? v : !!v
  try {
    await api.setTeamEnabled(props.sessionId, next)
    enabled.value = next
    if (next) load()
  } catch (e) { message.error('团队开关设置失败') }
}

function openMember(m) {
  router.push({ path: '/chat', query: { sid: m.sessionId, teamRoot: props.sessionId, memberName: displayName(m) } }).catch(() => {})
}
async function interrupt(m) {
  try {
    await api.interruptTeamMember(props.sessionId, m.sessionId)
    message.success('已叫停该成员当前轮')
    load()
  } catch (e) { message.error('叫停失败') }
}
async function openTask(n) {
  taskDetail.value = { ...n }
  taskEvents.value = []
  taskOpen.value = true
  try {
    const res = await api.getTeamTaskEvents(props.sessionId, n.id)
    taskEvents.value = Array.isArray(res) ? res : []
  } catch (e) { /* 忽略 */ }
}
function openTaskCanvas() { canvasOpen.value = true }

watch(() => props.sessionId, () => load(), { immediate: true })
let refreshTimer = null
onMounted(() => {
  if (props.sessionId) load()
  refreshTimer = window.setInterval(() => {
    if (enabled.value && !taskOpen.value && !canvasOpen.value) load()
  }, 3000)
})
onUnmounted(() => { if (refreshTimer) window.clearInterval(refreshTimer) })
</script>

<style scoped>
.team-panel{padding:14px;display:flex;flex-direction:column;gap:12px;color:var(--text-1,#1f2329)}
.tp-head{display:flex;align-items:center;justify-content:space-between;gap:10px}
.tp-head-l{display:flex;align-items:center;gap:10px;min-width:0}
.tp-ic{width:32px;height:32px;flex:none;border-radius:9px;display:flex;align-items:center;justify-content:center;background:linear-gradient(135deg,#8B7CF6,#6D28D9);color:#fff;box-shadow:0 2px 6px rgba(109,40,217,.28)}
.tp-ic svg{width:18px;height:18px}
.tp-title{font-size:14px;font-weight:650;letter-spacing:-.01em}
.tp-sub{font-size:11px;color:var(--text-3,#98a0aa);margin-top:1px}
.tp-off{border:1px dashed var(--border,#e6e9ed);border-radius:12px;padding:22px 16px;text-align:center}
.tp-off-ic{font-size:22px;color:#c7b7f5}
.tp-off-t{font-size:13px;font-weight:600;margin-top:4px}
.tp-off-s{font-size:11.5px;color:var(--text-3,#98a0aa);margin-top:4px;line-height:1.6}
.tp-off-btn{margin-top:12px;height:30px;padding:0 14px;border-radius:8px;border:1px solid #ddd6fe;background:#f5f3ff;color:#7c3aed;font-size:12px;font-weight:600}

.card{border:1px solid var(--border,#e6e9ed);border-radius:12px;background:var(--panel,#fff);overflow:hidden}
.card-hd{display:flex;align-items:center;justify-content:space-between;padding:9px 12px;border-bottom:1px solid var(--border,#eef0f3);background:var(--panel-2,#fafbfc)}
.card-t{font-size:12px;font-weight:650}
.card-meta{font-size:10.5px;color:var(--text-3,#98a0aa)}
.hint{padding:16px 12px;font-size:11.5px;color:var(--text-3,#98a0aa);text-align:center}

/* 成员 */
.mem-list{padding:4px}
.mem-row{display:flex;align-items:center;gap:10px;padding:8px;border-radius:9px}
.mem-row.clickable{cursor:pointer}
.mem-row:hover{background:var(--panel-2,#fafbfc)}
.avatar{width:30px;height:30px;border-radius:8px;flex:none;display:flex;align-items:center;justify-content:center;color:#fff;font-size:12px;font-weight:700;box-shadow:0 1px 3px rgba(16,24,40,.12)}
.avatar.lead{background:linear-gradient(135deg,#5E93F5,#2A5FE0)}
.mem-main{min-width:0;flex:1}
.mem-top{display:flex;align-items:center;gap:6px}
.mem-name{font-size:12.5px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:130px}
.tag-root{height:15px;padding:0 5px;border-radius:4px;background:#eef4ff;color:#2e6be6;border:1px solid #c9dbfb;font-size:9px;font-weight:650;display:inline-flex;align-items:center}
.tag-code{height:15px;padding:0 5px;border-radius:4px;background:#f5f3ff;color:#7c3aed;border:1px solid #ddd6fe;font-size:9px;font-weight:650;display:inline-flex;align-items:center}
.mem-sub{font-size:10.5px;color:var(--text-3,#98a0aa);margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.mem-state{flex:none;display:inline-flex;align-items:center;gap:4px;font-size:10px;color:var(--text-2,#5f6570)}
.mem-state .d{width:6px;height:6px;border-radius:50%;background:#c8ced6}
.mem-state.run{color:#3b8f4e}.mem-state.run .d{background:#3b8f4e;box-shadow:0 0 0 3px rgba(59,143,78,.15)}
.mem-state.wait{color:#a3691b}.mem-state.wait .d{background:#a3691b}
.mem-state.stop{color:#cf1322}.mem-state.stop .d{background:#cf1322}
.mem-acts{flex:none;display:flex;gap:1px;opacity:1}
.iact{width:24px;height:24px;border:0;background:transparent;border-radius:6px;color:var(--text-3,#98a0aa);display:inline-flex;align-items:center;justify-content:center;cursor:pointer;padding:0}
.iact svg{width:15px;height:15px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}
.iact:hover{background:var(--panel-3,#f2f4f7);color:var(--accent,#2e6be6)}
.iact.on{background:#f5f3ff;color:#7c3aed}
.iact.danger:hover{background:#fff2f0;color:#cf1322}

/* 输入区 */


/* 任务 */
.task-list{padding:4px}
.task-row{display:flex;align-items:center;gap:9px;padding:8px;border-radius:8px}
.task-row.clickable{cursor:pointer}
.task-row:hover{background:var(--panel-2,#fafbfc)}
.tbox{width:16px;height:16px;border-radius:5px;flex:none;display:flex;align-items:center;justify-content:center;font-size:9px;font-weight:800;border:1px solid #d8dde3;color:#98a0aa;background:#f2f4f7}
.tbox.done{background:#eaf5ec;border-color:#cfe4d3;color:#3b8f4e}
.tbox.prog{background:#eef4ff;border-color:#c9dbfb;color:#2e6be6}
.tbox.ready{background:#faf3e6;border-color:#f0dfb6;color:#a3691b}
.tbox.fail{background:#fff2f0;border-color:#f3c9c9;color:#cf1322}
.task-name{flex:1;min-width:0;font-size:12px;font-weight:550;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.task-state{flex:none;font-size:9.5px;padding:0 6px;height:17px;border-radius:4px;display:inline-flex;align-items:center;background:#f2f4f7;color:#98a0aa}
.task-state.done{background:#eaf5ec;color:#3b8f4e}
.task-state.prog{background:#eef4ff;color:#2e6be6}
.task-state.ready{background:#faf3e6;color:#a3691b}
.task-state.fail{background:#fff2f0;color:#cf1322}
.card-ft{display:flex;align-items:center;gap:6px;padding:8px 12px;border-top:1px solid var(--border,#eef0f3);background:var(--panel-2,#fafbfc);font-size:10.5px;color:var(--text-3,#98a0aa)}
.card-ft .link{margin-left:auto;color:#7c3aed;font-weight:600;cursor:pointer}

/* 团队动态 */
.feed-list{padding:4px 6px 6px;display:flex;flex-direction:column;gap:2px;max-height:280px;overflow:auto}
.feed{display:flex;gap:8px;padding:7px 6px;border-radius:8px}
.feed:hover{background:var(--panel-2,#fafbfc)}
.feed-tag{flex:none;height:16px;margin-top:1px;padding:0 5px;border-radius:4px;font-size:9px;font-weight:650;display:inline-flex;align-items:center}
.feed-tag.msg{background:#f5f3ff;color:#7c3aed}
.feed-tag.task{background:#eef4ff;color:#2e6be6}
.feed-body{min-width:0;flex:1}
.feed-line{display:flex;align-items:center;gap:6px}
.feed-line b{font-size:11px;font-weight:650;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.feed-time{margin-left:auto;flex:none;font-size:9.5px;color:var(--text-3,#98a0aa)}
.feed-text{font-size:11.5px;color:var(--text-2,#5f6570);margin-top:1px;line-height:1.5;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}

/* 任务详情 */
.task-detail{display:flex;flex-direction:column;gap:10px}
.td-top{display:flex;align-items:center;gap:8px}
.td-owner{font-size:12px;color:var(--text-3,#98a0aa)}
.td-desc{font-size:12.5px;color:var(--text-2,#5f6570);line-height:1.65}
.td-sec{font-size:11px;font-weight:700;color:var(--text-3,#98a0aa);margin-top:4px}
.td-tl{display:flex;flex-direction:column;gap:10px;border-left:1px solid var(--border,#e6e9ed);padding-left:14px;margin-left:3px}
.td-ev{position:relative}
.td-dot{position:absolute;left:-19px;top:4px;width:8px;height:8px;border-radius:50%;background:#7c3aed}
.td-kind{font-size:12px;font-weight:650}
.td-at{font-size:10.5px;color:var(--text-3,#98a0aa)}
.td-detail{font-size:11.5px;color:var(--text-2,#5f6570);margin-top:2px}
.mem-tree{margin-left:16px;padding-left:8px;border-left:1px dashed #e6e9ed;display:flex;flex-direction:column;gap:2px}
.mem-row.nested{position:relative}
.mem-row.nested::before{content:"";position:absolute;left:-8px;top:50%;width:10px;height:1px;background:#e6e9ed}
</style>
