<template>
  <div class="app-root">
    <!-- 左侧边栏：logo / 导航 / 最近会话 / 底部用户区（可折叠为图标条） -->
    <aside class="side" :class="{ collapsed }">
      <div class="side-logo">
        <span class="logo-mark">渠</span>
        <span v-if="!collapsed" class="logo-name">问渠</span>
        <button class="app-icon-btn fold" :title="collapsed ? '展开侧边栏' : '折叠侧边栏'" @click="toggleFold">
          <menu-unfold-outlined v-if="collapsed" />
          <menu-fold-outlined v-else />
        </button>
      </div>

      <nav class="side-nav">
        <button class="nav-item" :class="{ active: isActive('/chat') && !route.query.sid }" @click="newChat" title="新建对话">
          <plus-outlined />
          <span v-if="!collapsed">新建对话</span>
        </button>
        <!-- 导航由 /auth/me 下发的菜单树渲染（RBAC：按角色绑定下发，权限管理页维护） -->
        <button v-for="m in navMenus" :key="m.id" class="nav-item"
                :class="{ active: isActive(m.path) }" @click="router.push(m.path)" :title="m.name">
          <component :is="iconOf(m.icon)" />
          <span v-if="!collapsed">{{ m.name }}</span>
        </button>
      </nav>

      <div v-if="!collapsed" class="side-label">最近</div>
      <div class="side-sessions">
        <a-spin v-if="sessionStore.loading" size="small" style="display:block;margin:16px auto" />
        <template v-else>
          <div v-for="s in visibleSessionList" :key="s.id"
               class="sess-item" :class="{ active: isActive('/chat') && route.query.sid === s.id }"
               :title="s.title" @click="openSession(s.id)">
            <span v-if="collapsed" class="sess-dot"></span>
            <span v-else class="sess-title">{{ s.title || '新对话' }}</span>
            <template v-if="!collapsed">
              <a-tooltip title="导出 Markdown">
                <span class="sess-export" @click.stop="exportSessionMd(s)"><download-outlined /></span>
              </a-tooltip>
              <a-popconfirm title="删除该会话？" ok-text="删除" cancel-text="取消" @confirm.stop="delSession(s.id)">
                <span class="sess-del" @click.stop><delete-outlined /></span>
              </a-popconfirm>
            </template>
          </div>
          <div v-if="!visibleSessionList.length && !collapsed" class="sess-empty">暂无会话</div>
        </template>
      </div>

      <div class="side-foot">
        <span class="avatar">{{ (userName || '游')[0] }}</span>
        <span v-if="!collapsed" class="user-name">{{ userName || '未登录' }}</span>
        <a-tooltip title="退出登录" placement="right">
          <button class="app-icon-btn" style="margin-left:auto" @click="doLogout"><logout-outlined /></button>
        </a-tooltip>
        <a-tooltip title="个人设置" placement="right">
          <button class="app-icon-btn" @click="goProfile" title="个人设置"><user-outlined /></button>
        </a-tooltip>
      </div>
    </aside>

    <!-- 主内容区 -->
    <div class="main"><router-view /></div>

  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { PlusOutlined, MessageOutlined, RobotOutlined, FolderOutlined, BarChartOutlined, SettingOutlined, ExperimentOutlined,
         MenuFoldOutlined, MenuUnfoldOutlined, DeleteOutlined, DownloadOutlined, TeamOutlined,
         LogoutOutlined, UserOutlined, DatabaseOutlined, SafetyOutlined, AppstoreOutlined, FileOutlined } from '@ant-design/icons-vue'
import { deleteSessionApi, logoutApi } from '../api'
import { ensureAuth, isAdminSync, clearAuth } from '../utils/auth'
import { sessionStore, loadSessions, visibleSessions } from './store'
import { exportSessionMarkdown } from './exportMd'
import './app.css'

const route = useRoute()
const router = useRouter()
const isAdmin = ref(isAdminSync())
const userName = ref('')

// 侧边栏菜单：/auth/me 下发的菜单树（顶级渲染为导航项；子级预留，当前侧边栏一层平铺）
const ICONS = {
  MessageOutlined, RobotOutlined, DatabaseOutlined, TeamOutlined, BarChartOutlined,
  ExperimentOutlined, SafetyOutlined, SettingOutlined, AppstoreOutlined, PlusOutlined,
  FolderOutlined, UserOutlined, FileOutlined
}
const iconOf = name => ICONS[name] || FileOutlined
// ensureAuth 填充的是模块级缓存（非响应式），故挂载后显式赋值
const navMenus = ref([])

// 侧边栏折叠（持久化）
const collapsed = ref(localStorage.getItem('app_sidebar') === '1')
const toggleFold = () => {
  collapsed.value = !collapsed.value
  localStorage.setItem('app_sidebar', collapsed.value ? '1' : '0')
}

const visibleSessionList = computed(visibleSessions)
const isActive = p => route.path === p
const newChat = () => {
  sessionStore.newChatTick++
  router.push('/chat').catch(() => {})
}
const openSession = sid => router.push({ path: '/chat', query: { sid } })

// 整会话导出 Markdown（无需先打开会话）
const exportSessionMd = s => {
  if (s && s.id) exportSessionMarkdown(s.id, s.title || 'AI对话')
}

