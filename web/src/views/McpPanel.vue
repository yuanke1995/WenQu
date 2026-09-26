<template>
  <!-- MCP 外部工具（个人）：连接自己登记的 MCP Server，其工具会自动注册给模型调用。
       原「系统设置 → MCP 面板」的全局 JSON 配置已废弃，服务改按用户隔离。
       页面骨架与「模型供应商」Tab 一致：标题栏（标题 + 说明 + 主操作）+ 带内边距的内容区。 -->
  <div class="app-page">
    <div class="app-page-head">
      <h1 class="app-page-title">MCP 外部工具</h1>
      <span class="head-hint-plain">接入外部 MCP 服务，它的工具自动注册给模型，与内置工具一样可被调用；服务只属于你自己</span>
      <button class="app-btn" style="margin-left:auto" @click="openAdd">
        <plus-outlined /> 添加服务
      </button>
    </div>

    <div class="app-page-body">
      <div class="key-bar">
        <div class="key-bar-left">
          <a-input v-model:value="keyword" placeholder="搜索服务名称 / 地址" allow-clear size="small" class="res-search">
            <template #prefix><search-outlined class="res-search-ic" /></template>
          </a-input>
          <span class="key-stat">
            共 <b>{{ servers.length }}</b> 个服务 · 已连接 <b>{{ connectedCount }}</b>
            <span v-if="checkedAt" class="key-dim">· 更新于 {{ checkedAt }}</span>
          </span>
        </div>
        <div class="key-bar-actions">
          <a-tooltip title="刷新连接状态">
            <button class="app-icon-btn" aria-label="刷新连接状态" :disabled="loading" @click="loadStatus"><reload-outlined /></button>
          </a-tooltip>
          <button class="app-btn ghost small" :disabled="reloading" @click="doReload">
            {{ reloading ? '重连中…' : '全部重连' }}
          </button>
        </div>
      </div>

      <!-- 工具调用总开关由管理员在系统设置里控制：没开时连上了也调不动，必须让用户看见 -->
      <a-alert v-if="!toolsEnabled" type="warning" show-icon style="margin-bottom:12px"
               message="平台未开启「工具调用」总开关"
               description="服务照常连接与展示，但模型暂时无法调用任何工具（含 MCP 工具）。需管理员在「系统设置 → 工具调用」中开启总开关。" />

      <div v-if="!servers.length" class="app-card key-empty">
        <div class="key-empty-title">还没有 MCP 服务</div>
        <div class="key-empty-desc">
          接入外部 MCP 服务（如时间工具、内部系统查询），它的工具会自动注册给模型，与内置工具一样可被调用。
          这里加的服务只属于你自己，不影响其他人的问答。
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
          <div class="mcp-card-head">
            <span class="mcp-dot" :class="stateCls(s)"></span>
            <span class="mcp-card-name">{{ s.name }}</span>
            <span class="mcp-state" :class="stateCls(s)" :title="s.state || ''">{{ stateText(s) }}</span>
            <div class="mcp-card-actions">
              <button v-if="s.connected" class="app-link-btn" @click="toggleTools(s)">
                {{ expanded === s.id ? '收起工具' : '查看工具' }}
              </button>
              <a-tooltip :title="s.enabled ? '停用后断开连接、不再暴露它的工具' : '启用后自动连接'">
                <a-switch size="small" :checked="!!s.enabled" :loading="togglingId === s.id"
                          @change="v => toggleEnabled(s, v)" />
              </a-tooltip>
              <button class="app-link-btn" @click="openEdit(s)">编辑</button>
              <a-popconfirm title="删除该服务？其工具将不再可用" ok-text="删除" cancel-text="取消" @confirm="removeServer(s)">
                <button class="app-link-btn danger">删除</button>
              </a-popconfirm>
            </div>
          </div>
          <div class="mcp-card-sub">
            <span class="mcp-type-pill">{{ s.type }}</span>
            <span class="mcp-url" :title="s.url">{{ s.url }}</span>
          </div>
          <div v-if="expanded === s.id" class="mcp-tools">
            <div v-for="t in s.tools" :key="t.name" class="mcp-tool">
              <code>{{ t.name }}</code>
              <span class="mcp-tool-desc">{{ t.description || '（无描述）' }}</span>
            </div>
            <div v-if="!s.tools.length" class="key-dim">该服务未提供任何工具</div>
          </div>
        </div>
      </template>
    </div>

    <!-- 添加 / 编辑弹窗：先测再存（地址填错当场就能发现，不用先存再回来删） -->
    <a-modal v-model:open="formOpen" :title="form.id ? '编辑 MCP 服务' : '添加 MCP 服务'" :footer="null" :width="580">
      <a-form layout="vertical">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" placeholder="如：时间工具 / 内部系统查询" :maxlength="40" />
        </a-form-item>
        <a-form-item label="服务地址" required>
          <a-input v-model:value="form.url" placeholder="http://127.0.0.1:8931 或 http://host:port/mcp" />
        </a-form-item>
        <a-form-item label="传输类型">
          <a-radio-group v-model:value="form.type" size="small" button-style="solid">
            <a-radio-button value="streamable">streamable（推荐）</a-radio-button>
            <a-radio-button value="sse">SSE</a-radio-button>
          </a-radio-group>
        </a-form-item>
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
          {{ saving ? '保存中…' : (form.id ? '保存并重连' : '添加并连接') }}
        </button>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { SearchOutlined, ReloadOutlined, PlusOutlined } from '@ant-design/icons-vue'
