/** 人读时长：<1s 说「不到 1 秒」，秒级留一位小数，≥60s 说「N 分 M 秒」。 */
export const durZh = ms => {
  const n = Math.max(0, Number(ms) || 0)
  if (n < 1000) return '不到 1 秒'
  const tenths = Math.round(n / 100)
  if (tenths < 600) return (tenths % 10 ? (tenths / 10).toFixed(1) : String(tenths / 10)) + ' 秒'
  const secs = Math.round(tenths / 10)
  if (secs < 3600) {
    const s = secs % 60
    return Math.floor(secs / 60) + ' 分' + (s ? ' ' + s + ' 秒' : '')
  }
  const m = Math.round(secs / 60) % 60
  return Math.floor(Math.round(secs / 60) / 60) + ' 小时' + (m ? ' ' + m + ' 分' : '')
}
