<template>
  <div class="ops-ann">
    <div class="metric-grid three">
      <div class="app-card metric"><div class="metric-label">发布中</div><div class="metric-num">{{ publishedCount }}</div><div class="metric-sub">登录后横幅常显 · danger 不可关</div></div>
      <div class="app-card metric"><div class="metric-label">已下线</div><div class="metric-num">{{ offlineCount }}</div><div class="metric-sub">保留历史，可编辑重新发布</div></div>
      <div class="app-card metric"><div class="metric-label">已读回执</div><div class="metric-num">{{ readTotal }}</div><div class="metric-sub">关闭横幅或展开详情计为已读</div></div>
    </div>

    <section class="app-card ann-card-list">
      <div class="app-list-bar ann-toolbar">
        <span class="ann-hint">停机窗口 / 升级 / 新功能通知：发布前可预览横幅，支持定时上下线与按部门/角色定向。</span>
        <span class="app-list-spacer"></span>
        <button class="app-btn" type="button" @click="openEditor(null)">发布公告</button>
      </div>

      <div v-if="loading" class="ann-loading"><a-skeleton active :paragraph="{ rows: 4 }" /></div>
      <a-empty v-else-if="!rows.length" description="还没有发布过公告" />
      <div v-else class="ann-list">
        <div v-for="a in rows" :key="a.id" class="ann-row">
          <div class="ann-level" :class="a.level"></div>
          <div class="ann-body">
            <div class="ann-top">
              <span class="app-pill" :class="levelPill(a.level)">{{ levelText(a.level) }}</span>
              <h4 class="ann-title">{{ a.title }}</h4>
              <span class="app-pill" :class="statusPill(a)">{{ statusText(a) }}</span>
            </div>
            <p v-if="a.content" class="ann-content">{{ a.content }}</p>
            <div class="ann-meta">
              <span>对象 <b>{{ audienceLabel(a) }}</b></span>
              <span>窗口 <b>{{ windowText(a) }}</b></span>
              <span>已读 <b>{{ a.readCount || 0 }} / {{ a.audienceCount ?? '—' }}</b></span>
              <span v-if="a.contentFormat === 'markdown'" class="ann-format">Markdown</span>
            </div>
            <div v-if="a.audienceCount" class="read-bar"><i :style="{ width: readPct(a) + '%' }"></i></div>
          </div>
          <div class="ann-ops">
            <button class="app-link-btn" type="button" @click="openEditor(a)">编辑</button>
            <button v-if="a.enabled === 1" class="app-link-btn danger" type="button" @click="confirmDisable(a)">下线</button>
            <button v-else class="app-link-btn danger" type="button" @click="confirmPurge(a)">删除</button>
          </div>
        </div>
      </div>
    </section>

    <div class="app-list-foot">
      「已下线」保留历史，可编辑重新发布；彻底删除仅对已下线公告开放，动作带标题入审计。
    </div>

    <!-- 编辑抽屉 -->
    <a-drawer v-model:open="editorOpen" :width="430" :closable="false" placement="right"
              :header-style="drawerHeaderStyle" :body-style="drawerBodyStyle" :footer-style="drawerFooterStyle">
      <template #title>
        <div class="ops-drawer-head">
          <div>
            <div class="ops-drawer-eyebrow">{{ form.id ? '编辑公告' : '发布公告' }}</div>
            <div class="ops-drawer-name">{{ form.id ? (form.title || '公告详情') : '发布公告' }}</div>
          </div>
          <button class="app-icon-btn ops-drawer-close" type="button" aria-label="关闭" @click="editorOpen = false">✕</button>
        </div>
      </template>
      <div class="ann-editor">
        <div class="editor-section">
          <div class="editor-label">横幅预览（与登录后横幅同款配色）</div>
          <div class="ann-preview" :class="form.level">
            <span class="ann-preview-ic">{{ form.level === 'info' ? 'i' : '!' }}</span>
            <div class="ann-preview-main">
              <div class="ann-preview-title">{{ form.title || '公告标题' }}</div>
              <div class="ann-preview-body" v-html="previewHtml"></div>
            </div>
          </div>
        </div>

        <div class="editor-section">
          <div class="editor-label">级别</div>
          <div class="ops-seg level-seg" role="group" aria-label="公告级别">
            <button v-for="o in levelOptions" :key="o.value" type="button" :data-level="o.value"
                    :class="{ active: form.level === o.value }" @click="form.level = o.value">{{ o.label }}</button>
          </div>
        </div>

        <div class="editor-section">
          <div class="editor-label">标题</div>
          <a-input v-model:value="form.title" size="small" :maxlength="200" placeholder="如：周六 22:00-24:00 停机维护" />
        </div>

        <div class="editor-section">
          <div class="editor-label">正文</div>
          <a-textarea v-model:value="form.content" :rows="4" :maxlength="2000" placeholder="停机窗口、影响范围、联系人等；留空则只有横幅标题" />
        </div>

        <div class="editor-row">
          <div class="editor-section">
            <div class="editor-label">正文格式</div>
            <a-select v-model:value="form.contentFormat" size="small" :options="formatOptions" style="width:100%" />
          </div>
          <div class="editor-section">
            <div class="editor-label">发布状态</div>
            <div class="editor-switch"><a-switch v-model:checked="annEnabled" size="small" /><span>{{ annEnabled ? '发布中' : '保存为草稿（已下线）' }}</span></div>
          </div>
        </div>

        <div class="editor-section">
          <div class="editor-label">展示对象</div>
          <a-select v-model:value="form.audienceType" size="small" :options="audienceOptions" style="width:100%" @change="onAudienceType" />
          <a-select v-if="form.audienceType !== 'all'" v-model:value="form.audienceIds" mode="tags" size="small"
                    :options="audienceIdOptions" :placeholder="form.audienceType === 'department' ? '选择或输入部门 ID' : '选择或输入角色 code'"
                    style="width:100%;margin-top:6px" />
        </div>

        <div v-if="superadmin" class="editor-section">
          <div class="editor-label">发布租户</div>
          <a-select v-model:value="form.tenantId" size="small" :options="tenantOptions" placeholder="默认当前租户" allow-clear style="width:100%" />
        </div>

        <div class="editor-row">
          <div class="editor-section">
            <div class="editor-label">开始时间</div>
            <a-date-picker v-model:value="form.startTime" size="small" show-time format="YYYY-MM-DD HH:mm"
                           value-format="YYYY-MM-DD HH:mm:ss" placeholder="立即生效" allow-clear style="width:100%" />
          </div>
          <div class="editor-section">
            <div class="editor-label">结束时间</div>
            <a-date-picker v-model:value="form.endTime" size="small" show-time format="YYYY-MM-DD HH:mm"
                           value-format="YYYY-MM-DD HH:mm:ss" placeholder="直到手动下线" allow-clear style="width:100%" />
          </div>
        </div>
        <div class="editor-tip">开始时间空 = 发布时间起；结束时间空 = 直到手动下线。窗口外成员侧不会下发。</div>
      </div>

      <template #footer>
        <div class="editor-foot">
          <button class="app-btn ghost" type="button" @click="editorOpen = false">取消</button>
          <button class="app-btn" type="button" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </template>
    </a-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { listAnnouncements, saveAnnouncement, deleteAnnouncement, purgeAnnouncement,
         listDepartments, listRoleOptions, listTenants } from '../../api'
