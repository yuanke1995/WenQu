<template>
  <!-- 通用底部 sheet（移动壳的地基交互件）：遮罩点击关闭、圆角在上、底部让开 Home Indicator。
       键盘弹起时 --kb 收缩 sheet 的可用高度（输入类 sheet 的输入框不会被键盘盖住）。 -->
  <Teleport to="body">
    <div v-if="open" class="bs-mask" :class="{ 'bs-flying': flying }" :style="maskStyle" @click.self="close">
      <section class="bs-sheet" :class="{ 'bs-dragging': dragging }" :style="sheetStyle"
               role="dialog" :aria-label="title"
               @pointerdown="onDown" @pointermove="onMove" @pointerup="onUp" @pointercancel="onUp">
        <div class="bs-grab" aria-hidden="true"></div>
        <header class="bs-head">
          <div class="bs-titles">
            <div class="bs-title">{{ title }}</div>
            <div v-if="subtitle" class="bs-sub">{{ subtitle }}</div>
          </div>
          <button class="bs-close" type="button" title="关闭" aria-label="关闭" @click="close">×</button>
        </header>
        <div class="bs-body"><slot /></div>
      </section>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { addSheet, removeSheet, sheetZ } from './sheetStack'

const props = defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, default: '' },
  subtitle: { type: String, default: '' },
  /** 面板最大高度：默认 76dvh（留出顶栏与会话上下文） */
  maxHeight: { type: String, default: '76dvh' },
  /** 这层界面的 z-index 基线：栈里每深一层再加 10 */
  baseZ: { type: Number, default: 1100 }
})
const emit = defineEmits(['close'])
const close = () => emit('close')

// 登记/摘掉只看 open 这一个事实：返回键（由 sheetStack 关栈顶）、×、遮罩、下拉、页面自己置
// open=false 五条路都收敛到「open 翻假」，所以栈不需要知道是谁关的。
const token = ref(0)
watch(() => props.open, v => {
  if (v && !token.value) token.value = addSheet(close, props.baseZ)
  else if (!v && token.value) { removeSheet(token.value); token.value = 0 }
}, { immediate: true })
// sheet 常因为一次跳转就整块卸载（点通知 → router.push）：卸载也要摘栈，
// 否则哨兵留在 history 里，下一次返回会去关一个已经不存在的界面
onUnmounted(() => { if (token.value) { removeSheet(token.value); token.value = 0 } })

// ==================== 下拉关闭 ====================
// 只认**把手区**（抓手条 + 标题行）：body 里内容常常可滚，「滚到底继续下拉」在触屏上既是
// 滚动回弹也是关闭手势，两者抢起来一定有一边是错的。把手区是明确的、不抢。
// 关闭走 emit('close')，与 × / 遮罩 / 返回键同一条路——栈的哨兵结算因此只有一份。
// 不区分 pointerType：判据是「向下 90px 或快甩」，鼠标用户不会随手满足它；而触屏笔记本
// （Surface 那类）本来就两种输入都有，按设备类型禁掉反而把手势做没了。
const dragY = ref(0)
const dragging = ref(false)
const flying = ref(false)
let startY = 0, startAt = 0

function onDown (e) {
  if (flying.value) return
  if (e.target.closest('.bs-body') || e.target.closest('.bs-close')) return
  startY = e.clientY; startAt = Date.now(); dragY.value = 0; dragging.value = true
  e.currentTarget.setPointerCapture?.(e.pointerId)
}
function onMove (e) {
  if (!dragging.value) return
  // 只准向下拖：向上拖是「把 sheet 顶得更高」的错觉，我们没有这个能力，宁可不动
  const d = e.clientY - startY
  dragY.value = d > 6 ? d : 0
}
function onUp () {
  if (!dragging.value) return
  dragging.value = false
  const d = dragY.value
  const vel = d / Math.max(16, Date.now() - startAt)   // px/ms
  dragY.value = 0
  if (d > 90 || vel > 0.55) flyOut()
}
function flyOut () {
  flying.value = true
  // 先播 180ms 下滑再撤：立刻 emit 会让 v-if 直接摘掉 DOM，用户看到的闪一下像崩了
  setTimeout(() => { flying.value = false; emit('close') }, 180)
}

