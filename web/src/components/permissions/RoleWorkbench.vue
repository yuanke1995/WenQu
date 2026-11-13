<template>
  <div class="rw">
    <aside class="rw-rail">
      <div class="rw-rail-head">
        <a-input v-model:value="keyword" allow-clear size="small" placeholder="搜索角色名称 / 编码">
          <template #prefix><search-outlined /></template>
        </a-input>
      </div>
      <div class="rw-chips">
        <button class="rw-chip" :class="{ on: filter === 'all' }" @click="filter = 'all'">全部 {{ roles.length }}</button>
        <button class="rw-chip" :class="{ on: filter === 'active' }" @click="filter = 'active'">启用 {{ activeCount }}</button>
        <button class="rw-chip warn" :class="{ on: filter === 'empty' }" @click="filter = 'empty'">空角色 {{ emptyCount }}</button>
      </div>

      <div class="rw-cards">
        <button v-for="r in filteredRoles" :key="r.code" type="button" class="rw-card"
                :class="{ on: r.code === activeCode }" @click="selectRole(r)">
          <span class="rw-card-top">
            <span class="rw-name">{{ r.name }}</span>
            <span v-if="r.builtin === 1" class="app-pill blue">内置</span>
            <span v-if="r.adminFlag === 1" class="app-pill violet">管理员级</span>
          </span>
          <span class="rw-card-meta">
            <code>{{ r.code }}</code>
            <span>{{ statOf(r.code).memberCount ?? '—' }} 成员</span>
            <span>{{ statOf(r.code).menuCount ?? '—' }} 菜单</span>
            <span class="rw-st">
              <span class="app-pill" :class="r.status === 0 ? 'muted' : 'ok'">{{ r.status === 0 ? '停用' : '启用' }}</span>
            </span>
          </span>
        </button>
        <div v-if="!filteredRoles.length" class="rw-empty-rail">没有匹配的角色</div>
      </div>
    </aside>

    <section v-if="activeRole" class="rw-main">
      <div class="rw-head">
        <div class="rw-head-top">
          <h3>{{ activeRole.name }}</h3>
          <code>{{ activeRole.code }}</code>
          <span v-if="activeRole.builtin === 1" class="app-pill blue">内置</span>
          <span v-if="activeRole.adminFlag === 1" class="app-pill violet">管理员级</span>
          <span class="app-pill" :class="activeRole.status === 0 ? 'muted' : 'ok'">
            {{ activeRole.status === 0 ? '停用' : '启用' }}
          </span>
          <span class="rw-spacer"></span>
          <button class="app-btn ghost" type="button" @click="$emit('edit-role', activeRole)">编辑</button>
          <button class="app-btn ghost" type="button" @click="$emit('clone-role', activeRole)">复制</button>
          <button class="app-btn ghost" type="button" @click="$emit('effective', '')">有效权限</button>
          <a-dropdown>
            <button class="app-btn ghost" type="button">更多</button>
            <template #overlay>
              <a-menu>
                <a-menu-item @click="$emit('delete-role', activeRole)">删除角色</a-menu-item>
                <a-menu-item @click="$emit('open-members')">去成员管理</a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </div>
        <div class="rw-head-sub">
          <span>成员 <b>{{ statOf(activeRole.code).memberCount ?? '—' }}</b></span>
          <span>可见菜单 <b>{{ statOf(activeRole.code).menuCount ?? '—' }} / {{ visibleMenuCount }}</b></span>
          <span>允许 <b>{{ activeRole.adminFlag === 1 ? '全部' : (statOf(activeRole.code).permissionCount ?? '—') }}</b></span>
          <span>禁止 <b>{{ statOf(activeRole.code).denyCount ?? 0 }}</b></span>
          <span>数据范围 <b>{{ scopeText }}</b></span>
        </div>
      </div>

      <div class="rw-segs">
        <button class="rw-seg" :class="{ on: tab === 'permissions' }" @click="tab = 'permissions'">权限配置</button>
        <button class="rw-seg" :class="{ on: tab === 'members' }" @click="tab = 'members'">
          成员 <span>{{ statOf(activeRole.code).memberCount ?? 0 }}</span>
        </button>
        <button class="rw-seg" :class="{ on: tab === 'scope' }" @click="tab = 'scope'">数据范围</button>
        <button class="rw-seg" :class="{ on: tab === 'changes' }" @click="tab = 'changes'">变更记录</button>
      </div>

      <div class="rw-body">
        <permission-editor v-if="tab === 'permissions'" ref="editorRef"
                           :role="activeRole" :menus="menus" :catalog="catalog"
                           two-column :show-cancel="false" @saved="onSaved" />

        <role-members-panel v-else-if="tab === 'members'" :role-code="activeRole.code"
                            @effective="uid => $emit('effective', uid)" @open-members="$emit('open-members')" />
        <div v-else-if="tab === 'scope'" class="rw-scope">
          <div class="rw-sec"><b>数据范围</b><button class="app-link-btn" @click="$emit('edit-role', activeRole)">修改</button></div>
          <div class="app-card" style="padding:12px 14px">
            <span class="app-pill blue">{{ scopeText }}</span>
            <p class="rw-tip">范围只授予查看权；生效部门：{{ scope.departmentId || '成员自己的部门' }}。</p>
          </div>
        </div>

        <div v-else class="rw-changes">
          <div class="rw-sec">
            <b>最近变更</b>
            <span class="rw-count">{{ changes.length }} 条</span>
            <span class="rw-spacer"></span>
            <button class="app-link-btn" @click="loadChanges">刷新</button>
            <button class="app-link-btn" @click="openAudit">在审计事件中查看全部</button>
          </div>
          <div v-if="changesError" class="rw-error">{{ changesError }}</div>
          <div v-else-if="!changes.length" class="rw-empty-rail">暂无变更记录（近 30 天）</div>
          <div v-else class="rw-timeline">
            <div v-for="c in changes" :key="c.id || (c.ts + c.action)" class="rw-tli">
              <i class="rw-dot" :class="{ danger: c.level === 'ERROR', warn: c.level === 'WARN' }"></i>
              <div class="rw-tlc">
                <b>{{ c.action || '权限变更' }}</b>
                <span class="rw-meta">{{ fmtTs(c.ts) }} · {{ c.actor_uid || '—' }}</span>
                <span v-if="c.detail" class="rw-detail">{{ shortDetail(c.detail) }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <section v-else class="rw-main rw-none">
      <a-empty description="没有可管理的角色" />
    </section>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Modal } from 'ant-design-vue'
