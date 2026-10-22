<template>
  <div class="app-page">
    <div class="app-page-head">
      <h1 class="app-page-title">我的产物</h1>
      <span class="head-hint-plain">模型在回答里生成的可下载文件</span>
      <!-- 工具条分两组：窄屏各自占一整行（搜索行 / 批量行），宽屏保持原来的单行排布 -->
      <div class="art-tools">
        <a-input v-model:value="keyword" placeholder="按文件名搜索" allow-clear class="art-search"
                 @press-enter="load" @change="onKeywordChange" />
        <button class="app-btn" :disabled="loading" @click="load">
          <reload-outlined /> 刷新
        </button>
      </div>
      <div v-if="rows.length" class="art-batch">
        <a-checkbox :checked="allChecked" :indeterminate="someChecked" @change="toggleAll">全选</a-checkbox>
        <button v-if="selectedIds.length" class="app-btn ghost small art-del" :disabled="batchLoading"
                @click="askBatchDelete">
          <delete-outlined /> 删除选中（{{ selectedIds.length }}）
        </button>
      </div>
    </div>

    <div class="app-page-body">
      <!-- 工作区筛选：只列用户自己建的工作区，与侧栏「工作区」分区同一口径（implicit 兜底容器是归属兜底、
           没挂工作区只是存量状态，都不值得占一档）；建工作区的入口在侧栏那一区的 ＋，不在这里重复一个 -->
      <div v-if="realProjects.length" class="art-filters">
        <button :class="['art-filter', { active: !activeProject }]" @click="selectProject('')">全部</button>
        <button v-for="p in realProjects" :key="p.id" :class="['art-filter', { active: activeProject === p.id }]"
                @click="selectProject(p.id)">{{ p.displayName }}</button>
      </div>

      <a-spin :spinning="loading">
        <div v-if="!rows.length" class="app-card art-empty">
          <p class="art-empty-title">{{ keyword.trim() ? '没有匹配的产物' : '还没有产物' }}</p>
          <p class="head-hint-plain">
            {{ keyword.trim() ? '换个关键词试试' : '在对话里让 AI「把结果整理成一份清单 / 表格」，生成的文件会出现在这里' }}
          </p>
        </div>

        <div v-else class="art-list">
          <template v-for="g in groups" :key="g.key || '__unassigned__'">
            <!-- 只有一个分组时不显示分组头：存量产物全在「会话」这一档，加个标题反而像告警 -->
            <div v-if="groups.length > 1" class="art-group-head">
              <span class="art-group-name">{{ g.title }}</span>
              <span class="art-group-count">{{ g.rows.length }}</span>
            </div>
            <div v-for="r in g.rows" :key="r.id" class="app-card art-row" :class="{ picked: selectedIds.includes(r.id) }">
              <a-checkbox :checked="selectedIds.includes(r.id)" @change="e => toggle(r.id, e.target.checked)" />
              <file-text-outlined class="art-icon" />
              <div class="art-main">
                <div class="art-line">
                  <span class="art-name" :title="r.filename">{{ r.filename }}</span>
                  <span class="art-chip">{{ r.ext || 'file' }}</span>
                </div>
                <!-- 元信息只放不会变长的三项；说明单独一行——它和文件名一样长，
                     挤在同一行时必然把大小/日期压得内部换行（「45.9 / KB」那种碎排） -->
                <div class="art-sub">
                  <span>{{ fmtSize(r.size) }}</span>
                  <span class="art-dot">·</span>
                  <span>{{ fmtTime(r.createTime) }}</span>
                  <span class="art-dot">·</span>
                  <span :class="['art-expire', { warn: expireSoon(r.expireTime) }]"
                        :title="r.expireTime ? '预计清理时间：' + fmtTime(r.expireTime) : '产物不会被自动清理'">
                    {{ fmtExpire(r.expireTime) }}
                  </span>
                </div>
                <div v-if="r.description" class="art-desc" :title="r.description">{{ r.description }}</div>
              </div>
              <!-- 操作区独立成组：窄屏整组换行右对齐（见 .art-acts 的媒体查询） -->
              <div class="art-acts">
                <!-- 预览＝进预览台：页面设计档给真实视口（可切 375/768/1440 与自定义宽），读字档把
                     Markdown/表格/代码在应用里排好版。安全性仍靠服务端那份 CSP sandbox 头
                     （见 ArtifactSandboxInterceptor）：产物是模型生成的 HTML，不带该头时这一份内容等于
                     让模型脚本跑在本域源上、能读登录令牌。哪些类型怎么摆见 utils/artifactView（一份判据），
                     聊天区与工作区页共用。href 是真签名地址，Ctrl/中键点击照旧开成新标签页。 -->
                <a v-if="canPreview(r)" :href="resolveImg(r.url)" target="_blank" rel="noopener"
                   data-pv :data-pv-name="r.filename" :data-pv-ext="artifactExt(r)"
                   :data-pv-size="r.size" :data-pv-id="r.id"
                   class="app-btn ghost small" title="预览（按住 Ctrl 或中键开成新标签页）">
                  <eye-outlined /> 预览
                </a>
                <a :href="resolveImg(r.url)" :download="r.filename" class="app-btn ghost small" title="下载">
                  <download-outlined /> 下载
                </a>
                <button class="app-btn ghost small" title="移动到工作区" @click="askMove(r)">
                  <folder-open-outlined />
                </button>
                <button class="app-btn ghost small art-del" title="删除" @click="askDelete(r)">
                  <delete-outlined />
                </button>
              </div>
            </div>
          </template>
        </div>
      </a-spin>

      <!-- 移动到工作区：产物归用户不归会话，但工作区归属是「从哪个工作区交出来的」的唯一记录，
           归错比没归更难纠正，这里给事后整理的入口 -->
      <MoveToWorkspaceModal v-model:open="moveOpen"
                            :projects="projects"
                            :current-id="moveTarget ? (moveTarget.projectId || '') : ''"
                            title="移动产物到工作区"
                            :note="MOVE_ARTIFACT_NOTE"
                            :saving="moveSaving"
                            @moved="doMoveArtifact" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { ReloadOutlined, FileTextOutlined, DownloadOutlined, DeleteOutlined, FolderOpenOutlined, EyeOutlined } from '@ant-design/icons-vue'
