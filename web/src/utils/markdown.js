// ==================== Markdown 渲染公共模块 ====================
// 问答页（Chat.vue）与文档管理（Documents.vue）共用同一渲染管线：
// markdown-it + DOMPurify + highlight.js，图文交错、引用角标、代码复制按钮、富渲染。
// 纯函数设计：不依赖任何组件状态/消息上下文，产物 HTML 由调用方决定交互语义
// （Chat：角标→来源弹窗、图片→灯箱；Documents：仅展示）。
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/common'
import 'highlight.js/styles/github.css'
import { message } from 'ant-design-vue'
import { watch } from 'vue'
import { themeState } from './theme'
import { sandboxRun } from '../api.js'

// 图片 URL 兼容（/ai/ 前缀走 /proxy；data:/http 原样）
export const resolveImg = u => u.startsWith('data:') ? u : u.startsWith('http') ? u : '/proxy' + u.replace(/^\/ai/, '')

// 图片加载兜底：加载失败替换为灰底占位图（签名过期/文件缺失等场景避免裂图）
export const FALLBACK_IMG = 'data:image/svg+xml;utf8,' + encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="120"><rect width="100%" height="100%" fill="#f5f5f5"/><text x="50%" y="50%" fill="#999" font-size="14" text-anchor="middle" dominant-baseline="middle">图片加载失败</text></svg>')
export const onImgError = e => { e.target.onerror = null; e.target.src = FALLBACK_IMG }

// 复制图标 SVG（antd CopyOutlined / CheckOutlined 路径，render 内联生成无需 vRender 组件挂载）
const COPY_SVG = '<span class="anticon"><svg viewBox="64 64 896 896" width="1em" height="1em" fill="currentColor"><path d="M832 64H296c-4.4 0-8 3.6-8 8v56c0 4.4 3.6 8 8 8h496v688c0 4.4 3.6 8 8 8h56c4.4 0 8-3.6 8-8V96c0-17.7-14.3-32-32-32zM704 192H192c-17.7 0-32 14.3-32 32v530.7c0 8.5 3.4 16.6 9.4 22.6l173.3 173.3c12.9 12.9 30.2 20 48.4 20H704c17.7 0 32-14.3 32-32V224c0-17.7-14.3-32-32-32zM384 824l-128-128h128v128z"/></svg></span>'
const CHECK_SVG = '<span class="anticon"><svg viewBox="64 64 896 896" width="1em" height="1em" fill="currentColor"><path d="M912 190h-69.9c-9.8 0-19.1 4.5-25.1 12.2L404.7 724.5 207 474c-6.1-7.7-15.3-12.2-25.1-12.2H112c-6.7 0-12.7 4.1-15.2 10.3-2.4 6.3-1.1 13.4 3.6 18.3l235.3 258.5c12.5 13.7 32.5 14.9 46.5 2.7l446.5-424.3c6.4-6.1 9-15.1 5.7-23.4-2.9-7.2-9.8-12.1-17.4-12.1z"/></svg></span>'

// markdown-it 实例（配置与渲染管线整体一致）
// 安全：html:false（不渲染原始 HTML）+ DOMPurify 白名单双保险
const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,                 // 单换行即换行，贴近原手写行为
  langPrefix: 'hljs language-', // 代码块带 hljs 类才能着色
  highlight(str, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try { return hljs.highlight(str, { language: lang, ignoreIllegals: true }).value } catch (e) { /* 落空走兜底 */ }
    }
    return md.utils.escapeHtml(str) // 兜底必须手动转义（markdown-it 不自动转义 highlight 返回值）
  }
})
// 降级标题：h1→h2 ... h6 封顶（保持原手写 #→h2 行为，h1 留给页面）
md.renderer.rules.heading_open = t => `<h${Math.min(+t[0].tag[1] + 1, 6)}>`
md.renderer.rules.heading_close = t => `</h${Math.min(+t[0].tag[1] + 1, 6)}>`

