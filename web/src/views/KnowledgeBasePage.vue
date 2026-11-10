<template>
  <div class="app-page">
    <div class="app-page-head">
      <div class="head-title">
        <h3 class="app-page-title">知识库</h3>
        <span class="head-hint-plain">文档的容器，检索按库隔离</span>
      </div>
      <div class="head-acts">
        <a-input-search v-model:value="search" placeholder="搜索知识库、描述…" allow-clear style="width:200px" />
        <a-radio-group v-model:value="view" size="small" button-style="solid">
          <a-radio-button value="grid"><appstore-outlined /></a-radio-button>
          <a-radio-button value="table"><unordered-list-outlined /></a-radio-button>
        </a-radio-group>
        <button class="app-btn ghost" @click="openImport()">
          <import-outlined /> 导入文档包
        </button>
        <button class="app-btn" @click="openWizard()">
          <plus-outlined /> 新建知识库
        </button>
      </div>
    </div>

    <div class="app-page-body">
      <a-spin :spinning="loading">
        <div class="statbar">
          <div class="stat stat-active" @click="resetFilters">
            <span class="sic"><book-outlined /></span>
            <div><div class="n">{{ statsView.kbCount }}</div><div class="l">个知识库</div></div>
            <span class="jump">全部</span>
          </div>
          <div class="stat" @click="healthFilter = 'all'">
            <span class="sic"><file-text-outlined /></span>
            <div><div class="n">{{ fmtNum(statsView.docCount) }}</div><div class="l">个文档</div></div>
            <span class="jump">查看</span>
          </div>
          <div class="stat" @click="sort = 'docs'">
            <span class="sic"><database-outlined /></span>
            <div><div class="n">{{ fmtNum(statsView.chunkCount) }}</div><div class="l">个知识块</div></div>
            <span class="jump">按规模</span>
          </div>
          <div class="stat" @click="sort = 'recent'">
            <span class="sic"><aim-outlined /></span>
            <div><div class="n">{{ fmtNum(statsView.hit90d) }}</div><div class="l">90 天命中</div></div>
            <span class="jump">按使用</span>
          </div>
          <div :class="['stat', { warn: statsView.attentionCount > 0 }]" @click="healthFilter = 'attention'">
            <span class="sic"><warning-outlined /></span>
            <div><div class="n">{{ statsView.attentionCount }}</div><div class="l">个需关注</div></div>
            <span class="jump">筛选</span>
          </div>
        </div>

        <div class="filterbar">
          <div class="seg">
            <span v-for="o in ownerOptions" :key="o.key"
                  :class="['seg-item', { on: owner === o.key }]" @click="owner = o.key">
              {{ o.label }} <i class="cnt">{{ ownerCount(o.key) }}</i>
            </span>
          </div>
          <div class="chips">
            <span :class="['chip', { on: healthFilter === 'all' }]" @click="healthFilter = 'all'">全部</span>
            <span :class="['chip', { on: healthFilter === 'attention', danger: statsView.attentionCount > 0 }]"
                  @click="healthFilter = 'attention'">
              <i class="dot"></i>需关注 {{ statsView.attentionCount }}
            </span>
            <span :class="['chip', { on: healthFilter === 'parsing' }]" @click="healthFilter = 'parsing'">正在解析</span>
            <span :class="['chip', { on: healthFilter === 'noEmbedding' }]" @click="healthFilter = 'noEmbedding'">未绑定模型</span>
            <span :class="['chip', { on: showArchived }]" @click="showArchived = !showArchived">已归档</span>
          </div>
          <span class="sp"></span>
          <span class="sel">
            <a-select v-model:value="sort" size="small" :bordered="false" style="width:104px" :options="sortOptions" />
          </span>
          <span class="result">{{ filtered.length }} / {{ raw.length }}</span>
        </div>

        <!-- 向量模型缺失：知识库的硬依赖，但只影响知识库，所以提示放在这里而不是侧栏 -->
        <div v-if="setupGuide.loaded && !embeddingReady" class="kb-embed-tip">
          <WarningOutlined />
          <span>知识库需要向量模型：先添加一个「向量」模型，才能创建知识库并检索文档。</span>
          <button v-if="canManageProviders" class="app-link-btn" type="button" @click="addEmbeddingModel">添加向量模型</button>
          <span v-else class="kb-tip-muted">请联系管理员开通「智能体」菜单权限</span>
        </div>

        <div v-if="view === 'grid'" class="kb-grid">
          <div v-for="kb in filtered" :key="kb.id"
               :class="['kb-card', { warn: kb.needAttention, archived: kb.archived === 1, empty: isEmptyKb(kb) }]"
               @click="openDrawer(kb)">
            <div class="kb-top">
              <KbIcon :kb="kb" :size="30" />
              <div class="kb-title">
                <div class="kb-name">
                  <span class="nm">{{ kb.name }}</span>
                  <span v-if="kb.isDefault === 1" class="badge mine">默认</span>
                  <span v-if="kb.builtin === 1" class="badge gold">官方</span>
                  <span v-if="kb.archived === 1" class="badge gray">已归档</span>
                  <span v-if="kb.needAttention" class="badge warn">需关注</span>
                </div>
                <div class="kb-desc" :title="kb.description || ''">{{ kb.description || '暂无描述' }}</div>
              </div>
              <span class="menu-wrap" @click.stop>
                <a-dropdown :trigger="['click']">
                  <button class="app-icon-btn"><more-outlined /></button>
                  <template #overlay>
                    <a-menu @click="({ key }) => onCardMenu(key, kb)">
                      <a-menu-item key="probe">试检索</a-menu-item>
                      <a-menu-item v-if="canManage(kb)" key="edit">编辑设置</a-menu-item>
                      <a-menu-item v-if="canManage(kb) && kb.builtin !== 1" key="graph">知识图谱</a-menu-item>
                      <a-menu-item v-if="canManage(kb) && kb.builtin !== 1" key="export">导出 zip</a-menu-item>
                      <a-menu-item v-if="kb.archived !== 1" key="copy">复制为模板</a-menu-item>
                      <a-menu-divider v-if="canManage(kb) && kb.isDefault !== 1 && kb.builtin !== 1" />
                      <a-menu-item v-if="canManage(kb) && kb.isDefault !== 1 && kb.builtin !== 1" key="archive">
                        {{ kb.archived === 1 ? '恢复知识库' : '归档（可恢复）' }}
                      </a-menu-item>
                      <a-menu-item v-if="canManage(kb) && kb.isDefault !== 1 && kb.builtin !== 1" key="delete" danger>
                        删除知识库…
                      </a-menu-item>
                    </a-menu>
                  </template>
                </a-dropdown>
              </span>
            </div>

            <div class="kb-nums">
              <div :class="['kb-num', { dim: isEmptyKb(kb) }]"><b>{{ kb.docTotal ?? kb.docCount ?? 0 }}</b><span>文档</span></div>
              <div :class="['kb-num', { dim: isEmptyKb(kb) }]"><b>{{ fmtNum(kb.chunkTotal || 0) }}</b><span>知识块</span></div>
              <div :class="['kb-num', { dim: isEmptyKb(kb) }]"><b>{{ fmtNum(kb.hit90d || 0) }}</b><span>90 天命中</span></div>
            </div>

            <div class="hbar">
              <i class="ok" :style="{ width: pct(kb, 'docActive') }"></i>
              <i class="run" :style="{ width: pct(kb, 'docParsing') }"></i>
              <i class="err" :style="{ width: pct(kb, 'docFailed') }"></i>
              <i class="off" :style="{ width: pct(kb, 'docDeprecated') }"></i>
            </div>
            <div class="kb-legend">
              <template v-if="isEmptyKb(kb)">
                <span><i class="d off"></i>还没有可检索内容</span>
              </template>
              <template v-else>
                <span><i class="d ok"></i>已入库 {{ kb.docActive || 0 }}</span>
                <span v-if="kb.docParsing"><i class="d run"></i>解析中 {{ kb.docParsing }}</span>
                <span v-if="kb.docFailed" class="danger"><i class="d err"></i>失败 {{ kb.docFailed }}</span>
              </template>
              <span class="push">{{ isEmptyKb(kb) ? '空库' : lastUsed(kb) }}</span>
            </div>

            <div class="kb-meta">
              <span :class="['mchip', { danger: !kb.embeddingRef }]">
                <database-outlined class="mini-ic" />
                {{ modelName(kb.embeddingRef) || '未绑定向量模型' }}
              </span>
              <span v-if="kb.queryParams || kb.parseParams" class="mchip ok">参数已自定义</span>
              <span v-else class="mchip">继承全局参数</span>
              <span class="ref">{{ kb.agentRefCount ? `被 ${kb.agentRefCount} 个智能体使用` : '未绑定智能体' }}</span>
            </div>

            <div class="kb-foot">
              <template v-if="isEmptyKb(kb)">
                <button class="app-btn sm" @click.stop="openImport(kb)"><import-outlined /> 上传资料</button>
                <button v-if="canManage(kb)" class="app-btn ghost sm" @click.stop="openEdit(kb)">编辑</button>
                <span class="upd">从模板开始 →</span>
              </template>
              <template v-else>
                <button class="app-btn sm" @click.stop="openDocs(kb)">{{ canManage(kb) ? '打开' : '查看' }}</button>
                <button class="app-btn ghost sm" @click.stop="openProbe(kb)">试检索</button>
                <button v-if="kbState(kb).danger" class="app-link-btn upd danger" @click.stop="openDocs(kb)">
                  {{ kbState(kb).text }}
                </button>
                <span v-else class="upd">{{ kbState(kb).text }}</span>
              </template>
            </div>
          </div>

          <div v-if="!loading && !filtered.length" class="kb-empty">
            <div class="empty-title">没有匹配的知识库</div>
            <div class="empty-sub">调整筛选，或新建一个知识库 / 导入已有文档包</div>
            <div class="empty-acts">
              <button class="app-btn" @click="openWizard()"><plus-outlined /> 新建知识库</button>
              <button class="app-btn ghost" @click="openImport()"><import-outlined /> 导入文档包</button>
              <button class="app-btn ghost" @click="openWizard()"><bulb-outlined /> 从模板开始</button>
            </div>
          </div>
        </div>

        <div v-else class="app-card table-card">
          <a-table :data-source="filtered" :columns="columns" size="small" row-key="id"
                   :pagination="{ pageSize: 20, hideOnSinglePage: true, size: 'small' }"
                   :custom-row="row => ({ onClick: () => openDrawer(row) })"
                   :locale="{ emptyText: '没有匹配的知识库' }">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'name'">
                <div class="t-name">
                  <KbIcon :kb="record" :size="22" />
                  <div>
                    <div class="t-title">
                      {{ record.name }}
                      <span v-if="record.isDefault === 1" class="badge mine">默认</span>
                      <span v-if="record.builtin === 1" class="badge gold">官方</span>
                      <span v-if="record.archived === 1" class="badge gray">已归档</span>
                    </div>
                    <div class="t-desc">{{ record.description || '暂无描述' }}</div>
                  </div>
                </div>
              </template>
              <template v-else-if="column.key === 'docs'">
                <b>{{ record.docTotal ?? record.docCount ?? 0 }}</b>
              </template>
              <template v-else-if="column.key === 'chunks'">{{ fmtNum(record.chunkTotal || 0) }}</template>
              <template v-else-if="column.key === 'hit'">{{ fmtNum(record.hit90d || 0) }}</template>
              <template v-else-if="column.key === 'health'">
                <span v-if="record.docFailed" class="app-pill err">失败 {{ record.docFailed }}</span>
                <span v-else-if="record.docParsing" class="app-pill warn">解析中 {{ record.docParsing }}</span>
                <span v-else class="app-pill ok">健康</span>
              </template>
              <template v-else-if="column.key === 'agents'">{{ record.agentRefCount || 0 }}</template>
              <template v-else-if="column.key === 'time'">{{ lastUsed(record) }}</template>
              <template v-else-if="column.key === 'act'">
                <span class="t-act" @click.stop>
                  <a-button size="small" type="link" @click="openProbe(record)">试检索</a-button>
                  <a-button size="small" type="link" @click="openDocs(record)">文档</a-button>
                </span>
              </template>
            </template>
          </a-table>
        </div>
      </a-spin>
    </div>

    <KbCreateWizard v-model:open="wizardOpen" :seed="wizardSeed" @created="onKbCreated" />
    <KnowledgeBaseEditModal v-model:open="showEdit" :kb="editing" @saved="load" />
    <KnowledgeGraphModal v-model:open="graphModal" :kb="graphKb" />
    <KbOverviewDrawer v-model:open="drawerOpen" :kb="drawerKb"
                      @probe="openProbeFromDrawer" @docs="openDocs" @edit="openEdit"
                      @graph="openGraph" @changed="load" />
    <KbProbeModal v-model:open="probeOpen" :kb="probeKb" :initial-query="probeQuery" />
    <KbImportModal v-model:open="importOpen" :kb-list="raw" :default-kb-id="defaultKbId"
                   :fixed-kb-id="importFixedKb" @done="onImported" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import {
  AimOutlined, AppstoreOutlined, BookOutlined, BulbOutlined, DatabaseOutlined, FileTextOutlined,
  ImportOutlined, MoreOutlined, PlusOutlined, UnorderedListOutlined, WarningOutlined
} from '@ant-design/icons-vue'
import {
  archiveKnowledgeBase, deleteKnowledgeBase, exportKnowledgeBase, getKbOverview,
  unarchiveKnowledgeBase
} from '../api'
import KnowledgeGraphModal from '../components/KnowledgeGraphModal.vue'
import KnowledgeBaseEditModal from '../components/KnowledgeBaseEditModal.vue'
import KbIcon from '../components/KbIcon.vue'
import KbCreateWizard from '../components/KbCreateWizard.vue'
import KbOverviewDrawer from '../components/KbOverviewDrawer.vue'
import KbProbeModal from '../components/KbProbeModal.vue'
import KbImportModal from '../components/KbImportModal.vue'
import { loadModelIndex, modelRefInfo } from '../utils/modelRef'
import { isAdminSync, ensureAuth, menuHasPath } from '../utils/auth'
import { embeddingReady, refreshSetupGuide, setupGuide } from '../utils/setupGuide'
import { mobileShell } from '../h5/mobile'

