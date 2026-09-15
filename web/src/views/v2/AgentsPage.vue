<template>
  <div class="v2-page">
    <!-- ==================== 列表视图 ==================== -->
    <template v-if="!editing">
      <div class="v2-page-head">
        <h1 class="v2-page-title">智能体</h1>
        <span class="ap-count">共 {{ agents.length }} 个</span>
        <div class="ap-head-r">
          <a-input v-model:value="keyword" class="ap-search" size="small" allow-clear placeholder="搜索名称或描述">
            <template #prefix><search-outlined class="ap-search-ic" /></template>
          </a-input>
          <a-tooltip title="刷新">
            <button class="v2-icon-btn" :disabled="loading" aria-label="刷新列表" @click="reload"><reload-outlined /></button>
          </a-tooltip>
          <button class="v2-btn small" @click="openCreate">新建智能体</button>
        </div>
      </div>

      <div class="v2-page-body">
        <div v-if="loading" class="ap-empty"><a-spin size="small" /></div>

        <div v-else-if="!agents.length" class="ap-empty">
          <div class="ap-empty-t">还没有智能体</div>
          <div class="ap-empty-d">
            一个智能体就是一组预设：选好模型、写好角色提示词、圈定知识库范围、按需开启能力。
            对话时在输入框上方切换，这一轮问答就按它的配置走；没配置的维度沿用系统设置。
          </div>
          <button class="v2-btn small" @click="openCreate">新建第一个智能体</button>
        </div>

        <div v-else-if="!filtered.length" class="ap-empty">
          <div class="ap-empty-t">没有匹配的智能体</div>
          <div class="ap-empty-d">没有名称或描述包含「{{ keyword }}」的智能体。</div>
          <button class="v2-btn ghost small" @click="keyword = ''">清除搜索</button>
        </div>

        <div v-else class="ap-grid">
          <article v-for="a in filtered" :key="a.id" class="ap-card" @click="openEdit(a)">
            <div class="ap-card-head">
              <span class="ap-avatar"><robot-outlined /></span>
              <span class="ap-name" :title="a.name">{{ a.name }}</span>
              <span v-if="isDefault(a)" class="ap-tag-default">默认</span>
            </div>
            <p class="ap-desc" :title="a.description || ''">{{ a.description || '未填写描述' }}</p>
            <div class="ap-chips">
              <span class="ap-chip">{{ a.model || '跟随全局模型' }}</span>
              <span class="ap-chip">{{ scopeText(a) }}</span>
              <span v-for="c in capsForcedOn(a)" :key="c" class="ap-chip ap-chip-on">{{ c }}</span>
            </div>
            <div class="ap-card-foot">
              <button class="v2-link-btn" @click.stop="openEdit(a)">配置</button>
              <button v-if="!isDefault(a)" class="v2-link-btn" @click.stop="doSetDefault(a.id)">设为默认</button>
              <a-popconfirm title="删除该智能体？对话页将不再可选" ok-text="删除" cancel-text="取消" @confirm="doDelete(a.id)">
                <button class="v2-link-btn danger" @click.stop>删除</button>
              </a-popconfirm>
            </div>
          </article>
        </div>
      </div>
    </template>

    <!-- ==================== 配置视图（独立整页，不用弹窗） ==================== -->
    <template v-else>
      <div class="v2-page-head">
        <button class="v2-icon-btn" title="返回列表" aria-label="返回列表" @click="closeEdit"><arrow-left-outlined /></button>
        <h1 class="v2-page-title">{{ editingId ? '配置智能体' : '新建智能体' }}</h1>
        <span v-if="editingId" class="ap-count">{{ form.name || '未命名' }}</span>
        <div class="ap-head-r">
          <button class="v2-btn ghost small" @click="closeEdit">取消</button>
          <button class="v2-btn small" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </div>

      <div class="v2-page-body">
        <!-- 生效摘要：随表单实时变化，改完一眼知道最终结果 -->
        <div class="ap-summary">
          <span class="ap-summary-avatar"><robot-outlined /></span>
          <span class="ap-summary-name">{{ form.name || '未命名智能体' }}</span>
          <span class="ap-summary-sep">·</span>
          <span class="ap-summary-item">{{ form.model || globalModel }}</span>
          <span class="ap-summary-sep">·</span>
          <span class="ap-summary-item">{{ summaryScope }}</span>
          <span class="ap-summary-sep">·</span>
          <span class="ap-summary-item" :class="{ 'is-accent': capsTouched }">{{ summaryCaps }}</span>
        </div>

        <a-form layout="vertical" class="ap-form">
          <section class="v2-card">
            <h2 class="v2-card-title"><idcard-outlined class="ap-sec-ic" />身份</h2>
            <p class="ap-block-hint">对话页下拉里展示的就是名称与描述，写清楚它适合什么场景。</p>
            <a-form-item label="名称" required>
              <a-input v-model:value="form.name" :maxlength="200" placeholder="如：合同审查助手 / 运维排障 / 产品 FAQ" />
            </a-form-item>
            <a-form-item label="描述" style="margin-bottom:0">
              <a-input v-model:value="form.description" :maxlength="500" placeholder="一句话说明它适合什么场景" />
            </a-form-item>
          </section>

          <section class="v2-card">
            <h2 class="v2-card-title"><thunderbolt-outlined class="ap-sec-ic" />模型与提示词</h2>
            <p class="ap-block-hint">留空表示沿用系统设置里的全局值，不覆盖。</p>
            <a-form-item label="模型">
              <a-input v-model:value="form.model" :maxlength="255" :placeholder="'跟随全局：' + globalModel" />
            </a-form-item>
            <a-form-item label="系统提示词" style="margin-bottom:0">
              <a-textarea v-model:value="form.systemPrompt" :rows="6"
                          placeholder="填写后完全替换全局系统提示词；留空沿用全局" />
            </a-form-item>
          </section>

          <section class="v2-card">
            <h2 class="v2-card-title"><database-outlined class="ap-sec-ic" />知识库范围</h2>
            <p class="ap-block-hint">限定这个智能体能检索到的内容，用于把不同角色限制在各自的资料范围内。</p>
            <a-radio-group v-model:value="scopeMode">
              <a-radio-button value="all">全部文档</a-radio-button>
              <a-radio-button value="pick">指定文档</a-radio-button>
            </a-radio-group>
            <div v-if="scopeMode === 'pick'" class="ap-pick">
              <a-select v-model:value="form.knowledgeScope" mode="multiple" :options="docOptions" allow-clear
                        show-search option-filter-prop="label" :max-tag-count="6" style="width:100%"
                        placeholder="选择允许检索的文档" />
              <div class="ap-block-hint" style="margin:6px 0 0">
                已选 {{ form.knowledgeScope.length }} 篇；一篇都不选则该智能体检索不到任何内容。
              </div>
            </div>
          </section>

          <section class="v2-card">
            <h2 class="v2-card-title"><control-outlined class="ap-sec-ic" />能力</h2>
            <p class="ap-block-hint">
              默认全部沿用系统设置里的开关；只有需要为这个智能体单独破例时，才把某一项改成「开启」或「关闭」。
            </p>
            <div class="ap-caps">
              <div v-for="c in CAPS" :key="c.key" class="ap-cap" :class="{ overridden: !!form[c.key] }">
                <span class="ap-cap-ic"><component :is="c.icon" /></span>
                <div class="ap-cap-l">
                  <div class="ap-cap-name">
                    {{ c.label }}
                    <span v-if="form[c.key]" class="ap-cap-badge">已覆盖</span>
                  </div>
                  <div class="ap-cap-desc">
                    {{ c.desc }}<span class="ap-cap-global"> · 全局{{ globalText(c) }}</span>
                  </div>
                </div>
                <a-segmented v-model:value="form[c.key]" :options="SEG" size="small" />
              </div>
            </div>
          </section>

          <section class="v2-card">
            <h2 class="v2-card-title"><star-outlined class="ap-sec-ic" />默认</h2>
            <a-checkbox v-model:checked="form.isDefault">设为默认智能体</a-checkbox>
            <span class="ap-block-hint" style="margin-left:8px">对话页打开时预选它（同一时间只有一个默认）</span>
          </section>
        </a-form>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  ArrowLeftOutlined, ReloadOutlined, SearchOutlined, RobotOutlined, IdcardOutlined,
  ThunderboltOutlined, DatabaseOutlined, ControlOutlined, StarOutlined,
  FileSearchOutlined, CalculatorOutlined, FileDoneOutlined, AppstoreOutlined, ApiOutlined
} from '@ant-design/icons-vue'
import { listAgents, createAgent, updateAgent, deleteAgent, setAgentDefault, listDocuments, getConfig } from '../../api'

