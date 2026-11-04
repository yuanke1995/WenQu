<template>
  <div class="app-page">
    <div class="app-page-head">
      <h3 class="app-page-title">权限管理</h3>
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
      <!-- 并入本域的独立管理页：有哪些/叫什么/什么顺序全由菜单表数据驱动（renderAs=tab 且父为本页），
           与「智能体工作台」同一套收编方式——管理员改名称、隐藏或收回角色授权，这里都跟着变 -->
      <a-tab-pane v-for="t in extraTabs" :key="t.key">
        <template #tab><span class="pg-tab">{{ t.name }}</span></template>
      </a-tab-pane>
    </a-tabs>

    <!-- 并入的整页组件自带页头工具栏（计数 + 说明 + 主操作），形态与上面三张表不同，
         故不套共用卡片，而是整块替掉内容区 -->
    <component v-if="activeExtra" :is="TAB_COMPONENTS[activeExtra.key]" />
    <div v-else class="app-page-body">
      <div class="app-card" style="padding:0;overflow:hidden">
        <!-- 工具栏：搜索 + 计数 + 新建（app-list-bar 全局类，卡片内嵌 in-card 变体） -->
        <div class="app-list-bar in-card">
          <a-input v-model:value="keyword" allow-clear size="small" class="rb-search"
                   :placeholder="tab === 'apis' ? '搜索路径 / 名称' : tab === 'menus' ? '搜索菜单名称 / 路径' : '搜索角色编码 / 名称'">
            <template #prefix><search-outlined class="rb-search-ic" /></template>
          </a-input>
          <a-select v-if="tab === 'apis'" v-model:value="moduleFilter" allow-clear size="small" class="rb-filter"
                    placeholder="全部模块" :options="moduleFilterOptions" />
          <span class="app-list-count">{{ countText }}</span>
          <button v-if="tab === 'menus'" class="app-btn rb-new" @click="openMenuCreate"><plus-outlined /> 新建菜单</button>
          <button v-else-if="tab === 'apis'" class="app-btn rb-new" @click="openApiCreate"><plus-outlined /> 登记接口</button>
          <button v-if="tab === 'roles'" class="app-btn" @click="openEffective">有效权限视图</button>
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
                  <!-- 渲染位置：sidebar 是默认态不加噪，其余三类必须可见——
                       尤其 tab 型不出现在侧栏，权限页里不标出来就成了「看不见的菜单」 -->
                  <span v-if="(record.renderAs || 'sidebar') === 'tab'" class="app-pill rb-ra">Tab</span>
                  <span v-else-if="(record.renderAs || 'sidebar') === 'group'" class="app-pill rb-ra">分组</span>
                  <span v-else-if="(record.renderAs || 'sidebar') === 'hidden'" class="app-pill rb-ra">容器</span>
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
            <a-table-column title="可见" key="visible" width="80">
              <template #default="{ record }">
                <!-- 显示/隐藏是这一个菜单自己的开关，不该藏在编辑弹窗里：
                     隐藏某项是日常动作（临时收口、给新页签让路），点开弹窗只为改这一格太重 -->
                <a-switch size="small" :checked="record.visible !== 0"
                          :loading="visibleSaving === record.id"
                          @change="v => toggleMenuVisible(record, v)" />
              </template>
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
            <a-table-column title="授权点 / 已授权角色" key="perm" width="230">
              <template #default="{ record }">
                <template v-if="record.permissionCode">
                  <code class="rb-path" style="margin-right:6px">{{ record.permissionCode }}</code>
                  <span v-if="rolesOfPermission(record.permissionCode).length" class="tree-count">
                    {{ rolesOfPermission(record.permissionCode).join('、') }}
                  </span>
                  <span v-else class="rb-none">未授权给任何角色</span>
                </template>
                <span v-else class="rb-none">公开 / 未标注</span>
              </template>
            </a-table-column>
            <a-table-column title="所属菜单" key="menus" width="200">
              <template #default="{ record }">
                <template v-if="(record.menuIds || []).length">
                  <span v-for="mid in record.menuIds" :key="mid" class="menu-tag">{{ menuNameOf(mid) }}</span>
                </template>
                <span v-else class="rb-none">未挂菜单</span>
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
            <a-table-column title="租户" key="tenant" width="100">
              <template #default="{ record }">
                <code v-if="record.tenantId" class="rb-uid">{{ record.tenantId }}</code>
                <span v-else class="rb-none">—</span>
              </template>
            </a-table-column>
            <a-table-column title="成员数" key="members" width="90">
              <template #default="{ record }">
                <span class="tree-count" :class="{ warn: memberCountOf(record.code) === 0 && record.builtin !== 1 }">{{ memberCountOf(record.code) }}</span>
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
          <div v-if="menuForm.renderAs === 'tab'" class="form-tip">
            Tab 型必须挂在宿主页面（如「智能体」）名下，且路径填 <code>/agents?tab=key</code> 形式。
          </div>
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
    <a-modal v-model:open="effectiveModal" title="有效权限视图" :width="560" :footer="null">
      <p class="form-tip" style="margin-top:0">
        给定登录账号，算出他实际可用的授权点集合（各角色 allow 并集 − 全局 deny；管理员级显示 admin:all-allow）。排查「为什么进不去 / 为什么进得来」从这里入手。
      </p>
      <a-input-search v-model:value="effectiveUid" placeholder="输入登录账号（uid）" enter-button="计算"
                      :loading="effectiveLoading" @search="queryEffective" />
      <div v-if="effectiveError" class="form-tip" style="color:var(--app-danger,#c0392b)">{{ effectiveError }}</div>
      <template v-if="effectiveResult">
        <div class="form-tip" style="margin:10px 0 4px">
          <b>{{ effectiveResult.uid }}</b> · 角色：
          <code v-for="r in effectiveResult.roles" :key="r" class="rb-uid" style="margin-right:6px">{{ r }}</code>
          · 判定模式：<code class="rb-uid">{{ effectiveResult.mode }}</code>
        </div>
        <a-tree v-if="effectiveGroups.length" default-expand-all :selectable="false" :tree-data="effectiveGroups">
          <template #title="node">
            <span class="tree-menu">
              <span class="rb-name">{{ node.module }}</span>
              <span class="tree-count">{{ (node.children || []).length }}</span>
            </span>
          </template>
        </a-tree>
        <div v-else class="rb-empty" style="padding:20px 0">该账号当前没有任何可用授权点</div>
      </template>
    </a-modal>
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
        <a-form-item label="数据范围">
          <a-select v-model:value="roleForm.scopeType" style="width:100%"
                    :options="[
                      { label: '本人创建的（默认，谁建归谁）', value: 'own' },
                      { label: '本部门创建的可读', value: 'department' },
                      { label: '本部门及下级部门创建的可读', value: 'department_and_children' },
                      { label: '全部资源可读（只读口径）', value: 'all' }]" />
          <div class="form-tip">范围只授予「看」（READ），管理权仍在创建者手里；资源侧没有共享入口（批次 C2）。</div>
        </a-form-item>
        <a-form-item v-if="roleForm.scopeType === 'department' || roleForm.scopeType === 'department_and_children'" label="生效部门">
          <a-select v-model:value="roleForm.scopeDepartmentId" :options="scopeDeptOptions" allow-clear
                    placeholder="空=按成员自己的部门判定" style="width:100%" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 角色权限抽屉（单树：菜单 + 名下接口；勾菜单自动带上接口） -->
    <a-drawer v-model:open="drawer" :title="drawerTitle" :width="580" destroy-on-close>
      <a-alert v-if="drawerRole && drawerRole.adminFlag === 1" type="info" show-icon style="margin-bottom:12px"
               message="管理员级角色天然拥有全部权限，以下绑定仅对普通角色生效" />
      <p class="form-tip" style="margin-top:0">
        侧栏菜单控制「看得见」，功能授权点控制「调得动」——按业务动作授权，同一动作跨端点只勾一次。未授予的授权点调用将返回 403。
      </p>
      <a-input v-model:value="drawerKeyword" allow-clear size="small" class="drawer-search"
               placeholder="搜索菜单名称 / 授权点编码">
        <template #prefix><search-outlined class="rb-search-ic" /></template>
      </a-input>
      <template v-if="drawer && (menuDrawerTreeFiltered.length || permDrawerTree.length)">
        <div class="drawer-sec-title">侧栏菜单</div>
        <a-tree v-if="menuDrawerTreeFiltered.length" checkable default-expand-all :selectable="false"
                :check-strictly="true" :checked-keys="menuCheckedKeys" @check="onMenuCheck"
                :tree-data="menuDrawerTreeFiltered">
          <template #title="node">
            <span class="tree-menu">
              <component :is="iconOf(node.icon)" class="rb-menu-ic" />
              <span class="rb-name">{{ node.name }}</span>
            </span>
          </template>
        </a-tree>
        <div class="drawer-sec-title">功能授权点</div>
        <a-tree v-if="permDrawerTree.length" checkable default-expand-all :selectable="false"
                :check-strictly="true" :checked-keys="permCheckedKeys" @check="onPermCheck"
                :tree-data="permDrawerTree">
          <template #title="node">
            <span v-if="node.kind === 'perm'" class="tree-api">
              <code class="rb-path">{{ node.label }}</code>
            </span>
            <span v-else class="tree-menu">
              <span class="rb-name">{{ node.groupName }}</span>
              <span class="tree-count" :class="{ warn: node.grantedCount < node.ownedCount }">
                {{ node.grantedCount }}/{{ node.ownedCount }}
              </span>
            </span>
          </template>
        </a-tree>
      </template>
      <div v-else-if="drawer" class="drawer-empty">
        {{ drawerKeyword ? '没有匹配的菜单或授权点' : '暂无可配置的菜单或授权点' }}
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
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { SearchOutlined, PlusOutlined, MessageOutlined, RobotOutlined, DatabaseOutlined, TeamOutlined,
         BarChartOutlined, ExperimentOutlined, SafetyOutlined, SettingOutlined, AppstoreOutlined,
         FolderOutlined, UserOutlined, FileOutlined, FileTextOutlined } from '@ant-design/icons-vue'