const isAdmin = isAdminSync()
const myUid = ref('')
const router = useRouter()
// 知识库缺口只在这里提示：向量模型属于可选能力，不占侧栏「还差 N 项」
const canManageProviders = computed(() => Boolean(isAdmin || menuHasPath('/agents')))
const addEmbeddingModel = () =>
  router.push('/agents?tab=providers&action=add-model&type=embedding&from=setup')

const loading = ref(false)
const raw = ref([])
const stats = ref({})
const search = ref('')
const owner = ref('all')
const healthFilter = ref('all')
const sort = ref('recent')
const showArchived = ref(false)
const view = ref('grid')

const showEdit = ref(false)
const editing = ref(null)
const wizardOpen = ref(false)
const wizardSeed = ref(null)
const graphModal = ref(false)
const graphKb = ref(null)
const drawerOpen = ref(false)
const drawerKb = ref(null)
const probeOpen = ref(false)
const probeKb = ref(null)
const probeQuery = ref('')
const importOpen = ref(false)
const importFixedKb = ref('')

const ownerOptions = [
  { key: 'all', label: '全部' },
  { key: 'mine', label: '我的' },
  { key: 'official', label: '官方' },
  { key: 'shared', label: '共享' }
]
const sortOptions = [
  { value: 'recent', label: '最近使用' },
  { value: 'docs', label: '文档数' },
  { value: 'name', label: '名称' }
]
const columns = [
  { title: '知识库', key: 'name', width: 360 },
  { title: '文档', key: 'docs', width: 70, align: 'right' },
  { title: '知识块', key: 'chunks', width: 90, align: 'right' },
  { title: '90 天命中', key: 'hit', width: 100, align: 'right' },
  { title: '健康', key: 'health', width: 110 },
  { title: '智能体', key: 'agents', width: 76, align: 'right' },
  { title: '最近使用', key: 'time', width: 110 },
  { title: '操作', key: 'act', width: 130 }
]

