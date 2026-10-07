// ==================== 回答正文的查看器（图片灯箱 / 引用来源弹窗 / 角标悬浮卡）====================
//
// 为什么是模块级单例而不是某个组件的私有状态：这三样都是「整页一个」的浮层。
// 每条回答各挂一份 modal + lightbox，多图切换会互相打架、原文详情也会各发一遍请求；
// 而正文本身有多处渲染点（时间线的 text 段、无时间线的整条正文、思考面板、来源弹窗内的原文），
// 触发点必须共用同一个浮层。
//
// 分工（三处只写一次，主聊天页 / 智能体分享页 / 会话只读分享页共用）：
//   · 本文件：状态 + 动作（含正文的点击与悬浮委托）
//   · components/AnswerViewerHost.vue：浮层 DOM，每页挂一次
//   · components/AnswerBody.vue：一段正文（v-html + 触发），图片/引用/代码块能力全在这里接
//
// 新增一种正文内交互（比如新的围栏语言按钮、新的角标形态）只改这三处之一，
// 不必再在分享页补一遍——此前分享页把 renderMd 的 images 写成 []、角标既无样式也无点击，
// 就是「各写一份正文渲染」漏出来的。
import { ref, computed, watch } from 'vue'
import { getKnowledgeDetail } from '../api'
import { isLoggedIn } from '../utils/auth'
import { resolveImg, copyCode, handleMdAction } from '../utils/markdown'
import { externalOrigin } from './projections'

// ==================== 图片灯箱 ====================
const previewList = ref([])
const previewIndex = ref(0)
const previewUrl = computed(() => previewList.value[previewIndex.value] || '')
const zoom = ref(1)
const offset = ref({ x: 0, y: 0 })
const dragState = ref(null)

const resetView = () => { zoom.value = 1; offset.value = { x: 0, y: 0 } }
const prevImg = () => { if (previewIndex.value > 0) { previewIndex.value--; resetView() } }
const nextImg = () => { if (previewIndex.value < previewList.value.length - 1) { previewIndex.value++; resetView() } }
const closeLightbox = () => { previewList.value = []; previewIndex.value = 0; resetView(); dragState.value = null }

/**
 * 打开灯箱。urls 必须是可直接赋给 src 的地址（调用方自己 resolveImg）。
 * index 越界回落首图：正文里的 [图片N] 序号可能多于本轮实际下发的图（模型引用了被裁掉的段）。
 */
const openImages = (urls, index = 0) => {
  const list = Array.isArray(urls) ? urls.filter(Boolean) : []
  if (!list.length) return
  previewList.value = list
  previewIndex.value = index >= 0 && index < list.length ? index : 0
  resetView()
}

const onWheel = e => {
  let factor = Math.pow(1.08, -e.deltaY / 100)
  if (factor > 1.3) factor = 1.3
  if (factor < 1 / 1.3) factor = 1 / 1.3
  zoom.value = Math.min(8, Math.max(0.25, zoom.value * factor))
}
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

// 键盘只在灯箱打开时挂：Esc/方向键在关着的时候属于页面（主聊天页的 Esc 是「停止生成」两段式）
const onKeydown = e => {
  if (e.key === 'Escape') closeLightbox()
  else if (e.key === 'ArrowLeft') prevImg()
  else if (e.key === 'ArrowRight') nextImg()
}
watch(previewUrl, v => {
  if (v) window.addEventListener('keydown', onKeydown)
  else window.removeEventListener('keydown', onKeydown)
})

// ==================== 引用来源详情弹窗 ====================
const sourceVisible = ref(false)
const sourceLoading = ref(false)
const sourceTitle = ref('')
const sourceContent = ref('')
const sourceSnippet = ref('')
const sourceImages = ref([])
/** 联网来源（origin=WEB）的原网页地址：库内来源为空，此时弹窗不显示「打开原网页」 */
const sourceUrl = ref('')

