import { createRouter, createWebHistory } from 'vue-router'
import { message } from 'ant-design-vue'
import { ensureAuth, isAdminSync } from './utils/auth'
import Chat from './views/Chat.vue'
import Documents from './views/Documents.vue'
import Dashboard from './views/Dashboard.vue'
import Settings from './views/Settings.vue'
import Evaluation from './views/Evaluation.vue'
// v2 工作台（三栏布局的新版界面，与旧版页面并存，路由前缀 /v2）
import V2Layout from './views/v2/V2Layout.vue'
import ChatV2 from './views/v2/ChatPage.vue'
import DocumentsV2 from './views/v2/DocumentsPage.vue'
import DashboardV2 from './views/v2/DashboardPage.vue'
import SettingsV2 from './views/v2/SettingsPage.vue'
import EvaluationV2 from './views/v2/EvaluationPage.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/chat' },
    { path: '/chat', component: Chat },
    { path: '/documents', component: Documents, meta: { requiresAdmin: true, title: '文档管理' } },
    { path: '/dashboard', component: Dashboard, meta: { requiresAdmin: true, title: '数据看板' } },
    { path: '/settings', component: Settings, meta: { requiresAdmin: true, title: '系统设置' } },
    { path: '/evaluation', component: Evaluation, meta: { requiresAdmin: true, title: '检索评估' } },
    // v2：聊天对所有用户开放，管理页同样走管理员守卫
    { path: '/v2', redirect: '/v2/chat' },
    { path: '/v2/chat', component: V2Layout, children: [{ path: '', component: ChatV2 }] },
    { path: '/v2/documents', component: V2Layout, children: [{ path: '', component: DocumentsV2 }], meta: { requiresAdmin: true, title: '文档管理' } },
    { path: '/v2/dashboard', component: V2Layout, children: [{ path: '', component: DashboardV2 }], meta: { requiresAdmin: true, title: '数据看板' } },
    { path: '/v2/settings', component: V2Layout, children: [{ path: '', component: SettingsV2 }], meta: { requiresAdmin: true, title: '系统设置' } },
    { path: '/v2/evaluation', component: V2Layout, children: [{ path: '', component: EvaluationV2 }], meta: { requiresAdmin: true, title: '检索评估' } }
  ]
})

// 管理员路由守卫：文档/看板/设置/评估仅管理员可访问（普通用户只开放智能问答）
router.beforeEach(async to => {
  if (!to.meta.requiresAdmin) return true
  // 未拉取过身份则先向 /auth/me 确认（管理员口令或网关 X-User-Id 白名单判定）
  await ensureAuth()
  if (isAdminSync()) return true
  message.warning('该功能仅管理员可用')
  return { path: '/chat', replace: true }
})

export default router