// ==================== 能力定义 ====================
// path：该能力在全局配置里的开关路径；gate：还受此总闸制约（关掉总闸时能力不生效）
const CAPS = [
  { key: 'toolKnowledge', label: '知识库检索', desc: '回答过程中可自主检索知识库补充依据', icon: FileSearchOutlined,
    path: ['tool', 'knowledgeRetrieval', 'enabled'], gate: ['tool', 'enabled'] },
  { key: 'toolBuiltin', label: '内置高频工具', desc: '算术计算 / 当前时间 / 日期相差天数', icon: CalculatorOutlined,
    path: ['tool', 'builtin', 'enabled'], gate: ['tool', 'enabled'] },
  { key: 'toolArtifact', label: '产物交付', desc: '生成 Markdown / CSV / JSON / HTML 文件并附下载卡片', icon: FileDoneOutlined,
    path: ['tool', 'artifact', 'enabled'], gate: ['tool', 'enabled'] },
  { key: 'toolSkill', label: '技能 Skills', desc: '注入技能清单，模型可按需读取技能全文', icon: AppstoreOutlined,
    path: ['skill', 'enabled'] },
  { key: 'toolMcp', label: 'MCP 外部工具', desc: '连接外部 MCP Server，把它的工具交给模型', icon: ApiOutlined,
    path: ['mcp', 'enabled'] }
]
// 三态：''=跟随全局 / '1'=开启 / '0'=关闭（与后端 tool_* 字段 1/0/null 对应）
const SEG = [
  { label: '跟随全局', value: '' },
  { label: '开启', value: '1' },
  { label: '关闭', value: '0' }
]

