<template>
  <div class="app-root">
    <!-- 左侧边栏：logo / 导航 / 最近会话 / 底部用户区（可折叠为图标条） -->
    <aside class="side" :class="{ collapsed }">
      <div class="side-logo">
        <BrandMark :size="24" />
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
        <!-- 导航由 /auth/me 下发的菜单树渲染（RBAC：按角色绑定下发，权限管理页维护）；
             待配置 tag：/chat=聊天模型或默认模型未就绪、/knowledge=向量模型未就绪（tooltip 列缺失项） -->
        <button v-for="m in navMenus" :key="m.id" class="nav-item"
                :class="{ active: isActive(m.path) }" @click="router.push(m.path)" :title="menuTitle(m)">
          <component :is="iconOf(m.icon)" />
          <span v-if="!collapsed">{{ m.name }}</span>
          <span v-if="!collapsed && menuPending(m)" class="app-pill nav-pending">待配置</span>
          <i v-if="menuPending(m)" class="nav-dot"></i>
        </button>

        <!-- 配置引导入口（导航组末尾——辅助层功能不占 logo 下的黄金位，必配的醒目性
             由菜单「待配置」tag 与对话页欢迎卡承担）：
             必配未完成=琥珀「配置引导·还差 N 项」+ 折叠圆点（待办感）；
             必配完成但进阶项有缺=弱化链接「进阶配置 · N 项可选」（感知但不催办）；
             全部完成=消失，对已配好的存量用户零打扰 -->
        <button v-if="guideVisible" class="nav-item guide-entry" :class="{ adv: guideAdvOnly }"
                @click="guideOpen = true" :title="guideEntryTitle">
          <compass-outlined />
          <span v-if="!collapsed" class="guide-entry-text">{{ guideAdvOnly ? '进阶配置' : '配置引导' }}</span>
          <span v-if="!collapsed && !guideAdvOnly" class="guide-count">还差 {{ pendingCount }} 项</span>
          <span v-else-if="!collapsed" class="guide-count muted">{{ advPendingCount }} 项可选</span>
          <i v-if="pendingCount > 0" class="nav-dot"></i>
        </button>
      </nav>

      <!-- 会话搜索（防抖走后端 keyword 检索：标题/消息内容模糊匹配）；右端内嵌批量管理入口 -->
      <div v-if="!collapsed" class="sess-search-wrap">
        <search-outlined class="sess-search-ic" />
        <input v-model="searchKw" class="sess-search" placeholder="搜索会话…" @input="onSearchInput" />
        <button v-if="searchKw" class="sess-search-clear" title="清除搜索" @click="clearSearch"><close-outlined /></button>
      </div>
      <!-- 批量操作条：进入批量模式才出现，紧贴列表上方，作用于当前搜索结果 -->
      <div v-if="!collapsed && batchMode" class="sess-batch-bar">
        <span class="batch-count">已选 {{ batchSel.size }}</span>
        <span class="batch-actions">
          <button class="batch-btn" @click="toggleSelectAll">{{ allSelected ? '取消全选' : (sessionStore.hasMore ? '全选(已加载)' : '全选') }}</button>
          <button class="batch-btn danger" :disabled="!batchSel.size" @click="confirmBatchDelete">删除</button>
          <button class="batch-btn" @click="exitBatchMode">取消</button>
        </span>
      </div>
      <!-- 会话列表为游标分页：默认 20 条，点底部「查看更多」每次再渲染 20 条；
           组头数字是后端全量口径，不随加载进度漂移 -->
      <div class="side-sessions">
        <a-spin v-if="sessionStore.loading" size="small" style="display:block;margin:16px auto" />
        <template v-else>
          <!-- 按时间分组展示（后端已按 置顶→更新时间 排序，这里只分桶不改序）；折叠图标条下不显示组头。
               组头可点击折叠/展开（搜索时强制全展开——搜到却看不见是死胡同） -->
          <template v-for="g in groupedSessions" :key="g.label">
            <button v-if="!collapsed" type="button" class="side-label sess-group-label" @click="toggleGroup(g.label)">
              {{ g.label }}
              <span class="group-count">{{ groupTotal(g.label, g.items.length) }}</span>
              <right-outlined class="group-caret" :class="{ open: !groupHidden(g.label) }" />
            </button>
            <template v-if="collapsed || !groupHidden(g.label)">
            <div v-for="s in g.items" :key="s.id"
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
                    <a-menu-item key="batch"><check-square-outlined /> 批量管理</a-menu-item>
                    <a-menu-divider />
                    <a-menu-item key="delete" danger><delete-outlined /> 删除</a-menu-item>
                  </a-menu>
                </template>
              </a-dropdown>
            </template>
          </div>
            </template>
          </template>
          <!-- 增量加载入口（居左，内边距与会话行对齐）：查看更多 (N)=剩余可展示条数；
               点过查看更多后出现「收起」，一键还原首屏 20 条 -->
          <div v-if="(sessionStore.hasMore || sessionStore.expanded) && !collapsed" class="sess-more">
            <a-spin v-if="sessionStore.loadingMore" size="small" />
            <template v-else>
              <button v-if="sessionStore.hasMore" type="button" class="sess-more-btn" @click="loadMoreSessions">
                查看更多<template v-if="sessRemaining > 0"> ({{ sessRemaining }})</template>
              </button>
              <button v-if="sessionStore.expanded" type="button" class="sess-more-btn collapse" @click="collapseSessions">收起</button>
            </template>
          </div>
          <div v-if="!visibleSessionList.length && !collapsed" class="sess-empty">
            {{ searchKw ? '没有匹配的会话' : '暂无会话' }}
            <button v-if="sessionStore.hasMore" type="button" class="sess-empty-more" @click="loadMoreSessions">
              查看更多
            </button>
          </div>
        </template>
      </div>

      <!-- 重命名会话 -->
      <a-modal v-model:open="renameState.open" title="重命名会话" ok-text="保存" cancel-text="取消" @ok="doRename">
        <a-input v-model:value="renameState.title" :maxlength="50" placeholder="会话标题（≤50 字）"
                 @press-enter="doRename" />
      </a-modal>

      <div class="side-foot">
        <!-- 头像+昵称即「个人设置」入口（此前另有一个与头像语义重复的人形图标，折叠态还挤溢出） -->
        <a-tooltip :title="collapsed ? '个人设置（' + (userName || '未登录') + '）' : '个人设置'" placement="right">
          <button class="foot-user" @click="goProfile">
            <UserAvatar :avatar="userAvatar" :name="userName" :size="22" />
            <span v-if="!collapsed" class="user-name">{{ userName || '未登录' }}</span>
          </button>
        </a-tooltip>
        <!-- 站内通知铃铛：Badge=未读数（30s 轮询），点开拉列表；popover 挂 body，样式走底部非 scoped 块 -->
        <a-popover v-model:open="notifOpen" trigger="click" placement="topRight" overlay-class-name="notif-popover" @open-change="onNotifOpen">
          <template #content>
            <div class="notif-panel">
              <div class="notif-head">
                <span class="notif-title">通知</span>
                <button v-if="unreadCount > 0" class="notif-readall" @click="markAllRead">全部已读</button>
              </div>
              <a-spin v-if="notifLoading" size="small" style="display:block;margin:24px auto" />
              <div v-else-if="!notifItems.length" class="notif-empty">暂无通知</div>
              <div v-else class="notif-list">
                <button v-for="n in notifItems" :key="n.id" class="notif-item" :class="{ unread: !n.readFlag }" @click="openNotif(n)">
                  <component :is="notifIcon(n.type)" class="notif-ic" :class="notifClass(n.type)" />
                  <span class="notif-body">
                    <span class="notif-item-title">{{ n.title }}<span v-if="!n.readFlag" class="notif-dot" /></span>
                    <span v-if="n.content" class="notif-content">{{ n.content }}</span>
                    <span class="notif-time">{{ notifTime(n.createTime) }}</span>
                  </span>
                </button>
              </div>
            </div>
          </template>
          <a-badge :count="unreadCount" :offset="[-4, 4]" size="small" :title="''">
            <a-tooltip title="通知" placement="right">
              <button class="app-icon-btn"><bell-outlined /></button>
            </a-tooltip>
          </a-badge>
        </a-popover>
        <a-tooltip :title="themeState === 'dark' ? '切换到亮色主题' : '切换到暗色主题'" placement="right">
          <button class="app-icon-btn" @click="toggleTheme">
            <!-- 主题切换：亮色显月亮（点去暗色）、暗色显太阳（点去亮色），替代原先的灯泡 -->
            <svg v-if="themeState === 'dark'" class="theme-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <circle cx="12" cy="12" r="4"/>
              <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/>
            </svg>
            <svg v-else class="theme-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>
            </svg>
          </button>
        </a-tooltip>
        <a-tooltip title="退出登录" placement="right">
          <button class="app-icon-btn" @click="doLogout"><logout-outlined /></button>
        </a-tooltip>
      </div>
    </aside>

    <!-- 配置引导抽屉：常驻入口点开，清单含向量模型项（scope=all）；跳转前由组件 emit close 关闭 -->
    <a-drawer v-model:open="guideOpen" placement="left" :width="400" title="配置引导">
      <SetupGuide scope="all" variant="plain" @close="guideOpen = false" />
    </a-drawer>

    <!-- 主内容区 -->
    <div class="main"><router-view /></div>

    <!-- 全局帮助入口：右下角悬浮「?」，任意页面就抽屉读手册（帮助中心整页 /help 上不重复出现） -->
    <HelpFab />
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { PlusOutlined, MessageOutlined, RobotOutlined, FolderOutlined, BarChartOutlined, SettingOutlined, ExperimentOutlined,
         MenuFoldOutlined, MenuUnfoldOutlined, DeleteOutlined, DownloadOutlined, TeamOutlined, CompassOutlined,
         LogoutOutlined, UserOutlined, DatabaseOutlined, SafetyOutlined, AppstoreOutlined, FileOutlined,
         FileTextOutlined, SearchOutlined, CloseOutlined, PushpinOutlined, MoreOutlined, EditOutlined, StarFilled, StarOutlined,
         CheckOutlined, CheckSquareOutlined, QuestionCircleOutlined, PieChartOutlined,
         RightOutlined, BellOutlined, CheckCircleFilled, CloseCircleFilled, ExclamationCircleFilled } from '@ant-design/icons-vue'
