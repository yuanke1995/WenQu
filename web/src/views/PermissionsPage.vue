<template>
  <div class="app-page">
    <div class="app-page-head">
      <h3 class="app-page-title">权限管理</h3>
      <span class="head-hint-plain">菜单决定侧边栏可见项，接口绑定决定能否调用</span>
    </div>

    <!-- 页签式 Tab：与「智能体工作台」同一形态。此处只承载导航，内容用下方共用卡片按 tab 渲染；
         页签状态落 URL(?tab=)，刷新/回退/分享可还原 -->
    <a-tabs v-model:active-key="tab" class="pg-tabs">
      <a-tab-pane key="menus">
        <template #tab><span class="pg-tab">菜单<i v-if="loaded.menus" class="pg-badge">{{ menus.length }}</i></span></template>
      </a-tab-pane>
      <a-tab-pane key="apis">
        <template #tab><span class="pg-tab">接口<i v-if="loaded.apis" class="pg-badge">{{ apis.length }}</i></span></template>
      </a-tab-pane>
      <a-tab-pane key="roles">
        <template #tab><span class="pg-tab">角色<i v-if="loaded.roles" class="pg-badge">{{ roles.length }}</i></span></template>
      </a-tab-pane>
    </a-tabs>

    <div class="app-page-body">
      <div class="app-card" style="padding:0;overflow:hidden">
        <!-- 工具栏：搜索 + 计数 + 新建 -->
        <div class="rb-toolbar">
          <a-input v-model:value="keyword" allow-clear size="small" class="rb-search"
                   :placeholder="tab === 'apis' ? '搜索路径 / 名称' : tab === 'menus' ? '搜索菜单名称 / 路径' : '搜索角色编码 / 名称'">
            <template #prefix><search-outlined class="rb-search-ic" /></template>
          </a-input>
          <a-select v-if="tab === 'apis'" v-model:value="moduleFilter" allow-clear size="small" class="rb-filter"
                    placeholder="全部模块" :options="moduleFilterOptions" />
          <span class="rb-count">{{ countText }}</span>
          <button v-if="tab === 'menus'" class="app-btn rb-new" @click="openMenuCreate"><plus-outlined /> 新建菜单</button>
          <button v-else-if="tab === 'apis'" class="app-btn rb-new" @click="openApiCreate"><plus-outlined /> 登记接口</button>
          <button v-else class="app-btn rb-new" @click="openRoleCreate"><plus-outlined /> 新建角色</button>
        </div>

        <a-spin :spinning="loading">
          <!-- ==================== 菜单 ==================== -->
          <a-table v-if="tab === 'menus'" :data-source="menuTree" :row-key="r => r.id" size="middle" :pagination="false">
            <a-table-column title="菜单" key="name">
              <template #default="{ record }">
                <span class="rb-menu">
                  <component :is="iconOf(record.icon)" class="rb-menu-ic" />
                  <span class="rb-name">{{ record.name }}</span>
                  <span v-if="record.builtin === 1" class="app-pill builtin">内置</span>
                  <span v-if="record.visible === 0" class="app-pill warn">隐藏</span>
                </span>
              </template>
            </a-table-column>
            <a-table-column title="路径" key="path">
              <template #default="{ record }">
                <code v-if="record.path" class="rb-path">{{ record.path }}</code>
                <span v-else class="rb-none">—</span>
              </template>
            </a-table-column>
            <a-table-column title="图标" key="icon" width="180">
              <template #default="{ record }">
                <span v-if="record.icon" class="rb-icon-opt" :title="record.icon">
                  <component :is="iconOf(record.icon)" class="rb-icon-opt-ic" />
                  <span class="rb-icon-opt-name">{{ iconLabel(record.icon) }}</span>
                </span>
                <span v-else class="rb-none">—</span>
              </template>
            </a-table-column>
            <a-table-column title="排序" key="sort" width="80">
              <template #default="{ record }"><span class="rb-none">{{ record.sortOrder ?? 0 }}</span></template>
            </a-table-column>
            <a-table-column title="操作" key="action" width="130">
              <template #default="{ record }">
                <span class="rb-inline">
                  <button class="app-link-btn" @click="openMenuEdit(record)">编辑</button>
                  <a-popconfirm v-if="record.builtin !== 1" title="删除后绑定该菜单的角色将不再看到它，确定？"
                                ok-text="删除" cancel-text="取消" @confirm="delMenu(record.id)">
                    <button class="app-link-btn danger">删除</button>
                  </a-popconfirm>
                </span>
              </template>
            </a-table-column>
            <template #emptyText>
              <div class="rb-empty"><span>暂无菜单</span></div>
            </template>
          </a-table>

          <!-- ==================== 接口 ==================== -->
          <a-table v-else-if="tab === 'apis'" :data-source="filteredApis" :row-key="r => r.id" size="middle" :pagination="false">
            <a-table-column title="方法" key="method" width="90">
              <template #default="{ record }">
                <span class="method-pill" :class="'m-' + (record.method || 'ALL').toLowerCase()">{{ record.method }}</span>
              </template>
            </a-table-column>
            <a-table-column title="路径" key="path">
              <template #default="{ record }"><code class="rb-path">{{ record.path }}</code></template>
            </a-table-column>
            <a-table-column title="名称" key="name">
              <template #default="{ record }">
                <span v-if="record.name" class="rb-name">{{ record.name }}</span>
                <span v-else class="rb-none">—</span>
              </template>
            </a-table-column>
            <a-table-column title="模块" key="module" width="150">
              <template #default="{ record }">
                <span v-if="record.module" class="rb-name">{{ record.module }}</span>
                <span v-else class="rb-none">—</span>
              </template>
            </a-table-column>
            <a-table-column title="操作" key="action" width="130">
              <template #default="{ record }">
                <span class="rb-inline">
                  <button class="app-link-btn" @click="openApiEdit(record)">编辑</button>
                  <a-popconfirm :title="record.builtin === 1 ? '代码中仍存在的端点重启后会重新登记，确定删除？' : '确定删除该接口登记？'"
                                ok-text="删除" cancel-text="取消" @confirm="delApi(record.id)">
                    <button class="app-link-btn danger">删除</button>
                  </a-popconfirm>
                </span>
              </template>
            </a-table-column>
            <template #emptyText>
              <div class="rb-empty"><span>{{ keyword ? '没有匹配的接口' : '暂无接口（启动时会自动扫描登记）' }}</span></div>
            </template>
          </a-table>

          <!-- ==================== 角色 ==================== -->
          <a-table v-else :data-source="filteredRoles" :row-key="r => r.code" size="middle" :pagination="false">
            <a-table-column title="角色" key="code">
              <template #default="{ record }">
                <span class="rb-menu">
                  <span class="rb-name">{{ record.name }}</span>
                  <code class="rb-uid">{{ record.code }}</code>
                  <span v-if="record.builtin === 1" class="app-pill builtin">内置</span>
                </span>
              </template>
            </a-table-column>
            <a-table-column title="管理员级" key="adminFlag" width="110">
              <template #default="{ record }">
                <span class="app-pill" :class="record.adminFlag === 1 ? 'ok' : ''">{{ record.adminFlag === 1 ? '是' : '否' }}</span>
              </template>
            </a-table-column>
            <a-table-column title="状态" key="status" width="90">
              <template #default="{ record }">
                <span class="app-pill" :class="record.status === 0 ? 'warn' : 'ok'">{{ record.status === 0 ? '停用' : '启用' }}</span>
              </template>
            </a-table-column>
            <a-table-column title="描述" key="description">
              <template #default="{ record }">
                <span v-if="record.description" class="rb-desc">{{ record.description }}</span>
                <span v-else class="rb-none">—</span>
              </template>
            </a-table-column>
            <a-table-column title="操作" key="action" width="240">
              <template #default="{ record }">
                <span class="rb-inline">
                  <button class="app-link-btn" @click="openRoleEdit(record)">编辑</button>
                  <button class="app-link-btn" @click="openDrawer(record, 'menu')">菜单权限</button>
                  <button class="app-link-btn" @click="openDrawer(record, 'api')">接口权限</button>
                  <a-popconfirm v-if="record.builtin !== 1" title="删除后其绑定一并清除，确定？"
                                ok-text="删除" cancel-text="取消" @confirm="delRole(record.code)">
                    <button class="app-link-btn danger">删除</button>
                  </a-popconfirm>
                </span>
              </template>
            </a-table-column>
            <template #emptyText>
              <div class="rb-empty">
                <span>{{ keyword ? '没有匹配的角色' : '暂无角色' }}</span>
                <button v-if="!keyword" class="app-link-btn" @click="openRoleCreate">新建第一个角色</button>
              </div>
            </template>
          </a-table>
        </a-spin>
      </div>
    </div>

    <!-- 菜单弹窗 -->
    <a-modal v-model:open="menuModal" :title="menuForm.id ? '编辑菜单' : '新建菜单'" :width="480"
             :confirm-loading="saving" ok-text="保存" cancel-text="取消" @ok="saveMenu">
      <a-form layout="vertical" style="margin-top:4px">
        <a-form-item label="父菜单（可选）">
          <a-tree-select v-model:value="menuForm.parentId" allow-clear tree-default-expand-all
                         placeholder="空 = 顶级菜单" style="width:100%"
                         :tree-data="parentTreeData" :field-names="{ label: 'name', value: 'id', children: 'children' }" />
        </a-form-item>
        <a-form-item label="菜单名称">
          <a-input v-model:value="menuForm.name" maxlength="50" placeholder="如 数据看板" />
        </a-form-item>
        <a-form-item label="路由路径">
          <a-input v-model:value="menuForm.path" placeholder="如 /dashboard（留空 = 仅作分组节点）" />
        </a-form-item>
        <a-form-item label="图标">
          <a-select v-model:value="menuForm.icon" allow-clear show-search style="width:100%"
                    placeholder="默认图标" :options="iconOptions" :filter-option="filterIcon">
            <template #option="opt">
              <span class="rb-icon-opt">
                <component :is="iconOf(opt.value)" class="rb-icon-opt-ic" />
                <span class="rb-icon-opt-name">{{ opt.label }}</span>
                <span class="rb-icon-opt-key">{{ opt.value }}</span>
              </span>
            </template>
            <template #optionLabel="opt">
              <span class="rb-icon-opt">
                <component :is="iconOf(opt.value)" class="rb-icon-opt-ic" />
                <span class="rb-icon-opt-name">{{ opt.label }}</span>
              </span>
            </template>
          </a-select>
        </a-form-item>
        <a-form-item label="排序">
          <a-input-number v-model:value="menuForm.sort" :min="0" :max="9999" style="width:100%" />
          <div class="form-tip">数字小的排前面。</div>
        </a-form-item>
        <a-form-item label="可见">
          <a-switch v-model:checked="menuVisible" checked-children="显示" un-checked-children="隐藏" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 接口弹窗 -->
    <a-modal v-model:open="apiModal" :title="apiForm.id ? '编辑接口' : '登记接口'" :width="500"
             :confirm-loading="saving" ok-text="保存" cancel-text="取消" @ok="saveApi">
      <a-form layout="vertical" style="margin-top:4px">
        <a-form-item label="HTTP 方法">
          <a-select v-model:value="apiForm.method" style="width:100%" :disabled="apiForm.builtin"
                    :options="['GET','POST','PUT','DELETE','PATCH','ALL'].map(m => ({ label: m, value: m }))" />
        </a-form-item>
        <a-form-item label="接口路径">
          <a-input v-model:value="apiForm.path" :disabled="apiForm.builtin"
                   placeholder="以 /api/ 开头；占位符用 {id}，如 /api/ai/agent/{id}" />
          <div v-if="apiForm.builtin" class="form-tip">扫描登记的接口以代码为准，方法与路径不可改。</div>
        </a-form-item>
        <a-form-item label="名称">
          <a-input v-model:value="apiForm.name" maxlength="100" placeholder="展示用名称（可选）" />
        </a-form-item>
        <a-form-item label="模块">
          <a-input v-model:value="apiForm.module" maxlength="50" placeholder="如 智能体（可自定义归类）" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 角色弹窗 -->
    <a-modal v-model:open="roleModal" :title="roleForm.isEdit ? '编辑角色' : '新建角色'" :width="460"
             :confirm-loading="saving" ok-text="保存" cancel-text="取消" @ok="saveRole">
      <a-form layout="vertical" style="margin-top:4px">
        <a-form-item label="角色编码">
          <a-input v-model:value="roleForm.code" :disabled="roleForm.isEdit"
                   placeholder="小写字母开头，如 editor（建后不可修改）" />
          <div class="form-tip">即用户身上的角色值（用户建档时选择）。</div>
        </a-form-item>
        <a-form-item label="角色名称">
          <a-input v-model:value="roleForm.name" maxlength="50" placeholder="如 内容编辑" />
        </a-form-item>
        <a-form-item label="描述（可选）">
          <a-input v-model:value="roleForm.description" maxlength="255" placeholder="角色说明" />
        </a-form-item>
        <a-form-item label="管理员级">
          <a-switch v-model:checked="roleAdmin" :disabled="roleForm.builtin" checked-children="是" un-checked-children="否" />
          <div class="form-tip">管理员级角色放行全部接口与菜单，无需逐条绑定；内置角色的该开关不可改。</div>
        </a-form-item>
        <a-form-item v-if="!roleForm.builtin" label="状态">
          <a-radio-group v-model:value="roleForm.status" button-style="solid" size="small">
            <a-radio-button :value="1">启用</a-radio-button>
            <a-radio-button :value="0">停用</a-radio-button>
          </a-radio-group>
          <div class="form-tip">停用后该角色仅保留问答白名单能力，绑定全部失效。</div>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 角色权限抽屉（菜单 / 接口绑定） -->
    <a-drawer v-model:open="drawer" :title="drawerTitle" :width="540" destroy-on-close>
      <a-alert v-if="drawerRole && drawerRole.adminFlag === 1" type="info" show-icon style="margin-bottom:12px"
               message="管理员级角色天然拥有全部权限，以下绑定仅对普通角色生效" />
      <a-tabs v-model:active-key="drawerTab">
        <a-tab-pane key="menu" tab="菜单权限">
          <p class="form-tip" style="margin-top:0">勾选该角色在侧边栏可见的菜单。菜单数据在「菜单」页维护。</p>
          <a-tree v-if="drawer" checkable default-expand-all v-model:checked-keys="checkedMenuIds"
                  :tree-data="bindMenuTree" :field-names="{ title: 'name', key: 'id', children: 'children' }" />
        </a-tab-pane>
        <a-tab-pane key="api" tab="接口权限">
          <p class="form-tip" style="margin-top:0">勾选该角色可调用的接口（按模块分组）。未勾选的接口该角色调用将返回 403。</p>
          <a-tree v-if="drawer" checkable default-expand-all v-model:checked-keys="checkedApiKeys"
                  :tree-data="bindApiTree" />
        </a-tab-pane>
      </a-tabs>
      <template #footer>
        <button class="app-btn" style="margin-right:8px" @click="drawer = false">取消</button>
        <button class="app-btn primary" :disabled="drawerSaving" @click="saveDrawer">
          {{ drawerSaving ? '保存中…' : '保存权限' }}
        </button>
      </template>
    </a-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { SearchOutlined, PlusOutlined, MessageOutlined, RobotOutlined, DatabaseOutlined, TeamOutlined,
         BarChartOutlined, ExperimentOutlined, SafetyOutlined, SettingOutlined, AppstoreOutlined,
         FolderOutlined, UserOutlined, FileOutlined } from '@ant-design/icons-vue'