// ==================== 富渲染：Mermaid / HTML·SVG 预览 / 沙盒运行 ====================
// 三个能力都挂在 fence 上（语言标签决定），渲染分两段：
//   ① 本模块同步产出**占位结构**（围栏 + 按钮 + 数据属性），不碰 mermaid/iframe 这些重活；
//   ② 异步部分（mermaid 绘图、iframe 预览、点击运行）由 enhanceMd / handleMdAction 驱动，
//      因为 v-html 只能同步塞字符串，且 mermaid 本身是 ~1MB 的懒加载依赖。
// 三条语言白名单与后端 SandboxController.LANGS 保持一致（前端不给按钮，后端也会拒）。
const MERMAID_LANG = 'mermaid'
const PREVIEW_LANGS = new Set(['html', 'htm', 'xml', 'svg'])
const RUNNABLE_LANGS = new Set(['python', 'python3', 'node', 'javascript', 'js', 'bash', 'sh', 'shell'])

// mermaid 懒加载单例：首次遇到图表时才 import，避免首屏为用不到的 1MB 付出代价
let mermaidMod = null
// mermaid 把配色烘进 svg 的 fill/stroke 里（不读 CSS 变量），所以主题必须跟着 html[data-theme] 走，
// 否则暗色下是「白底黑字」浮在深色面板上。themeChanges 交给调用方在切换主题时重挂（见 watchTheme）。
const isDark = () => {
  try { return document.documentElement.getAttribute('data-theme') === 'dark' } catch (e) { return false }
}
let mermaidTheme = null
const applyMermaidTheme = () => {
  if (!mermaidMod) return
  const want = isDark() ? 'dark' : 'default'
  if (want === mermaidTheme) return
  mermaidTheme = want
  mermaidMod.initialize({ startOnLoad: false, securityLevel: 'strict', theme: want, fontFamily: 'inherit' })
}
const loadMermaid = async () => {
  if (!mermaidMod) {
    mermaidMod = (await import('mermaid')).default
    // strict：mermaid 自己消毒图上的标签文本（用户输入会进节点名），不给它往 DOM 塞任意 HTML 的口子
    applyMermaidTheme()
  }
  return mermaidMod
}

/**
 * 主题切换后重画已渲染的图表（mermaid 配色烘进 svg，改 CSS 变量没用，必须重画）。
 * 容器上的 data-code/data-done 就是重画依据：清掉 done 即触发 enhanceDiagrams 再画一遍。
 * 由下面的 themeState 监听自动调用；也可由页面在容器就绪后手动调（首帧兜底）。
 */
export const redrawDiagrams = root => {
  applyMermaidTheme()
  root.querySelectorAll('.md-diagram[data-md-diagram][data-done]').forEach(el => { delete el.dataset.done })
  mermaidCache.clear()
  enhanceDiagrams(root)
}

// 主题切换自动重画：图表可能挂在任意消费方的容器里（本模块拿不到那些 ref），
// 所以监听全局主题状态后扫描 document——图表容器数量是消息级的小量，扫一遍可接受。
watch(() => themeState.value, () => {
  if (!mermaidMod) return          // 本次会话还没画过图，无需处理
  redrawDiagrams(document.body)
})

// 图 id 必须全局唯一且只含安全字符：mermaid 把它写进 svg 的 aria-labelledby/clip-path 引用里
let mmSeq = 0
const nextDiagramId = () => 'mmd-' + Date.now().toString(36) + '-' + (mmSeq++)

