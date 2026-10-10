<template>
  <!-- 只在真有留存时出现：卡片里的预览已经说明「以上为预览」，这句是它唯一的续读入口 -->
  <button class="spill-btn" type="button" :disabled="loading" :title="'读回这条工具调用的完整' + kind + '原文'"
          @click="load">
    <download-outlined /> 查看完整{{ kind }}<template v-if="loading">（读取中…）</template>
  </button>

  <a-modal v-model:open="shown" :title="title" width="min(920px, 94vw)" :footer="null" class="spill-modal">
    <div class="spill-bar">
      <input v-model="q" class="spill-q" type="text" placeholder="在这份原文里筛关键字（按行）…" />
      <span class="spill-meta">{{ metaText }}</span>
    </div>
    <pre v-if="bodyText" class="spill-body">{{ bodyText }}</pre>
    <div v-else-if="err" class="spill-err">{{ err }}</div>
    <div v-else class="spill-err">这份原文是空的（工具没有回显内容）。</div>
  </a-modal>
</template>

<script setup>
/**
 * 工具大输出的「查看完整输出/入参」：卡片里只放了前 8K 字预览，其余整份存在服务端磁盘上
 * （见 ToolSpillService），点这里按需读回。关键字过滤在已读回的这份原文里做（按行筛），
 * 不建索引、不查服务端 —— 用户要的通常是「那句报错在哪」，滚两屏找不到才贴关键字进来筛。
 */
import { computed, ref } from 'vue'
import { getToolOutput } from '../api'

const props = defineProps({
  id: { type: String, default: '' },
  tool: { type: String, default: '' },
  kind: { type: String, default: '输出' }   // 输出 | 入参（写文件那种超长内容也留得住）
})

const shown = ref(false)
const loading = ref(false)
const err = ref('')
const q = ref('')
const raw = ref('')
const totalChars = ref(0)
const capped = ref(false)      // 单次读回有体积上限（服务端截过）
const outOfBand = ref(false)   // 原文本身在落盘时就只留了前段

const title = computed(() => (props.tool ? `完整${props.kind}：${props.tool}` : `完整${props.kind}`))

const lines = computed(() => {
  const t = raw.value
  if (!t) return []
  const kw = q.value.trim().toLowerCase()
  const all = t.split('\n')
  if (!kw) return all
  return all.filter(l => l.toLowerCase().includes(kw))
})

const bodyText = computed(() => {
  if (!raw.value) return ''
  if (!q.value.trim()) return lines.value.join('\n')
  return lines.value.length ? `匹配 ${lines.value.length} 行\n\n${lines.value.join('\n')}` : ''
})

const metaText = computed(() => {
  if (!raw.value) return ''
  const parts = [`共 ${totalChars.value.toLocaleString()} 字`]
  if (q.value.trim()) parts.push(lines.value.length ? `筛出 ${lines.value.length} 行` : '没有匹配行')
  if (capped.value) parts.push('一次最多读回 512K 字，后面未包含')
  if (outOfBand.value) parts.push('原文过大，服务端只留存了前 4M 字')
  return parts.join(' · ')
})

async function load () {
  if (!props.id) { err.value = '这条记录没有可读回的原文标识'; shown.value = true; return }
  shown.value = true
  if (raw.value || err.value) return          // 读过一次就用手里这份，反复点开不该重复打接口
  loading.value = true
  err.value = ''
  try {
    const r = await getToolOutput(props.id)
    const d = (r && r.data) || null
    if (!d) { err.value = '这份原文读不回来了（可能已超过保留期被清理）'; return }
    raw.value = d.content || ''
    totalChars.value = Number(d.chars) || raw.value.length
    capped.value = !!d.truncated
    outOfBand.value = !!d.outOfBand
  } catch (e) {
    err.value = '读取失败：' + (e?.message || '网络异常')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/* 安静的一行小字按钮：卡片正文已经在讲执行结果，续读入口不该抢版面 */
.spill-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  padding: 2px 8px;
  border: 1px solid var(--app-border, #d9d9d9);
  border-radius: 6px;
  background: transparent;
  color: var(--app-text-secondary, #666);
  font-size: 12px;
  cursor: pointer;
}
.spill-btn:hover { color: var(--app-accent, #1677ff); border-color: var(--app-accent, #1677ff); }
.spill-btn:disabled { opacity: .6; cursor: default; }
.spill-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; flex-wrap: wrap; }
.spill-q {
  flex: 1 1 240px;
  padding: 5px 9px;
  border: 1px solid var(--app-border, #d9d9d9);
  border-radius: 6px;
  background: var(--app-bg-elevated, #fff);
  color: var(--app-text, #222);
  font-size: 13px;
}
.spill-meta { font-size: 12px; color: var(--app-text-secondary, #888); }
.spill-body {
  max-height: 58vh;
  overflow: auto;
  margin: 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--app-bg-input, #f6f7f9);
  color: var(--app-text, #222);
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}
.spill-err { font-size: 13px; color: var(--app-danger, #d4380d); padding: 8px 2px; }
</style>
