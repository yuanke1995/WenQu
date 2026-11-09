<template>
  <!-- 工具试调用（MCP 工具行尾「试一下」）：真实执行一次，结果不持久化 -->
  <a-modal :open="open" :width="660" :footer="null" :closable="false" :mask-closable="false" centered
           wrap-class-name="mcp-invoke-modal" :body-style="{ padding: '0' }" @cancel="close">
    <div class="iv">
      <div class="iv-head">
        <div class="iv-title">试调用 · <span>{{ tool?.label || tool?.name }}</span></div>
        <div class="iv-sub">服务：{{ server?.name || '' }}</div>
        <div class="iv-note">真实执行一次 · 计入审计与统计</div>
      </div>
      <div class="iv-body">
        <div class="iv-grid">
          <div>
            <div class="iv-label">入参（JSON）</div>
            <a-textarea v-model:value="argsText" class="iv-json" :rows="7" spellcheck="false"
                        placeholder='{ "metric": "dau" }' />
            <div class="iv-hint">本地校验 JSON，不合法不发请求</div>
          </div>
          <div>
            <div class="iv-label">参数说明</div>
            <div class="iv-schema">
              <div v-if="fields.length" class="iv-schema-t">来自该工具的 inputSchema</div>
              <template v-if="fields.length">
                <div v-for="f in fields" :key="f.name" class="iv-field">
                  <div class="iv-field-line">
                    <code>{{ f.name }}</code>
                    <span class="iv-ty">{{ f.type }}</span>
                    <span v-if="f.required" class="iv-req">必填</span>
                    <span v-else class="iv-opt">可选</span>
                  </div>
                  <div class="iv-desc">{{ f.description || '—' }}</div>
                </div>
              </template>
              <div v-else class="iv-schema-empty">{{ schemaEmptyText }}</div>
            </div>
          </div>
        </div>
        <div v-if="result" class="iv-result">
          <div class="iv-meta">
            <span class="iv-pill" :class="result.ok ? 'ok' : 'bad'">{{ result.ok ? '执行成功' : '执行失败' }}</span>
            <span class="iv-ms">耗时 <b>{{ result.durationMs ?? 0 }}</b> ms</span>
            <button class="app-link-btn iv-copy" @click="copy">复制结果</button>
          </div>
          <div class="iv-code" :class="{ bad: !result.ok }">{{ resultText }}</div>
          <div v-if="result.ok && truncated" class="iv-result-foot">返回体积较大，展示已截断；复制可拿全量</div>
        </div>
      </div>
      <div class="iv-foot">
        <button class="app-btn ghost" @click="close">关闭</button>
        <button class="app-btn" :disabled="running" @click="run">{{ running ? '执行中…' : '执行试调用' }}</button>
      </div>
    </div>
  </a-modal>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { message } from 'ant-design-vue'
import { getMcpToolSchema, invokeMcpTool } from '../api'

const props = defineProps({
  open: { type: Boolean, default: false },
  server: { type: Object, default: null },
  tool: { type: Object, default: null }
})
const emit = defineEmits(['update:open', 'executed'])

const argsText = ref('{}')
const running = ref(false)
const result = ref(null)
const schema = ref(null)
const schemaErr = ref('')
const schemaLoading = ref(false)

const requiredNames = computed(() => new Set((schema.value?.inputSchema?.required) || []))

/** JSON Schema 的 type 可能是字符串/数组，也可能是 anyOf/oneOf 组合；尽量翻译成可读类型 */
const fieldType = p => {
  if (Array.isArray(p.type)) return p.type.join(' | ')
  if (p.type) return p.type
  for (const k of ['anyOf', 'oneOf']) {
    const list = p[k]
    if (Array.isArray(list)) {
      const ts = list
        .map(x => (x && typeof x === 'object' ? (Array.isArray(x.type) ? x.type.join(' | ') : x.type) : ''))
        .filter(Boolean)
      if (ts.length) return ts.join(' | ')
    }
  }
  return '—'
}

/** inputSchema.properties → 参数说明行（类型/必填/描述） */
const fields = computed(() => {
  const propsObj = schema.value?.inputSchema?.properties
  if (!propsObj || typeof propsObj !== 'object') return []
  return Object.entries(propsObj).map(([name, raw]) => {
    const p = raw && typeof raw === 'object' ? raw : {}
    return { name, type: fieldType(p), description: p.description || '', required: requiredNames.value.has(name) }
  })
})

const schemaEmptyText = computed(() => {
  if (schemaLoading.value) return '加载中…'
  if (schemaErr.value) return '无法获取入参说明：' + schemaErr.value
  return '该工具未声明入参'
})

/** 结果正文：成功时尽量 pretty；失败显示错误原文 */
const resultText = computed(() => {
  const r = result.value
  if (!r) return ''
  if (!r.ok) return r.error || '执行失败'
  const raw = r.resultText || ''
  try { return JSON.stringify(JSON.parse(raw), null, 2) } catch (e) { return raw }
})

const truncated = computed(() => {
  const r = result.value
  return !!(r && r.ok && (r.resultText || '').length > 1500)
})

const close = () => emit('update:open', false)

