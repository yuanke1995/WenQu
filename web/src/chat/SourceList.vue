<script setup>
/**
 * 引用来源折叠块（主对话页与公开分享页共用）。
 *
 * <p>两家的形态本来就同源：一行可点的摘要行 + 展开后的明细。差别只在信息量——
 * 主对话页还要列检索词与精确检索词、每条来源带原文摘要片段；分享页只列来源本身
 * （它没有排障语境，游客也不关心"用了什么检索词"）。所以这些差异做成开关而不是两份实现。
 *
 * <p>来源名与「是否外部来源」的判定统一走 {@code sourceName / externalOrigin}：
 * 那两处口径曾经各写一份，结果是联网来源在分享页统一显示成「来源文档不可用」
 * （裁剪时丢了 origin 字段，而分享页靠它区分联网/库内）。
 */
import { CaretRightOutlined } from '@ant-design/icons-vue'
import { sourceName } from './projections'
import { openSource } from './answerViewer'

defineProps({
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
  /** 展开态（v-model:open 双向） */
  open: { type: Boolean, default: false }
})
const emit = defineEmits(['update:open'])
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
      <div v-for="(s, si) in (sources || [])" :key="si" class="rt-ref"
           :title="s.origin === 'WEB' ? '点击查看来源摘要与原网页' : '点击查看原文'" @click="openSource(s)">
        <span class="rt-ref-tag">[{{ s.ref }}]</span>
        <span v-if="s.origin === 'WEB'" class="rt-ref-web">联网</span>
        <span v-else-if="s.origin === 'MCP'" class="rt-ref-web" title="MCP 工具返回的来源">MCP</span>
        {{ sourceName(s) }}
        <div v-if="showSnippet && s.snippet" class="rt-snip">{{ s.snippet }}</div>
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
.rt-ref { padding: 3px 0; border-top: 1px dashed var(--app-border); cursor: pointer; }
.rt-ref:hover { color: var(--app-accent); }
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
