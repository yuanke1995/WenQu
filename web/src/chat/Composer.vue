<script setup>
/**
 * 聊天输入框（桌面端）：正文与「引用类」条目在**同一条文字流**里。
 *
 * 为什么不是 textarea：textarea 的 value 只能是字符，附件/技能/@ 引用这些条目只能摆在正文上方
 * 另起一行，于是「这一条挨着哪个字」没有表达，全选复制也永远拿不到它们。要内联就得让条目成为
 * 文档里的节点——contenteditable + contenteditable=false 的原子 chip，浏览器负责整颗删除与光标穿过。
 *
 * 三条边界（改这个文件前先读）：
 *  1. **chip 不产出字符**。序列化出来的正文与发给模型的 question 完全一致，chip 走各自的字段
 *     （attachments / mentions / skills / historyRefs / 计划模式开关），位置只是呈现。
 *  2. **DOM 是真源，父级的数组是数据**。谁在/谁的先后看 DOM；名字、体积、上传进度、fileId 看数组。
 *     两边靠 sync（数组→DOM 增删）与 update:keys（DOM→数组剪枝）对账，所以 Backspace 删掉一颗
 *     chip 等于取消那条引用，而在面板里取消勾选也会把 chip 摘掉。
 *  3. **Vue 不渲染编辑区内部**。chip 节点全部命令式创建：Vue 一旦 patch 这块子树，就会把用户刚打的
 *     字和浏览器刚做的换行一起抹掉。图标靠模板里那排隐藏节点 clone 出来（避免把 SVG 字符串搬进来）。
 */
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
// 图标必须显式具名导入：unplugin-vue-components 的 AntDesignVueResolver 只管 antd 的 a-xxx，
// icons-vue 不在它的解析表里——不 import 就退化成运行期 resolveComponent，找不到组件时既不报错也不画图标
import { FileTextOutlined, DatabaseOutlined, RobotOutlined, HistoryOutlined,
         OrderedListOutlined, PictureOutlined } from '@ant-design/icons-vue'

const props = defineProps({
  /** 正文（不含 chip） */
  modelValue: { type: String, default: '' },
  /** 占位文案 */
  placeholder: { type: String, default: '' },
  /** key → 描述对象 {kind,name,sub,title,delTitle,state,thumb,avatar}；父级持有数据，这里只管画 */
  resolve: { type: Function, required: true }
})
const emit = defineEmits(['update:modelValue', 'update:keys', 'input', 'keydown', 'paste', 'click', 'focus', 'blur', 'chip-click', 'ready'])

const rootEl = ref(null)
const icHost = ref(null)
const composing = ref(false)
const hover = reactive({ on: false, src: '', name: '', x: 0, y: 0 })
/** 最近一次向外发出的正文：用来区分「父级改了值」和「我们在里面打的字」 */
let lastEmit = ''

const isChip = n => !!(n && n.nodeType === 1 && n.classList && n.classList.contains('cchip'))
const chipNodes = () => Array.from(rootEl.value ? rootEl.value.querySelectorAll('.cchip') : [])
const domKeys = () => chipNodes().map(n => n.dataset.key)

/** 编辑区里除了文本节点与 chip 不该有别的结构（回车与粘贴都由我们自己插），
 *  所以序列化只需两件事：文本节点取字符，BR 记一个换行，chip 记零个字符。 */
const serialize = () => {
  let s = ''
  const walk = n => {
    for (const c of n.childNodes) {
      if (c.nodeType === 3) s += c.nodeValue
      else if (c.nodeType === 1) {
        if (isChip(c)) continue
        if (c.nodeName === 'BR') s += '\n'
        else walk(c)
      }
    }
  }
  if (rootEl.value) walk(rootEl.value)
  return s
}

/** 光标在正文里的下标（chip 算零宽，所以与 serialize 的口径天然对齐）；焦点不在本框返回 -1 */
const caretIndex = () => {
  const el = rootEl.value
  const sel = window.getSelection()
  if (!el || !sel || !sel.rangeCount) return -1
  const r = sel.getRangeAt(0)
  if (!el.contains(r.startContainer)) return -1
  let i = 0
  let found = -1
  const walk = n => {
    for (const c of n.childNodes) {
      if (found >= 0) return
      if (c === r.startContainer) {
        found = c.nodeType === 3 ? i + Math.min(r.startOffset, c.nodeValue.length) : i
        return
      }
      if (c.nodeType === 3) i += c.nodeValue.length
      else if (c.nodeType === 1) {
        if (isChip(c)) continue
        if (c.nodeName === 'BR') i += 1
        else { walk(c); if (found >= 0) return }
      }
    }
  }
  walk(el)
  return found < 0 ? i : found
}

