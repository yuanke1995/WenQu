<template>
  <!-- 个人近 7 天调用统计抽屉（页头用量小字 / 卡片用量点击打开） -->
  <a-drawer :open="open" :width="460" :closable="false"
            :body-style="{ padding: '0', display: 'flex', flexDirection: 'column' }"
            @close="close" @update:open="v => emit('update:open', v)">
    <div class="st">
      <div class="st-head">
        <div class="st-title">近 7 天调用统计</div>
        <div class="st-sub">{{ rangeText }} · 仅统计你自己的真实调用</div>
        <button class="st-x app-icon-btn" aria-label="关闭统计" @click="close"><close-outlined /></button>
      </div>
      <div class="st-body">
        <div class="st-sum">
          <div class="st-cell"><div class="k">总调用</div><div class="v">{{ stats.total || 0 }}</div></div>
          <div class="st-cell"><div class="k">成功率</div><div class="v">{{ rate }}%</div></div>
          <div class="st-cell"><div class="k">平均耗时</div><div class="v">{{ stats.avgMs || 0 }}<span class="u"> ms</span></div></div>
        </div>

        <template v-if="loading && !stats.total">
          <div class="st-loading">统计加载中…</div>
        </template>
        <template v-else-if="!stats.total">
          <div class="st-empty-state">近 7 天暂无调用记录</div>
        </template>
        <template v-else>
          <div class="st-sec">按服务</div>
          <table class="st-tbl">
            <thead><tr><th>服务</th><th class="num">调用</th><th class="num">成功率</th><th class="num">平均耗时</th></tr></thead>
            <tbody>
              <tr v-for="row in byServer" :key="row.server">
                <td class="name">{{ row.server }}</td>
                <td class="num">{{ row.calls }}</td>
                <td class="num">{{ rowRate(row) }}%</td>
                <td class="num">{{ row.avgMs }}ms</td>
              </tr>
              <tr v-if="!byServer.length"><td colspan="4" class="st-empty">近 7 天暂无调用记录</td></tr>
            </tbody>
          </table>

          <div class="st-sec">按工具（Top 20）</div>
          <table class="st-tbl">
            <thead><tr><th>工具</th><th class="num">调用</th><th class="num">失败</th><th class="num">平均耗时</th></tr></thead>
            <tbody>
              <tr v-for="row in byTool" :key="row.tool">
                <td class="name">
                  <template v-if="labelOf(row.tool)">{{ labelOf(row.tool) }}<span class="raw">{{ row.tool }}</span></template>
                  <template v-else>{{ row.tool }}</template>
                </td>
                <td class="num">{{ row.calls }}</td>
                <td class="num">{{ row.failed }}</td>
                <td class="num">{{ row.avgMs }}ms</td>
              </tr>
              <tr v-if="!byTool.length"><td colspan="4" class="st-empty">近 7 天暂无调用记录</td></tr>
            </tbody>
          </table>
        </template>
      </div>
      <div class="st-foot">统计口径：本人在对话与工作流中的真实调用（试调用计入）；已删除服务的记录保留原样显示。</div>
    </div>
  </a-drawer>
</template>

<script setup>
import { computed } from 'vue'
import { CloseOutlined } from '@ant-design/icons-vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  stats: { type: Object, default: () => ({}) },
  loading: { type: Boolean, default: false },
  /** 工具裸名 → 用户起的中文名（未起名的不在表里） */
  labels: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['update:open'])

const close = () => emit('update:open', false)

const byServer = computed(() => props.stats.byServer || [])
const byTool = computed(() => props.stats.byTool || [])

const rate = computed(() => {
  const total = Number(props.stats.total || 0)
  const failed = Number(props.stats.failed || 0)
  return total ? Math.round((total - failed) / total * 100) : 0
})

const rowRate = row => {
  const calls = Number(row.calls || 0)
  const failed = Number(row.failed || 0)
  return calls ? Math.round((calls - failed) / calls * 100) : 0
}

const labelOf = name => props.labels[name] || ''

/** 近 7 天日期区间（按用户本地时区，不做时区换算） */
const rangeText = computed(() => {
  const end = new Date()
  const start = new Date()
  start.setDate(end.getDate() - 6)
  const md = d => `${d.getMonth() + 1}月${d.getDate()}日`
  return `${md(start)} – ${md(end)}`
})
</script>

<style scoped>
.st { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.st-head { flex: none; padding: 18px 20px 12px; border-bottom: 1px solid var(--app-border); position: relative; }
.st-title { font-size: 15px; font-weight: 600; color: var(--app-text); }
.st-sub { margin-top: 4px; font-size: 12px; color: var(--app-text3); }
.st-x { position: absolute; right: 14px; top: 14px; }
.st-body { flex: 1; min-height: 0; padding: 16px 20px; overflow-y: auto; }
.st-sum { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.st-cell { background: var(--app-panel-2); border: 1px solid var(--app-border); border-radius: 8px; padding: 9px 10px; }
.st-cell .k { font-size: 11px; color: var(--app-text3); }
.st-cell .v { margin-top: 3px; font-size: 17px; font-weight: 600; color: var(--app-text); font-variant-numeric: tabular-nums; }
.st-cell .u { font-size: 11px; font-weight: 400; color: var(--app-text3); }
.st-sec { margin: 18px 0 6px; font-size: 12px; color: var(--app-text2); }
.st-tbl { width: 100%; border-collapse: collapse; }
.st-tbl th { font-size: 11px; font-weight: 400; color: var(--app-text3); text-align: left; padding: 0 0 6px; border-bottom: 1px solid var(--app-border); }
.st-tbl th.num { text-align: right; }
.st-tbl td { font-size: 12px; color: var(--app-text2); padding: 7px 0; border-bottom: 1px solid #f1f3f5; font-variant-numeric: tabular-nums; }
.st-tbl td.name { color: var(--app-text); }
.st-tbl td.num { text-align: right; }
.st-tbl td .raw { display: block; font-family: var(--mono, ui-monospace, SFMono-Regular, Menlo, monospace); font-size: 10.5px; color: var(--app-text3); margin-top: 1px; }
.st-tbl td.st-empty { text-align: center; color: var(--app-text3); padding: 16px 0; }
.st-loading { padding: 24px 0; text-align: center; font-size: 12px; color: var(--app-text3); }
.st-empty-state { padding: 40px 0; text-align: center; font-size: 12px; color: var(--app-text3); }
.st-foot { flex: none; padding: 12px 20px 16px; border-top: 1px solid var(--app-border); font-size: 11.5px; line-height: 1.75; color: var(--app-text3); }
</style>
