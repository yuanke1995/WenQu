<template>
  <div class="app-page">
    <div class="app-page-head">
      <h3 class="app-page-title">数据看板</h3>
      <span class="head-stat">问答质量、执行 Trace 与知识回流闭环</span>
      <button class="app-btn ghost" style="margin-left:auto" :disabled="analyticsLoading" @click="reloadAll">刷新</button>
    </div>

    <div class="app-page-body">
      <a-tabs v-model:activeKey="tab">
        <!-- ============ 总览（原有统计 + 体检 + 差评/缺口） ============ -->
        <a-tab-pane key="overview" tab="总览">
          <!-- 核心指标卡 -->
          <a-spin :spinning="analyticsLoading">
            <div class="metric-grid">
              <div class="app-card metric"><div class="metric-label">问答总数</div><div class="metric-num">{{ summary.total || 0 }}</div></div>
              <div class="app-card metric"><div class="metric-label">无命中率</div><div class="metric-num">{{ summary.noHitRate || 0 }}<i class="metric-unit">%</i></div></div>
              <div class="app-card metric"><div class="metric-label">有引用标注</div><div class="metric-num">{{ summary.citationRate || 0 }}<i class="metric-unit">%</i></div></div>
              <div class="app-card metric"><div class="metric-label">反馈满意率</div>
                <div class="metric-num" :style="{ color: (fb.likeRate || 0) >= 80 ? 'var(--app-ok)' : 'var(--app-danger)' }">{{ fb.likeRate || 0 }}<i class="metric-unit">%</i></div>
                <div class="metric-sub">👍 {{ fb.likes || 0 }} · 👎 {{ fb.dislikes || 0 }}</div>
              </div>
            </div>
          </a-spin>

          <!-- 检索质量自动体检 -->
          <div class="app-card" style="margin-top:14px">
            <div class="app-card-title">检索质量自动体检
              <span class="card-sub">定时按线上参数跑评估集，指标较上期下滑即预警</span>
              <button class="app-btn ghost" style="margin-left:auto" :disabled="checkLoading" @click="doAutoCheck">立即体检</button>
            </div>
            <a-spin :spinning="checkLoading">
              <template v-if="report">
                <div style="margin-bottom:10px;display:flex;align-items:center;gap:10px">
                  <span class="app-pill" :class="checkPillClass">{{ checkStatusText }}</span>
                  <span style="color:var(--app-text2);font-size:12px">{{ report.message || '' }}</span>
                  <span v-if="report.runTime" style="color:var(--app-text3);font-size:11px">{{ report.runTime }}</span>
                </div>
                <a-table v-if="checkMetricRows.length" :data-source="checkMetricRows" :columns="checkCols" size="small"
                         row-key="name" :pagination="false" :locale="{ emptyText: '暂无指标' }" />
              </template>
              <a-empty v-else description="暂无体检记录（生成评估集后次日自动产生，或点右上角「立即体检」）" />
            </a-spin>
          </div>

          <div class="two-col">
            <div class="app-card">
              <div class="app-card-title">热门问题 TOP10</div>
              <a-table :data-source="summary.topQuestions || []" :columns="qCols" size="small"
                       row-key="question" :pagination="false" :loading="analyticsLoading" :locale="{ emptyText: '暂无数据' }" />
            </div>
            <div class="app-card">
              <div class="app-card-title">无命中问题 TOP10<span class="card-sub">建议补充知识库</span></div>
              <a-table :data-source="summary.noHitQuestions || []" :columns="noHitCols" size="small"
                       row-key="question" :pagination="false" :loading="analyticsLoading" :locale="{ emptyText: '暂无数据' }" />
            </div>
          </div>

          <!-- 差评样本 -->
          <div class="app-card" style="margin-top:14px">
            <div class="app-card-title">差评样本（反馈回流）
              <button class="app-btn ghost" style="margin-left:auto" @click="loadBadCases">刷新</button>
            </div>
            <a-table :data-source="badCases" :columns="badCols" size="small" row-key="messageId" :loading="badLoading"
                     :pagination="badCases.length > 10 ? { pageSize: 10 } : false"
                     :locale="{ emptyText: '暂无差评样本' }">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'action'">
                  <a-tooltip :title="record.knowledgeIds?.length ? '加入评估集（问题→引用过的知识块）' : '该轮回答无引用块，无法加入评估集'">
                    <button class="app-link-btn" :disabled="!record.knowledgeIds?.length || evalAdding === record.messageId" @click="addToEval(record)">加入评估集</button>
                  </a-tooltip>
                  <button class="app-link-btn" @click="openBadCaseAdd(record)">补知识块</button>
                </template>
              </template>
            </a-table>
          </div>

          <!-- 知识库缺口 -->
          <div class="app-card" style="margin-top:14px">
            <div class="app-card-title">知识库缺口管理（无命中问题汇总）
              <button class="app-btn ghost" style="margin-left:auto" @click="loadUnmatched">刷新</button>
            </div>
            <a-table :data-source="unmatchedList" :columns="unmatchedCols" size="small"
                     row-key="question" :pagination="{ pageSize: 10 }" :loading="unmatchedLoading"
                     :locale="{ emptyText: '暂无数据' }">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'action'">
                  <button class="app-link-btn" @click="openAdd(record)">入库</button>
                </template>
              </template>
            </a-table>
          </div>
        </a-tab-pane>

        <!-- ============ 执行 Trace（对话 + 工作流统一视图） ============ -->
        <a-tab-pane key="trace" tab="执行 Trace">
          <div class="app-card">
            <div class="trace-filters">
              <a-select v-model:value="traceKind" style="width: 110px" :options="[{ value: 'chat', label: '对话' }, { value: 'workflow', label: '工作流' }]" @change="loadTraces" />
              <a-select v-model:value="traceDays" style="width: 110px"
                        :options="[{ value: 1, label: '近 24 小时' }, { value: 7, label: '近 7 天' }, { value: 30, label: '近 30 天' }, { value: 0, label: '全部' }]"
                        @change="loadTraces" />
              <template v-if="traceKind === 'chat'">
                <a-select v-model:value="traceCitation" style="width: 120px" allow-clear placeholder="引用"
                          :options="[{ value: 1, label: '有引用' }, { value: 0, label: '无引用' }]" @change="loadTraces" />
                <a-select v-model:value="traceRating" style="width: 120px" allow-clear placeholder="评分"
                          :options="[{ value: 1, label: '👍 好评' }, { value: 0, label: '👎 差评' }]" @change="loadTraces" />
              </template>
              <a-input-search v-model:value="traceKeyword" placeholder="关键词（问题/入参）" style="width: 220px"
                              allow-clear @search="loadTraces" />
              <button class="app-btn ghost small" style="margin-left:auto" @click="loadTraces">查询</button>
            </div>
            <a-table :data-source="traceRows" :columns="traceCols" size="small" row-key="id"
                     :loading="traceLoading" :pagination="tracePagination" :locale="{ emptyText: '暂无执行记录' }"
                     @change="onTraceTableChange">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'statusCol'">
                  <template v-if="record.kind === 'workflow'">
                    <a-tag :color="wfStatusColor(record.status)">{{ wfStatusLabel(record.status) }}</a-tag>
                    <a-tag v-if="record.version != null" color="blue">v{{ record.version }}</a-tag>
                  </template>
                  <template v-else>
                    <span class="wf-run-dot" :class="record.hasCitation === 1 ? 'rd-success' : 'rd-failed'" />
                    <span class="trace-ct">{{ record.hasCitation === 1 ? '有引用' : '无引用' }}</span>
                    <span v-if="record.rating === 1" title="好评">👍</span>
                    <span v-else-if="record.rating === 0" title="差评">👎</span>
                  </template>
                </template>
                <template v-if="column.key === 'action'">
                  <button class="app-link-btn" @click="openTraceDetail(record)">详情</button>
                </template>
              </template>
            </a-table>
          </div>
        </a-tab-pane>

        <!-- ============ 采样池（自动采样 → 标注 → 回流评测集） ============ -->
        <a-tab-pane key="pool" tab="采样池">
          <div class="metric-grid" style="margin-bottom:12px">
            <div class="app-card metric">
              <div class="metric-label">待标注</div>
              <div class="metric-num">{{ poolStats.pending || 0 }}</div>
            </div>
            <div class="app-card metric"><div class="metric-label">已回流评测集</div><div class="metric-num">{{ poolStats.labeled || 0 }}</div></div>
            <div class="app-card metric"><div class="metric-label">已忽略</div><div class="metric-num">{{ poolStats.dismissed || 0 }}</div></div>
            <div class="app-card metric">
              <div class="metric-label">最近入池</div>
              <div class="metric-num" style="font-size:14px;line-height:1.6">{{ fmtTime(poolStats.lastSampledAt) }}</div>
              <button class="app-btn ghost small" style="margin-top:4px" :disabled="sampling" @click="doSample">立即采样</button>
            </div>
          </div>
          <div class="app-card">
            <div class="app-card-title">待标注样本
              <span class="card-sub">差评必采 + 无引用/随机按配置入池；标注「期望命中的知识块」后回流评测集</span>
              <a-select v-model:value="poolStatus" style="width: 110px; margin-left:auto"
                        :options="[{ value: 'pending', label: '待标注' }, { value: 'labeled', label: '已回流' }, { value: 'dismissed', label: '已忽略' }]"
                        @change="loadPool" />
            </div>
            <a-table :data-source="poolRows" :columns="poolCols" size="small" row-key="id"
                     :loading="poolLoading" :pagination="poolPagination" :locale="{ emptyText: '池子里没有样本（可点「立即采样」或等每日自动采样）' }"
                     @change="onPoolTableChange">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'source'">
                  <a-tag :color="{ bad: 'red', nohit: 'orange', random: 'blue' }[record.source] || 'default'">
                    {{ { bad: '差评', nohit: '无引用', random: '随机' }[record.source] || record.source }}
                  </a-tag>
                </template>
                <template v-if="column.key === 'action'">
                  <button class="app-link-btn" @click="openLabel(record)">标注回流</button>
                  <button class="app-link-btn danger" @click="doDismiss(record)">忽略</button>
                </template>
              </template>
            </a-table>
          </div>
        </a-tab-pane>

        <!-- ============ 费用（§13 Token 成本报表）：台账×模型单价，按用户/按模型 ============ -->
        <a-tab-pane key="cost" tab="费用">
          <div class="metric-grid" style="margin-bottom:12px">
            <div class="app-card metric">
              <div class="metric-label">区间费用</div>
              <div class="metric-num">{{ fmtCost(cost.total) }}</div>
              <a-segmented v-model:value="costRange" size="small" style="margin-top:6px"
                           :options="[{ value: 7, label: '近 7 日' }, { value: 30, label: '近 30 日' }]" @change="loadCost" />
            </div>
            <div class="app-card metric">
              <div class="metric-label">未计价 tokens</div>
              <div class="metric-num">{{ fmtTokensCompact(cost.unpricedTokens) }}</div>
              <div class="metric-sub" style="margin-top:6px">模型未登记单价，费用合计不含这部分</div>
            </div>
          </div>
          <div v-if="unpricedList.length || Number(cost.unregisteredTokens) > 0" class="app-card" style="margin-bottom:12px">
            <div class="app-card-title">未计价模型
              <span class="card-sub">{{ unpricedList.length
                  ? '在「智能体 → 模型供应商 → 管理模型 → 编辑」里登记单价后即可计入费用'
                  : '这些 token 对应的模型记录已不在册，无处登记单价' }}</span>
            </div>
            <div style="display:flex;flex-wrap:wrap;align-items:center;gap:6px;padding:4px 0 8px">
              <a-tag v-for="m in shownUnpriced" :key="m.label" color="orange">
                {{ m.label }} · {{ fmtTokensCompact(m.tokens) }}<template v-if="Number(m.refs) > 1">（{{ m.refs }} 个供应商）</template>
              </a-tag>
              <button v-if="unpricedList.length > UNPRICED_SHOWN" class="app-link-btn" @click="unpricedAll = !unpricedAll">
                {{ unpricedAll ? '收起' : `展开全部 ${unpricedList.length} 类` }}
              </button>
            </div>
            <div v-if="Number(cost.unregisteredTokens) > 0" class="metric-sub">
              另有 {{ fmtTokensCompact(cost.unregisteredTokens) }} tokens 来自已删除的模型记录或未记录模型的早期台账
              （{{ cost.unregisteredCount }} 个引用），不计入费用，也无法通过登记单价挽回。
            </div>
          </div>
          <!-- 只在真摊开多家时出现：单租户部署里这张表永远只有一行，等于把「本租户」再说一遍 -->
          <div v-if="cost.crossTenant && (cost.byTenant || []).length > 1" class="app-card">
            <div class="app-card-title">按租户结算<span class="card-sub">跨租户台账汇总；每个租户一行，费用按现行单价现算</span></div>
            <a-table :data-source="cost.byTenant || []" :columns="costTenantCols" size="small"
                     row-key="tenantId" :loading="costLoading" :pagination="costPager"
                     :locale="{ emptyText: '该时间范围内没有用量' }">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'cost'">{{ record.cost != null ? fmtCost(record.cost) : '未计价' }}</template>
              </template>
            </a-table>
          </div>
          <div class="two-col">
            <div class="app-card">
              <div class="app-card-title">按用户<span class="card-sub">台账输入/输出 tokens × 模型单价</span></div>
              <a-table :data-source="cost.byUser || []" :columns="costUserCols" size="small" row-key="uid"
                       :loading="costLoading" :pagination="costPager" :locale="{ emptyText: '该时间范围内没有用量' }">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'cost'">{{ record.cost != null ? fmtCost(record.cost) : '—' }}</template>
                </template>
              </a-table>
            </div>
            <div class="app-card">
              <div class="app-card-title">按模型<span class="card-sub">计价口径：输入/输出单价分开（元/百万 tokens）</span></div>
              <a-table :data-source="cost.byModel || []" :columns="costModelCols" size="small" row-key="model"
                       :loading="costLoading" :pagination="costPager" :locale="{ emptyText: '该时间范围内没有用量' }">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'cost'">{{ record.cost != null ? fmtCost(record.cost) : '未计价' }}</template>
                </template>
              </a-table>
            </div>
          </div>
          <div class="app-card">
            <div class="app-card-title">按部门<span class="card-sub">成员归属部门汇总；未归部门（含匿名与系统调用）单列一条</span></div>
            <a-table :data-source="cost.byDepartment || []" :columns="costDeptCols" size="small"
                     :row-key="r => r.departmentId || 'none'" :loading="costLoading" :pagination="costPager"
                     :locale="{ emptyText: '该时间范围内没有用量' }">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'cost'">{{ record.cost != null ? fmtCost(record.cost) : '未计价' }}</template>
              </template>
            </a-table>
          </div>
          <div class="app-card">
            <div class="app-card-title">
              配额与预算
              <span class="card-sub">上限管「谁最多能花多少」；用量按台账现算，与上面三张表同源</span>
              <button class="app-link-btn" style="margin-left:auto" @click="openQuota()">新建配额</button>
            </div>
            <a-table :data-source="quotas" :columns="quotaCols" size="small" row-key="id"
                     :loading="quotaLoading" :pagination="false"
                     :locale="{ emptyText: '还没设任何上限——当前不限额' }">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'tokenLimit'">
                  {{ Number(record.tokenLimit) > 0 ? fmtTokensCompact(record.tokenLimit) : '—' }}
                </template>
                <template v-else-if="column.key === 'costLimit'">
                  {{ record.costLimit != null ? fmtCost(record.costLimit) : '—' }}
                </template>
                <template v-else-if="column.key === 'used'">
                  <div>{{ fmtTokensCompact(record.usedTokens) }}<template v-if="record.costLimit != null"> · {{ fmtCost(record.usedCost) }}</template></div>
                  <div class="metric-sub">
                    {{ record.tokenPercent != null ? record.tokenPercent + '%' : '' }}<template v-if="record.tokenPercent != null && record.costPercent != null"> / </template>{{ record.costPercent != null ? record.costPercent + '%' : '' }}
                  </div>
                </template>
                <template v-else-if="column.key === 'state'">
                  <a-tag v-if="record.breach" :color="Number(record.enforce) === 1 ? 'red' : 'orange'">
                    {{ record.breach === 'token' ? 'token 到顶' : '费用到顶' }}·{{ Number(record.enforce) === 1 ? '已拦下' : '只告警' }}
                  </a-tag>
                  <a-tag v-else-if="Number(record.tokenPercent || 0) >= 80 || Number(record.costPercent || 0) >= 80" color="gold">接近上限</a-tag>
                  <span v-else class="metric-sub">正常</span>
                </template>
                <template v-else-if="column.key === 'op'">
                  <button class="app-link-btn" @click="openQuota(record)">编辑</button>
                  <button class="app-link-btn" style="margin-left:8px" @click="removeQuota(record)">删除</button>
                </template>
              </template>
            </a-table>
            <div class="metric-sub" style="padding-top:6px">
              到顶后拦的是用户主动发起的调用（对话 / 子智能体 / 工作流）；历史压缩、记忆提炼这类辅助调用不拦——
              掐断它们只会让上下文撑爆、这一轮反而更贵。
            </div>
          </div>
        </a-tab-pane>
      </a-tabs>

      <a-modal v-model:open="quotaModal" :title="quotaForm.id ? '编辑配额' : '新建配额'" @ok="submitQuota">
        <a-form layout="vertical">
          <a-form-item v-if="superadmin" label="归属租户"
                       extra="平台级管理员可给任一租户设限（如按合同封顶）；租户管理员固定为本租户">
            <a-select v-model:value="quotaForm.tenantId" :options="tenantOptions" style="width:100%"
                      :disabled="!!quotaForm.id" />
          </a-form-item>
          <a-form-item label="作用域">
            <a-segmented v-model:value="quotaForm.scope" :options="quotaScopeOptions" @change="onQuotaScopeChange" />
          </a-form-item>
          <a-form-item v-if="quotaForm.scope === 'department'" label="部门">
            <a-select v-model:value="quotaForm.scopeId" :options="deptOptions" placeholder="选部门" style="width:100%" />
          </a-form-item>
          <a-form-item v-if="quotaForm.scope === 'user'" label="成员">
            <a-select v-model:value="quotaForm.scopeId" :options="memberOptions" placeholder="选成员" style="width:100%"
                      show-search option-filter-prop="label" />
          </a-form-item>
          <a-form-item label="周期">
            <a-segmented v-model:value="quotaForm.period" :options="[{ value: 'day', label: '按日' }, { value: 'month', label: '按月' }]" />
          </a-form-item>
          <a-form-item label="token 上限（0＝不按 token 限）">
            <a-input-number v-model:value="quotaForm.tokenLimit" :min="0" :step="100000" style="width:100%"
                            :formatter="v => (v || 0).toLocaleString('zh-CN')" :parser="v => v.replace(/[^0-9]/g, '')" />
          </a-form-item>
          <a-form-item label="费用上限（元，留空＝不按金额限）">
            <a-input-number v-model:value="quotaForm.costLimit" :min="0" :step="1" style="width:100%" />
            <div class="metric-sub">金额只累计已登记单价的模型；未配单价的模型不进金额口径（token 口径照算）。</div>
          </a-form-item>
          <a-form-item label="到额处理">
            <a-switch v-model:checked="quotaForm.enforce" checked-children="熔断" un-checked-children="只告警" />
            <span class="metric-sub" style="margin-left:8px">新策略建议先「只告警」跑一个周期，确认拦得对再熔断。</span>
          </a-form-item>
          <a-form-item label="备注（为什么设这条）">
            <a-input v-model:value="quotaForm.remark" :maxlength="200" placeholder="如：外部合作账号只给 200 万/月" />
          </a-form-item>
        </a-form>
      </a-modal>

      <!-- Trace 详情抽屉 -->
      <a-drawer v-model:open="detailDrawer" :title="detailTitle" width="560" destroy-on-close>
        <a-spin :spinning="detailLoading">
          <template v-if="detail">
            <!-- 对话型 -->
            <template v-if="detail.kind === 'chat'">
              <div class="dt-block"><div class="dt-label">问题</div><pre class="dt-pre">{{ detail.log?.question }}</pre></div>
              <div class="dt-block"><div class="dt-label">回答摘要</div><pre class="dt-pre">{{ detail.log?.answerSummary }}</pre></div>
              <template v-if="detail.message">
                <div class="dt-block"><div class="dt-label">回答全文</div><pre class="dt-pre">{{ detail.message.content }}</pre></div>
                <div class="dt-block">
                  <div class="dt-label">引用来源（{{ (detail.message.sources || []).length }}）</div>
                  <div v-for="(s, i) in detail.message.sources" :key="i" class="dt-source">
                    <span class="dt-source-name">{{ s.title || s.docTitle || s.docId }}</span>
                    <span class="dt-meta">{{ s.knowledgeId ? '块 ' + s.knowledgeId.slice(0, 8) : '' }}</span>
                  </div>
                  <div v-if="!(detail.message.sources || []).length" class="dt-meta">无引用</div>
                </div>
                <div class="dt-block">
                  <div class="dt-label">工具调用（{{ (detail.message.toolCalls || []).length }}）· Token（{{ detail.message.tokens?.total || 0 }}）</div>
                  <pre v-if="(detail.message.toolCalls || []).length" class="dt-pre">{{ JSON.stringify(detail.message.toolCalls, null, 2) }}</pre>
                </div>
              </template>
              <div v-else class="dt-block"><div class="dt-label">全过程</div><div class="dt-meta">该记录早于 trace 关联键上线（无 message_id），只有摘要视图。</div></div>
              <div v-if="detail.feedback" class="dt-block">
                <div class="dt-label">用户反馈</div>
                <pre class="dt-pre">{{ detail.feedback.rating === 1 ? '👍 好评' : '👎 差评' }}{{ detail.feedback.text ? '：' + detail.feedback.text : '' }}</pre>
              </div>
              <div v-if="detail.sample" class="dt-block">
                <div class="dt-label">采样状态</div>
                <a-tag :color="detail.sample.status === 'pending' ? 'orange' : detail.sample.status === 'labeled' ? 'green' : 'default'">
                  {{ { pending: '待标注', labeled: '已回流', dismissed: '已忽略' }[detail.sample.status] }}
                </a-tag>
              </div>
            </template>
            <!-- 工作流型 -->
            <template v-else>
              <div class="dt-block">
                <div class="dt-label">运行信息</div>
                <div class="dt-kv"><span>工作流</span><b>{{ detail.workflowName }}</b></div>
                <div class="dt-kv"><span>状态</span><a-tag :color="wfStatusColor(detail.run?.status)">{{ wfStatusLabel(detail.run?.status) }}</a-tag></div>
                <div class="dt-kv"><span>触发</span>{{ { manual: '手动调试', api: 'API 触发', agent: '智能体对话' }[detail.run?.triggerType] || detail.run?.triggerType }}</div>
                <div class="dt-kv"><span>版本</span>{{ detail.run?.version != null ? 'v' + detail.run.version : '草稿' }}</div>
                <div class="dt-kv"><span>耗时</span>{{ detail.run?.durationMs != null ? detail.run.durationMs + ' ms' : '—' }}</div>
              </div>
              <div v-if="detail.run?.error" class="dt-block"><div class="dt-label">错误</div><pre class="dt-pre">{{ detail.run.error }}</pre></div>
              <div class="dt-block"><div class="dt-label">入参</div><pre class="dt-pre">{{ prettyJson(detail.run?.inputs) }}</pre></div>
              <div class="dt-block"><div class="dt-label">出参</div><pre class="dt-pre">{{ prettyJson(detail.run?.outputs) }}</pre></div>
              <div class="dt-block">
                <div class="dt-label">节点 trace（{{ wfTraces.length }}）</div>
                <div v-for="(t, i) in wfTraces" :key="i" class="wf-trace" @click="t._open = !t._open">
                  <div class="wf-trace-head">
                    <a-tag :color="{ success: 'green', failed: 'red', waiting: 'orange' }[t.status] || 'red'">{{ t.status }}</a-tag>
                    <span class="wf-trace-name">{{ t.nodeId }}<span class="dt-meta">（{{ t.type }}）</span></span>
                    <span class="dt-meta">{{ t.elapsedMs != null ? t.elapsedMs + ' ms' : '' }}</span>
                    <span class="dt-meta" style="margin-left:auto">{{ t._open ? '收起' : '展开' }}</span>
                  </div>
                  <div v-if="t.error" class="wf-run-err">{{ t.error }}</div>
                  <pre v-if="t._open && (t.input || t.output)" class="dt-pre">{{ JSON.stringify({ input: t.input, output: t.output }, null, 2) }}</pre>
                </div>
              </div>
            </template>
          </template>
        </a-spin>
      </a-drawer>

      <!-- 标注回流弹窗 -->
      <a-modal v-model:open="labelModal" title="标注并回流评测集" ok-text="回流" cancel-text="取消"
               :confirm-loading="labelLoading" @ok="submitLabel">
        <div class="dt-block">
          <div class="dt-label">问题</div>
          <pre class="dt-pre">{{ labelRow?.question }}</pre>
        </div>
        <div class="dt-block">
          <div class="dt-label">回答摘要</div>
          <pre class="dt-pre">{{ labelRow?.answerSummary }}</pre>
        </div>
        <div class="dt-block" style="margin-bottom:12px">
          <div class="dt-label">期望命中的知识块（该轮实际命中的已预填，可增删；评测语义是「这些块应当被检索到」）</div>
          <a-select v-model:value="labelKnowledgeIds" mode="tags" style="width: 100%" :open="false"
                    placeholder="知识块 ID（已按该轮命中预填）" :token-separators="[',', ' ']" />
        </div>
        <a-input v-model:value="labelNote" placeholder="备注（可选，如差评原因）" />
      </a-modal>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, h } from 'vue'