// 沙盒运行：同一代码块重复点时复用同一个输出槽，不叠加面板
const RUN_ICON = '<span class="anticon"><svg viewBox="64 64 896 896" width="1em" height="1em" fill="currentColor"><path d="M464 256c0-4.4-2.4-8.5-6.3-10.6s-8.3-1.9-12.1 0L161.6 415.4c-4.9 3-7.7 8.4-7.7 14.2v236.8c0 5.8 2.8 11.2 7.7 14.2l284 170c3.8 2.3 8.3 2.1 12.1 0s6.3-6.2 6.3-10.6V256z"/></svg></span>'
const PREVIEW_ICON = '<span class="anticon"><svg viewBox="64 64 896 896" width="1em" height="1em" fill="currentColor"><path d="M672 418H264c-4.4 0-8-3.6-8-8v-80c0-4.4 3.6-8 8-8h408c4.4 0 8 3.6 8 8v80c0 4.4-3.6 8-8 8zM400 440c-66.7 0-121.9-22.9-165.1-58.8C182.7 330.6 138 289.4 128 250.7c-.9-3.4-.9-6.8 0-10.2C138 201.4 182.7 160.2 234.9 109.7 278.1 73.8 333.3 50.9 400 50.9s121.9 22.9 165.1 58.8c52.2 50.5 96.9 109.7 106.9 148.4.9 3.4.9 6.8 0 10.2-10 38.7-54.7 97.9-106.9 148.4C521.9 417.1 466.7 440 400 440zM288 250.7c30.9 44.6 91.8 75.1 160 75.1 42.5 0 84.6-14.2 112-36.6-27.4-22.4-69.5-36.6-112-36.6-68.2 0-129.1 30.5-160 75.1-2.9 4.4-2.9 8.6 0 13zM496 250.7c0-4.4 2.9-8.6 0-13-30.9-44.6-91.8-75.1-160-75.1-42.5 0-84.6 14.2-112 36.6 27.4 22.4 69.5 36.6 112 36.6 68.2 0 129.1-30.5 160-75.1 2.9-4.4 2.9-8.6 0-13z"/></svg></span>'

// 围栏渲染：语言决定挂哪些能力。此处**只输出无交互的骨架**（容器 + data 属性），
// 按钮/详情节点一律交给后面的 DOM 后处理用 createElement 建——
// v-html 的字符串要过 DOMPurify，按钮与 details 的白名单不该由渲染层去赌。
const fence = (tokens, idx) => {
  const token = tokens[idx]
  const info = (token.info || '').trim()
  const lang = info.split(/\s+/)[0].toLowerCase()
  const code = token.content
  // Mermaid：源码放**文本子节点**（pre>code），不放 data-code 属性。
  // 原因（实测定位，不是猜）：DOMPurify 的 mXSS 守卫（SAFE_FOR_XML，分支 /((--!?|])>)|<\/(style|script|…)/i）
  // 会剥掉任何**值里含 `-->` / `]>`** 的属性，而 mermaid 源码恰恰满屏 `A --> B`、`A -.-> B`，
  // 放属性里等于属性必被剥光。文本节点不受该守卫影响，故源码走 pre>code，由 enhanceDiagrams 取 textContent。
  if (lang === MERMAID_LANG) {
    return `<div class="md-diagram" data-md-diagram>`
      + `<pre class="md-diagram-src-code"><code>${md.utils.escapeHtml(code)}</code></pre>`
      + `</div>`
  }
  const highlighted = md.options.highlight(code, lang)
  // data-lang 决定后处理挂哪些按钮；data-md-code 标记「围栏产物」，复制按钮注入据此跳过重复生成
  return `<div class="md-code" data-md-code data-lang="${md.utils.escapeHtml(lang)}">`
    + `<div class="md-code-head"><span class="md-code-lang">${md.utils.escapeHtml(lang)}</span></div>`
    + `<pre><code class="hljs${lang ? ' language-' + md.utils.escapeHtml(lang) : ''}">${highlighted}</code></pre>`
    + `<div class="md-code-slot"></div>`
    + `</div>`
}
md.renderer.rules.fence = fence

