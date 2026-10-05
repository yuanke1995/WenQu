// ==================== 移动端能力判定 ====================
// 三条判据，用途互不重叠，禁止混用：
//   isNarrow —— 布局形态。与 CSS 的 @media (max-width: 768px) 同口径，
//               保证「CSS 切了 JS 也切」，不会出现样式已切而行为没切。
//   isCoarse —— 指针精度（粗指针 / 无 hover）。用于 hover-only 浮层降级、触屏热区放大。
//   isTouch  —— 有无触点。用于拖拽等桌面专属能力的降级。
//
// 为什么不用 navigator.userAgent 判「是不是移动端」：
//   ① UA 分不出 iPad（iPadOS 的 UA 带 Macintosh，会被判成桌面）；
//   ② UA 更分不出「桌面浏览器把窗口缩窄」——而后者必须留在 PC 行为里，
//      一旦误判成移动端就会违反「PC 端行为零改动」。
// 宽度与能力必须分开判：iPad 窄屏分屏既 <768px 又能 hover；桌面窗口缩到 375px 仍能 hover。
//
// 布局形态本身不需要这里的 isNarrow：绝大多数情况用 CSS @media 就够，
// 且 antd 的 modal/drawer/select 虽然 teleport 到 body，但媒体查询匹配的是视口宽度、
// 与 DOM 层级无关，写在 app.css 里的 768 规则对浮层照样命中。
// isNarrow 只在「必须改行为或模板」的地方消费（见 ChatPage / AppLayout）。
import { ref } from 'vue'

const NARROW = '(max-width: 768px)'
const COARSE = '(pointer: coarse)'
const NO_HOVER = '(hover: none)'

// 本模块在 main.js 里是**顶层 import**，顶层代码立即执行 —— 若这里抛错，整个应用白屏。
// 故 matchMedia / maxTouchPoints 全部降级兜底：缺失时按「最保守的 PC 分支」取值
//（非窄屏、精确指针），宁可窄屏退回 PC 布局，也不能让页面打不开。
const mq = q => {
  try { return window.matchMedia ? window.matchMedia(q) : null } catch (e) { return null }
}
const mqNarrow = mq(NARROW)
const mqCoarse = mq(COARSE)
const mqNoHover = mq(NO_HOVER)

// 初值同步求值（不在 onMounted 后再改）：挂载首帧就能拿到正确形态，避免闪跳
export const isNarrow = ref(mqNarrow ? mqNarrow.matches : false)
// 两者任一成立即视为「非精确指针」：触屏设备无 hover，鼠标设备指针精确；
// 某些触屏笔记本两者都有（如 Surface），此时以 coarse 为准更贴合实际交互
export const isCoarse = ref(Boolean((mqCoarse && mqCoarse.matches) || (mqNoHover && mqNoHover.matches)))
export const isTouch = ref(Boolean((navigator.maxTouchPoints || 0) > 0))

function bind (m, r) {
  if (!m || !m.addEventListener) return
  m.addEventListener('change', e => { r.value = e.matches })
}
bind(mqNarrow, isNarrow)
bind(mqCoarse, isCoarse)
bind(mqNoHover, isCoarse)

// ==================== 移动壳（/m/chat）的设备判据 ====================
// 与「布局形态」（isNarrow，宽度 ≤768）分开：手机上把 /chat 交给移动原生壳，
// 判据是**触屏**（isCoarse）且宽度 ≤1024——覆盖手机竖屏/横屏（横屏约 900px）与平板竖屏；
// 桌面（含把窗口拖窄的鼠标用户）isCoarse 恒 false，永远留在 PC 布局（其窄屏行为由既有补丁承担）。
// 为什么不用 isNarrow：手机横屏宽于 768 就不是「窄屏」了，但依然是触屏，仍该进移动壳。
const MOBILE_SHELL_MAX_W = 1024
export const preferMobileShell = () => isCoarse.value && (window.innerWidth || 0) <= MOBILE_SHELL_MAX_W
