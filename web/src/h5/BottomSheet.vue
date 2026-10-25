<template>
  <!-- 通用底部 sheet（移动壳的地基交互件）：遮罩点击关闭、圆角在上、底部让开 Home Indicator。
       键盘弹起时 --kb 收缩 sheet 的可用高度（输入类 sheet 的输入框不会被键盘盖住）。 -->
  <Teleport to="body">
    <div v-if="open" class="bs-mask" :style="maskStyle" @click.self="close">
      <section class="bs-sheet" :style="{ maxHeight }" role="dialog" :aria-label="title">
        <div class="bs-grab" aria-hidden="true"></div>
        <header class="bs-head">
          <div class="bs-titles">
            <div class="bs-title">{{ title }}</div>
            <div v-if="subtitle" class="bs-sub">{{ subtitle }}</div>
          </div>
          <button class="bs-close" type="button" title="关闭" @click="close">×</button>
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

// 登记/摘掉只看 open 这一个事实：返回键（由 sheetStack 关栈顶）、×、遮罩、页面自己置
// open=false 四条路都收敛到「open 翻假」，所以栈不需要知道是谁关的。
const token = ref(0)
watch(() => props.open, v => {
  if (v && !token.value) token.value = addSheet(close, props.baseZ)
  else if (!v && token.value) { removeSheet(token.value); token.value = 0 }
}, { immediate: true })
// sheet 常因为一次跳转就整块卸载（点通知 → router.push）：卸载也要摘栈，
// 否则哨兵留在 history 里，下一次返回会去关一个已经不存在的界面
onUnmounted(() => { if (token.value) { removeSheet(token.value); token.value = 0 } })

const maskStyle = computed(() => ({ zIndex: token.value ? sheetZ(token.value, props.baseZ) : props.baseZ }))
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
.bs-body {
  flex: 1; min-height: 0; overflow-y: auto;
  -webkit-overflow-scrolling: touch;
  padding: 10px 12px 16px;
}
</style>
