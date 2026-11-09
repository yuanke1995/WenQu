<template>
  <a-modal :open="open" title="邀请成员" :width="560" :footer="null" @cancel="close">
    <div class="im-sub">
      生成一条一次性邀请链接，对方打开即可注册并加入
      <b>{{ tenantName }}</b>。
    </div>

    <template v-if="!created">
      <a-form layout="vertical" class="im-form" @submit.prevent="submit">
        <a-form-item label="角色" extra="可多选；不选时按租户默认角色（成员）。">
          <a-select v-model:value="form.roleIds" mode="multiple" :options="roleOptions"
                    placeholder="选择角色" :max-tag-count="3" />
        </a-form-item>
        <a-form-item label="部门">
          <a-select v-model:value="form.deptId" allow-clear :options="deptOptions" placeholder="不指定" />
        </a-form-item>
        <div class="im-grid">
          <a-form-item label="有效期">
            <a-radio-group v-model:value="form.expireDays">
              <a-radio :value="7">7 天</a-radio>
              <a-radio :value="15">15 天</a-radio>
              <a-radio :value="30">30 天</a-radio>
            </a-radio-group>
          </a-form-item>
          <a-form-item label="可用次数">
            <a-radio-group v-model:value="form.maxUses">
              <a-radio :value="1">1 次</a-radio>
              <a-radio :value="5">5 次</a-radio>
              <a-radio :value="0">不限</a-radio>
            </a-radio-group>
          </a-form-item>
        </div>
        <a-form-item label="限定邮箱" extra="选填。当前版本不校验邮箱归属，持有链接即可注册。">
          <a-input v-model:value="form.email" :maxlength="160" placeholder="name@company.com" />
        </a-form-item>
        <div class="im-actions">
          <a-button type="primary" :loading="busy" @click="submit">生成邀请链接</a-button>
          <a-button @click="close">取消</a-button>
        </div>
      </a-form>
    </template>

    <template v-else>
      <div class="im-result">
        <div class="im-result-title">邀请链接（只显示这一次）</div>
        <div class="im-link">
          <code>{{ inviteUrl }}</code>
          <a-button size="small" type="primary" @click="copyLink">复制链接</a-button>
        </div>
        <div class="im-tip">关闭后无法再次查看明文链接。</div>
      </div>
      <div class="im-actions">
        <a-button @click="created = false">再生成一条</a-button>
        <a-button type="primary" @click="close">完成</a-button>
      </div>
    </template>

    <div class="im-pending">
      <div class="im-pending-head">
        待接受邀请
        <span class="im-pending-count">{{ invites.length }} 条在途</span>
        <a-spin v-if="loading" :spinning="true" size="small" />
      </div>
      <a-empty v-if="!loading && !invites.length" description="暂无在途邀请" :image="false" />
      <a-table v-else :data-source="invites" :row-key="r => r.id" size="small" :pagination="false"
               :columns="cols" class="im-table">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'roles'">
            <span class="im-role">{{ roleText(record.roleIds) }}</span>
            <div v-if="record.email" class="im-sub-line">{{ record.email }}</div>
          </template>
          <template v-else-if="column.key === 'uses'">
            {{ record.maxUses === 0 ? record.usedCount + ' / 不限' : record.usedCount + ' / ' + record.maxUses }}
          </template>
          <template v-else-if="column.key === 'expires'">{{ fmt(record.expiresAt) }}</template>
          <template v-else-if="column.key === 'status'">
            <span class="im-status" :class="record.status">{{ statusText(record.status) }}</span>
          </template>
          <template v-else-if="column.key === 'action'">
            <a-popconfirm v-if="record.status === 'active'" title="撤销后该链接立即不可用" ok-text="撤销"
                          cancel-text="取消" @confirm="revoke(record)">
              <button class="app-link-btn danger">撤销</button>
            </a-popconfirm>
            <span v-else class="im-none">—</span>
          </template>
        </template>
      </a-table>
    </div>
  </a-modal>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { createTenantInvite, listTenantInvites, revokeTenantInvite } from '../api'
import { copyText } from '../utils/clipboard'
import { publicUrl } from '../utils/publicUrl'

const props = defineProps({
  open: { type: Boolean, default: false },
  roles: { type: Array, default: () => [] },
  departments: { type: Array, default: () => [] },
  tenantName: { type: String, default: '' }
})
const emit = defineEmits(['update:open', 'created'])

const form = reactive({ roleIds: [], deptId: undefined, email: '', maxUses: 1, expireDays: 7 })
const busy = ref(false)
const created = ref(false)
const inviteUrl = ref('')
const invites = ref([])
const loading = ref(false)