import { message } from 'ant-design-vue'
import {
  getAnalytics, getUnmatchedQuestions, createKnowledge, getBadCases, addEvalCase, getEvalLastReport, runEvalAutoCheck,
  listTraces, getChatTrace, getWorkflowTrace, listTracePool, labelTraceSample, dismissTraceSample, runTraceSampling, getTraceStats,
  getUsageCost, listQuotas, saveQuota, deleteQuota, listDepartments, listUsers, listTenants
} from '../api'
import { isSuperadminSync } from '../utils/auth'

const tab = ref('overview')
const qCols = [
  { title: '问题', dataIndex: 'question', key: 'question', ellipsis: true },
  { title: '次数', dataIndex: 'count', key: 'count', width: 70 }
]
const noHitCols = [
  { title: '问题', dataIndex: 'question', key: 'question', ellipsis: true },
  { title: '次数', dataIndex: 'count', key: 'count', width: 70 }
]
const unmatchedCols = [
  { title: '问题', dataIndex: 'question', key: 'question', ellipsis: true },
  { title: '次数', dataIndex: 'count', key: 'count', width: 70 },
  { title: '最近提问', dataIndex: 'latestTime', key: 'latestTime', width: 150 },
  { title: '操作', key: 'action', width: 80 }
]

const data = ref({})
const summary = computed(() => data.value || {})
const fb = computed(() => summary.value.feedback || {})

