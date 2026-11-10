<template>
  <!-- 技能详情抽屉（效果图 SCENE 2）：正文 / 附属文件 / 使用数据 / 版本 / 测试 五个 Tab。
       头部常驻图标 + 名称 + 状态 + 开关 + ⋯ 菜单；编辑/复制/上架/删除等动作上抛给列表页统一处理。 -->
  <a-drawer :open="open" placement="right" :width="760" :closable="false"
            :body-style="{ padding: '0', height: '100%', display: 'flex', flexDirection: 'column' }"
            @close="close">
    <div v-if="skill" class="sk-dr">
      <!-- 头部 -->
      <div class="dr-head">
        <span class="cat-ic" :class="'cat-' + catKey"><component :is="catIcon" /></span>
        <div class="dr-tt">
          <div class="dr-name" :title="skill.name">{{ skill.name }}</div>
          <div class="dr-sub">{{ headSub }}</div>
        </div>
        <span class="app-pill" :class="pillCls">{{ pillText }}</span>
        <span class="dr-sp"></span>
        <a-switch v-if="skill.disableable" size="small" :checked="!skill.disabled" :loading="toggling"
                  @change="onToggle" />
        <a-dropdown :trigger="['click']" placement="bottomRight">
          <button class="dots-btn" aria-label="更多操作"><more-outlined /></button>
          <template #overlay>
            <a-menu @click="onMenu">
              <a-menu-item v-if="skill.editable" key="edit"><edit-outlined /> 编辑</a-menu-item>
              <a-menu-item key="copy"><copy-outlined /> 复制为新技能</a-menu-item>
              <a-menu-item v-if="skill.editable" key="versions"><history-outlined /> 版本历史</a-menu-item>
              <a-menu-item v-if="skill.deletable" key="download"><download-outlined /> 下载 zip</a-menu-item>
              <a-menu-item v-if="skill.editable" key="publish"><shop-outlined /> 上架到市场</a-menu-item>
              <template v-if="skill.deletable">
                <a-menu-divider />
                <a-menu-item key="remove" danger><delete-outlined /> 删除</a-menu-item>
              </template>
            </a-menu>
          </template>
        </a-dropdown>
        <button class="app-icon-btn" aria-label="关闭" @click="close"><close-outlined /></button>
      </div>

      <a-tabs v-model:activeKey="tab" class="dr-tabs">
        <!-- 正文 -->
        <a-tab-pane key="content" tab="正文">
          <div class="dr-view-tabs">
            <button class="app-link-btn" :class="{ on: !rawView }" @click="rawView = false">预览</button>
            <button class="app-link-btn" :class="{ on: rawView }" @click="rawView = true">原文</button>
          </div>
          <a-spin v-if="detailLoading" style="display:block;margin:36px auto" />
          <template v-else>
            <!-- eslint-disable-next-line vue/no-v-html -->
            <div v-if="!rawView" class="md dr-body-block" v-html="renderMd(detail.content || '（这条技能没有正文）')" />
            <pre v-else class="dr-raw">{{ detail.raw || detail.content || '' }}</pre>
          </template>
        </a-tab-pane>

        <!-- 附属文件 -->
        <a-tab-pane key="files">
          <template #tab>附属文件<span v-if="files.length" class="dr-tab-n">{{ files.length }}</span></template>
          <a-spin v-if="detailLoading" style="display:block;margin:36px auto" />
          <template v-else>
            <div v-if="!files.length" class="dr-empty">这条技能只有 SKILL.md，没有附属文件。</div>
            <template v-else>
              <button v-for="f in files" :key="f.path" class="dr-file-row" type="button"
                      :class="{ on: fileView && fileView.path === f.path }" @click="openFile(f)">
                <file-text-outlined class="dr-file-ic" />
                <span class="dr-file-path">{{ f.path }}</span>
                <span class="dr-file-size">{{ fmtSize(f.size) }}</span>
              </button>
              <div v-if="fileView" class="dr-file-prev">
                <div class="dr-file-prev-t">{{ fileView.path }}</div>
                <a-spin v-if="fileLoading" style="display:block;margin:20px auto" />
                <pre v-else class="dr-raw">{{ fileView.content }}</pre>
              </div>
            </template>
          </template>
        </a-tab-pane>

        <!-- 使用数据 -->
        <a-tab-pane key="usage" tab="使用数据">
          <a-spin v-if="statsLoading && !stats" style="display:block;margin:36px auto" />
          <template v-else-if="stats">
            <div class="dr-tiles">
              <div class="dr-tile"><div class="n">{{ stats.reads30d || 0 }}<small>次</small></div><div class="l">30 天被读取</div></div>
              <div class="dr-tile"><div class="n">{{ stats.reads7d || 0 }}<small>次</small></div><div class="l">7 天被读取</div></div>
              <div class="dr-tile"><div class="n" :class="{ warn: !stats.lastUsedAt }">{{ lastUsedText }}</div><div class="l">最近触发</div></div>
              <div class="dr-tile"><div class="n">{{ activeDays }}<small>天</small></div><div class="l">30 天内有调用</div></div>
            </div>

            <div v-if="trend" class="dr-chart">
              <div class="dr-chart-t">30 天调用趋势 <span>{{ trend.peakText }}</span></div>
              <svg :viewBox="'0 0 700 150'" class="dr-chart-svg" preserveAspectRatio="none">
                <g stroke="var(--app-border)" stroke-width="1">
                  <line x1="0" y1="30" x2="700" y2="30" /><line x1="0" y1="70" x2="700" y2="70" /><line x1="0" y1="110" x2="700" y2="110" />
                </g>
                <path :d="trend.area" fill="rgba(46,107,230,.10)" />
                <path :d="trend.line" fill="none" stroke="var(--app-accent)" stroke-width="2" />
              </svg>
              <div class="dr-chart-x"><span>{{ trend.first }}</span><span>{{ trend.last }}</span></div>
            </div>

            <div v-if="recent.length" class="dr-trig">
              <div class="dr-trig-t">最近触发（打开可回到原会话，看模型实际怎么用的）</div>
              <div v-for="r in recent" :key="r.sessionId + ':' + r.at" class="dr-trig-row">
                <message-outlined class="dr-trig-ic" />
                <span class="q" :title="r.title">{{ r.title || '（无标题会话）' }}</span>
                <span class="tm">{{ relTime(r.at) }}</span>
                <button class="go app-link-btn" @click="openSession(r.sessionId)">打开 →</button>
              </div>
            </div>
            <div v-else class="dr-empty">30 天内没有被任何会话触发过。</div>

            <div v-if="!skill.description" class="dr-tip">
              <warning-outlined />
              <div><b>缺描述：</b>模型靠描述判断要不要读取这条技能；没有描述，它基本不会被主动用到。建议到「正文」右侧 ⋯ 菜单里编辑补上触发场景。</div>
            </div>
            <div v-else-if="(stats.reads30d || 0) === 0 && !skill.disabled" class="dr-tip">
              <warning-outlined />
              <div><b>30 天零调用：</b>技能生效中但没有被读取过。多半是描述太宽泛——把「什么场景、什么关键词出现时该用它」写进描述，再去对话里点名试一次验证。</div>
            </div>
          </template>
          <div v-else class="dr-empty">使用数据读取失败，关闭抽屉后重新打开重试。</div>
        </a-tab-pane>

        <!-- 版本 -->
        <a-tab-pane key="versions">
          <template #tab>版本<span v-if="versions && versions.length" class="dr-tab-n">{{ versions.length }}</span></template>
          <div v-if="!skill.editable" class="dr-empty">版本历史只对「我的技能」开放：编辑或覆盖前会自动留快照，可一键回滚。</div>
          <template v-else>
            <a-spin v-if="versionsLoading && !versions" style="display:block;margin:36px auto" />
            <div v-else-if="!versions || !versions.length" class="dr-empty">还没有版本快照——每次保存编辑都会自动留一份 SKILL.md，最多保留 20 份。</div>
            <div v-for="v in versions" :key="v.ts" class="dr-ver-row">
              <div class="dr-ver-main">
                <div class="dr-ver-t">{{ fmtDateTime(v.ts) }}</div>
                <div class="dr-ver-d">{{ v.description || '（编辑保存）' }}<span v-if="v.category" class="dr-ver-cat">{{ v.category }}</span><span class="dr-ver-size">{{ fmtSize(v.size) }}</span></div>
              </div>
              <a-popconfirm title="回滚到这个版本？当前内容会先自动留一份快照" ok-text="回滚" cancel-text="取消"
                            @confirm="rollback(v.ts)">
                <button class="app-btn ghost small" :disabled="rolling">回滚到此版本</button>
              </a-popconfirm>
            </div>
          </template>
        </a-tab-pane>

        <!-- 测试 -->
        <a-tab-pane key="test" tab="测试">
          <div class="dr-test-note">
            技能由模型按描述<b>按需读取</b>，本页无法替你「跑一遍」。最直接的验证方式：去对话里点名这条技能——
            点名不受停用限制，能立刻看到模型按技能做法产出的效果，再回来改描述、改正文。
          </div>
          <a-input v-model:value="testQ" placeholder="想验证的问题（可选，带过去当首句）" :maxlength="500" />
          <div class="dr-test-ops">
            <button class="app-btn" @click="goTry"><play-circle-outlined /> 去对话点名试用</button>
          </div>
        </a-tab-pane>
      </a-tabs>
    </div>
  </a-drawer>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  CloseOutlined, CodeOutlined, CopyOutlined, DeleteOutlined, DownloadOutlined, EditOutlined,
  FileTextOutlined, HistoryOutlined, MessageOutlined, MoreOutlined, PlayCircleOutlined,
  ShopOutlined, SkinOutlined, TableOutlined, ToolOutlined, WarningOutlined
} from '@ant-design/icons-vue'
import { getSkillDetail, getSkillFile, getSkillStats, listSkillVersions, rollbackSkill, setSkillDisabled } from '../api'
import { renderMd } from '../utils/markdown'