// 代码块复制（事件委托入口）：clipboard API 优先（localhost 安全上下文），execCommand 兜底
export const copyCode = btn => {
  const pre = btn.closest('pre')
  if (!pre) return
  const txt = (pre.querySelector('code')?.textContent ?? pre.innerText).trim()
  if (!txt) return
  const ok = () => {
    btn.classList.add('copied')
    btn.title = '已复制'
    btn.innerHTML = CHECK_SVG
    setTimeout(() => { btn.classList.remove('copied'); btn.title = '复制'; btn.innerHTML = COPY_SVG }, 1600)
  }
  const fallbackCopy = () => {
    try {
      const ta = document.createElement('textarea')
      ta.value = txt
      ta.setAttribute('readonly', '')
      ta.style.position = 'absolute'
      ta.style.left = '-9999px'
      ta.style.top = '0'
      document.body.appendChild(ta)
      ta.focus()
      ta.select()
      ta.setSelectionRange(0, txt.length)
      const flag = document.execCommand('copy')
      ta.remove()
      if (flag) ok()
      else message.error('复制失败，请手动复制')
    } catch (err) { message.error('复制失败，请手动复制') }
  }
  if (navigator.clipboard?.writeText) {
    navigator.clipboard.writeText(txt).then(ok).catch(fallbackCopy)
  } else {
    fallbackCopy()
  }
}

// 知识块原文：把无编号的 [图片]/[图片：描述] 按 images 顺序编号，供 renderMd() 渲染
export const prepKnowledgeContent = (content, images) => {
  if (!content) return ''
  if (!images.length) return content.replace(/\[图片(?:[：:][^\]]*)?\]/g, '')
  let i = 0
  return content.replace(/\[图片(?:[：:][^\]]*)?\]/g, () => {
    i++
    return i <= images.length ? `[图片${i}]` : '[图片]'
  })
}

// 核心渲染：content(markdown，含 [图片N] 占位) + images(数组) → 安全 HTML
// 产物结构：标题/表格/代码高亮/图文交错(md-img 居中 data-seq)/引用角标(ref-sup)/代码复制按钮(code-copy)
// 事件委托由调用方处理（点击 .code-copy 调 copyCode；.ref-sup/.md-img 按页面语义处理）
// 表格容错：LLM 常在标题/列表项后直接跟表格行（无空行），markdown-it 会把表格行吞进列表/段落变成纯文本。
// 逐行扫描：识别"表头行 + 分隔行(|---|)"表格起点，在其前补空行；分隔行后连续收集表体行。
// 显式区分表头/分隔/表体，避免误伤正常表格（上一版正则把"分隔行+表体行"误当表格起点，导致表体丢失）。
// 注意：分隔行必须含至少一个 '-' —— [\s:|-]+ 会把"全空单元格表体行"(|  |  |)误判为分隔行，
// 导致表格被拆碎（序号块预览中修订记录表掉行、剩余行渲染成原始竖线文本）。
const isTableRow = l => /^\s*\|.*\|\s*$/.test(l)
const isSepRow = l => /^\s*\|[\s:|-]*-[\s:|-]*\|\s*$/.test(l)
const ensureTableSpacing = text => {
  const lines = text.split('\n')
  const res = []
  let i = 0
  while (i < lines.length) {
    // 表格起点：当前是表格行 且 下一行是分隔行（表头+分隔）
    if (isTableRow(lines[i]) && i + 1 < lines.length && isSepRow(lines[i + 1])) {
      const prev = res.length ? res[res.length - 1] : ''
      if (prev.trim() !== '') res.push('') // 表格前补空行（已有空行则 prev 为空不补）
      res.push(lines[i])                    // 表头行
      res.push(lines[i + 1])                // 分隔行
      i += 2
      while (i < lines.length && isTableRow(lines[i]) && !isSepRow(lines[i])) {
        res.push(lines[i])                  // 表体行（连续表格行）
        i++
      }
      continue
    }
    res.push(lines[i])
    i++
  }
  return res.join('\n')
}

// ==================== 富渲染开关 ====================
// 「在沙盒中运行」要会话上下文（后端 scope = (sessionId, uid)），所以**默认不开**、由问答页显式传
// runnable:true 开。默认关而不是全局开+其它页面关：新增消费方忘了传参时最坏结果是没有按钮，
// 反过来则是每个新页面都挂一个点了必报错的按钮。
// 图表（Mermaid）与内联预览无状态，任何页面都开。
const RUNNABLE_DEFAULT = false

