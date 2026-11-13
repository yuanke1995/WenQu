<template>
  <div class="pe">
    <a-spin :spinning="loading">
      <div v-if="loadFailed" class="pe-error">
        <div>
          <b>权限数据读取失败</b>
          <span>{{ loadFailed }}</span>
        </div>
        <button class="app-btn ghost" type="button" @click="load">重试</button>
      </div>

      <template v-else>
        <div class="pe-bar">
          <a-input v-model:value="search" allow-clear size="small" class="pe-search"
                   placeholder="搜索菜单名称 / 授权点中文名 / 编码">
            <template #prefix><search-outlined class="pe-search-ic" /></template>
          </a-input>
          <button class="pe-chip" :class="{ on: filter === 'all' }" @click="filter = 'all'">全部 {{ catalog.length }}</button>
          <button class="pe-chip" :class="{ on: filter === 'allow' }" @click="filter = 'allow'">已允许 {{ stats.allow }}</button>
          <button class="pe-chip" :class="{ on: filter === 'deny' }" @click="filter = 'deny'">已禁止 {{ stats.deny }}</button>
          <button class="pe-chip" :class="{ on: filter === 'none' }" @click="filter = 'none'">
            未授予 {{ catalog.length - stats.allow - stats.deny }}
          </button>
          <span class="pe-spacer"></span>
          <button class="pe-template" type="button" title="按「只读管理员」预设一键套用：全部菜单可见，只读授权点放行，写类动作不授予（管理员级改为全部禁止）"
                  @click="confirmReadonlyTemplate">套用「只读管理员」模板</button>
          <span v-if="role && role.adminFlag === 1" class="app-pill violet">管理员级：全部动作</span>
        </div>

        <div class="pe-body" :class="{ two: twoColumn }">
          <section class="pe-menus">
            <div class="pe-sec">
              <b>可见菜单</b>
              <span class="pe-cnt">{{ checkedVisibleCount }} / {{ visibleMenus.length }}</span>
              <span class="pe-spacer"></span>
              <button class="pe-link" type="button" @click="selectAllMenus">全选</button>
              <button class="pe-link" type="button" @click="clearVisibleMenus">清空</button>
            </div>
            <a-tree v-if="menuTreeData.length" class="pe-tree" checkable default-expand-all
                    :selectable="false" :check-strictly="true"
                    :checked-keys="checkedMenuIds" :tree-data="menuTreeData" @check="onMenuCheck">
              <template #title="node">
                <span class="pe-mtitle">
                  <span>{{ node.title }}</span>
                  <span v-if="node.renderAs === 'tab'" class="pe-rpill tab">Tab</span>
                  <span v-else-if="node.renderAs === 'group'" class="pe-rpill group">分组</span>
                  <span v-else-if="node.renderAs === 'hidden'" class="pe-rpill hidden">仅权限</span>
                </span>
              </template>
            </a-tree>
            <div v-else class="pe-empty">没有匹配的菜单</div>
          </section>

          <section class="pe-perms">
            <div class="pe-sec">
              <b>功能授权点</b>
              <span class="pe-cnt">{{ stats.allow + stats.deny }} / {{ catalog.length }}</span>
              <span class="pe-spacer"></span>
              <span class="pe-hint">按业务域分组</span>
            </div>

            <div v-for="g in filteredGroups" :key="g.key" class="pe-group">
              <div class="pe-ghead">
                <b>{{ g.label }}</b>
                <span class="pe-cnt">{{ g.granted }} / {{ g.ownedCount }}</span>
                <span v-if="g.denied" class="pe-deny">禁止 {{ g.denied }}</span>
                <span class="pe-spacer"></span>
                <button class="pe-link" type="button" @click="setGroup(g, 'allow')">全选允许</button>
                <button class="pe-link" type="button" @click="setGroup(g, 'deny')">全部禁止</button>
                <button class="pe-link" type="button" @click="setGroup(g, 'none')">清空</button>
              </div>
              <div v-for="it in g.items" :key="it.code" class="pe-row">
                <div class="pe-nm">
                  <b>{{ it.title }}</b>
                  <code v-if="it.title !== it.code">{{ it.code }}</code>
                </div>
                <permission-tristate :model-value="permState[it.code] || 'none'"
                                     :exempt="exemptMap[it.code] || 0"
                                     @update:model-value="v => setPerm(it.code, v)"
                                     @update:exempt="v => setExempt(it.code, v)" />
              </div>
            </div>
            <div v-if="!filteredGroups.length" class="pe-empty">没有匹配的授权点</div>
          </section>
        </div>
      </template>
    </a-spin>

    <div class="pe-foot">
      <span v-if="dirty" class="pe-dirty"><i></i>{{ dirty }} 项未保存</span>
      <button v-if="dirty" class="pe-link" type="button" @click="undo">撤销</button>
      <span class="pe-spacer"></span>
      <button v-if="showCancel" class="app-btn ghost" type="button" @click="$emit('cancel')">取消</button>
      <button class="app-btn" type="button" :disabled="!dirty || saving || !!loadFailed" @click="save">
        {{ saving ? '保存中…' : '保存权限' }}
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { SearchOutlined } from '@ant-design/icons-vue'
import { getRolePermissions, saveRolePermissionPoints } from '../../api'
import { buildMenuTree, buildPermissionGroups, menuSubtreeIds, permissionStats, permissionTitle } from '../../utils/permissions'
import PermissionTristate from './PermissionTristate.vue'

