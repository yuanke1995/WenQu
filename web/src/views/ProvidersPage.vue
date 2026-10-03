<template>
  <!-- 页面骨架与「智能体 / 技能 / MCP 外部工具」Tab 完全一致：标题栏（页名 sr-only + 说明 + 主操作）+ 带内边距的内容区 -->
  <div class="app-page">
    <div class="app-page-head">
      <!-- 页名与 Tab 名重复，标题仅保留给读屏器（sr-only），视觉上从说明文字起头 -->
      <h1 class="app-page-title sr-only">模型供应商</h1>
      <span class="head-hint-plain">OpenAI 兼容网关统一管理：新建供应商 → 拉取模型 → 按类型登记。谁建归谁：你新建的供应商只有你能看到和使用</span>
      <button class="app-btn" style="margin-left:auto" @click="openCreate">
        <plus-outlined /> 新建供应商
      </button>
      <!-- 批量区（分隔线独立成区，不与常规按钮挤作一堆）。默认收起，点「批量管理」进入批量模式；
           开关放最右：进出模式自身位置不动；列表里的供应商都是自己可管理的，可勾选 -->
      <div v-if="list.length" class="batch-group">
        <template v-if="batchMode">
          <a-checkbox :checked="allChecked" :indeterminate="someChecked" @change="toggleAll">全选</a-checkbox>
          <button class="app-btn ghost small" :disabled="!selected.length || batchBusy" @click="doBatchEnabled(true)">启用</button>
          <button class="app-btn ghost small" :disabled="!selected.length || batchBusy" @click="doBatchEnabled(false)">停用</button>
          <button class="app-btn ghost small batch-del" :disabled="!selected.length || batchBusy" @click="doBatchDelete">删除</button>
        </template>
        <button class="app-btn ghost small" :class="{ 'batch-on': batchMode }" @click="toggleBatchMode">{{ batchMode ? '退出管理' : '批量管理' }}</button>
      </div>
    </div>

    <div class="app-page-body">
      <a-spin :spinning="loading">
        <div v-if="list.length" class="pv-grid">
          <div v-for="p in list" :key="p.id" class="app-card pv-card">
            <div class="pv-head">
              <a-checkbox v-if="batchMode && p.manageable" class="pv-check" :checked="selected.includes(p.id)"
                          @change="toggleSelect(p.id)" />
              <ProviderIcon :icon="p.icon" :name="p.name" :size="34" />
              <div class="pv-title">
                <div class="pv-name">
                  {{ p.name }}
                  <!-- 归属提示：只在看到别人的供应商时出现（管理员视角）；自己的不显示避免噪音 -->
                  <a-tag v-if="p.ownerUid && p.ownerUid !== me" color="default" class="pv-owner-tag">
                    归属 {{ p.ownerUid }}
                  </a-tag>
                  <a-tag v-if="!p.enabled" color="default" class="pv-disabled-tag">已停用</a-tag>
                </div>
                <div class="pv-url" :title="p.baseUrl">{{ p.baseUrl }}</div>
              </div>
              <a-switch v-if="p.manageable" :checked="p.enabled" size="small"
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
              <template v-if="p.manageable">
                <button class="app-link-btn" @click="openModels(p)">
                  <database-outlined /> 管理模型（{{ p.modelCount }}）
                </button>
                <button class="app-link-btn" @click="openEdit(p)">编辑</button>
                <a-popconfirm title="确定删除该供应商？其已登记的模型会一并删除。"
                              ok-text="删除" cancel-text="取消" @confirm="onDelete(p)">
                  <button class="app-link-btn danger">删除</button>
                </a-popconfirm>
              </template>
            </div>
          </div>
        </div>
        <div v-else-if="!loading" class="app-card pv-empty">
          <div class="pv-empty-title">还没有供应商</div>
          <div class="pv-empty-desc">新建一个 OpenAI 兼容网关（DeepSeek / 智谱GLM / 通义百炼 / Kimi / Ollama 自托管服务等），登记 API Key 后即可远程拉取模型列表。你自己建的供应商只有你能看到和使用。</div>
        </div>
      </a-spin>
    </div>

    <!-- 新建/编辑供应商 -->
    <a-modal v-model:open="showEdit" :title="editing ? '编辑供应商' : '新建供应商'"
             :confirm-loading="saving" :width="560" @ok="save" @cancelled="showEdit = false">
      <a-form :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" :maxlength="100" placeholder="如：DeepSeek / 智谱GLM / 自托管 Ollama" />
        </a-form-item>
        <a-form-item label="图标">
          <div class="pv-icon-picker">
            <button v-for="k in iconKeys" :key="k.key" type="button"
                    class="pv-icon-cell" :class="{ active: form.icon === k.key }"
                    :title="k.name" @click="onPickIcon(k.key)">
              <ProviderIcon :icon="k.key" :name="form.name || k.name" :tooltip="k.name" :size="20" />
            </button>
          </div>
          <div class="pv-hint">留空自动按名称生成徽标；点上方图标选内置品牌；没有的品牌可把官方 logo 图放到图床，贴图片 URL。</div>
          <a-input v-model:value="iconUrl" placeholder="自定义图片 URL（可留空，http(s)://…）" style="margin-top:6px" />
        </a-form-item>
        <a-form-item label="网关地址" required>
          <a-input v-model:value="form.baseUrl" placeholder="如 https://api.deepseek.com；…/v1、…/v4 等版本尾缀可自动识别" />
          <div v-if="form.icon === 'ollama' || /^https?:\/\/(localhost|127\.0\.0\.1)/.test(form.baseUrl || '')" class="pv-hint">
            地址由问渠服务器发起访问：localhost 指部署问渠的机器，不是你自己的电脑；Ollama 装在你自己电脑上时，请填它对服务器可达的地址（如 http://192.168.x.x:11434），并让 Ollama 监听 0.0.0.0。
          </div>
        </a-form-item>
        <a-form-item label="API Key">
          <a-input-password v-model:value="form.apiKey" autocomplete="new-password"
                            :placeholder="editing ? '未修改时显示掩码，无需重新输入（RSA 加密入库）' : '部分自托管服务（Ollama 等）可留空'" />
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
             :width="960" :footer="null" @cancelled="showModels = false">
      <div class="pv-models-toolbar">
        <button class="app-btn" :disabled="fetching" @click="onFetch">
          <cloud-download-outlined /> {{ fetching ? '拉取中…' : '从服务拉取' }}
        </button>
        <button class="app-btn" @click="addManualRow">手动添加</button>
        <span class="pv-hint" style="margin-left:auto">类型可改；窗口/输出按模型名自动预填（请核对官方值），留空用全局默认；保存后生效</span>
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
            <a-tag v-if="c.guessVisionCapable && c.guessedType === 'chat'" color="blue"
                   title="该模型名识别为「聊天+图片理解」一体，加入后图片理解自动预填为支持" class="pv-type-tag">图</a-tag>
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
                     :disabled="!record.isNew" style="min-width:150px" @change="onModelIdChange(record)" />
          </template>
          <template v-else-if="column.key === 'displayName'">
            <a-input v-model:value="record.displayName" size="small" placeholder="同模型名" />
          </template>
          <template v-else-if="column.key === 'modelType'">
            <a-select v-model:value="record.modelType" size="small" :options="typeOptions" style="width:100px"
                      @change="clearTestState(record)" />
          </template>
          <template v-else-if="column.key === 'visionCapable'">
            <!-- 图片理解三态（自动/支持/不支持）：「聊天+视觉」一体模型标支持后可直接选进视觉槽位，无需重复登记 -->
            <a-select v-model:value="record.visionCapable" size="small" :options="visionOptions"
                      style="width:100%" :disabled="!visionApplies(record)" />
          </template>
          <template v-else-if="column.key === 'thinking'">
            <a-select v-model:value="record.thinking" size="small" :options="thinkingOptions"
                      style="width:100%" :disabled="record.modelType !== 'chat'" />
          </template>
          <template v-else-if="column.key === 'ctx'">
            <!-- 上下文窗口/最大输出（模型固有属性声明，NULL=用全局默认）：上下文预算 = 窗口×安全系数−最大输出 -->
            <a-tooltip title="上下文窗口 / 最大输出 token（留空用全局默认值）。决定检索资料能塞多少：预算 = 窗口×安全系数−最大输出">
              <div class="pv-ctx-pair">
                <a-input-number v-model:value="record.contextWindow" size="small" :min="1" :step="1000"
                                placeholder="窗口" class="pv-ctx-num" />
                <span class="pv-ctx-slash">/</span>
                <a-input-number v-model:value="record.maxOutput" size="small" :min="1" :step="512"
                                placeholder="输出" class="pv-ctx-num" />
              </div>
            </a-tooltip>
          </template>
          <template v-else-if="column.key === 'enabled'">
            <a-switch v-model:checked="record.enabled" size="small" />
          </template>
          <template v-else-if="column.key === 'action'">
            <div class="pv-row-actions">
              <button class="app-link-btn" @click="openModelEdit(record)">编辑</button>
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

    <!-- 单模型高级配置：能力位 + 思考强度 + token 档位（表格只做速览与基础项，改这里） -->
    <a-modal v-model:open="editOpen" title="编辑模型" :width="560" :mask-closable="false" @ok="applyModelEdit">
      <template v-if="editForm">
        <div class="pv-edit-name">{{ editForm.modelId || '（未命名模型）' }}</div>

        <div class="pv-edit-section">能力</div>
        <div class="pv-edit-row">
          <span class="pv-edit-label">工具调用</span>
          <a-select v-model:value="editForm.toolCapable" size="small" :options="capOptions" style="width:132px" />
          <span class="pv-edit-hint">不支持时本轮不下发 Function Calling（部分网关收到 tools 会直接 400）</span>
        </div>
        <div class="pv-edit-row">
          <span class="pv-edit-label">图片输入</span>
          <a-select v-model:value="editForm.visionCapable" size="small" :options="capOptions" style="width:132px" />
          <span class="pv-edit-hint">支持时该模型也会出现在视觉模型选择器里，并可让聊天直接读图</span>
        </div>
        <div class="pv-edit-row">
          <span class="pv-edit-label">思考模式</span>
          <a-select v-model:value="editForm.thinking" size="small" :options="thinkingOptions" style="width:132px" />
          <span class="pv-edit-hint">思考强度在下方单独配置</span>
        </div>

        <div class="pv-edit-section">思考强度</div>
        <div class="pv-edit-row">
          <span class="pv-edit-label">默认强度</span>
          <a-select v-model:value="editForm.defaultReasoningLevel" size="small" style="width:180px"
                    :placeholder="'自动（用请求层默认值）'" allow-clear
                    :options="defaultLevelOptions" />
        </div>
        <div class="pv-edit-row pv-edit-row-top">
          <span class="pv-edit-label">支持档位</span>
          <div class="pv-level-grid">
            <a-checkbox v-for="lv in reasoningLevelOptions" :key="lv.value" :checked="editLevels.includes(lv.value)"
                        @change="e => toggleEditLevel(lv.value, e.target.checked)">
              {{ lv.label }}
            </a-checkbox>
          </div>
        </div>
        <div class="pv-edit-hint pv-edit-hint-block">
          勾选该模型真实支持的强度档位；留空表示只支持思考开关、不支持强度调节。默认强度须在勾选档位内。
          运行时按厂商方言映射下发（OpenAI reasoning_effort / Claude thinking.budget_tokens / Qwen thinking_budget）。
        </div>

        <div class="pv-edit-section">Token</div>
        <div class="pv-edit-row pv-edit-row-top">
          <div class="pv-edit-token">
            <span class="pv-edit-label">输入</span>
            <a-input-number v-model:value="editForm.contextWindow" size="small" :min="1" :step="1024"
                            placeholder="窗口" style="width:150px" />
            <div class="pv-quick">
              <button v-for="q in inputPresets" :key="q" class="app-link-btn" @click="editForm.contextWindow = q">{{ fmtK(q) }}</button>
            </div>
          </div>
        </div>
        <div class="pv-edit-row pv-edit-row-top">
          <div class="pv-edit-token">
            <span class="pv-edit-label">输出</span>
            <a-input-number v-model:value="editForm.maxOutput" size="small" :min="1" :step="1024"
                            placeholder="输出" style="width:150px" />
            <div class="pv-quick">
              <button v-for="q in outputPresets" :key="q" class="app-link-btn" @click="editForm.maxOutput = q">{{ fmtK(q) }}</button>
            </div>
          </div>
        </div>
        <div class="pv-edit-hint pv-edit-hint-block">
          留空用全局默认。上下文预算 = 窗口×安全系数−输出，输出必须小于预算，否则该模型下检索资料无法填入。
        </div>
      </template>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { PlusOutlined, DatabaseOutlined, CloudDownloadOutlined } from '@ant-design/icons-vue'
