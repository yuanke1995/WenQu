<template>
  <div class="app-page">
    <div class="app-page-head">
      <h1 class="app-page-title sr-only">安全与运维</h1>
      <span class="head-count">运行状态 · 会话 · 通告</span>
      <span class="head-hint-plain">巡检与会话动作都落审计；公告按租户发布，登录后横幅常显</span>
    </div>

    <div class="app-page-body">
      <a-tabs v-model:activeKey="tab">
        <!-- ==================== 系统巡检 ==================== -->
        <a-tab-pane key="checkup" tab="系统巡检">
          <div class="checkup-head">
            <button class="app-btn" :disabled="running" @click="runCheckup">
              <span v-if="running">巡检中…</span>
              <template v-else><reload-outlined /> 立即巡检</template>
            </button>
            <span v-if="report" class="checkup-meta">
              上次巡检 {{ (report.at || '').replace('T', ' ').slice(0, 19) }} · 耗时 {{ report.tookMs }}ms
            </span>
            <span v-else class="checkup-meta">本进程内还未巡检过（后端重启后报告不保留，不假装记得）</span>
          </div>

          <a-alert v-if="report" :type="report.overall === 'ok' ? 'success' : report.overall === 'warn' ? 'warning' : 'error'"
                   show-icon style="margin-bottom:12px"
                   :message="report.overall === 'ok' ? '整体健康' : report.overall === 'warn' ? '整体可用，有要留意的事项' : '有必须处理的问题'" />

          <a-table v-if="report" :columns="checkCols" :data-source="report.checks" row-key="key"
                   :pagination="false" size="middle">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'level'">
                <a-tag :color="record.level === 'ok' ? 'green' : record.level === 'warn' ? 'orange' : 'red'">
                  {{ { ok: '正常', warn: '留意', fail: '必须处理' }[record.level] || record.level }}
                </a-tag>
              </template>
              <template v-else-if="column.key === 'ms'">{{ record.ms }}ms</template>
            </template>
          </a-table>
          <a-empty v-else description="点击「立即巡检」对运行状态做一次全面体检（各巡检项互相独立，一项挂不影响其余出结果）" />
        </a-tab-pane>

        <!-- ==================== 会话治理 ==================== -->
        <a-tab-pane key="session" tab="会话治理">
          <div class="form-tip" style="margin-top:0">
            <b>强制下线</b>＝该账号已签发的全部登录态（所有设备）当场失效，含此刻没在发请求的旧令牌；
            本人改密码、管理员重置密码会自动做同样的事，不必再来点一次。
          </div>

          <div class="kick-toolbar">
            <a-input v-model:value="kickKw" allow-clear size="small" class="kick-search"
                     placeholder="搜索昵称 / 登录账号" @change="onKickKw">
              <template #prefix><search-outlined class="kick-search-ic" /></template>
            </a-input>
            <a-select v-model:value="kickDept" allow-clear size="small" class="kick-filter"
                      placeholder="全部部门" :options="deptOptions" @change="onKickFilter" />
            <a-select v-model:value="kickStatus" allow-clear size="small" class="kick-filter"
                      placeholder="全部状态" :options="kickStatusOptions" @change="onKickFilter" />
            <span class="kick-meta">共 {{ kickTotal }} 人 · 按最近登录排序</span>
          </div>

          <!-- 取数失败单独露：不能让它塌成「没有这个人」的空表 -->
          <a-alert v-if="kickError" type="error" show-icon style="margin:10px 0"
                   :message="`成员名单拉取失败：${kickError}`">
            <template #action>
              <a-button size="small" :loading="kickLoading" @click="loadKickPage()">重试</a-button>
            </template>
          </a-alert>

          <a-table :columns="kickCols" :data-source="kickRows" row-key="uid" size="middle"
                   table-layout="fixed" :loading="kickLoading"
                   :pagination="{ current: kickPage, pageSize: kickSize, total: kickTotal,
                                  onChange: onKickPage, size: 'small', showSizeChanger: false,
                                  showTotal: t => `共 ${t} 人` }">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'member'">
                <div class="kick-user">
                  <UserAvatar :avatar="record.avatar" :name="record.username" :size="26" />
                  <div class="kick-user-txt">
                    <span class="kick-name">{{ record.username || '（未命名）' }}</span>
                    <code class="kick-uid">{{ record.uid }}</code>
                  </div>
                </div>
              </template>
              <template v-else-if="column.key === 'department'">
                <span v-if="record.departmentId" class="kick-dept">{{ deptName(record.departmentId) }}</span>
                <span v-else class="kick-none">未分配</span>
              </template>
              <template v-else-if="column.key === 'lastLoginTime'">
                <span v-if="record.lastLoginTime" :title="fullTime(record.lastLoginTime)">{{ fmtTime(record.lastLoginTime) }}</span>
                <span v-else class="kick-none">从未登录</span>
              </template>
              <template v-else-if="column.key === 'status'">
                <span class="app-pill" :class="record.status === 0 ? 'warn' : 'ok'">
                  {{ record.status === 0 ? '已禁用' : '启用' }}
                </span>
              </template>
              <template v-else-if="column.key === 'action'">
                <a-button type="link" size="small" danger @click="confirmKick(record)">强制下线</a-button>
              </template>
            </template>
          </a-table>
        </a-tab-pane>

        <!-- ==================== 平台公告 ==================== -->
        <a-tab-pane key="announcement" tab="平台公告">
          <div class="checkup-head">
            <span class="form-tip" style="margin:0">停机窗口 / 升级 / 新功能通知：按租户发布，登录后横幅常显，直到下线。下线保留历史，可恢复。</span>
            <button class="app-btn" style="margin-left:auto" @click="openAnnouncement()">
              <plus-outlined /> 发布公告
            </button>
          </div>
          <a-table :columns="annCols" :data-source="announcements" row-key="id" :pagination="false" size="middle">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'title'">
                <div>{{ record.title }}</div>
                <div class="ann-content">{{ record.content || '' }}</div>
              </template>
              <template v-else-if="column.key === 'level'">
                <a-tag :color="{ info: 'blue', warn: 'orange', danger: 'red' }[record.level] || 'blue'">
                  {{ { info: '通知', warn: '警示', danger: '紧急' }[record.level] || record.level }}
                </a-tag>
              </template>
              <template v-else-if="column.key === 'enabled'">
                <a-tag :color="record.enabled === 1 ? 'green' : 'default'">{{ record.enabled === 1 ? '发布中' : '已下线' }}</a-tag>
              </template>
              <template v-else-if="column.key === 'tenant'">
                <span class="ann-tenant">{{ tenantName(record.tenantId) }}</span>
              </template>
              <template v-else-if="column.key === 'action'">
                <a-space>
                  <a-button type="link" size="small" @click="openAnnouncement(record)">编辑</a-button>
                  <a-popconfirm v-if="record.enabled === 1" title="下线该公告？（保留历史，可再编辑重新发布）"
                                ok-text="下线" cancel-text="取消" @confirm="onDisableAnnouncement(record)">
                    <a-button type="link" size="small" danger>下线</a-button>
                  </a-popconfirm>
                </a-space>
              </template>
            </template>
          </a-table>

          <a-modal v-model:open="annOpen" :title="annForm.id ? '编辑公告' : '发布公告'" @ok="submitAnnouncement" :confirm-loading="saving">
            <a-form layout="vertical">
              <a-form-item v-if="superadmin" label="发布租户" extra="平台级管理员可发到任一租户；租户管理员固定本租户">
                <a-select v-model:value="annForm.tenantId" :options="tenantOptions" style="width:100%" />
              </a-form-item>
              <a-form-item label="级别">
                <a-segmented v-model:value="annForm.level" :options="[
                  { value: 'info', label: '通知' }, { value: 'warn', label: '警示' }, { value: 'danger', label: '紧急' }]" />
              </a-form-item>
              <a-form-item label="标题" required>
                <a-input v-model:value="annForm.title" :maxlength="200" placeholder="如：周六 22:00-24:00 停机维护" />
              </a-form-item>
              <a-form-item label="正文" extra="停机窗口、影响范围、联系人等；留空则只有横幅标题">
                <a-textarea v-model:value="annForm.content" :rows="4" :maxlength="2000" />
              </a-form-item>
              <a-form-item label="立即发布">
                <a-switch v-model:checked="annEnabled" />
              </a-form-item>
            </a-form>
          </a-modal>
        </a-tab-pane>
      </a-tabs>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import {
  runOpsCheckup, getLastOpsCheckup, forceLogoutUser,
  listAnnouncements, saveAnnouncement, deleteAnnouncement,
  pageUsers, listDepartments, listTenants
} from '../api'
import UserAvatar from '../components/UserAvatar.vue'
import { isSuperadminSync } from '../utils/auth'