const analyticsLoading = ref(false)
const unmatchedLoading = ref(false)
const badLoading = ref(false)

// ==================== 执行 Trace ====================
const traceKind = ref('chat')
const traceDays = ref(7)
const traceCitation = ref(null)
const traceRating = ref(null)
const traceKeyword = ref('')
const traceRows = ref([])
const traceLoading = ref(false)
const tracePage = ref(1)
const traceTotal = ref(0)
const TRACE_SIZE = 20

const traceCols = computed(() => traceKind.value === 'workflow' ? [
  { title: '时间', dataIndex: 'time', key: 'time', width: 150, customRender: ({ text }) => fmtTime(text) },
  { title: '工作流', dataIndex: 'title', key: 'title', ellipsis: true },
  { title: '入参', dataIndex: 'sub', key: 'sub', ellipsis: true },
  { title: '状态', key: 'statusCol', width: 170 },
  { title: '耗时', dataIndex: 'elapsedMs', key: 'elapsedMs', width: 90, customRender: ({ text }) => text != null ? text + ' ms' : '—' },
  { title: '操作', key: 'action', width: 70 }
] : [
  { title: '时间', dataIndex: 'time', key: 'time', width: 150, customRender: ({ text }) => fmtTime(text) },
  { title: '智能体', dataIndex: 'agentName', key: 'agentName', width: 110, ellipsis: true, customRender: ({ text }) => text || '全局' },
  { title: '问题', dataIndex: 'title', key: 'title', ellipsis: true },
  { title: '状态', key: 'statusCol', width: 160 },
  { title: '命中', dataIndex: 'hitDocCount', key: 'hitDocCount', width: 70 },
  { title: '耗时', dataIndex: 'elapsedMs', key: 'elapsedMs', width: 90,
    customHeaderCell: () => ({ title: '这一轮机器实际干活的时间：等你在提问卡/计划卡上读卡答题的那段不算（日志里两个数都留着）' }),
    customRender: ({ text }) => text != null ? text + ' ms' : '—' },
  { title: '操作', key: 'action', width: 70 }
])