const props = defineProps({
  role: { type: Object, default: null },
  menus: { type: Array, default: () => [] },
  catalog: { type: Array, default: () => [] },
  twoColumn: { type: Boolean, default: false },
  showCancel: { type: Boolean, default: true }
})
const emit = defineEmits(['saved', 'cancel', 'dirty-change'])

const loading = ref(false)
const saving = ref(false)
const loadFailed = ref('')
const search = ref('')
const filter = ref('all')
const checkedMenuIds = ref([])
const permState = ref({})
const exemptMap = ref({})
const baseline = ref({ menuIds: [], perms: {}, exempt: {} })

const stats = computed(() => permissionStats(permState.value))

// 隐藏菜单（visible=0）不进「可见菜单」树；其绑定仍留在 checkedMenuIds，保存时不丢
const visibleMenus = computed(() => (props.menus || []).filter(m => m.visible !== 0))
const visibleIdSet = computed(() => new Set(visibleMenus.value.map(m => m.id)))
const checkedVisibleCount = computed(() => checkedMenuIds.value.filter(id => visibleIdSet.value.has(id)).length)

function toTreeData (list) {
  return (list || []).map(m => ({
    key: m.id,
    title: m.name,
    renderAs: m.renderAs || 'sidebar',
    children: m.children ? toTreeData(m.children) : undefined
  }))
}

const menuTreeData = computed(() => {
  const tree = buildMenuTree(visibleMenus.value)
  const k = search.value.trim().toLowerCase()
  if (!k) return toTreeData(tree)
  const hit = n => String(n.name || '').toLowerCase().includes(k) || String(n.path || '').toLowerCase().includes(k)
  const prune = list => list.map(n => {
    if (hit(n)) return { ...n, children: n.children }
    const children = prune(n.children || [])
    return children.length ? { ...n, children } : null
  }).filter(Boolean)
  return toTreeData(prune(tree))
})

const groups = computed(() => buildPermissionGroups(props.catalog).map(g => {
  const items = g.items.map(p => ({
    code: p.code,
    title: permissionTitle(p)
  }))
  return {
    ...g,
    items,
    granted: items.filter(i => permState.value[i.code] === 'allow').length,
    denied: items.filter(i => permState.value[i.code] === 'deny').length
  }
}))

const filteredGroups = computed(() => {
  const k = search.value.trim().toLowerCase()
  return groups.value.map(g => {
    let items = g.items
    if (filter.value !== 'all') items = items.filter(i => (permState.value[i.code] || 'none') === filter.value)
    if (k) {
      items = items.filter(i =>
        i.title.toLowerCase().includes(k) || i.code.toLowerCase().includes(k) || g.label.toLowerCase().includes(k))
    }
    if (!items.length) return null
    return {
      ...g,
      items,
      granted: items.filter(i => permState.value[i.code] === 'allow').length,
      denied: items.filter(i => permState.value[i.code] === 'deny').length
    }
  }).filter(Boolean)
})

