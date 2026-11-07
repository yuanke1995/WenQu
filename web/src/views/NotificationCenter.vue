<template>
  <div class="app-page notif-center">
    <div class="app-page-head">
      <h1 class="app-page-title">通知中心</h1>
      <span v-if="unreadCount" class="app-pill warn plain">{{ unreadCount }} 条未读</span>
      <span class="head-hint-plain" title="解析、工作流、网页源刷新、定时任务、审批等异步事件的归档与处理">
        解析、工作流、网页源刷新、定时任务、审批等异步事件都在这里
      </span>
      <div class="nc-head-acts">
        <button v-if="unreadCount > 0" class="app-btn ghost small" type="button" @click="markAll">
          全部已读
        </button>
        <button class="app-btn ghost small" type="button" @click="goSettings">
          <setting-outlined /> 通知与推送设置
        </button>
      </div>
    </div>

    <div class="app-page-body">
      <!-- 吸顶工具条：桶页签 + 搜索 + 类型 + 仅未读 + 批量入口 + 清空 -->
      <div class="nc-toolbar">
        <div class="nc-tabs" role="tablist" aria-label="通知分类">
          <button
            v-for="tab in bucketTabs"
            :key="tab.key"
            class="nc-tab"
            :class="{ on: bucket === tab.key }"
            type="button"
            role="tab"
            :aria-selected="bucket === tab.key"
            @click="onBucket(tab.key)"
          >
            {{ tab.label }}
            <span v-if="tab.count" class="nc-tab-badge" :class="tab.tone">{{ tab.count }}</span>
          </button>
        </div>

        <div class="nc-tools">
          <a-input
            v-model:value="keyword"
            class="nc-search"
            placeholder="搜索通知内容"
            allow-clear
          >
            <template #prefix><search-outlined /></template>
          </a-input>
          <span class="nc-switch">
            <a-switch v-model:checked="unreadOnly" size="small" @change="load" />
            <span>仅未读</span>
          </span>
          <a-select
            v-model:value="typeFilter"
            class="nc-select"
            :options="typeOptions"
            @change="onType"
          />
          <button class="app-btn ghost small" :class="{ 'nc-on': selectMode }" type="button"
                  @click="toggleSelectMode">
            {{ selectMode ? '退出选择' : '选择' }}
          </button>
          <a-dropdown :trigger="['click']">
            <button class="app-btn ghost small" type="button" :disabled="!items.length">
              清空 <down-outlined />
            </button>
            <template #overlay>
              <a-menu>
                <a-menu-item key="read" @click="confirmClear('read')">清空已读</a-menu-item>
                <a-menu-item key="all" @click="confirmClear('all')">清空全部</a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </div>
      </div>

      <!-- 筛选摘要：每个条件可单独清除 -->
      <div v-if="filterChips.length" class="nc-filterrow">
        <button v-for="chip in filterChips" :key="chip.key" class="nc-fchip" type="button"
                @click="chip.clear">
          {{ chip.label }} <span class="nc-fchip-x">×</span>
        </button>
        <button class="app-link-btn" type="button" @click="resetFilters">清除全部筛选</button>
      </div>

      <!-- 批量条：与列表一起滚动，操作后清空选择 -->
      <div v-if="selectMode" class="nc-batch">
        <span class="nc-batch-count">已选 {{ selected.length }} 条</span>
        <button class="app-btn ghost small" type="button" :disabled="!selected.length"
                @click="markSelectedRead">标记已读</button>
        <button class="app-btn ghost small nc-danger" type="button" :disabled="!selected.length"
                @click="confirmDeleteSelected">删除</button>
        <button class="app-btn ghost small" type="button" @click="selectAllLoaded">全选已加载</button>
        <button class="app-link-btn" type="button" @click="selected = []">取消选择</button>
      </div>

      <NotificationList
        :items="items"
        variant="page"
        :loading="loading"
        :error="error"
        :selectable="selectMode"
        :selected="selected"
        show-date-headers
        :grouped="grouped"
        :has-more="hasMore"
        :total="total"
        :show-total="bucket !== 'action'"
        :empty-title="emptyTitle"
        :empty-desc="emptyDesc"
        @open="openRow"
        @toggle-select="toggleSelect"
        @mark-unread="markUnread"
        @delete="removeOne"
        @load-more="loadMore"
        @retry="load"
      >
        <template #empty-action>
          <button class="app-btn ghost small" type="button" @click="goSettings">
            <setting-outlined /> 通知与推送设置
          </button>
        </template>
      </NotificationList>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { DownOutlined, SearchOutlined, SettingOutlined } from '@ant-design/icons-vue'
