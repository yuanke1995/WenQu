<template>
  <!-- 一次咨询在聊天区的那份「子对话」：我们问它什么、它干了什么、答了什么、据哪些资料。
       刻意不带自己的折叠头——外层工具卡已经写了「委派助手执行 · 谁 · 多久」，再叠一个标题就是两句同样的话。
       两种形态同一个组件：subChat 到位后是终态卡（问/干/答/据/交付）；只有 _steps 时是执行中的
       直播卡（时间线逐步追加），跑完 subChat 接管，刷新后也不丢——两份时间线同源（转发帧/落库帧）。 -->
  <div class="sa-chat" :class="{ failed: v.failed }">
    <div v-if="v.question" class="sac-line">
      <div class="sac-label">问它</div>
      <div class="sac-q">{{ v.question }}</div>
    </div>

    <!-- 执行过程（干活视角）：直播时逐步追加，终态后取落库那份；digest 支没有工具步，这段自然不渲染 -->
    <div v-if="stepsView.length" class="sac-line">
      <div class="sac-label">
        {{ live ? '执行中' : '执行过程' }}
        <span class="sac-meta">{{ stepsView.length }} 步</span>
      </div>
      <div class="sac-steps">
        <div v-for="(s, i) in stepsView" :key="i"
             class="sac-step" :class="{ err: s.st === 'error', denied: s.denied }">
          <span class="sac-step-dot">{{ s.st === 'error' ? '✕' : '✓' }}</span>
          <span class="sac-step-name" :title="s.name">{{ s.label || s.name }}</span>
          <span v-if="s.denied" class="sac-step-tag">被挡下</span>
          <span v-if="s.ms" class="sac-step-ms">{{ stepMs(s.ms) }}</span>
        </div>
      </div>
    </div>

    <div v-if="!live" class="sac-line">
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
    <!-- 产物：新数据带 url 可点预览/下载（与主对话产物同一入口）；旧消息只有文件名，如实只展示 -->
    <div v-if="v.artifacts.length" class="sac-line">
      <div class="sac-label">它交付的产物</div>
      <div class="sac-arts">
        <div v-for="(a, i) in v.artifacts" :key="i" class="sac-art-item">
          <a v-if="a.url" class="sac-art-link" :href="resolveImg(a.url)" target="_blank" rel="noopener">{{ a.name }}</a>
          <span v-else class="sac-art-plain">{{ a.name }}</span>
          <span v-if="a.size" class="sac-art-size">{{ fmtSize(a.size) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
// 数据来自后端随工具卡落库的 subChat（见 SubAgentConsultService.render）：实时、刷新、换设备同构。
// 老消息没这份 ⇒ 父级按 consultChatView 返回 null 直接不渲染本组件，退回普通工具卡，不空块不报错。
// 直播期（subChat 未到）父级按 _steps 有值也挂本组件：执行中形态只渲染时间线，问/答/资料段空着不占位。
import { computed, ref } from 'vue'
import AgentAvatar from './AgentAvatar.vue'
import AnswerBody from './AnswerBody.vue'
import { consultChatView } from '../chat/projections'
import { resolveImg } from '../utils/markdown'

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

/** 直播形态：终态 subChat 还没来，卡上只有引擎侧逐步累积的 _steps */
const live = computed(() => !props.t?.subChat)
const v = computed(() => consultChatView(props.t) || {
  question: '', name: '', answer: '', steps: 0, stepList: [], dur: '', sources: [], artifacts: [],
  failed: false, error: ''
})
/** 终态优先落库那份（steps 字段）；直播退回 _steps——两者同源同形，切换无跳变 */
const stepsView = computed(() => v.value.stepList.length
  ? v.value.stepList
  : (Array.isArray(props.t._steps) ? props.t._steps : []))
// 长回答不把它撑成一堵墙：超过这个字数先夹高，想看全文点一下展开
const CLIP_CHARS = 600
const full = ref(false)
const clip = computed(() => v.value.answer.length > CLIP_CHARS)
const refLine = computed(() => v.value.sources
  .map(s => `[${s.ref}]《${s.title || '资料'}》`).join('、'))
const stepMs = ms => ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`
const fmtSize = n => n >= 1048576 ? `${(n / 1048576).toFixed(1)}MB`
  : n >= 1024 ? `${Math.round(n / 1024)}KB` : `${n}B`
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
/* 执行过程时间线：轻行距逐行排，超长（>6 行）内部滚动，不把卡撑成一面墙 */
.sac-steps {
  max-height: 168px; overflow-y: auto; font-size: 12px; line-height: 1.6;
  padding: 4px 9px; background: var(--app-panel-2); border-radius: 6px;
}
.sac-step { display: flex; align-items: center; gap: 6px; padding: 1px 0; color: var(--app-text2); }
.sac-step-dot { flex: none; font-size: 10px; color: var(--app-success, #52c41a); }
.sac-step.err .sac-step-dot { color: var(--app-danger); }
.sac-step-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sac-step.err .sac-step-name { color: var(--app-danger); }
.sac-step-tag {
  flex: none; font-size: 10px; padding: 0 4px; border-radius: 3px;
  color: var(--app-text3); border: 1px solid var(--app-border);
}
.sac-step-ms { flex: none; font-size: 11px; color: var(--app-text3); }
.sac-arts { display: flex; flex-direction: column; gap: 2px; }
.sac-art-item { display: flex; align-items: baseline; gap: 6px; font-size: 12.5px; }
.sac-art-link { color: var(--app-accent); word-break: break-all; }
.sac-art-plain { color: var(--app-text2); word-break: break-all; }
.sac-art-size { flex: none; font-size: 11px; color: var(--app-text3); }
/* 夹高只夹这一份正文：资料行与提问本来就短，不需要 */
.sac-md.clipped { max-height: 260px; overflow: hidden; mask-image: linear-gradient(180deg, #000 72%, transparent); }
.sac-more {
  margin-top: 2px; padding: 2px 0; background: none; border: 0; cursor: pointer;
  font-size: 11.5px; color: var(--app-accent);
}
.sac-refs { font-size: 12.5px; line-height: 1.8; word-break: break-word; }
</style>
