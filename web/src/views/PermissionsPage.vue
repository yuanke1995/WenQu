<template>
  <div class="app-page">
    <div class="app-page-head">
      <div class="head-title">
        <h1 class="app-page-title">权限管理</h1>
        <span class="head-hint-plain">角色 · 菜单 · 接口 · 接入治理</span>
      </div>
      <div class="pg-head-acts">
        <button class="app-btn ghost" type="button" @click="openEffective('')">
          <eye-outlined /> 有效权限查询
        </button>
        <button v-if="view === 'roles'" class="app-btn" type="button" @click="openRoleCreate">
          <plus-outlined /> 新建角色
        </button>
      </div>
    </div>

    <div class="app-page-body pg-body">
      <overview-strip :summary="summary" :active="view" @pick="onPick" />

      <nav class="pg-nav" role="tablist" aria-label="权限管理分区">
        <button v-for="v in navItems" :key="v.key" type="button" class="pg-nav-item"
                :class="{ on: view === v.key }" role="tab" :aria-selected="view === v.key"
                @click="setView(v.key)">
          {{ v.label }}
          <span v-if="v.count !== null" class="pg-nav-count">{{ v.count }}</span>
        </button>
        <span class="pg-nav-sep"></span>
        <span class="pg-nav-group">接入与治理</span>
        <button v-for="t in accessTabs" :key="t.key" type="button" class="pg-nav-item"
                :class="{ on: view === 'access' && accessTab === t.key }"
                role="tab" :aria-selected="view === 'access' && accessTab === t.key"
                @click="openAccess(t.key)">
          {{ t.name }}
        </button>
      </nav>

      <!-- ==================== 角色（角色中心） ==================== -->
      <div v-if="view === 'roles'" class="app-card pg-card pg-card-flush">
        <a-spin :spinning="rolesLoading">
          <role-workbench :roles="roles" :stats="roleStats" :menus="menus" :catalog="catalog"
                          :initial-code="String(route.query.code || '')"
                          @edit-role="openRoleEdit" @clone-role="cloneRole" @delete-role="askDeleteRole"
                          @effective="openEffective" @open-members="goMembers"
                          @refresh="refreshRoles" @select="onRoleSelect" />
        </a-spin>
      </div>

      <!-- ==================== 菜单 ==================== -->
      <div v-else-if="view === 'menus'" class="app-card pg-card pg-card-flush">
        <div class="app-list-bar in-card">
          <a-input v-model:value="menuKeyword" allow-clear size="small" class="pg-search"
                   placeholder="搜索菜单名称 / 路径">
            <template #prefix><search-outlined class="pg-search-ic" /></template>
          </a-input>
          <button v-for="f in MENU_FILTERS" :key="f.key" type="button" class="pg-chip"
                  :class="{ on: menuRenderFilter === f.key }" @click="menuRenderFilter = f.key">
            {{ f.label }}
          </button>
          <div class="pg-spacer"></div>
          <button class="app-btn ghost" type="button" @click="toggleAllMenus">
            {{ menuExpanded ? '收起全部' : '展开全部' }}
          </button>
          <button class="app-btn" type="button" @click="openMenuCreate"><plus-outlined /> 新建菜单</button>
        </div>

        <a-spin :spinning="menusLoading">
          <a-table :data-source="menuRows" :row-key="r => r.id" size="middle" :pagination="false" :scroll="{ x: 1040 }">
            <a-table-column title="" key="drag" width="34">
              <template #default="{ record }">
                <span class="pg-drag" draggable="true" title="拖拽到同级另一行上可调整顺序"
                      @dragstart="onDragStart(record)" @dragend="dragId = ''"
                      @dragover.prevent @drop.prevent="dropOn(record)">⠿</span>
              </template>
            </a-table-column>
            <a-table-column title="菜单" key="name">
              <template #default="{ record }">
                <span class="pg-menu" @dragover.prevent @drop.prevent="dropOn(record)">
                  <span class="pg-indent" :style="{ width: Math.max(0, (record.__depth || 0)) * 16 + 'px' }"></span>
                  <component :is="iconOf(record.icon)" class="pg-menu-ic" />
                  <span class="pg-name">{{ record.name }}</span>
                  <span v-if="record.builtin === 1" class="app-pill blue">内置</span>
                  <span v-if="record.renderAs === 'tab'" class="app-pill violet">页内 Tab</span>
                  <span v-else-if="record.renderAs === 'group'" class="app-pill muted">分组</span>
                  <span v-else-if="record.renderAs === 'hidden'" class="app-pill muted">仅权限</span>
                </span>
              </template>
            </a-table-column>
            <a-table-column title="路径" key="path">
              <template #default="{ record }">
                <code v-if="record.path" class="pg-path">{{ record.path }}</code>
                <span v-else class="pg-none">—</span>
              </template>
            </a-table-column>
            <a-table-column title="渲染位置" key="renderAs" width="100">
              <template #default="{ record }">
                <span class="app-pill" :class="record.renderAs === 'tab' ? 'violet' : 'muted'">
                  {{ renderLabel(record.renderAs) }}
                </span>
              </template>
            </a-table-column>
            <a-table-column title="排序" key="sort" width="70">
              <template #default="{ record }"><span class="pg-none">{{ record.sortOrder ?? 0 }}</span></template>
            </a-table-column>
            <a-table-column title="可见角色" key="roles" width="200">
              <template #default="{ record }">
                <template v-if="menuRolesFailed">
                  <button class="app-link-btn" @click="loadMenuRoles">取数失败 · 重试</button>
                </template>
                <template v-else>
                  <span v-for="rc in (menuRoles[record.id] || []).slice(0, 3)" :key="rc" class="pg-role-chip">{{ rc }}</span>
                  <span v-if="(menuRoles[record.id] || []).length > 3" class="pg-role-chip more">
                    +{{ menuRoles[record.id].length - 3 }}
                  </span>
                  <span v-if="!(menuRoles[record.id] || []).length" class="pg-none">无</span>
                </template>
              </template>
            </a-table-column>
            <a-table-column title="可见" key="visible" width="80">
              <template #default="{ record }">
                <a-switch size="small" :checked="record.visible !== 0" :loading="visibleSaving === record.id"
                          @change="v => toggleMenuVisible(record, v)" />
              </template>
            </a-table-column>
            <a-table-column title="操作" key="action" width="120">
              <template #default="{ record }">
                <span class="pg-inline">
                  <button class="app-link-btn" @click="openMenuEdit(record)">编辑</button>
                  <a-popconfirm v-if="record.builtin !== 1" title="删除后绑定该菜单的角色将不再看到它，确定？"
                                ok-text="删除" cancel-text="取消" @confirm="delMenu(record.id)">
                    <button class="app-link-btn danger">删除</button>
                  </a-popconfirm>
                </span>
              </template>
            </a-table-column>
          </a-table>
          <div v-if="!menuRows.length" class="pg-empty">没有匹配的菜单</div>
        </a-spin>
        <div class="pg-foot-tip">拖拽仅同级生效；隐藏父级会一并影响其 Tab</div>
      </div>

      <!-- ==================== 接口 ==================== -->
      <div v-else-if="view === 'apis'" class="app-card pg-card pg-card-flush">
        <div class="app-list-bar in-card pg-api-bar">
          <div class="pg-seg" role="tablist" aria-label="按治理状态筛选">
            <button v-for="f in API_STATUS" :key="f.key" type="button" class="pg-seg-item"
                    :class="{ on: apiStatus === f.key, warn: f.key === 'problem' && f.count() > 0 }"
                    role="tab" :aria-selected="apiStatus === f.key" @click="apiStatus = f.key">
              {{ f.label }}<span class="pg-seg-n">{{ f.count() }}</span>
            </button>
          </div>
          <div class="pg-spacer"></div>
          <div class="pg-api-tools">
            <a-input v-model:value="apiKeyword" allow-clear size="small" class="pg-search"
                     placeholder="搜索路径 / 接口名 / 授权点">
              <template #prefix><search-outlined class="pg-search-ic" /></template>
            </a-input>
            <a-select v-model:value="apiModule" size="small" show-search class="pg-module-select"
                      :options="moduleSelectOptions" option-filter-prop="label" placeholder="全部模块" />
            <button class="app-btn ghost" type="button" @click="loadApis">刷新</button>
            <button class="app-btn" type="button" @click="openApiCreate"><plus-outlined /> 登记接口</button>
          </div>
        </div>

        <a-spin :spinning="apisLoading">
          <a-table :data-source="pagedApis" :row-key="r => r.id" size="middle" :pagination="false"
                   :row-selection="{ selectedRowKeys: selectedApiIds, onChange: onSelectApis }">
            <a-table-column title="方法" key="method" width="78">
              <template #default="{ record }">
                <span class="method-pill" :class="'m-' + (record.method || 'ALL').toLowerCase()">{{ record.method }}</span>
              </template>
            </a-table-column>
            <a-table-column title="接口" key="iface">
              <template #default="{ record }">
                <div class="pg-iface">
                  <code class="pg-path" :title="record.path">{{ record.path }}</code>
                  <span v-if="record.builtin !== 1" class="app-pill violet pg-iface-src">手工</span>
                </div>
                <div v-if="record.name" class="pg-iface-sub">{{ record.name }}</div>
              </template>
            </a-table-column>
            <a-table-column title="授权点" key="perm" width="156">
              <template #default="{ record }">
                <template v-if="record.permissionCode">
                  <a-tooltip :title="record.permissionCode">
                    <b class="pg-perm-name">{{ permTitle(record.permissionCode) }}</b>
                  </a-tooltip>
                  <div class="pg-perm-sub">
                    <template v-if="permRolesFailed">
                      <button class="app-link-btn" @click="loadPermRoles">角色反查失败 · 重试</button>
                    </template>
                    <template v-else-if="!permRolesReady">
                      <span class="pg-none">读取中…</span>
                    </template>
                    <template v-else-if="rolesOfPerm(record.permissionCode).length">
                      <span class="pg-role-chip">{{ rolesOfPerm(record.permissionCode)[0] }}</span>
                      <span v-if="rolesOfPerm(record.permissionCode).length > 1" class="pg-role-chip more">
                        +{{ rolesOfPerm(record.permissionCode).length - 1 }}
                      </span>
                    </template>
                    <span v-else class="pg-none">未授予角色</span>
                  </div>
                </template>
                <span v-else class="app-pill warn">未标注</span>
              </template>
            </a-table-column>
            <a-table-column title="治理状态" key="health" width="102">
              <template #default="{ record }">
                <a-tooltip :title="governanceOf(record).tip">
                  <span class="app-pill" :class="governanceOf(record).tone">{{ governanceOf(record).label }}</span>
                </a-tooltip>
                <span v-if="governanceOf(record).more" class="pg-more">+{{ governanceOf(record).more }}</span>
              </template>
            </a-table-column>
            <a-table-column title="操作" key="action" width="92">
              <template #default="{ record }">
                <span class="pg-inline">
                  <button class="app-link-btn" @click="openApiEdit(record)">编辑</button>
                  <a-popconfirm :title="record.builtin === 1 ? '代码中仍存在的端点重启后会重新登记，确定删除？' : '确定删除该接口登记？'"
                                ok-text="删除" cancel-text="取消" @confirm="delApi(record.id)">
                    <button class="app-link-btn danger">删除</button>
                  </a-popconfirm>
                </span>
              </template>
            </a-table-column>

            <!-- 展开行：把授权点编码、完整角色与菜单列表收进按需展开，列表本身只留一列结论 -->
            <template #expandedRowRender="{ record }">
              <div class="pg-detail">
                <div class="pg-detail-item">
                  <span class="pg-detail-k">授权点</span>
                  <template v-if="record.permissionCode">
                    <b>{{ permTitle(record.permissionCode) }}</b>
                    <code class="pg-code">{{ record.permissionCode }}</code>
                  </template>
                  <span v-else class="pg-none">未标注</span>
                </div>
                <div class="pg-detail-item">
                  <span class="pg-detail-k">已授权角色</span>
                  <template v-if="rolesOfPerm(record.permissionCode || '').length">
                    <span v-for="rc in rolesOfPerm(record.permissionCode)" :key="rc" class="pg-role-chip">{{ rc }}</span>
                  </template>
                  <span v-else-if="!permRolesReady" class="pg-none">读取中…</span>
                  <span v-else-if="record.permissionCode" class="pg-none">暂未授予任何角色</span>
                  <span v-else class="pg-none">—</span>
                </div>
                <div class="pg-detail-item">
                  <span class="pg-detail-k">所属菜单</span>
                  <template v-if="(record.menuIds || []).length">
                    <span v-for="mid in (record.menuIds || [])" :key="mid" class="pg-role-chip">{{ menuNameOf(mid) }}</span>
                  </template>
                  <span v-else class="pg-none">未挂菜单</span>
                </div>
                <div class="pg-detail-item">
                  <span class="pg-detail-k">模块 / 来源</span>
                  <span>{{ record.module || '未归类' }}</span>
                  <span class="pg-none">·</span>
                  <span>{{ record.builtin === 1 ? '启动扫描登记' : '手工登记' }}</span>
                </div>
              </div>
            </template>
          </a-table>

          <div v-if="!pagedApis.length" class="pg-empty">
            {{ apiKeyword || apiModule || apiStatus !== 'all' ? '没有匹配的接口' : '暂无接口（启动时会自动扫描登记）' }}
          </div>

          <div class="pg-pager">
            <span>共 {{ filteredApis.length }} 条 · 第 {{ apiPage }} / {{ apiPageCount }} 页</span>
            <div class="pg-spacer"></div>
            <button class="pg-pbtn" type="button" :disabled="apiPage <= 1" @click="apiPage--">‹</button>
            <span class="pg-pbtn on">{{ apiPage }}</span>
            <button class="pg-pbtn" type="button" :disabled="apiPage >= apiPageCount" @click="apiPage++">›</button>
          </div>
        </a-spin>

        <div v-if="selectedApiIds.length" class="pg-bulk">
          <div class="pg-bulk-sel">
            已选 {{ selectedApiIds.length }} 个接口
            <span>· 批量归属菜单</span>
          </div>
          <div class="pg-spacer"></div>
          <button class="app-btn ghost" type="button" @click="selectedApiIds = []">取消选择</button>
          <button class="app-btn" type="button" @click="openBatchMenu">批量归属菜单</button>
        </div>
      </div>

      <!-- ==================== 接入与治理 ==================== -->
      <div v-else-if="activeAccess" class="pg-access">
        <component :is="activeAccess" />
      </div>
    </div>

    <!-- ==================== 菜单弹窗 ==================== -->
    <a-modal v-model:open="menuModal" :title="menuForm.id ? '编辑菜单' : '新建菜单'" :width="500"
             :confirm-loading="saving" ok-text="保存" cancel-text="取消" @ok="saveMenu">
      <a-form layout="vertical" style="margin-top:4px">
        <a-form-item label="父菜单（可选）">
          <a-tree-select v-model:value="menuForm.parentId" allow-clear tree-default-expand-all
                         placeholder="空 = 顶级菜单" style="width:100%"
                         :tree-data="parentTreeData" :field-names="{ label: 'name', value: 'id', children: 'children' }" />
        </a-form-item>
        <a-form-item label="渲染位置">
          <a-radio-group v-model:value="menuForm.renderAs" button-style="solid" size="small">
            <a-radio-button value="sidebar">侧栏入口</a-radio-button>
            <a-radio-button value="tab">页内 Tab</a-radio-button>
            <a-radio-button value="group">分组标题</a-radio-button>
            <a-radio-button value="hidden">仅权限</a-radio-button>
          </a-radio-group>
          <div class="form-tip">
            侧栏入口=左侧导航可点项；页内 Tab=宿主页面顶部的标签页（可单独授权）；
            分组标题=侧栏里不可点的小标题；仅权限=不出现在任何界面，只作为接口归属的容器。
          </div>
        </a-form-item>
        <a-form-item label="菜单名称">
          <a-input v-model:value="menuForm.name" :maxlength="50" placeholder="如 数据看板" />
        </a-form-item>
        <a-form-item label="路由路径">
          <a-input v-model:value="menuForm.path" placeholder="如 /dashboard（留空 = 仅作分组节点）" />
        </a-form-item>
        <a-form-item label="图标">
          <a-select v-model:value="menuForm.icon" allow-clear show-search style="width:100%"
                    placeholder="默认图标" :options="iconOptions" :filter-option="filterIcon">
            <template #option="opt">
              <span class="pg-icon-opt">
                <component :is="iconOf(opt.value)" class="pg-icon-opt-ic" />
                <span class="pg-icon-opt-name">{{ opt.label }}</span>
                <span class="pg-code">{{ opt.value }}</span>
              </span>
            </template>
            <template #optionLabel="opt">
              <span class="pg-icon-opt">
                <component :is="iconOf(opt?.value)" class="pg-icon-opt-ic" />
                <span class="pg-icon-opt-name">{{ opt?.label }}</span>
              </span>
            </template>
          </a-select>
        </a-form-item>
        <a-form-item label="排序">
          <a-input-number v-model:value="menuForm.sort" :min="0" :max="9999" style="width:100%" />
          <div class="form-tip">数字小的排前，也可拖拽调整</div>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- ==================== 接口弹窗 ==================== -->
    <a-modal v-model:open="apiModal" :title="apiForm.id ? '编辑接口' : '登记接口'" :width="520"
             :confirm-loading="saving" ok-text="保存" cancel-text="取消" @ok="saveApi">
      <a-form layout="vertical" style="margin-top:4px">
        <a-form-item label="HTTP 方法">
          <a-select v-model:value="apiForm.method" style="width:100%" :disabled="apiForm.builtin"
                    :options="['GET','POST','PUT','DELETE','PATCH','ALL'].map(m => ({ label: m, value: m }))" />
        </a-form-item>
        <a-form-item label="接口路径">
          <a-input v-model:value="apiForm.path" :disabled="apiForm.builtin"
                   placeholder="以 /api/ 开头；占位符用 {id}，如 /api/ai/agent/{id}" />
          <div v-if="apiForm.builtin" class="form-tip">扫描登记的接口以代码为准</div>
        </a-form-item>
        <a-form-item label="名称">
          <a-input v-model:value="apiForm.name" :maxlength="100" placeholder="展示用名称（可选）" />
        </a-form-item>
        <a-form-item label="模块">
          <a-input v-model:value="apiForm.module" :maxlength="50" placeholder="如 智能体（可自定义归类）" />
        </a-form-item>
        <a-form-item label="所属菜单（可多选）">
          <a-tree-select v-model:value="apiMenuSelectValue" multiple tree-checkable :tree-check-strictly="true"
                         :tree-data="menuSelectTree" :field-names="{ label: 'name', value: 'id', children: 'children' }"
                         allow-clear tree-default-expand-all :max-tag-count="4" style="width:100%"
                         placeholder="不选择 = 归入「其他接口」分组" />
          <div class="form-tip">决定接口挂在哪个菜单下，不影响鉴权</div>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- ==================== 批量归属菜单 ==================== -->
    <a-modal v-model:open="batchModal" title="批量归属菜单" :width="460"
             :confirm-loading="batchSaving" ok-text="应用" cancel-text="取消" @ok="saveBatchMenu">
      <p class="form-tip" style="margin-top:0">
        将 {{ selectedApiIds.length }} 个接口的所属菜单设为下面所选（覆盖原有归属）。归属只影响权限页分组，不影响鉴权。
      </p>
      <a-tree-select v-model:value="batchMenuIds" multiple tree-checkable :tree-check-strictly="true"
                     :tree-data="menuSelectTree" :field-names="{ label: 'name', value: 'id', children: 'children' }"
                     allow-clear tree-default-expand-all :max-tag-count="4" style="width:100%"
                     placeholder="不选择 = 清空归属" />
    </a-modal>

    <!-- ==================== 角色弹窗 ==================== -->
    <a-modal v-model:open="roleModal" :title="roleForm.isEdit ? '编辑角色' : '新建角色'" :width="480"
             :confirm-loading="saving" ok-text="保存" cancel-text="取消" @ok="saveRole">
      <a-form layout="vertical" style="margin-top:4px">
        <a-form-item label="角色编码">
          <a-input v-model:value="roleForm.code" :disabled="roleForm.isEdit"
                   placeholder="小写字母开头，如 editor（建后不可修改）" />
        </a-form-item>
        <a-form-item label="角色名称">
          <a-input v-model:value="roleForm.name" :maxlength="50" placeholder="如 内容编辑" />
        </a-form-item>
        <a-form-item label="描述（可选）">
          <a-input v-model:value="roleForm.description" :maxlength="255" placeholder="角色说明" />
        </a-form-item>
        <a-form-item label="管理员级">
          <a-switch v-model:checked="roleAdmin" :disabled="!!roleForm.builtin" checked-children="是" un-checked-children="否" />
          <div class="form-tip">管理员级默认拥有全部动作</div>
        </a-form-item>
        <a-form-item v-if="!roleForm.builtin" label="状态">
          <a-radio-group v-model:value="roleForm.status" button-style="solid" size="small">
            <a-radio-button :value="1">启用</a-radio-button>
            <a-radio-button :value="0">停用</a-radio-button>
          </a-radio-group>
          <div class="form-tip">停用后只剩问答白名单能力</div>
        </a-form-item>
        <a-form-item label="数据范围">
          <a-select v-model:value="roleForm.scopeType" style="width:100%"
                    :options="[
                      { label: '本人创建的（默认）', value: 'own' },
                      { label: '本部门创建的可读', value: 'department' },
                      { label: '本部门及下级部门创建的可读', value: 'department_and_children' },
                      { label: '全部资源可读（只读口径）', value: 'all' }]" />
          <div class="form-tip">范围只授予查看权</div>
        </a-form-item>
        <a-form-item v-if="roleForm.scopeType.startsWith('department')" label="生效部门">
          <a-select v-model:value="roleForm.scopeDepartmentId" :options="scopeDeptOptions" allow-clear
                    placeholder="空 = 按成员自己的部门判定" style="width:100%" />
        </a-form-item>
      </a-form>
    </a-modal>

    <effective-permissions-modal v-model:open="effectiveOpen" :initial-uid="effectiveUid" :catalog="catalog" />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import {
  SearchOutlined, PlusOutlined, EyeOutlined, MessageOutlined, RobotOutlined, DatabaseOutlined,
  TeamOutlined, BarChartOutlined, ExperimentOutlined, SafetyOutlined, SettingOutlined,
  AppstoreOutlined, FolderOutlined, UserOutlined, FileOutlined, FileTextOutlined
} from '@ant-design/icons-vue'
import {
  listMenus, createMenu, updateMenu, deleteMenu,
  listApis, createApi, updateApi, deleteApi,
  listRoles, createRole, updateRole, deleteRole,
  getRolePermissions, saveRolePermissionPoints,
  getPermissionCatalog, getRoleOverview, getMenuRoles, getPermissionRoles,
  listDepartments, getRoleDataScope, saveRoleDataScope
} from '../api'
import { ensureAuth } from '../utils/auth'
import { permissionTitle } from '../utils/permissions'
import OverviewStrip from '../components/permissions/OverviewStrip.vue'
import RoleWorkbench from '../components/permissions/RoleWorkbench.vue'
import EffectivePermissionsModal from '../components/permissions/EffectivePermissionsModal.vue'
import IdentityProvidersPage from './IdentityProvidersPage.vue'
import ServiceAccountsPage from './ServiceAccountsPage.vue'
import ContentSafetyPage from './ContentSafetyPage.vue'

