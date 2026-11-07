<template>
  <div class="hte">
    <!-- 工具集列表 -->
    <div v-if="!toolsets.length" class="hte-empty">
      还没有工具集 —— 点「导入 OpenAPI」把宿主项目的接口规格贴进来，勾选操作后即成为本智能体可调用的工具
    </div>
    <div v-for="(t, i) in toolsets" :key="t.id" class="hte-row">
      <span class="hte-dot" :class="{ off: t.enabled === false }"></span>
      <div class="hte-main">
        <div class="hte-name">{{ t.name }}
          <span v-if="t.auth && t.auth.hasValue" class="hte-auth-badge">凭据已配置</span>
          <span v-if="t.enabled === false" class="hte-auth-badge off">已停用</span>
        </div>
        <div class="hte-sub">{{ t.baseUrl }} · {{ (t.ops || []).length }} 个操作 · 超时 {{ t.timeoutMs || 10000 }}ms</div>
      </div>
      <div class="hte-acts">
        <button type="button" class="app-link-btn" @click="openEdit(i)">编辑</button>
        <button type="button" class="app-link-btn danger" @click="removeAt(i)">删除</button>
      </div>
    </div>
    <button type="button" class="app-btn ghost small hte-add" @click="openImport">
      <plus-outlined /> 导入 OpenAPI
    </button>

    <!-- 导入预览弹窗：粘贴 spec → 服务端解析 → 勾选操作 -->
    <a-modal v-model:open="importOpen" title="导入 OpenAPI 规格" width="680px" :mask-closable="false"
             :confirm-loading="importing" ok-text="下一步：配置工具集" cancel-text="取消" @ok="confirmImport">
      <a-textarea v-model:value="importSpec" :rows="8" placeholder='粘贴 OpenAPI 3.x 的 JSON 或 YAML 文本，例如：
openapi: 3.0.3
info:
  title: crm
servers:
  - url: https://crm.example.com/api
paths:
  /orders/{id}:
    get: …'
                  class="hte-spec" />
      <div class="hte-import-act">
        <button type="button" class="app-btn ghost small" :disabled="!importSpec.trim() || parsing" @click="doParse">
          {{ parsing ? '解析中…' : '解析预览' }}
        </button>
        <span v-if="parseError" class="hte-err">{{ parseError }}</span>
      </div>
      <template v-if="preview">
        <div v-if="preview.warnings && preview.warnings.length" class="hte-warn">
          <div v-for="(w, i) in preview.warnings" :key="i">⚠ {{ w }}</div>
        </div>
        <div class="hte-preview-h">
          解析出 {{ preview.ops.length }} 个操作（勾选要导出为工具的；最多 20 个/工具集）
        </div>
        <div class="hte-ops">
          <a-checkbox-group v-model:value="pickedOps" class="hte-op-list">
            <label v-for="(op, i) in preview.ops" :key="i" class="hte-op">
              <a-checkbox :value="i" />
              <span class="hte-op-method" :class="op.method.toLowerCase()">{{ op.method }}</span>
              <span class="hte-op-path">{{ op.path }}</span>
              <span class="hte-op-summary">{{ op.summary || '' }}</span>
            </label>
          </a-checkbox-group>
        </div>
      </template>
    </a-modal>

    <!-- 工具集编辑弹窗 -->
    <a-modal v-model:open="editOpen" :title="editIndex < 0 ? '配置工具集' : '编辑工具集'" width="620px"
             :mask-closable="false" ok-text="保存到表单" cancel-text="取消" @ok="confirmEdit">
      <div class="hte-form">
        <div class="hte-field">
          <span class="hte-label">名称<i>*</i></span>
          <a-input v-model:value="draft.name" size="small" placeholder="如 crm（用于生成工具名 crm_getOrder）" />
        </div>
        <div class="hte-field">
          <span class="hte-label">接口地址<i>*</i></span>
          <a-input v-model:value="draft.baseUrl" size="small" placeholder="https://host.example.com/api（该工具集所有操作共用的前缀）" />
        </div>
        <div class="hte-field">
          <span class="hte-label">超时(ms)</span>
          <a-input-number v-model:value="draft.timeoutMs" size="small" :min="1000" :max="60000" :step="1000" style="width:140px" />
        </div>
        <div class="hte-field">
          <span class="hte-label">鉴权</span>
          <div class="hte-auth">
            <a-select v-model:value="draft.auth.type" size="small" style="width:150px"
                      :options="[{ value: 'none', label: '无鉴权' }, { value: 'bearer', label: 'Bearer Token' }, { value: 'apiKeyHeader', label: '自定义请求头' }]" />
            <a-input v-if="draft.auth.type === 'apiKeyHeader'" v-model:value="draft.auth.header" size="small"
                     placeholder="头名（默认 X-Api-Key）" style="width:180px" />
            <a-input-password v-if="draft.auth.type !== 'none'" v-model:value="draft.auth.value" size="small"
                              :placeholder="draft.auth.hasValue ? '已配置（留空=沿用）' : '凭据值（加密存储，回显脱敏）'" style="flex:1" />
          </div>
          <div class="hte-hint">凭据加密落库，保存后不再回显明文；留空表示沿用原凭据</div>
        </div>
        <div class="hte-field">
          <span class="hte-label">操作（{{ draft.ops.length }}）</span>
          <div class="hte-edit-ops">
            <div v-for="(op, i) in draft.ops" :key="op.name" class="hte-edit-op">
              <span class="hte-op-method" :class="op.method.toLowerCase()">{{ op.method }}</span>
              <span class="hte-op-path">{{ op.path }}</span>
              <span class="hte-op-toolname">{{ op.name }}</span>
              <button type="button" class="app-link-btn danger" @click="draft.ops.splice(i, 1)">移除</button>
            </div>
            <div v-if="!draft.ops.length" class="hte-empty">没有操作——删除本工具集后重新导入</div>
          </div>
        </div>
        <div class="hte-field">
          <span class="hte-label">状态</span>
          <a-switch v-model:checked="draft.enabled" size="small" />
          <span class="hte-hint" style="margin-left:8px">停用后本轮不挂载（配置保留）</span>
        </div>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import { previewAgentHttpTools } from '../../api.js'