const loading = ref(false)
const saving = ref(false)
const keyword = ref('')
const agents = ref([])
const docOptions = ref([])
const cfg = ref({})
const globalModel = ref('未配置')

const editing = ref(false)
const editingId = ref('')
const scopeMode = ref('all')
const blankForm = () => ({
  name: '', description: '', model: '', systemPrompt: '', knowledgeScope: [], isDefault: false,
  toolKnowledge: '', toolBuiltin: '', toolSkill: '', toolArtifact: '', toolMcp: ''
})
const form = ref(blankForm())

const isDefault = a => a.isDefault === 1 || a.isDefault === true
const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return agents.value
  return agents.value.filter(a =>
    String(a.name || '').toLowerCase().includes(kw) || String(a.description || '').toLowerCase().includes(kw))
})
const scopeText = a => {
  if (!a.knowledgeScope) return '全部文档'
  const n = String(a.knowledgeScope).split(',').filter(Boolean).length
  return n === 1 ? '限 1 篇文档' : `限 ${n} 篇文档`
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
  if (scopeMode.value !== 'pick') return '全部文档'
  const n = (form.value.knowledgeScope || []).length
  return n ? `限 ${n} 篇文档` : '未选文档（检索不到内容）'
})
const capsTouched = computed(() => CAPS.some(c => !!form.value[c.key]))
const summaryCaps = computed(() => {
  const on = CAPS.filter(c => form.value[c.key] === '1').length
  const off = CAPS.filter(c => form.value[c.key] === '0').length
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
    const [ar, dr, cr] = await Promise.all([listAgents(), listDocuments(), getConfig()])
    if (ar.success && ar.data) agents.value = ar.data
    if (dr.success && dr.data) {
      const list = Array.isArray(dr.data) ? dr.data : (dr.data.list || [])
      docOptions.value = list.map(d => ({ value: d.id, label: d.fileName || d.name || d.id }))
    }
    if (cr.success && cr.data) {
      cfg.value = cr.data
      const m = cr.data.chat?.model?.value
      globalModel.value = m ? String(m) : '未配置'
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
const openEdit = a => {
  editingId.value = a.id
  const scope = a.knowledgeScope ? String(a.knowledgeScope).split(',').filter(Boolean) : []
  form.value = {
    name: a.name || '',
    description: a.description || '',
    model: a.model || '',
    systemPrompt: a.systemPrompt || '',
    knowledgeScope: scope,
    isDefault: isDefault(a),
    toolKnowledge: triStr(a.toolKnowledge),
    toolBuiltin: triStr(a.toolBuiltin),
    toolSkill: triStr(a.toolSkill),
    toolArtifact: triStr(a.toolArtifact),
    toolMcp: triStr(a.toolMcp)
  }
  scopeMode.value = scope.length ? 'pick' : 'all'
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
    model: f.model.trim(),
    systemPrompt: f.systemPrompt,
    // 「全部文档」时清空范围（空 → 后端存 null → 继承全局知识库）；「指定文档」时存逗号串
    knowledgeScope: scopeMode.value === 'pick' ? (f.knowledgeScope || []).join(',') : '',
    toolKnowledge: tri(f.toolKnowledge),
    toolBuiltin: tri(f.toolBuiltin),
    toolSkill: tri(f.toolSkill),
    toolArtifact: tri(f.toolArtifact),
    toolMcp: tri(f.toolMcp),
    isDefault: f.isDefault ? 1 : 0
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
</script>

<style scoped>
.ap-count { font-size: 12px; color: var(--v2-text3); }
.ap-head-r { margin-left: auto; display: flex; align-items: center; gap: 8px; }
.ap-search { width: 200px; }
.ap-search-ic { color: var(--v2-text3); }

.ap-empty { padding: 48px 20px; text-align: center; }
.ap-empty-t { font-size: 13px; font-weight: 500; margin-bottom: 6px; }
.ap-empty-d { font-size: 12px; color: var(--v2-text2); line-height: 1.7; max-width: 460px; margin: 0 auto 14px; }

/* 卡片列表 */
.ap-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
.ap-card {
  background: var(--v2-panel); border: 1px solid var(--v2-border); border-radius: 12px;
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
  background: var(--v2-accent-weak); color: var(--v2-accent);
}
.ap-name { font-size: 13px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ap-tag-default {
  font-size: 10px; line-height: 1; padding: 3px 6px; border-radius: 999px; flex: none; margin-left: auto;
  background: #eaf5ec; color: var(--v2-ok);
}
.ap-desc {
  font-size: 12px; color: var(--v2-text2); line-height: 1.6; margin: 0; min-height: 32px;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.ap-chips { display: flex; flex-wrap: wrap; gap: 5px; }
.ap-chip {
  font-size: 11px; line-height: 1; padding: 4px 7px; border-radius: 6px;
  background: #f1f3f5; color: var(--v2-text2); white-space: nowrap;
}
.ap-chip-on { background: var(--v2-accent-weak); color: var(--v2-accent); }
.ap-card-foot {
  display: flex; align-items: center; justify-content: flex-end; gap: 2px;
  border-top: 1px dashed var(--v2-border); padding-top: 6px; margin-top: auto;
}

/* 配置视图 */
.ap-summary {
  display: flex; align-items: center; flex-wrap: wrap; gap: 6px; max-width: 820px;
  margin-bottom: 12px; padding: 10px 14px; border-radius: 12px;
  background: linear-gradient(0deg, var(--v2-accent-weak), var(--v2-accent-weak));
  border: 1px solid #dbe6fb; font-size: 12px; color: var(--v2-text2);
}
.ap-summary-avatar {
  width: 22px; height: 22px; border-radius: 6px; flex: none; font-size: 11px;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--v2-panel); color: var(--v2-accent);
}
.ap-summary-name { font-size: 13px; font-weight: 500; color: var(--v2-text); }
.ap-summary-sep { color: var(--v2-text3); }
.ap-summary-item { color: var(--v2-text2); }
.ap-summary-item.is-accent { color: var(--v2-accent); }

.ap-form { display: flex; flex-direction: column; gap: 12px; max-width: 820px; }
.ap-form :deep(.ant-form-item) { margin-bottom: 12px; }
.ap-block-hint { font-size: 12px; color: var(--v2-text3); line-height: 1.6; margin: -4px 0 12px; }
.ap-pick { margin-top: 12px; }
.ap-sec-ic { font-size: 13px; color: var(--v2-text3); }

/* 能力行：图标块 + 名称/描述 + 三态控件；只在「已覆盖」时才高亮 */
.ap-caps { display: flex; flex-direction: column; }
.ap-cap {
  display: flex; align-items: center; gap: 12px; padding: 10px 0;
  border-top: 1px dashed var(--v2-border);
}
.ap-cap:first-child { border-top: none; padding-top: 0; }
.ap-cap-ic {
  width: 30px; height: 30px; border-radius: 8px; flex: none; font-size: 14px;
  display: inline-flex; align-items: center; justify-content: center;
  background: #f1f3f5; color: var(--v2-text2); transition: background .15s, color .15s;
}
.ap-cap.overridden .ap-cap-ic { background: var(--v2-accent-weak); color: var(--v2-accent); }
.ap-cap-l { flex: 1; min-width: 0; }
.ap-cap-name { display: flex; align-items: center; gap: 6px; font-size: 13px; }
.ap-cap.overridden .ap-cap-name { font-weight: 500; }
.ap-cap-badge {
  font-size: 10px; line-height: 1; padding: 3px 6px; border-radius: 999px;
  background: var(--v2-accent-weak); color: var(--v2-accent);
}
.ap-cap-desc { font-size: 12px; color: var(--v2-text3); line-height: 1.6; margin-top: 2px; }
.ap-cap-global { color: #b6bdc7; }
</style>
