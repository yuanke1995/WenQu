<template>
  <!-- 批量更新：按后果分组。
       fork 类（应用模板/工作流）默认勾选（新增副本、无损）；技能默认不勾选（整包覆盖、有损），必须手动确认。 -->
  <a-modal :open="open" :title="'批量更新（' + items.length + ' 个可更新）'" :width="600" :footer="null" :mask-closable="false"
           :closable="!running" destroy-on-close @cancel="close">
    <div class="bm-head">
      <div class="bm-sub">勾选要更新的内容；技能是覆盖安装，默认不勾选。</div>
    </div>

    <div class="bm-list">
      <div v-for="it in items" :key="it.id" class="bm-row" :class="{ done: status[it.id] === 'done' }">
        <button type="button" class="bm-check" :class="{ on: selected.has(it.id) }"
                :disabled="running || status[it.id] === 'done'"
                :aria-label="'选择 ' + it.name" @click="toggle(it)">
          <check-outlined v-if="selected.has(it.id)" />
        </button>
        <span class="bm-type" :class="it.itemType">{{ typeLabel(it.itemType) }}</span>
        <div class="bm-main">
          <div class="bm-name">{{ it.name }}</div>
          <div class="bm-ver">
            {{ fmtVersion(it.installedVersion) }} <span class="bm-arrow">→</span> {{ fmtVersion(it.version) }}
          </div>
          <div class="bm-consequence" :class="{ loss: it.itemType === 'skill' }">{{ consequence(it) }}</div>
          <div v-if="status[it.id] === 'failed'" class="bm-error">{{ failMsg[it.id] }}</div>
        </div>
        <span v-if="status[it.id] === 'running'" class="bm-state">更新中…</span>
        <span v-else-if="status[it.id] === 'done'" class="bm-state ok">已更新</span>
      </div>
    </div>

    <div class="bm-foot">
      <span class="bm-sum">已选 <b>{{ selectedCount }}</b> 项{{ failureCount ? `，${failureCount} 项失败` : '' }}</span>
      <span class="bm-sp"></span>
      <button class="app-btn ghost" :disabled="running" @click="close">{{ allDone ? '完成' : '取消' }}</button>
      <button v-if="!allDone" class="app-btn" :disabled="running || !selectedCount" @click="run">
        {{ running ? '更新中…' : `更新所选（${selectedCount}）` }}
      </button>
    </div>
  </a-modal>
</template>

