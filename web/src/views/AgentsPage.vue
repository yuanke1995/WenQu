<template>
  <div class="app-page">
    <!-- ==================== 列表视图 ==================== -->
    <template v-if="!editing">
      <div class="app-page-head">
        <h1 class="app-page-title">智能体</h1>
        <span class="ap-count">共 {{ agents.length }} 个</span>
        <div class="ap-head-r">
          <a-input v-model:value="keyword" class="ap-search" size="small" allow-clear placeholder="搜索名称或描述">
            <template #prefix><search-outlined class="ap-search-ic" /></template>
          </a-input>
          <a-tooltip title="刷新">
            <button class="app-icon-btn" :disabled="loading" aria-label="刷新列表" @click="reload"><reload-outlined /></button>
          </a-tooltip>
          <button class="app-btn small" @click="openCreate">新建智能体</button>
        </div>
      </div>

      <div class="app-page-body">
        <div v-if="loading" class="ap-empty"><a-spin size="small" /></div>

        <div v-else-if="!agents.length" class="ap-empty">
          <div class="ap-empty-t">还没有智能体</div>
          <div class="ap-empty-d">
            一个智能体就是一组预设：选好模型、写好角色提示词、圈定知识库范围、按需开启能力。
            对话时在输入框上方切换，这一轮问答就按它的配置走；没配置的维度沿用系统设置。
          </div>
          <button class="app-btn small" @click="openCreate">新建第一个智能体</button>
        </div>

        <div v-else-if="!sections.length" class="ap-empty">
          <div class="ap-empty-t">没有匹配的智能体</div>
          <div class="ap-empty-d">没有名称或描述包含「{{ keyword }}」的智能体。</div>
          <button class="app-btn ghost small" @click="keyword = ''">清除搜索</button>
        </div>

        <!-- 子智能体不再与主智能体混排：主智能体一组，子智能体单独一组 -->
        <div v-else class="ap-groups">
          <section v-for="sec in sections" :key="sec.key" class="ap-group">
            <!-- 只剩主智能体一组时不显示组头，保持与旧版一致 -->
            <div v-if="sections.length > 1 || sec.key !== 'main'" class="ap-group-head">
              <span class="ap-group-title">{{ sec.title }}</span>
              <span v-if="sec.hint" class="ap-group-hint">{{ sec.hint }}</span>
              <span class="ap-group-count">{{ sec.members.length }}</span>
            </div>
            <div class="ap-grid">
              <article v-for="a in sec.members" :key="sec.key + '-' + a.id" class="ap-card" @click="openEdit(a)">
                <div class="ap-card-head">
                  <span class="ap-avatar"><robot-outlined /></span>
                  <span class="ap-name" :title="a.name">{{ a.name }}</span>
                  <span v-if="isDefault(a) || isBuiltin(a)" class="ap-card-tags">
                    <span v-if="isDefault(a)" class="ap-tag-default">默认</span>
                    <span v-if="isBuiltin(a)" class="ap-tag-builtin" title="系统内置，不可删除">内置</span>
                  </span>
                </div>
                <p class="ap-desc" :title="a.description || ''">{{ a.description || '未填写描述' }}</p>
                <div class="ap-chips">
                  <span class="ap-chip">{{ scopeText(a) }}</span>
                  <span v-for="c in capsForcedOn(a)" :key="c" class="ap-chip ap-chip-on">{{ c }}</span>
                  <span v-if="scopeLabel(a)" class="ap-chip ap-chip-warn" title="已限制共享范围，点「共享」查看或修改">{{ scopeLabel(a) }}</span>
                </div>
                <div class="ap-card-foot">
                  <button class="app-link-btn" @click.stop="openEdit(a)">配置</button>
                  <button class="app-link-btn" @click.stop="openShare(a)">共享</button>
                  <button v-if="!isSub(a) && !isDefault(a)" class="app-link-btn" @click.stop="doSetDefault(a.id)">设为默认</button>
                  <!-- 内置智能体不提供删除入口（后端也会拒绝），避免出现"点了报错"的死路 -->
                  <a-popconfirm v-if="!isBuiltin(a)" title="删除该智能体？对话页将不再可选" ok-text="删除" cancel-text="取消" @confirm="doDelete(a.id)">
                    <button class="app-link-btn danger" @click.stop>删除</button>
                  </a-popconfirm>
                  <span v-else class="ap-builtin-hint">系统内置</span>
                </div>
              </article>
            </div>
          </section>
        </div>
      </div>
    </template>

    <!-- ==================== 配置视图（独立整页，不用弹窗） ==================== -->
    <template v-else>
      <div class="app-page-head">
        <button class="app-icon-btn" title="返回列表" aria-label="返回列表" @click="closeEdit"><arrow-left-outlined /></button>
        <h1 class="app-page-title">{{ editingId ? '配置智能体' : '新建智能体' }}</h1>
        <span v-if="editingId" class="ap-count">{{ form.name || '未命名' }}</span>
        <div class="ap-head-r">
          <button class="app-btn ghost small" @click="closeEdit">取消</button>
          <button class="app-btn small" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </div>

      <div class="app-page-body">
        <!-- 生效摘要：随表单实时变化，改完一眼知道最终结果 -->
        <div class="ap-summary">
          <span class="ap-summary-avatar"><robot-outlined /></span>
          <span class="ap-summary-name">{{ form.name || '未命名智能体' }}</span>
          <span class="ap-summary-sep">·</span>
          <span class="ap-summary-item">{{ summaryScope }}</span>
          <span class="ap-summary-sep">·</span>
          <span class="ap-summary-item" :class="{ 'is-accent': capsTouched }">{{ summaryCaps }}</span>
        </div>

        <a-form layout="vertical" class="ap-form">
          <section class="app-card">
            <h2 class="app-card-title"><idcard-outlined class="ap-sec-ic" />身份</h2>
            <p class="ap-block-hint">对话页下拉里展示的就是名称与描述，写清楚它适合什么场景。</p>
            <p class="ap-block-hint" style="margin:0 0 8px">
              描述还是「自动派遣」的路由依据：用户开启自动派遣时，系统按名称+描述把每条问题派给最合适的智能体——
              各智能体的职责要互不重叠，重叠会导致派错。
            </p>
            <a-form-item label="用途">
              <a-radio-group v-model:value="form.isSubagent">
                <a-radio-button :value="0">主智能体</a-radio-button>
                <a-radio-button :value="1">子智能体</a-radio-button>
              </a-radio-group>
              <div class="ap-block-hint" style="margin: 6px 0 0">
                主智能体可在对话页直接选用；子智能体不能直接选用，只能被主智能体委派去查资料。
              </div>
            </a-form-item>
            <a-form-item label="名称" required>
              <a-input v-model:value="form.name" :maxlength="200" placeholder="如：合同审查助手 / 运维排障 / 产品 FAQ" />
            </a-form-item>
            <a-form-item label="描述" style="margin-bottom:0">
              <a-textarea v-model:value="form.description" :maxlength="500" :rows="2"
                          placeholder="写清职责范围与典型问题（如：负责《操作手册》的界面操作与表单填写问题），自动派遣将按描述把用户问题路由到本智能体" />
            </a-form-item>
          </section>

          <section class="app-card">
            <h2 class="app-card-title"><thunderbolt-outlined class="ap-sec-ic" />提示词</h2>
            <p class="ap-block-hint">智能体不再绑定聊天模型：回答用哪套模型由用户在对话页选择或个人设置默认。</p>
            <a-form-item label="系统提示词" style="margin-bottom:0">
              <a-textarea v-model:value="form.systemPrompt" :rows="6"
                          placeholder="填写后完全替换全局系统提示词；留空沿用全局" />
            </a-form-item>
          </section>

          <section class="app-card">
            <h2 class="app-card-title"><database-outlined class="ap-sec-ic" />知识库范围</h2>
            <p class="ap-block-hint">限定这个智能体能检索到的内容：选择它允许使用的「知识库」（文档归属哪个库，在「文档管理」里设置）。</p>
            <a-radio-group v-model:value="scopeMode">
              <a-radio-button value="all">全部知识库</a-radio-button>
              <a-radio-button value="pick">指定知识库</a-radio-button>
              <a-radio-button value="none">不使用知识库</a-radio-button>
            </a-radio-group>
            <div v-if="scopeMode === 'pick'" class="ap-pick">
              <a-select v-model:value="form.knowledgeBaseIds" mode="multiple" :options="kbOptions" allow-clear
                        show-search option-filter-prop="label" :max-tag-count="6" style="width:100%"
                        placeholder="选择允许检索的知识库" />
              <div class="ap-block-hint" style="margin:6px 0 0">
                已选 {{ form.knowledgeBaseIds.length }} 个知识库；一个都不选则该智能体检索不到任何内容。
                选定后还可在下方「检索参数」覆盖该库的策略（留空即用知识库自己的配置）。
              </div>
            </div>
            <div v-else-if="scopeMode === 'none'" class="ap-block-hint" style="margin:8px 0 0">
              纯角色智能体：完全不走资料检索，仅凭系统提示词与对话上下文作答。
              适合通用法律顾问、写作助手这类不挂资料的场景；对话中手动 @ 的文档仍会被参考。
            </div>
          </section>

          <section class="app-card">
            <h2 class="app-card-title"><control-outlined class="ap-sec-ic" />能力</h2>
            <p class="ap-block-hint">
              默认全部沿用系统设置里的开关；只有需要为这个智能体单独破例时，才把某一项改成「开启」或「关闭」。
            </p>
            <div class="ap-caps">
              <div v-for="c in CAPS" :key="c.key" class="ap-cap-block">
                <div class="ap-cap" :class="{ overridden: capOverridden(c) }">
                  <span class="ap-cap-ic"><component :is="c.icon" /></span>
                  <div class="ap-cap-l">
                    <div class="ap-cap-name">
                      {{ c.label }}
                      <span v-if="capOverridden(c)" class="ap-cap-badge">已覆盖</span>
                    </div>
                    <div class="ap-cap-desc">
                      {{ c.desc }}<span class="ap-cap-global"> · 全局{{ globalText(c) }}</span>
                    </div>
                  </div>
                  <a-segmented v-if="c.kind === 'switch'" v-model:value="form[c.key]" :options="SEG" size="small" />
                  <a-segmented v-else v-model:value="form[c.modeKey]" :options="SEG_MULTI" size="small" />
                </div>
                <!-- 「指定」模式：展开具体项多选（对齐通用智能体平台的 skills / mcps 资源列表） -->
                <div v-if="needsPick(c)" class="ap-cap-pick">
                  <a-select v-model:value="form[c.listKey]" mode="multiple" :options="optionsOf(c)" allow-clear
                            size="small" style="width:100%"
                            :placeholder="'选择' + c.label + '（一项都不选则等同「不使用」）'" />
                  <div v-if="!optionsOf(c).length" class="ap-pick-empty">
                    当前系统里没有可选项 —— 需先在设置页配置{{ c.label }}
                  </div>
                </div>
              </div>
            </div>
          </section>

          <section class="app-card" v-if="!form.isSubagent">
            <h2 class="app-card-title"><apartment-outlined class="ap-sec-ic" />子智能体委派</h2>
            <p class="ap-block-hint">
              选中后，复杂问题会并行交给这些子智能体各自检索——各按自己的知识库范围与角色视角，再汇总作答。
              不选则沿用系统的多视角并行检索。
            </p>
            <a-select v-model:value="form.subAgentIds" mode="multiple" :options="subOptions" allow-clear
                      show-search option-filter-prop="label" :max-tag-count="6" style="width:100%"
                      :placeholder="subOptions.length ? '选择允许委派的子智能体（最多 4 个）'
                        : '还没有子智能体——先在列表新建一个「用途 = 子智能体」的条目'" />
          </section>

          <section class="app-card" v-if="!form.isSubagent">
            <h2 class="app-card-title"><control-outlined class="ap-sec-ic" />检索参数</h2>
            <p class="ap-block-hint">
              留空即继承「系统设置 → 检索设置」；只填需要为这个智能体单独调整的项
              （例如法律类助手提高相似度阈值保精度、操作手册助手放宽阈值保召回）。
            </p>
            <div class="qp-grid">
              <label v-for="f in QP_FIELDS" :key="f.key" class="qp-item">
                <span class="qp-label">{{ f.label }}</span>
                <a-input v-model:value="form.qp[f.key]" :placeholder="f.ph" allow-clear />
              </label>
              <label class="qp-item">
                <span class="qp-label">重排服务</span>
                <a-segmented v-model:value="form.qpRerank"
                             :options="[{ label: '跟随全局', value: 'inherit' }, { label: '开启', value: 'on' }, { label: '关闭', value: 'off' }]" />
              </label>
            </div>
          </section>

          <section class="app-card" v-if="!form.isSubagent">
            <h2 class="app-card-title"><star-outlined class="ap-sec-ic" />默认</h2>
            <a-checkbox v-model:checked="form.isDefault">设为默认智能体</a-checkbox>
            <span class="ap-block-hint" style="margin-left:8px">对话页打开时预选它（同一时间只有一个默认）</span>
          </section>
        </a-form>
      </div>
    </template>

    <!-- 共享范围（公共组件：与文档 / API Key 同一套两区表单） -->
    <ShareScopeModal v-model:open="shareVisible" resource-label="智能体" read-verb="使用"
                     :share-config="shareTarget.shareConfig" :save-fn="saveShareFn" @saved="reload" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  ArrowLeftOutlined, ReloadOutlined, SearchOutlined, RobotOutlined, IdcardOutlined,
  ThunderboltOutlined, DatabaseOutlined, ControlOutlined, StarOutlined, ApartmentOutlined,
  FileSearchOutlined, CalculatorOutlined, FileDoneOutlined, AppstoreOutlined, ApiOutlined
} from '@ant-design/icons-vue'
import { listAgents, createAgent, updateAgent, deleteAgent, setAgentDefault, listKnowledgeBases, getConfig,
         listSkills, getMcpStatus, listSubAgents, updateAgentShare } from '../api'
