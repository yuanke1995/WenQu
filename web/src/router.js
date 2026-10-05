import { createRouter, createWebHistory } from 'vue-router'
import { message } from 'ant-design-vue'
import { ensureAuth, isAdminSync, isLoggedIn, menuHasPath } from './utils/auth'
import { preferMobileShell } from './h5/mobile'

// 视图全部懒加载：每个页面（连带其专属依赖，如 Stats/知识库的 echarts、
// FlowEditor 的 vue-flow）各自成 chunk，首次导航才下载——手机首屏不再下载全站。
// AppLayout 被多条路由引用，rollup 按 module id 去重，只打包/下载一次。
const page = loader => () => loader()

const routes = [
  { path: '/', redirect: '/chat' },
  { path: '/login', component: page(() => import('./views/LoginPage.vue')), meta: { title: '登录' } },
  // 单点登录回调（IdP 授权后由后端 302 到这里）：此时还没有登录令牌，必须免登录守卫
  { path: '/auth/oidc/callback', component: page(() => import('./views/OidcCallbackPage.vue')), meta: { title: '单点登录', public: true } },
  // 智能体公开分享（/s/{token} 免登录对话；?embed=1 为 iframe 嵌入的紧凑模式）
  { path: '/s/:token', component: page(() => import('./views/ShareChatPage.vue')), meta: { title: '智能体对话', public: true } },
  // 会话只读分享（/shared/{token} 免登录查看一段对话；不能续聊，内容为正文 + 来源文档名）
  // pageFlow：内容按文档流自然增高的独立页（不经 AppLayout 工作台外壳）——App.vue 据此
  // 绕开 .content-app 的 height:100vh + overflow:hidden，否则超出一屏的部分既滚不动也不显示。
  { path: '/shared/:token', component: page(() => import('./views/SharedSessionPage.vue')), meta: { title: '对话分享', public: true, pageFlow: true } },
  { path: '/chat', component: page(() => import('./views/AppLayout.vue')), children: [{ path: '', component: page(() => import('./views/ChatPage.vue')) }] },
  // 移动原生壳（M2）：不经 AppLayout 工作台外壳，独立布局；手机访问 /chat 由守卫重定向到这里。
  // 与管理页的 DesktopOnlyGuard 白名单无关（那套只管 AppLayout 子路由）。
  { path: '/m/chat', component: page(() => import('./h5/MobileChatPage.vue')), meta: { title: '对话' } },
  { path: '/profile', component: page(() => import('./views/AppLayout.vue')), children: [{ path: '', component: page(() => import('./views/ProfilePage.vue')) }], meta: { title: '个人设置' } },
  // 智能体工作台不再要求管理员：技能 Skills 与 MCP 是个人资产，所有人都要能进来管自己的；
  // 其中的「模型供应商 / 智能体」Tab 在页内按管理员身份显隐（见 AgentsHubPage）
  { path: '/agents', component: page(() => import('./views/AppLayout.vue')), children: [{ path: '', component: page(() => import('./views/AgentsHubPage.vue')) }], meta: { title: '智能体' } },
  // 模型供应商并入智能体页 Tab（?tab=providers）；旧入口重定向
  { path: '/providers', redirect: { path: '/agents', query: { tab: 'providers' } } },
  { path: '/knowledge', component: page(() => import('./views/AppLayout.vue')), children: [
    { path: '', component: page(() => import('./views/KnowledgeBasePage.vue')) },
    { path: ':kbId/docs', component: page(() => import('./views/DocumentsPage.vue')), meta: { title: '文档管理' } }
  ], meta: { title: '知识库' } },
  // 文档管理并入知识库（卡片点进）；旧入口重定向
  { path: '/documents', redirect: '/knowledge' },
  { path: '/artifacts', component: page(() => import('./views/AppLayout.vue')), children: [{ path: '', component: page(() => import('./views/ArtifactsPage.vue')) }], meta: { title: '我的产物' } },
  { path: '/notifications', component: page(() => import('./views/AppLayout.vue')), children: [{ path: '', component: page(() => import('./views/NotificationCenter.vue')) }], meta: { title: '通知中心' } },
  // 使用统计：个人 Token 用量（所有登录用户，数据按会话归属隔离）
  { path: '/stats', component: page(() => import('./views/AppLayout.vue')), children: [{ path: '', component: page(() => import('./views/StatsPage.vue')) }], meta: { title: '使用统计' } },
  // 帮助中心：官方内置手册（问渠使用手册）只读视图，所有登录用户可见
  { path: '/help', component: page(() => import('./views/AppLayout.vue')), children: [{ path: '', component: page(() => import('./views/HelpPage.vue')) }], meta: { title: '帮助中心' } },
  { path: '/members', component: page(() => import('./views/AppLayout.vue')), children: [{ path: '', component: page(() => import('./views/MembersPage.vue')) }], meta: { requiresAdmin: true, title: '成员管理' } },
  { path: '/permissions', component: page(() => import('./views/AppLayout.vue')), children: [{ path: '', component: page(() => import('./views/PermissionsPage.vue')) }], meta: { requiresAdmin: true, title: '权限管理' } },
  { path: '/dashboard', component: page(() => import('./views/AppLayout.vue')), children: [{ path: '', component: page(() => import('./views/DashboardPage.vue')) }], meta: { requiresAdmin: true, title: '数据看板' } },
  { path: '/settings', component: page(() => import('./views/AppLayout.vue')), children: [{ path: '', component: page(() => import('./views/SettingsPage.vue')) }], meta: { requiresAdmin: true, title: '系统设置' } },
  { path: '/evaluation', component: page(() => import('./views/AppLayout.vue')), children: [{ path: '', component: page(() => import('./views/EvaluationPage.vue')) }], meta: { requiresAdmin: true, title: '检索评估' } },
  // 兜底：未知路径（含历史遗留的旧链接）统一回到对话页
  { path: '/:pathMatch(.*)*', redirect: '/chat' }
]

