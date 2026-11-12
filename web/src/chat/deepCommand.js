/**
 * 深度执行命令：`/deep` 与 `/深度` 是同一个入口，可带一句任务；没有任务时=上膛（下一句回车即执行）。
 * `/plan`、`/goal` 与其中文形式不再识别——单一入口是产品收敛的一部分，不做别名兼容。
 */
const DEEP = /^\/(?:deep|深度)(?:\s+([\s\S]*))?$/i

/** @returns {{matched:boolean, task:string}} task 取命令后的原文（保留大小写与用词），无参数时为空串 */
export function parseDeepCommand(raw) {
  const text = String(raw == null ? '' : raw).trim()
  if (!text.startsWith('/')) return { matched: false, task: '' }
  const m = text.match(DEEP)
  if (!m) return { matched: false, task: '' }
  return { matched: true, task: (m[1] || '').trim() }
}