import { listMenus, createMenu, updateMenu, deleteMenu,
         listApis, createApi, updateApi, deleteApi,
         listRoles, createRole, updateRole, deleteRole,
         getRolePermissions,
         getPermissionCatalog, saveRolePermissionPoints, getEffectivePermissions,
         getRoleMemberCounts, getPermissionRoles, listDepartments,
         getRoleDataScope, saveRoleDataScope } from '../api'
import { ensureAuth } from '../utils/auth'
import IdentityProvidersPage from './IdentityProvidersPage.vue'
import AuditLogPage from './AuditLogPage.vue'
import ContentSafetyPage from './ContentSafetyPage.vue'

/* ==================== 页签：状态落 URL / 数据懒加载 / 各页签独立记忆筛选 ==================== */
const route = useRoute()
const router = useRouter()

/** 本页固有的三个页签：它们就是「权限管理」本身，不作为菜单行存在 */
const BASE_TABS = ['menus', 'apis', 'roles']
/** tabKey → 组件。菜单表不知道组件的存在，这张表是数据与视图之间唯一的桥（同 AgentsHubPage） */
const TAB_COMPONENTS = { identity: IdentityProvidersPage, audit: AuditLogPage, content: ContentSafetyPage }
/** 从 tab 菜单的 path（/permissions?tab=key）解析组件映射 key */
const tabKeyOf = p => {
  try { return new URLSearchParams(String(p || '').split('?')[1] || '').get('tab') } catch (e) { return null }
}