const props = defineProps({
  open: Boolean,
  skill: { type: Object, default: null },
  initialTab: { type: String, default: 'content' }
})
const emit = defineEmits(['update:open', 'changed', 'edit', 'copy', 'publish', 'download', 'remove'])

const router = useRouter()
const SOURCE_LABEL = { user: '我的技能', builtin: '内置技能', workspace: '工作区技能' }

const tab = ref('content')
const rawView = ref(false)
const detail = ref({})
const detailLoading = ref(false)
const toggling = ref(false)
const stats = ref(null)
const statsLoading = ref(false)
const versions = ref(null)
const versionsLoading = ref(false)
const rolling = ref(false)
const fileView = ref(null)
const fileLoading = ref(false)
const testQ = ref('')

const close = () => emit('update:open', false)

// 打开（或换技能）时重置并拉正文；数据/版本 Tab 懒加载
watch(() => [props.open, props.skill && props.skill.name], ([o, n]) => {
  if (!o || !n) return
  tab.value = props.initialTab || 'content'
  rawView.value = false
  fileView.value = null
  testQ.value = ''
  detail.value = { ...props.skill }
  stats.value = null
  versions.value = null
  loadDetail()
  if (tab.value === 'usage') loadStats()
  if (tab.value === 'versions') loadVersions()
}, { immediate: true })

