<template>
  <!-- MCP 外部工具（个人）：连接自己登记的 MCP Server，其工具会自动注册给模型调用。
       原「系统设置 → MCP 面板」的全局 JSON 配置已废弃，服务改按用户隔离。
       页面骨架与「模型供应商」Tab 一致：标题栏（标题 + 说明 + 主操作）+ 带内边距的内容区。 -->
  <div class="app-page">
    <div class="app-page-head">
      <!-- 页名与 Tab 名重复，标题仅保留给读屏器（sr-only），视觉上从统计起头 -->
      <h1 class="app-page-title sr-only">MCP 外部工具</h1>
      <span class="head-count">
        共 <b>{{ servers.length }}</b> 个服务 · 已连接 <b>{{ connectedCount }}</b>
        <span v-if="checkedAt" class="head-dim">· 更新于 {{ checkedAt }}</span>
      </span>
      <span class="head-divider"></span>
      <button class="head-stats" type="button" title="查看近 7 天调用明细" @click="openStats">{{ statsSummary }}</button>
      <span class="head-hint-plain" title="外部 MCP 工具会自动注册给模型，与内置工具一样可被调用">外部 MCP 工具会自动注册给模型，与内置工具一样可被调用</span>
      <!-- 页头右侧一组（对齐智能体 Tab）：搜索 + 刷新 + 重连 + 添加 + 批量区（分隔线独立成区） -->
      <div class="head-r">
        <a-input v-model:value="keyword" class="head-search" size="small" allow-clear placeholder="搜索名称或地址">
          <template #prefix><search-outlined class="head-search-ic" /></template>
        </a-input>
        <a-tooltip title="刷新列表与连接状态（未连上的服务会重试连接）">
          <button class="app-icon-btn" aria-label="刷新列表与连接状态" :disabled="loading" @click="loadStatus(true)"><reload-outlined /></button>
        </a-tooltip>
        <button class="app-btn ghost small" :disabled="reloading" @click="doReload">
          {{ reloading ? '重连中…' : '全部重连' }}
        </button>
        <button class="app-btn ghost small" :disabled="!servers.length" @click="doExport">
          <download-outlined /> 导出
        </button>
        <button class="app-btn" @click="openAdd">
          <plus-outlined /> 添加服务
        </button>
        <!-- 批量区：默认收起，点「批量管理」进入批量模式；开关放最右：进出模式自身位置不动 -->
        <div v-if="servers.length" class="batch-group">
          <template v-if="batchMode">
            <a-checkbox :checked="allChecked" :indeterminate="someChecked" @change="toggleAll">全选</a-checkbox>
            <button class="app-btn ghost small" :disabled="!selected.length || batchBusy" @click="doBatchEnabled(true)">启用</button>
            <button class="app-btn ghost small" :disabled="!selected.length || batchBusy" @click="doBatchEnabled(false)">停用</button>
            <button class="app-btn ghost small" :disabled="!selected.length || batchBusy" @click="doBatchReconnect">重连</button>
            <button class="app-btn ghost small batch-del" :disabled="!selected.length || batchBusy" @click="doBatchDelete">删除</button>
          </template>
          <button class="app-btn ghost small" :class="{ 'batch-on': batchMode }" @click="toggleBatchMode">{{ batchMode ? '退出管理' : '批量管理' }}</button>
        </div>
      </div>
    </div>

    <div class="app-page-body">
      <!-- 工具调用总开关由管理员在系统设置里控制：没开时连上了也调不动，必须让用户看见 -->
      <a-alert v-if="!toolsEnabled" type="warning" show-icon style="margin-bottom:12px"
               message="平台未开启「工具调用」总开关"
               description="服务照常连接与展示，但模型暂时无法调用任何工具（含 MCP 工具）。需管理员在「系统设置 → 工具调用」中开启总开关。" />

      <div v-if="loading && !servers.length" class="app-card key-empty">
        <div class="key-dim">加载中…</div>
      </div>
      <div v-else-if="!servers.length" class="app-card key-empty">
        <div class="key-empty-title">还没有 MCP 服务</div>
        <div class="key-empty-desc">
          接入外部 MCP 服务（如时间工具、内部系统查询），它的工具会自动注册给模型，与内置工具一样可被调用。
        </div>
        <button class="app-btn small" @click="openAdd">添加第一个服务</button>
      </div>
      <div v-else-if="!filtered.length" class="app-card key-empty">
        <div class="key-empty-title">没有匹配的服务</div>
        <div class="key-empty-desc">没有名称或地址包含「{{ keyword }}」的服务。</div>
        <button class="app-btn ghost small" @click="keyword = ''">清除搜索</button>
      </div>

      <template v-else>
        <div v-for="s in filtered" :key="s.id" class="app-card mcp-card">
          <div class="mcp-card-top" :class="{ clickable: expandable(s) }"
               :title="expandable(s) ? (expanded === s.id ? '点击收起' : '点击展开') : ''"
               @click="expandable(s) && toggleTools(s)">
            <div class="mcp-card-info">
              <div class="mcp-card-head">
                <a-checkbox v-if="batchMode && !isPlatform(s)" class="mcp-check" :checked="selected.includes(s.id)"
                            @click.stop @change="toggleSelect(s.id)" />
                <span class="mcp-dot" :class="stateCls(s)"></span>
                <span class="mcp-card-name">{{ s.name }}</span>
                <span v-if="isPlatform(s)" class="mcp-pill-platform"
                      title="平台为全员提供：管理员维护地址与工具名，普通用户只读可用">平台</span>
                <span v-if="expandable(s)" class="mcp-chev" :class="{ up: expanded === s.id }" aria-hidden="true">
                  <down-outlined />
                </span>
              </div>
              <div class="mcp-card-sub">
                <span class="mcp-state" :class="stateCls(s)" :title="s.state || ''">{{ stateText(s) }}</span>
                <span class="mcp-type-pill">{{ s.type }}</span>
                <span class="mcp-url" :title="s.url">{{ s.url }}</span>
                <code v-for="k in (s.headerKeys || [])" :key="k" class="mcp-hdr-pill" :title="k">{{ k }}</code>
                <span v-if="usageOf(s)" class="mcp-usage" :title="usageTitle(s)">{{ usageOf(s) }}</span>
              </div>
            </div>
            <div class="mcp-card-actions" @click.stop>
              <a-tooltip v-if="canEdit(s)" :title="s.enabled ? '停用后断开连接、不再暴露它的工具' : '启用后自动连接'">
                <a-switch size="small" :checked="!!s.enabled" :loading="togglingId === s.id"
                          @change="v => toggleEnabled(s, v)" />
              </a-tooltip>
              <a-tooltip :title="s.enabled ? '只重连这一个服务（远程抖动/卡连接中/假绿时手动拉一把）' : '启用后再重连'">
                <button class="app-link-btn" :disabled="!s.enabled || !!reconnectingId"
                        @click="reconnectServer(s)">{{ reconnectingId === s.id ? '重连中…' : '重连' }}</button>
              </a-tooltip>
              <button v-if="canEdit(s)" class="app-link-btn" @click="openEdit(s)">编辑</button>
              <a-popconfirm v-if="!isPlatform(s)" title="删除该服务？其工具将不再可用" ok-text="删除" cancel-text="取消" @confirm="removeServer(s)">
                <button class="app-link-btn danger">删除</button>
              </a-popconfirm>
            </div>
          </div>

          <!-- 已连接：展开工具清单（行尾可试调用 / 改名） -->
          <div v-if="expanded === s.id && s.connected" class="mcp-tools">
            <div v-for="t in s.tools" :key="t.name" class="mcp-tool">
              <template v-if="toolEditing === s.id + '|' + t.name">
                <input v-model="toolDraft" class="app-input mcp-tool-name-input" maxlength="16"
                       :placeholder="'留空恢复原名 ' + t.name"
                       @keyup.enter="saveToolLabel(s, t)" @keyup.esc="cancelToolEdit" />
                <button class="app-link-btn" :disabled="toolSaving" @click="saveToolLabel(s, t)">
                  {{ toolSaving ? '保存中…' : '保存' }}
                </button>
                <button class="app-link-btn" @click="cancelToolEdit">取消</button>
              </template>
              <template v-else>
                <code class="mcp-tool-label" :class="{ named: t.label && t.label !== t.name }"
                      :title="t.name">{{ t.label && t.label !== t.name ? t.label : t.name }}</code>
                <code v-if="t.label && t.label !== t.name" class="mcp-tool-raw" title="工具原名">{{ t.name }}</code>
                <span class="mcp-tool-desc">{{ t.description || '（无描述）' }}</span>
                <span class="mcp-tool-ops">
                  <button class="app-link-btn mcp-tool-try" @click.stop="openInvoke(s, t)">试一下</button>
                  <button class="app-link-btn mcp-tool-rename" @click.stop="startToolEdit(s, t)">
                    {{ t.label && t.label !== t.name ? '改名' : '起中文名' }}
                  </button>
                </span>
              </template>
            </div>
            <div v-if="!s.tools.length" class="key-dim">该服务未提供任何工具</div>
          </div>

          <!-- 失败：展开错误详情（人话原因 + 折叠原文/建议 + 手动重连/复测） -->
          <div v-else-if="expanded === s.id && isFailed(s)" class="mcp-fail">
            <div class="mcp-fail-reason">
              <warning-outlined />
              <span>{{ failInfo(s).reason }}</span>
            </div>
            <div class="mcp-fail-fold">
              <button class="mcp-fold-head" type="button" @click="toggleRaw(s.id)">
                <down-outlined :class="{ up: rawOpenId === s.id }" />
                <span>错误原文与排查建议</span>
              </button>
              <div v-if="rawOpenId === s.id" class="mcp-fold-body">
                <div class="mcp-err-code">
                  <code>{{ failInfo(s).raw || '（无错误原文）' }}</code>
                  <button class="app-link-btn" @click="copyText(failInfo(s).raw)">复制</button>
                </div>
                <ul class="mcp-tips">
                  <li v-for="(tip, i) in failInfo(s).tips" :key="i">{{ tip }}</li>
                </ul>
              </div>
            </div>
            <div class="mcp-fail-foot">
              <span class="mcp-retry-hint">失败后会自动按退避间隔重试（30 秒 → 10 分钟）</span>
              <span class="mcp-fail-ops">
                <button class="app-btn ghost small" :disabled="!s.enabled || !!reconnectingId"
                        @click="reconnectServer(s)">重连</button>
                <button class="app-btn ghost small" :disabled="!!testingId" @click="testFailed(s)">
                  {{ testingId === s.id ? '测试中…' : '测试连接' }}
                </button>
              </span>
            </div>
          </div>
        </div>
      </template>
    </div>

    <!-- 添加 / 编辑弹窗：先测再存（地址填错当场就能发现，不用先存再回来删） -->
    <a-modal v-model:open="formOpen" :footer="null" :width="580">
      <template #title>
        <span>{{ form.id ? '编辑 MCP 服务' : '添加 MCP 服务' }}</span>
        <button v-if="!form.id" class="app-link-btn import-entry" @click="openImport">粘贴 JSON 导入</button>
      </template>
      <a-form layout="vertical">
        <a-form-item label="名称" required :validate-status="nameError ? 'error' : ''" :help="nameError">
          <a-input v-model:value="form.name" placeholder="如：时间工具 / 内部系统查询" :maxlength="40" />
        </a-form-item>
        <a-form-item label="服务地址" required>
          <a-input v-model:value="form.url" placeholder="http://127.0.0.1:8931 或 http://host:port/mcp"
                   @blur="onUrlBlur" />
        </a-form-item>
        <a-form-item label="传输类型">
          <a-radio-group v-model:value="form.type" size="small" button-style="solid" @change="onTypeChange">
            <a-radio-button value="streamable">streamable（推荐）</a-radio-button>
            <a-radio-button value="sse">SSE</a-radio-button>
          </a-radio-group>
          <div v-if="autoTypeHint" class="form-tip ok">已按地址自动识别传输类型：{{ form.type }}</div>
          <div class="form-tip">streamable：标准 MCP 传输，默认端点 /mcp；SSE：旧版传输，默认端点 /sse</div>
        </a-form-item>
        <a-collapse ghost class="adv-collapse">
          <a-collapse-panel key="auth" header="认证与请求头（一般保持默认即可）">
            <div v-if="form.id && form.hasHeaders && !headerEditing && !clearHeaders" class="hdr-saved">
              <div class="hdr-saved-line">
                <span class="key-dim">已保存请求头：</span>
                <code v-for="k in savedHeaderKeys" :key="k" class="hdr-chip">{{ k }}</code>
              </div>
              <div class="hdr-saved-actions">
                <button class="app-link-btn" @click="startReplaceHeaders">替换为新的请求头</button>
                <button class="app-link-btn danger" @click="clearHeaders = true">清除凭据</button>
              </div>
            </div>
            <div v-if="clearHeaders" class="hdr-clear">
              保存后将清除已保存的请求头（连接会按无凭据重建）。
              <button class="app-link-btn" @click="clearHeaders = false">取消清除</button>
            </div>
            <template v-if="headersEditorVisible">
              <div v-for="(row, i) in headerRows" :key="i" class="hdr-row">
                <a-input v-model:value="row.key" placeholder="键名，如 Authorization" class="hdr-key"
                         autocomplete="off" />
                <a-input-password v-model:value="row.value" placeholder="值" class="hdr-value"
                                  autocomplete="new-password" />
                <button class="app-icon-btn" aria-label="删除这一行" title="删除这一行" @click="headerRows.splice(i, 1)">
                  <delete-outlined />
                </button>
              </div>
              <div class="hdr-actions">
                <button class="app-link-btn" @click="addHeaderRow()">+ 添加一行</button>
                <a-select :value="null" size="small" placeholder="常用预设" style="width: 150px" @change="applyPreset">
                  <a-select-option value="bearer">Bearer Token</a-select-option>
                  <a-select-option value="apikey">X-Api-Key</a-select-option>
                </a-select>
              </div>
              <div class="hdr-extra">值只写不回显；编辑时不动这里 = 保持原凭据，填写 = 整块替换。</div>
            </template>
          </a-collapse-panel>
        </a-collapse>
        <a-form-item v-if="probe.done" label="测试结果">
          <div v-if="probe.available" class="mcp-probe-ok">连接正常，提供 {{ probe.tools.length }} 个工具</div>
          <div v-else class="mcp-probe-bad">连接失败：{{ probe.error }}</div>
          <div v-if="probe.available && probe.tools.length" class="mcp-tools" style="margin-top:6px">
            <div v-for="t in probe.tools" :key="t.name" class="mcp-tool">
              <code>{{ t.name }}</code>
              <span class="mcp-tool-desc">{{ t.description || '（无描述）' }}</span>
            </div>
          </div>
        </a-form-item>
      </a-form>
      <div class="key-modal-foot">
        <button class="app-btn ghost" :disabled="probe.loading" @click="testForm">
          {{ probe.loading ? '测试中…' : '测试连接' }}
        </button>
        <button class="app-btn" :disabled="saving" @click="submitForm">
          {{ saving ? '保存中…' : (form.id ? '保存' : '添加') }}
        </button>
      </div>
    </a-modal>

    <!-- JSON 粘贴导入：Claude/Cursor 的 mcpServers 片段，先预览再一次性导入 -->
    <a-modal v-model:open="importOpen" title="粘贴 JSON 导入" :width="640" :footer="null">
      <a-form layout="vertical">
        <a-form-item label="mcpServers 片段" extra="只导入 http(s) 地址型条目；本地进程（command / stdio）型会跳过并说明原因">
          <a-textarea v-model:value="importText" :rows="7"
                      placeholder='{"mcpServers":{"github":{"url":"https://api.githubcopilot.com/mcp/","headers":{"Authorization":"Bearer xxx"}}}}' />
        </a-form-item>
      </a-form>
      <div class="import-actions">
        <button class="app-btn ghost" :disabled="importBusy" @click="parseImportText">解析预览</button>
      </div>
      <div v-if="importPreview.length" class="import-list">
        <div v-for="it in importPreview" :key="it.key" class="import-item" :class="{ bad: !it.ok }">
          <span class="import-mark">{{ it.ok ? '可导入' : '跳过' }}</span>
          <span class="import-name">{{ it.name || '（未命名）' }}</span>
          <span class="import-reason">{{ it.ok ? it.url : it.reason }}</span>
        </div>
      </div>
      <div v-if="importPreview.length" class="key-modal-foot">
        <button class="app-btn ghost" @click="importOpen = false">取消</button>
        <button class="app-btn" :disabled="importBusy || !importable.length" @click="doImport">
          {{ importBusy ? '导入中…' : `导入 ${importable.length} 个` }}
        </button>
      </div>
    </a-modal>

    <McpInvokeModal v-model:open="invokeOpen" :server="invokeServer" :tool="invokeTool" @executed="onInvoked" />
    <McpStatsDrawer v-model:open="statsOpen" :stats="stats" :loading="statsLoading" :labels="toolLabels" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { SearchOutlined, ReloadOutlined, PlusOutlined, DeleteOutlined, DownloadOutlined, DownOutlined, WarningOutlined } from '@ant-design/icons-vue'
