/**
 * 预览台：产物与工作区文件的统一预览出口。
 *
 * <p>为什么不是一个固定尺寸的弹窗：页面设计类产物要看的是**真实视口**下的布局、断点与首屏。
 * 早先给它开浏览器新标签页（能看到，但离开应用、回不去上下文、也没法比窄屏），再早是代码块那种
 * `min(1080px,92vw) × 86vh` 弹层（1080 不是任何真实设备宽度，86vh 把首屏折掉一截，外面还套遮罩与
 * 圆角，配色和留白的判断全被干扰）。两头都不对，所以这里给的是整屏舞台 + 可切换的视口宽度档位：
 * iframe 的 CSS 宽度真按档位来，媒体查询跑出来的字节数与真机一致。
 *
 * <p>分档判据在 utils/artifactView（一份），本模块只负责摆：page/image/pdf 直接把签名地址交给
 * iframe/img（服务端那份 CSP sandbox 头兜住模型脚本，见 ArtifactSandboxInterceptor），
 * md/table/text 按 id/路径取原文在应用里渲染。
 *
 * <p>入口是 document 级委托，不是每个页面自己接事件：六个入口面（PC 气泡、PC 右栏、H5 消息行、
 * H5 引用面板、产物页、工作区页）只要给锚点加 data-pv 就接上了。某页忘了加委托会留下一个
 * 「点了没反应的按钮」——那正是这轮要治的病，不该由接线方式再引入一次。锚点的 href 保持是那份
 * 真签名地址，所以 Ctrl/中键点击仍开真标签，右键「链接另存为」也还灵。
 */
import { artifactContent, projectFileContent } from '../api'
import { PV, artifactExt, hasViewportTiers, previewKindOf } from './artifactView'
import { copyCode, enhanceDiagrams, renderMd } from './markdown'

// 视口档位：375 是最常见的手机逻辑宽度，768 竖屏平板，1440 桌面基准。刻意不含 1080——
// 上一版弹层那个宽度既不是设备也不是一屏，看着像给了档位其实谁都对不上。
const TIERS = ['自适应', '375', '768', '1440']
const ZOOMS = [['fit', '适合宽度'], ['0.5', '50%'], ['0.75', '75%'], ['1', '100%']]
// 表格里一次摆出来的行数上限：再多就不是「看一眼」，该下载。总行数是全部数出来的，不受此帽影响。
const TABLE_ROWS_SHOWN = 300

let layer = null // { mask, body, canvas, frame, tier, zoom, reqSeq, onKey, onResize }

const el = (tag, cls, text) => {
  const n = document.createElement(tag)
  if (cls) n.className = cls
  if (text !== undefined) n.textContent = text
  return n
}

const fmtSize = bytes => {
  const b = Number(bytes)
  if (!b || b <= 0) return ''
  if (b < 1024) return b + ' B'
  if (b < 1024 * 1024) return (b / 1024).toFixed(b < 10240 ? 1 : 0) + ' KB'
  return (b / 1024 / 1024).toFixed(1) + ' MB'
}

// ==================== 关闭 / 单例 ====================

const closePreview = () => {
  if (!layer) return
  const { mask, onKey, onResize } = layer
  layer = null
  document.removeEventListener('keydown', onKey)
  window.removeEventListener('resize', onResize)
  document.body.classList.remove('ps-lock')
  // 直接摘掉整棵子树：iframe 随之销毁，长会话里不会攒下一堆还在跑脚本的活文档
  mask.remove()
}

// ==================== 视口舞台（page 档） ====================

/**
 * 把舞台尺寸按「档位宽 × 缩放」算到 iframe 上。
 *
 * <p>缩放用 transform 而不是改 width：改 width 会把媒体查询再跑一遍，用户想看的明明是
 * 「那个宽度长什么样、缩小点好放进屏幕」，两件事得分开。缩放后的可视高度按 1/z 给，
 * 让缩放后的 iframe 恰好铺满舞台——页面自己滚，这里不再套第二条滚动条。
 */
