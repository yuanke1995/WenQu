<script setup>
/**
 * 消息列表外壳（主对话页与公开分享页共用）：一行一条消息的布局 + 两页逐字相同的那些渲染。
 *
 * <p><b>共享的部分</b>（放这里，两页不再各写一份）：居中列与行距、消息块的左右贴边、
 * 用户气泡的外观、整轮用时那一条、以及四个叶子的**出现位置与次第**：
 * 提问随行材料 → 正文 → 降级条 → 引用来源 → 动作行。次第是"读起来对不对"的核心，
 * 两个页面各摆一次，迟早有一个把降级条摆到正文上面去。
 *
 * <p><b>不共享的部分走插槽</b>，而且插槽位置是固定的——页面能换掉"画什么"，换不掉"摆哪儿"：
 * <ul>
 *   <li>{@code #row-before}   —— 行之前（主对话页的消息分隔线：上下文压缩点、模型切换点）</li>
 *   <li>{@code #user-meta}    —— 提问随行材料之后（主对话页的 @引用 / #历史引用）</li>
 *   <li>{@code #body-before}  —— 正文之前（主对话页的回答归属徽标）</li>
 *   <li>{@code #body}         —— 正文本身。**不给就退化成共用兜底**（TurnBar + AnswerBody），
 *                                 公开分享页与将来的简单壳直接用兜底；主对话页给的是时间线/工具卡那一套</li>
 *   <li>{@code #body-after}   —— 正文之后（错误卡、忙碌提示、派发提示、工具状态、计划卡、审批卡、产物）</li>
 *   <li>{@code #sources}      —— 引用来源。不给就用共用 SourceList（分享页口径）；主对话页给带检索词的版本</li>
 *   <li>{@code #sources-after}—— 来源之后（子智能体编排卡、「接下来可以」）</li>
 *   <li>{@code #actions-prefix|more|extra} —— 动作行内部的三处附加位（版本切换器 / 更多菜单 / 用量与时间）</li>
 *   <li>{@code #actions-after}—— 动作行之后（本轮已停止说明、复制/编辑行、就地编辑卡）</li>
 * </ul>
 *
 * <p><b>滚动容器不在这里</b>：主对话页是 {@code .messages}（带滚动条槽补偿），分享页是 {@code .sc-list}，
 * 两边的空态、尾随留白、回到底部按钮也都各属其壳。本组件只出「行」，页面自己摆这些壳里的东西。
 */
import AnswerBody from '../components/AnswerBody.vue'
import UserBubble from './UserBubble.vue'
import DegradationBar from './DegradationBar.vue'
import SourceList from './SourceList.vue'
import AnswerActions from './AnswerActions.vue'
import TurnBar from '../components/TurnBar.vue'
import { retrievalLineTitle } from './projections'

const props = defineProps({
  /** 消息数组（两页的形状一致：role/content/images/sources/messageId/fb/degradations…） */
  messages: { type: Array, default: () => [] },
  /** 正在就地编辑的行下标（-1=没有）。编辑中要顶掉原气泡与操作行，由本组件统一执行 */
  editingIndex: { type: Number, default: -1 },
  /** 兜底正文给不给「在云端沙盒运行」（分享页不给：游客链路不暴露沙盒） */
  runnable: { type: Boolean, default: false },
  /** 沙盒运行所需的会话号 */
  sessionId: { type: String, default: '' },
  /**
   * 是否给某一轮「重新生成」。默认每轮都给（主对话页口径）；
   * 公开分享页只给最后一轮——中间那轮重跑会与后面几轮的历史错位。
   */
  canRegenerate: { type: Function, default: () => () => true }
})
const emit = defineEmits(['paste-view', 'feedback', 'regenerate'])

/** 动作行的出现条件：不在生成中，且至少有一样可展开的东西（落库凭据 / 时间 / 正文）。
 *  <p>"有正文"这一档是给失败轮留的：那一轮没有 messageId 也没有 time，
 *  但正文里留着半程内容与错误说明，复制入口不该跟着一起消失。 */
function showActions (m) {
  return m.role === 'ai' && !m.loading && !!(m.messageId || m.time || m.content)
}
function hasSources (m) {
  return !!(m.sources && m.sources.length)
}
/** 来源折叠行的文案：与主对话页同一条投影（retrievalLineTitle），它给不出东西时才退回来源条数。
 *  <p>刻意不各写一份：同一件事在两边说成「参考 2 段资料」和「引用 2 条来源」，
 *  用户会读成两回事，而维护的人迟早只改一边。 */
function sourceTitle (m) {
  return retrievalLineTitle(m) || ('引用 ' + ((m.sources && m.sources.length) || 0) + ' 条来源')
}
</script>