import { getMcpStatus, reloadMcp, probeMcp, addMcpServer, updateMcpServer, setMcpServerEnabled, deleteMcpServer,
         reconnectMcpServer, batchDeleteMcpServers, batchSetMcpServersEnabled, importMcpServers, setMcpToolLabel,
         getMcpSelfStats } from '../api'
import { isAdminSync } from '../utils/auth'
import McpInvokeModal from '../components/McpInvokeModal.vue'
import McpStatsDrawer from '../components/McpStatsDrawer.vue'

/** 平台行为全员提供（地址与工具名由管理员维护）：普通用户只读可用，连启停开关都不给 */
const isAdmin = isAdminSync()
const isPlatform = s => s.scope === 'platform'
const canEdit = s => !isPlatform(s) || isAdmin

const servers = ref([])
const toolsEnabled = ref(true)   // 平台「工具调用」总开关（管理员控制），关了连上也没用
const loading = ref(false)
const reloading = ref(false)
const checkedAt = ref('')
const keyword = ref('')
const expanded = ref('')
const rawOpenId = ref('')      // 失败卡里「错误原文与排查建议」的展开项
const testingId = ref('')      // 失败卡「测试连接」进行中
const togglingId = ref('')
const reconnectingId = ref('')
const saving = ref(false)
const formOpen = ref(false)
const form = ref({ id: '', name: '', url: '', type: 'streamable', hasHeaders: false })
const probe = ref({ loading: false, done: false, available: false, error: '', tools: [] })
// 认证区：编辑态默认只显示已保存的键名；「替换」打开空白行，保存时整块替换
const savedHeaderKeys = ref([])
const headerRows = ref([])
const headerEditing = ref(false)
const clearHeaders = ref(false)
const headersEditorVisible = computed(() =>
  !form.value.id || headerEditing.value || !form.value.hasHeaders)
