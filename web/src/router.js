import { createRouter, createWebHistory } from 'vue-router'
import { message } from 'ant-design-vue'
import { ensureAuth, isAdminSync, isLoggedIn } from './utils/auth'
import Login from './views/LoginPage.vue'
// 工作台（三栏布局）：侧边栏 + 内容区
import V2Layout from './views/v2/V2Layout.vue'
import ChatV2 from './views/v2/ChatPage.vue'
import AgentsV2 from './views/v2/AgentsPage.vue'
import DocumentsV2 from './views/v2/DocumentsPage.vue'
import DashboardV2 from './views/v2/DashboardPage.vue'
import SettingsV2 from './views/v2/SettingsPage.vue'
import EvaluationV2 from './views/v2/EvaluationPage.vue'
import MembersV2 from './views/v2/MembersPage.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/chat' },
    { path: '/login', component: Login, meta: { title: '登录' } },
    { path: '/chat', component: V2Layout, children: [{ path: '', component: ChatV2 }] },
    { path: '/agents', component: V2Layout, children: [{ path: '', component: AgentsV2 }], meta: { requiresAdmin: true, title: '智能体' } },
    { path: '/documents', component: V2Layout, children: [{ path: '', component: DocumentsV2 }], meta: { requiresAdmin: true, title: '文档管理' } },
    { path: '/members', component: V2Layout, children: [{ path: '', component: MembersV2 }], meta: { requiresAdmin: true, title: '成员管理' } },
    { path: '/dashboard', component: V2Layout, children: [{ path: '', component: DashboardV2 }], meta: { requiresAdmin: true, title: '数据看板' } },
    { path: '/settings', component: V2Layout, children: [{ path: '', component: SettingsV2 }], meta: { requiresAdmin: true, title: '系统设置' } },
    { path: '/evaluation', component: V2Layout, children: [{ path: '', component: EvaluationV2 }], meta: { requiresAdmin: true, title: '检索评估' } },
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
