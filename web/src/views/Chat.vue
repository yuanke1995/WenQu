<template>
  <div class="chat-layout">
    <!-- 左侧会话列表 -->
    <SessionSidebar
      ref="sidebarRef"
      :sessions="visibleSessions"
      :current-session-id="currentSessionId"
      :collapsed="sidebarCollapsed"
      :loading="sessionsLoading"
      :creating="creatingSession"
      :width="sidebarWidth"
      :dragging="sidebarDragging"
      @select="switchSession"
      @delete="handleDeleteSession"
      @batch-delete="handleBatchDeleteSessions"
      @clear="handleClearAll"
      @new="createNewSession"
      @toggle-collapse="toggleSidebar"
      @search="onSearchSessions"
      @filter-change="onFilterChange"
      @toggle-pin="handleTogglePin"
      @toggle-favorite="handleToggleFavorite"
      @rename="openRenameModal"
      @export="handleExportSession"
    />
    <!-- 侧边栏拖拽手柄（左右伸缩） -->
    <div class="sidebar-resizer" title="拖动调整宽度" @mousedown="onSidebarDrag" />

    <!-- 右侧聊天区域 -->
    <div class="chat">
      <div class="chat-box">
        <div class="head">
          <span class="head-title">{{ currentSessionTitle }}</span>
          <span class="disclaimer" title="查看免责声明" @click="disclaimerVisible = true"><info-circle-outlined style="margin-right:4px" />AI 回答可能有误，重要信息请核实</span>
        </div>

        <div class="messages" ref="box" @click="openPreview" @scroll="onMessagesScroll">
          <div v-if="messages.length === 0" class="welcome">
            <robot-outlined style="font-size:48px;color:#1677ff" />
            <h2>你好！我是小报 👋</h2>
            <p>基于操作手册知识库回答你关于系统使用的问题</p>
            <div>
              <a-tag v-for="(q,i) in tips" :key="i" color="blue" style="cursor:pointer;margin:4px" @click="ask(q)">{{ q }}</a-tag>
            </div>
          </div>
          <div v-for="(m,i) in messages" :key="i" class="row" :class="m.role">
            <!-- 多选删除模式：勾选框（勾选按轮联动，勾回答自动带上同组问题） -->
            <a-checkbox v-if="selectMode" class="msg-select-box"
                        :checked="selected.includes(roundStart(i))"
                        @click.stop @change="toggleSelect(i)" />
            <div class="msg-block" :class="m.role">
              <div class="bubble" :class="m.role">
              <!-- 用户上传图片（本地 dataUrl 预览 / 历史 URL） -->
                <div v-if="m.role === 'user' && m.images && m.images.length" class="msg-imgs">
                  <img v-for="(u, ui) in m.images" :key="ui" :src="resolveImg(u)"
                       class="msg-img" :alt="'上传图片' + (ui + 1)" @click="openPreviewFromMsg(m, ui)" @error="onImgError" />
                </div>
                <!-- 深度思考折叠面板（AI 消息有 thinking 时显示，可展开/收起） -->
                <div v-if="m.role === 'ai' && m.thinking" class="think-panel" :class="{ open: m.thinkOpen }">
                  <div class="think-head" @click="m.thinkOpen = !m.thinkOpen">
                    <down-outlined class="think-arrow" />
                    <span class="think-title">深度思考</span>
                    <a-spin v-if="m.thinkLoading" size="small" style="margin-left:6px" />
                    <span v-else class="think-badge">已完成</span>
                  </div>
                  <div v-show="m.thinkOpen" class="think-body" :ref="el => thinkingBodyRefs[i] = el" @click.stop>
                    <div class="md" v-html="renderMd(m.thinking, [])"></div>
                  </div>
                </div>
                <div class="md" :data-msg-index="i" v-html="renderMd(m.content, m.images)"></div>
                <!-- 检索进度提示：首 token 前显示后端 stage 事件（自带加载图标，与下方 spinner 互斥） -->
                <div v-if="m.loading && m.stage && !m.content" class="stage-hint">
                  <loading-outlined /> {{ m.stage }}
                </div>
                <!-- 内容生成中：仅当已有正文在流出时显示（文字增长本身就是进度，避免与阶段提示双重转圈） -->
                <a-spin v-if="m.loading && m.content" size="small" style="margin-top:4px" />
                <!-- fail-loud：本轮回答的降级事件警示（检索失败/改写失败/重排不可用/上下文截断等） -->
                <div v-if="m.role === 'ai' && m.degradations && m.degradations.length" class="degradation-bar">
                  <exclamation-circle-outlined style="margin-right:6px" />
                  <span v-for="(d, di) in m.degradations" :key="di" class="degradation-item">{{ d.msg }}</span>
                </div>
                <!-- SSE warn 事件（如回答超时截断） -->
                <div v-if="m.role === 'ai' && m.warnMsg" class="degradation-bar">{{ m.warnMsg }}</div>
                <!-- 检索状态行（回答下方合并区）：概览行展开=检索词+来源条目；条目点击弹原文（平替原引用 chips，与正文 [N] 角标同源） -->
                <div v-if="m.role === 'ai' && (m.retrieved || (m.sources && m.sources.length))" class="retrieval-merged">
                  <div class="retrieval-line" @click="m.rtOpen = !m.rtOpen">
                    <template v-if="m.retrieved">搜索 {{ m.retrieved.keywords }} 个关键词<template v-if="!m.loading">，参考 {{ m.retrieved.refs }} 段资料</template></template>
                    <template v-else>参考 {{ (m.sources || []).length }} 段资料</template>
                    <down-outlined class="rt-arrow" :class="{ open: m.rtOpen }" />
                  </div>
                  <div v-if="m.rtOpen" class="retrieval-detail">
                    <div v-if="m.retrieved?.terms?.length" class="rt-terms">检索词：{{ (m.retrieved.terms || []).join('、') }}</div>
                    <div v-for="(s, si) in (m.sources || [])" :key="si" class="rt-ref" title="点击查看原文" @click="openSource(s)">
                      <span class="rt-ref-tag">[{{ s.ref }}]</span>{{ (s.fileName || '未知文档') + (s.title ? ' §' + s.title : '') }}
                      <div v-if="s.snippet" class="rt-snip">{{ s.snippet }}</div>
                    </div>
                  </div>
                </div>
                <!-- 相关推荐问题 -->
                <div v-if="m.role === 'ai' && m.related && m.related.length" class="related">
                  <span class="related-label">猜你想问：</span>
                  <a-tag v-for="(q, qi) in m.related" :key="qi" color="green"
                         style="cursor:pointer;margin:2px" @click="ask(q)">{{ q }}</a-tag>
                </div>
              </div>
              <!-- ===== 操作区（气泡外部：图标一行排开 + 不常用收进「更多」 + 行尾时间） ===== -->
              <!-- 流式中断/失败：重试入口（保留已生成内容，重新生成完整回答） -->
              <div v-if="m.role === 'ai' && m.failed && !m.loading" class="retry-row">
                <a-button size="small" type="primary" ghost :disabled="loading" @click="regenerate(i)">
                  <sync-outlined /> 重试
                </a-button>
              </div>
              <!-- 回答操作（纯图标 + tooltip）：复制 / 有帮助 / 没帮助 / 重新生成 / 更多（检索调试、导出）+ 行尾时间 -->
              <div v-if="!selectMode && m.role === 'ai' && !m.loading && (m.messageId || m.time)" class="fb-row">
                <template v-if="m.messageId">
                  <a-tooltip title="复制" placement="top">
                    <button class="act-icon-btn" @click="copyAnswer(i)"><copy-outlined /></button>
                  </a-tooltip>
                  <a-tooltip :title="m.fb != null ? '已评价' : '有帮助'" placement="top">
                    <button class="act-icon-btn" :class="{ 'fb-active': m.fb === 1 }"
                            :disabled="m.fb != null" @click="openFeedback(m, 1)"><like-outlined /></button>
                  </a-tooltip>
                  <a-tooltip :title="m.fb != null ? '已评价' : '没帮助'" placement="top">
                    <button class="act-icon-btn" :class="{ 'fb-active': m.fb === 0 }"
                            :disabled="m.fb != null" @click="openFeedback(m, 0)"><dislike-outlined /></button>
                  </a-tooltip>
                  <a-tooltip title="重新生成" placement="top">
                    <button class="act-icon-btn" :disabled="loading" @click="regenerate(i)"><reload-outlined /></button>
                  </a-tooltip>
                  <a-dropdown :trigger="['hover']">
                    <button class="act-icon-btn" title="更多"><more-outlined /></button>
                    <template #overlay>
                      <a-menu @click="({ key }) => onMoreAction(key, i)">
                        <a-menu-item v-if="debugEntryVisible" key="debug"><bug-outlined style="margin-right:8px" />检索调试</a-menu-item>
                        <a-menu-item key="export"><download-outlined style="margin-right:8px" />导出 Markdown</a-menu-item>
                        <a-menu-divider />
                        <a-menu-item key="deleteRound" style="color:#cf1322"><delete-outlined style="margin-right:8px" />删除对话</a-menu-item>
                      </a-menu>
                    </template>
                  </a-dropdown>
                </template>
                <span v-if="m.time" class="msg-time-inline">{{ fmtMsgTime(m.time) }}</span>
              </div>
              <!-- 断连自动重试中：内联提示（位于 AI 回答气泡整体下方，替代全局弹窗） -->
              <div v-if="m.retrying" class="retry-tip">
                <a-spin size="small" />
                <span>连接中断，正在自动重试…</span>
              </div>
              <!-- 用户消息编辑：悬浮在问题气泡整体下方（hover 显示） -->
              <div v-if="!selectMode && m.role === 'user'" class="msg-edit-row">
                <a-tooltip title="编辑此问题重新发送" placement="top">
                  <edit-outlined class="act-icon-btn" @click="editMessage(i)" />
                </a-tooltip>
                <a-tooltip title="删除本轮对话（含回答）" placement="top">
                  <delete-outlined class="act-icon-btn act-danger" @click="enterSelectMode(i)" />
                </a-tooltip>
                <span v-if="m.time" class="msg-time-inline">{{ fmtMsgTime(m.time) }}</span>
              </div>
            </div>
          </div>
          <!-- 生成中用户上翻回看历史时自动滚动暂停，此按钮一键回到底部继续跟随 -->
          <div v-if="!stickToBottom && messages.length" class="jump-latest" @click.stop="scrollForce">↓ 回到底部</div>
        </div>

        <!-- 引用来源详情弹窗（灯箱打开时禁用 ESC 关闭：ESC 优先关灯箱；宽度按内容自适应 + 右下角可拖拽伸缩） -->
        <a-modal v-model:open="sourceVisible" :title="sourceTitle" :footer="null"
                 :width="sourceImages.length ? 720 : 560"
                 wrap-class-name="source-modal"
                 :keyboard="!previewUrl" :mask-closable="!previewUrl">
          <a-spin v-if="sourceLoading" style="display:block;margin:40px auto" />
          <template v-else>
            <!-- 知识块全文（溯源；内嵌图片点击可灯箱放大并左右切换；代码块可复制） -->
            <div class="md src-content" @click="openPreview"
                 v-html="renderMd(prepKnowledgeContent(sourceContent || sourceSnippet, sourceImages), sourceImages)"></div>
          </template>
          <!-- 右下角拖拽伸缩手柄（JS 实现，antd modal teleport 场景 CSS resize 不可靠） -->
          <div class="src-resizer" title="拖拽调整大小" @mousedown="onSrcResizeStart" />
        </a-modal>

        <!-- 回答反馈弹窗 -->
        <a-modal v-model:open="feedbackVisible" title="反馈" :footer="null" width="440">
          <a-textarea v-model:value="feedbackText" placeholder="可选：告诉我们哪里不满意（如回答不准确、图片不对等）" :rows="3" />
          <a-button type="primary" style="margin-top:12px" :loading="feedbackSubmitting" @click="submitFeedback">
            提交反馈
          </a-button>
        </a-modal>

        <!-- 免责声明（点击头部提示打开） -->
        <a-modal v-model:open="disclaimerVisible" title="免责声明" :footer="null" width="560">
          <div class="md disclaimer-body" v-html="renderMd(DISCLAIMER_TEXT, [])"></div>
        </a-modal>

        <!-- 会话重命名：条目旁悬浮小卡片（fixed 定位在会话条目右侧；回车保存，ESC/点外部/滚动即关） -->
        <div v-if="renameVisible" class="rename-pop" :style="renameStyle" @mousedown.stop>
          <div class="rename-pop-title">重命名会话</div>
          <a-textarea ref="renameInputRef" v-model:value="renameValue" :maxlength="50" show-count
                      placeholder="输入新的会话标题" size="small"
                      :auto-size="{ minRows: 2, maxRows: 4 }"
                      @keydown.enter.exact.prevent="doRenameSession"
                      @keydown.esc="closeRenamePop" />
          <div class="rename-pop-actions">
            <a-button size="small" @click="closeRenamePop">取消</a-button>
            <a-button size="small" type="primary" :loading="renamingSession" @click="doRenameSession">保存</a-button>
          </div>
        </div>

        <!-- 检索调试弹窗：分步展示检索过程（为什么这么答） -->
        <a-modal v-model:open="debugVisible" title="🔍 检索调试（为什么这么答）" :footer="null" width="780">
          <div style="display:flex;gap:8px;margin-bottom:12px">
            <a-input v-model:value="debugQuestion" placeholder="输入要调试的问题" @pressEnter="runDebug" />
            <a-button type="primary" :loading="debugLoading" @click="runDebug">调试</a-button>
          </div>
          <a-spin :spinning="debugLoading">
            <template v-if="debugResult">
              <!-- 检索词元：分词结果（主词元+子词元），便于验证分词/召回 -->
              <div v-if="debugResult.keywordTerms?.length" class="dbg-terms">
                <span class="dbg-terms-label">检索词元</span>
                <a-tag v-for="(t, ti) in debugResult.keywordTerms" :key="ti" color="blue" style="margin:2px">{{ t }}</a-tag>
              </div>
              <a-collapse :bordered="false" :default-active-key="['final']">
                <a-collapse-panel v-for="(s, si) in debugStages" :key="si" :name="si === 4 ? 'final' : String(si)"
                                  :header="s.name">
                  <div v-if="s.items.length" class="dbg-item" v-for="(it, ii) in s.items" :key="ii">
                    <div class="dbg-head">
                      <span class="dbg-title">{{ it.title }}</span>
                      <a-tag v-if="it.docName" size="small">{{ it.docName }}</a-tag>
                      <a-tag v-if="it.titleHit" color="green" size="small">标题命中</a-tag>
                      <a-tag color="blue" size="small">{{ it.tag }}</a-tag>
                    </div>
                    <div class="dbg-snippet">{{ it.snippet }}</div>
                  </div>
                  <a-empty v-else description="无命中" />
                </a-collapse-panel>
              </a-collapse>
            </template>
          </a-spin>
        </a-modal>

        <!-- 图片点击放大灯箱：多图左右切换、滚轮缩放、拖动平移、双击重置、ESC 关闭 -->
        <div v-if="previewUrl" class="lightbox" @click="close" @wheel.prevent="onWheel">
          <img
            :src="previewUrl" alt="大图预览" @click.stop @error="onImgError"
            :class="{ dragging: !!dragState }"
            :style="{ transform: 'translate(' + offset.x + 'px,' + offset.y + 'px) scale(' + zoom + ')' }"
            @mousedown="onImgMouseDown" @mousemove="onImgMouseMove" @mouseup="onImgMouseUp" @mouseleave="onImgMouseUp"
            @dblclick="onImgDblClick"
          />
          <!-- 上一张/下一张（多图时显示，到头禁用） -->
          <button v-if="previewList.length > 1" class="lightbox-prev" :disabled="previewIndex === 0"
                  @click.stop="prev" aria-label="上一张">‹</button>
          <button v-if="previewList.length > 1" class="lightbox-next" :disabled="previewIndex === previewList.length - 1"
                  @click.stop="next" aria-label="下一张">›</button>
          <span v-if="previewList.length > 1" class="lightbox-count">{{ previewIndex + 1 }} / {{ previewList.length }}</span>
          <span class="lightbox-close" @click.stop="close">×</span>
          <span v-if="zoom !== 1" class="lightbox-hint">{{ Math.round(zoom * 100) }}%</span>
          <span class="lightbox-tip">滚轮缩放 · 拖动平移 · 双击重置 · ESC 关闭<span v-if="previewList.length > 1"> · ← → 切换</span></span>
        </div>

        <!-- 多选删除底栏（替换输入框区，内容居中） -->
        <div v-if="selectMode" class="select-bar">
          <a-button size="small" @click="exitSelectMode">取消</a-button>
          <span class="select-count">已选 {{ selected.length }} 轮对话</span>
          <a-button size="small" danger type="primary" :disabled="!selected.length" :loading="deletingRounds" @click="deleteSelected">
            删除
          </a-button>
        </div>
        <div v-if="!selectMode" class="input" @dragenter.prevent="onDragEnter" @dragover.prevent
             @dragleave.prevent="onDragLeave" @drop.prevent="onDropImages">
          <!-- 拖入图片提示遮罩 -->
          <div v-if="dragOver" class="drop-overlay">松开以添加图片</div>
          <!-- 待发送图片预览（点击可放大查看） -->
          <div v-if="pendingImages.length" class="pending-imgs">
            <div v-for="(p, pi) in pendingImages" :key="pi" class="pending-img">
              <img :src="p.dataUrl" alt="待发送图片" @click="previewPendingImage(pi)" />
              <span class="pending-del" @click.stop="removePendingImage(pi)">×</span>
            </div>
          </div>
          <!-- 输入卡片：文本在上，工具行在下（图片/深度思考靠左，发送/停止靠右） -->
          <div class="input-box">
            <a-textarea
              ref="textareaRef"
              v-model:value="text"
              placeholder="请输入问题，Enter 发送，Shift+Enter 换行"
              :disabled="loading || selectMode"
              :auto-size="{ minRows: 1, maxRows: 6 }"
              class="input-area"
              @keydown.enter.exact.prevent="onEnterKey"
            />
            <div class="input-toolbar">
              <div class="toolbar-left">
                <a-tooltip title="上传图片（最多 5 张，随问题一起发送）">
                  <button class="act-icon-btn toolbar-btn" @click="pickImages"><picture-outlined /></button>
                </a-tooltip>
                <a-tooltip :title="deepThinkOn ? '深度思考：已开启（点击关闭）' : '深度思考：已关闭（点击开启，AI 先展示思维链再回答）'">
                  <button class="act-icon-btn toolbar-btn" :class="{ 'toolbar-btn-on': deepThinkOn }" @click="toggleDeepThink"><bulb-outlined /></button>
                </a-tooltip>
              </div>
              <button v-if="loading" class="send-btn stop" title="停止生成" @click="stop"><pause-circle-outlined /></button>
              <button v-else class="send-btn" title="发送" :disabled="!canSend" @click="send"><arrow-up-outlined /></button>
            </div>
          </div>
          <input ref="fileInput" type="file" accept="image/*" multiple style="display:none" @change="onFilesChange" />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, watch, h } from 'vue'
