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
      <!-- 会话搜索（防抖走后端 keyword 检索：标题/消息内容模糊匹配）；右端内嵌批量管理入口 -->
      <div v-if="!collapsed" class="sess-search-wrap">
        <search-outlined class="sess-search-ic" />
        <input v-model="searchKw" class="sess-search" placeholder="搜索会话…" @input="onSearchInput" />
        <button v-if="searchKw" class="sess-search-clear" title="清除搜索" @click="clearSearch"><close-outlined /></button>
        <span class="sess-search-div"></span>
        <a-tooltip :title="batchMode ? '退出批量管理' : '批量删除会话'">
          <button class="sess-manage" :class="{ on: batchMode }" @click="batchMode ? exitBatchMode() : enterBatchMode()">
            <check-square-outlined />
          </button>
        </a-tooltip>
      </div>
      <!-- 批量操作条：进入批量模式才出现，紧贴列表上方，作用于当前搜索结果 -->
      <div v-if="!collapsed && batchMode" class="sess-batch-bar">
        <span class="batch-count">已选 {{ batchSel.size }}</span>
        <span class="batch-actions">
          <button class="batch-btn" @click="toggleSelectAll">{{ allSelected ? '取消全选' : '全选' }}</button>
          <button class="batch-btn danger" :disabled="!batchSel.size" @click="confirmBatchDelete">删除</button>
          <button class="batch-btn" @click="exitBatchMode">完成</button>
        </span>
      </div>
      <div class="side-sessions">
        <a-spin v-if="sessionStore.loading" size="small" style="display:block;margin:16px auto" />
        <template v-else>
          <div v-for="s in visibleSessionList" :key="s.id"
               class="sess-item" :class="{ active: !batchMode && isActive('/chat') && route.query.sid === s.id, picked: batchMode && batchSel.has(s.id) }"
               :title="s.title" @click="batchMode && !collapsed ? toggleBatchSel(s.id) : openSession(s.id)">
            <span v-if="collapsed" class="sess-dot"></span>
            <template v-else>
              <!-- 批量模式：行首勾选块，点行即切换选中（不进会话、hover 操作隐藏） -->
              <span v-if="batchMode" class="batch-check" :class="{ on: batchSel.has(s.id) }">
                <check-outlined v-if="batchSel.has(s.id)" />
              </span>
              <pushpin-outlined v-if="s.isPinned === 1" class="sess-pin-flag" />
              <span class="sess-title" :class="{ fav: s.isFavorite === 1 }">
                <star-filled v-if="s.isFavorite === 1" class="sess-fav-flag" />{{ s.title || '新对话' }}
              </span>
            </template>
            <template v-if="!collapsed && !batchMode">
              <a-tooltip :title="s.isPinned === 1 ? '取消置顶' : '置顶'">
                <button class="sess-op" :class="{ on: s.isPinned === 1 }" @click.stop="togglePin(s)"><pushpin-outlined /></button>
              </a-tooltip>
              <a-dropdown trigger="['click']" placement="bottomRight">
                <button class="sess-op" @click.stop><more-outlined /></button>
                <template #overlay>
                  <a-menu @click="({ key }) => sessionMenu(s, key)">
                    <a-menu-item key="rename"><edit-outlined /> 重命名</a-menu-item>
                    <a-menu-item key="favorite"><star-filled v-if="s.isFavorite === 1" /><star-outlined v-else /> {{ s.isFavorite === 1 ? '取消收藏' : '收藏' }}</a-menu-item>
                    <a-menu-item key="export"><download-outlined /> 导出 Markdown</a-menu-item>
                    <a-menu-divider />
                    <a-menu-item key="delete" danger><delete-outlined /> 删除</a-menu-item>
                  </a-menu>
                </template>
              </a-dropdown>
            </template>
          </div>
          <div v-if="!visibleSessionList.length && !collapsed" class="sess-empty">
            {{ searchKw ? '没有匹配的会话' : '暂无会话' }}
          </div>
        </template>
      </div>

      <!-- 重命名会话 -->
      <a-modal v-model:open="renameState.open" title="重命名会话" ok-text="保存" cancel-text="取消" @ok="doRename">
        <a-input v-model:value="renameState.title" :maxlength="50" placeholder="会话标题（≤50 字）"
                 @press-enter="doRename" />
      </a-modal>

      <div class="side-foot">
        <span class="avatar">{{ (userName || '游')[0] }}</span>
        <span v-if="!collapsed" class="user-name">{{ userName || '未登录' }}</span>
        <a-tooltip :title="themeState === 'dark' ? '切换到亮色主题' : '切换到暗色主题'" placement="right">
          <button class="app-icon-btn" @click="toggleTheme">
            <bulb-filled v-if="themeState === 'dark'" /><bulb-outlined v-else />
          </button>
        </a-tooltip>
        <a-tooltip title="退出登录" placement="right">
          <button class="app-icon-btn" @click="doLogout"><logout-outlined /></button>
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
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { PlusOutlined, MessageOutlined, RobotOutlined, FolderOutlined, BarChartOutlined, SettingOutlined, ExperimentOutlined,
         MenuFoldOutlined, MenuUnfoldOutlined, DeleteOutlined, DownloadOutlined, TeamOutlined,
         LogoutOutlined, UserOutlined, DatabaseOutlined, SafetyOutlined, AppstoreOutlined, FileOutlined,
         SearchOutlined, CloseOutlined, PushpinOutlined, MoreOutlined, EditOutlined, StarFilled, StarOutlined,
         CheckOutlined, CheckSquareOutlined,
         BulbOutlined, BulbFilled } from '@ant-design/icons-vue'