import { getMcpStatus, reloadMcp, probeMcp, addMcpServer, updateMcpServer, setMcpServerEnabled, deleteMcpServer } from '../api'

const servers = ref([])
const toolsEnabled = ref(true)   // 平台「工具调用」总开关（管理员控制），关了连上也没用
const loading = ref(false)
const reloading = ref(false)
const checkedAt = ref('')
const keyword = ref('')
const expanded = ref('')
const togglingId = ref('')
const saving = ref(false)
const formOpen = ref(false)
const form = ref({ id: '', name: '', url: '', type: 'streamable' })
const probe = ref({ loading: false, done: false, available: false, error: '', tools: [] })

const connectedCount = computed(() => servers.value.filter(s => s.connected).length)
const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return servers.value
  return servers.value.filter(s =>
    String(s.name || '').toLowerCase().includes(kw) || String(s.url || '').toLowerCase().includes(kw))
})

/** 失败态去掉 failed: 前缀只留原因；停用是明确语义，不叫「失败」 */
const stateText = s => {
  if (!s.enabled) return '已停用'
  if (s.connected) return `已连接 · ${s.toolCount || 0} 个工具`
  const st = s.state || ''
  if (st.startsWith('failed:')) return '连接失败：' + st.slice(7)
  return '未连接'
}
const stateCls = s => (!s.enabled ? 'muted' : (s.connected ? 'ok' : 'bad'))

const apply = d => {
  toolsEnabled.value = d?.toolsEnabled !== false
  servers.value = d?.servers || []
  checkedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
}
const loadStatus = async () => {
  loading.value = true
  try {
    const r = await getMcpStatus()
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
      const bad = (r.data?.servers || []).filter(s => s.enabled && !s.connected).length
      if (bad) message.warning(`已重连，${bad} 个服务仍未连上（见状态详情）`)
      else message.success('已重连')
    } else message.error(r.msg || '重连失败')
  } catch (e) { message.error(e.message || '重连失败') }
  finally { reloading.value = false }
}