const statsView = computed(() => {
  const s = stats.value || {}
  if (s.kbCount != null) return s
  const docCount = raw.value.reduce((n, k) => n + Number(k.docTotal || 0), 0)
  const chunkCount = raw.value.reduce((n, k) => n + Number(k.chunkTotal || 0), 0)
  const hit90d = raw.value.reduce((n, k) => n + Number(k.hit90d || 0), 0)
  const attentionCount = raw.value.filter(k => k.needAttention).length
  return { kbCount: raw.value.length, docCount, chunkCount, hit90d, attentionCount }
})

const defaultKbId = computed(() => (raw.value.find(k => k.isDefault === 1) || {}).id || '')

const filtered = computed(() => {
  let arr = raw.value.filter(k => {
    if (!showArchived.value && k.archived === 1) return false
    if (search.value) {
      const q = search.value.toLowerCase()
      if (!String(k.name || '').toLowerCase().includes(q) &&
          !String(k.description || '').toLowerCase().includes(q)) return false
    }
    if (owner.value === 'mine' && !(k.createdBy === myUid.value && k.builtin !== 1)) return false
    if (owner.value === 'official' && k.builtin !== 1) return false
    if (owner.value === 'shared' && (k.createdBy === myUid.value || k.builtin === 1)) return false
    if (healthFilter.value === 'attention' && !k.needAttention) return false
    if (healthFilter.value === 'parsing' && !(Number(k.docParsing) > 0)) return false
    if (healthFilter.value === 'noEmbedding' && k.embeddingRef) return false
    return true
  })
  const t = v => (v ? new Date(String(v).replace(' ', 'T')).getTime() || 0 : 0)
  arr = [...arr].sort((a, b) => {
    // 默认库恒在最前：它是未指定归属文档的兜底，排序/筛选都不该把它挤到后面
    if ((a.isDefault || 0) !== (b.isDefault || 0)) return (b.isDefault || 0) - (a.isDefault || 0)
    if (sort.value === 'name') return String(a.name || '').localeCompare(String(b.name || ''), 'zh')
    if (sort.value === 'docs') return Number(b.docTotal || 0) - Number(a.docTotal || 0)
    return Math.max(t(b.lastUsedAt), t(b.updateTime)) - Math.max(t(a.lastUsedAt), t(a.updateTime))
  })
  return arr
})