watch(() => props.initialTab, t => { if (props.open && t) tab.value = t })
watch(tab, t => {
  if (t === 'usage' && !stats.value && !statsLoading.value) loadStats()
  if (t === 'versions' && !versions.value && !versionsLoading.value && props.skill && props.skill.editable) loadVersions()
})

const errText = e => (e && e.message) || String(e)

const loadDetail = async () => {
  detailLoading.value = true
  try {
    const r = await getSkillDetail(props.skill.name)
    if (!r || !r.success) throw new Error((r && r.msg) || '读取失败')
    detail.value = r.data || detail.value
  } catch (e) {
    message.error('读取技能失败：' + errText(e))
  } finally {
    detailLoading.value = false
  }
}

const loadStats = async () => {
  statsLoading.value = true
  try {
    const r = await getSkillStats(props.skill.name)
    if (!r || !r.success) throw new Error((r && r.msg) || '读取失败')
    stats.value = r.data || {}
  } catch (e) {
    message.error('读取使用数据失败：' + errText(e))
  } finally {
    statsLoading.value = false
  }
}

const loadVersions = async () => {
  versionsLoading.value = true
  try {
    const r = await listSkillVersions(props.skill.name)
    if (!r || !r.success) throw new Error((r && r.msg) || '读取失败')
    versions.value = Array.isArray(r.data?.versions) ? r.data.versions : []
  } catch (e) {
    message.error('读取版本历史失败：' + errText(e))
  } finally {
    versionsLoading.value = false
  }
}

const rollback = async ts => {
  rolling.value = true
  try {
    const r = await rollbackSkill(props.skill.name, ts)
    if (!r || !r.success) throw new Error((r && r.msg) || '回滚失败')
    message.success('已回滚，下一轮生效')
    await loadVersions()
    emit('changed')
  } catch (e) {
    message.error('回滚失败：' + errText(e))
  } finally {
    rolling.value = false
  }
}

