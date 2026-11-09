<script setup>
/**
 * 统一审计事件：查「谁在什么时候对什么做了什么」，
 * 一键导出 CSV 交合规，并校验哈希链有没有被改过。挂在「权限管理 → 审计事件」页签下。
 */
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { SearchOutlined } from '@ant-design/icons-vue'
import { listAuditEvents, verifyAuditChain, exportAuditCsv } from '../api'

const route = useRoute()
const rows = ref([])
const actions = ref([])
const total = ref(0)
const loading = ref(false)
const exporting = ref(false)
const verifying = ref(false)
const chain = ref(null)
const dropped = ref(0)
/** 初值支持深链：权限页「变更记录 → 查看全部」会带 ?keyword=角色编码&days=30 过来 */
const initialDays = () => {
  const d = Number(route.query.days)
  return [1, 7, 30, 90, 365].includes(d) ? d : 7
}
const filters = ref({
  days: initialDays(),
  actor: String(route.query.actor || ''),
  action: route.query.action ? String(route.query.action) : undefined,
  keyword: String(route.query.keyword || '')
})
const page = ref(1)
// 每页 15 条：控制列表长度，尽量让一屏放下、不出滚动条（后端 query 对 size 的上限是 500，改这里就够）
const size = 15

const cols = [
  { title: '时间', dataIndex: 'ts', key: 'ts', width: 138 },
  { title: '级别', dataIndex: 'level', key: 'level', width: 56 },
  { title: '动作', dataIndex: 'action', key: 'action', width: 190, ellipsis: true },
  { title: '操作人', dataIndex: 'actor_uid', key: 'actor_uid', width: 110, ellipsis: true },
  { title: '来源 IP', dataIndex: 'actor_ip', key: 'actor_ip', width: 98, ellipsis: true },
  { title: '对象', key: 'target', width: 160, ellipsis: true },
  { title: '详情', dataIndex: 'detail', key: 'detail', ellipsis: true }
]
const query = () => {
  const f = filters.value
  const p = new URLSearchParams()
  p.set('days', f.days || 7); p.set('page', page.value); p.set('size', size)
  if (f.actor) p.set('actor', f.actor)
  if (f.action) p.set('action', f.action)
  if (f.keyword) p.set('keyword', f.keyword)
  return p.toString()
}
const load = async () => {
  loading.value = true
  try {
    const r = await listAuditEvents(query())
    if (!r.success) { message.error(r.msg || '审计事件加载失败'); return }
    const d = r.data || {}
    rows.value = d.items || []
    total.value = Number(d.total || 0)
    actions.value = d.actions || []
    dropped.value = Number(d.droppedSinceStart || 0)
  } catch (e) { message.error(e.message || '审计事件加载失败') } finally { loading.value = false }
}
const search = () => { page.value = 1; load() }

/** 深链参数变化（同一页面再次从权限页跳来）时同步筛选并重查 */
watch(() => [route.query.keyword, route.query.days, route.query.actor, route.query.action], () => {
  if (route.path !== '/security') return
  filters.value.days = initialDays()
  filters.value.actor = String(route.query.actor || '')
  filters.value.action = route.query.action ? String(route.query.action) : undefined
  filters.value.keyword = String(route.query.keyword || '')
  page.value = 1
  load()
})
const onPage = (p) => { page.value = p; load() }
const doExport = async () => {
  exporting.value = true
  try {
    await exportAuditCsv(query().replace(/page=\d+&?/, '').replace(/&size=\d+/, ''))
    message.success('已导出 CSV')
  } catch (e) { message.error(e.message || '导出失败') } finally { exporting.value = false }
}
const doVerify = async () => {
  verifying.value = true
  try {
    const r = await verifyAuditChain(50000)
    if (!r.success) { message.error(r.msg || '校验失败'); return }
    chain.value = r.data || null
    message.success(chain.value?.ok ? '审计链自校验通过' : `发现 ${chain.value?.problems?.length || 0} 处断点`)
  } catch (e) { message.error(e.message || '校验失败') } finally { verifying.value = false }
}
const targetText = row => {
  if (!row.target_type) return '—'
  return `${row.target_type} · ${row.target_id || ''}`
}
/** 后端给的是 ISO 串（2026-10-28T22:33:28.225），165px 列宽里会折成两行把行高撑到 52px；
 *  展示统一成「2026-10-28 22:33:28」单行，15 行就能全放进视口 */
