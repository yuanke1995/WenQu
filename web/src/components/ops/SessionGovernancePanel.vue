<template>
  <div class="ops-session">
    <!-- 指标 -->
    <div class="metric-grid">
      <div class="app-card metric"><div class="metric-label">在册成员</div><div class="metric-num">{{ statText(stats.total) }}</div><div class="metric-sub">含禁用账号</div></div>
      <div class="app-card metric"><div class="metric-label">锁定中</div><div class="metric-num warn">{{ statText(stats.locked) }}</div><div class="metric-sub">连续失败触发 · 重置密码可解锁</div></div>
      <div class="app-card metric"><div class="metric-label">今日登录</div><div class="metric-num">{{ statText(stats.today) }}</div><div class="metric-sub">按最近登录时间统计</div></div>
      <div class="app-card metric"><div class="metric-label">30 天未登录</div><div class="metric-num">{{ statText(stats.inactive) }}</div><div class="metric-sub">含从未登录</div></div>
    </div>

    <!-- 风险提示 -->
    <div v-if="hasRisk" class="app-note warn session-note">
      <span class="session-note-ic">!</span>
      <div class="session-note-text">
        <b>{{ statText(stats.locked) }} 个账号处于锁定态</b>，{{ statText(stats.fail) }} 个连续失败 ≥3 次，{{ statText(stats.inactive) }} 个 30 天未登录。建议先核对离职名单再处置。
      </div>
      <button class="app-link-btn" type="button" @click="focusRisk">仅看锁定中</button>
    </div>

    <a-alert v-if="loadError" type="error" show-icon :message="`成员名单拉取失败：${loadError}`" style="margin:0 0 10px">
      <template #action><a-button size="small" :loading="loading" @click="reload">重试</a-button></template>
    </a-alert>

    <!-- 列表 -->
    <section class="app-card session-card">
      <div class="app-list-bar session-toolbar">
        <a-input v-model:value="kw" allow-clear size="small" class="session-search"
                 placeholder="搜索昵称 / 登录账号" @change="onKw">
          <template #prefix><SearchOutlined class="session-search-ic" /></template>
        </a-input>
        <a-select v-model:value="dept" allow-clear size="small" class="session-filter"
                  placeholder="全部部门" :options="deptOptions" @change="onFilter" />
        <a-select v-model:value="status" allow-clear size="small" class="session-filter"
                  placeholder="全部状态" :options="statusOptions" @change="onFilter" />
        <div class="ops-seg" role="group" aria-label="风险筛选">
          <button v-for="o in riskOptions" :key="o.value" type="button" :class="{ active: risk === o.value }"
                  @click="setRisk(o.value)">{{ o.label }}</button>
        </div>
        <span class="app-list-spacer"></span>
        <span class="app-list-count">共 {{ total }} 人 · 按最近登录排序</span>
        <button class="app-btn danger batch-kick" type="button" :disabled="!selectedUids.length" @click="confirmBatchKick">
          批量强制下线 ({{ selectedUids.length }})
        </button>
      </div>

      <a-table class="session-table" :columns="columns" :data-source="rows" row-key="uid" size="small"
               table-layout="fixed" :loading="loading" :scroll="{ x: 920 }"
               :row-selection="{ selectedRowKeys: selectedUids, onChange: onSelectChange }"
               :customRow="rowProps"
               :pagination="{ current: page, pageSize: size, total, onChange: onPage, showSizeChanger: false, showTotal: t => `共 ${t} 人` }">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'member'">
            <div class="session-user">
              <UserAvatar :avatar="record.avatar" :name="record.username" :size="26" />
              <div class="session-user-txt">
                <span class="session-name">{{ record.username || '（未命名）' }}</span>
                <code class="session-uid">{{ record.uid }}</code>
              </div>
            </div>
          </template>
          <template v-else-if="column.key === 'department'">
            <span v-if="record.departmentId" class="session-dept">{{ deptName(record.departmentId) }}</span>
            <span v-else class="session-muted">未分配</span>
          </template>
          <template v-else-if="column.key === 'lastLoginTime'">
            <span v-if="record.lastLoginTime" :title="fullTime(record.lastLoginTime)">{{ relativeTime(record.lastLoginTime) }}</span>
            <span v-else class="session-muted">从未登录</span>
          </template>
          <template v-else-if="column.key === 'fail'">
            <span class="session-fail" :class="{ zero: !record.loginFailCount }">{{ record.loginFailCount || 0 }}</span>
          </template>
          <template v-else-if="column.key === 'status'">
            <span class="app-pill" :class="statusPill(record)">{{ statusText(record) }}</span>
          </template>
          <template v-else-if="column.key === 'epoch'">
            <span class="session-mono">{{ record.tokenEpoch || 0 }}</span>
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space>
              <button class="app-link-btn" type="button" @click.stop="openDetail(record)">详情</button>
              <button class="app-link-btn danger" type="button" @click.stop="confirmKick(record)">强制下线</button>
            </a-space>
          </template>
        </template>
      </a-table>
      <div class="session-foot">
        会话为无状态 JWT：强制下线 = 会话代数 + 1，该账号所有设备立即失效。设备级在线会话（IP / UA / 最后活跃）不在本次范围。
      </div>
    </section>

    <!-- 成员安全详情 -->
    <a-drawer v-model:open="drawerOpen" :width="392" :closable="false" placement="right"
              :header-style="drawerHeaderStyle" :body-style="drawerBodyStyle" :footer-style="drawerFooterStyle">
      <template #title>
        <div class="ops-drawer-head">
          <div>
            <div class="ops-drawer-eyebrow">成员安全详情</div>
            <div class="ops-drawer-name">{{ current ? (current.username || current.uid) : '成员' }}</div>
          </div>
          <button class="app-icon-btn ops-drawer-close" type="button" aria-label="关闭" @click="drawerOpen = false">✕</button>
        </div>
      </template>
      <template v-if="current">
        <div class="drawer-pill">
          <span class="app-pill" :class="statusPill(current)">{{ statusText(current) }}</span>
          <span class="drawer-uid">{{ current.uid }}</span>
        </div>

        <div class="drawer-section">
          <h4>账号与归属</h4>
          <div class="drawer-kv">
            <span class="k">登录账号</span><span class="v">{{ current.uid }}</span>
            <span class="k">部门</span><span class="v">{{ current.departmentId ? deptName(current.departmentId) : '未分配' }}</span>
            <span class="k">最近登录</span><span class="v">{{ current.lastLoginTime ? fullTime(current.lastLoginTime) : '从未登录' }}</span>
            <span class="k">会话代数</span><span class="v">{{ current.tokenEpoch || 0 }}</span>
          </div>
        </div>

        <div class="drawer-section">
          <h4>登录安全</h4>
          <div class="drawer-kv">
            <span class="k">连续失败</span><span class="v" :class="{ 'drawer-warn': current.loginFailCount }">{{ current.loginFailCount || 0 }} 次</span>
            <span class="k">锁定至</span><span class="v">{{ current.lockedUntil ? fullTime(current.lockedUntil) : '—' }}</span>
            <span class="k">账号状态</span><span class="v">{{ statusText(current) }}</span>
            <span class="k">角色</span><span class="v">{{ (current.roles || []).join('、') || '—' }}</span>
          </div>
        </div>

        <div class="drawer-section">
          <h4>最近安全动作</h4>
          <div v-if="auditLoading" class="drawer-empty">加载中…</div>
          <div v-else-if="auditError" class="drawer-empty">{{ auditError }}</div>
          <div v-else-if="auditRows.length" class="drawer-timeline">
            <div v-for="(a, i) in auditRows" :key="i" class="drawer-event">
              <span class="event-time">{{ fmtAuditTime(a.ts) }}</span>
              <span class="event-action">{{ a.action }}</span>
              <span class="event-detail">{{ auditDetail(a) }}</span>
            </div>
          </div>
          <div v-else class="drawer-empty">最近 90 天没有与该账号相关的审计事件</div>
        </div>
      </template>
      <template #footer>
        <div class="ops-drawer-foot">
          <button class="app-btn ghost" type="button" @click="drawerOpen = false">关闭</button>
          <button v-if="current" class="app-btn ghost" type="button" style="color:var(--app-danger);border-color:var(--app-danger-border)" @click="confirmKick(current)">强制下线</button>
        </div>
      </template>
    </a-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { SearchOutlined } from '@ant-design/icons-vue'