onMounted(() => {
  load()
  refreshSetupGuide()  // 未配向量模型时在页头给出添加入口（TTL 去重，通常直接复用侧栏已拉到的状态）
  ensureAuth().then(me => { myUid.value = me.user || '' }).catch(() => {})
})

async function load () {
  loading.value = true
  try {
    loadModelIndex().catch(() => {})
    const r = await getKbOverview()
    const d = r.data || {}
    raw.value = d.knowledgeBases || []
    stats.value = d.stats || {}
  } catch (e) {
    message.error(e.message || '知识库列表加载失败')
  } finally {
    loading.value = false
  }
}

function canManage (kb) {
  return !mobileShell.value && (isAdmin || (kb.createdBy === myUid.value && kb.builtin !== 1))
}

function ownerCount (key) {
  return raw.value.filter(k => {
    if (!showArchived.value && k.archived === 1) return false
    if (key === 'mine') return k.createdBy === myUid.value && k.builtin !== 1
    if (key === 'official') return k.builtin === 1
    if (key === 'shared') return k.createdBy !== myUid.value && k.builtin !== 1
    return true
  }).length
}

function resetFilters () {
  owner.value = 'all'
  healthFilter.value = 'all'
  search.value = ''
  showArchived.value = false
  sort.value = 'recent'
}

