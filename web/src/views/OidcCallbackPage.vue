<template>
  <div class="oidc-wrap">
    <!-- 底部活水波纹：与登录页同一视觉（品牌标三道波线的呼应） -->
    <svg class="oidc-waves" viewBox="0 0 1440 320" preserveAspectRatio="none" aria-hidden="true">
      <path d="M0,160 C180,120 320,196 540,168 C760,140 900,96 1080,132 C1240,164 1360,180 1440,168 L1440,320 L0,320 Z" fill="rgba(94,147,245,.10)" />
      <path d="M0,206 C200,170 360,238 560,214 C780,188 920,150 1100,178 C1260,202 1370,214 1440,206 L1440,320 L0,320 Z" fill="rgba(94,147,245,.08)" />
      <path d="M0,246 C220,214 380,272 580,252 C800,230 940,200 1120,220 C1270,236 1380,248 1440,242 L1440,320 L0,320 Z" fill="rgba(42,95,224,.10)" />
    </svg>
    <div class="oidc-card">
      <div class="oidc-brand">
        <BrandMark :size="32" class="brand-mark" />
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
import BrandMark from '../components/BrandMark.vue'
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
  position: relative; height: 100vh; display: flex; align-items: center; justify-content: center; overflow: hidden;
  /* 与登录页右侧表单区同款淡晕染，过渡时视觉不断层 */
  background:
    radial-gradient(880px 460px at 100% 0%, rgba(94, 147, 245, .08), transparent 55%),
    radial-gradient(880px 460px at 0% 100%, rgba(42, 95, 224, .07), transparent 55%),
    var(--app-bg, var(--app-bg));
  color: var(--app-text, var(--app-text));
}
.oidc-waves { position: absolute; left: 0; right: 0; bottom: -2px; width: 100%; height: 200px; pointer-events: none; }
html[data-theme='dark'] .oidc-waves { opacity: .8; }
.oidc-card {
  position: relative; z-index: 1;
  width: 380px; background: var(--app-panel, #fff); border: 1px solid var(--app-border, var(--app-border));
  border-radius: 14px; padding: 26px 26px 22px; box-shadow: 0 12px 36px rgba(31, 60, 120, .10);
}
.oidc-brand { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; }
/* 品牌标 = BrandMark 组件（自带圆角与品牌渐变） */
.brand-mark { flex: none; display: block; }
.brand-name { font-size: 14px; font-weight: 500; }
.brand-slogan { font-size: 11px; color: var(--app-text3, var(--app-text3)); }
.oidc-title { font-size: 16px; font-weight: 500; margin: 0 0 2px; }
.oidc-sub { font-size: 12px; color: var(--app-text2, var(--app-text2)); margin: 0; }
.oidc-err { font-size: 12px; color: var(--app-danger, var(--app-danger)); margin: 8px 0 16px; line-height: 1.6; }
.oidc-btn { width: 100%; justify-content: center; }
</style>
