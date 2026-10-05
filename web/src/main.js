import { createApp } from 'vue'
// antd 不再全量注册：模板里的 a-xxx 由 unplugin-vue-components 在编译期按需解析
// （见 vite.config.js 的 AntDesignVueResolver）。js 侧命令式 API（message/Modal/theme）
// 仍走各文件的具名导入——antd-vue 的 sideEffects 声明保证这些能被 tree-shake。
import { message } from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import './md.css'
// 设计令牌全局唯一来源（app.css）：此前只在 AppLayout 里 import——登录页/分享页等
// 不经布局的页面拿不到 --app-* 变量，这里提到入口统一加载
import './views/app.css'
import App from './App.vue'
import router from './router'
import { ensureAuth, isLoggedIn, clearAuth } from './utils/auth'
import { initTheme } from './utils/theme'
// 移动端地基（src/h5/）：装 visualViewport 监听（写 --kb / --app-vh 到 :root）。
// 必须在此处（组件树之外）安装——它监听的是 window 事件，与任何组件无关，
// 且要在首次渲染前就位，否则首帧会拿到过期的视口高度。
import { installKeyboardInset } from './h5/keyboard'

// ==================== Edge「窗口无法最小化」兼容修复 ====================
// 现象：Edge 中当「本页是激活标签」时最小化浏览器窗口，窗口缩下去后立即自动弹回；
//       切到别的标签页就正常；Chrome / Firefox 不复现。
// 根因（Edge 侧 bug，与本项目业务代码无关）：
//   窗口最小化 → document.visibilityState 变为 'hidden' → 触发 visibilitychange；
//   vue-router（4.6.x）的 html5 history 实现里的 beforeUnloadListener 会在此刻调用
//   history.replaceState() 保存滚动位置；Edge 把「visibilitychange 期间发生 history API
//   调用」误判为"页面需要被激活"的信号，于是强制还原已最小化的窗口。
//   参考：https://github.com/vuejs/router/issues/2644
// 修复：页面隐藏时屏蔽 history.replaceState（隐藏期间本应用不依赖它做导航）。
//   本项目 createRouter 未配置 scrollBehavior，该调用保存的滚动位置本就无人使用，
//   应用自身也从不直接调用 history API，故屏蔽对本项目无副作用。
// 清理：Edge 修复该 bug 后可整段删除；或改用降级 vue-router（< 4.6）的方案。
function patchEdgeMinimizeBug() {
  const h = window.history
  const rawReplaceState = h && h.replaceState
  if (typeof rawReplaceState !== 'function') return
  const patched = function (...args) {
    // 仅在页面处于隐藏态（最小化 / 切走窗口）时跳过，避免触发 Edge 的窗口还原
    if (document.visibilityState === 'hidden') return
    return rawReplaceState.apply(h, args)
  }
  try {
    h.replaceState = patched
  } catch (e) {
    // history 只读或不可写（极少数环境）：放弃补丁，不影响其余功能
    console.warn('[compat] history.replaceState 补丁未生效（Edge 最小化 bug 依旧）', e)
  }
}
patchEdgeMinimizeBug()

// 主题初始化：index.html 内联脚本已定好 DOM 上的 data-theme（防闪），
// 这里把它同步进响应式状态（antd ConfigProvider 需要感知亮/暗切换）。
initTheme()

// 软键盘视口变量：宽屏全程不启用（--kb 恒为 0、--app-vh 不写），PC 行为零变化
installKeyboardInset()

// ==================== PWA：Service Worker 注册（仅生产构建） ====================
// 只缓存 /assets/*（带内容 hash 的构建产物），分享页与 API 明确排除——策略见 public/sw.js 头注释。
// dev 不注册：vite 的资源 URL 不带 hash，SW 会缓存开发期模块、干扰热更新（改代码不生效的经典坑）。
// 注册失败只记日志：无 SW 时功能完全可用（只是少了安装能力与二次访问的资源缓存）。
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(e => {
      console.warn('[pwa] Service Worker 注册失败（不影响使用）', e)
    })
  })
}

const app = createApp(App)

// ==================== 全局错误边界（防白屏） ====================
// Vue 组件渲染/生命周期中抛出的异常：记录日志 + 用户友好提示，而不是整页白屏
let lastErrTip = 0
app.config.errorHandler = (err, _instance, info) => {
  console.error('[Vue Error]', info, err)
  const now = Date.now()
  if (now - lastErrTip > 5000) {   // 5 秒内只提示一次，防连报刷屏
    lastErrTip = now
    message.error('页面出现异常，请刷新重试')
  }
}

// 未捕获的 Promise 异常（接口异常已由 api.js 统一抛出，此处兜底记录）
window.addEventListener('unhandledrejection', e => {
  console.error('[UnhandledRejection]', e.reason)
})

// 401 统一处理（api.js 在请求/上传/SSE 检测到 401 时派发）
// 要点：401 = 登录态失效，除了提示，必须**清令牌 + 强制跳登录页**——
// 路由守卫只在「发生导航」时检查 isLoggedIn，而 401 事件本身不触发导航，光提示不会回登录页。
window.addEventListener('app:unauthorized', () => {
  message.error('登录状态已失效，请重新登录')
  clearAuth()
  if (router.currentRoute.value.path !== '/login') {
    // mount 前 router 可能未 ready，replace 会抛未处理 promise；吞掉（守卫会在导航时兜底）
    router.replace('/login').catch(() => {})
  }
})

// 403 统一处理：优先展示后端具体原因（资源归属如「无权访问该会话」、角色未授权、仅管理员等各有文案）；
// 未携带具体原因时回落到管理员验证提示（兜底旧端点）
window.addEventListener('app:forbidden', e => {
  message.error(e?.detail || '无管理员权限，请先完成管理员验证')
})

// 首屏先确认身份/角色再挂载（避免 App 渲染后才异步拉取导致的"角色已就绪但视图未刷新"时序问题）。
// 已登录才拉取；未登录直接进登录页，避免无谓的 401 噪音。
const boot = isLoggedIn() ? ensureAuth() : Promise.resolve()
boot.finally(() => {
  app.use(router).mount('#app')
})