const prepare = async () => {
  argsText.value = '{}'
  result.value = null
  schema.value = null
  schemaErr.value = ''
  if (!props.server?.id || !props.tool?.name) return
  schemaLoading.value = true
  try {
    const r = await getMcpToolSchema(props.server.id, props.tool.name)
    if (r.success) schema.value = r.data
  } catch (e) {
    schemaErr.value = e.message || '获取 schema 失败'
  } finally {
    schemaLoading.value = false
  }
}

watch(() => props.open, v => { if (v) prepare() })

const run = async () => {
  if (running.value) return
  let args
  try {
    args = JSON.parse(argsText.value || '{}')
  } catch (e) {
    message.error('入参不是合法的 JSON：' + (e.message || ''))
    return
  }
  if (!args || typeof args !== 'object' || Array.isArray(args)) {
    message.warning('入参必须是 JSON 对象（形如 {"key": "value"}）')
    return
  }
  running.value = true
  result.value = null
  try {
    const r = await invokeMcpTool(props.server.id, props.tool.name, args)
    if (!r.success) { message.error(r.msg || '执行失败'); return }
    result.value = r.data
    emit('executed')
  } catch (e) {
    message.error(e.message || '执行失败')
  } finally {
    running.value = false
  }
}

const copy = async () => {
  const text = resultText.value
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    message.success('已复制')
  } catch (e) {
    message.error('复制失败，请手动选择文本')
  }
}
</script>

<style>
/* 弹窗本体：与工作台卡片同圆角/阴影（ant-modal 默认 8px 偏小） */
.mcp-invoke-modal .ant-modal-content {
  border-radius: 14px;
  overflow: hidden;
  padding: 0;
  box-shadow: 0 12px 32px rgba(16, 24, 40, .18);
}
</style>

<style scoped>
.iv-head { padding: 18px 24px 0; position: relative; }
.iv-title { font-size: 15px; font-weight: 600; color: var(--app-text); }
.iv-title span { font-weight: 600; }
.iv-sub { margin-top: 4px; font-size: 12px; color: var(--app-text3); }
.iv-note { position: absolute; right: 24px; top: 22px; font-size: 11.5px; color: var(--app-text3); }
.iv-body { padding: 16px 24px 0; }
.iv-grid { display: grid; grid-template-columns: 1fr 216px; gap: 14px; }
.iv-label { font-size: 12px; color: var(--app-text2); margin-bottom: 6px; }
.iv-json {
  height: 150px; border-radius: 8px; padding: 9px 10px !important;
  font-family: var(--mono, ui-monospace, SFMono-Regular, Menlo, monospace) !important;
  font-size: 11.5px !important; line-height: 1.65 !important; resize: none;
}
.iv-hint { margin-top: 6px; font-size: 11px; color: var(--app-text3); }
.iv-schema {
  min-height: 166px; max-height: 260px; background: var(--app-panel-2); border: 1px solid var(--app-border);
  border-radius: 8px; padding: 9px 10px; overflow-y: auto;
}
.iv-schema-t { font-size: 11px; color: var(--app-text3); letter-spacing: .03em; margin-bottom: 8px; }
.iv-field { margin-bottom: 9px; }
.iv-field:last-child { margin-bottom: 0; }
.iv-field-line { display: flex; align-items: center; gap: 5px; }
.iv-field-line code { font-family: var(--mono, ui-monospace, SFMono-Regular, Menlo, monospace); font-size: 11.5px; color: var(--app-text); }
.iv-ty { font-size: 10.5px; color: var(--app-text3); }
.iv-req { font-size: 10px; line-height: 14px; padding: 0 4px; border-radius: 3px; color: var(--app-warn-text); background: var(--app-warn-weak); }
.iv-opt { font-size: 10px; line-height: 14px; color: var(--app-text3); }
.iv-desc { margin-top: 2px; font-size: 11.5px; line-height: 1.5; color: var(--app-text3); }
.iv-schema-empty { font-size: 11.5px; line-height: 1.6; color: var(--app-text3); word-break: break-all; }
.iv-result { margin-top: 14px; padding-top: 12px; border-top: 1px dashed var(--app-border); }
.iv-meta { display: flex; align-items: center; gap: 8px; }
.iv-pill { display: inline-flex; align-items: center; font-size: 11px; line-height: 18px; padding: 0 7px; border-radius: 999px; }
.iv-pill.ok { background: var(--app-ok-weak); color: var(--app-ok); }
.iv-pill.bad { background: var(--app-danger-weak); color: var(--app-danger); }
.iv-ms { font-size: 11.5px; color: var(--app-text3); }
.iv-ms b { font-weight: 500; color: var(--app-text2); }
.iv-copy { margin-left: auto; font-size: 11.5px; color: var(--app-text3); }
.iv-code {
  margin-top: 8px; background: var(--app-code-bg); border-radius: 8px; padding: 9px 11px;
  font-family: var(--mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 11.5px; line-height: 1.65; color: var(--app-text2);
  max-height: 190px; overflow: auto; white-space: pre-wrap; word-break: break-word;
}
.iv-code.bad { color: var(--app-danger); }
.iv-result-foot { margin-top: 6px; font-size: 11px; color: var(--app-text3); }
.iv-foot { display: flex; justify-content: flex-end; gap: 8px; padding: 16px 24px 18px; }
</style>
