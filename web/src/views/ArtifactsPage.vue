<template>
  <div class="app-page">
    <div class="app-page-head">
      <h1 class="app-page-title">我的产物</h1>
      <span class="head-hint-plain">模型在回答里生成的可下载文件（Markdown / CSV / JSON / HTML），按用户归属保存</span>
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
      <a-spin :spinning="loading">
        <div v-if="!rows.length" class="app-card art-empty">
          <p class="art-empty-title">{{ keyword.trim() ? '没有匹配的产物' : '还没有产物' }}</p>
          <p class="head-hint-plain">
            {{ keyword.trim() ? '换个关键词试试' : '在对话里让 AI「把结果整理成一份清单 / 表格」，生成的文件会出现在这里' }}
          </p>
        </div>

        <div v-else class="art-list">
          <div v-for="r in rows" :key="r.id" class="app-card art-row" :class="{ picked: selectedIds.includes(r.id) }">
            <a-checkbox :checked="selectedIds.includes(r.id)" @change="e => toggle(r.id, e.target.checked)" />
            <file-text-outlined class="art-icon" />
            <div class="art-main">
              <div class="art-line">
                <span class="art-name">{{ r.filename }}</span>
                <span class="art-chip">{{ r.ext || 'file' }}</span>
              </div>
              <div class="art-sub">
                <span>{{ fmtSize(r.size) }}</span>
                <span class="art-dot">·</span>
                <span>{{ fmtTime(r.createTime) }}</span>
                <template v-if="r.description">
                  <span class="art-dot">·</span>
                  <span class="art-desc">{{ r.description }}</span>
                </template>
                <span class="art-dot">·</span>
                <span :class="['art-expire', { warn: expireSoon(r.expireTime) }]"
                      :title="r.expireTime ? '预计清理时间：' + fmtTime(r.expireTime) : '产物不会被自动清理'">
                  {{ fmtExpire(r.expireTime) }}
                </span>
              </div>
            </div>
            <!-- 操作区独立成组：窄屏整组换行右对齐（见 .art-acts 的媒体查询） -->
            <div class="art-acts">
              <a :href="r.url" :download="r.filename" class="app-btn ghost small" title="下载">
                <download-outlined /> 下载
              </a>
              <button class="app-btn ghost small art-del" title="删除" @click="askDelete(r)">
                <delete-outlined />
              </button>
            </div>
          </div>
        </div>
      </a-spin>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { ReloadOutlined, FileTextOutlined, DownloadOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import { listArtifacts, deleteArtifact, deleteArtifactsBatch } from '../api'

const rows = ref([])
const loading = ref(false)
const keyword = ref('')
const selectedIds = ref([])
const batchLoading = ref(false)

const load = async () => {
  loading.value = true
  try {
    const r = await listArtifacts(keyword.value.trim())
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

onMounted(load)
</script>

<style scoped>
.art-list { display: flex; flex-direction: column; gap: 8px; }
.art-row { display: flex; align-items: center; gap: 12px; }
.art-row.picked { border-color: var(--app-accent); }
.art-icon { font-size: 18px; color: var(--app-accent); }
.art-main { flex: 1; min-width: 0; }
.art-line { display: flex; align-items: center; gap: 8px; }
.art-name { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.art-chip {
  font-size: 11px; padding: 1px 6px; border-radius: 4px;
  background: var(--app-accent-weak); color: var(--app-accent);
}
.art-sub { margin-top: 2px; font-size: 12px; color: var(--app-text3); display: flex; gap: 4px; min-width: 0; }
.art-desc { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.art-dot { opacity: 0.6; }
.art-expire { flex-shrink: 0; }
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
  .art-row { flex-wrap: wrap; row-gap: 0; }
  .art-main { flex: 1 1 auto; }
  .art-acts { width: 100%; justify-content: flex-end; }
  .art-acts > .app-btn { min-height: 44px; padding: 0 14px; }
  /* 行内勾选：把点击区从 16px 撑到 30×44（antd 的 wrapper 本身就是可点区域） */
  .art-row :deep(.ant-checkbox-wrapper) { width: 30px; height: 44px; display: inline-flex; align-items: center; }
}
</style>
