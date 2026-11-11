<template>
  <div class="app-page">
    <div class="app-page-head">
      <div class="head-title">
        <h3 class="app-page-title">成员管理</h3>
        <span class="head-hint-plain">管理账号、部门与角色 · 身份归属与下达</span>
      </div>
      <div class="head-acts">
        <button class="app-btn ghost" @click="goTab('invites')"><MailOutlined /> 邀请成员</button>
        <button class="app-btn" @click="openUserCreate"><PlusOutlined /> 新建用户</button>
        <!-- 租户相关动作已收口到「租户与开通」：这里只保留跳转，不在成员页复制第二套 -->
        <router-link v-if="isSuper" to="/tenants" class="app-btn ghost">租户与开通 →</router-link>
      </div>
    </div>

    <!-- 概览条：数字即筛选。每张卡点一下就是一次预设筛选，不是装饰 -->
    <div class="mem-overview">
      <button class="mem-stat" @click="preset('all')">
        <span class="sic"><TeamOutlined /></span>
        <span class="txt"><b>{{ numOrDash(overview.total) }}</b><span>在册成员</span></span>
      </button>
      <button class="mem-stat" @click="preset('active')">
        <span class="sic"><PlayCircleOutlined /></span>
        <span class="txt"><b>{{ numOrDash(overview.active) }}</b><span>启用中</span></span>
      </button>
      <button class="mem-stat" :class="{ info: overview.pendingInvites > 0 }" @click="preset('invites')">
        <span class="sic"><SendOutlined /></span>
        <span class="txt"><b>{{ numOrDash(overview.pendingInvites) }}</b><span>在途邀请</span></span>
        <span v-if="overview.pendingInvites" class="jump">看清单 ›</span>
      </button>
      <button class="mem-stat" :class="{ warn: overview.expiringGrants > 0 }" @click="preset('expiring')">
        <span class="sic"><ClockCircleOutlined /></span>
        <span class="txt"><b>{{ numOrDash(overview.expiringGrants) }}</b><span>临期授权</span></span>
        <span v-if="overview.expiringGrants" class="jump">看清单 ›</span>
      </button>
      <button class="mem-stat" @click="preset('unassigned')">
        <span class="sic"><FolderOutlined /></span>
        <span class="txt"><b>{{ numOrDash(overview.unassignedDept) }}</b><span>未分配部门</span></span>
      </button>
      <button class="mem-stat" @click="preset('today')">
        <span class="sic"><RiseOutlined /></span>
        <span class="txt"><b>{{ numOrDash(overview.loggedInToday) }}</b><span>今日登录</span></span>
      </button>
    </div>

    <!-- 页签式 Tab：与「智能体工作台」同一形态。状态落 URL(?tab=)，刷新/回退/分享可还原 -->
    <a-tabs v-model:active-key="tab" class="pg-tabs">
      <a-tab-pane key="users">
        <template #tab><span class="pg-tab">用户<i v-if="overview.total" class="pg-badge">{{ overview.total }}</i></span></template>
      </a-tab-pane>
      <a-tab-pane key="departments">
        <template #tab><span class="pg-tab">部门<i v-if="departments.length" class="pg-badge">{{ departments.length }}</i></span></template>
      </a-tab-pane>
      <a-tab-pane key="invites">
        <template #tab><span class="pg-tab">邀请与在途<i v-if="overview.pendingInvites" class="pg-badge warn">{{ overview.pendingInvites }}</i></span></template>
      </a-tab-pane>
    </a-tabs>

    <div class="app-page-body mem-body">
      <!-- ==================== 用户页签 ==================== -->
      <template v-if="tab === 'users'">
        <!-- 临期授权提醒：页顶一条，说清是谁、什么时候回收 -->
        <a-alert v-if="expiringSoon.length" type="warning" show-icon style="margin-bottom:10px">
          <template #message>
            {{ expiringSoon.length }} 项临时授权将在 7 天内到期
          </template>
          <template #description>
            <div v-for="e in expiringSoon.slice(0, 5)" :key="e.uid + e.roleCode" class="form-tip">
              <code>{{ e.uid }}</code> 的 <b>{{ roleLabel(e.roleCode) }}</b> 将于 {{ fmt(e.expireAt) }} 到期（到期后自动回收）
            </div>
          </template>
          <template #action>
            <button class="app-link-btn" @click="preset('expiring')">查看临期清单</button>
          </template>
        </a-alert>

        <!-- 到期清单读不出来要说清并给重试：静默当作「没有临期」会让保存把已有的到期洗成永久 -->
        <div v-if="expiringFailed" class="form-tip mem-expire-warn" style="margin-bottom:10px">
          临时授权到期清单没读到，编辑成员里的保存会暂时被挡住。
          <button class="app-link-btn" @click="loadExpiring">重试</button>
        </div>

        <div class="app-card mem-card" style="padding:0;overflow:hidden">
          <!-- 工具栏：搜索 + 筛选 + 计数 + 导出 -->
          <div class="mem-toolbar">
            <a-input v-model:value="filters.users.kw" allow-clear size="small" class="mem-search"
                     placeholder="搜索昵称 / 登录账号" @change="reloadUsers">
              <template #prefix><search-outlined class="mem-search-ic" /></template>
            </a-input>
            <a-select v-model:value="filters.users.status" allow-clear size="small" class="mem-filter"
                      placeholder="全部状态" :options="statusOptions" @change="reloadUsers" />
            <a-select v-model:value="filters.users.authSource" allow-clear size="small" class="mem-filter"
                      placeholder="全部认证方式" :options="authSourceOptions" @change="reloadUsers" />
            <a-select v-model:value="filters.users.departmentId" allow-clear size="small" class="mem-filter"
                      placeholder="全部部门" :options="deptFilterOptions" @change="reloadUsers" />
            <a-select v-model:value="filters.users.roleSource" allow-clear size="small" class="mem-filter"
                      placeholder="全部角色来源" :options="roleSourceOptions" @change="reloadUsers" />
            <span class="app-list-spacer"></span>
            <span class="app-list-count">共 {{ usersTotal }} 人 · 按最近登录倒序</span>
            <button class="app-btn ghost sm" @click="exportCsv"><DownloadOutlined /> 导出 CSV</button>
          </div>

          <!-- 批量条：选中即浮出。IdP 授予的角色在批量追加里同样置灰 -->
          <div v-if="selectedUids.length" class="mem-batchbar">
            <span>已选 <b>{{ selectedUids.length }}</b> 项</span>
            <span class="app-list-spacer"></span>
            <button class="app-btn ghost sm" @click="openBatch('setDepartment')"><FolderOutlined /> 批量改部门</button>
            <button class="app-btn ghost sm" @click="openBatch('addRoles')"><KeyOutlined /> 批量追加角色</button>
            <button class="app-btn ghost sm" @click="openBatch('setStatus')">批量启用 / 禁用</button>
            <button class="app-link-btn" @click="selectedUids = []">清除</button>
          </div>

          <a-spin :spinning="loading">
            <a-table :data-source="users" :row-key="r => r.uid" size="middle" :pagination="false"
                     :row-selection="{ selectedRowKeys: selectedUids, onChange: onSelect }" :scroll="{ x: 900 }">
              <a-table-column title="成员" key="member" width="210">
                <template #default="{ record }">
                  <div class="mem-user">
                    <UserAvatar :avatar="record.avatar" :name="record.username" :size="26" />
                    <div class="mem-user-txt">
                      <span class="mem-name">{{ record.username || '（未命名）' }}</span>
                      <code class="mem-uid">{{ record.uid }}</code>
                    </div>
                  </div>
                </template>
              </a-table-column>
              <a-table-column title="部门" key="department" width="120">
                <template #default="{ record }">
                  <span v-if="record.departmentId" class="mem-dept">{{ deptName(record.departmentId) }}</span>
                  <span v-else class="mem-none">未分配</span>
                </template>
              </a-table-column>
              <!-- 角色与来源：角色名 + 「手动 / 组映射·不可改」两段显示，与编辑弹窗的置灰口径同源 -->
              <a-table-column title="角色与来源" key="roles" width="230">
                <template #default="{ record }">
                  <span class="mem-roles">
                    <span v-for="rc in roleCodes(record)" :key="rc" class="role-pill" :class="'r-' + rc">
                      {{ roleLabel(rc) }}
                    </span>
                    <span v-if="hasIdpRole(record)" class="mem-src lock">组映射 · 不可改</span>
                    <span v-else class="mem-src">手动</span>
                  </span>
                </template>
              </a-table-column>
              <a-table-column title="认证方式" key="identities" width="140">
                <template #default="{ record }">
                  <span v-if="(record.identities || []).length" class="mem-auth">
                    {{ record.identities.map(i => providerName(i.providerId)).join('、') }}
                  </span>
                  <span v-else class="mem-none">本地账号</span>
                </template>
              </a-table-column>
              <a-table-column title="状态" key="status" width="92">
                <template #default="{ record }">
                  <span class="app-pill" :class="statusPill(record)">{{ statusText(record) }}</span>
                </template>
              </a-table-column>
              <a-table-column title="最近登录" key="lastLoginTime" width="140">
                <template #default="{ record }">
                  <span v-if="record.lastLoginTime" class="mem-login-time">{{ fmt(record.lastLoginTime) }}</span>
                  <span v-else class="mem-none">从未登录</span>
                </template>
              </a-table-column>
              <a-table-column title="操作" key="action" width="150">
                <template #default="{ record }">
                  <span class="mem-inline">
                    <button class="app-link-btn" @click="open360(record)">详情</button>
                    <button class="app-link-btn" @click="openUserEdit(record)">编辑</button>
                    <a-dropdown :trigger="['click']">
                      <button class="mem-more" aria-label="更多操作"><MoreOutlined /></button>
                      <template #overlay>
                        <a-menu>
                          <a-menu-item @click="openResetPwd(record)">重置密码</a-menu-item>
                          <a-menu-item @click="toggleStatus(record)">
                            {{ record.status === 0 ? '启用账号' : '禁用账号' }}
                          </a-menu-item>
                          <a-menu-item danger @click="delUser(record)">删除</a-menu-item>
                        </a-menu>
                      </template>
                    </a-dropdown>
                  </span>
                </template>
              </a-table-column>
              <template #emptyText>
                <div class="mem-empty">
                  <span>{{ filters.users.kw || activeFilterCount ? '没有匹配的成员' : '暂无成员' }}</span>
                  <span v-if="activeFilterCount" class="form-tip">当前有 {{ activeFilterCount }} 个筛选条件</span>
                  <button v-if="activeFilterCount" class="app-link-btn" @click="clearFilters">清空筛选</button>
                  <button v-else class="app-link-btn" @click="openUserCreate">新建第一个用户</button>
                </div>
              </template>
            </a-table>

            <!-- 分页：服务端分页 -->
            <div class="mem-pager">
              <span class="app-list-count">共 {{ usersTotal }} 条 · 每页 {{ pageSize }} 条</span>
              <span class="app-list-spacer"></span>
              <a-pagination size="small" :current="page" :page-size="pageSize" :total="usersTotal"
                            :show-size-changer="true" :page-size-options="['20', '50', '100']"
                            @change="onPageChange" />
            </div>
          </a-spin>
        </div>
      </template>

      <!-- ==================== 部门页签：左树右详情 ==================== -->
      <template v-else-if="tab === 'departments'">
        <div class="mem-dept-wrap">
          <div class="app-card mem-dept-left">
            <div class="mem-dept-search">
              <a-input v-model:value="deptKeyword" allow-clear size="small" placeholder="搜索部门">
                <template #prefix><search-outlined class="mem-search-ic" /></template>
              </a-input>
            </div>
            <div class="mem-dept-sec">组织架构</div>
            <div class="mem-dept-tree">
              <div v-for="n in deptTreeRows" :key="n.id" class="mem-dnode"
                   :class="{ on: selectedDeptId === n.id, dim: n.depth > 0 }"
                   :style="{ paddingLeft: (8 + n.depth * 16) + 'px' }"
                   @click="selectDept(n.id)">
                <ApartmentOutlined v-if="n.depth === 0" />
                <FolderOutlined v-else />
                <span class="nm">{{ n.name }}</span>
                <span class="ct">{{ deptCount(n.id) }} 人</span>
              </div>
            </div>
            <div class="mem-dept-sec">归属</div>
            <div class="mem-dnode" :class="{ on: selectedDeptId === '' }" @click="selectDept('')">
              <UserOutlined />
              <span class="nm">未分配</span>
              <span class="ct">{{ deptCount('') }} 人</span>
            </div>
            <div class="form-tip mem-dept-tip">
              点部门看成员清单，点人数跳到用户页签并自动筛选该部门。
            </div>
          </div>

          <div class="app-card mem-dept-right">
            <template v-if="selectedDeptId !== null">
              <div class="mem-dept-head">
                <div>
                  <div class="mem-dept-title">{{ selectedDeptId ? deptName(selectedDeptId) : '未分配' }}</div>
                  <div class="mem-dept-sub">{{ deptDesc(selectedDeptId) }}</div>
                </div>
                <span class="app-list-spacer"></span>
                <button v-if="selectedDeptId" class="app-btn ghost sm" @click="openDeptEdit(selectedDept)">
                  <EditOutlined /> 编辑部门
                </button>
                <button v-if="selectedDeptId" class="app-btn ghost sm" @click="delDept(selectedDeptId)">
                  <DeleteOutlined /> 删除部门
                </button>
                <button class="app-btn sm" @click="openDeptCreate"><PlusOutlined /> 新建部门</button>
              </div>

              <div class="mem-dept-tiles">
                <div class="mem-tile"><b>{{ deptCount(selectedDeptId) }}</b><span>成员</span></div>
                <div class="mem-tile"><b>{{ childCount(selectedDeptId) }}</b><span>子部门</span></div>
                <div class="mem-tile"><b>{{ deptRoleCounts(selectedDeptId).admin }}</b><span>管理员级成员</span></div>
                <div class="mem-tile"><b>{{ deptNeverLogged(selectedDeptId) }}</b><span>未登录过</span></div>
              </div>

              <div class="mem-dept-members">
                <div class="app-card-title">
                  部门成员 · {{ deptCount(selectedDeptId) }} 人
                  <span class="app-list-spacer"></span>
                  <button class="app-link-btn" @click="gotoUsersByDept(selectedDeptId)">在用户页签查看</button>
                </div>
                <a-table :data-source="deptMembers" :row-key="r => r.uid" size="small" :pagination="false">
                  <a-table-column title="成员" key="member">
                    <template #default="{ record }">
                      <div class="mem-user">
                        <UserAvatar :avatar="record.avatar" :name="record.username" :size="24" />
                        <div class="mem-user-txt">
                          <span class="mem-name">{{ record.username || '（未命名）' }}</span>
                          <code class="mem-uid">{{ record.uid }}</code>
                        </div>
                      </div>
                    </template>
                  </a-table-column>
                  <a-table-column title="角色" key="roles" width="220">
                    <template #default="{ record }">
                      <span class="mem-roles">
                        <span v-for="rc in roleCodes(record)" :key="rc" class="role-pill" :class="'r-' + rc">
                          {{ roleLabel(rc) }}
                        </span>
                      </span>
                    </template>
                  </a-table-column>
                  <a-table-column title="状态" key="status" width="88">
                    <template #default="{ record }">
                      <span class="app-pill" :class="statusPill(record)">{{ statusText(record) }}</span>
                    </template>
                  </a-table-column>
                  <a-table-column title="最近登录" key="lastLoginTime" width="140">
                    <template #default="{ record }">
                      <span v-if="record.lastLoginTime" class="mem-login-time">{{ fmt(record.lastLoginTime) }}</span>
                      <span v-else class="mem-none">从未登录</span>
                    </template>
                  </a-table-column>
                </a-table>
                <div class="form-tip" style="margin-top:8px">
                  删除部门前先看清人数：后端在部门下仍有人时会拒绝删除，界面前置提示而不是先删了再说。
                </div>
              </div>
            </template>
            <div v-else class="mem-empty">从左侧选择一个部门</div>
          </div>
        </div>
      </template>

      <!-- ==================== 邀请与在途页签 ==================== -->
      <template v-else>
        <div class="mem-invite-wrap">
          <div class="app-card mem-invite-left">
            <div class="app-card-title">生成邀请链接</div>
            <div class="form-tip" style="margin-top:0">
              一次性短链，对方打开即注册并加入 <b>{{ inviteTenantName }}</b>，链接只显示一次。
            </div>
            <a-form layout="vertical">
              <a-form-item label="角色（可多选）">
                <a-select v-model:value="inviteForm.roleIds" mode="multiple" :options="inviteRoleOptions"
                          placeholder="选择角色" :max-tag-count="3" />
              </a-form-item>
              <a-form-item label="部门">
                <a-select v-model:value="inviteForm.deptId" allow-clear :options="inviteDeptOptions" placeholder="不指定" />
              </a-form-item>
              <div class="mem-invite-grid">
                <a-form-item label="有效期">
                  <a-radio-group v-model:value="inviteForm.expireDays" size="small">
                    <a-radio-button :value="7">7 天</a-radio-button>
                    <a-radio-button :value="15">15 天</a-radio-button>
                    <a-radio-button :value="30">30 天</a-radio-button>
                  </a-radio-group>
                </a-form-item>
                <a-form-item label="可用次数">
                  <a-radio-group v-model:value="inviteForm.maxUses" size="small">
                    <a-radio-button :value="1">1 次</a-radio-button>
                    <a-radio-button :value="5">5 次</a-radio-button>
                    <a-radio-button :value="0">不限</a-radio-button>
                  </a-radio-group>
                </a-form-item>
              </div>
              <a-form-item label="限定邮箱（可选）">
                <a-input v-model:value="inviteForm.email" :maxlength="160" placeholder="name@company.com" />
                <div class="form-tip">当前版本不校验邮箱归属，持有链接即可注册。</div>
              </a-form-item>
            </a-form>
            <button class="app-btn mem-invite-submit" @click="submitInvite">
              <LinkOutlined /> 生成邀请链接
            </button>
            <div v-if="inviteUrl" class="mem-invite-result">
              <div class="mem-invite-result-t">邀请链接（只显示这一次）</div>
              <div class="mem-invite-link">
                <code>{{ inviteUrl }}</code>
                <button class="app-btn sm" @click="copyInvite">复制链接</button>
              </div>
              <div class="form-tip">关闭后无法再次查看明文链接。</div>
            </div>
            <div class="form-tip mem-dept-tip">
              链接只在生成后显示一次，关闭后无法再次查看明文；撤销后立即不可用。
            </div>
          </div>

          <div class="app-card mem-invite-right">
            <div class="mem-dept-head">
              <div>
                <div class="mem-dept-title">在途邀请</div>
                <div class="mem-dept-sub">状态口径：有效 / 已撤销 / 已用尽 / 已过期</div>
              </div>
              <span class="app-list-spacer"></span>
              <span class="app-list-count">共 {{ pendingInvites.length }} 条</span>
            </div>
            <a-table :data-source="pendingInvites" :row-key="r => r.id" size="small" :pagination="false"
                     :loading="invitesLoading">
              <a-table-column title="角色 / 部门" key="roles">
                <template #default="{ record }">
                  <div class="mem-name">{{ inviteRoleText(record.roleIds) }}</div>
                  <div class="form-tip">{{ record.deptId ? deptName(record.deptId) : '未指定部门' }} · 邀请人 {{ record.createdBy || '—' }}</div>
                </template>
              </a-table-column>
              <a-table-column title="已用" key="uses" width="84">
                <template #default="{ record }">{{ record.usedCount || 0 }} / {{ record.maxUses === 0 ? '不限' : record.maxUses }}</template>
              </a-table-column>
              <a-table-column title="到期" key="expiresAt" width="140">
                <template #default="{ record }">{{ fmt(record.expiresAt) }}</template>
              </a-table-column>
              <a-table-column title="状态" key="status" width="88">
                <template #default="{ record }">
                  <span class="app-pill" :class="inviteStatusPill(record.status)">{{ inviteStatusText(record.status) }}</span>
                </template>
              </a-table-column>
              <a-table-column title="操作" key="action" width="90">
                <template #default="{ record }">
                  <button v-if="record.status === 'active'" class="app-link-btn danger" @click="revokeInvite(record)">撤销</button>
                  <span v-else class="mem-none">—</span>
                </template>
              </a-table-column>
              <template #emptyText>
                <div class="mem-empty">暂无在途邀请</div>
              </template>
            </a-table>

            <div class="mem-dept-members">
              <div class="app-card-title">
                已失效邀请 · 近 30 天
                <span class="app-list-count">不可再使用，但保留痕迹</span>
              </div>
              <a-table :data-source="expiredInvites" :row-key="r => r.id" size="small" :pagination="false">
                <a-table-column title="角色 / 部门" key="roles">
                  <template #default="{ record }">
                    <div class="mem-name">{{ inviteRoleText(record.roleIds) }}</div>
                    <div class="form-tip">{{ record.deptId ? deptName(record.deptId) : '未指定部门' }} · 邀请人 {{ record.createdBy || '—' }}</div>
                  </template>
                </a-table-column>
                <a-table-column title="已用" key="uses" width="84">
                  <template #default="{ record }">{{ record.usedCount || 0 }} / {{ record.maxUses === 0 ? '不限' : record.maxUses }}</template>
                </a-table-column>
                <a-table-column title="到期" key="expiresAt" width="140">
                  <template #default="{ record }">{{ fmt(record.expiresAt) }}</template>
                </a-table-column>
                <a-table-column title="状态" key="status" width="88">
                  <template #default="{ record }">
                    <span class="app-pill" :class="inviteStatusPill(record.status)">{{ inviteStatusText(record.status) }}</span>
                  </template>
                </a-table-column>
                <template #emptyText><div class="mem-empty">近 30 天没有失效的邀请</div></template>
              </a-table>
              <div class="form-tip" style="margin-top:8px">
                已失效的邀请不删除：出问题时需要知道「当时发过什么、什么时候失效的」，审计链才完整。
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>

    <!-- 用户弹窗 -->
    <a-modal v-model:open="userModal" :title="userForm.isEdit ? '编辑用户' : '新建用户'" :width="480"
             :confirm-loading="saving" ok-text="保存" cancel-text="取消" @ok="saveUser">
      <a-form layout="vertical" style="margin-top:4px">
        <a-form-item label="登录账号" :validate-status="uidError ? 'error' : ''" :help="uidError">
          <a-input v-model:value="userForm.uid" :disabled="userForm.isEdit"
                   placeholder="请输入登录账号" @change="uidError = ''" />
        </a-form-item>
        <a-form-item label="用户昵称">
          <a-input v-model:value="userForm.username" :maxlength="100" placeholder="显示昵称，如 张三" />
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
          <a-select v-model:value="userForm.roles" :options="roleOptions" mode="multiple" style="width:100%"
                    placeholder="至少选一个角色" />
          <div class="form-tip">{{ roleTip }}</div>
        </a-form-item>
        <!-- 临时授权到期。默认折成一行摘要——「这个角色什么时候失效」不是每次编辑都要动的事，
             但不给口就等於只能永久授权 -->
        <a-form-item v-if="userForm.isEdit && manualRoleList.length" label="临时授权">
          <div v-if="!expiryKnown" class="form-tip mem-expire-warn">
            到期时间没读到，保存会被挡住。
            <button class="app-link-btn" @click="loadExpiring">重试</button>
          </div>
          <div v-else-if="!expiryOpen" class="form-tip">
            {{ expirySummary }}
            <button class="app-link-btn" style="margin-left:8px" @click="expiryOpen = true">调整到期</button>
          </div>
          <div v-else class="mem-expire-list">
            <div v-for="c in manualRoleList" :key="c" class="mem-expire-row">
              <code class="mem-expire-role">{{ c }}</code>
              <a-date-picker :value="(userForm.roleExpires || {})[c]"
                             :disabled-date="d => d && d.valueOf() < Date.now() - 86400000"
                             @update:value="v => setExpiry(c, v)"
                             show-time size="small" value-format="YYYY-MM-DDTHH:mm:ss"
                             placeholder="永久" style="width:196px" />
              <button v-if="(userForm.roleExpires || {})[c]" class="app-link-btn"
                      @click="setExpiry(c, null)">改回永久</button>
            </div>
            <div class="form-tip">到期自动回收；留空 = 永久</div>
            <button class="app-link-btn" @click="expiryOpen = false">收起</button>
          </div>
        </a-form-item>
        <a-form-item v-if="isSuper" label="所属租户">
          <a-select v-model:value="userForm.tenantId" :options="tenantOptionsList" style="width:100%" />
          <div class="form-tip">只改所属租户，已有数据留在原租户</div>
        </a-form-item>
        <a-form-item v-if="userForm.isEdit" label="状态">
          <a-radio-group v-model:value="userForm.status" button-style="solid" size="small">
            <a-radio-button :value="1">启用</a-radio-button>
            <a-radio-button :value="0">禁用</a-radio-button>
          </a-radio-group>
          <div class="form-tip">禁用后不再有部门与管理员权益</div>
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
          <a-input v-model:value="deptForm.name" :maxlength="100" placeholder="如 研发部" />
        </a-form-item>
        <a-form-item label="描述（可选）">
          <a-input v-model:value="deptForm.description" :maxlength="255" placeholder="部门说明" />
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

    <!-- 成员 360 抽屉：一个成员的四段聚合面 -->
    <Member360Drawer v-model:open="drawerOpen" :member="currentMember" :departments="departments"
                     :tenants="tenants" :roles="roles" :providers="providers" :expiring="expiringAll"
                     @edit="openUserEdit" @reset-password="openResetPwd" @toggle-status="toggleStatus"
                     @remove="delUser" @reload="reloadUsers" @goto-department="gotoUsersByDept"
                     @adjust-expiry="adjustExpiry" />

    <!-- 批量操作：变更摘要 → 执行 → 逐条结果 -->
    <BatchActionModal v-model:open="batchOpen" :action="batchAction" :members="selectedMembers"
                      :departments="departments" :roles="roles" @done="reloadUsers" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  SearchOutlined, PlusOutlined, ApartmentOutlined, MailOutlined, FolderOutlined, KeyOutlined,
  ClockCircleOutlined, SendOutlined, RiseOutlined, PlayCircleOutlined, TeamOutlined, UserOutlined,
  MoreOutlined, EditOutlined, DeleteOutlined, DownloadOutlined, LinkOutlined
} from '@ant-design/icons-vue'
import { isSuperadminSync, tenantStateSync } from '../utils/auth'
import {
  createUser, updateUser, deleteUser, resetUserPassword,
  listDepartments, createDepartment, updateDepartment, deleteDepartment,
  listRoleOptions, listIdentityProviders, unbindIdentity,
  listTenants, moveMemberTenant,
  getExpiringRoles, getUserOverview, pageUsers, exportUsersCsv,
  listTenantInvites, createTenantInvite, revokeTenantInvite
} from '../api'
import { copyText } from '../utils/clipboard'
import { publicUrl } from '../utils/publicUrl'
import UserAvatar from '../components/UserAvatar.vue'
import Member360Drawer from '../components/members/Member360Drawer.vue'
import BatchActionModal from '../components/members/BatchActionModal.vue'

