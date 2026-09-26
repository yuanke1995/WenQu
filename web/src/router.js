import { createRouter, createWebHistory } from 'vue-router'
import { message } from 'ant-design-vue'
import { ensureAuth, isAdminSync, isLoggedIn } from './utils/auth'
import Login from './views/LoginPage.vue'
// 工作台（三栏布局）：侧边栏 + 内容区
import AppLayout from './views/AppLayout.vue'
import Chat from './views/ChatPage.vue'
import AgentsHub from './views/AgentsHubPage.vue'
import KnowledgeBase from './views/KnowledgeBasePage.vue'
import Documents from './views/DocumentsPage.vue'
import Dashboard from './views/DashboardPage.vue'
import Settings from './views/SettingsPage.vue'
import Evaluation from './views/EvaluationPage.vue'
import Members from './views/MembersPage.vue'
import Profile from './views/ProfilePage.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/chat' },
    { path: '/login', component: Login, meta: { title: '登录' } },
    { path: '/chat', component: AppLayout, children: [{ path: '', component: Chat }] },
    { path: '/profile', component: AppLayout, children: [{ path: '', component: Profile }], meta: { title: '个人设置' } },
    { path: '/agents', component: AppLayout, children: [{ path: '', component: AgentsHub }], meta: { requiresAdmin: true, title: '智能体' } },
    // 模型供应商并入智能体页 Tab（?tab=providers）；旧入口重定向
    { path: '/providers', redirect: { path: '/agents', query: { tab: 'providers' } } },
    { path: '/knowledge', component: AppLayout, children: [
      { path: '', component: KnowledgeBase },
      { path: ':kbId/docs', component: Documents, meta: { title: '文档管理' } }
    ], meta: { requiresAdmin: true, title: '知识库' } },
    // 文档管理并入知识库（卡片点进）；旧入口重定向
    { path: '/documents', redirect: '/knowledge' },
    { path: '/members', component: AppLayout, children: [{ path: '', component: Members }], meta: { requiresAdmin: true, title: '成员管理' } },
    { path: '/dashboard', component: AppLayout, children: [{ path: '', component: Dashboard }], meta: { requiresAdmin: true, title: '数据看板' } },
    { path: '/settings', component: AppLayout, children: [{ path: '', component: Settings }], meta: { requiresAdmin: true, title: '系统设置' } },
    { path: '/evaluation', component: AppLayout, children: [{ path: '', component: Evaluation }], meta: { requiresAdmin: true, title: '检索评估' } },
    // 兜底：未知路径（含历史遗留的旧链接）统一回到对话页
    { path: '/:pathMatch(.*)*', redirect: '/chat' }
  ]
})

// 路由守卫：先要求登录（本地登录令牌），再对管理页要求管理员
router.beforeEach(async to => {
  if (to.path === '/login') return true
  if (!isLoggedIn()) return { path: '/login', replace: true }
  if (!to.meta.requiresAdmin) return true
  // 未拉取过身份则先向 /auth/me 确认
  await ensureAuth()
  if (isAdminSync()) return true
  message.warning('该功能仅管理员可用')
  return { path: '/chat', replace: true }
})

export default router
