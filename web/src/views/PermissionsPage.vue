<template>
  <div class="app-page">
    <div class="app-page-head">
      <h3 class="app-page-title">权限管理</h3>
      <span class="head-hint-plain" title="接口按菜单归属分组；勾选菜单会一并授权名下接口，未授权的接口调用将返回 403">接口按菜单归属分组；勾选菜单会一并授权名下接口，未授权的接口调用将返回 403</span>
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
            <a-table-column title="模块" key="module" width="130">
              <template #default="{ record }">
                <span v-if="record.module" class="rb-name">{{ record.module }}</span>
                <span v-else class="rb-none">—</span>
              </template>
            </a-table-column>
            <a-table-column title="所属菜单" key="menus" width="200">
              <template #default="{ record }">
                <template v-if="(record.menuIds || []).length">
                  <span v-for="mid in record.menuIds" :key="mid" class="menu-tag">{{ menuNameOf(mid) }}</span>
                </template>
                <span v-else class="rb-none">未归属</span>
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
            <a-table-column title="操作" key="action" width="210">
              <template #default="{ record }">
                <span class="rb-inline">
                  <button class="app-link-btn" @click="openRoleEdit(record)">编辑</button>
                  <button class="app-link-btn" @click="openDrawer(record)">权限配置</button>
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
              <!-- opt 可能为 undefined：库里存了不在 ICONS 里的旧图标名时，select 找不到对应 option，
                   antd 的 optionLabelRender 会以 undefined 调用本插槽（SingleSelector.js:135），必须容错 -->
              <span class="rb-icon-opt">
                <component :is="iconOf(opt?.value)" class="rb-icon-opt-ic" />
                <span class="rb-icon-opt-name">{{ opt?.label }}</span>
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
        <a-form-item label="所属菜单（可多选）">
          <a-tree-select v-model:value="apiMenuSelectValue" multiple tree-checkable :tree-check-strictly="true"
                         :tree-data="menuSelectTree" :field-names="{ label: 'name', value: 'id', children: 'children' }"
                         allow-clear tree-default-expand-all :max-tag-count="4" style="width:100%"
                         placeholder="不选择 = 归入「其他接口」分组" />
          <div class="form-tip">归属决定权限配置页里接口挂在哪个菜单下（可多归属），不影响鉴权。</div>
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

    <!-- 角色权限抽屉（单树：菜单 + 名下接口；勾菜单自动带上接口） -->
    <a-drawer v-model:open="drawer" :title="drawerTitle" :width="580" destroy-on-close>
      <a-alert v-if="drawerRole && drawerRole.adminFlag === 1" type="info" show-icon style="margin-bottom:12px"
               message="管理员级角色天然拥有全部权限，以下绑定仅对普通角色生效" />
      <a-alert v-if="gapCount > 0" type="warning" show-icon style="margin-bottom:12px"
               :message="`检测到 ${gapCount} 个接口未授权`"
               description="已勾选菜单下仍有接口未勾选，该角色调用时会返回 403。">
        <template #action>
          <button class="app-btn primary gap-fill-btn" @click="fillAllGaps">一键补齐</button>
        </template>
      </a-alert>
      <p class="form-tip" style="margin-top:0">
        勾选菜单会连同其名下接口一并勾上（可再单独取消）；接口也可脱离菜单单独勾选。未勾选的接口调用将返回 403。
      </p>
      <a-input v-model:value="drawerKeyword" allow-clear size="small" class="drawer-search"
               placeholder="搜索菜单名称 / 接口路径">
        <template #prefix><search-outlined class="rb-search-ic" /></template>
      </a-input>
      <a-tree v-if="drawer && drawerTree.length" checkable default-expand-all :selectable="false"
              :check-strictly="true" :checked-keys="treeCheckedKeys" @check="onTreeCheck"
              :tree-data="drawerTree">
        <template #title="node">
          <span v-if="node.kind === 'api'" class="tree-api">
            <span class="method-pill" :class="'m-' + (node.method || 'ALL').toLowerCase()">{{ node.method }}</span>
            <code class="rb-path">{{ node.label }}</code>
            <span v-if="node.shared" class="tree-shared" title="该接口同时归属多个菜单，勾选任一菜单都会带上它">共属</span>
          </span>
          <span v-else class="tree-menu">
            <component v-if="node.kind === 'menu'" :is="iconOf(node.icon)" class="rb-menu-ic" />
            <span class="rb-name">{{ node.kind === 'group' ? node.groupName : node.menuName }}</span>
            <span v-if="node.ownedCount" class="tree-count"
                  :class="{ warn: node.grantedCount < node.ownedCount && (node.kind === 'group' || node.menuChecked) }">
              {{ node.grantedCount }}/{{ node.ownedCount }} 接口
              <template v-if="node.kind === 'menu' && node.menuChecked && node.grantedCount < node.ownedCount">
                ，{{ node.ownedCount - node.grantedCount }} 个未授权
              </template>
            </span>
          </span>
        </template>
      </a-tree>
      <div v-else-if="drawer" class="drawer-empty">
        {{ drawerKeyword ? '没有匹配的菜单或接口' : '暂无可配置的菜单或接口' }}
      </div>
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
         FolderOutlined, UserOutlined, FileOutlined, FileTextOutlined } from '@ant-design/icons-vue'
