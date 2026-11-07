<template>
  <div class="nl" :class="`nl-${variant}`">
    <!-- 首屏加载：与真实行等高的骨架 -->
    <div v-if="loading && !items.length" class="nl-skels">
      <div v-for="i in skeletonCount" :key="i" class="nl-skel">
        <span class="nl-skel-ic" />
        <span class="nl-skel-main">
          <i class="nl-skel-line w60" />
          <i class="nl-skel-line w90" />
          <i class="nl-skel-line w40" />
        </span>
      </div>
    </div>

    <!-- 加载失败 -->
    <div v-else-if="error && !items.length" class="nl-state">
      <ExclamationCircleFilled class="nl-state-ic err" />
      <b>通知加载失败</b>
      <span>{{ error }}</span>
      <button class="nl-state-btn" type="button" @click="$emit('retry')">重试</button>
    </div>

    <!-- 空态 -->
    <div v-else-if="!items.length" class="nl-state">
      <BellOutlined class="nl-state-ic" />
      <b>{{ emptyTitle }}</b>
      <span>{{ emptyDesc }}</span>
      <slot name="empty-action" />
      <!-- 服务端还有下一页但本页被筛空时，仍给一个手动加载入口（否则空态会堵住继续翻页） -->
      <button v-if="variant === 'page' && hasMore" class="nl-state-btn" type="button"
              :disabled="loading" @click="$emit('load-more')">
        {{ loading ? '加载中…' : '加载更多' }}
      </button>
    </div>

    <template v-else>
      <template v-for="section in sections" :key="section.key">
        <div v-if="showDateHeaders" class="nl-date">
          <b>{{ section.label }}</b>
          <span>共 {{ section.count }} 条</span>
        </div>

        <div class="nl-list">
          <template v-for="node in section.nodes" :key="node.kind === 'group' ? `g:${node.key}` : node.item.id">
            <!-- 聚合组：只折叠展示，展开后每条仍能单独深链 -->
            <div v-if="node.kind === 'group'" class="nl-group">
              <div
                class="nl-item nl-group-head"
                :class="{ unread: groupUnread(node) }"
                role="button"
                tabindex="0"
                @click="$emit('open', node.latest)"
                @keydown="e => openKey(e, node.latest)"
              >
                <span v-if="selectable" class="nl-check" @click.stop>
                  <a-checkbox
                    :checked="groupChecked(node)"
                    :indeterminate="groupIndeterminate(node)"
                    @change="e => toggleGroup(node, e.target.checked)"
                  />
                </span>
                <span v-if="groupUnread(node)" class="nl-bar" />
                <span class="nl-ic warn"><component :is="iconOf(node.latest.type)" /></span>
                <span class="nl-main">
                  <span class="nl-title-row">
                    <span class="nl-title">{{ groupTitle(node) }}</span>
                    <span v-if="variant !== 'page'" class="nl-count">×{{ node.items.length }}</span>
                  </span>
                  <span class="nl-sub">{{ groupSub(node) }}</span>
                  <span class="nl-meta">
                    <span class="nl-pill">{{ groupPill(node) }}</span>
                    <span>{{ node.items.length }} 条 · 最新 {{ notifTime(node.latest.createTime) }}</span>
                  </span>
                </span>
                <span class="nl-actions">
                  <button class="nl-go" type="button" @click.stop="$emit('open', node.latest)">去处理 ›</button>
                  <button class="nl-expand" type="button" :aria-expanded="isExpanded(node.key)"
                          @click.stop="toggleExpand(node.key)">
                    {{ isExpanded(node.key) ? '收起 ▴' : '展开 ▾' }}
                  </button>
                </span>
              </div>

              <div v-if="isExpanded(node.key)" class="nl-children">
                <div
                  v-for="child in node.items"
                  :key="child.id"
                  class="nl-child"
                  role="button"
                  tabindex="0"
                  @click="$emit('open', child)"
                  @keydown="e => openKey(e, child)"
                >
                  <span v-if="selectable" class="nl-check" @click.stop>
                    <a-checkbox :checked="selected.includes(child.id)"
                                @change="e => $emit('toggle-select', [child.id], e.target.checked)" />
                  </span>
                  <span class="nl-dot" />
                  <span class="nl-main">
                    <span class="nl-title-row">
                      <span class="nl-title">{{ notifCleanText(child.title) }}</span>
                      <span v-if="child.hitCount > 1" class="nl-hit">×{{ child.hitCount }}</span>
                    </span>
                    <span v-if="child.content" class="nl-sub">{{ notifCleanText(child.content) }}</span>
                    <span class="nl-meta">
                      <span class="nl-pill">{{ notifLabel(child.type) }}</span>
                      <span>{{ notifTime(child.createTime) }}</span>
                    </span>
                  </span>
                  <span class="nl-actions">
                    <span v-if="isResolvedAction(child)" class="nl-resolved">已处理</span>
                    <button v-else class="nl-go" type="button" @click.stop="$emit('open', child)">去处理 ›</button>
                    <NotifKebab :item="child" @mark-unread="$emit('mark-unread', $event)"
                                @delete="$emit('delete', $event)" />
                  </span>
                </div>
              </div>
            </div>

            <!-- 单条 -->
            <div
              v-else
              class="nl-item"
              :class="{ unread: !node.item.readFlag, read: node.item.readFlag }"
              role="button"
              tabindex="0"
              @click="$emit('open', node.item)"
              @keydown="e => openKey(e, node.item)"
            >
              <span v-if="selectable" class="nl-check" @click.stop>
                <a-checkbox :checked="selected.includes(node.item.id)"
                            @change="e => $emit('toggle-select', [node.item.id], e.target.checked)" />
              </span>
              <span v-if="!node.item.readFlag" class="nl-bar" />
              <span class="nl-ic" :class="toneOf(node.item)"><component :is="iconOf(node.item.type)" /></span>
              <span class="nl-main">
                <span class="nl-title-row">
                  <span class="nl-title">{{ notifCleanText(node.item.title) }}</span>
                  <span v-if="node.item.hitCount > 1" class="nl-hit">×{{ node.item.hitCount }}</span>
                </span>
                <span v-if="node.item.content" class="nl-sub">{{ notifCleanText(node.item.content) }}</span>
                <span class="nl-meta">
                  <span class="nl-pill">{{ notifLabel(node.item.type) }}</span>
                  <span>{{ notifTime(node.item.createTime) }}</span>
                </span>
              </span>
              <span class="nl-actions">
                <!-- 底层审批/运行已经处理完：不再显示「去处理」，改为「已处理」 -->
                <span v-if="isResolvedAction(node.item)" class="nl-resolved">已处理</span>
                <button v-else-if="isAction(node.item)" class="nl-go" type="button"
                        @click.stop="$emit('open', node.item)">去处理 ›</button>
                <NotifKebab :item="node.item" @mark-unread="$emit('mark-unread', $event)"
                            @delete="$emit('delete', $event)" />
              </span>
            </div>
          </template>
        </div>
      </template>

      <div v-if="variant === 'page'" class="nl-more-wrap">
        <button v-if="hasMore" class="nl-more-btn" type="button" :disabled="loading"
                @click="$emit('load-more')">
          {{ loading ? '加载中…' : '加载更多' }}
        </button>
        <div v-else-if="items.length && showTotal" class="nl-foot">共 {{ total }} 条</div>
      </div>
      <div v-if="variant === 'page'" ref="sentinel" class="nl-sentinel" />
    </template>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { BellOutlined, ExclamationCircleFilled } from '@ant-design/icons-vue'
