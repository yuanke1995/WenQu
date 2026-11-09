<template>
  <div class="ops-checkup">
    <a-skeleton v-if="loading" active :paragraph="{ rows: 9 }" />

    <template v-else-if="report">
      <!-- 健康总览 -->
      <div class="hero-card app-card">
        <div class="hero-score">
          <div class="score-ring">
            <svg viewBox="0 0 76 76" aria-hidden="true">
              <circle cx="38" cy="38" r="31" fill="none" stroke="var(--app-border)" stroke-width="6" />
              <circle cx="38" cy="38" r="31" fill="none" :stroke="ringColor" stroke-width="6"
                      stroke-linecap="round" :stroke-dasharray="ringDash" transform="rotate(-90 38 38)" />
            </svg>
            <div class="score-inner"><b>{{ score }}</b><span>健康分</span></div>
          </div>
          <div class="hero-copy">
            <span class="app-pill" :class="overallPill">{{ overallText }}</span>
            <div class="hero-meta">
              上次巡检 <b>{{ fmtTime(report.at) }}</b> · 耗时 {{ report.tookMs || 0 }}ms · {{ triggerText }}
            </div>
            <div class="hero-meta-sub">自动巡检（节拍在「设置 → 定时任务」）</div>
          </div>
        </div>
        <div class="hero-kpis">
          <div class="hero-kpi"><b class="ok">{{ okCount }}</b><span>通过</span></div>
          <div class="hero-kpi"><b class="warn">{{ warnCount }}</b><span>留意</span></div>
          <div class="hero-kpi"><b class="err">{{ failCount }}</b><span>失败</span></div>
          <div class="hero-kpi"><b>{{ report.tookMs || 0 }}<em>ms</em></b><span>本次耗时</span></div>
        </div>
        <div class="hero-trend">
          <div class="trend-head">
            <span>近 7 天</span>
            <button class="app-link-btn" type="button" @click="openHistory">历史报告</button>
          </div>
          <div v-if="trend.length" class="trend-bars" aria-label="近 7 天巡检趋势">
            <span v-for="d in trendSlots" :key="d.date" class="trend-bar"
                  :class="d.overall === 'fail' ? 'err' : d.overall === 'warn' ? 'warn' : d.overall === 'empty' ? 'empty' : 'ok'"
                  :style="{ height: barHeight(d) }" :title="trendTitle(d)"></span>
          </div>
          <div v-else class="trend-empty">暂无历史趋势</div>
          <div class="trend-foot">绿 正常 · 黄 留意 · 红 失败</div>
        </div>
      </div>

      <!-- 最需要处理的一项 -->
      <div v-if="worst" class="app-note checkup-note" :class="worst.level === 'fail' ? 'danger' : 'warn'">
        <span class="checkup-note-ic">{{ worst.level === 'fail' ? '!' : '!' }}</span>
        <div class="checkup-note-text">
          <b>{{ worst.name }}</b>：{{ worst.detail }}
        </div>
        <button class="app-link-btn" type="button" @click="openDetail(worst)">查看处置建议</button>
      </div>
      <div v-else class="app-note checkup-note ok">
        <span class="checkup-note-ic">✓</span>
        <div class="checkup-note-text"><b>全部巡检项正常</b>：平台依赖、安全审计与问答链路都未发现需要处理的事项。</div>
      </div>

      <!-- 巡检项 -->
      <section class="app-card checkup-card">
        <div class="app-list-bar checkup-toolbar">
          <div class="checkup-seg" role="group" aria-label="巡检项分组">
            <button type="button" :class="{ active: groupFilter === 'all' }" @click="groupFilter = 'all'">
              全部 <em>{{ checks.length }}</em>
            </button>
            <button v-for="g in groups" :key="g.name" type="button"
                    :class="{ active: groupFilter === g.name }" @click="groupFilter = g.name">
              {{ g.name }} <em>{{ g.items.length }}</em>
            </button>
          </div>
          <span class="app-list-spacer"></span>
          <label class="checkup-only">
            <a-switch v-model:checked="onlyAbnormal" size="small" />
            <span>只看异常</span>
          </label>
          <button class="app-link-btn" type="button" @click="exportReport">导出报告</button>
        </div>

        <div v-if="visibleGroups.length" class="checkup-groups">
          <div v-for="g in visibleGroups" :key="g.name" class="checkup-group">
            <div class="checkup-group-head">
              <span>{{ g.name }}</span>
              <span class="checkup-group-count">{{ g.items.length }} 项</span>
            </div>
            <div v-for="c in g.items" :key="c.key" class="checkup-row"
                 tabindex="0" role="button" @click="openDetail(c)" @keydown.enter.prevent="openDetail(c)">
              <span class="checkup-dot" :class="levelClass(c.level)"></span>
              <span class="checkup-name">{{ c.name }}</span>
              <span class="app-pill" :class="levelPill(c.level)">{{ levelText(c.level) }}</span>
              <span class="checkup-detail">{{ c.detail }}</span>
              <span class="checkup-ms">{{ c.ms }}ms</span>
              <span class="checkup-act">详情 ›</span>
            </div>
          </div>
        </div>
        <a-empty v-else :description="onlyAbnormal ? '该分组下没有异常项' : '该分组暂无巡检项'" />
      </section>

      <div class="app-list-foot">
        巡检各项互相独立：一项失败不影响其余项出结果。报告与「立即巡检」动作都入审计。
      </div>
    </template>

    <a-empty v-else description="本租户还没有巡检记录">
      <button class="app-btn" type="button" :disabled="running" @click="$emit('run')">
        {{ running ? '巡检中…' : '立即巡检' }}
      </button>
    </a-empty>

    <!-- 巡检项详情 -->
    <a-drawer v-model:open="detailOpen" :width="392" :closable="false" placement="right"
              :header-style="drawerHeaderStyle" :body-style="drawerBodyStyle" :footer-style="drawerFooterStyle">
      <template #title>
        <div class="ops-drawer-head">
          <div>
            <div class="ops-drawer-eyebrow">巡检项详情</div>
            <div class="ops-drawer-name">{{ selected ? selected.name : '巡检项详情' }}</div>
          </div>
          <button class="app-icon-btn ops-drawer-close" type="button" aria-label="关闭" @click="detailOpen = false">✕</button>
        </div>
      </template>
      <template v-if="selected">
        <div class="drawer-headline">
          <span class="app-pill" :class="levelPill(selected.level)">{{ levelText(selected.level) }}</span>
          <span class="drawer-meta">{{ selected.ms }}ms · {{ fmtTime(report.at) }}</span>
        </div>
        <div class="drawer-section">
          <h4>这项在查什么</h4>
          <p>{{ checkMeta(selected.key).what }}</p>
        </div>
        <div class="drawer-section">
          <h4>当前值</h4>
          <div class="drawer-kv">
            <span class="k">当前值</span>
            <span class="v">{{ selected.detail }}</span>
          </div>
        </div>
        <div class="drawer-section">
          <h4>处置建议</h4>
          <p>{{ selected.hint || '暂无额外建议。' }}</p>
        </div>
        <div class="drawer-section">
          <h4>近 7 次</h4>
          <div v-if="checkHistoryLoading" class="drawer-empty">加载中…</div>
          <div v-else-if="checkHistory.length" class="drawer-bars">
            <span v-for="(lv, i) in checkHistory" :key="i" class="drawer-bar"
                  :class="levelClass(lv)" :title="lv"></span>
          </div>
          <div v-else class="drawer-empty">暂无足够的历史报告</div>
        </div>
      </template>
      <template #footer>
        <div class="ops-drawer-foot">
          <button class="app-btn ghost" type="button" @click="detailOpen = false">关闭</button>
          <button class="app-btn ghost" type="button" @click="openHistory">查看历史报告</button>
          <button v-if="selected && selected.key === 'audit'" class="app-btn ghost" type="button" @click="goAudit">去查审计</button>
        </div>
      </template>
    </a-drawer>

    <!-- 历史报告 -->
    <a-drawer v-model:open="historyOpen" :width="460" :closable="false" placement="right"
              :header-style="drawerHeaderStyle" :body-style="drawerBodyStyle">
      <template #title>
        <div class="ops-drawer-head">
          <div>
            <div class="ops-drawer-eyebrow">巡检报告</div>
            <div class="ops-drawer-name">巡检历史</div>
          </div>
          <button class="app-icon-btn ops-drawer-close" type="button" aria-label="关闭" @click="historyOpen = false">✕</button>
        </div>
      </template>
      <div v-if="historyLoading" class="drawer-empty">加载中…</div>
      <template v-else>
        <div class="history-list">
          <button v-for="h in history" :key="h.id" type="button" class="history-row"
                  :class="{ active: historyDetail && historyDetail.id === h.id }"
                  @click="loadHistoryDetail(h)">
            <span class="app-pill" :class="levelPill(h.overall)">{{ levelText(h.overall) }}</span>
            <span class="history-time">{{ fmtTime(h.at) }}</span>
            <span class="history-counts">{{ h.tookMs }}ms · 失败 {{ h.failCount || 0 }} · 留意 {{ h.warnCount || 0 }}</span>
            <span class="history-trigger">{{ h.trigger === 'schedule' ? '定时' : '手动' }}</span>
          </button>
        </div>
        <a-empty v-if="!history.length" description="最近 7 天没有巡检报告" />
        <div v-if="historyDetail" class="history-detail">
          <h4>{{ fmtTime(historyDetail.at) }} 的巡检项</h4>
          <a-table class="history-table" :columns="historyCols" :data-source="historyDetail.checks || []" row-key="key"
                   size="small" :pagination="false" :scroll="{ y: 300 }">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'level'">
                <span class="app-pill" :class="levelPill(record.level)">{{ levelText(record.level) }}</span>
              </template>
            </template>
          </a-table>
        </div>
      </template>
    </a-drawer>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { getOpsCheckupHistory, getOpsCheckupTrend, getOpsCheckupDetail } from '../../api'

