<template>
  <!-- 移动原生壳（M2）：独立于工作台三栏布局的聊天主线。
       引擎（会话/流式/选择器/附件/引用）与 PC 壳共用同一份 useChatEngine，此处只做移动呈现：
       顶栏 + 全屏消息流 + 贴底输入区 + 底部 sheet 族（会话/模型思考/引用与附件/状态来源）。
       发送靠按钮（移动键盘的回车是换行）、停止直点（无桌面 ESC 两段式）。 -->
  <div class="m-chat">
    <header class="m-bar">
      <button class="m-bar-btn" type="button" title="会话列表" @click="sessionsOpen = true">
        <menu-outlined />
        <span v-if="loading" class="m-bar-dot" />
      </button>
      <button class="m-bar-title" type="button" title="查看状态与来源" @click="refOpen = true">
        <span class="m-bar-t">{{ currentSessionTitle }}</span>
        <caret-down-outlined class="m-bar-caret" />
      </button>
      <button class="m-bar-btn" type="button" title="新建对话" @click="createNewSession()">
        <plus-outlined />
      </button>
    </header>

    <main ref="box" class="m-list" @scroll.passive="onScroll">
      <!-- 空态：品牌 + 示例问题（点即发） -->
      <div v-if="!messages.length" class="m-welcome">
        <BrandMark class="m-brand" />
        <div class="m-welcome-title">有什么可以帮你？</div>
        <div class="m-welcome-sub">智能体与知识库问答，支持图片提问与深度思考</div>
        <MobileSampleCards :questions="SAMPLE_QUESTIONS" @ask="ask" />
      </div>

      <MobileMsgRow
        v-for="(m, i) in messages" :key="m.messageId || i"
        :m="m" :index="i" :active="activeIdx === i" :is-last="i === messages.length - 1"
        :debug-display="debugDisplayVisible" :session-id="currentSessionId || ''" :loading="loading"
        :variant-switching="variantSwitching"
        @activate="activeIdx = $event"
        @preview="openPreview"
        @source="openSourceDetail"
        @retry="regenerate"
        @edit="startEdit"
        @feedback="submitFeedback"
        @approve="resolveApproval"
        @switch-version="switchBranch"
        @ask="ask"
        @copy="copyAnswer"
        @copy-user="copyUserMessage"
      />
    </main>

    <button v-if="!stickToBottom && messages.length" class="m-jump" type="button" title="回到最新" @click="scrollForce()">
      <arrow-down-outlined />
    </button>

    <!-- ==================== 输入区 ==================== -->
    <footer class="m-composer">
      <!-- 本轮随行内容 chips -->
      <div v-if="hasChips" class="m-chips">
        <span v-for="(p, i) in pendingImages" :key="'img' + i" class="m-chip m-chip-img">
          <img :src="p.dataUrl" alt="" @click="openPreview(pendingImages.map(x => x.dataUrl), i)" />
          <button class="m-chip-del" type="button" title="移除" @click="removePendingImage(i)">×</button>
        </span>
        <span v-for="(f, i) in pendingFiles" :key="'f' + i" class="m-chip">
          {{ f.uploading ? '上传中…' : f.name }}<button class="m-chip-del" type="button" title="移除" @click="removePendingFile(i)">×</button>
        </span>
        <span v-for="s in pickedSkills" :key="'s' + s" class="m-chip m-chip-skill">
          技能 · {{ s }}<button class="m-chip-del" type="button" title="移除" @click="toggleSkill(s)">×</button>
        </span>
        <span v-for="(mm, i) in pendingMentions" :key="'m' + i" class="m-chip m-chip-mention">
          {{ mm.name || mm.id }}<button class="m-chip-del" type="button" title="移除" @click="removeMention(i)">×</button>
        </span>
        <span v-for="(h, i) in pendingHistoryRefs" :key="'h' + i" class="m-chip">
          历史 · {{ (h.digest || h.messageId).slice(0, 8) }}<button class="m-chip-del" type="button" title="移除" @click="removeHistoryRef(i)">×</button>
        </span>
      </div>

      <div class="m-input-card">
        <textarea
          ref="ta"
          v-model="text"
          class="m-ta"
          rows="1"
          placeholder="问点什么？"
          enterkeyhint="enter"
          @input="autosize"
        ></textarea>
        <div class="m-tools">
          <button class="m-tool" type="button" title="模型与思考" @click="modelOpen = true">
            <thunderbolt-outlined :class="{ 'm-tool-on': deepThinkOn }" />
            <span class="m-tool-text">{{ modelShort }}</span>
          </button>
          <button class="m-tool" type="button" title="添加：技能 / 引用 / 历史 / 附件" @click="attachOpen = true">
            <plus-circle-outlined />
          </button>
          <span class="m-tools-sp" />
          <button v-if="loading" class="m-send stop" type="button" title="停止生成" @click="stop()">
            <pause-circle-outlined />
          </button>
          <button v-else class="m-send" type="button" title="发送" :disabled="!canSend" @click="send()">
            <arrow-up-outlined />
          </button>
        </div>
      </div>
    </footer>

    <!-- ==================== sheet 族 ==================== -->
    <MobileSessionSheet :open="sessionsOpen" :current-id="currentSessionId || ''"
                        @close="sessionsOpen = false"
                        @select="onSelectSession"
                        @new-chat="onNewChat" />
    <MobileModelSheet :open="modelOpen" @close="modelOpen = false" />
    <MobileAttachSheet :open="attachOpen" @close="attachOpen = false" />
    <MobileRefSheet :open="refOpen" @close="refOpen = false" @source="openSourceDetail" />

    <!-- ==================== 来源详情 ==================== -->
    <BottomSheet :open="src.visible" :title="src.title" max-height="72dvh" @close="src.visible = false">
      <div class="m-src">
        <a v-if="src.url" class="m-src-url" :href="src.url" target="_blank" rel="noopener">
          <global-outlined /> 打开原网页
        </a>
        <div v-if="src.loading" class="m-src-tip">正在读取原文…</div>
        <div v-if="src.snippet" class="m-src-snippet">{{ src.snippet }}</div>
        <img v-for="(u, i) in src.images" :key="i" class="m-src-img" :src="resolveImg(u)" alt="原文图片" @click="openPreview(src.images.map(resolveImg), i)" />
        <div v-if="src.content" class="md m-src-content" v-html="renderMd(src.content, [])" />
        <div v-if="!src.loading && !src.content && !src.snippet" class="m-src-tip">该来源没有可展示的内容</div>
      </div>
    </BottomSheet>

    <!-- ==================== 图片查看 ==================== -->
    <Teleport to="body">
      <div v-if="preview.list.length" class="m-lightbox" @click="closePreview">
        <img :src="preview.list[preview.idx]" class="m-lightbox-img" alt="图片" @click.stop />
        <button v-if="preview.list.length > 1" class="m-lb-nav prev" type="button" @click.stop="preview.idx = (preview.idx + preview.list.length - 1) % preview.list.length">‹</button>
        <button v-if="preview.list.length > 1" class="m-lb-nav next" type="button" @click.stop="preview.idx = (preview.idx + 1) % preview.list.length">›</button>
        <button class="m-lb-close" type="button" title="关闭" @click="closePreview">×</button>
        <div v-if="preview.list.length > 1" class="m-lb-count">{{ preview.idx + 1 }} / {{ preview.list.length }}</div>
      </div>
    </Teleport>

    <!-- ==================== 编辑重发（全屏编辑） ==================== -->
    <Teleport to="body">
      <div v-if="edit.open" class="m-edit">
        <header class="m-edit-bar">
          <button class="m-bar-btn" type="button" title="取消" @click="closeEdit"><close-outlined /></button>
          <div class="m-edit-title">编辑后从这一轮重新生成</div>
          <button class="m-edit-ok" type="button" :disabled="!edit.text.trim()" @click="confirmEdit">重新生成</button>
        </header>
        <textarea ref="editTa" v-model="edit.text" class="m-edit-ta" placeholder="修改你的问题…"></textarea>
      </div>
    </Teleport>

    <!-- ==================== 反馈 ==================== -->
    <BottomSheet :open="fb.open" :title="fb.rating === 1 ? '这条回答有帮助' : '这条回答没帮助'" max-height="60dvh" @close="fb.open = false">
      <textarea v-model="fb.text" class="m-fb-ta" rows="3" placeholder="补充说明（选填）：哪里好 / 哪里不对？"></textarea>
      <button class="m-fb-ok" type="button" :disabled="fb.busy" @click="doFeedback">提交反馈</button>
    </BottomSheet>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, provide, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  MenuOutlined, PlusOutlined, CaretDownOutlined, ArrowUpOutlined, ArrowDownOutlined, PauseCircleOutlined,
  ThunderboltOutlined, PlusCircleOutlined, CloseOutlined, GlobalOutlined
} from '@ant-design/icons-vue'
import { useChatEngine } from '../chat/useChatEngine'
import { submitFeedback as apiSubmitFeedback, getKnowledgeDetail } from '../api'
import { renderMd, resolveImg, enhanceDiagrams } from '../utils/markdown'
import { preferMobileShell } from './mobile'
import BottomSheet from './BottomSheet.vue'
import MobileMsgRow from './MobileMsgRow.vue'
import MobileSessionSheet from './MobileSessionSheet.vue'
import MobileModelSheet from './MobileModelSheet.vue'
import MobileAttachSheet from './MobileAttachSheet.vue'
import MobileRefSheet from './MobileRefSheet.vue'
import MobileSampleCards from './MobileSampleCards.vue'
import BrandMark from '../components/BrandMark.vue'

