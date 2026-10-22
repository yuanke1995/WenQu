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
        <!-- 描述为空时按能力给一句能照着做的引导：发布者没写描述是常态，
             「在下方输入你的问题」在能发图的链接上等于把最重要的用法藏了起来 -->
        <p>{{ info.description || welcomeHint }}</p>
      </div>
      <!-- 消息列表与主对话页共用 chat/ChatThread.vue：行/块/气泡布局、四个叶子的次第
           （正文 → 降级 → 引用来源 → 动作行）都由它定。分享页没有登录态那些附加物，
           所以一个插槽都不填——#body 用兜底（等待态 → 正文 → 阶段脚注），
           #sources 用兜底（不带检索词与片段），只有"重新生成只给最后一轮"这一条要传进去。 -->
      <ChatThread :messages="messages" :can-regenerate="(m, i) => i === messages.length - 1"
                  @feedback="(m, i, r) => fb(m, r)" @regenerate="(m, i) => regenerate(i)" />
    </div>

    <!-- 输入区与主对话页同一个组件（chat/ChatInput.vue）：卡片、正文与附件/图片 chip 同流、
         工具条、拖拽落点、发送/停止键都是一份实现，所以这页不再自己拼一套。
         分享页没有 + 面板/@ 引用/技能/计划模式，那些插槽一个都不填——「只填自己有的」。
         页脚放进 #below：它得跟卡片同一个内边距列才居中对齐，包在外面就对不上了。 -->
    <ChatInput
      ref="inputRef" v-model="draft" :resolve="chipOf" :placeholder="placeholder"
      :sending="sending" :can-send="canSend" :can-attach="canAttach" :attach-title="attachTitle"
      :accept="acceptAttr"
      @attach="openPicker" @send="send" @stop="stop" @files="onDropFiles"
      @paste="onComposerPaste" @keydown="onInputKeydown" @update:keys="onKeys"
      @chip-click="onChipClick" @ready="syncChips">
      <template #below>
        <div class="sc-foot">
          <span>内容由 AI 生成，请注意甄别</span>
          <span v-if="!isEmbed" class="sc-foot-hint">Enter 发送 · Shift + Enter 换行</span>
        </div>
      </template>
    </ChatInput>

    <!-- 正文浮层（图片灯箱 / 引用来源弹窗 / 角标悬浮卡）：与主聊天页共用一份实现。
         不传 sessionId ⇒ 来源弹窗里的代码块不给「在云端沙盒运行」（游客链路本就不暴露沙盒）。 -->
    <AnswerViewerHost />
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { useRoute } from 'vue-router'
// 只留本页自己画的两个图标：发送/停止、引用箭头、复制/评价/重生成、降级感叹号、附件图标
// 都已随对应叶子进各自组件（这里留着就成了「看着像在用」的死导入）
import { HistoryOutlined, PlusOutlined } from '@ant-design/icons-vue'
import { getShareInfo, getShareHistory, listShareSessions, clearShareSessions, sendShareMessage, getShareRunStatus,
         resumeShareTurn, stopShareTurn, uploadShareAttachment, submitShareFeedback } from '../api'
import AgentAvatar from '../components/AgentAvatar.vue'
import BrandMark from '../components/BrandMark.vue'
// 正文与浮层与主聊天页同源：图片、引用角标、来源弹窗、灯箱都走同一套，不再各写一份
import AnswerViewerHost from '../components/AnswerViewerHost.vue'
// 输入区与主对话页共用一份壳（卡片/正文与 chip 同流/工具条/拖拽落点/发送停止键）
import ChatInput from '../chat/ChatInput.vue'
// 消息列表外壳（与主对话页同一份）：行/块/气泡布局 + 提问随行材料 + 降级条 + 引用来源 + 动作行
import ChatThread from '../chat/ChatThread.vue'
import { openImages } from '../chat/answerViewer'
import { fmtSize, resumedStartAt } from '../chat/projections'
// 取材判定层与主聊天页同源（白名单/限额/被拒文案只有一份，改一处两页同时生效）
import { MAX_IMAGES, MAX_FILES, MAX_FILE_MB, SUPPORTED_EXTS, REJECT, classifyIntake, rejectText,
         compressImage, pasteFilesOf, isLongPaste, pasteNameOf, isImageFile } from '../chat/intake'
