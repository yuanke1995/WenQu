<template>
  <!-- 质量页：Tab 清单由菜单表数据驱动（renderAs=tab 且 parent=menu-evaluation），
       与「智能体」「权限管理」两页同一套模式：菜单表管「有哪些/叫什么/什么顺序」，
       本组件只管「每个 key 渲染成什么」（TAB_COMPONENTS 映射）。
       检索评估/数据看板原为管理员专属、智能体评测原为全员可见——收编后统一按角色绑定下发。 -->
  <div class="app-page quality-page">
    <div class="app-page-head">
      <h3 class="app-page-title">质量</h3>
      <span class="head-stat">{{ tabs.map(t => t.name).join(' · ') }}</span>
    </div>

    <a-spin v-if="loading" style="display:block;margin:48px auto" />
    <a-empty v-else-if="!tabs.length" description="没有可用的功能 Tab（请联系管理员授权）"
             style="margin-top:64px" />
    <template v-else>
      <a-tabs v-model:activeKey="active" class="quality-tabs" @change="onTab">
        <a-tab-pane v-for="t in tabs" :key="t.key" :tab="t.name" />
      </a-tabs>

      <div class="quality-body">
        <keep-alive>
          <component :is="current" />
        </keep-alive>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ensureAuth } from '../utils/auth'

const EvaluationPage = defineAsyncComponent(() => import('./EvaluationPage.vue'))
const AgentEvalPage = defineAsyncComponent(() => import('./AgentEvalPage.vue'))
const DashboardPage = defineAsyncComponent(() => import('./DashboardPage.vue'))

/** tabKey → 组件。菜单表不知道组件的存在，这张表是数据与视图之间唯一的桥 */
const TAB_COMPONENTS = {
  eval: EvaluationPage,
  agent: AgentEvalPage,
  dashboard: DashboardPage
}

/** 从页签菜单的 path（/quality?view=xxx）解析组件映射 key */
const tabKeyOf = p => {
  try { return new URLSearchParams(String(p || '').split('?')[1] || '').get('view') } catch (e) { return null }
}

const route = useRoute()
const router = useRouter()
const loading = ref(true)
/** 可见 Tab 清单：来自 /auth/me 菜单树的 children（已按角色过滤 + sortOrder 排序） */
const tabs = ref([])

onMounted(async () => {
  const info = await ensureAuth()
  const host = (info?.menus || []).find(m => m && m.path === '/quality')
  tabs.value = ((host && host.children) || [])
    .filter(c => c && c.renderAs === 'tab')
    .map(c => ({ id: c.id, key: tabKeyOf(c.path), name: c.name }))
    .filter(t => t.key && TAB_COMPONENTS[t.key]) // 菜单表里有、代码里还没映射的 Tab 不渲染（升级半程态容错）
  loading.value = false
  syncFromRoute()
})

const active = ref('')
// URL 里的 view 被权限收回（不在可见集合）或非法时回落第一个可见 Tab，避免停在空白页
const pick = key => {
  const visible = tabs.value
  return visible.some(t => t.key === key) ? key : visible[0]?.key || ''
}
const current = computed(() => {
  const t = tabs.value.find(x => x.key === active.value) || tabs.value[0]
  return t ? TAB_COMPONENTS[t.key] : null
})
const syncFromRoute = () => { active.value = pick(String(route.query.view || '')) }
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