import { message, notification } from 'ant-design-vue'
import { RobotOutlined, SendOutlined, PauseCircleOutlined, LikeOutlined, DislikeOutlined, PictureOutlined, ReloadOutlined, EditOutlined, BugOutlined, SyncOutlined, CopyOutlined, DownloadOutlined, InfoCircleOutlined, QuestionCircleOutlined, DownOutlined, BulbOutlined, LoadingOutlined, MoreOutlined, DeleteOutlined, ArrowUpOutlined } from '@ant-design/icons-vue'
import { sendQuestion, newSession, getHistory, listSessions, deleteSessionApi, batchDeleteSessionsApi, pinSession, favoriteSession, renameSessionApi, submitFeedback as apiSubmitFeedback, getKnowledgeDetail, clearAllSessionsApi, debugRetrieval, getSuggested, deleteMessageGroup, undoDeleteMessageGroup, getConfig } from '../api'
import SessionSidebar from '../components/SessionSidebar.vue'
import { renderMd, resolveImg, onImgError, copyCode, prepKnowledgeContent } from '../utils/markdown'

const text = ref('')
const textareaRef = ref(null)
// 深度思考开关（localStorage 记忆偏好，默认关）
const deepThinkOn = ref(localStorage.getItem('ai_deep_think') === '1')
const canSend = computed(() => !!(text.value.trim() || pendingImages.value.length))
// 思考面板 body 引用（思考中自动滚动到底部，跟随最新内容）
const thinkingBodyRefs = []
// 开关变更持久化（script 中访问浏览器全局 localStorage，模板内联表达式会被编译为 _ctx 属性导致 undefined）
const onDeepThinkChange = checked => {
  localStorage.setItem('ai_deep_think', checked ? '1' : '0')
}
// 图标按钮点击切换（思考中禁用）
const toggleDeepThink = () => {
  if (loading.value) return
  deepThinkOn.value = !deepThinkOn.value
  localStorage.setItem('ai_deep_think', deepThinkOn.value ? '1' : '0')
}

// 检索调试状态
const debugVisible = ref(false)
const debugLoading = ref(false)
const debugQuestion = ref('')
const debugResult = ref(null)
const loading = ref(false)
const sessionsLoading = ref(false)
const currentSessionId = ref(null)
const sessions = ref([])
// 顶部标题：当前会话标题（列表里查不到时回退"新对话"）
const currentSessionTitle = computed(() => {
  const s = sessions.value.find(x => x.id === currentSessionId.value)
  return s?.title || '新对话'
})
const messages = ref([])
const box = ref(null)
// 自动滚动跟随：生成中用户上翻即暂停（回看历史不被拽走），回到底部附近自动恢复
const stickToBottom = ref(true)
const AUTO_SCROLL_MARGIN = 80
const onMessagesScroll = () => {
  const el = box.value
  if (!el) return
  stickToBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < AUTO_SCROLL_MARGIN
}
// 灯箱多图状态：previewList（resolveImg 后 URL）+ previewIndex；previewUrl 为 computed
const previewList = ref([])
const previewIndex = ref(0)
const previewUrl = computed(() => previewList.value[previewIndex.value] || '')
const zoom = ref(1)
const offset = ref({ x: 0, y: 0 })
const dragState = ref(null)
const abortController = ref(null)