import {
  notificationList, notificationCounts, notificationMarkRead, notificationMarkAllRead,
  notificationDelete, notificationClear, notificationMarkUnread
} from '../api'
import {
  NOTIF_BUCKETS, NOTIF_BUCKET_ORDER, NOTIF_TYPES, emitNotifChanged, notifBucket, notifLabel,
  notifTarget
} from '../chat/useNotifications'
import NotificationList from '../components/notification/NotificationList.vue'

const route = useRoute()
const router = useRouter()

// ==================== 筛选状态 ====================
// 默认落在「待处理」：先回答有没有要我做的事，再看全部
// 入口可带 ?bucket= 指定落点（action/alert/done/all，如铃铛浮层「查看全部通知」→ all）
const initialBucket = [route.query.bucket].flat().find(b => b === 'all' || b in NOTIF_BUCKETS)
const bucket = ref(initialBucket || 'action')
const typeFilter = ref('')
const unreadOnly = ref(false)
const keyword = ref('')
const debouncedKeyword = ref('')
const selectMode = ref(false)

// ==================== 列表状态 ====================
const items = ref([])
const cursor = ref(0)
const hasMore = ref(false)
const total = ref(0)
const unreadCount = ref(0)
const loading = ref(false)
const error = ref('')
const selected = ref([])
const counts = ref({})

// 桶页签：待处理 / 异常 / 完成 / 全部；角标只数未读（与铃铛同一口径）
const bucketCounts = computed(() => {
  const out = { action: 0, alert: 0, done: 0 }
  for (const [type, n] of Object.entries(counts.value || {})) {
    const b = notifBucket(type)
    if (out[b] != null) out[b] += n
  }
  return out
})
// 页签顺序：待处理 → 全部 → 异常 → 完成（待处理与全部相邻，默认停在待处理）
const bucketTabs = computed(() => {
  const tabOf = key => ({
    key,
    label: NOTIF_BUCKETS[key].label,
    tone: NOTIF_BUCKETS[key].tone,
    count: bucketCounts.value[key] || 0
  })
  const others = NOTIF_BUCKET_ORDER.filter(k => k !== 'action').map(tabOf)
  return [tabOf('action'), { key: 'all', label: '全部', tone: '', count: 0 }, ...others]
})

const typeOptions = computed(() => [
  { value: '', label: '全部类型' },
  ...NOTIF_TYPES.map(t => ({
    value: t.type,
    label: t.label + (counts.value[t.type] ? ` (${counts.value[t.type]})` : '')
  }))
])

// 当前请求的类型集合：单类型优先；否则按桶；全部=空
const activeTypes = computed(() => {
  if (typeFilter.value) return [typeFilter.value]
  if (bucket.value !== 'all') return NOTIF_BUCKETS[bucket.value].types
  return []
})

// 搜索关键词非空时不做聚合：命中的每条都直接露出来
const grouped = computed(() => !debouncedKeyword.value)

const hasFilters = computed(() => !!(typeFilter.value || unreadOnly.value || debouncedKeyword.value))
const filterChips = computed(() => {
  const chips = []
  if (debouncedKeyword.value) {
    chips.push({
      key: 'q', label: `搜索：${debouncedKeyword.value}`,
      // 清空输入即由 keyword watcher 立即回拉，避免这里再 load 一次造成双请求
      clear: () => { keyword.value = '' }
    })
  }
  if (typeFilter.value) {
    chips.push({
      key: 'type', label: `类型：${notifLabel(typeFilter.value)}`,
      clear: () => { typeFilter.value = ''; load() }
    })
  }
  if (unreadOnly.value) {
    chips.push({
      key: 'unread', label: '仅未读',
      clear: () => { unreadOnly.value = false; load() }
    })
  }
  return chips
})

