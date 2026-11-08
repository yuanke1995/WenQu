<template>
  <!-- 执行计划卡（人在回路）：只读展示一轮计划的生命周期——
       流式产出 drafting → 待确认 pending → 已批准 / 未批准 / 已被取代。
       卡上只放「目标 + 三步 + 卡脚」，完整正文、版本轨与意见都在 PlanViewer 抽屉里；
       旧计划（已批准/已被取代/未批准）默认收成一行，点开才铺三段式（按会话记住）。
       裁决动作不在这里：打字框整块顶替成确认栏（PC 的 planp-*、移动壳的 m-planp-*）。 -->
  <div ref="rootEl" class="plan-card" :class="'plan-' + card.status">
    <!-- 旧计划：一行摘要。点整行展开 / 收起，不再用覆盖正文的绝对定位按钮 -->
    <button v-if="!expanded && !isLive" type="button" class="pc-line" :title="lineTitle"
            @click="toggleLine">
      <ordered-list-outlined class="pc-ic" />
      <span class="pc-line-t">执行计划</span>
      <span class="pc-ver">v{{ version }}</span>
      <span class="pc-state" :class="'st-' + stateTone">{{ stateText }}</span>
      <span class="pc-line-meta">
        <template v-if="progress">{{ progress.done }}/{{ progress.total }}{{ progress.running ? ' 进行中' : (progress.done >= progress.total ? ' 已完成' : ' 未完成') }}<template v-if="progress.failed"> · {{ progress.failed }} 失败</template></template>
        <template v-else>{{ stepCount }} 步</template>
      </span>
      <caret-right-outlined class="pc-line-caret" />
    </button>

    <template v-else>
      <div class="pc-head">
        <ordered-list-outlined class="pc-ic" />
        <span class="pc-title">执行计划</span>
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
        <span class="pc-state" :class="'st-' + stateTone">{{ stateText }}</span>
        <button v-if="!isLive" type="button" class="pc-fold" title="收起为一行" @click="toggleLine">
          <up-outlined />
        </button>
      </div>

      <div v-if="card.status !== 'rejected'" class="pc-body">
        <p v-if="outline.goal" class="pc-goal">{{ outline.goal }}</p>
        <!-- 执行中/已完成：拿会话任务清单当步骤进度（清单本来就是这一轮的执行项）。
             没有清单就退回计划步骤预览，绝不按工具调用数去编造「第几步已完成」。 -->
        <template v-if="progress">
          <ol class="pc-steps pc-exec">
            <li v-for="(it, pi) in shownProgress" :key="pi" :class="'is-' + it.status">
              <span class="pc-st" :class="it.status">
                <check-outlined v-if="it.status === 'done'" class="pc-st-ic" />
                <close-outlined v-else-if="it.status === 'failed'" class="pc-st-ic" />
                <loading-outlined v-else-if="it.status === 'doing' && progress.running" class="pc-st-ic" spin />
              </span>
              <span class="pc-step"><span class="pc-step-t">{{ it.text }}</span></span>
            </li>
          </ol>
          <div v-if="progress.items.length > shownProgress.length" class="pc-more">
            还有 {{ progress.items.length - shownProgress.length }} 项
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

      <!-- 执行中调整：只放一条摘要（最近一条），完整记录在抽屉执行页；点它直接落到执行页签 -->
      <button v-if="adjusts.length" type="button" class="pc-adjusts" @click="emit('open', null, 'exec')">
        <span class="pc-adjusts-lab">本轮调整 {{ adjusts.length }}</span>
        <span class="pc-adjusts-txt" :title="lastAdjust">最近：{{ lastAdjust }}</span>
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
import { computed, nextTick, ref } from 'vue'
import { OrderedListOutlined, UpOutlined, CaretRightOutlined, ArrowRightOutlined, CheckOutlined, CloseOutlined, LoadingOutlined } from '@ant-design/icons-vue'
import { renderMd } from '../utils/markdown'
import { planOutline, planStepCount } from '../chat/plan'
import { planLineOpenOf, setPlanLineOpen } from '../chat/planUiState'

