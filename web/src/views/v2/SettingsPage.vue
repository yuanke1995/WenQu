<template>
  <div class="v2-page">
    <div class="v2-page-head">
      <h3 class="v2-page-title">系统设置</h3>
      <span v-if="dirtyCount" class="dirty-hint">有 {{ dirtyCount }} 项已修改未保存</span>
      <button v-else class="head-hint-plain">修改后点右侧保存生效，悬停参数旁 ? 查看说明</button>
      <button class="v2-btn" style="margin-left:auto" :disabled="!dirtyCount" :class="{ dis: !dirtyCount }" @click="save">
        <save-outlined /> 保存配置{{ dirtyCount ? `（${dirtyCount} 项改动）` : '' }}
      </button>
    </div>

    <div class="set-body">
      <!-- 左侧分组导航 -->
      <nav class="set-nav">
        <span v-for="p in PANELS" :key="p.key" class="set-nav-item" :class="{ active: current === p.key }" @click="current = p.key">
          {{ groupLabel(p.key) }}
        </span>
      </nav>

      <!-- 右侧：当前分组表单 -->
      <section class="set-content">
        <a-spin :spinning="loading">
          <div class="set-panel-head">
            <h3 class="v2-page-title">{{ currentPanel?.title }}</h3>
            <button v-if="!NO_RESET.includes(current)" class="v2-btn ghost" :disabled="resettingKey === current" @click="onResetGroup(current)">
              恢复本组默认
            </button>
          </div>

          <a-alert v-for="(al, ai) in (PANEL_ALERTS[current] || [])" :key="ai" :type="al.type" show-icon
                   style="margin-bottom:12px" :message="al.msg" />

          <div class="v2-card set-card">
            <a-form :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }" @submit.prevent>
              <template v-for="(blk, i) in blocksOf(current)" :key="i">
                <div v-if="blk.type === 'sub'" class="cfg-sub">{{ blk.title }}</div>

                <!-- 向量模型组：索引状态与重嵌入（只读状态 + 手动触发） -->
                <template v-if="current === 'embedding' && blk.type === 'sub' && blk.title.includes('索引状态')">
                  <a-form-item>
                    <template #label>
                      <a-tooltip :title="TIPS.embeddingDimensions" placement="top">当前索引维度 <question-circle-outlined class="tip-icon" /></a-tooltip>
                    </template>
                    <span v-if="embeddingDimensions" style="color:var(--v2-text2)">{{ embeddingDimensions }} 维</span>
                    <span v-else style="color:var(--v2-text3)">未记录（首次重嵌入完成后自动记录）</span>
                  </a-form-item>
                  <a-form-item label="重嵌入状态">
                    <div>
                      <span v-if="reembed.status === 'running'" style="color:var(--v2-accent)">进行中：{{ reembed.done }} / {{ reembed.total }} 块<span v-if="reembed.failed" style="color:var(--v2-danger)">（失败 {{ reembed.failed }}）</span></span>
                      <span v-else-if="reembed.status === 'done'" style="color:var(--v2-ok)">已完成：{{ reembed.done }} 块<span v-if="reembed.failed" style="color:var(--v2-danger)">（失败 {{ reembed.failed }}，可重试补齐）</span></span>
                      <span v-else-if="reembed.status === 'failed'" style="color:var(--v2-danger)">失败：{{ reembed.error }}（已完成 {{ reembed.done }} 块，可重试）</span>
                      <span v-else style="color:var(--v2-text3)">未运行</span>
                      <button class="v2-btn ghost small" :disabled="reembedTriggering" @click="doTriggerReembed">{{ reembedTriggering ? '启动中…' : '手动重嵌入' }}</button>
                      <button class="v2-btn ghost small" @click="refreshReembedStatus">刷新</button>
                    </div>
                    <div v-if="reembed.status !== 'idle'" class="reembed-meta">
                      <span v-if="reembed.newDim">维度：{{ reembed.oldDim || '未知' }} → {{ reembed.newDim }}</span>
                      <span v-if="reembedElapsed" style="margin-left:12px">耗时 {{ reembedElapsed }}</span>
                      <span v-if="reembed.indexed" style="margin-left:12px">索引内 {{ reembed.indexed }} 块<span v-if="reembed.status === 'done' && reembed.indexed < reembed.done" style="color:var(--v2-danger)">（少于成功写入数，建议再跑一次）</span></span>
                    </div>
                  </a-form-item>
                </template>

                <!-- 常规字段（SchemaField 全量复用：类型控件/条件显隐/参数说明） -->
                <template v-else-if="blk.type === 'field'">
                  <SchemaField :field="blk.field" :form="form" :tips="TIPS" @change="onFieldChange">
                    <template v-if="probeKey(blk.field)" #extra>
                      <button class="v2-btn ghost small probe-btn" :disabled="probeStates[probeKey(blk.field)].loading" @click="doProbe(probeKey(blk.field))">
                        {{ probeStates[probeKey(blk.field)].loading ? '测试中…' : '测试连接' }}
                      </button>
                      <a-tooltip v-if="probeStates[probeKey(blk.field)].result" :title="probeStates[probeKey(blk.field)].result.detail">
                        <span class="probe-chip" :class="probeStates[probeKey(blk.field)].result.available ? 'ok' : 'bad'">
                          {{ probeStates[probeKey(blk.field)].result.available ? '可达' : '不可达' }} {{ probeStates[probeKey(blk.field)].result.latencyMs }}ms
                        </span>
                      </a-tooltip>
                    </template>
                  </SchemaField>
                  <!-- 厂商预设：紧跟问答模型名之后 -->
                  <a-form-item v-if="current === 'chat' && blk.field.group === 'chat' && blk.field.key === 'model'">
                    <template #label>
                      <a-tooltip :title="TIPS.chatPreset" placement="top">厂商预设 <question-circle-outlined class="tip-icon" /></a-tooltip>
                    </template>
                    <a-select v-model:value="chatPreset" style="width:340px" :options="chatPresetOptions"
                              placeholder="选择厂商自动填充网关地址与补全路径" @change="onChatPresetChange" />
                  </a-form-item>
                </template>
              </template>

              <!-- 语义缓存运维：运行统计 + 手动清空 -->
              <a-form-item v-if="current === 'semanticCache'" label="缓存状态">
                <div>
                  <span v-if="cacheStats.count != null" style="color:var(--v2-text2)">
                    已缓存 <b style="color:var(--v2-accent);font-weight:500">{{ cacheStats.count }}</b> 条（上限 {{ form.semanticCache?.maxEntries ?? '—' }}）
                  </span>
                  <span v-else style="color:var(--v2-text3)">统计未加载</span>
                  <a-popconfirm title="清空后缓存重新积累，确定清空？" ok-text="清空" cancel-text="取消" @confirm="doClearCache">
                    <button class="v2-btn danger small" style="margin-left:12px" :disabled="cacheClearing">{{ cacheClearing ? '清空中…' : '清空语义缓存' }}</button>
                  </a-popconfirm>
                  <button class="v2-btn ghost small" style="margin-left:8px" @click="refreshCacheStats">刷新</button>
                </div>
                <div class="reembed-meta">清空后按提问重新积累；知识库变更（解析/删除/回滚/启停用）时后端会自动整体清空，一般无需手动操作。</div>
              </a-form-item>
            </a-form>
          </div>
        </a-spin>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { SaveOutlined, QuestionCircleOutlined } from '@ant-design/icons-vue'
