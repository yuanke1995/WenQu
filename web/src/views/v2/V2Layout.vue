<template>
  <div class="v2-root">
    <!-- 左侧边栏：logo / 导航 / 最近会话 / 底部用户区（可折叠为图标条） -->
    <aside class="side" :class="{ collapsed }">
      <div class="side-logo">
        <span class="logo-mark">文</span>
        <span v-if="!collapsed" class="logo-name">AI 文档助手</span>
        <button class="v2-icon-btn fold" :title="collapsed ? '展开侧边栏' : '折叠侧边栏'" @click="toggleFold">
          <menu-unfold-outlined v-if="collapsed" />
          <menu-fold-outlined v-else />
        </button>
      </div>

      <nav class="side-nav">
        <button class="nav-item" :class="{ active: isActive('/v2/chat') && !route.query.sid }" @click="newChat" title="新建对话">
          <plus-outlined />
          <span v-if="!collapsed">新建对话</span>
        </button>
        <button class="nav-item" :class="{ active: isActive('/v2/chat') }" @click="goChat" title="对话">
          <message-outlined />
          <span v-if="!collapsed">对话</span>
        </button>
        <template v-if="isAdmin">
          <button class="nav-item" :class="{ active: isActive('/v2/agents') }" @click="router.push('/v2/agents')" title="智能体">
            <robot-outlined />
            <span v-if="!collapsed">智能体</span>
          </button>
          <button class="nav-item" :class="{ active: isActive('/v2/documents') }" @click="router.push('/v2/documents')" title="文档管理">
            <folder-outlined />
            <span v-if="!collapsed">文档管理</span>
          </button>
          <button class="nav-item" :class="{ active: isActive('/v2/dashboard') }" @click="router.push('/v2/dashboard')" title="数据看板">
            <bar-chart-outlined />
            <span v-if="!collapsed">数据看板</span>
          </button>
          <button class="nav-item" :class="{ active: isActive('/v2/evaluation') }" @click="router.push('/v2/evaluation')" title="检索评估">
            <experiment-outlined />
            <span v-if="!collapsed">检索评估</span>
          </button>
          <button class="nav-item" :class="{ active: isActive('/v2/settings') }" @click="router.push('/v2/settings')" title="系统设置">
            <setting-outlined />
            <span v-if="!collapsed">系统设置</span>
          </button>
        </template>
      </nav>

      <div v-if="!collapsed" class="side-label">最近</div>
      <div class="side-sessions">
        <a-spin v-if="sessionStore.loading" size="small" style="display:block;margin:16px auto" />
        <template v-else>
          <div v-for="s in visibleSessionList" :key="s.id"
               class="sess-item" :class="{ active: isActive('/v2/chat') && route.query.sid === s.id }"
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
        <a-tooltip v-if="!isAdmin" :title="collapsed ? '管理员验证' : ''" placement="right">
          <button class="v2-icon-btn" style="margin-left:auto" @click="adminModal = true"><safety-certificate-outlined /></button>
        </a-tooltip>
      </div>
    </aside>

    <!-- 主内容区 -->
    <div class="main"><router-view /></div>

    <!-- 管理员验证（非管理员显示入口；逻辑与旧版一致） -->
    <a-modal v-model:open="adminModal" title="管理员验证" :confirm-loading="adminVerifying" ok-text="验证" cancel-text="取消" width="420px" @ok="verifyAdmin">
      <p style="margin-top:0">请输入管理员访问口令（对应后端 <code>AI_ADMIN_TOKEN</code>；若已由平台网关按账号白名单识别为管理员则无需输入）。</p>
      <a-input-password v-model:value="adminTokenInput" placeholder="管理员口令" @pressEnter="verifyAdmin" />
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { PlusOutlined, MessageOutlined, RobotOutlined, FolderOutlined, BarChartOutlined, SettingOutlined, ExperimentOutlined,
         MenuFoldOutlined, MenuUnfoldOutlined, DeleteOutlined, SafetyCertificateOutlined, DownloadOutlined } from '@ant-design/icons-vue'
import { deleteSessionApi } from '../../api'
import { ensureAuth, isAdminSync, setAdminToken } from '../../utils/auth'
import { sessionStore, loadSessions, visibleSessions } from './store'
import { exportSessionMarkdown } from './exportMd'
import './v2.css'

const route = useRoute()
const router = useRouter()
const isAdmin = ref(isAdminSync())
const userName = ref('')

// 侧边栏折叠（持久化）
const collapsed = ref(localStorage.getItem('v2_sidebar') === '1')
const toggleFold = () => {
  collapsed.value = !collapsed.value
  localStorage.setItem('v2_sidebar', collapsed.value ? '1' : '0')
}

