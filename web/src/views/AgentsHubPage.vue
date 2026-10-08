<template>
  <!-- 智能体工作台：Tab 清单由菜单表数据驱动（renderAs=tab 且 parent=menu-agents）。
       2026-10-08 前 Tab 硬编码于此，与 RBAC 完全脱节——一个 menu-agents 绑定 = 6 个 Tab
       全有或全无。收编后每个 Tab 是独立菜单，可单独授权（对齐其数据隔离粒度：
       技能/MCP 是个人资产、智能体自建自管、供应商谁建归谁）。
       职责边界：菜单表管「有哪些/叫什么/什么顺序」，本组件只管「每个 key 渲染成什么」
       （TAB_COMPONENTS 映射）——不要把组件类名塞进菜单表，也不要把清单写回这里。
       技能与 MCP 是个人资产（2026-09-26 从「系统设置」迁出）；智能体普通用户自建自管
       （ResourceVisibilityService 资源级隔离）；模型供应商谁建归谁（2026-10）；
       2026-10-02 起全部资源严格按 userId 隔离，管理员级也无全量视角。 -->
  <div class="app-page">
    <a-spin v-if="loading" style="display:block;margin:48px auto" />
    <a-empty v-else-if="!tabs.length" description="没有可用的功能 Tab（请联系管理员授权）"
             style="margin-top:64px" />
    <a-tabs v-else v-model:activeKey="active" class="hub-tabs" destroy-inactive-tab-pane>
      <a-tab-pane v-for="t in tabs" :key="t.key" :tab="t.name">
        <component :is="TAB_COMPONENTS[t.key]" />
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AgentsPage from './AgentsPage.vue'
import ProvidersPage from './ProvidersPage.vue'
import SkillPanel from './SkillPanel.vue'
import McpPanel from './McpPanel.vue'
import ScheduledPanel from './ScheduledPanel.vue'
import WorkflowPanel from './WorkflowPanel.vue'
import { ensureAuth } from '../utils/auth'

/** tabKey → 组件。菜单表不知道组件的存在，这张表是数据与视图之间唯一的桥 */
const TAB_COMPONENTS = {
  providers: ProvidersPage,
  agents: AgentsPage,
  skills: SkillPanel,
  mcp: McpPanel,
  workflow: WorkflowPanel,
  scheduled: ScheduledPanel
}

/** 从 tab 菜单的 path（/agents?tab=xxx）解析组件映射 key */
const tabKeyOf = p => {
  try { return new URLSearchParams(String(p || '').split('?')[1] || '').get('tab') } catch (e) { return null }
}

const route = useRoute()
const router = useRouter()

const loading = ref(true)
/** 可见 Tab 清单：来自 /auth/me 菜单树的 children（已按角色过滤 + sortOrder 排序） */
const tabs = ref([])

onMounted(async () => {
  // AppLayout 挂载时已 ensureAuth，此处命中模块级缓存，不重复请求
  const info = await ensureAuth()
  // 宿主定位用 path 而非写死 menu-agents：任何「sidebar 父 + tab 子」的页面都能复用这套模式
  const parent = (info?.menus || []).find(m => m && m.path === '/agents')
  tabs.value = ((parent && parent.children) || [])
    .filter(c => c && c.renderAs === 'tab')
    .map(c => ({ id: c.id, key: tabKeyOf(c.path), name: c.name, path: c.path }))
    .filter(t => t.key && TAB_COMPONENTS[t.key])  // 菜单表里有、代码里还没映射的 Tab 不渲染（升级半程态容错）
  loading.value = false
})

// Tab 状态落在路由 query（?tab=skills），刷新/旧链接可还原；切 Tab 用 replace 不堆历史记录。
// get 兜底：URL 里的 tab 被权限收回（不在可见集合）或非法时，回落到第一个可见 Tab 并修正 URL——
// 否则用户会停在「当前 Tab 不存在」的空白页，且 URL 看起来一切正常。
const active = computed({
  get: () => {
    const q = route.query.tab
    if (tabs.value.some(t => t.key === q)) return q
    return tabs.value.length ? tabs.value[0].key : undefined
  },
  set: v => router.replace({ path: '/agents', query: v && v !== tabs.value[0]?.key ? { tab: v } : {} })
})
</script>

<style scoped>
.hub-tabs { flex: 1; min-height: 0; }
.hub-tabs :deep(.ant-tabs-nav) { margin-bottom: 0; padding: 4px 20px 0; background: var(--app-panel); }
.hub-tabs :deep(.ant-tabs-tab) { font-size: 13px; padding: 10px 2px; }
.hub-tabs :deep(.ant-tabs-nav::before) { border-color: var(--app-border); }
.hub-tabs :deep(.ant-tabs-content-holder) { flex: 1; min-height: 0; overflow-y: auto; }
.hub-tabs :deep(.ant-tabs-content) { height: 100%; }
.hub-tabs :deep(.ant-tabs-tabpane) { height: 100%; }
</style>
