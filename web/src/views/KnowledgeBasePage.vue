<template>
  <div class="kb-page">
    <div class="app-page-head">
      <h3 class="app-page-title">知识库</h3>
      <span class="head-hint-plain">文档的容器：检索按库隔离，向量/检索/解析参数随库（留空继承全局模板）</span>
      <button class="app-btn" style="margin-left:auto" @click="openCreate">
        <plus-outlined /> 新建知识库
      </button>
    </div>

    <a-spin :spinning="loading">
      <div class="kb-cards">
        <div v-for="kb in list" :key="kb.id" class="app-card kb-card" @click="openDocs(kb)">
          <div class="kb-card-head">
            <database-outlined class="kb-card-ic" />
            <span class="kb-name">{{ kb.name }}</span>
            <a-tag v-if="kb.isDefault === 1" color="blue" style="margin-left:auto">默认</a-tag>
          </div>
          <p class="kb-card-desc" :title="kb.description || ''">{{ kb.description || '暂无描述' }}</p>
          <div class="kb-card-meta">
            <span :class="{ 'kb-zero': !kb.docCount }">{{ kb.docCount }} 个文档</span>
            <span class="kb-meta-sep">·</span>
            <span v-if="kb.embeddingRef" :title="kb.embeddingRef">{{ modelRefInfo(kb.embeddingRef)?.displayName || '自定义向量模型' }}</span>
            <span v-else class="kb-warn">未绑定向量模型</span>
            <span class="kb-meta-sep">·</span>
            <span v-if="kb.queryParams" class="kb-params" title="库级检索参数已覆盖全局">检索已自定义</span>
            <span v-if="kb.parseParams" class="kb-params" title="库级解析参数已覆盖全局">解析已自定义</span>
            <span v-if="!kb.queryParams && !kb.parseParams" class="kb-dim">继承全局参数</span>
          </div>
          <div class="kb-card-actions" @click.stop>
            <template v-if="isAdmin || kb.createdBy === myUid">
              <button class="app-link-btn" @click="openEdit(kb)">编辑</button>
              <button class="app-link-btn" @click="openGraph(kb)">图谱</button>
              <button class="app-link-btn danger" :disabled="kb.isDefault === 1" @click="onDelete(kb)">删除</button>
            </template>
            <button class="app-link-btn" style="margin-left:auto" @click="openDocs(kb)">文档管理 →</button>
          </div>
        </div>
        <div v-if="!loading && !list.length" class="kb-empty">还没有知识库，点右上角「新建知识库」创建</div>
      </div>
    </a-spin>

    <a-modal v-model:open="showEdit" :title="editing ? '编辑知识库' : '新建知识库'"
             :width="640" :confirm-loading="saving" @ok="save">
      <a-form :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" placeholder="如：操作手册库" />
        </a-form-item>
        <a-form-item label="描述">
          <a-input v-model:value="form.description" placeholder="这个库放什么资料" />
        </a-form-item>
        <a-form-item label="向量模型" required>
          <ModelSelect v-model="form.embeddingRef" type="embedding"
                       :width="320" :admin-tip-visible="true" />
          <div class="kb-hint">必选：本库文档按此模型向量化与检索（不同模型的向量空间不兼容，无法跨模型混用）；换模型会自动按库重嵌入，期间该库检索降级关键词路。</div>
        </a-form-item>

        <a-divider class="kb-divider" plain>检索参数（新建时按当前全局值预填；改成自己的值即独立保存，清空则跟随全局）</a-divider>
        <div class="kb-param-grid">
          <a-form-item label="向量权重" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
            <a-input-number v-model:value="form.q.vectorWeight" :min="0" :max="1" :step="0.05" style="width:100%" :placeholder="numPh('retrieval', 'vectorWeight')" />
          </a-form-item>
          <a-form-item label="关键词权重" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
            <a-input-number v-model:value="form.q.keywordWeight" :min="0" :max="1" :step="0.05" style="width:100%" :placeholder="numPh('retrieval', 'keywordWeight')" />
          </a-form-item>
          <a-form-item label="向量阈值" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
            <a-input-number v-model:value="form.q.vecThreshold" :min="0" :max="1" :step="0.05" style="width:100%" :placeholder="numPh('retrieval', 'vecThreshold')" />
          </a-form-item>
          <a-form-item label="向量召回上限" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
            <a-input-number v-model:value="form.q.vectorTopK" :min="1" :max="100" style="width:100%" :placeholder="numPh('retrieval', 'vectorTopK')" />
          </a-form-item>
          <a-form-item label="关键词召回上限" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
            <a-input-number v-model:value="form.q.keywordLimit" :min="1" style="width:100%" :placeholder="numPh('retrieval', 'keywordLimit')" />
          </a-form-item>
          <a-form-item label="重排" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
            <a-select v-model:value="form.q.rerankEnabled" style="width:100%" :options="triOptions" :placeholder="triPh('rerank', 'enabled')" allow-clear />
          </a-form-item>
          <a-form-item label="重排模型" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
            <ModelSelect v-model="form.q.rerankModel" type="rerank" width="100%" inherit-label="不重排" />
          </a-form-item>
        </div>

        <a-divider class="kb-divider" plain>解析参数（新建时按当前全局模板预填；仅对之后解析的文档生效）</a-divider>
        <div class="kb-param-grid">
          <a-form-item label="分块最大字符" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
            <a-input-number v-model:value="form.p.maxSize" :min="200" :step="100" style="width:100%" :placeholder="numPh('chunk', 'maxSize')" />
          </a-form-item>
          <a-form-item label="分块重叠字符" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
            <a-input-number v-model:value="form.p.overlap" :min="0" :step="20" style="width:100%" :placeholder="numPh('chunk', 'overlap')" />
          </a-form-item>
          <a-form-item label="最大知识块数" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
            <a-input-number v-model:value="form.p.maxChunks" :min="0" :step="100" style="width:100%" :placeholder="numPh('chunk', 'maxChunks')" />
          </a-form-item>
          <a-form-item label="最多提取图片" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
            <a-input-number v-model:value="form.p.maxImages" :min="0" :step="10" style="width:100%" :placeholder="numPh('chunk', 'maxImages')" />
          </a-form-item>
          <a-form-item label="结构感知切分" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
            <a-select v-model:value="form.p.structural" style="width:100%" :options="triOptions" :placeholder="triPh('chunk', 'structural')" allow-clear />
          </a-form-item>
          <a-form-item label="边界阈值比例" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
            <a-input-number v-model:value="form.p.structuralRatio" :min="0.5" :max="1" :step="0.05" style="width:100%" :placeholder="numPh('chunk', 'structuralRatio')" />
          </a-form-item>
          <a-form-item label="标题识别层级" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
            <a-input-number v-model:value="form.p.headingDepth" :min="1" :max="6" style="width:100%" :placeholder="numPh('chunk', 'headingDepth')" />
          </a-form-item>
          <a-form-item label="问答对增强" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
            <a-select v-model:value="form.p.qaEnabled" style="width:100%" :options="triOptions" :placeholder="triPh('parse', 'qaEnabled')" allow-clear />
          </a-form-item>
          <a-form-item label="每块问答对数" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
            <a-input-number v-model:value="form.p.qaPerChunk" :min="1" :max="5" :step="1" style="width:100%" :placeholder="numPh('parse', 'qaPerChunk')" />
          </a-form-item>
          <a-form-item label="父子分块" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
            <a-select v-model:value="form.p.childEnabled" style="width:100%" :options="triOptions" :placeholder="triPh('parse', 'childEnabled')" allow-clear />
          </a-form-item>
          <a-form-item label="子块尺寸(字符)" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
            <a-input-number v-model:value="form.p.childSize" :min="100" :max="2000" :step="100" style="width:100%" :placeholder="numPh('parse', 'childSize')" />
          </a-form-item>
          <a-form-item label="PDF 解析引擎" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
            <a-select v-model:value="form.p.ocrEngine" style="width:100%" :options="ocrEngineOptions" :placeholder="valPh('parse', 'ocrEngine')" allow-clear />
          </a-form-item>
          <a-form-item label="扫描件阈值(字符)" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
            <a-input-number v-model:value="form.p.ocrMinText" :min="0" :step="5" style="width:100%" :placeholder="numPh('parse', 'ocrMinText')" />
          </a-form-item>
          <a-form-item label="OCR 渲染 DPI" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
            <a-input-number v-model:value="form.p.ocrDpi" :min="72" :max="400" :step="8" style="width:100%" :placeholder="numPh('parse', 'ocrDpi')" />
          </a-form-item>
          <a-form-item label="图片描述模型" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
            <ModelSelect v-model="form.p.visionRef" type="vision" width="100%" inherit-label="不描述图片" />
          </a-form-item>
        </div>
        <div class="kb-hint" style="margin:4px 0 0">
          改解析参数后需重新解析文档才会生效（不自动重解析全库）；扫描件/图片型 PDF 的 OCR 依赖「图片描述模型」——
          <span class="kb-warn">未绑定时该类文档会解析失败</span>（不产出残缺内容）。
          纯文本文档建议「PDF 解析引擎」留空或用 none（秒级）；版面引擎（MinerU/PP）专为表格/版面还原，CPU 约 10s/页、
          <span class="kb-warn">大文档会明显变慢</span>。
        </div>

        <!-- 设默认库是全局动作（影响所有人的新建归属），仅管理员 -->
        <a-form-item v-if="isAdmin" label="设为默认库" style="margin-top:12px">
          <a-switch v-model:checked="form.isDefault" />
          <span class="kb-hint" style="margin-left:8px">新建文档默认归属</span>
        </a-form-item>

        <!-- P1 GraphRAG 库级开关（默认关）：开启后解析完成自动抽三元组，检索一跳图扩展 -->
        <a-form-item label="GraphRAG 知识图谱" class="kb-item-wrap-label" style="margin-top:4px">
          <a-switch v-model:checked="form.graphEnabled" />
          <div class="kb-hint" style="margin-top:4px">
            开启后新解析的文档自动抽「实体-关系」三元组，检索时一跳图扩展（跨文档多跳问答）。
            需在系统设置 → 定时维护 → GraphRAG 配置抽取模型；已有文档点列表页「构建图谱」回溯。
          </div>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 图谱浏览（图视图 / 列表 + 构建/状态） -->
    <a-modal v-model:open="graphModal" :title="`知识图谱 · ${graphKb?.name || ''}`" :footer="null" width="860px" @after-open="onGraphModalOpen">
      <div class="graph-toolbar">
        <a-radio-group v-model:value="graphView" size="small" button-style="solid" @change="onGraphViewChange">
          <a-radio-button value="graph">图视图</a-radio-button>
          <a-radio-button value="list">列表</a-radio-button>
        </a-radio-group>
        <a-button size="small" :loading="graphBuilding" :disabled="graphInfo.building" @click="doBuild">
          {{ graphInfo.building ? `构建中 ${graphInfo.done || 0}/${graphInfo.total || 0}` : '构建图谱（存量回溯）' }}
        </a-button>
        <span class="kb-hint">
          实体 {{ graphInfo.entities || 0 }} · 三元组 {{ graphInfo.triples || 0 }}
          <template v-if="graphInfo.building">（失败 {{ graphInfo.failed || 0 }}）</template>
        </span>
        <a-popconfirm title="清空该库全部图谱数据？（三元组/实体/抽取记录全删，可重新构建）" ok-text="清空" cancel-text="取消" @confirm="doClearGraph">
          <a-button size="small" danger>清空图谱</a-button>
        </a-popconfirm>
        <a-button size="small" @click="refreshGraph" style="margin-left:auto">刷新</a-button>
      </div>

      <!-- 图视图：力导向图（节点=实体、边=关系谓词；点节点 → 下方显示该实体的关系清单） -->
      <div v-show="graphView === 'graph'" class="graph-wrap">
        <div ref="graphChartEl" class="graph-canvas"></div>
        <div v-if="!graphInfo.triples" class="graph-empty">还没有三元组——先点「构建图谱」。</div>
        <div v-if="selectedEntity" class="graph-panel">
          <b>{{ selectedEntity }}</b> 的关系（{{ entityRelations.length }}）：
          <span v-for="(r, i) in entityRelations" :key="i" class="graph-rel">
            {{ r.source }} —【{{ r.value }}】→ {{ r.target }}
          </span>
          <button class="app-link-btn" style="margin-left:auto" @click="selectedEntity = null">收起</button>
        </div>
        <div v-else-if="graphInfo.triples" class="graph-note">点一个节点可在下方查看它的关系；拖动节点/空白平移、滚轮缩放。</div>
        <div v-else-if="graphInfo.triples > graphSampled" class="graph-note">图示展示前 {{ graphSampled }} 条关系（按提及度优先），完整清单请切「列表」。</div>
      </div>

      <!-- 列表视图：逐条核对（带来源文档） -->
      <div v-show="graphView === 'list'">
        <a-table :data-source="triples" :columns="tripleCols" size="small" row-key="id"
                 :loading="triplesLoading" :pagination="triplePagination"
                 :locale="{ emptyText: '还没有三元组（先构建图谱，或确认已开启开关并配置抽取模型）' }"
                 @change="onTripleTableChange" />
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { PlusOutlined, DatabaseOutlined } from '@ant-design/icons-vue'
import * as echarts from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
echarts.use([GraphChart, TooltipComponent, CanvasRenderer])
import { listKnowledgeBases, createKnowledgeBase, updateKnowledgeBase, deleteKnowledgeBase, getKbParamDefaults,
         graphBuild, graphStatus, graphTriples, graphClear } from '../api'
