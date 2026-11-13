<template>
  <!-- 执行计划卡（人在回路）：只读展示一轮计划的生命周期——
       流式产出 drafting → 待确认 pending → 已批准 / 未批准 / 已被取代。
       卡上只放「目标 + 三步 + 卡脚」，完整正文、版本轨与意见都在 PlanViewer 抽屉里；
       批准后转执行态：进度条 + 逐步骤行（完成 / 进行 / 等待 / 失败），每步只报产物与耗时；
       旧计划（已批准/已被取代/未批准）默认收成一行，点开才铺三段式（按会话记住）。
       裁决动作不在这里：打字框整块顶替成确认栏（PC 的 planp-*、移动壳的 m-planp-*）。 -->
  <div ref="rootEl" class="plan-card" :class="['plan-' + card.status, { 'is-exec': !!progress, 'is-done': execDone, 'is-failed': execFailed }]">
    <!-- 旧计划：一行摘要。点整行展开 / 收起，不再用覆盖正文的绝对定位按钮。
         已重新规划的旧卡在行尾多一颗「查看新一轮」直达；不把两个按钮塞进同一个 button。 -->
    <div v-if="!expanded && !isLive" class="pc-line-wrap">
      <button type="button" class="pc-line" :title="lineTitle" @click="toggleLine">
        <ordered-list-outlined class="pc-ic" />
        <span class="pc-line-t">{{ deep ? '深度执行' : '执行计划' }}</span>
        <span class="pc-ver">v{{ version }}</span>
        <span class="pc-state" :class="'st-' + stateTone">{{ stateText }}</span>
        <span class="pc-line-meta">
          <template v-if="progress">{{ progress.done }}/{{ progress.total }}{{ progress.running ? ' 进行中' : (progress.done >= progress.total ? ' 已完成' : ' 未完成') }}<template v-if="progress.failed"> · {{ progress.failed }} 失败</template></template>
          <template v-else>{{ stepCount }} 步</template>
        </span>
        <caret-right-outlined class="pc-line-caret" />
      </button>
      <button v-if="replanned && card.replannedBy" type="button" class="pc-line-jump"
              title="跳到按新意见生成的那一版计划" aria-label="查看新一轮"
              @click="emit('locate-replanned')">新一轮 ↗</button>
    </div>

    <template v-else>
      <div class="pc-head">
        <ordered-list-outlined class="pc-ic" />
        <span class="pc-title">{{ deep ? '深度执行' : '执行计划' }}</span>
        <span v-if="chain.length > 1" class="pc-vers">
          <template v-for="(vi, xi) in shownVers" :key="vi">
            <button type="button" class="pc-vbtn" :class="{ on: vi === chain.length - 1 }"
                    :title="'查看第 ' + (vi + 1) + ' 版'"
                    @click="emit('open', vi)">v{{ vi + 1 }}</button>
            <span v-if="xi < shownVers.length - 1" class="pc-arr">›</span>
          </template>
        </span>
        <span v-else class="pc-ver">v{{ version }}</span>
        <button v-if="lineage" type="button" class="pc-src" title="回到旧计划的执行记录"
                @click="emit('locate-source')">{{ lineage }} ↗</button>
        <span class="pc-state" :class="'st-' + stateTone" aria-live="polite">{{ stateText }}</span>
        <span v-if="countdown && isLive" class="pc-timer" :class="{ warn: expired }">
          <clock-circle-outlined /> {{ countdown }}
        </span>
        <button v-if="!isLive" type="button" class="pc-fold" title="收起为一行" @click="toggleLine">
          <up-outlined />
        </button>
      </div>

      <div v-if="card.status !== 'rejected'" class="pc-body">
        <p v-if="outline.goal" class="pc-goal">{{ outline.goal }}</p>
        <!-- 执行中/已完成：进度条 + 逐步骤行。步骤来自会话任务清单（清单本来就是这一轮的执行项），
             产物与耗时按后端工具卡的 stepId 归到对应步骤；没有归属数据时就只报状态，绝不猜。 -->
        <template v-if="progress">
          <div class="exec-progress-line">
            <span class="pc-bar" role="progressbar" :aria-valuemin="0" :aria-valuemax="progress.total"
                  :aria-valuenow="progress.done"
                  :aria-label="'本轮任务进度 ' + progress.done + '/' + progress.total">
              <i v-for="(seg, si) in barSegs" :key="si" :class="'is-' + seg"></i>
            </span>
            <span class="exec-pct" :class="{ ok: execDone, fail: execFailed }">{{ progress.done }}/{{ progress.total }}</span>
          </div>
          <ol class="exec-steps">
            <li v-for="(row, ri) in execRows" :key="ri" class="exec-row" :class="row.tone">
              <span class="exec-st" :class="row.tone">
                <check-outlined v-if="row.tone === 'done'" />
                <loading-outlined v-else-if="row.tone === 'run'" spin />
                <exclamation-circle-outlined v-else-if="row.tone === 'fail'" />
                <template v-else>{{ ri + 1 }}</template>
              </span>
              <div class="exec-main">
                <div class="exec-t">{{ row.text }}</div>
                <div v-if="row.chips.length" class="exec-meta">
                  <span v-for="(c, ci) in row.chips" :key="ci" class="exec-chip" :class="{ out: c.out, ok: c.ok }">{{ c.text }}</span>
                </div>
                <div v-if="row.tone === 'run'" class="exec-mini"><i></i></div>
                <div v-else-if="row.tone === 'fail'" class="exec-d">这一步没有跑完；前序产物已保留</div>
              </div>
              <span class="exec-time">{{ row.time }}</span>
            </li>
          </ol>
          <div v-if="execDone" class="exec-strip">
            <check-outlined class="ok-ic" />
            <span><b>{{ progress.total }} 步全部完成</b><template v-if="artifactCount"> · {{ artifactCount }} 个产物已保留</template></span>
          </div>
          <div v-else-if="execFailed" class="exec-fail-note">
            执行中断 · {{ progress.done }}/{{ progress.total }} 步完成；可查看执行详情重试或停止。
          </div>
        </template>
        <template v-else-if="outline.steps.length">
          <ol class="pc-steps">
            <li v-for="(s, si) in shownSteps" :key="si">
              <span class="pc-no">{{ si + 1 }}</span>
              <span class="pc-step">
                <span class="pc-step-t">{{ s.title }}</span>
                <span v-if="s.detail" class="pc-step-s">{{ s.detail }}</span>
              </span>
            </li>
          </ol>
          <div v-if="outline.steps.length > shownSteps.length" class="pc-more">
            还有 {{ outline.steps.length - shownSteps.length }} 步 · 共 {{ outline.steps.length }} 步
          </div>
        </template>
        <!-- 解析不出列表：退回两行摘要（不为了结构去改模型输出） -->
        <div v-else-if="card.plan && card.plan.trim()" class="md pc-fallback"
             v-html="renderMd(fallbackText, [])"></div>
      </div>

      <div v-else class="pc-rejected">
        <span>这一版计划未获批准，本轮已停止。</span>
        <button type="button" class="pc-link" @click="emit('open', null)">查看原计划</button>
      </div>

      <!-- 调整记录：版本链进来后不再藏在卡头折叠里，逐版可点开对比；当前版高亮 -->
      <div v-if="versionRows.length > 1 && card.status !== 'rejected'" class="pc-adjust">
        <span class="lab">调整记录</span>
        <template v-for="(row, ri) in versionRows" :key="row.i">
          <button type="button" class="adj" :class="{ on: row.on }" :title="'查看第 ' + row.n + ' 版'"
                  @click="emit('open', row.i)">{{ row.label }}</button>
          <span v-if="ri < versionRows.length - 1" class="arr">›</span>
        </template>
        <button type="button" class="adj-open" @click="emit('open', null)">逐版查看 ↗</button>
      </div>

      <!-- 执行中调整：只放一条摘要（最近一条），完整记录在抽屉执行页；点它直接落到执行页签 -->
      <button v-if="adjusts.length" type="button" class="pc-adjusts" @click="emit('open', null, 'exec')">
        <span class="pc-adjusts-lab">本轮调整 {{ adjusts.length }}</span>
        <span class="pc-adjusts-txt" :title="lastAdjust">最近：{{ lastAdjust }}<template v-if="lastAdjustImages"> · 含 {{ lastAdjustImages }} 张图</template></span>
        <span class="pc-adjusts-go">查看</span>
      </button>

      <div class="pc-foot">
        <span class="pc-meta">
          <template v-if="card.revising">正在按意见修改…</template>
          <template v-else-if="card.status === 'drafting'">正在生成计划…</template>
          <template v-else-if="progress">
            {{ progress.done }}/{{ progress.total }} 已完成<template v-if="progress.failed"> · {{ progress.failed }} 步失败</template><template v-if="progress.running && doingStep"> · 进行第 {{ doingStep }} 步</template><template v-if="artifactCount"> · {{ artifactCount }} 个产物</template>
          </template>
          <template v-else>
            共 {{ stepCount }} 步<template v-if="chain.length > 1"> · 已修改 {{ chain.length - 1 }} 次</template>
          </template>
        </span>
        <span class="pc-acts">
          <button v-if="replanned && card.replannedBy" type="button" class="pc-act"
                  title="跳到按新意见生成的那一版计划" @click="emit('locate-replanned')">查看新一轮 ↗</button>
          <button v-if="canAdjust" type="button" class="pc-act" @click="emit('adjust')">调整计划</button>
          <button type="button" class="pc-open" :disabled="!card.plan && card.status === 'drafting'"
                  @click="emit('open', null, progress ? 'exec' : 'plan')">
            {{ progress ? '查看执行详情' : (card.status === 'approved' ? '查看计划详情' : '查看完整计划') }}
            <arrow-right-outlined />
          </button>
        </span>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import {
  OrderedListOutlined, UpOutlined, CaretRightOutlined, ArrowRightOutlined,
  ClockCircleOutlined, CheckOutlined, LoadingOutlined, ExclamationCircleOutlined
} from '@ant-design/icons-vue'
import { renderMd } from '../utils/markdown'
import { planCardExpanded, planOutline, planStepCount } from '../chat/plan'
import { planLineChoiceOf, setPlanLineOpen } from '../chat/planUiState'