// 渲染缓存（LRU）：renderMd 是纯函数，但模板里直接 v-html="renderMd(...)" 调用——
// 任何响应式状态变化都会让 v-for 全列表重新跑完整管线（markdown-it + DOMPurify + TreeWalker +
// DOM 后处理），消息越多越卡。按输入 key 缓存后，已渲染消息零成本复用；流式段的 content
// 每次变化必然 miss，其渲染频率由调用方的节流控制（本缓存不管流式）。
const RENDER_CACHE = new Map()      // key → html；Map 保持插入序，命中即重插实现 LRU
const RENDER_CACHE_MAX = 200
const imagesKeyOf = imgs => (imgs && imgs.length ? imgs.join('\u0001') : '')

export const renderMd = (t, images = [], opts = {}) => {
  if (!t) return ''
  const runnable = opts.runnable === undefined ? RUNNABLE_DEFAULT : !!opts.runnable
  // key 必须带 runnable：同一段文本在两个页面可能一个挂运行按钮一个不挂，共用缓存会串
  const key = t + '\u0000' + imagesKeyOf(images) + '\u0000' + (runnable ? 'r1' : 'r0')
  const cached = RENDER_CACHE.get(key)
  if (cached !== undefined) {
    RENDER_CACHE.delete(key)
    RENDER_CACHE.set(key, cached)
    return cached
  }
  const html = renderMdInner(t, images, runnable)
  RENDER_CACHE.set(key, html)
  if (RENDER_CACHE.size > RENDER_CACHE_MAX) {
    RENDER_CACHE.delete(RENDER_CACHE.keys().next().value)
  }
  return html
}

