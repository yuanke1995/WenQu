<template>
  <div class="pv-page">
    <div class="app-page-head">
      <h3 class="app-page-title">模型供应商</h3>
      <span class="head-hint-plain">OpenAI 兼容网关统一管理：新建供应商 → 拉取模型 → 按类型登记，供智能体/对话/设置页选用</span>
      <button class="app-btn" style="margin-left:auto" @click="openCreate">
        <plus-outlined /> 新建供应商
      </button>
    </div>

    <a-spin :spinning="loading">
      <div v-if="list.length" class="pv-grid">
        <div v-for="p in list" :key="p.id" class="app-card pv-card">
          <div class="pv-head">
            <ProviderIcon :icon="p.icon" :name="p.name" :size="34" />
            <div class="pv-title">
              <div class="pv-name">
                {{ p.name }}
                <a-tag v-if="!p.enabled" color="default" class="pv-disabled-tag">已停用</a-tag>
              </div>
              <div class="pv-url" :title="p.baseUrl">{{ p.baseUrl }}</div>
            </div>
            <a-switch :checked="p.enabled" size="small"
                      @change="v => onToggle(p, v)" />
          </div>
          <div class="pv-models">
            <a-tag v-for="t in typeChips(p)" :key="t.key" :color="t.color" class="pv-type-tag">
              {{ t.label }} {{ t.count }}
            </a-tag>
            <span v-if="!p.modelCount" class="pv-none">未登记模型</span>
          </div>
          <div class="pv-remark" v-if="p.remark">{{ p.remark }}</div>
          <div class="pv-actions">
            <button class="app-link-btn" @click="openModels(p)">
              <database-outlined /> 管理模型（{{ p.modelCount }}）
            </button>
            <button class="app-link-btn" @click="openEdit(p)">编辑</button>
            <a-popconfirm title="确定删除该供应商？其已登记的模型会一并删除。"
                          ok-text="删除" cancel-text="取消" @confirm="onDelete(p)">
              <button class="app-link-btn danger">删除</button>
            </a-popconfirm>
          </div>
        </div>
      </div>
      <div v-else-if="!loading" class="app-card pv-empty">
        <div class="pv-empty-title">还没有供应商</div>
        <div class="pv-empty-desc">新建一个 OpenAI 兼容网关（DeepSeek / 智谱GLM / 通义百炼 / Kimi / Ollama 本地服务等），登记 API Key 后即可远程拉取模型列表。</div>
      </div>
    </a-spin>

    <!-- 新建/编辑供应商 -->
    <a-modal v-model:open="showEdit" :title="editing ? '编辑供应商' : '新建供应商'"
             :confirm-loading="saving" :width="560" @ok="save" @cancelled="showEdit = false">
      <a-form :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" :maxlength="100" placeholder="如：DeepSeek / 智谱GLM / 本地 Ollama" />
        </a-form-item>
        <a-form-item label="图标">
          <div class="pv-icon-picker">
            <button v-for="k in iconKeys" :key="k.key" type="button"
                    class="pv-icon-cell" :class="{ active: form.icon === k.key }"
                    :title="k.name" @click="onPickIcon(k.key)">
              <ProviderIcon :icon="k.key" :name="form.name || k.name" :size="20" />
            </button>
          </div>
          <div class="pv-hint">留空自动按名称生成徽标；点上方图标选内置品牌；没有的品牌可把官方 logo 图放到图床，贴图片 URL。</div>
          <a-input v-model:value="iconUrl" placeholder="自定义图片 URL（可留空，http(s)://…）" style="margin-top:6px" />
        </a-form-item>
        <a-form-item label="网关地址" required>
          <a-input v-model:value="form.baseUrl" placeholder="如 https://api.deepseek.com；…/v1、…/v4 等版本尾缀可自动识别" />
        </a-form-item>
        <a-form-item label="API Key">
          <a-input-password v-model:value="form.apiKey" autocomplete="new-password"
                            :placeholder="editing ? '未修改时显示掩码，无需重新输入（RSA 加密入库）' : '部分本地服务（Ollama 等）可留空'" />
        </a-form-item>
        <a-collapse ghost class="pv-advanced">
          <a-collapse-panel key="adv" header="高级（路径覆盖，一般留空自动识别）">
            <a-form-item label="补全路径" :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }">
              <a-input v-model:value="form.completionsPath" placeholder="默认 /v1/chat/completions；智谱 /v4、方舟 /v3（留空自动识别）" />
            </a-form-item>
            <a-form-item label="向量路径" :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }">
              <a-input v-model:value="form.embeddingsPath" placeholder="默认 /v1/embeddings（留空自动识别）" />
            </a-form-item>
          </a-collapse-panel>
        </a-collapse>
        <a-form-item label="备注">
          <a-input v-model:value="form.remark" :maxlength="255" placeholder="用途/计费说明等" />
        </a-form-item>
        <a-form-item label=" " :colon="false">
          <button class="app-link-btn" type="button" :disabled="testing" @click="onTest">
            {{ testing ? '测试中…' : '测试连接（拉取模型列表）' }}
          </button>
          <span v-if="testResult" :class="testResult.ok ? 'pv-test-ok' : 'pv-test-err'">{{ testResult.text }}</span>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 模型管理 -->
    <a-modal v-model:open="showModels" :title="`管理模型 — ${modelProvider ? modelProvider.name : ''}`"
             :width="680" :footer="null" @cancelled="showModels = false">
      <div class="pv-models-toolbar">
        <button class="app-btn" :disabled="fetching" @click="onFetch">
          <cloud-download-outlined /> {{ fetching ? '拉取中…' : '从服务拉取' }}
        </button>
        <button class="app-btn" @click="addManualRow">手动添加</button>
        <span class="pv-hint" style="margin-left:auto">类型可改（聊天/视觉/向量/重排/其他）；保存后生效</span>
      </div>

      <!-- 拉取候选：搜索 + 按行添加（类型在下方表格可改，无需勾选） -->
      <div v-if="candidates.length" class="pv-candidates">
        <div class="pv-candidates-head">
          <a-input v-model:value="candidateKeyword" size="small" allow-clear
                   placeholder="搜索模型名" class="pv-candidate-search" />
          <span class="pv-hint">{{ filteredCandidates.length }} / {{ candidates.length }}</span>
          <button class="app-link-btn" :disabled="!filteredCandidates.length" @click="importAllCandidates">
            全部导入（{{ filteredCandidates.length }}）
          </button>
          <button class="app-link-btn" @click="candidates = []">收起</button>
        </div>
        <div class="pv-candidate-list">
          <div v-for="c in filteredCandidates" :key="c.modelId" class="pv-candidate">
            <span class="pv-candidate-name" :title="c.modelId">{{ c.modelId }}</span>
            <a-tag :color="TYPE_META[c.guessedType]?.color" class="pv-type-tag">{{ TYPE_META[c.guessedType]?.label }}</a-tag>
            <a-tag v-if="c.exists" class="pv-type-tag" color="default">已登记</a-tag>
            <button class="app-link-btn" :disabled="c.exists || inModels(c.modelId)" @click="addCandidate(c)">
              {{ c.exists || inModels(c.modelId) ? '已加入' : '添加' }}
            </button>
          </div>
          <div v-if="!filteredCandidates.length" class="pv-hint" style="padding:8px 2px">无匹配模型</div>
        </div>
      </div>

      <!-- 已登记模型 -->
      <a-table :columns="modelCols" :data-source="models" row-key="rowKey" size="small" :pagination="false">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'modelId'">
            <a-input v-model:value="record.modelId" size="small" placeholder="模型名（调用 API 原样透传）"
                     :disabled="!record.isNew" style="min-width:200px" @change="clearTestState(record)" />
          </template>
          <template v-else-if="column.key === 'displayName'">
            <a-input v-model:value="record.displayName" size="small" placeholder="默认同模型名" />
          </template>
          <template v-else-if="column.key === 'modelType'">
            <a-select v-model:value="record.modelType" size="small" :options="typeOptions" style="width:100px"
                      @change="clearTestState(record)" />
          </template>
          <template v-else-if="column.key === 'enabled'">
            <a-switch v-model:checked="record.enabled" size="small" />
          </template>
          <template v-else-if="column.key === 'action'">
            <div class="pv-row-actions">
              <!-- 测试按钮即状态：测完原位变为「可达 Nms」（绿）/「失败」（红），悬浮看详情，再点重测 -->
              <a-tooltip :title="testState(record).text || '发一次最小真实调用验证可达性'">
                <button class="app-link-btn" :class="testState(record).ok === true ? 't-ok' : (testState(record).ok === false ? 't-bad' : '')"
                        :disabled="testState(record).loading" @click="doTestModel(record)">
                  {{ testState(record).loading ? '测试中…'
                     : (testState(record).ok === true ? '可达 ' + testState(record).latencyMs + 'ms'
                     : (testState(record).ok === false ? '失败' : '测试')) }}
                </button>
              </a-tooltip>
              <!-- 未点保存前移除都可恢复（关闭弹窗即放弃），无需确认 -->
              <button class="app-link-btn danger" @click="removeRow(record)">移除</button>
            </div>
          </template>
        </template>
      </a-table>

      <div class="pv-models-footer">
        <button class="app-btn primary" :disabled="savingModels" @click="saveModels">
          {{ savingModels ? '保存中…' : `保存（${models.filter(m => m.modelId && m.modelId.trim() && m.enabled !== false).length} 个启用）` }}
        </button>
        <span class="pv-hint">未登记启用的模型不会出现在各处模型选择器中</span>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined, DatabaseOutlined, CloudDownloadOutlined } from '@ant-design/icons-vue'
