<template>
  <a-config-provider :locale="zhCN" :theme="antdThemeCfg">
    <a-layout style="min-height:100vh">
      <a-layout-content :class="contentClass">
        <router-view />
      </a-layout-content>
    </a-layout>
  </a-config-provider>
</template>

<script setup>
import { computed, watchEffect } from 'vue'
import { useRoute } from 'vue-router'
import { theme as antdTheme } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'
import { themeState } from './utils/theme'

// antd 组件全局中文化（确认框按钮/分页/日期等）
dayjs.locale('zh-cn')

const route = useRoute()
// 外层容器分两种形态：
//   content-app  —— 工作台（AppLayout 及其子页）满屏无内边距，高度锁一屏、滚动交给内部布局；
//                   另含 /s/:token 与 OIDC 回调——它们自带 height:100vh + 内部滚动容器，本就不吃外层滚动。
//   content-flow —— 内容按文档流自然增高的独立页（meta.pageFlow，如只读分享页）：不能锁高，
//                   否则超出一屏的部分被 overflow:hidden 裁掉且滚不动。
//   content-login—— 登录页：独立全屏卡片，由卡片自身居中。
const isLogin = computed(() => route.path === '/login')
const isFlow = computed(() => route.meta?.pageFlow === true)
const contentClass = computed(() => (isLogin.value ? 'content-login' : (isFlow.value ? 'content-flow' : 'content-app')))

// antd 组件主题与 --app-* token 对齐：亮/暗算法切换 + 品牌主色/圆角/字号对齐。
// 值必须是字面量（antd 的 token 不认 CSS 变量）——改 --app-accent 时这里同步改。
// 弹窗/下拉等 teleport 到 body 的组件此前一直是 antd 默认蓝 + 默认圆角，与产品设计不一致。
const antdThemeCfg = computed(() => ({
  algorithm: themeState.value === 'dark' ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
  token: {
    colorPrimary: themeState.value === 'dark' ? '#5b8cf0' : '#2e6be6',
    colorInfo: themeState.value === 'dark' ? '#5b8cf0' : '#2e6be6',
    colorError: themeState.value === 'dark' ? '#e07a5a' : '#d4552e',
    colorSuccess: themeState.value === 'dark' ? '#57a86a' : '#3b8f4e',
    colorWarning: themeState.value === 'dark' ? '#d9a03f' : '#c77c14',
    borderRadius: 8,
    fontSize: 13,
    fontFamily: 'inherit'
  }
}))
// antd 的 body 级样式（弹窗遮罩等）需要跟随主题重绘
watchEffect(() => {
  document.body.style.colorScheme = themeState.value
})
</script>

<style>
html, body { margin: 0; overflow-x: hidden; }
/* 全局纤细滚动条：轨道透明（消除页面/容器滚动条轨道的竖向分界线），滑块圆角 */
::-webkit-scrollbar { width: 8px; height: 8px; }
::-webkit-scrollbar-thumb { background: var(--app-scrollbar); border-radius: 4px; }
::-webkit-scrollbar-thumb:hover { background: var(--app-text3); }
::-webkit-scrollbar-track, ::-webkit-scrollbar-corner { background: transparent; }
* { scrollbar-width: thin; scrollbar-color: var(--app-scrollbar) transparent; }
/* 工作台：无内边距满屏，由内部布局自己管理 */
.content-app { padding:0;background:var(--app-bg, #f7f8fa);color:var(--app-text);height:100vh;overflow:hidden }
/* 登录页：无内边距，卡片自身居中 */
.content-login { padding:0;background:var(--app-bg, #f7f8fa) }
/* 文档流独立页（meta.pageFlow，如只读分享页）：与工作台同底色/文字色，但**不锁高、不裁溢出**——
   高度留给内容，滚动由文档本身承担。锁定会让超出一屏的内容既看不见也滚不动。 */
.content-flow { padding:0;background:var(--app-bg, #f7f8fa);color:var(--app-text) }
</style>
