<template>
  <div>
    <a-alert type="info" show-icon style="margin-bottom:16px"
             message="问答/视觉/向量三类模型均支持跨厂商热切换：修改网关地址/API Key/模型名（预设覆盖 DeepSeek、智谱GLM、百炼Qwen、Kimi、豆包、混元、千帆、MiniMax、SiliconFlow、Ollama 等 OpenAI 兼容端点），保存即生效免重启，API Key 以 RSA 加密入库。其中向量模型切换会先探测新配置（失败拒绝保存），通过后自动全量重嵌入并在下方展示进度。鼠标悬停参数名旁的 ? 可查看说明。" />

    <!-- 模式开关：新手只看必需项，专家显示全部 -->
    <div class="mode-bar">
      <span class="mode-label">专家模式</span>
      <a-switch v-model:checked="expertMode" />
      <span class="mode-hint">{{ expertMode ? '显示全部参数（含超时 / 重试 / 并发等排障项）' : '只显示必配项与检索强度，其余保持默认' }}</span>
    </div>

    <!-- 分区锚点：点击展开并平滑定位到对应配置分组 -->
    <div v-if="expertMode" class="cfg-anchor">
      <template v-for="a in anchors" :key="a.key">
        <a :class="{ 'anchor-active': currentAnchor === a.key }" href="javascript:void(0)" @click="jumpTo(a.key)">{{ a.label }}</a>
      </template>
      <span class="anchor-legend"><span class="core-dot"></span>＝ 关键参数（其余为进阶调优，悬停 ? 看说明）</span>
    </div>

    <!-- 未保存改动提示（差异感知：避免"以为保存了其实没有"） -->
    <div v-if="dirtyCount" class="dirty-tip">
      <warning-outlined style="color:#d48806" /> 有 {{ dirtyCount }} 项配置已修改未保存，点击右下角「保存配置（{{ dirtyCount }} 项改动）」生效
    </div>

    <a-spin :spinning="loading">
      <SettingsBasic v-if="!expertMode" :form="form" :probe-states="probeStates"
                     @probe="doProbe" @switch-expert="expertMode = true" />

            <a-collapse v-else v-model:activeKey="activeKeys" :bordered="false" class="cfg-collapse">
        <a-collapse-panel v-for="p in PANELS" :key="p.key" :id="'cfg-anchor-' + p.key" :header="p.title">
          <template v-if="!NO_RESET.includes(p.key)" #extra>
            <a-button size="small" type="text" class="reset-group-btn" :loading="resettingKey === p.key"
                      @click.stop="onResetGroup(p.key)">恢复本组默认</a-button>
          </template>
          <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 14 }">
            <template v-for="(blk, i) in blocksOf(p.key)" :key="i">
              <div v-if="blk.type === 'sub'" class="cfg-sub">{{ blk.title }}</div>
              <template v-if="p.key === 'embedding' && blk.type === 'sub' && blk.title.includes('索引状态')">
                <a-form-item>
                  <template #label><a-tooltip :title="TIPS.embeddingDimensions" placement="top">当前索引维度 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
                  <span v-if="embeddingDimensions" style="color:#555">{{ embeddingDimensions }} 维</span>
                  <span v-else style="color:#999">未记录（尚未切换过向量模型；首次重嵌入完成后自动记录）</span>
                </a-form-item>
                <a-form-item label="重嵌入状态">
                  <div>
                    <span v-if="reembed.status === 'running'" style="color:#1677ff">
                      进行中：{{ reembed.done }} / {{ reembed.total }} 块
                      <span v-if="reembed.failed" style="color:#cf1322">（失败 {{ reembed.failed }}）</span>
                    </span>
                    <span v-else-if="reembed.status === 'done'" style="color:#389e0d">
                      已完成：{{ reembed.done }} 块<span v-if="reembed.failed" style="color:#cf1322">（失败 {{ reembed.failed }}，可重试补齐）</span>
                    </span>
                    <span v-else-if="reembed.status === 'failed'" style="color:#cf1322">
                      失败：{{ reembed.error }}（已完成 {{ reembed.done }} 块，可重试）
                    </span>
                    <span v-else style="color:#999">未运行</span>
                    <a-button size="small" style="margin-left:12px" :loading="reembedTriggering" @click="doTriggerReembed">
                      手动重嵌入
                    </a-button>
                    <a-button size="small" style="margin-left:8px" @click="refreshReembedStatus">刷新</a-button>
                  </div>
                  <!-- 维度变化 / 耗时 / 索引对账：任务跑过才有意义 -->
                  <div v-if="reembed.status !== 'idle'" style="margin-top:6px;color:#999;font-size:12px;line-height:1.8">
                    <span v-if="reembed.newDim">
                      维度：{{ reembed.oldDim || '未知' }} → {{ reembed.newDim }}
                      <span v-if="reembed.oldDim && reembed.oldDim !== reembed.newDim" style="color:#d46b08">（维度已变，索引 schema 已按新维度重建）</span>
                    </span>
                    <span v-if="reembedElapsed" style="margin-left:12px">耗时 {{ reembedElapsed }}</span>
                    <!-- 对账：索引内实际块数少于成功写入数 = 有丢块（DROP 与并发解析撞车），需再跑一次补齐 -->
                    <span v-if="reembed.indexed" style="margin-left:12px">
                      索引内 {{ reembed.indexed }} 块
                      <span v-if="reembed.status === 'done' && reembed.indexed < reembed.done" style="color:#cf1322">
                        ⚠ 少于成功写入 {{ reembed.done }} 块（疑与并发解析撞车丢块，建议解析空闲时再跑一次）
                      </span>
                    </span>
                  </div>
                </a-form-item>
              </template>
              <template v-else-if="blk.type === 'field'">
                <SchemaField :field="blk.field" :form="form" :tips="tips" @change="onFieldChange">
                  <template v-if="probeKey(blk.field)" #extra>
                    <a-button size="small" style="margin-left:8px" :loading="probeStates[probeKey(blk.field)].loading"
                              @click="doProbe(probeKey(blk.field))">测试连接</a-button>
                    <a-tooltip v-if="probeStates[probeKey(blk.field)].result" :title="probeStates[probeKey(blk.field)].result.detail">
                      <span class="probe-chip" :class="probeStates[probeKey(blk.field)].result.available ? 'probe-ok' : 'probe-bad'">
                        {{ probeStates[probeKey(blk.field)].result.available ? '可达' : '不可达' }} {{ probeStates[probeKey(blk.field)].result.latencyMs }}ms
                      </span>
                    </a-tooltip>
                  </template>
                </SchemaField>
                <!-- 厂商预设：非配置项，紧跟模型名之后 -->
                <a-form-item v-if="blk.field.group === 'chat' && blk.field.key === 'model'">
                  <template #label><a-tooltip :title="TIPS.chatPreset" placement="top">厂商预设 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
                  <a-select v-model:value="chatPreset" style="width:360px" :options="chatPresetOptions"
                            placeholder="选择厂商自动填充网关地址与补全路径" @change="onChatPresetChange" />
                </a-form-item>
              </template>
            </template>
          </a-form>
          <a-alert v-for="(al, ai) in (PANEL_ALERTS[p.key] || [])" :key="ai" :type="al.type" show-icon
                   style="margin:0 24px 16px" :message="al.msg" />
        </a-collapse-panel>
      </a-collapse>

      <!-- 悬浮保存按钮：固定在右下角，无需滚动到底部 -->
      <div style="position:fixed; right:24px; bottom:24px; z-index:100; margin:0">
        <a-button type="primary" :loading="saving" :disabled="!dirtyCount" @click="save"
                  style="box-shadow:0 4px 12px rgba(0,0,0,0.18)">
          <template #icon><save-outlined /></template>
          {{ dirtyCount ? `保存配置（${dirtyCount} 项改动）` : '保存配置' }}
        </a-button>
      </div>
    </a-spin>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { QuestionCircleOutlined, SaveOutlined, WarningOutlined } from '@ant-design/icons-vue'