const applyStage = () => {
  if (!layer) return
  const { body, canvas, frame, tier, zoom } = layer
  const bw = body.clientWidth
  const bh = body.clientHeight
  const tierW = tier === '自适应' ? bw : Math.max(240, Number(tier) || bw)
  const z = tier === '自适应' ? 1 : (zoom === 'fit' ? Math.min(1, bw / tierW) : Number(zoom) || 1)
  canvas.style.width = Math.round(tierW * z) + 'px'
  canvas.style.height = bh + 'px'
  frame.style.width = tierW + 'px'
  frame.style.height = Math.round(bh / z) + 'px'
  frame.style.transform = z === 1 ? '' : `scale(${z})`
}

/** 只有非满宽档位才需要中性底衬，否则白页贴白舞台看不出边界 */
const markCanvasFramed = () => {
  if (!layer) return
  const framed = layer.tier !== '自适应'
  layer.body.classList.toggle('ps-framed', framed)
  layer.canvas.classList.toggle('ps-canvas-framed', framed)
}

// ==================== 三种读法（md / text / table） ====================

const LANG_BY_EXT = {
  py: 'python', sh: 'bash', bash: 'bash', zsh: 'bash', mjs: 'javascript', js: 'javascript',
  jsx: 'javascript', ts: 'typescript', tsx: 'typescript', md: 'markdown', yml: 'yaml',
  htm: 'html', conf: 'ini', properties: 'ini'
}

const fenceFor = code => {
  // 围栏必须比正文里最长的反引号串还长，否则文件内容里的 ``` 会提前关掉代码块
  const runs = code.match(/`+/g)
  let max = 0
  if (runs) for (const r of runs) max = Math.max(max, r.length)
  return '`'.repeat(Math.max(3, max + 1))
}

const docClasses = ['ps-doc', 'md']

const renderInto = async (host, html, kind) => {
  host.innerHTML = html
  if (kind === PV.MD || kind === PV.TEXT) {
    // mermaid 图与代码块的外框都靠这一步补（与回答正文同一条管线）；失败已在容器内就地提示
    enhanceDiagrams(host).catch(() => {})
  }
}

const buildTable = (rows, shownCount) => {
  const wrap = el('div', 'ps-table-wrap')
  const table = el('table', 'ps-table')
  const head = document.createElement('thead')
  const headRow = document.createElement('tr')
  for (const c of rows[0] || []) {
    const th = document.createElement('th')
    th.textContent = c
    headRow.appendChild(th)
  }
  head.appendChild(headRow)
  table.appendChild(head)
  const tbody = document.createElement('tbody')
  for (let i = 1; i <= shownCount; i++) {
    const r = rows[i]
    if (!r) break
    const tr = document.createElement('tr')
    for (const c of r) {
      const td = document.createElement('td')
      td.textContent = c
      tr.appendChild(td)
    }
    tbody.appendChild(tr)
  }
  table.appendChild(tbody)
  wrap.appendChild(table)
  return wrap
}

/**
 * 分隔符取值解析（RFC4180 那一套：引号包裹、"" 转义、CRLF）。
 *
 * <p>不引第三方解析器：这里要的只是「读一眼」，引号与换行两条规则手写 30 行，
 * 换来的是零依赖与不用为一份表格再打一个包。
 */
const parseDelimited = (text, sep) => {
  const rows = []
  let row = []
  let field = ''
  let quoted = false
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (quoted) {
      if (c === '"') {
        if (text[i + 1] === '"') { field += '"'; i++ } else quoted = false
      } else field += c
      continue
    }
    if (c === '"') { quoted = true; continue }
    if (c === sep) { row.push(field); field = ''; continue }
    if (c === '\r') continue
    if (c === '\n') { row.push(field); rows.push(row); row = []; field = ''; continue }
    field += c
  }
  if (field !== '' || row.length) { row.push(field); rows.push(row) }
  return rows
}

