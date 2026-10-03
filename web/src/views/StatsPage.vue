<template>
  <div class="app-page">
    <div class="app-page-head">
      <h3 class="app-page-title">使用统计</h3>
      <span class="app-pill">应用用量</span>
      <button class="app-btn ghost" style="margin-left:auto" :disabled="loading" @click="load">刷新</button>
    </div>

    <div class="app-page-body">
      <!-- ==================== 统计卡（全时段口径） ==================== -->
      <div class="app-card stat-cards">
        <div v-for="c in cardCells" :key="c.label" class="stat-cell">
          <div class="stat-num">{{ c.value }}</div>
          <div class="stat-label">{{ c.label }}</div>
        </div>
      </div>

      <!-- ==================== Token 活动（近一年热力图） ==================== -->
      <div class="app-card">
        <div class="app-card-title">
          Token 活动
          <a-segmented v-model:value="heatMode" size="small" style="margin-left:auto"
                       :options="[{ value: 'daily', label: '每日' }, { value: 'weekly', label: '每周' }, { value: 'total', label: '累计' }]" />
        </div>
        <div class="heat-scroll">
          <div class="heat-inner">
            <div class="heat-grid" :style="{ gridTemplateColumns: heatColTemplate }"
                 @mouseover="onCellOver" @mouseleave="hideTip">
              <template v-for="(col, ci) in heatData.columns" :key="'c' + ci">
                <template v-for="(cell, ri) in col" :key="ci + '-' + ri">
                  <div v-if="cell" class="heat-cell" :class="'hl' + heatData.levelOf(cell)"
                       :data-l1="cellTip(cell).l1" :data-l2="cellTip(cell).l2"></div>
                  <div v-else class="heat-cell empty"></div>
                </template>
              </template>
            </div>
            <div class="heat-months" :style="{ gridTemplateColumns: heatColTemplate }">
              <template v-for="(m, i) in heatMonths" :key="'m' + i">
                <div class="heat-month" :style="{ gridColumnStart: m.col }">{{ m.label }}</div>
              </template>
            </div>
          </div>
        </div>
        <div class="heat-legend">
          <span>少</span>
          <i v-for="l in 5" :key="l" class="heat-cell" :class="'hl' + (l - 1)"></i>
          <span>多</span>
        </div>
      </div>

      <!-- ==================== 时间范围切换 ==================== -->
      <div class="range-row">
        <span class="range-label">时间范围</span>
        <a-segmented v-model:value="range" :options="[{ value: 7, label: '近 7 日' }, { value: 30, label: '近 30 日' }]" @change="load" />
      </div>

      <!-- ==================== 每日 Token 趋势图 ==================== -->
      <div class="app-card">
        <div class="app-card-title">每日 Token 趋势图</div>
        <div class="trend-legend">
          <span v-for="s in trendSeries" :key="s.model" class="tl-item">
            <i class="tl-dot" :style="{ background: seriesColor(s.model) }"></i>{{ s.model }}
          </span>
        </div>
        <div ref="trendEl" class="chart trend"></div>
      </div>

      <!-- ==================== 模型用量（环形图） ==================== -->
      <div class="app-card">
        <div class="app-card-title">模型用量
          <span v-if="hasUnrecorded" class="card-sub">「未记录」为模型字段上线前的历史消息；新回答起按实际模型统计</span>
        </div>
        <div v-if="modelRows.length" class="donut-flex">
          <div ref="pieEl" class="chart donut"></div>
          <div class="legend-list">
            <div v-for="m in modelRows" :key="m.model" class="legend-row">
              <i class="tl-dot" :style="{ background: seriesColor(m.model) }"></i>
              <div class="legend-main">
                <div class="legend-name">{{ m.model }}</div>
                <div class="legend-sub">{{ fmtTokens(m.tokens) }} tokens</div>
              </div>
              <div class="legend-pct">{{ pct(m.tokens) }}%</div>
            </div>
          </div>
        </div>
        <a-empty v-else description="该时间范围内暂无用量数据" />
      </div>
    </div>

    <!-- 热力图悬浮提示（单实例浮层：事件委托驱动，随鼠标即时显示；原生 title 有约 1s 延迟且样式不可控） -->
    <Teleport to="body">
      <div v-if="tip.show" class="heat-tip" :style="{ left: tip.x + 'px', top: tip.y + 'px' }">
        <div class="heat-tip-l1">{{ tip.l1 }}</div>
        <div class="heat-tip-l2">{{ tip.l2 }}</div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { message } from 'ant-design-vue'