/* ==================== 页签：状态落 URL ==================== */
const route = useRoute()
const router = useRouter()
const TABS = ['users', 'departments', 'invites']
const tab = computed({
  get: () => (TABS.includes(route.query.tab) ? route.query.tab : 'users'),
  set: v => router.replace({ path: '/members', query: v === 'users' ? {} : { tab: v } })
})
const goTab = t => { tab.value = t }

/* ==================== 概览条 ==================== */
const overview = ref({})
const loadOverview = async () => {
  try {
    const r = await getUserOverview()
    // 失败时保持空对象：界面显示「—」，不把整页打成错误态
    overview.value = (r && r.data) || {}
  } catch (e) {
    overview.value = {}
  }
}
const numOrDash = v => (v === undefined || v === null ? '—' : v)

/* ==================== 临时授权到期 ==================== */
const expiringAll = ref([])
const expiryKnown = ref(false)
const expiringFailed = ref(false)

const expiringSoon = computed(() => {
  const soon = Date.now() + 7 * 86400_000
  return expiringAll.value.filter(e => new Date(String(e.expireAt).replace(' ', 'T')).getTime() <= soon)
})

async function loadExpiring () {
  expiringFailed.value = false
  try {
    const r = await getExpiringRoles(3650)
    if (r && r.success) {
      expiringAll.value = r.data || []
      expiryKnown.value = true
    } else {
      expiringFailed.value = true
    }
  } catch (e) {
    expiringFailed.value = true
  }
}
onMounted(loadExpiring)