// JSON 导入
const MAX_MCP_SERVERS = 10
const importOpen = ref(false)
const importText = ref('')
const importPreview = ref([])
const importBusy = ref(false)
const importable = computed(() => importPreview.value.filter(x => x.ok))

// ---- 近 7 天用量：页头小字、卡片用量与统计抽屉共用同一份数据 ----
const emptyStats = () => ({ total: 0, success: 0, failed: 0, avgMs: 0, byServer: [], byTool: [] })
const stats = ref(emptyStats())
const statsLoading = ref(false)
const statsOpen = ref(false)
const statsByServer = computed(() => {
  const m = {}
  for (const x of (stats.value.byServer || [])) if (x && x.server) m[x.server] = x
  return m
})
/** 工具裸名 → 用户起的中文名（未起名的不进表，统计里直接显示裸名） */
const toolLabels = computed(() => {
  const m = {}
  for (const s of servers.value) {
    for (const t of (s.tools || [])) {
      if (t.label && t.label !== t.name) m[t.name] = t.label
    }
  }
  return m
})
const statsSummary = computed(() => {
  if (statsLoading.value && !stats.value.total) return '近 7 天统计加载中…'
  if (!stats.value.total) return '近 7 天暂无调用'
  const total = Number(stats.value.total) || 0
  const failed = Number(stats.value.failed) || 0
  return `近 7 天 ${total} 次 · 成功率 ${total ? Math.round((total - failed) / total * 100) : 0}%`
})
const usageOf = s => {
  const x = statsByServer.value[s.name]
  if (!x || !Number(x.calls)) return ''
  const rate = Math.round((x.calls - (x.failed || 0)) / x.calls * 100)
  return `近 7 天 ${x.calls} 次 · ${rate}%`
}
const usageTitle = s => {
  const x = statsByServer.value[s.name]
  return x ? `${x.calls} 次调用，失败 ${x.failed} 次，平均 ${x.avgMs}ms` : ''
}

