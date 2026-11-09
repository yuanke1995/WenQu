<template>
  <div class="join-page" :style="{ '--join-accent': accent }">
    <div class="join-card">
      <div class="join-brand">
        <span class="join-logo" :style="{ background: accent }">{{ brandInitial }}</span>
        <div>
          <div class="join-brand-name">{{ brand.brandName || brand.name || '问渠' }}</div>
          <div class="join-brand-sub">邀请注册</div>
        </div>
      </div>

      <template v-if="loading">
        <div class="join-loading">正在校验邀请…</div>
      </template>

      <template v-else-if="error">
        <h1 class="join-title">邀请不可用</h1>
        <p class="join-desc">{{ error }}</p>
        <div class="join-actions">
          <a class="join-link" href="/login">返回登录</a>
          <a class="join-link" href="/apply">申请开通独立工作区</a>
        </div>
      </template>

      <template v-else>
        <h1 class="join-title">加入{{ brand.brandName || brand.name }}</h1>
        <p class="join-desc">
          <template v-if="brand.inviterName">由 <b>{{ brand.inviterName }}</b> 邀请。</template>
          创建账号后直接进入工作台。
        </p>
        <div class="join-meta">
          <span v-if="roleLabels.length" class="join-roles">
            <span v-for="r in roleLabels" :key="r" class="join-role">{{ r }}</span>
          </span>
          <span class="join-expire">邀请有效期至 {{ expireText }}</span>
        </div>
        <p v-if="brand.emailBound" class="join-hint">限定了邮箱，但持有链接即可注册。</p>

        <a-form layout="vertical" class="join-form" @submit.prevent="submit">
          <a-form-item label="登录账号" required>
            <a-input v-model:value="form.uid" :maxlength="64" autocomplete="off"
                     placeholder="字母、数字、点、下划线或短横线" @pressEnter="submit" />
          </a-form-item>
          <a-form-item label="设置密码" required>
            <a-input-password v-model:value="form.password" :maxlength="128" autocomplete="new-password"
                              placeholder="至少 6 位" @pressEnter="submit" />
          </a-form-item>
          <a-form-item label="姓名" extra="选填，用于成员列表展示">
            <a-input v-model:value="form.username" :maxlength="100" autocomplete="off" @pressEnter="submit" />
          </a-form-item>
          <a-form-item label="通知邮箱" extra="选填。用于接收待处理与失败类邮件提醒">
            <a-input v-model:value="form.email" :maxlength="160" autocomplete="off"
                     placeholder="name@company.com" @pressEnter="submit" />
          </a-form-item>
          <button class="join-submit" type="button" :disabled="busy" @click="submit">
            {{ busy ? '创建中…' : '创建账号并加入' }}
          </button>
        </a-form>
        <p class="join-foot">已有账号？<a href="/login">去登录</a></p>
      </template>
    </div>
    <footer class="join-page-foot">问渠 WenQu</footer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { acceptJoin, previewJoin } from '../api'
import { setToken } from '../utils/auth'

const route = useRoute()
const router = useRouter()
const token = computed(() => String(route.params.token || ''))

const loading = ref(true)
const error = ref('')
const busy = ref(false)
const brand = ref({})
const form = reactive({ uid: '', password: '', username: '', email: '' })

const accent = computed(() => {
  const c = String(brand.value.brandColor || '')
  return /^#[0-9a-fA-F]{6}$/.test(c) ? c : 'var(--app-accent, #2e6be6)'
})
const brandInitial = computed(() => {
  const n = String(brand.value.brandName || brand.value.name || '问').trim()
  return n.slice(0, 1)
})
const roleLabels = computed(() => {
  const known = { user: '成员', admin: '管理员', superadmin: '超级管理员' }
  return (brand.value.roles || []).map(r => known[r] || r)
})
const expireText = computed(() => {
  const s = brand.value.expiresAt
  if (!s) return '—'
  return String(s).replace('T', ' ').slice(0, 16)
})

onMounted(async () => {
  try {
    const r = await previewJoin(token.value)
    if (!r || !r.success) {
      error.value = (r && r.msg) || '邀请链接无效或已失效'
      return
    }
    brand.value = r.data || {}
  } catch (e) {
    error.value = e.message || '邀请链接无效或已失效'
  } finally {
    loading.value = false
  }
})

async function submit () {
  if (busy.value) return
  if (!form.uid.trim()) { message.error('要填登录账号'); return }
  if (!form.password) { message.error('要设置密码'); return }
  busy.value = true
  try {
    const r = await acceptJoin(token.value, {
      uid: form.uid.trim(),
      password: form.password,
      username: form.username.trim(),
      email: form.email.trim()
    })
    if (!r || !r.success) { message.error((r && r.msg) || '加入失败'); return }
    const data = r.data || {}
    if (data.token) setToken(data.token)
    message.success('已加入，正在进入工作台')
    router.replace('/chat')
  } catch (e) {
    message.error(e.message || '加入失败，请稍后再试')
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.join-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 52px 16px;
  background: var(--app-bg, #f6f7f9);
  --join-accent: var(--app-accent, #2e6be6);
}
.join-card {
  width: 100%;
  max-width: 440px;
  padding: 26px 28px 22px;
  border-radius: 12px;
  background: var(--app-panel, #fff);
  border: 1px solid var(--app-border, #eceef1);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.05);
}
.join-brand { display: flex; align-items: center; gap: 10px; }
.join-logo {
  width: 34px; height: 34px; border-radius: 9px; color: #fff;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 15px; font-weight: 600;
}
.join-brand-name { font-size: 14px; font-weight: 600; color: var(--app-text, #1f2329); }
.join-brand-sub { margin-top: 2px; font-size: 11px; color: var(--app-text-3, #98a0aa); letter-spacing: .04em; }
.join-title { margin: 22px 0 0; font-size: 19px; font-weight: 600; color: var(--app-text, #1f2329); }
.join-desc { margin: 8px 0 0; font-size: 13px; line-height: 1.7; color: var(--app-text-2, #66707a); }
.join-meta { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 14px; }
.join-roles { display: inline-flex; gap: 5px; }
.join-role {
  height: 20px; padding: 0 7px; border-radius: 4px; font-size: 11.5px;
  display: inline-flex; align-items: center;
  color: var(--join-accent); background: var(--app-accent-weak, #eef2f7);
}
.join-expire { font-size: 12px; color: var(--app-text-3, #98a0aa); }
.join-hint { margin: 10px 0 0; font-size: 12px; line-height: 1.6; color: var(--app-text-3, #98a0aa); }
.join-form { margin-top: 18px; }
.join-form :deep(.ant-form-item) { margin-bottom: 14px; }
.join-submit {
  width: 100%; height: 38px; margin-top: 4px; border: none; border-radius: 8px;
  background: var(--join-accent); color: #fff; font-size: 13px; cursor: pointer;
}
.join-submit:disabled { opacity: .7; cursor: default; }
.join-actions { margin-top: 18px; }
.join-link { color: var(--join-accent); font-size: 13px; text-decoration: none; }
.join-foot { margin: 16px 0 0; text-align: center; font-size: 12px; color: var(--app-text-3, #98a0aa); }
.join-foot a { color: var(--join-accent); text-decoration: none; }
.join-loading { padding: 36px 0; text-align: center; font-size: 13px; color: var(--app-text-3, #98a0aa); }
.join-page-foot { font-size: 12px; color: var(--app-text-3, #98a0aa); }
</style>
