/** 文件体积的展示口径（列表、附件 chip、交付包共用）：1 KB 以下按字节，其余保留一位小数。 */
export const fmtSize = n => {
  if (!n && n !== 0) return ''
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(1) + ' MB'
}

/** 展示类时间统一 locale，避免同一页面在不同系统语言下格式漂移。 */
export function fmtDate (ts) {
  try { return new Date(ts).toLocaleDateString('zh-CN') } catch (e) { return '' }
}

export function fmtDateTime (ts) {
  try { return new Date(ts).toLocaleString('zh-CN', { hour12: false }) } catch (e) { return '' }
}

/**
 * 表格里「只要到分钟」的时钟口径：后端返回 ISO 串时直接截断，不做时区换算。
 *
 * 用一个 len 参数收口两种精度（16=到分钟、19=到秒），避免各页面自己 slice 出两套口径。
 *
 * 与 {@link fmtDateTime} 的区别：那个走 toLocaleString（跟随本地时区与语言），适合「给人看的详情」；
 * 这个保持后端原样，适合「列表里要对齐、要可排序」的场景。两者不要互相替代。
 *
 * @param ts ISO 时间串或 Date
 * @param len 16 = 到分钟（yyyy-MM-dd HH:mm）；19 = 到秒
 * @param empty 空值占位，默认 '—'
 */
export function fmtClock (ts, len = 16, empty = '—') {
  if (ts === null || ts === undefined || ts === '') return empty
  const s = ts instanceof Date ? ts.toISOString() : String(ts)
  return s.replace('T', ' ').slice(0, len)
}