import { getConfig, saveConfig, resetConfig, checkRerank, checkKeywordEngine, getAnswerCacheStats, clearAnswerCache,
         getReembedStatus, triggerReembed, probeConnectivity } from '../../api'
import SchemaField from '../../components/SchemaField.vue'
import { FIELDS, PANELS, TIPS, blocksOf, buildDefaultForm, readForm, writeForm } from '../../configSchema'

// 分组导航（沿用旧版锚点短名）
const NAV_LABELS = {
  chat: '智能问答模型', vision: '视觉模型', chunk: '文档解析', embedding: '向量模型', retrieval: '检索设置',
  context: '上下文控制', deepReasoning: '深度思考', tool: '工具调用', mcp: 'MCP 外部工具',
  semanticCache: '语义缓存', ratelimit: '接口限流', maintenance: '定时维护'
}
const groupLabel = key => NAV_LABELS[key] || key
const current = ref('chat')
const currentPanel = computed(() => PANELS.find(p => p.key === current.value))

// 无「恢复本组默认」的分组
const NO_RESET = ['embedding', 'maintenance']

// 分组顶部说明（与旧版文案一致）
const PANEL_ALERTS = {
  chat: [{ type: 'info', msg: '问答模型支持跨厂商热切换：修改网关地址/API Key/模型名保存即生效免重启，API Key 以 RSA 加密入库。' }],
  vision: [{ type: 'info', msg: '视觉模型用于文档图片与用户图片的描述识别；关闭后图片仅展示、内容不进检索与引用。' }],
  chunk: [{ type: 'info', msg: '上传大小上限保存即生效；分块/图片上限只对重新解析/新上传文档生效，超限按保护策略截断入库。' }],
  embedding: [{ type: 'warning', msg: '向量模型热切换说明：不同模型的向量数学上不可迁移。保存时会先探测新配置并校验维度，通过后自动重建索引并后台全量重嵌入；任务开始即清空语义缓存。重嵌入期间向量检索自动降级关键词路，服务不中断。' }],
  retrieval: [
    { type: 'info', msg: '融合分 = 向量权重×向量相似度 + 关键词权重×命中率 + 标题命中奖励。保存后立即生效。' },
    { type: 'info', msg: '重排：OpenAI 兼容 /v1/rerank 服务。未启动或不可用时自动回退融合分排序，不影响正常问答。' }
  ],
  context: [{ type: 'info', msg: '预算 = min(模型窗口×安全系数−输出限制, 成本上限)，知识块按相关度降序累积填充，超出自动裁剪。保存后立即生效。' }],
  deepReasoning: [{ type: 'info', msg: '深度思考：AI 先流式展示思维链，思考末尾输出检索计划（精化 query + 子问题）多路并行检索合并后回答。失败自动降级。' }],
  semanticCache: [{ type: 'info', msg: '命中相似问题（≥阈值）时直接复用历史回答：省检索与 LLM 成本、秒级返回。知识库变更时自动整体清空，不会用过期答案。' }],
  ratelimit: [{ type: 'info', msg: 'Redis 固定窗口计数，按用户（匿名按 IP）限频，超限返回 429。限频设为 0 表示不限流；Redis 不可用时自动放行。' }],
  maintenance: [{ type: 'info', msg: '后台定时任务参数，保存即生效。周期填 ≤0 表示暂停该任务；清理类任务只删超期数据。' }]
}

