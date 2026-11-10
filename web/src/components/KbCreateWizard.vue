<template>
  <a-modal :open="open" title="新建知识库" :width="960" :footer="null"
           @update:open="v => emit('update:open', v)">
    <div class="wiz-steps">
      <span :class="['wiz-step', { on: step === 1, done: step > 1 }]"><i>1</i>选择场景</span>
      <span class="wiz-line"></span>
      <span :class="['wiz-step', { on: step === 2, done: step > 2 }]"><i>2</i>基础信息</span>
      <span class="wiz-line"></span>
      <span :class="['wiz-step', { on: step === 3 }]"><i>3</i>导入资料</span>
      <span class="sp"></span>
      <span class="wiz-hint">模板只预置解析/检索参数，不预置任何内容</span>
    </div>

    <div v-if="step === 1" class="wiz-body">
      <div class="wiz-q">这个库主要放什么资料？</div>
      <div class="wiz-d">模板只预置「解析与检索参数 + 示例问题」，不预置任何内容；选空白则全部走全局默认。</div>
      <div class="tpl-grid">
        <div v-for="t in TEMPLATES" :key="t.key" :class="['tpl', { on: template === t.key }]"
             @click="pick(t.key)">
          <span v-if="template === t.key" class="tpl-chk"><check-outlined /></span>
          <div class="tpl-icon" :style="{ background: t.bg, color: t.color }">
            <component :is="t.icon" />
          </div>
          <div class="tpl-name">{{ t.name }}</div>
          <div class="tpl-desc">{{ t.desc }}</div>
          <div class="tpl-meta">{{ t.meta }}</div>
        </div>
      </div>
      <div class="tpl-note">
        <bulb-outlined />
        <span>不确定选哪个？先选最接近的场景，建库后用「试检索」验证效果，再在设置里微调分块与阈值。</span>
      </div>
      <div v-if="checks.length" class="preset self-check">
        <div class="preset-head">
          <aim-outlined /> 建库后自检问题
          <a-tag color="default" style="margin:0">来自模板</a-tag>
        </div>
        <div v-for="c in checks" :key="c.q" class="check-row">
          <span class="check-q">{{ c.q }}</span>
          <b>预期命中：{{ c.expect }}</b>
        </div>
        <div class="check-note">建库后一键跑这 {{ checks.length }} 个问题，检索命中即说明参数可用。</div>
      </div>
    </div>

    <div v-else-if="step === 2" class="wiz-body wiz-form">
      <div class="wiz-left">
        <div class="field">
          <label>知识库名称 <em>*</em></label>
          <a-input v-model:value="form.name" placeholder="如：法律库" :maxlength="200" />
        </div>
        <div class="field">
          <label>一句话描述（智能体据此判断是否检索本库）</label>
          <a-input v-model:value="form.description" placeholder="例如：劳动合同、民法典与公司合规资料" :maxlength="500" />
        </div>
        <div class="field">
          <label>向量模型 <em>*</em></label>
          <ModelSelect v-model="form.embeddingRef" type="embedding" width="100%" :admin-tip-visible="true" />
          <div class="field-hint">必选；不同模型的向量不可混用，换模型会自动重嵌入。</div>
        </div>
        <div class="field-row">
          <a-checkbox v-model:checked="form.graphEnabled">开启知识图谱</a-checkbox>
          <span class="field-hint">开启后解析完成自动抽三元组，检索时可一跳扩展；可稍后在设置里改。</span>
        </div>
        <div v-if="form.graphEnabled" class="field">
          <label>图谱抽取模型</label>
          <ModelSelect v-model="form.graphModelRef" type="chat" width="100%"
                       inherit-label="跟随库主默认聊天模型" />
          <div class="field-hint">留空则用你的个人默认聊天模型；没有默认模型时请先选一个。</div>
        </div>
      </div>
      <div class="wiz-right">
        <div class="preset-head">
          <span>「{{ seededParams ? '复制原库' : currentTpl.name }}」会预置</span>
          <a-tag color="blue" style="margin:0">可修改</a-tag>
        </div>
        <div class="preset-row" v-for="p in presetRows" :key="p.k"><span>{{ p.k }}</span><b>{{ p.v }}</b></div>
        <div class="preset-tip">
          创建后可在「编辑设置 → 高级参数」逐项调整；模板不会写入文档内容。
        </div>
      </div>
    </div>

    <div v-else class="wiz-body wiz-import">
      <div class="wiz-left">
        <a-upload-dragger :before-upload="onImportFile" :show-upload-list="false" accept=".zip"
                          :disabled="saving || !!createdKb" style="margin-top:0">
          <p class="ant-upload-drag-icon"><inbox-outlined /></p>
          <p class="ant-upload-text">{{ importFile ? importFile.name : '点击或拖拽 zip 文档包到此处（可选）' }}</p>
          <p class="ant-upload-hint">不选也可以先创建空库，稍后从文档管理上传；新库没有同名冲突</p>
        </a-upload-dragger>
        <div class="import-note">
          导入后按本库参数重新解析、分块、向量化；解析在后台队列执行，可离开页面。
        </div>
        <!-- 清单预览：创建后才有库 id，故先创建再拉 manifest，冲突策略在开始导入前选择 -->
        <a-spin :spinning="previewing">
          <div v-if="previewError" class="import-err">{{ previewError }}</div>
          <div v-if="preview" class="import-preview">
            <div class="ip-head">
              共 <b>{{ preview.total }}</b> 个文档
              <span v-if="preview.conflicts" class="ip-warn">同名冲突 {{ preview.conflicts }}</span>
              <span v-else class="ip-ok">无同名冲突</span>
            </div>
            <div class="ip-list">
              <div v-for="d in (preview.documents || []).slice(0, 8)" :key="d.fileName" class="ip-row">
                <file-text-outlined class="ip-ic" />
                <span class="ip-name" :title="d.fileName">{{ d.fileName }}</span>
                <span v-if="d.exists" class="ip-tag">已存在</span>
                <span v-if="d.missingSourceFile" class="ip-tag err">源文件缺失</span>
              </div>
              <div v-if="(preview.documents || []).length > 8" class="ip-more">
                还有 {{ preview.documents.length - 8 }} 个文件…
              </div>
            </div>
            <div v-if="preview.conflicts" class="conflict-row">
              <span>同名冲突</span>
              <a-radio-group v-model:value="strategy" size="small">
                <a-radio-button value="replace">替换</a-radio-button>
                <a-radio-button value="skip">跳过</a-radio-button>
                <a-radio-button value="keepBoth">保留两份</a-radio-button>
              </a-radio-group>
            </div>
          </div>
        </a-spin>
        <div v-if="createdKb" class="selfcheck">
          <div class="sc-head">
            <check-circle-outlined class="sc-ic" /> 知识库已创建
            <span class="sp"></span>
            <a-button size="small" :loading="selfChecking" :disabled="!checks.length" @click="runSelfCheck">
              {{ selfCheck.length ? '重新自检' : `运行自检（${checks.length} 个问题）` }}
            </a-button>
          </div>
          <div v-if="!checks.length" class="sc-note">空白模板没有示例问题，可上传资料后在「试检索」里验证。</div>
          <div v-for="(r, i) in selfCheck" :key="i" class="sc-row">
            <span class="sc-q" :title="r.q">{{ r.q }}</span>
            <span :class="['sc-res', r.hit ? 'ok' : 'none']">{{ r.hit ? '命中' : '未命中' }}</span>
            <span class="sc-doc" :title="r.topDoc">{{ r.topDoc || '—' }}</span>
          </div>
          <div v-if="selfCheck.length" class="sc-note">
            命中说明参数可用；未命中可换关键词、补资料或调整阈值后重测。
          </div>
        </div>
        <div v-if="importResult" class="import-result">
          <div class="ir-head">
            导入结果：成功 {{ importResult.ok }} / {{ importResult.total }}
            <span v-if="importResult.failed" class="ir-fail">失败 {{ importResult.failed }}</span>
          </div>
          <div v-for="r in importResult.results" :key="r.fileName" class="ir-row">
            <check-circle-outlined v-if="r.success" class="ir-ok" />
            <close-circle-outlined v-else class="ir-err" />
            <span class="ir-name">{{ r.fileName }}</span>
            <span class="ir-msg">{{ r.msg || '已提交解析' }}</span>
          </div>
        </div>
      </div>
      <div class="wiz-right">
        <div class="preset-head">创建摘要</div>
        <div class="preset-row"><span>名称</span><b>{{ form.name || '—' }}</b></div>
        <div class="preset-row"><span>向量模型</span><b>{{ form.embeddingRef ? '已选择' : '未选择' }}</b></div>
        <div class="preset-row"><span>参数</span><b>{{ seededParams ? '复制原库' : currentTpl.name }}</b></div>
        <div class="preset-row"><span>知识图谱</span><b>{{ form.graphEnabled ? '开启' : '关闭' }}</b></div>
        <div class="preset-row"><span>资料</span><b>{{ importFile ? importFile.name : '先建空库' }}</b></div>
        <div class="preset-tip">
          {{ createdKb ? '知识库已创建，可运行自检或继续导入资料。' : '创建后可随时在「编辑设置 → 高级参数」里调整。' }}
        </div>
      </div>
    </div>

    <div class="wiz-foot">
      <span class="wiz-hint">预计 1 分钟完成</span>
      <span class="sp"></span>
      <a-button @click="emit('update:open', false)">取消</a-button>
      <a-button v-if="step > 1 && !createdKb" @click="step--">上一步</a-button>
      <a-button v-if="step === 1" type="primary" @click="step = 2">下一步</a-button>
      <a-button v-else-if="step === 2" type="primary" @click="step = 3">下一步：导入资料</a-button>
      <a-button v-else-if="createdKb && importFile && !importResult" type="primary"
                :loading="importing" :disabled="!preview" @click="startImport">
        开始导入（{{ preview?.total ?? '…' }} 个文档）
      </a-button>
      <a-button v-else-if="createdKb" type="primary" @click="emit('update:open', false)">完成</a-button>
      <a-button v-else type="primary" :loading="saving || importing" @click="create">
        {{ importFile ? '创建并预览清单' : '创建知识库' }}
      </a-button>
    </div>
  </a-modal>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  AimOutlined, BookOutlined, BulbOutlined, CheckCircleOutlined, CheckOutlined, CloseCircleOutlined,
  CodeOutlined, CustomerServiceOutlined, FileTextOutlined, InboxOutlined, PlusOutlined,
  SafetyCertificateOutlined
} from '@ant-design/icons-vue'
import ModelSelect from './ModelSelect.vue'
import {
  createKnowledgeBase, importKnowledgeBase, previewKnowledgeBaseImport, probeKnowledgeBase
} from '../api'

