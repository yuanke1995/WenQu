<template>
  <div class="app-page">
    <div class="app-page-head">
      <h1 class="app-page-title sr-only">租户与开通</h1>
      <span class="head-count">共 <b>{{ tenants.length }}</b> 个租户</span>
      <span class="head-hint-plain">{{ superadmin
        ? '开通/启停/划人与审批是平台级动作；落点清单是备份恢复的取数依据'
        : '租户级管理员只看本租户；开通/启停/审批由平台级管理员处理' }}</span>
      <button v-if="superadmin" class="app-btn" style="margin-left:auto" @click="openCreate">
        <plus-outlined /> 直开租户
      </button>
    </div>

    <div class="app-page-body">
      <a-tabs v-model:activeKey="tab">
        <!-- ==================== 租户清单 ==================== -->
        <a-tab-pane key="tenants" tab="租户">
          <a-spin :spinning="loading">
            <a-table v-if="tenants.length" :data-source="tenants" :columns="tenantCols" row-key="id"
                     :pagination="false" size="middle" table-layout="fixed">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'id'"><code>{{ record.id }}</code></template>
                <template v-else-if="column.key === 'status'">
                  <template v-if="superadmin">
                    <a-switch :checked="record.status === 1" size="small"
                              :disabled="record.id === 'default'"
                              @change="v => onToggleTenant(record, v)" />
                    <span v-if="record.id === 'default'" class="hint-inline">默认租户不可停用</span>
                  </template>
                  <a-tag v-else :color="record.status === 1 ? 'green' : 'red'">
                    {{ record.status === 1 ? '启用' : '停用' }}
                  </a-tag>
                </template>
                <template v-else-if="column.key === 'contact'">{{ record.contact || '—' }}</template>
                <template v-else-if="column.key === 'members'">{{ record.members }} 人</template>
                <template v-else-if="column.key === 'note'">
                  <span class="tenant-note">{{ record.note || '—' }}</span>
                </template>
                <template v-else-if="column.key === 'action'">
                  <a-space>
                    <a-button type="link" size="small" @click="openInventory(record)">落点清单</a-button>
                  </a-space>
                </template>
              </template>
            </a-table>
            <a-empty v-else description="还没有租户（单租户部署只有 default 一个）" />
            <div class="form-tip" style="margin-top:8px">
              停用只挡该租户成员登录，数据一律保留；没有删除租户的按钮——行级隔离下删租户等于按归属删光它的全部数据。
            </div>
          </a-spin>
        </a-tab-pane>

        <!-- ==================== 自助开通申请 ==================== -->
        <a-tab-pane key="requests">
          <template #tab>开通申请<span v-if="pendingCount" class="tab-badge">{{ pendingCount }}</span></template>
          <a-spin :spinning="reqLoading">
            <div class="req-filter">
              <a-segmented v-model:value="reqFilter" :options="[
                { value: 'all', label: '全部' }, { value: 'pending', label: '待审' },
                { value: 'approved', label: '已批准' }, { value: 'rejected', label: '已驳回' }]" />
            </div>
            <a-table v-if="superadmin && filteredRequests.length" :data-source="filteredRequests"
                     :columns="reqCols" row-key="id" :pagination="false" size="middle" table-layout="fixed">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'orgName'">
                  <div>{{ record.orgName }}</div>
                  <div class="req-purpose">{{ record.purpose }}</div>
                </template>
                <template v-else-if="column.key === 'desiredId'"><code>{{ record.desiredId || '（未填）' }}</code></template>
                <template v-else-if="column.key === 'contactName'">
                  <div>{{ record.contactName }}</div>
                  <div class="req-purpose">{{ record.contactEmail }}</div>
                </template>
                <template v-else-if="column.key === 'status'">
                  <a-tag :color="reqStatusColor(record.status)">{{ reqStatusLabel(record.status) }}</a-tag>
                  <div v-if="record.status !== 'pending'" class="req-purpose">{{ record.reviewer }} · {{ record.reviewNote || '' }}</div>
                </template>
                <template v-else-if="column.key === 'action'">
                  <a-space v-if="record.status === 'pending'">
                    <a-button type="link" size="small" @click="openApprove(record)">批准</a-button>
                    <a-button type="link" size="small" danger @click="openReject(record)">驳回</a-button>
                  </a-space>
                  <span v-else class="metric-sub">已处理</span>
                </template>
              </template>
            </a-table>
            <a-empty v-else-if="superadmin" description="暂无开通申请（公开表单在 /apply，提交后到这里审批）" />
            <a-empty v-else description="开通申请仅平台级管理员可查看与处理" />
          </a-spin>
        </a-tab-pane>
      </a-tabs>
    </div>

    <!-- 直开租户 -->
    <a-modal v-model:open="createOpen" title="直开租户" :confirm-loading="saving" @ok="submitCreate">
      <a-form layout="vertical" autocomplete="off">
        <a-form-item label="租户标识" required
                     extra="2-32 位小写字母、数字、- 或 _；直接作为数据归属标识（tenant_id），开通后不可改">
          <a-input v-model:value="createForm.id" placeholder="如 huazhang" />
        </a-form-item>
        <a-form-item label="显示名" required><a-input v-model:value="createForm.name" placeholder="如 华章智造集团" /></a-form-item>
        <a-form-item label="联系人"><a-input v-model:value="createForm.contact" /></a-form-item>
        <a-form-item label="备注"><a-textarea v-model:value="createForm.note" :rows="2" placeholder="这个租户是谁、怎么开通的（审计要看）" /></a-form-item>
      </a-form>
    </a-modal>

    <!-- 批准开通 -->
    <a-modal v-model:open="approveOpen" title="批准开通申请" :confirm-loading="saving" @ok="submitApprove">
      <a-form layout="vertical" autocomplete="off">
        <a-form-item label="申请机构">
          <span>{{ approving.orgName }} · {{ approving.contactName }}（{{ approving.contactEmail }}）</span>
          <div class="form-tip">{{ approving.purpose }}</div>
        </a-form-item>
        <a-form-item label="租户标识" required
                     extra="申请人已填期望标识则默认它；标识直接作为数据归属，开通后不可改">
          <a-input v-model:value="approveForm.tenantId" placeholder="2-32 位小写字母、数字、- 或 _" />
        </a-form-item>
        <div class="form-tip">
          批准＝开一家新客户：建租户 + 建首位管理员账号（标识「租户-admin」，随机口令只在这次结果里出现一次）。
        </div>
      </a-form>
    </a-modal>

    <!-- 批准结果：口令只显示这一次 -->
    <a-modal v-model:open="approveResultOpen" title="已开通（请把口令交给对方）" :footer="null" width="520">
      <a-alert type="warning" show-icon message="随机口令只显示这一次，关闭后无法再次查看" style="margin-bottom:12px" />
      <a-descriptions :column="1" size="small" bordered>
        <a-descriptions-item label="租户标识"><code>{{ approveResult.tenantId }}</code></a-descriptions-item>
        <a-descriptions-item label="首位管理员"><code>{{ approveResult.adminUid }}</code></a-descriptions-item>
        <a-descriptions-item label="初始口令"><code>{{ approveResult.password }}</code></a-descriptions-item>
      </a-descriptions>
      <div class="form-tip" style="margin-top:8px">{{ approveResult.note }}</div>
    </a-modal>

    <!-- 驳回 -->
    <a-modal v-model:open="rejectOpen" title="驳回开通申请" :confirm-loading="saving" @ok="submitReject">
      <a-form layout="vertical">
        <a-form-item label="驳回说明" extra="会留给申请人看；驳回不动任何数据">
          <a-textarea v-model:value="rejectForm.note" :rows="3"
                      placeholder="如：主体资料不全，请补充营业执照后重新提交" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 落点清单（备份与恢复的取数依据） -->
    <a-drawer :open="inventoryOpen" :title="`落点清单 — ${inventoryTenant ? inventoryTenant.name : ''}`"
              width="680" @close="inventoryOpen = false">
      <a-spin :spinning="invLoading">
        <template v-if="inventory">
          <div class="inv-overview">
            <div class="inv-cell"><div class="stat-num">{{ inventory.members.length }}</div><div class="stat-label">成员</div></div>
            <div class="inv-cell"><div class="stat-num">{{ inventory.kbCount ?? '—' }}</div><div class="stat-label">知识库</div></div>
            <div class="inv-cell"><div class="stat-num">{{ inventory.rows }}</div><div class="stat-label">库内行数</div></div>
            <div class="inv-cell"><div class="stat-num">{{ fmtBytes(inventory.filesBytes) }}</div><div class="stat-label">磁盘占用</div></div>
            <div class="inv-cell" v-if="inventory.filesMissing"><div class="stat-num" style="color:#d4380d">{{ inventory.filesMissing }}</div><div class="stat-label">目录缺失</div></div>
          </div>

          <a-divider orientation="left" class="inv-divider">库内落点（{{ inventory.tables.length }} 张表）</a-divider>
          <div class="inv-tables">
            <div v-for="t in inventory.tables" :key="t.table" class="inv-table-row">
              <code>{{ t.table }}</code><span class="inv-rows">{{ t.rows }} 行</span>
            </div>
          </div>

          <a-divider orientation="left" class="inv-divider">磁盘目录</a-divider>
          <div class="inv-tables">
            <div v-for="f in inventory.files" :key="f.path" class="inv-table-row">
              <span class="inv-kind">{{ f.kind }}</span>
              <span class="inv-path" :title="f.path">{{ f.path }}</span>
              <a-tag v-if="!f.exists" color="red">缺失</a-tag>
              <span class="inv-rows">{{ fmtBytes(f.bytes) }}</span>
            </div>
          </div>

          <a-divider orientation="left" class="inv-divider">Redis 键形态</a-divider>
          <div style="margin-bottom:8px">
            <a-tag v-for="k in inventory.redisKeyPatterns || []" :key="k" class="inv-key"><code>{{ k }}</code></a-tag>
            <span v-if="!(inventory.redisKeyPatterns || []).length" class="metric-sub">无</span>
          </div>

          <a-divider orientation="left" class="inv-divider">不随租户走的全局部分</a-divider>
          <ul class="inv-global">
            <li v-for="(g, i) in inventory.notInTenantScope || []" :key="i">{{ g }}</li>
          </ul>
        </template>
      </a-spin>
    </a-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import {
  listTenants, createTenant, setTenantStatus,
  listTenantRequests, approveTenantRequest, rejectTenantRequest, getTenantInventory
} from '../api'
import { isSuperadminSync } from '../utils/auth'

