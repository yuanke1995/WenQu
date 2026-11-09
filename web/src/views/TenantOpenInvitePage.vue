<template>
  <div class="to-page">
    <div class="to-card">
      <div class="to-brand">
        <span class="to-logo">问</span>
        <div>
          <div class="to-brand-name">问渠</div>
          <div class="to-brand-sub">开通工作区</div>
        </div>
      </div>

      <template v-if="loading">
        <div class="to-loading">正在校验开通邀请…</div>
      </template>

      <template v-else-if="error">
        <h1 class="to-title">邀请不可用</h1>
        <p class="to-desc">{{ error }}</p>
        <div class="to-actions">
          <a class="to-btn" href="/apply">申请开通</a>
          <a class="to-link" href="/login">返回登录</a>
        </div>
      </template>

      <template v-else-if="!done">
        <h1 class="to-title">开通「{{ form.orgName || '你的工作区' }}」</h1>
        <p class="to-desc">
          这份邀请已由平台预批准。补齐信息后会立即开通独立工作区，并为你创建第一个管理员账号。
        </p>
        <p v-if="invite.note" class="to-note">{{ invite.note }}</p>

        <a-form layout="vertical" class="to-form" @submit.prevent>
          <a-form-item label="机构名称" required>
            <a-input v-model:value="form.orgName" :maxlength="80" placeholder="会作为工作区显示名" />
          </a-form-item>
          <a-form-item label="工作区标识" required :help="idHint"
                       :validate-status="idState === 'taken' || idState === 'invalid' ? 'error' : (idState === 'ok' ? 'success' : '')">
            <a-input v-model:value="form.desiredId" :maxlength="32" placeholder="例如 mingde" @input="onIdInput" @blur="checkId" />
          </a-form-item>
          <a-form-item label="联系人">
            <a-input v-model:value="form.contactName" :maxlength="60" placeholder="谁负责这件事" />
          </a-form-item>
          <a-form-item label="联系邮箱" required extra="邀请绑定邮箱，不可修改">
            <a-input v-model:value="form.email" :maxlength="160" disabled />
          </a-form-item>
          <a-form-item label="管理员密码" required extra="至少 6 位，开通后用于首次登录">
            <a-input-password v-model:value="form.password" :maxlength="128" autocomplete="new-password" placeholder="设置登录密码" />
          </a-form-item>
          <a-form-item label="确认密码" required>
            <a-input-password v-model:value="form.confirm" :maxlength="128" autocomplete="new-password" placeholder="再次输入" />
          </a-form-item>
          <button class="to-submit" type="button" :disabled="busy" @click="submit">
            {{ busy ? '正在开通…' : '开通并进入工作区' }}
          </button>
        </a-form>
        <p class="to-foot">已有账号？<a href="/login">去登录</a></p>
      </template>

      <template v-else>
        <div class="to-done">
          <span class="to-done-badge">已开通</span>
          <h1 class="to-title">「{{ form.orgName }}」工作区已就绪</h1>
          <p class="to-desc">
            工作区标识 <code>{{ doneData.tenantId }}</code>，管理员账号 <code>{{ doneData.adminUid }}</code>。
            请用刚设置的密码登录。
          </p>
          <div class="to-actions">
            <a class="to-btn" :href="'/login?tenant=' + encodeURIComponent(doneData.tenantId || '')">去登录</a>
            <a class="to-link" href="/login">返回登录</a>
          </div>
        </div>
      </template>
    </div>
    <footer class="to-page-foot">问渠 WenQu</footer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { acceptTenantOpenInvite, checkTenantId, previewTenantOpenInvite } from '../api'

const route = useRoute()
const token = computed(() => String(route.params.token || ''))
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const invite = ref({})
const form = reactive({ orgName: '', desiredId: '', contactName: '', email: '', password: '', confirm: '' })
const done = ref(false)
const doneData = ref({})

const idState = ref('')
const idHint = ref('')
let timer = null
function onIdInput () {
  idState.value = ''
  idHint.value = ''
  clearTimeout(timer)
  const v = form.desiredId.trim()
  if (!v) return
  if (!/^[a-z0-9][a-z0-9_-]{1,31}$/.test(v)) {
    idState.value = 'invalid'
    idHint.value = '只能用 2–32 位小写字母、数字、- 或 _'
    return
  }
  timer = setTimeout(checkId, 400)
}
async function checkId () {
  const v = form.desiredId.trim().toLowerCase()
  if (!v) return
  if (!/^[a-z0-9][a-z0-9_-]{1,31}$/.test(v)) { idState.value = 'invalid'; return }
  idState.value = 'checking'
  try {
    const r = await checkTenantId(v)
    const d = (r && r.data) || {}
    if (d.available) { idState.value = 'ok'; idHint.value = '' }
    else { idState.value = d.reason && d.reason.includes('占用') ? 'taken' : 'invalid'; idHint.value = d.reason || '不可用' }
  } catch (e) { idState.value = '' }
}

