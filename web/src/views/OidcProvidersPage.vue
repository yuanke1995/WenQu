<template>
  <div class="app-page">
    <div class="app-page-head">
      <h1 class="app-page-title sr-only">单点登录（OIDC）</h1>
      <span class="head-count">共 <b>{{ list.length }}</b> 个认证源</span>
      <span class="head-hint-plain">每个 IdP 登记一个认证源，登录页按排序出现对应入口按钮；可同时接多个 IdP</span>
      <button class="app-btn" style="margin-left:auto" @click="openCreate">
        <plus-outlined /> 新建认证源
      </button>
    </div>

    <div class="app-page-body">
      <a-spin :spinning="loading">
        <a-table v-if="list.length" :columns="columns" :data-source="sortedList" row-key="id" :pagination="false" size="middle">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'enabled'">
              <a-switch :checked="record.enabled === 1" size="small" @change="v => onToggle(record, v)" />
            </template>
            <template v-else-if="column.key === 'clientId'">
              <span class="mono">{{ maskTail(record.clientId) }}</span>
            </template>
            <template v-else-if="column.key === 'clientSecret'">
              <span class="mono">{{ record.clientSecret || '—' }}</span>
            </template>
            <template v-else-if="column.key === 'configured'">
              <a-tag :color="record.configured ? 'success' : 'default'">{{ record.configured ? '可用' : '未填完' }}</a-tag>
            </template>
            <template v-else-if="column.key === 'action'">
              <a-space>
                <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
                <a-popconfirm title="删除该认证源？仍有账号绑定时会拒绝" ok-text="删除" cancel-text="取消" @confirm="onDelete(record)">
                  <a-button type="link" size="small" danger>删除</a-button>
                </a-popconfirm>
              </a-space>
            </template>
          </template>
        </a-table>
        <a-empty v-else description="暂无认证源，点击右上角新建" />
      </a-spin>
    </div>

    <a-drawer :open="drawerOpen" :title="editingId ? '编辑认证源' : '新建认证源'" width="640"
              @close="drawerOpen = false" :mask-closable="false">
      <a-form layout="vertical">
        <a-divider orientation="left">接入（客户端凭据）</a-divider>
        <a-form-item label="认证源名称" required>
          <a-input v-model:value="form.name" placeholder="如「企业微信登录」「统一身份认证」" />
        </a-form-item>
        <a-form-item label="启用">
          <a-switch v-model:checked="form.enabled" />
        </a-form-item>
        <a-form-item label="Issuer URL" extra="填 Issuer 即可，各端点自动发现">
          <a-input v-model:value="form.issuerUrl" placeholder="https://idp.example.com/realms/main" />
        </a-form-item>
        <a-form-item label="Client ID">
          <a-input v-model:value="form.clientId" placeholder="IdP 登记的客户端 ID" />
        </a-form-item>
        <a-form-item label="Client Secret" extra="RSA 加密入库；编辑时留空 = 保持原值">
          <a-input-password v-model:value="form.clientSecret" placeholder="IdP 登记的客户端密钥" />
        </a-form-item>
        <a-form-item label="回调地址（Redirect URI）" extra="留空 = 按访问地址自动推导">
          <a-input v-model:value="form.redirectUri" placeholder="留空" />
        </a-form-item>
        <a-form-item label="前端跳转基地址" extra="留空 = 同源相对路径；前后端分离时填前端地址">
          <a-input v-model:value="form.frontendBaseUrl" placeholder="https://web.example.com" />
        </a-form-item>

        <a-collapse ghost class="adv-collapse">
          <a-collapse-panel key="adv" header="高级设置（端点、身份映射、自动建号 —— 一般保持默认即可）">
            <a-divider orientation="left">端点与授权参数（留空 = 自动发现）</a-divider>
        <a-form-item label="授权端点">
          <a-input v-model:value="form.authorizationEndpoint" placeholder="留空 = 自动发现" />
        </a-form-item>
        <a-form-item label="令牌端点">
          <a-input v-model:value="form.tokenEndpoint" placeholder="留空 = 自动发现" />
        </a-form-item>
        <a-form-item label="用户信息端点">
          <a-input v-model:value="form.userinfoEndpoint" placeholder="留空 = 自动发现" />
        </a-form-item>
        <a-form-item label="请求的 scope">
          <a-input v-model:value="form.scopes" placeholder="openid profile email" />
        </a-form-item>
        <a-form-item label="强制重新认证" extra="开启后带 prompt=login">
          <a-switch v-model:checked="form.forcePromptLogin" />
        </a-form-item>

        <a-divider orientation="left">身份映射与建号</a-divider>
        <a-form-item label="用户名映射字段">
          <a-input v-model:value="form.usernameClaim" placeholder="preferred_username" />
        </a-form-item>
        <a-form-item label="姓名映射字段">
          <a-input v-model:value="form.nameClaim" placeholder="name" />
        </a-form-item>
        <a-form-item label="用 IdP 用户名当账号标识">
          <a-switch v-model:checked="form.useRawUsername" />
        </a-form-item>
        <a-form-item label="首次登录自动建号">
          <a-switch v-model:checked="form.autoCreateUser" />
        </a-form-item>
        <a-form-item label="新建账号的默认角色" extra="须是权限管理中已启用的角色">
          <a-input v-model:value="form.defaultRole" placeholder="user" />
        </a-form-item>
        <a-form-item label="新建账号的默认部门" extra="不存在则按名称自动创建">
          <a-input v-model:value="form.defaultDepartment" placeholder="留空 = 不设部门" />
        </a-form-item>
        <a-form-item label="从 IdP 同步部门">
          <a-switch v-model:checked="form.fetchDepartmentInfo" />
        </a-form-item>
        <a-form-item label="部门映射字段">
          <a-input v-model:value="form.departmentClaim" placeholder="department" />
        </a-form-item>
        <a-form-item label="排序" extra="小在前，决定登录页按钮顺序">
          <a-input-number v-model:value="form.sortOrder" :min="0" style="width:160px" />
        </a-form-item>
          </a-collapse-panel>
        </a-collapse>
      </a-form>

      <template #footer>
        <a-button @click="drawerOpen = false">取消</a-button>
        <a-button type="primary" :loading="saving" @click="onSave">保存</a-button>
      </template>
    </a-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import {
  listOidcProviders, createOidcProvider, updateOidcProvider, deleteOidcProvider, setOidcProviderEnabled
} from '../api'
import { PlusOutlined } from '@ant-design/icons-vue'