import { resolveImg } from '../utils/markdown'

const route = useRoute()
const token = computed(() => String(route.params.token || ''))
const isEmbed = computed(() => route.query.embed === '1')

const info = ref({ name: '', description: '', icon: '', isBuiltin: false })
/**
 * 游客能力位与限额（来自 /share/{token}/info）。
 * 默认全关：拿不到 info 时页面也不该先给一个"能发图"的假入口——能力位是发布者授的权，
 * 猜错的方向只能是更保守的那一侧。
 */
const cap = ref({ guestImages: false, guestFiles: false, maxImages: MAX_IMAGES, maxImageMb: 10,
                  maxAttachments: MAX_FILES, maxAttachmentMb: MAX_FILE_MB, maxQuestionChars: 8000 })
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

/** 输入区组件的句柄：开系统选择器、以及 Composer 的 sync/repaint 都走它 */
const inputRef = ref(null)
/** 待发送的图片与附件。它们是**数据**，编辑区里的 chip 是**呈现**——
 *  两边靠 syncChips（数组→DOM）与 onKeys（DOM→数组剪枝）对账，与主对话页同一套契约 */
const pendingImages = ref([])
const pendingFiles = ref([])
/** 待发送 chip 的编号源：fileId 要等上传响应回来才有，之前总得有个稳定身份，
 *  否则"这颗 chip 对应数组里哪一条"只能靠文件名猜，重名就串号 */
let chipSeq = 0
/** 已挂进编辑区的 chip key（按 DOM 顺序）。Composer 只在「父级改了值」与「我们打了字」之间做区分，
 *  删除动作要靠它反推是哪一条被删了 */
const inlineKeys = ref([])

const canAttach = computed(() => cap.value.guestImages || cap.value.guestFiles)
/** 前端预检取「前端默认」与「服务端下发」的较严一档：服务端才是权威，前端不能比它宽松
 *  （否则用户白传一趟才被拒），也不该比它更严（否则功能看起来莫名少了一截） */
const limits = computed(() => ({
  maxImages: Math.max(1, Math.min(MAX_IMAGES, cap.value.maxImages || MAX_IMAGES)),
  maxFiles: Math.max(1, Math.min(MAX_FILES, cap.value.maxAttachments || MAX_FILES)),
  maxFileMb: Math.max(1, Math.min(MAX_FILE_MB, cap.value.maxAttachmentMb || MAX_FILE_MB))
}))
/** 选择器的接收类型：只收能力位允许的那几类，让系统选择器自己就灰掉不支持的格式 */
const acceptAttr = computed(() => {
  const parts = []
  if (cap.value.guestImages) parts.push('image/*')
  if (cap.value.guestFiles) parts.push(...SUPPORTED_EXTS.map(e => '.' + e))
  return parts.join(',') || undefined
})
const placeholder = computed(() => canAttach.value ? '输入你的问题，或加上图片/文件…' : '输入你的问题…')
const welcomeHint = computed(() => canAttach.value
  ? '在下方输入你的问题；图片和文件可以直接拖进来、粘贴进来，或者点左侧的加号添加。'
  : '在下方输入你的问题，我会基于所配置的资料与能力回答。')
const hasUploading = () => pendingFiles.value.some(f => f.uploading)
const canSend = computed(() => !!draft.value.trim() && !sending.value && !hasUploading())

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

/** 历史消息 → 页面消息（首屏恢复与切换会话同一份口径）。
 *  <p>messageId / degradations 都取自后端**已经在下发**的字段（SessionService.toMessageMap）：
 *  此前只留 role/content/images/sources，于是降级提示看不见、评价也没有落点。 */
