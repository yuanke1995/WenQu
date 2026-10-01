<script>
// 模块级实例计数：同页多实例（如侧栏+欢迎屏同时挂载）时给渐变 id 去重，避免 SVG id 重复引用
let brandUid = 0
</script>

<script setup>
// 问渠品牌标（产品图标）：蓝渐变圆角方 + 三道活水波线 + 右上「源头」圆点。
// 立意：「问渠那得清如许？为有源头活水来」——波线既是活水，也似知识条目；圆点即"源头"。
// 渐变与波线为固定品牌色，明暗主题通用（品牌标的裸色属有意保留，不在 app.css 的 --app-* 令牌体系内；
// 替代旧版 var(--app-text) 文字方块，暗色下不再反白刺眼）。
defineProps({ size: { type: Number, default: 24 } })

const gid = `wq-brand-g-${++brandUid}`
</script>

<template>
  <svg :width="size" :height="size" viewBox="0 0 64 64" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
    <defs>
      <linearGradient :id="gid" x1="14" y1="4" x2="50" y2="60" gradientUnits="userSpaceOnUse">
        <stop offset="0" stop-color="#5E93F5" />
        <stop offset="1" stop-color="#2A5FE0" />
      </linearGradient>
    </defs>
    <rect x="3" y="3" width="58" height="58" rx="17" :fill="`url(#${gid})`" />
    <circle cx="46" cy="16.5" r="3.6" fill="#fff" />
    <path d="M18 25c4.5-5.2 9-5.2 13.5 0s9 5.2 13.5 0" stroke="#fff" stroke-width="5.4" stroke-linecap="round" />
    <path d="M16 35c5-5.6 10-5.6 15 0s10 5.6 15 0" stroke="#fff" stroke-width="5.4" stroke-linecap="round" />
    <path d="M16 45c5-5.6 10-5.6 15 0s10 5.6 15 0" stroke="#fff" stroke-width="5.4" stroke-linecap="round" />
  </svg>
</template>
