<template>
  <!-- 智能体工作台：四个 Tab 对所有人开放。
       技能 Skills 与 MCP 是**个人资产**（每人管自己的，2026-09-26 从「系统设置」迁出）；
       智能体对普通用户开放**自建自管**（后端按 ResourceVisibilityService 做资源级隔离：
       只能看到/管理自己创建的与共享给自己的）；
       模型供应商分两级（2026-09-26）：管理员登记的 = 平台级（所有人可见可用，对普通用户只读），
       普通用户可登记**自己的**网关与 Key = 个人级（仅本人可见可用）。 -->
  <div class="app-page">
    <a-tabs v-model:activeKey="active" class="hub-tabs" destroy-inactive-tab-pane>
      <a-tab-pane key="providers" tab="模型供应商"><ProvidersPage /></a-tab-pane>
      <a-tab-pane key="agents" tab="智能体"><AgentsPage /></a-tab-pane>
      <a-tab-pane key="skills" tab="技能 Skills"><SkillPanel /></a-tab-pane>
      <a-tab-pane key="mcp" tab="MCP 外部工具"><McpPanel /></a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AgentsPage from './AgentsPage.vue'
import ProvidersPage from './ProvidersPage.vue'
import SkillPanel from './SkillPanel.vue'
import McpPanel from './McpPanel.vue'

// Tab 状态落在路由 query（?tab=skills），刷新/旧链接可还原；切 Tab 用 replace 不堆历史记录
const route = useRoute()
const router = useRouter()

const TABS = ['providers', 'agents', 'skills', 'mcp']

const active = computed({
  get: () => {
    const q = route.query.tab
    // 默认落在「智能体」；未知 tab（含历史链接）一律回落
    return TABS.includes(q) ? q : 'agents'
  },
  set: v => router.replace({ path: '/agents', query: v === 'agents' ? {} : { tab: v } })
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