const mapHistory = rows => (Array.isArray(rows) ? rows : [])
  .filter(m => m && m.content)
  .map(m => ({
    role: m.role === 'user' ? 'user' : 'ai', content: m.content,
    messageId: m.messageId || '',
    // 图片与引用随消息一起落库（后端 toMessageMap 同源输出），刷新后不必重问一遍才有图
    images: Array.isArray(m.images) ? m.images : [],
    sources: Array.isArray(m.sources) ? m.sources : [],
    // 附件（含 fileId）：attachments 给气泡展示、attachData 给重新生成带走凭据——
    // 两个字段名与主对话页一致（共享的 ChatThread/UserBubble 按 attachments 取展示列表）
    attachments: Array.isArray(m.attachments) ? m.attachments : [],
    attachData: Array.isArray(m.attachments) ? m.attachments : [],
    degradations: Array.isArray(m.degradations) ? m.degradations : [],
    // 既有评价（后端按 messageId 回填，与登录态同口径）：不回填的话刷新后按钮显示成没评过，
    // 游客会以为反馈丢了再点一次
    fb: (m.fb === 0 || m.fb === 1) ? m.fb : null,
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
  attachRunning()
}

/** 开一段新对话：只清指针与页面，会话仍由后端在首条消息时创建（不会攒出空会话） */
function newSession () {
  if (sending.value || !messages.value.length) return
  clearPending()
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
    clearPending()
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
    if (r && r.success !== false) {
      const d = r.data || {}
      info.value = d
      // 能力位与限额：只认后端下发的，缺失一律按"没开"（保守侧）
      cap.value = {
        guestImages: !!d.guestImages,
        guestFiles: !!d.guestFiles,
        maxImages: d.maxImages || MAX_IMAGES,
        maxImageMb: d.maxImageMb || 10,
        maxAttachments: d.maxAttachments || MAX_FILES,
        maxAttachmentMb: d.maxAttachmentMb || MAX_FILE_MB,
        maxQuestionChars: d.maxQuestionChars || 8000
      }
    }
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
    attachRunning()   // 刷新时这一轮可能还在跑（关掉页面它照旧继续），接回来给它长完
  }
})
onUnmounted(() => {
  if (controller) controller.abort()
})

// ==================== 取材：选择 / 拖入 / 粘贴 / 长文本 ====================
/** 点「+」：分享页没有 + 面板，直接开系统选择器（主对话页那侧是展开面板，各取所需） */
function openPicker () {
  if (sending.value) return
  inputRef.value?.openPicker()
}
/** 选择器收下来的文件 */
function onPickedFiles (files) {
  if (sending.value) return
  addFiles(files)
}
// 拖拽的进出场与「只有真拖着文件才亮提示」的判据都在 ChatInput 里（它自己数 dragDepth），
// 这里只接文件数组；生成中不收（本页没有插话能力，收了也发不出去）
function onDropFiles (files) {
  if (sending.value) return
  addFiles(files)
}
/** 粘贴：带文件的交给附件管线（Composer 已 preventDefault，不会把文件名当文本插进正文）；
 *  超长文本转成附件（问题正文有长度上限，整篇文档塞进去只会被拒）。
 *  返回 true 表示这次粘贴已被本页消费——Composer 据此决定还要不要把纯文本插进光标处 */
function onComposerPaste (e) {
  if (!canAttach.value || sending.value) return
  const files = pasteFilesOf(e.clipboardData)
  if (files.length) { addFiles(files); return }
  if (isLongPaste(e.clipboardData)) {
    addFiles([new File([e.clipboardData.getData('text/plain')], pasteNameOf(pendingFiles.value), { type: 'text/plain' })])
    message.info('粘贴的长文本已转为附件')
  }
}
/**
 * 取材统一入口：判定在 intake.js（与主聊天页共用同一份白名单/限额/文案），
 * 这里只落副作用——图片去压缩、附件去上传、越界就提示。
 * <p>先按能力位拦一道：能力没开时**不收**（而不是收下再让服务端拒），
 * 用户当场就知道这条链接不支持，不用等发送。
 */