const emptyTitle = computed(() => {
  if (debouncedKeyword.value) return `没有找到「${debouncedKeyword.value}」`
  if (hasFilters.value) return '没有符合条件的通知'
  if (bucket.value === 'action') return '没有待处理的通知'
  return '还没有通知'
})
const emptyDesc = computed(() => {
  if (debouncedKeyword.value || hasFilters.value) return '换个关键词，或清除筛选后再看。'
  if (bucket.value === 'action') return '需要你处理的审批、提问或计划确认会出现在这里。'
  return '解析、工作流、定时任务或对话需要你处理时，会出现在这里。'
})

// ==================== 取数 ====================
const fetchPage = async reset => {
  loading.value = true
  if (reset) error.value = ''
  try {
    const r = await notificationList({
      limit: 50,
      types: activeTypes.value,
      q: debouncedKeyword.value || undefined,
      unreadOnly: unreadOnly.value || undefined,
      cursor: reset ? 0 : cursor.value
    })
    const body = r?.data || {}
    // 「待处理」桶只留还真的在等人的条目：底层审批/运行已处理的不再占位置
    let rows = body.items || []
    if (bucket.value === 'action') rows = rows.filter(n => n.actionable !== false)
    if (reset) {
      items.value = rows
      cursor.value = 0
    } else {
      items.value = [...items.value, ...rows]
    }
    hasMore.value = !!body.hasMore
    if (body.nextCursor != null) cursor.value = body.nextCursor
    total.value = body.total || 0
    unreadCount.value = body.unreadCount || 0
    error.value = ''
    // 选中项对账：被删/被筛掉的 id 移除
    const alive = new Set(items.value.map(x => x.id))
    selected.value = selected.value.filter(id => alive.has(id))
  } catch (e) {
    error.value = e.message || '通知加载失败'
    if (!reset) message.error(error.value)
    if (reset) items.value = []
  } finally {
    loading.value = false
  }
}
const load = () => fetchPage(true)
const loadMore = () => { if (!loading.value && hasMore.value) fetchPage(false) }

const refreshCounts = async () => {
  try {
    const r = await notificationCounts()
    counts.value = (r?.data?.counts) || {}
    unreadCount.value = r?.data?.unread || 0
  } catch { /* 计数失败不打扰列表 */ }
}

// 搜索防抖：300ms；清空时立即恢复
let searchTimer = null
const applyKeyword = () => {
  clearTimeout(searchTimer)
  if (!keyword.value.trim()) {
    debouncedKeyword.value = ''
    load()
    return
  }
  searchTimer = setTimeout(() => {
    debouncedKeyword.value = keyword.value.trim()
    load()
  }, 300)
}
watch(keyword, applyKeyword)

// ==================== 筛选交互 ====================
const onBucket = key => {
  if (bucket.value === key && !typeFilter.value) return
  bucket.value = key
  typeFilter.value = '' // 桶与单类型互斥：选桶即清类型
  load()
}
// 入口落点统一走这里：组件已挂载时再点「查看全部通知」只换 query 不重挂，靠 watcher 接住
const applyBucket = key => {
  if (bucket.value === key && !typeFilter.value) return
  bucket.value = key
  typeFilter.value = '' // 与选桶同口径：切桶即清类型
  load()
}
watch(() => route.query.bucket, v => {
  const b = [v].flat().find(x => x === 'all' || x in NOTIF_BUCKETS)
  if (b) applyBucket(b)
})
// 兜底：目标地址与当前完全一致时 vue-router 判重复导航，query 不变，由 AppLayout 派事件补一刀
const onNotifGoto = e => applyBucket(e.detail?.bucket || 'all')
const onType = v => {
  typeFilter.value = v || ''
  if (typeFilter.value) bucket.value = 'all' // 选类型即回到全部桶
  load()
}
const resetFilters = () => {
  keyword.value = ''
  debouncedKeyword.value = ''
  typeFilter.value = ''
  unreadOnly.value = false
  bucket.value = 'all'
  load()
}
const toggleSelectMode = () => {
  selectMode.value = !selectMode.value
  if (!selectMode.value) selected.value = []
}
const toggleSelect = (ids, checked) => {
  if (checked) selected.value = [...new Set([...selected.value, ...ids])]
  else selected.value = selected.value.filter(id => !ids.includes(id))
}
const selectAllLoaded = () => { selected.value = items.value.map(i => i.id) }