const props = defineProps({
  report: { type: Object, default: null },
  running: { type: Boolean, default: false },
  loading: { type: Boolean, default: false }
})
const emit = defineEmits(['run', 'open-audit'])

// 抽屉外壳与原型对齐：自定义 eyebrow 标题栏、18px 内边距、底部动作条
const drawerHeaderStyle = { padding: '16px 18px 12px', borderBottom: '1px solid var(--app-border)' }
const drawerBodyStyle = { padding: '14px 18px 8px' }
const drawerFooterStyle = { padding: '11px 18px', borderTop: '1px solid var(--app-border)' }

const groupFilter = ref('all')
const onlyAbnormal = ref(false)

const checks = computed(() => (props.report && props.report.checks) || [])
const okCount = computed(() => checks.value.filter(c => c.level === 'ok').length)
const warnCount = computed(() => checks.value.filter(c => c.level === 'warn').length)
const failCount = computed(() => checks.value.filter(c => c.level === 'fail').length)
const score = computed(() => Math.max(0, Math.min(100, 100 - failCount.value * 25 - warnCount.value * 8)))
const ringDash = computed(() => {
  const c = 2 * Math.PI * 31
  const dash = c * score.value / 100
  return `${dash.toFixed(2)} ${(c - dash).toFixed(2)}`
})
const ringColor = computed(() => failCount.value ? 'var(--app-danger)' : warnCount.value ? 'var(--app-warn)' : 'var(--app-ok)')
const overallPill = computed(() => props.report && props.report.overall === 'fail' ? 'err' : props.report && props.report.overall === 'warn' ? 'warn' : 'ok')
const overallText = computed(() => props.report && props.report.overall === 'fail'
  ? '有必须处理的问题' : props.report && props.report.overall === 'warn' ? '整体可用，有要留意的事项' : '整体健康')
