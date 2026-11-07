<template>
  <a-modal :open="open" title="有效权限查询" :width="640" :footer="null"
           @cancel="$emit('update:open', false)">
    <p class="ep-tip">
      给定账号，算出实际可用的授权点（各角色允许并集 − 全局禁止）。排查「为什么进不去 / 为什么进得来」从这里入手。
    </p>
    <a-input-search v-model:value="uid" placeholder="输入登录账号（uid）" enter-button="计算"
                    :loading="loading" @search="query" />
    <div v-if="error" class="ep-error">{{ error }}</div>

    <template v-if="result">
      <div class="ep-sum">
        <b>{{ result.uid }}</b>
        <span class="ep-sep">·</span>
        <span class="ep-roles">
          <span v-for="r in result.roles || []" :key="r" class="app-pill blue">{{ r }}</span>
        </span>
      </div>

      <div v-if="isAdmin" class="ep-admin">
        管理员级：全部动作{{ denyCodes.length ? `（已禁止 ${denyCodes.length} 项）` : '（未被禁止任何动作）' }}
      </div>

      <div class="ep-scroll">
        <template v-if="!isAdmin">
          <div v-for="g in allowGroups" :key="g.key" class="ep-group">
            <div class="ep-ghead"><b>{{ g.label }}</b><span>{{ g.items.length }}</span></div>
            <div v-for="it in g.items" :key="it.code" class="ep-row">
              <code>{{ it.code }}</code>
              <span class="ep-name">{{ it.title }}</span>
              <span v-if="it.roles.length" class="ep-src">{{ it.roles.join('、') }}</span>
            </div>
          </div>
          <div v-if="!allowGroups.length" class="ep-empty">该账号当前没有任何可用授权点</div>
        </template>

        <div v-if="denyCodes.length" class="ep-group deny">
          <div class="ep-ghead"><b>被禁止</b><span>{{ denyCodes.length }}</span></div>
          <div v-for="d in denyItems" :key="d.code" class="ep-row">
            <code>{{ d.code }}</code>
            <span class="ep-name">{{ d.title }}</span>
            <span class="ep-src">{{ d.roles.join('、') }}</span>
          </div>
        </div>
        <div v-else-if="isAdmin" class="ep-empty">没有被任何角色禁止的动作</div>
      </div>

      <div class="ep-foot">
        <button class="app-btn ghost" type="button" @click="copy">复制清单</button>
        <button class="app-btn ghost" type="button" @click="exportJson">导出 JSON</button>
      </div>
    </template>
  </a-modal>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { getEffectivePermissions, getRolePermissions } from '../../api'
import { domainKey, domainLabel, permissionTitle } from '../../utils/permissions'