/** 正文下标 → DOM 位置；越过 chip 边界时落在它前面（chip 不可切入内部） */
const posToPoint = pos => {
  const el = rootEl.value
  let left = Math.max(0, pos)
  let hit = null
  const walk = n => {
    for (const c of n.childNodes) {
      if (hit) return
      if (c.nodeType === 3) {
        const len = c.nodeValue.length
        if (left <= len) { hit = { node: c, offset: left }; return }
        left -= len
      } else if (c.nodeType === 1) {
        if (isChip(c) || c.nodeName === 'BR') {
          if (left <= 0) { hit = { before: c }; return }
          if (c.nodeName === 'BR') left -= 1
          continue
        }
        walk(c)
      }
    }
  }
  walk(el)
  return hit
}
const setCaret = pos => {
  const el = rootEl.value
  if (!el) return
  const p = posToPoint(pos)
  const range = document.createRange()
  if (p && p.node) range.setStart(p.node, p.offset)
  else if (p && p.before) range.setStartBefore(p.before)
  else { range.selectNodeContents(el); range.collapse(false) }
  range.collapse(true)
  const sel = window.getSelection()
  sel.removeAllRanges()
  sel.addRange(range)
}

/** 选区 [from,to) 交给浏览器的插入路径：撤销栈与 IME 状态归它管，手改 DOM 会把 Ctrl+Z 打乱 */
const replaceRange = (from, to, str) => {
  const el = rootEl.value
  if (!el) return
  el.focus()
  const a = posToPoint(from)
  const b = posToPoint(to)
  if (a && b) {
    const range = document.createRange()
    if (a.node) range.setStart(a.node, a.offset); else range.setStartBefore(a.before)
    if (b.node) range.setEnd(b.node, b.offset); else range.setEndBefore(b.before)
    const sel = window.getSelection()
    sel.removeAllRanges()
    sel.addRange(range)
  }
  // 带换行的内容必须自己插文本节点（pre-wrap 会把 \n 画成换行）：Chrome 的 insertText 会把 \n
  // 变成 <div> 块结构，两行正文在序列化时就粘成一行发出去了
  const ok = str.indexOf('\n') >= 0 ? false
    : (str ? document.execCommand('insertText', false, str) : document.execCommand('delete'))
  if (!ok) {
    const sel = window.getSelection()
    if (!sel.rangeCount) return
    const r = sel.getRangeAt(0)
    r.deleteContents()
    if (str) {
      const tn = document.createTextNode(str)
      r.insertNode(tn)
      const after = document.createRange()
      after.setStart(tn, str.length)
      after.collapse(true)
      sel.removeAllRanges()
      sel.addRange(after)
    }
  }
  afterEdit()
}
const insertText = str => {
  const i = caretIndex()
  replaceRange(i < 0 ? serialize().length : i, i < 0 ? serialize().length : i, str)
}

