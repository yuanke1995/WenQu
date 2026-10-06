<template>
  <!-- 窄屏聊天头部：替代 PC 的 .chat-head。
       PC 那块在窄屏仍渲染会与 MobileTopBar 形成「双顶栏」（图里很明显）：
       顶栏已提供标题/搜索/新建，这里只保留 PC 版没有的三个动作 —— 会话内查找、
       分享、状态面板，且都用图标（PC 版的状态是文字按钮，窄屏放不下）。 -->
  <div class="m-chat-head">
    <button class="m-ch-btn" title="在本会话中查找" @click="$emit('search')">
      <search-outlined />
    </button>
    <button class="m-ch-btn" :class="{ 'share-on': shareActive }" @click="$emit('share')"
            :title="shareActive ? '这段对话正在对外分享（只读链接生效中）' : '分享这段对话（只读链接）'">
      <share-alt-outlined />
    </button>
    <!-- 状态面板：窄屏是覆盖式 sheet，点开即用；文案用图标，语义靠 title 提示 -->
    <button class="m-ch-btn" title="运行状态与引用来源" @click="$emit('panel')">
      <info-circle-outlined />
    </button>
  </div>
</template>

<script setup>
import { SearchOutlined, ShareAltOutlined, InfoCircleOutlined } from '@ant-design/icons-vue'

// 纯展示 + 事件转发，不持有状态（对话/面板状态都在 ChatPage），
// 避免窄屏与宽屏两套状态打架；shareActive 也由 ChatPage 传入（与侧栏分享标记同源的会话状态）
defineProps({ shareActive: { type: Boolean, default: false } })
defineEmits(['search', 'share', 'panel'])
</script>

<style scoped>
/* 高度 36px 的一行图标：贴在移动顶栏下方，让顶栏独占标题、这一行只放动作。
   不用绝对定位 —— 把它放在 .chat-col 的正常流里，消息区自动让位，
   避免遮挡与「谁是底」的问题。 */
.m-chat-head {
  display: flex; align-items: center; justify-content: flex-end; gap: 2px;
  flex: none; height: 36px; padding: 0 8px;
  background: var(--app-panel); border-bottom: 1px solid var(--app-border);
}
.m-ch-btn {
  width: 34px; height: 34px; border: none; background: transparent; cursor: pointer;
  color: var(--app-text3); font-size: 15px; border-radius: 8px;
  display: inline-flex; align-items: center; justify-content: center;
  touch-action: manipulation;   /* 禁掉双击缩放，保证首次点击生效 */
}
.m-ch-btn:active { color: var(--app-accent); background: var(--app-accent-weak); }
/* 分享激活态：琥珀（--app-warn）= 侧栏会话行分享标记同色；按下也不闪回强调蓝（激活是状态非手势） */
.m-ch-btn.share-on, .m-ch-btn.share-on:active { color: var(--app-warn); }
</style>
