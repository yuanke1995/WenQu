<template>
  <!-- 一支子智能体运行的「完整执行流」：关键帧逐行铺开（工具步入参/结果可展开、检索、产物、
       降级提醒、待裁决）。两个消费方共用这一个组件：派遣卡里的完整过程抽屉（直播吃 _frames、
       终态吃账本回放）与过程回放面板的 subagent 聚合块——渲染只有这一份，不许两处各写一遍。
       数据形 {t: 帧类型, c: 原样载荷文本}，与后端落账的 frames 逐字同构。 -->
  <div class="srf" :style="{ maxHeight: maxHeight + 'px' }">
    <div class="srf-head">
      <span>完整执行流 · {{ rows.length }} 帧</span>
      <span v-if="live" class="srf-live"><span class="srf-live-dot"></span>直播</span>
      <span v-else class="srf-badge">回放 · 会话账本</span>
      <span class="srf-meta">{{ live ? '实时转发帧 · 刷新后以回放为准' : '刷新 / 换设备同构' }}</span>
    </div>
    <div v-if="!rows.length" class="srf-empty">这一支还没有可展示的过程记录{{ live ? '' : '（账本按天保留，可能已超期）' }}</div>
    <template v-for="(r, i) in rows" :key="i">
      <!-- 工具步：点行展开入参/结果 -->
      <div v-if="r.kind === 'tool'" class="srf-fr">
        <button class="srf-row" type="button" @click="toggle(i)">
          <loading-outlined v-if="r.st === 'start'" spin class="srf-ic srf-ic-acc" />
          <check-outlined v-else-if="r.st !== 'error'" class="srf-ic srf-ic-ok" />
          <close-circle-outlined v-else class="srf-ic srf-ic-err" />
          <span class="srf-name">{{ toolLabel(r.name, r.label) }}</span>
          <code v-if="r.brief" class="srf-brief">{{ r.brief }}</code>
          <span v-if="r.denied" class="srf-flag">被挡下</span>
          <span v-if="r.ms" class="srf-ms">{{ toolDuration(r.ms) }}</span>
          <caret-right-outlined class="srf-caret" :class="{ open: !!open[i] }" />
        </button>
        <div v-if="open[i]" class="srf-detail">
          <template v-if="r.args">
            <div class="srf-io-label">入参</div>
            <pre class="srf-io">{{ r.args }}</pre>
          </template>
          <template v-if="r.result">
            <div class="srf-io-label">结果</div>
            <pre class="srf-io">{{ r.result }}</pre>
          </template>
          <template v-if="r.error">
            <div class="srf-io-label">错误</div>
            <pre class="srf-io srf-io-err">{{ r.error }}</pre>
          </template>
        </div>
      </div>
      <!-- 检索：检索词一览 -->
      <div v-else-if="r.kind === 'retrieved'" class="srf-plain">
        <file-search-outlined class="srf-ic srf-ic-acc" />
        <span>{{ r.text }}</span>
      </div>
      <!-- 产物 -->
      <div v-else-if="r.kind === 'artifact'" class="srf-plain">
        <paper-clip-outlined class="srf-ic srf-ic-acc" />
        <span class="srf-art">{{ r.text }}</span>
        <span v-if="r.size" class="srf-sub">{{ fmtSize(r.size) }}</span>
      </div>
      <!-- 降级/提醒 -->
      <div v-else-if="r.kind === 'warn'" class="srf-warn">
        <warning-outlined class="srf-ic" />
        <span>{{ r.text }}</span>
      </div>
      <!-- 待裁决（审批/提问）：这一帧意味着子轮正卡在人手上 -->
      <div v-else-if="r.kind === 'hold'" class="srf-hold">
        <question-circle-outlined class="srf-ic" />
        <span>{{ r.text }}</span>
      </div>
      <!-- 未知帧型：原样给一段截断文本，不静默吞 -->
      <div v-else class="srf-plain srf-raw">
        <span>{{ r.text }}</span>
      </div>
    </template>
  </div>
</template>

<script setup>
// 帧缓冲在两路是同源的：直播 = 引擎侧 subagent_step 累积的 _frames；回放 = 后端收束时落账的
// subagent 事件 payload.frames（GET /session/{id}/events 的 subagent 行带全量 payload）。
// 同一帧可能经「SSE 直发 + 总线扇出」各到一遍，上游已按 类型+内容 去重；这里再做一层
// start/终态配对：同一把工具的 start 行被终态行原位替换，不会出现一 tool 两行。
import { computed, reactive } from 'vue'
import { CheckOutlined, CloseCircleOutlined, LoadingOutlined, CaretRightOutlined,
         FileSearchOutlined, PaperClipOutlined, WarningOutlined, QuestionCircleOutlined } from '@ant-design/icons-vue'