import { isSuperadminSync } from '../../utils/auth'
import { renderMd } from '../../utils/markdown'

const superadmin = isSuperadminSync()
const rows = ref([])
const loading = ref(false)

// 抽屉外壳与原型对齐：自定义 eyebrow 标题栏、18px 内边距、底部动作条
const drawerHeaderStyle = { padding: '16px 18px 12px', borderBottom: '1px solid var(--app-border)' }
const drawerBodyStyle = { padding: '14px 18px 8px' }
const drawerFooterStyle = { padding: '11px 18px', borderTop: '1px solid var(--app-border)' }

const levelOptions = [{ value: 'info', label: '通知' }, { value: 'warn', label: '警示' }, { value: 'danger', label: '紧急' }]
const formatOptions = [{ value: 'plain', label: '纯文本' }, { value: 'markdown', label: 'Markdown' }]
const audienceOptions = [{ value: 'all', label: '全员' }, { value: 'department', label: '指定部门' }, { value: 'role', label: '指定角色' }]

const departments = ref([])
const roles = ref([])
const tenants = ref([])
const deptOptions = computed(() => departments.value.map(d => ({ label: d.name, value: d.id })))
const roleOptions = computed(() => roles.value.map(r => ({ label: r.name || r.code, value: r.code })))
const tenantOptions = computed(() => tenants.value.map(t => ({ label: t.name || t.id, value: t.id })))
const audienceIdOptions = computed(() => form.audienceType === 'department' ? deptOptions.value : roleOptions.value)