const renderMdInner = (t, images = [], runnable = RUNNABLE_DEFAULT) => {
  // ① 预处理：图片标记 [图片N：描述]/[图片N] → markdown 图片占位（保留位置/顺序）
  // 只吞行内空白与标点，不吞换行：占位符后的空行承担"图片与后续块（表格/段落）分段"的语义，
  // 吞掉会把表格首行粘进图片行，表格永远无法成块渲染
  let pre = t.replace(/\[图片\s*(\d+)(?:[：:][^\]]*)?\][，。、；：！？ \u3000]*/g, '![img](__AI_IMG_$1__)')
  // ② 表格容错：表头前无空行时补空行，让表格独立渲染（已有空行不重复补）
  pre = ensureTableSpacing(pre)
  // ③ 清理末尾孤立竖线（LLM 回答结尾偶发残留" |"），避免渲染成一行竖线
  pre = pre.replace(/\n\s*\|\s*$/g, '')
  // ④ 渲染 + 消毒（放行内部图片占位前缀 __AI_IMG_，否则 DOMPurify 会剥掉其 src 导致图片丢失）
  // ADD_URI_SAFE_ATTR：DOMPurify 对白名单内属性的"值"也统一走 ALLOWED_URI_REGEXP 校验
  // （URI_SAFE_ATTRIBUTES 默认不含数值型属性）。上面的严格 URI 正则只认 __AI_IMG_/http/data，
  // start="6"/colspan 等纯数字值会被当非法 URI 剥掉 → 列表被图片打断后每段从 1 重排、合并单元格丢失。
  // 这些属性值天然不是 URI，走官方扩展点跳过 URI 校验（base 合并默认列表，不影响其它安全检查）。
  let html = DOMPurify.sanitize(md.render(pre), {
    ALLOWED_URI_REGEXP: /^(?:__AI_IMG_|https?:|data:image\/|mailto:|tel:)/i,
    ADD_URI_SAFE_ATTR: ['start', 'colspan', 'rowspan', 'span']
  })
  // ⑤ DOM 后处理（sanitize 之后新建元素不受白名单限制）
  const box = document.createElement('div')
  box.innerHTML = html
  // 图片：占位 → 真实 src + 居中 + data-seq；图片缺失保留原文 [图片N]
  box.querySelectorAll('img[src^="__AI_IMG_"]').forEach(img => {
    const m = (img.getAttribute('src') || '').match(/^__AI_IMG_(\d+)__$/)
    const n = m ? m[1] : ''
    const u = images[Number(n) - 1]
    if (!u) { img.replaceWith(document.createTextNode(`[图片${n}]`)); return }
    const wrap = document.createElement('div')
    wrap.style.textAlign = 'center'
    const real = document.createElement('img')
    real.className = 'md-img'
    real.src = resolveImg(u)
    real.alt = '文档图片'
    real.onerror = onImgError
    real.dataset.seq = n
    wrap.appendChild(real)
    img.replaceWith(wrap)
  })
  // 引用角标：[N] → sup（TreeWalker 跳过 pre/code/a，防误伤代码块/链接内的 [1]）
  const walker = document.createTreeWalker(box, NodeFilter.SHOW_TEXT)
  const targets = []
  while (walker.nextNode()) {
    const node = walker.currentNode
    if (node.nodeValue && /\[\d+\]/.test(node.nodeValue) && !node.parentElement.closest('pre,code,a')) targets.push(node)
  }
  for (const node of targets) {
    const frag = document.createDocumentFragment()
    node.nodeValue.split(/(\[\d+\])/).forEach(part => {
      const m = part.match(/^\[(\d+)\]$/)
      if (m) {
        const sup = document.createElement('sup')
        sup.className = 'ref-sup'
        sup.dataset.ref = m[1]
        sup.textContent = part
        frag.appendChild(sup)
      } else if (part) {
        frag.appendChild(document.createTextNode(part))
      }
    })
    node.parentNode.replaceChild(frag, node)
  }
  // 代码块复制按钮：只生成 HTML 结构（事件统一由调用方委托，避免 innerHTML 序列化丢失事件）
  box.querySelectorAll('pre').forEach(pre => {
    const btn = document.createElement('button')
    btn.type = 'button'
    btn.className = 'code-copy'
    btn.title = '复制'
    btn.innerHTML = COPY_SVG
    pre.appendChild(btn)
  })
  // 富渲染按钮：按 data-lang 挂「在沙盒中运行」/「内联预览」（后端 SandboxController.LANGS 同口径）
  box.querySelectorAll('.md-code[data-md-code]').forEach(codeBox => {
    const lang = (codeBox.dataset.lang || '').toLowerCase()
    const head = codeBox.querySelector('.md-code-head')
    if (runnable && RUNNABLE_LANGS.has(lang)) head.appendChild(actBtn('md-act-run', '在云端沙盒中运行（不影响本机）', RUN_ICON))
    if (PREVIEW_LANGS.has(lang)) head.appendChild(actBtn('md-act-preview', '内联预览', PREVIEW_ICON))
    // 无语言标注的围栏不留空标题栏（md-bare 让 CSS 把 pre 的圆角还回去）
    if (!lang) { head.remove(); codeBox.classList.add('md-bare') }
  })
  return box.innerHTML
}

const actBtn = (cls, title, icon) => {
  const b = document.createElement('button')
  b.type = 'button'
  b.className = 'md-act ' + cls
  b.title = title
  b.innerHTML = icon
  return b
}

// ==================== 富渲染的异步面 ====================

/**
 * 渲染已插入 DOM 的 Mermaid 图表。
 *
 * <p>为什么必须独立于 renderMd：v-html 只能同步塞字符串，而 mermaid 是懒加载的 ~1MB 依赖且
 * mermaid.render 本身是异步的。所以渲染层只留占位容器，页面在 nextTick 后调本函数把图补上。
 *
 * <p>幂等两层：容器已处理过（data-done 等于当前源码）直接跳过——流式输出期间渲染层会被反复重跑，
 * 同一份源码重复进 mermaid.render 纯属浪费；源码相同的容器（同一张图常在多条消息里出现）复用
 * mermaidCache 的 svg 串不再画一遍。
 * <p>失败也写 data-done：同一段源码画挂了就是挂了（幂等），重试留给「源码变了」的情形——
 * 这正是流式补全后能自愈、而已渲染完的坏图不反复刷解析错误的原因。
 */
