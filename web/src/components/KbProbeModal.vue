<template>
  <a-modal :open="open" :title="`检索测试 · ${kb?.name || ''}`" :width="1080" :footer="null"
           @update:open="v => emit('update:open', v)">
    <div class="probe-top">
      <a-input v-model:value="query" allow-clear placeholder="输入一个问题，只检索、不生成回答"
               :disabled="loading" @press-enter="run" />
      <a-button type="primary" :loading="loading" @click="run">检索</a-button>
    </div>
    <div class="probe-cfg">
      <span class="cfg-label">TopK</span>
      <a-input-number v-model:value="topK" :min="1" :max="20" size="small" style="width:64px" />
      <span class="cfg-sep"></span>
      <span class="cfg-label">重排</span>
      <a-switch v-model:checked="rerank" size="small" />
      <span class="cfg-sep"></span>
      <a-tag style="margin:0">混合检索</a-tag>
      <a-tag color="blue" style="margin:0">仅本库</a-tag>
      <span v-if="result?.params" class="cfg-params">
        参数：向量 {{ result.params.vectorWeight }} / 关键词 {{ result.params.keywordWeight }} / 阈值 {{ result.params.vecThreshold }}
      </span>
    </div>

    <a-spin :spinning="loading">
      <div v-if="error" class="probe-empty err">{{ error }}</div>
      <div v-else-if="!result" class="probe-empty">
        输入问题后点「检索」，查看该库命中的知识块、分数与命中判定。
      </div>
      <div v-else class="probe-split">
        <div class="probe-results">
          <div class="res-head">
            <b>召回 {{ result.recallCount }}</b>
            <span>·</span><span>重排后 {{ result.rerankedCount }}</span>
            <span>·</span><span>耗时 {{ result.latencyMs }}ms</span>
            <span class="sp"></span>
            <span class="sort-note">按融合分排序</span>
            <span :class="['verdict-pill', result.verdict === 'hit' ? 'ok' : 'none']">
              {{ result.verdict === 'hit' ? '命中' : '无命中' }}
            </span>
          </div>
          <div v-if="!result.hits.length" class="probe-empty">
            <div>没有命中任何知识块。可降低阈值、换关键词，或把这个问题加入未命中治理。</div>
            <a-button size="small" type="link" :disabled="missSaved" @click="saveMiss">
              {{ missSaved ? '已记录为未命中' : '记录为未命中' }}
            </a-button>
          </div>
          <div v-for="(h, i) in result.hits" :key="h.knowledgeId"
               :class="['hit-row', { on: selected === i, top: i === 0 }]"
               @click="selected = i">
            <div class="hit-top">
              <span class="hit-rank">{{ i + 1 }}</span>
              <span class="hit-score">{{ h.score }}<small>融合分</small></span>
              <div class="hit-src">
                <div class="hit-doc">{{ h.docName || '未知文档' }}</div>
                <div class="hit-path">{{ h.titlePath || h.title || '未分类' }}</div>
              </div>
              <span v-if="h.rerankScore != null" class="hit-rerank">重排 {{ h.rerankScore }} · #{{ i + 1 }}</span>
              <span v-else class="hit-rerank dim">未重排</span>
              <span :class="['hit-badge', hitLevel(h, i).cls]">{{ hitLevel(h, i).text }}</span>
            </div>
            <div class="hit-snippet">{{ h.snippet }}</div>
            <div class="hit-bars">
              <span class="sbar">向量
                <span class="track"><i :style="{ width: pct(h.vectorScore) }"></i></span>
                {{ h.vectorScore == null ? '—' : h.vectorScore }}
              </span>
              <span class="sbar key">关键词
                <span class="track"><i :style="{ width: pct(h.keywordScore) }"></i></span>
                {{ h.keywordScore == null ? '—' : h.keywordScore }}
              </span>
              <span class="sp"></span>
              <span :class="['gate-note', { weak: hitLevel(h, i).cls === 'weak' }]">{{ gateNote(h, i) }}</span>
            </div>
          </div>
        </div>

        <div class="probe-diag">
          <div :class="['diag-verdict', result.verdict === 'hit' ? 'ok' : 'warn']">
            <div class="dv-t">{{ result.verdict === 'hit' ? '命中 · 结果可用' : '无命中 · 需要治理' }}</div>
            <div class="dv-s">
              {{ result.verdict === 'hit'
                ? '首条直接回答了问题，可点右侧「加入评测集」固化。'
                : '可降低阈值/换关键词，或把问题加入未命中列表。' }}
            </div>
          </div>

          <div class="diag-sec">
            <div class="diag-t">检索诊断</div>
            <div class="kv"><span>召回片段</span><b>{{ result.recallCount }}</b></div>
            <div class="kv"><span>重排后</span><b>{{ result.rerankedCount }}</b></div>
            <div class="kv"><span>低于阈值被剔除</span><b>{{ result.droppedByGate }}</b></div>
            <div class="kv"><span>召回耗时</span><b>{{ result.recallMs }} ms</b></div>
            <div class="kv"><span>融合阈值</span><b>{{ result.thresholds?.minFusion }}</b></div>
            <div class="kv"><span>重排阈值</span><b>{{ result.thresholds?.minRerank }}</b></div>
            <div class="kv"><span>最低命中分</span><b :class="{ 'kv-ok': result.hits.length }">{{ minHitScore ?? '—' }}</b></div>
            <div class="kv"><span>向量模型</span><b>{{ embeddingLabel }}</b></div>
            <div class="kv"><span>重排</span><b>{{ result.rerankApplied ? '已执行' : (result.rerankSkipReason || '未执行') }}</b></div>
          </div>

          <div class="diag-sec" v-if="result.params">
            <div class="diag-t">本库生效参数</div>
            <div class="kv"><span>向量权重</span><b>{{ result.params.vectorWeight }}</b></div>
            <div class="kv"><span>关键词权重</span><b>{{ result.params.keywordWeight }}</b></div>
            <div class="kv"><span>向量阈值</span><b>{{ result.params.vecThreshold }}</b></div>
            <div class="kv"><span>重排模型</span><b>{{ friendlyModel(result.params.rerankModel) }}</b></div>
          </div>

          <div class="diag-sec">
            <div class="diag-t">结果可用吗？</div>
            <div class="fb">
              <a-button size="small" :disabled="!activeHit || feedbackSent" @click="sendFeedback(true)">有用</a-button>
              <a-button size="small" danger :disabled="!activeHit || feedbackSent" @click="sendFeedback(false)">没用</a-button>
              <span v-if="feedbackSent" class="fb-done">已记录</span>
            </div>
            <a-button type="primary" size="small" block style="margin-top:8px"
                      :disabled="!activeHit" :loading="addingEval" @click="addToEval">加入评测集</a-button>
            <div class="diag-note">「没用」会进入该库的未命中列表，并带上这次的问题与来源。</div>
          </div>

          <div class="diag-sec" v-if="suggestions.length">
            <div class="diag-t">建议动作</div>
            <div v-for="(s, i) in suggestions" :key="i" class="sug-row">
              <i :class="['sug-dot', s.type]"></i>
              <span>{{ s.text }}</span>
            </div>
          </div>

          <div class="diag-sec" v-if="result.warnings?.length">
            <div class="diag-t">降级提示</div>
            <div v-for="w in result.warnings" :key="w" class="diag-warn">{{ w }}</div>
          </div>
        </div>
      </div>
    </a-spin>

    <div class="probe-foot">
      <span class="foot-note"><safety-outlined />试检索只调用检索链路，不调用聊天模型，不产生回答费用</span>
      <span class="sp"></span>
      <a-button size="small" :disabled="!result" @click="copyResult">复制结果</a-button>
      <a-button size="small" @click="emit('update:open', false)">关闭</a-button>
    </div>
  </a-modal>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { addEvalCase, feedbackKnowledgeBase, probeKnowledgeBase, recordKbMiss } from '../api'
