<template>
  <!-- 详情「内容」页签：按类型给结构化预览，原始配置永远折叠可查 -->
  <div class="cv">
    <!-- 技能：正文 + 附带文件 + 原始文件 -->
    <template v-if="item.itemType === 'skill'">
      <!-- eslint-disable-next-line vue/no-v-html -->
      <div v-if="skillHtml" class="md cv-md" v-html="skillHtml" />
      <pre v-else class="cv-raw">{{ item.skillMd || '暂时读不到技能正文，可下载物品包查看。' }}</pre>
      <div v-if="files.length" class="cv-files">
        <span class="cv-files-k">附带文件 {{ files.length }} 个</span>
        <span v-for="f in files.slice(0, 8)" :key="f.name" class="cv-file" :title="f.name">{{ shortName(f.name) }}</span>
        <span v-if="files.length > 8" class="cv-file muted">+{{ files.length - 8 }}</span>
      </div>
      <details class="cv-fold">
        <summary>原始技能文件</summary>
        <pre class="cv-raw">{{ item.skillMd || '暂时读不到技能正文。' }}</pre>
      </details>
    </template>

    <!-- 应用模板：能力摘要 + 引用明细 -->
    <template v-else-if="item.itemType === 'agent'">
      <div class="cv-caps">
        <div v-for="c in caps" :key="c.k" class="cv-cap">
          <div class="cv-cap-k">{{ c.k }}</div>
          <div class="cv-cap-v">{{ c.v }}<small>个</small></div>
        </div>
      </div>
      <div class="cv-refs">
        <div v-if="persona" class="cv-ref"><span class="k">人设</span><span class="v">{{ persona }}</span></div>
        <div class="cv-ref"><span class="k">知识库</span><span class="v">{{ kbCount }} 个<em v-if="kbCount">（安装时按你的权限裁剪）</em></span></div>
        <div class="cv-ref"><span class="k">技能</span><span class="v">{{ joinOrDash(skillNames) }}</span></div>
        <div class="cv-ref"><span class="k">MCP</span><span class="v">{{ joinOrDash(mcpNames) }}</span></div>
        <div class="cv-ref"><span class="k">HTTP 工具</span><span class="v">{{ joinOrDash(httpTools) }}</span></div>
        <div class="cv-ref"><span class="k">工作流工具</span><span class="v">{{ joinOrDash(workflowTools) }}</span></div>
        <div v-if="boundWorkflow" class="cv-ref"><span class="k">绑定工作流</span><span class="v">{{ boundWorkflow }}</span></div>
      </div>
      <details class="cv-fold">
        <summary>原始配置</summary>
        <pre class="cv-raw">{{ item.configJson || '内容读取失败，可下载物品包查看。' }}</pre>
      </details>
    </template>

    <!-- 工作流：只读流程图 -->
    <template v-else-if="item.itemType === 'workflow'">
      <div v-if="graph" class="cv-flow">
        <FlowEditor :dsl="dslText" readonly preview />
      </div>
      <div v-else class="cv-empty">流程定义读取失败，可下载物品包查看。</div>
      <div v-if="graph" class="cv-flow-meta">{{ graph.nodes.length }} 个节点 · {{ graph.edges.length }} 条连线</div>
      <details class="cv-fold">
        <summary>原始流程定义</summary>
        <pre class="cv-raw">{{ item.configJson || '流程定义读取失败。' }}</pre>
      </details>
    </template>

    <!-- MCP：参数表 -->
    <template v-else-if="item.itemType === 'mcp'">
      <div class="cv-params">
        <div class="cv-param"><span class="k">名称</span><span class="v">{{ mcp.name || item.name }}</span></div>
        <div class="cv-param"><span class="k">地址</span><span class="v mono" :title="mcp.url">{{ mcp.url || '—' }}</span></div>
        <div class="cv-param"><span class="k">传输类型</span><span class="v mono">{{ item.mcpType || mcp.type || 'streamable' }}</span></div>
        <div class="cv-param"><span class="k">需要认证</span><span class="v" :class="{ warn: needsAuth }">{{ needsAuth ? '需要，安装后补充请求头' : '不需要' }}</span></div>
        <div class="cv-param"><span class="k">我的配额</span><span class="v">{{ quota.used }} / {{ quota.max }} 个服务</span></div>
      </div>
      <details class="cv-fold">
        <summary>原始配置</summary>
        <pre class="cv-raw">{{ item.configJson || '内容读取失败，可下载物品包查看。' }}</pre>
      </details>
    </template>
  </div>
