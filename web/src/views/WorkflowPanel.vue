<template>
  <!-- 工作流列表（个人资产）：编辑进画布、运行历史页、发布与版本、删除。
       布局对齐 ScheduledPanel 同款卡片风格。 -->
  <div class="app-page">
    <!-- 编辑态（editingId !== null）或运行历史态，整页替换列表；列表头/列表体随之隐藏 -->
    <template v-if="editingId === null && historyId === null">
    <div class="app-page-head">
      <!-- 页名与 Tab 名重复，标题仅保留给读屏器（sr-only），视觉上从统计起头 -->
      <h1 class="app-page-title sr-only">工作流</h1>
      <span class="head-count">
        共 <b>{{ totalCount }}</b> 个 · 已发布 <b>{{ publishedCount }}</b> · 草稿 <b>{{ draftCount }}</b>
        <template v-if="shared.length"> · 共享给我的 <b>{{ shared.length }}</b> 个</template>
      </span>
      <span class="head-hint-plain">把「检索 → LLM → 条件 → 输出」画成一张图：DSL 是唯一真源，画布只是编辑器</span>
      <!-- M5 模板库与导入导出：模板选用即创建；导入吃导出的 JSON 文件；导出下载 DSL -->
      <button class="app-btn" style="margin-left:auto" @click="openTemplates">
        <appstore-outlined /> 模板库
      </button>
      <button class="app-btn" @click="pickImport">
        <upload-outlined /> 导入
      </button>
      <button class="app-btn" @click="openEditor('')">
        <plus-outlined /> 新建工作流
      </button>
      <!-- 批量区（分隔线独立成区，不与常规按钮挤作一堆）。默认收起——点「批量管理」进入批量模式
          （卡片出现勾选框）；批量模式内按钮常驻未选禁用；开关放最右：进出模式自身位置不动 -->
      <div v-if="mine.length" class="batch-group">
        <template v-if="batchMode">
          <a-checkbox :checked="allChecked" :indeterminate="someChecked" @change="toggleAll">全选</a-checkbox>
          <button class="app-btn ghost small" :disabled="!selected.length || batchBusy" @click="doBatchPublish">发布</button>
          <button class="app-btn ghost small" :disabled="!selected.length || batchBusy" @click="doBatchUnpublish">下线</button>
          <button class="app-btn ghost small" :disabled="!selected.length || batchBusy" @click="doBatchExport">导出</button>
          <button class="app-btn ghost small wf-del" :disabled="!selected.length || batchBusy" @click="doBatchDelete">删除</button>
        </template>
        <button class="app-btn ghost small" :class="{ 'batch-on': batchMode }" @click="toggleBatchMode">{{ batchMode ? '退出管理' : '批量管理' }}</button>
      </div>
      <input ref="importInput" type="file" accept=".json,application/json" style="display:none" @change="doImport" />
    </div>

    <div class="app-page-body">
      <a-spin :spinning="loading">
        <div v-if="!hasAny" class="app-card wf-empty">
          <p class="wf-empty-title">还没有工作流</p>
          <p class="head-hint-plain">
            例如「用户提问 → 知识库检索 → LLM 回答 → 按回答长度路由」——纯鼠标搭建，点运行即可调试，不用写任何 JSON
          </p>
        </div>

        <template v-else>
          <div v-for="sec in sections" :key="sec.key">
            <!-- 共享给我的分栏头（他人创建、对我可见） -->
            <div v-if="sec.key === 'shared' && sec.rows.length" class="wf-sec-head">
              <span class="wf-sec-title">共享给我的</span>
              <span class="wf-meta">他人创建；只读工作流可查看画布与运行已发布版本，可管理的可直接编辑</span>
            </div>
            <div v-if="sec.rows.length" class="wf-list">
              <div v-for="r in sec.rows" :key="r.id" class="app-card wf-card">
                <div class="wf-head">
                  <a-checkbox v-if="batchMode && r.mine" class="wf-check" :checked="selected.includes(r.id)" @change="toggleSelect(r.id)" />
                  <!-- 状态用「色点 + 文字」：一眼分清发布态，不靠大色块 -->
                  <span class="wf-dot" :class="r.status === 'published' ? 'dot-pub' : 'dot-draft'" />
                  <span class="wf-name">{{ r.name }}</span>
                  <span class="wf-status" :class="r.status === 'published' ? 'st-pub' : 'st-draft'">
                    {{ r.status === 'published' ? (r.publishedVersion != null ? '已发布 v' + r.publishedVersion : '已发布') : '草稿' }}
                  </span>
                  <span v-if="r.scheduleEnabled" class="wf-tag-sched"><clock-circle-outlined /> 定时</span>
                  <span v-if="r.callbackUrl" class="wf-tag-sched"><api-outlined /> 回调</span>
                  <span v-if="!r.mine" class="wf-tag-shared">共享</span>
                  <span class="wf-meta">{{ summary(r.dsl) }}</span>
                  <!-- 最近一次运行：右侧对齐，扫一眼知道"这个工作流现在能不能跑" -->
                  <span class="wf-last">
                    <template v-if="r.lastRunStatus">
                      <span class="wf-run-dot" :class="'rd-' + r.lastRunStatus" />
                      <span class="wf-run-txt">{{ runLabel(r.lastRunStatus) }}</span>
                      <span class="wf-meta">{{ fmtTime(r.lastRunAt) }}</span>
                    </template>
                    <span v-else class="wf-meta">尚未运行</span>
                  </span>
                </div>
                <div v-if="r.description" class="wf-desc">{{ r.description }}</div>
                <div class="wf-actions">
                  <button class="app-btn ghost small" @click="openEditor(r.id, !canManage(r))">
                    <edit-outlined /> {{ canManage(r) ? '编辑画布' : '查看画布（只读）' }}
                  </button>
                  <button class="app-btn ghost small" @click="openHistory(r)">
                    <history-outlined /> 运行历史
                  </button>
                  <button class="app-btn ghost small" @click="openVersions(r)">
                    <tags-outlined /> 版本
                  </button>
                  <template v-if="canManage(r)">
                    <button class="app-btn ghost small" @click="openShare(r)">
                      <team-outlined /> 共享
                    </button>
                    <button class="app-btn ghost small" @click="openAutomation(r)">
                      <clock-circle-outlined /> 定时/回调
                    </button>
                    <button class="app-btn ghost small" @click="doExport(r)">
                      <download-outlined /> 导出
                    </button>
                    <!-- 发布/下线：已发布版本被冻结，草稿继续可改 -->
                    <button v-if="r.status === 'published'" class="app-btn ghost small" :disabled="busyId === r.id" @click="doUnpublish(r)">
                      <stop-outlined /> 下线
                    </button>
                    <button v-else class="app-btn small" :disabled="busyId === r.id" @click="doPublish(r)">
                      <cloud-upload-outlined /> 发布
                    </button>
                    <button class="app-btn ghost small wf-del" @click="doDelete(r)">
                      <delete-outlined /> 删除
                    </button>
                  </template>
                  <span class="wf-meta wf-upd">更新于 {{ fmtTime(r.updateTime) }}</span>
                </div>
              </div>
            </div>
          </div>
        </template>
      </a-spin>
    </div>
  </template>

    <!-- 画布编辑器：整页替换列表（返回即回列表并刷新）。只读=共享给我且无管理权 -->
    <FlowEditor v-if="editingId !== null" :workflow-id="editingId" :readonly="editorReadonly" @back="closeEditor" @saved="load" />

    <!-- 运行历史页（M4：独立页，含 trace 回放与待审批裁决） -->
    <WorkflowRunHistory v-if="historyId !== null" :workflow-id="historyId" :name="historyName" :initial-run-id="initialRunId" @back="closeHistory" />

    <!-- 版本历史（发布历史 + 回滚） -->
    <a-modal v-model:open="versionModal" :title="`版本历史${versionRow ? ' · ' + versionRow.name : ''}`" :footer="null" width="620px">
      <a-spin :spinning="versionLoading">
        <div v-if="!versions.length" class="head-hint-plain">还没有发布过版本（在画布里点「发布」即冻结第一版）</div>
        <div v-else class="wf-versions">
          <div v-for="v in versions" :key="v.version" class="wf-version">
            <div class="wf-version-head">
              <a-tag :color="v.current ? 'green' : 'default'">v{{ v.version }}{{ v.current ? ' · 当前' : '' }}</a-tag>
              <span class="wf-meta">{{ fmtTime(v.publishedAt) }}</span>
              <span class="wf-meta">{{ v.publishedBy || '' }}</span>
              <button v-if="!v.current" class="app-btn ghost small" :disabled="busyId === versionRow.id" @click="doRollback(v)">
                <rollback-outlined /> 回滚到此版
              </button>
            </div>
            <div v-if="v.note" class="wf-version-note">{{ v.note }}</div>
          </div>
        </div>
      </a-spin>
    </a-modal>

    <!-- M5 模板库：选用即以模板 DSL 创建工作流并进画布 -->
    <a-modal v-model:open="tplModal" title="模板库" :footer="null" width="640px">
      <a-spin :spinning="tplLoading">
        <div class="head-hint-plain" style="margin-bottom:10px">
          内置模板都是可运行的 DSL：选用即创建工作流并进入画布，改好模型 / 知识库等配置后即可调试运行
        </div>
        <div class="wf-tpl-list">
          <div v-for="t in templates" :key="t.key" class="app-card wf-tpl">
            <div class="wf-head">
              <span class="wf-name">{{ t.name }}</span>
              <span class="wf-meta">{{ t.nodeCount }} 节点</span>
              <button class="app-btn small" style="margin-left:auto" :disabled="tplCreating" @click="useTemplate(t)">
                使用模板
              </button>
            </div>
            <div class="wf-desc">{{ t.description }}</div>
          </div>
        </div>
      </a-spin>
    </a-modal>

    <!-- 第 1 期：共享范围（复用统一共享弹窗，v2 share_config，与知识库/智能体同款） -->
    <ShareScopeModal v-model:open="shareModal" resource-label="工作流" read-verb="查看并运行"
                     :share-config="shareRow ? shareRow.shareConfig : ''" :save-fn="saveShare" @saved="load" />

    <!-- 第 1 期：定时触发 + 终态回调 -->
    <a-modal v-model:open="autoModal" :title="`定时与回调${autoRow ? ' · ' + autoRow.name : ''}`"
             :confirm-loading="autoSaving" ok-text="保存" cancel-text="取消" width="560px" @ok="saveAutomation">
      <div class="auto-block">
        <div class="auto-block-title">定时触发</div>
        <div class="auto-row">
          <span class="auto-label">启用</span>
          <a-switch v-model:checked="autoForm.scheduleEnabled" size="small" />
          <span class="wf-meta">只运行<strong>已发布版本</strong>；草稿变更不生效</span>
        </div>
        <div class="auto-row">
          <span class="auto-label">cron</span>
          <a-input v-model:value="autoForm.cron" placeholder="分 时 日 月 周，如 0 9 * * 1-5 表示工作日 9 点" allow-clear />
        </div>
        <div class="auto-row">
          <span class="auto-label">时区</span>
          <a-input v-model:value="autoForm.timezone" placeholder="Asia/Shanghai" allow-clear />
        </div>
        <div v-if="autoNextRunAt" class="wf-meta auto-hint">下次执行：{{ fmtTime(autoNextRunAt) }}</div>
        <div v-else-if="autoForm.scheduleEnabled" class="wf-meta auto-hint">保存后按 cron 计算下次执行时刻</div>
      </div>
      <div class="auto-block">
        <div class="auto-block-title">运行终态回调</div>
        <div class="auto-row">
          <span class="auto-label">地址</span>
          <a-input v-model:value="autoForm.callbackUrl" placeholder="https://your.app/hook（运行成功/失败/超时后 POST JSON）" allow-clear />
        </div>
        <div class="auto-row">
          <span class="auto-label">签名密钥</span>
          <a-input-password v-model:value="autoForm.callbackSecret"
                            :placeholder="autoSecretSet ? '已设置（留空保持不变）' : '选填：HMAC-SHA256 签名密钥'" />
        </div>
        <div class="wf-meta auto-hint">
          回调头带 X-Wenqu-Signature: sha256=…（对请求体做 HMAC-SHA256）；地址不得指向内网
        </div>
      </div>
      <template #footer>
        <button class="app-btn ghost small" :disabled="autoBusy" @click="doRunScheduleNow">
          <play-circle-outlined /> 立即运行一次
        </button>
        <span class="flex-gap"></span>
        <button class="app-btn ghost small" @click="autoModal = false">取消</button>
        <button class="app-btn small" :disabled="autoSaving" @click="saveAutomation">保存</button>
      </template>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import {
  PlusOutlined, EditOutlined, DeleteOutlined, HistoryOutlined, CloudUploadOutlined,
  StopOutlined, RollbackOutlined, TagsOutlined, AppstoreOutlined, UploadOutlined, DownloadOutlined,
  TeamOutlined, ClockCircleOutlined, ApiOutlined, PlayCircleOutlined
} from '@ant-design/icons-vue'
import FlowEditor from './FlowEditor.vue'
import WorkflowRunHistory from './WorkflowRunHistory.vue'
import ShareScopeModal from './ShareScopeModal.vue'
import {
  listWorkflows, deleteWorkflow, publishWorkflow, unpublishWorkflow,
  listWorkflowVersions, rollbackWorkflow,
  listWorkflowTemplates, createWorkflow, getWorkflow,
  batchDeleteWorkflows, batchPublishWorkflows, batchUnpublishWorkflows,
  shareWorkflow, getWorkflowAutomation, saveWorkflowAutomation, runWorkflowScheduleNow
} from '../api'

