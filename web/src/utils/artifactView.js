// 产物能不能当文档在浏览器里打开。判据只有这一份：产物页与聊天区（PC 气泡、PC 右栏、H5 消息行、
// H5 引用面板）共用——同一段文本在不同页面给出不同的按钮集，用户只会当成随机行为。
//
// 清单按无头 Edge 实测维护（顶层导航，响应头与产物静态路一致）：
//   csv（text/csv）、xlsx/xls/zip 一律落下载，给「预览」按钮就是骗人；
//   md/markdown/txt/log/py/java/json/html/htm 渲染成文本或页面，png/jpg/jpeg/svg 渲染成图，pdf 用内置阅读器。
// md/markdown/py 能开起来靠服务端补的 Content-Type（见 ImageWebConfig），少了它中文会按单字节解成乱码。
const DOWNLOAD_ONLY = new Set(['csv', 'xlsx', 'xls', 'zip'])

/** 产物扩展名（小写，不带点）：优先用服务端给的 ext，历史消息里没有就取文件名后缀 */
export const artifactExt = a => {
  if (!a) return ''
  const ext = String(a.ext || '')
  if (ext) return ext.replace(/^\./, '').toLowerCase()
  const name = String(a.filename || '')
  const i = name.lastIndexOf('.')
  return i > 0 ? name.slice(i + 1).toLowerCase() : ''
}

export const canPreviewArtifact = a => !!a && !!a.url && !DOWNLOAD_ONLY.has(artifactExt(a))