const router = useRouter()

// ==================== 滚动（引擎 hooks 的落点） ====================
const box = ref(null)
const stickToBottom = ref(true)
const AUTO_SCROLL_MARGIN = 80
const onScroll = () => {
  const el = box.value
  if (!el) return
  stickToBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < AUTO_SCROLL_MARGIN
}
const scroll = () => nextTick(() => {
  const el = box.value
  if (el && stickToBottom.value) el.scrollTop = el.scrollHeight
})
const scrollForce = () => nextTick(() => {
  const el = box.value
  if (el) { el.scrollTop = el.scrollHeight; stickToBottom.value = true }
})

const ta = ref(null)
// 输入框自适应高度（上限 132px 后内部滚动）
const autosize = () => {
  const el = ta.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 132) + 'px'
}

// ==================== sheet 开关（send 时统一收起） ====================
const sessionsOpen = ref(false)
const modelOpen = ref(false)
const attachOpen = ref(false)
const refOpen = ref(false)
const closeSheets = () => { sessionsOpen.value = false; modelOpen.value = false; attachOpen.value = false; refOpen.value = false }

// ==================== 引擎（与 PC 同一份） ====================
// 刻意不传 focusInput：移动端挂载即弹软键盘是反模式（PC 传它是为了键盘用户开箱可打）。
const engine = useChatEngine({
  chatPath: '/m/chat',
  scrollFollow: scroll,
  scrollForce,
  scrollSoft: scroll,
  closePanels: closeSheets
})
// sheet 族经 provide 取同一实例（useChatEngine 必须单例，不能各调一次）
provide('wqChat', engine)
const {
  text, canSend, send, stop, streamAnswer, messages, loading, currentSessionId, currentSessionTitle,
  deepThinkOn, effectiveModelLabel, debugDisplayVisible, regenerate, switchBranch, variantSwitching,
  resolveApproval, createNewSession, pendingImages, pendingFiles, pickedSkills, pendingMentions,
  pendingHistoryRefs, toggleSkill, removePendingImage, removePendingFile, removeMention, removeHistoryRef,
  ready
} = engine