// ---- 工具试调用 ----
const invokeOpen = ref(false)
const invokeServer = ref(null)
const invokeTool = ref(null)
const openInvoke = (s, t) => {
  invokeServer.value = { id: s.id, name: s.name }
  invokeTool.value = { name: t.name, label: t.label }
  invokeOpen.value = true
}
const onInvoked = () => loadStats({ silent: true })

const loadStats = async ({ silent = false } = {}) => {
  statsLoading.value = true
  try {
    const r = await getMcpSelfStats(7)
    if (r.success) stats.value = { ...emptyStats(), ...(r.data || {}) }
  } catch (e) {
    if (!silent) message.error(e.message || '统计加载失败')
  } finally {
    statsLoading.value = false
  }
}
const openStats = () => {
  statsOpen.value = true
  loadStats()
}

const connectedCount = computed(() => servers.value.filter(s => s.connected).length)
const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return servers.value
  return servers.value.filter(s =>
    String(s.name || '').toLowerCase().includes(kw) || String(s.url || '').toLowerCase().includes(kw))
})

// ---- 批量操作：全选作用于当前搜索过滤后的可见项 ----
const selected = ref([])
const batchBusy = ref(false)
// 批量模式默认关闭：卡片不显示勾选框，点「批量管理」才进入（退出即清空勾选）
const batchMode = ref(false)
const toggleBatchMode = () => {
  batchMode.value = !batchMode.value
  if (!batchMode.value) selected.value = []
}
const selectableIds = computed(() => filtered.value.filter(s => !isPlatform(s)).map(s => s.id))
const allChecked = computed(() =>
  selectableIds.value.length > 0 && selectableIds.value.every(id => selected.value.includes(id)))
const someChecked = computed(() => selected.value.length > 0 && !allChecked.value)
const toggleSelect = id => {
  selected.value = selected.value.includes(id)
    ? selected.value.filter(x => x !== id)
    : [...selected.value, id]
}
const toggleAll = () => { selected.value = allChecked.value ? [] : [...selectableIds.value] }

/** 批量结果汇报：全成功走 message；有失败逐条弹 Modal 列出原因（不静默吞） */
const reportBatch = (r, verb) => {
  const data = (r && r.data) || {}
  const okCount = (data.succeeded || []).length
  const failed = data.failed || []
  if (!failed.length) {
    message.success(`已${verb} ${okCount} 个服务`)
    return
  }
  Modal.warning({
    title: `${verb}完成：成功 ${okCount} 个，失败 ${failed.length} 个`,
    content: failed.map(f => `「${f.name || f.id}」：${f.error}`).join('；'),
    okText: '知道了'
  })
}

const doBatchEnabled = async on => {
  batchBusy.value = true
  try {
    const r = await batchSetMcpServersEnabled([...selected.value], on)
    reportBatch(r, on ? '启用' : '停用')
    await loadStatus()
  } catch (e) {
    message.error((on ? '批量启用' : '批量停用') + '失败：' + (e.message || ''))
  } finally {
    batchBusy.value = false
  }
}

/** 批量重连：逐个调单服务重连，结果按 reportBatch 口径汇报 */
const doBatchReconnect = async () => {
  if (!selected.value.length) return
  batchBusy.value = true
  const succeeded = []
  const failed = []
  try {
    for (const id of selected.value) {
      const s = servers.value.find(x => x.id === id)
      try {
        const r = await reconnectMcpServer(id)
        if (r && r.success) succeeded.push(s ? s.name : id)
        else failed.push({ name: s ? s.name : id, error: (r && r.msg) || '重连失败' })
      } catch (e) {
        failed.push({ name: s ? s.name : id, error: e.message || '重连失败' })
      }
    }
    reportBatch({ data: { succeeded, failed } }, '重连')
    await loadStatus()
  } finally {
    batchBusy.value = false
  }
}

const doBatchDelete = () => {
  Modal.confirm({
    title: `删除选中的 ${selected.value.length} 个服务？`,
    content: '删除后其工具将不再可用（连接池统一重建）。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      batchBusy.value = true
      try {
        const r = await batchDeleteMcpServers([...selected.value])
        reportBatch(r, '删除')
        await loadStatus()
      } catch (e) {
        message.error('批量删除失败：' + (e.message || ''))
      } finally {
        batchBusy.value = false
      }
    }
  })
}

/** 失败态只在状态行留结论，原因放到展开区（状态行不再被 Java 原文截断） */
const stateText = s => {
  if (!s.enabled) return '已停用'
  if (s.connected) return `已连接 · ${s.toolCount || 0} 个工具`
  const st = s.state || ''
  if (st === 'connecting') return '连接中…'
  if (st.startsWith('failed:')) return '连接失败'
  return '未连接'
}
const stateCls = s =>
  (!s.enabled ? 'muted' : (s.connected ? 'ok' : (s.state === 'connecting' ? 'pending' : 'bad')))
const isFailed = s => String(s.state || '').startsWith('failed:')
const expandable = s => s.connected || isFailed(s)