const triggerText = computed(() => props.report && props.report.trigger === 'schedule' ? '定时' : '手动')

const SEVERITY = { fail: 0, warn: 1, ok: 2 }
const groups = computed(() => {
  const map = new Map()
  for (const c of checks.value) {
    const name = c.group || '其他'
    if (!map.has(name)) map.set(name, [])
    map.get(name).push(c)
  }
  return [...map.entries()].map(([name, items]) => ({
    name,
    items: items.slice().sort((a, b) => (SEVERITY[a.level] ?? 9) - (SEVERITY[b.level] ?? 9))
  }))
})
const visibleGroups = computed(() => groups.value
  .filter(g => groupFilter.value === 'all' || groupFilter.value === g.name)
  .map(g => ({ name: g.name, items: g.items.filter(c => !onlyAbnormal.value || c.level !== 'ok') }))
  .filter(g => g.items.length))
const worst = computed(() => checks.value.find(c => c.level === 'fail') || checks.value.find(c => c.level === 'warn') || null)

const trend = ref([])
const pad2 = n => String(n).padStart(2, '0')
// 始终铺满 7 个日期槽位：只有 1~2 天报告时，柱子不会因为 flex:1 被拉成整块色块
const trendSlots = computed(() => {
  const byDate = new Map((trend.value || []).map(d => [d.date, d]))
  const base = new Date()
  const out = []
  for (let i = 6; i >= 0; i--) {
    const dt = new Date(base.getFullYear(), base.getMonth(), base.getDate() - i)
    const key = `${dt.getFullYear()}-${pad2(dt.getMonth() + 1)}-${pad2(dt.getDate())}`
    out.push(byDate.get(key) || { date: key, runs: 0, fail: 0, warn: 0, overall: 'empty' })
  }
  return out
})
async function loadTrend () {
  try {
    const r = await getOpsCheckupTrend(7)
    trend.value = r.success ? (r.data || []) : []
  } catch (e) {
    trend.value = []
  }
}
const barHeight = d => d.overall === 'empty'
  ? '2px'
  : `${Math.min(100, 22 + Math.max(1, Number(d.runs || 1)) * 12)}%`
