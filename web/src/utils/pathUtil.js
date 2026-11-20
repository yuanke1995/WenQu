/** API 返回的路径可能来自本机执行器（Windows 用反斜杠），统一成 POSIX 形态再折目录。 */
export const toPosix = p => String(p == null ? '' : p).replace(/\\/g, '/')

export const normalizeApiPath = toPosix

export const baseNameOf = p => {
  const parts = toPosix(p).split('/').filter(Boolean)
  return parts.length ? parts[parts.length - 1] : ''
}

export const dirOf = p => {
  const s = toPosix(p)
  const i = s.lastIndexOf('/')
  return i < 0 ? '' : s.slice(0, i)
}
