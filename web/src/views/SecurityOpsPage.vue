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

          <a-table v-if="report" :data-source="report.checks" row-key="key" :pagination="false" size="middle">
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
            平台的会话治理只有一种判据：<b>会话代数</b>。本人改密码、管理员重置密码、强制下线都会把代数 +1，
            该账号已签发的全部令牌（所有设备）当场失效——账号被盗、离职即踢、改完密码怎么还在线，答案都是同一个。
            令牌本身无状态（不查库），代数比对挂在每请求必查的用户档案上，代价为零。
          </div>
          <div class="kick-box">
            <a-select v-model:value="kickUid" show-search option-filter-prop="label"
                      :options="memberOptions" placeholder="选择要强制下线的成员" style="width:320px" />
            <a-popconfirm :title="`强制下线 ${kickUid || '该成员'}？其所有设备的登录态将立即失效`"
                          ok-text="强制下线" cancel-text="取消" @confirm="doForceLogout"
                          :disabled="!kickUid">
              <a-button type="primary" danger :disabled="!kickUid" :loading="kicking">强制下线</a-button>
            </a-popconfirm>
          </div>
          <a-alert type="info" show-icon style="margin-top:12px"
                   message="相关动作会自动触发下线：本人修改密码、管理员重置密码（同时清除登录锁定）。无需再单独强制下线。" />
        </a-tab-pane>

        <!-- ==================== 平台公告 ==================== -->
        <a-tab-pane key="announcement" tab="平台公告">
          <div class="checkup-head">
            <span class="form-tip" style="margin:0">停机窗口 / 升级 / 新功能通知：按租户发布，登录后横幅常显，直到下线。下线保留历史，可恢复。</span>
            <button class="app-btn" style="margin-left:auto" @click="openAnnouncement()">
              <plus-outlined /> 发布公告
            </button>
          </div>
          <a-table :data-source="announcements" row-key="id" :pagination="false" size="middle">
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
import { message } from 'ant-design-vue'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import {
  runOpsCheckup, getLastOpsCheckup, forceLogoutUser,
  listAnnouncements, saveAnnouncement, deleteAnnouncement,
  listUsers, listTenants
} from '../api'
import { isSuperadminSync } from '../utils/auth'

const superadmin = isSuperadminSync()
const tab = ref('checkup')
const saving = ref(false)

// ==================== 系统巡检 ====================
const report = ref(null)
const running = ref(false)
async function runCheckup () {
  running.value = true
  try {
    const r = await runOpsCheckup()
    if (r.success) { report.value = r.data; message.success('巡检完成：' + (r.data.overall === 'ok' ? '整体健康' : '有需关注项，见下')) }
    else message.error(r.msg || '巡检失败')
  } catch (e) { message.error(e.message || '巡检失败') }
  finally { running.value = false }
}

// ==================== 会话治理 ====================
const memberOptions = ref([])
const kickUid = ref(undefined)
const kicking = ref(false)
async function loadMembers () {
  try {
    const r = await listUsers()
    if (r.success) memberOptions.value = (r.data || []).map(x => ({ value: x.uid, label: (x.username || x.uid) + '（' + x.uid + '）' }))
  } catch (e) { message.error(e.message || '成员名单加载失败') }
}
async function doForceLogout () {
  if (!kickUid.value) return
  kicking.value = true
  try {
    const r = await forceLogoutUser(kickUid.value)
    if (r.success) { message.success('已强制下线：' + kickUid.value); kickUid.value = undefined }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
  finally { kicking.value = false }
}

// ==================== 平台公告 ====================
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
  loadMembers()
  loadAnnouncements()
})
</script>

<style scoped>
.form-tip { font-size: 12px; color: var(--app-text-3, #888); line-height: 1.7; }
.checkup-head { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.checkup-meta { font-size: 12px; color: var(--app-text-3, #888); }
.kick-box { display: flex; gap: 12px; align-items: center; margin-top: 14px; }
.ann-content { font-size: 12px; color: var(--app-text-3, #999); margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ann-tenant { font-size: 12px; color: var(--app-text-2, #666); }
</style>