/**
 * HTTP 工具集编辑器（智能体自有配置，c_ai_agent.http_tools）。
 * 凭据口径与后端对齐：编辑时值列留空 = 沿用旧凭据（后端按工具集 id 匹配保留密文）；
 * 导入预览与保存共用同一解析器，预览即所见。
 */
const props = defineProps({ modelValue: { type: Array, default: () => [] } })
const emit = defineEmits(['update:modelValue'])

const toolsets = ref(props.modelValue || [])
const sync = () => emit('update:modelValue', toolsets.value.slice())

// ---- 导入 ----
const importOpen = ref(false)
const importSpec = ref('')
const parsing = ref(false)
const importing = ref(false)
const parseError = ref('')
const preview = ref(null)
const pickedOps = ref([])

const openImport = () => {
  importSpec.value = ''
  preview.value = null
  pickedOps.value = []
  parseError.value = ''
  importOpen.value = true
}

const doParse = async () => {
  parsing.value = true
  parseError.value = ''
  preview.value = null
  try {
    const r = await previewAgentHttpTools(importSpec.value)
    if (r && r.success !== false && r.data) {
      preview.value = r.data
      pickedOps.value = r.data.ops.map((_, i) => i)   // 默认全选，用户再收敛
    } else parseError.value = r?.msg || '解析失败'
  } catch (e) { parseError.value = e.message || '解析失败' } finally { parsing.value = false }
}

const confirmImport = () => {
  if (!preview.value) { message.warning('先解析预览'); return false }
  if (!pickedOps.value.length) { message.warning('至少勾选一个操作'); return false }
  // 名称/地址用解析建议打底，用户在下一步可改
  draft.id = ''
  draft.name = preview.value.suggestedName || ''
  draft.baseUrl = preview.value.suggestedBaseUrl || ''
  draft.timeoutMs = 10000
  draft.enabled = true
  draft.auth = { type: 'none', header: '', value: '', hasValue: false }
  draft.ops = pickedOps.value.map(i => {
    const op = preview.value.ops[i]
    return {
      operationId: op.operationId, method: op.method, path: op.path, summary: op.summary,
      params: op.params || [], hasBody: !!op.hasBody, inputSchema: op.inputSchema || {}
    }
  })
  if (!toolsets.value.length || editIndex.value < 0) editIndex.value = -1
  importOpen.value = false
  editOpen.value = true
  return false
}

// ---- 编辑 ----
const editOpen = ref(false)
const editIndex = ref(-1)
const blank = () => ({
  id: '', name: '', baseUrl: '', timeoutMs: 10000, enabled: true,
  auth: { type: 'none', header: '', value: '', hasValue: false }, ops: []
})
const draft = reactive(blank())