import { toolLabel, toolDuration, prettyIo } from '../chat/projections'

const props = defineProps({
  /** 帧数组：[{t: 帧类型, c: 原样载荷文本}] */
  frames: { type: Array, default: () => [] },
  /** true=直播形态（红点角标 + 实时文案）；false=账本回放 */
  live: { type: Boolean, default: false },
  /** 抽屉内部滚动上限（px） */
  maxHeight: { type: Number, default: 320 }
})

const open = reactive({})
const toggle = i => { open[i] = !open[i] }

const trunc = (s, n) => (s && s.length > n ? s.slice(0, n) + '…' : String(s || ''))
const rawText = c => {
  try {
    const o = JSON.parse(c)
    if (o && typeof o === 'object') return trunc(o.message || o.content || o.text || o.question || o.tool || '', 120) || trunc(c, 120)
  } catch { /* 非 JSON 原样 */ }
  return trunc(c, 120)
}
const briefOf = o => {
  try {
    const a = typeof o.args === 'string' ? JSON.parse(o.args) : o.args
    if (a && typeof a === 'object') {
      for (const k of ['query', 'keyword', 'pattern', 'cmd', 'command', 'path', 'url', 'question', 'q']) {
        if (typeof a[k] === 'string' && a[k]) return trunc(a[k], 42)
      }
      for (const v of Object.values(a)) if (typeof v === 'string' && v) return trunc(v, 42)
    } else if (typeof a === 'string' && a) return trunc(a, 42)
  } catch { /* 入参不是 JSON：不强求 brief */ }
  return ''
}
const mkTool = (o, st) => ({
  kind: 'tool', name: String(o.name || ''), label: String(o.label || ''), st,
  ms: Number(o.elapsedMs) || 0, denied: !!o.denied, brief: briefOf(o),
  args: o.args == null || o.args === '' ? '' : prettyIo(o.args),
  result: o.result == null ? '' : String(o.result),
  error: o.error == null ? '' : String(o.error)
})

const rows = computed(() => {
  const out = []
  const settled = new Set()   // 已收终态的工具（双路去重）
  const starts = new Map()    // start 行在 out 里的下标（终态来了原位替换）
  for (const f of (props.frames || [])) {
    const t = f.t || ''
    const c = typeof f.c === 'string' ? f.c : ''
    if (t === 'tool_status') {
      let o = null
      try { o = JSON.parse(c) } catch { /* 载荷残缺：跳过，少一行比一行乱强 */ }
      if (!o || !o.name) continue
      // 早产卡只是「入参还在生成」的进度帧：它和随后的 start/done 各画一行，一次调用会变三帧，
      // 且那张 start 行永远停在转圈。抽屉只认正式起止。
      if (o.preparing) continue
      const key = String(o.name) + '#' + (o.startedAtMs || 0)
      if (o.status === 'start') {
        if (starts.has(key)) continue
        starts.set(key, out.length)
        out.push(mkTool(o, 'start'))
        continue
      }
      if (o.status === 'done' || o.status === 'error') {
        if (settled.has(key)) continue
        settled.add(key)
        const row = mkTool(o, o.status)
        const pi = starts.get(key)
        if (pi != null) { starts.delete(key); out[pi] = row } else out.push(row)
      }
      continue
    }
    if (t === 'retrieved') {
      let terms = []
      try {
        const o = JSON.parse(c)
        terms = Array.isArray(o?.terms) ? o.terms : (Array.isArray(o?.keywords) ? o.keywords : [])
      } catch { /* 原样兜底 */ }
      out.push({ kind: 'retrieved', text: terms.length ? '检索：' + trunc(terms.join('、'), 90) : (c ? trunc(c, 90) : '取得一批检索资料') })
      continue
    }
    if (t === 'artifact') {
      let name = '', size = 0
      try {
        const o = JSON.parse(c)
        name = o.filename || o.file || o.name || ''
        size = Number(o.size) || 0
      } catch { name = trunc(c, 60) }
      out.push({ kind: 'artifact', text: name || '交付产物', size })
      continue
    }
    if (t === 'warn') { out.push({ kind: 'warn', text: rawText(c) }); continue }
    if (t === 'approval_required' || t === 'ask_user') {
      out.push({ kind: 'hold', text: (t === 'ask_user' ? '向人提问：' : '等待裁决：') + rawText(c) })
      continue
    }
    out.push({ kind: 'raw', text: trunc(c, 200) })
  }
  return out
})

