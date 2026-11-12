<template>
  <div class="app-page quality-page">
    <div class="app-page-head">
      <h3 class="app-page-title">质量</h3>
      <span class="head-stat">检索评估 · 智能体评测 · 数据看板</span>
    </div>

    <a-tabs v-model:activeKey="active" class="quality-tabs" @change="onTab">
      <a-tab-pane v-for="t in tabs" :key="t.key" :tab="t.label" />
    </a-tabs>

    <div class="quality-body">
      <keep-alive>
        <component :is="current" />
      </keep-alive>
    </div>
  </div>
</template>

<script setup>
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isAdminSync } from '../utils/auth'

const EvaluationPage = defineAsyncComponent(() => import('./EvaluationPage.vue'))
const AgentEvalPage = defineAsyncComponent(() => import('./AgentEvalPage.vue'))
const DashboardPage = defineAsyncComponent(() => import('./DashboardPage.vue'))

const route = useRoute()
const router = useRouter()

const ALL_TABS = [
  { key: 'eval', label: '检索评估', component: EvaluationPage, admin: true },
  { key: 'agent', label: '智能体评测', component: AgentEvalPage, admin: false },
  { key: 'dashboard', label: '数据看板', component: DashboardPage, admin: true }
]

const tabs = computed(() => isAdminSync() ? ALL_TABS : ALL_TABS.filter(t => !t.admin))
const active = ref('')

const pick = key => {
  const visible = tabs.value
  return visible.some(t => t.key === key) ? key : visible[0]?.key || ''
}

const current = computed(() => (tabs.value.find(t => t.key === active.value) || tabs.value[0])?.component)

const syncFromRoute = () => { active.value = pick(String(route.query.view || '')) }
syncFromRoute()
watch(() => route.query.view, syncFromRoute)

const onTab = key => {
  const q = { ...route.query, view: key }
  if (key === (tabs.value[0]?.key || '')) delete q.view
  router.replace({ path: '/quality', query: q })
}
</script>

<style scoped>
.quality-page { height: 100%; min-height: 0; display: flex; flex-direction: column; }
.quality-tabs { flex: none; }
.quality-tabs :deep(.ant-tabs-nav) { margin: 0; padding: 0 20px; background: var(--app-panel); }
.quality-tabs :deep(.ant-tabs-nav::before) { border-color: var(--app-border); }
.quality-body { flex: 1; min-height: 0; overflow: auto; }
</style>
