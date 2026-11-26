<template>
    <a-modal :open="store.shareVisible" title="分享这段对话" :footer="null" width="520px">
      <a-spin :spinning="store.shareLoading">
        <template v-if="store.shareInfo.enabled">
          <div class="share-link-row">
            <input class="share-link" :value="store.shareUrl" readonly @focus="$event.target.select()" />
            <button class="app-btn" @click="store.copyShareLink"><copy-outlined /> 复制</button>
          </div>
          <div class="share-meta">
            链接展示的是这段对话的<strong>最新内容</strong>（后续继续提问也会一并出现在分享页）；
            拿到链接的人只能查看，不能继续提问<span v-if="store.shareInfo.visitCount"> · 已被访问 {{ store.shareInfo.visitCount }} 次</span>
          </div>
          <div class="share-warn">
            分享内容包含完整问答与引用来源的文档名/章节。请确认其中没有不适合外发的内容。
          </div>
          <div class="share-actions">
            <button class="app-btn ghost" @click="store.openSharedPage">预览</button>
            <button class="app-btn ghost" @click="store.regenerateShareLink">换一个新链接</button>
            <button class="app-btn ghost danger" @click="store.doDisableShare">停止分享</button>
          </div>
        </template>
        <template v-else>
          <p class="share-intro">
            生成一个只读链接，拿到链接的人可以查看这段对话的问答与引用来源，<strong>不能继续提问</strong>。
          </p>
          <p class="share-intro">链接随时可以停止；停止后重新生成会换新链接，旧链接立即失效。</p>
          <button class="app-btn" @click="store.doEnableShare"><share-alt-outlined /> 生成分享链接</button>
        </template>
      </a-spin>
    </a-modal>

    <!-- 回答反馈弹窗 -->
    <a-modal :open="store.feedbackVisible" title="反馈" :footer="null" :width="440">
      <a-textarea v-model:value="store.feedbackText" placeholder="可选：告诉我们哪里不满意" :rows="3" />
      <button class="app-btn" style="margin-top:12px" :disabled="store.feedbackSubmitting" @click="store.doSubmitFeedback">提交反馈</button>
    </a-modal>

    <!-- 免责声明 -->
    <a-modal :open="store.disclaimerVisible" title="免责声明" :footer="null" :width="560">
      <div class="md" style="max-height:60vh;overflow-y:auto" v-html="store.renderMd(store.DISCLAIMER_TEXT, [])"></div>
    </a-modal>

    <!-- 检索调试弹窗 -->
    <a-modal :open="store.debugVisible" title="检索调试（为什么这么答）" :footer="null" :width="780">
      <div style="display:flex;gap:8px;margin-bottom:12px">
        <a-input v-model:value="store.debugQuestion" placeholder="输入要调试的问题" @press-enter="store.runDebug" />
        <button class="app-btn" :disabled="store.debugLoading" @click="store.runDebug">调试</button>
      </div>
      <a-spin :spinning="store.debugLoading">
        <template v-if="store.debugResult">
          <div v-if="store.debugResult.rewrite" class="dbg-rewrite">
            <span class="dbg-terms-label">追问改写</span>
            <template v-if="store.debugResult.rewrite.applied">
              <a-tag color="green" size="small">已改写</a-tag>
              <span class="dbg-rw-text">{{ store.debugResult.rewrite.original }} → {{ store.debugResult.rewrite.query }}</span>
            </template>
            <template v-else>
              <a-tag size="small">未改写</a-tag>
              <span class="dbg-rw-text">{{ store.rewriteSkipText(store.debugResult.rewrite.skipReason) }}</span>
            </template>
          </div>
          <div v-if="store.debugResult.keywordTerms?.length" class="dbg-terms">
            <span class="dbg-terms-label">检索词元</span>
            <a-tag v-for="(t, ti) in store.debugResult.keywordTerms" :key="ti" color="blue" style="margin:2px">{{ t }}</a-tag>
          </div>
          <div v-if="store.debugResult.retrievalDiag?.subQueries?.length" class="dbg-terms">
            <span class="dbg-terms-label">多问识别</span>
            <a-tag size="small">仅诊断，检索未拆分</a-tag>
            <a-tag v-for="(sq, qi) in store.debugResult.retrievalDiag.subQueries" :key="qi" color="purple" style="margin:2px">{{ sq }}</a-tag>
          </div>
          <a-collapse :bordered="false" :default-active-key="['final']">
            <a-collapse-panel v-for="(s, si) in store.debugStages" :key="si" :name="si === 4 ? 'final' : String(si)" :header="s.name">
              <template v-if="s.items.length">
              <div v-for="(it, ii) in s.items" :key="ii" class="dbg-item">
                <div class="dbg-head">
                  <span class="dbg-title">{{ it.title }}</span>
                  <a-tag v-if="it.docName" size="small">{{ it.docName }}</a-tag>
                  <a-tag color="blue" size="small">{{ it.tag }}</a-tag>
                </div>
                <div class="dbg-snippet">{{ it.snippet }}</div>
              </div>
              </template>
              <a-empty v-else description="无命中" />
            </a-collapse-panel>
          </a-collapse>
        </template>
      </a-spin>
    </a-modal>

    <!-- 早期对话摘要：分隔线「查看摘要」打开。内容在打开那一刻快照下来（见引擎 openCompactSummary），
         否则发送下一轮会清掉当场结果、弹窗跟着变空 -->
    <a-modal :open="store.compactSummaryOpen" :footer="null" width="720px">
      <template #title>
        <div class="csum-head">
          <span class="csum-title">早期对话摘要</span>
        </div>
      </template>
      <div class="csum-stats">
        <div v-if="store.compactSummary && store.compactSummary.freed > 0" class="csum-stat">
          <span class="cs-k">本次腾出</span><span class="cs-v">约 {{ store.fmtTokens(store.compactSummary.freed) }} tokens</span>
        </div>
        <div v-if="store.compactSummary" class="csum-stat">
          <span class="cs-k">摘要规模</span><span class="cs-v">{{ store.fmtTokens(store.compactSummary.tokens) }} tokens</span>
        </div>
        <div v-if="store.compactSummary && store.compactSummary.keepTurns > 0" class="csum-stat">
          <span class="cs-k">逐字保留</span><span class="cs-v">最近 {{ store.compactSummary.keepTurns }} 次问答</span>
        </div>
      </div>
      <!-- 摘要正文本身是模型给的条目化 Markdown：按 Markdown 渲染，别用 pre 把 - 和 # 原样摊出来 -->
      <div class="md csum-md" v-html="store.renderMd(store.compactSummary ? store.compactSummary.text : '')"></div>
      <div class="csum-foot">
        <span class="csum-tip">完整问答仍保留，可往上回看</span>
        <div class="csum-acts">
          <button class="app-btn ghost small" type="button" @click="store.csumInstrOpen = !store.csumInstrOpen">
            <edit-outlined /> 带要求重新压缩
          </button>
          <button class="app-btn small" type="button" @click="store.copyText(store.compactSummary ? store.compactSummary.text : '', '已复制摘要')">
            <copy-outlined /> 复制摘要
          </button>
        </div>
      </div>
      <!-- 这一次摘要要保留什么、忽略什么：交给后端并进框架的摘要提示词，只作用这一次压缩，不留到之后的轮 -->
      <div v-if="store.csumInstrOpen" class="csum-instr">
        <input :value="store.csumInstr" @update:value="v => set('csumInstr', v)" class="csum-instr-input" maxlength="200"
               placeholder="这次摘要要保留什么、忽略什么" @keyup.enter="store.applyRecompact" />
        <button class="app-btn small" type="button" :disabled="store.compacting" @click="store.applyRecompact">
          {{ store.compacting ? '压缩中…' : '重新压缩' }}
        </button>
        <button class="app-btn ghost small" type="button" @click="set('csumInstrOpen', false)">取消</button>
      </div>
    </a-modal>

    <!-- 粘贴文本卡片点开看全文：正文只留在本轮内存里（随问答落库的是名称/体积），
         所以刷新后回看历史卡片会明确提示不可预览，而不是点了没反应 -->
    <a-modal :open="store.pasteView.open" :title="store.pasteTitle(store.pasteView.name)" :footer="null" width="640px">
      <div class="paste-view-bar">
        <span class="paste-view-meta">{{ store.pasteView.text.length }} 字</span>
        <button class="app-btn ghost" @click="store.copyText(store.pasteView.text, '已复制全文')"><copy-outlined /> 复制全文</button>
      </div>
      <pre class="csum-body">{{ store.pasteView.text }}</pre>
    </a-modal>

</template>

<script setup>
import { CopyOutlined, EditOutlined, ShareAltOutlined } from '@ant-design/icons-vue'
import { inject } from 'vue'
// 父页 provide('chatCtx')：一个 reactive 上下文（含 ref 与动作）
const store = inject('chatCtx')
const set = (k, v) => { store[k] = v }
</script>

<style scoped src="./ChatPageModals.css"></style>
