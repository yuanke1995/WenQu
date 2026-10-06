<template>
  <!-- 单轮操作（消息行「⋯」入口）：导出这轮问答 / 加入评测集 / 检索调试 / 删除本轮。
       PC 是消息操作行上的 a-dropdown；触屏没有 hover，收进底部 sheet。
       两项排障动作（评测/调试）沿用 PC 的同一开关 chat.retrievalDebugEnabled（debugEntryVisible），
       不给普通用户露出必 403 的入口。 -->
  <BottomSheet :open="open" :title="view === 'debug' ? '本轮检索调试' : '本轮操作'"
               :subtitle="view === 'debug' ? '' : roundLabel" max-height="82dvh" @close="$emit('close')">
    <!-- ==================== 检索调试结果 ==================== -->
    <div v-if="view === 'debug'" class="rd">
      <div class="rd-q">{{ debugQuestion || '（未取到本轮问题）' }}</div>
      <div v-if="debugLoading" class="rd-empty">正在跑检索调试…</div>
      <div v-else-if="!debugResult" class="rd-empty">没有调试结果</div>
      <template v-else>
        <div v-if="debugResult.keywordTerms && debugResult.keywordTerms.length" class="rd-terms">
          <span v-for="(t, ti) in debugResult.keywordTerms" :key="ti" class="rd-term">{{ t }}</span>
        </div>
        <div v-for="(st, si) in debugStages" :key="si" class="rd-stage">
          <div class="rd-stage-name">{{ st.name }}</div>
          <div v-if="!st.items.length" class="rd-empty">（无）</div>
          <div v-for="(it, ii) in st.items" :key="ii" class="rd-item">
            <div class="rd-item-head">
              <span class="rd-item-title">{{ it.title }}</span>
              <span class="rd-item-tag">{{ it.tag }}</span>
            </div>
            <div v-if="it.docName" class="rd-item-doc">{{ it.docName }}</div>
            <div v-if="it.snippet" class="rd-item-snip">{{ it.snippet }}</div>
          </div>
        </div>
      </template>
      <button class="rd-btn wide" type="button" @click="view = 'menu'">返回操作</button>
    </div>

    <!-- ==================== 操作菜单 ==================== -->
    <div v-else class="rd">
      <button class="rd-btn" type="button" :disabled="exporting" @click="doExport">
        <file-markdown-outlined /><span>{{ exporting ? '正在导出…' : '导出这轮问答' }}</span>
      </button>
      <!-- 双通道（与整会话导出同一课）：微信内置浏览器与 iOS Safari 对 <a download> 支持不一致，
           "复制全文"不是降级方案，而是这条路上必须有的一条出口 -->
      <template v-if="built">
        <div class="rd-row">
          <button class="rd-btn" type="button" @click="saveBuilt"><download-outlined /><span>保存文件</span></button>
          <button class="rd-btn" type="button" @click="copyBuilt"><copy-outlined /><span>复制全文</span></button>
        </div>
        <div class="rd-tip">全文 {{ builtSize }} · {{ built.fileName }}；保存文件无反应时用「复制全文」</div>
      </template>

      <button v-if="canAddEval" class="rd-btn" type="button" @click="addToEval">
        <dislike-outlined /><span>加入评测集</span>
      </button>
      <button v-if="debugEntryVisible" class="rd-btn" type="button" @click="openDebug">
        <bug-outlined /><span>检索调试</span>
      </button>
      <button class="rd-btn danger" type="button" @click="doDelete">
        <delete-outlined /><span>删除本轮对话</span>
      </button>
      <div class="rd-tip">删除本轮 = 这条回答连同它对应的提问一起删除（不可恢复）。</div>
    </div>
  </BottomSheet>
</template>

