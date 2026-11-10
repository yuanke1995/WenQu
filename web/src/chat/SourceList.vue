<script setup>
/**
 * 引用来源折叠块（主对话页与公开分享页共用）。
 *
 * <p>两家的形态本来就同源：一行可点的摘要行 + 展开后的明细。差别只在信息量——
 * 主对话页还要列检索词与精确检索词、每条来源带原文摘要片段；分享页只列来源本身
 * （它没有排障语境，游客也不关心"用了什么检索词"）。所以这些差异做成开关而不是两份实现。
 *
 * <p>来源名与「是否外部来源」的判定统一走 {@code sourceName / externalOrigin}：
 * 联网来源在分享页要靠 origin 字段与库内来源区分，
 * 裁剪时丢了它就会统一显示成「来源文档不可用」。
 */
import { ref } from 'vue'
import { CaretRightOutlined, DislikeOutlined, LikeOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { externalOrigin, sourceName } from './projections'
import { openSource } from './answerViewer'
import { feedbackKnowledgeByChunk } from '../api'

const props = defineProps({
  /** 来源列表：[{ref, origin, siteName, fileName, title, url, snippet, score}] */
  sources: { type: Array, default: () => [] },
  /** 折叠行文案（主对话页传检索概览，分享页传「引用 N 条来源」） */
  title: { type: String, default: '' },
  /** 折叠行后缀（如「（N 段填入上下文）」），没有就不显示 */
  note: { type: String, default: '' },
  /** 检索词（主对话页排障用；分享页不传） */
  terms: { type: Array, default: () => [] },
  /** 精确检索（工具）用的检索词，同上 */
  toolQueries: { type: Array, default: () => [] },
  /** 是否在来源名下显示原文片段（主对话页显示，分享页不显示） */
  showSnippet: { type: Boolean, default: false },
  /** 触发本轮回答的用户问题（反馈回流用；分享页不传） */
  question: { type: String, default: '' },
  /** 是否显示引用反馈（游客分享页关闭） */
  canFeedback: { type: Boolean, default: true },
  /** 展开态（v-model:open 双向） */
  open: { type: Boolean, default: false }
})
const emit = defineEmits(['update:open'])

const fbSent = ref({})

async function sendFb (s, useful) {
  if (!props.canFeedback || externalOrigin(s) || !s.knowledgeId) return
  try {
    await feedbackKnowledgeByChunk({
      knowledgeId: s.knowledgeId,
      docId: s.docId,
      question: props.question,
      useful
    })
    fbSent.value = { ...fbSent.value, [s.ref]: true }
    message.success('感谢反馈')
  } catch (e) {
    message.error(e.message || '反馈失败')
  }
}
</script>

<template>
  <div class="retrieval-merged">
    <div class="retrieval-line" @click="emit('update:open', !open)">
      <template v-if="title">{{ title }}<template v-if="note">{{ note }}</template></template>
      <caret-right-outlined class="tl-caret" :class="{ open }" />
    </div>
    <div v-if="open" class="retrieval-detail">
      <div v-if="terms && terms.length" class="rt-terms">检索词：{{ terms.join('、') }}</div>
      <div v-if="toolQueries && toolQueries.length" class="rt-terms rt-tool-terms">
        <span class="rt-tool-tag">精确检索</span>{{ toolQueries.join('；') }}
      </div>
      <div v-for="(s, si) in (sources || [])" :key="si" class="rt-ref-row">
        <div class="rt-ref"
             :title="s.origin === 'WEB' ? '点击查看来源摘要与原网页' : '点击查看原文'" @click="openSource(s)">
          <span class="rt-ref-tag">[{{ s.ref }}]</span>
          <span v-if="s.origin === 'WEB'" class="rt-ref-web">联网</span>
          <span v-else-if="s.origin === 'MCP'" class="rt-ref-web" title="MCP 工具返回的来源">MCP</span>
          {{ sourceName(s) }}
          <div v-if="showSnippet && s.snippet" class="rt-snip">{{ s.snippet }}</div>
        </div>
        <div v-if="canFeedback && !externalOrigin(s) && s.knowledgeId" class="rt-fb" @click.stop>
          <template v-if="fbSent[s.ref]">
            <span class="rt-fb-done">已反馈</span>
          </template>
          <template v-else>
            <button class="rt-fb-btn" title="这条引用有用" @click="sendFb(s, true)"><like-outlined /></button>
            <button class="rt-fb-btn no" title="这条引用没用" @click="sendFb(s, false)"><dislike-outlined /></button>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.retrieval-merged { margin-top: 8px; width: 100%; }
.retrieval-line {
  display: flex; align-items: center; gap: 2px; font-size: 12px;
  color: var(--app-text3); user-select: none; cursor: pointer;
}
.retrieval-line:hover { color: var(--app-accent); }
.retrieval-detail {
  font-size: 12px; color: var(--app-text2); background: var(--app-panel-2); border: 1px solid var(--app-border);
  border-radius: 8px; padding: 8px 10px; margin: 4px 0 2px; line-height: 1.6;
  /* 来源多时（十几个片段）整块会顶掉大半屏聊天记录：限高后内部滚动，短列表仍按内容收缩 */
  max-height: 320px; overflow-y: auto; overscroll-behavior: contain;
  scrollbar-width: thin; scrollbar-gutter: stable;
}
.rt-terms { margin-bottom: 6px; }
/* 末行不留尾部外边距：卡片内末行若带 margin-bottom，多出的间距会落在 padding 内侧，
   把单行文字顶得偏上、卡片下沿多出等量空白 */
.retrieval-detail > :last-child { margin-bottom: 0; }
.rt-tool-terms { color: var(--app-accent); }
.rt-tool-tag {
  display: inline-block; font-size: 10px; line-height: 1; padding: 3px 6px; border-radius: 999px;
  background: var(--app-accent-weak); color: var(--app-accent); margin-right: 6px; vertical-align: 1px;
}
.rt-ref { padding: 3px 0; border-top: 1px dashed var(--app-border); cursor: pointer; flex: 1; min-width: 0; }
.rt-ref:hover { color: var(--app-accent); }
.rt-ref-row { display: flex; align-items: flex-start; gap: 6px; }
.rt-fb { display: inline-flex; align-items: center; gap: 2px; flex: none; padding-top: 2px; }
.rt-fb-btn { width: 20px; height: 20px; border: none; background: transparent; color: var(--app-text3);
  border-radius: 4px; display: inline-flex; align-items: center; justify-content: center; cursor: pointer; font-size: 11px; padding: 0; }
.rt-fb-btn:hover { color: var(--app-ok); background: var(--app-ok-weak); }
.rt-fb-btn.no:hover { color: var(--app-danger); background: #fff2f0; }
.rt-fb-done { font-size: 10px; color: var(--app-text3); }
.rt-ref-tag { color: var(--app-accent); margin-right: 4px; }
/* 联网来源标记：与库内来源区分开，用户有权知道这条依据是网上的还是库里的 */
.rt-ref-web {
  margin: 0 6px; padding: 0 5px; border-radius: 4px; background: var(--app-panel-2);
  color: var(--app-text3); font-size: 11px;
}
.rt-snip { color: var(--app-text3); margin-top: 2px; }
/* 折叠箭头（与时间线共用同一个类名与旋转口径） */
.tl-caret { font-size: 10px; transition: transform .18s; }
.tl-caret.open { transform: rotate(90deg); }
</style>