import ModelSelect from '../components/ModelSelect.vue'
import { loadModelIndex, modelRefInfo } from '../utils/modelRef'
import { isAdminSync, ensureAuth } from '../utils/auth'

// 权限：管理员全量；普通用户可自建自管自己的库，别人共享的库只读（后端按共享范围过滤返回）
const isAdmin = isAdminSync()
const myUid = ref('')

const router = useRouter()
const openDocs = kb => router.push(`/knowledge/${kb.id}/docs`)

const list = ref([])
const loading = ref(false)
const saving = ref(false)
const showEdit = ref(false)
const editing = ref(null)
const form = ref(blank())
const triOptions = [
  { value: 'true', label: '开' },
  { value: 'false', label: '关' }
]

/** 库级 PDF 解析引擎选项（清空=继承全局；速度差异见标注） */
const ocrEngineOptions = [
  { value: 'none', label: '纯文本层（快，秒级）' },
  { value: 'vision', label: '视觉模型 OCR（逐页识别）' },
  { value: 'pp_structure_v3', label: 'PP-StructureV3 版面还原' },
  { value: 'mineru', label: 'MinerU 版面还原（慢，约 10s/页）' }
]

function blank () {
  return {
    name: '', description: '', embeddingRef: '', isDefault: false, graphEnabled: false,
    q: { vectorWeight: null, keywordWeight: null, vecThreshold: null, vectorTopK: null, keywordLimit: null, rerankEnabled: null, rerankModel: '' },
    p: { maxSize: null, overlap: null, maxChunks: null, maxImages: null, structural: null, structuralRatio: null, headingDepth: null, qaEnabled: null, qaPerChunk: null, childEnabled: null, childSize: null, ocrEngine: null, ocrMinText: null, ocrDpi: null, visionRef: '' }
  }
}