import ProviderIcon from '../components/ProviderIcon.vue'
import { BRAND_PATHS, BRAND_BADGES } from '../assets/providerIcons.js'
import {
  listProviders, createProvider, updateProvider, setProviderEnabled, deleteProvider,
  listProviderModels, saveProviderModels, fetchProviderModels, testProvider
} from '../api'

const list = ref([])
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const testResult = ref(null)

// ---- 编辑弹窗 ----
const showEdit = ref(false)
const editing = ref(null)
const form = ref(blank())

function blank() {
  return { name: '', icon: '', baseUrl: '', apiKey: '', completionsPath: '', embeddingsPath: '', remark: '' }
}

// 图标候选：官方 SVG + 品牌徽标（'custom' 后端迁移兜底值按名称自动生成，等价留空）
const iconKeys = computed(() => {
  const keys = [{ key: '', name: '自动（按名称）' }]
  for (const k of Object.keys(BRAND_PATHS)) keys.push({ key: k, name: k })
  for (const [k, b] of Object.entries(BRAND_BADGES)) keys.push({ key: k, name: b.name || k })
  return keys
})

// 内置厂商预设：选中图标后自动填充网关地址（版本尾缀写在地址里，后端 normalize 自动识别；
// gemini 的 OpenAI 兼容层路径特殊，显式给 completions/embeddings 路径）
const BRAND_PRESETS = {
  deepseek:    { name: 'DeepSeek',    baseUrl: 'https://api.deepseek.com' },
  zhipu:       { name: '智谱GLM',     baseUrl: 'https://open.bigmodel.cn/api/paas/v4' },
  qwen:        { name: '通义千问',     baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1' },
  moonshot:    { name: 'Kimi',        baseUrl: 'https://api.moonshot.cn' },
  doubao:      { name: '豆包',         baseUrl: 'https://ark.cn-beijing.volces.com/api/v3' },
  hunyuan:     { name: '腾讯混元',     baseUrl: 'https://api.hunyuan.cloud.tencent.com' },
  qianfan:     { name: '百度千帆',     baseUrl: 'https://qianfan.baidubce.com/v2' },
  minimax:     { name: 'MiniMax',     baseUrl: 'https://api.minimax.chat' },
  siliconflow: { name: 'SiliconFlow', baseUrl: 'https://api.siliconflow.cn' },
  openai:      { name: 'OpenAI',      baseUrl: 'https://api.openai.com' },
  openrouter:  { name: 'OpenRouter',  baseUrl: 'https://openrouter.ai/api/v1' },
  ollama:      { name: '本地 Ollama',  baseUrl: 'http://localhost:11434' },
  gemini:      { name: 'Gemini',      baseUrl: 'https://generativelanguage.googleapis.com',
                 completionsPath: '/v1beta/openai/chat/completions',
                 embeddingsPath: '/v1beta/openai/embeddings' },
}
// 判断当前地址是否为某个预设值（选过预设后又换品牌时应覆盖；手填的自定义地址不覆盖）
const KNOWN_PRESET_URLS = new Set(Object.values(BRAND_PRESETS).map(p => p.baseUrl.replace(/\/+$/, '')))

const onPickIcon = key => {
  form.value.icon = key
  const preset = BRAND_PRESETS[key]
  if (!preset) return
  // 名称：留空才填，不覆盖用户输入
  if (!form.value.name || !form.value.name.trim()) {
    form.value.name = preset.name
  }
  // 地址：为空、或当前值本身是预设地址时才覆盖；手填的自定义地址保持不动
  const cur = (form.value.baseUrl || '').trim().replace(/\/+$/, '')
  if (!cur || KNOWN_PRESET_URLS.has(cur)) {
    form.value.baseUrl = preset.baseUrl
    form.value.completionsPath = preset.completionsPath || ''
    form.value.embeddingsPath = preset.embeddingsPath || ''
  }
}

// 图标输入框只承载自定义图片 URL：内置品牌 key 由上方图标格选择（form.icon 统一存 key 或 URL），
// 否则 key 原样显示在文本框里会被误认成第二个名称
const iconUrl = computed({
  get: () => (/^https?:\/\//i.test(form.value.icon || '') ? form.value.icon : ''),
  set: v => { form.value.icon = (v || '').trim() }
})

const typeOptions = [
  { label: '聊天', value: 'chat' },
  { label: '视觉', value: 'vision' },
  { label: '向量', value: 'embedding' },
  { label: '重排', value: 'rerank' },
  { label: '其他', value: 'other' },
]

const TYPE_META = {
  chat: { label: '聊天', color: 'blue' },
  vision: { label: '视觉', color: 'geekblue' },
  embedding: { label: '向量', color: 'purple' },
  rerank: { label: '重排', color: 'cyan' },
  other: { label: '其他', color: 'default' },
}

function typeChips(p) {
  const counts = p.typeCounts || {}
  return Object.keys(TYPE_META)
    .filter(k => counts[k])
    .map(k => ({ key: k, ...TYPE_META[k], count: counts[k] }))
}

const load = async () => {
  loading.value = true
  try {
    const r = await listProviders()
    list.value = (r && r.data) || []
  } catch (e) {
    message.error(e.message || '供应商列表加载失败')
  } finally {
    loading.value = false
  }
}

const openCreate = () => {
  editing.value = null
  form.value = blank()
  testResult.value = null
  showEdit.value = true
}

const openEdit = p => {
  editing.value = p
  form.value = {
    name: p.name || '',
    icon: p.icon || '',
    baseUrl: p.baseUrl || '',
    apiKey: p.apiKeyMasked || '',
    completionsPath: p.completionsPath || '',
    embeddingsPath: p.embeddingsPath || '',
    remark: p.remark || ''
  }
  testResult.value = null
  showEdit.value = true
}

const save = async () => {
  if (!form.value.name || !form.value.name.trim()) {
    message.warning('请填写供应商名称')
    return
  }
  if (!form.value.baseUrl || !form.value.baseUrl.trim()) {
    message.warning('请填写网关地址')
    return
  }
  saving.value = true
  try {
    const body = { ...form.value }
    if (editing.value) await updateProvider(editing.value.id, body)
    else await createProvider(body)
    message.success(editing.value ? '已保存' : '已创建')
    showEdit.value = false
    await load()
  } catch (e) {
    message.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

const onToggle = async (p, v) => {
  try {
    await setProviderEnabled(p.id, v)
    p.enabled = v
  } catch (e) {
    message.error(e.message || '操作失败')
  }
}

const onDelete = async p => {
  // 确认交互由行内 a-popconfirm 承担
  try {
    await deleteProvider(p.id)
    message.success('已删除')
    await load()
  } catch (e) {
    // 仍被引用时后端会给出具体引用位置
    message.warning(e.message || '删除失败')
  }
}

/**
 * 测试连接：优先拉一次 /v1/models（顺带验证地址与 Key）；
 * 网关未实现 /v1/models 时回退「最小补全/向量探测」——用该供应商第一个已登记模型真实调用一次。
 */
const onTest = async () => {
  if (!form.value.baseUrl || !form.value.baseUrl.trim()) {
    message.warning('请先填写网关地址')
    return
  }
  testing.value = true
  testResult.value = null
  try {
    const r = await fetchProviderModels(form.value.baseUrl.trim(), form.value.apiKey || '', editing.value?.id)
    const n = (r && r.data && r.data.length) || 0
    testResult.value = { ok: true, text: `连接成功，网关返回 ${n} 个模型` }
  } catch (e) {
    // /v1/models 不通：已保存的供应商改用模型级探测（第一个启用的模型，优先聊天类型）
    const firstModel = await firstRegisteredModel(editing.value?.id)
    if (firstModel) {
      try {
        const tr = await testProvider({
          providerId: editing.value.id,
          baseUrl: form.value.baseUrl.trim(),
          apiKey: '',
          model: firstModel.modelId,
          modelType: firstModel.modelType,
          path: firstModel.modelType === 'embedding'
            ? (form.value.embeddingsPath || editing.value.embeddingsPath || '')
            : (form.value.completionsPath || editing.value.completionsPath || '')
        })
        const d = (tr && tr.data) || {}
        testResult.value = d.available
          ? { ok: true, text: `连接成功（模型 ${firstModel.modelId} 探测可达 ${d.latencyMs}ms）` }
          : { ok: false, text: `网关已连通，但模型 ${firstModel.modelId} 探测失败：${d.detail || '（无详情）'}` }
      } catch (e2) {
        testResult.value = { ok: false, text: e2.message || '测试失败' }
      }
    } else {
      testResult.value = {
        ok: false,
        text: (e.message || '连接失败') + '；网关可能未实现 /v1/models——新供应商可先保存，在「管理模型」里手动添加模型后用行内「测试」探测'
      }
    }
  } finally {
    testing.value = false
  }
}

/** 取供应商第一个启用模型（优先 chat 类型），供 /v1/models 不通时的探测回退 */
const firstRegisteredModel = async providerId => {
  if (!providerId) return null
  try {
    const r = await listProviderModels(providerId)
    const ms = ((r && r.data) || []).filter(m => m.enabled !== false)
    return ms.find(m => m.modelType === 'chat') || ms[0] || null
  } catch (e) {
    return null
  }
}

// ---- 模型管理弹窗 ----
const showModels = ref(false)
const modelProvider = ref(null)
const models = ref([])
const candidates = ref([])
const candidateKeyword = ref('')
const fetching = ref(false)
const savingModels = ref(false)
let rowSeq = 0

const modelCols = [
  { title: '模型名', key: 'modelId' },
  { title: '展示名', key: 'displayName', width: 140 },
  { title: '类型', key: 'modelType', width: 100 },
  { title: '启用', key: 'enabled', width: 60 },
  { title: '操作', key: 'action', width: 150 }
]

/** 每行模型的连通性测试状态：rowKey → {loading, ok, latencyMs, text} */
const testStates = ref({})
const testState = record => testStates.value[record.rowKey] || { loading: false, ok: null, latencyMs: 0, text: '' }

/** 模型名/类型改动后旧测试结果失效，清除 */
const clearTestState = record => {
  if (testStates.value[record.rowKey]) {
    const next = { ...testStates.value }
    delete next[record.rowKey]
    testStates.value = next
  }
}

/** 单模型连通性测试：chat/vision 发最小补全、embedding 真实向量一次、rerank 探服务（后端分派） */
const doTestModel = async record => {
  const p = modelProvider.value
  if (!p || !record.modelId || !record.modelId.trim()) {
    message.warning('请先填写模型名')
    return
  }
  testStates.value = {
    ...testStates.value,
    [record.rowKey]: { loading: true, ok: null, latencyMs: 0, text: '' }
  }
  const key = record.rowKey
  const done = st => { testStates.value = { ...testStates.value, [key]: st } }
  try {
    const r = await testProvider({
      providerId: p.id,
      baseUrl: p.baseUrl,
      // apiKey 传空：后端用库中真实 Key（避免掩码误判）
      apiKey: '',
      model: record.modelId.trim(),
      modelType: record.modelType || 'chat',
      // embedding 探测需要向量路径，其余走补全路径
      path: record.modelType === 'embedding' ? (p.embeddingsPath || '') : (p.completionsPath || '')
    })
    const d = (r && r.data) || {}
    done({
      loading: false,
      ok: !!d.available,
      latencyMs: d.latencyMs ?? 0,
      text: d.available ? `可用（${d.detail || ''}）` : (d.detail || '探测失败')
    })
  } catch (e) {
    done({ loading: false, ok: false, latencyMs: 0, text: e.message || '测试失败' })
  }
}

/** 候选按关键字过滤（不区分大小写） */
const filteredCandidates = computed(() => {
  const kw = (candidateKeyword.value || '').trim().toLowerCase()
  if (!kw) return candidates.value
  return candidates.value.filter(c => c.modelId.toLowerCase().includes(kw))
})

const inModels = modelId =>
  models.value.some(m => m.modelId === modelId)

const openModels = async p => {
  modelProvider.value = p
  candidates.value = []
  candidateKeyword.value = ''
  showModels.value = true
  try {
    const r = await listProviderModels(p.id)
    models.value = ((r && r.data) || []).map(m => ({
      rowKey: 'db-' + m.id, id: m.id,
      modelId: m.modelId, displayName: m.displayName || '',
      modelType: m.modelType || 'chat', enabled: m.enabled !== false, isNew: false
    }))
  } catch (e) {
    message.error(e.message || '模型列表加载失败')
    models.value = []
  }
}

const onFetch = async () => {
  fetching.value = true
  try {
    // 已存供应商：apiKey 传空，由后端用库中真实 Key 解密后拉取
    const r = await fetchProviderModels(modelProvider.value.baseUrl, '', modelProvider.value.id)
    candidates.value = ((r && r.data) || []).map(c => ({
      modelId: c.modelId, guessedType: c.guessedType || 'chat', exists: !!c.exists
    }))
    candidateKeyword.value = ''
    if (!candidates.value.length) message.info('网关未返回任何模型')
  } catch (e) {
    message.warning(e.message || '拉取失败（网关可能未实现 /v1/models，可手动添加）')
  } finally {
    fetching.value = false
  }
}

/** 候选加入待保存列表（类型用后端自动分类结果，加入后可在下方表格修改） */
const addCandidate = c => {
  if (inModels(c.modelId)) return
  models.value.push({
    rowKey: 'new-' + (++rowSeq), modelId: c.modelId, displayName: '',
    modelType: c.guessedType, enabled: true, isNew: true
  })
}

const importAllCandidates = () => {
  let added = 0
  for (const c of filteredCandidates.value) {
    if (inModels(c.modelId)) continue
    addCandidate(c)
    added++
  }
  message.success(added ? `已加入 ${added} 个待保存模型` : '没有可导入的新模型')
}

const addManualRow = () => {
  models.value.push({
    rowKey: 'new-' + (++rowSeq), modelId: '', displayName: '',
    modelType: 'chat', enabled: true, isNew: true
  })
}

const removeRow = async record => {
  // 未点「保存」前移除都可恢复（关闭弹窗即放弃改动），无需确认
  models.value = models.value.filter(m => m.rowKey !== record.rowKey)
}

const saveModels = async () => {
  const payload = models.value
    .filter(m => m.modelId && m.modelId.trim())
    .map(m => ({
      modelId: m.modelId.trim(),
      displayName: (m.displayName || '').trim() || null,
      modelType: m.modelType || 'chat',
      enabled: m.enabled !== false
    }))
  savingModels.value = true
  try {
    await saveProviderModels(modelProvider.value.id, payload)
    message.success('模型已保存')
    showModels.value = false
    await load()
  } catch (e) {
    message.error(e.message || '保存失败')
  } finally {
    savingModels.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.pv-page { padding: 4px 2px; }
.pv-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
}
.pv-card { padding: 14px 16px; }
.pv-head { display: flex; align-items: center; gap: 12px; }
.pv-title { flex: 1; min-width: 0; }
.pv-name { font-weight: 600; display: flex; align-items: center; gap: 6px; }
.pv-disabled-tag { font-size: 11px; line-height: 16px; }
.pv-url {
  font-size: 12px; color: var(--app-text3, #999);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.pv-models { margin-top: 10px; display: flex; flex-wrap: wrap; gap: 4px; }
.pv-type-tag { font-size: 11px; line-height: 18px; }
.pv-none { color: var(--app-text3, #bbb); font-size: 12px; }
.pv-remark { margin-top: 6px; font-size: 12px; color: var(--app-text3, #999); }
.pv-actions { margin-top: 10px; display: flex; gap: 4px; }
.pv-empty { text-align: center; padding: 40px 20px; }
.pv-empty-title { font-weight: 600; margin-bottom: 6px; }
.pv-empty-desc { color: var(--app-text3, #999); font-size: 13px; max-width: 520px; margin: 0 auto; }
.pv-hint { font-size: 12px; color: var(--app-text3, #999); }
.pv-icon-picker {
  display: flex; flex-wrap: wrap; gap: 4px;
  max-height: 132px; overflow-y: auto; padding: 2px;
}
.pv-icon-cell {
  width: 30px; height: 30px; border-radius: 6px;
  border: 1px solid transparent; background: transparent;
  display: inline-flex; align-items: center; justify-content: center;
  cursor: pointer;
}
.pv-icon-cell:hover { background: #f0f2f5; }
.pv-icon-cell.active { border-color: #1677ff; background: #e6f4ff; }
.pv-test-ok { color: var(--app-ok, #52c41a); font-size: 12px; margin-left: 8px; }
.pv-test-err { color: #e64340; font-size: 12px; margin-left: 8px; }
.pv-row-actions { display: flex; align-items: center; gap: 10px; }
.app-link-btn.t-ok { color: var(--app-ok, #52c41a); }
.app-link-btn.t-bad { color: #e64340; }
.pv-probe-chip.ok { color: var(--app-ok, #52c41a); background: #f6ffed; }
.pv-probe-chip.bad { color: #e64340; background: #fff1f0; }
.pv-advanced { margin: 0 0 8px; }
.pv-models-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.pv-candidates {
  border: 1px solid #f0f0f0; border-radius: 8px;
  padding: 8px 10px; margin-bottom: 10px;
}
.pv-candidates-head { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; font-size: 13px; }
.pv-candidate-search { width: 220px; }
.pv-candidate-list { max-height: 240px; overflow-y: auto; }
.pv-candidate {
  display: flex; align-items: center; gap: 8px;
  padding: 3px 2px; font-size: 13px;
}
.pv-candidate:hover { background: #fafafa; }
.pv-candidate-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pv-models-footer { margin-top: 12px; display: flex; align-items: center; gap: 10px; }
.app-btn.primary { background: #1677ff; color: #fff; }
</style>
