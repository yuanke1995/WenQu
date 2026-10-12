<template>
  <!-- 执行过程（事件账本）：按轮列出这一会话跑过什么——工具步与耗时、审批/提问/计划三张卡、
       产物、子智能体编排、停止与失败。这份记录不参与智能体的回答，只服务事后回看与排障。
       PC 用模态框、移动壳用底部 sheet 装载，取数与渲染只有这一份（两端不许各写一遍）。
       按轮回放时可以「对照正文」：把那一轮的回答与它的账本并排放，回答「为什么是这么答的」。 -->
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
  <div v-else>
    <div class="ev-bar">
      <span class="ev-tip">
        <template v-if="turnId">这一轮共 {{ turns[0].count }} 条，按发生顺序。</template>
        <template v-else>共 {{ turns.length }} 轮 · 只列最近若干轮，按发生顺序。点开每一轮看它跑了哪些步。</template>
      </span>
      <!-- 对照正文只在「看这一轮」时才有意义：会话级列表对着哪一条回答？ -->
      <button v-if="canCompare" class="ev-retry" type="button" @click="compare = !compare">
        {{ compare ? '只看过程' : '对照正文' }}
      </button>
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
          <li v-for="(s, i) in steps" :key="'s' + i" :class="{ hit: active === i }" @click="focus(i, 'answer')">
            <span class="ev-no">{{ i + 1 }}</span>
            <span class="ev-step-name">{{ s.name }}</span>
            <span class="ev-step-state" :class="s.status === 'error' ? 'bad' : 'ok'">{{ stepState(s.status) }}</span>
            <span v-if="s.elapsedMs" class="ev-step-ms">{{ s.elapsedMs }}ms</span>
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
            <span class="ev-count">{{ t.count }} 条</span>
            <span v-if="t.endedAt > t.startedAt" class="ev-dur">{{ ((t.endedAt - t.startedAt) / 1000).toFixed(1) }}s</span>
            <span class="ev-types">{{ evTypeSummary(t) }}</span>
          </button>
          <div v-if="t._open" class="ev-list">
            <div v-for="e in t.events" :key="e.id" class="ev-row"
                 :class="{ clickable: e._step != null, hit: e._step != null && active === e._step }"
                 @click="e._step != null && focus(e._step, 'ledger')">
              <span class="ev-type">
                <span v-if="e._step != null" class="ev-no">{{ e._step + 1 }}</span>{{ evTypeLabel(e.type) }}
              </span>
              <span class="ev-at">{{ fmtEvClock(e.at) }}</span>
              <span class="ev-sum" :title="e.summary">{{ e.summary }}</span>
            </div>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { CaretRightOutlined } from '@ant-design/icons-vue'
import { getSessionEvents } from '../api'
import AnswerBody from './AnswerBody.vue'

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

/** 给账本里的工具终态行编号：第 i 个终态工具事件 ↔ 回答里第 i 个工具步 */
function numberToolEvents (list) {
  let i = 0
  for (const e of list) {
    if (e.type === 'tool' && e.status && e.status !== 'start') {
      e._step = i++
    } else {
      e._step = null
    }
  }
  return list
}

function focus (i) {
  active.value = active.value === i ? -1 : i
}

async function load () {
  const sid = props.sessionId
  const turn = props.turnId || ''
  if (!sid) { err.value = '还没有选中会话'; turns.value = []; return }
  const my = ++seq
  loading.value = true
  err.value = ''
  turns.value = []
  try {
    const r = await getSessionEvents(sid, props.turns, turn)
    if (my !== seq) return
    const list = (r && r.data && Array.isArray(r.data.turns)) ? r.data.turns : []
    // 按轮进来只有一条，直接摊开——用户点的就是这一轮的过程，不该再多点一下
    if (turn) list.forEach(t => { t._open = true })
    list.forEach(t => { numberToolEvents(t.events || []) })
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

const EV_TYPE_LABEL = {
  tool: '工具', approval: '工具审批', approval_required: '工具待批准', ask_user: '提问卡',
  plan: '本轮步骤', plan_approval: '计划待批准', plan_cancelled: '计划未批准',
  plan_superseded: '计划被取代', artifact: '产物', subagent: '子智能体',
  subagent_route: '派遣路由', agent: '智能体', agent_bound: '绑定智能体',
  agent_dispatched: '自动派遣', agent_delegated: '委派作答',
  retrieved: '检索', usage: '用量', todo: '任务清单', error: '失败', warn: '提醒',
  steer: '中途插话', stop: '用户停止', interrupted: '中断收尾', detach: '断线中止',
  done: '本轮结束'
}
const evTypeLabel = t => EV_TYPE_LABEL[t] || t
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
/** 折叠头的一行小计：工具×6 · 检索 · 产物（让人不展开也知道这轮跑了什么量级） */
function evTypeSummary (t) {
  const counts = {}
  for (const e of (t.events || [])) counts[e.type] = (counts[e.type] || 0) + 1
  return Object.keys(counts).slice(0, 5).map(k => evTypeLabel(k) + (counts[k] > 1 ? '×' + counts[k] : '')).join(' · ')
}
</script>

<style scoped>
/* 执行过程（事件账本）：一轮一行，展开按序列出事件摘要 */
.ev-bar { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.ev-tip { font-size: 12px; color: var(--app-text-secondary); margin-bottom: 8px; line-height: 1.6; }
.ev-retry {
  flex: 0 0 auto; margin-left: 6px; padding: 2px 10px; font-size: 12px; cursor: pointer;
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
.ev-types { margin-left: auto; color: var(--app-text-secondary); font-size: 11px; }
.ev-list { border-top: 1px solid var(--app-border); padding: 4px 10px 8px; }
.ev-row { display: flex; gap: 8px; align-items: baseline; padding: 3px 0; font-size: 12px; border-radius: 6px; }
.ev-row.clickable { cursor: pointer; }
.ev-row.clickable:hover { background: var(--app-bg); }
.ev-row.hit { background: var(--app-accent-weak); }
.ev-type { flex: 0 0 88px; color: var(--app-accent); }
.ev-at { flex: 0 0 62px; color: var(--app-text-secondary); font-variant-numeric: tabular-nums; }
.ev-sum {
  flex: 1; min-width: 0; color: var(--app-text); word-break: break-all;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
</style>