const trendTitle = d => d.overall === 'empty'
  ? `${d.date} · 无巡检`
  : `${d.date} · ${d.runs} 次 · 失败 ${d.fail || 0} · 留意 ${d.warn || 0}`

const fmtTime = s => (s ? String(s).replace('T', ' ').slice(0, 19) : '—')
const levelText = l => ({ ok: '正常', warn: '留意', fail: '必须处理' }[l] || l || '—')
const levelPill = l => l === 'ok' ? 'ok' : l === 'warn' ? 'warn' : 'err'
const levelClass = l => l === 'ok' ? 'ok' : l === 'warn' ? 'warn' : 'err'

const CHECK_META = {
  mysql: { what: '验证主库连通性与版本。数据库不在线时，成员、会话、配置与审计全部不可用。' },
  redis: { what: '验证 Redis 在线、可 ping、可读内存信息。它是运行态缓存与多副本协调的硬依赖，启动期不在线后端起不来。' },
  keyword: { what: '检查关键词引擎是否可达。不可达时检索自动降级回 MySQL LIKE，不影响问答但影响召回质量。' },
  disk: { what: '检查落盘目录是否存在、所在盘剩余空间。图片、附件、产物、工具外溢原文都写在这里。' },
  audit: { what: '抽验最近 2000 行审计事件，逐行校验哈希与链头。内容被改写必须立即追责；序号洞按设计如实报。' },
  login: { what: '统计当前处于登录锁定态的账号。连续失败达到阈值会临时锁定，管理员重置密码可立即解锁。' },
  providers: { what: '检查启用中的供应商数量与 API Key 配置。一个可用供应商都没有时，问答链路整体不可用。' },
  tools: { what: '核对工具登记名与实现名是否逐字一致，以及常驻会话的沙盒/记忆工具是否都进了档位名单。' },
  resume: { what: '检查进程重启后「发出去了却没有回答」的轮有没有被接回；接不回时那条问题旁边会一直空着。' }
}
const checkMeta = key => CHECK_META[key] || { what: '该项检查运行状态并给出处置建议。' }

const detailOpen = ref(false)
const selected = ref(null)
const historyOpen = ref(false)
const history = ref([])
const historyLoading = ref(false)
const historyDetail = ref(null)
const checkHistory = ref([])
const checkHistoryLoading = ref(false)
const checkHistoryCache = new Map()
const historyCols = [
  { title: '巡检项', dataIndex: 'name', key: 'name' },
  { title: '级别', key: 'level', width: 84 },
  { title: '说明', dataIndex: 'detail', key: 'detail' }
]