const openEdit = i => {
  editIndex.value = i
  const t = toolsets.value[i]
  Object.assign(draft, JSON.parse(JSON.stringify(t || blank())))
  if (!draft.auth) draft.auth = { type: 'none', header: '', value: '', hasValue: false }
  editOpen.value = true
}

const confirmEdit = () => {
  if (!draft.name.trim()) { message.warning('请填写工具集名称'); return false }
  if (!/^https?:\/\//.test((draft.baseUrl || '').trim())) { message.warning('接口地址须以 http(s):// 开头'); return false }
  if (!draft.ops.length) { message.warning('至少保留一个操作'); return false }
  const item = JSON.parse(JSON.stringify(draft))
  item.name = draft.name.trim()
  item.baseUrl = draft.baseUrl.trim()
  if (item.auth.type === 'none') item.auth.value = ''
  if (editIndex.value >= 0) toolsets.value.splice(editIndex.value, 1, item)
  else toolsets.value.push(item)
  sync()
  editOpen.value = false
  return true
}

const removeAt = i => { toolsets.value.splice(i, 1); sync() }
</script>

<style scoped>
.hte { display: flex; flex-direction: column; gap: 6px; }
.hte-empty { color: var(--app-text-3, #999); font-size: 12px; padding: 2px 0; }
.hte-row { display: flex; align-items: center; gap: 10px; padding: 6px 10px; border: 1px solid var(--app-border, #eee); border-radius: 8px; }
.hte-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--app-ok, #52c41a); flex: none; }
.hte-dot.off { background: var(--app-text-4, #ccc); }
.hte-main { flex: 1; min-width: 0; }
.hte-name { font-weight: 600; font-size: 13px; display: flex; gap: 8px; align-items: center; }
.hte-auth-badge { font-size: 11px; font-weight: 400; color: var(--app-ok, #52c41a); }
.hte-auth-badge.off { color: var(--app-text-3, #999); }
.hte-sub { color: var(--app-text-3, #999); font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hte-acts { display: flex; gap: 10px; flex: none; }
.hte-add { align-self: flex-start; }
.hte-spec { font-family: ui-monospace, Menlo, monospace; font-size: 12px; }
.hte-import-act { display: flex; align-items: center; gap: 10px; margin-top: 8px; }
.hte-err { color: var(--app-danger, #ff4d4f); font-size: 12px; }
.hte-warn { color: #ad6800; background: #fffbe6; border: 1px solid #ffe58f; border-radius: 6px; padding: 6px 10px; font-size: 12px; margin-top: 8px; }
.hte-preview-h { margin: 10px 0 6px; font-size: 12px; color: var(--app-text-2, #666); }
.hte-ops { max-height: 260px; overflow: auto; border: 1px solid var(--app-border, #eee); border-radius: 6px; padding: 6px 10px; }
.hte-op-list { display: flex; flex-direction: column; gap: 4px; width: 100%; }
.hte-op { display: flex; align-items: center; gap: 8px; font-size: 12px; cursor: pointer; }
.hte-op-method { font-family: ui-monospace, Menlo, monospace; font-weight: 700; width: 52px; flex: none; }
.hte-op-method.get { color: #1890ff; } .hte-op-method.post { color: #52c41a; }
.hte-op-method.put { color: #ad6800; } .hte-op-method.delete { color: #ff4d4f; }
.hte-op-method.patch, .hte-op-method.head { color: #722ed1; }
.hte-op-path { font-family: ui-monospace, Menlo, monospace; }
.hte-op-summary { color: var(--app-text-3, #999); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hte-form { display: flex; flex-direction: column; gap: 10px; }
.hte-field { display: flex; flex-direction: column; gap: 4px; }
.hte-label { font-size: 12px; color: var(--app-text-2, #666); }
.hte-label i { color: var(--app-danger, #ff4d4f); font-style: normal; margin-left: 2px; }
.hte-auth { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.hte-hint { font-size: 12px; color: var(--app-text-3, #999); }
.hte-edit-ops { display: flex; flex-direction: column; gap: 4px; max-height: 200px; overflow: auto;
  border: 1px solid var(--app-border, #eee); border-radius: 6px; padding: 6px 10px; }
.hte-edit-op { display: flex; align-items: center; gap: 8px; font-size: 12px; }
.hte-op-toolname { color: var(--app-text-3, #999); font-family: ui-monospace, Menlo, monospace; margin-left: auto; }
</style>