import { deleteSessionApi, logoutApi, renameSessionApi, pinSession, favoriteSession, batchDeleteSessionsApi } from '../api'
import { themeState, toggleTheme } from '../utils/theme'
import { ensureAuth, isAdminSync, clearAuth } from '../utils/auth'
import { sessionStore, loadSessions, visibleSessions, chatStreams } from './store'
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

// ==================== 会话管理（重命名/置顶/收藏/搜索：后端接口既有，此处接线 UI） ====================
const togglePin = async s => {
  try {
    await pinSession(s.id, s.isPinned !== 1)
    await loadSessions()
  } catch (e) { message.error(e.message || '操作失败') }
}
const toggleFavorite = async s => {
  try {
    await favoriteSession(s.id, s.isFavorite !== 1)
    await loadSessions()
  } catch (e) { message.error(e.message || '操作失败') }
}
const renameState = reactive({ open: false, id: '', title: '' })
const renameSession = s => {
  renameState.open = true
  renameState.id = s.id
  renameState.title = s.title || ''
}
const doRename = async () => {
  const t = renameState.title.trim()
  if (!t) { message.warning('标题不能为空'); return }
  try {
    await renameSessionApi(renameState.id, t)
    message.success('已重命名')
    renameState.open = false
    await loadSessions()
  } catch (e) { message.error(e.message || '重命名失败') }
}
const sessionMenu = (s, key) => {
  if (key === 'rename') renameSession(s)
  else if (key === 'favorite') toggleFavorite(s)
  else if (key === 'export') exportSessionMd(s)
  else if (key === 'delete') confirmDelete(s)
}
const confirmDelete = s => {
  Modal.confirm({
    title: '删除该会话？',
    content: '会话与消息记录会被删除，不可恢复。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: () => delSession(s.id)
  })
}
// 会话搜索（300ms 防抖走后端 keyword 检索；空串恢复全量）
const searchKw = ref('')
let searchTimer = null
const onSearchInput = () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => loadSessions(searchKw.value.trim()), 300)
}
const clearSearch = () => {
  searchKw.value = ''
  clearTimeout(searchTimer)
  loadSessions('')
}
onUnmounted(() => clearTimeout(searchTimer))

const delSession = async sid => {
  try {
    // 删除流式中的会话：先中止其后台流（连接断开后后端在下次 SSE 发送失败时取消本轮，
    // 不再往已删除的会话落库回答），并移除记录防止切回时把死消息接回视图
    const st = chatStreams.get(sid)
    if (st) {
      chatStreams.delete(sid)
      st.abort.abort()
    }
    await deleteSessionApi(sid)
    message.success('会话已删除')
    await loadSessions()
    if (route.query.sid === sid) {
      router.push('/chat').catch(() => {})
      sessionStore.autoPickTick++
    }
  } catch (e) { message.error(e.message || '删除失败') }
}

