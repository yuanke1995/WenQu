import { createRouter, createWebHistory } from 'vue-router'
import { message } from 'ant-design-vue'
import { ensureAuth, isAdminSync } from './utils/auth'
import Chat from './views/Chat.vue'
import Documents from './views/Documents.vue'
import Dashboard from './views/Dashboard.vue'
import Settings from './views/Settings.vue'
import Evaluation from './views/Evaluation.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/chat' },
    { path: '/chat', component: Chat },
    { path: '/documents', component: Documents, meta: { requiresAdmin: true, title: '文档管理' } },
    { path: '/dashboard', component: Dashboard, meta: { requiresAdmin: true, title: '数据看板' } },
    { path: '/settings', component: Settings, meta: { requiresAdmin: true, title: '系统设置' } },
    { path: '/evaluation', component: Evaluation, meta: { requiresAdmin: true, title: '检索评估' } }
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