import {
  pageUsers, listDepartments, forceLogoutUser, batchForceLogoutUsers, listAuditEvents
} from '../../api'
import UserAvatar from '../UserAvatar.vue'

// 抽屉外壳与原型对齐：自定义 eyebrow 标题栏、18px 内边距、底部动作条
const drawerHeaderStyle = { padding: '16px 18px 12px', borderBottom: '1px solid var(--app-border)' }
const drawerBodyStyle = { padding: '14px 18px 8px' }
const drawerFooterStyle = { padding: '11px 18px', borderTop: '1px solid var(--app-border)' }

const columns = [
  { title: '成员', key: 'member' },
  { title: '部门', key: 'department', width: 120 },
  { title: '最近登录', key: 'lastLoginTime', width: 130 },
  { title: '失败', key: 'fail', width: 70 },
  { title: '账号状态', key: 'status', width: 110 },
  { title: '会话代数', key: 'epoch', width: 90 },
  { title: '操作', key: 'action', width: 150 }
]
const statusOptions = [{ value: 1, label: '启用中' }, { value: 0, label: '已禁用' }]
const riskOptions = [
  { value: 'all', label: '全部' },
  { value: 'locked', label: '锁定中' },
  { value: 'fail', label: '多次失败' },
  { value: 'inactive', label: '30 天未登录' }
]

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const loadError = ref('')
const kw = ref('')
const dept = ref(undefined)
const status = ref(undefined)
const risk = ref('all')
const selectedUids = ref([])