const bodyHint = (total, shown, truncated, cap) => {
  const parts = []
  if (total !== null) {
    const n = total.toLocaleString('zh-CN')
    // 内容被上限截断时行数只算到截断点，这时说「共 X 行」是假话
    parts.push(truncated ? `已解析 ${n} 行` : (total === 1 ? '共 1 行' : `共 ${n} 行`))
  }
  if (total > shown) parts.push(`仅预览前 ${shown.toLocaleString('zh-CN')} 行`)
  if (truncated) parts.push(`内容超出 ${Math.round(cap / 1024)}KB 预览上限，后半部分没取回来，请下载看全`)
  return parts.join(' · ')
}

const showTextBody = async (src, kind) => {
  const { body } = layer
  const seq = ++layer.reqSeq
  body.textContent = ''
  const hint = el('div', 'ps-note', '正在读取内容…')
  body.appendChild(hint)
  let data
  try {
    data = src.artifactId ? await artifactContent(src.artifactId)
      : await projectFileContent(src.projectId, src.path)
  } catch (e) {
    if (!layer || seq !== layer.reqSeq) return
    // 读不到就明说并给出出口，别留一张白页让用户以为是文件本身坏了
    body.textContent = ''
    const err = el('div', 'ps-error')
    err.appendChild(el('p', null, '内容读取失败：' + (e && e.message ? e.message : '未知错误')))
    const acts = el('div', 'ps-error-acts')
    const retry = el('button', 'ps-btn', '重试')
    retry.type = 'button'
    retry.addEventListener('click', () => showTextBody(src, kind))
    acts.appendChild(retry)
    body.appendChild(err)
    body.appendChild(acts)
    return
  }
  if (!layer || seq !== layer.reqSeq) return
  body.textContent = ''
  // request() 回的是整个信封 {success,msg,data}，载荷在 data.data 里——直接读 data.content
  // 得到 undefined，三档读字全部静默变成「空白预览」，看着像文件本身是空的
  const file = (data && data.data) || {}
  const content = String(file.content ?? '')
  const truncated = !!file.truncated
  const host = el('div', docClasses.join(' '))
  const fileExt = { ext: file.ext, filename: file.filename }
  if (kind === PV.MD) {
    // literal：产物正文里的 [图片1]、[2] 是文件自己的字，不许按聊天消息那套角标规则改写
    await renderInto(host, renderMd(content, [], { runnable: false, preview: false, literal: true }), PV.MD)
  } else if (kind === PV.TABLE) {
    const rows = parseDelimited(content, artifactExt(fileExt) === 'tsv' ? '\t' : ',')
    const total = Math.max(0, rows.length - 1)
    if (!rows.length) host.appendChild(el('div', 'ps-note', '这份文件没有内容'))
    else if (rows.length === 1) host.appendChild(el('div', 'ps-note', '这份表只有表头，没有数据行'))
    else host.appendChild(buildTable(rows, Math.min(total, TABLE_ROWS_SHOWN)))
    // 「首行按表头」这句必须说：模型产出的 csv 常没表头，不说用户会以为第一行数据被吞了
    const tip = [bodyHint(total, Math.min(total, TABLE_ROWS_SHOWN), truncated, file.cap),
      total ? '首行按表头显示' : ''].filter(Boolean).join(' · ')
    if (tip) host.appendChild(el('div', 'ps-note', tip))
  } else {
    const ext = artifactExt(fileExt)
    const lang = LANG_BY_EXT[ext] || ext || 'text'
    const f = fenceFor(content)
    await renderInto(host, renderMd(`${f}${lang}\n${content}\n${f}`,
      [], { runnable: false, preview: false, literal: true }), PV.TEXT)
  }
  body.appendChild(host)
  const foot = kind === PV.TABLE ? '' : bodyHint(null, 0, truncated, file.cap)
  if (foot) body.appendChild(el('div', 'ps-note ps-note-foot', foot))
}

