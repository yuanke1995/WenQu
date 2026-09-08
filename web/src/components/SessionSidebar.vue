<template>
  <div class="session-sidebar" :class="{ collapsed, 'no-anim': dragging }"
       :style="{ width: width + 'px', minWidth: width + 'px' }">
    <!-- 展开态：新建对话 + 会话列表 -->
    <div v-if="!collapsed" class="sidebar-inner">
      <!-- 头部：新建对话 + 清空 + 收起按钮 -->
      <div class="sidebar-header">
        <div class="header-row">
          <a-button type="primary" block @click="$emit('new')" :disabled="loading || creating"
                    :style="width < 220 ? 'padding: 0 8px' : ''">
            <plus-outlined />
            <span v-if="width >= 220">新建对话</span>
          </a-button>
          <a-tooltip title="清空所有对话">
            <a-popconfirm title="确定清空所有对话？此操作不可恢复" ok-text="清空" cancel-text="取消"
                          placement="bottomRight" @confirm="$emit('clear')" @click.stop>
              <a-button type="text" class="collapse-btn">
                <clear-outlined />
              </a-button>
            </a-popconfirm>
          </a-tooltip>
          <a-tooltip title="收起侧边栏">
            <a-button type="text" class="collapse-btn" @click="$emit('toggle-collapse')">
              <menu-fold-outlined />
            </a-button>
          </a-tooltip>
        </div>
      </div>

      <!-- 搜索 + 收藏筛选 -->
      <div class="sidebar-search" v-if="!collapsed">
        <div class="search-row">
          <a-input
            ref="searchInputRef"
            v-model:value="searchText"
            placeholder="搜索会话"
            allow-clear
            size="small"
            @input="onSearchInput"
            @clear="onSearchClear"
          >
            <template #prefix><search-outlined style="color:#bbb" /></template>
          </a-input>
        </div>
        <div class="filter-row">
          <span class="filter-tab" :class="{ active: filter === 'all' }" @click="setFilter('all')">全部</span>
          <span class="filter-tab" :class="{ active: filter === 'fav' }" @click="setFilter('fav')">收藏</span>
          <span v-if="batchMode" class="batch-entry active" @click="toggleBatch">退出管理</span>
        </div>
      </div>

      <!-- 会话列表 -->
      <div class="session-list">
        <div v-if="sessions.length === 0" class="empty">
          <span>{{ searchText || filter === 'fav' ? '无匹配会话' : '暂无历史会话' }}</span>
        </div>
        <div
          v-for="s in sessions"
          :key="s.id"
          :ref="el => setItemEl(s.id, el)"
          class="session-item"
          :class="{ active: s.id === currentSessionId }"
          @click="batchMode ? toggleSel(s.id) : $emit('select', s.id)"
        >
          <!-- 批量管理模式：勾选框替代 ··· 操作 -->
          <a-checkbox v-if="batchMode" class="batch-check"
                      :checked="selected.has(s.id)" @click.stop @change="toggleSel(s.id)" />
          <div class="session-info">
            <div class="session-title">
              <span v-if="s.isPinned" class="pin-mark"><pushpin-filled style="font-size:11px" /></span>
              {{ s.title || '新对话' }}
            </div>
            <div class="session-meta">
              <span>{{ formatTime(s.updateTime) }}</span>
              <span class="msg-count">{{ s.messageCount || 0 }} 条消息</span>
            </div>
          </div>
          <div v-if="!batchMode" class="session-actions" @click.stop>
            <!-- 更多菜单：收藏/置顶/删除收进 ···，保持会话项简洁 -->
            <a-dropdown :trigger="['click']" placement="bottomRight">
              <more-outlined class="act-icon" title="更多" />
              <template #overlay>
                <a-menu @click="e => onMenuClick(s, e)">
                  <a-menu-item key="batch">
                    <check-square-outlined style="color:#666;margin-right:6px" />批量管理
                  </a-menu-item>
                  <a-menu-divider />
                  <a-menu-item key="rename">
                    <edit-outlined style="color:#666;margin-right:6px" />重命名
                  </a-menu-item>
                  <a-menu-item key="fav">
                    <star-outlined :style="{ color: s.isFavorite ? '#faad14' : '#666', marginRight: '6px' }" />
                    {{ s.isFavorite ? '取消收藏' : '收藏' }}
                  </a-menu-item>
                  <a-menu-item key="pin">
                    <pushpin-outlined :style="{ color: s.isPinned ? '#1677ff' : '#666', marginRight: '6px' }" />
                    {{ s.isPinned ? '取消置顶' : '置顶' }}
                  </a-menu-item>
                  <a-menu-divider />
                  <a-menu-item key="export">
                    <download-outlined style="color:#666;margin-right:6px" />导出 Markdown
                  </a-menu-item>
                  <a-menu-item key="del" danger>
                    <delete-outlined style="margin-right:6px" />删除会话
                  </a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </div>
        </div>
      </div>

      <!-- 批量管理操作栏（底部常驻） -->
      <div v-if="batchMode" class="batch-bar">
        <a-checkbox :checked="allSelected" :indeterminate="selected.size > 0 && !allSelected"
                    @change="toggleAll">全选</a-checkbox>
        <span class="batch-count">已选 {{ selected.size }}</span>
        <a-button size="small" danger :disabled="selected.size === 0" @click="doBatchDelete">删除所选</a-button>
      </div>
    </div>

    <!-- 折叠态：小图标条（展开 + 新建 + 对话） -->
    <div v-else class="collapsed-bar">
      <a-tooltip title="展开会话列表" placement="right">
        <a-button type="text" class="cb-btn" @click="$emit('toggle-collapse')">
          <menu-unfold-outlined />
        </a-button>
      </a-tooltip>
      <a-tooltip title="新建对话" placement="right">
        <a-button type="text" class="cb-btn" @click="$emit('new')" :disabled="loading || creating">
          <plus-outlined />
        </a-button>
      </a-tooltip>
      <a-tooltip :title="`会话列表（共 ${sessions.length} 条）`" placement="right">
        <div class="cb-badge" @click="$emit('toggle-collapse')">
          <a-badge :count="sessions.length" :overflow-count="99" size="small">
            <a-button type="text" class="cb-btn" style="pointer-events:none">
              <comment-outlined />
            </a-button>
          </a-badge>
        </div>
      </a-tooltip>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { Modal } from 'ant-design-vue'