import ShareScopeModal from './ShareScopeModal.vue'
import ProviderIcon from '../components/ProviderIcon.vue'

// ==================== 能力定义 ====================
// path：该能力在全局配置里的开关路径；gate：还受此总闸制约（关掉总闸时能力不生效）
const CAPS = [
  { key: 'toolKnowledge', label: '知识库检索', desc: '回答过程中可自主检索知识库补充依据', icon: FileSearchOutlined,
    kind: 'switch', path: ['tool', 'knowledgeRetrieval', 'enabled'], gate: ['tool', 'enabled'] },
  // 以下三项是「多实例能力」：除总开关外，还能指定具体用哪几个（对齐通用智能体平台的资源列表语义）
  { key: 'toolBuiltin', label: '内置高频工具', desc: '算术计算 / 当前时间 / 日期相差天数', icon: CalculatorOutlined,
    kind: 'list', modeKey: 'builtinMode', listKey: 'builtinTools', optionsKey: 'builtinOptions',
    path: ['tool', 'builtin', 'enabled'], gate: ['tool', 'enabled'] },
  { key: 'toolArtifact', label: '产物交付', desc: '生成 Markdown / CSV / JSON / HTML 文件并附下载卡片', icon: FileDoneOutlined,
    kind: 'switch', path: ['tool', 'artifact', 'enabled'], gate: ['tool', 'enabled'] },
  { key: 'toolSkill', label: '技能 Skills', desc: '注入技能清单，模型可按需读取技能全文', icon: AppstoreOutlined,
    kind: 'list', modeKey: 'skillMode', listKey: 'skills', optionsKey: 'skillOptions',
    path: ['skill', 'enabled'] },
  { key: 'toolMcp', label: 'MCP 外部工具', desc: '连接外部 MCP Server，把它的工具交给模型', icon: ApiOutlined,
    kind: 'list', modeKey: 'mcpMode', listKey: 'mcps', optionsKey: 'mcpOptions',
    path: ['mcp', 'enabled'] }
]
// 开关型三态：''=跟随全局 / '1'=开启 / '0'=关闭（对应后端 tool_* 的 1/0/null）
const SEG = [
  { label: '跟随全局', value: '' },
  { label: '开启', value: '1' },
  { label: '关闭', value: '0' }
]
// 多实例能力：跟随全局 / 不使用 / 指定（选「指定」才展开具体项多选）
const SEG_MULTI = [
  { label: '跟随全局', value: 'inherit' },
  { label: '不使用', value: 'none' },
  { label: '指定', value: 'pick' }
]
// 内置工具可选项（value 与后端 BuiltinTools 的 @Tool 方法名一致，按名匹配注册）
const BUILTIN_TOOL_OPTIONS = [
  { value: 'calculate', label: '算术计算' },
  { value: 'currentDateTime', label: '当前日期时间' },
  { value: 'daysBetween', label: '日期相差天数' }
]