import * as echarts from 'echarts/core'
import { LineChart, PieChart } from 'echarts/charts'
import { TooltipComponent, GridComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { getUsageStats } from '../api'
import { themeState } from '../utils/theme'

echarts.use([LineChart, PieChart, TooltipComponent, GridComponent, CanvasRenderer])

// ==================== 状态与数据 ====================
const loading = ref(false)
const range = ref(30)          // 趋势/占比窗口：7 | 30
const heatMode = ref('daily')  // 热力图视图：每日 | 每周 | 累计
const cards = ref({})          // { totalTokens, peakDayTokens, longestChatSeconds, currentStreakDays, longestStreakDays }
const heatmap = ref([])        // [{ date, tokens }] 稀疏日清单（近 365 天）
const trendDays = ref([])      // ['yyyy-MM-dd', ...]
const trendSeries = ref([])    // [{ model, values[] }]
const modelRows = ref([])      // [{ model, tokens }]（近 N 日）
const hasUnrecorded = computed(() => modelRows.value.some(m => m.model === '未记录'))

const load = async () => {
  loading.value = true
  try {
    const r = await getUsageStats(range.value)
    if (!r.success) throw new Error(r.msg || '加载失败')
    const d = r.data || {}
    cards.value = d.cards || {}
    heatmap.value = d.heatmap || []
    trendDays.value = d.trend?.days || []
    trendSeries.value = d.trend?.series || []
    modelRows.value = d.models || []
    await nextTick()
    renderCharts()
  } catch (e) {
    message.error(e.message || '加载统计失败')
  } finally {
    loading.value = false
  }
}

// ==================== 统计卡 ====================
const cardCells = computed(() => [
  { label: '累计 Token 数', value: fmtTokens(cards.value.totalTokens || 0) },
  { label: '峰值 Token 数', value: fmtTokens(cards.value.peakDayTokens || 0) },
  { label: '最长聊天时长', value: fmtDuration(cards.value.longestChatSeconds || 0) },
  { label: '当前连续天数', value: (cards.value.currentStreakDays || 0) + ' 天' },
  { label: '最长连续天数', value: (cards.value.longestStreakDays || 0) + ' 天' }
])

// 模型占比：分母=时间范围内全部模型 token 总量；≥10% 取整数、<10% 保留一位小数（与设计稿一致）
const pct = tokens => {
  const total = modelRows.value.reduce((a, m) => a + (Number(m.tokens) || 0), 0)
  const v = total > 0 ? ((Number(tokens) || 0) / total) * 100 : 0
  return v >= 10 ? String(Math.round(v)) : v.toFixed(1)
}

// 中文习惯的大数缩写：亿 / 万（与产品文案一致，不使用 k/M）
function fmtTokens(n) {
  const v = Number(n) || 0
  if (v >= 1e8) return trim1(v / 1e8) + ' 亿'
  if (v >= 1e4) return trim1(v / 1e4) + ' 万'
  return String(v)
}
function fmtCompact(n) {
  const v = Number(n) || 0
  if (v >= 1e8) return trim1(v / 1e8) + '亿'
  if (v >= 1e4) return trim1(v / 1e4) + '万'
  return String(v)
}
const trim1 = x => (Math.round(x * 10) / 10).toString()

function fmtDuration(sec) {
  const s = Math.max(0, Math.floor(Number(sec) || 0))
  if (s < 60) return s <= 0 ? '—' : '不足 1 分钟'
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60)
  if (d > 0) return `${d} 天 ${h} 小时`
  if (h > 0) return `${h} 小时 ${m} 分钟`
  return `${m} 分钟`
}

