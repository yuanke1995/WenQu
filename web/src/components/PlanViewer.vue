<!--
  计划视图（完整计划 / 版本轨 / diff / 裁决）。

  PC：右侧抽屉 560px，遮罩不挡聊天区阅读；H5（≤720px）：全屏 sheet。
  版本只在**当前版**可裁决；历史版只读，避免把意见写回已作废的稿子。

  与卡片的接缝：
    - 卡片只出「目标 + 三步 + 卡脚」，不承载完整正文与版本记录；
    - 抽屉里的动作全部 emit 回页面，由页面调引擎（批准 / revise / supersede / 拒绝），
      组件本身不发请求，分享页将来可以只读复用。
-->
<template>
  <teleport to="body">
    <div v-if="open" class="pv-mask" @click="emit('close')">
      <aside class="pv-drawer" role="dialog" aria-modal="true" aria-label="执行计划"
             @click.stop>
        <header class="pv-head">
          <div class="pv-head-main">
            <span class="pv-eyebrow">执行计划</span>
            <h3 class="pv-title">{{ title || '本轮执行计划' }}</h3>
            <div class="pv-sub">
              <span class="pv-state" :class="'st-' + stateTone">{{ stateText }}</span>
              <button v-if="lineageLabel" type="button" class="pv-src" title="回到旧计划的执行记录"
                      @click="emit('locate-source')">{{ lineageLabel }} ↗</button>
              <span v-if="chain.length > 1" class="pv-rev">已修改 {{ chain.length - 1 }} 次</span>
              <span v-if="countdown && card.status === 'pending'" class="pv-timer" :class="{ warn: expired }">◷ {{ countdown }}</span>
              <span v-if="card.auto" class="pv-auto">自动开启</span>
            </div>
          </div>
          <button ref="closeBtn" type="button" class="pv-close" title="关闭（Esc）" @click="emit('close')">
            <close-outlined />
          </button>
        </header>

        <!-- 页签：计划（版本/意见/diff）与执行（步骤/工具/产物）共用这一个抽屉。
             只有计划批准后才出现「执行」——没执行过就给一个空页签，用户会以为是坏了 -->
        <div v-if="hasExec" class="pv-tabs" role="tablist">
          <button type="button" role="tab" class="pv-tab" :class="{ on: tab === 'plan' }"
                  :aria-selected="tab === 'plan'" @click="switchTab('plan')">计划</button>
          <button type="button" role="tab" class="pv-tab" :class="{ on: tab === 'exec' }"
                  :aria-selected="tab === 'exec'" @click="switchTab('exec')">
            执行<span v-if="progress" class="pv-tab-n">{{ progress.done }}/{{ progress.total }}</span>
          </button>
        </div>

        <!-- 版本轨：当前版在最后；点历史版只读回看 -->
        <div v-if="tab === 'plan'" class="pv-vers">
          <button v-for="(v, vi) in chain" :key="vi" type="button" class="pv-vbtn"
                  :class="{ on: vi === sel }" :title="'第 ' + (vi + 1) + ' 版'"
                  @click="pick(vi)">
            <b>v{{ vi + 1 }}</b>
            <span v-if="vi === 0">原稿</span>
            <span v-else-if="vi === chain.length - 1">当前</span>
            <span v-else>第 {{ vi + 1 }} 版</span>
          </button>
          <span class="pv-vers-line"></span>
          <button type="button" class="pv-diff" :class="{ on: diffOn }"
                  :disabled="sel === 0" :title="sel === 0 ? '首版没有可对比的上一版' : '对比上一版'"
                  @click="diffOn = !diffOn">
            变更高亮 · {{ diffOn && sel > 0 ? '开' : '关' }}
          </button>
        </div>

        <div ref="bodyEl" class="pv-body">
          <template v-if="tab === 'plan'">
          <!-- 原始计划独占正文：结构区（版本说明/意见/变更摘要/步骤）整块让位，
               从正文顶部开始读，不再挤在步骤下面那 280px 的滚动框里 -->
          <template v-if="rawMode">
            <div class="pv-raw-head">
              <span class="pv-raw-lab">原始计划 · v{{ sel + 1 }}</span>
              <button type="button" class="pv-raw-back" @click="toggleRaw(false)">返回结构视图</button>
            </div>
            <div class="md pv-fullmd pv-raw-doc" v-html="renderMd(planText(v.plan), [])"></div>
          </template>

          <template v-else>
          <div class="pv-meta">
            <span class="pv-chip" :class="{ cur: isCurrent }">
              {{ isCurrent ? (card.status === 'pending' ? '当前待确认版' : '当前版') : '历史版本 · 只读' }}
            </span>
            <span v-if="isHistorical" class="pv-meta-note">历史版本不可批准，请回当前版</span>
          </div>

          <div v-if="v.feedback" class="pv-fb">
            <div class="pv-fb-lab">产出这一版的意见 · v{{ sel + 1 }}</div>
            <p>{{ v.feedback }}</p>
            <button type="button" class="pv-fb-jump" @click="emit('locate')">在对话中定位 ↗</button>
          </div>

          <div v-if="diffOn && prevPlan && sel > 0" class="pv-sum">
            <span class="pv-sum-lab">变更摘要（v{{ sel }} → v{{ sel + 1 }}）</span>
            <b v-if="diff.changedCount" class="chg">修改 {{ diff.changedCount }}</b>
            <b v-if="diff.addedCount" class="add">新增 {{ diff.addedCount }}</b>
            <b v-if="diff.removedCount" class="del">删除 {{ diff.removedCount }}</b>
            <span v-if="!diff.changedCount && !diff.addedCount && !diff.removedCount">无步骤变更</span>
          </div>

          <p v-if="outline.goal" class="pv-goal">{{ outline.goal }}</p>

          <ol v-if="outline.steps.length" class="pv-steps">
            <li v-for="(s, si) in outline.steps" :key="si"
                :class="{ chg: marked('changed', si), add: marked('added', si) }">
              <span class="pv-no">{{ si + 1 }}</span>
              <span class="pv-step">
                <span class="pv-step-t">
                  {{ s.title }}
                  <span v-if="marked('changed', si)" class="pv-tag chg">已修改</span>
                  <span v-else-if="marked('added', si)" class="pv-tag add">新增</span>
                </span>
                <span v-if="s.detail" class="pv-step-s">{{ s.detail }}</span>
              </span>
            </li>
          </ol>

          <div v-if="diffOn && diff.removed.length" class="pv-del">
            已删除：<span v-for="(t, ti) in diff.removed" :key="ti"><s>{{ t }}</s><span v-if="ti < diff.removed.length - 1">、</span></span>
          </div>

          <div v-if="!outline.steps.length" class="md pv-fullmd"
               v-html="renderMd(planText(v.plan) || '（计划为空）', [])"></div>

          <div v-if="outline.steps.length && planText(v.plan)" class="pv-raw">
            <button type="button" class="pv-raw-toggle" @click="toggleRaw(true)">查看原始计划</button>
          </div>
          </template>
          </template>

          <!-- ---------- 执行页签：卡上看结果，这里看过程 ---------- -->
          <template v-else>
            <div v-if="progress" class="pv-exec-sum">
              <div class="pv-bar"><i :style="{ width: execPct + '%' }"></i></div>
              <div class="pv-exec-nums">
                <span><i class="pv-dot ok"></i>已完成 {{ progress.done }}</span>
                <span v-if="execDoing"><i class="pv-dot run"></i>进行中 {{ execDoing }}</span>
                <span v-if="execFailed"><i class="pv-dot fail"></i>失败 {{ execFailed }}</span>
                <span><i class="pv-dot"></i>等待 {{ execWait }}</span>
                <span class="pv-exec-total">{{ progress.done }} / {{ progress.total }}</span>
              </div>
            </div>

            <!-- 执行中调整：插话没有 stepId，不硬塞进步骤时间线；单独一块更诚实 -->
            <div v-if="adjusts.length" class="pv-adjusts">
              <div class="pv-adjusts-head">执行中调整 <span class="pv-adjusts-n">{{ adjusts.length }}</span></div>
              <div v-for="(a, ai) in adjusts" :key="ai" class="pv-adjust-row">
                <span class="pv-adjust-st">已送达</span>
                <span class="pv-adjust-t">{{ a.text }}</span>
              </div>
              <p class="pv-adjusts-note">只影响未执行部分，已完成的不重做。</p>
            </div>

            <ol v-if="exec.steps.length" class="pv-tl">
              <li v-for="s in exec.steps" :key="s.index" :class="'is-' + s.status">
                <span class="pv-tl-dot"></span>
                <div class="pv-tl-main">
                  <div class="pv-tl-head">
                    <b class="pv-tl-t">{{ s.index + 1 }} {{ s.text }}</b>
                    <span class="pv-tl-badge" :class="s.status">{{ stepBadge(s.status) }}</span>
                    <span v-if="s.elapsedMs" class="pv-tl-time">{{ dur(s.elapsedMs) }}</span>
                  </div>
                  <div v-if="s.tools.length || s.artifacts.length" class="pv-tl-tools">
                    <span v-for="(t, ti) in s.tools" :key="'t' + ti" class="pv-tchip" :class="t.status">
                      {{ t.label || t.name }}<em v-if="t.status === 'error'">失败</em>
                    </span>
                    <span v-for="(a, ai) in s.artifacts" :key="'a' + ai" class="pv-tchip out">{{ a.name }}</span>
                  </div>
                  <div v-else-if="s.status === 'wait'" class="pv-tl-note">{{ waitNoteOf(s.index) }}</div>
                </div>
              </li>
            </ol>

            <!-- 存量轮次没有 stepId：平铺工具，不按调用顺序硬塞进步骤 -->
            <div v-if="!exec.grouped && exec.tools.length" class="pv-other">
              <div class="pv-other-lab">工具调用 · 未按步骤归类</div>
              <div class="pv-tl-tools">
                <span v-for="(t, ti) in exec.tools" :key="ti" class="pv-tchip" :class="t.status">
                  {{ t.label || t.name }}<em v-if="t.status === 'error'">失败</em>
                </span>
              </div>
            </div>
            <div v-if="exec.others.length" class="pv-other">
              <div class="pv-other-lab">未归入步骤的工具调用</div>
              <div class="pv-tl-tools">
                <span v-for="(t, ti) in exec.others" :key="ti" class="pv-tchip" :class="t.status">
                  {{ t.label || t.name }}<em v-if="t.status === 'error'">失败</em>
                </span>
              </div>
            </div>
            <div v-if="exec.artifacts.length" class="pv-other">
              <div class="pv-other-lab">本轮产物 · {{ exec.artifacts.length }}</div>
              <div class="pv-tl-tools">
                <span v-for="(a, ai) in exec.artifacts" :key="ai" class="pv-tchip out">{{ a.name }}</span>
              </div>
            </div>

            <p v-if="!exec.steps.length && !exec.tools.length" class="pv-exec-empty">
              这一轮还没有可展示的执行记录。
            </p>
          </template>
        </div>

        <!-- 底栏：只对当前版给裁决；历史版给「回到当前版」 -->
        <footer class="pv-foot">
          <template v-if="tab === 'exec'">
            <div class="pv-foot-row">
              <span class="pv-hint">{{ execFootHint }}</span>
              <div class="pv-acts">
                <button v-if="adjustable" type="button" class="pv-btn ghost"
                        @click="emit('adjust')">调整计划</button>
                <button v-if="execRunning && !readOnly" type="button" class="pv-btn warn"
                        @click="emit('stop')">停止执行</button>
                <button type="button" class="pv-btn primary" @click="emit('close')">
                  {{ execRunning ? '后台运行' : '关闭' }}
                </button>
              </div>
            </div>
          </template>

          <template v-else-if="canAct">
            <div v-if="opinionOpen" class="pv-opinion">
              <textarea v-model="opinion" class="pv-ta" :maxlength="8000" rows="3"
                        :placeholder="'写清楚要改什么，例如：图表用环形图，不要柱状图'"
                        :disabled="busy" @keydown.esc.stop="opinionOpen = false"></textarea>
              <div class="pv-opinion-foot">
                <span class="pv-hint">同一轮内升到 v{{ chain.length + 1 }}</span>
                <div class="pv-acts">
                  <button type="button" class="pv-btn ghost" :disabled="busy" @click="opinionOpen = false">取消</button>
                  <button type="button" class="pv-btn ghost" :disabled="busy || !opinion.trim()"
                          title="本版作废，意见作为一条新消息发出"
                          @click="submitSupersede">转到对话修改</button>
                  <button type="button" class="pv-btn primary" v-if="!restored" :disabled="busy || !opinion.trim()"
                          @click="submitRevise">提交修改</button>
                </div>
              </div>
            </div>
            <div v-else class="pv-foot-row">
              <span class="pv-hint">{{ restored ? '恢复的计划接不上原轮次，意见作为新消息发出' : '确认后按此版执行；批准后可继续调整' }}</span>
              <div class="pv-acts">
                <button type="button" class="pv-btn ghost" @click="emit('close')">{{ restored ? '关闭' : '退出计划' }}</button>
                <button type="button" :class="['pv-btn', restored ? 'primary' : 'warn']" :disabled="busy"
                        @click="opinionOpen = true">{{ restored ? '转到对话修改' : '提修改意见' }}</button>
                <button v-if="!restored" type="button" class="pv-btn primary" :disabled="busy"
                        @click="emit('approve')">确认执行</button>
              </div>
            </div>
          </template>

          <div v-else-if="isHistorical" class="pv-foot-row">
            <span class="pv-hint">历史版本只读，裁决只对当前版生效</span>
            <button type="button" class="pv-btn primary" @click="pick(chain.length - 1)">回到当前版 v{{ chain.length }}</button>
          </div>

          <div v-else class="pv-foot-row">
            <span class="pv-hint">{{ readOnlyHint }}</span>
            <div class="pv-acts">
              <button v-if="adjustable" type="button" class="pv-btn primary"
                      @click="emit('adjust')">调整计划</button>
              <button type="button" class="pv-btn" :class="{ ghost: adjustable }"
                      @click="emit('close')">关闭</button>
            </div>
          </div>
        </footer>
      </aside>
    </div>
  </teleport>