// ==================== 读状态 ====================
const markRead = async ids => {
  if (!ids.length) return
  const set = new Set(ids)
  const wasUnread = items.value.filter(it => set.has(it.id) && !it.readFlag).length
  try {
    await notificationMarkRead(ids)
    items.value.forEach(it => { if (set.has(it.id)) it.readFlag = 1 })
    unreadCount.value = Math.max(0, unreadCount.value - wasUnread)
    // 仅未读视图下已读行应立即消失，而不是留在列表里与新口径打架
    if (unreadOnly.value) {
      items.value = items.value.filter(it => !set.has(it.id))
      total.value = Math.max(0, total.value - wasUnread)
    }
    refreshCounts()
    emitNotifChanged('center')
  } catch (e) {
    message.error('标记已读失败：' + (e.message || ''))
  }
}
const markSelectedRead = async () => {
  await markRead([...selected.value])
  selected.value = []
}
const markAll = async () => {
  try {
    await notificationMarkAllRead()
    items.value.forEach(it => { it.readFlag = 1 })
    unreadCount.value = 0
    // 「仅未读」视图下全部已读后应回到空态（重新拉一次，避免留下已读行）
    if (unreadOnly.value) load()
    refreshCounts()
    emitNotifChanged('center')
  } catch (e) {
    message.error('操作失败：' + (e.message || ''))
  }
}
const markUnread = async item => {
  if (!item || !item.readFlag) return
  try {
    await notificationMarkUnread([item.id])
    item.readFlag = 0
    unreadCount.value += 1
    refreshCounts()
    emitNotifChanged('center')
  } catch (e) {
    message.error('标记未读失败：' + (e.message || ''))
  }
}

// ==================== 删除 / 清空 ====================
const removeOne = async item => {
  try {
    await notificationDelete([item.id])
    items.value = items.value.filter(x => x.id !== item.id)
    selected.value = selected.value.filter(x => x !== item.id)
    if (!item.readFlag) unreadCount.value = Math.max(0, unreadCount.value - 1)
    total.value = Math.max(0, total.value - 1)
    refreshCounts()
    emitNotifChanged('center')
  } catch (e) {
    message.error('删除失败：' + (e.message || ''))
  }
}
const confirmDeleteSelected = () => {
  const ids = [...selected.value]
  if (!ids.length) return
  Modal.confirm({
    title: `删除选中的 ${ids.length} 条通知？`,
    content: '删除后不可恢复。',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        await notificationDelete(ids)
        items.value = items.value.filter(x => !ids.includes(x.id))
        selected.value = []
        total.value = Math.max(0, total.value - ids.length)
        await refreshCounts()
        emitNotifChanged('center')
      } catch (e) {
        message.error('删除失败：' + (e.message || ''))
        throw e
      }
    }
  })
}
const confirmClear = scope => {
  Modal.confirm({
    title: scope === 'read' ? '清空已读通知？' : '清空全部通知？',
    content: '此操作不可恢复。',
    okText: '清空',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        await notificationClear(scope)
        await load()
        await refreshCounts()
        emitNotifChanged('center')
      } catch (e) {
        message.error('清空失败：' + (e.message || ''))
        throw e
      }
    }
  })
}

