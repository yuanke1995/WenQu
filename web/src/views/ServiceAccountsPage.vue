<template>
  <div class="app-page">
    <div class="app-page-head">
      <h1 class="app-page-title sr-only">服务账号</h1>
      <span class="head-count">共 <b>{{ accounts.length }}</b> 个服务账号</span>
      <span class="head-hint-plain">给外部系统用的非人类身份：请求头带 <code>X-Service-Token</code>，权限走角色，不能登录网页</span>
      <button class="app-btn" style="margin-left:auto" @click="openCreate">
        <plus-outlined /> 新建服务账号
      </button>
    </div>

    <div class="app-page-body">
      <a-alert type="info" show-icon style="margin-bottom:12px">
        <template #message>令牌只在创建或轮换的那一刻显示一次</template>
        <template #description>
          <div class="form-tip">
            库里只存哈希，忘了只能轮换重发；轮换后旧令牌立刻失效。停用即全部调用被拒。
            角色挂在「成员」同一套关系上，所以能做什么完全由角色决定——要给它单独的口子，先在权限管理建一个角色。
          </div>
        </template>
      </a-alert>

      <div class="app-card" style="padding:0;overflow:hidden">
        <a-spin :spinning="loading">
          <a-table :data-source="accounts" :row-key="r => r.id" size="middle" :pagination="false">
            <a-table-column title="名称" key="name">
              <template #default="{ record }">
                <span class="sa-name">{{ record.name }}</span>
                <div v-if="record.description" class="form-tip">{{ record.description }}</div>
              </template>
            </a-table-column>
            <a-table-column title="令牌" key="token" width="120">
              <template #default>
                <code class="sa-mask">••••••••</code>
              </template>
            </a-table-column>
            <a-table-column title="角色" key="roles" width="200">
              <template #default="{ record }">
                <span v-if="(rolesMap[record.id] || []).length" class="sa-roles">
                  <code v-for="c in rolesMap[record.id]" :key="c" class="sa-role">{{ c }}</code>
                </span>
                <span v-else class="form-tip">没有任何角色</span>
              </template>
            </a-table-column>
            <a-table-column title="状态" key="status" width="90">
              <template #default="{ record }">
                <a-switch :checked="record.status === 1" size="small" :loading="busyId === record.id"
                          checked-children="启用" un-checked-children="停用" @change="v => toggleStatus(record, v)" />
              </template>
            </a-table-column>
            <a-table-column title="到期" key="expire" width="140">
              <template #default="{ record }">
                <span v-if="!record.expireAt" class="form-tip">永久</span>
                <span v-else :class="{ 'sa-expired': isPast(record.expireAt) }">{{ fmt(record.expireAt) }}</span>
              </template>
            </a-table-column>
            <a-table-column title="创建人" key="creator" width="120">
              <template #default="{ record }">{{ record.createdBy || '—' }}</template>
            </a-table-column>
            <a-table-column title="操作" key="action" width="190">
              <template #default="{ record }">
                <span class="app-row-actions">
                  <button class="app-link-btn" @click="openEdit(record)">编辑</button>
                  <a-popconfirm title="轮换后旧令牌立刻失效，正在用它的外部系统会全部报 401。确定？"
                                ok-text="轮换" cancel-text="取消" @confirm="doRotate(record)">
                    <button class="app-link-btn">轮换令牌</button>
                  </a-popconfirm>
                  <a-popconfirm title="删除后它的角色绑定一并清理，不可恢复。确定？"
                                ok-text="删除" cancel-text="取消" @confirm="doDelete(record)">
                    <button class="app-link-btn danger">删除</button>
                  </a-popconfirm>
                </span>
              </template>
            </a-table-column>
            <template #emptyText>
              <div class="rb-empty">
                <span>还没有服务账号</span>
                <button class="app-link-btn" @click="openCreate">新建第一个</button>
              </div>
            </template>
          </a-table>
        </a-spin>
      </div>
    </div>

    <!-- 建号 / 编辑 -->
    <a-modal v-model:open="formModal" :title="form.id ? '编辑服务账号' : '新建服务账号'" :width="480"
             :confirm-loading="saving" :ok-text="form.id ? '保存' : '创建并签发'" cancel-text="取消" @ok="save">
      <a-form layout="vertical" style="margin-top:4px">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" maxlength="100" placeholder="如 报表系统 / 对账作业" />
        </a-form-item>
        <a-form-item label="用途说明">
          <a-input v-model:value="form.description" maxlength="255" placeholder="谁在用、出问题找谁（交接线索）" />
        </a-form-item>
        <a-form-item v-if="!form.id" label="角色" required>
          <a-select v-model:value="form.roles" mode="multiple" style="width:100%"
                    placeholder="至少选一个，否则它什么都调不动" :options="roleOptions" />
          <div class="form-tip">能调用哪些接口完全由角色决定；一个都不选时它只能通过身份校验、任何端点都会 403。</div>
        </a-form-item>
        <a-form-item label="到期时间">
          <a-date-picker v-model:value="form.expireAt" show-time value-format="YYYY-MM-DDTHH:mm:ss"
                         style="width:100%" placeholder="留空 = 永久" />
          <div class="form-tip" v-if="form.id && originalExpire">
            原本是 <b>{{ originalExpire === '永久' ? '永久' : fmt(originalExpire) }}</b>；清空这一栏并保存即改回永久。
          </div>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 明文令牌：只此一次 -->
    <a-modal v-model:open="tokenModal" title="令牌（只显示这一次）" :width="520" :closable="false" :maskClosable="false">
      <p class="form-tip" style="margin-top:0">
        现在复制走。关掉之后库里只剩哈希，只能轮换重发——轮换会让旧令牌立刻失效。
      </p>
      <a-textarea :value="tokenText" :rows="2" readonly class="sa-token" />
      <div class="sa-token-actions">
        <button class="app-btn primary" @click="copyToken">复制</button>
        <button class="app-btn" @click="tokenModal = false">我已保存</button>
      </div>
      <div v-if="copyHint" class="form-tip">{{ copyHint }}</div>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import { listServiceAccounts, createServiceAccount, updateServiceAccount,
         rotateServiceAccountToken, deleteServiceAccount, getServiceAccountRoles,
         listRoleOptions } from '../api'