// 模型短名（工具条展示）：优先模型库友好名，缩到 10 字以内
const modelShort = computed(() => {
  const v = effectiveModelLabel.value || ''
  return v.length > 10 ? v.slice(0, 10) + '…' : v
})
const hasChips = computed(() => pendingImages.value.length || pendingFiles.value.length || pickedSkills.value.length
  || pendingMentions.value.length || pendingHistoryRefs.value.length)

// ==================== 空态示例问题（与 PC 同四类） ====================
const SAMPLE_QUESTIONS = [
  { icon: '🔍', label: '知识检索', text: '帮我查一下系统操作手册里的登录步骤' },
  { icon: '📝', label: '总结提炼', text: '把这篇文档的核心要点总结成 5 条' },
  { icon: '✍️', label: '辅助写作', text: '帮我起草一份项目周报的框架' },
  { icon: '📊', label: '对比分析', text: '对比一下方案 A 和方案 B 的优劣' }
]
const ask = q => { text.value = q; nextTick(send) }

// ==================== 交互细节 ====================
const activeIdx = ref(null)
const preview = reactive({ list: [], idx: 0 })
const openPreview = (list, idx) => { preview.list = list || []; preview.idx = idx || 0 }
const closePreview = () => { preview.list = []; preview.idx = 0 }