import { getConfig, saveConfig, resetConfig, checkRerank, checkKeywordEngine, getAnswerCacheStats, clearAnswerCache, getReembedStatus, triggerReembed, probeConnectivity } from '../api'
import SettingsBasic from './SettingsBasic.vue'
import SchemaField from '../components/SchemaField.vue'
import { FIELDS, PANELS, TIPS, blocksOf, buildDefaultForm, readForm, writeForm } from '../configSchema'

// 专家模式：false=只显示 L1 必配项，true=显示全部 157 项。状态持久化到 localStorage
const expertMode = ref(localStorage.getItem('settings.expertMode') === '1')
watch(expertMode, v => localStorage.setItem('settings.expertMode', v ? '1' : '0'))

// 面板级提示文案（schema 只描述字段，面板说明留在使用方）
const PANEL_ALERTS = {
  "chunk": [
    {
      "type": "info",
      "msg": "上传大小上限保存即生效（新上传按新限制校验）；分块/图片上限对超大文档保护：知识块数超上限截断入库，图片数超上限不再提取描述。分块重叠与分块/图片上限均只对重新解析/新上传文档生效。"
    }
  ],
  "embedding": [
    {
      "type": "warning",
      "msg": "向量模型热切换说明：不同模型的向量在数学上不可迁移（维度/语义空间均不同）。保存时会先探测新配置并校验维度合法（探测失败或维度非法一律拒绝保存，旧索引保持完整）；通过后自动按新维度重建向量索引并后台全量重嵌入（无需重新上传文档，MySQL 知识块不动）。任务开始即清空语义缓存——旧模型的问题向量已作废，留着可能命中语义无关的历史回答。重嵌入期间向量检索自动降级关键词路，服务不中断。完成后请核对上方「索引内块数」与成功写入块数是否一致，并抽查几个问题验证召回质量。"
    }
  ],
  "retrieval": [
    {
      "type": "info",
      "msg": "融合分 = 向量权重×向量相似度 + 关键词权重×命中率 + 标题命中奖励。保存后立即生效，可配合「检索调试」对比效果。"
    },
    {
      "type": "info",
      "msg": "重排：OpenAI 兼容 /v1/rerank 服务（sentence-transformers CrossEncoder，bge-reranker-v2-m3）。未启动或不可用时自动回退融合分排序，不影响正常问答。"
    }
  ],
  "context": [
    {
      "type": "info",
      "msg": "预算 = min(模型窗口×安全系数−输出限制, 成本上限)，知识块按相关度降序累积填充，超出预算的块自动被裁；每块只取命中关键词±窗口片段。历史单条截断+总量限制，[图片N] 标记自动剥离避免编号冲突。保存后立即生效。"
    }
  ],
  "deepReasoning": [
    {
      "type": "info",
      "msg": "深度思考：AI 先流式展示思维链（回答上方折叠面板，思考完成自动收起），思考末尾输出 <search> 检索计划（精化 query + 子问题），多路并行检索合并后回答；思考链与思考关键词参与最终检索/回答增强。默认 maxThinkingTokens=0 不设上限（qwen 思考模式设 max_tokens 会空输出）。失败自动降级（思考内容不白费，用于增强检索）。"
    }
  ],
  "semanticCache": [
    {
      "type": "info",
      "msg": "命中相似问题（≥阈值）时直接复用历史回答：省检索与 LLM 成本、秒级返回，回答下方会标注来源问题。知识库变更（解析/删除/回滚/启停用）时自动整体清空，不会用过期答案。"
    }
  ],
  "ratelimit": [
    {
      "type": "info",
      "msg": "Redis 固定窗口计数，按用户（网关未透传 X-User-Id 时按 IP）限频，超限返回 429 并提示等待秒数。限频设为 0 表示该接口不限流；Redis 不可用时自动放行，不影响正常使用。保存后立即生效。"
    }
  ],
  "maintenance": [
    {
      "type": "info",
      "msg": "以上均为后台定时任务参数，保存即生效（已运行的调度按新周期重排）。周期填 ≤0 表示暂停该任务；清理类任务只删超期数据，不影响正在进行中的解析与问答。"
    }
  ]
}
// 无「恢复本组默认」按钮的面板
const NO_RESET = ['embedding', 'maintenance']
// 需要「测试连接」按钮的字段 → 对应探测分组
const PROBE_BY_KEY = {
  'chat.baseUrl': 'chat', 'vision.baseUrl': 'vision', 'embedding.baseUrl': 'embedding',
  'keyword.baseUrl': 'keyword', 'rerank.baseUrl': 'rerank'
}
const probeKey = f => PROBE_BY_KEY[f.group + '.' + f.key] || ''
// 枚举字段变更：关键词引擎切换需校验目标服务
const onFieldChange = (field, v) => {
  if (field.group === 'keyword' && field.key === 'engine') onKeywordEngineChange(v)
}

