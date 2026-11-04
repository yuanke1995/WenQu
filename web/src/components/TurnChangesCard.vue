<template>
  <!-- 每轮文件改动卡：这一轮助手在工作区里改了哪些文件、各多少行。
       行点击经 data-pv 进预览台的 diff 档——差异由本机执行器现算，快照过期会给出可读提示。 -->
  <div v-if="rows.length" class="tcc" data-changed-files>
    <button type="button" class="tcc-head" :aria-expanded="open" @click="open = !open">
      <caret-right-outlined class="tcc-caret" :class="{ on: open }" />
      <span class="tcc-title">{{ changes.total }} 个文件已更改</span>
      <span v-if="changes.total > changes.files.length" class="tcc-capped">
        （只列前 {{ changes.files.length }} 个）
      </span>
      <span class="tcc-sum">
        <span class="tcc-add">+{{ changes.added }}</span>
        <span class="tcc-del">−{{ changes.deleted }}</span>
      </span>
    </button>
    <div v-if="open" class="tcc-list">
      <a v-for="(f, i) in changes.files" :key="f.path" class="tcc-row" role="button" tabindex="0"
         data-pv :data-pv-changes="messageId || ''" :data-pv-index="String(i)" :data-pv-name="f.path"
         :title="f.binary ? '二进制文件，不显示差异' : (f.oversized ? '文件过大，不显示差异' : '查看改动差异')">
        <file-text-outlined class="tcc-ic" />
        <span class="tcc-path">
          <span class="tcc-name">{{ nameOf(f.path) }}</span>
          <span class="tcc-dir">{{ dirOf(f.path) }}</span>
        </span>
        <span v-if="f.binary" class="tcc-tag">二进制</span>
        <span v-else-if="f.oversized" class="tcc-tag">过大</span>
        <span v-else class="tcc-nums">
          <span class="tcc-add">+{{ f.added }}</span>
          <span class="tcc-del">−{{ f.deleted }}</span>
        </span>
      </a>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { CaretRightOutlined, FileTextOutlined } from '@ant-design/icons-vue'

const props = defineProps({
  /** 消息行上的 changes（{files,total,added,deleted}；null=这一轮没记录） */
  changes: { type: Object, default: null },
  /** 差异按 messageId+index 取：落库消息才有 id，实时轮在 done 帧里带 */
  messageId: { type: String, default: '' }
})

/** 折叠行数与 DSH 同款：超过 4 行默认收起，一眼只看总数 */
const COLLAPSED = 4
const open = ref(false)
const rows = computed(() => (props.changes && Array.isArray(props.changes.files)) ? props.changes.files : [])
if (rows.value.length && rows.value.length <= COLLAPSED) open.value = true

const nameOf = p => String(p || '').split('/').pop()
const dirOf = p => {
  const s = String(p || '')
  const i = s.lastIndexOf('/')
  return i > 0 ? s.slice(0, i) : ''
}
</script>

<style scoped>
.tcc { margin-top: 6px; border: 1px solid var(--app-border, rgba(0,0,0,.08)); border-radius: 8px;
  background: var(--app-surface2, rgba(0,0,0,.02)); overflow: hidden; }
.tcc-head { display: flex; align-items: center; gap: 6px; width: 100%; padding: 7px 10px;
  border: 0; background: none; cursor: pointer; text-align: left; color: var(--app-text); }
.tcc-head:hover { background: var(--app-interactive-hover, rgba(0,0,0,.04)); }
.tcc-caret { font-size: 10px; color: var(--app-text3); transition: transform .15s ease; }
.tcc-caret.on { transform: rotate(90deg); }
.tcc-title { font-size: 12px; font-weight: 600; }
.tcc-capped { font-size: 11px; color: var(--app-text3); }
.tcc-sum { margin-left: auto; display: flex; gap: 8px; font-size: 12px; font-variant-numeric: tabular-nums; }
.tcc-add { color: var(--app-ok); }
.tcc-del { color: var(--app-danger); }
.tcc-list { border-top: 1px solid var(--app-border, rgba(0,0,0,.08)); display: flex; flex-direction: column; }
.tcc-row { display: flex; align-items: center; gap: 8px; padding: 5px 10px 5px 24px;
  color: var(--app-text); cursor: pointer; font-size: 12px; }
.tcc-row:hover { background: var(--app-interactive-hover, rgba(0,0,0,.04)); }
.tcc-row:focus-visible { outline: 2px solid var(--app-accent); outline-offset: -2px; }
.tcc-ic { font-size: 13px; color: var(--app-text3); flex: none; }
.tcc-path { min-width: 0; display: flex; align-items: baseline; gap: 6px; overflow: hidden; }
.tcc-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tcc-dir { font-size: 11px; color: var(--app-text3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tcc-tag { flex: none; font-size: 11px; line-height: 16px; padding: 0 6px; border-radius: var(--app-radius-sm, 6px);
  color: var(--app-text2); background: var(--app-surface3, rgba(0,0,0,.06)); }
.tcc-nums { margin-left: auto; flex: none; display: flex; gap: 8px; font-size: 12px;
  font-variant-numeric: tabular-nums; }
</style>