const fmtTs = s => (typeof s === 'string' ? s.replace('T', ' ').slice(0, 19) : s)
const detailText = row => {
  try {
    const o = JSON.parse(row.detail || '{}')
    return Object.entries(o).filter(([k]) => !['docId', 'kbId', 'agentId', 'uid', 'id', 'key'].includes(k))
      .map(([k, v]) => `${k}=${v}`).join('  ')
  } catch (e) { return row.detail || '' }
}
/* ==================== 表体高度实测（表头固定，页内不滚） ====================
   页面骨架为「页内不滚、表体内部滚动」：筛选栏/表头/分页/页脚常驻，
   行放不下时只在表格体里滚（macOS 悬浮滚动条平时不可见）。scroll.y 必须给真实像素，
   不能用 window.innerHeight 减常数去估——筛选栏会折行、链校验横幅时有时无，常数一变就错。 */
const wrapRef = ref(null)
const tableHost = ref(null)
const tableY = ref(240)
let wrapRO = null
const measureTableY = () => {
  const wrap = wrapRef.value
  if (!wrap || !wrap.clientHeight) return
  const cs = getComputedStyle(wrap)
  let used = parseFloat(cs.paddingTop) + parseFloat(cs.paddingBottom)
  // flex gap 不体现在子元素 offsetHeight 里，漏算会让 scroll.y 偏大、分页条被挤出卡片
  const gap = parseFloat(cs.rowGap || cs.gap || 0)
  if (gap > 0) used += gap * Math.max(0, wrap.children.length - 1)
  const card = wrap.querySelector('.al-card')
  for (const el of wrap.children) {
    if (el === card) continue
    const ecs = getComputedStyle(el)
    used += el.offsetHeight + parseFloat(ecs.marginTop) + parseFloat(ecs.marginBottom)
  }
  // 卡片自身描边 + 卡片内、表体之外的固定件（工具栏）；表体由 scroll.y 自行封顶
  if (card) {
    const ccs = getComputedStyle(card)
    used += parseFloat(ccs.borderTopWidth || 0) + parseFloat(ccs.borderBottomWidth || 0)
    for (const el of card.children) {
      if (el === tableHost.value) continue
      const ecs = getComputedStyle(el)
      used += el.offsetHeight + parseFloat(ecs.marginTop) + parseFloat(ecs.marginBottom)
    }
  }
  // 表格内、表体之外的固定件：拆出来的独立表头 + 分页条（margin 不在 offsetHeight 里）
  const head = wrap.querySelector('.ant-table-header')
  if (head) used += head.offsetHeight
  const pag = wrap.querySelector('.ant-table-pagination')
  if (pag) {
    const pcs = getComputedStyle(pag)
    used += pag.offsetHeight + parseFloat(pcs.marginTop) + parseFloat(pcs.marginBottom)
  }
  tableY.value = Math.max(160, wrap.clientHeight - used)
}
onMounted(() => {
  load()
  measureTableY()
  // 宿主是 .app-page 的 flex 直系子元素，高度只随窗口/横幅变化，观察它不会和 scroll.y 相互触发
  wrapRO = new ResizeObserver(measureTableY)
  if (wrapRef.value) wrapRO.observe(wrapRef.value)
})
onUnmounted(() => wrapRO?.disconnect())
// 数据到位后分页条才可能出现、链校验横幅挂出会挤压表格——这两类变化宿主高度不变，得补测一次
watch([rows, chain, dropped], () => nextTick(measureTableY))
</script>