const props = defineProps({
  open: { type: Boolean, default: false },
  /** 复制为模板的来源库：预填名称/描述/参数/模型，进入第二步 */
  seed: { type: Object, default: null }
})
const emit = defineEmits(['update:open', 'created'])

const TEMPLATES = [
  {
    key: 'law', name: '法规制度', color: '#a3691b', bg: '#faf3e6',
    icon: SafetyCertificateOutlined, desc: '法条、合同、合规制度；按章节切分，保留条款编号',
    meta: '推荐：结构感知 + 父子分块',
    params: {
      query: { 'retrieval.vecThreshold': '0.5', 'retrieval.vectorWeight': '0.5', 'retrieval.keywordWeight': '0.5' },
      parse: { 'chunk.maxSize': '600', 'chunk.overlap': '60', 'chunk.structural': '1', 'parse.childEnabled': '1', 'parse.childSize': '300', 'parse.qaEnabled': '0' },
      graph: true
    }
  },
  {
    key: 'manual', name: '产品手册', color: '#3b8f4e', bg: '#eaf5ec',
    icon: FileTextOutlined, desc: '功能说明、版本日志；图文混排，按标题层级切分',
    meta: '推荐：标题识别 + 图片描述',
    params: {
      query: { 'retrieval.vecThreshold': '0.4', 'retrieval.vectorWeight': '0.6', 'retrieval.keywordWeight': '0.4' },
      parse: { 'chunk.maxSize': '800', 'chunk.overlap': '80', 'chunk.structural': '1', 'chunk.headingDepth': '4', 'parse.childEnabled': '0' },
      graph: false
    }
  },
  {
    key: 'contract', name: '合同协议', color: '#0f8a8a', bg: '#e6f4f4',
    icon: BookOutlined, desc: '合同模板、条款库；长条款不截断，保留上下文',
    meta: '推荐：大分块 + 低重叠',
    params: {
      query: { 'retrieval.vecThreshold': '0.5', 'retrieval.vectorWeight': '0.45', 'retrieval.keywordWeight': '0.55' },
      parse: { 'chunk.maxSize': '1000', 'chunk.overlap': '100', 'chunk.structural': '1', 'parse.childEnabled': '0' },
      graph: false
    }
  },
  {
    key: 'faq', name: '客服 FAQ', color: '#7c5cd6', bg: '#f0ecfb',
    icon: CustomerServiceOutlined, desc: '问答对、售后政策；短块优先，提升问答命中',
    meta: '推荐：问答对增强 + 小分块',
    params: {
      query: { 'retrieval.vecThreshold': '0.35', 'retrieval.vectorWeight': '0.7', 'retrieval.keywordWeight': '0.3' },
      parse: { 'chunk.maxSize': '300', 'chunk.overlap': '30', 'parse.qaEnabled': '1', 'parse.qaPerChunk': '2', 'parse.childEnabled': '1', 'parse.childSize': '200' },
      graph: false
    }
  },
  {
    key: 'dev', name: '研发文档', color: '#2e6be6', bg: '#eaf0fd',
    icon: CodeOutlined, desc: '架构、接口、复盘；代码块与表格保留原格式',
    meta: '推荐：代码感知 + 图谱开启',
    params: {
      query: { 'retrieval.vecThreshold': '0.4', 'retrieval.vectorWeight': '0.6', 'retrieval.keywordWeight': '0.4' },
      parse: { 'chunk.maxSize': '800', 'chunk.overlap': '100', 'chunk.structural': '1', 'chunk.headingDepth': '4', 'parse.childEnabled': '1', 'parse.childSize': '400' },
      graph: true
    }
  },
  {
    key: 'blank', name: '空白知识库', color: '#5f6570', bg: '#eef0f3',
    icon: PlusOutlined, desc: '不预置任何参数，全部继承系统与个人默认值',
    meta: '适合已经清楚每个参数的人',
    params: { query: {}, parse: {}, graph: false }
  }
]