/** 库级检索参数 ↔ 表单：JSON 键（retrieval.* / rerank.*）与表单字段互转，空值=继承不写入 */
const QUERY_KEYS = {
  vectorWeight: 'retrieval.vectorWeight', keywordWeight: 'retrieval.keywordWeight',
  vecThreshold: 'retrieval.vecThreshold', vectorTopK: 'retrieval.vectorTopK',
  keywordLimit: 'retrieval.keywordLimit', rerankEnabled: 'rerank.enabled', rerankModel: 'rerank.model'
}
const PARSE_KEYS = {
  maxSize: 'chunk.maxSize', overlap: 'chunk.overlap', maxChunks: 'chunk.maxChunks',
  maxImages: 'chunk.maxImages', structural: 'chunk.structural',
  structuralRatio: 'chunk.structuralRatio', headingDepth: 'chunk.headingDepth',
  qaEnabled: 'parse.qaEnabled', qaPerChunk: 'parse.qaPerChunk',
  childEnabled: 'parse.childEnabled', childSize: 'parse.childSize',
  ocrEngine: 'parse.ocrEngine', ocrMinText: 'parse.ocrMinText', ocrDpi: 'parse.ocrDpi'
}

function hydrateForm (row) {
  const f = blank()
  f.name = row?.name || ''
  f.description = row?.description || ''
  f.embeddingRef = row?.embeddingRef || ''
  f.isDefault = row?.isDefault === 1
  f.graphEnabled = row?.graphEnabled === 1
  let q = {}
  let p = {}
  try { q = row?.queryParams ? JSON.parse(row.queryParams) : {} } catch { q = {} }
  try { p = row?.parseParams ? JSON.parse(row.parseParams) : {} } catch { p = {} }
  for (const [field, key] of Object.entries(QUERY_KEYS)) {
    const v = q[key]
    if (v === undefined || v === '') continue
    // rerankEnabled 是 'true'/'false' 开关字符串（同解析参数的开关字段），走 Number 会得 NaN
    f.q[field] = (field === 'rerankModel' || field === 'rerankEnabled') ? String(v) : Number(v)
  }
  for (const [field, key] of Object.entries(PARSE_KEYS)) {
    const v = p[key]
    if (v === undefined || v === '') continue
    f.p[field] = (field === 'structural' || field === 'qaEnabled' || field === 'childEnabled' || field === 'ocrEngine') ? String(v) : Number(v)
  }
  if (p.visionRef) f.p.visionRef = p.visionRef
  return f
}

