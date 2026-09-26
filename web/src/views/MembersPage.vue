<template>
  <div class="app-page">
    <div class="app-page-head">
      <h3 class="app-page-title">成员管理</h3>
      <span class="head-hint-plain">管理账号、部门与角色归属；角色能做什么在「权限管理」里配置</span>
    </div>

    <!-- 页签式 Tab：与「智能体工作台」同一形态。此处只承载导航，内容用下方共用卡片按 tab 渲染；
         页签状态落 URL(?tab=)，刷新/回退/分享可还原 -->
    <a-tabs v-model:active-key="tab" class="pg-tabs">
      <a-tab-pane key="users">
        <template #tab><span class="pg-tab">用户<i v-if="loaded.users" class="pg-badge">{{ users.length }}</i></span></template>
      </a-tab-pane>
      <a-tab-pane key="departments">
        <template #tab><span class="pg-tab">部门<i v-if="loaded.departments" class="pg-badge">{{ departments.length }}</i></span></template>
      </a-tab-pane>
    </a-tabs>

    <div class="app-page-body">
      <div class="app-card" style="padding:0;overflow:hidden">
        <!-- 工具栏：搜索 + 计数 + 新建 -->
        <div class="mem-toolbar">
          <a-input v-model:value="keyword" allow-clear size="small" class="mem-search"
                   :placeholder="tab === 'users' ? '搜索用户名 / 用户标识' : '搜索部门名称'">
            <template #prefix><search-outlined class="mem-search-ic" /></template>
          </a-input>
          <a-select v-if="tab === 'users'" v-model:value="deptFilter" allow-clear size="small" class="mem-filter"
                    placeholder="全部部门" :options="deptFilterOptions" />
          <a-select v-if="tab === 'users'" v-model:value="roleFilter" allow-clear size="small" class="mem-filter"
                    placeholder="全部角色" :options="roleFilterOptions" />
          <span class="mem-count">{{ tab === 'users' ? filteredUsers.length + ' 名用户' : filteredDepts.length + ' 个部门' }}</span>
          <button v-if="tab === 'users'" class="app-btn mem-new" @click="openUserCreate"><plus-outlined /> 新建用户</button>
          <button v-else class="app-btn mem-new" @click="openDeptCreate"><plus-outlined /> 新建部门</button>
        </div>

        <a-spin :spinning="loading">
          <!-- 用户 -->
          <a-table v-if="tab === 'users'" :data-source="filteredUsers" :row-key="r => r.uid" size="middle" :pagination="false">
            <a-table-column title="成员" key="member">
              <template #default="{ record }">
                <div class="mem-user">
                  <span class="mem-avatar">{{ initial(record) }}</span>
                  <div class="mem-user-txt">
                    <span class="mem-name">{{ record.username || '（未命名）' }}</span>
                    <code class="mem-uid">{{ record.uid }}</code>
                  </div>
                </div>
              </template>
            </a-table-column>
            <a-table-column title="部门" key="department" width="180">
              <template #default="{ record }">
                <span v-if="record.departmentId" class="mem-dept">{{ deptName(record.departmentId) }}</span>
                <span v-else class="mem-none">未分配</span>
              </template>
            </a-table-column>
            <a-table-column title="角色" key="role" width="120">
              <template #default="{ record }">
                <span class="role-pill" :class="'r-' + (record.role || 'user')">{{ roleLabel(record.role) }}</span>
              </template>
            </a-table-column>
            <a-table-column title="状态" key="status" width="100">
              <template #default="{ record }">
                <span class="app-pill" :class="record.status === 0 ? 'warn' : 'ok'">{{ record.status === 0 ? '已禁用' : '启用' }}</span>
              </template>
            </a-table-column>
            <a-table-column title="操作" key="action" width="140">
              <template #default="{ record }">
                <span class="mem-inline">
                  <button class="app-link-btn" @click="openUserEdit(record)">编辑</button>
                  <button class="app-link-btn" @click="openResetPwd(record)">重置密码</button>
                  <a-popconfirm title="删除后该用户将失去部门与角色归属（不影响其历史会话）" ok-text="删除" cancel-text="取消" @confirm="delUser(record.uid)">
                    <button class="app-link-btn danger">删除</button>
                  </a-popconfirm>
                </span>
              </template>
            </a-table-column>
            <template #emptyText>
              <div class="mem-empty">
                <span>{{ keyword ? '没有匹配的用户' : '暂无用户' }}</span>
                <button v-if="!keyword" class="app-link-btn" @click="openUserCreate">新建第一个用户</button>
              </div>
            </template>
          </a-table>

          <!-- 部门（树形：无关键字按层级展示，有关键字平铺命中项） -->
          <a-table v-else :data-source="filteredDepts" :row-key="r => r.id" size="middle" :pagination="false"
                   :default-expand-all-rows="true">
            <a-table-column title="部门名称" key="name" width="260">
              <template #default="{ record }">
                <span class="mem-user">
                  <span class="mem-avatar dept"><apartment-outlined /></span>
                  <span class="mem-name">{{ record.name }}</span>
                </span>
              </template>
            </a-table-column>
            <a-table-column title="描述" key="description">
              <template #default="{ record }">
                <span v-if="record.description" class="mem-desc">{{ record.description }}</span>
                <span v-else class="mem-none">—</span>
              </template>
            </a-table-column>
            <a-table-column title="人数" key="count" width="90">
              <template #default="{ record }"><span class="mem-inline">{{ deptUserCount(record.id) }} 人</span></template>
            </a-table-column>
            <a-table-column title="操作" key="action" width="140">
              <template #default="{ record }">
                <span class="mem-inline">
                  <button class="app-link-btn" @click="openDeptEdit(record)">编辑</button>
                  <a-popconfirm title="确定删除该部门？" ok-text="删除" cancel-text="取消" @confirm="delDept(record.id)">
                    <button class="app-link-btn danger">删除</button>
                  </a-popconfirm>
                </span>
              </template>
            </a-table-column>
            <template #emptyText>
              <div class="mem-empty">
                <span>{{ keyword ? '没有匹配的部门' : '暂无部门' }}</span>
                <button v-if="!keyword" class="app-link-btn" @click="openDeptCreate">新建第一个部门</button>
              </div>
            </template>
          </a-table>
        </a-spin>
      </div>
    </div>

    <!-- 用户弹窗 -->
    <a-modal v-model:open="userModal" :title="userForm.isEdit ? '编辑用户' : '新建用户'" :width="480"
             :confirm-loading="saving" ok-text="保存" cancel-text="取消" @ok="saveUser">
      <a-form layout="vertical" style="margin-top:4px">
        <a-form-item label="用户标识（uid）" :validate-status="uidError ? 'error' : ''" :help="uidError">
          <a-input v-model:value="userForm.uid" :disabled="userForm.isEdit"
                   placeholder="登录标识，如 alice（建后不可修改）" @change="uidError = ''" />
        </a-form-item>
        <a-form-item label="用户名">
          <a-input v-model:value="userForm.username" maxlength="100" placeholder="显示名称，如 张三" />
        </a-form-item>
        <template v-if="!userForm.isEdit">
          <a-form-item label="初始密码" :validate-status="pwdError ? 'error' : ''" :help="pwdError">
            <a-input-password v-model:value="userForm.password" placeholder="至少 6 位；用户用它登录"
                              autocomplete="new-password" @change="pwdError = ''" />
          </a-form-item>
          <a-form-item label="确认密码">
            <a-input-password v-model:value="userForm.confirm" placeholder="再次输入密码" autocomplete="new-password" />
          </a-form-item>
        </template>
        <a-form-item label="部门">
          <a-select v-model:value="userForm.departmentId" :options="deptOptions" allow-clear
                    placeholder="未分配" style="width:100%"
                    :not-found-content="'暂无部门，可切换到「部门」页先新建'" />
        </a-form-item>
        <a-form-item label="角色">
          <a-select v-model:value="userForm.role" :options="roleOptions" style="width:100%" />
          <div class="form-tip">{{ roleTip }}</div>
        </a-form-item>
        <a-form-item v-if="userForm.isEdit" label="状态">
          <a-radio-group v-model:value="userForm.status" button-style="solid" size="small">
            <a-radio-button :value="1">启用</a-radio-button>
            <a-radio-button :value="0">禁用</a-radio-button>
          </a-radio-group>
          <div class="form-tip">禁用后该用户不再具备部门与管理员身份权益。</div>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 部门弹窗 -->
    <a-modal v-model:open="deptModal" :title="deptForm.id ? '编辑部门' : '新建部门'" :width="440"
             :confirm-loading="saving" ok-text="保存" cancel-text="取消" @ok="saveDept">
      <a-form layout="vertical" style="margin-top:4px">
        <a-form-item label="父部门（可选）">
          <a-tree-select v-model:value="deptForm.parentId" allow-clear tree-default-expand-all
                         placeholder="空 = 顶级部门" style="width:100%"
                         :tree-data="deptParentTreeData" :field-names="{ label: 'name', value: 'id', children: 'children' }" />
        </a-form-item>
        <a-form-item label="部门名称">
          <a-input v-model:value="deptForm.name" maxlength="100" placeholder="如 研发部" />
        </a-form-item>
        <a-form-item label="描述（可选）">
          <a-input v-model:value="deptForm.description" maxlength="255" placeholder="部门说明" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 重置密码 -->
    <a-modal v-model:open="pwdModal" title="重置密码" :width="420" :confirm-loading="saving"
             ok-text="重置" cancel-text="取消" @ok="savePwd">
      <p class="form-tip" style="margin-top:0">将重置用户 <b>{{ pwdForm.uid }}</b> 的密码，并清除其登录失败锁定。</p>
      <a-form layout="vertical">
        <a-form-item label="新密码" :validate-status="pwdError ? 'error' : ''" :help="pwdError">
          <a-input-password v-model:value="pwdForm.password" placeholder="至少 6 位" autocomplete="new-password" @change="pwdError = ''" />
        </a-form-item>
        <a-form-item label="确认密码">
          <a-input-password v-model:value="pwdForm.confirm" placeholder="再次输入密码" autocomplete="new-password" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { SearchOutlined, PlusOutlined, ApartmentOutlined } from '@ant-design/icons-vue'