const props = defineProps({
  /** 引擎挂在这一轮助手消息上的计划卡记录：{plan,status,versions,feedback,id} */
  card: { type: Object, required: true },
  /** 深度执行上下文：这张卡就是本次执行的过程卡（不再另起一张） */
  deep: { type: Boolean, default: false },
  /** 会话 id + 本条计划在本会话里的稳定键：旧计划的展开态按这两个维度记住 */
  sid: { type: String, default: '' },
  pkey: { type: [String, Number], default: '' },
  /** 批准后的执行进度：{items:[{text,status}], done, total, running}；没有清单时为 null */
  progress: { type: Object, default: null },
  /** 执行详情（planExecDetail 的结果）：按 stepId 把工具与产物归到步骤上；没有归属数据时为 null */
  execDetail: { type: Object, default: null },
  /** 本轮产物数（产物列表与计划卡并排展示时，卡脚给个计数） */
  artifactCount: { type: Number, default: 0 },
  /** 本轮调整记录（执行中插话）：[{text,messageId}]；只对已批准计划展示 */
  adjusts: { type: Array, default: () => [] },
  /** 待确认倒计时文案（与底部确认栏同源同值） */
  countdown: { type: String, default: '' },
  /** 倒计时已归零：动作已被锁住 */
  expired: { type: Boolean, default: false },
  /** 只读（分享页/历史只读场）：不给「调整计划」入口 */
  readOnly: { type: Boolean, default: false }
})
const emit = defineEmits(['open', 'adjust', 'locate-replanned', 'locate-source'])

