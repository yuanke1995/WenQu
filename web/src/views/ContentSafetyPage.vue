<script setup>
/**
 * 内容安全规则（Phase 3 · P12）：定「拦什么字」。
 * 闸门本身的开关、外部检查服务与文案在「系统设置 → 内容安全」，这里只管规则清单与试跑。
 * 挂在「权限管理 → 内容安全」页签下（与认证源、审计事件同侧，都是治理数据）。
 */
import { ref, computed, onMounted } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { PlusOutlined, SearchOutlined } from '@ant-design/icons-vue'
import {
  listContentRules, contentSafetyStatus, saveContentRule, deleteContentRule, dryRunContentRule
} from '../api'

const rows = ref([])
const status = ref(null)
const loading = ref(false)
const keyword = ref('')

const STAGE = { input: '只判提问', output: '只判回答', both: '两侧都判' }
const MATCH = { keyword: '关键词', regex: '正则' }
const ACTION = { block: '拦下', warn: '只告警' }

const cols = [
  { title: '规则名', dataIndex: 'name', key: 'name', width: 190, ellipsis: true },
  { title: '类目', dataIndex: 'category', key: 'category', width: 100, ellipsis: true },
  { title: '作用侧', key: 'stage', width: 100 },
  { title: '方式', key: 'match', width: 84 },
  { title: '匹配内容', dataIndex: 'pattern', key: 'pattern', ellipsis: true },
  { title: '命中动作', key: 'action', width: 92 },
  { title: '排序', dataIndex: 'priority', key: 'priority', width: 64 },
  { title: '启用', key: 'enabled', width: 64 },
  { title: '操作', key: 'ops', width: 108 }
]

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return rows.value
  return rows.value.filter(r => [r.name, r.category, r.pattern, r.note]
    .some(v => String(v || '').toLowerCase().includes(k)))
})

const load = async () => {
  loading.value = true
  try {
    const [r, s] = await Promise.all([listContentRules(), contentSafetyStatus()])
    if (!r.success) { message.error(r.msg || '规则加载失败'); return }
    rows.value = r.data || []
    status.value = s && s.success ? s.data : null
  } catch (e) { message.error(e.message || '规则加载失败') } finally { loading.value = false }
}
onMounted(load)

/* ==================== 编辑弹窗 ==================== */
const editOpen = ref(false)
const saving = ref(false)
const form = ref(empty())

function empty () {
  return {
    id: '', name: '', category: '', stage: 'both', matchType: 'keyword', pattern: '',
    action: 'block', caseSensitive: 0, enabled: 1, priority: 100, note: ''
  }
}
const openCreate = () => { form.value = empty(); editOpen.value = true }
const openEdit = row => { form.value = { ...empty(), ...row }; editOpen.value = true }

const save = async () => {
  if (!form.value.pattern.trim()) { message.warning('匹配内容不能为空'); return }
  saving.value = true
  try {
    const body = { ...form.value, name: form.value.name.trim(), id: form.value.id || null }
    const r = await saveContentRule(body)
    if (!r.success) { message.error(r.msg || '保存失败'); return }
    message.success('已保存，最迟 15 秒后其它副本跟着生效')
    editOpen.value = false
    load()
  } catch (e) { message.error(e.message || '保存失败') } finally { saving.value = false }
}

const toggle = async (row, on) => {
  try {
    const r = await saveContentRule({ ...row, enabled: on ? 1 : 0 })
    if (!r.success) { message.error(r.msg || '操作失败'); load(); return }
    row.enabled = on ? 1 : 0
  } catch (e) { message.error(e.message || '操作失败'); load() }
}

const remove = row => {
  Modal.confirm({
    title: `删除规则「${row.name}」？`,
    content: '删除即停止判定。正在跑的那一轮按已经生效的判定走完。',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const r = await deleteContentRule(row.id)
        if (!r.success) { message.error(r.msg || '删除失败'); return }
        message.success('已删除')
        load()
      } catch (e) { message.error(e.message || '删除失败') }
    }
  })
}

/* ==================== 试跑 ==================== */
const runOpen = ref(false)
const running = ref(false)
const runText = ref('')
const runStage = ref('input')
const runHits = ref(null)

const openRun = () => { runHits.value = null; runOpen.value = true }
const doRun = async () => {
  if (!runText.value.trim()) { message.warning('贴一段文本再试跑'); return }
  running.value = true
  try {
    const r = await dryRunContentRule(runText.value, runStage.value)
    if (!r.success) { message.error(r.msg || '试跑失败'); return }
    runHits.value = r.data || { hits: [] }
  } catch (e) { message.error(e.message || '试跑失败') } finally { running.value = false }
}

