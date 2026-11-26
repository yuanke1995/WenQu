<script setup>
/**
 * 一条回答的动作行（主对话页与公开分享页共用）：复制 / 有帮助 / 没帮助 / 重新生成。
 *
 * <p>为什么抽出来：四个动作主对话页与分享页都要有——游客拿到答案带不走、
 * 答歪了只能原样重打一遍问题，而发布者连一条质量信号都收不到。两处各写一份的代价不是多几十行，
 * 是「主对话页加了第五个动作，分享页永远少一个」。
 *
 * <p><b>只画按钮与可用态，「点了做什么」交给页面</b>：
 * <ul>
 *   <li>复制在组件内完成（{@code copyText} 是共享工具，两边语义完全相同）；</li>
 *   <li>评价 emit 出去——主对话页点👎会弹一个可选的意见框，分享页是一击提交（访客是路人，
 *       多一步就少一大截反馈量），这个差别属于页面策略，不属于按钮；</li>
 *   <li>重新生成 emit 出去——两边的重发载荷不同（主对话页要带技能/@引用/#历史引用）。
 *       主对话页把它收进「更多」菜单（传 {@code regenerateInMore}），本组件就不再单独画那颗按钮；
 *       分享页没有更多菜单，仍由本组件直接给按钮。</li>
 * </ul>
 *
 * <p>按钮一律「图标 + 悬浮说明」而不是「图标 + 文字」：主对话页就是这个口径，
 * 窄屏也放得下。页面自己的附加物走插槽：{@code #prefix}（版本切换器）、
 * {@code #more}（更多菜单，只在有 messageId 时出现）、{@code #extra}（用量/时间，始终出现）。
 */