function openDrawer (kb) {
  drawerKb.value = kb
  drawerOpen.value = true
}

const openDocs = kb => router.push(`/knowledge/${kb.id}/docs`)
const openProbe = kb => { probeKb.value = kb; probeQuery.value = ''; probeOpen.value = true }
const openProbeFromDrawer = ({ kb, query }) => {
  probeKb.value = kb
  probeQuery.value = query || ''
  probeOpen.value = true
}
const openEdit = kb => { editing.value = kb; showEdit.value = true }
const openGraph = kb => { graphKb.value = kb; graphModal.value = true }

function openWizard (seed = null) {
  wizardSeed.value = seed
  wizardOpen.value = true
}

function openCopyWizard (kb) {
  openWizard(kb)
}

function openImport (kb) {
  importFixedKb.value = kb?.id || ''
  importOpen.value = true
}

async function onKbCreated () {
  // 导入步骤已收进向导第三步（可选 zip），这里只刷新列表
  await load()
}

function onImported () {
  load()
}

async function onCardMenu (key, kb) {
  if (key === 'probe') openProbe(kb)
  else if (key === 'edit') openEdit(kb)
  else if (key === 'graph') openGraph(kb)
  else if (key === 'export') doExport(kb)
  else if (key === 'copy') openCopyWizard(kb)
  else if (key === 'archive') toggleArchive(kb)
  else if (key === 'delete') onDelete(kb)
}