/* ==================== 数据 ==================== */
const loading = ref(false)
const saving = ref(false)
const users = ref([])
const usersTotal = ref(0)
const page = ref(1)
const pageSize = ref(20)
const departments = ref([])
const tenants = ref([])
const roles = ref([])
const providers = ref([])
const selectedUids = ref([])

/** 每个页签各记自己的筛选（此前共用一个 keyword，切页签即被清空） */
const filters = reactive({
  users: { kw: '', status: undefined, authSource: undefined, departmentId: undefined, roleSource: undefined },
  departments: { kw: '' },
  invites: {}
})
const activeFilterCount = computed(() => {
  const f = filters.users
  const n = [f.kw, f.status, f.authSource, f.departmentId, f.roleSource].filter(v => v !== undefined && v !== '').length
  return n + (unassignedOnly.value ? 1 : 0) + (loggedInTodayOnly.value ? 1 : 0)
})
const clearFilters = () => {
  filters.users.kw = ''
  filters.users.status = undefined
  filters.users.authSource = undefined
  filters.users.departmentId = undefined
  filters.users.roleSource = undefined
  unassignedOnly.value = false
  loggedInTodayOnly.value = false
  page.value = 1
  reloadUsers()
}

/** 概览卡点的预设筛选：数字即筛选 */
function preset (kind) {
  tab.value = 'users'
  const f = filters.users
  // 先把上一次的预设清掉（预设之间互斥）
  unassignedOnly.value = false
  loggedInTodayOnly.value = false
  if (kind === 'expiring') {
    // 临期授权：定位到对应的人（用 uid 关键字筛）
    const uid = expiringSoon.value[0] && expiringSoon.value[0].uid
    f.kw = uid || ''
  } else if (kind === 'invites') {
    tab.value = 'invites'
    return
  } else if (kind === 'unassigned') {
    f.departmentId = undefined
    f.kw = ''
    f.status = undefined
    f.authSource = undefined
    f.roleSource = undefined
    // 「未分配」由服务端按 department_id 过滤（口径与服务端一致，不在当页里筛）
    unassignedOnly.value = true
  } else if (kind === 'today') {
    f.kw = ''
    f.status = undefined
    f.authSource = undefined
    f.roleSource = undefined
    f.departmentId = undefined
    loggedInTodayOnly.value = true
  } else if (kind === 'active') {
    f.kw = ''
    f.authSource = undefined
    f.roleSource = undefined
    f.departmentId = undefined
    f.status = 1
  } else {
    f.kw = ''
    f.status = undefined
    f.authSource = undefined
    f.roleSource = undefined
    f.departmentId = undefined
  }
  page.value = 1
  reloadUsers()
}
/** 未分配/今日登录是服务端已有的口径，但 /user/page 的入参里没有对应字段时的页内兜底 */
const unassignedOnly = ref(false)
const loggedInTodayOnly = ref(false)