const broken = computed(() => (status.value && status.value.broken) || [])
const patternHint = computed(() => form.value.matchType === 'regex'
  ? '正则表达式，如 (?<!\\d)\\d{16,19}(?!\\d)（16-19 位连续数字）'
  : '关键词，多个用逗号或换行分隔（同一条规则里任一个命中即算命中）')
</script>

<template>
  <div class="cs-wrap">
    <div class="cs-bar">
      <a-input v-model:value="keyword" allow-clear size="small" class="cs-search" placeholder="搜索规则名 / 匹配内容 / 依据">
        <template #prefix><SearchOutlined class="cs-ic" /></template>
      </a-input>
      <span class="cs-count">{{ filtered.length }} 条</span>
      <span class="cs-spacer"></span>
      <button class="app-link-btn" @click="openRun">试跑规则</button>
      <button class="app-btn cs-new" @click="openCreate"><PlusOutlined /> 新建规则</button>
    </div>

    <div v-if="status" class="cs-state">
      <span>闸门{{ status.enabled ? '开着' : '已关闭' }}</span>
      <span class="cs-dot">·</span>
      <span>此刻生效：提问侧 {{ status.inputRules }} 条，回答侧 {{ status.outputRules }} 条</span>
      <span class="cs-dot">·</span>
      <span>外部检查：{{ status.provider === 'generic' ? (status.provider || '') : '未接' }}
        <template v-if="status.provider === 'generic'">
          （提问侧{{ status.remoteOnInput ? '调用' : '不调用' }}，回答侧{{ status.remoteOnOutput ? '调用' : '不调用' }}）
        </template>
      </span>
      <router-link class="cs-link" to="/settings">闸门参数在系统设置里改</router-link>
    </div>
    <div v-if="broken.length" class="cs-warn">
      <div v-for="(b, i) in broken" :key="i">{{ b }}</div>
    </div>

    <a-table :data-source="filtered" :columns="cols" size="small" row-key="id" :loading="loading"
             :pagination="false" :locale="{ emptyText: '还没有一条规则——不配规则时闸门不会拦任何东西' }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'stage'">
          <span class="cs-meta">{{ STAGE[record.stage] || record.stage }}</span>
        </template>
        <template v-else-if="column.key === 'match'">
          <span class="cs-meta">{{ MATCH[record.matchType] || record.matchType }}<template v-if="record.caseSensitive">（区分大小写）</template></span>
        </template>
        <template v-else-if="column.key === 'pattern'">
          <span class="cs-pattern">{{ record.pattern }}</span>
        </template>
        <template v-else-if="column.key === 'action'">
          <a-tag v-if="record.action === 'block'" color="red">拦下</a-tag>
          <a-tag v-else color="orange">只告警</a-tag>
        </template>
        <template v-else-if="column.key === 'enabled'">
          <a-switch :checked="record.enabled === 1" size="small" @change="v => toggle(record, v)" />
        </template>
        <template v-else-if="column.key === 'ops'">
          <button class="app-link-btn" @click="openEdit(record)">编辑</button>
          <button class="app-link-btn cs-del" @click="remove(record)">删除</button>
        </template>
      </template>
    </a-table>

    <div class="cs-foot">
      出口闸命中只能<b>当场截断</b>回答（已经流到屏幕上的字收不回来），所以新规则建议先选「只告警」跑一段，
      在「权限管理 → 审计事件」里看清命中面，再改「拦下」。每条规则的判定顺序按「排序」升序，先命中的那条说话。
    </div>

    <!-- 编辑弹窗 -->
    <a-modal v-model:open="editOpen" :title="form.id ? '编辑规则' : '新建规则'" width="640px"
             :confirm-loading="saving" ok-text="保存" cancel-text="取消" @ok="save">
      <div class="cs-form">
        <div class="cs-row">
          <label>规则名</label>
          <a-input v-model:value="form.name" placeholder="审计与拦截文案会点名这句话，如「手机号外泄」" />
        </div>
        <div class="cs-row">
          <label>类目</label>
          <a-input v-model:value="form.category" placeholder="同类规则归组，如「个人信息」（不参与判定）" />
        </div>
        <div class="cs-row">
          <label>作用侧</label>
          <a-select v-model:value="form.stage" style="width:200px"
                    :options="[{ value: 'input', label: '只判提问' }, { value: 'output', label: '只判回答' }, { value: 'both', label: '两侧都判' }]" />
          <label class="cs-inline">命中动作</label>
          <a-select v-model:value="form.action" style="width:150px"
                    :options="[{ value: 'block', label: '拦下' }, { value: 'warn', label: '只告警' }]" />
        </div>
        <div class="cs-row">
          <label>方式</label>
          <a-select v-model:value="form.matchType" style="width:200px"
                    :options="[{ value: 'keyword', label: '关键词（字面包含）' }, { value: 'regex', label: '正则表达式' }]" />
          <label class="cs-inline">区分大小写</label>
          <a-switch :checked="form.caseSensitive === 1" size="small"
                    @change="v => form.caseSensitive = v ? 1 : 0" />
        </div>
        <div class="cs-row cs-col">
          <label>匹配内容</label>
          <a-textarea v-model:value="form.pattern" :rows="3" :placeholder="patternHint" />
          <span class="cs-meta">改完可以先用「试跑规则」验一次，别靠真发一句话去猜。</span>
        </div>
        <div class="cs-row">
          <label>排序</label>
          <a-input-number v-model:value="form.priority" :min="0" :max="9999" style="width:120px" />
          <label class="cs-inline">启用</label>
          <a-switch :checked="form.enabled === 1" size="small" @change="v => form.enabled = v ? 1 : 0" />
        </div>
        <div class="cs-row cs-col">
          <label>依据</label>
          <a-input v-model:value="form.note" placeholder="为什么定这条规则（合规检查要看得到出处）" />
        </div>
      </div>
    </a-modal>

    <!-- 试跑弹窗 -->
    <a-modal v-model:open="runOpen" title="试跑规则" width="640px" :footer="null">
      <div class="cs-form">
        <div class="cs-row">
          <label>判哪一侧</label>
          <a-select v-model:value="runStage" style="width:180px"
                    :options="[{ value: 'input', label: '提问' }, { value: 'output', label: '回答' }]" />
          <button class="app-btn cs-new" :disabled="running" @click="doRun">{{ running ? '判中…' : '开始试跑' }}</button>
        </div>
        <div class="cs-row cs-col">
          <label>文本</label>
          <a-textarea v-model:value="runText" :rows="5" placeholder="把准备拦的一段字贴进来" />
        </div>
        <div v-if="runHits" class="cs-result">
          <template v-if="runHits.hits.length === 0">这条文本不会命中任何规则{{ runStage === 'input' ? '（提问会正常发出）' : '（回答会原样给出）' }}。</template>
          <template v-else>
            命中 {{ runHits.hits.length }} 条：
            <div v-for="(h, i) in runHits.hits" :key="i" class="cs-hit">
              <a-tag :color="h.action === 'block' ? 'red' : 'orange'">{{ h.outcome }}</a-tag>
              {{ h.rule }}<span class="cs-meta"> 命中「{{ h.matched }}」</span>
            </div>
          </template>
          <div v-if="runHits.broken && runHits.broken.length" class="cs-warn">
            <div v-for="(b, i) in runHits.broken" :key="'b' + i">{{ b }}</div>
          </div>
        </div>
      </div>
    </a-modal>
  </div>