async function doExport (kb) {
  try {
    await exportKnowledgeBase(kb.id, kb.name)
    message.success('导出完成')
  } catch (e) {
    message.error(e.message || '导出失败')
  }
}

function toggleArchive (kb) {
  const archived = kb.archived === 1
  Modal.confirm({
    title: archived ? `恢复知识库「${kb.name}」？` : `归档知识库「${kb.name}」？`,
    content: archived
      ? '恢复后会重新出现在默认列表；不会自动恢复之前的智能体绑定。'
      : `归档后保留文档与向量，但从列表默认视图、智能体绑定与检索中摘除${kb.agentRefCount ? `（将影响 ${kb.agentRefCount} 个智能体）` : ''}，可随时恢复。`,
    okText: archived ? '恢复' : '归档',
    cancelText: '取消',
    onOk: async () => {
      try {
        await (archived ? unarchiveKnowledgeBase(kb.id) : archiveKnowledgeBase(kb.id))
        message.success(archived ? '已恢复' : '已归档')
        await load()
      } catch (e) {
        message.error(e.message || '操作失败')
      }
    }
  })
}

function onDelete (kb) {
  Modal.confirm({
    title: `删除知识库「${kb.name}」？`,
    content: `将一并永久删除该库下的 ${kb.docTotal || 0} 个文档（含回收站）及其向量索引与图谱，且不可恢复；`
      + `${kb.agentRefCount ? `绑定的 ${kb.agentRefCount} 个智能体会自动摘除这个知识库。` : '当前没有智能体绑定这个库。'}`,
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      const hide = message.loading('正在删除知识库及其全部文档…', 0)
      try {
        const r = await deleteKnowledgeBase(kb.id)
        if (r && r.success === false) { message.warning(r.msg || '无法删除'); return }
        message.success('已删除')
        await load()
      } catch (e) {
        message.error(e.message || '删除失败')
      } finally {
        hide()
      }
    }
  })
}

function pct (kb, key) {
  const total = Number(kb.docTotal || 0)
  if (!total) return '0%'
  return `${(Number(kb[key] || 0) / total) * 100}%`
}

/** 空库：上传前没有任何文档与知识块，卡面走虚线/置灰变体 */
function isEmptyKb (kb) {
  return Number(kb.docTotal ?? kb.docCount ?? 0) === 0 && Number(kb.chunkTotal || 0) === 0 && kb.archived !== 1
}

/** 卡底状态文案：失败 > 解析队列 > 图谱构建 > 零命中 > 只读 > 图谱规模 */
function kbState (kb) {
  if (kb.archived === 1) return { text: '' }
  if (Number(kb.docFailed || 0) > 0) return { text: '查看失败 →', danger: true }
  if (Number(kb.docParsing || 0) > 0) return { text: `解析队列 ${kb.docParsing}` }
  if (kb.graph?.building) return { text: '图谱构建中' }
  if (Number(kb.docTotal || 0) > 0 && !Number(kb.hit90d || 0)) return { text: '90 天零命中' }
  if (!canManage(kb)) return { text: '只读权限' }
  if (kb.graph?.triples) return { text: `图谱 ${kb.graph.entities} 实体` }
  return { text: '' }
}

function modelName (ref) {
  return modelRefInfo(ref)?.displayName || (ref ? String(ref).split('/').pop() : '')
}

function fmtNum (n) {
  return Number(n || 0).toLocaleString('zh-CN')
}

function lastUsed (kb) {
  const t = kb.lastUsedAt || kb.updateTime
  if (!t) return '从未使用'
  const d = new Date(String(t).replace(' ', 'T'))
  if (Number.isNaN(d.getTime())) return String(t).slice(0, 10)
  const diff = Date.now() - d.getTime()
  if (diff < 60000) return '刚刚'
  if (diff < 3600000) return `${Math.floor(diff / 60000)} 分钟前`
  if (diff < 86400000) return `${Math.floor(diff / 3600000)} 小时前`
  if (diff < 86400000 * 30) return `${Math.floor(diff / 86400000)} 天前`
  return `${d.getMonth() + 1}-${d.getDate()}`
}
</script>