<template>
  <div ref="wrapRef" class="al-wrap">
    <div v-if="chain" class="app-note" :class="{ warn: !chain.ok }">
      <template v-if="chain.ok">哈希链自校验通过：各副本按序号连续、每行哈希与内容一致。</template>
      <template v-else>
        发现 {{ chain.problems.length }} 处断点（前 5 处）：
        <div v-for="(p, i) in chain.problems.slice(0, 5)" :key="i" class="al-problem">
          副本 {{ p.replica }} · seq {{ p.seq }}<template v-if="p.tenant"> · {{ p.tenant }}</template> · {{ p.problem }}
        </div>
      </template>
      <span v-if="Number(chain.droppedSinceStart) > 0" class="al-drop">
        本进程另有 {{ chain.droppedSinceStart }} 条事件因队列满未入库（日志里仍有全文）
      </span>
    </div>
    <div v-else-if="dropped > 0" class="app-note warn">
      本进程有 {{ dropped }} 条审计事件因队列满未入库（日志里仍有全文，可事后回捞）
    </div>
    <div v-else class="app-note al-idle">
      尚未校验哈希链。点击右侧「校验哈希链」逐副本核验，结果会显示在这里。
    </div>

    <section class="app-card al-card">
      <!-- 筛选栏与系统列表工具栏同一风格（app-list-bar 全局类）：控件 small、搜索输入带前缀图标 -->
      <div class="app-list-bar in-card al-toolbar">
        <a-select v-model:value="filters.days" style="width:110px" size="small" @change="search"
                  :options="[{ value: 1, label: '今天' }, { value: 7, label: '近 7 天' }, { value: 30, label: '近 30 天' }, { value: 90, label: '近 90 天' }, { value: 365, label: '近一年' }]" />
        <a-input v-model:value="filters.actor" placeholder="操作人 uid" style="width:150px" size="small" allow-clear @press-enter="search" />
        <a-select v-model:value="filters.action" placeholder="动作" style="width:180px" size="small" allow-clear show-search
                  :options="actions.map(a => ({ value: a, label: a }))" @change="search" />
        <a-input v-model:value="filters.keyword" placeholder="关键字（动作或详情）" style="width:190px" size="small" class="al-search" allow-clear @press-enter="search">
          <template #prefix><SearchOutlined class="al-ic" /></template>
        </a-input>
        <button class="app-link-btn" @click="search">查询</button>
        <span class="app-list-spacer"></span>
        <button class="app-link-btn" :disabled="exporting" @click="doExport">{{ exporting ? '导出中…' : '导出 CSV' }}</button>
        <button class="app-link-btn" :disabled="verifying" @click="doVerify">{{ verifying ? '校验中…' : '校验哈希链' }}</button>
      </div>

      <!-- x/y 同时给：antd 按固定布局拆出独立表头，两份表格列宽才不会漂（同「运行状态」表） -->
      <div ref="tableHost" class="al-table-host">
        <a-table class="al-table" :data-source="rows" :columns="cols" size="small" row-key="id" :loading="loading"
                 :scroll="{ x: 960, y: tableY }"
                 :pagination="{ current: page, pageSize: size, total, onChange: onPage, showSizeChanger: false, showTotal: t => `共 ${t} 条` }"
                 :locale="{ emptyText: '该条件下没有审计事件' }">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'ts'">{{ fmtTs(record.ts) }}</template>
          <template v-else-if="column.key === 'level'">
            <a-tag v-if="record.level === 'warn'" color="orange">需关注</a-tag>
            <span v-else class="al-meta">常规</span>
          </template>
          <template v-else-if="column.key === 'target'">{{ targetText(record) }}</template>
          <template v-else-if="column.key === 'detail'"><span class="al-meta">{{ detailText(record) }}</span></template>
        </template>
        </a-table>
      </div>
    </section>
    <div class="app-list-foot">留存期在「系统设置 → 运维」设置（0 = 永久）</div>
  </div>
</template>