const list = ref([])
const loading = ref(false)
const drawerOpen = ref(false)
const editingId = ref('')
const saving = ref(false)

const columns = [
  { title: '名称', key: 'name', dataIndex: 'name' },
  { title: '状态', key: 'enabled', width: 80 },
  { title: 'Issuer', key: 'issuerUrl', dataIndex: 'issuerUrl', ellipsis: true },
  { title: 'Client ID', key: 'clientId' },
  { title: 'Client Secret', key: 'clientSecret' },
  { title: '配置', key: 'configured', width: 90 },
  { title: '排序', key: 'sortOrder', dataIndex: 'sortOrder', width: 70 },
  { title: '操作', key: 'action', width: 120 }
]

const sortedList = computed(() =>
  [...list.value].sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0)))

const emptyForm = () => ({
  name: '', enabled: true, issuerUrl: '', clientId: '', clientSecret: '',
  redirectUri: '', frontendBaseUrl: '', authorizationEndpoint: '', tokenEndpoint: '',
  userinfoEndpoint: '', scopes: 'openid profile email', forcePromptLogin: false,
  usernameClaim: 'preferred_username', nameClaim: 'name', useRawUsername: false,
  autoCreateUser: true, defaultRole: 'user', defaultDepartment: '',
  fetchDepartmentInfo: false, departmentClaim: 'department', sortOrder: 0
})
const form = reactive(emptyForm())