export const enhanceDiagrams = async root => {
  const list = Array.from(root.querySelectorAll('.md-diagram[data-md-diagram]'))
  if (!list.length) return
  // 先取源码（还在 pre>code 里），再逐个画
  const jobs = list.map(el => ({ el, code: el.querySelector('.md-diagram-src-code code')?.textContent || '' }))
    .filter(j => j.el.dataset.done !== j.code)
  if (!jobs.length) return
  const mermaid = await loadMermaid()
  for (const j of jobs) await paintDiagram(mermaid, j.el, j.code)
}

// 源码 → svg 串（成功才留；LRU 上限防长会话无限增长）
const mermaidCache = new Map()
const MERMAID_CACHE_MAX = 60

const paintDiagram = async (mermaid, el, code) => {
  el.dataset.state = 'loading'
  // 画之前先把源码 pre 摘下来暂存（成功后要换成图，失败时要留在 details 里）
  const srcPre = el.querySelector('.md-diagram-src-code')
  let svg = mermaidCache.get(code)
  if (svg === undefined) {
    try {
      svg = (await mermaid.render(nextDiagramId(), code)).svg
      mermaidCache.set(code, svg)
      if (mermaidCache.size > MERMAID_CACHE_MAX) mermaidCache.delete(mermaidCache.keys().next().value)
    } catch (e) {
      el.textContent = ''
      showDiagramError(el, code, e)
      return
    }
  }
  el.dataset.state = 'ok'
  el.dataset.done = code
  el.textContent = ''
  el.insertAdjacentHTML('afterbegin', svg)
  el.appendChild(diagramSource(code, false, srcPre))
}

const showDiagramError = (el, code, e) => {
  el.dataset.state = 'err'
  el.dataset.done = code
  el.textContent = ''
  const tip = document.createElement('div')
  tip.className = 'md-diagram-err'
  tip.textContent = '图表渲染失败：' + (e && e.message ? String(e.message).split('\n')[0] : '语法错误')
  el.append(tip, diagramSource(code, true))
}

/** 源码折叠区：优先复用渲染层已产出的 pre>code（省一次重建，且高亮/转义已在管线里做过） */
const diagramSource = (code, open, reusePre) => {
  const det = document.createElement('details')
  det.className = 'md-diagram-src'
  det.open = !!open
  const sum = document.createElement('summary')
  sum.textContent = open ? '查看源码' : '图表源码'
  let pre = reusePre
  if (!pre) {
    pre = document.createElement('pre')
    const c = document.createElement('code')
    c.textContent = code
    pre.appendChild(c)
  }
  det.append(sum, pre)
  return det
}

/** HTML/SVG 代码块内联预览：sandbox iframe 载入 srcdoc，不给 same-origin（产物代码不能读本页） */
const togglePreview = box => {
  const slot = box.querySelector('.md-code-slot')
  if (!slot) return
  // 再点一次收起：先 revoke 掉上次的 blob URL，否则预览过的代码块会一直挂着个对象 URL
  const prevUrl = slot.dataset && slot.dataset.blobUrl
  if (prevUrl) { URL.revokeObjectURL(prevUrl); delete slot.dataset.blobUrl }
  if (slot.firstChild) { slot.textContent = ''; box.classList.remove('md-code-open'); return }
  const lang = (box.dataset.lang || '').toLowerCase()
  const code = box.querySelector('pre code')?.textContent || ''
  if (!code.trim()) return
  const frame = document.createElement('iframe')
  frame.className = 'md-preview-frame'
  // allow-scripts 但**不给** allow-same-origin：产物里的脚本能跑，但不能拿到父页 origin 去读 token/cookie
  frame.setAttribute('sandbox', 'allow-scripts')
  frame.setAttribute('title', '内容预览')
  const doc = lang === 'svg'
    // SVG 片段要包一层最小 HTML 才有个可渲染的文档
    ? `<!doctype html><meta charset="utf-8"><body style="margin:0;display:flex;justify-content:center">${code}</body>`
    : code
  frame.srcdoc = doc
  const bar = document.createElement('div')
  bar.className = 'md-preview-bar'
  // 「新窗口打开」用 blob: 而不是 data: —— 浏览器已禁止顶层导航到 data: URL，data: 链接点了没反应
  const open = document.createElement('a')
  const blobUrl = URL.createObjectURL(new Blob([doc], { type: 'text/html;charset=utf-8' }))
  slot.dataset.blobUrl = blobUrl
  open.href = blobUrl
  open.target = '_blank'
  open.rel = 'noopener'
  open.textContent = '新窗口打开'
  bar.appendChild(open)
  slot.append(bar, frame)
  box.classList.add('md-code-open')
}