import { PlusOutlined, DeleteOutlined, MenuFoldOutlined, MenuUnfoldOutlined, ClearOutlined, CommentOutlined,
         SearchOutlined, PushpinOutlined, PushpinFilled, StarOutlined, MoreOutlined, CheckSquareOutlined, EditOutlined, DownloadOutlined } from '@ant-design/icons-vue'

const props = defineProps({
  sessions: { type: Array, default: () => [] },
  currentSessionId: { type: String, default: null },
  collapsed: { type: Boolean, default: false },
  loading: { type: Boolean, default: false },
  creating: { type: Boolean, default: false },   // 新建对话进行中（防重复点击）
  width: { type: Number, default: 260 },         // 展开态宽度（可由外层拖拽调整）
  dragging: { type: Boolean, default: false }    // 拖拽中（禁用宽度过渡动画）
})

const emit = defineEmits(['select', 'delete', 'new', 'toggle-collapse', 'clear', 'toggle-pin', 'toggle-favorite', 'search', 'filter-change', 'batch-delete', 'rename', 'export'])

// 「···」更多菜单：批量管理进入多选模式（当前会话默认勾选）；收藏/置顶/重命名直接派发，删除二次确认
const onMenuClick = (s, { key }) => {
  if (key === 'batch') {
    batchMode.value = true
    selected.value = new Set([s.id])
  } else if (key === 'fav') emit('toggle-favorite', s)
  else if (key === 'pin') emit('toggle-pin', s)
  else if (key === 'rename') {
    // 携带条目屏幕坐标：父组件在条目旁渲染重命名悬浮卡片
    const el = itemEls.get(s.id)
    emit('rename', s, el ? el.getBoundingClientRect() : null)
  }
  else if (key === 'export') emit('export', s)
  else if (key === 'del') {
    Modal.confirm({
      title: '删除会话？',
      content: `「${s.title || '新对话'}」的问答记录将被删除`,
      okText: '删除', okType: 'danger', cancelText: '取消',
      onOk: () => emit('delete', s.id)
    })
  }
}