// 折叠面板：专家模式下默认展开三组核心配置（问答模型 / 向量模型 / 检索），其余收起
const activeKeys = ref(['chat', 'embedding', 'retrieval'])

// 分区锚点：点击展开 + 平滑滚动定位
const anchors = [
  { key: 'chat', label: '智能问答' },
  { key: 'vision', label: '视觉模型' },
  { key: 'chunk', label: '文档解析' },
  { key: 'embedding', label: '向量模型' },
  { key: 'retrieval', label: '检索设置' },
  { key: 'context', label: '上下文控制' },
  { key: 'deepReasoning', label: '深度思考' },
  { key: 'semanticCache', label: '语义缓存' },
  { key: 'ratelimit', label: '接口限流' },
  { key: 'maintenance', label: '定时维护' }
]
const currentAnchor = ref('')
let anchorObserver = null
const jumpTo = (key) => {
  currentAnchor.value = key
  // 展开该分组（若收起）
  if (!activeKeys.value.includes(key)) activeKeys.value = [...activeKeys.value, key]
  // 平滑滚动到分组
  requestAnimationFrame(() => {
    document.getElementById('cfg-anchor-' + key)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  })
}

// 参数说明（hover ? 查看"调整该参数会影响什么"）


const loading = ref(false)
const saving = ref(false)
const cacheStats = ref({ count: 0 })
const cacheClearing = ref(false)
const doClearCache = async () => {
  cacheClearing.value = true
  try {
    const r = await clearAnswerCache()
    if (r.success) { cacheStats.value = { count: 0 }; message.success('答案缓存已清空') }
    else message.error(r.msg || '清空失败')
  } catch (e) { message.error(e.message || '清空失败') }
  finally { cacheClearing.value = false }
}
const rerankChecking = ref(false)
const keywordChecking = ref(false)

