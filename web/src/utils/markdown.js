// ==================== Markdown 渲染公共模块 ====================
// 问答页（Chat.vue）与文档管理（Documents.vue）共用同一渲染管线：
// markdown-it + DOMPurify + highlight.js，图文交错、引用角标、代码复制按钮。
// 纯函数设计：不依赖任何组件状态/消息上下文，产物 HTML 由调用方决定交互语义
// （Chat：角标→来源弹窗、图片→灯箱；Documents：仅展示）。
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/common'
import 'highlight.js/styles/github.css'
import { message } from 'ant-design-vue'

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

// 渲染缓存（LRU）：renderMd 是纯函数，但模板里直接 v-html="renderMd(...)" 调用——
// 任何响应式状态变化都会让 v-for 全列表重新跑完整管线（markdown-it + DOMPurify + TreeWalker +
// DOM 后处理），消息越多越卡。按输入 key 缓存后，已渲染消息零成本复用；流式段的 content
// 每次变化必然 miss，其渲染频率由调用方的节流控制（本缓存不管流式）。
const RENDER_CACHE = new Map()      // key → html；Map 保持插入序，命中即重插实现 LRU
const RENDER_CACHE_MAX = 200
const imagesKeyOf = imgs => (imgs && imgs.length ? imgs.join('\u0001') : '')

export const renderMd = (t, images = []) => {
  if (!t) return ''
  const key = t + '\u0000' + imagesKeyOf(images)
  const cached = RENDER_CACHE.get(key)
  if (cached !== undefined) {
    RENDER_CACHE.delete(key)
    RENDER_CACHE.set(key, cached)
    return cached
  }
  const html = renderMdInner(t, images)
  RENDER_CACHE.set(key, html)
  if (RENDER_CACHE.size > RENDER_CACHE_MAX) {
    RENDER_CACHE.delete(RENDER_CACHE.keys().next().value)
  }
  return html
}

const renderMdInner = (t, images = []) => {
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
  return box.innerHTML
}
