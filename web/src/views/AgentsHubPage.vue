<template>
  <!-- 智能体工作台：模型供应商 + 智能体 合并为两个 Tab（供应商在上），侧边栏只留「智能体」一个入口 -->
  <div class="app-page">
    <a-tabs v-model:activeKey="active" class="hub-tabs" destroy-inactive-tab-pane>
      <a-tab-pane key="providers" tab="模型供应商"><ProvidersPage /></a-tab-pane>
      <a-tab-pane key="agents" tab="智能体"><AgentsPage /></a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AgentsPage from './AgentsPage.vue'
import ProvidersPage from './ProvidersPage.vue'

// Tab 状态落在路由 query（?tab=providers），刷新/旧链接可还原；切 Tab 用 replace 不堆历史记录
const route = useRoute()
const router = useRouter()
const active = computed({
  get: () => (route.query.tab === 'providers' ? 'providers' : 'agents'),
  set: v => router.replace({ path: '/agents', query: v === 'providers' ? { tab: 'providers' } : {} })
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