const sheetStyle = computed(() => ({
  maxHeight: props.maxHeight,
  transform: dragY.value ? `translateY(${dragY.value}px)` : '',
  transition: dragging.value ? 'none' : ''
}))
// 遮罩跟手淡出：不淡的话拖到一半 sheet 悬在半空、背景还是全黑，读起来像卡住了
const maskStyle = computed(() => ({
  zIndex: token.value ? sheetZ(token.value, props.baseZ) : props.baseZ,
  opacity: dragY.value ? String(Math.max(.25, 1 - dragY.value / 320)) : ''
}))
</script>

<style scoped>
.bs-mask {
  position: fixed; inset: 0; z-index: 1100;
  background: rgba(0, 0, 0, .42);
  display: flex; align-items: flex-end;
  /* 键盘弹起时遮罩仍然全屏，只是 sheet 被顶起；点空白处关闭是移动端惯例 */
  touch-action: manipulation;
}
.bs-sheet {
  width: 100%;
  background: var(--app-panel);
  border-radius: 16px 16px 0 0;
  box-shadow: 0 -8px 32px rgba(16, 24, 40, .18);
  display: flex; flex-direction: column;
  padding-bottom: calc(var(--sab, 0px) + var(--kb, 0px));
  animation: bs-up .22s cubic-bezier(.16, 1, .3, 1);
  overscroll-behavior: contain;
}
@keyframes bs-up { from { transform: translateY(100%); } to { transform: none; } }
@media (prefers-reduced-motion: reduce) { .bs-sheet { animation: none; } }
.bs-grab {
  width: 36px; height: 4px; border-radius: 2px; background: var(--app-border-strong);
  margin: 8px auto 0; flex: none;
}
.bs-head {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 12px 8px 16px; flex: none;
  border-bottom: 1px solid var(--app-border);
}
.bs-titles { flex: 1; min-width: 0; }
.bs-title { font-size: 15px; font-weight: 600; color: var(--app-text); }
.bs-sub { font-size: 12px; color: var(--app-text3); margin-top: 2px; }
.bs-close {
  flex: none; width: 34px; height: 34px; border: none; border-radius: 50%;
  background: var(--app-panel-2); color: var(--app-text2); font-size: 20px; line-height: 1;
  display: inline-flex; align-items: center; justify-content: center;
  touch-action: manipulation;
}
.bs-close:active { background: var(--app-accent-weak); color: var(--app-accent); }
/* 触屏热区：这颗 × 视觉仍是 34px（改大会把标题挤没），用透明伪元素把命中区补到 44×44——
   它左边是标题文本、不可点，所以双向扩不会挡到别的东西。 */
.bs-close { position: relative; }
.bs-close::after {
  content: ''; position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%);
  width: 44px; height: 44px;
}
.bs-body {
  flex: 1; min-height: 0; overflow-y: auto;
  -webkit-overflow-scrolling: touch;
  padding: 10px 12px 16px;
}
/* 平板横屏（iPad 1024 宽也走移动壳）下，sheet 里一行 1000px 宽的正文与表单同样读不动：
   内容列封顶。手机取满宽，这条对 ≤720px 零影响。 */
.bs-body > * { max-width: min(100%, 720px); margin-inline: auto; }
/* 把手区（抓手条 + 标题行）：明确的可拖区，不让浏览器把这里的竖向移动抢去当滚动或下拉刷新。
   body 不设 touch-action，内容照常滚。 */
.bs-grab, .bs-head { touch-action: none; }
.bs-sheet { will-change: transform; transition: transform .2s cubic-bezier(.16, 1, .3, 1); }
.bs-sheet.bs-dragging { transition: none; }
/* 松手过阈值：整张滑出、遮罩跟着淡，180ms 后才 emit('close')——先撤 DOM 会闪一下像崩了 */
.bs-mask.bs-flying { opacity: 0; transition: opacity .18s ease-in; }
.bs-mask.bs-flying .bs-sheet { transform: translateY(100%); transition: transform .18s ease-in; }
@media (prefers-reduced-motion: reduce) {
  .bs-sheet, .bs-mask.bs-flying, .bs-mask.bs-flying .bs-sheet { transition: none; }
}
</style>
