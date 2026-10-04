import { createRouter, createWebHistory } from 'vue-router'
import { message } from 'ant-design-vue'
import { ensureAuth, isAdminSync, isLoggedIn, menuHasPath } from './utils/auth'
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
import Permissions from './views/PermissionsPage.vue'
import Profile from './views/ProfilePage.vue'
import Artifacts from './views/ArtifactsPage.vue'
import Stats from './views/StatsPage.vue'
import Help from './views/HelpPage.vue'
import OidcCallback from './views/OidcCallbackPage.vue'
import ShareChat from './views/ShareChatPage.vue'
import SharedSession from './views/SharedSessionPage.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/chat' },
    { path: '/login', component: Login, meta: { title: '登录' } },
    // 单点登录回调（IdP 授权后由后端 302 到这里）：此时还没有登录令牌，必须免登录守卫
    { path: '/auth/oidc/callback', component: OidcCallback, meta: { title: '单点登录', public: true } },
    // 智能体公开分享（/s/{token} 免登录对话；?embed=1 为 iframe 嵌入的紧凑模式）
    { path: '/s/:token', component: ShareChat, meta: { title: '智能体对话', public: true } },
    // 会话只读分享（/shared/{token} 免登录查看一段对话；不能续聊，内容为正文 + 来源文档名）
    // pageFlow：内容按文档流自然增高的独立页（不经 AppLayout 工作台外壳）——App.vue 据此
    // 绕开 .content-app 的 height:100vh + overflow:hidden，否则超出一屏的部分既滚不动也不显示。
    { path: '/shared/:token', component: SharedSession, meta: { title: '对话分享', public: true, pageFlow: true } },
    { path: '/chat', component: AppLayout, children: [{ path: '', component: Chat }] },
    { path: '/profile', component: AppLayout, children: [{ path: '', component: Profile }], meta: { title: '个人设置' } },
    // 智能体工作台不再要求管理员：技能 Skills 与 MCP 是个人资产，所有人都要能进来管自己的；
    // 其中的「模型供应商 / 智能体」Tab 在页内按管理员身份显隐（见 AgentsHubPage）
    { path: '/agents', component: AppLayout, children: [{ path: '', component: AgentsHub }], meta: { title: '智能体' } },
    // 模型供应商并入智能体页 Tab（?tab=providers）；旧入口重定向
    { path: '/providers', redirect: { path: '/agents', query: { tab: 'providers' } } },
    { path: '/knowledge', component: AppLayout, children: [
      { path: '', component: KnowledgeBase },
      { path: ':kbId/docs', component: Documents, meta: { title: '文档管理' } }
    ], meta: { title: '知识库' } },
    // 文档管理并入知识库（卡片点进）；旧入口重定向
    { path: '/documents', redirect: '/knowledge' },
    { path: '/artifacts', component: AppLayout, children: [{ path: '', component: Artifacts }], meta: { title: '我的产物' } },
    // 使用统计：个人 Token 用量（所有登录用户，数据按会话归属隔离）
    { path: '/stats', component: AppLayout, children: [{ path: '', component: Stats }], meta: { title: '使用统计' } },
    // 帮助中心：官方内置手册（问渠使用手册）只读视图，所有登录用户可见
    { path: '/help', component: AppLayout, children: [{ path: '', component: Help }], meta: { title: '帮助中心' } },
    { path: '/members', component: AppLayout, children: [{ path: '', component: Members }], meta: { requiresAdmin: true, title: '成员管理' } },
    { path: '/permissions', component: AppLayout, children: [{ path: '', component: Permissions }], meta: { requiresAdmin: true, title: '权限管理' } },
    { path: '/dashboard', component: AppLayout, children: [{ path: '', component: Dashboard }], meta: { requiresAdmin: true, title: '数据看板' } },
    { path: '/settings', component: AppLayout, children: [{ path: '', component: Settings }], meta: { requiresAdmin: true, title: '系统设置' } },
    { path: '/evaluation', component: AppLayout, children: [{ path: '', component: Evaluation }], meta: { requiresAdmin: true, title: '检索评估' } },
    // 兜底：未知路径（含历史遗留的旧链接）统一回到对话页
    { path: '/:pathMatch(.*)*', redirect: '/chat' }
  ]
})

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
  if (!to.meta.requiresAdmin) return true
  if (isAdminSync() || menuHasPath(to.path)) return true
  message.warning('暂无该功能的访问权限（可联系管理员在权限管理中为角色授权）')
  return { path: '/chat', replace: true }
})

export default router
