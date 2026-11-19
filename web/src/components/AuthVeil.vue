<template>
  <!-- 登录 ⇄ 工作台 的品牌过渡遮罩（状态由 utils/authTransition.js 驱动）。
       视觉「活水漫屏」：直接把登录页底部那三道水波（.hero-waves）涨起来——
         登录 → 工作台（in）：底部三层半透明蓝水波自下而上漫过登录页，盖满整屏
           （换页藏在最后一帧）→ 遮罩整体淡出，露出已落位的工作台。
         工作台 → 登录（out）：同一套水波再涨一次盖住工作台，换页后淡出，露出对焦后的登录页。
       颜色分层与登录页 hero-waves 完全一致：w1/w2 取 #5E93F5 的 10%/8%，w3 取 #2A5FE0 的 10%；
       暗色换成 #5B8CF0 的同档透明度。水波只动 transform、画布只动 opacity，全程走合成器。 -->
  <div v-if="authVeil.visible" class="auth-veil"
       :class="[authVeil.phase, authVeil.mode, { started: authVeil.phase !== 'idle' }]"
       aria-hidden="true">
    <!-- 画布层：与登录页/工作台同一套渐变。最后一帧才铺满，用来盖住路由切换 -->
    <div class="veil-wash"></div>
    <!-- 三道水波：颜色/叠放顺序同登录页 .hero-waves（w1 最上最淡、w3 最前最深）。
         in（登录）用「向下填充」的波面自下而上涨起；out（退出）用「向上填充」的波面自上而下漫下。 -->
    <svg class="water" viewBox="0 0 1440 900" preserveAspectRatio="none">
      <g class="w w1"><path :d="authVeil.mode === 'out' ? d1u : d1" /></g>
      <g class="w w2"><path :d="authVeil.mode === 'out' ? d2u : d2" /></g>
      <g class="w w3"><path :d="authVeil.mode === 'out' ? d3u : d3" /></g>
    </svg>
  </div>
</template>

<script setup>
import { authVeil } from '../utils/authTransition'

// 与 LoginPage 底部水波同样的和缓大曲线：从波峰(crest)向下填充到很远(3000)，
// 这样整组上移 translateY 到底时波峰越过屏幕顶部，水面必然铺满、波谷也不漏底。
function wavePath (crest, amp, wl, phase) {
  const W = 1440
  let d = `M -120 3000 L -120 ${(crest + amp * Math.sin((-120 / wl) * Math.PI * 2 + phase)).toFixed(1)} `
  for (let x = -120; x <= W + 120; x += 24) {
    const y = crest + amp * Math.sin((x / wl) * Math.PI * 2 + phase)
    d += `L ${x.toFixed(1)} ${y.toFixed(1)} `
  }
  d += `L ${W + 120} 3000 Z`
  return d
}
// 三道波峰高度与登录页底部一致：w1 最高最淡、w2 次之、w3 最低最深（最前）
const d1 = wavePath(470, 26, 1000, 0.2)
const d2 = wavePath(560, 24, 900, 1.4)
const d3 = wavePath(650, 22, 820, 2.4)

// 退出方向：同样三条波峰，但向上填充到 -3000。这样整组自上而下平移时，
// 领先的是「波面下沿」，水就像从顶上漫下来（与登录时的自下而上涨起相反）。
function wavePathDown (crest, amp, wl, phase) {
  const W = 1440
  let d = `M -120 -3000 L -120 ${(crest + amp * Math.sin((-120 / wl) * Math.PI * 2 + phase)).toFixed(1)} `
  for (let x = -120; x <= W + 120; x += 24) {
    const y = crest + amp * Math.sin((x / wl) * Math.PI * 2 + phase)
    d += `L ${x.toFixed(1)} ${y.toFixed(1)} `
  }
  d += `L ${W + 120} -3000 Z`
  return d
}
const d1u = wavePathDown(470, 26, 1000, 0.2)
const d2u = wavePathDown(560, 24, 900, 1.4)
const d3u = wavePathDown(650, 22, 820, 2.4)
</script>

<style scoped>
.auth-veil {
  position: fixed; inset: 0; z-index: 1000;
  overflow: hidden; pointer-events: auto;   /* 过渡期间拦住误点 */
}