const copyText = async (txt, okMsg = '已复制到剪贴板') => {
  if (!txt) { message.warning('没有可复制的内容'); return }
  try {
    if (navigator.clipboard?.writeText) { await navigator.clipboard.writeText(txt); message.success(okMsg) }
    else { message.warning('当前环境不支持自动复制，请长按选择文本') }
  } catch (e) { message.warning('复制失败，请长按选择文本') }
}
const copyAnswer = m => copyText((m?.content || '').trim())
const copyUserMessage = m => copyText((m?.content || '').trim())

// 会话切换：引擎的 switchSession 会同步 URL 与历史（含流式中会话的活流接回）
const onSelectSession = async sid => {
  sessionsOpen.value = false
  if (sid !== currentSessionId.value) await engine.switchSession(sid)
}
const onNewChat = async () => { sessionsOpen.value = false; await createNewSession() }

// 切走会话/清空选中：操作行回到「最新一条可见」的默认态，滚动恢复跟随
watch(currentSessionId, () => { activeIdx.value = null; stickToBottom.value = true })

// 来源详情（移动端用 sheet 展示，替代 PC 的弹窗）
const src = reactive({ visible: false, title: '', snippet: '', content: '', images: [], url: '', loading: false })
const openSourceDetail = async s => {
  if (!s) return
  src.visible = true
  src.url = ''
  src.images = []
  src.content = ''
  src.loading = false
  const isExternal = s.origin === 'WEB' || s.origin === 'MCP'
  if (isExternal) {
    src.title = (s.siteName || (s.origin === 'MCP' ? 'MCP 来源' : '联网来源')) + (s.title ? ' §' + s.title : '')
    src.snippet = s.snippet || '（该来源未提供摘要）'
    src.url = s.url || ''
    return
  }
  src.title = (s.fileName || (s.docId ? '来源文档不可用' : '手动补充的知识')) + (s.title ? ' §' + s.title : '')
  src.snippet = s.snippet || '（无原文片段）'
  src.images = Array.isArray(s.images) ? s.images : []
  src.loading = true
  try {
    const r = await getKnowledgeDetail(s.knowledgeId)
    if (r.success && r.data) {
      src.content = r.data.content || ''
      if (Array.isArray(r.data.images)) src.images = r.data.images
      if (r.data.title) src.title = (s.fileName || '来源') + ' §' + r.data.title
    }
  } catch (e) { /* 接口失败回退 snippet */ } finally { src.loading = false }
}

// 反馈：移动端用 sheet（可选补充说明）
const fb = reactive({ open: false, rating: 1, text: '', busy: false, msg: null })
const submitFeedback = (m, rating) => {
  if (m.fb != null) { message.warning('同一回答只能评价一次'); return }
  fb.msg = m; fb.rating = rating; fb.text = ''; fb.open = true
}
const doFeedback = async () => {
  const m = fb.msg
  if (!m || !m.messageId) { message.warning('该回答不可反馈'); return }
  fb.busy = true
  try {
    const r = await apiSubmitFeedback(m.messageId, fb.rating, fb.text.trim())
    if (r.success) { m.fb = fb.rating; message.success('感谢反馈'); fb.open = false }
    else message.error(r.msg || '提交失败')
  } catch (e) { message.error(e.message || '提交失败') } finally { fb.busy = false }
}