const dirty = computed(() => {
  const b = baseline.value
  let n = 0
  const baseMenus = new Set(b.menuIds || [])
  const curMenus = new Set(checkedMenuIds.value)
  for (const id of baseMenus) if (!curMenus.has(id)) n++
  for (const id of curMenus) if (!baseMenus.has(id)) n++
  const keys = new Set([...Object.keys(b.perms || {}), ...Object.keys(permState.value)])
  for (const code of keys) {
    const be = (b.perms || {})[code] || 'none'
    const ce = permState.value[code] || 'none'
    if (be !== ce) { n++; continue }
    if (ce === 'deny' && ((b.exempt || {})[code] || 0) !== (exemptMap.value[code] || 0)) n++
  }
  return n
})

watch(dirty, v => emit('dirty-change', v))

function snapshot () {
  const perms = {}
  for (const code of Object.keys(permState.value).sort()) perms[code] = permState.value[code]
  return {
    menuIds: [...checkedMenuIds.value].sort(),
    perms,
    exempt: { ...exemptMap.value }
  }
}

async function load () {
  if (!props.role || !props.role.code || !props.menus.length || !props.catalog.length) return
  loading.value = true
  loadFailed.value = ''
  try {
    const r = await getRolePermissions(props.role.code)
    if (r && r.success === false) throw new Error(r.msg || '加载角色权限失败')
    const d = (r && r.data) || {}
    const validMenus = new Set(props.menus.map(m => m.id))
    const validPerms = new Set(props.catalog.map(p => p.code))
    checkedMenuIds.value = (d.menuIds || []).filter(id => validMenus.has(id))
    const state = {}
    for (const code of (d.permissions || [])) {
      if (validPerms.has(code)) state[code] = 'allow'
    }
    const exempt = {}
    for (const row of (d.denies || [])) {
      if (!row || !validPerms.has(row.permissionCode)) continue
      state[row.permissionCode] = 'deny'
      exempt[row.permissionCode] = Number(row.exempt) === 1 ? 1 : 0
    }
    permState.value = state
    exemptMap.value = exempt
    baseline.value = snapshot()
  } catch (e) {
    loadFailed.value = e.message || '加载角色权限失败'
  } finally {
    loading.value = false
  }
}

watch(() => [props.role && props.role.code, props.menus.length, props.catalog.length], load, { immediate: true })

function selectAllMenus () {
  checkedMenuIds.value = [...new Set([...checkedMenuIds.value, ...visibleMenus.value.map(m => m.id)])]
}

function clearVisibleMenus () {
  checkedMenuIds.value = checkedMenuIds.value.filter(id => !visibleIdSet.value.has(id))
}

function onMenuCheck (_keys, e) {
  const on = !!e?.checked
  const subtree = menuSubtreeIds(visibleMenus.value, e?.node?.key)
  if (on) checkedMenuIds.value = [...new Set([...checkedMenuIds.value, ...subtree])]
  else checkedMenuIds.value = checkedMenuIds.value.filter(id => !subtree.has(id))
}

function setPerm (code, value) {
  const next = { ...permState.value }
  const ex = { ...exemptMap.value }
  if (value === 'none') {
    delete next[code]
    delete ex[code]
  } else {
    next[code] = value
    if (value === 'allow') delete ex[code]
    else if (!(code in ex)) ex[code] = 0
  }
  permState.value = next
  exemptMap.value = ex
}

function setExempt (code, value) {
  exemptMap.value = { ...exemptMap.value, [code]: Number(value) === 1 ? 1 : 0 }
}

function setGroup (group, value) {
  const next = { ...permState.value }
  const ex = { ...exemptMap.value }
  for (const it of group.items) {
    if (value === 'none') {
      delete next[it.code]
      delete ex[it.code]
    } else {
      next[it.code] = value
      if (value === 'allow') delete ex[it.code]
      else if (!(it.code in ex)) ex[it.code] = 0
    }
  }
  permState.value = next
  exemptMap.value = ex
}

