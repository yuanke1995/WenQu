<template>
  <div class="ov">
    <button v-for="c in cards" :key="c.key" type="button" class="ov-card"
            :class="{ on: c.on, warn: c.warn }" @click="$emit('pick', c.key)">
      <span class="ov-v">{{ c.value }}</span>
      <span class="ov-l">{{ c.label }}</span>
      <span class="ov-d">{{ c.detail }}</span>
    </button>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  summary: { type: Object, default: null },
  active: { type: String, default: 'roles' }
})
defineEmits(['pick'])

const s = computed(() => props.summary || {})
const n = v => (v === null || v === undefined ? '—' : v)

const cards = computed(() => [
  {
    key: 'roles',
    label: '角色',
    value: n(s.value.roles),
    detail: s.value.roles === undefined ? '读取中' : `启用 ${n(s.value.activeRoles)} · 停用 ${(s.value.roles ?? 0) - (s.value.activeRoles ?? 0)}`,
    on: props.active === 'roles'
  },
  {
    key: 'permissions',
    label: '授权点',
    value: n(s.value.permissions),
    detail: '按业务域分组展示',
    on: false
  },
  {
    key: 'apis',
    label: '接口',
    value: n(s.value.apis),
    detail: s.value.apis === undefined ? '读取中' : `${n(s.value.apisWithoutPermission)} 未授权 · ${n(s.value.apisWithoutMenu)} 未挂菜单`,
    warn: Number(s.value.apisWithoutPermission || 0) > 0,
    on: props.active === 'apis'
  },
  {
    key: 'menus',
    label: '菜单',
    value: n(s.value.menus),
    detail: s.value.menus === undefined ? '读取中' : `${n(s.value.hiddenMenus)} 隐藏 · ${n(s.value.adminRoles)} 个管理员级`,
    on: props.active === 'menus'
  },
  {
    key: 'pending',
    label: '待处理',
    value: (Number(s.value.rolesWithoutMembers || 0) + Number(s.value.expiringGrants || 0)) || 0,
    detail: `${n(s.value.rolesWithoutMembers)} 空角色 · ${n(s.value.expiringGrants)} 项临期授权`,
    warn: Number(s.value.rolesWithoutMembers || 0) + Number(s.value.expiringGrants || 0) > 0
  }
])
</script>

<style scoped>
.ov { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); border: 1px solid var(--app-border); border-radius: var(--app-radius); overflow: hidden; background: var(--app-panel); }
.ov-card {
  display: flex; flex-direction: column; gap: 1px; align-items: flex-start; min-width: 0;
  padding: 10px 13px; border: 0; border-right: 1px solid var(--app-border);
  background: var(--app-panel); cursor: pointer; font-family: inherit; text-align: left;
  transition: background .12s;
}
.ov-card:last-child { border-right: 0; }
.ov-card:hover { background: var(--app-panel-2); }
.ov-card.on { background: var(--app-accent-weak); box-shadow: inset 0 -2px 0 var(--app-accent); }
.ov-v { font-size: 19px; line-height: 1.2; font-weight: 600; letter-spacing: -.01em; color: var(--app-text); font-variant-numeric: tabular-nums; }
.ov-card.on .ov-v { color: var(--app-accent); }
.ov-card.warn .ov-v { color: var(--app-warn); }
.ov-l { font-size: 11.5px; color: var(--app-text2); }
.ov-d { font-size: 10.5px; color: var(--app-text3); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 100%; }
@media (max-width: 1100px) {
  .ov { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .ov-card { border-bottom: 1px solid var(--app-border); }
}
@media (max-width: 700px) {
  .ov { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