// ==================== Token 活动热力图 ====================
// 一次派生：列（周×7 天的格子矩阵）+ 档位函数。三种视图共用日数据（tokens + 轮次）：
//   每日=按当日分档；每周=每列按周合计整列同色；累计=每列按「截至该周末」的周累计整列同色。
// 档位均按非零值四分位划档（GitHub 口径）。
const dailyMap = computed(() => {
  const m = new Map()
  for (const c of heatmap.value) m.set(c.date, { t: Number(c.tokens) || 0, c: Number(c.count) || 0 })
  return m
})

const heatData = computed(() => {
  const m = dailyMap.value
  const today = new Date()
  const fmt = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  // 末列 = 本周（周日开头，GitHub 口径）；向前铺满近 365 天
  const saturday = new Date(today)
  saturday.setDate(saturday.getDate() + (6 - today.getDay()))
  const weeks = Math.ceil((365 + today.getDay() + 1) / 7)
  const dayOf = (w, i) => { const d = new Date(saturday); d.setDate(d.getDate() - w * 7 - (6 - i)); return d }

  // 每周聚合（旧→新，与列序一致）：tokens / 轮次 / 周结束日（未到的周末以今天截断）
  const weekAgg = []
  for (let w = weeks - 1; w >= 0; w--) {
    let t = 0, c = 0
    for (let i = 0; i < 7; i++) {
      const rec = m.get(fmt(dayOf(w, i)))
      if (rec) { t += rec.t; c += rec.c }
    }
    const endD = dayOf(w, 6)
    weekAgg.push({ t, c, end: fmt(endD > today ? today : endD) })
  }
  // 累计视图（按周）：截至各周末的累计 tokens / 轮次（前缀和=日累计在周末的取值）
  const weekCum = []
  let ct = 0, cc = 0
  for (const a of weekAgg) { ct += a.t; cc += a.c; weekCum.push({ t: ct, c: cc, end: a.end }) }

  const levelOfVal = heatMode.value === 'weekly'
    ? leveler(weekAgg.map(a => a.t))
    : heatMode.value === 'total'
      ? leveler(weekCum.map(x => x.t))
      : leveler(sortedDates(m).map(d => m.get(d).t))

  const columns = []
  for (let w = weeks - 1; w >= 0; w--) {
    const col = []
    const wi = weeks - 1 - w
    for (let i = 0; i < 7; i++) {
      const d = dayOf(w, i)
      if (d > today) { col.push(null); continue }
      const key = fmt(d)
      if (heatMode.value === 'weekly') {
        // 每周一列：7 格按该周合计整列同色（保持网格结构，聚合粒度一眼可辨）
        col.push({ date: key, value: weekAgg[wi].t, count: weekAgg[wi].c, weekEnd: weekAgg[wi].end })
      } else if (heatMode.value === 'total') {
        col.push({ date: key, value: weekCum[wi].t, count: weekCum[wi].c, weekEnd: weekCum[wi].end })
      } else {
        const rec = m.get(key) || { t: 0, c: 0 }
        col.push({ date: key, value: rec.t, count: rec.c })
      }
    }
    columns.push(col)
  }
  return { columns, levelOf: cell => levelOfVal(cell.value) }
})

function sortedDates(m) {
  return [...m.keys()].sort()
}

// 值 → 0..4 档：按非零值四分位划档（GitHub 口径）
function leveler(values) {
  const nz = values.filter(v => v > 0).sort((a, b) => a - b)
  if (!nz.length) return () => 0
  const q = p => nz[Math.min(nz.length - 1, Math.floor(p * nz.length))]
  const t1 = q(0.25), t2 = q(0.5), t3 = q(0.75)
  return v => (v <= 0 ? 0 : v <= t1 ? 1 : v <= t2 ? 2 : v <= t3 ? 3 : 4)
}