// 侧边栏宽度驱动：可拖拽伸缩，缩到阈值(<150px)自动切换为图标条（48px）
const SIDEBAR_ICON_W = 48
const SIDEBAR_ICON_THRESHOLD = 150
const SIDEBAR_MAX = 420
const sidebarWidth = ref(parseInt(localStorage.getItem('ai_sidebar_width')) || 260)
const sidebarCollapsed = computed(() => sidebarWidth.value < SIDEBAR_ICON_THRESHOLD)
function toggleSidebar() {
  sidebarWidth.value = sidebarWidth.value < SIDEBAR_ICON_THRESHOLD ? 260 : SIDEBAR_ICON_W
  localStorage.setItem('ai_sidebar_width', String(sidebarWidth.value))
}
// 拖拽右侧边缘调整宽度（拖拽中禁用宽度过渡动画，保证跟手；松开设 transition 平滑折叠）
const sidebarDragging = ref(false)
function onSidebarDrag(e) {
  e.preventDefault()
  sidebarDragging.value = true
  const startX = e.clientX
  const startW = sidebarWidth.value
  const onMove = ev => {
    const w = Math.max(SIDEBAR_ICON_W, Math.min(SIDEBAR_MAX, startW + (ev.clientX - startX)))
    sidebarWidth.value = w
  }
  const onUp = () => {
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
    sidebarDragging.value = false
    localStorage.setItem('ai_sidebar_width', String(sidebarWidth.value))
  }
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}

// 检索调试入口开关（内部排障用，chat.retrievalDebugEnabled，默认隐藏）
const debugEntryVisible = ref(false)

// 推荐问题池：DB 配置（chat.suggestedQuestions，设置页/看板可管理）；加载失败回退内置默认
const FALLBACK_TIPS = ['系统有哪些功能？', '如何创建一个新表单？', '字段验证怎么设置？', '什么是填报周期？']
const tips = ref(FALLBACK_TIPS)

// 文档图片访问：data URL（用户上传预览）原样返回；http 原样；其余走 /proxy
// 点击回答内容：复制按钮 / 引用角标 [N] → 来源详情；图片 → 灯箱预览（事件委托）
const openPreview = e => {
  const t = e.target
  // 复制按钮：target 可能是按钮内 SVG/span，需 closest 向上查找
  const copyBtn = t && t.closest ? t.closest('.code-copy') : null
  if (copyBtn) {
    copyCode(copyBtn)
    return
  }
  if (t && t.classList && t.classList.contains('ref-sup')) {
    const mdEl = t.closest('.md')
    const msgIdx = mdEl ? Number(mdEl.dataset.msgIndex) : -1
    const ref = Number(t.dataset.ref)
    const src = messages.value[msgIdx]?.sources?.[ref - 1]
    if (src) openSource(src)
    return
  }
  if (t && t.tagName && t.tagName.toLowerCase() === 'img') {
    // 图片点击：优先消息内 data-seq 定位；引用弹窗全文内（无 .md 上下文）用当前来源图片作切换列表
    const mdEl = t.closest('.md')
    const msgIdx = mdEl ? Number(mdEl.dataset.msgIndex) : -1
    const seq = Number(t.dataset.seq || 0)
    let imgs = messages.value[msgIdx]?.images
    if ((!Array.isArray(imgs) || !imgs.length) && sourceVisible.value && sourceImages.value.length) {
      imgs = sourceImages.value
    }
    if (Array.isArray(imgs) && imgs.length) {
      previewList.value = imgs.map(resolveImg)
      previewIndex.value = seq > 0 && seq <= imgs.length ? seq - 1 : 0
    } else {
      previewList.value = [t.getAttribute('src')]
      previewIndex.value = 0
    }
    zoom.value = 1
    offset.value = { x: 0, y: 0 }
  }
}

// 免责声明弹窗（点击头部提示打开）
const disclaimerVisible = ref(false)
const DISCLAIMER_TEXT = `

### 一、内容生成方式

本系统的回答由人工智能模型基于知识库检索结果**自动生成**，仅供学习与工作参考，不构成任何形式的专业建议（包括但不限于法律、医疗、财务、投资建议），亦不代表系统开发方与运营方的官方立场。

### 二、准确性不作保证

AI 生成内容可能存在**错误、遗漏、过时或与实际情况不符**之处。知识库内容可能更新滞后，回答引用的资料版本可能与最新版本存在差异。重要信息（如操作规范、数据口径、流程要求、审批条件等）请以**官方文档、正式通知或相关业务部门的确认为准**。

### 三、引用来源说明

回答中标注的引用来源仅用于帮助定位参考资料。受文档分块与摘要机制影响，展示的片段可能与原文存在出入，完整含义请以知识块原文及原始文档为准。

### 四、使用限制

请勿仅依赖本系统的回答做出对个人或组织有重大影响的决策。因使用或依赖本系统内容而产生的任何直接或间接损失，系统提供方不承担责任。请遵守信息安全相关规定，**不要在提问中输入密码、密钥、客户隐私等敏感信息**。

### 五、反馈与改进

如发现回答有误或内容不当，可通过回答下方的反馈按钮告知我们，帮助我们持续改进。`

// 引用来源详情弹窗
const sourceVisible = ref(false)
const sourceTitle = ref('')
const sourceSnippet = ref('')
const sourceImages = ref([])
const sourceContent = ref('')      // 知识块全文（异步加载）
const sourceLoading = ref(false)
const openSource = async s => {
  if (!s) return
  sourceTitle.value = (s.fileName || '未知文档') + (s.title ? ' §' + s.title : '')
  sourceSnippet.value = s.snippet || '（无原文片段）'
  sourceImages.value = Array.isArray(s.images) ? s.images : [] // 旧消息 sources 无 images，兼容为空
  sourceContent.value = ''
  sourceLoading.value = true
  sourceVisible.value = true
  // 重置上次拖拽残留的高度：antd modal 关闭后 DOM 保留（display:none），
  // width 会被 props 重写，但 JS 设置的 style.height 没有 prop 对应会残留，
  // 不重置会导致再次打开时弹窗保持上次拉高后的尺寸
  nextTick(() => {
    const modal = document.querySelector('.source-modal .ant-modal')
    if (modal) modal.style.height = ''
  })
  try {
    const r = await getKnowledgeDetail(s.knowledgeId)
    if (r.success && r.data) {
      sourceContent.value = r.data.content || ''
      if (Array.isArray(r.data.images)) sourceImages.value = r.data.images
      if (r.data.title) sourceTitle.value = (s.fileName || '未知文档') + ' §' + r.data.title
    }
  } catch (e) { /* 接口失败：回退显示 snippet */ }
  finally { sourceLoading.value = false }
}

// 引用弹窗右下角拖拽伸缩（JS 实现：mousedown 记录起点，mousemove 更新 modal 宽高）
// 拖拽不误关的原理：
// 1. mousedown 必须 stopPropagation——阻止冒泡到 content 触发 antd 的 onContentMouseDown，
//    否则 contentClickRef 被置 true；若拖拽结束时 mouseup 落在 modal 外（居中对称扩展时
//    鼠标易跑出 modal），onContentMouseUp 不触发，该标记残留 true，下次打开弹窗点遮罩会被
//    antd 误判为「内容内点击」导致遮罩关闭失效。
// 2. 拖拽结束瞬间（mouseup 后同步派发）的 click 目标常是 wrap（鼠标跑出 modal 落到遮罩上），
//    不拦截会被 antd 的 onWrapperClick 当作「点遮罩关闭」→ 弹窗被误关。用 capture 阶段
//    拦截该 click（srcResizing 标志 + setTimeout 延迟移除 guard，恰好覆盖 click 派发）。
let srcResizing = false
const onSrcResizeStart = e => {
  e.preventDefault()
  e.stopPropagation()
  srcResizing = true
  const modal = document.querySelector('.source-modal .ant-modal')
  if (!modal) return
  const startX = e.clientX, startY = e.clientY
  const startW = modal.offsetWidth, startH = modal.offsetHeight
  const onMove = ev => {
    modal.style.width = Math.max(420, startW + ev.clientX - startX) + 'px'
    modal.style.height = Math.max(240, startH + ev.clientY - startY) + 'px'
  }
  const onClickGuard = e => {
    if (srcResizing) e.stopPropagation() // 吞掉拖拽结束的 click，阻止 antd 误关
  }
  const onUp = () => {
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
    document.body.style.userSelect = ''
    // click 在 mouseup 之后同步派发，setTimeout(0) 确保 click 先经过 guard 再移除
    setTimeout(() => {
      window.removeEventListener('click', onClickGuard, true)
      srcResizing = false
    }, 0)
  }
  document.body.style.userSelect = 'none' // 拖拽中防选中文本
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
  window.addEventListener('click', onClickGuard, true)
}
// 灯箱切换：重置缩放/平移，到头禁用（不循环）
const resetView = () => { zoom.value = 1; offset.value = { x: 0, y: 0 } }
const prev = () => { if (previewIndex.value > 0) { previewIndex.value--; resetView() } }
const next = () => { if (previewIndex.value < previewList.value.length - 1) { previewIndex.value++; resetView() } }

// 回答反馈（👍👎 + 可选文本；单选：同一回答只能选一项，可重复点当前项补充说明）
const feedbackVisible = ref(false)
const feedbackSubmitting = ref(false)
const feedbackText = ref('')
const feedbackTarget = ref(null) // { msg, rating }
const openFeedback = (m, rating) => {
  if (m.fb != null && m.fb !== rating) {
    message.warning(`已评价为「${m.fb === 1 ? '有帮助' : '没帮助'}」，同一回答只能选择一项`)
    return
  }
  feedbackTarget.value = { msg: m, rating }
  feedbackText.value = ''
  feedbackVisible.value = true
}
const submitFeedback = async () => {
  const t = feedbackTarget.value
  if (!t || !t.msg.messageId) { message.warning('该回答不可反馈'); return }
  feedbackSubmitting.value = true
  try {
    const r = await apiSubmitFeedback(t.msg.messageId, t.rating, feedbackText.value.trim())
    if (r.success) {
      t.msg.fb = t.rating
      message.success('感谢反馈')
      feedbackVisible.value = false
    } else message.error(r.msg || '提交失败')
  } catch (e) { message.error(e.message || '提交失败') }
  finally { feedbackSubmitting.value = false }
}

// 灯箱：关闭（重置缩放与平移）
const close = () => {
  previewList.value = []
  previewIndex.value = 0
  zoom.value = 1
  offset.value = { x: 0, y: 0 }
  dragState.value = null
}

// 灯箱：滚轮缩放（25% ~ 800%）。按滚轮幅度平滑缩放：每 100 单位滚轮量 = 8% 倍率变化（等比，
// 正反手感对称）；单次事件 clamp ±30%，避免触控板惯性/快速滚动导致跳变过大
const onWheel = e => {
  let factor = Math.pow(1.08, -e.deltaY / 100)
  if (factor > 1.3) factor = 1.3
  if (factor < 1 / 1.3) factor = 1 / 1.3
  zoom.value = Math.min(8, Math.max(0.25, zoom.value * factor))
}

