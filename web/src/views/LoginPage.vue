<template>
  <div class="login-wrap">
    <div class="login-card">
      <div class="login-brand">
        <span class="brand-mark">渠</span>
        <div>
          <div class="brand-name">问渠</div>
          <div class="brand-slogan">答案，自有源头 · Ask the source.</div>
        </div>
      </div>

      <h3 class="login-title">{{ isInit ? '初始化管理员' : '登录' }}</h3>
      <p class="login-sub">{{ isInit ? '首次使用：创建第一个超级管理员账号' : '请输入账号与密码' }}</p>

      <a-form layout="vertical" @submit.prevent>
        <template v-if="isInit">
          <a-form-item label="用户标识（uid）">
            <a-input v-model:value="form.uid" placeholder="如 admin" autocomplete="off" />
          </a-form-item>
          <a-form-item label="用户名">
            <a-input v-model:value="form.username" placeholder="显示名称，如 管理员" autocomplete="off" />
          </a-form-item>
        </template>
        <a-form-item v-else label="账号">
          <a-input v-model:value="form.identifier" placeholder="uid 或用户名" autocomplete="username" @pressEnter="submit" />
        </a-form-item>

        <a-form-item label="密码">
          <a-input-password v-model:value="form.password" placeholder="请输入密码"
                            :autocomplete="isInit ? 'new-password' : 'current-password'" @pressEnter="submit" />
        </a-form-item>
        <a-form-item v-if="isInit" label="确认密码">
          <a-input-password v-model:value="form.confirm" placeholder="再次输入密码" autocomplete="new-password" @pressEnter="submit" />
        </a-form-item>

        <button class="app-btn login-btn" :disabled="loading" @click="submit">
          {{ loading ? '处理中…' : (isInit ? '创建并进入' : '登录') }}
        </button>
      </a-form>

      <template v-if="!isInit && oidc.enabled">
        <div class="login-divider"><span>或</span></div>
        <button class="app-btn login-btn login-oidc" :disabled="oidcLoading" @click="submitOidc">
          {{ oidcLoading ? '正在跳转…' : `使用 ${oidc.providerName} 登录` }}
        </button>
      </template>

      <p v-if="error" class="login-err">{{ error }}</p>
      <p v-if="isInit" class="login-hint">系统尚无可用账号，创建后即以此账号登录。密码至少 6 位。</p>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { getFirstRun, loginApi, initializeAdmin, getOidcConfig, getOidcLoginUrl } from '../api'
import { setToken } from '../utils/auth'
import './app.css'

const route = useRoute()
const router = useRouter()
const isInit = ref(false)
const loading = ref(false)
const error = ref('')
const form = ref({ identifier: '', uid: '', username: '', password: '', confirm: '' })

// 单点登录：入口只在后端回报「已启用且配置完整」时出现（配置只填一半就显示按钮，点下去必然撞错误页）
const oidc = ref({ enabled: false, providerName: 'OIDC登录' })
const oidcLoading = ref(false)

onMounted(async () => {
  // 单点登录失败时后端会 302 回 /login?oidc_error=...，把原因直接摊开（比只回登录页更可诊断）
  const oe = String(route.query.oidc_error || '').trim()
  if (oe) error.value = oe
  try {
    const r = await getFirstRun()
    if (r && r.success && r.data && r.data.needsInitialize) isInit.value = true
  } catch (e) { /* 探测失败按普通登录页处理 */ }
  try {
    const r = await getOidcConfig()
    if (r && r.success && r.data) {
      oidc.value = {
        enabled: Boolean(r.data.enabled),
        providerName: r.data.providerName || 'OIDC登录'
      }
    }
  } catch (e) { /* 探测失败则不显示入口（本地登录不受影响） */ }
})

async function submitOidc () {
  error.value = ''
  oidcLoading.value = true
  try {
    const r = await getOidcLoginUrl('/chat')
    const url = r && r.data && r.data.loginUrl
    if (!url) { error.value = (r && r.msg) || '单点登录暂不可用'; return }
    // 跳转到 IdP：整页跳转（授权码流程必须由浏览器完成重定向）
    window.location.href = url
  } catch (e) {
    error.value = e.message || '单点登录暂不可用'
    oidcLoading.value = false
  }
}

async function submit () {
  error.value = ''
  const f = form.value
  if (isInit.value) {
    if (!String(f.uid).trim()) { error.value = '请填写用户标识'; return }
    if (!String(f.username).trim()) { error.value = '请填写用户名'; return }
    if (!f.password) { error.value = '请填写密码'; return }
    if (f.password !== f.confirm) { error.value = '两次输入的密码不一致'; return }
  } else {
    if (!String(f.identifier).trim()) { error.value = '请填写账号'; return }
    if (!f.password) { error.value = '请填写密码'; return }
  }
  loading.value = true
  try {
    const r = isInit.value
      ? await initializeAdmin(String(f.uid).trim(), String(f.username).trim(), f.password)
      : await loginApi(String(f.identifier).trim(), f.password)
    const token = r && r.data && r.data.token
    if (!token) { error.value = (r && r.msg) || '登录失败'; return }
    setToken(token)
    message.success(isInit.value ? '管理员已创建' : '登录成功')
    router.replace('/chat')
  } catch (e) {
    error.value = e.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  height: 100vh; display: flex; align-items: center; justify-content: center;
  background: var(--app-bg, #f7f8fa); color: var(--app-text, #1f2329);
}
.login-card {
  width: 380px; background: var(--app-panel, #fff); border: 1px solid var(--app-border, #e6e9ed);
  border-radius: 14px; padding: 26px 26px 20px; box-shadow: 0 6px 24px rgba(31, 35, 41, .06);
}
.login-brand { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; }
.brand-mark {
  width: 32px; height: 32px; border-radius: 9px; background: var(--app-text, #1f2329); color: #fff;
  display: inline-flex; align-items: center; justify-content: center; font-size: 15px; flex: none;
}
.brand-name { font-size: 14px; font-weight: 500; }
.brand-slogan { font-size: 11px; color: var(--app-text3, #98a0aa); }
.login-title { font-size: 16px; font-weight: 500; margin: 0 0 2px; }
.login-sub { font-size: 12px; color: var(--app-text2, #5f6570); margin: 0 0 16px; }
.login-btn { width: 100%; justify-content: center; }
.login-divider {
  display: flex; align-items: center; gap: 8px; margin: 14px 0 12px;
  color: var(--app-text3, #98a0aa); font-size: 11px;
}
.login-divider::before, .login-divider::after { content: ''; flex: 1; height: 1px; background: var(--app-border, #e6e9ed); }
.login-oidc {
  background: transparent; color: var(--app-text, #1f2329);
  border: 1px solid var(--app-border, #e6e9ed);
}
.login-err { color: var(--app-danger, #d4552e); font-size: 12px; margin: 10px 0 0; }
.login-hint { color: var(--app-text3, #98a0aa); font-size: 11px; margin: 10px 0 0; line-height: 1.6; }
</style>
