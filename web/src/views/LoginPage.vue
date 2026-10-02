<template>
  <div class="login-wrap">
    <!-- 底部活水波纹：通铺整幅页宽，把品牌叙事与表单连成一个场景（呼应品牌标的三道波线） -->
    <svg class="hero-waves" viewBox="0 0 1440 320" preserveAspectRatio="none" aria-hidden="true">
      <path class="w1" d="M0,160 C180,120 320,196 540,168 C760,140 900,96 1080,132 C1240,164 1360,180 1440,168 L1440,320 L0,320 Z" />
      <path class="w2" d="M0,206 C200,170 360,238 560,214 C780,188 920,150 1100,178 C1260,202 1370,214 1440,206 L1440,320 L0,320 Z" />
      <path class="w3" d="M0,246 C220,214 380,272 580,252 C800,230 940,200 1120,220 C1270,236 1380,248 1440,242 L1440,320 L0,320 Z" />
    </svg>

    <!-- 内容框：限宽居中——背景与波纹通铺全幅，内容不随超宽屏越拉越散 -->
    <div class="login-frame">
      <!-- 「源头」圆点：锚定内容框右上角，与左上品牌区呼应；兼作登录页唯一常驻的主题切换入口——
           视觉仍是 10px 圆点（画在 ::after 上），按钮本体扩到 32px 保证可点，悬停放大提亮提示可交互 -->
      <button class="hero-dot" type="button"
              :title="themeState === 'dark' ? '切换到亮色主题' : '切换到暗色主题'"
              aria-label="切换亮色/暗色主题" @click="toggleTheme"></button>

      <!-- 左：品牌叙事面板。立意取自品牌出处「问渠那得清如许？为有源头活水来」，窄屏整栏隐藏 -->
      <aside class="login-hero">
        <div class="hero-inner">
          <div class="hero-brand">
            <BrandMark :size="44" />
            <div>
              <div class="hero-name">问渠</div>
              <div class="hero-slogan">答案，自有源头 · Ask the source.</div>
            </div>
          </div>
          <h1 class="hero-poem">问渠那得清如许？<br />为有源头活水来。</h1>
          <p class="hero-cite">—— 朱熹《观书有感》</p>
          <ul class="hero-feats">
            <li>知识库问答，答案自带出处、可溯源</li>
            <li>多模型统一接入，按任务自动派遣</li>
            <li>技能与 MCP 工具，即连即用</li>
          </ul>
        </div>
      </aside>

      <!-- 右：登录表单 -->
      <main class="login-pane">
        <div class="login-card">
          <!-- 窄屏（品牌面板隐藏）时才显示的紧凑品牌行 -->
          <div class="login-brand">
            <BrandMark :size="32" class="brand-mark" />
            <div>
              <div class="brand-name">问渠</div>
              <div class="brand-slogan">答案，自有源头 · Ask the source.</div>
            </div>
          </div>

          <!-- 卡片头：标题右侧一道品牌蓝渐隐细线填补留白（呼应右上圆点与底部波纹）；
               副标题只说有信息量的话——输入框自带标签，普通登录只留一句欢迎 -->
          <h3 class="login-title">{{ isInit ? '初始化管理员' : '登录' }}</h3>
          <p class="login-sub">{{ isInit ? '首次使用：创建第一个超级管理员账号' : '欢迎回来' }}</p>

          <a-form layout="vertical" @submit.prevent>
            <template v-if="isInit">
              <a-form-item label="登录账号">
                <a-input v-model:value="form.uid" placeholder="请输入登录账号" autocomplete="off" />
              </a-form-item>
              <a-form-item label="用户昵称">
                <a-input v-model:value="form.username" placeholder="显示昵称，如 管理员" autocomplete="off" />
              </a-form-item>
            </template>
            <a-form-item v-else label="登录账号">
              <a-input v-model:value="form.identifier" placeholder="请输入登录账号" autocomplete="username" @pressEnter="submit" />
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
          <p v-if="isInit" class="login-hint">系统尚无可用账号，创建后即以此账号登录；登录账号创建后不可修改。密码至少 6 位。</p>
        </div>
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { getFirstRun, loginApi, initializeAdmin, getOidcConfig, getOidcLoginUrl } from '../api'
import { setToken } from '../utils/auth'
import BrandMark from '../components/BrandMark.vue'
import { themeState, toggleTheme } from '../utils/theme'
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
    if (!String(f.uid).trim()) { error.value = '请填写登录账号'; return }
    if (!String(f.username).trim()) { error.value = '请填写用户昵称'; return }
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
  position: relative; height: 100vh; height: 100dvh; overflow: hidden;
  /* 整页一张浅色画布：晕染铺满全幅，左右两侧不再各画各的底、也不画分割线 */
  background:
    radial-gradient(760px 400px at 88% 10%, rgba(94, 147, 245, .10), transparent 60%),
    radial-gradient(900px 520px at 6% 88%, rgba(94, 147, 245, .08), transparent 62%),
    radial-gradient(720px 380px at 12% 6%, rgba(94, 147, 245, .10), transparent 60%),
    linear-gradient(165deg, #f3f7fd 0%, #edf2fa 50%, #e7eef9 100%);
  color: var(--app-text, var(--app-text));
}
/* 内容层：通铺全幅；品牌文字绝对定位在最左，登录卡在该层内 margin:auto 即页面水平正中 */
.login-frame {
  position: relative; height: 100%;
}