const route = useRoute()
const router = useRouter()

/* ==================== 视图：角色中心 + 二级导航 ==================== */
const ACCESS_COMPONENTS = {
  identity: IdentityProvidersPage,
  service: ServiceAccountsPage,
  content: ContentSafetyPage
}
const accessTabs = ref([])
const activeAccess = computed(() => (activeAccessKey.value ? ACCESS_COMPONENTS[activeAccessKey.value] : null))

const tabKeyOf = path => {
  try { return new URLSearchParams(String(path || '').split('?')[1] || '').get('tab') } catch (e) { return null }
}

const view = computed({
  get: () => {
    const v = route.query.view
    if (['roles', 'menus', 'apis', 'access'].includes(v)) return v
    const t = route.query.tab
    if (['menus', 'apis', 'roles'].includes(t)) return t
    if (t && ACCESS_COMPONENTS[t]) return 'access'
    return 'roles'
  },
  set: v => {
    const query = v === 'roles' ? {} : { view: v }
    if (v === 'access') query.tab = activeAccessKey.value
    router.replace({ path: '/permissions', query })
  }
})
const activeAccessKey = computed(() => {
  const t = route.query.tab
  if (t && ACCESS_COMPONENTS[t]) return t
  return accessTabs.value.length ? accessTabs.value[0].key : 'identity'
})

