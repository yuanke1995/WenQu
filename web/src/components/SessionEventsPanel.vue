<template>
  <!-- 执行过程（事件账本）：按轮列出这一会话跑过什么——工具步与耗时、审批/提问/计划三张卡、
       产物、子智能体编排、停止与失败。这份记录不参与智能体的回答，只服务事后回看与排障。
       PC 用模态框、移动壳用底部 sheet 装载，取数与渲染只有这一份（两端不许各写一遍）。
       展示口径在 chat/processEvents.js：usage/retrieved 是累计快照，合并成一个节点只报最新态；
       工具行按发生顺序编号，「对照正文」靠这个编号与回答里的工具步对上。 -->
  <div v-if="loading" class="ev-tip">正在读取…</div>
  <div v-else-if="err" class="ev-tip">
    {{ err }}
    <button class="ev-retry" type="button" @click="load">重试</button>
  </div>
  <div v-else-if="!turns.length" class="ev-tip">
    <template v-if="turnId">这一轮没有过程记录：账本按天保留，超期会被清理；
      更早的轮次（账本上线之前跑的）本来也没有留痕。</template>
    <template v-else>这个会话还没有过程记录（账本按天保留，超期会被清理）。</template>
  </div>
  <div v-else ref="rootEl">
    <div class="ev-bar">
      <span class="ev-tip">
        <template v-if="turnId">这一轮共 {{ turns[0].count }} 条事件，归并为 {{ turns[0].view.count }} 个节点，按发生顺序。</template>
        <template v-else>共 {{ turns.length }} 轮 · 只列最近若干轮，按发生顺序。用量与检索按最新快照合并。点开每一轮看它跑了哪些步。</template>
      </span>
      <div class="ev-actions">
        <!-- 对照正文只在「看这一轮」时才有意义：会话级列表对着哪一条回答？ -->
        <button v-if="canCompare" class="ev-retry" type="button" @click="compare = !compare">
          {{ compare ? '只看过程' : '对照正文' }}
        </button>
        <button class="ev-retry" type="button" title="把当前过程复制为 Markdown" @click="copyMarkdown">复制</button>
        <button class="ev-retry" type="button" title="下载原始事件 JSON" @click="downloadJson">导出</button>
      </div>
    </div>

    <!-- 会话级总览：最近几轮合起来跑了多少、等了多久、错了几次 -->
    <div v-if="!turnId && summary" class="ev-metrics ev-summary">
      <span class="ev-metric"><span class="k">工具调用</span><span class="v">{{ summary.tools }}</span></span>
      <span class="ev-metric"><span class="k">检索</span><span class="v">{{ summary.words }} 词 / {{ summary.refs }} 段</span></span>
      <span class="ev-metric" :class="{ warn: summary.wait }"><span class="k">等待你</span><span class="v">{{ summary.wait ? toolDuration(summary.wait) : '—' }}</span></span>
      <span class="ev-metric"><span class="k">总用时</span><span class="v">{{ summary.msText }}</span></span>
      <span class="ev-metric" :class="{ err: summary.errors }"><span class="k">失败</span><span class="v">{{ summary.errors }}</span></span>
    </div>

    <div class="ev-split" :class="{ two: compare }">
      <section v-if="compare" class="ev-answer">
        <div v-if="question" class="ev-q">{{ question }}</div>
        <div v-if="thinkingText" class="ev-think">
          <button class="ev-think-head" type="button" @click="thinkOpen = !thinkOpen">
            <caret-right-outlined class="ev-caret" :class="{ open: thinkOpen }" /> 它先想了这些
          </button>
          <AnswerBody v-if="thinkOpen" :content="thinkingText" :msg-index="-1" :viewer="viewer" />
        </div>
        <ol v-if="steps.length" class="ev-steps">
          <li v-for="(s, i) in steps" :key="'s' + i" :data-answer-step="i"
              :class="{ hit: active === i }" @click="focus(i)">
            <span class="ev-no">{{ i + 1 }}</span>
            <span class="ev-step-name">{{ s.name }}</span>
            <span class="ev-step-state" :class="s.status === 'error' ? 'bad' : 'ok'">{{ stepState(s.status) }}</span>
            <span v-if="s.elapsedMs" class="ev-step-ms" :title="s.elapsedMs + ' ms（这一次调用实际跑掉的时长，等人作答不算）'"
            >{{ toolDuration(s.elapsedMs) }}</span>
          </li>
        </ol>
        <AnswerBody class="ev-body" :content="answerText" :images="message.images || []"
                    :sources="message.sources || []" :msg-index="-1" :viewer="viewer"
                    @citation="src => $emit('citation', src)" @preview="(u, i) => $emit('preview', u, i)" />
      </section>

      <section class="ev-ledger">
        <div v-for="t in turns" :key="t.turnId" class="ev-turn">
          <button class="ev-head" type="button" @click="toggleTurn(t)">
            <caret-right-outlined class="ev-caret" :class="{ open: t._open }" />
            <span class="ev-time">{{ fmtEvTime(t.startedAt) }}</span>
            <span class="ev-count" :title="`原始 ${t.count} 条事件`">{{ t.view.count }} 个节点</span>
            <span v-if="t.endedAt > t.startedAt" class="ev-dur"
                  title="这一轮从开跑到收口挂了多久（含你在提问卡、计划卡上读卡答题的时间；每一行的工具时长不含等待）"
            >{{ ((t.endedAt - t.startedAt) / 1000).toFixed(1) }}s</span>
            <!-- 会话级跨轮对比：一根相对最长一轮的迷你条，扫一眼就知道哪轮明显变慢 -->
            <span v-if="!turnId && t.view.durationPct" class="ev-mini"
                  :title="'本轮用时约为最长一轮的 ' + t.view.durationPct + '%'">
              <i :style="{ width: t.view.durationPct + '%' }"></i>
            </span>
            <span class="ev-types">{{ describeTurn(t.view) }}</span>
          </button>
          <!-- 阶段比例条：准备 / 执行 / 等待 / 收尾，按首末事件与 waitedMs 推算 -->
          <div v-if="t.view.phases.length" class="ev-phase">
            <i v-for="p in t.view.phases" :key="p.key" :class="'p-' + p.key"
               :style="{ flexGrow: p.ms }" :title="p.label + ' ' + p.text"></i>
          </div>
          <div v-if="t.view.phases.length && (turnId || t._open)" class="ev-phase-legend">
            <span v-for="p in t.view.phases" :key="p.key"><i :class="'p-' + p.key"></i>{{ p.label }} {{ p.text }}</span>
          </div>
          <div v-if="t._open" class="ev-list">
            <div v-if="t.view.toolsMissing" class="ev-note">
              这一轮有 {{ t.view.toolsMissing }} 个工具步没有账本记录（旧记录），左右编号无法对照。
            </div>
            <!-- 单轮指标条 + 筛选：会话级列表不做筛选，避免跨轮节点混在一起 -->
            <template v-if="turnId">
              <div class="ev-metrics">
                <span class="ev-metric"><span class="k">总用时</span><span class="v">{{ t.view.durationText }}</span></span>
                <span class="ev-metric"><span class="k">工具</span><span class="v">{{ t.view.toolCount }}</span></span>
                <span class="ev-metric"><span class="k">检索</span><span class="v">{{ t.view.retrievalText }}</span></span>
                <span class="ev-metric" :class="{ warn: t.view.waitMs }"><span class="k">等待</span><span class="v">{{ t.view.waitText }}</span></span>
                <span class="ev-metric" :class="{ err: t.view.errorCount }"><span class="k">失败</span><span class="v">{{ t.view.errorCount }}</span></span>
                <span class="ev-metric"><span class="k">用量</span><span class="v">{{ t.view.usageText }}</span></span>
              </div>
              <div class="ev-filter">
                <button v-for="f in filterChips(t)" :key="f.key" type="button" class="ev-fchip"
                        :class="{ on: filter === f.key }" @click="filter = f.key">
                  {{ f.label }}<span class="n">{{ f.count }}</span>
                </button>
                <input v-model="search" class="ev-search" type="search" placeholder="在过程里找…" />
              </div>
            </template>
            <div v-if="turnId && !visibleNodes(t).length && t.view.nodes.length" class="ev-tip">
              没有匹配的过程节点。
            </div>
            <template v-for="n in visibleNodes(t)" :key="n.key">
              <!-- 子智能体运行过程：一行聚合 + 可展开的完整帧流。
                   payload 随行带全量（别的类型只有摘要），展开就是派遣卡上那份同构帧流 -->
              <template v-if="n.kind === 'subagent'">
                <div class="ev-row subagent-row" @click="n.open = !n.open">
                  <span class="ev-type sg-type">
                    <caret-right-outlined class="ev-caret sg-caret" :class="{ open: n.open }" />子智能体
                  </span>
                  <span class="ev-at">{{ fmtEvClock(n.at) }}</span>
                  <span class="ev-sum sg-sum">{{ n.summary }}</span>
                </div>
                <div v-if="n.open" class="sg-body">
                  <SubAgentRunFrames :frames="framesOf(n.event)" :live="false" :max-height="260" />
                </div>
              </template>
              <template v-else>
                <div class="ev-row" :class="[n.tone, { clickable: n.step != null, hit: n.step != null && active === n.step }]"
                     :data-ledger-step="n.step" @click="n.step != null && focus(n.step)">
                  <span class="ev-type" :class="n.tone">
                    <span v-if="n.step != null" class="ev-no">{{ n.step + 1 }}</span>{{ n.label }}
                  </span>
                  <span class="ev-at">{{ fmtEvClock(n.at) }}</span>
                  <span class="ev-sum" :title="n.title + (n.summary ? ' · ' + n.summary : '')">
                    <span class="ev-title">{{ n.title }}</span>
                    <span v-if="n.summary" class="ev-sub"> · {{ n.summary }}</span>
                  </span>
                  <span v-if="n.statusText" class="ev-state" :class="n.statusTone">{{ n.statusText }}</span>
                  <span v-if="n.duration" class="ev-dur"
                        title="这一次调用实际跑掉的时长，等人作答不算">{{ n.duration }}</span>
                  <!-- 快照合并后仍可看逐帧：默认只展示最新态，点开才是那 5 条 usage / 4 条 retrieved -->
                  <button v-if="n.frames && (n.frames.length > 1 || n.kind === 'usage')" type="button" class="ev-snap"
                          title="同一轮的累计快照，默认只展示最新态；点开看逐帧与使用统计入口"
                          @click.stop="n.open = !n.open">{{ n.open ? '收起' : (n.frames.length > 1 ? n.frames.length + ' 次' : '明细') }}</button>
                </div>
                <div v-if="n.open && n.frames" class="ev-frames">
                  <div v-for="(f, fi) in n.frames" :key="fi" class="ev-frame">
                    <span class="ev-frame-at">{{ fmtEvClock(f.at) }}</span>
                    <span class="ev-frame-t">{{ f.text }}</span>
                  </div>
                  <router-link v-if="n.kind === 'usage'" to="/stats" class="ev-stats">查看使用统计 ›</router-link>
                </div>
              </template>
            </template>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { CaretRightOutlined } from '@ant-design/icons-vue'
