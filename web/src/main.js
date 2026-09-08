import { createApp } from 'vue'
import Antd from 'ant-design-vue'
import { message } from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import './md.css'
import App from './App.vue'
import router from './router'

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
window.addEventListener('app:unauthorized', () => {
  message.error('登录状态已失效，请刷新页面重试')
})

// 403 统一处理（管理端点被拒：非管理员或管理员口令无效）
window.addEventListener('app:forbidden', () => {
  message.error('无管理员权限，请先完成管理员验证')
})

app.use(Antd).use(router).mount('#app')