const departments = ref([])
const deptOptions = computed(() => departments.value.map(d => ({ label: d.name, value: d.id })))
const deptName = id => (departments.value.find(d => d.id === id) || {}).name || '—'

const stats = ref({ total: null, locked: null, today: null, inactive: null, fail: null })
const hasRisk = computed(() => Number(stats.value.locked || 0) > 0
  || Number(stats.value.fail || 0) > 0 || Number(stats.value.inactive || 0) > 0)
const statText = v => (v === null || v === undefined ? '—' : v)

const fmtTime = s => (s ? String(s).replace('T', ' ').slice(0, 19) : '')
const fullTime = s => (s ? String(s).replace('T', ' ') : '')
const relativeTime = s => {
  if (!s) return ''
  const d = new Date(String(s).replace(' ', 'T'))
  if (isNaN(d.getTime())) return fullTime(s)
  const diff = Date.now() - d.getTime()
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return Math.floor(diff / 60_000) + ' 分钟前'
  if (diff < 86_400_000) return Math.floor(diff / 3_600_000) + ' 小时前'
  if (diff < 7 * 86_400_000) return Math.floor(diff / 86_400_000) + ' 天前'
  return fullTime(s).slice(0, 10)
}
const lockedNow = r => r.lockedUntil && new Date(String(r.lockedUntil).replace(' ', 'T')).getTime() > Date.now()
const statusPill = r => (r.status === 0 ? 'muted' : lockedNow(r) ? 'warn' : 'ok')
const statusText = r => (r.status === 0 ? '已禁用' : lockedNow(r) ? '锁定中' : '启用')

const query = () => {
  const q = { kw: kw.value, departmentId: dept.value, status: status.value, page: page.value, size: size.value }
  if (risk.value === 'locked') q.locked = true
  else if (risk.value === 'fail') q.minFail = 3
  else if (risk.value === 'inactive') q.inactiveDays = 30
  return q
}

let kwTimer = null
function onKw () {
  clearTimeout(kwTimer)
  kwTimer = setTimeout(() => { page.value = 1; loadPage() }, 300)
}
function onFilter () { page.value = 1; loadPage() }
function setRisk (value) { risk.value = value; onFilter() }
function onPage (p, s) { page.value = p; size.value = s; loadPage() }
function onSelectChange (keys) { selectedUids.value = keys }