function addFiles (files) {
  let imgAccepted = 0
  let fileMaxWarned = false
  for (const f of files) {
    if (isImageFile(f) && !cap.value.guestImages) { message.warning('该分享未开放图片输入'); continue }
    if (!isImageFile(f) && !cap.value.guestFiles) { message.warning('该分享未开放附件上传'); continue }
    // 本批已接受的张数自己累加：compressImage 是异步的，列表长度在循环里不会变
    const verdict = classifyIntake(f, pendingImages.value.length + imgAccepted, pendingFiles.value.length, limits.value)
    if (verdict.kind === 'reject') {
      if (verdict.reason !== REJECT.FILE_MAX || !fileMaxWarned) {
        message.warning(rejectText(verdict))
        if (verdict.reason === REJECT.FILE_MAX) fileMaxWarned = true
      }
      continue
    }
    if (verdict.kind === 'image') {
      imgAccepted++
      compressImage(f)
        .then(dataUrl => pendingImages.value.push({ chipKey: 'p' + (++chipSeq), dataUrl }))
        .catch(() => message.error(`图片处理失败：${verdict.label}`))
      continue
    }
    uploadOne(f)
  }
}
/** 上传单个附件换 fileId（上传中就挂进卡片，用户能看到进度与失败态，而不是点发送后才知道没传上去） */
async function uploadOne (f) {
  const item = reactive({ chipKey: 'f' + (++chipSeq), name: f.name, size: f.size, mime: f.type || '',
                          fileId: '', uploading: true, error: '' })
  pendingFiles.value.push(item)
  try {
    const r = await uploadShareAttachment(token.value, visitorId(), f)
    const d = (r && r.data) || {}
    if (!d.fileId) throw new Error('上传未返回文件标识')
    item.fileId = d.fileId
    item.name = d.name || item.name
    item.mime = d.mime || item.mime
    if (d.size) item.size = d.size
    item.uploading = false
  } catch (e) {
    item.uploading = false
    item.error = e.message || '上传失败'
    message.error(`附件上传失败：${f.name} — ${item.error}`)
  }
}
function clearPending () {
  pendingImages.value = []
  pendingFiles.value = []
}

// ==================== chip 契约（与主对话页同一套：见 chat/Composer.vue 的三条边界）====================
/** key → 画成什么样。名称/体积/上传进度/失败态都取自**数组**（DOM 只是呈现） */
function chipOf (key) {
  if (!key) return null
  if (/^f\d+$/.test(key)) {
    const f = pendingFiles.value.find(x => x.chipKey === key)
    if (!f) return null
    return {
      kind: 'file', icon: 'file',
      name: f.name,
      sub: f.uploading ? '上传中…' : (f.error ? '上传失败' : fmtSize(f.size)),
      state: f.uploading ? 'uploading' : (f.error ? 'err' : ''),
      title: f.error || f.name, delTitle: '移除该附件',
      fileId: f.fileId || '', mime: f.mime || '', size: f.size || 0
    }
  }
  if (/^p\d+$/.test(key)) {
    const p = pendingImages.value.find(x => x.chipKey === key)
    if (!p) return null
    return { kind: 'img', icon: 'img', name: '', thumb: p.dataUrl,
             title: '待发送图片（悬浮可看大图）', delTitle: '移除该图片' }
  }
  return null
}
/** 数组 → 编辑区：增的插到光标处、减的摘掉节点，顺带重画（上传进度/体积/失败态都在 chip 上） */
function syncChips () {
  const cp = inputRef.value
  if (!cp) return
  cp.sync(desiredKeys())
  cp.repaint()
  inlineKeys.value = cp.domKeys()
}
const desiredKeys = () => [...pendingImages.value.map(p => p.chipKey), ...pendingFiles.value.map(f => f.chipKey)]
/** DOM → 数组：Backspace 删掉一颗 chip 等于取消那条材料（不剪枝的话引用还在生效、卡片却没了） */
function onKeys (keys) {
  const keep = new Set(keys)
  const gone = new Set(inlineKeys.value.filter(k => !keep.has(k)))
  inlineKeys.value = keys
  if (!gone.size) return
  if (pendingImages.value.some(p => gone.has(p.chipKey))) {
    pendingImages.value = pendingImages.value.filter(p => !gone.has(p.chipKey))
  }
  if (pendingFiles.value.some(f => gone.has(f.chipKey))) {
    pendingFiles.value = pendingFiles.value.filter(f => !gone.has(f.chipKey))
  }
}
/** 点图片 chip：开灯箱看大图（附件没有可点开的全文，全名已在悬浮提示里） */
function onChipClick (key) {
  const d = chipOf(key)
  if (d && d.kind === 'img') {
    const i = pendingImages.value.findIndex(p => p.chipKey === key)
    if (i >= 0) openImages(pendingImages.value.map(x => x.dataUrl), i)
  }
}
/** 回车发送 / Shift+回车换行由 Composer 自己插换行，这里只管「回车=发送」这一件事 */
function onInputKeydown (e) {
  if (e.isComposing || e.keyCode === 229) return   // 输入法组合中（中文候选未上屏）一律放行
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
}
watch([pendingImages, pendingFiles], syncChips, { deep: true })