const roleOptions = computed(() => (props.roles || []).map(r => ({ label: r.name || r.code, value: r.code })))
const deptOptions = computed(() => (props.departments || []).map(d => ({ label: d.name, value: d.id })))
const cols = [
  { title: '角色', key: 'roles', width: 170 },
  { title: '已用', key: 'uses', width: 80 },
  { title: '到期', key: 'expires', width: 130 },
  { title: '状态', key: 'status', width: 80 },
  { title: '操作', key: 'action', width: 70 }
]

const roleText = raw => {
  const codes = String(raw || '').split(',').filter(Boolean)
  if (!codes.length) return '成员'
  return codes.map(c => ((props.roles || []).find(r => r.code === c) || {}).name || c).join('、')
}
const statusText = s => ({ active: '有效', revoked: '已撤销', exhausted: '已用尽', expired: '已过期' }[s] || s || '有效')
const fmt = s => (s ? String(s).replace('T', ' ').slice(0, 16) : '—')

function close () {
  emit('update:open', false)
}

async function loadInvites () {
  loading.value = true
  try {
    const r = await listTenantInvites()
    invites.value = (r && r.data) || []
  } catch (e) {
    invites.value = []
  } finally {
    loading.value = false
  }
}

watch(() => props.open, open => {
  if (open) {
    created.value = false
    inviteUrl.value = ''
    form.roleIds = []
    form.deptId = undefined
    form.email = ''
    form.maxUses = 1
    form.expireDays = 7
    loadInvites()
  }
})

async function submit () {
  if (busy.value) return
  busy.value = true
  try {
    const body = {
      roleIds: (form.roleIds || []).join(','),
      deptId: form.deptId || '',
      email: form.email.trim(),
      maxUses: form.maxUses,
      expireDays: form.expireDays
    }
    const r = await createTenantInvite(body)
    if (!r || !r.success) { message.error((r && r.msg) || '生成失败'); return }
    const path = (r.data && r.data.path) || ''
    inviteUrl.value = path ? publicUrl(path) : ''
    created.value = true
    emit('created')
    loadInvites()
  } catch (e) {
    message.error(e.message || '生成失败，请稍后再试')
  } finally {
    busy.value = false
  }
}

async function copyLink () {
  if (!inviteUrl.value) return
  await copyText(inviteUrl.value, '链接已复制')
}

async function revoke (record) {
  try {
    const r = await revokeTenantInvite(record.id)
    if (!r || !r.success) { message.error((r && r.msg) || '撤销失败'); return }
    message.success('已撤销')
    loadInvites()
  } catch (e) {
    message.error(e.message || '撤销失败')
  }
}
</script>

<style scoped>
.im-sub { margin-bottom: 14px; font-size: 12.5px; line-height: 1.7; color: var(--app-text-2, #66707a); }
.im-sub b { color: var(--app-text, #1f2329); font-weight: 500; }
.im-form :deep(.ant-form-item) { margin-bottom: 14px; }
.im-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.im-actions { display: flex; gap: 8px; margin-top: 4px; padding-top: 14px; border-top: 1px solid var(--app-border, #eceef1); }
.im-result { padding: 12px; border-radius: 10px; background: var(--app-panel-2, #fafbfc); border: 1px solid var(--app-border, #eceef1); }
.im-result-title { margin-bottom: 8px; font-size: 12px; color: var(--app-text-2, #66707a); }
.im-link { display: flex; gap: 8px; align-items: center; }
.im-link code {
  flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  padding: 7px 10px; border-radius: 6px; background: var(--app-code-bg, #f6f8fa);
  font-family: var(--mono, ui-monospace, monospace); font-size: 11.5px; color: var(--app-text, #1f2329);
}
.im-tip { margin-top: 8px; font-size: 11.5px; color: var(--app-text-3, #98a0aa); }
.im-pending { margin-top: 18px; }
.im-pending-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; font-size: 12px; color: var(--app-text-2, #66707a); }
.im-pending-count { font-size: 11.5px; color: var(--app-text-3, #98a0aa); }
.im-role { color: var(--app-text, #1f2329); }
.im-sub-line { margin-top: 1px; font-size: 11px; color: var(--app-text-3, #98a0aa); }
.im-status { font-size: 12px; }
.im-status.active { color: var(--app-ok, #3b8f4e); }
.im-status.exhausted, .im-status.expired { color: var(--app-text-3, #98a0aa); }
.im-status.revoked { color: var(--app-danger, #d4552e); }
.im-none { color: var(--app-text-3, #98a0aa); }
.im-table :deep(.ant-table) { font-size: 12px; }
</style>
