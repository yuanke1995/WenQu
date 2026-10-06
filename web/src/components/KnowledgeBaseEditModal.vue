<template>
  <a-modal :open="open" :title="isEdit ? '编辑知识库' : '新建知识库'"
           :width="640" :confirm-loading="saving"
           @update:open="v => emit('update:open', v)" @ok="save">
    <a-form :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }">
      <!-- 官方内置库：仅检索/解析参数与向量模型可维护（后端白名单同口径），其余由版本同步维护 -->
      <div v-if="isBuiltin" class="kb-hint" style="margin:0 0 10px">
        官方内置库：文档内容随版本自动同步，名称 / 图标 / 描述不可修改；
        <b>仅向量模型与检索/解析参数可维护</b>（典型用途：绑定重排模型——不绑则没有精排，相关分门从重排门退化到融合分门，词面重叠的弱相关块更容易混进引用）。
      </div>
      <a-form-item label="名称" :required="!isBuiltin && !isDefaultKb">
        <a-input v-model:value="form.name" :disabled="isBuiltin || isDefaultKb" placeholder="如：操作手册库" />
        <div v-if="isDefaultKb" class="kb-hint" style="margin-top:4px">默认库名称固定，不可修改</div>
      </a-form-item>
      <a-form-item label="图标">
        <!-- 默认库恒为问渠品牌标（后端同样强制），直接不给改；官方内置库图标随版本同步固定 -->
        <div v-if="isDefaultKb" class="kb-icon-locked">
          <KbIcon :kb="{ icon: 'wenqu', isDefault: 1 }" :size="26" />
          <span class="kb-hint">「{{ props.kb.name || '默认知识库' }}」为默认库，固定使用问渠品牌标，不可修改</span>
        </div>
        <div v-else-if="isBuiltin" class="kb-icon-locked">
          <KbIcon :kb="props.kb" :size="26" />
          <span class="kb-hint">官方内置库图标随版本自动同步，不可修改</span>
        </div>
        <template v-else>
          <div class="kb-icon-pick">
            <button v-for="opt in ICON_OPTIONS" :key="opt.value || 'default'" type="button"
                    class="kb-icon-opt" :class="{ on: (form.icon || '') === opt.value }"
                    :title="opt.label" :aria-pressed="(form.icon || '') === opt.value"
                    @click="form.icon = opt.value">
              <KbIcon :kb="{ icon: opt.value, isDefault: 0 }" :size="22" />
            </button>
          </div>
          <div class="kb-hint" style="margin-top:4px">展示在知识库列表卡片上；不选即默认库图标。</div>
        </template>
      </a-form-item>
      <a-form-item label="描述">
        <a-input v-model:value="form.description" :disabled="isBuiltin || isDefaultKb" placeholder="这个库放什么资料" />
        <div v-if="isDefaultKb" class="kb-hint" style="margin-top:4px">默认库描述固定，不可修改</div>
      </a-form-item>
      <!-- 向量模型：官方内置库也**可改**（管理员级）。原先随「名称/描述」一起置灰，而后端
           同一批字段里又放开了检索参数——于是引用清单里「去修改」指过来，看到的却是一个
           灰框：供应商额度耗尽时，管理员唯一能救内置库的动作就是换一个能用的向量模型。 -->
      <a-form-item label="向量模型" required>
        <ModelSelect v-model="form.embeddingRef" type="embedding"
                     :width="320" :admin-tip-visible="true" />
        <div class="kb-hint">必选：本库文档按此模型向量化与检索（不同模型的向量空间不兼容，无法跨模型混用）；换模型会自动按库重嵌入，期间该库检索降级关键词路。</div>
        <div v-if="isBuiltin" class="kb-hint">
          当前供应商额度不足时，在这里换成一个能用的向量模型即可救活本库；改完点「保存」会按库重嵌入已有文档。
        </div>
      </a-form-item>

      <!-- 高级参数：默认收起（小白不改就能用），每个字段的问号里有"这是什么/什么时候才需要调" -->
      <a-divider class="kb-divider" plain>高级参数（可选）</a-divider>
      <div class="kb-hint" style="margin:0 0 4px">
        检索与解析参数默认继承系统推荐值，<b>不改就能用</b>。仅当问答效果不理想（答非所问、该搜到的资料没搜到）时，
        再展开对应一组、按字段后问号里的说明微调。
      </div>
      <a-collapse v-model:activeKey="advActive" ghost class="kb-adv">
        <a-collapse-panel key="q">
          <template #header>
            <span>检索参数</span>
            <a-tag v-if="customQ" color="blue" size="small" style="margin-left:8px">{{ customQ }} 项已自定义</a-tag>
            <span v-else-if="kb" class="kb-adv-sub">全部跟随默认</span>
          </template>
          <div class="kb-param-grid">
            <a-form-item label="向量权重" :tooltip="tip('retrieval.vectorWeight')" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
              <a-input-number v-model:value="form.q.vectorWeight" :min="0" :max="1" :step="0.05" style="width:100%" :placeholder="numPh('retrieval', 'vectorWeight')" />
            </a-form-item>
            <a-form-item label="关键词权重" :tooltip="tip('retrieval.keywordWeight')" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
              <a-input-number v-model:value="form.q.keywordWeight" :min="0" :max="1" :step="0.05" style="width:100%" :placeholder="numPh('retrieval', 'keywordWeight')" />
            </a-form-item>
            <a-form-item label="向量阈值" :tooltip="tip('retrieval.vecThreshold')" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
              <a-input-number v-model:value="form.q.vecThreshold" :min="0" :max="1" :step="0.05" style="width:100%" :placeholder="numPh('retrieval', 'vecThreshold')" />
            </a-form-item>
            <a-form-item label="向量召回上限" :tooltip="tip('retrieval.vectorTopK')" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
              <a-input-number v-model:value="form.q.vectorTopK" :min="1" :max="100" style="width:100%" :placeholder="numPh('retrieval', 'vectorTopK')" />
            </a-form-item>
            <a-form-item label="关键词召回上限" :tooltip="tip('retrieval.keywordLimit')" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
              <a-input-number v-model:value="form.q.keywordLimit" :min="1" style="width:100%" :placeholder="numPh('retrieval', 'keywordLimit')" />
            </a-form-item>
            <a-form-item label="重排" :tooltip="tip('rerank.enabled')" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
              <a-select v-model:value="form.q.rerankEnabled" style="width:100%" :options="triOptions" :placeholder="triPh('rerank', 'enabled')" allow-clear />
            </a-form-item>
            <a-form-item label="重排模型" :tooltip="tip('rerank.model')" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
              <ModelSelect v-model="form.q.rerankModel" type="rerank" width="100%" inherit-label="跟随默认" :placeholder="rerankPh" />
            </a-form-item>
          </div>
          <div class="kb-hint" style="margin:2px 0 0">新建库按你当前生效的默认值预填（保存即固化）；清空某项 = 该库该项跟随系统全局默认（管理员在「系统设置」修改，改后自动生效）。</div>
        </a-collapse-panel>
        <a-collapse-panel key="p">
          <template #header>
            <span>解析参数</span>
            <a-tag v-if="customP" color="blue" size="small" style="margin-left:8px">{{ customP }} 项已自定义</a-tag>
            <span v-else-if="kb" class="kb-adv-sub">全部跟随默认</span>
          </template>
          <div class="kb-param-grid">
            <a-form-item label="分块最大字符" :tooltip="tip('chunk.maxSize')" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
              <a-input-number v-model:value="form.p.maxSize" :min="200" :step="100" style="width:100%" :placeholder="numPh('chunk', 'maxSize')" />
            </a-form-item>
            <a-form-item label="分块重叠字符" :tooltip="tip('chunk.overlap')" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
              <a-input-number v-model:value="form.p.overlap" :min="0" :step="20" style="width:100%" :placeholder="numPh('chunk', 'overlap')" />
            </a-form-item>
            <a-form-item label="最大知识块数" :tooltip="tip('chunk.maxChunks')" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
              <a-input-number v-model:value="form.p.maxChunks" :min="0" :step="100" style="width:100%" :placeholder="numPh('chunk', 'maxChunks')" />
            </a-form-item>
            <a-form-item label="最多提取图片" :tooltip="tip('chunk.maxImages')" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
              <a-input-number v-model:value="form.p.maxImages" :min="0" :step="10" style="width:100%" :placeholder="numPh('chunk', 'maxImages')" />
            </a-form-item>
            <a-form-item label="结构感知切分" :tooltip="tip('chunk.structural')" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
              <a-select v-model:value="form.p.structural" style="width:100%" :options="triOptions" :placeholder="triPh('chunk', 'structural')" allow-clear />
            </a-form-item>
            <a-form-item label="边界阈值比例" :tooltip="tip('chunk.structuralRatio')" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
              <a-input-number v-model:value="form.p.structuralRatio" :min="0.5" :max="1" :step="0.05" style="width:100%" :placeholder="numPh('chunk', 'structuralRatio')" />
            </a-form-item>
            <a-form-item label="标题识别层级" :tooltip="tip('chunk.headingDepth')" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
              <a-input-number v-model:value="form.p.headingDepth" :min="1" :max="6" style="width:100%" :placeholder="numPh('chunk', 'headingDepth')" />
            </a-form-item>
            <a-form-item label="问答对增强" :tooltip="tip('parse.qaEnabled')" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
              <a-select v-model:value="form.p.qaEnabled" style="width:100%" :options="triOptions" :placeholder="triPh('parse', 'qaEnabled')" allow-clear />
            </a-form-item>
            <a-form-item label="每块问答对数" :tooltip="tip('parse.qaPerChunk')" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
              <a-input-number v-model:value="form.p.qaPerChunk" :min="1" :max="5" :step="1" style="width:100%" :placeholder="numPh('parse', 'qaPerChunk')" />
            </a-form-item>
            <a-form-item label="父子分块" :tooltip="tip('parse.childEnabled')" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
              <a-select v-model:value="form.p.childEnabled" style="width:100%" :options="triOptions" :placeholder="triPh('parse', 'childEnabled')" allow-clear />
            </a-form-item>
            <a-form-item label="子块尺寸(字符)" :tooltip="tip('parse.childSize')" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
              <a-input-number v-model:value="form.p.childSize" :min="100" :max="2000" :step="100" style="width:100%" :placeholder="numPh('parse', 'childSize')" />
            </a-form-item>
            <a-form-item label="PDF 解析引擎" :tooltip="tip('parse.ocrEngine')" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
              <a-select v-model:value="form.p.ocrEngine" style="width:100%" :options="ocrEngineOptions" :placeholder="valPh('parse', 'ocrEngine')" allow-clear />
            </a-form-item>
            <a-form-item label="扫描件阈值(字符)" :tooltip="tip('parse.ocrMinText')" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
              <a-input-number v-model:value="form.p.ocrMinText" :min="0" :step="5" style="width:100%" :placeholder="numPh('parse', 'ocrMinText')" />
            </a-form-item>
            <a-form-item label="OCR 渲染 DPI" :tooltip="tip('parse.ocrDpi')" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
              <a-input-number v-model:value="form.p.ocrDpi" :min="72" :max="400" :step="8" style="width:100%" :placeholder="numPh('parse', 'ocrDpi')" />
            </a-form-item>
            <a-form-item label="图片描述模型" :tooltip="tip('parse.visionRef')" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
              <ModelSelect v-model="form.p.visionRef" type="vision,ocr" width="100%" inherit-label="不描述图片" />
            </a-form-item>
            <a-form-item label="扫描件 OCR 模型" :tooltip="tip('parse.ocrRef')" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
              <ModelSelect v-model="form.p.ocrRef" type="ocr" width="100%" inherit-label="跟随图片描述模型" />
            </a-form-item>
          </div>
          <div class="kb-hint" style="margin:6px 0 0">
            改解析参数后需重新解析文档才生效（不自动重解析全库）。扫描件/图片型 PDF 需绑定「扫描件 OCR 模型」——
            <span class="kb-warn">两者都空时这类文档解析会失败</span>；纯文本 PDF 用「纯文本层」最快，
            表格/版式复杂的大文档再选 MinerU / PP 版面引擎（较慢，约 10s/页）。
          </div>
        </a-collapse-panel>
      </a-collapse>

      <!-- 无「设为默认库」开关：默认库是「未指定归属文档」的兜底（每人一个、系统懒创建、不可删）；
           后端 isDefault 不可写（无晋升路径），UI 上传也全部显式带库——2026-10-06 评估后不提供换默认库入口 -->

      <!-- P1 GraphRAG 库级开关（默认关）：开启后解析完成自动抽三元组，检索一跳图扩展；
           官方内置库不显示——后端 builtin 白名单只收检索/解析参数与向量模型，提交会被整单拒绝 -->
      <a-form-item v-if="!isBuiltin" label="知识图谱" style="margin-top:4px">
        <div class="kb-switch-line">
          <a-switch v-model:checked="form.graphEnabled" />
        </div>
        <div class="kb-hint" style="margin-top:4px">
          GraphRAG：开启后新解析的文档自动抽「实体-关系」三元组，检索时一跳图扩展（跨文档多跳问答）；已有文档点列表页「构建图谱」回溯。
        </div>
      </a-form-item>
      <a-form-item v-if="!isBuiltin && form.graphEnabled" label="图谱抽取模型">
        <ModelSelect v-model="form.graphModelRef" type="chat" width="320" inherit-label="跟随库主默认聊天模型" />
        <div class="kb-hint" style="margin-top:4px">
          本库抽三元组用的聊天模型（抽取消耗的 token 记在所选模型上）。
          留空则用库主在个人设置里选的默认聊天模型；两者都没有 = 不抽取（开启时会被拦下）。
        </div>
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { message } from 'ant-design-vue'
import { createKnowledgeBase, updateKnowledgeBase, getKbParamDefaults, getKbParamTips } from '../api'
import ModelSelect from './ModelSelect.vue'
import KbIcon from './KbIcon.vue'
import { loadModelIndex, modelRefInfo } from '../utils/modelRef'

