// 计划正文归一化成**字符串**：卡片主体最终交给 renderMd 渲染，喂进去非字符串就是屏幕上那个
// `[object Object]`（框架 plan_exit 的入参只有一句 summary，后端把整个入参对象当 plan 下发）。
// 这里做最后一道闸——对象按常见字段名（content/plan/text/summary）取值，都取不到才退成可读的 JSON，
// 绝不把对象直接透传给渲染层。
export const planText = v => {
  if (v == null) return ''
  if (typeof v === 'string') return v
  if (Array.isArray(v)) return v.map(planText).filter(Boolean).join('\n')
  if (typeof v === 'object') {
    for (const k of ['content', 'plan', 'text', 'summary']) {
      const t = planText(v[k])
      if (t) return t
    }
    try { return JSON.stringify(v, null, 2) } catch (e) { return '' }
  }
  return String(v)
}