import { listMenus, createMenu, updateMenu, deleteMenu,
         listApis, createApi, updateApi, deleteApi,
         listRoles, createRole, updateRole, deleteRole,
         getRolePermissions, saveRolePermissions } from '../api'

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
  FolderOutlined, UserOutlined, FileOutlined, FileTextOutlined
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
  FileOutlined: '文件',
  FileTextOutlined: '文档'
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
function blankApi () { return { id: '', method: 'GET', path: '', name: '', module: '', menuIds: [], builtin: 0 } }
function openApiCreate () { apiForm.value = blankApi(); apiModal.value = true }
function openApiEdit (r) {
  apiForm.value = { id: r.id, method: r.method || 'GET', path: r.path || '', name: r.name || '', module: r.module || '',
    menuIds: Array.isArray(r.menuIds) ? [...r.menuIds] : [], builtin: r.builtin || 0 }
  apiModal.value = true
}
/** 所属菜单选择器数据源（复用菜单树；fieldNames 已映射 label:name/value:id） */
const menuSelectTree = computed(() => buildTree(menus.value))
/** 菜单 id → 名称（归属标签展示用；菜单未加载时回落 id） */
const menuNameOf = id => (menus.value.find(m => m.id === id) || {}).name || id
/**
 * 所属菜单选择值：treeCheckStrictly 会强制 labelInValue（值为 {value,label} 对象），
 * 这里归一成纯 id 数组存取，避免表单与其他逻辑感知 labelInValue。
 */
