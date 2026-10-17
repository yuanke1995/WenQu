<script setup>
/**
 * 一条回答的动作行（主对话页与公开分享页共用）：复制 / 有帮助 / 没帮助 / 重新生成。
 *
 * <p>为什么抽出来：这四个动作主对话页一直有，分享页此前<b>一个都没有</b>——游客拿到答案带不走、
 * 答歪了只能原样重打一遍问题，而发布者连一条质量信号都收不到。两处各写一份的代价不是多几十行，
 * 是「主对话页加了第五个动作，分享页永远少一个」。
 *
 * <p><b>只画按钮与可用态，「点了做什么」交给页面</b>：
 * <ul>
 *   <li>复制在组件内完成（{@code copyText} 是共享工具，两边语义完全相同）；</li>
 *   <li>评价 emit 出去——主对话页点👎会弹一个可选的意见框，分享页是一击提交（访客是路人，
 *       多一步就少一大截反馈量），这个差别属于页面策略，不属于按钮；</li>
 *   <li>重新生成 emit 出去——两边的重发载荷不同（主对话页要带技能/@引用/#历史引用）。</li>
 * </ul>
 *
 * <p>按钮一律「图标 + 悬浮说明」而不是「图标 + 文字」：主对话页就是这个口径，
 * 窄屏也放得下四个。页面自己的附加物走插槽：{@code #prefix}（版本切换器）、
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
  canRegenerate: { type: Boolean, default: true }
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
      <a-tooltip title="复制"><button class="app-icon-btn" @click="doCopy"><copy-outlined /></button></a-tooltip>
      <a-tooltip :title="fb != null ? '已评价' : '有帮助'">
        <button class="app-icon-btn" :class="{ 'fb-active': fb === 1 }" :disabled="fb != null"
                @click="emit('feedback', 1)"><like-outlined /></button>
      </a-tooltip>
      <a-tooltip :title="fb != null ? '已评价' : '没帮助'">
        <button class="app-icon-btn" :class="{ 'fb-active': fb === 0 }" :disabled="fb != null"
                @click="emit('feedback', 0)"><dislike-outlined /></button>
      </a-tooltip>
      <a-tooltip v-if="canRegenerate" title="重新生成">
        <button class="app-icon-btn" :disabled="loading" @click="emit('regenerate')"><reload-outlined /></button>
      </a-tooltip>
      <slot name="more" />
    </template>
    <slot name="extra" />
  </div>
</template>

<style scoped>
/* 动作行：与正文同一列、贴着回答下方一行。间距故意只有 8px——它是这一条回答的脚注，
   不是新的一块内容 */
.fb-row { margin-top: 8px; display: flex; align-items: center; gap: 2px; }
/* 已评价态：钉在强调色上，用户一眼看出自己评过哪一条（同一条不能再评，按钮同时被禁用） */
.fb-active { color: var(--app-accent); }
/* 窄屏把整行做成触控友好的热区（图标 15px、按钮 34px）：分享链接大量在手机上打开，
   而这四个动作是游客唯一能对回答做的事 */
@media (max-width: 768px) {
  .fb-row { gap: 4px; }
  .fb-row .app-icon-btn { min-width: 34px; min-height: 34px; font-size: 15px; }
}
</style>