const mine = ref([])            // 我创建的
const shared = ref([])          // 共享给我的（他人创建、对我可读/可管理）
const loading = ref(false)
const editingId = ref(null)     // null = 列表；'' = 新建；'id' = 编辑
const editorReadonly = ref(false)   // 画布只读（共享给我且无管理权）
const historyId = ref(null)     // 非 null = 运行历史页
const historyName = ref('')
const initialRunId = ref(null)  // 通知深链：打开运行历史后自动选中并定位到该 run（审批用）
const route = useRoute()
const busyId = ref(null)        // 正在执行发布/下线/回滚的工作流 id
const versionModal = ref(false)
const versionLoading = ref(false)
const versionRow = ref(null)
const versions = ref([])

/** 是否有可展示的工作流 */
const hasAny = computed(() => mine.value.length + shared.value.length > 0)
/** 分栏：我创建的 / 共享给我的（空栏不渲染） */
const sections = computed(() => [
  { key: 'mine', rows: mine.value },
  { key: 'shared', rows: shared.value }
])
/** 页头统计：共 = 我创建的 + 共享给我的；已发布/草稿按全部可见条目拆分（与卡片状态口径一致） */
const totalCount = computed(() => mine.value.length + shared.value.length)
const publishedCount = computed(() => [...mine.value, ...shared.value].filter(r => r.status === 'published').length)
const draftCount = computed(() => totalCount.value - publishedCount.value)
/** 是否可管理（编辑/发布/共享/删除）；共享只读为 false */
const canManage = r => r.myPermission === 'MANAGE'