/** 发送载荷：图片走 data URL 内联、附件只带 fileId（与登录态同一形状，服务端共用一套校验）。
 *  上传失败或还没传完的一律不带——带上去会被后端整轮拒掉，用户看到的就成了"点了没反应"。
 *  <p>附件顺带带上 name/size 只为本轮气泡显示"我发了哪个文件"：服务端会用自己落盘的元信息
 *  覆盖这两项，客户端传什么它都不信（见 ShareController.parseGuestAttachments）。 */
function currentPayload () {
  return {
    images: pendingImages.value.map(p => p.dataUrl),
    attachments: pendingFiles.value
      .filter(f => f.fileId && !f.error && !f.uploading)
      .map(f => ({ fileId: f.fileId, name: f.name, mime: f.mime, size: f.size }))
  }
}

function send () {
  const text = draft.value.trim()
  if (sending.value) return
  if (!text) { message.warning('请输入问题'); return }
  if (text.length > cap.value.maxQuestionChars) {
    message.warning(`单条内容过长（最多 ${cap.value.maxQuestionChars} 字）`)
    return
  }
  if (hasUploading()) { message.warning('还有附件在上传中，请稍候再发'); return }
  const { images, attachments } = currentPayload()
  draft.value = ''
  clearPending()
  messages.value.push({ role: 'user', content: text, images, attachments, attachData: attachments })
  // 必须用 reactive 包一条消息：直接 push 普通对象再回头改 `ai.content` 是**绕过代理**的写入，
  // 不触发重渲染（症状：正文永远不出现，页面只显示空气泡 + 停止键）。
  const ai = newAiMessage()
  messages.value.push(ai)
  sending.value = true
  scrollBottom()
  controller = new AbortController()
  sendShareMessage(token.value, {
    sessionId: sessionId.value, visitorId: visitorId(), message: text, images, attachments
  }, {
    signal: controller.signal,
    ...shareStreamHandlers(ai)
  })
}

/** 一条等待中的回答（发起与接流共用同一形状） */
const newAiMessage = () => reactive({
  role: 'ai', content: '', images: [], sources: [], degradations: [],
  messageId: '', fb: null, srcOpen: true, time: Date.now(), turnMs: 0, humanWaitMs: 0, failed: false, loading: true
})

/** 开一轮要把「整轮用时」那三条数复位：TurnBar 跑着时读 time 当起点，答完读 done 带回的
 *  墙钟。重新生成复用同一个消息对象，漏一下就是上一轮的秒数接着往这轮上加。 */
function resetAiTurn (ai) {
  ai.time = Date.now()
  ai.turnMs = 0
  ai.humanWaitMs = 0
  ai.failed = false
  ai.loading = true
}

/**
 * 一轮问答的回调（发起与断线接流共用一份）：逐字正文、图片、收尾汇总。
 * 抽出来是为了「接回来的这一轮」和「一直看着的这一轮」在屏幕上长得一样。
 */