// 切换关键词引擎：切到 meilisearch 时先校验服务可用性，不可用则回滚并提示（服务地址需先保存生效）
const onKeywordEngineChange = async val => {
  if (val !== 'meilisearch') return
  keywordChecking.value = true
  try {
    const r = await checkKeywordEngine()
    if (r.success && r.data?.available) {
      message.success('Meilisearch 服务正常。保存后请执行全量重建（接口 /api/ai/search-index/reindex）再提问')
    } else {
      form.value.keyword.engine = 'mysql'
      message.error('Meilisearch 不可用：请先启动服务（docker compose up meilisearch 或本机二进制），或检查服务地址')
    }
  } catch (e) {
    form.value.keyword.engine = 'mysql'
    message.error('Meilisearch 校验失败：' + (e.message || '服务不可用'))
  } finally {
    keywordChecking.value = false
  }
}

// 启用重排开关：打开前先校验服务可用性，服务不正常阻止开启并回滚
const onRerankEnabledChange = async checked => {
  if (!checked) return            // 关闭无需校验
  rerankChecking.value = true
  try {
    const r = await checkRerank()
    if (r.success && r.data?.available) {
      message.success('重排服务正常，已启用')
    } else {
      form.value.retrieval.rerank.enabled = false
      message.error('重排服务不可用：请先启动本地服务（scripts/win 或 scripts/mac 的 start_rerank_server），或检查服务地址')
    }
  } catch (e) {
    form.value.retrieval.rerank.enabled = false
    message.error('重排服务校验失败：' + (e.message || '服务不可用'))
  } finally {
    rerankChecking.value = false
  }
}
// 厂商预设：主流国产模型 OpenAI 兼容端点（baseUrl 均为网关根地址，不含版本段；版本段在 completionsPath）
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