// 编辑重发（全屏编辑，正文可改；随行的技能/@/历史/深度思考沿用原轮）
const edit = reactive({ open: false, idx: null, text: '' })
const startEdit = mi => {
  const m = messages.value[mi]
  if (!m || m.role !== 'user') return
  if (loading.value) { message.warning('当前正在回答，请先停止或稍候'); return }
  if (!m.messageId) { message.warning('该消息尚未落库，暂不能编辑重发'); return }
  edit.idx = mi; edit.text = m.content || ''; edit.open = true
}
const closeEdit = () => { edit.open = false; edit.idx = null; edit.text = '' }
const confirmEdit = () => {
  const mi = edit.idx
  const old = mi != null ? messages.value[mi] : null
  const txt = edit.text.trim()
  if (!old || old.role !== 'user') { closeEdit(); return }
  if (!txt) { message.warning('内容不能为空'); return }
  if (txt === old.content) { closeEdit(); return }   // 无改动：不产生新分支
  if (loading.value) { message.warning('当前正在回答，请先停止或稍候'); return }
  const editMessageId = old.messageId
  if (!editMessageId) { message.warning('该消息尚未落库，暂不能编辑重发'); return }
  closeEdit()
  const skills = Array.isArray(old.skills) ? old.skills : []
  const historyRefs = Array.isArray(old.historyRefs) ? old.historyRefs : []
  const deep = !!old.deepThink
  const atts = Array.isArray(old.attachData) ? old.attachData : []
  const attsMeta = Array.isArray(old.attachments) ? old.attachments : []
  const mentionsNew = Array.isArray(old.mentions) ? old.mentions : []
  const imgsAll = old.images || []
  const imgs = imgsAll.filter(u => String(u).startsWith('data:'))
  messages.value = messages.value.slice(0, mi)
  const verN = (old.variantCount || 1) + 1
  const nu = reactive({
    role: 'user', content: txt, images: imgsAll, attachments: attsMeta, attachData: atts,
    skills, mentions: mentionsNew, historyRefs, deepThink: deep,
    time: Date.now(), messageId: null, variantCount: verN, variantIndex: verN
  })
  messages.value.push(nu)
  streamAnswer(txt, imgs, null, false, 1, deep, atts, skills, mentionsNew, null, historyRefs, editMessageId, nu)
}

// 流式结束后补画 mermaid（v-html 之后；流式期间围栏是半截代码，画必然失败）
watch([messages, loading], async () => {
  if (loading.value) return
  await nextTick()
  if (box.value) enhanceDiagrams(box.value).catch(() => {})
})

// 设备形态自检：从移动壳被拖大到桌面宽度（或触屏变鼠标）时回到 PC 布局。
// 只在 Android/iPad 分屏等真实场景兜底；桌面用户永远不会进到这里（守卫已拦）。
const onViewportChange = () => { if (!preferMobileShell()) router.replace({ path: '/chat' }).catch(() => {}) }

onMounted(async () => {
  window.addEventListener('resize', onViewportChange, { passive: true })
  await ready()
  autosize()
})
onUnmounted(() => {
  window.removeEventListener('resize', onViewportChange)
})
</script>

<style scoped>
/* 满高容器：--app-vh 由 h5/keyboard.js 写（视觉视口高，已扣软键盘）——
   键盘弹起时容器收缩，输入区自然上移，不会出现「fixed 贴在布局视口底部被键盘盖住」。 */
.m-chat {
  position: relative;   /* .m-jump 的定位基准 */
  height: 100vh; height: var(--app-vh, 100dvh);
  display: flex; flex-direction: column;
  background: var(--app-bg); color: var(--app-text);
  overflow: hidden;
}

/* ---- 顶栏（安全区让开刘海）---- */
.m-bar {
  flex: none; display: flex; align-items: center; gap: 4px;
  padding: 6px 8px; padding-top: calc(6px + var(--sat, 0px));
  background: var(--app-panel); border-bottom: 1px solid var(--app-border);
}
.m-bar-btn {
  width: 40px; height: 40px; flex: none; border: none; border-radius: 10px; background: transparent;
  color: var(--app-text2); font-size: 18px; display: inline-flex; align-items: center; justify-content: center;
  position: relative; touch-action: manipulation;
}
.m-bar-btn:active { background: var(--app-accent-weak); color: var(--app-accent); }
.m-bar-dot { position: absolute; top: 8px; right: 8px; width: 7px; height: 7px; border-radius: 50%; background: var(--app-accent); }
.m-bar-title {
  flex: 1; min-width: 0; display: inline-flex; align-items: center; justify-content: center; gap: 4px;
  border: none; background: transparent; color: var(--app-text); font-size: 15px; font-weight: 600;
  min-height: 40px; touch-action: manipulation;
}
.m-bar-t { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 70%; }
.m-bar-caret { font-size: 11px; color: var(--app-text3); }