import { listArtifacts, listProjects, deleteArtifact, deleteArtifactsBatch, moveArtifactProject } from '../api'
import { resolveImg } from '../utils/markdown'
import { canPreview, artifactExt } from '../utils/artifactView'
import MoveToWorkspaceModal from '../components/MoveToWorkspaceModal.vue'

const rows = ref([])
// 工作区容器：产物按工作区分组展示。空数组时退化成原来的单列表（接口挂掉也不该让页面白屏）
const projects = ref([])
// 筛选条只认用户真实建的工作区：implicit 兜底容器不再单独占一档展示
const realProjects = computed(() => projects.value.filter(p => p.selectionStatus !== 'implicit'))
/** implicit 兜底容器的 id：它的产物在分组时并入「会话」栏，不再单独出一个兜底容器分组 */
const implicitProjectId = computed(() => (projects.value.find(p => p.selectionStatus === 'implicit') || {}).id || '')
// 当前筛选的工作区：'' = 全部；其余 = 工作区 id
const activeProject = ref('')
const loading = ref(false)
const keyword = ref('')
const selectedIds = ref([])
const batchLoading = ref(false)

const load = async () => {
  loading.value = true
  try {
    const r = await listArtifacts(keyword.value.trim(), activeProject.value)
    rows.value = (r && r.data) || []
    // 勾选只保留当前列表里仍存在的产物（搜索/刷新后失效的勾选自动剔除）
    const ids = new Set(rows.value.map(x => x.id))
    selectedIds.value = selectedIds.value.filter(id => ids.has(id))
  } catch (e) {
    // 列表失败要说清（不许静默空列表 ⇒ 用户会以为产物丢了）
    message.error('产物列表加载失败：' + (e.message || '请刷新重试'))
    rows.value = []
  } finally {
    loading.value = false
  }
}

