// run_code 扇出看板与运行中水位：纯展示判据，不读 .value。
import { toolDuration } from './tools.js'

export const fanHasBoard = f => !!f && ((Number(f.total) || 0) > 0 || (Number(f.inFlight) || 0) > 0)

export const fanHead = t => {
  const f = t.fanOut
  if (!fanHasBoard(f)) return ''
  if (t.status === 'start') {
    const run = Number(f.inFlight) || 0
    return `子调用 ${Number(f.total) || 0} 次${run ? ' · 在途 ' + run : ''}`
  }
  return `扇出 ${Number(f.total) || 0} 次`
}

export const fanTitle = f => `扇出 ${Number(f.total) || 0} 次工具调用` +
  ((Number(f.inFlight) || 0) > 0 ? `（还有 ${f.inFlight} 条在跑）` : '')

export const fanRowState = s => s.status === 'start' ? `在跑×${Number(s.running) || 1}` : toolDuration(s.elapsedMs)

/** 收尾类型：正常结束不额外刷屏，只把「没跑成」的三种说出来。 */
export const fanRun = t => {
  const r = t.run
  if (!r || r.kind === 'ok') return ''
  if (r.kind === 'exit') return `退出码 ${r.exit}：脚本可能没跑完`
  if (r.kind === 'timeout') return '追踪超时：脚本可能还在后台跑'
  if (r.kind === 'infra') return '执行环境不可用（不是脚本写错）'
  return '运行未完成'
}

/** 并发水位总览：不点开卡片也能看出跑了多少、在途几条。 */
export const runBoardText = m => {
  const f = m && m.runBoard
  const total = Number(f && f.total) || 0
  const run = Number(f && f.inFlight) || 0
  if (!total && !run) return ''
  const bits = [`子调用 ${total}/${Number(f.max) || '?'} 次`]
  if (run) bits.push(`在途 ${run}`)
  if (Number(f.delivered) > 0) bits.push(`交付 ${f.delivered} 个文件`)
  return '脚本运行中 · ' + bits.join(' · ')
}

export const fanBudget = f => {
  if (!f || !f.max) return ''
  const bits = [`本轮额度 ${Number(f.used) || 0}/${f.max}`]
  if (f.parallel) bits.push(`并发上限 ${f.parallel}`)
  if (f.delivered) bits.push(`交付 ${f.delivered} 个文件`)
  return bits.join(' · ')
}

export const groupRunning = g => g.tools.some(t => t.status === 'start')
export const groupHasError = g => g.tools.some(t => t.status === 'error')