const step = ref(1)
const template = ref('law')
const saving = ref(false)
const importing = ref(false)
const importFile = ref(null)
const importResult = ref(null)
const createdKb = ref(null)
const seededParams = ref(null)
const preview = ref(null)
const previewing = ref(false)
const previewError = ref('')
const strategy = ref('replace')
const selfCheck = ref([])
const selfChecking = ref(false)
const form = reactive({ name: '', description: '', embeddingRef: '', graphEnabled: false, graphModelRef: '' })

/** 建库后自检问题：模板只给问题与预期命中，不写任何内容；建库后逐条跑试检索验证参数 */
const SELF_CHECKS = {
  law: [
    { q: '试用期最长可以约定多久？', expect: '劳动合同法 · 第十九条' },
    { q: '竞业限制补偿金怎么计算？', expect: '司法解释 · 第 36 条' },
    { q: '员工手册对远程办公怎么规定？', expect: '员工手册 · 2.3' }
  ],
  manual: [
    { q: '如何升级到最新版本？', expect: '版本日志 · 升级说明' },
    { q: '支持哪些文件格式？', expect: '功能说明 · 支持格式' },
    { q: '接口报错怎么排查？', expect: '常见问题 · 错误码' }
  ],
  contract: [
    { q: '合同违约如何赔偿？', expect: '违约责任 · 第 8 条' },
    { q: '保密期限一般是多久？', expect: '保密条款 · 第 5 条' },
    { q: '合同解除的条件有哪些？', expect: '合同解除 · 第 9 条' }
  ],
  faq: [
    { q: '退换货政策是什么？', expect: '售后政策 · 退换货' },
    { q: '多久能发货？', expect: '配送说明 · 时效' },
    { q: '如何申请发票？', expect: '发票与报销' }
  ],
  dev: [
    { q: '鉴权接口怎么调用？', expect: '接口文档 · 鉴权' },
    { q: '服务上线流程是什么？', expect: '发布流程 · 上线' },
    { q: '数据库变更如何审批？', expect: '研发规范 · 变更审批' }
  ],
  blank: []
}
const checks = computed(() => SELF_CHECKS[template.value] || [])