const publishedCount = computed(() => rows.value.filter(a => statusOf(a) === 'published').length)
const offlineCount = computed(() => rows.value.filter(a => a.enabled !== 1).length)
const readTotal = computed(() => rows.value.reduce((n, a) => n + Number(a.readCount || 0), 0))

const levelText = l => ({ info: '通知', warn: '警示', danger: '紧急' }[l] || l)
const levelPill = l => ({ info: 'muted', warn: 'warn', danger: 'err' }[l] || 'muted')

function normalizeTime (s) {
  if (!s) return null
  return String(s).replace('T', ' ').slice(0, 19)
}
const nowMs = () => Date.now()
const timeMs = s => {
  const t = normalizeTime(s)
  if (!t) return null
  const d = new Date(t.replace(' ', 'T'))
  return isNaN(d.getTime()) ? null : d.getTime()
}
function statusOf (a) {
  if (a.enabled !== 1) return 'offline'
  const start = timeMs(a.startTime)
  const end = timeMs(a.endTime)
  if (start && start > nowMs()) return 'scheduled'
  if (end && end <= nowMs()) return 'ended'
  return 'published'
}
const statusText = a => ({ published: '发布中', scheduled: '待发布', ended: '已结束', offline: '已下线' }[statusOf(a)])
const statusPill = a => ({ published: 'ok', scheduled: 'warn', ended: 'muted', offline: 'muted' }[statusOf(a)])
const windowText = a => {
  const s = normalizeTime(a.startTime)
  const e = normalizeTime(a.endTime)
  if (!s && !e) return '长期有效'
  if (s && !e) return `${s.slice(5, 16)} 起`
  if (!s && e) return `至 ${e.slice(5, 16)}`
  return `${s.slice(5, 16)} → ${e.slice(5, 16)}`
}
const audienceLabel = a => {
  if (!a.audienceType || a.audienceType === 'all') return '全员'
  const ids = (a.audienceIds || '').split(',').map(x => x.trim()).filter(Boolean)
  if (!ids.length) return '全员'
  const names = a.audienceType === 'department'
    ? ids.map(id => (departments.value.find(d => d.id === id) || {}).name || id)
    : ids.map(code => (roles.value.find(r => r.code === code) || {}).name || code)
  return `${a.audienceType === 'department' ? '部门' : '角色'}：${names.join('、')}`
}
const readPct = a => {
  const total = Number(a.audienceCount || 0)
  if (!total) return 0
  return Math.min(100, Math.round(Number(a.readCount || 0) * 100 / total))
}

async function load () {
  loading.value = true
  try {
    const r = await listAnnouncements()
    if (r.success) rows.value = r.data || []
    else message.error(r.msg || '公告加载失败')
  } catch (e) { message.error(e.message || '公告加载失败') } finally { loading.value = false }
}
async function loadOptions () {
  try {
    const r = await listDepartments()
    if (r.success) departments.value = r.data || []
  } catch (e) { /* 权限不足时允许手输 ID */ }
  try {
    const r = await listRoleOptions()
    if (r.success) roles.value = r.data || []
  } catch (e) { /* 同上 */ }
  if (superadmin) {
    try {
      const r = await listTenants()
      if (r.success) tenants.value = r.data || []
    } catch (e) { /* 忽略 */ }
  }
}

// ==================== 编辑 ====================
const editorOpen = ref(false)
const saving = ref(false)
const annEnabled = ref(true)
const form = reactive({
  id: '',
  title: '',
  content: '',
  contentFormat: 'plain',
  level: 'info',
  audienceType: 'all',
  audienceIds: [],
  startTime: null,
  endTime: null,
  tenantId: undefined
})

function openEditor (a) {
  if (a) {
    form.id = a.id
    form.title = a.title || ''
    form.content = a.content || ''
    form.contentFormat = a.contentFormat || 'plain'
    form.level = a.level || 'info'
    form.audienceType = a.audienceType || 'all'
    form.audienceIds = (a.audienceIds || '').split(',').map(x => x.trim()).filter(Boolean)
    form.startTime = normalizeTime(a.startTime)
    form.endTime = normalizeTime(a.endTime)
    form.tenantId = a.tenantId || undefined
    annEnabled.value = a.enabled === 1
  } else {
    form.id = ''
    form.title = ''
    form.content = ''
    form.contentFormat = 'plain'
    form.level = 'info'
    form.audienceType = 'all'
    form.audienceIds = []
    form.startTime = null
    form.endTime = null
    form.tenantId = undefined
    annEnabled.value = true
  }
  editorOpen.value = true
  loadOptions()
}
function onAudienceType () { form.audienceIds = [] }