</template>

<script setup>
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { CloseOutlined } from '@ant-design/icons-vue'
import { renderMd } from '../utils/markdown'
import { planOutline, planChain, diffSteps, planExecDetail } from '../chat/plan'
import { planViewerVersionOf, setPlanViewerVersion } from '../chat/planUiState'

const props = defineProps({
  open: { type: Boolean, default: false },
  /** 计划卡记录（同一个对象，状态会随裁决/流式更新） */
  card: { type: Object, default: null },
  sid: { type: String, default: '' },
  pkey: { type: [String, Number], default: '' },
  /** 这条计划前面的用户问题，用作抽屉标题 */
  title: { type: String, default: '' },
  /** 只读（分享页 / 已收尾的计划）：分类文案 */
  readOnly: { type: Boolean, default: false },
  /** 打开时定位到某一版（-1/缺省=上次看的那版，再缺省=当前版） */
  version: { type: Number, default: -1 },
  /** 打开时落在哪个页签：'plan'（默认）或 'exec'（从卡脚「查看执行详情」进来） */
  initialTab: { type: String, default: 'plan' },
  /** 本轮助手消息：执行页签的工具调用与产物都挂在它上面 */
  msg: { type: Object, default: null },
  /** 执行进度（planExecProgress 的产物）：步骤与状态以任务清单为准 */
  progress: { type: Object, default: null },
  /** 本轮调整记录（执行中插话）：[{text,messageId}]；执行页签并列展示 */
  adjusts: { type: Array, default: () => [] },
  /** 待确认倒计时（与卡片、底部确认栏同源同值） */
  countdown: { type: String, default: '' },
  expired: { type: Boolean, default: false }
})
const emit = defineEmits(['close', 'approve', 'revise', 'supersede', 'locate', 'stop',
                          'adjust', 'locate-source'])