function shareStreamHandlers (ai) {
  return {
    onToken: t => {
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
      ai.loading = false
      // done.content 是本轮落库版汇总：sources（角标点开的就是它）、finalImages（正文图）、
      // finalContent（引用自检/清理后改写过正文时，以它为准，否则 [图片N] 与 [N] 的下标会错位）
      try {
        const p = JSON.parse(d.content || '{}')
        if (Array.isArray(p.sources)) ai.sources = p.sources
        if (Array.isArray(p.finalImages)) ai.images = p.finalImages
        if (typeof p.finalContent === 'string' && p.finalContent !== '') ai.content = p.finalContent
        if (Array.isArray(p.degradations)) ai.degradations = p.degradations
        // 落库的消息 ID：评价与「重新生成」都要用它（服务端按它校验归属）
        if (p.messageId) ai.messageId = p.messageId
        // 整轮用时：与主对话页读同一个数（后端算好的墙钟），那条总览两页才说同一句话
        if (typeof p.elapsedMs === 'number') ai.turnMs = p.elapsedMs
        if (typeof p.humanWaitMs === 'number') ai.humanWaitMs = p.humanWaitMs
      } catch (e) { /* 汇总解析失败：正文已在流式里累积完，不影响可读 */ }
      // 首轮由服务端建会话：done 带回 sessionId，持久化供刷新恢复
      if (d && d.sessionId) rememberSession(d.sessionId)
      scrollBottom()
    },
    onError: msg => {
      sending.value = false
      ai.loading = false
      ai.failed = true
      if (!ai.content) ai.content = '⚠️ ' + (msg || '回答失败')
      else message.warning(msg || '本轮中断')
      scrollBottom()
    }
  }
}

/**
 * 刷新/切会话后接回正在跑的这一轮：先探测（没有就不开通道），有才挂一条新通道。
 * <p>分享页原本只有「按历史回显」这一条恢复路径——正在生成的那一轮要等它落库后才看得见，
 * 用户刷新后面对的是一个不动的界面，读成「卡住了」。
 */
const attaching = ref(false)
async function attachRunning () {
  const sid = sessionId.value
  if (!sid || sending.value || attaching.value) return
  attaching.value = true
  let running = false
  let snap = null
  try {
    const r = await getShareRunStatus(token.value, sid, visitorId())
    running = !!(r && r.data && r.data.running)
  } catch (e) { /* 探测失败不打扰：分享页首要还是能用，实在在跑的话下一次刷新再接 */ } finally { attaching.value = false }
  if (!running) return
  const ai = newAiMessage()
  messages.value.push(ai)
  sending.value = true
  scrollBottom()
  controller = new AbortController()
  resumeShareTurn(token.value, sid, visitorId(), {
    signal: controller.signal,
    ...shareStreamHandlers(ai),
    // 快照：此刻为止的正文/图片/引用一次给全，之后只收快照之后的增量（序号去重在 api 内核里）
    onSnapshot: payload => {
      try {
        snap = typeof payload === 'string' ? JSON.parse(payload) : payload
      } catch (e) { snap = null }
      if (!snap || snap.running === false) {
        // 探测与接流之间那一轮正好收尾：按历史回显，别留一颗空转的气泡
        sending.value = false
        ai.loading = false
        if (!ai.content) ai.content = ''
        reloadHistoryQuietly()
        return
      }
      if (typeof snap.content === 'string') ai.content = snap.content
      if (Array.isArray(snap.images)) ai.images = snap.images
      if (Array.isArray(snap.sources)) ai.sources = snap.sources
      // 同主对话页：接上的这一轮按服务端已跑时长续表，不从 0 重新起跳
      ai.time = resumedStartAt(snap.elapsedMs, Date.now())
      scrollBottom()
    }
  })
}

/** 静默重载历史（接流落空时用：这一轮已经落库，按历史显示最准） */
async function reloadHistoryQuietly () {
  const sid = sessionId.value
  if (!sid) return
  try {
    const r = await getShareHistory(token.value, sid, visitorId())
    const list = mapHistory(r.data)
    if (list.length) messages.value = list
  } catch (e) { /* 历史拉不动就保持现状，别把已有内容清成空白 */ }
}