function rowProps (record) {
  return {
    onClick: event => {
      if (event && event.target && event.target.closest
          && event.target.closest('.ant-table-selection-column, .ant-checkbox-wrapper, button, a')) return
      openDetail(record)
    }
  }
}

async function loadPage () {
  loading.value = true
  loadError.value = ''
  try {
    const r = await pageUsers(query())
    if (r.success) {
      rows.value = r.data.items || []
      total.value = r.data.total || 0
      page.value = r.data.page || 1
      const ids = new Set(rows.value.map(x => x.uid))
      selectedUids.value = selectedUids.value.filter(id => ids.has(id))
    } else {
      rows.value = []
      total.value = 0
      loadError.value = r.msg || '接口返回失败'
    }
  } catch (e) {
    rows.value = []
    total.value = 0
    loadError.value = e.message || '网络错误'
  } finally { loading.value = false }
}

async function loadStats () {
  try {
    const [all, locked, today, inactive, fail] = await Promise.all([
      pageUsers({ size: 1 }),
      pageUsers({ size: 1, locked: true }),
      pageUsers({ size: 1, loggedInToday: true }),
      pageUsers({ size: 1, inactiveDays: 30 }),
      pageUsers({ size: 1, minFail: 3 })
    ])
    stats.value = {
      total: all.success ? all.data.total : null,
      locked: locked.success ? locked.data.total : null,
      today: today.success ? today.data.total : null,
      inactive: inactive.success ? inactive.data.total : null,
      fail: fail.success ? fail.data.total : null
    }
  } catch (e) { /* 指标拿不到不挡列表 */ }
}

async function loadDepartments () {
  try {
    const r = await listDepartments()
    if (r.success) departments.value = r.data || []
  } catch (e) { message.error(e.message || '部门名单加载失败') }
}

function reload () {
  loadPage()
  loadStats()
}
function focusRisk () { risk.value = 'locked'; onFilter() }

function confirmKick (record) {
  Modal.confirm({
    title: `强制下线 ${record.username || record.uid}？`,
    content: '该账号所有设备的登录态立即失效，需重新登录。此动作落审计。',
    okText: '强制下线', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      try {
        const r = await forceLogoutUser(record.uid)
        if (r.success) {
          message.success(`已强制下线：${record.uid}`)
          reload()
          drawerOpen.value = false
        } else message.error(r.msg || '操作失败')
      } catch (e) { message.error(e.message || '操作失败') }
    }
  })
}
function confirmBatchKick () {
  const uids = selectedUids.value.slice()
  if (!uids.length) return
  Modal.confirm({
    title: `批量强制下线 ${uids.length} 个账号？`,
    content: '这些账号所有设备的登录态立即失效，每人各落一条审计。单次最多 100 个。',
    okText: '批量下线', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      try {
        const r = await batchForceLogoutUsers(uids)
        if (r.success) {
          message.success(r.msg || `已强制下线 ${r.data.successCount} 个账号`)
          if (r.data && r.data.failCount) message.warning(`失败 ${r.data.failCount} 个，详见接口返回`)
          selectedUids.value = []
          reload()
        } else message.error(r.msg || '操作失败')
      } catch (e) { message.error(e.message || '操作失败') }
    }
  })
}

// ==================== 详情抽屉 ====================
const drawerOpen = ref(false)
const current = ref(null)
const auditRows = ref([])
const auditLoading = ref(false)
const auditError = ref('')

function openDetail (record) {
  current.value = record
  drawerOpen.value = true
  loadAudit(record.uid)
}
async function loadAudit (uid) {
  auditLoading.value = true
  auditError.value = ''
  auditRows.value = []
  try {
    const q = new URLSearchParams({ days: '90', page: '1', size: '10', keyword: uid })
    const r = await listAuditEvents(q.toString())
    if (r.success) auditRows.value = r.data.items || []
    else auditError.value = r.msg || '审计事件加载失败（可能没有 audit:read 权限）'
  } catch (e) {
    auditError.value = e.message || '审计事件加载失败'
  } finally { auditLoading.value = false }
}
const fmtAuditTime = s => (s ? String(s).replace('T', ' ').slice(5, 19) : '')
const auditDetail = row => {
  try {
    const o = JSON.parse(row.detail || '{}')
    return Object.entries(o).filter(([k]) => !['docId', 'kbId', 'agentId', 'uid', 'id', 'key'].includes(k))
      .map(([k, v]) => `${k}=${v}`).join('  ')
  } catch (e) { return row.detail || '' }
}