import ProviderIcon from '../components/ProviderIcon.vue'
import { BRAND_PATHS, BRAND_BADGES } from '../assets/providerIcons.js'
import { authUser } from '../utils/auth'
import { refreshSetupGuide } from '../utils/setupGuide'
import {
  listProviders, createProvider, updateProvider, setProviderEnabled, deleteProvider,
  listProviderModels, saveProviderModels, fetchProviderModels, testProvider,
  batchDeleteProviders, batchSetProvidersEnabled
} from '../api'

const route = useRoute()
const router = useRouter()

const list = ref([])
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const testResult = ref(null)

// 当前登录人 uid（归属提示只用它判断「这是不是别人的供应商」）
const me = computed(() => authUser.value?.user || '')

// ---- 批量操作：列表里的供应商都是自己可管理的，可勾选 ----
const selected = ref([])
const batchBusy = ref(false)
// 批量模式默认关闭：卡片不显示勾选框，点「批量管理」才进入（退出即清空勾选）
const batchMode = ref(false)
const toggleBatchMode = () => {
  batchMode.value = !batchMode.value
  if (!batchMode.value) selected.value = []
}
const selectableIds = computed(() => list.value.filter(p => p.manageable).map(p => p.id))
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
    message.success(`已${verb} ${okCount} 个供应商`)
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
    const r = await batchSetProvidersEnabled([...selected.value], on)
    reportBatch(r, on ? '启用' : '停用')
    await load()
  } catch (e) {
    message.error((on ? '批量启用' : '批量停用') + '失败：' + (e.message || ''))
  } finally {
    batchBusy.value = false
  }
}

