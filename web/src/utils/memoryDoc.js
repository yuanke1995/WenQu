// MEMORY.md 的结构化解析与序列化。
//
// 背景：MEMORY.md 由框架的 consolidation 按稳定格式生成——「## 小节 / ### 子题 / - 要点」
// （见 MemoryPrompts 的 CONSOLIDATION_RULES：「Keep the existing section headers stable」），
// 裁剪实现 MemoryBudget.trim 也只认 '## ' 小节。个人设置面板因此可以按这个结构做
// 「不碰 Markdown 原文」的可视化编辑：用户面对的是分类与条目文本，不是 md 语法。
//
// 保真原则：解析不丢内容、序列化尽量还原原格式——
//   - 文档头（第一个 '## ' 之前的内容，如 '# MEMORY.md'）原样保留；
//   - '- ' 开头的行 = 条目（编辑时去掉前缀，只给用户看纯文本）；
//   - '### ' 开头 = 小节内的子题；
//   - 其余非空行（如裁剪说明 '> ...'、异常格式）按「原文段」原样保留，可编辑但序列化时不加前缀，
//     保证 round-trip 不丢东西；空行只作分隔，归一化处理。

let seq = 0
/** 条目 id：同一文档内唯一即可（解析与新增共用计数器，避免碰撞） */
export const nextEntryId = () => 'e' + (++seq)

/** 解析 MEMORY.md 文本 → { header, sections:[{ id, title, entries }] }
 *  entry: { kind:'item'|'raw', id, text } 或 { kind:'sub', id, title, items:[{id,text}] } */
export function parseMemoryDoc(text) {
  const doc = { header: '', sections: [] }
  const lines = (text || '').replace(/\r\n/g, '\n').split('\n')

  // 文档头：第一个 '## ' 之前的全部行
  let i = 0
  const head = []
  while (i < lines.length && !lines[i].startsWith('## ')) { head.push(lines[i]); i++ }
  doc.header = head.join('\n').replace(/\n+$/, '')

  let sec = null
  let sub = null
  let rawBuf = []
  const flushRaw = () => {
    if (!rawBuf.length || !sec) { rawBuf = []; return }
    sec.entries.push({ id: nextEntryId(), kind: 'raw', text: rawBuf.join('\n') })
    rawBuf = []
  }
  while (i < lines.length) {
    const line = lines[i]
    if (line.startsWith('## ')) {
      flushRaw()
      sec = { id: nextEntryId(), title: line.slice(3).trim(), entries: [] }
      doc.sections.push(sec)
      sub = null
    } else if (line.startsWith('### ') && sec) {
      flushRaw()
      sub = { id: nextEntryId(), kind: 'sub', title: line.slice(4).trim(), items: [] }
      sec.entries.push(sub)
    } else if (line.trim() === '') {
      flushRaw() // 空行只作分隔：结束一个原文段，序列化时按结构重新补空行
    } else if (/^-\s/.test(line) || line === '-') {
      flushRaw()
      const item = { id: nextEntryId(), kind: 'item', text: line.replace(/^-\s?/, '') }
      ;(sub ? sub.items : sec.entries).push(item)
    } else if (sec) {
      rawBuf.push(line) // 未识别行：连续行合并为一个原文段
    }
    // '## ' 之前不该出现内容（已在文档头里），忽略
    i++
  }
  flushRaw()
  return doc
}

/** 序列化回 MEMORY.md 文本。格式与框架生成的口径一致：
 *  文档头 → 空行 → '## 小节' → 条目（无空行）→ 空行 → '### 子题' → 条目…… */
export function serializeMemoryDoc(doc) {
  if (!doc) return ''
  const out = []
  const header = (doc.header || '').replace(/\s+$/, '')
  if (header) out.push(header)
  for (const sec of doc.sections || []) {
    const entries = sec.entries || []
    if (!(sec.title || '').trim() && !entries.length) continue // 空分类直接丢弃
    out.push('')
    out.push('## ' + ((sec.title || '').trim() || '未命名分类'))
    for (const e of entries) {
      if (e.kind === 'item') {
        if ((e.text || '').trim()) out.push('- ' + e.text) // 空条目丢弃
      } else if (e.kind === 'raw') {
        if ((e.text || '').trim()) out.push(e.text)
      } else if (e.kind === 'sub') {
        const items = (e.items || []).filter(it => (it.text || '').trim())
        if (!items.length && !(e.title || '').trim()) continue
        out.push('')
        out.push('### ' + ((e.title || '').trim() || '未命名小节'))
        for (const it of items) out.push('- ' + it.text)
      }
    }
  }
  return out.join('\n')
}