import { modelRefInfo } from '../utils/modelRef'
import { SafetyOutlined } from '@ant-design/icons-vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  kb: { type: Object, default: null },
  initialQuery: { type: String, default: '' }
})
const emit = defineEmits(['update:open'])

const query = ref('')
const topK = ref(8)
const rerank = ref(true)
const loading = ref(false)
const result = ref(null)
const error = ref('')
const selected = ref(0)
const feedbackSent = ref(false)
const addingEval = ref(false)
const missSaved = ref(false)

const activeHit = computed(() => (result.value?.hits || [])[selected.value] || null)

/** 当前生效的命中门：重排生效看重排阈值，否则看融合阈值（与后端 probe 的 gate 判定同口径） */
const gate = computed(() => {
  const r = result.value
  if (!r) return 0
  return Number((r.rerankApplied ? r.thresholds?.minRerank : r.thresholds?.minFusion) || 0)
})
const minHitScore = computed(() => {
  const hits = result.value?.hits || []
  if (!hits.length) return null
  return Math.min(...hits.map(h => Number(h.score || 0))).toFixed(2)
})
const embeddingLabel = computed(() => {
  const ref = props.kb?.embeddingRef || ''
  if (!ref) return '未绑定'
  return modelRefInfo(ref)?.displayName || String(ref).split('/').pop() || ref
})