// 图标可选集（emoji 口径与智能体一致）：''=默认库图标；其余 emoji 原样存库、原样渲染。
// 「问渠品牌标」不进选择集——它是默认库专属（编辑默认库时锁定态单独展示，见 isDefaultKb）
const ICON_OPTIONS = [
  { value: '', label: '默认图标' },
  { value: '📚', label: '资料' },
  { value: '🧠', label: '知识大脑' },
  { value: '⚖️', label: '法律' },
  { value: '📊', label: '报表' },
  { value: '🔍', label: '检索' },
  { value: '📁', label: '档案' },
  { value: '🛠️', label: '工具' },
  { value: '🌐', label: '全网' },
  { value: '💡', label: '点子' },
  { value: '📝', label: '笔记' }
]

// kb 为 null = 新建模式（表单以当前全局值为模板预填）；传对象 = 编辑该库
const props = defineProps({
  open: { type: Boolean, default: false },
  kb: { type: Object, default: null }
})
const emit = defineEmits(['update:open', 'saved'])

const isEdit = computed(() => !!props.kb)
const saving = ref(false)

function blank () {
  return {
    name: '', description: '', icon: '', embeddingRef: '', graphEnabled: false, graphModelRef: '',
    q: { vectorWeight: null, keywordWeight: null, vecThreshold: null, vectorTopK: null, keywordLimit: null, rerankEnabled: null, rerankModel: '' },
    p: { maxSize: null, overlap: null, maxChunks: null, maxImages: null, structural: null, structuralRatio: null, headingDepth: null, qaEnabled: null, qaPerChunk: null, childEnabled: null, childSize: null, ocrEngine: null, ocrMinText: null, ocrDpi: null, visionRef: '', ocrRef: '' }
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
  // 图标：未配时默认库按问渠品牌标预选（口径同智能体：内置默认 wenqu）
  f.icon = row?.icon || (row?.isDefault === 1 ? 'wenqu' : '')
  f.embeddingRef = row?.embeddingRef || ''
  f.graphEnabled = row?.graphEnabled === 1
  f.graphModelRef = row?.graphModelRef || ''
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
  if (p.ocrRef) f.p.ocrRef = p.ocrRef
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
  if (f.p.ocrRef) p.ocrRef = f.p.ocrRef
  return {
    queryParams: Object.keys(q).length ? JSON.stringify(q) : null,
    parseParams: Object.keys(p).length ? JSON.stringify(p) : null
  }
}

const form = ref(blank())

// 官方内置库（builtin=1）：文档内容随版本自动同步，名称/图标/描述不接受编辑；
// 但检索/解析参数与**向量模型可改**（后端 KnowledgeBaseService.update 对内置库是这三个字段的白名单，
// 前后端必须严格一致——多提交一个字段会被后端整单 fail-loud 拒绝）
const isBuiltin = computed(() => props.kb?.builtin === 1)
// 默认库（问渠，每人一个、系统懒创建）：图标恒为问渠品牌标、名称固定，表单只读展示（后端同样强制）
const isDefaultKb = computed(() => props.kb?.isDefault === 1)
const triOptions = [
  { value: 'true', label: '开' },
  { value: 'false', label: '关' }
]

/** 库级 PDF 解析引擎选项（清空=继承全局；速度差异见下方提示） */
const ocrEngineOptions = [
  { value: 'none', label: '纯文本层（快，秒级）' },
  { value: 'vision', label: '视觉模型 OCR（逐页识别）' },
  { value: 'pp_structure_v3', label: 'PP-StructureV3 版面还原' },
  { value: 'mineru', label: 'MinerU 版面还原（慢，约 10s/页）' }
]

// 全局参数默认值缓存（扁平的「后端键 → 值」）：占位符展示"继承的全局值" + 新建库模板预填。
// 走 /kb/param-defaults（普通用户可读）而不是管理端点 /config：知识库已对普通用户开放自建，
// 用 /config 会让普通用户永远看不到默认值（403，还会触发全局误报提示）。
const flatCfg = ref({})
const loadParamDefaults = async () => {
  if (Object.keys(flatCfg.value).length) return
  const r = await getKbParamDefaults()
  flatCfg.value = (r && r.data) || {}
}

// 参数问号文案（/kb/param-tips，普通用户可读；与系统设置页字段说明同一份定义源）：
// 表单专属字段（两个模型槽位）没有配置键，用静态文案补。
const STATIC_TIPS = {
  'parse.visionRef': '文档内嵌图片（docx/PDF 里的插图、截图）的自动描述模型：把图转成文字后一起参与检索，图片内容也能被搜到。留空=解析时跳过图片描述。',
  'parse.ocrRef': '扫描件/图片型 PDF 逐页识别的 OCR 专用模型（如 PaddleOCR-VL）。留空回落「图片描述模型」；两者都空时这类文档解析会失败。'
}
const paramTips = ref({})
const loadParamTips = async () => {
  if (Object.keys(paramTips.value).length) return
  const r = await getKbParamTips()
  paramTips.value = (r && r.data) || {}
}
const tip = key => paramTips.value[key] || STATIC_TIPS[key] || ''

// 高级参数折叠区（默认收起：小白不改就能用）。编辑有自定义值的库时，组标题标出数量，
// 避免"被折叠藏起来"变成另一种不透明。
const advActive = ref([])
const customCount = obj => Object.values(obj).filter(v => v !== null && v !== undefined && v !== '').length
const customQ = computed(() => (props.kb ? customCount(form.value.q) : 0))
const customP = computed(() => (props.kb ? customCount(form.value.p) : 0))
const flatVal = key => String(flatCfg.value[key] ?? '').trim()
const gval = (group, key) => flatVal(group + '.' + key)
/** 数字类占位符：显示当前生效默认值（个人设置 > 系统全局；留空继承它） */
const numPh = (group, key) => {
  const v = gval(group, key)
  return v === '' ? '继承' : `默认 ${v}`
}
/** 开关类占位符：显示当前默认状态（开/关） */
const triPh = (group, key) => {
  const v = gval(group, key)
  return v === '' ? '继承' : `默认（${v === 'true' ? '开' : '关'}）`
}
/** 枚举类占位符：显示当前默认值（如 ocrEngine） */
const valPh = (group, key) => {
  const v = gval(group, key)
  return v === '' ? '继承默认' : `继承默认（${v}）`
}

// 重排模型槽位的占位点名：库未绑模型时实际生效的默认层 = 个人设置（rerank.model）→ 平台默认
// （rerank.platformRef）→ 本地 rerank 服务（前端不可知，不展示），口径同 ModelRegistryService.rerankRoute。
// 仅在重排生效（库覆盖或默认开）时点名，与数字项「默认 0.8」同口径——否则"跟随默认"背后
// 到底有没有模型、用谁的模型，用户只能靠猜。模型名优先取可选列表的展示名；平台层模型可能
// 不在本人可选列表（登记人是管理员），退回引用串的模型段（去供应商 UUID 前缀，同 ModelSelect 失配兜底）。
const rerankIndex = ref({})
const rerankEnabledEffective = computed(() => {
  const v = form.value.q.rerankEnabled
  return v === 'true' || v === 'false' ? v === 'true' : flatVal('rerank.enabled') === 'true'
})
const rerankPh = computed(() => {
  if (!rerankEnabledEffective.value) return ''
  const dref = flatVal('rerank.model') || flatVal('rerank.platformRef')
  if (!dref) return ''
  const info = rerankIndex.value[dref]
  if (info && info.displayName) return `默认 ${info.displayName}`
  const i = dref.indexOf('/')
  return `默认 ${i === -1 ? dref : dref.slice(i + 1)}`
})

/**
 * 新建：以你当前的**生效默认值**为模板预填解析与检索参数（生效顺序：遗留个人值 > 系统全局；保存即固化到本库；
 * 之后改默认设置不会回溯影响已建库——要跟随就清空对应项后保存，空值即"不写覆盖"）。
 * 任一字段留空 = 该库该项跟随系统全局默认（管理员在「系统设置」修改）。
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
  q.rerankModel = rerankRef && modelRefInfo(rerankRef) ? rerankRef : ''   // 列表外引用不预填（留空=继承：本地 rerank 服务）
}

// 打开时装载：编辑库→回填该库覆盖值；新建→先清空模板再按全局值预填
watch(() => props.open, open => {
  if (!open) return
  form.value = props.kb ? hydrateForm(props.kb) : blank()
  // 每次打开高级参数都收起（默认继承全局，不需要看）；官方内置库例外——它进来就是为了调检索参数
  advActive.value = isBuiltin.value ? ['q'] : []
  loadParamTips().catch(() => {})   // 问号文案加载失败不阻塞（只是少个问号）
  // 重排模型占位点名需要模型索引解析展示名（编辑模式此前不装；模块级 10s 缓存，新建模式重复调用无额外请求）
  loadModelIndex().then(i => { rerankIndex.value = i || {} }).catch(() => {})
  if (props.kb) {
    loadParamDefaults().catch(e => message.error('知识库参数默认值加载失败：' + (e.message || '请刷新重试')))
  } else {
    prefillFromGlobal().catch(e => message.error('知识库参数默认值加载失败：' + (e.message || '请刷新重试')))
  }
})

const save = async () => {
  // 官方内置库：名称/描述/图标不接受编辑（后端白名单会拒），但**向量模型可改**——
  // 供应商额度耗尽时，换一个能用的向量模型是管理员唯一能救这个库的动作。
  // 提交字段必须与后端白名单严格一致，多一个字段都会被 fail-loud 整单拒绝。
  if (!isBuiltin.value) {
    if (!form.value.name || !form.value.name.trim()) {
      message.warning('请填写知识库名称')
      return
    }
  }
  if (!form.value.embeddingRef) {
    message.warning('请选择向量模型（必选：向量空间与库一一对应）')
    return
  }
  saving.value = true
  try {
    const { queryParams, parseParams } = serialize(form.value)
    if (isBuiltin.value) {
      const savedBuiltin = await updateKnowledgeBase(props.kb.id,
        { queryParams, parseParams, embeddingRef: form.value.embeddingRef })
      if (savedBuiltin && savedBuiltin.success === false) {
        message.error(savedBuiltin.msg || '保存失败')
        return
      }
      message.success('已保存')
      emit('update:open', false)
      emit('saved', props.kb.id)
      return
    }
    const body = {
      name: form.value.name.trim(),
      description: form.value.description || null,
      // 图标：空串 → 后端归一为 null（默认展示）；'wenqu'=问渠品牌标；emoji 原样存（默认库后端强制 wenqu）
      icon: form.value.icon || '',
      embeddingRef: form.value.embeddingRef,
      queryParams,
      parseParams,
      graphEnabled: form.value.graphEnabled ? 1 : 0,
      graphModelRef: form.value.graphModelRef || ''
    }
    const saved = props.kb
      ? await updateKnowledgeBase(props.kb.id, body)
      : await createKnowledgeBase(body)
    if (saved && saved.success === false) {
      message.error(saved.msg || '保存失败')
      return
    }
    message.success(props.kb ? '已保存' : '已创建')
    emit('update:open', false)
    emit('saved', props.kb ? props.kb.id : (saved?.data?.id ?? null))
  } catch (e) {
    message.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.kb-hint { font-size: 12px; color: var(--app-text3); line-height: 1.6; margin-top: 4px; }
/* 图标选择器（口径同智能体页 ap-icon-pick） */
.kb-icon-pick { display: flex; flex-wrap: wrap; gap: 6px; }
.kb-icon-opt {
  width: 34px; height: 34px; border-radius: 9px; padding: 0;
  border: 1px solid var(--app-border); background: var(--app-panel); cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
  transition: border-color .15s, box-shadow .15s;
}
.kb-icon-opt:hover { border-color: var(--app-accent-border); }
.kb-icon-opt.on { border-color: var(--app-accent); box-shadow: 0 0 0 2px var(--app-accent-weak); }
.kb-icon-locked { display: flex; align-items: center; gap: 8px; }
.kb-divider { margin: 16px 0 4px; font-size: 12px; color: var(--app-text2); }
.kb-param-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); column-gap: 8px; }
/* 高级参数折叠区：默认收起，展开内容与卡片贴平（ghost 样式去掉默认边框底色） */
.kb-adv :deep(.ant-collapse-header) { padding: 6px 0 !important; font-size: 13px; color: var(--app-text2); }
.kb-adv :deep(.ant-collapse-content-box) { padding: 4px 0 0 !important; }
.kb-adv-sub { margin-left: 8px; font-size: 11px; color: var(--app-text3); }
/* min-width:0：长内容（如列表外模型的原始引用串）只省略号，不把轨道撑出弹窗 */
.kb-param-grid :deep(.ant-form-item) { margin-bottom: 8px; min-width: 0; }
.kb-warn { color: var(--app-danger); }
/* 开关行：裸放开关会贴行首，中心比标签文字高约 8px；撑到 32px 并居中 = 与输入框/标签文字同轴 */
.kb-switch-line { display: flex; align-items: center; min-height: 32px; }
</style>