// ==================== 舞台搭建 ====================

const openStage = (anchor, src, kind) => {
  closePreview()
  const mask = el('div', 'ps-mask')
  const modal = el('div', 'ps-modal')
  modal.setAttribute('role', 'dialog')
  modal.setAttribute('aria-modal', 'true')
  modal.setAttribute('aria-label', '预览 ' + src.name)

  // ---- 顶栏 ----
  const bar = el('div', 'ps-bar')
  const titleWrap = el('div', 'ps-title')
  titleWrap.appendChild(el('span', 'ps-name', src.name))
  const size = fmtSize(src.size)
  if (size) titleWrap.appendChild(el('span', 'ps-meta', size))
  bar.appendChild(titleWrap)

  const tiers = el('div', 'ps-tiers')
  const tierBtns = []
  const zoomSel = el('select', 'ps-zoom')
  const widthInput = el('input', 'ps-width')
  widthInput.type = 'number'
  widthInput.min = '240'
  widthInput.max = '4000'
  widthInput.step = '1'
  widthInput.title = '自定义视口宽度（像素）'
  widthInput.placeholder = '宽'

  layer = { mask, modal, body: null, canvas: null, frame: null, tier: '自适应', zoom: 'fit', reqSeq: 0 }

  const setTier = t => {
    layer.tier = t
    // 自定义宽度不属于任何一颗档位钮：这时点亮「自定义」，否则一排全灭看着像没选中
    const isCustom = t !== '自适应' && !TIERS.some(x => String(x) === String(t))
    for (const b of tierBtns) b.classList.toggle('on', !isCustom && b.dataset.tier === String(t))
    custom.classList.toggle('on', isCustom)
    widthInput.value = t === '自适应' ? '' : String(t)
    markCanvasFramed()
    applyStage()
  }
  TIERS.forEach(t => {
    const b = el('button', 'ps-tier', t)
    b.type = 'button'
    b.dataset.tier = t
    b.addEventListener('click', () => setTier(t))
    tierBtns.push(b)
    tiers.appendChild(b)
  })
  const custom = el('button', 'ps-tier', '自定义')
  custom.type = 'button'
  custom.dataset.tier = '自定义'
  custom.addEventListener('click', () => {
    const v = Number(widthInput.value) || 375
    widthInput.value = String(v)
    setTier(v)
  })
  tiers.appendChild(custom)
  widthInput.addEventListener('change', () => {
    const v = Number(widthInput.value)
    if (v >= 240) setTier(v)
  })
  tiers.appendChild(widthInput)
  for (const [v, label] of ZOOMS) {
    const o = document.createElement('option')
    o.value = v
    o.textContent = label
    zoomSel.appendChild(o)
  }
  zoomSel.value = 'fit'
  zoomSel.title = '缩放（不改变页面自己的布局宽度）'
  zoomSel.addEventListener('change', () => {
    layer.zoom = zoomSel.value
    applyStage()
  })
  const zoomWrap = el('div', 'ps-zoom-wrap')
  zoomWrap.appendChild(el('span', 'ps-meta', '缩放'))
  zoomWrap.appendChild(zoomSel)
  if (hasViewportTiers(kind)) {
    bar.appendChild(tiers)
    bar.appendChild(zoomWrap)
  } else {
    tiers.style.display = 'none'
    zoomWrap.style.display = 'none'
  }

  const acts = el('div', 'ps-acts')
  const mk = (cls, text, title) => {
    const a = el('a', cls, text)
    a.href = anchor.href
    a.title = title
    a.target = '_blank'
    a.rel = 'noopener'
    acts.appendChild(a)
    return a
  }
  mk('ps-btn', '新标签', '在浏览器新标签页打开（真实整屏视口）')
  const dl = mk('ps-btn', '下载', '下载这份文件')
  dl.setAttribute('download', src.name)
  dl.target = '_self'
  const close = el('button', 'ps-close', '×')
  close.type = 'button'
  close.title = '关闭（Esc）'
  close.addEventListener('click', closePreview)
  acts.appendChild(close)
  bar.appendChild(acts)

  // ---- 主体 ----
  const body = el('div', 'ps-body')
  layer.body = body
  modal.appendChild(bar)
  modal.appendChild(body)
  mask.appendChild(modal)

  if (kind === PV.PAGE || kind === PV.PDF) {
    const canvas = el('div', 'ps-canvas')
    const frame = document.createElement('iframe')
    // 沙盒口径与代码块预览一致：给脚本，不给同源——产物是模型写的 HTML，同源性一给就等于把
    // 本域登录令牌交给它。服务端那份 CSP sandbox 头是第二道，两边不同口径各自兜。
    frame.setAttribute('sandbox', 'allow-scripts')
    frame.setAttribute('title', src.name)
    frame.className = 'ps-frame'
    frame.src = anchor.href
    canvas.appendChild(frame)
    body.appendChild(canvas)
    layer.canvas = canvas
    layer.frame = frame
    frame.addEventListener('load', applyStage)
  } else if (kind === PV.IMAGE) {
    const wrap = el('div', 'ps-img-wrap')
    const img = document.createElement('img')
    img.className = 'ps-img'
    img.alt = src.name
    img.src = anchor.href
    wrap.appendChild(img)
    body.appendChild(wrap)
    // 点一下在「适合屏幕」与 1:1 之间切：设计稿要看像素与字号，缩放态看不出锯齿
    img.addEventListener('load', () => {
      const natural = img.naturalWidth
      const label = el('div', 'ps-note ps-note-foot', `${natural} × ${img.naturalHeight} px · 点击切换适合/实际大小`)
      body.appendChild(label)
      wrap.addEventListener('click', () => wrap.classList.toggle('ps-img-actual'))
    })
    img.addEventListener('error', () => {
      body.textContent = ''
      body.appendChild(el('div', 'ps-error', '图片读取失败：地址可能已过期，请刷新页面或点「新标签」重试'))
    })
  } else {
    showTextBody(src, kind)
  }

  const onKey = e => { if (e.key === 'Escape') closePreview() }
  const onResize = () => applyStage()
  layer.onKey = onKey
  layer.onResize = onResize
  document.addEventListener('keydown', onKey)
  window.addEventListener('resize', onResize)

  document.body.appendChild(mask)
  document.body.classList.add('ps-lock')
  setTier('自适应')
  close.focus?.()
}