// ==================== chip 的画与对账 ====================
const iconOf = kind => {
  const host = icHost.value
  if (!host) return null
  // 找不到图标源就退回文件图标：宁可画一个不像的，也不要画一个空洞（缺图标不报错，最难发现）
  const src = host.querySelector(`[data-ic="${kind}"]`) || host.querySelector('[data-ic="file"]')
  if (!src) return null
  const node = src.cloneNode(true)
  node.removeAttribute('data-ic')
  node.classList.add('cchip-ic')
  return node
}
const buildChip = key => {
  const d = props.resolve(key) || { kind: 'file', name: key }
  const el = document.createElement('span')
  el.className = 'cchip cchip-' + d.kind + (d.mod ? ' m-' + d.mod : '') + (d.state ? ' is-' + d.state : '')
  el.contentEditable = 'false'
  el.dataset.key = key
  el.dataset.kind = d.kind
  el.draggable = false
  if (d.title) el.title = d.title
  if (d.kind === 'img' && d.thumb) {
    const im = document.createElement('img')
    im.className = 'cchip-thumb'
    im.src = d.thumb
    im.alt = d.name || ''
    el.appendChild(im)
  } else if (d.avatar) {
    const av = document.createElement('span')
    av.className = 'cchip-ava'
    av.textContent = d.avatar
    el.appendChild(av)
  } else {
    const ic = iconOf(d.icon || d.kind)
    if (ic) el.appendChild(ic)
  }
  const nm = document.createElement('span')
  nm.className = 'cchip-name'
  nm.textContent = d.name || ''
  el.appendChild(nm)
  const sub = document.createElement('span')
  sub.className = 'cchip-sub'
  sub.textContent = d.sub || ''
  el.appendChild(sub)
  const del = document.createElement('span')
  del.className = 'cchip-del'
  del.dataset.del = key
  del.title = d.delTitle || '移除'
  del.textContent = '×'
  el.appendChild(del)
  return el
}
/** 数据变了（上传进度/失败/体积回来）只重画内部，不动节点位置——换节点会把光标弹走 */
const repaintChip = (node, key) => {
  const fresh = buildChip(key)
  node.className = fresh.className
  node.title = fresh.title
  node.replaceChildren(...Array.from(fresh.childNodes))
}
/** 数组 → DOM：多出来的摘掉、缺的插到光标处。返回 DOM 里的最终顺序 */
const sync = wantKeys => {
  const el = rootEl.value
  if (!el) return []
  const want = new Set(wantKeys)
  chipNodes().forEach(n => { if (!want.has(n.dataset.key)) n.remove() })
  syncEmpty()
  const have = new Set(domKeys())
  const add = (wantKeys || []).filter(k => !have.has(k))
  if (!add.length) return domKeys()
  const i = caretIndex()
  // 有光标就插在光标处，没有（焦点在面板搜索框上）就接到末尾；连插多颗逐颗往后接，
  // 否则第二颗会插到第一颗前面，顺序与数组相反
  const point = i < 0 ? null : posToPoint(i)
  let last = null
  add.forEach(k => {
    const node = buildChip(k)
    if (last) last.insertAdjacentElement('afterend', node)
    else if (!point) el.appendChild(node)
    else if (point.node) {
      const r = document.createRange()
      r.setStart(point.node, Math.min(point.offset, point.node.nodeValue.length))
      r.collapse(true)
      r.insertNode(node)
    } else el.insertBefore(node, point.before)
    last = node
  })
  // 光标跟到最后一颗之后：否则用户接着打字会打到 chip 前面去（实测踩过：附件 chip 后面的字跑到了它左边）
  if (i >= 0 && last) {
    const r = document.createRange()
    r.setStartAfter(last)
    r.collapse(true)
    const sel = window.getSelection()
    sel.removeAllRanges()
    sel.addRange(r)
  }
  syncEmpty()
  return domKeys()
}
const repaint = () => chipNodes().forEach(n => repaintChip(n, n.dataset.key))
/** Chrome 会在末尾留一个 `<br>` 当空行占位，它不是内容：留着就会被序列化成一行换行，于是「空框」不算空——
 *  占位文案不回来，看着像提示语被吃掉了。自己插的换行是文本节点里的 \n，不受这一步影响。 */
const pruneTrailingBreak = () => {
  const el = rootEl.value
  if (!el) return
  const focused = document.activeElement === el
  let n = el.lastChild
  let removed = false
  while (n && n.nodeName === 'BR') { const dead = n; n = n.previousSibling; dead.remove(); removed = true }
  if (removed && focused) setCaret(serialize().length)
}
/** 重算「空态」：先把 Chrome 留的空行占位 <br> 扫掉再判，否则空框会被算成「有一行换行」。
 *  所有改动路径都走这里（sync / afterEdit / setText），空态只有一份口径。 */
const syncEmpty = () => {
  const el = rootEl.value
  if (!el) return
  pruneTrailingBreak()
  el.classList.toggle('is-empty', !serialize() && !chipNodes().length)
}

/** 光标紧邻的那颗 chip（前向/后向各一个方向）。空文本节点要跳过——execCommand 与粘贴会在 chip 旁边
 *  留下零长文本节点，Chrome 的退格遇到这种形态不整颗删 chip，而是插一个 <br>（实测）。 */