onMounted(() => {
  loadDepartments()
  loadPage()
  loadStats()
})
</script>

<style scoped>
.ops-session { display: flex; flex-direction: column; gap: 10px; }
.metric-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.metric { padding: 12px 14px; }
.metric-label { font-size: 10.5px; color: var(--app-text3); }
.metric-num { font-size: 23px; font-weight: 700; line-height: 1.2; margin-top: 5px; letter-spacing: -.02em; }
.metric-num.warn { color: var(--app-warn); }
.metric-sub { font-size: 10.5px; color: var(--app-text3); margin-top: 3px; }
.session-note { display: flex; align-items: center; gap: 10px; margin: 0; padding: 9px 12px; border-radius: 8px; }
.session-note-ic {
  width: 18px; height: 18px; border-radius: 50%; flex: none;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 11px; font-weight: 700; background: rgba(255, 255, 255, .6);
}
.session-note-text { flex: 1; min-width: 0; line-height: 1.5; font-size: 11.5px; }
.session-card { padding: 0; overflow: hidden; }
.session-toolbar { padding: 10px 12px; border-bottom: 1px solid var(--app-border); }
/* 原型工具栏控件高 28px；antd small 默认 24px，这里统一抬到 28 保持节奏。
   输入/选择器的上下 padding 必须归零，否则 28px 高度里只剩 18px 内容区，
   文字下半截会被裁掉（affix 包装器内 input 只占 26px，外面还有 2px 上下边框） */