import { getSessionEvents } from '../api'
import { copyText } from '../utils/clipboard'
// 时长文案与工具卡同一把尺（<1s 用 ms、1~60s 一位小数、更长换成 m/s）：账本里另写一套
// 「180000ms」会让同一个数在两处长得不一样，读的人先要怀疑是不是数错了
import { toolDuration } from '../chat/projections'
import { buildTurnView, describeTurn } from '../chat/processEvents'
import AnswerBody from './AnswerBody.vue'
import SubAgentRunFrames from './SubAgentRunFrames.vue'

// viewer=false（移动壳）时正文里的角标/图片点击往外抛，由页面用它自己的底部抽屉展示——
// 不许在面板里留一个点了没反应的角标
defineEmits(['citation', 'preview'])

const props = defineProps({
  /** 面板是否打开：每次从关到开都重新取数 */
  open: { type: Boolean, default: false },
  sessionId: { type: String, default: '' },
  /** 非空=只看这一轮（消息行的 turnId）；空=按会话分轮列出最近几轮 */
  turnId: { type: String, default: '' },
  turns: { type: Number, default: 8 },
  /**
   * 对照视图的另一半：这一轮那条回答（消息对象，带 content/thinking/toolCalls/sources/images）。
   * 不传就没有「对照正文」这个开关——账本单看仍然照常。
   */
  message: { type: Object, default: null },
  /** 那一轮的问题原文（对照视图的抬头） */
  question: { type: String, default: '' },
  /** 正文里的角标/图片点击要不要走平台共用浮层（移动壳用 false，触屏没有悬浮卡） */
  viewer: { type: Boolean, default: true }
})