/* ① 画布：与登录页同一套渐变；两种模式都在最后一帧才铺满，盖住换页硬切 */
.veil-wash {
  position: absolute; inset: 0; opacity: 0;
  background:
    radial-gradient(760px 400px at 88% 10%, rgba(94, 147, 245, .10), transparent 60%),
    radial-gradient(900px 520px at 6% 88%, rgba(94, 147, 245, .08), transparent 62%),
    radial-gradient(720px 380px at 12% 6%, rgba(94, 147, 245, .10), transparent 60%),
    linear-gradient(165deg, #f3f7fd 0%, #edf2fa 50%, #e7eef9 100%);
}
.auth-veil.in.started .veil-wash { animation: wq-wash-in .48s ease-out both; }
.auth-veil.out.started .veil-wash { animation: wq-wash-in .4s ease-out both; }
@keyframes wq-wash-in { 0%, 55% { opacity: 0; } 100% { opacity: 1; } }

/* ② 三道水波：颜色分层与登录页 .hero-waves 完全一致（半透明叠色，不是实心蓝） */
.auth-veil .water { position: absolute; inset: 0; width: 100%; height: 100%; display: block; }
.auth-veil .w { transform: translateY(820px); will-change: transform; }
.auth-veil .w path { stroke: none; }
.auth-veil .w1 path { fill: rgba(94, 147, 245, .10); }
.auth-veil .w2 path { fill: rgba(94, 147, 245, .08); }
.auth-veil .w3 path { fill: rgba(42, 95, 224, .10); }

/* ③ cover：水波自下而上漫起。三层错峰，故能看到一道道水浪往上走；
     终值各让自身波峰越过顶部，保证盖满。in 480ms：0.38+0.08=0.46s 铺满再放行。
     out 400ms：0.3+0.08=0.38s 内铺满。 */
.auth-veil.in.started .w1 { animation: wq-rise-1 .38s cubic-bezier(.5, .05, .2, 1) both; }
.auth-veil.in.started .w2 { animation: wq-rise-2 .38s cubic-bezier(.5, .05, .2, 1) .04s both; }
.auth-veil.in.started .w3 { animation: wq-rise-3 .38s cubic-bezier(.5, .05, .2, 1) .08s both; }
.auth-veil.out.started .w1 { animation: wq-fall-1 .3s cubic-bezier(.5, .05, .2, 1) both; }
.auth-veil.out.started .w2 { animation: wq-fall-2 .3s cubic-bezier(.5, .05, .2, 1) .04s both; }
.auth-veil.out.started .w3 { animation: wq-fall-3 .3s cubic-bezier(.5, .05, .2, 1) .08s both; }
@keyframes wq-rise-1 { from { transform: translateY(820px); } to { transform: translateY(-560px); } }
@keyframes wq-rise-2 { from { transform: translateY(820px); } to { transform: translateY(-640px); } }
@keyframes wq-rise-3 { from { transform: translateY(820px); } to { transform: translateY(-720px); } }
/* 退出：水从顶上往下降。起点整组在屏幕上方（波面藏在顶外），终点波面落到底部以下 → 盖满。 */
@keyframes wq-fall-1 { from { transform: translateY(-980px); } to { transform: translateY(520px); } }
@keyframes wq-fall-2 { from { transform: translateY(-980px); } to { transform: translateY(420px); } }
@keyframes wq-fall-3 { from { transform: translateY(-980px); } to { transform: translateY(320px); } }

/* ④ reveal：不是把整层淡掉（那样会先一片空白、再整页突然显形），而是让整片水层
     顺着 cover 的方向滑走——登录时水继续往上退，退出时水往下退。新页面被水线
     自上/下一点点露出来，连续、没有"啵"的一下。滑走方向与 cover 同向，观感连贯。 */
.auth-veil.reveal { pointer-events: none; }
/* 登录：水升上来盖住后，再"退"下去（往下滑），工作台从顶部被露出；
   退出：水从上面压下来后，再"收"回上方（往上滑），登录页从底部被露出。
   都是"水来了又退回去"，比淡出更像水、也没有整页突然显形。 */
.auth-veil.reveal.in { animation: wq-drain-down .42s cubic-bezier(.5, 0, .2, 1) both; }
.auth-veil.reveal.out { animation: wq-drain-up .42s cubic-bezier(.5, 0, .2, 1) both; }
@keyframes wq-drain-up { from { transform: translateY(0); } to { transform: translateY(-102%); } }
@keyframes wq-drain-down { from { transform: translateY(0); } to { transform: translateY(102%); } }

/* ==================== 暗色 ==================== */
html[data-theme='dark'] .veil-wash {
  background:
    radial-gradient(760px 400px at 88% 10%, rgba(91, 140, 240, .10), transparent 60%),
    radial-gradient(900px 520px at 6% 88%, rgba(91, 140, 240, .08), transparent 62%),
    radial-gradient(720px 380px at 12% 6%, rgba(91, 140, 240, .09), transparent 60%),
    linear-gradient(165deg, #161a24 0%, #14161a 50%, #111420 100%);
}
html[data-theme='dark'] .w1 path { fill: rgba(91, 140, 240, .10); }
html[data-theme='dark'] .w2 path { fill: rgba(91, 140, 240, .07); }
html[data-theme='dark'] .w3 path { fill: rgba(91, 140, 240, .10); }

/* 减弱动效：只留极短的一次覆盖 */
@media (prefers-reduced-motion: reduce) {
  .auth-veil .veil-wash,
  .auth-veil .w,
  .auth-veil.in.started .w,
  .auth-veil.out.started .w { animation-duration: .06s !important; animation-delay: 0s !important; }
}
</style>