import { listUsers, createUser, updateUser, deleteUser, resetUserPassword,
         listDepartments, createDepartment, updateDepartment, deleteDepartment,
         listRoleOptions } from '../api'

/* ==================== 页签：状态落 URL / 数据懒加载 / 各页签独立记忆筛选 ==================== */
const route = useRoute()
const router = useRouter()
const TABS = ['users', 'departments']
const tab = computed({
  get: () => (TABS.includes(route.query.tab) ? route.query.tab : 'users'),
  set: v => router.replace({ path: '/members', query: v === 'users' ? {} : { tab: v } })
})

/** 每个页签各记自己的搜索/筛选（此前共用一个 keyword，切页签即被清空） */
const filters = reactive({
  users: { keyword: '', dept: undefined, role: undefined },
  departments: { keyword: '' }
})
const keyword = computed({
  get: () => filters[tab.value].keyword,
  set: v => { filters[tab.value].keyword = v }
})

const loading = ref(false)
const saving = ref(false)
const users = ref([])
const departments = ref([])
const roles = ref([]) // 角色下拉选项（RBAC：{code,name,adminFlag,builtin}）

// 用户页签：部门 / 角色筛选（与搜索协同；角色选项来自角色表，动态）
const deptFilter = computed({
  get: () => filters.users.dept,
  set: v => { filters.users.dept = v }
})
const roleFilter = computed({
  get: () => filters.users.role,
  set: v => { filters.users.role = v }
})
const deptFilterOptions = computed(() => departments.value.map(d => ({ label: d.name, value: d.id })))
const roleFilterOptions = computed(() => roles.value.map(r => ({ label: r.name, value: r.code })))
// 换筛选时清掉本页签的关键字（原来清的是那个共用 keyword）
watch([deptFilter, roleFilter], () => { filters.users.keyword = '' })