const router = createRouter({ history: createWebHistory(), routes })

// 路由守卫：先要求登录（本地登录令牌），再对权限受控页校验——
// 管理员级角色（admin/superadmin 或自定义 admin_flag=1）放行；
// 普通角色若其角色绑定的菜单包含该路径（RBAC 授权）也放行，否则回对话页
router.beforeEach(async to => {
  if (to.path === '/login' || to.meta.public) return true
  if (!isLoggedIn()) return { path: '/login', replace: true }
  // 未拉取过身份则先向 /auth/me 确认（同时下发菜单树）。必须先于任何登录页放行：
  // 布局层（侧栏头像/昵称）直接读 authUser，而「全新浏览器首次登录」时 main.js 启动阶段
  // 还没有令牌、不会预拉身份，登录后首次进入布局页会在渲染期读到 null 而报错。
  // 缓存命中时此处瞬时返回，仅首次登录真正发一次 /auth/me。
  await ensureAuth()
  // 设备形态重定向（登录态之后）：手机（触屏且宽 ≤1024）把 /chat 交给移动原生壳 /m/chat，
  // 查询串（?sid=）原样透传；桌面与「鼠标用户拖窄窗口」preferMobileShell 恒 false，行为零变化。
  // 反向同判：在 /m/chat 上放大窗口/接上鼠标后回到 PC 布局。
  if (to.path === '/chat' && preferMobileShell()) return { path: '/m/chat', query: to.query, replace: true }
  if (to.path === '/m/chat' && !preferMobileShell()) return { path: '/chat', query: to.query, replace: true }
  if (!to.meta.requiresAdmin) return true
  if (isAdminSync() || menuHasPath(to.path)) return true
  message.warning('暂无该功能的访问权限（可联系管理员在权限管理中为角色授权）')
  return { path: '/chat', replace: true }
})

export default router
