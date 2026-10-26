<template>
  <div class="app-page">
    <div class="app-page-head">
      <h1 class="app-page-title sr-only">认证源与身份同步</h1>
      <span class="head-count">共 <b>{{ list.length }}</b> 个认证源</span>
      <span class="head-hint-plain">SAML / SCIM / LDAP 统一登记；组映射规则决定 IdP 组自动授予的角色；OIDC 仍在「单点登录」页签</span>
      <button class="app-btn" style="margin-left:auto" @click="openCreate">
        <plus-outlined /> 新建认证源
      </button>
    </div>

    <div class="app-page-body">
      <a-spin :spinning="loading">
        <a-table v-if="list.length" :data-source="list" row-key="id" :pagination="false" size="middle">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'type'">
              <a-tag :color="typeColor(record.type)">{{ typeLabel(record.type) }}</a-tag>
            </template>
            <template v-else-if="column.key === 'enabled'">
              <a-switch :checked="record.enabled === 1" size="small" @change="v => onToggle(record, v)" />
            </template>
            <template v-else-if="column.key === 'boundUsers'">
              <span>{{ record.boundUsers }} 个账号</span>
            </template>
            <template v-else-if="column.key === 'action'">
              <a-space>
                <a-button type="link" size="small" @click="openMappings(record)">组映射</a-button>
                <a-button v-if="record.type === 'ldap'" type="link" size="small" @click="onTest(record)">测试连接</a-button>
                <a-button v-if="record.type === 'ldap'" type="link" size="small" @click="onSync(record)">立即同步</a-button>
                <a-button v-if="record.type === 'ldap'" type="link" size="small" @click="openLogs(record)">日志</a-button>
                <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
                <a-popconfirm title="删除该认证源？仍有账号绑定时会拒绝" ok-text="删除" cancel-text="取消" @confirm="onDelete(record)">
                  <a-button type="link" size="small" danger>删除</a-button>
                </a-popconfirm>
              </a-space>
            </template>
          </template>
        </a-table>
        <a-empty v-else description="暂无认证源，点击右上角新建（SAML 登录 / SCIM 入站 / LDAP 目录同步）" />
      </a-spin>
    </div>

    <!-- 认证源抽屉 -->
    <a-drawer :open="drawerOpen" :title="editingId ? '编辑认证源' : '新建认证源'" width="640"
              @close="drawerOpen = false" :mask-closable="false">
      <a-form layout="vertical" autocomplete="off">
        <a-form-item label="类型" :extra="editingId ? '类型创建后不可改' : '决定协议与所需配置'">
          <a-select v-model:value="form.type" :disabled="!!editingId" :options="typeOptions" style="width:220px" />
        </a-form-item>
        <a-form-item label="认证源名称" required>
          <a-input v-model:value="form.name" placeholder="如「企业 AD 目录」「Okta SCIM」" />
        </a-form-item>
        <a-form-item label="启用">
          <a-switch v-model:checked="form.enabled" />
        </a-form-item>

        <!-- SAML -->
        <template v-if="form.type === 'saml'">
          <a-divider orientation="left">SAML 服务端（SP）</a-divider>
          <a-form-item label="spBaseUrl（必填）"
                       extra="部署对外可达根地址。ACS = {spBaseUrl}/login/saml2/sso/{id}，EntityID = {spBaseUrl}/saml2/service-provider-metadata/{id}，登记到 IdP">
            <a-input v-model:value="cfg.spBaseUrl" placeholder="https://wenqu.corp.com" />
          </a-form-item>
          <a-form-item label="IdP 元数据地址" extra="首选：配了它可省去手填三件套">
            <a-input v-model:value="cfg.metadataUrl" placeholder="https://idp.example.com/metadata" />
          </a-form-item>
          <a-collapse ghost class="adv-collapse">
            <a-collapse-panel key="saml-manual" header="手填模式（无元数据地址时）：IdP 三件套">
              <a-form-item label="IdP EntityID"><a-input v-model:value="cfg.idpEntityId" /></a-form-item>
              <a-form-item label="IdP SSO 地址"><a-input v-model:value="cfg.idpSsoUrl" /></a-form-item>
              <a-form-item label="IdP 证书（PEM）">
                <a-textarea v-model:value="cfg.idpCertificate" :rows="4" placeholder="-----BEGIN CERTIFICATE-----" />
              </a-form-item>
              <a-form-item label="要求签名 AuthnRequest" extra="ADFS 默认要求时开启">
                <a-switch v-model:checked="cfg.signAuthnRequest" />
              </a-form-item>
              <a-form-item label="SP 证书（PEM）" extra="可选，与私钥成对；用于请求签名与加密断言解密">
                <a-textarea v-model:value="cfg.spCertificate" :rows="3" />
              </a-form-item>
              <a-form-item label="SP 私钥（PKCS#8 PEM）" extra="敏感字段，RSA 加密入库；回显为 ****** 不覆盖">
                <a-textarea v-model:value="cfg.spPrivateKey" :rows="3" autocomplete="new-password" />
              </a-form-item>
            </a-collapse-panel>
          </a-collapse>
          <a-form-item label="前端跳转基地址" extra="留空 = 同源相对路径">
            <a-input v-model:value="cfg.frontendBaseUrl" />
          </a-form-item>
        </template>

        <!-- SCIM -->
        <template v-else-if="form.type === 'scim'">
          <a-divider orientation="left">SCIM 入站凭据</a-divider>
          <a-form-item label="Bearer Token" extra="敏感字段，RSA 加密入库；IdP 侧配置到 SCIM 连接；回显 ****** 不覆盖">
            <a-input-password v-model:value="cfg.bearerToken" autocomplete="new-password" />
          </a-form-item>
          <div class="form-tip">
            IdP 侧租户 URL 填 <code>{{ origin }}/api/ai/scim/v2</code>（认证源创建后 id 固定）；
            Users + Groups 均已支持，建号不设密码、销户为禁用可恢复。
          </div>
        </template>

        <!-- LDAP -->
        <template v-else-if="form.type === 'ldap'">
          <a-divider orientation="left">LDAP / AD 目录</a-divider>
          <a-form-item label="LDAP 地址" required extra="ldap://host:389（389 走 STARTTLS 明文口）或 ldaps://host:636">
            <a-input v-model:value="cfg.url" placeholder="ldap://ad.corp.com:389" />
          </a-form-item>
          <a-form-item label="Base DN" required><a-input v-model:value="cfg.baseDn" placeholder="DC=corp,DC=com" /></a-form-item>
          <a-form-item label="Bind DN" extra="留空 = 匿名绑定（多数环境需要服务账号）">
            <a-input v-model:value="cfg.bindDn" placeholder="CN=svc-wenqu,DC=corp,DC=com" />
          </a-form-item>
          <a-form-item label="Bind 密码" extra="敏感字段，RSA 加密入库；回显 ****** 不覆盖">
            <a-input-password v-model:value="cfg.bindPassword" autocomplete="new-password" />
          </a-form-item>
          <a-form-item label="用户过滤器"><a-input v-model:value="cfg.userFilter" placeholder="(objectClass=person)" /></a-form-item>
          <a-form-item label="账号属性"><a-input v-model:value="cfg.uidAttr" placeholder="AD 填 sAMAccountName" /></a-form-item>
          <a-form-item label="显示名属性"><a-input v-model:value="cfg.nameAttr" placeholder="displayName" /></a-form-item>
          <a-form-item label="组成员属性（AD 形态）" extra="如 memberOf；与组反查可同时配置取并集">
            <a-input v-model:value="cfg.memberOfAttr" placeholder="memberOf" />
          </a-form-item>
          <a-collapse ghost class="adv-collapse">
            <a-collapse-panel key="ldap-group" header="组反查（OpenLDAP / posixGroup 形态）">
              <a-form-item label="组 Base DN"><a-input v-model:value="cfg.groupBaseDn" placeholder="OU=Groups,DC=corp,DC=com" /></a-form-item>
              <a-form-item label="组过滤器"><a-input v-model:value="cfg.groupFilter" placeholder="(objectClass=groupOfNames)" /></a-form-item>
              <a-form-item label="组名属性"><a-input v-model:value="cfg.groupNameAttr" placeholder="cn" /></a-form-item>
              <a-form-item label="成员属性"><a-input v-model:value="cfg.groupMemberAttr" placeholder="member" /></a-form-item>
            </a-collapse-panel>
          </a-collapse>
          <a-form-item label="同步间隔（分钟）" extra="目录拉取 → diff 建/禁/组映射；最小 5">
            <a-input-number v-model:value="cfg.syncIntervalMinutes" :min="5" style="width:160px" />
          </a-form-item>
        </template>

        <a-divider orientation="left">自动建号</a-divider>
        <a-form-item label="新建账号的默认角色" extra="须是权限管理中已启用的角色；组映射规则可覆盖">
          <a-input v-model:value="form.defaultRole" placeholder="user" />
        </a-form-item>
        <a-form-item label="首次同步/登录自动建号" extra="关闭则须管理员预先建好账号">
          <a-switch v-model:checked="form.autoCreateUser" />
        </a-form-item>
        <a-form-item label="排序"><a-input-number v-model:value="form.sortOrder" :min="0" style="width:160px" /></a-form-item>
      </a-form>
      <template #footer>
        <a-button @click="drawerOpen = false">取消</a-button>
        <a-button type="primary" :loading="saving" @click="save">保存</a-button>
      </template>
    </a-drawer>

    <!-- 组映射抽屉 -->
    <a-drawer :open="mappingOpen" :title="`组映射规则 — ${mappingProvider ? mappingProvider.name : ''}`" width="680"
              @close="mappingOpen = false" :mask-closable="false">
      <div class="form-tip" style="margin-top:0">
        IdP 组命中规则时按 priority（大者优先）授予角色/部门，唯一胜者；手动授予的角色永不被覆盖。
        组名须与 IdP 侧一致（SCIM displayName / LDAP cn）。
      </div>
      <a-table :data-source="mappings" row-key="id" :pagination="false" size="small" style="margin-top:8px">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'externalGroup'"><code>{{ record.externalGroup }}</code></template>
          <template v-else-if="column.key === 'roleCode'">
            <a-tag v-if="record.roleCode" color="blue">{{ record.roleCode }}</a-tag>
            <span v-else class="mem-none">未配置</span>
          </template>
          <template v-else-if="column.key === 'departmentId'">{{ deptName(record.departmentId) }}</template>
          <template v-else-if="column.key === 'action'">
            <a-space>
              <a-button type="link" size="small" @click="openMappingEdit(record)">编辑</a-button>
              <a-popconfirm title="删除规则并回收该组授予的角色？" ok-text="删除" cancel-text="取消" @confirm="onDeleteMapping(record)">
                <a-button type="link" size="small" danger>删除</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>
      <a-divider orientation="left" style="margin-top:20px">{{ mappingEditing.id ? '编辑规则' : '新建规则' }}</a-divider>
      <a-form layout="vertical">
        <a-form-item label="IdP 组名" required>
          <a-input v-model:value="mappingEditing.externalGroup" :disabled="!!mappingEditing.id" placeholder="如 Engineering" />
        </a-form-item>
        <a-form-item label="映射角色">
          <a-select v-model:value="mappingEditing.roleCode" :options="roleOptions" allow-clear placeholder="未配置 = 不授角色" />
        </a-form-item>
        <a-form-item label="映射部门">
          <a-select v-model:value="mappingEditing.departmentId" :options="deptOptions" allow-clear placeholder="不联动部门" />
        </a-form-item>
        <a-form-item label="优先级" extra="多组命中时大者优先">
          <a-input-number v-model:value="mappingEditing.priority" :min="0" style="width:160px" />
        </a-form-item>
        <a-button type="primary" :loading="saving" @click="saveMapping">{{ mappingEditing.id ? '保存规则' : '添加规则' }}</a-button>
      </a-form>
    </a-drawer>

    <!-- 同步日志抽屉 -->
    <a-drawer :open="logsOpen" :title="`同步日志 — ${logsProvider ? logsProvider.name : ''}`" width="640"
              @close="logsOpen = false">
      <a-timeline style="margin-top:8px">
        <a-timeline-item v-for="l in logs" :key="l.id"
                         :color="l.status === 'success' ? 'green' : l.status === 'partial' ? 'orange' : 'red'">
          <div class="log-line">
            <b>{{ l.createTime ? String(l.createTime).replace('T', ' ').slice(0, 19) : '' }}</b>
            <span class="log-stat">建 {{ l.usersCreated || 0 }} · 改 {{ l.usersUpdated || 0 }} · 禁 {{ l.usersDisabled || 0 }}</span>
          </div>
          <div class="form-tip" style="margin:0">{{ l.detail }}</div>
        </a-timeline-item>
      </a-timeline>
      <a-empty v-if="!logs.length" description="暂无同步记录" />
    </a-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import {
  listIdentityProviders, createIdentityProvider, updateIdentityProvider,
  deleteIdentityProvider, testIdentityProvider,
  listGroupMappings, createGroupMapping, updateGroupMapping, deleteGroupMapping,
  triggerIdentitySync, listSyncLogs
} from '../api'
import { listRoleOptions, listDepartments } from '../api'