const filteredUsers = computed(() => {
  const k = filters.users.keyword.trim().toLowerCase()
  const d = filters.users.dept
  const r = filters.users.role
  return users.value.filter(u => {
    if (d !== undefined && (u.departmentId || '') !== d) return false
    if (r !== undefined && (u.role || 'user') !== r) return false
    if (k && !(u.username || '').toLowerCase().includes(k) && !(u.uid || '').toLowerCase().includes(k)) return false
    return true
  })
})

/** 平铺 → 树（按 parentId；孤儿当顶级） */
function buildDeptTree (list) {
  const byId = new Map(list.map(d => [d.id, { ...d, children: undefined }]))
  const roots = []
  for (const node of byId.values()) {
    const parent = node.parentId ? byId.get(node.parentId) : null
    if (parent && parent.id !== node.id) {
      parent.children = parent.children || []
      parent.children.push(node)
    } else roots.push(node)
  }
  return roots
}
/** 无关键字 → 树形层级；有关键字 → 平铺命中项（树里搜层级不便看） */
const filteredDepts = computed(() => {
  const k = filters.departments.keyword.trim().toLowerCase()
  const list = departments.value
  if (!k) return buildDeptTree(list)
  return list.filter(d => (d.name || '').toLowerCase().includes(k) || (d.description || '').toLowerCase().includes(k))
})