<script setup>
import { computed, inject, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { BugOutlined, CopyOutlined, DeleteOutlined, DislikeOutlined, DownloadOutlined, FileMarkdownOutlined } from '@ant-design/icons-vue'
import BottomSheet from './BottomSheet.vue'
import { buildAnswerMd, saveMd } from '../views/exportMd'
import { addEvalCase, debugRetrieval, deleteMessageGroup } from '../api'
import { copyText } from '../utils/clipboard'
import { loadSessions } from '../views/store'

const props = defineProps({
  open: { type: Boolean, default: false },
  /** 目标 AI 消息在 messages 里的下标 */
  index: { type: Number, default: -1 }
})
const emit = defineEmits(['close'])

const engine = inject('wqChat')
const { messages, currentSessionTitle, debugEntryVisible } = engine

const m = computed(() => messages.value[props.index] || null)
/** 本轮提问与前一条回答配对：向前找最近的用户消息，遇更早的回答即停（与 PC 的配对口径一致） */
const pairQuestion = () => {
  for (let i = props.index - 1; i >= 0; i--) {
    const x = messages.value[i]
    if (!x) continue        // 删除本轮后数组当场变短，下标可能越过新长度（sheet 关停前还有一帧重渲染）
    if (x.role === 'user') return x
    if (x.role === 'ai' || x.role === 'assistant') break
  }
  return null
}
const roundLabel = computed(() => {
  const q = (pairQuestion()?.content || '').trim().replace(/\s+/g, ' ')
  return q ? (q.length > 24 ? q.slice(0, 24) + '…' : q) : '这轮回答'
})

// ==================== 导出这轮问答 ====================
// 与 PC 共用 buildAnswerMd（图片尽量内嵌）；落盘这一下在手机上不保证可用，故双通道显式给出
const exporting = ref(false)
const built = ref(null)
const builtSize = computed(() => {
  if (!built.value) return ''
  const n = new Blob([built.value.md]).size
  return n > 1024 * 1024 ? (n / 1024 / 1024).toFixed(1) + ' MB' : Math.max(1, Math.round(n / 1024)) + ' KB'
})
const doExport = async () => {
  const a = m.value
  if (!a || !a.content) { message.warning('该回答无可导出内容'); return }
  exporting.value = true
  try {
    built.value = await buildAnswerMd({ answer: a, question: pairQuestion(), title: currentSessionTitle.value })
  } catch (e) {
    message.error(e.message || '导出失败')
  } finally {
    exporting.value = false
  }
}
const saveBuilt = () => saveMd(built.value)
const copyBuilt = () => copyText(built.value?.md, '全文已复制，可粘贴到备忘录')

// ==================== 加入评测集（差评回流固化） ====================
const canAddEval = computed(() => debugEntryVisible.value && (m.value?.sources || []).length > 0)
const addToEval = async () => {
  const kids = [...new Set((m.value?.sources || []).map(s => s.knowledgeId).filter(Boolean))]
  if (!kids.length) { message.warning('本轮没有可固化的知识块引用'); return }
  const q = pairQuestion()
  if (!q?.content) { message.warning('找不到本轮对应的提问'); return }
  try {
    const r = await addEvalCase(q.content, kids)
    if (r && r.success !== false) {
      if (r.data?.added === false) message.info(r.data.reason || '评估集已存在相同问题')
      else message.success(`已加入评测集（期望块 ${r.data?.expected ?? kids.length} 个，可在检索评估页跑回归）`)
    } else message.error(r?.msg || '加入评测集失败')
  } catch (e) { message.error(e.message || '加入评测集失败') }
}

// ==================== 检索调试 ====================
const view = ref('menu')
const debugQuestion = ref('')
const debugLoading = ref(false)
const debugResult = ref(null)
// 分阶段展示与 PC 调试弹窗同一口径（六段：关键词/向量/合并/重排/最终上下文/被排除）
const debugStages = computed(() => {
  const d = debugResult.value
  if (!d) return []
  const map = (items, tagFn) => (items || []).map(it => ({
    title: it.title || '（无标题）',
    docName: it.docName || '',
    snippet: it.snippet || '',
    tag: tagFn(it)
  }))
  return [
    { name: `关键词命中（${(d.keywordHits || []).length}）`, items: map(d.keywordHits, it => '命中率 ' + (it.hitRate ?? 0)) },
    { name: `向量命中（${(d.vectorHits || []).length}）`, items: map(d.vectorHits, it => '相似度 ' + (it.score ?? 0)) },
    { name: `合并后（${(d.merged || []).length}）`, items: map(d.merged, it => '分 ' + (it.score ?? 0)) },
    { name: `重排后（${(d.reranked || []).length}）` + (d.rerankApplied ? '' : `（${d.rerankSkipReason || '未重排'}）`), items: map(d.reranked, it => '分 ' + (it.score ?? 0)) },
    { name: `最终上下文（${(d.finalContext || []).length}/8）`, items: map(d.finalContext, it => '分 ' + (it.score ?? 0)) },
    { name: `被排除（${(d.excluded || []).length}）`, items: map(d.excluded, it => '分 ' + (it.score ?? 0)) }
  ]
})
const openDebug = () => {
  debugQuestion.value = (pairQuestion()?.content || '').trim()
  debugResult.value = null
  view.value = 'debug'
  runDebug()
}
const runDebug = async () => {
  if (!debugQuestion.value) { message.warning('找不到本轮对应的提问'); return }
  debugLoading.value = true
  try {
    const r = await debugRetrieval(debugQuestion.value)
    if (r.success) debugResult.value = r.data
    else message.error(r.msg || '调试失败')
  } catch (e) { message.error(e.message || '调试失败') }
  finally { debugLoading.value = false }
}

// ==================== 删除本轮对话 ====================
// 与 PC 同口径：后端按回答 messageId 删除整组（回答 + 同组问题）软删，本地同步移除
const doDelete = () => {
  const mid = m.value?.messageId
  if (!mid) { message.warning('该轮对话不可删除'); return }
  Modal.confirm({
    title: '删除本轮对话',
    content: '这条回答与它对应的提问会一起删除，且不可恢复。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      try {
        const r = await deleteMessageGroup(mid)
        if (r.success) {
          const mi = props.index
          let from = mi
          if (mi > 0 && messages.value[mi - 1]?.role === 'user') from = mi - 1
          messages.value.splice(from, mi - from + 1)
          message.success('已删除本轮对话')
          loadSessions().catch(() => {})
          emit('close')
        } else message.error(r.msg || '删除失败')
      } catch (e) { message.error(e.message || '删除失败') }
    }
  })
}