const tracePagination = computed(() => ({
  current: tracePage.value, total: traceTotal.value, pageSize: TRACE_SIZE, showSizeChanger: false
}))

const loadTraces = async (p) => {
  if (typeof p === 'number') tracePage.value = p
  else tracePage.value = 1
  traceLoading.value = true
  try {
    const r = await listTraces({
      kind: traceKind.value, page: tracePage.value, size: TRACE_SIZE,
      keyword: traceKeyword.value || undefined,
      hasCitation: traceKind.value === 'chat' ? traceCitation.value : undefined,
      rating: traceKind.value === 'chat' ? traceRating.value : undefined,
      days: traceDays.value
    })
    if (r.success) {
      traceRows.value = (r.data?.rows) || []
      traceTotal.value = r.data?.total || 0
    } else message.error(r.msg || '加载失败')
  } catch (e) { message.error(e.message || '加载失败（Trace 为管理员功能）') }
  finally { traceLoading.value = false }
}
const onTraceTableChange = pg => loadTraces(pg.current)

const wfStatusLabel = s => ({ running: '运行中', success: '成功', failed: '失败', timeout: '超时', waiting_approval: '待审批' }[s] || s)
const wfStatusColor = s => ({ running: 'processing', success: 'green', failed: 'red', timeout: 'orange', waiting_approval: 'orange' }[s] || 'default')