// 切工作区 = 换一次查询（服务端过滤，而不是前端筛：产物可能很多）
const selectProject = id => {
  if (activeProject.value === id) return
  activeProject.value = id
  selectedIds.value = []
  load()
}

// 工作区列表单独加载：失败不影响产物列表（分组退化成"会话"一档，功能仍可用）
const loadProjects = async () => {
  try {
    const r = await listProjects()
    projects.value = (r && r.data) || []
  } catch (e) {
    projects.value = []
  }
}

/**
 * 按工作区分组。
 * 排序：真实工作区（接口已按此序返回）→「会话」永远最后。
 * implicit 兜底容器的产物并入「会话」，不再出现「兜底容器」这个分组；
 * 「会话」就是侧栏那一栏的名字——没有工作区的产物不另起一个名词，免得界面上多出一种归属状态。
 */
const groups = computed(() => {
  const byKey = new Map()
  for (const r of rows.value) {
    const raw = r.projectId || ''
    const k = raw && raw === implicitProjectId.value ? '' : raw
    if (!byKey.has(k)) byKey.set(k, [])
    byKey.get(k).push(r)
  }
  const out = []
  for (const p of projects.value) {
    if (p.selectionStatus === 'implicit') continue
    if (byKey.has(p.id)) out.push({ key: p.id, title: p.displayName || '未命名工作区', rows: byKey.get(p.id) })
  }
  // 接口里已经不存在的工作区（被删）单独兜住，不让这些产物的分组凭空消失
  for (const [k, list] of byKey) {
    if (!k) continue
    if (!projects.value.some(p => p.id === k)) out.push({ key: k, title: '已删除的工作区', rows: list })
  }
  if (byKey.has('')) out.push({ key: '', title: '会话', rows: byKey.get('') })
  return out
})

// 清空搜索框立即恢复全量（不然用户以为产物少了）
const onKeywordChange = e => {
  if (!e || !e.target || e.target.value === '') load()
}

const fmtSize = n => {
  const v = Number(n) || 0
  if (v >= 1024 * 1024) return (v / 1024 / 1024).toFixed(2) + ' MB'
  if (v >= 1024) return (v / 1024).toFixed(1) + ' KB'
  return v + ' B'
}

const fmtTime = s => (s ? String(s).replace('T', ' ').slice(0, 16) : '—')

// 清理时间相对文案：expireTime 为空 = 保留天数为 0（永不清理）。快到期（≤3 天）标警示色。
const DAY_MS = 24 * 3600 * 1000
const expireDaysLeft = t => {
  if (!t) return null
  const exp = new Date(String(t).replace(' ', 'T')).getTime()
  if (Number.isNaN(exp)) return null
  return (exp - Date.now()) / DAY_MS
}
const fmtExpire = t => {
  const d = expireDaysLeft(t)
  if (d === null) return '永久保留'
  if (d <= 0) return '即将清理'
  if (d < 1) return '1 天内清理'
  return `${Math.ceil(d)} 天后清理`
}
const expireSoon = t => {
  const d = expireDaysLeft(t)
  return d !== null && d <= 3
}

// ==================== 移动到工作区（产物归属可改） ====================
const MOVE_ARTIFACT_NOTE = '移动只改归属，文件本身不动；已下载过的链接不受影响。'
const moveOpen = ref(false)
const moveTarget = ref(null)
const moveSaving = ref(false)
const askMove = r => { moveTarget.value = r; moveOpen.value = true }
const doMoveArtifact = async projectId => {
  const r = moveTarget.value
  if (!r) return
  moveSaving.value = true
  try {
    await moveArtifactProject(r.id, projectId)
    message.success(projectId ? '已移动到该工作区' : '已摘掉工作区归属')
    moveOpen.value = false
    await load()
  } catch (e) {
    message.error('移动失败：' + (e.message || '请重试'))
  } finally {
    moveSaving.value = false
  }
}