// ---- 批量操作：卡片勾选 + 全选 + 批量发布/下线/导出/删除 ----
const selected = ref([])        // 勾选的工作流 id
const batchBusy = ref(false)
// 批量模式默认关闭：卡片不显示勾选框，点「批量管理」才进入（退出即清空勾选）
const batchMode = ref(false)
const toggleBatchMode = () => {
  batchMode.value = !batchMode.value
  if (!batchMode.value) selected.value = []
}
const allChecked = computed(() => mine.value.length > 0 && selected.value.length === mine.value.length)
const someChecked = computed(() => selected.value.length > 0 && selected.value.length < mine.value.length)
const toggleSelect = id => {
  selected.value = selected.value.includes(id)
    ? selected.value.filter(x => x !== id)
    : [...selected.value, id]
}
const toggleAll = () => { selected.value = allChecked.value ? [] : mine.value.map(r => r.id) }

/** 批量结果汇报：全成功走 message；有失败条目逐条弹 Modal 列出原因（不静默吞） */
const reportBatch = (r, verb) => {
  const data = (r && r.data) || {}
  const okCount = (data.succeeded || []).length
  const failed = data.failed || []
  if (!failed.length) {
    message.success(`已${verb} ${okCount} 个工作流`)
    return
  }
  Modal.warning({
    title: `${verb}完成：成功 ${okCount} 个，失败 ${failed.length} 个`,
    content: failed.map(f => `「${f.name || f.id}」：${f.error}`).join('；'),
    okText: '知道了'
  })
}