function setView (v) {
  if (v === 'access') { openAccess(activeAccessKey.value); return }
  view.value = v
}
function openAccess (key) {
  router.replace({ path: '/permissions', query: { view: 'access', tab: key } })
}
function onPick (key) {
  if (key === 'pending') { message.info('空角色可在角色列表用「空角色」筛选；临期授权见成员管理顶部提醒'); return }
  if (key === 'permissions') { setView('roles'); return }
  setView(key)
}

onMounted(async () => {
  const info = await ensureAuth()
  const host = (info?.menus || []).find(m => m && m.path === '/permissions')
  const fromMenu = ((host && host.children) || [])
    .filter(c => c && c.renderAs === 'tab')
    .map(c => ({ id: c.id, key: tabKeyOf(c.path), name: c.name }))
    .filter(t => t.key && ACCESS_COMPONENTS[t.key])
  // 菜单表里没有这三条 Tab 时仍给出默认入口：它们是权限域固有的一部分，不该因为少一条菜单记录而消失
  accessTabs.value = fromMenu.length
    ? fromMenu
    : [
        { id: 'default-identity', key: 'identity', name: '认证源' },
        { id: 'default-service', key: 'service', name: '服务账号' },
        { id: 'default-content', key: 'content', name: '内容安全' }
      ]
})