/** 错误原文 → 人话原因 + 排查建议；没命中映射表就把原文截 120 字原样展示 */
const ERROR_MAP = [
  { keys: ['header parser', 'client failed to initialize'], reason: '远端返回的不是有效的 MCP 响应——地址可能不对，或被网关/代理拦截', tips: ['确认地址是 MCP 端点本体（streamable 默认路径 /mcp）', '如经过网关，确认它没有改写响应体或返回 HTML 错误页'] },
  { keys: ['timeout', 'timeoutexception'], reason: '连接超时——网络不通或远端没有响应', tips: ['检查网络可达性，稍后用「测试连接」复测', '远端服务可能正忙，稍等再试'] },
  { keys: ['closedchannel', 'connection closed', '连接已断开'], reason: '连接被远端关闭——服务可能已重启或空闲超时', tips: ['点「重连」重新建立连接；仍失败时确认远端服务在运行', '检查远端是否限制了并发连接或空闲时长'] },
  { keys: ['terminated the handshake', 'ssl', 'certificate'], reason: 'TLS 握手被终止——https 证书或端口不对', tips: ['确认该地址是否应使用 http', '检查证书是否过期或不被信任'] },
  { keys: ['401', '403', 'unauthorized'], reason: '鉴权未通过——检查认证请求头', tips: ['在「编辑 → 认证与请求头」补全或更新凭据', '确认密钥没有过期'] },
  { keys: ['404'], reason: '端点不存在——检查地址路径', tips: ['streamable 默认 /mcp，SSE 默认 /sse', '确认地址里没有多余的路径'] },
  { keys: ['connection refused'], reason: '拒绝连接——服务未启动或端口不对', tips: ['确认服务已启动、端口与地址一致', '检查防火墙是否放行'] },
  { keys: ['unknownhost'], reason: '域名无法解析', tips: ['检查域名拼写与 DNS', '确认当前网络能访问外网'] }
]
const failInfo = s => {
  const raw = String(s.state || '').replace(/^failed:/, '').trim()
  const low = raw.toLowerCase()
  const hit = ERROR_MAP.find(x => x.keys.some(k => low.includes(k)))
  return {
    raw,
    reason: hit ? hit.reason : (raw ? (raw.length > 120 ? raw.slice(0, 120) + '…' : raw) : '连接失败，原因未知'),
    tips: hit ? hit.tips : ['检查服务地址、端口与网络连通性', '确认远端服务已启动且返回 MCP 协议响应']
  }
}
const toggleRaw = id => { rawOpenId.value = rawOpenId.value === id ? '' : id }

const copyText = async text => {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    message.success('已复制')
  } catch (e) {
    message.error('复制失败，请手动选择文本')
  }
}

/** 失败卡「测试连接」：用该服务已保存的配置与凭据再探一次，结果只走 toast（不改卡片状态） */
const testFailed = async s => {
  if (testingId.value) return
  testingId.value = s.id
  try {
    const r = await probeMcp(s.url, s.type, undefined, s.id)
    if (!r.success) { message.error(r.msg || '测试失败'); return }
    const d = r.data || {}
    if (d.available) message.success(`连接正常，提供 ${(d.tools || []).length} 个工具`)
    else message.error('连接失败：' + (d.error || '未知错误'))
  } catch (e) {
    message.error(e.message || '测试失败')
  } finally {
    testingId.value = ''
  }
}

/** 导出为 Claude/Cursor 兼容的 mcpServers JSON；请求头只提示需重填，不落值 */
const doExport = () => {
  if (!servers.value.length) return
  const withHeaders = servers.value.filter(s => s.hasHeaders)
  Modal.confirm({
    title: `导出 ${servers.value.length} 个 MCP 服务？`,
    content: '将导出为 mcpServers JSON（Claude/Cursor 兼容格式），可直接粘贴回问渠或分享给同事。'
      + (withHeaders.length ? ' 请求头的值不会导出，带凭据的服务导入后需重新填写。' : ''),
    okText: '导出全部', cancelText: '取消',
    onOk: downloadExport
  })
}
const downloadExport = () => {
  const mcpServers = {}
  servers.value.forEach(s => { mcpServers[s.name] = { url: s.url, type: s.type } })
  const payload = { mcpServers }
  const withHeaders = servers.value.filter(s => s.hasHeaders).map(s => s.name)
  if (withHeaders.length) {
    payload['x-wenqu-notes'] = `以下服务配置了请求头，导入后需重新填写：${withHeaders.join('、')}`
  }
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'wenqu-mcp-servers.json'
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
  message.success(`已导出 ${servers.value.length} 个服务`)
}

/* 连接在后台进行（进页面/增删改后都有 connecting 过渡态）：链式轮询直到没有连接中的
   服务为止。不用 setInterval——上一发状态请求本身可能因在线校验拖 1~3s，必须等它回来
   再排下一发，避免请求堆积。上限 90 发（约 3 分钟）防后端卡死导致无限轮询。 */
const CONNECTING_POLL_MS = 2000
const CONNECTING_POLL_MAX = 90
let pollTimer = null
let pollCount = 0
const hasConnecting = () => servers.value.some(s => s.enabled && s.state === 'connecting')
const stopPoll = () => { if (pollTimer) { clearTimeout(pollTimer); pollTimer = null } }
const schedulePoll = () => {
  if (pollTimer || !hasConnecting()) { if (!hasConnecting()) pollCount = 0; return }
  if (pollCount >= CONNECTING_POLL_MAX) return
  pollCount++
  pollTimer = setTimeout(() => { pollTimer = null; loadStatus() }, CONNECTING_POLL_MS)
}

const apply = d => {
  toolsEnabled.value = d?.toolsEnabled !== false
  servers.value = d?.servers || []
  // 批量勾选与现存列表对账：已被删掉的 id 从选中集合里清掉（避免批量操作撞「不存在」）
  const alive = new Set(servers.value.map(x => x.id))
  selected.value = selected.value.filter(id => alive.has(id))
  checkedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  schedulePoll()
}
/**
 * 拉状态并整体替换列表。retry=true（点「刷新」按钮）时后端会先对未连上的服务补一次
 * 重连，远端恢复的服务当场翻回绿点；进页面/增删改后的刷新走默认 false（不重试，
 * 连接本身在后台进行，状态由上面的轮询收敛）。
 */
const loadStatus = async (retry = false) => {
  loading.value = true
  try {
    // 本页要的是「此刻真实状态」：恒带 verifyOnline=true 在线校验（别的消费方别学——智能体页只要服务清单）
    const r = await getMcpStatus(retry, true)
    if (r.success) apply(r.data)
  } catch (e) { message.error(e.message || '状态加载失败') }
  finally { loading.value = false }
}
const doReload = async () => {
  reloading.value = true
  try {
    const r = await reloadMcp()
    if (r.success) {
      apply(r.data)
      const list = r.data?.servers || []
      const pending = list.filter(s => s.enabled && s.state === 'connecting').length
      const bad = list.filter(s => s.enabled && s.state !== 'connecting' && !s.connected).length
      if (pending) message.info('重连已发起，连接完成后状态自动刷新')
      else if (bad) message.warning(`已重连，${bad} 个服务仍未连上（见状态详情）`)
      else message.success('已重连')
    } else message.error(r.msg || '重连失败')
  } catch (e) { message.error(e.message || '重连失败') }
  finally { reloading.value = false }
}

