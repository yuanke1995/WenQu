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

    <div ref="listEl" class="sc-list" :class="{ hero: !messages.length && !loading }">
      <!-- 空态：整块垂直居中（此前顶在上方留大片空白，页面显得空且廉价） -->
      <div v-if="!messages.length && !loading" class="sc-hero">
        <div class="sc-hero-mark"><AgentAvatar :agent="info" :size="56" /></div>
        <h1 class="sc-hero-t">{{ info.name || '智能体对话' }}</h1>
        <p class="sc-hero-d">{{ info.description || '在下方输入你的问题，我会基于所配置的资料与能力回答。' }}</p>
        <div class="sc-hero-tip">
          <span class="sc-hero-dot" />
          {{ info.name ? `正在与「${info.name}」对话` : '智能体已就绪' }}
        </div>
      </div>
      <div v-else class="sc-thread">
        <div v-for="(m, i) in messages" :key="i" class="sc-row" :class="m.role">
          <div v-if="m.role === 'user'" class="sc-bubble user">{{ m.content }}</div>
          <div v-else class="sc-bubble-wrap ai">
            <div class="sc-ai-mark"><AgentAvatar :agent="info" :size="26" /></div>
            <div class="sc-bubble ai">
              <div class="sc-md" v-html="renderMd(m.content, [])"></div>
              <!-- 阶段提示只在这里出现一次。此前下面还挂了一条 sending && lastAiStage 的
                   「正在检索资料…」独立气泡，与本处 m.stage 指向同一份状态 ⇒ 同一句话渲染两遍 -->
              <div v-if="m.stage" class="sc-stage" :class="{ live: sending }">
                <span v-if="sending" class="sc-pulse" />{{ m.stage }}
              </div>
            </div>
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
}
</script>

<style scoped>
.sc-page { display: flex; flex-direction: column; height: 100vh; background: var(--app-bg); }

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

/* ---------- 消息区 ---------- */
.sc-list { flex: 1; min-height: 0; overflow-y: auto; padding: 24px 20px; }
/* 对话线程限宽居中：此前气泡直接铺满视口，宽屏下行长失控、左右两侧空白比例失衡 */
.sc-thread { max-width: 820px; margin: 0 auto; }
/* 空态：垂直居中，不再顶在上方。
   width:100% 是必需的——.sc-list.hero 是 flex 容器，居中子项默认按内容宽度收缩，
   桌面 1280 下空态块会缩成 210px 的一小条。 */
.sc-list.hero { display: flex; align-items: center; justify-content: center; }
.sc-hero { width: 100%; max-width: 560px; text-align: center; padding: 0 20px; }
.sc-hero-mark { display: flex; justify-content: center; margin-bottom: 16px; }
.sc-hero-t { margin: 0 0 8px; font-size: 20px; font-weight: 600; color: var(--app-text); line-height: 1.4; }
.sc-hero-d { margin: 0; font-size: 13.5px; line-height: 1.7; color: var(--app-text2); }
.sc-hero-tip {
  display: inline-flex; align-items: center; gap: 6px; margin-top: 18px;
  font-size: 12px; color: var(--app-text3);
  background: var(--app-panel); border: 1px solid var(--app-border);
  border-radius: 999px; padding: 5px 12px;
}
.sc-hero-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--app-ok); flex: none; }

.sc-row { display: flex; margin-bottom: 18px; }
.sc-row.user { justify-content: flex-end; }
.sc-bubble-wrap { display: flex; gap: 10px; align-items: flex-start; min-width: 0; max-width: 100%; }
.sc-ai-mark { flex: none; margin-top: 2px; }
.sc-bubble { max-width: 78%; padding: 10px 14px; border-radius: 12px; font-size: 14px; line-height: 1.75; word-break: break-word; }
/* 用户气泡：右对齐 + 主色实底；AI 气泡：白底卡片 + 细边（此前 AI 侧无气泡感，与用户气泡同为圆角块、难分主次） */
.sc-bubble.user { background: var(--app-accent); color: #fff; white-space: pre-wrap; box-shadow: var(--app-shadow-sm); }
.sc-bubble.ai {
  background: var(--app-panel); color: var(--app-text);
  border: 1px solid var(--app-border); box-shadow: var(--app-shadow-sm);
  border-top-left-radius: 4px; min-width: 0;
}
.sc-stage { font-size: 12px; color: var(--app-text3); margin-top: 6px; }
/* 进行中：气泡内的虚线小条 + 呼吸点（此前这条提示是一条独立气泡，与气泡内的 m.stage 重复渲染） */
.sc-stage.live {
  display: inline-flex; align-items: center; gap: 7px;
  background: var(--app-panel-2); border: 1px dashed var(--app-border-strong);
  border-radius: 8px; padding: 4px 10px; color: var(--app-text2);
}
.sc-pulse {
  width: 6px; height: 6px; border-radius: 50%; background: var(--app-accent); flex: none;
  animation: sc-blink 1.1s ease-in-out infinite;
}
@keyframes sc-blink { 0%, 100% { opacity: .25; } 50% { opacity: 1; } }

/* ---------- 输入区 ---------- */
.sc-input-wrap {
  flex: none; padding: 14px 20px 12px;
  background: var(--app-panel); border-top: 1px solid var(--app-border);
}
.sc-input {
  display: flex; gap: 10px; align-items: flex-end;
  max-width: 820px; margin: 0 auto;
  background: var(--app-bg); border: 1px solid var(--app-border-strong);
  border-radius: 14px; padding: 6px 6px 6px 12px;
  transition: border-color .15s, box-shadow .15s;
}
.sc-input:focus-within { border-color: var(--app-accent); box-shadow: 0 0 0 3px var(--app-accent-weak); }
.sc-textarea {
  flex: 1; resize: none; border: none; background: transparent;
  padding: 6px 0; font-size: 14px; line-height: 1.6; font-family: inherit;
  color: var(--app-text); outline: none;
}
.sc-send {
  width: 36px; height: 36px; border-radius: 10px; border: none; flex: none;
  background: var(--app-accent); color: #fff; cursor: pointer; font-size: 15px;
  display: inline-flex; align-items: center; justify-content: center;
  transition: background .15s;
}
.sc-send:hover { background: var(--app-accent-hover); }
.sc-send:disabled { background: var(--app-accent-disabled); cursor: not-allowed; }
.sc-send.stop { background: #f53f3f; }
.sc-foot {
  max-width: 820px; margin: 8px auto 0; display: flex; justify-content: space-between;
  gap: 12px; font-size: 11.5px; color: var(--app-text3);
}
.sc-foot-hint { white-space: nowrap; }

/* iframe 嵌入紧凑模式：无头部、小内边距、空态收紧（宿主给的框通常只有 420×640） */
.sc-page.embed .sc-list { padding: 12px; }
.sc-page.embed .sc-thread { max-width: 100%; }
.sc-page.embed .sc-list.hero { padding: 16px 12px; }
.sc-page.embed .sc-hero-mark { margin-bottom: 10px; }
.sc-page.embed .sc-hero-t { font-size: 16px; }
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
  .sc-hero { padding: 0 8px; }
  .sc-hero-t { font-size: 18px; }
  /* 气泡 78% 在窄屏上偏窄，行长变短反而更易读，但留 88% 更省纵向空间 */
  .sc-bubble { max-width: 86%; padding: 9px 12px; font-size: 14px; }
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