/* ==================== 概览 / 导航计数 ==================== */
const overview = ref(null)
const summary = computed(() => (overview.value && overview.value.summary) || {})
const roleStats = computed(() => (overview.value && overview.value.roles) || [])

/** 二级导航计数：数据未到时显示「—」而不是 0 */
const navItems = computed(() => {
  const s = summary.value
  const n = v => (v === undefined || v === null ? null : v)
  return [
    { key: 'roles', label: '角色', count: n(s.roles) },
    { key: 'menus', label: '菜单', count: n(s.menus) },
    { key: 'apis', label: '接口', count: n(s.apis) }
  ]
})

async function loadOverview () {
  try {
    const r = await getRoleOverview()
    if (r && r.success !== false) overview.value = r.data || null
  } catch (e) { /* 概览失败保留「—」，不阻塞列表 */ }
}

/* ==================== 数据 ==================== */
const menus = ref([])
const apis = ref([])
const roles = ref([])
const catalog = ref([])
const memberCounts = ref({})
const permRoles = ref({})
const menuRoles = ref({})
const permRolesFailed = ref(false)
const permRolesReady = ref(false)
const menuRolesFailed = ref(false)

const menusLoading = ref(false)
const apisLoading = ref(false)
const rolesLoading = ref(false)
let menusLoaded = false
let apisLoaded = false
let rolesLoaded = false
let catalogLoaded = false