// 灯箱：拖动平移（放大后查看超出窗口的边缘）
const onImgMouseDown = e => {
  if (e.button !== 0) return
  dragState.value = { startX: e.clientX, startY: e.clientY, ox: offset.value.x, oy: offset.value.y }
  e.preventDefault()
}
const onImgMouseMove = e => {
  if (!dragState.value) return
  offset.value.x = dragState.value.ox + (e.clientX - dragState.value.startX)
  offset.value.y = dragState.value.oy + (e.clientY - dragState.value.startY)
}
const onImgMouseUp = () => { dragState.value = null }

// 双击重置视图
const onImgDblClick = () => {
  zoom.value = 1
  offset.value = { x: 0, y: 0 }
}

// ESC 关闭灯箱，← → 切换图片
const onKeydown = e => {
  if (e.key === 'Escape') close()
  else if (e.key === 'ArrowLeft') prev()
  else if (e.key === 'ArrowRight') next()
}
watch(previewUrl, v => {
  if (v) window.addEventListener('keydown', onKeydown)
  else window.removeEventListener('keydown', onKeydown)
})
onUnmounted(() => window.removeEventListener('keydown', onKeydown))

// 全局快捷键：Esc 停止生成（未生成则清空输入框）、Cmd/Ctrl+N 新建会话、Cmd/Ctrl+K 聚焦会话搜索；
// 输入法组合键（isComposing）不拦截；灯箱打开时 ESC/方向键交由灯箱处理
const onGlobalKeydown = e => {
  if (e.isComposing || e.keyCode === 229) return
  if (previewUrl.value && ['Escape', 'ArrowLeft', 'ArrowRight'].includes(e.key)) return
  const mod = e.metaKey || e.ctrlKey
  const k = e.key.toLowerCase()
  if (mod && k === 'n') { e.preventDefault(); createNewSession(); return }
  if (mod && k === 'k') { e.preventDefault(); focusSearch(); return }
  if (e.key === 'Escape') {
    if (renameVisible.value) { closeRenamePop(); return }
    if (loading.value) { stop(); return }
    if (document.activeElement === textareaRef.value) {
      if (text.value) text.value = ''
      else textareaRef.value?.blur()
    }
  }
}
// 粘贴发图（微信截图等 Ctrl+V；纯文本粘贴不拦截）
const onGlobalPaste = e => onPasteImages(e)

// 窄窗口（<640px）自动折叠侧边栏：保证浏览器窗口可以任意缩小，不出现横向溢出
const handleWindowResize = () => {
  if (window.innerWidth < 640 && sidebarWidth.value >= SIDEBAR_ICON_THRESHOLD) {
    sidebarWidth.value = SIDEBAR_ICON_W
    localStorage.setItem('ai_sidebar_width', String(sidebarWidth.value))
  }
}
window.addEventListener('resize', handleWindowResize)
onUnmounted(() => {
  window.removeEventListener('resize', handleWindowResize)
  window.removeEventListener('keydown', onGlobalKeydown)
  window.removeEventListener('paste', onGlobalPaste)
  // 重命名浮层监听（watch 卸载时不触发 cleanup，手动移除）
  if (renameCleanup) {
    const { onDocDown, onScroll, onResize } = renameCleanup
    document.removeEventListener('mousedown', onDocDown)
    document.removeEventListener('scroll', onScroll, true)
    window.removeEventListener('resize', onResize)
    renameCleanup = null
  }
})

// 初始化：加载会话列表 → 选择最近会话或新建
onMounted(async () => {

  await loadSessions()
  if (sessions.value.length > 0) {
    await switchSession(sessions.value[0].id)
  } else {
    await createNewSession()
  }
  focusInput()
  // 全局快捷键与粘贴发图（组件卸载时移除）
  window.addEventListener('keydown', onGlobalKeydown)
  window.addEventListener('paste', onGlobalPaste)
  // 推荐问题池：DB 配置优先（失败静默回退内置默认）
  getSuggested().then(r => {
    if (r.success && Array.isArray(r.data) && r.data.length) tips.value = r.data
  }).catch(() => {})
  // 检索调试入口开关（保存即生效，进页面时读取）
  getConfig().then(r => {
    if (r.success) debugEntryVisible.value = r.data?.chat?.retrievalDebugEnabled?.value === 'true'
  }).catch(() => {})
})

// 加载会话列表（支持关键词搜索）
const searchKw = ref('')
async function loadSessions(keyword) {
  sessionsLoading.value = true
  try {
    const r = await listSessions(keyword ?? searchKw.value)
    if (r.success && Array.isArray(r.data)) {
      sessions.value = r.data
    }
  } catch (e) {
    message.error('加载会话列表失败: ' + (e.message || '未知错误'))
  } finally {
    sessionsLoading.value = false
  }
}
// 侧边栏搜索输入
const onSearchSessions = kw => {
  searchKw.value = kw
  loadSessions(kw)
}
// 收藏筛选（本地过滤）
const favFilter = ref('all')
const onFilterChange = f => { favFilter.value = f }

// 切换会话
async function switchSession(sid) {
  if (loading.value) return
  currentSessionId.value = sid
  try {
    const r = await getHistory(sid)
    if (r.success && Array.isArray(r.data)) {
      messages.value = r.data
        // 纯图片用户提问 content 为空：只过滤空壳（无正文也无图），不能只按 content 过滤否则刷新后图片提问消失
        .filter(m => m && (m.content || (Array.isArray(m.images) && m.images.length)))
        .map(m => ({
          role: m.role === 'user' ? 'user' : 'ai',
          content: String(m.content || ''),
          messageId: m.messageId || m.id || null,
          fb: (m.fb === 0 || m.fb === 1) ? m.fb : null, // 历史消息的既有评价（单选锁定刷新后仍生效）
          images: Array.isArray(m.images) ? m.images : [],
          sources: Array.isArray(m.sources) ? m.sources : [],
          related: [],
          thinking: m.thinking || '',
          thinkOpen: false,
          time: m.createTime ? new Date(m.createTime).getTime() : null,
          retrieved: (() => { try { return m.retrieved ? JSON.parse(m.retrieved) : null } catch (e) { return null } })()
        }))
      // 切换会话回到底部（历史加载完成）
      scrollForce()
    } else {
      messages.value = []
    }
  } catch (e) {
    messages.value = []
  }
}

// 会话列表显示：隐藏空会话 + 收藏筛选（收藏 tab 仅展示收藏项）
const visibleSessions = computed(() => {
  let list = sessions.value.filter(s => (s.messageCount ?? 0) > 0)
  if (favFilter.value === 'fav') {
    list = list.filter(s => s.isFavorite === 1)
  }
  return list
})