const delSession = async sid => {
  try {
    await deleteSessionApi(sid)
    message.success('会话已删除')
    await loadSessions()
    if (route.query.sid === sid) {
      router.push('/chat').catch(() => {})
      sessionStore.autoPickTick++
    }
  } catch (e) { message.error(e.message || '删除失败') }
}

// 个人设置：独立页（默认模型三类 + 修改密码）
const goProfile = () => router.push('/profile')

// 退出登录：令牌无状态，清本地令牌并回登录页
const doLogout = async () => {
  try { await logoutApi() } catch (e) { /* 忽略：服务端不维护会话 */ }
  clearAuth()
  message.success('已退出登录')
  router.replace('/login')
}

onMounted(async () => {
  const info = await ensureAuth(true)
  isAdmin.value = Boolean(info && info.admin)
  userName.value = (info && (info.username || info.user)) || ''
  navMenus.value = ((info && info.menus) || []).filter(m => m && m.path)
  loadSessions()
})
</script>

<style scoped>
.pref-section { margin-bottom: 4px; }
.pref-label { font-weight: 600; margin-bottom: 8px; }
.pref-row { display: flex; align-items: center; gap: 8px; }
.pref-hint { font-size: 12px; color: var(--app-text3, #999); margin-top: 6px; }
.pwd-err { color: #e64340; font-size: 12px; margin: 0 0 8px; }
.side {
  width: 200px; flex: none; display: flex; flex-direction: column;
  background: var(--app-panel); border-right: 1px solid var(--app-border);
  padding: 10px 8px; transition: width .18s ease; overflow: hidden;
}
.side.collapsed { width: 56px; }
.side-logo { display: flex; align-items: center; gap: 8px; padding: 2px 6px 12px; }
/* 折叠态：logo 与收起按钮总宽超出 56px 会被 overflow:hidden 裁掉按钮 → 隐藏 logo、按钮居中 */
.side.collapsed .side-logo { justify-content: center; padding: 2px 0 12px; }
.side.collapsed .logo-mark { display: none; }
/* 折叠态所有图标统一对齐到侧边栏中轴（实测导航图标左偏 4px、头像左偏 2.5px） */
.side.collapsed .nav-item { justify-content: center; padding-left: 0; padding-right: 0; }
.side.collapsed .side-foot { justify-content: center; padding-left: 0; padding-right: 0; }
.side.collapsed .side-foot .app-icon-btn { margin-left: 0 !important; }
/* 非管理员折叠态：头像+验证按钮放不下（22+8+26 > 40），藏头像只留验证按钮并居中 */
.side.collapsed .side-foot .avatar:not(:only-child) { display: none; }
.logo-mark {
  width: 24px; height: 24px; border-radius: 6px; background: var(--app-text);
  color: #fff; font-size: 12px; display: inline-flex; align-items: center; justify-content: center; flex: none;
}
.logo-name { font-weight: 500; font-size: 13px; white-space: nowrap; }
.fold { margin-left: auto; }
.side.collapsed .fold { margin-left: 0; }

.side-nav { display: flex; flex-direction: column; gap: 2px; }
.nav-item {
  display: flex; align-items: center; gap: 9px; border: none; background: transparent;
  padding: 7px 9px; border-radius: 8px; font-size: 13px; color: var(--app-text2);
  cursor: pointer; text-align: left; white-space: nowrap; transition: background .15s, color .15s;
}
.nav-item:hover { background: var(--app-accent-weak); color: var(--app-text); }
.nav-item.active { background: var(--app-accent-weak); color: var(--app-text); font-weight: 500; }

.side-label { margin: 14px 8px 4px; font-size: 11px; color: var(--app-text3); }
.side-sessions { flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 1px; }
.sess-item {
  display: flex; align-items: center; padding: 6px 9px; border-radius: 8px;
  font-size: 12px; color: var(--app-text2); cursor: pointer; min-width: 0;
}
.sess-item:hover { background: #f2f4f7; }
.sess-item.active { background: var(--app-accent-weak); color: var(--app-text); }
.sess-title { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; flex: 1; min-width: 0; }
.sess-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--app-text3); margin: 0 auto; }
.sess-item.active .sess-dot { background: var(--app-accent); }
.sess-del { color: var(--app-text3); opacity: 0; flex: none; margin-left: 4px; font-size: 12px; }
.sess-item:hover .sess-del { opacity: 1; }
.sess-del:hover { color: var(--app-danger); }
.sess-export { color: var(--app-text3); opacity: 0; flex: none; margin-left: 4px; font-size: 12px; }
.sess-item:hover .sess-export { opacity: 1; }
.sess-export:hover { color: var(--app-accent); }
.sess-empty { font-size: 12px; color: var(--app-text3); text-align: center; padding: 16px 0; }

.side-foot {
  display: flex; align-items: center; gap: 8px; padding: 8px 6px 2px;
  border-top: 1px solid var(--app-border);
}
.avatar {
  width: 22px; height: 22px; border-radius: 50%; flex: none;
  background: var(--app-accent-weak); color: var(--app-accent);
  font-size: 11px; display: inline-flex; align-items: center; justify-content: center;
}
.user-name { font-size: 12px; color: var(--app-text2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.pwd-err { margin: 4px 0 0; font-size: 12px; color: var(--app-danger); }

.main { flex: 1; min-width: 0; height: 100%; }
</style>