/** 命中档位：首条且明显高于阈值=命中；贴近阈值=弱相关；其余=相关 */
function hitLevel (h, i) {
  const score = Number(h.score || 0)
  const g = gate.value
  if (i === 0 && (!g || score >= g * 1.4)) return { text: '命中', cls: 'ok' }
  if (g && score < g * 1.25) return { text: '弱相关', cls: 'weak' }
  return { text: '相关', cls: 'rel' }
}

function gateNote (h, i) {
  if (hitLevel(h, i).cls === 'weak') return `勉强过阈值 ${gate.value}`
  return gate.value ? `✓ 高于阈值 ${gate.value}` : '✓ 有效命中'
}

/** 建议动作：按本次结果与库状态给可执行建议，最多三条 */
const suggestions = computed(() => {
  const r = result.value
  if (!r) return []
  const out = []
  if (!r.hits.length) {
    out.push({ type: 'warn', text: '没有召回任何知识块：确认文档已入库，换更具体的关键词或降低阈值后重测' })
    out.push({ type: 'info', text: '把这个问题「记录为未命中」，后续可在未命中治理里补资料或加评测' })
  } else {
    const weak = r.hits.findIndex((h, i) => hitLevel(h, i).cls === 'weak')
    if (weak >= 0) {
      const next = gate.value ? (gate.value * 1.3).toFixed(2) : ''
      out.push({ type: 'warn', text: `第 ${weak + 1} 条为弱相关，若频繁出现可提高阈值${next ? `至 ${next}` : ''}` })
    }
    if (!r.rerankApplied) {
      out.push({ type: 'info', text: `本轮未重排：${r.rerankSkipReason || '重排未生效'}，可检查重排模型配置` })
    } else if (r.hits.length < topK.value) {
      out.push({ type: 'info', text: `仅命中 ${r.hits.length} 条（TopK ${topK.value}），该主题资料可能偏少` })
    }
    if (r.verdict === 'hit') {
      out.push({ type: 'info', text: '结果可用：可点「加入评测集」把这个问题固化为回归用例' })
    }
  }
  if (Number(props.kb?.docParsing || 0) > 0) {
    out.push({ type: 'info', text: `有 ${props.kb.docParsing} 个文档正在解析，完成后建议重新测一次` })
  }
  return out.slice(0, 3)
})

async function copyResult () {
  const r = result.value
  if (!r) return
  const lines = [
    `问题：${r.query}`,
    `结论：${r.verdict === 'hit' ? '命中' : '无命中'} · 召回 ${r.recallCount} · 重排后 ${r.rerankedCount} · ${r.latencyMs}ms`,
    ...(r.hits || []).map((h, i) =>
      `${i + 1}. 融合 ${h.score} · 向量 ${h.vectorScore ?? '—'} · 关键词 ${h.keywordScore ?? '—'}`
      + ` · 重排 ${h.rerankScore ?? '—'}\n   ${h.docName || ''} ${h.titlePath || h.title || ''}\n   ${h.snippet || ''}`)
  ]
  try {
    await navigator.clipboard.writeText(lines.join('\n'))
    message.success('已复制检索结果')
  } catch (e) {
    message.error('复制失败，请手动选中结果复制')
  }
}

function friendlyModel (ref) {
  if (!ref) return '跟随默认'
  return modelRefInfo(ref)?.displayName || String(ref).split('/').pop() || ref
}