function undo () {
  checkedMenuIds.value = [...baseline.value.menuIds]
  permState.value = { ...baseline.value.perms }
  exemptMap.value = { ...baseline.value.exempt }
}

/* ---------- 预设模板：只读管理员 ----------
 * 只读/写类的判据来自后端目录的 readonly 字段（该授权点名下端点是否全为 GET/ALL，或 self:*），
 * 与 RbacSeedRunner 建「只读管理员」预设的口径同源，前端不另写一套猜测规则。 */
const hasReadonlyFlag = computed(() => (props.catalog || []).some(p => 'readonly' in p))

function confirmReadonlyTemplate () {
  if (!props.role) return
  if (!hasReadonlyFlag.value) {
    message.warning('授权点目录没有返回 readonly 标记，请重启后端后刷新重试')
    return
  }
  const isAdmin = props.role.adminFlag === 1
  const readCount = props.catalog.filter(p => Number(p.readonly) === 1).length
  const writeCount = props.catalog.length - readCount
  const body = isAdmin
    ? `管理员级默认允许全部动作，这里把 ${writeCount} 个写类授权点设为「禁止」（压过全员），只保留 ${readCount} 个只读动作。`
    : `允许 ${readCount} 个只读授权点，其余 ${writeCount} 个写类动作保持未授予（不额外禁止，避免压掉本角色以外的授权）。`
  Modal.confirm({
    title: '套用「只读管理员」模板？',
    content: `${body}可见菜单设为全部 ${visibleMenus.value.length} 个。套用后仍可逐项调整，点「保存权限」才生效。`,
    okText: '套用',
    cancelText: '取消',
    onOk: applyReadonlyTemplate
  })
}

function applyReadonlyTemplate () {
  const isAdmin = props.role.adminFlag === 1
  const next = {}
  const ex = {}
  for (const p of props.catalog) {
    const read = Number(p.readonly) === 1
    if (isAdmin) {
      if (!read) { next[p.code] = 'deny'; ex[p.code] = 0 }
    } else if (read) {
      next[p.code] = 'allow'
    }
  }
  permState.value = next
  exemptMap.value = ex
  checkedMenuIds.value = [...new Set([
    ...checkedMenuIds.value.filter(id => !visibleIdSet.value.has(id)),
    ...visibleMenus.value.map(m => m.id)
  ])]
  message.success('已套用「只读管理员」模板，点「保存权限」生效')
}

