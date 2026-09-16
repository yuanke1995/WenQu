import { createApp } from 'vue'
import Antd from 'ant-design-vue'
import { message } from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import './md.css'
import App from './App.vue'
import router from './router'
import { ensureAuth, isLoggedIn, clearAuth } from './utils/auth'

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

// 403 统一处理（管理端点被拒：非管理员或管理员口令无效）
window.addEventListener('app:forbidden', () => {
  message.error('无管理员权限，请先完成管理员验证')
})

// 首屏先确认身份/角色再挂载（避免 App 渲染后才异步拉取导致的"角色已就绪但视图未刷新"时序问题）。
// 已登录才拉取；未登录直接进登录页，避免无谓的 401 噪音。
const boot = isLoggedIn() ? ensureAuth() : Promise.resolve()
boot.finally(() => {
  app.use(Antd).use(router).mount('#app')
})