const currentTpl = computed(() => TEMPLATES.find(t => t.key === template.value) || TEMPLATES[0])
const presetRows = computed(() => {
  if (seededParams.value) {
    return [
      { k: '检索参数', v: paramSummary(seededParams.value.query) },
      { k: '解析参数', v: paramSummary(seededParams.value.parse) },
      { k: '知识图谱', v: form.graphEnabled ? '开（复制原库）' : '关' }
    ]
  }
  const p = currentTpl.value.params
  const rows = []
  if (p.parse['chunk.maxSize']) rows.push({ k: '分块大小', v: p.parse['chunk.maxSize'] + ' 字符' })
  if (p.parse['chunk.overlap']) rows.push({ k: '分块重叠', v: p.parse['chunk.overlap'] + ' 字符' })
  if (p.parse['chunk.structural']) rows.push({ k: '结构感知切分', v: '开（保留条款层级）' })
  if (p.parse['parse.childEnabled'] === '1') rows.push({ k: '父子分块', v: '开（子块 ' + (p.parse['parse.childSize'] || '300') + '）' })
  if (p.parse['parse.qaEnabled'] === '1') rows.push({ k: '问答对增强', v: '开' })
  if (p.query['retrieval.vecThreshold']) rows.push({ k: '向量阈值', v: p.query['retrieval.vecThreshold'] })
  if (p.graph) rows.push({ k: '知识图谱', v: '开（自动抽三元组）' })
  if (checks.value.length) rows.push({ k: '示例问题', v: `${checks.value.length} 条（建库后自检）` })
  if (!rows.length) rows.push({ k: '参数', v: '全部继承默认' })
  return rows
})