<style scoped>
.head-title { display: flex; align-items: center; gap: 12px; min-width: 0; }
.head-acts { margin-left: auto; display: flex; align-items: center; gap: 8px; flex: none; }
.statbar { display: flex; gap: 10px; margin-bottom: 12px; }
.stat { flex: 1; background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 8px;
  padding: 9px 13px; display: flex; align-items: center; gap: 10px; cursor: pointer; }
.stat:hover { border-color: var(--app-accent-border); }
.stat .sic { width: 30px; height: 30px; border-radius: 8px; background: var(--app-panel-2); display: inline-flex;
  align-items: center; justify-content: center; color: var(--app-text2); flex: none; font-size: 15px; }
.stat .n { font-size: 18px; font-weight: 650; line-height: 1.2; }
.stat .l { font-size: 11px; color: var(--app-text3); }
.stat .jump { margin-left: auto; font-size: 10.5px; color: var(--app-text3); }
.stat.warn { border-color: var(--app-warn-border); background: #fffdf7; }
.stat.warn .n { color: var(--app-warn-text); }
.stat.warn .sic { background: var(--app-warn-weak); color: var(--app-warn); }
.stat.stat-active { border-color: var(--app-accent-border); box-shadow: 0 0 0 3px rgba(46,107,230,.08); }
.stat.stat-active .sic { background: var(--app-accent-weak); color: var(--app-accent); }
.filterbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.seg { display: inline-flex; background: #eceff3; border-radius: 7px; padding: 2px; }
.seg-item { height: 24px; padding: 0 11px; display: inline-flex; align-items: center; gap: 5px; border-radius: 5px;
  font-size: 12px; color: var(--app-text2); cursor: pointer; }
.seg-item.on { background: #fff; color: var(--app-text); font-weight: 600; box-shadow: 0 1px 2px rgba(16,24,40,.04); }
.seg-item .cnt { font-style: normal; font-size: 10.5px; color: var(--app-text3); }
.chips { display: inline-flex; gap: 6px; align-items: center; }
.chip { height: 24px; padding: 0 10px; display: inline-flex; align-items: center; gap: 5px; border-radius: 999px;
  border: 1px solid var(--app-border-strong); background: #fff; font-size: 11.5px; color: var(--app-text2); cursor: pointer; }
.chip.on { background: var(--app-accent-weak); border-color: var(--app-accent-border); color: var(--app-accent); font-weight: 500; }
.chip.danger { color: var(--app-warn-text); border-color: var(--app-warn-border); background: #fffdf7; }
.chip .dot { width: 5px; height: 5px; border-radius: 50%; background: currentColor; }
.filterbar .sp { flex: 1; }
.sel { height: 26px; display: inline-flex; align-items: center; border: 1px solid var(--app-border-strong);
  border-radius: 6px; background: #fff; }
.result { font-size: 11.5px; color: var(--app-text3); }
/* 向量模型缺失提示：知识库能力的前置条件，只在知识库页出现 */
.kb-embed-tip {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  margin: 0 0 12px; padding: 9px 12px; border-radius: 8px; font-size: 12px;
  color: var(--app-warn-text); background: var(--app-warn-weak); border: 1px solid var(--app-warn-border);
}
.kb-embed-tip .app-link-btn { margin-left: auto; font-size: 12px; }
.kb-tip-muted { color: var(--app-text3); font-size: 11.5px; }
.kb-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; align-items: stretch; }
.kb-card { background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 10px; padding: 13px 14px 11px;
  display: flex; flex-direction: column; gap: 0; cursor: pointer; transition: border-color .15s, box-shadow .15s; min-height: 218px; }
.kb-card:hover { border-color: var(--app-accent-border); box-shadow: 0 4px 16px -6px rgba(46,107,230,.25); }
.kb-card.warn { border-color: var(--app-warn-border); }
.kb-card.archived { background: var(--app-panel-2); opacity: .8; }
.kb-card.empty { border-style: dashed; background: var(--app-panel-2); }
.kb-top { display: flex; align-items: flex-start; gap: 9px; min-width: 0; }
.kb-title { min-width: 0; flex: 1; }
.kb-name { display: flex; align-items: center; gap: 6px; font-size: 13.5px; font-weight: 600; line-height: 1.4; }
.kb-name .nm { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.badge { flex: none; height: 17px; padding: 0 6px; border-radius: 4px; font-size: 10px; font-weight: 500;
  display: inline-flex; align-items: center; }
.badge.mine { background: var(--app-accent-weak); color: var(--app-accent); }
.badge.gold { background: #fdf6e3; color: #a86b12; }
.badge.gray { background: #eef0f3; color: var(--app-text3); }
.badge.warn { background: var(--app-warn-weak); color: var(--app-warn-text); }
.kb-desc { font-size: 11.5px; color: var(--app-text3); line-height: 1.55; margin-top: 6px; min-height: 36px;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.menu-wrap { flex: none; }
.kb-nums { display: flex; align-items: center; margin-top: 8px; }
.kb-num { flex: 1; min-width: 0; }
.kb-num b { display: block; font-size: 17px; font-weight: 650; line-height: 1.2; }
.kb-num span { font-size: 10.5px; color: var(--app-text3); }
.kb-num.dim b { color: var(--app-text3); }
.kb-num + .kb-num { border-left: 1px solid var(--app-border); padding-left: 12px; }
.hbar { display: flex; height: 5px; border-radius: 999px; overflow: hidden; background: #eef0f3; margin-top: 9px; }
.hbar i { display: block; height: 100%; }
.hbar .ok { background: var(--app-ok); } .hbar .run { background: var(--app-warn); }
.hbar .err { background: var(--app-danger); } .hbar .off { background: #c9cfd6; }
.kb-legend { display: flex; align-items: center; gap: 9px; margin-top: 5px; font-size: 10.5px; color: var(--app-text3); flex-wrap: wrap; }
.kb-legend .d { width: 5px; height: 5px; border-radius: 50%; display: inline-block; margin-right: 3px; }
.kb-legend .d.ok { background: var(--app-ok); } .kb-legend .d.run { background: var(--app-warn); }
.kb-legend .d.err { background: var(--app-danger); } .kb-legend .d.off { background: #c9cfd6; }
.kb-legend .danger { color: var(--app-danger); }
.kb-legend .push { margin-left: auto; }
.kb-meta { display: flex; align-items: center; gap: 6px; margin-top: 7px; min-width: 0; }
.mchip { height: 19px; padding: 0 6px; border-radius: 4px; font-size: 10.5px; display: inline-flex; align-items: center; gap: 4px;
  background: var(--app-panel-2); border: 1px solid var(--app-border); color: var(--app-text2); white-space: nowrap; }
.mchip.ok { color: var(--app-ok); background: var(--app-ok-weak); border-color: transparent; }
.mchip.danger { color: var(--app-danger); background: #fff2f0; border-color: transparent; }
.mini-ic { width: 10px; height: 10px; }
.kb-meta .ref { margin-left: auto; font-size: 10.5px; color: var(--app-text3); white-space: nowrap; }
.kb-foot { display: flex; align-items: center; gap: 6px; border-top: 1px solid var(--app-border); margin-top: auto; padding-top: 9px; }
.app-btn.sm, .app-btn.ghost.sm { padding: 4px 10px; font-size: 11.5px; }
.kb-foot .upd { margin-left: auto; font-size: 10.5px; color: var(--app-text3); }
.kb-foot .upd.danger { color: var(--app-danger); }
.kb-foot .app-link-btn.upd { margin-left: auto; }
.kb-empty { grid-column: 1 / -1; text-align: center; padding: 60px 0; }
.empty-title { font-size: 14px; font-weight: 600; color: var(--app-text2); }
.empty-sub { font-size: 12px; color: var(--app-text3); margin-top: 6px; }
.empty-acts { display: flex; justify-content: center; gap: 8px; margin-top: 16px; }
.table-card { padding: 0; overflow: hidden; }
.t-name { display: flex; align-items: center; gap: 9px; min-width: 0; }
.t-title { display: flex; align-items: center; gap: 5px; font-size: 12.5px; font-weight: 500; }
.t-desc { font-size: 10.5px; color: var(--app-text3); margin-top: 1px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 300px; }
.t-act { display: inline-flex; gap: 2px; }
:deep(.ant-table-row) { cursor: pointer; }
</style>