async function loadMenus (force = false) {
  if (menusLoaded && !force) return
  menusLoading.value = true
  try {
    const r = await listMenus()
    menus.value = (r && r.data) || []
    menusLoaded = true
  } catch (e) { message.error(e.message || '菜单加载失败') } finally { menusLoading.value = false }
}
async function loadApis (force = false) {
  if (apisLoaded && !force) return
  apisLoading.value = true
  try {
    const r = await listApis()
    apis.value = (r && r.data) || []
    apisLoaded = true
  } catch (e) { message.error(e.message || '接口加载失败') } finally { apisLoading.value = false }
  loadPermRoles()
  loadMenus()
  loadCatalog()
}
async function loadRoles (force = false) {
  if (rolesLoaded && !force) return
  rolesLoading.value = true
  try {
    const r = await listRoles()
    roles.value = (r && r.data) || []
    rolesLoaded = true
  } catch (e) { message.error(e.message || '角色加载失败') } finally { rolesLoading.value = false }
}
async function loadCatalog (force = false) {
  if (catalogLoaded && !force) return
  try {
    const r = await getPermissionCatalog()
    catalog.value = (r && r.data) || []
    catalogLoaded = true
  } catch (e) { /* 目录失败时编辑器按编码展示 */ }
}
async function loadPermRoles () {
  try {
    const r = await getPermissionRoles()
    if (r && r.success === false) throw new Error(r.msg || '反查失败')
    permRoles.value = (r && r.data) || {}
    permRolesFailed.value = false
    permRolesReady.value = true
  } catch (e) {
    permRolesFailed.value = true
    permRolesReady.value = false
  }
}
async function loadMenuRoles () {
  try {
    const r = await getMenuRoles()
    if (r && r.success === false) throw new Error(r.msg || '反查失败')
    menuRoles.value = (r && r.data) || {}
    menuRolesFailed.value = false
  } catch (e) { menuRolesFailed.value = true }
}
async function refreshRoles () {
  await Promise.all([loadRoles(true), loadOverview()])
}

watch(view, v => {
  if (v === 'roles') {
    loadRoles(); loadMenus(); loadCatalog(); loadOverview()
  } else if (v === 'menus') {
    loadMenus(); loadMenuRoles(); loadOverview()
  } else if (v === 'apis') {
    loadApis(); loadOverview()
  }
}, { immediate: true })

/* ==================== 角色 ==================== */
const roleModal = ref(false)
const roleForm = ref(blankRole())
const roleAdmin = ref(false)
const scopeDeptOptions = ref([])
let scopeDeptLoaded = false

function blankRole () {
  return { isEdit: false, code: '', name: '', description: '', status: 1, builtin: 0, scopeType: 'own', scopeDepartmentId: null }
}
async function ensureScopeDepts () {
  if (scopeDeptLoaded) return
  try {
    const r = await listDepartments()
    scopeDeptOptions.value = ((r && r.data) || []).map(d => ({ label: d.name, value: d.id }))
    scopeDeptLoaded = true
  } catch (e) { /* 部门选项失败不阻塞弹窗 */ }
}
function openRoleCreate () { roleForm.value = blankRole(); roleAdmin.value = false; roleModal.value = true; ensureScopeDepts() }
async function openRoleEdit (r) {
  roleForm.value = {
    isEdit: true, code: r.code, name: r.name || '', description: r.description || '',
    status: r.status === 0 ? 0 : 1, builtin: r.builtin || 0, scopeType: 'own', scopeDepartmentId: null
  }
  roleAdmin.value = r.adminFlag === 1
  roleModal.value = true
  ensureScopeDepts()
  try {
    const d = await getRoleDataScope(r.code)
    roleForm.value.scopeType = (d && d.data && d.data.scopeType) || 'own'
    roleForm.value.scopeDepartmentId = (d && d.data && d.data.departmentId) || null
  } catch (e) { /* 回显失败按默认 own，保存前不写回 */ }
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
    if (r && r.success === false) throw new Error(r.msg || '保存失败')
    try {
      await saveRoleDataScope(String(f.code).trim() || f.code, f.scopeType || 'own', f.scopeDepartmentId || null)
    } catch (e) { message.warning('角色已保存，数据范围未保存') }
    message.success('已保存')
    roleModal.value = false
    await refreshRoles()
  } catch (e) { message.error(e.message || '保存失败') } finally { saving.value = false }
}

async function cloneRole (role) {
  if (!role) return
  const baseCode = `${role.code}-copy`
  let code = baseCode
  let i = 2
  while (roles.value.some(r => r.code === code)) { code = `${baseCode}${i++}` }
  saving.value = true
  try {
    const r = await createRole({
      code, name: `${role.name} 副本`, description: role.description || '',
      adminFlag: role.adminFlag === 1 ? 1 : 0, status: 1
    })
    if (r && r.success === false) throw new Error(r.msg || '创建失败')
    try {
      const src = await getRolePermissions(role.code)
      const d = (src && src.data) || {}
      await saveRolePermissionPoints(code, d.menuIds || [], d.permissions || [], d.denies || [])
    } catch (e) { message.warning('角色已创建，但权限未复制，请在权限配置里手动设置') }
    message.success(`已创建 ${code}`)
    await refreshRoles()
  } catch (e) { message.error(e.message || '复制失败') } finally { saving.value = false }
}

function askDeleteRole (role) {
  if (!role || role.builtin === 1) { message.warning('内置角色不可删除'); return }
  Modal.confirm({
    title: `删除角色「${role.name}」？`,
    content: '删除后该角色的菜单与授权点绑定一并清除，成员将失去该角色。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      try {
        const r = await deleteRole(role.code)
        if (r && r.success === false) throw new Error(r.msg || '删除失败')
        message.success('已删除')
        await refreshRoles()
      } catch (e) { message.error(e.message || '删除失败') }
    }
  })
}

function onRoleSelect (code) {
  const query = { ...route.query }
  if (code) query.code = code
  else delete query.code
  router.replace({ path: '/permissions', query }).catch(() => {})
}
function goMembers () { router.push('/members') }

/* ==================== 有效权限 ==================== */
const effectiveOpen = ref(false)
const effectiveUid = ref('')
function openEffective (uid) {
  effectiveUid.value = uid || ''
  effectiveOpen.value = true
}

/* ==================== 菜单 ==================== */
const ICONS = {
  MessageOutlined, RobotOutlined, DatabaseOutlined, TeamOutlined, BarChartOutlined,
  ExperimentOutlined, SafetyOutlined, SettingOutlined, AppstoreOutlined,
  FolderOutlined, UserOutlined, FileOutlined, FileTextOutlined
}
const ICON_LABELS = {
  MessageOutlined: '消息', RobotOutlined: '机器人', DatabaseOutlined: '数据库', TeamOutlined: '团队',
  BarChartOutlined: '图表', ExperimentOutlined: '实验', SafetyOutlined: '安全', SettingOutlined: '设置',
  AppstoreOutlined: '应用', FolderOutlined: '文件夹', UserOutlined: '用户', FileOutlined: '文件', FileTextOutlined: '文档'
}
const iconOf = name => ICONS[name] || FileOutlined
const iconOptions = Object.keys(ICONS).map(k => ({ label: ICON_LABELS[k] || k, value: k }))
function filterIcon (input, option) {
  const q = String(input || '').trim().toLowerCase()
  if (!q) return true
  return String(option.label).toLowerCase().includes(q) || String(option.value).toLowerCase().includes(q)
}
function renderLabel (renderAs) {
  return { sidebar: '侧栏', tab: '页内 Tab', group: '分组', hidden: '仅权限' }[renderAs || 'sidebar'] || '侧栏'
}

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

