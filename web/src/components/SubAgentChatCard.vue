<template>
  <!-- 一次咨询在聊天区的那份「子对话」：我们问它什么、它答了什么、它据哪些资料。
       刻意不带自己的折叠头——外层工具卡已经写了「问另一位助手 · 谁 · 多久」，再叠一个标题就是两句同样的话。 -->
  <div class="sa-chat" :class="{ failed: v.failed }">
    <div v-if="v.question" class="sac-line">
      <div class="sac-label">问它</div>
      <div class="sac-q">{{ v.question }}</div>
    </div>

    <div class="sac-line">
      <div class="sac-label">
        <AgentAvatar :agent="agent" :size="14" />
        <span class="sac-who">{{ v.name || '另一位助手' }}</span>
        <span v-if="v.steps" class="sac-meta">跑了 {{ v.steps }} 步工具</span>
        <span v-if="v.dur" class="sac-meta">{{ v.dur }}</span>
      </div>
      <!-- 没跑成的那一次同样占一行：这一位从聊天区悄悄消失，用户会以为没问过它 -->
      <div v-if="v.failed" class="sac-err">
        {{ v.timeout ? '这一支跑到超时被叫停' : '这一支没跑成' }}<template v-if="v.error">：{{ v.error }}</template>
      </div>
      <template v-else>
        <AnswerBody class="sac-md" :class="{ clipped: clip && !full }" :content="v.answer"
                    :sources="sources" :viewer="viewer" @citation="e => $emit('citation', e)"
                    @preview="e => $emit('preview', e)" />
        <button v-if="clip" class="sac-more" type="button" @click="full = !full">
          {{ full ? '收起它的回答' : '展开它的完整回答' }}
        </button>
      </template>
    </div>

    <!-- 资料用**父轮**编号（后端登记时已换成这一套号），所以点角标开的就是右侧那份来源详情 -->
    <div v-if="v.sources.length" class="sac-line">
      <div class="sac-label">它据的资料</div>
      <AnswerBody class="sac-refs" :content="refLine" :sources="sources" :viewer="viewer"
                  @citation="e => $emit('citation', e)" @preview="e => $emit('preview', e)" />
    </div>
    <div v-if="v.artifacts.length" class="sac-line">
      <div class="sac-label">它交付的产物</div>
      <div class="sac-art">{{ v.artifacts.join('、') }}</div>
    </div>
  </div>
</template>

<script setup>
// 数据来自后端随工具卡落库的 subChat（见 SubAgentConsultService.render）：实时、刷新、换设备同构。
// 老消息没这份 ⇒ 父级按 consultChatView 返回 null 直接不渲染本组件，退回普通工具卡，不空块不报错。
import { computed, ref } from 'vue'
import AgentAvatar from './AgentAvatar.vue'
import AnswerBody from './AnswerBody.vue'
import { consultChatView } from '../chat/projections'

const props = defineProps({
  /** 工具卡记录（派遣助手那一条：agent_spawn，或历史消息里的 consultAgent） */
  t: { type: Object, required: true },
  /** 被咨询那位的头像素材（拿不到就默认机器人：移动壳没有助手清单） */
  agent: { type: Object, default: null },
  /** 本轮引用表：子对话里的角标与主管线共用这一份编号 */
  sources: { type: Array, default: () => [] },
  /** true=点角标走共用浮层；false=外发给页面（移动壳用底部抽屉） */
  viewer: { type: Boolean, default: true }
})
defineEmits(['citation', 'preview'])

const v = computed(() => consultChatView(props.t) || {
  question: '', name: '', answer: '', steps: 0, dur: '', sources: [], artifacts: [], failed: false
})
// 长回答不把它撑成一堵墙：超过这个字数先夹高，想看全文点一下展开
const CLIP_CHARS = 600
const full = ref(false)
const clip = computed(() => v.value.answer.length > CLIP_CHARS)
const refLine = computed(() => v.value.sources
  .map(s => `[${s.ref}]《${s.title || '资料'}》`).join('、'))
</script>

<style scoped>
/* 不设 max-width：气泡内卡片跟随消息列宽（与气泡内其它卡片同一条口径），
   限宽会让长回答在宽屏上挤成一窄条 */
/* 内边距随所在壳走：PC 工具卡头是 4px 9px、移动端是 10px 12px，由宿主用 :deep 覆盖这个变量，
   否则卡里的正文会与卡片头左右错位（与 AskRecordCard 同一手法） */
.sa-chat { padding: var(--sa-chat-pad, 0 9px 6px); }
.sac-line { margin: 6px 0; }
.sac-label {
  display: flex; align-items: center; gap: 5px; margin-bottom: 3px;
  font-size: 11px; color: var(--app-text3);
}
.sac-who { color: var(--app-text2); font-weight: 500; }
.sac-meta { color: var(--app-text3); }
.sac-meta::before { content: '·'; margin-right: 5px; }
.sac-q {
  font-size: 12.5px; line-height: 1.7; color: var(--app-text2);
  padding: 6px 9px; background: var(--app-panel-2); border-left: 2px solid var(--app-border);
  border-radius: 0 6px 6px 0; white-space: pre-wrap; word-break: break-word;
}
.sac-err { font-size: 12.5px; line-height: 1.7; color: var(--app-danger); word-break: break-word; }
.sac-art { font-size: 12.5px; line-height: 1.7; color: var(--app-text2); word-break: break-word; }
/* 夹高只夹这一份正文：资料行与提问本来就短，不需要 */
.sac-md.clipped { max-height: 260px; overflow: hidden; mask-image: linear-gradient(180deg, #000 72%, transparent); }
.sac-more {
  margin-top: 2px; padding: 2px 0; background: none; border: 0; cursor: pointer;
  font-size: 11.5px; color: var(--app-accent);
}
.sac-refs { font-size: 12.5px; line-height: 1.8; word-break: break-word; }
</style>
