// 引用角标联动与行跳转（视图胶水，DOM 可注入）：正文角标高亮/闪烁定位、来源行点击、计划行跳转。
/**
 * @param ctx.doc document 等价物（测试注入）
 * @param ctx.schedule 定时器（测试注入）
 * @param ctx.messagesOf () => 消息列表
 * @param ctx.lastAiOf () => 最后一条已完成回答
 */
export const useSourceFocus = ({ hoveredRef, doc = document, schedule = setTimeout, messagesOf, lastAiOf }) => {
  const msgIndexOfLastAi = () => {
    const last = lastAiOf()
    return last ? messagesOf().indexOf(last) : -1
  }
  const setSupHighlight = (refN, on) => {
    const idx = msgIndexOfLastAi()
    if (idx < 0) return
    doc.querySelectorAll('.md[data-msg-index="' + idx + '"] .ref-sup[data-ref="' + refN + '"]')
      .forEach(el => el.classList.toggle('ref-hl', on))
  }
  const hoverSource = s => { hoveredRef.value = s.ref; setSupHighlight(s.ref, true) }
  const unhoverSource = s => {
    if (hoveredRef.value === s.ref) hoveredRef.value = null
    setSupHighlight(s.ref, false)
  }
  // 行首角标号：正文有对应角标 → 滚动定位并闪烁；没有（片段未被回答引用）→ 退回打开原文弹窗。
  // 整行本身一律开原文弹窗——用户点这一行要的是「看这段的原文」，不是被弹回正文再看一遍。
  const locateSource = (s, openSource) => {
    const idx = msgIndexOfLastAi()
    const el = idx >= 0 ? doc.querySelector('.md[data-msg-index="' + idx + '"] .ref-sup[data-ref="' + s.ref + '"]') : null
    if (el) {
      el.scrollIntoView({ block: 'center', behavior: 'smooth' })
      el.classList.remove('ref-flash')
      void el.offsetWidth // 强制 reflow 重启动画
      el.classList.add('ref-flash')
      schedule(() => el.classList.remove('ref-flash'), 1600)
    } else {
      openSource(s)
    }
  }
  /** 计划行跳转：滚到该行并闪一圈（同一行连点也能重播） */
  const jumpToPlanRow = mi => {
    const el = doc.querySelector('.row[data-row-index="' + mi + '"]')
    if (!el) return
    el.scrollIntoView({ block: 'center', behavior: 'smooth' })
    el.classList.remove('plan-jump')
    void el.offsetWidth
    el.classList.add('plan-jump')
    schedule(() => el.classList.remove('plan-jump'), 1500)
  }
  return { hoverSource, unhoverSource, locateSource, jumpToPlanRow }
}