const accounts = ref([])
const rolesMap = ref({})
const roleOptions = ref([])
const loading = ref(false)
const saving = ref(false)
const busyId = ref('')

const formModal = ref(false)
const form = ref({ id: '', name: '', description: '', roles: [], expireAt: null })
const originalExpire = ref('')

const tokenModal = ref(false)
const tokenText = ref('')
const copyHint = ref('')

const fmt = v => String(v || '').replace('T', ' ').slice(0, 16)
const isPast = v => !!v && new Date(String(v).replace(' ', 'T')).getTime() < Date.now()

async function load () {
  loading.value = true
  try {
    const r = await listServiceAccounts()
    accounts.value = (r && r.data) || []
    await loadRoles()
  } catch (e) {
    message.error(e.message || '服务账号清单读取失败')
  } finally {
    loading.value = false
  }
}

/** 角色绑定按 uid=svc:{id} 反查（与成员管理同一份数据）；账号数量级很小，逐个取即可 */
async function loadRoles () {
  const map = {}
  await Promise.all(accounts.value.map(async a => {
    try {
      const r = await getServiceAccountRoles(a.id)
      map[a.id] = ((r && r.data) || []).filter(c => c && c !== 'user')
    } catch (e) { map[a.id] = [] }
  }))
  rolesMap.value = map
}

function openCreate () {
  form.value = { id: '', name: '', description: '', roles: [], expireAt: null }
  originalExpire.value = ''
  formModal.value = true
  if (!roleOptions.value.length) loadRoleOptions()
}

