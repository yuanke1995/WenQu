<template>
  <!-- 通知 sheet（顶栏铃铛入口）：列表 + 全部已读 + 点开跳转。
       取数实例由移动壳 provide（inject('wqNotif')）——红点与列表必须共用同一份未读数；
       呈现与 PC 铃铛 popover 同构（同一份图标/色调/相对时间），只是把 popover 换成 sheet。 -->
  <BottomSheet :open="open" title="通知" :subtitle="unreadCount > 0 ? unreadCount + ' 条未读' : ''"
               max-height="78dvh" @close="$emit('close')">
    <div class="nt">
      <div class="nt-bar">
        <button class="nt-act" type="button" :disabled="!unreadCount" @click="markAllRead">全部已读</button>
        <button class="nt-act" type="button" :disabled="loading" @click="loadList">刷新</button>
      </div>

      <div v-if="loading && !items.length" class="nt-empty">加载中…</div>
      <div v-else-if="!items.length" class="nt-empty">还没有通知</div>

      <button v-for="n in items" :key="n.id" class="nt-item" :class="{ unread: !n.readFlag }" type="button" @click="openNotif(n)">
        <component :is="notifIcon(n.type)" class="nt-ic" :class="notifClass(n.type)" />
        <span class="nt-body">
          <span class="nt-title">{{ n.title }}<span v-if="!n.readFlag" class="nt-dot" /></span>
          <span v-if="n.content" class="nt-content">{{ n.content }}</span>
          <span class="nt-time">{{ notifTime(n.createTime) }}</span>
        </span>
      </button>

      <!-- 手机上不引通知中心整页（筛选/清理/静音偏好是管理向）：这里只回答"有什么新事"，
           点开直达对应位置；完整管理留在电脑端 -->
      <div class="nt-tip">只显示最近 50 条。审批类通知点开会直达可以处理的位置。</div>
    </div>
  </BottomSheet>
</template>

<script setup>
import { inject, watch } from 'vue'
import { useRouter } from 'vue-router'
import BottomSheet from './BottomSheet.vue'
import { notifIcon, notifClass, notifTime } from '../chat/useNotifications'

const props = defineProps({ open: { type: Boolean, default: false } })
const emit = defineEmits(['close'])
const router = useRouter()

// 取数实例由移动壳 provide（本文件禁止再调 useNotifications：会造出第二份未读数）
const { items, loading, unreadCount, loadList, markRead, markAllRead } = inject('wqNotif')

watch(() => props.open, v => { if (v) loadList() })

/** 点开通知：先置已读（乐观，失败回滚在共用单元里），再按 ref 跳转。
 *  落点与 PC 同一套语义，只把 /chat 换成移动壳 /m/chat；管理向页面（/agents、/knowledge）
 *  手机上本来就有守卫卡拦着——不在这里另做判断，守卫是"这个页面能不能开"的唯一事实源。 */
const openNotif = n => {
  markRead(n)
  emit('close')
  const go = loc => router.push(loc).catch(() => {})
  if (n.type === 'tool.approval' && n.refId && n.refSub) go({ path: '/m/chat', query: { sid: n.refId, approval: n.refSub } })
  else if (n.type === 'tool.ask' && n.refId && n.refSub) go({ path: '/m/chat', query: { sid: n.refId, ask: n.refSub } })
  else if (n.type === 'workflow.approval' && n.refId && n.refSub) go({ path: '/agents', query: { tab: 'workflow', wf: n.refId, run: n.refSub } })
  else if (n.refType === 'kb' && n.refId) go(`/knowledge/${n.refId}/docs`)
  else if (n.refType === 'workflow' && n.refId) go({ path: '/agents', query: { tab: 'workflow' } })
  else if (n.refType === 'session' && n.refId) go({ path: '/m/chat', query: { sid: n.refId } })
  else if (n.refType === 'provider' && n.refId) go({ path: '/agents', query: { tab: 'providers' } })
}
</script>

<style scoped>
.nt { display: flex; flex-direction: column; gap: 4px; }
.nt-bar { display: flex; gap: 8px; padding: 2px 0 8px; }
.nt-act {
  min-height: 36px; padding: 0 14px; border: 1px solid var(--app-border); border-radius: 9px;
  background: var(--app-panel); color: var(--app-text2); font-size: 13px; touch-action: manipulation;
}
.nt-act:disabled { opacity: .5; }
.nt-act:active:not(:disabled) { background: var(--app-accent-weak); color: var(--app-accent); }
.nt-empty { padding: 28px 0; text-align: center; color: var(--app-text3); font-size: 13px; }
.nt-item {
  display: flex; gap: 10px; align-items: flex-start; width: 100%; text-align: left;
  padding: 11px 10px; border: none; border-bottom: 1px dashed var(--app-border);
  background: transparent; color: var(--app-text2); touch-action: manipulation;
}
.nt-item.unread { background: var(--app-accent-weak); border-radius: 10px; }
.nt-ic { font-size: 15px; flex: none; margin-top: 2px; color: var(--app-text3); }
.nt-ic.ok { color: var(--app-ok); }
.nt-ic.err { color: var(--app-danger); }
.nt-ic.warn { color: var(--app-warn-text); }
.nt-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.nt-title { font-size: 14px; color: var(--app-text); display: flex; align-items: center; gap: 6px; }
.nt-item.unread .nt-title { font-weight: 600; }
.nt-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--app-accent); flex: none; }
.nt-content {
  font-size: 12px; color: var(--app-text3); line-height: 1.5;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.nt-time { font-size: 11px; color: var(--app-text3); }
.nt-tip { font-size: 12px; line-height: 1.6; color: var(--app-text3); padding: 10px 2px 0; }
</style>