const rootEl = ref(null)
// 待确认/生成中的计划常驻展开；批准之后默认收成一行，避免历史计划把消息流刷满。
// 「这一轮正在跑」自动铺开步骤进度，但用户手动收起要算数：深度执行后台续跑时
// 卡上的进度常常已经 5/5，如果收起被自动展开顶掉，那颗折叠键就成了摆设。
const isLive = computed(() => props.card.status === 'pending' || props.card.status === 'drafting')
const runningNow = computed(() => !!(props.progress && props.progress.running))
const lineChoice = ref(planLineChoiceOf(props.sid, props.pkey))
const expanded = computed(() => planCardExpanded({
  live: isLive.value, running: runningNow.value, choice: lineChoice.value
}))
// 深度执行里消息对象会被整条替换（列表按消息对象做键），卡片可能重挂：跟着槽位重读用户选择
watch(() => [props.sid, props.pkey], () => { lineChoice.value = planLineChoiceOf(props.sid, props.pkey) })

const outline = computed(() => planOutline(props.card.plan))
const stepCount = computed(() => outline.value.steps.length || planStepCount(props.card.plan))
const shownSteps = computed(() => outline.value.steps.slice(0, 3))
/** 进度条分段：一项一格，按状态定色（未开始留空、全靠轨道底色兜住） */
const barSegs = computed(() => (props.progress ? props.progress.items.map(i => i.status || 'wait') : []))
const execDone = computed(() => !!(props.progress && props.progress.total && props.progress.done >= props.progress.total))
const execFailed = computed(() => !!(props.progress && props.progress.failed))
const fmtElapsed = ms => {
  const n = Number(ms) || 0
  if (n <= 0) return ''
  const s = Math.round(n / 1000)
  if (s <= 0) return ''
  if (s < 60) return s + 's'
  const m = Math.floor(s / 60)
  return m + ':' + String(s % 60).padStart(2, '0')
}
/** 执行行：步骤文本 + 状态 + 该步产物 / 耗时（有 stepId 归属才展示，否则只说状态） */
const execRows = computed(() => {
  const p = props.progress
  if (!p) return []
  const detail = props.execDetail && props.execDetail.grouped ? props.execDetail : null
  return p.items.map((it, i) => {
    const tone = it.status === 'done' ? 'done'
      : (it.status === 'doing' ? 'run' : (it.status === 'failed' ? 'fail' : 'wait'))
    const d = detail && detail.steps[i]
    const chips = []
    for (const a of ((d && d.artifacts) || []).slice(0, 2)) {
      if (a && a.name) chips.push({ text: a.name, out: true, ok: false })
    }
    // 完成/进行中不再补一颗状态 chip：勾选图标、转圈与耗时已经说清状态，重复只会把每行塞满
    if (!chips.length && tone === 'fail') chips.push({ text: '失败', out: false, ok: false })
    return {
      text: it.text,
      tone,
      chips,
      time: tone === 'run' ? '进行中' : (tone === 'wait' ? '—' : (fmtElapsed(d && d.elapsedMs) || ''))
    }
  })
})
// 「进行第 3 步」按下标数第几个执行项；没有 doing 项时不报步号（不拿已完成数 +1 去猜）
const doingStep = computed(() => {
  const i = props.progress ? props.progress.items.findIndex(x => x.status === 'doing') : -1
  return i >= 0 ? i + 1 : 0
})
const version = computed(() => (Array.isArray(props.card.versions) ? props.card.versions.length : 0) + 1)
const chain = computed(() => {
  const vs = Array.isArray(props.card.versions) ? props.card.versions : []
  return [...vs, { plan: props.card.plan || '', feedback: props.card.feedback || '' }]
})
// 版本链最多给 4 颗入口（首版 + 最近三版），太长会在卡头换行
const shownVers = computed(() => {
  const all = chain.value.map((_, i) => i)
  if (all.length <= 4) return all
  return [0, all.length - 3, all.length - 2, all.length - 1]
})
/** 「调整记录」行：每版一颗可点标签，当前版高亮；中间版带一句意见摘要 */
const versionRows = computed(() => chain.value.map((v, i) => {
  const n = i + 1
  const fb = String(v && v.feedback ? v.feedback : '').replace(/\s+/g, ' ').trim()
  const label = i === 0 ? `v${n} 原稿`
    : (i === chain.value.length - 1 ? `v${n} 当前` : (fb ? `v${n} ${fb.slice(0, 8)}` : `v${n}`))
  return { i, n, label, on: i === chain.value.length - 1 }
}))
/** 已按新意见重新规划（旧卡保留执行记录）：状态不再报「已批准/未完成」，而是说清它被重新规划过 */
const replanned = computed(() => !!(props.card.replannedBy || props.card.replannedAt))
/** 调整入口只在「已批准 + 本机接得上 + 非只读 + 还没被重新规划」时出现 */
const canAdjust = computed(() => props.card.status === 'approved'
  && !props.card.restored && !props.readOnly && !replanned.value)