watch(() => props.open, v => {
  if (!v) return
  step.value = 1
  template.value = 'law'
  form.name = ''
  form.description = ''
  form.embeddingRef = ''
  form.graphEnabled = false
  form.graphModelRef = ''
  importFile.value = null
  importResult.value = null
  createdKb.value = null
  seededParams.value = null
  preview.value = null
  previewError.value = ''
  strategy.value = 'replace'
  selfCheck.value = []
  selfChecking.value = false
  if (props.seed) {
    // 复制为模板：直接进第二步，参数按原库 JSON 原样带过去
    step.value = 2
    template.value = 'blank'
    form.name = `${props.seed.name || '知识库'} 副本`
    form.description = props.seed.description || ''
    form.embeddingRef = props.seed.embeddingRef || ''
    form.graphEnabled = props.seed.graphEnabled === 1
    form.graphModelRef = props.seed.graphModelRef || ''
    seededParams.value = { query: props.seed.queryParams || null, parse: props.seed.parseParams || null }
  } else {
    pick('law')
  }
})

function pick (key) {
  seededParams.value = null
  template.value = key
  const t = TEMPLATES.find(x => x.key === key)
  if (t && key !== 'blank') {
    form.name = t.name
    form.description = t.desc
    form.graphEnabled = !!t.params.graph
  } else {
    form.name = ''
    form.description = ''
    form.graphEnabled = false
  }
}

function onImportFile (file) {
  if (!/\.zip$/i.test(file.name)) { message.warning('只支持知识库导出 zip 包'); return false }
  importFile.value = file
  importResult.value = null
  preview.value = null
  previewError.value = ''
  strategy.value = 'replace'
  return false
}

function paramSummary (raw) {
  if (!raw) return '继承默认'
  try {
    const n = Object.keys(JSON.parse(raw) || {}).length
    return n ? `${n} 项覆盖` : '继承默认'
  } catch (e) {
    return '已自定义'
  }
}