const loading = ref(false)
const err = ref('')
const turns = ref([])
const compare = ref(false)
const active = ref(-1)          // 对照视图里当前联动的第 N 步（两侧同一个编号）
const thinkOpen = ref(false)
const rootEl = ref(null)
const summary = ref(null)       // 会话级总览：最近几轮的工具/检索/等待/用时/失败合计
const filter = ref('all')       // 单轮筛选：全部/工具/检索/卡片/产物/失败/等待
const search = ref('')          // 单轮本地搜索：不发请求，只过滤已取回的事件
// 每次取数领一个号：快速切会话/切轮时，晚到的响应不许覆盖当前视图
let seq = 0

const canCompare = computed(() => !!props.turnId && !!props.message)
const answerText = computed(() => (props.message && props.message.content) || '')
const thinkingText = computed(() => (props.message && props.message.thinking) || '')
/** 回答里的工具步（与账本里的工具终态行同序，编号就是对照的锚点） */
const steps = computed(() => {
  const list = props.message && Array.isArray(props.message.toolCalls) ? props.message.toolCalls : []
  return list.filter(t => t && t.name && t.status !== 'start')
})
const stepState = s => (s === 'error' ? '失败' : (s === 'denied' ? '被挡下' : '完成'))

async function focus (i) {
  active.value = active.value === i ? -1 : i
  if (active.value < 0) return
  // 两侧同号联动：点左侧回答步，右侧账本节点滚到可见；点右侧工具行，左侧回答步同样回看
  await nextTick()
  const left = rootEl.value?.querySelector(`[data-answer-step="${i}"]`)
  const right = rootEl.value?.querySelector(`[data-ledger-step="${i}"]`)
  for (const el of [left, right]) {
    if (el && el.scrollIntoView) el.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }
}

