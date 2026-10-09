<template>
  <div class="sc-page" :class="{ embed: isEmbed }">
    <header class="sc-head" :class="{ mini: isEmbed }">
      <template v-if="!isEmbed">
        <div class="sc-avatar"><AgentAvatar :agent="info" :size="36" /></div>
        <div class="sc-head-t">
          <div class="sc-name">{{ info.name || '智能体对话' }}</div>
          <div v-if="info.description" class="sc-desc">{{ info.description }}</div>
        </div>
      </template>
      <!-- 会话工具：游客一份链接下可以有多段对话（后端 sessionId 传空即建新会话）。
           embed 紧凑模式没有头部，这里同样渲染，靠 .mini 收成一行小条 -->
      <div class="sc-tools">
        <a-popover v-model:open="histOpen" trigger="click" placement="bottomRight" :width="280">
          <template #content>
            <div class="sc-hist">
              <div v-if="histErr" class="sc-hist-err">
                <span>{{ histErr }}</span>
                <button class="sc-hist-retry" type="button" @click="loadSessions">重试</button>
              </div>
              <div v-else-if="histLoading && !sessions.length" class="sc-hist-empty">加载中…</div>
              <div v-else-if="!sessions.length" class="sc-hist-empty">还没有历史对话</div>
              <button v-for="s in sessions" :key="s.id" type="button"
                      class="sc-hist-item" :class="{ cur: s.id === sessionId }"
                      :disabled="sending" @click="openSession(s.id)">
                <span class="sc-hist-title">{{ s.title || '新对话' }}</span>
                <span class="sc-hist-meta">{{ timeText(s.updateTime) }}<template v-if="s.messageCount"> · {{ s.messageCount }} 条</template></span>
              </button>
              <!-- 公用电脑上的收尾动作：问完把自己的记录抹掉。删的是服务端的数据，文案不许写成"仅清本机缓存"。
                   二次确认就地换掉这一行，不用 a-popconfirm——它会把触发元素包进一层 span，在浮层里铺不满。 -->
              <div v-if="sessions.length && !histErr" class="sc-hist-foot">
                <button v-if="!askClear" class="sc-hist-clear" type="button" :disabled="sending" @click="askClear = true">
                  清除我的对话记录
                </button>
                <template v-else>
                  <span class="sc-hist-ask">清除后这些对话就找不回了</span>
                  <button class="sc-hist-yes" type="button" @click="clearSessions">清除</button>
                  <button class="sc-hist-no" type="button" @click="askClear = false">取消</button>
                </template>
              </div>
            </div>
          </template>
          <button class="sc-tool sc-tool-hist" type="button" title="历史对话" :disabled="sending" @click="loadSessions">
            <history-outlined /><span class="sc-tool-t">历史对话</span>
          </button>
        </a-popover>
        <!-- 当前这段还没说过话就不必再开一段（避免点一下把进行中的对话清空） -->
        <button class="sc-tool sc-tool-new" type="button" title="开始一段新对话"
                :disabled="sending || !messages.length" @click="newSession">
          <plus-outlined /><span class="sc-tool-t">新对话</span>
        </button>
      </div>
      <a v-if="!isEmbed" href="/" target="_blank" rel="noopener" class="sc-brand" title="由问渠 WenQu 提供">
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
            <!-- 还没出正文时只留一行带呼吸点的提示，不画任何框（文案两档，与主聊天页同源） -->
            <div v-if="!m.content" class="sc-typing">
              <span class="sc-pulse" /><span>{{ stageLabel(m.stage) || '正在检索资料…' }}</span>
            </div>
            <template v-else>
              <AnswerBody :content="m.content" :images="m.images" :sources="m.sources"
                          :msg-index="i" :streaming="!!m.loading" />
              <!-- 正文已出、后续又推来阶段提示：作为正文下方的脚注 -->
              <div v-if="m.stage" class="sc-stage"><span class="sc-pulse" />{{ stageLabel(m.stage) }}</div>
              <!-- 引用来源：与主聊天页同一份 sourceName 口径，点开的是同一个来源弹窗 -->
              <div v-if="m.sources && m.sources.length" class="sc-srcs">
                <button class="sc-srcs-head" type="button" @click="m.srcOpen = !m.srcOpen">
                  <span>引用 {{ m.sources.length }} 条来源</span>
                  <caret-right-outlined class="sc-srcs-caret" :class="{ open: m.srcOpen }" />
                </button>
                <div v-if="m.srcOpen" class="sc-srcs-list">
                  <div v-for="s in m.sources" :key="s.ref" class="sc-src" title="点击查看原文" @click="openSource(s)">
                    <span class="sc-src-no">[{{ s.ref }}]</span>
                    <span class="sc-src-name">{{ sourceName(s) }}</span>
                  </div>
                </div>
              </div>
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

    <!-- 正文浮层（图片灯箱 / 引用来源弹窗 / 角标悬浮卡）：与主聊天页共用一份实现。
         不传 sessionId ⇒ 来源弹窗里的代码块不给「在云端沙盒运行」（游客链路本就不暴露沙盒）。 -->
    <AnswerViewerHost />
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { useRoute } from 'vue-router'
import { SendOutlined, StopOutlined, CaretRightOutlined, HistoryOutlined, PlusOutlined } from '@ant-design/icons-vue'
import { getShareInfo, getShareHistory, listShareSessions, clearShareSessions, sendShareMessage } from '../api'
import AgentAvatar from '../components/AgentAvatar.vue'
import BrandMark from '../components/BrandMark.vue'
// 正文与浮层与主聊天页同源：图片、引用角标、来源弹窗、灯箱都走同一套，不再各写一份
import AnswerBody from '../components/AnswerBody.vue'
import AnswerViewerHost from '../components/AnswerViewerHost.vue'
import { openSource } from '../chat/answerViewer'
import { sourceName, stageLabel } from '../chat/projections'

