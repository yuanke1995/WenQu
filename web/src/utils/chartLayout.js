// ECharts 顶部「标题 + 图例」的版式校正（纯函数，不依赖渲染框架）。
//
// 背景：模型生成的统计图常用 title.subtext 塞口径/日期，又把 legend.top 写成 42~44，
// 以为标题只占一行；实际标题块从画布顶算起是 top(默认 15) + padding(5) + 标题行(18)
// + itemGap(10) + 副标题行(12) ≈ 60px，图例正好压在「口径：…」那行上糊成一团。
// 这里按标题块真实高度把图例推到它下方：只在真的叠上时才动，且只往下让，不会把模型
// 摆得更高的图例拉回来；图例底边压到绘图区时再把 grid.top 一起下压。
//
// 行高取法跟 zrender 一致：显式 lineHeight > fontSize（zrender 拿「国」字宽当行高，约等于字号）。

const DEFAULT_TITLE_FONT = 18
const DEFAULT_SUBTEXT_FONT = 12
const DEFAULT_LEGEND_FONT = 12
const DEFAULT_LEGEND_ITEM_H = 14
const DEFAULT_TITLE_TOP = 15  // ECharts title 默认 top = tokens.size.m
const DEFAULT_GRID_TOP = 65   // ECharts grid 默认 top
const HEADER_PAD = 5          // ECharts title/legend 默认 padding
const HEADER_GAP = 8          // 标题块与图例、图例与绘图区之间留的呼吸
const TIGHT_TITLE_TOP = 6     // 无显式 top 时标题上提的用量（画布外已有卡片留白）

const numOr = (v, d) => (typeof v === 'number' && isFinite(v) ? v : d)

const lineHeight = (style, fallback) => {
  const lh = numOr(style && style.lineHeight, 0)
  return lh > 0 ? lh : numOr(style && style.fontSize, fallback)
}

const lineCount = v => String(v == null ? '' : v).split('\n').length

const paddingTop = pad => (Array.isArray(pad) ? numOr(pad[0], HEADER_PAD) : numOr(pad, HEADER_PAD))

const titleBlockHeight = t => {
  const sub = t.subtext == null ? '' : String(t.subtext).trim()
  const textH = lineHeight(t.textStyle, DEFAULT_TITLE_FONT) * lineCount(t.text)
  if (!sub) return textH
  return textH + numOr(t.itemGap, 10) + lineHeight(t.subtextStyle, DEFAULT_SUBTEXT_FONT) * lineCount(t.subtext)
}

const legendBlockHeight = l =>
  Math.max(lineHeight(l.textStyle, DEFAULT_LEGEND_FONT), numOr(l.itemHeight, DEFAULT_LEGEND_ITEM_H)) + HEADER_PAD * 2

const nonTop = v => v === 'middle' || v === 'center' || v === 'bottom'

/**
 * 就地修正 option 的顶部排版，返回同一个对象。
 *
 * @param {object} opt 已解析的 ECharts option
 * @param {number} height 画布像素高度（百分比定位要靠它换算；拿不到就整体不动）
 */
export const fixChartHeader = (opt, height) => {
  if (!opt || typeof opt !== 'object' || Array.isArray(opt)) return opt
  const t = opt.title
  const l = opt.legend
  if (!t || !l || Array.isArray(t) || Array.isArray(l)) return opt
  if (t.show === false || l.show === false) return opt
  // 沉底锚定、竖排图例都不是「顶部标题 + 横排图例」的布局，原样保留
  if (t.bottom != null || l.bottom != null || l.orient === 'vertical') return opt
  if (nonTop(t.top) || nonTop(l.top)) return opt
  if (!(height > 0)) return opt

  const px = v => {
    if (typeof v === 'number' && isFinite(v)) return v
    if (typeof v === 'string' && v.trim().endsWith('%')) {
      const p = parseFloat(v)
      return isFinite(p) ? height * p / 100 : null
    }
    return null
  }

  let titleTop = px(t.top)
  const autoTitleTop = titleTop == null
  if (autoTitleTop) titleTop = DEFAULT_TITLE_TOP
  const blockBottom = titleTop + paddingTop(t.padding) + titleBlockHeight(t)
  let legendTop = numOr(px(l.top), 0)

  if (legendTop >= blockBottom) return opt   // 图例本来就在标题块下方，一字不动
  if (autoTitleTop) {
    // 只上提默认位置的标题：画布外已经有一圈卡片留白，标题内部再留 20px 太浪费
    t.top = TIGHT_TITLE_TOP
    legendTop = TIGHT_TITLE_TOP + paddingTop(t.padding) + titleBlockHeight(t) + HEADER_GAP
  } else {
    legendTop = blockBottom + HEADER_GAP
  }
  l.top = Math.round(legendTop)

  const legendBottom = legendTop + legendBlockHeight(l) + HEADER_GAP
  if (!opt.grid && !opt.xAxis) return opt   // 饼图/日历图这类没有绘图区，图例让开标题就够了
  if (Array.isArray(opt.grid)) return opt   // 多绘图区各自摆位，交给模型自己负责
  const g = opt.grid || (opt.grid = {})
  const gridTop = numOr(px(g.top), DEFAULT_GRID_TOP)
  if (gridTop >= legendBottom) return opt
  const next = Math.round(legendBottom)
  // 原本按百分比摆的绘图区继续按百分比给，换端改高度时跟着缩放
  g.top = typeof g.top === 'string' && g.top.trim().endsWith('%') ? +(next / height * 100).toFixed(2) + '%' : next
  return opt
}