const loading = ref(false)
const saving = ref(false)

// ==================== 表单状态与回填 ====================
const form = ref({ ...buildDefaultForm(),
  intent: { enabled: false, timeoutMillis: 3000, model: '', prompt: '', chatPrompt: '' } })

const fetchAndFill = async () => {
  loading.value = true
  try {
    const r = await getConfig()
    if (r.success && r.data) {
      const d = r.data
      for (const f of FIELDS) {
        const raw = d[f.group]?.[f.submitKey || f.key]?.value
        let v
        if (raw === undefined || raw === null || raw === '') {
          v = f.type === 'switch' ? false : (f.type === 'number' ? f.def : (f.def ?? ''))
        } else if (f.type === 'number') v = Number(raw)
        else if (f.type === 'switch') v = raw === 'true' || raw === true
        else v = raw
        if (f.factor) v = Math.round(Number(v) / f.factor)
        writeForm(form.value, f.path, v)
      }
      const em = d.embedding || {}
      embeddingDimensions.value = em.dimensions?.value || ''
      refreshCacheStats()
      refreshReembedStatus()
      initialPayload.value = buildPayload()
    }
  } catch (e) { message.error(e.message || '加载配置失败') }
  finally { loading.value = false }
}

// ==================== 提交载荷与脏检测（与旧版同一管线） ====================
const buildPayload = () => {
  const out = {}
  for (const f of FIELDS) {
    let v = readForm(form.value, f.path)
    if (typeof v === 'string') {
      v = v.trim()
      if (/apikey/i.test(f.key) && v.startsWith('****')) continue
    } else if (typeof v === 'number' || typeof v === 'boolean') {
      v = String(v)
    } else if (v === undefined || v === null) {
      continue
    }
    if (f.factor) v = String(Math.round(Number(v) * f.factor))
    ;(out[f.group] = out[f.group] || {})[f.submitKey || f.key] = v
  }
  return out
}
const initialPayload = ref(null)
const dirtyPayload = computed(() => {
  const base = initialPayload.value
  if (!base) return {}
  const cur = buildPayload()
  const out = {}
  for (const g of Object.keys(cur)) {
    const cb = base[g] || {}
    const diff = {}
    for (const k of Object.keys(cur[g] || {})) {
      if (JSON.stringify(cur[g][k]) !== JSON.stringify(cb[k])) diff[k] = cur[g][k]
    }
    if (Object.keys(diff).length) out[g] = diff
  }
  return out
})
const dirtyCount = computed(() =>
  Object.values(dirtyPayload.value).reduce((n, g) => n + Object.keys(g).length, 0))