function pct (v) {
  if (v == null) return '0%'
  return `${Math.max(0, Math.min(100, Math.round(Number(v) * 100)))}%`
}

watch(() => props.open, v => {
  if (v) {
    result.value = null
    error.value = ''
    selected.value = 0
    feedbackSent.value = false
    missSaved.value = false
    if (props.initialQuery) query.value = props.initialQuery
  }
})

async function run () {
  const q = String(query.value || '').trim()
  if (!q) { message.warning('请输入检索问题'); return }
  loading.value = true
  error.value = ''
  feedbackSent.value = false
  try {
    const r = await probeKnowledgeBase(props.kb.id, { query: q, topK: topK.value, rerank: rerank.value })
    result.value = r.data || null
    selected.value = 0
  } catch (e) {
    result.value = null
    error.value = e.message || '检索失败'
  } finally {
    loading.value = false
  }
}

async function sendFeedback (useful) {
  if (!activeHit.value) return
  try {
    await feedbackKnowledgeBase(props.kb.id, {
      knowledgeId: activeHit.value.knowledgeId,
      docId: activeHit.value.docId,
      question: query.value,
      useful
    })
    feedbackSent.value = true
    message.success('感谢反馈')
  } catch (e) {
    message.error(e.message || '反馈失败')
  }
}

async function addToEval () {
  if (!activeHit.value) return
  addingEval.value = true
  try {
    const r = await addEvalCase(query.value, [activeHit.value.knowledgeId])
    if (r.data && r.data.added === false) message.info(r.data.reason || '评测集已存在该问题')
    else message.success('已加入评测集')
  } catch (e) {
    message.error(e.message || '加入评测集失败（需要评测写权限）')
  } finally {
    addingEval.value = false
  }
}

async function saveMiss () {
  const q = String(query.value || '').trim()
  if (!q) return
  try {
    await recordKbMiss(props.kb.id, { question: q })
    missSaved.value = true
    message.success('已记录为未命中，可在库概览的「未命中」里查看')
  } catch (e) {
    message.error(e.message || '记录失败')
  }
}
</script>

<style scoped>
.probe-top { display: flex; gap: 8px; }
.probe-cfg { display: flex; align-items: center; gap: 8px; margin: 10px 0 12px; flex-wrap: wrap; }
.cfg-label { font-size: 12px; color: var(--app-text3); }
.cfg-sep { width: 1px; height: 14px; background: var(--app-border); }
.cfg-hint { font-size: 11px; color: var(--app-text3); margin-left: auto; }
.cfg-params { font-size: 10.5px; color: var(--app-text3); margin-left: auto; }
.probe-empty { border: 1px dashed var(--app-border); border-radius: 8px; padding: 42px 16px; text-align: center;
  font-size: 12px; color: var(--app-text3); }