const apiMenuSelectValue = computed({
  get: () => (apiForm.value.menuIds || []).map(id => ({ value: id, label: menuNameOf(id) })),
  set: v => { apiForm.value.menuIds = (v || []).map(x => (x && typeof x === 'object' ? x.value : x)) }
})
async function saveApi () {
  const f = apiForm.value
  if (!String(f.path || '').trim()) { message.warning('请填写接口路径'); return }
  saving.value = true
  try {
    const body = { method: f.method, path: f.path, name: f.name, module: f.module, menuIds: f.menuIds || [] }
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

// ==================== 权限抽屉（单树：菜单 + 名下接口，勾选联动） ====================
const drawer = ref(false)
const drawerRole = ref(null)
const drawerSaving = ref(false)
const drawerTitle = computed(() => drawerRole.value ? `角色权限 · ${drawerRole.value.name}` : '角色权限')

/** 源状态（树的勾选由两者派生；同一接口多归属多实例天然同步） */
const checkedMenuIds = ref([])
const checkedApiIds = ref([])
const checkedMenuSet = computed(() => new Set(checkedMenuIds.value))
const checkedApiSet = computed(() => new Set(checkedApiIds.value))

/** 未归属任何现存菜单的接口（挂「其他接口」伪分组） */
const ungroupedApis = computed(() => {
  const menuIdSet = new Set(menus.value.map(m => m.id))
  return apis.value.filter(a => !(a.menuIds || []).some(mid => menuIdSet.has(mid)))
})

/** 菜单 id → 直属子菜单 id 列表 */
const menuChildrenMap = computed(() => {
  const map = new Map()
  for (const m of menus.value) {
    const pid = m.parentId || ''
    if (!map.has(pid)) map.set(pid, [])
    map.get(pid).push(m.id)
  }
  return map
})

/** 菜单子树（含自身）id 集合 */
function menuSubtreeIds (menuId) {
  const out = new Set([menuId])
  const stack = [menuId]
  while (stack.length) {
    const cur = stack.pop()
    for (const child of (menuChildrenMap.value.get(cur) || [])) {
      if (!out.has(child)) { out.add(child); stack.push(child) }
    }
  }
  return out
}

/** 给定菜单集合名下（直接归属）的全部接口 id */
function apiIdsOfMenus (menuIds) {
  const set = new Set(menuIds)
  const out = []
  for (const a of apis.value) {
    if ((a.menuIds || []).some(mid => set.has(mid))) out.push(a.id)
  }
  return out
}

const UNGROUPED_KEY = '__ungrouped__'
const apiLeafKey = (groupId, apiId) => `api:${groupId}:${apiId}`
const byPath = (x, y) => (x.path || '').localeCompare(y.path || '')

/** 权限树：菜单树 + 各菜单名下接口叶子（多归属多实例）+ 其他接口分组；节点内容由 #title 插槽渲染 */
const bindTree = computed(() => {
  const menuIdSet = new Set(menus.value.map(m => m.id))
  const byMenu = new Map()
  for (const a of apis.value) {
    for (const mid of (a.menuIds || []).filter(id => menuIdSet.has(id))) {
      if (!byMenu.has(mid)) byMenu.set(mid, [])
      byMenu.get(mid).push(a)
    }
  }
  const leafNode = (groupId, a) => ({
    key: apiLeafKey(groupId, a.id),
    kind: 'api',
    apiId: a.id,
    method: a.method,
    label: `${a.path}${a.name ? ' · ' + a.name : ''}`,
    shared: (a.menuIds || []).filter(id => menuIdSet.has(id)).length > 1
  })
  const menuNode = m => {
    const owned = (byMenu.get(m.id) || []).slice().sort(byPath)
    return {
      key: m.id,
      kind: 'menu',
      menuName: m.name,
      icon: m.icon,
      ownedCount: owned.length,
      grantedCount: owned.filter(a => checkedApiSet.value.has(a.id)).length,
      menuChecked: checkedMenuSet.value.has(m.id),
      children: [
        ...((m.children || []).map(menuNode)),
        ...owned.map(a => leafNode(m.id, a))
      ]
    }
  }
  const roots = buildTree(menus.value).map(menuNode)
  const rest = ungroupedApis.value.slice().sort(byPath)
  if (rest.length) {
    roots.push({
      key: UNGROUPED_KEY,
      kind: 'group',
      groupName: '其他接口',
      ownedCount: rest.length,
      grantedCount: rest.filter(a => checkedApiSet.value.has(a.id)).length,
      children: rest.map(a => leafNode(UNGROUPED_KEY, a))
    })
  }
  return roots
})

/** 受控勾选 keys：由源状态派生（接口在所有归属分支同步勾选；其他接口全选时组节点勾选） */
const treeCheckedKeys = computed(() => {
  const keys = [...checkedMenuIds.value]
  const menuIdSet = new Set(menus.value.map(m => m.id))
  for (const a of apis.value) {
    if (!checkedApiSet.value.has(a.id)) continue
    const owners = (a.menuIds || []).filter(mid => menuIdSet.has(mid))
    if (owners.length) { for (const mid of owners) keys.push(apiLeafKey(mid, a.id)) }
    else keys.push(apiLeafKey(UNGROUPED_KEY, a.id))
  }
  const rest = ungroupedApis.value
  if (rest.length && rest.every(a => checkedApiSet.value.has(a.id))) keys.push(UNGROUPED_KEY)
  return keys
})

/** 抽屉内搜索：命中菜单名保留整条分支；命中接口路径/名称保留其父链与叶子；
    仅影响展示，勾选状态与保存仍以源状态为准 */
const drawerKeyword = ref('')
const drawerTree = computed(() => {
  const k = drawerKeyword.value.trim().toLowerCase()
  if (!k) return bindTree.value
  const hit = s => String(s || '').toLowerCase().includes(k)
  const prune = node => {
    if (node.kind === 'api') return hit(node.label) ? node : null
    if (hit(node.kind === 'group' ? node.groupName : node.menuName)) return node
    const children = (node.children || []).map(prune).filter(Boolean)
    if (!children.length) return null
    // 分支未命中被剪枝后，「x/y 接口」按可见子集重算，避免计数与警示失真
    const leaves = children.filter(c => c.kind === 'api')
    return { ...node, children, ownedCount: leaves.length,
             grantedCount: leaves.filter(c => checkedApiSet.value.has(c.apiId)).length }
  }
  return bindTree.value.map(prune).filter(Boolean)
})

/** 勾选联动：勾菜单 → 带全子树菜单与名下接口；取消 → 接口仍属其它已勾菜单则保留；接口/组节点独立增删 */
function onTreeCheck (_keys, e) {
  const on = !!e?.checked
  const node = e?.node || {}
  if (node.kind === 'api') {
    const id = node.apiId
    if (on) { if (!checkedApiSet.value.has(id)) checkedApiIds.value = [...checkedApiIds.value, id] }
    else checkedApiIds.value = checkedApiIds.value.filter(x => x !== id)
    return
  }
  if (node.kind === 'group') {
    const ids = ungroupedApis.value.map(a => a.id)
    const set = new Set(ids)
    checkedApiIds.value = on
      ? [...new Set([...checkedApiIds.value, ...ids])]
      : checkedApiIds.value.filter(x => !set.has(x))
    return
  }
  const menuId = node.key
  const subtree = menuSubtreeIds(menuId)
  if (on) {
    checkedMenuIds.value = [...new Set([...checkedMenuIds.value, ...subtree])]
    const add = apiIdsOfMenus(subtree).filter(id => !checkedApiSet.value.has(id))
    if (add.length) checkedApiIds.value = [...checkedApiIds.value, ...add]
  } else {
    const remain = checkedMenuIds.value.filter(id => !subtree.has(id))
    checkedMenuIds.value = remain
    const remainSet = new Set(remain)
    const scope = new Set(apiIdsOfMenus(subtree))
    const keep = new Set()
    for (const a of apis.value) {
      if (!scope.has(a.id)) continue
      if ((a.menuIds || []).some(mid => remainSet.has(mid))) keep.add(a.id)
    }
    checkedApiIds.value = checkedApiIds.value.filter(id => !scope.has(id) || keep.has(id))
  }
}

/** 存量缺口：已勾选菜单（含子树）名下有接口未勾选（“菜单配了但接口没配”的 403 隐患） */
const gapCount = computed(() => {
  const scope = new Set()
  for (const mid of checkedMenuIds.value) {
    for (const id of apiIdsOfMenus(menuSubtreeIds(mid))) scope.add(id)
  }
  let n = 0
  for (const id of scope) if (!checkedApiSet.value.has(id)) n++
  return n
})

/** 一键补齐：与勾选联动同语义，把已勾选菜单子树下的菜单与接口全部勾上 */
function fillAllGaps () {
  const addMenus = new Set(checkedMenuIds.value)
  for (const mid of checkedMenuIds.value) {
    for (const id of menuSubtreeIds(mid)) addMenus.add(id)
  }
  const addApis = new Set(checkedApiIds.value)
  for (const id of apiIdsOfMenus(addMenus)) addApis.add(id)
  checkedMenuIds.value = [...addMenus]
  checkedApiIds.value = [...addApis]
}

async function openDrawer (role) {
  drawerRole.value = role
  drawer.value = true
  // 先清空再回显（抽屉 destroy-on-close，直接加载）
  checkedMenuIds.value = []
  checkedApiIds.value = []
  drawerKeyword.value = ''
  try {
    // 树的菜单/归属数据直接吃 menus、apis：从角色页签直接打开时，这两个页签的数据可能还没加载过
    await Promise.all([ensureData('menus'), ensureData('apis')])
    const r = await getRolePermissions(role.code)
    const d = (r && r.data) || {}
    // 只回显仍存在的绑定（菜单/接口被删后库里可能残留旧 id）
    const validMenus = new Set(menus.value.map(m => m.id))
    const validApis = new Set(apis.value.map(a => a.id))
    checkedMenuIds.value = ((d.menuIds) || []).filter(id => validMenus.has(id))
    checkedApiIds.value = ((d.apiIds) || []).filter(id => validApis.has(id))
  } catch (e) { message.error(e.message || '加载角色权限失败') }
}

async function saveDrawer () {
  const role = drawerRole.value
  if (!role) return
  drawerSaving.value = true
  try {
    const r = await saveRolePermissions(role.code, checkedMenuIds.value, checkedApiIds.value)
    if (r.success) { message.success('权限已保存'); drawer.value = false }
    else message.error(r.msg || '保存失败')
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
// 接口页签额外预取菜单：表格「所属菜单」列需要菜单名
watch(tab, t => {
  ensureData(t)
  if (t === 'apis') ensureData('menus')
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
.method-pill.m-post { color: #1d5bd6; background: var(--app-accent-weak); }
.method-pill.m-put { color: #9a6700; background: #fff3d6; }
.method-pill.m-delete { color: #c0392b; background: #fdebea; }
.method-pill.m-patch { color: #7c3aed; background: #f1eafd; }
.method-pill.m-all { color: var(--app-text2); background: var(--app-panel-2); }

/* 接口表「所属菜单」标签 */
.menu-tag { display: inline-flex; align-items: center; font-size: 11px; line-height: 18px; padding: 0 8px; margin: 1px 4px 1px 0; border-radius: 999px; background: var(--app-accent-weak); color: var(--app-text2); }

/* 权限树节点 */
.tree-menu { display: inline-flex; align-items: center; gap: 8px; min-width: 0; }
.tree-count { font-size: 11px; color: var(--app-text3); }
.tree-count.warn { color: #9a6700; background: #fff3d6; padding: 0 6px; border-radius: 999px; }
.tree-api { display: inline-flex; align-items: center; gap: 8px; min-width: 0; }
.tree-shared { flex: none; font-size: 10px; line-height: 16px; padding: 0 6px; border-radius: 999px; color: var(--app-text3); border: 1px solid var(--app-border); }
.gap-fill-btn { flex: none; }
.drawer-search { margin-bottom: 10px; }
.drawer-empty { padding: 24px 0; text-align: center; font-size: 12px; color: var(--app-text3); }

.app-pill.builtin { color: var(--app-accent); background: var(--app-accent-weak); }
.form-tip { margin-top: 6px; font-size: 12px; color: var(--app-text3); line-height: 1.5; }
</style>