async function create () {
  if (createdKb.value) return
  if (!form.name.trim()) { message.warning('请填写知识库名称'); return }
  if (!form.embeddingRef) { message.warning('请选择向量模型'); return }
  saving.value = true
  try {
    const t = seededParams.value || currentTpl.value.params
    const queryParams = typeof t.query === 'string' ? t.query : (Object.keys(t.query || {}).length ? JSON.stringify(t.query) : undefined)
    const parseParams = typeof t.parse === 'string' ? t.parse : (Object.keys(t.parse || {}).length ? JSON.stringify(t.parse) : undefined)
    const r = await createKnowledgeBase({
      name: form.name.trim(),
      description: form.description.trim() || undefined,
      embeddingRef: form.embeddingRef,
      queryParams,
      parseParams,
      graphEnabled: form.graphEnabled ? 1 : 0,
      graphModelRef: form.graphEnabled ? (form.graphModelRef || undefined) : undefined
    })
    if (r.success === false) { message.error(r.msg || '创建失败'); return }
    const kb = r.data
    createdKb.value = kb
    message.success('知识库已创建')
    emit('created', kb)
    // 有 zip：先拉导入清单给用户确认冲突策略；没有 zip：留在向导里跑建库后自检
    if (importFile.value) await loadPreview()
  } catch (e) {
    message.error(e.message || '创建失败')
  } finally {
    saving.value = false
  }
}

async function loadPreview () {
  if (!createdKb.value?.id || !importFile.value) return
  previewing.value = true
  previewError.value = ''
  try {
    const r = await previewKnowledgeBaseImport(createdKb.value.id, importFile.value)
    if (r.success === false) { previewError.value = r.msg || '读取导出包失败'; return }
    preview.value = r.data || null
    if (preview.value?.conflicts) strategy.value = 'replace'
  } catch (e) {
    preview.value = null
    previewError.value = e.message || '读取导出包失败'
  } finally {
    previewing.value = false
  }
}

async function startImport () {
  if (!createdKb.value?.id || !importFile.value || importing.value) return
  importing.value = true
  try {
    const ir = await importKnowledgeBase(createdKb.value.id, importFile.value, strategy.value)
    if (ir.success === false) {
      message.error(ir.msg || '导入失败，可稍后到文档管理重试')
      return
    }
    importResult.value = ir.data || null
    if (!importResult.value?.failed) message.success('创建并导入完成')
    else message.warning(`导入完成：成功 ${importResult.value.ok}/${importResult.value.total}`)
  } catch (e) {
    message.error(e.message || '导入失败，可稍后到文档管理重试')
  } finally {
    importing.value = false
  }
}

/** 建库后自检：逐条走试检索（不调聊天模型），只回报命中与首条来源 */
async function runSelfCheck () {
  if (!createdKb.value?.id || !checks.value.length || selfChecking.value) return
  selfChecking.value = true
  const rows = []
  try {
    for (const c of checks.value) {
      let hit = false
      let topDoc = ''
      try {
        const r = await probeKnowledgeBase(createdKb.value.id, { query: c.q, topK: 3, rerank: true })
        const d = r.data || {}
        hit = d.verdict === 'hit'
        const h = (d.hits || [])[0]
        topDoc = h ? [h.docName, h.titlePath || h.title].filter(Boolean).join(' · ') : ''
      } catch (e) {
        // 单条失败不阻断其余问题；结果行按未命中展示
      }
      rows.push({ q: c.q, expect: c.expect, hit, topDoc })
    }
    selfCheck.value = rows
    const hitCount = rows.filter(x => x.hit).length
    if (hitCount) message.success(`自检完成：${hitCount}/${rows.length} 个问题命中`)
    else message.warning('自检完成：暂未命中，确认资料解析完成或调整阈值后重测')
  } finally {
    selfChecking.value = false
  }
}
</script>