const superadmin = isSuperadminSync()
const tab = ref('checkup')
const saving = ref(false)

// ==================== 系统巡检 ====================
const checkCols = [
  { title: '巡检项', dataIndex: 'name', key: 'name', width: 150 },
  { title: '级别', key: 'level', width: 100 },
  { title: '说明', dataIndex: 'detail', key: 'detail' },
  { title: '耗时', key: 'ms', width: 88, align: 'right' }
]
const report = ref(null)
const running = ref(false)
async function runCheckup () {
  running.value = true
  try {
    const r = await runOpsCheckup()
    if (r.success) report.value = r.data
    else message.error(r.msg || '巡检失败')
  } catch (e) { message.error(e.message || '巡检失败') }
  finally { running.value = false }
}

// ==================== 会话治理 ====================
const kickCols = [
  { title: '成员', key: 'member' },
  { title: '部门', key: 'department', width: 150 },
  { title: '最近登录', key: 'lastLoginTime', width: 150 },
  { title: '状态', key: 'status', width: 90 },
  { title: '操作', key: 'action', width: 110 }
]
const kickStatusOptions = [{ value: 1, label: '启用中' }, { value: 0, label: '已禁用' }]
const kickRows = ref([])
const kickTotal = ref(0)
const kickPage = ref(1)
const kickSize = ref(20)
const kickLoading = ref(false)
const kickError = ref('')
const kickKw = ref('')
const kickDept = ref(undefined)
const kickStatus = ref(undefined)