// 平台级判据在后端（requirePlatform），这里只管把不够格的按钮藏起来——藏不住的请求服务端也会拒
const superadmin = isSuperadminSync()
const tab = ref('tenants')

const tenants = ref([])
const requests = ref([])
const loading = ref(false)
const reqLoading = ref(false)
const saving = ref(false)

// ==================== 租户清单 ====================
const tenantCols = [
  { title: '标识', key: 'id', dataIndex: 'id', width: 130 },
  { title: '名称', key: 'name', dataIndex: 'name', width: 170 },
  { title: '状态', key: 'status', dataIndex: 'status', width: 170 },
  { title: '联系人', key: 'contact', dataIndex: 'contact', width: 110 },
  { title: '成员', key: 'members', dataIndex: 'members', width: 76 },
  { title: '备注', key: 'note', dataIndex: 'note', ellipsis: true },
  { title: '操作', key: 'action', width: 110 }
]

async function load () {
  loading.value = true
  try {
    const r = await listTenants()
    if (r.success) tenants.value = r.data || []
    else message.error(r.msg || '租户清单加载失败')
  } catch (e) { message.error(e.message || '租户清单加载失败') }
  finally { loading.value = false }
}

async function onToggleTenant (t, v) {
  try {
    const r = await setTenantStatus(t.id, v)
    if (r.success) { t.status = v ? 1 : 0; message.success(v ? '已启用' : '已停用（数据保留，成员登录被挡）') }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}

// ==================== 直开租户 ====================
const createOpen = ref(false)
const createForm = ref({ id: '', name: '', contact: '', note: '' })
function openCreate () {
  createForm.value = { id: '', name: '', contact: '', note: '' }
  createOpen.value = true
}
async function submitCreate () {
  const f = createForm.value
  if (!/^[a-z0-9_-]{2,32}$/.test((f.id || '').trim())) { message.error('租户标识要用 2-32 位小写字母、数字、- 或 _'); return }
  if (!f.name || !f.name.trim()) { message.error('要给租户起个显示名'); return }
  saving.value = true
  try {
    const r = await createTenant({ id: f.id.trim(), name: f.name.trim(), contact: f.contact, note: f.note })
    if (r.success) { message.success('已开通租户（空租户：成员从成员管理里归属过来）'); createOpen.value = false; await load() }
    else message.error(r.msg || '开通失败')
  } catch (e) { message.error(e.message || '开通失败') }
  finally { saving.value = false }
}

// ==================== 开通申请 ====================
const reqFilter = ref('all')
const reqCols = [
  { title: '申请机构', key: 'orgName', dataIndex: 'orgName', width: 220 },
  { title: '期望标识', key: 'desiredId', dataIndex: 'desiredId', width: 120 },
  { title: '联系人', key: 'contactName', dataIndex: 'contactName', width: 180 },
  { title: '状态', key: 'status', dataIndex: 'status', width: 190 },
  { title: '操作', key: 'action', width: 130 }
]
const filteredRequests = computed(() =>
  reqFilter.value === 'all' ? requests.value : requests.value.filter(r => r.status === reqFilter.value))
const pendingCount = computed(() => requests.value.filter(r => r.status === 'pending').length)
const reqStatusLabel = s => ({ pending: '待审', approved: '已批准', rejected: '已驳回' }[s] || s)
const reqStatusColor = s => ({ pending: 'gold', approved: 'green', rejected: 'red' }[s] || 'default')

async function loadRequests () {
  if (!superadmin) return
  reqLoading.value = true
  try {
    const r = await listTenantRequests()
    if (r.success) requests.value = r.data || []
    else message.error(r.msg || '开通申请加载失败')
  } catch (e) { message.error(e.message || '开通申请加载失败') }
  finally { reqLoading.value = false }
}

// ---- 批准 ----
const approveOpen = ref(false)
const approveResultOpen = ref(false)
const approving = ref({})
const approveForm = ref({ tenantId: '' })
const approveResult = ref({})
function openApprove (req) {
  approving.value = req
  approveForm.value = { tenantId: req.desiredId || '' }
  approveOpen.value = true
}
async function submitApprove () {
  const tid = (approveForm.value.tenantId || '').trim()
  if (!/^[a-z0-9_-]{2,32}$/.test(tid)) { message.error('租户标识要用 2-32 位小写字母、数字、- 或 _'); return }
  saving.value = true
  try {
    const r = await approveTenantRequest(approving.value.id, tid)
    if (r.success && r.data) {
      approveResult.value = r.data
      approveOpen.value = false
      approveResultOpen.value = true
      await Promise.all([load(), loadRequests()])
    } else message.error(r.msg || '批准失败')
  } catch (e) { message.error(e.message || '批准失败') }
  finally { saving.value = false }
}

// ---- 驳回 ----
const rejectOpen = ref(false)
const rejecting = ref({})
const rejectForm = ref({ note: '' })
function openReject (req) {
  rejecting.value = req
  rejectForm.value = { note: '' }
  rejectOpen.value = true
}
async function submitReject () {
  saving.value = true
  try {
    const r = await rejectTenantRequest(rejecting.value.id, rejectForm.value.note)
    if (r.success) { message.success('已驳回'); rejectOpen.value = false; await loadRequests() }
    else message.error(r.msg || '驳回失败')
  } catch (e) { message.error(e.message || '驳回失败') }
  finally { saving.value = false }
}

// ==================== 落点清单 ====================
const inventoryOpen = ref(false)
const inventoryTenant = ref(null)
const inventory = ref(null)
const invLoading = ref(false)
async function openInventory (t) {
  inventoryTenant.value = t
  inventory.value = null
  inventoryOpen.value = true
  invLoading.value = true
  try {
    const r = await getTenantInventory(t.id)
    if (r.success) inventory.value = r.data || {}
    else { message.error(r.msg || '落点清单加载失败'); inventoryOpen.value = false }
  } catch (e) { message.error(e.message || '落点清单加载失败'); inventoryOpen.value = false }
  finally { invLoading.value = false }
}

function fmtBytes (n) {
  if (n == null) return '—'
  if (n < 1024) return n + ' B'
  if (n < 1048576) return (n / 1024).toFixed(1) + ' KB'
  if (n < 1073741824) return (n / 1048576).toFixed(1) + ' MB'
  return (n / 1073741824).toFixed(2) + ' GB'
}

onMounted(() => { load(); loadRequests() })
</script>

<style scoped>
.form-tip { font-size: 12px; color: var(--app-text-3, #888); line-height: 1.6; }
.hint-inline { margin-left: 8px; font-size: 12px; color: var(--app-text-3, #aaa); }
.tenant-note { color: var(--app-text-2, #666); font-size: 12px; }
.tab-badge {
  display: inline-block; margin-left: 6px; padding: 0 6px; border-radius: 8px;
  background: #faad14; color: #fff; font-size: 11px; line-height: 16px;
}
.req-filter { margin-bottom: 12px; }
.req-purpose { font-size: 12px; color: var(--app-text-3, #999); margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
code { padding: 1px 6px; border-radius: 4px; background: var(--app-bg-2, #f5f5f5); font-size: 12px; }
.inv-overview { display: flex; gap: 28px; padding: 4px 0 8px; }
.inv-cell .stat-num { font-size: 20px; font-weight: 600; }
.inv-cell .stat-label { font-size: 12px; color: var(--app-text-3, #888); }
.inv-divider { margin: 14px 0 8px; font-size: 13px; }
.inv-table-row { display: flex; align-items: center; gap: 10px; padding: 3px 0; font-size: 12px; }
.inv-rows { margin-left: auto; color: var(--app-text-3, #888); white-space: nowrap; }
.inv-kind { color: var(--app-text-2, #666); width: 72px; flex: none; }
.inv-path { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; direction: rtl; text-align: left; }
.inv-key { margin-bottom: 4px; }
.inv-global { margin: 0; padding-left: 18px; font-size: 12px; color: var(--app-text-2, #666); line-height: 1.9; }
</style>