function serialize (f) {
  const q = {}
  for (const [field, key] of Object.entries(QUERY_KEYS)) {
    const v = f.q[field]
    if (v === null || v === undefined || v === '') continue
    q[key] = String(v)
  }
  // 选了重排模型但未显式设置开关时自动开启（否则模型配了也不会执行重排）
  if (q['rerank.model'] && q['rerank.enabled'] === undefined) q['rerank.enabled'] = 'true'
  const p = {}
  for (const [field, key] of Object.entries(PARSE_KEYS)) {
    const v = f.p[field]
    if (v === null || v === undefined || v === '') continue
    p[key] = String(v)
  }
  if (f.p.visionRef) p.visionRef = f.p.visionRef
  return {
    queryParams: Object.keys(q).length ? JSON.stringify(q) : null,
    parseParams: Object.keys(p).length ? JSON.stringify(p) : null
  }
}

const load = async () => {
  loading.value = true
  try {
    loadModelIndex().catch(() => {})
    const r = await listKnowledgeBases()
    list.value = (r && r.data) || []
  } catch (e) {
    message.error('知识库列表加载失败')
  } finally {
    loading.value = false
  }
}

// 全局参数默认值缓存（扁平的「后端键 → 值」）：占位符展示"继承的全局值" + 新建库模板预填。
// 走 /kb/param-defaults（普通用户可读）而不是管理端点 /config：知识库已对普通用户开放自建，
// 用 /config 会让普通用户永远看不到默认值（403，还会触发全局误报提示）。
const flatCfg = ref({})
const loadParamDefaults = async () => {
  if (Object.keys(flatCfg.value).length) return
  const r = await getKbParamDefaults()
  flatCfg.value = (r && r.data) || {}
}
const flatVal = key => String(flatCfg.value[key] ?? '').trim()
const gval = (group, key) => flatVal(group + '.' + key)
/** 数字类占位符：显示当前全局值（留空继承它） */
const numPh = (group, key) => {
  const v = gval(group, key)
  return v === '' ? '继承' : `全局 ${v}`
}
/** 开关类占位符：显示全局当前状态（开/关） */
const triPh = (group, key) => {
  const v = gval(group, key)
  return v === '' ? '继承' : `全局（${v === 'true' ? '开' : '关'}）`
}
/** 枚举类占位符：显示全局当前值（如 ocrEngine） */
const valPh = (group, key) => {
  const v = gval(group, key)
  return v === '' ? '继承全局' : `继承全局（${v}）`
}