const loading = ref(false)
const saving = ref(false)
const keyword = ref('')
const agents = ref([])
const kbOptions = ref([])
const cfg = ref({})

// 多实例能力的可选项：内置工具（前端常量）/ 技能 / MCP Server（后两者来自接口）
const builtinOptions = ref(BUILTIN_TOOL_OPTIONS)
const skillOptions = ref([])
const mcpOptions = ref([])
// 可委派的子智能体下拉（4.3：来自 /agent/sub，仅作为主智能体的委派候选，本身不参与对话）
const subOptions = ref([])
const OPTION_REFS = { builtinOptions, skillOptions, mcpOptions }
const optionsOf = c => OPTION_REFS[c.optionsKey]?.value || []

const editing = ref(false)
const editingId = ref('')
const scopeMode = ref('all')
const blankForm = () => ({
  name: '', description: '', systemPrompt: '', knowledgeBaseIds: [], isDefault: false,
  // 开关型：'' = 跟随全局 / '1' = 开启 / '0' = 关闭
  toolKnowledge: '', toolBuiltin: '', toolSkill: '', toolArtifact: '', toolMcp: '',
  // 多实例能力：模式（inherit/none/pick）+ 选「指定」时的具体项
  builtinMode: 'inherit', builtinTools: [],
  skillMode: 'inherit', skills: [],
  mcpMode: 'inherit', mcps: [],
  // 新增时必须给出默认值：save() 会直接读这两个字段，缺失会让整个保存动作抛错
  isSubagent: 0, subAgentIds: [],
  // 检索参数覆盖：留空 = 继承全局「系统设置 → 检索设置」；非空的项才写入 queryParams
  qp: blankQp(), qpRerank: 'inherit'
})
/** 检索参数覆盖：可覆盖的项（值即后端 ConfigService 的完整配置键） */
const QP_FIELDS = [
  { key: 'vectorWeight', label: '向量权重', path: 'retrieval.vectorWeight', ph: '0~1，留空继承全局' },
  { key: 'keywordWeight', label: '关键词权重', path: 'retrieval.keywordWeight', ph: '0~1，留空继承全局' },
  { key: 'vecThreshold', label: '相似度阈值', path: 'retrieval.vecThreshold', ph: '0~1，留空继承全局' },
  { key: 'vectorTopK', label: '向量召回数', path: 'retrieval.vectorTopK', ph: '如 15，留空继承全局' },
  { key: 'keywordLimit', label: '关键词召回数', path: 'retrieval.keywordLimit', ph: '如 20，留空继承全局' }
]
const blankQp = () => ({ vectorWeight: null, keywordWeight: null, vecThreshold: null, vectorTopK: null, keywordLimit: null })