const doBatchDelete = () => {
  Modal.confirm({
    title: `删除选中的 ${selected.value.length} 个供应商？`,
    content: '供应商及其已登记的模型会一并删除；仍被引用的条目会失败并逐条给出原因。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      batchBusy.value = true
      try {
        const r = await batchDeleteProviders([...selected.value])
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

// ---- 编辑弹窗 ----
const showEdit = ref(false)
const editing = ref(null)
const form = ref(blank())

function blank() {
  return { name: '', icon: '', baseUrl: '', apiKey: '', completionsPath: '', embeddingsPath: '', remark: '' }
}

// 图标候选：官方 SVG + 品牌徽标（'custom' 后端迁移兜底值按名称自动生成，等价留空）
// 悬浮提示用候选自己的名字（ICON_LABELS/预设里的友好名），不跟表单已填的供应商名走
const ICON_LABELS = {
  anthropic: 'Anthropic',
  googlegemini: 'Gemini',
  alibabacloud: '阿里云',
  huggingface: 'Hugging Face',
  vllm: 'vLLM',
}
const iconKeys = computed(() => {
  const label = k => BRAND_PRESETS[k]?.name || ICON_LABELS[k] || BRAND_BADGES[k]?.name || k
  const keys = [{ key: '', name: '自动（按名称）' }]
  for (const k of Object.keys(BRAND_PATHS)) keys.push({ key: k, name: label(k) })
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
  ollama:      { name: 'Ollama（自托管）', baseUrl: 'http://localhost:11434' },
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
  { label: 'OCR 专用', value: 'ocr' },
  { label: '向量', value: 'embedding' },
  { label: '重排', value: 'rerank' },
  { label: '语音', value: 'audio' },
  { label: '全模态', value: 'omni' },
  { label: '其他', value: 'other' },
]

const TYPE_META = {
  chat: { label: '聊天', color: 'blue' },
  vision: { label: '视觉', color: 'geekblue' },
  ocr: { label: 'OCR', color: 'volcano' },
  embedding: { label: '向量', color: 'purple' },
  rerank: { label: '重排', color: 'cyan' },
  audio: { label: '语音', color: 'orange' },
  omni: { label: '全模态', color: 'gold' },
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
    // 勾选与现存列表对账：已被删掉的 id 从选中集合里清掉（避免批量操作撞「不存在」）
    const alive = new Set(list.value.map(x => x.id))
    selected.value = selected.value.filter(id => alive.has(id))
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
    let r
    if (editing.value) r = await updateProvider(editing.value.id, body)
    else r = await createProvider(body)
    message.success(editing.value ? '已保存' : '已创建')
    showEdit.value = false
    await load()
    refreshSetupGuide(true)
    // 新建供应商一气呵成：接口返回 Provider 实体（r.data.id）时自动打开其「管理模型」弹窗，
    // 「新建 → 拉取模型 → 保存」不断链，减少小白找入口的断点
    const newId = !editing.value && r && r.data && r.data.id
    if (newId) {
      const created = list.value.find(p => p.id === newId)
      if (created) openModels(created)
    }
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
    refreshSetupGuide(true)  // 删掉模型后引导清单/tag 需要回升（对账）
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
  { title: '展示名', key: 'displayName', width: 120 },
  { title: '类型', key: 'modelType', width: 80 },
  { title: '图片理解', key: 'visionCapable', width: 84 },
  { title: '思考', key: 'thinking', width: 84 },
  { title: '窗口 / 输出', key: 'ctx', width: 156 },
  { title: '启用', key: 'enabled', width: 44 },
  { title: '操作', key: 'action', width: 178 }
]

// 思考能力选项（auto=按模型名判定）；仅对聊天模型有意义，其他类型存了也不生效
const thinkingOptions = [
  { label: '自动', value: 'auto' },
  { label: '不支持', value: 'none' },
  { label: '可开关', value: 'switchable' },
  { label: '恒思考', value: 'always' }
]

// 图片理解能力选项（auto=按类型与模型名自动判定）：「聊天+视觉」一体模型（gpt-4o/gemini/deepseek-vl 等）
// 标「支持」即可直接选进视觉模型槽位，无需再单独登记一个纯视觉模型
const visionOptions = [
  { label: '自动', value: 'auto' },
  { label: '支持', value: '1' },
  { label: '不支持', value: '0' }
]
/** 图片理解仅对话类类型有意义（向量/重排/OCR/语音没有图片理解可言），其余类型禁选并按自动保存 */
const visionApplies = record => ['chat', 'vision', 'omni'].includes(record.modelType)

// ==================== 单模型高级配置弹窗 ====================
const editOpen = ref(false)
/** 编辑中的行副本（改完写回原行，不直接动表格数据，取消即放弃） */
const editForm = ref(null)
const editRowKey = ref('')
/** 编辑副本的档位集合（勾选项，与 record.reasoningLevels 数组同步） */
const editLevels = ref([])

/** 能力位三态选项（工具调用/图片输入共用） */
const capOptions = [
  { label: '自动', value: 'auto' },
  { label: '支持', value: '1' },
  { label: '不支持', value: '0' }
]
/** 思考强度档位（由弱到强，与后端 REASONING_LEVEL_LIST 同序） */
const reasoningLevelOptions = [
  { label: '低', value: 'low' },
  { label: '中', value: 'medium' },
  { label: '高', value: 'high' },
  { label: '超高', value: 'xhigh' },
  { label: '极致', value: 'max' }
]
/** 默认强度下拉：只列已勾选档位（后端会校验默认档位须在支持档位内，前端先约束避免报错） */
const defaultLevelOptions = computed(() => {
  const picked = editLevels.value
  return picked.length
      ? reasoningLevelOptions.filter(l => picked.includes(l.value))
      : []
})
const inputPresets = [32768, 65536, 131072, 262144]
const outputPresets = [8192, 16384, 32768, 65536]
const fmtK = n => (n % 1024 === 0 ? `${n / 1024}K` : `${Math.round(n / 1024)}K`)

const openModelEdit = record => {
  editForm.value = {
    modelId: record.modelId,
    modelType: record.modelType,
    toolCapable: record.toolCapable || 'auto',
    visionCapable: record.visionCapable || 'auto',
    thinking: record.thinking || 'auto',
    defaultReasoningLevel: record.defaultReasoningLevel || undefined,
    contextWindow: record.contextWindow ?? null,
    maxOutput: record.maxOutput ?? null
  }
  editLevels.value = Array.isArray(record.reasoningLevels) ? [...record.reasoningLevels] : []
  editRowKey.value = record.rowKey
  editOpen.value = true
}

const toggleEditLevel = (lv, checked) => {
  const next = checked
      ? [...editLevels.value, lv].sort(
          (a, b) => reasoningLevelOptions.findIndex(x => x.value === a) - reasoningLevelOptions.findIndex(x => x.value === b))
      : editLevels.value.filter(x => x !== lv)
  editLevels.value = next
  // 默认强度必须落在支持档位内：取消勾选时同步清空，避免留下自相矛盾的配置
  if (editForm.value.defaultReasoningLevel && !next.includes(editForm.value.defaultReasoningLevel)) {
    editForm.value.defaultReasoningLevel = undefined
  }
}

const applyModelEdit = () => {
  const target = models.value.find(m => m.rowKey === editRowKey.value)
  if (target && editForm.value) {
    target.toolCapable = editForm.value.toolCapable
    target.visionCapable = editForm.value.visionCapable
    target.thinking = editForm.value.thinking
    target.contextWindow = editForm.value.contextWindow
    target.maxOutput = editForm.value.maxOutput
    target.reasoningLevels = [...editLevels.value]
    target.defaultReasoningLevel = editForm.value.defaultReasoningLevel || null
  }
  editOpen.value = false
}

/** 每行模型的连通性测试状态：rowKey → {loading, ok, latencyMs, text} */
const testStates = ref({})
const testState = record => testStates.value[record.rowKey] || { loading: false, ok: null, latencyMs: 0, text: '' }

// 常见模型家族的窗口/输出预填建议：纯表单便利（官方数值随版本会变，保存前请核对），运行时不读它，
// 留空/清空即回落全局默认——避免重蹈旧「模型窗口映射」写死数值过时变化石的覆辙
const MODEL_PRESET_HINTS = [
  { kw: 'deepseek', window: 65536, output: 8192 },
  { kw: 'qwen', window: 131072, output: 8192 },
  { kw: 'glm', window: 131072, output: 8192 },
  { kw: 'gpt', window: 128000, output: 16384 },
  { kw: 'claude', window: 200000, output: 8192 },
  { kw: 'gemini', window: 1048576, output: 65536 },
  { kw: 'kimi', window: 131072, output: 8192 },
  { kw: 'moonshot', window: 131072, output: 8192 }
]
const guessCtx = modelId => {
  const id = (modelId || '').toLowerCase()
  const hit = MODEL_PRESET_HINTS.find(p => id.includes(p.kw))
  return hit ? { contextWindow: hit.window, maxOutput: hit.output } : null
}

/** 新增行填写模型名后按家族预填窗口/输出（两项任一已填/手改过就不覆盖） */
const applyCtxPreset = record => {
  if (!record.isNew || record.contextWindow != null || record.maxOutput != null) return
  const g = guessCtx(record.modelId)
  if (g) Object.assign(record, g)
}

const onModelIdChange = record => {
  clearTestState(record)
  applyCtxPreset(record)
}

/** 模型名/类型改动后旧测试结果失效，清除 */
const clearTestState = record => {
  if (testStates.value[record.rowKey]) {
    const next = { ...testStates.value }
    delete next[record.rowKey]
    testStates.value = next
  }
}

/** 单模型连通性测试：chat/vision/omni 发最小补全、embedding 真实向量一次、rerank 探服务、audio 探网关可达（后端分派） */
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
      modelType: m.modelType || 'chat', thinking: m.thinking || 'auto',
      visionCapable: m.visionCapable == null ? 'auto' : String(m.visionCapable),
      toolCapable: m.toolCapable == null ? 'auto' : String(m.toolCapable),
      reasoningLevels: Array.isArray(m.reasoningLevels) ? [...m.reasoningLevels] : [],
      defaultReasoningLevel: m.defaultReasoningLevel || null,
      contextWindow: m.contextWindow ?? null, maxOutput: m.maxOutput ?? null,
      enabled: m.enabled !== false, isNew: false
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
      modelId: c.modelId, guessedType: c.guessedType || 'chat',
      guessVisionCapable: !!c.guessVisionCapable, exists: !!c.exists
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
  const row = {
    rowKey: 'new-' + (++rowSeq), modelId: c.modelId, displayName: '', thinking: 'auto',
    modelType: c.guessedType, visionCapable: c.guessVisionCapable ? '1' : 'auto',
    toolCapable: 'auto', reasoningLevels: [], defaultReasoningLevel: null,
    contextWindow: null, maxOutput: null, enabled: true, isNew: true
  }
  applyCtxPreset(row)
  models.value.push(row)
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
    rowKey: 'new-' + (++rowSeq), modelId: '', displayName: '', thinking: 'auto',
    modelType: 'chat', visionCapable: 'auto', toolCapable: 'auto',
    reasoningLevels: [], defaultReasoningLevel: null,
    contextWindow: null, maxOutput: null, enabled: true, isNew: true
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
      thinking: m.modelType === 'chat' ? (m.thinking || 'auto') : 'auto',
      // 非对话类类型归一为自动（禁选态下也可能残留旧值，保存时兜底）
      visionCapable: visionApplies(m) ? (m.visionCapable || 'auto') : 'auto',
      // 工具调用/思考强度仅对话类有意义
      toolCapable: visionApplies(m) ? (m.toolCapable || 'auto') : 'auto',
      reasoningLevels: visionApplies(m) ? (m.reasoningLevels || []) : [],
      defaultReasoningLevel: visionApplies(m) ? (m.defaultReasoningLevel || null) : null,
      contextWindow: m.contextWindow || null,
      maxOutput: m.maxOutput || null,
      enabled: m.enabled !== false
    }))
  savingModels.value = true
  try {
    await saveProviderModels(modelProvider.value.id, payload)
    message.success('模型已保存')
    showModels.value = false
    await load()
    refreshSetupGuide(true)  // 登记模型后立即对账引导状态（返回对话页 tag/清单即时正确）
  } catch (e) {
    message.error(e.message || '保存失败')
  } finally {
    savingModels.value = false
  }
}

/** 深链处理：/agents?tab=providers&action=new-provider（配置引导清单「去添加」的跳转目标）。
 *  无任何供应商 → 直接弹「新建供应商」；已有 → 打开第一个还没登记聊天模型的供应商的「管理模型」，
 *  全都登记过则兜底 openCreate()。处理完 replace 清掉 action：本页在 a-tabs
 *  destroy-inactive-tab-pane 下切 Tab 会重挂载，不清参数会反复自动弹窗。 */
const handleSetupAction = () => {
  if (route.query.action !== 'new-provider') return
  router.replace({ path: '/agents', query: { tab: 'providers' } })
  const target = list.value.find(p => !(p.typeCounts && p.typeCounts.chat))
  if (target) openModels(target)
  else openCreate()
}

onMounted(async () => {
  await load()
  handleSetupAction()
})
</script>

<style scoped>
/* 批量操作区（页头最右，分隔线与常规按钮划清界限）：开关恒在最右、进出模式位置不动 */
.batch-group { display: flex; align-items: center; gap: 8px; padding-left: 12px; border-left: 1px solid var(--app-border); }
.batch-on { color: var(--app-accent); border-color: var(--app-accent); }
.batch-del { color: var(--app-danger); }
.pv-check { flex: none; }
/* 窗口/输出双数字输入：等宽小输入框夹一个斜杠分隔符 */
.pv-ctx-pair { display: flex; align-items: center; gap: 4px; }
.pv-ctx-num { flex: 1; min-width: 0; }
.pv-ctx-slash { color: var(--app-text3); flex: none; }
/* 内边距/滚动由 .app-page-body 提供（与智能体/技能/MCP 同一套骨架），此处只放网格与卡片细节 */
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
.pv-owner-tag { font-size: 11px; line-height: 16px; }
.pv-url {
  font-size: 12px; color: var(--app-text3, var(--app-text3));
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.pv-models { margin-top: 10px; display: flex; flex-wrap: wrap; gap: 4px; }
.pv-type-tag { font-size: 11px; line-height: 18px; }
.pv-none { color: var(--app-text3, var(--app-text3)); font-size: 12px; }
.pv-remark { margin-top: 6px; font-size: 12px; color: var(--app-text3, var(--app-text3)); }
.pv-actions { margin-top: 10px; display: flex; gap: 4px; }
.pv-empty { text-align: center; padding: 40px 20px; }
.pv-empty-title { font-weight: 600; margin-bottom: 6px; }
.pv-empty-desc { color: var(--app-text3, var(--app-text3)); font-size: 13px; max-width: 520px; margin: 0 auto; }
.pv-hint { font-size: 12px; color: var(--app-text3, var(--app-text3)); }
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
.pv-icon-cell.active { border-color: var(--app-accent); background: #e6f4ff; }
.pv-test-ok { color: var(--app-ok, var(--app-ok)); font-size: 12px; margin-left: 8px; }
.pv-test-err { color: var(--app-danger); font-size: 12px; margin-left: 8px; }
.pv-row-actions { display: flex; align-items: center; gap: 10px; white-space: nowrap; }
.app-link-btn.t-ok { color: var(--app-ok, var(--app-ok)); }
.app-link-btn.t-bad { color: var(--app-danger); }
.pv-probe-chip.ok { color: var(--app-ok, var(--app-ok)); background: #f6ffed; }
.pv-probe-chip.bad { color: var(--app-danger); background: #fff1f0; }
.pv-advanced { margin: 0 0 8px; }
.pv-models-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.pv-candidates {
  border: 1px solid var(--app-code-inline-bg); border-radius: 8px;
  padding: 8px 10px; margin-bottom: 10px;
}
.pv-candidates-head { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; font-size: 13px; }
.pv-candidate-search { width: 220px; }
.pv-candidate-list { max-height: 240px; overflow-y: auto; }
.pv-candidate {
  display: flex; align-items: center; gap: 8px;
  padding: 3px 2px; font-size: 13px;
}
.pv-candidate:hover { background: var(--app-panel-2); }
.pv-candidate-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pv-models-footer { margin-top: 12px; display: flex; align-items: center; gap: 10px; }
.app-btn.primary { background: var(--app-accent); color: #fff; }

/* ==================== 单模型高级配置弹窗 ==================== */
.pv-edit-name {
  font-size: 15px; font-weight: 600; color: var(--app-text);
  padding-bottom: 10px; margin-bottom: 4px; border-bottom: 1px solid var(--app-border);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.pv-edit-section {
  font-size: 12px; color: var(--app-text3); margin: 16px 0 8px;
  padding-bottom: 6px; border-bottom: 1px solid var(--app-border);
}
.pv-edit-section:first-of-type { margin-top: 10px; }
.pv-edit-row { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
/* 多行内容（档位网格/token 组）顶部对齐，避免标签被拉高居中 */
.pv-edit-row-top { align-items: flex-start; }
.pv-edit-label { width: 60px; flex: none; font-size: 13px; color: var(--app-text2); }
.pv-edit-hint { font-size: 12px; color: var(--app-text3); line-height: 1.5; }
.pv-edit-hint-block { margin: -2px 0 12px 70px; }
.pv-level-grid {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px 12px; flex: 1;
}
.pv-edit-token { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.pv-quick { display: flex; align-items: center; gap: 8px; }
.pv-quick .app-link-btn {
  padding: 2px 8px; border: 1px solid var(--app-border); border-radius: 6px; font-size: 12px;
}
.pv-quick .app-link-btn:hover { border-color: var(--app-accent); color: var(--app-accent); }
</style>