const doBatchDelete = () => {
  Modal.confirm({
    title: `删除选中的 ${selected.value.length} 个工作流？`,
    content: '工作流定义会被删除；已产生的运行记录会保留（回放与审计价值独立于定义存在）。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      batchBusy.value = true
      try {
        const r = await batchDeleteWorkflows([...selected.value])
        reportBatch(r, '删除')
        await load()
      } catch (e) {
        message.error('批量删除失败：' + (e.message || ''))
      } finally {
        batchBusy.value = false
      }
    }
  })
}

const doBatchPublish = async () => {
  batchBusy.value = true
  try {
    const r = await batchPublishWorkflows([...selected.value], '')
    reportBatch(r, '发布')
    await load()
  } catch (e) {
    message.error('批量发布失败：' + (e.message || ''))
  } finally {
    batchBusy.value = false
  }
}

const doBatchUnpublish = async () => {
  batchBusy.value = true
  try {
    const r = await batchUnpublishWorkflows([...selected.value])
    reportBatch(r, '下线')
    await load()
  } catch (e) {
    message.error('批量下线失败：' + (e.message || ''))
  } finally {
    batchBusy.value = false
  }
}

/** 批量导出：逐个取详情，合并为一个 {items:[...]} 文件下载（导入端识别 items 循环创建） */
const doBatchExport = async () => {
  batchBusy.value = true
  try {
    const items = []
    for (const id of selected.value) {
      const r = await getWorkflow(id)
      const wf = (r && r.data) || {}
      if (!wf.dsl) { message.error(`「${wf.name || id}」没有 DSL 可导出，已中止`); return }
      items.push({ name: wf.name, description: wf.description, dsl: JSON.parse(wf.dsl) })
    }
    const payload = {
      exportedFrom: 'wenqu-workflow',
      exportedAt: new Date().toISOString(),
      items
    }
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `workflows-${items.length}.workflow.json`
    a.click()
    URL.revokeObjectURL(a.href)
    message.success(`已导出 ${items.length} 个工作流`)
  } catch (e) {
    message.error('批量导出失败：' + (e.message || ''))
  } finally {
    batchBusy.value = false
  }
}