/** queryParams(JSON 串) → 表单（未配置的项为 null = 继承全局） */
function parseQueryParams (json) {
  const qp = blankQp()
  let ps = null
  try { ps = json ? JSON.parse(json) : null } catch (e) { ps = null }
  if (ps && typeof ps === 'object') {
    for (const f of QP_FIELDS) {
      const v = ps[f.path]
      if (v !== undefined && v !== null && String(v).trim() !== '') qp[f.key] = v
    }
  }
  // 重排三态：显式配过才显示"已覆盖"
  let rr = 'inherit'
  if (ps && ps['rerank.enabled'] !== undefined && ps['rerank.enabled'] !== null) {
    rr = String(ps['rerank.enabled']) === 'true' ? 'on' : 'off'
  }
  return { qp, rr }
}

/** 表单 → queryParams(JSON 串)；全部留空返回空串（后端存 null = 全部继承全局设置） */
function buildQueryParams (qp, rr) {
  const o = {}
  for (const f of QP_FIELDS) {
    const v = qp ? qp[f.key] : null
    if (v !== null && v !== undefined && String(v).trim() !== '') o[f.path] = String(v).trim()
  }
  if (rr === 'on' || rr === 'off') o['rerank.enabled'] = rr === 'on' ? 'true' : 'false'
  return Object.keys(o).length ? JSON.stringify(o) : ''
}