const save = async () => {
  const payload = dirtyPayload.value
  if (!Object.keys(payload).length) { message.info('没有需要保存的改动'); return }
  saving.value = true
  try {
    const r = await saveConfig(payload)
    if (r.success) {
      const n = r.data && typeof r.data === 'object' ? Object.keys(r.data).length : 0
      if (n === 0) message.warning('没有可保存的配置项（后端未识别提交的键），请检查后重试')
      else { message.success(`配置已保存并生效（更新 ${n} 项）`); initialPayload.value = buildPayload() }
      refreshReembedStatus()
    } else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}

// ==================== 恢复本组默认 ====================
const resettingKey = ref('')
const doResetGroup = async (key, label) => {
  resettingKey.value = key
  try {
    const r = await resetConfig([key])
    if (r.success) {
      const cnt = r.data && typeof r.data === 'object' ? Object.keys(r.data).length : 0
      message.success(`「${label}」已恢复默认（${cnt} 项）`)
      await fetchAndFill()
    } else message.error(r.msg || '恢复失败')
  } catch (e) { message.error(e.message || '恢复失败') }
  finally { resettingKey.value = '' }
}
const onResetGroup = key => {
  const label = groupLabel(key)
  Modal.confirm({
    title: `恢复「${label}」为默认值？`,
    content: '该组当前的自定义值会被覆盖为出厂默认（模型 API Key 与向量模型组不受影响）。',
    okText: '恢复', cancelText: '取消',
    onOk: () => doResetGroup(key, label)
  })
}

// ==================== 测试连接（先测后存） ====================
const probeStates = ref({
  chat: { loading: false, result: null },
  vision: { loading: false, result: null },
  embedding: { loading: false, result: null },
  rerank: { loading: false, result: null },
  keyword: { loading: false, result: null }
})
const probeLabels = { chat: '对话模型', vision: '视觉模型', embedding: '向量模型', rerank: '重排服务', keyword: '关键词引擎' }
const PROBE_BY_KEY = {
  'chat.baseUrl': 'chat', 'vision.baseUrl': 'vision', 'embedding.baseUrl': 'embedding',
  'keyword.baseUrl': 'keyword', 'rerank.baseUrl': 'rerank'
}
const probeKey = f => PROBE_BY_KEY[f.group + '.' + f.key] || ''

const doProbe = async group => {
  const s = probeStates.value[group]
  if (!s || s.loading) return
  const f = form.value
  const payload = { group }
  if (group === 'chat') {
    Object.assign(payload, { baseUrl: f.chat.baseUrl, apiKey: f.chat.apiKey, model: f.chat.model, path: f.chat.completionsPath })
  } else if (group === 'vision') {
    Object.assign(payload, { baseUrl: f.vision.baseUrl, apiKey: f.vision.apiKey, model: f.vision.model })
  } else if (group === 'embedding') {
    Object.assign(payload, { baseUrl: f.embedding.baseUrl, apiKey: f.embedding.apiKey, model: f.embedding.model, path: f.embedding.embeddingsPath })
  } else if (group === 'rerank') {
    Object.assign(payload, { baseUrl: f.retrieval.rerank.baseUrl, model: f.retrieval.rerank.model })
  } else if (group === 'keyword') {
    Object.assign(payload, { baseUrl: f.keyword.baseUrl, apiKey: f.keyword.apiKey })
  }
  s.loading = true
  s.result = null
  try {
    const r = await probeConnectivity(payload)
    const d = r?.data || {}
    s.result = { available: !!d.available, latencyMs: d.latencyMs ?? 0, detail: d.detail || (r?.msg || '（无详情）') }
    if (!s.result.available) message.error(`${probeLabels[group]}不可达：${s.result.detail}`)
  } catch (e) {
    s.result = { available: false, latencyMs: 0, detail: e.message || '请求失败' }
    message.error(`${probeLabels[group]}探测失败：${s.result.detail}`)
  } finally { s.loading = false }
}

// 被探测项改动后清空旧探测结果
watch(
  () => [
    form.value.chat.baseUrl, form.value.chat.completionsPath, form.value.chat.model, form.value.chat.apiKey,
    form.value.vision.baseUrl, form.value.vision.model, form.value.vision.apiKey,
    form.value.embedding.baseUrl, form.value.embedding.embeddingsPath, form.value.embedding.model, form.value.embedding.apiKey,
    form.value.retrieval.rerank.baseUrl, form.value.retrieval.rerank.model,
    form.value.keyword.baseUrl, form.value.keyword.apiKey
  ],
  () => {
    for (const k of Object.keys(probeStates.value)) probeStates.value[k].result = null
  }
)

// ==================== 枚举变更校验 / 厂商预设 ====================
const onFieldChange = (field, v) => {
  if (field.group === 'keyword' && field.key === 'engine') onKeywordEngineChange(v)
}
const onKeywordEngineChange = async val => {
  if (val !== 'meilisearch') return
  try {
    const r = await checkKeywordEngine()
    if (r.success && r.data?.available) {
      message.success('Meilisearch 服务正常。保存后请执行全量重建（/api/ai/search-index/reindex）再提问')
    } else {
      form.value.keyword.engine = 'mysql'
      message.error('Meilisearch 不可用：请先启动服务，或检查服务地址')
    }
  } catch (e) {
    form.value.keyword.engine = 'mysql'
    message.error('Meilisearch 校验失败：' + (e.message || '服务不可用'))
  }
}

const chatPreset = ref('custom')
const chatPresets = {
  deepseek: { baseUrl: 'https://api.deepseek.com', completionsPath: '/v1/chat/completions' },
  zhipu: { baseUrl: 'https://open.bigmodel.cn/api/paas', completionsPath: '/v4/chat/completions' },
  dashscope: { baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode', completionsPath: '/v1/chat/completions' },
  moonshot: { baseUrl: 'https://api.moonshot.cn', completionsPath: '/v1/chat/completions' },
  ark: { baseUrl: 'https://ark.cn-beijing.volces.com/api', completionsPath: '/v3/chat/completions' },
  hunyuan: { baseUrl: 'https://api.hunyuan.cloud.tencent.com', completionsPath: '/v1/chat/completions' },
  qianfan: { baseUrl: 'https://qianfan.baidubce.com', completionsPath: '/v2/chat/completions' },
  minimax: { baseUrl: 'https://api.minimax.chat', completionsPath: '/v1/chat/completions' },
  siliconflow: { baseUrl: 'https://api.siliconflow.cn', completionsPath: '/v1/chat/completions' },
  ollama: { baseUrl: 'http://localhost:11434', completionsPath: '/v1/chat/completions' }
}
const chatPresetOptions = [
  { value: 'custom', label: '自定义 / 保持现状' },
  { value: 'deepseek', label: 'DeepSeek（api.deepseek.com）' },
  { value: 'zhipu', label: '智谱 GLM（open.bigmodel.cn）' },
  { value: 'dashscope', label: '阿里百炼 Qwen（dashscope）' },
  { value: 'moonshot', label: 'Kimi 月之暗面（moonshot）' },
  { value: 'ark', label: '豆包/火山方舟（volces.com）' },
  { value: 'hunyuan', label: '腾讯混元（hunyuan）' },
  { value: 'qianfan', label: '百度千帆 v2（qianfan）' },
  { value: 'minimax', label: 'MiniMax（minimax.chat）' },
  { value: 'siliconflow', label: 'SiliconFlow 硅基流动（多模型聚合）' },
  { value: 'ollama', label: '本地 Ollama（localhost:11434）' }
]
const onChatPresetChange = val => {
  const p = chatPresets[val]
  if (!p) return
  form.value.chat.baseUrl = p.baseUrl
  form.value.chat.completionsPath = p.completionsPath
  message.info('已填充网关地址与补全路径，请补齐 API Key 与模型名后保存')
}

// ==================== 语义缓存统计与清空 ====================
const cacheStats = ref({ count: null })
const cacheClearing = ref(false)
const refreshCacheStats = () => {
  getAnswerCacheStats().then(r => { if (r.success) cacheStats.value = r.data }).catch(() => {})
}
const doClearCache = async () => {
  cacheClearing.value = true
  try {
    const r = await clearAnswerCache()
    if (r.success) { cacheStats.value = { count: 0 }; message.success('答案缓存已清空') }
    else message.error(r.msg || '清空失败')
  } catch (e) { message.error(e.message || '清空失败') }
  finally { cacheClearing.value = false }
}

// ==================== 重嵌入状态 ====================
const embeddingDimensions = ref('')
const reembed = ref({ status: 'idle', total: 0, done: 0, failed: 0, error: null, oldDim: 0, newDim: 0, indexed: 0 })
const reembedTriggering = ref(false)
const reembedElapsed = computed(() => {
  const s = reembed.value
  if (!s.startTime) return ''
  const end = s.status === 'running' ? Date.now() : (s.endTime || 0)
  if (!end || end < s.startTime) return ''
  const sec = Math.round((end - s.startTime) / 1000)
  return sec < 60 ? `${sec} 秒` : `${Math.floor(sec / 60)} 分 ${sec % 60} 秒`
})
let reembedTimer = null
const refreshReembedStatus = async () => {
  try {
    const r = await getReembedStatus()
    if (r.success) reembed.value = r.data || { status: 'idle' }
    if (reembed.value.status === 'running') {
      if (!reembedTimer) reembedTimer = setInterval(refreshReembedStatus, 3000)
    } else if (reembedTimer) {
      clearInterval(reembedTimer); reembedTimer = null
    }
  } catch (e) { /* 静默 */ }
}
const doTriggerReembed = async () => {
  reembedTriggering.value = true
  try {
    const r = await triggerReembed()
    if (r.success) { message.success('全量重嵌入任务已启动，期间检索自动降级关键词路'); refreshReembedStatus() }
    else message.error(r.msg || '触发失败')
  } catch (e) { message.error(e.message || '触发失败') }
  finally { reembedTriggering.value = false }
}

onMounted(fetchAndFill)
onUnmounted(() => {
  if (reembedTimer) { clearInterval(reembedTimer); reembedTimer = null }
})
</script>

<style scoped>
.dirty-hint { font-size: 12px; color: #a3691b; background: #faf3e6; border-radius: 6px; padding: 3px 10px; }
.head-hint-plain { font-size: 12px; color: var(--v2-text3); background: transparent; border: none; }
.v2-btn.dis { background: #c6d4f2; cursor: not-allowed; }
.set-body { flex: 1; min-height: 0; display: flex; }
.set-nav {
  width: 150px; flex: none; border-right: 1px solid var(--v2-border); background: var(--v2-panel);
  padding: 10px 8px; display: flex; flex-direction: column; gap: 2px; overflow-y: auto;
}
.set-nav-item { padding: 7px 10px; border-radius: 8px; font-size: 12px; color: var(--v2-text2); cursor: pointer; }
.set-nav-item:hover { background: var(--v2-accent-weak); }
.set-nav-item.active { background: var(--v2-accent-weak); color: var(--v2-text); font-weight: 500; }
.set-content { flex: 1; min-width: 0; overflow-y: auto; padding: 14px 20px 24px; }
.set-panel-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.set-card { padding: 18px 20px 6px; }
.cfg-sub { font-size: 12px; font-weight: 500; color: var(--v2-text3); margin: 14px 0 2px; padding-bottom: 4px; border-bottom: 1px dashed var(--v2-border); }
.tip-icon { color: var(--v2-text3); font-size: 12px; cursor: help; }
.v2-btn.small { padding: 3px 10px; font-size: 11px; border-radius: 6px; margin-left: 10px; }
.v2-btn.small + .v2-btn.small { margin-left: 8px; }
.probe-btn { margin-left: 8px; }
.probe-chip { margin-left: 8px; font-size: 11px; border-radius: 999px; padding: 3px 9px; cursor: help; }
.probe-chip.ok { color: var(--v2-ok); background: #eaf5ec; }
.probe-chip.bad { color: var(--v2-danger); background: #fbecea; }
.reembed-meta { margin-top: 6px; color: var(--v2-text3); font-size: 12px; line-height: 1.8; }
</style>