import { deleteSessionApi, logoutApi, renameSessionApi, pinSession, favoriteSession, batchDeleteSessionsApi,
         notificationList, notificationUnreadCount, notificationMarkRead, notificationMarkAllRead } from '../api'
import { themeState, toggleTheme } from '../utils/theme'
import { authUser, ensureAuth, isAdminSync, clearAuth } from '../utils/auth'
import { chatDone, chatReady, defaultReady, embeddingReady, pendingCount, refreshSetupGuide, setupGuide,
         advPendingCount } from '../utils/setupGuide'
import { sessionStore, loadSessions, loadMoreSessions, collapseSessions, visibleSessions, chatStreams } from './store'
import BrandMark from '../components/BrandMark.vue'
import HelpFab from '../components/HelpFab.vue'
import UserAvatar from '../components/UserAvatar.vue'
import SetupGuide from '../components/SetupGuide.vue'
import { exportSessionMarkdown } from './exportMd'
import './app.css'

const route = useRoute()
const router = useRouter()
const isAdmin = ref(isAdminSync())
// 侧栏显示名：读 auth.js 的响应式镜像——个人设置改完昵称 ensureAuth(true) 后这里立即跟着变，无需刷新
const userName = computed(() => {
  const i = authUser.value
  return ((i && (i.username || i.user)) || '')
})
// 头像同理做空安全：身份就绪前 authUser 可能为 null（守卫时序已被 router.js 收口，
// 此处防御模板直读 .avatar 抛 Cannot read properties of null）
const userAvatar = computed(() => {
  const i = authUser.value
  return (i && i.avatar) || ''
})