const form = ref(blankForm())

const isDefault = a => a.isDefault === 1 || a.isDefault === true
/** 系统内置（如默认「知识库助手」）：不可删除，卡片上以「内置」标记区分 */
const isBuiltin = a => a.isBuiltin === 1 || a.isBuiltin === true
/** 子智能体：不直接参与对话，只能被主智能体委派 */
const isSub = a => a.isSubagent === 1 || a.isSubagent === true
/** 逗号串 → 数组（具体项） */
const splitList = v => (v ? String(v).split(',').filter(Boolean) : [])
const matchKw = a => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return true
  return String(a.name || '').toLowerCase().includes(kw) || String(a.description || '').toLowerCase().includes(kw)
}
/**
 * 列表分组：主智能体一组，子智能体单独一组，互不混排。
 * 搜索关键词同时作用于所有组，过滤后为空的组不显示。
 */
const sections = computed(() => {
  const secs = []
  const mains = agents.value.filter(a => !isSub(a) && matchKw(a))
  if (mains.length) {
    secs.push({ key: 'main', title: '主智能体', hint: '可在对话页直接选用', members: mains })
  }
  const subs = agents.value.filter(a => isSub(a) && matchKw(a))
  if (subs.length) {
    secs.push({ key: 'sub', title: '子智能体', hint: '不直接参与对话，供主智能体并行委派', members: subs })
  }
  return secs
})
const scopeText = a => {
  if (a.knowledgeDisabled === 1 || a.knowledgeDisabled === true) return '不使用知识库'
  const n = String(a.knowledgeBaseIds || '').split(',').filter(Boolean).length
  if (!n) return '全部知识库'
  return n === 1 ? '限 1 个知识库' : `限 ${n} 个知识库`
}

// ==================== 共享范围（弹窗为公共组件 ShareScopeModal） ====================
const shareVisible = ref(false)
const shareTarget = ref({ id: '', shareConfig: '' })
const saveShareFn = json => updateAgentShare(shareTarget.value.id, json)
// 卡片上的共享范围标记：仅非全员时显示（全员=默认，不显示以免噪音）
function scopeLabel (a) {
  if (!a.shareConfig || !String(a.shareConfig).trim()) return ''
  let parsed = null
  try { parsed = JSON.parse(a.shareConfig) } catch (e) { return '' }
  const r = (parsed && parsed.read_scope) || {}
  const lvl = r.access_level || 'global'
  if (lvl === 'department') {
    const n = Array.isArray(r.department_ids) ? r.department_ids.length : 0
    return n ? '限 ' + n + ' 个部门' : '部门可见'
  }
  if (lvl === 'user') {
    const n = Array.isArray(r.user_uids) ? r.user_uids.length : 0
    return n ? '限 ' + n + ' 人' : '指定人可见'
  }
  return ''
}
function openShare (a) {
  shareTarget.value = { id: a.id, shareConfig: a.shareConfig || '' }
  shareVisible.value = true
}
/** 卡片上只标出「显式开启」的能力——跟随全局的不占位置 */
const capsForcedOn = a => CAPS.filter(c => a[c.key] === 1).map(c => c.label)