const closeBtn = ref(null)
const bodyEl = ref(null)
const sel = ref(0)
const diffOn = ref(true)
const opinion = ref('')
const opinionOpen = ref(false)
const tab = ref('plan')
/** 原始计划独屏：打开后结构区整块隐藏，正文从顶部开始读 */
const rawMode = ref(false)

const chain = computed(() => planChain(props.card))
const busy = computed(() => !!(props.card && props.card.busy))
const v = computed(() => chain.value[sel.value] || { plan: '', feedback: '' })
const planText = p => typeof p === 'string' ? p : (p == null ? '' : JSON.stringify(p, null, 2))
const isCurrent = computed(() => sel.value === chain.value.length - 1)
const isHistorical = computed(() => !isCurrent.value)
const canAct = computed(() => props.card && props.card.status === 'pending' && isCurrent.value && !props.readOnly)
/** 恢复的计划卡：本设备没有原来那一轮的流，只能把意见作为新消息发出（与底部确认栏同一口径） */
const restored = computed(() => !!(props.card && props.card.restored))
const outline = computed(() => planOutline(planText(v.value.plan)))
const prevPlan = computed(() => sel.value > 0 ? planText(chain.value[sel.value - 1].plan) : '')
const diff = computed(() => diffSteps(prevPlan.value, planText(v.value.plan)))
const marked = (kind, i) => !!(diffOn.value && sel.value > 0 && diff.value[kind].has(i))