async function openDetail (c) {
  selected.value = c
  detailOpen.value = true
  checkHistory.value = []
  if (checkHistoryCache.has(c.key)) {
    checkHistory.value = checkHistoryCache.get(c.key)
    return
  }
  checkHistoryLoading.value = true
  try {
    let list = history.value
    if (!list.length) {
      const r = await getOpsCheckupHistory({ days: 7, limit: 7 })
      if (r.success) {
        list = r.data || []
        history.value = list
      }
    }
    const ids = list.slice(0, 7).map(h => h.id)
    const details = await Promise.all(ids.map(async id => {
      try {
        const r = await getOpsCheckupDetail(id)
        return r.success ? (r.data || null) : null
      } catch (e) { return null }
    }))
    const levels = details.filter(Boolean).map(d => {
      const found = (d.checks || []).find(x => x.key === c.key)
      return found ? found.level : null
    }).filter(Boolean).reverse()
    checkHistory.value = levels
    checkHistoryCache.set(c.key, levels)
  } catch (e) {
    checkHistory.value = []
  } finally {
    checkHistoryLoading.value = false
  }
}

async function openHistory () {
  detailOpen.value = false
  historyOpen.value = true
  if (history.value.length) return
  historyLoading.value = true
  try {
    const r = await getOpsCheckupHistory({ days: 7, limit: 20 })
    history.value = r.success ? (r.data || []) : []
  } catch (e) {
    history.value = []
  } finally {
    historyLoading.value = false
  }
}
async function loadHistoryDetail (h) {
  try {
    const r = await getOpsCheckupDetail(h.id)
    historyDetail.value = r.success ? (r.data || null) : null
  } catch (e) {
    historyDetail.value = null
  }
}

