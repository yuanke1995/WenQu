<template>
  <div class="app-page">
    <div class="app-page-head">
      <h3 class="app-page-title">知识库</h3>
      <span class="head-hint-plain" title="文档的容器：检索按库隔离，向量/检索/解析参数随库">文档的容器：检索按库隔离，向量/检索/解析参数随库</span>
      <button v-if="!mobileShell" class="app-btn" style="margin-left:auto" @click="openCreate">
        <plus-outlined /> 新建知识库
      </button>
      <!-- 导入：先弹说明窗（格式/去向/同名语义），确认后再选文件。直接弹文件选择器会让用户
           不知道要什么格式、会导到哪 -->
      <button v-if="!mobileShell" class="app-btn ghost" @click="openImport">
        <import-outlined /> 导入文档包
      </button>
      <input ref="importFileInput" type="file" accept=".zip" style="display:none" @change="onImportFile" />
    </div>

    <div class="app-page-body">
      <a-spin :spinning="loading">
        <div class="kb-cards">
          <div v-for="kb in list" :key="kb.id" class="app-card kb-card" @click="openDocs(kb)">
            <div class="kb-card-head">
              <KbIcon :kb="kb" :size="22" />
              <span class="kb-name">{{ kb.name }}</span>
              <!-- 混合权限（库内文档分属不同可见范围）：同库内无权文档会在检索后剔除时占用召回名额，
                   建议分库根治。默认不显示，鼠标悬停说明原因。 -->
              <a-tooltip v-if="kb.mixedScope" title="库内文档分属不同可见范围：其他人可能搜不全这个库里的内容。建议把不同可见范围的文档拆成多个知识库。">
                <a-tag color="orange" class="kb-mixed-tag"
                       :style="(kb.isDefault === 1 || kb.builtin === 1) ? '' : 'margin-left:auto'">建议分库</a-tag>
              </a-tooltip>
              <a-tag v-if="kb.isDefault === 1" color="blue" :style="kb.mixedScope ? '' : 'margin-left:auto'">默认</a-tag>
              <a-tag v-if="kb.builtin === 1" color="gold" :style="(kb.isDefault === 1 || kb.mixedScope) ? '' : 'margin-left:auto'">官方</a-tag>
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
              <!-- 官方内置库：文档内容随版本自动同步（后端拒绝改内容与删除），但检索/解析参数是运行时配置、
                   同步不碰——管理员可编辑这两项（典型用途：给官方库绑定重排模型）。非管理员仍无任何管理入口 -->
              <!-- 移动壳只读：编辑/图谱/删除都不渲染（图谱弹窗里有「构建/清空图谱」这类写动作，
                   且它本身还没做移动适配，见 h5/README 的下一候选） -->
              <template v-if="!mobileShell && (isAdmin || (kb.createdBy === myUid && kb.builtin !== 1))">
                <button class="app-link-btn" @click="openEdit(kb)">编辑</button>
                <template v-if="kb.builtin !== 1">
                  <button class="app-link-btn" @click="openGraph(kb)">图谱</button>
                  <!-- 导出：zip 打包源文件+清单（迁移/备份用，向量按导入库重嵌） -->
                  <button class="app-link-btn" :disabled="exportingId === kb.id" @click="doExport(kb)">
                    {{ exportingId === kb.id ? '导出中…' : '导出' }}
                  </button>
                  <!-- 默认库是兜底归属（不可删），删除按钮直接不渲染，只留 disabled 样式会误导可点 -->
                  <button v-if="kb.isDefault !== 1" class="app-link-btn danger" @click="onDelete(kb)">删除</button>
                </template>
              </template>
              <button class="app-link-btn" style="margin-left:auto" @click="openDocs(kb)">文档管理 →</button>
            </div>
          </div>
          <div v-if="!loading && !list.length" class="kb-empty">还没有知识库，点右上角「新建知识库」创建</div>
        </div>
      </a-spin>
    </div>

    <!-- 知识库新建/编辑共用一个弹窗组件（文档列表页「知识库配置」也用它，避免两份表单走样） -->
    <KnowledgeBaseEditModal v-model:open="showEdit" :kb="editing" @saved="load" />

    <!-- 图谱浏览（§5 组件化：库列表页 / 库详情页共用，含搜索定位与实体溯源） -->
    <KnowledgeGraphModal v-model:open="graphModal" :kb="graphKb" />
  </div>
