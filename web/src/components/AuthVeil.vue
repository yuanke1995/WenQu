<template>
  <!-- 登录 ⇄ 工作台 的品牌过渡遮罩（状态由 utils/authTransition.js 驱动）。
       in（登录 → 工作台）：从品牌标出发的推镜——
         源头圆点亮起 → 三道涟漪散开 → 三道活水线画出 → 品牌标胀开成「门」
         → 光井绽放 → 画布铺满（换页藏在最后一帧）→ 遮罩整体退去。
       out（工作台 → 登录）：同一套元素的逆放——工作台以品牌标为圆心后退失焦，
         涟漪向内收拢、品牌门收成源头圆点、光井收束，登录页再从失焦中落位。
       两种模式共用同一个根节点，reveal 阶段统一整层淡出。 -->
  <div v-if="authVeil.visible" class="auth-veil"
       :class="[authVeil.phase, authVeil.mode, { started: authVeil.phase !== 'idle' }]"
       :style="anchorStyle" aria-hidden="true">

    <!-- 画布层：与登录页同一套渐变。两种模式都在最后一帧才铺满，用来盖住路由切换 -->
    <div class="veil-wash"></div>

    <!-- 活水线只在推镜（in）出现；收镜（out）靠涟漪与门反向收拢就够了 -->
    <svg v-if="authVeil.mode === 'in'" class="veil-streams" viewBox="0 0 1440 900" preserveAspectRatio="none">
      <path pathLength="1" d="M-80,286 C180,226 320,346 560,306 C800,266 940,186 1160,226 C1320,254 1400,286 1520,272" />
      <path pathLength="1" d="M-80,420 C200,360 360,470 600,436 C840,402 980,330 1200,368 C1360,396 1420,424 1520,412" />
      <path pathLength="1" d="M-80,554 C220,500 380,598 620,570 C860,542 1000,476 1220,508 C1370,530 1430,556 1520,546" />
    </svg>
    <!-- 顺序即叠放顺序：涟漪 → 门 → 圆点 → 光井 -->
    <div class="veil-ripples"><i></i><i></i><i></i></div>
    <!-- 品牌标「门」只属于推镜；收镜只收成右上角那颗圆点，不再出现图标 -->
    <div v-if="authVeil.mode === 'in'" class="veil-gate"><BrandMark :size="52" /></div>
    <div class="veil-seed"></div>
    <div class="veil-glow"></div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import BrandMark from './BrandMark.vue'
import { authVeil } from '../utils/authTransition'

// 涟漪、光井、门的圆心：推镜落在登录页品牌标上；收镜落在登录页右上角的源头圆点上
// （out 的位置由样式里的 --ax/--ay 给出，见 .auth-veil.out）
const anchorStyle = computed(() => authVeil.mode === 'in'
  ? { '--ax': authVeil.anchor.x + 'px', '--ay': authVeil.anchor.y + 'px' }
  : {})
</script>

<style scoped>
.auth-veil {
  position: fixed; inset: 0; z-index: 1000;
  overflow: hidden; pointer-events: auto;   /* 过渡期间拦住误点 */
}

/* 整体退场：不动子元素，让仍在中途的品牌层自然被遮罩带走 */
.auth-veil.reveal { animation: veil-fade-out .42s cubic-bezier(.4, 0, .2, 1) both; }
@keyframes veil-fade-out { from { opacity: 1; } to { opacity: 0; } }