// 详情抽屉
const detailDrawer = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const detailTitle = computed(() => {
  if (!detail.value) return '执行详情'
  return detail.value.kind === 'workflow' ? `工作流 · ${detail.value.workflowName || ''}` : '对话执行详情'
})
const wfTraces = ref([])
const openTraceDetail = async record => {
  detailDrawer.value = true
  detailLoading.value = true
  detail.value = null
  wfTraces.value = []
  try {
    const r = record.kind === 'workflow' ? await getWorkflowTrace(record.id) : await getChatTrace(record.id)
    detail.value = r.data || null
    if (detail.value?.kind === 'workflow' && detail.value.run?.nodeTraces) {
      try { wfTraces.value = (JSON.parse(detail.value.run.nodeTraces) || []).map(t => ({ ...t, _open: false })) } catch (e) { wfTraces.value = [] }
    }
  } catch (e) { message.error(e.message || '详情加载失败') }
  finally { detailLoading.value = false }
}

const prettyJson = s => {
  try { return JSON.stringify(JSON.parse(s), null, 2) } catch (e) { return s || '（无）' }
}
const fmtTime = t => (t ? String(t).replace('T', ' ').slice(0, 16) : '—')

// ==================== 采样池 ====================
const poolStats = ref({})
const poolStatus = ref('pending')
const poolRows = ref([])
const poolLoading = ref(false)
const poolPage = ref(1)
const poolTotal = ref(0)
const POOL_SIZE = 20
const sampling = ref(false)

const poolCols = [
  { title: '入池时间', dataIndex: 'createdAt', key: 'createdAt', width: 140, customRender: ({ text }) => fmtTime(text) },
  { title: '来源', key: 'source', width: 80 },
  { title: '问题', dataIndex: 'question', key: 'question', ellipsis: true },
  { title: '回答摘要', dataIndex: 'answerSummary', key: 'answerSummary', ellipsis: true },
  { title: '操作', key: 'action', width: 150 }
]
const poolPagination = computed(() => ({
  current: poolPage.value, total: poolTotal.value, pageSize: POOL_SIZE, showSizeChanger: false
}))