const form = ref({ ...buildDefaultForm(),
  // intent.* 后端未纳入 defaults()/snapshot()，页面上无对应控件，仅保留默认值以防渲染取空
  intent: { enabled: false, timeoutMillis: 3000, model: '', prompt: '', chatPrompt: '' } })

// ==================== 测试连接（模型网关 / 服务可达性） ====================
// 用表单里「尚未保存」的值探测，先测后存；与保存流程无关，不改配置、不落库。
// 注意：必须定义在 form 之后——watch 创建时会立即执行取值函数收集依赖，早于 form 初始化会抛 ReferenceError。
const probeStates = ref({
  chat: { loading: false, result: null },
  vision: { loading: false, result: null },
  embedding: { loading: false, result: null },
  rerank: { loading: false, result: null },
  keyword: { loading: false, result: null }
})
const probeLabels = { chat: '对话模型', vision: '视觉模型', embedding: '向量模型', rerank: '重排服务', keyword: '关键词引擎' }

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
    s.result = {
      available: !!d.available,
      latencyMs: d.latencyMs ?? 0,
      detail: d.detail || (r?.msg || '（无详情）')
    }
    // 失败时同时弹提示：详情可能较长，chip + tooltip 之外再给一次显性反馈
    if (!s.result.available) {
      message.error(`${probeLabels[group]}不可达：${s.result.detail}`)
    }
  } catch (e) {
    s.result = { available: false, latencyMs: 0, detail: e.message || '请求失败' }
    message.error(`${probeLabels[group]}探测失败：${s.result.detail}`)
  } finally {
    s.loading = false
  }
}

// 任一被探测的配置项改动后，清空已有的探测结果（避免"改了地址还显示旧的可达"）
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

// 当前向量索引维度（后端重嵌入成功后回写 embedding.dimensions，只读展示）
const embeddingDimensions = ref('')

// 向量模型全量重嵌入状态（切换后自动触发/手动重试；运行中轮询刷新）
const reembed = ref({ status: 'idle', total: 0, done: 0, failed: 0, error: null, oldDim: 0, newDim: 0, indexed: 0 })
const reembedTriggering = ref(false)
// 耗时：运行中按当前时间算（轮询驱动刷新），结束后按 endTime 定格
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
    // 运行中每 3s 轮询，结束即停
    if (reembed.value.status === 'running') {
      if (!reembedTimer) reembedTimer = setInterval(refreshReembedStatus, 3000)
    } else if (reembedTimer) {
      clearInterval(reembedTimer); reembedTimer = null
      // 任务结束时后端已回写 embedding.dimensions，同步刷新只读维度展示
      if (reembed.value.newDim) embeddingDimensions.value = String(reembed.value.newDim)
    }
  } catch (e) { /* 状态查询失败静默（不影响配置页） */ }
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