const menuKeyword = ref('')
const menuRenderFilter = ref('all')
const menuExpanded = ref(true)
const MENU_FILTERS = [
  { key: 'all', label: '全部' },
  { key: 'sidebar', label: '侧栏' },
  { key: 'tab', label: '页内 Tab' },
  { key: 'group', label: '分组' },
  { key: 'hidden', label: '仅权限' }
]

/** 表格只接受扁平行：树按父级顺序展开，深度写进 __depth 供缩进 */
const menuRows = computed(() => {
  const k = menuKeyword.value.trim().toLowerCase()
  const tree = buildTree(menus.value)
  const pass = n => {
    const ra = n.renderAs || 'sidebar'
    if (menuRenderFilter.value !== 'all' && ra !== menuRenderFilter.value) return false
    if (!k) return true
    return String(n.name || '').toLowerCase().includes(k) || String(n.path || '').toLowerCase().includes(k)
  }
  const rows = []
  const walk = (list, depth) => {
    for (const n of list) {
      // 不能把 children 带进行数据：a-table 见到 children 会自己画展开箭头，与这里的缩进重复
      const { children, ...rest } = n
      if (pass(n)) rows.push({ ...rest, __depth: depth })
      if (children && menuExpanded.value) walk(children, depth + 1)
    }
  }
  walk(tree, 0)
  return rows
})
function toggleAllMenus () { menuExpanded.value = !menuExpanded.value }

const dragId = ref('')
function onDragStart (record) { dragId.value = record.id }

const menuModal = ref(false)
const menuForm = ref(blankMenu())
const visibleSaving = ref('')
function blankMenu () { return { id: '', parentId: undefined, name: '', icon: undefined, path: '', sort: 0, renderAs: 'sidebar' } }
function openMenuCreate () { menuForm.value = blankMenu(); menuModal.value = true }
function openMenuEdit (r) {
  menuForm.value = {
    id: r.id, parentId: r.parentId || undefined, name: r.name || '', icon: r.icon || undefined,
    path: r.path || '', sort: r.sortOrder ?? 0, renderAs: r.renderAs || 'sidebar'
  }
  menuModal.value = true
}
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
    const body = {
      parentId: f.parentId || '', name: f.name, icon: f.icon || '', path: f.path || '',
      sortOrder: f.sort, renderAs: f.renderAs || 'sidebar'
    }
    const r = f.id ? await updateMenu(f.id, body) : await createMenu(body)
    if (r && r.success === false) throw new Error(r.msg || '保存失败')
    message.success('已保存')
    menuModal.value = false
    await loadMenus(true)
    await loadOverview()
  } catch (e) { message.error(e.message || '保存失败') } finally { saving.value = false }
}
async function toggleMenuVisible (record, on) {
  const kids = menus.value.filter(m => m.parentId === record.id && m.visible !== 0)
  if (!on && kids.length) {
    const tabKids = kids.filter(c => (c.renderAs || 'sidebar') === 'tab')
    const rest = kids.filter(c => (c.renderAs || 'sidebar') !== 'tab')
    const lines = []
    if (tabKids.length) lines.push(`${tabKids.map(c => c.name).join('、')}（${tabKids.length} 个页内 Tab）会失去宿主，打开该页面将没有可用页签`)
    if (rest.length) lines.push(`${rest.map(c => c.name).join('、')} 会升到侧栏顶级继续显示`)
    Modal.confirm({
      title: `隐藏「${record.name}」？`,
      content: lines.join('；') + '。',
      okText: '隐藏', cancelText: '取消',
      onOk: () => applyMenuVisible(record, false)
    })
    return
  }
  await applyMenuVisible(record, on)
}
async function applyMenuVisible (record, on) {
  visibleSaving.value = record.id
  try {
    const r = await updateMenu(record.id, { visible: on ? 1 : 0 })
    if (r && r.success === false) throw new Error(r.msg || '操作失败')
    const src = menus.value.find(m => m.id === record.id)
    if (src) src.visible = on ? 1 : 0
    loadOverview()
  } catch (e) {
    message.error(e.message || '操作失败')
    await loadMenus(true)
  } finally { visibleSaving.value = '' }
}
async function delMenu (id) {
  try {
    const r = await deleteMenu(id)
    if (r && r.success === false) throw new Error(r.msg || '删除失败')
    message.success('已删除')
    await loadMenus(true)
    await loadOverview()
  } catch (e) { message.error(e.message || '删除失败') }
}

/* ---------- 拖拽排序（同级内，10/20/30 序列） ---------- */
async function dropOn (target) {
  const from = menus.value.find(m => m.id === dragId.value)
  dragId.value = ''
  if (!from || !target || from.id === target.id) return
  if ((from.parentId || '') !== (target.parentId || '')) {
    message.warning('只能在同一父级内调整顺序')
    return
  }
  const siblings = menus.value
    .filter(m => (m.parentId || '') === (target.parentId || ''))
    .slice()
    .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
  const fromIdx = siblings.findIndex(m => m.id === from.id)
  const toIdx = siblings.findIndex(m => m.id === target.id)
  if (fromIdx < 0 || toIdx < 0) return
  const [moved] = siblings.splice(fromIdx, 1)
  siblings.splice(toIdx, 0, moved)
  const changed = []
  siblings.forEach((m, i) => {
    const next = (i + 1) * 10
    if ((m.sortOrder ?? 0) !== next) {
      m.sortOrder = next
      changed.push(m)
    }
  })
  if (!changed.length) return
  // 树按扁平数组顺序组装，改完 sortOrder 必须重排源数组，否则界面要等下次拉取才变
  menus.value = menus.value.slice().sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
  try {
    for (const m of changed) {
      await updateMenu(m.id, { sortOrder: m.sortOrder })
    }
    message.success('顺序已保存')
  } catch (e) {
    message.error(e.message || '排序保存失败')
    await loadMenus(true)
  }
}

/* ==================== 接口 ==================== */
const apiKeyword = ref('')
const apiModule = ref('')
const apiStatus = ref('all')
const apiPage = ref(1)
const apiPageSize = 30
const selectedApiIds = ref([])

const moduleOptions = computed(() => {
  const set = new Set(apis.value.map(a => a.module).filter(Boolean))
  return [...set].sort((a, b) => a.localeCompare(b, 'zh'))
})
const moduleCount = m => apis.value.filter(a => (a.module || '') === m).length
const moduleSelectOptions = computed(() => [
  { label: `全部模块（${apis.value.length}）`, value: '' },
  ...moduleOptions.value.map(m => ({ label: `${m}（${moduleCount(m)}）`, value: m }))
])

/** 治理判定：未标注（无授权点）/ 未授权（授权点没有角色）/ 未挂菜单，三者任一即「待治理」 */
const isUnlabeled = a => !a.permissionCode
const isUnauthorized = a => !!a.permissionCode && permRolesReady.value && !rolesOfPerm(a.permissionCode).length
const isUnbound = a => !(a.menuIds || []).length
const hasProblem = a => isUnlabeled(a) || isUnauthorized(a) || isUnbound(a)