const loadPoolStats = async () => {
  try {
    const r = await getTraceStats()
    if (r.success) poolStats.value = r.data || {}
  } catch (e) { /* 静默 */ }
}
const loadPool = async (p) => {
  if (typeof p === 'number') poolPage.value = p
  else poolPage.value = 1
  poolLoading.value = true
  try {
    const r = await listTracePool({ status: poolStatus.value, page: poolPage.value, size: POOL_SIZE })
    if (r.success) {
      poolRows.value = r.data?.rows || []
      poolTotal.value = r.data?.total || 0
    } else message.error(r.msg || '加载失败')
  } catch (e) { message.error(e.message || '加载失败（采样池为管理员功能）') }
  finally { poolLoading.value = false }
}
const onPoolTableChange = pg => loadPool(pg.current)

const doSample = async () => {
  sampling.value = true
  try {
    const r = await runTraceSampling()
    if (r.success) {
      const d = r.data || {}
      message.success(`采样完成：差评 ${d.bad || 0} / 无引用 ${d.nohit || 0} / 随机 ${d.random || 0}`)
      loadPool()
      loadPoolStats()
    } else message.error(r.msg || '采样失败')
  } catch (e) { message.error(e.message || '采样失败') }
  finally { sampling.value = false }
}

// 标注回流
const labelModal = ref(false)
const labelLoading = ref(false)
const labelRow = ref(null)
const labelKnowledgeIds = ref([])
const labelNote = ref('')
const openLabel = async record => {
  labelRow.value = record
  labelKnowledgeIds.value = []
  labelNote.value = record.note || ''
  labelModal.value = true
  // 预填该轮实际命中的知识块（trace 详情的 sources；存量行无关联则手动填）
  if (record.qaLogId || record.id) {
    try {
      const r = await getChatTrace(record.qaLogId)
      const src = r.data?.message?.sources || []
      const ids = src.map(s => s.knowledgeId).filter(Boolean)
      if (ids.length) labelKnowledgeIds.value = [...new Set(ids)]
    } catch (e) { /* 预填失败不阻断，手动填 */ }
  }
}
const submitLabel = async () => {
  if (!labelKnowledgeIds.value.length) { message.warning('请至少填 1 个期望命中的知识块'); return }
  labelLoading.value = true
  try {
    const r = await labelTraceSample(labelRow.value.id, labelKnowledgeIds.value, labelNote.value)
    if (r.success) {
      const ev = r.data?.eval || {}
      if (ev.added) message.success('已标注并回流评测集')
      else message.info(ev.reason || '评测集已存在相同问题，样本已标记为已回流')
      labelModal.value = false
      loadPool()
      loadPoolStats()
    } else message.error(r.msg || '回流失败')
  } catch (e) { message.error(e.message || '回流失败') }
  finally { labelLoading.value = false }
}
const doDismiss = record => {
  dismissTraceSample(record.id).then(r => {
    if (r.success) { message.success('已忽略'); loadPool(); loadPoolStats() }
    else message.error(r.msg || '操作失败')
  }).catch(e => message.error(e.message || '操作失败'))
}

// 检索质量自动体检
const report = ref(null)
const checkLoading = ref(false)
const checkCols = [
  { title: '指标', dataIndex: 'name', key: 'name', width: 200 },
  { title: '本期', dataIndex: 'value', key: 'value', width: 100 },
  { title: '较上期', key: 'delta', width: 110, customRender: ({ record }) => {
    if (record.delta === null || record.delta === undefined) return '—'
    const v = Number(record.delta)
    const color = v < -0.01 ? '#cf1322' : (v > 0.01 ? '#3f8600' : '#999')
    return h('span', { style: { color } }, (v > 0 ? '▲ +' : v < 0 ? '▼ ' : '') + v.toFixed(1) + '%')
  } },
  { title: '评价', key: 'note', width: 90, customRender: ({ record }) => {
    if (record.delta === null || record.delta === undefined) return '—'
    const v = Number(record.delta)
    return h('span', { style: { color: v < 0 ? '#cf1322' : '#3f8600', fontSize: 12 } }, v < 0 ? '下滑' : (v > 0 ? '提升' : '持平'))
  } }
]
const checkMetricRows = computed(() => {
  const m = report.value?.metrics
  const d = report.value?.deltaPct || {}
  if (!m || typeof m !== 'object') return []
  return Object.entries(m).map(([name, v]) => {
    const num = Number(v)
    return { name: name === 'MRR' ? name : name + (name.startsWith('recall') ? '（前' + name.replace('recall@', '') + '名命中率）' : '（命中率）'), value: (num * 100).toFixed(1) + '%', delta: d[name] === undefined ? null : Number(d[name]) }
  })
})
const checkStatusText = computed(() => ({
  ok: '正常', decline: '检索质量下滑', empty: '待评估集', error: '执行异常'
}[report.value?.status] || '—'))
const checkPillClass = computed(() => ({
  ok: 'ok', decline: 'err', empty: 'warn', error: 'err'
}[report.value?.status] || 'muted'))
const doAutoCheck = async () => {
  checkLoading.value = true
  try {
    const r = await runEvalAutoCheck()
    if (r.success) {
      report.value = r.data || null
      if (report.value?.status === 'decline') message.warning('体检完成：检测到检索质量下滑，建议对比调参')
      else message.success('体检完成')
    } else message.error(r.msg || '体检失败')
  } catch (e) { message.error(e.message || '体检失败') }
  finally { checkLoading.value = false }
}

// 差评样本
const badCases = ref([])
const evalAdding = ref('')
const badCols = [
  { title: '问题', dataIndex: 'question', key: 'question', ellipsis: true },
  { title: '回答摘要', dataIndex: 'answer', key: 'answer', ellipsis: true },
  { title: '反馈说明', dataIndex: 'feedbackText', key: 'feedbackText', ellipsis: true, customRender: ({ text }) => text || '—' },
  { title: '时间', dataIndex: 'time', key: 'time', width: 150, customRender: ({ text }) => text ? String(text).replace('T', ' ').slice(0, 16) : '—' },
  { title: '操作', key: 'action', width: 160 }
]
const loadBadCases = async () => {
  badLoading.value = true
  try {
    const r = await getBadCases()
    if (r.success) badCases.value = r.data || []
  } catch (e) { /* 静默 */ }
  finally { badLoading.value = false }
}
const addToEval = async record => {
  evalAdding.value = record.messageId
  try {
    const r = await addEvalCase(record.question, record.knowledgeIds || [])
    if (r.success) {
      if (r.data?.added) message.success('已加入评估集')
      else message.info(r.data?.reason || '评估集已存在相同问题')
    } else message.error(r.msg || '加入失败')
  } catch (e) { message.error(e.message || '加入失败') }
  finally { evalAdding.value = '' }
}
const openBadCaseAdd = record => openAdd({ question: record.question, answer: record.answer, docId: '' })

