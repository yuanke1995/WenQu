<template>
  <!-- 引用来源详情弹窗 -->
  <a-modal v-model:open="sourceVisible" :title="sourceTitle" :footer="null" :width="sourceImages.length ? 720 : 560"
           wrap-class-name="source-modal" :keyboard="!previewUrl" :mask-closable="!previewUrl">
    <a-spin v-if="sourceLoading" style="display:block;margin:40px auto" />
    <div v-else class="md src-content" @click="onSourceBodyClick"
         v-html="renderMd(prepKnowledgeContent(sourceContent || sourceSnippet, sourceImages), sourceImages, rich)"></div>
    <a v-if="sourceUrl" class="src-origin-link" :href="sourceUrl" target="_blank" rel="noopener">打开原网页</a>
  </a-modal>

  <!-- 引用角标悬浮卡：Teleport 到 body（不被消息区 overflow 裁剪），fixed 定位跟随角标 -->
  <Teleport to="body">
    <div v-if="refTip" class="ref-card" :style="refCardStyle"
         @mouseenter="cancelCloseRefTip" @mouseleave="scheduleCloseRefTip">
      <div class="ref-card-head">
        <span class="ref-card-no">[{{ refTip.ref }}]</span>
        <span class="ref-card-file" :title="refTip.fileName">{{ refTip.fileName }}</span>
        <span v-if="debugVisible && refTip.score != null" class="ref-card-score" :title="refTip.scoreLabel">
          {{ refTip.scoreLabel }} {{ Number(refTip.score).toFixed(2) }}
        </span>
      </div>
      <div v-if="refTip.title" class="ref-card-title">§ {{ refTip.title }}</div>
      <div class="ref-card-snippet">{{ refTip.snippet }}</div>
      <button v-if="refTip.src" class="ref-card-btn" type="button" @click.stop="refTipOpenSource">查看原文 →</button>
    </div>
  </Teleport>

  <!-- 图片灯箱：多图切换 / 滚轮缩放 / 拖动平移 / ESC 关闭 -->
  <div v-if="previewUrl" class="lightbox" @click="closeLightbox" @wheel.prevent="onWheel">
    <img :src="previewUrl" alt="大图预览" @click.stop @error="onImgError" class="lightbox-img"
         :style="{ transform: 'translate(' + offset.x + 'px,' + offset.y + 'px) scale(' + zoom + ')' }"
         @mousedown="onImgMouseDown" @mousemove="onImgMouseMove" @mouseup="onImgMouseUp" @mouseleave="onImgMouseUp" @dblclick="resetView" />
    <button v-if="previewList.length > 1" class="lightbox-prev" :disabled="previewIndex === 0" @click.stop="prevImg">‹</button>
    <button v-if="previewList.length > 1" class="lightbox-next" :disabled="previewIndex === previewList.length - 1" @click.stop="nextImg">›</button>
    <span class="lightbox-close" @click.stop="closeLightbox">×</span>
    <span v-if="previewList.length > 1" class="lightbox-count">{{ previewIndex + 1 }} / {{ previewList.length }}</span>
    <span class="lightbox-tip">滚轮缩放 · 拖动平移 · 双击重置 · ESC 关闭</span>
  </div>
</template>

<script setup>
// ==================== 回答正文浮层的宿主（来源弹窗 / 角标悬浮卡 / 图片灯箱）====================
// 状态与动作在 chat/answerViewer.js，本组件只出 DOM：**每个用到 AnswerBody 的页面挂一次**
// （主聊天页、智能体分享页、会话只读分享页）。挂两次会同时存在两份灯箱，
// 多图切换时互相打架——所以约定是「一页一宿主」，而不是每条消息一个。
import { computed } from 'vue'
import { renderMd, prepKnowledgeContent, onImgError } from '../utils/markdown'
import {
  previewList, previewIndex, previewUrl, zoom, offset,
  closeLightbox, prevImg, nextImg, resetView, onWheel,
  onImgMouseDown, onImgMouseMove, onImgMouseUp,
  sourceVisible, sourceLoading, sourceTitle, sourceContent, sourceSnippet, sourceImages, sourceUrl,
  refTip, refCardStyle, cancelCloseRefTip, scheduleCloseRefTip, refTipOpenSource, handleBodyClick
} from '../chat/answerViewer'

const props = defineProps({
  /** 相关度分值等调参信息是否露出（页面按 chat.retrievalDebugEnabled 传） */
  debugVisible: { type: Boolean, default: false },
  /** 弹窗内代码块给不给「在云端沙盒运行」：游客页没有沙盒权限，留空即只读 */
  sessionId: { type: String, default: '' }
})

const rich = computed(() => ({ runnable: !!props.sessionId }))
// 原文里再点图片/代码块，走的仍是同一套正文委托（图片用该来源自己的 images，无引用角标）
const onSourceBodyClick = e => handleBodyClick(e, { images: sourceImages.value, sources: [], sessionId: props.sessionId })
</script>