const statusOptions = [
  { label: '启用中', value: 1 },
  { label: '已禁用', value: 0 }
]
const authSourceOptions = computed(() => {
  const list = [{ label: '本地账号', value: 'local' }]
  for (const p of providers.value) list.push({ label: p.name, value: p.id })
  return list
})
const roleSourceOptions = [
  { label: '手动授予', value: 'manual' },
  { label: '组映射授予', value: 'idp' }
]
const deptFilterOptions = computed(() => departments.value.map(d => ({ label: d.name, value: d.id })))

const roleName = code => (roles.value.find(r => r.code === code) || {}).name
const roleLabel = code => roleName(code) || ({ superadmin: '超级管理员', admin: '管理员', user: '成员' }[code] || code)
const deptName = id => {
  if (!id) return '未分配'
  const d = departments.value.find(x => x.id === id)
  return d ? d.name : '—'
}
const providerName = id => {
  const p = providers.value.find(x => x.id === id)
  return (p && p.name) || id
}
const roleCodes = record => (record.roles && record.roles.length) ? record.roles : ['user']
const hasIdpRole = record => {
  const src = record.roleSources || {}
  return Object.values(src).some(v => v === 'idp')
}
const statusText = record => {
  if (record.status === 0) return '已禁用'
  if (record.lockedUntil && new Date(String(record.lockedUntil).replace(' ', 'T')) > new Date()) return '锁定中'
  return '启用'
}
const statusPill = record => {
  if (record.status === 0) return 'muted'
  if (record.lockedUntil && new Date(String(record.lockedUntil).replace(' ', 'T')) > new Date()) return 'warn'
  return 'ok'
}
const fmt = s => (s ? String(s).replace('T', ' ').slice(0, 16) : '')