const openSource = async s => {
  if (!s) return
  sourceUrl.value = ''
  // 外部来源（联网/MCP）没有库内文档可打开：getKnowledgeDetail(knowledgeId) 必然失败，
  // 走这里展示站点/标题/摘要并给出原网页链接（否则用户点角标得到空白弹窗）
  if (externalOrigin(s)) {
    sourceTitle.value = (s.siteName || (s.origin === 'MCP' ? 'MCP 来源' : '联网来源')) + (s.title ? ' §' + s.title : '')
    sourceSnippet.value = s.snippet || '（该来源未提供摘要）'
    sourceImages.value = []
    sourceContent.value = ''
    sourceLoading.value = false
    sourceVisible.value = true
    sourceUrl.value = s.url || ''
    return
  }
  sourceTitle.value = (s.fileName || (s.docId ? '来源文档不可用' : '手动补充的知识')) + (s.title ? ' §' + s.title : '')
  sourceSnippet.value = s.snippet || '（无原文片段）'
  sourceImages.value = Array.isArray(s.images) ? s.images : []
  sourceContent.value = ''
  sourceVisible.value = true
  // 免登录访客不请求知识块原文：/knowledge/{id} 要登录态，拿 401 会撞上全局「登录已失效 →
  // 跳 /login」闸门，把正在提问的游客一脚踢出分享页。这里直接停在片段（正文里引用的就是它）。
  if (!isLoggedIn()) {
    sourceLoading.value = false
    return
  }
  sourceLoading.value = true
  try {
    const r = await getKnowledgeDetail(s.knowledgeId)
    if (r.success && r.data) {
      sourceContent.value = r.data.content || ''
      if (Array.isArray(r.data.images)) sourceImages.value = r.data.images
      if (r.data.title) sourceTitle.value = (s.fileName || (s.docId ? '来源文档不可用' : '手动补充的知识')) + ' §' + r.data.title
    }
  } catch (e) { /* 接口失败回退 snippet */ }
  finally { sourceLoading.value = false }
}

// ==================== 角标悬浮卡 ====================
// 自绘浮层，替代原生 title——原生 title 有约 1s 延迟、不可样式化、换行不渲染、也放不下相关度。
// 展示序号 + 来源文件 + 章节 + 片段 + 相关度，「查看原文」跳来源弹窗。
const refTip = ref(null)
const refTipPos = ref({ left: 12, top: 0, above: false })
let refTipTimer = null
/** 相关度分值属调参排障信息，默认不对普通用户露出：由页面按配置（chat.retrievalDebugEnabled）打开 */
const debugVisible = ref(false)

const refCardStyle = computed(() => {
  const w = Math.min(400, Math.max(260, window.innerWidth - 24))
  const left = Math.max(12, Math.min(refTipPos.value.left, window.innerWidth - w - 12))
  return {
    left: left + 'px',
    top: refTipPos.value.top + 'px',
    width: w + 'px',
    transform: refTipPos.value.above ? 'translateY(-100%)' : 'none'
  }
})
const cancelCloseRefTip = () => { if (refTipTimer) { clearTimeout(refTipTimer); refTipTimer = null } }
const closeRefTip = () => { cancelCloseRefTip(); refTip.value = null; hoveredRef.value = null }
/** 延迟关闭：角标 → 浮层之间有一段空隙，立即关闭会闪 */
const scheduleCloseRefTip = () => {
  cancelCloseRefTip()
  refTipTimer = setTimeout(() => { refTip.value = null; hoveredRef.value = null }, 160)
}

/** 正文角标与右栏来源列表的联动状态（页面据此高亮对应条目 / 反向高亮角标） */
const hoveredRef = ref(null)

/**
 * @param el      角标元素（定位锚点）
 * @param sources 该条正文自己的来源数组（按 sources[n-1] 取，不再靠 DOM 序号反查整页消息）
 * @param n       角标序号
 */