// ==================== 全局配置快照（用于显示每项的全局状态） ====================
const rawOf = path => path.reduce((o, k) => (o == null ? undefined : o[k]), cfg.value)?.value
const isOn = path => { const v = rawOf(path); return v === 'true' || v === true }
const globalText = c => {
  if (c.gate && !isOn(c.gate)) return '关闭（工具总开关未开）'
  return isOn(c.path) ? '开启' : '关闭'
}

// ==================== 生效摘要（实时随表单变化） ====================
const summaryScope = computed(() => {
  if (scopeMode.value === 'none') return '不使用知识库'
  if (scopeMode.value !== 'pick') return '全部文档'
  const n = (form.value.knowledgeBaseIds || []).length
  return n ? `限 ${n} 篇文档` : '未选文档（检索不到内容）'
})
/** 能力当前状态：开关型看三态值，多实例看模式（'1'/'pick'=开，'0'/'none'=关，其余=跟随全局） */
const capState = c => (c.kind === 'switch' ? form.value[c.key] : form.value[c.modeKey])
const capOn = c => { const v = capState(c); return v === '1' || v === 'pick' }
const capOff = c => { const v = capState(c); return v === '0' || v === 'none' }
/** 是否被本智能体显式覆盖（驱动「已覆盖」徽标与图标变色） */
const capOverridden = c => capOn(c) || capOff(c)
/** 多实例能力是否处于「指定」模式（决定要不要展开具体项多选） */
const needsPick = c => c.kind === 'list' && form.value[c.modeKey] === 'pick'

const capsTouched = computed(() => CAPS.some(c => capOverridden(c)))
const summaryCaps = computed(() => {
  const on = CAPS.filter(c => capOn(c)).length
  const off = CAPS.filter(c => capOff(c)).length
  if (!on && !off) return '能力跟随全局'
  const parts = []
  if (on) parts.push(`${on} 项开启`)
  if (off) parts.push(`${off} 项关闭`)
  return parts.join(' / ')
})

// ==================== 数据加载 ====================
const reload = async () => {
  loading.value = true
  try {
    const [ar, dr, cr, sr, mr, xr] = await Promise.all([
      listAgents(), listKnowledgeBases(), getConfig(), listSkills(), getMcpStatus(), listSubAgents()
    ])
    if (ar.success && ar.data) agents.value = ar.data
    if (dr.success && dr.data) {
      const list = Array.isArray(dr.data) ? dr.data : (dr.data.list || [])
      kbOptions.value = list.map(k => ({
        value: k.id,
        label: k.name + (k.docCount != null ? `（${k.docCount} 个文档）` : '')
      }))
    }
    if (cr.success && cr.data) {
      cfg.value = cr.data
    }
    // 技能与 MCP Server 的可选项（供「指定」模式下的多选）
    if (sr && sr.success && Array.isArray(sr.data)) {
      skillOptions.value = sr.data.filter(s => !s.disabled).map(s => ({ value: s.name, label: s.name }))
    }
    if (mr && mr.success && mr.data) {
      mcpOptions.value = (mr.data.servers || []).map(s => ({
        value: s.name,
        label: s.connected ? s.name : s.name + '（未连接）'
      }))
    }
    // 可委派的子智能体
    if (xr && xr.success && Array.isArray(xr.data)) {
      subOptions.value = xr.data.map(s => ({ value: s.id, label: s.name }))
    }
  } catch (e) { message.error(e.message || '加载失败') }
  finally { loading.value = false }
}

// ==================== 列表操作 ====================
const triStr = v => (v === null || v === undefined ? '' : String(v))

const openCreate = () => {
  editingId.value = ''
  form.value = blankForm()
  scopeMode.value = 'all'
  editing.value = true
}
/** 总开关三态 → 多实例能力的模式：'none' 不使用 / 'pick' 指定 / 'inherit' 跟随全局 */
const modeOf = tri => {
  if (tri === 0 || tri === '0') return 'none'
  if (tri === 1 || tri === '1') return 'pick'
  return 'inherit'
}

const openEdit = a => {
  editingId.value = a.id
  const kbs = splitList(a.knowledgeBaseIds)
  form.value = {
    name: a.name || '',
    description: a.description || '',
    systemPrompt: a.systemPrompt || '',
    knowledgeBaseIds: kbs,
    isDefault: isDefault(a),
    toolKnowledge: triStr(a.toolKnowledge),
    toolBuiltin: triStr(a.toolBuiltin),
    toolSkill: triStr(a.toolSkill),
    toolArtifact: triStr(a.toolArtifact),
    toolMcp: triStr(a.toolMcp),
    builtinMode: modeOf(a.toolBuiltin), builtinTools: splitList(a.builtinTools),
    skillMode: modeOf(a.toolSkill), skills: splitList(a.skills),
    mcpMode: modeOf(a.toolMcp), mcps: splitList(a.mcps),
    isSubagent: (a.isSubagent === 1 || a.isSubagent === true) ? 1 : 0,
    subAgentIds: splitList(a.subAgentIds),
    ...(() => { const r = parseQueryParams(a.queryParams); return { qp: r.qp, qpRerank: r.rr } })()
  }
  // 三档：不使用知识库 > 指定知识库（选了库）> 全部知识库
  scopeMode.value = (a.knowledgeDisabled === 1 || a.knowledgeDisabled === true)
    ? 'none' : (kbs.length ? 'pick' : 'all')
  editing.value = true
}
const closeEdit = () => { editing.value = false }