// 置顶/取消置顶（本地更新 + 原地排序，避免整表刷新导致 tooltip 卡住）
async function handleTogglePin(s) {
  try {
    const next = !(s.isPinned === 1)
    const r = await pinSession(s.id, next)
    if (r.success) {
      const target = sessions.value.find(x => x.id === s.id)
      if (target) target.isPinned = next ? 1 : 0
      sortSessions()
      message.success(next ? '已置顶' : '已取消置顶')
    } else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}

// 收藏/取消收藏（本地更新，不整表刷新）
async function handleToggleFavorite(s) {
  try {
    const next = !(s.isFavorite === 1)
    const r = await favoriteSession(s.id, next)
    if (r.success) {
      const target = sessions.value.find(x => x.id === s.id)
      if (target) target.isFavorite = next ? 1 : 0
      message.success(next ? '已收藏' : '已取消收藏')
    } else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}

// 原地排序（置顶优先 + 更新时间倒序），保持与后端 listSessions 一致；不替换数组引用避免 DOM 重建
function sortSessions() {
  sessions.value.sort((a, b) =>
    (b.isPinned || 0) - (a.isPinned || 0) || new Date(b.updateTime) - new Date(a.updateTime))
}

// 新建会话（防重复 + 无感复用空会话：已存在空会话时切换过去，不新建不提示）
const creatingSession = ref(false)
async function createNewSession() {
  if (creatingSession.value) return
  // 列表已存在空会话（messageCount=0）→ 无感复用，避免堆叠一堆"新建对话"
  const emptySid = sessions.value.find(s => (s.messageCount ?? 0) === 0)?.id
  if (emptySid) {
    if (currentSessionId.value !== emptySid) {
      await switchSession(emptySid)
    } else {
      messages.value = []
    }
    focusInput()
    return
  }
  creatingSession.value = true
  try {
    const r = await newSession()
    if (r.success && r.data?.sessionId) {
      currentSessionId.value = r.data.sessionId
      messages.value = []
      await loadSessions()   // 立即刷新会话列表（新会话置顶显示）
      focusInput()
    }
  } catch (e) {
    message.error('创建会话失败: ' + (e.message || '未知错误'))
  } finally {
    creatingSession.value = false
  }
}

// 聚焦输入框（新建/切换会话后调用；输入区固定在页面底部，nextTick 确保渲染完成）
const focusInput = () => nextTick(() => textareaRef.value?.focus())
// 会话搜索框（Cmd/Ctrl+K 快捷键；折叠态先展开再聚焦）
const sidebarRef = ref(null)
const focusSearch = () => {
  if (sidebarWidth.value < SIDEBAR_ICON_THRESHOLD) sidebarWidth.value = 260
  nextTick(() => sidebarRef.value?.focusSearch())
}
// 会话重命名：sidebar ··· 菜单触发，在会话条目旁悬浮小卡片（fixed 定位）；输入后调接口并同步本地标题
const renameVisible = ref(false)
const renameValue = ref('')
const renameTarget = ref(null)
const renameInputRef = ref(null)
const renamingSession = ref(false)
const renamePos = ref(null) // 条目 getBoundingClientRect（悬浮卡片定位锚点；会话条目最多 420px 宽，卡片恒在右侧可视区）
const RENAME_POP_W = 276
const renameStyle = computed(() => {
  const r = renamePos.value
  if (!r) return {}
  const left = Math.max(8, Math.min(r.right + 10, window.innerWidth - RENAME_POP_W - 8))
  const top = Math.max(8, Math.min(r.top, window.innerHeight - 160))
  return { left: left + 'px', top: top + 'px', width: RENAME_POP_W + 'px' }
})
const openRenameModal = (s, rect) => {
  renameTarget.value = s
  renameValue.value = (s && s.title) || ''
  renamePos.value = rect
  renameVisible.value = true
  nextTick(() => renameInputRef.value?.focus())
}
const closeRenamePop = () => {
  renameVisible.value = false
  renamePos.value = null
  renameTarget.value = null
}
// 浮层打开期间：点外部关闭、滚动/窗口变化即关（fixed 卡片不随内容滚动，位置失效就收起）
let renameCleanup = null
watch(renameVisible, v => {
  if (renameCleanup) {
    const { onDocDown, onScroll, onResize } = renameCleanup
    document.removeEventListener('mousedown', onDocDown)
    document.removeEventListener('scroll', onScroll, true)
    window.removeEventListener('resize', onResize)
    renameCleanup = null
  }
  if (v) {
    const onDocDown = () => closeRenamePop()
    const onScroll = () => closeRenamePop()
    const onResize = () => closeRenamePop()
    document.addEventListener('mousedown', onDocDown)
    document.addEventListener('scroll', onScroll, true)
    window.addEventListener('resize', onResize)
    renameCleanup = { onDocDown, onScroll, onResize }
  }
})
const doRenameSession = async () => {
  const t = renameTarget.value
  const title = renameValue.value.trim()
  if (!t || !title) { message.warning('标题不能为空'); return }
  renamingSession.value = true
  try {
    const r = await renameSessionApi(t.id, title)
    if (r.success) {
      const target = sessions.value.find(x => x.id === t.id)
      if (target) target.title = title
      closeRenamePop()
      message.success('已重命名')
    } else message.error(r.msg || '重命名失败')
  } catch (e) { message.error(e.message || '重命名失败') }
  finally { renamingSession.value = false }
}

// 聊天区拖入/粘贴发图（与文档页能力对齐；纯文本粘贴不受影响）
const dragOver = ref(false)
let dragDepth = 0
const onDragEnter = e => {
  if (!loading.value && Array.from(e.dataTransfer?.types || []).includes('Files')) {
    dragDepth++
    dragOver.value = true
  }
}
const onDragLeave = () => { if (--dragDepth <= 0) { dragDepth = 0; dragOver.value = false } }
const onDropImages = e => {
  dragDepth = 0
  dragOver.value = false
  if (loading.value) return
  addImageFiles(Array.from(e.dataTransfer?.files || []))
}
const onPasteImages = e => {
  const imgs = Array.from(e.clipboardData?.files || []).filter(f => f.type.startsWith('image/'))
  if (!imgs.length || loading.value) return
  e.preventDefault() // 阻止图片被当作文本粘进输入框
  addImageFiles(imgs)
}
const addImageFiles = files => {
  for (const f of files) {
    if (pendingImages.value.length >= 5) { message.warning('最多上传 5 张图片'); break }
    if (!f.type.startsWith('image/')) continue
    compressImage(f).then(dataUrl => pendingImages.value.push({ dataUrl })).catch(() => message.error(`图片处理失败: ${f.name}`))
  }
}

// 删除会话
async function handleDeleteSession(sid) {
  try {
    await deleteSessionApi(sid)
    message.success('会话已删除')
    if (sid === currentSessionId.value) {
      // 删除的是当前会话，切换到下一个或新建
      const remaining = sessions.value.filter(s => s.id !== sid)
      if (remaining.length > 0) {
        await switchSession(remaining[0].id)
      } else {
        await createNewSession()
      }
    }
    await loadSessions()
  } catch (e) {
    message.error('删除失败: ' + (e.message || '未知错误'))
  }
}

// 批量删除会话（sidebar 已二次确认）
async function handleBatchDeleteSessions(ids) {
  try {
    const r = await batchDeleteSessionsApi(ids)
    if (!r.success) {
      message.error(r.msg || '批量删除失败')
      await loadSessions()
      return
    }
    message.success(`已删除 ${r.data?.deleted ?? ids.length} 个会话`)
    if (ids.includes(currentSessionId.value)) {
      const remaining = sessions.value.filter(s => !ids.includes(s.id))
      if (remaining.length > 0) {
        await switchSession(remaining[0].id)
      } else {
        messages.value = []
        currentSessionId.value = null
      }
    }
    await loadSessions()
  } catch (e) {
    message.error('批量删除失败: ' + (e.message || '未知错误'))
    await loadSessions()
  }
}

// 清空所有对话（sidebar 已二次确认）
async function handleClearAll() {
  try {
    await clearAllSessionsApi()
    messages.value = []
    currentSessionId.value = null
    sessions.value = []
    message.success('所有对话已清空')
  } catch (e) {
    message.error('清空失败: ' + (e.message || '未知错误'))
  }
}

// 用户上传图片：压缩为 data URL（最长边 1280，JPEG 0.85），最多 5 张
const pendingImages = ref([])
const fileInput = ref(null)
const pickImages = () => { if (pendingImages.value.length >= 5) { message.warning('最多上传 5 张图片'); return } fileInput.value?.click() }
const onFilesChange = e => {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  addImageFiles(files)
}
const compressImage = file => new Promise((resolve, reject) => {
  const img = new Image()
  const url = URL.createObjectURL(file)
  img.onload = () => {
    const max = 1280
    let { width, height } = img
    if (width > max || height > max) {
      const ratio = Math.min(max / width, max / height)
      width = Math.round(width * ratio); height = Math.round(height * ratio)
    }
    const canvas = document.createElement('canvas')
    canvas.width = width; canvas.height = height
    canvas.getContext('2d').drawImage(img, 0, 0, width, height)
    URL.revokeObjectURL(url)
    resolve(canvas.toDataURL('image/jpeg', 0.85))
  }
  img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('图片加载失败')) }
  img.src = url
})
const removePendingImage = i => pendingImages.value.splice(i, 1)
// 待发送预览图 → 灯箱放大（所有待发送图可左右切换）
const previewPendingImage = pi => {
  if (!pendingImages.value.length) return
  previewList.value = pendingImages.value.map(p => p.dataUrl)
  previewIndex.value = pi
  zoom.value = 1
  offset.value = { x: 0, y: 0 }
}
// 用户气泡图片 → 灯箱（该消息全部图片可切换）
const openPreviewFromMsg = (m, index) => {
  if (!m.images || !m.images.length) return
  previewList.value = m.images.map(resolveImg)
  previewIndex.value = index || 0
  zoom.value = 1
  offset.value = { x: 0, y: 0 }
}

const send = () => {
  const q = text.value.trim()
  const imgs = pendingImages.value.map(p => p.dataUrl)
  if ((!q && !imgs.length) || loading.value) return
  text.value = ''
  pendingImages.value = []
  const deep = deepThinkOn.value
  messages.value.push({ role: 'user', content: q, images: imgs, deepThink: deep, time: Date.now() })
  streamAnswer(q, imgs, null, messages.value.length === 1, 1, deep)
}

// 回车发送：中文输入法组合中的 Enter（选词确认，isComposing/keyCode 229）必须交还输入法，
// 否则确认候选词的 Enter 会触发发送——消息发出、输入框清空后，输入法又把确认文本回填，
// 表现为"消息已发送但问题还在输入框里"
const onEnterKey = e => {
  if (e.isComposing || e.keyCode === 229) return
  send()
}

watch(currentSessionId, () => exitSelectMode())

// 消息时间格式化：当天只显示时分，昨天带前缀，更早带日期
const fmtMsgTime = ts => {
  if (!ts) return ''
  const d = new Date(ts)
  if (isNaN(d.getTime())) return ''
  const now = new Date()
  const hm = String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0')
  if (d.toDateString() === now.toDateString()) return '今天 ' + hm
  const yest = new Date(now)
  yest.setDate(now.getDate() - 1)
  if (d.toDateString() === yest.toDateString()) return '昨天 ' + hm
  return String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0') + ' ' + hm
}

// 统一流式回答：replaceIdx 为 null 追加新 AI 消息；否则替换该条（重新生成）
// autoRetry：剩余自动重试次数（仅"未收到任何 token"的瞬时断连才自动重试，避免清空已生成内容）
// deepThink：是否深度思考（开启后后端先流式下发 thinking 事件）
const streamAnswer = (question, imgs, replaceIdx, isFirstMessage, autoRetry = 1, deepThink = false) => {
  const idx = replaceIdx ?? messages.value.length
  if (replaceIdx == null) {
    messages.value.push({ role: 'ai', content: '', images: [], sources: [], related: [], degradations: [], warnMsg: '', loading: true, thinking: '', thinkOpen: true, thinkLoading: false, stage: '正在思考中…', time: Date.now() })
  } else {
    messages.value[replaceIdx] = { role: 'ai', content: '', images: [], sources: [], related: [], degradations: [], warnMsg: '', loading: true, messageId: null, fb: null, thinking: '', thinkOpen: true, thinkLoading: false, stage: '正在思考中…', time: Date.now() }
  }
  loading.value = true
  // 发送后立即滚到底部：不等第一个 token（深度思考/图片处理时 AI 迟迟不出字，视角也要先到最下看到 loading 气泡）
  scrollForce()
  abortController.value = new AbortController()
  let full = ''
  let gotToken = false
  sendQuestion(currentSessionId.value, question, imgs, {
    signal: abortController.value.signal,
    deepThink,
    onThinking: t => {
      const m = messages.value[idx]
      m.thinking = (m.thinking || '') + t
      m.thinkLoading = true
      scroll()
      // 思考面板内部自动滚动到底（思考内容超出面板高度时跟随最新）
      nextTick(() => {
        const body = thinkingBodyRefs[idx]
        if (body) body.scrollTop = body.scrollHeight
      })
    },
    onThinkingDone: payload => {
      const m = messages.value[idx]
      m.thinkLoading = false
      // 思考完成自动折叠（避免超长思维链刷屏；想看再点开）
      m.thinkOpen = false
      try {
        const j = JSON.parse(payload)
        if (j.thinking) m.thinking = j.thinking
      } catch (e) { /* 兼容旧 payload */ }
    },
    onToken: t => { gotToken = true; full += t; messages.value[idx].content = full; messages.value[idx].stage = ''; messages.value[idx].thinkLoading = false; scroll() },
    onStage: s => { messages.value[idx].stage = s; scroll() },
    onRetrieved: payload => {
      try {
        const j = JSON.parse(payload)
        // 检索状态行（含检索词列表，展开明细用）；到达即清阶段提示
        messages.value[idx].retrieved = { keywords: j.keywords || 0, refs: j.refs || 0, terms: j.terms || [] }
        messages.value[idx].stage = ''
        scroll()
      } catch (e) { /* 忽略 */ }
    },
    onImage: imgs => {
      try {
        const parsed = JSON.parse(imgs)
        messages.value[idx].images = Array.isArray(parsed) ? parsed : []
      } catch (e) {
        messages.value[idx].images = []
      }
    },
    onDone: contentJson => {
      // done 事件 content 为 {sources, related, messageId, thinking, degradations} JSON 字符串
      let sources = [], related = [], messageId = null, degradations = []
      try {
        const p = JSON.parse(contentJson || '{}')
        sources = Array.isArray(p.sources) ? p.sources : []
        related = Array.isArray(p.related) ? p.related : []
        messageId = p.messageId || null
        degradations = Array.isArray(p.degradations) ? p.degradations : []
        if (p.thinking) messages.value[idx].thinking = p.thinking
        messages.value[idx].thinkLoading = false
        // 后端图片相关性校验后下发的修正内容/图片（覆盖流式中间态，保证 [图片N] 编号与图一致）
        if (typeof p.finalContent === 'string' && p.finalContent !== '') {
          messages.value[idx].content = p.finalContent
        }
        if (Array.isArray(p.finalImages)) {
          messages.value[idx].images = p.finalImages
        }
      } catch (e) { /* 旧版/停止生成：无负载 */ }
      if (messages.value[idx].content === '') messages.value[idx].content = '（已停止生成）'
      messages.value[idx].loading = false
      messages.value[idx].sources = sources
      // 引用语义自检/越界校验可能裁剪了来源：状态行"参考 N 段资料"改用最终 sources 长度，
      // 与展开明细保持一致（retrieved 事件先到，done 时以最终值为准）
      if (messages.value[idx].retrieved && Array.isArray(sources)) {
        messages.value[idx].retrieved.refs = sources.length
      }
      messages.value[idx].related = related
      messages.value[idx].messageId = messageId
      messages.value[idx].degradations = degradations
      loading.value = false
      abortController.value = null
      scrollForce()
      // 首条消息后刷新会话列表（标题已由后端生成）
      if (isFirstMessage) loadSessions()
    },
    onWarn: w => {
      // fail-loud：SSE warn 事件（如回答超时截断）在回答气泡下提示
      messages.value[idx].warnMsg = w
      scroll()
    },
    onError: e => {
      // 用户主动停止不走到这里（AbortError 在 api.js 按正常结束处理）
      // 瞬时断连自动重试：未收到任何 token 时静默重试（2.5s 退避），替换当前消息而非追加
      if (autoRetry > 0 && !gotToken) {
        // 内联提示（回答框下方），替代全局弹窗 message.warning
        messages.value[idx].retrying = true
        scroll()
        setTimeout(() => {
          // 等待期间消息列表可能已变化（切换/清空会话），放弃自动重试
          if (idx < messages.value.length && messages.value[idx]?.role === 'ai' && messages.value[idx]?.loading) {
            streamAnswer(question, imgs, idx, false, 0, deepThink)
          } else {
            if (messages.value[idx]) messages.value[idx].retrying = false
            loading.value = false
            abortController.value = null
          }
        }, 2500)
        return
      }
      // 已收到部分回答：保留已生成内容（不自动重连清空重来），标记 failed 显示「重试」按钮
      messages.value[idx].content = '😅 ' + e
      messages.value[idx].loading = false
      messages.value[idx].retrying = false
      messages.value[idx].failed = true
      loading.value = false
      abortController.value = null
      message.error(e)
      scrollForce()
    }
  })
}