import { SearchOutlined } from '@ant-design/icons-vue'
import { getRoleDataScope, listAuditEvents } from '../../api'
import { scopeLabel } from '../../utils/permissions'
import PermissionEditor from './PermissionEditor.vue'
import RoleMembersPanel from './RoleMembersPanel.vue'

const props = defineProps({
  roles: { type: Array, default: () => [] },
  stats: { type: Array, default: () => [] },
  menus: { type: Array, default: () => [] },
  catalog: { type: Array, default: () => [] },
  initialCode: { type: String, default: '' }
})
const emit = defineEmits(['create-role', 'edit-role', 'clone-role', 'delete-role', 'effective', 'open-members', 'refresh', 'select'])

const keyword = ref('')
const filter = ref('all')
const activeCode = ref('')
const router = useRouter()
const tab = ref('permissions')
const editorRef = ref(null)
const scope = ref({ scopeType: 'own', departmentId: null })
const changes = ref([])
const changesError = ref('')

const statsByCode = computed(() => {
  const map = {}
  for (const s of (props.stats || [])) map[s.code] = s
  return map
})
const statOf = code => statsByCode.value[code] || {}
const visibleMenuCount = computed(() => (props.menus || []).filter(m => m.visible !== 0).length)
const activeRole = computed(() => props.roles.find(r => r.code === activeCode.value) || null)
const activeCount = computed(() => props.roles.filter(r => r.status !== 0).length)
const emptyCount = computed(() => props.roles.filter(r => r.builtin !== 1 && (statOf(r.code).memberCount ?? 0) === 0).length)
const scopeText = computed(() => scopeLabel(scope.value.scopeType))

