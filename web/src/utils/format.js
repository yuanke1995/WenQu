/** 文件体积的展示口径（列表、附件 chip、交付包共用）：1 KB 以下按字节，其余保留一位小数。 */
export const fmtSize = n => {
  if (!n && n !== 0) return ''
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(1) + ' MB'
}