/** 展开/收起：已连接看工具，失败看错误详情；失败卡展开时默认把原文折叠项也打开 */
const toggleTools = s => {
  if (!expandable(s)) return
  const willOpen = expanded.value !== s.id
  expanded.value = willOpen ? s.id : ''
  rawOpenId.value = willOpen && isFailed(s) ? s.id : ''
}

// ---- 工具中文名：手工逐条起名（后端不再自动翻译；没起名就展示工具原名） ----
const toolEditing = ref('')   // `${serverId}|${toolName}`
const toolDraft = ref('')
const toolSaving = ref(false)
const startToolEdit = (s, t) => {
  toolEditing.value = s.id + '|' + t.name
  toolDraft.value = t.label && t.label !== t.name ? t.label : ''
}
const cancelToolEdit = () => { toolEditing.value = ''; toolDraft.value = '' }
const saveToolLabel = async (s, t) => {
  if (toolSaving.value) return
  toolSaving.value = true
  try {
    const r = await setMcpToolLabel(s.id, t.name, toolDraft.value.trim())
    if (!r.success) { message.error(r.msg || '保存失败'); return }
    t.label = r.data?.label || t.name
    cancelToolEdit()
    message.success(t.label === t.name ? '已恢复原名' : '已保存名字')
  } catch (e) {
    message.error(e.message || '保存失败')
  } finally {
    toolSaving.value = false
  }
}
const resetProbe = () => {
  probe.value = { loading: false, done: false, available: false, error: '', tools: [] }
}
const resetHeaderState = () => {
  savedHeaderKeys.value = []
  headerRows.value = []
  headerEditing.value = false
  clearHeaders.value = false
}
const addHeaderRow = (key = '', value = '') => headerRows.value.push({ key, value })
const applyPreset = kind => {
  if (kind === 'bearer') addHeaderRow('Authorization', 'Bearer ')
  else if (kind === 'apikey') addHeaderRow('X-Api-Key', '')
}
const startReplaceHeaders = () => {
  clearHeaders.value = false
  headerEditing.value = true
  headerRows.value = []
  addHeaderRow()
}
/** 编辑行 → 请求头对象；没有任何键时返回 null（新增态=不带凭据） */
const headersObject = () => {
  const out = {}
  let any = false
  for (const r of headerRows.value) {
    const k = String(r.key || '').trim()
    if (!k) continue
    out[k] = String(r.value == null ? '' : r.value)
    any = true
  }
  return any ? out : null
}
// ---- 编辑弹窗：URL 自动识别传输类型 + 名称即时查重----
const typeTouched = ref(false)
const autoTypeHint = ref(false)
const nameError = computed(() => {
  const n = form.value.name.trim().toLowerCase()
  if (!n) return ''
  const hit = servers.value.some(s => String(s.name || '').trim().toLowerCase() === n && s.id !== form.value.id)
  return hit ? '已存在同名服务' : ''
})
const inferType = url => (String(url || '').trim().toLowerCase().endsWith('/sse') ? 'sse' : 'streamable')
const onUrlBlur = () => {
  if (typeTouched.value) return
  const t = inferType(form.value.url)
  if (t !== form.value.type) {
    form.value.type = t
    autoTypeHint.value = true
  }
}
const onTypeChange = () => { typeTouched.value = true; autoTypeHint.value = false }

