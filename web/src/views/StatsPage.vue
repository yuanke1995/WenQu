<template>
  <div class="app-page">
    <div class="app-page-head">
      <h3 class="app-page-title">使用统计</h3>
      <span class="app-pill">应用用量</span>
      <button class="app-btn ghost" style="margin-left:auto" :disabled="loading" @click="load">刷新</button>
    </div>

    <!-- 一屏布局：统计卡 / Token 活动 / 图表行三行，图表行吃掉剩余高度（页面本身不滚动） -->
    <div class="app-page-body stats-body">
      <!-- ==================== 统计卡（全时段口径） ==================== -->
      <div class="app-card stat-cards">
        <div v-for="c in cardCells" :key="c.label" class="stat-cell">
          <div class="stat-num">{{ c.value }}</div>
          <div class="stat-label">{{ c.label }}</div>
        </div>
      </div>

      <!-- ==================== Token 活动（近一年热力图） ==================== -->
      <div class="app-card heat-card">
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
            <!-- 月份标签与「少→多」图例同行：省一行高度（一屏布局的高度预算） -->
            <div class="heat-foot">
              <div class="heat-months" :style="{ gridTemplateColumns: heatColTemplate }">
                <template v-for="(m, i) in heatMonths" :key="'m' + i">
                  <div class="heat-month" :style="{ gridColumnStart: m.col }">{{ m.label }}</div>
                </template>
              </div>
              <div class="heat-legend">
                <span>少</span>
                <i v-for="l in 5" :key="l" class="heat-cell" :class="'hl' + (l - 1)"></i>
                <span>多</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- ==================== 趋势图 + 模型用量 ==================== -->
      <div class="charts-row">
        <!-- 每日 Token 趋势图 -->
        <div class="app-card chart-card">
          <div class="app-card-title">
            每日 Token 趋势图
            <a-segmented v-model:value="range" size="small" style="margin-left:auto"
                         :options="[{ value: 7, label: '近 7 日' }, { value: 30, label: '近 30 日' }]" @change="load" />
          </div>
          <div class="trend-legend">
            <span v-for="s in trendSeries" :key="s.model" class="tl-item">
              <i class="tl-dot" :style="{ background: seriesColor(s.model) }"></i>{{ s.label || s.model }}
            </span>
          </div>
          <div ref="trendEl" class="chart trend"></div>
        </div>

        <!-- 模型用量（环形图） -->
        <div class="app-card chart-card">
          <div class="app-card-title">模型用量
            <span v-if="hasUnrecorded" class="card-sub">「未记录」为模型字段上线前的历史消息；新回答起按实际模型统计</span>
          </div>
          <div v-if="modelRows.length" class="donut-flex">
            <div ref="pieEl" class="chart donut"></div>
            <div class="legend-list">
              <div v-for="m in modelRows" :key="m.model" class="legend-row">
                <i class="tl-dot" :style="{ background: seriesColor(m.model) }"></i>
                <div class="legend-main">
                  <div class="legend-name">{{ m.label || m.model }}</div>
                  <div class="legend-sub">{{ fmtTokens(m.tokens) }} tokens</div>
                </div>
                <div class="legend-pct">{{ pct(m.tokens) }}%</div>
              </div>
            </div>
          </div>
          <a-empty v-else description="该时间范围内暂无用量数据" />
        </div>
      </div>
    </div>

    <!-- 热力图悬浮提示（单实例浮层：事件委托驱动，随鼠标即时显示；原生 title 有约 1s 延迟且样式不可控） -->
    <Teleport to="body">
      <div v-if="tip.show" ref="tipEl" class="heat-tip" :style="{ left: tip.x + 'px', top: tip.y + 'px' }">
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
import { TooltipComponent, GridComponent, TitleComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { getUsageStats } from '../api'
import { themeState } from '../utils/theme'

echarts.use([LineChart, PieChart, TooltipComponent, GridComponent, TitleComponent, CanvasRenderer])

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
// 档位均按非零值四分位划档（GitHub 口径）。末列=进行中的本周（周日开头），未来日子按零值格
// 预生成（与无活动的日子同款灰、悬浮照常）——整列结构始终完整，今天的格子当天上墙。
const dailyMap = computed(() => {
  const m = new Map()
  for (const c of heatmap.value) m.set(c.date, { t: Number(c.tokens) || 0, c: Number(c.count) || 0 })
  return m
})

const heatData = computed(() => {
  const m = dailyMap.value
  const today = new Date()
  const fmt = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  // 末列 = 进行中的本周（周日开头、周六收尾，GitHub 口径）；向前固定铺 53 列，历史覆盖 ≥365 天
  const saturday = new Date(today)
  saturday.setDate(saturday.getDate() + (6 - today.getDay()))
  const weeks = 1 + Math.ceil((364 - today.getDay()) / 7)
  const dayOf = (w, i) => { const d = new Date(saturday); d.setDate(d.getDate() - w * 7 - (6 - i)); return d }

  // 每周聚合（旧→新，与列序一致）：tokens / 轮次 / 周结束日（末列为进行中的本周，以今天截断并标 partial）
  const weekAgg = []
  for (let w = weeks - 1; w >= 0; w--) {
    let t = 0, c = 0
    for (let i = 0; i < 7; i++) {
      const rec = m.get(fmt(dayOf(w, i)))
      if (rec) { t += rec.t; c += rec.c }
    }
    const endD = dayOf(w, 6)
    weekAgg.push({ t, c, end: fmt(endD > today ? today : endD), partial: endD > today })
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
  const colEnds = [] // 各列周六（列尾）日期：月份标签按它跨月判定（不依赖格子对象，列数学改动时更稳）
  for (let w = weeks - 1; w >= 0; w--) {
    const col = []
    const wi = weeks - 1 - w
    for (let i = 0; i < 7; i++) {
      const key = fmt(dayOf(w, i))
      if (heatMode.value === 'weekly') {
        // 每周一列：7 格按该周合计整列同色（保持网格结构，聚合粒度一眼可辨）；进行中的本周整列同色、按截至今天聚合
        col.push({ date: key, value: weekAgg[wi].t, count: weekAgg[wi].c, weekEnd: weekAgg[wi].end, partial: weekAgg[wi].partial })
      } else if (heatMode.value === 'total') {
        col.push({ date: key, value: weekCum[wi].t, count: weekCum[wi].c, weekEnd: weekAgg[wi].end, partial: weekAgg[wi].partial })
      } else {
        // 每日：未来日子按零值格预生成（同款灰、悬浮照常），不做任何特殊占位
        const rec = m.get(key) || { t: 0, c: 0 }
        col.push({ date: key, value: rec.t, count: rec.c })
      }
    }
    columns.push(col)
    colEnds.push(fmt(dayOf(w, 6)))
  }
  return { columns, colEnds, levelOf: cell => levelOfVal(cell.value) }
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
    return { l1: `${full(cell.weekEnd)} 当周${cell.partial ? '（进行中）' : ''}`, l2: `${fmtTokens(cell.value)} tokens · ${cell.count} 轮消息` }
  }
  if (heatMode.value === 'total') {
    return { l1: `截至 ${full(cell.weekEnd)} 当周累计${cell.partial ? '（进行中）' : ''}`, l2: `${fmtTokens(cell.value)} tokens · ${cell.count} 轮消息` }
  }
  return { l1: full(cell.date), l2: `${fmtTokens(cell.value)} tokens · ${cell.count} 轮消息` }
}

// ==================== 热力图悬浮提示（事件委托 + 单实例浮层） ====================
const tip = ref({ show: false, l1: '', l2: '', x: 0, y: 0 })
const tipEl = ref(null)
const onCellOver = e => {
  const el = e.target.closest('.heat-cell')
  if (!el || el.classList.contains('empty') || !el.dataset.l2) { tip.value.show = false; return }
  // 鼠标在格间移动时 mouseover 持续触发：跟随鼠标上方居中显示
  tip.value = { show: true, l1: el.dataset.l1, l2: el.dataset.l2, x: e.clientX, y: e.clientY }
  nextTick(clampTip)
}
// 浮层以鼠标为锚水平居中、向上偏移，靠边悬停会探出视口：渲染后实测尺寸，把落点收回窗口内
const clampTip = () => {
  const node = tipEl.value
  if (!node) return
  const w = node.offsetWidth, h = node.offsetHeight, margin = 8
  const x = Math.min(Math.max(tip.value.x, w / 2 + margin), window.innerWidth - w / 2 - margin)
  const y = Math.min(Math.max(tip.value.y, h + 2 * margin), window.innerHeight - margin)
  tip.value.x = Math.round(x)
  tip.value.y = Math.round(y)
}
const hideTip = () => { tip.value.show = false }

// 列模板：列数与热力图一致，每列 minmax(9px, 1fr) 随容器伸缩（宽屏铺满卡片，不再挤成一小条）
const heatColTemplate = computed(() => `repeat(${heatData.value.columns.length}, minmax(9px, 1fr))`)
// 月份标签：某列的周日跨入新月份时标注（网格下沿，与截图一致）
const heatMonths = computed(() => {
  const out = []
  let prevMonth = -1
  heatData.value.columns.forEach((col, ci) => {
    // 用列尾（周六）跨月判定：新月份首次出现的列打标（10月标在含 10-1 的那列）
    const d = new Date(heatData.value.colEnds[ci])
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
        name: s.label || s.model, type: 'line', smooth: true, showSymbol: false,
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
          name: m.label || m.model, value: Number(m.tokens) || 0,
          itemStyle: { color: seriesColor(m.model) }
        }))
      }]
    }, true)
  }
}