async function load () {
  const sid = props.sessionId
  const turn = props.turnId || ''
  if (!sid) { err.value = '还没有选中会话'; turns.value = []; return }
  const my = ++seq
  loading.value = true
  err.value = ''
  filter.value = 'all'
  search.value = ''
  summary.value = null
  turns.value = []
  try {
    const r = await getSessionEvents(sid, props.turns, turn)
    if (my !== seq) return
    const list = (r && r.data && Array.isArray(r.data.turns)) ? r.data.turns : []
    // 按轮进来只有一条，直接摊开——用户点的就是这一轮的过程，不该再多点一下
    if (turn) list.forEach(t => { t._open = true })
    // 展示投影只做一次：合并快照、翻译 JSON、给工具行编号（对照正文的锚点）。
    // 单轮时才传 message——会话级列表没有「哪一条回答」可对照。
    list.forEach(t => { t.view = buildTurnView(t, { message: turn ? props.message : null }) })
    // 会话级跨轮对比：每轮用时相对最长一轮的比例（只画迷你条，不再造一张图表）
    const maxDur = list.reduce((m, t) => Math.max(m, t.view.durationMs), 0)
    list.forEach(t => { t.view.durationPct = maxDur ? Math.round(t.view.durationMs / maxDur * 100) : 0 })
    // 会话级总览：只合计，不把每轮的累计用量再相加（那会重复计上下文）
    const agg = list.reduce((a, t) => {
      a.tools += t.view.toolCount
      a.wait += t.view.waitMs
      a.errors += t.view.errorCount
      a.ms += t.view.durationMs
      a.words += (t.view.retrieval && t.view.retrieval.keywords) || 0
      a.refs += (t.view.retrieval && t.view.retrieval.refs) || 0
      return a
    }, { tools: 0, wait: 0, errors: 0, ms: 0, words: 0, refs: 0 })
    summary.value = { ...agg, turns: list.length, msText: agg.ms ? toolDuration(agg.ms) : '—' }
    turns.value = list
    compare.value = !!turn && !!props.message   // 从气泡点进来的默认就是对照：那才是「为什么这么答」
  } catch (e) {
    if (my !== seq) return
    // 失败必须说出来并给重试：静默空态会被读成「这一轮的过程没记下来」
    err.value = '过程记录读取失败：' + (e?.message || '网络异常')
  } finally {
    if (my === seq) loading.value = false
  }
}