<script setup>
import { computed, h, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { CheckOutlined } from '@ant-design/icons-vue'
import { installMarketItem } from '../api'

const props = defineProps({
  open: { type: Boolean, default: false },
  /** 可更新清单（/market/updates 的 items） */
  items: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:open', 'done'])

const TYPE_LABEL = { skill: '技能', agent: '应用模板', workflow: '工作流', mcp: 'MCP 服务' }
const typeLabel = t => TYPE_LABEL[t] || '内容'
const consequence = it => it.itemType === 'skill'
  ? '整包覆盖同名技能：装后改动、旧版本附带的参考文件与脚本会丢失'
  : '生成一份新副本，旧副本保留'

const selected = ref(new Set())
const status = ref({})
const failMsg = ref({})
const running = ref(false)

const fmtVersion = v => {
  const s = (v || '').trim()
  if (!s) return ''
  return /^v/i.test(s) ? s : 'v' + s
}
const selectedCount = computed(() => selected.value.size)
const failureCount = computed(() => Object.values(status.value).filter(s => s === 'failed').length)
const allDone = computed(() => props.items.length > 0
  && props.items.every(it => status.value[it.id] === 'done'))

watch(() => props.open, open => {
  if (!open) return
  // 默认只勾 fork 类；技能必须人工确认，避免一键把别人改过的技能覆盖掉
  selected.value = new Set(props.items.filter(it => it.itemType !== 'skill').map(it => it.id))
  status.value = {}
  failMsg.value = {}
  running.value = false
})

const toggle = it => {
  if (running.value || status.value[it.id] === 'done') return
  const next = new Set(selected.value)
  if (next.has(it.id)) next.delete(it.id)
  else next.add(it.id)
  selected.value = next
}
const close = () => {
  if (running.value) return
  emit('update:open', false)
}

const run = async () => {
  const targets = props.items.filter(it => selected.value.has(it.id) && status.value[it.id] !== 'done')
  if (!targets.length) { message.warning('请先勾选要更新的内容'); return }
  running.value = true
  const warnings = []
  let ok = 0
  for (const it of targets) {
    status.value = { ...status.value, [it.id]: 'running' }
    try {
      const r = await installMarketItem(it.id, it.itemType === 'skill')
      if (!r || !r.success) throw new Error((r && r.msg) || '更新失败')
      status.value = { ...status.value, [it.id]: 'done' }
      // 已完成的从勾选集移除：底栏「已选 N 项」始终表示还剩多少要处理
      const next = new Set(selected.value)
      next.delete(it.id)
      selected.value = next
      ok += 1
      const ws = (r.data?.warnings || []).filter(Boolean)
      if (ws.length) warnings.push(`${it.name}：${ws.join('；')}`)
    } catch (e) {
      const msg = (e && e.message) || String(e)
      status.value = { ...status.value, [it.id]: 'failed' }
      failMsg.value = { ...failMsg.value, [it.id]: '更新失败：' + msg }
    }
  }
  running.value = false
  emit('done')
  if (ok > 0) message.success(`已更新 ${ok} 项`)
  if (warnings.length) {
    Modal.info({
      title: '更新完成，注意以下调整',
      width: 560,
      content: h('ul', { style: 'padding-left:18px;margin:8px 0 0;line-height:1.9;' },
        warnings.map(w => h('li', w))),
      okText: '知道了'
    })
  }
}
</script>

<style scoped>
.bm-head { padding-right: 24px; }
.bm-title { font-size: 15px; font-weight: 600; color: var(--app-text); }
.bm-sub { margin-top: 5px; font-size: 12px; color: var(--app-text3); }
.bm-list { margin-top: 14px; border: 1px solid var(--app-border); border-radius: 8px; overflow: hidden; }
.bm-row { display: flex; align-items: flex-start; gap: 10px; padding: 11px 12px;
  border-bottom: 1px solid var(--app-border); background: var(--app-panel); }
.bm-row:last-child { border-bottom: 0; }
.bm-row.done { background: var(--app-panel-2); }
.bm-check { width: 16px; height: 16px; flex: none; margin-top: 2px; border-radius: 4px;
  border: 1px solid var(--app-border-strong); background: var(--app-panel); color: #fff;
  display: inline-flex; align-items: center; justify-content: center; padding: 0; cursor: pointer;
  font-size: 10px; }
.bm-check.on { background: var(--app-accent); border-color: var(--app-accent); }
.bm-check:disabled { cursor: default; opacity: .7; }
.bm-type { flex: none; margin-top: 1px; font-size: 10.5px; line-height: 17px; padding: 0 7px;
  border-radius: 999px; background: var(--app-panel-2); color: var(--app-text2); }
.bm-type.agent { background: var(--app-ok-weak); color: var(--app-ok); }
.bm-type.workflow { background: var(--app-warn-weak); color: var(--app-warn-text); }
.bm-type.skill { background: var(--app-panel-2); color: var(--app-text2); }
.bm-main { flex: 1; min-width: 0; }
.bm-name { font-size: 13px; font-weight: 500; color: var(--app-text); white-space: nowrap;
  overflow: hidden; text-overflow: ellipsis; }
.bm-ver { margin-top: 3px; font-size: 11px; color: var(--app-accent); }
.bm-arrow { margin: 0 4px; color: var(--app-text3); }
.bm-consequence { margin-top: 3px; font-size: 11.5px; line-height: 1.6; color: var(--app-text3); }
.bm-consequence.loss { color: var(--app-warn-text); }
.bm-error { margin-top: 3px; font-size: 11.5px; color: var(--app-danger); }
.bm-state { flex: none; margin-top: 2px; font-size: 11.5px; color: var(--app-text3); }
.bm-state.ok { color: var(--app-ok); }
.bm-foot { display: flex; align-items: center; gap: 8px; margin-top: 16px; }
.bm-sum { font-size: 11.5px; color: var(--app-text3); }
.bm-sum b { color: var(--app-text2); font-weight: 600; }
.bm-sp { flex: 1; }
</style>