function openEdit (acc) {
  form.value = { id: acc.id, name: acc.name || '', description: acc.description || '',
                 roles: rolesMap.value[acc.id] || [], expireAt: acc.expireAt || null }
  originalExpire.value = acc.expireAt || '永久'
  formModal.value = true
}

async function save () {
  const f = form.value
  if (!f.name.trim()) { message.warning('名称不能为空'); return }
  if (!f.id && !f.roles.length) { message.warning('至少选一个角色，否则它调不动任何接口'); return }
  saving.value = true
  try {
    if (f.id) {
      // expireAt 一定带在 body 里：后端靠「有没有这个键」区分「没改」与「改回永久」
      const r = await updateServiceAccount(f.id, { name: f.name, description: f.description,
                                                   expireAt: f.expireAt || '' })
      if (r && r.success === false) { message.error(r.msg || '保存失败'); return }
      message.success('已保存')
    } else {
      const r = await createServiceAccount({ name: f.name, description: f.description,
                                            roles: f.roles, expireAt: f.expireAt || null })
      if (r && !r.success) { message.error(r.msg || '创建失败'); return }
      const d = (r && r.data) || {}
      tokenText.value = d.token || ''
      tokenModal.value = true
      message.success('已创建')
    }
    formModal.value = false
    await load()
  } catch (e) {
    message.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggleStatus (acc, on) {
  busyId.value = acc.id
  try {
    const r = await updateServiceAccount(acc.id, { status: on ? 1 : 0 })
    if (r && r.success === false) { message.error(r.msg || '操作失败'); return }
    if (!on) message.success('已停用，它的调用会全部被拒')
    await load()
  } catch (e) {
    message.error(e.message || '操作失败')
  } finally {
    busyId.value = ''
  }
}

async function doRotate (acc) {
  try {
    const r = await rotateServiceAccountToken(acc.id)
    if (r && !r.success) { message.error(r.msg || '轮换失败'); return }
    tokenText.value = ((r && r.data) || {}).token || ''
    tokenModal.value = true
    await load()
  } catch (e) { message.error(e.message || '轮换失败') }
}

async function doDelete (acc) {
  try {
    const r = await deleteServiceAccount(acc.id)
    if (r && r.success === false) { message.error(r.msg || '删除失败'); return }
    message.success('已删除')
    await load()
  } catch (e) { message.error(e.message || '删除失败') }
}

async function loadRoleOptions () {
  try {
    const r = await listRoleOptions()
    roleOptions.value = ((r && r.data) || []).map(x => ({ label: `${x.name}（${x.code}）`, value: x.code }))
  } catch (e) { roleOptions.value = [] }
}

async function copyToken () {
  copyHint.value = ''
  try {
    await navigator.clipboard.writeText(tokenText.value)
    copyHint.value = '已复制到剪贴板'
  } catch (e) {
    // 非安全上下文（http 内网部署）拿不到 clipboard API：文本框已是只读全选态，让用户手动复制
    copyHint.value = '这个环境不允许网页直接写剪贴板，请在上面框内全选后手动复制'
  }
}

onMounted(() => { load(); loadRoleOptions() })
</script>

<style scoped>
.sa-name { font-weight: 600; }
.sa-mask { color: var(--app-text3); letter-spacing: 1px; }
.sa-roles { display: flex; flex-wrap: wrap; gap: 4px; }
.sa-role { font-size: 11px; background: var(--app-panel-2, #f2f4f7); border: 1px solid var(--app-border);
           border-radius: 4px; padding: 0 5px; }
.sa-expired { color: var(--app-danger, #c0392b); }
.sa-token { font-family: var(--app-mono, monospace); font-size: 12px; }
.sa-token-actions { display: flex; gap: 8px; margin-top: 10px; }
.rb-empty { padding: 28px 0; color: var(--app-text3); font-size: 12px; display: flex;
            flex-direction: column; align-items: center; gap: 8px; }
</style>