// 每次打开回到菜单态并清掉上轮的导出/调试结果（否则换一条消息会看到别人的结论）
watch(() => props.open, v => { if (v) resetLocal() })
watch(() => props.index, () => resetLocal())
function resetLocal () {
  view.value = 'menu'
  built.value = null
  debugResult.value = null
  debugQuestion.value = ''
}
</script>

<style scoped>
.rd { display: flex; flex-direction: column; gap: 8px; }
.rd-row { display: flex; gap: 8px; }
.rd-btn {
  flex: 1; min-width: 0; min-height: 46px; padding: 0 14px;
  display: inline-flex; align-items: center; justify-content: center; gap: 6px;
  border: 1px solid var(--app-border); border-radius: 10px; background: var(--app-panel);
  color: var(--app-text2); font-size: 14px; touch-action: manipulation;
}
.rd-btn:active:not(:disabled) { background: var(--app-accent-weak); color: var(--app-accent); }
.rd-btn:disabled { opacity: .55; }
.rd-btn.wide { width: 100%; }
.rd-btn.danger { color: var(--app-danger); border-color: var(--app-danger-border); }
.rd-btn.danger:active { background: var(--app-danger-weak); color: var(--app-danger); }
.rd-tip { font-size: 12px; line-height: 1.6; color: var(--app-text3); padding: 4px 2px 0; }
.rd-empty { font-size: 13px; color: var(--app-text3); padding: 6px 2px; }
.rd-q { font-size: 13px; color: var(--app-text2); background: var(--app-panel-2); border-radius: 8px; padding: 10px; line-height: 1.6; }
.rd-terms { display: flex; flex-wrap: wrap; gap: 6px; }
.rd-term { font-size: 12px; color: var(--app-accent); background: var(--app-info-weak, var(--app-accent-weak)); border-radius: 6px; padding: 3px 8px; }
.rd-stage { display: flex; flex-direction: column; gap: 6px; margin-top: 6px; }
.rd-stage-name { font-size: 12px; color: var(--app-text3); }
.rd-item { border: 1px solid var(--app-border); border-radius: 10px; padding: 8px 10px; display: flex; flex-direction: column; gap: 4px; }
.rd-item-head { display: flex; align-items: baseline; gap: 8px; }
.rd-item-title { flex: 1; min-width: 0; font-size: 13px; color: var(--app-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rd-item-tag { flex: none; font-size: 12px; color: var(--app-accent); }
.rd-item-doc { font-size: 12px; color: var(--app-text3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rd-item-snip { font-size: 12px; color: var(--app-text3); line-height: 1.6; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
</style>