/** 已重新规划（旧卡保留执行记录）：卡头状态要与执行事实一致，不能还挂「已批准」 */
const replanned = computed(() => !!(props.card && (props.card.replannedBy || props.card.replannedAt)))
const stateText = computed(() => {
  if (props.card?.revising) return props.card.revisingStarted ? '生成中…' : '修改中…'
  if (replanned.value) {
    const p = props.progress
    if (p && p.total && p.done >= p.total) return '已完成 · 已重新规划'
    if (p && p.failed) return '未完成 · 已重新规划'
    if (p && p.total) return '已停止 · 已重新规划'
    return '已重新规划'
  }
  return {
    drafting: '生成中…', pending: '待确认', approved: '已批准',
    superseded: '已被取代', rejected: '未批准', stopped: '已停止'
  }[props.card?.status] || ''
})
const stateTone = computed(() => {
  if (props.card?.revising) return props.card.revisingStarted ? 'drafting' : 'pending'
  if (replanned.value) return 'muted'
  return {
    drafting: 'drafting', pending: 'pending', approved: 'ok',
    superseded: 'muted', rejected: 'muted', stopped: 'muted'
  }[props.card?.status] || 'muted'
})
/** 调整入口：已批准、本机接得上、非只读、且还没被重新规划 */
const adjustable = computed(() => !!(props.card && props.card.status === 'approved'
  && !props.card.restored && !props.readOnly && !replanned.value))
const readOnlyHint = computed(() => {
  if (replanned.value) return '这一版已停止执行并按新意见重新规划；执行记录与产物保留'
  const st = props.card?.status
  if (st === 'approved') {
    if (props.card?.restored) return '这张计划是恢复的，请回到发起它的设备调整'
    return '计划正文已锁；可以调整执行方向，或重新计划'
  }
  if (st === 'superseded') return '这一版已被取代，只保留修改记录'
  if (st === 'rejected') return '这一版未获批准，本轮已停止'
  if (st === 'stopped') return '本轮已停止，这一版没有走到批准'
  return '只读'
})
/** 卡头血缘标：新计划基于哪一版、带过来多少执行进度 */
const lineageLabel = computed(() => {
  const f = props.card && props.card.adjustFrom
  if (!f || !f.cardId) return ''
  const v = Number(f.version) || 1
  const total = Number(f.total) || 0
  const done = Number(f.done) || 0
  return total > 0 ? `调整自 v${v} · 已执行 ${done}/${total}` : `调整自 v${v}`
})