import {
  groupNotifications, notifBucket, notifClass, notifCleanText, notifDate, notifDetail, notifIcon,
  notifLabel, notifSubject, notifTime
} from '../../chat/useNotifications'
import NotifKebab from './NotifKebab.vue'

const props = defineProps({
  items: { type: Array, default: () => [] },
  variant: { type: String, default: 'page' }, // page | popover | sheet
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  selectable: { type: Boolean, default: false },
  selected: { type: Array, default: () => [] },
  showDateHeaders: { type: Boolean, default: false },
  grouped: { type: Boolean, default: true },
  hasMore: { type: Boolean, default: false },
  total: { type: Number, default: 0 },
  // 「待处理」桶会滤掉已处理项，服务端 total 仍包含它们，所以那一档不展示总数
  showTotal: { type: Boolean, default: true },
  emptyTitle: { type: String, default: '还没有通知' },
  emptyDesc: { type: String, default: '解析、工作流、定时任务或对话需要你处理时，会出现在这里。' }
})

const emit = defineEmits(['open', 'toggle-select', 'mark-unread', 'delete', 'load-more', 'retry'])

const skeletonCount = computed(() => ({ page: 6, popover: 3, sheet: 4 }[props.variant] || 4))
const iconOf = type => notifIcon(type)
const toneOf = item => notifClass(item.type)
const isAction = item => notifBucket(item?.type) === 'action' && item.actionable !== false
const isResolvedAction = item => notifBucket(item?.type) === 'action' && item.actionable === false