function stop () {
  // 先叫停服务端那一轮：本轮已挂到运行总线上（为了刷新能接回来），
  // 只 abort 前端这条通道会让它继续在后台跑满宽限期——用户按了停止却还在烧额度
  if (sessionId.value) {
    stopShareTurn(token.value, sessionId.value, visitorId()).catch(() => { /* 停不掉不算失败：那一轮可能刚结束 */ })
  }
  if (controller) controller.abort()
  sending.value = false
  // 中断要把这一轮**整条撤掉**，不能只置 sending=false——那样「正在溯源」那一行会永久
  // 卡在页面上（后续新消息也压不掉它）。曾改成补一句「已停止生成」占位气泡，
  // 但那是空的回答，留在对话里比不留更碍眼；用户点了停止，要的就是"没这条"。
  // 判据必须是 'ai'：这页从历史回显到本地气泡都用 'ai'/'user'，写成别的名义上是撤气泡、
  // 实际上是两条分支都不执行（空泡留着呼吸、已出正文的那条永远停在流式态）
  const cur = messages.value[messages.value.length - 1]
  if (cur && cur.role === 'ai' && !cur.content) messages.value.pop()
  // 已出正文的那条要撤掉 loading：否则这一轮永远停在流式态，正文里的图表不会被补画
  else if (cur && cur.role === 'ai') cur.loading = false
  scrollBottom()
}

// ==================== 回答动作：复制 / 评价 / 重新生成 ====================
/** 服务端图片 URL → data URL：重新生成要把原图原样带走，而历史回放的图只剩带签名的 URL，
 *  直接发出去会被服务端的 data:image/ 前缀校验拒掉。签名有效期内 fetch 得到，过期就放弃这张 */
const fetchToDataUrl = async url => {
  const r = await fetch(url)
  if (!r.ok) throw new Error('HTTP ' + r.status)
  const b = await r.blob()
  return await new Promise((resolve, reject) => {
    const fr = new FileReader()
    fr.onload = () => resolve(fr.result)
    fr.onerror = () => reject(new Error('读取失败'))
    fr.readAsDataURL(b)
  })
}


/**
 * 提交评价（一击完成，不弹输入框）。
 * <p>登录态那边点👎会弹一个可选的意见框；公开链接上刻意省掉这一步——访客是路人，
 * 多一步就少一大截反馈量，而"这一条被踩了"本身已经是最有价值的那条信号。
 */
async function fb (m, rating) {
  if (m.fb != null || !m.messageId || sending.value) return
  // 乐观置位：按钮立即变灰，避免连点产生两条反馈请求（服务端按 messageId 唯一，重复写只会覆盖）
  m.fb = rating
  try {
    const r = await submitShareFeedback(token.value, {
      messageId: m.messageId, visitorId: visitorId(), rating
    })
    if (r && r.success === false) throw new Error(r.msg || '反馈失败')
    message.success(rating === 1 ? '谢谢，已记为有帮助' : '谢谢，已记为没帮助')
  } catch (e) {
    m.fb = null   // 失败要退回可评状态，否则用户以为评上了、其实没落库
    message.error(e.message || '反馈失败，请稍后再试')
  }
}

/**
 * 重新生成这一条回答：找到它对应的提问，按原样（含图片/附件）重发一轮。
 * <p>与登录态同一套语义：{@code regenerate=true} 让服务端跳过用户消息的重复落库，
 * 并带上 {@code replaceMessageId}（工作流驱动的智能体据此软删旧回答）。
 * <p>只在最后一条回答上提供入口：中间那轮重跑会与后面几轮的历史错位。
 */