const escapeHtml = s => String(s || '').replace(/[&<>"']/g, c => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
}[c]))
const previewHtml = computed(() => {
  const text = form.content || ''
  if (!text.trim()) return '<span style="color:var(--app-text3)">正文会显示在这里；留空则只有标题。</span>'
  if (form.contentFormat === 'markdown') return renderMd(text)
  return escapeHtml(text).replace(/\n/g, '<br>')
})

async function save () {
  if (!form.title || !form.title.trim()) { message.error('要给公告起个标题'); return }
  if (form.audienceType !== 'all' && !form.audienceIds.length) {
    message.error(form.audienceType === 'department' ? '请选择至少一个部门' : '请选择至少一个角色')
    return
  }
  const start = form.startTime ? timeMs(form.startTime) : null
  const end = form.endTime ? timeMs(form.endTime) : null
  if (start && end && end <= start) { message.error('结束时间必须晚于开始时间'); return }
  saving.value = true
  try {
    const r = await saveAnnouncement({
      id: form.id || undefined,
      title: form.title,
      content: form.content,
      contentFormat: form.contentFormat,
      level: form.level,
      enabled: annEnabled.value ? 1 : 0,
      audienceType: form.audienceType,
      audienceIds: form.audienceType === 'all' ? null : form.audienceIds.join(','),
      startTime: form.startTime || null,
      endTime: form.endTime || null,
      tenantId: superadmin ? form.tenantId : undefined
    })
    if (r.success) {
      message.success('已保存')
      editorOpen.value = false
      load()
    } else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') } finally { saving.value = false }
}

function confirmDisable (a) {
  Modal.confirm({
    title: '下线该公告？',
    content: '下线保留历史，可再编辑重新发布；动作入审计。',
    okText: '下线', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      try {
        const r = await deleteAnnouncement(a.id)
        if (r.success) { message.success('已下线'); load() } else message.error(r.msg || '下线失败')
      } catch (e) { message.error(e.message || '下线失败') }
    }
  })
}
function confirmPurge (a) {
  Modal.confirm({
    title: '删除该公告？',
    content: '物理删除不可恢复，动作带标题入审计。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      try {
        const r = await purgeAnnouncement(a.id)
        if (r.success) { message.success('已删除'); load() } else message.error(r.msg || '删除失败')
      } catch (e) { message.error(e.message || '删除失败') }
    }
  })
}

onMounted(() => {
  load()
  loadOptions()
})
</script>