// 悬浮两行文案（对照参考稿）：完整日期 + tokens · 轮次；每周/累计带周口径后缀
function cellTip(cell) {
  const full = d => { const p = d.split('-'); return `${p[0]}年${+p[1]}月${+p[2]}日` }
  if (heatMode.value === 'weekly') {
    return { l1: `${full(cell.weekEnd)} 当周`, l2: `${fmtTokens(cell.value)} tokens · ${cell.count} 轮消息` }
  }
  if (heatMode.value === 'total') {
    return { l1: `截至 ${full(cell.weekEnd)} 当周累计`, l2: `${fmtTokens(cell.value)} tokens · ${cell.count} 轮消息` }
  }
  return { l1: full(cell.date), l2: `${fmtTokens(cell.value)} tokens · ${cell.count} 轮消息` }
}

// ==================== 热力图悬浮提示（事件委托 + 单实例浮层） ====================
const tip = ref({ show: false, l1: '', l2: '', x: 0, y: 0 })
const onCellOver = e => {
  const el = e.target.closest('.heat-cell')
  if (!el || el.classList.contains('empty') || !el.dataset.l2) { tip.value.show = false; return }
  // 鼠标在格间移动时 mouseover 持续触发：跟随鼠标上方居中显示
  tip.value = { show: true, l1: el.dataset.l1, l2: el.dataset.l2, x: e.clientX, y: e.clientY }
}
const hideTip = () => { tip.value.show = false }

// 列模板：列数与热力图一致，每列 minmax(9px, 1fr) 随容器伸缩（宽屏铺满卡片，不再挤成一小条）
const heatColTemplate = computed(() => `repeat(${heatData.value.columns.length}, minmax(9px, 1fr))`)
// 月份标签：某列的周日跨入新月份时标注（网格下沿，与截图一致）
const heatMonths = computed(() => {
  const out = []
  let prevMonth = -1
  heatData.value.columns.forEach((col, ci) => {
    // 列首（行 0）= 该列周日；列首若是未来日期（末列），回退到列内最后一个真实日期再补齐
    const last = col.map((c, i) => ({ c, i })).filter(x => x.c !== null).pop()
    if (!last) return
    const d = new Date(last.c.date)
    d.setDate(d.getDate() - last.i)
    if (d.getMonth() !== prevMonth) {
      prevMonth = d.getMonth()
      out.push({ col: ci + 1, label: `${d.getMonth() + 1}月` })
    }
  })
  return out
})

// ==================== 图表（ECharts；颜色取实际渲染值，随主题重绘） ====================
const trendEl = ref(null)
const pieEl = ref(null)
let trendChart = null, pieChart = null

const cssVar = name => getComputedStyle(document.documentElement).getPropertyValue(name).trim()
// 数据编码色（非主题面）：亮/暗两套，趋势与环形图共用同一映射
const PALETTES = {
  light: ['#2e6be6', '#3b8f4e', '#8e6fe0', '#d4782e', '#2aa198', '#c74f9e'],
  dark: ['#6b9df0', '#67b978', '#a98ef0', '#e09a5f', '#5fc4bb', '#dd85c0']
}
const MUTED = { light: '#b3bac3', dark: '#5f6570' }
const seriesColor = model => {
  if (model === '其他' || model === '未记录') return themeState.value === 'dark' ? MUTED.dark : MUTED.light
  const i = namedIndex(model)
  const pal = PALETTES[themeState.value === 'dark' ? 'dark' : 'light']
  return pal[i % pal.length]
}
const namedIndex = model => {
  let i = 0
  for (const s of trendSeries.value) {
    if (s.model === '其他' || s.model === '未记录') continue
    if (s.model === model) return i
    i++
  }
  return i
}