// ==================== 全局委托 ====================
// 锚点带 data-pv 即接入；href 仍是那份真签名地址，所以带修饰键的点击一律放行给浏览器
// （Ctrl/⌘ 点击与中键开真标签，右键另存照常），只有左键普通点击才收进预览台。
if (typeof document !== 'undefined') {
  document.addEventListener('click', e => {
    if (e.defaultPrevented || e.button !== 0) return
    if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
    const a = e.target && e.target.closest ? e.target.closest('a[data-pv]') : null
    if (!a) return
    const d = a.dataset
    const src = {
      name: d.pvName || 'preview',
      size: d.pvSize || '',
      artifactId: d.pvId || '',
      projectId: d.pvProject || '',
      path: d.pvPath || ''
    }
    const kind = previewKindOf({ ext: d.pvExt, filename: src.name })
    // 判据与按钮是同一次调用：v-if 说能预览、这里说不能，就是同一份东西两个页面不同表现
    if (kind === PV.NONE) return
    e.preventDefault()
    openStage(a, src, kind)
  })
  // 读字档里的代码块复制按钮：正文点击委托归各页面自己（它要带来源/图片上下文），
  // 预览台这份在 document 级补一次，只认 .code-copy 且不碰别的类名。
  document.addEventListener('click', e => {
    const btn = e.target && e.target.closest ? e.target.closest('.ps-doc .code-copy') : null
    if (btn) { e.preventDefault(); copyCode(btn) }
  })
}
