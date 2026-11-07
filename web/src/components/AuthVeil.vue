<template>
  <!-- 登录 ⇄ 工作台 的品牌过渡遮罩（状态由 utils/authTransition.js 驱动）。
       三层叠在一起：
       1) veil-blur：backdrop-filter 把离场页面滤镜化模糊，揭开时 blur 归零、页面"对焦"；
       2) veil-wash：登录页同一张浅蓝画布，盖住路由切换的瞬间（避免两页硬切的那一帧）；
       3) veil-waves：品牌三道活水波纹，盖住时自下而上漫起，揭开时退去。
       中心品牌标是两页的视觉锚点：登录页左上角的小标 → 工作台欢迎屏的大标，
       过渡期间它先在屏幕中心短暂停留（揭开时很快隐去，不和欢迎屏的标叠在一起）。 -->
  <div v-if="authVeil.visible" class="auth-veil" :class="[authVeil.phase, authVeil.mode]"
       aria-hidden="true">
    <div class="veil-blur"></div>
    <div class="veil-wash"></div>
    <svg class="veil-waves" viewBox="0 0 1440 320" preserveAspectRatio="none">
      <path class="w1" d="M0,160 C180,120 320,196 540,168 C760,140 900,96 1080,132 C1240,164 1360,180 1440,168 L1440,320 L0,320 Z" />
      <path class="w2" d="M0,206 C200,170 360,238 560,214 C780,188 920,150 1100,178 C1260,202 1370,214 1440,206 L1440,320 L0,320 Z" />
      <path class="w3" d="M0,246 C220,214 380,272 580,252 C800,230 940,200 1120,220 C1270,236 1380,248 1440,242 L1440,320 L0,320 Z" />
    </svg>
    <div class="veil-brand">
      <BrandMark :size="38" />
      <span class="veil-name">问渠</span>
    </div>
  </div>
</template>

<script setup>
import BrandMark from './BrandMark.vue'
import { authVeil } from '../utils/authTransition'
</script>

<style scoped>
.auth-veil {
  position: fixed; inset: 0; z-index: 1000;
  overflow: hidden; pointer-events: auto;   /* 过渡期间拦住误点 */
}

/* ① 滤镜层：透明背景 + 只靠 backdrop-filter 出效果。
   不能写 opacity:0——backdrop-filter 在完全透明的元素上不生效 */
.veil-blur { position: absolute; inset: 0; }
.auth-veil.cover .veil-blur { animation: veil-blur-in .22s ease-out both; }
.auth-veil.reveal .veil-blur { animation: veil-blur-out .42s ease-in both; }
@keyframes veil-blur-in {
  from { backdrop-filter: blur(0); -webkit-backdrop-filter: blur(0); }
  to   { backdrop-filter: blur(16px) saturate(.92); -webkit-backdrop-filter: blur(16px) saturate(.92); }
}
@keyframes veil-blur-out {
  from { backdrop-filter: blur(16px) saturate(.92); -webkit-backdrop-filter: blur(16px) saturate(.92); }
  to   { backdrop-filter: blur(0); -webkit-backdrop-filter: blur(0); }
}

/* ② 画布层：与 LoginPage 的 .login-wrap 同一套渐变（两页共用一张底，观感连续）。
   盖住时 .30s 内铺满；揭开时 .20s 先退，让汇聚动作尽快露出来 */
.veil-wash {
  position: absolute; inset: 0; opacity: 0;
  background:
    radial-gradient(760px 400px at 88% 10%, rgba(94, 147, 245, .10), transparent 60%),
    radial-gradient(900px 520px at 6% 88%, rgba(94, 147, 245, .08), transparent 62%),
    radial-gradient(720px 380px at 12% 6%, rgba(94, 147, 245, .10), transparent 60%),
    linear-gradient(165deg, #f3f7fd 0%, #edf2fa 50%, #e7eef9 100%);
}
.auth-veil.cover .veil-wash { animation: veil-wash-in .30s ease-out both; }
.auth-veil.reveal .veil-wash { animation: veil-wash-out .20s ease-in both; }
@keyframes veil-wash-in { from { opacity: 0; } to { opacity: 1; } }
@keyframes veil-wash-out { from { opacity: 1; } to { opacity: 0; } }

html[data-theme='dark'] .veil-wash {
  background:
    radial-gradient(760px 400px at 88% 10%, rgba(91, 140, 240, .10), transparent 60%),
    radial-gradient(900px 520px at 6% 88%, rgba(91, 140, 240, .08), transparent 62%),
    radial-gradient(720px 380px at 12% 6%, rgba(91, 140, 240, .09), transparent 60%),
    linear-gradient(165deg, #161a24 0%, #14161a 50%, #111420 100%);
}

/* ③ 波纹层：盖住时自下而上漫起（呼应品牌标的三道波线），揭开时向下退去 */
.veil-waves {
  position: absolute; left: 0; right: 0; bottom: -1px;
  width: 100%; height: 46%; pointer-events: none;
}
.veil-waves .w1 { fill: rgba(94, 147, 245, .10); }
.veil-waves .w2 { fill: rgba(94, 147, 245, .08); }
.veil-waves .w3 { fill: rgba(42, 95, 224, .10); }
.auth-veil.cover .veil-waves { animation: veil-waves-rise .26s ease-out both; }
.auth-veil.reveal .veil-waves { animation: veil-waves-sink .38s ease-in both; }
@keyframes veil-waves-rise {
  from { transform: translateY(108%); opacity: .4; }
  to   { transform: translateY(0); opacity: 1; }
}
@keyframes veil-waves-sink {
  from { transform: translateY(0); opacity: 1; }
  to   { transform: translateY(44%); opacity: 0; }
}
html[data-theme='dark'] .veil-waves .w1 { fill: rgba(91, 140, 240, .10); }
html[data-theme='dark'] .veil-waves .w2 { fill: rgba(91, 140, 240, .07); }
html[data-theme='dark'] .veil-waves .w3 { fill: rgba(91, 140, 240, .10); }

/* 中心品牌标：短暂停留的视觉锚点，揭开时 .12s 就退场（避免与欢迎屏品牌标重叠） */
.veil-brand {
  position: absolute; inset: 0; opacity: 0;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 12px; pointer-events: none;
}
.veil-name {
  font-size: 13px; letter-spacing: 4px; font-weight: 500;
  color: var(--app-accent, #2e6be6);
}
.auth-veil.cover .veil-brand { animation: veil-brand-in .22s cubic-bezier(.2, .8, .3, 1) both; }
.auth-veil.reveal .veil-brand { animation: veil-brand-out .12s ease-in both; }
@keyframes veil-brand-in {
  from { opacity: 0; transform: translateY(6px) scale(.96); }
  to   { opacity: 1; transform: translateY(0) scale(1); }
}
@keyframes veil-brand-out { from { opacity: 1; } to { opacity: 0; } }

/* 减弱动效：不做位移/模糊，只保留一次极短的遮挡 */
@media (prefers-reduced-motion: reduce) {
  .auth-veil.cover .veil-blur,
  .auth-veil.cover .veil-wash,
  .auth-veil.cover .veil-waves,
  .auth-veil.cover .veil-brand,
  .auth-veil.reveal .veil-blur,
  .auth-veil.reveal .veil-wash,
  .auth-veil.reveal .veil-waves,
  .auth-veil.reveal .veil-brand { animation-duration: .06s; }
}
</style>