const onToggle = async checked => {
  toggling.value = true
  try {
    const r = await setSkillDisabled(props.skill.name, !checked)
    if (!r || !r.success) throw new Error((r && r.msg) || '操作失败')
    if (!checked) message.success(r.msg || '已停用')
    emit('changed')
  } catch (e) {
    message.error('操作失败：' + errText(e))
  } finally {
    toggling.value = false
  }
}

const onMenu = ({ key }) => {
  if (key === 'versions') { tab.value = 'versions'; return }
  emit(key, props.skill)
}

const openFile = async f => {
  if (f.binary) { message.info('二进制文件不支持在线预览，可下载技能包查看'); return }
  fileView.value = { path: f.path, content: '' }
  fileLoading.value = true
  try {
    const r = await getSkillFile(props.skill.name, f.path)
    if (!r || !r.success) throw new Error((r && r.msg) || '读取失败')
    fileView.value = { path: f.path, content: r.data.content || '' }
  } catch (e) {
    fileView.value = { path: f.path, content: '读取失败：' + errText(e) }
  } finally {
    fileLoading.value = false
  }
}

const openSession = sid => {
  if (!sid) return
  router.push({ path: '/chat', query: { sid } })
}

const goTry = () => {
  const q = { skill: props.skill.name }
  if (testQ.value.trim()) q.q = testQ.value.trim()
  router.push({ path: '/chat', query: q })
  close()
}

// ---- 展示辅助 ----
const CAT_ICONS = { form: TableOutlined, doc: FileTextOutlined, code: CodeOutlined, design: SkinOutlined, general: ToolOutlined }
const catKey = computed(() => {
  const c = detail.value.category || (props.skill && props.skill.category) || ''
  if (c.includes('表单') || c.includes('表格')) return 'form'
  if (c.includes('文档') || c.includes('写作')) return 'doc'
  if (c.includes('代码') || c.includes('审查')) return 'code'
  if (c.includes('设计') || c.includes('走查')) return 'design'
  return 'general'
})
const catIcon = computed(() => CAT_ICONS[catKey.value])

const pillCls = computed(() => {
  const s = props.skill || {}
  if (s.disabled) return s.platformDisabled ? 'warn' : 'muted'
  return 'ok'
})
const pillText = computed(() => {
  const s = props.skill || {}
  if (s.disabled) return s.platformDisabled ? '平台停用' : '我停用了'
  return '生效中'
})

const files = computed(() => (detail.value.files || []).filter(f => f.path !== 'SKILL.md'))
const headSub = computed(() => {
  const d = detail.value
  const bits = [SOURCE_LABEL[d.source] || '技能']
  if (d.size) bits.push(fmtSize(d.size))
  if (d.fileCount > 1) bits.push(d.fileCount + ' 个文件')
  if (d.updatedAt) bits.push('更新于 ' + fmtDate(d.updatedAt))
  return bits.join(' · ')
})

const recent = computed(() => (stats.value && stats.value.recent) || [])
const activeDays = computed(() => ((stats.value && stats.value.series30d) || []).filter(d => d.count > 0).length)
const lastUsedText = computed(() => {
  const ts = stats.value && stats.value.lastUsedAt
  if (!ts) return '从未'
  return relTime(ts)
})