function exportReport () {
  if (!props.report) return
  const blob = new Blob([JSON.stringify(props.report, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `wenqu-checkup-${String(props.report.at || '').replace(/[:T]/g, '-').slice(0, 19)}.json`
  a.click()
  URL.revokeObjectURL(url)
}
function goAudit () {
  detailOpen.value = false
  emit('open-audit')
}

watch(() => props.report, () => {
  history.value = []
  historyDetail.value = null
  checkHistoryCache.clear()
  checkHistory.value = []
  loadTrend()
}, { deep: false })

loadTrend()
</script>

<style scoped>
.ops-checkup { display: flex; flex-direction: column; gap: 10px; }

.hero-card { display: flex; align-items: center; gap: 22px; padding: 14px 18px; min-height: 126px; }
.hero-score { display: flex; align-items: center; gap: 16px; flex: 1; min-width: 0; }
.score-ring { width: 76px; height: 76px; position: relative; flex: none; }
.score-ring svg { width: 76px; height: 76px; display: block; }
.score-inner {
  position: absolute; inset: 0; display: flex; flex-direction: column;
  align-items: center; justify-content: center; line-height: 1;
}
.score-inner b { font-size: 22px; font-weight: 700; letter-spacing: -.02em; }
.score-inner span { font-size: 9.5px; color: var(--app-text3); margin-top: 4px; }
.hero-copy { min-width: 0; }
.hero-copy .app-pill { margin-bottom: 8px; }
.hero-meta { font-size: 11.5px; color: var(--app-text2); line-height: 1.5; }
.hero-meta b { color: var(--app-text); font-weight: 500; font-family: var(--app-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace); font-size: 11px; }
.hero-meta-sub { font-size: 11px; color: var(--app-text3); margin-top: 3px; }
.hero-kpis { display: flex; align-items: center; gap: 18px; flex: none; padding: 0 18px; border-left: 1px solid var(--app-border); }
.hero-kpi { text-align: center; min-width: 46px; }
.hero-kpi b { display: block; font-size: 20px; font-weight: 700; line-height: 1.15; }
.hero-kpi b.ok { color: var(--app-ok); }
.hero-kpi b.warn { color: var(--app-warn); }
.hero-kpi b.err { color: var(--app-danger); }
.hero-kpi em { font-size: 10px; font-style: normal; font-weight: 500; color: var(--app-text3); margin-left: 1px; }
.hero-kpi span { font-size: 10.5px; color: var(--app-text3); }
.hero-trend { flex: none; width: 190px; border-left: 1px solid var(--app-border); padding-left: 18px; }
.trend-head { display: flex; align-items: center; justify-content: space-between; font-size: 11px; color: var(--app-text2); margin-bottom: 8px; }
.trend-bars { height: 34px; display: flex; align-items: flex-end; gap: 5px; }
.trend-bar { flex: 1; border-radius: 2px 2px 1px 1px; min-height: 6px; }
.trend-bar.ok { background: var(--app-ok); opacity: .82; }
.trend-bar.warn { background: var(--app-warn); opacity: .9; }
.trend-bar.err { background: var(--app-danger); }
.trend-bar.empty { min-height: 0; background: var(--app-border); opacity: .5; }
.trend-empty { height: 34px; display: flex; align-items: center; justify-content: center; font-size: 10px; color: var(--app-text3); }
.trend-foot { font-size: 9.5px; color: var(--app-text3); margin-top: 6px; }

.checkup-note { display: flex; align-items: center; gap: 10px; margin: 0; padding: 9px 12px; border-radius: 8px; }
.checkup-note.ok { border-color: var(--app-border); background: var(--app-ok-weak, #eaf5ec); color: var(--app-ok); }
.checkup-note.warn, .checkup-note.danger { border-color: var(--app-warn-border, #f0dfb6); background: var(--app-warn-weak, #faf3e6); color: var(--app-warn-text, #a3691b); }
.checkup-note-ic {
  width: 18px; height: 18px; border-radius: 50%; flex: none;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 11px; font-weight: 700; background: rgba(255, 255, 255, .6);
}
.checkup-note-text { flex: 1; min-width: 0; line-height: 1.5; font-size: 11.5px; }
.checkup-note-text b { font-weight: 600; }

.checkup-card { padding: 0; overflow: hidden; }
.checkup-toolbar { padding: 10px 12px; border-bottom: 1px solid var(--app-border); }
.checkup-seg { display: inline-flex; gap: 2px; padding: 2px; background: var(--app-bg, #f7f8fa); border-radius: 7px; }
.checkup-seg button {
  border: none; background: transparent; color: var(--app-text2); font-size: 11px;
  padding: 5px 9px; border-radius: 5px; white-space: nowrap; cursor: pointer;
}
.checkup-seg button em { font-style: normal; color: var(--app-text3); margin-left: 3px; font-size: 10px; }
.checkup-seg button.active { background: var(--app-panel); color: var(--app-text); font-weight: 600; box-shadow: 0 1px 2px rgba(16, 24, 40, .06); }
.checkup-seg button.active em { color: var(--app-accent); }
.checkup-only { display: inline-flex; align-items: center; gap: 6px; font-size: 11px; color: var(--app-text2); white-space: nowrap; }
.checkup-groups { font-size: 11.5px; }
.checkup-group-head {
  display: flex; align-items: center; justify-content: space-between;
  height: 28px; padding: 0 14px; background: var(--app-panel-2, #fafbfc);
  border-bottom: 1px solid var(--app-border);
  font-size: 10px; color: var(--app-text3); letter-spacing: .04em;
}
.checkup-group-count { font-size: 9.5px; }
.checkup-row {
  display: grid; grid-template-columns: 8px minmax(130px, 1.1fr) 70px minmax(0, 2.2fr) 52px 46px;
  align-items: center; column-gap: 10px; min-height: 34px; padding: 0 14px;
  border-bottom: 1px solid var(--app-border); cursor: pointer; transition: background .12s;
}
.checkup-row:hover { background: var(--app-accent-weak, #eef2f7); }
.checkup-row:focus-visible { outline: 2px solid var(--app-accent); outline-offset: -2px; }
.checkup-dot { width: 6px; height: 6px; border-radius: 50%; }
.checkup-dot.ok { background: var(--app-ok); }
.checkup-dot.warn { background: var(--app-warn); }
.checkup-dot.err { background: var(--app-danger); }
.checkup-name { font-size: 12px; color: var(--app-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.checkup-detail { color: var(--app-text2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.checkup-ms { color: var(--app-text3); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 10.5px; text-align: right; }
.checkup-act { text-align: right; color: var(--app-accent); font-size: 11px; }

.drawer-headline { display: flex; align-items: center; gap: 9px; margin-bottom: 12px; }
.ops-drawer-head { display: flex; align-items: flex-start; gap: 10px; min-width: 0; }
.ops-drawer-eyebrow { font-size: 10px; color: var(--app-text3); letter-spacing: .06em; margin-bottom: 4px; }
.ops-drawer-name { font-size: 15px; font-weight: 600; line-height: 1.35; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ops-drawer-close { margin-left: auto; flex: none; }
.ops-drawer-foot { display: flex; justify-content: flex-end; gap: 8px; }
.drawer-meta { font-size: 10.5px; color: var(--app-text3); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.drawer-section { margin-bottom: 16px; }
.drawer-section h4 { font-size: 11px; font-weight: 600; color: var(--app-text2); margin: 0 0 7px; }
.drawer-section p { margin: 0; font-size: 11.5px; color: var(--app-text2); line-height: 1.7; }
.drawer-kv { display: grid; grid-template-columns: 78px 1fr; gap: 6px 10px; font-size: 11.5px; }
.drawer-kv .k { color: var(--app-text3); }
.drawer-kv .v { color: var(--app-text); word-break: break-all; line-height: 1.6; }
.drawer-empty { font-size: 11px; color: var(--app-text3); }
.drawer-bars { display: flex; align-items: flex-end; gap: 5px; height: 30px; }
.drawer-bar { width: 22px; border-radius: 2px; min-height: 6px; }
.drawer-bar.ok { height: 70%; background: var(--app-ok); opacity: .82; }
.drawer-bar.warn { height: 52%; background: var(--app-warn); }
.drawer-bar.err { height: 40%; background: var(--app-danger); }
.drawer-actions { display: flex; gap: 8px; padding-top: 4px; }
.history-list { display: flex; flex-direction: column; gap: 4px; margin-bottom: 14px; }
.history-row {
  display: grid; grid-template-columns: 62px 1fr auto; align-items: center; gap: 8px;
  padding: 8px 10px; border: 1px solid var(--app-border); border-radius: 6px;
  background: var(--app-panel); text-align: left; cursor: pointer;
}
.history-row:hover, .history-row.active { border-color: var(--app-accent); background: var(--app-accent-weak, #eef2f7); }
.history-time { font-size: 11.5px; color: var(--app-text); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.history-counts { grid-column: 2 / 3; font-size: 10px; color: var(--app-text3); margin-top: 2px; }
.history-trigger { font-size: 10px; color: var(--app-text3); }
.history-detail h4 { font-size: 11.5px; font-weight: 600; color: var(--app-text2); margin: 12px 0 8px; }
.history-table :deep(.ant-table) { font-size: 11px; }
.history-table :deep(.ant-table-thead > tr > th) {
  background: var(--app-panel-2, #fafbfc); color: var(--app-text3);
  font-size: 10px; font-weight: 500; padding: 6px 8px;
  border-bottom: 1px solid var(--app-border);
}
.history-table :deep(.ant-table-thead > tr > th::before) { display: none; }
.history-table :deep(.ant-table-tbody > tr > td) {
  padding: 6px 8px; border-bottom: 1px solid var(--app-border);
}

@media (max-width: 900px) {
  .hero-card { flex-wrap: wrap; }
  .hero-trend { width: 100%; border-left: none; padding-left: 0; border-top: 1px solid var(--app-border); padding-top: 10px; }
  .hero-kpis { border-left: none; padding: 0; }
  .checkup-row { grid-template-columns: 8px minmax(110px, 1.2fr) 62px minmax(0, 1.4fr) 46px; }
  .checkup-detail { display: none; }
}
@media (max-width: 600px) {
  .hero-kpis { width: 100%; justify-content: space-between; }
  .checkup-seg { flex-wrap: wrap; }
  .checkup-row { grid-template-columns: 8px minmax(90px, 1fr) 56px 40px; }
  .checkup-ms { display: none; }
}
</style>