const route = useRoute()
const token = computed(() => String(route.params.token || ''))
const isEmbed = computed(() => route.query.embed === '1')

const info = ref({ name: '', description: '', icon: '', isBuiltin: false })
const messages = ref([])
const draft = ref('')
const sending = ref(false)
// 历史消息加载中（首屏恢复与切换会话共用）：空态必须在它结束后才渲染，否则加载过程看起来像"没聊过"
const loading = ref(false)
const listEl = ref(null)
const sessionId = ref('')
const sessions = ref([])
const histOpen = ref(false)
const histLoading = ref(false)
const histErr = ref('')
const askClear = ref(false)
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

/** 当前这段会话的指针（只存这一个，其余靠后端按访客 uid + 智能体列出）；传空=不落盘并清掉 */
function rememberSession (id) {
  sessionId.value = id || ''
  try {
    if (id) localStorage.setItem('share_session_' + token.value, id)
    else localStorage.removeItem('share_session_' + token.value)
  } catch (e) { /* 隐私模式下 localStorage 不可用，只是刷新后不记忆，不影响对话 */ }
}

/** 历史消息 → 页面消息（首屏恢复与切换会话同一份口径） */
const mapHistory = rows => (Array.isArray(rows) ? rows : [])
  .filter(m => m && m.content)
  .map(m => ({
    role: m.role === 'user' ? 'user' : 'assistant', content: m.content,
    // 图片与引用随消息一起落库（后端 toMessageMap 同源输出），刷新后不必重问一遍才有图
    images: Array.isArray(m.images) ? m.images : [],
    sources: Array.isArray(m.sources) ? m.sources : [],
    srcOpen: true
  }))

/** 会话行的时间标签：今天带时分（一天内多段对话靠时间区分），更早给相对/日期标签 */
function timeText (v) {
  const d = Array.isArray(v) ? new Date(v[0], (v[1] || 1) - 1, v[2] || 1, v[3] || 0, v[4] || 0)
    : (v ? new Date(v) : null)
  if (!d || isNaN(d.getTime())) return ''
  const now = new Date()
  const t0 = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const t = d.getTime()
  if (t >= t0) return '今天 ' + d.toTimeString().slice(0, 5)
  if (t >= t0 - 86400000) return '昨天'
  if (t >= t0 - 6 * 86400000) return '本周'
  return `${d.getMonth() + 1}/${d.getDate()}`
}

/** 拉自己的历史会话列表。失败必须显式报错并可重试——显示成空列表会被读成"之前那段没保存" */
async function loadSessions () {
  if (histLoading.value) return
  histLoading.value = true
  histErr.value = ''
  askClear.value = false
  try {
    const r = await listShareSessions(token.value, visitorId())
    sessions.value = (r.data && r.data.items) || []
  } catch (e) {
    histErr.value = e.message || '历史对话加载失败'
  } finally {
    histLoading.value = false
  }
}

async function openSession (id) {
  if (sending.value || id === sessionId.value) { histOpen.value = false; return }
  histOpen.value = false
  rememberSession(id)
  messages.value = []
  loading.value = true
  try {
    const r = await getShareHistory(token.value, id, visitorId())
    messages.value = mapHistory(r.data)
  } catch (e) {
    message.error(e.message || '这段对话加载失败')
  } finally {
    loading.value = false
    scrollBottom()
  }
}

