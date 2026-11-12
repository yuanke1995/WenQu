// 消息文本的展示口径：历史列表标题/摘要、时间、错误分类文案。纯函数，不读 .value。

export const histItemTitle = m => String((m && m.content) || '').replace(/[#*`>\-\n]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 60)

export const histItemDigest = histItemTitle

export const fmtMsgTime = ts => {
  if (!ts) return ''
  const d = new Date(ts)
  if (isNaN(d.getTime())) return ''
  const now = new Date()
  const hm = String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0')
  if (d.toDateString() === now.toDateString()) return '今天 ' + hm
  const yest = new Date(now)
  yest.setDate(now.getDate() - 1)
  if (d.toDateString() === yest.toDateString()) return '昨天 ' + hm
  return String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0') + ' ' + hm
}

/**
 * 错误分类文案。kind 由 api.js 给出（'spoken'=服务端挑好的人话，'interrupted'=断线/超时/连接被掐断），
 * 优先于按文本猜测：中断类在后端已把半程回答按截断态落库，文案要说明内容没丢。
 */
export const errorBrief = (raw, kind) => {
  const s = String(raw || '')
  if (kind === 'spoken') return s.trim() || '生成失败，可重试或更换模型'
  if (kind === 'interrupted') {
    if (/timeout|timed?\s*out|长时间未收到响应/i.test(s)) return '响应超时：连接已中断'
    if (/提前关闭/.test(s)) return '连接已断开：回答未正常结束'
    return '连接已中断：回答未正常结束'
  }
  if (/AbortError|aborted?/i.test(s)) return '生成已停止'
  // 额度不足必须在 429 分支之前判：额度耗尽常返回 429 + insufficient_quota，
  // 先命中「限流」会给出「等一会就好」的误导建议——额度问题等多久都不会自己好
  if (/额度不足|余额不足|套餐已到期|配额耗尽|insufficient.?quota|billing|payment required|arrearage|out of credit|insufficient balance/i.test(s)) {
    return '模型服务额度不足：请到「模型供应商」充值，或改用其他模型'
  }
  if (/timeout|timed?\s*out/i.test(s)) return '请求超时：模型服务响应过慢或网络不稳定，可重试'
  if (/Failed to fetch|NetworkError|network/i.test(s)) return '网络连接失败：请检查网络或代理设置'
  if (/401|Unauthorized/i.test(s)) return '鉴权失败：登录已过期，请重新登录'
  if (/429|rate\s*limit/i.test(s)) return '请求过于频繁：模型服务限流，稍后重试'
  if (/5\d{2}|Bad Gateway|Service Unavailable/i.test(s)) return '模型服务异常：稍后重试，或到「模型供应商」检查该供应商状态'
  return '生成失败，可重试或更换模型'
}
