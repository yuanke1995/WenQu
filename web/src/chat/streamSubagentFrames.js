// 子智能体分支步骤帧（纯函数 + 注入）：派遣卡上的「当前步骤」与迷你时间线、完整过程抽屉的帧累积。
// 帧形如 {i, agent, type, content}；i 在咨询这条通路上恒为 0，归行只能认 agent 名字。

/**
 * @param msg 本轮回答消息
 * @param raw 帧载荷
 * @param ctx.isDispatchTool/consultNameOf/stepBriefOf 派遣工具判定、咨询名、步骤摘要（纯函数）
 * @param ctx.onScroll 跟随滚动
 */
export const applySubagentStep = (msg, raw, ctx) => {
  try {
    const o = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (!o || !o.agent) return
    const dispatchOf = x => ctx.isDispatchTool(x.name) && x.status === 'start' && ctx.consultNameOf(x) === o.agent
    // 完整过程的直播帧：关键帧全量累积进派遣卡的 _frames，「完整过程」抽屉直播就吃它。
    // 同一帧可能经两条路各到一遍（SSE 直发 + 总线扇出），按类型+内容前缀去重；上限 240 帧，超出丢最早。
    // tool_status 之外的新帧型（检索/产物/提醒/待裁决）在这里只进抽屉，下面的迷你时间线逻辑不变。
    {
      const all = Array.isArray(msg.toolCalls) ? msg.toolCalls : []
      let any = [...all].reverse().find(dispatchOf)
      if (!any) {
        const running = all.filter(x => ctx.isDispatchTool(x.name) && x.status === 'start')
        if (running.length === 1) any = running[0]
      }
      if (any) {
        const content = typeof o.content === 'string' ? o.content : JSON.stringify(o.content ?? '')
        // 早产工具卡（preparing）只是「入参还在生成」的进度帧，不是这一步的正式起止：
        // 进抽屉会与随后的 start/done 各画一行，一次调用变成三帧（start 行还永远停在转圈）
        let preparing = false
        if (o.type === 'tool_status') {
          try {
            const pc = typeof o.content === 'string' ? JSON.parse(o.content) : o.content
            preparing = !!(pc && pc.preparing)
          } catch (e) { /* 载荷残缺：当正式帧处理 */ }
        }
        if (preparing) {
          // 进度仍要更新卡头那行「当前步骤」，但不进完整过程抽屉
          const card = [...(Array.isArray(msg.toolCalls) ? msg.toolCalls : [])].reverse().find(dispatchOf)
          if (card) {
            try {
              const pc = typeof o.content === 'string' ? JSON.parse(o.content) : o.content
              card._live = ctx.stepBriefOf(pc)
              ctx.onScroll()
            } catch (e) { /* 忽略 */ }
          }
          return
        }
        const key = (o.type || '') + '|' + content.slice(0, 200)
        any._frameKeys = any._frameKeys instanceof Set ? any._frameKeys : new Set()
        if (!any._frameKeys.has(key)) {
          any._frameKeys.add(key)
          any._frames = Array.isArray(any._frames) ? any._frames : []
          any._frames.push({ t: o.type || '', c: content })
          if (any._frames.length > 240) any._frames.splice(0, any._frames.length - 240)
          ctx.onScroll()
        }
      }
    }
    if (o.type !== 'tool_status' || !o.agent) return
    const c = typeof o.content === 'string' ? JSON.parse(o.content) : o.content
    if (!c || !c.name) return
    const terminal = c.status === 'done' || c.status === 'error'
    if (c.status !== 'start' && !terminal) return
    const list = Array.isArray(msg.toolCalls) ? msg.toolCalls : []
    let card = [...list].reverse().find(dispatchOf)
    // 名字查不到（助手清单那条旁路没落到）时，只有一支在跑才敢认领：两支并发认领错就是张冠李戴
    if (!card) {
      const running = list.filter(x => ctx.isDispatchTool(x.name) && x.status === 'start')
      if (running.length === 1) card = running[0]
    }
    if (!card) return
    if (c.status === 'start') {
      card._live = ctx.stepBriefOf(c)
      return
    }
    card._steps = Array.isArray(card._steps) ? card._steps : []
    card._steps.push({
      name: String(c.name || ''),
      label: String(c.label || ''),
      ms: Number(c.elapsedMs) || 0,
      denied: false,
      st: c.status === 'error' ? 'error' : 'done'
    })
    // 与后端 subChat.steps 同一把尺：时间线只留最近 20 步，更早的看步数就够了
    if (card._steps.length > 20) card._steps.splice(0, card._steps.length - 20)
    if (card._live) card._live = ''
  } catch (e) { /* 忽略 */ }
}