// 三种情况都要重取：① 关→开（每次切入都重拉，手里那份可能已经过时——刚跑完一轮、刚删过本轮，
// 用旧结果会让人以为自己的那一轮没被记录）；② 开着切会话；③ 开着切轮次。
// immediate 是必需的：移动壳的 sheet 用 v-if 渲染内容，面板是在 open 已为 true 时才挂上来的。
watch(() => [props.open, props.sessionId, props.turnId],
  ([o, sid, turn], prev) => {
    if (!o) return
    // prev 在 immediate 首次调用时是 undefined（Vue 不传旧值），别按数组解构
    if (!prev || !prev[0] || prev[1] !== sid || prev[2] !== turn) load()
  }, { immediate: true })

function toggleTurn (t) { t._open = !t._open }

/** subagent 行的完整帧流：payload 随行带全量（service 端 eventItem 对这一类特判） */
function framesOf (e) {
  try {
    const p = typeof e.payload === 'string' ? JSON.parse(e.payload) : e.payload
    return Array.isArray(p?.frames) ? p.frames : []
  } catch { return [] }
}

// ---------- P1：单轮筛选 / 本地搜索 ----------
const FILTERS = [
  { key: 'all', label: '全部' },
  { key: 'tool', label: '工具' },
  { key: 'retr', label: '检索' },
  { key: 'card', label: '卡片' },
  { key: 'art', label: '产物' },
  { key: 'err', label: '失败' },
  { key: 'wait', label: '等待' }
]
function filterChips (t) {
  const c = t.view.counts || {}
  return FILTERS
    .filter(f => f.key === 'all' || (f.key === 'wait' ? c.wait > 0 : (c[f.key] || 0) > 0))
    .map(f => ({ ...f, count: f.key === 'all' ? t.view.nodes.length : (c[f.key] || 0) }))
}
function nodeText (n) {
  return [n.label, n.title, n.summary, ...(n.frames || []).map(f => f.text)]
    .filter(Boolean).join(' ').toLowerCase()
}
/** 单轮才过滤；会话级列表跨轮，筛选项会让人误以为只筛了当前这一轮 */
function visibleNodes (t) {
  if (!props.turnId) return t.view.nodes
  const q = search.value.trim().toLowerCase()
  return t.view.nodes.filter(n => {
    if (filter.value !== 'all' && !(n.filters || []).includes(filter.value)) return false
    return !q || nodeText(n).includes(q)
  })
}