const departments = ref([])
const deptOptions = computed(() => departments.value.map(d => ({ label: d.name, value: d.id })))
const deptName = id => (departments.value.find(d => d.id === id) || {}).name || '—'
const fmtTime = s => (s ? String(s).replace('T', ' ').slice(0, 16) : '')
const fullTime = s => (s ? String(s).replace('T', ' ') : '')

async function loadKickPage () {
  kickLoading.value = true
  kickError.value = ''
  try {
    const r = await pageUsers({ kw: kickKw.value, departmentId: kickDept.value,
      status: kickStatus.value, page: kickPage.value, size: kickSize.value })
    if (r.success) {
      kickRows.value = r.data.items || []
      kickTotal.value = r.data.total || 0
      kickPage.value = r.data.page || 1
    } else {
      kickRows.value = []
      kickTotal.value = 0
      kickError.value = r.msg || '接口返回失败'
    }
  } catch (e) {
    kickRows.value = []
    kickTotal.value = 0
    kickError.value = e.message || '网络错误'
  } finally { kickLoading.value = false }
}
// 关键字防抖：名单是库侧过滤，逐键打库在万级成员下等于自己发起一次压测
let kickTimer = null
function onKickKw () {
  clearTimeout(kickTimer)
  kickTimer = setTimeout(() => { kickPage.value = 1; loadKickPage() }, 300)
}
function onKickFilter () { kickPage.value = 1; loadKickPage() }
function onKickPage (p, s) { kickPage.value = p; kickSize.value = s; loadKickPage() }
function confirmKick (record) {
  Modal.confirm({
    title: `强制下线 ${record.username || record.uid}？`,
    content: '该账号所有设备的登录态立即失效，需重新登录。此动作落审计。',
    okText: '强制下线', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      try {
        const r = await forceLogoutUser(record.uid)
        if (r.success) { message.success(`已强制下线：${record.uid}`); await loadKickPage() }
        else message.error(r.msg || '操作失败')
      } catch (e) { message.error(e.message || '操作失败') }
    }
  })
}
async function loadDepartments () {
  try {
    const r = await listDepartments()
    if (r.success) departments.value = r.data || []
  } catch (e) { message.error(e.message || '部门名单加载失败') }
}