// ==================== 打开与跳转 ====================
const openRow = item => {
  if (!item?.readFlag) markRead([item.id])
  const to = notifTarget(item)
  if (to) router.push(to).catch(() => {})
}
const goSettings = () => router.push('/profile?panel=notify')

// 浮层/sheet 里「全部已读」等操作后，中心页跟着刷新
const onChanged = e => {
  if (e?.detail?.source === 'widget') {
    load()
    refreshCounts()
  }
}

onMounted(async () => {
  // 先拉列表：已处理的待办会在这次请求里自动置已读，随后再取计数才是新口径
  await load()
  await refreshCounts()
  window.addEventListener('app:notif-changed', onChanged)
  window.addEventListener('app:notif-goto', onNotifGoto)
})
onUnmounted(() => {
  clearTimeout(searchTimer)
  window.removeEventListener('app:notif-changed', onChanged)
  window.removeEventListener('app:notif-goto', onNotifGoto)
})
</script>

<style scoped>
.nc-head-acts { margin-left: auto; display: flex; align-items: center; gap: 8px; flex: none; }

/* 吸顶工具条：负边距抵消 .app-page-body 的 16/20 内边距 */
.nc-toolbar {
  position: sticky; top: 0; z-index: 2;
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  margin: -16px -20px 10px; padding: 12px 20px 10px;
  background: var(--app-bg); border-bottom: 1px solid var(--app-border);
}
.nc-tabs { display: flex; align-items: center; gap: 2px; flex: none; }
.nc-tab {
  display: inline-flex; align-items: center; gap: 5px;
  border: 0; background: transparent; color: var(--app-text2); cursor: pointer;
  font-size: 12px; padding: 6px 10px; border-radius: 7px; transition: background .15s, color .15s;
}
.nc-tab:hover { background: var(--app-panel-2); color: var(--app-text); }
.nc-tab.on { background: var(--app-accent-weak); color: var(--app-accent); font-weight: 600; }
.nc-tab-badge { font-family: var(--ui-monospace, monospace); font-size: 10px; line-height: 1; padding: 2px 5px; border-radius: 5px; background: var(--app-panel-2); color: var(--app-text3); }
.nc-tab-badge.warn { background: var(--app-warn-weak); color: var(--app-warn-text); }
.nc-tab-badge.err { background: var(--app-danger-weak); color: var(--app-danger); }
.nc-tab-badge.ok { background: var(--app-ok-weak); color: var(--app-ok); }

.nc-tools { margin-left: auto; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; min-width: 0; }
.nc-search { width: 210px; }
.nc-switch { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--app-text2); white-space: nowrap; }
.nc-select { width: 160px; }
.nc-on { border-color: var(--app-accent-border) !important; color: var(--app-accent) !important; background: var(--app-accent-weak) !important; }
.nc-danger { color: var(--app-danger); }
.nc-danger:hover:not(:disabled) { background: var(--app-danger-weak); }

.nc-filterrow {
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
  padding: 0 0 10px; font-size: 12px;
}
.nc-fchip {
  display: inline-flex; align-items: center; gap: 5px;
  border: 1px solid var(--app-accent-border); background: var(--app-accent-weak);
  color: var(--app-accent); border-radius: 999px; padding: 3px 9px;
  font-size: 11px; cursor: pointer;
}
.nc-fchip:hover { background: var(--app-panel-2); }
.nc-fchip-x { font-weight: 700; }

.nc-batch {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  padding: 8px 12px; margin: 0 0 10px;
  background: var(--app-accent-weak); border: 1px solid var(--app-accent-border);
  border-radius: 8px; font-size: 12px; color: var(--app-accent);
}
.nc-batch-count { font-weight: 600; }

@media (max-width: 900px) {
  .head-hint-plain { display: none; }
  .nc-tools { margin-left: 0; width: 100%; }
  .nc-search { flex: 1; min-width: 180px; }
  .nc-select { width: 140px; }
}
</style>