const props = defineProps({
  /** 引擎挂在这一轮助手消息上的计划卡记录：{plan,status,versions,feedback,id} */
  card: { type: Object, required: true },
  /** 会话 id + 本条计划在本会话里的稳定键：旧计划的展开态按这两个维度记住 */
  sid: { type: String, default: '' },
  pkey: { type: [String, Number], default: '' },
  /** 批准后的执行进度：{items:[{text,status}], done, total, running}；没有清单时为 null */
  progress: { type: Object, default: null },
  /** 本轮产物数（产物列表与计划卡并排展示时，卡脚给个计数） */
  artifactCount: { type: Number, default: 0 },
  /** 本轮调整记录（执行中插话）：[{text,messageId}]；只对已批准计划展示 */
  adjusts: { type: Array, default: () => [] },
  /** 只读（分享页/历史只读场）：不给「调整计划」入口 */
  readOnly: { type: Boolean, default: false }
})
const emit = defineEmits(['open', 'adjust', 'locate-replanned', 'locate-source'])

const rootEl = ref(null)
// 待确认/生成中的计划常驻展开；批准之后默认收成一行，避免历史计划把消息流刷满。
// 唯一例外是「这一轮正在跑」：执行中的计划继续铺开步骤进度，跑完再收。
const isLive = computed(() => props.card.status === 'pending' || props.card.status === 'drafting')
const lineOpen = ref(planLineOpenOf(props.sid, props.pkey))
const expanded = computed(() => isLive.value || !!(props.progress && props.progress.running) || lineOpen.value)

const outline = computed(() => planOutline(props.card.plan))
const stepCount = computed(() => outline.value.steps.length || planStepCount(props.card.plan))
const shownSteps = computed(() => outline.value.steps.slice(0, 3))
const shownProgress = computed(() => (props.progress ? props.progress.items.slice(0, 5) : []))
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
/** 已按新意见重新规划（旧卡保留执行记录）：状态不再报「已批准/未完成」，而是说清它被重新规划过 */
const replanned = computed(() => !!(props.card.replannedBy || props.card.replannedAt))
/** 调整入口只在「已批准 + 本机接得上 + 非只读 + 还没被重新规划」时出现 */
const canAdjust = computed(() => props.card.status === 'approved'
  && !props.card.restored && !props.readOnly && !replanned.value)