// 批量管理：多选会话统一删除（emit ids，父组件调批量接口并刷新列表）
const batchMode = ref(false)
const selected = ref(new Set())
const toggleBatch = () => {
  batchMode.value = !batchMode.value
  selected.value = new Set()
}
const toggleSel = id => {
  const next = new Set(selected.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selected.value = next
}
const allSelected = computed(() => props.sessions.length > 0 && props.sessions.every(s => selected.value.has(s.id)))
const toggleAll = () => {
  selected.value = allSelected.value ? new Set() : new Set(props.sessions.map(s => s.id))
}
const doBatchDelete = () => {
  const ids = [...selected.value]
  if (!ids.length) return
  Modal.confirm({
    title: `删除选中的 ${ids.length} 个会话？`,
    content: '选中会话的问答记录将被删除',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: () => {
      emit('batch-delete', ids)
      batchMode.value = false
      selected.value = new Set()
    }
  })
}

// 搜索（300ms 防抖）
const searchText = ref('')
let debounceTimer = null
const onSearchInput = () => {
  clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => emit('search', searchText.value.trim()), 300)
}
const onSearchClear = () => {
  clearTimeout(debounceTimer)
  emit('search', '')
}
// 收藏筛选 tab（父组件按 s.isFavorite 本地过滤）
const filter = ref('all')
const setFilter = f => {
  filter.value = f
  emit('filter-change', f)
}