const onResize = () => { trendChart?.resize(); pieChart?.resize() }
watch(themeState, () => { renderCharts() })

// 一屏布局下图表高度由容器剩余空间决定（不再是固定 px）：容器尺寸变化必须重算画布，
// 否则窗口缩放/侧栏折叠后 ECharts 仍按旧尺寸绘制（留白或裁切）
let ro = null
onMounted(() => {
  load()
  window.addEventListener('resize', onResize)
  if (typeof ResizeObserver !== 'undefined') {
    ro = new ResizeObserver(() => onResize())
    if (trendEl.value) ro.observe(trendEl.value)
    if (pieEl.value) ro.observe(pieEl.value)
  }
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  ro?.disconnect()
  ro = null
  trendChart?.dispose()
  pieChart?.dispose()
  trendChart = pieChart = null
})
</script>

<style scoped>
/* ==================== 一屏布局 ====================
   三行：统计卡 / Token 活动 / 图表行。图表行吃掉剩余高度（1fr），
   各卡片内部用 min-height:0 + flex 让图表填满而不撑破容器——
   常规屏幕下页面不出现滚动条；窗口过矮时（图表行触底 240px）才回落到滚动。 */
.stats-body {
  display: grid;
  grid-template-columns: 1fr;
  grid-template-rows: auto auto minmax(240px, 1fr);
  gap: 12px;
  padding: 12px 16px;
}
.charts-row {
  display: grid;
  /* 模型用量列给足宽度（≥420px，宽屏最多 46%）：环形图 + 图例都要摆得开 */
  grid-template-columns: minmax(0, 1fr) minmax(400px, 46%);
  gap: 12px;
  min-height: 0;
}
/* 窄屏（≤1100px）两图上下排：此时内容必然高于一屏，body 回落为滚动 */
@media (max-width: 1100px) {
  .charts-row { grid-template-columns: 1fr; grid-template-rows: minmax(260px, 1fr) minmax(260px, 1fr); }
}
.chart-card { display: flex; flex-direction: column; min-height: 0; padding: 12px 14px; }

