<template>
  <div class="sc-page" :class="{ embed: isEmbed }">
    <header v-if="!isEmbed" class="sc-head">
      <div class="sc-avatar"><AgentAvatar :agent="info" :size="36" /></div>
      <div class="sc-head-t">
        <div class="sc-name">{{ info.name || '智能体对话' }}</div>
        <div v-if="info.description" class="sc-desc">{{ info.description }}</div>
      </div>
      <a href="/" target="_blank" rel="noopener" class="sc-brand" title="由问渠 WenQu 提供">
        <BrandMark :size="18" />
        <span>问渠 WenQu</span>
      </a>
    </header>

    <div ref="listEl" class="sc-list">
      <!-- 空态：与 ChatPage 的 .welcome 同一套（靠上 + 智能体头像 + 名称 + 描述），
           此前自创了"垂直居中 + 就绪胶囊"那套，与主聊天页观感不一致。 -->
      <div v-if="!messages.length && !loading" class="sc-welcome">
        <AgentAvatar :agent="info" :size="44" class="sc-welcome-mark" />
        <h2>{{ info.name || '智能体对话' }}</h2>
        <p>{{ info.description || '在下方输入你的问题，我会基于所配置的资料与能力回答。' }}</p>
      </div>
      <div v-for="(m, i) in messages" :key="i" class="sc-row" :class="m.role">
        <div class="sc-msg-block" :class="m.role">
          <!-- 用户：浅灰底气泡（与 ChatPage .bubble.user 同款，不是主色蓝） -->
          <div v-if="m.role === 'user'" class="sc-bubble user">{{ m.content }}</div>
          <!-- AI：无气泡，正文直接排（ChatPage .bubble.ai 是 transparent + padding:0） -->
          <div v-else class="sc-bubble ai">
            <!-- 还没出正文时只留一行带呼吸点的提示，不画任何框 -->
            <div v-if="!m.content" class="sc-typing">
              <span class="sc-pulse" /><span>{{ m.stage || '正在思考…' }}</span>
            </div>
            <template v-else>
              <div class="sc-md" v-html="renderMd(m.content, [])"></div>
              <!-- 正文已出、后续又推来阶段提示：作为正文下方的脚注 -->
              <div v-if="m.stage" class="sc-stage"><span class="sc-pulse" />{{ m.stage }}</div>
            </template>
          </div>
        </div>
      </div>
    </div>

    <footer class="sc-input-wrap">
      <div class="sc-input">
        <textarea
          v-model="draft"
          class="sc-textarea"
          :rows="isEmbed ? 1 : 2"
          :disabled="sending"
          placeholder="输入你的问题…"
          enterkeyhint="send"
          @keydown.enter.exact.prevent="send"
        ></textarea>
        <button v-if="!sending" class="sc-send" :disabled="!draft.trim()" aria-label="发送" @click="send">
          <send-outlined />
        </button>
        <button v-else class="sc-send stop" aria-label="停止" @click="stop"><stop-outlined /></button>
      </div>
      <div class="sc-foot">
        <span>内容由 AI 生成，请注意甄别</span>
        <span v-if="!isEmbed" class="sc-foot-hint">Enter 发送 · Shift + Enter 换行</span>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { useRoute } from 'vue-router'
import { SendOutlined, StopOutlined } from '@ant-design/icons-vue'
import { getShareInfo, getShareHistory, sendShareMessage } from '../api'
import { renderMd } from '../utils/markdown'
import AgentAvatar from '../components/AgentAvatar.vue'
import BrandMark from '../components/BrandMark.vue'

const route = useRoute()
const token = computed(() => String(route.params.token || ''))
const isEmbed = computed(() => route.query.embed === '1')

const info = ref({ name: '', description: '', icon: '', isBuiltin: false })
const messages = ref([])
const draft = ref('')
const sending = ref(false)
const listEl = ref(null)
let sessionId = ''
let controller = null

const visitorId = () => {
  try {
    let v = localStorage.getItem('share_visitor_id')
    if (!v) {
      v = (crypto.randomUUID ? crypto.randomUUID() : 'v' + Date.now() + Math.random().toString(36).slice(2)).replace(/-/g, '')
      localStorage.setItem('share_visitor_id', v)
    }
    return v
  } catch (e) { return 'v' + Date.now() }
}

const scrollBottom = () => nextTick(() => {
  if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
})

