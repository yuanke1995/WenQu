<template>
  <!-- 跑动状态行的「活水」记号：问渠品牌标里那三道波线（几何见 chat/waveGlyph.js）。
       仅状态行用，颜色跟父级 currentColor 走（回答头给 accent，暗色下同样成立）。 -->
  <svg class="trace-wave" :width="size" :height="size" :viewBox="`0 0 ${WAVE_VIEWBOX} ${WAVE_VIEWBOX}`"
       fill="none" aria-hidden="true" focusable="false">
    <path v-for="(d, i) in WAVE_PATHS" :key="i" :d="d" class="tw-line"
          stroke="currentColor" :stroke-width="WAVE_STROKE" stroke-linecap="round"
          :style="{ animationDelay: (i * 0.16).toFixed(2) + 's' }" />
  </svg>
</template>

<script setup>
import { WAVE_VIEWBOX, WAVE_PATHS, WAVE_STROKE } from '../chat/waveGlyph.js'

defineProps({ size: { type: Number, default: 14 } })
</script>

<style scoped>
.trace-wave { flex: none; display: block; }
/* 三道依次呼吸（错开一点延迟），像水一层层推过去；不整句变色，也不抢正文注意力 */
.tw-line { opacity: .45; animation: tw-flow 1.4s ease-in-out infinite; }
@keyframes tw-flow { 0%, 100% { opacity: .35; } 50% { opacity: 1; } }
@media (prefers-reduced-motion: reduce) {
  .tw-line { animation: none; opacity: .85; }
}
</style>