<style scoped>
/* 本页挂在「安全与运维」：
   整块是一张卡片（筛选栏 + 表头 + 表体 + 分页），与巡检/会话列表同一视觉语言。
   页内不滚（overflow hidden）：表格体高度由 tableY 实测封顶，行放不下时在表体内部滚动、
   表头由 antd 固定（scroll.y 拆出的 .ant-table-header，别给 th 加 position:sticky）。 */
.al-wrap {
  flex: 1; min-height: 0; overflow: hidden;
  display: flex; flex-direction: column; gap: 10px;
  padding: 16px 20px;
}
.al-wrap .app-note { margin-bottom: 0; padding: 9px 12px; border-radius: 8px; font-size: 11.5px; }
.al-idle { color: var(--app-text3); }
.al-card { padding: 0; overflow: hidden; flex: 1; min-height: 0; display: flex; flex-direction: column; }
.al-toolbar { padding: 10px 12px; border-bottom: 1px solid var(--app-border); }
/* 原型工具栏控件高 28px；antd small 默认 24px，这里统一抬到 28 保持节奏。
   输入/选择器的上下 padding 必须归零，否则 28px 高度里只剩 18px 内容区，
   文字下半截会被裁掉（affix 包装器内 input 只占 26px，外面还有 2px 上下边框） */
.al-toolbar :deep(.ant-input) { height: 28px; line-height: 26px; padding-top: 0; padding-bottom: 0; }
.al-toolbar :deep(.ant-input-affix-wrapper) { height: 28px; padding-top: 0; padding-bottom: 0; }
.al-toolbar :deep(.ant-input-affix-wrapper > .ant-input) {
  height: 26px; line-height: 26px; padding-top: 0; padding-bottom: 0;
}
.al-toolbar :deep(.ant-select-single .ant-select-selector),
.al-toolbar :deep(.ant-picker) { height: 28px; }
.al-toolbar :deep(.ant-select-single .ant-select-selector .ant-select-selection-item),
.al-toolbar :deep(.ant-select-single .ant-select-selector .ant-select-selection-placeholder) { line-height: 26px; }
.al-table-host { flex: 1; min-height: 0; overflow: hidden; }
.al-search :deep(.ant-input-prefix) { margin-inline-end: 6px; }
.al-ic { color: var(--app-text3); }
.al-meta { color: var(--app-text2); font-size: 10.5px; }
.al-problem { color: var(--app-danger); font-size: 11.5px; }
.al-drop { margin-left: 8px; color: var(--app-warn-text, #a3691b); }

/* Ant 表格默认样式偏松、偏灰；这里收成原型里的紧凑卡片表：浅表头、细行分隔、小字号 */
.al-table :deep(.ant-table) { font-size: 10.5px; background: transparent; }
.al-table :deep(.ant-table-container) { border: none; }
.al-table :deep(.ant-table-thead > tr > th) {
  background: var(--app-panel-2, #fafbfc); color: var(--app-text3);
  font-size: 10.5px; font-weight: 500; padding: 8px 5px;
  border-bottom: 1px solid var(--app-border);
}
.al-table :deep(.ant-table-thead > tr > th::before) { display: none; }
.al-table :deep(.ant-table-tbody > tr > td) {
  padding: 9px 5px; border-bottom: 1px solid var(--app-border); vertical-align: middle;
}
.al-table :deep(.ant-table-thead > tr > th:first-child),
.al-table :deep(.ant-table-tbody > tr > td:first-child) { padding-left: 14px; }
.al-table :deep(.ant-table-thead > tr > th:last-child),
.al-table :deep(.ant-table-tbody > tr > td:last-child) { padding-right: 14px; }
.al-table :deep(.ant-table-tbody > tr:hover > td) { background: var(--app-accent-weak, #eef2f7); }
.al-table :deep(.ant-table-tbody > tr:last-child > td) { border-bottom: none; }
.al-table :deep(.ant-table-pagination.ant-pagination) { margin: 8px 12px; }
.al-table :deep(.ant-tag) { font-size: 10px; line-height: 16px; padding: 0 5px; margin: 0; }
</style>