const lastAdjust = computed(() => {
  const rows = props.adjusts || []
  return rows.length ? String(rows[rows.length - 1].text || '') : ''
})
/** 最近一条调整带了几张图：计划卡只做摘要，图本身在执行详情里按条展示 */
const lastAdjustImages = computed(() => {
  const rows = props.adjusts || []
  const last = rows.length ? rows[rows.length - 1] : null
  return last && Array.isArray(last.images) ? last.images.length : 0
})
/** 新计划的血缘标：基于哪一版旧计划、那一轮已执行到 N/M（旧卡没有这个字段） */
const lineage = computed(() => {
  const f = props.card.adjustFrom
  if (!f || !f.cardId) return ''
  const v = Number(f.version) || 1
  const total = Number(f.total) || 0
  const done = Number(f.done) || 0
  return total > 0 ? `调整自 v${v} · ${done}/${total}` : `调整自 v${v}`
})
const stateText = computed(() => {
  if (props.card.revising) return props.card.revisingStarted ? '生成中…' : '修改中…'
  if (replanned.value) {
    const p = props.progress
    if (p && p.total && p.done >= p.total) return '已完成 · 已重新规划'
    if (p && p.failed) return '未完成 · 已重新规划'
    if (p && p.total) return '已停止 · 已重新规划'
    return '已重新规划'
  }
  if (props.progress) {
    if (props.progress.running) return '已批准 · 执行中'
    if (props.progress.total && props.progress.done >= props.progress.total) return '已完成'
    // 跑到一半停了（用户停止/失败）：不能报「已完成」，也不要拿「已批准」把缺口盖住
    return props.progress.failed ? '执行中断' : '未完成'
  }
  return {
    drafting: '生成中…', pending: '待确认', approved: '已批准', superseded: '已被取代',
    rejected: '未批准', stopped: '已停止'
  }[props.card.status] || props.card.status
})
const stateTone = computed(() => {
  if (props.card.revising) return props.card.revisingStarted ? 'drafting' : 'pending'
  if (replanned.value) return 'muted'
  if (props.progress) {
    if (props.progress.running) return 'run'
    if (props.progress.total && props.progress.done >= props.progress.total) return 'ok'
    return props.progress.failed ? 'fail' : 'muted'
  }
  return {
    drafting: 'drafting', pending: 'pending', approved: 'ok',
    superseded: 'muted', rejected: 'muted', stopped: 'muted'
  }[props.card.status] || 'muted'
})
const lineTitle = computed(() => stateText.value + ' · 共 ' + stepCount.value + ' 步 · 点开查看')
const fallbackText = computed(() => {
  const s = String(props.card.plan || '').trim()
  const lines = s.split('\n').map(x => x.trim()).filter(Boolean)
  return lines.slice(0, 2).join('\n')
})