const props = defineProps({
  open: { type: Boolean, default: false },
  initialUid: { type: String, default: '' },
  catalog: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:open'])

const uid = ref('')
const loading = ref(false)
const error = ref('')
const result = ref(null)
const sources = ref({})

const isAdmin = computed(() => (result.value?.permissions || []).some(p => String(p).includes('all-allow')))
const denyCodes = computed(() => (result.value?.globalDenies || []))
const catalogMap = computed(() => new Map((props.catalog || []).map(p => [p.code, p])))

function titleOf (code) {
  return permissionTitle(catalogMap.value.get(code) || { code })
}

function roleSourceOf (code) {
  return (sources.value[code] || [])
}

function groupCodes (codes) {
  const by = new Map()
  for (const code of codes) {
    const key = domainKey(code)
    if (!by.has(key)) by.set(key, [])
    by.get(key).push(code)
  }
  return [...by.entries()]
    .map(([key, list]) => ({
      key,
      label: domainLabel(key),
      items: list.slice().sort((a, b) => a.localeCompare(b)).map(code => ({
        code,
        title: titleOf(code),
        roles: roleSourceOf(code)
      }))
    }))
    .sort((a, b) => a.label.localeCompare(b.label, 'zh'))
}

const allowGroups = computed(() => {
  if (!result.value || isAdmin.value) return []
  return groupCodes(result.value.permissions || [])
})

const denyItems = computed(() => {
  if (!result.value) return []
  return groupCodes(denyCodes.value).flatMap(g => g.items)
})

async function loadSources (roles, codes) {
  const map = {}
  await Promise.all((roles || []).map(async code => {
    try {
      const r = await getRolePermissions(code)
      for (const perm of ((r && r.data && r.data.permissions) || [])) {
        if (!map[perm]) map[perm] = []
        if (!map[perm].includes(code)) map[perm].push(code)
      }
    } catch (e) { /* 单个角色取数失败不影响有效权限结论 */ }
  }))
  sources.value = map
}

async function query () {
  const id = String(uid.value || '').trim()
  if (!id) { message.warning('请输入登录账号'); return }
  loading.value = true
  error.value = ''
  result.value = null
  try {
    const r = await getEffectivePermissions(id)
    if (r && r.success === false) throw new Error(r.msg || '查询失败')
    result.value = r.data || null
    await loadSources(result.value?.roles || [], result.value?.permissions || [])
  } catch (e) {
    error.value = e.message || '查询失败'
  } finally {
    loading.value = false
  }
}

watch(() => props.open, v => {
  if (!v) return
  uid.value = props.initialUid || ''
  error.value = ''
  result.value = null
  sources.value = {}
  if (uid.value) query()
})

function copy () {
  if (!result.value) return
  const lines = (result.value.permissions || []).map(p => `${p}\t${titleOf(p)}\t${roleSourceOf(p).join('、')}`)
  const denies = denyCodes.value.map(p => `${p}\t${titleOf(p)}\t禁止`)
  const text = [`账号：${result.value.uid}`, `角色：${(result.value.roles || []).join('、')}`, ...lines, ...denies].join('\n')
  navigator.clipboard?.writeText(text).then(() => message.success('已复制'), () => message.error('复制失败'))
}

function exportJson () {
  if (!result.value) return
  const blob = new Blob([JSON.stringify({
    uid: result.value.uid,
    roles: result.value.roles || [],
    permissions: result.value.permissions || [],
    globalDenies: denyCodes.value,
    sources: sources.value
  }, null, 2)], { type: 'application/json' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `effective-permissions-${result.value.uid}.json`
  a.click()
  URL.revokeObjectURL(a.href)
}
</script>

<style scoped>
.ep-tip { margin: 0 0 10px; font-size: 12px; line-height: 1.6; color: var(--app-text3); }
.ep-error { margin-top: 8px; font-size: 12px; color: var(--app-danger); }
.ep-sum { display: flex; align-items: center; gap: 8px; margin: 12px 0 8px; font-size: 12.5px; }
.ep-sep { color: var(--app-text3); }
.ep-roles { display: inline-flex; flex-wrap: wrap; gap: 4px; }
.ep-admin { padding: 8px 10px; margin-bottom: 8px; border-radius: 8px; background: var(--violet-weak, #f1eafd); color: #7c3aed; font-size: 12px; }
.ep-scroll { max-height: 420px; overflow-y: auto; padding-right: 4px; }
.ep-group { border: 1px solid var(--app-border); border-radius: 8px; margin-bottom: 8px; overflow: hidden; }
.ep-group.deny { border-color: var(--app-danger-border); }
.ep-ghead { display: flex; align-items: center; gap: 8px; padding: 6px 10px; background: var(--app-panel-2); border-bottom: 1px solid var(--app-border); font-size: 11.5px; }
.ep-ghead b { font-size: 12px; }
.ep-ghead span { font-size: 10.5px; color: var(--app-text3); }
.ep-group.deny .ep-ghead b { color: var(--app-danger); }
.ep-row { display: flex; align-items: center; gap: 10px; padding: 5px 10px; border-top: 1px solid var(--app-border); font-size: 11.5px; }
.ep-row:first-of-type { border-top: 0; }
.ep-row code { flex: none; font-size: 10px; color: var(--app-text3); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.ep-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ep-src { flex: none; font-size: 10px; color: var(--app-text3); }
.ep-empty { padding: 18px 0; text-align: center; font-size: 12px; color: var(--app-text3); }
.ep-foot { display: flex; gap: 8px; margin-top: 10px; }
</style>