const chipAdjacent = dir => {
  const el = rootEl.value
  const sel = window.getSelection()
  if (!el || !sel || !sel.rangeCount) return null
  const r = sel.getRangeAt(0)
  if (!r.collapsed) return null
  let node = null
  if (r.startContainer === el) node = el.childNodes[dir < 0 ? r.startOffset - 1 : r.startOffset]
  else if (r.startContainer.nodeType === 3) {
    if (dir < 0 && r.startOffset > 0) return null      // 落在文本中间：这一下该删字符
    node = dir < 0 ? r.startContainer.previousSibling : r.startContainer.nextSibling
  }
  while (node && node.nodeType === 3 && !node.nodeValue) node = dir < 0 ? node.previousSibling : node.nextSibling
  return isChip(node) ? node : null
}
/** 退格/删除落在 chip 边界时自己摘掉整颗，并让父级按 key 剪枝数组 */
const deleteAdjacentChip = dir => {
  const node = chipAdjacent(dir)
  if (!node) return false
  node.remove()
  afterEdit()
  return true
}
/** 把浏览器塞进来的 <div>/<p> 摊平成「文本节点 + \n」。
 *  必须摊平：序列化只认文本节点与换行，留着 div 就会把两行正文粘成一行发出去。 */
const flattenBlocks = () => {
  const el = rootEl.value
  let changed = false
  Array.from(el.children).forEach(c => {
    if (c.nodeName !== 'DIV' && c.nodeName !== 'P') return
    const frag = document.createDocumentFragment()
    if (el.firstChild !== c) frag.appendChild(document.createTextNode('\n'))
    while (c.firstChild) frag.appendChild(c.firstChild)
    el.replaceChild(frag, c)
    changed = true
  })
  return changed
}
/** 零长文本节点没有信息量，只会让光标与序列化口径分叉，改动之后统一扫掉 */
const pruneEmptyText = () => {
  const el = rootEl.value
  const anchor = window.getSelection()?.anchorNode
  Array.from(el.childNodes).forEach(n => { if (n.nodeType === 3 && !n.nodeValue && n !== anchor) n.remove() })
}
/** 一切改动之后：把正文与 key 顺序报给父级，并转一个 input 事件（面板筛选词靠它实时取） */
const afterEdit = () => {
  const el = rootEl.value
  if (!el) return
  // 摊平可能一次不够（块里还有块），但浏览器造的层级就一层，给三次上限
  for (let i = 0; i < 3 && flattenBlocks(); i++) { /* 继续摊 */ }
  pruneEmptyText()
  syncEmpty()          // 顺带扫掉 Chrome 的空行占位 <br>：先扫再序列化，t 才是真正文
  const t = serialize()
  const empty = !t && !chipNodes().length
  if (empty && el.firstChild) el.innerHTML = ''   // 清干净，否则 :empty 兜不住浏览器留下的结构，占位文案不出来
  if (t !== lastEmit) { lastEmit = t; emit('update:modelValue', t) }
  emit('update:keys', domKeys())
  emit('input')
}

// ==================== 事件 ====================
const onKeydown = e => {
  // 输入法组合中（中文候选未上屏）一律放行：这一刻 DOM 里是拼音串，重画或序列化都会打断上屏
  if (e.isComposing || e.keyCode === 229) return
  if (e.key === 'Enter') {
    // 两种 Enter 都不让浏览器自己处理：它会在编辑区里建 <div> 结构，正文与 chip 的位置关系跟着变乱
    e.preventDefault()
    if (e.shiftKey) insertText('\n')
    else emit('keydown', e)   // 交回父级决定：面板开着先确认高亮项，否则发送
    return
  }
  // chip 的删除必须自己接管（浏览器在这上面不可靠）：摘掉节点后走同一条剪枝通道，等于取消那条引用
  if (e.key === 'Backspace' && deleteAdjacentChip(-1)) { e.preventDefault(); return }
  if (e.key === 'Delete' && deleteAdjacentChip(1)) { e.preventDefault(); return }
  emit('keydown', e)
}
const onInput = () => { if (!composing.value) afterEdit() }
const onCompositionend = () => { composing.value = false; afterEdit() }
const onCompositionstart = () => { composing.value = true }