const toggleTools = s => { expanded.value = expanded.value === s.id ? '' : s.id }
const openAdd = () => {
  form.value = { id: '', name: '', url: '', type: 'streamable' }
  probe.value = { loading: false, done: false, available: false, error: '', tools: [] }
  formOpen.value = true
}
const openEdit = s => {
  form.value = { id: s.id, name: s.name, url: s.url || '', type: s.type || 'streamable' }
  probe.value = { loading: false, done: false, available: false, error: '', tools: [] }
  formOpen.value = true
}
const testForm = async () => {
  if (!form.value.url.trim()) { message.warning('请先填写服务地址'); return }
  probe.value = { loading: true, done: false, available: false, error: '', tools: [] }
  try {
    const r = await probeMcp(form.value.url.trim(), form.value.type)
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
  if (!url) { message.warning('请填写服务地址'); return }
  saving.value = true
  try {
    const r = form.value.id
      ? await updateMcpServer(form.value.id, { name, url, type: form.value.type })
      : await addMcpServer({ name, url, type: form.value.type, enabled: true })
    if (r.success) {
      message.success(form.value.id ? '已保存并重连' : '已添加并连接')
      formOpen.value = false
      await loadStatus()
    } else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}
const toggleEnabled = async (s, on) => {
  togglingId.value = s.id
  try {
    const r = await setMcpServerEnabled(s.id, on)
    if (r.success) { message.success(on ? '已启用' : '已停用'); await loadStatus() }
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

onMounted(loadStatus)
</script>

<style scoped>
.key-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.key-bar-left { display: flex; align-items: center; gap: 10px; min-width: 0; }
.key-bar-actions { margin-left: auto; display: flex; gap: 8px; align-items: center; flex: none; }
.key-stat { font-size: 12px; color: var(--app-text2); }
.key-stat b { color: var(--app-text); font-weight: 600; }
.key-dim { color: var(--app-text3); font-size: 12px; }
.res-search { width: 220px; }
.res-search :deep(.ant-input-affix-wrapper) { border-radius: 8px; }
.res-search-ic { color: var(--app-text3); font-size: 12px; }
/* 空状态：与「模型供应商」一致——白卡片居中，不用虚线框 */
.key-empty { text-align: center; padding: 40px 20px; }
.key-empty-title { font-weight: 600; margin-bottom: 6px; }
.key-empty-desc { font-size: 13px; color: var(--app-text3); line-height: 1.7; max-width: 520px; margin: 0 auto 14px; }
.key-modal-foot { display: flex; justify-content: flex-end; gap: 8px; margin-top: 18px; }

/* 尺寸/圆角/边框复用 .app-card，与模型供应商卡片同规格 */
.mcp-card { margin-bottom: 12px; }
.mcp-card:last-child { margin-bottom: 0; }
.mcp-card-head { display: flex; align-items: center; gap: 8px; }
.mcp-card-name { font-size: 13px; font-weight: 500; max-width: 40%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mcp-card-head .mcp-state { font-size: 12px; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mcp-card-actions { margin-left: auto; flex: none; display: flex; align-items: center; gap: 8px; }
.mcp-card-sub { display: flex; align-items: center; gap: 8px; margin-top: 4px; font-size: 12px; color: var(--app-text3); }
.mcp-card-sub .mcp-url { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mcp-type-pill { flex: none; font-size: 11px; padding: 0 6px; border-radius: 3px; background: #f1f3f5; color: var(--app-text3); }
.mcp-tools { margin-top: 8px; border-top: 1px dashed var(--app-border); padding-top: 6px; }
.mcp-tool { display: flex; gap: 8px; font-size: 12px; padding: 2px 0; align-items: baseline; }
.mcp-tool code { flex: none; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; background: #f2f3f5; padding: 1px 5px; border-radius: 4px; }
.mcp-tool-desc { min-width: 0; color: var(--app-text3); }
.mcp-probe-ok { font-size: 12px; color: var(--app-ok); }
.mcp-probe-bad { font-size: 12px; color: var(--app-danger); word-break: break-all; }
.mcp-dot { flex: none; width: 7px; height: 7px; border-radius: 50%; }
.mcp-dot.ok { background: var(--app-ok); }
.mcp-dot.bad { background: var(--app-danger); }
.mcp-dot.muted { background: var(--app-text3); }
.mcp-state { flex: none; }
.mcp-state.ok { color: var(--app-ok); }
.mcp-state.muted { color: var(--app-text3); }
.mcp-state.bad { color: var(--app-danger); max-width: 320px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
</style>