// 折叠 / 展开都锚卡头：点按点（卡头那颗折叠键、或收起后的整行摘要）停在原地，卡片向下收放，
// 视角不跟着一上一下（此前收起锚底、展开锚顶，两次方向相反，看起来就是「跳」）。
// 正常情况下卡头在文档里的位置不变，不需要补差值；只有浏览器把 scrollTop 夹回边界时才补。
const scrollParentOf = el => {
  for (let n = el?.parentElement; n; n = n.parentElement) {
    const oy = getComputedStyle(n).overflowY
    if ((oy === 'auto' || oy === 'scroll' || oy === 'overlay') && n.scrollHeight > n.clientHeight) return n
  }
  return document.scrollingElement || document.documentElement
}
const toggleLine = async () => {
  const root = rootEl.value
  // 以「眼前是不是铺开的」为准，而不是以记住的偏好为准：自动展开（本轮在跑）时点收起必须真收起
  const next = !expanded.value
  lineChoice.value = next
  setPlanLineOpen(props.sid, props.pkey, next)
  if (!root) return
  const before = root.getBoundingClientRect().top
  await nextTick()
  const delta = root.getBoundingClientRect().top - before
  if (delta) scrollParentOf(root).scrollTop += delta
}
</script>

<style scoped>
/* 生命周期能从卡片自身状态读出来：drafting 生成中（蓝）→ pending 待确认（琥珀，同审批卡语义）
   → approved 已批准（绿）/ rejected 未批准（灰弱化收尾）。
   版式对齐 design/mockups/plan-mode-ux：琥珀底 + 卡头 / 正文 / 卡脚三段，执行态换绿。 */
