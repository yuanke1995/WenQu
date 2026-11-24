import { sanitizeFileName } from './fileName.js'

/**
 * 跨源签名地址的 <a download> 会被浏览器忽略（规范只对同源 / blob / data 生效），
 * 结果就是"点了下载却直接跳到文件"。这里在 document 级统一拦截跨源下载，改为
 * fetch → blob → a[download]，同源与 blob/data 不动，保持原生行为。
 */
export function shouldInterceptDownload (href, pageOrigin) {
  if (!href) return false
  if (href.startsWith('blob:') || href.startsWith('data:') || href.startsWith('#')) return false
  let u
  try { u = new URL(href, pageOrigin) } catch { return false }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') return false
  return u.origin !== pageOrigin
}

export async function downloadFromUrl (url, filename) {
  const res = await fetch(url, { credentials: 'include' })
  if (!res.ok) throw new Error(`下载失败(${res.status})`)
  downloadBlob(await res.blob(), filename || 'download')
}

/**
 * 把一段内存里的内容直接存成文件（导出 CSV / Markdown / JSON 等）。
 *
 * 内存内容落盘的唯一入口。内联 write 极易分叉出三套撤销时机（立即 / 1000ms / 2000ms），
 * 其中立即 revoke 会让 Firefox / Safari 的异步取流中断；从已改写的 a.href 读回对象再 revoke
 * 更是直接 revoke 错对象。
 *
 * @param content 文件内容（string / Blob / ArrayBuffer）
 * @param filename 文件名（会过 sanitizeFileName）
 * @param type 非 Blob 内容时的 MIME，默认纯文本 UTF-8
 */
export function downloadBlob (content, filename, type = 'text/plain;charset=utf-8') {
  const blob = content instanceof Blob ? content : new Blob([content], { type })
  const objectUrl = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = objectUrl
  a.download = sanitizeFileName(filename || 'download')
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
  // 立即 revoke 在部分浏览器会让下载中断（Firefox / Safari 异步取流），故延迟统一 2000ms。
  setTimeout(() => URL.revokeObjectURL(objectUrl), 2000)
}

export function installDownloadInterceptor () {
  if (typeof document === 'undefined') return
  document.addEventListener('click', ev => {
    if (ev.defaultPrevented || ev.button !== 0 || ev.metaKey || ev.ctrlKey || ev.shiftKey || ev.altKey) return
    const a = ev.target && ev.target.closest ? ev.target.closest('a[download]') : null
    if (!a) return
    const href = a.getAttribute('href') || a.href
    if (!shouldInterceptDownload(href, window.location.origin)) return
    ev.preventDefault()
    const filename = a.getAttribute('download') || ''
    downloadFromUrl(href, filename).catch(() => {
      // 签名地址不允许 CORS 时退回浏览器原生导航（文件仍能打开，只是文件名由服务端决定）
      window.location.assign(href)
    })
  }, true)
}