const trend = computed(() => {
  const s = (stats.value && stats.value.series30d) || []
  if (s.length < 2) return null
  const w = 700, h = 150, pad = 6, base = h - 22
  const max = Math.max(1, ...s.map(d => d.count || 0))
  const x = i => pad + (i * (w - pad * 2)) / (s.length - 1)
  const y = v => base - (v / max) * (base - 18)
  const line = s.map((d, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(d.count || 0).toFixed(1)}`).join(' ')
  const area = `${line} L${x(s.length - 1).toFixed(1)},${base} L${x(0).toFixed(1)},${base} Z`
  let peak = 0, peakIdx = 0
  s.forEach((d, i) => { if ((d.count || 0) > peak) { peak = d.count; peakIdx = i } })
  return {
    line, area,
    first: (s[0].date || '').slice(5),
    last: (s[s.length - 1].date || '').slice(5),
    peakText: peak > 0 ? `按日 · 峰值 ${String((s[peakIdx].date || '').slice(5))}（${peak} 次）` : '按日 · 30 天内无调用'
  }
})

function fmtSize(n) {
  if (!n) return ''
  if (n >= 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + 'MB'
  if (n >= 1024) return Math.round(n / 1024) + 'KB'
  return n + 'B'
}
function fmtDate(ts) { try { return new Date(ts).toLocaleDateString() } catch (e) { return '' } }
function fmtDateTime(ts) { try { return new Date(ts).toLocaleString() } catch (e) { return '' } }
function relTime(ts) {
  if (!ts) return '从未'
  const m = Math.floor((Date.now() - ts) / 60000)
  if (m < 1) return '刚刚'
  if (m < 60) return m + ' 分钟前'
  const h = Math.floor(m / 60)
  if (h < 24) return h + ' 小时前'
  const d = Math.floor(h / 24)
  if (d < 30) return d + ' 天前'
  return Math.floor(d / 30) + ' 个月前'
}
</script>

<style scoped>
/* 类别五色：与效果图一致，暗色下弱底改透明度保证可读 */
.sk-dr {
  --c-form: #2e6be6;   --c-form-bg: #eaf0fd;
  --c-doc: #3b8f4e;    --c-doc-bg: #eaf5ec;
  --c-code: #7c5cd6;   --c-code-bg: #f0ecfb;
  --c-design: #c77c14; --c-design-bg: #faf3e6;
  --c-general: #0f8a8a;--c-general-bg: #e6f4f4;
  display: flex; flex-direction: column; height: 100%; min-width: 0;
}
html[data-theme='dark'] .sk-dr {
  --c-form-bg: rgba(46, 107, 230, .18);
  --c-doc-bg: rgba(59, 143, 78, .18);
  --c-code-bg: rgba(124, 92, 214, .2);
  --c-design-bg: rgba(199, 124, 20, .18);
  --c-general-bg: rgba(15, 138, 138, .18);
}

.dr-head { flex: none; display: flex; align-items: center; gap: 10px; padding: 14px 20px 12px; border-bottom: 1px solid var(--app-border); }
.cat-ic { width: 34px; height: 34px; border-radius: 8px; flex: none; display: inline-flex; align-items: center; justify-content: center; font-size: 16px; }
.cat-form { background: var(--c-form-bg); color: var(--c-form); }
.cat-doc { background: var(--c-doc-bg); color: var(--c-doc); }
.cat-code { background: var(--c-code-bg); color: var(--c-code); }
.cat-design { background: var(--c-design-bg); color: var(--c-design); }
.cat-general { background: var(--c-general-bg); color: var(--c-general); }
.dr-tt { min-width: 0; }
.dr-name { font-size: 15px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 380px; }
.dr-sub { font-size: 11px; color: var(--app-text3); margin-top: 2px; }
.dr-sp { flex: 1; }
.dots-btn { width: 26px; height: 26px; border-radius: 6px; border: 1px solid var(--app-border-strong); background: var(--app-panel);
  display: inline-flex; align-items: center; justify-content: center; color: var(--app-text2); cursor: pointer; font-size: 13px; }
.dots-btn:hover { border-color: var(--app-accent-border); color: var(--app-accent); }

.dr-tabs { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.dr-tabs :deep(.ant-tabs-nav) { margin: 0; padding: 0 20px; flex: none; }
.dr-tabs :deep(.ant-tabs-content-holder) { flex: 1; min-height: 0; overflow-y: auto; }
.dr-tabs :deep(.ant-tabs-content), .dr-tabs :deep(.ant-tabs-tabpane) { height: 100%; }
.dr-tabs :deep(.ant-tabs-tabpane) { padding: 14px 20px 20px; }
.dr-tab-n { font-size: 10px; color: var(--app-text3); background: var(--app-panel-2); border-radius: 999px; padding: 1px 6px; margin-left: 4px; }

.dr-view-tabs { display: flex; gap: 12px; border-bottom: 1px solid var(--app-border); padding-bottom: 6px; margin-bottom: 10px; }
.dr-view-tabs .app-link-btn { color: var(--app-text3); }
.dr-view-tabs .app-link-btn.on { color: var(--app-accent); font-weight: 600; }
.dr-body-block { border: 1px solid var(--app-border); border-radius: 8px; padding: 12px 16px; }
.dr-raw { border: 1px solid var(--app-border); border-radius: 8px; padding: 12px 14px; background: var(--app-code-bg);
  font-family: var(--app-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; margin: 0; }

.dr-empty { font-size: 12px; color: var(--app-text3); line-height: 1.8; padding: 18px 2px; }

.dr-file-row { display: flex; align-items: center; gap: 10px; width: 100%; border: 1px solid var(--app-border); background: var(--app-panel);
  border-radius: 8px; padding: 8px 12px; margin-bottom: 6px; cursor: pointer; font-size: 12px; color: var(--app-text2); text-align: left; }
.dr-file-row:hover { border-color: var(--app-accent-border); color: var(--app-accent); }
.dr-file-row.on { border-color: var(--app-accent-border); background: var(--app-accent-weak); }
.dr-file-ic { color: var(--app-text3); flex: none; }
.dr-file-path { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dr-file-size { flex: none; color: var(--app-text3); font-size: 11px; }
.dr-file-prev { margin-top: 12px; }
.dr-file-prev-t { font-size: 12px; font-weight: 600; color: var(--app-text2); margin-bottom: 6px; }

.dr-tiles { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.dr-tile { border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel-2); padding: 10px 12px; }
.dr-tile .n { font-size: 18px; font-weight: 650; }
.dr-tile .n small { font-size: 11px; font-weight: 400; color: var(--app-text3); margin-left: 2px; }
.dr-tile .n.warn { color: var(--app-warn-text); font-size: 14px; }
.dr-tile .l { font-size: 11px; color: var(--app-text3); margin-top: 3px; }

.dr-chart { border: 1px solid var(--app-border); border-radius: 8px; margin-top: 12px; padding: 12px 14px 10px; }
.dr-chart-t { font-size: 12px; font-weight: 600; color: var(--app-text2); display: flex; align-items: center; gap: 8px; }
.dr-chart-t span { font-weight: 400; font-size: 11px; color: var(--app-text3); }
.dr-chart-svg { display: block; width: 100%; height: 150px; margin-top: 8px; }
.dr-chart-x { display: flex; justify-content: space-between; font-size: 10px; color: var(--app-text3); margin-top: 2px; }

.dr-trig { margin-top: 14px; }
.dr-trig-t { font-size: 12px; font-weight: 600; color: var(--app-text2); margin-bottom: 6px; }
.dr-trig-row { display: flex; align-items: center; gap: 10px; padding: 8px 12px; border: 1px solid var(--app-border);
  border-radius: 8px; margin-bottom: 6px; background: var(--app-panel); }
.dr-trig-ic { color: var(--app-text3); flex: none; font-size: 13px; }
.dr-trig-row .q { flex: 1; min-width: 0; font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.dr-trig-row .tm { font-size: 11px; color: var(--app-text3); flex: none; }
.dr-trig-row .go { flex: none; }

.dr-tip { margin-top: 14px; border: 1px solid var(--app-warn-border); background: var(--app-warn-weak); border-radius: 8px;
  padding: 10px 12px; font-size: 12px; color: var(--app-warn-text); line-height: 1.7; display: flex; gap: 8px; }
.dr-tip :deep(svg) { width: 14px; height: 14px; flex: none; margin-top: 3px; color: var(--app-warn); }
.dr-tip b { font-weight: 600; }

.dr-ver-row { display: flex; align-items: center; gap: 12px; padding: 10px 12px; border: 1px solid var(--app-border);
  border-radius: 8px; margin-bottom: 6px; background: var(--app-panel); }
.dr-ver-main { flex: 1; min-width: 0; }
.dr-ver-t { font-size: 12px; font-weight: 600; color: var(--app-text); }
.dr-ver-d { font-size: 11px; color: var(--app-text3); margin-top: 2px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.dr-ver-cat { background: var(--app-panel-2); border: 1px solid var(--app-border); border-radius: 4px; padding: 0 6px; }
.dr-ver-size { margin-left: auto; }

.dr-test-note { font-size: 12px; color: var(--app-text2); line-height: 1.8; border: 1px solid var(--app-border);
  background: var(--app-panel-2); border-radius: 8px; padding: 12px 14px; margin-bottom: 14px; }
.dr-test-note b { color: var(--app-text); }
.dr-test-ops { display: flex; justify-content: flex-end; margin-top: 14px; }

@media (max-width: 768px) {
  .dr-tiles { grid-template-columns: repeat(2, 1fr); }
}
</style>