const filteredRoles = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  return props.roles.filter(r => {
    if (k && !String(r.code || '').toLowerCase().includes(k) && !String(r.name || '').toLowerCase().includes(k)) return false
    if (filter.value === 'active' && r.status === 0) return false
    if (filter.value === 'empty' && !(r.builtin !== 1 && (statOf(r.code).memberCount ?? 0) === 0)) return false
    return true
  })
})

watch(() => props.roles.map(r => r.code).join(','), () => {
  const want = props.initialCode
  if (want && props.roles.some(r => r.code === want)) { activeCode.value = want; return }
  if (!activeCode.value && props.roles.length) activeCode.value = props.roles[0].code
  if (activeCode.value && !props.roles.some(r => r.code === activeCode.value)) activeCode.value = props.roles[0]?.code || ''
}, { immediate: true })

watch(() => props.initialCode, code => {
  if (code && code !== activeCode.value && props.roles.some(r => r.code === code)) activeCode.value = code
})

watch(activeCode, code => emit('select', code))

function selectRole (role) {
  if (!role || role.code === activeCode.value) return
  if (editorRef.value && editorRef.value.hasDirty && editorRef.value.hasDirty()) {
    Modal.confirm({
      title: '有未保存的权限改动',
      content: '切换角色会丢弃当前改动，继续？',
      okText: '丢弃并切换',
      cancelText: '留在当前角色',
      onOk: () => { activeCode.value = role.code }
    })
    return
  }
  activeCode.value = role.code
}

async function loadScope () {
  if (!activeRole.value) return
  try {
    const r = await getRoleDataScope(activeRole.value.code)
    scope.value = (r && r.data) || { scopeType: 'own', departmentId: null }
  } catch (e) {
    scope.value = { scopeType: 'own', departmentId: null }
  }
}

async function loadChanges () {
  if (!activeRole.value) return
  changesError.value = ''
  try {
    const params = new URLSearchParams({ days: 30, size: 30, keyword: activeRole.value.code }).toString()
    const r = await listAuditEvents(params)
    if (r && r.success === false) throw new Error(r.msg || '变更记录读取失败')
    changes.value = ((r && r.data && r.data.items) || [])
  } catch (e) {
    changesError.value = e.message || '变更记录读取失败'
    changes.value = []
  }
}

function onSaved () {
  emit('refresh')
  loadScope()
  loadChanges()
}

/**
 * 深链到「安全与运维 → 审计事件」：keyword 用角色编码，与后端审计文案里的 `code=xxx` 对得上；
 * 时间范围跟这里的「近 30 天」保持一致，避免点过去看到的是另一批数据。
 */
function openAudit () {
  if (!activeRole.value) return
  router.push({
    path: '/security',
    query: { tab: 'audit', keyword: activeRole.value.code, days: 30 }
  })
}

watch(activeCode, () => {
  tab.value = 'permissions'
  loadScope()
  loadChanges()
}, { immediate: true })

watch(() => props.roles.length, () => { if (!activeCode.value && props.roles.length) activeCode.value = props.roles[0].code })

function fmtTs (s) {
  return typeof s === 'string' ? s.replace('T', ' ').slice(0, 16) : (s || '—')
}

function shortDetail (detail) {
  const text = String(detail || '')
  return text.length > 70 ? text.slice(0, 70) + '…' : text
}
</script>

