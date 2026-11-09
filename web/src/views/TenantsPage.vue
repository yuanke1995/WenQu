<template>
  <div class="app-page">
    <div class="app-page-head">
      <h1 class="app-page-title">租户与开通</h1>
      <span class="head-count" v-if="superadmin">共 <b>{{ tenants.length }}</b> 个租户</span>
      <span class="head-hint-plain">{{ superadmin
        ? '开通、启停、划人与审批是平台级动作；申请入口在登录页与 /apply'
        : '这里只显示你所在的租户；开通、审批与套餐由平台级管理员处理' }}</span>
      <button v-if="superadmin" class="app-btn" style="margin-left:auto" @click="openCreate">
        <plus-outlined /> 新租户
      </button>
    </div>

    <div class="app-page-body">
      <!-- ==================== 平台概览 ==================== -->
      <div v-if="superadmin && overview" class="tx-kpis">
        <div class="tx-kpi">
          <div class="tx-kpi-n">{{ overview.total }}</div>
          <div class="tx-kpi-l">租户总数</div>
          <div class="tx-kpi-d">启用 {{ overview.active }} · 停用 {{ overview.suspended }} · 归档 {{ overview.archived }}</div>
        </div>
        <div class="tx-kpi warn" :class="{ clickable: overview.pendingRequests > 0 }" @click="goRequests">
          <div class="tx-kpi-n">{{ overview.pendingRequests }}</div>
          <div class="tx-kpi-l">待审申请</div>
          <div class="tx-kpi-d">{{ overview.pendingRequests ? '最久等待 ' + overview.oldestPendingHours + ' 小时' : '暂无待办' }}</div>
        </div>
        <div class="tx-kpi">
          <div class="tx-kpi-n">{{ overview.newThisMonth }}</div>
          <div class="tx-kpi-l">本月新开通</div>
          <div class="tx-kpi-d">按租户创建时间统计</div>
        </div>
        <div class="tx-kpi">
          <div class="tx-kpi-n">{{ totalMembers }}</div>
          <div class="tx-kpi-l">平台成员</div>
          <div class="tx-kpi-d">所有租户成员合计</div>
        </div>
      </div>

      <a-tabs v-model:activeKey="tab" @change="onTabChange">
        <!-- ==================== 租户清单 ==================== -->
        <a-tab-pane key="tenants" tab="租户">
          <a-spin :spinning="loading">
            <a-table v-if="tenants.length" :data-source="tenants" :columns="tenantCols" row-key="id"
                     :pagination="false" size="middle" table-layout="fixed" :scroll="{ x: 960 }">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'id'">
                  <div class="tx-tenant-name">{{ record.name }}</div>
                  <code class="tx-tenant-id">{{ record.id }}</code>
                </template>
                <template v-else-if="column.key === 'status'">
                  <template v-if="superadmin">
                    <a-tag v-if="record.status === 2" color="orange">已归档</a-tag>
                    <template v-else>
                      <a-switch :checked="record.status === 1" size="small"
                                :disabled="record.id === 'default'"
                                @change="v => onToggleTenant(record, v)" />
                      <span v-if="record.id === 'default'" class="hint-inline">默认租户不可停用</span>
                    </template>
                  </template>
                  <a-tag v-else :color="record.status === 1 ? 'green' : (record.status === 2 ? 'orange' : 'red')">
                    {{ statusText(record.status) }}
                  </a-tag>
                </template>
                <template v-else-if="column.key === 'plan'">
                  <span class="tx-plan" :class="record.plan || 'standard'">{{ planLabel(record.plan) }}</span>
                </template>
                <template v-else-if="column.key === 'members'">{{ record.members }} 人</template>
                <template v-else-if="column.key === 'tokens'">{{ record.tokensThisMonth != null ? fmtTokens(record.tokensThisMonth) : '—' }}</template>
                <template v-else-if="column.key === 'contact'">{{ record.contact || '—' }}</template>
                <template v-else-if="column.key === 'note'">
                  <span class="tenant-note">{{ record.note || '—' }}</span>
                </template>
                <template v-else-if="column.key === 'action'">
                  <a-space>
                    <a-button type="link" size="small" @click="openDetail(record)">详情</a-button>
                    <a-button type="link" size="small" @click="openBrand(record)">品牌</a-button>
                    <a-popconfirm v-if="superadmin && record.status === 2" title="恢复后该租户成员即可重新登录" ok-text="恢复"
                                  cancel-text="取消" @confirm="doRestore(record)">
                      <a-button type="link" size="small">恢复</a-button>
                    </a-popconfirm>
                  </a-space>
                </template>
              </template>
            </a-table>
            <a-empty v-else description="还没有租户（单租户部署只有 default 一个）" />
            <div class="form-tip" style="margin-top:8px">
              停用只挡该租户成员登录，数据一律保留；归档为只读、可恢复，彻底清除走运维脚本。
            </div>
          </a-spin>
        </a-tab-pane>

        <!-- ==================== 开通申请工作台 ==================== -->
        <a-tab-pane key="requests">
          <template #tab>开通申请<span v-if="pendingCount" class="tab-badge">{{ pendingCount }}</span></template>
          <a-spin :spinning="reqLoading">
            <template v-if="superadmin">
              <div class="tx-queue-wrap">
                <aside class="tx-queue">
                  <div class="tx-queue-head">
                    <a-segmented v-model:value="reqFilter" size="small" :options="[
                      { value: 'pending', label: '待审' }, { value: 'all', label: '全部' },
                      { value: 'approved', label: '已开通' }, { value: 'rejected', label: '已驳回' }]" />
                  </div>
                  <div v-if="filteredRequests.length" class="tx-queue-list">
                    <button v-for="r in filteredRequests" :key="r.id" class="tx-queue-item"
                            :class="{ on: activeRequest && activeRequest.id === r.id }" @click="activeRequest = r">
                      <div class="tx-queue-row">
                        <b>{{ r.orgName }}</b>
                        <span class="tx-queue-time" :class="{ hot: r.status === 'pending' && r.waitingHours >= 24 }">
                          {{ r.status === 'pending' ? r.waitingHours + 'h' : reqStatusLabel(r.status) }}
                        </span>
                      </div>
                      <div class="tx-queue-sub">{{ r.desiredId || '未填标识' }} · {{ r.contactName || '未填联系人' }}</div>
                      <div v-if="r.scale" class="tx-queue-sub">{{ r.scale }} 人 · {{ r.domain || '未填域名' }}</div>
                    </button>
                  </div>
                  <a-empty v-else :description="reqFilter === 'pending' ? '暂无待审申请' : '没有符合条件的申请'" />
                </aside>

                <section v-if="activeRequest" class="tx-approval">
                  <div class="tx-approval-head">
                    <div>
                      <div class="tx-approval-title">{{ activeRequest.orgName }}</div>
                      <div class="tx-approval-sub">申请编号 {{ activeRequest.id }} · {{ fmtTime(activeRequest.createTime) }} 提交</div>
                    </div>
                    <span class="tx-spacer"></span>
                    <a-tag :color="reqStatusColor(activeRequest.status)">{{ reqStatusLabel(activeRequest.status) }}</a-tag>
                  </div>

                  <div class="tx-facts">
                    <div><span>期望标识</span><code>{{ activeRequest.desiredId || '（未填）' }}</code></div>
                    <div><span>团队规模</span>{{ activeRequest.scale || '未填' }}</div>
                    <div><span>联系人</span>{{ activeRequest.contactName || '未填' }}</div>
                    <div><span>联系邮箱</span>{{ activeRequest.contactEmail }}</div>
                    <div><span>企业域名</span>{{ activeRequest.domain || '未填' }}</div>
                    <div><span>单点登录</span>{{ activeRequest.ssoRequested ? '需要' : '暂不需要' }}</div>
                    <div class="span2"><span>用途</span>{{ activeRequest.purpose }}</div>
                    <div v-if="activeRequest.resubmitOf" class="span2"><span>重新提交</span>关联原单 {{ activeRequest.resubmitOf }}</div>
                    <div v-if="materialsOf(activeRequest).length" class="span2">
                      <span>认证材料</span>
                      <a v-for="m in materialsOf(activeRequest)" :key="m.path" :href="m.url" target="_blank" rel="noopener" class="tx-material-link">{{ m.name }}</a>
                    </div>
                  </div>

                  <div v-if="activeRequest.status === 'pending'" class="tx-approval-preview">
                    <b>批准后将创建</b>
                    <div>租户 <code>{{ activeRequest.desiredId || '（批准时指定）' }}</code>（{{ activeRequest.orgName }}）</div>
                    <div>首位管理员 <code>{{ (activeRequest.desiredId || '租户') + '-admin' }}</code> · 角色「管理员」</div>
                    <div>初始口令一次性生成，交付方式：邮件 + 复制</div>
                  </div>
                  <div v-else class="tx-approval-preview done">
                    <b>审批结果</b>
                    <div>租户 <code>{{ activeRequest.openedTenant || '—' }}</code> · 管理员 <code>{{ activeRequest.adminUid || '—' }}</code></div>
                    <div>{{ activeRequest.reviewer || '—' }} · {{ fmtTime(activeRequest.reviewedAt) }}</div>
                    <div v-if="activeRequest.reviewNote">说明：{{ activeRequest.reviewNote }}</div>
                  </div>

                  <div class="tx-approval-actions">
                    <template v-if="activeRequest.status === 'pending'">
                      <a-button danger ghost @click="openReject(activeRequest)">驳回</a-button>
                      <span class="tx-spacer"></span>
                      <a-button @click="openApprove(activeRequest)">批准并开通</a-button>
                    </template>
                    <template v-else-if="activeRequest.status === 'approved'">
                      <span class="tx-spacer"></span>
                      <a-button @click="resendDelivery(activeRequest, false)">重发凭据提醒</a-button>
                      <a-button type="primary" @click="resetDelivery(activeRequest)">重置口令并发送</a-button>
                    </template>
                  </div>
                </section>
                <section v-else class="tx-approval tx-approval-empty">
                  <a-empty description="从左侧选择一条申请" />
                </section>
              </div>
            </template>
            <a-empty v-else description="开通申请仅平台级管理员可查看与处理" />
          </a-spin>
        </a-tab-pane>

        <!-- ==================== 邀请式开通 ==================== -->
        <a-tab-pane key="invites" tab="开通邀请">
          <div v-if="superadmin">
            <div class="tx-invite-form">
              <div class="tx-invite-grid">
                <a-input v-model:value="inviteForm.orgName" placeholder="机构名称（可留空由客户填）" />
                <a-input v-model:value="inviteForm.desiredId" placeholder="预填标识（可留空）" />
                <a-input v-model:value="inviteForm.contactName" placeholder="联系人（可留空）" />
                <a-input v-model:value="inviteForm.contactEmail" placeholder="限定联系邮箱（必填）" />
                <a-input-number v-model:value="inviteForm.expireDays" :min="1" :max="30" addon-after="天" style="width:100%" />
              </div>
              <a-textarea v-model:value="inviteForm.note" :rows="2" :maxlength="300" placeholder="给客户的说明（可留空）" />
              <div class="tx-invite-actions">
                <a-button type="primary" :loading="inviteBusy" @click="submitInvite">生成开通邀请</a-button>
                <span class="form-tip">平台预批准；客户凭链接补齐信息后立即开通，不再进入审批队列。</span>
              </div>
            </div>

            <a-alert v-if="inviteResult" type="success" show-icon style="margin:10px 0"
                     :message="'已生成邀请（' + inviteResult.tokenPrefix + '…）'">
              <template #description>
                <div class="tx-invite-result">
                  <code>{{ inviteResult.link }}</code>
                  <a-button size="small" @click="copyText(inviteResult.link)">复制链接</a-button>
                </div>
              </template>
            </a-alert>

            <a-table :data-source="openInvites" :columns="inviteCols" row-key="id" size="small"
                     :pagination="false" table-layout="fixed" :scroll="{ x: 860 }">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'target'">
                  <div class="tx-tenant-name">{{ record.orgName || '（客户自填）' }}</div>
                  <code class="tx-tenant-id">{{ record.desiredId || '—' }}</code>
                </template>
                <template v-else-if="column.key === 'contact'">
                  <div>{{ record.contactName || '—' }}</div>
                  <div class="tx-sub">{{ record.contactEmail }}</div>
                </template>
                <template v-else-if="column.key === 'uses'">{{ record.usedCount }} / {{ record.maxUses || '不限' }}</template>
                <template v-else-if="column.key === 'expiresAt'">{{ fmtDate(record.expiresAt) }} {{ String(record.expiresAt || '').slice(11, 16) }}</template>
                <template v-else-if="column.key === 'status'">
                  <a-tag :color="inviteStatusColor(record.status)">{{ inviteStatusLabel(record.status) }}</a-tag>
                </template>
                <template v-else-if="column.key === 'action'">
                  <a-popconfirm v-if="record.status === 'active'" title="撤销后该链接无法再使用" ok-text="撤销" cancel-text="取消"
                                @confirm="doRevokeInvite(record)">
                    <a-button type="link" size="small" danger>撤销</a-button>
                  </a-popconfirm>
                  <span v-else class="metric-sub">—</span>
                </template>
              </template>
            </a-table>
            <a-empty v-if="!openInvites.length" description="还没有开通邀请" />
          </div>
          <a-empty v-else description="开通邀请仅平台级管理员可用" />
        </a-tab-pane>

        <!-- ==================== 开通设置 ==================== -->
        <a-tab-pane key="settings" tab="开通设置">
          <div v-if="superadmin" class="tx-settings">
            <div class="tx-setting-row">
              <div>
                <div class="tx-setting-title">域名白名单自动开通</div>
                <div class="tx-setting-desc">开启后，联系邮箱或企业域名命中白名单的申请会立即开通并发送凭据；默认关闭，建议只对已签约客户开启。</div>
              </div>
              <a-switch v-model:checked="autoForm.enabled" />
            </div>
            <a-textarea v-model:value="autoForm.domains" :rows="3" :maxlength="2000"
                        placeholder="company.com, subsidiary.com（逗号分隔）" />
            <div class="tx-invite-actions">
              <a-button type="primary" :loading="autoBusy" @click="saveAuto">保存设置</a-button>
              <span class="form-tip">白名单只做「是否自动放行」判断，仍会走建租户 + 建管理员 + 邮件交付。</span>
            </div>
          </div>
          <a-empty v-else description="开通设置仅平台级管理员可用" />
        </a-tab-pane>
      </a-tabs>
    </div>

    <!-- ==================== 直开租户 ==================== -->
    <a-modal v-model:open="createOpen" title="直接开通租户" :confirm-loading="saving" @ok="submitCreate">
      <a-form layout="vertical" autocomplete="off">
        <a-form-item label="租户标识" required :help="createIdHint"
                     :validate-status="createIdState === 'taken' || createIdState === 'invalid' ? 'error' : (createIdState === 'ok' ? 'success' : '')">
          <a-input v-model:value="createForm.id" placeholder="如 huazhang" @input="onCreateIdInput" @blur="checkCreateId" />
          <template #extra>2-32 位小写字母、数字、- 或 _；直接作为数据归属标识，开通后不可改。</template>
        </a-form-item>
        <a-form-item label="显示名" required><a-input v-model:value="createForm.name" placeholder="如 华章智造集团" /></a-form-item>
        <div class="tx-two">
          <a-form-item label="套餐">
            <a-select v-model:value="createForm.plan" :options="planOptions" />
          </a-form-item>
          <a-form-item label="企业域名"><a-input v-model:value="createForm.domain" placeholder="company.com" /></a-form-item>
        </div>
        <a-form-item label="联系人"><a-input v-model:value="createForm.contact" /></a-form-item>
        <a-form-item label="备注"><a-textarea v-model:value="createForm.note" :rows="2" placeholder="这个租户是谁、怎么开通的（审计要看）" /></a-form-item>
      </a-form>
    </a-modal>

    <!-- ==================== 租户详情抽屉 ==================== -->
    <a-drawer :open="detailOpen" :title="detail ? detail.name + ' · ' + detail.id : '租户详情'" width="720" @close="detailOpen = false">
      <a-spin :spinning="detailLoading">
        <template v-if="detail">
          <div class="tx-detail-head">
            <span class="tx-avatar">{{ (detail.name || '租').slice(0, 1) }}</span>
            <div>
              <div class="tx-detail-name">{{ detail.name }}</div>
              <div class="tx-sub">{{ detail.id }} · 创建于 {{ fmtDate(detail.createTime) }}</div>
            </div>
            <span class="tx-spacer"></span>
            <a-tag :color="detail.status === 1 ? 'green' : (detail.status === 2 ? 'orange' : 'red')">{{ statusText(detail.status) }}</a-tag>
          </div>

          <div class="tx-detail-kpis">
            <div><b>{{ detail.members }}</b><span>成员</span></div>
            <div><b>{{ detail.knowledgeBases }}</b><span>知识库</span></div>
            <div><b>{{ detail.documents }}</b><span>文档</span></div>
            <div><b>{{ fmtTokens(detail.tokensThisMonth) }}</b><span>本月 Tokens</span></div>
          </div>

          <div class="tx-detail-facts">
            <div><span>套餐</span><b>{{ planLabel(detail.plan) }}</b></div>
            <div><span>套餐上限</span>{{ detail.limits ? detail.limits.monthlyTokens.toLocaleString() + ' tokens / 月 · ' + detail.limits.members + ' 人' : '—' }}</div>
            <div><span>企业域名</span>{{ detail.domain || '未配置' }}</div>
            <div><span>单点登录</span>
              <router-link v-if="detail.ssoRequested" to="/permissions?tab=identity">已申请 · 去配置</router-link>
              <span v-else>未申请</span>
            </div>
            <div><span>联系人</span>{{ detail.contact || '—' }}</div>
            <div><span>最近活跃</span>{{ detail.lastActive ? fmtTime(detail.lastActive) : '暂无用量' }}</div>
            <div><span>品牌</span>{{ detail.brandName || '默认' }}{{ detail.brandColor ? ' · ' + detail.brandColor : '' }}</div>
            <div v-if="detail.archiveTime" class="span2"><span>归档</span>{{ fmtTime(detail.archiveTime) }} · {{ detail.archiveBy || '—' }}</div>
            <div class="span2"><span>备注</span>{{ detail.note || '—' }}</div>
          </div>

          <div class="tx-detail-actions">
            <template v-if="superadmin">
              <a-button v-if="detail.status === 1 && detail.id !== 'default'" @click="onToggleTenant(detail, false)">停用</a-button>
              <a-button v-if="detail.status === 0" @click="onToggleTenant(detail, true)">启用</a-button>
              <a-button @click="openBrand(detail)">编辑品牌</a-button>
              <a-button @click="openPlan(detail)">套餐与域名</a-button>
              <a-button @click="openInventory(detail)">落点清单</a-button>
              <a-popconfirm v-if="detail.status === 2" title="恢复后该租户成员即可重新登录" ok-text="恢复" cancel-text="取消"
                            @confirm="doRestore(detail)">
                <a-button>恢复</a-button>
              </a-popconfirm>
              <a-button v-else-if="detail.id !== 'default'" danger @click="openArchive(detail)">归档</a-button>
            </template>
            <template v-else>
              <a-button @click="openBrand(detail)">编辑品牌</a-button>
            </template>
          </div>
        </template>
      </a-spin>
    </a-drawer>

    <!-- ==================== 租户品牌（带实时预览） ==================== -->
    <a-modal v-model:open="brandOpen" title="租户品牌" :confirm-loading="saving" width="720" @ok="saveBrand">
      <div class="tx-brand-wrap">
        <a-form layout="vertical" autocomplete="off" class="tx-brand-form">
          <a-form-item label="登录页展示名" extra="留空=用租户显示名">
            <a-input v-model:value="brandForm.brandName" :maxlength="100" placeholder="如 北辰科技" />
          </a-form-item>
          <a-form-item label="Logo 地址" extra="相对路径或 https 图片地址；留空=问渠默认标">
            <a-input v-model:value="brandForm.brandLogo" :maxlength="200" placeholder="/ai/images/branding/xxx.png" />
          </a-form-item>
          <a-form-item label="登录页主色" extra="6 位十六进制色值，仅登录页生效">
            <a-input v-model:value="brandForm.brandColor" :maxlength="7" placeholder="#1f8a63" />
          </a-form-item>
          <a-form-item label="登录页副标题">
            <a-input v-model:value="brandForm.loginHint" :maxlength="200" placeholder="留空=答案，自有源头 · Ask the source." />
          </a-form-item>
        </a-form>
        <div class="tx-brand-preview">
          <div class="tx-preview-label">登录页预览</div>
          <div class="tx-preview-card" :style="{ '--pv': brandColorSafe }">
            <div class="tx-preview-brand">
              <img v-if="brandForm.brandLogo && !brandLogoBroken" :src="brandForm.brandLogo" alt="" class="tx-preview-logo" @error="brandLogoBroken = true" />
              <span v-else class="tx-preview-mark">问</span>
              <div>
                <div class="tx-preview-name">{{ brandForm.brandName || detail?.name || '问渠' }}</div>
                <div class="tx-preview-hint">{{ brandForm.loginHint || '答案，自有源头' }}</div>
              </div>
            </div>
            <div class="tx-preview-form">
              <div class="tx-preview-input">登录账号</div>
              <div class="tx-preview-input">密码</div>
              <div class="tx-preview-btn">登录</div>
            </div>
            <div class="tx-preview-alt">还没有独立工作区？申请开通</div>
          </div>
          <div class="form-tip">预览只示意品牌色、名称与标语；实际登录页还会带品牌插图与背景。</div>
        </div>
      </div>
    </a-modal>

    <!-- ==================== 套餐与域名 ==================== -->
    <a-modal v-model:open="planOpen" title="套餐与域名" :confirm-loading="saving" @ok="savePlan">
      <a-form layout="vertical">
        <a-form-item label="套餐" extra="套餐会同步到租户级月 token 配额，展示与拦截使用同一个上限">
          <a-select v-model:value="planForm.plan" :options="planOptions" />
        </a-form-item>
        <a-form-item label="企业域名" extra="用于 SSO 与自动开通白名单；留空=不限定">
          <a-input v-model:value="planForm.domain" :maxlength="190" placeholder="company.com" />
        </a-form-item>
        <div class="tx-plan-preview">
          <div><span>月 Tokens</span><b>{{ currentPlanLimit.monthlyTokens.toLocaleString() }}</b></div>
          <div><span>成员上限</span><b>{{ currentPlanLimit.members }} 人</b></div>
        </div>
      </a-form>
    </a-modal>

    <!-- ==================== 归档确认 ==================== -->
    <a-modal v-model:open="archiveOpen" title="归档租户" :ok-button-props="{ danger: true, disabled: archiveConfirm !== archiveTarget?.id }"
             ok-text="确认归档" cancel-text="取消" :confirm-loading="saving" @ok="doArchive">
      <template v-if="archiveTarget">
        <p class="tx-archive-title">归档「{{ archiveTarget.name }}」</p>
        <div class="tx-archive-consequences">
          <div>· {{ archiveTarget.members }} 名成员<strong>立即无法登录</strong>，会话保留</div>
          <div>· 数据转为只读，可随时导出</div>
          <div>· <strong>不会删除任何数据</strong>，可随时恢复</div>
          <div>· 品牌与邀请链接同时失效</div>
        </div>
        <a-form layout="vertical">
          <a-form-item :label="'输入租户标识 ' + archiveTarget.id + ' 以确认'">
            <a-input v-model:value="archiveConfirm" :placeholder="archiveTarget.id" />
          </a-form-item>
        </a-form>
      </template>
    </a-modal>

    <!-- ==================== 批准开通 ==================== -->
    <a-modal v-model:open="approveOpen" title="批准开通申请" :confirm-loading="saving" ok-text="批准并开通" @ok="submitApprove">
      <a-form layout="vertical" autocomplete="off">
        <a-form-item label="申请机构">
          <span>{{ approving.orgName }} · {{ approving.contactName || '未填联系人' }}（{{ approving.contactEmail }}）</span>
          <div class="form-tip">{{ approving.purpose }}</div>
        </a-form-item>
        <a-form-item label="租户标识" required :help="approveIdHint"
                     :validate-status="approveIdState === 'taken' || approveIdState === 'invalid' ? 'error' : (approveIdState === 'ok' ? 'success' : '')">
          <a-input v-model:value="approveForm.tenantId" placeholder="2-32 位小写字母、数字、- 或 _" @input="onApproveIdInput" @blur="checkApproveId" />
        </a-form-item>
        <div class="form-tip">批准＝开一家新客户：建租户 + 建首位管理员账号（标识「租户-admin」，随机口令只在这次结果里出现一次）。</div>
      </a-form>
    </a-modal>

    <!-- ==================== 交付凭据 ==================== -->
    <a-modal v-model:open="deliveryOpen" title="已开通 · 交付凭据" :footer="null" width="560">
      <a-alert type="warning" show-icon style="margin-bottom:12px"
               message="初始口令只显示这一次，关闭后无法再次查看；可在申请详情里重发或重置。" />
      <div class="tx-delivery">
        <div><span>工作区地址</span><code>{{ loginUrl }}</code><a @click="copyText(loginUrl)">复制</a></div>
        <div><span>租户标识</span><code>{{ delivery.tenantId }}</code><a @click="copyText(delivery.tenantId)">复制</a></div>
        <div><span>管理员账号</span><code>{{ delivery.adminUid }}</code><a @click="copyText(delivery.adminUid)">复制</a></div>
        <div v-if="delivery.password"><span>初始口令</span><code>{{ delivery.password }}</code><a @click="copyText(delivery.password)">复制</a></div>
        <div><span>送达方式</span>
          <b :class="delivery.emailSent ? 'tx-ok' : 'tx-warn'">{{ delivery.emailSent ? '已发送到 ' + delivery.email : '邮件未发出，请手动交付' }}</b>
        </div>
      </div>
      <div class="tx-delivery-actions">
        <a-button type="primary" @click="copyText(deliveryText)">复制全部</a-button>
        <a-button @click="downloadDelivery">下载凭据</a-button>
        <a-button @click="resendDelivery({ id: delivery.requestId }, false)" :loading="deliveryBusy">重发邮件</a-button>
        <a-button danger ghost @click="resetDelivery({ id: delivery.requestId })" :loading="deliveryBusy">重置口令</a-button>
        <span class="tx-spacer"></span>
        <a-button @click="deliveryOpen = false">完成</a-button>
      </div>
      <div class="form-tip" style="margin-top:8px">{{ delivery.note }}</div>
    </a-modal>

    <!-- ==================== 驳回 ==================== -->
    <a-modal v-model:open="rejectOpen" title="驳回开通申请" :confirm-loading="saving" @ok="submitReject">
      <a-form layout="vertical">
        <a-form-item label="驳回说明" extra="会展示在申请人的进度页，并随结果邮件发送">
          <a-textarea v-model:value="rejectForm.note" :rows="3" placeholder="如：主体资料不全，请补充营业执照后重新提交" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- ==================== 落点清单 ==================== -->
    <a-drawer :open="inventoryOpen" :title="`落点清单 — ${inventoryTenant ? inventoryTenant.name : ''}`"
              width="680" @close="inventoryOpen = false">
      <a-spin :spinning="invLoading">
        <template v-if="inventory">
          <div class="inv-overview">
            <div class="inv-cell"><div class="stat-num">{{ inventory.members.length }}</div><div class="stat-label">成员</div></div>
            <div class="inv-cell"><div class="stat-num">{{ inventory.kbCount ?? '—' }}</div><div class="stat-label">知识库</div></div>
            <div class="inv-cell"><div class="stat-num">{{ inventory.rows }}</div><div class="stat-label">库内行数</div></div>
            <div class="inv-cell"><div class="stat-num">{{ fmtBytes(inventory.filesBytes) }}</div><div class="stat-label">磁盘占用</div></div>
            <div class="inv-cell" v-if="inventory.filesMissing"><div class="stat-num" style="color:#d4380d">{{ inventory.filesMissing }}</div><div class="stat-label">目录缺失</div></div>
          </div>
          <a-divider orientation="left" class="inv-divider">库内落点（{{ inventory.tables.length }} 张表）</a-divider>
          <div class="inv-tables">
            <div v-for="t in inventory.tables" :key="t.table" class="inv-table-row">
              <code>{{ t.table }}</code><span class="inv-rows">{{ t.rows }} 行</span>
            </div>
          </div>
          <a-divider orientation="left" class="inv-divider">磁盘目录</a-divider>
          <div class="inv-tables">
            <div v-for="f in inventory.files" :key="f.path" class="inv-table-row">
              <span class="inv-kind">{{ f.kind }}</span>
              <span class="inv-path" :title="f.path">{{ f.path }}</span>
              <a-tag v-if="!f.exists" color="red">缺失</a-tag>
              <span class="inv-rows">{{ fmtBytes(f.bytes) }}</span>
            </div>
          </div>
          <a-divider orientation="left" class="inv-divider">Redis 键形态</a-divider>
          <div style="margin-bottom:8px">
            <a-tag v-for="k in inventory.redisKeyPatterns || []" :key="k" class="inv-key"><code>{{ k }}</code></a-tag>
            <span v-if="!(inventory.redisKeyPatterns || []).length" class="metric-sub">无</span>
          </div>
          <a-divider orientation="left" class="inv-divider">不随租户走的全局部分</a-divider>
          <ul class="inv-global">
            <li v-for="(g, i) in inventory.notInTenantScope || []" :key="i">{{ g }}</li>
          </ul>
        </template>
      </a-spin>
    </a-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import {
  listTenants, createTenant, setTenantStatus,
  listTenantRequests, approveTenantRequest, rejectTenantRequest, getTenantInventory,
  archiveTenant, restoreTenant, updateTenantBrand,
  getTenantOverview, getTenantDetail, updateTenantPlan, checkTenantId, deliverTenantRequest,
  getTenantAutoApprove, saveTenantAutoApprove,
  listTenantOpenInvites, createTenantOpenInvite, revokeTenantOpenInvite
} from '../api'
import { isSuperadminSync } from '../utils/auth'

