<template>
  <div class="sc-page" :class="{ embed: isEmbed }">
    <header v-if="!isEmbed" class="sc-head">
      <div class="sc-avatar"><robot-outlined /></div>
      <div class="sc-head-t">
        <div class="sc-name">{{ info.name || '智能体对话' }}</div>
        <div v-if="info.description" class="sc-desc">{{ info.description }}</div>
      </div>
    </header>

    <div ref="listEl" class="sc-list">
      <div v-if="!messages.length && !loading" class="sc-empty">
        <div class="sc-empty-t">{{ info.name || '你好' }}</div>
        <div class="sc-empty-d">有什么可以帮你？直接在下方输入提问。</div>
      </div>
      <div v-for="(m, i) in messages" :key="i" class="sc-row" :class="m.role">
        <div v-if="m.role === 'user'" class="sc-bubble user">{{ m.content }}</div>
        <div v-else class="sc-bubble ai">
          <div class="sc-md" v-html="renderMd(m.content, [])"></div>
          <div v-if="m.stage" class="sc-stage">{{ m.stage }}</div>
        </div>
      </div>
      <div v-if="sending && lastAiStage" class="sc-row ai"><div class="sc-stage live">{{ lastAiStage }}</div></div>
    </div>

    <footer class="sc-input-wrap">
      <div class="sc-input">
        <textarea
          v-model="draft"
          class="sc-textarea"
          :rows="isEmbed ? 1 : 2"
          :disabled="sending"
          placeholder="输入问题，Enter 发送"
          @keydown.enter.exact.prevent="send"
        ></textarea>
        <button v-if="!sending" class="sc-send" :disabled="!draft.trim()" aria-label="发送" @click="send">
          <send-outlined />
        </button>
        <button v-else class="sc-send stop" aria-label="停止" @click="stop"><stop-outlined /></button>
      </div>
      <div class="sc-foot">
        <span>内容由 AI 生成，请注意甄别</span>
        <a v-if="!isEmbed" href="/" target="_blank" rel="noopener" class="sc-brand">问渠 WenQu</a>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { useRoute } from 'vue-router'
import { RobotOutlined, SendOutlined, StopOutlined } from '@ant-design/icons-vue'
import { getShareInfo, getShareHistory, sendShareMessage } from '../api'
import { renderMd } from '../utils/markdown'

const route = useRoute()
const token = computed(() => String(route.params.token || ''))
const isEmbed = computed(() => route.query.embed === '1')

const info = ref({ name: '', description: '' })
const messages = ref([])
const draft = ref('')
const sending = ref(false)
const lastAiStage = ref('')
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
  const ai = { role: 'assistant', content: '', stage: '' }
  messages.value.push(ai)
  sending.value = true
  lastAiStage.value = ''
  scrollBottom()
  controller = new AbortController()
  sendShareMessage(token.value, { sessionId, visitorId: visitorId(), message: text }, {
    signal: controller.signal,
    onStage: s => { ai.stage = s; lastAiStage.value = s; scrollBottom() },
    onToken: t => {
      if (ai.stage) { ai.stage = ''; lastAiStage.value = '' }
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
.sc-head { display: flex; gap: 12px; align-items: center; padding: 16px 20px; background: var(--app-panel); border-bottom: 1px solid var(--app-border); }
.sc-avatar { width: 40px; height: 40px; border-radius: 10px; background: var(--app-accent); color: #fff; display: flex; align-items: center; justify-content: center; font-size: 20px; flex: none; }
.sc-name { font-size: 16px; font-weight: 600; color: var(--app-text); }
.sc-desc { font-size: 12px; color: var(--app-text2); margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 640px; }
.sc-list { flex: 1; overflow-y: auto; padding: 20px; }
.sc-row { display: flex; margin-bottom: 14px; }
.sc-row.user { justify-content: flex-end; }
.sc-bubble { max-width: 78%; padding: 10px 14px; border-radius: 12px; font-size: 14px; line-height: 1.7; }
.sc-bubble.user { background: var(--app-accent); color: #fff; white-space: pre-wrap; word-break: break-word; }
.sc-bubble.ai { background: var(--app-panel); color: var(--app-text); border: 1px solid var(--app-border); }
.sc-stage { font-size: 12px; color: var(--app-text3); margin-top: 6px; }
.sc-stage.live { background: var(--app-panel); border: 1px dashed var(--app-border-strong); border-radius: 8px; padding: 6px 12px; color: var(--app-text2); }
.sc-empty { text-align: center; padding: 60px 20px; color: var(--app-text2); }
.sc-empty-t { font-size: 18px; font-weight: 600; color: var(--app-text); margin-bottom: 6px; }
.sc-empty-d { font-size: 13px; }
.sc-input-wrap { padding: 12px 20px 10px; background: var(--app-panel); border-top: 1px solid var(--app-border); }
.sc-input { display: flex; gap: 8px; align-items: flex-end; max-width: 860px; margin: 0 auto; }
.sc-textarea { flex: 1; resize: none; border: 1px solid var(--app-border-strong); border-radius: 10px; padding: 9px 12px; font-size: 14px; font-family: inherit; outline: none; }
.sc-textarea:focus { border-color: var(--app-accent); }
.sc-send { width: 40px; height: 40px; border-radius: 10px; border: none; background: var(--app-accent); color: #fff; cursor: pointer; font-size: 16px; flex: none; }
.sc-send:disabled { background: #bbd0fb; cursor: not-allowed; }
.sc-send.stop { background: #f53f3f; }
.sc-foot { max-width: 860px; margin: 6px auto 0; display: flex; justify-content: space-between; font-size: 11px; color: #b0b5bf; }
.sc-brand { color: var(--app-text3); text-decoration: none; }
.sc-brand:hover { color: var(--app-accent); }
/* iframe 嵌入紧凑模式：无头部、小内边距 */
.sc-page.embed .sc-list { padding: 12px; }
.sc-page.embed .sc-input-wrap { padding: 8px 12px 8px; }
.sc-page.embed .sc-foot span { display: none; }
.sc-page.embed .sc-foot { justify-content: flex-end; }
/* markdown 基础排版（复用全局 renderMd，气泡内适配） */
.sc-md :deep(p) { margin: 0 0 8px; }
.sc-md :deep(p:last-child) { margin-bottom: 0; }
.sc-md :deep(pre) { background: var(--app-panel-2); border-radius: 8px; padding: 10px; overflow-x: auto; font-size: 12.5px; }
.sc-md :deep(code) { background: var(--app-panel-2); border-radius: 3px; padding: 1px 4px; font-size: 12.5px; }
.sc-md :deep(pre code) { background: none; padding: 0; }
.sc-md :deep(table) { border-collapse: collapse; font-size: 13px; }
.sc-md :deep(th), .sc-md :deep(td) { border: 1px solid var(--app-border); padding: 4px 8px; }
.sc-md :deep(ul), .sc-md :deep(ol) { padding-left: 20px; margin: 6px 0; }
</style>