import { listMenus, createMenu, updateMenu, deleteMenu,
         listApis, createApi, updateApi, deleteApi,
         listRoles, createRole, updateRole, deleteRole,
         getRoleMenus, saveRoleMenus, getRoleApis, saveRoleApis } from '../api'

/* ==================== 页签：状态落 URL / 数据懒加载 / 各页签独立记忆筛选 ==================== */
const route = useRoute()
const router = useRouter()
const TABS = ['menus', 'apis', 'roles']
const tab = computed({
  get: () => (TABS.includes(route.query.tab) ? route.query.tab : 'menus'),
  set: v => router.replace({ path: '/permissions', query: v === 'menus' ? {} : { tab: v } })
})

/** 每个页签各记自己的搜索/筛选（此前共用一个 keyword，切页签即被清空） */
const filters = reactive({
  menus: { keyword: '' },
  apis: { keyword: '', module: undefined },
  roles: { keyword: '' }
})
const keyword = computed({
  get: () => filters[tab.value].keyword,
  set: v => { filters[tab.value].keyword = v }
})

const loading = ref(false)
const saving = ref(false)
const menus = ref([])
const apis = ref([])
const roles = ref([])

// ==================== 公共工具 ====================
const ICONS = {
  MessageOutlined, RobotOutlined, DatabaseOutlined, TeamOutlined, BarChartOutlined,
  ExperimentOutlined, SafetyOutlined, SettingOutlined, AppstoreOutlined,
  FolderOutlined, UserOutlined, FileOutlined
}
/** 图标中文名（值仍存英文类名，只用于界面展示；未登记的按类名原样显示） */
const ICON_LABELS = {
  MessageOutlined: '消息',
  RobotOutlined: '机器人',
  DatabaseOutlined: '数据库',
  TeamOutlined: '团队',
  BarChartOutlined: '图表',
  ExperimentOutlined: '实验',
  SafetyOutlined: '安全',
  SettingOutlined: '设置',
  AppstoreOutlined: '应用',
  FolderOutlined: '文件夹',
  UserOutlined: '用户',
  FileOutlined: '文件'
}
const iconOf = name => ICONS[name] || FileOutlined
const iconLabel = name => ICON_LABELS[name] || name
const iconOptions = Object.keys(ICONS).map(k => ({ label: ICON_LABELS[k] || k, value: k }))
/** 搜索同时匹配中文名与英文类名 */
function filterIcon (input, option) {
  const q = String(input || '').trim().toLowerCase()
  if (!q) return true
  return String(option.label).toLowerCase().includes(q) || String(option.value).toLowerCase().includes(q)
}