<template>
  <!-- 键用消息对象本身，不用下标：插话会插到正在生成的那颗回答**前面**（顺序与刷新后一致），
       按下标键那一颗及其后每一行都要换键重挂——长回答整段重渲染、mermaid 重画、展开态丢。 -->
  <div v-for="(m, i) in messages" :key="m" :data-row-index="i" class="row" :class="m.role">
    <slot name="row-before" :m="m" :i="i" />
    <div class="msg-block" :class="[m.role, { editing: editingIndex === i }]">
      <div class="bubble" :class="m.role">
        <!-- 目标指令回声：句首挂一枚 /goal 命令标记，目标原文照旧是正文。
             这行不是一等回答的提问，复制/编辑重发/重新生成对它没有意义，动作行由页面按 m.goalCommand 撤掉 -->
        <div v-if="m.role === 'user' && m.goalCommand" class="cmd-line">
          <code class="cmd-token">/goal</code><span class="cmd-name">目标</span>
        </div>
        <!-- 提问随行材料（图片/附件）：与主对话页、分享页共用同一个叶子 -->
        <UserBubble v-if="m.role === 'user'" :images="m.images" :attachments="m.attachments"
                    @paste-view="a => emit('paste-view', a)">
          <template #meta><slot name="user-meta" :m="m" :i="i" /></template>
        </UserBubble>

        <slot name="body-before" :m="m" :i="i" />

        <slot name="body" :m="m" :i="i">
          <!-- 共用兜底正文：一轮的进行中提示只有 TurnBar 那一条（与主对话页同一个组件、同一句
               「正在溯源」），不再另写阶段提示行——跑着的时候它跟在正文末尾（正文未到就只这一行），
               答完回到回答顶上当总览。 -->
          <TurnBar v-if="m.role === 'ai' && !m.loading" :m="m" />
          <!-- 提问走 literal：图片占位与引用角标都不替换——提问里的 [图片N] / [N] 是用户手打的
               字面量，替换掉会改他的原文、还会让自己的附件多渲染一遍 -->
          <AnswerBody v-if="m.content" :content="m.content" :msg-index="i"
                      :images="m.role === 'ai' ? m.images : []"
                      :sources="m.role === 'ai' ? m.sources : []"
                      :literal="m.role !== 'ai'"
                      :runnable="runnable" :session-id="sessionId"
                      :streaming="!!m.loading" />
          <TurnBar v-if="m.role === 'ai' && m.loading" :m="m" />
        </slot>

        <slot name="body-after" :m="m" :i="i" />

        <!-- 降级提示：后端一直在 done 里下发，两个页面都必须把它显出来（本来就不该靠页面自觉） -->
        <DegradationBar v-if="m.role === 'ai'" :items="m.degradations" :warn="m.warnMsg" />

        <slot name="sources" :m="m" :i="i">
          <SourceList v-if="m.role === 'ai' && hasSources(m)" :sources="m.sources"
                      :title="sourceTitle(m)" v-model:open="m.srcOpen" />
        </slot>

        <slot name="sources-after" :m="m" :i="i" />
      </div>

      <AnswerActions v-if="showActions(m)" :content="m.content" :message-id="m.messageId" :fb="m.fb"
                     :loading="!!m.loading" :can-regenerate="canRegenerate(m, i)"
                     @feedback="r => emit('feedback', m, i, r)" @regenerate="emit('regenerate', m, i)">
        <template #prefix><slot name="actions-prefix" :m="m" :i="i" /></template>
        <template #more><slot name="actions-more" :m="m" :i="i" /></template>
        <template #extra><slot name="actions-extra" :m="m" :i="i" /></template>
      </AnswerActions>

      <slot name="actions-after" :m="m" :i="i" />
    </div>
  </div>
</template>

<style scoped>
/* 行与消息块：居中列宽取页面定义的 --chat-col（与输入卡片同源，默认 860）。
   横向留白由页面的滚动容器（.messages / .sc-list）负责，这里只管"能有多宽"。 */
.row { display: flex; flex-wrap: wrap; margin-bottom: 20px; justify-content: center; }
.msg-block {
  position: relative; display: flex; flex-direction: column; min-width: 0;
  max-width: var(--chat-col, 860px); width: 100%;
}
.msg-block.user { align-items: flex-end; }
.msg-block.ai { align-items: flex-start; }

.bubble { width: 100%; line-height: 1.65; }
/* 用户气泡：浅灰底 + 圆角，宽度贴合内容（不是主色蓝） */
.bubble.user {
  background: var(--app-panel-2); border-radius: 12px; padding: 9px 14px;
  width: fit-content; max-width: 100%;
}
/* 气泡里的 markdown 段落不留外边距，否则段落 margin 会把气泡顶得比文字高出一圈 */
.bubble.user :deep(.md > p) { margin: 0; }
/* 命令回声的标记行：一枚命令 token + 它做什么，压在正文上方（token 用等宽，读得出那是敲进去的） */
.cmd-line { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
.cmd-token {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11.5px; line-height: 1;
  padding: 3px 6px; border-radius: 6px; background: var(--app-panel-2); color: var(--app-text2);
}
.cmd-name { font-size: 11.5px; line-height: 1; color: var(--app-text3); }
/* AI 侧无气泡：正文直接排版，不画边框不画底 */
.bubble.ai { background: transparent; padding: 0; }

/* 生成中那一行由 components/TurnBar.vue 自己带样式与动效，这里不再画提示行 */

/* 就地编辑：编辑中的那一行要顶掉原气泡与操作行（只剩页面插进来的编辑卡）。
   操作行（.msg-edit-row）由页面通过 #actions-after 插入，拿不到本组件的 scope 属性，
   所以那一条必须走 :deep() 才命中 */
.msg-block.user.editing > .bubble { display: none; }
.msg-block.user.editing :deep(.msg-edit-row) { display: none; }

@media (max-width: 768px) {
  .row { margin-bottom: 14px; }
  .bubble { max-width: 100%; }
}
</style>