<style scoped>
.ops-ann { display: flex; flex-direction: column; gap: 10px; }
.metric-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.metric { padding: 12px 14px; }
.metric-label { font-size: 10.5px; color: var(--app-text3); }
.metric-num { font-size: 23px; font-weight: 700; line-height: 1.2; margin-top: 5px; letter-spacing: -.02em; }
.metric-sub { font-size: 10.5px; color: var(--app-text3); margin-top: 3px; }
.ann-card-list { padding: 0; overflow: hidden; }
.ann-toolbar { padding: 10px 12px; border-bottom: 1px solid var(--app-border); }
.ann-hint { font-size: 11px; color: var(--app-text2); }
.ann-loading { padding: 16px; }
.ann-list { display: flex; flex-direction: column; }
.ann-row { display: flex; gap: 12px; padding: 12px 14px; border-bottom: 1px solid var(--app-border); }
.ann-row:last-child { border-bottom: none; }
.ann-level { width: 3px; border-radius: 2px; flex: none; background: var(--app-accent); }
.ann-level.warn { background: var(--app-warn); }
.ann-level.danger { background: var(--app-danger); }
.ann-body { flex: 1; min-width: 0; }
.ann-top { display: flex; align-items: center; gap: 8px; min-width: 0; }
.ann-title { margin: 0; font-size: 12.5px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ann-content {
  margin: 5px 0 0; font-size: 11px; color: var(--app-text2); line-height: 1.55;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.ann-meta { display: flex; flex-wrap: wrap; gap: 5px 12px; margin-top: 7px; font-size: 10px; color: var(--app-text3); }
.ann-meta b { color: var(--app-text2); font-weight: 500; }
.ann-format { border: 1px solid var(--app-border); border-radius: 3px; padding: 0 4px; }
.read-bar { height: 4px; background: var(--app-bg, #f7f8fa); border-radius: 2px; margin-top: 7px; overflow: hidden; max-width: 260px; }
.read-bar i { display: block; height: 100%; background: var(--app-accent); border-radius: 2px; }
.ann-ops { flex: none; display: flex; flex-direction: column; align-items: flex-end; justify-content: center; gap: 2px; }

.editor-section { margin-bottom: 14px; }
/* 原型表单控件高 28px；antd small 默认 24px，这里统一抬到 28（textarea 不参与）；
   上下 padding 必须归零，否则 28px 高度里只剩 18px 内容区，文字下半截会被裁掉 */
.ann-editor :deep(.ant-input:not(textarea)) {
  height: 28px; line-height: 26px; padding-top: 0; padding-bottom: 0;
}
.ann-editor :deep(.ant-select-single .ant-select-selector),
.ann-editor :deep(.ant-picker) { height: 28px; padding-top: 0; padding-bottom: 0; }
.ann-editor :deep(.ant-picker input) { line-height: 26px; }
.ann-editor :deep(.ant-select-single .ant-select-selector .ant-select-selection-item),
.ann-editor :deep(.ant-select-single .ant-select-selector .ant-select-selection-placeholder) { line-height: 26px; }
.ops-seg { display: inline-flex; gap: 2px; padding: 2px; background: var(--app-bg, #f7f8fa); border-radius: 7px; }
.ops-seg button {
  border: none; background: transparent; color: var(--app-text2); font-size: 11px;
  padding: 5px 9px; border-radius: 5px; white-space: nowrap; cursor: pointer;
}
.ops-seg button:hover { color: var(--app-text); }
.ops-seg button.active {
  background: var(--app-panel); color: var(--app-text); font-weight: 600;
  box-shadow: 0 1px 2px rgba(16, 24, 40, .06);
}
.level-seg button.active[data-level="warn"] { color: var(--app-warn-text, #a3691b); }
.level-seg button.active[data-level="danger"] { color: var(--app-danger); }
.ops-drawer-head { display: flex; align-items: flex-start; gap: 10px; min-width: 0; }
.ops-drawer-eyebrow { font-size: 10px; color: var(--app-text3); letter-spacing: .06em; margin-bottom: 4px; }
.ops-drawer-name { font-size: 15px; font-weight: 600; line-height: 1.35; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ops-drawer-close { margin-left: auto; flex: none; }
.editor-label { font-size: 10.5px; color: var(--app-text3); margin-bottom: 6px; }
.editor-row { display: flex; gap: 12px; }
.editor-row > .editor-section { flex: 1; min-width: 0; }
.editor-switch { display: flex; align-items: center; gap: 7px; font-size: 11px; color: var(--app-text2); height: 32px; }
.editor-tip { font-size: 10.5px; color: var(--app-text3); line-height: 1.6; margin-top: -6px; }
.editor-foot { display: flex; justify-content: flex-end; gap: 8px; }
.ann-preview {
  border: 1px solid var(--app-border); border-radius: 6px; padding: 9px 11px;
  display: flex; gap: 9px; align-items: flex-start; background: var(--app-panel-2);
  min-height: 54px;
}
.ann-preview.info { background: var(--app-info-weak, #f0f6ff); border-color: var(--app-info-border, #d6e4ff); }
.ann-preview.warn { background: var(--app-warn-weak, #faf3e6); border-color: var(--app-warn-border, #f0dfb6); }
.ann-preview.danger { background: var(--app-danger-weak, #fff2f0); border-color: var(--app-danger-border, #ffccc7); }
.ann-preview-ic {
  width: 17px; height: 17px; border-radius: 50%; flex: none;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 10px; font-weight: 700; background: rgba(255, 255, 255, .7); color: var(--app-accent);
}
.ann-preview.warn .ann-preview-ic { color: var(--app-warn-text, #a3691b); }
.ann-preview.danger .ann-preview-ic { color: var(--app-danger); }
.ann-preview-main { min-width: 0; }
.ann-preview-title { font-size: 11.5px; font-weight: 600; line-height: 1.5; }
.ann-preview-body { font-size: 10.5px; color: var(--app-text2); line-height: 1.55; margin-top: 2px; max-height: 150px; overflow: auto; }
.ann-preview-body :deep(p) { margin: 3px 0; }
.ann-preview-body :deep(code) { background: var(--app-code-inline-bg, #f0f0f0); padding: 0 3px; border-radius: 3px; }

@media (max-width: 900px) {
  .metric-grid { grid-template-columns: 1fr; }
  .editor-row { flex-direction: column; }
  .ann-row { flex-wrap: wrap; }
  .ann-ops { flex-direction: row; }
}
</style>