// 侧边栏菜单：/auth/me 下发的菜单树（顶级渲染为导航项；子级预留，当前侧边栏一层平铺）
const ICONS = {
  MessageOutlined, RobotOutlined, DatabaseOutlined, TeamOutlined, BarChartOutlined,
  ExperimentOutlined, SafetyOutlined, SettingOutlined, AppstoreOutlined, PlusOutlined,
  FolderOutlined, UserOutlined, FileOutlined, FileTextOutlined, QuestionCircleOutlined,
  PieChartOutlined
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

// 会话时间字段稳健解析：ISO 字符串为主（Spring 默认序列化），兼容时间戳/数组/对象形态
const sessTime = v => {
  if (!v) return null
  if (typeof v === 'number') return new Date(v)
  if (typeof v === 'string') { const d = new Date(v); return isNaN(d.getTime()) ? null : d }
  if (Array.isArray(v)) return new Date(v[0], (v[1] || 1) - 1, v[2] || 1, v[3] || 0, v[4] || 0, v[5] || 0)
  if (typeof v === 'object' && v.year) return new Date(v.year, (v.monthValue || 1) - 1, v.dayOfMonth || 1, v.hour || 0, v.minute || 0, v.second || 0)
  return null
}
// 按时间分组（后端已按 置顶→更新时间 排序，这里只分桶不改序）：置顶 / 今天 / 7 天内 / 更早；
// 组内保持后端序，空组不渲染；批量全选口径仍是 visibleSessionList（平铺），与分组显示无关
const groupedSessions = computed(() => {
  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const weekStart = startOfToday - 6 * 86400000
  const groups = [
    { label: '置顶', items: [] },
    { label: '今天', items: [] },
    { label: '7 天内', items: [] },
    { label: '更早', items: [] }
  ]
  for (const s of visibleSessionList.value) {
    if (s.isPinned === 1) { groups[0].items.push(s); continue }
    const d = sessTime(s.updateTime)
    const t = d ? d.getTime() : 0
    if (t >= startOfToday) groups[1].items.push(s)
    else if (t >= weekStart) groups[2].items.push(s)
    else groups[3].items.push(s)
  }
  return groups.filter(g => g.items.length)
})

// 分组折叠状态（记忆到 localStorage，默认全展开）；搜索时强制全展开——搜到却看不见是死胡同
const GROUPS_KEY = 'app_sess_groups_collapsed'
const collapsedGroups = reactive(new Set((() => {
  try { return JSON.parse(localStorage.getItem(GROUPS_KEY) || '[]') } catch { return [] }
})()))
const toggleGroup = label => {
  collapsedGroups.has(label) ? collapsedGroups.delete(label) : collapsedGroups.add(label)
  localStorage.setItem(GROUPS_KEY, JSON.stringify([...collapsedGroups]))
}
const groupHidden = label => !searchKw.value && collapsedGroups.has(label)

// ==================== 会话分页：组头全量数字 + 「查看更多」增量加载 ====================
// 组头计数用后端 groupCounts（全量口径，仅统计有消息的会话）；后端未返回时回退已加载条数
const COUNT_KEYS = { 置顶: 'pinned', 今天: 'today', '7 天内': 'week', 更早: 'earlier' }
const groupTotal = (label, loaded) => sessionStore.counts[COUNT_KEYS[label]] ?? loaded
// 「查看更多 (N)」的 N：全量 - 已加载可展示数（两者同口径：仅统计有消息的会话）
const sessRemaining = computed(() => Math.max(0, (sessionStore.total || 0) - visibleSessionList.value.length))
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
  else if (key === 'batch') enterBatchMode()
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

// ==================== 新手配置引导（侧栏入口 + 菜单 tag + 抽屉） ====================
// 状态口径见 utils/setupGuide.js：入口/抽屉=任一项未完成；/chat tag=①②任一未完成；
// /knowledge tag=③未完成。loaded=false（首次对账未成功）一律不显示，避免给存量用户误报。
// 两层清单：必配（pendingCount，琥珀待办）与进阶（advPendingCount，「配置了效果更好」弱化建议）。
const guideOpen = ref(false)
const guideVisible = computed(() =>
  setupGuide.loaded && (pendingCount.value > 0 || advPendingCount.value > 0))
// 必配全部完成后入口切弱化形态：不再琥珀计数、无折叠圆点，避免"永远有待办"的催办感
const guideAdvOnly = computed(() => pendingCount.value === 0 && advPendingCount.value > 0)
const guideEntryTitle = computed(() =>
  guideAdvOnly.value ? '进阶配置 — 配置后效果更好（可选）' : '配置引导')
// 聊天侧缺失项文案（tooltip 用）
const chatPendingText = computed(() => {
  const parts = []
  if (!chatReady.value) parts.push('添加聊天模型')
  if (!defaultReady.value) parts.push('设置默认聊天模型')
  return parts.join('、')
})
const menuPending = m =>
  (m.path === '/chat' && !chatDone.value) || (m.path === '/knowledge' && !embeddingReady.value)
const menuTitle = m => {
  if (m.path === '/chat' && !chatDone.value) return `${m.name} — 还差：${chatPendingText.value}`
  if (m.path === '/knowledge' && !embeddingReady.value) return `${m.name} — 还差：添加向量模型（建知识库需要）`
  return m.name
}
// 导航回来 TTL 对账（15s 内不重复请求；配置动作后的 force 对账在 Providers/Profile 页内做）
watch(() => route.path, () => { refreshSetupGuide() })

// 退出登录：令牌无状态，清本地令牌并回登录页
const doLogout = async () => {
  try { await logoutApi() } catch (e) { /* 忽略：服务端不维护会话 */ }
  clearAuth()
  message.success('已退出登录')
  router.replace('/login')
}

// ==================== 站内通知（铃铛）：未读数 30s 轮询，点开拉列表 ====================
// 后端触发面：解析终态（成功/终态失败）、工作流失败/超时/挂起待审核（非 manual 的失败才通知——
// manual 的发起人正在画布前看 SSE 实时进度）、网页源自动刷新失败。接收人=资源归属人。
const notifOpen = ref(false)
const notifLoading = ref(false)
const notifItems = ref([])
const unreadCount = ref(0)
let notifTimer = null

// 类型 → 图标/语义色：成功绿、失败红、等待/超时黄
const NOTIF_ICONS = {
  'parse.done': CheckCircleFilled,
  'parse.failed': CloseCircleFilled,
  'workflow.failed': CloseCircleFilled,
  'workflow.timeout': ExclamationCircleFilled,
  'workflow.approval': ExclamationCircleFilled,
  'web.refresh.failed': CloseCircleFilled
}
const NOTIF_TONES = {
  'parse.done': 'ok',
  'parse.failed': 'err',
  'workflow.failed': 'err',
  'workflow.timeout': 'warn',
  'workflow.approval': 'warn',
  'web.refresh.failed': 'err'
}
const notifIcon = t => NOTIF_ICONS[t] || BellOutlined
const notifClass = t => NOTIF_TONES[t] || ''
// 复用会话时间的稳健解析（ISO/数组/对象形态都兼容）
const notifTime = v => {
  const d = sessTime(v)
  if (!d) return ''
  const diff = Date.now() - d.getTime()
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  if (diff < 7 * 86_400_000) return `${Math.floor(diff / 86_400_000)} 天前`
  const p = n => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}
// 未读数轮询：失败静默（登录态失效由 app:unauthorized 全局接管，轮询自身不弹错）
const refreshUnread = async () => {
  try {
    const res = await notificationUnreadCount()
    unreadCount.value = res?.count || 0
  } catch { /* 下一轮再试 */ }
}
const loadNotifs = async () => {
  notifLoading.value = true
  try {
    const res = await notificationList(50)
    notifItems.value = res?.items || []
    unreadCount.value = res?.unreadCount || 0
  } catch (e) { message.error(e.message || '通知加载失败') }
  finally { notifLoading.value = false }
}
const onNotifOpen = open => { if (open) loadNotifs() }
const markAllRead = async () => {
  try {
    await notificationMarkAllRead()
    notifItems.value = notifItems.value.map(n => ({ ...n, readFlag: 1 }))
    unreadCount.value = 0
  } catch (e) { message.error(e.message || '操作失败') }
}
// 点击通知：先本地置已读（乐观更新，失败回滚），再按 ref 跳转
const openNotif = async n => {
  if (!n.readFlag) {
    n.readFlag = 1
    unreadCount.value = Math.max(0, unreadCount.value - 1)
    notificationMarkRead([n.id]).catch(() => { n.readFlag = 0; refreshUnread() })
  }
  if (n.refType === 'kb' && n.refId) router.push(`/knowledge/${n.refId}/docs`)
  else if (n.refType === 'workflow' && n.refId) router.push({ path: '/agents', query: { tab: 'workflow' } })
  notifOpen.value = false
}

onMounted(async () => {
  const info = await ensureAuth(true)
  isAdmin.value = Boolean(info && info.admin)
  navMenus.value = ((info && info.menus) || []).filter(m => m && m.path)
  loadSessions()
  refreshUnread()
  refreshSetupGuide(true)  // 登录即可见的配置引导首拉（此时菜单树已就绪，tag/入口立即可判）
  notifTimer = setInterval(refreshUnread, 30_000)
})
onUnmounted(() => clearInterval(notifTimer))
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
.side.collapsed .side-logo svg { display: none; }
/* 折叠态导航图标对齐到侧边栏中轴（实测导航图标左偏 4px） */
.side.collapsed .nav-item { justify-content: center; padding-left: 0; padding-right: 0; }
/* 折叠态：底部改为竖排（头像=个人设置入口 + 主题 + 退出），沿侧边栏中轴对齐——
   横排 3 个 26px 图标在 ~56px 图标条里放不下，此前直接溢出 */
.side.collapsed .side-foot { flex-direction: column; gap: 6px; padding: 8px 0 6px; }
.side.collapsed .side-foot .app-icon-btn { margin-left: 0 !important; }
/* 品牌标用 BrandMark 组件（SVG 自带圆角与品牌渐变，明暗主题通用）；此处只留占位规则 */
.logo-name { font-weight: 500; font-size: 13px; white-space: nowrap; }
.fold { margin-left: auto; }
.side.collapsed .fold { margin-left: 0; }

.side-nav { display: flex; flex-direction: column; gap: 2px; }
.nav-item {
  display: flex; align-items: center; gap: 9px; border: none; background: transparent;
  padding: 7px 9px; border-radius: 8px; font-size: 13px; color: var(--app-text2);
  cursor: pointer; text-align: left; white-space: nowrap; transition: background .15s, color .15s;
  position: relative; /* 折叠态/窄屏的待配置圆点绝对定位于行内右上角 */
}
.nav-item:hover { background: var(--app-accent-weak); color: var(--app-text); }
.nav-item.active { background: var(--app-accent-weak); color: var(--app-text); font-weight: 500; }

/* ==================== 新手配置引导：菜单 tag + 折叠圆点 + 侧栏入口 ==================== */
/* 展开态行尾琥珀 pill（复用 .app-pill 形态，配色走主题变量以兼容暗色） */
.nav-item .app-pill.nav-pending {
  margin-left: auto; flex: none;
  font-size: 10px; padding: 2.5px 7px;
  color: var(--app-warn-text); background: var(--app-warn-weak);
}
/* 折叠态/窄屏（≤768 纯 CSS 收成图标条）：span 文本被隐藏，用 <i> 圆点提示待配置（span 会被隐藏规则吞掉） */
.nav-dot {
  display: none; position: absolute; top: 6px; right: 9px;
  width: 6px; height: 6px; border-radius: 50%;
  background: var(--app-warn); flex: none;
}
.side.collapsed .nav-dot { display: block; }
@media (max-width: 768px) { .side .nav-dot { display: block; } }
/* 侧栏引导入口（导航组末尾）：与上方导航留分组间距；必配态主色弱底 + 琥珀计数角标 */
.guide-entry { margin-top: 10px; background: var(--app-accent-weak); }
.guide-entry-text { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; }
.guide-count {
  flex: none; font-size: 10px; line-height: 1; padding: 3px 7px; border-radius: 999px;
  color: var(--app-warn-text); background: var(--app-warn-weak);
}
/* 进阶弱化态：链接式（无弱底、灰字），hover 才提亮——感知得到但不催办 */
.guide-entry.adv { background: transparent; color: var(--app-text3); }
.guide-entry.adv:hover { background: var(--app-accent-weak); color: var(--app-text2); }
.guide-count.muted { color: var(--app-text3); background: var(--app-panel-2); }

.side-label { margin: 14px 8px 4px; font-size: 11px; color: var(--app-text3); }
/* 分组头可点折叠：全宽按钮化，箭头指示状态（展开=向下），右侧淡显条数 */
.sess-group-label {
  /* 不写 width:calc(100%-12px)：百分比宽 + margin 在滚动容器里会撑出横向溢出（出现横向滚动条），
     用 flex 列布局默认拉伸 + margin 收窄即可 */
  display: flex; align-items: center; gap: 4px;
  border: none; background: transparent; cursor: pointer; padding: 2px 3px; margin: 14px 6px 4px;
  border-radius: 6px; font-size: 11px; color: var(--app-text3); text-align: left;
  transition: color .15s, background .15s;
}
.sess-group-label:hover { color: var(--app-text2); background: var(--app-panel-2); }
/* 箭头靠右：文字在左，条数紧贴文字，箭头居行尾指示展开/折叠 */
.group-caret { margin-left: auto; font-size: 9px; transition: transform .15s; }
.group-caret.open { transform: rotate(90deg); }
.group-count { font-size: 10px; color: var(--app-text3); opacity: .8; }
/* 列表内时间分组组头：比页级标签更贴紧（首组上方由搜索框间距兜底） */
.sess-group-label { margin: 10px 8px 3px; }
/* 批量管理入口在每条会话「更多」菜单里（搜索框不再内嵌入口） */
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

/* 会话搜索框（列表顶部，防抖走后端检索）
   视觉与导航项同一语言：无边框弱底胶囊，聚焦时才浮现边框——
   此前常驻边框+纯白底，夹在无边框导航项之间显得格格不入 */
.sess-search-wrap {
  position: relative; display: flex; align-items: center; margin: 6px 2px 6px;
  border: 1px solid transparent; border-radius: 8px; background: var(--app-panel-2);
}
.sess-search-wrap:focus-within { border-color: var(--app-accent); background: var(--app-bg, #fff); }
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
/* 游标分页：底部增量加载入口——居左、内边距与 .sess-item 对齐（6px 9px），文本随行首对齐；
   展开后「查看更多」旁出现「收起」（还原首屏），蓝=主操作、灰=次级操作 */
.sess-more { display: flex; align-items: center; gap: 10px; padding: 2px 0 4px; }
.sess-more-btn {
  border: none; background: none; cursor: pointer;
  font-size: 12px; color: var(--app-accent); padding: 6px 9px; border-radius: 8px;
}
.sess-more-btn:hover { background: var(--app-accent-weak); }
.sess-more-btn.collapse { color: var(--app-text2); }
.sess-empty-more {
  display: block; margin: 6px auto 0; border: none; background: none; cursor: pointer;
  font-size: 12px; color: var(--app-accent); padding: 2px 8px; border-radius: 4px;
}
.sess-empty-more:hover { background: var(--app-accent-weak); }

.side-foot {
  display: flex; align-items: center; gap: 4px; padding: 8px 6px 2px;
  border-top: 1px solid var(--app-border);
}
/* 头像+昵称 = 个人设置入口（点击进 /profile），占满剩余宽度把右侧两个图标推到行尾 */
.foot-user {
  flex: 1; min-width: 0; display: flex; align-items: center; gap: 8px;
  border: none; background: transparent; cursor: pointer; padding: 3px 4px;
  border-radius: 6px; text-align: left;
  transition: background .15s;
}
.foot-user:hover { background: var(--app-accent-weak); }
.user-name { font-size: 12px; color: var(--app-text2); min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
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
  .side .side-logo svg { display: none; }
  .side .side-logo { justify-content: center; padding: 2px 0 12px; }
  .side .fold { margin-left: 0; }
  .side .nav-item { justify-content: center; padding-left: 0; padding-right: 0; }
  .side .nav-item > span { display: none; }
  .side .side-label, .side .sess-search-wrap, .side .sess-title, .side .sess-op,
  .side .sess-del, .side .sess-export, .side .sess-pin-flag, .side .sess-empty, .side .user-name { display: none; }
  .side .side-sessions { align-items: center; }
  .side .sess-item { justify-content: center; padding: 6px 0; width: 100%; }
  .side .sess-dot { display: block; }
  .side .side-foot { flex-direction: column; gap: 6px; padding: 8px 0 6px; }
  .side .side-foot .app-icon-btn { margin-left: 0 !important; }
}
</style>

<!-- 站内通知面板样式：popover 内容 teleport 到 body，scoped 特性丢失，必须用非 scoped 块；
     全部类挂在 .notif-popover 下防全局泄漏 -->
<style>
.notif-popover .ant-popover-inner { padding: 10px; border-radius: var(--app-radius); }
.notif-popover .notif-panel { width: 320px; }
.notif-popover .notif-head { display: flex; align-items: center; justify-content: space-between; padding: 2px 4px 8px; border-bottom: 1px solid var(--app-border); }
.notif-popover .notif-title { font-weight: 600; font-size: 13px; color: var(--app-text); }
.notif-popover .notif-readall { border: none; background: none; color: var(--app-accent); cursor: pointer; font-size: 12px; padding: 0; }
.notif-popover .notif-readall:hover { color: var(--app-accent-hover); }
.notif-popover .notif-empty { padding: 30px 0; text-align: center; color: var(--app-text3); font-size: 12px; }
.notif-popover .notif-list { max-height: 380px; overflow-y: auto; scrollbar-width: thin; }
.notif-popover .notif-item { display: flex; gap: 10px; align-items: flex-start; width: 100%; border: none; background: none; text-align: left; padding: 10px 8px; border-radius: 8px; cursor: pointer; }
.notif-popover .notif-item:hover { background: var(--app-panel-2); }
.notif-popover .notif-item.unread { background: var(--app-accent-weak); }
.notif-popover .notif-item.unread:hover { background: var(--app-panel-2); }
.notif-popover .notif-ic { font-size: 16px; margin-top: 2px; flex: none; }
.notif-popover .notif-ic.ok { color: var(--app-ok); }
.notif-popover .notif-ic.err { color: var(--app-danger); }
.notif-popover .notif-ic.warn { color: var(--app-warn); }
.notif-popover .notif-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.notif-popover .notif-item-title { font-size: 13px; color: var(--app-text); line-height: 1.4; }
.notif-popover .notif-item.unread .notif-item-title { font-weight: 600; }
.notif-popover .notif-dot { display: inline-block; width: 6px; height: 6px; border-radius: 50%; background: var(--app-accent); margin-left: 6px; vertical-align: middle; }
.notif-popover .notif-content { font-size: 12px; color: var(--app-text2); line-height: 1.45; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; word-break: break-all; }
.notif-popover .notif-time { font-size: 11px; color: var(--app-text3); }
</style>