/** 代码块「在沙盒中运行」：POST /sandbox/run，输出回填到该代码块下方的槽位 */
const runInSandbox = async (box, sessionId) => {
  const slot = box.querySelector('.md-code-slot')
  const btn = box.querySelector('.md-act-run')
  if (!slot || !btn) return
  const lang = (box.dataset.lang || '').toLowerCase()
  const code = box.querySelector('pre code')?.textContent || ''
  if (!code.trim()) return
  // 复用同一槽位：重复点覆盖上一次结果，不堆面板
  slot.textContent = ''
  box.classList.add('md-code-open')
  btn.disabled = true
  const panel = document.createElement('div')
  panel.className = 'md-run'
  const head = document.createElement('div')
  head.className = 'md-run-head'
  head.textContent = '运行中…'
  const out = document.createElement('pre')
  out.className = 'md-run-out'
  panel.append(head, out)
  slot.appendChild(panel)
  try {
    const r = await sandboxRun(sessionId, lang, code)
    const d = r.data || {}
    const exit = d.exitCode
    const dur = d.elapsedMs != null ? Math.round(d.elapsedMs) : null
    head.textContent = (exit === 0 ? '运行完成' : '运行失败（退出码 ' + exit + '）')
      + (dur != null ? ' · ' + dur + ' ms' : '')
      + (d.truncated ? ' · 输出过长已截断' : '')
    head.className = 'md-run-head ' + (exit === 0 ? 'ok' : 'err')
    out.textContent = d.output || '(无输出)'
  } catch (e) {
    head.textContent = '运行失败'
    head.className = 'md-run-head err'
    out.textContent = e && e.message ? e.message : String(e)
  } finally {
    btn.disabled = false
  }
}

/**
 * 「在沙盒中运行」按钮的事件入口（与 copyCode 同为页面级委托，页面在 click 里先判 .code-copy 再判本函数）。
 * ctx: { sessionId } ——沙盒执行要会话上下文；缺 sessionId 时运行按钮不响应（不静默失败，给出提示）。
 * 「内联预览」**不在此列**：它不需要页面上下文，由下面的全局委托统一处理（单一入口，避免两处都命中）。
 */
export const handleMdAction = (btn, ctx = {}) => {
  if (btn.classList.contains('md-act-run')) {
    const box = btn.closest('.md-code')
    if (!box) return true
    const sid = ctx.sessionId
    if (!sid) {
      message.warning('当前不在会话中，无法在沙盒中运行')
      return true
    }
    runInSandbox(box, sid)
    return true
  }
  return false
}

// ==================== 全局委托：内联预览 ====================
// 纯前端行为、不需要任何页面上下文，所以用 document 级委托统一处理：任何消费 renderMd 的页面
// （问答/文档预览/技能手册/分享页……）都自动能用，不会因为「某页忘了加委托」而留下一个点了没反应的按钮。
if (typeof document !== 'undefined') {
  document.addEventListener('click', e => {
    const btn = e.target && e.target.closest ? e.target.closest('.md-act-preview') : null
    const box = btn && btn.closest('.md-code')
    if (box) togglePreview(box)
  })
}