// ==================== 执行页签 ====================
/** 只有批准过的计划才有执行过程可看（待确认的计划不存在「执行」） */
const hasExec = computed(() => props.card?.status === 'approved')
const exec = computed(() => planExecDetail(props.progress, props.msg?.toolCalls, props.msg?.artifacts))
const execRunning = computed(() => !!(props.progress && props.progress.running))
const execDoing = computed(() => {
  const items = (props.progress && props.progress.items) || []
  return items.filter(i => i.status === 'doing').length
})
const execWait = computed(() => {
  const items = (props.progress && props.progress.items) || []
  return items.filter(i => i.status === 'wait').length
})
const execFailed = computed(() => {
  const items = (props.progress && props.progress.items) || []
  return items.filter(i => i.status === 'failed').length
})
const execPct = computed(() => {
  const p = props.progress
  if (!p || !p.total) return 0
  return Math.max(0, Math.min(100, Math.round((p.done / p.total) * 100)))
})
const execFootHint = computed(() => {
  if (replanned.value) return '这一版已按新意见重新规划；执行记录与产物保留'
  if (execRunning.value) return '执行在后台进行，关掉这个抽屉不会停止本轮'
  if (execFailed.value) return `有 ${execFailed.value} 个步骤失败：可直接在输入框说明后让它重试`
  if (props.progress && props.progress.total && props.progress.done >= props.progress.total) return '执行已完成，记录已保留'
  return '当前没有正在进行的执行'
})
const stepBadge = s => ({ done: '已完成', doing: '进行中', wait: '等待', failed: '失败' }[s] || '')
/** 等待项的说明：前面有步骤失败时不能说「等前一步完成」——那一步已经不会完成了 */
const waitNoteOf = i => {
  const items = (props.progress && props.progress.items) || []
  for (let k = 0; k < i; k++) {
    if (items[k] && items[k].status === 'failed') return '前一步失败，先重试才能继续'
  }
  return '等待前一步完成'
}
/** 毫秒 → m:ss（0 不显示，避免「0:00」这种看似没跑的时长） */
const dur = ms => {
  const n = Number(ms) || 0
  if (n <= 0) return ''
  const sec = Math.round(n / 1000)
  return `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`
}

function switchTab (t) {
  if (t === 'exec' && !hasExec.value) return
  tab.value = t
  rawMode.value = false
  if (t === 'plan') opinionOpen.value = false
  if (bodyEl.value) bodyEl.value.scrollTop = 0
}

/** 切到原始计划/回到结构视图：先把视图换掉再从正文顶部开始读 */
async function toggleRaw (on) {
  rawMode.value = on
  opinionOpen.value = false
  await nextTick()
  if (bodyEl.value) bodyEl.value.scrollTop = 0
}

function pick (i) {
  const n = chain.value.length
  if (i < 0 || i >= n) return
  sel.value = i
  opinionOpen.value = false
  rawMode.value = false
  setPlanViewerVersion(props.sid, props.pkey, i)
  if (bodyEl.value) bodyEl.value.scrollTop = 0
}

function reset () {
  const n = chain.value.length
  const remembered = planViewerVersionOf(props.sid, props.pkey)
  const want = props.version >= 0 ? props.version : (remembered >= 0 ? remembered : n - 1)
  sel.value = Math.max(0, Math.min(n - 1, want))
  diffOn.value = true
  opinion.value = ''
  opinionOpen.value = false
  rawMode.value = false
  tab.value = (props.initialTab === 'exec' && hasExec.value) ? 'exec' : 'plan'
  if (bodyEl.value) bodyEl.value.scrollTop = 0
}

watch(() => props.open, async open => {
  if (!open) return
  reset()
  await nextTick()
  closeBtn.value?.focus?.()
}, { immediate: true })

// 版本链在打开期间变长（模型修订回来）：停在原版，不跳；用户本来就在等这一版的话跟随到最新
watch(() => chain.value.length, (n, prevN) => {
  if (!props.open) return
  if (sel.value === prevN - 1 && n > prevN) sel.value = n - 1
})

function submitRevise () {
  const t = opinion.value.trim()
  if (!t || busy.value) return
  emit('revise', t)
  opinion.value = ''
  opinionOpen.value = false
}
function submitSupersede () {
  const t = opinion.value.trim()
  if (!t || busy.value) return
  emit('supersede', t)
  opinion.value = ''
  opinionOpen.value = false
}

// 遮罩打开时锁背景滚动；关闭 / Esc 一并还原
let prevOverflow = ''
watch(() => props.open, open => {
  if (open) {
    prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
  } else {
    document.body.style.overflow = prevOverflow || ''
  }
}, { immediate: true })

function onKey (e) {
  if (!props.open) return
  if (e.key === 'Escape') {
    e.stopPropagation()
    if (opinionOpen.value) opinionOpen.value = false
    else emit('close')
  }
}
window.addEventListener('keydown', onKey, true)
onUnmounted(() => {
  window.removeEventListener('keydown', onKey, true)
  document.body.style.overflow = prevOverflow || ''
})
</script>