/** 平铺 → 树（按 parentId 组装；孤儿节点当顶级） */
function buildTree (list) {
  const byId = new Map(list.map(m => [m.id, { ...m, children: undefined }]))
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

// ==================== 菜单 ====================
const menuTree = computed(() => {
  const k = filters.menus.keyword.trim().toLowerCase()
  const tree = buildTree(menus.value)
  if (!k) return tree
  // 命中（自身或后代）的分支
  const hit = node => (node.name || '').toLowerCase().includes(k) || (node.path || '').toLowerCase().includes(k)
    || (node.children || []).some(hit)
  const prune = list => list.filter(hit).map(n => ({ ...n, children: n.children ? prune(n.children) : undefined }))
  return prune(tree)
})
const countText = computed(() =>
  tab.value === 'menus' ? `${menus.value.length} 个菜单`
    : tab.value === 'apis' ? `${filteredApis.value.length} 个接口`
      : `${filteredRoles.value.length} 个角色`)

const menuModal = ref(false)
const menuForm = ref(blankMenu())
const menuVisible = ref(true)
function blankMenu () { return { id: '', parentId: undefined, name: '', icon: undefined, path: '', sort: 0 } }
function openMenuCreate () { menuForm.value = blankMenu(); menuVisible.value = true; menuModal.value = true }
function openMenuEdit (r) {
  menuForm.value = { id: r.id, parentId: r.parentId || undefined, name: r.name || '', icon: r.icon || undefined, path: r.path || '', sort: r.sortOrder ?? 0 }
  menuVisible.value = r.visible !== 0
  menuModal.value = true
}
/** 父菜单候选：排除自身及其后代（防环） */
const parentTreeData = computed(() => {
  const tree = buildTree(menus.value)
  if (!menuForm.value.id) return tree
  const strip = list => (list || [])
    .filter(n => n.id !== menuForm.value.id)
    .map(n => ({ ...n, children: strip(n.children) }))
  return strip(tree)
})
async function saveMenu () {
  const f = menuForm.value
  if (!String(f.name || '').trim()) { message.warning('请填写菜单名称'); return }
  saving.value = true
  try {
    const body = { parentId: f.parentId || '', name: f.name, icon: f.icon || '', path: f.path || '', sortOrder: f.sort, visible: menuVisible.value ? 1 : 0 }
    const r = f.id ? await updateMenu(f.id, body) : await createMenu(body)
    if (r.success) { message.success('已保存'); menuModal.value = false; await refresh('menus') }
    else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}
async function delMenu (id) {
  try {
    const r = await deleteMenu(id)
    if (r.success) { message.success('已删除'); await refresh('menus') }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}

// ==================== 接口 ====================
const moduleFilter = computed({
  get: () => filters.apis.module,
  set: v => { filters.apis.module = v }
})
const moduleFilterOptions = computed(() => {
  const set = new Set(apis.value.map(a => a.module).filter(Boolean))
  return [...set].sort().map(m => ({ label: m, value: m }))
})
const filteredApis = computed(() => {
  const k = filters.apis.keyword.trim().toLowerCase()
  const m = filters.apis.module
  return apis.value.filter(a => {
    if (m && (a.module || '') !== m) return false
    if (k && !(a.path || '').toLowerCase().includes(k) && !(a.name || '').toLowerCase().includes(k)) return false
    return true
  })
})

const apiModal = ref(false)
const apiForm = ref(blankApi())
function blankApi () { return { id: '', method: 'GET', path: '', name: '', module: '', builtin: 0 } }
function openApiCreate () { apiForm.value = blankApi(); apiModal.value = true }
function openApiEdit (r) {
  apiForm.value = { id: r.id, method: r.method || 'GET', path: r.path || '', name: r.name || '', module: r.module || '', builtin: r.builtin || 0 }
  apiModal.value = true
}
async function saveApi () {
  const f = apiForm.value
  if (!String(f.path || '').trim()) { message.warning('请填写接口路径'); return }
  saving.value = true
  try {
    const body = { method: f.method, path: f.path, name: f.name, module: f.module }
    const r = f.id ? await updateApi(f.id, body) : await createApi(body)
    if (r.success) { message.success('已保存'); apiModal.value = false; await refresh('apis') }
    else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}
async function delApi (id) {
  try {
    const r = await deleteApi(id)
    if (r.success) { message.success('已删除'); await refresh('apis') }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}

// ==================== 角色 ====================
const filteredRoles = computed(() => {
  const k = filters.roles.keyword.trim().toLowerCase()
  if (!k) return roles.value
  return roles.value.filter(r => (r.code || '').toLowerCase().includes(k) || (r.name || '').toLowerCase().includes(k))
})

const roleModal = ref(false)
const roleForm = ref(blankRole())
const roleAdmin = ref(false)
function blankRole () { return { isEdit: false, code: '', name: '', description: '', status: 1, builtin: 0 } }
function openRoleCreate () { roleForm.value = blankRole(); roleAdmin.value = false; roleModal.value = true }
function openRoleEdit (r) {
  roleForm.value = { isEdit: true, code: r.code, name: r.name || '', description: r.description || '', status: r.status === 0 ? 0 : 1, builtin: r.builtin || 0 }
  roleAdmin.value = r.adminFlag === 1
  roleModal.value = true
}
async function saveRole () {
  const f = roleForm.value
  if (!f.isEdit && !/^[a-z][a-z0-9_-]{0,19}$/.test(String(f.code || '').trim())) {
    message.warning('角色编码：小写字母开头，仅小写字母/数字/_/-，≤20 字符'); return
  }
  if (!String(f.name || '').trim()) { message.warning('请填写角色名称'); return }
  saving.value = true
  try {
    const body = { name: f.name, description: f.description, adminFlag: roleAdmin.value ? 1 : 0, status: f.status }
    const r = f.isEdit ? await updateRole(f.code, body) : await createRole({ ...body, code: String(f.code).trim() })
    if (r.success) { message.success('已保存'); roleModal.value = false; await refresh('roles') }
    else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}
async function delRole (code) {
  try {
    const r = await deleteRole(code)
    if (r.success) { message.success('已删除'); await refresh('roles') }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}

// ==================== 权限抽屉（菜单/接口绑定） ====================
const drawer = ref(false)
const drawerTab = ref('menu')
const drawerRole = ref(null)
const drawerSaving = ref(false)
const checkedMenuIds = ref([])
const checkedApiKeys = ref([])
const drawerTitle = computed(() => drawerRole.value ? `角色权限 · ${drawerRole.value.name}` : '角色权限')

/** 绑定用菜单树：全量（含隐藏——隐藏菜单仍可作为权限归属） */
const bindMenuTree = computed(() => buildTree(menus.value))
/** 绑定用接口树：按模块分组；叶子 key = 接口 id */
const bindApiTree = computed(() => {
  const groups = new Map()
  for (const a of apis.value) {
    const mod = a.module || '未归类'
    if (!groups.has(mod)) groups.set(mod, [])
    groups.get(mod).push(a)
  }
  return [...groups.keys()].sort().map(mod => ({
    key: 'mod:' + mod,
    title: `${mod}（${groups.get(mod).length}）`,
    selectable: false,
    children: groups.get(mod)
      .sort((x, y) => (x.path || '').localeCompare(y.path || ''))
      .map(a => ({ key: a.id, title: `${a.method} ${a.path}${a.name ? ' · ' + a.name : ''}` }))
  }))
})

async function openDrawer (role, type) {
  drawerRole.value = role
  drawerTab.value = type
  drawer.value = true
  // 先清空再回显（抽屉 destroy-on-close，直接加载）
  checkedMenuIds.value = []
  checkedApiKeys.value = []
  try {
    // 抽屉的菜单树/接口树直接吃 menus、apis：从角色页签直接打开时，这两个页签的数据可能还没加载过
    await Promise.all([ensureData('menus'), ensureData('apis')])
    const [m, a] = await Promise.all([getRoleMenus(role.code), getRoleApis(role.code)])
    checkedMenuIds.value = (m && m.data) || []
    // 只回显仍存在的接口绑定（接口被删后库里可能残留旧 id）
    const valid = new Set(apis.value.map(x => x.id))
    checkedApiKeys.value = (((a && a.data) || []).filter(id => valid.has(id)))
  } catch (e) { message.error(e.message || '加载角色权限失败') }
}

async function saveDrawer () {
  const role = drawerRole.value
  if (!role) return
  drawerSaving.value = true
  try {
    // 接口树勾选里可能混入分组父节点（key 形如 mod:xxx），只取真实接口 id
    const apiIds = checkedApiKeys.value.filter(k => !String(k).startsWith('mod:'))
    const [m, a] = await Promise.all([saveRoleMenus(role.code, checkedMenuIds.value), saveRoleApis(role.code, apiIds)])
    if (m.success && a.success) { message.success('权限已保存'); drawer.value = false }
    else message.error(m.msg || a.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { drawerSaving.value = false }
}

// ==================== 加载（按页签懒加载：首次切到该页签才拉，之后不再重复请求） ====================
const loaded = reactive({ menus: false, apis: false, roles: false })

async function loadMenus () {
  const r = await listMenus()
  menus.value = (r && r.data) || []
}
async function loadApis () {
  const r = await listApis()
  apis.value = (r && r.data) || []
}
async function loadRoles () {
  const r = await listRoles()
  roles.value = (r && r.data) || []
}
const LOADERS = { menus: loadMenus, apis: loadApis, roles: loadRoles }

const inflight = new Map()
/** 同一份数据的并发请求合并成一次（空闲预取与手动切换可能撞在一起） */
function fetchData (name) {
  if (!inflight.has(name)) {
    const p = LOADERS[name]().finally(() => inflight.delete(name))
    inflight.set(name, p)
  }
  return inflight.get(name)
}

/** 强制重拉（保存/删除后） */
async function refresh (name) {
  loading.value = true
  try {
    await fetchData(name)
    loaded[name] = true
  } catch (e) { message.error(e.message || '加载失败') }
  finally { loading.value = false }
}
/** 首次需要该页签数据时才请求；失败不置 loaded，下次进入会重试 */
async function ensureData (name) {
  if (loaded[name]) return
  await refresh(name)
}
/** 空闲预取：不动 loading、失败静默（真切到该页签时会重试） */
async function prefetch (name) {
  if (loaded[name]) return
  try {
    await fetchData(name)
    loaded[name] = true
  } catch (e) { /* 预取失败不打扰用户 */ }
}
/** 首屏只等当前页签，其余页签等浏览器空闲再补拉——只为让 Tab 角标有数字，各页签数据量都只有几十条 */
const scheduleIdle = window.requestIdleCallback
  ? cb => window.requestIdleCallback(cb, { timeout: 2000 })
  : cb => setTimeout(cb, 300)

// 页签即数据入口：URL 直达 ?tab=apis 时只拉接口；其余页签稍后空闲补拉
watch(tab, t => {
  ensureData(t)
  scheduleIdle(() => TABS.filter(x => x !== t).forEach(prefetch))
}, { immediate: true })
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

.rb-toolbar { display: flex; align-items: center; gap: 10px; padding: 10px 14px; border-bottom: 1px solid var(--app-border); }
.rb-search { width: 240px; }
.rb-search-ic { color: var(--app-text3); }
.rb-filter { width: 140px; flex: none; }
.rb-count { font-size: 12px; color: var(--app-text3); }
.rb-new { margin-left: auto; }
.rb-inline { display: inline-flex; align-items: center; gap: 8px; }
.rb-menu { display: inline-flex; align-items: center; gap: 8px; min-width: 0; }
.rb-menu-ic { color: var(--app-text3); font-size: 13px; }
.rb-name { color: var(--app-text); }
.rb-uid { font-size: 11px; color: var(--app-text3); }
.rb-path { font-size: 12px; color: var(--app-text2); }
.rb-desc { color: var(--app-text2); }
.rb-none { color: var(--app-text3); }
.rb-icon-opt { display: inline-flex; align-items: center; gap: 8px; min-width: 0; }
.rb-icon-opt-ic { color: var(--app-text2); font-size: 14px; flex: none; }
.rb-icon-opt-name { color: var(--app-text); }
.rb-icon-opt-key { font-size: 11px; color: var(--app-text3); }
.rb-empty { padding: 28px 0; color: var(--app-text3); font-size: 12px; display: flex; flex-direction: column; align-items: center; gap: 8px; }

.method-pill { display: inline-flex; align-items: center; font-size: 11px; line-height: 1; padding: 4px 8px; border-radius: 999px; font-weight: 500; }
.method-pill.m-get { color: #1a7f37; background: #e6f4ea; }
.method-pill.m-post { color: #1d5bd6; background: #e8eefc; }
.method-pill.m-put { color: #9a6700; background: #fff3d6; }
.method-pill.m-delete { color: #c0392b; background: #fdebea; }
.method-pill.m-patch { color: #7c3aed; background: #f1eafd; }
.method-pill.m-all { color: var(--app-text2); background: #f1f3f5; }

.app-pill.builtin { color: var(--app-accent); background: #e8eefc; }
.form-tip { margin-top: 6px; font-size: 12px; color: var(--app-text3); line-height: 1.5; }
</style>