const list = ref([])
const roles = ref([])
const departments = ref([])
const loading = ref(false)
const saving = ref(false)
const drawerOpen = ref(false)
const editingId = ref('')
const cfg = ref({})
const origin = window.location.origin

const typeLabel = t => ({ saml: 'SAML 登录', scim: 'SCIM 入站', ldap: 'LDAP 目录' }[t] || t)
const typeColor = t => ({ saml: 'purple', scim: 'cyan', ldap: 'geekblue' }[t] || 'default')
const typeOptions = [
  { label: 'SAML 登录（浏览器 SSO）', value: 'saml' },
  { label: 'SCIM 入站（IdP 推送用户/组）', value: 'scim' },
  { label: 'LDAP / AD 目录同步', value: 'ldap' }
]

const roleOptions = computed(() => roles.value.map(r => ({ label: r.name, value: r.code })))
const deptOptions = computed(() => departments.value.map(d => ({ label: d.name, value: d.id })))
const deptName = id => (departments.value.find(d => d.id === id) || {}).name || '—'

async function load () {
  loading.value = true
  try {
    const [r, rr, rd] = await Promise.all([listIdentityProviders(), listRoleOptions(), listDepartments()])
    list.value = (r && r.data) || []
    roles.value = (rr && rr.data) || []
    departments.value = (rd && rd.data) || []
  } catch (e) {
    message.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}
onMounted(load)

function blank () {
  return { type: 'ldap', name: '', enabled: 1, defaultRole: 'user', autoCreateUser: 1, sortOrder: 0, config: {} }
}
function openCreate () {
  const b = blank()
  editingId.value = ''
  form.value = { type: b.type, name: b.name, enabled: !!b.enabled, defaultRole: b.defaultRole, autoCreateUser: !!b.autoCreateUser, sortOrder: b.sortOrder }
  cfg.value = {}
  drawerOpen.value = true
}
function openEdit (p) {
  editingId.value = p.id
  form.value = {
    type: p.type, name: p.name, enabled: p.enabled === 1,
    defaultRole: p.defaultRole || '', autoCreateUser: p.autoCreateUser === 1, sortOrder: p.sortOrder || 0
  }
  cfg.value = { ...(p.config || {}) }
  drawerOpen.value = true
}

const form = ref(blank())

function payload () {
  const f = form.value
  const config = {}
  for (const [k, v] of Object.entries(cfg.value)) {
    if (v === undefined || v === null) continue
    if (typeof v === 'boolean') config[k] = v ? 1 : 0
    else config[k] = v
  }
  return {
    name: f.name, enabled: f.enabled ? 1 : 0,
    defaultRole: f.defaultRole || '', autoCreateUser: f.autoCreateUser ? 1 : 0,
    sortOrder: f.sortOrder, type: f.type, config
  }
}

async function save () {
  const f = form.value
  if (!f.name || !f.name.trim()) { message.error('请填写认证源名称'); return }
  saving.value = true
  try {
    const r = editingId.value
      ? await updateIdentityProvider(editingId.value, payload())
      : await createIdentityProvider(payload())
    if (r.success) { message.success('已保存'); drawerOpen.value = false; await load() }
    else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}

async function onToggle (p, v) {
  try {
    const r = await updateIdentityProvider(p.id, { ...p, enabled: v ? 1 : 0, config: p.config })
    if (r.success) { p.enabled = v ? 1 : 0; message.success(v ? '已启用' : '已停用') }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}

async function onDelete (p) {
  try {
    const r = await deleteIdentityProvider(p.id)
    if (r.success) { message.success('已删除'); await load() }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}

async function onTest (p) {
  message.loading({ content: '连接测试中…', key: 'test' })
  try {
    const r = await testIdentityProvider(p.id)
    const ok = r.data && r.data.ok
    if (ok) message.success({ content: '连接成功', key: 'test' })
    else message.error({ content: (r.data && r.data.error) || '连接失败', key: 'test', duration: 6 })
  } catch (e) { message.error({ content: e.message || '连接失败', key: 'test', duration: 6 }) }
}

async function onSync (p) {
  message.loading({ content: '同步中…', key: 'sync' })
  try {
    const r = await triggerIdentitySync(p.id)
    if (r.success && r.data) {
      const l = r.data
      message.success({ content: `同步完成：建 ${l.usersCreated || 0} · 改 ${l.usersUpdated || 0} · 禁 ${l.usersDisabled || 0}`, key: 'sync', duration: 5 })
      await load()
    } else message.error({ content: r.msg || '同步失败', key: 'sync', duration: 6 })
  } catch (e) { message.error({ content: e.message || '同步失败', key: 'sync', duration: 6 }) }
}

// ==================== 组映射 ====================
const mappingOpen = ref(false)
const mappingProvider = ref(null)
const mappings = ref([])
const mappingEditing = reactive({ id: '', externalGroup: '', roleCode: undefined, departmentId: undefined, priority: 0 })

async function openMappings (p) {
  mappingProvider.value = p
  mappingOpen.value = true
  mappingEditing.id = ''
  mappingEditing.externalGroup = ''
  mappingEditing.roleCode = undefined
  mappingEditing.departmentId = undefined
  mappingEditing.priority = 0
  await loadMappings()
}
async function loadMappings () {
  try {
    const r = await listGroupMappings(mappingProvider.value.id)
    mappings.value = (r && r.data) || []
  } catch (e) { message.error(e.message || '加载映射失败') }
}
function openMappingEdit (m) {
  Object.assign(mappingEditing, {
    id: m.id, externalGroup: m.externalGroup,
    roleCode: m.roleCode || undefined, departmentId: m.departmentId || undefined,
    priority: m.priority || 0
  })
}
async function saveMapping () {
  const e = mappingEditing
  if (!e.externalGroup || !e.externalGroup.trim()) { message.error('请填写 IdP 组名'); return }
  saving.value = true
  try {
    const body = {
      providerId: mappingProvider.value.id,
      externalGroup: e.externalGroup.trim(),
      roleCode: e.roleCode || '', departmentId: e.departmentId || '', priority: e.priority || 0
    }
    const r = e.id ? await updateGroupMapping(e.id, body) : await createGroupMapping(body)
    if (r.success) {
      message.success('已保存')
      Object.assign(mappingEditing, { id: '', externalGroup: '', roleCode: undefined, departmentId: undefined, priority: 0 })
      await loadMappings()
    } else message.error(r.msg || '保存失败')
  } catch (err) { message.error(err.message || '保存失败') }
  finally { saving.value = false }
}
async function onDeleteMapping (m) {
  try {
    const r = await deleteGroupMapping(m.id)
    if (r.success) { message.success('已删除'); await loadMappings() }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}

// ==================== 同步日志 ====================
const logsOpen = ref(false)
const logsProvider = ref(null)
const logs = ref([])
async function openLogs (p) {
  logsProvider.value = p
  logsOpen.value = true
  try {
    const r = await listSyncLogs(p.id, 20)
    logs.value = (r && r.data) || []
  } catch (e) { message.error(e.message || '加载日志失败') }
}
</script>

<style scoped>
.mono { font-family: var(--app-mono, monospace); font-size: 12px; }
.form-tip { font-size: 12px; color: var(--app-text-3, #888); line-height: 1.6; }
.mem-none { color: var(--app-text-3, #aaa); font-size: 12px; }
.log-line { display: flex; gap: 12px; align-items: baseline; }
.log-stat { color: var(--app-text-3, #888); font-size: 12px; }
code { padding: 1px 6px; border-radius: 4px; background: var(--app-bg-2, #f5f5f5); font-size: 12px; }
</style>