const load = async () => {
  loading.value = true
  try {
    const r = await listWorkflows()
    const d = (r && r.data) || {}
    mine.value = d.mine || []
    shared.value = d.shared || []
    // 勾选与现存列表对账：已被删掉的 id 从选中集合里清掉（避免批量操作撞「不存在」）
    const alive = new Set(mine.value.map(x => x.id))
    selected.value = selected.value.filter(id => alive.has(id))
    // 通知深链：?wf=&run= 直达某工作流的运行历史并定位到指定 run（工作流待审核通知用）
    maybeDeepLink()
  } catch (e) {
    message.error('工作流加载失败：' + (e.message || '请刷新重试'))
    mine.value = []
    shared.value = []
  } finally {
    loading.value = false
  }
}

/** 通知深链：route.query.wf/run 命中本地工作流时，打开运行历史并定位到指定 run */
const maybeDeepLink = () => {
  const wf = route.query.wf
  const run = route.query.run
  if (!wf) return
  const found = [...mine.value, ...shared.value].find(x => x.id === wf)
  if (found) {
    openHistory(found)
    if (run) initialRunId.value = String(run)
  }
}

const openEditor = (id, readonly = false) => { editingId.value = id; editorReadonly.value = readonly }
const closeEditor = () => { editingId.value = null; load() }
const openHistory = row => { historyId.value = row.id; historyName.value = row.name }
const closeHistory = () => { historyId.value = null; initialRunId.value = null; load() }

const doDelete = row => {
  Modal.confirm({
    title: '删除该工作流？',
    content: '工作流定义会被删除；已产生的运行记录会保留（回放与审计价值独立于定义存在）。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      try {
        const r = await deleteWorkflow(row.id)
        if (r && r.success) { message.success('已删除'); await load() }
        else message.error((r && r.msg) || '删除失败')
      } catch (e) {
        message.error('删除失败：' + (e.message || ''))
      }
    }
  })
}

/** 发布：把库里的草稿冻结成一版（未发布的草稿改动画布不影响任何对外行为） */
const doPublish = async row => {
  busyId.value = row.id
  try {
    const r = await publishWorkflow(row.id, '')
    message.success(`已发布 v${r.data.version}（API 触发与智能体绑定将使用这一版）`)
    await load()
  } catch (e) {
    message.error('发布失败：' + (e.message || ''))
  } finally {
    busyId.value = null
  }
}

const doUnpublish = async row => {
  busyId.value = row.id
  try {
    await unpublishWorkflow(row.id)
    message.success('已下线：API 触发与智能体绑定随即不可用')
    await load()
  } catch (e) {
    message.error('下线失败：' + (e.message || ''))
  } finally {
    busyId.value = null
  }
}

const openVersions = async row => {
  versionRow.value = row
  versions.value = []
  versionModal.value = true
  versionLoading.value = true
  try {
    const r = await listWorkflowVersions(row.id)
    versions.value = (r && r.data) || []
  } catch (e) {
    message.error('版本历史加载失败：' + (e.message || ''))
  } finally {
    versionLoading.value = false
  }
}

/** 回滚 = 以该版本 DSL 再发一版（历史不被改写，可再次回滚） */
const doRollback = async v => {
  const row = versionRow.value
  Modal.confirm({
    title: `回滚到 v${v.version}？`,
    content: '会以该版本的 DSL 发布为新版本（版本号递增），当前已发布版本不受影响、仍可回滚回来。',
    okText: '回滚', cancelText: '取消',
    onOk: async () => {
      busyId.value = row.id
      try {
        const r = await rollbackWorkflow(row.id, v.version)
        message.success(`已回滚到 v${v.version}，发布为 v${r.data.version}`)
        versionModal.value = false
        await load()
      } catch (e) {
        message.error('回滚失败：' + (e.message || ''))
      } finally {
        busyId.value = null
      }
    }
  })
}