/** 开一段新对话：只清指针与页面，会话仍由后端在首条消息时创建（不会攒出空会话） */
function newSession () {
  if (sending.value || !messages.value.length) return
  rememberSession('')
  messages.value = []
}

/** 清除自己在该链接下的全部对话（服务端删数据，不是只清本机缓存）；成功后当前线程一并清空 */
async function clearSessions () {
  if (sending.value) return
  try {
    const r = await clearShareSessions(token.value, visitorId())
    const n = (r.data && r.data.deleted) || 0
    sessions.value = []
    askClear.value = false
    rememberSession('')
    messages.value = []
    histOpen.value = false
    message.success(n ? '已清除 ' + n + ' 段对话' : '已清除')
  } catch (e) {
    message.error(e.message || '清除失败，请重试')
  }
}

const scrollBottom = () => nextTick(() => {
  if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
})

onMounted(async () => {
  // 访客会话绑定到 token（不同分享互不串会话）
  try { sessionId.value = localStorage.getItem('share_session_' + token.value) || '' } catch (e) { sessionId.value = '' }
  try {
    const r = await getShareInfo(token.value)
    if (r && r.success !== false) info.value = r.data || {}
  } catch (e) {
    message.error(e.message || '分享链接无效或已停止访问')
    return
  }
  // 刷新恢复：有会话则拉最近历史（只能取到自己的会话，后端按访客 uid 校验）
  if (sessionId.value) {
    loading.value = true
    try {
      const r = await getShareHistory(token.value, sessionId.value, visitorId())
      messages.value = mapHistory(r.data)
      // 取不到消息说明这个指针不属于当前访客（换浏览器/访客标识被清）——留着它每次提问都会被
      // 后端按归属判 403，且此时页面是空态、没有入口可清，必须就地放弃、当新对话开。
      if (!messages.value.length) rememberSession('')
      scrollBottom()
    } catch (e) { /* 历史失败不阻塞对话 */ } finally { loading.value = false }
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
  const ai = reactive({ role: 'assistant', content: '', stage: '', images: [], sources: [], srcOpen: true, loading: true })
  messages.value.push(ai)
  sending.value = true
  scrollBottom()
  controller = new AbortController()
  sendShareMessage(token.value, { sessionId: sessionId.value, visitorId: visitorId(), message: text }, {
    signal: controller.signal,
    onStage: s => { ai.stage = s; scrollBottom() },
    onToken: t => {
      if (ai.stage) ai.stage = ''   // 正文开始即撤掉阶段提示
      ai.content += t
      scrollBottom()
    },
    // 图片按到达顺序整组下发（[图片N] 的 N 就是这个数组的下标），与主聊天页同口径
    onImage: payload => {
      try {
        const parsed = JSON.parse(payload)
        ai.images = Array.isArray(parsed) ? parsed : []
      } catch (e) { ai.images = [] }
    },
    onDone: d => {
      sending.value = false
      ai.stage = ''
      ai.loading = false
      // done.content 是本轮落库版汇总：sources（角标点开的就是它）、finalImages（正文图）、
      // finalContent（引用自检/清理后改写过正文时，以它为准，否则 [图片N] 与 [N] 的下标会错位）
      try {
        const p = JSON.parse(d.content || '{}')
        if (Array.isArray(p.sources)) ai.sources = p.sources
        if (Array.isArray(p.finalImages)) ai.images = p.finalImages
        if (typeof p.finalContent === 'string' && p.finalContent !== '') ai.content = p.finalContent
      } catch (e) { /* 汇总解析失败：正文已在流式里累积完，不影响可读 */ }
      // 首轮由服务端建会话：done 带回 sessionId，持久化供刷新恢复
      if (d && d.sessionId) rememberSession(d.sessionId)
      scrollBottom()
    },
    onError: msg => {
      sending.value = false
      ai.stage = ''
      ai.loading = false
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
  // 已出正文的那条要撤掉 loading：否则这一轮永远停在流式态，正文里的图表不会被补画
  else if (cur && cur.role === 'assistant') cur.loading = false
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

/* ---------- 会话工具（历史对话 / 新对话）---------- */
.sc-tools { flex: none; display: flex; align-items: center; gap: 6px; }
.sc-tool {
  display: inline-flex; align-items: center; gap: 5px; flex: none;
  padding: 5px 10px; border-radius: 999px; border: 1px solid var(--app-border);
  background: transparent; color: var(--app-text2); cursor: pointer;
  font-size: 12.5px; font-family: inherit; line-height: 1.4;
  transition: color .2s, border-color .2s, background .2s;
}
.sc-tool:hover:not(:disabled) { color: var(--app-accent); border-color: var(--app-accent-border); background: var(--app-accent-weak); }
.sc-tool:disabled { color: var(--app-text3); cursor: not-allowed; opacity: .55; }
/* embed 紧凑模式：头部只剩这一行工具，右对齐收成小条 */
.sc-head.mini { justify-content: flex-end; padding: 6px 10px; }

/* 历史对话面板：节点由本页渲染（teleport 后 scoped 属性仍在），只出自己的列表样式 */
.sc-hist { max-height: 320px; overflow-y: auto; }
.sc-hist-item {
  display: flex; flex-direction: column; gap: 2px; width: 100%; text-align: left;
  padding: 8px 10px; border: none; border-radius: 8px; background: transparent;
  cursor: pointer; font-family: inherit; color: var(--app-text);
}
.sc-hist-item:hover:not(:disabled) { background: var(--app-panel-2); }
.sc-hist-item.cur { background: var(--app-accent-weak); }
.sc-hist-item:disabled { cursor: not-allowed; opacity: .6; }
.sc-hist-title {
  font-size: 13px; line-height: 1.45; min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.sc-hist-meta { font-size: 11.5px; color: var(--app-text3); }
.sc-hist-empty { padding: 12px 10px; font-size: 12.5px; color: var(--app-text3); text-align: center; }
.sc-hist-err {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  padding: 8px 6px; font-size: 12.5px; color: var(--app-danger-text);
}
.sc-hist-retry {
  flex: none; padding: 3px 10px; border-radius: 999px; border: 1px solid var(--app-danger-border);
  background: var(--app-danger-weak); color: var(--app-danger-text); cursor: pointer;
  font-size: 12px; font-family: inherit;
}
/* 清除入口：列表底部一条分隔 + 危险色整行按钮；点下去就地换成"问句 + 清除/取消" */
.sc-hist-foot {
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
  margin-top: 6px; padding-top: 8px; border-top: 1px solid var(--app-border);
}
.sc-hist-clear {
  flex: 1; min-width: 0; padding: 6px 8px; border: none; border-radius: 8px;
  background: transparent; color: var(--app-danger-text); cursor: pointer;
  font-size: 12.5px; font-family: inherit; text-align: center;
}
.sc-hist-clear:hover:not(:disabled) { background: var(--app-danger-weak); }
.sc-hist-clear:disabled { opacity: .55; cursor: not-allowed; }
.sc-hist-ask { flex: 1; min-width: 0; font-size: 12px; color: var(--app-text2); }
.sc-hist-yes, .sc-hist-no {
  flex: none; padding: 4px 10px; border-radius: 999px;
  font-size: 12px; font-family: inherit; cursor: pointer;
}
.sc-hist-yes { border: 1px solid var(--app-danger-border); background: var(--app-danger-weak); color: var(--app-danger-text); }
.sc-hist-no { border: 1px solid var(--app-border); background: transparent; color: var(--app-text2); }

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

/* 正文排版与引用角标/图片样式全在 src/md.css（.md 全局规则，与主聊天页同源），此处不再各写一份。
   这里只补本页自有的「引用来源」折叠条。 */
.sc-srcs { margin-top: 10px; border-top: 1px solid var(--app-border); padding-top: 6px; }
.sc-srcs-head {
  display: flex; align-items: center; gap: 6px; width: 100%;
  padding: 2px 0; border: none; background: transparent; cursor: pointer;
  font-size: 12.5px; color: var(--app-text2); font-family: inherit;
}
.sc-srcs-head:hover { color: var(--app-accent); }
.sc-srcs-caret { font-size: 10px; transition: transform .18s; }
.sc-srcs-caret.open { transform: rotate(90deg); }
.sc-srcs-list { margin-top: 4px; display: flex; flex-direction: column; gap: 2px; }
.sc-src {
  display: flex; align-items: baseline; gap: 6px; padding: 4px 6px; border-radius: 6px;
  font-size: 12.5px; color: var(--app-text2); cursor: pointer; min-width: 0;
}
.sc-src:hover { background: var(--app-panel-2); color: var(--app-accent); }
.sc-src-no { flex: none; color: var(--app-text3); font-size: 11px; }
.sc-src-name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

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
  /* 会话工具收成图标（窄屏头部要给名称留位），热区补到 34px */
  .sc-tool-t { display: none; }
  .sc-tool { padding: 7px 9px; font-size: 14px; }
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