/** 每行只给一个结论，多个问题用 +N 与 tooltip 补充，避免表格里堆满彩色胶囊 */
function governanceOf (record) {
  const issues = []
  if (!record.permissionCode) {
    issues.push({ label: '未标注', tone: 'warn', tip: '没有绑定授权点，登录用户即可调用' })
  } else if (isUnauthorized(record)) {
    issues.push({ label: '未授权', tone: 'err', tip: '授权点尚未授予任何角色' })
  }
  if (!(record.menuIds || []).length) {
    issues.push({ label: '未挂菜单', tone: 'warn', tip: '未归属任何菜单，无法按业务定位' })
  }
  if (!issues.length) return { label: '已治理', tone: 'muted', tip: '已绑定授权点与菜单', more: 0 }
  return { ...issues[0], tip: issues.map(i => i.label).join(' · '), more: issues.length - 1 }
}

const statusFiltered = computed(() => {
  const k = apiKeyword.value.trim().toLowerCase()
  return apis.value.filter(a => {
    if (apiModule.value && (a.module || '') !== apiModule.value) return false
    if (apiStatus.value === 'problem' && !hasProblem(a)) return false
    if (apiStatus.value === 'unauthorized' && !(isUnlabeled(a) || isUnauthorized(a))) return false
    if (apiStatus.value === 'unbound' && !isUnbound(a)) return false
    if (apiStatus.value === 'manual' && a.builtin === 1) return false
    if (k) {
      const title = a.permissionCode ? permTitle(a.permissionCode) : ''
      const hay = `${a.path || ''} ${a.name || ''} ${a.permissionCode || ''} ${title}`.toLowerCase()
      if (!hay.includes(k)) return false
    }
    return true
  })
})
const filteredApis = computed(() => statusFiltered.value)
const apiPageCount = computed(() => Math.max(1, Math.ceil(filteredApis.value.length / apiPageSize)))
const pagedApis = computed(() => filteredApis.value.slice((apiPage.value - 1) * apiPageSize, apiPage.value * apiPageSize))

const API_STATUS = computed(() => [
  { key: 'all', label: '全部', count: () => apis.value.length },
  { key: 'problem', label: '待治理', count: () => apis.value.filter(hasProblem).length },
  {
    key: 'unauthorized', label: '未授权',
    count: () => (permRolesReady.value ? apis.value.filter(a => isUnlabeled(a) || isUnauthorized(a)).length : '—')
  },
  { key: 'unbound', label: '未挂菜单', count: () => apis.value.filter(isUnbound).length },
  { key: 'manual', label: '手工登记', count: () => apis.value.filter(a => a.builtin !== 1).length }
])

watch([apiKeyword, apiModule, apiStatus], () => { apiPage.value = 1 })

function permTitle (code) {
  const item = catalog.value.find(p => p.code === code) || { code }
  return permissionTitle(item)
}
const rolesOfPerm = code => (permRoles.value || {})[code] || []
const menuNameOf = id => (menus.value.find(m => m.id === id) || {}).name || id
function onSelectApis (keys) { selectedApiIds.value = keys }

const apiModal = ref(false)
const apiForm = ref(blankApi())
function blankApi () { return { id: '', method: 'GET', path: '', name: '', module: '', menuIds: [], builtin: 0 } }
function openApiCreate () { apiForm.value = blankApi(); apiModal.value = true }
function openApiEdit (r) {
  apiForm.value = {
    id: r.id, method: r.method || 'GET', path: r.path || '', name: r.name || '', module: r.module || '',
    menuIds: Array.isArray(r.menuIds) ? [...r.menuIds] : [], builtin: r.builtin || 0
  }
  apiModal.value = true
}
const menuSelectTree = computed(() => buildTree(menus.value))
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
    if (r && r.success === false) throw new Error(r.msg || '保存失败')
    message.success('已保存')
    apiModal.value = false
    await loadApis(true)
    await loadOverview()
  } catch (e) { message.error(e.message || '保存失败') } finally { saving.value = false }
}
async function delApi (id) {
  try {
    const r = await deleteApi(id)
    if (r && r.success === false) throw new Error(r.msg || '删除失败')
    message.success('已删除')
    await loadApis(true)
    await loadOverview()
  } catch (e) { message.error(e.message || '删除失败') }
}

const batchModal = ref(false)
const batchSaving = ref(false)
const batchMenuIds = ref([])
function openBatchMenu () { batchMenuIds.value = []; batchModal.value = true }
async function saveBatchMenu () {
  const targets = apis.value.filter(a => selectedApiIds.value.includes(a.id))
  if (!targets.length) { batchModal.value = false; return }
  batchSaving.value = true
  let ok = 0
  const failed = []
  try {
    for (const a of targets) {
      try {
        const r = await updateApi(a.id, {
          method: a.method, path: a.path, name: a.name, module: a.module, menuIds: batchMenuIds.value
        })
        if (r && r.success === false) throw new Error(r.msg || '失败')
        ok++
      } catch (e) { failed.push(a.path) }
    }
    if (failed.length) message.warning(`已更新 ${ok} 个，失败 ${failed.length} 个：${failed.slice(0, 3).join('、')}`)
    else message.success(`已更新 ${ok} 个接口的所属菜单`)
    batchModal.value = false
    selectedApiIds.value = []
    await loadApis(true)
  } finally { batchSaving.value = false }
}

/* ==================== 公共 ==================== */
const saving = ref(false)
</script>

<style scoped>
.pg-body { padding: 14px 20px 20px; display: flex; flex-direction: column; }
/* 内容超过一屏时由 .app-page-body 滚动，概览与列表卡片保持自身高度，禁止被 flex 压扁 */
.pg-body > .ov { flex: none; }
.pg-head-acts { margin-left: auto; display: flex; align-items: center; gap: 8px; flex: none; }
.head-title { display: flex; align-items: center; gap: 12px; min-width: 0; }

/* 接入与治理：三个并入页共用同一块容器，内部按它们各自的 flex 布局铺满 */
.pg-access {
  flex: 1; min-height: 460px; display: flex; flex-direction: column;
  border: 1px solid var(--app-border); border-radius: var(--app-radius);
  background: var(--app-panel); overflow: hidden;
}
.pg-access :deep(.app-page) { height: 100%; }
.pg-access :deep(.app-page-body) { padding: 12px 14px; }
.pg-access :deep(.cs-wrap) { padding: 14px 16px 12px; }

