<template>
  <div class="app-page">
    <div class="app-page-head">
      <h3 class="app-page-title">数据看板</h3>
      <span class="head-stat">问答质量与知识库缺口一览</span>
      <button class="app-btn ghost" style="margin-left:auto" :disabled="analyticsLoading" @click="reloadAll">刷新</button>
    </div>

    <div class="app-page-body">
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

      <!-- 入库弹窗 -->
      <a-modal v-model:open="addVisible" title="补充知识块" :footer="null" :width="640" destroy-on-close>
        <a-form layout="vertical">
          <a-form-item label="问题（自动作为标题）"><a-input v-model:value="addForm.title" disabled /></a-form-item>
          <a-form-item label="回答内容（请填写准确的回答内容或操作步骤）" required>
            <a-textarea v-model:value="addForm.content" :rows="6" placeholder="请补充准确的回答内容、操作步骤或说明" />
          </a-form-item>
          <a-form-item label="关联文档 ID（可选）"><a-input v-model:value="addForm.docId" /></a-form-item>
          <a-form-item>
            <button class="app-btn" :disabled="addLoading" @click="submitAdd">确认入库</button>
            <button class="app-btn ghost" style="margin-left:8px" @click="addVisible = false">取消</button>
          </a-form-item>
        </a-form>
      </a-modal>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, h } from 'vue'
import { message } from 'ant-design-vue'
import { getAnalytics, getUnmatchedQuestions, createKnowledge, getBadCases, addEvalCase, getEvalLastReport, runEvalAutoCheck } from '../api'

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
</style>
