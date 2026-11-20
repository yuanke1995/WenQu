/**
 * 下载文件名净化：与后端 FileNameSanitizer 同口径。
 * 平台判断只用于文件名规则，功能逻辑不依赖 UA。
 */
const ILLEGAL = /[\\/:*?"<>|\x00-\x1f]/g
const RESERVED = new Set([
  'CON', 'PRN', 'AUX', 'NUL',
  ...Array.from({ length: 9 }, (_, i) => `COM${i + 1}`),
  ...Array.from({ length: 9 }, (_, i) => `LPT${i + 1}`)
])

export function sanitizeFileName (name, maxLen = 120) {
  let s = String(name == null ? '' : name).trim().normalize('NFC')
  s = s.replace(ILLEGAL, '_')
  while (s.endsWith('.') || s.endsWith(' ')) s = s.slice(0, -1)
  if (!s || s === '.' || s === '..') s = 'unnamed'
  const stem = s.split('.')[0]
  if (RESERVED.has(stem.toUpperCase())) s = '_' + s
  const limit = Math.max(1, maxLen)
  if (s.length > limit) s = s.slice(0, limit)
  while (s.endsWith('.') || s.endsWith(' ')) s = s.slice(0, -1)
  return s || 'unnamed'
}
