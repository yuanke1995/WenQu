// 产物「文本缩略」的两个纯函数：不依赖接口与浏览器，便于单测。
// 对话产物卡与「我的产物」网格共用这一份口径。

/** 把原文洗成缩略行：保留标题重字，去掉 Markdown 结构字符与分隔线 */
export const pickPreviewLines = (raw, max = 4) => {
  const out = []
  for (const line of String(raw || '').split(/\r?\n/)) {
    let t = line.trim()
    if (!t) continue
    const head = /^#{1,6}\s/.test(t)
    t = t.replace(/^#{1,6}\s+/, '').replace(/^[-*+>]\s+/, '').replace(/^\d+\.\s+/, '')
    if (/^[:|\-\s]+$/.test(t)) continue
    t = t.replace(/\*\*/g, '').replace(/`/g, '')
    if (t) out.push({ text: t, head })
    if (out.length >= max) break
  }
  return out
}

/**
 * 表单形 JSON：只认「components 里有一张带 matrix 的 BasicTable」这一条结构特征。
 * 命中就画迷你表格骨架；解析失败/结构不符一律返回 false，由调用方退回文本行。
 * 这里刻意不解析字段值与合并单元格——缩略只是示意，真内容交给预览台。
 */
export const looksLikeFormJson = raw => {
  const text = String(raw || '').trim()
  if (!text.startsWith('{') && !text.startsWith('[')) return false
  try {
    const obj = JSON.parse(text)
    const comps = obj && Array.isArray(obj.components) ? obj.components : null
    return !!comps && comps.some(c => c && (c.name === 'BasicTable'
      || (c.instance && Array.isArray(c.instance.matrix))))
  } catch (e) {
    return false
  }
}