// ==================== 删除确认：Modal.confirm（含手机） ====================
// 原先单条与批量都用 a-popconfirm：click 触发的小浮层在手机/iab（触屏）上不可靠，
// 而 /artifacts 现已进窄屏白名单；Modal.confirm 与侧栏删会话（AppLayout）同一口径，
// 全端统一，不再按设备分支——浮层可靠性问题在 PC 上并不存在，没必要保留两套
const askDelete = row => Modal.confirm({
  title: `删除「${row.filename}」？`,
  content: '将删除该产物及其文件，删除后不可恢复。',
  okText: '删除', okType: 'danger', cancelText: '取消',
  onOk: () => doDelete(row)
})
const askBatchDelete = () => Modal.confirm({
  title: `删除选中的 ${selectedIds.value.length} 个产物？`,
  content: '将同时删除文件，删除后不可恢复。',
  okText: '删除', okType: 'danger', cancelText: '取消',
  onOk: () => doBatchDelete()
})

// ==================== 勾选 / 批量删除 ====================
const allChecked = computed(() => rows.value.length > 0 && selectedIds.value.length === rows.value.length)
const someChecked = computed(() => selectedIds.value.length > 0 && selectedIds.value.length < rows.value.length)

const toggle = (id, checked) => {
  selectedIds.value = checked
    ? [...selectedIds.value, id]
    : selectedIds.value.filter(x => x !== id)
}
const toggleAll = e => {
  selectedIds.value = e.target.checked ? rows.value.map(x => x.id) : []
}

const doDelete = async row => {
  try {
    const r = await deleteArtifact(row.id)
    if (r && r.success) {
      message.success('已删除')
      selectedIds.value = selectedIds.value.filter(id => id !== row.id)
      await load()
    } else {
      message.error((r && r.msg) || '删除失败')
    }
  } catch (e) {
    message.error('删除失败：' + (e.message || ''))
  }
}

const doBatchDelete = async () => {
  batchLoading.value = true
  try {
    const r = await deleteArtifactsBatch(selectedIds.value)
    if (r && r.success) {
      const d = (r && r.data) || {}
      const skipped = d.skipped || []
      if (skipped.length) {
        message.warning(`已删除 ${d.deleted} 项，${skipped.length} 项未能删除（不存在或无权限）`)
      } else {
        message.success(`已删除 ${d.deleted} 项`)
      }
      selectedIds.value = []
      await load()
    } else {
      message.error((r && r.msg) || '批量删除失败')
    }
  } catch (e) {
    message.error('批量删除失败：' + (e.message || ''))
  } finally {
    batchLoading.value = false
  }
}

onMounted(() => {
  loadProjects()   // 分组标题要用；失败只让分组退化成"没挂工作区"，不阻塞产物列表
  load()
})
</script>