// ---- 第 1 期：共享范围 / 定时与回调 ----
const shareModal = ref(false)
const shareRow = ref(null)
const openShare = row => { shareRow.value = row; shareModal.value = true }
/** 共享弹窗保存回调：json 为 v2 share_config（空串=私有），由后端强校验 manage ⊆ read */
const saveShare = json => shareWorkflow(shareRow.value.id, json)

const autoModal = ref(false)
const autoRow = ref(null)
const autoSaving = ref(false)
const autoBusy = ref(false)
const autoSecretSet = ref(false)
const autoNextRunAt = ref(null)
const autoForm = ref({ scheduleEnabled: false, cron: '', timezone: 'Asia/Shanghai', callbackUrl: '', callbackSecret: '' })

const openAutomation = async row => {
  autoRow.value = row
  autoForm.value = { scheduleEnabled: false, cron: '', timezone: 'Asia/Shanghai', callbackUrl: '', callbackSecret: '' }
  autoSecretSet.value = false
  autoNextRunAt.value = null
  autoModal.value = true
  try {
    const r = await getWorkflowAutomation(row.id)
    const d = (r && r.data) || {}
    autoForm.value = {
      scheduleEnabled: !!d.scheduleEnabled,
      cron: d.cron || '',
      timezone: d.timezone || 'Asia/Shanghai',
      callbackUrl: d.callbackUrl || '',
      callbackSecret: ''
    }
    autoSecretSet.value = !!d.callbackSecretSet
    autoNextRunAt.value = d.nextRunAt || null
  } catch (e) {
    message.error('自动化配置加载失败：' + (e.message || ''))
  }
}

const saveAutomation = async () => {
  if (!autoRow.value) return
  if (autoForm.value.scheduleEnabled && !String(autoForm.value.cron || '').trim()) {
    message.warning('启用定时触发前请填写 cron 表达式')
    return
  }
  autoSaving.value = true
  try {
    const body = {
      scheduleEnabled: autoForm.value.scheduleEnabled,
      cron: autoForm.value.cron,
      timezone: autoForm.value.timezone,
      callbackUrl: autoForm.value.callbackUrl
    }
    // 密钥留空 = 不修改（placeholder 已提示）；填了才覆盖
    if (String(autoForm.value.callbackSecret || '').trim()) body.callbackSecret = autoForm.value.callbackSecret
    const r = await saveWorkflowAutomation(autoRow.value.id, body)
    if (r && r.success) {
      message.success('自动化配置已保存')
      autoModal.value = false
      await load()
    } else {
      message.error((r && r.msg) || '保存失败')
    }
  } catch (e) {
    message.error('保存失败：' + (e.message || ''))
  } finally {
    autoSaving.value = false
  }
}

/** 立即运行一次（跑已发布版本）：同步返回终态 run，失败也展示原因 */
const doRunScheduleNow = async () => {
  if (!autoRow.value) return
  autoBusy.value = true
  try {
    const r = await runWorkflowScheduleNow(autoRow.value.id)
    const run = (r && r.data) || {}
    if (run.status === 'success') message.success('定时运行成功（已发布版本）')
    else message.error('定时运行结束：' + runLabel(run.status) + (run.error ? '：' + run.error : ''))
    await load()
  } catch (e) {
    message.error('触发失败：' + (e.message || ''))
  } finally {
    autoBusy.value = false
  }
}

/** DSL 摘要：节点/边数量 */
const summary = dslText => {
  try {
    const d = JSON.parse(dslText)
    return `${(d.nodes || []).length} 节点 / ${(d.edges || []).length} 边`
  } catch (e) { return 'DSL 解析失败' }
}

// ---- M5 模板库 / 导入 / 导出 ----
const tplModal = ref(false)
const tplLoading = ref(false)
const tplCreating = ref(false)
const templates = ref([])

const openTemplates = async () => {
  tplModal.value = true
  tplLoading.value = true
  templates.value = []
  try {
    const r = await listWorkflowTemplates()
    templates.value = (r && r.data) || []
  } catch (e) {
    message.error('模板加载失败：' + (e.message || ''))
  } finally {
    tplLoading.value = false
  }
}