/* ==================== 用户列表（服务端分页） ==================== */
async function reloadUsers () {
  loading.value = true
  try {
    const f = filters.users
    const r = await pageUsers({
      kw: f.kw || '',
      departmentId: f.departmentId || '',
      status: f.status,
      authSource: f.authSource || '',
      roleSource: f.roleSource || '',
      unassigned: unassignedOnly.value,
      loggedInToday: loggedInTodayOnly.value,
      page: page.value,
      size: pageSize.value
    })
    const data = (r && r.data) || {}
    users.value = data.items || []
    usersTotal.value = data.total || 0
  } catch (e) {
    message.error(e.message || '成员名单加载失败')
    users.value = []
    usersTotal.value = 0
  } finally {
    loading.value = false
  }
}
const onPageChange = (p, s) => {
  page.value = p
  if (s && s !== pageSize.value) pageSize.value = s
  reloadUsers()
}
const onSelect = keys => { selectedUids.value = keys }
const selectedMembers = computed(() => users.value.filter(u => selectedUids.value.includes(u.uid)))

async function exportCsv () {
  try {
    const f = filters.users
    await exportUsersCsv({
      kw: f.kw || '',
      departmentId: f.departmentId || '',
      status: f.status,
      authSource: f.authSource || '',
      roleSource: f.roleSource || '',
      unassigned: unassignedOnly.value,
      loggedInToday: loggedInTodayOnly.value
    })
    message.success('已导出')
  } catch (e) {
    message.error(e.message || '导出失败')
  }
}

/* ==================== 批量操作 ==================== */
const batchOpen = ref(false)
const batchAction = ref('setDepartment')
const openBatch = action => { batchAction.value = action; batchOpen.value = true }

/* ==================== 成员 360 ==================== */
const drawerOpen = ref(false)
const currentMember = ref({})
const open360 = record => { currentMember.value = record; drawerOpen.value = true }

/* ==================== 部门页签 ==================== */
const deptKeyword = ref('')
/** 选中部门：null=没选，''=未分配，其余=部门 id */
const selectedDeptId = ref(null)
const selectedDept = computed(() => departments.value.find(d => d.id === selectedDeptId.value))

/** 平铺 → 树（按 parentId；孤儿当顶级），带层级深度 */
const deptTreeRows = computed(() => {
  const k = deptKeyword.value.trim().toLowerCase()
  let rows = buildDeptTree(departments.value)
  const flat = []
  const walk = (list, depth) => {
    for (const n of list) {
      flat.push({ id: n.id, name: n.name, depth })
      if (n.children && n.children.length) walk(n.children, depth + 1)
    }
  }
  walk(rows, 0)
  if (k) return flat.filter(n => (n.name || '').toLowerCase().includes(k))
  return flat
})
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
const selectDept = id => { selectedDeptId.value = id }
const deptCount = id => {
  const counts = overview.value.departmentCounts || {}
  return counts[id || '__unassigned__'] || 0
}
const deptDesc = id => {
  if (!id) return '没有归属到任何部门的成员'
  const d = departments.value.find(x => x.id === id)
  const parent = d && d.parentId ? departments.value.find(x => x.id === d.parentId) : null
  const kids = departments.value.filter(x => x.parentId === id).length
  const bits = []
  if (parent) bits.push('上级：' + parent.name)
  bits.push('子部门 ' + kids + ' 个')
  if (d && d.description) bits.push(d.description)
  return bits.join(' · ')
}
const childCount = id => departments.value.filter(x => x.parentId === id).length

