<template>
  <!--
    平台公告横幅（登录后常显）：读本租户发布中、时间窗口内、命中当前用户部门/角色的公告，
    逐条可关闭（sessionStorage 记住本次会话已关），danger 级别常驻不可关——停机这种事不接受「我没看见」。
    放在内容区之上（与断网横幅同级）：「平台要停机」是持续状态，不是一条读完即走的通知。
  -->
  <div v-if="shown.length" class="ann-bar">
    <a-alert v-for="a in shown" :key="a.id" :type="typeOf(a.level)" show-icon class="ann-item"
             :closable="a.level !== 'danger'" @close="dismiss(a)">
      <template #message>
        <span class="ann-title">{{ a.title }}</span>
      </template>
      <template v-if="a.content" #description>
        <span class="ann-desc">{{ stripMarkdown(a.content) }}</span>
      </template>
      <template #action>
        <button v-if="a.content" class="ann-detail-link" type="button" @click="openDetail(a)">详情</button>
      </template>
    </a-alert>
  </div>

  <a-modal v-model:open="detailOpen" :title="detail ? detail.title : '公告详情'" :footer="null" :width="560">
    <div v-if="detail" class="ann-detail">
      <div class="ann-detail-meta">
        <span class="app-pill" :class="detail.level === 'danger' ? 'err' : detail.level === 'warn' ? 'warn' : 'ok'">
          {{ detail.level === 'danger' ? '紧急' : detail.level === 'warn' ? '警示' : '通知' }}
        </span>
        <span v-if="detail.createTime" class="ann-detail-time">{{ fmtTime(detail.createTime) }}</span>
      </div>
      <div class="ann-detail-body" v-html="htmlOf(detail)"></div>
    </div>
  </a-modal>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getActiveAnnouncements, markAnnouncementRead } from '../api'
import { renderMd } from '../utils/markdown'

const shown = ref([])
const detailOpen = ref(false)
const detail = ref(null)

const typeOf = level => ({ info: 'info', warn: 'warning', danger: 'error' }[level] || 'info')
const dismissed = a => sessionStorage.getItem('ann-dismissed-' + a.id) === '1'

const fmtTime = s => (s ? String(s).replace('T', ' ').slice(0, 16) : '')
const stripMarkdown = s => String(s || '')
  .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
  .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
  .replace(/[*_`#>~]/g, '')
  .replace(/\s+/g, ' ')
  .trim()
const escapeHtml = s => String(s || '').replace(/[&<>"']/g, c => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
}[c]))
const htmlOf = a => {
  if (!a) return ''
  const text = a.content || ''
  if (a.contentFormat === 'markdown') return renderMd(text)
  return escapeHtml(text).replace(/\n/g, '<br>')
}

function markRead (a) {
  if (!a) return
  markAnnouncementRead(a.id).catch(() => { /* 回执失败不挡人：横幅是增强，不是链路 */ })
}
function openDetail (a) {
  detail.value = a
  detailOpen.value = true
  markRead(a)
}
function dismiss (a) {
  sessionStorage.setItem('ann-dismissed-' + a.id, '1')
  shown.value = shown.value.filter(x => x.id !== a.id)
  markRead(a)
}

onMounted(async () => {
  try {
    const r = await getActiveAnnouncements()
    if (r.success) shown.value = (r.data || []).filter(a => !dismissed(a))
  } catch (e) { /* 公告拿不到不挡人 */ }
})
</script>

<style scoped>
.ann-bar { display: flex; flex-direction: column; gap: 8px; padding: 8px 16px 0; }
.ann-item { margin: 0; }
.ann-title { font-size: 13px; }
.ann-desc { font-size: 12px; }
.ann-detail-link {
  border: none; background: transparent; color: var(--app-accent);
  font-size: 12px; cursor: pointer; padding: 0 2px;
}
.ann-detail-link:hover { text-decoration: underline; }
.ann-detail-meta { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.ann-detail-time { font-size: 11px; color: var(--app-text3); }
.ann-detail-body { font-size: 13px; color: var(--app-text2); line-height: 1.75; }
.ann-detail-body :deep(p) { margin: 6px 0; }
.ann-detail-body :deep(code) { background: var(--app-code-inline-bg, #f0f0f0); padding: 1px 4px; border-radius: 3px; }
.ann-detail-body :deep(img) { max-width: 100%; }
</style>
