<template>
  <!-- 通知 sheet（顶栏铃铛入口）：分段筛选 + 批量管理 + 点开直达。
       取数实例由移动壳 provide（inject('wqNotif')）——红点与列表必须共用同一份未读数；
       呈现与 PC 铃铛浮层同构（同一个 NotificationList），只是密度与可达动作不同。 -->
  <BottomSheet :open="open" title="通知" :subtitle="unreadCount > 0 ? unreadCount + ' 条未读' : ''"
               max-height="78dvh" @close="$emit('close')">
    <div class="nt">
      <div class="nt-bar">
        <div class="nt-tabs" role="tablist" aria-label="通知范围">
          <button class="nt-tab" :class="{ on: tab === 'action' }" type="button" role="tab"
                  :aria-selected="tab === 'action'" @click="tab = 'action'">
            待处理 <span v-if="actionCount" class="nt-b">{{ actionCount }}</span>
          </button>
          <button class="nt-tab" :class="{ on: tab === 'all' }" type="button" role="tab"
                  :aria-selected="tab === 'all'" @click="tab = 'all'">
            全部
          </button>
        </div>
        <button class="nt-act" type="button" :disabled="!unreadCount" @click="markAllRead">
          全部已读
        </button>
        <button class="nt-act" :class="{ on: selectMode }" type="button" @click="toggleSelectMode">
          {{ selectMode ? '完成' : '选择' }}
        </button>
      </div>

      <div v-if="selectMode" class="nt-batch">
        <span class="nt-batch-count">已选 {{ selected.length }} 条</span>
        <button class="nt-batch-btn" type="button" :disabled="!selected.length"
                @click="markSelectedRead">标记已读</button>
        <button class="nt-batch-btn danger" type="button" :disabled="!selected.length"
                @click="confirmDeleteSelected">删除</button>
        <button class="nt-batch-btn" type="button" @click="selectAll">全选</button>
        <button class="nt-batch-btn plain" type="button" @click="selected = []">取消</button>
      </div>

      <NotificationList
        :items="visibleItems"
        variant="sheet"
        :loading="loading"
        :error="error"
        :selectable="selectMode"
        :selected="selected"
        grouped
        empty-title="还没有通知"
        empty-desc="有新动静时会出现在这里。"
        @open="openNotif"
        @toggle-select="toggleSelect"
        @mark-unread="markUnread"
        @delete="removeOne"
      />

      <p class="nt-tip">只显示最近 50 条，点开直达可以处理的位置</p>
    </div>
  </BottomSheet>
</template>

<script setup>
import { computed, inject, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Modal } from 'ant-design-vue'
import BottomSheet from './BottomSheet.vue'
import NotificationList from '../components/notification/NotificationList.vue'
import { notifBucket, notifTarget } from '../chat/useNotifications'

const props = defineProps({ open: { type: Boolean, default: false } })
const emit = defineEmits(['close'])
const router = useRouter()

// 取数实例由移动壳 provide（本文件禁止再调 useNotifications：会造出第二份未读数）
const { items, loading, error, unreadCount, loadList, markRead, markAllRead, markUnread,
  markManyRead, remove, removeMany } = inject('wqNotif')

const tab = ref('all')
const selectMode = ref(false)
const selected = ref([])

const actionCount = computed(() => items.value.filter(n => notifBucket(n.type) === 'action').length)
const visibleItems = computed(() => tab.value === 'all'
  ? items.value
  : items.value.filter(n => notifBucket(n.type) === 'action'))

// 打开即拉最新；若「待处理」为空，自动落到「全部」，避免首次打开就是空屏
watch(() => props.open, async v => {
  if (!v) {
    selectMode.value = false
    selected.value = []
    return
  }
  await loadList()
  if (!actionCount.value && tab.value === 'action') tab.value = 'all'
})
watch(tab, () => { selected.value = [] })

/** 点开通知：先置已读（乐观，失败回滚在共用单元里），再按共享深链跳转 */
const openNotif = n => {
  markRead(n)
  emit('close')
  const to = notifTarget(n, { mobile: true })
  if (to) router.push(to).catch(() => {})
}

const toggleSelectMode = () => {
  selectMode.value = !selectMode.value
  if (!selectMode.value) selected.value = []
}
const toggleSelect = (ids, checked) => {
  if (checked) selected.value = [...new Set([...selected.value, ...ids])]
  else selected.value = selected.value.filter(id => !ids.includes(id))
}
const selectAll = () => { selected.value = visibleItems.value.map(n => n.id) }
const markSelectedRead = async () => {
  await markManyRead([...selected.value])
  selected.value = []
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
      const ok = await removeMany(ids)
      if (ok) selected.value = []
      else throw new Error('删除失败')
    }
  })
}
const removeOne = n => remove(n)
</script>

<style scoped>
.nt { display: flex; flex-direction: column; gap: 8px; }

/* 顶部操作行：分段筛选 + 全部已读 + 选择 */
.nt-bar { display: flex; align-items: center; gap: 8px; padding: 2px 0 2px; flex-wrap: wrap; }
.nt-tabs { display: flex; gap: 4px; flex: 1; min-width: 150px; }
.nt-tab {
  flex: 1; min-height: 32px; border: 1px solid var(--app-border); border-radius: 8px;
  background: var(--app-panel-2); color: var(--app-text2); font-size: 12px;
  display: inline-flex; align-items: center; justify-content: center; gap: 4px;
  cursor: pointer; touch-action: manipulation;
}
.nt-tab.on { background: var(--app-accent-weak); border-color: var(--app-accent-border); color: var(--app-accent); font-weight: 600; }
.nt-b { font-family: var(--ui-monospace, monospace); font-size: 9.5px; color: var(--app-warn-text); background: var(--app-warn-weak); border-radius: 4px; padding: 1px 4px; }
.nt-act {
  min-height: 36px; padding: 0 12px; border: 1px solid var(--app-border); border-radius: 9px;
  background: var(--app-panel); color: var(--app-text2); font-size: 12px;
  touch-action: manipulation; white-space: nowrap;
}
.nt-act:disabled { opacity: .5; }
.nt-act:active:not(:disabled) { background: var(--app-accent-weak); color: var(--app-accent); }
.nt-act.on { border-color: var(--app-accent-border); background: var(--app-accent-weak); color: var(--app-accent); font-weight: 600; }

/* 批量条：手机端也保留全选/标记已读/删除 */
.nt-batch {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 9px 10px; border-radius: 9px;
  background: var(--app-accent-weak); border: 1px solid var(--app-accent-border);
  font-size: 12px; color: var(--app-accent);
}
.nt-batch-count { font-weight: 600; width: 100%; }
.nt-batch-btn {
  min-height: 34px; padding: 0 12px; border: 1px solid var(--app-accent-border);
  border-radius: 8px; background: var(--app-panel); color: var(--app-accent);
  font-size: 12px; touch-action: manipulation;
}
.nt-batch-btn:disabled { opacity: .5; }
.nt-batch-btn.danger { color: var(--app-danger); border-color: var(--app-danger-border); }
.nt-batch-btn.plain { border-color: transparent; background: transparent; color: var(--app-accent); }

.nt-tip { font-size: 12px; line-height: 1.6; color: var(--app-text3); padding: 4px 2px 0; }
/* 触屏热区：顶部操作与批量按钮补到 44px 命中高度 */
.nt-act, .nt-batch-btn { position: relative; }
.nt-act::after, .nt-batch-btn::after {
  content: ''; position: absolute; top: 50%; left: 0; right: 0;
  height: 44px; transform: translateY(-50%);
}
</style>