/** 部门成员清单：与用户页签同一口径（服务端按部门过滤） */
const deptMembers = ref([])
const loadDeptMembers = async () => {
  const id = selectedDeptId.value
  if (id === null) { deptMembers.value = []; return }
  try {
    const r = await pageUsers({ departmentId: id || '', page: 1, size: 100 })
    deptMembers.value = (r && r.data && r.data.items) || []
  } catch (e) {
    deptMembers.value = []
  }
}
const deptRoleCounts = id => {
  let admin = 0
  for (const m of deptMembers.value) {
    if ((m.roles || []).some(rc => ['admin', 'superadmin'].includes(rc) || roleIsAdmin(rc))) admin++
  }
  return { admin }
}
const roleIsAdmin = code => {
  const r = roles.value.find(x => x.code === code)
  return !!(r && r.adminFlag === 1)
}
const deptNeverLogged = id => deptMembers.value.filter(m => !m.lastLoginTime).length
const gotoUsersByDept = id => {
  tab.value = 'users'
  filters.users.departmentId = id || undefined
  filters.users.kw = ''
  filters.users.status = undefined
  filters.users.authSource = undefined
  filters.users.roleSource = undefined
  unassignedOnly.value = !id
  loggedInTodayOnly.value = false
  page.value = 1
  reloadUsers()
}
watch(() => selectedDeptId.value, loadDeptMembers)

/* ==================== 邀请与在途 ==================== */
const invitesLoading = ref(false)
const allInvites = ref([])
const inviteUrl = ref('')
const inviteForm = reactive({ roleIds: [], deptId: undefined, email: '', maxUses: 1, expireDays: 7 })
const inviteRoleOptions = computed(() => roles.value.map(r => ({ label: r.name || r.code, value: r.code })))
const inviteDeptOptions = computed(() => departments.value.map(d => ({ label: d.name, value: d.id })))
const pendingInvites = computed(() => allInvites.value.filter(r => r.status === 'active'))
const expiredInvites = computed(() => allInvites.value.filter(r => r.status !== 'active'))
const inviteRoleText = raw => {
  const codes = String(raw || '').split(',').filter(Boolean)
  if (!codes.length) return '成员'
  return codes.map(c => roleLabel(c)).join('、')
}
const inviteStatusText = s => ({ active: '有效', revoked: '已撤销', exhausted: '已用尽', expired: '已过期' }[s] || s || '有效')
const inviteStatusPill = s => ({ active: 'ok', revoked: 'err', exhausted: 'muted', expired: 'muted' }[s] || 'muted')

async function loadInvites () {
  invitesLoading.value = true
  try {
    const r = await listTenantInvites()
    allInvites.value = (r && r.data) || []
  } catch (e) {
    allInvites.value = []
  } finally {
    invitesLoading.value = false
  }
}
async function submitInvite () {
  try {
    const body = {
      roleIds: (inviteForm.roleIds || []).join(','),
      deptId: inviteForm.deptId || '',
      email: (inviteForm.email || '').trim(),
      maxUses: inviteForm.maxUses,
      expireDays: inviteForm.expireDays
    }
    const r = await createTenantInvite(body)
    if (!r || !r.success) { message.error((r && r.msg) || '生成失败'); return }
    const path = (r.data && r.data.path) || ''
    inviteUrl.value = path ? publicUrl(path) : ''
    message.success('邀请链接已生成（只显示这一次）')
    loadInvites()
    loadOverview()
  } catch (e) {
    message.error(e.message || '生成失败，请稍后再试')
  }
}
async function copyInvite () {
  await copyText(inviteUrl.value, '链接已复制')
}
async function revokeInvite (record) {
  try {
    const r = await revokeTenantInvite(record.id)
    if (r && r.success === false) { message.error(r.msg || '撤销失败'); return }
    message.success('已撤销')
    loadInvites()
    loadOverview()
  } catch (e) {
    message.error(e.message || '撤销失败')
  }
}

