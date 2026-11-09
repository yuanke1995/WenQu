<template>
  <div v-if="visible" class="onb-card">
    <div class="onb-head">
      <div>
        <div class="onb-title">欢迎来到 {{ tenantName }}</div>
        <div class="onb-sub">完成这几步即可开始使用 · {{ completed }} / {{ total }}</div>
      </div>
      <button class="onb-dismiss" type="button" @click="dismiss">不再提示</button>
    </div>
    <div class="onb-grid">
      <div v-for="item in items" :key="item.key" class="onb-item" :class="{ done: item.done }">
        <span class="onb-dot">
          <svg v-if="item.done" viewBox="0 0 24 24" width="9" height="9" fill="none"
               stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
            <path d="m5 12 5 5 9-10" />
          </svg>
        </span>
        <span class="onb-text">{{ item.label }}</span>
        <button v-if="!item.done" class="onb-go" type="button" @click="go(item.to)">去完成</button>
        <span v-else class="onb-go done">已完成</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getTenantOnboarding } from '../api'
import { isAdminSync, tenantStateSync } from '../utils/auth'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const state = ref(null)
const hidden = ref(false)

const tenantState = computed(() => tenantStateSync() || {})
const tenantName = computed(() => tenantState.value.name || '你的租户')
const items = computed(() => {
  const it = (state.value && state.value.items) || {}
  return [
    { key: 'password', label: '修改初始密码', done: Boolean(it.password), to: '/profile?tab=security' },
    { key: 'department', label: '新建第一个部门', done: Boolean(it.department), to: '/members?tab=departments' },
    { key: 'member', label: '邀请第一位成员', done: Boolean(it.member), to: '/members' },
    { key: 'model', label: '绑定聊天模型', done: Boolean(it.model), to: '/agents?tab=providers' },
    { key: 'kb', label: '建第一个知识库', done: Boolean(it.kb), to: '/knowledge' }
  ]
})
const completed = computed(() => items.value.filter(i => i.done).length)
const total = computed(() => items.value.length)

const hiddenKey = computed(() => {
  const id = tenantState.value.id || 'default'
  return `onb_hidden_${id}`
})

const visible = computed(() => {
  const st = tenantState.value
  if (!st || !st.id || st.isDefault) return false
  if (!isAdminSync()) return false
  if (hidden.value) return false
  if (!state.value || !state.value.required) return false
  return !state.value.done
})

function dismiss () {
  hidden.value = true
  try { localStorage.setItem(hiddenKey.value, '1') } catch (e) { /* ignore */ }
}

function go (to) {
  router.push(to)
}

onMounted(async () => {
  try { hidden.value = Boolean(localStorage.getItem(hiddenKey.value)) } catch (e) { /* ignore */ }
  // 只在对话落地页展示，避免每个管理页顶部都顶一条；用户从对话开始用这个产品
  if (route.path !== '/chat') return
  loading.value = true
  try {
    const r = await getTenantOnboarding()
    state.value = (r && r.data) || null
  } catch (e) { state.value = null }
  finally { loading.value = false }
})
</script>

<style scoped>
.onb-card {
  margin: 12px 16px 0;
  padding: 14px 16px 12px;
  border-radius: 10px;
  background: var(--app-panel, #fff);
  border: 1px solid var(--app-border, #e6e9ed);
  box-shadow: 0 1px 2px rgba(16, 24, 40, .04);
}
.onb-head { display: flex; align-items: flex-start; gap: 12px; }
.onb-title { font-size: 14px; font-weight: 600; color: var(--app-text, #1f2329); }
.onb-sub { margin-top: 3px; font-size: 12px; color: var(--app-text-3, #98a0aa); }
.onb-dismiss {
  margin-left: auto; flex: none; border: none; background: transparent; padding: 0;
  font-size: 12px; color: var(--app-text-3, #98a0aa); cursor: pointer;
}
.onb-dismiss:hover { color: var(--app-accent, #2e6be6); }
.onb-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 4px 28px; margin-top: 10px; }
.onb-item { display: flex; align-items: center; gap: 8px; height: 30px; border-bottom: 1px solid #f1f3f5; }
.onb-item:nth-last-child(-n+2) { border-bottom: none; }
.onb-dot {
  width: 15px; height: 15px; border-radius: 50%; flex: none;
  border: 1px solid var(--app-border-strong, #d8dde3);
  display: inline-flex; align-items: center; justify-content: center;
  color: var(--app-ok, #3b8f4e);
}
.onb-item.done .onb-dot { background: var(--app-ok-weak, #eaf5ec); border-color: var(--app-ok, #3b8f4e); }
.onb-text { font-size: 12.5px; color: var(--app-text-2, #5f6570); min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.onb-item.done .onb-text { color: var(--app-text-3, #98a0aa); text-decoration: line-through; }
.onb-go {
  margin-left: auto; flex: none; border: none; background: transparent; padding: 0;
  font-size: 12px; color: var(--app-accent, #2e6be6); cursor: pointer;
}
.onb-go.done { color: var(--app-text-3, #98a0aa); cursor: default; }
@media (max-width: 1024px) {
  .onb-card { display: none; }
  .onb-grid { grid-template-columns: 1fr; }
}
</style>