/** 选用模板：以模板 DSL 创建工作流（名称用模板名，画布里可再改），创建后直接进画布 */
const useTemplate = async t => {
  tplCreating.value = true
  try {
    const r = await createWorkflow({ name: t.name, description: t.description, dsl: t.dsl })
    if (r && r.success) {
      message.success(`已从模板「${t.name}」创建工作流`)
      tplModal.value = false
      openEditor(r.data.id)   // 进画布继续配置（模型/知识库）
    } else {
      message.error((r && r.msg) || '模板创建失败')
    }
  } catch (e) {
    message.error('模板创建失败：' + (e.message || ''))
  } finally {
    tplCreating.value = false
  }
}

const importInput = ref(null)
const pickImport = () => { importInput.value && importInput.value.click() }

/** 导入：读导出的 JSON 文件（包装格式 {name, description, dsl} 或裸 DSL 均可），走创建接口复用全部校验 */
const doImport = async ev => {
  const file = ev.target.files && ev.target.files[0]
  ev.target.value = ''   // 允许连续导入同一文件
  if (!file) return
  try {
    const text = await file.text()
    const parsed = JSON.parse(text)
    // 批量导出格式（{items:[...]}）：逐条创建，部分失败逐条汇报
    if (parsed && Array.isArray(parsed.items)) { await importMany(parsed.items, file.name); return }
    const dsl = parsed && parsed.dsl ? parsed.dsl : parsed
    const name = (parsed && typeof parsed.name === 'string' && parsed.name.trim()) || '导入的工作流'
    if (!dsl || !Array.isArray(dsl.nodes)) {
      message.error('文件里没有可识别的 DSL（缺少 nodes 字段）')
      return
    }
    const r = await createWorkflow({
      name,
      description: (parsed && parsed.description) || `导入自 ${file.name}`,
      dsl: JSON.stringify(dsl)
    })
    if (r && r.success) {
      message.success(`已导入「${name}」`)
      await load()
      openEditor(r.data.id)
    } else {
      message.error((r && r.msg) || '导入失败：DSL 校验未通过')
    }
  } catch (e) {
    message.error('导入失败：' + (e.message || '（不是合法 JSON 文件）'))
  }
}

/** 批量导入：items 数组逐条走创建接口（复用全部校验）；成功 n 条 + 失败条目逐条带原因 */
const importMany = async (items, fileName) => {
  let ok = 0
  const failed = []
  for (const item of items) {
    if (!item || !item.dsl || !Array.isArray(item.dsl.nodes)) {
      failed.push(`「${(item && item.name) || '未命名'}」：没有可识别的 DSL（缺少 nodes 字段）`)
      continue
    }
    try {
      const r = await createWorkflow({
        name: item.name || '导入的工作流',
        description: item.description || `导入自 ${fileName}`,
        dsl: JSON.stringify(item.dsl)
      })
      if (r && r.success) ok++
      else failed.push(`「${(item && item.name) || '未命名'}」：${(r && r.msg) || '校验未通过'}`)
    } catch (e) {
      failed.push(`「${(item && item.name) || '未命名'}」：${e.message || ''}`)
    }
  }
  await load()
  if (!failed.length) message.success(`已导入 ${ok} 个工作流`)
  else Modal.warning({
    title: `导入完成：成功 ${ok} 个，失败 ${failed.length} 个`,
    content: failed.join('；'),
    okText: '知道了'
  })
}

/** 导出：下载 {exportedFrom, name, description, dsl} 包装 JSON（导入接口同款可再导入） */
const doExport = async row => {
  try {
    const r = await getWorkflow(row.id)
    const wf = (r && r.data) || {}
    if (!wf.dsl) { message.error('该工作流没有 DSL 可导出'); return }
    const payload = {
      exportedFrom: 'wenqu-workflow',
      exportedAt: new Date().toISOString(),
      name: wf.name,
      description: wf.description,
      dsl: JSON.parse(wf.dsl)   // 导出为 DSL 对象（可读），导入时 stringify 回字符串
    }
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `${wf.name || 'workflow'}.workflow.json`
    a.click()
    URL.revokeObjectURL(a.href)
  } catch (e) {
    message.error('导出失败：' + (e.message || ''))
  }
}

const runLabel = s => ({ queued: '排队中', running: '运行中', success: '成功', failed: '失败', timeout: '超时', waiting_approval: '待审批' }[s] || s)
const runColor = s => ({ queued: 'default', running: 'processing', success: 'green', failed: 'red', timeout: 'orange', waiting_approval: 'orange' }[s] || 'default')
const fmtTime = t => (t ? String(t).replace('T', ' ').slice(0, 16) : '—')

onMounted(load)
// 通知深链：停留在工作流 Tab 时再次点击不同通知 → 重新定位 run
watch(() => [route.query.wf, route.query.run], () => { if (mine.value.length || shared.value.length) maybeDeepLink() })
</script>