/* ==================== 左：品牌叙事面板 ====================
   立意取自品牌出处「问渠那得清如许？为有源头活水来」；
   绝对定位锚在页面最左（垂直居中），窄于 1200px 时会与居中卡片重叠，整栏隐藏 */
.login-hero {
  position: absolute; left: 64px; top: 0; bottom: 0; z-index: 1;
  display: flex; align-items: center;
}
/* 「源头」圆点：呼应品牌标右上角的白点，缓慢呼吸；兼作主题切换按钮——
   圆点画在 ::after 上保持 10px 视觉，按钮本体 32px 且居中对齐原圆点位（原中心 top 61 / right 77） */
.hero-dot {
  position: absolute; top: 45px; right: 61px; width: 32px; height: 32px;
  display: flex; align-items: center; justify-content: center;
  padding: 0; border: none; background: transparent; cursor: pointer;
}
.hero-dot::after {
  content: ''; width: 10px; height: 10px; border-radius: 50%;
  background: #2a5fe0; opacity: .85;
  box-shadow: 0 0 0 6px rgba(42, 95, 224, .10), 0 0 26px 8px rgba(42, 95, 224, .16);
  animation: hero-pulse 5s ease-in-out infinite;
  transition: transform .2s ease, box-shadow .2s ease;
}
.hero-dot:hover::after,
.hero-dot:focus-visible::after {
  transform: scale(1.4);
  box-shadow: 0 0 0 8px rgba(42, 95, 224, .14), 0 0 30px 10px rgba(42, 95, 224, .22);
}
.hero-dot:focus-visible { outline: 2px solid #2a5fe0; outline-offset: 2px; border-radius: 50%; }
@keyframes hero-pulse { 0%, 100% { opacity: .85; } 50% { opacity: .45; } }
.hero-inner { position: relative; z-index: 1; padding: 40px 0; }
.hero-brand { display: flex; align-items: center; gap: 12px; margin-bottom: 48px; }
.hero-name { font-size: 18px; font-weight: 600; letter-spacing: 2px; }
.hero-slogan { font-size: 12px; color: var(--app-text3, var(--app-text3)); margin-top: 2px; }
/* 诗句用衬线字：登录页是全站唯一「讲品牌故事」的地方，允许与界面正文的无衬线区分 */
.hero-poem {
  margin: 0; font-family: 'Songti SC', 'STSong', 'SimSun', 'Noto Serif CJK SC', serif;
  font-size: 30px; font-weight: 600; line-height: 1.65; letter-spacing: 1px;
  color: #2450c8;
}
.hero-cite { margin: 14px 0 0; font-size: 13px; color: var(--app-text3, var(--app-text3)); }
.hero-feats { list-style: none; margin: 44px 0 0; padding: 0; display: flex; flex-direction: column; gap: 14px; }
.hero-feats li { display: flex; align-items: center; gap: 10px; font-size: 14px; color: var(--app-text2, var(--app-text2)); }
.hero-feats li::before { content: ''; flex: none; width: 6px; height: 6px; border-radius: 50%; background: rgba(42, 95, 224, .65); }
.hero-waves { position: absolute; left: 0; right: 0; bottom: -2px; width: 100%; height: 200px; pointer-events: none; }
.hero-waves .w1 { fill: rgba(94, 147, 245, .10); }
.hero-waves .w2 { fill: rgba(94, 147, 245, .08); }
.hero-waves .w3 { fill: rgba(42, 95, 224, .10); }
@media (prefers-reduced-motion: reduce) { .hero-dot::after { animation: none; } }

/* 暗色：深底淡蓝晕染，诗句与圆点提亮到可读档 */
html[data-theme='dark'] .login-wrap {
  background:
    radial-gradient(760px 400px at 88% 10%, rgba(91, 140, 240, .10), transparent 60%),
    radial-gradient(900px 520px at 6% 88%, rgba(91, 140, 240, .08), transparent 62%),
    radial-gradient(720px 380px at 12% 6%, rgba(91, 140, 240, .09), transparent 60%),
    linear-gradient(165deg, #161a24 0%, #14161a 50%, #111420 100%);
}
html[data-theme='dark'] .hero-dot::after { background: #5b8cf0; box-shadow: 0 0 0 6px rgba(91, 140, 240, .12), 0 0 26px 8px rgba(91, 140, 240, .18); }
html[data-theme='dark'] .hero-dot:hover::after,
html[data-theme='dark'] .hero-dot:focus-visible::after { box-shadow: 0 0 0 8px rgba(91, 140, 240, .16), 0 0 30px 10px rgba(91, 140, 240, .24); }
html[data-theme='dark'] .hero-dot:focus-visible { outline-color: #5b8cf0; }
html[data-theme='dark'] .hero-poem { color: #9db9f5; }
html[data-theme='dark'] .hero-feats li::before { background: rgba(91, 140, 240, .7); }
html[data-theme='dark'] .hero-waves .w1 { fill: rgba(91, 140, 240, .10); }
html[data-theme='dark'] .hero-waves .w2 { fill: rgba(91, 140, 240, .07); }
html[data-theme='dark'] .hero-waves .w3 { fill: rgba(91, 140, 240, .10); }

/* ==================== 右：表单区 ====================
   通铺全宽的一层，卡片 margin:auto 居中即整页水平正中 */
.login-pane {
  height: 100%; overflow-y: auto;
  display: flex; padding: 32px 20px; box-sizing: border-box;
}
.login-card {
  position: relative; z-index: 1;
  width: 100%; max-width: 384px; margin: auto; padding: 30px 30px 22px;
  background: var(--app-panel, #fff); border: 1px solid var(--app-border, var(--app-border));
  border-radius: 14px; box-shadow: 0 12px 36px rgba(31, 60, 120, .10);
}
html[data-theme='dark'] .login-card { box-shadow: 0 12px 36px rgba(0, 0, 0, .4); }

/* 紧凑品牌行：仅窄屏（品牌面板隐藏时）显示，宽屏与左侧面板重复 */
.login-brand { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; }
/* 品牌标 = BrandMark 组件（自带圆角与品牌渐变） */
.brand-mark { flex: none; display: block; }
.brand-name { font-size: 14px; font-weight: 500; }
.brand-slogan { font-size: 11px; color: var(--app-text3, var(--app-text3)); }
.login-title {
  display: flex; align-items: center; gap: 12px;
  font-size: 20px; font-weight: 600; letter-spacing: 1px; margin: 0 0 6px;
}
/* 标题渐隐细线：从品牌蓝淡出到透明，右端与卡片内边距对齐 */
.login-title::after {
  content: ''; flex: 1; height: 2px; border-radius: 1px;
  background: linear-gradient(90deg, rgba(42, 95, 224, .30), rgba(42, 95, 224, 0));
}
html[data-theme='dark'] .login-title::after {
  background: linear-gradient(90deg, rgba(91, 140, 240, .35), rgba(91, 140, 240, 0));
}
.login-sub { font-size: 13px; color: var(--app-text2, var(--app-text2)); margin: 0 0 18px; }
.login-btn { width: 100%; justify-content: center; }
.login-divider {
  display: flex; align-items: center; gap: 8px; margin: 14px 0 12px;
  color: var(--app-text3, var(--app-text3)); font-size: 11px;
}
.login-divider::before, .login-divider::after { content: ''; flex: 1; height: 1px; background: var(--app-border, var(--app-border)); }
.login-oidc {
  background: transparent; color: var(--app-text, var(--app-text));
  border: 1px solid var(--app-border, var(--app-border));
}
.login-err { color: var(--app-danger, var(--app-danger)); font-size: 12px; margin: 10px 0 0; }
.login-hint { color: var(--app-text3, var(--app-text3)); font-size: 11px; margin: 10px 0 0; line-height: 1.6; }

/* ==================== 响应式 ==================== */
/* 窄于 1200px：左侧品牌文字与居中卡片会重叠，整栏收起，卡片内恢复紧凑品牌行 */
@media (max-width: 1199px) {
  .login-hero { display: none; }
}
@media (min-width: 1200px) {
  .login-brand { display: none; }
}
</style>