/* ① 画布层：登录页同一套渐变。两种模式都在最后一帧才铺满，让收放动作完整可见 */
.veil-wash {
  position: absolute; inset: 0; opacity: 0;
  background:
    radial-gradient(760px 400px at 88% 10%, rgba(94, 147, 245, .10), transparent 60%),
    radial-gradient(900px 520px at 6% 88%, rgba(94, 147, 245, .08), transparent 62%),
    radial-gradient(720px 380px at 12% 6%, rgba(94, 147, 245, .10), transparent 60%),
    linear-gradient(165deg, #f3f7fd 0%, #edf2fa 50%, #e7eef9 100%);
}
.auth-veil.in.started .veil-wash { animation: veil-wash-in .48s ease-out both; }
@keyframes veil-wash-in {
  0%, 78% { opacity: 0; }
  100% { opacity: 1; }
}
.auth-veil.out.started .veil-wash { animation: veil-wash-in-out .42s ease-out both; }
@keyframes veil-wash-in-out {
  0%, 72% { opacity: 0; }
  100% { opacity: 1; }
}

/* ==================== in 模式：从品牌标推镜 ==================== */
/* ③ 三道活水线：从品牌标方向横穿画面，逐条画出后整层退去 */
.veil-streams {
  position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none;
}
.veil-streams path {
  fill: none; stroke: rgba(46, 107, 230, .30); stroke-width: 1.4;
  stroke-linecap: round; stroke-dasharray: 1; stroke-dashoffset: 1;
}
.auth-veil.in.started .veil-streams path { animation: stream-draw .76s cubic-bezier(.3, .6, .2, 1) both; }
.auth-veil.in.started .veil-streams path:nth-child(2) { animation-delay: .09s; }
.auth-veil.in.started .veil-streams path:nth-child(3) { animation-delay: .18s; }
@keyframes stream-draw { to { stroke-dashoffset: 0; } }
.auth-veil.in.started .veil-streams { animation: streams-fade .42s ease-in .54s both; }
@keyframes streams-fade { from { opacity: 1; } to { opacity: 0; } }

/* ④ 三道涟漪：对应品牌标里的三道波线，从圆心依次散开 */
.veil-ripples { position: absolute; inset: 0; pointer-events: none; }
.veil-ripples i {
  position: absolute; left: var(--ax); top: var(--ay);
  width: 280px; height: 280px; border-radius: 50%;
  border: 1px solid rgba(46, 107, 230, .34); opacity: 0;
  transform: translate(-50%, -50%) scale(.06);
}
.veil-ripples i:nth-child(2) { border-color: rgba(46, 107, 230, .26); }
.veil-ripples i:nth-child(3) { border-color: rgba(46, 107, 230, .18); }
.auth-veil.in.started .veil-ripples i { animation: ripple-out 1s cubic-bezier(.16, .7, .3, 1) both; }
.auth-veil.in.started .veil-ripples i:nth-child(2) { animation-duration: 1.14s; animation-delay: .08s; }
.auth-veil.in.started .veil-ripples i:nth-child(3) { animation-duration: 1.28s; animation-delay: .16s; }
@keyframes ripple-out {
  0%   { transform: translate(-50%, -50%) scale(.06); opacity: .5; }
  100% { transform: translate(-50%, -50%) scale(2.4); opacity: 0; }
}

/* ⑤ 品牌标「门」：在原点胀开，镜头从它中间穿过去 */
.veil-gate {
  position: absolute; left: var(--ax); top: var(--ay); width: 52px; height: 52px;
  opacity: 0; pointer-events: none;
  transform: translate(-50%, -50%) scale(.9);
}
.auth-veil.in.started .veil-gate { animation: gate-open .9s cubic-bezier(.16, 1, .3, 1) .07s both; }
@keyframes gate-open {
  0%   { transform: translate(-50%, -50%) scale(.9); opacity: 0; filter: blur(0); }
  18%  { transform: translate(-50%, -50%) scale(1.28); opacity: .92; }
  55%  { transform: translate(-50%, -50%) scale(1.42); opacity: .88; }
  100% { transform: translate(-50%, -50%) scale(2.6); opacity: 0; filter: blur(3px); }
}

/* ⑥ 源头圆点：品牌标右上那颗白点，在起点亮起后隐去 */
.veil-seed {
  position: absolute; left: var(--ax); top: var(--ay);
  width: 9px; height: 9px; border-radius: 50%; background: #fff; opacity: 0;
  box-shadow: 0 0 0 2px rgba(46, 107, 230, .30), 0 0 26px 8px rgba(46, 107, 230, .42);
  transform: translate(-50%, -50%);
}
.auth-veil.in.started .veil-seed { animation: seed-life .8s ease-out both; }
@keyframes seed-life {
  0%   { transform: translate(-50%, -50%) scale(.4); opacity: 0; }
  18%  { transform: translate(-50%, -50%) scale(1); opacity: 1; }
  60%  { opacity: 1; }
  82%, 100% { opacity: 0; }
}

/* ⑦ 光井：穿过品牌标之后迎面而来的光 */
.veil-glow {
  position: absolute; left: var(--ax); top: var(--ay);
  width: 560px; height: 560px; border-radius: 50%; opacity: 0;
  filter: blur(8px); pointer-events: none;
  background: radial-gradient(circle, rgba(255, 255, 255, .98) 0%, rgba(196, 219, 255, .60) 34%, rgba(196, 219, 255, 0) 70%);
  transform: translate(-50%, -50%) scale(.25);
}
.auth-veil.in.started .veil-glow { animation: glow-bloom .9s ease-out .2s both; }
@keyframes glow-bloom {
  0%   { transform: translate(-50%, -50%) scale(.25); opacity: 0; }
  55%  { transform: translate(-50%, -50%) scale(1); opacity: .9; }
  100% { transform: translate(-50%, -50%) scale(1.7); opacity: 0; }
}

/* ==================== out 模式：反向收镜 ====================
   落点是登录页右上角的源头圆点（与 LoginPage 的 .hero-dot 位置成对）：
   工作台以它为圆心后退，涟漪向内收拢、品牌门缩成一点、光井收束，
   换页后登录页正是从这颗圆点旁落位。 */
.auth-veil.out {
  --ax: calc(100vw - 77px);
  --ay: 61px;
}
@media (max-width: 768px) {
  .auth-veil.out {
    --ax: calc(100vw - 24px);
    --ay: calc(8px + var(--sat, 0px) + 16px);
  }
}
.auth-veil.out.started .veil-ripples i { animation: ripple-in .95s cubic-bezier(.3, .65, .2, 1) both; }
.auth-veil.out.started .veil-ripples i:nth-child(2) { animation-duration: 1.08s; animation-delay: .06s; }
.auth-veil.out.started .veil-ripples i:nth-child(3) { animation-duration: 1.22s; animation-delay: .12s; }
@keyframes ripple-in {
  0%   { transform: translate(-50%, -50%) scale(2.4); opacity: 0; }
  25%  { opacity: .38; }
  100% { transform: translate(-50%, -50%) scale(.02); opacity: 0; }
}
.auth-veil.out.started .veil-seed { animation: seed-life-out .7s ease-in both; }
@keyframes seed-life-out {
  0%   { transform: translate(-50%, -50%) scale(.4); opacity: 0; }
  16%  { transform: translate(-50%, -50%) scale(1); opacity: 1; }
  52%  { opacity: 1; }
  80%, 100% { opacity: 0; }
}
.auth-veil.out.started .veil-glow { animation: glow-collapse .85s ease-in both; }
@keyframes glow-collapse {
  0%   { transform: translate(-50%, -50%) scale(1.5); opacity: 0; }
  28%  { transform: translate(-50%, -50%) scale(1.1); opacity: .72; }
  100% { transform: translate(-50%, -50%) scale(.08); opacity: 0; }
}
/* 收镜终点的圆点就是登录页那颗蓝点本身，换成品牌蓝而不是推镜用的白光点 */
.auth-veil.out .veil-seed {
  background: #2a5fe0;
  box-shadow: 0 0 0 2px rgba(42, 95, 224, .18), 0 0 22px 6px rgba(42, 95, 224, .35);
}
html[data-theme='dark'] .auth-veil.out .veil-seed {
  background: #5b8cf0;
  box-shadow: 0 0 0 2px rgba(91, 140, 240, .20), 0 0 22px 6px rgba(91, 140, 240, .38);
}

/* ==================== 暗色 ==================== */
html[data-theme='dark'] .veil-wash {
  background:
    radial-gradient(760px 400px at 88% 10%, rgba(91, 140, 240, .10), transparent 60%),
    radial-gradient(900px 520px at 6% 88%, rgba(91, 140, 240, .08), transparent 62%),
    radial-gradient(720px 380px at 12% 6%, rgba(91, 140, 240, .09), transparent 60%),
    linear-gradient(165deg, #161a24 0%, #14161a 50%, #111420 100%);
}
html[data-theme='dark'] .veil-streams path { stroke: rgba(91, 140, 240, .32); }
html[data-theme='dark'] .veil-ripples i { border-color: rgba(91, 140, 240, .32); }
html[data-theme='dark'] .veil-ripples i:nth-child(2) { border-color: rgba(91, 140, 240, .24); }
html[data-theme='dark'] .veil-ripples i:nth-child(3) { border-color: rgba(91, 140, 240, .16); }
html[data-theme='dark'] .veil-glow {
  background: radial-gradient(circle, rgba(120, 160, 240, .55) 0%, rgba(70, 105, 180, .28) 36%, rgba(70, 105, 180, 0) 70%);
}

/* 减弱动效：只保留一次极短的遮挡 */
@media (prefers-reduced-motion: reduce) {
  .auth-veil,
  .auth-veil .veil-wash,
  .auth-veil .veil-streams,
  .auth-veil .veil-streams path,
  .auth-veil .veil-ripples i,
  .auth-veil .veil-gate,
  .auth-veil .veil-seed,
  .auth-veil .veil-glow {
    animation-duration: .06s !important;
    animation-delay: 0s !important;
  }
}
</style>
