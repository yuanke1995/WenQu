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
        <button class="nav-item" :class="{ active: isActive('/chat') }" @click="goChat" title="对话">
          <message-outlined />
          <span v-if="!collapsed">对话</span>
        </button>
        <template v-if="isAdmin">
          <button class="nav-item" :class="{ active: isActive('/agents') }" @click="router.push('/agents')" title="智能体">
            <robot-outlined />
            <span v-if="!collapsed">智能体</span>
          </button>
          <button class="nav-item" :class="{ active: isActive('/documents') }" @click="router.push('/documents')" title="文档管理">
            <folder-outlined />
            <span v-if="!collapsed">文档管理</span>
          </button>
          <button class="nav-item" :class="{ active: isActive('/members') }" @click="router.push('/members')" title="成员管理">
            <team-outlined />
            <span v-if="!collapsed">成员管理</span>
          </button>
          <button class="nav-item" :class="{ active: isActive('/dashboard') }" @click="router.push('/dashboard')" title="数据看板">
            <bar-chart-outlined />
            <span v-if="!collapsed">数据看板</span>
          </button>
          <button class="nav-item" :class="{ active: isActive('/evaluation') }" @click="router.push('/evaluation')" title="检索评估">
            <experiment-outlined />
            <span v-if="!collapsed">检索评估</span>
          </button>
          <button class="nav-item" :class="{ active: isActive('/settings') }" @click="router.push('/settings')" title="系统设置">
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
        <a-tooltip title="修改密码" placement="right">
          <button class="app-icon-btn" @click="pwdModal = true"><lock-outlined /></button>
        </a-tooltip>
      </div>
    </aside>

    <!-- 主内容区 -->
    <div class="main"><router-view /></div>

    <!-- 修改密码（用户自助） -->
    <a-modal v-model:open="pwdModal" title="修改密码" :confirm-loading="pwdSaving" ok-text="保存" cancel-text="取消" width="420px" @ok="submitPwd">
      <a-form layout="vertical" style="margin-top:4px">
        <a-form-item label="当前密码" required>
          <a-input-password v-model:value="pwdForm.oldPassword" placeholder="请输入当前密码" @pressEnter="submitPwd" />
        </a-form-item>
        <a-form-item label="新密码" required>
          <a-input-password v-model:value="pwdForm.newPassword" placeholder="至少 6 位" @change="pwdError = ''" @pressEnter="submitPwd" />
        </a-form-item>
        <a-form-item label="确认新密码" required style="margin-bottom:0">
          <a-input-password v-model:value="pwdForm.confirm" placeholder="再次输入新密码" @pressEnter="submitPwd" />
        </a-form-item>
      </a-form>
      <p v-if="pwdError" class="pwd-err">{{ pwdError }}</p>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { PlusOutlined, MessageOutlined, RobotOutlined, FolderOutlined, BarChartOutlined, SettingOutlined, ExperimentOutlined,
         MenuFoldOutlined, MenuUnfoldOutlined, DeleteOutlined, DownloadOutlined, TeamOutlined,
         LogoutOutlined, LockOutlined } from '@ant-design/icons-vue'
import { deleteSessionApi, logoutApi, changePasswordApi } from '../api'
import { ensureAuth, isAdminSync, clearAuth } from '../utils/auth'
import { sessionStore, loadSessions, visibleSessions } from './store'
import { exportSessionMarkdown } from './exportMd'
import './app.css'

const route = useRoute()
const router = useRouter()
const isAdmin = ref(isAdminSync())
const userName = ref('')

// 侧边栏折叠（持久化）
const collapsed = ref(localStorage.getItem('app_sidebar') === '1')
const toggleFold = () => {
  collapsed.value = !collapsed.value
  localStorage.setItem('app_sidebar', collapsed.value ? '1' : '0')
}

const visibleSessionList = computed(visibleSessions)
const isActive = p => route.path === p
const goChat = () => router.push(route.query.sid ? { path: '/chat', query: { sid: route.query.sid } } : '/chat')
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

// 修改密码（用户自助，接 POST /auth/password；成功后强制重新登录）
const pwdModal = ref(false)
const pwdSaving = ref(false)
const pwdError = ref('')
const pwdForm = ref({ oldPassword: '', newPassword: '', confirm: '' })
const submitPwd = async () => {
  const f = pwdForm.value
  pwdError.value = ''
  if (!f.oldPassword) { pwdError.value = '请输入当前密码'; return }
  if (!f.newPassword || f.newPassword.length < 6) { pwdError.value = '新密码至少 6 位'; return }
  if (f.newPassword !== f.confirm) { pwdError.value = '两次输入的新密码不一致'; return }
  pwdSaving.value = true
  try {
    const r = await changePasswordApi(f.oldPassword, f.newPassword)
    if (r && r.success) {
      message.success('密码已修改，请重新登录')
      pwdModal.value = false
      clearAuth()
      router.push('/login')
    } else {
      message.error((r && r.msg) || '修改失败')
    }
  } catch (e) { message.error(e.message || '修改失败') }
  finally { pwdSaving.value = false }
}

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
  loadSessions()
})
</script>

<style scoped>
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