.pg-nav {
  display: flex; align-items: center; gap: 4px; margin: 12px 0 10px;
  padding: 3px; border-radius: 9px; background: var(--app-panel-2); border: 1px solid var(--app-border);
  overflow-x: auto; flex: none;
}
.pg-nav-item {
  border: 0; background: transparent; cursor: pointer; font-family: inherit;
  padding: 5px 12px; border-radius: 7px; font-size: 12.5px; color: var(--app-text2);
  display: inline-flex; align-items: center; gap: 6px; white-space: nowrap;
}
.pg-nav-item:hover { color: var(--app-text); background: var(--app-panel); }
.pg-nav-item.on { background: var(--app-panel); color: var(--app-accent); font-weight: 600; box-shadow: 0 1px 2px rgba(16,24,40,.06); }
.pg-nav-count { font-size: 10.5px; color: var(--app-text3); background: var(--app-panel-2); border-radius: 999px; padding: 0 5px; }
.pg-nav-item.on .pg-nav-count { color: var(--app-accent); background: var(--app-accent-weak); }
.pg-nav-sep { width: 1px; height: 16px; background: var(--app-border); margin: 0 6px; flex: none; }
.pg-nav-group { font-size: 10.5px; color: var(--app-text3); letter-spacing: .04em; margin-right: 2px; white-space: nowrap; }

.pg-card { padding: 0; overflow: hidden; flex: none; }
.pg-card-flush { padding: 0; }
.pg-search { width: 240px; }
.pg-search-ic { color: var(--app-text3); }
.pg-chip {
  height: 24px; padding: 0 9px; border: 1px solid var(--app-border); border-radius: 999px;
  background: var(--app-panel); color: var(--app-text2); font-size: 11px; cursor: pointer; font-family: inherit;
}
.pg-chip.on { background: var(--app-accent-weak); border-color: var(--app-accent-border); color: var(--app-accent); font-weight: 600; }
.pg-spacer { flex: 1; }
.pg-inline { display: inline-flex; align-items: center; gap: 8px; }
.pg-menu { display: inline-flex; align-items: center; gap: 7px; min-width: 0; }
.pg-indent { flex: none; }
.pg-menu-ic { color: var(--app-text3); font-size: 13px; flex: none; }
.pg-name { color: var(--app-text); }
.pg-path { font-size: 11.5px; color: var(--app-text2); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.pg-code { font-size: 10px; color: var(--app-text3); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.pg-perm-name {
  display: block; font-size: 12px; font-weight: 600; color: var(--app-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.pg-perm-sub { display: flex; align-items: center; gap: 4px; margin-top: 3px; min-width: 0; }
.pg-perm-sub .app-link-btn { font-size: 10.5px; padding: 0; color: var(--app-warn-text); }
.pg-none { color: var(--app-text3); }
.pg-drag { cursor: grab; color: var(--app-border-strong); font-size: 13px; user-select: none; }
.pg-drag:active { cursor: grabbing; }
.pg-role-chip {
  display: inline-flex; align-items: center; font-size: 10px; line-height: 18px; padding: 0 6px;
  margin: 1px 4px 1px 0; border-radius: 4px; background: var(--app-panel-2);
  border: 1px solid var(--app-border); color: var(--app-text2);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.pg-role-chip.more { color: var(--app-accent); border-color: var(--app-accent-border); background: var(--app-accent-weak); }
.pg-empty { padding: 24px 0; text-align: center; font-size: 12px; color: var(--app-text3); }
.pg-foot-tip { padding: 7px 14px 10px; font-size: 11px; color: var(--app-text3); border-top: 1px solid var(--app-border); }

.pg-api-bar { align-items: center; row-gap: 8px; }
.pg-seg {
  display: inline-flex; align-items: center; gap: 2px; padding: 2px; flex: none; max-width: 100%;
  border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel-2);
  overflow-x: auto;
}
.pg-seg-item {
  border: 0; background: transparent; cursor: pointer; font-family: inherit;
  padding: 4px 9px; border-radius: 6px; font-size: 11.5px; color: var(--app-text2);
  display: inline-flex; align-items: center; gap: 5px; white-space: nowrap;
}
.pg-seg-item:hover { color: var(--app-text); background: var(--app-panel); }
.pg-seg-item.on { background: var(--app-panel); color: var(--app-accent); font-weight: 600; box-shadow: var(--app-shadow-sm); }
.pg-seg-n { font-size: 10px; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.pg-seg-item.on .pg-seg-n { color: var(--app-accent); }
.pg-seg-item.warn:not(.on) .pg-seg-n { color: var(--app-warn); font-weight: 600; }
.pg-api-tools { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-left: auto; }
.pg-module-select { width: 168px; }
.pg-iface { display: flex; align-items: center; gap: 6px; min-width: 0; }
.pg-iface .pg-path { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pg-iface-src { font-size: 10px; padding: 2px 6px; flex: none; }
.pg-iface-sub { margin-top: 2px; font-size: 11px; color: var(--app-text3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pg-more { margin-left: 4px; font-size: 10px; color: var(--app-text3); }
.pg-detail { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 8px 20px; padding: 4px 10px 8px; }
.pg-detail-item { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; font-size: 11.5px; color: var(--app-text2); min-width: 0; }
.pg-detail-k { flex: none; min-width: 66px; font-size: 10.5px; color: var(--app-text3); }

.pg-pager { display: flex; align-items: center; gap: 5px; padding: 8px 12px; border-top: 1px solid var(--app-border); font-size: 11px; color: var(--app-text3); }
.pg-pbtn {
  min-width: 22px; height: 22px; padding: 0 6px; border: 1px solid var(--app-border); border-radius: 5px;
  background: var(--app-panel); color: var(--app-text2); font-size: 11px; cursor: pointer; font-family: inherit;
}
.pg-pbtn.on { background: var(--app-accent); border-color: var(--app-accent); color: #fff; font-weight: 600; }
.pg-pbtn:disabled { color: var(--app-border-strong); cursor: not-allowed; }
.pg-bulk {
  display: flex; align-items: center; gap: 8px; margin: 0 12px 12px; padding: 8px 11px;
  border: 1px solid var(--app-accent-border); border-radius: var(--app-radius); background: var(--app-accent-weak);
}
.pg-bulk-sel { font-size: 12px; font-weight: 600; color: var(--app-text); }
.pg-bulk-sel span { font-weight: 400; color: var(--app-text3); font-size: 11px; }

.pg-icon-opt { display: inline-flex; align-items: center; gap: 8px; min-width: 0; }
.pg-icon-opt-ic { color: var(--app-text2); font-size: 14px; flex: none; }
.pg-icon-opt-name { color: var(--app-text); }

.method-pill { display: inline-flex; align-items: center; font-size: 10.5px; line-height: 1; padding: 4px 7px; border-radius: 999px; font-weight: 500; }
.method-pill.m-get { color: #1a7f37; background: #e6f4ea; }
.method-pill.m-post { color: #1d5bd6; background: var(--app-accent-weak); }
.method-pill.m-put { color: #9a6700; background: #fff3d6; }
.method-pill.m-delete { color: #c0392b; background: #fdebea; }
.method-pill.m-patch { color: #7c3aed; background: #f1eafd; }
.method-pill.m-all { color: var(--app-text2); background: var(--app-panel-2); }

.form-tip { margin-top: 6px; font-size: 12px; color: var(--app-text3); line-height: 1.5; }

@media (max-width: 900px) {
  .pg-search { width: 100%; }
  .pg-api-tools { width: 100%; }
  .pg-module-select { flex: 1 1 140px; width: auto; }
  .pg-body { padding: 12px; }
}
</style>