onMounted(async () => {
  // 访客会话绑定到 token（不同分享互不串会话）
  try { sessionId = localStorage.getItem('share_session_' + token.value) || '' } catch (e) { sessionId = '' }
  try {
    const r = await getShareInfo(token.value)
    if (r && r.success !== false) info.value = r.data || {}
  } catch (e) {
    message.error(e.message || '分享链接无效或已停止访问')
    return
  }
  // 刷新恢复：有会话则拉最近历史（只能取到自己的会话，后端按访客 uid 校验）
  if (sessionId) {
    try {
      const r = await getShareHistory(token.value, sessionId, visitorId())
      if (r && r.success !== false && Array.isArray(r.data) && r.data.length) {
        messages.value = r.data
          .filter(m => m && m.content)
          .map(m => ({ role: m.role === 'user' ? 'user' : 'assistant', content: m.content }))
        scrollBottom()
      }
    } catch (e) { /* 历史失败不阻塞对话 */ }
  }
})

function send () {
  const text = draft.value.trim()
  if (!text || sending.value) return
  draft.value = ''
  messages.value.push({ role: 'user', content: text })
  // 必须用 reactive 包一条消息：直接 push 普通对象再回头改 `ai.stage` 是**绕过代理**的写入，
  // 不触发重渲染（症状：阶段提示永远不出现，页面只显示空气泡 + 停止键）。
  // 此前之所以"看起来正常"，是旁边恰好有个 ref（lastAiStage）在变更、顺带触发了一次重渲染，
  // 把绕过代理写进去的值读了出来——删掉那个 ref 后这条路径就彻底失效了。
  const ai = reactive({ role: 'assistant', content: '', stage: '' })
  messages.value.push(ai)
  sending.value = true
  scrollBottom()
  controller = new AbortController()
  sendShareMessage(token.value, { sessionId, visitorId: visitorId(), message: text }, {
    signal: controller.signal,
    onStage: s => { ai.stage = s; scrollBottom() },
    onToken: t => {
      if (ai.stage) ai.stage = ''   // 正文开始即撤掉阶段提示
      ai.content += t
      scrollBottom()
    },
    onDone: d => {
      sending.value = false
      ai.stage = ''
      // 首轮由服务端建会话：done 带回 sessionId，持久化供刷新恢复
      if (d && d.sessionId) {
        sessionId = d.sessionId
        try { localStorage.setItem('share_session_' + token.value, sessionId) } catch (e) { /* ignore */ }
      }
      scrollBottom()
    },
    onError: msg => {
      sending.value = false
      ai.stage = ''
      if (!ai.content) ai.content = '⚠️ ' + (msg || '回答失败')
      else message.warning(msg || '本轮中断')
      scrollBottom()
    }
  })
}

function stop () {
  if (controller) controller.abort()
  sending.value = false
  // 中断要把这一轮**整条撤掉**，不能只置 sending=false——那样「正在检索资料…」会永久
  // 卡在页面上（后续新消息也压不掉它）。曾改成补一句「已停止生成」占位气泡，
  // 但那是空的回答，留在对话里比不留更碍眼；用户点了停止，要的就是"没这条"。
  const cur = messages.value[messages.value.length - 1]
  if (cur && cur.role === 'assistant' && !cur.content) messages.value.pop()
  scrollBottom()
}
</script>

<style scoped>
/* 整页白底（对齐 ChatPage .chat2 的 background: var(--app-panel)）。
   此前用 --app-bg（灰）导致观感整体偏暗：输入框的悬浮阴影浮在灰底上、
   用户气泡的 --app-panel-2 浅灰几乎与背景同色 —— 这就是"看着不一样"的主因。 */
.sc-page { display: flex; flex-direction: column; height: 100vh; background: var(--app-panel); }