.session-toolbar :deep(.ant-input) { height: 28px; line-height: 26px; padding-top: 0; padding-bottom: 0; }
.session-toolbar :deep(.ant-input-affix-wrapper) { height: 28px; padding-top: 0; padding-bottom: 0; }
.session-toolbar :deep(.ant-input-affix-wrapper > .ant-input) {
  height: 26px; line-height: 26px; padding-top: 0; padding-bottom: 0;
}
.session-toolbar :deep(.ant-select-single .ant-select-selector) { height: 28px; }
.session-toolbar :deep(.ant-select-single .ant-select-selector .ant-select-selection-item),
.session-toolbar :deep(.ant-select-single .ant-select-selector .ant-select-selection-placeholder) { line-height: 26px; }
.session-search { width: 220px; }
.session-search-ic { color: var(--app-text3); }
.session-filter { width: 130px; flex: none; }
.ops-seg { display: inline-flex; gap: 2px; padding: 2px; background: var(--app-bg, #f7f8fa); border-radius: 7px; }
.ops-seg button {
  border: none; background: transparent; color: var(--app-text2); font-size: 11px;
  padding: 5px 9px; border-radius: 5px; white-space: nowrap; cursor: pointer;
}
.ops-seg button:hover { color: var(--app-text); }
.ops-seg button.active {
  background: var(--app-panel); color: var(--app-text); font-weight: 600;
  box-shadow: 0 1px 2px rgba(16, 24, 40, .06);
}
.batch-kick { height: 28px; padding: 0 12px; }
.batch-kick:disabled { background: transparent; color: var(--app-text3); border-color: var(--app-border); cursor: not-allowed; }
.session-user { display: inline-flex; align-items: center; gap: 9px; min-width: 0; }
.session-user-txt { display: flex; flex-direction: column; gap: 1px; min-width: 0; }
.session-name { color: var(--app-text); font-size: 12px; }
.session-uid { font-size: 10.5px; color: var(--app-text3); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.session-dept { color: var(--app-text2); }
.session-muted { font-size: 11.5px; color: var(--app-text3); }
.session-fail { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; color: var(--app-warn-text, #a3691b); font-weight: 600; }
.session-fail.zero { color: var(--app-text3); font-weight: 400; }
.session-mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 10.5px; color: var(--app-text3); }
.session-foot { font-size: 10px; color: var(--app-text3); padding: 8px 12px; border-top: 1px solid var(--app-border); background: var(--app-panel-2, #fafbfc); line-height: 1.5; }

/* Ant 表格默认样式偏松；收成与原型的巡检列表同一密度：浅表头、细行分隔、小字号 */
.session-table :deep(.ant-table) { font-size: 11.5px; background: transparent; }
.session-table :deep(.ant-table-container) { border: none; }
.session-table :deep(.ant-table-thead > tr > th) {
  background: var(--app-panel-2, #fafbfc); color: var(--app-text3);
  font-size: 10.5px; font-weight: 500; padding: 8px 5px;
  border-bottom: 1px solid var(--app-border);
}
.session-table :deep(.ant-table-thead > tr > th::before) { display: none; }
.session-table :deep(.ant-table-tbody > tr > td) {
  padding: 8px 5px; border-bottom: 1px solid var(--app-border); vertical-align: middle;
}
.session-table :deep(.ant-table-thead > tr > th:first-child),
.session-table :deep(.ant-table-tbody > tr > td:first-child) { padding-left: 14px; }
.session-table :deep(.ant-table-thead > tr > th:last-child),
.session-table :deep(.ant-table-tbody > tr > td:last-child) { padding-right: 14px; }
.session-table :deep(.ant-table-tbody > tr:hover > td) { background: var(--app-accent-weak, #eef2f7); }
.session-table :deep(.ant-table-tbody > tr:last-child > td) { border-bottom: none; }
.session-table :deep(.ant-table-pagination.ant-pagination) { margin: 8px 12px; }

.drawer-title { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.ops-drawer-head { display: flex; align-items: flex-start; gap: 10px; min-width: 0; }
.ops-drawer-eyebrow { font-size: 10px; color: var(--app-text3); letter-spacing: .06em; margin-bottom: 4px; }
.ops-drawer-name { font-size: 15px; font-weight: 600; line-height: 1.35; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ops-drawer-close { margin-left: auto; flex: none; }
.ops-drawer-foot { display: flex; justify-content: flex-end; gap: 8px; }
.drawer-pill { display: flex; align-items: center; gap: 9px; margin-bottom: 14px; }
.drawer-name { font-size: 14px; font-weight: 600; line-height: 1.3; }
.drawer-uid { font-size: 10.5px; color: var(--app-text3); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.drawer-section { margin-bottom: 16px; }
.drawer-section h4 { font-size: 11px; font-weight: 600; color: var(--app-text2); margin: 0 0 7px; }
.drawer-kv { display: grid; grid-template-columns: 78px 1fr; gap: 6px 10px; font-size: 11.5px; }
.drawer-kv .k { color: var(--app-text3); }
.drawer-kv .v { color: var(--app-text); word-break: break-all; }
.drawer-kv .v.drawer-warn { color: var(--app-warn-text, #a3691b); font-weight: 600; }
.drawer-empty { font-size: 11px; color: var(--app-text3); line-height: 1.6; }
.drawer-timeline { display: flex; flex-direction: column; gap: 7px; }
.drawer-event { display: grid; grid-template-columns: 82px 1fr; gap: 2px 8px; font-size: 11px; border-left: 2px solid var(--app-border); padding-left: 8px; }
.event-time { color: var(--app-text3); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 10px; grid-row: 1 / 3; }
.event-action { color: var(--app-text); font-weight: 500; }
.event-detail { color: var(--app-text2); line-height: 1.5; }
.drawer-foot { display: flex; justify-content: flex-end; gap: 8px; padding-top: 6px; }

@media (max-width: 900px) {
  .metric-grid { grid-template-columns: repeat(2, 1fr); }
  .session-toolbar { gap: 6px; }
  .session-search { width: 160px; }
}
@media (max-width: 600px) {
  .metric-grid { grid-template-columns: 1fr; }
  .session-filter { width: 104px; }
}
</style>
