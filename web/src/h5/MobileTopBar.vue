<template>
  <!-- 窄屏顶栏：抽屉入口 / 会话标题 / 搜索 / 新建 / 帮助。
       刻意不放主题切换、通知、退出——窄屏放不下一排图标，这三项抽屉底部 .side-foot 已全都有。 -->
  <header class="m-topbar">
    <button class="m-tb-btn" title="菜单与会话" @click="$emit('toggle-side')">
      <menu-outlined />
    </button>
    <div class="m-tb-title" :title="title">{{ title }}</div>
    <button class="m-tb-btn" title="搜索会话" @click="$emit('search')">
      <search-outlined />
    </button>
    <button class="m-tb-btn" title="新建对话" @click="$emit('new-chat')">
      <plus-outlined />
    </button>
    <!-- 帮助入口：窄屏下 HelpFab（fixed 右下角）会压住对话页输入框，改为顶栏入口 -->
    <button class="m-tb-btn" title="帮助中心" @click="openHelp">
      <question-circle-outlined />
    </button>
  </header>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { MenuOutlined, SearchOutlined, PlusOutlined, QuestionCircleOutlined } from '@ant-design/icons-vue'
import { sessionStore } from '../views/store'

const route = useRoute()

// 标题取当前会话（route.query.sid 对应项）的标题，纯 computed 不发请求；
// 没有 sid（新对话）或会话未在已加载列表里（分页未拉到）时回落「新对话」
const title = computed(() => {
  const sid = route.query.sid
  if (!sid) return '新对话'
  const hit = (sessionStore.list || []).find(s => s.id === sid)
  return (hit && hit.title) || '新对话'
})

// 帮助抽屉由 HelpFab 持有（它是常驻组件，抽屉开合状态在它内部），
// 这里用全局事件而不是抽组件：只为传一个开关不值得拆组件，也不会引入父子耦合
const openHelp = () => window.dispatchEvent(new CustomEvent('app:open-help'))
</script>

<style scoped>
/* 高度 44px + 刘海安全区。position 不用 sticky：它作为 .main 内的 flex 首个子节点，
   靠 flex 布局固定在顶部即可，sticky 反而会与内部滚动容器的定位打架 */
.m-topbar {
  display: flex; align-items: center; gap: 2px; flex: none;
  height: calc(44px + var(--sat, 0px));
  padding: var(--sat, 0px) 6px 0;
  background: var(--app-panel);
  border-bottom: 1px solid var(--app-border);
}
/* 触摸热区 44×44（触屏设计规范的下限，指尖点得准） */
.m-tb-btn {
  flex: none; width: 44px; height: 44px; border: none; background: transparent;
  color: var(--app-text2); font-size: 17px; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
  border-radius: 8px; touch-action: manipulation;
  transition: color .15s, background .15s;
}
.m-tb-btn:active { color: var(--app-accent); background: var(--app-accent-weak); }
/* 标题占满剩余宽度并单行省略：会话名可能很长，不截断会把右侧按钮挤出屏幕 */
.m-tb-title {
  flex: 1; min-width: 0; text-align: center;
  font-size: 14px; font-weight: 500; color: var(--app-text);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
</style>