</template>

<script setup>
import { ref, onMounted, h } from 'vue'
import { message, Modal, Select } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { PlusOutlined, ImportOutlined } from '@ant-design/icons-vue'
import { listKnowledgeBases, deleteKnowledgeBase, exportKnowledgeBase, importKnowledgeBase } from '../api'
import KnowledgeGraphModal from '../components/KnowledgeGraphModal.vue'
import KnowledgeBaseEditModal from '../components/KnowledgeBaseEditModal.vue'
import KbIcon from '../components/KbIcon.vue'
import { loadModelIndex, modelRefInfo } from '../utils/modelRef'
import { isAdminSync, ensureAuth } from '../utils/auth'
// 移动壳（H5）下本库页只读——这是 DesktopOnlyGuard 对 /knowledge 的承诺（库卡片 + 文档列表 + 知识块预览）。
// 判据用 mobileShell 而不是 isNarrow：手机横屏不算「窄屏」但仍是 H5，桌面把窗口拖窄仍该保留 PC 行为。
import { mobileShell } from '../h5/mobile'

// 权限：管理员全量；普通用户可自建自管自己的库，可见=自己的库+默认库+别人显式共享的库（只读），后端按共享范围过滤返回
const isAdmin = isAdminSync()
const myUid = ref('')

const router = useRouter()
const openDocs = kb => router.push(`/knowledge/${kb.id}/docs`)

const list = ref([])
const loading = ref(false)
const showEdit = ref(false)
// 编辑目标：null=新建（弹窗内以全局值做模板预填），否则回填该库覆盖值
const editing = ref(null)

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

// 新建/编辑弹窗：表单与保存逻辑在 KnowledgeBaseEditModal 组件内（kb=null 时为新建模式）
const openCreate = () => { editing.value = null; showEdit.value = true }
const openEdit = row => { editing.value = row; showEdit.value = true }

// ==================== P1 GraphRAG：图谱弹窗（§5 组件化，搜索定位/实体三元组/源块溯源在组件内） ====================
const graphModal = ref(false)
const graphKb = ref(null)
const openGraph = kb => {
  graphKb.value = kb
  graphModal.value = true
}

const onDelete = row => {
  // 有确认才敢点：后端会拦住「库里还有文档」的那一类，所以这里删掉的是空壳库，
  // 真正静默的后果是级联——所有绑定它的智能体（含子智能体）当场失去这份资料域，向量索引一并清理。
  Modal.confirm({
    title: `删除知识库「${row.name || '未命名'}」？`,
    content: '库里必须已无文档才能删。删除后，绑定过它的智能体会自动摘除这个知识库，'
      + '该库的向量索引同时清理，且不可恢复。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      const r = await deleteKnowledgeBase(row.id)
      if (r && r.success === false) {
        message.warning(r.msg || '无法删除')
        return
      }
      message.success('已删除（关联智能体已同步摘除该库）')
      await load()
    }
  })
}

// ── 导出 / 导入（迁移与备份）：导出 zip=源文件+清单（向量按导入库重嵌）；导入走既有解析管线 ──
const exportingId = ref('')
const doExport = async kb => {
  exportingId.value = kb.id
  try {
    await exportKnowledgeBase(kb.id, kb.name)
    message.success('导出完成（浏览器下载中）')
  } catch (e) { message.error(e.message || '导出失败') }
  finally { exportingId.value = '' }
}