/* ---- 消息流 ---- */
.m-list {
  flex: 1; min-height: 0; overflow-y: auto; overscroll-behavior-y: contain;
  -webkit-overflow-scrolling: touch;
  padding: 12px 12px 8px;
}
.m-welcome { display: flex; flex-direction: column; align-items: center; padding: 20px 0 8px; }
/* 示例卡组件自带 12px 横向内边距（PC 窄屏布局用语）；在列表已加内边距时取消，避免双层缩进 */
.m-welcome :deep(.m-samples) { padding-left: 0; padding-right: 0; }
.m-brand { font-size: 40px; }
.m-welcome-title { margin-top: 12px; font-size: 19px; font-weight: 600; }
.m-welcome-sub { margin: 6px 0 16px; font-size: 13px; color: var(--app-text3); text-align: center; }
.m-jump {
  position: absolute; right: 14px; bottom: calc(150px + var(--sab, 0px) + var(--kb, 0px));
  width: 40px; height: 40px; border-radius: 50%; border: 1px solid var(--app-border);
  background: var(--app-panel); color: var(--app-text2); font-size: 16px; box-shadow: var(--app-shadow);
  display: inline-flex; align-items: center; justify-content: center; touch-action: manipulation; z-index: 5;
}

/* ---- 输入区 ---- */
.m-composer {
  /* 键盘弹起（--kb>0）时容器已按视觉视口收缩，安全区不再叠加——max() 让两者互斥 */
  flex: none; padding: 6px 10px calc(8px + max(0px, var(--sab, 0px) - var(--kb, 0px)));
  background: var(--app-panel); border-top: 1px solid var(--app-border);
}
.m-chips { display: flex; flex-wrap: wrap; gap: 6px; padding: 2px 2px 8px; }
.m-chip {
  display: inline-flex; align-items: center; gap: 4px; max-width: 100%;
  font-size: 12px; color: var(--app-text2); background: var(--app-panel-2);
  border: 1px solid var(--app-border); border-radius: 999px; padding: 4px 6px 4px 10px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.m-chip-skill { background: var(--app-info-weak); border-color: var(--app-info-border); color: var(--app-accent); }
.m-chip-mention { background: var(--app-accent-weak); border-color: var(--app-accent-border); color: var(--app-accent); }
.m-chip-img { padding: 0 0 0 0; border: none; background: transparent; position: relative; }
.m-chip-img img { width: 46px; height: 46px; object-fit: cover; border-radius: 8px; display: block; }
.m-chip-del {
  width: 26px; height: 26px; flex: none; border: none; border-radius: 50%; background: transparent;
  color: var(--app-text3); font-size: 15px; line-height: 1; touch-action: manipulation;
}
.m-chip-img .m-chip-del { position: absolute; top: -6px; right: -6px; background: var(--app-panel); box-shadow: var(--app-shadow-sm); }
.m-input-card {
  border: 1px solid var(--app-border); border-radius: 14px; background: var(--app-panel-2);
  padding: 6px 8px 6px;
}
.m-input-card:focus-within { border-color: var(--app-accent-border); }
.m-ta {
  width: 100%; box-sizing: border-box; border: none; background: transparent; resize: none;
  color: var(--app-text); font-size: 16px; line-height: 1.5; padding: 6px 4px;
  max-height: 132px; outline: none; font-family: inherit;
}
.m-tools { display: flex; align-items: center; gap: 6px; padding: 2px 0 0; }
.m-tool {
  display: inline-flex; align-items: center; gap: 5px; min-height: 36px; padding: 0 10px;
  border: 1px solid var(--app-border); border-radius: 999px; background: var(--app-panel);
  color: var(--app-text2); font-size: 13px; touch-action: manipulation; max-width: 46%;
}
.m-tool-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.m-tool-on { color: var(--app-accent); }
.m-tools-sp { flex: 1; }
.m-send {
  width: 42px; height: 42px; flex: none; border: none; border-radius: 50%;
  background: var(--app-accent); color: #fff; font-size: 18px;
  display: inline-flex; align-items: center; justify-content: center; touch-action: manipulation;
}
.m-send:disabled { background: var(--app-accent-disabled); }
.m-send.stop { background: var(--app-danger-weak); color: var(--app-danger-text); border: 1px solid var(--app-danger-border); }

/* ---- 来源详情 ---- */
.m-src { display: flex; flex-direction: column; gap: 10px; }
.m-src-url { display: inline-flex; align-items: center; gap: 6px; color: var(--app-accent); font-size: 14px; text-decoration: none; }
.m-src-tip { font-size: 13px; color: var(--app-text3); }
.m-src-snippet { font-size: 13px; color: var(--app-text2); background: var(--app-panel-2); border-radius: 8px; padding: 10px; line-height: 1.7; }
.m-src-img { width: 100%; border-radius: 8px; }
.m-src-content { font-size: 14px; line-height: 1.75; }

/* ---- 图片查看 ---- */
.m-lightbox {
  position: fixed; inset: 0; z-index: 1300; background: rgba(0, 0, 0, .9);
  display: flex; align-items: center; justify-content: center;
}
.m-lightbox-img { max-width: 96vw; max-height: 86vh; border-radius: 4px; }
.m-lb-close { position: fixed; top: calc(10px + var(--sat, 0px)); right: 12px; width: 40px; height: 40px; border: none; border-radius: 50%; background: rgba(255, 255, 255, .14); color: #fff; font-size: 22px; touch-action: manipulation; }
.m-lb-nav { position: fixed; top: 50%; transform: translateY(-50%); width: 44px; height: 44px; border: 1px solid rgba(255, 255, 255, .35); border-radius: 50%; background: rgba(0, 0, 0, .4); color: #fff; font-size: 24px; touch-action: manipulation; }
.m-lb-nav.prev { left: 10px; }
.m-lb-nav.next { right: 10px; }
.m-lb-count { position: fixed; bottom: calc(20px + var(--sab, 0px)); left: 50%; transform: translateX(-50%); color: rgba(255, 255, 255, .8); font-size: 13px; background: rgba(0, 0, 0, .5); border-radius: 999px; padding: 3px 12px; }

/* ---- 全屏编辑 ---- */
.m-edit {
  position: fixed; inset: 0; z-index: 1250; background: var(--app-bg);
  display: flex; flex-direction: column;
}
.m-edit-bar {
  flex: none; display: flex; align-items: center; gap: 8px;
  padding: 6px 8px; padding-top: calc(6px + var(--sat, 0px));
  background: var(--app-panel); border-bottom: 1px solid var(--app-border);
}
.m-edit-title { flex: 1; font-size: 13px; color: var(--app-text3); text-align: center; }
.m-edit-ok {
  min-height: 38px; padding: 0 14px; border: none; border-radius: 10px;
  background: var(--app-accent); color: #fff; font-size: 14px; touch-action: manipulation;
}
.m-edit-ok:disabled { background: var(--app-accent-disabled); }
.m-edit-ta {
  flex: 1; margin: 12px; padding: 12px; box-sizing: border-box;
  border: 1px solid var(--app-border); border-radius: 12px; background: var(--app-panel);
  color: var(--app-text); font-size: 16px; line-height: 1.7; outline: none; resize: none; font-family: inherit;
}

/* ---- 反馈 ---- */
.m-fb-ta {
  width: 100%; box-sizing: border-box; border: 1px solid var(--app-border); border-radius: 10px;
  background: var(--app-panel-2); color: var(--app-text); font-size: 16px; padding: 10px; outline: none; resize: none; font-family: inherit;
}
.m-fb-ok { width: 100%; margin-top: 10px; min-height: 44px; border: none; border-radius: 12px; background: var(--app-accent); color: #fff; font-size: 15px; touch-action: manipulation; }
.m-fb-ok:disabled { background: var(--app-accent-disabled); }
</style>