const renderCharts = () => {
  const theme = themeState.value === 'dark' ? 'dark' : 'light'
  const text2 = cssVar('--app-text2') || (theme === 'dark' ? '#a8b0ba' : '#5f6570')
  const text3 = cssVar('--app-text3') || (theme === 'dark' ? '#6f7883' : '#98a0aa')
  const border = cssVar('--app-border') || (theme === 'dark' ? '#333a44' : '#e6e9ed')

  // ---- 每日趋势 ----
  if (trendEl.value) {
    if (!trendChart) trendChart = echarts.init(trendEl.value)
    const days = trendDays.value.map(d => { const p = d.split('-'); return `${+p[1]}/${+p[2]}` })
    trendChart.setOption({
      animation: false,
      grid: { left: 8, right: 16, top: 20, bottom: 8, containLabel: true },
      tooltip: {
        trigger: 'axis',
        backgroundColor: theme === 'dark' ? '#262b33' : '#fff',
        borderColor: border,
        textStyle: { color: theme === 'dark' ? '#e8eaed' : '#1f2329', fontSize: 12 },
        valueFormatter: v => fmtCompact(v) + ' tokens'
      },
      xAxis: {
        type: 'category', boundaryGap: false, data: days,
        axisLine: { lineStyle: { color: border } }, axisTick: { show: false },
        axisLabel: { color: text3, fontSize: 11, interval: Math.max(0, Math.floor(days.length / 8) - 1) }
      },
      yAxis: {
        type: 'value',
        splitLine: { lineStyle: { color: border, type: 'dashed' } },
        axisLabel: { color: text3, fontSize: 11, formatter: v => fmtCompact(v) }
      },
      series: trendSeries.value.map(s => ({
        name: s.model, type: 'line', smooth: true, showSymbol: false,
        lineStyle: { width: 2, color: seriesColor(s.model) },
        itemStyle: { color: seriesColor(s.model) },
        emphasis: { focus: 'series' },
        data: s.values
      }))
    }, true)
  }

  // ---- 模型用量环形图 ----
  if (pieEl.value && modelRows.value.length) {
    if (!pieChart) pieChart = echarts.init(pieEl.value)
    const total = modelRows.value.reduce((a, m) => a + (Number(m.tokens) || 0), 0)
    pieChart.setOption({
      animation: false,
      tooltip: {
        backgroundColor: theme === 'dark' ? '#262b33' : '#fff',
        borderColor: border,
        textStyle: { color: theme === 'dark' ? '#e8eaed' : '#1f2329', fontSize: 12 },
        formatter: p => `${p.name}：${fmtTokens(p.value)} tokens（${p.percent}%）`
      },
      title: {
        text: fmtTokens(total), subtext: 'tokens', left: 'center', top: '38%',
        textStyle: { color: text2, fontSize: 20, fontWeight: 500 },
        subtextStyle: { color: text3, fontSize: 12 }, itemGap: 2
      },
      series: [{
        type: 'pie', radius: ['62%', '84%'], center: ['50%', '50%'],
        label: { show: false }, labelLine: { show: false },
        itemStyle: { borderColor: cssVar('--app-panel') || (theme === 'dark' ? '#22262d' : '#fff'), borderWidth: 2 },
        data: modelRows.value.map(m => ({
          name: m.model, value: Number(m.tokens) || 0,
          itemStyle: { color: seriesColor(m.model) }
        }))
      }]
    }, true)
  }
}

const onResize = () => { trendChart?.resize(); pieChart?.resize() }
watch(themeState, () => { renderCharts() })

onMounted(() => {
  load()
  window.addEventListener('resize', onResize)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  trendChart?.dispose()
  pieChart?.dispose()
  trendChart = pieChart = null
})
</script>

<style scoped>
/* ==================== 统计卡 ==================== */
.stat-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  padding: 18px 8px;
}
.stat-cell {
  text-align: center;
  padding: 4px 12px;
  border-right: 1px solid var(--app-border);
}
.stat-cell:last-child { border-right: none; }
.stat-num { font-size: 22px; font-weight: 500; line-height: 1.3; color: var(--app-text); }
.stat-label { font-size: 12px; color: var(--app-text3); margin-top: 4px; }
@media (max-width: 768px) {
  .stat-cell { border-right: none; border-bottom: 1px dashed var(--app-border); padding: 8px 0; }
  .stat-cell:last-child { border-bottom: none; }
}

