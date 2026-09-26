<template>
  <div class="oidc-wrap">
    <div class="oidc-card">
      <div class="oidc-brand">
        <span class="brand-mark">渠</span>
        <div>
          <div class="brand-name">问渠</div>
          <div class="brand-slogan">答案，自有源头 · Ask the source.</div>
        </div>
      </div>

      <template v-if="error">
        <h3 class="oidc-title">单点登录未完成</h3>
        <p class="oidc-err">{{ error }}</p>
        <button class="app-btn oidc-btn" @click="backToLogin">返回登录页</button>
      </template>
      <template v-else>
        <h3 class="oidc-title">正在完成登录…</h3>
        <p class="oidc-sub">正在校验单点登录凭据，请稍候。</p>
      </template>
    </div>
  </div>
</template>

<script setup>
// 单点登录回调页（对应后端 /api/ai/auth/oidc/callback 跳转过来的 ?code=xxx）
// 只做一件事：用一次性 code 换登录令牌 → 落本地 → 进系统。
// 令牌不放在 URL 里（避免留在浏览器历史/代理日志），所以这一步必须由前端发起。
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { exchangeOidcCode } from '../api'
import { setToken, ensureAuth } from '../utils/auth'
import './app.css'

const route = useRoute()
const router = useRouter()
const error = ref('')

// 开发期页面可能被热重载触发两次挂载，code 是一次性的 ⇒ 加闸防重复兑换
let exchanged = false

onMounted(async () => {
  if (exchanged) return
  exchanged = true
  const code = String(route.query.code || '').trim()
  if (!code) {
    error.value = '登录链接不完整（缺少 code），请从登录页重新发起单点登录'
    return
  }
  try {
    const r = await exchangeOidcCode(code)
    const payload = (r && r.data) || {}
    if (!payload.token) {
      error.value = (r && r.msg) || '登录失败，请返回登录页重试'
      return
    }
    setToken(payload.token)
    // 强制重拉身份（含菜单树）：上一次会话的缓存可能是别的账号
    await ensureAuth(true)
    message.success('登录成功')
    const target = String(payload.redirectPath || '').trim()
    router.replace(target && target.startsWith('/') ? target : '/chat')
  } catch (e) {
    error.value = e.message || '登录失败，请返回登录页重试'
  }
})

function backToLogin () {
  router.replace('/login')
}
</script>

<style scoped>
.oidc-wrap {
  height: 100vh; display: flex; align-items: center; justify-content: center;
  background: var(--app-bg, #f7f8fa); color: var(--app-text, #1f2329);
}
.oidc-card {
  width: 380px; background: var(--app-panel, #fff); border: 1px solid var(--app-border, #e6e9ed);
  border-radius: 14px; padding: 26px 26px 22px; box-shadow: 0 6px 24px rgba(31, 35, 41, .06);
}
.oidc-brand { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; }
.brand-mark {
  width: 32px; height: 32px; border-radius: 9px; background: var(--app-text, #1f2329); color: #fff;
  display: inline-flex; align-items: center; justify-content: center; font-size: 15px; flex: none;
}
.brand-name { font-size: 14px; font-weight: 500; }
.brand-slogan { font-size: 11px; color: var(--app-text3, #98a0aa); }
.oidc-title { font-size: 16px; font-weight: 500; margin: 0 0 2px; }
.oidc-sub { font-size: 12px; color: var(--app-text2, #5f6570); margin: 0; }
.oidc-err { font-size: 12px; color: var(--app-danger, #d4552e); margin: 8px 0 16px; line-height: 1.6; }
.oidc-btn { width: 100%; justify-content: center; }
</style>