// ==================== 批量删除（后端 /sessions/batch-delete 既有：逐条校验归属、软删、返回成功数） ====================
const batchMode = ref(false)
const batchSel = ref(new Set())
const allSelected = computed(() =>
  visibleSessionList.value.length > 0 && visibleSessionList.value.every(s => batchSel.value.has(s.id)))
const enterBatchMode = () => { batchMode.value = true; batchSel.value = new Set() }
const exitBatchMode = () => { batchMode.value = false; batchSel.value = new Set() }
const toggleBatchSel = id => {
  const next = new Set(batchSel.value)
  if (next.has(id)) next.delete(id); else next.add(id)
  batchSel.value = next
}
const toggleSelectAll = () => {
  batchSel.value = allSelected.value ? new Set() : new Set(visibleSessionList.value.map(s => s.id))
}
const confirmBatchDelete = () => {
  const ids = [...batchSel.value]
  if (!ids.length) return
  Modal.confirm({
    title: `删除选中的 ${ids.length} 个会话？`,
    content: '会话与消息记录会被删除，不可恢复。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: () => doBatchDelete(ids)
  })
}
const doBatchDelete = async ids => {
  try {
    // 与单个删除一致：先中止选中会话的后台流，防止往已删除的会话落库回答
    ids.forEach(sid => {
      const st = chatStreams.get(sid)
      if (st) { chatStreams.delete(sid); st.abort.abort() }
    })
    const res = await batchDeleteSessionsApi(ids)
    message.success(`已删除 ${res?.deleted ?? ids.length} 个会话`)
    exitBatchMode()
    await loadSessions(searchKw.value.trim())
    // 当前打开的会话在被删列表里 → 回对话页重新挑会话
    if (route.query.sid && ids.includes(route.query.sid)) {
      router.push('/chat').catch(() => {})
      sessionStore.autoPickTick++
    }
  } catch (e) { message.error(e.message || '批量删除失败') }
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
.pref-hint { font-size: 12px; color: var(--app-text3, var(--app-text3)); margin-top: 6px; }
.pwd-err { color: var(--app-danger); font-size: 12px; margin: 0 0 8px; }
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
/* 搜索框右端：分隔线 + 批量管理入口（与搜索同属「管理会话」动线，再点一次退出） */
.sess-search-div { width: 1px; height: 12px; background: var(--app-border); margin: 0 2px; flex: none; }
.sess-manage { border: none; background: transparent; color: var(--app-text3); cursor: pointer; font-size: 12px; padding: 2px 5px 2px 3px; margin-right: 3px; display: inline-flex; align-items: center; }
.sess-manage:hover, .sess-manage.on { color: var(--app-accent); }
/* 批量操作条：独立一行贴列表上方，主题色弱底提示「处于批量模式」 */
.sess-batch-bar {
  display: flex; align-items: center; justify-content: space-between;
  margin: 0 2px 6px; padding: 5px 8px; border-radius: 7px;
  background: var(--app-accent-weak); border: 1px solid var(--app-accent-weak);
  font-size: 11px; color: var(--app-text2);
}
.batch-count { white-space: nowrap; }
.batch-actions { display: inline-flex; align-items: center; gap: 7px; }
.batch-btn { border: none; background: transparent; color: var(--app-accent); cursor: pointer; font-size: 11px; padding: 0; }
.batch-btn.danger { color: var(--app-danger); }
.batch-btn:disabled { color: var(--app-text3); cursor: not-allowed; }
/* 批量模式行首勾选块（选中填主题色 + 白勾） */
.batch-check {
  width: 14px; height: 14px; border-radius: 4px; flex: none; margin-right: 6px;
  border: 1px solid var(--app-border); background: var(--app-panel);
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 9px; color: #fff;
}
.batch-check.on { background: var(--app-accent); border-color: var(--app-accent); }
.side-sessions { flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 1px; }
.sess-item {
  display: flex; align-items: center; padding: 6px 9px; border-radius: 8px;
  font-size: 12px; color: var(--app-text2); cursor: pointer; min-width: 0;
  transition: background .12s;
}
.sess-item:hover { background: var(--app-panel-2); }
/* 当前行：弱底 + 左缘主题色窄条（比纯底色多一层方位感）；批量选中行共用弱底（无窄条） */
.sess-item.active { background: var(--app-accent-weak); color: var(--app-text); box-shadow: inset 2px 0 0 var(--app-accent); }
.sess-item.picked { background: var(--app-accent-weak); color: var(--app-text); }
.sess-title { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; flex: 1; min-width: 0; }
.sess-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--app-text3); margin: 0 auto; }
.sess-item.active .sess-dot { background: var(--app-accent); }
.sess-del { color: var(--app-text3); opacity: 0; flex: none; margin-left: 4px; font-size: 12px; }
.sess-item:hover .sess-del { opacity: 1; }
.sess-del:hover { color: var(--app-danger); }
.sess-export { color: var(--app-text3); opacity: 0; flex: none; margin-left: 4px; font-size: 12px; }
.sess-item:hover .sess-export { opacity: 1; }
.sess-export:hover { color: var(--app-accent); }

