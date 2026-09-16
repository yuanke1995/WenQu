<template>
  <a-config-provider :locale="zhCN">
    <a-layout style="min-height:100vh">
      <a-layout-content :class="contentClass">
        <router-view />
      </a-layout-content>
    </a-layout>
  </a-config-provider>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'

// antd 组件全局中文化（确认框按钮/分页/日期等）
dayjs.locale('zh-cn')

const route = useRoute()
// 工作台自带侧边栏与滚动区域、登录页为独立全屏卡片：两者都不需要外层内边距
const isLogin = computed(() => route.path === '/login')
const contentClass = computed(() => (isLogin.value ? 'content-login' : 'content-v2'))
</script>

<style>
html, body { margin: 0; overflow-x: hidden; }
/* 全局纤细滚动条：轨道透明（消除页面/容器滚动条轨道的竖向分界线），滑块圆角 */
::-webkit-scrollbar { width: 8px; height: 8px; }
::-webkit-scrollbar-thumb { background: rgba(0,0,0,.18); border-radius: 4px; }
::-webkit-scrollbar-thumb:hover { background: rgba(0,0,0,.28); }
::-webkit-scrollbar-track, ::-webkit-scrollbar-corner { background: transparent; }
* { scrollbar-width: thin; scrollbar-color: rgba(0,0,0,.18) transparent; }
/* 工作台：无内边距满屏，由内部布局自己管理 */
.content-v2 { padding:0;background:#f7f8fa;height:100vh;overflow:hidden }
/* 登录页：无内边距，卡片自身居中 */
.content-login { padding:0;background:#f7f8fa }
</style>