const importFileInput = ref(null)
const importing = ref(false)
let importTargetKb = ''
// 两段式：说明窗（格式/去向/同名语义 + 选目标库）→ 确认后开文件选择器 → 选完直接导入。
// 反过来「先选文件」会让用户在不知道要什么格式、导到哪的情况下点开系统文件框
const openImport = () => {
  const options = list.value
    .filter(kb => kb.builtin !== 1)
    .map(kb => ({ value: kb.id, label: kb.name + (kb.embeddingRef ? '' : '（未绑定向量模型）') }))
  if (!options.length) { message.warning('没有可导入的知识库（官方内置库不接受导入），请先新建'); return }
  importTargetKb = options[0].value
  Modal.confirm({
    title: '导入文档包',
    width: 520,
    content: h('div', { style: 'margin-top:8px' }, [
      h('p', { style: 'font-size:12px;color:#666;margin:0 0 10px;line-height:1.8' }, [
        '导入的是「知识库导出」生成的 zip 包（内含 manifest.json 与各文档源文件）。导入后：解析、分块、向量化按目标库配置全部重算；',
        h('b', null, '同名文档会被替换'), '，重复导入安全；单文件上限 200MB、单包 500 个文档。',
      ]),
      h('p', { style: 'font-size:12px;color:#888;margin:0 0 6px' }, '导入到哪个知识库？'),
      h(Select, {
        value: importTargetKb,
        options,
        style: 'width:100%',
        'onUpdate:value': v => { importTargetKb = v }
      }),
    ]),
    okText: '选择文件并导入', cancelText: '取消',
    onOk: () => new Promise(resolve => {
      if (!importTargetKb) { message.warning('请选择目标知识库'); resolve(false); return }
      // 打开系统文件选择器；用户取消（无文件）时什么也不发生
      importFileInput.value?.click()
      resolve(true)
    })
  })
}
const onImportFile = async e => {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (!file || !importTargetKb) return
  importing.value = true
  try {
    const r = await importKnowledgeBase(importTargetKb, file)
    if (r.success) {
      const d = r.data || {}
      const fails = (d.results || []).filter(x => !x.success)
      message.success(`导入完成：成功 ${d.ok}/${d.total}` + (fails.length ? `，失败 ${fails.length} 个（${fails.map(x => `${x.fileName}: ${x.msg}`).join('；').slice(0, 120)}…）` : ''))
      await load()
    } else message.error(r.msg || '导入失败')
  } catch (err) { message.error(err.message || '导入失败') }
  finally { importing.value = false }
}

onMounted(() => {
  load()
  ensureAuth().then(me => { myUid.value = me.user || '' })
})
</script>

<style scoped>
/* 内边距/滚动由 .app-page-body 提供（与文档页/智能体/技能同一套骨架），此处只放网格与卡片细节 */
.kb-cards {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px; align-items: stretch;
}
.kb-card { cursor: pointer; display: flex; flex-direction: column; gap: 8px; transition: border-color .15s, box-shadow .15s; }
.kb-card:hover { border-color: var(--app-accent); box-shadow: 0 4px 16px -6px rgba(46, 107, 230, .25); }
.kb-card-head { display: flex; align-items: center; gap: 8px; min-width: 0; }
.kb-name { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.kb-card-desc {
  margin: 0; font-size: 12px; color: var(--app-text3); line-height: 1.6; min-height: 38px;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.kb-card-meta { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; font-size: 11px; color: var(--app-text2); }
.kb-meta-sep { color: var(--app-text3); }
.kb-card-actions { display: flex; align-items: center; gap: 10px; border-top: 1px solid var(--app-border); padding-top: 8px; margin-top: auto; }
/* 建议分库：治理建议而非状态标记，弱化视觉权重（不与「默认/官方」抢焦点），靠颜色区分语义 */
.kb-mixed-tag { opacity: .75; font-weight: 400; }
.kb-mixed-tag:hover { opacity: 1; }
.kb-dim { color: var(--app-text3); font-size: 11px; }
.kb-params { color: var(--app-ok); font-size: 11px; }
.kb-warn { color: var(--app-danger); font-size: 11px; }
.kb-zero { color: var(--app-text3); }
.kb-empty { grid-column: 1 / -1; text-align: center; color: var(--app-text3); font-size: 12px; padding: 40px 0; }
.kb-hint { font-size: 12px; color: var(--app-text3); line-height: 1.6; margin-top: 4px; }
</style>