// 知识库缺口
const unmatchedList = ref([])
const addVisible = ref(false)
const addLoading = ref(false)
const addForm = ref({ title: '', content: '', docId: '' })
const openAdd = record => {
  addForm.value = { title: record.question, content: record.answer || '', docId: record.docId || '' }
  addVisible.value = true
}
const submitAdd = async () => {
  const f = addForm.value
  if (!f.content.trim()) { message.warning('请填写回答内容'); return }
  addLoading.value = true
  try {
    const r = await createKnowledge(f.title, f.content.trim(), f.docId.trim() || null)
    if (r.success) { message.success('知识块已创建（含向量召回）'); addVisible.value = false; loadUnmatched() }
    else message.error(r.msg || '创建失败')
  } catch (e) { message.error(e.message || '创建失败') }
  finally { addLoading.value = false }
}
const loadUnmatched = async () => {
  unmatchedLoading.value = true
  try {
    const r = await getUnmatchedQuestions()
    if (r.success) unmatchedList.value = Array.isArray(r.data) ? r.data : []
  } catch (e) { /* 静默 */ }
  finally { unmatchedLoading.value = false }
}

const reloadAll = async () => {
  analyticsLoading.value = true
  try {
    const r = await getAnalytics()
    if (r.success) data.value = r.data || {}
  } catch (e) { message.error('加载看板失败: ' + (e.message || '')) }
  finally { analyticsLoading.value = false }
}

// ==================== 费用（§13）：台账×模型单价，按用户/按模型/按部门/按租户 ====================
const costRange = ref(30)
const costLoading = ref(false)
const cost = ref({})
const quotas = ref([])
const quotaLoading = ref(false)
const quotaModal = ref(false)
const quotaForm = ref({ id: null, scope: 'user', scopeId: undefined, period: 'month',
  tokenLimit: 0, costLimit: null, enforce: true, remark: '', tenantId: undefined })
const quotaScopeOptions = [{ value: 'tenant', label: '本租户' }, { value: 'department', label: '按部门' }, { value: 'user', label: '按成员' }]
const deptOptions = ref([])
const memberOptions = ref([])
const tenantOptions = ref([])
// 平台级管理员能看到别家租户的规则（后端按行归属放开）；清单里出现多个租户时才多出「租户」一列
const superadmin = isSuperadminSync()
const quotaCols = computed(() => {
  const base = [
    { title: '作用域', dataIndex: 'scope', key: 'scope', width: 92, customRender: ({ text }) => ({ tenant: '本租户', department: '部门', user: '成员' }[text] || text) }
  ]
  const ids = new Set((quotas.value || []).map(q => q.tenantId || 'default'))
  if (ids.size > 1) {
    base.push({ title: '租户', key: 'tenant', width: 110,
      customRender: ({ record }) => (tenantOptions.value.find(t => t.value === (record.tenantId || 'default')) || {}).label || record.tenantId || 'default' })
  }
  return base.concat([
    { title: '对象', dataIndex: 'scopeName', key: 'scopeName', ellipsis: true },
    { title: '周期', dataIndex: 'period', key: 'period', width: 68, customRender: ({ text }) => (text === 'day' ? '按日' : '按月') },
    { title: 'token 上限', key: 'tokenLimit', width: 108, align: 'right' },
    { title: '费用上限', key: 'costLimit', width: 108, align: 'right' },
    { title: '已用 / 进度', key: 'used', width: 150 },
    { title: '状态', key: 'state', width: 132 },
    { title: '操作', key: 'op', width: 110 }
  ])
})
const loadQuotas = async () => {
  quotaLoading.value = true
  try {
    const r = await listQuotas()
    if (r.success) quotas.value = r.data || []
    else message.error(r.msg || '配额清单加载失败')
    if (superadmin && !tenantOptions.value.length) {
      const rt = await listTenants()
      if (rt.success) tenantOptions.value = (rt.data || []).map(t => ({ value: t.id, label: t.name || t.id }))
    }
  } catch (e) { message.error(e.message || '配额清单加载失败') } finally { quotaLoading.value = false }
}
const openQuota = async record => {
  quotaForm.value = record ? {
    id: record.id, scope: record.scope, scopeId: record.scopeId || undefined, period: record.period,
    tokenLimit: Number(record.tokenLimit || 0), costLimit: record.costLimit == null ? null : Number(record.costLimit),
    enforce: Number(record.enforce) !== 0, remark: record.remark || '',
    tenantId: superadmin ? (record.tenantId || 'default') : undefined
  } : { id: null, scope: 'user', scopeId: undefined, period: 'month', tokenLimit: 0, costLimit: null, enforce: true, remark: '',
    tenantId: superadmin ? 'default' : undefined }
  if (!deptOptions.value.length || !memberOptions.value.length) {
    try {
      const [d, m] = await Promise.all([listDepartments(), listUsers()])
      if (d.success) deptOptions.value = (d.data || []).map(x => ({ value: x.id, label: x.name }))
      if (m.success) memberOptions.value = (m.data || []).map(x => ({ value: x.uid, label: (x.username || x.uid) + '（' + x.uid + '）' }))
    } catch (e) { message.error('部门或成员名单加载失败：' + e.message) }
  }
  quotaModal.value = true
}
const onQuotaScopeChange = () => { quotaForm.value.scopeId = undefined }
const submitQuota = async () => {
  const f = quotaForm.value
  if (f.scope !== 'tenant' && !f.scopeId) { message.warning('要先选部门或成员'); return }
  if (!(Number(f.tokenLimit) > 0) && !(f.costLimit != null && Number(f.costLimit) > 0)) {
    message.warning('至少填一个上限，否则这条规则不限任何东西'); return
  }
  try {
    const r = await saveQuota({ id: f.id, scope: f.scope, scopeId: f.scopeId, period: f.period,
      tokenLimit: Number(f.tokenLimit || 0), costLimit: f.costLimit == null || f.costLimit === '' ? null : Number(f.costLimit),
      enforce: f.enforce ? 1 : 0, remark: f.remark,
      tenantId: superadmin ? (f.tenantId || 'default') : undefined })
    if (!r.success) { message.error(r.msg || '保存失败'); return }
    message.success('已保存')
    quotaModal.value = false
    loadQuotas()
  } catch (e) { message.error(e.message || '保存失败') }
}
const removeQuota = async record => {
  const label = (record.scopeName || record.scope) + ' · ' + (record.period === 'day' ? '按日' : '按月')
  if (!window.confirm('删除这条配额「' + label + '」？删除即解除该档约束。')) return
  try {
    const r = await deleteQuota(record.id)
    if (!r.success) { message.error(r.msg || '删除失败'); return }
    message.success('已删除')
    loadQuotas()
  } catch (e) { message.error(e.message || '删除失败') }
}
/** 未计价清单默认只展开前 8 类（按 token 降序）——曾经这里是几百个原始 providerId/模型名标签 */
const UNPRICED_SHOWN = 8
const unpricedAll = ref(false)
const unpricedList = computed(() => cost.value.unpricedModels || [])
const shownUnpriced = computed(() =>
  unpricedAll.value ? unpricedList.value : unpricedList.value.slice(0, UNPRICED_SHOWN))