const formatTime = (t) => {
  if (!t) return ''
  const d = new Date(t)
  if (isNaN(d.getTime())) return ''
  const now = new Date()
  const isToday = d.toDateString() === now.toDateString()
  if (isToday) {
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${mm}-${dd}`
}

// 会话条目 DOM 引用（重命名悬浮卡片定位用；列表增删时自动重建）
const itemEls = new Map()
const setItemEl = (id, el) => {
  if (el) itemEls.set(id, el)
  else itemEls.delete(id)
}

// 供父组件快捷键（Cmd/Ctrl+K）聚焦搜索框
const searchInputRef = ref(null)
const focusSearch = () => searchInputRef.value?.focus()
defineExpose({ focusSearch })
</script>

<style scoped>
.session-sidebar {
  background: #fafafa;
  border-right: 1px solid #e8e8e8;
  display: flex;
  flex-direction: row;
  overflow: hidden;
  position: relative;
  transition: width 0.18s ease, min-width 0.18s ease; /* 阈值折叠/展开时平滑过渡 */
}
.session-sidebar.no-anim { transition: none; }  /* 拖拽中禁用动画，保证跟手 */
.session-sidebar.collapsed { /* 宽度由 inline style 控制（48px） */ }
/* 折叠态小图标条 */
.collapsed-bar {
  width: 100%;           /* 跟随容器宽度（用户拖到哪宽度就是哪），图标居中 */
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 10px;
  gap: 4px;
}
.cb-btn {
  width: 36px;
  height: 36px;
  padding: 0 !important;            /* 覆盖 antd 按钮默认 padding，图标严格居中 */
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  color: #555;
  border-radius: 8px;
}
.cb-btn:hover {
  background: #f0f0f0 !important;
  color: #1677ff !important;
}
/* 折叠态对话图标（含条数角标） */
.cb-badge {
  cursor: pointer;
  border-radius: 8px;
  padding: 2px;
}
.cb-badge:hover {
  background: #f0f0f0;
}
.cb-badge :deep(.ant-badge) { display: block; }
.cb-badge :deep(.ant-badge-count) {
  box-shadow: none;
  font-size: 10px;
  /* 角标默认突出在右上角外，内收使其落在 hover 圆角范围内 */
  transform: translate(20%, -20%) !important;
  right: 6px;
  top: 4px;
}
.sidebar-inner {
  width: 100%;
  min-width: 0;              /* 跟随外层拖拽宽度伸缩 */
  display: flex;
  flex-direction: column;
  height: 100%;
}
.sidebar-header {
  padding: 12px;
  border-bottom: 1px solid #e8e8e8;
}
/* 搜索 + 收藏筛选 */
.sidebar-search {
  padding: 8px 12px 4px;
  border-bottom: 1px solid #f0f0f0;
}
.search-row { margin-bottom: 10px; }
.filter-row {
  display: flex;
  align-items: center;
  gap: 4px;
  padding-bottom: 2px;
}
.filter-tab {
  font-size: 12px;
  line-height: 20px;
  display: inline-flex;
  align-items: center;
  color: #666;
  padding: 2px 10px;
  border-radius: 10px;
  cursor: pointer;
  transition: all .15s;
  user-select: none;
}
.filter-tab:hover { color: #1677ff; }
.filter-tab.active {
  background: #e6f4ff;
  color: #1677ff;
  font-weight: 500;
}
/* 批量管理入口（筛选行右侧） */
.batch-entry {
  margin-left: auto;
  font-size: 12px;
  color: #999;
  cursor: pointer;
  user-select: none;
}
.batch-entry:hover, .batch-entry.active { color: #1677ff; }
/* 会话项操作区（hover 显示 ··· 更多按钮） */
.session-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  opacity: 0;
  transition: opacity .15s;
  flex-shrink: 0;
  margin-left: 6px;
}
.session-item:hover .session-actions { opacity: 1; }
.session-item.active .session-actions { opacity: 1; }
.act-icon {
  color: #bbb;
  font-size: 14px;
  padding: 3px;
  cursor: pointer;
  border-radius: 4px;
}
.act-icon:hover { color: #1677ff; background: #f0f0f0; }
/* 置顶标题前的图钉标记 */
.pin-mark { color: #1677ff; margin-right: 2px; }
.header-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.collapse-btn {
  color: #888;
  font-size: 14px;
  flex-shrink: 0;
}
.collapse-btn:hover {
  color: #1677ff !important;
  background: #f0f0f0 !important;
}
.session-list {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 8px;
  scrollbar-width: thin; /* Firefox */
  scrollbar-color: #d9d9d9 transparent;
}
/* 细滚动条（Webkit/Chrome/Edge）：默认淡色，hover 深色 */
.session-list::-webkit-scrollbar {
  width: 6px;
}
.session-list::-webkit-scrollbar-track {
  background: transparent;
}
.session-list::-webkit-scrollbar-thumb {
  background: #d9d9d9;
  border-radius: 3px;
}
.session-list::-webkit-scrollbar-thumb:hover {
  background: #bfbfbf;
}
.empty {
  text-align: center;
  color: #999;
  padding: 32px 16px;
  font-size: 13px;
}
/* 批量管理模式：勾选框 + 底部操作栏 */
.batch-check { margin-right: 8px; flex-shrink: 0; }
.batch-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-top: 1px solid #e8e8e8;
  background: #fff;
}
.batch-count { flex: 1; font-size: 12px; color: #999; }
.session-item {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 2px;
  transition: background 0.15s;
}
.session-item:hover {
  background: #f0f0f0;
}
.session-item.active {
  background: #e6f4ff;
}
.session-info {
  flex: 1;
  min-width: 0;
  overflow: hidden;
}
.session-title {
  font-size: 14px;
  color: #333;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: 1.4;
}
.session-meta {
  font-size: 12px;
  color: #999;
  margin-top: 2px;
  display: flex;
  gap: 8px;
  align-items: center;
  /* 侧边栏拖窄时禁止逐字折行（"3 条消息"会竖排）：时间保持完整，条数超宽时省略号 */
  white-space: nowrap;
  overflow: hidden;
}
.session-meta > span:first-child { flex-shrink: 0; }
.msg-count {
  color: #bbb;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
