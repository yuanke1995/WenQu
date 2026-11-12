// 交付包的聚合口径：一组产物收成一个包（入口、分组、体量），卡片与抽屉都吃这一份。
// 纯函数、不读 .value，可在 Node 里直接测；深度执行再在包上面加版本与验收。
import { fmtSize } from '../utils/format.js'

/** 产物在交付包里的角色：决定分组与卡片标签（入口会由 deliveryOf 改写） */
export const deliveryTagOf = filename => {
  const name = String(filename || '')
  if (/^(robots\.txt|sitemap\.xml|favicon\.(svg|ico|png))$/i.test(name)) return '站点文件'
  if (/\.(svg|ico|webmanifest)$/i.test(name) || /\.xml$/i.test(name)) return '站点文件'
  if (/\.html?$/i.test(name)) return '页面'
  if (/\.(md|markdown|pdf|docx|txt)$/i.test(name)) return '文档'
  return '文件'
}
/** 主入口：index.html → README → 任一 html → 第一件（没有入口也允许，不是所有交付都有站点） */
export const deliveryEntryOf = files => {
  const list = files || []
  return list.find(f => /^index\.html?$/i.test(String(f.filename || ''))) ||
    list.find(f => /^readme\.(md|markdown|txt)$/i.test(String(f.filename || ''))) ||
    list.find(f => /\.html?$/i.test(String(f.filename || ''))) ||
    list[0] || null
}
/** 交付包名的主语：目标原文/问题去掉指令词与后半句（"做一个问渠官网，…" → "问渠官网"） */
export const deliverySubjectOf = text => {
  let head = String(text || '').split(/[，,。；;：:\n]/)[0].trim()
  for (let i = 0; i < 3; i++) {
    head = head.replace(/^(请|帮我|帮忙|给我|做一个|做个|写一个|写个|生成|搭建|创建|设计|实现)/, '').trim()
  }
  return head.slice(0, 24)
}
/**
 * 产物清单 → 交付包视图（卡片、抽屉、H5 汇总同吃这一份）。
 * meta 由调用方给：name / versionLabel / round / stateLabel / badgeTone / approved /
 * turnIds / anchorKey / scope / shared。单文件也能成包——要不要画成卡片由调用方定。
 */
export const deliveryOf = (files, meta = {}) => {
  const raw = (files || []).filter(f => f && (f.url || f.seq != null))
  if (!raw.length) return null
  const withTag = raw.map(f => ({ ...f, tag: deliveryTagOf(f.filename) }))
  const entry = deliveryEntryOf(withTag)
  if (entry) entry.tag = '入口'
  const total = withTag.reduce((s, f) => s + (Number(f.size) || 0), 0)
  const keyOf = f => f
    ? (f.id != null ? 'id:' + f.id : (f.seq != null ? 'seq:' + f.seq : (f.url || 'name:' + f.filename)))
    : ''
  const pages = withTag.filter(f => f.tag === '页面' && (!entry || keyOf(f) !== keyOf(entry)))
  const docs = withTag.filter(f => f.tag === '文档')
  const site = withTag.filter(f => f.tag === '站点文件')
  const other = withTag.filter(f => f.tag === '文件')
  const groups = [
    { key: 'entry', label: '入口', files: entry ? [entry] : [], closed: false },
    { key: 'pages', label: '内容页', files: pages, closed: false },
    { key: 'docs', label: '文档', files: docs, closed: false },
    { key: 'site', label: '站点文件', files: site, closed: true },
    { key: 'other', label: '其他', files: other, closed: true }
  ].filter(g => g.files.length)
  return {
    ...meta,
    entry,
    files: withTag,
    fileCount: withTag.length,
    sizeLabel: fmtSize(total),
    pageCount: pages.length,
    siteCount: site.length,
    groups,
    turnIds: meta.turnIds || [],
    anchorKey: meta.anchorKey || '',
    stateLabel: meta.stateLabel || '',
    badgeTone: meta.badgeTone || ''
  }
}

/** 交付包是否值得支一张卡：现在单件也成卡，只要包里有文件 */
export const deliveryCardOf = d => (d && Array.isArray(d.files) && d.files.length ? d : null)