/* ==================== 统计卡 ==================== */
.stat-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  padding: 12px 8px;
}
.stat-cell {
  text-align: center;
  padding: 2px 12px;
  border-right: 1px solid var(--app-border);
}
.stat-cell:last-child { border-right: none; }
.stat-num { font-size: 20px; font-weight: 500; line-height: 1.25; color: var(--app-text); }
.stat-label { font-size: 12px; color: var(--app-text3); margin-top: 2px; }
@media (max-width: 768px) {
  .stat-cell { border-right: none; border-bottom: 1px dashed var(--app-border); padding: 8px 0; }
  .stat-cell:last-child { border-bottom: none; }
}

/* ==================== 热力图 ==================== */
/* 纵向不滚动（高度由卡片预算决定）；窄于最小列宽时才允许横向滚动 */
.heat-card { padding: 12px 14px; }
.heat-card .app-card-title { margin-bottom: 6px; }
.heat-scroll { overflow-x: auto; overflow-y: hidden; padding: 2px; }
/* 宽屏铺满（上限 1200px 防格子过大撑爆一屏），窄屏横向滚动 */
.heat-inner { min-width: 100%; max-width: 1200px; }
.heat-grid {
  display: grid;
  grid-auto-flow: column;
  grid-template-rows: repeat(7, auto); /* 列流必须显式行数：否则全部格子塞进第 1 列 */
  gap: 3px;
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
/* .heat-cell.empty 仅为格子缺数据的兜底（正常情况下不会出现），无专门样式=同零值格 */
.hl1 { background: #c9ddf8; }
.hl2 { background: #93bdf1; }
.hl3 { background: #5c98e9; }
.hl4 { background: #2e6be6; }
:global(html[data-theme='dark']) .hl1 { background: #1e3a61; }
:global(html[data-theme='dark']) .hl2 { background: #28528d; }
:global(html[data-theme='dark']) .hl3 { background: #3a72c0; }
:global(html[data-theme='dark']) .hl4 { background: #5c98e9; }
/* 月份行与「少→多」图例同一行（省一行高度） */
.heat-foot { display: flex; align-items: flex-end; gap: 14px; margin-top: 5px; }
.heat-months { display: grid; gap: 3px; flex: 1; min-height: 14px; }
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
  display: flex; align-items: center; gap: 4px; flex: none;
  font-size: 11px; color: var(--app-text3);
}
.heat-legend .heat-cell { width: 10px; height: 10px; }

/* ==================== 趋势图 / 环形图 ==================== */
/* 图表高度由容器决定（一屏布局）：flex:1 + min-height:0 才能被压缩而不是撑破卡片 */
.trend-legend { display: flex; flex-wrap: wrap; gap: 4px 16px; margin: 0 0 2px; flex: none; }
.card-sub { font-size: 11px; color: var(--app-text3); font-weight: 400; margin-left: 10px; }
.tl-item { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--app-text2); }
.tl-dot { width: 9px; height: 9px; border-radius: 50%; flex: none; display: inline-block; }
.chart { width: 100%; }
.chart.trend { flex: 1; min-height: 160px; }
.donut-flex { display: flex; align-items: stretch; gap: 16px; flex: 1; min-height: 0; }
.chart.donut { flex: 1 1 45%; min-width: 150px; height: auto; min-height: 150px; }
.legend-list { flex: 1 1 55%; min-width: 150px; overflow-y: auto; }
.legend-row {
  display: flex; align-items: center; gap: 10px;
  padding: 7px 2px; border-bottom: 1px solid var(--app-border);
}
.legend-row:last-child { border-bottom: none; }
.legend-main { flex: 1; min-width: 0; }
.legend-name { font-size: 13px; color: var(--app-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.legend-sub { font-size: 11px; color: var(--app-text3); margin-top: 2px; }
.legend-pct { font-size: 13px; color: var(--app-text2); font-variant-numeric: tabular-nums; }
</style>