/** 三态转换：'' → null（跟随全局）；'1' → 1；'0' → 0 */
const tri = v => (v === '' || v == null ? null : Number(v))

const save = async () => {
  const f = form.value
  if (!f.name.trim()) { message.warning('请填写名称'); return }
  const payload = {
    name: f.name.trim(),
    description: f.description.trim(),
    systemPrompt: f.systemPrompt,
    // 「全部知识库」时清空（空 → 后端存 null → 不限制）；「指定知识库」时存逗号串；
    // 「不使用知识库」时置 knowledgeDisabled=1 并清空（两者互斥，后端以开关为准）
    knowledgeBaseIds: scopeMode.value === 'pick' ? (f.knowledgeBaseIds || []).join(',') : '',
    knowledgeDisabled: scopeMode.value === 'none' ? 1 : 0,
    toolKnowledge: tri(f.toolKnowledge),
    toolArtifact: tri(f.toolArtifact),
    isDefault: f.isDefault ? 1 : 0,
    isSubagent: f.isSubagent ? 1 : 0,
    // 子智能体没有委派对象；主智能体一个都没选 → 空串（后端归一为 null → 编排走多视角策略）
    subAgentIds: f.isSubagent ? null : (f.subAgentIds || []).join(','),
    // 检索参数覆盖：留空项不写入 → 继承全局；全空 → 空串 → 后端存 null
    queryParams: buildQueryParams(f.qp, f.qpRerank)
  }
  // 多实例能力：模式 →（总开关三态 + 具体项）
  //   指定 → 开关置 1 + 项列表；一项都没选则等同「不使用」
  //   不使用 → 开关置 0 并清空列表
  //   跟随全局 → 两者都置 null（后端按 null 判定继承）
  for (const c of CAPS.filter(x => x.kind === 'list')) {
    const mode = f[c.modeKey]
    const picked = (f[c.listKey] || []).join(',')
    if (mode === 'pick' && picked) {
      payload[c.key] = 1
      payload[c.listKey] = picked
    } else if (mode === 'pick' || mode === 'none') {
      payload[c.key] = 0
      payload[c.listKey] = null
    } else {
      payload[c.key] = null
      payload[c.listKey] = null
    }
  }
  saving.value = true
  try {
    const r = editingId.value ? await updateAgent(editingId.value, payload) : await createAgent(payload)
    if (r.success) {
      message.success(editingId.value ? '已保存' : '已创建')
      editing.value = false
      await reload()
    } else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}

const doDelete = async id => {
  try {
    const r = await deleteAgent(id)
    if (r.success) { message.success('已删除'); await reload() }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}
const doSetDefault = async id => {
  try {
    const r = await setAgentDefault(id)
    if (r.success) { message.success('已设为默认'); await reload() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}

onMounted(reload)
onMounted(async () => { })
</script>

<style scoped>
.ap-count { font-size: 12px; color: var(--app-text3); }
.ap-head-r { margin-left: auto; display: flex; align-items: center; gap: 8px; }
.ap-search { width: 200px; }
.ap-search-ic { color: var(--app-text3); }

.ap-empty { padding: 48px 20px; text-align: center; }
.ap-empty-t { font-size: 13px; font-weight: 500; margin-bottom: 6px; }
.ap-empty-d { font-size: 12px; color: var(--app-text2); line-height: 1.7; max-width: 460px; margin: 0 auto 14px; }

/* 分组列表：主智能体一组，子智能体按委派它的主智能体成组 */
.ap-groups { display: flex; flex-direction: column; gap: 24px; }
.ap-group { display: flex; flex-direction: column; gap: 10px; }
.ap-group-head { display: flex; align-items: baseline; gap: 8px; min-width: 0; }
.ap-group-title { font-size: 13px; font-weight: 600; flex: none; }
.ap-group-hint {
  font-size: 12px; color: var(--app-text3); min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.ap-group-count {
  flex: none; font-size: 11px; line-height: 1; padding: 3px 7px; border-radius: 999px;
  background: #f1f3f5; color: var(--app-text2);
}

/* 卡片列表 */
.ap-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
.ap-card {
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 12px;
  padding: 12px 14px; display: flex; flex-direction: column; gap: 9px; cursor: pointer;
  transition: border-color .15s, box-shadow .15s, transform .15s;
}
.ap-card:hover {
  border-color: #bcd0f7;
  box-shadow: 0 6px 18px -10px rgba(46, 107, 230, .35);
}
.ap-card-head { display: flex; align-items: center; gap: 8px; }
.ap-avatar {
  width: 24px; height: 24px; border-radius: 7px; flex: none; font-size: 12px;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--app-accent-weak); color: var(--app-accent);
}
.ap-name { font-size: 13px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ap-card-tags { margin-left: auto; display: inline-flex; align-items: center; gap: 4px; flex: none; }
.ap-tag-default {
  font-size: 10px; line-height: 1; padding: 3px 6px; border-radius: 999px;
  background: #eaf5ec; color: var(--app-ok);
}
.ap-tag-builtin {
  font-size: 10px; line-height: 1; padding: 3px 6px; border-radius: 999px;
  background: #e8eefc; color: var(--app-accent);
}
.ap-builtin-hint { font-size: 11px; color: var(--app-text3); padding: 0 4px; }
.ap-desc {
  font-size: 12px; color: var(--app-text2); line-height: 1.6; margin: 0; min-height: 32px;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.ap-chips { display: flex; flex-wrap: wrap; gap: 5px; }
.ap-chip {
  font-size: 11px; line-height: 1; padding: 4px 7px; border-radius: 6px;
  background: #f1f3f5; color: var(--app-text2); white-space: nowrap;
}
.ap-chip-on { background: var(--app-accent-weak); color: var(--app-accent); }
.ap-chip-warn { background: #faf3e6; color: #a3691b; }
.ap-card-foot {
  display: flex; align-items: center; justify-content: flex-end; gap: 2px;
  border-top: 1px dashed var(--app-border); padding-top: 6px; margin-top: auto;
}

/* 配置视图 */
.ap-summary {
  display: flex; align-items: center; flex-wrap: wrap; gap: 6px; max-width: 820px;
  margin-bottom: 12px; padding: 10px 14px; border-radius: 12px;
  background: linear-gradient(0deg, var(--app-accent-weak), var(--app-accent-weak));
  border: 1px solid #dbe6fb; font-size: 12px; color: var(--app-text2);
}
.ap-summary-avatar {
  width: 22px; height: 22px; border-radius: 6px; flex: none; font-size: 11px;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--app-panel); color: var(--app-accent);
}
.ap-summary-name { font-size: 13px; font-weight: 500; color: var(--app-text); }
.ap-summary-sep { color: var(--app-text3); }
.ap-summary-item { color: var(--app-text2); }
.ap-summary-item.is-accent { color: var(--app-accent); }

.ap-form { display: flex; flex-direction: column; gap: 12px; max-width: 820px; }
.ap-form :deep(.ant-form-item) { margin-bottom: 12px; }
.ap-block-hint { font-size: 12px; color: var(--app-text3); line-height: 1.6; margin: -4px 0 12px; }
/* 检索参数覆盖：两列网格，留空=继承全局 */
.qp-grid { display: grid; grid-template-columns: repeat(2, minmax(220px, 1fr)); gap: 10px 18px; }
.qp-item { display: flex; align-items: center; gap: 10px; }
.qp-label { flex: none; width: 88px; font-size: 12.5px; color: var(--app-text2); }
.ap-pick { margin-top: 12px; }
.ap-sec-ic { font-size: 13px; color: var(--app-text3); }

/* 能力行：图标块 + 名称/描述 + 三态控件；只在「已覆盖」时才高亮 */
.ap-caps { display: flex; flex-direction: column; }
.ap-cap-block { border-top: 1px dashed var(--app-border); }
.ap-cap-block:first-child { border-top: none; }
.ap-cap { display: flex; align-items: center; gap: 12px; padding: 10px 0; }
.ap-cap-block:first-child .ap-cap { padding-top: 0; }
/* 「指定」模式展开的具体项多选：与上方图标块左对齐 */
.ap-cap-pick { padding: 0 0 12px 42px; }
.ap-pick-empty { font-size: 11px; color: var(--app-text3); margin-top: 5px; }
.ap-cap-ic {
  width: 30px; height: 30px; border-radius: 8px; flex: none; font-size: 14px;
  display: inline-flex; align-items: center; justify-content: center;
  background: #f1f3f5; color: var(--app-text2); transition: background .15s, color .15s;
}
.ap-cap.overridden .ap-cap-ic { background: var(--app-accent-weak); color: var(--app-accent); }
.ap-cap-l { flex: 1; min-width: 0; }
.ap-cap-name { display: flex; align-items: center; gap: 6px; font-size: 13px; }
.ap-cap.overridden .ap-cap-name { font-weight: 500; }
.ap-cap-badge {
  font-size: 10px; line-height: 1; padding: 3px 6px; border-radius: 999px;
  background: var(--app-accent-weak); color: var(--app-accent);
}
.ap-cap-desc { font-size: 12px; color: var(--app-text3); line-height: 1.6; margin-top: 2px; }
.ap-cap-global { color: #b6bdc7; }
</style>
