<template>
  <!-- 一段回答正文。class="md" 与 data-msg-index 都留在这里：
       md.css 靠 .md 命中排版，页面的「右栏来源 ↔ 正文角标」联动靠 data-msg-index 反查 DOM。
       外部传入的 class（如时间线段的 tl-text）由 Vue 的属性透传落到本根元素上，不用另开入口。 -->
  <div ref="rootEl" class="md" :class="{ streaming }"
       :data-msg-index="msgIndex >= 0 ? msgIndex : undefined"
       v-html="html" @click="onClick" @mouseover="onHover" @mouseleave="scheduleCloseRefTip" />
</template>

<script setup>
// ==================== 回答正文（主聊天页 / 分享页共用）====================
// 只负责「把一段 markdown 渲染出来 + 把点击/悬浮交给查看器」。
// 图片、引用角标、代码复制、沙盒运行按钮的**行为**都在 chat/answerViewer.js 与 AnswerViewerHost.vue，
// 所以新增一种正文内交互不必在聊天页和分享页各补一遍——分享页此前看不到图与引用，就是少了这层共用。
import { computed, nextTick, ref, watch } from 'vue'
import { renderMd, enhanceDiagrams } from '../utils/markdown'
import { handleBodyClick, handleBodyHover, scheduleCloseRefTip } from '../chat/answerViewer'

const props = defineProps({
  /** markdown 正文（含 [图片N] 与 [N] 角标占位） */
  content: { type: String, default: '' },
  /** 本轮图片：[图片N] 按下标取第 N 张，缺数组就等于图全成文字占位 */
  images: { type: Array, default: () => [] },
  /** 本轮引用来源：角标点开的就是 sources[n-1] */
  sources: { type: Array, default: () => [] },
  /** 代码块是否给「在云端沙盒运行」按钮：需要会话上下文，故默认关，由页面显式开 */
  runnable: { type: Boolean, default: false },
  /** 沙盒运行所需的会话号（runnable 为真时必传） */
  sessionId: { type: String, default: '' },
  /** 整条消息的序号（-1 = 不参与页面级角标联动） */
  msgIndex: { type: Number, default: -1 },
  /** 正在流式输出：挂打字光标，且**不补画 mermaid**（半截围栏必画失败） */
  streaming: { type: Boolean, default: false },
  /**
   * true（默认）：点角标/图片走共用浮层（chat/answerViewer 的来源弹窗与灯箱），悬浮出角标卡。
   * false：一律改为 emit('citation'|'preview') 交给页面自己弹——移动壳用的是底部抽屉与简化
   * 灯箱，触屏也没有 hover 悬浮卡这回事；只读会话分享页刻意不外发知识块原文，同样走这条。
   */
  viewer: { type: Boolean, default: true },
  /**
   * `[N]` 角标是否可交互（悬浮卡 + 点开来源详情）。
   * 只读会话分享页要置 false：那条链接刻意只外发文档名/章节，不外发知识块原文，
   * 点开只能得到一个空弹窗和一句误导说明——不如让角标保持纯上标（与改动前一致）。
   */
  citations: { type: Boolean, default: true }
})

const emit = defineEmits(['citation', 'preview'])

const rootEl = ref(null)
const html = computed(() => renderMd(props.content, props.images, { runnable: props.runnable }))

// citations=false 时按「本轮没有可点的来源」处理：传空数组，角标点击自然落空（不弹空窗）
const sourcesOf = () => (props.citations ? props.sources : [])

const onClick = e => handleBodyClick(e, {
  images: props.images,
  sources: sourcesOf(),
  sessionId: props.sessionId,
  ...(props.viewer ? {} : {
    onCitation: src => emit('citation', src),
    onImages: (urls, index) => emit('preview', urls, index)
  })
})
const onHover = e => { if (props.viewer && props.citations) handleBodyHover(e, props.sources) }

watch([html, () => props.streaming], async () => {
  if (props.streaming) return
  await nextTick()
  if (rootEl.value) enhanceDiagrams(rootEl.value).catch(() => { /* 绘图失败已在容器内就地提示，不外抛 */ })
}, { immediate: true })
</script>