</template>

<script setup>
import { computed, defineAsyncComponent } from 'vue'
import { renderMd } from '../utils/markdown'

// 工作流预览复用画布渲染器（只读 + 传 DSL，不建临时工作流）；异步加载，避免市场首屏带进整套画布依赖
const FlowEditor = defineAsyncComponent(() => import('../views/FlowEditor.vue'))

const props = defineProps({
  item: { type: Object, default: () => ({}) },
  /** MCP 个人配额（与列表 counts.mcpQuota 同源） */
  quota: { type: Object, default: () => ({ used: 0, max: 10 }) }
})

const parsed = computed(() => {
  try {
    const o = JSON.parse(props.item.configJson || '{}')
    return o && typeof o === 'object' ? o : null
  } catch (e) { return null }
})
const agentCfg = computed(() => parsed.value?.agent || parsed.value || {})
const csv = v => {
  if (Array.isArray(v)) return v.map(x => String(x).trim()).filter(Boolean)
  if (typeof v !== 'string' || !v.trim()) return []
  return v.split(/[,，]/).map(s => s.trim()).filter(Boolean)
}
const objNames = v => {
  if (typeof v === 'string' && !v.trim().startsWith('[')) return csv(v)
  let a = v
  if (typeof v === 'string') {
    try { a = JSON.parse(v) } catch (e) { a = [] }
  }
  if (!Array.isArray(a)) return []
  return a.map(x => (typeof x === 'string' ? x : (x?.name || x?.title || ''))).filter(Boolean)
}
const skillNames = computed(() => csv(agentCfg.value.skills))
const mcpNames = computed(() => csv(agentCfg.value.mcps))
const httpTools = computed(() => objNames(agentCfg.value.httpTools))
const workflowTools = computed(() => objNames(agentCfg.value.workflowTools))
const kbCount = computed(() => csv(agentCfg.value.knowledgeBaseIds).length)
const capsule = () => {
  const cfg = agentCfg.value
  const raw = (cfg.systemPrompt || cfg.description || '').toString().trim()
  return raw ? raw.split('\n')[0].slice(0, 80) : ''
}
const persona = computed(() => capsule())
const boundWorkflow = computed(() => parsed.value?.workflow?.name || '')
const caps = computed(() => [
  { k: '知识库', v: kbCount.value },
  { k: '技能', v: skillNames.value.length },
  { k: 'MCP', v: mcpNames.value.length },
  { k: 'HTTP 工具', v: httpTools.value.length },
  { k: '工作流工具', v: workflowTools.value.length }
])

const skillHtml = computed(() => {
  const body = (props.item.skillMd || '').replace(/^---\n[\s\S]*?\n---\n?/, '').trim()
  return body ? renderMd(body) : ''
})
const files = computed(() => (Array.isArray(props.item.files) ? props.item.files : []))
const shortName = n => {
  const s = String(n || '')
  return s.includes('/') ? s.split('/').pop() : s
}

const dslText = computed(() => (parsed.value && typeof parsed.value.dsl === 'string' ? parsed.value.dsl : ''))
const graph = computed(() => {
  if (!dslText.value.trim()) return null
  try {
    const d = JSON.parse(dslText.value)
    if (!d || typeof d !== 'object') return null
    return { nodes: Array.isArray(d.nodes) ? d.nodes : [], edges: Array.isArray(d.edges) ? d.edges : [] }
  } catch (e) { return null }
})