// ==================== 日期分段 ====================
const dayStart = d => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
const dayKey = v => {
  const d = notifDate(v)
  if (!d) return 'older'
  const now = new Date()
  const today = dayStart(now)
  const yesterday = dayStart(new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1))
  const t = dayStart(d)
  if (t === today) return 'today'
  if (t === yesterday) return 'yesterday'
  return 'older'
}
const sections = computed(() => {
  const list = props.items || []
  if (!props.showDateHeaders) {
    return list.length
      ? [{ key: 'all', label: '', count: list.length, nodes: groupNotifications(list, { group: props.grouped }) }]
      : []
  }
  const buckets = { today: [], yesterday: [], older: [] }
  for (const item of list) buckets[dayKey(item.createTime)].push(item)
  const labels = { today: '今天', yesterday: '昨天', older: '更早' }
  return ['today', 'yesterday', 'older']
    .filter(k => buckets[k].length)
    .map(k => ({
      key: k,
      label: labels[k],
      count: buckets[k].length,
      nodes: groupNotifications(buckets[k], { group: props.grouped })
    }))
})

// ==================== 聚合组 ====================
const groupTitle = node => {
  const subject = notifSubject(node.latest) || notifLabel(node.latest.type)
  if (props.variant !== 'page') return subject
  const allWorkflow = node.items.every(i => i.type === 'workflow.approval')
  return `${subject} · ${allWorkflow ? `共 ${node.items.length} 次运行待审核` : `共 ${node.items.length} 件待处理`}`
}
const groupSub = node => props.variant === 'page'
  ? `最近：${notifDetail(node.latest)} · ${notifTime(node.latest.createTime)}`
  : notifDetail(node.latest)
const groupPill = node => {
  const types = [...new Set(node.items.map(i => i.type))]
  return types.length === 1 ? notifLabel(types[0]) : '待处理'
}
const groupUnread = node => node.items.some(i => !i.readFlag)
const groupChecked = node => node.items.every(i => props.selected.includes(i.id))
const groupIndeterminate = node => !groupChecked(node) && node.items.some(i => props.selected.includes(i.id))
const toggleGroup = (node, checked) => emit('toggle-select', node.items.map(i => i.id), checked)

// 展开状态持久化：只记住最近 20 个组，避免 localStorage 无限增长
const EXPAND_KEY = 'wq.notif.expanded'
const readExpanded = () => {
  try {
    const arr = JSON.parse(localStorage.getItem(EXPAND_KEY) || '[]')
    return Array.isArray(arr) ? arr.slice(-20) : []
  } catch { return [] }
}
const expandedKeys = ref(readExpanded())
const isExpanded = key => expandedKeys.value.includes(key)
const toggleExpand = key => {
  expandedKeys.value = isExpanded(key)
    ? expandedKeys.value.filter(k => k !== key)
    : [...expandedKeys.value, key].slice(-20)
  try { localStorage.setItem(EXPAND_KEY, JSON.stringify(expandedKeys.value)) } catch { /* 隐私模式忽略 */ }
}

// ==================== 键盘与滚动加载 ====================
const openKey = (e, item) => {
  if (e.target !== e.currentTarget) return
  if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault()
    emit('open', item)
  }
}

const sentinel = ref(null)
let observer = null
onMounted(() => {
  if (typeof IntersectionObserver === 'undefined') return
  observer = new IntersectionObserver(entries => {
    if (entries[0]?.isIntersecting && props.hasMore && !props.loading) emit('load-more')
  }, { rootMargin: '120px' })
  watch(sentinel, el => {
    if (observer && el) observer.observe(el)
    else if (observer && !el) observer.disconnect()
  }, { immediate: true })
})
onBeforeUnmount(() => { observer?.disconnect() })
</script>

<style scoped>
.nl { min-width: 0; }
.nl-page { color: var(--app-text); }
.nl-popover, .nl-sheet { color: var(--app-text); }