import { message } from 'ant-design-vue'
import { CopyOutlined, LikeOutlined, DislikeOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { copyText } from '../utils/clipboard'

const props = defineProps({
  /** 回答正文（复制用） */
  content: { type: String, default: '' },
  /**
   * 落库消息 ID。为空时**只渲染 prefix/extra 插槽**，四个按钮整组不出现——
   * 与主对话页一致：没有 ID 就没有评价与重新生成的落点，摆一个点了报错的按钮不如不摆
   * （流式进行中、或凭据开始落库之前的存量消息，都会命中这一档）。
   */
  messageId: { type: String, default: '' },
  /** 既有评价：null=未评，1=有帮助，0=没帮助（评过即锁定，两边一致） */
  fb: { type: Number, default: null },
  /** 正在生成：重新生成在此期间不可点 */
  loading: { type: Boolean, default: false },
  /**
   * 是否提供「重新生成」。
   * <p>公开分享页只给**最后一轮**（中间那轮重跑会与后面几轮的历史错位）；
   * 主对话页每一轮都给（它有多版本切换器兜着，切回旧版本是明确支持的）。
   * 这是页面策略，所以做成开关而不是让组件去猜。
   */
  canRegenerate: { type: Boolean, default: true },
  /**
   * 「重新生成」是否已由页面收进它自己的 {@code #more} 菜单。
   * <p>为 true 时本组件不再单独画那颗按钮（主对话页口径）；分享页没有更多菜单，
   * 保持 false，由本组件直接给按钮。
   */
  regenerateInMore: { type: Boolean, default: false }
})
const emit = defineEmits(['feedback', 'regenerate'])

async function doCopy () {
  const txt = (props.content || '').trim()
  if (!txt) { message.warning('该回答无可复制内容'); return }
  await copyText(txt)
}
</script>

<template>
  <div class="fb-row">
    <slot name="prefix" />
    <template v-if="messageId">
      <button class="fb-btn" type="button" title="复制回答" @click="doCopy">
        <copy-outlined /><span class="fb-label">复制</span>
      </button>
      <a-tooltip :title="fb != null ? '已评价' : '这条回答有帮助'">
        <button class="fb-btn" type="button" :class="{ 'fb-active': fb === 1 }" :disabled="fb != null"
                @click="emit('feedback', 1)"><like-outlined /><span class="fb-label">有帮助</span></button>
      </a-tooltip>
      <a-tooltip :title="fb != null ? '已评价' : '这条回答没帮助'">
        <button class="fb-btn" type="button" :class="{ 'fb-active': fb === 0 }" :disabled="fb != null"
                @click="emit('feedback', 0)"><dislike-outlined /><span class="fb-label">没帮助</span></button>
      </a-tooltip>
      <a-tooltip v-if="canRegenerate && !regenerateInMore" title="重新生成这条回答">
        <button class="fb-btn" type="button" :disabled="loading" @click="emit('regenerate')">
          <reload-outlined /><span class="fb-label">重新生成</span>
        </button>
      </a-tooltip>
      <slot name="more" />
    </template>
    <!-- 用量/时间等元信息：与前面的按钮分开成一组，显隐交给所在页面控制
         （主对话页做成「悬浮才显示」——悬停这条消息时淡入，见 ChatThread.vue）。
         分隔线随这一组一起走：元信息整组不显示时不会留一条悬空竖线。 -->
    <span v-if="$slots.extra" class="fb-meta">
      <span v-if="messageId" class="fb-sep" aria-hidden="true"></span>
      <slot name="extra" />
    </span>
  </div>
</template>

<style scoped>
/* 动作行：与正文同一列、贴着回答下方一行。间距故意只有 8px——它是这一条回答的脚注，
   不是新的一块内容 */
.fb-row { margin-top: 8px; display: flex; align-items: center; flex-wrap: wrap; gap: 2px; }
/* 图标 + 文字：纯图标只有悬浮才知道是什么，长回答里更找不到；文字常驻、颜色仍弱于正文 */
.fb-btn {
  height: 27px; padding: 0 7px; border: 1px solid transparent; border-radius: 7px;
  background: transparent; color: var(--app-text2); font: inherit; font-size: 11.5px;
  display: inline-flex; align-items: center; gap: 5px; white-space: nowrap; cursor: pointer;
  transition: background .12s, color .12s;
}
.fb-btn:hover:not(:disabled) { background: var(--app-panel-2); color: var(--app-text); }
.fb-btn:disabled { cursor: default; opacity: .55; }
.fb-btn :deep(svg) { width: 14px; height: 14px; }
.fb-sep { width: 1px; height: 12px; background: var(--app-border); margin: 0 4px; flex: none; }
/* 元信息组（分隔线 + 用量/时间）：独立成组，显隐由所在页面控制。
   主对话页要的是「悬浮到这一行才显示」——指悬浮到本条动作行时淡入，所以触发点放在
   本组件的根 .fb-row 上；触屏没有 hover，不加规则保持常显，否则手机上看不到用量与时间。 */
.fb-meta { display: inline-flex; align-items: center; }
@media (hover: hover) {
  .fb-meta { opacity: 0; pointer-events: none; transition: opacity .12s ease; }
  .fb-row:hover .fb-meta,
  .fb-row:focus-within .fb-meta { opacity: 1; pointer-events: auto; }
}
/* 已评价态：钉在强调色上，用户一眼看出自己评过哪一条（同一条不能再评，按钮同时被禁用） */
.fb-active { color: var(--app-accent); }
/* 窄屏：保留文字（触屏没有悬浮说明），只把热区做到 34px；再窄才收掉文字 */
@media (max-width: 768px) {
  .fb-row { gap: 3px; }
  .fb-btn { min-height: 34px; padding: 0 8px; font-size: 12px; }
  .fb-btn :deep(svg) { width: 15px; height: 15px; }
}
@media (max-width: 430px) {
  .fb-label { display: none; }
  .fb-btn { min-width: 34px; justify-content: center; padding: 0 7px; }
  .fb-sep { margin: 0 2px; }
}
</style>
