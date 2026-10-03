/**
 * Token 估算（仅前端展示量级：块大小、文档体积），不作为计费/预算依据。
 * 后端上下文预算已用 jtokkit(cl100k BPE) 精确计数（TokenCounter），与本启发式数字略有出入——
 * 展示层不引入 2MB 词表包，保持轻量启发式即可。
 */
export function estimateTokens (text) {
  const s = text || ''
  if (!s) return 0
  let cjk = 0
  let wordChars = 0
  let words = 0
  let other = 0
  const isCjk = c => (c >= 0x4e00 && c <= 0x9fff) || (c >= 0x3400 && c <= 0x4dbf) || (c >= 0xf900 && c <= 0xfaff)
  for (let i = 0; i < s.length; i++) {
    const code = s.charCodeAt(i)
    if (isCjk(code)) {
      cjk++
      if (wordChars > 0) { words++; wordChars = 0 }
    } else if (/[0-9a-zA-Z]/.test(s[i])) {
      wordChars++
    } else {
      if (wordChars > 0) { words++; wordChars = 0 }
      other++
    }
  }
  if (wordChars > 0) words++
  return Math.ceil((cjk * 1.0 + words * 1.3 + other * 0.3) * 1.1)
}

/** 展示用：>=1000 显示为 x.xk */
export function fmtTokens (n) {
  if (n == null) return '—'
  return n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n)
}