const visibleSessionList = computed(visibleSessions)
const isActive = p => route.path === p
const goChat = () => router.push(route.query.sid ? { path: '/v2/chat', query: { sid: route.query.sid } } : '/v2/chat')
const newChat = () => {
  sessionStore.newChatTick++
  router.push('/v2/chat').catch(() => {})
}
const openSession = sid => router.push({ path: '/v2/chat', query: { sid } })

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
      router.push('/v2/chat').catch(() => {})
      sessionStore.autoPickTick++
    }
  } catch (e) { message.error(e.message || '删除失败') }
}

// 管理员验证（与旧版 App.vue 同逻辑）
const adminModal = ref(false)
const adminTokenInput = ref('')
const adminVerifying = ref(false)
const verifyAdmin = async () => {
  const token = (adminTokenInput.value || '').trim()
  if (!token) { message.warning('请输入管理员口令'); return }
  adminVerifying.value = true
  try {
    const me = await setAdminToken(token)
    if (me?.admin) {
      message.success('管理员验证成功')
      adminModal.value = false
      adminTokenInput.value = ''
      isAdmin.value = true
    } else {
      message.error('口令无效或无管理员权限')
      adminTokenInput.value = ''
    }
  } finally { adminVerifying.value = false }
}

onMounted(async () => {
  await ensureAuth()
  isAdmin.value = isAdminSync()
  try {
    const r = await import('../../api').then(m => m.getAuthMe())
    if (r?.success && r.data?.user) userName.value = r.data.user
  } catch (e) { /* 用户名仅展示用，失败静默 */ }
  loadSessions()
})
</script>

<style scoped>
.side {
  width: 200px; flex: none; display: flex; flex-direction: column;
  background: var(--v2-panel); border-right: 1px solid var(--v2-border);
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
.side.collapsed .side-foot .v2-icon-btn { margin-left: 0 !important; }
/* 非管理员折叠态：头像+验证按钮放不下（22+8+26 > 40），藏头像只留验证按钮并居中 */
.side.collapsed .side-foot .avatar:not(:only-child) { display: none; }
.logo-mark {
  width: 24px; height: 24px; border-radius: 6px; background: var(--v2-text);
  color: #fff; font-size: 12px; display: inline-flex; align-items: center; justify-content: center; flex: none;
}
.logo-name { font-weight: 500; font-size: 13px; white-space: nowrap; }
.fold { margin-left: auto; }
.side.collapsed .fold { margin-left: 0; }

.side-nav { display: flex; flex-direction: column; gap: 2px; }
.nav-item {
  display: flex; align-items: center; gap: 9px; border: none; background: transparent;
  padding: 7px 9px; border-radius: 8px; font-size: 13px; color: var(--v2-text2);
  cursor: pointer; text-align: left; white-space: nowrap; transition: background .15s, color .15s;
}
.nav-item:hover { background: var(--v2-accent-weak); color: var(--v2-text); }
.nav-item.active { background: var(--v2-accent-weak); color: var(--v2-text); font-weight: 500; }

.side-label { margin: 14px 8px 4px; font-size: 11px; color: var(--v2-text3); }
.side-sessions { flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 1px; }
.sess-item {
  display: flex; align-items: center; padding: 6px 9px; border-radius: 8px;
  font-size: 12px; color: var(--v2-text2); cursor: pointer; min-width: 0;
}
.sess-item:hover { background: #f2f4f7; }
.sess-item.active { background: var(--v2-accent-weak); color: var(--v2-text); }
.sess-title { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; flex: 1; min-width: 0; }
.sess-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--v2-text3); margin: 0 auto; }
.sess-item.active .sess-dot { background: var(--v2-accent); }
.sess-del { color: var(--v2-text3); opacity: 0; flex: none; margin-left: 4px; font-size: 12px; }
.sess-item:hover .sess-del { opacity: 1; }
.sess-del:hover { color: var(--v2-danger); }
.sess-export { color: var(--v2-text3); opacity: 0; flex: none; margin-left: 4px; font-size: 12px; }
.sess-item:hover .sess-export { opacity: 1; }
.sess-export:hover { color: var(--v2-accent); }
.sess-empty { font-size: 12px; color: var(--v2-text3); text-align: center; padding: 16px 0; }

.side-foot {
  display: flex; align-items: center; gap: 8px; padding: 8px 6px 2px;
  border-top: 1px solid var(--v2-border);
}
.avatar {
  width: 22px; height: 22px; border-radius: 50%; flex: none;
  background: var(--v2-accent-weak); color: var(--v2-accent);
  font-size: 11px; display: inline-flex; align-items: center; justify-content: center;
}
.user-name { font-size: 12px; color: var(--v2-text2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.main { flex: 1; min-width: 0; height: 100%; }
</style>