const openAdd = () => {
  form.value = { id: '', name: '', url: '', type: 'streamable', hasHeaders: false }
  typeTouched.value = false
  autoTypeHint.value = false
  resetHeaderState()
  resetProbe()
  formOpen.value = true
}
const openEdit = s => {
  form.value = { id: s.id, name: s.name, url: s.url || '', type: s.type || 'streamable', hasHeaders: !!s.hasHeaders }
  typeTouched.value = false
  autoTypeHint.value = false
  resetHeaderState()
  savedHeaderKeys.value = Array.isArray(s.headerKeys) ? [...s.headerKeys] : []
  resetProbe()
  formOpen.value = true
}
const testForm = async () => {
  if (!form.value.url.trim()) { message.warning('请先填写服务地址'); return }
  // 编辑态没动认证区 = 用已保存凭据（只传 serverId）；动了 = 用当前编辑行；清除 = 显式不带
  let headers
  if (!form.value.id) headers = headersObject() || undefined
  else if (clearHeaders.value) headers = {}
  else if (headerEditing.value) headers = headersObject() || {}
  else headers = undefined
  probe.value = { loading: true, done: false, available: false, error: '', tools: [] }
  try {
    const r = await probeMcp(form.value.url.trim(), form.value.type, headers, form.value.id || undefined)
    const d = r.success ? r.data : null
    probe.value = { loading: false, done: true, available: !!(d && d.available),
      error: (d && d.error) || '未知错误', tools: (d && d.tools) || [] }
  } catch (e) {
    probe.value = { loading: false, done: true, available: false, error: e.message || '测试失败', tools: [] }
  }
}
const submitForm = async () => {
  const name = form.value.name.trim()
  const url = form.value.url.trim()
  if (!name) { message.warning('请填写名称'); return }
  if (nameError.value) { message.warning(nameError.value); return }
  if (!url) { message.warning('请填写服务地址'); return }
  const payload = { name, url, type: form.value.type }
  if (form.value.id) {
    if (clearHeaders.value) payload.clearHeaders = true
    else if (headerEditing.value) payload.headers = headersObject() || {}
  } else {
    const headers = headersObject()
    if (headers) payload.headers = headers
  }
  saving.value = true
  try {
    const r = form.value.id
      ? await updateMcpServer(form.value.id, payload)
      : await addMcpServer({ ...payload, enabled: true })
    if (r.success) {
      message.success(form.value.id ? '已保存，后台连接中' : '已添加，后台连接中')
      formOpen.value = false
      await loadStatus()
    } else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}

// ---- JSON 粘贴导入 ----
const openImport = () => {
  importText.value = ''
  importPreview.value = []
  importOpen.value = true
}
const guessType = (cfg, url) => {
  const t = String((cfg && cfg.type) || '').trim().toLowerCase()
  if (t === 'sse') return 'sse'
  if (t) return 'streamable' // http / streamable / streamable-http 统一按 streamable
  try {
    const path = new URL(url).pathname.replace(/\/+$/, '')
    return /\/sse$/i.test(path) ? 'sse' : 'streamable'
  } catch (e) { return 'streamable' }
}
const parseImportText = () => {
  let obj
  try { obj = JSON.parse(importText.value) } catch (e) {
    message.error('JSON 解析失败：' + (e.message || ''))
    return
  }
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) {
    message.error('JSON 顶层应为对象（或含 mcpServers 的对象）')
    return
  }
  const src = obj.mcpServers && typeof obj.mcpServers === 'object' ? obj.mcpServers : obj
  const existing = new Set(servers.value.map(s => s.name))
  let quota = Math.max(0, MAX_MCP_SERVERS - servers.value.length)
  const seen = new Set()
  const out = []
  let idx = 0
  const bad = (name, reason) => out.push({ key: ++idx, name, ok: false, reason })
  for (const [rawName, cfg] of Object.entries(src)) {
    const name = String(rawName || '').trim()
    if (!cfg || typeof cfg !== 'object') { bad(name, '配置不是对象'); continue }
    if (!cfg.url) {
      bad(name, cfg.command ? '平台暂不支持本地进程（stdio）型服务' : '缺少 url')
      continue
    }
    const url = String(cfg.url).trim()
    if (!/^https?:\/\//i.test(url)) { bad(name, '只支持 http(s) 地址'); continue }
    if (!name) { bad(name, '缺少名称'); continue }
    if (name.includes('/')) { bad(name, '名称不能包含 /'); continue }
    if (name.length > 40) { bad(name, '名称不能超过 40 个字符'); continue }
    if (existing.has(name) || seen.has(name)) { bad(name, '已存在同名服务'); continue }
    if (quota <= 0) { bad(name, `已达上限（每人最多 ${MAX_MCP_SERVERS} 个）`); continue }
    quota--
    seen.add(name)
    out.push({
      key: ++idx, name, ok: true, url, type: guessType(cfg, url),
      headers: cfg.headers && typeof cfg.headers === 'object' && !Array.isArray(cfg.headers) ? cfg.headers : undefined
    })
  }
  importPreview.value = out
  if (!out.length) message.warning('没有解析到任何条目')
}
const doImport = async () => {
  const payload = importable.value.map(x => ({ name: x.name, url: x.url, type: x.type, headers: x.headers }))
  importBusy.value = true
  try {
    const r = await importMcpServers(payload)
    if (!r.success) { message.error(r.msg || '导入失败'); return }
    const data = r.data || {}
    const added = data.added || []
    const backendSkipped = data.skipped || []
    const localSkipped = importPreview.value.filter(x => !x.ok)
    const reasons = [
      ...localSkipped.map(x => `「${x.name || '未命名'}」：${x.reason}`),
      ...backendSkipped.map(x => `「${x.name || '未命名'}」：${x.reason}`)
    ]
    if (reasons.length) {
      Modal.warning({
        title: `已导入 ${added.length} 个，跳过 ${reasons.length} 个`,
        content: reasons.join('；'),
        okText: '知道了'
      })
    } else {
      message.success(`已导入 ${added.length} 个服务，后台连接中`)
    }
    importOpen.value = false
    formOpen.value = false
    importText.value = ''
    importPreview.value = []
    await loadStatus()
  } catch (e) { message.error(e.message || '导入失败') }
  finally { importBusy.value = false }
}
const toggleEnabled = async (s, on) => {
  togglingId.value = s.id
  try {
    const r = await setMcpServerEnabled(s.id, on)
    if (r.success) { if (on) message.success('已启用，后台连接中'); await loadStatus() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
  finally { togglingId.value = '' }
}
const removeServer = async s => {
  try {
    const r = await deleteMcpServer(s.id)
    if (r.success) { message.success('已删除'); await loadStatus() }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}

/** 单个服务重连：只重连这一个，其余连接不受影响；发起后即返回，状态由后台建连 + 轮询收敛 */
const reconnectServer = async s => {
  if (reconnectingId.value || !s.enabled) return
  reconnectingId.value = s.id
  try {
    const r = await reconnectMcpServer(s.id)
    if (r.success) {
      apply(r.data)
      message.success('已发起重连，连接完成后状态自动刷新')
    } else message.error(r.msg || '重连失败')
  } catch (e) { message.error(e.message || '重连失败') }
  finally { reconnectingId.value = '' }
}

onMounted(() => {
  loadStatus()
  loadStats({ silent: true })
})
onBeforeUnmount(stopPoll) // 离开页面就别再轮询了
</script>

<style scoped>
/* 页头右侧工具组（对齐智能体 Tab）：批量操作 + 搜索 + 刷新 + 重连 + 添加 */
.head-r { margin-left: auto; display: flex; align-items: center; gap: 8px; flex: none; }
.head-count { font-size: 12px; color: var(--app-text2); white-space: nowrap; }
.head-count b { color: var(--app-text); font-weight: 600; }
.head-dim { color: var(--app-text3); font-size: 12px; }
.head-divider { width: 1px; height: 14px; background: var(--app-border); flex: none; }
.head-stats { border: none; background: transparent; padding: 0; font-size: 12px; color: var(--app-text3); white-space: nowrap; cursor: pointer; }
.head-stats:hover { color: var(--app-accent); }
.head-search { width: 180px; }
.head-search-ic { color: var(--app-text3); font-size: 12px; }
.batch-group { display: flex; align-items: center; gap: 8px; padding-left: 12px; border-left: 1px solid var(--app-border); }
.batch-on { color: var(--app-accent); border-color: var(--app-accent); }
.form-tip { margin-top: 5px; font-size: 11.5px; line-height: 1.6; color: var(--app-text3); }
.form-tip.ok { color: var(--app-ok); }
.batch-del { color: var(--app-danger); }
.mcp-check { flex: none; }
.key-dim { color: var(--app-text3); font-size: 12px; }
/* 空状态：与「模型供应商」一致——白卡片居中，不用虚线框 */
.key-empty { text-align: center; padding: 40px 20px; }
.key-empty-title { font-weight: 600; margin-bottom: 6px; }
.key-empty-desc { font-size: 13px; color: var(--app-text3); line-height: 1.7; max-width: 520px; margin: 0 auto 14px; }
.key-modal-foot { display: flex; justify-content: flex-end; gap: 8px; margin-top: 18px; }

/* 尺寸/圆角/边框复用 .app-card，与模型供应商卡片同规格 */
.mcp-card { margin-bottom: 12px; }
.mcp-card:last-child { margin-bottom: 0; }
.mcp-card-top { display: flex; align-items: flex-start; gap: 12px; }
.mcp-card-top.clickable { cursor: pointer; user-select: none; }
.mcp-card-info { flex: 1; min-width: 0; }
.mcp-card-head { display: flex; align-items: center; gap: 8px; }
.mcp-card-name { font-size: 13px; font-weight: 500; max-width: 40%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mcp-chev { margin-left: auto; flex: none; display: inline-flex; align-items: center; color: var(--app-text3); font-size: 11px; transition: transform .15s ease; }
.mcp-chev.up { transform: rotate(180deg); }
.mcp-card-actions { flex: none; display: flex; align-items: center; gap: 8px; }
.mcp-card-actions .app-link-btn { display: inline-flex; align-items: center; line-height: 1; }
.mcp-card-sub { display: flex; align-items: center; gap: 8px; margin-top: 4px; min-width: 0; overflow: hidden; font-size: 12px; color: var(--app-text3); }
.mcp-card-sub .mcp-url { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mcp-state { flex: none; font-size: 12px; }
.mcp-usage { flex: none; font-size: 11.5px; color: var(--app-text3); }
.mcp-hdr-pill { flex: none; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; line-height: 16px; padding: 0 6px; border-radius: 4px; background: var(--app-accent-weak); color: var(--app-accent); }
.mcp-type-pill { flex: none; font-size: 11px; padding: 0 6px; border-radius: 3px; background: var(--app-panel-2); color: var(--app-text3); }
.mcp-pill-platform { flex: none; font-size: 11px; padding: 0 6px; border-radius: 3px; border: 1px solid var(--app-accent); color: var(--app-accent); background: transparent; }
.mcp-tools { margin-top: 8px; border-top: 1px dashed var(--app-border); padding-top: 6px; }
.mcp-tool { display: flex; gap: 8px; font-size: 12px; padding: 2px 0; align-items: baseline; }
.mcp-tool code { flex: none; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; background: var(--app-panel-2); padding: 1px 5px; border-radius: 4px; }
.mcp-tool-label.named { color: var(--app-text); }
.mcp-tool-raw { color: var(--app-text3); background: none !important; padding-left: 0 !important; }
.mcp-tool-ops { margin-left: auto; flex: none; display: flex; gap: 10px; }
.mcp-tool-try,
.mcp-tool-rename { font-size: 11.5px; color: var(--app-text3); }
.mcp-tool-try:hover,
.mcp-tool-rename:hover { color: var(--app-accent); }
.mcp-tool-name-input { width: 180px; flex: none; }
.mcp-tool-desc { min-width: 0; color: var(--app-text3); }
.mcp-probe-ok { font-size: 12px; color: var(--app-ok); }
.mcp-probe-bad { font-size: 12px; color: var(--app-danger); word-break: break-all; }

/* 失败卡展开：人话原因 → 折叠的错误原文/建议 → 退避提示 + 手动重连/复测 */
.mcp-fail { margin-top: 8px; border-top: 1px dashed var(--app-border); padding-top: 9px; }
.mcp-fail-reason { display: flex; align-items: flex-start; gap: 6px; font-size: 12.5px; line-height: 1.5; color: var(--app-danger); }
.mcp-fail-reason .anticon { flex: none; margin-top: 2px; font-size: 12px; }
.mcp-fail-fold { margin: 7px 0 0 18px; }
.mcp-fold-head { display: flex; align-items: center; gap: 5px; padding: 0; border: none; background: transparent; cursor: pointer; font-size: 12px; color: var(--app-text2); }
.mcp-fold-head .anticon { flex: none; font-size: 11px; color: var(--app-text3); transition: transform .15s ease; }
.mcp-fold-head .anticon.up { transform: rotate(180deg); }
.mcp-fold-body { margin-top: 6px; }
.mcp-err-code { display: flex; align-items: center; gap: 12px; padding: 7px 10px; border-radius: 6px; background: var(--app-code-bg); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11.5px; color: var(--app-text2); }
.mcp-err-code code { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.mcp-err-code .app-link-btn { flex: none; font-size: 11.5px; color: var(--app-text3); }
.mcp-tips { margin: 6px 0 0; padding: 0 0 0 14px; font-size: 11.5px; line-height: 1.8; color: var(--app-text3); }
.mcp-fail-foot { display: flex; align-items: center; justify-content: space-between; margin-top: 9px; padding-top: 8px; border-top: 1px dashed var(--app-border); }
.mcp-retry-hint { font-size: 11.5px; color: var(--app-text3); }
.mcp-fail-ops { display: flex; gap: 8px; }

/* 添加弹窗标题行的 JSON 导入入口 */
.import-entry { margin-left: 12px; font-size: 12px; font-weight: 400; }

/* 认证与请求头折叠区 */
.adv-collapse { margin-top: -6px; }
.hdr-saved { padding: 4px 0; }
.hdr-saved-line { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.hdr-chip { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; background: var(--app-panel-2); color: var(--app-text2); padding: 1px 6px; border-radius: 4px; }
.hdr-saved-actions { display: flex; gap: 12px; margin-top: 8px; }
.hdr-saved-actions .danger { color: var(--app-danger); }
.hdr-clear { font-size: 12px; color: var(--app-warn); line-height: 1.8; }
.hdr-row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.hdr-key { flex: 0 0 42%; }
.hdr-value { flex: 1; min-width: 0; }
.hdr-actions { display: flex; align-items: center; gap: 12px; }
.hdr-extra { margin-top: 8px; font-size: 12px; color: var(--app-text3); line-height: 1.7; }

/* JSON 导入弹窗 */
.import-actions { display: flex; justify-content: flex-end; }
.import-list { margin-top: 12px; max-height: 240px; overflow: auto; border-top: 1px dashed var(--app-border); }
.import-item { display: flex; align-items: baseline; gap: 8px; font-size: 12px; padding: 6px 0; border-bottom: 1px dashed var(--app-border); }
.import-mark { flex: none; color: var(--app-ok); }
.import-item.bad .import-mark { color: var(--app-text3); }
.import-name { flex: none; max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 500; }
.import-reason { min-width: 0; color: var(--app-text3); word-break: break-all; }
.import-item.bad .import-reason { color: var(--app-warn); }
.mcp-dot { flex: none; width: 7px; height: 7px; border-radius: 50%; }
.mcp-dot.ok { background: var(--app-ok); }
.mcp-dot.bad { background: var(--app-danger); }
.mcp-dot.muted { background: var(--app-text3); }
.mcp-dot.pending { background: var(--app-warn); animation: mcp-breathe 1.5s ease-in-out infinite; }
@keyframes mcp-breathe { 0%, 100% { opacity: 1; } 50% { opacity: .35; } }
@media (prefers-reduced-motion: reduce) { .mcp-dot.pending { animation: none; } }
.mcp-state.ok { color: var(--app-ok); }
.mcp-state.muted { color: var(--app-text3); }
.mcp-state.pending { color: var(--app-warn); }
.mcp-state.bad { color: var(--app-danger); }
</style>