const costPager = { pageSize: 10, showSizeChanger: false, hideOnSinglePage: true }
const loadCost = async () => {
  costLoading.value = true
  try {
    const r = await getUsageCost(costRange.value)
    if (r.success) cost.value = r.data || {}
    else message.error(r.msg || '费用报表加载失败')
  } catch (e) { message.error(e.message || '费用报表加载失败') }
  finally { costLoading.value = false }
}
const costUserCols = [
  { title: '用户', dataIndex: 'name', key: 'name' },
  { title: '输入 tok', dataIndex: 'promptTokens', key: 'promptTokens', width: 96, align: 'right', customRender: ({ text }) => fmtTokensCompact(text) },
  { title: '输出 tok', dataIndex: 'completionTokens', key: 'completionTokens', width: 96, align: 'right', customRender: ({ text }) => fmtTokensCompact(text) },
  { title: '费用', key: 'cost', width: 110, align: 'right' }
]
const costDeptCols = [
  { title: '部门', dataIndex: 'name', key: 'name' },
  { title: '输入 tok', dataIndex: 'promptTokens', key: 'promptTokens', width: 96, align: 'right', customRender: ({ text }) => fmtTokensCompact(text) },
  { title: '输出 tok', dataIndex: 'completionTokens', key: 'completionTokens', width: 96, align: 'right', customRender: ({ text }) => fmtTokensCompact(text) },
  { title: '费用', key: 'cost', width: 110, align: 'right' }
]
const costTenantCols = [
  { title: '租户', dataIndex: 'name', key: 'name' },
  { title: '标识', dataIndex: 'tenantId', key: 'tenantId', width: 110 },
  { title: '成员', dataIndex: 'members', key: 'members', width: 72, align: 'right' },
  { title: '输入 tok', dataIndex: 'promptTokens', key: 'promptTokens', width: 96, align: 'right', customRender: ({ text }) => fmtTokensCompact(text) },
  { title: '输出 tok', dataIndex: 'completionTokens', key: 'completionTokens', width: 96, align: 'right', customRender: ({ text }) => fmtTokensCompact(text) },
  { title: '费用', key: 'cost', width: 110, align: 'right' }
]
const costModelCols = [
  { title: '模型', dataIndex: 'label', key: 'label' },
  { title: '输入 tok', dataIndex: 'promptTokens', key: 'promptTokens', width: 96, align: 'right', customRender: ({ text }) => fmtTokensCompact(text) },
  { title: '输出 tok', dataIndex: 'completionTokens', key: 'completionTokens', width: 96, align: 'right', customRender: ({ text }) => fmtTokensCompact(text) },
  { title: '费用', key: 'cost', width: 110, align: 'right' }
]
/** 费用（元）：null=无可计费用；小额保留分位 */
const fmtCost = v => {
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  if (n === 0) return '¥0'
  if (n < 0.01) return '<¥0.01'
  return '¥' + n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
/** token 大数缩写（万/亿），看板表格用 */
const fmtTokensCompact = n => {
  const v = Number(n) || 0
  if (v >= 1e8) return (Math.round(v / 1e7) / 10) + '亿'
  if (v >= 1e4) return (Math.round(v / 1e3) / 10) + '万'
  return String(v)
}

onMounted(() => {
  loadBadCases()
  getEvalLastReport().then(r => { if (r.success) report.value = r.data || null }).catch(() => {})
  reloadAll()
  loadUnmatched()
  loadPoolStats()
  loadPool()
  loadTraces()
  loadCost()
  loadQuotas()
})
</script>

<style scoped>
.head-stat { font-size: 12px; color: var(--app-text3); }
.metric-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; }
.metric-label { font-size: 12px; color: var(--app-text3); margin-bottom: 4px; }
.metric-num { font-size: 24px; font-weight: 500; line-height: 1.2; }
.metric-unit { font-style: normal; font-size: 13px; color: var(--app-text3); margin-left: 2px; }
.metric-sub { font-size: 11px; color: var(--app-text3); margin-top: 4px; }
.card-sub { font-size: 11px; color: var(--app-text3); font-weight: 400; }
.two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-top: 14px; }
@media (max-width: 1000px) { .two-col { grid-template-columns: 1fr; } }

/* 执行 Trace */
.trace-filters { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.trace-ct { font-size: 12px; color: var(--app-text2); margin-right: 4px; }
.wf-run-dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; margin-right: 5px; vertical-align: middle; }
.rd-success { background: var(--app-ok); }
.rd-failed { background: var(--app-warn); }

/* 详情抽屉 */
.dt-block { margin-bottom: 12px; }
.dt-label { font-size: 12px; color: var(--app-text3); margin-bottom: 4px; }
.dt-pre {
  background: var(--app-panel); border-radius: 6px; padding: 8px 10px; margin: 0;
  font-size: 12px; max-height: 220px; overflow: auto; white-space: pre-wrap; word-break: break-all;
}
.dt-meta { font-size: 12px; color: var(--app-text3); }
.dt-source { display: flex; align-items: center; gap: 8px; padding: 3px 0; border-bottom: 1px dashed var(--app-border); }
.dt-source:last-child { border-bottom: none; }
.dt-source-name { font-size: 13px; }
.dt-kv { display: flex; align-items: center; gap: 10px; font-size: 13px; padding: 2px 0; }
.dt-kv > span { color: var(--app-text3); font-size: 12px; width: 48px; flex: none; }
.wf-trace { border-top: 1px dashed var(--app-border); padding: 6px 0; }
.wf-trace-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; cursor: pointer; }
.wf-trace-name { font-size: 13px; font-weight: 500; font-family: ui-monospace, Menlo, monospace; }
.wf-run-err { font-size: 12px; color: var(--app-danger, var(--app-danger)); margin-top: 4px; }
</style>