const superadmin = isSuperadminSync()
const route = useRoute()

const tab = ref(['requests', 'invites', 'settings'].includes(String(route.query.tab)) ? String(route.query.tab) : 'tenants')
watch(() => route.query.tab, v => { if (['requests', 'invites', 'settings'].includes(String(v))) tab.value = String(v) })

const tenants = ref([])
const requests = ref([])
const loading = ref(false)
const reqLoading = ref(false)
const saving = ref(false)

// ==================== 概览 / 清单 ====================
const overview = ref(null)
const totalMembers = computed(() => tenants.value.reduce((s, t) => s + (Number(t.members) || 0), 0))

const tenantCols = [
  { title: '租户', key: 'id', width: 190 },
  { title: '状态', key: 'status', width: 150 },
  { title: '套餐', key: 'plan', width: 90 },
  { title: '成员', key: 'members', width: 80 },
  { title: '本月 Tokens', key: 'tokens', width: 120 },
  { title: '联系人', key: 'contact', width: 110 },
  { title: '备注', key: 'note', ellipsis: true },
  { title: '操作', key: 'action', width: 180 }
]
const planOptions = [
  { value: 'trial', label: '试用（50 万 tokens / 月 · 10 人）' },
  { value: 'standard', label: '标准（500 万 tokens / 月 · 100 人）' },
  { value: 'dedicated', label: '专属（5000 万 tokens / 月 · 1000 人）' }
]
const planLabel = p => ({ trial: '试用', standard: '标准', dedicated: '专属' }[p] || '标准')
const statusText = s => (s === 1 ? '启用' : (s === 2 ? '已归档' : '已停用'))
const fmtTokens = n => {
  const v = Number(n) || 0
  if (v >= 1000000) return (v / 1000000).toFixed(1) + 'M'
  if (v >= 1000) return (v / 1000).toFixed(1) + 'K'
  return String(v)
}
const fmtTime = s => s ? String(s).replace('T', ' ').slice(0, 16) : ''
const fmtDate = s => s ? String(s).slice(0, 10) : '—'