const roleName = code => (roles.value.find(r => r.code === code) || {}).name
const roleLabel = r => roleName(r) || ({ superadmin: '超级管理员', admin: '管理员', user: '成员' }[r] || r)
const initial = r => String(r.username || r.uid || '?').trim().charAt(0).toUpperCase() || '?'
const deptName = id => (departments.value.find(d => d.id === id) || {}).name || '—'
const deptUserCount = id => users.value.filter(u => u.departmentId === id).length

const deptOptions = computed(() => departments.value.map(d => ({ label: d.name, value: d.id })))
const roleOptions = computed(() => roles.value.map(r => ({ label: r.name, value: r.code })))
const roleTip = computed(() => {
  const r = roles.value.find(x => x.code === userForm.value.role)
  if (!r) return ''
  if (r.adminFlag === 1) return '管理员级角色：可访问全部管理功能。'
  return r.builtin === 1 ? '普通角色：问答与个人资产，可按权限管理中的绑定开放更多功能。' : '自定义角色：按「权限管理 → 角色权限」的绑定开放菜单与接口。'
})

/* ==================== 加载（按页签懒加载 + 保存后按需重拉） ==================== */
const loaded = reactive({ users: false, departments: false, roles: false })

async function loadUsers () {
  const r = await listUsers()
  users.value = (r && r.data) || []
}
async function loadDepartments () {
  const r = await listDepartments()
  departments.value = (r && r.data) || []
}
async function loadRoles () {
  const r = await listRoleOptions()
  roles.value = (r && r.data) || []
}
const LOADERS = { users: loadUsers, departments: loadDepartments, roles: loadRoles }

/** 页签 → 该页签渲染真正要用的数据：用户表要部门名/角色名，部门表的「人数」列要用户 */
const TAB_DEPS = {
  users: ['users', 'departments', 'roles'],
  departments: ['departments', 'users']
}

