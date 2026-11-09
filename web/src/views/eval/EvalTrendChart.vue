<template>
  <div ref="el" class="ev-chart" :style="{ height }"></div>
</template>

<script setup>
import { onMounted, onUnmounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent, MarkLineComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([LineChart, GridComponent, LegendComponent, TooltipComponent, MarkLineComponent, CanvasRenderer])

const props = defineProps({
  rows: { type: Array, default: () => [] },
  series: { type: Array, default: () => [] },
  height: { type: String, default: '220px' },
  threshold: { type: Number, default: null }
})

const el = ref(null)
let chart = null

const labels = rows => (rows || []).map(r => {
  const t = String(r.time || '')
  return t.length >= 10 ? t.slice(5, 10) : t
})

function render () {
  if (!el.value) return
  if (!chart) chart = echarts.init(el.value)
  const rows = props.rows || []
  const series = (props.series || []).map((s, i) => ({
    name: s.label,
    type: 'line',
    smooth: true,
    showSymbol: rows.length <= 20,
    symbolSize: 5,
    data: rows.map(r => r[s.key] == null ? null : Number(r[s.key])),
    lineStyle: { width: 2, color: s.color },
    itemStyle: { color: s.color },
    areaStyle: i === 0 ? { color: s.color, opacity: 0.08 } : undefined,
    markLine: i === 0 && props.threshold != null ? {
      silent: true,
      symbol: 'none',
      label: { formatter: `阈值 ${props.threshold}`, color: '#98a0aa', fontSize: 10 },
      lineStyle: { color: '#c77c14', type: 'dashed', width: 1, opacity: 0.6 },
      data: [{ yAxis: props.threshold }]
    } : undefined
  }))
  chart.setOption({
    animation: false,
    grid: { left: 8, right: 12, top: 26, bottom: 4, containLabel: true },
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(255,255,255,.98)',
      borderColor: '#e6e9ed',
      textStyle: { color: '#1f2329', fontSize: 12 }
    },
    legend: { right: 0, top: 0, icon: 'roundRect', itemWidth: 8, itemHeight: 8, textStyle: { fontSize: 10, color: '#5f6570' } },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: labels(rows),
      axisLine: { lineStyle: { color: '#e6e9ed' } },
      axisTick: { show: false },
      axisLabel: { color: '#98a0aa', fontSize: 10 }
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 1,
      splitLine: { lineStyle: { color: '#eef1f5', type: 'dashed' } },
      axisLabel: { color: '#98a0aa', fontSize: 10, formatter: v => Number(v).toFixed(1) }
    },
    series
  }, true)
  chart.resize()
}

onMounted(render)
watch(() => [props.rows, props.series, props.threshold], render, { deep: true })
const onResize = () => chart && chart.resize()
window.addEventListener('resize', onResize)
onUnmounted(() => {
  window.removeEventListener('resize', onResize)
  if (chart) { chart.dispose(); chart = null }
})
</script>

<style scoped>
.ev-chart { width: 100%; }
</style>