/** 拉取配置并回填表单与保存基线（进入页面 / 恢复本组默认后调用） */
const fetchAndFill = async () => {
  loading.value = true
  try {
    const r = await getConfig()
    if (r.success && r.data) {
      const d = r.data
      // 由 schema 统一回填：类型按 field.type 归一，MB 字段按 factor 换算
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
      // 只读：后端记录的当前索引维度（重嵌入成功后回写；空=尚未记录）
      embeddingDimensions.value = em.dimensions?.value || ''
      // 缓存统计（条数）异步刷新
      getAnswerCacheStats().then(r => { if (r.success) cacheStats.value = r.data }).catch(() => {})
      // 重嵌入状态（若后台仍在跑则自动开启轮询）
      refreshReembedStatus()
      // 保存差异基线：以"表单 → 载荷"同一管线产物为准（与后端掩码/数值归一一致）
      initialPayload.value = buildPayload()
    }
  } catch (e) { message.error(e.message || '加载配置失败') }
  finally { loading.value = false }
}

onMounted(async () => {
  await fetchAndFill()

  // 滚动高亮跟随：视口内最靠上的分组自动点亮对应锚点
  anchorObserver = new IntersectionObserver(entries => {
    entries.forEach(en => {
      if (en.isIntersecting) currentAnchor.value = en.target.id.replace('cfg-anchor-', '')
    })
  }, { rootMargin: '-20px 0px -70% 0px', threshold: 0 })
  anchors.forEach(a => {
    const el = document.getElementById('cfg-anchor-' + a.key)
    if (el) anchorObserver.observe(el)
  })
})

onUnmounted(() => {
  if (anchorObserver) { anchorObserver.disconnect(); anchorObserver = null }
  if (reembedTimer) { clearInterval(reembedTimer); reembedTimer = null }
})