async function regenerate (aiIndex) {
  if (sending.value) return
  const ai = messages.value[aiIndex]
  if (!ai || ai.role !== 'ai') return
  let ui = -1
  for (let i = aiIndex - 1; i >= 0; i--) {
    if (messages.value[i].role === 'user') { ui = i; break }
  }
  if (ui < 0) { message.warning('找不到这一轮对应的问题'); return }
  const q = messages.value[ui]
  // 图片：本会话刚发的是 data URL，历史回放的是签名 URL——后者抓回字节才能重发。
  // 抓不回来（签名过期/文件已清）就只重生成文字部分，并且说出来，不然用户以为图也带上了
  const images = []
  if (cap.value.guestImages) {
    let dropped = 0
    for (const u of (q.images || [])) {
      if (String(u).startsWith('data:')) { images.push(u); continue }
      try { images.push(await fetchToDataUrl(resolveImg(u))) } catch (e) { dropped++ }
    }
    if (dropped) message.warning(`有 ${dropped} 张图片已无法重新发送，本次只按文字重新生成`)
  }
  // 附件：只带仍然有效的 fileId（后端会按访客目录再复核一次归属与是否过期）
  const attachments = cap.value.guestFiles
    ? (q.attachData || []).filter(a => a && a.fileId).map(a => ({ fileId: a.fileId }))
    : []
  if (cap.value.guestFiles && (q.attachData || []).length > attachments.length) {
    message.warning('部分附件已失效，本次重新生成不带它们')
  }
  // 视图上把这一条换掉：新回答接管同一条气泡，旧内容不再占位。
  // replaceMessageId 必须**先取下来**再清 ai.messageId——顺序反过来就是把自己刚清空的空串发上去，
  // 旧回答不会被替换，刷新后同一问会挂着两条回答
  const replaceId = ai.messageId || undefined
  ai.content = ''
  ai.images = []
  ai.sources = []
  ai.degradations = []
  ai.fb = null
  ai.messageId = ''
  resetAiTurn(ai)
  sending.value = true
  scrollBottom()
  controller = new AbortController()
  sendShareMessage(token.value, {
    sessionId: sessionId.value, visitorId: visitorId(), message: q.content,
    images, attachments, regenerate: true, replaceMessageId: replaceId
  }, {
    signal: controller.signal,
    ...shareStreamHandlers(ai)
  })
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




@keyframes sc-blink { 0%, 100% { opacity: .25; } 50% { opacity: 1; } }




.sc-foot {
  max-width: 860px; margin: 8px auto 0; display: flex; justify-content: space-between;
  gap: 12px; font-size: 11.5px; color: var(--app-text3);
}
.sc-foot-hint { white-space: nowrap; }


/* iframe 嵌入紧凑模式：无头部、小内边距、空态收紧（宿主给的框通常只有 420×640） */
.sc-page.embed .sc-list { padding: 12px; }
.sc-page.embed .sc-welcome { padding: 28px 12px 20px; }
.sc-page.embed .sc-welcome h2 { font-size: 15px; margin-top: 10px; }
/* 输入卡片本身归 ChatInput，这里只调它外层的间距（embed 的宿主框通常只有 420×640）。
   组件根元素带本页的 scope id，所以 :deep() 能命中它，同时不必把 embed 的数值塞进共享组件 */
.sc-page.embed :deep(.ci) { padding: 8px 12px; }
.sc-page.embed :deep(.ci-drop) { inset: 6px 12px; }
.sc-page.embed .sc-foot-hint { display: none; }


/* ==================== 移动端窄屏（h5）====================
   分享链接大量在微信/手机浏览器里打开，这页不走 AppLayout、此前零窄屏适配。
   结构本身是 max-width + flex，天然不破版，这里只补三件事：
   高度交给 --app-vh（键盘）、内边距收窄、触控热区。 */
@media (max-width: 768px) {
  /* 100vh 在 iOS 键盘弹起时不变 → 输入框被盖。用 --app-vh（视觉视口高，已扣键盘）；
     宽屏不写该变量 → 回落 100vh，与改动前一致 */
  .sc-page { height: 100vh; height: var(--app-vh, 100vh); }
  /* iOS 上聚焦字号 <16px 的输入框会自动放大页面且不缩回。这条只对分享页成立：
     /s/:token 不走移动壳（手机直接打开这一页），而主对话页的窄屏是「桌面窗口被拖窄」、
     没有软键盘，所以 16px 不该塞进共享组件里去改主对话页。 */
  .sc-page :deep(.composer-edit) { font-size: 16px; }
  .sc-head { padding: calc(10px + var(--sat, 0px)) 12px 10px; gap: 10px; }
  .sc-desc { -webkit-line-clamp: 1; }
  .sc-brand span { display: none; }
  .sc-brand { padding: 5px 7px; }
  /* 会话工具收成图标（窄屏头部要给名称留位），热区补到 34px */
  .sc-tool-t { display: none; }
  .sc-tool { padding: 7px 9px; font-size: 14px; }
  .sc-list { padding: 14px 12px 8px; overscroll-behavior-y: contain; }
  .sc-welcome { padding: 40px 8px 24px; }
  .sc-foot { padding: 0 2px; }
  .sc-foot-hint { display: none; }
}
</style>