<style scoped>
.art-list { display: flex; flex-direction: column; gap: 8px; }
/* 工作区筛选条：横向可滚动，窄屏不换行挤成两排 */
.art-filters {
  display: flex; align-items: center; gap: 6px;
  margin: 0 0 12px; padding-bottom: 2px;
  overflow-x: auto; white-space: nowrap;
}
.art-filter {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 3px 10px; border-radius: 14px; cursor: pointer;
  font-size: 12px; line-height: 18px;
  border: 1px solid var(--app-border); background: transparent; color: var(--app-text2, var(--app-text3));
}
.art-filter:hover { border-color: var(--app-accent); color: var(--app-accent); }
.art-filter.active { border-color: var(--app-accent); background: var(--app-accent-weak); color: var(--app-accent); }
/* 分组头：只在产物跨多个工作区时出现（单组时不渲染，见模板注释） */
.art-group-head {
  display: flex; align-items: center; gap: 8px;
  margin-top: 10px; padding: 0 2px 2px;
  font-size: 12px; color: var(--app-text3);
}
.art-group-head:first-child { margin-top: 0; }
.art-group-name { font-weight: 500; color: var(--app-text2, var(--app-text3)); }
.art-group-count {
  font-size: 11px; padding: 0 6px; border-radius: 8px;
  background: var(--app-accent-weak); color: var(--app-accent);
}
.art-row { display: flex; align-items: center; gap: 12px; }
.art-row.picked { border-color: var(--app-accent); }
.art-icon { flex: none; font-size: 18px; color: var(--app-accent); }
.art-main { flex: 1; min-width: 0; }
.art-line { display: flex; align-items: center; gap: 8px; min-width: 0; }
.art-name { font-weight: 500; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.art-chip {
  flex: none; font-size: 11px; padding: 1px 6px; border-radius: 4px;
  background: var(--app-accent-weak); color: var(--app-accent);
}
/* 元信息按「整项」换行，绝不项内换行：挤不下时把「90 天后清理」整个挪到下一行，
   而不是把「45.9 KB」劈成两截（此前每项默认可收缩，长说明一进来就出现碎排） */
.art-sub {
  margin-top: 2px; font-size: 12px; color: var(--app-text3);
  display: flex; flex-wrap: wrap; gap: 0 4px; min-width: 0;
}
.art-sub > span { flex: none; white-space: nowrap; }
.art-desc {
  margin-top: 2px; font-size: 12px; color: var(--app-text3);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.art-dot { opacity: 0.6; }
.art-expire.warn { color: var(--app-danger, var(--app-danger)); font-weight: 500; }
.art-del { color: var(--app-danger, var(--app-danger)); }
.art-empty { text-align: center; padding: 28px 16px; }
.art-empty-title { margin: 0 0 6px; font-weight: 500; }
.art-tools, .art-batch { display: contents; }   /* 宽屏：保持页头单行流式排布（与改动前一致） */
.art-search { width: 200px; margin-left: auto; }
.art-acts { display: flex; align-items: center; gap: 8px; flex: none; }

/* ==================== 窄屏（手机）：/artifacts 在守卫白名单里，这里做真适配 ====================
   页头两组各占一行、操作区换行右对齐、触摸热区加高；行内布局不变（卡片列表天然适合竖排） */
@media (max-width: 768px) {
  .app-page-head { align-items: baseline; }
  .art-tools, .art-batch { display: flex; align-items: center; gap: 8px; width: 100%; }
  .art-tools { margin-top: 2px; }
  .art-search { flex: 1; width: auto; min-width: 0; margin-left: 0; }
  /* 搜索框与「刷新」同排；批量行只在有选中时撑到右侧 */
  .art-batch { justify-content: flex-start; }
  .art-row { flex-wrap: wrap; row-gap: 0; column-gap: 8px; }
  /* 图标恒为同一个文档形状（类型已由 chip 标出），手机上把这 30px 让给文件名 */
  .art-icon { display: none; }
  /* 基准必须是 0 而非 auto：auto 让正文按内容宽度参与换行计算，结果勾选框被单独顶成一个行盒，
     看上去像浮在文件名上方的一块空白 */
  .art-main { flex: 1 1 0; min-width: 0; }
  .art-acts { width: 100%; justify-content: flex-end; }
  .art-acts > .app-btn { min-height: 44px; padding: 0 14px; }
  /* 行内勾选：把点击区从 16px 撑到 30×44（antd 的 wrapper 本身就是可点区域） */
  .art-row :deep(.ant-checkbox-wrapper) { width: 30px; height: 44px; display: inline-flex; align-items: center; }
}
</style>