/**
 * 新建：以当前全局值为**模板**预填解析与检索参数（保存即固化到本库；之后改全局设置不会回溯
 * 影响已建库——要跟随就清空对应项后保存，空值即"不写覆盖"）。任一字段留空 = 该库该项跟随全局。
 */
const prefillFromGlobal = async () => {
  // 模型索引一并加载：rerank 引用要先验可解析（停用/他人供应商不在可选列表）再预填，
  // 否则触发器裸显原始 UUID 长串（也是此前参数网格被撑爆错位的诱因）
  await Promise.all([loadParamDefaults(), loadModelIndex()])
  const num = v => (v === undefined || v === null || v === '' ? null : Number(v))
  const q = form.value.q
  const p = form.value.p
  // 解析参数（模板）
  p.maxSize = num(flatVal('chunk.maxSize'))
  p.overlap = num(flatVal('chunk.overlap'))
  p.maxChunks = num(flatVal('chunk.maxChunks'))
  p.maxImages = num(flatVal('chunk.maxImages'))
  const structural = flatVal('chunk.structural')
  p.structural = structural === '' ? null : structural
  p.structuralRatio = num(flatVal('chunk.structuralRatio'))
  p.headingDepth = num(flatVal('chunk.headingDepth'))
  const qaEnabled = flatVal('parse.qaEnabled')
  p.qaEnabled = qaEnabled === '' ? null : qaEnabled
  p.qaPerChunk = num(flatVal('parse.qaPerChunk'))
  const childEnabled = flatVal('parse.childEnabled')
  p.childEnabled = childEnabled === '' ? null : childEnabled
  p.childSize = num(flatVal('parse.childSize'))
  const ocrEngine = flatVal('parse.ocrEngine')
  p.ocrEngine = ocrEngine === '' ? null : ocrEngine
  p.ocrMinText = num(flatVal('parse.ocrMinText'))
  p.ocrDpi = num(flatVal('parse.ocrDpi'))
  // 检索参数（模板）：此前只预填解析参数，检索参数要用户自己猜当前生效值
  q.vectorWeight = num(flatVal('retrieval.vectorWeight'))
  q.keywordWeight = num(flatVal('retrieval.keywordWeight'))
  q.vecThreshold = num(flatVal('retrieval.vecThreshold'))
  q.vectorTopK = num(flatVal('retrieval.vectorTopK'))
  q.keywordLimit = num(flatVal('retrieval.keywordLimit'))
  const rerankEnabled = flatVal('rerank.enabled')
  q.rerankEnabled = rerankEnabled === '' ? null : rerankEnabled
  const rerankRef = flatVal('rerank.model')
  q.rerankModel = rerankRef && modelRefInfo(rerankRef) ? rerankRef : ''   // 列表外引用不预填（留空=继承全局）
}