/** 复制：正文走 text/plain（与发出去的一字不差），条目走 text/html 里的 data-wenqu-* 凭据。
 *  走 copy 事件的 setData 而不是 ClipboardItem——后者写文件受浏览器 MIME 白名单限制（写 pdf 会被拒），
 *  而粘回本应用只需要一个我们能自己认出来的标记，附件字节本来就在服务器上。 */
const onCopy = e => {
  const sel = window.getSelection()
  if (!sel || !sel.rangeCount || e.clipboardData == null) return
  const r = sel.getRangeAt(0)
  const el = rootEl.value
  if (!el.contains(r.commonAncestorContainer)) return
  let text = ''
  let html = ''
  let touched = false
  const eat = n => {
    for (const c of n.childNodes) {
      if (c.nodeType === 3) {
        if (!r.intersectsNode(c)) continue
        const from = c === r.startContainer ? r.startOffset : 0
        const to = c === r.endContainer ? r.endOffset : c.nodeValue.length
        const s = c.nodeValue.slice(from, to)
        text += s; html += esc(s); touched = true
      } else if (c.nodeType === 1) {
        if (isChip(c)) {
          if (!r.intersectsNode(c)) continue
          const one = chipToHtml(c.dataset.key)
          if (one) { html += one; touched = true }
          continue
        }
        if (c.nodeName === 'BR') { if (r.intersectsNode(c)) { text += '\n'; html += '<br>'; touched = true }; continue }
        eat(c)
      }
    }
  }
  eat(el)
  if (!touched) return
  e.preventDefault()
  e.clipboardData.setData('text/plain', text)
  e.clipboardData.setData('text/html', html)
}
const esc = s => String(s).replace(/[&<>]/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[ch]))
const attr = s => String(s == null ? '' : s).replace(/[&<>"]/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[ch]))
/** 一颗 chip 的外发形态。file 只带 fileId（字节在服务器上，粘回来按号挂回，不重传）；
 *  图片没有服务端凭据，只能连 data URL 一起搬，超过 2M 字符就放弃还原、保留可见的名字。 */
const chipToHtml = key => {
  const d = props.resolve(key)
  if (!d) return ''
  const p = [`data-wenqu-kind="${attr(d.kind)}"`, `data-wenqu-key="${attr(key)}"`]
  if (d.kind === 'file') p.push(`data-wenqu-file-id="${attr(d.fileId)}"`, `data-wenqu-name="${attr(d.name)}"`,
    `data-wenqu-mime="${attr(d.mime)}"`, `data-wenqu-size="${attr(d.size)}"`)
  else if (d.kind === 'img') {
    p.push(`data-wenqu-name="${attr(d.name)}"`)
    if (d.thumb && String(d.thumb).length < 2000000) p.push(`data-wenqu-dataurl="${attr(d.thumb)}"`)
  } else if (d.kind === 'mention') p.push(`data-wenqu-mtype="${attr(d.mtype)}"`, `data-wenqu-id="${attr(d.id)}"`, `data-wenqu-name="${attr(d.name)}"`)
  else if (d.kind === 'skill') p.push(`data-wenqu-name="${attr(d.name)}"`)
  else if (d.kind === 'hist') p.push(`data-wenqu-id="${attr(d.messageId)}"`, `data-wenqu-name="${attr(d.name)}"`)
  else if (d.kind === 'plan') p.push('data-wenqu-name="计划模式"')
  return `<span class="wenqu-chip" ${p.join(' ')}>${esc(d.name || '')}</span>`
}

/** 图片悬浮预览：浮层用 fixed 定位，避开输入卡片与消息列的 overflow 裁剪 */
let hoverKey = ''
const onMouseover = e => {
  const node = e.target && e.target.closest ? e.target.closest('.cchip-img') : null
  const key = node ? node.dataset.key : ''
  if (key === hoverKey) return
  hoverKey = key
  if (!key) { hover.on = false; return }
  const d = props.resolve(key)
  if (!d || !d.thumb) { hover.on = false; return }
  const r = node.getBoundingClientRect()
  hover.src = d.thumb
  hover.name = d.name || ''
  hover.x = Math.max(12, Math.min(r.left + r.width / 2, window.innerWidth - 150))
  hover.y = r.top
  hover.on = true
}
const onMouseout = e => {
  if (e.relatedTarget && e.relatedTarget.closest && e.relatedTarget.closest('.cchip-img')) return
  hoverKey = ''
  hover.on = false
}
const onClick = e => {
  const del = e.target && e.target.closest ? e.target.closest('.cchip-del') : null
  if (del) {
    e.preventDefault()
    e.stopPropagation()
    const node = del.closest('.cchip')
    if (node) node.remove()
    afterEdit()
    return
  }
  const chip = e.target && e.target.closest ? e.target.closest('.cchip') : null
  if (chip && chip.dataset.kind === 'file') emit('chip-click', chip.dataset.key)
  emit('click', e)
}
/** 粘贴一律拦下来自己处理：浏览器的默认粘贴会把外部 HTML 连样式一起搬进编辑区 */
const onPaste = e => {
  e.preventDefault()
  emit('paste', e)
}
const onFocus = () => emit('focus')
const onBlur = () => { hover.on = false; hoverKey = ''; emit('blur') }
const onScroll = () => { hover.on = false; hoverKey = '' }

watch(() => props.placeholder, () => syncEmpty())
/** 父级改值（发送后清空、插话取回、切会话）：值与我们刚报出去的不一样才算外部改动。
 *  不这么判的话，自己打字时每一次 emit 都会回流回来把 DOM 重写一遍，光标与撤销栈都会散。 */
watch(() => props.modelValue, v => { if (String(v || '') !== lastEmit) setText(v || '') })
/** 挂载/重挂载（提问卡、计划卡会把整块输入区换掉再换回来）：正文从 props 灌回来，
 *  chip 请父级重新推一遍。不这么做就是「打到一半冒出提问卡，答完回来字和附件全没了」。 */
onMounted(() => {
  const el = rootEl.value
  if (!el) return
  if (props.modelValue) setText(props.modelValue)
  else syncEmpty()
  emit('ready')
})
onBeforeUnmount(() => { hover.on = false })

const focus = () => { const el = rootEl.value; if (el) el.focus() }
const isFocused = () => document.activeElement === rootEl.value
/** 外部改值（发送后清空、插话取回、切会话）：chip 保持原位，正文整段换掉 */
const setText = v => {
  const el = rootEl.value
  if (!el) return
  lastEmit = v || ''
  const chips = chipNodes()
  el.innerHTML = ''
  chips.forEach(c => el.appendChild(c))
  if (lastEmit) el.appendChild(document.createTextNode(lastEmit))
  syncEmpty()
  const r = document.createRange()
  r.selectNodeContents(el)
  r.collapse(false)
  const sel = window.getSelection()
  sel.removeAllRanges()
  sel.addRange(r)
}
const clear = () => {
  const el = rootEl.value
  if (!el) return
  el.innerHTML = ''
  lastEmit = ''
  syncEmpty()
  emit('update:keys', [])
}
const blurOut = () => rootEl.value && rootEl.value.blur()

defineExpose({
  rootEl, serialize, caretIndex, setCaret, replaceRange, insertText,
  sync, repaint, afterEdit, focus, blurOut, isFocused, setText, clear, domKeys
})
</script>

<template>
  <div class="composer">
    <!-- 图标源：编辑区由命令式 DOM 画 chip，Vue 的 patch 进不去，所以图标只能从这里 clone。
         写错组件名不报错也不渲染（kebab 静默失配），data-ic 与 resolve 返回的 icon 一一对上才有图。 -->
    <div ref="icHost" class="cchip-src" aria-hidden="true">
      <file-text-outlined data-ic="file" />
      <file-text-outlined data-ic="mention" />
      <database-outlined data-ic="kb" />
      <robot-outlined data-ic="agent" />
      <history-outlined data-ic="hist" />
      <ordered-list-outlined data-ic="plan" />
      <picture-outlined data-ic="img" />
    </div>
    <div ref="rootEl" class="composer-edit" contenteditable="true" spellcheck="false" role="textbox"
         aria-multiline="true" enterkeyhint="send" :data-ph="placeholder" :style="{ maxHeight: 'calc(1.6em * 6)' }"
         @keydown="onKeydown" @input="onInput" @click="onClick" @paste="onPaste" @copy="onCopy"
         @compositionstart="onCompositionstart" @compositionend="onCompositionend"
         @focus="onFocus" @blur="onBlur" @mouseover="onMouseover" @mouseout="onMouseout" @scroll="onScroll"></div>
    <Transition name="cchip-pop">
      <div v-if="hover.on" class="cchip-pop" :style="{ left: hover.x + 'px', top: hover.y + 'px' }">
        <img :src="hover.src" :alt="hover.name" />
      </div>
    </Transition>
  </div>
</template>

<style scoped>
/* chip 是命令式建出来的节点，拿不到 scoped 的 data-v 标记，所以它们的全部样式都包在 :deep() 里
   （挂在这个组件的模板元素之下）。写成普通选择器会静默不生效：颜色、圆角、删除键一个都不长。 */
.composer { position: relative; }
.cchip-src { display: none; }
/* 正文与 chip 同一条流：pre-wrap 让我们自己插进去的 \n 直接成行，不需要 <br> 结构 */
.composer-edit {
  white-space: pre-wrap; overflow-wrap: anywhere; word-break: break-word;
  padding: 6px 4px; font-size: 14px; line-height: 1.6; min-height: calc(1.6em + 12px);
  max-height: calc(1.6em * 6); overflow-y: auto; outline: none; color: var(--app-text);
}
.composer-edit.is-empty::before {
  content: attr(data-ph); color: var(--app-text3); pointer-events: none; user-select: none;
}
:deep(.cchip) {
  display: inline-flex; align-items: center; gap: 4px; max-width: 240px; vertical-align: middle;
  margin: 1px 3px 1px 0; padding: 1px 5px; border-radius: 6px; font-size: 12px; line-height: 18px;
  background: var(--app-accent-weak); color: var(--app-accent); user-select: none; cursor: default;
  border: 1px solid transparent;
}
:deep(.cchip-name) { min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
:deep(.cchip-sub) { flex: none; font-size: 11px; opacity: .75; }
:deep(.cchip-sub:empty) { display: none; }
:deep(.cchip-del) { flex: none; cursor: pointer; font-size: 13px; line-height: 1; opacity: .6; padding: 0 1px; }
:deep(.cchip-del:hover) { opacity: 1; color: var(--app-danger); }
:deep(.cchip-ic) { flex: none; font-size: 12px; display: inline-flex; }
:deep(.cchip-ava) {
  flex: none; width: 15px; height: 15px; border-radius: 4px; font-size: 10px; line-height: 15px;
  text-align: center; background: var(--app-accent); color: var(--app-panel);
}
/* @ 引用三类（知识库/文档/智能体）与气泡上的 chip 同一套配色，靠图标与底色区隔 */
:deep(.cchip.m-kb) { background: var(--app-accent-weak); }
:deep(.cchip.m-doc) { background: var(--app-warn-weak); color: var(--app-warn-text); }
:deep(.cchip.m-agent) { border-color: var(--app-accent); }
:deep(.cchip-hist) { background: var(--app-info-weak); color: var(--app-accent); border-color: var(--app-info-border); }
:deep(.cchip-skill) { background: var(--app-panel-2); color: var(--app-text2); border-color: var(--app-border); }
:deep(.cchip-file.is-err) { background: var(--app-danger-weak); border-color: var(--app-danger-border); color: var(--app-danger-text); }
:deep(.cchip-file.is-expired) { opacity: .6; color: var(--app-text3); }
:deep(.cchip-file.is-uploading) { opacity: .75; }
:deep(.cchip-img) { padding: 1px; background: var(--app-panel-2); }
:deep(.cchip-thumb) {
  display: block; width: 22px; height: 22px; object-fit: cover; border-radius: 4px;
  border: 1px solid var(--app-border); background: var(--app-panel);
}
:deep(.cchip-img .cchip-name) { max-width: 90px; }
/* 悬浮预览：贴 chip 上沿，fixed 跳出输入卡片的 overflow 与消息列的滚动容器 */
.cchip-pop {
  position: fixed; z-index: 60; transform: translate(-50%, -100%); margin-top: -8px;
  padding: 4px; background: var(--app-panel); border: 1px solid var(--app-border);
  border-radius: 10px; box-shadow: var(--app-shadow-lg); pointer-events: none;
}
.cchip-pop img { display: block; max-width: 280px; max-height: 280px; border-radius: 6px; }
.cchip-pop-enter-active, .cchip-pop-leave-active { transition: opacity .12s ease; }
.cchip-pop-enter-from, .cchip-pop-leave-to { opacity: 0; }
</style>
