// 上下文窗口与用量的展示口径：窗口缩写、本轮占用、悬停明细。
// 三个数各起各的名、各占一行，谁也不跟谁相加（占用 ≠ 累计送入 ≠ 输出）。
import { fmtTokens } from '../utils/token.js'

/** 模型窗口缩写：1M/128K 这类官方口径优先，否则按两种进制整除情况回落。 */
export const fmtWindow = n => {
  if (!n || n <= 0) return ''
  for (const base of [1048576, 1000000]) {
    if (n >= base) {
      const m = n / base
      if (m % 1 === 0) return m + 'M'
      if (m < 10 && (m * 10) % 1 === 0) return m.toFixed(1) + 'M'
    }
  }
  if (n >= 1024) {
    if (n % 1000 === 0) return n / 1000 + 'K'
    if (n % 1024 === 0) return n / 1024 + 'K'
    const k = n / 1024
    return (k >= 100 ? Math.round(k) : Math.round(k * 10) / 10) + 'K'
  }
  return String(n)
}

/** 本轮结束时的上下文占用：与容量圆环、右栏用量同一个分子（缺占用才回落计费输入）。 */
export const usageCtxTokens = t => (!t || typeof t !== 'object') ? 0 : (Number(t.context) || Number(t.prompt) || 0)

/** 用量悬停明细：只报名字和数，不写解释——消费方按换行渲染成多行 tooltip。 */
export const usageTip = t => {
  if (!t || typeof t !== 'object') return ''
  const n = v => fmtTokens(Number(v) || 0)
  const segs = [`上下文占用 ${n(usageCtxTokens(t))} tokens`]
  if (Number(t.prompt) > 0) segs.push(`本轮累计送入 ${n(t.prompt)} tokens`)
  segs.push(`输出 ${n(t.output)} tokens`)
  return segs.join('\n')
}
