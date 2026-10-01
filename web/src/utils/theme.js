// ==================== 主题（亮色/暗色）====================
// 事实源：<html data-theme="light|dark">——所有 --app-* token 的双套值定义在 app.css，
// 组件样式一律走 token，不感知主题；antd 组件主题由 App.vue 的 ConfigProvider 对齐。
// 持久化 localStorage.app_theme；首次进入跟随系统 prefers-color-scheme（之后以用户选择为准）。
// 防首屏闪烁：index.html 内联脚本在 CSS 加载前先设 data-theme（此处 initTheme 只做状态同步）。
import { ref } from 'vue'

const KEY = 'app_theme'

export const themeState = ref('light')

/** 应用主题（写 DOM + 存储 + 响应式状态，三处同源） */
export function applyTheme (t) {
  const v = t === 'dark' ? 'dark' : 'light'
  themeState.value = v
  try { document.documentElement.dataset.theme = v } catch (e) { /* 非浏览器环境忽略 */ }
  try { localStorage.setItem(KEY, v) } catch (e) { /* 存储不可用忽略 */ }
}

/** 切换亮/暗 */
export function toggleTheme () {
  applyTheme(themeState.value === 'dark' ? 'light' : 'dark')
}

/** 启动时同步状态（DOM 上可能已由 index.html 内联脚本设好，这里以 DOM 为准读回） */
export function initTheme () {
  let t = ''
  try { t = document.documentElement.dataset.theme || localStorage.getItem(KEY) || '' } catch (e) { /* 忽略 */ }
  if (!t) {
    t = (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) ? 'dark' : 'light'
  }
  themeState.value = t === 'dark' ? 'dark' : 'light'
  try { document.documentElement.dataset.theme = themeState.value } catch (e) { /* 忽略 */ }
}