<style scoped>
/* 页头统计（与技能 / MCP 面板同款）：数字加重、小字说明 */
.head-count { font-size: 12px; color: var(--app-text2); white-space: nowrap; }
.head-count b { color: var(--app-text); font-weight: 600; }
/* 批量操作区（页头最右，分隔线与常规按钮划清界限）：开关恒在最右、进出模式位置不动 */
.batch-group { display: flex; align-items: center; gap: 8px; padding-left: 12px; border-left: 1px solid var(--app-border); }
/* 「批量管理」开关的激活态：品牌色描边提示当前处于批量模式 */
.batch-on { color: var(--app-accent); border-color: var(--app-accent); }
.wf-check { flex: none; }
.wf-list { display: flex; flex-direction: column; gap: 10px; }
.wf-card { display: flex; flex-direction: column; gap: 8px; transition: box-shadow .15s, border-color .15s; }
.wf-card:hover { box-shadow: 0 3px 12px rgba(0, 0, 0, .07); }
.wf-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
/* 状态：色点 + 文字（比大色块 tag 轻，扫读快） */
.wf-dot { width: 8px; height: 8px; border-radius: 50%; flex: none; }
.dot-pub { background: var(--app-ok); box-shadow: 0 0 0 3px rgba(82, 196, 26, .15); }
.dot-draft { background: var(--app-text3); box-shadow: 0 0 0 3px rgba(201, 205, 212, .18); }
.wf-name { font-weight: 500; font-size: 14px; }
.wf-status { font-size: 12px; color: var(--app-text2); }
.st-pub { color: #389e0d; }
.st-draft { color: var(--app-text3); }
.wf-meta { font-size: 12px; color: var(--app-text3); }
.wf-last { display: inline-flex; align-items: center; gap: 6px; margin-left: auto; font-size: 12px; color: var(--app-text3); }
.wf-run-dot { width: 7px; height: 7px; border-radius: 50%; flex: none; }
.rd-success { background: var(--app-ok); }
.rd-queued { background: var(--app-text3); }
.rd-failed { background: var(--app-danger); }
.rd-running { background: var(--app-accent); }
.rd-timeout { background: var(--app-warn); }
.rd-waiting_approval { background: var(--app-warn); }
.wf-run-txt { color: var(--app-text2); }
.wf-desc { font-size: 13px; color: var(--app-text2); }
.wf-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; border-top: 1px dashed var(--app-border); padding-top: 8px; }
.wf-del { color: var(--app-danger, var(--app-danger)); }
.wf-upd { margin-left: auto; }
.wf-empty { text-align: center; padding: 28px 16px; }
.wf-empty-title { margin: 0 0 6px; font-weight: 500; }
.wf-versions { display: flex; flex-direction: column; gap: 10px; }
.wf-version { border-bottom: 1px solid var(--app-border); padding-bottom: 8px; }
.wf-version:last-child { border-bottom: none; }
.wf-version-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.wf-version-note { font-size: 13px; color: var(--app-text2); margin-top: 4px; }
/* M5 模板库弹窗卡片 */
.wf-tpl-list { display: flex; flex-direction: column; gap: 10px; }
.wf-tpl { display: flex; flex-direction: column; gap: 6px; padding: 12px 14px; }
/* 第 1 期：共享分栏头 + 卡片上的定时/回调/共享小标 */
.wf-sec-head { display: flex; align-items: baseline; gap: 10px; margin: 18px 0 8px; flex-wrap: wrap; }
.wf-sec-title { font-size: 13px; font-weight: 500; color: var(--app-text); }
.wf-tag-sched, .wf-tag-shared {
  display: inline-flex; align-items: center; gap: 3px; font-size: 11px; line-height: 1;
  padding: 2px 7px; border-radius: 10px; flex: none;
}
.wf-tag-sched { color: var(--app-accent); background: var(--app-accent-weak); }
.wf-tag-shared { color: var(--app-text2); background: var(--app-bg); border: 1px solid var(--app-border); }
/* 定时与回调弹窗 */
.auto-block { margin-bottom: 16px; }
.auto-block:last-of-type { margin-bottom: 0; }
.auto-block-title { font-size: 13px; font-weight: 500; color: var(--app-text); margin-bottom: 8px; }
.auto-row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.auto-label { width: 56px; flex: none; font-size: 13px; color: var(--app-text2); }
.auto-hint { margin-top: 2px; }
.flex-gap { flex: 1 1 auto; }
</style>