/* ============ 列表卡与日期头 ============ */
.nl-list { border: 1px solid var(--app-border); border-radius: var(--app-radius); overflow: hidden; background: var(--app-panel); }
.nl-popover .nl-list, .nl-sheet .nl-list { border: 0; border-radius: 0; background: transparent; }
.nl-date { display: flex; align-items: center; padding: 10px 2px 6px; font-size: 12px; color: var(--app-text3); }
.nl-date b { color: var(--app-text2); font-weight: 600; font-size: 12px; }
.nl-date span { margin-left: auto; }

/* ============ 行 ============ */
.nl-item { position: relative; display: flex; align-items: flex-start; gap: 10px; padding: 12px 14px; border-bottom: 1px solid var(--app-border); background: var(--app-panel); cursor: pointer; }
.nl-item:last-child { border-bottom: 0; }
.nl-item:hover { background: var(--app-panel-2); }
.nl-item.unread { background: var(--app-panel); }
.nl-item.read { background: var(--app-panel); }
.nl-bar { position: absolute; left: 0; top: 10px; bottom: 10px; width: 3px; border-radius: 2px; background: var(--app-accent); flex: none; }
.nl-check { display: flex; align-items: center; align-self: stretch; padding-left: 2px; flex: none; }
.nl-ic { width: 28px; height: 28px; border-radius: 8px; background: var(--app-accent-weak); color: var(--app-accent); display: flex; align-items: center; justify-content: center; flex: none; font-size: 15px; }
.nl-ic.ok { background: var(--app-ok-weak); color: var(--app-ok); }
.nl-ic.err { background: var(--app-danger-weak); color: var(--app-danger); }
.nl-ic.warn { background: var(--app-warn-weak); color: var(--app-warn); }
.nl-item.read .nl-ic { background: var(--app-panel-2); color: var(--app-text3); }
.nl-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.nl-title-row { display: flex; align-items: center; gap: 6px; min-width: 0; }
.nl-title { font-size: 13px; line-height: 1.45; color: var(--app-text); overflow-wrap: anywhere; }
.nl-item.unread .nl-title { font-weight: 600; }
.nl-item.read .nl-title { color: var(--app-text2); }
.nl-hit { font-size: 11px; color: var(--app-warn-text); background: var(--app-warn-weak); border-radius: 8px; padding: 1px 6px; flex: none; }
.nl-count { font-family: var(--ui-monospace, monospace); font-size: 11px; color: var(--app-warn-text); background: var(--app-warn-weak); border: 1px solid var(--app-warn-border); border-radius: 6px; padding: 0 5px; flex: none; }
.nl-sub { font-size: 12px; color: var(--app-text2); line-height: 1.45; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; overflow-wrap: anywhere; }
.nl-popover .nl-sub { -webkit-line-clamp: 1; }
.nl-meta { display: flex; align-items: center; gap: 8px; margin-top: 2px; font-size: 11px; color: var(--app-text3); min-width: 0; }
.nl-meta > span { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.nl-pill { display: inline-flex; align-items: center; font-size: 10.5px; line-height: 1; padding: 3px 7px; border-radius: 999px; background: var(--app-panel-2); color: var(--app-text2); border: 1px solid var(--app-border); white-space: nowrap; }
.nl-actions { display: flex; align-items: center; gap: 6px; flex: none; padding-top: 1px; }
.nl-go { border: 0; background: transparent; color: var(--app-accent); font-size: 11px; cursor: pointer; padding: 2px 0; white-space: nowrap; }
.nl-go:hover { text-decoration: underline; }
.nl-resolved { font-size: 10.5px; line-height: 1; padding: 3px 7px; border-radius: 999px; color: var(--app-text3); background: var(--app-panel-2); border: 1px solid var(--app-border); white-space: nowrap; }
.nl-expand { border: 1px solid var(--app-border); background: var(--app-panel); color: var(--app-text2); font-size: 11px; border-radius: 6px; padding: 2px 7px; cursor: pointer; white-space: nowrap; }
.nl-expand:hover { color: var(--app-accent); border-color: var(--app-accent-border); background: var(--app-accent-weak); }

/* ============ 聚合组 ============ */
.nl-group { border-bottom: 1px solid var(--app-border); background: var(--app-panel-2); }
.nl-group:last-child { border-bottom: 0; }
.nl-group-head { border-bottom: 0; background: transparent; }
.nl-group-head:hover { background: var(--app-panel); }
.nl-group-head .nl-ic { background: var(--app-warn-weak); color: var(--app-warn); }
.nl-children { padding: 0 14px 6px 44px; background: var(--app-panel); }
.nl-child { position: relative; display: flex; align-items: flex-start; gap: 8px; padding: 8px 0; border-top: 1px dashed var(--app-border); cursor: pointer; }
.nl-child:hover { background: var(--app-panel-2); }
.nl-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--app-warn); margin-top: 8px; flex: none; }
.nl-child .nl-title { font-size: 12.5px; }
.nl-child .nl-sub { font-size: 11.5px; }