<style scoped>
.wiz-steps { display: flex; align-items: center; gap: 8px; padding: 0 0 12px; border-bottom: 1px solid var(--app-border); }
.wiz-step { display: inline-flex; align-items: center; gap: 7px; font-size: 12px; color: var(--app-text3); }
.wiz-step i { width: 20px; height: 20px; border-radius: 50%; border: 1px solid var(--app-border-strong); background: #fff;
  display: inline-flex; align-items: center; justify-content: center; font-style: normal; font-size: 10.5px; font-weight: 600; }
.wiz-step.on { color: var(--app-text); font-weight: 600; }
.wiz-step.on i { background: var(--app-accent); border-color: var(--app-accent); color: #fff; }
.wiz-step.done i { background: var(--app-ok); border-color: var(--app-ok); color: #fff; }
.wiz-line { width: 34px; height: 1px; background: var(--app-border-strong); }
.wiz-steps .sp, .wiz-foot .sp { flex: 1; }
.wiz-hint { font-size: 11px; color: var(--app-text3); }
.wiz-body { padding: 14px 0 6px; }
.tpl-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.tpl { border: 1px solid var(--app-border); border-radius: 9px; padding: 12px; cursor: pointer; position: relative;
  background: #fff; transition: border-color .15s, box-shadow .15s; }
.tpl:hover { border-color: var(--app-accent-border); }
.tpl.on { border-color: var(--app-accent); box-shadow: 0 0 0 3px rgba(46,107,230,.09); }
.tpl-chk { position: absolute; top: 9px; right: 9px; width: 16px; height: 16px; border-radius: 50%;
  background: var(--app-accent); color: #fff; font-size: 10px; display: inline-flex; align-items: center; justify-content: center; }
.tpl-icon { width: 28px; height: 28px; border-radius: 7px; display: inline-flex; align-items: center; justify-content: center; }
.tpl-name { font-size: 13px; font-weight: 600; margin-top: 8px; }
.tpl-desc { font-size: 11px; color: var(--app-text3); line-height: 1.55; margin-top: 3px; min-height: 34px; }
.tpl-meta { font-size: 10.5px; color: var(--app-text3); margin-top: 4px; }
.tpl-note { display: flex; gap: 8px; align-items: flex-start; border: 1px dashed var(--app-border-strong);
  border-radius: 8px; padding: 8px 10px; margin-top: 10px; font-size: 11px; color: var(--app-text3); line-height: 1.6; }
.tpl-note :deep(.anticon) { margin-top: 2px; color: var(--app-accent); }
.wiz-q { font-size: 13px; font-weight: 600; color: var(--app-text); margin-bottom: 4px; }
.wiz-d { font-size: 11px; color: var(--app-text3); line-height: 1.6; margin-bottom: 10px; }
.self-check { border: 1px solid var(--app-border); border-radius: 9px; background: var(--app-panel-2);
  padding: 10px 12px; margin-top: 10px; }
.self-check .preset-head { display: flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600;
  color: var(--app-text2); margin-bottom: 6px; }
.self-check .preset-head :deep(.anticon) { color: var(--app-accent); }
.check-row { display: flex; align-items: center; gap: 10px; padding: 5px 0; border-bottom: 1px dashed var(--app-border);
  font-size: 11.5px; color: var(--app-text2); }
.check-row b { margin-left: auto; font-weight: 500; color: var(--app-text3); font-size: 10.5px; }
.check-note { font-size: 10.5px; color: var(--app-text3); line-height: 1.6; margin-top: 5px; }
.wiz-form { display: flex; gap: 24px; }
.wiz-left { flex: 1; min-width: 0; }
.wiz-right { width: 300px; flex: none; background: var(--app-panel-2); border: 1px solid var(--app-border);
  border-radius: 9px; padding: 12px 14px; }
.field { margin-bottom: 14px; }
.field label { display: block; font-size: 12px; color: var(--app-text2); margin-bottom: 5px; }
.field label em { color: var(--app-danger); font-style: normal; }
.field-hint { font-size: 10.5px; color: var(--app-text3); line-height: 1.6; margin-top: 4px; }
.field-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.preset-head { display: flex; align-items: center; gap: 8px; font-size: 12px; font-weight: 600; color: var(--app-text2);
  margin-bottom: 8px; }
.preset-row { display: flex; align-items: center; font-size: 11.5px; color: var(--app-text3); padding: 5px 0;
  border-bottom: 1px dashed var(--app-border); }
.preset-row b { margin-left: auto; color: var(--app-text2); font-weight: 500; }
.preset-tip { margin-top: 9px; font-size: 10.5px; color: var(--app-text3); line-height: 1.6; }
.wiz-foot { display: flex; align-items: center; gap: 8px; margin-top: 12px; padding-top: 12px; }
.wiz-import { display: flex; gap: 24px; }
.wiz-import .wiz-left { flex: 1; min-width: 0; }
.wiz-import .wiz-right { width: 300px; flex: none; background: var(--app-panel-2); border: 1px solid var(--app-border);
  border-radius: 9px; padding: 12px 14px; }
.import-note { font-size: 10.5px; color: var(--app-text3); line-height: 1.6; margin-top: 8px; }
.import-err { margin-top: 8px; font-size: 11.5px; color: var(--app-danger); }
.import-preview { margin-top: 10px; border: 1px solid var(--app-border); border-radius: 8px; overflow: hidden; }
.ip-head { display: flex; align-items: center; gap: 10px; padding: 8px 10px; background: var(--app-panel-2);
  font-size: 11.5px; color: var(--app-text3); }
.ip-head b { color: var(--app-text); }
.ip-warn { color: var(--app-warn-text); }
.ip-ok { color: var(--app-ok); }
.ip-list { max-height: 180px; overflow-y: auto; }
.ip-row { display: flex; align-items: center; gap: 7px; padding: 5px 10px; border-top: 1px solid var(--app-border);
  font-size: 11px; }
.ip-ic { color: var(--app-text3); flex: none; }
.ip-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--app-text); }
.ip-tag { flex: none; font-size: 9.5px; color: var(--app-warn-text); background: var(--app-warn-weak);
  border-radius: 3px; padding: 1px 5px; }
.ip-tag.err { color: var(--app-danger); background: #fff2f0; }
.ip-more { padding: 6px 10px; border-top: 1px solid var(--app-border); font-size: 10.5px; color: var(--app-text3); }
.conflict-row { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border-top: 1px solid var(--app-border);
  background: #fffdf7; font-size: 11.5px; color: var(--app-text2); }
.selfcheck { margin-top: 10px; border: 1px solid var(--app-border); border-radius: 8px; padding: 10px 12px; }
.sc-head { display: flex; align-items: center; gap: 7px; font-size: 12px; font-weight: 600; color: var(--app-text2); }
.sc-head .sp { flex: 1; }
.sc-ic { color: var(--app-ok); }
.sc-note { font-size: 10.5px; color: var(--app-text3); line-height: 1.6; margin-top: 6px; }
.sc-row { display: flex; align-items: center; gap: 8px; padding: 6px 0; border-bottom: 1px dashed var(--app-border);
  font-size: 11px; }
.sc-q { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--app-text2); }
.sc-res { flex: none; font-size: 10px; border-radius: 999px; padding: 1px 7px; }
.sc-res.ok { color: var(--app-ok); background: var(--app-ok-weak); }
.sc-res.none { color: var(--app-warn-text); background: var(--app-warn-weak); }
.sc-doc { flex: none; max-width: 150px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-size: 10px; color: var(--app-text3); }
.import-result { margin-top: 12px; border: 1px solid var(--app-border); border-radius: 8px; overflow: hidden; }
.ir-head { display: flex; align-items: center; gap: 10px; padding: 8px 10px; background: var(--app-panel-2);
  font-size: 11.5px; color: var(--app-text2); }
.ir-fail { color: var(--app-danger); }
.ir-row { display: flex; align-items: center; gap: 7px; padding: 6px 10px; border-top: 1px solid var(--app-border);
  font-size: 11px; }
.ir-ok { color: var(--app-ok); }
.ir-err { color: var(--app-danger); }
.ir-name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--app-text); }
.ir-msg { margin-left: auto; font-size: 10.5px; color: var(--app-text3); }
</style>