const showRefTip = (el, sources, n) => {
  const src = (sources || [])[n - 1]
  const r = el.getBoundingClientRect()
  const above = r.bottom + 240 > window.innerHeight
  refTipPos.value = { left: r.left, top: above ? r.top - 6 : r.bottom + 6, above }
  if (!src) {
    // 来源未随本轮下发（如工具模式：模型引用的是 searchKnowledge 工具返回的【引用N】，
    // 该结果在工具卡片里而不在 sources）——不静默无响应，给明确说明
    refTip.value = {
      ref: n, fileName: '', title: '',
      snippet: '本轮的引用来源未随消息下发。若本轮调用了「知识库检索」工具，可在工具卡片中查看检索到的原文片段。',
      score: null, scoreLabel: '', src: null
    }
    return
  }
  refTip.value = {
    ref: n,
    // 外部来源（联网/MCP）没有 fileName/docId，用站点名；MCP 无相关度分，标签区留空
    fileName: externalOrigin(src)
      ? (src.siteName || (src.origin === 'MCP' ? 'MCP 来源' : '联网来源'))
      : (src.fileName || (src.docId ? '来源文档不可用' : '手动补充的知识')),
    title: src.title || '',
    snippet: src.snippet || '（无原文片段）',
    score: (src.rerankScore != null ? src.rerankScore : src.score),
    scoreLabel: src.origin === 'WEB' ? '服务商相关度' : (src.rerankScore != null ? '重排相关度' : (src.origin === 'MCP' ? '' : '检索融合分')),
    src
  }
}
const refTipOpenSource = () => {
  const s = refTip.value && refTip.value.src
  closeRefTip()
  if (s) openSource(s)
}

// ==================== 正文的点击 / 悬浮委托 ====================

/**
 * 一段正文（v-html 产物）的点击语义：代码复制 / 沙盒运行与内联预览 / 引用角标 / 图片。
 * ctx 由渲染该正文的组件提供（图片与来源数组、沙盒所需的会话号）。
 * onCitation / onImages 是留给页面的出口：移动壳用的是底部抽屉与简化灯箱，不套 PC 浮层；
 * 不传就走本模块共用的来源弹窗与灯箱（PC 聊天页与两个分享页）。
 */
const handleBodyClick = (e, ctx = {}) => {
  const t = e.target
  if (t && t.closest) {
    const copyBtn = t.closest('.code-copy')
    if (copyBtn) { copyCode(copyBtn); return }
    // 富渲染按钮（沙盒运行 / 内联预览）：紧跟复制按钮判定，命中即消费
    const mdAct = t.closest('.md-act')
    if (mdAct && handleMdAction(mdAct, { sessionId: ctx.sessionId || '' })) return
  }
  if (t && t.classList && t.classList.contains('ref-sup')) {
    const src = (ctx.sources || [])[Number(t.dataset.ref) - 1]
    if (!src) return
    if (ctx.onCitation) ctx.onCitation(src)
    else openSource(src)
    return
  }
  if (t && t.tagName && t.tagName.toLowerCase() === 'img') {
    const imgs = ctx.images || []
    const seq = Number(t.dataset.seq || 0)
    const urls = imgs.length ? imgs.map(resolveImg) : [t.getAttribute('src')]
    const index = imgs.length && seq > 0 && seq <= imgs.length ? seq - 1 : 0
    if (ctx.onImages) ctx.onImages(urls, index)
    else openImages(urls, index)
  }
}

/** 正文悬浮：命中角标则出悬浮卡并联动右栏，离开角标即延迟收起 */
const handleBodyHover = (e, sources) => {
  const t = e.target
  if (!t || !t.classList) return
  if (!t.classList.contains('ref-sup')) { scheduleCloseRefTip(); return }
  cancelCloseRefTip()
  const n = Number(t.dataset.ref)
  hoveredRef.value = n
  showRefTip(t, sources, n)
}

export {
  previewList, previewIndex, previewUrl, zoom, offset, dragState,
  openImages, closeLightbox, prevImg, nextImg, resetView, onWheel,
  onImgMouseDown, onImgMouseMove, onImgMouseUp,
  sourceVisible, sourceLoading, sourceTitle, sourceContent, sourceSnippet, sourceImages, sourceUrl, openSource,
  refTip, refTipPos, refCardStyle, debugVisible, showRefTip, closeRefTip, cancelCloseRefTip,
  scheduleCloseRefTip, refTipOpenSource, hoveredRef,
  handleBodyClick, handleBodyHover
}