// ---------- P2：复制 Markdown / 导出原始 JSON ----------
function processMarkdown () {
  const lines = []
  for (const t of turns.value) {
    const v = t.view
    const head = [fmtEvTime(t.startedAt), v.durationText, describeTurn(v)].filter(Boolean).join(' · ')
    lines.push(`## 执行过程 · ${head}`)
    if (props.turnId && props.question) lines.push(`> 问：${props.question}`)
    for (const n of v.nodes) {
      const bits = []
      if (n.step != null) bits.push(`${n.step + 1}.`)
      bits.push(`[${n.label}]`, fmtEvClock(n.at), n.title)
      if (n.summary) bits.push(`· ${n.summary}`)
      if (n.statusText) bits.push(`· ${n.statusText}`)
      if (n.duration) bits.push(`· ${n.duration}`)
      lines.push(bits.join(' '))
    }
    lines.push('')
  }
  return lines.join('\n').trim()
}
function copyMarkdown () {
  copyText(processMarkdown(), '过程已复制为 Markdown')
}
function downloadJson () {
  const raw = turns.value.map(t => ({
    turnId: t.turnId,
    startedAt: t.startedAt,
    endedAt: t.endedAt,
    count: t.count,
    events: t.events
  }))
  const data = {
    sessionId: props.sessionId,
    turnId: props.turnId || null,
    exportedAt: Date.now(),
    turns: raw
  }
  const suffix = String(props.turnId || ('session-' + (props.sessionId || 'process')))
    .replace(/[^\w.-]+/g, '_')
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `wenqu-process-${suffix}.json`
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

const pad2 = n => (n < 10 ? '0' + n : '' + n)
function fmtEvTime (ms) {
  if (!ms) return '—'
  const d = new Date(ms)
  return `${d.getMonth() + 1}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}
function fmtEvClock (ms) {
  if (!ms) return ''
  const d = new Date(ms)
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`
}
</script>

<style scoped>
/* 执行过程（事件账本）：一轮一行，展开按序列出事件摘要 */
.ev-bar { display: flex; flex-wrap: wrap; align-items: flex-start; justify-content: space-between; gap: 10px; }
.ev-tip { font-size: 12px; color: var(--app-text-secondary); margin-bottom: 8px; line-height: 1.6; }
.ev-bar .ev-tip { flex: 1; min-width: 0; }
.ev-actions { flex: none; display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px; }
.ev-retry {
  flex: 0 0 auto; margin-left: 0; padding: 2px 10px; font-size: 12px; cursor: pointer;
  border: 1px solid var(--app-border); border-radius: 6px;
  background: transparent; color: var(--app-accent);
}
/* 对照视图：PC 左右两栏（正文 | 账本），窄屏改成上下两块——两栏在触屏上都读不了 */
.ev-split { display: grid; grid-template-columns: minmax(0, 1fr); gap: 12px; }
.ev-split.two { grid-template-columns: minmax(0, 1.05fr) minmax(0, 1fr); }
@media (max-width: 768px) {
  .ev-split.two { grid-template-columns: minmax(0, 1fr); }
}
.ev-answer { min-width: 0; }
.ev-q {
  font-size: 13px; color: var(--app-text); margin-bottom: 8px; padding-bottom: 8px;
  border-bottom: 1px solid var(--app-border); word-break: break-word;
}
.ev-think { margin-bottom: 8px; }
.ev-think-head {
  border: 0; background: transparent; cursor: pointer; font-size: 12px;
  color: var(--app-text-secondary); display: flex; align-items: center; gap: 6px; padding: 0;
}
.ev-body { font-size: 13px; }
.ev-steps { list-style: none; margin: 8px 0; padding: 0; }
.ev-steps li {
  display: flex; align-items: center; gap: 8px; padding: 4px 8px; font-size: 12px;
  border: 1px solid var(--app-border); border-radius: 6px; margin-bottom: 4px; cursor: pointer;
  background: var(--app-bg);
}
.ev-steps li.hit { border-color: var(--app-accent); background: var(--app-accent-weak); }
.ev-step-name { color: var(--app-text); word-break: break-all; }
.ev-step-state { flex: 0 0 auto; }
.ev-step-state.ok { color: var(--app-text-secondary); }
.ev-step-state.bad { color: var(--app-danger); }
.ev-step-ms { margin-left: auto; color: var(--app-text-secondary); font-variant-numeric: tabular-nums; }
.ev-no {
  flex: 0 0 auto; display: inline-block; min-width: 18px; height: 18px; line-height: 18px;
  margin-right: 6px; border-radius: 9px; text-align: center; font-size: 11px;
  background: var(--app-accent-weak); color: var(--app-accent);
}
.ev-turn { border: 1px solid var(--app-border); border-radius: 8px; margin-bottom: 6px; overflow: hidden; }
.ev-head {
  display: flex; align-items: center; gap: 8px; width: 100%; padding: 7px 10px;
  border: 0; background: transparent; cursor: pointer; text-align: left;
  font-size: 12px; color: var(--app-text);
}
.ev-head:hover { background: var(--app-bg); }
.ev-caret { transition: transform .15s; color: var(--app-text-secondary); }
.ev-caret.open { transform: rotate(90deg); }
.ev-time { font-variant-numeric: tabular-nums; }
.ev-count { color: var(--app-text-secondary); }
.ev-dur { color: var(--app-accent); font-variant-numeric: tabular-nums; }
/* 会话级跨轮对比：迷你条只表示「相对最长一轮」，不额外造图表 */
.ev-mini {
  flex: none; width: 42px; height: 4px; margin-left: 2px; border-radius: 2px;
  background: #e9ecf0; overflow: hidden;
}
.ev-mini i { display: block; height: 100%; border-radius: 2px; background: var(--app-accent); }
.ev-types {
  margin-left: auto; min-width: 0; color: var(--app-text-secondary); font-size: 11px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
/* 阶段比例条：准备 / 执行 / 等待 / 收尾 */
.ev-phase { display: flex; height: 5px; margin: 2px 10px 6px; border-radius: 3px; overflow: hidden; background: #eef1f4; }
.ev-phase i { display: block; flex-basis: 0; min-width: 2px; height: 100%; }
.ev-phase .p-prep, .ev-phase-legend .p-prep { background: #b7c4d4; }
.ev-phase .p-gen, .ev-phase-legend .p-gen { background: #8fb0f0; }
.ev-phase .p-wait, .ev-phase-legend .p-wait { background: var(--app-warn-border); }
.ev-phase .p-tail, .ev-phase-legend .p-tail { background: #c9d2de; }
.ev-phase-legend {
  display: flex; flex-wrap: wrap; gap: 4px 12px;
  margin: 0 10px 8px; font-size: 10px; color: var(--app-text-secondary);
}
.ev-phase-legend i {
  display: inline-block; width: 8px; height: 4px; border-radius: 2px;
  margin-right: 4px; vertical-align: 1px;
}
.ev-list { border-top: 1px solid var(--app-border); padding: 4px 10px 8px; }
/* 单轮指标条：总用时 / 工具 / 检索 / 等待 / 失败 / 用量 */
.ev-metrics {
  display: flex; flex-wrap: wrap; gap: 6px 16px;
  margin: 4px 0 8px; padding: 7px 9px;
  border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-bg);
}
.ev-metric { display: inline-flex; flex-direction: column; min-width: 0; }
.ev-metric .k { font-size: 10px; color: var(--app-text-secondary); }
.ev-metric .v {
  font-size: 12px; font-weight: 600; color: var(--app-text);
  font-variant-numeric: tabular-nums; white-space: nowrap;
}
.ev-metric.warn .v { color: var(--app-warn-text); }
.ev-metric.err .v { color: var(--app-danger); }
.ev-summary { margin: 0 0 10px; }
/* 单轮筛选与本地搜索：不发请求，只过滤已取回的事件 */
.ev-filter { display: flex; flex-wrap: wrap; align-items: center; gap: 5px; margin-bottom: 8px; }
.ev-fchip {
  height: 22px; padding: 0 8px; display: inline-flex; align-items: center; gap: 4px;
  border: 1px solid var(--app-border); border-radius: 999px;
  background: #fff; color: var(--app-text-secondary); font-size: 10.5px; cursor: pointer;
}
.ev-fchip.on { border-color: var(--app-accent-border); background: var(--app-accent-weak); color: var(--app-accent); font-weight: 600; }
.ev-fchip .n { font-size: 9.5px; opacity: .8; font-variant-numeric: tabular-nums; }
.ev-search {
  flex: 1 1 120px; min-width: 110px; height: 22px; padding: 0 8px;
  border: 1px solid var(--app-border); border-radius: 6px;
  background: #fff; color: var(--app-text); font-size: 11px; outline: none;
}
.ev-search:focus { border-color: var(--app-accent); }
/* 纵向时间轴：左侧细轨 + 类型色节点，行与行之间连续 */
.ev-row {
  position: relative; display: flex; gap: 8px; align-items: baseline;
  padding: 5px 0 5px 15px; font-size: 12px; border-radius: 6px;
}
.ev-row::before {
  content: ''; position: absolute; left: 5px; top: -4px; bottom: -4px;
  width: 1px; background: var(--app-border);
}
.ev-row::after {
  content: ''; position: absolute; left: 1px; top: 12px;
  width: 7px; height: 7px; border-radius: 50%;
  border: 2px solid var(--app-border); background: #fff;
}
.ev-row.tool::after { border-color: var(--app-accent); }
.ev-row.retr::after { border-color: #7c3aed; }
.ev-row.use::after { border-color: var(--app-warn-text); }
.ev-row.art::after { border-color: #0f766e; }
.ev-row.card::after { border-color: #3b8f4e; }
.ev-row.err::after { border-color: var(--app-danger); background: var(--app-danger-weak); }
.ev-row.warn::after { border-color: var(--app-warn-text); background: var(--app-warn-weak); }
.ev-row.done::after { border-color: var(--app-text-secondary); }
.ev-row.clickable { cursor: pointer; }
.ev-row.clickable:hover { background: var(--app-bg); }
.ev-row.hit { background: var(--app-accent-weak); }
.ev-row.err { background: var(--app-danger-weak); }
.ev-row.warn { background: var(--app-warn-weak); }
.ev-type { flex: 0 0 74px; color: var(--app-accent); }
/* 类型色：工具蓝、检索紫、用量琥珀、卡片绿、产物青、失败红——一眼分清这一行是什么 */
.ev-type.tool { color: var(--app-accent); }
.ev-type.retr { color: #7c3aed; }
.ev-type.use { color: var(--app-warn-text); }
.ev-type.art { color: #0f766e; }
.ev-type.card { color: #3b8f4e; }
.ev-type.sub { color: #7c3aed; }
.ev-type.err { color: var(--app-danger); }
.ev-type.warn { color: var(--app-warn-text); }
.ev-type.done { color: var(--app-text-secondary); }
.ev-at { flex: 0 0 56px; color: var(--app-text-secondary); font-variant-numeric: tabular-nums; }
.ev-sum {
  flex: 1; min-width: 0; color: var(--app-text); word-break: break-all;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.ev-title { color: var(--app-text); }
.ev-sub { color: var(--app-text-secondary); }
.ev-state { flex: 0 0 auto; font-size: 11px; }
.ev-state.err { color: var(--app-danger); }
.ev-state.warn { color: var(--app-warn-text); }
/* 行内单步耗时与折叠头的整轮耗时同名不同义：折叠头保留主色，行内用次要色 */
.ev-row .ev-dur { flex: 0 0 auto; color: var(--app-text-secondary); font-size: 11px; font-variant-numeric: tabular-nums; }
.ev-snap {
  flex: 0 0 auto; border: 1px solid var(--app-border); border-radius: 5px;
  background: transparent; color: var(--app-text-secondary); font-size: 10.5px;
  line-height: 17px; padding: 0 5px; cursor: pointer;
}
.ev-snap:hover { color: var(--app-accent); border-color: var(--app-accent); }
/* 快照逐帧明细：默认收着，点「N 次」才出现；整行宽度，窄栏里也不挤 */
.ev-frames {
  margin: 0 0 6px; padding: 4px 8px 4px 12px; border-left: 2px solid var(--app-border);
  background: var(--app-bg); border-radius: 0 4px 4px 0;
}
.ev-frame { display: flex; gap: 8px; font-size: 10.5px; line-height: 1.7; }
.ev-frame-at { flex: 0 0 56px; color: var(--app-text-secondary); font-variant-numeric: tabular-nums; }
.ev-frame-t { flex: 1; min-width: 0; color: var(--app-text); word-break: break-all; }
.ev-stats {
  display: inline-block; margin-top: 6px; font-size: 11px; color: var(--app-accent);
}
.ev-stats:hover { text-decoration: underline; }
.ev-note {
  margin: 4px 0 6px; padding: 5px 8px; font-size: 11px; line-height: 1.6;
  color: var(--app-warn-text); background: var(--app-warn-weak);
  border: 1px solid var(--app-warn-border); border-radius: 6px;
}
/* 子智能体聚合行：与普通行同形，主色弱底标出「这一行能展开」 */
.subagent-row { cursor: pointer; border-radius: 6px; }
.subagent-row:hover { background: var(--app-accent-weak); }
.sg-type { color: var(--app-accent); display: inline-flex; align-items: center; gap: 2px; }
.sg-caret { font-size: 10px; }
.sg-sum { color: var(--app-text); }
.sg-body { padding: 6px 0 8px 20px; }
</style>