onMounted(async () => {
  try {
    const r = await previewTenantOpenInvite(token.value)
    if (!r || !r.success) { error.value = (r && r.msg) || '邀请链接无效或已失效'; return }
    invite.value = r.data || {}
    form.orgName = invite.value.orgName || ''
    form.desiredId = invite.value.desiredId || ''
    form.contactName = invite.value.contactName || ''
    form.email = invite.value.contactEmail || ''
    if (form.desiredId) checkId()
  } catch (e) {
    error.value = e.message || '邀请链接无效或已失效'
  } finally {
    loading.value = false
  }
})

async function submit () {
  if (busy.value) return
  if (!form.orgName.trim()) { message.error('要填机构名称'); return }
  if (!form.desiredId.trim()) { message.error('要给工作区起一个标识'); return }
  if (idState.value === 'taken') { message.error('这个标识已被占用'); return }
  if (!form.password || form.password.length < 6) { message.error('密码至少 6 位'); return }
  if (form.password !== form.confirm) { message.error('两次输入的密码不一致'); return }
  busy.value = true
  try {
    const r = await acceptTenantOpenInvite({
      token: token.value,
      orgName: form.orgName.trim(),
      desiredId: form.desiredId.trim().toLowerCase(),
      contactName: form.contactName.trim(),
      email: form.email,
      password: form.password
    })
    if (!r || !r.success) { message.error((r && r.msg) || '开通失败'); return }
    doneData.value = r.data || {}
    done.value = true
    message.success('工作区已开通')
  } catch (e) {
    message.error(e.message || '开通失败，请稍后再试')
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.to-page {
  min-height: 100vh; display: flex; flex-direction: column; align-items: center; gap: 16px;
  padding: 52px 16px; background: var(--app-bg, #f6f7f9);
}
.to-card {
  width: 100%; max-width: 480px; padding: 26px 28px 22px; border-radius: 12px;
  background: var(--app-panel, #fff); border: 1px solid var(--app-border, #eceef1);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.05);
}
.to-brand { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; }
.to-logo { width: 34px; height: 34px; border-radius: 9px; background: var(--app-accent, #2e6be6); color: #fff; display: inline-flex; align-items: center; justify-content: center; font-weight: 700; }
.to-brand-name { font-size: 14px; font-weight: 650; color: var(--app-text, #1f2329); }
.to-brand-sub { font-size: 11px; color: var(--app-text-3, #98a1ab); }
.to-title { margin: 0 0 8px; font-size: 20px; font-weight: 650; color: var(--app-text, #1f2329); }
.to-desc { margin: 0 0 12px; font-size: 13px; line-height: 1.7; color: var(--app-text-2, #66707a); }
.to-note { margin: 0 0 14px; padding: 9px 12px; border-radius: 7px; background: var(--app-accent-weak, #eef2f7); color: var(--app-accent, #2e6be6); font-size: 12px; line-height: 1.6; }
.to-form :deep(.ant-form-item) { margin-bottom: 13px; }
.to-submit { width: 100%; height: 34px; border: 0; border-radius: 7px; background: var(--app-accent, #2e6be6); color: #fff; font-size: 13px; cursor: pointer; }
.to-submit:disabled { opacity: .6; cursor: default; }
.to-foot { margin: 16px 0 0; font-size: 12px; color: var(--app-text-3, #98a1ab); }
.to-loading { padding: 28px 0; text-align: center; font-size: 13px; color: var(--app-text-2, #66707a); }
.to-actions { display: flex; align-items: center; gap: 12px; margin-top: 14px; }
.to-btn { display: inline-flex; align-items: center; height: 32px; padding: 0 14px; border-radius: 6px; background: var(--app-accent, #2e6be6); color: #fff; font-size: 13px; text-decoration: none; }
.to-link { font-size: 12px; color: var(--app-text-2, #66707a); }
.to-page-foot { font-size: 12px; color: var(--app-text-3, #98a1ab); }
.to-done { text-align: center; padding: 14px 0 6px; }
.to-done-badge { display: inline-block; font-size: 11px; font-weight: 650; color: var(--app-ok, #3b8f4e); background: var(--app-ok-weak, #eaf5ec); border-radius: 9px; padding: 2px 9px; margin-bottom: 10px; }
</style>