function maskTail (v) {
  if (!v) return '—'
  return v.length <= 6 ? v : v.slice(0, 3) + '…' + v.slice(-3)
}

async function load () {
  loading.value = true
  try {
    const r = await listOidcProviders()
    list.value = (r && r.data) || []
  } catch (e) {
    message.error(e.message || '加载认证源失败')
  } finally {
    loading.value = false
  }
}

function openCreate () {
  Object.assign(form, emptyForm())
  editingId.value = ''
  drawerOpen.value = true
}

function openEdit (row) {
  Object.assign(form, {
    name: row.name, enabled: row.enabled === 1, issuerUrl: row.issuerUrl || '',
    clientId: row.clientId || '', clientSecret: '', redirectUri: row.redirectUri || '',
    frontendBaseUrl: row.frontendBaseUrl || '', authorizationEndpoint: row.authorizationEndpoint || '',
    tokenEndpoint: row.tokenEndpoint || '', userinfoEndpoint: row.userinfoEndpoint || '',
    scopes: row.scopes || 'openid profile email', forcePromptLogin: row.forcePromptLogin === 1,
    usernameClaim: row.usernameClaim || 'preferred_username', nameClaim: row.nameClaim || 'name',
    useRawUsername: row.useRawUsername === 1, autoCreateUser: row.autoCreateUser === 1,
    defaultRole: row.defaultRole || 'user', defaultDepartment: row.defaultDepartment || '',
    fetchDepartmentInfo: row.fetchDepartmentInfo === 1, departmentClaim: row.departmentClaim || 'department',
    sortOrder: row.sortOrder || 0
  })
  editingId.value = row.id
  drawerOpen.value = true
}

async function onSave () {
  if (!form.name || !form.name.trim()) {
    message.warning('请填写认证源名称')
    return
  }
  saving.value = true
  const payload = {
    name: form.name, enabled: form.enabled ? 1 : 0, issuerUrl: form.issuerUrl, clientId: form.clientId,
    clientSecret: form.clientSecret, redirectUri: form.redirectUri, frontendBaseUrl: form.frontendBaseUrl,
    authorizationEndpoint: form.authorizationEndpoint, tokenEndpoint: form.tokenEndpoint,
    userinfoEndpoint: form.userinfoEndpoint, scopes: form.scopes, forcePromptLogin: form.forcePromptLogin ? 1 : 0,
    usernameClaim: form.usernameClaim, nameClaim: form.nameClaim, useRawUsername: form.useRawUsername ? 1 : 0,
    autoCreateUser: form.autoCreateUser ? 1 : 0, defaultRole: form.defaultRole,
    defaultDepartment: form.defaultDepartment, fetchDepartmentInfo: form.fetchDepartmentInfo ? 1 : 0,
    departmentClaim: form.departmentClaim, sortOrder: form.sortOrder || 0
  }
  try {
    if (editingId.value) await updateOidcProvider(editingId.value, payload)
    else await createOidcProvider(payload)
    message.success('已保存')
    drawerOpen.value = false
    await load()
  } catch (e) {
    message.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onToggle (row, val) {
  try {
    await setOidcProviderEnabled(row.id, !!val)
    message.success(val ? '已启用' : '已停用')
    await load()
  } catch (e) {
    message.error(e.message || '操作失败')
  }
}

async function onDelete (row) {
  try {
    await deleteOidcProvider(row.id)
    message.success('已删除')
    await load()
  } catch (e) {
    message.error(e.message || '删除失败')
  }
}

onMounted(load)
</script>

<style scoped>
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.adv-collapse { margin-top: 4px; }
.adv-collapse :deep(.ant-collapse-content-box) { padding-bottom: 0; }
</style>
