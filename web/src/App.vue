<template>
  <a-config-provider :locale="zhCN">
    <a-layout style="min-height:100vh">
      <!-- v2 工作台自带侧边栏壳层，隐藏旧顶部导航（旧页面路由不受影响） -->
      <a-layout-header v-if="!isV2" class="header">
        <div class="logo">
          <robot-outlined style="color:#fff;font-size:20px;margin-right:8px" />
          <span>问渠 WenQu</span>
        </div>
        <a-menu theme="dark" mode="horizontal" :selected-keys="[activeKey]" @click="onMenu" class="menu">
          <a-menu-item key="chat">智能问答</a-menu-item>
          <template v-if="isAdmin">
            <a-menu-item key="documents">文档管理</a-menu-item>
            <a-menu-item key="dashboard">数据看板</a-menu-item>
            <a-menu-item key="evaluation">检索评估</a-menu-item>
            <a-menu-item key="settings">系统设置</a-menu-item>
          </template>
        </a-menu>
        <a-tooltip v-if="!isAdmin" title="管理员验证后可使用文档管理/系统设置等功能">
          <a-button type="text" class="admin-btn" @click="adminModal = true">
            <safety-certificate-outlined style="color:#fff;font-size:16px" />
          </a-button>
        </a-tooltip>
        <a-modal v-model:open="adminModal" title="管理员验证" :confirm-loading="adminVerifying" ok-text="验证" cancel-text="取消"
                 width="420px" @ok="verifyAdmin">
          <p style="margin-top:0">请输入管理员访问口令（对应后端 <code>AI_ADMIN_TOKEN</code>；若已由平台网关按账号白名单识别为管理员则无需输入，可直接进入管理功能）。</p>
          <a-input-password v-model:value="adminTokenInput" placeholder="管理员口令" @pressEnter="verifyAdmin" />
        </a-modal>
      </a-layout-header>
      <a-layout-content :class="isV2 ? 'content-v2' : 'content'">
        <router-view />
      </a-layout-content>
    </a-layout>
  </a-config-provider>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { RobotOutlined, SafetyCertificateOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'
import { ensureAuth, isAdminSync, setAdminToken } from './utils/auth'

// antd 组件全局中文化（确认框按钮/分页/日期等）
dayjs.locale('zh-cn')

const route = useRoute()
const router = useRouter()
// 首屏 mount 前 main.js 已 ensureAuth（cached 就绪），此处同步取初始值，杜绝"先渲染隐藏、后更新菜单"的闪烁/滞后
const isAdmin = ref(isAdminSync())
const adminModal = ref(false)
const adminTokenInput = ref('')
const adminVerifying = ref(false)
const refreshRole = async () => { await ensureAuth(true); isAdmin.value = isAdminSync() }
onMounted(async () => {
  await ensureAuth()
  isAdmin.value = isAdminSync()
})
const verifyAdmin = async () => {
  const token = (adminTokenInput.value || '').trim()
  if (!token) { message.warning('请输入管理员口令'); return }
  adminVerifying.value = true
  try {
    const me = await setAdminToken(token)
    if (me?.admin) {
      message.success('管理员验证成功')
      adminModal.value = false
      adminTokenInput.value = ''
      isAdmin.value = true
    } else {
      message.error('口令无效或无管理员权限')
      adminTokenInput.value = ''
    }
  } finally {
    adminVerifying.value = false
  }
}
const activeKey = computed(() => {
  if (route.path === '/documents') return 'documents'
  if (route.path === '/dashboard') return 'dashboard'
  if (route.path === '/evaluation') return 'evaluation'
  if (route.path === '/settings') return 'settings'
  return 'chat'
})
// v2 工作台路由前缀：隐藏旧壳层（顶栏/内边距），由 V2Layout 自带侧边栏
const isV2 = computed(() => route.path.startsWith('/v2'))
const onMenu = ({ key }) => {
  const map = { chat: '/chat', documents: '/documents', dashboard: '/dashboard', evaluation: '/evaluation', settings: '/settings' }
  router.push(map[key] || '/chat')
}
</script>

<style>
html, body { margin: 0; overflow-x: hidden; }
/* 全局纤细滚动条：轨道透明（消除页面/容器滚动条轨道的竖向分界线），滑块圆角 */
::-webkit-scrollbar { width: 8px; height: 8px; }
::-webkit-scrollbar-thumb { background: rgba(0,0,0,.18); border-radius: 4px; }
::-webkit-scrollbar-thumb:hover { background: rgba(0,0,0,.28); }
::-webkit-scrollbar-track, ::-webkit-scrollbar-corner { background: transparent; }
* { scrollbar-width: thin; scrollbar-color: rgba(0,0,0,.18) transparent; }
.header { display:flex;align-items:center }
.logo { color:#fff;font-size:16px;font-weight:600;display:flex;align-items:center;margin-right:40px }
.menu { flex:1;min-width:0 }
.admin-btn { margin-right:8px }
.admin-btn:hover { background:rgba(255,255,255,.12) !important }
.content { padding:24px;background:#f0f2f5 }
/* v2 工作台：无内边距满屏，由内部布局自己管理 */
.content-v2 { padding:0;background:#f7f8fa;height:100vh;overflow:hidden }
</style>