/* 会话搜索框（列表顶部，防抖走后端检索） */
.sess-search-wrap {
  position: relative; display: flex; align-items: center; margin: 0 2px 6px;
  border: 1px solid var(--app-border); border-radius: 7px; background: var(--app-bg, #fff);
}
.sess-search-wrap:focus-within { border-color: var(--app-accent); }
.sess-search-ic { color: var(--app-text3); font-size: 11px; margin-left: 7px; flex: none; }
.sess-search {
  flex: 1; min-width: 0; border: none; outline: none; background: transparent;
  font-size: 12px; padding: 5px 6px 5px 5px; color: var(--app-text);
}
.sess-search::placeholder { color: var(--app-text3); }
.sess-search-clear { border: none; background: transparent; color: var(--app-text3); cursor: pointer; padding: 2px 6px; font-size: 10px; }
.sess-search-clear:hover { color: var(--app-text); }

/* 会话项操作区（hover 出现）：置顶快捷按钮 + 更多菜单（重命名/收藏/导出/删除） */
.sess-op {
  border: none; background: transparent; color: var(--app-text3); opacity: 0;
  flex: none; margin-left: 4px; font-size: 12px; cursor: pointer; padding: 0 1px;
  display: inline-flex; align-items: center;
}
.sess-item:hover .sess-op { opacity: 1; }
.sess-op:hover { color: var(--app-accent); }
.sess-op.on { opacity: 1; color: var(--app-accent); }
.sess-pin-flag { color: var(--app-accent); font-size: 10px; flex: none; margin-right: 3px; }
.sess-fav-flag { color: var(--app-warn); font-size: 10px; margin-right: 3px; }
.sess-title.fav { color: var(--app-text); }
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

/* ==================== 响应式：窄屏适配 ====================
   此前全站零媒体查询——侧栏固定 200px + 消息区固定内边距，窗口收窄即挤坏。
   ≤768：侧栏强制收为图标条（与折叠态同款视觉，JS 的 collapsed 状态不动，纯 CSS 覆盖）；
   ≤1024：会话操作图标常显（触屏无 hover）。 */
@media (max-width: 1024px) {
  .sess-op { opacity: 1; }
}
@media (max-width: 768px) {
  .side { width: 56px; padding: 10px 4px; }
  .side .logo-mark { display: none; }
  .side .side-logo { justify-content: center; padding: 2px 0 12px; }
  .side .fold { margin-left: 0; }
  .side .nav-item { justify-content: center; padding-left: 0; padding-right: 0; }
  .side .nav-item > span { display: none; }
  .side .side-label, .side .sess-search-wrap, .side .sess-title, .side .sess-op,
  .side .sess-del, .side .sess-export, .side .sess-pin-flag, .side .sess-empty, .side .user-name { display: none; }
  .side .side-sessions { align-items: center; }
  .side .sess-item { justify-content: center; padding: 6px 0; width: 100%; }
  .side .sess-dot { display: block; }
  .side .side-foot { justify-content: center; flex-wrap: wrap; gap: 6px; padding: 8px 0 2px; }
  .side .side-foot .app-icon-btn { margin-left: 0 !important; }
}
</style>
