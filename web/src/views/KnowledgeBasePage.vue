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
            <button class="app-link-btn" @click="openEdit(kb)">编辑</button>
            <button class="app-link-btn danger" :disabled="kb.isDefault === 1" @click="onDelete(kb)">删除</button>
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

        <a-divider class="kb-divider" plain>检索参数（留空 = 继承全局设置）</a-divider>
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

        <a-divider class="kb-divider" plain>解析参数（留空 = 继承全局模板；仅对之后解析的文档生效）</a-divider>
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
          <a-form-item label="图片描述模型" :label-col="{ span: 10 }" :wrapper-col="{ span: 13 }">
            <ModelSelect v-model="form.p.visionRef" type="vision" width="100%" inherit-label="不描述图片" />
          </a-form-item>
        </div>
        <div class="kb-hint" style="margin:4px 0 0">
          改解析参数后需重新解析文档才会生效（不自动重解析全库）；扫描件/图片型 PDF 的 OCR 依赖「图片描述模型」——
          <span class="kb-warn">未绑定时该类文档会解析失败</span>（不产出残缺内容）。
        </div>

        <a-form-item label="设为默认库" style="margin-top:12px">
          <a-switch v-model:checked="form.isDefault" />
          <span class="kb-hint" style="margin-left:8px">新建文档默认归属</span>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { PlusOutlined, DatabaseOutlined } from '@ant-design/icons-vue'
import { listKnowledgeBases, createKnowledgeBase, updateKnowledgeBase, deleteKnowledgeBase, getConfig } from '../api'
import ModelSelect from '../components/ModelSelect.vue'
import { loadModelIndex, modelRefInfo } from '../utils/modelRef'

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

function blank () {
  return {
    name: '', description: '', embeddingRef: '', isDefault: false,
    q: { vectorWeight: null, keywordWeight: null, vecThreshold: null, vectorTopK: null, keywordLimit: null, rerankEnabled: null, rerankModel: '' },
    p: { maxSize: null, overlap: null, maxChunks: null, maxImages: null, structural: null, structuralRatio: null, headingDepth: null, visionRef: '' }
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
  structuralRatio: 'chunk.structuralRatio', headingDepth: 'chunk.headingDepth'
}

function hydrateForm (row) {
  const f = blank()
  f.name = row?.name || ''
  f.description = row?.description || ''
  f.embeddingRef = row?.embeddingRef || ''
  f.isDefault = row?.isDefault === 1
  let q = {}
  let p = {}
  try { q = row?.queryParams ? JSON.parse(row.queryParams) : {} } catch { q = {} }
  try { p = row?.parseParams ? JSON.parse(row.parseParams) : {} } catch { p = {} }
  for (const [field, key] of Object.entries(QUERY_KEYS)) {
    const v = q[key]
    if (v === undefined || v === '') continue
    f.q[field] = field === 'rerankModel' ? v : Number(v)
  }
  for (const [field, key] of Object.entries(PARSE_KEYS)) {
    const v = p[key]
    if (v === undefined || v === '') continue
    f.p[field] = field === 'structural' ? String(v) : Number(v)
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

// 全局配置缓存：占位符展示"继承的全局值" + 新建库预填解析模板（一次拉取，两处共用）
const globalCfg = ref({})
const loadGlobalCfg = async () => {
  if (Object.keys(globalCfg.value).length) return
  try {
    const r = await getConfig()
    globalCfg.value = (r && r.data) || {}
  } catch (e) { /* 拉取失败占位符退化为"继承" */ }
}
const gval = (group, key) => String(globalCfg.value[group]?.[key]?.value ?? '').trim()
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

/** 新建时用全局解析模板预填（对齐"全局设置是新建库默认模板"的语义；检索参数仍留空=继承） */
const prefillFromGlobal = async () => {
  try {
    await loadGlobalCfg()
    const d = globalCfg.value
    const get = (group, key) => d[group]?.[key]?.value
    const num = v => (v === undefined || v === null || v === '' ? null : Number(v))
    const p = form.value.p
    p.maxSize = num(get('chunk', 'maxSize'))
    p.overlap = num(get('chunk', 'overlap'))
    p.maxChunks = num(get('chunk', 'maxChunks'))
    p.maxImages = num(get('chunk', 'maxImages'))
    const structural = get('chunk', 'structural')
    p.structural = structural === undefined || structural === '' ? null : String(structural)
    p.structuralRatio = num(get('chunk', 'structuralRatio'))
    p.headingDepth = num(get('chunk', 'headingDepth'))
  } catch (e) { /* 预填失败不阻塞新建（留空=继承） */ }
}

const openCreate = () => {
  editing.value = null
  form.value = blank()
  showEdit.value = true
  loadGlobalCfg().then(() => prefillFromGlobal())
}

const openEdit = row => {
  editing.value = row
  form.value = hydrateForm(row)
  showEdit.value = true
  loadGlobalCfg()
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
      isDefault: form.value.isDefault ? 1 : 0
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
    message.error('保存失败')
  } finally {
    saving.value = false
  }
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
    message.error('删除失败')
  }
}

onMounted(() => { load(); loadGlobalCfg() })
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
.kb-param-grid { display: grid; grid-template-columns: 1fr 1fr; column-gap: 8px; }
.kb-param-grid :deep(.ant-form-item) { margin-bottom: 8px; }
</style>