<style scoped>
.pv-mask {
  position: fixed; inset: 0; z-index: 1100;
  background: rgba(16, 24, 40, .28);
  display: flex; justify-content: flex-end;
}
.pv-drawer {
  width: 560px; max-width: 94vw; height: 100%;
  background: var(--app-panel, #fff);
  border-left: 1px solid var(--app-border-strong, #d8dde3);
  box-shadow: -18px 0 50px rgba(16, 24, 40, .16);
  display: flex; flex-direction: column;
  animation: pv-in .22s ease-out;
}
@keyframes pv-in { from { transform: translateX(22px); opacity: .6; } to { transform: none; opacity: 1; } }

.pv-head { display: flex; align-items: flex-start; gap: 12px; padding: 18px 18px 12px; border-bottom: 1px solid var(--app-border); }
.pv-head-main { min-width: 0; }
.pv-eyebrow { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 10px; letter-spacing: .12em; color: var(--app-warn-text); text-transform: uppercase; }
.pv-title { margin: 5px 0 0; font-size: 16px; line-height: 1.4; font-weight: 650; color: var(--app-text); overflow-wrap: anywhere; }
.pv-sub { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 7px; font-size: 11.5px; color: var(--app-text3); }
.pv-state { font-weight: 600; }
.pv-state.st-pending { color: var(--app-warn-text); }
.pv-state.st-ok { color: var(--app-ok); }
.pv-state.st-drafting, .pv-state.st-muted { color: var(--app-text3); }
.pv-rev, .pv-auto { padding-left: 8px; border-left: 1px solid var(--app-border); }
.pv-timer { margin-left: auto; display: inline-flex; align-items: center; gap: 4px; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 10.5px; color: var(--app-text2); font-variant-numeric: tabular-nums; }
.pv-timer.warn { color: var(--app-warn); }
.pv-src { border: 1px solid var(--app-accent-border); background: var(--app-accent-weak); color: var(--app-accent);
  border-radius: 4px; padding: 1px 6px; font-size: 10.5px; line-height: 1.5; cursor: pointer; }
.pv-src:hover { border-color: var(--app-accent); }
.pv-close { flex: none; margin-left: auto; width: 28px; height: 28px; border: 1px solid var(--app-border); border-radius: 7px; background: var(--app-panel); color: var(--app-text2); cursor: pointer; display: flex; align-items: center; justify-content: center; }
.pv-close:hover { color: var(--app-accent); border-color: var(--app-accent); }

.pv-vers { display: flex; align-items: center; gap: 4px; padding: 9px 18px; border-bottom: 1px solid var(--app-border); overflow-x: auto; }
.pv-vbtn { flex: none; display: inline-flex; align-items: baseline; gap: 4px; border: 1px solid var(--app-border); background: var(--app-panel); border-radius: 6px; padding: 3px 7px; font-size: 10.5px; color: var(--app-text3); cursor: pointer; white-space: nowrap; }
.pv-vbtn b { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 10.5px; color: var(--app-text2); }
.pv-vbtn:hover { border-color: var(--app-accent); }
.pv-vbtn.on { border-color: var(--app-accent-border); background: var(--app-accent-weak); color: var(--app-accent); }
.pv-vbtn.on b { color: var(--app-accent); }
.pv-vers-line { flex: 1; min-width: 8px; height: 1px; background: var(--app-border); }
.pv-diff { flex: none; border: 1px solid var(--app-border); background: var(--app-panel); border-radius: 6px; padding: 3px 8px; font-size: 10.5px; color: var(--app-text2); cursor: pointer; white-space: nowrap; }
.pv-diff.on { border-color: var(--app-warn-border); background: var(--app-warn-weak); color: var(--app-warn-text); }
.pv-diff:disabled { opacity: .5; cursor: default; }

.pv-body { flex: 1; min-height: 0; overflow-y: auto; padding: 14px 18px 18px; }
.pv-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.pv-chip { font-size: 10.5px; padding: 2px 7px; border-radius: 999px; border: 1px solid var(--app-border); color: var(--app-text3); background: var(--app-panel-2); }
.pv-chip.cur { border-color: var(--app-warn-border); background: var(--app-warn-weak); color: var(--app-warn-text); }
.pv-meta-note { font-size: 11px; color: var(--app-text3); }

.pv-fb { margin-bottom: 12px; padding: 9px 11px; border: 1px solid var(--app-border); border-left: 2px solid var(--app-accent); border-radius: 0 7px 7px 0; background: var(--app-panel-2); }
.pv-fb-lab { font-size: 10.5px; font-weight: 600; color: var(--app-text2); }
.pv-fb p { margin: 4px 0 0; font-size: 12.5px; line-height: 1.7; color: var(--app-text); white-space: pre-wrap; overflow-wrap: anywhere; }
.pv-fb-jump { margin-top: 5px; border: 0; background: none; padding: 0; font-size: 11px; color: var(--app-accent); cursor: pointer; }

.pv-sum { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; padding: 7px 10px; border: 1px dashed var(--app-border-strong); border-radius: 7px; font-size: 11px; color: var(--app-text3); }
.pv-sum-lab { color: var(--app-text2); font-weight: 600; }
.pv-sum .chg, .pv-sum .add, .pv-sum .del { font-weight: 700; }
.pv-sum .chg { color: var(--app-warn-text); }
.pv-sum .add { color: var(--app-ok); }
.pv-sum .del { color: var(--app-danger, #cf1322); }

.pv-goal { margin: 0 0 9px; font-size: 12.5px; line-height: 1.65; color: var(--app-text2); }
.pv-steps { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 2px; }
.pv-steps li { display: flex; gap: 9px; align-items: flex-start; min-width: 0; padding: 6px 8px; border-radius: 6px; }
.pv-steps li.chg { background: var(--app-warn-weak); }
.pv-steps li.add { background: var(--app-ok-weak); }
.pv-no { flex: none; width: 18px; height: 18px; margin-top: 1px; border-radius: 50%; border: 1px solid var(--app-border); background: var(--app-panel); color: var(--app-text3); font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 10px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
.pv-step { min-width: 0; display: flex; flex-direction: column; }
.pv-step-t { font-size: 13px; line-height: 1.55; font-weight: 600; color: var(--app-text); }
.pv-step-s { margin-top: 1px; font-size: 11.5px; line-height: 1.6; color: var(--app-text3); }
.pv-tag { margin-left: 5px; font-size: 9.5px; font-weight: 600; padding: 0 5px; border-radius: 4px; vertical-align: 1px; }
.pv-tag.chg { color: var(--app-warn-text); background: var(--app-warn-weak); border: 1px solid var(--app-warn-border); }
.pv-tag.add { color: var(--app-ok); background: var(--app-ok-weak); border: 1px solid var(--app-ok-border, #cfe4d3); }
.pv-del { margin-top: 9px; padding: 7px 9px; border-radius: 6px; background: var(--app-panel-2); font-size: 11px; color: var(--app-text3); }
.pv-del s { color: var(--app-danger, #cf1322); }
.pv-fullmd { margin-top: 4px; font-size: 12.5px; }
.pv-raw { margin-top: 14px; padding-top: 10px; border-top: 1px dashed var(--app-border); }
.pv-raw-toggle { border: 0; background: none; padding: 0; font-size: 11.5px; color: var(--app-text3); cursor: pointer; }
.pv-raw-toggle:hover { color: var(--app-accent); }
/* 原始计划独屏：标题行 + 全文，正文自己滚（不再套 280px 小窗） */
.pv-raw-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.pv-raw-lab { font-size: 10.5px; font-weight: 600; letter-spacing: .04em; color: var(--app-text3); }
.pv-raw-back { margin-left: auto; border: 1px solid var(--app-border); background: var(--app-panel); border-radius: 6px; padding: 2px 8px; font-size: 11px; color: var(--app-text2); cursor: pointer; white-space: nowrap; }
.pv-raw-back:hover { border-color: var(--app-accent); color: var(--app-accent); }
.pv-raw-doc { margin-top: 0; }

/* ---------- 页签 ---------- */
.pv-tabs { display: flex; gap: 2px; padding: 0 18px; border-bottom: 1px solid var(--app-border); background: var(--app-panel); }
.pv-tab { border: 0; background: none; padding: 8px 11px 7px; font-size: 12.5px; color: var(--app-text3); cursor: pointer; border-bottom: 2px solid transparent; margin-bottom: -1px; white-space: nowrap; }
.pv-tab:hover { color: var(--app-text2); }
.pv-tab.on { color: var(--app-accent); border-bottom-color: var(--app-accent); font-weight: 600; }
.pv-tab-n { margin-left: 4px; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 11px; }

/* ---------- 执行页签 ---------- */
.pv-exec-sum { margin-bottom: 14px; }

/* 执行中调整：单独一块，不混进步骤时间线（插话没有 stepId，硬塞会伪造归属） */
.pv-adjusts { margin-bottom: 14px; border: 1px solid var(--app-accent-border); background: var(--app-accent-weak);
  border-radius: 8px; padding: 9px 10px; }
.pv-adjusts-head { display: flex; align-items: center; gap: 5px; font-size: 11px; font-weight: 600; color: var(--app-accent); }
.pv-adjusts-n { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 10px; }
.pv-adjust-row { display: flex; align-items: flex-start; gap: 7px; margin-top: 6px; font-size: 11.5px; line-height: 1.6; color: var(--app-text); }
.pv-adjust-st { flex: none; font-size: 10px; color: var(--app-ok); }
.pv-adjust-t { min-width: 0; overflow-wrap: anywhere; }
.pv-adjusts-note { margin: 7px 0 0; font-size: 10.5px; line-height: 1.6; color: var(--app-text3); }
.pv-bar { height: 4px; border-radius: 999px; background: var(--app-panel-2); overflow: hidden; }
.pv-bar i { display: block; height: 100%; border-radius: 999px; background: var(--app-ok); transition: width .3s ease; }
.pv-exec-nums { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-top: 7px; font-size: 11px; color: var(--app-text3); }
.pv-exec-nums span { display: inline-flex; align-items: center; gap: 4px; }
.pv-exec-total { margin-left: auto; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; color: var(--app-text2); font-weight: 600; }
.pv-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--app-border-strong); }
.pv-dot.ok { background: var(--app-ok); }
.pv-dot.run { background: var(--app-warn-text); box-shadow: 0 0 0 2px var(--app-warn-weak); }
.pv-dot.fail { background: var(--app-danger, #cf1322); }

.pv-tl { list-style: none; margin: 0; padding: 0; position: relative; }
.pv-tl li { position: relative; display: flex; gap: 10px; padding: 0 0 14px 0; }
/* 竖线：最后一项不连（用伪元素画在圆点列上，圆点自己盖住起点） */
.pv-tl li:not(:last-child)::before { content: ''; position: absolute; left: 5px; top: 14px; bottom: 0; width: 1px; background: var(--app-border); }
.pv-tl-dot { flex: none; width: 11px; height: 11px; margin-top: 4px; border-radius: 50%; border: 1.5px solid var(--app-border-strong); background: var(--app-panel); position: relative; z-index: 1; }
.pv-tl li.is-done .pv-tl-dot { border-color: var(--app-ok); background: var(--app-ok); }
.pv-tl li.is-doing .pv-tl-dot { border-color: var(--app-warn-text); background: #fff; box-shadow: 0 0 0 3px var(--app-warn-weak); }
.pv-tl li.is-failed .pv-tl-dot { border-color: var(--app-danger, #cf1322); background: var(--app-danger, #cf1322); }
.pv-tl-main { flex: 1; min-width: 0; }
.pv-tl-head { display: flex; align-items: baseline; gap: 7px; flex-wrap: wrap; }
.pv-tl-t { font-size: 12.5px; line-height: 1.55; font-weight: 600; color: var(--app-text); overflow-wrap: anywhere; }
.pv-tl li.is-wait .pv-tl-t { color: var(--app-text3); font-weight: 500; }
.pv-tl li.is-failed .pv-tl-t { color: var(--app-danger, #cf1322); }
.pv-tl-badge { flex: none; font-size: 9.5px; font-weight: 600; padding: 0 5px; border-radius: 4px; border: 1px solid var(--app-border); color: var(--app-text3); }
.pv-tl-badge.done { border-color: var(--app-ok-border, #cfe4d3); background: var(--app-ok-weak); color: var(--app-ok); }
.pv-tl-badge.doing { border-color: var(--app-warn-border); background: var(--app-warn-weak); color: var(--app-warn-text); }
.pv-tl-badge.failed { border-color: var(--app-danger, #cf1322); background: #fff1f0; color: var(--app-danger, #cf1322); }
.pv-tl-time { margin-left: auto; flex: none; font-size: 10.5px; color: var(--app-text3); font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; }
.pv-tl-tools { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 5px; }
.pv-tl-note { margin-top: 3px; font-size: 11px; color: var(--app-text3); }
.pv-tchip { display: inline-flex; align-items: center; gap: 4px; max-width: 100%; font-size: 10.5px; line-height: 1.5; padding: 1px 7px; border-radius: 5px; border: 1px solid var(--app-border); background: var(--app-panel-2); color: var(--app-text2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pv-tchip.done { border-color: var(--app-ok-border, #cfe4d3); background: var(--app-ok-weak); color: var(--app-ok); }
.pv-tchip.start { border-color: var(--app-warn-border); background: #fffaf0; color: var(--app-warn-text); }
.pv-tchip.error { border-color: var(--app-danger, #cf1322); color: var(--app-danger, #cf1322); background: #fff1f0; }
.pv-tchip.out { border-color: var(--app-accent-weak, #dbe7ff); background: var(--app-accent-weak, #eff4ff); color: var(--app-accent); }
.pv-tchip em { font-style: normal; font-weight: 700; }
.pv-other { margin-top: 14px; }
.pv-other-lab { margin-bottom: 5px; font-size: 10.5px; font-weight: 600; color: var(--app-text3); }
.pv-exec-empty { margin: 18px 0; font-size: 12.5px; color: var(--app-text3); text-align: center; }

.pv-foot { border-top: 1px solid var(--app-border); padding: 11px 18px calc(11px + env(safe-area-inset-bottom, 0px)); background: var(--app-panel); }
.pv-foot-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.pv-hint { font-size: 11.5px; color: var(--app-text3); }
.pv-acts { margin-left: auto; display: flex; align-items: center; gap: 7px; }
.pv-btn { border: 1px solid var(--app-border); background: var(--app-panel); border-radius: 7px; padding: 5px 12px; font-size: 12.5px; color: var(--app-text2); cursor: pointer; white-space: nowrap; }
.pv-btn:hover { border-color: var(--app-accent); color: var(--app-accent); }
.pv-btn.primary { background: var(--app-accent); border-color: var(--app-accent); color: #fff; }
.pv-btn.primary:hover { background: var(--app-accent-hover); border-color: var(--app-accent-hover); color: #fff; }
.pv-btn.warn { border-color: var(--app-warn-border); background: var(--app-warn-weak); color: var(--app-warn-text); font-weight: 600; }
.pv-btn:disabled { opacity: .45; cursor: not-allowed; }
.pv-opinion { display: flex; flex-direction: column; gap: 8px; }
.pv-ta { width: 100%; box-sizing: border-box; resize: vertical; min-height: 64px; border: 1px solid var(--app-border); border-radius: 7px; padding: 8px 10px; font: inherit; font-size: 13px; line-height: 1.6; color: var(--app-text); background: var(--app-panel); }
.pv-ta:focus { outline: none; border-color: var(--app-accent); }
.pv-opinion-foot { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }

@media (max-width: 720px) {
  .pv-mask { background: rgba(16, 24, 40, .4); }
  .pv-drawer { width: 100%; max-width: none; border-left: 0; animation: pv-up .22s ease-out; }
  @keyframes pv-up { from { transform: translateY(18px); opacity: .7; } to { transform: none; opacity: 1; } }
  .pv-head { padding: 14px 14px 10px; }
  .pv-vers { padding: 8px 14px; }
  .pv-body { padding: 12px 14px 16px; }
  .pv-foot { padding: 10px 14px calc(10px + env(safe-area-inset-bottom, 0px)); }
  .pv-foot-row .pv-hint { flex: 1 1 100%; }
  .pv-acts { margin-left: 0; width: 100%; justify-content: flex-end; }
  .pv-btn { min-height: 36px; }
}
</style>
