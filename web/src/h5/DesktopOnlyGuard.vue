<template>
  <!-- 窄屏页面守卫：非白名单页面渲染引导卡，**且不渲染 slot**（否则被拦的页面
       仍然挂在 DOM 里、只是被盖住，滚动与点击都会穿透到下面的真实内容）。
       白名单页面则把 slot 原样透出——守卫退化为纯粹的 v-if。 -->
  <div v-if="!allowed" class="dg-wrap">
    <div class="dg-card">
      <desktop-outlined class="dg-ic" />
      <div class="dg-title">该页面建议用电脑访问</div>
      <div class="dg-desc">{{ desc }}</div>
      <button class="app-btn" @click="goChat">返回对话</button>
    </div>
  </div>
  <slot v-else />
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { DesktopOutlined } from '@ant-design/icons-vue'

// 窄屏下正常可用的路径白名单。与 router.js 的路由表对应，
// 但**刻意不复用 router 的 meta**（加 meta 需改 15 处路由，且将来新增页面容易漏配 →
// 漏配的后果是「新页面在手机上直接被拦」，比不拦更糟）。
// 白名单是显式枚举，新增页面默认走引导卡，是更安全的默认值。
const ALLOW = [
  '/chat',       // 对话（主入口）
  '/profile',    // 个人设置：改密码必须在手机上可用
  '/help',       // 帮助中心：纯文档阅读，窄屏样式由 md.css 兜住
  '/artifacts',  // 我的产物：卡片列表形态，手机上查看/下载是真需求（窄屏适配见 ArtifactsPage.vue）
  '/knowledge'   // 知识库：只读浏览（库卡片 + 文档列表 + 知识块预览），管理动作仍在桌面做
]
// 兜底用前缀匹配：带路径参数的子路由（/knowledge/:id/docs）按前缀判，
// 不在前缀表里即视为引导卡
const ALLOW_PREFIX = ['/profile', '/help', '/knowledge']

const props = defineProps({
  path: { type: String, required: true }
})
const router = useRouter()

const allowed = computed(() =>
  ALLOW.includes(props.path) || ALLOW_PREFIX.some(p => props.path.startsWith(p + '/'))
)
const desc = computed(() =>
  props.path.startsWith('/knowledge')
    ? '知识库与文档管理包含较多表格与表单，在手机上操作体验有限。'
    : '系统设置、成员权限、数据看板等页面以表格与表单为主，在手机上操作体验有限。'
)
const goChat = () => router.push('/chat').catch(() => {})
</script>

<style scoped>
.dg-wrap {
  height: 100%; display: flex; align-items: center; justify-content: center;
  padding: 24px calc(20px + var(--sal, 0px)) 24px calc(20px + var(--sar, 0px));
}
.dg-card {
  max-width: 320px; text-align: center; padding: 28px 22px;
  background: var(--app-panel); border: 1px solid var(--app-border);
  border-radius: var(--app-radius-lg);
}
.dg-ic { font-size: 34px; color: var(--app-text3); }
.dg-title { margin-top: 12px; font-size: 15px; font-weight: 500; color: var(--app-text); }
.dg-desc { margin: 8px 0 18px; font-size: 13px; line-height: 1.6; color: var(--app-text2); }
</style>