/** 从当前表单组装提交载荷（与后端分组/掩码规则一致：掩码 apiKey 不提交） */
const buildPayload = () => {
  const out = {}
  for (const f of FIELDS) {
    let v = readForm(form.value, f.path)
    if (typeof v === 'string') {
      v = v.trim()
      // 掩码 apiKey 原样提交会覆盖真实 key：未修改（**** 开头）则跳过
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

/** 加载完成时的基线载荷（保存差异判定用；掩码 apiKey 未改时载荷无该键，与基线一致不误报） */
const initialPayload = ref(null)
/** 键级差集：只含真正变化的配置项，既是"有无改动"的判据，也是提交载荷
 *  （后端 update() 为部分更新语义——只写请求体中出现且命中白名单的键，未传的键原样不动）
 *  值经同一 buildPayload 管线归一，避免数值/空格伪差异；掩码 apiKey 未改时该键不在两侧，不误报 */
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
/** 改动项数（键级，仅用于按钮与提示文案；与提交内容同一口径） */
const dirtyCount = computed(() =>
  Object.values(dirtyPayload.value).reduce((n, g) => n + Object.keys(g).length, 0))

// ===== 恢复本组默认 =====
const resettingKey = ref('')
const groupLabel = key => (anchors.find(a => a.key === key) || {}).label || key
const doResetGroup = async (key, label) => {
  resettingKey.value = key
  try {
    const r = await resetConfig([key])
    if (r.success) {
      const cnt = r.data && typeof r.data === 'object' ? Object.keys(r.data).length : 0
      message.success(`「${label}」已恢复默认（${cnt} 项）`)
      await fetchAndFill() // 回填 + 重建保存基线
    } else message.error(r.msg || '恢复失败')
  } catch (e) { message.error(e.message || '恢复失败') }
  finally { resettingKey.value = '' }
}
const onResetGroup = key => {
  const label = groupLabel(key)
  Modal.confirm({
    title: `恢复「${label}」为默认值？`,
    content: '该组当前的自定义值会被覆盖为出厂默认（模型 API Key 与向量模型组不受影响）。',
    okText: '恢复',
    cancelText: '取消',
    onOk: () => doResetGroup(key, label)
  })
}

const save = async () => {
  const payload = dirtyPayload.value
  if (!Object.keys(payload).length) { message.info('没有需要保存的改动'); return }
  saving.value = true
  try {
    // 只提交改动项：后端为部分更新语义，未改动的键不写库（也避免用当前值无谓重写）
    const r = await saveConfig(payload)
    if (r.success) {
      const n = r.data && typeof r.data === 'object' ? Object.keys(r.data).length : 0
      // N=0 即"假保存"哨兵：后端白名单未命中任何键时给出明确提示而非"已保存"误导
      if (n === 0) message.warning('没有可保存的配置项（后端未识别提交的键），请检查后重试')
      else { message.success(`配置已保存并生效（更新 ${n} 项）`); initialPayload.value = buildPayload() }
      // 若触发了向量模型切换，重嵌入任务已自动启动（状态轮询自动开启）
      refreshReembedStatus()
    }
    else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}
</script>

<style scoped>
/* 模式开关条 */
.mode-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  padding: 8px 12px;
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
}
.mode-label {
  font-size: 13px;
  font-weight: 500;
}
.mode-hint {
  font-size: 12px;
  color: #8c8c8c;
}
.tip-icon {
  color: #bbb;
  font-size: 12px;
  margin-left: 4px;
  cursor: help;
}
.tip-icon:hover {
  color: #1677ff;
}
/* 核心参数标记：前置琥珀色小圆点（轻量、可扫读；图例见锚点条右侧） */
.core-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #faad14;
  margin-right: 6px;
  vertical-align: middle;
}
/* 锚点条右侧「核心」图例 */
.anchor-legend {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  color: #999;
  font-size: 12px;
  white-space: nowrap;
}
/* 折叠面板：去掉卡片默认背景与边框，保持与页面一致的浅色观感 */
.cfg-collapse {
  background: transparent;
}
/* 分区锚点导航条：吸顶常驻（滚动时保持可见，随时可跳任意分组） */
.cfg-anchor {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 6px;
  margin-bottom: 14px;
  padding: 8px 12px;
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  position: sticky;
  top: 0;
  z-index: 10;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}
.cfg-anchor a {
  font-size: 13px;
  color: #555;
  padding: 3px 10px;
  border-radius: 12px;
  text-decoration: none;
  transition: all 0.2s;
}
.cfg-anchor a:hover {
  color: #1677ff;
  background: #e6f4ff;
}
.cfg-anchor a.anchor-active {
  color: #fff;
  background: #1677ff;
}
.cfg-collapse :deep(.ant-collapse-item) {
  background: #fff;
  border-radius: 8px;
  margin-bottom: 12px;
  border: 1px solid #f0f0f0;
}
.cfg-collapse :deep(.ant-collapse-header) {
  font-weight: 500;
}
.dirty-tip {
  background: #fffbe6;
  border: 1px solid #ffe58f;
  color: #ad6800;
  border-radius: 6px;
  padding: 6px 12px;
  margin: 0 0 12px;
  font-size: 13px;
}
.cfg-sub {
  margin: 4px 0 10px;
  padding: 2px 0 2px 8px;
  border-left: 3px solid #1677ff;
  color: #4a5568;
  font-size: 12.5px;
  font-weight: 500;
  background: #f6f8fa;
  border-radius: 0 4px 4px 0;
}
.reset-group-btn {
  font-size: 12px;
  color: #8c8c8c;
  margin-right: 4px;
}
.reset-group-btn:hover {
  color: #d4380d !important;
}
/* 测试连接结果标记：紧凑小标签，hover 看详情 */
.probe-chip {
  margin-left: 8px;
  padding: 4px 6px;
  font-size: 12px;
  line-height: 1;
  border-radius: 3px;
  border: 1px solid transparent;
  cursor: default;
  white-space: nowrap;
}
.probe-ok {
  color: #389e0d;
  background: #f6ffed;
  border-color: #b7eb8f;
}
.probe-bad {
  color: #cf1322;
  background: #fff1f0;
  border-color: #ffa39e;
}
</style>