/* ============ 密度：浮层 ============ */
.nl-popover .nl-item { padding: 9px 10px; gap: 8px; }
.nl-popover .nl-ic { width: 24px; height: 24px; border-radius: 7px; font-size: 13px; }
.nl-popover .nl-title { font-size: 13px; }
.nl-popover .nl-sub { font-size: 11.5px; }
.nl-popover .nl-meta { margin-top: 1px; }
.nl-popover .nl-children { padding: 0 10px 4px 42px; }
.nl-popover .nl-child { padding: 7px 0; }

/* ============ 密度：手机 ============ */
.nl-sheet .nl-item { padding: 11px 12px; gap: 9px; min-height: 56px; }
.nl-sheet .nl-ic { width: 26px; height: 26px; border-radius: 8px; font-size: 14px; }
.nl-sheet .nl-title { font-size: 14px; }
.nl-sheet .nl-sub { font-size: 12px; }
.nl-sheet .nl-go, .nl-sheet .nl-expand { min-height: 40px; padding-left: 8px; padding-right: 8px; display: inline-flex; align-items: center; touch-action: manipulation; }
.nl-sheet .nl-check { min-height: 44px; padding: 0 6px; }
.nl-sheet :deep(.nk-btn) { width: 34px; height: 34px; }
.nl-sheet .nl-children { padding: 0 12px 4px 40px; }

/* ============ 骨架 ============ */
.nl-skels { display: flex; flex-direction: column; }
.nl-skel { display: flex; gap: 10px; padding: 12px 14px; border-bottom: 1px solid var(--app-border); }
.nl-skel-ic { width: 28px; height: 28px; border-radius: 8px; background: var(--app-panel-2); flex: none; }
.nl-skel-main { flex: 1; display: flex; flex-direction: column; gap: 6px; }
.nl-skel-line { display: block; height: 9px; border-radius: 4px; background: var(--app-panel-2); }
.nl-skel-line.w60 { width: 60%; }
.nl-skel-line.w90 { width: 90%; height: 8px; }
.nl-skel-line.w40 { width: 40%; height: 8px; }
@media (prefers-reduced-motion: no-preference) {
  .nl-skel-ic, .nl-skel-line { animation: nl-pulse 1.4s ease-in-out infinite; }
  @keyframes nl-pulse { 0%, 100% { opacity: 1; } 50% { opacity: .55; } }
}

/* ============ 空 / 错 ============ */
.nl-state { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 44px 16px; text-align: center; color: var(--app-text3); }
.nl-state-ic { font-size: 34px; opacity: .55; }
.nl-state-ic.err { color: var(--app-danger); opacity: .8; }
.nl-state b { font-size: 13px; color: var(--app-text2); font-weight: 600; }
.nl-state span { font-size: 12px; line-height: 1.6; max-width: 320px; }
.nl-state-btn { margin-top: 6px; border: 1px solid var(--app-border); background: var(--app-panel); color: var(--app-text2); border-radius: 7px; padding: 5px 14px; font-size: 12px; cursor: pointer; }
.nl-state-btn:hover { color: var(--app-accent); border-color: var(--app-accent-border); background: var(--app-accent-weak); }

/* ============ 页脚 ============ */
.nl-more-wrap { padding: 14px 0 2px; text-align: center; }
.nl-more-btn { border: 1px solid var(--app-border); background: var(--app-panel); color: var(--app-text2); border-radius: 7px; padding: 5px 16px; font-size: 12px; cursor: pointer; }
.nl-more-btn:hover:not(:disabled) { color: var(--app-accent); border-color: var(--app-accent-border); }
.nl-more-btn:disabled { opacity: .55; cursor: default; }
.nl-foot { font-size: 12px; color: var(--app-text3); padding: 2px 0; }
.nl-sentinel { height: 1px; }
</style>
