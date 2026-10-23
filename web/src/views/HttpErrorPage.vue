<template>
  <div class="he-page">
    <div class="he-card">
      <div class="he-code">{{ code }}</div>
      <h1 class="he-title">{{ title }}</h1>
      <p v-if="desc" class="he-desc">{{ desc }}</p>
      <div class="he-actions">
        <a-button v-if="canLogin" type="primary" @click="goLogin">去登录</a-button>
        <a-button @click="goHome">回首页</a-button>
      </div>
    </div>
    <footer class="he-foot">
      <span>内容由 AI 生成，请注意甄别</span>
      <a href="/" class="he-brand">问渠 WenQu</a>
    </footer>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

// 各状态码的兜底文案：没带 ?msg= 时用；带了（后端具体原因）就优先展示 msg，
// 但仍保留这里的说明作为上下文
const PRESET = {
  400: { title: '请求有误', desc: '请检查链接是否完整，或联系管理员。' },
  401: { title: '登录已失效或无权访问', desc: '链接可能已被停止分享或已失效，请联系分享给你的人；如果你就是所有者，登录后可重新生成分享。' },
  403: { title: '无访问权限', desc: '当前账号没有打开这个页面的权限，可联系管理员授权。' },
  404: { title: '页面不存在', desc: '链接可能已失效或从未存在，请确认后重试。' },
  500: { title: '服务器开小差了', desc: '服务暂时不可用，请稍后重试。' }
}

const code = computed(() => {
  const c = String(route.query.code || '401').trim()
  return /^\d{3}$/.test(c) ? c : '401'
})
const preset = computed(() => PRESET[code.value] || { title: '出错啦', desc: '发生了未知错误，请稍后重试。' })
// 后端带过来的具体原因优先展示在标题；没带则回落到该状态码的兜底标题
const msg = computed(() => String(route.query.msg || '').trim())
const title = computed(() => msg.value ? msg.value.slice(0, 120) : preset.value.title)
const desc = computed(() => preset.value.desc)
// 401/403 给「去登录」入口，其余只回首页（避免给无登录语义的 404/500 挂一个登录按钮）
const canLogin = computed(() => code.value === '401' || code.value === '403')

const goLogin = () => router.replace('/login')
const goHome = () => router.replace('/')
</script>

<style scoped>
/* 通用 HTTP 错误页：与分享页同一套页型——
   免登录、居中栏、--app-* 令牌配色，登录页/分享页不经 AppLayout 也拿得到变量（main.js 统一加载） */
.he-page {
  min-height: 100vh; background: var(--app-bg); color: var(--app-text);
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 32px 16px;
}
.he-card {
  width: 100%; max-width: 520px; text-align: center;
  background: var(--app-panel); border: 1px solid var(--app-border);
  border-radius: 14px; padding: 44px 32px 36px;
}
.he-code {
  font-size: 56px; font-weight: 700; line-height: 1;
  color: var(--app-accent); opacity: .9; letter-spacing: 2px; margin-bottom: 14px;
}
.he-title { font-size: 17px; font-weight: 600; margin: 0 0 8px; line-height: 1.5; word-break: break-word; }
.he-desc { font-size: 13px; color: var(--app-text3); margin: 0 0 26px; line-height: 1.7; }
.he-actions { display: flex; justify-content: center; gap: 12px; }
.he-foot {
  width: 100%; max-width: 520px; margin-top: 28px; padding-top: 14px;
  border-top: 1px solid var(--app-border);
  display: flex; align-items: center; gap: 12px;
  font-size: 12px; color: var(--app-text3);
}
.he-brand { margin-left: auto; color: var(--app-accent); text-decoration: none; }

/* 手机：分享/错误链接多在微信/手机浏览器里打开，卡片收内边距、页脚让开安全区 */
@media (max-width: 768px) {
  .he-page { padding: calc(16px + var(--sat, 0px)) 12px calc(16px + var(--sab, 0px)); }
  .he-card { padding: 36px 20px 30px; }
  .he-code { font-size: 46px; }
}
</style>
