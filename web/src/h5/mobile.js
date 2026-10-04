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

const mqNarrow = window.matchMedia(NARROW)
const mqCoarse = window.matchMedia(COARSE)
const mqNoHover = window.matchMedia(NO_HOVER)

// 初值同步求值（不在 onMounted 后再改）：挂载首帧就能拿到正确形态，避免闪跳
export const isNarrow = ref(mqNarrow.matches)
// 两者任一成立即视为「非精确指针」：触屏设备无 hover，鼠标设备指针精确；
// 某些触屏笔记本两者都有（如 Surface），此时以 coarse 为准更贴合实际交互
export const isCoarse = ref(mqCoarse.matches || mqNoHover.matches)
export const isTouch = ref(navigator.maxTouchPoints > 0)

function bind (mq, r) {
  if (!mq.addEventListener) return
  mq.addEventListener('change', e => { r.value = e.matches })
}
bind(mqNarrow, isNarrow)
bind(mqCoarse, isCoarse)
bind(mqNoHover, isCoarse)