const lastAdjust = computed(() => {
  const rows = props.adjusts || []
  return rows.length ? String(rows[rows.length - 1].text || '') : ''
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
    if (props.progress.running) return '执行中'
    if (props.progress.total && props.progress.done >= props.progress.total) return '已完成'
    // 跑到一半停了（用户停止/失败）：不能报「已完成」，也不要拿「已批准」把缺口盖住
    return props.progress.failed ? '有步骤失败' : '未完成'
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

// 收起锚卡片底边、展开锚卡片顶边：点按点之后卡片留在眼前，而不是滑到计划下面
const scrollParentOf = el => {
  for (let n = el?.parentElement; n; n = n.parentElement) {
    const oy = getComputedStyle(n).overflowY
    if ((oy === 'auto' || oy === 'scroll' || oy === 'overlay') && n.scrollHeight > n.clientHeight) return n
  }
  return document.scrollingElement || document.documentElement
}
const toggleLine = async () => {
  const root = rootEl.value
  const next = !lineOpen.value
  if (!root) { lineOpen.value = next; setPlanLineOpen(props.sid, props.pkey, next); return }
  const scroller = scrollParentOf(root)
  const before = root.getBoundingClientRect()
  lineOpen.value = next
  setPlanLineOpen(props.sid, props.pkey, next)
  await nextTick()
  const after = root.getBoundingClientRect()
  const delta = next ? after.top - before.top : after.bottom - before.bottom
  if (delta) scroller.scrollTop += delta
}
</script>

<style scoped>
/* 生命周期能从卡片自身状态读出来：drafting 生成中（蓝）→ pending 待确认（琥珀，同审批卡语义）
   → approved 已批准（绿）/ rejected 未批准（灰弱化收尾） */
.plan-card { margin-top: 8px; border: 1px solid var(--app-warn-border); background: var(--app-warn-weak); border-radius: 8px; padding: 10px 12px; }
.plan-card.plan-approved { border-color: var(--app-ok-border, var(--app-border)); background: var(--app-panel); }
.plan-card.plan-rejected { border-color: var(--app-border); background: var(--app-panel); opacity: .78; }
/* 已被取代（用户提出修改、继续对话后本版作废）：弱化，但点开仍能读全文 */
.plan-card.plan-superseded { border-color: var(--app-border); background: var(--app-panel); opacity: .86; }

.pc-head { display: flex; align-items: center; gap: 6px; min-width: 0; font-size: 13px; font-weight: 600; color: var(--app-warn-text); }
.plan-card.plan-approved .pc-head, .plan-card.plan-rejected .pc-head,
.plan-card.plan-superseded .pc-head { color: var(--app-text2); }
.pc-ic { flex: none; }
.pc-title { flex: none; }
.pc-ver { flex: none; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 10.5px; font-weight: 500; color: var(--app-text3); }
.pc-state { flex: none; margin-left: auto; font-size: 12px; font-weight: 500; }
.pc-state.st-drafting, .pc-state.st-muted { color: var(--app-text3); }
.pc-state.st-pending, .pc-state.st-run { color: var(--app-warn-text); }
.pc-state.st-ok { color: var(--app-ok); }
.pc-state.st-fail { color: var(--app-danger, #cf1322); }
/* 血缘标：新计划卡头的小号来源，点击回旧卡执行记录；不抢版本号与状态的读数 */
.pc-src { flex: none; border: 1px solid var(--app-accent-border); background: var(--app-accent-weak);
  color: var(--app-accent); border-radius: 4px; padding: 1px 5px; font-size: 10px; line-height: 1.5;
  font-weight: 500; cursor: pointer; white-space: nowrap; }
.pc-src:hover { border-color: var(--app-accent); }

/* 卡头版本轨：v1 › v2 › v3；只做入口，正文仍在抽屉里 */
.pc-vers { display: inline-flex; align-items: center; gap: 1px; min-width: 0; overflow: hidden; }
.pc-vbtn { flex: none; border: 0; background: none; padding: 1px 3px; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 10.5px; color: var(--app-text3); cursor: pointer; border-radius: 3px; }
.pc-vbtn:hover { color: var(--app-accent); background: var(--app-accent-weak); }
.pc-vbtn.on { color: var(--app-text); font-weight: 700; }
.pc-arr { flex: none; font-size: 10px; color: var(--app-text3); }
.pc-fold { flex: none; margin-left: 2px; border: 0; background: none; padding: 2px; color: var(--app-text3); cursor: pointer; border-radius: 4px; }
.pc-fold:hover { color: var(--app-accent); background: var(--app-accent-weak); }

/* 旧计划一行摘要（整行可点，热区 ≥ 24px） */
.pc-line { display: flex; align-items: center; gap: 6px; width: 100%; min-height: 24px; padding: 0; border: 0; background: none; text-align: left; color: var(--app-text2); cursor: pointer; }
.pc-line:hover .pc-line-t { color: var(--app-accent); }
.pc-line-t { flex: none; font-size: 13px; font-weight: 600; }
.pc-line .pc-state { margin-left: 0; }
.pc-line-meta { flex: none; margin-left: auto; font-size: 11.5px; color: var(--app-text3); }
.pc-line-caret { flex: none; font-size: 11px; color: var(--app-text3); }

/* 三段式：目标 → 前三步 → 卡脚；卡高仍压在 190px 内 */
.pc-body { margin-top: 7px; }
.pc-goal { margin: 0 0 6px; font-size: 12.5px; line-height: 1.55; color: var(--app-text); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.pc-steps { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 3px; }
.pc-steps li { display: flex; gap: 7px; align-items: flex-start; min-width: 0; }
.pc-no { flex: none; width: 14px; height: 14px; margin-top: 2px; border-radius: 50%; background: var(--app-panel); border: 1px solid var(--app-warn-border); color: var(--app-warn-text); font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 9px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
.plan-card.plan-approved .pc-no, .plan-card.plan-superseded .pc-no, .plan-card.plan-rejected .pc-no { border-color: var(--app-border); color: var(--app-text3); }
/* 执行进度：状态圆点替代步骤序号，一眼看出做到哪一步 */
.pc-st { flex: none; width: 14px; height: 14px; margin-top: 2px; border-radius: 50%; border: 1px solid var(--app-border); background: var(--app-panel); display: flex; align-items: center; justify-content: center; }
.pc-st.done { border-color: #cfe4d3; background: var(--app-ok-weak); color: var(--app-ok); }
.pc-st.doing { border-color: var(--app-warn-border); background: #fffaf0; color: var(--app-warn-text); }
.pc-st.failed { border-color: var(--app-danger, #cf1322); background: #fff1f0; color: var(--app-danger, #cf1322); }
.pc-st-ic { font-size: 10px; }
.pc-exec .pc-step-t { flex: 1; max-width: 100%; font-weight: 500; color: var(--app-text2); }
.pc-exec li.is-done .pc-step-t { color: var(--app-text3); }
.pc-exec li.is-failed .pc-step-t { color: var(--app-danger, #cf1322); }
.pc-step { flex: 1; min-width: 0; display: flex; flex-direction: row; align-items: baseline; gap: 6px; }
.pc-step-t { flex: none; max-width: 62%; font-size: 12.5px; line-height: 1.5; font-weight: 500; color: var(--app-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.pc-step-s { flex: 1; min-width: 0; font-size: 11px; line-height: 1.5; color: var(--app-text3); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.pc-more { margin-top: 5px; padding-left: 21px; font-size: 11px; color: var(--app-text3); }
.pc-fallback { margin-top: 2px; max-height: 42px; overflow: hidden; font-size: 12px;
  -webkit-mask-image: linear-gradient(to bottom, #000 60%, transparent 98%); mask-image: linear-gradient(to bottom, #000 60%, transparent 98%); }
.pc-rejected { margin-top: 6px; display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--app-text3); }
.pc-link { border: 0; background: none; padding: 0; font-size: 12px; color: var(--app-accent); cursor: pointer; }

/* 执行中调整摘要：一条带底色的小行，不抢步骤区的注意力；完整记录在执行抽屉里 */
.pc-adjusts { display: flex; align-items: center; gap: 6px; width: 100%; margin-top: 7px; padding: 4px 7px;
  border: 1px solid var(--app-accent-border); background: var(--app-accent-weak); border-radius: 6px;
  font-size: 11px; line-height: 1.5; color: var(--app-text3); cursor: pointer; text-align: left; }
.pc-adjusts:hover { border-color: var(--app-accent); }
.pc-adjusts-lab { flex: none; font-weight: 600; color: var(--app-accent); }
.pc-adjusts-txt { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pc-adjusts-go { flex: none; color: var(--app-accent); }

.pc-foot { display: flex; align-items: center; gap: 8px; margin-top: 8px; padding-top: 7px; border-top: 1px dashed var(--app-border); font-size: 11.5px; color: var(--app-text3); }
.pc-meta { min-width: 0; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pc-acts { flex: none; margin-left: auto; display: flex; align-items: center; gap: 2px; }
/* 次级动作（调整计划 / 查看新一轮）：不压过主出口，但热区 ≥ 24px */
.pc-act { flex: none; border: 0; background: none; padding: 3px 5px; min-height: 24px; font-size: 12px;
  font-weight: 500; color: var(--app-accent); cursor: pointer; white-space: nowrap; }
.pc-act:hover { color: var(--app-accent-hover); background: var(--app-accent-weak); border-radius: 4px; }
.pc-open { flex: none; display: inline-flex; align-items: center; gap: 3px; border: 0; background: none; padding: 2px 0; min-height: 24px; font-size: 12px; font-weight: 600; color: var(--app-warn-text); cursor: pointer; }
.plan-card.plan-approved .pc-open, .plan-card.plan-superseded .pc-open, .plan-card.plan-rejected .pc-open { color: var(--app-accent); }
.pc-open:hover { color: var(--app-accent-hover); }
.pc-open:disabled { color: var(--app-text3); cursor: default; }
</style>
