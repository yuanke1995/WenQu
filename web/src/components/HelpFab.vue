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
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { QuestionCircleOutlined } from '@ant-design/icons-vue'
import HelpCenter from './HelpCenter.vue'

const route = useRoute()
const open = ref(false)
</script>

<style scoped>
/* 30px 小尺寸 + 贴角 10px 是有意的：宽屏下输入卡片居中留白大、无遮挡；
   窄屏下输入卡片贴右缘时，该尺寸刚好压在卡片圆角的内边距上，不盖到发送键 */
.help-fab {
  position: fixed; right: 10px; bottom: 10px; z-index: 90;
  width: 30px; height: 30px; border-radius: 50%; padding: 0;
  border: 1px solid var(--app-border); background: var(--app-panel); color: var(--app-text3);
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 15px; cursor: pointer;
  box-shadow: var(--app-shadow-sm);
  transition: color .15s, border-color .15s, box-shadow .15s;
}
.help-fab:hover { color: var(--app-accent); border-color: var(--app-accent); box-shadow: var(--app-shadow); }

.help-drawer-title { display: flex; flex-direction: column; gap: 1px; padding-right: 12px; }
.help-drawer-name { font-size: 14px; font-weight: 500; }
.help-drawer-hint { font-size: 11px; font-weight: 400; color: var(--app-text3); }
</style>