/** 默认只拉还没拉过的；force=true 用于保存/删除后重拉。失败的不标记 loaded，下次进入会重试 */
async function loadData (names, { force = false } = {}) {
  const todo = force ? names : names.filter(n => !loaded[n])
  if (!todo.length) return
  loading.value = true
  try {
    const res = await Promise.allSettled(todo.map(n => LOADERS[n]()))
    const failed = []
    res.forEach((r, i) => {
      if (r.status === 'fulfilled') loaded[todo[i]] = true
      else failed.push(todo[i])
    })
    if (failed.length) message.error(`${failed.length} 项数据加载失败，切换页签可重试`)
  } finally { loading.value = false }
}

// 页签即数据入口：URL 直达 ?tab=departments 时也只会拉它依赖的数据
watch(tab, t => loadData(TAB_DEPS[t]), { immediate: true })

// ---------- 用户 ----------
const userModal = ref(false)
const userForm = ref(blankUser())
const uidError = ref('')
const pwdError = ref('')
function blankUser () { return { isEdit: false, uid: '', username: '', password: '', confirm: '', departmentId: undefined, role: 'user', status: 1 } }
function openUserCreate () { userForm.value = blankUser(); uidError.value = ''; userModal.value = true }
function openUserEdit (r) {
  userForm.value = {
    isEdit: true,
    uid: r.uid,
    username: r.username || '',
    departmentId: r.departmentId || undefined,
    role: r.role || 'user',
    status: r.status === 0 ? 0 : 1
  }
  uidError.value = ''
  userModal.value = true
}
async function saveUser () {
  const f = userForm.value
  uidError.value = ''
  pwdError.value = ''
  if (!f.isEdit) {
    const uid = String(f.uid || '').trim()
    if (!uid) { uidError.value = '请填写用户标识'; return }
    if (!/^[A-Za-z0-9_@.\-]+$/.test(uid)) { uidError.value = '仅允许字母、数字及 _ @ . -'; return }
    if (!f.password || f.password.length < 6) { pwdError.value = '密码至少 6 位'; return }
    if (f.password !== f.confirm) { pwdError.value = '两次输入的密码不一致'; return }
  }
  saving.value = true
  try {
    const base = { username: f.username, departmentId: f.departmentId || '', role: f.role }
    const r = f.isEdit
      ? await updateUser(f.uid, { ...base, status: f.status })
      : await createUser({ ...base, uid: String(f.uid).trim(), password: f.password })
    if (r.success) { message.success('已保存'); userModal.value = false; await loadData(['users'], { force: true }) }
    else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}
async function delUser (uid) {
  try {
    const r = await deleteUser(uid)
    if (r.success) { message.success('已删除'); await loadData(['users'], { force: true }) }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}

// ---------- 重置密码 ----------
const pwdModal = ref(false)
const pwdForm = ref({ uid: '', password: '', confirm: '' })
function openResetPwd (r) { pwdForm.value = { uid: r.uid, password: '', confirm: '' }; pwdError.value = ''; pwdModal.value = true }
async function savePwd () {
  const f = pwdForm.value
  pwdError.value = ''
  if (!f.password || f.password.length < 6) { pwdError.value = '密码至少 6 位'; return }
  if (f.password !== f.confirm) { pwdError.value = '两次输入的密码不一致'; return }
  saving.value = true
  try {
    const r = await resetUserPassword(f.uid, f.password)
    if (r.success) { message.success('密码已重置'); pwdModal.value = false }
    else message.error(r.msg || '重置失败')
  } catch (e) { message.error(e.message || '重置失败') }
  finally { saving.value = false }
}

// ---------- 部门（树形） ----------
const deptModal = ref(false)
const deptForm = ref(blankDept())
function blankDept () { return { id: '', parentId: undefined, name: '', description: '' } }
function openDeptCreate () { deptForm.value = blankDept(); deptModal.value = true }
function openDeptEdit (r) {
  deptForm.value = { id: r.id, parentId: r.parentId || undefined, name: r.name || '', description: r.description || '' }
  deptModal.value = true
}
/** 父部门候选：排除自身及其后代（防环） */
const deptParentTreeData = computed(() => {
  const tree = buildDeptTree(departments.value)
  if (!deptForm.value.id) return tree
  const strip = list => (list || [])
    .filter(n => n.id !== deptForm.value.id)
    .map(n => ({ ...n, children: strip(n.children) }))
  return strip(tree)
})
async function saveDept () {
  const f = deptForm.value
  if (!String(f.name || '').trim()) { message.warning('请填写部门名称'); return }
  saving.value = true
  try {
    const body = { name: f.name, description: f.description, parentId: f.parentId || '' }
    const r = f.id
      ? await updateDepartment(f.id, body)
      : await createDepartment(body)
    if (r.success) { message.success('已保存'); deptModal.value = false; await loadData(['departments'], { force: true }) }
    else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}
async function delDept (id) {
  try {
    const r = await deleteDepartment(id)
    if (r.success) { message.success('已删除'); await loadData(['departments'], { force: true }) }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}
</script>

<style scoped>
/* 页签式 Tab（与「智能体工作台」的 .hub-tabs 同形）：只作导航，内容仍在下方共用卡片里按 tab 渲染 */
.pg-tabs { flex: none; }
.pg-tabs :deep(.ant-tabs-nav) { margin: 0; padding: 4px 20px 0; background: var(--app-panel); }
.pg-tabs :deep(.ant-tabs-nav::before) { border-color: var(--app-border); }
.pg-tabs :deep(.ant-tabs-tab) { font-size: 13px; padding: 10px 2px; }
.pg-tab { display: inline-flex; align-items: center; }
.pg-badge {
  font-style: normal; font-size: 11px; line-height: 16px; margin-left: 6px;
  padding: 0 6px; border-radius: 999px;
  background: var(--app-accent-weak); color: var(--app-text3);
}
.pg-tabs :deep(.ant-tabs-tab-active) .pg-badge { background: var(--app-accent); color: #fff; }

.mem-toolbar { display: flex; align-items: center; gap: 10px; padding: 10px 14px; border-bottom: 1px solid var(--app-border); }
.mem-search { width: 220px; }
.mem-search-ic { color: var(--app-text3); }
.mem-filter { width: 130px; flex: none; }
.mem-filter .ant-select-selector { font-size: 12px; }
.mem-count { font-size: 12px; color: var(--app-text3); }
.mem-new { margin-left: auto; }

.mem-user { display: inline-flex; align-items: center; gap: 9px; min-width: 0; }
.mem-avatar {
  width: 26px; height: 26px; border-radius: 50%; flex: none;
  background: var(--app-accent-weak); color: var(--app-accent);
  font-size: 12px; display: inline-flex; align-items: center; justify-content: center;
}
.mem-avatar.dept { border-radius: 7px; }
.mem-user-txt { display: flex; flex-direction: column; gap: 1px; min-width: 0; }
.mem-name { color: var(--app-text); }
.mem-uid { font-size: 11px; color: var(--app-text3); }
.mem-dept { color: var(--app-text2); }
.mem-desc { color: var(--app-text2); }
.mem-none { color: var(--app-text3); }
.mem-inline { display: inline-flex; align-items: center; gap: 8px; }

.role-pill { display: inline-flex; align-items: center; font-size: 11px; line-height: 1; padding: 4px 8px; border-radius: 999px; }
.role-pill.r-superadmin { color: var(--app-accent); background: #e8eefc; }
.role-pill.r-admin { color: #3b6d11; background: #eaf3de; }
.role-pill.r-user { color: var(--app-text2); background: #f1f3f5; }

.mem-empty { padding: 28px 0; color: var(--app-text3); font-size: 12px; display: flex; flex-direction: column; align-items: center; gap: 8px; }
.form-tip { margin-top: 6px; font-size: 12px; color: var(--app-text3); line-height: 1.5; }
</style>
