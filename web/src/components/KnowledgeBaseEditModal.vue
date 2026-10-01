<template>
  <a-modal :open="open" :title="isEdit ? '编辑知识库' : '新建知识库'"
           :width="640" :confirm-loading="saving"
           @update:open="v => emit('update:open', v)" @ok="save">
    <a-form :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }">
      <a-form-item label="名称" required>
        <a-input v-model:value="form.name" placeholder="如：操作手册库" />
      </a-form-item>
      <a-form-item label="图标">
        <!-- 默认库恒为问渠品牌标（后端同样强制），直接不给改 -->
        <div v-if="iconLocked" class="kb-icon-locked">
          <KbIcon :kb="{ icon: 'wenqu', isDefault: 1 }" :size="26" />
          <span class="kb-hint">{{ iconLockHint }}</span>
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
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { message } from 'ant-design-vue'
import { createKnowledgeBase, updateKnowledgeBase, getKbParamDefaults } from '../api'
import ModelSelect from './ModelSelect.vue'
import KbIcon from './KbIcon.vue'
import { loadModelIndex, modelRefInfo } from '../utils/modelRef'
import { isAdminSync } from '../utils/auth'

// 图标可选集（emoji 口径与智能体一致）：''=默认库图标；其余 emoji 原样存库、原样渲染。
// 「问渠品牌标」不进选择集——它是默认库专属（编辑默认库时锁定态单独展示，见 iconLocked）
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

const isAdmin = isAdminSync()
const isEdit = computed(() => !!props.kb)
const saving = ref(false)

function blank () {
  return {
    name: '', description: '', icon: '', embeddingRef: '', isDefault: false, graphEnabled: false,
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
  // 图标：未配时默认库按问渠品牌标预选（口径同智能体：内置默认 wenqu）
  f.icon = row?.icon || (row?.isDefault === 1 ? 'wenqu' : '')
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

const form = ref(blank())

// 默认库图标锁定：恒为问渠品牌标（编辑中的默认库，或本次勾选了「设为默认库」）
const iconLocked = computed(() => props.kb?.isDefault === 1 || form.value.isDefault)
const iconLockHint = computed(() => props.kb?.isDefault === 1
  ? `「${props.kb.name || '默认知识库'}」为默认库，固定使用问渠品牌标，不可修改`
  : '设为默认库后固定使用问渠品牌标，保存后不可修改')
// 勾选「设为默认库」即锁定为品牌标（取消勾选恢复可自选；默认库被降级时品牌标随身份失效）
watch(() => form.value.isDefault, on => {
  if (on) form.value.icon = 'wenqu'
  else if (props.kb?.isDefault === 1) form.value.icon = ''
})
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

// 打开时装载：编辑库→回填该库覆盖值；新建→先清空模板再按全局值预填
watch(() => props.open, open => {
  if (!open) return
  form.value = props.kb ? hydrateForm(props.kb) : blank()
  if (props.kb) {
    loadParamDefaults().catch(e => message.error('知识库参数默认值加载失败：' + (e.message || '请刷新重试')))
  } else {
    prefillFromGlobal().catch(e => message.error('知识库参数默认值加载失败：' + (e.message || '请刷新重试')))
  }
})

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
      // 图标：空串 → 后端归一为 null（默认展示）；'wenqu'=问渠品牌标；emoji 原样存（默认库后端强制 wenqu）
      icon: form.value.icon || '',
      embeddingRef: form.value.embeddingRef,
      queryParams,
      parseParams,
      isDefault: form.value.isDefault ? 1 : 0,
      graphEnabled: form.value.graphEnabled ? 1 : 0
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
/* min-width:0：长内容（如列表外模型的原始引用串）只省略号，不把轨道撑出弹窗 */
.kb-param-grid :deep(.ant-form-item) { margin-bottom: 8px; min-width: 0; }
.kb-warn { color: var(--app-danger); }
/* 长标签「GraphRAG 知识图谱」放不下标准 5/24 标签列：放开 nowrap 折成两行，
   保持与表单其他行同一标签列右对齐（antd 标签默认 nowrap + 固定行高，需一并放开） */
.kb-item-wrap-label :deep(.ant-form-item-label),
.kb-item-wrap-label :deep(.ant-form-item-label > label) { white-space: normal; height: auto; }
</style>