const mcp = computed(() => (props.item.itemType === 'mcp' ? (parsed.value || {}) : {}))
const needsAuth = computed(() => !!props.item.requiresAuth || !!mcp.value.requiresAuth)
const joinOrDash = list => (list && list.length ? list.join('、') : '—')
</script>

<style scoped>
.cv-md { max-height: 46vh; overflow-y: auto; border: 1px solid var(--app-border);
  border-radius: 8px; padding: 12px 14px; }
.cv-raw { max-height: 320px; overflow: auto; border: 1px solid var(--app-border); border-radius: 8px;
  padding: 12px 14px; background: var(--app-panel-2); margin: 0; white-space: pre-wrap;
  word-break: break-word; font-size: 12px; line-height: 1.7;
  font-family: var(--app-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace); }
.cv-files { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 8px; }
.cv-files-k { font-size: 11.5px; color: var(--app-text3); }
.cv-file { font-size: 11px; line-height: 18px; padding: 0 7px; border-radius: 4px;
  background: var(--app-panel-2); color: var(--app-text2);
  font-family: var(--app-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cv-file.muted { color: var(--app-text3); }
.cv-fold { margin-top: 10px; border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel); }
.cv-fold > summary { padding: 8px 12px; font-size: 12px; color: var(--app-text2); cursor: pointer;
  list-style: none; }
.cv-fold > summary::-webkit-details-marker { display: none; }
.cv-fold > summary::before { content: '›'; display: inline-block; margin-right: 6px; color: var(--app-text3);
  transition: transform .15s; }
.cv-fold[open] > summary::before { transform: rotate(90deg); }
.cv-fold .cv-raw { border: 0; border-top: 1px solid var(--app-border); border-radius: 0 0 8px 8px; max-height: 260px; }

.cv-caps { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 8px; }
.cv-cap { padding: 8px 10px; border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel-2); }
.cv-cap-k { font-size: 10.5px; color: var(--app-text3); white-space: nowrap; }
.cv-cap-v { margin-top: 3px; font-size: 15px; font-weight: 600; color: var(--app-text); }
.cv-cap-v small { margin-left: 3px; font-size: 10.5px; font-weight: 400; color: var(--app-text3); }
.cv-refs { margin-top: 10px; padding: 8px 12px; border: 1px solid var(--app-border); border-radius: 8px;
  background: var(--app-panel-2); }
.cv-ref { display: flex; align-items: baseline; gap: 10px; padding: 3px 0; font-size: 11.5px; line-height: 1.7; }
.cv-ref .k { flex: none; width: 64px; color: var(--app-text3); }
.cv-ref .v { min-width: 0; color: var(--app-text2); word-break: break-word; }
.cv-ref .v em { font-style: normal; color: var(--app-text3); }

.cv-flow { height: 380px; border: 1px solid var(--app-border); border-radius: 8px; overflow: hidden;
  background: var(--app-panel-2); }
.cv-flow-meta { margin-top: 6px; font-size: 11.5px; color: var(--app-text3); }
.cv-empty { border: 1px dashed var(--app-border); border-radius: 8px; padding: 28px;
  text-align: center; font-size: 12px; color: var(--app-text3); }

.cv-params { border: 1px solid var(--app-border); border-radius: 8px; overflow: hidden; }
.cv-param { display: flex; align-items: center; gap: 10px; padding: 8px 12px;
  border-bottom: 1px solid var(--app-border); font-size: 11.5px; }
.cv-param:last-child { border-bottom: 0; }
.cv-param .k { flex: none; width: 72px; color: var(--app-text3); }
.cv-param .v { flex: 1; min-width: 0; color: var(--app-text2); }
.cv-param .v.mono { font-family: var(--app-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.cv-param .v.warn { color: var(--app-warn-text); }
</style>