// 重新生成：找到该回答对应的用户问题，重新流式回答（替换本条）
const regenerate = mi => {
  if (loading.value) return
  for (let i = mi - 1; i >= 0; i--) {
    if (messages.value[i].role === 'user') {
      // 仅本地 dataUrl 图片可重发（历史 URL 图跳过，避免后端无法处理）；复用原问题的深度思考开关
      const imgs = (messages.value[i].images || []).filter(u => u.startsWith('data:'))
      const deep = !!messages.value[i].deepThink
      streamAnswer(messages.value[i].content, imgs, mi, false, 1, deep)
      return
    }
  }
  message.warning('未找到对应的问题')
}

// 复制回答（Markdown 原文）
const copyAnswer = async mi => {
  const m = messages.value[mi]
  if (!m || !m.content) { message.warning('该回答无可复制内容'); return }
  const txt = m.content.trim()
  if (navigator.clipboard?.writeText) {
    try { await navigator.clipboard.writeText(txt); message.success('已复制到剪贴板') }
    catch (e) { fallbackCopyText(txt) }
  } else {
    fallbackCopyText(txt)
  }
}
const fallbackCopyText = txt => {
  try {
    const ta = document.createElement('textarea')
    ta.value = txt
    ta.setAttribute('readonly', '')
    ta.style.position = 'absolute'
    ta.style.left = '-9999px'
    document.body.appendChild(ta)
    ta.focus(); ta.select(); ta.setSelectionRange(0, txt.length)
    const ok = document.execCommand('copy')
    ta.remove()
    if (ok) message.success('已复制到剪贴板')
    else message.error('复制失败，请手动复制')
  } catch (err) { message.error('复制失败，请手动复制') }
}

// ==================== Markdown 导出（单轮 + 整会话） ====================
// 图片策略：data URL 原样；服务内 /ai/... 图片经 /proxy fetch 转 base64 内嵌（导出文件自包含）；
// 抓取失败（签名过期/跨域被拦）返回 null → 正文保留 [图片N] 占位。

const imgToDataUri = async u => {
  if (!u) return null
  if (u.startsWith('data:')) return u
  try {
    const r = await fetch(resolveImg(u))
    if (!r.ok) return null
    const blob = await r.blob()
    return await new Promise((res, rej) => {
      const fr = new FileReader()
      fr.onload = () => res(fr.result)
      fr.onerror = rej
      fr.readAsDataURL(blob)
    })
  } catch (e) {
    return null
  }
}

/** 把正文里的 [图片N] 占位替换为内嵌图（取不到图的保留原占位文本） */
const embedMdImages = async (content, imgs) => {
  const uris = []
  for (const u of (imgs || [])) uris.push(await imgToDataUri(u))
  return content.replace(/\[图片\s*(\d+)(?:[：:]([^\]]*))?\]/g, (all, num, desc) => {
    const uri = uris[Number(num) - 1]
    return uri ? `![图片${num}${desc && desc.trim() ? '：' + desc.trim() : ''}](${uri})` : all
  })
}