.probe-empty.err { color: var(--app-danger); border-color: #ffccc7; background: #fff2f0; }
.probe-split { display: flex; gap: 12px; min-height: 420px; }
.probe-results { flex: 1; min-width: 0; }
.res-head { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--app-text3); margin-bottom: 8px; }
.res-head b { color: var(--app-text); }
.res-head .sp { flex: 1; }
.verdict-pill { font-size: 11px; padding: 1px 8px; border-radius: 999px; }
.verdict-pill.ok { color: var(--app-ok); background: #eaf5ec; }
.verdict-pill.none { color: var(--app-warn-text); background: var(--app-warn-weak); }
.sort-note { font-size: 10.5px; color: var(--app-text3); }
.hit-badge { flex: none; font-size: 10px; padding: 1px 7px; border-radius: 999px; }
.hit-badge.ok { color: var(--app-accent); background: var(--app-accent-weak); }
.hit-badge.rel { color: var(--app-ok); background: var(--app-ok-weak); }
.hit-badge.weak { color: var(--app-warn-text); background: var(--app-warn-weak); }
.hit-row { border: 1px solid var(--app-border); border-radius: 8px; padding: 9px 11px; margin-bottom: 8px; cursor: pointer; }
.hit-row:hover { border-color: var(--app-accent-border); }
.hit-row.on { border-color: var(--app-accent); box-shadow: 0 0 0 3px rgba(46,107,230,.08); }
.hit-row.top { border-color: #cfe6d4; }
.hit-row.top.on { border-color: var(--app-accent); }
.hit-top { display: flex; align-items: center; gap: 8px; min-width: 0; }
.hit-rank { width: 18px; height: 18px; border-radius: 5px; background: var(--app-panel-2); color: var(--app-text3);
  font-size: 10px; display: inline-flex; align-items: center; justify-content: center; flex: none; }
.hit-row.top .hit-rank { background: var(--app-accent); color: #fff; }
.hit-score { font-size: 15px; font-weight: 650; color: var(--app-accent); flex: none; }
.hit-score small { font-size: 9px; color: var(--app-text3); font-weight: 400; margin-left: 2px; }
.hit-src { flex: 1; min-width: 0; }
.hit-doc { font-size: 12px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.hit-path { font-size: 10.5px; color: var(--app-text3); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.hit-rerank { flex: none; font-size: 10.5px; color: var(--app-ok); background: var(--app-ok-weak);
  border-radius: 4px; padding: 1px 6px; }
.hit-rerank.dim { color: var(--app-text3); background: var(--app-panel-2); }
.hit-snippet { margin-top: 5px; font-size: 11.5px; color: var(--app-text2); line-height: 1.6;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.hit-bars { display: flex; align-items: center; gap: 12px; margin-top: 6px; font-size: 9.5px; color: var(--app-text3); }
.sbar { display: inline-flex; align-items: center; gap: 5px; }
.sbar .track { width: 48px; height: 4px; border-radius: 999px; background: #eef0f3; overflow: hidden; }
.sbar .track i { display: block; height: 100%; background: var(--app-accent); }
.sbar.key .track i { background: var(--c-teal, #0f8a8a); }
.hit-bars .sp { flex: 1; }
.hit-bars .fusion { color: var(--app-text2); }
.gate-note { font-size: 9.5px; color: var(--app-text3); }
.gate-note.weak { color: var(--app-warn-text); }
.kv b.kv-ok { color: var(--app-ok); }
.probe-diag { width: 320px; flex: none; border-left: 1px solid var(--app-border); padding-left: 14px; }
.diag-verdict { border-radius: 8px; padding: 10px 12px; }
.diag-verdict.ok { background: var(--app-ok-weak); border: 1px solid #cfe6d4; }
.diag-verdict.warn { background: var(--app-warn-weak); border: 1px solid var(--app-warn-border); }
.dv-t { font-size: 13px; font-weight: 600; color: var(--app-ok); }
.diag-verdict.warn .dv-t { color: var(--app-warn-text); }
.dv-s { font-size: 11px; color: var(--app-text2); line-height: 1.6; margin-top: 3px; }
.diag-sec { margin-top: 12px; }
.diag-t { font-size: 11px; font-weight: 600; color: var(--app-text3); margin-bottom: 6px; }
.kv { display: flex; align-items: center; font-size: 11.5px; padding: 4px 0; border-bottom: 1px dashed var(--app-border); }
.kv:last-child { border-bottom: none; }
.kv span { color: var(--app-text3); }
.kv b { margin-left: auto; color: var(--app-text); font-weight: 500; }
.fb { display: flex; gap: 6px; align-items: center; }
.fb-done { font-size: 11px; color: var(--app-ok); }
.diag-note { font-size: 10.5px; color: var(--app-text3); line-height: 1.6; margin-top: 6px; }
.diag-warn { font-size: 11px; color: var(--app-warn-text); background: var(--app-warn-weak); border-radius: 5px;
  padding: 5px 8px; margin-bottom: 5px; line-height: 1.5; }
.sug-row { display: flex; align-items: flex-start; gap: 6px; font-size: 11px; color: var(--app-text2);
  line-height: 1.6; padding: 3px 0; }
.sug-dot { width: 6px; height: 6px; border-radius: 50%; margin-top: 6px; flex: none; background: var(--app-text3); }
.sug-dot.warn { background: var(--app-warn); }
.sug-dot.info { background: var(--app-accent); }
.probe-foot { display: flex; align-items: center; gap: 8px; margin-top: 12px; padding-top: 10px;
  border-top: 1px solid var(--app-border); }
.probe-foot .sp { flex: 1; }
.foot-note { display: inline-flex; align-items: center; gap: 5px; font-size: 10.5px; color: var(--app-text3); }
</style>