.plan-card { margin-top: 8px; border: 1px solid var(--app-warn-border); background: var(--app-warn-weak); border-radius: 10px; overflow: hidden; }
.plan-card.plan-approved { border-color: var(--app-ok-border, #cfe4d3); background: var(--app-ok-weak, #f7fbf8); }
.plan-card.plan-approved.is-failed { border-color: var(--app-danger-border, #efc8c0); background: var(--app-danger-weak, #fff8f7); }
.plan-card.plan-rejected { border-color: var(--app-border); background: var(--app-panel); opacity: .78; }
/* 已被取代（用户提出修改、继续对话后本版作废）：弱化，但点开仍能读全文 */
.plan-card.plan-superseded { border-color: var(--app-border); background: var(--app-panel); opacity: .86; }

.pc-head { display: flex; align-items: center; gap: 7px; min-width: 0; padding: 9px 12px; font-size: 12.5px; font-weight: 600; color: var(--app-warn-text); }
.plan-card.plan-approved .pc-head { color: var(--app-ok); }
.plan-card.plan-approved.is-failed .pc-head { color: var(--app-danger, #cf1322); }
.plan-card.plan-rejected .pc-head, .plan-card.plan-superseded .pc-head { color: var(--app-text2); }
.pc-ic { flex: none; }
.pc-title { flex: none; }
.pc-ver { flex: none; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 9.5px; font-weight: 500; color: var(--app-warn-text); border: 1px solid var(--app-warn-border); background: var(--app-panel); padding: 0 5px; border-radius: 999px; line-height: 16px; }
.plan-card.plan-approved .pc-ver { color: var(--app-ok); border-color: var(--app-ok-border, #cfe4d3); }
.plan-card.plan-rejected .pc-ver, .plan-card.plan-superseded .pc-ver { color: var(--app-text3); border-color: var(--app-border); }
.pc-state { flex: none; font-size: 11.5px; font-weight: 600; color: var(--app-warn-text); white-space: nowrap; }
.pc-state.st-drafting, .pc-state.st-muted { color: var(--app-text3); font-weight: 500; }
.pc-state.st-pending, .pc-state.st-run { color: var(--app-warn-text); }
.plan-card.plan-approved:not(.is-failed) .pc-state.st-run { color: var(--app-ok); }
.pc-state.st-ok { color: var(--app-ok); }
.pc-state.st-fail { color: var(--app-danger, #cf1322); }
/* 倒计时与底部确认栏同源同值：卡头看得到「还剩多久要做裁决」 */
.pc-timer { flex: none; margin-left: auto; display: inline-flex; align-items: center; gap: 4px; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 10.5px; color: var(--app-text2); font-variant-numeric: tabular-nums; }
.pc-timer.warn { color: var(--app-warn); }
/* 血缘标：新计划卡头的小号来源，点击回旧卡执行记录；不抢版本号与状态的读数 */
.pc-src { flex: none; border: 1px solid var(--app-accent-border); background: var(--app-accent-weak);
  color: var(--app-accent); border-radius: 4px; padding: 1px 5px; font-size: 10px; line-height: 1.5;
  font-weight: 500; cursor: pointer; white-space: nowrap; }
.pc-src:hover { border-color: var(--app-accent); }

/* 卡头版本轨：v1 › v2 › v3；只做入口，正文仍在抽屉里 */
.pc-vers { display: inline-flex; align-items: center; gap: 1px; min-width: 0; overflow: hidden; }
.pc-vbtn { flex: none; border: 1px solid transparent; background: none; padding: 1px 4px; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 9.5px; color: var(--app-text3); cursor: pointer; border-radius: 4px; }
.pc-vbtn:hover { color: var(--app-accent); background: var(--app-accent-weak); }
.pc-vbtn.on { color: var(--app-warn-text); border-color: var(--app-warn-border); background: var(--app-panel); font-weight: 700; }
.plan-card.plan-approved .pc-vbtn.on { color: var(--app-ok); border-color: var(--app-ok-border, #cfe4d3); }
.pc-arr { flex: none; font-size: 9px; color: var(--app-text3); }
.pc-fold { flex: none; margin-left: auto; border: 0; background: none; padding: 2px; color: var(--app-text3); cursor: pointer; border-radius: 4px; }
.pc-fold:hover { color: var(--app-accent); background: var(--app-accent-weak); }

/* 旧计划一行摘要（整行可点，热区 ≥ 24px）；行尾的「新一轮」是独立按钮，不与展开按钮嵌套 */
.pc-line-wrap { display: flex; align-items: center; gap: 4px; min-width: 0; padding: 6px 12px; }
.pc-line-wrap .pc-line { flex: 1; width: auto; min-width: 0; }
.pc-line-jump { flex: none; border: 0; background: none; padding: 2px 4px; min-height: 24px; font-size: 10.5px;
  color: var(--app-accent); cursor: pointer; white-space: nowrap; }
.pc-line-jump:hover { background: var(--app-accent-weak); border-radius: 4px; }
.pc-line { display: flex; align-items: center; gap: 6px; width: 100%; min-height: 24px; padding: 0; border: 0; background: none; text-align: left; color: var(--app-text2); cursor: pointer; }
.pc-line:hover .pc-line-t { color: var(--app-accent); }
.pc-line-t { flex: none; font-size: 13px; font-weight: 600; }
.pc-line .pc-state { margin-left: 0; }
.pc-line-meta { flex: none; margin-left: auto; font-size: 11.5px; color: var(--app-text3); }
.pc-line-caret { flex: none; font-size: 11px; color: var(--app-text3); }

/* 三段式：目标 → 前三步 → 卡脚 */
.pc-body { padding: 0 12px 9px; }
.pc-goal { margin: 0 0 7px; font-size: 12.5px; line-height: 1.55; font-weight: 600; color: var(--app-text); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.pc-steps { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; }
.pc-steps li { display: flex; gap: 8px; align-items: flex-start; min-width: 0; padding: 3.5px 0; }
.pc-no { flex: none; width: 17px; height: 17px; margin-top: 1px; border-radius: 50%; background: var(--app-panel); border: 1px solid var(--app-warn-border); color: var(--app-warn-text); font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 10px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
.plan-card.plan-approved .pc-no, .plan-card.plan-superseded .pc-no, .plan-card.plan-rejected .pc-no { border-color: var(--app-border); color: var(--app-text3); }
.pc-step { flex: 1; min-width: 0; display: flex; flex-direction: row; align-items: baseline; gap: 6px; }
.pc-step-t { flex: none; max-width: 62%; font-size: 12.5px; line-height: 1.5; font-weight: 500; color: var(--app-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.pc-step-s { flex: 1; min-width: 0; font-size: 11.5px; line-height: 1.5; color: var(--app-text2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.pc-more { padding: 3px 0 0 25px; font-size: 11px; color: var(--app-text2); }
.pc-fallback { margin-top: 2px; max-height: 42px; overflow: hidden; font-size: 12px;
  -webkit-mask-image: linear-gradient(to bottom, #000 60%, transparent 98%); mask-image: linear-gradient(to bottom, #000 60%, transparent 98%); }
.pc-rejected { padding: 0 12px 9px; display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--app-text3); }
.pc-link { border: 0; background: none; padding: 0; font-size: 12px; color: var(--app-accent); cursor: pointer; }

/* 执行态：进度条 + 逐步骤行（完成绿勾 / 进行中转圈 / 等待编号 / 失败叹号） */
.exec-progress-line { display: flex; align-items: center; gap: 8px; margin: 1px 0 6px; }
.pc-bar { flex: 1; min-width: 0; height: 4px; border-radius: 999px; background: var(--app-panel-3, #eef0f3); display: flex; gap: 2px; overflow: hidden; }
.pc-bar i { flex: 1; height: 100%; background: transparent; }
.pc-bar i.is-done { background: var(--app-ok); }
.pc-bar i.is-doing { background: var(--app-warn); }
.pc-bar i.is-failed { background: var(--app-danger, #cf1322); }
.exec-pct { flex: none; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-variant-numeric: tabular-nums; font-size: 10px; color: var(--app-text2); }
.exec-pct.ok { color: var(--app-ok); }
.exec-pct.fail { color: var(--app-danger, #cf1322); }
.exec-steps { list-style: none; margin: 0; padding: 0; }
.exec-row { display: flex; align-items: flex-start; gap: 8px; padding: 5px 0; border-bottom: 1px dashed var(--app-border); }
.exec-row:last-child { border-bottom: none; }
.exec-row.run { background: var(--app-warn-weak); margin: 0 -6px; padding-left: 6px; padding-right: 6px; border-radius: 6px; }
.exec-row.fail { background: var(--app-danger-weak); margin: 0 -6px; padding-left: 6px; padding-right: 6px; border-radius: 6px; }
.exec-st { flex: none; width: 17px; height: 17px; margin-top: 1px; border-radius: 50%; border: 1px solid var(--app-border); background: var(--app-panel); color: var(--app-text3); font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 9px; font-weight: 700; display: inline-flex; align-items: center; justify-content: center; }
.exec-st.done { border-color: var(--app-ok-border, #cfe4d3); background: var(--app-ok-weak); color: var(--app-ok); }
.exec-st.run { border-color: var(--app-warn-border); background: var(--app-panel); color: var(--app-warn-text); }
.exec-st.fail { border-color: var(--app-danger-border, #efc8c0); background: var(--app-danger-weak); color: var(--app-danger, #cf1322); }
.exec-main { flex: 1; min-width: 0; }
.exec-t { font-size: 12px; font-weight: 500; line-height: 1.5; color: var(--app-text); }
.exec-row.done .exec-t { color: var(--app-text2); }
.exec-row.run .exec-t { color: var(--app-warn-text); font-weight: 600; }
.exec-row.fail .exec-t { color: var(--app-danger, #cf1322); font-weight: 600; }
.exec-d { margin-top: 1px; font-size: 10.5px; color: var(--app-text3); line-height: 1.5; }
.exec-meta { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 3px; }
.exec-chip { font-size: 10px; line-height: 16px; padding: 0 5px; border-radius: 4px; border: 1px solid var(--app-border); background: var(--app-panel); color: var(--app-text3); }
.exec-chip.out { color: var(--app-accent); border-color: var(--app-accent-border); background: var(--app-accent-weak); }
.exec-chip.ok { color: var(--app-ok); border-color: var(--app-ok-border, #cfe4d3); background: var(--app-ok-weak); }
.exec-time { flex: none; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 9.5px; color: var(--app-text3); text-align: right; white-space: nowrap; padding-top: 1px; }
.exec-mini { height: 2px; border-radius: 1px; background: var(--app-warn-border); overflow: hidden; margin-top: 5px; }
.exec-mini i { display: block; height: 100%; width: 46%; background: var(--app-warn); animation: plan-exec-pulse 1.4s ease-in-out infinite; }
@keyframes plan-exec-pulse { 0%, 100% { opacity: .45; transform: translateX(-10%); } 50% { opacity: 1; transform: translateX(110%); } }
@media (prefers-reduced-motion: reduce) { .exec-mini i { animation: none; width: 60%; } }
.exec-strip { display: flex; align-items: center; gap: 7px; margin-top: 7px; padding: 6px 8px; border-radius: 6px; background: var(--app-panel); font-size: 11.5px; color: var(--app-text2); }
.exec-strip .ok-ic { color: var(--app-ok); flex: none; }
.exec-strip b { color: var(--app-text); }
.exec-fail-note { margin-top: 7px; font-size: 11px; line-height: 1.6; color: var(--app-danger, #cf1322); }

/* 调整记录：版本链的可读轨，逐版可点开；当前版高亮，不再只是一颗「已修改 N 次」 */
.pc-adjust { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; padding: 6px 12px;
  border-top: 1px dashed var(--app-warn-border); background: var(--app-panel); font-size: 10.5px; color: var(--app-text2); }
.plan-card.plan-approved .pc-adjust { border-top-color: var(--app-ok-border, #cfe4d3); }
.pc-adjust .lab { color: var(--app-text3); }
.pc-adjust .adj { border: 0; background: none; padding: 0; font-size: 10.5px; color: var(--app-text2); cursor: pointer; }
.pc-adjust .adj:hover { color: var(--app-accent); }
.pc-adjust .adj.on { color: var(--app-warn-text); font-weight: 600; }
.plan-card.plan-approved .pc-adjust .adj.on { color: var(--app-ok); }
.pc-adjust .arr { font-size: 10px; color: var(--app-text3); }
.pc-adjust .adj-open { margin-left: auto; border: 0; background: none; padding: 0; font-size: 10.5px; color: var(--app-warn-text); cursor: pointer; }
.plan-card.plan-approved .pc-adjust .adj-open { color: var(--app-ok); }
.pc-adjust .adj-open:hover { text-decoration: underline; }

/* 执行中调整摘要：一条带底色的小行，不抢步骤区的注意力；完整记录在执行抽屉里 */
.pc-adjusts { display: flex; align-items: center; gap: 6px; width: calc(100% - 24px); margin: 0 12px 6px; padding: 4px 7px;
  border: 1px solid var(--app-accent-border); background: var(--app-accent-weak); border-radius: 6px;
  font-size: 11px; line-height: 1.5; color: var(--app-text3); cursor: pointer; text-align: left; }
.pc-adjusts:hover { border-color: var(--app-accent); }
.pc-adjusts-lab { flex: none; font-weight: 600; color: var(--app-accent); }
.pc-adjusts-txt { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pc-adjusts-go { flex: none; color: var(--app-accent); }

.pc-foot { display: flex; align-items: center; gap: 8px; padding: 7px 12px; border-top: 1px dashed var(--app-warn-border); background: var(--app-panel); font-size: 11px; color: var(--app-text2); }
.plan-card.plan-approved .pc-foot { border-top-color: var(--app-ok-border, #cfe4d3); }
.plan-card.plan-rejected .pc-foot, .plan-card.plan-superseded .pc-foot { border-top-color: var(--app-border); }
.pc-meta { min-width: 0; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pc-acts { flex: none; margin-left: auto; display: flex; align-items: center; gap: 2px; }
/* 次级动作（调整计划 / 查看新一轮）：不压过主出口，但热区 ≥ 24px */
.pc-act { flex: none; border: 0; background: none; padding: 3px 5px; min-height: 24px; font-size: 12px;
  font-weight: 500; color: var(--app-accent); cursor: pointer; white-space: nowrap; }
.pc-act:hover { color: var(--app-accent-hover); background: var(--app-accent-weak); border-radius: 4px; }
.pc-open { flex: none; display: inline-flex; align-items: center; gap: 3px; border: 0; background: none; padding: 2px 0; min-height: 24px; font-size: 11px; font-weight: 600; color: var(--app-warn-text); cursor: pointer; }
.plan-card.plan-approved .pc-open { color: var(--app-ok); }
.plan-card.plan-rejected .pc-open, .plan-card.plan-superseded .pc-open { color: var(--app-accent); }
.pc-open:hover { text-decoration: underline; }
.pc-open:disabled { color: var(--app-text3); cursor: default; }
</style>
