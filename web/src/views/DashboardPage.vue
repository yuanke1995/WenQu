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
      </a-tabs>

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
              <div class="dt-block">
                <div class="dt-label">分段耗时</div>
                <pre class="dt-pre">{{ prettyStage(detail.log?.stageMs) }}</pre>
              </div>
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
  listTraces, getChatTrace, getWorkflowTrace, listTracePool, labelTraceSample, dismissTraceSample, runTraceSampling, getTraceStats
} from '../api'

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
  { title: '耗时', dataIndex: 'elapsedMs', key: 'elapsedMs', width: 90, customRender: ({ text }) => text != null ? text + ' ms' : '—' },
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

const prettyStage = s => {
  if (!s) return '（无分段）'
  try {
    const m = JSON.parse(s)
    const names = { rewrite: '改写', retrieve: '检索', generate: '生成', citation: '引用' }
    const keys = Object.keys(m)
    return keys.map((k, i) => {
      const next = i + 1 < keys.length ? m[keys[i + 1]] : null
      const own = next != null ? next - m[k] : null
      return `${names[k] || k}：${own != null ? own + ' ms（累计 ' + m[k] + '）' : m[k] + ' ms'}`
    }).join('\n')
  } catch (e) { return s }
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

onMounted(() => {
  loadBadCases()
  getEvalLastReport().then(r => { if (r.success) report.value = r.data || null }).catch(() => {})
  reloadAll()
  loadUnmatched()
  loadPoolStats()
  loadPool()
  loadTraces()
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