const downloadMd = (md, fileName) => {
  const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

const fmtTime = ts => {
  if (!ts) return ''
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return ''
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

// 导出该轮问答为 .md（问题仅配对当前回答所在轮次；图片尽量 base64 内嵌）
const exportAnswer = async mi => {
  const m = messages.value[mi]
  if (!m || !m.content) { message.warning('该回答无可导出内容'); return }
  const imgs = Array.isArray(m.images) ? m.images : []
  const hide = imgs.length ? message.loading('正在导出（含图片抓取转码）…', 0) : null
  try {
    const title = sessions.value.find(s => s.id === currentSessionId.value)?.title || 'AI回答'
    const parts = [`# ${title}\n`]
    // 单轮配对：向 mi 前找最近一条 user（遇更早 ai/assistant 即停）
    let question = null
    for (let i = mi - 1; i >= 0; i--) {
      if (messages.value[i].role === 'user') { question = messages.value[i]; break }
      if (messages.value[i].role === 'assistant' || messages.value[i].role === 'ai') break
    }
    if (question?.content) parts.push('## 问题\n' + question.content.trim() + '\n')
    const body = await embedMdImages(m.content.trim(), imgs)
    parts.push('## 回答\n' + body + '\n')
    if (m.sources && m.sources.length) {
      parts.push('## 引用来源\n' + m.sources.map((s, si) =>
        `${si + 1}. ${s.fileName || '未知文档'}${s.title ? ' §' + s.title : ''}`).join('\n') + '\n')
    }
    const safeName = (title || new Date().toISOString().slice(0, 10)).replace(/[\\/:*?"<>|]/g, '_')
    downloadMd(parts.join('\n'), safeName + '.md')
  } finally {
    if (hide) hide()
  }
}

// 导出整个会话为 .md（按轮次：问题（含用户图）→ 回答（含引用图）→ 引用来源）
// 入口在会话列表「···」菜单（可导出任意会话，无需先打开）
const exportSessionMarkdown = async sidOrSession => {
  const sid = typeof sidOrSession === 'object' && sidOrSession
    ? sidOrSession.id
    : (sidOrSession || currentSessionId.value)
  if (!sid) return
  const s = (sessions.value || []).find(x => x.id === sid)
  const title = (s && s.title) || (sid === currentSessionId.value ? currentSessionTitle.value : 'AI对话')
  const hide = message.loading('正在导出整个会话（含图片抓取转码）…', 0)
  try {
    const r = await getHistory(sid)
    if (!r.success) { message.error(r.msg || '获取会话历史失败'); return }
    const list = r.data || []
    const rows = list.filter(m => m && ((m.content && m.content.trim()) || (Array.isArray(m.images) && m.images.length)))
    const now = new Date()
    const roundCount = rows.filter(m => m.role === 'ai').length
    const parts = [`# ${title}`, '', `> 导出时间：${now.toLocaleString('zh-CN', { hour12: false })} · 共 ${roundCount} 轮问答`, '']
    let qn = 0
    let first = true
    for (const m of rows) {
      if (!first) parts.push('---', '')
      first = false
      if (m.role === 'user') {
        qn++
        parts.push(`## ${qn}. 问题`, '')
        if (m.content && m.content.trim()) parts.push(m.content.trim(), '')
        const uims = Array.isArray(m.images) ? m.images : []
        for (let k = 0; k < uims.length; k++) {
          const uri = await imgToDataUri(uims[k])
          if (uri) parts.push(`![用户图片${k + 1}](${uri})`, '')
        }
      } else {
        const body = await embedMdImages((m.content || '').trim(), Array.isArray(m.images) ? m.images : [])
        parts.push('**回答**', '', body, '')
        if (m.sources && m.sources.length) {
          parts.push('**引用来源**', m.sources.map((s, si) =>
            `${si + 1}. ${s.fileName || '未知文档'}${s.title ? ' §' + s.title : ''}`).join('\n'), '')
        }
        if (m.messageId && m.fb !== undefined) {
          // 保留评价状态，便于回顾哪些回答被认可
          parts.push(`> 评价：${m.fb === 1 ? '有帮助 👍' : '没帮助 👎'}${fmtTime(m.time) ? ' · ' + fmtTime(m.time) : ''}`, '')
        } else if (fmtTime(m.time)) {
          parts.push(`> ${fmtTime(m.time)}`, '')
        }
      }
    }
    if (parts.length <= 3) { message.warning('会话为空，无可导出内容'); return }
    const safeName = (title || new Date().toISOString().slice(0, 10)).replace(/[\\/:*?"<>|]/g, '_')
    downloadMd(parts.join('\n'), safeName + '.md')
  } catch (e) {
    message.error(e.message || '导出失败')
  } finally {
    if (hide) hide()
  }
}

// 会话列表「···」菜单：导出该会话（任意会话均可，不必先打开）
const handleExportSession = s => {
  if (s && s.id) exportSessionMarkdown(s.id)
}

// 检索调试：打开弹窗（默认该轮问题）并执行
// 操作区「更多」菜单：不常用动作收口
const onMoreAction = (key, mi) => {
  if (key === 'debug') openDebug(mi)
  else if (key === 'export') exportAnswer(mi)
  else if (key === 'deleteRound') enterSelectMode(mi)
}

// ==================== 多选删除（进入选择模式 → 勾选轮次 → 底栏批量删除，支持撤销） ====================
const selectMode = ref(false)
const selected = ref([])   // 选中的"轮次起始索引"（用户消息）
const deletingRounds = ref(false)

// 消息所属轮次的起始索引（用户消息=自身；回答=其前面的用户消息；孤条=自身）
const roundStart = i => {
  const m = messages.value[i]
  if (!m) return -1
  if (m.role === 'user') return i
  if (i > 0 && messages.value[i - 1]?.role === 'user') return i - 1
  return i
}
const toggleSelect = i => {
  const s = roundStart(i)
  if (s < 0) return
  const at = selected.value.indexOf(s)
  if (at >= 0) selected.value.splice(at, 1)
  else selected.value.push(s)
}
// 进入多选模式：从触发消息所在轮次开始勾选（从回答进入时默认勾上同组问题）
const enterSelectMode = mi => {
  selectMode.value = true
  selected.value = []
  const s = roundStart(mi)
  if (s >= 0) selected.value.push(s)
}
const exitSelectMode = () => { selectMode.value = false; selected.value = [] }

// 底栏「删除」：批量删除选中轮次，完成后可撤销（逐轮恢复）
const deleteSelected = async () => {
  const rounds = selected.value.slice().sort((a, b) => a - b).map(s => {
    const aiIdx = messages.value[s + 1]?.role === 'ai' ? s + 1 : s
    const mid = messages.value[aiIdx]?.messageId || null
    return { from: s, aiIdx, mid, items: messages.value.slice(s, aiIdx + 1) }
  }).filter(r => r.mid)
  if (!rounds.length) { message.warning('选中内容无可删除'); return }
  const sessionIdAtDelete = currentSessionId.value
  deletingRounds.value = true
  let ok = 0
  for (const r of rounds) {
    try {
      const res = await deleteMessageGroup(r.mid)
      if (res.success) ok++
    } catch (e) { /* 单轮失败继续其余 */ }
  }
  deletingRounds.value = false
  if (ok === 0) { message.error('删除失败'); return }
  // 本地移除（从后往前，索引不漂移）
  [...rounds].sort((a, b) => b.from - a.from).forEach(r => messages.value.splice(r.from, r.items.length))
  exitSelectMode()
  loadSessions()
  // 撤销 toast（5 秒，批量恢复全部选中轮次）
  const nkey = 'undo_' + Date.now()
  notification.open({
    key: nkey,
    message: '已删除 ' + ok + ' 轮对话',
    placement: 'bottom',
    duration: 5,
    btn: h('button', {
      class: 'undo-toast-btn',
      onClick: async () => {
        notification.close(nkey)
        try {
          for (const r of rounds) await undoDeleteMessageGroup(r.mid)
          if (currentSessionId.value === sessionIdAtDelete) {
            rounds.slice().sort((a, b) => a.from - b.from)
              .forEach((r, k) => messages.value.splice(r.from + k, 0, ...r.items))
          } else {
            loadSessions()
          }
          message.success('已恢复 ' + ok + ' 轮对话')
          loadSessions()
        } catch (e) { message.error(e.message || '恢复失败') }
      }
    }, '撤销')
  })
}



const openDebug = mi => {
  for (let i = mi - 1; i >= 0; i--) {
    if (messages.value[i].role === 'user') { debugQuestion.value = messages.value[i].content; break }
  }
  debugVisible.value = true
  runDebug()
}
const runDebug = async () => {
  const q = debugQuestion.value.trim()
  if (!q) { message.warning('请输入问题'); return }
  debugLoading.value = true
  debugResult.value = null
  try {
    const r = await debugRetrieval(q)
    if (r.success) debugResult.value = r.data
    else message.error(r.msg || '调试失败')
  } catch (e) { message.error(e.message || '调试失败') }
  finally { debugLoading.value = false }
}
// 分步调试面板数据（统一行结构：title/docName/snippet/tag）
const debugStages = computed(() => {
  const d = debugResult.value
  if (!d) return []
  const map = (items, tagFn) => (items || []).map(it => ({
    title: it.title || '（无标题）',
    docName: it.docName || '',
    snippet: it.snippet || '',
    titleHit: !!it.titleHit,
    tag: tagFn(it)
  }))
  return [
    { name: `关键词命中（${(d.keywordHits || []).length}）`, items: map(d.keywordHits, it => '命中率 ' + (it.hitRate ?? 0)) },
    { name: `向量命中（${(d.vectorHits || []).length}）`, items: map(d.vectorHits, it => '相似度 ' + (it.score ?? 0)) },
    { name: `合并后（${(d.merged || []).length}）`, items: map(d.merged, it => '分 ' + (it.score ?? 0)) },
    { name: `重排后（${(d.reranked || []).length}）` + (d.rerankApplied ? '' : `（${d.rerankSkipReason || '未重排'}）`), items: map(d.reranked, it => '分 ' + (it.score ?? 0)) },
    { name: `最终上下文（${(d.finalContext || []).length}/8）`, items: map(d.finalContext, it => '分 ' + (it.score ?? 0)) },
    { name: `被排除（${(d.excluded || []).length}）`, items: map(d.excluded, it => '分 ' + (it.score ?? 0)) }
  ]
})

// 编辑问题：回填输入框（回显本地图片）+ 重新发送
const editMessage = mi => {
  const m = messages.value[mi]
  if (!m || m.role !== 'user') return
  text.value = m.content
  // 回显本地图片（dataUrl）到待发送区；历史 URL 图跳过（后端仅处理 data:）
  const localImgs = (m.images || []).filter(u => u.startsWith('data:'))
  if (localImgs.length) {
    pendingImages.value = [...pendingImages.value, ...localImgs.map(u => ({ dataUrl: u }))]
  }
  const urlCount = (m.images || []).length - localImgs.length
  nextTick(() => textareaRef.value?.focus())
  message.info(urlCount > 0
    ? `已回填文字并回显 ${localImgs.length} 张图片（${urlCount} 张历史图片需重新上传）`
    : '已回填到输入框，修改后按 Enter 发送')
}

const stop = () => {
  if (abortController.value) {
    abortController.value.abort()
    abortController.value = null
  }
}

const ask = q => { text.value = q; nextTick(send) }

// Markdown 渲染统一走公共模块（utils/markdown.js）：renderMd / resolveImg / onImgError / copyCode / prepKnowledgeContent
// 交互事件（角标→来源弹窗、图片→灯箱、代码复制）由 openPreview 事件委托处理

// 贴底才自动滚动：生成期间用户上翻回看历史不被拽走；scrollForce 强制回底（发送/切换/完成/失败时用）
const scroll = () => nextTick(() => {
  if (box.value && stickToBottom.value) {
    box.value.scrollTop = box.value.scrollHeight
    stickToBottom.value = true
  }
})
const scrollForce = () => nextTick(() => {
  if (box.value) {
    box.value.scrollTop = box.value.scrollHeight
    stickToBottom.value = true
  }
})
</script>

<style scoped>
.chat-layout {
  display: flex;
  height: calc(100vh - 112px);
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
}
/* 侧边栏拖拽手柄 */
.sidebar-resizer {
  width: 5px;
  flex-shrink: 0;
  cursor: col-resize;
  background: transparent;
  transition: background .15s;
  position: relative;
  z-index: 2;
}
.sidebar-resizer:hover, .sidebar-resizer:active { background: #cfe3ff; }
.chat {
  flex: 1;
  min-width: 0;
  display: flex;
  justify-content: center;
}
.chat-box {
  width: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.head {
  position: relative;          /* 供右上角工具按钮定位 */
  display: flex;
  flex-direction: column;   /* 上下两行：标题 + 免责声明，同一框内 */
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 12px 24px;
  border-bottom: 1px solid #f0f0f0;
  font-weight: 600;
}
.head-title {
  font-size: 15px;
  color: #333;
  max-width: 60%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 免责声明：标题下方、同一框内，弱化显示不抢焦点；可点击查看完整声明 */
.disclaimer {
  display: flex;
  align-items: center;
  font-size: 11px;
  font-weight: 400;
  color: #999;
  text-align: center;
  cursor: pointer;
  user-select: none;
}
.disclaimer:hover { color: #1677ff; }
.disclaimer-body { max-height: 60vh; overflow-y: auto; color: #333; }
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 24px 48px;   /* 左右留白：拉满宽度下消息不贴边 */
}
.welcome {
  text-align: center;
  padding: 60px 20px;
}
.welcome h2 { margin: 16px 0 8px; }
.welcome p { color: #888; margin-bottom: 24px; }
.row { display: flex; gap: 12px; margin-bottom: 20px; justify-content: center; }
/* 文档流：内容列居中限宽——问题右对齐浅灰气泡，回答列内全宽无气泡 */
.row.user { justify-content: center; }
/* 消息块：承载气泡 + 气泡下方悬浮操作区（编辑等）；宽度约束由外层承担 */
.msg-block { position: relative; display: flex; flex-direction: column; min-width: 0; max-width: min(92%, 860px); width: 100%; }
.msg-block.user { align-items: flex-end; }
.msg-block.ai { align-items: flex-start; }
.bubble { width: 100%; padding: 12px 16px; border-radius: 12px; line-height: 1.6; }
.bubble.user { background: #f2f3f5; color: #333; width: fit-content; max-width: 100%; }
/* 用户问题气泡：正文为纯文本，收拢段落边距使高度贴合文字（.md p 默认 6px 上下边距会把气泡撑高一倍；
   p 为 v-html 动态内容不带 scoped 属性，需 :deep 穿透） */
.bubble.user :deep(.md > p) { margin: 0; }
.bubble.ai { background: transparent; padding: 0; }
.input { position: relative; padding: 12px 48px 16px; display: flex; flex-wrap: wrap; align-items: flex-end; gap: 8px; }
/* 拖入图片提示遮罩（仅接收文件拖入；文本拖入不受影响） */
.drop-overlay {
  position: absolute; inset: 4px 48px 8px; z-index: 6; pointer-events: none;
  background: rgba(22,119,255,.06); border: 2px dashed #1677ff; border-radius: 12px;
  display: flex; align-items: center; justify-content: center;
  color: #1677ff; font-size: 15px; font-weight: 500; letter-spacing: 1px;
}
/* 回到底部（生成中上翻回看历史时暂停自动滚动，按钮一键恢复跟随） */
.jump-latest {
  position: sticky; bottom: 12px; z-index: 5;
  width: fit-content; margin: 0 auto 4px;
  background: rgba(22,119,255,.92); color: #fff; font-size: 12px;
  padding: 4px 16px; border-radius: 999px; cursor: pointer; user-select: none;
  box-shadow: 0 2px 8px rgba(0,0,0,.18);
}
.jump-latest:hover { background: #1677ff; }
/* 会话重命名悬浮小卡片（fixed 钉在会话条目右侧；z-index 高于 antd dropdown 菜单，避免被盖） */
.rename-pop {
  position: fixed; z-index: 1080;
  background: #fff; border: 1px solid #e5e6eb; border-radius: 10px;
  box-shadow: 0 6px 20px rgba(0,0,0,.12);
  padding: 12px 12px 10px;
}
.rename-pop-title { font-size: 13px; font-weight: 600; color: #333; margin-bottom: 8px; }
.rename-pop-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 10px; }
/* 输入卡片：无内边框文本在上，工具行在下 */
.input-box {
  position: relative; flex: 1; min-width: 0; max-width: 860px;
  margin-left: auto; margin-right: auto;
  border: 1px solid #e5e6eb; border-radius: 16px; background: #fff;
  padding: 8px 10px 6px;
  transition: border-color .2s, box-shadow .2s;
}
.input-box:focus-within { border-color: #1677ff; box-shadow: 0 0 0 2px rgba(22,119,255,.08); }
.input-toolbar { display: flex; align-items: center; justify-content: space-between; margin-top: 4px; }
.toolbar-left { display: flex; align-items: center; gap: 2px; }
.toolbar-btn-on { color: #1677ff !important; background: rgba(22,119,255,.08); }
.send-btn {
  width: 32px; height: 32px; border-radius: 50%; border: none;
  background: #1677ff; color: #fff; font-size: 16px; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
  transition: background .2s, transform .1s;
}
.send-btn:hover:not(:disabled) { background: #4096ff; }
.send-btn:active:not(:disabled) { transform: scale(.94); }
.send-btn:disabled { background: #e5e6eb; color: #fff; cursor: not-allowed; }
.send-btn.stop { background: #ff4d4f; }
.send-btn.stop:hover { background: #ff7875; }
/* 深度思考折叠面板（AI 气泡内） */
.think-panel {
  margin: 4px 0 8px;
  border: 1px solid #e8e8e8;
  border-radius: 8px;
  background: #fafafa;
  overflow: hidden;
}
.think-panel .think-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  cursor: pointer;
  user-select: none;
  font-size: 12px;
  color: #888;
}
.think-panel .think-head:hover { background: #f0f0f0; }
.think-panel .think-arrow {
  font-size: 10px;
  transition: transform .2s;
  color: #999;
}
.think-panel.open .think-arrow { transform: rotate(180deg); }
.think-panel .think-title { font-weight: 600; color: #555; }
.think-panel .think-badge { font-size: 11px; color: #bbb; }
.think-panel .think-body {
  padding: 0 10px 8px;
  border-top: 1px dashed #eee;
  color: #666;
  font-size: 12px;
  line-height: 1.7;
  max-height: 300px;
  overflow-y: auto;
}
.think-panel .think-body .md :deep(p) { margin: 4px 0; }
/* 待发送图片预览 */
.pending-imgs { display: flex; flex-wrap: wrap; gap: 8px; margin: 0 auto 8px; flex: 0 0 100%; max-width: 860px; }
.pending-img { position: relative; }
.pending-img img { width: 64px; height: 64px; object-fit: cover; border-radius: 6px; border: 1px solid #e0e0e0; cursor: zoom-in; }
.pending-del { position: absolute; top: -6px; right: -6px; width: 18px; height: 18px; border-radius: 50%;
  background: rgba(0,0,0,.55); color: #fff; font-size: 12px; line-height: 18px; text-align: center; cursor: pointer; }
.pending-del:hover { background: #ff4d4f; }
/* 用户气泡内图片 */
.msg-imgs { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 6px; }
.msg-img { width: 96px; height: 96px; object-fit: cover; border-radius: 6px;
  border: 1px solid rgba(255,255,255,.35); cursor: zoom-in; }
.msg-img:hover { opacity: .85; }
/* 用户消息编辑按钮：悬浮显示；图标所在整行都可 hover 触发（图标仍靠右），移开消失 */
.msg-edit-row {
  position: absolute;
  top: calc(100% + 4px);      /* 紧贴气泡下边缘外侧 */
  left: 0; right: 0;           /* 占满整行宽度：整行 hover 都显示图标 */
  height: 24px;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: flex-end;   /* 用户消息在右侧，图标靠右 */
}
/* 多行自适应输入框：随内容增高（1~6 行），右侧留出发送/停止按钮位 */
.input-area {
  resize: none;
  padding: 2px 4px;
  font-size: 14px;
  line-height: 1.6;
  border: none;
  background: transparent;
}
.input-area:focus { border: none; box-shadow: none; }
/* 检索调试面板 */
.dbg-item { padding: 6px 8px; margin-bottom: 6px; border: 1px solid #f0f0f0; border-radius: 6px; background: #fafafa; }
/* 检索词元展示行 */
.dbg-terms { padding: 8px 10px; margin-bottom: 10px; border: 1px solid #e6f4ff; border-radius: 6px; background: #f0f7ff; }
.dbg-terms-label { font-size: 12px; color: #888; margin-right: 6px; }
.dbg-head { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.dbg-title { font-weight: 500; font-size: 13px; }
.dbg-snippet { margin-top: 3px; font-size: 12px; color: #888; word-break: break-all; }
/* 来源引用 chips */
/* 来源引用 chips 已由回答下方检索状态行合并区平替（.retrieval-merged），chips 样式移除 */
/* 引用弹窗知识块全文 */
.src-content { max-height: 45vh; overflow-y: auto; color: #333; line-height: 1.7; font-size: 14px;
  padding-right: 6px; scrollbar-width: thin; }
.src-content :deep(img) { max-width: 100%; max-height: 260px; border: 1px solid #e0e0e0;
  border-radius: 6px; display: block; margin: 8px 0; cursor: zoom-in; }
/* 引用弹窗：可拖拽伸缩（JS 手柄）——modal 变 flex 容器，body 内容跟随高度
   （antd modal teleport 到 body，scoped/:deep 均无法命中，必须 :global） */
.source-modal :global(.ant-modal) { display: flex; flex-direction: column; min-width: 420px; min-height: 240px; }
.source-modal :global(.ant-modal-content) { display: flex; flex-direction: column; flex: 1; overflow: hidden; position: relative; }
.source-modal :global(.ant-modal-body) { flex: 1; overflow: auto; min-height: 0; }
/* 右下角拖拽手柄（单三角，hover 高亮） */
.source-modal :global(.src-resizer) {
  position: absolute; right: 0; bottom: 0; z-index: 10;
  width: 0; height: 0;
  border-left: 14px solid transparent;
  border-bottom: 14px solid #bfbfbf;
  cursor: nwse-resize;
  opacity: .8;
}
.source-modal :global(.src-resizer:hover) { border-bottom-color: #1677ff; opacity: 1; }
/* 伸缩后内容区跟随弹窗高度（不再受固定 45vh 限制） */
.source-modal :global(.src-content) { max-height: none; height: 100%; }
/* 相关推荐 */
.related { margin-top: 10px; display: flex; flex-wrap: wrap; align-items: center; gap: 4px; }
.related-label { font-size: 12px; color: #888; margin-right: 4px; }
/* fail-loud 警示条：回答降级事件（检索失败/改写失败/截断等） */
.degradation-bar {
  margin-top: 8px; padding: 6px 10px; border-radius: 4px;
  background: #fffbe6; border: 1px solid #ffe58f; color: #ad6800;
  font-size: 12px; line-height: 1.6; display: flex; flex-wrap: wrap; gap: 4px 12px;
}
.degradation-item { display: inline-block; }
/* 多选删除：勾选框与底部选择栏 */
.msg-select-box { align-self: center; margin-right: 2px; }
.select-bar {
  display: flex; align-items: center; justify-content: center; gap: 14px;
  padding: 14px 16px; border-top: 1px solid #f0f0f0; background: #fff;
}
.select-count { font-size: 14px; color: #333; }
/* 消息时间（操作行行尾小字） */
.msg-time-inline { font-size: 11px; color: #c0c4cc; margin-left: 10px; white-space: nowrap; user-select: none; }
/* 操作区图标按钮（纯图标紧凑排布） */
.act-icon-btn {
  border: none; background: transparent; cursor: pointer;
  width: 24px; height: 24px; padding: 0; margin: 0;
  display: inline-flex; align-items: center; justify-content: center;
  border-radius: 4px; color: #8c8c8c; font-size: 14px;
  transition: color .15s, background .15s;
}
.act-icon-btn:hover:not(:disabled) { color: #1677ff; background: rgba(22,119,255,.08); }
.act-icon-btn.act-danger:hover:not(:disabled) { color: #ff4d4f; background: rgba(255,77,79,.08); }
.act-icon-btn:disabled { cursor: not-allowed; opacity: .4; }
.act-icon-btn.fb-active { color: #1677ff; }
/* 反馈已评价：按钮整体禁用（act-icon-btn:disabled 已置灰），不能再点任何一项 */
/* 检索状态行（回答下方合并区）：概览行 + 展开的检索词/来源条目，条目点击弹原文（平替原引用 chips） */
.retrieval-merged { margin-top: 8px; width: 100%; }
.retrieval-line { font-size: 12px; color: #999; user-select: none; cursor: pointer; }
.retrieval-line:hover { color: #1677ff; }
.rt-arrow { font-size: 10px; margin-left: 2px; transition: transform .15s; }
.rt-arrow.open { transform: rotate(180deg); }
.retrieval-detail {
  font-size: 12px; color: #888; background: #fafafa; border: 1px solid #f0f0f0;
  border-radius: 8px; padding: 8px 10px; margin: 4px 0 2px; width: 100%;
}
.rt-terms { margin-bottom: 6px; color: #666; }
.rt-ref { padding: 3px 0; border-top: 1px dashed #ececec; cursor: pointer; }
.rt-ref:hover { color: #1677ff; }
.rt-ref-tag { color: #1677ff; margin-right: 4px; }
.rt-snip { color: #bbb; margin-top: 2px; }
/* 检索进度提示（首 token 前的阶段状态：理解问题/检索资料/生成回答） */
.stage-hint { margin-top: 6px; font-size: 13px; color: #1677ff; display: flex; align-items: center; gap: 6px; }
/* 回答反馈（默认隐藏，hover 消息块时悬浮显示；保留整行可 hover 避免移过去按钮消失） */
.fb-row {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}
.fb-row :deep(.fb-active) { color: #1677ff; font-weight: 600; }
/* 流式中断重试 */
.retry-row { margin-top: 10px; }
/* 断连自动重试内联提示（AI 回答框下方） */
.retry-tip {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 6px;
  color: #d48806;            /* 警示黄 */
  font-size: 12px;
  background: #fffbe6;
  border: 1px solid #ffe58f;
  border-radius: 6px;
  padding: 4px 10px;
  width: fit-content;
}

/* 图片灯箱（z-index 须高于 antd modal 默认 1000，避免被引用/反馈弹窗盖住） */
.lightbox { position: fixed; inset: 0; background: rgba(0,0,0,.78); display: flex;
  align-items: center; justify-content: center; z-index: 2000; cursor: zoom-out; overflow: hidden; }
.lightbox img { max-width: 90vw; max-height: 90vh; border-radius: 4px;
  box-shadow: 0 4px 24px rgba(0,0,0,.4); cursor: grab; user-select: none;
  transition: transform .12s ease; will-change: transform; }
.lightbox img.dragging { cursor: grabbing; transition: none; }
.lightbox-close { position: fixed; top: 16px; right: 24px; font-size: 36px; color: #fff;
  cursor: pointer; line-height: 1; opacity: .85; user-select: none; }
.lightbox-close:hover { opacity: 1; }
.lightbox-hint { position: fixed; top: 20px; left: 50%; transform: translateX(-50%);
  color: #fff; font-size: 14px; background: rgba(0,0,0,.5); padding: 2px 10px; border-radius: 12px; user-select: none; }
.lightbox-tip { position: fixed; bottom: 20px; left: 50%; transform: translateX(-50%);
  color: rgba(255,255,255,.6); font-size: 12px; user-select: none; }
/* 灯箱上一张/下一张切换按钮 */
.lightbox-prev, .lightbox-next {
  position: fixed; top: 50%; transform: translateY(-50%);
  width: 44px; height: 44px; border-radius: 50%;
  border: 1px solid rgba(255,255,255,.35);
  background: rgba(0,0,0,.4); color: #fff;
  font-size: 26px; line-height: 1; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: background .15s, opacity .15s; z-index: 2001; user-select: none;
}
.lightbox-prev { left: 16px; }
.lightbox-next { right: 16px; }
.lightbox-prev:hover:not(:disabled), .lightbox-next:hover:not(:disabled) { background: rgba(0,0,0,.7); }
.lightbox-prev:disabled, .lightbox-next:disabled { opacity: .25; cursor: not-allowed; }
/* 灯箱图片计数 */
.lightbox-count { position: fixed; bottom: 44px; left: 50%; transform: translateX(-50%);
  color: rgba(255,255,255,.75); font-size: 13px;
  background: rgba(0,0,0,.45); padding: 2px 12px; border-radius: 12px; user-select: none; }
</style>

<style>
/* 撤销删除 toast（notification 挂在 body 下，scoped 样式覆盖不到，用全局块） */
.undo-toast .ant-notification-notice-message { color: #fff; }
.undo-toast .ant-notification-notice { background: rgba(0,0,0,.82); border-radius: 8px; }
.undo-toast-btn {
  border: none; background: transparent; cursor: pointer;
  color: #4d9bff; font-size: 14px; padding: 4px 8px;
}
.undo-toast-btn:hover { color: #78b3ff; }
</style>