const openCreate = () => {
  editing.value = null
  form.value = blank()
  showEdit.value = true
  prefillFromGlobal().catch(e => message.error('知识库参数默认值加载失败：' + (e.message || '请刷新重试')))
}

const openEdit = row => {
  editing.value = row
  form.value = hydrateForm(row)
  showEdit.value = true
  loadParamDefaults().catch(e => message.error('知识库参数默认值加载失败：' + (e.message || '请刷新重试')))
}

const save = async () => {
  if (!form.value.name || !form.value.name.trim()) {
    message.warning('请填写知识库名称')
    return
  }
  if (!form.value.embeddingRef) {
    message.warning('请选择向量模型（必选：向量空间与库一一对应）')
    return
  }
  saving.value = true
  try {
    const { queryParams, parseParams } = serialize(form.value)
    const body = {
      name: form.value.name.trim(),
      description: form.value.description || null,
      embeddingRef: form.value.embeddingRef,
      queryParams,
      parseParams,
      isDefault: form.value.isDefault ? 1 : 0,
      graphEnabled: form.value.graphEnabled ? 1 : 0
    }
    const r = editing.value
      ? await updateKnowledgeBase(editing.value.id, body)
      : await createKnowledgeBase(body)
    if (r && r.success === false) {
      message.error(r.msg || '保存失败')
      return
    }
    message.success(editing.value ? '已保存' : '已创建')
    showEdit.value = false
    await load()
  } catch (e) {
    message.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

// ==================== P1 GraphRAG：图谱构建与三元组浏览 ====================
const graphModal = ref(false)
const graphKb = ref(null)
const graphInfo = ref({})
const graphBuilding = ref(false)
const triples = ref([])
const triplesLoading = ref(false)
const triplePage = ref(1)
const tripleTotal = ref(0)
const tripleCols = [
  { title: '主体', dataIndex: 'subject', key: 'subject', width: 150, ellipsis: true },
  { title: '关系', dataIndex: 'predicate', key: 'predicate', width: 90 },
  // 客体是最长的一列，吃掉剩余弹性宽度；来源文档基本是同名文件，固定宽即可
  { title: '客体', dataIndex: 'object', key: 'object', ellipsis: true },
  { title: '来源文档', dataIndex: 'doc', key: 'doc', width: 180, ellipsis: true }
]
const triplePagination = computed(() => ({
  current: triplePage.value, total: tripleTotal.value, pageSize: 20, showSizeChanger: false
}))
const openGraph = async kb => {
  graphKb.value = kb
  graphModal.value = true
  triplePage.value = 1
  refreshGraph()
}
const refreshGraph = async () => {
  if (!graphKb.value) return
  try {
    const [st, tr] = await Promise.all([
      graphStatus(graphKb.value.id),
      graphTriples(graphKb.value.id, triplePage.value, 20)
    ])
    if (st.success) graphInfo.value = st.data || {}
    if (tr.success) {
      triples.value = tr.data?.rows || []
      tripleTotal.value = tr.data?.total || 0
    }
  } catch (e) { message.error(e.message || '图谱状态加载失败') }
  // 图视图开着时刷新同步重渲染（构建完成/手动刷新都能看到新关系）
  if (graphView.value === 'graph') nextTick(renderGraphChart)
}
const onTripleTableChange = pg => { triplePage.value = pg.current; refreshGraph() }

// ==================== 图视图（echarts 力导向图：节点=实体、边=关系） ====================
// 交互铁律：**init + 一次 setOption 之后绝不再碰图表状态**（部分 setOption 会重建力模拟与漫游坐标系
// ——roam/拖拽/滚轮就此失灵，前两版都栽在这）。邻居信息走「点击节点 → 图下方关系面板」，纯 Vue 状态。
const graphView = ref('graph')   // 默认图视图；列表是核对清单
const graphChartEl = ref(null)
const graphSampled = 500         // 图视图一次拉取的关系上限（后端放宽到 500，超出部分走列表）
let chartInstance = null
const graphLinks = ref([])       // 当前关系（关系面板用）
const selectedEntity = ref(null) // 点选的实体（null=未选）

const entityRelations = computed(() => {
  if (!selectedEntity.value) return []
  return graphLinks.value.filter(l => l.source === selectedEntity.value || l.target === selectedEntity.value)
})

const onGraphViewChange = () => { if (graphView.value === 'graph') nextTick(renderGraphChart) }

watch(graphModal, open => {
  if (open) nextTick(() => { if (graphView.value === 'graph') renderGraphChart() })
  else disposeChart()
})

function disposeChart() {
  if (chartInstance) { chartInstance.dispose(); chartInstance = null }
}

async function renderGraphChart() {
  if (!graphKb.value || !graphChartEl.value) return
  let rows = []
  try {
    const r = await graphTriples(graphKb.value.id, 1, graphSampled)
    rows = r.success ? (r.data?.rows || []) : []
  } catch (e) { message.error(e.message || '图谱数据加载失败'); return }
  disposeChart()
  selectedEntity.value = null
  if (!rows.length) { graphLinks.value = []; return }   // 空态由模板的 .graph-empty 兜底
  // 节点度数（连线越多的实体越大）
  const degree = {}
  const nodeNames = []
  const seen = new Set()
  graphLinks.value = rows.map(t => {
    for (const n of [t.subject, t.object]) {
      degree[n] = (degree[n] || 0) + 1
      if (!seen.has(n)) { seen.add(n); nodeNames.push(n) }
    }
    return { source: t.subject, target: t.object, value: t.predicate, doc: t.doc }
  })
  chartInstance = echarts.init(graphChartEl.value)
  // 一次性 setOption，此后只读不写——交互全部交给 echarts 原生 roam（拖拽/平移/滚轮缩放）
  // 关键：graph 漫游只覆盖「初始布局矩形」，平移出新区域后就拖不动了（echarts 内部行为）。
  // 把视图矩形向四周外扩 1600px，漫游可及范围远超连续平移所需；力布局以中心聚类，视觉不受影响。
  const wrapRect = graphChartEl.value.getBoundingClientRect()
  const pad = 1600
  chartInstance.setOption({
    backgroundColor: 'transparent',
    tooltip: {
      formatter: p => p.dataType === 'edge'
        ? `<b>${p.data.source}</b> —【${p.data.value}】→ <b>${p.data.target}</b><br/><span style="color:#999">来源：${p.data.doc || '—'}</span>`
        : `<b>${p.name}</b><br/><span style="color:#999">点击查看该实体的关系</span>`
    },
    series: [{
      type: 'graph', layout: 'force', roam: true, draggable: true, cursor: 'grab',
      left: -pad, top: -pad, width: wrapRect.width + pad * 2, height: wrapRect.height + pad * 2,
      force: { repulsion: 320, edgeLength: [60, 150], gravity: 0.2, layoutAnimation: true },
      data: nodeNames.map(n => ({
        name: n,
        symbolSize: Math.min(48, 14 + (degree[n] || 1) * 4),
        label: { show: true, fontSize: 10 }
      })),
      links: graphLinks.value,
      label: { color: '#333', position: 'right' },
      lineStyle: { color: '#b9c0cc', curveness: 0.05 },
      edgeLabel: { show: true, fontSize: 10, color: '#8a919e', formatter: '{c}' },
      edgeSymbol: ['none', 'arrow'], edgeSymbolSize: 7,
      itemStyle: { color: '#4f6ef2' }
    }]
  }, true)
  // 点节点 = 在图下方显示该实体的关系清单（纯 Vue 状态，零图表突变）
  chartInstance.on('click', p => {
    if (p.dataType === 'node') {
      window.__lastNodeClick = p.name   // 调试口：自动化测试/排障用，无副作用
      selectedEntity.value = p.name
    }
  })
}

const onWinResize = () => { if (chartInstance) chartInstance.resize() }
const doBuild = async () => {
  graphBuilding.value = true
  try {
    const r = await graphBuild(graphKb.value.id)
    if (r.success) {
      message.success(`构建已开始（${r.data?.total || 0} 个文档），可点「刷新」看进度`)
      // 构建中轮询进度（3s 一次，弹窗开着才轮询）
      const timer = setInterval(() => {
        if (!graphModal.value) { clearInterval(timer); return }
        graphStatus(graphKb.value.id).then(st => {
          if (st.success) {
            graphInfo.value = st.data || {}
            if (!st.data?.building) { clearInterval(timer); message.success('图谱构建完成'); refreshGraph() }
          }
        }).catch(() => clearInterval(timer))
      }, 3000)
    } else message.error(r.msg || '构建启动失败')
  } catch (e) { message.error(e.message || '构建启动失败') }
  finally { graphBuilding.value = false }
}
const doClearGraph = async () => {
  try {
    const r = await graphClear(graphKb.value.id)
    if (r.success) { message.success('图谱已清空'); refreshGraph() }
    else message.error(r.msg || '清空失败')
  } catch (e) { message.error(e.message || '清空失败') }
}

const onDelete = async row => {
  try {
    const r = await deleteKnowledgeBase(row.id)
    if (r && r.success === false) {
      message.warning(r.msg || '无法删除')
      return
    }
    message.success('已删除（关联智能体已同步摘除该库）')
    await load()
  } catch (e) {
    message.error(e.message || '删除失败')
  }
}

onMounted(() => {
  load()
  loadParamDefaults().catch(e => message.error('知识库参数默认值加载失败：' + (e.message || '请刷新重试')))
  ensureAuth().then(me => { myUid.value = me.user || '' })
  window.addEventListener('resize', onWinResize)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onWinResize)
  disposeChart()
})
</script>

<style scoped>
.kb-page { padding: 4px 2px; }
.kb-cards {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px; align-items: stretch;
}
.kb-card { cursor: pointer; display: flex; flex-direction: column; gap: 8px; transition: border-color .15s, box-shadow .15s; }
.kb-card:hover { border-color: var(--app-accent); box-shadow: 0 4px 16px -6px rgba(46, 107, 230, .25); }
.kb-card-head { display: flex; align-items: center; gap: 8px; min-width: 0; }
.kb-card-ic { color: var(--app-accent); font-size: 16px; flex: none; }
.kb-name { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.kb-card-desc {
  margin: 0; font-size: 12px; color: var(--app-text3); line-height: 1.6; min-height: 38px;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.kb-card-meta { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; font-size: 11px; color: var(--app-text2); }
.kb-meta-sep { color: var(--app-text3); }
.kb-card-actions { display: flex; align-items: center; gap: 10px; border-top: 1px solid var(--app-border); padding-top: 8px; margin-top: auto; }
.kb-dim { color: var(--app-text3); font-size: 11px; }
.kb-params { color: var(--app-ok); font-size: 11px; }
.kb-warn { color: var(--app-danger); font-size: 11px; }
.kb-zero { color: var(--app-text3); }
.kb-empty { grid-column: 1 / -1; text-align: center; color: var(--app-text3); font-size: 12px; padding: 40px 0; }
.kb-hint { font-size: 12px; color: var(--app-text3); line-height: 1.6; margin-top: 4px; }
.kb-divider { margin: 16px 0 4px; font-size: 12px; color: var(--app-text2); }
.kb-param-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); column-gap: 8px; }
/* min-width:0：长内容（如列表外模型的原始引用串）只省略号，不把轨道撑出弹窗 */
.kb-param-grid :deep(.ant-form-item) { margin-bottom: 8px; min-width: 0; }

/* 图谱：工具栏 + 力导向图画布 */
.graph-toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.graph-wrap { position: relative; }
.graph-canvas { height: 480px; width: 100%; }
.graph-panel {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  border: 1px solid var(--app-border); border-radius: 8px; padding: 8px 10px; margin-top: 6px;
  background: var(--app-bg, #fafafa); font-size: 12px;
}
.graph-rel {
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 4px;
  padding: 2px 8px; font-size: 12px; font-family: ui-monospace, Menlo, monospace;
}
.graph-empty, .graph-note { font-size: 12px; color: var(--app-text3); }
.graph-empty { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; }
/* 长标签「GraphRAG 知识图谱」放不下标准 5/24 标签列：放开 nowrap 折成两行，
   保持与表单其他行同一标签列右对齐（antd 标签默认 nowrap + 固定行高，需一并放开） */
.kb-item-wrap-label :deep(.ant-form-item-label),
.kb-item-wrap-label :deep(.ant-form-item-label > label) { white-space: normal; height: auto; }
</style>
