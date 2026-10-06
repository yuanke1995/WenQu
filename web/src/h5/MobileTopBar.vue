<template>
  <!-- 窄屏顶栏：菜单入口 / 会话标题 / 搜索 / 新建 / 帮助（H5 外壳下再补一个通知）。
       刻意不放主题切换、退出——窄屏放不下一排图标，这两项菜单底部 .side-foot / sheet foot 已全都有。 -->
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
    <!-- 通知（仅 H5 外壳）：触屏下侧栏整体退场，铃铛不能只留在侧栏 foot——
         与 /m/chat 顶栏同口径（角标与列表共用同一份未读数） -->
    <button v-if="notif" class="m-tb-btn" title="通知" @click="$emit('notif')">
      <bell-outlined />
      <span v-if="unread > 0" class="m-tb-badge">{{ unread > 99 ? '99+' : unread }}</span>
    </button>
  </header>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { MenuOutlined, SearchOutlined, PlusOutlined, QuestionCircleOutlined, BellOutlined } from '@ant-design/icons-vue'
import { sessionStore } from '../views/store'

// notif/unread：H5 外壳（AppLayout 的触屏形态）传入——顶栏多一个铃铛入口；
// 抽屉形态（桌面窄窗口）不传，顶栏与之前完全一致（通知在抽屉 foot）
defineProps({
  notif: { type: Boolean, default: false },
  unread: { type: Number, default: 0 }
})
defineEmits(['toggle-side', 'search', 'new-chat', 'notif'])

const route = useRoute()

// 标题：对话页取当前会话名，**其它页取路由标题**（个人设置 / 帮助中心…）。
// 此前一律按会话取名，非对话页窄屏顶栏会一直显示「新对话」，与页面内容对不上。
const title = computed(() => {
  if (route.path !== '/chat') return route.meta?.title || '问渠'
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
  flex: none; position: relative; width: 44px; height: 44px; border: none; background: transparent;
  color: var(--app-text2); font-size: 17px; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
  border-radius: 8px; touch-action: manipulation;
  transition: color .15s, background .15s;
}
/* 未读角标：与移动壳顶栏 .m-bar-badge 同形态（同一份未读数、同一种表达） */
.m-tb-badge {
  position: absolute; top: 3px; right: 3px; min-width: 16px; height: 16px; padding: 0 4px;
  border-radius: 8px; background: var(--app-danger); color: #fff;
  font-size: 10px; line-height: 16px; font-weight: 600; text-align: center;
}
.m-tb-btn:active { color: var(--app-accent); background: var(--app-accent-weak); }
/* 标题占满剩余宽度并单行省略：会话名可能很长，不截断会把右侧按钮挤出屏幕 */
.m-tb-title {
  flex: 1; min-width: 0; text-align: center;
  font-size: 14px; font-weight: 500; color: var(--app-text);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
</style>
