<template>
  <div class="app-page notif-center">
    <div class="app-page-head">
      <h1 class="app-page-title">通知中心</h1>
      <span class="head-hint-plain" title="解析/工作流/网页源刷新/定时任务/审批等异步事件的归档与处理">解析/工作流/网页源刷新/定时任务/审批等异步事件的归档与处理</span>
      <!-- 筛选 + 操作区 -->
      <div class="nc-tools">
        <a-select v-model:value="typeFilter" class="nc-select" :options="typeOptions" @change="applyFilter" />
        <a-switch v-model:checked="unreadOnly" @change="applyFilter">
          <template #checkedChildren>仅未读</template>
          <template #unCheckedChildren>全部</template>
        </a-switch>
        <button class="app-btn ghost small" :disabled="unreadCount === 0" @click="markAll">全部已读</button>
        <a-dropdown :trigger="['click']" :disabled="!items.length">
          <button class="app-btn ghost small">清空 <down-outlined /></button>
          <template #overlay>
            <a-menu>
              <a-menu-item key="read" @click="clearRead">清空已读</a-menu-item>
              <a-menu-item key="all" @click="clearAll">清空全部</a-menu-item>
            </a-menu>
          </template>
        </a-dropdown>
        <!-- 收哪些类、用什么方式收到，统一在「个人设置 → 通知与推送」一处管
             （勾选类偏好不留两个入口，两边各存一份开关必然漂） -->
        <button class="app-btn ghost small" @click="router.push('/profile?panel=notify')">
          <setting-outlined /> 通知与推送设置
        </button>
      </div>
    </div>

    <!-- 批量操作栏：有勾选即出现（点勾选命中区进入，见 .nc-check） -->
    <div v-if="selected.length" class="nc-batch">
      <span class="nc-batch-count">已选 {{ selected.length }} 条：</span>
      <button class="app-btn ghost small" @click="markRead([...selected])">标记已读</button>
      <a-popconfirm title="确定删除选中的通知？" ok-text="删除" cancel-text="取消" @confirm="deleteSelected">
        <button class="app-btn ghost small nc-danger">删除</button>
      </a-popconfirm>
      <button class="app-link-btn" @click="selected = []">取消选择</button>
    </div>

    <div class="app-page-body">
      <a-spin :spinning="loading">
        <div v-if="!items.length && !loading" class="nc-empty">
          <bell-outlined class="nc-empty-ic" />
          <p>{{ typeFilter || unreadOnly ? '当前筛选下没有通知' : '还没有通知' }}</p>
        </div>
        <template v-else>
          <div v-for="n in items" :key="n.id" class="nc-item" :class="{ unread: !n.readFlag }"
               @click="openRow(n, $event)">
            <a-checkbox class="nc-check" :checked="selected.includes(n.id)" @change="e => toggleSelect(n.id, e.target.checked)" />
            <component :is="iconOf(n.type)" class="nc-ic" :class="toneOf(n.type)" />
            <div class="nc-body">
              <div class="nc-row1">
                <span class="nc-title">{{ n.title }}</span>
                <span v-if="n.hitCount > 1" class="nc-hit">×{{ n.hitCount }}</span>
                <span v-if="!n.readFlag" class="nc-dot" />
              </div>
              <div v-if="n.content" class="nc-content">{{ n.content }}</div>
              <div class="nc-meta">
                <span class="nc-type">{{ labelOf(n.type) }}</span>
                <span>{{ fmt(n.createTime) }}</span>
                <span v-if="navOf(n)" class="nc-link">查看详情 ›</span>
              </div>
            </div>
            <button class="nc-del" title="删除" @click.stop="remove([n.id])"><delete-outlined /></button>
          </div>
          <div v-if="hasMore" class="nc-more">
            <button class="app-btn ghost small" :disabled="loading" @click="loadMore">加载更多</button>
          </div>
          <div v-else-if="items.length" class="nc-foot-meta">共 {{ total }} 条</div>
        </template>
      </a-spin>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { BellOutlined, DownOutlined, SettingOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import {
  notificationList, notificationCounts, notificationMarkRead, notificationMarkAllRead,
  notificationDelete, notificationClear
} from '../api'
import { NOTIF_TYPES } from '../chat/useNotifications'

const router = useRouter()

// 类型元数据（标签/图标/语义色）在 chat/useNotifications 一份：
// 铃铛列表、这里的筛选、个人设置里「收哪些类」三处必须同一批词，不各抄一份
const TYPES = NOTIF_TYPES
const META = Object.fromEntries(TYPES.map(t => [t.type, t]))
const iconOf = t => (META[t]?.icon) || BellOutlined
const toneOf = t => (META[t]?.tone) || ''
const labelOf = t => (META[t]?.label) || t

// 筛选下拉：全部 + 各类型（带未读角标）
const typeFilter = ref('')
const unreadOnly = ref(false)
const typeOptions = computed(() => [
  { value: '', label: '全部类型' },
  ...TYPES.map(t => ({
    value: t.type,
    label: t.label + (counts.value[t.type] ? ` (${counts.value[t.type]})` : '')
  }))
])

// 列表状态
const items = ref([])
const cursor = ref(0)
const hasMore = ref(false)
const total = ref(0)
const unreadCount = ref(0)
const loading = ref(false)
const selected = ref([])
const counts = ref({})

const fetchPage = async (reset) => {
  loading.value = true
  try {
    const r = await notificationList({
      limit: 50, type: typeFilter.value || undefined,
      unreadOnly: unreadOnly.value || undefined,
      cursor: reset ? 0 : cursor.value
    })
    const body = r?.data || {}
    const rows = body.items || []
    if (reset) { items.value = rows; cursor.value = 0 }
    else items.value = [...items.value, ...rows]
    hasMore.value = !!body.hasMore
    if (body.nextCursor != null) cursor.value = body.nextCursor
    total.value = body.total || 0
    unreadCount.value = body.unreadCount || 0
    // 选中项对账：被删的 id 移除
    const alive = new Set(items.value.map(x => x.id))
    selected.value = selected.value.filter(id => alive.has(id))
  } catch (e) {
    message.error('通知加载失败：' + (e.message || ''))
  } finally {
    loading.value = false
  }
}
const load = () => fetchPage(true)
const loadMore = () => fetchPage(false)
const applyFilter = () => load()
const refreshCounts = async () => {
  try { const r = await notificationCounts(); counts.value = (r?.data?.counts) || {}; unreadCount.value = r?.data?.unread || 0 }
  catch (e) { /* 静默 */ }
}

const toggleSelect = (id, checked) => {
  selected.value = checked ? [...selected.value, id] : selected.value.filter(x => x !== id)
}
const markRead = async ids => {
  if (!ids.length) return
  try {
    await notificationMarkRead(ids)
    ids.forEach(id => { const it = items.value.find(x => x.id === id); if (it) it.readFlag = 1 })
    unreadCount.value = Math.max(0, unreadCount.value - ids.length)
    refreshCounts()
  } catch (e) { message.error('标记已读失败：' + (e.message || '')) }
}
const markAll = async () => {
  try { await notificationMarkAllRead(); items.value.forEach(it => it.readFlag = 1); unreadCount.value = 0; refreshCounts() }
  catch (e) { message.error('操作失败：' + (e.message || '')) }
}
const remove = async ids => {
  if (!ids.length) return
  try {
    await notificationDelete(ids)
    items.value = items.value.filter(x => !ids.includes(x.id))
    selected.value = selected.value.filter(x => !ids.includes(x))
    refreshCounts()
  } catch (e) { message.error('删除失败：' + (e.message || '')) }
}
const deleteSelected = () => remove([...selected.value])
const clearRead = async () => {
  try { await notificationClear('read'); await load(); refreshCounts() }
  catch (e) { message.error('清空失败：' + (e.message || '')) }
}
const clearAll = async () => {
  try { await notificationClear('all'); await load(); refreshCounts() }
  catch (e) { message.error('清空失败：' + (e.message || '')) }
}

// 点击行：乐观置已读 + 按 ref 跳转（审批类带二级目标直达可裁决位置）
const navOf = n => {
  if (n.type === 'workflow.approval' && n.refId && n.refSub)
    return { path: '/agents', query: { tab: 'workflow', wf: n.refId, run: n.refSub } }
  if (n.type === 'tool.approval' && n.refId && n.refSub)
    return { path: '/chat', query: { sid: n.refId, approval: n.refSub } }
  if (n.refType === 'kb' && n.refId) return { path: `/knowledge/${n.refId}/docs` }
  if (n.refType === 'workflow' && n.refId) return { path: '/agents', query: { tab: 'workflow' } }
  if (n.refType === 'session' && n.refId) return { path: '/chat', query: { sid: n.refId } }
  // 模型额度不足：直达模型供应商页（供应商管理并入智能体页 ?tab=providers）
  if (n.refType === 'provider' && n.refId) return { path: '/agents', query: { tab: 'providers' } }
  return null
}
const openRow = (n, e) => {
  const t = e?.target
  // 勾选命中区（antd wrapper 是 label，@click.stop 只盖住内层 16px）：只勾选、不跳转
  if (t?.closest?.('.nc-check')) return
  // 批量勾选中：点行=勾选/取消，避免勾选时误触「查看详情」跳走；点链接仍可直达详情
  if (selected.value.length && !t?.closest?.('.nc-link')) return toggleSelect(n.id, !selected.value.includes(n.id))
  if (!n.readFlag) markRead([n.id])
  const to = navOf(n)
  if (to) router.push(to)
}

// 时间格式化（与铃铛一致）
const fmt = v => {
  if (!v) return ''
  const d = new Date(v)
  if (isNaN(d)) return ''
  const diff = Date.now() - d.getTime()
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  if (diff < 7 * 86_400_000) return `${Math.floor(diff / 86_400_000)} 天前`
  const p = n => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

onMounted(() => { load(); refreshCounts() })
</script>

<style scoped>
.nc-tools { display: flex; align-items: center; gap: 10px; margin-left: auto; flex-wrap: wrap; }
.nc-select { width: 160px; }
.nc-danger { color: var(--app-danger); }
.nc-danger:hover:not(:disabled) { background: var(--app-danger-weak); }
.nc-meta { font-size: 12px; color: var(--app-text3); margin-right: auto; }
.nc-batch { display: flex; align-items: center; gap: 10px; padding: 8px 12px; margin: 0 0 10px; background: var(--app-accent-weak); border: 1px solid var(--app-accent-border); border-radius: 8px; font-size: 12px; color: var(--app-accent); }
.nc-batch-count { font-weight: 600; }
.nc-empty { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 60px 0; color: var(--app-text3); }
.nc-empty-ic { font-size: 40px; opacity: .5; }
.nc-item { display: flex; align-items: flex-start; gap: 10px; padding: 12px; border-radius: 8px; cursor: pointer; border: 1px solid transparent; }
.nc-item:hover { background: var(--app-panel-2); }
.nc-item.unread { background: var(--app-accent-weak); }
/* 勾选命中区：antd wrapper 本身即可点 label —— 内边距 + 负外边距把命中区撑满「文字前的整个左侧」
   （行左边界 → 图标前，整行高度）；内层 .ant-checkbox 默认 align-self:center（拉高后会居中下沉），
   改回 flex-start，并让上内边距多 2.5px 抵消，勾选框视觉位置与原来逐像素一致 */
.nc-item :deep(.nc-check) { align-self: stretch; align-items: flex-start; padding: 14.5px 9px 9.5px 13px; margin: -12px -9px -12px -13px; }
.nc-item :deep(.nc-check .ant-checkbox) { align-self: flex-start; }
.nc-ic { font-size: 17px; margin-top: 2px; flex: none; }
.nc-ic.ok { color: var(--app-ok); }
.nc-ic.err { color: var(--app-danger); }
.nc-ic.warn { color: var(--app-warn); }
.nc-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.nc-row1 { display: flex; align-items: center; gap: 8px; }
.nc-title { font-size: 13px; color: var(--app-text); line-height: 1.4; }
.nc-item.unread .nc-title { font-weight: 600; }
.nc-hit { font-size: 11px; color: var(--app-warn-text); background: var(--app-warn-weak); border-radius: 8px; padding: 1px 7px; flex: none; }
.nc-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--app-accent); flex: none; }
.nc-content { font-size: 12px; color: var(--app-text2); line-height: 1.45; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; word-break: break-all; }
.nc-meta { display: flex; align-items: center; gap: 10px; font-size: 11px; color: var(--app-text3); }
.nc-link { color: var(--app-accent); }
.nc-del { border: none; background: none; color: var(--app-text3); cursor: pointer; padding: 4px; border-radius: 6px; flex: none; }
.nc-del:hover { color: var(--app-danger); background: var(--app-panel-2); }
.nc-more { text-align: center; padding: 14px 0; }
.nc-foot-meta { text-align: center; padding: 14px 0; font-size: 12px; color: var(--app-text3); }
</style>