/* ==================== 用户编辑（保留既有语义） ==================== */
const userModal = ref(false)
const userForm = ref(blankUser())
const uidError = ref('')
const pwdError = ref('')
function blankUser () { return { isEdit: false, uid: '', username: '', password: '', confirm: '', departmentId: undefined, roles: ['user'], status: 1, tenantId: 'default', origTenant: 'default', roleExpires: {}, roleSources: {} } }
function openUserCreate () { userForm.value = blankUser(); uidError.value = ''; expiryOpen.value = false; userModal.value = true }
function openUserEdit (r) {
  userForm.value = {
    isEdit: true,
    uid: r.uid,
    username: r.username || '',
    departmentId: r.departmentId || undefined,
    roles: (r.roles && r.roles.length ? r.roles : ['user']).slice(),
    status: r.status === 0 ? 0 : 1,
    tenantId: r.tenantId || 'default',
    origTenant: r.tenantId || 'default',
    roleSources: r.roleSources || {},
    roleExpires: expiryOf(r.uid)
  }
  expiryOpen.value = Object.keys(userForm.value.roleExpires).length > 0
  uidError.value = ''
  userModal.value = true
}
async function saveUser () {
  const f = userForm.value
  uidError.value = ''
  pwdError.value = ''
  if (!f.isEdit) {
    const uid = String(f.uid || '').trim()
    if (!uid) { uidError.value = '请填写登录账号'; return }
    if (!/^[A-Za-z0-9_@.\-]+$/.test(uid)) { uidError.value = '仅允许字母、数字及 _ @ . -'; return }
    if (!f.password || f.password.length < 6) { pwdError.value = '密码至少 6 位'; return }
    if (f.password !== f.confirm) { pwdError.value = '两次输入的密码不一致'; return }
  }
  saving.value = true
  try {
    // 到期清单没读到时不保存：后端按「全量替换手动角色」写，缺了 roleExpires 就是把已有的临期改成永久
    if (f.isEdit && !expiryKnown.value) {
      message.error('临时授权的到期时间没读到，本次不能保存（保存会把已有的临期改成永久）。请重试。')
      return
    }
    const base = { username: f.username, departmentId: f.departmentId || '', roles: (f.roles && f.roles.length ? f.roles : ['user']) }
    const roleExpires = {}
    for (const c of manualRoleList.value) if (f.roleExpires && f.roleExpires[c]) roleExpires[c] = f.roleExpires[c]
    const r = f.isEdit
      ? await updateUser(f.uid, { ...base, status: f.status, roleExpires })
      : await createUser({ ...base, uid: String(f.uid).trim(), password: f.password })
    if (!r.success) { message.error(r.msg || '保存失败'); return }
    // 归属变更是另一个动作（只改账号归属，不动他已产出的资源），所以单独一步、单独报错
    if (f.isEdit && isSuper.value && f.tenantId && f.tenantId !== f.origTenant) {
      const mv = await moveMemberTenant(f.uid, f.tenantId)
      if (!mv.success) { message.error(mv.msg || '归属变更失败'); return }
      await loadTenants()
    }
    message.success('已保存')
    userModal.value = false
    await reloadUsers()
    loadOverview()
    loadExpiring()
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}
async function delUser (record) {
  try {
    const r = await deleteUser(record.uid)
    if (r.success) { message.success('已删除'); await reloadUsers(); loadOverview() }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}
async function toggleStatus (record) {
  try {
    const r = await updateUser(record.uid, {
      username: record.username || '',
      departmentId: record.departmentId || '',
      roles: roleCodes(record),
      status: record.status === 0 ? 1 : 0
    })
    if (!r.success) { message.error(r.msg || '操作失败'); return }
    message.success(record.status === 0 ? '已启用' : '已禁用')
    await reloadUsers()
    loadOverview()
  } catch (e) { message.error(e.message || '操作失败') }
}
/** 逐源解绑身份：只清除该账号在指定认证源的绑定，其他认证源不动 */
async function unbind (uid, providerId) {
  try {
    const r = await unbindIdentity(uid, providerId)
    if (r.success) { message.success('已解绑'); await reloadUsers() }
    else message.error(r.msg || '解绑失败')
  } catch (e) { message.error(e.message || '解绑失败') }
}

/* ==================== 临时授权（编辑弹窗口径） ==================== */
function expiryOf (uid) {
  const out = {}
  for (const e of expiringAll.value) {
    if (e.uid !== uid || e.grantedBy === 'idp') continue
    if (e.roleCode && e.expireAt) out[e.roleCode] = String(e.expireAt).slice(0, 19)
  }
  return out
}
const expiryOpen = ref(false)
const manualRoleList = computed(() => {
  const f = userForm.value
  return (f.roles || []).filter(c => c && (!f.roleSources || f.roleSources[c] !== 'idp'))
})
const expirySummary = computed(() => {
  const f = userForm.value
  const hits = manualRoleList.value.filter(c => f.roleExpires && f.roleExpires[c])
  if (!hits.length) return '全部永久有效'
  return `${hits.length} 个角色是临期的：` + hits.map(c => `${c} 至 ${String(f.roleExpires[c]).replace('T', ' ').slice(0, 16)}`).join('、')
})
function setExpiry (code, v) {
  const f = userForm.value
  const next = { ...(f.roleExpires || {}) }
  if (v) next[code] = v
  else delete next[code]
  f.roleExpires = next
}
/** 抽屉里点「调整到期」：直接打开编辑弹窗并展开到期区 */
function adjustExpiry (record) {
  openUserEdit(record)
  expiryOpen.value = true
}

/* ==================== 重置密码 ==================== */
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

/* ==================== 部门（写操作） ==================== */
const deptModal = ref(false)
const deptForm = ref(blankDept())
function blankDept () { return { id: '', parentId: undefined, name: '', description: '' } }
function openDeptCreate () { deptForm.value = blankDept(); deptModal.value = true }
function openDeptEdit (r) {
  if (!r) return
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
    const r = f.id ? await updateDepartment(f.id, body) : await createDepartment(body)
    if (r.success) { message.success('已保存'); deptModal.value = false; await loadDepartments(); loadOverview() }
    else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}
async function delDept (id) {
  try {
    const r = await deleteDepartment(id)
    if (r.success) { message.success('已删除'); await loadDepartments(); loadOverview() }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}

/* ==================== 加载 ==================== */
const isSuper = computed(() => isSuperadminSync())
const defaultTenant = 'default'
const tenantOptionsList = computed(() => tenants.value
  .filter(t => t.status !== 0)
  .map(t => ({ value: t.id, label: `${t.name}（${t.id}）` })))
const inviteTenantName = computed(() => {
  const t = tenantStateSync()
  return t ? t.name : '当前租户'
})
const deptOptions = computed(() => departments.value.map(d => ({ label: d.name, value: d.id })))
const roleOptions = computed(() => roles.value.map(r => {
  const src = (userForm.value.roleSources || {})[r.code]
  const idpLocked = src === 'idp'
  return {
    label: r.name + (idpLocked ? '（组映射授予，不可改）' : ''),
    value: r.code,
    disabled: idpLocked
  }
}))
const roleTip = computed(() => {
  const picked = userForm.value.roles || []
  if (!picked.length) return '至少保留一个角色，否则按「成员」权限判定。'
  if (picked.some(c => (roles.value.find(x => x.code === c) || {}).adminFlag === 1)) {
    return '已包含管理员级角色：该用户可访问全部管理功能（并集放大权限）。'
  }
  return '手动角色与 IdP 组映射角色并存（并集判定）；组映射授予的角色在成员管理中不可改。'
})

async function loadDepartments () {
  const r = await listDepartments()
  departments.value = (r && r.data) || []
}
async function loadRoles () {
  const r = await listRoleOptions()
  roles.value = (r && r.data) || []
}
async function loadProviders () {
  // 统一认证源表（OIDC/SAML/SCIM/LDAP 四类同一份数据）——「认证方式」列的 providerId 都在这里
  const r = await listIdentityProviders()
  providers.value = ((r && r.data) || []).map(p => ({ id: p.id, name: p.name }))
}
async function loadTenants () {
  const r = await listTenants()
  tenants.value = (r && r.data) || []
}

/** 页签 → 该页签渲染真正要用的数据 */
const TAB_DEPS = {
  users: ['users', 'departments', 'roles', 'providers', 'tenants'],
  departments: ['departments', 'users', 'roles'],
  invites: ['invites', 'roles', 'departments', 'tenants']
}
const loaded = reactive({ users: false, departments: false, roles: false, providers: false, tenants: false, invites: false })
const LOADERS = {
  users: reloadUsers,
  departments: loadDepartments,
  roles: loadRoles,
  providers: loadProviders,
  tenants: loadTenants,
  invites: loadInvites
}
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

watch(tab, t => {
  loadData(TAB_DEPS[t])
  if (t === 'invites') loadInvites()
}, { immediate: true })
onMounted(loadOverview)
</script>

<style scoped>
/* 页签式 Tab（与「智能体工作台」的 .hub-tabs 同形）：只作导航，内容仍在下方共用卡片里按 tab 渲染 */
.pg-tabs { flex: none; }
.pg-tabs :deep(.ant-tabs-nav) { margin: 0; padding: 4px 20px 0; background: var(--app-panel); }
.pg-tabs :deep(.ant-tabs-nav::before) { border-color: var(--app-border); }
.pg-tabs :deep(.ant-tabs-tab) { font-size: 13px; padding: 10px 2px; }
.pg-tab { display: inline-flex; align-items: center; }
.pg-badge { font-style: normal; font-size: 11px; line-height: 16px; margin-left: 6px;
  padding: 0 6px; border-radius: 999px; background: var(--app-accent-weak); color: var(--app-text3); }
.pg-tabs :deep(.ant-tabs-tab-active) .pg-badge { background: var(--app-accent); color: #fff; }
.pg-badge.warn { background: var(--app-warn-weak) !important; color: var(--app-warn-text) !important; }
.pg-tabs :deep(.ant-tabs-tab-active) .pg-badge.warn { background: var(--app-warn) !important; color: #fff; }

.mem-body { padding: 12px 20px 16px; }

/* ---------------- 概览条 ---------------- */
/* 卡片内纵向排：图标与数字一行，文案另起一行。
   横排（图标+数字+文案+跳转挤一行）在窄栏下会把文案压成省略号——数字看得见、文案看不见，等于白做。 */
.mem-overview { flex: none; display: flex; gap: 9px; padding: 0 20px 12px; }
.mem-stat { flex: 1 1 0; min-width: 0; background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 8px;
  padding: 8px 11px; display: flex; flex-direction: column; align-items: flex-start; gap: 2px;
  text-align: left; cursor: pointer; font-family: inherit; transition: border-color .15s; position: relative; }
.mem-stat:hover { border-color: var(--app-accent-border); }
.mem-stat .sic { width: 26px; height: 26px; border-radius: 7px; background: var(--app-panel-2); color: var(--app-text2);
  display: inline-flex; align-items: center; justify-content: center; flex: none; }
.mem-stat .txt { min-width: 0; width: 100%; }
.mem-stat .txt b { display: block; font-size: 17px; font-weight: 650; line-height: 1.15; font-variant-numeric: tabular-nums; }
.mem-stat .txt span { font-size: 10.5px; color: var(--app-text3); display: block; margin-top: 1px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* 跳转提示：悬停才出现，平时不占位（占位会把文案挤窄） */
.mem-stat .jump { position: absolute; top: 9px; right: 10px; font-size: 10px; color: var(--app-text3);
  opacity: 0; transition: opacity .15s; }
.mem-stat:hover .jump { opacity: 1; }
.mem-stat.warn { border-color: var(--app-warn-border); background: #fffdf7; }
.mem-stat.warn .txt b { color: var(--app-warn-text); }
.mem-stat.warn .sic { background: var(--app-warn-weak); color: var(--app-warn); }
.mem-stat.warn .jump { opacity: 1; color: var(--app-warn-text); }
.mem-stat.info { border-color: var(--app-accent-border); background: #f8fbff; }
.mem-stat.info .txt b { color: var(--app-accent); }
.mem-stat.info .sic { background: var(--app-accent-weak); color: var(--app-accent); }

/* ---------------- 工具栏 ---------------- */
.mem-card { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.mem-toolbar { display: flex; align-items: center; gap: 8px; padding: 10px 14px; border-bottom: 1px solid var(--app-border); flex-wrap: wrap; }
.mem-search { width: 200px; }
.mem-search-ic { color: var(--app-text3); }
.mem-filter { width: 130px; }
.mem-filter :deep(.ant-select-selector) { font-size: 12px; }

/* 批量条 */
.mem-batchbar { display: flex; align-items: center; gap: 8px; padding: 7px 14px;
  border-bottom: 1px solid var(--app-accent-border); background: var(--app-accent-weak);
  font-size: 12px; color: var(--app-accent); }
.mem-batchbar b { font-weight: 600; }

/* ---------------- 表格 ---------------- */
.mem-user { display: inline-flex; align-items: center; gap: 9px; min-width: 0; }
.mem-user-txt { display: flex; flex-direction: column; gap: 1px; min-width: 0; }
.mem-name { color: var(--app-text); }
.mem-uid { font-size: 11px; color: var(--app-text3); font-family: var(--app-mono, ui-monospace, monospace); }
.mem-dept { color: var(--app-text2); }
.mem-none { color: var(--app-text3); }
.mem-roles { display: inline-flex; align-items: center; gap: 5px; flex-wrap: wrap; }
.role-pill { display: inline-flex; align-items: center; font-size: 10.5px; line-height: 1; padding: 4px 8px; border-radius: 999px; }
.role-pill.r-superadmin { color: var(--app-accent); background: var(--app-accent-weak); }
.role-pill.r-admin { color: var(--app-ok); background: var(--app-ok-weak); }
.role-pill.r-user { color: var(--app-text2); background: var(--app-panel-2); }
/* 角色来源：与编辑弹窗里 IdP 角色置灰的口径同源，不再只靠提示文字说明 */
.mem-src { font-size: 10px; color: var(--app-text3); background: var(--app-panel-2);
  border: 1px solid var(--app-border); border-radius: 3px; padding: 1px 5px; white-space: nowrap; }
.mem-src.lock { color: #7c5cd6; background: #f0ecfb; border-color: transparent; }
.mem-auth { color: var(--app-text2); font-size: 12px; }
.mem-login-time { color: var(--app-text2); font-size: 12px; white-space: nowrap; font-variant-numeric: tabular-nums; }
.mem-inline { display: inline-flex; align-items: center; gap: 10px; }
.mem-more { width: 22px; height: 22px; border-radius: 5px; border: 1px solid var(--app-border); background: #fff;
  color: var(--app-text3); display: inline-flex; align-items: center; justify-content: center; padding: 0; }
.mem-more:hover { color: var(--app-accent); border-color: var(--app-accent-border); }
.mem-pager { display: flex; align-items: center; gap: 8px; padding: 9px 14px; border-top: 1px solid var(--app-border); }
.mem-empty { padding: 28px 0; color: var(--app-text3); font-size: 12px; display: flex; flex-direction: column;
  align-items: center; gap: 6px; }

/* ---------------- 部门页签 ---------------- */
.mem-dept-wrap { flex: 1; min-height: 0; display: flex; gap: 12px; }
.mem-dept-left { width: 250px; flex: none; display: flex; flex-direction: column; padding: 12px; }
.mem-dept-right { flex: 1; min-width: 0; display: flex; flex-direction: column; padding: 14px; overflow-y: auto; }
.mem-dept-search { margin-bottom: 8px; }
.mem-dept-sec { font-size: 10px; font-weight: 600; color: var(--app-text3); letter-spacing: .04em; margin: 8px 2px 5px; }
.mem-dept-tree { flex: 1; min-height: 0; overflow-y: auto; }
.mem-dnode { display: flex; align-items: center; gap: 7px; padding: 6px 8px; border-radius: 6px;
  font-size: 12px; color: var(--app-text2); cursor: pointer; }
.mem-dnode:hover { background: var(--app-panel-2); }
.mem-dnode.on { background: var(--app-accent-weak); color: var(--app-accent); font-weight: 500; }
.mem-dnode.dim { color: var(--app-text3); }
.mem-dnode .nm { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mem-dnode .ct { font-size: 10.5px; color: var(--app-text3); }
.mem-dnode.on .ct { color: var(--app-accent); }
.mem-dept-tip { margin-top: 10px; padding: 8px 9px; border: 1px dashed var(--app-border-strong); border-radius: 7px;
  font-size: 10.5px; color: var(--app-text3); line-height: 1.7; }
.mem-dept-head { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.mem-dept-title { font-size: 14px; font-weight: 600; }
.mem-dept-sub { font-size: 11px; color: var(--app-text3); margin-top: 3px; }
.mem-dept-tiles { display: flex; gap: 9px; margin-bottom: 14px; }
.mem-tile { flex: 1; border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel-2); padding: 8px 11px; }
.mem-tile b { display: block; font-size: 16px; font-weight: 650; line-height: 1.2; font-variant-numeric: tabular-nums; }
.mem-tile span { font-size: 10.5px; color: var(--app-text3); }

/* ---------------- 邀请页签 ---------------- */
.mem-invite-wrap { flex: 1; min-height: 0; display: flex; gap: 12px; }
.mem-invite-left { width: 380px; flex: none; padding: 14px; display: flex; flex-direction: column; overflow-y: auto; }
.mem-invite-right { flex: 1; min-width: 0; padding: 14px; display: flex; flex-direction: column; overflow-y: auto; }
.mem-invite-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 14px; }
.mem-invite-submit { width: 100%; justify-content: center; }
.mem-invite-result { margin-top: 12px; padding: 10px; border-radius: 8px; background: var(--app-panel-2); border: 1px solid var(--app-border); }
.mem-invite-result-t { margin-bottom: 7px; font-size: 11px; color: var(--app-text2); }
.mem-invite-link { display: flex; gap: 8px; align-items: center; }
.mem-invite-link code { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  padding: 6px 9px; border-radius: 6px; background: var(--app-code-bg, #f6f8fa);
  font-family: var(--app-mono, ui-monospace, monospace); font-size: 11px; }

/* 临时授权 */
.mem-expire-list { display: flex; flex-direction: column; gap: 6px; }
.mem-expire-row { display: flex; align-items: center; gap: 8px; }
.mem-expire-role { font-size: 12px; background: var(--app-panel-2); border: 1px solid var(--app-border);
  border-radius: 4px; padding: 1px 6px; min-width: 72px; }
.mem-expire-warn { color: var(--app-danger, #c0392b); }
</style>