</template>

<style scoped>
/* 与「审计事件」同一挂载点：根容器是 .app-page 的直系子元素，不当滚动容器就会被裁掉。
   内边距同 .app-page-body，与菜单/接口/角色三个页签对齐。 */
.cs-wrap { flex: 1; min-height: 0; overflow-y: auto; padding: 16px 20px; }
.cs-bar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding-bottom: 10px; }
.cs-search { width: 260px; }
.cs-ic { color: var(--app-text-muted, #999); }
.cs-count { color: var(--app-text-muted, #888); font-size: 12px; }
.cs-spacer { flex: 1 1 auto; }
.cs-state { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; font-size: 13px;
            border: 1px solid var(--app-border, #eee); border-radius: 6px; padding: 8px 10px; margin-bottom: 10px; }
.cs-dot { color: var(--app-text-muted, #bbb); }
.cs-link { margin-left: auto; font-size: 12px; }
.cs-warn { border: 1px solid #ffa940; background: rgba(255, 169, 64, .08); border-radius: 6px;
           padding: 8px 10px; margin-bottom: 10px; font-size: 12px; color: #d46b08; }
.cs-meta { color: var(--app-text-muted, #888); font-size: 12px; }
.cs-pattern { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.cs-del { color: #d4380d; }
.cs-foot { padding-top: 8px; color: var(--app-text-muted, #888); font-size: 12px; }
.cs-form { display: flex; flex-direction: column; gap: 12px; }
.cs-row { display: flex; align-items: center; gap: 10px; }
.cs-row > label { width: 72px; flex: none; font-size: 13px; color: var(--app-text-muted, #666); }
.cs-col { flex-direction: column; align-items: stretch; gap: 4px; }
.cs-col > label { width: auto; }
.cs-inline { width: auto !important; }
.cs-result { border-top: 1px solid var(--app-border, #eee); padding-top: 10px; font-size: 13px; }
.cs-hit { padding: 4px 0; }
</style>