const fmtSize = n => n >= 1048576 ? `${(n / 1048576).toFixed(1)}MB` : n >= 1024 ? `${Math.round(n / 1024)}KB` : `${n}B`
</script>

<style scoped>
/* 与派遣卡内其它块同一套令牌；不设 max-width，跟随消息列宽 */
.srf {
  border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel-2);
  overflow: hidden; overflow-y: auto;
}
.srf-head {
  display: flex; align-items: center; gap: 8px; padding: 6px 10px;
  font-size: 11px; color: var(--app-text3);
  border-bottom: 1px solid var(--app-border); position: sticky; top: 0; background: var(--app-panel-2); z-index: 1;
}
.srf-live { display: inline-flex; align-items: center; gap: 4px; font-size: 10.5px; color: var(--app-danger); }
.srf-live-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--app-danger); animation: srf-pulse 1.2s ease-in-out infinite; }
.srf-badge { font-size: 10.5px; color: var(--app-text3); border: 1px solid var(--app-border); border-radius: 999px; padding: 0 6px; }
.srf-meta { margin-left: auto; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
@keyframes srf-pulse { 0%, 100% { opacity: 1; } 50% { opacity: .2; } }
.srf-empty { padding: 8px 10px; font-size: 12px; color: var(--app-text3); }

.srf-fr { border-bottom: 1px solid var(--app-border); font-size: 12px; }
.srf-fr:last-child { border-bottom: 0; }
.srf-row {
  display: flex; align-items: center; gap: 6px; width: 100%;
  padding: 4px 9px; background: none; border: 0; text-align: left; cursor: pointer;
  font: inherit; color: var(--app-text2);
}
.srf-row:hover { background: var(--app-panel); }
.srf-ic { flex: none; display: inline-flex; font-size: 12px; }
.srf-ic-ok { color: var(--app-ok); }
.srf-ic-err { color: var(--app-danger); }
.srf-ic-acc { color: var(--app-accent); }
.srf-name { flex: none; font-weight: 500; color: var(--app-text2); }
.srf-brief {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 11px;
  color: var(--app-accent); background: var(--app-accent-weak);
  padding: 0 6px; border-radius: 4px; max-width: 220px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.srf-flag { flex: none; font-size: 11px; color: var(--app-text3); }
.srf-ms { margin-left: auto; flex: none; font-size: 11px; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.srf-caret { flex: none; font-size: 10px; color: var(--app-text3); transition: transform .15s; }
.srf-caret.open { transform: rotate(90deg); }
.srf-detail { padding: 0 10px 8px 27px; }
.srf-io-label { font-size: 10.5px; color: var(--app-text3); margin: 5px 0 2px; }
.srf-io {
  margin: 0; padding: 6px 8px; border-radius: 6px; background: var(--app-bg);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 11px;
  line-height: 1.6; color: var(--app-text); max-height: 200px; overflow: auto;
  white-space: pre-wrap; word-break: break-all;
}
.srf-io-err { color: var(--app-danger); }
.srf-plain { display: flex; align-items: center; gap: 6px; padding: 4px 9px; font-size: 12px; color: var(--app-text2); border-bottom: 1px solid var(--app-border); }
.srf-plain:last-child { border-bottom: 0; }
.srf-plain .srf-sub { color: var(--app-text3); font-size: 11px; }
.srf-art { color: var(--app-accent); word-break: break-all; }
.srf-raw span { color: var(--app-text3); word-break: break-all; }
.srf-warn {
  display: flex; align-items: center; gap: 6px; margin: 3px 6px; padding: 3px 8px; border-radius: 6px;
  background: var(--app-warn-weak); box-shadow: inset 0 0 0 1px var(--app-warn-border);
  color: var(--app-warn-text); font-size: 12px;
}
.srf-warn .srf-ic { color: var(--app-warn-text); }
.srf-hold {
  display: flex; align-items: center; gap: 6px; margin: 3px 6px; padding: 3px 8px; border-radius: 6px;
  background: var(--app-accent-weak); box-shadow: inset 0 0 0 1px var(--app-accent-border, var(--app-border));
  color: var(--app-accent); font-size: 12px;
}
.srf-hold .srf-ic { color: var(--app-accent); }
</style>