/* ==================== 热力图 ==================== */
.heat-scroll { overflow-x: auto; padding: 4px 2px; }
/* 宽屏铺满（上限 1200px 防格子过大），窄屏横向滚动 */
.heat-inner { min-width: 100%; max-width: 1200px; }
.heat-grid {
  display: grid;
  grid-auto-flow: column;
  grid-template-rows: repeat(7, auto); /* 列流必须显式行数：否则全部格子塞进第 1 列 */
  gap: 4px;
}
.heat-cell {
  width: 100%;
  aspect-ratio: 1;
  align-self: start; /* 阻止默认 stretch 覆盖 aspect-ratio 高度（空内容格会塌成 0 高） */
  border-radius: 2.5px;
  background: var(--app-panel-2);
  outline: 1px solid rgba(0, 0, 0, .04);
  outline-offset: -1px;
}
.heat-cell.empty { background: transparent; outline: none; }
.hl1 { background: #c9ddf8; }
.hl2 { background: #93bdf1; }
.hl3 { background: #5c98e9; }
.hl4 { background: #2e6be6; }
:global(html[data-theme='dark']) .hl1 { background: #1e3a61; }
:global(html[data-theme='dark']) .hl2 { background: #28528d; }
:global(html[data-theme='dark']) .hl3 { background: #3a72c0; }
:global(html[data-theme='dark']) .hl4 { background: #5c98e9; }
.heat-months { display: grid; gap: 4px; margin-top: 8px; min-height: 16px; }
.heat-month { font-size: 11px; color: var(--app-text3); white-space: nowrap; }
/* 热力图悬浮提示：teleport 到 body，深色浮层跟随鼠标上方居中；两行=日期 / tokens·轮次 */
.heat-tip {
  position: fixed;
  transform: translate(-50%, calc(-100% - 8px));
  background: rgba(28, 32, 38, .92);
  color: #fff;
  padding: 8px 12px;
  border-radius: 8px;
  white-space: nowrap;
  pointer-events: none;
  z-index: 1080;
}
.heat-tip-l1 { font-size: 11px; line-height: 1.4; color: rgba(255, 255, 255, .72); }
.heat-tip-l2 { font-size: 12px; line-height: 1.4; font-weight: 600; }
.heat-legend {
  display: flex; align-items: center; gap: 4px; justify-content: flex-end;
  margin-top: 8px; font-size: 11px; color: var(--app-text3);
}
.heat-legend .heat-cell { width: 10px; height: 10px; }

/* ==================== 时间范围 ==================== */
.range-row {
  display: flex; align-items: center; gap: 12px;
  margin: 16px 0 4px;
}
.range-label { font-size: 13px; color: var(--app-text2); }

/* ==================== 趋势图 / 环形图 ==================== */
.trend-legend { display: flex; flex-wrap: wrap; gap: 6px 18px; margin: 2px 0 4px; }
.card-sub { font-size: 11px; color: var(--app-text3); font-weight: 400; margin-left: 10px; }
.tl-item { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--app-text2); }
.tl-dot { width: 9px; height: 9px; border-radius: 50%; flex: none; display: inline-block; }
.chart { width: 100%; }
.chart.trend { height: 320px; }
.chart.donut { height: 260px; flex: none; width: 300px; }
.donut-flex { display: flex; align-items: center; gap: 24px; flex-wrap: wrap; }
.legend-list { flex: 1; min-width: 260px; }
.legend-row {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 2px; border-bottom: 1px solid var(--app-border);
}
.legend-row:last-child { border-bottom: none; }
.legend-main { flex: 1; min-width: 0; }
.legend-name { font-size: 13px; color: var(--app-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.legend-sub { font-size: 11px; color: var(--app-text3); margin-top: 2px; }
.legend-pct { font-size: 13px; color: var(--app-text2); font-variant-numeric: tabular-nums; }
</style>