<style scoped>
.rw { display: grid; grid-template-columns: 268px minmax(0, 1fr); gap: 0; min-height: 560px; }
.rw-rail { border-right: 1px solid var(--app-border); background: var(--app-panel-2); padding: 12px 10px; min-width: 0; }
.rw-rail-head { margin-bottom: 8px; }
.rw-chips { display: flex; flex-wrap: wrap; gap: 4px; margin-bottom: 9px; }
.rw-chip {
  height: 23px; padding: 0 8px; border: 1px solid var(--app-border); border-radius: 999px;
  background: var(--app-panel); color: var(--app-text2); font-size: 11px; cursor: pointer; font-family: inherit;
}
.rw-chip.on { background: var(--app-accent-weak); border-color: var(--app-accent-border); color: var(--app-accent); font-weight: 600; }
.rw-chip.warn.on { background: var(--app-warn-weak); border-color: var(--app-warn-border); color: var(--app-warn-text); }
.rw-cards { display: flex; flex-direction: column; gap: 6px; }
.rw-card {
  display: flex; flex-direction: column; gap: 3px; width: 100%; text-align: left;
  padding: 8px 10px; border: 1px solid var(--app-border); border-radius: 8px;
  background: var(--app-panel); cursor: pointer; font-family: inherit;
}
.rw-card:hover { border-color: var(--app-accent-border); }
.rw-card.on { border-color: var(--app-accent-border); box-shadow: 0 0 0 2px var(--app-accent-weak); }
.rw-card-top { display: flex; align-items: center; gap: 5px; flex-wrap: wrap; }
.rw-name { font-size: 12.5px; font-weight: 600; color: var(--app-text); }
.rw-card-meta { display: flex; align-items: center; gap: 8px; font-size: 10px; color: var(--app-text3); min-width: 0; }
.rw-card-meta code { font-size: 9.5px; color: var(--app-text3); }
.rw-st { margin-left: auto; }
.rw-main { display: flex; flex-direction: column; min-width: 0; }
.rw-head { padding: 12px 16px 8px; border-bottom: 1px solid var(--app-border); }
.rw-head-top { display: flex; align-items: center; gap: 7px; flex-wrap: wrap; }
.rw-head-top h3 { margin: 0; font-size: 16px; font-weight: 600; }
.rw-head-top code { font-size: 10px; color: var(--app-text3); }
.rw-spacer { flex: 1; }
.rw-head-sub { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 5px; font-size: 11px; color: var(--app-text3); }
.rw-head-sub b { color: var(--app-text2); font-weight: 600; }
.rw-segs { display: flex; gap: 2px; border-bottom: 1px solid var(--app-border); padding: 0 16px; }
.rw-seg {
  border: 0; background: transparent; cursor: pointer; font-family: inherit;
  padding: 8px 10px; font-size: 12px; color: var(--app-text2); border-bottom: 2px solid transparent;
}
.rw-seg.on { color: var(--app-accent); border-bottom-color: var(--app-accent); font-weight: 600; }
.rw-seg span { font-size: 10px; color: var(--app-text3); margin-left: 4px; }
.rw-body { padding: 11px 16px 14px; display: flex; flex-direction: column; min-height: 0; }
.rw-scope { max-width: 620px; }
.rw-sec { display: flex; align-items: center; gap: 8px; margin: 0 0 7px; font-size: 12px; color: var(--app-text2); }
.rw-sec b { color: var(--app-text); }
.rw-count { font-size: 10.5px; color: var(--app-text3); }
.rw-tip { margin: 7px 0 0; font-size: 12px; line-height: 1.6; color: var(--app-text3); }
.rw-timeline { display: flex; flex-direction: column; gap: 10px; }
.rw-tli { display: flex; gap: 9px; }
.rw-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--app-accent); margin-top: 5px; flex: none; }
.rw-dot.danger { background: var(--app-danger); }
.rw-dot.warn { background: var(--app-warn); }
.rw-tlc { display: flex; flex-direction: column; gap: 1px; min-width: 0; }
.rw-tlc b { font-size: 11.5px; color: var(--app-text); }
.rw-meta { font-size: 10px; color: var(--app-text3); }
.rw-detail { font-size: 10.5px; color: var(--app-text3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rw-error { padding: 8px 10px; border-radius: 8px; background: var(--app-danger-weak); color: var(--app-danger-text); font-size: 11.5px; }
.rw-empty-rail { padding: 18px 0; text-align: center; font-size: 11.5px; color: var(--app-text3); }
.rw-none { align-items: center; justify-content: center; }
@media (max-width: 1000px) {
  .rw { grid-template-columns: 1fr; }
  .rw-rail { border-right: 0; border-bottom: 1px solid var(--app-border); }
  .rw-cards { flex-direction: row; flex-wrap: wrap; }
  .rw-card { width: calc(50% - 3px); }
}
</style>