const extraTabs = ref([])
const tabKeys = computed(() => BASE_TABS.concat(extraTabs.value.map(t => t.key)))
const tab = computed({
  get: () => (tabKeys.value.includes(route.query.tab) ? route.query.tab : 'menus'),
  set: v => router.replace({ path: '/permissions', query: v === 'menus' ? {} : { tab: v } })
})
const activeExtra = computed(() => extraTabs.value.find(t => t.key === tab.value) || null)

onMounted(async () => {
  // AppLayout 挂载时已 ensureAuth，此处命中模块级缓存，不重复请求
  const info = await ensureAuth()
  const host = (info?.menus || []).find(m => m && m.path === '/permissions')
  extraTabs.value = ((host && host.children) || [])
    .filter(c => c && c.renderAs === 'tab')
    .map(c => ({ id: c.id, key: tabKeyOf(c.path), name: c.name }))
    .filter(t => t.key && TAB_COMPONENTS[t.key])  // 菜单表里有、代码里还没映射的 Tab 不渲染（升级半程态容错）
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
function blankMenu () { return { id: '', parentId: undefined, name: '', icon: undefined, path: '', sort: 0, renderAs: 'sidebar' } }
function openMenuCreate () { menuForm.value = blankMenu(); menuModal.value = true }
function openMenuEdit (r) {
  menuForm.value = { id: r.id, parentId: r.parentId || undefined, name: r.name || '', icon: r.icon || undefined, path: r.path || '', sort: r.sortOrder ?? 0, renderAs: r.renderAs || 'sidebar' }
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
    const body = { parentId: f.parentId || '', name: f.name, icon: f.icon || '', path: f.path || '', sortOrder: f.sort, renderAs: f.renderAs || 'sidebar' }
    const r = f.id ? await updateMenu(f.id, body) : await createMenu(body)
    if (r.success) { message.success('已保存'); menuModal.value = false; await refresh('menus') }
    else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}
/** 行内「可见」开关：PUT 只带 visible 一个字段（后端按字段增量更新），新建菜单默认显示 */
const visibleSaving = ref('')
async function toggleMenuVisible (record, on) {
  // 子级按 parentId 现查，不用 record.children：搜索态下那是剪过枝的子集，会漏报
  const kids = menus.value.filter(m => m.parentId === record.id && m.visible !== 0)
  if (!on && kids.length) {
    // 隐藏父级不是「整枝收起来」：服务端把可见子级上提为顶级，Tab 型子级则因失去宿主而全部失效——
    // 两种结果都反直觉，点下去之前必须点名
    const tabKids = kids.filter(c => (c.renderAs || 'sidebar') === 'tab')
    const rest = kids.filter(c => (c.renderAs || 'sidebar') !== 'tab')
    const lines = []
    if (tabKids.length) lines.push(`${tabKids.map(c => c.name).join('、')}（${tabKids.length} 个页内 Tab）会失去宿主，打开该页面将没有可用页签`)
    if (rest.length) lines.push(`${rest.map(c => c.name).join('、')} 会升到侧栏顶级继续显示`)
    Modal.confirm({
      title: `隐藏「${record.name}」？`,
      content: lines.join('；') + '。',
      okText: '隐藏',
      cancelText: '取消',
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
    if (!r.success) { message.error(r.msg || '操作失败'); await refresh('menus'); return }
    // 表格行是 menuTree 计算出的拷贝，改它不会重渲染：写回 menus 源数组，由 computed 组出新树
    const src = menus.value.find(m => m.id === record.id)
    if (src) src.visible = on ? 1 : 0
  } catch (e) {
    message.error(e.message || '操作失败')
    await refresh('menus')
  } finally { visibleSaving.value = '' }
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
function blankRole () { return { isEdit: false, code: '', name: '', description: '', status: 1, builtin: 0, scopeType: 'own', scopeDepartmentId: null } }
const scopeDeptOptions = ref([])
const scopeDeptLoaded = ref(false)
async function ensureScopeDepts () {
  if (scopeDeptLoaded.value) return
  try {
    const r = await listDepartments()
    scopeDeptOptions.value = ((r && r.data) || []).map(d => ({ label: d.name, value: d.id }))
    scopeDeptLoaded.value = true
  } catch (e) { /* 部门选项失败不阻塞弹窗（可留空按成员部门判定） */ }
}
function openRoleCreate () { roleForm.value = blankRole(); roleAdmin.value = false; roleModal.value = true; ensureScopeDepts() }
async function openRoleEdit (r) {
  roleForm.value = { isEdit: true, code: r.code, name: r.name || '', description: r.description || '', status: r.status === 0 ? 0 : 1, builtin: r.builtin || 0, scopeType: 'own', scopeDepartmentId: null }
  roleAdmin.value = r.adminFlag === 1
  roleModal.value = true
  ensureScopeDepts()
  try {
    const d = await getRoleDataScope(r.code)
    roleForm.value.scopeType = (d && d.data && d.data.scopeType) || 'own'
    roleForm.value.scopeDepartmentId = (d && d.data && d.data.departmentId) || null
  } catch (e) { /* 回显失败按默认 own */ }
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
    if (r.success) {
      // C2：数据范围随角色保存（创建的角色也要落范围行）
      try { await saveRoleDataScope(String(f.code).trim() || f.code, f.scopeType || 'own', f.scopeDepartmentId || null) } catch (e) { /* 范围保存失败不阻塞角色保存 */ }
      message.success('已保存'); roleModal.value = false; await refresh('roles')
    }
    else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}
// ==================== 反查（批次 D-1/D-2）：已授权角色 / 成员数 ====================
const memberCounts = ref({})
const permRoles = ref({})
async function loadReverseData () {
  try {
    const [a, b] = await Promise.all([getRoleMemberCounts(), getPermissionRoles()])
    memberCounts.value = (a && a.data) || {}
    permRoles.value = (b && b.data) || {}
  } catch (e) { /* 反查列失败不阻塞主表 */ }
}
const memberCountOf = code => (memberCounts.value || {})[code] || 0
const rolesOfPermission = code => (permRoles.value || {})[code] || []

// ==================== 有效权限视图（D-3 最小形态） ====================
const effectiveModal = ref(false)
const effectiveUid = ref('')
const effectiveLoading = ref(false)
const effectiveResult = ref(null)
const effectiveError = ref('')
function openEffective () {
  effectiveUid.value = ''
  effectiveResult.value = null
  effectiveError.value = ''
  effectiveModal.value = true
}
async function queryEffective () {
  const uid = String(effectiveUid.value || '').trim()
  if (!uid) { message.warning('请输入登录账号'); return }
  effectiveLoading.value = true
  effectiveError.value = ''
  try {
    const r = await getEffectivePermissions(uid)
    if (r.success) {
      effectiveResult.value = r.data
      if (r.data.permissions.includes('admin:all-allow')) {
        effectiveError.value = ''
      }
    } else { effectiveError.value = r.msg || '查询失败' }
  } catch (e) { effectiveError.value = e.message || '查询失败' }
  finally { effectiveLoading.value = false }
}
/** 有效授权点按模块分组展示（目录里没有的编码（如 admin 标记）落「其他」组） */
const effectiveGroups = computed(() => {
  if (!effectiveResult.value) return []
  const perms = effectiveResult.value.permissions || []
  const byModule = new Map()
  for (const code of perms) {
    const meta = permCatalog.value.find(p => p.code === code)
    const mod = code === 'admin:all-allow' ? '管理员级' : (meta ? (meta.module || '其他') : '未登记（退役授权点？）')
    if (!byModule.has(mod)) byModule.set(mod, [])
    byModule.get(mod).push({ key: code, title: code })
  }
  return [...byModule.entries()].map(([mod, items]) => ({ key: 'eff:' + mod, module: mod, children: items }))
})

async function delRole (code) {
  try {
    const r = await deleteRole(code)
    if (r.success) { message.success('已删除'); await refresh('roles') }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}

// ==================== 权限抽屉（菜单树 + 授权点分组树，RBAC 批次 A7） ====================
const drawer = ref(false)
const drawerRole = ref(null)
const drawerSaving = ref(false)
const drawerTitle = computed(() => drawerRole.value ? `角色权限 · ${drawerRole.value.name}` : '角色权限')

/** 源状态：菜单 id 集 + 授权点编码集（树的勾选由两者派生） */
const checkedMenuIds = ref([])
const checkedPerms = ref([])
const checkedMenuSet = computed(() => new Set(checkedMenuIds.value))
const checkedPermSet = computed(() => new Set(checkedPerms.value))

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

/** 菜单树（不再挂接口叶子：接口能力走授权点，语义粒度由后端折算回端点绑定） */
const menuDrawerTree = computed(() => {
  const menuNode = m => ({
    key: m.id,
    name: m.name,
    icon: m.icon,
    children: (m.children || []).map(menuNode)
  })
  return buildTree(menus.value).map(menuNode)
})
const menuCheckedKeys = computed(() => [...checkedMenuIds.value])

/** 授权点目录：module 分组 → 授权点（组级全选；目录来自 /role/permission-catalog） */
const permCatalog = ref([])
const permGrouped = computed(() => {
  const byModule = new Map()
  for (const p of permCatalog.value) {
    const mod = p.module || '其他'
    if (!byModule.has(mod)) byModule.set(mod, [])
    byModule.get(mod).push(p)
  }
  return [...byModule.entries()]
    .sort((a, b) => String(a[0]).localeCompare(String(b[0]), 'zh'))
    .map(([mod, items]) => ({
      key: 'module:' + mod,
      kind: 'group',
      groupName: mod,
      ownedCount: items.length,
      grantedCount: items.filter(p => checkedPermSet.value.has(p.code)).length,
      children: items
        .slice()
        .sort((a, b) => String(a.code).localeCompare(String(b.code)))
        .map(p => ({ key: p.code, kind: 'perm', label: `${p.code}${p.name && p.name !== p.code ? ' · ' + p.name : ''}` }))
    }))
})

const drawerKeyword = ref('')
const permDrawerTree = computed(() => {
  const k = drawerKeyword.value.trim().toLowerCase()
  if (!k) return permGrouped.value
  const hit = x => String(x || '').toLowerCase().includes(k)
  return permGrouped.value
    .map(g => {
      if (hit(g.groupName)) return g
      const children = g.children.filter(c => hit(c.label))
      if (!children.length) return null
      return { ...g, children,
               ownedCount: children.length,
               grantedCount: children.filter(c => checkedPermSet.value.has(c.key)).length }
    })
    .filter(Boolean)
})
const menuDrawerTreeFiltered = computed(() => {
  const k = drawerKeyword.value.trim().toLowerCase()
  if (!k) return menuDrawerTree.value
  const hit = x => String(x || '').toLowerCase().includes(k)
  const prune = node => {
    if (hit(node.name)) return { ...node }
    const children = (node.children || []).map(prune).filter(Boolean)
    return children.length ? { ...node, children } : null
  }
  return menuDrawerTree.value.map(prune).filter(Boolean)
})

/** 菜单勾选：勾/取消连同子树（菜单只管侧栏可见性，不再联动接口） */
function onMenuCheck (_keys, e) {
  const on = !!e?.checked
  const subtree = menuSubtreeIds(e?.node?.key)
  if (on) {
    checkedMenuIds.value = [...new Set([...checkedMenuIds.value, ...subtree])]
  } else {
    checkedMenuIds.value = checkedMenuIds.value.filter(id => !subtree.has(id))
  }
}

/** 授权点勾选：组节点=全选/清空该模块；叶子=单个授权点 */
function onPermCheck (_keys, e) {
  const on = !!e?.checked
  const node = e?.node || {}
  if (node.kind === 'perm') {
    if (on) { if (!checkedPermSet.value.has(node.key)) checkedPerms.value = [...checkedPerms.value, node.key] }
    else checkedPerms.value = checkedPerms.value.filter(x => x !== node.key)
    return
  }
  const ids = (node.children || []).map(c => c.key)
  const set = new Set(ids)
  checkedPerms.value = on
    ? [...new Set([...checkedPerms.value, ...ids])]
    : checkedPerms.value.filter(x => !set.has(x))
}

async function openDrawer (role) {
  drawerRole.value = role
  drawer.value = true
  // 先清空再回显（抽屉 destroy-on-close，直接加载）
  checkedMenuIds.value = []
  checkedPerms.value = []
  drawerKeyword.value = ''
  try {
    // 菜单与授权点目录：从角色页签直接打开时可能还没加载过
    await Promise.all([ensureData('menus'), loadPermCatalog()])
    const r = await getRolePermissions(role.code)
    const d = (r && r.data) || {}
    // 只回显仍存在的绑定（菜单/授权点退役后库里可能残留旧值）
    const validMenus = new Set(menus.value.map(m => m.id))
    const validPerms = new Set(permCatalog.value.map(p => p.code))
    checkedMenuIds.value = ((d.menuIds) || []).filter(id => validMenus.has(id))
    checkedPerms.value = ((d.permissions) || []).filter(code => validPerms.has(code))
  } catch (e) { message.error(e.message || '加载角色权限失败') }
}

/** 授权点目录懒加载（抽屉打开时才拉；失败留空，下次打开重试） */
async function loadPermCatalog () {
  if (permCatalog.value.length) return
  const r = await getPermissionCatalog()
  permCatalog.value = (r && r.data) || []
}

async function saveDrawer () {
  const role = drawerRole.value
  if (!role) return
  drawerSaving.value = true
  try {
    const r = await saveRolePermissionPoints(role.code, checkedMenuIds.value, checkedPerms.value)
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
  loadReverseData()
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

// 页签即数据入口：URL 直达 ?tab=apis 时只拉接口；其余固有页签稍后空闲补拉
// 接口页签额外预取菜单：表格「所属菜单」列需要菜单名
// 并入的整页 Tab 不在这本懒加载账里——它自己挂载时拉清单，切走即销毁、再切回来重拉
watch(tab, t => {
  if (!LOADERS[t]) return
  ensureData(t)
  if (t === 'apis') ensureData('menus')
  scheduleIdle(() => BASE_TABS.filter(x => x !== t).forEach(prefetch))
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

.rb-search { width: 240px; }
.rb-search-ic { color: var(--app-text3); }
.rb-filter { width: 140px; flex: none; }
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
.app-pill.rb-ra { color: #7c3aed; background: #f1eafd; }
.form-tip { margin-top: 6px; font-size: 12px; color: var(--app-text3); line-height: 1.5; }
</style>
