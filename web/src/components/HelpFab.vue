<template>
  <template v-if="route.path !== '/help'">
    <!-- 全局帮助入口：任意工作台页面右下角悬浮「?」，就地唤起手册抽屉（不打断当前页面） -->
    <a-tooltip title="帮助中心 · 问渠使用手册" placement="left">
      <button class="help-fab" aria-label="帮助中心" @click="open = true">
        <question-circle-outlined />
      </button>
    </a-tooltip>
    <a-drawer v-model:open="open" placement="right" :width="'min(1080px, 92vw)'"
              :body-style="{ padding: '14px 20px 20px' }">
      <template #title>
        <div class="help-drawer-title">
          <span class="help-drawer-name">帮助中心</span>
          <span class="help-drawer-hint">官方使用手册（随版本自动同步）；也可以在对话里直接提问，答案会从本手册检索并带引用</span>
        </div>
      </template>
      <HelpCenter />
    </a-drawer>
  </template>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { QuestionCircleOutlined } from '@ant-design/icons-vue'
import HelpCenter from './HelpCenter.vue'

const route = useRoute()
const open = ref(false)

// 窄屏帮助入口在移动顶栏（src/h5/MobileTopBar.vue）——那里 FAB 会压住对话页输入框。
// 用全局事件而非 props：HelpFab 是常驻组件、抽屉开合状态在它内部，
// 为传一个开关而把状态提到 AppLayout 反而多一层耦合。
const onOpenHelp = () => { open.value = true }
onMounted(() => window.addEventListener('app:open-help', onOpenHelp))
onUnmounted(() => window.removeEventListener('app:open-help', onOpenHelp))
</script>

<style scoped>
/* 30px 小尺寸是有意的：宽屏下输入卡片居中留白大、无遮挡；
   窄屏下输入卡片贴右缘时，该尺寸刚好压在卡片圆角的内边距上，不盖到发送键 */
.help-fab {
  position: fixed; right: 2px; bottom: 2px; z-index: 90;
  width: 30px; height: 30px; border-radius: 50%; padding: 0;
  border: 1px solid var(--app-border); color: var(--app-text3);
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 15px; cursor: pointer;
  /* 半隐藏＝只降视觉权重、绝不移位。贴角的元素一旦 hover 时位移，指针就会落在
     它让开的位置上 → hover 断开 → 弹回 → 又 hover，角上反复横跳。
     故静止态用「透明底 + 压暗」藏起来，hover 态只点亮，几何位置全程不动。 */
  opacity: .38; background: transparent; box-shadow: none;
  transition: opacity .18s, color .15s, border-color .15s, background-color .15s, box-shadow .15s;
}
.help-fab:hover, .help-fab:focus-visible {
  opacity: 1; color: var(--app-accent); border-color: var(--app-accent);
  background: var(--app-panel); box-shadow: var(--app-shadow);
}

.help-drawer-title { display: flex; flex-direction: column; gap: 1px; padding-right: 12px; }
.help-drawer-name { font-size: 14px; font-weight: 500; }
.help-drawer-hint { font-size: 11px; font-weight: 400; color: var(--app-text3); }

/* 窄屏隐藏：fixed 右下角的 FAB 会压住对话页输入框的发送键一侧。
   帮助入口改到移动顶栏（src/h5/MobileTopBar.vue 通过 app:open-help 事件打开同一个抽屉）。
   判据用宽度而非 hover：这里问的是「别处有没有该入口」，而顶栏正是随宽度出现的。 */
@media (max-width: 768px) {
  .help-fab { display: none; }
}
/* 触屏（无 hover）不压暗：它没有能点亮的那一下。
   判据与 h5/mobile.js 的 isCoarse 同源；769~1024 的触屏平板仍会渲染本 FAB。 */
@media (hover: none) {
  .help-fab { opacity: 1; background: var(--app-panel); box-shadow: var(--app-shadow-sm); }
}
</style>
