<script setup>
/**
 * 统一审计事件（Phase 3 · P11）：查「谁在什么时候对什么做了什么」，
 * 一键导出 CSV 交合规，并校验哈希链有没有被改过。挂在「权限管理 → 审计事件」页签下。
 */
import { ref, onMounted, computed } from 'vue'
import { message } from 'ant-design-vue'
import { listAuditEvents, verifyAuditChain, exportAuditCsv } from '../api'

const rows = ref([])
const actions = ref([])
const total = ref(0)
const loading = ref(false)
const exporting = ref(false)
const verifying = ref(false)
const chain = ref(null)
const dropped = ref(0)
const filters = ref({ days: 7, actor: '', action: '', keyword: '' })
const page = ref(1)
const size = 50

const cols = [
  { title: '时间', dataIndex: 'ts', key: 'ts', width: 165 },
  { title: '级别', dataIndex: 'level', key: 'level', width: 68 },
  { title: '动作', dataIndex: 'action', key: 'action', width: 190, ellipsis: true },
  { title: '操作人', dataIndex: 'actor_uid', key: 'actor_uid', width: 110, ellipsis: true },
  { title: '来源 IP', dataIndex: 'actor_ip', key: 'actor_ip', width: 128, ellipsis: true },
  { title: '对象', key: 'target', width: 190, ellipsis: true },
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
const onPage = (p) => { page.value = p; load() }
const doExport = async () => {
  exporting.value = true
  try {
    await exportAuditCsv(query().replace(/page=\d+&?/, '').replace(/&size=\d+/, ''))
    message.success('已导出 CSV（按当前筛选条件）')
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
const detailText = row => {
  try {
    const o = JSON.parse(row.detail || '{}')
    return Object.entries(o).filter(([k]) => !['docId', 'kbId', 'agentId', 'uid', 'id', 'key'].includes(k))
      .map(([k, v]) => `${k}=${v}`).join('  ')
  } catch (e) { return row.detail || '' }
}
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size)))
onMounted(load)
</script>

<template>
  <div class="al-wrap">
    <div class="al-bar">
      <a-select v-model:value="filters.days" style="width:120px" @change="search"
                :options="[{ value: 1, label: '今天' }, { value: 7, label: '近 7 天' }, { value: 30, label: '近 30 天' }, { value: 90, label: '近 90 天' }, { value: 365, label: '近一年' }]" />
      <a-input v-model:value="filters.actor" placeholder="操作人 uid" style="width:160px" allow-clear @press-enter="search" />
      <a-select v-model:value="filters.action" placeholder="动作" style="width:200px" allow-clear show-search
                :options="actions.map(a => ({ value: a, label: a }))" @change="search" />
      <a-input v-model:value="filters.keyword" placeholder="关键字（动作或详情）" style="width:200px" allow-clear @press-enter="search" />
      <button class="app-link-btn" @click="search">查询</button>
      <span class="al-spacer"></span>
      <button class="app-link-btn" :disabled="exporting" @click="doExport">{{ exporting ? '导出中…' : '导出 CSV' }}</button>
      <button class="app-link-btn" :disabled="verifying" @click="doVerify">{{ verifying ? '校验中…' : '校验哈希链' }}</button>
    </div>

    <div v-if="chain" class="al-chain" :class="{ bad: !chain.ok }">
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
    <div v-else-if="dropped > 0" class="al-chain bad">
      本进程有 {{ dropped }} 条审计事件因队列满未入库（日志里仍有全文，可事后回捞）
    </div>

    <a-table :data-source="rows" :columns="cols" size="small" row-key="id" :loading="loading"
             :pagination="{ current: page, pageSize: size, total, onChange: onPage, showSizeChanger: false }"
             :locale="{ emptyText: '该条件下没有审计事件' }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'level'">
          <a-tag v-if="record.level === 'warn'" color="orange">需关注</a-tag>
          <span v-else class="al-meta">常规</span>
        </template>
        <template v-else-if="column.key === 'target'">{{ targetText(record) }}</template>
        <template v-else-if="column.key === 'detail'"><span class="al-meta">{{ detailText(record) }}</span></template>
      </template>
    </a-table>
    <div class="al-foot">共 {{ total }} 条 · 第 {{ page }} / {{ pageCount }} 页 · 留存期在「系统设置 → 运维」的「审计事件保留天数」里设（默认 365 天，0 = 永久保留）</div>
  </div>
</template>

<style scoped>
.al-wrap { padding: 4px 0 0; }
.al-bar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding-bottom: 10px; }
.al-spacer { flex: 1 1 auto; }
.al-meta { color: var(--app-text-muted, #888); font-size: 12px; }
.al-chain { border: 1px solid var(--app-border, #eee); border-radius: 6px; padding: 8px 10px; margin-bottom: 10px; font-size: 13px; }
.al-chain.bad { border-color: #ffa940; background: rgba(255, 169, 64, .08); }
.al-problem { color: #d4380d; font-size: 12px; }
.al-drop { margin-left: 8px; color: #d46b08; }
.al-foot { padding-top: 8px; color: var(--app-text-muted, #888); font-size: 12px; }
</style>
