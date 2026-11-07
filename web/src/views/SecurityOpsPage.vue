<template>
  <div class="app-page">
    <div class="app-page-head">
      <div class="head-title">
        <h1 class="app-page-title">安全与运维</h1>
        <span class="head-hint-plain">运行状态 · 访问安全 · 治理通告</span>
      </div>
      <span v-if="report" class="ops-head-last">
        上次巡检 {{ shortTime(report.at) }} · {{ report.tookMs || 0 }}ms
      </span>
      <div class="ops-head-actions">
        <button class="app-btn" type="button" :disabled="running" @click="runCheckup">
          {{ running ? '巡检中…' : '立即巡检' }}
        </button>
        <a-tooltip title="重新读取上一次巡检报告">
          <button class="app-btn ghost" type="button" :disabled="reportLoading" @click="loadLast">
            {{ reportLoading ? '刷新中…' : '刷新' }}
          </button>
        </a-tooltip>
      </div>
    </div>

    <!-- 页签条：与原型一致的自绘 42px Tab，放在页头与滚动区之间，不随内容滚走 -->
    <nav class="ops-tabbar" role="tablist" aria-label="安全与运维页签">
      <button class="ops-tab" :class="{ active: tab === 'checkup' }" type="button" role="tab"
              :aria-selected="tab === 'checkup'" @click="tab = 'checkup'">
        系统巡检 <span v-if="checkupCount" class="ops-tab-count">{{ checkupCount }}</span>
      </button>
      <button class="ops-tab" :class="{ active: tab === 'session' }" type="button" role="tab"
              :aria-selected="tab === 'session'" @click="tab = 'session'">会话与访问</button>
      <button class="ops-tab" :class="{ active: tab === 'announcement' }" type="button" role="tab"
              :aria-selected="tab === 'announcement'" @click="openTab('announcement')">
        平台公告 <span v-if="announcementCount" class="ops-tab-count">{{ announcementCount }}</span>
      </button>
      <button v-if="hasAudit" class="ops-tab" :class="{ active: tab === 'audit' }" type="button" role="tab"
              :aria-selected="tab === 'audit'" @click="tab = 'audit'">审计事件</button>
    </nav>

    <div class="app-page-body ops-body">
      <a-alert v-if="loadError" type="error" show-icon :message="`上次巡检报告读取失败：${loadError}`"
               style="margin-bottom:10px" closable @close="loadError = ''">
        <template #action><a-button size="small" @click="loadLast">重试</a-button></template>
      </a-alert>

      <div class="ops-panes">
        <CheckupPanel v-if="tab === 'checkup'" :report="report" :running="running" :loading="reportLoading"
                      @run="runCheckup" @open-audit="openAudit" />
        <SessionGovernancePanel v-else-if="tab === 'session'" />
        <AnnouncementPanel v-else-if="tab === 'announcement'" />
        <div v-else-if="tab === 'audit' && hasAudit" class="audit-host">
          <AuditLogPage />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { getActiveAnnouncements, getLastOpsCheckup, runOpsCheckup } from '../api'
import { ensureAuth, isAdminSync, menuHasPath } from '../utils/auth'
import CheckupPanel from '../components/ops/CheckupPanel.vue'
import SessionGovernancePanel from '../components/ops/SessionGovernancePanel.vue'
import AnnouncementPanel from '../components/ops/AnnouncementPanel.vue'
import AuditLogPage from './AuditLogPage.vue'

const route = useRoute()
const report = ref(null)
const reportLoading = ref(false)
const running = ref(false)
const loadError = ref('')
const hasAudit = ref(isAdminSync())
const tab = ref('checkup')
const checkupCount = computed(() => (report.value?.checks || []).length)
const announcementCount = ref(0)

async function refreshAnnouncementCount () {
  try {
    const r = await getActiveAnnouncements()
    if (r.success) announcementCount.value = (r.data || []).length
  } catch (e) { /* 公告计数拿不到不影响页签 */ }
}
function openTab (key) {
  tab.value = key
  if (key === 'announcement') refreshAnnouncementCount()
}

const shortTime = s => (s ? String(s).replace('T', ' ').slice(5, 19) : '—')

async function loadLast () {
  reportLoading.value = true
  loadError.value = ''
  try {
    const r = await getLastOpsCheckup()
    if (r.success) report.value = r.data || null
    else loadError.value = r.msg || '接口返回失败'
  } catch (e) {
    loadError.value = e.message || '网络错误'
  } finally { reportLoading.value = false }
}

async function runCheckup () {
  if (running.value) return
  running.value = true
  try {
    const r = await runOpsCheckup()
    if (r.success) {
      report.value = r.data || null
      message.success('巡检完成')
    } else message.error(r.msg || '巡检失败')
  } catch (e) {
    message.error(e.message || '巡检失败')
  } finally { running.value = false }
}

function openAudit () {
  if (hasAudit.value) tab.value = 'audit'
}

onMounted(async () => {
  // 页签权限：audit 是独立 Tab 菜单（/security?tab=audit）；管理员直通，其余按菜单绑定
  try {
    const info = await ensureAuth()
    hasAudit.value = Boolean(info && (info.admin || isAdminSync()
      || menuHasPath('/security?tab=audit')))
    const q = String(route.query.tab || '')
    const allowed = ['checkup', 'session', 'announcement'].concat(hasAudit.value ? ['audit'] : [])
    tab.value = allowed.includes(q) ? q : 'checkup'
  } catch (e) {
    hasAudit.value = isAdminSync()
  }
  loadLast()
  refreshAnnouncementCount()
})
</script>

<style scoped>
/* 页头按原型 54px 定高；页签条单独占 42px，内容区 14/20/18 内边距 */
.app-page-head { height: 54px; padding: 0 20px; }
.head-title { display: flex; align-items: baseline; gap: 10px; min-width: 0; }
.head-title .head-hint-plain { font-size: 11.5px; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ops-body { padding: 14px 20px 18px; }
.ops-tabbar {
  flex: none;
  height: 42px;
  display: flex;
  align-items: stretch;
  gap: 2px;
  padding: 0 20px;
  background: var(--app-panel);
  border-bottom: 1px solid var(--app-border);
}
.ops-tab {
  position: relative;
  border: none;
  background: transparent;
  color: var(--app-text2);
  font-size: 12.5px;
  padding: 0 14px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border-bottom: 2px solid transparent;
  cursor: pointer;
}
.ops-tab:hover { color: var(--app-text); }
.ops-tab.active { color: var(--app-accent); border-bottom-color: var(--app-accent); font-weight: 600; }
.ops-tab-count {
  font-size: 10px;
  color: var(--app-text3);
  background: var(--app-panel-2, #fafbfc);
  border-radius: 999px;
  padding: 1px 6px;
}
.ops-tab.active .ops-tab-count { background: var(--app-accent-weak, #eef2f7); color: var(--app-accent); }
.ops-panes { min-height: 0; }
.ops-head-last { font-size: 11px; color: var(--app-text3); white-space: nowrap; }
.ops-head-actions { margin-left: auto; display: flex; align-items: center; gap: 10px; flex: none; }
.audit-host { height: calc(100vh - 128px); min-height: 420px; display: flex; }
.audit-host :deep(.al-wrap) { padding: 0; }
@media (max-width: 900px) {
  .ops-head-last { display: none; }
  .audit-host { height: auto; min-height: 480px; }
}
</style>