async function save () {
  if (!dirty.value || saving.value || loadFailed.value || !props.role) return
  saving.value = true
  try {
    const permissions = Object.entries(permState.value).filter(([, v]) => v === 'allow').map(([c]) => c)
    const denies = Object.entries(permState.value)
      .filter(([, v]) => v === 'deny')
      .map(([c]) => ({ permissionCode: c, exempt: (exemptMap.value[c] || 0) === 1 ? 1 : 0 }))
    const r = await saveRolePermissionPoints(props.role.code, checkedMenuIds.value, permissions, denies)
    if (r && r.success === false) throw new Error(r.msg || '保存失败')
    baseline.value = snapshot()
    message.success(`已保存：菜单 ${checkedVisibleCount.value} · 允许 ${permissions.length} · 禁止 ${denies.length}`)
    emit('saved')
  } catch (e) {
    message.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

defineExpose({
  hasDirty: () => dirty.value > 0,
  undo,
  reload: load
})
</script>

<style scoped>
.pe { display: flex; flex-direction: column; min-height: 0; flex: 1; }
.pe-bar { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; padding: 0 0 8px; }
.pe-search { width: 260px; }
.pe-search-ic { color: var(--app-text3); }
.pe-chip {
  height: 24px; padding: 0 9px; border: 1px solid var(--app-border); border-radius: 999px;
  background: var(--app-panel); color: var(--app-text2); font-size: 11px; cursor: pointer; font-family: inherit;
}
.pe-chip.on { background: var(--app-accent-weak); border-color: var(--app-accent-border); color: var(--app-accent); font-weight: 600; }
.pe-spacer { flex: 1; }
.pe-body { display: flex; flex-direction: column; gap: 14px; min-height: 0; }
.pe-body.two { display: grid; grid-template-columns: minmax(0, .92fr) minmax(0, 1.2fr); gap: 16px; align-items: start; }
.pe-menus, .pe-perms { min-width: 0; }
.pe-perms { max-height: 540px; overflow-y: auto; padding-right: 4px; }
.pe-sec { display: flex; align-items: center; gap: 8px; margin: 0 0 6px; font-size: 12px; color: var(--app-text2); }
.pe-sec b { color: var(--app-text); font-size: 12.5px; }
.pe-cnt { font-size: 10.5px; color: var(--app-text3); background: var(--app-panel-2); border-radius: 999px; padding: 1px 6px; }
.pe-deny { font-size: 10.5px; color: var(--app-danger); }
.pe-hint { font-size: 10.5px; color: var(--app-text3); }
.pe-template {
  height: 24px; padding: 0 9px; border: 1px solid var(--app-border); border-radius: 999px;
  background: var(--app-panel); color: var(--app-text2); font-size: 11px;
  cursor: pointer; font-family: inherit; white-space: nowrap;
}
.pe-template:hover { border-color: var(--app-accent-border); color: var(--app-accent); background: var(--app-accent-weak); }
.pe-link { border: 0; background: transparent; color: var(--app-accent); font-size: 11px; cursor: pointer; font-family: inherit; padding: 0 2px; }
.pe-link:hover { text-decoration: underline; }
.pe-tree { font-size: 12px; }
.pe-mtitle { display: inline-flex; align-items: center; gap: 6px; }
.pe-rpill { font-size: 9px; line-height: 16px; padding: 0 5px; border-radius: 4px; background: var(--app-panel-2); color: var(--app-text3); }
.pe-rpill.tab { background: var(--violet-weak, #f1eafd); color: #7c3aed; }
.pe-rpill.hidden { background: var(--app-panel-2); color: var(--app-text3); }
.pe-group { border: 1px solid var(--app-border); border-radius: 8px; margin-bottom: 8px; overflow: hidden; }
.pe-ghead {
  display: flex; align-items: center; gap: 7px; padding: 6px 10px;
  background: var(--app-panel-2); border-bottom: 1px solid var(--app-border); font-size: 11px;
}
.pe-ghead b { font-size: 11.5px; }
.pe-row { display: flex; align-items: center; gap: 10px; padding: 5px 10px; border-top: 1px solid var(--app-border); }
.pe-row:first-of-type { border-top: 0; }
.pe-nm { min-width: 0; flex: 1; }
.pe-nm b { display: block; font-size: 11.5px; font-weight: 600; line-height: 1.45; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pe-nm code { font-size: 9.5px; color: var(--app-text3); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.pe-empty { padding: 20px 0; text-align: center; font-size: 11.5px; color: var(--app-text3); }
.pe-error {
  display: flex; align-items: center; gap: 10px; padding: 10px 12px; margin-bottom: 8px;
  border: 1px solid var(--app-danger-border); background: var(--app-danger-weak); border-radius: 8px;
}
.pe-error b { display: block; font-size: 12px; color: var(--app-danger-text); }
.pe-error span { display: block; font-size: 11px; color: var(--app-text2); margin-top: 2px; }
.pe-error .app-btn { margin-left: auto; flex: none; }
.pe-foot {
  display: flex; align-items: center; gap: 10px; padding: 9px 0 0; margin-top: 8px;
  border-top: 1px solid var(--app-border); flex: none;
}
.pe-dirty { display: inline-flex; align-items: center; gap: 5px; font-size: 11.5px; color: var(--app-warn-text); }
.pe-dirty i { width: 6px; height: 6px; border-radius: 50%; background: var(--app-warn); }
@media (max-width: 900px) {
  .pe-body.two { grid-template-columns: 1fr; }
  .pe-search { width: 100%; }
}
</style>