async function load () {
  loading.value = true
  try {
    const r = await listTenants()
    if (r.success) tenants.value = r.data || []
    else message.error(r.msg || '租户清单加载失败')
  } catch (e) { message.error(e.message || '租户清单加载失败') }
  finally { loading.value = false }
  if (superadmin) {
    try {
      const o = await getTenantOverview()
      if (o.success) overview.value = o.data || null
    } catch (e) { /* 概览失败不阻断清单 */ }
  }
}

async function onToggleTenant (t, v) {
  if (!v) {
    Modal.confirm({
      title: '停用「' + t.name + '」？',
      content: '该租户成员将无法登录，数据与配额全部保留，可随时重新启用。',
      okText: '停用', okButtonProps: { danger: true }, cancelText: '取消',
      onOk: async () => {
        const r = await setTenantStatus(t.id, false)
        if (!r.success) { message.error(r.msg || '操作失败'); return }
        message.success('已停用')
        refreshAfterChange(t.id)
      }
    })
    return
  }
  try {
    const r = await setTenantStatus(t.id, true)
    if (r.success) { message.success('已启用'); refreshAfterChange(t.id) }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}

function refreshAfterChange (id) {
  load()
  if (detailOpen.value && detail.value && detail.value.id === id) openDetail({ id })
}

// ==================== 租户详情 ====================
const detailOpen = ref(false)
const detail = ref(null)
const detailLoading = ref(false)
async function openDetail (t) {
  detailOpen.value = true
  detailLoading.value = true
  detail.value = null
  try {
    const r = await getTenantDetail(t.id)
    if (r.success) detail.value = r.data || null
    else message.error(r.msg || '租户详情加载失败')
  } catch (e) { message.error(e.message || '租户详情加载失败') }
  finally { detailLoading.value = false }
}

// ==================== 直开租户 ====================
const createOpen = ref(false)
const createForm = reactive({ id: '', name: '', contact: '', note: '', plan: 'standard', domain: '' })
const createIdState = ref('')
const createIdHint = ref('')
function openCreate () {
  Object.assign(createForm, { id: '', name: '', contact: '', note: '', plan: 'standard', domain: '' })
  createIdState.value = ''
  createIdHint.value = ''
  createOpen.value = true
}
function onCreateIdInput () {
  createIdState.value = ''
  clearTimeout(createTimer)
  const v = createForm.id.trim()
  if (!v) return
  if (!/^[a-z0-9][a-z0-9_-]{1,31}$/.test(v)) { createIdState.value = 'invalid'; createIdHint.value = '格式不对'; return }
  createTimer = setTimeout(checkCreateId, 400)
}
let createTimer = null
async function checkCreateId () {
  const v = createForm.id.trim().toLowerCase()
  if (!v) return
  if (!/^[a-z0-9][a-z0-9_-]{1,31}$/.test(v)) { createIdState.value = 'invalid'; createIdHint.value = '格式不对'; return }
  createIdState.value = 'checking'
  try {
    const r = await checkTenantId(v)
    const d = (r && r.data) || {}
    createIdState.value = d.available ? 'ok' : (d.reason && d.reason.includes('占用') ? 'taken' : 'invalid')
    createIdHint.value = d.available ? '' : (d.reason || '不可用')
  } catch (e) { createIdState.value = '' }
}
async function submitCreate () {
  const f = createForm
  if (!/^[a-z0-9][a-z0-9_-]{1,31}$/.test(f.id.trim())) { message.error('租户标识要用 2-32 位小写字母、数字、- 或 _'); return }
  if (!f.name.trim()) { message.error('要给租户起个显示名'); return }
  saving.value = true
  try {
    const r = await createTenant({
      id: f.id.trim(), name: f.name.trim(), contact: f.contact, note: f.note,
      plan: f.plan, domain: f.domain.trim()
    })
    if (r.success) { message.success('已开通租户'); createOpen.value = false; await load() }
    else message.error(r.msg || '开通失败')
  } catch (e) { message.error(e.message || '开通失败') }
  finally { saving.value = false }
}

// ==================== 品牌 ====================
const brandOpen = ref(false)
const brandLogoBroken = ref(false)
const brandForm = reactive({ id: '', brandName: '', brandLogo: '', brandColor: '', loginHint: '' })
const brandColorSafe = computed(() => /^#[0-9a-fA-F]{6}$/.test(brandForm.brandColor) ? brandForm.brandColor : '#2e6be6')
function openBrand (t) {
  Object.assign(brandForm, {
    id: t.id,
    brandName: t.brandName || '',
    brandLogo: t.brandLogo || '',
    brandColor: t.brandColor || '',
    loginHint: t.loginHint || ''
  })
  brandLogoBroken.value = false
  brandOpen.value = true
}
async function saveBrand () {
  const f = brandForm
  const c = (f.brandColor || '').trim()
  if (c && !/^#[0-9a-fA-F]{6}$/.test(c)) { message.error('主色要填 6 位十六进制，如 #1f8a63'); return }
  saving.value = true
  try {
    const r = await updateTenantBrand(f.id, {
      brandName: f.brandName.trim(), brandLogo: f.brandLogo.trim(),
      brandColor: c, loginHint: f.loginHint.trim()
    })
    if (!r.success) { message.error(r.msg || '保存失败'); return }
    message.success('品牌已更新')
    brandOpen.value = false
    await load()
    if (detailOpen.value && detail.value && detail.value.id === f.id) openDetail({ id: f.id })
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}

// ==================== 套餐 ====================
const planOpen = ref(false)
const planForm = reactive({ id: '', plan: 'standard', domain: '' })
const currentPlanLimit = computed(() => {
  const p = planForm.plan
  if (p === 'trial') return { monthlyTokens: 500000, members: 10 }
  if (p === 'dedicated') return { monthlyTokens: 50000000, members: 1000 }
  return { monthlyTokens: 5000000, members: 100 }
})
function openPlan (t) {
  Object.assign(planForm, { id: t.id, plan: t.plan || 'standard', domain: t.domain || '' })
  planOpen.value = true
}
async function savePlan () {
  saving.value = true
  try {
    const r = await updateTenantPlan(planForm.id, planForm.plan, planForm.domain.trim())
    if (!r.success) { message.error(r.msg || '保存失败'); return }
    message.success('套餐已更新')
    planOpen.value = false
    await load()
    if (detailOpen.value && detail.value && detail.value.id === planForm.id) openDetail({ id: planForm.id })
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}

// ==================== 归档 ====================
const archiveOpen = ref(false)
const archiveTarget = ref(null)
const archiveConfirm = ref('')
function openArchive (t) {
  archiveTarget.value = t
  archiveConfirm.value = ''
  archiveOpen.value = true
}
async function doArchive () {
  const t = archiveTarget.value
  if (!t || archiveConfirm.value.trim() !== t.id) return
  saving.value = true
  try {
    const r = await archiveTenant(t.id)
    if (!r.success) { message.error(r.msg || '归档失败'); return }
    message.success('已归档')
    archiveOpen.value = false
    detailOpen.value = false
    await load()
  } catch (e) { message.error(e.message || '归档失败') }
  finally { saving.value = false }
}
async function doRestore (t) {
  try {
    const r = await restoreTenant(t.id)
    if (!r.success) { message.error(r.msg || '恢复失败'); return }
    message.success('已恢复')
    await load()
    if (detailOpen.value) openDetail({ id: t.id })
  } catch (e) { message.error(e.message || '恢复失败') }
}

// ==================== 申请工作台 ====================
const reqFilter = ref('pending')
const activeRequest = ref(null)
const filteredRequests = computed(() => {
  if (reqFilter.value === 'all') return requests.value
  return requests.value.filter(r => r.status === reqFilter.value)
})
const pendingCount = computed(() => requests.value.filter(r => r.status === 'pending').length)
const reqStatusLabel = s => ({ pending: '待审', approved: '已开通', rejected: '已驳回' }[s] || s)
const reqStatusColor = s => ({ pending: 'gold', approved: 'green', rejected: 'red' }[s] || 'default')
const materialsOf = r => {
  const m = r.materials
  if (Array.isArray(m)) return m
  try { const a = JSON.parse(m || '[]'); return Array.isArray(a) ? a : [] } catch (e) { return [] }
}

async function loadRequests () {
  if (!superadmin) return
  reqLoading.value = true
  try {
    const r = await listTenantRequests()
    if (r.success) {
      requests.value = r.data || []
      if (!activeRequest.value || !requests.value.some(x => x.id === activeRequest.value.id)) {
        activeRequest.value = filteredRequests.value[0] || requests.value[0] || null
      }
    } else message.error(r.msg || '开通申请加载失败')
  } catch (e) { message.error(e.message || '开通申请加载失败') }
  finally { reqLoading.value = false }
}
watch(filteredRequests, list => {
  if (list.length && (!activeRequest.value || !list.some(x => x.id === activeRequest.value.id))) {
    activeRequest.value = list[0]
  }
})

function goRequests () { tab.value = 'requests' }

// ---- 批准 ----
const approveOpen = ref(false)
const approving = ref({})
const approveForm = reactive({ tenantId: '' })
const approveIdState = ref('')
const approveIdHint = ref('')
let approveTimer = null
function openApprove (r) {
  approving.value = r
  approveForm.tenantId = r.desiredId || ''
  approveIdState.value = ''
  approveIdHint.value = ''
  approveOpen.value = true
}
function onApproveIdInput () {
  approveIdState.value = ''
  clearTimeout(approveTimer)
  const v = approveForm.tenantId.trim()
  if (!v) return
  if (!/^[a-z0-9][a-z0-9_-]{1,31}$/.test(v)) { approveIdState.value = 'invalid'; approveIdHint.value = '格式不对'; return }
  approveTimer = setTimeout(checkApproveId, 400)
}
async function checkApproveId () {
  const v = approveForm.tenantId.trim().toLowerCase()
  if (!v) return
  if (!/^[a-z0-9][a-z0-9_-]{1,31}$/.test(v)) { approveIdState.value = 'invalid'; approveIdHint.value = '格式不对'; return }
  approveIdState.value = 'checking'
  try {
    const r = await checkTenantId(v)
    const d = (r && r.data) || {}
    approveIdState.value = d.available ? 'ok' : (d.reason && d.reason.includes('占用') ? 'taken' : 'invalid')
    approveIdHint.value = d.available ? '' : (d.reason || '不可用')
  } catch (e) { approveIdState.value = '' }
}
async function submitApprove () {
  const tid = approveForm.tenantId.trim()
  if (!/^[a-z0-9][a-z0-9_-]{1,31}$/.test(tid)) { message.error('租户标识要用 2-32 位小写字母、数字、- 或 _'); return }
  saving.value = true
  try {
    const r = await approveTenantRequest(approving.value.id, tid)
    if (r.success && r.data) {
      approveOpen.value = false
      delivery.value = { ...r.data, requestId: approving.value.id }
      deliveryOpen.value = true
      await Promise.all([load(), loadRequests()])
    } else message.error(r.msg || '批准失败')
  } catch (e) { message.error(e.message || '批准失败') }
  finally { saving.value = false }
}

// ---- 交付 ----
const deliveryOpen = ref(false)
const delivery = ref({})
const deliveryBusy = ref(false)
const loginUrl = computed(() => {
  const base = window.location.origin
  return base + '/login' + (delivery.value.tenantId ? '?tenant=' + delivery.value.tenantId : '')
})
const deliveryText = computed(() => [
  '工作区地址：' + loginUrl.value,
  '租户标识：' + (delivery.value.tenantId || ''),
  '管理员账号：' + (delivery.value.adminUid || ''),
  delivery.value.password ? '初始口令：' + delivery.value.password : '',
  '首次登录后请立刻修改密码。'
].filter(Boolean).join('\n'))

async function resendDelivery (req, reset) {
  if (!req || !req.id) return
  deliveryBusy.value = true
  try {
    const r = await deliverTenantRequest(req.id, false)
    if (!r.success) { message.error(r.msg || '操作失败'); return }
    delivery.value = { ...delivery.value, ...r.data, requestId: req.id }
    if (!deliveryOpen.value) { deliveryOpen.value = true } else { message.success(r.data.emailSent ? '已重发邮件' : '邮件未发出，请手动交付') }
    await loadRequests()
  } catch (e) { message.error(e.message || '操作失败') }
  finally { deliveryBusy.value = false }
}
async function resetDelivery (req) {
  if (!req || !req.id) return
  Modal.confirm({
    title: '重置首位管理员口令？',
    content: '旧口令会立即失效，新口令只显示这一次并发送到申请人邮箱。',
    okText: '重置并发送', okButtonProps: { danger: true }, cancelText: '取消',
    onOk: async () => {
      deliveryBusy.value = true
      try {
        const r = await deliverTenantRequest(req.id, true)
        if (!r.success) { message.error(r.msg || '操作失败'); return }
        delivery.value = { ...delivery.value, ...r.data, requestId: req.id }
        deliveryOpen.value = true
        message.success(r.data.emailSent ? '已重置并发送' : '已重置，但邮件未发出')
        await loadRequests()
      } catch (e) { message.error(e.message || '操作失败') }
      finally { deliveryBusy.value = false }
    }
  })
}

function copyText (text) {
  const s = String(text || '')
  if (!s) return
  if (navigator.clipboard && window.isSecureContext) {
    navigator.clipboard.writeText(s).then(() => message.success('已复制')).catch(() => fallbackCopy(s))
  } else fallbackCopy(s)
}
function fallbackCopy (s) {
  const ta = document.createElement('textarea')
  ta.value = s
  ta.style.position = 'fixed'
  ta.style.opacity = '0'
  document.body.appendChild(ta)
  ta.select()
  try { document.execCommand('copy'); message.success('已复制') } catch (e) { message.error('复制失败，请手动选择') }
  document.body.removeChild(ta)
}
function downloadDelivery () {
  const blob = new Blob([deliveryText.value], { type: 'text/plain;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = '工作区凭据-' + (delivery.value.tenantId || 'tenant') + '.txt'
  a.click()
  setTimeout(() => URL.revokeObjectURL(a.href), 1000)
}

// ---- 驳回 ----
const rejectOpen = ref(false)
const rejecting = ref({})
const rejectForm = reactive({ note: '' })
function openReject (r) {
  rejecting.value = r
  rejectForm.note = ''
  rejectOpen.value = true
}
async function submitReject () {
  saving.value = true
  try {
    const r = await rejectTenantRequest(rejecting.value.id, rejectForm.note)
    if (r.success) {
      message.success('已驳回')
      rejectOpen.value = false
      await loadRequests()
      if (activeRequest.value && activeRequest.value.id === rejecting.value.id) {
        activeRequest.value = requests.value.find(x => x.id === rejecting.value.id) || activeRequest.value
      }
    } else message.error(r.msg || '驳回失败')
  } catch (e) { message.error(e.message || '驳回失败') }
  finally { saving.value = false }
}

// ==================== 开通邀请 ====================
const inviteForm = reactive({ orgName: '', desiredId: '', contactName: '', contactEmail: '', note: '', expireDays: 7 })
const inviteBusy = ref(false)
const inviteResult = ref(null)
const openInvites = ref([])
const inviteCols = [
  { title: '目标机构', key: 'target', width: 180 },
  { title: '联系人', key: 'contact', width: 190 },
  { title: '使用', key: 'uses', width: 90 },
  { title: '到期', dataIndex: 'expiresAt', key: 'expiresAt', width: 140 },
  { title: '状态', key: 'status', width: 90 },
  { title: '开出', dataIndex: 'openedTenant', key: 'openedTenant', width: 110 },
  { title: '操作', key: 'action', width: 80 }
]
const inviteStatusLabel = s => ({ active: '有效', revoked: '已撤销', exhausted: '已用尽', expired: '已过期' }[s] || s)
const inviteStatusColor = s => ({ active: 'green', revoked: 'red', exhausted: 'orange', expired: 'default' }[s] || 'default')
async function loadInvites () {
  if (!superadmin) return
  try {
    const r = await listTenantOpenInvites()
    if (r.success) openInvites.value = r.data || []
  } catch (e) { /* 非阻断 */ }
}
async function submitInvite () {
  if (!inviteForm.contactEmail.trim()) { message.error('要填限定联系邮箱'); return }
  inviteBusy.value = true
  try {
    const r = await createTenantOpenInvite({ ...inviteForm, contactEmail: inviteForm.contactEmail.trim() })
    if (!r.success) { message.error(r.msg || '生成失败'); return }
    inviteResult.value = { ...r.data, link: window.location.origin + r.data.path }
    Object.assign(inviteForm, { orgName: '', desiredId: '', contactName: '', contactEmail: '', note: '', expireDays: 7 })
    await loadInvites()
  } catch (e) { message.error(e.message || '生成失败') }
  finally { inviteBusy.value = false }
}
async function doRevokeInvite (row) {
  try {
    const r = await revokeTenantOpenInvite(row.id)
    if (!r.success) { message.error(r.msg || '撤销失败'); return }
    message.success('已撤销')
    await loadInvites()
  } catch (e) { message.error(e.message || '撤销失败') }
}

// ==================== 开通设置 ====================
const autoForm = reactive({ enabled: false, domains: '' })
const autoBusy = ref(false)
async function loadAuto () {
  if (!superadmin) return
  try {
    const r = await getTenantAutoApprove()
    if (r.success && r.data) Object.assign(autoForm, { enabled: !!r.data.enabled, domains: r.data.domains || '' })
  } catch (e) { /* 非阻断 */ }
}
async function saveAuto () {
  autoBusy.value = true
  try {
    const r = await saveTenantAutoApprove({ enabled: autoForm.enabled, domains: autoForm.domains.trim() })
    if (!r.success) { message.error(r.msg || '保存失败'); return }
    message.success('已保存')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { autoBusy.value = false }
}

function onTabChange (k) {
  if (k === 'requests') loadRequests()
  if (k === 'invites') loadInvites()
  if (k === 'settings') loadAuto()
}

// ==================== 落点清单 ====================
const inventoryOpen = ref(false)
const inventoryTenant = ref(null)
const inventory = ref(null)
const invLoading = ref(false)
async function openInventory (t) {
  inventoryTenant.value = t
  inventory.value = null
  inventoryOpen.value = true
  invLoading.value = true
  try {
    const r = await getTenantInventory(t.id)
    if (r.success) inventory.value = r.data || {}
    else { message.error(r.msg || '落点清单加载失败'); inventoryOpen.value = false }
  } catch (e) { message.error(e.message || '落点清单加载失败'); inventoryOpen.value = false }
  finally { invLoading.value = false }
}
function fmtBytes (n) {
  if (n == null) return '—'
  if (n < 1024) return n + ' B'
  if (n < 1048576) return (n / 1024).toFixed(1) + ' KB'
  if (n < 1073741824) return (n / 1048576).toFixed(1) + ' MB'
  return (n / 1073741824).toFixed(2) + ' GB'
}

onMounted(() => {
  load()
  loadRequests()
  if (tab.value === 'invites') loadInvites()
  if (tab.value === 'settings') loadAuto()
})
</script>

<style scoped>
.form-tip { font-size: 12px; color: var(--app-text-3, #888); line-height: 1.6; }
.hint-inline { margin-left: 8px; font-size: 12px; color: var(--app-text-3, #aaa); }
.tenant-note { color: var(--app-text-2, #666); font-size: 12px; }
.tab-badge {
  display: inline-block; margin-left: 6px; padding: 0 6px; border-radius: 8px;
  background: #faad14; color: #fff; font-size: 11px; line-height: 16px;
}
.tx-sub { font-size: 11px; color: var(--app-text-3, #98a0aa); }
.tx-spacer { margin-left: auto; }
.tx-two { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }

/* 概览卡 */
.tx-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 14px; }
.tx-kpi { border: 1px solid var(--app-border, #e6e9ed); border-radius: 10px; padding: 12px 14px; background: var(--app-panel-2, #fafbfc); }
.tx-kpi.warn { border-color: #f0dfb6; background: #faf3e6; }
.tx-kpi.clickable { cursor: pointer; }
.tx-kpi-n { font-size: 24px; font-weight: 700; line-height: 1.2; letter-spacing: -.02em; }
.tx-kpi.warn .tx-kpi-n { color: #a3691b; }
.tx-kpi-l { font-size: 12px; color: var(--app-text-3, #98a0aa); margin-top: 2px; }
.tx-kpi-d { font-size: 11px; color: var(--app-text-2, #5f6570); margin-top: 5px; }

.tx-tenant-name { font-weight: 600; font-size: 13px; }
.tx-tenant-id { font-family: var(--mono, monospace); font-size: 11px; color: var(--app-text-3, #98a0aa); }
.tx-plan { font-size: 11px; padding: 1px 7px; border-radius: 8px; background: var(--app-bg-2, #f2f3f5); color: var(--app-text-2, #5f6570); }
.tx-plan.trial { background: #faf3e6; color: #a3691b; }
.tx-plan.dedicated { background: #eef2f7; color: #2e6be6; }

/* 审批工作台 */
.tx-queue-wrap { display: grid; grid-template-columns: 290px minmax(0, 1fr); border: 1px solid var(--app-border, #e6e9ed); border-radius: 10px; overflow: hidden; min-height: 420px; }
.tx-queue { border-right: 1px solid var(--app-border, #e6e9ed); background: var(--app-panel-2, #fafbfc); display: flex; flex-direction: column; }
.tx-queue-head { padding: 10px 12px; border-bottom: 1px solid var(--app-border, #e6e9ed); }
.tx-queue-list { overflow: auto; max-height: 560px; }
.tx-queue-item { display: block; width: 100%; text-align: left; border: 0; border-bottom: 1px solid var(--app-border, #e6e9ed); border-left: 2px solid transparent; background: transparent; padding: 10px 12px; cursor: pointer; }
.tx-queue-item:hover { background: #fff; }
.tx-queue-item.on { background: #fff; border-left-color: var(--app-accent, #2e6be6); }
.tx-queue-row { display: flex; align-items: center; gap: 8px; }
.tx-queue-row b { font-size: 12.5px; color: var(--app-text, #1f2329); }
.tx-queue-time { margin-left: auto; font-size: 11px; color: var(--app-text-3, #98a0aa); }
.tx-queue-time.hot { color: #cf1322; font-weight: 600; }
.tx-queue-sub { font-size: 11px; color: var(--app-text-3, #98a0aa); margin-top: 2px; }
.tx-approval { padding: 16px 18px; display: flex; flex-direction: column; }
.tx-approval-empty { align-items: center; justify-content: center; }
.tx-approval-head { display: flex; align-items: flex-start; gap: 10px; }
.tx-approval-title { font-size: 16px; font-weight: 700; }
.tx-approval-sub { font-size: 11.5px; color: var(--app-text-3, #98a0aa); margin-top: 2px; }
.tx-facts { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 18px; margin: 16px 0; font-size: 12.5px; }
.tx-facts > div { min-width: 0; }
.tx-facts span { display: block; font-size: 11px; color: var(--app-text-3, #98a0aa); margin-bottom: 2px; }
.tx-facts .span2 { grid-column: 1 / -1; }
.tx-material-link { margin-right: 10px; }
.tx-approval-preview { border: 1px solid var(--app-accent-border, #bcd0f7); background: var(--app-accent-weak, #eef2f7); border-radius: 8px; padding: 10px 12px; font-size: 12px; line-height: 1.8; }
.tx-approval-preview b { color: var(--app-accent, #2e6be6); }
.tx-approval-preview.done { border-color: var(--app-border, #e6e9ed); background: var(--app-panel-2, #fafbfc); }
.tx-approval-preview.done b { color: var(--app-text, #1f2329); }
.tx-approval-actions { display: flex; align-items: center; gap: 8px; margin-top: 16px; }

/* 邀请 */
.tx-invite-form { border: 1px solid var(--app-border, #e6e9ed); border-radius: 10px; padding: 12px; margin-bottom: 12px; }
.tx-invite-grid { display: grid; grid-template-columns: 1.2fr 1fr 1fr 1.3fr 110px; gap: 8px; margin-bottom: 8px; }
.tx-invite-actions { display: flex; align-items: center; gap: 10px; margin-top: 8px; flex-wrap: wrap; }
.tx-invite-result { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 4px; }
.tx-invite-result code { word-break: break-all; }

/* 设置 */
.tx-settings { max-width: 640px; }
.tx-setting-row { display: flex; align-items: flex-start; gap: 20px; padding: 12px 0; }
.tx-setting-title { font-size: 13px; font-weight: 650; }
.tx-setting-desc { font-size: 12px; color: var(--app-text-2, #5f6570); line-height: 1.7; margin-top: 3px; }

/* 详情抽屉 */
.tx-detail-head { display: flex; align-items: center; gap: 11px; }
.tx-avatar { width: 36px; height: 36px; border-radius: 9px; background: var(--app-accent-weak, #eef2f7); color: var(--app-accent, #2e6be6); display: inline-flex; align-items: center; justify-content: center; font-weight: 700; }
.tx-detail-name { font-size: 16px; font-weight: 700; }
.tx-detail-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin: 16px 0; }
.tx-detail-kpis > div { border: 1px solid var(--app-border, #e6e9ed); border-radius: 8px; padding: 9px 10px; background: var(--app-panel-2, #fafbfc); text-align: center; }
.tx-detail-kpis b { display: block; font-size: 19px; }
.tx-detail-kpis span { font-size: 11px; color: var(--app-text-3, #98a0aa); }
.tx-detail-facts { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 18px; font-size: 12.5px; margin-bottom: 16px; }
.tx-detail-facts span { display: block; font-size: 11px; color: var(--app-text-3, #98a0aa); margin-bottom: 2px; }
.tx-detail-facts .span2 { grid-column: 1 / -1; }
.tx-detail-actions { display: flex; gap: 8px; flex-wrap: wrap; border-top: 1px solid var(--app-border, #e6e9ed); padding-top: 14px; }

/* 品牌预览 */
.tx-brand-wrap { display: grid; grid-template-columns: 1fr 300px; gap: 20px; }
.tx-brand-form :deep(.ant-form-item) { margin-bottom: 12px; }
.tx-brand-preview { align-self: start; }
.tx-preview-label { font-size: 11px; color: var(--app-text-3, #98a0aa); margin-bottom: 6px; }
.tx-preview-card { border: 1px solid var(--app-border, #e6e9ed); border-radius: 10px; padding: 14px; background: #fff; }
.tx-preview-brand { display: flex; align-items: center; gap: 8px; }
.tx-preview-mark { width: 28px; height: 28px; border-radius: 7px; background: var(--pv); color: #fff; display: inline-flex; align-items: center; justify-content: center; font-weight: 700; }
.tx-preview-logo { width: 28px; height: 28px; border-radius: 7px; object-fit: contain; }
.tx-preview-name { font-size: 13px; font-weight: 700; }
.tx-preview-hint { font-size: 10px; color: var(--app-text-3, #98a0aa); }
.tx-preview-form { margin-top: 12px; display: flex; flex-direction: column; gap: 6px; }
.tx-preview-input { height: 26px; border: 1px solid var(--app-border-strong, #d8dde3); border-radius: 5px; font-size: 10px; color: var(--app-text-3, #98a0aa); display: flex; align-items: center; padding: 0 8px; }
.tx-preview-btn { height: 28px; border-radius: 5px; background: var(--pv); color: #fff; font-size: 11px; display: flex; align-items: center; justify-content: center; }
.tx-preview-alt { margin-top: 10px; font-size: 10px; color: var(--app-text-2, #5f6570); text-align: center; }

/* 套餐 */
.tx-plan-preview { display: flex; gap: 20px; border: 1px solid var(--app-border, #e6e9ed); border-radius: 8px; padding: 10px 12px; background: var(--app-panel-2, #fafbfc); }
.tx-plan-preview span { display: block; font-size: 11px; color: var(--app-text-3, #98a0aa); }
.tx-plan-preview b { font-size: 13px; }

/* 归档 */
.tx-archive-title { font-size: 15px; font-weight: 650; margin: 0 0 8px; }
.tx-archive-consequences { font-size: 12.5px; line-height: 1.9; color: var(--app-text-2, #5f6570); margin-bottom: 14px; }

/* 交付 */
.tx-delivery { border: 1px solid var(--app-border, #e6e9ed); border-radius: 8px; overflow: hidden; }
.tx-delivery > div { display: flex; align-items: center; gap: 10px; padding: 8px 12px; border-bottom: 1px solid var(--app-border, #e6e9ed); font-size: 12px; }
.tx-delivery > div:last-child { border-bottom: 0; }
.tx-delivery span { width: 88px; flex: none; color: var(--app-text-3, #98a0aa); }
.tx-delivery code { word-break: break-all; }
.tx-delivery a { margin-left: auto; cursor: pointer; color: var(--app-accent, #2e6be6); }
.tx-delivery-actions { display: flex; align-items: center; gap: 8px; margin-top: 14px; flex-wrap: wrap; }
.tx-ok { color: var(--app-ok, #3b8f4e); }
.tx-warn { color: #a3691b; }

/* 落点清单 */
.inv-overview { display: flex; gap: 28px; padding: 4px 0 8px; }
.inv-cell .stat-num { font-size: 20px; font-weight: 600; }
.inv-cell .stat-label { font-size: 12px; color: var(--app-text-3, #888); }
.inv-divider { margin: 14px 0 8px; font-size: 13px; }
.inv-table-row { display: flex; align-items: center; gap: 10px; padding: 3px 0; font-size: 12px; }
.inv-rows { margin-left: auto; color: var(--app-text-3, #888); white-space: nowrap; }
.inv-kind { color: var(--app-text-2, #666); width: 72px; flex: none; }
.inv-path { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; direction: rtl; text-align: left; }
.inv-key { margin-bottom: 4px; }
.inv-global { margin: 0; padding-left: 18px; font-size: 12px; color: var(--app-text-2, #666); line-height: 1.9; }

@media (max-width: 900px) {
  .tx-kpis, .tx-detail-kpis { grid-template-columns: 1fr 1fr; }
  .tx-queue-wrap { grid-template-columns: 1fr; }
  .tx-queue { border-right: 0; border-bottom: 1px solid var(--app-border, #e6e9ed); }
  .tx-brand-wrap { grid-template-columns: 1fr; }
  .tx-invite-grid { grid-template-columns: 1fr 1fr; }
  .tx-facts, .tx-detail-facts { grid-template-columns: 1fr; }
  .tx-facts .span2, .tx-detail-facts .span2 { grid-column: auto; }
}
</style>