/* ---------- 头部 ---------- */
.sc-head {
  display: flex; gap: 12px; align-items: center; flex: none;
  padding: 12px 20px; background: var(--app-panel);
  border-bottom: 1px solid var(--app-border);
}
.sc-avatar { flex: none; display: flex; }
.sc-head-t { min-width: 0; flex: 1; }
.sc-name {
  font-size: 15px; font-weight: 600; color: var(--app-text); line-height: 1.35;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
/* 描述此前强制单行 nowrap：智能体描述普遍是一句话说明，截断成「问渠内置的知识库…」反而看不出能问什么。
   放宽到两行，超出仍省略。 */
.sc-desc {
  font-size: 12.5px; color: var(--app-text3); margin-top: 2px; line-height: 1.5;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
  overflow: hidden;
}
.sc-brand {
  flex: none; display: inline-flex; align-items: center; gap: 6px;
  font-size: 12px; color: var(--app-text3); text-decoration: none;
  padding: 5px 10px; border-radius: 999px; border: 1px solid var(--app-border);
}
.sc-brand:hover { color: var(--app-accent); border-color: var(--app-accent-border); background: var(--app-accent-weak); }

/* ---------- 消息区：与 ChatPage 同一套设计语言 ----------
   之前分享页自创了另一套（用户蓝底 + AI 白底带边框 + 消息侧挂头像），
   与主聊天页观感割裂。关键三条按 ChatPage 对齐：
   ① 用户气泡是 --app-panel-2 浅灰底，不是主色蓝；
   ② AI 消息**完全无气泡**（transparent + padding:0），正文直接排版；
   ③ 消息块 max-width: min(94%, 860px) 居中，与 .input-box 同宽。 */
.sc-list { flex: 1; min-height: 0; overflow-y: auto; padding: 20px 32px 8px; }

/* 空态 = ChatPage 的 .welcome：靠上（padding 72px 起），不垂直居中 */
.sc-welcome { text-align: center; padding: 72px 20px 40px; }
.sc-welcome-mark { display: block; margin: 0 auto; }
.sc-welcome h2 { margin: 14px 0 6px; font-size: 16px; font-weight: 500; color: var(--app-text); }
.sc-welcome p { color: var(--app-text3); margin: 0; font-size: 13px; line-height: 1.7; }

.sc-row { display: flex; flex-wrap: wrap; margin-bottom: 20px; justify-content: center; }
.sc-msg-block {
  position: relative; display: flex; flex-direction: column; min-width: 0;
  max-width: min(94%, 860px); width: 100%;
}
.sc-msg-block.user { align-items: flex-end; }
.sc-msg-block.ai { align-items: flex-start; }

.sc-bubble { width: 100%; line-height: 1.65; font-size: 14px; color: var(--app-text); word-break: break-word; }
/* 用户气泡：浅灰底 + 圆角，宽度贴合内容（与 ChatPage .bubble.user 逐项一致） */
.sc-bubble.user {
  background: var(--app-panel-2); border-radius: 12px; padding: 9px 14px;
  width: fit-content; max-width: 100%; white-space: pre-wrap;
}
/* AI 侧无气泡：正文直接排版，不画边框不画底 */
.sc-bubble.ai { background: transparent; padding: 0; }

/* 生成中（正文未到）：一行带呼吸点的提示，什么框都不画。
   加 flex:none 是因为它是 .sc-msg-block 的 flex item，默认可被压缩，一压就折成两行。 */
.sc-typing {
  flex: none;
  display: inline-flex; align-items: center; gap: 8px;
  font-size: 13px; color: var(--app-text3); line-height: 1.6;
  padding: 2px 0; white-space: nowrap;
}
/* 正文已出、又推来阶段提示：正文下方的脚注一行 */
.sc-stage {
  display: flex; align-items: center; gap: 7px;
  font-size: 12px; color: var(--app-text3); margin-top: 8px; line-height: 1.6;
}
.sc-pulse {
  width: 6px; height: 6px; border-radius: 50%; background: var(--app-accent); flex: none;
  animation: sc-blink 1.1s ease-in-out infinite;
}
@keyframes sc-blink { 0%, 100% { opacity: .25; } 50% { opacity: 1; } }

/* ---------- 输入区：与 ChatPage .input-box 同款 ---------- */
.sc-input-wrap { flex: none; padding: 10px 20px 12px; background: var(--app-panel); }
.sc-input {
  position: relative; display: flex; gap: 8px; align-items: flex-end;
  max-width: 860px; margin: 0 auto;
  background: var(--app-panel); border: 1px solid var(--app-border);
  border-radius: 16px; padding: 10px 12px 8px;
  box-shadow: 0 1px 2px rgba(16, 24, 40, .04), 0 8px 20px -10px rgba(16, 24, 40, .10);
  transition: border-color .2s, box-shadow .2s;
}
.sc-input:focus-within {
  border-color: var(--app-accent);
  box-shadow: 0 1px 2px rgba(16, 24, 40, .04), 0 10px 26px -10px rgba(46, 107, 230, .30);
}
.sc-textarea {
  flex: 1; resize: none; border: none; background: transparent;
  padding: 6px 4px; font-size: 14px; line-height: 1.6; font-family: inherit;
  color: var(--app-text); outline: none;
}
.sc-send {
  width: 30px; height: 30px; border-radius: 50%; border: none; flex: none;
  background: var(--app-accent); color: #fff; cursor: pointer; font-size: 15px;
  display: inline-flex; align-items: center; justify-content: center; transition: background .2s;
}
.sc-send:hover:not(:disabled) { background: var(--app-accent-hover); }
/* 禁用态：浅蓝底 + 白图标对比度太低，图标几乎看不见。改灰底灰图标（与 ChatPage/H5 同口径） */
.sc-send:disabled { background: var(--app-border); color: var(--app-text3); cursor: not-allowed; }
/* 停止态：弱化危险色（浅红底+危险色内容），与 ChatPage .send-btn.stop 同一视觉语言 */
.sc-send.stop { background: var(--app-danger-weak); color: var(--app-danger-text); border: 1px solid var(--app-danger-border); }
.sc-foot {
  max-width: 860px; margin: 8px auto 0; display: flex; justify-content: space-between;
  gap: 12px; font-size: 11.5px; color: var(--app-text3);
}
.sc-foot-hint { white-space: nowrap; }

/* iframe 嵌入紧凑模式：无头部、小内边距、空态收紧（宿主给的框通常只有 420×640） */
.sc-page.embed .sc-list { padding: 12px; }
.sc-page.embed .sc-welcome { padding: 28px 12px 20px; }
.sc-page.embed .sc-welcome h2 { font-size: 15px; margin-top: 10px; }
.sc-page.embed .sc-input-wrap { padding: 8px 12px 8px; }
.sc-page.embed .sc-foot-hint { display: none; }

/* markdown 基础排版（复用全局 renderMd，气泡内适配） */
.sc-md :deep(p) { margin: 0 0 8px; }
.sc-md :deep(p:last-child) { margin-bottom: 0; }
.sc-md :deep(h1), .sc-md :deep(h2), .sc-md :deep(h3) { font-size: 14.5px; font-weight: 600; margin: 12px 0 6px; }
.sc-md :deep(h1:first-child), .sc-md :deep(h2:first-child), .sc-md :deep(h3:first-child) { margin-top: 0; }
.sc-md :deep(pre) { background: var(--app-code-bg); border-radius: 8px; padding: 10px; overflow-x: auto; font-size: 12.5px; }
.sc-md :deep(code) { background: var(--app-code-inline-bg); border-radius: 3px; padding: 1px 4px; font-size: 12.5px; }
.sc-md :deep(pre code) { background: none; padding: 0; }
.sc-md :deep(table) { border-collapse: collapse; font-size: 13px; }
.sc-md :deep(th), .sc-md :deep(td) { border: 1px solid var(--app-border); padding: 4px 8px; }
.sc-md :deep(ul), .sc-md :deep(ol) { padding-left: 20px; margin: 6px 0; }
.sc-md :deep(a) { color: var(--app-accent); }

/* ==================== 移动端窄屏（h5）====================
   分享链接大量在微信/手机浏览器里打开，这页不走 AppLayout、此前零窄屏适配。
   结构本身是 max-width + flex，天然不破版，这里只补三件事：
   高度交给 --app-vh（键盘）、内边距收窄、触控热区。 */
@media (max-width: 768px) {
  /* 100vh 在 iOS 键盘弹起时不变 → 输入框被盖。用 --app-vh（视觉视口高，已扣键盘）；
     宽屏不写该变量 → 回落 100vh，与改动前一致 */
  .sc-page { height: 100vh; height: var(--app-vh, 100vh); }
  .sc-head { padding: calc(10px + var(--sat, 0px)) 12px 10px; gap: 10px; }
  .sc-desc { -webkit-line-clamp: 1; }
  .sc-brand span { display: none; }
  .sc-brand { padding: 5px 7px; }
  .sc-list { padding: 14px 12px 8px; overscroll-behavior-y: contain; }
  .sc-row { margin-bottom: 14px; }
  .sc-welcome { padding: 40px 8px 24px; }
  /* 消息块在窄屏放开到 100%：min(94%,860px) 的 94% 在 412 屏上只有 387px，
     再叠加气泡自身内边距会显得窄；行长由 .sc-bubble 的可读宽度兜住 */
  .sc-msg-block { max-width: 100%; }
  .sc-bubble.user { padding: 8px 12px; }
  /* 底部输入区：留出 Home Indicator + 键盘高度 */
  .sc-input-wrap { padding: 8px 10px calc(8px + var(--sab, 0px)); }
  .sc-input { gap: 6px; padding: 5px 5px 5px 10px; border-radius: 12px; }
  /* 触控热区：40px 尚可，但输入框内的文字 14px 在 iOS 聚焦会触发自动缩放，必须 16px */
  .sc-textarea { font-size: 16px; padding: 5px 0; }
  .sc-send { width: 44px; height: 44px; }
  .sc-foot { padding: 0 2px; }
  .sc-foot-hint { display: none; }
}
</style>