// ==================== 平台公告 ====================
const annCols = [
  { title: '标题', key: 'title' },
  { title: '级别', key: 'level', width: 84 },
  ...(superadmin ? [{ title: '租户', key: 'tenant', width: 130 }] : []),
  { title: '状态', key: 'enabled', width: 88 },
  { title: '操作', key: 'action', width: 120 }
]
const announcements = ref([])
const tenantOptions = ref([])
const annOpen = ref(false)
const annEnabled = ref(true)
const annForm = ref({ id: '', title: '', content: '', level: 'info', tenantId: undefined })
const tenantName = id => (tenantOptions.value.find(t => t.value === (id || 'default')) || {}).label || id || 'default'

async function loadAnnouncements () {
  try {
    const [r, rt] = await Promise.all([listAnnouncements(), superadmin ? listTenants() : Promise.resolve(null)])
    if (r.success) announcements.value = r.data || []
    else message.error(r.msg || '公告加载失败')
    if (rt && rt.success) tenantOptions.value = (rt.data || []).map(t => ({ value: t.id, label: t.name || t.id }))
  } catch (e) { message.error(e.message || '公告加载失败') }
}
function openAnnouncement (record) {
  annForm.value = record
    ? { id: record.id, title: record.title, content: record.content || '', level: record.level || 'info', tenantId: record.tenantId || 'default' }
    : { id: '', title: '', content: '', level: 'info', tenantId: superadmin ? undefined : undefined }
  annEnabled.value = record ? record.enabled === 1 : true
  annOpen.value = true
}
async function submitAnnouncement () {
  const f = annForm.value
  if (!f.title || !f.title.trim()) { message.error('要给公告起个标题'); return }
  saving.value = true
  try {
    const r = await saveAnnouncement({ id: f.id || undefined, title: f.title, content: f.content,
      level: f.level, enabled: annEnabled.value ? 1 : 0, tenantId: superadmin ? f.tenantId : undefined })
    if (r.success) { message.success('已保存'); annOpen.value = false; await loadAnnouncements() }
    else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}
async function onDisableAnnouncement (a) {
  try {
    const r = await deleteAnnouncement(a.id)
    if (r.success) { message.success('已下线'); await loadAnnouncements() }
    else message.error(r.msg || '下线失败')
  } catch (e) { message.error(e.message || '下线失败') }
}

onMounted(() => {
  runCheckup()          // 进页先看一眼当前状态（最后一次报告）
  loadKickPage()
  loadDepartments()
  loadAnnouncements()
})
</script>

<style scoped>
.form-tip { font-size: 12px; color: var(--app-text-3, #888); line-height: 1.7; }
.checkup-head { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.checkup-meta { font-size: 12px; color: var(--app-text-3, #888); }
.kick-toolbar { display: flex; align-items: center; gap: 10px; margin: 12px 0 8px; }
.kick-search { width: 220px; }
.kick-search-ic { color: var(--app-text3); }
.kick-filter { width: 130px; flex: none; }
.kick-filter .ant-select-selector { font-size: 12px; }
.kick-meta { margin-left: auto; font-size: 12px; color: var(--app-text3); }
.kick-user { display: inline-flex; align-items: center; gap: 9px; min-width: 0; }
.kick-user-txt { display: flex; flex-direction: column; gap: 1px; min-width: 0; }
.kick-name { color: var(--app-text); }
.kick-uid { font-size: 11px; color: var(--app-text3); }
.kick-dept { color: var(--app-text2); }
.kick-none { font-size: 12px; color: var(--app-text3); }
.ann-content { font-size: 12px; color: var(--app-text-3, #999); margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ann-tenant { font-size: 12px; color: var(--app-text-2, #666); }
</style>
